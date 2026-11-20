package frb.axeron.manager.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.Settings
import frb.axeron.api.Axeron
import frb.axeron.shell.Axsh
import frb.axeron.api.AxeronPluginService
import frb.axeron.manager.R
import frb.axeron.server.Environment
import frb.axeron.shared.AxeronApiConstant.server.TYPE_NEW_ENV


private val SettingsRepository.SettingType.alias: String
    get() = when (this) {
        SettingsRepository.SettingType.GLOBAL -> "global"
        SettingsRepository.SettingType.SECURE -> "secure"
        SettingsRepository.SettingType.SYSTEM -> "system"
        SettingsRepository.SettingType.ANDROID_PROP -> "android_properties"
        SettingsRepository.SettingType.AXERON_ENV -> "axeron_environment"
    }

class SettingsRepository(private val contentResolver: ContentResolver) {

    enum class SettingType(val stringId: Int) {
        GLOBAL(R.string.global_settings),
        SECURE(R.string.secure_settings),
        SYSTEM(R.string.system_settings),
        ANDROID_PROP(R.string.android_properties),
        AXERON_ENV(R.string.axeron_environment);
    }


    /** Ambil satu nilai berdasarkan key */
    fun getValue(type: SettingType, key: String): String? {
        return when (type) {
            SettingType.GLOBAL -> Settings.Global.getString(contentResolver, key)
            SettingType.SECURE -> Settings.Secure.getString(contentResolver, key)
            SettingType.SYSTEM -> Settings.System.getString(contentResolver, key)
            SettingType.ANDROID_PROP -> getAndroidProp(key)
            SettingType.AXERON_ENV -> getAxeronEnv(key)
        }
    }

    /**
     * Simpan / ubah nilai setting. Without the server, Global/Secure/System fall back to the
     * app's own WRITE_SECURE_SETTINGS / WRITE_SETTINGS (see [SettingsAccess]).
     */
    fun putValue(type: SettingType, key: String, value: String): Boolean {
        if (type == SettingType.AXERON_ENV) return setAxeronEnv(key, value)
        if (!Axeron.pingBinder()) return putDirect(type, key, value)
        return when (type) {
            SettingType.GLOBAL,
            SettingType.SECURE,
            SettingType.SYSTEM -> putSetting(type, key, value) || putDirect(type, key, value)

            SettingType.ANDROID_PROP -> setAndroidProp(key, value)
            SettingType.AXERON_ENV -> setAxeronEnv(key, value)
        }
    }

