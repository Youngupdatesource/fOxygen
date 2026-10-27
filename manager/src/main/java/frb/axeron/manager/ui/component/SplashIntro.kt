package frb.axeron.manager.ui.component

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import frb.axeron.manager.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

// One master clock (0..1 over TOTAL_MS) drives the whole intro; every effect is a slice of it.
// Reading the clock only in draw / graphicsLayer lambdas means no recomposition per frame.
private const val TOTAL_MS = 1650
private const val EXIT_AT = 0.76f

private const val FRAME_SRC = 432f
private const val EMBER_COUNT = 14

private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private val Accent = Color(0xFFD3460E)
private val Cream = Color(0xFFE0D0B7)
private val Ink = Color(0xFF2E1D0C)

private val HeadPivot = Offset(-66f, 6f)
private val TailPivot = Offset(-10f, 84f)
private val BodyPivot = Offset(54f, 106f)
private val HeadTopLeft = Offset(-101f, -106f)
private val TailTopLeft = Offset(-120f, 9f)
private val BodyTopLeft = Offset(-13f, -87f)

private fun phase(t: Float, from: Float, to: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

private fun bump(t: Float, from: Float, to: Float): Float =
    sin(PI * ((t - from) / (to - from)).coerceIn(0f, 1f)).toFloat()

private class Ripple(val origin: Offset) {
    val progress = Animatable(0f)
}

private class Ember(
    val angle: Float,
    val dist: Float,
    val start: Float,
    val rise: Float,
    val drift: Float,
    val size: Float,
    val warm: Boolean,
)

private fun makeEmbers(): Array<Ember> {
    val random = Random(11)
    return Array(EMBER_COUNT) {
        Ember(
            angle = (random.nextFloat() * 2f * PI).toFloat(),
            dist = 0.25f + random.nextFloat() * 0.75f,
            start = 0.10f + random.nextFloat() * 0.40f,
            rise = 40f + random.nextFloat() * 70f,
            drift = 4f + random.nextFloat() * 10f,
            size = 1.2f + random.nextFloat() * 1.8f,
            warm = random.nextFloat() < 0.6f,
        )
    }
}

@Stable
private class IconMotion {
    var targetX = 0f
    var targetY = 0f
    var targetPress = 0f
    var lookX by mutableFloatStateOf(0f)
    var lookY by mutableFloatStateOf(0f)
    var press by mutableFloatStateOf(0f)
    var wag by mutableFloatStateOf(0f)
    val perk = Animatable(0f)
    private var wagClock = 0f

    fun aim(position: Offset, half: Offset) {
        targetX = ((position.x - half.x) / half.x).coerceIn(-1f, 1f)
        targetY = ((position.y - half.y) / half.y).coerceIn(-1f, 1f)
    }

    fun release() {
        targetX = 0f
        targetY = 0f
        targetPress = 0f
    }

    fun step(dt: Float) {
        val follow = 1f - exp(-dt * 9f)
        lookX += (targetX - lookX) * follow
        lookY += (targetY - lookY) * follow
        press += (targetPress - press) * (1f - exp(-dt * 16f))
        if (targetPress > 0.5f) {
            wagClock += dt
            wag += (1f - wag) * (1f - exp(-dt * 10f))
        } else {
            wag -= wag * (1f - exp(-dt * 6f))
        }
    }

    fun wagAngle(): Float = wag * 3.5f * sin(wagClock * 16f)
}

private class DrawCache {
    val disc = Path()
    val iris = Path()
    val layerPaint = Paint()
    var glowRadius = -1f
    var glowBrush: Brush = SolidColor(Color.Transparent)
    var sweepWidth = -1f
    var sweepBrush: Brush = SolidColor(Color.Transparent)
}

/**
 * Wraps the app and plays a short intro over it on a cold start.
 *
 * The first frame is the system splash (the launcher icon in a circle on the splash colour), so
 * the hand-over is invisible; from there:
 *
 * 1. rings pulse out of the icon and a soft glow breathes behind it;
 * 2. the fox comes apart into head, body and tail that move on their own (the head lifts and
 *    looks around, the body breathes, the tail swishes) over a background that drifts the other
 *    way, with embers rising off it, while the whole icon lifts;
 * 3. the app name rises in letter by letter, then a highlight sweeps across it;
 * 4. an iris opens from the icon and the app scales up into place underneath.
 *
 * It answers touch: a tap sends a ring out of the finger with a light tick, the icon dips and the
 * fox perks its head; holding makes the tail wag, and dragging makes the head look after the
 * finger while the layers shift in parallax.
 *
 * @param enabled false = no intro at all, [content] is shown as is.
 * @param started the clock only runs once this is true (the system splash is gone by then).
 */
@Composable
fun SplashIntroHost(
    enabled: Boolean,
    started: Boolean,
    onFinished: () -> Unit,
    content: @Composable () -> Unit,
) {
    var active by remember { mutableStateOf(enabled) }
    val clock = remember { Animatable(0f) }

    LaunchedEffect(started) {
        if (active && started) {
            clock.animateTo(1f, tween(TOTAL_MS, easing = LinearEasing))
            active = false
            onFinished()
        }
    }

    // The cue for the first screen (StartupMotion): it starts to rise in when the iris starts to
    // open, so what the iris uncovers is already coming up. With no intro, a moment after the
    // system splash has faded. The timeout is a safety net: it must never stay hidden.
    LaunchedEffect(Unit) {
        if (enabled) {
            withTimeoutOrNull(5000L) { snapshotFlow { clock.value }.first { it >= EXIT_AT } }
        } else {
            delay(150L)
        }
        StartupMotion.revealed = true
        delay(2500L)
        StartupMotion.played = true
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (active) {
                        // The app settles into place while the iris opens.
                        Modifier.graphicsLayer {
                            val s = 0.96f + 0.04f * phase(clock.value, EXIT_AT, 1f, Emphasized)
                            scaleX = s
                            scaleY = s
                        }
                    } else Modifier
                )
        ) { content() }

        if (active) SplashIntro(progress = { clock.value })
    }
}

