package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Animatable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Memory of the last frame drawn for a banner, plus the size it was drawn at.
 *
 * Banners live inside tabs that leave the composition on every tab switch/swipe. On return the
 * image used to be re-requested from scratch (GIFs are not kept by Coil's memory cache, and
 * the area size is only known after the first layout), so the banner was blank for a frame or
 * more and then popped in. With the last frame kept here it is drawn on the very first frame.
 *
 * The frames are also kept on disk (one small still per banner). The memory part dies with the
 * process, so after the app had been closed for a while every banner (Home, and every plugin
 * card when the app reopens on the Plugins tab) started blank again and then all of them
 * decoded at once. [preload] reads the stills back before the first frame, in the background,
 * while the splash screen is still up.
 */
internal object BannerFrameCache {
    private const val MAX_BYTES = 16 * 1024 * 1024

    private const val DIR_NAME = "banner_frames"
    private const val FILE_EXT = ".webp"
    private const val PRELOAD_MAX_FILES = 24
    private const val DISK_MAX_FILES = 48
    private const val DISK_MAX_BYTES = 12L * 1024 * 1024
    private const val PERSIST_MAX_EDGE = 640
    private const val PERSIST_QUALITY = 85
    private const val REFRESH_AFTER_MS = 24L * 60 * 60 * 1000

    // Both maps are keyed by id(key), a hash of the banner's cache key: that is also what names
    // the file on disk, so a frame read back from disk needs no knowledge of the original key.
    private val frames = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val sizes = HashMap<String, IntSize>()

    /** Bumps when frames arrive from disk, so cards composed a moment earlier pick them up. */
    var revision by mutableIntStateOf(0)
        private set

    /** True once [preload] has finished (or had nothing to do). */
    @Volatile
    var preloaded = false
        private set

    @Volatile
    private var appContext: Context? = null

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "banner-frames").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
    }

    // Lossy WebP keeps the alpha channel of transparent PNG banners and stays small.
    @Suppress("DEPRECATION")
    private val webpFormat: Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    private fun id(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun dir(context: Context): File =
        File(context.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun diskFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_EXT) }?.toList() ?: emptyList()

    @Synchronized
    fun frame(key: String): Bitmap? = frames.get(id(key))

    @Synchronized
    fun size(key: String): IntSize = sizes[id(key)] ?: IntSize.Zero

    @Synchronized
    fun putSize(key: String, size: IntSize) {
        if (sizes.size > 64) sizes.clear()
        sizes[id(key)] = size
    }

    /**
     * Keeps [bitmap] as the frame for [key]. With [replace] false an existing frame stays; a new
     * frame is also written to disk for the next time the app starts.
     */
    fun putFrame(key: String, bitmap: Bitmap, replace: Boolean = false) {
        if (bitmap.allocationByteCount > MAX_BYTES / 2) return
        val frameId = id(key)
        val stored = synchronized(this) {
            if (replace || frames.get(frameId) == null) {
                frames.put(frameId, bitmap)
                true
            } else false
        }
        if (stored) persist(frameId, bitmap)
    }

    /** Called once from Application.onCreate: starts reading the stills back in the background. */
    fun preload(context: Context) {
        val app = context.applicationContext
        appContext = app
        worker.execute {
            try {
                val files = diskFiles(dir(app))
                    .sortedByDescending { it.lastModified() }
                    .take(PRELOAD_MAX_FILES)
                val now = System.currentTimeMillis()
                for (file in files) {
                    val parsed = parseName(file.name) ?: continue
                    val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
                    if (bitmap == null) {
                        file.delete()
                        continue
                    }
                    synchronized(this) {
                        if (frames.get(parsed.first) == null) frames.put(parsed.first, bitmap)
                        if (parsed.second != IntSize.Zero && sizes[parsed.first] == null) {
                            sizes[parsed.first] = parsed.second
                        }
                    }
                    // Used again: keep it among the newest, so unused stills are what ages out.
                    file.setLastModified(now)
                }
            } catch (_: Throwable) {
                // A missing still only means a blank first frame, as before.
            } finally {
                preloaded = true
                Handler(Looper.getMainLooper()).post { revision++ }
            }
        }
    }

    // File name: "<id>_<width>x<height>.webp" (the size the banner was drawn at, 0x0 if unknown).
    private fun parseName(name: String): Pair<String, IntSize>? {
        val base = name.removeSuffix(FILE_EXT)
        val cut = base.indexOf('_')
        if (cut <= 0) return null
        val dims = base.substring(cut + 1).split('x')
        val w = dims.getOrNull(0)?.toIntOrNull() ?: 0
        val h = dims.getOrNull(1)?.toIntOrNull() ?: 0
        return base.substring(0, cut) to IntSize(w, h)
    }

    private fun persist(frameId: String, bitmap: Bitmap) {
        val app = appContext ?: return
        worker.execute {
            runCatching {
                val dir = dir(app)
                val existing = diskFiles(dir).firstOrNull { it.name.startsWith("${frameId}_") }
                // A still that is already there is only rewritten once it is a day old, in case
                // the banner file behind the same key was swapped.
                if (existing != null &&
                    System.currentTimeMillis() - existing.lastModified() < REFRESH_AFTER_MS
                ) return@runCatching

                var source = bitmap
                if (source.config == Bitmap.Config.HARDWARE) {
                    source = source.copy(Bitmap.Config.ARGB_8888, false) ?: return@runCatching
                }
                val edge = maxOf(source.width, source.height)
                if (edge > PERSIST_MAX_EDGE) {
                    val ratio = PERSIST_MAX_EDGE.toFloat() / edge
                    source = Bitmap.createScaledBitmap(
                        source,
                        (source.width * ratio).toInt().coerceAtLeast(1),
                        (source.height * ratio).toInt().coerceAtLeast(1),
                        true
                    )
                }
                val drawn = synchronized(this) { sizes[frameId] } ?: IntSize.Zero
                val tmp = File(dir, "$frameId.tmp")
                FileOutputStream(tmp).use { source.compress(webpFormat, PERSIST_QUALITY, it) }
                existing?.delete()
                val target = File(dir, "${frameId}_${drawn.width}x${drawn.height}$FILE_EXT")
                if (!tmp.renameTo(target)) tmp.delete()
                trim(dir)
            }
        }
    }

    private fun trim(dir: File) {
        val files = diskFiles(dir).sortedByDescending { it.lastModified() }
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= DISK_MAX_FILES || bytes > DISK_MAX_BYTES) file.delete()
        }
    }
}

