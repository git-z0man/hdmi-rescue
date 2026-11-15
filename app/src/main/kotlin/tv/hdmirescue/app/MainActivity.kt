package tv.hdmirescue.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.net.URLDecoder

/// A Bravia input that will not play, fixed from the couch with one press — no computer, no power
/// cord.
///
/// Two faults, both races at boot on a Sony Bravia XR-75X90K, and the button tells them apart:
///
/// 1. **Settings stuck** (2026-11-14): every input throws the player back to the home screen and
///    the picture menu is greyed out — `IAudioPictureSetting is not ready yet`. Sony's vendor
///    service lost Bravia Sync and is restarted, see [settingsStuck].
/// 2. **Input black** (2026-09-11): Sony's `com.sony.dtv.tvinput.external` failed to reach the
///    HardwareManager, so every input stays black *without* a "no signal" message:
///
///        E/TIS_BuiltinTisBase_EX: syncAcquireHardware. acquireHardware() HardwareManagerService
///                                 is not connected.
///
///    The input service is restarted, see [repair].
///
/// Then the television goes back to the input that failed, and the screen says why it broke, see
/// [cause]. Both restarts go through adb, see [adbShell]. Restarting the whole television is *not*
/// a rung: a reboot only rolls the same dice again.
class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var setup: Button
    private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(32), dp(48), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "HDMI Rescue"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        })
        root.addView(hint("Input jumps back, stays black, or the picture menu is greyed out? Press Repair."))

        // ⚠️ **The one button holds focus on open.** This screen is opened for exactly one press,
        // and a remote that needs a DOWN first looks like the next defect. `post`, because a node
        // cannot take focus before the first layout pass.
        val repair = button("Repair") { repair() }
        root.addView(repair)
        repair.post { repair.requestFocus() }

        log = hint("").apply { setTextColor(ACCENT) }
        root.addView(log)

        // Only shown when adb cannot be reached: the repair needs ADB debugging, which a
        // television ships without.
        setup = button("Open the developer options") { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            .apply { visibility = View.GONE }
        root.addView(setup)

        // ⚠️ **No button for this, on purpose.** When the system holds a dead binding to the input
        // service, every input disappears and nothing reachable from software clears it
        // (TvInputManagerService.java:746, :2935). Seen on 2026-09-18; only the plug helped, and
        // Sony documents the same step (support article 00114591).
        root.addView(
            hint(
                "No inputs listed at all, not even in the television's own settings? Then pull out " +
                    "the mains plug, press the power button once, wait two minutes, plug it back in."
            )
        )

        setContentView(root.apply { setBackgroundColor(BACKGROUND) })
    }

    /// Find which of the two faults it is, restart the matching service, then go back to the input
    /// that failed. Off the main thread — this opens a socket and may sit through a dialog.
    ///
    /// ⚠️ **The input service only while the system holds a live connection to it.** If that
    /// binding dies while the system is still setting it up, `onBindingDied` arrives instead of
    /// `onServiceDisconnected`, only the second clears the flag that permits a new bind
    /// (TvInputManagerService.java:746, :2935), and *every* input disappears for good. So the
    /// connection is read first, the inputs are counted afterwards, and a second press while one
    /// is running is ignored.
    private fun repair() {
        if (busy) return
        busy = true
        setup.visibility = View.GONE
        say("Repairing…\n\nIf the television asks whether to allow debugging, say yes and tick “Always allow”.")
        Thread {
            // The patient call first, so the debugging dialog is out of the way; then the input,
            // read before anything restarts while the log still names the one that failed.
            val stuck = settingsStuck()
            val back = lastInput()
            val connected = if (stuck == true) null else serviceConnected()
            val result = when {
                stuck == true -> adbShell(this, "am crash $VENDOR_SERVICE_PKG")
                connected == null -> Result.failure(IOException("no adb connection"))
                !connected -> Result.failure(IllegalStateException("service not connected"))
                else -> adbShell(this, "am force-stop $INPUT_SERVICE_PKG")
            }
            // The vendor service needs longer: it restarts and binds its five setting modules anew.
            Thread.sleep(if (stuck == true) 10_000 else 4_000)
            val inputs = if (result.isSuccess) registeredInputs() else -1
            val why = if (result.isSuccess || connected == false) cause() else ""
            runOnUiThread {
                busy = false
                when {
                    connected == false -> say(
                        "The television is not holding a live connection to its input service — " +
                            "restarting it now would strand the inputs completely. Pull the plug, see below.$why"
                    )
                    result.isFailure -> {
                        say(
                            "No adb connection (${result.exceptionOrNull()?.message}).\n\nSwitch on " +
                                "Developer options → Debugging → ADB debugging. No developer options? " +
                                "Settings → System → About → select “Android TV OS build” seven times."
                        )
                        setup.visibility = View.VISIBLE
                        setup.requestFocus()
                    }
                    inputs == 0 -> say(
                        "The service was stopped, but the television has not registered its inputs " +
                            "again. Pull the plug, see below.$why"
                    )
                    back == null -> say("Repaired. Now pick your input.$why")
                    else -> {
                        say("Repaired — switching back.$why")
                        switchTo(back)
                    }
                }
            }
        }.start()
    }

    /// Why it broke, as far as the set can tell — appended to the result, empty when nothing shows.
    ///
    /// Established on 2026-11-14: both faults start the same way. While the television boots,
    /// Android stops every app process that has not attached within 10 s ("start timeout" in
    /// `dumpsys activity exit-info`), and Sony never rebinds to the ones it lost. 13–14 services
    /// went that way in a single second on almost every boot — until a USB stick that had been
    /// set up as internal storage (adopted) was turned back into removable
    /// storage. Four boots without it: no start timeout at all.
    private fun cause(): String {
        // `sm list-volumes private` lists internal storage as `private mounted null`; an adopted
        // drive carries its own id instead of `null`.
        val adopted = adbShell(this, "sm list-volumes private", attempts = 1).getOrNull()
            ?.lines()?.any { it.startsWith("private:") && !it.trim().endsWith("null") } == true
        val killed = adbShell(
            this,
            "b=\$(date -d @\$(( \$(date +%s) - \$(cut -d. -f1 /proc/uptime) )) '+%Y-%m-%d %H:%M:%S'); " +
                "dumpsys activity exit-info | awk -v b=\"\$b\" '/timestamp=/{ts=substr(\$1,11)\" \"\$2} " +
                "/description=start timeout/{if (ts >= b) n++} END{print n+0}'",
            attempts = 1,
        ).getOrNull()?.trim()?.toIntOrNull() ?: 0
        return buildString {
            if (killed > 0) {
                append("\n\nCause: $killed services were stopped while the television started up (start timeout).")
            }
            if (adopted) {
                append(
                    "\n\nA USB drive is set up as internal storage — that slowed every start-up down on " +
                        "this television. Settings → System → Storage → the drive → format as removable storage."
                )
            }
        }
    }

    /// The input the television last tried to play, read from the log — the player itself logs the
    /// passthrough URI it was asked for, encoded or not. `null` when the log has none.
    private fun lastInput(): String? =
        adbShell(this, "logcat -d | grep -oE 'content://android.media.tv/passthrough/[^ ,}]+' | tail -1", attempts = 1)
            .getOrNull()?.substringAfter("passthrough/", "")?.trim()
            ?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotEmpty() }

    /// The same route the launcher takes when it switches inputs:
    /// `TvContract.buildChannelUriForPassthroughInput` builds
    /// `content://android.media.tv/passthrough/<inputId>` (TvContract.java:473-489), and a VIEW
    /// intent on it needs no permission.
    private fun switchTo(inputId: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputId)))
        }.onFailure { say("Repaired, but could not switch back: ${it.javaClass.simpleName}. Pick your input.") }
    }

    /// Is the system holding a live connection to Sony's input service? `null` when adb could not
    /// answer at all. `service: null` in the dump means a restart would leave the television with
    /// no inputs.
    private fun serviceConnected(): Boolean? =
        adbShell(this, "dumpsys tv_input | grep -A3 'ExternalTvInputService}' | grep 'service:'")
            .getOrNull()?.let { !it.contains("null") }

    /// Has Sony's vendor service lost Bravia Sync? `null` when adb could not answer.
    ///
    /// Established on 2026-11-14: `com.sony.dtv.sonyvendorservice` reports `IAudioPictureSetting`
    /// ready only once its bindings to `com.sony.dtv.braviasyncservice` connect. When Bravia Sync
    /// is stopped at boot with a start timeout (see [cause]), all five of those bindings end `DEAD`,
    /// Sony handles no `onBindingDied` and never binds again — so the player crashes on every
    /// input with `IAudioPictureSetting is not ready yet`. `am crash` on the persistent vendor
    /// service makes the system restart it, and it binds afresh (`am force-stop` skips persistent
    /// processes).
    ///
    /// ⚠️ **Stuck only when there are records and every one is `DEAD`.** An empty dump (a timeout,
    /// the vendor service mid-restart, another firmware's format) must not count: it would crash a
    /// system service for nothing and never reach the input-service rung.
    ///
    /// ⚠️ `dumpsys activity services` (plural) is the system server's own record and never calls
    /// into the app — unlike `dumpsys activity service`, which crashed Sony's input service.
    private fun settingsStuck(): Boolean? = adbShell(
        this,
        "dumpsys activity services $BRAVIA_SYNC_PKG | grep -B1 'BraviaSyncService:$VENDOR_SERVICE_PKG}' " +
            "| grep ConnectionRecord | awk '/DEAD/{d++} !/DEAD/{l++} END{print (d>0 && l==0) ? \"stuck\" : \"ok\"}'",
    ).getOrNull()?.trim()?.let { it == "stuck" }

    /// How many inputs the *system* has on file for Sony's service — zero means the binding is
    /// stuck, whatever the app's own list says.
    private fun registeredInputs(): Int =
        adbShell(this, "dumpsys tv_input | grep -c 'ExternalTvInputService/'", attempts = 1)
            .getOrNull()?.trim()?.toIntOrNull() ?: 0

    /// ⚠️ An ordinary settings action — no permission, and on this set it resolves into
    /// `com.android.tv.settings`. The fallback is the settings root, for a television whose
    /// developer options are still locked.
    private fun open(action: String) {
        if (runCatching { startActivity(Intent(action)) }.isFailure &&
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.isFailure
        ) {
            say("This television opens none of the settings screens by itself.")
        }
    }

    private fun say(text: String) { log.text = text }

    // ---- building blocks --------------------------------------------------------------------

    private fun hint(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(0, dp(8), 0, dp(8))
    }

    /// ⚠️ **Colours written out, not taken from the theme.** `Theme.DeviceDefault` paints dark
    /// grey on dark grey here, and a button nobody can see from three metres away is not a button.
    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(Color.WHITE)
        setBackgroundColor(SURFACE)
        setPadding(dp(20), dp(16), dp(20), dp(16))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(16); bottomMargin = dp(6) }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, focused ->
            setBackgroundColor(if (focused) ACCENT else SURFACE)
            setTextColor(if (focused) BACKGROUND else Color.WHITE)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INPUT_SERVICE_PKG = "com.sony.dtv.tvinput.external"
        const val VENDOR_SERVICE_PKG = "com.sony.dtv.sonyvendorservice"
        const val BRAVIA_SYNC_PKG = "com.sony.dtv.braviasyncservice"
        val BACKGROUND = Color.parseColor("#0B0E11")
        val SURFACE = Color.parseColor("#1B2026")
        val ACCENT = Color.parseColor("#4FC3F7")
        val MUTED = Color.parseColor("#9AA4AE")
    }
}
