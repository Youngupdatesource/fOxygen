package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.lerp
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

/** Text colour used on a light fade. Fixed: the theme's own onSurface is light in a dark theme. */
private val DARK_TEXT = Color(0xFF1C1B1F)

/**
 * What the status card draws over its banner so the text on it stays readable: the colour of the
 * fade behind the title/version line ([scrimTop]) and behind the uptime ([scrimBottom]) — both
 * taken from the banner itself, so the fade melts into the picture instead of laying a flat
 * white/black film over it — how opaque it is behind the title and version line ([top]), in the
 * middle where there is no text ([mid]) and behind the uptime ([bottom]), and the colour of the
 * text itself ([content]).
 */
@Immutable
data class BannerTone(
    val content: Color,
    val scrimTop: Color,
    val scrimBottom: Color,
    val top: Float,
    val mid: Float,
    val bottom: Float,
) {
    /** Vertical fade: the text blocks keep their full opacity over their whole height. */
    fun brush(): Brush {
        val middle = lerp(scrimTop, scrimBottom, 0.5f)
        return Brush.verticalGradient(
            colorStops = arrayOf(
                0f to scrimTop.copy(alpha = top),
                0.45f to scrimTop.copy(alpha = top),
                0.62f to middle.copy(alpha = mid),
                0.78f to scrimBottom.copy(alpha = bottom),
                1f to scrimBottom.copy(alpha = bottom),
            )
        )
    }

    companion object {
        /** Before the banner has been looked at (and if it can't be): the theme's own look. */
        fun fallback(dark: Boolean): BannerTone =
            if (dark) BannerTone(Color.White, Color.Black, Color.Black, 0.25f, 0.10f, 0.60f)
            else BannerTone(DARK_TEXT, Color.White, Color.White, 0.30f, 0.12f, 0.55f)
    }
}

/**
 * Looks at the part of the banner that is actually on screen, behind the text, and decides how
 * strong the fade must be for the text to be readable.
 *
 * - The picture is read through Coil's own loader, small, so EXIF rotation and the first frame of
 *   a GIF/WebP are what the card shows. One sample is kept for the current banner.
 * - Only the visible area counts: the card shows the banner cropped to [STATUS_BANNER_ASPECT],
 *   moved by the focus point the user set. Two zones are measured, where the title/version line
 *   and the uptime are drawn.
 * - Contrast is the WCAG ratio against the colour the pixel really ends up with once the fade is
 *   blended over it, so "enough" is measured, not guessed. Up to [FAIL_SHARE] of a zone may stay
 *   below the target: a few specks must not force a heavy fade over the whole banner.
 * - The fade takes the banner's own colour: in each zone the dominant swatch (androidx Palette) is
 *   pulled towards the theme's side, pastel in a light theme and deep in a dark one, so the fade
 *   melts into the picture instead of laying a flat white/black film over it. The two zones can
 *   have different tints; the gradient runs from one to the other. If Palette finds no swatch (an
 *   almost white or black picture) the zone's average colour is used.
 * - What also follows the picture is the text colour and how strong the fade is: the text that
 *   contrasts with the fade (dark on a light tint, white on a dark one) is used while that does
 *   not take more than [STAY_WITH_THEME] of fade; a picture that would need more gets the other
 *   text colour and only as much fade as keeps it readable.
 */
internal object BannerAnalyzer {
    private const val SAMPLE_SIDE = 256
    private const val MAX_ALPHA = 0.80f
    private const val ALPHA_STEP = 0.025f
    private const val TARGET_CONTRAST = 4.5f
    private const val FAIL_SHARE = 0.06f
    private const val STAY_WITH_THEME = 0.25f

    // How far the banner's colour is pulled towards white (light theme) / black (dark theme).
    private const val TINT_LIGHT = 0.80f
    private const val TINT_DARK = 0.62f

    // Relative luminance where white and near-black text contrast equally: above it a fade is
    // "light" and wants dark text.
    private const val CROSSOVER_LUM = 0.18f

