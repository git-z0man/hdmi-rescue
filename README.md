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
`acquireHardware`. Restarting the service is what helps. Restarting the whole television is the
usual ritual, but an unreliable one — see below.

Proven on 2026-09-11 with a PlayStation 5 on HDMI 4: after the service restart the log went from
that error to `notifyHardwareAvailable`, and the picture came back at 3840×2160@60.

## The app

```bash
TV=192.168.1.20:5555          # your set's address, from Settings → Network
./gradlew :app:assembleRelease
adb connect "$TV"
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
```

It appears on the Google TV home screen as **HDMI Rescue**.

1. Pick the affected input — occupied ports are listed first and one holds focus on open.
2. If the picture stays away, press **Repair — restart the input service**. It switches back to
   the input by itself once the service is up again.
3. **Check the repair function** says whether the adb route works, and offers the two setup steps
   — unlocking the developer options, and switching ADB debugging on — while they are missing.

| | |
|---|---|
| Switching inputs | **certain.** A VIEW intent on `content://android.media.tv/passthrough/<inputId>` — the same route the launcher takes, and it needs no permission (`TvContract.java:473-489`). |
| Restarting the service | **the real repair, with one prerequisite.** The app connects to the television's own adb daemon on `127.0.0.1:5555` and runs `am force-stop` there. That shell is uid 2000 and holds `FORCE_STOP_PACKAGES`; the app itself never could. |

No app can stop that service directly. `killBackgroundProcesses` stops at oom priority 500,
while a service holding TV hardware sits at 100 — the system binds it with
`BIND_FOREGROUND_SERVICE_WHILE_AWAKE` for as long as it exists — and `forceStopPackage` is
signature-only. Hence the detour through adb.

**What it needs:** ADB debugging switched on at the television (developer options → Debugging →
**ADB debugging**, labelled *USB debugging* on some builds; on a set with no USB device port, that
one switch is what opens port 5555), and the "Allow debugging?" dialog confirmed once with the
remote, with *Always allow* ticked. The app
reads the setting on start and offers a button straight into the developer options when it is off.

**Restarting the whole television is not a substitute.** The fault is a race at boot, so a reboot
only rolls the same dice again — which is why it "sometimes" helps and often does not.

## ⚠️ Read this before you extend the app

`am force-stop` on the input service is safe **only while the system holds a live connection to
it**, and the app checks that before every restart (`dumpsys tv_input`, the `service:` line of the
`ExternalTvInputService` block). Do not remove that check, and do not fire the command blind.

Why: when the binding dies while the system is still setting it up, the client gets
`onBindingDied`, not `onServiceDisconnected`. `TvInputManagerService` clears its `bound` flag only
in the latter (`TvInputManagerService.java:2935`), and refuses to bind again while the flag stands
(`:746`). The system then holds a dead binding for good:

```
service: null, callback: null, bound: true, reconnecting: false
```

and **every** input disappears — from the launcher, from the input menu, and from Settings →
External inputs → HDMI signal format. The bug is in AOSP, unchanged through Android 14, not in
Sony's code. Nothing recovers it from the outside: no package operation, no broadcast, no binder
call reaches that flag, and `am force-stop` cannot either — it is what causes it.

Seen on 2026-09-18, and this is how far the recovery went:

| Attempt | Result |
|---|---|
| `am force-stop` again | inputs stayed gone |
| `am restart` (restart the system service) | logged "Shutting down activity manager…" and hung |
| Reboot | input list back, but Sony's own services came up half-started: every input threw the player back to the home screen with `IAudioPictureSetting is not ready yet` |
| Mains plug out, power button, two minutes, plug in | everything healthy again |

Hence the app's last resort is a sentence, not a button: pull the plug. Sony documents the same
step (support article 00114591), and it is the only one that repaired all of it.

## The script

```bash
export BRAVIA=192.168.1.20:5555   # your set's address
./scripts/hdmi-rescue.sh status   # ports, CEC, last tune — and the hardware line
./scripts/hdmi-rescue.sh fix      # re-tune, and restart the service only if that was not enough
```

`fix` checks the log for `notifyHardwareAvailable` after re-tuning and stops as soon as that is
enough. `BRAVIA=<host:port>` is required and says which set to talk to; `ANDROID_HOME` is only
needed when `adb` is not already on the `PATH`.

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

The app and script were built inside another project of mine, a television player, while chasing a
black HDMI 4, and moved here once they turned out to have nothing to do with the player. The
application id still reads `com.steffenzimmermann.braviafix`, the name it shipped under, so an
install upgrades the copy already on the television instead of leaving an orphan beside it.

## License

MIT — see [LICENSE](LICENSE).
