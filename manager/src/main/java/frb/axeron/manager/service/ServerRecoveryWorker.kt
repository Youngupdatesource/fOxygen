package frb.axeron.manager.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import frb.axeron.api.Axeron
import kotlinx.coroutines.delay

/**
 * Pemulihan server setelah binder putus (lihat [ServerWatch]). Berjalan sebagai job sekali jalan:
 * tidak memegang wakelock sendiri, dan tidak menunggu lama di dalam job. Kalau restart belum
 * membuahkan hasil, job meminta retry; sistem yang menjadwalkan ulang dengan backoff eksponensial
 * di jendela idle-nya. Job periodik 30 menit ([ServerHealthWorker]) tetap menjadi jaring pengaman.
 */
class ServerRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ServerRecoveryWorker"

        private const val MAX_ATTEMPTS = 6

        // Proses manager bisa dibangunkan job dari kondisi mati; binder server yang sehat tiba
        // beberapa saat kemudian. Sama seperti ServerHealthWorker, beri waktu sebelum menyimpulkan mati.
        private const val BINDER_WAIT_TRIES = 10
        private const val BINDER_WAIT_STEP_MS = 500L

        // Restart baru dikirim: server mungkin masih naik, jangan kirim lagi.
        private const val RECENT_RESTART_MS = 45_000L
    }

    override suspend fun doWork(): Result {
        if (!ServerGuard.autoRestartEnabled) return Result.success()
        if (!ServerGuard.wasRunning) return Result.success()

        repeat(BINDER_WAIT_TRIES) {
            if (Axeron.pingBinder()) return Result.success()
            delay(BINDER_WAIT_STEP_MS)
        }

        if (ServerGuard.restartedRecently(RECENT_RESTART_MS)) {
            Log.i(TAG, "restart masih berjalan, periksa lagi nanti")
            return retryOrGiveUp()
        }

        Log.w(TAG, "Server mati (attempt ${runAttemptCount + 1}/$MAX_ATTEMPTS), restart")
        ServerGuard.restart(applicationContext)
        // Jangan menunggu server naik di dalam job: hasilnya diperiksa pada run berikutnya.
        return retryOrGiveUp()
    }

    private fun retryOrGiveUp(): Result =
        if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
            Log.w(TAG, "menyerah setelah $MAX_ATTEMPTS percobaan; job periodik yang melanjutkan")
            Result.success()
        } else {
            Result.retry()
        }
}
