package ai.opencode.feature.drawer

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import ai.opencode.feature.settings.onboarding.WelcomeView
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import androidx.compose.ui.graphics.painter.Painter
import ai.opencode.devtools.traces.TraceUploadsHost
import ai.opencode.core.store.rememberScope
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.LocalAccessibilityFocusMoves
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.catchup.CatchUpChrome
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.catchup.CatchUpStage
import ai.opencode.feature.drawer.catchup.CatchUpStageProxy
import ai.opencode.feature.drawer.container.DrawerKeyboard
import ai.opencode.feature.drawer.container.SessionDrawerContainer
import ai.opencode.feature.drawer.container.SidebarMetrics
import ai.opencode.feature.drawer.container.clearAndMergeSemantics
import ai.opencode.feature.drawer.container.rememberSessionDrawerController
import ai.opencode.feature.drawer.list.LocalDrawerSelection
import ai.opencode.feature.drawer.motion.DrawerButton
import ai.opencode.feature.drawer.motion.rememberDrawerKeyboardHold
import ai.opencode.feature.drawer.motion.rememberDrawerShortcutMotion
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.shared
import ai.opencode.feature.drawer.style.DrawerMetrics
import ai.opencode.feature.voice.FloatingCallWindow
import ai.opencode.feature.voice.VoiceRoutePreferences
import ai.opencode.feature.voice.VoiceStoreOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlin.math.max
import ai.opencode.server.OpenCodeServer
import androidx.compose.runtime.collectAsState
import ai.opencode.feature.session.SessionFeature
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.SessionBrowserPage
import ai.opencode.feature.session.ServerConnectionNotice
import ai.opencode.feature.session.SessionListContext
import ai.opencode.feature.session.SessionPageStack
import ai.opencode.feature.session.SessionStackPage
import ai.opencode.feature.session.sessionBrowserStackPage
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.DisposableEffect
import ai.opencode.feature.session.SessionView
import ai.opencode.feature.session.withRequestsConnectionIsAvailable
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.LocalAppearanceTranscriptPreview
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPresentation
import ai.opencode.feature.settings.SettingsView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/**
 * One server's Home. A window at least [SidebarMetrics.minimumWindowWidth] wide lists the sessions
 * in a sidebar beside the session; anything narrower slides the phone's drawer under it. Per-frame
 * drawer motion never recomposes this view: the container's plain objects own it, and only settled
 * open/shut reaches the reducer.
 */
@Composable
fun HomeView(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    modifier: Modifier = Modifier,
    initiallyOpenDrawer: Boolean = false,
    welcomeMark: Painter? = null,
) {
    val view = rememberHomeViewState(store, initiallyOpenDrawer)
    val reducedMotion = rememberReduceMotion()
    view.keyboardHold = rememberDrawerKeyboardHold(view.keyboardMonitor)
    view.shortcutMotion = rememberDrawerShortcutMotion()
    val hasDetail by store.observe { it.sessionDetail != null }

    val settingsShown by store.observe { it.settings != null }
    val settingsAtRoot by store.observe { it.settings?.path?.isEmpty() ?: true }
    SettingsPresentation(
        isShown = settingsShown,
        atRoot = settingsAtRoot,
        // At the root Back closes Settings (`SettingsDelegate.Close`, which Home handles).
        onClose = { store.send(HomeFeature.Action.SettingsScreen(PresentationAction.Presented(SettingsFeature.Action.BackTapped))) },
        settings = { HomeSettings(store) },
        modifier = modifier,
    ) {
        ai.opencode.feature.composer.OpenCodeSheetHost {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // An iPad-wide window lists the sessions in a sidebar beside the session; anything
                // narrower slides the phone's drawer under it.
                val usesSidebar = maxWidth >= SidebarMetrics.minimumWindowWidth
                // Android: Material's modal drawer width (at most 360 dp, leaving 56 dp of the
                // session beside it to tap shut).
                val drawerWidth = if (usesSidebar) SidebarMetrics.width else min(maxWidth - 56.dp, 360.dp)
                SideEffect { view.usesSidebar = usesSidebar }
                if (hasDetail) HomeLayout(store, view, usesSidebar, drawerWidth, reducedMotion)
                HomeSidebarEffects(store, view, usesSidebar)
            }
        }
    }
    HomeSettingsBack(store)
    HomeWelcome(store, welcomeMark)
    HomeFloatingCallWindow(store)
    HomeLifecycle(store, view)
    // Hosted on Home, so deletes from the session's own menu confirm too.
    HomeDeleteConfirmation(store)
    // Report a Problem and its toast.
    TraceUploadsHost(store.rememberScope({ it.traces }, HomeFeature.Action::Traces))
}

