# HDMI Rescue

A Sony Bravia that shows a **black HDMI input and no "No signal" message** is almost never a
cable fault. This repository holds the two shapes of the same repair: an app that sits on the
television and is driven with the remote, and a shell script for when a computer is at hand.

Built for a **Sony Bravia XR-75X90K** (Android 12, MediaTek MT5835) but nothing in it is
model-specific except the input ids in the script's comments — the app reads the input list from
the system.

## The fault

Sony's `com.sony.dtv.tvinput.external` starts with the television and sometimes fails to connect
to the HardwareManager. Every input switch then ends here:

```
E/TIS_BuiltinTisBase_EX: syncAcquireHardware. acquireHardware() HardwareManagerService is not connected.
I/TIS_BuiltinTisBase_EX: it does not notify video available because stream is not opened.
```

The input stays black — **without** a "no signal" message, because the set never goes looking for
a signal in the first place. Everything else looks healthy, which is what makes it so
misleading: the port reports `connected`, CEC lists the device by name and as the active source,
and the input banner appears normally.

Switching inputs, re-tuning and toggling CEC change nothing; they all end in the same failed
`acquireHardware`. Only restarting the service helps — or restarting the whole television, which
is the usual ritual and the reason it "eventually comes back".

Proven on 2026-09-11 with a PlayStation 5 on HDMI 4: after the service restart the log went from
that error to `notifyHardwareAvailable`, and the picture came back at 3840×2160@60.

## The app

```bash
./gradlew :app:assembleRelease
adb connect 10.1.1.22:5555
adb -s 10.1.1.22:5555 install -r app/build/outputs/apk/release/app-release.apk
```

It appears on the Google TV home screen as **HDMI Rescue**.

1. Pick the affected input — occupied ports are listed first and one holds focus on open.
2. If the picture stays away, press **Repair — restart the input service**. It switches back to
   the input by itself once the service is up again.

| | |
|---|---|
| Switching inputs | **certain.** A VIEW intent on `content://android.media.tv/passthrough/<inputId>` — the same route the launcher takes, and it needs no permission (`TvContract.java:473-489`). |
| Restarting the service | **the real repair, with one prerequisite.** The app connects to the television's own adb daemon on `127.0.0.1:5555` and runs `am force-stop` there. That shell is uid 2000 and holds `FORCE_STOP_PACKAGES`; the app itself never could. |

No app can stop that service directly. `killBackgroundProcesses` stops at oom priority 500,
while a service holding TV hardware sits at 100 — the system binds it with
`BIND_FOREGROUND_SERVICE_WHILE_AWAKE` for as long as it exists — and `forceStopPackage` is
signature-only. Hence the detour through adb.

**What it needs:** ADB debugging switched on at the television (developer options → Debugging →
USB debugging; on a set with no USB device port, that switch is what opens port 5555), and the
"Allow debugging?" dialog confirmed once with the remote, with *Always allow* ticked. The app
reads the setting on start and offers a button straight into the developer options when it is off.

**Restarting the whole television is not a substitute.** The fault is a race at boot, so a reboot
only rolls the same dice again — which is why it "sometimes" helps and often does not.

## The script

```bash
./scripts/hdmi-rescue.sh status   # ports, CEC, last tune — and the hardware line
./scripts/hdmi-rescue.sh fix      # re-tune, and restart the service only if that was not enough
```

`fix` checks the log for `notifyHardwareAvailable` after re-tuning and stops as soon as that is
enough. Set `BRAVIA=<host:port>` to point it at a different set.

⚠️ **The script never sends a key to the television.** Everything it reads is `dumpsys` and
`logcat`; the input is chosen through the passthrough intent. The input switch still changes what
is on screen, so it is not something to run over someone's shoulder unannounced.

## Environment

- JDK 26 (Homebrew); `./gradlew` needs `JAVA_HOME` set or a wrapper script that sets it.
- `local.properties` carries `sdk.dir`; it is git-ignored.
- `compileSdk = 37`, `compileSdkMinor = 2` — AGP 9.4 requires the minor, and the package is
  called `platforms;android-37.2`.
- One dependency: `dev.mobile:dadb` for the adb connection. Speaking the protocol by hand means an
  RSA handshake and a packet format — far more code than the library. It publishes Java 17
  metadata, hence `compileOptions` in `app/build.gradle.kts`.

## Where it came from

The app and script were built inside [zplayer-tv](../zplayer-tv) while chasing a black HDMI 4 and
moved here once they turned out to have nothing to do with the player. The application id still
reads `com.steffenzimmermann.braviafix`, the name it shipped under, so an install upgrades the
copy already on the television instead of leaving an orphan beside it.
