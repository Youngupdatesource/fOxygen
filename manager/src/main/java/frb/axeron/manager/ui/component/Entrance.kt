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
 * The cue for the app's entrance, played strictly once when the app process is opened.
 *
 * Until [revealed] the things marked with [riseIn] on the initial screen wait out of sight;
 * AxActivity flips [revealed] right after the splash screen is removed and immediately marks
 * [played] once the initial frame's items have claimed their animation, so switching tabs via
 * the bottom pill or navigating later never replays the entrance animation.
 */
object StartupMotion {
    /** Bumps when the initial entrance session starts. */
    var session by mutableIntStateOf(0)
        internal set

    /** True from the moment the current entrance should start rising in. */
    var revealed by mutableStateOf(false)
        internal set

    /**
     * True as soon as the initial screen has started its entrance (or when the user interacts
     * with navigation). Any screen or tab composed after this starts in place (1f) with no replay.
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

    /** Starts the entrance once for a fresh app launch. */
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

    /** No entrance (e.g. activity recreation / rotation / already played in this process). */
    internal fun skip() {
        played = true
        settled = true
        revealed = true
    }
}

private const val BASE_DELAY_MS = 70L

/**
 * Rises into place on the initial app launch only. Once settled (or on any tab opened after the
 * initial launch frame), returns [this] directly without attaching a redundant graphicsLayer.
 */
@Composable
fun Modifier.riseIn(
    index: Int,
    distance: Dp = 56.dp,
    stepMs: Long = 50L,
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
                animationSpec = spring(dampingRatio = 0.82f, stiffness = 240f)
            )
        }
    }

    if (StartupMotion.played && StartupMotion.session == ownSession && progress.value >= 0.999f) {
        return this
    }

    return this.graphicsLayer {
        val p = if (StartupMotion.session != ownSession && !StartupMotion.played) 0f else progress.value
        alpha = (p * 2f).coerceIn(0f, 1f)
        translationY = (1f - p) * distance.toPx()
        val s = 0.97f + 0.03f * p.coerceIn(0f, 1f)
        scaleX = s
        scaleY = s
    }
}
