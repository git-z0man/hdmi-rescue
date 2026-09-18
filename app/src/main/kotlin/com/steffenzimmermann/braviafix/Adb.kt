package com.steffenzimmermann.braviafix

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.io.IOException

/// The television, talking to itself over ADB.
///
/// Restarting Sony's input service is the one repair that reliably works, and no app may ask for
/// it: `killBackgroundProcesses` stops at oom priority 500 while a service holding TV hardware
/// sits at 100, bound by the system (`BIND_FOREGROUND_SERVICE_WHILE_AWAKE`), and `forceStopPackage`
/// is signature-only. The adb shell on the same set runs as uid 2000 and does hold
/// `FORCE_STOP_PACKAGES` — so the app connects to the daemon on its own loopback address and asks
/// there.
///
/// ⚠️ **This needs ADB debugging switched on at the television**, and the first connection raises
/// the "Allow debugging?" dialog on screen, which has to be confirmed with the remote once.
private const val HOST = "127.0.0.1"
private const val PORT = 5555

/// 15 attempts are ~30 s of retrying, which is what the first connection needs: it waits for a
/// human with a remote control. A check nobody asked for passes 1.
fun adbShell(context: Context, command: String, attempts: Int = 15): Result<String> {
    // ⚠️ Our own key pair in filesDir. `AdbKeyPair.readDefault()` looks under `~/.android`, which
    // on Android resolves to `/` and throws. Both files are kept: read without the public one and
    // dadb sends an empty RSAPUBLICKEY, so the television can never offer the dialog at all.
    val privateKey = File(context.filesDir, "adbkey")
    val publicKey = File(context.filesDir, "adbkey.pub")
    val keys = runCatching {
        if (!privateKey.exists() || !publicKey.exists()) AdbKeyPair.generate(privateKey, publicKey)
        AdbKeyPair.read(privateKey, publicKey)
    }.getOrElse { return Result.failure(it) }

    var last: Throwable = IOException("no attempt made")
    repeat(attempts) {
        // ⚠️ A fresh connection per attempt, and `socketTimeout` is not optional: without it the
        // handshake blocks forever while the dialog waits on screen, and a timed-out socket is
        // never reopened by dadb, only reused.
        try {
            Dadb.create(HOST, PORT, keys, connectTimeout = 3_000, socketTimeout = 5_000).use { adb ->
                val response = adb.shell(command)
                return if (response.exitCode == 0) Result.success(response.allOutput.trim())
                else Result.failure(IOException("exit ${response.exitCode}: ${response.allOutput.trim()}"))
            }
        } catch (e: Exception) {
            last = e
            Thread.sleep(2_000)
        }
    }
    return Result.failure(last)
}
