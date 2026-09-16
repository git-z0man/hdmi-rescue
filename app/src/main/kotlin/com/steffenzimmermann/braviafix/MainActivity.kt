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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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
    private var first: Button? = null
    private var lastInput: TvInputInfo? = null
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

        // ⚠️ **The repair needs ADB debugging, and most televisions ship with it off.** The key is
        // readable without any permission (`@Readable` since Android 12), so the app can say so
        // instead of letting the button fail later.
        if (Settings.Global.getInt(contentResolver, Settings.Global.ADB_ENABLED, 0) == 0) {
            root.addView(
                hint(
                    "ADB debugging is switched off, and the repair cannot work without it. In the " +
                        "developer options: Debugging → USB debugging. On a television that is what " +
                        "opens the port this app talks to."
                )
            )
            root.addView(button("Open the developer options") { openDeveloperOptions() })
        }

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
    private fun repair() {
        say(
            "Restarting the input service…\n\nIf the television asks whether to allow debugging, " +
                "say yes and tick “Always allow”."
        )
        Thread {
            val result = adbShell(this, "am force-stop $INPUT_SERVICE_PKG")
            Thread.sleep(3_000)
            runOnUiThread {
                result.fold(
                    {
                        val back = lastInput
                        if (back == null) say("The service was restarted. Now pick the input above.")
                        else {
                            say("The service was restarted — switching back to ${label(back)}.")
                            switchTo(back)
                        }
                    },
                    {
                        say(
                            "No adb connection: ${it.javaClass.simpleName}: ${it.message}\n\n" +
                                "Check that ADB debugging is on, or repair it from a computer:\n" +
                                "./scripts/hdmi-rescue.sh fix"
                        )
                    },
                )
            }
        }.start()
    }

    /// ⚠️ On Android 12 TV settings the developer options open straight from this action, master
    /// switch first on the page — no seven taps on the build number. The fallbacks are for a
    /// television that dropped the intent filter: the About page carries that build number.
    private fun openDeveloperOptions() {
        val actions = listOf(
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            Settings.ACTION_DEVICE_INFO_SETTINGS,
            Settings.ACTION_SETTINGS,
        )
        if (actions.none { runCatching { startActivity(Intent(it)) }.isSuccess }) {
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