/** The container with the session, the drawer, and Catch Up. */
@Composable
private fun HomeLayout(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
    drawerWidth: Dp,
    reducedMotion: Boolean,
) {
    val controller = rememberSessionDrawerController(view.isSessionDrawerPresented)
    val haptics = remember { dependency(HapticsClient) }
    val density = LocalDensity.current
    val catchUp by store.observe { it.catchUp }
    val isSidebarShown by store.observe { it.isSidebarShown }
    val previewRevision by store.observe { it.previewRevision }
    val selectedSessionID by store.observe { it.sessionDetail?.session?.id }
    // A subagent or a web page pushed over the session: its back gesture has the drag.
    val pushed by store.observe { it.subagentStack.isNotEmpty() || it.sessionDetail?.browser?.pageID != null }
    val settingsShown by store.observe { it.isSettingsShown }
    val sheetPresented by store.observe { it.topDetail?.activePicker != null || it.topDetail?.shellOutput != null }
    val catchUpProxy = remember { CatchUpStageProxy() }
    val currentReducedMotion by rememberUpdatedState(reducedMotion)
    // The last stack shown, so the stage keeps its last count while it fades out.
    val lastCatchUp = remember { arrayOfNulls<CatchUpFeature.Stack>(1) }
    catchUp?.let { lastCatchUp[0] = it }

    /**
     * Back, TalkBack's escape, or the last card gone (`finished`, which the drawer's Catch Up row
     * celebrates). The stack hands back to the drawer it was opened from, as it was; set before the
     * stack ends, so the card sees both in one update and returns to the open drawer rather than
     * landing shut and opening again. Navigation: no haptic.
     */
    fun leaveCatchUp(finished: Boolean) {
        if (finished && store.state.catchUp?.queue?.isEmpty() != true) return
        val action = if (finished) HomeFeature.Action.catchUpFinished(currentReducedMotion) else HomeFeature.Action.catchUpExitTapped
        // The card's picture first: it fades over the session coming back.
        controller.captureCardThen {
            if (store.state.catchUp == null) return@captureCardThen
            if (!store.state.showsDrawerLabel) {
                store.send(action)
                return@captureCardThen
            }
            view.isSessionDrawerPresented = true
            store.send(action)
            // TalkBack lands back on the Catch Up row (`DrawerListController`).
            view.catchUpFocusRequests += 1
        }
    }

    val stage = catchUp?.let { stack ->
        CatchUpStage(
            queue = stack.queue,
            undoDirection = stack.handled.lastOrNull()?.disposition,
            onDismiss = { sessionID, disposition ->
                store.send(HomeFeature.Action.catchUpCardDismissed(sessionID, disposition))
            },
            onUndo = { store.send(HomeFeature.Action.catchUpUndoTapped) },
            onExit = { leaveCatchUp(finished = false) },
            onFinish = { leaveCatchUp(finished = true) },
        )
    }

    SessionDrawerContainer(
        controller = controller,
        isPresented = if (usesSidebar) false else view.isSessionDrawerPresented,
        onPresentationChanged = { presented ->
            if (!usesSidebar && view.isSessionDrawerPresented != presented) view.isSessionDrawerPresented = presented
        },
        width = drawerWidth,
        sidebarShown = if (usesSidebar) isSidebarShown else null,
        reducedMotion = reducedMotion,
        catchUp = stage,
        catchUpProxy = catchUpProxy,
        keyboardHold = requireNotNull(view.keyboardHold),
        panRegions = view.panRegions,
        previewRevision = previewRevision,
        onArrived = { open ->
            if (!open) view.cancelPinDrag()
            store.send(HomeFeature.Action.SessionDrawerArrived(open))
        },
        onCommitted = { haptics.play(Haptic.snap) },
        onTouchDown = { haptics.prepare(Haptic.snap) },
        onReveal = { phase ->
            view.timeReveal.update(phase, reach = TIME_REVEAL_REACH * density.fontScale, reducedMotion = currentReducedMotion)
        },
        contentSessionID = DrawerKeyboard.closingSession(chosen = view.chosenSessionID, shown = selectedSessionID),
        isContentPushed = pushed,
        isSheetPresented = settingsShown || sheetPresented,
        content = {
            HomeSessionStack(store, view, usesSidebar, onCatchUpOpen = {
                controller.captureCardThen {
                    if (store.state.catchUp != null) store.send(HomeFeature.Action.catchUpOpenTapped)
                }
            })
        },
        // The drawer's panel; Home hosts the delete confirmation. The drawer's selection reaches
        // the rows' checks, which wave out from the held row.
        drawer = {
            CompositionLocalProvider(LocalDrawerSelection provides view.selection) {
                HomeDrawer(store, view, Modifier.fillMaxSize(), hostsDeleteConfirmation = false)
            }
        },
        chrome = {
            val shown = catchUp ?: lastCatchUp[0]
            if (shown != null) {
                CatchUpChrome(
                    remaining = shown.queue.size,
                    canUndo = shown.handled.isNotEmpty(),
                    onExit = { leaveCatchUp(finished = false) },
                    onUndo = { catchUpProxy.undoCard() },
                    onDismiss = { catchUpProxy.dismissCard(it) },
                )
            }
        },
        preview = { sessionID, revision -> CatchUpPreviewCard(store, sessionID, revision) },
    )
}

