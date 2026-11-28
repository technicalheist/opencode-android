package ai.opencode.feature.composer

import ai.opencode.core.diagnostics.PerfFrameLatency
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.ui.BackSwipeRules
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.drawerKeyboardField
import ai.opencode.server.model.PastedText
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.view.WindowInsetsController
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.placeCursorAtEnd
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How long a recreated window's inherited keys stay up over the restored field before they count as its own. */
private const val INHERITED_KEYS_SETTLE_MILLIS = 1_000L

/** How long a recreated field waits for its draft to put its saved selection back. */
private const val SELECTION_RESTORE_MILLIS = 1_000L

/** How long keys must stay hidden before they count as having left the focused field. */
private const val KEYS_LEFT_SETTLE_MILLIS = 250L

/**
 * The field's selection, saved across an activity recreation. Restored only in the same
 * process and for a draft of the same length; after process death the caret starts at the end.
 */
internal class ComposerSavedSelection(private val saved: List<Int>? = null) {
    // Until the field reports its own, a save keeps what was restored (switches in a row).
    private var length = saved?.get(2) ?: 0
    private var selection = saved?.let { TextRange(it[0], it[1]) } ?: TextRange.Zero

    fun update(length: Int, selection: TextRange) {
        this.length = length
        this.selection = selection
    }

    fun restored(text: String): TextRange? {
        val (start, end, savedLength) = saved ?: return null
        if (savedLength != text.length || maxOf(start, end) > text.length) return null
        return TextRange(start, end)
    }

    companion object {
        private val process: String get() = "${Process.myPid()}:${Process.getStartUptimeMillis()}"

        val Saver: Saver<ComposerSavedSelection, ArrayList<String>> = Saver(
            save = { arrayListOf(process, it.selection.start.toString(), it.selection.end.toString(), it.length.toString()) },
            restore = { saved ->
                val values = saved.takeIf { it.size == 4 && it[0] == process }?.drop(1)?.mapNotNull { it.toIntOrNull() }
                ComposerSavedSelection(values?.takeIf { it.size == 3 })
            },
        )
    }
}

/**
 * The composer tunables the text input reads (iOS `ComposerTunables`, `Session/ComposerViews.swift`):
 * their baked defaults. The session area's composer tunables keep the same names.
 */
object ComposerTextInputTunables {
    /**
     * `composer.spacing.expandedTopInset`: the expanded composer's resting top inset. It
     * scrolls away with the text, rather than clipping text below a permanent empty band.
     */
    val expandedTopInset: Dp = 16.dp

    /** `composer.controls.swipeUpDistance`: swipe-up focus distance (dp). */
    const val SWIPE_UP_DISTANCE = 18f

    /** `composer.controls.swipeUpVelocity`: swipe-up flick velocity (dp/s). */
    const val SWIPE_UP_VELOCITY = 260f

    /** `composer.lines.grow`: a typed line's growth or shrink (0.28 s, no bounce). */
    val lineGrowth: MotionSpring = MotionSpring(duration = 0.28, bounce = 0.0)
}

/**
 * Rules for a downward swipe on the focused composer dismissing the keyboard, and an upward
 * swipe on the unfocused composer pulling it up (iOS `ComposerSwipeDown`). Distances in dp
 * (iOS pt), velocities in dp/s; `translation.y > 0` is downward.
 */
object ComposerSwipeDown {
    const val MINIMUM_DISTANCE = 18f
    const val PREDICTED_DISTANCE = 60f
    const val MINIMUM_FLING_DISTANCE = 10f
    const val VERTICAL_RATIO = 1.3f

    fun shouldDismissKeyboard(
        translation: Offset,
        predictedEndTranslation: Offset = Offset.Zero,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingUp: Boolean = false,
    ): Boolean {
        if (!isFocused || isHoldingMic || isScrollingUp) return false
        val dy = translation.y
        val dx = translation.x
        if (dy <= 0 || abs(dy) <= abs(dx) * VERTICAL_RATIO) return false
        return dy >= MINIMUM_DISTANCE || (dy >= MINIMUM_FLING_DISTANCE && predictedEndTranslation.y >= PREDICTED_DISTANCE)
    }

    fun shouldFocusKeyboard(
        translation: Offset,
        predictedEndTranslation: Offset = Offset.Zero,
        velocityY: Float? = null,
        isFocused: Boolean,
        isHoldingMic: Boolean = false,
        isScrollingDown: Boolean = false,
        minimumDistance: Float = ComposerTextInputTunables.SWIPE_UP_DISTANCE,
        minimumFlickVelocity: Float = ComposerTextInputTunables.SWIPE_UP_VELOCITY,
    ): Boolean {
        if (isFocused || isHoldingMic || isScrollingDown) return false
        val dy = translation.y
        val dx = translation.x
        if (dy >= 0 || abs(dy) <= abs(dx) * VERTICAL_RATIO) return false
        val distance = -dy
        if (distance >= minimumDistance) return true
        if (distance < MINIMUM_FLING_DISTANCE) return false
        if (velocityY != null) return -velocityY >= minimumFlickVelocity
        val predictedExtra = max(0f, -(predictedEndTranslation.y - dy))
        return predictedExtra >= minimumFlickVelocity * 0.15f || -predictedEndTranslation.y >= max(PREDICTED_DISTANCE, minimumDistance)
    }

    /**
     * Whether a drag on the text belongs to the text field, so no swipe acts on it: it started
     * on or near the selection (its handles sit at its edges), the finger rested before moving
     * (a long press selecting), or one of the field's own gestures took it (a selection drag).
     */
    fun textOwnsDrag(startsOnSelection: Boolean, heldFor: Double, holdBegan: Boolean, textGestureBegan: Boolean): Boolean =
        startsOnSelection || textGestureBegan || BackSwipeRules.yieldsToHold(heldFor, holdBegan)

    /**
     * Whether [point] is on or near one of a selection's rects; [reach] in the same units
     * (iOS `BackSwipeRules.selectionHandleReach`, 24 dp).
     */
    fun selectionClaims(point: Offset, selectionRects: List<Rect>, reach: Float = BackSwipeRules.selectionHandleReach.value): Boolean =
        selectionRects.any { rect -> rect.isFinite && rect.inflate(reach).contains(point) }

    /**
     * iOS `ImagePasteTextView.selectionClaims`: only a focused field's non-empty [selection]
     * claims a touch. An unfocused field shows no selection, and a caret isn't one; its rects
     * are only measured when it could claim.
     */
    fun selectionClaims(
        point: Offset,
        isFocused: Boolean,
        selection: TextRange,
        selectionRects: () -> List<Rect>,
        reach: Float = BackSwipeRules.selectionHandleReach.value,
    ): Boolean = isFocused && !selection.collapsed && selectionClaims(point, selectionRects(), reach)
}

