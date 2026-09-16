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
2. If the picture stays away, press **Restart the input service (attempt)** and pick the input
   again.

| | |
|---|---|
| Switching inputs | **certain.** A VIEW intent on `content://android.media.tv/passthrough/<inputId>` — the same route the launcher takes, and it needs no permission (`TvContract.java:473-489`). |
| Restarting the service | **an attempt only, and usually in vain.** `killBackgroundProcesses` reaches background processes; the stuck service was seen running as a bound foreground service, which it does not reach. Really stopping Sony's service needs `FORCE_STOP_PACKAGES`, which a sideloaded app cannot hold — not even through `pm grant`. |

The app says so on screen rather than pretending otherwise: when the attempt is not enough it
names the two routes that remain.

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
- No dependencies beyond AGP itself. That is deliberate: this app has to work when nothing else
  on the television does.

## Where it came from

The app and script were built inside [zplayer-tv](../zplayer-tv) while chasing a black HDMI 4 and
moved here once they turned out to have nothing to do with the player. The application id still
reads `com.steffenzimmermann.braviafix`, the name it shipped under, so an install upgrades the
copy already on the television instead of leaving an orphan beside it.
