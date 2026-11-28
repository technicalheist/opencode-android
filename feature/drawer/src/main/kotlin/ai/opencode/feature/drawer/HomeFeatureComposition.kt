package ai.opencode.feature.drawer

import ai.opencode.server.EventStreamFocusClient
import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.combine
import ai.opencode.core.store.forEach
import ai.opencode.core.store.ifLet
import ai.opencode.core.store.onChange
import ai.opencode.core.store.scope
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.media.PageReaderClient
import ai.opencode.feature.drawer.notices.DeliveredNotificationsClient
import ai.opencode.devtools.traces.TraceUploadsFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.hasLiveDictation
import ai.opencode.feature.session.dictationHold
import ai.opencode.feature.session.dictationPhase
import ai.opencode.feature.session.dictationPress
import ai.opencode.feature.session.unstartedUploadIDs
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.feature.system.liveactivity.DictationActivityAttributes
import ai.opencode.feature.system.liveactivity.DictationLiveActivity
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceLiveActivity
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import java.util.UUID

// How Home's reducers stack. Children settle after Home's own coordination (`core`), session
// screens run as presented and identified children, and a chain of observers follows state Home
// derives. Fast streaming actions ([isFastStreamAction]) skip the observer chain.

private object SessionDetailSlot
private object SettingsSlot
private object WelcomeSlot
private object SubagentStackSlot

internal fun HomeFeature.builtBody(): Reducer<State, Action> {
    val full = fullBody()
    val sessions = detailSessionsCore()
    return Reducer { state, action ->
        val isFast = isFastStreamAction(state, action)
        var reduced = if (isFast) sessions.reduce(state, action) else full.reduce(state, action)
        // The fast path skips the watches below it, the followed run's too.
        if (isFast && reduced.state.streamMovesSessionActivity(action)) {
            val synced = syncSessionActivity(reduced.state)
            reduced = Reduced(synced.state, Effect.merge(reduced.effect, synced.effect))
        }
        var s = reduced.state
        // The Catch Up preview's revision bumps when its inputs change for the same session.
        val oldSlice = state.catchUpPreviewSlice
        val newSlice = s.catchUpPreviewSlice
        if (oldSlice != newSlice && oldSlice != null && newSlice != null && oldSlice.sessionID == newSlice.sessionID &&
            (oldSlice.items != newSlice.items || oldSlice.isRunning != newSlice.isRunning ||
                oldSlice.snapshot != newSlice.snapshot || oldSlice.activeFamilyIDs != newSlice.activeFamilyIDs)
        ) {
            s = s.copy(previewRevision = s.previewRevision + 1)
        }
        // Unread or waiting work arriving, by whatever action (a failed receipt included), ends an
        // All Caught Up finish: its `.success` and rest are cancelled.
        if (!s.catchUpState.justFinished || s.catchUpSessions.isEmpty()) return@Reducer Reduced(s, reduced.effect)
        Reduced(s, Effect.merge(reduced.effect, Effect.send(Action.CatchUp(CatchUpFeature.Action.WorkArrived))))
    }
}

/**
 * Settings runs presented over Home's reducers ([State.settings]): its effects end when it closes,
 * whichever action closes it. While it's up it's told whenever Home's connection or notification
 * state changes (opening fills both in). What's New read on the pushed page is seen once the page
 * closes, so its dots stay while you read and the drawer's row leaves on the way back.
 */
