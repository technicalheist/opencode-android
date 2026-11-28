package ai.opencode.feature.media

import ai.opencode.core.store.Store

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.rememberAppAppearance
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// MARK: - Sources

/**
 * Which corners of a source are rounded: a collage tile rounds only the collage's own. Absolute
 * (not start/end), like the mask and the flight's window frame.
 */
data class MediaCorners(
    val topLeft: Boolean = true,
    val topRight: Boolean = true,
    val bottomLeft: Boolean = true,
    val bottomRight: Boolean = true,
) {
    fun radii(radius: Float): List<Float> = listOf(
        if (topLeft) radius else 0f,
        if (topRight) radius else 0f,
        if (bottomRight) radius else 0f,
        if (bottomLeft) radius else 0f,
    )

    fun roundRect(rect: Rect, radius: Float): RoundRect {
        val r = radii(max(radius, 0f))
        return RoundRect(
            rect = rect,
            topLeft = CornerRadius(r[0]),
            topRight = CornerRadius(r[1]),
            bottomRight = CornerRadius(r[2]),
            bottomLeft = CornerRadius(r[3]),
        )
    }

    fun shape(radius: Dp): RoundedCornerShape = RoundedCornerShape(
        topStart = if (topLeft) radius else 0.dp,
        topEnd = if (topRight) radius else 0.dp,
        bottomEnd = if (bottomRight) radius else 0.dp,
        bottomStart = if (bottomLeft) radius else 0.dp,
    )

    companion object {
        val All = MediaCorners()
    }
}

/**
 * The views full-screen media zooms out of and back into. Thumbnails register their layout
 * coordinates by media ID; lists reuse items, so each source carries the media it shows now and
 * lookups check it. A UI registry, only touched on the main thread.
 */
@Stable
class MediaZoomSources internal constructor() {
    /** A registered view. */
    class Source internal constructor() {
        /** The first is the media this view shows; the rest are pages it stands in for, like the collage's “+2” tile. */
        var mediaIDs: List<String> = emptyList()
            internal set
        internal var coordinates: LayoutCoordinates? = null
        internal var cornerRadius: Dp = 0.dp
        internal var corners: MediaCorners = MediaCorners.All
        internal var density: Float = 1f
        /** It draws the media itself (a thumbnail), so the viewer lands on it without a fade; a card only stands in for it. */
        internal var showsMedia: Boolean = false
        /** It covers the screen's other views of the same media, so flights start and land on it. */
        internal var isPreferred: Boolean = false

        /** Its exact frame in the window, unclipped. */
        val windowRect: Rect?
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return null
                val origin = coordinates.positionInWindow()
                return Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
            }

        /** On screen: a view scrolled out of sight is no place to land. */
        internal val isOnScreen: Boolean
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return false
                val visible = coordinates.boundsInWindow()
                return visible.width > 0f && visible.height > 0f
            }

        /** Nothing of it clipped away, so the media can land on all of it. */
        internal val isFullyVisible: Boolean
            get() {
                val coordinates = coordinates?.takeIf { it.isAttached } ?: return false
                val visible = coordinates.boundsInWindow()
                val area = coordinates.size.width.toFloat() * coordinates.size.height
                return area > 0f && visible.width * visible.height >= area * 0.98f
            }

        val cornerRadiusPx: Float get() = cornerRadius.value * density
    }

    private val sources = LinkedHashSet<Source>()

    /** The media up full screen. */
    var hiddenID: String? by mutableStateOf(null)
        private set

    internal fun add(source: Source) {
        sources.add(source)
    }

    internal fun remove(source: Source) {
        sources.remove(source)
    }

    fun isHidden(ids: List<String>): Boolean = hiddenID?.let(ids::contains) ?: false

    /** Out of the transcript while its media is up full screen, so a drag never shows it twice; an item recomposed meanwhile comes up hidden too. */
    fun hide(id: String?) {
        if (id == hiddenID) return
        hiddenID = id
    }

    /** The on-screen view showing [id], preferring one that shows it over one standing in for it. */
    fun view(id: String?): Source? {
        if (id == null) return null
        val candidates = sources.filter { it.mediaIDs.contains(id) && it.isOnScreen }.sortedByDescending { it.isPreferred }
        return candidates.firstOrNull { it.mediaIDs.firstOrNull() == id } ?: candidates.firstOrNull()
    }

    companion object {
        /** The process's one registry. */
        val shared: MediaZoomSources = MediaZoomSources()
    }
}

private class MediaZoomSourceElement(
    val ids: List<String>,
    val cornerRadius: Dp,
    val corners: MediaCorners,
    val showsMedia: Boolean,
    val isPreferred: Boolean = false,
) : ModifierNodeElement<MediaZoomSourceNode>() {
    override fun create() = MediaZoomSourceNode(ids, cornerRadius, corners, showsMedia).also { it.source.isPreferred = isPreferred }

    override fun update(node: MediaZoomSourceNode) {
        node.source.mediaIDs = ids
        node.source.cornerRadius = cornerRadius
        node.source.corners = corners
        node.source.showsMedia = showsMedia
        node.source.isPreferred = isPreferred
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mediaZoomSource"
        properties["ids"] = ids
    }

    override fun equals(other: Any?) =
        other is MediaZoomSourceElement && other.ids == ids && other.cornerRadius == cornerRadius && other.corners == corners &&
            other.showsMedia == showsMedia && other.isPreferred == isPreferred

    override fun hashCode() =
        (((ids.hashCode() * 31 + cornerRadius.hashCode()) * 31 + corners.hashCode()) * 31 + showsMedia.hashCode()) * 31 + isPreferred.hashCode()
}

private class MediaZoomSourceNode(
    ids: List<String>,
    cornerRadius: Dp,
    corners: MediaCorners,
    showsMedia: Boolean,
) : Modifier.Node(), GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    val source = MediaZoomSources.Source().also {
        it.mediaIDs = ids
        it.cornerRadius = cornerRadius
        it.corners = corners
        it.showsMedia = showsMedia
    }

    override fun onAttach() {
        MediaZoomSources.shared.add(source)
    }

    override fun onDetach() {
        MediaZoomSources.shared.remove(source)
        source.coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        source.coordinates = coordinates
        source.density = currentValueOf(LocalDensity).density
    }
}

/**
 * Marks a card or row that opens full-screen media, so the viewer zooms out of it and back into it.
 * Only its frame and shape are used. [showsMedia]: it draws the media itself (cropped to fill), so
 * the media lands on it without fading. [preferred]: a page over the transcript, whose views of the
 * same media it covers.
 */
fun Modifier.mediaZoomSource(id: String, cornerRadius: Dp = 0.dp, showsMedia: Boolean = false, preferred: Boolean = false): Modifier =
    this then MediaZoomSourceElement(listOf(id), cornerRadius, MediaCorners.All, showsMedia, preferred)

/**
 * An image the full-screen viewer zooms out of and back into, so the flight can start and land on
 * exactly its frame and shape. It leaves the transcript (alpha 0) while its media is up full
 * screen. Decorative: the button around it carries the label and takes the taps.
 *
 * @param ids the media this shows, then any pages it stands in for.
 * @param fits fill crops to the frame; fit sizes itself to the image, at most [maxHeight] tall.
 * @param corners the rounded corners: a collage tile rounds only the collage's own.
 */
