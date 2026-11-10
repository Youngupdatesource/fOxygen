package frb.axeron.manager.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The cue for the app's entrance, every time it is opened (not only on Home).
 *
 * Until [revealed] the things marked with [riseIn] wait out of sight, just below where they
 * belong; AxActivity flips it a moment after the system splash is gone, and they rise into
 * place one after another. This holds for whichever tab the app opens on: Home, Privileges,
 * Plugins or Settings.
 *
 * [begin] starts the entrance over (a new [session]): it is called when the app is opened from
 * scratch and when it comes back from the background after a while, so an app that was still
 * alive in the background plays the entrance again instead of just appearing.
 */
object StartupMotion {
    /** Bumps every time the entrance starts over; items hide and rise in again. */
    var session by mutableIntStateOf(0)
        internal set

    /** True from the moment the current entrance should start to come in. */
    var revealed by mutableStateOf(false)
        internal set

    /**
     * True once the window for the entrance is over (a little after [revealed]). A screen that
     * shows up later, or comes back to, is simply there: nothing is replayed.
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

    @Volatile
    private var tabSwitchUntil = 0L

    @Volatile
    private var lastTabSwitchAt = 0L

    @Volatile
    private var tabSwitchInBurst = false

    /**
     * A tab was just opened by a tap or a link (not by swiping). Called by the navigation
     * transition, just before the new tab composes: cards that appear within the next moment rise
     * in again, with the same entrance as at start-up.
     */
    internal fun markTabSwitch() {
        val now = SystemClock.uptimeMillis()
        // Tabs being switched one right after another (spamming the capsule): the entrances
        // overlap, so each one is compressed (see riseIn) instead of restarting from empty.
        tabSwitchInBurst = now - lastTabSwitchAt < TAB_BURST_MS
        lastTabSwitchAt = now
        tabSwitchUntil = now + TAB_SWITCH_WINDOW_MS
    }

    internal fun tabSwitchIsBurst(): Boolean = tabSwitchInBurst

    /** A swipe handoff or anything else that must not replay: cards just appear in place. */
    internal fun clearTabSwitch() {
        tabSwitchUntil = 0L
    }

    internal fun tabSwitchActive(): Boolean = SystemClock.uptimeMillis() < tabSwitchUntil

    /** Starts the entrance over: everything marked with [riseIn] hides and rises in again. */
    internal fun begin() {
        played = false
        settled = false
        revealed = false
        session++
    }

    /** No entrance (for example the window was only recreated by a rotation): all is in place. */
    internal fun skip() {
        played = true
        settled = true
        revealed = true
    }
}

private const val BASE_DELAY_MS = 90L

// A tab opened by tap/link counts as "just opened" for this long (generous: the first
// composition of a heavy tab can take a while, and it must still count).
private const val TAB_SWITCH_WINDOW_MS = 900L

// A tab switch this soon after the previous one is part of a burst (rapid switching).
private const val TAB_BURST_MS = 700L

// In a burst each card starts already part of the way in, so a tab that is switched away from
// again almost at once never shows up empty, and the cards settle together, not one by one.
private const val BURST_START_PROGRESS = 0.5f
private const val BURST_STEP_MS = 12L

/**
 * Set only inside the neighbouring-tab preview that follows the finger while swiping between
 * tabs: how far that tab has come in, 0f..1f (1f once it has landed). The cards then play the
 * same entrance, but driven by the finger instead of a timer, so nothing replays when the
 * preview is handed over to the real tab.
 */
val LocalTabDragEntrance = compositionLocalOf<(() -> Float)?> { null }

/** One look for the entrance everywhere: the same at start-up, on a tab switch and in the swipe. */
private fun GraphicsLayerScope.applyRise(p: Float, distance: Dp) {
    alpha = (p * 2f).coerceIn(0f, 1f) // fully there by the time it is half way up
    translationY = (1f - p) * distance.toPx()
    val s = 0.97f + 0.03f * p.coerceIn(0f, 1f)
    scaleX = s
    scaleY = s
}

/**
 * Rises into place: from [distance] below, fading in and settling from a slightly smaller size,
 * [index] steps after the first. A spring with a little give carries it the last bit, so it lands
 * rather than stops. It is the app's entrance, and it plays everywhere the same way:
 *
 * - when the app is opened (on whichever tab it opens on),
 * - when a tab is opened by tap or link,
 * - while swiping between tabs: the preview plays the same entrance, driven by how far the
 *   finger has brought the tab in (no timer), and the real tab, once handed over, is simply in
 *   place, so nothing replays.
 *
 * A screen that just shows up otherwise (back from a sub-screen, say) starts in place.
 * It wraps exactly one thing, so putting it on a Box around a card does not change the layout.
 */
@Composable
fun Modifier.riseIn(
    index: Int,
    distance: Dp = 56.dp,
    stepMs: Long = 60L,
): Modifier {
    val dragEntrance = LocalTabDragEntrance.current
    return if (dragEntrance != null) {
        riseInFollowingFinger(dragEntrance, index, distance)
    } else {
        riseInOnTimer(index, distance, stepMs)
    }
}

@Composable
private fun Modifier.riseInFollowingFinger(
    entrance: () -> Float,
    index: Int,
    distance: Dp,
): Modifier = this.graphicsLayer {
    // Lower cards catch up a little later; at 1f everything is exactly in place.
    applyRise((entrance() * 1.25f - index * 0.05f).coerceIn(0f, 1f), distance)
}

@Composable
private fun Modifier.riseInOnTimer(
    index: Int,
    distance: Dp,
    stepMs: Long,
): Modifier {
    val session = StartupMotion.session
    val revealed = StartupMotion.revealed
    // Opened by tap/link after the start-up entrance: decided once, when this card first
    // composes (the navigation transition marks it just before that).
    val tabSwitch = remember { StartupMotion.played && StartupMotion.tabSwitchActive() }
    val burst = remember { tabSwitch && StartupMotion.tabSwitchIsBurst() }
    val progress = remember {
        Animatable(
            when {
                burst -> BURST_START_PROGRESS
                StartupMotion.played && !tabSwitch -> 1f
                else -> 0f
            }
        )
    }
    // The session this item's progress belongs to. When a newer session starts (the app was
    // opened again) the item is hidden at once, in the very same frame, and the effect below
    // winds its progress back to 0 and lets it rise in again.
    var ownSession by remember { mutableIntStateOf(session) }

    LaunchedEffect(session, revealed) {
        if (ownSession != session) {
            progress.snapTo(0f)
            ownSession = session
        }
        if (revealed && progress.value < 1f) {
            if (burst && StartupMotion.played) {
                // Parallel entrances: every card is its own animation on its own Animatable, so
                // switching tabs again only abandons the cards that went away and the new tab's
                // cards run alongside them: compressed, with a short stagger and a firmer spring.
                delay(index * BURST_STEP_MS)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f)
                )
            } else {
                delay(BASE_DELAY_MS + index * stepMs)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f)
                )
            }
        }
    }

    return this.graphicsLayer {
        applyRise(if (StartupMotion.session != ownSession) 0f else progress.value, distance)
    }
}