private fun HomeFeature.fullBody(): Reducer<State, Action> =
    ifLet<State, Action, SettingsFeature.State, SettingsFeature.Action>(
        id = SettingsSlot,
        parent = ifLet<State, Action, WelcomeFeature.State, WelcomeFeature.Action>(
            id = WelcomeSlot,
            parent = homeReducers(),
            state = { it.welcome },
            setState = { s: State, c: WelcomeFeature.State? -> s.copy(welcome = c) },
            action = { (it as? Action.Welcome)?.action },
            embed = Action::Welcome,
            child = WelcomeFeature(),
        ),
        state = { it.settings },
        setState = { s: State, c: SettingsFeature.State? -> s.copy(settings = c) },
        action = { (it as? Action.SettingsScreen)?.action },
        embed = Action::SettingsScreen,
        child = SettingsFeature(),
    )
        .onChange({ it.settings?.whatsNew }) { old, new, state ->
            if (old == null || new != null) Reduced(state) else Reduced(settingsClosed(old, state))
        }
        .onChange({ it.search }) { _, _, state -> sessionSearchChanged(state) }
        // Only the screen on top shows its transcript: a subagent pushed over it, its browser or
        // Settings covers it (what arrives is new there).
        .onChange({ it.transcriptCoverIsStale }) { _, _, state ->
            Reduced(if (state.transcriptCoverIsStale) state.syncingTranscriptCover() else state)
        }
        .onChange({ it.pollingPauseIsStale }) { _, _, state ->
            if (state.pollingPauseIsStale) state.syncingPollingPause() else Reduced(state)
        }
        // The stream sheds other sessions' deltas under load, never these. Watched over all of
        // Home, so Catch Up reconciling to another card counts too (cd04514c).
        .onChange({ it.streamFocusSessionIDs }) { _, new, state ->
            dependency(EventStreamFocusClient).publish(streamFocusOwner, new)
            Reduced(state)
        }
        // The call's delegator reads what the drawer shows from here, watched over all of Home too.
        .onChange({ it.voiceSessionBoardInput }) { _, _, state ->
            dependency(VoiceSessionBoardClient).publish(state.voiceSessionBoard)
            Reduced(state)
        }
        .onChange({ it.settingsHomeInput }) { old, new, state ->
            if (old == null || new == null) {
                Reduced(state)
            } else {
                Reduced(state, Effect.send(Action.SettingsScreen(PresentationAction.Presented(new))))
            }
        }
        .onChange({ it.settingsNotificationsInput }) { old, new, state ->
            if (old == null || new == null) {
                Reduced(state)
            } else {
                Reduced(state, Effect.send(Action.SettingsScreen(PresentationAction.Presented(new))))
            }
        }

private fun HomeFeature.homeReducers(): Reducer<State, Action> = combine(
    scope(
        state = { it.traces },
        setState = { s: State, c: TraceUploadsFeature.State -> s.copy(traces = c) },
        action = { (it as? Action.Traces)?.action },
        embed = Action::Traces,
        child = TraceUploadsFeature(),
    ),
    // Save the outgoing member before the presented reducer selects another one. Drawer navigation
    // instead saves in makeDetail/makeDraft.
    Reducer { state, action ->
        val detailAction = (action as? Action.SessionDetail)?.action as? PresentationAction.Presented
        if (detailAction?.action is SessionFeature.Action.FamilyPicked) Reduced(state.rememberingSession()) else Reduced(state)
    },
    detailReducer().homeChildrenObservers(this),
    // Whatever replaced the detail an uncached tap's read belongs to (New Session, a voice draft,
    // Catch Up, a deletion, another tap) ends it.
    Reducer { state, _ ->
        val fetch = state.selectedTranscriptFetch
        if (fetch == null || fetch.transition == state.detailGeneration) {
            Reduced(state)
        } else {
            Reduced(state.copy(selectedTranscriptFetch = null), Effect.cancel(HomeFeature.CancelID.SelectedTranscript))
        }
    },
    // The pairing's drawer opens only over the bare draft it was asked on: after any action that
    // navigates or presents anything, it's dropped.
    Reducer { state, _ ->
        if (state.drawerOpenRequest == null || state.opensDrawerForPairing) {
            Reduced(state)
        } else {
            AppLog.app.info { "paired computer drawer open dropped" }
            Reduced(state.copy(drawerOpenRequest = null))
        }
    },
    // Absorbing shared files is a pure state change; start their uploads here.
    Reducer { state, _ ->
        if (state.sessionDetail?.unstartedUploadIDs.isNullOrEmpty()) {
            Reduced(state)
        } else {
            Reduced(state, Effect.send(Action.detail(SessionFeature.Action.queuedUploadsStarted)))
        }
    },
    // Only list and attention changes move the stack; skip the transcript's own high-frequency
    // traffic and actions that already maintain `catchUp` or only update the transcript cache.
    Reducer { state, action ->
        val skips = when (action) {
            is Action.SessionDetail, is Action.SubagentStack, is Action.CatchUp, is Action.TranscriptPrefetched, is Action.CachedTranscriptRefreshed,
            is Action.SelectedTranscriptResponse, is Action.ViewAcknowledged -> true
            is Action.ServerEvent -> action.event is OpenCodeServerEvent.ContentBatch || action.event is OpenCodeServerEvent.MessageInvalidated
            else -> false
        }
        if (skips) return@Reducer Reduced(state)
        val front = state.catchUp?.queue?.firstOrNull()
        val s = state.reconcilingCatchUp()
        val openedID = s.catchUp?.queue?.firstOrNull()
        if (openedID == null || openedID == front) Reduced(s) else cancelBackgroundTranscriptReads(s, setOf(openedID))
    },
)

