package com.steffenzimmermann.braviafix

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException

/// A black HDMI input, fixed from the couch — no computer, no power cord.
///
/// The fault behind it, established on a Sony Bravia XR-75X90K on 2026-09-11: Sony's
/// `com.sony.dtv.tvinput.external` starts with the television and sometimes fails to connect to
/// the HardwareManager. Every input then stays black — *without* a "no signal" message, because
/// the set never goes looking for a signal in the first place. The port reports `connected`, CEC
/// lists the device by name, the input banner appears; only the picture is missing. The log is
/// where it shows:
///
///     E/TIS_BuiltinTisBase_EX: syncAcquireHardware. acquireHardware() HardwareManagerService
///                              is not connected.
///     I/TIS_BuiltinTisBase_EX: it does not notify video available because stream is not opened.
///
/// Two rungs: switching inputs (a VIEW intent, no permission), and restarting Sony's service over
/// adb when switching is not enough — see [adbShell]. Restarting the whole television is *not* the
/// third rung: the fault is a race at boot, so a reboot only rolls the same dice again.
class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var step1: LinearLayout
    private lateinit var step2: LinearLayout
    private var first: Button? = null
    private var lastInput: TvInputInfo? = null
    private var busy = false
    private val tv by lazy { getSystemService(TvInputManager::class.java) }

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
        root.addView(
            hint(
                "Black input, but no “No signal” message? Then the TV's input service is " +
                    "stuck. Pick the input below first — if that does not help, restart the service."
            )
        )

        root.addView(section("Pick an input"))
        // The list comes from the system rather than a hard-coded table: only the television
        // knows which id hides behind "HDMI 4" — on this set it is `…/HW5`, with `…/HDMI400004`
        // sitting next to it for the CEC device on the same port.
        //
        // ⚠️ **The empty case belongs here too.** On a device without pass-through inputs (an
        // emulator, say) a heading would otherwise stand above nothing, and the screen would look
        // broken rather than empty.
        val ports = inputs()
        if (ports.isEmpty()) root.addView(hint("This device reports no HDMI inputs."))
        else ports.forEach { info -> root.addView(inputButton(info).also { first = first ?: it }) }

        root.addView(section("If that is not enough"))
        val repair = button("Repair — restart the input service") { repair() }
        root.addView(repair)
        first = first ?: repair

        // ⚠️ **No button here, on purpose.** Restarting the system service (`am restart`) hung on
        // 2026-09-18, and the reboot that followed brought the input list back but left Sony's own
        // services half-started: every input then threw the player back to the home screen with
        // `IAudioPictureSetting is not ready yet`. Only cutting the power repaired that, and Sony
        // documents it themselves (support article 00114591).
        root.addView(
            hint(
                "No inputs listed above at all, not even in the television's own settings? Then no " +
                    "software can help — the system is holding a dead connection to its input " +
                    "service and will not let go. Pull out the mains plug, press the power button " +
                    "on the television once, wait two minutes, plug it back in."
            )
        )

        // The repair needs ADB debugging, which a television ships without. Rather than let the
        // button fail later, the app checks and says which of the two steps is still missing.
        root.addView(section("Repair function"))
        status = hint("").apply { setTextColor(ACCENT) }
        root.addView(status)
        root.addView(button("Check the repair function") { check(patient = true) })

        // ⚠️ Two switches, so two steps: unlocking the developer options does *not* switch ADB
        // debugging on. Each block hides on its own, so a screen read in an emergency carries the
        // one step that is still missing rather than a setup guide.
        step1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // ⚠️ The button can only reach the page, not the line: no intent addresses a single
            // settings entry, so the entry is named here instead. It is the last one on the page,
            // below the kernel version — out of sight until you scroll.
            addView(
                hint(
                    "Step 1 — unlock the developer options: on the page that opens, scroll to the " +
                        "very bottom, to “Android TV OS build” (“Android TV-Betriebssystem-Build”), " +
                        "and select it seven times, until the television says you are a developer."
                )
            )
            addView(button("Step 1 — open the About page") { open(Settings.ACTION_DEVICE_INFO_SETTINGS) })
        }
        step2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(
                hint(
                    "Step 2 — in the developer options: Debugging → ADB debugging. On a television " +
                        "with no USB device port, that switch is what opens the port this app uses."
                )
            )
            addView(
                button("Step 2 — open the developer options") {
                    open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                }
            )
        }
        root.addView(step1)
        root.addView(step2)

        log = hint("").apply { setTextColor(ACCENT) }
        root.addView(log)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            addView(root, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })

        // ⚠️ **Something has to hold focus.** Google's TV guidance says so in general, and here
        // in particular: this screen gets read when nothing else works. A remote that needs a
        // DOWN press before anything is even highlighted looks like the next defect. `post`,
        // because a node cannot take focus before the first layout pass.
        first?.let { target -> target.post { target.requestFocus() } }
    }

    /// The check belongs here rather than in `onCreate`: this also runs when the user comes back
    /// from the settings, which is exactly when the answer changes.
    ///
    /// ⚠️ One attempt only, unlike the button: with the key not yet authorised [adbShell] waits
    /// out the dialog for half a minute, and nobody opened this screen to watch it think.
    override fun onResume() {
        super.onResume()
        check(patient = false)
    }

    /// Is the repair actually available? Three answers, and each names what to do next: ADB
    /// debugging off, on but no connection (the dialog is waiting, or the key was refused), or
    /// ready. Runs again whenever the user comes back from the settings.
    ///
    /// `patient` is for the button: then the attempt is worth the full waiting time, because
    /// someone is standing by to confirm the dialog with the remote.
    private fun check(patient: Boolean) {
        status.text = "Checking…"
        Thread {
            // Both keys are readable without a permission (`@Readable` since Android 12).
            val unlocked = global(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
            val enabled = global(Settings.Global.ADB_ENABLED)
            // `id` is the smallest command that proves the whole chain: connection, key accepted,
            // and a shell that answers. It should say uid=2000(shell).
            val reply = if (enabled) adbShell(this, "id", attempts = if (patient) 15 else 1) else null
            runOnUiThread {
                when {
                    !unlocked -> state("The developer options are locked — steps 1 and 2 below.", true, true)
                    !enabled -> state("ADB debugging is off — step 2 below.", false, true)
                    reply!!.isSuccess -> state("Ready — the repair button works.", false, false)
                    else -> state(
                        "ADB debugging is on, but there is no connection: " +
                            "${reply.exceptionOrNull()?.message}\n\nIf the television is showing the " +
                            "debugging dialog, allow it and tick “Always allow”, then check again.",
                        false,
                        true,
                    )
                }
            }
        }.start()
    }

    private fun global(key: String) =
        runCatching { Settings.Global.getInt(contentResolver, key, 0) != 0 }.getOrDefault(false)

    private fun state(text: String, one: Boolean, two: Boolean) {
        status.text = text
        step1.visibility = if (one) View.VISIBLE else View.GONE
        step2.visibility = if (two) View.VISIBLE else View.GONE
    }

    /// Pass-through inputs only — HDMI and AV. Everything else (tuners, Play Movies) is not what
    /// this screen is about and would be noise on a page read in an emergency.
    ///
    /// ⚠️ **Occupied ports first.** Sorted by name alone, "AV — nothing connected" came out on
    /// top and took the focus: the first button pressed in an emergency would have been the one
    /// port with certainly nothing behind it.
    private fun inputs(): List<TvInputInfo> = tv.tvInputList
        .filter { it.isPassthroughInput }
        .sortedWith(
            compareBy(
                { if (tv.getInputState(it.id) == TvInputManager.INPUT_STATE_DISCONNECTED) 1 else 0 },
                { label(it) },
            )
        )

    private fun label(info: TvInputInfo): String =
        (info.loadCustomLabel(this) ?: info.loadLabel(this))?.toString() ?: info.id

    private fun stateOf(info: TvInputInfo): String = when (tv.getInputState(info.id)) {
        TvInputManager.INPUT_STATE_CONNECTED -> "in use"
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "in use, standby"
        else -> "nothing connected"
    }

    private fun inputButton(info: TvInputInfo) = button("${label(info)}  —  ${stateOf(info)}") {
        switchTo(info)
    }

    private fun switchTo(info: TvInputInfo) {
        // The same route the launcher takes when it switches inputs:
        // `TvContract.buildChannelUriForPassthroughInput` builds
        // `content://android.media.tv/passthrough/<inputId>` (TvContract.java:473-489), and a
        // VIEW intent on it needs no permission.
        val uri = TvContract.buildChannelUriForPassthroughInput(info.id)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onSuccess { lastInput = info }
            .onFailure { say("Could not switch: ${it.javaClass.simpleName}") }
    }

    /// Stop Sony's service, wait for the system to bring it back, then return to the input that
    /// was black. Off the main thread — this opens a socket and may sit through a dialog.
    ///
    /// ⚠️ **Only while the service is connected.** Killing it is not free: if the binding dies
    /// while the system is still setting it up, `onBindingDied` arrives instead of
    /// `onServiceDisconnected`, and only the second one clears the flag that permits a new bind
    /// (TvInputManagerService.java:746, :2935 — unchanged in Android 13 and 14). The system then
    /// holds a dead binding forever and *every* input disappears, in the launcher and in the
    /// television's own settings. That happened on 2026-09-18, and nothing short of pulling the
    /// plug brought it back. So the connection is read first, the inputs are counted afterwards,
    /// and a second press while one is running is ignored.
    private fun repair() {
        if (busy) return
        busy = true
        say(
            "Restarting the input service…\n\nIf the television asks whether to allow debugging, " +
                "say yes and tick “Always allow”."
        )
        Thread {
            val connected = serviceConnected()
            val result = when {
                connected == null -> Result.failure(IOException("no adb connection"))
                !connected -> Result.failure(IllegalStateException("service not connected"))
                else -> adbShell(this, "am force-stop $INPUT_SERVICE_PKG")
            }
            Thread.sleep(4_000)
            val inputs = if (result.isSuccess) registeredInputs() else -1
            runOnUiThread {
                busy = false
                when {
                    connected == false -> say(
                        "The television is not holding a live connection to its input service — " +
                            "restarting it now would strand the inputs completely. Pull the plug " +
                            "instead, see below."
                    )
                    result.isFailure -> say(
                        "No adb connection: ${result.exceptionOrNull()?.javaClass?.simpleName}: " +
                            "${result.exceptionOrNull()?.message}\n\nCheck the repair function above, " +
                            "or repair it from a computer:  ./scripts/hdmi-rescue.sh fix"
                    )
                    inputs == 0 -> say(
                        "The service was stopped, but the television has not registered its inputs " +
                            "again. Pull the plug, see below."
                    )
                    else -> {
                        val back = lastInput
                        if (back == null) say("The service was restarted. Now pick the input above.")
                        else {
                            say("The service was restarted — switching back to ${label(back)}.")
                            switchTo(back)
                        }
                    }
                }
            }
        }.start()
    }

    /// Is the system holding a live connection to Sony's input service? `null` when adb could not
    /// answer at all. This is the flag the whole repair hinges on: `service: null` in the dump
    /// means a restart would leave the television with no inputs.
    private fun serviceConnected(): Boolean? =
        adbShell(this, "dumpsys tv_input | grep -A3 'ExternalTvInputService}' | grep 'service:'")
            .getOrNull()?.let { !it.contains("null") }

    /// How many inputs the *system* has on file for Sony's service — zero means the binding is
    /// stuck, whatever the app's own list says.
    private fun registeredInputs(): Int =
        adbShell(this, "dumpsys tv_input | grep -c 'ExternalTvInputService/'", attempts = 1)
            .getOrNull()?.trim()?.toIntOrNull() ?: 0

    /// The way out of the stuck binding, short of pulling the plug: restart the Android system
    /// ⚠️ Both setup pages are ordinary settings actions — no permission, and on this set both
    /// resolve into `com.android.tv.settings`. The fallback is the settings root, for a television
    /// that dropped the more specific intent filter.
    private fun open(action: String) {
        if (runCatching { startActivity(Intent(action)) }.isFailure &&
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }.isFailure
        ) {
            say("This television opens none of the settings screens by itself.")
        }
    }

    private fun say(text: String) { log.text = text }

    // ---- building blocks --------------------------------------------------------------------

    private fun section(text: String) = TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(MUTED)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setPadding(0, dp(24), 0, dp(8))
    }

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
            .apply { bottomMargin = dp(6) }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, focused ->
            setBackgroundColor(if (focused) ACCENT else SURFACE)
            setTextColor(if (focused) BACKGROUND else Color.WHITE)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val INPUT_SERVICE_PKG = "com.sony.dtv.tvinput.external"
        val BACKGROUND = Color.parseColor("#0B0E11")
        val SURFACE = Color.parseColor("#1B2026")
        val ACCENT = Color.parseColor("#4FC3F7")
        val MUTED = Color.parseColor("#9AA4AE")
    }
}
