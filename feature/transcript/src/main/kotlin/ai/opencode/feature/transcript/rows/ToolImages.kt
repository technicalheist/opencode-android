package ai.opencode.feature.transcript.rows

import ai.opencode.feature.media.AgentImageRowAnchor
import ai.opencode.feature.media.CachedImage
import ai.opencode.feature.media.MediaReferenceResolver
import ai.opencode.feature.transcript.user.ToolImageMosaicTile
import ai.opencode.server.model.OpenCodeImageAttachment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every image a tool read, whole and in order, so it can be read without opening it: justified rows
 * ([JustifiedImageRows]) where each row's images share a height and together fill the width,
 * nothing cropped or hidden. Tapping one opens them all in the gallery, at [onPreview]'s ID.
 */
@Composable
fun ToolImageMosaic(images: List<OpenCodeImageAttachment>, modifier: Modifier = Modifier, onPreview: (String) -> Unit) {
    // Shapes of `media:` tiles, as they loaded or were first shown. The server sends no
    // dimensions for tool images, so without these they'd be square until loaded, and again
    // after a memory warning empties the caches.
    val loadedAspects = remember { mutableStateMapOf<String, Float>() }
    // The row's place in the transcript, to say its height is about to change.
    val row = remember { AgentImageRowAnchor() }
    val decoded by produceState(images.map { CachedImage.cachedThumbnail(it.id, it.mediaHash) }, images) {
        value = withContext(Dispatchers.Default) { images.map(MediaReferenceResolver::thumbnail) }
    }
    val aspects = ToolImageMosaic.aspects(images, decoded.map { it?.let { image -> IntSize(image.width, image.height) } }, loadedAspects)
    AgentImageRowAnchor.Placement(row)
    JustifiedImagesLayout(aspects, modifier.semantics { testTag = "tool-image-mosaic" }) {
        images.forEachIndexed { index, attachment ->
            key(attachment.id) {
                ToolImageMosaicTile(
                    attachment, decoded.getOrNull(index), index, images.size, onPreview,
                    onLoad = { size ->
                        val change = ToolImageMosaic.change(size, loadedAspects[attachment.id], aspects.getOrElse(index) { 1f })
                        // A new shape marks the row revised first, so the list eases it, or lands
                        // it with the offset compensated above the reader.
                        if (change.revisesRow) row.revised()
                        change.record?.let { loadedAspects[attachment.id] = it }
                    },
                )
            }
        }
    }
}

object ToolImageMosaic {
    /** What a tile's loaded image changes: the shape to remember (null when known), and whether the row's height may change. */
    data class Change(val record: Float?, val revisesRow: Boolean)

    /** Each tile's shape: its decoded image, else the size it loaded at, else the server's dimensions, else square. */
    fun aspects(images: List<OpenCodeImageAttachment>, decoded: List<IntSize?>, loaded: Map<String, Float>): List<Float> =
        images.zip(decoded) { attachment, size ->
            if (size != null && size.width > 0 && size.height > 0) return@zip size.width.toFloat() / size.height
            loaded[attachment.id] ?: attachment.aspectRatio?.toFloat() ?: 1f
        }

    /**
     * What a tile's image of [size] changes: the shape to remember (null when already known),
     * and whether the row's height may change, as it does when a new shape differs from the one
     * the tile was laid out at. A known shape is already in the layout, whatever [laidOutAt] was
     * captured.
     */
    fun change(size: IntSize, known: Float?, laidOutAt: Float): Change {
        if (size.width <= 0 || size.height <= 0) return Change(null, false)
        val aspect = size.width.toFloat() / size.height
        val isKnown = known?.let { abs(it - aspect) < 0.001f } ?: false
        if (isKnown) return Change(null, false)
        return Change(aspect, abs(laidOutAt - aspect) >= 0.001f)
    }
}

