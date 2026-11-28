package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.increment
import ai.opencode.feature.session.BindingAction
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes.ContentState.Phase
import ai.opencode.feature.system.liveactivity.SessionLiveActivity
import ai.opencode.feature.system.liveactivity.SessionLiveActivityClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class SessionLiveActivityTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val open = OpenCodeSession(
        id = "ses_open", serverTitle = "Fix upload", projectID = "p", directory = "/work", updatedAt = Instant.ofEpochSecond(2_000),
    )
    private val other = OpenCodeSession(
        id = "ses_other", serverTitle = "Other", projectID = "p", directory = "/work", updatedAt = Instant.ofEpochSecond(1_000),
    )
    private val t0 = Instant.ofEpochSecond(3_000)
    private val title = "Fix upload"

    private val attributes: SessionActivityAttributes
        get() = SessionActivityAttributes(serverKey = SessionDirectory.serverKey(server.persistenceID), sessionID = open.id)

    private fun content(phase: Phase, step: String? = null, preview: String? = null) =
        SessionActivityAttributes.ContentState(title = title, phase = phase, step = step, preview = preview, startedAt = t0)

    private fun working(step: String? = null) = content(Phase.working, step = step)

    private fun reply(text: String): List<OpenCodeTranscriptItem> = listOf(
        OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Fix the upload test"),
        OpenCodeTranscriptItem.Tool(
            id = "msg_a1-tool-1",
            call = OpenCodeToolCall(
                callID = "t1", name = "read", status = OpenCodeToolCall.Status.Completed,
                input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = emptyMap(),
            ),
        ),
        OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = text),
    )

    /** Home with the open session's run followed and the screen elsewhere. */
    private fun Harness.following(sawServerRun: Boolean = false, active: Boolean = false): HomeFeature.State = state {
        HomeFeature.State(
            server,
            OpenCodeHomeSnapshot(
                projects = emptyList(),
                sessions = listOf(open, other),
                activeSessionIDs = if (active) setOf(open.id) else emptySet(),
            ),
        ).copy(sessionActivity = SessionLiveActivity.Tracked(attributes = attributes, content = working(), sawServerRun = sawServerRun))
    }

    private fun HomeFeature.State.withActivity(
        transform: (SessionLiveActivity.Tracked) -> SessionLiveActivity.Tracked,
    ): HomeFeature.State = copy(sessionActivity = sessionActivity?.let(transform))

    private fun recorder(pushes: LockIsolated<List<SessionActivityAttributes.ContentState?>>) =
        SessionLiveActivityClient(update = { content, _ -> pushes.append(content) }, endAll = {})

    private val liveActivityOn = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.sessionLiveActivity))

    @Test
    fun theRunReadsWorkingUntilTheServerHasRunItAndStopped() = homeTest { h ->
        var state = h.following()
        assertEquals(SessionLiveActivity.Run(title = title, isServerActive = false, isWorking = true, needsInput = false), state.sessionActivityRun)

        state = state.copy(activeSessionIDs = setOf(open.id))
        assertEquals(true, state.sessionActivityRun?.isServerActive)
        assertEquals(true, state.sessionActivityRun?.isWorking)

        state = state.withActivity { it.copy(sawServerRun = true) }
            .copy(activeSessionIDs = emptySet(), transcriptCache = mapOf(open.id to reply("Fixed it.")))
        assertEquals(
            SessionLiveActivity.Run(
                title = title, isServerActive = false, isWorking = false, needsInput = false,
                outcome = SessionLiveActivity.Outcome.done, preview = "Fixed it.",
            ),
            state.sessionActivityRun,
        )

        state = state.copy(activeSessionIDs = setOf(open.id), attentionSessionIDs = setOf(open.id))
        assertEquals(true, state.sessionActivityRun?.needsInput)
    }

    // region Home

    private fun Harness.sendStore(enabled: Boolean, pushes: LockIsolated<List<SessionActivityAttributes.ContentState?>>) =
        store(
            state {
                HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(open, other), activeSessionIDs = emptySet()))
                    .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()))
            },
        ) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(t0)
            this[UuidClient] = UuidClient.incrementing()
            this[ExperimentalFeatures] = if (enabled) liveActivityOn else ExperimentalFeatures.enabling(emptySet())
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> awaitCancellation() },
                getSession = { _, _ -> open },
                pendingInputs = { _, _ -> emptyList() },
                transcript = { _, _, _ -> emptyList() },
            )
            this[SessionLiveActivityClient] = recorder(pushes)
        }.also { it.exhaustive = false }

    /** A send starts following its run, Working; off, nothing changes. */
    @Test
    fun aSendStartsTheActivityOnlyWithTheExperimentOn() = homeTest { h ->
        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val store = h.sendStore(enabled = true, pushes = pushes)
        // The draft is typed in.
        store.send(detail(SessionFeature.Action.Binding(BindingAction.Draft("Fix the upload test"))))
        store.send(detail(SessionFeature.Action.SendTapped))
        eventually { pushes.value.isNotEmpty() }
        assertEquals(SessionLiveActivity.Tracked(attributes = attributes, content = working()), store.state.sessionActivity)
        assertEquals(listOf(working()), pushes.value)
        store.skipInFlightEffects()

        val offPushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val off = h.sendStore(enabled = false, pushes = offPushes)
        off.send(detail(SessionFeature.Action.Binding(BindingAction.Draft("Fix the upload test"))))
        off.send(detail(SessionFeature.Action.SendTapped))
        repeat(100) { yield() }
        assertNull(off.state.sessionActivity)
        assertTrue(offPushes.value.isEmpty())
        off.skipInFlightEffects()
    }

    /** The run's live events name its step, wherever the screen is. */
    @Test
    fun theStepFollowsTheRunsLiveEvents() = homeTest { h ->
        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val store = h.store(h.following(sawServerRun = true, active = true)) {
            this[ExperimentalFeatures] = liveActivityOn
            this[SessionLiveActivityClient] = recorder(pushes)
        }

        store.send(
            Action.ServerEvent(
                OpenCodeServerEvent.ContentBatch(open.id, listOf(OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m", toolID = "t", name = "read"))),
            ),
        ) { it.withActivity { tracked -> tracked.copy(step = "Read", content = tracked.content.copy(step = "Read")) } }
        // Another session's events change nothing.
        store.send(
            Action.ServerEvent(
                OpenCodeServerEvent.ContentBatch(other.id, listOf(OpenCodeLiveContentEvent.ReasoningStarted(messageID = "m", ordinal = 0))),
            ),
        )
        store.finish()
        assertEquals(listOf(working(step = "Read")), pushes.value)
    }

    /**
     * Leaving with the run going, the background wait reads its end and settles it Done with the
     * reply's opening line.
     */
    @Test
    fun theBackgroundWaitSettlesTheRun() = homeTest { h ->
        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val items = reply("Fixed the race.")
        val store = h.store(h.following(sawServerRun = true, active = true)) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                waitForIdle = { _, _ -> },
                transcript = { _, _, limit ->
                    assertEquals(SessionLiveActivity.SETTLED_READ_LIMIT, limit)
                    items
                },
            )
            this[ExperimentalFeatures] = liveActivityOn
            this[SessionLiveActivityClient] = recorder(pushes)
        }

        store.send(Action.SceneEnteredBackground) { it.copy(connection = it.connection.copy(isInBackground = true), detailGenerationWhenLeft = it.detailGeneration) }
        store.receive(Action.SessionActivitySettled(open.id, items)) {
            it.withActivity { tracked ->
                tracked.copy(settledItems = items, content = tracked.content.copy(phase = Phase.done, preview = "Fixed the race."))
            }
        }
        store.finish()
        assertEquals(listOf(content(Phase.done, preview = "Fixed the race.")), pushes.value)
    }

    /** Idle before the server ran it means the send hadn't started its run yet: not an end. */
    @Test
    fun anIdleSessionBeforeItsRunIsNotAnEnd() = homeTest { h ->
        val store = h.store(h.following()) { this[ExperimentalFeatures] = liveActivityOn }
        store.send(Action.SessionActivitySettled(open.id, reply("Old reply")))
        store.send(Action.SessionActivitySettled(other.id, reply("Old reply")))
    }

    /** No run to follow: backgrounding waits on nothing. */
    @Test
    fun backgroundingWithoutARunWaitsOnNothing() = homeTest { h ->
        val store = h.store(h.following().copy(sessionActivity = null)) { this[ExperimentalFeatures] = liveActivityOn }
        store.send(Action.SceneEnteredBackground) { it.copy(connection = it.connection.copy(isInBackground = true), detailGenerationWhenLeft = it.detailGeneration) }
    }

    /**
     * Switched off mid-run, the activity ends, the background wait is cancelled with its background
     * time, and leaving again does nothing.
     */
    @Test
    fun switchingTheExperimentOffMidRunEndsTheActivityAndItsWork() = homeTest { h ->
        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val isOn = LockIsolated(true)
        val began = LockIsolated(0)
        val ended = LockIsolated(0)
        val store = h.store(h.following(sawServerRun = true, active = true)) {
            this[Clock] = TestClock()
            this[ExperimentalFeatures] = ExperimentalFeatures { feature -> feature == ExperimentalFeature.sessionLiveActivity && isOn.value }
            this[BackgroundTaskClient] = BackgroundTaskClient {
                began.increment()
                ({ ended.increment() })
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                waitForIdle = { _, _ -> awaitCancellation() },
                transcript = { _, _, _ ->
                    fail("A cancelled wait must not read the transcript")
                    emptyList()
                },
            )
            this[SessionLiveActivityClient] = recorder(pushes)
        }

        store.send(Action.SceneEnteredBackground) { it.copy(connection = it.connection.copy(isInBackground = true), detailGenerationWhenLeft = it.detailGeneration) }
        eventually { began.value != 0 }
        assertEquals(1, began.value)

        isOn.setValue(false)
        store.send(Action.Settings(HomeSettingsFeature.Action.ExperimentalFeatureChanged(ExperimentalFeature.sessionLiveActivity, false))) {
            it.copy(sessionActivity = null)
        }
        eventually { ended.value != 0 && pushes.value.isNotEmpty() }
        assertEquals(1, ended.value)
        assertEquals(listOf<SessionActivityAttributes.ContentState?>(null), pushes.value)

        store.send(Action.SceneEnteredBackground)
        store.finish()
        assertEquals("off, leaving starts no background work", 1, began.value)
        assertEquals(listOf<SessionActivityAttributes.ContentState?>(null), pushes.value)
    }

    /**
     * A pending request reads Needs your input; once the server runs it and stops, it's Done with
     * the reply's opening line.
     */
    @Test
    fun needsInputThenDoneWithItsPreview() = homeTest { h ->
        val state = h.following().copy(transcriptCache = mapOf(open.id to reply("Fixed it.")))
        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(t0)
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> awaitCancellation() },
                homeSnapshot = { _ -> awaitCancellation() },
            )
            this[ExperimentalFeatures] = liveActivityOn
            this[SessionLiveActivityClient] = recorder(pushes)
        }
        store.exhaustive = false

        store.send(Action.ServerEvent(OpenCodeServerEvent.ExecutionStarted(open.id)))
        assertEquals(true, store.state.sessionActivity?.sawServerRun)
        store.send(Action.ServerEvent(OpenCodeServerEvent.AttentionChanged(askedSessionID = open.id)))
        assertEquals(Phase.needsInput, store.state.sessionActivity?.content?.phase)

        // Answered, it works again.
        store.send(Action.AttentionRefreshed(emptySet()))
        assertEquals(Phase.working, store.state.sessionActivity?.content?.phase)
        store.send(Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded(open.id)))
        assertEquals(content(Phase.done, preview = "Fixed it."), store.state.sessionActivity?.content)
        eventually { pushes.value.size >= 3 }
        assertEquals(listOf(content(Phase.needsInput), working(), content(Phase.done, preview = "Fixed it.")), pushes.value)
        store.skipInFlightEffects()
    }

    /**
     * Settled, stream traffic costs the followed run nothing; a missing Done preview still catches
     * up from its own session's items, then nothing does.
     */
    @Test
    fun aSettledRunSkipsStreamTrafficOnceItShowsAllItCan() = homeTest { h ->
        val state = h.following(sawServerRun = true)
            .withActivity { it.copy(content = it.content.copy(phase = Phase.done)) }
            // Its reply read, not yet shown.
            .copy(transcriptCache = mapOf(open.id to reply("Fixed it.")))
        val batch = Action.ServerEvent(
            OpenCodeServerEvent.ContentBatch(open.id, listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "m", ordinal = 0, delta = "x"))),
        )
        val elsewhere = Action.ServerEvent(
            OpenCodeServerEvent.ContentBatch(other.id, listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "m", ordinal = 0, delta = "x"))),
        )
        assertTrue(state.streamMovesSessionActivity(batch))
        assertFalse("another session's items can't bring its preview", state.streamMovesSessionActivity(elsewhere))
        val working = state.withActivity { it.copy(content = it.content.copy(phase = Phase.working)) }
        assertTrue(working.streamMovesSessionActivity(elsewhere))

        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val store = h.store(state) {
            // The cached copy's next read waits out its pause, never reached here.
            this[Clock] = TestClock()
            this[ExperimentalFeatures] = liveActivityOn
            this[SessionLiveActivityClient] = recorder(pushes)
        }
        store.send(elsewhere)
        store.send(batch) {
            it.copy(refreshingTranscripts = setOf(open.id))
                .withActivity { tracked -> tracked.copy(content = tracked.content.copy(preview = "Fixed it.")) }
        }
        assertFalse(store.state.streamMovesSessionActivity(batch))
        val failed = store.state.withActivity { it.copy(content = content(Phase.failed)) }
        assertFalse(failed.streamMovesSessionActivity(batch))
        store.send(batch)
        eventually { pushes.value.isNotEmpty() }
        assertEquals(listOf(content(Phase.done, preview = "Fixed it.")), pushes.value)
        store.skipInFlightEffects()
    }

    /** A run stopped from the app ends its activity at once. */
    @Test
    fun aStoppedRunEndsTheActivity() = homeTest { h ->
        val state = h.following(sawServerRun = true, active = true).copy(
            transcriptCache = mapOf(
                open.id to listOf(
                    OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Go"),
                    OpenCodeTranscriptItem.Info(id = "msg_a1-error", text = "Interrupted"),
                ),
            ),
        )
        val pushes = LockIsolated<List<SessionActivityAttributes.ContentState?>>(emptyList())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(homeSnapshot = { _ -> awaitCancellation() })
            this[ExperimentalFeatures] = liveActivityOn
            this[SessionLiveActivityClient] = recorder(pushes)
        }
        store.exhaustive = false
        store.send(Action.ServerEvent(OpenCodeServerEvent.ExecutionEnded(open.id)))
        assertNull(store.state.sessionActivity)
        eventually { pushes.value.isNotEmpty() }
        assertEquals(listOf<SessionActivityAttributes.ContentState?>(null), pushes.value)
        store.skipInFlightEffects()
    }

    // endregion

    /**
     * All Experiments switching Session Live Activity off ends the activity, as the switch itself
     * does.
     */
    @Test
    fun allSwitchingTheLiveActivityOffEndsIt() = homeTest { h ->
        val pushes = LockIsolated(listOf<SessionActivityAttributes.ContentState?>())
        val store = h.store(h.following()) {
            this[SessionLiveActivityClient] = recorder(pushes)
        }
        store.send(Action.Settings(HomeSettingsFeature.Action.ExperimentalFeatureApplied(ExperimentalFeature.sessionLiveActivity, false))) {
            it.copy(sessionActivity = null)
        }
        store.finish()
        assertEquals(listOf<SessionActivityAttributes.ContentState?>(null), pushes.value)
    }
}