/**
 * Whether a drag over the composer belongs to a control inside it (the level, +, a held mic,
 * an open panel), iOS `ComposerSwipeOwnership`. Decided once, as the drag first reports, and
 * kept through its last callback: the control's own gesture can end first, and the drag must
 * not pass to the keyboard's dismissal. A plain object, owned by the composer view.
 */
class ComposerSwipeOwnership {
    private var start: Offset? = null
    private var focusedStart: Offset? = null
    private var yields = false
    private var focusedAtStart = false

    /**
     * [yieldsNow] is read only for a drag not seen before. Once the text field claims the drag
     * ([textClaims], a selection's handle or a long press), it stays yielded through its last
     * callback.
     */
    fun yields(startLocation: Offset, isFocused: Boolean = false, textClaims: Boolean = false, yieldsNow: () -> Boolean): Boolean {
        if (start != startLocation) {
            start = startLocation
            focusedStart = startLocation
            focusedAtStart = isFocused
            yields = yieldsNow()
        }
        if (textClaims) yields = true
        return yields
    }

    fun beganFocused(startLocation: Offset, isFocused: Boolean): Boolean {
        if (focusedStart != startLocation) {
            focusedStart = startLocation
            focusedAtStart = isFocused
        }
        return focusedAtStart
    }

    /** The drag ended: the next one decides afresh. */
    fun ended() {
        start = null
        focusedStart = null
        yields = false
        focusedAtStart = false
    }
}

/**
 * Per-field state the iOS `ImagePasteTextView` kept on itself: scroll pinning and swipe flags,
 * and the text sync of iOS `ComposerTextInput.updateUIView` ([syncText], [publish]).
 */
class ComposerTextInputController {
    /** The last text the parent passed (or we reported): a different one is a programmatic change. */
    var externalText: String? = null

    /**
     * iOS `isPublishingText`: set while the field reports the reader's edit. The field owns the
     * text in that interval, so a sync that runs inside it never writes an older draft back.
     */
    var isPublishingText = false
        private set

    /** A send asked for the parent's text while the field was publishing (Return): taken once it's done. */
    var syncsAfterPublish = false

    /** iOS `textFocusRequest`: the parent's last focus request; a new one places the caret at the end. */
    var textFocusRequest = 0
        private set

    /**
     * Set after a programmatic text change: layouts keep the end in view while the composer
     * grows to its cap and the keyboard rises, until the reader drags, moves the caret, or types.
     */
    var pinsToEnd = false

    /** Set on a user edit so the layout pass that enables scrolling when the draft first crosses the cap still brings the caret into view. */
    var followsCaret = false

    /** A non-send edit (Shift+Return, a paste) is pending: the newline it adds isn't Return. */
    var nonSendEditFrom: String? = null

    var isScrollingUp = false
    var isScrollingDown = false
    var isSuppressingFocusAfterSwipe = false
    var isFocusingFromSwipe = false
    var swipeBeganFocused = false

    var layoutTick by mutableIntStateOf(0)
    var layout: TextLayoutResult? = null

    /** iOS `lastTap`: the last quick finger tap on the text's last visible line ([ComposerDoubleTap]). */
    var lastTap: ComposerTextTap? = null

    /**
     * iOS `noteSelectionChange`: keeps [lastTap] only while the selection is still the caret that
     * tap placed; typing, removing an autocomplete chip, or any other move makes it stale.
     */
    fun noteSelectionChange(text: String, selection: TextRange) {
        val tap = lastTap ?: return
        val layout = layout
        if (layout == null || layout.layoutInput.text.length != text.length) {
            lastTap = null
            return
        }
        val tapped = layout.getOffsetForPosition(tap.point)
        if (!ComposerDoubleTap.keepsTap(tap, text, selection, tapped, ComposerDoubleTap.wordAt(layout, tapped))) lastTap = null
    }

    /** iOS `noteTextChange`. */
    fun noteTextChange() {
        lastTap = null
    }

    /**
     * iOS `ComposerTextInput.updateUIView`'s text sync: brings the parent's draft into [state].
     * [currentText] reads the parent's draft now, not the [text] an earlier composition captured,
     * so a recomposition that lands between the reader's edit and the parent taking
     * it can't write the older draft back. A sync while the field publishes an edit does nothing.
     *
     * The caret: a new [focusRequest] (Quote, a restored draft) puts it at the end; otherwise
     * Composer Autocomplete's caret wins (null means the end); otherwise a focused
     * field keeps its selection, clamped to the new text; otherwise the caret goes to the end.
     */
    fun syncText(
        state: TextFieldState,
        text: String,
        currentText: (() -> String)?,
        focusRequest: Int,
        hasFocus: Boolean,
        bridge: ComposerAutocompleteTextBridge?,
    ) {
        if (isPublishingText) return
        val draft = currentText?.invoke() ?: text
        val requestedEnd = textFocusRequest != focusRequest
        textFocusRequest = focusRequest
        if (externalText == null) externalText = draft
        if (draft == externalText) return
        externalText = draft
        if (state.text.toString() == draft) return
        val previous = state.selection
        val preserveSelection = hasFocus && !requestedEnd
        val caret = bridge?.caretUTF16Offset
        val usesAutocompleteCaret = bridge != null && !requestedEnd && bridge.isEnabled &&
            (caret == null || caret in 0..draft.length)
        bridge?.beginProgrammaticTextUpdate()
        try {
            // iOS `finishCompositionIfNeeded`: an open composition (an IME's composing
            // region, voice typing) must finish before the text changes under it (a send's clear),
            // or it lands in a stale range. A programmatic `edit` commits the composing region where
            // it is and restarts the IME's input, so the words it held don't come back.
            state.edit {
                replace(0, length, draft)
                if (usesAutocompleteCaret) {
                    // Autocomplete normalizes an intentional end position to null.
                    selection = TextRange(caret ?: draft.length)
                } else if (preserveSelection) {
                    val start = min(previous.start, draft.length)
                    val end = min(previous.end, draft.length)
                    selection = TextRange(start, end)
                } else {
                    placeCursorAtEnd()
                }
            }
            if (usesAutocompleteCaret) {
                bridge.syncLastSentCaretOffset()
                pinsToEnd = (caret ?: draft.length) == draft.length
            } else {
                pinsToEnd = !preserveSelection
            }
        } finally {
            bridge?.endProgrammaticTextUpdate()
        }
    }

    /**
     * iOS `textViewDidChange`'s write-back: the reader's edit goes to [onTextChange] with
     * [isPublishingText] set, so a sync it sets off can't undo it.
     */
    fun publish(newText: String, onTextChange: (String) -> Unit) {
        isPublishingText = true
        try {
            externalText = newText
            onTextChange(newText)
        } finally {
            isPublishingText = false
        }
    }

    /** iOS `ComposerSwipePanRecognizer.textOwnership` for the drag in progress. */
    var isSwipeYieldedToText = false

