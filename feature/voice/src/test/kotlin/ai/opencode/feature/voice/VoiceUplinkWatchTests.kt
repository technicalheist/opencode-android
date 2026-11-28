package ai.opencode.feature.voice

import ai.opencode.feature.voice.ReplyWatch.Effect
import ai.opencode.feature.voice.ReplyWatch.Event
import ai.opencode.feature.voice.ReplyWatch.LogEntry
import ai.opencode.feature.voice.ReplyWatch.Outcome
import ai.opencode.feature.voice.ReplyWatch.SettlementSource
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VoiceUplinkWatchTests {
    private val t0 = Instant.ofEpochSecond(1_000)
    private fun Instant.adding(seconds: Double): Instant = plusNanos((seconds * 1e9).toLong())

    @Test fun quickSendsStayHealthy() {
        var watch = VoiceUplinkWatch()
        for (i in 0 until 50) {
            val at = t0.adding(i * 0.1)
            watch = watch.sendStarted(at)
            val (finished, change) = watch.sendFinished(at.adding(0.004))
            watch = finished
            assertNull(change)
            val (checked, checkChange) = watch.check(at.adding(0.05))
            watch = checked
            assertNull(checkChange)
        }
        assertFalse(watch.isPoor)
    }

    @Test fun aStuckSendIsPoorBeforeItReturnsThenRecoversAfterQuietSends() {
        var watch = VoiceUplinkWatch()
        watch = watch.sendStarted(t0)
        fun check(seconds: Double): Boolean? = watch.check(t0.adding(seconds)).let { watch = it.first; it.second }
        assertNull(check(0.5))
        assertEquals(true, check(1.1))
        assertNull("reported once", check(2.0))
        // It finally goes out after 3.9 s, then sends are quick again.
        watch.sendFinished(t0.adding(3.9)).let { watch = it.first; assertNull(it.second) }
        var recoveredAt: Double? = null
        for (i in 1..60) {
            val offset = 3.9 + i * 0.1
            val at = t0.adding(offset)
            watch = watch.sendStarted(at)
            watch = watch.sendFinished(at.adding(0.004)).first
            val (checked, change) = watch.check(at.adding(0.01))
            watch = checked
            if (change == false) { recoveredAt = offset; break }
        }
        assertNotNull(recoveredAt)
        assertTrue((recoveredAt ?: 0.0) >= 3.9 + VoiceUplinkWatch.recoverAfter - 0.01)
        assertFalse(watch.isPoor)
    }

    @Test fun aSlowFinishedSendIsPoor() {
        var watch = VoiceUplinkWatch()
        watch = watch.sendStarted(t0)
        val (finished, change) = watch.sendFinished(t0.adding(1.8))
        watch = finished
        assertEquals(true, change)
        assertTrue(watch.isPoor)
    }
}

class ReplyWatchCoreTests {
    data class Step(val event: Event, val expectedEffects: List<Effect>)

    data class Scenario(
        val name: String,
        val initial: ReplyWatch,
        val steps: List<Step>,
        val expectedWatchedIDs: List<String>,
        val expectedCompletedOutcomes: List<Outcome>,
    )