@Composable
private fun SplashIntro(progress: () -> Float) {
    val backdrop = colorResource(R.color.splash_background)
    val iconBack = ImageBitmap.imageResource(R.mipmap.ic_launcher_background)
    val foxHead = ImageBitmap.imageResource(R.drawable.splash_fox_head)
    val foxTail = ImageBitmap.imageResource(R.drawable.splash_fox_tail)
    val foxBody = ImageBitmap.imageResource(R.drawable.splash_fox_body)
    val name = stringResource(R.string.app_name)
    val ink = if (backdrop.luminance() > 0.5f) Ink else Cream

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val ripples = remember { mutableStateListOf<Ripple>() }
    val motion = remember { IconMotion() }
    val cache = remember { DrawCache() }
    val embers = remember { makeEmbers() }
    val clipIris = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            motion.step(((now - last) / 1_000_000_000f).coerceAtMost(0.05f))
            last = now
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (clipIris) Modifier
                else Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            )
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val half = Offset(size.width / 2f, size.height / 2f)

                    val ripple = Ripple(down.position)
                    ripples.add(ripple)
                    scope.launch {
                        ripple.progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                        ripples.remove(ripple)
                    }
                    scope.launch {
                        motion.perk.animateTo(1f, tween(80))
                        motion.perk.animateTo(
                            0f,
                            spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        )
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    motion.targetPress = 1f
                    motion.aim(down.position, half)
                    var held = true
                    while (held) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            held = false
                        } else {
                            motion.aim(change.position, half)
                        }
                    }
                    motion.release()
                }
            }
            .drawWithContent {
                val t = progress()
                val w = size.width
                val h = size.height
                val dp1 = 1.dp.toPx()

                val discR = 96.dp.toPx()
                val frame = 288.dp.toPx()
                val k = frame / FRAME_SRC
                val kBack = frame / iconBack.width
                val lift = 28.dp.toPx() * phase(t, 0.10f, 0.40f, Emphasized)
                val center = Offset(w / 2f, h / 2f - lift)
                val fadeOut = 1f - phase(t, EXIT_AT, EXIT_AT + 0.10f)

                val hole = phase(t, EXIT_AT, 1f, Standard)
                val irisR = hypot(w, h) * 0.6f * hole

                withTransform({
                    if (clipIris && hole > 0f) {
                        cache.iris.rewind()
                        cache.iris.addOval(Rect(center, irisR))
                        clipPath(cache.iris, ClipOp.Difference)
                    }
                }) {
                    drawRect(backdrop)

                    val glow = sin(PI * phase(t, 0f, 0.5f)).toFloat()
                    if (glow > 0f) {
                        val r = discR * 2.4f
                        if (cache.glowRadius != r) {
                            cache.glowRadius = r
                            cache.glowBrush = Brush.radialGradient(
                                colors = listOf(Accent.copy(alpha = 0.30f), Color.Transparent),
                                center = Offset.Zero,
                                radius = r
                            )
                        }
                        translate(center.x, center.y) {
                            drawCircle(
                                brush = cache.glowBrush,
                                radius = r,
                                center = Offset.Zero,
                                alpha = glow * fadeOut
                            )
                        }
                    }

                    for (i in 0 until 3) {
                        val start = i * 0.08f
                        val p = phase(t, start, start + 0.42f, FastOutSlowInEasing)
                        if (p > 0f && p < 1f) {
                            drawCircle(
                                color = Accent,
                                radius = discR * (1f + 1.15f * p),
                                center = center,
                                alpha = (1f - p) * (1f - p) * 0.6f * fadeOut,
                                style = Stroke(width = (3.dp.toPx() * (1f - p)).coerceAtLeast(1f))
                            )
                        }
                    }

                    val fading = fadeOut < 1f
                    if (fading) {
                        cache.layerPaint.alpha = fadeOut
                        drawIntoCanvas {
                            it.saveLayer(Rect(center, discR * 1.6f), cache.layerPaint)
                        }
                    }

                    val breathe = 1f + 0.05f * sin(PI * phase(t, 0f, 0.28f)).toFloat()
                    val dip = 1f - 0.06f * motion.press
                    val perk = motion.perk.value
                    val lookX = motion.lookX
                    val lookY = motion.lookY

                    val wake = phase(t, 0.06f, 0.20f, Emphasized)
                    val relax = phase(t, 0.50f, 0.70f, Standard)
                    val raised = wake - relax
                    val sway = sin(2f * PI.toFloat() * 2.5f * phase(t, 0.20f, 0.50f)) *
                        bump(t, 0.20f, 0.50f)
                    val breath = sin(2f * PI.toFloat() * 2f * phase(t, 0.06f, 0.62f)) *
                        bump(t, 0.06f, 0.62f)
                    val swish = sin(2f * PI.toFloat() * 3.5f * phase(t, 0.14f, 0.66f)) *
                        bump(t, 0.14f, 0.66f)

                    val headDeg = -7f * raised + 2.2f * sway - 4f * perk + 4f * lookX
                    val headDx = 5f * lookX
                    val headDy = -3f * raised - 2.5f * breath - 3f * perk + 3f * lookY
                    val tailDeg = 3.2f * swish + motion.wagAngle()
                    val bodyScaleY = 1f + 0.022f * breath - 0.03f * motion.press
                    val bodyScaleX = 1f + 0.015f * motion.press

                    val backEase = phase(t, 0f, 0.8f, FastOutSlowInEasing)
                    val backScale = 1f + 0.12f * backEase
                    val backDeg = 5f * backEase
                    val foxScale = 1f + 0.06f * phase(t, 0.10f, 0.40f, Emphasized)

                    scale(breathe * dip, pivot = center) {
                        cache.disc.rewind()
                        cache.disc.addOval(Rect(center, discR))
                        clipPath(cache.disc) {
                            withTransform({
                                translate(center.x - 5f * lookX * k, center.y - 5f * lookY * k)
                                rotate(backDeg, Offset.Zero)
                                scale(backScale * kBack, backScale * kBack, Offset.Zero)
                            }) {
                                drawImage(
                                    iconBack,
                                    Offset(-iconBack.width / 2f, -iconBack.height / 2f)
                                )
                            }
                        }

                        withTransform({
                            translate(center.x, center.y)
                            scale(k * foxScale, k * foxScale, Offset.Zero)
                        }) {
                            withTransform({
                                translate(1.5f * lookX, lookY)
                                scale(bodyScaleX, bodyScaleY, BodyPivot)
                            }) {
                                drawImage(foxBody, BodyTopLeft)
                            }
                            withTransform({
                                rotate(tailDeg, TailPivot)
                            }) {
                                drawImage(foxTail, TailTopLeft)
                            }
                            withTransform({
                                translate(headDx, headDy)
                                rotate(headDeg, HeadPivot)
                            }) {
                                drawImage(foxHead, HeadTopLeft)
                            }
                        }
                    }

                    if (fading) drawIntoCanvas { it.restore() }

                    for (i in embers.indices) {
                        val e = embers[i]
                        val p = phase(t, e.start, e.start + 0.34f)
                        if (p > 0f && p < 1f) {
                            val ex = center.x + cos(e.angle) * e.dist * discR +
                                sin(p * 6f + e.angle) * e.drift * dp1
                            val ey = center.y + sin(e.angle) * e.dist * discR * 0.8f -
                                e.rise * dp1 * p
                            drawCircle(
                                color = if (e.warm) Accent else Cream,
                                radius = e.size * dp1 * (1f - 0.4f * p),
                                center = Offset(ex, ey),
                                alpha = sin(PI * p).toFloat() * 0.85f * fadeOut
                            )
                        }
                    }

                    for (r in ripples) {
                        val p = r.progress.value
                        drawCircle(
                            color = Accent,
                            radius = 10.dp.toPx() + 110.dp.toPx() * p,
                            center = r.origin,
                            alpha = (1f - p) * (1f - p) * 0.55f * fadeOut,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }

                    this@drawWithContent.drawContent()
                }

                if (!clipIris && hole > 0f) {
                    drawCircle(
                        color = Color.Black,
                        radius = irisR,
                        center = center,
                        blendMode = BlendMode.Clear
                    )
                }
            }
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .offset(y = 126.dp)
                .graphicsLayer {
                    translationY = -28.dp.toPx() * phase(progress(), 0.10f, 0.40f, Emphasized)
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    val s = phase(progress(), 0.50f, 0.74f, Standard)
                    if (s > 0f && s < 1f) {
                        val band = size.width * 0.35f
                        if (cache.sweepWidth != band) {
                            cache.sweepWidth = band
                            cache.sweepBrush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.85f),
                                    Color.Transparent
                                ),
                                startX = -band,
                                endX = band
                            )
                        }
                        val x = -band + (size.width + 2f * band) * s
                        translate(left = x) {
                            drawRect(
                                brush = cache.sweepBrush,
                                topLeft = Offset(-band, 0f),
                                size = Size(2f * band, size.height),
                                blendMode = BlendMode.SrcAtop
                            )
                        }
                    }
                }
        ) {
            name.forEachIndexed { index, ch ->
                Text(
                    text = ch.toString(),
                    color = if (ch.isUpperCase()) Accent else ink,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.graphicsLayer {
                        val t = progress()
                        val start = 0.24f + index * 0.035f
                        val p = phase(t, start, start + 0.22f, Emphasized)
                        alpha = p * (1f - phase(t, EXIT_AT, EXIT_AT + 0.10f))
                        translationY = (1f - p) * 18.dp.toPx()
                        val letterScale = 0.8f + 0.2f * p
                        scaleX = letterScale
                        scaleY = letterScale
                    }
                )
            }
        }
    }
}
