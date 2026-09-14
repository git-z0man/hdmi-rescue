package com.steffenzimmermann.braviafix

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
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
/// ⚠️ **This app cannot promise to repair the second case, and it does not claim to.** Switching
/// inputs it can do for certain (rung 1); killing the service it can only *attempt* (rung 2) —
/// `killBackgroundProcesses` reaches background processes only, and a bound service often is not
/// one. When that fails it says what to do instead, rather than pretending it fixed anything.
class MainActivity : Activity() {

    private lateinit var log: TextView
    private var first: Button? = null
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
        val restart = button("Restart the input service (attempt)") {
            // The contract says: "Have the system immediately kill all background processes
            // associated with the given package" — *background*. Whether the bound input service
            // counts is the system's call, not ours.
            val outcome = runCatching {
                getSystemService(ActivityManager::class.java).killBackgroundProcesses(INPUT_SERVICE_PKG)
            }.fold(
                { "Attempted. Now pick the input above once more.\n\nIf it stays black this app" },
                { "Failed: ${it.javaClass.simpleName}.\n\nThis app" },
            )
            say(
                "$outcome can do no more, and only these are left:\n" +
                    "• switch the television off and on again, or\n" +
                    "• from a computer:  ./scripts/hdmi-rescue.sh fix"
            )
        }
        root.addView(restart)
        first = first ?: restart

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
        // The same route the launcher takes when it switches inputs:
        // `TvContract.buildChannelUriForPassthroughInput` builds
        // `content://android.media.tv/passthrough/<inputId>` (TvContract.java:473-489), and a
        // VIEW intent on it needs no permission.
        val uri = TvContract.buildChannelUriForPassthroughInput(info.id)
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            .onFailure { say("Could not switch: ${it.javaClass.simpleName}") }
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
