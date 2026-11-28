package ai.opencode.feature.drawer.list

import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.MotionFrameAction
import ai.opencode.core.motion.SpringSolver
import ai.opencode.core.motion.motionFrameLoop
import ai.opencode.core.motion.rememberFrameDurationSeconds
import ai.opencode.core.store.StoreOf
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.feature.drawer.DrawerListFooter
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.SessionPagesAction
import ai.opencode.feature.drawer.icons.OpenCodeProjectLookup
import ai.opencode.feature.drawer.isPinned
import ai.opencode.feature.drawer.motion.DrawerPanRegions
import ai.opencode.feature.drawer.motion.DrawerMotionTunables
import ai.opencode.feature.drawer.motion.DrawerShortcutMotion
import ai.opencode.feature.drawer.motion.DrawerTimeReveal
import ai.opencode.feature.drawer.motion.DrawerTimestamp
import ai.opencode.feature.drawer.motion.drawerPanList
import ai.opencode.feature.drawer.drawerSessions
import ai.opencode.feature.drawer.style.DrawerEmptyPinDropZone
import ai.opencode.feature.drawer.style.DrawerHeaderScrollState
import ai.opencode.feature.drawer.style.DrawerSectionHeaderKind
import ai.opencode.feature.drawer.style.DrawerTunables
import ai.opencode.feature.drawer.visibleSessions
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// The drawer's session list as a `LazyColumn` keyed by [DrawerListItem.key]. Home's state reaches
// the rows through one model build ([DrawerListModel.build]); unchanged rows keep their instances,
// so only changed rows recompose. [DrawerPinDragHost.flushNow] sets the list's snapshot at once, so
// a pin drop lays out in the frame that commits it. The time reveal springs the rows back from
// where they were at release (0.4 s, bounce 0.2; Reduce Motion fades over 0.2 s).

/** What the list does that only `HomeDrawer` can. */
class DrawerListActions(
    val select: (sessionID: String) -> Unit,
    /** A row's hold: it joins or leaves the selection. */
    val held: (sessionID: String) -> Unit,
    val openCatchUp: () -> Unit,
    val openWhatsNew: () -> Unit,
    val hideWhatsNew: () -> Unit,
    val openArchived: () -> Unit,
)

@Stable
internal class DrawerListReveal(private val timeReveal: DrawerTimeReveal) : DrawerListRevealValues {
    /** Where the rows rest while no drag moves them: the last drag's place, springing back to zero. */
    var heldOffset: Float by mutableFloatStateOf(0f)
    var heldProgress: Float by mutableFloatStateOf(0f)

    private val isLive: Boolean get() = timeReveal.offset != 0f || timeReveal.progress != 0f
    override val offset: Float get() = if (isLive) timeReveal.offset else heldOffset
    override val progress: Float get() = if (isLive) timeReveal.progress else heldProgress
}

/**
 * The last model built, so unchanged rows keep their instances; and the gate that keeps a hidden
 * drawer from building.
 */
internal class DrawerListModelHolder {
    var model = DrawerListModel()

    /** Models built, for the perf gate tests. */
    var builds = 0
        private set

    /** The first build done hidden, before the drawer shows. */
    private var hasBuilt = false
    private var wasHidden = false

    /**
     * This build is the first since the drawer was hidden: what changed while hidden lands in place
     * instead of animating. Cleared once the shortcuts have synced.
     */
    var isRevealing = false

    /**
     * The model to show. While the drawer is hidden it keeps the last one, once there is one (the
     * prewarm builds it hidden so the first open doesn't); call [build] only when it should change.
     */
    fun model(isHidden: Boolean, build: () -> DrawerListModel): DrawerListModel {
        if (isHidden && hasBuilt) {
            wasHidden = true
            return model
        }
        if (!isHidden && wasHidden) isRevealing = true
        wasHidden = isHidden
        hasBuilt = true
        builds += 1
        model = stabilized(model, build())
        return model
    }
}