    /**
     * iOS `ComposerSwipePanRecognizer`'s touch: nil ownership until decided (the finger first
     * moves the decision distance, or the capsule asks first), then for good.
     */
    var swipeTextOwnership: Boolean? = null
    var swipeDownUptime: Long? = null
    var swipeLatestUptime = 0L
    var swipeStartsOnSelection = false
    var swipeSelectionAtDown: TextRange? = null

    /** The coordinates the field's swipe handler reads touches in (its viewport). */
    var swipeCoordinates: LayoutCoordinates? = null

    /**
     * iOS `yieldsToText()`: whether the text owns the touch; asked before the field has seen the
     * move (the capsule's handler, on the same event), it decides from the hold so far.
     */
    fun yieldsToText(holdBegan: Boolean, textGestureBegan: Boolean): Boolean {
        swipeTextOwnership?.let { return it }
        val down = swipeDownUptime ?: return false
        val owns = ComposerSwipeDown.textOwnsDrag(
            startsOnSelection = swipeStartsOnSelection,
            heldFor = max(0.0, (swipeLatestUptime - down) / 1000.0),
            holdBegan = holdBegan,
            textGestureBegan = textGestureBegan,
        )
        swipeTextOwnership = owns
        isSwipeYieldedToText = owns
        return owns
    }

    fun endSwipeTouch() {
        swipeTextOwnership = null
        swipeDownUptime = null
        swipeStartsOnSelection = false
        swipeSelectionAtDown = null
        isSwipeYieldedToText = false
    }
}

/**
 * The field's text toolbar ("Paste", a selection's actions), shown through [platform]. A toolbar
 * that closes because the field became [covered] (the + menu, the drawer) is dismissed: the field
 * still holds its toolbar state and asks to show it again whenever it's uncovered in a focused
 * window, and those requests are swallowed until the field itself hides it (a tap, a long press,
 * an edit, losing focus), which it only does in a focused, uncovered window. A long press then
 * shows it as usual.
 */
private class CoverDismissedTextToolbar(
    private val covered: () -> Boolean,
    private val windowFocused: () -> Boolean,
) : TextContextMenuProvider {
    var platform: TextContextMenuProvider? = null
    private var dismissedByCover = false

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        try {
            if (dismissedByCover) awaitCancellation()
            platform?.showTextContextMenu(dataProvider)
        } catch (cancelled: CancellationException) {
            dismissedByCover = when {
                covered() -> true
                windowFocused() -> false
                else -> dismissedByCover
            }
            throw cancelled
        }
    }
}

/** The mounted field's [ComposerSwipeInput], refreshed every composition. */
private class ComposerFieldSwipeInput : ComposerSwipeInput {
    var yieldsToText: () -> Boolean = { false }
    var claims: (Offset) -> Boolean = { false }
    var doubleTap: () -> Boolean = { false }
    var flush: () -> Unit = {}
    var sync: () -> Unit = {}
    override val isSwipeYieldedToText: Boolean get() = yieldsToText()
    override fun selectionClaims(rootPoint: Offset): Boolean = claims(rootPoint)
    override fun takesDoubleTap(): Boolean = doubleTap()
    override fun flushEdits() = flush()
    override fun takeParentText() = sync()
}

