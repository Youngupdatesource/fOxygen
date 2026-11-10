package frb.axeron.manager.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/**
 * One shared phase (0f..1f, looping) for every skeleton on screen, so they all shine in step and
 * only one animation runs however many rows there are. It is only read while drawing: the sweep
 * redraws the rows and never recomposes them.
 */
@Composable
fun rememberShimmerPhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "skeleton")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonPhase"
    )
}

/** A placeholder block: a soft tint with a light band sweeping across it. */
@Composable
fun Modifier.skeleton(
    phase: State<Float>,
    shape: Shape = RoundedCornerShape(8.dp),
): Modifier {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val shine = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    return this
        .clip(shape)
        .drawWithCache {
            val w = size.width
            onDrawBehind {
                drawRect(base)
                // The band travels from before the left edge to past the right edge.
                val x = (phase.value * 1.8f - 0.4f) * w
                drawRect(
                    Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, shine, Color.Transparent),
                        startX = x - w * 0.35f,
                        endX = x + w * 0.35f
                    )
                )
            }
        }
}

/**
 * Stands in for one row of the privilege list while it is still loading: the app icon, the name
 * and package lines and the switch, laid out like the real row so nothing shifts when the list
 * arrives. [index] varies the line lengths a little so the rows do not look stamped.
 */
@Composable
fun PrivilegeSkeletonRow(
    phase: State<Float>,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val nameWidth = listOf(0.46f, 0.58f, 0.40f, 0.52f)[index % 4]
    val packageWidth = listOf(0.72f, 0.64f, 0.78f, 0.68f)[index % 4]
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { }
            .padding(end = 6.dp, top = 6.dp)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .size(48.dp)
                .skeleton(phase, RoundedCornerShape(12.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(nameWidth)
                    .height(16.dp)
                    .skeleton(phase)
            )
            Box(
                Modifier
                    .fillMaxWidth(packageWidth)
                    .height(12.dp)
                    .skeleton(phase)
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .skeleton(phase, RoundedCornerShape(16.dp))
        )
    }
}
