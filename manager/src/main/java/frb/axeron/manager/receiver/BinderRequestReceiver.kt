package frb.axeron.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import frb.axeron.server.shell.ShellBinderRequestHandler

class BinderRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        ShellBinderRequestHandler.handleRequest(context, intent) {
            pending.finish()
        }
    }
}