@Composable
fun MediaThumbnail(
    image: Bitmap,
    ids: List<String>,
    modifier: Modifier = Modifier,
    fits: Boolean = false,
    maxHeight: Dp = Dp.Infinity,
    cornerRadius: Dp = 0.dp,
    corners: MediaCorners = MediaCorners.All,
) {
    val bitmap = remember(image) { image.asImageBitmap() }
    val shape = remember(cornerRadius, corners) { corners.shape(cornerRadius) }
    val sources = MediaZoomSources.shared
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = if (fits) ContentScale.Fit else ContentScale.Crop,
        filterQuality = FilterQuality.High,
        modifier = modifier
            .thumbnailSize(image, fits, maxHeight)
            .then(MediaZoomSourceElement(ids, cornerRadius, corners, showsMedia = true))
            .graphicsLayer {
                alpha = if (sources.isHidden(ids)) 0f else 1f
                this.shape = shape
                clip = true
            },
    )
}

/**
 * Fill takes the proposal (the image's size where unspecified); fit fits the image, at most
 * [maxHeight] tall.
 */
private fun Modifier.thumbnailSize(image: Bitmap, fits: Boolean, maxHeight: Dp): Modifier = layout { measurable, constraints ->
    // Pixels as points.
    val imageSize = Size(image.width.dp.toPx(), image.height.dp.toPx())
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else imageSize.width
    val proposedHeight = if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY
    val size = if (fits) {
        val cap = if (maxHeight == Dp.Infinity) Float.POSITIVE_INFINITY else maxHeight.toPx()
        MediaZoomGeometry.fittedSize(imageSize, Size(width, min(proposedHeight, cap)))
    } else {
        Size(width, if (proposedHeight.isInfinite()) imageSize.height else proposedHeight)
    }
    // The image keeps its own frame (its clip, and where flights start and land) even when the
    // caller insists on a larger one (`fillMaxWidth`): centred in it.
    val w = size.width.roundToInt().coerceIn(0, constraints.maxWidth)
    val h = size.height.roundToInt().coerceIn(0, constraints.maxHeight)
    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(w, h))
    val outerWidth = max(w, constraints.minWidth)
    val outerHeight = max(h, constraints.minHeight)
    layout(outerWidth, outerHeight) { placeable.place((outerWidth - w) / 2, (outerHeight - h) / 2) }
}

// MARK: - Viewer

/**
 * What the presentation needs from the full-screen content, and what the content needs from the
 * presentation. Content reads it from [LocalMediaViewer].
 */
@Stable
class MediaViewerState(sourceID: String?) {
    /** What's on screen, for the flight back into the transcript. */
    var sourceID: String? by mutableStateOf(sourceID)
        private set

    /** The image on screen: its page draws it where the motion places it ([placeImage]). */
    var image: Bitmap? by mutableStateOf(null)
        private set

    /** The page on screen plays a video, which opens once its first frame is up ([videoRendered]). */
    var isVideo by mutableStateOf(false)
        private set

    /** The video whose first frame is up. */
    internal var renderedID: String? by mutableStateOf(null)

    /** A zoomed-in image pans instead of dismissing. */
    var isZoomed by mutableStateOf(false)

    /** Close and the page position step aside while a drag dismisses. */
    var chromeHidden by mutableStateOf(false)
        internal set

    /** How long the last chrome change fades (0.15 s hiding, 0.25 s showing), for content that animates its own chrome. */
    var chromeFadeSeconds: Double = 0.25
        internal set

    /** Whether the status bar is hidden while the dark room is up (such as while photo viewer controls are hidden). */
    var statusBarHidden by mutableStateOf(false)
        internal set

    /** The image on screen is drawn by the hand-off's flight instead. */
    var mediaHidden by mutableStateOf(false)
        internal set

    /**
     * Where the selected image page draws an image laid out at a frame, while the presentation
     * moves it (open, drag, close); null draws it in place.
     */
    var placeImage: (frame: Rect) -> MediaPlacement? = { null }
        internal set

    /** Where each page lays its image out, by media ID, so flights land exactly on it. */
    val mediaFrames: MutableMap<String, Rect> = HashMap()

    val mediaFrame: Rect? get() = sourceID?.let { mediaFrames[it] }

    /** Where each image page draws its image in the window, fitted or zoomed, by media ID. */
    val pageFrames: MutableMap<String, Rect> = HashMap()

    /** The badges drawn on each source's corners, by media ID, carried by the flight. */
    var flightAccessories: (String) -> List<MediaFlightAccessory> = { emptyList() }
        internal set

    var dismiss: () -> Unit = {}
        internal set

    /** Puts the media away without flying it home, for media that's gone (a composer photo removed from the viewer). */
    var discard: () -> Unit = {}
        internal set

    var handOff: (handOff: DrawingHandOff, frame: Rect?) -> Unit = { _, _ -> }
        internal set

    var handOffCovered by mutableStateOf(false)
        internal set
    internal var onShow: () -> Unit = {}

    /** The content moved to another page or finished loading its image. */
    fun show(sourceID: String?, image: Bitmap?, isVideo: Boolean = false) {
        val moved = sourceID != this.sourceID
        this.sourceID = sourceID
        this.image = image
        this.isVideo = isVideo
        if (moved) {
            isZoomed = false
            onShow()
        }
    }

    /** A video page's first frame is up ([id]), or gone (null). */
    fun videoRendered(id: String?) {
        renderedID = id
    }

    fun setStatusBarHidden(hidden: Boolean) {
        if (statusBarHidden == hidden) return
        statusBarHidden = hidden
    }
}

val LocalMediaViewer = staticCompositionLocalOf<MediaViewerState?> { null }

/**
 * A badge on a thumbnail's corner (a composer photo's ×, a video's duration). It rides the flight
 * pinned to that corner at its own size (the bitmap's pixels), fading out as the image flies in and
 * back in as it flies home.
 */
class MediaFlightAccessory(
    val image: Bitmap,
    val corner: Corner,
    /** From the thumbnail's corner to the image's matching corner, in px. */
    val offset: Offset = Offset.Zero,
) {
    enum class Corner { TopTrailing, BottomLeading }
}

/**
 * Full-screen media and files. Media opens the Photos way: it flies out of what was tapped, follows
 * a drag down while shrinking, rounding toward the thumbnail's corners and letting the session show
 * through, and flies back on release (or springs back from a short drag). Documents zoom out of
 * their source, and a swipe down (from anywhere a scroll view doesn't take it) shrinks them toward
 * it and zooms them home, as the system zoom's does. Under Reduce Motion both cross-fade, and a
 * drag down fades them out. One viewer at a time.
 *
 * [MediaView] owns the presenter and provides it as [LocalMediaPresenter] to the content it
 * hosts; the transcript image viewer goes through the store ([presentPreview]).
 */
