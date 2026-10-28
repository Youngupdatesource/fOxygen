package frb.axeron.server.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import frb.axeron.server.ServerConstants


class BinderRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ServerConstants.REQUEST_BINDER_AXRUNTIME) return
        val pending = goAsync()
        ShellBinderRequestHandler.handleRequest(context.applicationContext, intent) { pending.finish() }
    }
}
