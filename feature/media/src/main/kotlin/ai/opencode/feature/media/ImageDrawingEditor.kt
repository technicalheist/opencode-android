package ai.opencode.feature.media

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.collectState
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.rememberAppAppearance
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The opener's prompt (iOS `ImageDrawingFeature.Add`): takes the drawn copy on the main thread and
 * answers the media ID of its new thumbnail, or null once it no longer takes attachments.
 */
typealias ImageDrawingAdd = (OpenCodeOutgoingAttachment) -> String?

/**
 * Draw on Image (iOS `ImageDrawingFeature`): marks up a copy of one image and hands it to the
 * prompt that opened the viewer ([add]). It never sends; the prompt decides whether the copy joins the original or replaces it.
 *
 * iOS dismisses itself through `@Dependency(\.dismiss)`; here it asks its presenter with
 * [Action.DelegateAction.Dismiss], and dropping the editor cancels its export.
 */
class ImageDrawingFeature(private val add: ImageDrawingAdd) : Reducer<ImageDrawingFeature.State, ImageDrawingFeature.Action> {
    /** [image] compares by identity (a `Bitmap` has no value equality). */
    data class State(
        val image: Bitmap,
        val drawing: ImageDrawing = ImageDrawing(),
        val isAdding: Boolean = false,
        val isConfirmingDiscard: Boolean = false,
        val failure: Failure? = null,
    )

    enum class Failure {
        /** The drawn copy couldn't be made. */
        Flatten,

        /** The prompt has gone (another session) or is answering a request. */
        Prompt;

        val message: String
            get() = when (this) {
                Flatten -> localized(R.string.media_drawing_flatten_failed)
                Prompt -> localized(R.string.media_drawing_prompt_unavailable)
            }
    }

    sealed interface Action {
        data class InkSelected(val ink: ImageDrawing.Ink) : Action

        /** A stroke the finger or stylus lifted from; a cancelled touch never gets here. */
        data class StrokeFinished(val stroke: ImageDrawing.Stroke) : Action
        data object UndoTapped : Action
        data object CancelTapped : Action
        data object DiscardConfirmed : Action
        data class IsConfirmingDiscardChanged(val isConfirmingDiscard: Boolean) : Action
        data object AddTapped : Action

        /** Null once the copy is in the prompt. */
        data class AddFinished(val failure: Failure?, val target: String? = null) : Action
        data object FailureDismissed : Action
        data class Delegate(val action: DelegateAction) : Action

        sealed interface DelegateAction {
            /** The drawn copy is in the opener's prompt, shown by the thumbnail with media ID [target]. */
            data class Added(val target: String) : DelegateAction

            /** Put the editor away. */
            data object Dismiss : DelegateAction
        }
    }

    private enum class CancelID { Add }

    private val dismiss: Effect<Action> = Effect.send(Action.Delegate(Action.DelegateAction.Dismiss))

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        is Action.InkSelected -> Reduced(state.copy(drawing = state.drawing.copy(ink = action.ink)))

        is Action.StrokeFinished ->
            if (state.isAdding) {
                Reduced(state)
            } else {
                Reduced(state.copy(drawing = state.drawing.copy(strokes = state.drawing.strokes + action.stroke)))
            }

        Action.UndoTapped -> if (state.isAdding) Reduced(state) else Reduced(state.copy(drawing = state.drawing.undo()))

        Action.CancelTapped -> when {
            state.isAdding -> Reduced(state)
            !state.drawing.changed -> Reduced(state, dismiss)
            else -> Reduced(state.copy(isConfirmingDiscard = true))
        }

        Action.DiscardConfirmed -> Reduced(state.copy(isConfirmingDiscard = false), dismiss)

        is Action.IsConfirmingDiscardChanged -> Reduced(state.copy(isConfirmingDiscard = action.isConfirmingDiscard))

        Action.AddTapped -> {
            if (!state.drawing.changed || state.isAdding) {
                Reduced(state)
            } else {
                val imageDrawing = dependency(ImageDrawingClient)
                val id = dependency(UuidClient).next().toString()
                val drawing = state.drawing
                val image = state.image
                Reduced(
                    state.copy(isAdding = true),
                    Effect.run<Action>(onError = { _, send -> send(Action.AddFinished(Failure.Flatten)) }) { send ->
                        val attachment = imageDrawing.flatten(drawing, image, id)
                        if (attachment == null) {
                            send(Action.AddFinished(Failure.Flatten))
                            return@run
                        }
                        // Checked on the main thread with the add, so an editor put away
                        // meanwhile adds nothing.
                        currentCoroutineContext().ensureActive()
                        val target = add(attachment)
                        send(if (target != null) Action.AddFinished(null, target) else Action.AddFinished(Failure.Prompt))
                    }.cancellable(CancelID.Add, cancelInFlight = true),
                )
            }
        }

