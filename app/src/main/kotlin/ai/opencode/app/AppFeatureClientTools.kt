package ai.opencode.app

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.combine
import ai.opencode.core.store.onChange
import ai.opencode.core.store.scope
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.CallAudioFailure
import ai.opencode.feature.voice.VoiceCallAudioFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceOpenLinkFeature
import ai.opencode.feature.voice.VoiceOpenSessionFeature
import ai.opencode.feature.voice.VoiceScreenRequestFeature
import ai.opencode.feature.voice.sessionDelegatorElsewhereID
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolsTarget
import kotlin.coroutines.cancellation.CancellationException

// The phone's client tools, all for a live call.

/** The directory a call's delegator session lives in, read from the server. */
data class ClientToolsLocation(val sessionID: String, val directory: String)

/**
 * The phone's tools' socket: Home's server, in the call's directory, for the length of the call.
 */
internal val AppFeature.State.clientToolsTarget: ClientToolsTarget?
    get() {
        // Keep the reply's socket through retargeting and until its reply is sent.
        if (voiceOpenSession.inFlight) return clientTools.target
        val tools = offeredClientTools
        if (tools.isEmpty()) return null
        val home = home ?: return null
        val voice = home.sessionDetail?.voice ?: return null
        if (voice.phase.isTerminal) return null
        return ClientToolsTarget(home.server, voice.target.directory, tools)
    }

/** The session a live call's delegator steers in another directory; it gets its own socket. */
internal val AppFeature.State.voiceDelegatorSessionID: String?
    get() {
        if (offeredClientTools.isEmpty()) return null
        return home?.sessionDetail?.voice?.sessionDelegatorElsewhereID
    }

/** The second socket, while the delegator's session lives outside the call's directory. */
internal val AppFeature.State.delegatorClientToolsTarget: ClientToolsTarget?
    get() {
        if (voiceOpenSession.inFlight) return delegatorClientTools.target
        val home = home ?: return null
        val id = voiceDelegatorSessionID ?: return null
        val location = voiceDelegatorLocation ?: return null
        if (location.sessionID != id) return null
        if (location.directory == clientToolsTarget?.directory) return null
        return ClientToolsTarget(home.server, location.directory, offeredClientTools)
    }

/** The tools the phone offers, plus opening sessions during a live session-delegator call. */
internal val AppFeature.State.offeredClientTools: Set<ClientTool>
    get() {
        val voice = home?.sessionDetail?.voice
        if (voice == null || voice.phase != VoiceFeature.State.Phase.Live || !voice.usesSessionDelegator) return clientTools.tools
        return clientTools.tools + ClientTool.voiceOpenSession
    }

/** What `VoiceOpenSessionFeature` checks a voice open against: the screen, the sockets and the navigation. */
internal val AppFeature.State.openSessionEnvironment: VoiceOpenSessionFeature.Environment
    get() {
        val home = home
        val voice = home?.sessionDetail?.voice
        val elsewhere = voice?.sessionDelegatorElsewhereID
        val location = voiceDelegatorLocation
        val slot: ClientToolsFeature.State? = when {
            elsewhere == null -> clientTools
            location?.sessionID != elsewhere -> null
            location.directory == clientTools.target?.directory -> clientTools
            else -> delegatorClientTools
        }
        val attached = slot != null && slot.status == ClientToolsFeature.State.Status.Attached && slot.connection != null &&
            slot.target?.tools?.contains(ClientTool.voiceOpenSession) == true
        var navigation: VoiceOpenSessionFeature.Navigation? = null
        if (home != null) {
            val stack = mutableListOf<Int>()
            val presentations = mutableListOf<VoiceOpenSessionFeature.Presentation>()
            val detail = home.sessionDetail
            val mounted = if (detail != null) listOf(detail) + home.subagentStack else home.subagentStack
            if (detail != null) stack.add(detail.navigationID)
            for (pushed in home.subagentStack) stack.add(pushed.navigationID)
            for (screen in mounted) {
                presentations.add(
                    VoiceOpenSessionFeature.Presentation(
                        picker = screen.activePicker?.rawValue,
                        folder = screen.folderBrowser != null,
                        shell = screen.shellOutput?.shell?.id,
                    ),
                )
            }
            navigation = VoiceOpenSessionFeature.Navigation(
                rootGeneration = home.detailGeneration,
                stack = stack,
                sessionID = home.topDetail?.session?.id,
                browserID = home.topDetail?.browser?.pageID,
                catchUp = home.catchUp != null,
                // What's New is a Settings root on Android, so `settings` covers both.
                settings = home.isSettingsShown,
                deletion = home.pendingDeletion?.id,
                presentations = presentations,
            )
        }
        return VoiceOpenSessionFeature.Environment(
            screen = VoiceOpenSessionFeature.Screen(
                sessionID = home?.topDetail?.session?.id,
                visible = clientToolScreenBlock(includingRequestDock = false) == null,
                live = voice?.phase == VoiceFeature.State.Phase.Live,
            ),
            foreground = isInForeground,
            attached = attached,
            navigation = navigation,
            callID = voice?.callLogID,
        )
    }

