package frb.axeron.manager.ui.util

import frb.axeron.shell.Axsh
import frb.axeron.api.AxeronCommandSession
import frb.axeron.api.core.Starter

object ServerRestart {

    fun command(): String = "{ ${Starter.internalCommand}; } >/dev/null 2>&1 </dev/null"

    fun launch() {
        Axsh.exec(
            AxeronCommandSession.getQuickCmd(command(), true, false),
            null,
            null
        )
    }
}
