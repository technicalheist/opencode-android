package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.EventStream
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Gate
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.delegateEvent
import ai.opencode.feature.voice.VoiceFeatureTestSupport.increment
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receiveEnded
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.settle
import ai.opencode.feature.voice.VoiceFeatureTestSupport.transcripts
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceWork
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.server.OpenCodeClientError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class VoiceFeatureTestsDelegation {
    private lateinit var h: Harness

    private fun makeStore(
        apiKey: String? = "sk-test",
        configure: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<State, Action> {
        // Each test starts from no saved conversation (the shared store is the test's own).
        val store = h.store(h.state { State(server, session) }) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            // Android: an ended call's notification lingers on the clock.
            this[Clock] = ImmediateClock()
            voiceCall { copy(completeFunction = { _, _ -> }) }
            configure()
        }
        store.exhaustive = false
        store.setAPIKey(apiKey)
        // These cover calls on this phone's key; Automatic would try the server first.
        store.withDependencies { shared(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone) }
        return store
    }

    private suspend fun startLiveCall(
        configure: DependencyValues.Builder.() -> Unit = {},
    ): Pair<TestStore<State, Action>, EventStream> {
        val events = EventStream()
        val store = makeStore {
            voiceCall { copy(start = { _, _ -> events.events }) }
            configure()
        }
        store.send(Action.Start)
        events.yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live() }
        return store to events
    }

    @Test fun delegationSteersAndSpeaksTheReply() = voiceTest { harness ->
        h = harness
        val events = EventStream()
        val submitted = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<Pair<String, String>>())
        val store = makeStore {
            voiceCall {
                copy(
                    start = { _, config ->
                        assertTrue(config.instructions.contains("\"Voice lab\""))
                        assertTrue(config.instructions.contains("user: earlier context"))
                        events.events
                    },
                    completeFunction = { callID, output -> completed.append(callID to output) },
                )
            }
            voiceWork {
                copy(
                    recentMessages = { _, _, _ -> listOf("user: earlier context") },
                    submit = { _, text ->
                        submitted.append(text)
                        VoiceWorkAdmission(promptID = "msg_voice_1")
                    },
                    awaitReply = { _, _, _ -> "Fixed the failing test by tightening the fixture.\n\n```swift\nlet x = 1\n```" },
                )
            }
        }

        store.send(Action.Start)
        events.yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live() }

        events.yield(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "fix the failing test", final = true))
        store.receive(Action.CallEvent::class)
        events.yield(VoiceCallEvent.AssistantDone(itemID = "item_2", text = "On it."))
        store.receive(Action.CallEvent::class)
        events.yield(delegateEvent(id = "call_1", text = "Fix the failing test."))
        store.receive(Action.CallEvent::class)
        assertEquals(1, store.state.pendingWork.size)

        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkReplied::class) { it.copy(pendingWork = emptyList()) }

        // The user's own words are the request; Realtime's `delegate` text is the voice agent's framing.
        val text = submitted.value[0]
        val first = handoffBody(text)
        assertEquals(JsonPrimitive("fix the failing test"), first?.get("instruction"))
        assertEquals(JsonPrimitive("Fix the failing test."), first?.get("voice"))
        assertEquals(
            JsonArray(listOf(JsonPrimitive("user: fix the failing test"), JsonPrimitive("assistant: On it."))),
            first?.get("transcript_delta"),
        )
        assertEquals(listOf("call_1"), completed.value.map { it.first })
        assertTrue(completed.value[0].second.startsWith("[COMPLETE] Fixed the failing test"))

        // Accepted lines are not re-sent by a later delegation.
        events.yield(delegateEvent(id = "call_2", text = "Run the suite."))
        store.receive(Action.CallEvent::class)
        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkReplied::class)
        assertEquals(JsonArray(emptyList()), handoffBody(submitted.value[1])?.get("transcript_delta"))
        // Nothing new was said: the framing is the request, and isn't repeated as a label.
        assertEquals(JsonPrimitive("Run the suite."), handoffBody(submitted.value[1])?.get("instruction"))
        assertNull(handoffBody(submitted.value[1])?.get("voice"))

        events.finish()
        store.receive(Action.CallEnded::class) { it.withPhase(State.Phase.Ended) }
        store.receiveEnded()
        assertEquals(listOf("user: fix the failing test"), store.transcripts()["${server.id}:ses_voice"]?.tail)
        store.finish()
    }

    @Test fun failedAdmissionReleasesTranscriptAndReportsFailure() = voiceTest { harness ->
        h = harness
        val completed = LockIsolated(listOf<String>())
        val (store, events) = startLiveCall {
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }) }
            voiceWork { copy(submit = { _, _ -> throw OpenCodeClientError(message = "HTTP 503") }) }
        }
        events.yield(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "ship it", final = true))
        store.receive(Action.CallEvent::class)
        events.yield(delegateEvent(text = "Ship it."))
        store.receive(Action.CallEvent::class)
        store.receive(Action.WorkFailed::class) { it.copy(pendingWork = emptyList()) }
        settle()
        assertEquals(listOf(VoiceHandoff.failure("HTTP 503")), completed.value)
        // The line is available again for the retry.
        val tail = store.state.tail
        assertEquals(listOf("user: ship it"), tail.capture().capture.lines)

        store.send(Action.EndTapped) { it.withPhase(State.Phase.Ended) }
        store.receiveEnded()
        store.finish()
    }

    @Test fun transientWaitFailureRetriesAndSpeaksTheReply() = voiceTest { harness ->
        h = harness
        val attempts = LockIsolated(0)
        val completed = LockIsolated(listOf<String>())
        val store = makeStore {
            this[Clock] = ImmediateClock()
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }) }
            voiceWork {
                copy(
                    submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_voice_retry") },
                    awaitReply = { _, _, _ ->
                        val attempt = attempts.increment()
                        if (attempt == 1) throw OpenCodeClientError(message = "The server returned HTTP 502.", statusCode = 502)
                        "All delegated work completed successfully."
                    },
                )
            }
        }

        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "finish the delegated work", final = true)))
        store.send(Action.CallEvent(delegateEvent(id = "call_retry", text = "Finish the delegated work.")))
        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkReplied::class) { it.copy(pendingWork = emptyList()) }
        settle()

        assertEquals(2, attempts.value)
        assertEquals(listOf(VoiceHandoff.completion("All delegated work completed successfully.")), completed.value)
        store.finish()
    }

    @Test fun longRunningWaitKeepsRewaitingWhileSessionIsRunning() = voiceTest { harness ->
        h = harness
        val attempts = LockIsolated(0)
        val completed = LockIsolated(listOf<String>())
        val store = makeStore {
            this[Clock] = ImmediateClock()
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }) }
            voiceWork {
                copy(
                    submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_long_run") },
                    checkSessionActive = { _, _ -> true },
                    awaitReply = { _, _, _ ->
                        val attempt = attempts.increment()
                        if (attempt <= 6) throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server")
                        "Finished the 50-minute migration."
                    },
                )
            }
        }

        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "run the full migration", final = true)))
        store.send(Action.CallEvent(delegateEvent(id = "call_long", text = "Run the full migration.")))
        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkReplied::class) { it.copy(pendingWork = emptyList()) }
        settle()

        assertEquals(7, attempts.value)
        assertEquals(listOf(VoiceHandoff.completion("Finished the 50-minute migration.")), completed.value)
        store.finish()
    }

    @Test fun wedgedActiveWaitTerminatesWithFailureAnd4xxDoesNotRetry() = voiceTest { harness ->
        h = harness
        val clock = TestClock()
        val attempts = LockIsolated(0)
        val completed = LockIsolated(listOf<String>())
        val store = makeStore {
            this[Clock] = clock
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }) }
            voiceWork {
                copy(
                    submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_wedged") },
                    checkSessionActive = { _, _ -> true },
                    awaitReply = { _, _, promptID ->
                        val attempt = attempts.increment()
                        if (promptID == "msg_404") throw OpenCodeClientError(message = "The server returned HTTP 404.", statusCode = 404)
                        throw OpenCodeClientError(message = "The server returned HTTP 502 (attempt $attempt).", statusCode = 502)
                    },
                )
            }
        }

        store.send(Action.CallEvent(delegateEvent(id = "call_wedged", text = "Run the migration.")))
        store.receive(Action.WorkAdmitted::class)
        for (step in 1 until 30) {
            val backoff = minOf(16, 1 shl minOf(step - 1, 4))
            while (attempts.value <= step) {
                yield()
                clock.advance(backoff.seconds)
            }
        }
        store.receive(Action.WorkFailed::class) { it.copy(pendingWork = emptyList()) }
        settle()
        assertEquals(30, attempts.value)
        assertTrue(completed.value.lastOrNull()?.contains("HTTP 502 (attempt 30)") == true)

        // 4xx does not retry even when `checkSessionActive` is true.
        attempts.setValue(0)
        store.updateDependencies { voiceWork { copy(submit = { _, _ -> VoiceWorkAdmission(promptID = "msg_404") }) } }
        store.send(Action.CallEvent(delegateEvent(id = "call_404", text = "Run on missing session.")))
        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkFailed::class) { it.copy(pendingWork = emptyList()) }
        settle()
        assertEquals(1, attempts.value)
        assertEquals(VoiceHandoff.failure("The server returned HTTP 404."), completed.value.lastOrNull())
        store.finish()
    }

    @Test fun concurrentDelegationsFinishingTogetherSpeakOnlyTheFinalReplyOnce() = voiceTest { harness ->
        h = harness
        val completed = LockIsolated(listOf<Pair<String, String>>())
        val abandoned = LockIsolated(listOf<String>())
        val nextPrompt = LockIsolated(0)
        val gate = Gate()
        val store = makeStore {
            this[Clock] = ImmediateClock()
            voiceCall {
                copy(
                    completeFunction = { callID, output -> completed.append(callID to output) },
                    closeDelegation = { callID, _ -> abandoned.append(callID) },
                )
            }
            voiceWork {
                copy(
                    submit = { _, _ ->
                        val index = nextPrompt.increment()
                        VoiceWorkAdmission(promptID = "msg_$index")
                    },
                    awaitReply = { _, _, promptID ->
                        gate.await()
                        // `msg_1` had no assistant text before `msg_2` steered the run; `msg_2` has the final answer.
                        if (promptID == "msg_1") null else "The migration finished with zero errors."
                    },
                )
            }
        }

        store.send(Action.CallEvent(delegateEvent(id = "call_1", text = "Start the migration.")))
        store.receive(Action.WorkAdmitted::class)
        store.send(Action.CallEvent(delegateEvent(id = "call_2", text = "Also verify the schema.")))
        store.receive(Action.WorkAdmitted::class)

        gate.yield()
        gate.finish()
        store.receive(Action.WorkReplied::class)
        store.receive(Action.WorkReplied::class) { it.copy(pendingWork = emptyList()) }
        settle()

        assertEquals(1, completed.value.size)
        assertEquals("call_2", completed.value.firstOrNull()?.first)
        assertEquals("[COMPLETE] The migration finished with zero errors.", completed.value.firstOrNull()?.second)
        assertEquals(listOf("call_1"), abandoned.value)
        store.finish()
    }

    @Test fun failedNewerDelegationDoesNotSilenceEarlierToolOnlyDelegation() = voiceTest { harness ->
        h = harness
        val completed = LockIsolated(listOf<Pair<String, String>>())
        val abandoned = LockIsolated(listOf<String>())
        val gate = Gate()
        val store = makeStore {
            this[Clock] = ImmediateClock()
            voiceCall {
                copy(
                    completeFunction = { callID, output -> completed.append(callID to output) },
                    closeDelegation = { callID, _ -> abandoned.append(callID) },
                )
            }
            voiceWork {
                copy(
                    submit = { _, text ->
                        if (text.contains("Second failing request")) throw OpenCodeClientError(message = "HTTP 503", statusCode = 503)
                        VoiceWorkAdmission(promptID = "msg_1")
                    },
                    awaitReply = { _, _, _ ->
                        gate.await()
                        null
                    },
                )
            }
        }

        store.send(Action.CallEvent(delegateEvent(id = "call_1", text = "First tool-only request.")))
        store.receive(Action.WorkAdmitted::class)

        store.send(Action.CallEvent(delegateEvent(id = "call_2", text = "Second failing request.")))
        store.receive(Action.WorkFailed::class)
        settle()

        gate.yield()
        gate.finish()
        store.receive(Action.WorkReplied::class) { it.copy(pendingWork = emptyList()) }
        settle()

        assertTrue(abandoned.value.isEmpty())
        assertEquals(listOf("call_2", "call_1"), completed.value.map { it.first })
        assertEquals("[COMPLETE] OpenCode finished without a text reply.", completed.value.lastOrNull()?.second)
        store.finish()
    }

    @Test fun endingDuringAdmissionAbandonsTheLatePrompt() = voiceTest { harness ->
        h = harness
        val entered = Gate()
        val admission = Gate()
        val submitReturned = LockIsolated(false)
        val store = makeStore {
            voiceWork {
                copy(
                    submit = { _, _ ->
                        entered.yield()
                        // Held until the hang-up cancels it; the server still admits the prompt, so
                        // the POST reports success after the call is over.
                        try {
                            admission.awaitFinish()
                        } catch (_: CancellationException) {
                        }
                        submitReturned.setValue(true)
                        VoiceWorkAdmission(promptID = "msg_late")
                    },
                    awaitReply = { _, _, _ -> throw AssertionError("an ended call must not wait on work it abandoned") },
                )
            }
        }

        store.send(Action.CallEvent(delegateEvent(id = "call_late", text = "Ship it.")))
        entered.await()
        assertTrue(store.state.admittedPromptIDs.isEmpty())
        store.send(Action.EndTapped) { it.withPhase(State.Phase.Ended).copy(pendingWork = emptyList()) }
        store.receiveEnded()
        admission.finish()
        store.finish()
        assertTrue(submitReturned.value)
    }

    @Test fun delegateWithoutTextFallsBackToLatestSpokenRequest() = voiceTest { harness ->
        h = harness
        val submitted = LockIsolated(listOf<String>())
        val store = makeStore {
            voiceWork {
                copy(
                    submit = { _, text ->
                        submitted.append(text)
                        VoiceWorkAdmission(promptID = "msg_spoken_fallback")
                    },
                    awaitReply = { _, _, _ -> "Done." },
                )
            }
        }

        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "Retry the release build", final = true)))
        store.send(Action.CallEvent(delegateEvent(id = "call_fallback")))
        store.receive(Action.WorkAdmitted::class)
        store.receive(Action.WorkReplied::class) { it.copy(pendingWork = emptyList()) }

        assertEquals(JsonPrimitive("Retry the release build"), handoffBody(submitted.value[0])?.get("instruction"))
        store.finish()
    }

    @Test fun delegateWithoutTextOrSpeechAsksModelToRetryTheTool() = voiceTest { harness ->
        h = harness
        val completed = LockIsolated(listOf<String>())
        val store = makeStore {
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }) }
            voiceWork { copy(submit = { _, _ -> throw AssertionError("an empty delegation must not be submitted") }) }
        }

        store.send(Action.CallEvent(delegateEvent(id = "call_empty")))
        settle()
        assertEquals(
            listOf(VoiceHandoff.failure("No spoken request was available. Call delegate again with the exact request text.")),
            completed.value,
        )
        store.finish()
    }

    @Test fun aLongCallKeepsItsNewestCaptions() = voiceTest { harness ->
        h = harness
        val (store, events) = startLiveCall { voiceCall { copy(end = {}) } }
        val limit = VoiceTranscriptRecord.maxLogLines
        for (index in 0 until limit + 50) {
            store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u$index", text = "line $index", final = true)))
        }
        assertEquals(limit, store.state.captions.size)
        assertEquals("user-u50", store.state.captions.first().id)
        assertEquals(2 * CaptionReveal.maxLines, store.state.captionTargets.size)
        assertEquals("line ${limit + 49}", store.state.captionTargets.last().text)

        events.finish()
        store.receive(Action.CallEnded::class)
        val log = store.transcripts()["${server.id}:ses_voice"]?.log
        assertEquals(limit, log?.size)
        assertEquals("user: line 50", log?.first())
        store.finish()
    }

    @Test fun transcriptsKeepTheNewestSessions() = voiceTest { harness ->
        h = harness
        val older = (0 until 70).map { "${server.id}:ses_old_$it" }
        val (store, events) = startLiveCall { voiceCall { copy(end = {}) } }
        store.withDependencies {
            val records = mutableMapOf<String, VoiceTranscriptRecord>()
            for ((index, key) in older.withIndex()) {
                records[key] = VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(index - 1_000L), tail = emptyList(), log = listOf("user: old"))
            }
            shared(VoiceSharedKeys.voiceTranscripts).set(records)
        }
        events.yield(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "hello there", final = true))
        store.receive(Action.CallEvent::class)
        events.finish()
        store.receive(Action.CallEnded::class)
        store.finish()

        val kept = store.transcripts()
        assertTrue(kept.size <= VoiceTranscriptRecord.maxSessions)
        assertEquals(listOf("user: hello there"), kept["${server.id}:ses_voice"]?.tail)
        assertNull("The session called longest ago went", kept[older[0]])
        assertTrue(kept[older[69]] != null)
    }

    @Test fun failedCallPersistsOnceAndEndsWithoutASecondWrite() = voiceTest { harness ->
        h = harness
        val (store, events) = startLiveCall { voiceCall { copy(end = {}) } }
        events.yield(VoiceCallEvent.UserTranscript(itemID = "item_1", text = "hello there", final = true))
        store.receive(Action.CallEvent::class)
        events.finish(throwing = RealtimeClientError.Closed("socket dropped"))
        store.receive(Action.CallEnded::class) { it.withPhase(State.Phase.Failed("socket dropped")) }
        val key = "${server.id}:ses_voice"
        assertEquals(listOf("user: hello there"), store.transcripts()[key]?.tail)
        val logged = store.transcripts()[key]?.log?.size

        store.send(Action.EndTapped)
        store.receiveEnded()
        assertEquals(
            "A dismissed failure keeps its phase: there's no bar to sweep out",
            State.Phase.Failed("socket dropped"), store.state.phase,
        )
        assertEquals("Dismissing a failed call must not append the transcript again", logged, store.transcripts()[key]?.log?.size)
        store.finish()
    }

    @Test fun retryAfterFailureStartsUnmuted() = voiceTest { harness ->
        h = harness
        val calls = LockIsolated(listOf<EventStream>())
        val store = makeStore {
            voiceCall {
                copy(start = { _, _ ->
                    val stream = EventStream()
                    calls.append(stream)
                    stream.events
                })
            }
        }

        store.send(Action.Start)
        VoiceFeatureTestSupport.eventually { calls.value.isNotEmpty() }
        calls.value[0].yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live() }
        store.send(Action.MuteTapped) { it.copy(isMuted = true) }
        calls.value[0].yield(VoiceCallEvent.AudioInterrupted)
        store.receive(Action.CallEvent::class) { it.copy(isAudioInterrupted = true) }
        calls.value[0].finish(throwing = RealtimeClientError.Closed("socket dropped"))
        store.receive(Action.CallEnded::class) { it.withPhase(State.Phase.Failed("socket dropped")) }

        // A new call opens a new, unmuted microphone; the bar must say so.
        store.send(Action.Start) {
            it.withPhase(State.Phase.Connecting).copy(isMuted = false, isAudioInterrupted = false)
        }
        h.skipInFlightEffects()
    }

    /** What the user said about looking held for that call only. */
    @Test fun aNewCallForgetsThatTheUserWasLooking() = voiceTest { harness ->
        h = harness
        val (store, events) = startLiveCall()
        store.send(Action.DelegatorNotedLooking(true)) { it.copy(userLooking = true) }
        store.send(Action.EndTapped)
        assertTrue(store.state.phase.isTerminal)
        // An ended call takes no word for it.
        store.send(Action.DelegatorNotedLooking(false))
        assertTrue(store.state.userLooking)

        store.send(Action.Start)
        assertFalse(store.state.userLooking)
        assertEquals(VoiceScreenVisibility.notLooking, store.state.screenVisibility)
        events.finish()
        h.skipInFlightEffects()
    }

    @Test fun endingACallLandsTheHangUpHapticOnce() = voiceTest { harness ->
        h = harness
        val (store, _) = startLiveCall()
        store.exhaustive = false
        store.send(Action.EndTapped)
        assertEquals(listOf(VoiceCallHaptic.callStarted, VoiceCallHaptic.ended), h.haptics.voice)
        // Already sweeping out.
        store.send(Action.EndTapped)
        assertEquals(listOf(VoiceCallHaptic.callStarted, VoiceCallHaptic.ended), h.haptics.voice)
        store.send(Action.EndPressed)
        assertEquals(listOf(VoiceCallHaptic.callStarted, VoiceCallHaptic.ended), h.haptics.voice)
    }

    @Test fun pressingEndAnswersTheFingerThenLandsTheCallsCue() = voiceTest { harness ->
        h = harness
        val (store, _) = startLiveCall()
        h.haptics.reset()
        store.send(Action.EndPressed)
        store.receive(Action.EndTapped::class)
        assertEquals(listOf("cancel", "voice.ended"), h.haptics.log)
        // Already sweeping out: nothing more.
        store.send(Action.EndPressed)
        assertEquals(listOf("cancel", "voice.ended"), h.haptics.log)
    }

    @Test fun cancellingAConnectingCallAnswersTheFinger() = voiceTest { harness ->
        h = harness
        val events = EventStream()
        val store = makeStore { voiceCall { copy(start = { _, _ -> events.events }) } }
        store.send(Action.Start) { it.withPhase(State.Phase.Connecting) }
        store.send(Action.EndPressed)
        store.receive(Action.EndTapped::class) { it.withPhase(State.Phase.Ended) }
        assertEquals(listOf("voice.callStarted", "cancel", "voice.ended"), h.haptics.log)
    }

    @Test fun closingAFailedCallsCardBacksOut() = voiceTest { harness ->
        h = harness
        val store = makeStore(apiKey = null)
        store.send(Action.Start) {
            it.withPhase(State.Phase.Failed("Add your OpenAI API key in Settings to use Voice."))
        }
        store.send(Action.EndPressed)
        store.receive(Action.EndTapped::class)
        store.receiveEnded()
        assertEquals(listOf("cancel"), h.haptics.log)
    }

    /**
     * The agent's goodbye and the Live Activity end the call without a finger
     * on the bar: only the call's own cue plays.
     */
    @Test fun anEndWithoutAPressPlaysOnlyTheCallsCue() = voiceTest { harness ->
        h = harness
        val (store, _) = startLiveCall()
        h.haptics.reset()
        store.send(Action.EndTapped)
        assertEquals(listOf("voice.ended"), h.haptics.log)
    }

    @Test fun callStartAndMuteTogglesTriggerDistinctVoiceHaptics() = voiceTest { harness ->
        h = harness
        val (store, events) = startLiveCall()
        assertEquals(listOf(VoiceCallHaptic.callStarted), h.haptics.voice)
        store.send(Action.MuteTapped) { it.copy(isMuted = true) }
        assertEquals(listOf(VoiceCallHaptic.callStarted, VoiceCallHaptic.muted), h.haptics.voice)
        store.send(Action.MuteTapped) { it.copy(isMuted = false) }
        assertEquals(listOf(VoiceCallHaptic.callStarted, VoiceCallHaptic.muted, VoiceCallHaptic.unmuted), h.haptics.voice)

        events.finish()
        store.receive(Action.CallEnded::class) { it.withPhase(State.Phase.Ended) }
        store.receiveEnded()
        store.finish()
    }

    @Test fun missingKeyFailsBeforeAnyConnection() = voiceTest { harness ->
        h = harness
        val store = makeStore(apiKey = null) {
            voiceCall { copy(start = { _, _ -> throw AssertionError("must not connect without a key") }) }
        }
        store.send(Action.Start) {
            it.withPhase(State.Phase.Failed("Add your OpenAI API key in Settings to use Voice."))
        }
    }

    /**
     * The call bar's view appearing again (Settings closing, the drawer
     * swapping sessions) never restarts a live call.
     */
    @Test fun appearingAgainNeverRestartsALiveCall() = voiceTest { harness ->
        h = harness
        val starts = LockIsolated(0)
        val events = EventStream()
        val store = makeStore {
            voiceCall {
                copy(start = { _, _ ->
                    starts.increment()
                    events.events
                })
            }
        }
        store.send(Action.StartIfNeeded)
        store.receive(Action.Start::class)
        events.yield(VoiceCallEvent.Connected)
        store.receive(Action.CallEvent::class) { it.live() }
        store.send(Action.StartIfNeeded)
        assertEquals(1, starts.value)
        assertEquals(State.Phase.Live, store.state.phase)
        events.finish()
        store.receive(Action.CallEnded::class) { it.withPhase(State.Phase.Ended) }
        store.receiveEnded()
        store.finish()
    }

    /** Check API Key, then Back: the failed call waits for Try Again. */
    @Test fun aFailedCallStartsAgainOnlyOnTryAgain() = voiceTest { harness ->
        h = harness
        val store = makeStore(apiKey = null) {
            voiceCall { copy(start = { _, _ -> throw AssertionError("must not connect without a key") }) }
        }
        store.send(Action.StartIfNeeded)
        store.receive(Action.Start::class) {
            it.withPhase(State.Phase.Failed("Add your OpenAI API key in Settings to use Voice."))
        }
        assertFalse(store.state.awaitsFirstStart)
        // Exhaustive from here: a second `.start` would go unreceived and fail.
        store.exhaustive = true
        store.send(Action.StartIfNeeded)
        store.finish()
    }

    @Test fun rejectedKeyFailsBeforeOpeningTheCall() = voiceTest { harness ->
        h = harness
        val store = makeStore(apiKey = "sk-rejected") {
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> throw RealtimePreflightError.RejectedAPIKey })
            voiceCall { copy(start = { _, _ -> throw AssertionError("must not open a call after a failed preflight") }) }
        }

        store.send(Action.Start)
        store.receive(Action.CallEnded::class) {
            it.withPhase(State.Phase.Failed("OpenAI rejected the API key."))
        }
        store.finish()
    }

    @Test fun callAudioStartsAtTheTapBeforeThePreflightAndIsReleasedAfter() = voiceTest { harness ->
        h = harness
        val steps = LockIsolated(listOf<String>())
        val store = makeStore(apiKey = "sk-new") {
            this[VoiceAudioClient] = VoiceAudioClient(
                start = VoiceAudioClient.testValue.start,
                prepare = { atEar ->
                    steps.append("prepare $atEar")
                    7
                },
                discardPrepared = { token -> steps.append("discard $token") },
            )
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> steps.append("preflight") })
            voiceCall {
                copy(start = { _, _ ->
                    steps.append("call")
                    kotlinx.coroutines.flow.emptyFlow()
                })
            }
        }

        store.send(Action.Start)
        store.receive(Action.CallEnded::class)
        store.finish()
        assertEquals(listOf("prepare false", "preflight", "call", "discard 7"), steps.value)
    }

    @Test fun callAudioStartedAtTheTapIsReleasedWhenTheStartFails() = voiceTest { harness ->
        h = harness
        val discarded = LockIsolated(listOf<Int>())
        val store = makeStore(apiKey = "sk-rejected") {
            this[VoiceAudioClient] = VoiceAudioClient(
                start = VoiceAudioClient.testValue.start,
                prepare = { 3 },
                discardPrepared = { token -> discarded.append(token) },
            )
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> throw RealtimePreflightError.RejectedAPIKey })
        }

        store.send(Action.Start)
        store.receive(Action.CallEnded::class)
        store.finish()
        assertEquals(listOf(3), discarded.value)
    }

    @Test fun aFailedPreflightEndsThePendingStart() = voiceTest { harness ->
        h = harness
        val store = makeStore(apiKey = "sk-rejected") {
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> throw RealtimePreflightError.RejectedAPIKey })
        }

        store.send(Action.Start)
        // Counts `VoiceCallSounds.cancelPendingStart`, which ends the connecting stage the tap
        // began.
        assertTrue(h.haptics.sounds.stageElapsed(DotScopeSimulation.Stage.connecting) != null)
        store.receive(Action.CallEnded::class)
        assertNull("The scanner's timeline from the tap ends with the start", h.haptics.sounds.stageElapsed(DotScopeSimulation.Stage.connecting))
        store.finish()
    }

    @Test fun realtimeServiceErrorEndsWithTheServerMessage() = voiceTest { harness ->
        h = harness
        val (store, events) = startLiveCall()
        events.yield(VoiceCallEvent.ServiceError("The selected model is not available to this project."))
        store.receive(Action.CallEvent::class) {
            it.withPhase(State.Phase.Failed("The selected model is not available to this project."))
        }
        store.finish()
    }

    @Test fun microphoneDenialIsExplicit() = voiceTest { harness ->
        h = harness
        val store = makeStore {
            voiceCall { copy(start = { _, _ -> throw VoiceAudioError.microphoneDenied }) }
        }

        store.send(Action.Start)
        store.receive(Action.CallEnded::class) {
            it.withPhase(State.Phase.Failed("Allow microphone access in Settings to talk to your session."))
        }
        store.finish()
    }

    @Test fun rejectedCallAudioFailsTheCall() = voiceTest { harness ->
        h = harness
        val store = makeStore {
            voiceCall {
                copy(start = { _, _ ->
                    throw VoiceAudioError.engineRejected(operation = "Enabling voice processing", reason = "required condition is false")
                })
            }
        }

        store.send(Action.Start)
        store.receive(Action.CallEnded::class) {
            it.withPhase(State.Phase.Failed("Couldn’t start call audio. Try again in a moment."))
        }
        store.finish()
    }

    @Test fun hangUpRequestEndsAfterGoodbyePlays() = voiceTest { harness ->
        h = harness
        val ended = LockIsolated(0)
        val (store, events) = startLiveCall {
            voiceCall { copy(end = { ended.increment() }) }
            this[Clock] = TestClock()
        }
        events.yield(VoiceCallEvent.FunctionCall(callID = "call_end", name = "end_voice_session", arguments = VoiceFeatureTestSupport.arguments()))
        store.receive(Action.CallEvent::class) { it.withPhase(State.Phase.EndingAfterGoodbye(spoken = false)) }
        // The response that carried the hang-up draining is not the goodbye.
        events.yield(VoiceCallEvent.AssistantPlaybackFinished)
        store.receive(Action.CallEvent::class)
        assertEquals(State.Phase.EndingAfterGoodbye(spoken = false), store.state.phase)
        events.yield(VoiceCallEvent.AssistantDone(itemID = "item_bye", text = "Bye for now."))
        store.receive(Action.CallEvent::class) { it.withPhase(State.Phase.EndingAfterGoodbye(spoken = true)) }
        events.yield(VoiceCallEvent.AssistantPlaybackFinished)
        store.receive(Action.CallEvent::class)
        store.receive(Action.EndTapped::class) { it.withPhase(State.Phase.Ended) }
        store.receiveEnded()
        settle()
        assertEquals(1, ended.value)
        store.finish()
    }
}
