package frb.axeron.manager.data

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import frb.axeron.api.Axeron
import frb.axeron.api.AxeronPluginService
import java.io.File

object AxshLauncher {

    private const val TAG = "AxshLauncher"

    const val PATH = "/sdcard/axeron/axsh"

    const val RUN_COMMAND = "sh $PATH"

    fun binaryPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, "libaxsh.so").absolutePath

    fun script(context: Context): String {
        val binary = binaryPath(context)
        val abi = File(context.applicationInfo.nativeLibraryDir).name
        val pkg = context.packageName
        return listOf(
            "#!/system/bin/sh",
            "AXSH='$binary'",
            "[ -x \"\$AXSH\" ] || AXSH=\"\$(pm path $pkg | sed -n 's/^package://;s/base\\.apk\$//p')lib/$abi/libaxsh.so\"",
            "exec \"\$AXSH\" \"\$@\"",
            ""
        ).joinToString("\n")
    }

    private const val MANAGE_STORAGE_PERMISSION = "android.permission.MANAGE_EXTERNAL_STORAGE"

    suspend fun allowManageStorage(context: Context, packageName: String) {
        val declared = try {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains(MANAGE_STORAGE_PERMISSION)
        } catch (_: Exception) {
            false
        }
        if (!declared) return
        try {
            val state = AxeronPluginService.execWithIO(
                cmd = "cmd appops get $packageName MANAGE_EXTERNAL_STORAGE",
                useBusybox = false,
                hideStderr = true
            )
            if (state.out.contains("allow")) return
            AxeronPluginService.execWithIO(
                cmd = "cmd appops set $packageName MANAGE_EXTERNAL_STORAGE allow",
                useBusybox = false,
                hideStderr = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "can't allow MANAGE_EXTERNAL_STORAGE for $packageName", e)
        }
    }

    fun ensure(context: Context): Boolean {
        return try {
            val fs = Axeron.newFileService() ?: return false
            val wanted = script(context)
            val current = runCatching {
                if (!fs.exists(PATH)) null
                else fs.setFileInputStream(PATH)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            if (current == wanted) return true

            File(PATH).parent?.let { fs.mkdirs(it) }
            val session = fs.getStreamSession(PATH, true, false) ?: return false
            session.outputStream.use {
                it.write(wanted.toByteArray())
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "can't write $PATH", e)
            false
        }
    }
}
