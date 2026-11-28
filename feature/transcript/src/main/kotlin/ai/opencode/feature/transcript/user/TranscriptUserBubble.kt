package ai.opencode.feature.transcript.user

import ai.opencode.core.R as CoreR
import ai.opencode.feature.transcript.rows.LocalRowHeightMotion
import ai.opencode.feature.transcript.rows.DisclosureMotion
import androidx.compose.animation.core.Animatable
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.markdown.marked
import ai.opencode.feature.transcript.markdown.ui.ArrivingText
import ai.opencode.feature.transcript.markdown.ui.LocalSessionLinks
import ai.opencode.feature.transcript.markdown.ui.MarkdownPresentation
import ai.opencode.feature.transcript.markdown.ui.PresentationOptions
import ai.opencode.feature.transcript.markdown.ui.ReportsSessionLinks
import ai.opencode.feature.transcript.markdown.ui.SessionMentions
import ai.opencode.feature.transcript.markdown.ui.currentMarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.rememberLinkListener
import ai.opencode.server.model.SessionLink
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

/** The bubble's text: body with iOS's 2 pt of line spacing. */
internal val UserBubbleTextStyle = OpenCodeTextStyles.body.copy(lineHeight = (22 + UserBubbleFold.lineSpacing).sp)

/**
 * A user message's text bubble (iOS `TranscriptUserBubble`). Holding it lifts it and opens its
 * menu: Copy and Select (plus Send Now, Move to Queue and Delete while it waits as a steering
 * message) and Revert to Here when allowed, rather than word-by-word selection; Select enters
 * native range selection over the whole text. A failed send's bubble opens Retry, Edit and Delete
 * on a tap instead.
 */