/** Why a screen request can't be put to the person now, or null when its card would show. */
internal val AppFeature.State.screenRequestRefusal: String?
    get() = when (clientToolCardBlock) {
        ClientToolCardBlock.NoCall -> VoiceScreenRequestFeature.Refusal.noCall
        ClientToolCardBlock.Background -> VoiceScreenRequestFeature.Refusal.background
        ClientToolCardBlock.Covered -> VoiceScreenRequestFeature.Refusal.covered
        // Its own card refuses a second request itself; the other's here.
        null -> if (home?.sessionDetail?.voice?.openLink?.pending != null) VoiceScreenRequestFeature.Refusal.otherRequest else null
    }

/** Why a link can't be put to the person now, or null when its card would show. */
internal val AppFeature.State.openLinkRefusal: String?
    get() = when (clientToolCardBlock) {
        ClientToolCardBlock.NoCall -> VoiceOpenLinkFeature.Refusal.noCall
        ClientToolCardBlock.Background -> VoiceOpenLinkFeature.Refusal.background
        ClientToolCardBlock.Covered -> VoiceOpenLinkFeature.Refusal.covered
        null -> {
            // Nor while a shared screen is being captured: the card would be in it.
            val screen = home?.sessionDetail?.voice?.screenRequest
            if (screen != null && (screen.pending != null || screen.capturing != null)) VoiceOpenLinkFeature.Refusal.busy else null
        }
    }

private enum class ClientToolCardBlock { NoCall, Background, Covered }

/** What stops a client tool's card showing above the call's composer. */
private val AppFeature.State.clientToolCardBlock: ClientToolCardBlock?
    get() = clientToolScreenBlock(includingRequestDock = true)

/** The dock is part of the session, but covers the slot for consent cards. */
private fun AppFeature.State.clientToolScreenBlock(includingRequestDock: Boolean): ClientToolCardBlock? {
        val home = home ?: return ClientToolCardBlock.NoCall
        val detail = home.sessionDetail ?: return ClientToolCardBlock.NoCall
        val voice = detail.voice ?: return ClientToolCardBlock.NoCall
        if (voice.phase != VoiceFeature.State.Phase.Live) return ClientToolCardBlock.NoCall
        if (!isInForeground) return ClientToolCardBlock.Background
        // Not `presentsOverHome`: a pushed subagent shows the call's card too.
        val coversHome = home.catchUp != null || home.isSettingsShown ||
            home.welcome != null || home.pendingDeletion != null || home.isDrawerOpen
        // Not `presentsOverSession`: the call itself counts there.
        val top = home.topDetail ?: detail
        // A sheet presented by any mounted screen covers the one on top.
        var coversSession = top.browser.isOpen || (includingRequestDock && top.requestDock.isAnswering)
        for (screen in listOf(detail) + home.subagentStack) {
            if (screen.activePicker != null || screen.folderBrowser != null || screen.shellOutput != null) coversSession = true
        }
        if (presentsOverApp || coversHome || coversSession) return ClientToolCardBlock.Covered
        return null
}

private enum class ClientToolsLocateID { Locate }

/** One socket follows [clientToolsTarget], another [delegatorClientToolsTarget]. */
internal fun sharingScreenWithAgents(base: Reducer<AppFeature.State, AppFeature.Action>): Reducer<AppFeature.State, AppFeature.Action> {
    val sockets = combine(
        // A lookup can arrive before a queued observation action. Always give the coordinator the
        // current screen before it handles that lookup.
        Reducer { state, action -> authorizingVoiceNavigation(state, action) },
        scope(
            state = { it.clientTools },
            setState = { s, tools -> s.copy(clientTools = tools) },
            action = { (it as? AppFeature.Action.ClientTools)?.action },
            embed = AppFeature.Action::ClientTools,
            child = ClientToolsFeature(),
        ),
        scope(
            state = { it.delegatorClientTools },
            setState = { s, tools -> s.copy(delegatorClientTools = tools) },
            action = { (it as? AppFeature.Action.DelegatorClientTools)?.action },
            embed = AppFeature.Action::DelegatorClientTools,
            child = ClientToolsFeature(),
        ),
        scope(
            state = { it.voiceOpenSession },
            setState = { s, open -> s.copy(voiceOpenSession = open) },
            action = { (it as? AppFeature.Action.VoiceOpenSession)?.action },
            embed = AppFeature.Action::VoiceOpenSession,
            child = VoiceOpenSessionFeature(),
        ),
        base,
        Reducer { state, action -> routeClientTools(state, action) },
    )
    return sockets
        .onChange({ it.voiceOpenSession.observation }) { _, _, state ->
            if (state.voiceOpenSession.active == null) Reduced(state)
            else Reduced(state, Effect.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.EnvironmentChanged)))
        }
        .onChange({ it.clientToolsTarget }) { _, target, state ->
            Reduced(state, Effect.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(target))))
        }
        .onChange({ it.voiceDelegatorSessionID }) { _, _, state -> locateVoiceDelegator(state) }
        .onChange({ it.delegatorClientToolsTarget }) { _, target, state ->
            Reduced(state, Effect.send(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.TargetChanged(target))))
        }
}

