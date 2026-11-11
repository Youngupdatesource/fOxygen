package frb.axeron.manager.ui.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import frb.axeron.api.Axeron
import frb.axeron.axsh.AxshConstants
import frb.axeron.manager.ui.util.HanziToPinyin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class PrivilegeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val FLAG_ALLOWED = 1 shl 1
        private const val FLAG_DENIED = 1 shl 2
        private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED
        private const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"
        private const val KEY_SHIZUKU_ONLY = "privilege_shizuku_only"
        private const val KEY_TERMUX_ONLY = "privilege_termux_only"
        private const val KEY_GRANTED_UIDS = "privilege_granted_uids"

        private val PRIVILEGE_ORDER = compareByDescending<AppsViewModel.AppInfo> { it.isAdded }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label }
    }

    private class LoadedApp(
        val packageInfo: PackageInfo,
        val label: String,
        val isGranted: Boolean,
        val usesShizuku: Boolean,
        val hasTermuxLib: Boolean
    )

    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var shizukuOnly by mutableStateOf(prefs.getBoolean(KEY_SHIZUKU_ONLY, true))
        private set

    fun updateShizukuOnly(value: Boolean) {
        shizukuOnly = value
        prefs.edit().putBoolean(KEY_SHIZUKU_ONLY, value).apply()
    }

    /** Hanya tampilkan app yang membawa libtermux.so (terminal berbasis Termux). */
    var termuxOnly by mutableStateOf(prefs.getBoolean(KEY_TERMUX_ONLY, false))
        private set

    fun updateTermuxOnly(value: Boolean) {
        termuxOnly = value
        prefs.edit().putBoolean(KEY_TERMUX_ONLY, value).apply()
    }

    private var cachedGranted by mutableStateOf(
        prefs.getStringSet(KEY_GRANTED_UIDS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    )

    private fun saveGrantedCache(uids: Set<Int>) {
        cachedGranted = uids
        prefs.edit().putStringSet(KEY_GRANTED_UIDS, uids.map { it.toString() }.toSet()).apply()
    }

    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    var search by mutableStateOf("")

    val privilegeList by derivedStateOf {
        val currentSearch = search
        val onlyShizuku = shizukuOnly
        val onlyTermux = termuxOnly

        privileges.values.asSequence()
            .filter { it.isNotSystemOrSelf() }
            .filter {
                when {
                    onlyShizuku && onlyTermux -> it.usesShizuku || it.hasTermuxLib
                    onlyShizuku -> it.usesShizuku
                    onlyTermux -> it.hasTermuxLib
                    else -> true
                }
            }
            .filter { app ->
                currentSearch.isEmpty() ||
                        app.label.contains(currentSearch, true) ||
                        app.packageName.contains(currentSearch, true) ||
                        (app.pinyin.isNotEmpty() && app.pinyin.contains(currentSearch, true))
            }
            .sortedWith(PRIVILEGE_ORDER)
            .toList()
    }

    // Helper Extension
    private fun AppsViewModel.AppInfo.isNotSystemOrSelf(): Boolean {
        val isSystem = (packageInfo.applicationInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isSelf = uid == android.os.Process.myUid()
        return !isSystem && !isSelf
    }


    var privileges by mutableStateOf<Map<Int, AppsViewModel.AppInfo>>(HashMap())
        private set

    val privilegedCount by derivedStateOf {
        if (privileges.isEmpty()) cachedGranted.size else privileges.values.count { it.isAdded }
    }

    fun granted(uid: Int): Boolean {
        return (Axeron.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
    }

    val isRootMode: Boolean
        get() = runCatching { Axeron.pingBinder() && Axeron.getAxeronInfo().isRoot() }.getOrDefault(false)

    fun axshRootless(uid: Int): Boolean =
        (Axeron.getFlagsForUid(uid, AxshConstants.FLAG_AXSH_ROOTLESS) and AxshConstants.FLAG_AXSH_ROOTLESS) != 0

    fun setAxshRootless(uid: Int, enabled: Boolean) {
        Axeron.updateFlagsForUid(
            uid,
            AxshConstants.FLAG_AXSH_ROOTLESS,
            if (enabled) AxshConstants.FLAG_AXSH_ROOTLESS else 0
        )
    }

    fun grant(uid: Int) {
        Axeron.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
        privileges = privileges.toMutableMap().apply {
            this[uid]?.let {
                this[uid] = it.copy(isAdded = true)
            }
        }
        saveGrantedCache(cachedGranted + uid)
    }

    fun revoke(uid: Int) {
        Axeron.updateFlagsForUid(uid, MASK_PERMISSION, 0)
        privileges = privileges.toMutableMap().apply {
            this[uid]?.let {
                this[uid] = it.copy(isAdded = false)
            }
        }
        saveGrantedCache(cachedGranted - uid)
    }


    private fun getApplications(): List<PackageInfo> {
        return application.packageManager.getInstalledPackages(PackageManager.GET_PERMISSIONS)
    }

    private var loadJob: Job? = null

    fun loadInstalledApps(refresh: Boolean = true) {
        if (isRefreshing && refresh) return

        // Hanya satu pemuatan yang boleh jalan: pemuatan lama yang selesai belakangan tidak
        // boleh menimpa hasil yang lebih baru.
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            isRefreshing = refresh
            val pm = application.packageManager
            // Status izin hanya valid kalau daemon terjangkau. Saat daemon sedang restart,
            // getFlagsForUid mengembalikan "tidak diizinkan" untuk semua app, itu yang mereset
            // daftar. Kalau tidak terjangkau, pakai status sebelumnya.
            val previous = privileges
            val reachableBefore = Axeron.pingBinder()
            val cached = cachedGranted

            val selfUid = android.os.Process.myUid()

            val result = withContext(Dispatchers.IO) {
                // Sebelumnya semua paket (termasuk ratusan app sistem) di-loadLabel dan di-IPC ke daemon
                // satu per satu, lalu baru dibuang oleh filter di UI. Buang dulu di sini.
                val packages = getApplications().filter {
                    val info = it.applicationInfo ?: return@filter false
                    (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && info.uid != selfUid
                }

                // loadLabel + getFlagsForUid (Binder) dibagi ke beberapa worker.
                val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                val chunkSize = ((packages.size + workers - 1) / workers).coerceAtLeast(1)
                val loaded = coroutineScope {
                    packages.chunked(chunkSize).map { chunk ->
                        async {
                            chunk.map { packageInfo ->
                                val appInfo = packageInfo.applicationInfo!!
                                val usesShizuku =
                                    packageInfo.requestedPermissions?.contains(SHIZUKU_PERMISSION) == true
                                val hasTermuxLib = TermuxLibScanner.has(packageInfo)
                                packageInfo.requestedPermissions = null
                                packageInfo.requestedPermissionsFlags = null
                                LoadedApp(
                                    packageInfo,
                                    appInfo.loadLabel(pm).toString(),
                                    if (reachableBefore) granted(appInfo.uid)
                                    else previous[appInfo.uid]?.isAdded ?: (appInfo.uid in cached),
                                    usesShizuku,
                                    hasTermuxLib
                                )
                            }
                        }
                    }.awaitAll().flatten()
                }

                // Pinyin tetap berurutan (HanziToPinyin singleton, thread-safety tidak dijamin).
                loaded.associate { app ->
                    app.packageInfo.applicationInfo!!.uid to AppsViewModel.AppInfo(
                        label = app.label,
                        packageInfo = app.packageInfo,
                        isAdded = app.isGranted,
                        pinyin = appSearchPinyin(app.label),
                        usesShizuku = app.usesShizuku,
                        hasTermuxLib = app.hasTermuxLib
                    )
                }
            }

            val trusted = reachableBefore && Axeron.pingBinder()
            privileges = if (trusted) {
                result
            } else {
                result.mapValues { (uid, app) ->
                    previous[uid]?.let { app.copy(isAdded = it.isAdded) } ?: app
                }
            }
            if (trusted) {
                saveGrantedCache(result.values.filter { it.isAdded }.map { it.uid }.toSet())
            }
            isRefreshing = false
        }
    }
}

/**
 * Apakah sebuah app membawa libtermux.so (terminal berbasis Termux).
 *
 * Dicek dulu di folder native yang sudah diekstrak (murah), lalu di dalam APK dan split-nya:
 * app modern sering memasang lib tanpa ekstrak (extractNativeLibs=false), jadi foldernya kosong
 * dan lib hanya ada di dalam zip. Hanya daftar isi zip yang dibaca. Hasil disimpan per paket
 * dan dibuang saat app diperbarui (lastUpdateTime berubah), jadi refresh tidak memindai ulang.
 */
private object TermuxLibScanner {
    private const val LIB_NAME = "libtermux.so"
    private val cache = ConcurrentHashMap<String, Pair<Long, Boolean>>()

    fun has(info: PackageInfo): Boolean {
        val app = info.applicationInfo ?: return false
        cache[info.packageName]?.let { (stamp, found) ->
            if (stamp == info.lastUpdateTime) return found
        }
        val found = scan(app)
        cache[info.packageName] = info.lastUpdateTime to found
        return found
    }

    private fun scan(app: ApplicationInfo): Boolean {
        val libDir = app.nativeLibraryDir
        if (libDir != null && File(libDir, LIB_NAME).exists()) return true
        val apks = ArrayList<String>()
        app.sourceDir?.let { apks.add(it) }
        app.splitSourceDirs?.let { apks.addAll(it) }
        return apks.any { containsLib(it) }
    }

    private fun containsLib(apkPath: String): Boolean = try {
        ZipFile(apkPath).use { zip ->
            val entries = zip.entries()
            var found = false
            while (!found && entries.hasMoreElements()) {
                val name = entries.nextElement().name
                found = name.startsWith("lib/") && name.endsWith("/$LIB_NAME")
            }
            found
        }
    } catch (_: Exception) {
        false
    }
}