@Stable
class MediaPresenter internal constructor() {
    internal class Presentation(
        val sourceID: String?,
        val isMedia: Boolean,
        val reducesMotion: Boolean,
        val screenName: String,
        val flightAccessories: ((String) -> List<MediaFlightAccessory>)?,
        val onDismiss: (() -> Unit)?,
        val content: @Composable (MediaViewerState) -> Unit,
    )

    internal var presentation: Presentation? by mutableStateOf(null)
    internal var reducesMotion = false

    val isPresenting: Boolean get() = presentation != null

    /**
     * Presents [content] full screen; ignored while a viewer is up. [flightAccessories] are the
     * source's corner badges the flight carries; [onDismiss] runs once the viewer is gone and
     * its source shows again.
     */
    fun present(
        sourceID: String?,
        isMedia: Boolean,
        reducesMotion: Boolean,
        screenName: String? = null,
        flightAccessories: ((String) -> List<MediaFlightAccessory>)? = null,
        onDismiss: (() -> Unit)? = null,
        content: @Composable (MediaViewerState) -> Unit,
    ): Boolean {
        // One viewer at a time.
        if (isPresenting) return false
        presentation = Presentation(
            sourceID,
            isMedia,
            reducesMotion,
            screenName ?: if (isMedia) localized(R.string.media_viewer_screen_title) else localized(R.string.media_file_viewer_screen_title),
            flightAccessories,
            onDismiss,
            content,
        )
        return true
    }

    /** A store per presentation, so the opening prompt and its cancellable effects end together. */
    fun presentPreview(
        preview: TranscriptImagePreview,
        addDrawing: ImageDrawingAdd? = null,
        documentGallery: (AgentFileDestination) -> TranscriptImagePreview? = { null },
        flightAccessories: ((String) -> List<MediaFlightAccessory>)? = null,
    ) {
        if (isPresenting) return
        val file = preview.pages.firstOrNull { it.id == preview.id }?.file
        val store = Store(
            FullscreenViewerFeature.State(preview.withSelectedStandIn(), allowsDrawing = addDrawing != null),
            FullscreenViewerFeature(addDrawing),
        )
        present(preview.id, file?.reference?.kind?.isMedia ?: true, reducesMotion, flightAccessories = flightAccessories, onDismiss = store::close) { viewer ->
            CompositionLocalProvider(LocalDocumentVisualGallery provides documentGallery) { FullscreenViewer(store, viewer) }
        }
    }

    companion object {
        /** Media plays in a dark room; documents and text viewers follow the app's appearance. */
        fun isDarkRoom(isMedia: Boolean): Boolean = isMedia
    }
}

/** The presenter of the [MediaView] above this content, or `null` outside one. */
val LocalMediaPresenter = staticCompositionLocalOf<MediaPresenter?> { null }

/**
 * The Photos drag: how media shrinks, rounds, and lets the session show through as it's pulled
 * down, and when letting go puts it away. Distances in one unit; [dismisses] takes dp and dp/s.
 */
data class MediaDismissDrag(val screenHeight: Float) {
    /** How far down the media is at its smallest and the session fully shows. */
    val range: Float = max(screenHeight * 0.5f, 1f)

    /** 0 at rest, 1 at the bottom of the range. */
    fun progress(distance: Float): Float = min(max(distance / range, 0f), 1f)

    fun scale(distance: Float): Float = 1 - (1 - minimumScale) * progress(distance)

    /** The drag distance a scale corresponds to, to pick up a spring-back. */
    fun distance(atScale: Float): Float = min(max((1 - atScale) / (1 - minimumScale), 0f), 1f) * range

    fun backdropAlpha(distance: Float): Float = 1 - progress(distance)

    /** Square at full screen, rounding toward the thumbnail's corners with the scale, so a release flies home already the thumbnail's shape. */
    fun cornerRadius(distance: Float, toward: Float): Float = toward * progress(distance)

    companion object {
        const val minimumScale: Float = 0.6f
        const val dismissDistance: Float = 120f
        const val dismissVelocity: Float = 900f

        /** Far enough, or flicked down; a flick back up keeps it. */
        fun dismisses(distance: Float, velocity: Float): Boolean =
            (distance > dismissDistance && velocity > -300f) || velocity > dismissVelocity
    }
}

/**
 * Android only: how far a predictive back gesture pulls the media at full progress, as
 * fractions of the pull-down's range ([MediaDismissDrag.range]) and of the screen's width.
 * At full progress the media is at about 88% of its size, as Android's own back preview.
 */
internal object MediaBackPreview {
    const val DOWN = 0.3f
    const val SIDEWAYS = 0.04f
}

/**
 * The viewer's motion roles: open and the documents' close on surface; a short drag back on panel
 * open; home to the thumbnail on flight; nowhere to land,
 * carried on and faded on panel close; the backdrop's fade in on dim.
 */
internal object MediaMotion {
    val open = MotionRole.Surface
    val springBack = MotionRole.PanelOpen
    val home = MotionRole.Flight
    val away = MotionRole.PanelClose
    val backdropIn = MotionRole.Dim
    /** Reduce Motion's open (0.2 s). */
    const val fade = 0.2
}

/**
 * Where the viewer draws media laid out at a frame: scaled by [scale] about the origin, moved by
 * [offset], and clipped to [clip] (rounded by [radius] on [corners]).
 */
class MediaPlacement internal constructor(
    val scale: Float,
    val offset: Offset,
    val clip: Rect?,
    val radius: Float,
    val corners: MediaCorners,
    val alpha: Float,
) {
    fun map(rect: Rect): Rect = Rect(
        rect.left * scale + offset.x,
        rect.top * scale + offset.y,
        rect.right * scale + offset.x,
        rect.bottom * scale + offset.y,
    )
}

/** Draws [content] where [placement] puts it. */
internal fun DrawScope.drawPlaced(placement: MediaPlacement, content: DrawScope.() -> Unit) {
    if (placement.alpha <= 0f) return
    val moved: DrawScope.() -> Unit = {
        withTransform({
            translate(placement.offset.x, placement.offset.y)
            scale(placement.scale, placement.scale, pivot = Offset.Zero)
        }, content)
    }
    val clip = placement.clip
    if (clip == null) {
        moved()
    } else {
        val path = Path().apply { addRoundRect(placement.corners.roundRect(clip, placement.radius)) }
        clipPath(path, block = moved)
    }
}

/** The drawn image flying from the editor into its composer slot. */
internal class Flight(image: Bitmap, frame: Rect, val drawing: ImageDrawing?) {
    val bitmap: ImageBitmap = image.asImageBitmap()
    var frame: Rect by mutableStateOf(frame)
    var radius by mutableFloatStateOf(0f)
    var corners: MediaCorners by mutableStateOf(MediaCorners.All)
    var alpha by mutableFloatStateOf(1f)
    var scale by mutableFloatStateOf(1f)
    var badges: List<Pair<MediaFlightAccessory, ImageBitmap>> = emptyList()
    var badgeAlpha by mutableFloatStateOf(0f)
}

