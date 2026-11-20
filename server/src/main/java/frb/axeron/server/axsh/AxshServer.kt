package frb.axeron.server.axsh

import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.SparseArray
import frb.axeron.server.IAxeronService
import frb.axeron.server.util.Logger
import frb.axeron.shell.Sessions
import java.io.FileDescriptor
import java.io.IOException
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Native `axsh` clients connect to the abstract socket [socketName] wherever SELinux lets them
 * (root mode) and run the shared [Sessions] protocol right here, without the binder round
 * trip a broker needs. Callers are identified by SO_PEERCRED.
 */
class AxshServer(
    private val server: () -> IAxeronService,
    private val authorize: (uid: Int, pid: Int) -> Boolean,
) : Sessions.Host {

    companion object {
        private val LOGGER = Logger("AxshServer")
        private val currentPeerUid = ThreadLocal<Int?>()

        fun consumePeerUid(): Int? {
            val uid = currentPeerUid.get()
            currentPeerUid.remove()
            return uid
        }
    }

    val socketName: String = "axeron." +
        ByteArray(8).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    override val tag = "AxshServer"

    private val sessions = Sessions(this)

    fun start() {
        if (!sessions.listen(socketName)) LOGGER.w("axsh socket unavailable")
    }

    fun stop() = sessions.stop()

    fun openChannel(uid: Int): ParcelFileDescriptor {
        val pair = ParcelFileDescriptor.createSocketPair()
        synchronized(channels) {
            (channels.get(uid) ?: mutableListOf<ParcelFileDescriptor>().also { channels.put(uid, it) })
                .add(pair[0])
        }
        thread(name = "axsh-channel-$uid", isDaemon = true) { serveChannel(uid, pair[0]) }
        return pair[1]
    }

    private val channels = SparseArray<MutableList<ParcelFileDescriptor>>()

    fun closeChannels(uid: Int) {
        val open = synchronized(channels) { channels.get(uid)?.toList() }.orEmpty()
        for (own in open) {
            try {
                Os.shutdown(own.fileDescriptor, OsConstants.SHUT_RDWR)
            } catch (_: Exception) {
            }
        }
        if (open.isNotEmpty()) LOGGER.i("closed %d axsh channel(s) of uid=%d", open.size, uid)
    }

    private fun serveChannel(uid: Int, own: ParcelFileDescriptor) {
        LOGGER.i("axsh channel for uid=%d open", uid)
        try {
            val channel = localSocket(own.fileDescriptor)
            val input = channel.inputStream
            while (input.read() >= 0) {
                channel.ancillaryFileDescriptors?.forEach { take(uid, it) }
            }
        } catch (e: Exception) {
            LOGGER.w(e, "axsh channel for uid=%d failed", uid)
        } finally {
            synchronized(channels) {
                channels.get(uid)?.let {
                    it.remove(own)
                    if (it.isEmpty()) channels.remove(uid)
                }
            }
            try {
                own.close()
            } catch (_: IOException) {
            }
            LOGGER.i("axsh channel for uid=%d closed", uid)
        }
    }

    private fun take(uid: Int, fd: FileDescriptor) {
        val client = try {
            localSocket(fd)
        } catch (e: Exception) {
            LOGGER.w(e, "forwarded fd is not usable")
            try {
                Os.close(fd)
            } catch (_: Exception) {
            }
            return
        }
        val peer = try {
            client.peerCredentials
        } catch (_: IOException) {
            null
        }
        if (peer?.uid != uid) {
            LOGGER.w("channel of uid=%d forwarded a client of uid=%s, dropped", uid, peer?.uid.toString())
            client.close()
            return
        }
        thread(name = "axsh-session", isDaemon = true) { sessions.serve(client) }
    }

    private fun localSocket(fd: FileDescriptor): LocalSocket =
        LocalSocket::class.java.getDeclaredConstructor(FileDescriptor::class.java)
            .apply { isAccessible = true }
            .newInstance(fd)

    override fun ignore(uid: Int, pid: Int): Boolean = !authorize(uid, pid)

    override fun refuse(uid: Int, pid: Int): String? {
        if (!authorize(uid, pid)) return Sessions.DENIED_MESSAGE
        currentPeerUid.set(uid)
        return null
    }

    override fun service(): IAxeronService = server()
}