@Composable
internal fun TranscriptUserBubble(
    text: String,
    /** Dimmed while the message waits in the inbox, or after its send failed. */
    isPending: Boolean = false,
    itemID: String? = null,
    reveal: AgentMessageReveal.Frame = AgentMessageReveal.Frame.rest,
    failedSend: TranscriptFailedSend? = null,
    pendingActions: TranscriptPendingActions? = null,
    onCopy: (String) -> Unit = {},
    onRevertToHere: (() -> Unit)? = null,
    /** Tight where it meets another surface of its run. */
    corners: UserSurfaceCorners = UserSurfaceCorners.round,
    fold: UserBubbleFold.State = UserBubbleFold.State(),
    modifier: Modifier = Modifier,
) {
    var isSelecting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val folds = remember { BooleanArray(1) }
    val reduceMotion = rememberReduceMotion()
    val context = LocalTranscriptRowContext.current
    // A new item, or closing its fold, ends selection (iOS `onChange(of: itemID / isExpanded)`).
    LaunchedEffect(itemID) { isSelecting = false }
    LaunchedEffect(fold.isExpanded) { if (!fold.isExpanded) isSelecting = false }

    fun beginSelection() {
        if (folds[0] && !fold.isExpanded) fold.toggle()
        isSelecting = true
    }

    // The bubble lifts while its menu is open, as iOS's context menu lifts its preview.
    val lift by animateFloatAsState(if (menu) 1.03f else 1f, if (reduceMotion) Motion.reducedFadeSpec() else Motion.microPop.spec(), label = "lift")
    TrailingFractionLayout(TranscriptMetrics.maximumUserBubbleFraction, modifier) {
        Box {
            val face: @Composable (Modifier) -> Unit = { faceModifier ->
                UserBubbleFace(
                    text = text,
                    isPending = isPending,
                    isSelecting = isSelecting,
                    reveal = reveal,
                    corners = corners,
                    fold = fold,
                    folds = folds,
                    reduceMotion = reduceMotion,
                    onCopy = onCopy,
                    onEndSelection = { isSelecting = false },
                    modifier = faceModifier.graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                    },
                )
            }
            if (failedSend != null) {
                face(
                    Modifier
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = true }
                        .semantics { customActions = failedSend.accessibilityActions },
                )
                UserMessageMenu(menu, { menu = false }) { failedSend.Actions { menu = false } }
            } else {
                // Long press opens the bubble's menu (lifting the bubble shape), not word-by-word
                // text selection; Select enters native range selection.
                val hasMenu = pendingActions != null || text.isNotEmpty()
                face(
                    Modifier.transcriptDragSource(
                        payload = if (isSelecting || isPending || pendingActions != null) null else TranscriptDragPayload.Text(text),
                        cornerRadius = TranscriptMetrics.bubbleCornerRadius,
                        surface = TranscriptDragSurfaceStyle.Bubble,
                        context = TranscriptDragContext(context.server, context.directory),
                        onLift = if (hasMenu && !isSelecting) ({ menu = true }) else null,
                        // Dragging out closes the menu the hold opened.
                        onDragStart = { menu = false },
                    ),
                )
                UserMessageMenu(menu, { menu = false }) {
                    val dismiss = { menu = false }
                    if (pendingActions != null) {
                        pendingActions.Menu(copying = text, dismiss = dismiss, selecting = ::beginSelection)
                    } else if (text.isNotEmpty()) {
                        UserMenuItem(stringResource(CoreR.string.common_copy), "square.on.square", dismiss) { onCopy(text) }
                        UserMenuItem(stringResource(R.string.transcript_message_select), "selection.pin.in.out", dismiss, onClick = ::beginSelection)
                        if (onRevertToHere != null) UserMenuItem(stringResource(R.string.transcript_message_revert_to_here), "arrow.uturn.backward", dismiss, onClick = onRevertToHere)
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubbleFace(
    text: String,
    isPending: Boolean,
    isSelecting: Boolean,
    reveal: AgentMessageReveal.Frame,
    corners: UserSurfaceCorners,
    fold: UserBubbleFold.State,
    folds: BooleanArray,
    reduceMotion: Boolean,
    onCopy: (String) -> Unit,
    onEndSelection: () -> Unit,
    modifier: Modifier,
) {
    val morphs = LocalPromotionMorph.current
    // Opacity rather than a secondary style, so promotion can animate it.
    val pendingAlpha by animateFloatAsState(if (isPending) 0.6f else 1f, PromotionMotion.animation(reduceMotion, morphs), label = "pending")
    val metrics = UserBubbleFold.Metrics.current()
    UserBubbleFoldView(
        metrics = metrics,
        fold = fold,
        folds = folds,
        content = { maxLines ->
            Box {
                // Its selectable copy stands in while selecting; hidden from TalkBack then.
                UserBubbleText(
                    text,
                    maxLines,
                    Modifier
                        .graphicsLayer { alpha = (if (isSelecting) 0f else pendingAlpha) * reveal.text.toFloat() }
                        .then(if (isSelecting) Modifier.clearAndSetSemantics { } else Modifier),
                )
                if (isSelecting) {
                    UserBubbleSelectableText(
                        text = SessionMentions.shown(text, LocalSessionLinks.current),
                        isPending = isPending,
                        onCopy = onCopy,
                        onEndSelection = onEndSelection,
                        modifier = Modifier.matchParentSize().graphicsLayer { alpha = reveal.text.toFloat() },
                    )
                }
            }
        },
        control = {
            // Inside the bubble, a quiet line under the text.
            Box(Modifier.graphicsLayer { alpha = pendingAlpha * reveal.text.toFloat() }) { fold.Label() }
        },
        modifier = modifier
            .voiceMessageBackground(VoiceMessageStyle.Standard, isPending, reveal, corners)
            .padding(horizontal = TranscriptMetrics.standard, vertical = TranscriptMetrics.bubbleVertical),
    )
}

/**
 * A long user message folds: past [overLines] lines its bubble shows [showLines], fading out over
 * its last two into Show More, which opens it in place on the list's disclosure motion (Show Less
 * closes it). Whatever sent it, typed, dictated, or another client's paste. TalkBack reads the
 * whole message either way (iOS `UserBubbleFold`).
 */
object UserBubbleFold {
    const val overLines = 12
    const val showLines = 8
    /** Lines a closed fold lays out: enough to fold and to fill what it shows. */
    const val closedLines = overLines + 3
    const val lineSpacing = 2f
    val fade: Dp = 44.dp
    /** Show More's row, under the text inside the bubble. */
    val controlHeight: Dp = 32.dp

    /** Open or folded, and the toggle, from the list's `TranscriptDisclosureState`. */
    class State(val isExpanded: Boolean = false, val toggle: () -> Unit = {}) {
        @Composable
        fun Label() {
            val color = OpenCodeTheme.colors.secondaryText
            val style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (isExpanded) stringResource(R.string.transcript_show_less) else stringResource(R.string.transcript_show_more), style = style, color = color)
                OpenCodeIcon(if (isExpanded) "chevron.up" else "chevron.down", contentDescription = null, size = smallGlyph(style), tint = color)
            }
        }
    }

    /** Heights at the body font's size, for this font scale, in pixels. */
    data class Metrics(val lineHeight: Float, val lineSpacing: Float) {
        fun height(lines: Int): Float = lines * lineHeight + max(lines - 1, 0) * lineSpacing

        /** Text up to this tall shows whole; a little slack keeps a 12-line message from folding on rounding. */
        val limit: Float get() = height(overLines) + lineHeight / 2
        /** The folded text, cut halfway into the gap under its last line. */
        val folded: Float get() = height(showLines) + lineSpacing / 2

        companion object {
            @Composable
            fun current(): Metrics {
                val density = LocalDensity.current
                return remember(density) {
                    with(density) { Metrics(OpenCodeTextStyles.body.lineHeight.toPx(), lineSpacing.sp.toPx()) }
                }
            }
        }
    }

    /** Whether text this tall folds. */
    fun folds(height: Float, metrics: Metrics): Boolean = height > metrics.limit
}

/**
 * Lays the text out whole when it fits the limit, otherwise folded with its control: one
 * measurement, so the row's first layout is already its height (iOS `UserBubbleFoldView`).
 * Closed, it lays out only [UserBubbleFold.closedLines], ending in an ellipsis below the cut, so its
 * last shown lines fade out mid-sentence; open or opening, it lays out the whole text.
 */
@Composable
private fun UserBubbleFoldView(
    metrics: UserBubbleFold.Metrics,
    fold: UserBubbleFold.State,
    folds: BooleanArray,
    content: @Composable (maxLines: Int) -> Unit,
    control: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fadePx = with(LocalDensity.current) { UserBubbleFold.fade.toPx() }
    // Read only while laying out and drawing.
    val openness = remember { Animatable(if (fold.isExpanded) 1f else 0f) }
    val isClosed by remember { derivedStateOf { openness.value == 0f } }
    val maxLines = if (isClosed && !fold.isExpanded) UserBubbleFold.closedLines else Int.MAX_VALUE
    val rowHeight = LocalRowHeightMotion.current
    val reduceMotion = rememberReduceMotion()
    LaunchedEffect(fold.isExpanded) {
        val target = if (fold.isExpanded) 1f else 0f
        if (openness.value == target) return@LaunchedEffect
        if (reduceMotion) {
            openness.snapTo(target)
            return@LaunchedEffect
        }
        rowHeight?.animatesItself = true
        try {
            openness.animateTo(target, DisclosureMotion.spring(fold.isExpanded).spec())
        } finally {
            rowHeight?.animatesItself = false
        }
    }
    Layout(
        contents = listOf(
            {
                Box(
                    Modifier.drawWithContent {
                        val open = openness.value
                        if (!folds[0] || open >= 1f) {
                            drawContent()
                            return@drawWithContent
                        }
                        // The fade masks a layer only as tall as the text shown. A layer as tall
                        // as the whole text can be bigger than the GPU's largest texture; Android
                        // then draws it without the layer, and the mask erases the window to black.
                        val end = metrics.folded + (size.height - metrics.folded) * open
                        val start = max(0f, end - fadePx)
                        val shown = Rect(0f, 0f, size.width, end)
                        drawIntoCanvas { canvas ->
                            canvas.save()
                            canvas.clipRect(shown)
                            canvas.saveLayer(shown, Paint())
                            drawContent()
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black,
                                    (start / end) to Color.Black,
                                    // The cut fades less as the fold opens, and not at all once open.
                                    1f to Color.Black.copy(alpha = open),
                                    startY = 0f,
                                    endY = end,
                                ),
                                size = shown.size,
                                blendMode = BlendMode.DstIn,
                            )
                            canvas.restore()
                            canvas.restore()
                        }
                    },
                ) { content(maxLines) }
            },
            {
                val label = if (fold.isExpanded) stringResource(R.string.transcript_show_less) else stringResource(R.string.transcript_show_more)
                Box(
                    Modifier
                        .clickable(onClick = fold.toggle)
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                        },
                    contentAlignment = Alignment.BottomStart,
                ) { control() }
            },
        ),
        modifier = modifier,
    ) { (contentMeasurables, controlMeasurables), constraints ->
        val text = contentMeasurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
        val isFolding = UserBubbleFold.folds(text.height.toFloat(), metrics)
        folds[0] = isFolding
        if (!isFolding) {
            return@Layout layout(text.width, text.height) { text.placeRelative(0, 0) }
        }
        val controlMin = UserBubbleFold.controlHeight.roundToPx()
        val ctl = controlMeasurables.first().measure(
            Constraints(minWidth = text.width, maxWidth = max(text.width, constraints.maxWidth), minHeight = controlMin),
        )
        val folded = metrics.folded
        val textHeight = (folded + (text.height - folded) * openness.value).toInt()
        val width = max(text.width, ctl.width)
        layout(width, textHeight + ctl.height) {
            text.placeRelative(0, 0)
            ctl.placeRelative(0, textHeight)
        }
    }
}