/** Lays out images by [JustifiedImageRows], each at its exact frame. */
@Composable
fun JustifiedImagesLayout(
    aspects: List<Float>,
    modifier: Modifier = Modifier,
    /** How tall a lone image may stand. */
    loneMaxHeight: Float = JustifiedImageRows.singleMaxHeight,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 360.dp.toPx()
        val rows = JustifiedImageRows(aspects, width / density, loneMaxHeight)
        val placeables = measurables.mapIndexedNotNull { index, measurable ->
            val frame = rows.frames.getOrNull(index) ?: return@mapIndexedNotNull null
            val w = (frame.width * density).roundToInt()
            val h = (frame.height * density).roundToInt()
            measurable.measure(Constraints.fixed(max(0, w), max(0, h))) to frame
        }
        val height = (rows.height * density).roundToInt()
        layout(width.roundToInt(), height.coerceIn(constraints.minHeight, max(constraints.minHeight, constraints.maxHeight))) {
            for ((placeable, frame) in placeables) placeable.place((frame.left * density).roundToInt(), (frame.top * density).roundToInt())
        }
    }
}

/**
 * Justified rows: images keep their shapes, a row's images share one height and fill the width, and
 * the breaks between rows are chosen (in order) to keep every row near a comfortable height. A wide
 * panorama gets a row to itself; a tall screenshot pairs with something square. A row that can't
 * fill the width without growing past the cap (a lone tall screenshot) stays at the cap,
 * leading-aligned. Units are dp.
 */
@Immutable
class JustifiedImageRows(rawAspects: List<Float>, width: Float, loneMaxHeight: Float = singleMaxHeight) {
    val frames: List<Rect>
    val height: Float

    init {
        val aspects = rawAspects.map { if (it.isFinite() && it > 0) min(max(it, aspectRange.start), aspectRange.endInclusive) else 1f }
        if (aspects.isEmpty() || width <= 0) {
            frames = emptyList()
            height = 0f
        } else if (aspects.size == 1) {
            val h = min(width / aspects[0], loneMaxHeight)
            frames = listOf(Rect(0f, 0f, min(width, h * aspects[0]), h))
            height = h
        } else {
            val target = width * 0.6f
            val maxHeight = width * 0.9f
            // A row's height when it fills the width, and its cost: how far it is from the
            // target, and a steep price for a row that can't fill.
            fun rowHeight(start: Int, end: Int): Float {
                val gaps = spacing * (end - start - 1)
                var sum = 0f
                for (i in start until end) sum += aspects[i]
                return (width - gaps) / sum
            }
            fun cost(start: Int, end: Int): Float {
                val h = rowHeight(start, end)
                var c = (h - target) * (h - target)
                if (h > maxHeight) c += (h - maxHeight) * (h - maxHeight) * 20
                if (end - start > 4) c += 1e9f
                return c
            }
            // Best breaks for the first i images, then walk back.
            val n = aspects.size
            val best = FloatArray(n + 1) { Float.POSITIVE_INFINITY }
            val from = IntArray(n + 1)
            best[0] = 0f
            for (end in 1..n) {
                for (start in max(0, end - 4) until end) {
                    if (best[start] == Float.POSITIVE_INFINITY) continue
                    val total = best[start] + cost(start, end)
                    if (total < best[end]) {
                        best[end] = total
                        from[end] = start
                    }
                }
            }
            val ranges = ArrayDeque<IntRange>()
            var end = n
            while (end > 0) {
                ranges.addFirst(from[end] until end)
                end = from[end]
            }
            val result = mutableListOf<Rect>()
            var y = 0f
            for (range in ranges) {
                val h = min(rowHeight(range.first, range.last + 1), maxHeight)
                var x = 0f
                for (index in range) {
                    val w = h * aspects[index]
                    result += Rect(x, y, x + w, y + h)
                    x += w + spacing
                }
                y += h + spacing
            }
            frames = result
            height = max(0f, y - spacing)
        }
    }

    override fun equals(other: Any?): Boolean = other is JustifiedImageRows && other.frames == frames && other.height == height
    override fun hashCode(): Int = frames.hashCode() * 31 + height.hashCode()

    companion object {
        const val spacing: Float = 4f
        const val cornerRadius: Float = 14f
        /** Shapes are laid out within this range: a stranger one gets a tile at its edge. */
        val aspectRange: ClosedFloatingPointRange<Float> = 0.2f..8f
        /** A lone image may stand this tall (a phone screenshot would otherwise run to two screens). */
        const val singleMaxHeight: Float = 520f
    }
}
