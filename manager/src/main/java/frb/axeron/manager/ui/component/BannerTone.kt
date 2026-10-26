package frb.axeron.manager.ui.component

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
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
import androidx.core.graphics.drawable.toBitmap
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
 * fade (white or black), how opaque it is behind the title and version line ([top]), in the
 * middle where there is no text ([mid]) and behind the uptime ([bottom]), and the colour of the
 * text itself ([content]).
 */
@Immutable
data class BannerTone(
    val content: Color,
    val scrim: Color,
    val top: Float,
    val mid: Float,
    val bottom: Float,
) {
    /** Vertical fade: the text blocks keep their full opacity over their whole height. */
    fun brush(): Brush = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to scrim.copy(alpha = top),
            0.45f to scrim.copy(alpha = top),
            0.62f to scrim.copy(alpha = mid),
            0.78f to scrim.copy(alpha = bottom),
            1f to scrim.copy(alpha = bottom),
        )
    )

    companion object {
        /** Before the banner has been looked at (and if it can't be): the theme's own look. */
        fun fallback(dark: Boolean): BannerTone =
            if (dark) BannerTone(Color.White, Color.Black, 0.25f, 0.10f, 0.60f)
            else BannerTone(DARK_TEXT, Color.White, 0f, 0f, 0f) // light: no fade
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
 * - The theme alone decides the colour of the fade: white in a light theme, black in a dark one,
 *   whatever the picture. What follows the picture is the text colour and how strong the fade is:
 *   the text that contrasts with the fade (dark on white, white on black) is used while that does
 *   not take more than [STAY_WITH_THEME] of fade; a picture that would need more (a dark banner
 *   under a light theme) gets the other text colour and only as much fade as keeps it readable.
 */
internal object BannerAnalyzer {
    private const val SAMPLE_SIDE = 256
    private const val MAX_ALPHA = 0.80f
    private const val ALPHA_STEP = 0.025f
    private const val TARGET_CONTRAST = 4.5f
    private const val FAIL_SHARE = 0.06f
    private const val STAY_WITH_THEME = 0.25f

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

        // Light theme: no fade at all, the banner is shown as it is. The only thing that follows
        // the picture is the text colour: whichever of the dark text and white fails the contrast
        // over fewer of the pixels behind the text (dark on a tie).
        if (!dark) {
            val failDark = failShare(top, darkTextLum) + failShare(bottom, darkTextLum)
            val failWhite = failShare(top, 1f) + failShare(bottom, 1f)
            return BannerTone(
                content = if (failWhite < failDark) Color.White else DARK_TEXT,
                scrim = Color.White,
                top = 0f,
                mid = 0f,
                bottom = 0f,
            )
        }

        // The fade's colour is the theme's, always.
        val whiteFade = !dark

        // Text that contrasts with the fade: more fade only helps it. null = even MAX_ALPHA fails.
        val naturalLum = if (whiteFade) darkTextLum else 1f
        val needTop = neededAlpha(top, whiteFade, naturalLum)
        val needBottom = neededAlpha(bottom, whiteFade, naturalLum)
        val natural = max(needTop ?: 1f, needBottom ?: 1f)

        // Text of the fade's own side (white on a white fade): more fade only hurts it. It works
        // when the picture already gives enough contrast, up to how much fade it can take.
        val sameLum = if (whiteFade) 1f else darkTextLum
        val sameTop = allowedAlpha(top, whiteFade, sameLum)
        val sameBottom = allowedAlpha(bottom, whiteFade, sameLum)
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
        val darkContent = whiteFade == useNatural // dark text: natural on white, same-side on black
        return BannerTone(
            content = if (darkContent) DARK_TEXT else Color.White,
            scrim = if (whiteFade) Color.White else Color.Black,
            top = alphaTop,
            mid = min(alphaTop, alphaBottom) * 0.6f,
            bottom = alphaBottom,
        )
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

    /** Share (0..1) of the pixels over which text of this luminance misses [TARGET_CONTRAST]. */
    private fun failShare(pixels: IntArray, textLum: Float): Float {
        if (pixels.isEmpty()) return 0f
        var bad = 0
        for (p in pixels) {
            val l = luminance((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            if (contrast(l, textLum) < TARGET_CONTRAST) bad++
        }
        return bad / pixels.size.toFloat()
    }

    /** Does the text keep [TARGET_CONTRAST] over all but [FAIL_SHARE] of the pixels at this fade? */
    private fun passes(pixels: IntArray, whiteFade: Boolean, textLum: Float, alpha: Float): Boolean {
        if (pixels.isEmpty()) return true
        val fade = if (whiteFade) 255 else 0
        var bad = 0
        for (p in pixels) {
            val r = mix((p shr 16) and 0xFF, fade, alpha)
            val g = mix((p shr 8) and 0xFF, fade, alpha)
            val b = mix(p and 0xFF, fade, alpha)
            if (contrast(luminance(r, g, b), textLum) < TARGET_CONTRAST) bad++
        }
        return bad <= pixels.size * FAIL_SHARE
    }

    /** Smallest fade opacity at which the text passes, or null when even [MAX_ALPHA] does not. */
    private fun neededAlpha(pixels: IntArray, whiteFade: Boolean, textLum: Float): Float? {
        var a = 0f
        while (a <= MAX_ALPHA + 1e-4f) {
            if (passes(pixels, whiteFade, textLum, a)) return a
            a += ALPHA_STEP
        }
        return null
    }

    /** Largest fade opacity at which the text still passes, or null when it fails without any. */
    private fun allowedAlpha(pixels: IntArray, whiteFade: Boolean, textLum: Float): Float? {
        if (!passes(pixels, whiteFade, textLum, 0f)) return null
        var a = 0f
        while (a + ALPHA_STEP <= MAX_ALPHA + 1e-4f &&
            passes(pixels, whiteFade, textLum, a + ALPHA_STEP)
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

    val content by animateColorAsState(target.content, tween(300), label = "bannerContent")
    val scrim by animateColorAsState(target.scrim, tween(300), label = "bannerScrim")
    val top by animateFloatAsState(target.top, tween(300), label = "bannerTop")
    val mid by animateFloatAsState(target.mid, tween(300), label = "bannerMid")
    val bottom by animateFloatAsState(target.bottom, tween(300), label = "bannerBottom")
    return BannerTone(content, scrim, top, mid, bottom)
}