/** The voice open Home handles this action for, when App's operation still authorizes it. */
private fun authorizingVoiceNavigation(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
    val startsOpen = action is AppFeature.Action.VoiceOpenSession &&
        (action.action is VoiceOpenSessionFeature.Action.Requested || action.action is VoiceOpenSessionFeature.Action.Fallback)
    var s = state
    if (startsOpen || s.voiceOpenSession.inFlight) {
        s = s.copy(voiceOpenSession = s.voiceOpenSession.observing(s.openSessionEnvironment))
    }
    val operation = s.voiceOpenSession.active
    val requestedID = openSessionToolID(action)
    var permitted: String? = null
    if (operation != null && operation.authorized && operation.id == requestedID &&
        operation.generation == s.voiceOpenSession.generation &&
        s.openSessionEnvironment.screen.live && s.isInForeground
    ) {
        permitted = operation.id
    }
    val home = s.home
    if (home != null && home.voiceNavigationToolID != permitted) s = s.copy(home = home.copy(voiceNavigationToolID = permitted))
    return Reduced(s)
}

/** The tool-call ID of a voice open on its way into Home, if [action] is one. */
private fun openSessionToolID(action: AppFeature.Action): String? {
    val home = (action as? AppFeature.Action.Home)?.action as? HomeFeature.Action.SessionDetail ?: return null
    val detail = (home.action as? PresentationAction.Presented)?.action as? SessionFeature.Action.Delegate ?: return null
    return (detail.delegate as? SessionFeature.Delegate.OpenSession)?.toolID
}

/** The SSE fallback of a voice open, from the call's delegator. */
private fun openSessionRequest(action: AppFeature.Action): VoiceFeature.DelegateAction.OpenSessionRequested? {
    val home = (action as? AppFeature.Action.Home)?.action as? HomeFeature.Action.SessionDetail ?: return null
    val detail = (home.action as? PresentationAction.Presented)?.action as? SessionFeature.Action.Voice ?: return null
    val voice = (detail.action as? PresentationAction.Presented)?.action as? VoiceFeature.Action.Delegate ?: return null
    return voice.action as? VoiceFeature.DelegateAction.OpenSessionRequested
}

private fun routeClientTools(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
    var observed = state
    if (observed.voiceOpenSession.inFlight) {
        observed = observed.copy(voiceOpenSession = observed.voiceOpenSession.observing(observed.openSessionEnvironment))
    }
    return routingClientTools(observed, action)
}

private fun routingClientTools(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
    if (action is AppFeature.Action.VoiceOpenSession) {
        val delegate = action.action as? VoiceOpenSessionFeature.Action.Delegate ?: return Reduced(state)
        val active = state.voiceOpenSession.active
        val environmentChanged = Effect.send<AppFeature.Action>(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.EnvironmentChanged))
        if (active == null || active.id != delegate.id || active.stage != VoiceOpenSessionFeature.Stage.navigating ||
            active.generation != state.voiceOpenSession.generation || !state.openSessionEnvironment.screen.live || !state.isInForeground
        ) {
            return Reduced(state, environmentChanged)
        }
        val open = state.voiceOpenSession.copy(active = active.copy(authorized = true))
        val home = AppFeature.Action.Home(
            HomeFeature.Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSession(delegate.session, toolID = delegate.id))),
        )
        return Reduced(state.copy(voiceOpenSession = open), Effect.send(home))
    }
    val toolID = openSessionToolID(action)
    if (toolID != null) {
        val active = state.voiceOpenSession.active
        if (state.home?.voiceNavigationToolID != toolID || active?.id != toolID) return Reduced(state)
        val open = state.voiceOpenSession.copy(active = active.copy(committed = true, generation = state.voiceOpenSession.generation))
        return Reduced(
            state.copy(voiceOpenSession = open),
            Effect.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.EnvironmentChanged)),
        )
    }
    val request = openSessionRequest(action)
    if (request != null) {
        val server = state.home?.server ?: return Reduced(state)
        return Reduced(
            state,
            Effect.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Fallback(request.toolID, request.targetID, server))),
        )
    }
    if (action is AppFeature.Action.ClientTools) {
        val delegate = action.action as? ClientToolsFeature.Action.Delegate ?: return Reduced(state)
        return Reduced(state, route(delegate.delegate, state) { call, message ->
            AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(call, message))
        })
    }
    if (action is AppFeature.Action.DelegatorClientTools) {
        val delegate = action.action as? ClientToolsFeature.Action.Delegate ?: return Reduced(state)
        return Reduced(state, route(delegate.delegate, state) { call, message ->
            AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Refuse(call, message))
        })
    }
    if (action is AppFeature.Action.VoiceDelegatorLocated) {
        if (action.location.sessionID != state.voiceDelegatorSessionID) return Reduced(state)
        return Reduced(state.copy(voiceDelegatorLocation = action.location))
    }
    return Reduced(state)
}

