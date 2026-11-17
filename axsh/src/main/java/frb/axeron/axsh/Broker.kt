package frb.axeron.axsh

import android.content.Intent
import android.net.LocalSocket
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Serves the bootstrap's session: asks the manager for this uid's server capability with an
 * `AxRuntime.REQUEST_BINDER` broadcast, caches the server's abstract socket name (for direct
 * root-mode connections), and provides an `openChannel` pipe for the native `--axsh-daemon`.
 */
object Broker : Sessions.Host {

    override val tag = "AxshBroker"

    private const val ACTION_REQUEST_BINDER = "AxRuntime.REQUEST_BINDER"
    private const val MANAGER_PACKAGE = AxshConstants.MANAGER_PACKAGE
    private const val BINDER_TIMEOUT_MS = 5_000L
    private const val SERVER_NAME_FILE = ".axsh-server"

    private val sessions = Sessions(this)

    @Volatile
    private var sendRequest: ((Intent) -> Unit)? = null

    @Volatile
    private var cached: IAxshLauncher? = null

    fun socketName(uid: Int = Process.myUid()): String = "axsh.$uid"

    internal fun serveOne(socket: LocalSocket, sender: (Intent) -> Unit) {
        sendRequest = sender
        sessions.serve(socket)
    }

    internal fun listen(): Boolean = sessions.listen(socketName())

    internal fun stop() = sessions.stop()

    internal val activeSessions: Int get() = sessions.active

    internal val idleMillis: Long get() = sessions.idleMillis

    internal fun openChannel(): ParcelFileDescriptor? = try {
        launcher()?.openAxshChannel()
    } catch (e: Exception) {
        Log.w(tag, "openAxshChannel failed", e)
        null
    }

    override fun refuse(uid: Int, pid: Int): String? =
        if (uid == Process.myUid()) null else "Not this app's process"

    @Synchronized
    override fun launcher(): IAxshLauncher? {
        cached?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val service = requestBinder() ?: return null
        return fetchLauncher(service)?.also {
            cached = it
            cacheSocketName(it)
        }
    }

    private fun cacheSocketName(launcher: IAxshLauncher) {
        val dir = System.getenv("TMPDIR")?.takeIf { it.isNotEmpty() } ?: return
        try {
            val name = launcher.axshSocketName ?: return
            val tmp = File(dir, "$SERVER_NAME_FILE.tmp")
            tmp.writeText(name)
            Os.chmod(tmp.path, "600".toInt(8))
            if (!tmp.renameTo(File(dir, SERVER_NAME_FILE))) tmp.delete()
        } catch (e: Exception) {
            Log.i(tag, "no server socket name to leave: ${e.message ?: e}")
        }
    }

    private fun fetchLauncher(service: IBinder): IAxshLauncher? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AxshConstants.SERVICE_DESCRIPTOR)
            service.transact(AxshConstants.TRANSACTION_GET_LAUNCHER, data, reply, 0)
            reply.readException()
            IAxshLauncher.Stub.asInterface(reply.readStrongBinder())
        } catch (e: Exception) {
            Log.w(tag, "server has no axsh launcher", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun requestBinder(): IBinder? {
        val start = SystemClock.uptimeMillis()
        val latch = CountDownLatch(1)
        var received: IBinder? = null
        val receiver = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != 1) return super.onTransact(code, data, reply, flags)
                if (latch.count == 0L) return true
                received = data.readStrongBinder()
                latch.countDown()
                return true
            }
        }

        val intent = Intent(ACTION_REQUEST_BINDER)
            .setPackage(MANAGER_PACKAGE)
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra("data", Bundle().apply {
                putBinder("binder", receiver)
                putInt(AxshConstants.EXTRA_UID, Process.myUid())
            })
        try {
            val sender = sendRequest ?: return null
            sender(intent)
        } catch (e: Exception) {
            Log.w(tag, "binder request failed", e)
            return null
        }

        if (!latch.await(BINDER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(tag, "no binder from $MANAGER_PACKAGE")
            return null
        }
        val binder = received
        if (binder == null || !binder.pingBinder()) {
            Log.w(tag, "manager replied without a live binder")
            return null
        }
        Log.i(tag, "binder from $MANAGER_PACKAGE in ${SystemClock.uptimeMillis() - start} ms")
        return binder
    }
}
