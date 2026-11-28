package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.TestStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.makeDetail
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.voice.DotScopeSimulation
import ai.opencode.feature.voice.ScreenAwakeClient
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** A call keeps the screen on, only in the foreground. */
class ScreenAwakeTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(
        id = "ses_voice", serverTitle = "Fix flaky upload test", projectID = "p", directory = "/Users/kit/project",
        updatedAt = Instant.parse("0001-01-01T00:00:00Z"),
    )
    private val shared = AppTestShared()
    private lateinit var effects: kotlinx.coroutines.CoroutineScope

    private fun TestScope.makeStore(calls: Locked<List<Boolean>>, clock: TestClock): TestStore<AppFeature.State, AppFeature.Action> {
        val app = shared.run {
            val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
            val (next, detail) = home.makeDetail(session)
            AppFeature.State.initial().copy(connection = AppFeature.State.Connection.Connected, home = next.copy(sessionDetail = detail))
        }
        effects = detachedScope(coroutineContext)
        val store = shared.appStore(app, effects) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = clock
            this[ScreenAwakeClient] = ScreenAwakeClient { awake -> calls.update { it + awake } }
        }
        store.exhaustive = false
        return store
    }

    private suspend fun voice(store: TestStore<AppFeature.State, AppFeature.Action>, action: VoiceFeature.Action) {
        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.Voice(PresentationAction.Presented(action)))))
    }

    /** Settles actions the effects sent back, then reads what the screen was told. */
    private suspend fun settled(store: TestStore<AppFeature.State, AppFeature.Action>, calls: Locked<List<Boolean>>): List<Boolean> {
        repeat(20) { yield() }
        store.skipReceivedActions()
        return calls.value
    }

    private val TestStore<AppFeature.State, AppFeature.Action>.phase get() = state.home?.sessionDetail?.voice?.phase

    @Test
    fun theScreenStaysAwakeFromConnectingUntilHangUp() = runTest {
        val calls = Locked<List<Boolean>>(emptyList())
        val clock = TestClock()
        val store = makeStore(calls, clock)

        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        assertEquals(VoiceFeature.State.Phase.Connecting, store.phase)
        assertEquals("Connecting already keeps it awake", listOf(true), settled(store, calls))

        voice(store, VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected))
        voice(store, VoiceFeature.Action.MuteTapped)
        assertEquals("Nothing else in the call touches it", listOf(true), settled(store, calls))

        voice(store, VoiceFeature.Action.EndTapped)
        assertEquals(VoiceFeature.State.Phase.Ended, store.phase)
        assertEquals("Hanging up lets it sleep while the bar sweeps out", listOf(true, false), settled(store, calls))
        clock.advance(DotScopeSimulation.exitDuration.seconds)
        assertEquals(listOf(true, false), settled(store, calls))
        effects.skipInFlightEffects()
    }

    @Test
    fun theBackgroundLetsTheScreenSleepAndReturningKeepsItAwake() = runTest {
        val calls = Locked<List<Boolean>>(emptyList())
        val store = makeStore(calls, TestClock())

        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        voice(store, VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected))
        store.send(AppFeature.Action.ForegroundChanged(false))
        assertEquals("The call carries on in the background", VoiceFeature.State.Phase.Live, store.phase)
        assertEquals(listOf(true, false), settled(store, calls))

        store.send(AppFeature.Action.ForegroundChanged(true))
        assertEquals(listOf(true, false, true), settled(store, calls))

        // A call that ends in the background never turns it on.
        store.send(AppFeature.Action.ForegroundChanged(false))
        voice(store, VoiceFeature.Action.EndTapped)
        store.send(AppFeature.Action.ForegroundChanged(true))
        assertEquals(listOf(true, false, true, false), settled(store, calls))
        effects.skipInFlightEffects()
    }

    /** The app tells the call when it leaves the front and comes back. */
    @Test
    fun theAppTellsTheCallWhenItGoesToTheBackgroundAndBack() = runTest {
        val store = makeStore(Locked(emptyList()), TestClock())
        // No call, nothing to tell.
        store.send(AppFeature.Action.ForegroundChanged(false))
        store.send(AppFeature.Action.ForegroundChanged(true))
        assertNull(store.state.home?.sessionDetail?.voice)

        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        voice(store, VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected))
        store.send(AppFeature.Action.ForegroundChanged(false))
        store.receive(voiceAction(VoiceFeature.Action.AppVisibilityChanged(false)))
        assertEquals(false, store.state.home?.sessionDetail?.voice?.isAppVisible)
        store.send(AppFeature.Action.ForegroundChanged(true))
        store.receive(voiceAction(VoiceFeature.Action.AppVisibilityChanged(true)))
        assertEquals(true, store.state.home?.sessionDetail?.voice?.isAppVisible)
        effects.skipInFlightEffects()
    }

    /** A call that fails as it starts (no API key) turns the screen on and straight off again, in that order, with nothing left on. */
    @Test
    fun aCallThatFailsAsItStartsLeavesTheScreenFreeToSleep() = runTest {
        shared.run { shared(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone) }
        val calls = Locked<List<Boolean>>(emptyList())
        val store = makeStore(calls, TestClock())

        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        assertEquals(listOf(true), calls.value)
        voice(store, VoiceFeature.Action.Start)
        assertEquals(VoiceFeature.State.Phase.Failed("Add your OpenAI API key in Settings to use Voice."), store.phase)
        assertEquals(listOf(true, false), calls.value)
        effects.skipInFlightEffects()
    }

    @Test
    fun aFailedCallLetsTheScreenSleep() = runTest {
        val calls = Locked<List<Boolean>>(emptyList())
        val store = makeStore(calls, TestClock())

        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        voice(store, VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected))
        voice(store, VoiceFeature.Action.CallEvent(VoiceCallEvent.ServiceError("The connection closed")))
        assertEquals(VoiceFeature.State.Phase.Failed("The connection closed"), store.phase)
        assertEquals(listOf(true, false), settled(store, calls))
        effects.skipInFlightEffects()
    }

    @Test
    fun leavingHomeMidCallLetsTheScreenSleep() = runTest {
        val calls = Locked<List<Boolean>>(emptyList())
        val store = makeStore(calls, TestClock())

        store.send(AppFeature.Action.Home(HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)))
        voice(store, VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected))
        store.send(AppFeature.Action.PairingCodeScanned("not a pairing code"))
        assertNull(store.state.home)
        assertEquals(listOf(true, false), settled(store, calls))
        effects.skipInFlightEffects()
    }
}
