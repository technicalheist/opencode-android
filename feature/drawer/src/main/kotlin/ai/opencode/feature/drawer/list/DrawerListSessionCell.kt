package ai.opencode.feature.drawer.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.ui.AccessibilityFocusRequest
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.motion.DrawerTimeReveal
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.drawer.style.DrawerRowEmphasis
import ai.opencode.feature.drawer.style.DrawerSelectionStyle
import ai.opencode.feature.drawer.style.DrawerTone
import ai.opencode.feature.drawer.style.DrawerTunables
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/*
 * A drawer session row. The project tile, the fading title, the status (the Working ring or a dot),
 * the selected highlight and the time the reveal slides in all live in the slide, which the time
 * reveal moves; the touch surface ([drawerRowInteraction]: tap, hold to select, lift to pin or
 * reorder) stays put over the row.
 *
 * Per-frame values (the reveal's offset and progress, the pin drag's translation, the card-edge
 * fade) are read in layer and draw blocks only, so neither a reveal nor a drag recomposes a row.
 * A row recomposes only when its [DrawerListRow] changes; the list keeps unchanged rows'
 * instances so they skip.
 */

/** What a session row does, as closures: the row never holds a store. */
class DrawerListRowActions(
    val select: () -> Unit,
    val pressed: () -> Unit,
    /** The hold: the row joins or leaves the selection ([DrawerSelection]). */
    val held: () -> Unit,
    /** The list is scrolling or flinging: a finger on it isn't a press. */
    val isListMoving: () -> Boolean,
    /** The held finger moved: start the pin drag at this list `y` (dp). */
    val beginDrag: (fingerY: Float) -> Unit,
    val moveDrag: (fingerY: Float, uptimeMillis: Long) -> Unit,
    /** The finger lifted at this list point (dp); [cancelled] when its stream was taken away. */
    val endDrag: (fingerX: Float, fingerY: Float, cancelled: Boolean) -> Unit,
    /** The row left the list mid-drag. */
    val dismantled: () -> Unit,
    val lifted: (String?) -> Unit,
    val togglePin: () -> Unit,
    val toggleRead: () -> Unit,
    val toggleMute: () -> Unit,
    val toggleHidden: () -> Unit,
    val delete: () -> Unit,
    val movePinUp: () -> Unit,
    val movePinDown: () -> Unit,
)

/** The time reveal as the rows read it: the list springs it back from where it is on release. */
interface DrawerListRevealValues {
    val offset: Float
    val progress: Float
}

object DrawerListSessionCell {
    /** The status slot (ring or dot). */
    val indicatorSize: Dp = 18.dp
}