/** The call's own action, as Home receives it. */
internal fun voiceAction(action: VoiceFeature.Action): AppFeature.Action =
    AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(action))))

private fun route(
    delegate: ClientToolsFeature.DelegateAction,
    state: AppFeature.State,
    refuse: (ClientToolCall, String) -> AppFeature.Action,
): Effect<AppFeature.Action> {
    if (delegate is ClientToolsFeature.DelegateAction.Disconnected) {
        if (state.home?.sessionDetail?.voice == null) return Effect.none()
        val openSession: Effect<AppFeature.Action> = if (state.voiceOpenSession.inFlight) {
            Effect.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.ConnectionClosed(delegate.connection)))
        } else {
            Effect.none()
        }
        return Effect.concatenate(
            openSession,
            Effect.send(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.ConnectionClosed(delegate.connection)))),
            Effect.send(voiceAction(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.ConnectionClosed(delegate.connection)))),
        )
    }
    val call = (delegate as ClientToolsFeature.DelegateAction.ToolCalled).call
    val isLive = state.home?.sessionDetail?.voice?.phase == VoiceFeature.State.Phase.Live
    when (call.tool) {
        ClientTool.voiceOpenSession -> {
            val home = state.home ?: return Effect.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Refused(call, "call_ended")))
            return Effect.send(AppFeature.Action.VoiceOpenSession(VoiceOpenSessionFeature.Action.Requested(call, home.server)))
        }
        ClientTool.voiceRequestScreen -> {
            val refusal = state.screenRequestRefusal
            if (refusal != null) return Effect.send(refuse(call, refusal))
            return Effect.send(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.Requested(call))))
        }
        ClientTool.voiceOpenURL -> {
            // A bad link is refused for what it is, wherever the person is.
            val link = VoiceOpenLinkFeature.link(call)
            if (link is VoiceOpenLinkFeature.Link.Invalid) return Effect.send(refuse(call, link.failure.message))
            val refusal = state.openLinkRefusal
            if (refusal != null) return Effect.send(refuse(call, refusal))
            return Effect.send(voiceAction(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.Requested(call))))
        }
        // Not refused in the background or under a sheet: nothing waits on the person, and
        // music on a call with the phone locked is normal.
        ClientTool.voicePlayAudio -> {
            if (!isLive) return Effect.send(refuse(call, CallAudioFailure.NoCall.message))
            return Effect.send(voiceAction(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.PlayRequested(call))))
        }
        ClientTool.voiceAudioControl -> {
            if (!isLive) return Effect.send(refuse(call, CallAudioFailure.NoCall.message))
            return Effect.send(voiceAction(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.ControlRequested(call))))
        }
    }
}

/** Reads where the delegator's session lives (one GET per session per call). */
private fun locateVoiceDelegator(state: AppFeature.State): Reduced<AppFeature.State, AppFeature.Action> {
    val id = state.voiceDelegatorSessionID
    val server = state.home?.server
    if (id == null || server == null) {
        return Reduced(state.copy(voiceDelegatorLocation = null), Effect.cancel(ClientToolsLocateID.Locate))
    }
    if (state.voiceDelegatorLocation?.sessionID == id) return Reduced(state)
    val openCodeClient = dependency(OpenCodeClient)
    val effect = Effect.run<AppFeature.Action> { send ->
        try {
            val session = openCodeClient.getSession(server, id)
            send(AppFeature.Action.VoiceDelegatorLocated(ClientToolsLocation(id, session.directory)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.network.info { "client tools (delegator): couldn't read its session (${error.javaClass.simpleName})" }
        }
    }.cancellable(ClientToolsLocateID.Locate, cancelInFlight = true)
    return Reduced(state, effect)
}
