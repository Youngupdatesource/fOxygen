package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import frb.axeron.api.Axeron
import frb.axeron.server.PluginInfo
import frb.axeron.shared.AxeronApiConstant
import frb.axeron.shared.PathHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Plugin banners, prepared before the list shows them.
 *
 * The card draws a banner only once it is composed, and a LazyColumn composes a card only when it
 * is about to be seen. Until then nothing was read, so each banner went: blank card -> Binder read
 * of the file -> decode -> pop in, which looked like flicker and delay while scrolling or after a
 * tab switch. Here every banner's file is read and one downscaled still frame is decoded as soon
 * as the plugin list is known. That frame goes into [BannerFrameCache] under the same key the card
 * uses, so [CachedBannerImage] has it on the card's very first frame; the live (animated) painter
 * then takes over exactly as before.
 */

/** Bytes of banner files already read, so a file is not fetched over Binder twice. */
internal object PluginBannerBytes {
    private val cache = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    fun get(key: String): ByteArray? = synchronized(this) { cache.get(key) }
    fun put(key: String, value: ByteArray) = synchronized(this) { cache.put(key, value) }
}

/** The key the card and the prefetch both use; they must agree for the frame to be found. */
internal fun pluginBannerKey(plugin: PluginInfo): String =
    "plugin_banner:${plugin.prop.id}:${plugin.prop.banner}"

/** Reads a plugin's local banner file through the server (blocking: call off the main thread). */
internal fun readPluginBannerBytes(plugin: PluginInfo): ByteArray? = try {
    val dir = File(
        PathHelper.getWorkingPath(
            Axeron.getAxeronInfo().isRoot(),
            AxeronApiConstant.folder.PARENT_PLUGIN
        ),
        plugin.prop.id
    )
    Axeron.newFileService()
        .setFileInputStream(File(dir, plugin.prop.banner).absolutePath)
        ?.use { it.readBytes() }
} catch (e: Exception) {
    null
}

private val bannerReadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val bannerReadsInFlight = HashMap<String, Deferred<ByteArray?>>()

// Binder reads go to the server one after another, never as a burst.
private val bannerReadGate = Semaphore(2)

/**
 * A plugin banner's bytes, read at most once even when the prefetch and the card ask for the same
 * file at the same time (they used to read it twice, each over Binder, right when the Plugins tab
 * is also busy coming up). The read is shared, so one caller leaving does not cancel it.
 */
internal suspend fun loadPluginBannerBytes(plugin: PluginInfo): ByteArray? {
    val key = pluginBannerKey(plugin)
    PluginBannerBytes.get(key)?.let { return it }
    val read = synchronized(bannerReadsInFlight) {
        bannerReadsInFlight.getOrPut(key) {
            bannerReadScope.async {
                try {
                    bannerReadGate.withPermit {
                        PluginBannerBytes.get(key)
                            ?: readPluginBannerBytes(plugin)?.also { PluginBannerBytes.put(key, it) }
                    }
                } finally {
                    synchronized(bannerReadsInFlight) { bannerReadsInFlight.remove(key) }
                }
            }
        }
    }
    return read.await()
}

private const val MAX_FRAME_EDGE = 720

/** Warms [BannerFrameCache] with one still frame per plugin banner. Safe to call repeatedly. */
suspend fun prefetchPluginBanners(context: Context, plugins: List<PluginInfo>) {
    val app = context.applicationContext
    val useBanner = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getBoolean("use_banner", true)
    if (!useBanner) return
    withContext(Dispatchers.IO) {
        for (plugin in plugins) {
            if (plugin.prop.banner.isEmpty()) continue
            val key = pluginBannerKey(plugin)
            if (BannerFrameCache.frame(key) != null) continue
            val frame = try {
                loadFrame(app, plugin, key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: continue
            BannerFrameCache.putFrame(key, frame)
        }
    }
}

private suspend fun loadFrame(context: Context, plugin: PluginInfo, key: String): Bitmap? {
    val banner = plugin.prop.banner
    if (banner.startsWith("http", true)) {
        // Same URL the card requests later, so the download is not wasted: Coil keeps it on disk.
        val request = ImageRequest.Builder(context)
            .data(banner)
            .size(MAX_FRAME_EDGE)
            .allowHardware(false)
            .build()
        val result = context.imageLoader.execute(request)
        return (result as? SuccessResult)?.drawable?.toBitmap()
    }
    val bytes = loadPluginBannerBytes(plugin) ?: return null
    return decodeSampled(bytes)
}

/** First frame of an image/GIF/WebP, downscaled by powers of two to at most [MAX_FRAME_EDGE]. */
private fun decodeSampled(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_FRAME_EDGE) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}
