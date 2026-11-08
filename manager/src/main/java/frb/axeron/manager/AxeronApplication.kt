package frb.axeron.manager

import android.annotation.SuppressLint
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.topjohnwu.superuser.Shell
import frb.axeron.Axerish
import frb.axeron.api.core.AxeronSettings
import frb.axeron.api.core.Engine
import frb.axeron.manager.service.ServerWatch
import frb.axeron.manager.ui.component.BannerFrameCache
import frb.axeron.manager.ui.util.ThrottledAppIconFetcher
import frb.axeron.manager.ui.util.createShellBuilder
import me.zhanghai.android.appiconloader.coil.AppIconKeyer
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.util.Locale

class AxeronApplication : Engine(), ImageLoaderFactory {
    companion object {
        private const val ICON_MEMORY_CACHE_BYTES = 16 * 1024 * 1024
        private const val IMAGE_DISK_CACHE_BYTES = 48L * 1024 * 1024

        lateinit var axeronApp: AxeronApplication

        init {
//            logd("ShizukuApplication", "init")
            Axerish.initialize(BuildConfig.APPLICATION_ID)
            Shell.setDefaultBuilder(createShellBuilder())
            Shell.enableLegacyStderrRedirection = true
            Shell.enableVerboseLogging = BuildConfig.DEBUG

            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            // libadb is not loaded here: AdbPairingClient loads it itself, the first time pairing
            // is used. Loading it at process start cost a dlopen on the main thread of every
            // cold start, for a library most launches never touch.
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        axeronApp = this
        AxeronSettings.initialize(axeronApp)
    }

    override fun onCreate() {
        super.onCreate()
        // Starts reading the banner stills saved by the last run back into memory, in the
        // background, so Home and the Plugins tab do not open with blank banners.
        BannerFrameCache.preload(this)
        ServerWatch.start(this)
    }

    @Volatile
    private var imageLoaderCreated = false

    /**
     * Coil asks for its loader the first time an image is requested, not at process start: building
     * it (decoders, memory cache, the icon fetcher) is work a cold start does not need before the
     * first frame.
     */
    @SuppressLint("ResourceType")
    override fun newImageLoader(): ImageLoader {
        imageLoaderCreated = true
        val context = this
        val iconSize = resources.getDimensionPixelSize(android.R.dimen.app_icon_size)
        return ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(ThrottledAppIconFetcher.Factory(iconSize, context))
                // ImageDecoder (API 28+) mendekode frame GIF/WebP animasi langsung di ukuran target
                // dan hemat memori; GifDecoder hanya untuk API 26-27.
                if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory())
                else add(GifDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(ICON_MEMORY_CACHE_BYTES)
                    .build()
            }
            // Remote banners (plugin banner URLs) were downloaded again on every cold start, and
            // stayed blank until they arrived. Local files and in-memory banners never use this.
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "coil_images"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    val okhttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().cache(Cache(File(cacheDir, "okhttp"), 10 * 1024 * 1024))
            .addInterceptor { block ->
                block.proceed(
                    block.request().newBuilder()
                        .header("User-Agent", "AxManager/${BuildConfig.VERSION_CODE}")
                        .header("Accept-Language", Locale.getDefault().toLanguageTag()).build()
                )
            }.build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Not created yet: nothing to clear (and asking for it here would create it).
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && imageLoaderCreated) {
            Coil.imageLoader(this).memoryCache?.clear()
        }
    }
}