/**
 * The drawer's session list.
 *
 * @param inputs the list's view-local values (selection, environment).
 * @param isDrawerVisible The drawer shows: the shortcut rows animate only then.
 * @param catchUpFocusRequests bumped when leaving Catch Up hands TalkBack back to its row.
 * @param contentPadding room under the title and above New Session and Settings.
 * @param panRegions the drawer pan's regions: a rightward drag on the list reveals the rows' times.
 */
@Composable
fun DrawerListView(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    pinDrag: DrawerPinDrag,
    timeReveal: DrawerTimeReveal,
    headerScroll: DrawerHeaderScrollState,
    shortcutMotion: DrawerShortcutMotion,
    inputs: DrawerListInputs,
    isDrawerVisible: Boolean,
    catchUpFocusRequests: Int,
    actions: DrawerListActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    panRegions: DrawerPanRegions? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    remember { DrawerListPerfProbe.mountStarted(); true }
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val scope = rememberCoroutineScope()
    val frameDuration = rememberFrameDurationSeconds()
    val currentActions by rememberUpdatedState(actions)
    val currentInputs = rememberUpdatedState(inputs)

    // Home's state as the list reads it; `flushNow` sets it at once for a drop.
    val homeState = remember(store) { mutableStateOf(store.state, referentialEqualityPolicy()) }
    LaunchedEffect(store) { store.states.collect { homeState.value = it } }
    val liftedID = remember { mutableStateOf<String?>(null) }
    val holder = remember { DrawerListModelHolder() }
    // The rows' times follow the device's 24-hour setting.
    val timeContext = LocalContext.current
    val hour24 = remember(timeContext) { android.text.format.DateFormat.is24HourFormat(timeContext) }

    // While the drawer is hidden this reads nothing but the flag, so Home's changes don't rebuild it.
    val drawerHidden = LocalDrawerHidden.current
    val model by remember {
        derivedStateOf {
            holder.model(isHidden = drawerHidden.value) {
                val state = homeState.value
                // Today and the projects worked out once for every row.
                val day = DrawerTimestamp.Day(Instant.now(), hour24 = hour24)
                val projects = OpenCodeProjectLookup(state.projects)
                DrawerListModel.build(
                    state = state,
                    inputs = currentInputs.value,
                    liftedID = liftedID.value,
                    now = Instant.now(),
                    timestamp = { date, _ -> day.text(date) },
                    project = { session, _ -> projects.resolved(session) },
                )
            }
        }
    }
    val isSettingsShown by remember { derivedStateOf { homeState.value.isSettingsShown } }

    // The drawer's knobs join the tuning panel once while it's on.
    SideEffect { DrawerTunables.registerIfNeeded() }

    // What changed while the drawer was hidden lands in place on reveal.
    val shortcutsAnimate = isDrawerVisible && !holder.isRevealing
    val settingsWasShown = remember { booleanArrayOf(false) }
    val whatsNewExitDelay = remember(model.shortcuts.showsWhatsNew) {
        if (settingsWasShown[0] && !isSettingsShown) DrawerMotionTunables.SHORTCUT_CLOSE_DELAY else 0.0
    }
    SideEffect {
        shortcutMotion.sync(model.shortcuts, searchIsEmpty = model.search.isEmpty(), isDrawerVisible = shortcutsAnimate)
        settingsWasShown[0] = isSettingsShown
        holder.isRevealing = false
    }

    // The time reveal, sprung back from where the rows were.
    val reveal = remember(timeReveal) { DrawerListReveal(timeReveal) }
    LaunchedEffect(reveal, inputs.reducedMotion, frameDuration) {
        snapshotFlow { timeReveal.offset to timeReveal.progress }.collectLatest { (offset, progress) ->
            if (offset != 0f || progress != 0f) {
                reveal.heldOffset = offset
                reveal.heldProgress = progress
                return@collectLatest
            }
            if (reveal.heldOffset <= 0.5f && reveal.heldProgress <= 0.01f) {
                reveal.heldOffset = 0f
                reveal.heldProgress = 0f
                return@collectLatest
            }
            springBack(reveal, inputs.reducedMotion, frameDuration)
        }
    }

    // The header's edge follows how far rows have scrolled beneath the title.
    LaunchedEffect(listState, headerScroll) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }.collect { (index, px) ->
            val fade = max(DrawerTunables.fade.value, 1f)
            val scrolled = if (index == 0) min(px / density.density, fade) else fade
            headerScroll.update(((scrolled * 2).roundToInt() / 2f).dp)
        }
    }

    // The drawer doesn't make room for search's keyboard; a drag on the list puts it away.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) keyboard?.hide() }
    }

    // The pin drag reads the rows' places from the list.
    val listCoordinates = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val host = remember(listState, scope) {
        object : DrawerPinDragHost {
            override fun visibleItems(): List<DrawerPinDragItem> {
                val info = listState.layoutInfo
                val start = info.viewportStartOffset
                return info.visibleItemsInfo.mapNotNull { item ->
                    val key = pinDragKey(item.key) ?: return@mapNotNull null
                    DrawerPinDragItem(key, (item.offset - start) / density.density, item.size / density.density)
                }
            }

            override val width: Float get() = (listCoordinates[0]?.size?.width ?: 0) / density.density

            override val viewportTop: Float get() = -listState.layoutInfo.viewportStartOffset / density.density

            override val height: Float get() = (listCoordinates[0]?.size?.height ?: 0) / density.density

            override val headerBottom: Float
                get() = max(listState.layoutInfo.beforeContentPadding / density.density - DrawerTunables.topGap.value, 0f)

            override val footerTop: Float
                get() = height - listState.layoutInfo.afterContentPadding / density.density

            override val window: Rect
                get() {
                    val origin = listCoordinates[0]?.takeIf { it.isAttached }?.positionInWindow() ?: return super.window
                    val size = windowInfo.containerSize
                    return Rect(-origin.x, -origin.y, size.width - origin.x, size.height - origin.y).let {
                        Rect(it.left / density.density, it.top / density.density, it.right / density.density, it.bottom / density.density)
                    }
                }

            override fun flushNow() {
                homeState.value = store.state
            }

            override fun preserveScroll() {
                listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }

            override fun scrollBy(delta: Float) {
                // Synchronous: the layout info the next read sees is already scrolled.
                listState.dispatchRawDelta(delta * density.density)
            }

            override val scope: CoroutineScope get() = scope
            override val frameDurationSeconds: Double get() = frameDuration
        }
    }
    DisposableEffect(pinDrag, host) {
        pinDrag.attach(host)
        onDispose { pinDrag.detach(host) }
    }

    fun beginPinDrag(id: String, fingerY: Float) {
        val state = store.state
        val sessions = state.drawerSessions
        val unpinnedOrder = state.visibleSessions
            .filter { it.id == id || !state.isPinned(it.id) }
            .sortedByDescending { it.updatedAt }
            .map { it.id }
        pinDrag.beginDrag(
            sessionID = id,
            isPinnedSection = state.isPinned(id),
            pinnedIDs = sessions.pinned.map { it.id },
            recentIDs = sessions.recents.map { it.id },
            unpinnedRecentsIndex = unpinnedOrder.indexOf(id).coerceAtLeast(0),
            showsShortcuts = state.search.isEmpty() && (model.shortcuts.showsCatchUp || model.shortcuts.showsWhatsNew),
            reducedMotion = currentInputs.value.reducedMotion,
            fingerY = fingerY,
            onZoneCrossed = { store.send(HomeFeature.Action.DrawerPinZoneCrossed(it)) },
            onSlotChanged = { store.send(HomeFeature.Action.DrawerPinSlotChanged) },
            onDropReleased = { sessionID, index -> store.send(HomeFeature.Action.DrawerRowDropReleased(sessionID, index)) },
            onDrop = { sessionID, index ->
                store.send(HomeFeature.Action.DrawerRowDropped(sessionID, index))
                host.flushNow()
            },
            onSetLifted = { liftedID.value = it },
        )
    }

    // Leaving Catch Up: TalkBack lands on the Catch Up row, or on the highlighted session.
    // Accessibility focus only, not input focus. Once, as the leave happens: a row not laid out by
    // then never takes it later.
    val answeredFocusRequests = remember { intArrayOf(catchUpFocusRequests) }
    var catchUpReturn by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(catchUpFocusRequests) {
        if (catchUpFocusRequests == answeredFocusRequests[0]) return@LaunchedEffect
        answeredFocusRequests[0] = catchUpFocusRequests
        catchUpReturn = catchUpFocusRequests
        delay(CATCH_UP_RETURN_FOCUS_MS)
        catchUpReturn = null
    }
    val returnFocus = rememberAccessibilityFocusRequest(catchUpReturn)
    val returnsToCatchUp = model.search.isEmpty() && model.shortcuts.showsCatchUp

    if (DrawerListPerfProbe.isEnabled) {
        LaunchedEffect(listState) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.count { it.key is String && (it.key as String).startsWith("session-") } }
                .collect { DrawerListPerfProbe.frame(it, scope) }
        }
    }

    // A list resting at its top stays there when a row arrives above the first one (Catch Up or
    // Pinned coming back); LazyColumn would otherwise keep the old first row in place and leave the
    // new one scrolled under the title.
    val restsAtTop = remember { booleanArrayOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
            .collect { restsAtTop[0] = it }
    }
    // The footer in view while it spins reads the next page, and each page's answer checks again,
    // so a page too short to push it out reads the one after.
    val currentModel = rememberUpdatedState(model)
    LaunchedEffect(store, listState) {
        snapshotFlow {
            val footer = currentModel.value.footer
            val footerShows = listState.layoutInfo.visibleItemsInfo.any { it.key == DrawerListItem.Footer.key }
            Triple(footer == DrawerListFooter.loading && footerShows, currentModel.value.pagesRead, currentModel.value.search)
        }.collect { (loadsMore, _, _) ->
            if (loadsMore) store.send(HomeFeature.Action.SessionPages(SessionPagesAction.LoadMoreRequested))
        }
    }

    val firstKey = model.items.firstOrNull()?.key
    val shownFirstKey = remember { arrayOfNulls<String>(1) }
    if (shownFirstKey[0] != firstKey) {
        if (shownFirstKey[0] != null && restsAtTop[0]) listState.requestScrollToItem(0, 0)
        shownFirstKey[0] = firstKey
    }

    // Pin, Unpin and Move Up/Down outside a drag (the menu, TalkBack) move the row to its new place
    // as the rows between make room, in one move; not on another server's Home or a changed search,
    // and a drag lands its own.
    val shownPins = remember { arrayOfNulls<DrawerShownPins>(1) }
    val pins = DrawerShownPins(
        serverID = model.serverID,
        search = model.search,
        pinned = model.sections[DrawerListSection.pinned].orEmpty(),
    )
    shownPins[0]?.let { previous ->
        if (previous.movesPins(pins) && isDrawerVisible) pinDrag.pinMove.begin(model.reducedMotion)
    }
    shownPins[0] = pins

    Box(modifier) {
        // Beneath the rows: they move out of its way, and the lifted row (drawn in the list) stays
        // above it.
        DrawerPinDropZoneOverlay(pinDrag)
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier
                .fillMaxSize()
                // A Pin or Unpin's rows start where they showed, before the frame draws them anew.
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                        pinDrag.pinMove.apply()
                    }
                }
                .onGloballyPositioned { listCoordinates[0] = it }
                .then(if (panRegions != null) Modifier.drawerPanList(panRegions) else Modifier)
                .testTag("drawer-list"),
        ) {
            val current = model
            itemsIndexed(current.items, key = { _, item -> item.key }, contentType = { _, item -> contentType(item) }) { _, item ->
                // The dragged row draws above every other item, headers included, through its drag,
                // landing and settle. The z-order has to sit on the item's root: the list orders
                // its items, not their content.
                val isDragged = item is DrawerListItem.Session && pinDrag.draggingID == item.id
                Box(Modifier.zIndex(if (isDragged) 1f else 0f)) {
                when (item) {
                    DrawerListItem.CatchUp, DrawerListItem.WhatsNew -> DrawerListShortcutRow(
                        kind = if (item == DrawerListItem.CatchUp) DrawerListShortcutKind.CatchUp else DrawerListShortcutKind.WhatsNew,
                        motion = shortcutMotion,
                        shortcuts = current.shortcuts,
                        animates = shortcutsAnimate,
                        exitDelay = if (item == DrawerListItem.WhatsNew) whatsNewExitDelay else 0.0,
                        reducedMotion = current.reducedMotion,
                        openCatchUp = { currentActions.openCatchUp() },
                        markAllRead = { store.send(HomeFeature.Action.CatchUp(ai.opencode.feature.drawer.catchup.CatchUpFeature.Action.MarkAllReadTapped)) },
                        openWhatsNew = { currentActions.openWhatsNew() },
                        hideWhatsNew = { currentActions.hideWhatsNew() },
                        returnFocus = returnFocus.takeIf { item == DrawerListItem.CatchUp },
                    )
                    DrawerListItem.Archived -> ArchivedEntryRow(current.archivedCount, regularList = true, onClick = { currentActions.openArchived() })
                    is DrawerListItem.Header -> DrawerListHeaderCell(item.kind, pinDrag)
                    DrawerListItem.Empty -> DrawerListEmptyRow(current.search)
                    DrawerListItem.Footer -> DrawerListFooterRow(
                        footer = current.footer ?: DrawerListFooter.loading,
                        retry = { store.send(HomeFeature.Action.SessionPages(SessionPagesAction.RetryTapped)) },
                    )
                    is DrawerListItem.Ghost -> DrawerListGhostCell(item.index, current.reducedMotion)
                    DrawerListItem.ServerNote -> current.serverNote?.let { note ->
                        DrawerServerNoteRow(note, onAction = { store.send(HomeFeature.Action.ServerSwitchNoteActionTapped) })
                    }
                    is DrawerListItem.Session -> {
                        val row = current.rows[item.id] ?: return@itemsIndexed
                        val rowActions = remember(row.session.id, row.unread, row.isPinnedSection) {
                            rowActions(row, store, pinDrag, liftedID, listState, { currentActions }, ::beginPinDrag)
                        }
                        DrawerListSessionCell(
                            row = row,
                            actions = rowActions,
                            reveal = reveal,
                            pinDrag = pinDrag,
                            listCoordinates = { listCoordinates[0] },
                            returnFocus = returnFocus.takeIf { row.selected && !returnsToCatchUp },
                        )
                    }
                }
                }
            }
        }
    }
}

