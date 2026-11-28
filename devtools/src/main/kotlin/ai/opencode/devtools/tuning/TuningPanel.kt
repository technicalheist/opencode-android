package ai.opencode.devtools.tuning

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.tuning.TunableDescriptor
import ai.opencode.core.tuning.TuneKind
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.tuning.TuningTitle
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeSwitchDefaults
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.openCodeTapTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val ChangedColor = Color(0xFFFF9500)

/** What the panel shows as. */
private enum class TuningForm { Panel, Pill, Tab }

/** `.spring(duration: 0.38, bounce: 0.12)`. */
private val SettleSpring = MotionSpring(duration = 0.38, bounce = 0.12)

/**
 * The throw spring, `.interpolatingSpring(stiffness: 240, damping: 26)`, in duration/bounce terms:
 * ω = √240 (duration 2π/ω), ζ = 26 / (2√240) (bounce 1 − ζ).
 */
private val ThrowSpring = MotionSpring(duration = 2 * PI / sqrt(240.0), bounce = 1 - 26 / (2 * sqrt(240.0)))

/** A centre is settled within a tenth of a dp. */
private val OffsetThreshold = Offset(0.1f, 0.1f)

/** How the next settle runs: set by a throw, read once by the settle effect. Not state. */
private class PendingSettle {
    var spec: AnimationSpec<Offset>? = null
    var velocity: Offset? = null
    var snap = false
    /** Forms measured at least once. */
    val measured = mutableSetOf<TuningForm>()
}

/** One drag's callbacks, in dp (velocity dp a second). */
private class ThrowHandler(
    val onStart: () -> Unit,
    val onDrag: (translation: Offset) -> Unit,
    val onEnd: (translation: Offset, velocity: Offset) -> Unit,
    val onCancel: () -> Unit,
)

