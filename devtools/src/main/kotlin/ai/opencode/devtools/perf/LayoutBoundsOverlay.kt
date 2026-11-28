package ai.opencode.devtools.perf

import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private data class BoundsBox(val rect: Rect, val clickable: Boolean, val tooSmall: Boolean)

/**
 * In-app "Show layout bounds": outlines every semantics node and fills touch targets under 48 dp
 * red. Draw-only: touches pass through.
 */
@Composable
fun LayoutBoundsOverlay(modifier: Modifier = Modifier) {
    val view = LocalView.current
    val density = LocalDensity.current
    val minTarget = with(density) { 48.dp.toPx() } - 0.5f
    var boxes by remember { mutableStateOf(emptyList<BoundsBox>()) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(view) {
        val root = view as? RootForTest ?: return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                // A few times a second is plenty for a debug overlay.
                if (now - last > 200_000_000L) {
                    last = now
                    val next = mutableListOf<BoundsBox>()
                    collect(root.semanticsOwner.unmergedRootSemanticsNode, minTarget, next)
                    boxes = next
                }
            }
        }
    }
    val small = boxes.count { it.tooSmall }
    Box(modifier.onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        Canvas(Modifier.fillMaxSize()) {
            for (box in boxes) {
                val rect = box.rect.translate(-origin)
                if (box.tooSmall) drawRect(Color.Red.copy(alpha = 0.28f), rect.topLeft, rect.size)
                drawRect(
                    color = when {
                        box.tooSmall -> Color.Red
                        box.clickable -> Color(0xFF00C853)
                        else -> Color(0xFF00B8D4).copy(alpha = 0.6f)
                    },
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.BottomStart) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color.Black.copy(alpha = 0.72f),
                contentColor = if (small > 0) Color(0xFFFF8A80) else Color.White,
                modifier = Modifier.padding(8.dp).semantics { contentDescription = "Layout bounds" },
            ) {
                Text(
                    "${boxes.size} nodes · ${boxes.count { it.clickable }} targets · $small under 48 dp",
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = OpenCodeFonts.monospace),
                )
            }
        }
    }
}

private fun collect(node: SemanticsNode, minTarget: Float, into: MutableList<BoundsBox>) {
    val bounds = node.boundsInRoot
    if (bounds.width > 0 && bounds.height > 0 && !node.isRoot) {
        val clickable = SemanticsActions.OnClick in node.config || SemanticsActions.OnLongClick in node.config
        // The touch area Compose actually uses can exceed the drawn bounds (minimumInteractiveComponentSize).
        val touch = node.touchBoundsInRoot
        val tooSmall = clickable && (touch.width < minTarget || touch.height < minTarget)
        into += BoundsBox(bounds, clickable, tooSmall)
    }
    for (child in node.children) collect(child, minTarget, into)
}