/**
 * The floating call's window: a floating call stays put above everything while the card slides,
 * sessions change and Settings, Welcome or a sheet opens: the call belongs to the root session, and
 * its orb has a window of its own. Only while the call doesn't show its card (`call.flatMap {
 * $0.phase.showsCard ? nil: $0 }`); a failed or dropped call hands over to its card, and the window
 * fades the orb.
 */
@Composable
private fun HomeFloatingCallWindow(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val call = rememberHomeCall(store)
    val showsCard = call?.observe { it.phase.showsCard }?.value
    FloatingCallWindow(if (call != null && showsCard == false) call else null)
}

/**
 * The open session's call, scoped from its screen: null while there's none. It keeps its last state
 * while it leaves, so a view still drawing it never reads a missing call.
 */
@Composable
internal fun rememberHomeCall(store: StoreOf<HomeFeature.State, HomeFeature.Action>): VoiceStoreOf? {
    val present by store.observe { it.sessionDetail?.voice != null }
    if (!present) return null
    return remember(store) {
        var last: VoiceFeature.State? = null
        store.scope<HomeFeature.State, HomeFeature.Action, VoiceFeature.State, VoiceFeature.Action>(
            state = { home -> (home.sessionDetail?.voice ?: last ?: error("rememberHomeCall read before the call appeared")).also { last = it } },
            action = { HomeFeature.Action.detail(SessionFeature.Action.voicePresented(it)) },
        )
    }
}

/** The open session's screen, keeping its last state while it leaves. */
@Composable
private fun rememberRootStore(store: StoreOf<HomeFeature.State, HomeFeature.Action>): StoreOf<SessionFeature.State, SessionFeature.Action> =
    remember(store) {
        var last: SessionFeature.State? = store.state.sessionDetail
        store.scope<HomeFeature.State, HomeFeature.Action, SessionFeature.State, SessionFeature.Action>(
            state = { home -> (home.sessionDetail ?: last ?: SessionFeature.State.draft(home.server, null, emptyList())).also { last = it } },
            action = { HomeFeature.Action.detail(it) },
        )
    }

