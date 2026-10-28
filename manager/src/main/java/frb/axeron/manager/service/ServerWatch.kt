package frb.axeron.manager.service

import android.app.Application
import frb.axeron.api.Axeron

/**
 * Pendengar binder di level proses (bukan ViewModel), jadi tetap aktif selama proses manager hidup
 * walau Activity sudah ditutup. Murni event: tidak ada polling dan tidak ada wakelock.
 *
 * - Binder server putus: bila server seharusnya hidup, jadwalkan job pemulihan sekali jalan.
 *   Stop dari app menurunkan `wasRunning` lebih dulu, jadi tidak memicu pemulihan.
 * - Binder server diterima lagi: batalkan job pemulihan yang masih menunggu.
 */
object ServerWatch {
    private var started = false

    @Synchronized
    fun start(app: Application) {
        if (started) return
        started = true

        Axeron.addBinderDeadListener(Axeron.OnBinderDeadListener {
            if (ServerGuard.wasRunning && ServerGuard.autoRestartEnabled) {
                ServerHealthScheduler.scheduleRecovery(app)
            }
        })
        Axeron.addBinderReceivedListener(Axeron.OnBinderReceivedListener {
            ServerHealthScheduler.cancelRecovery(app)
        })
    }
}
