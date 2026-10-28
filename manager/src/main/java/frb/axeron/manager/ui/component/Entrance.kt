package frb.axeron.manager.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The cue for the app's first screen entrance, after the splash.
 *
 * Until [revealed] the things marked with [riseIn] wait out of sight, just below where they
 * belong; AxActivity flips it a moment after the system splash is gone, and they rise into
 * place one after another.
 */
object StartupMotion {
    /** True from the moment the first screen should start to come in. */
    var revealed by mutableStateOf(false)
        internal set

    /**
     * True once the window for the entrance is over (a little after [revealed]). A screen that
     * shows up later, or comes back to, is simply there: nothing is replayed.
     */
    @Volatile
    var played = false
        internal set
}

private const val BASE_DELAY_MS = 90L

/**
 * Rises into place on the start-up entrance: from [distance] below, fading in and settling from
 * a slightly smaller size, [index] steps after the first. A spring with a little give carries it
 * the last bit, so it lands rather than stops. Without an entrance in progress (already played)
 * it is a no-op in effect: it starts in place.
 *
 * It wraps exactly one thing, so putting it on a Box around a card does not change the layout.
 */
@Composable
fun Modifier.riseIn(
    index: Int,
    distance: Dp = 56.dp,
    stepMs: Long = 60L,
): Modifier {
    val progress = remember { Animatable(if (StartupMotion.played) 1f else 0f) }
    val revealed = StartupMotion.revealed

    LaunchedEffect(revealed) {
        if (revealed && progress.value < 1f) {
            delay(BASE_DELAY_MS + index * stepMs)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f)
            )
        }
    }

    return this.graphicsLayer {
        val p = progress.value
        alpha = (p * 2f).coerceIn(0f, 1f) // fully there by the time it is half way up
        translationY = (1f - p) * distance.toPx()
        val s = 0.97f + 0.03f * p.coerceIn(0f, 1f)
        scaleX = s
        scaleY = s
    }
}