/**
 * `detailSessions`, dropping a gone screen's late actions first: a popped subagent's view stays in
 * the page stack for its exit animation, and async UI callbacks or queued forwards can land after
 * its state has left [State.subagentStack].
 */
private fun HomeFeature.detailReducer(): Reducer<State, Action> {
    val chain = detailSessions()
    return Reducer<State, Action> { state, action ->
        if (action is Action.SubagentStack && state.subagentStack.none { it.navigationID == action.id }) return@Reducer Reduced(state)
        if (action is Action.SessionDetail && state.sessionDetail == null) return@Reducer Reduced(state)
        chain.reduce(state, action)
    }
        // A Send Later share sends itself once its composer has it.
        .onChange({ it.sessionDetail?.sharedSend != null }) { _, new, state ->
            if (!new) Reduced(state) else Reduced(state, Effect.send(Action.detail(SessionFeature.Action.SharedSendRequested)))
        }
}

/** Home's own actions, the open session and its pushed subagents. */
private fun HomeFeature.detailSessionsCore(): Reducer<State, Action> {
    val catchUp = CatchUpFeature()
    val notifications = HomeNotificationsFeature()
    val connection = HomeConnectionFeature()
    val inboxSidebar = InboxSidebarFeature()
    val session = SessionFeature()
    val home = combine<State, Action>(
        scope(
            state = { it.catchUpState },
            setState = { s: State, c: CatchUpFeature.State -> s.copy(catchUpState = c) },
            action = { (it as? Action.CatchUp)?.action },
            embed = Action::CatchUp,
            child = catchUp,
        ),
        // Home first coordinates against the previous child state (permission changes and
        // connection recovery), then the children settle that action.
        Reducer { state, action -> core(state, action) },
        scope(
            state = { it.notifications },
            setState = { s: State, c: HomeNotificationsFeature.State -> s.copy(notificationState = c.status) },
            action = ::notificationsAction,
            embed = ::homeAction,
            child = notifications,
        ),
        scope(
            state = { it.inboxSidebar },
            setState = { s: State, c: InboxSidebarFeature.State -> s.copy(inboxSidebarStatus = c.status) },
            action = { (it as? Action.InboxSidebar)?.action },
            embed = Action::InboxSidebar,
            child = inboxSidebar,
        ),
        scope(
            state = { it.connection },
            setState = { s: State, c: HomeConnectionFeature.State -> s.copy(connection = c) },
            action = ::connectionAction,
            embed = ::homeAction,
            child = connection,
        ),
    )
    val presented = ifLet<State, Action, SessionFeature.State, SessionFeature.Action>(
        id = SessionDetailSlot,
        parent = home,
        state = { it.sessionDetail },
        setState = { s: State, d: SessionFeature.State? -> s.copy(sessionDetail = d) },
        action = { (it as? Action.SessionDetail)?.action },
        embed = Action::SessionDetail,
        child = session,
        childID = { it.navigationID },
    )
    return forEach<State, Action, Int, SessionFeature.State, SessionFeature.Action>(
        slot = SubagentStackSlot,
        parent = presented,
        elements = { it.subagentStack },
        setElements = { s: State, list: List<SessionFeature.State> -> s.copy(subagentStack = list) },
        elementID = { it.navigationID },
        action = { (it as? Action.SubagentStack)?.let { a -> a.id to a.action } },
        embed = { id, a -> Action.SubagentStack(id, a) },
        child = session,
    )
}