/** A pushed subagent's screen by its `navigationID`, drawable while it animates out. */
private fun subagentStore(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    navigationID: Int,
): StoreOf<SessionFeature.State, SessionFeature.Action> {
    var last: SessionFeature.State? = store.state.subagentStack.firstOrNull { it.navigationID == navigationID }
    return store.scope(
        state = { home ->
            (home.subagentStack.firstOrNull { it.navigationID == navigationID } ?: last ?: SessionFeature.State.draft(home.server, null, emptyList()))
                .also { last = it }
        },
        action = { HomeFeature.Action.SubagentStack(navigationID, it) },
    )
}

/**
 * One page of the session's stack: a subagent at [index], or a web page over the screen [owner]
 * names.
 */
private sealed interface StackEntry {
    val page: SessionStackPage

    data class Subagent(val index: Int, val navigationID: Int, override val page: SessionStackPage) : StackEntry

    /** [owner]: the screen that opened it, a subagent's `navigationID` or null for the session. */
    data class Web(val owner: Int?, override val page: SessionStackPage) : StackEntry
}

/**
 * What the session's stack shows, bottom first: each subagent pushed over the session, and a web
 * page over whichever screen opened it. Back goes to the screen beneath.
 */
private fun stackEntries(state: HomeFeature.State): List<StackEntry> {
    val entries = ArrayList<StackEntry>()
    fun web(owner: Int?, screen: SessionFeature.State) {
        val page = sessionBrowserStackPage(screen) ?: return
        entries += StackEntry.Web(owner, page)
    }
    state.sessionDetail?.let { web(null, it) }
    for ((index, child) in state.subagentStack.withIndex()) {
        entries += StackEntry.Subagent(index, child.navigationID, SessionStackPage(id = child.navigationID))
        web(child.navigationID, child)
    }
    return entries
}

/**
 * The session (or draft) at the root of [SessionPageStack], with each subagent pushed over it and
 * any in-app browser page over the screen that opened it. The root's call shows on whichever
 * subagent is on top (`hostedCall`).
 */
@Composable
private fun HomeSessionStack(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
    onCatchUpOpen: () -> Unit,
) {
    val root = rememberRootStore(store)
    val call = rememberHomeCall(store)
    val entries by store.observe(::stackEntries)
    val catchingUp by store.observe { it.catchUp != null }
    val drawerOpen by store.observe { it.isDrawerOpen }
    val sheetPresented by store.observe {
        val top = it.topDetail
        top?.activePicker != null || top?.shellOutput != null
    }
    val listContext by remember(store) {
        store.states.map { it.sessionListContext(it.sessionDetail?.session) }.distinctUntilChanged()
    }.collectAsState(initial = store.state.sessionListContext(store.state.sessionDetail?.session))
    // Pages drawn while they animate out, by their identity.
    val known = remember { HashMap<Int, StackEntry>() }
    for (entry in entries) known[entry.page.id] = entry
    val subagentStores = remember(store) { HashMap<Int, StoreOf<SessionFeature.State, SessionFeature.Action>>() }
    fun subagent(navigationID: Int) = subagentStores.getOrPut(navigationID) { subagentStore(store, navigationID) }
    SessionPageStack(
        pages = entries.map { it.page },
        onPop = { depth ->
            // The reader went back one page: a web page closes; a subagent pops to the screen beneath it.
            val current = stackEntries(store.state)
            when (val entry = current.getOrNull(depth)) {
                null -> store.send(HomeFeature.Action.SubagentsPopped(depth))
                is StackEntry.Web -> {
                    val close = SessionFeature.Action.Browser(BrowserFeature.Action.CloseTapped)
                    store.send(if (entry.owner == null) HomeFeature.Action.detail(close) else HomeFeature.Action.SubagentStack(entry.owner, close))
                }
                is StackEntry.Subagent -> store.send(HomeFeature.Action.SubagentsPopped(entry.index))
            }
        },
        modifier = Modifier.fillMaxSize(),
        enabled = !catchingUp && !drawerOpen && !view.isSessionDrawerPresented && !sheetPresented,
        root = {
            SessionView(
                root,
                navigationIcon = { HomeDrawerButton(store, view, usesSidebar) },
                listContext = listContext,
                onCatchUpOpen = if (catchingUp) onCatchUpOpen else null,
                onConnectionAction = { action -> store.send(serverConnectionAction(action, store.state.server)) },
            )
        },
    ) { page ->
        when (val entry = known[page.id]) {
            is StackEntry.Subagent -> {
                val child = subagent(entry.navigationID)
                val project by child.observe { SessionListContext.project(it.session, store.state.projects) }
                SessionView(
                    child,
                    listContext = SessionListContext(project = project),
                    onBack = {
                        val index = store.state.subagentStack.indexOfFirst { it.navigationID == entry.navigationID }
                        if (index >= 0) store.send(HomeFeature.Action.SubagentsPopped(index))
                    },
                    hostedCall = call,
                )
            }
            is StackEntry.Web -> SessionBrowserPage(if (entry.owner == null) root else subagent(entry.owner), Modifier.fillMaxSize())
            null -> Unit
        }
    }
    // Stores for subagents that left go once their pages have.
    val liveIDs = entries.mapNotNull { (it as? StackEntry.Subagent)?.navigationID }.toSet()
    SideEffect { subagentStores.keys.retainAll { it in liveIDs || known[it] != null } }
}

