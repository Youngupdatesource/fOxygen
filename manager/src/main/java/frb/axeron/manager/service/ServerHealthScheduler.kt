package frb.axeron.manager.service

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ServerHealthScheduler {
    private const val TAG = "ServerHealthScheduler"
    const val WORK_NAME = "axeron_server_health_check"
    const val RECOVERY_WORK_NAME = "axeron_server_recovery"
    private const val INTERVAL_MINUTES = 30L

    // Jeda sebelum job pemulihan memeriksa server: restart yang disengaja (update, restart dari app,
    // plugin) mematikan binder juga, dan server barunya naik dalam <= 20 dtk. Selama jeda sistem yang
    // memegang jadwalnya, tidak ada wakelock dan tidak ada polling.
    private const val RECOVERY_DELAY_SECONDS = 25L
    private const val RECOVERY_BACKOFF_SECONDS = 15L

    // runCatching: saat direct boot (LOCKED_BOOT_COMPLETED) database WorkManager belum bisa dibuka.
    fun schedule(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<ServerHealthWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "schedule failed", it) }
    }

    /** Server mati (binder putus): satu job sekali jalan, backoff eksponensial bila restart gagal. */
    fun scheduleRecovery(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<ServerRecoveryWorker>()
                .setInitialDelay(RECOVERY_DELAY_SECONDS, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RECOVERY_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(RECOVERY_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }.onFailure { Log.w(TAG, "scheduleRecovery failed", it) }
    }

    fun cancelRecovery(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(RECOVERY_WORK_NAME)
        }.onFailure { Log.w(TAG, "cancelRecovery failed", it) }
    }

    fun cancel(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context.applicationContext)
            wm.cancelUniqueWork(WORK_NAME)
            wm.cancelUniqueWork(RECOVERY_WORK_NAME)
        }.onFailure { Log.w(TAG, "cancel failed", it) }
    }
}