/**
 * A sent message's text with its `@session:<id>` mentions drawn as session chips showing their
 * titles (iOS `SessionMentionText` in the bubble), at the bubble's line spacing; plain text when
 * it mentions none.
 */
@Composable
private fun UserBubbleText(text: String, maxLines: Int, modifier: Modifier) {
    val colors = OpenCodeTheme.colors
    val marked = remember(text) { SessionLink.marked(text) }
    // Android lays out every line past maxLines unless it ellipsizes; the ellipsis sits under the fold.
    val overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis
    if (marked == null) {
        Text(text, style = UserBubbleTextStyle, color = colors.text, overflow = overflow, maxLines = maxLines, modifier = modifier)
        return
    }
    val style = currentMarkdownStyle()
    val density = LocalDensity.current
    val targets = LocalSessionLinks.current.targets
    val listener = rememberLinkListener()
    val presented = remember(marked, targets, colors, density) {
        MarkdownPresentation.present(
            marked,
            PresentationOptions(foreground = colors.text, textSize = OpenCodeTextStyles.body.fontSize, tonesBody = false),
            style, targets, density, listener, siteIcon = null,
        )
    }
    ArrivingText(presented, emptyList(), UserBubbleTextStyle, colors.text, modifier, overflow = overflow, maxLines = maxLines)
    ReportsSessionLinks(presented.sessionIDs)
}