/** Whether a still frame is already known for [cacheKey] (in memory, or read back from disk). */
fun hasBannerFrame(cacheKey: String): Boolean = BannerFrameCache.frame(cacheKey) != null

/**
 * Screens drawn only as a preview (the swipe-between-tabs peek) switch banner animation off:
 * a GIF decoding and redrawing every frame under a moving page is what made transitions lag.
 */
val LocalBannerAnimationAllowed = compositionLocalOf { true }

/**
 * Banners animate only while their own screen is fully RESUMED. A navigation entry is not
 * resumed while it slides in or out, so during a transition the banner shows its still frame
 * and starts moving once the screen has settled.
 */
@Composable
fun rememberBannerAnimate(): Boolean {
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return state.isAtLeast(Lifecycle.State.RESUMED) && LocalBannerAnimationAllowed.current
}

/**
 * Banner (image/GIF/WebP) that fills its parent without flicker when it re-enters the
 * composition.
 *
 * - Drawn through Modifier.paint(sizeToIntrinsics = false) so the image never influences the
 *   height of the card around it (the parent decides the size).
 * - The cached last frame is shown until the live painter has loaded, then swapped in the same
 *   composition, so the two never blend.
 * - [animate] = false keeps only the cached frame (no live painter, so a GIF stops animating).
 */
@Composable
fun CachedBannerImage(
    cacheKey: String,
    model: Any?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    alignment: Alignment = Alignment.Center,
    animate: Boolean = rememberBannerAnimate(),
) {
    val context = LocalContext.current
    var areaSize by remember(cacheKey) { mutableStateOf(BannerFrameCache.size(cacheKey)) }
    var cached by remember(cacheKey) { mutableStateOf(BannerFrameCache.frame(cacheKey)?.asImageBitmap()) }

    // Stills read back from disk a moment after this card was composed (cold start): the cache
    // announces them through its revision, and they are used on the next frame.
    val revision = BannerFrameCache.revision
    val warmed = remember(cacheKey, revision) { BannerFrameCache.frame(cacheKey)?.asImageBitmap() }
    val frame = cached ?: warmed
    // The size the banner was last drawn at, known before the first layout (also from disk).
    val knownSize = remember(cacheKey, revision) { BannerFrameCache.size(cacheKey) }
    val area = if (areaSize != IntSize.Zero) areaSize else knownSize

    // Animated painters wait until the app's entrance has settled, as long as a still frame is
    // there to show meanwhile: opening the app used to start every GIF decoder at once, on top
    // of the entrance animation. Without any still frame the banner is loaded right away.
    val settled = StartupMotion.settled

    Box(
        modifier = modifier.onSizeChanged {
            if (it.width > 0 && it.height > 0 && it != areaSize) {
                areaSize = it
                BannerFrameCache.putSize(cacheKey, it)
            }
        }
    ) {
        val live = animate && model != null && area.width > 0 && area.height > 0 &&
            (settled || frame == null)
        val painter = if (live) {
            rememberAsyncImagePainter(
                model = ImageRequest.Builder(context)
                    .data(model)
                    .memoryCacheKey("$cacheKey:${area.width}x${area.height}")
                    .size(area.width, area.height)
                    .build(),
                contentScale = ContentScale.Crop
            )
        } else null
        // Coil 2.x: painter.state is a Compose-observable value (not a StateFlow).
        val success = painter?.state as? AsyncImagePainter.State.Success

        LaunchedEffect(success) {
            if (success != null && BannerFrameCache.frame(cacheKey) == null) {
                runCatching { success.result.drawable.toBitmap() }
                    .getOrNull()
                    ?.let {
                        BannerFrameCache.putFrame(cacheKey, it)
                        cached = it.asImageBitmap()
                    }
            }
        }

        // When the live painter leaves (transition started, tab left) keep the frame it was
        // showing, so the banner freezes where it was instead of jumping back to frame 0.
        DisposableEffect(success) {
            onDispose {
                val drawable = success?.result?.drawable
                if (drawable is Animatable) {
                    runCatching { drawable.toBitmap() }.getOrNull()?.let {
                        BannerFrameCache.putFrame(cacheKey, it, replace = true)
                        cached = it.asImageBitmap()
                    }
                }
            }
        }

        // The cached frame stays until the live painter has an image, then the two swap in the
        // same composition (never both drawn, so no double alpha and no blank frame).
        if (frame != null && success == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .paint(
                        BitmapPainter(frame),
                        sizeToIntrinsics = false,
                        alignment = alignment,
                        contentScale = ContentScale.Crop,
                        alpha = alpha
                    )
            )
        }
        if (painter != null && (success != null || frame == null)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .paint(
                        painter,
                        sizeToIntrinsics = false,
                        alignment = alignment,
                        contentScale = ContentScale.Crop,
                        alpha = alpha
                    )
            )
        }
    }
}