/** What a pushed subagent's screen leaves behind when it goes. */
private data class StackLeftovers(val pageID: UUID?, val livePress: Int?) {
    constructor(detail: SessionFeature.State) : this(
        pageID = detail.browser.pageID,
        livePress = if (detail.hasLiveDictation) detail.dictationPress else null,
    )
}

/**
 * Home's own actions, the open session and its pushed subagents, and the pages and recordings
 * those take with them when they go; a link tap dropped when its screen stops being the one on
 * top; and background reads resuming once the foreground read settles.
 */
private fun HomeFeature.detailSessions(): Reducer<State, Action> =
    detailSessionsCore()
        // A pushed subagent that leaves takes its page with it, and a recording still running there
        // stops (its words wait in its parked composer).
        .onChange({ state ->
            if (state.subagentStack.isEmpty()) emptyMap() else state.subagentStack.associate { it.navigationID to StackLeftovers(it) }
        }) { old, _, state ->
            val remaining = state.subagentStack.mapTo(HashSet()) { it.navigationID }
            val gone = old.filterKeys { it !in remaining }.values
            if (gone.isEmpty()) return@onChange Reduced(state)
            val dictation = dependency(DictationClient)
            Reduced(
                state,
                Effect.merge(
                    gone.mapNotNull { it.pageID }.map { discardPage(it) } +
                        gone.mapNotNull { it.livePress }.map { press -> Effect.run<Action> { dictation.cancel(press) } },
                ),
            )
        }
        // A link tap waits on the system only while the screen it was tapped on stays the one on
        // screen. Whatever changes that (another session, a subagent pushed or popped, a family
        // switch in place, Catch Up moving to another card or running out) drops the tap: its
        // answer must open nothing. Only the screen that was on top can hold one, and a screen
        // that left ended its own effects.
        .onChange({ it.visibleScreen }) { old, _, state ->
            val id = old.navigationID ?: return@onChange Reduced(state)
            if (old.stackDepth > 0) {
                if (state.subagentStack.firstOrNull { it.navigationID == id }?.linkProbe == null) return@onChange Reduced(state)
                return@onChange Reduced(state, Effect.send(Action.SubagentStack(id, SessionFeature.Action.VisibleScreenChanged)))
            }
            val detail = state.sessionDetail
            if (detail?.navigationID != id || detail.linkProbe == null) return@onChange Reduced(state)
            Reduced(state, Effect.send(Action.detail(SessionFeature.Action.VisibleScreenChanged)))
        }
        // A session replaced on screen (the drawer, New Session, Catch Up, a delete) or one that
        // left takes its in-app browser page with it.
        .onChange({ it.sessionDetail?.browser?.pageID }) { old, new, state ->
            if (old == null || old == new) Reduced(state) else Reduced(state, discardPage(old))
        }
        .onChange({ it.isInitialTranscriptInFlight }) { wasInFlight, _, state ->
            if (!wasInFlight || state.isInitialTranscriptInFlight) Reduced(state) else settleForegroundTranscriptLoad(state)
        }

/**
 * Streaming traffic that skips the observer chain: it moves neither the list, Catch Up nor navigation.
 */
