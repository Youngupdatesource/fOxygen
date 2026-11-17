package frb.axeron.server

import android.os.Binder
import android.os.Parcel
import frb.axeron.server.util.Logger
import frb.axeron.shared.AxeronApiConstant.server.BINDER_DESCRIPTOR

/**
 * The server as one app sees it: minted for [uid] alone. The manager holds the server itself;
 * every other app gets one of these from the manager's relay or capability push.
 *
 * Every transaction must come from [uid] and, past asking, find its grant still there: a revoke
 * in the manager, a denial, or another app on the uid (see AxeronConfigManager.find) ends it at
 * the next call. Calls go on to [service] on the same binder thread so its own gates still see
 * the caller. Manager-only methods are blocked at the door.
 */
class ClientCapability(private val service: AxeronService, val uid: Int) : Binder() {

    companion object {
        private val LOGGER = Logger("ClientCapability")
    }

    init {
        attachInterface(null, BINDER_DESCRIPTOR)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(BINDER_DESCRIPTOR)
            return true
        }

        val caller = getCallingUid()
        if (caller != uid) {
            LOGGER.w("capability of uid=%d called by uid=%d, refused", uid, caller)
            throw SecurityException("This capability belongs to another app")
        }
        if (service.isManagerOnlyTransaction(code)) {
            throw SecurityException("Transaction $code is reserved for the manager")
        }
        if (!service.isAskingTransaction(code) && !isAllowed()) {
            throw SecurityException("Not allowed by fOxygen")
        }

        return service.dispatchClientTransaction(code, data, reply, flags)
    }

    private fun isAllowed(): Boolean =
        service.configManager.find(uid)?.isAllowed == true ||
            service.clientManager.findClients(uid).any { it.allowed }
}