/**
 * The composer's multiline text input (iOS `ComposerTextInput`, a `UITextView` bridge):
 *
 * - **Line cap.** Grows line by line to [lineLimit] (the composer's own six expanded, one
 *   collapsed) and only then scrolls; a short draft never rubber-bands. The expanded [topInset]
 *   scrolls away with the text.
 * - **Caret.** A programmatic draft (dictation, a restored draft, Share, Quote) lands with the
 *   end in view (or the autocomplete caret); a user edit keeps the caret in view as the field
 *   crosses its cap; dragging the text hands it over to the reader.
 * - **Paste and drop.** Images go to [onImagePaste] (never into the text); a paste past
 *   [PastedText]'s limits goes to [onLongTextPaste] as a Pasted Text card (null while answering
 *   a request: `takesPasteAttachments`, so it goes in as text); dropped files go to [onFileDrop];
 *   dropped text on an unfocused field is appended on a new line.
 * - **Hardware keyboard.** Ctrl/⌘+Return → [onCommandReturn]; Shift+Return inserts a newline
 *   that never sends ([onNonSendNewline]); ↑/↓, Escape, Tab and Return drive Composer
 *   Autocomplete while its list is open ([autocompleteBridge]).
 * - **Return sends.** With [returnKeySends] the IME shows Send; it inserts a newline the
 *   parent reads as Return (`new == old + "\n"`), as iOS does.
 * - **Swipes.** A downward swipe on the focused field dismisses the keyboard; an upward swipe
 *   on the unfocused field pulls it up ([ComposerSwipeDown], via [onDismissKeyboard] /
 *   [onFocusKeyboard]); neither fires while the text itself scrolls that way, nor while a hold
 *   (a held mic, [isHoldingMic]) owns the touch.
 * - **Selecting.** A drag the text is selecting with (it starts on or near the selection, or
 *   rests first) is never a swipe ([ComposerSwipeDown.textOwnsDrag]).
 * - **Keyboard insets.** The field only takes and drops focus; the session's composer docks
 *   above the IME with `imePadding()`/animated `WindowInsets.ime` (iOS keyboard docking).
 *
 * [isKeyboardHeld] is iOS `DrawerKeyboardHold`: under the open drawer no key, paste or drop
 * reaches the draft. [canTakeFocus] is iOS `isReachable`: never take focus under something that
 * takes its touches (the drawer, Settings). [topInsetFraction] sets how much of [topInset] shows
 * (the keyboard morph scrubs it; null follows [expanded]); [refusesFocus] keeps the field from
 * taking focus while something else owns the composer (One Picker open). [currentText] reads
 * the parent's draft as it is now (a composition's [text] can lag the reader's edit), and a new
 * [focusRequest] places the caret at the end ([ComposerTextInputController.syncText]). [onEdit]
 * (iOS `onEdit`) takes a typed edit and where it left the caret as one call, instead of
 * [onTextChange] plus the bridge's own caret report.
 * [isCovered]: something is drawn over the field (the + menu); like the drawer's hold, it hides
 * the field's handles and text toolbar, which are popup windows above the app's.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ComposerTextInput(
    text: String,
    onTextChange: (String) -> Unit,
    isFocused: Boolean,
    onFocusedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onImagePaste: (List<Uri>) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = OpenCodeTextStyles.body,
    topInset: Dp = ComposerTextInputTunables.expandedTopInset,
    onLongTextPaste: ((String) -> Unit)? = null,
    onFileDrop: ((List<Uri>) -> Unit)? = null,
    onDismissKeyboard: (() -> Unit)? = null,
    onFocusKeyboard: (() -> Unit)? = null,
    onCommandReturn: (() -> Unit)? = null,
    onNonSendNewline: ((String) -> Unit)? = null,
    onDropTargetedChange: ((Boolean) -> Unit)? = null,
    returnKeySends: Boolean = false,
    fieldLink: ComposerFieldLink? = null,
    autocompleteBridge: ComposerAutocompleteTextBridge? = null,
    lineLimit: Int? = null,
    accessibilityLabel: String = stringResource(R.string.composer_message_placeholder),
    isKeyboardHeld: Boolean = false,
    isHoldingMic: Boolean = false,
    canTakeFocus: Boolean = true,
    topInsetFraction: Float? = null,
    refusesFocus: (() -> Boolean)? = null,
    currentText: (() -> String)? = null,
    focusRequest: Int = 0,
    onEdit: ((String, ComposerAutocompleteAction.CaretUpdate) -> Unit)? = null,
    isCovered: Boolean = false,
) {
    // A dark/light or language switch recreates the activity, where iOS's text view keeps
    // its selection (and rotation, handled in place, keeps it here): the caret or selection comes
    // back where it was, for the same draft in the same process.
    val savedSelection = rememberSaveable(saver = ComposerSavedSelection.Saver) { ComposerSavedSelection() }
    val state = remember { TextFieldState(text, savedSelection.restored(text) ?: TextRange(text.length)) }
    LaunchedEffect(state) {
        // The draft can reach a recreated field a moment after it composes (its caret then at the end).
        withTimeoutOrNull(SELECTION_RESTORE_MILLIS) {
            val restored = snapshotFlow { savedSelection.restored(state.text.toString()) }.filterNotNull().first()
            if (state.selection != restored) state.edit { selection = restored }
        }
        snapshotFlow { state.text.length to state.selection }.collect { (length, selection) -> savedSelection.update(length, selection) }
    }
    val controller = remember { ComposerTextInputController() }
    val scroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    var hasFocus by remember { mutableStateOf(false) }

    val currentOnTextChange by rememberUpdatedState(onTextChange)
    val currentOnEdit by rememberUpdatedState(onEdit)
    val currentOnFocusedChange by rememberUpdatedState(onFocusedChange)
    val currentIsFocused by rememberUpdatedState(isFocused)
    val currentOnImagePaste by rememberUpdatedState(onImagePaste)
    val currentOnLongTextPaste by rememberUpdatedState(onLongTextPaste)
    val currentOnFileDrop by rememberUpdatedState(onFileDrop)
    val currentOnDismissKeyboard by rememberUpdatedState(onDismissKeyboard)
    val currentOnFocusKeyboard by rememberUpdatedState(onFocusKeyboard)
    val currentOnNonSendNewline by rememberUpdatedState(onNonSendNewline)
    val currentOnDropTargetedChange by rememberUpdatedState(onDropTargetedChange)
    val currentBridge by rememberUpdatedState(autocompleteBridge)
    // iOS `DrawerKeyboardHold.isHeld(textView)`: the drawer holds this field's keyboard.
    val fieldKey = remember { Any() }
    val drawerHold = LocalDrawerKeyboardSignals.current
    val held by rememberUpdatedState(isKeyboardHeld || drawerHold?.isHeld(fieldKey) == true)
    val holdingMic by rememberUpdatedState(isHoldingMic)
    val currentRefusesFocus by rememberUpdatedState(refusesFocus)

    fun syncFromParent() {
        controller.syncText(state, text, currentText, focusRequest, hasFocus, autocompleteBridge)
    }

    // Programmatic updates (dictation, a restored draft, Share/Quote) follow the newest text;
    // synced before the field measures so the first frame of an expand already has it.
    SideEffect { syncFromParent() }

    // The reader's own edits: report them, with a non-send newline first so the parent
    // never reads it as Return.
    fun publishEdit(newText: String) {
        val previous = controller.externalText ?: return
        if (newText == previous) return
        controller.noteTextChange()
        // A reader's own edit owns the caret; drop any pending follow-the-end.
        controller.pinsToEnd = false
        controller.followsCaret = true
        val nonSendFrom = controller.nonSendEditFrom
        controller.nonSendEditFrom = null
        if (nonSendFrom != null && newText == nonSendFrom + "\n") currentOnNonSendNewline?.invoke(newText)
        val onEdit = currentOnEdit
        if (onEdit != null) {
            // One action for the edit and the caret it left (iOS `onEdit`).
            val caret = currentBridge?.caretUpdateAfterEdit(newText, state.selection, state.composition)
                ?: ComposerAutocompleteAction.CaretUpdate.Unchanged
            controller.publish(newText) { onEdit(it, caret) }
        } else {
            controller.publish(newText, currentOnTextChange)
            currentBridge?.notifyCaretAfterEdit(newText, state.selection, state.composition)
        }
        if (controller.syncsAfterPublish) {
            controller.syncsAfterPublish = false
            syncFromParent()
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { newText -> publishEdit(newText) }
    }

    // Moving the caret off the end is reading or editing earlier text.
    LaunchedEffect(state) {
        snapshotFlow { state.selection }.collect { selection ->
            controller.noteSelectionChange(state.text.toString(), selection)
            if (!hasFocus) return@collect
            val current = state.text.toString()
            currentBridge?.selectionDidChange(current, selection, state.composition, controller.externalText ?: current)
            if (selection != TextRange(current.length)) controller.pinsToEnd = false
        }
    }

    // A drag on the text is the reader's: stop following the end or the caret.
    LaunchedEffect(scroll) {
        scroll.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                controller.pinsToEnd = false
                controller.followsCaret = false
            }
        }
    }

    // Focus follows `isFocused`, settled after the update (iOS dispatches it to the next turn).
    LaunchedEffect(isFocused, canTakeFocus) {
        awaitFrame()
        if (isFocused && !hasFocus && canTakeFocus && currentRefusesFocus?.invoke() != true) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else if (!isFocused && hasFocus) {
            focusManager.clearFocus()
        }
    }

    val lineHeightPx = with(density) {
        if (textStyle.lineHeight.isSp) textStyle.lineHeight.toPx() else textStyle.fontSize.toPx() * 1.3f
    }
    val lines = lineLimit ?: if (expanded) 6 else 1
    // iOS `appliedTopInset`: the keyboard morph scrubs how much of the inset shows.
    val appliedTopInset = topInset * (topInsetFraction ?: if (expanded) 1f else 0f)
    val insetPx = with(density) { appliedTopInset.toPx() }
    val currentInsetPx by rememberUpdatedState(insetPx)
    val capPx = ceil(lineHeightPx * lines + insetPx)
    val capDp = with(density) { capPx.toDp() }
    // Past the cap the text scrolls; below it, nothing does (no bounce, no indicator).
    val overflows = scroll.maxValue > 0

    LaunchedEffect(overflows) {
        if (!overflows) {
            controller.followsCaret = false
            if (scroll.value != 0) scroll.scrollTo(0)
        }
    }

    // Keep the end (programmatic text) or the caret (typing) in view once the text scrolls.
    LaunchedEffect(scroll, state) {
        snapshotFlow { Triple(scroll.maxValue, controller.layoutTick, state.selection) }.collectLatest { (maxValue, _, selection) ->
            if (maxValue <= 0) return@collectLatest
            // Short of room the composer lays the field out below its cap.
            val viewport = scroll.viewportSize.takeIf { it > 0 }?.toFloat() ?: capPx
            if (controller.pinsToEnd) {
                if (scroll.value != maxValue) scroll.scrollTo(maxValue)
                return@collectLatest
            }
            if (!controller.followsCaret) return@collectLatest
            controller.followsCaret = false
            val textLength = state.text.length
            if (selection == TextRange(textLength)) {
                if (scroll.value != maxValue) scroll.scrollTo(maxValue)
                return@collectLatest
            }
            val layout = controller.layout ?: return@collectLatest
            val caret = runCatching { layout.getCursorRect(selection.end.coerceIn(0, textLength)) }.getOrNull() ?: return@collectLatest
            val top = caret.top + insetPx - 2f
            val bottom = caret.bottom + insetPx + 2f
            val visibleTop = scroll.value.toFloat()
            val visibleBottom = visibleTop + viewport
            if (bottom > visibleBottom) {
                scroll.scrollTo(min(maxValue.toFloat(), bottom - viewport).toInt())
            } else if (top < visibleTop) {
                scroll.scrollTo(max(0f, top).toInt())
            }
        }
    }

    val styling = autocompleteBridge?.let { rememberAutocompleteTextStyling(it, state.text.toString(), textStyle) }

    // Under the open drawer no edit reaches the composer.
    val holdTransformation = remember {
        object : InputTransformation {
            override fun TextFieldBuffer.transformInput() {
                if (held) {
                    revertAllChanges()
                    return
                }
            }
        }
    }
    val inputTransformation = (autocompleteBridge?.let { holdTransformation.then(it.inputTransformation) } ?: holdTransformation)
        .then(PerfKeystroke)

    fun insertNonSendNewline() {
        controller.nonSendEditFrom = state.text.toString()
        state.edit { replaceSelection("\n") }
    }

    fun dismissKeyboard() {
        currentOnDismissKeyboard?.invoke()
        if (hasFocus) focusManager.clearFocus()
    }

    // On iOS the keyboard only leaves as the field resigns, so a hidden keyboard is an unfocused
    // composer. Android's Back hides the IME and leaves the field focused (caret showing, row
    // open, a draft's projects folded): the keys leaving on their own take the focus with them.
    // Not while the drawer holds them down, or when another window or the background took them.
    val imeVisible = WindowInsets.isImeVisible
    val windowInfo = LocalWindowInfo.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var imeWasVisible by remember { mutableStateOf(false) }
    // A recreated window (dark mode, language, font size) starts out reporting the old window's
    // keys as up before it has focus. Until keys come up in this window, or those keys have
    // stayed up over the restored, focused field for a moment, a hide is the system moving the
    // old window's keys (sometimes reported before the restored field's rise starts), not the
    // keys leaving.
    var keysInherited by remember { mutableStateOf(imeVisible && !windowInfo.isWindowFocused) }
    var inheritedKeysWentDown by remember { mutableStateOf(false) }
    LaunchedEffect(keysInherited, hasFocus, imeVisible, windowInfo.isWindowFocused) {
        if (!keysInherited || !hasFocus || !imeVisible || !windowInfo.isWindowFocused) return@LaunchedEffect
        delay(INHERITED_KEYS_SETTLE_MILLIS)
        keysInherited = false
    }
    // Each time the app leaves the foreground (Home, Recents): the wait below doesn't outlive it.
    var pauses by remember { mutableIntStateOf(0) }
    // [pauses] when the keys were last seen coming up.
    var pausesWhenShown by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) pauses++ }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val imeTarget = WindowInsets.imeAnimationTarget
    LaunchedEffect(imeVisible) {
        val left = imeWasVisible && !imeVisible
        if (keysInherited) {
            if (!imeVisible) inheritedKeysWentDown = true else if (inheritedKeysWentDown) keysInherited = false
            imeWasVisible = imeVisible
            return@LaunchedEffect
        }
        imeWasVisible = imeVisible
        if (imeVisible) pausesWhenShown = pauses
        if (!left || !hasFocus || drawerHold?.isHeld(fieldKey) == true || isKeyboardHeld) return@LaunchedEffect
        // A recreated window (dark mode, font size) first reports the old window's keys as shown,
        // then as hidden while they rise for this field: keys on their way up aren't leaving.
        if (imeTarget.getBottom(density) > 0) return@LaunchedEffect
        // Keys the background took (Home, Recents) haven't left: the field keeps its focus and the
        // keys come back with the app, as iOS's text view stays first responder.
        if (!windowInfo.isWindowFocused || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        // After a quick return the background's hide can reach the field only once the app
        // is back in front: keys last seen up before the app left went with it, not with the reader.
        if (pauses != pausesWhenShown) return@LaunchedEffect
        // Keys that take no room (Gboard floating, or its toolbar over a hardware keyboard) keep
        // the target at 0 and, as a recreated window re-attaches them, can flick hidden and straight
        // back: only keys that stay down have left (a flick back restarts this effect). The wait
        // can end only once the app is back from the background; then the keys went with it.
        val pausesBefore = pauses
        delay(KEYS_LEFT_SETTLE_MILLIS)
        if (pauses != pausesBefore) return@LaunchedEffect
        if (!hasFocus || drawerHold?.isHeld(fieldKey) == true || isKeyboardHeld) return@LaunchedEffect
        if (!windowInfo.isWindowFocused || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        // On the drawer's card the drawer takes the focus (`releaseFocus`) instead of it
        // being cleared. `clearFocus` also drops the window's view focus, and after a key (Back,
        // or TalkBack's Back) the window gives it straight back: an input-focus event TalkBack
        // follows to the screen's first element ("Open sessions"). VoiceOver stays on the text
        // view as UIKit resigns it (iOS `dismissKeyboard`, ComposerTextInput.swift:802-805). The
        // field still loses its focus: the caret goes and `onFocusedChange(false)` reaches the host.
        if (drawerHold?.releaseFocus() != true) dismissKeyboard()
    }

    // A recreated window asks for the restored field's keys before it is the IME's target; when
    // switches come quickly the system drops that request and the keys never come. The first time
    // this window can control the keys, a field that already has focus asks for them again.
    val view = LocalView.current
    DisposableEffect(view) {
        val controller = view.windowInsetsController
        var asked = false
        val listener = object : WindowInsetsController.OnControllableInsetsChangedListener {
            override fun onControllableInsetsChanged(controller: WindowInsetsController, types: Int) {
                if (asked || types and android.view.WindowInsets.Type.ime() == 0) return
                asked = true
                if (hasFocus && !held) keyboard?.show()
            }
        }
        controller?.addOnControllableInsetsChangedListener(listener)
        onDispose { controller?.removeOnControllableInsetsChangedListener(listener) }
    }

    // The caret's drop handle, the selection handles and the text toolbar are popup windows over
    // the app's, so they'd float above the + menu or the drawer (iOS keeps its caret in the field).
    // While the field is covered it reads as in an unfocused window, which hides them (the field
    // keeps its focus and keyboard); a hidden caret handle stays hidden until the next tap.
    val covered by rememberUpdatedState(isCovered || held)
    // A tap's drop handle that would hang below the field sits over the control row (+, the
    // model chip, Send) and takes the next tap meant for them. iOS shows no caret handle; this
    // hides that one the same way, for two frames, so it stays hidden until the next tap.
    var hidesCaretHandle by remember { mutableStateOf(false) }
    val coverableWindowInfo = remember(windowInfo) {
        object : WindowInfo by windowInfo {
            override val isWindowFocused: Boolean get() = windowInfo.isWindowFocused && !covered && !hidesCaretHandle
        }
    }
    val caretHandleHeightPx = with(density) { 25.dp.toPx() }
    // The + menu or the drawer taking the interaction ends the text toolbar (iOS's edit
    // menu, Android's own fields), rather than just hiding it until it comes back over what moved in.
    val toolbar = remember(windowInfo) { CoverDismissedTextToolbar({ covered }, { windowInfo.isWindowFocused }) }

    /** Whether a caret's drop handle would hang past the bottom of the field's [viewportHeight] (px). */
    fun caretHandleHangsOut(viewportHeight: Float): Boolean {
        val selection = state.selection
        if (!selection.collapsed || state.text.isEmpty()) return false
        val layout = controller.layout ?: return false
        val offset = selection.end.coerceIn(0, layout.layoutInput.text.length)
        val caret = runCatching { layout.getCursorRect(offset) }.getOrNull() ?: return false
        return caret.bottom + currentInsetPx - scroll.value + caretHandleHeightPx > viewportHeight
    }

    /** Hides a caret handle that would hang below the field, once the field has settled. */
    suspend fun hideCaretHandleIfItHangsOut(viewportHeight: () -> Float) {
        awaitFrame()
        awaitFrame()
        if (!caretHandleHangsOut(viewportHeight())) return
        hidesCaretHandle = true
        try {
            awaitFrame()
            awaitFrame()
        } finally {
            hidesCaretHandle = false
        }
    }

    // The keyboard rising squeezes a field: a handle that fit can end up hanging out.
    // Checked once the field has settled and scrolled its caret back into view.
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.viewportSize }.collectLatest { viewport ->
            if (!hasFocus || viewport <= 0) return@collectLatest
            delay(300)
            snapshotFlow { scroll.isScrollInProgress }.first { !it }
            hideCaretHandleIfItHangsOut { scroll.viewportSize.toFloat() }
        }
    }

    fun focusKeyboard() {
        if (controller.isFocusingFromSwipe) return
        currentOnFocusKeyboard?.invoke()
        controller.isFocusingFromSwipe = true
    }

    /**
     * iOS `ImagePasteTextView.selectionClaims`: whether [point] (in the field's viewport) is on or
     * near the selection being made; a caret isn't a selection.
     */
    fun selectionClaims(point: Offset, isFocused: Boolean): Boolean {
        val selection = state.selection
        val layout = controller.layout ?: return false
        val textPoint = Offset(point.x, point.y + scroll.value - currentInsetPx)
        return ComposerSwipeDown.selectionClaims(
            textPoint,
            isFocused = isFocused,
            selection = selection,
            selectionRects = { selectionRects(layout, selection) },
            reach = with(density) { BackSwipeRules.selectionHandleReach.toPx() },
        )
    }

    /**
     * iOS `takesDoubleTap`: whether the control tap now running is the second half of a finger's
     * double tap on the text's last line. If so, focuses the field and selects the word under
     * the first tap. Clears the recorded tap, except when asked about the first tap's own touch
     * (the composer's tap can see taps on the text too): then it keeps the tap for the second.
     */
    fun takesDoubleTap(now: Long): Boolean {
        val tap = controller.lastTap ?: return false
        val latest = fieldLink?.windowTouches?.latest
        if (latest != null && latest.time <= tap.began) return false
        controller.lastTap = null
        val coordinates = controller.swipeCoordinates?.takeIf { it.isAttached } ?: return false
        val second = fieldLink?.windowTouches?.latest ?: return false
        val secondPoint = coordinates.localPositionOf(coordinates.findRootCoordinates(), second.rootPoint)
        val tapViewportX = tap.point.x
        val isSecondHalf = ComposerDoubleTap.isSecondHalf(
            tap = tap,
            tapViewportX = tapViewportX,
            text = state.text.toString(),
            second = second,
            secondViewportPoint = secondPoint,
            fieldHeight = coordinates.size.height.toFloat(),
            now = now,
            density = density.density,
        )
        if (!isSecondHalf) return false
        if (!hasFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        val layout = controller.layout ?: return true
        val word = ComposerDoubleTap.wordAt(layout, layout.getOffsetForPosition(tap.point)) ?: return true
        state.edit { selection = word }
        return true
    }

    /**
     * iOS `ComposerTapObserver`'s end: a quick finger tap that began at [down] (viewport px) on
     * the last visible line is recorded as [ComposerTextInputController.lastTap].
     */
    fun noteTap(down: Offset, began: Long, ended: Long, viewportHeight: Float) {
        if (ended - began >= ComposerDoubleTap.TAP_DURATION_MS) return
        val layout = controller.layout ?: return
        val text = state.text.toString()
        if (layout.layoutInput.text.length != text.length) return
        val textPoint = Offset(down.x, down.y + scroll.value - currentInsetPx)
        val caret = runCatching { layout.getCursorRect(layout.getOffsetForPosition(textPoint)) }.getOrNull() ?: return
        val lastLineCaret = runCatching { layout.getCursorRect(text.length) }.getOrNull() ?: return
        val visibleBottom = scroll.value + viewportHeight - currentInsetPx
        if (!ComposerDoubleTap.isOnLastVisibleLine(caret, lastLineCaret, lineHeightPx, visibleBottom)) return
        controller.lastTap = ComposerTextTap(textPoint, began, ended, text)
    }

    /** iOS: taps on inline controls (autocomplete chips, frames grown by 4 dp) don't count. */
    fun isOnInlineControl(down: Offset): Boolean {
        val bridge = currentBridge ?: return false
        val styling = bridge.styling ?: return false
        val layout = bridge.textLayout ?: return false
        if (!bridge.isEnabled || layout.layoutInput.text.text != styling.visualText) return false
        val textPoint = Offset(down.x, down.y + scroll.value - currentInsetPx)
        val slop = ComposerDoubleTap.CONTROL_SLOP_DP * density.density
        for (run in styling.chips) {
            if (run.chip == null) continue
            val rect = bridge.chipRectPx(run, layout, density) ?: continue
            if (rect.inflate(slop).contains(textPoint)) return true
        }
        return false
    }

    // iOS `sendTransition.input`: the capsule's swipe asks the field whether a touch is
    // the text's, even one that starts just outside it, where a selection's handle hangs.
    val swipeInput = remember { ComposerFieldSwipeInput() }
    SideEffect {
        swipeInput.yieldsToText = {
            controller.yieldsToText(
                holdBegan = holdingMic,
                textGestureBegan = state.selection != controller.swipeSelectionAtDown && !state.selection.collapsed,
            )
        }
        swipeInput.claims = { rootPoint ->
            val coordinates = controller.swipeCoordinates?.takeIf { it.isAttached }
            coordinates != null &&
                selectionClaims(coordinates.localPositionOf(coordinates.findRootCoordinates(), rootPoint), isFocused = hasFocus)
        }
        swipeInput.doubleTap = { takesDoubleTap(SystemClock.uptimeMillis()) }
        // iOS reads the field's text, marked text included, and clears it before the keyboard's
        // next commit (`finishCompositionIfNeeded`). Here an edit reaches the parent a frame late
        // and the clear lands on the next composition, so a send in between sent a prefix and
        // the next commit wrote the whole old text back: flush before the send, clear right after.
        swipeInput.flush = { publishEdit(state.text.toString()) }
        swipeInput.sync = {
            if (controller.isPublishingText) controller.syncsAfterPublish = true else syncFromParent()
        }
        fieldLink?.input = swipeInput
    }
    DisposableEffect(fieldLink, swipeInput) {
        onDispose {
            // iOS `didMoveToWindow`: a field leaving the screen forgets its tap.
            controller.lastTap = null
            if (fieldLink?.input === swipeInput) fieldLink.input = null
        }
    }

    val receiveContent = remember {
        object : ReceiveContentListener {
            override fun onDragEnter() {
                currentOnDropTargetedChange?.invoke(true)
            }

            override fun onDragExit() {
                currentOnDropTargetedChange?.invoke(false)
            }

            override fun onDragEnd() {
                currentOnDropTargetedChange?.invoke(false)
            }

            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                if (held) return null
                val isDrop = transferableContent.source == TransferableContent.Source.DragAndDrop
                if (isDrop) currentOnDropTargetedChange?.invoke(false)
                val clip = transferableContent.clipEntry.clipData
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                // Images never enter the text: they become attachments (or, answering a request, nothing).
                if (transferableContent.hasMediaType(MediaType.Image) && uris.isNotEmpty()) {
                    currentOnImagePaste(uris)
                    return null
                }
                val fileDrop = currentOnFileDrop
                val pasted = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).text?.toString() }.joinToString("")
                if (isDrop && fileDrop != null && uris.isNotEmpty() && pasted.isEmpty()) {
                    fileDrop(uris)
                    return null
                }
                if (pasted.isEmpty()) return transferableContent
                val longPaste = currentOnLongTextPaste
                if (PastedText.isLong(pasted) && longPaste != null) {
                    longPaste(pasted)
                    return null
                }
                if (isDrop && !hasFocus) {
                    // Dropped on a field that isn't being edited: a new line after the draft.
                    val current = state.text.toString()
                    val separator = if (current.isEmpty() || current.endsWith("\n") || current.endsWith(" ")) "" else "\n"
                    controller.nonSendEditFrom = current
                    state.edit {
                        append(separator + pasted)
                        placeCursorAtEnd()
                    }
                    return null
                }
                controller.nonSendEditFrom = state.text.toString()
                return transferableContent
            }
        }
    }

    Box(
        modifier
            .clipToBounds()
            .heightIn(max = capDp)
            .onPlaced { controller.swipeCoordinates = it }
            .pointerInput(Unit) {
                val gestureScope = this
                coroutineScope { val settleScope = this; with(gestureScope) { awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    // iOS `noteSwipeTouchDown`.
                    controller.isSuppressingFocusAfterSwipe = false
                    controller.isFocusingFromSwipe = false
                    controller.swipeBeganFocused = hasFocus
                    controller.isScrollingUp = scroll.maxValue > 0 && scroll.value > 0
                    controller.isScrollingDown = scroll.maxValue > 0 && scroll.value < scroll.maxValue
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var yieldedToHold = false
                    // iOS `ComposerSwipePanRecognizer.textOwnership`: decided once the finger
                    // first moves the decision distance, then the text's for good.
                    val selectionAtDown = state.selection
                    val startsOnSelection = selectionClaims(down.position, isFocused = hasFocus)
                    controller.swipeSelectionAtDown = selectionAtDown
                    controller.swipeStartsOnSelection = startsOnSelection
                    controller.swipeDownUptime = down.uptimeMillis
                    controller.swipeLatestUptime = down.uptimeMillis
                    controller.swipeTextOwnership = if (startsOnSelection) true else null
                    controller.isSwipeYieldedToText = startsOnSelection
                    val decisionPx = BackSwipeRules.decisionDistance.toPx()
                    // iOS `ComposerTapObserver`: a lone finger's quick tap on the text, off any
                    // inline control, may be a double tap's first half ([ComposerDoubleTap]).
                    var isTap = currentEvent.changes.size == 1 && down.type == PointerType.Touch && !isOnInlineControl(down.position)
                    val tapSlopPx = ComposerDoubleTap.TAP_SLOP_DP.dp.toPx()
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.size > 1 || (change.position - down.position).getDistance() > tapSlopPx) isTap = false
                        if (isTap && !change.pressed) {
                            noteTap(down.position, down.uptimeMillis, change.uptimeMillis, size.height.toFloat())
                            // The field places the caret and shows its handle on this up.
                            val viewportHeight = size.height.toFloat()
                            settleScope.launch { hideCaretHandleIfItHangsOut { viewportHeight } }
                        }
                        tracker.addPosition(change.uptimeMillis, change.position)
                        controller.swipeLatestUptime = change.uptimeMillis
                        val heldFor = (change.uptimeMillis - down.uptimeMillis) / 1000.0
                        if (!yieldedToHold && BackSwipeRules.yieldsToHold(heldFor, holdBegan = holdingMic)) yieldedToHold = true
                        // The field's own selecting (a long press, a drag that moves the selection).
                        val textGestureBegan = state.selection != selectionAtDown && !state.selection.collapsed
                        val textOwnership = controller.swipeTextOwnership
                        if (textOwnership == null && (change.position - down.position).getDistance() >= decisionPx) {
                            controller.yieldsToText(holdBegan = holdingMic, textGestureBegan = textGestureBegan)
                        } else if (textOwnership == false && textGestureBegan) {
                            controller.swipeTextOwnership = true
                        }
                        val yieldedToText = controller.swipeTextOwnership == true
                        controller.isSwipeYieldedToText = yieldedToText
                        val translation = (change.position - down.position) / density.density
                        val velocity = tracker.calculateVelocity().let { Offset(it.x, it.y) / density.density }
                        val predicted = translation + velocity * 0.15f
                        val ended = !change.pressed
                        if (controller.swipeBeganFocused) {
                            val dismisses = (ended && controller.isSuppressingFocusAfterSwipe) || ComposerSwipeDown.shouldDismissKeyboard(
                                translation = translation,
                                predictedEndTranslation = predicted,
                                isFocused = hasFocus || (!ended && controller.isSuppressingFocusAfterSwipe),
                                isHoldingMic = yieldedToText,
                                isScrollingUp = controller.isScrollingUp,
                            )
                            if (dismisses) {
                                controller.isSuppressingFocusAfterSwipe = true
                                dismissKeyboard()
                            }
                        } else if (!controller.isFocusingFromSwipe) {
                            val focuses = ComposerSwipeDown.shouldFocusKeyboard(
                                translation = translation,
                                predictedEndTranslation = predicted,
                                velocityY = velocity.y,
                                isFocused = hasFocus,
                                isHoldingMic = yieldedToHold || yieldedToText,
                                isScrollingDown = controller.isScrollingDown,
                            )
                            if (focuses) focusKeyboard()
                        }
                        if (ended) break
                    }
                    // Settled on the next frame, as iOS does on the next main-queue turn.
                    settleScope.launch {
                        awaitFrame()
                        controller.isSuppressingFocusAfterSwipe = false
                        controller.isFocusingFromSwipe = false
                        controller.isScrollingUp = false
                        controller.isScrollingDown = false
                        controller.endSwipeTouch()
                    }
                } } }
            }
            .verticalScroll(scroll, enabled = overflows),
    ) {
        // The platform toolbar the field would set up for itself, read from an empty container.
        SelectionContainer(Modifier.size(0.dp).focusProperties { canFocus = false }) {
            toolbar.platform = LocalTextContextMenuToolbarProvider.current
        }
        CompositionLocalProvider(
            LocalWindowInfo provides coverableWindowInfo,
            LocalTextContextMenuToolbarProvider provides toolbar,
        ) { BasicTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = appliedTopInset)
                .focusRequester(focusRequester)
                .drawerKeyboardField(fieldKey)
                .onFocusChanged { focus ->
                    if (focus.isFocused && (controller.isSuppressingFocusAfterSwipe || currentRefusesFocus?.invoke() == true)) {
                        // The touch that just dismissed the keyboard doesn't bring it back.
                        focusManager.clearFocus()
                        return@onFocusChanged
                    }
                    hasFocus = focus.isFocused
                    if (focus.isFocused != currentIsFocused) currentOnFocusedChange(focus.isFocused)
                }
                .onPreviewKeyEvent { event ->
                    if (held) return@onPreviewKeyEvent event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (currentBridge?.handleKeyEvent(event) == true) return@onPreviewKeyEvent true
                    val isReturn = event.key == Key.Enter || event.key == Key.NumPadEnter
                    if (!isReturn) return@onPreviewKeyEvent false
                    when {
                        event.isMetaPressed || event.isCtrlPressed -> {
                            if (event.type == KeyEventType.KeyDown) onCommandReturn?.invoke()
                            true
                        }
                        event.isShiftPressed -> {
                            if (event.type == KeyEventType.KeyDown) insertNonSendNewline()
                            true
                        }
                        else -> false
                    }
                }
                .contentReceiver(receiveContent)
                .semantics { contentDescription = accessibilityLabel }
                .then(autocompleteBridge?.let { Modifier.autocompleteTextAnchor(it) } ?: Modifier),
            inputTransformation = inputTransformation,
            outputTransformation = autocompleteBridge?.takeIf { styling != null }?.outputTransformation,
            textStyle = textStyle.copy(color = OpenCodeTheme.colors.text),
            cursorBrush = SolidColor(OpenCodeTheme.colors.accent),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = if (returnKeySends) ImeAction.Send else ImeAction.Default,
            ),
            onKeyboardAction = KeyboardActionHandler {
                if (held) return@KeyboardActionHandler
                val bridge = currentBridge
                if (bridge != null && bridge.isEnabled && bridge.isOpen && bridge.hasSelectableRow) {
                    bridge.onConfirmSelection?.invoke()
                    return@KeyboardActionHandler
                }
                // The Send key is Return: a newline the parent reads as `old + "\n"`.
                state.edit { replaceSelection("\n") }
            },
            lineLimits = TextFieldLineLimits.MultiLine(),
            onTextLayout = { getResult ->
                val result = getResult()
                // The field calls this from its own measure, which observes every state read here:
                // `layoutTick += 1` reads before it writes, so the write invalidated the field's
                // measure, which called this again — a relayout and redraw every frame for as long
                // as the screen stayed up (60 fps idle, found against a live server). Only the
                // layout result is the field's input; the rest is bookkeeping for other readers.
                Snapshot.withoutReadObservation {
                    controller.layout = result
                    autocompleteBridge?.textLayout = result
                    if (result != null) autocompleteBridge?.updateLineBreaks(result)
                    controller.layoutTick += 1
                }
            },
            decorator = { innerTextField ->
                Box {
                    innerTextField()
                    if (autocompleteBridge != null) AutocompleteInlineChips(autocompleteBridge)
                }
            },
        ) }
    }
}

