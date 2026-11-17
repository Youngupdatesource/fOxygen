package frb.axeron.manager.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The cue for the app's entrance, played once when the app process is opened.
 */
object StartupMotion {
    /** Bumps every time the entrance starts over; items hide and rise in again. */
    var session by mutableIntStateOf(0)
        internal set

    /** True from the moment the current entrance should start to come in. */
    var revealed by mutableStateOf(false)
        internal set

    /**
     * True once the window for the entrance is over. A screen that shows up later, or comes back
     * to, is simply there: nothing is replayed.
     */
    @Volatile
    var played = false
        internal set

    /**
     * True once the entrance has settled. Heavy extras (animated banners) wait for this, so the
     * first frames after opening the app are not shared with GIF decoding.
     */
    var settled by mutableStateOf(false)
        internal set

    /** Starts the entrance over: everything marked with [riseIn] hides and rises in again. */
    internal fun begin() {
        played = false
        settled = false
        revealed = false
        session++
    }

    /** Marks the entrance window closed so subsequent tab taps never trigger [riseIn]. */
    internal fun finishNow() {
        played = true
        settled = true
        revealed = true
    }

    /** No entrance (for example the window was only recreated by a rotation): all is in place. */
    internal fun skip() {
        played = true
        settled = true
        revealed = true
    }
}

private const val BASE_DELAY_MS = 90L

@Composable
fun Modifier.riseIn(
    index: Int,
    distance: Dp = 56.dp,
    stepMs: Long = 60L,
): Modifier {
    val session = StartupMotion.session
    val revealed = StartupMotion.revealed
    val progress = remember { Animatable(if (StartupMotion.played) 1f else 0f) }
    var ownSession by remember { mutableIntStateOf(session) }

    LaunchedEffect(session, revealed) {
        if (ownSession != session) {
            ownSession = session
            if (!StartupMotion.played) {
                progress.snapTo(0f)
            }
        }
        if (revealed && progress.value < 1f) {
            delay(BASE_DELAY_MS + index * stepMs)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f)
            )
        }
    }

    return this.graphicsLayer {
        val p = if (StartupMotion.session != ownSession && !StartupMotion.played) 0f else progress.value
        alpha = (p * 2f).coerceIn(0f, 1f) // fully there by the time it is half way up
        translationY = (1f - p) * distance.toPx()
        val s = 0.97f + 0.03f * p.coerceIn(0f, 1f)
        scaleX = s
        scaleY = s
    }
}