/** The empty-pins drop zone at the list's top while a drag opens it. */
@Composable
private fun DrawerPinDropZoneOverlay(pinDrag: DrawerPinDrag) {
    val zone = pinDrag.dropZone ?: return
    DrawerEmptyPinDropZone(
        openProgress = zone.openProgress,
        hoverProgress = zone.hoverProgress,
        headerHeight = zone.headerHeight.dp,
        reducedMotion = zone.reducedMotion,
        modifier = Modifier
            .offset { IntOffset(0, (zone.top * density).roundToInt()) }
            .fillMaxWidth()
            .height(zone.height.dp)
            .clipToBounds(),
    )
}

private fun rowActions(
    row: DrawerListRow,
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    pinDrag: DrawerPinDrag,
    liftedID: androidx.compose.runtime.MutableState<String?>,
    listState: LazyListState,
    actions: () -> DrawerListActions,
    beginPinDrag: (String, Float) -> Unit,
): DrawerListRowActions {
    val id = row.session.id
    val unread = row.unread
    return DrawerListRowActions(
        select = { actions().select(id) },
        pressed = { store.send(HomeFeature.Action.DrawerRowPressed(id)) },
        held = { actions().held(id) },
        isListMoving = { listState.isScrollInProgress },
        beginDrag = { y -> beginPinDrag(id, y) },
        moveDrag = { y, time -> pinDrag.updateDrag(y, time) },
        endDrag = { x, y, cancelled ->
            if (cancelled) {
                pinDrag.cancelDrag(deferLiftedClear = true)
            } else {
                pinDrag.endDrag(x, y)
            }
            // A drag that never began (no list to move) leaves no lift behind.
            if (pinDrag.draggingID == null) liftedID.value = null
        },
        dismantled = { if (pinDrag.activeSessionID == id) pinDrag.cancelDrag() },
        lifted = { liftedID.value = it },
        togglePin = { store.send(HomeFeature.Action.TogglePin(id)) },
        toggleRead = { store.send(if (unread) HomeFeature.Action.MarkRead(id) else HomeFeature.Action.MarkUnread(id)) },
        toggleMute = { store.send(HomeFeature.Action.ToggleMute(id)) },
        toggleHidden = { store.send(HomeFeature.Action.ToggleHidden(id)) },
        delete = { store.send(HomeFeature.Action.DeleteSessionTapped(id)) },
        movePinUp = { store.send(HomeFeature.Action.MovePinUp(id)) },
        movePinDown = { store.send(HomeFeature.Action.MovePinDown(id)) },
    )
}

