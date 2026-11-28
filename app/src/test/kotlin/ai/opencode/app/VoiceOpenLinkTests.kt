package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceOpenLinkFeature
import ai.opencode.feature.voice.VoiceOpenURLClient
import ai.opencode.feature.voice.VoiceScreenRequestFeature
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
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Its app tests (MARK: App). */
class VoiceOpenLinkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val connection = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val second = UUID.fromString("00000000-0000-0000-0000-000000000013")
    private val uber = "https://m.uber.com/ul/?action=setPickup&pickup=my_location"
    private val call = ClientToolCall(
        connection = connection, id = JsonRpcID.Number(1), tool = ClientTool.voiceOpenURL,
        sessionID = "ses_voice", url = uber, title = "Uber",
    )
    private val server = OpenCodeServer(url = "https://studio.example.ts.net:4096", username = "opencode", password = "pw")
    private val session = OpenCodeSession(
        id = "ses_phone", serverTitle = "Phone tools", projectID = "proj", directory = "/work/app",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )
    private val voiceSession = OpenCodeSession(
        id = "ses_voice", serverTitle = "Voice", projectID = "home", directory = "/work/home",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )
    private val both = setOf(ClientTool.voiceRequestScreen, ClientTool.voiceOpenURL)
    private val links = setOf(ClientTool.voiceOpenURL)

    private fun liveCallState(delegatorElsewhere: Boolean = false): AppFeature.State = shared.run {
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
        var voice = VoiceFeature.State(server, session).withPhase(VoiceFeature.State.Phase.Live)
        if (delegatorElsewhere) {
            voice = voice.copy(usesSessionDelegator = true)
            voice = voice.copy(sessionDelegator = voice.sessionDelegator.copy(activeSessionID = voiceSession.id))
        }
        val detail = SessionFeature.State.session(server, session).copy(voice = voice)
        AppFeature.State.initial().copy(home = home.copy(sessionDetail = detail))
    }

    private fun AppFeature.State.withVoice(update: (VoiceFeature.State) -> VoiceFeature.State): AppFeature.State {
        val home = home ?: return this
        val detail = home.sessionDetail ?: return this
        val voice = detail.voice ?: return this
        return copy(home = home.copy(sessionDetail = detail.copy(voice = update(voice))))
    }

    private fun AppFeature.State.withClientTools(update: (ClientToolsFeature.State) -> ClientToolsFeature.State) =
        copy(clientTools = update(clientTools))

    private fun AppFeature.State.withDelegatorClientTools(update: (ClientToolsFeature.State) -> ClientToolsFeature.State) =
        copy(delegatorClientTools = update(delegatorClientTools))

    private fun voiceAction(action: VoiceFeature.Action): AppFeature.Action =
        AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(action))))

    private fun openLink(action: VoiceOpenLinkFeature.Action): AppFeature.Action = voiceAction(VoiceFeature.Action.OpenLink(action))

    private suspend fun waitFor(condition: () -> Boolean) {
        for (attempt in 0 until 1_000) {
            if (condition()) return
            yield()
        }
    }

    @Test fun bothSocketsOfferTheLinkAndPutItOnTheCallsCard() = runTest {
        val streams = Locked<Map<String, Channel<ClientToolsEvent>>>(emptyMap())
        val replies = Locked<List<ClientToolResult>>(emptyList())
        val opened = Locked<List<String>>(emptyList())
        val voiceSession = voiceSession
        val effects = detachedScope(coroutineContext)
        val initial = liveCallState(delegatorElsewhere = true)
            .copy(voiceDelegatorLocation = ClientToolsLocation(sessionID = voiceSession.id, directory = voiceSession.directory))
        val store = shared.appStore(initial, effects) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> voiceSession })
            this[VoiceOpenURLClient] = VoiceOpenURLClient { url ->
                opened.update { it + url }
                true
            }
            this[ClientToolsClient] = ClientToolsClient(
                connect = { target ->
                    assertTrue(target.tools == links + ClientTool.voiceOpenSession || target.tools == setOf(ClientTool.voiceOpenSession))
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
        val callTarget = ClientToolsTarget(server = server, directory = session.directory, tools = links + ClientTool.voiceOpenSession)
        val home = ClientToolsTarget(server = server, directory = voiceSession.directory, tools = links + ClientTool.voiceOpenSession)

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(links))) {
            it.withClientTools { c -> c.copy(tools = links) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(callTarget))) {
            it.withClientTools { c -> c.copy(target = callTarget, status = ClientToolsFeature.State.Status.Connecting) }
        }
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.TargetChanged(home))) {
            it.withDelegatorClientTools { c -> c.copy(target = home, status = ClientToolsFeature.State.Status.Connecting) }
        }
        waitFor { streams.value.size >= 2 }

        // The call's own socket: Open.
        val first = call
        streams.value.getValue(session.directory).send(ClientToolsEvent.Call(first))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(first)))) {
            it.withClientTools { c -> c.copy(connection = connection) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(first))))
        store.receive(openLink(VoiceOpenLinkFeature.Action.Requested(first))) {
            it.withVoice { v -> v.copy(openLink = v.openLink.copy(pending = first), floatingStage = v.floatingStage.copy(isHeld = true)) }
        }
        store.send(openLink(VoiceOpenLinkFeature.Action.OpenTapped)) {
            it.withVoice { v -> v.copy(openLink = v.openLink.copy(pending = null), floatingStage = v.floatingStage.copy(isHeld = false)) }
        }

        // The delegator's socket in /work/home: Not Now.
        val fromHome = call.copy(connection = second, id = JsonRpcID.Number(2), url = "https://www.avianca.com/en/", title = null)
        streams.value.getValue(voiceSession.directory).send(ClientToolsEvent.Call(fromHome))
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(fromHome)))) {
            it.withDelegatorClientTools { c -> c.copy(connection = second) }
        }
        store.receive(AppFeature.Action.DelegatorClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(fromHome))))
        store.receive(openLink(VoiceOpenLinkFeature.Action.Requested(fromHome))) {
            it.withVoice { v -> v.copy(openLink = v.openLink.copy(pending = fromHome), floatingStage = v.floatingStage.copy(isHeld = true)) }
        }
        store.send(openLink(VoiceOpenLinkFeature.Action.NotNowTapped)) {
            it.withVoice { v -> v.copy(openLink = v.openLink.copy(pending = null), floatingStage = v.floatingStage.copy(isHeld = false)) }
        }
        waitFor { replies.value.size >= 2 }

        // Turning off links retains the call's navigation sockets.
        store.exhaustive = false
        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(emptySet())))
        store.skipReceivedActions()
        assertEquals(setOf(ClientTool.voiceOpenSession), store.state.clientTools.target?.tools)
        assertEquals(setOf(ClientTool.voiceOpenSession), store.state.delegatorClientTools.target?.tools)

        assertEquals(listOf(uber), opened.value)
        assertEquals(
            listOf(
                ClientToolResult(listOf(ClientToolResult.Content.Text("Opened Uber (m.uber.com) on the user's phone."))),
                ClientToolResult.failure(VoiceOpenLinkFeature.Refusal.declined),
            ),
            replies.value,
        )
        effects.skipInFlightEffects()
    }

    @Test fun aBackgroundedPhoneOrABadLinkIsRefusedAtOnce() = runTest {
        val replies = Locked<List<ClientToolResult>>(emptyList())
        val events = Channel<ClientToolsEvent>(Channel.UNLIMITED)
        val effects = detachedScope(coroutineContext)
        val store = shared.appStore(liveCallState(), effects) {
            this[VoiceOpenURLClient] = VoiceOpenURLClient { _ -> throw AssertionError("Nothing opens without a tap") }
            this[ClientToolsClient] = ClientToolsClient(
                connect = { events.receiveAsFlow() },
                reply = { _, result ->
                    replies.update { it + result }
                    true
                },
            )
        }
        val target = ClientToolsTarget(server = server, directory = "/work/app", tools = both)

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(both))) {
            it.withClientTools { c -> c.copy(tools = both) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(target))) {
            it.withClientTools { c -> c.copy(target = target, status = ClientToolsFeature.State.Status.Connecting) }
        }

        // A bad scheme is refused for what it is, even with the card free.
        val bad = call.copy(url = "tel:+15551234567")
        events.send(ClientToolsEvent.Call(bad))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(bad)))) {
            it.withClientTools { c -> c.copy(connection = connection) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(bad))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(bad, VoiceOpenLinkFeature.Refusal.scheme("tel"))))

        // Locked or backgrounded: refused.
        store.send(AppFeature.Action.ForegroundChanged(false)) { it.copy(isInForeground = false) }
        store.receive(voiceAction(VoiceFeature.Action.AppVisibilityChanged(false))) { it.withVoice { voice -> voice.copy(isAppVisible = false) } }
        val background = call.copy(id = JsonRpcID.Number(2))
        events.send(ClientToolsEvent.Call(background))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Socket(ClientToolsEvent.Call(background))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.ToolCalled(background))))
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Refuse(background, VoiceOpenLinkFeature.Refusal.background)))
        assertNull(store.state.home?.sessionDetail?.voice?.openLink?.pending)

        store.send(AppFeature.Action.ClientTools(ClientToolsFeature.Action.ToolsChanged(emptySet()))) {
            it.withClientTools { c -> c.copy(tools = emptySet()) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.TargetChanged(null))) {
            it.withClientTools { c -> c.copy(target = null, status = ClientToolsFeature.State.Status.Idle, connection = null) }
        }
        store.receive(AppFeature.Action.ClientTools(ClientToolsFeature.Action.Delegate(ClientToolsFeature.DelegateAction.Disconnected(connection))))
        store.receive(voiceAction(VoiceFeature.Action.ScreenRequest(VoiceScreenRequestFeature.Action.ConnectionClosed(connection))))
        store.receive(openLink(VoiceOpenLinkFeature.Action.ConnectionClosed(connection)))
        store.finish()
        assertEquals(
            listOf(
                ClientToolResult.failure(VoiceOpenLinkFeature.Refusal.scheme("tel")),
                ClientToolResult.failure("The phone isn’t showing OpenCode, so I couldn’t open it. Text the link instead."),
            ),
            replies.value,
        )
        effects.skipInFlightEffects()
    }

    @Test fun aLinkWaitsForNoOtherCardAndNeedsALiveCall() {
        var state = liveCallState()
        assertNull(state.openLinkRefusal)
        val screen = ClientToolCall(connection = connection, id = JsonRpcID.Number(9), tool = ClientTool.voiceRequestScreen)
        state = state.withVoice { it.copy(screenRequest = it.screenRequest.copy(pending = screen)) }
        assertEquals(VoiceOpenLinkFeature.Refusal.busy, state.openLinkRefusal)
        state = state.withVoice { it.copy(screenRequest = it.screenRequest.copy(pending = null), openLink = it.openLink.copy(pending = call)) }
        assertEquals(VoiceScreenRequestFeature.Refusal.otherRequest, state.screenRequestRefusal)

        val liveState = liveCallState()
        val covered = liveState.copy(home = liveState.home?.copy(isDrawerOpen = true))
        assertEquals(VoiceOpenLinkFeature.Refusal.covered, covered.openLinkRefusal)
        val ended = liveCallState().withVoice { shared.run { it.withPhase(VoiceFeature.State.Phase.Ended) } }
        assertEquals(VoiceOpenLinkFeature.Refusal.noCall, ended.openLinkRefusal)
    }
}