internal fun isFastStreamAction(state: State, action: Action): Boolean = when (action) {
    is Action.ServerEvent -> when (val event = action.event) {
        is OpenCodeServerEvent.ContentBatch ->
            event.sessionID in state.activeSessionIDs || event.events.none { it is OpenCodeLiveContentEvent.StepStarted }
        is OpenCodeServerEvent.MessageInvalidated -> true
        else -> false
    }
    is Action.TranscriptPrefetched, is Action.CachedTranscriptRefreshed -> true
    is Action.SessionDetail -> {
        val presented = (action.action as? PresentationAction.Presented)?.action
        (presented is SessionFeature.Action.ContentBatch || presented is SessionFeature.Action.FamilyRunStateChanged) &&
            state.sessionDetail != null
    }
    else -> false
}

val State.notifications: HomeNotificationsFeature.State
    get() = HomeNotificationsFeature.State(server = server, status = notificationState)

private fun notificationsAction(action: Action): HomeNotificationsFeature.Action? = when (action) {
    Action.NotificationSettingsRequested -> HomeNotificationsFeature.Action.NotificationSettingsRequested
    is Action.NotificationPermissionLoaded -> HomeNotificationsFeature.Action.NotificationPermissionLoaded(action.permission)
    Action.EnableNotificationsTapped -> HomeNotificationsFeature.Action.EnableNotificationsTapped
    is Action.NotificationPermissionRequested -> HomeNotificationsFeature.Action.NotificationPermissionRequested(action.permission)
    is Action.NotificationSettingsOpened -> HomeNotificationsFeature.Action.NotificationSettingsOpened(action.opened)
    Action.NotifyPluginCheckRequested -> HomeNotificationsFeature.Action.NotifyPluginCheckRequested
    is Action.NotifyPluginChecked -> HomeNotificationsFeature.Action.NotifyPluginChecked(action.status)
    else -> null
}

private fun homeAction(action: HomeNotificationsFeature.Action): Action = when (action) {
    HomeNotificationsFeature.Action.NotificationSettingsRequested -> Action.NotificationSettingsRequested
    is HomeNotificationsFeature.Action.NotificationPermissionLoaded -> Action.NotificationPermissionLoaded(action.permission)
    HomeNotificationsFeature.Action.EnableNotificationsTapped -> Action.EnableNotificationsTapped
    is HomeNotificationsFeature.Action.NotificationPermissionRequested -> Action.NotificationPermissionRequested(action.permission)
    is HomeNotificationsFeature.Action.NotificationSettingsOpened -> Action.NotificationSettingsOpened(action.opened)
    HomeNotificationsFeature.Action.NotifyPluginCheckRequested -> Action.NotifyPluginCheckRequested
    is HomeNotificationsFeature.Action.NotifyPluginChecked -> Action.NotifyPluginChecked(action.status)
}

private fun connectionAction(action: Action): HomeConnectionFeature.Action? = when (action) {
    Action.Task -> HomeConnectionFeature.Action.Task
    Action.SceneEnteredBackground -> HomeConnectionFeature.Action.SceneEnteredBackground
    is Action.ServerConnected -> HomeConnectionFeature.Action.ServerConnected(action.server)
    is Action.EventStreamFailed -> HomeConnectionFeature.Action.EventStreamFailed(action.error)
    Action.ConnectionLost -> HomeConnectionFeature.Action.ConnectionLost
    Action.SnapshotFailed -> HomeConnectionFeature.Action.SnapshotFailed
    Action.SnapshotRetryRequested -> HomeConnectionFeature.Action.SnapshotRetryRequested
    Action.LastKnownActivityExpired -> HomeConnectionFeature.Action.LastKnownActivityExpired
    Action.NetworkRerouted -> HomeConnectionFeature.Action.NetworkRerouted
    Action.RetryConnectionTapped -> HomeConnectionFeature.Action.RetryConnectionTapped
    Action.SceneBecameActive -> HomeConnectionFeature.Action.SceneBecameActive
    Action.ReconnectingGraceElapsed -> HomeConnectionFeature.Action.ReconnectingGraceElapsed
    is Action.OfflineGraceElapsed -> HomeConnectionFeature.Action.OfflineGraceElapsed(action.pathDown)
    is Action.ServerEvent -> HomeConnectionFeature.Action.ServerEvent(action.event)
    else -> null
}