/** iOS's selection settles for this long after Select: a collapse within it is the system's, and is undone. */
private const val SELECTION_SETTLE_SECONDS = 0.45

/** iOS presents the edit menu over the whole selection this long after Select (`presentMenu: true`). */
private const val SELECTION_MENU_DELAY_MILLIS = 480L

/**
 * Native range selection over a user bubble after choosing Select from its menu (iOS
 * `UserBubbleSelectableText`): the whole message selected in a read-only field (so the keyboard
 * stays down), with the system's floating text toolbar over it (Copy, Select All), until the
 * selection collapses, the field loses focus, Back is pressed, or the bubble scrolls off screen.
 * The reader can adjust the range with the platform handles; a Copy plays `tap` (through
 * [onCopy], `CopyTextTapped`).
 */
@Composable
private fun UserBubbleSelectableText(text: String, isPending: Boolean, onCopy: (String) -> Unit, onEndSelection: () -> Unit, modifier: Modifier) {
    val state: TextFieldState = rememberTextFieldState(text)
    val focus = remember { FocusRequester() }
    val end by rememberUpdatedState(onEndSelection)
    val copy by rememberUpdatedState(onCopy)
    val platformToolbar = LocalTextToolbar.current
    val haptics = remember { dependency(HapticsClient) }
    val toolbar = remember(platformToolbar) { UserBubbleSelectionToolbar(platformToolbar) { haptics.play(Haptic.tap) } }
    var hadFocus by remember { mutableStateOf(false) }
    var adjusted by remember { mutableStateOf(false) }
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    fun selectedText(): String {
        val selection = state.selection
        return state.text.substring(selection.min, selection.max)
    }

    fun presentMenu() {
        val bounds = coordinates?.takeIf { it.isAttached }?.boundsInRoot() ?: return
        toolbar.present(
            bounds,
            copy = {
                selectedText().takeIf { it.isNotEmpty() }?.let(copy)
                toolbar.dismiss()
            },
            selectAll = {
                state.edit { selectAll() }
                presentMenu()
            },
        )
    }

    LaunchedEffect(text) {
        if (state.text.toString() != text) state.edit { replace(0, length, text) }
        state.edit { selectAll() }
        focus.requestFocus()
        delay(SELECTION_MENU_DELAY_MILLIS)
        if (!adjusted) {
            state.edit { selectAll() }
            presentMenu()
        }
    }
    LaunchedEffect(Unit) {
        val activatedAt = System.nanoTime()
        snapshotFlow { state.selection }.collect { selection ->
            val full = state.text.length
            if (selection.collapsed) {
                // A collapse while it settles is the system's: select it all again.
                if ((System.nanoTime() - activatedAt) / 1e9 < SELECTION_SETTLE_SECONDS && !adjusted) state.edit { selectAll() } else end()
            } else if (selection.min != 0 || selection.max != full) {
                // The reader moved a handle: the field's own toolbar follows the new range.
                if (!adjusted) toolbar.dismiss()
                adjusted = true
            }
        }
    }
    DisposableEffect(toolbar) { onDispose { toolbar.dismiss() } }
    SelectionBackHandler { end() }

    CompositionLocalProvider(LocalTextToolbar provides toolbar) {
        BasicTextField(
            state = state,
            readOnly = true,
            textStyle = UserBubbleTextStyle.copy(color = OpenCodeTheme.colors.text.copy(alpha = if (isPending) 0.6f else 1f)),
            modifier = modifier
                .focusRequester(focus)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true else if (hadFocus) end()
                }
                .onGloballyPositioned {
                    coordinates = it
                    // Scrolled off screen: selection ends (iOS observes the list's offset).
                    val visible = it.boundsInWindow()
                    if (hadFocus && (visible.width <= 0f || visible.height <= 0f)) end()
                },
        )
    }
}

