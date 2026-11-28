package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.VoiceCallAudioFeature
import ai.opencode.feature.voice.VoiceDelegatorChoice
import ai.opencode.feature.voice.VoiceDelegatorWorkspace
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceOpenLinkFeature
import ai.opencode.feature.voice.VoiceScreenRequestFeature
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.clienttools.ClientTool
import ai.opencode.server.clienttools.ClientToolCall
import ai.opencode.server.clienttools.ClientToolResult
import ai.opencode.server.clienttools.ClientToolsClient
import ai.opencode.server.clienttools.ClientToolsEvent
import ai.opencode.server.clienttools.ClientToolsTarget
import ai.opencode.server.clienttools.JsonRpcID
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** Its app tests (MARK: App, and the delegator tests under MARK: Transport). */
class ClientToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val server = OpenCodeServer(url = "https://studio.example.ts.net:4096", username = "opencode", password = "pw")
    private val screen = setOf(ClientTool.voiceRequestScreen)
    private val target get() = ClientToolsTarget(server = server, directory = "/work/app", tools = screen)
    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val session = OpenCodeSession(
        id = "ses_phone", serverTitle = "Phone tools", projectID = "proj", directory = "/work/app",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )

    private fun appWithSession(): AppFeature.State = shared.run {
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
        AppFeature.State.initial().copy(home = home.copy(sessionDetail = SessionFeature.State.session(server, session)))
    }

    private fun liveVoice(on: OpenCodeServer = server): VoiceFeature.State =
        shared.run { VoiceFeature.State(on, session).withPhase(VoiceFeature.State.Phase.Live) }

    private fun AppFeature.State.withDetail(update: (SessionFeature.State) -> SessionFeature.State): AppFeature.State {
        val home = home ?: return this
        val detail = home.sessionDetail ?: return this
        return copy(home = home.copy(sessionDetail = update(detail)))
    }

    private fun AppFeature.State.withVoice(voice: VoiceFeature.State): AppFeature.State = withDetail { it.copy(voice = voice) }

    private fun AppFeature.State.withHome(update: (HomeFeature.State) -> HomeFeature.State): AppFeature.State =
        copy(home = home?.let(update))

    private fun AppFeature.State.withClientTools(update: (ClientToolsFeature.State) -> ClientToolsFeature.State) =
        copy(clientTools = update(clientTools))

    private fun AppFeature.State.withDelegatorClientTools(update: (ClientToolsFeature.State) -> ClientToolsFeature.State) =
        copy(delegatorClientTools = update(delegatorClientTools))

    private fun voiceAction(action: VoiceFeature.Action): AppFeature.Action =
        AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(action))))

    private fun call(id: Int, tool: ClientTool, on: UUID = connection, sessionID: String? = null, action: String? = null) =
        ClientToolCall(connection = on, id = JsonRpcID.Number(id.toLong()), tool = tool, sessionID = sessionID, action = action)

    private suspend fun waitFor(condition: () -> Boolean) {
        for (attempt in 0 until 1_000) {
            if (condition()) return
            yield()
        }
    }

    @Test fun theSocketOpensForACallAndClosesWhenItEnds() = runTest {
        val sockets = Locked<List<ClientToolsTarget>>(emptyList())
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(appWithSession(), effects) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = TestClock()
            this[ClientToolsClient] = ClientToolsClient(
                connect = { target ->
                    sockets.update { it + target }
                    emptyFlow()
                },
                reply = { _, _ -> true },
            )
        }
        val tools = setOf(ClientTool.voiceOpenURL, ClientTool.voicePlayAudio, ClientTool.voiceAudioControl)

        // An open session, in the foreground or coming back to it, isn't a call: every tool
        // refuses without one, so nothing connects.
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(tools))) {
            it.withClientTools { c -> c.copy(tools = tools) }
        }
        store.send(AppFeature.Action.ForegroundChanged(false)) { it.copy(isInForeground = false) }
        store.send(AppFeature.Action.ForegroundChanged(true)) { it.copy(isInForeground = true) }
        assertEquals(emptyList<ClientToolsTarget>(), sockets.value)

        // Starting a call runs the whole call machinery; only the socket is checked here.
        store.exhaustive = false
        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        store.receive({ it is AppFeature.Action.ClientTools && it.action is ClientToolsFeature.Action.TargetChanged }, "targetChanged")
        val onCall = ClientToolsTarget(server = server, directory = session.directory, tools = tools)
        assertEquals(false, store.state.home?.sessionDetail?.voice?.phase?.isTerminal)
        assertEquals(onCall, store.state.clientTools.target)
        assertEquals(ClientToolsFeature.State.Status.Connecting, store.state.clientTools.status)
        waitFor { sockets.value.isNotEmpty() }
        assertEquals(listOf(onCall), sockets.value)

        store.send(voiceAction(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended)))
        store.receive({ it is AppFeature.Action.ClientTools && it.action is ClientToolsFeature.Action.TargetChanged }, "targetChanged")
        assertNull(store.state.home?.sessionDetail?.voice)
        assertNull(store.state.clientTools.target)
        assertEquals(ClientToolsFeature.State.Status.Idle, store.state.clientTools.status)
        assertEquals(listOf(onCall), sockets.value)
        effects.skipInFlightEffects()
    }

    @Test fun aRequestNobodyCanSeeIsRefusedAtOnce() = runTest {
        val state = appWithSession().withVoice(liveVoice()).withHome { it.copy(isDrawerOpen = true) }
        val replies = Locked<List<ClientToolResult>>(emptyList())
        val events = Channel<ClientToolsEvent>(Channel.UNLIMITED)
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(state, effects) {
            this[ClientToolsClient] = ClientToolsClient(
                connect = { events.receiveAsFlow() },
                reply = { _, result ->
                    replies.update { it + result }
                    true
                },
            )
        }

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(screen))) {
            it.withClientTools { c -> c.copy(tools = screen) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(target))) {
            it.withClientTools { c -> c.copy(target = target, status = ClientToolsFeature.State.Status.Connecting) }
        }

        // The drawer covers the call's composer.
        val covered = call(1, ClientTool.voiceRequestScreen, sessionID = "ses_agent")
        events.send(ClientToolsEvent.Call(covered))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(covered)))) {
            it.withClientTools { c -> c.copy(connection = connection) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(covered))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(covered, VoiceScreenRequestFeature.Refusal.covered)))

        // In the background the call keeps the socket, but nobody sees a card.
        store.send(AppFeature.Action.ForegroundChanged(false)) { it.copy(isInForeground = false) }
        store.receive(voiceAction(VoiceFeature.Action.AppVisibilityChanged(false))) {
            it.withDetail { detail -> detail.copy(voice = detail.voice?.copy(isAppVisible = false)) }
        }
        val background = call(2, ClientTool.voiceRequestScreen, sessionID = "ses_agent")
        events.send(ClientToolsEvent.Call(background))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(background))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(background))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(background, VoiceScreenRequestFeature.Refusal.background)))
        assertNull(store.state.home?.sessionDetail?.voice?.screenRequest?.pending)

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(emptySet()))) {
            it.withClientTools { c -> c.copy(tools = emptySet()) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(null))) {
            it.withClientTools { c -> c.copy(target = null, status = ClientToolsFeature.State.Status.Idle, connection = null) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.Disconnected(connection))))
        store.receive(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.ConnectionClosed(connection))))
        store.receive(voiceAction(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.ConnectionClosed(connection))))
        store.finish()
        assertEquals(
            listOf(
                ClientToolResult.failure(VoiceScreenRequestFeature.Refusal.covered),
                ClientToolResult.failure(VoiceScreenRequestFeature.Refusal.background),
            ),
            replies.value,
        )
        effects.skipInFlightEffects()
    }

    @Test fun onlyAVisibleCallsComposerTakesARequest() {
        val noCall = appWithSession()
        assertEquals(VoiceScreenRequestFeature.Refusal.noCall, noCall.screenRequestRefusal)
        val state = noCall.withVoice(liveVoice())
        assertNull(state.screenRequestRefusal)

        val settings = shared.run { state.withHome { it.showingSettings() } }
        assertEquals(VoiceScreenRequestFeature.Refusal.covered, settings.screenRequestRefusal)
        assertEquals(VoiceScreenRequestFeature.Refusal.covered, state.copy(isEditingServer = true).screenRequestRefusal)
        val picker = state.withDetail { it.copy(activePicker = SessionFeature.State.ActivePicker.model) }
        assertEquals(VoiceScreenRequestFeature.Refusal.covered, picker.screenRequestRefusal)
        assertEquals(VoiceScreenRequestFeature.Refusal.background, state.copy(isInForeground = false).screenRequestRefusal)
    }

    @Test fun aPushedSubagentTakesARequestUnlessSomethingIsOpenOnIt() {
        val state = appWithSession().withVoice(liveVoice())
        val child = OpenCodeSession(
            id = "ses_child", serverTitle = "Subagent", projectID = "proj", directory = "/work/app", updatedAt = session.updatedAt,
        )
        val subagent = shared.run { SessionFeature.State.session(server, child) }

        // The call's card shows on the pushed subagent's screen.
        val pushed = state.withHome { it.copy(subagentStack = it.subagentStack + subagent) }
        assertNull(pushed.screenRequestRefusal)

        val picking = subagent.copy(activePicker = SessionFeature.State.ActivePicker.model)
        val covered = state.withHome { it.copy(subagentStack = it.subagentStack + picking) }
        assertEquals(VoiceScreenRequestFeature.Refusal.covered, covered.screenRequestRefusal)
    }

    @Test fun theOnCallSocketUsesHomesServer() {
        val stale = server.copy(password = "old")
        val state = appWithSession().withVoice(liveVoice(on = stale)).withClientTools { it.copy(tools = screen) }
        assertEquals("pw", state.clientToolsTarget?.server?.password)
    }

    @Test fun aDelegatorSessionElsewhereGetsItsOwnSocketForTheCall() = runTest {
        val voiceSession = OpenCodeSession(
            id = "ses_voice", serverTitle = "Voice", projectID = "home", directory = "/work/home", updatedAt = session.updatedAt,
        )
        val voice = liveVoice().copy(
            usesSessionDelegator = true,
        ).let { it.copy(sessionDelegator = it.sessionDelegator.copy(activeSessionID = voiceSession.id)) }
        val state = appWithSession().withVoice(voice).withHome { it.copy(isDrawerOpen = true) }
            .copy(voiceDelegatorLocation = ClientToolsLocation(sessionID = voiceSession.id, directory = voiceSession.directory))
        val streams = Locked<Map<String, Channel<ClientToolsEvent>>>(emptyMap())
        val replies = Locked<List<ClientToolResult>>(emptyList())
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(state, effects) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, id ->
                    assertEquals(voiceSession.id, id)
                    voiceSession
                },
            )
            this[ClientToolsClient] = ClientToolsClient(
                connect = { target ->
                    val channel = Channel<ClientToolsEvent>(Channel.UNLIMITED)
                    streams.update { it + (target.directory to channel) }
                    channel.receiveAsFlow()
                },
                reply = { _, result ->
                    replies.update { it + result }
                    true
                },
            )
        }
        val all = ClientTool.entries.toSet()
        val home = ClientToolsTarget(server = server, directory = voiceSession.directory, tools = all)

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(all))) {
            it.withClientTools { c -> c.copy(tools = all) }
        }
        val open = ClientToolsTarget(server = server, directory = target.directory, tools = all)
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(open))) {
            it.withClientTools { c -> c.copy(target = open, status = ClientToolsFeature.State.Status.Connecting) }
        }
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.TargetChanged(home))) {
            it.withDelegatorClientTools { c -> c.copy(target = home, status = ClientToolsFeature.State.Status.Connecting) }
        }

        // A call on the second socket takes the same path: the drawer covers it.
        waitFor { streams.value[home.directory] != null }
        val request = call(4, ClientTool.voiceRequestScreen, on = second, sessionID = voiceSession.id)
        streams.value.getValue(home.directory).send(ClientToolsEvent.Call(request))
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(request)))) {
            it.withDelegatorClientTools { c -> c.copy(connection = second) }
        }
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(request))))
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Refuse(request, VoiceScreenRequestFeature.Refusal.covered)))

        // The delegator plays audio on the call: its audio calls reach the call's
        // player whatever covers the screen, and are answered once.
        val control = call(5, ClientTool.voiceAudioControl, on = second, action = "pause")
        streams.value.getValue(home.directory).send(ClientToolsEvent.Call(control))
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(control))))
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(control))))
        store.receive(voiceAction(VoiceFeature.Action.CallAudio(VoiceCallAudioFeature.Action.ControlRequested(control))))
        waitFor { replies.value.size >= 2 }

        // Turning experiments off keeps navigation available during the call.
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(emptySet()))) {
            it.withClientTools { c -> c.copy(tools = emptySet()) }
        }
        val navigationOnly = setOf(ClientTool.voiceOpenSession)
        val openNavigation = ClientToolsTarget(server = server, directory = target.directory, tools = navigationOnly)
        val homeNavigation = ClientToolsTarget(server = server, directory = voiceSession.directory, tools = navigationOnly)
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(openNavigation))) {
            it.withClientTools { c -> c.copy(target = openNavigation, status = ClientToolsFeature.State.Status.Connecting) }
        }
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.TargetChanged(homeNavigation))) {
            it.withDelegatorClientTools { c -> c.copy(target = homeNavigation, status = ClientToolsFeature.State.Status.Connecting, connection = null) }
        }
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.Disconnected(second))))
        store.receive(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.ConnectionClosed(second))))
        store.receive(voiceAction(VoiceFeature.Action.OpenLink(VoiceOpenLinkFeature.Action.ConnectionClosed(second))))
        // This test covers routing while the call lives; its two socket streams stay open.
        assertEquals(
            listOf(
                ClientToolResult.failure(VoiceScreenRequestFeature.Refusal.covered),
                ClientToolResult.failure(VoiceCallAudioFeature.Reply.nothingPlaying),
            ),
            replies.value,
        )
        effects.skipInFlightEffects()
    }

    @Test fun onlyADelegatorSessionInAnotherDirectoryIsLocated() {
        shared.run {
            shared(VoiceSharedKeys.voiceDelegatorChoice).set(VoiceDelegatorChoice.AutomaticSession)
            shared(VoiceSharedKeys.voiceDelegatorSessionIDs).set(mapOf(VoiceDelegatorWorkspace.serverID(server) to "ses_voice"))
            val voice = liveVoice().copy(usesSessionDelegator = true)
            var state = appWithSession().withVoice(voice).withClientTools { it.copy(tools = screen) }

            // Automatic: the remembered Voice session, before its first turn.
            assertEquals("ses_voice", state.voiceDelegatorSessionID)
            assertNull(state.delegatorClientToolsTarget)
            state = state.copy(voiceDelegatorLocation = ClientToolsLocation(sessionID = "ses_voice", directory = "/work/home"))
            assertEquals(ClientToolsTarget(server = server, directory = "/work/home", tools = screen + ClientTool.voiceOpenSession), state.delegatorClientToolsTarget)
            // In the call's own directory the first socket already covers it.
            state = state.copy(voiceDelegatorLocation = ClientToolsLocation(sessionID = "ses_voice", directory = "/work/app"))
            assertNull(state.delegatorClientToolsTarget)

            val viewed = voice.copy(sessionDelegator = voice.sessionDelegator.copy(activeSessionID = session.id))
            assertNull(state.withVoice(viewed).voiceDelegatorSessionID)
            assertNull(state.withVoice(voice.withPhase(VoiceFeature.State.Phase.Ended)).voiceDelegatorSessionID)
            assertNull(state.withVoice(voice.copy(usesSessionDelegator = false)).voiceDelegatorSessionID)
            // A live delegator call keeps `voiceOpenSession`, and so this socket, with no
            // experiment on: navigation needs it.
            assertEquals("ses_voice", state.withClientTools { it.copy(tools = emptySet()) }.voiceDelegatorSessionID)
            shared(VoiceSharedKeys.voiceDelegatorChoice).set(VoiceDelegatorChoice.ViewedSession)
            assertNull(state.voiceDelegatorSessionID)
        }
    }

    @Test fun nothingIsOfferedWhileTheExperimentIsOff() {
        var state = appWithSession().withVoice(liveVoice())
        assertNull(state.clientToolsTarget)
        state = state.withClientTools { it.copy(tools = screen) }
        assertEquals(target, state.clientToolsTarget)
        // Turning on another tool's experiment reconnects with both listed.
        val three = setOf(ClientTool.voiceRequestScreen, ClientTool.voicePlayAudio, ClientTool.voiceAudioControl)
        state = state.withClientTools { it.copy(tools = three) }
        assertEquals(three, state.clientToolsTarget?.tools)
    }
}