    companion object {
        private val blogPrompt = ReplyWatch.Prompt(promptID = "msg_blog", sessionID = "ses_blog", title = "Blog game")
        private val settledBlogPrompt = ReplyWatch.Prompt(
            promptID = "msg_blog",
            sessionID = "ses_blog",
            title = "Blog game",
            isFetchingSettledReply = true,
        )

        private fun announce(text: String) = Effect.Announce(
            sessionID = "ses_blog",
            promptIDs = listOf("msg_blog"),
            text = Outcome.Replied(text).notice("Blog game"),
        )

        private fun resolved(text: String) =
            Effect.Log(LogEntry.Resolved(promptID = "msg_blog", title = "Blog game", outcome = Outcome.Replied(text)))

        private val sentBlog = Step(
            event = Event.Sent(listOf(blogPrompt)),
            expectedEffects = listOf(
                Effect.Log(LogEntry.WatchStarted(promptID = "msg_blog", sessionID = "ses_blog", title = "Blog game")),
                Effect.StartWait(blogPrompt),
            ),
        )

        private fun succeeded(text: String) = Step(
            event = Event.WaitSucceeded(promptID = "msg_blog", reply = text),
            expectedEffects = listOf(
                Effect.CancelWatch(promptID = "msg_blog"),
                resolved(text),
                announce(text),
                Effect.FindBackgroundWork(sessionID = "ses_blog", promptID = "msg_blog", title = "Blog game", turn = 0, reply = text),
            ),
        )

        private fun settled(source: SettlementSource) = listOf(
            Effect.Log(LogEntry.Settled(promptID = "msg_blog", title = "Blog game", source = source)),
            Effect.FetchSettledReply(settledBlogPrompt, source = source),
        )

        val scenarios: List<Scenario>
            get() = listOf(
                Scenario(
                    name = "stalled /wait rescued by SSE executionEnded",
                    initial = ReplyWatch(callPhase = ReplyWatch.CallPhase.live),
                    steps = listOf(
                        sentBlog,
                        Step(Event.SseExecutionEnded(sessionID = "ses_blog"), settled(SettlementSource.sse)),
                        succeeded("Implemented the scoring loop."),
                    ),
                    expectedWatchedIDs = emptyList(),
                    expectedCompletedOutcomes = listOf(Outcome.Replied("Implemented the scoring loop.")),
                ),
                Scenario(
                    name = "finishing during a network blip fetches reply immediately when session is inactive",
                    initial = ReplyWatch(callPhase = ReplyWatch.CallPhase.live),
                    steps = listOf(
                        sentBlog,
                        Step(
                            Event.WaitFailed(
                                promptID = "msg_blog",
                                error = ReplyWatch.WaitError.Retryable("Couldn’t reach the OpenCode server"),
                                sessionStillActive = false,
                            ),
                            settled(SettlementSource.snapshot),
                        ),
                        succeeded("Finished during the blip."),
                    ),
                    expectedWatchedIDs = emptyList(),
                    expectedCompletedOutcomes = listOf(Outcome.Replied("Finished during the blip.")),
                ),
                Scenario(
                    name = "off-screen session finishing over SSE while viewing another active session",
                    initial = ReplyWatch(activeSessionIDs = setOf("ses_ios"), callPhase = ReplyWatch.CallPhase.live),
                    steps = listOf(
                        sentBlog,
                        Step(Event.SseExecutionEnded(sessionID = "ses_blog"), settled(SettlementSource.sse)),
                        succeeded("Off-screen work done."),
                    ),
                    expectedWatchedIDs = emptyList(),
                    expectedCompletedOutcomes = listOf(Outcome.Replied("Off-screen work done.")),
                ),
                Scenario(
                    name = "catching up after a reconnect via active-session snapshot",
                    initial = ReplyWatch(callPhase = ReplyWatch.CallPhase.live),
                    steps = listOf(
                        sentBlog,
                        Step(Event.Reconnected, listOf(Effect.RefreshActiveSessions(source = SettlementSource.reconnect))),
                        Step(
                            Event.ActiveSessionsSnapshot(emptySet(), source = SettlementSource.reconnect),
                            settled(SettlementSource.reconnect),
                        ),
                        succeeded("Caught up after reconnect."),
                    ),
                    expectedWatchedIDs = emptyList(),
                    expectedCompletedOutcomes = listOf(Outcome.Replied("Caught up after reconnect.")),
                ),
                Scenario(
                    name = "session that ended earlier and then receives a new prompt before executionStarted starts a wait without announcing",
                    initial = ReplyWatch(activeSessionIDs = setOf("ses_blog"), callPhase = ReplyWatch.CallPhase.live)
                        .setSessionActive("ses_blog", false),
                    steps = listOf(
                        Step(Event.SseExecutionEnded(sessionID = "ses_blog"), emptyList()),
                        sentBlog,
                    ),
                    expectedWatchedIDs = listOf("msg_blog"),
                    expectedCompletedOutcomes = emptyList(),
                ),
                Scenario(
                    name = "duplicate completion is ignored",
                    initial = ReplyWatch(callPhase = ReplyWatch.CallPhase.live),
                    steps = listOf(
                        sentBlog,
                        succeeded("First completion."),
                        Step(Event.WaitSucceeded(promptID = "msg_blog", reply = "Duplicate completion."), emptyList()),
                        Step(Event.Sent(listOf(blogPrompt)), emptyList()),
                    ),
                    expectedWatchedIDs = emptyList(),
                    expectedCompletedOutcomes = listOf(Outcome.Replied("First completion.")),
                ),
                Scenario(
                    name = "call ending mid-watch cancels in-flight watches and ignores late replies",
                    initial = ReplyWatch(callPhase = ReplyWatch.CallPhase.live),
                    steps = listOf(
                        sentBlog,
                        Step(
                            Event.CallPhaseChanged(ReplyWatch.CallPhase.ended),
                            listOf(Effect.CancelWatch(promptID = "msg_blog")),
                        ),
                        Step(Event.WaitSucceeded(promptID = "msg_blog", reply = "Too late."), emptyList()),
                    ),
                    expectedWatchedIDs = emptyList(),
                    expectedCompletedOutcomes = emptyList(),
                ),
            )
    }