        is Action.AddFinished -> {
            val failure = action.failure
            val target = action.target
            if (failure == null && target != null) {
                Reduced(state, Effect.merge(Effect.send(Action.Delegate(Action.DelegateAction.Added(target))), dismiss))
            } else {
                Reduced(state.copy(isAdding = false, failure = failure ?: Failure.Prompt))
            }
        }

        Action.FailureDismissed -> Reduced(state.copy(failure = null))

        is Action.Delegate -> Reduced(state)
    }
}

/**
 * The editor (iOS `ImageDrawingEditor`): its own full-screen window, so the gallery's paging,
 * dismiss gestures and Back stay out of drawing. Back is Cancel. One finger draws; two pinch
 * and pan.
 *
 * @param zoomedFrame where the viewer has the image zoomed, in window coordinates, so the
 *   editor opens on the same part of it; null opens it fitted.
 * @param onImageFrame where the image is drawn, in window coordinates.
 */
@Composable
fun ImageDrawingEditor(
    store: StoreOf<ImageDrawingFeature.State, ImageDrawingFeature.Action>,
    zoomedFrame: Rect? = null,
    onImageFrame: (Rect) -> Unit = {},
) {
    val state by store.collectState()
    val enabled = !state.isAdding
    var inkCanvas by remember { mutableStateOf<InkCanvas?>(null) }
    val reduceMotion = rememberReduceMotion()
    val controlsAlpha by animateFloatAsState(
        if (state.isAdding) 0f else 1f,
        MotionRole.MicroOut.animation(if (state.isAdding) MotionPhase.Exit else MotionPhase.Enter, reduceMotion),
        label = "drawingControls",
    )
    Dialog(
        onDismissRequest = { store.send(ImageDrawingFeature.Action.CancelTapped) },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(state.isAdding, reduceMotion) {
            // The viewer takes the added image from under the editor, so the window goes at once.
            if (state.isAdding && !reduceMotion) window?.setWindowAnimations(0)
        }
        Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
            OpenCodeTheme(theme = rememberAppAppearance().theme, appearance = AppearanceColorScheme.Dark) {
                OpenCodeTopBar(
                    title = { Text(stringResource(R.string.media_drawing_draw_on_image_description), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.graphicsLayer { alpha = controlsAlpha },
                    navigation = {
                        OpenCodeIconButton(
                            symbol = SFSymbol.Xmark,
                            contentDescription = stringResource(CoreR.string.common_cancel),
                            onClick = { store.send(ImageDrawingFeature.Action.CancelTapped) },
                            modifier = Modifier.testTag("image-drawing-cancel"),
                            style = OpenCodeIconButtonStyle.Standard,
                            enabled = enabled,
                        )
                    },
                    actions = {
                        OpenCodeIconButton(
                            symbol = SFSymbol.ArrowUturnBackward,
                            contentDescription = stringResource(R.string.media_drawing_undo_description),
                            onClick = { store.send(ImageDrawingFeature.Action.UndoTapped) },
                            modifier = Modifier.testTag("image-drawing-undo"),
                            style = OpenCodeIconButtonStyle.Standard,
                            enabled = enabled && state.drawing.changed,
                        )
                        val addDescription = stringResource(R.string.media_drawing_add_to_prompt_description)
                        TextButton(
                            onClick = { store.send(ImageDrawingFeature.Action.AddTapped) },
                            modifier = Modifier.testTag("image-drawing-add").semantics { contentDescription = addDescription },
                            enabled = enabled && state.drawing.changed,
                        ) {
                            Text(stringResource(CoreR.string.common_add), maxLines = 1)
                        }
                    },
                    containerColor = Color.Black,
                    hairline = false,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                )
            }
            AndroidView(
                factory = { context ->
                    val canvas = InkCanvas(context)
                    canvas.opening = zoomedFrame
                    inkCanvas = canvas
                    canvas
                },
                update = { canvas ->
                    canvas.image = state.image
                    canvas.drawing = state.drawing
                    canvas.isEnabled = enabled
                    canvas.commit = { store.send(ImageDrawingFeature.Action.StrokeFinished(it)) }
                    canvas.onFrame = onImageFrame
                    canvas.invalidate()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clipToBounds()
                    .onGloballyPositioned { coordinates -> inkCanvas?.positioned(coordinates.positionInWindow()) }
                    .testTag("image-drawing-canvas"),
            )
            Surface(
                shape = OpenCodeShape.control,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp).graphicsLayer { alpha = controlsAlpha },
            ) {
                Row(Modifier.padding(horizontal = 6.dp)) {
                    ImageDrawing.Ink.entries.forEach { ink ->
                        val selected = ink == state.drawing.ink
                        val inkName = inkName(ink)
                        Box(
                            Modifier
                                .size(48.dp)
                                .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                                    store.send(ImageDrawingFeature.Action.InkSelected(ink))
                                }
                                .semantics { contentDescription = inkName },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Box(Modifier.size(30.dp).border(2.dp, Color.White, CircleShape))
                            Box(Modifier.size(24.dp).background(Color(ink.argb), CircleShape).border(1.dp, Color.Gray, CircleShape))
                        }
                    }
                }
            }
        }
        if (state.isConfirmingDiscard) {
            OpenCodeAlertDialog(
                onDismissRequest = { store.send(ImageDrawingFeature.Action.IsConfirmingDiscardChanged(false)) },
                title = { Text(stringResource(R.string.media_drawing_discard_title)) },
                confirmButton = {
                    TextButton(onClick = { store.send(ImageDrawingFeature.Action.DiscardConfirmed) }) {
                        Text(stringResource(R.string.media_drawing_discard_confirm), color = OpenCodeTheme.colors.destructive)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { store.send(ImageDrawingFeature.Action.IsConfirmingDiscardChanged(false)) }) {
                        Text(stringResource(R.string.media_drawing_keep))
                    }
                },
            )
        }
        state.failure?.let { failure ->
            OpenCodeAlertDialog(
                onDismissRequest = { store.send(ImageDrawingFeature.Action.FailureDismissed) },
                title = { Text(stringResource(R.string.media_drawing_add_failed_title)) },
                text = { Text(failure.message) },
                confirmButton = {
                    TextButton(onClick = { store.send(ImageDrawingFeature.Action.FailureDismissed) }) { Text(stringResource(CoreR.string.common_ok)) }
                },
            )
        }
    }
}

