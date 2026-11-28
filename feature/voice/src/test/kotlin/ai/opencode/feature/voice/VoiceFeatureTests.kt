package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.arguments
import ai.opencode.feature.voice.VoiceFeatureTestSupport.call
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.date
import ai.opencode.feature.voice.VoiceFeatureTestSupport.delegator
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.response
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.settle
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The tests whose store is `VoiceFeature`. */
class VoiceFeatureTests {
    private lateinit var h: Harness

    private fun record(state: State, role: VoiceTranscriptTail.Role, text: String, id: String? = null): State =
        state.copy(tail = state.tail.record(role, text, id).tail)

    /**
     * "Yes, let it run the tests, pick Postgres, and after that also update
     * the docs. Actually send that now. Thanks, bye." Requests are answered,
     * a queued message is switched to steering, and the call ends once the
     * goodbye has played.
     */
    @Test fun delegatorAnswersRequestsSteersQueuedWorkAndEndsTheCall() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Yes let it run the tests, pick Postgres, then also update the docs, actually send that now. Bye", id = "u1")
        val replies = LockIsolated(listOf<String>())
        val deliveries = LockIsolated(listOf<String>())
        val listed = LockIsolated("")
        val form = Json { ignoreUnknownKeys = true }.decodeFromString<OpenCodeFormRequest>(
            """
            {"id":"form_1","sessionID":"ses_voice","title":"Which database?","fields":[
              {"key":"db","type":"string","title":"Database","options":[{"value":"pg","label":"Postgres"},{"value":"sqlite","label":"SQLite"}]}]}
            """.trimIndent(),
        )
        val store = h.store(voice) {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                val outputs = input.filterIsInstance<VoiceDelegator.InputItem.ToolOutput>()
                val last = outputs.lastOrNull() ?: return@delegator response("r1", listOf(call("t1", "list_requests", "{}")))
                when (last.callID) {
                    "t1" -> {
                        listed.setValue(last.output)
                        response(
                            "r2",
                            listOf(
                                call("t2", "reply_permission", """{"request_id":"perm_1","decision":"once"}"""),
                                call("t3", "answer_question", """{"form_id":"form_1","answers":{"db":"pg"}}"""),
                                call("t4", "send_message", """{"text":"Update the docs","delivery":"queue"}"""),
                            ),
                        )
                    }
                    "t4" -> response(
                        "r3",
                        listOf(
                            call("t5", "steer_queued", """{"message_id":"msg_docs"}"""),
                            call("t6", "end_call", "{}"),
                        ),
                    )
                    else -> response("r4", text = "Done. Talk soon!")
                }
            }
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, ids, _ ->
                    assertEquals(listOf(session.id), ids)
                    OpenCodePendingRequests(
                        permissions = listOf(OpenCodePermissionRequest(id = "perm_1", sessionID = session.id, action = "bash", resources = listOf("swift test"), save = null)),
                        forms = listOf(form),
                    )
                },
                replyPermission = { _, _, id, reply, _ -> replies.append("$id:${reply.name.lowercase()}") },
                replyForm = { _, _, id, answers -> replies.append("$id:${answers["db"] == OpenCodeFormValue.Str("pg")}") },
                sendDeliveredPrompt = { _, _, _, delivery ->
                    deliveries.append("send:${delivery?.rawValue ?: "-"}")
                    "msg_docs"
                },
                setPendingInputDelivery = { _, _, id, delivery -> deliveries.append("$id:${delivery.rawValue}") },
                moveToBackground = { _, id -> deliveries.append("background:$id") },
            )
            voiceCall { copy(completeFunction = { _, _ -> }, end = {}) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)
        // It read the requests before acting on them.
        assertTrue(listed.value.contains("perm_1") && listed.value.contains("swift test") && listed.value.contains("Postgres"))
        assertEquals(listOf("perm_1:once", "form_1:true"), replies.value)
        // Queuing leaves the running work be; steering moves it to the background first.
        assertEquals(listOf("send:queue", "background:${session.id}", "msg_docs:steer"), deliveries.value)
        // The goodbye plays, then the call ends.
        assertEquals(State.Phase.EndingAfterGoodbye(spoken = false), store.state.phase)
        store.send(Action.CallEvent(VoiceCallEvent.AssistantDone(itemID = "a1", text = "Done. Talk soon!")))
        store.send(Action.CallEvent(VoiceCallEvent.AssistantPlaybackFinished))
        store.receive(Action.EndTapped::class)
        assertTrue(store.state.phase.isTerminal)
    }

    /**
     * GPT-Live delegated again with nothing new from the user. The delegator
     * is not handed the voice agent's own words as the user's request, and the
     * voice agent hears why.
     */
    @Test fun aDelegationWithNoNewUserWordsFailsInsteadOfMakingUpARequest() = voiceTest { harness ->
        h = harness
        val voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        val requests = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<Pair<String, String>>())
        val store = h.store(voice) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                (input.firstOrNull() as? VoiceDelegator.InputItem.User)?.let { requests.append(it.text) }
                response("r${requests.value.size}", text = "The upload tests are running.")
            }
            voiceCall { copy(completeFunction = { callID, output -> completed.append(callID to output) }) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Run the upload tests", final = true)))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments("Running the upload tests."))))
        store.receive(Action.DelegatorFinished::class)
        store.send(Action.CallEvent(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Running the upload tests. They're running now.")))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d2", name = "delegate", arguments = arguments("Checking on the upload tests."))))
        settle()

        assertEquals(
            listOf(
                """
                The call is attached to session "Voice lab" (id ses_voice, project project, idle).
                ${VoiceScreenVisibility.notLooking.unseenNote}
                Recent voice conversation:
                user: Run the upload tests

                The user's request: Run the upload tests
                """.trimIndent(),
            ),
            requests.value,
        )
        assertEquals(listOf("d1", "d2"), completed.value.map { it.first })
        assertEquals(
            listOf("[COMPLETE] The upload tests are running.", VoiceHandoff.failure(VoiceHandoff.nothingNewToSend)),
            completed.value.map { it.second },
        )
        // Nothing for the voice agent to repeat ("it wasn't a new request").
        assertFalse(VoiceHandoff.nothingNewToSend.contains("new request"))
        assertTrue(VoiceHandoff.nothingNewToSend.contains("Don't mention this to the user"))
        assertTrue(store.state.delegatorWork.isEmpty())
        store.finish()
    }

    private fun delegatorStore(
        voice: State,
        requests: LockIsolated<List<String>>,
        completed: LockIsolated<List<String>>,
    ): TestStore<State, Action> {
        val store = h.store(voice) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                (input.firstOrNull() as? VoiceDelegator.InputItem.User)?.let { requests.append(it.text) }
                response("r${requests.value.size}", text = "Started it.")
            }
            voiceCall { copy(completeFunction = { _, output -> completed.append(output) }) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")
        return store
    }

    private fun delegatorCall(on: OpenCodeSession): State =
        h.state { State(server, on) }.live().copy(usesDelegator = true, connectedAt = date(0))

    /** The conversation is the call's, so moving to another session doesn't swap it. */
    @Test fun wordsSpokenBeforeASwitchDelegateAfterIt() = voiceTest { harness ->
        h = harness
        val ios = VoiceFeatureTestSupport.session("ses_switch_ios", "OpenCode iOS", "/Users/kit/ios")
        val home = VoiceFeatureTestSupport.session("ses_switch_home", "Home", "/Users/kit/home")
        var voice = delegatorCall(on = ios)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Research multimodal models for the app", id = "u1")
        voice = h.state { voice.retarget(home, reason = "you opened it", endedAt = date(95)) }
        val requests = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val store = delegatorStore(voice, requests, completed)

        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments("Checking that for us."))))
        store.receive(Action.DelegatorFinished::class)
        settle()
        assertEquals(1, requests.value.size)
        val request = requests.value.firstOrNull() ?: ""
        assertTrue(request.contains("The user's request: Research multimodal models for the app"))
        // The delegator hears where the screen just came from.
        assertTrue(request.contains("The screen moved here from session \"OpenCode iOS\" (id ses_switch_ios) 5 s ago"))
        assertEquals(listOf("[COMPLETE] Started it."), completed.value)
        store.finish()
    }

    @Test fun wordsSpokenBeforeMovingToADraftDelegateAfterIt() = voiceTest { harness ->
        h = harness
        val ios = VoiceFeatureTestSupport.session("ses_switch_draft", "OpenCode iOS", "/Users/kit/ios")
        var voice = delegatorCall(on = ios)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Start a session that profiles the scroll view", id = "u1")
        voice = h.state { voice.retargetToDraft(directory = "/Users/kit/ios", model = null, reason = "you started a new session") }
        assertTrue(voice.isOnDraft)
        val requests = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val store = delegatorStore(voice, requests, completed)

        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)
        assertEquals(true, requests.value.firstOrNull()?.contains("The user's request: Start a session that profiles the scroll view"))
        store.finish()
    }

    /**
     * The voice agent delegating again on its own, with nothing new from the
     * user, is still refused after a move.
     */
    @Test fun aSelfInitiatedDelegationAfterASwitchIsRefused() = voiceTest { harness ->
        h = harness
        val a = VoiceFeatureTestSupport.session("ses_self_a", "Upload", "/Users/kit/a")
        val b = VoiceFeatureTestSupport.session("ses_self_b", "Docs", "/Users/kit/b")
        var voice = delegatorCall(on = a)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Check the build", id = "u1")
        val captured = voice.tail.capture()
        voice = voice.copy(tail = captured.tail.accept(captured.capture))
        voice = h.state { voice.retarget(b) }
        val requests = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val store = delegatorStore(voice, requests, completed)

        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments("Checking the build."))))
        settle()
        assertTrue(requests.value.isEmpty())
        assertEquals(listOf(VoiceHandoff.failure(VoiceHandoff.nothingNewToSend)), completed.value)
        store.finish()
    }

    /** The user asking again for the same thing is a request, not a repeat. */
    @Test fun aRepeatedRequestDelegatesAgain() = voiceTest { harness ->
        h = harness
        val voice = delegatorCall(on = session).copy(tail = VoiceTranscriptTail())
        val requests = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val store = delegatorStore(voice, requests, completed)

        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u1", text = "Run the upload tests", final = true)))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)
        store.send(Action.CallEvent(VoiceCallEvent.AssistantDone(itemID = "a1", text = "Running the upload tests.")))
        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u2", text = "Run the upload tests", final = true)))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d2", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)
        settle()
        assertEquals(2, requests.value.size)
        assertTrue(requests.value.all { it.contains("The user's request: Run the upload tests") })
        assertEquals(listOf("[COMPLETE] Started it.", "[COMPLETE] Started it."), completed.value)
        store.finish()
    }

    /**
     * A switch keeps the call's conversation, and the voice model is told
     * only about the new session: never this call's own lines as "previous".
     */
    @Test fun aSwitchKeepsTheConversationWithoutReplayingIt() = voiceTest { harness ->
        h = harness
        val a = VoiceFeatureTestSupport.session("ses_keep_a", "Upload", "/Users/kit/a")
        val b = VoiceFeatureTestSupport.session("ses_keep_b", "Docs", "/Users/kit/b")
        // An earlier call on B.
        h.state {
            shared(VoiceSharedKeys.voiceTranscripts).update {
                it + ("${server.id}:${b.id}" to VoiceTranscriptRecord(endedAt = date(-500), tail = listOf("user: Old docs question"), log = emptyList()))
            }
        }
        var voice = delegatorCall(on = a).copy(connectedAt = date(0))
        voice = record(voice, VoiceTranscriptTail.Role.user, "Check the upload test", id = "u1")
        voice = record(voice, VoiceTranscriptTail.Role.assistant, "Checking.")
        voice = voice.copy(
            captions = listOf(State.Caption(id = "user-u1", role = VoiceTranscriptTail.Role.user, text = "Check the upload test")),
            liveAssistantText = "Checking",
            liveAssistantItemID = "a1",
        )

        voice = h.state { voice.retarget(b, endedAt = date(10)) }
        assertEquals(listOf("user: Check the upload test", "assistant: Checking."), voice.tail.lines)
        assertEquals(1, voice.captions.size)
        assertTrue("A reply being spoken carries on", voice.liveAssistantText == "Checking" && voice.liveAssistantItemID == "a1")
        // Neither B's earlier call nor this call's lines are replayed on a switch.
        assertTrue(voice.instructionsContext.previousTail.isEmpty())
        val delta = VoiceInstructions.buildRetarget(voice.instructionsContext, engine = VoiceEngine.gptLive)
        assertTrue(delta.contains("\"Docs\""))
        assertFalse(delta.contains("Old docs question"))
        assertFalse(delta.contains("Check the upload test"))

        // Back to A, which this call already wrote to: nothing is replayed.
        voice = h.state { voice.retarget(a, endedAt = date(20)) }
        assertTrue(voice.instructionsContext.previousTail.isEmpty())
        val back = VoiceInstructions.buildRetarget(voice.instructionsContext, engine = VoiceEngine.gptLive)
        assertFalse(back.contains("Previous voice call tail"))
        assertEquals(listOf("user: Check the upload test", "assistant: Checking."), voice.tail.lines.takeLast(2))
        // Each session it visited keeps only user turns for the next call's continuity.
        val transcripts = h.state { shared(VoiceSharedKeys.voiceTranscripts).value }
        assertEquals(listOf("user: Check the upload test"), transcripts["${server.id}:${b.id}"]?.tail)
    }

    @Test fun aTopicFromOneSessionFollowsTheCallAndIsNeverReplayed() = voiceTest { harness ->
        h = harness
        val home = VoiceFeatureTestSupport.session("ses_topic_home", "Home", "/Users/kit/home")
        val ios = VoiceFeatureTestSupport.session("ses_topic_ios", "OpenCode iOS", "/Users/kit/ios")
        var voice = delegatorCall(on = home)
        voice = record(voice, VoiceTranscriptTail.Role.user, "Which 1Password item has the Mac rental checkout password?", id = "u1")
        voice = record(voice, VoiceTranscriptTail.Role.assistant, "The rental login is in the Personal vault.")
        val captured = voice.tail.capture()
        voice = voice.copy(tail = captured.tail.accept(captured.capture))
        voice = h.state { voice.retarget(ios, endedAt = date(50)) }
        // Nothing from this call is handed back to the voice model as history.
        assertFalse(VoiceInstructions.buildRetarget(voice.instructionsContext, engine = VoiceEngine.gptLive).contains("1Password"))

        val requests = LockIsolated(listOf<String>())
        val completed = LockIsolated(listOf<String>())
        val store = delegatorStore(voice, requests, completed)
        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u2", text = "What were we saying about that checkout?", final = true)))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)
        // The topic from Home is still the conversation on the other session.
        assertEquals(true, requests.value.firstOrNull()?.contains("user: Which 1Password item has the Mac rental checkout password?"))
        assertEquals(true, requests.value.firstOrNull()?.contains("assistant: The rental login is in the Personal vault."))

        // Back on Home: no replay of what this call already said.
        val back = h.state { store.state.retarget(home, endedAt = date(60)) }
        assertTrue(back.instructionsContext.previousTail.isEmpty())
        assertFalse(VoiceInstructions.buildRetarget(back.instructionsContext, engine = VoiceEngine.gptLive).contains("1Password"))
        assertEquals(1, back.tail.lines.count { it.contains("1Password") })
        store.finish()
    }

    /**
     * Build 685, 22:56:57Z (14:56:57 PT): opening HEX mid-call replayed a stale
     * `VoiceTranscriptRecord.tail` containing Voice's own unbacked lines ("And
     * that text to Olive went through, delivered"), causing Voice to answer
     * "No. Just to Olive." nine seconds later without delegating.
     */
    @Test fun oliveHexRetargetNeverReplaysPastVoiceClaimsOrPreviousCallTail() = voiceTest { harness ->
        h = harness
        val ios = OpenCodeSession(id = "ses_olive_ios", serverTitle = "OPENCODE IOS", projectID = "p", directory = "/tmp/ios", updatedAt = VoiceFeatureTestSupport.distantPast)
        val hex = OpenCodeSession(id = "ses_olive_hex", serverTitle = "HEX", projectID = "p", directory = "/tmp/hex", updatedAt = VoiceFeatureTestSupport.distantPast)

        val staleEndedAt = Instant.parse("2026-11-01T22:00:00Z")
        val connectedAt = Instant.parse("2026-11-01T22:50:00Z")
        val retargetAt = Instant.parse("2026-11-01T22:56:57Z")

        val staleOliveTail = listOf(
            "user: Did you check notifications?",
            "assistant: Still waiting on it. I don't have an answer yet on the text to your wife or the notifications.",
            "assistant: Quick recap, we've got that text still unconfirmed, and your feedback already sent to the OPENCODE IOS session.",
            "assistant: And that text to Olive went through,",
            "assistant: delivered. I'm watching for her reply, and if she answers, I'll send it to you. That's the update.",
            "Voice: No. Just to Olive.",
        )

        h.state {
            shared(VoiceSharedKeys.voiceTranscripts).update {
                it + ("${server.id}:${hex.id}" to VoiceTranscriptRecord(endedAt = staleEndedAt, tail = staleOliveTail, log = emptyList()))
            }
        }

        // 1. Mid-call retarget to HEX at 22:56:57Z (14:56:57 PT): neither the delta nor full instructions replay the previous call's raw lines.
        var voice = delegatorCall(on = ios).copy(connectedAt = connectedAt)
        voice = h.state { voice.retarget(hex, reason = "you opened it", endedAt = retargetAt) }
        assertTrue(voice.instructionsContext.previousTail.isEmpty())

        val contextWithRecent = voice.instructionsContext.copy(recentMessages = listOf("assistant: HEX build is green."))
        val retargetDelta = VoiceInstructions.buildRetarget(contextWithRecent, engine = VoiceEngine.gptLive)
        val retargetFull = VoiceInstructions.build(contextWithRecent, engine = VoiceEngine.gptLive)
        assertTrue(retargetDelta.contains("\"HEX\""))
        assertTrue(retargetDelta.contains("HEX build is green."))
        assertFalse(retargetDelta.contains("Olive"))
        assertFalse(retargetDelta.contains("went through"))
        assertFalse(retargetDelta.contains("Did you check notifications?"))
        assertFalse(retargetDelta.contains("Previous voice call tail"))
        assertFalse(retargetFull.contains("Olive"))
        assertFalse(retargetFull.contains("# Previous Voice Call"))

        // Even if a caller passes raw `previousTail` directly to `buildRetarget`, switching sessions never replays it.
        val rawContext = contextWithRecent.copy(previousTail = staleOliveTail)
        assertFalse(VoiceInstructions.buildRetarget(rawContext, engine = VoiceEngine.gptLive).contains("Olive"))
        assertFalse(VoiceInstructions.buildRetarget(rawContext, engine = VoiceEngine.realtime).contains("Olive"))

        // 2. Starting a brand-new call directly on HEX strips Voice's own `assistant:` / `Voice:` lines from continuity.
        val freshOnHex = h.state { State(server, hex) }
        assertEquals(listOf("user: Did you check notifications?"), freshOnHex.previousCallTail)
        val startPrompt = VoiceInstructions.build(freshOnHex.instructionsContext, engine = VoiceEngine.gptLive)
        assertTrue(startPrompt.contains("# Previous Voice Call"))
        assertTrue(startPrompt.contains("silent memory"))
        assertTrue(startPrompt.contains("never completed outcomes or facts"))
        assertTrue(startPrompt.contains("user: Did you check notifications?"))
        assertFalse(startPrompt.contains("Olive"))
        assertFalse(startPrompt.contains("went through"))

        // 3. Saving a transcript record strips `assistant:` and `Voice:` lines from `tail` while keeping `log`.
        val saved = VoiceTranscriptRecord(endedAt = staleEndedAt, tail = emptyList(), log = emptyList())
            .appending(tail = staleOliveTail, log = staleOliveTail, endedAt = retargetAt)
        assertEquals(listOf("user: Did you check notifications?"), saved.tail)
        assertEquals(staleOliveTail.size, saved.log.size)
    }
}