    private fun putDirect(type: SettingType, key: String, value: String): Boolean = try {
        when (type) {
            SettingType.GLOBAL -> Settings.Global.putString(contentResolver, key, value)
            SettingType.SECURE -> Settings.Secure.putString(contentResolver, key, value)
            SettingType.SYSTEM -> Settings.System.putString(contentResolver, key, value)
            SettingType.ANDROID_PROP, SettingType.AXERON_ENV -> false
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Hapus nilai setting (atau kosongkan) */
    fun deleteValue(type: SettingType, key: String): Boolean {
        if (type == SettingType.AXERON_ENV) return unsetAxeronEnv(key)
        if (!Axeron.pingBinder()) return deleteDirect(type, key)
        return when (type) {
            SettingType.GLOBAL,
            SettingType.SECURE,
            SettingType.SYSTEM -> deleteSetting(type, key) || deleteDirect(type, key)

            SettingType.ANDROID_PROP -> setAndroidProp(key, "")
            SettingType.AXERON_ENV -> unsetAxeronEnv(key)
        }
    }

    private fun deleteDirect(type: SettingType, key: String): Boolean = try {
        val uri = contentUriOf(type)
        if (uri != null) {
            contentResolver.delete(uri, "name = ?", arrayOf(key)) >= 0
        } else {
            false
        }
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Ambil semua key-value untuk tipe tertentu */
    fun getAll(type: SettingType): Map<String, String> {
        return when (type) {
            SettingType.GLOBAL,
            SettingType.SECURE,
            SettingType.SYSTEM -> querySettings(type)
            SettingType.ANDROID_PROP -> getAllAndroidProps()
            SettingType.AXERON_ENV -> getAllLinuxEnv()
        }
    }


    // ======= Android Settings (GLOBAL, SECURE, SYSTEM) =======

    fun putSetting(type: SettingType, key: String, value: String): Boolean {
        val alias = type.alias

        return try {
            Axsh.exec(arrayOf("settings", "put", alias, key, value)).waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }


    fun deleteSetting(type: SettingType, key: String): Boolean {
        val alias = type.alias

        return try {
            Axsh.exec(arrayOf("settings", "delete", alias, key)).waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun contentUriOf(type: SettingType): Uri? = when (type) {
        SettingType.GLOBAL -> Settings.Global.CONTENT_URI
        SettingType.SECURE -> Settings.Secure.CONTENT_URI
        SettingType.SYSTEM -> Settings.System.CONTENT_URI
        SettingType.ANDROID_PROP, SettingType.AXERON_ENV -> null
    }

    private fun querySettingsDirect(type: SettingType): Map<String, String> {
        val uri = contentUriOf(type) ?: return emptyMap()
        val map = mutableMapOf<String, String>()
        try {
            contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                val valIdx = cursor.getColumnIndex("value")
                if (nameIdx >= 0 && valIdx >= 0) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIdx) ?: continue
                        val value = cursor.getString(valIdx) ?: ""
                        map[name] = value
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private fun querySettings(type: SettingType): Map<String, String> {
        if (!Axeron.pingBinder()) {
            return querySettingsDirect(type)
        }
        val alias = type.alias
        val map = mutableMapOf<String, String>()

        try {
            val process = Axsh.exec(arrayOf("settings", "list", alias))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val splitIndex = line.indexOf('=')
                    if (splitIndex != -1) {
                        val name = line.substring(0, splitIndex).trim()
                        val value = line.substring(splitIndex + 1).trim()
                        map[name] = value
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return if (map.isNotEmpty()) map else querySettingsDirect(type)
    }


    // ======= AndroidProp =======
    private fun getAndroidProp(key: String): String? {
        if (!Axeron.pingBinder()) return null
        return try {
            val process = Axsh.exec(arrayOf(AxeronPluginService.RESETPROP, key))
            process.inputStream.bufferedReader().use { it.readText().trim() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun setAndroidProp(key: String, value: String): Boolean {
        if (!Axeron.pingBinder()) return false
        return try {
            Axsh.exec(arrayOf(AxeronPluginService.RESETPROP, key, value)).waitFor() == 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getAllAndroidProps(): Map<String, String> {
        if (!Axeron.pingBinder()) return emptyMap()
        val map = mutableMapOf<String, String>()
        try {
            val process = Axsh.exec(arrayOf(AxeronPluginService.RESETPROP))
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val match = Regex("\\[(.+?)]: \\[(.*?)]").find(line)
                    if (match != null) {
                        val (key, value) = match.destructured
                        map[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    // ======= Linux Env =======

    private fun getAxeronEnv(key: String): String? =
        if (Axeron.pingBinder()) Axeron.getEnvironment()?.envMap?.get(key) else null


    private val envMap: Map<String, String>
        get() = Axeron.getEnvironment(TYPE_NEW_ENV)?.envMap ?: emptyMap()

    private fun setAxeronEnv(key: String, value: String): Boolean {
        if (!Axeron.pingBinder()) return false
        val newEnvMap = HashMap(envMap)
        newEnvMap[key] = value
        return try {
            Axeron.setNewEnvironment(Environment(newEnvMap, true))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun unsetAxeronEnv(key: String): Boolean {
        if (!Axeron.pingBinder()) return false
        val newEnvMap = HashMap(envMap)
        newEnvMap.remove(key)
        return try {
            Axeron.setNewEnvironment(Environment(newEnvMap, true))
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun getAllLinuxEnv(): Map<String, String> {
        if (!Axeron.pingBinder()) return emptyMap()
        return Axeron.getEnvironment()?.envMap ?: emptyMap()
    }

    private companion object {
        val PROP_LINE = Regex("\\[(.+?)]: \\[(.*?)]")
    }
}