@Composable
private fun inkName(ink: ImageDrawing.Ink): String = stringResource(
    when (ink) {
        ImageDrawing.Ink.White -> R.string.media_drawing_ink_white
        ImageDrawing.Ink.Black -> R.string.media_drawing_ink_black
        ImageDrawing.Ink.Red -> R.string.media_drawing_ink_red
        ImageDrawing.Ink.Yellow -> R.string.media_drawing_ink_yellow
        ImageDrawing.Ink.Green -> R.string.media_drawing_ink_green
        ImageDrawing.Ink.Blue -> R.string.media_drawing_ink_blue
    },
)

/**
 * Owns touch sampling (historical points included), the live stroke and the zoom; only a finished
 * stroke reaches the reducer ([commit]). A cancelled touch never does (iOS `InkCanvas`). A second
 * finger drops the live stroke and pinches and pans instead, until every finger lifts.
 */
private class InkCanvas(context: Context) : View(context) {
    var image: Bitmap? = null
        set(value) {
            if (field !== value) {
                field = value
                fit()
            }
        }
    var drawing = ImageDrawing()
    var commit: (ImageDrawing.Stroke) -> Unit = {}

    /** Where the viewer has the image zoomed, in window coordinates; taken once the canvas has its place. */
    var opening: Rect? = null

    /** Where the image is drawn, in window coordinates. */
    var onFrame: (Rect) -> Unit = {}
    private var live: MutableList<Offset>? = null
    private var liveInk = ImageDrawing.Ink.Red
    private var pointer = -1
    private val frame = RectF()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var windowOrigin = Offset.Zero

    /** Screen pixels per image pixel when the whole image fits. */
    private var fitScale = 1f

    /** 1 is fitted. */
    private var zoom = 1f
    private var maximumZoom = MediaZoomGeometry.smallestMaximumScale
    private val scale: Float get() = fitScale * zoom
    private var isZooming = false
    private var pinchSpan = 0f
    private var pinchCentre = Offset.Zero

