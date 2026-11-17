package frb.axeron.axsh

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Serves native axsh clients on the server's abstract socket (AxshServer), an app's broker,
 * or Bootstrap: reads handshake and pipes from the socket, starts the shell on them through
 * the server's [IAxshLauncher], then relays window changes in and the exit code out.
 * Wire format: `src/main/cpp/axsh_protocol.h`.
 */
class Sessions(private val host: Host) {

    interface Host {
        val tag: String

        fun refuse(uid: Int, pid: Int): String?

        fun ignore(uid: Int, pid: Int): Boolean = false

        fun launcher(): IAxshLauncher?

        fun launcher(uid: Int): IAxshLauncher? = launcher()
    }

    companion object {
        private const val VERSION = 1

        private const val STATUS_OK = 0
        private const val STATUS_DENIED = 1
        private const val STATUS_ERROR = 2

        private const val FLAG_PTY = 1

        private const val FRAME_EXIT = 0x02
        private const val FRAME_WINCH = 0x03

        private const val PIPE_FDS = 3
        private const val PTY_FDS = 2

        private val PTY_ENV = listOf("TERM", "COLORTERM", "LANG")
        private const val PTY_ENV_PREFIX = "LC_"

        private const val MAX_ARGS = 4096
        private const val MAX_STRING = 1 shl 20

        const val DENIED_MESSAGE = "AxManager permission required: allow this app in AxManager"

        internal fun ptyEnvironment(base: Array<String>?, client: List<String>): Array<String> {
            val env = LinkedHashMap<String, String>()
            base?.forEach { entry -> env[entry.substringBefore('=')] = entry }
            for (entry in client) {
                val name = entry.substringBefore('=', missingDelimiterValue = "")
                if (name in PTY_ENV || name.startsWith(PTY_ENV_PREFIX)) env[name] = entry
            }
            return env.values.toTypedArray()
        }

        internal fun windowSize(rows: Int, cols: Int): Long =
            (rows.toLong() and 0xffff) or ((cols.toLong() and 0xffff) shl 16)

        internal fun awaitExit(status: ParcelFileDescriptor, remote: IAxshProcess): Int {
            val code = ByteArray(4)
            val delivered = try {
                ParcelFileDescriptor.AutoCloseInputStream(status).use { readFully(it, code) }
            } catch (_: IOException) {
                false
            }
            if (delivered) return ByteBuffer.wrap(code).order(ByteOrder.LITTLE_ENDIAN).int
            return try {
                remote.waitFor()
            } catch (_: RemoteException) {
                -1
            }
        }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n < 0) return false
                off += n
            }
            return true
        }
    }

    private val tag get() = host.tag

    @Volatile
    private var listener: LocalServerSocket? = null

    /** Listens on the abstract socket [name], one thread per client. False if it can't bind. */
    @Synchronized
    fun listen(name: String): Boolean {
        if (listener != null) return true
        val socket = try {
            LocalServerSocket(name)
        } catch (e: IOException) {
            Log.e(tag, "can't bind @$name", e)
            return false
        }
        listener = socket
        thread(name = "axsh-accept", isDaemon = true) { acceptLoop(socket) }
        Log.i(tag, "listening on @$name")
        return true
    }

    @Synchronized
    fun stop() {
        try {
            listener?.close()
        } catch (_: IOException) {
        }
        listener = null
    }

    private fun acceptLoop(socket: LocalServerSocket) {
        while (true) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                if (listener === socket) Log.w(tag, "accept failed", e)
                break
            }
            thread(name = "axsh-session", isDaemon = true) { serve(client) }
        }
    }

    private val activeCount = AtomicInteger(0)

    @Volatile
    private var lastSessionEnd = SystemClock.elapsedRealtime()

    val active: Int get() = activeCount.get()

    val idleMillis: Long
        get() = if (active > 0) 0 else SystemClock.elapsedRealtime() - lastSessionEnd

    fun serve(socket: LocalSocket) {
        activeCount.incrementAndGet()
        try {
            socket.use { serveSession(it) }
        } catch (e: Throwable) {
            Log.w(tag, "session failed", e)
        } finally {
            lastSessionEnd = SystemClock.elapsedRealtime()
            activeCount.decrementAndGet()
        }
    }

    private fun serveSession(socket: LocalSocket) {
        val input = socket.inputStream
        val output = socket.outputStream

        val credentials = try {
            socket.peerCredentials
        } catch (_: IOException) {
            null
        }
        val uid = credentials?.uid ?: -1
        val pid = credentials?.pid ?: -1

        if (host.ignore(uid, pid)) {
            Log.i(tag, "uid=$uid pid=$pid ignored")
            return
        }

        val request = readRequest(input)
        val received = socket.ancillaryFileDescriptors.orEmpty()
        try {
            if (request == null) {
                writeStatus(output, STATUS_ERROR, "Malformed request")
                return
            }
            val expected = if (request.pty) PTY_FDS else PIPE_FDS
            if (received.size != expected) {
                writeStatus(output, STATUS_ERROR, "Malformed request: expected $expected pipes")
                return
            }

            host.refuse(uid, pid)?.let { reason ->
                Log.w(tag, "refused uid=$uid pid=$pid: $reason")
                writeStatus(output, STATUS_DENIED, reason)
                return
            }

            val launcher = host.launcher(uid)
            if (launcher == null) {
                writeStatus(output, STATUS_ERROR, "AxManager server is not running")
                return
            }

            val status = ParcelFileDescriptor.createPipe()
            try {
                val process = start(output, launcher, request, received, status[1])
                closeAll(received)
                if (process == null) return
                writeStatus(output, STATUS_OK, null)
                Log.i(tag, "session started (uid=$uid, pty=${request.pty}): ${request.argv.joinToString(" ")}")
                relay(input, output, process, status[0])
            } finally {
                status[0].close()
            }
        } finally {
            closeAll(received)
        }
    }

    private fun start(
        output: OutputStream,
        launcher: IAxshLauncher,
        request: Request,
        received: Array<out FileDescriptor>,
        status: ParcelFileDescriptor,
    ): IAxshProcess? {
        val pipes = received.map { ParcelFileDescriptor.dup(it) }
        val started = try {
            val base = launcher.environment
            val env = if (request.pty) ptyEnvironment(base, request.env) else base
            launcher.newAxshProcess(
                request.argv.toTypedArray(), env,
                pipes[0], pipes[1], pipes.getOrNull(2),
                request.pty, windowSize(request.rows, request.cols), status
            )
        } catch (e: SecurityException) {
            writeStatus(output, STATUS_DENIED, e.message?.takeIf { it.isNotBlank() } ?: DENIED_MESSAGE)
            return null
        } catch (e: Exception) {
            Log.w(tag, "remote shell failed to start", e)
            null
        } finally {
            (pipes + status).forEach {
                try {
                    it.close()
                } catch (_: IOException) {
                }
            }
        }
        if (started == null) writeStatus(output, STATUS_ERROR, "Can't start remote shell")
        return started
    }

    private fun relay(
        input: InputStream,
        output: OutputStream,
        process: IAxshProcess,
        status: ParcelFileDescriptor,
    ) {
        val exited = AtomicBoolean(false)
        thread(name = "axsh-session-in", isDaemon = true) {
            try {
                while (true) {
                    if (input.read() != FRAME_WINCH) break
                    val size = ByteArray(4)
                    if (!readFully(input, size)) break
                    val buf = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN)
                    val rows = buf.short.toInt() and 0xffff
                    val cols = buf.short.toInt() and 0xffff
                    process.setWindowSize(windowSize(rows, cols))
                }
            } catch (_: Exception) {
            }
            if (!exited.get()) {
                try {
                    process.destroy()
                } catch (_: Exception) {
                }
            }
        }

        val code = awaitExit(status, process)
        exited.set(true)
        val frame = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        frame.put(FRAME_EXIT.toByte())
        frame.putInt(code)
        output.write(frame.array())
        output.flush()
    }

    private fun closeAll(fds: Array<out FileDescriptor>) {
        for (fd in fds) {
            try {
                Os.close(fd)
            } catch (_: ErrnoException) {
            }
        }
    }

    private class Request(
        val argv: List<String>,
        val flags: Int,
        val env: List<String>,
        val rows: Int,
        val cols: Int,
    ) {
        val pty get() = flags and FLAG_PTY != 0
    }

    private fun readRequest(input: InputStream): Request? {
        val magic = ByteArray(4)
        if (!readFully(input, magic)) return null
        if (String(magic, Charsets.US_ASCII) != "AXSH") return null

        val version = input.read()
        val flags = input.read()
        if (version != VERSION || flags < 0) return null

        val argc = readU32(input) ?: return null
        if (argc <= 0 || argc > MAX_ARGS) return null
        val argv = ArrayList<String>(argc)
        repeat(argc) { argv.add(readString(input) ?: return null) }

        val envc = readU32(input) ?: return null
        if (envc < 0 || envc > MAX_ARGS) return null
        val env = ArrayList<String>(envc)
        repeat(envc) { env.add(readString(input) ?: return null) }

        var rows = 0
        var cols = 0
        if (flags and FLAG_PTY != 0) {
            val size = ByteArray(4)
            if (!readFully(input, size)) return null
            val buf = ByteBuffer.wrap(size).order(ByteOrder.LITTLE_ENDIAN)
            rows = buf.short.toInt() and 0xffff
            cols = buf.short.toInt() and 0xffff
        }

        return Request(argv, flags, env, rows, cols)
    }

    private fun writeStatus(output: OutputStream, status: Int, message: String?) {
        if (status != STATUS_OK) Log.w(tag, "refused session: status=$status, $message")
        output.write(status)
        if (message != null) {
            val bytes = message.toByteArray(Charsets.UTF_8)
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(bytes.size).array())
            output.write(bytes)
        }
        output.flush()
    }

    private fun readU32(input: InputStream): Int? {
        val b = ByteArray(4)
        if (!readFully(input, b)) return null
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readString(input: InputStream): String? {
        val len = readU32(input) ?: return null
        if (len < 0 || len > MAX_STRING) return null
        if (len == 0) return ""
        val b = ByteArray(len)
        if (!readFully(input, b)) return null
        return String(b, Charsets.UTF_8)
    }
}
