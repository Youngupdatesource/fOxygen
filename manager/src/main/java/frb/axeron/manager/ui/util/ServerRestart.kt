package frb.axeron.manager.ui.util

import frb.axeron.api.Axeron
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.core.Starter

object ServerRestart {

    fun command(): String = "{ ${Starter.internalCommand}; } >/dev/null 2>&1 </dev/null"

    fun launch() {
        Axeron.newProcess(
            AxeronCommandSession.getQuickCmd(command(), true, false),
            null,
            null
        )
    }
}
