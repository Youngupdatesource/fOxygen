package frb.axeron.manager.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class ServerHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ServerHealthWorker"

        // Jika worker membangunkan proses manager dari kondisi mati, binder dari server yang
        // sehat baru tiba beberapa saat kemudian. Tunggu dulu agar server sehat tidak di-restart.
        private const val BINDER_WAIT_MS = 5_000L
    }

    override suspend fun doWork(): Result {
        if (!ServerGuard.autoRestartEnabled) return Result.success()
        if (!ServerGuard.wasRunning) return Result.success()

        if (ServerGuard.awaitBinder(BINDER_WAIT_MS)) return Result.success()

        Log.w(TAG, "Server tidak merespons, mencoba restart dari WorkManager")
        ServerGuard.restart(applicationContext)
        return Result.success()
    }
}
