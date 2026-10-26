package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.drawable.Animatable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Text colour used on a light fade. Fixed: the theme's own onSurface is light in a dark theme. */
private val DARK_TEXT = Color(0xFF1C1B1F)

/**
 * What the status card draws over its banner so the text on it stays readable. All of it is
 * worked out from the banner itself:
 *
 * - the colour of the fade behind the title/version line ([scrimTop]) and behind the uptime
 *   ([scrimBottom]), taken from the picture so the fade melts into it;
 * - how thick each is ([top], [bottom]): the least that keeps the text readable;
 * - the colour of the text itself ([content]): dark on a light fade, white on a dark one.
 *
 * How far a fade reaches follows from how thick it is (see [brush]).
 */
@Immutable
data class BannerTone(
    val content: Color,
    val scrimTop: Color,
    val scrimBottom: Color,
    val top: Float,
    val bottom: Float,
) {
    /**
     * Two fades laid over each other and flattened into one gradient: the top one is solid behind
     * the title/version line and eases out below it, the bottom one eases in above the uptime and
     * is solid behind it. Both ease with smoothstep, and a thicker fade reaches further, so there
     * is never a visible edge where a fade ends, and between the two the picture shows as it is.
     */
    fun brush(): Brush {
        val topEnd = TOP_HOLD + FEATHER_BASE + FEATHER_GAIN * top
        val bottomBegin = BOTTOM_HOLD - (FEATHER_BASE + FEATHER_GAIN * bottom)
        val stops = ArrayList<Pair<Float, Color>>(STEPS + 1)
        for (i in 0..STEPS) {
            val y = i / STEPS.toFloat()
            val aTop = top * (1f - smooth(y, TOP_HOLD, topEnd))
            val aBottom = bottom * smooth(y, bottomBegin, BOTTOM_HOLD)
            val a = aTop + aBottom * (1f - aTop) // the bottom fade lies under the top one
            val color = if (a <= 0f) {
                scrimTop.copy(alpha = 0f)
            } else {
                val wTop = aTop / a
                val wBottom = 1f - wTop
                Color(
                    red = scrimTop.red * wTop + scrimBottom.red * wBottom,
                    green = scrimTop.green * wTop + scrimBottom.green * wBottom,
                    blue = scrimTop.blue * wTop + scrimBottom.blue * wBottom,
                    alpha = a
                )
            }
            stops.add(y to color)
        }
        return Brush.verticalGradient(colorStops = stops.toTypedArray())
    }

    companion object {
        private const val STEPS = 40

        // Fractions of the card's height: the top fade is solid down to TOP_HOLD (the end of the
        // version line), the bottom one from BOTTOM_HOLD (the start of the uptime) down.
        private const val TOP_HOLD = 0.34f
        private const val BOTTOM_HOLD = 0.82f
        private const val FEATHER_BASE = 0.14f
        private const val FEATHER_GAIN = 0.18f

        private fun smooth(y: Float, from: Float, to: Float): Float {
            if (to <= from) return if (y >= to) 1f else 0f
            val t = ((y - from) / (to - from)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Before the banner has been looked at (and if it can't be): the theme's own look. */
        fun fallback(dark: Boolean): BannerTone =
            if (dark) BannerTone(Color.White, Color.Black, Color.Black, 0.25f, 0.60f)
            else BannerTone(DARK_TEXT, Color.White, Color.White, 0.30f, 0.55f)
    }
}

/**
 * Looks at the part of the banner the status card shows and decides what goes over it. The
 * decision is the banner's, not the theme's: the same banner gets the same treatment in a light
 * and in a dark theme (the theme only breaks a tie).
 *
 * - Two ways to make the text readable are tried: a light fade with dark text and a dark fade
 *   with white text. Each fade takes the banner's own colour (androidx Palette's dominant swatch
 *   in each text zone, pulled towards white or black), and for each the least opacity at which
 *   the text keeps its contrast over the picture is measured. The cheaper one wins, so a dark
 *   banner never gets a light haze laid over it just because the theme is light, and a bright
 *   one never gets a heavy dark blanket.
 * - A busy picture (lots of contrast or detail behind the text) gets extra fade on top of the
 *   measured contrast: the text can pass the measurement and still be lost among the detail.
 * - An animated banner (GIF) is measured over its whole loop: [FRAME_COUNT] frames spread over it
 *   are sampled and all their pixels count together, so a bright moment later in the loop pushes
 *   the fade up before it happens instead of colliding with the text. The tint comes from the
 *   average of those frames, so it stays put while the picture moves. Other animated formats
 *   (animated WebP) cannot be sampled frame by frame; their first frame is measured and the fade
 *   gets [ANIMATED_EXTRA] more as a precaution.
 * - Nothing is a fixed amount: a calm picture gets hardly any fade, a hard one nearly opaque.
 */
internal object BannerAnalyzer {
    private const val SAMPLE_SIDE = 256
    private const val MAX_ALPHA = 0.95f
    private const val ALPHA_STEP = 0.025f
    private const val TARGET_CONTRAST = 5.0f
    private const val FAIL_SHARE = 0.04f

    // How far the banner's colour is pulled towards white (light theme) / black (dark theme).
    private const val TINT_LIGHT = 0.80f
    private const val TINT_DARK = 0.62f

    // Extra opacity on top of what the measurement asks for: the picture is only sampled once (the
    // first frame of a GIF), and a different font size or a busy edge must not tip it over.
    private const val SAFETY = 0.05f

    // Frames sampled from a GIF, and the least time between two of them.
    private const val FRAME_COUNT = 12
    private const val FRAME_MIN_STEP_MS = 80

    // Added to the margin for an animated picture whose later frames can't be seen.
    private const val ANIMATED_EXTRA = 0.10f

    // A fade is never thinner than this where it is used at all, so the card always has one.
    private const val FLOOR_TOP = 0.08f
    private const val FLOOR_BOTTOM = 0.30f

    // The theme's own way (light fade in a light theme, dark in a dark one) keeps the win unless
    // the other needs this much less fade.
    private const val THEME_BIAS = 0.10f

    // Busyness: spread (standard deviation) of the luminance behind the text. From BUSY_FROM it
    // counts, at BUSY_FROM + BUSY_RANGE it counts in full, and then adds up to BUSY_EXTRA fade.
    private const val BUSY_FROM = 0.10f
    private const val BUSY_RANGE = 0.20f
    private const val BUSY_EXTRA = 0.20f

    // Where the text is, as fractions of the card (x0, y0, x1, y1).
    private val TOP_ZONE = floatArrayOf(0.03f, 0.04f, 0.62f, 0.42f)
    private val BOTTOM_ZONE = floatArrayOf(0.03f, 0.76f, 0.50f, 0.97f)

    /**
     * [frames] are all the same size; one for a still picture, several for a GIF. [mean] is their
     * average (the picture itself for a still one). [animated] = the picture moves, whether or not
     * its frames could be read.
     */
    private class Sample(
        val width: Int,
        val height: Int,
        val frames: List<IntArray>,
        val mean: IntArray,
        val animated: Boolean,
    )

    private var sampleVersion = Long.MIN_VALUE
    private var sample: Sample? = null

    private val tones = object : LinkedHashMap<String, BannerTone>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BannerTone>?) =
            size > 32
    }

    private fun key(version: Long, fx: Float, fy: Float, dark: Boolean, backdrop: Color) =
        "$version|$fx|$fy|$dark|${backdrop.toArgb()}"

    @Synchronized
    fun cached(version: Long, fx: Float, fy: Float, dark: Boolean, backdrop: Color): BannerTone? =
        tones[key(version, fx, fy, dark, backdrop)]

    /** Blocking (decodes the picture once per banner); call off the main thread. Null: no result. */
    suspend fun analyze(
        context: Context,
        file: File,
        version: Long,
        fx: Float,
        fy: Float,
        dark: Boolean,
        backdrop: Color,
    ): BannerTone? {
        cached(version, fx, fy, dark, backdrop)?.let { return it }
        val s = sampleOf(context, file, version) ?: return null
        val tone = decide(s, fx, fy, dark, backdrop)
        synchronized(this) { tones[key(version, fx, fy, dark, backdrop)] = tone }
        return tone
    }

    private suspend fun sampleOf(context: Context, file: File, version: Long): Sample? {
        synchronized(this) {
            if (sampleVersion == version) sample?.let { return it }
        }
        val request = ImageRequest.Builder(context)
            .data(file)
            .size(SAMPLE_SIDE, SAMPLE_SIDE)
            .scale(Scale.FIT)
            .allowHardware(false) // pixels must be readable
            .build()
        val drawable = (context.imageLoader.execute(request) as? SuccessResult)?.drawable
            ?: return null
        val bitmap = runCatching { drawable.toBitmap(config = Bitmap.Config.ARGB_8888) }
            .getOrNull() ?: return null
        val taken = try {
            val width = bitmap.width
            val height = bitmap.height
            val first = IntArray(width * height)
            bitmap.getPixels(first, 0, width, 0, 0, width, height)
            val animated = drawable is Animatable
            // A GIF is sampled over its whole loop; anything else (or a GIF that can't be read
            // that way) is just the frame the loader gave.
            val frames = if (animated) gifFrames(file, width, height) else null
            val all = frames ?: listOf(first)
            Sample(width, height, all, meanOf(all), animated)
        } finally {
            bitmap.recycle()
        }
        synchronized(this) {
            sampleVersion = version
            sample = taken
        }
        return taken
    }

    private fun isGif(file: File): Boolean = runCatching {
        file.inputStream().use {
            val h = ByteArray(4)
            it.read(h) == 4 && h[0] == 'G'.code.toByte() && h[1] == 'I'.code.toByte() &&
                h[2] == 'F'.code.toByte() && h[3] == '8'.code.toByte()
        }
    }.getOrDefault(false)

    /**
     * [FRAME_COUNT] frames spread over a GIF's loop, scaled to [width] x [height] (the size the
     * first frame was loaded at), or null if the file is not a GIF or can't be read. Movie is old
     * but is the one framework class that can be asked for the picture at a given time.
     */
    @Suppress("DEPRECATION")
    private fun gifFrames(file: File, width: Int, height: Int): List<IntArray>? {
        if (!isGif(file)) return null
        return runCatching {
            val movie = Movie.decodeFile(file.absolutePath) ?: return@runCatching null
            val mw = movie.width()
            val mh = movie.height()
            val duration = movie.duration()
            if (mw <= 0 || mh <= 0 || duration <= 0) return@runCatching null

            val count = min(FRAME_COUNT, max(2, duration / FRAME_MIN_STEP_MS))
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                val out = ArrayList<IntArray>(count)
                for (i in 0 until count) {
                    movie.setTime((duration.toLong() * i / count).toInt())
                    bitmap.eraseColor(0)
                    canvas.save()
                    canvas.scale(width / mw.toFloat(), height / mh.toFloat())
                    movie.draw(canvas, 0f, 0f)
                    canvas.restore()
                    val px = IntArray(width * height)
                    bitmap.getPixels(px, 0, width, 0, 0, width, height)
                    out.add(px)
                }
                out
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    /** Per-pixel average of equally sized frames. */
    private fun meanOf(frames: List<IntArray>): IntArray {
        val first = frames[0]
        if (frames.size == 1) return first
        val n = frames.size
        val out = IntArray(first.size)
        for (i in first.indices) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (f in frames) {
                val p = f[i]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            out[i] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
        return out
    }

    private fun decide(s: Sample, fx: Float, fy: Float, dark: Boolean, backdrop: Color): BannerTone {
        // The part of the picture the card shows (ContentScale.Crop, moved by the focus point).
        val boxW = STATUS_BANNER_ASPECT
        val boxH = 1f
        val scale = max(boxW / s.width, boxH / s.height)
        val visW = boxW / scale
        val visH = boxH / scale
        val offX = (s.width - visW) * fx.coerceIn(0f, 1f)
        val offY = (s.height - visH) * fy.coerceIn(0f, 1f)

        val top = zone(s, offX, offY, visW, visH, TOP_ZONE, backdrop)
        val bottom = zone(s, offX, offY, visW, visH, BOTTOM_ZONE, backdrop)

        val darkTextLum = luminance(0x1C, 0x1B, 0x1F)

        // Way one: a light fade with dark text. Way two: a dark fade with white text.
        val lightTop = fadeRgb(s, offX, offY, visW, visH, TOP_ZONE, false, top)
        val lightBottom = fadeRgb(s, offX, offY, visW, visH, BOTTOM_ZONE, false, bottom)
        val darkTop = fadeRgb(s, offX, offY, visW, visH, TOP_ZONE, true, top)
        val darkBottom = fadeRgb(s, offX, offY, visW, visH, BOTTOM_ZONE, true, bottom)

        // Least opacity at which each keeps the text readable; null = even MAX_ALPHA is not enough.
        val needLightTop = neededAlpha(top, lightTop, darkTextLum)
        val needLightBottom = neededAlpha(bottom, lightBottom, darkTextLum)
        val needDarkTop = neededAlpha(top, darkTop, 1f)
        val needDarkBottom = neededAlpha(bottom, darkBottom, 1f)

        val costLight = ((needLightTop ?: 1f) + (needLightBottom ?: 1f)) / 2f
        val costDark = ((needDarkTop ?: 1f) + (needDarkBottom ?: 1f)) / 2f
        val useLight =
            if (dark) costLight + THEME_BIAS < costDark else costLight <= costDark + THEME_BIAS

        // Margin: the picture is sampled, a busy one hides text that measures fine, and a moving
        // picture whose frames could not be read is only known from its first one.
        val unsure = s.animated && s.frames.size < 2
        val margin = SAFETY + if (unsure) ANIMATED_EXTRA else 0f
        val marginTop = margin + BUSY_EXTRA * busyness(top)
        val marginBottom = margin + BUSY_EXTRA * busyness(bottom)

        return BannerTone(
            content = if (useLight) DARK_TEXT else Color.White,
            scrimTop = Color(OPAQUE or (if (useLight) lightTop else darkTop)),
            scrimBottom = Color(OPAQUE or (if (useLight) lightBottom else darkBottom)),
            top = thick(if (useLight) needLightTop else needDarkTop, FLOOR_TOP, marginTop),
            bottom = thick(if (useLight) needLightBottom else needDarkBottom, FLOOR_BOTTOM, marginBottom),
        )
    }

    /** Opacity for a zone from what it needs: needed + [margin], at least [floor], null = all. */
    private fun thick(need: Float?, floor: Float, margin: Float): Float =
        if (need == null) MAX_ALPHA else min(MAX_ALPHA, max(floor, need + margin))

    /** 0..1: how much the luminance behind the text varies (detail, edges, motion). */
    private fun busyness(pixels: IntArray): Float {
        if (pixels.size < 2) return 0f
        var sum = 0.0
        var sum2 = 0.0
        for (p in pixels) {
            val l = luminance((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toDouble()
            sum += l
            sum2 += l * l
        }
        val n = pixels.size
        val mean = sum / n
        val spread = sqrt(max(0.0, sum2 / n - mean * mean)).toFloat()
        return ((spread - BUSY_FROM) / BUSY_RANGE).coerceIn(0f, 1f)
    }

    private val OPAQUE = 0xFF000000.toInt()

    /**
     * The fade colour (0xRRGGBB) for one zone: the banner's dominant colour there, pulled towards
     * white or black depending on the theme. Palette works on the same bitmap region the zone
     * covers; [pixels] (what [zone] collected) is the fallback for a picture Palette filters out.
     */
    private fun fadeRgb(
        s: Sample,
        offX: Float,
        offY: Float,
        visW: Float,
        visH: Float,
        z: FloatArray,
        dark: Boolean,
        pixels: IntArray,
    ): Int {
        val x0 = (offX + z[0] * visW).toInt().coerceIn(0, s.width - 1)
        val x1 = (offX + z[2] * visW).toInt().coerceIn(x0 + 1, s.width)
        val y0 = (offY + z[1] * visH).toInt().coerceIn(0, s.height - 1)
        val y1 = (offY + z[3] * visH).toInt().coerceIn(y0 + 1, s.height)

        val dominant = runCatching {
            val bitmap = Bitmap.createBitmap(s.mean, s.width, s.height, Bitmap.Config.ARGB_8888)
            try {
                Palette.from(bitmap)
                    .maximumColorCount(10)
                    .setRegion(x0, y0, x1, y1)
                    .generate()
                    .dominantSwatch?.rgb
            } finally {
                bitmap.recycle()
            }
        }.getOrNull() ?: average(pixels)

        val target = if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val amount = if (dark) TINT_DARK else TINT_LIGHT
        return ColorUtils.blendARGB(OPAQUE or dominant, target, amount) and 0xFFFFFF
    }

    private fun average(pixels: IntArray): Int {
        if (pixels.isEmpty()) return 0x808080
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
        }
        val n = pixels.size
        return ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    /** RGB (0xRRGGBB) of the sample pixels under a zone in every frame, transparent ones over [backdrop]. */
    private fun zone(
        s: Sample,
        offX: Float,
        offY: Float,
        visW: Float,
        visH: Float,
        z: FloatArray,
        backdrop: Color,
    ): IntArray {
        val x0 = (offX + z[0] * visW).toInt().coerceIn(0, s.width - 1)
        val x1 = (offX + z[2] * visW).toInt().coerceIn(x0 + 1, s.width)
        val y0 = (offY + z[1] * visH).toInt().coerceIn(0, s.height - 1)
        val y1 = (offY + z[3] * visH).toInt().coerceIn(y0 + 1, s.height)
        val stepX = max(1, (x1 - x0) / 48)
        val stepY = max(1, (y1 - y0) / 24)

        val bg = backdrop.toArgb()
        val br = (bg shr 16) and 0xFF
        val bgG = (bg shr 8) and 0xFF
        val bb = bg and 0xFF

        val out = ArrayList<Int>()
        for (frame in s.frames) {
            var y = y0
            while (y < y1) {
                var x = x0
                while (x < x1) {
                    val p = frame[y * s.width + x]
                    val a = (p ushr 24) and 0xFF
                    var r = (p shr 16) and 0xFF
                    var g = (p shr 8) and 0xFF
                    var b = p and 0xFF
                    if (a < 255) {
                        r = (r * a + br * (255 - a)) / 255
                        g = (g * a + bgG * (255 - a)) / 255
                        b = (b * a + bb * (255 - a)) / 255
                    }
                    out.add((r shl 16) or (g shl 8) or b)
                    x += stepX
                }
                y += stepY
            }
        }
        return out.toIntArray()
    }

    /** Does the text keep [TARGET_CONTRAST] over all but [FAIL_SHARE] of the pixels at this fade? */
    private fun passes(pixels: IntArray, fade: Int, textLum: Float, alpha: Float): Boolean {
        if (pixels.isEmpty()) return true
        val fr = (fade shr 16) and 0xFF
        val fg = (fade shr 8) and 0xFF
        val fb = fade and 0xFF
        var bad = 0
        for (p in pixels) {
            val r = mix((p shr 16) and 0xFF, fr, alpha)
            val g = mix((p shr 8) and 0xFF, fg, alpha)
            val b = mix(p and 0xFF, fb, alpha)
            if (contrast(luminance(r, g, b), textLum) < TARGET_CONTRAST) bad++
        }
        return bad <= pixels.size * FAIL_SHARE
    }

    /** Smallest fade opacity at which the text passes, or null when even [MAX_ALPHA] does not. */
    private fun neededAlpha(pixels: IntArray, fade: Int, textLum: Float): Float? {
        var a = 0f
        while (a <= MAX_ALPHA + 1e-4f) {
            if (passes(pixels, fade, textLum, a)) return a
            a += ALPHA_STEP
        }
        return null
    }

    /** A channel with the fade over it, blended the way the canvas does it (in sRGB). */
    private fun mix(channel: Int, fade: Int, alpha: Float): Int =
        (channel + (fade - channel) * alpha + 0.5f).toInt().coerceIn(0, 255)

    private val LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** WCAG relative luminance. */
    private fun luminance(r: Int, g: Int, b: Int): Float =
        0.2126f * LINEAR[r] + 0.7152f * LINEAR[g] + 0.0722f * LINEAR[b]

    private fun contrast(l1: Float, l2: Float): Float =
        (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
}

/**
 * The [BannerTone] for the status card's banner, in step with the theme in use (not the system's:
 * the app can force light or dark by itself), the banner's focus point and the card's colour.
 *
 * Until the banner has been looked at (a few ms, and only the first time) the theme's own look
 * is used; changes then animate instead of jumping.
 */
@Composable
fun rememberBannerTone(file: File?): BannerTone {
    val context = LocalContext.current.applicationContext
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f
    val backdrop = scheme.primaryContainer // what shows through a transparent picture
    val version = BannerStore.version
    val fx = BannerStore.focusX
    val fy = BannerStore.focusY
    val fallback = remember(dark) { BannerTone.fallback(dark) }

    val target by produceState(
        initialValue = BannerAnalyzer.cached(version, fx, fy, dark, backdrop) ?: fallback,
        file, version, fx, fy, dark, backdrop
    ) {
        value = if (file == null) {
            fallback
        } else {
            withContext(Dispatchers.IO) {
                BannerAnalyzer.analyze(context, file, version, fx, fy, dark, backdrop)
            } ?: fallback
        }
    }

    // A slower, eased tween: the fade re-tints as a change of banner or theme, not as a jump.
    val spec = tween<Color>(450, easing = FastOutSlowInEasing)
    val floatSpec = tween<Float>(450, easing = FastOutSlowInEasing)
    val content by animateColorAsState(target.content, spec, label = "bannerContent")
    val scrimTop by animateColorAsState(target.scrimTop, spec, label = "bannerScrimTop")
    val scrimBottom by animateColorAsState(target.scrimBottom, spec, label = "bannerScrimBottom")
    val top by animateFloatAsState(target.top, floatSpec, label = "bannerTop")
    val bottom by animateFloatAsState(target.bottom, floatSpec, label = "bannerBottom")
    return BannerTone(content, scrimTop, scrimBottom, top, bottom)
}
