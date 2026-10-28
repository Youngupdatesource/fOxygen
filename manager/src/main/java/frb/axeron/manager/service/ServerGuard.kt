package frb.axeron.manager.service

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.Shell
import frb.axeron.adb.util.AdbEnvironment
import frb.axeron.api.Axeron
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Starter
import frb.axeron.manager.adb.AdbStarter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Status "server seharusnya hidup" + logika restart bersama.
 *
 * Flag disimpan lewat AxeronSettings.getPreferences() (store yang sama dengan getStartOnBoot),
 * jadi tidak perlu mengubah submodule api/.
 */
object ServerGuard {
    private const val TAG = "ServerGuard"
    private const val KEY_WAS_RUNNING = "server_guard_was_running"
    private const val KEY_AUTO_RESTART = "server_guard_auto_restart"

    private val prefs get() = AxeronSettings.getPreferences()

    /** true selama server terakhir diketahui jalan dan bukan dimatikan oleh user. */
    var wasRunning: Boolean
        get() = prefs.getBoolean(KEY_WAS_RUNNING, false)
        set(value) {
            prefs.edit().putBoolean(KEY_WAS_RUNNING, value).apply()
        }

    var autoRestartEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTART, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_RESTART, value).apply()
        }

    /**
     * Menyalakan ulang server memakai mode peluncuran terakhir.
     * Return true bila perintah start berhasil dikirim (bukan jaminan server sudah hidup).
     */
    suspend fun restart(context: Context): Boolean = restartLock.withLock {
        lastRestartAt = SystemClock.elapsedRealtime()
        if (AxeronSettings.getLastLaunchMode() == AxeronSettings.LaunchMethod.ROOT) {
            restartViaRoot()
        } else {
            restartViaAdbTcp(context)
        }
    }

    /**
     * Menunggu binder server tiba tanpa polling: satu callback + satu timer, bukan loop ping.
     * Berguna saat job membangunkan proses manager dari kondisi mati, karena binder dari server
     * yang sehat baru tiba beberapa saat kemudian.
     */
    suspend fun awaitBinder(timeoutMs: Long): Boolean {
        if (Axeron.pingBinder()) return true

        val ready = CompletableDeferred<Unit>()
        val listener = Axeron.OnBinderReceivedListener { ready.complete(Unit) }
        // Sticky: langsung terpanggil bila binder sudah siap saat dipasang.
        Axeron.addBinderReceivedListenerSticky(listener)
        try {
            // Tutup celah antara cek pertama dan pemasangan listener.
            if (Axeron.pingBinder()) ready.complete(Unit)
            return withTimeoutOrNull(timeoutMs) { ready.await() } != null
        } finally {
            Axeron.removeBinderReceivedListener(listener)
        }
    }

    // Satu restart pada satu waktu: auto-restart di UI dan job pemulihan tidak boleh saling menimpa.
    private val restartLock = Mutex()

    @Volatile
    private var lastRestartAt = 0L

    /** true bila restart baru saja dikirim dan server mungkin masih naik (jangan kirim lagi). */
    fun restartedRecently(withinMs: Long): Boolean {
        val at = lastRestartAt
        return at != 0L && SystemClock.elapsedRealtime() - at < withinMs
    }

    private suspend fun restartViaRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Shell.getShell().isRoot) {
                Shell.cmd(Starter.internalCommand).exec()
                Log.i(TAG, "restart via root: command sent")
                true
            } else {
                Shell.getCachedShell()?.close()
                Log.w(TAG, "restart via root: no root access")
                false
            }
        }.getOrElse {
            Log.e(TAG, "restart via root failed", it)
            false
        }
    }

    private suspend fun restartViaAdbTcp(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val port = AdbEnvironment.getAdbTcpPort().takeIf { it > 0 }
                ?: AxeronSettings.getTcpPort()
            if (port > 0) {
                AdbStarter.startAdbClient(context.applicationContext, port)
                Log.i(TAG, "restart via ADB TCP: command sent to port $port")
                true
            } else {
                Log.w(TAG, "restart via ADB: no TCP port available")
                false
            }
        }.getOrElse {
            Log.e(TAG, "restart via ADB failed", it)
            false
        }
    }
}
