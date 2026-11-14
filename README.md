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

## The second fault

Every input throws the player back to the home screen, and picture mode, brightness and audio
output are greyed out. The log shows `IAudioPictureSetting is not ready yet`.

Sony's `com.sony.dtv.sonyvendorservice` reports that interface ready only once its bindings to
`com.sony.dtv.braviasyncservice` connect. When Bravia Sync is stopped at boot (see "The root
cause" below), all five of those bindings end `DEAD` in
`dumpsys activity services com.sony.dtv.braviasyncservice`. Sony handles no `onBindingDied` and
never binds again.

`am force-stop` does nothing here, because the vendor service is persistent and is skipped.
`am crash com.sony.dtv.sonyvendorservice` makes the system restart it, and it binds afresh.

This was proven on 2026-11-14 after a `kernel_panic,sysrq_trigwarmd_crash` boot. Before the
restart there were zero live connections, afterwards five, and HDMI 4 held with no crash. The
repair button and `fix` try this first. They do so only when connections exist and every one is
`DEAD`, so an empty dump never triggers it.

## The root cause of both faults

Both faults begin the same way. While the television boots, Android stops every app process that
has not attached within 10 s. `dumpsys activity exit-info` records this as
`reason=7 (INITIALIZATION FAILURE)` with `description=start timeout`. Sony never rebinds to a
service it lost this way:
- When Bravia Sync is hit, you get the second fault.
- When `com.sony.dtv.tvinput.hardwaremanager` is hit, you get the first.

On this set, 13–14 Sony and Google processes went that way within a single second on almost every
boot. The cause was a USB flash drive that had been set up as internal storage (adopted, and
primary storage) for months. The first start timeouts in the exit history appear two days after
the drive was set up.

Measured on 2026-11-14:

| Boot | USB drive | Start timeouts |
|---|---|---|
| 1 | adopted, primary | 14 |
| 2, 3 | reformatted as removable | 0, 0 |
| 4, 5 | unplugged | 0, 0 |

The fix was to move primary storage back to internal (`pm move-primary-storage internal`), then
format the drive as removable storage (`sm partition <disk> public`) and remove its old record
(`sm forget <uuid>`). The app now says after a repair how many services were stopped at start-up,
and whether a drive is still set up as internal storage.

The nightly `kernel_panic,sysrq_trigwarmd_crash` boots have a separate cause, but they set up the
same situation. The shutdown hung while the volumes were torn down (`vdc volume shutdown`).
MediaTek's `/vendor/etc/service/mt_shutdown_monitor.sh` then forces a reboot through
`/proc/sysrq-trigger` once a shutdown takes longer than 15 s.

## The app

```bash
TV=192.168.1.20:5555          # your set's address, from Settings → Network
./gradlew :app:assembleRelease
adb connect "$TV"
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
```

It appears on the Google TV home screen as **HDMI Rescue**.

The screen has one button, **Repair**, which holds focus when the app opens. Pressing it does
three things:

1. It checks which fault the television has.
   - If the settings are stuck, it restarts the vendor service with `am crash` (see the second
     fault above).
   - Otherwise it restarts the input service with `am force-stop`.
2. It goes back to the input the television last tried to play. The app reads that input from the
   passthrough URI in the log.
3. If adb is unreachable, it explains the setup and shows a button into the developer options.

| | |
|---|---|
| Switching inputs | **Certain.** The app sends a VIEW intent on `content://android.media.tv/passthrough/<inputId>`. This is the same route the launcher takes, and it needs no permission (`TvContract.java:473-489`). |
| Restarting a service | **The real repair, with one prerequisite.** The app connects to the television's own adb daemon on `127.0.0.1:5555` and runs the command there. That shell runs as uid 2000 and holds `FORCE_STOP_PACKAGES`. The app itself never could. |

No app can stop that service directly. `killBackgroundProcesses` stops at oom priority 500,
while a service holding TV hardware sits at 100 — the system binds it with
`BIND_FOREGROUND_SERVICE_WHILE_AWAKE` for as long as it exists — and `forceStopPackage` is
signature-only. Hence the detour through adb.

**What it needs:** ADB debugging switched on at the television (developer options → Debugging →
**ADB debugging**, labelled *USB debugging* on some builds; on a set with no USB device port, that
one switch is what opens port 5555), and the "Allow debugging?" dialog confirmed once with the
remote, with *Always allow* ticked. When adb cannot be reached, the app says so after the press
and offers a button straight into the developer options.

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

`fix` first checks for the second fault, and restarts the vendor service if the settings are
stuck. Otherwise it checks the log for `notifyHardwareAvailable` after re-tuning, and stops as
soon as that is enough. `BRAVIA=<host:port>` is required and says which set to talk to; `ANDROID_HOME` is only
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