private fun homeAction(action: HomeConnectionFeature.Action): Action = when (action) {
    HomeConnectionFeature.Action.Task -> Action.Task
    HomeConnectionFeature.Action.SceneEnteredBackground -> Action.SceneEnteredBackground
    is HomeConnectionFeature.Action.ServerConnected -> Action.ServerConnected(action.server)
    is HomeConnectionFeature.Action.EventStreamFailed -> Action.EventStreamFailed(action.error)
    HomeConnectionFeature.Action.ConnectionLost -> Action.ConnectionLost
    HomeConnectionFeature.Action.SnapshotFailed -> Action.SnapshotFailed
    HomeConnectionFeature.Action.SnapshotRetryRequested -> Action.SnapshotRetryRequested
    HomeConnectionFeature.Action.LastKnownActivityExpired -> Action.LastKnownActivityExpired
    HomeConnectionFeature.Action.NetworkRerouted -> Action.NetworkRerouted
    HomeConnectionFeature.Action.RetryConnectionTapped -> Action.RetryConnectionTapped
    HomeConnectionFeature.Action.SceneBecameActive -> Action.SceneBecameActive
    HomeConnectionFeature.Action.ReconnectingGraceElapsed -> Action.ReconnectingGraceElapsed
    is HomeConnectionFeature.Action.OfflineGraceElapsed -> Action.OfflineGraceElapsed(action.pathDown)
    is HomeConnectionFeature.Action.ServerEvent -> Action.ServerEvent(action.event)
}

/**
 * A session on screen clears its delivered notifications, an answered request clears its needs-input notifications, the icon's badge counts what Catch Up
 * holds, the share sheet offers what the drawer does, and the system publication follows the list.
 */
