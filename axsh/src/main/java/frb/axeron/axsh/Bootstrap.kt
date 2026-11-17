package frb.axeron.axsh

import android.content.Intent
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.util.Log
import rikka.hidden.compat.ActivityManagerApis
import java.io.File
import java.io.FileDescriptor
import kotlin.system.exitProcess

/**
 * `app_process` entry started by the native client with one end of a socketpair as its first
 * argument. It runs as the caller's uid, serves that session through [Broker], spawns the
 * lightweight native `--axsh-daemon` channel when supported so subsequent runs skip JVM
 * startup, and exits.
 */
object Bootstrap {

    private const val TAG = "AxshBootstrap"
    private const val DAEMON_FLAG = "--axsh-daemon"
    private const val DEFAULT_KEEPALIVE_MINUTES = 15

    @JvmStatic
    fun main(args: Array<String>) {
        val fdNumber = args.getOrNull(0)?.toIntOrNull()
        if (fdNumber == null) {
            System.err.println("axsh: bootstrap needs a socket fd")
            exitProcess(2)
        }
        val axsh = args.getOrNull(1)
        args.getOrNull(2)?.toLongOrNull()?.let {
            Log.i(TAG, "JVM up ${SystemClock.uptimeMillis() - it} ms after spawn")
        }

        val socket = try {
            connectedSocket(ParcelFileDescriptor.adoptFd(fdNumber).fileDescriptor)
        } catch (e: Throwable) {
            System.err.println("axsh: bootstrap can't use fd $fdNumber: $e")
            exitProcess(2)
        }

        Broker.serveOne(socket, ::broadcast)
        startDaemon(axsh, DEFAULT_KEEPALIVE_MINUTES)
        exitProcess(0)
    }

    private fun startDaemon(axsh: String?, minutes: Int) {
        if (axsh == null || !File(axsh).canExecute()) return
        val channel = Broker.openChannel() ?: return
        try {
            channel.use {
                Os.dup2(it.fileDescriptor, 0)
                try {
                    ProcessBuilder(axsh, DAEMON_FLAG, minutes.toString())
                        .redirectInput(ProcessBuilder.Redirect.INHERIT)
                        .redirectOutput(File("/dev/null"))
                        .redirectError(File("/dev/null"))
                        .start()
                } finally {
                    val devNull = Os.open("/dev/null", OsConstants.O_RDONLY, 0)
                    Os.dup2(devNull, 0)
                    Os.close(devNull)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't start native daemon", e)
        }
    }

    private fun broadcast(intent: Intent) {
        ActivityManagerApis.broadcastIntent(
            intent, null, null, 0, null, null, null, -1, null,
            false, false, Os.getuid() / 100000
        )
    }

    private fun connectedSocket(fd: FileDescriptor): LocalSocket =
        LocalSocket::class.java.getDeclaredConstructor(FileDescriptor::class.java)
            .apply { isAccessible = true }
            .newInstance(fd)
}