/**
 * Back on `MotionRole.Release`, critically damped from where the rows are, no bounce on the way
 * home. Under Reduce Motion the time fades out (`Reduced`).
 */
private suspend fun springBack(reveal: DrawerListReveal, reducedMotion: Boolean, frameDuration: Double) {
    val startOffset = reveal.heldOffset
    val startProgress = reveal.heldProgress
    val spring = if (reducedMotion) {
        SpringSolver(MotionRole.Reduced, value = 1.0, target = 0.0, positionEpsilon = 0.002, velocityEpsilon = 0.02)
    } else {
        SpringSolver(MotionRole.Release, value = startOffset.toDouble(), target = 0.0, positionEpsilon = 0.1, velocityEpsilon = 1.0)
    }
    motionFrameLoop(frameDurationSeconds = frameDuration) { tick ->
        val step = spring.step(tick.dt)
        if (step.isSettled) {
            reveal.heldOffset = 0f
            reveal.heldProgress = 0f
            return@motionFrameLoop MotionFrameAction.Stop
        }
        val value = max(step.value, 0.0).toFloat()
        if (reducedMotion) {
            reveal.heldOffset = 0f
            reveal.heldProgress = startProgress * value
        } else {
            val fraction = if (startOffset > 0) min(value / startOffset, 1f) else 0f
            reveal.heldOffset = step.value.toFloat()
            reveal.heldProgress = startProgress * fraction
        }
        MotionFrameAction.Continue
    }
}