@Composable
fun DrawerListSessionCell(
    row: DrawerListRow,
    actions: DrawerListRowActions,
    reveal: DrawerListRevealValues,
    pinDrag: DrawerPinDrag,
    listCoordinates: () -> LayoutCoordinates?,
    modifier: Modifier = Modifier,
    returnFocus: AccessibilityFocusRequest? = null,
) {
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val hold = remember(row.session.id) { DrawerRowHoldState() }
    val currentActions by rememberUpdatedState(actions)
    val key = remember(row.session.id) { DrawerPinDrag.ItemKey.Row(row.session.id) }
    val rowCoordinates = remember(row.session.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val selectedText = stringResource(R.string.drawer_row_selected)
    val notSelectedText = stringResource(R.string.drawer_row_not_selected)
    val selectLabel = stringResource(R.string.drawer_row_select_action)
    val pinLabel = if (row.isPinnedSection) stringResource(R.string.drawer_unpin) else stringResource(R.string.drawer_pin)
    val readLabel = if (row.unread) stringResource(R.string.drawer_mark_as_read) else stringResource(R.string.drawer_mark_as_unread)
    val muteLabel = if (row.muted) stringResource(R.string.drawer_unmute) else stringResource(R.string.drawer_mute)
    val hideLabel = if (row.hidden) stringResource(R.string.drawer_unhide) else stringResource(R.string.drawer_hide)
    val deleteLabel = stringResource(CoreR.string.common_delete)
    val moveUpLabel = stringResource(R.string.drawer_move_up)
    val moveDownLabel = stringResource(R.string.drawer_move_down)

    // A row leaving the list mid-drag or mid-landing ends it.
    DisposableEffect(row.session.id) {
        onDispose { currentActions.dismantled() }
    }

    val failed = row.unsent == DrawerListRow.Unsent.Failed
    val hasStatus = row.indicator != Indicator.none || failed
    val emphasis = DrawerRowEmphasis.of(selected = row.selected, muted = row.muted, hasIndicator = hasStatus)
    val weight = emphasis.weight(DrawerTunables.unreadWeight, DrawerTunables.readWeight)
    // While the drawer selects, the chosen rows' tint stands in for the open session's mark.
    val selection = if (row.selected && row.checked == null) DrawerTunables.selection else null
    val checkedTint = checkedRowTint()
    val checkedTintAlpha = rememberCheckedTintAlpha(row.checked == true)
    val onTint = selection == DrawerSelectionStyle.Tint
    val titleColor = when {
        onTint -> Color.White
        row.muted -> colors.secondaryText
        emphasis == DrawerRowEmphasis.Active && DrawerTunables.unreadTone != DrawerTone.Primary -> DrawerTunables.unreadTone.color
        else -> colors.text.copy(alpha = colors.text.alpha * emphasis.opacity(row.increasedContrast, DrawerTunables.readOpacity))
    }
    val titleStyle = DrawerListFonts.font(DrawerTunables.rowFont.font, weight)

    // The dot and the ring arrive on `MotionRole.MicroIn` and leave on its exit, cross-fading under
    // Reduce Motion, for the same session; another row's arrives in place.
    val previousID = remember { arrayOf(row.session.id) }
    val animateStatus = previousID[0] == row.session.id
    previousID[0] = row.session.id
    val statusPhase = if (hasStatus) MotionPhase.Enter else MotionPhase.Exit
    val statusSpec = if (animateStatus) MotionRole.MicroIn.animation<Float>(statusPhase, row.reducedMotion) else snap()
    val ringAlpha by animateFloatAsState(if (row.indicator == Indicator.running) 1f else 0f, statusSpec, label = "ring")
    val dotAlpha by animateFloatAsState(
        if (row.indicator == Indicator.attention || row.indicator == Indicator.unread) 1f else 0f,
        statusSpec,
        label = "dot",
    )
    val failedAlpha by animateFloatAsState(if (failed) 1f else 0f, statusSpec, label = "failed")
    // The title makes room for the status on the indicator's own spring.
    val statusInset by animateDpAsState(
        if (!hasStatus) 0.dp else DrawerListSessionCell.indicatorSize + DrawerTunables.statusGap,
        if (animateStatus && !row.reducedMotion) MotionRole.MicroIn.animation() else snap(),
        label = "status-inset",
    )

    // The card-edge fade: on with the first dp of slide, reaching full strength on `MotionRole.Swap`.
    val masking by remember(row.reducedMotion) { derivedStateOf { DrawerRevealFade.masks(row.reducedMotion, reveal.offset) } }
    val veil = remember { Animatable(1f) }
    LaunchedEffect(masking) {
        if (masking) {
            veil.snapTo(1f)
            veil.animateTo(0f, MotionRole.Swap.animation())
        } else {
            veil.snapTo(0f)
        }
    }

    val held = hold.isDragActive || row.isLifted
    val raisedSurface = if (colors.isDark) MaterialTheme.colorScheme.surfaceContainerHigh else colors.canvas
    // The raised surface leaves on swap's exit once a hold lets go, and a dropped row puts its
    // surface down the same way, shadowless.
    val surface = remember { Animatable(0f) }
    val wasLifted = remember { booleanArrayOf(false) }
    var landingSurface by remember { mutableStateOf(false) }
    if (row.isLifted) wasLifted[0] = true
    LaunchedEffect(held) {
        if (held) {
            landingSurface = false
            surface.snapTo(1f)
            return@LaunchedEffect
        }
        val landed = wasLifted[0]
        wasLifted[0] = false
        landingSurface = landed
        surface.animateTo(0f, MotionRole.Swap.animation(MotionPhase.Exit))
        landingSurface = false
    }
    val cornerRadius = DrawerMetrics.rowCornerRadius
    val rowEdge = DrawerMetrics.rowEdge
    val rowGap = DrawerTunables.rowGap

    Box(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                translationY = pinDrag.translation(key) * density.density
                alpha = pinDrag.alpha(key)
                // The dragged row grows as it lifts.
                val grow = if (row.isLifted) DrawerLiftedRowView.apply(pinDrag.liftProgress).scale else 1f
                scaleX = grow
                scaleY = grow
                compositingStrategy = if (masking) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                if (!masking) return@drawWithContent
                val (solidEnd, rampEnd) = DrawerRevealFade.stops(
                    rowMaxX = size.width - rowEdge.toPx(),
                    offset = reveal.offset * density.density,
                    fade = max(DrawerTunables.titleFade.toPx(), 0.5.dp.toPx()),
                    ramp = DrawerTunables.revealFadeRamp.toPx(),
                )
                val strength = veil.value.coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.Black,
                        (solidEnd / rampEnd).coerceIn(0f, 1f) to Color.Black,
                        1f to Color.Black.copy(alpha = strength),
                        startX = 0f,
                        endX = rampEnd,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .padding(horizontal = rowEdge, vertical = rowGap / 2),
    ) {
        // The row's frame: the touch surface and what TalkBack reads, over the slide.
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.rowHeight)
                // The held row's shadow, always in the chain (only its elevation changes), so the
                // hold that raises it mid-gesture never changes the modifiers around the touch handler.
                // A lifted row casts its own tunable shadow instead (`drawLiftedRowSurface`).
                .graphicsLayer {
                    shadowElevation = if (row.isLifted || landingSurface) 0f else 8.dp.toPx() * surface.value
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius)
                    clip = false
                }
                .onGloballyPositioned { rowCoordinates[0] = it }
                .drawBehind {
                    val tintAlpha = checkedTintAlpha()
                    if (tintAlpha > 0f) drawRoundRect(checkedTint.copy(alpha = checkedTint.alpha * tintAlpha), cornerRadius = CornerRadius(cornerRadius.toPx()))
                    when {
                        row.isLifted -> drawLiftedRowSurface(raisedSurface, cornerRadius.toPx(), DrawerLiftedRowView.apply(pinDrag.liftProgress))
                        surface.value > 0f -> drawRoundRect(
                            raisedSurface.copy(alpha = raisedSurface.alpha * surface.value.coerceIn(0f, 1f)),
                            cornerRadius = CornerRadius(cornerRadius.toPx()),
                        )
                    }
                }
                .drawerRowInteraction(
                    interaction = DrawerRowInteraction(
                        sessionID = row.session.id,
                        reorderable = row.unsent == null,
                        holdable = row.unsent == null,
                        isPinnedSection = row.isPinnedSection,
                        select = { currentActions.select() },
                        pressed = { currentActions.pressed() },
                        held = { currentActions.held() },
                        isListMoving = { currentActions.isListMoving() },
                        beginDrag = { currentActions.beginDrag(it.y) },
                        moveDrag = { point, time -> currentActions.moveDrag(point.y, time) },
                        endDrag = { point, cancelled -> currentActions.endDrag(point.x, point.y, cancelled) },
                        lifted = { currentActions.lifted(it) },
                    ),
                    hold = hold,
                    list = listCoordinates,
                    row = { rowCoordinates[0] },
                )
                // Ahead of the clearing below, which would drop its marker.
                .accessibilityFocusRequest(returnFocus)
                .clearAndSetSemantics {
                    testTag = "drawer-session-row-${row.session.id}"
                    contentDescription = row.session.title
                    // `selected` would make the row checkable, and TalkBack would offer "double-tap
                    // to toggle" on a row that opens. While the drawer selects, the words say
                    // whether the row is chosen.
                    val chosen = when (row.checked) {
                        true -> selectedText
                        false -> notSelectedText
                        null -> if (row.selected) selectedText else null
                    }
                    stateDescription = listOfNotNull(chosen, row.accessibilityValue.ifEmpty { null }).joinToString(", ")
                    role = Role.Button
                    onClick { currentActions.select(); true }
                    if (row.checked == null && row.unsent == null) onLongClick(selectLabel) { currentActions.held(); true }
                    customActions = buildList {
                        if (row.unsent == null) {
                            add(CustomAccessibilityAction(pinLabel) { currentActions.togglePin(); true })
                            add(CustomAccessibilityAction(readLabel) { currentActions.toggleRead(); true })
                            add(CustomAccessibilityAction(muteLabel) { currentActions.toggleMute(); true })
                            add(CustomAccessibilityAction(hideLabel) { currentActions.toggleHidden(); true })
                        }
                        add(CustomAccessibilityAction(deleteLabel) { currentActions.delete(); true })
                        if (row.isPinnedSection) {
                            add(CustomAccessibilityAction(moveUpLabel) { currentActions.movePinUp(); true })
                            add(CustomAccessibilityAction(moveDownLabel) { currentActions.movePinDown(); true })
                        }
                    }
                },
        ) {
            // The slide: everything the time reveal moves (by layer translation only).
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        translationX = if (row.reducedMotion) 0f else reveal.offset * density.density
                    },
            ) {
                SelectionMark(selection, cornerRadius)
                RevealedTime(row.time, reducedMotion = row.reducedMotion, progress = { reveal.progress })
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = DrawerMetrics.rowHeight)
                    .graphicsLayer {
                        translationX = if (row.reducedMotion) 0f else reveal.offset * density.density
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = DrawerMetrics.textInset, end = DrawerMetrics.trailingInset),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The check takes the project tile's place while the drawer selects.
                    val side = DrawerListProjectTile.side(DrawerTunables.projectIconSize, density.fontScale)
                    SelectionLead(
                        checked = row.checked,
                        restWidth = if (row.project != null) side + DrawerTunables.projectIconGap else 0.dp,
                        selectWidth = side + DrawerTunables.projectIconGap,
                        checkSize = 20.dp,
                    ) {
                        if (row.project != null) {
                            DrawerListProjectTile(
                                project = row.project,
                                server = row.server,
                                muted = row.muted,
                                side = side,
                                increasedContrast = row.increasedContrast,
                                reducedMotion = row.reducedMotion,
                            )
                        }
                    }
                    DrawerListTitleView(
                        text = row.session.title,
                        style = titleStyle,
                        color = titleColor,
                        fade = DrawerTunables.titleFade,
                        time = row.time,
                        reducedMotion = row.reducedMotion,
                        modifier = Modifier.weight(1f).padding(end = statusInset.coerceAtLeast(0.dp)),
                        revealProgress = { reveal.progress },
                    )
                }
                // The status, at the trailing end of the title's line.
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = DrawerMetrics.trailingInset)
                        .size(DrawerListSessionCell.indicatorSize),
                    contentAlignment = Alignment.Center,
                ) {
                    DrawerListActivityRing(
                        isSpinning = row.indicator == Indicator.running && !row.reducedMotion,
                        opaqueTail = row.increasedContrast,
                        modifier = Modifier.size(14.dp).graphicsLayer { alpha = ringAlpha },
                    )
                    OpenCodeIcon(
                        SFSymbol.CircleFill,
                        contentDescription = null,
                        size = (11 * density.fontScale).dp,
                        tint = if (row.indicator == Indicator.attention) colors.warning else colors.signal,
                        modifier = Modifier.graphicsLayer { alpha = dotAlpha },
                    )
                    OpenCodeIcon(
                        "exclamationmark.circle.fill",
                        contentDescription = null,
                        size = (15 * density.fontScale).dp,
                        tint = colors.danger,
                        modifier = Modifier.graphicsLayer { alpha = failedAlpha },
                    )
                }
            }
        }
    }
}