/**
 * Drives one presentation. The media keeps its own layout; the motion is a transform of it: the
 * drag's (`x → dragScale · x + dragOffset`), carried [progress] of the way onto [home] and clipped
 * to its shape. An image page draws itself with it ([MediaViewerState.placeImage]); other content
 * (video, documents, a file still loading) moves as a whole layer. Under Reduce Motion it
 * cross-fades instead, and a drag down fades it out.
 */
@Stable
internal class MediaViewerController(
    val state: MediaViewerState,
    private val isMedia: Boolean,
    private val reducesMotion: Boolean,
    private val scope: CoroutineScope,
    private val density: Density,
    private val onDismissed: () -> Unit,
) {
    /** Where the media flies to (open: from): a source's live frame, or a fixed one when there's nowhere to land. */
    private class Home(
        val source: MediaZoomSources.Source?,
        val frame: Rect,
        val radius: Float,
        val corners: MediaCorners,
        /** The source shows this very media, so nothing needs to fade. */
        val showsMedia: Boolean,
        val badges: List<Pair<MediaFlightAccessory, ImageBitmap>>,
    )

    /** The host's size and where it sits in the window (sources are in window coordinates). */
    var bounds by mutableStateOf(Rect.Zero)
    var originInWindow = Offset.Zero
    /** Where the finger is, for a dismissal a scroll view hands over (nested scroll has no position). */
    internal var lastPointer = Offset.Zero

    var backdropAlpha by mutableFloatStateOf(if (reducesMotion) 1f else 0f)
    /** Reduce Motion: the whole viewer's opacity. */
    var viewAlpha by mutableFloatStateOf(if (reducesMotion) 0f else 1f)
    /** Light over the dark room; the session's own once it shows through. */
    var statusBarLight by mutableStateOf(isMedia && reducesMotion)
    var isLeaving by mutableStateOf(false)
        private set
    var flight: Flight? by mutableStateOf(null)
        private set

    private val photos = isMedia && !reducesMotion
    private var shown by mutableStateOf(reducesMotion)
    private var dragScale by mutableFloatStateOf(1f)
    private var dragOffset by mutableStateOf(Offset.Zero)
    private var dragRadius by mutableFloatStateOf(0f)
    private var dragCorners: MediaCorners = MediaCorners.All
    private var progress by mutableFloatStateOf(0f)
    private var home: Home? by mutableStateOf(null)

    private var finished = false
    private var motionJob: Job? = null
    private var backdropJob: Job? = null

    // A drag: the point of the media under the finger, where the finger was, and how far down it already was.
    private var dragAnchor = Offset.Zero
    private var dragFinger = Offset.Zero
    private var dragStartDistance = 0f
    private var lastFadeDistance = 0f

    init {
        state.dismiss = { close() }
        state.discard = { close(home = false) }
        state.handOff = { handOff, frame -> handOff(handOff, frame) }
        state.placeImage = { frame -> if (drawsImage) placement(frame) else null }
        if (photos) {
            state.chromeHidden = true
            state.onShow = { MediaZoomSources.shared.hide(state.sourceID) }
        }
    }

    /** Whether the status bar is hidden. */
    val statusBarHidden: Boolean
        get() {
            if (isLeaving || !state.statusBarHidden) return false
            return if (photos) statusBarLight else true
        }

    /** The image page draws itself where the motion has it; anything else moves as a whole. */
    private val drawsImage: Boolean get() = photos && state.image != null

    private fun updateStatusBarLight(light: Boolean) {
        if (!photos) return
        if (statusBarLight != light) statusBarLight = light
    }

    // MARK: Placement

    /** Where the image rests: where the page lays it out, or else fitted to the screen. */
    private val restingFrame: Rect
        get() {
            state.mediaFrame?.let { return it }
            val image = state.image ?: return bounds
            if (image.width <= 0 || image.height <= 0) return bounds
            val fitted = MediaZoomGeometry.fittedSize(Size(image.width.toFloat(), image.height.toFloat()), bounds.size)
            return Rect(Offset(bounds.center.x - fitted.width / 2, bounds.center.y - fitted.height / 2), fitted)
        }

    /** The frame the layer's motion follows: the video where it plays, else the whole page. */
    private val layerFrame: Rect get() = if (isMedia) state.mediaFrame ?: bounds else bounds

    private fun dragged(rect: Rect): Rect = Rect(
        rect.left * dragScale + dragOffset.x,
        rect.top * dragScale + dragOffset.y,
        rect.right * dragScale + dragOffset.x,
        rect.bottom * dragScale + dragOffset.y,
    )

    /** Where media laid out at [frame] is drawn now. */
    fun placement(frame: Rect): MediaPlacement {
        if (!shown) return MediaPlacement(1f, Offset.Zero, null, 0f, MediaCorners.All, alpha = 0f)
        val drawn = dragged(frame)
        val visible = drawn.intersect(dragged(bounds))
        val home = home
        if (home == null) {
            val clip = if (dragRadius > 0.5f) visible else null
            return MediaPlacement(dragScale, dragOffset, clip, dragRadius, dragCorners, alpha = 1f)
        }
        val p = progress
        val target = homeFrame(home)
        val image = lerp(drawn, cover(frame.size, target, topAligned = !isMedia), p)
        val scale = image.width / max(frame.width, 1f)
        val offset = Offset(image.left - frame.left * scale, image.top - frame.top * scale)
        val alpha = if (home.showsMedia) 1f else (1 - p).coerceIn(0f, 1f)
        return MediaPlacement(scale, offset, lerp(visible, target, p), dragRadius + (home.radius - dragRadius) * p, home.corners, alpha)
    }

    /** The content layer's placement: still while an image page draws its own. */
    fun layerPlacement(): MediaPlacement {
        if (reducesMotion || drawsImage) return MediaPlacement(1f, Offset.Zero, null, 0f, MediaCorners.All, alpha = if (shown) 1f else 0f)
        val placement = placement(layerFrame)
        // At rest, chrome outside the video stays.
        if (isMedia && home == null && dragRadius <= 0.5f) return MediaPlacement(placement.scale, placement.offset, null, 0f, MediaCorners.All, placement.alpha)
        return placement
    }

    private fun homeFrame(home: Home): Rect = home.source?.let(::sourceFrame) ?: home.frame

    private fun sourceFrame(source: MediaZoomSources.Source): Rect? =
        source.windowRect?.translate(-originInWindow.x, -originInWindow.y)

    /** [size] scaled to fill [target], centred on it (a page: its top on the target's). */
    private fun cover(size: Size, target: Rect, topAligned: Boolean): Rect {
        val scale = max(target.width / max(size.width, 1f), target.height / max(size.height, 1f))
        val width = size.width * scale
        val height = size.height * scale
        val top = if (topAligned) target.top else target.center.y - height / 2
        return Rect(Offset(target.center.x - width / 2, top), Size(width, height))
    }

    private val source: MediaZoomSources.Source? get() = MediaZoomSources.shared.view(state.sourceID)

    /** The source, if it's fully on screen; else a fixed frame about [from]: [scale]d and dropped by [drop]. */
    private fun homeFor(source: MediaZoomSources.Source?, from: Rect, scale: Float, drop: Float): Home {
        val frame = source?.takeIf { it.isFullyVisible }?.let(::sourceFrame)
        if (source != null && frame != null) {
            val id = state.sourceID
            val badges = id?.let(state.flightAccessories).orEmpty().map { it to it.image.asImageBitmap() }
            val showsMedia = source.showsMedia && source.mediaIDs.firstOrNull() == id
            return Home(source, frame, source.cornerRadiusPx, source.corners, showsMedia, badges)
        }
        val size = Size(from.width * scale, from.height * scale)
        val fallback = Rect(Offset(from.center.x - size.width / 2, from.center.y - size.height / 2 + drop), size)
        return Home(null, fallback, 0f, MediaCorners.All, showsMedia = false, badges = emptyList())
    }

    /** Folds a flight caught midway into the drag, so a drag or a close carries on from what's on screen. */
    private fun settle() {
        motionJob?.cancel()
        val home = home ?: return
        val frame = if (drawsImage) restingFrame else layerFrame
        val placed = placement(frame)
        dragScale = placed.scale
        dragOffset = placed.offset
        dragRadius = max(placed.radius, 0f)
        dragCorners = home.corners
        this.home = null
        progress = 0f
    }

    // MARK: Open

    /** A video waits (up to a second) for its first frame, so it grows out of its source showing it. */
    suspend fun awaitMedia() {
        if (!photos || !state.isVideo) return
        withTimeoutOrNull(1_000) { snapshotFlow { state.renderedID == state.sourceID }.first { it } }
    }

    /** The presentation runs once the content has laid out. */
    fun open() {
        if (reducesMotion) {
            motionJob = scope.launch {
                animate(viewAlpha, 1f, animationSpec = tween(millis(MediaMotion.fade))) { v, _ -> viewAlpha = v }
            }
            return
        }
        if (shown) return
        val from = if (drawsImage) restingFrame else layerFrame
        home = homeFor(source, from, scale = 0.94f, drop = 0f)
        progress = 1f
        shown = true
        if (isMedia) {
            MediaZoomSources.shared.hide(state.sourceID)
            updateStatusBarLight(true)
            if (drawsImage) setChromeHidden(false, flightDuration = MediaMotion.open.curve().duration)
            backdropJob = scope.launch {
                animate(backdropAlpha, 1f, animationSpec = MediaMotion.backdropIn.animation(reduceMotion = false)) { v, _ -> backdropAlpha = v }
            }
        }
        motionJob = scope.launch {
            spring(MediaMotion.open) { p -> progress = 1 - p }
            home = null
            progress = 0f
            if (isMedia) setChromeHidden(false)
        }
    }

    // MARK: Drag

    private val drag: MediaDismissDrag get() = MediaDismissDrag(screenHeight = bounds.height)

    /**
     * Down and steeper than sideways; never on a zoomed image. Media claims the drag before its
     * content ([fromContent] false: pages turn only once a drag has shown it isn't a dismissal).
     * Documents take only a drag their content left alone ([fromContent] true): a scroll view
     * scrolled away from its top keeps it, and one at its top hands it over through
     * [documentDismissConnection].
     */
    fun shouldBegin(translation: Offset, fromContent: Boolean): Boolean {
        if (isLeaving || !shown) return false
        if (translation.y <= abs(translation.x)) return false
        if (!isMedia) return fromContent
        if (fromContent) return false
        return !state.isZoomed
    }

    /** The media shrinks about the finger, so the spot it holds stays under it, and rounds toward its source. */
    fun dragBegan(anchor: Offset) {
        if (reducesMotion) {
            lastFadeDistance = 0f
            return
        }
        settle()
        backdropJob?.cancel()
        dragAnchor = Offset((anchor.x - dragOffset.x) / dragScale, (anchor.y - dragOffset.y) / dragScale)
        dragFinger = anchor
        dragStartDistance = drag.distance(atScale = dragScale)
        source?.let { dragCorners = it.corners }
        if (isMedia) setChromeHidden(true)
    }

    fun dragChanged(translation: Offset) {
        if (reducesMotion) {
            fadeChanged(max(0f, translation.y))
            return
        }
        val distance = translation.y + dragStartDistance
        val scale = drag.scale(distance)
        dragScale = scale
        dragOffset = Offset(
            dragFinger.x + translation.x - dragAnchor.x * scale,
            dragFinger.y + translation.y - dragAnchor.y * scale,
        )
        dragRadius = drag.cornerRadius(distance, toward = source?.cornerRadiusPx ?: 0f)
        if (isMedia) {
            backdropAlpha = drag.backdropAlpha(distance)
            updateStatusBarLight(backdropAlpha > 0.5f)
        }
    }

    /** [velocity] in px/s. */
    fun dragEnded(translation: Offset, velocity: Velocity) {
        if (reducesMotion) {
            fadeEnded(max(0f, translation.y), velocity.y)
            return
        }
        val distance = translation.y + dragStartDistance
        if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity.y))) close(velocity) else springBack(velocity)
    }

    private fun springBack(velocity: Velocity) {
        val rest = if (drawsImage) restingFrame else layerFrame
        val scale = dragScale
        val offset = dragOffset
        val radius = dragRadius
        val backdrop = backdropAlpha
        val speed = springVelocity(velocity, dragged(rest), rest)
        if (isMedia) {
            setChromeHidden(false)
            updateStatusBarLight(true)
        }
        motionJob?.cancel()
        motionJob = scope.launch {
            spring(MediaMotion.springBack, speed) { p ->
                dragScale = scale + (1 - scale) * p
                dragOffset = offset * (1 - p)
                dragRadius = radius * (1 - p)
                if (isMedia) backdropAlpha = backdrop + (1 - backdrop) * p
            }
        }
    }

    // region Predictive back (Android only)

    /** Whether a predictive back gesture is previewing the dismissal (see [backBegan]). */
    private var backPreviewing = false

    /**
     * A predictive back gesture began: it drives the same drag as a pull down, a little way
     * ([backProgressed]). Returns false when nothing previews (a zoomed image, already leaving);
     * the commit still closes.
     */
    fun backBegan(): Boolean {
        if (isLeaving || !shown) return false
        if (isMedia && state.isZoomed) return false
        dragBegan(bounds.center)
        backPreviewing = true
        return true
    }

    /** [progress] 0..1 from the platform; [fromRightEdge] shifts the media toward the left. */
    fun backProgressed(progress: Float, fromRightEdge: Boolean) {
        if (!backPreviewing) return
        val side = if (fromRightEdge) -1f else 1f
        dragChanged(
            Offset(
                x = side * progress * bounds.width * MediaBackPreview.SIDEWAYS,
                y = progress * drag.range * MediaBackPreview.DOWN,
            ),
        )
    }

    /** The gesture (or a button) committed: the media goes away from wherever it is. */
    fun backCommitted() {
        backPreviewing = false
        close()
    }

    /** The gesture was abandoned: the media springs back to rest. */
    fun backCancelled() {
        if (!backPreviewing) return
        backPreviewing = false
        if (reducesMotion) fadeEnded(distance = 0f, velocity = 0f) else springBack(Velocity.Zero)
    }

    // endregion

    private fun toDp(px: Float): Float = px / density.density

    /** [flightDuration]: the chrome changes on the flight's own spring, so nothing arrives or lingers after the image. */
    private fun setChromeHidden(hidden: Boolean, flightDuration: Double? = null) {
        if (state.chromeHidden == hidden) return
        state.chromeFadeSeconds = flightDuration ?: if (hidden) 0.15 else 0.25
        state.chromeHidden = hidden
    }

    // MARK: Close

    /**
     * Home into the source, carrying a release's [velocity]. With nowhere to land, media carries
     * on down and fades (panel close), and a document shrinks a little as it fades.
     */
    private fun close(velocity: Velocity = Velocity.Zero, home: Boolean = true) {
        if (isLeaving) return
        isLeaving = true
        if (reducesMotion) {
            fadeOut()
            return
        }
        settle()
        backdropJob?.cancel()
        val rest = if (drawsImage) restingFrame else layerFrame
        val from = dragged(rest)
        val target = if (isMedia) {
            homeFor(if (home) source else null, from, scale = 0.8f, drop = bounds.height * 0.25f)
        } else {
            homeFor(if (home) source else null, from, scale = 0.94f, drop = 0f)
        }
        val lands = target.source != null
        if (!lands) MediaZoomSources.shared.hide(null)
        val role = if (!isMedia) MediaMotion.open else if (lands) MediaMotion.home else MediaMotion.away
        val phase = if (isMedia && !lands) MotionPhase.Exit else MotionPhase.Enter
        setChromeHidden(true)
        updateStatusBarLight(false)
        val speed = if (lands) springVelocity(velocity, from, homeFrame(target)) else 0f
        val backdrop = backdropAlpha
        this.home = target
        progress = 0f
        motionJob = scope.launch {
            spring(role, speed, phase) { p ->
                progress = p
                if (isMedia) backdropAlpha = backdrop * (1 - p)
            }
            finishHome()
        }
    }

    /** The source's badges, riding the media home and fading in as it lands. */
    fun DrawScope.drawHomeBadges() {
        val home = home ?: return
        if (home.badges.isEmpty() || progress <= 0f || !isLeaving) return
        val frame = placement(if (drawsImage) restingFrame else layerFrame).clip ?: return
        drawBadges(home.badges, frame, progress * progress)
    }

    // MARK: Hand-off

    /**
     * A drawn image goes into its composer slot: a copy flies under the editor, which goes once
     * the copy covers it, then on to the slot (re-read each frame, as the attachment row opens and
     * scrolls), growing its corners and badges.
     */
    private fun handOff(handOff: DrawingHandOff, frame: Rect?) {
        if (isLeaving) return
        state.mediaHidden = true
        val from = frame?.translate(-originInWindow.x, -originInWindow.y)
        if (!photos || from == null) {
            state.handOffCovered = true
            close(home = false)
            return
        }
        isLeaving = true
        motionJob?.cancel()
        backdropJob?.cancel()
        setChromeHidden(true, flightDuration = 0.0)
        updateStatusBarLight(false)
        val flight = Flight(handOff.image, from, handOff.drawing)
        this.flight = flight
        MediaZoomSources.shared.hide(handOff.target)
        val backdrop = backdropAlpha
        motionJob = scope.launch {
            // The editor's window goes only once the copy is drawn under it.
            repeat(2) { withFrameNanos { } }
            state.handOffCovered = true
            var target = MediaZoomSources.shared.view(handOff.target)?.let(::sourceFrame)
            var waited = 0
            while (target == null && waited < 30) {
                withFrameNanos { }
                waited += 1
                target = MediaZoomSources.shared.view(handOff.target)?.let(::sourceFrame)
            }
            if (target == null) {
                val drop = bounds.height * 0.25f
                spring(MediaMotion.away, phase = MotionPhase.Exit) { p ->
                    flight.frame = from.translate(0f, drop * p)
                    flight.scale = 1 - 0.2f * p
                    flight.alpha = 1 - p
                    backdropAlpha = backdrop * (1 - p)
                }
                finishHome()
                return@launch
            }
            val source = MediaZoomSources.shared.view(handOff.target)
            val radius = source?.cornerRadiusPx ?: 0f
            flight.corners = source?.corners ?: MediaCorners.All
            if (source != null) flight.badges = state.flightAccessories(handOff.target).map { it to it.image.asImageBitmap() }
            var to: Rect = target
            spring(MediaMotion.home) { p ->
                MediaZoomSources.shared.view(handOff.target)?.let(::sourceFrame)?.let { to = it }
                flight.frame = lerp(from, to, p)
                flight.radius = radius * p
                flight.badgeAlpha = p * p
                backdropAlpha = backdrop * (1 - p)
            }
            finishHome()
        }
    }

    // MARK: Reduce Motion

    private fun fadeChanged(distance: Float) {
        lastFadeDistance = distance
        viewAlpha = 1 - (1 - drag.backdropAlpha(distance)) * 0.7f
        setChromeHidden(toDp(distance) > 12f)
    }

    private fun fadeEnded(distance: Float, velocity: Float) {
        if (MediaDismissDrag.dismisses(toDp(distance), toDp(velocity))) {
            close()
        } else {
            val alpha = viewAlpha
            motionJob?.cancel()
            motionJob = scope.launch {
                animate(alpha, 1f, animationSpec = MotionRole.Release.animation(reduceMotion = true)) { v, _ -> viewAlpha = v }
            }
            setChromeHidden(false)
        }
    }

    private fun fadeOut() {
        val alpha = viewAlpha
        motionJob?.cancel()
        motionJob = scope.launch {
            animate(alpha, 0f, animationSpec = tween(millis(Motion.reducedFade), easing = Motion.EaseInOut)) { v, _ -> viewAlpha = v }
            finish()
        }
    }

    /**
     * Documents (zoom or fade): a drag down their scroll view can't take. A scroll view at its top
     * hands over the downward delta it can't use. A drag that already scrolled keeps scrolling.
     * Once dismissing, the scroll view gets nothing (the finger drives the page) and the fling is
     * the release.
     */
    val documentDismissConnection: NestedScrollConnection = object : NestedScrollConnection {
        private var dragging = false
        private var scrolled = false
        private var anchor = Offset.Zero

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (!dragging || source != NestedScrollSource.UserInput) return Offset.Zero
            dragChanged(lastPointer - anchor)
            return available
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (isMedia || isLeaving || dragging || source != NestedScrollSource.UserInput) return Offset.Zero
            if (consumed.y != 0f) scrolled = true
            if (scrolled || available.y <= 0f || available.y <= abs(available.x + consumed.x)) return Offset.Zero
            dragging = true
            // Where the finger was before this delta, so the page moves with all of it.
            anchor = lastPointer - Offset(0f, available.y)
            dragBegan(anchor)
            dragChanged(lastPointer - anchor)
            return available
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            scrolled = false
            if (!dragging) return Velocity.Zero
            dragging = false
            dragEnded(lastPointer - anchor, Velocity(0f, available.y))
            return available
        }
    }

    // MARK: Lifecycle

    /**
     * The thumbnail comes back under the landed media. A thumbnail redraws on its next frame, so
     * the viewer stays two frames longer: never a frame with neither on screen.
     */
    private suspend fun finishHome() {
        MediaZoomSources.shared.hide(null)
        repeat(2) { withFrameNanos { } }
        finish()
    }

    private fun finish() {
        if (finished) return
        finished = true
        MediaZoomSources.shared.hide(null)
        onDismissed()
    }

    fun disposed() {
        motionJob?.cancel()
        backdropJob?.cancel()
        if (photos || MediaZoomSources.shared.hiddenID == state.sourceID) MediaZoomSources.shared.hide(null)
    }

    // MARK: Animation

    /** One curve of progress 0 → 1 for every property. */
    private suspend fun spring(role: MotionRole, velocity: Float = 0f, phase: MotionPhase = MotionPhase.Enter, update: (Float) -> Unit) {
        val spec = role.animation(phase, reducesMotion, visibilityThreshold = 0.0005f)
        animate(0f, 1f, initialVelocity = role.initialVelocity(velocity, reducesMotion), animationSpec = spec) { value, _ -> update(value) }
        update(1f)
    }

    /** A spring's initial velocity, in units of the distance to travel. */
    private fun springVelocity(velocity: Velocity, frame: Rect, target: Rect): Float {
        val dx = target.center.x - frame.center.x
        val dy = target.center.y - frame.center.y
        val length = dx * dx + dy * dy
        if (length <= 1f) return 0f
        return min(max((velocity.x * dx + velocity.y * dy) / length, 0f), 8f)
    }

    private fun millis(seconds: Double): Int = (seconds * 1000).roundToInt()

    private fun lerp(a: Rect, b: Rect, p: Float): Rect = Rect(
        a.left + (b.left - a.left) * p,
        a.top + (b.top - a.top) * p,
        a.right + (b.right - a.right) * p,
        a.bottom + (b.bottom - a.bottom) * p,
    )
}