/** Keeps unchanged rows' instances, so their cells skip recomposition. */
private fun stabilized(previous: DrawerListModel, next: DrawerListModel): DrawerListModel {
    if (previous.rows.isEmpty()) return next
    val rows = LinkedHashMap<String, DrawerListRow>(next.rows.size)
    for ((id, row) in next.rows) rows[id] = previous.rows[id]?.takeIf { it == row } ?: row
    return next.copy(rows = rows)
}

private fun contentType(item: DrawerListItem): Int = when (item) {
    DrawerListItem.CatchUp, DrawerListItem.WhatsNew, DrawerListItem.Archived -> 0
    is DrawerListItem.Header -> 2
    is DrawerListItem.Session -> 3
    DrawerListItem.Empty -> 4
    is DrawerListItem.Ghost -> 5
    DrawerListItem.ServerNote -> 6
    DrawerListItem.Footer -> 7
}

/** What the list showed of its pins, to tell a Pin, Unpin or Move outside a drag. */
internal data class DrawerShownPins(val serverID: String, val search: String, val pinned: List<DrawerListItem>) {
    fun movesPins(next: DrawerShownPins): Boolean = serverID == next.serverID && search == next.search && pinned != next.pinned
}

/** A `LazyColumn` key back to the pin drag's item (headers and rows only). */
private fun pinDragKey(key: Any): DrawerPinDrag.ItemKey? {
    val text = key as? String ?: return null
    return when {
        text.startsWith("session-") -> DrawerPinDrag.ItemKey.Row(text.removePrefix("session-"))
        text == DrawerListItem.Header(DrawerSectionHeaderKind.Pinned).key -> DrawerPinDrag.ItemKey.PinnedHeader
        text == DrawerListItem.Header(DrawerSectionHeaderKind.Recents).key -> DrawerPinDrag.ItemKey.RecentsHeader
        else -> null
    }
}

/** How long leaving Catch Up offers TalkBack its row: well past the two frames the request waits. */
private const val CATCH_UP_RETURN_FOCUS_MS = 1_000L