/**
 * The platform text toolbar, shown over the whole selection when Select opens it. The field's
 * own requests pass through, except a hide while this menu is up and the reader hasn't touched
 * the selection yet (the field doesn't know it put the menu there). The field's Copy plays `tap`.
 */
private class UserBubbleSelectionToolbar(private val platform: TextToolbar, private val onCopied: () -> Unit) : TextToolbar {
    private var holdsMenu = false

    override val status: TextToolbarStatus get() = platform.status

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        holdsMenu = false
        // iOS's selection view plays `tap` on Copy.
        val copy = onCopyRequested?.let { request -> { request(); onCopied() } }
        platform.showMenu(rect, copy, onPasteRequested, onCutRequested, onSelectAllRequested)
    }

    override fun hide() {
        if (!holdsMenu) platform.hide()
    }

    fun present(rect: Rect, copy: () -> Unit, selectAll: () -> Unit) {
        holdsMenu = true
        platform.showMenu(rect, onCopyRequested = copy, onPasteRequested = null, onCutRequested = null, onSelectAllRequested = selectAll)
    }

    fun dismiss() {
        if (!holdsMenu) return
        holdsMenu = false
        platform.hide()
    }
}

/** Back ends the selection (iOS: tapping elsewhere), ahead of the screen's own Back while it's up. */
@Composable
private fun SelectionBackHandler(onBack: () -> Unit) {
    val activity = LocalContext.current.findActivity() ?: return
    val current by rememberUpdatedState(onBack)
    DisposableEffect(activity) {
        val callback = OnBackInvokedCallback { current() }
        // Above the app's own handlers (default priority), below a full-screen viewer (overlay).
        activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT + 1, callback)
        onDispose { activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A Voice message's bubble, and every user bubble's fill: an agent's reveal lights it on arrival
 * (iOS `VoiceMessageStyle`). The badge on its corner ([VoiceMessageIndicator]) is what sets it
 * apart.
 */
enum class VoiceMessageStyle {
    /** The user's own bubble: same fill, no outline. */
    Standard,
    /** No fill, a hairline outline. */
    Outlined;

    companion object {
        val current = Standard
    }
}

/** iOS `VoiceMessageBackground`: the bubble's fill (or outline) and the reveal's light, one layer. */
@Composable
internal fun Modifier.voiceMessageBackground(
    style: VoiceMessageStyle,
    isPending: Boolean,
    reveal: AgentMessageReveal.Frame = AgentMessageReveal.Frame.rest,
    corners: UserSurfaceCorners = UserSurfaceCorners.round,
): Modifier {
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    // The shader compiles only for a bubble that reveals, not for every row.
    val glow = remember { lazy(LazyThreadSafetyMode.NONE) { AgentRevealGlow() } }
    // Only the shape: a run's corners tighten or round on their own.
    val full = TranscriptMetrics.bubbleCornerRadius
    val spec = PromotionMotion.animation<Dp>(reduceMotion)
    val topEnd by animateDpAsState(if (corners.top) UserMessageRun.tightRadius else full, spec, label = "topEnd")
    val bottomEnd by animateDpAsState(if (corners.bottom) UserMessageRun.tightRadius else full, spec, label = "bottomEnd")
    val fillAlpha by animateFloatAsState(if (isPending) 0.55f else 1f, PromotionMotion.animation(reduceMotion, LocalPromotionMorph.current), label = "fill")
    val shape = RoundedCornerShape(topStart = full, topEnd = topEnd, bottomEnd = bottomEnd, bottomStart = full)
    val increased = colors.highContrast
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        val resting = reveal == AgentMessageReveal.Frame.rest
        val body = reveal.body.toFloat()
        when (style) {
            VoiceMessageStyle.Standard -> if (resting) {
                drawOutline(outline, colors.userBubble.copy(alpha = colors.userBubble.alpha * fillAlpha))
            } else {
                // One layer, so the body's fade-in doesn't let the fill show through the light.
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = body })
                    drawOutline(outline, colors.userBubble.copy(alpha = colors.userBubble.alpha * fillAlpha))
                    // Clipped to the bubble's shape, as iOS clips the light (`clipShape`).
                    clipPath(Path().apply { addOutline(outline) }) { with(glow.value) { draw(reveal.light, corners, colors.isDark) } }
                    canvas.restore()
                }
            }
            VoiceMessageStyle.Outlined -> {
                if (!resting) {
                    drawIntoCanvas { canvas ->
                        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = body })
                        clipPath(Path().apply { addOutline(outline) }) { with(glow.value) { draw(reveal.light, corners, colors.isDark) } }
                        canvas.restore()
                    }
                }
                drawOutline(
                    outline,
                    SolidColor(if (increased) colors.secondaryText else colors.border),
                    alpha = (if (isPending) 0.55f else 1f) * body,
                    style = Stroke(1.dp.toPx()),
                )
            }
        }
    }
}