/**
 * What a session screen shows of the drawer's knowledge of it: its project, pin, read and mute
 * state, the drawer button's dot for other sessions, and the connection pill, outside Catch Up.
 */
internal fun HomeFeature.State.sessionListContext(session: OpenCodeSession?): SessionListContext {
    val listed = session?.let { s -> sessions.firstOrNull { it.id == s.id } }
    val isCatchUp = catchUp != null
    return SessionListContext(
        project = SessionListContext.project(session, projects),
        isPinned = listed?.let { isPinned(it.id) },
        isUnread = listed?.let { isUnread(it) } ?: false,
        isMuted = listed?.let { isMuted(it.id) } ?: false,
        elsewhere = if (isCatchUp) null else drawerBadge(excluding = session?.id),
        connection = if (isCatchUp) null else serverConnectionNotice(),
    )
}

/**
 * What the pill says of the connection, from the displayed status, so a reconnect still in its
 * grace says nothing.
 */
internal fun HomeFeature.State.serverConnectionNotice(): ServerConnectionNotice? =
    ServerConnectionNotice(
        status = connection.displayedStatus,
        credentialsRejected = credentialsRejected,
        errorMessage = connectionErrorMessage,
        server = server,
        isSwitching = switchingTo != null,
    )

/** The pill's action, as Home's. */
internal fun serverConnectionAction(action: ServerConnectionNotice.Action, server: OpenCodeServer): HomeFeature.Action =
    when (action) {
        ServerConnectionNotice.Action.retry -> HomeFeature.Action.RetryConnectionTapped
        ServerConnectionNotice.Action.pairAgain -> HomeFeature.Action.Delegate(HomeFeature.DelegateAction.PairServerRequested)
        ServerConnectionNotice.Action.editServer -> HomeFeature.Action.Delegate(HomeFeature.DelegateAction.EditServerRequested(server))
    }

/**
 * The session header's drawer button: opens the drawer, or toggles the sidebar beside a wide
 * window.
 */
@Composable
private fun HomeDrawerButton(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    view: HomeViewState,
    usesSidebar: Boolean,
) {
    val badge by store.observe { if (it.catchUp != null) null else it.drawerBadge(excluding = it.sessionDetail?.session?.id) }
    DrawerButton(
        badge = badge,
        onOpenSessions = {
            if (usesSidebar) {
                store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.SidebarToggled))
            } else {
                view.isSessionDrawerPresented = true
            }
        },
    )
}

/**
 * The real session screen for the card behind the open one, rendered from its cached transcript by
 * an inert store. One store for the preview host: its state is replaced in place, so its screen's
 * scaffold is reused across swipes. Inert: it reads nothing and sends nothing, and the stack hides
 * it from TalkBack.
 */