private fun DrawScope.drawBadges(badges: List<Pair<MediaFlightAccessory, ImageBitmap>>, frame: Rect, alpha: Float) {
    for ((badge, image) in badges) {
        val x = when (badge.corner) {
            MediaFlightAccessory.Corner.TopTrailing -> frame.right - image.width + badge.offset.x
            MediaFlightAccessory.Corner.BottomLeading -> frame.left + badge.offset.x
        }
        val y = when (badge.corner) {
            MediaFlightAccessory.Corner.TopTrailing -> frame.top + badge.offset.y
            MediaFlightAccessory.Corner.BottomLeading -> frame.bottom - image.height + badge.offset.y
        }
        drawImage(image, topLeft = Offset(x, y), alpha = alpha.coerceIn(0f, 1f))
    }
}

/**
 * The presented viewer: a black backdrop (hidden for documents) and the content with its chrome
 * above it, over the whole window. It takes every touch while up, hides the content below from
 * TalkBack (the host does), dismisses on back, and runs the dark room's status bar.
 */
@Composable
internal fun MediaViewerHost(
    sourceID: String?,
    isMedia: Boolean,
    reducesMotion: Boolean,
    screenName: String,
    onDismissed: () -> Unit,
    flightAccessories: ((String) -> List<MediaFlightAccessory>)? = null,
    content: @Composable (MediaViewerState) -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentDismissed by rememberUpdatedState(onDismissed)
    val state = remember {
        MediaViewerState(sourceID).also { state -> flightAccessories?.let { state.flightAccessories = it } }
    }
    val controller = remember {
        MediaViewerController(state, isMedia, reducesMotion, scope, density) { currentDismissed() }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val slop = LocalViewConfiguration.current.touchSlop

    LaunchedEffect(controller) {
        // Opening an image dismisses the keyboard.
        keyboard?.hide()
        focus.clearFocus(force = true)
        snapshotFlow { controller.bounds }.first { it.width > 0f && it.height > 0f }
        // One frame for the pages to lay out and report where their images rest.
        withFrameNanos { }
        controller.awaitMedia()
        controller.open()
    }
    DisposableEffect(controller) { onDispose { controller.disposed() } }
    // Back previews the dismissal as a short pull down; the commit puts the media away.
    MediaPredictiveBackHandler(
        enabled = !controller.isLeaving,
        onStarted = { controller.backBegan() },
        onProgressed = { progress, fromRightEdge -> controller.backProgressed(progress, fromRightEdge) },
        onCancelled = { controller.backCancelled() },
        onInvoked = { controller.backCommitted() },
    )
    StatusBarEffect(controller)

    val dark = MediaPresenter.isDarkRoom(isMedia)
    val page = !isMedia && !reducesMotion
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                controller.originInWindow = coordinates.positionInWindow()
                val size = coordinates.size
                val rect = Rect(Offset.Zero, Size(size.width.toFloat(), size.height.toFloat()))
                if (controller.bounds != rect) controller.bounds = rect
            }
            .graphicsLayer { alpha = controller.viewAlpha }
            .semantics { paneTitle = screenName }
            .pointerInput(controller) { trackPointer(controller) }
            .pointerInput(controller) { dismissDrag(controller, slop, fromContent = false) }
            .pointerInput(controller) { dismissDrag(controller, slop, fromContent = true) }
            .nestedScroll(controller.documentDismissConnection),
    ) {
        if (!page) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = controller.backdropAlpha }
                    .background(Color.Black),
            )
        }
        val themed: @Composable () -> Unit = {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val placement = controller.layerPlacement()
                        alpha = placement.alpha
                        scaleX = placement.scale
                        scaleY = placement.scale
                        translationX = placement.offset.x
                        translationY = placement.offset.y
                        transformOrigin = TransformOrigin(0f, 0f)
                        val clip = placement.clip
                        if (clip != null) {
                            // The clip in the layer's own (unscaled) pixels.
                            val local = Rect(
                                (clip.left - placement.offset.x) / placement.scale,
                                (clip.top - placement.offset.y) / placement.scale,
                                (clip.right - placement.offset.x) / placement.scale,
                                (clip.bottom - placement.offset.y) / placement.scale,
                            )
                            shape = MediaClipShape(placement.corners.roundRect(local, placement.radius / placement.scale))
                            this.clip = true
                        } else {
                            shape = RectangleShape
                            this.clip = false
                        }
                    }
                    .then(if (page) Modifier.background(MaterialTheme.colorScheme.background) else Modifier)
                    .drawWithContent {
                        drawContent()
                        with(controller) { drawHomeBadges() }
                    },
            ) {
                CompositionLocalProvider(LocalMediaViewer provides state) { content(state) }
            }
        }
        // The hand-off's copy flies under the content's chrome.
        Canvas(Modifier.fillMaxSize()) { controller.flight?.let { drawFlight(it) } }
        if (dark) {
            val appearance = rememberAppAppearance()
            OpenCodeTheme(theme = appearance.theme, appearance = AppearanceColorScheme.Dark, content = themed)
        } else {
            OpenCodeAppTheme(content = themed)
        }
    }
}

