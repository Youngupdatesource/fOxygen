package frb.axeron.manager.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import frb.axeron.manager.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

// One master clock (0..1 over TOTAL_MS) drives the whole intro; every effect is a slice of it.
// Reading the clock only in draw / graphicsLayer lambdas means no recomposition per frame.
private const val TOTAL_MS = 1650
private const val EXIT_AT = 0.76f

private val Emphasized = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Standard = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private val Accent = Color(0xFFD3460E)
private val Cream = Color(0xFFE0D0B7)
private val Ink = Color(0xFF2E1D0C)

private fun phase(t: Float, from: Float, to: Float, easing: Easing = LinearEasing): Float =
    easing.transform(((t - from) / (to - from)).coerceIn(0f, 1f))

private class Ripple(val origin: Offset) {
    val progress = Animatable(0f)
}

/**
 * Wraps the app and plays a short intro over it on a cold start.
 *
 * The first frame is the system splash (the launcher icon in a circle on the splash colour), so
 * the hand-over is invisible; from there:
 *
 * 1. rings pulse out of the icon and a soft glow breathes behind it;
 * 2. the icon's two layers (background and fox) drift at different speeds, and the icon lifts;
 * 3. the app name rises in letter by letter, then a highlight sweeps across it;
 * 4. an iris opens from the icon and the app scales up into place underneath.
 *
 * It answers touch: a tap sends a ring out of the finger with a light tick and the icon dips; a
 * drag makes the icon's layers follow the finger (parallax).
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
    // The two layers of the launcher icon (the adaptive icon itself can't be drawn by Compose).
    val iconBack = ImageBitmap.imageResource(R.mipmap.ic_launcher_background)
    val iconFront = ImageBitmap.imageResource(R.mipmap.ic_launcher_foreground)
    val name = stringResource(R.string.app_name)
    val ink = if (backdrop.luminance() > 0.5f) Ink else Cream

    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val ripples = remember { mutableStateListOf<Ripple>() }
    var pressed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf(Offset.Zero) } // finger, from the screen centre, px

    val dip = animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "introDip"
    )
    val shiftX = animateFloatAsState(touch.x, spring(stiffness = Spring.StiffnessLow), label = "introShiftX")
    val shiftY = animateFloatAsState(touch.y, spring(stiffness = Spring.StiffnessLow), label = "introShiftY")

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val screenCenter = Offset(size.width / 2f, size.height / 2f)

                    val ripple = Ripple(down.position)
                    ripples.add(ripple)
                    scope.launch {
                        ripple.progress.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
                        ripples.remove(ripple)
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)

                    pressed = true
                    touch = down.position - screenCenter
                    var held = true
                    while (held) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            held = false
                        } else {
                            touch = change.position - screenCenter
                        }
                    }
                    pressed = false
                    touch = Offset.Zero
                }
            }
            .drawWithContent {
                val t = progress()
                val w = size.width
                val h = size.height

                val discR = 96.dp.toPx() // the system splash draws the icon in a 192dp circle
                val frame = 288.dp.toPx() // ... cut from a 288dp icon frame
                val lift = 28.dp.toPx() * phase(t, 0.10f, 0.40f, Emphasized)
                val center = Offset(w / 2f, h / 2f - lift)
                val fadeOut = 1f - phase(t, EXIT_AT, EXIT_AT + 0.10f)

                drawRect(backdrop)

                // Glow behind the icon: swells and fades once.
                val glow = sin(PI * phase(t, 0f, 0.5f)).toFloat()
                if (glow > 0f) {
                    val r = discR * 2.4f
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Accent.copy(alpha = 0.30f * glow * fadeOut),
                                Color.Transparent
                            ),
                            center = center,
                            radius = r
                        ),
                        radius = r,
                        center = center
                    )
                }

                // Rings out of the icon, one after another.
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

                // The icon: breathes once, dips under a finger, layers drift apart.
                val breathe = 1f + 0.06f * sin(PI * phase(t, 0f, 0.28f)).toFloat()
                val maxShift = 10.dp.toPx()
                val frontDx = (shiftX.value * 0.06f).coerceIn(-maxShift, maxShift)
                val frontDy = (shiftY.value * 0.06f).coerceIn(-maxShift, maxShift)
                val backScale = 1f + 0.10f * phase(t, 0f, 0.8f, FastOutSlowInEasing)
                val frontScale = 1f + 0.03f * phase(t, 0f, 0.8f, FastOutSlowInEasing)
                val frameSize = IntSize(frame.roundToInt(), frame.roundToInt())

                scale(breathe * dip.value, pivot = center) {
                    val disc = Path().apply { addOval(Rect(center, discR)) }
                    clipPath(disc) {
                        scale(backScale, pivot = center) {
                            drawImage(
                                image = iconBack,
                                dstOffset = IntOffset(
                                    (center.x - frame / 2f - frontDx * 0.5f).roundToInt(),
                                    (center.y - frame / 2f - frontDy * 0.5f).roundToInt()
                                ),
                                dstSize = frameSize,
                                alpha = fadeOut
                            )
                        }
                        scale(frontScale, pivot = center) {
                            drawImage(
                                image = iconFront,
                                dstOffset = IntOffset(
                                    (center.x - frame / 2f + frontDx).roundToInt(),
                                    (center.y - frame / 2f + frontDy).roundToInt()
                                ),
                                dstSize = frameSize,
                                alpha = fadeOut
                            )
                        }
                    }
                }

                // Rings from the finger.
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

                drawContent() // the app name

                // Iris: a hole opens from the icon and takes everything above the app with it.
                val hole = phase(t, EXIT_AT, 1f, Standard)
                if (hole > 0f) {
                    drawCircle(
                        color = Color.Black,
                        radius = hypot(w, h) * 0.6f * hole,
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
                    // Highlight sweeping across the letters.
                    val s = phase(progress(), 0.50f, 0.74f, Standard)
                    if (s > 0f && s < 1f) {
                        val band = size.width * 0.35f
                        val x = -band + (size.width + 2f * band) * s
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.White.copy(alpha = 0.85f),
                                    Color.Transparent
                                ),
                                startX = x - band,
                                endX = x + band
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
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