    // Fade kept at the bottom, so the card always has one. With the text of the fade's own side
    // (white on white) it is only as strong as that text can take.
    private const val FLOOR_BOTTOM = 0.45f
    private const val FLOOR_BOTTOM_SAME_SIDE = 0.30f

    // Where the text is, as fractions of the card (x0, y0, x1, y1).
    private val TOP_ZONE = floatArrayOf(0.03f, 0.05f, 0.58f, 0.38f)
    private val BOTTOM_ZONE = floatArrayOf(0.03f, 0.80f, 0.45f, 0.96f)

    private class Sample(val width: Int, val height: Int, val pixels: IntArray)

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
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            Sample(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
        synchronized(this) {
            sampleVersion = version
            sample = taken
        }
        return taken
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

        // The fade's colour is the banner's own, per zone, leaning to the theme's side.
        val topFade = fadeRgb(s, offX, offY, visW, visH, TOP_ZONE, dark, top)
        val bottomFade = fadeRgb(s, offX, offY, visW, visH, BOTTOM_ZONE, dark, bottom)
        val lightFade = (luminanceOf(topFade) + luminanceOf(bottomFade)) / 2f > CROSSOVER_LUM

        // Text that contrasts with the fade: more fade only helps it. null = even MAX_ALPHA fails.
        val naturalLum = if (lightFade) darkTextLum else 1f
        val needTop = neededAlpha(top, topFade, naturalLum)
        val needBottom = neededAlpha(bottom, bottomFade, naturalLum)
        val natural = max(needTop ?: 1f, needBottom ?: 1f)

        // Text of the fade's own side (white on a light fade): more fade only hurts it. It works
        // when the picture already gives enough contrast, up to how much fade it can take.
        val sameLum = if (lightFade) 1f else darkTextLum
        val sameTop = allowedAlpha(top, topFade, sameLum)
        val sameBottom = allowedAlpha(bottom, bottomFade, sameLum)
        val sameWorks = sameTop != null && sameBottom != null

        val useNatural = natural <= STAY_WITH_THEME || !sameWorks

        val alphaTop: Float
        val alphaBottom: Float
        if (useNatural) {
            alphaTop = min(MAX_ALPHA, needTop ?: 1f)
            alphaBottom = min(MAX_ALPHA, max(needBottom ?: 1f, FLOOR_BOTTOM))
        } else {
            alphaTop = 0f
            alphaBottom = min(FLOOR_BOTTOM_SAME_SIDE, sameBottom ?: 0f)
        }
        val darkContent = lightFade == useNatural // dark text: natural on light, same-side on dark
        return BannerTone(
            content = if (darkContent) DARK_TEXT else Color.White,
            scrimTop = Color(OPAQUE or topFade),
            scrimBottom = Color(OPAQUE or bottomFade),
            top = alphaTop,
            mid = min(alphaTop, alphaBottom) * 0.6f,
            bottom = alphaBottom,
        )
    }

    private val OPAQUE = 0xFF000000.toInt()

    private fun luminanceOf(rgb: Int): Float =
        luminance((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)

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
            val bitmap = Bitmap.createBitmap(s.pixels, s.width, s.height, Bitmap.Config.ARGB_8888)
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

    /** RGB (0xRRGGBB) of the sample pixels under a zone, transparent ones over [backdrop]. */
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
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = s.pixels[y * s.width + x]
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

    /** Largest fade opacity at which the text still passes, or null when it fails without any. */
    private fun allowedAlpha(pixels: IntArray, fade: Int, textLum: Float): Float? {
        if (!passes(pixels, fade, textLum, 0f)) return null
        var a = 0f
        while (a + ALPHA_STEP <= MAX_ALPHA + 1e-4f &&
            passes(pixels, fade, textLum, a + ALPHA_STEP)
        ) {
            a += ALPHA_STEP
        }
        return a
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
    val mid by animateFloatAsState(target.mid, floatSpec, label = "bannerMid")
    val bottom by animateFloatAsState(target.bottom, floatSpec, label = "bannerBottom")
    return BannerTone(content, scrimTop, scrimBottom, top, mid, bottom)
}