/** A rounded rectangle anywhere in the layer, for a clip smaller than the layer itself. */
private class MediaClipShape(private val rect: RoundRect) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(rect)

    override fun equals(other: Any?) = other is MediaClipShape && other.rect == rect

    override fun hashCode() = rect.hashCode()
}

/** The hand-off's copy: the drawn image, aspect-filled into its frame with its strokes, and the slot's badges. */
private fun DrawScope.drawFlight(flight: Flight) {
    val frame = flight.frame
    if (frame.width <= 0f || frame.height <= 0f || flight.alpha <= 0f) return
    val bitmap = flight.bitmap
    withTransform({ scale(flight.scale, flight.scale, pivot = frame.center) }) {
        val path = Path().apply { addRoundRect(flight.corners.roundRect(frame, flight.radius)) }
        clipPath(path) {
            val imageW = bitmap.width.toFloat()
            val imageH = bitmap.height.toFloat()
            val scale = max(frame.width / imageW, frame.height / imageH)
            val srcW = frame.width / scale
            val srcH = frame.height / scale
            val srcOffset = IntOffset(((imageW - srcW) / 2).roundToInt(), ((imageH - srcH) / 2).roundToInt())
            drawImage(
                image = bitmap,
                srcOffset = srcOffset,
                srcSize = IntSize(srcW.roundToInt().coerceAtMost(bitmap.width), srcH.roundToInt().coerceAtMost(bitmap.height)),
                dstOffset = IntOffset(frame.left.roundToInt(), frame.top.roundToInt()),
                dstSize = IntSize(frame.width.roundToInt(), frame.height.roundToInt()),
                alpha = flight.alpha,
                filterQuality = FilterQuality.High,
            )
            val drawing = flight.drawing
            if (drawing != null) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val saved = native.saveLayerAlpha(null, (flight.alpha * 255).roundToInt())
                    native.translate(frame.left - (imageW - srcW) / 2 * scale, frame.top - (imageH - srcH) / 2 * scale)
                    native.scale(scale, scale)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val width = min(imageW, imageH) * 0.01f
                    for (stroke in drawing.strokes) {
                        stroke.draw(native, width, paint)
                    }
                    native.restoreToCount(saved)
                }
            }
        }
    }
    if (flight.badgeAlpha > 0f) drawBadges(flight.badges, frame, flight.badgeAlpha)
}