    /** One run per scenario, named in the failure. */
    @Test fun replyWatchStateMachineScenarios() {
        for (scenario in scenarios) {
            var state = scenario.initial
            for (step in scenario.steps) {
                val (next, effects) = state.transition(step.event)
                assertEquals(scenario.name, step.expectedEffects, effects)
                state = next
            }
            assertEquals(scenario.name, scenario.expectedWatchedIDs, state.watched.map { it.promptID })
            assertEquals(scenario.name, scenario.expectedCompletedOutcomes, state.completed.map { it.outcome })
        }
    }

    @Test fun statusSummaryReflectsLiveSessionRunStateTrackedWorkAndInFlightRequests() {
        val viewing = OpenCodeSession(
            id = "ses_ios", serverTitle = "OPENCODE IOS", projectID = "p",
            directory = "/Users/kit/opencode-ios", updatedAt = Instant.EPOCH,
        )
        var watch = ReplyWatch(activeSessionIDs = setOf("ses_blog"), callPhase = ReplyWatch.CallPhase.live)
        watch = watch.transition(
            Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_blog", sessionID = "ses_blog", title = "Blog game"))),
        ).first

        val idleViewing = watch.statusSummary(
            viewing = viewing,
            needsInput = false,
            draftDirectory = viewing.directory,
            hasDirectVoiceWork = false,
            otherDelegatorRequestsInFlight = 1,
        )
        assertTrue(idleViewing.contains("The call is attached to session \"OPENCODE IOS\" (id ses_ios, project opencode-ios, idle)."))
        assertTrue(idleViewing.contains("- \"Blog game\" (id ses_blog, prompt msg_blog): working (live status: active on server)."))
        assertTrue(idleViewing.contains("Other voice delegator requests currently in flight: 1."))

        watch = watch.setSessionActive("ses_ios", true)
        val activeViewing = watch.statusSummary(
            viewing = viewing,
            needsInput = true,
            draftDirectory = viewing.directory,
        )
        assertTrue(activeViewing.contains("The call is attached to session \"OPENCODE IOS\" (id ses_ios, project opencode-ios, working). It is waiting on a pending request: the user's approval or answer goes through list_requests and answer_question or reply_permission, never send_message."))
    }
}
