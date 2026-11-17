package frb.axeron.server.shell

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.axsh.AxshConstants
import frb.axeron.server.ServerConstants
import frb.axeron.shared.AxeronApiConstant
import java.util.concurrent.atomic.AtomicBoolean

object ShellBinderRequestHandler {

    private const val TAG = "ShellBinderRequest"

    private const val BINDER_WAIT_MS = 4_000L

    private const val FRESH_PROCESS_MS = 4_500L

    private val mainHandler = Handler(Looper.getMainLooper())

    fun handleRequest(context: Context, intent: Intent, onDone: () -> Unit = {}): Boolean {
        if (intent.action != ServerConstants.REQUEST_BINDER_AXRUNTIME) {
            onDone()
            return false
        }

        val bundle = intent.getBundleExtra("data")
        val receiver = bundle?.getBinder("binder")
        val uid = bundle?.getInt(AxshConstants.EXTRA_UID, -1) ?: -1
        if (receiver == null) {
            onDone()
            return false
        }

        Axeron.getBinder()?.takeIf { it.pingBinder() }?.let {
            reply(context, receiver, it, uid)
            onDone()
            return true
        }

        val age = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()
        if (age > FRESH_PROCESS_MS) {
            reply(context, receiver, null, uid)
            onDone()
            return true
        }

        val done = AtomicBoolean(false)
        val listener = object : Axeron.OnBinderReceivedListener {
            override fun onBinderReceived() {
                if (done.getAndSet(true)) return
                Axeron.removeBinderReceivedListener(this)
                reply(context, receiver, Axeron.getBinder(), uid)
                onDone()
            }
        }
        Axeron.addBinderReceivedListenerSticky(listener, mainHandler)
        mainHandler.postDelayed({
            if (done.getAndSet(true)) return@postDelayed
            Axeron.removeBinderReceivedListener(listener)
            Log.w(TAG, "no binder from the server within ${BINDER_WAIT_MS}ms")
            reply(context, receiver, null, uid)
            onDone()
        }, BINDER_WAIT_MS)
        return true
    }

    /**
     * Mints a UID-scoped [frb.axeron.server.ClientCapability] from the server when the requester
     * supplied its uid, so non-manager callers never receive the manager's unrestricted binder.
     */
    private fun resolveClientBinder(serverBinder: IBinder?, uid: Int): IBinder? {
        if (serverBinder == null || !serverBinder.pingBinder()) return null
        if (uid <= 0 || uid == Process.myUid()) return serverBinder
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(AxshConstants.SERVICE_DESCRIPTOR)
            data.writeInt(uid)
            serverBinder.transact(AxshConstants.TRANSACTION_GET_CLIENT_CAPABILITY, data, reply, 0)
            reply.readException()
            reply.readStrongBinder() ?: serverBinder
        } catch (e: Throwable) {
            Log.w(TAG, "getClientCapability failed for uid=$uid", e)
            serverBinder
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun reply(context: Context, receiver: IBinder, axeronBinder: IBinder?, uid: Int) {
        val scopedBinder = resolveClientBinder(axeronBinder, uid)
        val data = Parcel.obtain()
        try {
            data.writeStrongBinder(scopedBinder)
            data.writeLong(AxeronApiConstant.server.VERSION_CODE)
            data.writeString(context.applicationInfo.nativeLibraryDir)
            receiver.transact(1, data, null, IBinder.FLAG_ONEWAY)
        } catch (e: Throwable) {
            Log.w(TAG, "reply failed", e)
        } finally {
            data.recycle()
        }
    }
}