@Composable
private fun CatchUpPreviewCard(store: StoreOf<HomeFeature.State, HomeFeature.Action>, sessionID: String, revision: Int) {
    val home = store.state
    val session = remember(sessionID, revision) { home.sessions.firstOrNull { it.id == sessionID } }
    if (session == null) {
        Box(Modifier.fillMaxSize().background(OpenCodeTheme.colors.canvas))
        return
    }
    val previews = remember { CatchUpPreviewStores() }
    DisposableEffect(previews) { onDispose { previews.close() } }
    val preview = previews.store(session, home)
    SideEffect { previews.commit() }
    // A picture of the next session: nothing in it moves TalkBack's focus.
    CompositionLocalProvider(LocalAccessibilityFocusMoves provides false) {
        SessionView(
            preview,
            Modifier.fillMaxSize().clearAndMergeSemantics(),
            listContext = remember(session, revision) { home.sessionListContext(session) },
            onCatchUpOpen = {},
            isInertPreview = true,
        )
    }
}

/**
 * The inert store behind the Catch Up preview card, rebuilt from what Home knows (cached
 * transcript, run state, remembered snapshot) only when that changes. Its reducer honours the
 * renderer's first-frame event, as the live screen does, and nothing else: a preview performs no
 * loading, request handling or read receipts.
 */
internal class CatchUpPreviewStores {
    private data class Inputs(
        val session: OpenCodeSession,
        val items: List<OpenCodeTranscriptItem>?,
        val isRunning: Boolean,
        val snapshot: SessionFeature.SwitchSnapshot?,
        val activeFamilyIDs: Set<String>,
    )

    private var inputs: Inputs? = null
    private var store: Store<SessionFeature.State, SessionFeature.Action>? = null
    private var pending: SessionFeature.State? = null

    fun store(session: OpenCodeSession, home: HomeFeature.State): StoreOf<SessionFeature.State, SessionFeature.Action> {
        val items = home.transcriptCache[session.id]
        val isRunning = session.id in home.activeSessionIDs
        val snapshot = home.sessionSnapshots[session.id]
        val familyIDs = snapshot?.family?.mapTo(HashSet()) { it.id } ?: setOf(session.id)
        val active = if (home.activeSessionIDs.isEmpty()) emptySet() else home.activeSessionIDs intersect familyIDs
        val next = Inputs(session, items, isRunning, snapshot, active)
        val existing = store
        if (existing != null && inputs == next) return existing
        var state = SessionFeature.State.session(home.server, session, projects = home.projects, cachedItems = items, isRunning = isRunning)
        // Marked as the card will be once it's open, so it doesn't jump then.
        state = home.applyingOpening(session, detail = home.seedingSessionSnapshot(state)).copy(readsOnView = false)
        // Inert: its shell glyph doesn't probe the server either.
        state = state.withRequestsConnectionIsAvailable(false)
        if (existing != null && inputs?.session?.id == session.id && existing.state.hasRenderedFirstContentFrame) {
            state = state.copy(hasRenderedFirstContentFrame = true)
        }
        inputs = next
        if (existing != null) {
            // Applied after composition ([commit]): one host, one store, its state replaced in place.
            pending = state
            return existing
        }
        val created = Store<SessionFeature.State, SessionFeature.Action>(
            state,
            Reducer { current, action ->
                when (action) {
                    SessionFeature.Action.FirstContentFrameRendered -> Reduced(current.copy(hasRenderedFirstContentFrame = true))
                    is SessionFeature.Action.PreviewStateReplaced -> Reduced(action.state)
                    else -> Reduced(current)
                }
            },
        )
        store = created
        return created
    }

    fun commit() {
        val next = pending ?: return
        pending = null
        store?.send(SessionFeature.Action.PreviewStateReplaced(next))
    }

    fun close() {
        store?.close()
        store = null
    }
}

@Composable
private fun HomeSidebarEffects(store: StoreOf<HomeFeature.State, HomeFeature.Action>, view: HomeViewState, usesSidebar: Boolean) {
    val opensDrawerForPairing by store.observe { it.opensDrawerForPairing }
    LaunchedEffect(usesSidebar) {
        if (usesSidebar) view.isSessionDrawerPresented = false
        store.send(HomeFeature.Action.DrawerLabelAvailabilityChanged(!usesSidebar))
    }
    // A computer just paired: open its sessions as the drawer button does.
    LaunchedEffect(opensDrawerForPairing) {
        if (!opensDrawerForPairing || !store.state.opensDrawerForPairing) return@LaunchedEffect
        store.send(HomeFeature.Action.DrawerOpenRequestHandled)
        if (view.usesSidebar) {
            if (!store.state.isSidebarShown) store.send(HomeFeature.Action.Settings(HomeSettingsFeature.Action.SidebarToggled))
        } else {
            view.isSessionDrawerPresented = true
        }
    }
}