/**
 * The dismiss pan. For media ([fromContent] false) it looks at every touch before the content (the
 * initial pass), and claims a drag heading down more than sideways once it passes the touch slop,
 * unless the image is zoomed. Claimed drags are consumed, so the pager and the zoom never see them:
 * pages turn only once a drag has shown it isn't a dismissal. For documents ([fromContent] true) it
 * looks after the content (the main pass) and claims only a drag nothing in it took: a scroll view
 * (scrolled or not) consumes its drags, and one at its top hands its overscroll over through
 * [MediaViewerController.documentDismissConnection].
 */
private suspend fun PointerInputScope.dismissDrag(controller: MediaViewerController, slop: Float, fromContent: Boolean) {
    val pass = if (fromContent) PointerEventPass.Main else PointerEventPass.Initial
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        if (controller.isLeaving) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var total = Offset.Zero
        var anchor = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(pass)
            if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed || change.isConsumed) return@awaitEachGesture
            tracker.addPosition(change.uptimeMillis, change.position)
            total += change.positionChange()
            if (total.getDistance() > slop) {
                if (!controller.shouldBegin(total, fromContent)) return@awaitEachGesture
                change.consume()
                anchor = change.position
                break
            }
        }
        controller.dragBegan(anchor)
        var translation = Offset.Zero
        var ended = false
        try {
            while (true) {
                // Claimed: consumed before the content sees it, so nothing under it scrolls along.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    val v = tracker.calculateVelocity()
                    ended = true
                    controller.dragEnded(translation, v)
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                event.changes.forEach { it.consume() }
                translation = change.position - anchor
                controller.dragChanged(translation)
            }
        } finally {
            if (!ended) controller.dragEnded(translation, Velocity.Zero)
        }
    }
}