/** iOS `PerfFrameLatency.begin("keystroke")` once the hold and autocomplete accept an edit. */
private object PerfKeystroke : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        if (PerfFrameLatency.isEnabled && !asCharSequence().contentEquals(originalText)) PerfFrameLatency.begin("keystroke")
    }
}

/** Replaces the selection with [text] and puts the caret after it. */
private fun TextFieldBuffer.replaceSelection(text: String) {
    val start = selection.min
    replace(start, selection.max, text)
    selection = TextRange(start + text.length)
}

/** The selection's rects per line, in the layout's coordinates (iOS `selectionRects(for:)`). */
private fun selectionRects(layout: TextLayoutResult, selection: TextRange): List<Rect> {
    val length = layout.layoutInput.text.length
    val start = selection.min.coerceIn(0, length)
    val end = selection.max.coerceIn(0, length)
    if (start >= end) return emptyList()
    val firstLine = layout.getLineForOffset(start)
    val lastLine = layout.getLineForOffset(end)
    return (firstLine..lastLine).map { line ->
        val left = if (line == firstLine) layout.getHorizontalPosition(start, usePrimaryDirection = true) else layout.getLineLeft(line)
        val right = if (line == lastLine) layout.getHorizontalPosition(end, usePrimaryDirection = true) else layout.getLineRight(line)
        Rect(min(left, right), layout.getLineTop(line), max(left, right), layout.getLineBottom(line))
    }
}