@Composable
internal fun HomeLifecycle(store: StoreOf<HomeFeature.State, HomeFeature.Action>, view: HomeViewState) {
    val serverID by store.observe { it.server.persistenceID }
    val settingsShown by store.observe { it.isSettingsShown }
    val drawerCloseRequests by store.observe { it.drawerCloseRequests }
    val selectedSessionID by store.observe { it.sessionDetail?.session?.id }
    val server by store.observe { it.server }
    val isVoiceAvailable = rememberVoiceAvailable(server)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Keyed by the server: a switch from the drawer swaps Home's state in place, and the new
    // server's Home starts here like a fresh one. Retained across activity recreation (dark mode,
    // font size, locale) so the running event stream stays on its socket.
    val startedServerID = retain { arrayOfNulls<String>(1) }
    LaunchedEffect(serverID) {
        if (startedServerID[0] == serverID) return@LaunchedEffect
        startedServerID[0] = serverID
        store.send(HomeFeature.Action.Task)
        store.send(HomeFeature.Action.NotificationSettingsRequested)
    }
    val firstServer = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(serverID) {
        val previous = firstServer.value
        firstServer.value = serverID
        if (previous == null || previous == serverID) return@LaunchedEffect
        view.cancelPinDrag()
        view.endSearch()
    }
    // Search belongs to one visit to the drawer on one server.
    LaunchedEffect(view) {
        snapshotFlow { view.isSessionDrawerPresented }.drop(1).collect { open ->
            if (!open) {
                view.cancelPinDrag()
                view.endSearch()
            }
            // A new visit to the drawer starts from the session on screen.
            if (open) view.chosenSessionID = null
        }
    }
    LaunchedEffect(selectedSessionID) {
        if (selectedSessionID != null && selectedSessionID == view.chosenSessionID) view.chosenSessionID = null
    }
    val firstCloseRequest = remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(drawerCloseRequests) {
        val previous = firstCloseRequest.value
        firstCloseRequest.value = drawerCloseRequests
        if (previous != null && previous != drawerCloseRequests) view.closeSessionDrawer()
    }
    // Settings changes these outside any Home action; the view sees them and tells Home, so the
    // Start Call quick action follows.
    LaunchedEffect(serverID, isVoiceAvailable) {
        store.send(HomeFeature.Action.SystemPublishRequested(isVoiceAvailable))
    }
    // RESUMED is active, STARTED inactive, below that the background. A configuration change's stop
    // and the recreated activity's first resume aren't a leave/return cycle.
    val activity = LocalActivity.current
    val changingConfigurations = { activity?.isChangingConfigurations == true }
    val currentStore by rememberUpdatedState(store)
    val currentView by rememberUpdatedState(view)
    val leftActive = retain { booleanArrayOf(false) }
    DisposableEffect(lifecycle) {
        currentView.isSceneActive = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var catchingUp = true
        val observer = LifecycleEventObserver { _, event ->
            currentView.isSceneActive = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (catchingUp || changingConfigurations()) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!leftActive[0]) return@LifecycleEventObserver
                    leftActive[0] = false
                    currentStore.send(HomeFeature.Action.SceneBecameActive)
                    currentStore.send(HomeFeature.Action.NotificationSettingsRequested)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    leftActive[0] = true
                    currentView.cancelPinDrag()
                    currentStore.send(HomeFeature.Action.TranscriptCacheFlushRequested)
                }
                Lifecycle.Event.ON_STOP -> {
                    leftActive[0] = true
                    currentStore.send(HomeFeature.Action.SceneEnteredBackground)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        catchingUp = false
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * The presented Settings ([HomeFeature.State.settings]) as `:feature:settings`' [SettingsView]. It
 * keeps showing the last state while it slides out (Home drops the state as it closes); anything
 * sent then is dropped by the presentation.
 */
@Composable
private fun HomeSettings(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val settings = remember(store) {
        var last: SettingsFeature.State? = null
        store.scope<HomeFeature.State, HomeFeature.Action, SettingsFeature.State, SettingsFeature.Action>(
            state = { home -> home.settings?.also { last = it } ?: last ?: SettingsFeature.State(server = home.server) },
            action = { HomeFeature.Action.SettingsScreen(PresentationAction.Presented(it)) },
        )
    }
    CompositionLocalProvider(LocalAppearanceTranscriptPreview provides AppearanceTranscriptPreviewRows) { SettingsView(settings) }
}

/**
 * Show Welcome: the first-run welcome, faded in over Settings and Home, never a cover, so pairing's
 * scanner and the server form still present over it from the root. Close (and Back) dismiss it.
 * [mark] is the app mark, from `:app`'s resources.
 */
@Composable
private fun HomeWelcome(store: StoreOf<HomeFeature.State, HomeFeature.Action>, mark: Painter?) {
    val shown by store.observe { it.welcome != null }
    val dismiss = { store.send(HomeFeature.Action.Welcome(PresentationAction.Dismiss)) }
    val welcome = remember(store) {
        var last: WelcomeFeature.State? = null
        store.scope<HomeFeature.State, HomeFeature.Action, WelcomeFeature.State, WelcomeFeature.Action>(
            state = { home -> home.welcome?.also { last = it } ?: last ?: WelcomeFeature.State(isPreview = true) },
            action = { HomeFeature.Action.Welcome(PresentationAction.Presented(it)) },
        )
    }
    AnimatedVisibility(shown, enter = fadeIn(tween(WELCOME_FADE_MS)), exit = fadeOut(tween(WELCOME_FADE_MS))) {
        BackHandler(onBack = dismiss)
        WelcomeView(welcome, progress = null, problem = null, mark = mark, onClose = dismiss)
    }
}

private const val WELCOME_FADE_MS = 300

/**
 * Back closes Settings at its root, not what waits beneath it. `:feature:settings`'
 * [SettingsPresentation] registers its predictive handler with Home's first frame, before the
 * session's stack (subagent pushes) composes, so while a subagent is pushed beneath, this one —
 * registered anew each time Settings opens, so last — takes Back instead. (The drawer's and Catch
 * Up's own handlers stand down while Settings is up: `SessionDrawerContainer(isSheetPresented)`.)
 */
@Composable
private fun HomeSettingsBack(store: StoreOf<HomeFeature.State, HomeFeature.Action>) {
    val shown by store.observe { it.settings != null }
    val atRoot by store.observe { it.settings?.path?.isEmpty() ?: true }
    val pushedBeneath by store.observe { it.subagentStack.isNotEmpty() }
    key(shown) {
        BackHandler(enabled = shown && atRoot && pushedBeneath) {
            store.send(HomeFeature.Action.SettingsScreen(PresentationAction.Presented(SettingsFeature.Action.BackTapped)))
        }
    }
}

private const val TIME_REVEAL_REACH = 76f

/**
 * Voice set up for [server], recomputed whenever a setting it reads changes (Settings changes them
 * outside any Home action).
 */
@Composable
private fun rememberVoiceAvailable(server: OpenCodeServer): Boolean {
    val preferences = remember { VoiceRoutePreferences() }
    val enabled by preferences.enabled.flow.collectAsState()
    val engine by preferences.engine.flow.collectAsState()
    val openAIKey by preferences.openAIKey.flow.collectAsState()
    val xaiKey by preferences.xaiKey.flow.collectAsState()
    val provider by preferences.provider.flow.collectAsState()
    val voiceServer by preferences.voiceServer.flow.collectAsState()
    val support by preferences.support.flow.collectAsState()
    return remember(server, enabled, engine, openAIKey, xaiKey, provider, voiceServer, support) {
        preferences.isAvailable(server)
    }
}