/** Records where the finger is (without consuming), for a dismissal a scroll view hands over. */
private suspend fun PointerInputScope.trackPointer(controller: MediaViewerController) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.pressed }?.let { controller.lastPointer = it.position }
        }
    }
}

/**
 * The dark room's status bar: light icons over the black backdrop, the session's own once it shows
 * through, hidden while the photo viewer's controls are hidden. When the viewer goes the bar
 * follows the app's current appearance (as [ai.opencode.core.ui.OpenCodeAppTheme] sets it), not the
 * one it opened over.
 */
@Composable
private fun StatusBarEffect(controller: MediaViewerController) {
    val activity = LocalContext.current.findActivity() ?: return
    val window = activity.window
    val insets = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    val appLight = !rememberAppAppearance().colorScheme.resolveIsDark(isSystemInDarkTheme())
    val currentAppLight by rememberUpdatedState(appLight)
    val light = controller.statusBarLight
    val hidden = controller.statusBarHidden
    LaunchedEffect(light, hidden, appLight) {
        insets.isAppearanceLightStatusBars = if (light) false else appLight
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) insets.hide(WindowInsetsCompat.Type.systemBars()) else insets.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window) {
        onDispose {
            insets.isAppearanceLightStatusBars = currentAppLight
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * Back dismisses the viewer. The platform's back dispatcher at overlay priority, so it wins over
 * the app's own handlers while the viewer is up.
 */
@Composable
internal fun MediaBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val callback = OnBackInvokedCallback { currentOnBack() }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

/**
 * [MediaBackHandler] that also hears the back gesture's progress (Android only), so the viewer
 * previews its dismissal while the swipe is held. A button or TalkBack back calls only
 * [onInvoked].
 */
@Composable
internal fun MediaPredictiveBackHandler(
    enabled: Boolean,
    onStarted: () -> Unit,
    onProgressed: (progress: Float, fromRightEdge: Boolean) -> Unit,
    onCancelled: () -> Unit,
    onInvoked: () -> Unit,
) {
    val activity = LocalContext.current.findActivity() ?: return
    val currentOnStarted by rememberUpdatedState(onStarted)
    val currentOnProgressed by rememberUpdatedState(onProgressed)
    val currentOnCancelled by rememberUpdatedState(onCancelled)
    val currentOnInvoked by rememberUpdatedState(onInvoked)
    DisposableEffect(activity, enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        // Android 13 reports no gesture progress to apps, so back there only dismisses.
        val callback = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            OnBackInvokedCallback { currentOnInvoked() }
        } else object : OnBackAnimationCallback {
            override fun onBackStarted(backEvent: BackEvent) {
                currentOnStarted()
            }

            override fun onBackProgressed(backEvent: BackEvent) {
                currentOnProgressed(backEvent.progress, backEvent.swipeEdge == BackEvent.EDGE_RIGHT)
            }

            override fun onBackCancelled() {
                currentOnCancelled()
            }

            override fun onBackInvoked() {
                currentOnInvoked()
            }
        }
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