    init {
        contentDescription = localized(R.string.media_drawing_canvas_description)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    fun positioned(origin: Offset) {
        windowOrigin = origin
        val opening = opening
        val image = image
        if (opening != null && image != null) {
            this.opening = null
            val shown = opening.width / image.width
            val centre = Offset(width / 2f, height / 2f)
            val point = Offset(
                (origin.x + centre.x - opening.left) / shown,
                (origin.y + centre.y - opening.top) / shown,
            )
            show(shown / fitScale, point, centre)
            invalidate()
        }
        reportFrame()
    }

    /** Shows the whole image. */
    private fun fit() {
        val image = image ?: return
        if (width == 0 || height == 0) return
        fitScale = minOf(width.toFloat() / image.width, height.toFloat() / image.height)
        val pixels = Size(image.width.toFloat(), image.height.toFloat())
        maximumZoom = MediaZoomGeometry.maximumScale(pixels, Size(image.width * fitScale, image.height * fitScale), 1f)
        live = null
        show(1f, Offset.Zero, Offset.Zero)
    }

    /** Draws the image at [zoom] with [point] (image pixels) at [at] on the canvas, kept flush with the edges. */
    private fun show(zoom: Float, point: Offset, at: Offset) {
        val image = image ?: return
        this.zoom = zoom.coerceIn(1f, maximumZoom)
        val contentWidth = image.width * scale
        val contentHeight = image.height * scale
        val left = edge(at.x - point.x * scale, contentWidth, width.toFloat())
        val top = edge(at.y - point.y * scale, contentHeight, height.toFloat())
        frame.set(left, top, left + contentWidth, top + contentHeight)
        reportFrame()
    }

    /** Content smaller than its room is centred; larger content covers the room. */
    private fun edge(start: Float, content: Float, room: Float): Float =
        if (content <= room) (room - content) / 2 else start.coerceIn(room - content, 0f)

    private fun reportFrame() {
        onFrame(Rect(frame.left + windowOrigin.x, frame.top + windowOrigin.y, frame.right + windowOrigin.x, frame.bottom + windowOrigin.y))
    }

    private fun imagePoint(x: Float, y: Float) = Offset((x - frame.left) / scale, (y - frame.top) / scale)

    override fun onDraw(canvas: Canvas) {
        val image = image ?: return
        canvas.save()
        canvas.clipRect(frame)
        canvas.translate(frame.left, frame.top)
        canvas.scale(scale, scale)
        canvas.drawBitmap(image, 0f, 0f, photoPaint)
        val pen = minOf(image.width, image.height) * 0.01f
        drawing.strokes.forEach { it.draw(canvas, pen, inkPaint) }
        live?.let { ImageDrawing.Stroke(liveInk, it).draw(canvas, pen, inkPaint) }
        canvas.restore()
    }

    @SuppressLint("ClickableViewAccessibility") // A direct manipulation surface, not a button.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || image == null) {
            live = null
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isZooming = false
                pointer = event.getPointerId(0)
                liveInk = drawing.ink
                live = if (frame.contains(event.x, event.y)) mutableListOf(imagePoint(event.x, event.y)) else null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                live = null
                isZooming = true
                pinch(event)
            }
            MotionEvent.ACTION_CANCEL -> live = null
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                if (isZooming) pinch(event) else draw(event)
        }
        invalidate()
        return true
    }

    private fun draw(event: MotionEvent) {
        val index = event.findPointerIndex(pointer)
        val points = live
        if (index < 0 || points == null) return
        fun append(p: Offset) { if (points.last() != p) points += p }
        for (i in 0 until event.historySize) append(imagePoint(event.getHistoricalX(index, i), event.getHistoricalY(index, i)))
        append(imagePoint(event.getX(index), event.getY(index)))
        if (event.actionMasked != MotionEvent.ACTION_MOVE && event.actionIndex == index) {
            if (event.flags and MotionEvent.FLAG_CANCELED == 0) commit(ImageDrawing.Stroke(liveInk, points.toList()))
            live = null
        }
    }

    /** Zooms about the first two fingers and pans with them. */
    private fun pinch(event: MotionEvent) {
        if (event.pointerCount < 2 || event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            // The fingers changed, so the next move measures them afresh.
            pinchSpan = 0f
            return
        }
        val a = Offset(event.getX(0), event.getY(0))
        val b = Offset(event.getX(1), event.getY(1))
        val centre = (a + b) / 2f
        val span = (a - b).getDistance()
        if (event.actionMasked == MotionEvent.ACTION_MOVE && pinchSpan > 0f) {
            show(zoom * span / pinchSpan, imagePoint(pinchCentre.x, pinchCentre.y), centre)
        }
        pinchSpan = span
        pinchCentre = centre
    }
}