private fun Reducer<State, Action>.homeChildrenObservers(feature: HomeFeature): Reducer<State, Action> =
    onChange({ it.viewing }) { _, _, state ->
            val threads = state.viewedNotificationThreads
            if (threads.isEmpty()) return@onChange Reduced(state)
            val delivered = dependency(DeliveredNotificationsClient)
            Reduced(state, Effect.run { delivered.removeAll(threads) })
        }
        .onChange({ it.attentionSessionIDs }) { old, new, state ->
            val answered = old - new
            if (answered.isEmpty() || !state.isConnected) return@onChange Reduced(state)
            val delivered = dependency(DeliveredNotificationsClient)
            Reduced(state, Effect.run { delivered.removeNeedsInput(answered) })
        }
        // The icon's badge counts what Catch Up holds.
        .onChange({ it.appBadgePublication }) { old, new, state ->
            val next = new.ledger
            if (next == old.ledger) return@onChange Reduced(state)
            Reduced(state, feature.publishAppBadge(state, next))
        }
        // The share sheet offers what the drawer does, kept current for a share sheet opened over the app.
        .onChange({ it.shareTargetsPublication }) { old, _, state ->
            if (!state.hasConnected) return@onChange Reduced(state)
            val next = state.shareTargets
            if (old.hasConnected && old.targets() == next) return@onChange Reduced(state)
            dependency(ShareInboxClient).publishTargets(next)
            Reduced(state)
        }
        // Shortcuts, launcher actions and search offer what the drawer does.
        .onChange({ it.systemPublication }) { _, _, state ->
            feature.publishToSystem(state)
            Reduced(state)
        }
        // Whoever moves a live call (the drawer, New Session, a draft's project) only edits its
        // target; the voice model hears about it here.
        .onChange({ it.sessionDetail?.voice?.placement }) { old, new, state ->
            // Releasing what was let go clears it; that alone isn't a move.
            if (old == null || new == null || (old.target == new.target && new.abandonedCallIDs.isEmpty())) {
                Reduced(state)
            } else {
                Reduced(state, Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.TargetChanged))))
            }
        }
        // What the call's ongoing notification shows follows the call, changing only when its
        // content changes. The call's foreground service (`:feature:voice`'s `VoiceCallService`) is
        // that notification, so Home only keeps the content and posts no second one.
        .onChange({ it.sessionDetail?.voice?.let(::liveActivityCall) }) { _, call, state ->
            val content = VoiceLiveActivity.content(call, state.voiceCallActivity, dependency(DateClient).now())
            if (content == state.voiceCallActivity) Reduced(state) else Reduced(state.copy(voiceCallActivity = content))
        }
        // The recording's ongoing notification follows it across sessions, pushed only when what it
        // shows changes. A call starting stops the recording, and the call's own takes over.
        .onChange({ liveActivityRecording(it.recordingDetail) }) { _, recording, state ->
            val content = DictationLiveActivity.content(recording, state.dictationActivity, dependency(DateClient).now())
            if (content == state.dictationActivity) return@onChange Reduced(state)
            val attributes = recording?.let { DictationActivityAttributes(serverID = it.serverID) }
            val client = dependency(DictationLiveActivityClient)
            Reduced(state.copy(dictationActivity = content), Effect.run { client.update(content, attributes) })
        }
        // A run a send started follows its session's state here, from wherever it changes (stream,
        // snapshots, the open screen, the background wait).
        .onChange({ it.sessionActivityRun }) { _, _, state -> feature.syncSessionActivity(state) }
        .onChange({ if (LabMarks.isEnabled) it.sessionIndicators else emptyMap() }) { old, _, state ->
            for ((sessionID, indicator) in state.sessionIndicators) {
                if (old[sessionID] != indicator) LabMarks.mark("indicator:${indicator.labMarkName}", sessionID = sessionID)
            }
            Reduced(state)
        }

/** The part of a call its ongoing notification shows. */
internal fun liveActivityCall(voice: VoiceFeature.State): VoiceLiveActivity.Call = VoiceLiveActivity.Call(
    phase = when (voice.phase) {
        VoiceFeature.State.Phase.Connecting -> VoiceLiveActivity.CallPhase.Connecting
        VoiceFeature.State.Phase.Live -> VoiceLiveActivity.CallPhase.Live
        is VoiceFeature.State.Phase.EndingAfterGoodbye -> VoiceLiveActivity.CallPhase.EndingAfterGoodbye
        VoiceFeature.State.Phase.Ended -> VoiceLiveActivity.CallPhase.Ended
        is VoiceFeature.State.Phase.Failed -> VoiceLiveActivity.CallPhase.Failed
        VoiceFeature.State.Phase.Dropped -> VoiceLiveActivity.CallPhase.Dropped
    },
    isMuted = voice.isMuted,
    // Already delayed: a reconnect shorter than a second never sets it.
    isReconnecting = voice.isReconnecting,
    serverID = voice.target.server.id,
    sessionID = voice.target.sessionID ?: "",
)

/** Only a hands-free recording, outside a call. */
internal fun liveActivityRecording(detail: SessionFeature.State?): DictationLiveActivity.Recording? =
    DictationLiveActivity.recording(
        hasDetail = detail != null,
        callIsTerminal = detail?.voice?.phase?.isTerminal ?: true,
        isRecording = detail?.dictationPhase == DictationFeature.Phase.Recording,
        holdIsLocked = detail?.dictationHold?.isLocked ?: true,
        sessionTitle = detail?.session?.title,
        serverID = detail?.server?.id ?: "",
    )

/** A page no screen shows any more is let go. */
internal fun discardPage(id: UUID): Effect<Action> {
    val pageReader = dependency(PageReaderClient)
    return Effect.run { pageReader.discard(id) }
}