/** The selected row's mark: the shipped fill, the accent behind it, an outline, or a bar at its leading edge. */
@Composable
private fun SelectionMark(selection: DrawerSelectionStyle?, cornerRadius: Dp) {
    if (selection == null) return
    val colors = OpenCodeTheme.colors
    val indicator = MaterialTheme.colorScheme.secondaryContainer
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val radius = CornerRadius(cornerRadius.toPx())
                when (selection) {
                    // The desktop sidebar's active row: a faint pressed overlay
                    // (`--v2-overlay-simple-overlay-pressed`) in the row's small radius.
                    DrawerSelectionStyle.Fill -> drawRoundRect(colors.text.copy(alpha = if (colors.isDark) 0.10f else 0.07f), cornerRadius = radius)
                    DrawerSelectionStyle.Tint -> drawRoundRect(colors.accent, cornerRadius = radius)
                    DrawerSelectionStyle.Outline -> {
                        val inset = 0.5.dp.toPx()
                        drawRoundRect(
                            colors.text.copy(alpha = min(DrawerTunables.selectionOpacity * 2.5f, 1f)),
                            topLeft = Offset(inset, inset),
                            size = Size(size.width - inset * 2, size.height - inset * 2),
                            cornerRadius = CornerRadius(max(cornerRadius.toPx() - inset, 0f)),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                    DrawerSelectionStyle.Bar -> {
                        val width = 3.dp.toPx()
                        val x = if (rtl) size.width - width else 0f
                        drawRoundRect(
                            colors.accent,
                            topLeft = Offset(x, 10.dp.toPx()),
                            size = Size(width, max(0f, size.height - 20.dp.toPx())),
                            cornerRadius = CornerRadius(1.5.dp.toPx()),
                        )
                    }
                }
            },
    )
}