/**
 * The floating Tuning Panel. Drag and throw it to a side, past an edge to tuck it into a tab, or
 * collapse it into a pill. Tap a changed (orange) value to reset it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuningPanel(store: StoreOf<TuningFeature.State, TuningFeature.Action>, modifier: Modifier = Modifier) {
    val state by store.collectState()
    LaunchedEffect(store) { store.send(TuningFeature.Action.Task) }
    val reduceMotion = rememberReduceMotion()
    val settleSpec: AnimationSpec<Offset> =
        if (reduceMotion) Motion.easeInOut(0.2) else SettleSpring.spec(OffsetThreshold)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        // The safe area, and the keyboard's final top.
        val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
        val imeTarget = WindowInsets.imeAnimationTarget.getBottom(density) / density.density
        val screenWidth = maxWidth.value
        val screenHeight = maxHeight.value
        val safeArea = Rect(
            safe.getLeft(density, direction) / density.density,
            safe.getTop(density) / density.density,
            screenWidth - safe.getRight(density, direction) / density.density,
            screenHeight - safe.getBottom(density) / density.density,
        )
        val layout = TuningPlacementLayout.of(
            safeArea = safeArea,
            screen = Rect(0f, 0f, screenWidth, screenHeight),
            keyboardTop = if (imeTarget > 0f) screenHeight - imeTarget else null,
        )
        val placement = state.placement
        val form = when {
            placement.isTucked -> TuningForm.Tab
            state.isCollapsed -> TuningForm.Pill
            else -> TuningForm.Panel
        }
        var panelSize by remember { mutableStateOf(Size(300f, 160f)) }
        var pillSize by remember { mutableStateOf(Size(110f, 44f)) }
        fun sizeOf(form: TuningForm) = when (form) {
            TuningForm.Panel -> panelSize
            TuningForm.Pill -> pillSize
            TuningForm.Tab -> TuningPlacementLayout.TabSize
        }
        val target = layout.center(placement, sizeOf(form))

        // The centre springs to the target; while a finger holds it, `drag` follows the finger.
        val position = remember { Animatable(target, Offset.VectorConverter, OffsetThreshold) }
        var drag by remember { mutableStateOf(Offset.Zero) }
        var dragging by remember { mutableStateOf(false) }
        val pending = remember { PendingSettle() }
        LaunchedEffect(target, dragging) {
            if (dragging) return@LaunchedEffect
            if (drag != Offset.Zero) {
                position.snapTo(position.value + drag)
                drag = Offset.Zero
            }
            val spec = pending.spec ?: settleSpec
            val velocity = pending.velocity ?: position.velocity
            val snap = pending.snap
            pending.spec = null
            pending.velocity = null
            pending.snap = false
            if (snap) position.snapTo(target) else position.animateTo(target, spec, velocity)
        }

        // Follows the finger, then throws to the layout's landing; a spring carries it there from
        // the release speed along its path.
        fun throwHandler(size: Size, tap: (() -> Unit)?) = ThrowHandler(
            onStart = { dragging = true },
            onDrag = { drag = it },
            onEnd = { translation, velocity ->
                if (tap != null && translation.getDistance() < 4f) {
                    dragging = false
                    tap()
                } else {
                    val released = position.value + translation
                    val landing = layout.placement(releasedAt = released, velocity = velocity, size = size)
                    val landed = layout.center(landing, if (landing.isTucked) TuningPlacementLayout.TabSize else size)
                    val path = landed - released
                    val distance = path.getDistance()
                    val along = if (distance > 1f) (velocity.x * path.x + velocity.y * path.y) / distance else 0f
                    val initial = if (distance > 1f) (along / distance).coerceIn(0f, 12f) else 0f
                    // A unit initial velocity is a share of each axis' distance a second.
                    pending.spec = if (reduceMotion) Motion.easeOut(0.2) else ThrowSpring.spec(OffsetThreshold)
                    pending.velocity = path * initial
                    dragging = false
                    store.send(TuningFeature.Action.PlacementChanged(landing))
                }
            },
            onCancel = { dragging = false },
        )
        val untuck = {
            dragging = false
            store.send(TuningFeature.Action.PlacementChanged(placement.copy(isTucked = false)))
        }
        // The tab slides with the finger along its edge's normal; a tap, pull or flick inward
        // brings the panel back, anything else settles it back.
        val tabHandler = ThrowHandler(
            onStart = { dragging = true },
            onDrag = { translation ->
                val inward = if (placement.side == TuningPlacement.Side.Leading) translation.x.coerceAtLeast(0f) else translation.x.coerceAtMost(0f)
                drag = Offset(inward, 0f)
            },
            onEnd = { translation, velocity ->
                if (TuningPlacementLayout.pullsOut(translation.x, velocity.x, placement.side)) {
                    untuck()
                } else {
                    dragging = false
                }
            },
            onCancel = { dragging = false },
        )
        val panelHandler = rememberUpdatedState(throwHandler(panelSize, null))
        val pillHandler = rememberUpdatedState(throwHandler(pillSize) { store.send(TuningFeature.Action.ExpandTapped) })
        val tabHandlerState = rememberUpdatedState(tabHandler)

        val origin = TransformOrigin(if (placement.side == TuningPlacement.Side.Leading) 0f else 1f, 0.5f)
        val fadeSpec: FiniteAnimationSpec<Float> = if (reduceMotion) Motion.easeInOut(0.2) else SettleSpring.spec<Float>()
        AnimatedVisibility(state.isPanelShown, Modifier.fillMaxSize(), enter = EnterTransition.None, exit = ExitTransition.None) {
            val visibility: AnimatedVisibilityScope = this
            Crossfade(form, Modifier.fillMaxSize(), animationSpec = fadeSpec, label = "tuning-form") { shown ->
                val size = sizeOf(shown)
                val placed = Modifier
                    .offset {
                        val center = position.value + drag
                        IntOffset(((center.x - size.width / 2) * density.density).roundToInt(), ((center.y - size.height / 2) * density.density).roundToInt())
                    }
                    .then(
                        with(visibility) {
                            Modifier.animateEnterExit(
                                enter = if (reduceMotion) fadeIn(Motion.easeInOut(0.2)) else scaleIn(SettleSpring.spec<Float>(), initialScale = 0.6f, transformOrigin = origin) + fadeIn(fadeSpec),
                                exit = if (reduceMotion) fadeOut(Motion.easeInOut(0.2)) else scaleOut(SettleSpring.spec<Float>(), targetScale = 0.6f, transformOrigin = origin) + fadeOut(fadeSpec),
                            )
                        },
                    )
                fun measured(update: (Size) -> Unit, current: Size): Modifier = Modifier.onSizeChanged {
                    val next = Size(it.width / density.density, it.height / density.density)
                    // Only the panel's first size lands in place; a form measured later moves on
                    // the settle spring, or it would jump in one frame.
                    val first = pending.measured.isEmpty()
                    pending.measured.add(shown)
                    if (next != current) {
                        if (first && !position.isRunning && !dragging) pending.snap = true
                        update(next)
                    }
                }
                // Full-screen bounds: while Crossfade fades a form its layer draws offscreen, clipped to
                // these bounds, and the form sits away from the origin (`offset`). Wrapping only the form
                // clipped the panel to a corner fragment as it came out of the tab.
                Box(Modifier.fillMaxSize()) {
                    when (shown) {
                        TuningForm.Panel -> PanelForm(
                            store = store,
                            state = state,
                            // `min(272, width − 2 × margin)` wide, `min(0.4 × height, 340)` tall in
                            // the safe area.
                            width = minOf(272f, safeArea.width - 2 * TuningPlacementLayout.MARGIN).dp,
                            maxHeight = minOf(safeArea.height * 0.4f, 340f).dp,
                            drag = Modifier.throwGesture(4.dp, panelHandler),
                            modifier = placed.then(measured({ panelSize = it }, panelSize)),
                        )
                        TuningForm.Pill -> TuningPill(
                            state = state,
                            expand = { store.send(TuningFeature.Action.ExpandTapped) },
                            modifier = placed.then(measured({ pillSize = it }, pillSize)).throwGesture(0.dp, pillHandler),
                        )
                        TuningForm.Tab -> TuningTab(
                            side = placement.side,
                            pullOut = untuck,
                            modifier = placed.throwGesture(0.dp, tabHandlerState),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Starts after [minimumDistance], reports translation since touch-down and release velocity, in dp.
 */
