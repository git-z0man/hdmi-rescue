#!/bin/bash
# A black HDMI input on the Bravia — without the power cord and without sending a single key.
#
#   ./scripts/hdmi-rescue.sh status      # read only: ports, CEC, last tune, hardware
#   ./scripts/hdmi-rescue.sh fix         # the recipe that worked on 2026-09-11
#   ./scripts/hdmi-rescue.sh retune      # re-tune HDMI 4 (the raw port)
#   ./scripts/hdmi-rescue.sh retune ps5  # re-tune the CEC entry "PlayStation 5"
#   ./scripts/hdmi-rescue.sh restart-input   # restart the input service
#
# ⚠️ **What actually breaks here** (proven on the device on 2026-09-11): Sony's
# `com.sony.dtv.tvinput.external` starts with the television and sometimes fails to connect to
# the HardwareManager. Every input switch then leaves this in the log:
#
#     E/TIS_BuiltinTisBase_EX: syncAcquireHardware. acquireHardware() HardwareManagerService
#                              is not connected.
#     I/TIS_BuiltinTisBase_EX: it does not notify video available because stream is not opened.
#
# The input stays black — **without** a "no signal" message, because the set never goes looking
# for a signal. CEC still reports the device as the active source, the port still reports
# "connected", and that is exactly why it looks like a cable or CEC fault. Switching inputs,
# re-tuning and toggling CEC change **nothing**: they all end in the same failed
# `acquireHardware`. Only restarting the service helps — or the whole television, which used to
# be the method.
set -euo pipefail
[ -n "${ANDROID_HOME:-}" ] && export PATH="$ANDROID_HOME/platform-tools:$PATH"

# Your television, as `adb connect` wants it. Find the address under Settings → Network.
BRAVIA="${BRAVIA:?set BRAVIA=<host:port>, e.g. BRAVIA=192.168.1.20:5555}"
SVC="com.sony.dtv.tvinput.external"
# ⚠️ The raw port is the sturdier target: `HW5` is "HDMI 4" and depends on no CEC negotiation.
# `HDMI400004` is the CEC *device* on port 4 ("PlayStation 5"), which is what lets the console be
# driven from the TV remote. If one works and the other does not, the fault is CEC, not the signal.
HDMI4="$SVC%2F.ExternalTvInputService%2FHW5"
PS5="$SVC%2F.ExternalTvInputService%2FHDMI400004"

adb connect "$BRAVIA" >/dev/null 2>&1 || true
tv() { adb -s "$BRAVIA" shell "$@"; }

# ⚠️ **Never stop that service unless the system holds a live connection to it.** If the binding
# dies while it is still being set up, the system gets `onBindingDied` instead of
# `onServiceDisconnected`, and only the second one clears the flag that allows a new bind
# (TvInputManagerService.java:2935 and :746 — unchanged through Android 14). It then holds a dead
# binding for good and *every* input disappears, from the launcher and from the television's own
# settings. Seen on 2026-09-18; nothing but pulling the mains plug for two minutes brought it back.
connected() { ! tv "dumpsys tv_input" | grep -A3 "ExternalTvInputService}" | grep -q "service: null"; }

stop_service() {
    if connected; then
        tv "am force-stop $SVC"
    else
        echo "The system holds no live connection to the input service — stopping it now would" >&2
        echo "strand every input. Pull the mains plug, press the power button, wait two minutes." >&2
        return 1
    fi
}

# `TvContract.buildChannelUriForPassthroughInput` builds exactly these two segments
# (android-platform/37.2/media/tv/TvContract.java:473-489, PATH_PASSTHROUGH = "passthrough").
# A VIEW intent on it is what the launcher sends when it switches inputs — no permission, no
# remote control.
retune() {
    local target="$HDMI4"
    [ "${1:-}" = "ps5" ] && target="$PS5"
    tv "am start -a android.intent.action.VIEW -d 'content://android.media.tv/passthrough/$target'"
}

case "${1:-status}" in
status)
    # ⚠️ **Never `dumpsys activity service $SVC`.** Sony's dump throws a NullPointerException
    # (DebugInfoManager.java:141) and takes the whole service down — seen on 2026-09-15, when a
    # `status` run restarted the input as a side effect. `dumpsys tv_input` is the system
    # server's own view and touches the service not at all.
    echo "── Ports (state 0 connected, 1 standby, 2 nothing) ────"
    tv "dumpsys tv_input" | grep -E "tvinput\.external/.*: info: .*state: [0-9]" \
        | sed -E 's#.*/([A-Z]+[0-9]+): info: .*state: ([0-9]).*#\1  state \2#' || true
    echo
    echo "── CEC devices ────────────────────────────────────────"
    tv "dumpsys hdmi_control" | grep -E "display_name|mActiveSource|mArcEstablished" || true
    echo
    echo "── Last tune ──────────────────────────────────────────"
    tv "logcat -d" | grep -E "TIS_BuiltinTisBase_EX: onTune" | tail -1 || true
    echo
    echo "── Hardware (this is where the truth is) ──────────────"
    # `not connected` means the service is stuck and `fix` is due. `notifyHardwareAvailable`
    # means the video plane is there, and a black picture has some other cause.
    tv "logcat -d" | grep -E "acquireHardware|notifyHardwareAvailable|stream is not opened" | tail -5 || true
    echo
    echo "── Signal ─────────────────────────────────────────────"
    tv "logcat -d" | grep -oE "SignalInfo\{[^}]*\}" | tail -1 || true
    ;;
fix)
    # Smallest rung first: when only the session is stale after a hotplug, re-tuning is enough
    # and the service stays up.
    # ⚠️ Only log lines from *after* the re-tune count — an old `notifyHardwareAvailable` from
    # before the fault would otherwise skip the restart. Captured into a variable, not piped into
    # `grep -q`: with `pipefail`, grep leaving early kills adb and turns a hit into a miss.
    since=$(tv "date +'%m-%d %H:%M:%S.000'" | tr -d '\r')
    echo "1/3  re-tuning…";      retune "${2:-}"; sleep 4
    log=$(tv "logcat -d -T '$since'")
    if grep -q "notifyHardwareAvailable" <<<"$log"; then
        echo "     hardware is there — nothing more was needed."
    else
        echo "2/3  restarting the input service…"; stop_service; sleep 4
        echo "3/3  re-tuning once more…";          retune "${2:-}"; sleep 5
    fi
    echo
    tv "logcat -d" | grep -oE "SignalInfo\{[^}]*\}" | tail -1 || true
    echo "Look at the screen."
    ;;
retune)        retune "${2:-}" ;;
restart-input) stop_service && echo "Service stopped — Android restarts it on next use." ;;
*)             echo "unknown: $1" >&2; exit 2 ;;
esac
