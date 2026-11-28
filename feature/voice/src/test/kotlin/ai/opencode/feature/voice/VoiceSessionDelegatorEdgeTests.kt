package ai.opencode.feature.voice

import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.admittingFirstTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.hearing
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.hungUp
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.liveDelegatorState
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.mockServerID
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.preserveSettings
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.server
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.settlingFirstTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.startingTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.store
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDirectoryListing
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeRunMessagesPage
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.PromptContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class VoiceSessionDelegatorEdgeTests {
    private fun functionCall(callID: String) = VoiceFeature.Action.CallEvent(
        VoiceCallEvent.FunctionCall(callID = callID, name = "delegate", arguments = JsonObject(emptyMap())),
    )

    private fun userTranscript(itemID: String, text: String) =
        VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = itemID, text = text, final = true))

    private fun obj(vararg pairs: Pair<String, OpenCodeJSONValue>) = OpenCodeJSONValue.Object(mapOf(*pairs))

    private fun str(value: String) = OpenCodeJSONValue.Str(value)

    /** A state whose single admitted turn waits on [promptID] in `ses_voice`. */
    private fun admittedTurnState(shared: VoiceTestShared, callID: String, promptID: String): VoiceFeature.State {
        val base = liveDelegatorState(shared)
        val captured = base.tail.capture()
        return base.copy(
            tail = captured.tail,
            sessionDelegator = base.sessionDelegator.copy(
                activeSessionID = "ses_voice",
                turns = listOf(
                    VoiceSessionDelegator.State.Turn(
                        callID = callID,
                        capture = captured.capture,
                        sessionID = "ses_voice",
                        title = "Voice",
                        promptID = promptID,
                        saidTurn = 0,
                    ),
                ),
            ),
            delegatorWork = listOf(callID),
        )
    }

    // MARK: - Review Regressions (Exhaustive TestStore + Cancellation)

    @Test fun reconnectSkipsOldCallToolsWhenAnchorMissingPagesToVerifiedAnchorAndCancelsOnHangupExhaustively() = runBlocking {
        val shared = preserveSettings()

        // 1. Anchor missing in REST page (e.g. prompt not persisted yet): must NOT replay earlier call's tools.
        val missingAnchorState = admittedTurnState(shared, callID = "del_missing", promptID = "msg_not_in_rest_yet")
        val missingEffects = detachedScope(coroutineContext)
        val missingAnchorStore = store(shared, missingAnchorState, missingEffects, now = 0) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, _, _ ->
                    OpenCodeRunMessagesPage(
                        messages = listOf(
                            OpenCodeRunMessage(id = "msg_earlier_call_user", type = "user", text = "Bye"),
                            OpenCodeRunMessage(
                                id = "msg_earlier_call_reply",
                                type = "assistant",
                                text = "Goodbye",
                                toolCalls = listOf(
                                    OpenCodeRunMessage.ToolCall(id = "t_prev_open", name = "ios_voice_open_session", input = obj("session_id" to str("ses_old"))),
                                    OpenCodeRunMessage.ToolCall(id = "t_prev_pin", name = "ios_voice_pin_session", input = obj("session_id" to str("ses_old"), "pinned" to OpenCodeJSONValue.Bool(true))),
                                    OpenCodeRunMessage.ToolCall(id = "t_prev_end", name = "ios_voice_end_call", input = obj()),
                                ),
                            ),
                        ),
                        next = null,
                    )
                },
            )
        }

        missingAnchorStore.send(VoiceFeature.Action.SessionDelegatorStreamReconnected) {
            it.copy(sessionDelegator = it.sessionDelegator.copy(turns = it.sessionDelegator.turns.map { turn -> turn.copy(reconnectedMidTurn = true) }))
        }
        missingAnchorStore.finish(timeout = 5.seconds)
        assertEquals(VoiceFeature.State.Phase.Live, missingAnchorStore.state.phase)
        assertTrue(missingAnchorStore.state.sessionDelegator.appliedToolCallIDs.isEmpty())
        missingEffects.skipInFlightEffects()

        // 2. Anchor on older page (`next` cursor): reconciles only tools after verified anchor, then cancels in-flight REST read on hang-up.
        val pagedState = admittedTurnState(shared, callID = "del_paged", promptID = "msg_current_anchor")
        val openedTarget = OpenCodeSession(
            id = "ses_target",
            serverTitle = "Target",
            projectID = "proj_1",
            directory = "/Users/alex/target",
            updatedAt = Instant.ofEpochSecond(200),
        )
        val stallOnReconnect = Locked(false)
        val reconcileCancelled = Locked(false)
        val announced = Locked(listOf<String>())
        val endedCalls = Locked(0)

        val pagedEffects = detachedScope(coroutineContext)
        val pagedStore = store(shared, pagedState, pagedEffects, now = 6_000) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, sessionID, cursor ->
                    assertEquals("ses_voice", sessionID)
                    if (stallOnReconnect.value) {
                        try {
                            awaitCancellation()
                        } finally {
                            reconcileCancelled.set(true)
                        }
                    }
                    if (cursor == null) {
                        OpenCodeRunMessagesPage(
                            messages = listOf(
                                OpenCodeRunMessage(
                                    id = "msg_newest_page",
                                    type = "assistant",
                                    text = "",
                                    toolCalls = listOf(
                                        OpenCodeRunMessage.ToolCall(id = "t_page1_open", name = "ios_voice_open_session", input = obj("session_id" to str(openedTarget.id))),
                                    ),
                                ),
                            ),
                            next = "cur_page_2",
                        )
                    } else {
                        assertEquals("cur_page_2", cursor)
                        OpenCodeRunMessagesPage(
                            messages = listOf(
                                OpenCodeRunMessage(
                                    id = "msg_old_call",
                                    type = "assistant",
                                    text = "Old goodbye",
                                    toolCalls = listOf(
                                        OpenCodeRunMessage.ToolCall(id = "t_old_end", name = "ios_voice_end_call", input = obj()),
                                    ),
                                ),
                                OpenCodeRunMessage(id = "msg_current_anchor", type = "user", text = "Current prompt"),
                                OpenCodeRunMessage(
                                    id = "msg_after_anchor_page2",
                                    type = "assistant",
                                    text = "",
                                    toolCalls = listOf(
                                        OpenCodeRunMessage.ToolCall(id = "t_page2_say", name = "ios_voice_say", input = obj("text" to str("Checking status"))),
                                    ),
                                ),
                            ),
                            next = null,
                        )
                    }
                },
                getSession = { _, _ -> openedTarget },
            )
            this[VoiceCallClient] = testVoiceCall(end = { endedCalls.update { it + 1 } })
                .copy(announce = { _, _, text -> announced.update { it + text } })
        }

        val expectedPostAnchorCalls = listOf(
            OpenCodeRunMessage.ToolCall(id = "t_page2_say", name = "ios_voice_say", input = obj("text" to str("Checking status"))),
            OpenCodeRunMessage.ToolCall(id = "t_page1_open", name = "ios_voice_open_session", input = obj("session_id" to str(openedTarget.id))),
        )
        pagedStore.send(VoiceFeature.Action.SessionDelegatorStreamReconnected) {
            it.copy(sessionDelegator = it.sessionDelegator.copy(turns = it.sessionDelegator.turns.map { turn -> turn.copy(reconnectedMidTurn = true) }))
        }
        pagedStore.receive(VoiceFeature.Action.SessionDelegatorReconciledTools(expectedPostAnchorCalls), timeout = 5.seconds) {
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    appliedToolCallIDs = setOf("t_page2_say", "t_page1_open"),
                    turns = it.sessionDelegator.turns.map { turn -> turn.copy(didSayInterim = true) },
                ),
            )
        }
        pagedStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.OpenSessionRequested(toolID = "t_page1_open", targetID = openedTarget.id)))
        eventually { announced.value.size == 1 }
        assertEquals(1, announced.value.size)

        // Hang up during a stalled reconnect REST fetch cancels `CancelID.callScope` cleanly.
        stallOnReconnect.set(true)
        pagedStore.send(VoiceFeature.Action.SessionDelegatorStreamReconnected)
        pagedStore.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        pagedStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        eventually { reconcileCancelled.value }
        assertTrue(reconcileCancelled.value)
        assertEquals(1, endedCalls.value)
        pagedStore.finish()
        pagedEffects.skipInFlightEffects()
    }

    @Test fun secondSameSessionDeltaWaitsForStalledFirstAdmissionAndCancelsCleanlyExhaustively() = runBlocking {
        val shared = preserveSettings()
        val picked = VoiceDelegatorChoice.Picked(
            serverID = VoiceDelegatorWorkspace.serverID(server),
            sessionID = "ses_home",
            title = "HOME",
            projectName = "home",
        )
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.PickedSession(picked))
        val state = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "First request.", "u1")

        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val waitGate1 = CompletableDeferred<String>()
        val waitGate2 = CompletableDeferred<String>()
        val submittedPrompts = Locked(listOf<Pair<String, String>>())
        val completed = Locked(listOf<Pair<String, String>>())
        val endedCalls = Locked(0)
        val msgCounter = Locked(0)
        val now = Instant.ofEpochSecond(7_000)

        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 7_000) {
            this[ServerIDClient] = mockServerID(
                session = { "ses_unused" },
                message = {
                    val n = msgCounter.withValue { (it + 1) to (it + 1) }
                    "msg_$n"
                },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, sessionID, text, _ ->
                    assertEquals("ses_home", sessionID)
                    val mid = PromptContext.messageID.current() ?: "msg_fallback"
                    submittedPrompts.update { it + (mid to text) }
                    (if (mid == "msg_1") gate1 else gate2).await()
                    mid
                },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, sessionID, promptID ->
                assertEquals("ses_home", sessionID)
                (if (promptID == "msg_1") waitGate1 else waitGate2).await()
            })
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { id, out -> completed.update { it + (id to out) } },
                end = { endedCalls.update { it + 1 } },
            )
        }

        // Turn 1 starts admitting and stalls in flight (`promptID == nil`, `admitting == true`).
        store.send(functionCall("del_1")) {
            it.startingTurn("del_1", sessionID = "ses_home", admitting = true, saidTurn = 1, activeSessionID = "ses_home")
        }

        // User speaks a second delta while Turn 1 is still admitting: Turn 2 queues behind Turn 1 (`admitting == false`).
        store.send(userTranscript("u2", "Second request.")) { it.hearing("u2", "Second request.") }
        store.send(functionCall("del_2")) {
            it.startingTurn("del_2", sessionID = "ses_home", admitting = false, saidTurn = 3)
        }
        assertTrue("Second delta must wait for the first turn's admission", submittedPrompts.value.size <= 1)

        // Release Turn 1's stalled send: Turn 1 admits, accepts its capture, and immediately kicks off Turn 2's admission.
        val expectedContext = """[Phone context] Call on session "Session View" (ses_view) · """ + VoiceScreenVisibility.notLooking.unseenNote!!
        val expectedPrompt1 = VoiceSessionDelegator.formatPrompt(
            deltaLines = listOf("User: First request."),
            promisedClaim = null,
            contextLine = expectedContext,
            includeActiveCallHeader = true,
        )
        val admission1 = VoiceSessionDelegator.Admission(
            sessionID = "ses_home",
            title = "HOME",
            promptID = "msg_1",
            promptText = expectedPrompt1,
            explained = true,
            contextLine = expectedContext,
        )
        gate1.complete(Unit)

        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID = "ses_home")), timeout = 5.seconds)
        store.receive(VoiceFeature.Action.SessionDelegatorAdmitted(callID = "del_1", admission = admission1), timeout = 5.seconds) {
            val admitted = it.admittingFirstTurn("ses_home", "HOME", "msg_1", saidTurn = 1, contextLine = expectedContext, now = now)
            admitted.copy(
                sessionDelegator = admitted.sessionDelegator.copy(
                    turns = admitted.sessionDelegator.turns.mapIndexed { index, turn -> if (index == 1) turn.copy(admitting = true) else turn },
                ),
            )
        }

        // Now settle Turn 1 (`msg_1`) WHILE Turn 2 (`del_2`) is still admitting (`promptID == nil`):
        // Turn 2 must NOT be dropped from `turns`!
        waitGate1.complete("First reply.")
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_1", reply = "First reply.")), timeout = 5.seconds) {
            it.settlingFirstTurn("ses_home", "msg_1", "First reply.")
        }
        assertEquals(listOf("del_2"), store.state.sessionDelegator.turns.map { it.callID })
        assertTrue(store.state.sessionDelegator.turns[0].admitting)

        // Release Turn 2's send: Turn 2 admits with ONLY its delta (no repeated header/context) and settles cleanly.
        val expectedPrompt2 = VoiceSessionDelegator.formatPrompt(
            deltaLines = listOf("User: Second request."),
            promisedClaim = null,
            contextLine = null,
            includeActiveCallHeader = false,
        )
        val admission2 = VoiceSessionDelegator.Admission(
            sessionID = "ses_home",
            title = "HOME",
            promptID = "msg_2",
            promptText = expectedPrompt2,
            explained = false,
            contextLine = expectedContext,
        )
        gate2.complete(Unit)

        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID = "ses_home")), timeout = 5.seconds)
        store.receive(VoiceFeature.Action.SessionDelegatorAdmitted(callID = "del_2", admission = admission2), timeout = 5.seconds) {
            it.admittingFirstTurn("ses_home", "HOME", "msg_2", saidTurn = 3, contextLine = null, now = now)
        }

        waitGate2.complete("Second reply.")
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_2", reply = "Second reply.")), timeout = 5.seconds) {
            it.settlingFirstTurn("ses_home", "msg_2", "Second reply.")
        }
        store.finish(timeout = 5.seconds)
        assertEquals(listOf("del_1", "del_2"), completed.value.map { it.first })
        assertEquals(listOf("msg_1", "msg_2"), submittedPrompts.value.map { it.first })
        effects.skipInFlightEffects()

        // Also verify hang-up cancellation while Turn A is stalled and Turn B is queued releases BOTH captures.
        val cancelState = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Alpha.", "c1")
        val stallStream = CompletableDeferred<Unit>()

        val cancelEffects = detachedScope(coroutineContext)
        val cancelStore = store(shared, cancelState, cancelEffects, now = 7_000) {
            this[ServerIDClient] = mockServerID(session = { "ses_unused" }, message = { "msg_c" })
            this[VoiceSessionDelegator.Client] = VoiceSessionDelegator.Client(submit = {
                stallStream.await()
                throw OpenCodeClientError("Cancelled")
            })
            this[VoiceCallClient] = testVoiceCall(end = { endedCalls.update { it + 1 } })
        }

        cancelStore.send(functionCall("del_c1")) {
            it.startingTurn("del_c1", sessionID = "ses_home", admitting = true, saidTurn = 1, activeSessionID = "ses_home")
        }
        cancelStore.send(userTranscript("c2", "Beta.")) { it.hearing("c2", "Beta.") }
        cancelStore.send(functionCall("del_c2")) {
            it.startingTurn("del_c2", sessionID = "ses_home", admitting = false, saidTurn = 3)
        }
        cancelStore.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        cancelStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        assertEquals("Alpha. Beta.", cancelStore.state.tail.capture().capture.userText)
        stallStream.complete(Unit)
        cancelStore.finish()
        cancelEffects.skipInFlightEffects()
    }

    @Test fun automaticSessionCreationCancelsUnderlyingTaskOnHangupAndCachesSucceededCreateExhaustively() = runBlocking {
        val shared = preserveSettings()
        val sharedDelegator = VoiceSessionDelegator.Client.live()
        val serverKey = VoiceDelegatorWorkspace.serverID(server)
        val now = Instant.ofEpochSecond(8_000)
        val defaultModel = OpenCodeModelRef(id = "claude-sonnet-5-5", providerID = "opencode")
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.AutomaticSession)

        // 1. Hang-up while `searchSessions` is in flight cancels the gate's underlying Task promptly without creating a session.
        val preCreateState = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Stop early.", "u_pre")

        val searchStarted = CompletableDeferred<Unit>()
        val searchCancelled = Locked(false)
        val createCalls = Locked(0)
        val endedCalls = Locked(0)

        val preCreateEffects = detachedScope(coroutineContext)
        val preCreateStore = store(shared, preCreateState, preCreateEffects, now = 8_000) {
            this[VoiceSessionDelegator.Client] = sharedDelegator
            this[ServerIDClient] = mockServerID(session = { "ses_never" }, message = { "msg_pre" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                searchSessions = { _, _, _ ->
                    searchStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        searchCancelled.set(true)
                    }
                },
                createSession = { _, dir, id ->
                    createCalls.update { it + 1 }
                    OpenCodeSession(id = id ?: "ses_never", serverTitle = "Voice", projectID = "p", directory = dir, updatedAt = now)
                },
            )
            this[VoiceCallClient] = testVoiceCall(end = { endedCalls.update { it + 1 } })
        }

        preCreateStore.send(functionCall("del_pre")) {
            it.startingTurn("del_pre", sessionID = null, admitting = true, saidTurn = 1, provisionalSessionID = "ses_never")
        }
        withTimeout(5.seconds) { searchStarted.await() }

        preCreateStore.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        preCreateStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        eventually { searchCancelled.value }
        assertTrue("Last waiter cancelling must cancel the gate's underlying Task", searchCancelled.value)
        assertEquals("Canceled Automatic creation must not proceed to createSession", 0, createCalls.value)
        preCreateStore.finish()
        preCreateEffects.skipInFlightEffects()

        // 2. Hang-up AFTER `createSession` succeeded (while `renameSession` is suspended) cancels follow-up steps
        //    immediately AND caches the created session so the next call reuses it instead of creating a second "Voice" session.
        val call1State = liveDelegatorState(shared)
            .copy(pendingModel = defaultModel)
            .recording(VoiceTranscriptTail.Role.user, "Hello Voice.", "u_c1")

        val renameStarted = CompletableDeferred<Unit>()
        val renameCancelled = Locked(false)
        val stallRename = Locked(true)
        val renameCalls = Locked(listOf<Pair<String, String>>())
        val modelCalls = Locked(listOf<Pair<String, OpenCodeModelRef>>())
        val sendCalls = Locked(listOf<String>())
        val completed = Locked(listOf<Pair<String, String>>())

        val createdVoice = OpenCodeSession(
            id = "ses_auto_cached",
            serverTitle = "Voice",
            projectID = "proj_ws",
            directory = "/Users/alex/workspace",
            updatedAt = now,
        )

        val call1Effects = detachedScope(coroutineContext)
        val call1Store = store(shared, call1State, call1Effects, now = 8_000) {
            this[VoiceSessionDelegator.Client] = sharedDelegator
            this[ServerIDClient] = mockServerID(session = { "ses_auto_cached" }, message = { "msg_c1" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                searchSessions = { _, _, _ -> emptyList() },
                listDirectory = { _, _ -> OpenCodeDirectoryListing(directory = "/Users/alex/workspace", folders = emptyList()) },
                createSession = { _, dir, id ->
                    createCalls.update { it + 1 }
                    OpenCodeSession(id = id ?: "ses_auto_cached", serverTitle = "Untitled", projectID = "proj_ws", directory = dir, updatedAt = now)
                },
                renameSession = { _, id, title ->
                    renameCalls.update { it + (id to title) }
                    if (stallRename.value) {
                        renameStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            renameCancelled.set(true)
                        }
                    }
                },
                switchModel = { _, id, model -> modelCalls.update { it + (id to model) } },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    sendCalls.update { it + sessionID }
                    PromptContext.messageID.current() ?: "msg_c1"
                },
            )
            this[VoiceCallClient] = testVoiceCall(end = { endedCalls.update { it + 1 } })
        }

        call1Store.send(functionCall("del_c1")) {
            it.startingTurn("del_c1", sessionID = null, admitting = true, saidTurn = 1, provisionalSessionID = "ses_auto_cached")
        }

        call1Store.receive(
            VoiceFeature.Action.SessionDelegatorAutomaticResolved(
                callID = "del_c1",
                serverScopeKey = serverKey,
                sessionID = "ses_auto_cached",
                created = createdVoice,
            ),
            timeout = 5.seconds,
        ) {
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    turns = it.sessionDelegator.turns.map { turn ->
                        turn.copy(sessionID = "ses_auto_cached", title = "Voice", reportedCreatedAutomatic = true)
                    },
                    activeSessionID = "ses_auto_cached",
                ),
            )
        }
        assertEquals("ses_auto_cached", shared.get(VoiceSharedKeys.voiceDelegatorSessionIDs)[serverKey])
        call1Store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.DelegatorSessionCreated(createdVoice)))
        withTimeout(5.seconds) { renameStarted.await() }

        // Hang up while `renameSession` is suspended: cancels the underlying task without running `switchModel` or `sendDeliveredPrompt`.
        call1Store.send(VoiceFeature.Action.EndTapped) { it.hungUp() }
        call1Store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        eventually { renameCancelled.value }
        assertTrue(renameCancelled.value)
        assertEquals(1, createCalls.value)
        assertTrue("switchModel must not run after hang-up", modelCalls.value.isEmpty())
        assertTrue("sendPrompt must not run after hang-up", sendCalls.value.isEmpty())
        assertEquals("ses_auto_cached", shared.read { call1Store.state.voiceDelegatorSessionIDs[serverKey] })
        call1Store.finish()
        call1Effects.skipInFlightEffects()

        // 3. Next call reuses cached `ses_auto_cached` (never creates a second "Voice" session) and finishes interrupted rename + initial model setup.
        stallRename.set(false)
        val call2State = liveDelegatorState(shared)
            .copy(pendingModel = defaultModel)
            .recording(VoiceTranscriptTail.Role.user, "Second call.", "u_c2")

        val expectedContext = """[Phone context] Call on session "Session View" (ses_view) · """ + VoiceScreenVisibility.notLooking.unseenNote!!
        val expectedPrompt = VoiceSessionDelegator.formatPrompt(
            deltaLines = listOf("User: Second call."),
            promisedClaim = null,
            contextLine = expectedContext,
            includeActiveCallHeader = true,
        )
        val expectedAdmission = VoiceSessionDelegator.Admission(
            sessionID = "ses_auto_cached",
            title = "Voice",
            promptID = "msg_c2",
            promptText = expectedPrompt,
            explained = true,
            contextLine = expectedContext,
            cachedAutomaticSessionID = "ses_auto_cached",
        )

        val call2Effects = detachedScope(coroutineContext)
        val call2Store = store(shared, call2State, call2Effects, now = 8_000) {
            this[VoiceSessionDelegator.Client] = sharedDelegator
            this[ServerIDClient] = mockServerID(session = { "ses_should_not_create" }, message = { "msg_c2" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                createSession = { _, dir, id ->
                    createCalls.update { it + 1 }
                    OpenCodeSession(id = id ?: "ses_2", serverTitle = "Voice", projectID = "p", directory = dir, updatedAt = now)
                },
                renameSession = { _, id, title -> renameCalls.update { it + (id to title) } },
                switchModel = { _, id, model -> modelCalls.update { it + (id to model) } },
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    sendCalls.update { it + sessionID }
                    PromptContext.messageID.current() ?: "msg_c2"
                },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, sessionID, _ ->
                assertEquals("ses_auto_cached", sessionID)
                "Ready on cached Voice session."
            })
            this[VoiceCallClient] = testVoiceCall(completeFunction = { id, out -> completed.update { it + (id to out) } })
        }

        call2Store.send(functionCall("del_c2")) {
            it.startingTurn("del_c2", sessionID = "ses_auto_cached", admitting = true, saidTurn = 1, activeSessionID = "ses_auto_cached")
        }
        call2Store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID = "ses_auto_cached")), timeout = 5.seconds)
        call2Store.receive(VoiceFeature.Action.SessionDelegatorAdmitted(callID = "del_c2", admission = expectedAdmission), timeout = 5.seconds) {
            it.admittingFirstTurn("ses_auto_cached", "Voice", "msg_c2", saidTurn = 1, contextLine = expectedContext, now = now)
        }
        call2Store.receive(
            VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_c2", reply = "Ready on cached Voice session.")),
            timeout = 5.seconds,
        ) {
            it.settlingFirstTurn("ses_auto_cached", "msg_c2", "Ready on cached Voice session.")
        }
        call2Store.finish(timeout = 5.seconds)

        assertEquals("Succeeded create before hang-up must be reused without creating a second Voice session", 1, createCalls.value)
        assertEquals(1, modelCalls.value.size)
        assertEquals("ses_auto_cached", modelCalls.value.first().first)
        assertEquals(listOf("ses_auto_cached"), sendCalls.value)
        assertEquals("[COMPLETE] Ready on cached Voice session.", completed.value.firstOrNull()?.second)
        call2Effects.skipInFlightEffects()
    }

    @Test fun watchSessionPromptDoesNotBlockTurnPromptSettlement() {
        val watch = Watch()
        watch.step(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.live))
        watch.step(ReplyWatch.Event.DelegatorStarted(requestID = "del_turn"))
        watch.step(ReplyWatch.Event.DelegatorWaiting(sessionID = "ses_shared", requestID = "del_turn", isWaiting = true))

        val watchOnly = ReplyWatch.Prompt(promptID = "watch-tool_1", sessionID = "ses_shared", title = "Voice", topic = "Build")
            .copy(readsLatestSessionReply = true)
        val turnPrompt = ReplyWatch.Prompt(promptID = "msg_turn", sessionID = "ses_shared", title = "Voice")
        watch.step(ReplyWatch.Event.Sent(listOf(watchOnly, turnPrompt)))

        watch.step(ReplyWatch.Event.WaitSucceeded(promptID = "msg_turn", reply = "Turn reply arrived."))
        assertTrue(
            "An open ios_voice_watch_session prompt must not block a turn prompt on the same session",
            "msg_turn" in watch.value.resolvedPromptIDs,
        )
        assertFalse("watch-tool_1" in watch.value.resolvedPromptIDs)
        assertEquals(listOf("watch-tool_1"), watch.value.watched.map { it.promptID })
        assertEquals(ReplyWatch.Outcome.Replied("Turn reply arrived."), watch.value.deferredNotices.firstOrNull()?.outcome)
    }

    /** A turn in `ses_voice` waiting on [promptID], the call in [phase]. */
    private fun waitingTurnState(
        shared: VoiceTestShared,
        phase: VoiceFeature.State.Phase,
        userText: String,
        callID: String,
        promptID: String,
        didSayInterim: Boolean = false,
    ): VoiceFeature.State {
        val base = shared.read { VoiceFeature.State(server, VoiceSessionDelegatorTestSupport.session) }
            .withPhase(phase)
            .copy(usesSessionDelegator = true)
        val recorded = base.tail.record(VoiceTranscriptTail.Role.user, userText, id = "u1").tail
        val captured = recorded.capture()
        val state = base.copy(
            tail = captured.tail.accept(captured.capture),
            sessionDelegator = base.sessionDelegator.copy(
                activeSessionID = "ses_voice",
                turns = listOf(
                    VoiceSessionDelegator.State.Turn(
                        callID = callID,
                        capture = captured.capture,
                        sessionID = "ses_voice",
                        title = "Voice",
                        promptID = promptID,
                        saidTurn = 1,
                        didSayInterim = didSayInterim,
                    ),
                ),
            ),
            delegatorWork = listOf(callID),
        )
        return state
            .stepping(ReplyWatch.Event.DelegatorStarted(requestID = callID))
            .stepping(ReplyWatch.Event.DelegatorWaiting(sessionID = "ses_voice", requestID = callID, isWaiting = true))
            .stepping(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = promptID, sessionID = "ses_voice", title = "Voice"))))
    }

    @Test fun endingCallStillSettlesActiveSessionDelegatorTurn() = runBlocking {
        val shared = preserveSettings()
        val completed = Locked(listOf<Pair<String, String>>())
        val state = waitingTurnState(
            shared,
            VoiceFeature.State.Phase.EndingAfterGoodbye(spoken = false),
            userText = "Check status before we hang up",
            callID = "del_goodbye",
            promptID = "msg_goodbye",
        )
        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 1_700_000_000) {
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { id, out -> completed.update { it + (id to out) } },
                cancelPendingAnnouncements = { },
            )
        }

        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_goodbye", reply = "All green, bye!"))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_goodbye", reply = "All green, bye!"))
                .copy(
                    sessionDelegator = it.sessionDelegator.copy(turns = emptyList(), activeSessionID = null),
                    delegatorWork = emptyList(),
                )
                .stepping(
                    ReplyWatch.Event.DelegatorAnswered(
                        sessionID = "ses_voice",
                        reply = "All green, bye!",
                        promptIDs = listOf("msg_goodbye"),
                        requestID = "del_goodbye",
                    ),
                )
                .stepping(ReplyWatch.Event.DelegatorFinished(requestID = "del_goodbye", spoken = "All green, bye!"))
        }
        store.finish(timeout = 5.seconds)

        assertEquals(1, completed.value.size)
        assertEquals("del_goodbye", completed.value.firstOrNull()?.first)
        assertEquals("[COMPLETE] All green, bye!", completed.value.firstOrNull()?.second)
    }

    @Test fun supersededDelegationClosesLocallyWithoutWireAppendOnLive() = runTest(timeout = 1.minutes) {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 5.seconds,
                liveAcknowledgementWait = Duration.ZERO,
                liveAppendQuiet = Duration.ZERO,
            ),
        )
        val events = EventIterator(harness.start(unboundSpeechUsesInstructions = true), backgroundScope)
        val socket = harness.nextOpened()
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_close", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Run all tests", startMilliseconds = 0, endMilliseconds = 400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Run all tests", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_first", target = "client", offsetMilliseconds = 400))
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "del_first", name = "delegate", arguments = JsonObject(emptyMap())),
            events.next(),
        )

        harness.coordinator.closeDelegation(callID = "del_first", reason = VoiceDelegationCloseReason.superseded)

        assertFalse(
            "Closing a superseded GPT-Live delegation must never send thinking/commentary/instructions frames that provoke speech",
            socket.commands.any {
                it is LiveCommand.AppendThinking || it is LiveCommand.AppendCommentary || it is LiveCommand.AppendInstructions
            },
        )

        harness.coordinator.end()
    }

    @Test fun interimSpokenTurnClosesDelegationOnWire() = runBlocking {
        val shared = preserveSettings()
        val closed = Locked(listOf<Pair<String, VoiceDelegationCloseReason>>())
        val completed = Locked(listOf<Pair<String, String>>())
        val state = waitingTurnState(
            shared,
            VoiceFeature.State.Phase.Live,
            userText = "Check the deploy",
            callID = "del_interim",
            promptID = "msg_interim",
            didSayInterim = true,
        )
        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 1_700_000_000) {
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { id, out -> completed.update { it + (id to out) } },
                cancelPendingAnnouncements = { },
            ).copy(closeDelegation = { id, reason -> closed.update { it + (id to reason) } })
        }

        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_interim", reply = ""))) {
            it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_interim", reply = ""))
                .copy(
                    sessionDelegator = it.sessionDelegator.copy(turns = emptyList(), activeSessionID = null),
                    delegatorWork = emptyList(),
                )
                .stepping(
                    ReplyWatch.Event.DelegatorAnswered(
                        sessionID = "ses_voice",
                        reply = null,
                        promptIDs = listOf("msg_interim"),
                        requestID = "del_interim",
                    ),
                )
                .stepping(ReplyWatch.Event.DelegatorFinished(requestID = "del_interim", spoken = "Done."))
        }
        store.finish(timeout = 5.seconds)

        assertTrue(completed.value.isEmpty())
        assertEquals(1, closed.value.size)
        assertEquals("del_interim", closed.value.firstOrNull()?.first)
        assertEquals(VoiceDelegationCloseReason.coveredByInterimSpeech, closed.value.firstOrNull()?.second)
    }
}