private fun Modifier.throwGesture(minimumDistance: Dp, handler: State<ThrowHandler>): Modifier =
    this.then(
        Modifier.pointerInput(minimumDistance) {
            val slop = minimumDistance.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tracker = VelocityTracker()
                // Summed deltas: the element moves with the finger, so its local positions don't.
                var translation = Offset.Zero
                tracker.addPosition(down.uptimeMillis, translation)
                var started = slop == 0f
                var ended = false
                if (started) {
                    down.consume()
                    handler.value.onStart()
                }
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        translation += change.positionChangeIgnoreConsumed()
                        tracker.addPosition(change.uptimeMillis, translation)
                        if (!started && translation.getDistance() >= slop) {
                            started = true
                            handler.value.onStart()
                        }
                        if (started) {
                            change.consume()
                            handler.value.onDrag(translation / density)
                        }
                        if (!change.pressed) {
                            if (started) {
                                val velocity = tracker.calculateVelocity()
                                ended = true
                                handler.value.onEnd(translation / density, Offset(velocity.x, velocity.y) / density)
                            }
                            break
                        }
                    }
                } finally {
                    if (started && !ended) handler.value.onCancel()
                }
            }
        },
    )

@Composable
private fun PanelForm(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    state: TuningFeature.State,
    width: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    drag: Modifier = Modifier,
) {
    val tuning = TuningStore.shared
    // Observed: registration and value changes redraw the panel.
    tuning.catalogRevision
    val overrides = tuning.observedOverrides
    val groups = TuningPanelGroups.shown(
        registered = tuning.descriptors.map { it.group }.distinct(),
        context = tuning.context,
        showsAll = state.showsAll,
        hidden = tuning.hiddenGroups,
    )
    val changed = overrides.keys.count { key -> key.substringBefore('.') in groups }
    val title = if (state.showsAll) "All" else tuning.context.displayTitle
    Surface(
        modifier = modifier.semantics { contentDescription = "Tuning Panel" },
        shape = OpenCodeShape.panel,
        color = OpenCodeSurfaces.color,
        shadowElevation = 6.dp,
        border = OpenCodeSurfaces.border,
    ) {
        Column(Modifier.width(width).heightIn(max = maxHeight)) {
            PanelHeader(store, state, title, changed, groups, drag)
            SlowMotionControl(state.slowMotion) { store.send(TuningFeature.Action.SlowMotionSelected(it)) }
            HorizontalDivider()
            if (groups.isEmpty()) {
                Text(
                    if (state.showsAll) "Nothing to tune yet" else "Nothing to tune here",
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    for (group in groups) {
                        item(key = "header-$group") {
                            GroupHeader(store, group, isFolded = group in state.foldedGroups, overrides = overrides)
                        }
                        if (group !in state.foldedGroups) {
                            val presets = tuning.presets.filter { it.group == group }
                            for (set in presets) {
                                item(key = "presets-${set.key}") { PresetChips(store, set, overrides) }
                            }
                            for ((sectionTitle, rows) in sections(tuning.descriptors.filter { it.group == group })) {
                                if (sectionTitle.isNotEmpty()) {
                                    item(key = "section-$group-$sectionTitle") {
                                        Text(
                                            sectionTitle,
                                            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                items(rows, key = { it.key }) { descriptor ->
                                    TunableRow(descriptor, overrides[descriptor.key]) { value ->
                                        store.send(TuningFeature.Action.ValueChanged(descriptor.key, value))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (state.didCopy) {
                Text("Copied", Modifier.padding(12.dp).align(Alignment.CenterHorizontally), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The speed for the whole app, its caption orange while slowed. */
@Composable
private fun SlowMotionControl(slowMotion: SlowMotion, select: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Slow Motion",
            Modifier.clearAndSetSemantics {},
            style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold),
            color = if (slowMotion.isSlowed) ChangedColor else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = "Slow Motion" }) {
            SlowMotion.scales.forEachIndexed { index, scale ->
                SegmentedButton(
                    selected = slowMotion.scale == scale,
                    onClick = { select(scale) },
                    shape = SegmentedButtonDefaults.itemShape(index, SlowMotion.scales.size),
                    icon = {},
                ) { Text(SlowMotion.label(scale), style = OpenCodeTextStyles.caption1, maxLines = 1) }
            }
        }
    }
}

/**
 * The panel folded, naming what it tunes and how many values are changed. A still finger unfolds
 * it.
 */
@Composable
private fun TuningPill(state: TuningFeature.State, expand: () -> Unit, modifier: Modifier = Modifier) {
    val tuning = TuningStore.shared
    tuning.catalogRevision
    val groups = TuningPanelGroups.shown(
        registered = tuning.descriptors.map { it.group }.distinct(),
        context = tuning.context,
        showsAll = state.showsAll,
        hidden = tuning.hiddenGroups,
    )
    val changed = tuning.observedOverrides.keys.count { key -> key.substringBefore('.') in groups }
    val title = if (state.showsAll) "All" else tuning.context.displayTitle
    // A 34 tall capsule (caption semibold, 12 padding, 6 spacing) inside a 48 dp touch target.
    Box(
        modifier
            .heightIn(min = 48.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Tuning Panel"
                stateDescription = if (changed > 0) "$changed changed" else ""
                role = Role.Button
                onClick(label = "Expand") { expand(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = OpenCodeShape.control,
            color = OpenCodeSurfaces.color,
            shadowElevation = 6.dp,
            border = OpenCodeSurfaces.border,
        ) {
            Row(
                Modifier.height(34.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val caption = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold)
                OpenCodeIcon(SFSymbol.SliderHorizontal3, null, size = OpenCodeIconSize.small)
                Text(title, style = caption, maxLines = 1)
                if (changed > 0) {
                    Text("$changed", style = OpenCodeTextStyles.caption2.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"), color = ChangedColor)
                }
            }
        }
    }
}

/**
 * The panel tucked against an edge, a slim tab with a chevron pointing inward to pull it back out.
 */
@Composable
private fun TuningTab(side: TuningPlacement.Side, pullOut: () -> Unit, modifier: Modifier = Modifier) {
    val leading = side == TuningPlacement.Side.Leading
    Box(
        modifier
            .size(TuningPlacementLayout.TabSize.width.dp, TuningPlacementLayout.TabSize.height.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Tuning Panel"
                role = Role.Button
                // TalkBack's click label (“Double tap to …”).
                onClick(label = "Bring the panel back") { pullOut(); true }
            },
        contentAlignment = if (leading) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Surface(
            modifier = Modifier.size(TuningPlacementLayout.TAB_GLASS_WIDTH.dp, TuningPlacementLayout.TabSize.height.dp),
            shape = OpenCodeShape.control,
            color = OpenCodeSurfaces.color,
            shadowElevation = 6.dp,
            border = OpenCodeSurfaces.border,
        ) {
            Box(contentAlignment = Alignment.Center) {
                OpenCodeIcon(
                    if (leading) SFSymbol.ChevronCompactRight else SFSymbol.ChevronCompactLeft,
                    null,
                    size = OpenCodeIconSize.small,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PanelHeader(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    state: TuningFeature.State,
    title: String,
    changed: Int,
    groups: List<String>,
    drag: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // The handle is the title (slider glyph + footnote semibold); the changed count lives on
        // groups.
        Row(
            drag.weight(1f).heightIn(min = 48.dp).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpenCodeIcon(SFSymbol.SliderHorizontal3, null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.secondaryText)
            Text(title, style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Caption semibold in a 24 tall capsule, filled while on.
        Box(
            Modifier
                .clickable(role = Role.Button) { store.send(TuningFeature.Action.ShowsAllToggled) }
                .heightIn(min = 48.dp)
                .semantics { contentDescription = "Show All Groups"; selected = state.showsAll },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "All",
                Modifier
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (state.showsAll) 0.14f else 0f), CircleShape)
                    .height(24.dp)
                    .wrapContentHeight()
                    .padding(horizontal = 8.dp),
                style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold),
                color = if (state.showsAll) MaterialTheme.colorScheme.onSurface else OpenCodeTheme.colors.secondaryText,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { OpenCodeIcon(SFSymbol.Ellipsis, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy All Values") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyValuesTapped(null)) }, leadingIcon = { OpenCodeIcon(SFSymbol.DocOnDoc, null) })
                DropdownMenuItem(text = { Text("Copy All as Kotlin") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyKotlinTapped(null)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ChevronLeftForwardslashChevronRight, null) })
                DropdownMenuItem(
                    text = { Text("Reset All", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; store.send(TuningFeature.Action.ResetTapped(null)) },
                    leadingIcon = { OpenCodeIcon(SFSymbol.ArrowCounterclockwise, null, tint = MaterialTheme.colorScheme.error) },
                )
                val hidden = TuningStore.shared.hiddenGroups.sorted()
                if (hidden.isNotEmpty()) HorizontalDivider()
                for (group in hidden) {
                    DropdownMenuItem(text = { Text("Show ${TuningTitle.format(group)}") }, onClick = { menu = false; store.send(TuningFeature.Action.GroupHidden(group, false)) }, leadingIcon = { OpenCodeIcon(SFSymbol.Eye, null) })
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Developer Tools") }, onClick = { menu = false; store.send(TuningFeature.Action.DeveloperToolsTapped) }, leadingIcon = { OpenCodeIcon(SFSymbol.HammerFill, null) })
                DropdownMenuItem(text = { Text("Turn Off Tuning") }, onClick = { menu = false; store.send(TuningFeature.Action.TurnOffTapped) }, leadingIcon = { OpenCodeIcon(SFSymbol.Power, null) })
            }
        }
        IconButton(onClick = { store.send(TuningFeature.Action.CollapseTapped) }) { OpenCodeIcon(SFSymbol.ArrowDownRightAndArrowUpLeft, "Collapse") }
        IconButton(onClick = { store.send(TuningFeature.Action.CloseTapped) }) { OpenCodeIcon(SFSymbol.Xmark, "Close") }
    }
    if (groups.isEmpty()) Spacer(Modifier.size(0.dp))
}

@Composable
private fun GroupHeader(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    group: String,
    isFolded: Boolean,
    overrides: Map<String, TuneValue>,
) {
    val title = TuningTitle.format(group)
    val changed = overrides.keys.count { it.substringBefore('.') == group }
    val canUndo = TuningStore.shared.canUndoRoll(group)
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f)
                .clickable(onClickLabel = if (isFolded) "Expand" else "Collapse") { store.send(TuningFeature.Action.GroupFolded(group, !isFolded)) }
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp)
                .semantics { contentDescription = title },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OpenCodeIcon(if (isFolded) SFSymbol.ChevronRight else SFSymbol.ChevronDown, null, size = OpenCodeIconSize.small)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (changed > 0) Text("$changed", style = MaterialTheme.typography.labelMedium, color = ChangedColor)
        }
        if (canUndo) {
            IconButton(onClick = { store.send(TuningFeature.Action.UndoRollTapped(group)) }) { OpenCodeIcon(SFSymbol.ArrowUturnBackward, "Undo $title Roll") }
        }
        IconButton(onClick = { store.send(TuningFeature.Action.RandomizeTapped(group)) }) { OpenCodeIcon(SFSymbol.DieFace5, "Randomize $title") }
        Box {
            IconButton(onClick = { menu = true }) { OpenCodeIcon(SFSymbol.Ellipsis, "$title Actions") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                if (canUndo) DropdownMenuItem(text = { Text("Undo Roll") }, onClick = { menu = false; store.send(TuningFeature.Action.UndoRollTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ArrowUturnBackward, null) })
                DropdownMenuItem(text = { Text("Randomize $title") }, onClick = { menu = false; store.send(TuningFeature.Action.RandomizeTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.DieFace5, null) })
                DropdownMenuItem(text = { Text("Copy Values") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyValuesTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.DocOnDoc, null) })
                DropdownMenuItem(text = { Text("Copy as Kotlin") }, onClick = { menu = false; store.send(TuningFeature.Action.CopyKotlinTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ChevronLeftForwardslashChevronRight, null) })
                DropdownMenuItem(text = { Text("Reset $title") }, onClick = { menu = false; store.send(TuningFeature.Action.ResetTapped(group)) }, leadingIcon = { OpenCodeIcon(SFSymbol.ArrowCounterclockwise, null) })
                DropdownMenuItem(text = { Text("Hide Group") }, onClick = { menu = false; store.send(TuningFeature.Action.GroupHidden(group, true)) }, leadingIcon = { OpenCodeIcon(SFSymbol.EyeSlash, null) })
            }
        }
    }
}

@Composable
private fun PresetChips(
    store: StoreOf<TuningFeature.State, TuningFeature.Action>,
    presets: ai.opencode.core.tuning.TuningPresets,
    overrides: Map<String, TuneValue>,
) {
    val matching = presets.matching(overrides)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        presets.presets.forEachIndexed { index, preset ->
            FilterChip(
                selected = matching == index,
                onClick = { store.send(TuningFeature.Action.PresetSelected(presets.key, index)) },
                label = { Text("${index + 1} ${preset.name}") },
                modifier = Modifier.semantics { contentDescription = "${presets.label} ${index + 1}: ${preset.name}" },
            )
        }
    }
}

@Composable
private fun TunableRow(descriptor: TunableDescriptor, override: TuneValue?, set: (TuneValue?) -> Unit) {
    val current = override ?: descriptor.defaultValue
    val isChanged = override != null
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        // Caption2 on one line, 0.36 of the panel's width.
        val labelStyle = MaterialTheme.typography.labelSmall
        val labelSize = if (labelStyle.fontSize.isSp) labelStyle.fontSize else 11.sp
        BasicText(
            descriptor.label,
            Modifier.fillMaxWidth(0.36f).padding(end = 8.dp),
            style = labelStyle.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isChanged) 1f else 0.72f)),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = labelSize * 0.75f, maxFontSize = labelSize),
        )
        when (descriptor.kind) {
            TuneKind.Number -> {
                val number = (current as? TuneValue.Number)?.value ?: 0.0
                val range = descriptor.effectiveRange
                // Continuous track (no stop marks for 100+ positions), snapped to the step grid.
                val step = descriptor.step ?: if (descriptor.valueType == "Int") 1.0 else null
                // Tinted orange once changed.
                val sliderColors = if (isChanged) {
                    SliderDefaults.colors(thumbColor = ChangedColor, activeTrackColor = ChangedColor)
                } else {
                    SliderDefaults.colors()
                }
                Slider(
                    value = number.toFloat().coerceIn(range.start.toFloat(), range.endInclusive.toFloat()),
                    onValueChange = { raw ->
                        val snapped = if (step != null && step > 0) range.start + Math.round((raw - range.start) / step) * step else raw.toDouble()
                        set(TuneValue.Number(snapped))
                    },
                    valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
                    colors = sliderColors,
                    modifier = Modifier.weight(1f).semantics { contentDescription = descriptor.label },
                )
                ValueText(descriptor.valueText(current), isChanged) { set(null) }
            }
            TuneKind.Bool -> {
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = (current as? TuneValue.Bool)?.value ?: false,
                    onCheckedChange = { set(TuneValue.Bool(it)) },
                    modifier = Modifier.semantics { contentDescription = descriptor.label },
                    colors = OpenCodeSwitchDefaults.colors(),
                )
                if (isChanged) ValueText("Reset", true) { set(null) }
            }
            TuneKind.Choice -> {
                var open by remember { mutableStateOf(false) }
                val choice = (current as? TuneValue.Choice)?.value.orEmpty()
                Spacer(Modifier.weight(1f))
                Box {
                    Text(
                        TuningTitle.format(choice),
                        Modifier.clickable(onClickLabel = descriptor.label) { open = true }.openCodeTapTarget().padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isChanged) ChangedColor else MaterialTheme.colorScheme.primary,
                    )
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        for (option in descriptor.choices.orEmpty()) {
                            DropdownMenuItem(text = { Text(TuningTitle.format(option)) }, onClick = { open = false; set(TuneValue.Choice(option)) })
                        }
                    }
                }
                if (isChanged) ValueText("Reset", true) { set(null) }
            }
        }
    }
}

@Composable
private fun ValueText(text: String, isChanged: Boolean, reset: () -> Unit) {
    val style = MaterialTheme.typography.labelSmall.copy(fontFamily = OpenCodeFonts.monospace)
    val color = if (isChanged) ChangedColor else OpenCodeTheme.colors.secondaryText
    if (isChanged) {
        Text(text, Modifier.clickable(onClickLabel = "Reset") { reset() }.openCodeTapTarget().padding(start = 6.dp), style = style, color = color)
    } else {
        Text(text, Modifier.widthIn(min = 44.dp).padding(start = 6.dp), style = style, color = color)
    }
}

/** Rows grouped by the key's second segment ("drawer.edge.…" is Edge). */
internal fun sections(rows: List<TunableDescriptor>): List<Pair<String, List<TunableDescriptor>>> {
    val result = LinkedHashMap<String, MutableList<TunableDescriptor>>()
    for (row in rows) {
        val title = row.section?.let(TuningTitle::format).orEmpty()
        result.getOrPut(title) { mutableListOf() } += row
    }
    return result.map { it.key to it.value }
}
