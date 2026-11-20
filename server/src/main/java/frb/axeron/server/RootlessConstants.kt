package frb.axeron.server

object RootlessConstants {

    const val MANAGER_PACKAGE = "com.foxygen.manager"

    const val FLAG_AXSH_ROOTLESS = 1 shl 9

    const val SHELL_CONTEXT = "u:r:shell:s0"

    const val SHELL_GROUPS =
        "2000,1004,1007,1011,1015,1028,1009,3001,3002,3003,3006,3009,1002,1018,1078,1079,3012,1003"

    const val DROP_HELPER = "libaxdrop.so"

    fun rootlessArgv(helper: String, argv: Array<String>): Array<String> =
        arrayOf(helper, "-c", SHELL_CONTEXT, "-G", SHELL_GROUPS, "--") + argv
}