/**
 * The time the reveal uncovers: right-aligned in the gutter the slide opens,
 * [DrawerTimeReveal.SPACING] before the row (mirrored in RTL). Under reduce motion the title
 * cross-fades into it instead ([DrawerListTitleView]), so this stays hidden.
 */
@Composable
private fun RevealedTime(time: String, reducedMotion: Boolean, progress: () -> Float) {
    if (reducedMotion) return
    Text(
        text = time,
        style = DrawerListFonts.monospacedDigits(DrawerListFonts.font(OpenCodeTextStyles.footnote, FontWeight.Normal)),
        color = OpenCodeTheme.colors.secondaryText,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity, minHeight = 0))
                val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
                layout(constraints.minWidth, height) {
                    val spacing = DrawerTimeReveal.SPACING.dp.roundToPx()
                    // `placeRelative` mirrors in RTL: the gutter is on the leading side either way.
                    placeable.placeRelative(-spacing - placeable.width, (height - placeable.height) / 2)
                }
            }
            .graphicsLayer { alpha = progress().coerceIn(0f, 1f) },
    )
}

/**
 * The card-edge fade while the time reveal slides a row under the card: the whole row fades out
 * over the last `titleFade` before its resting trailing edge (the card's edge), the ramp moving
 * onto that edge over the first `revealFadeRamp` of slide so no point of slide steps the row's
 * alpha.
 */
internal object DrawerRevealFade {
    /** A row at rest, or under Reduce Motion (the time cross-fades in place), is unmasked. */
    fun masks(reducedMotion: Boolean, offset: Float): Boolean = !reducedMotion && offset != 0f

    /** Where the mask is solid until and where its ramp to clear ends, in the row's coordinates (one unit throughout). */
    fun stops(rowMaxX: Float, offset: Float, fade: Float, ramp: Float): Pair<Float, Float> {
        val shift = fade * min(max(offset, 0f) / max(ramp, 1f), 1f)
        return (rowMaxX - shift) to max(rowMaxX + fade - shift, 1f)
    }
}
