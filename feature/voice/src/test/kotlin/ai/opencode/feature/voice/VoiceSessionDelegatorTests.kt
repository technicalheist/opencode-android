package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.eventually
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.liveDelegatorState
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.mockServerID
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.otherServer
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.preserveSettings
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.server
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.session
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.store
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeDirectoryListing
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeRunMessagesPage
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.PromptContext
import ai.opencode.server.testing.WireDecoders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class VoiceSessionDelegatorTests {
    private data class SentPrompt(val sessionID: String, val text: String, val delivery: OpenCodeDelivery?, val messageID: String?)

    private val noArguments: RealtimeJSON = JsonObject(emptyMap())

    private fun functionCall(callID: String, arguments: RealtimeJSON = noArguments) =
        VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = callID, name = "delegate", arguments = arguments))

    private fun userTranscript(itemID: String, text: String) =
        VoiceFeature.Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = itemID, text = text, final = true))

    private fun isAdmitted(action: VoiceFeature.Action) = action is VoiceFeature.Action.SessionDelegatorAdmitted

    // MARK: - Choice, Route Availability & Wire Helpers

    @Test fun choiceRoundTripsWithoutStoringServerURLsAndReportsRouteAvailability() {
        val picked = VoiceDelegatorChoice.PickedSession(
            VoiceDelegatorChoice.Picked(
                session = OpenCodeSession(
                    id = "ses_home",
                    serverTitle = "HOME",
                    projectID = "p",
                    directory = "/Users/alex/code/home",
                    updatedAt = VoiceFeatureTestSupport.distantPast,
                ),
                server = server,
            ),
        )
        assertFalse(picked.rawValue.contains("localhost"))
        assertEquals(true, picked.picked?.serverID?.startsWith("srv_"))
        assertEquals(picked, VoiceDelegatorChoice.of(picked.rawValue))
        assertEquals(VoiceDelegatorChoice.BuiltIn, VoiceDelegatorChoice.of("built-in"))
        assertEquals(VoiceDelegatorChoice.AutomaticSession, VoiceDelegatorChoice.of("automatic"))
        assertEquals(VoiceDelegatorChoice.ViewedSession, VoiceDelegatorChoice.of("viewed"))
        assertTrue(picked.belongs(server))
        assertFalse(picked.belongs(otherServer))

        val serverAvail = VoiceDelegatorRouteAvailability.resolve(
            route = VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive),
            selectedEngine = VoiceEngine.gptLive,
        )
        assertTrue(serverAvail.isAvailable)
        assertNull(serverAvail.explanation)

        val geminiAvail = VoiceDelegatorRouteAvailability.resolve(
            route = VoiceRoute.serverGeminiLive,
            selectedEngine = VoiceEngine.gptLive,
        )
        assertFalse(geminiAvail.isAvailable)
        assertEquals(true, geminiAvail.explanation?.contains("Gemini Live"))

        val realtimeAvail = VoiceDelegatorRouteAvailability.resolve(
            route = VoiceRoute.PhoneKey(VoiceEngine.realtime),
            selectedEngine = VoiceEngine.realtime,
        )
        assertFalse(realtimeAvail.isAvailable)
        assertEquals(true, realtimeAvail.explanation?.contains("GPT-Live"))

        val serverRealtimeAvail = VoiceDelegatorRouteAvailability.resolve(
            route = VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.realtime),
            selectedEngine = VoiceEngine.realtime,
        )
        assertFalse(serverRealtimeAvail.isAvailable)
        assertEquals(true, serverRealtimeAvail.explanation?.contains("GPT-Live"))

        val phoneLiveAvail = VoiceDelegatorRouteAvailability.resolve(
            route = VoiceRoute.PhoneKey(VoiceEngine.gptLive),
            selectedEngine = VoiceEngine.gptLive,
        )
        assertTrue(phoneLiveAvail.isAvailable)
        assertNull(phoneLiveAvail.explanation)
    }

    @Test fun startSupportsSessionDelegatorOnServerVoiceAndPhoneKeyGPTLive() = runTest {
        val shared = preserveSettings()
        val logDir = Files.createTempDirectory("voice-sdel-").toFile()
        val recorder = DependencyValues.withValues(DependencyValues.testing().update { this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100)) }) {
            VoiceCallLogRecorder(directory = logDir)
        }
        try {
            shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.AutomaticSession)

            // 1. Server Voice brokered GPT-Live -> uses OpenCode Session Delegator with client delegation (no phone key needed).
            shared.set(VoiceSharedKeys.serverVoiceProvider, ServerVoiceProvider.openAI)
            shared.set(VoiceSharedKeys.serverVoiceSupportByServer, mapOf(server.persistenceID to ServerVoiceSupport.supported))

            val serverState = shared.read { VoiceFeature.State(server, session) }.copy(serverVoiceSupport = ServerVoiceSupport.supported)

            val startedConfigs = mutableListOf<RealtimeSessionConfig>()
            val serverStore = voiceTestStore(shared, { serverState }) {
                this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
                this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.voiceSessionDelegator))
                this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ -> throw AssertionError("Unused") }
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[VoiceWorkClient] = testVoiceWork(recentMessages = { _, _, _ -> emptyList() })
                this[ServerVoiceClient] = ServerVoiceClient(
                    checkSupport = { ServerVoiceSupport.supported },
                    models = { null },
                    createSession = { _, _ -> throw AssertionError("Unused") },
                )
                this[VoiceCallClient] = testVoiceCall(start = { key, config ->
                    assertTrue(key.isEmpty())
                    startedConfigs += config
                    emptyFlow()
                })
            }
            serverStore.exhaustive = false
            serverStore.send(VoiceFeature.Action.Start)
            serverStore.receive({ it == VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended) }, "ended", 5.seconds)
            serverStore.finish()
            assertEquals(VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive), serverStore.state.activeRoute)
            assertFalse(serverStore.state.usesDelegator)
            assertTrue(serverStore.state.usesSessionDelegator)
            assertEquals(LiveDelegation.client, startedConfigs.firstOrNull()?.liveDelegation)
            assertEquals(true, startedConfigs.firstOrNull()?.unboundSpeechUsesInstructions)

            // 2. Phone-key GPT-Live -> locks in OpenCode Session Delegator & unboundSpeechUsesInstructions.
            startedConfigs.clear()
            shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
            shared.set(SharedKeys.openAIAPIKey, "sk-test")
            shared.set(SharedKeys.verifiedVoiceKeyFingerprint, VoiceKeyFingerprint.make(key = "sk-test", model = VoiceEngine.gptLive.modelID))

            val phoneState = shared.read { VoiceFeature.State(server, session) }
            val phoneStore = voiceTestStore(shared, { phoneState }) {
                this[DateClient] = DateClient.constant(Instant.ofEpochSecond(110))
                this[ExperimentalFeatures] = ExperimentalFeatures.enabling(setOf(ExperimentalFeature.voiceSessionDelegator))
                this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ -> throw AssertionError("Unused") }
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[VoiceWorkClient] = testVoiceWork(recentMessages = { _, _, _ -> emptyList() })
                this[VoiceCallClient] = testVoiceCall(start = { _, config ->
                    startedConfigs += config
                    emptyFlow()
                })
            }
            phoneStore.exhaustive = false
            phoneStore.send(VoiceFeature.Action.Start)
            phoneStore.receive({ it == VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended) }, "ended", 5.seconds)
            phoneStore.finish()
            assertEquals(VoiceRoute.PhoneKey(VoiceEngine.gptLive), phoneStore.state.activeRoute)
            assertFalse(phoneStore.state.usesDelegator)
            assertTrue(phoneStore.state.usesSessionDelegator)
            assertEquals(true, startedConfigs.firstOrNull()?.unboundSpeechUsesInstructions)
        } finally {
            logDir.deleteRecursively()
        }
    }

    @Test fun sseIgnoresSupersededExecutionInterruptionsAndDecodesRunMessageToolCalls() {
        val supersededFrame = """{"type":"session.execution.interrupted","data":{"sessionID":"ses_voice","reason":"superseded"}}"""
        assertNull(WireDecoders.decodeStreamFrame(supersededFrame))

        val cancelledFrame = """{"type":"session.execution.interrupted","data":{"sessionID":"ses_voice","reason":"cancelled"}}"""
        assertEquals(OpenCodeServerEvent.ExecutionEnded(sessionID = "ses_voice"), WireDecoders.decodeStreamFrame(cancelledFrame))

        val missingReasonFrame = """{"type":"session.execution.interrupted","data":{"sessionID":"ses_voice"}}"""
        assertEquals(OpenCodeServerEvent.ExecutionEnded(sessionID = "ses_voice"), WireDecoders.decodeStreamFrame(missingReasonFrame))

        val pageData = """
            {
              "data": [
                {
                  "id": "msg_asst_1",
                  "type": "assistant",
                  "content": [
                    {
                      "id": "call_open_1",
                      "type": "tool",
                      "name": "ios_voice_open_session",
                      "state": { "input": { "session_id": "ses_target" } }
                    },
                    {
                      "type": "text",
                      "text": "Opened the target session."
                    }
                  ]
                },
                {
                  "id": "msg_user_1",
                  "type": "user",
                  "text": "Open target"
                }
              ]
            }
        """.trimIndent().toByteArray()
        val page = WireDecoders.decodeRunMessages(pageData)
        assertEquals(listOf("msg_user_1", "msg_asst_1"), page.messages.map { it.id })
        assertEquals(
            listOf(
                OpenCodeRunMessage.ToolCall(
                    id = "call_open_1",
                    name = "ios_voice_open_session",
                    input = OpenCodeJSONValue.Object(mapOf("session_id" to OpenCodeJSONValue.Str("ses_target"))),
                ),
            ),
            page.messages[1].toolCalls,
        )
    }

    @Test fun spokenReplyCollapsesCodeBlocksAndStripsMarkdownWhilePreservingIdentifiers() {
        val markdown = """
            ## Build Summary

            - Fixed **crash** in [VoiceFeature](https://example.com/file) using `ios_voice_say`.
            - Kept _plain emphasis_ and `snake_case_id` intact.

            ```swift
            let x = 42
            print(x)
            ```
        """.trimIndent()
        val cleaned = VoiceSessionDelegator.spokenReply(markdown)
        assertFalse(cleaned.contains("##"))
        assertFalse(cleaned.contains("**"))
        assertFalse(cleaned.contains("https://example.com"))
        assertFalse(cleaned.contains("let x = 42"))
        assertTrue(cleaned.contains("Build Summary"))
        assertTrue(cleaned, cleaned.contains("Fixed crash in VoiceFeature using ios_voice_say."))
        assertTrue(cleaned, cleaned.contains("Kept plain emphasis and snake_case_id intact."))

        val codeOnly = VoiceSessionDelegator.spokenReply("```swift\nlet x = 42\n```")
        assertEquals("The session returned a code block in the transcript.", codeOnly)
    }

    // MARK: - Point 1 & 2: Automatic Session, Watermarked Deltas, Context Deduplication & ReplyWatch

    @Test fun automaticSessionCreatesVoiceSessionOnceAndSendsWatermarkedDeltasWithDeduplicatedContext() = runBlocking {
        val shared = preserveSettings()
        val defaultModel = OpenCodeModelRef(id = "claude-sonnet-5-5", providerID = "opencode")
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.AutomaticSession)
        val state = liveDelegatorState(shared).copy(pendingModel = defaultModel)
            .recording(VoiceTranscriptTail.Role.user, "Check the failing tests.", "u1")
            .recording(VoiceTranscriptTail.Role.assistant, "Checking now.", "a1")

        val sentPrompts = Locked(listOf<SentPrompt>())
        val createdDirs = Locked(listOf<String>())
        val renamed = Locked(listOf<Pair<String, String>>())
        val modelSwitches = Locked(listOf<Pair<String, OpenCodeModelRef>>())
        val completed = Locked(listOf<Pair<String, String>>())
        val announced = Locked(listOf<String>())
        val nextID = Locked(0)
        fun next(): Int = nextID.withValue { (it + 1) to (it + 1) }

        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 1_000) {
            this[ServerIDClient] = mockServerID(
                session = { "ses_auto_${next()}" },
                message = { "msg_voice_${next()}" },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                searchSessions = { _, _, _ -> emptyList() },
                listDirectory = { _, path ->
                    assertNull(path)
                    OpenCodeDirectoryListing(directory = "/Users/alex/workspace", folders = emptyList())
                },
                createSession = { _, directory, sessionID ->
                    createdDirs.update { it + directory }
                    OpenCodeSession(
                        id = sessionID ?: "ses_fallback",
                        serverTitle = "Untitled",
                        projectID = "proj_ws",
                        directory = directory,
                        updatedAt = Instant.ofEpochSecond(1_000),
                    )
                },
                renameSession = { _, id, title -> renamed.update { it + (id to title) } },
                switchModel = { _, id, model -> modelSwitches.update { it + (id to model) } },
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, sessionID, text, delivery ->
                    val mid = PromptContext.messageID.current()
                    sentPrompts.update { it + SentPrompt(sessionID, text, delivery, mid) }
                    mid ?: "msg_generated"
                },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, sessionID, promptID ->
                assertEquals("ses_auto_1", sessionID)
                if (promptID == "msg_voice_2") "**Two** unit tests failed in `VoiceTests.swift`." else "Re-ran them; both pass now."
            })
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { callID, output -> completed.update { it + (callID to output) } },
            ).copy(announce = { _, _, text -> announced.update { it + text } })
        }
        store.exhaustive = false

        // Turn 1: creates "Voice" session once, sets initial model once, sends active-call header + context + delta.
        store.send(functionCall("del_1", VoiceFeatureTestSupport.arguments("Checking now.")))
        store.receive({ it is VoiceFeature.Action.Delegate && it.action is VoiceFeature.DelegateAction.DelegatorSessionCreated }, 5.seconds)
        store.eventuallyReducing { completed.value.size == 1 }

        val serverKey = VoiceDelegatorWorkspace.serverID(server)
        assertEquals("ses_auto_1", shared.get(VoiceSharedKeys.voiceDelegatorSessionIDs)[serverKey])
        assertEquals(listOf("/Users/alex/workspace"), createdDirs.value)
        assertEquals(listOf("ses_auto_1"), renamed.value.map { it.first })
        assertEquals(listOf("Voice"), renamed.value.map { it.second })
        assertEquals(1, modelSwitches.value.size)
        assertEquals("ses_auto_1", modelSwitches.value.firstOrNull()?.first)
        assertEquals(defaultModel, modelSwitches.value.firstOrNull()?.second)

        assertEquals(1, sentPrompts.value.size)
        val firstPrompt = sentPrompts.value[0]
        assertEquals("ses_auto_1", firstPrompt.sessionID)
        assertEquals(OpenCodeDelivery.Steer, firstPrompt.delivery)
        assertEquals("msg_voice_2", firstPrompt.messageID)
        assertTrue(firstPrompt.text.contains("[Voice call active]"))
        assertTrue(firstPrompt.text, firstPrompt.text.contains("[Phone context] Call on session \"Session View\" (ses_view) · Assume the user isn't looking at the screen"))
        assertTrue(firstPrompt.text, firstPrompt.text.contains("[Voice delta]\nUser: Check the failing tests.\nVoice: Checking now."))
        assertEquals(1, completed.value.size)
        assertEquals("del_1", completed.value[0].first)
        assertEquals("[COMPLETE] Two unit tests failed in VoiceTests.swift.", completed.value[0].second)
        assertTrue("Delegator's own reply must not also fire a duplicate [Session update] notice", announced.value.isEmpty())

        // Turn 2 on the same screen: reuses cached "Voice" session, does NOT switch model again,
        // omits [Voice call active] and unchanged [Phone context], and sends ONLY new delta lines.
        store.send(userTranscript("u2", "Fix them and re-run."))
        store.send(functionCall("del_2"))
        store.eventuallyReducing { completed.value.size == 2 }

        assertEquals(1, createdDirs.value.size)
        assertEquals("Model is only set when creating the Voice session", 1, modelSwitches.value.size)
        assertEquals(2, sentPrompts.value.size)
        val secondPrompt = sentPrompts.value[1]
        assertEquals("ses_auto_1", secondPrompt.sessionID)
        assertFalse(secondPrompt.text.contains("[Voice call active]"))
        assertFalse(secondPrompt.text.contains("[Phone context]"))
        assertFalse(secondPrompt.text.contains("Check the failing tests."))
        assertEquals("[Voice delta]\nUser: Fix them and re-run.", secondPrompt.text)
        assertEquals(2, completed.value.size)
        assertEquals("[COMPLETE] Re-ran them; both pass now.", completed.value[1].second)

        // Turn 3 after targetNeedsInput changes: includes updated [Phone context], still omits [Voice call active].
        val movedState = store.state.copy(targetNeedsInput = true)
        store.finish()
        effects.skipInFlightEffects()

        val movedEffects = detachedScope(coroutineContext)
        val movedStore = store(shared, movedState, movedEffects, now = 1_010) {
            this[ServerIDClient] = mockServerID(session = { "ses_unused" }, message = { "msg_voice_9" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, sessionID, text, delivery ->
                    val mid = PromptContext.messageID.current()
                    sentPrompts.update { it + SentPrompt(sessionID, text, delivery, mid) }
                    mid ?: "msg_voice_9"
                },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, _, _ -> "Approved." })
            this[VoiceCallClient] = testVoiceCall(completeFunction = { callID, output -> completed.update { it + (callID to output) } })
        }
        movedStore.exhaustive = false
        movedStore.send(userTranscript("u3", "What needs my input?"))
        movedStore.send(functionCall("del_3"))
        movedStore.eventuallyReducing { completed.value.size == 3 }

        val thirdPrompt = sentPrompts.value[2]
        assertFalse(thirdPrompt.text.contains("[Voice call active]"))
        assertTrue(thirdPrompt.text, thirdPrompt.text.contains("[Phone context] Call on session \"Session View\" (ses_view) · needs input · Assume the user isn't looking at the screen"))
        assertTrue(thirdPrompt.text.contains("User: What needs my input?"))
        movedStore.finish()
        movedEffects.skipInFlightEffects()
    }

    // MARK: - Point 2 & 6: Superseded Steer Exhaustive State Machine

    @Test fun newerSteerSupersedesEarlierDelegationAndSpeaksOnlyCombinedFinalReply() = runBlocking {
        val shared = preserveSettings()
        val picked = VoiceDelegatorChoice.Picked(
            serverID = VoiceDelegatorWorkspace.serverID(server),
            sessionID = "ses_home",
            title = "HOME",
            projectName = "home",
        )
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.PickedSession(picked))
        val state = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Run the smoke tests.", "u1")

        // Finishing without a value answers nil.
        val waitGate1 = CompletableDeferred<String?>()
        val waitGate2 = CompletableDeferred<String?>()
        val abandoned = Locked(listOf<String>())
        val completed = Locked(listOf<Pair<String, String>>())
        val activities = Locked(listOf<List<String?>>())
        val msgCounter = Locked(0)

        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 2_000) {
            this[ServerIDClient] = mockServerID(
                session = { "ses_unused" },
                message = { "msg_${msgCounter.withValue { (it + 1) to (it + 1) }}" },
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, _, _, _ -> PromptContext.messageID.current() ?: "msg_fallback" },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, _, promptID ->
                (if (promptID == "msg_1") waitGate1 else waitGate2).await()
            })
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { id, out -> completed.update { it + (id to out) } },
                noteProgressActivity = { id, activity -> activities.update { it + listOf(listOf(id, activity)) } },
            ).copy(closeDelegation = { id, _ -> abandoned.update { it + id } })
        }
        store.exhaustive = false

        store.send(functionCall("del_1"))
        store.receive(::isAdmitted, 5.seconds)
        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = "ses_home",
                events = listOf(OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m1", toolID = "t_shell", name = "shell")),
            ),
        )
        assertTrue(eventually { activities.value.size == 1 })
        assertEquals(listOf(listOf("del_1", "running a command")), activities.value)

        // User steers again mid-turn before del_1 finishes.
        store.send(userTranscript("u2", "Actually only run VoiceTests."))
        store.send(functionCall("del_2"))
        store.receive(::isAdmitted, 5.seconds)

        assertEquals("Earlier delegation is abandoned as soon as the newer steer is admitted", listOf("del_1"), abandoned.value)
        // The follow-up says what the run it joined is doing; the superseded
        // turn hears nothing more.
        assertTrue(eventually { activities.value.size == 2 })
        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = "ses_home",
                events = listOf(OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m2", toolID = "t_grep", name = "grep")),
            ),
        )
        assertTrue(eventually { activities.value.size == 3 })
        assertEquals(
            listOf(listOf("del_1", "running a command"), listOf("del_2", "running a command"), listOf("del_2", "searching the code")),
            activities.value,
        )
        assertEquals(true, store.state.sessionDelegator.turns.firstOrNull { it.callID == "del_1" }?.superseded)
        assertEquals(false, store.state.sessionDelegator.turns.firstOrNull { it.callID == "del_2" }?.superseded)

        // Even if a late completion arrives for msg_1, it was cancelled/superseded and must not speak or close early.
        waitGate1.complete(null)
        store.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_1", reply = "Stale partial reply that must not be spoken.")))
        assertTrue("Superseded delegation must not complete or close the active turn early", completed.value.isEmpty())

        waitGate2.complete("Ran VoiceTests only; all 14 passed.")
        store.receive({ it is VoiceFeature.Action.ReplyWatch }, 5.seconds)
        store.eventuallyReducing { completed.value.isNotEmpty() }
        store.skipReceivedActions()

        assertEquals(1, completed.value.size)
        assertEquals("del_2", completed.value[0].first)
        assertEquals("[COMPLETE] Ran VoiceTests only; all 14 passed.", completed.value[0].second)
        assertTrue(store.state.sessionDelegator.turns.isEmpty())
        assertFalse(store.state.isWorking)
        store.finish()
        effects.skipInFlightEffects()
    }

    // MARK: - Point 4 & 5: Live Tool Captions and All Five ios_voice_* Tools

    @Test fun liveToolCaptionsUpdateStatusAndTargetsWithoutSendingMidTurnThinkingAppends() = runBlocking {
        val shared = preserveSettings()
        val base = liveDelegatorState(shared)
        val captured = base.tail.capture()
        val state = base.copy(
            tail = captured.tail,
            sessionDelegator = base.sessionDelegator.copy(
                activeSessionID = session.id,
                turns = listOf(
                    VoiceSessionDelegator.State.Turn(
                        callID = "del_1",
                        capture = captured.capture,
                        sessionID = session.id,
                        title = session.title,
                        promptID = "msg_1",
                        saidTurn = 0,
                    ),
                ),
            ),
            delegatorWork = listOf("del_1"),
        )

        val activities = Locked(listOf<List<String?>>())
        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 0) {
            this[VoiceCallClient] = testVoiceCall(noteProgressActivity = { callID, activity -> activities.update { it + listOf(listOf(callID, activity)) } })
        }

        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = session.id,
                events = listOf(
                    OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m1", toolID = "t1", name = "read"),
                    OpenCodeLiveContentEvent.ToolCalled(
                        messageID = "m1",
                        toolID = "t1",
                        input = OpenCodeJSONValue.Object(mapOf("path" to OpenCodeJSONValue.Str("/Users/alex/project/VoiceFeature.swift"))),
                    ),
                ),
            ),
        ) {
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    toolNames = it.sessionDelegator.toolNames + ("t1" to "read"),
                    runningTools = listOf(VoiceSessionDelegator.State.RunningTool(id = "t1", name = "read", label = "Reading VoiceFeature.swift", sessionID = session.id)),
                ),
            )
        }
        assertEquals("Reading VoiceFeature.swift", store.state.statusText)
        assertEquals("Reading VoiceFeature.swift", store.state.captionTargets.lastOrNull()?.text)
        store.finish()
        // The spoken "still working" line, if one comes due, says what's running.
        assertEquals(listOf(listOf("del_1", "reading files")), activities.value)

        store.send(
            VoiceFeature.Action.SessionDelegatorTools(
                sessionID = session.id,
                events = listOf(OpenCodeLiveContentEvent.ToolSucceeded(messageID = "m1", toolID = "t1", output = listOf("ok"), metadata = emptyMap())),
            ),
        ) {
            it.copy(sessionDelegator = it.sessionDelegator.copy(toolNames = it.sessionDelegator.toolNames - "t1", runningTools = emptyList()))
        }
        assertEquals("Working", store.state.statusText)
        store.finish()
        assertEquals(listOf(listOf("del_1", "reading files"), listOf("del_1", null)), activities.value)
        effects.skipInFlightEffects()
    }

    @Test fun phoneSideToolEffectsApplyOpenPinWatchSayAndEndCallFromToolInput() = runBlocking {
        val shared = preserveSettings()

        // 5. ios_voice_end_call during a goodbye turn waits for the delegation to settle, then ends the call.
        var goodbyeVoice = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Thanks, bye!", "u_bye")
        goodbyeVoice = goodbyeVoice.copy(replyWatch = goodbyeVoice.replyWatch.noteUserActed())
        val captured = goodbyeVoice.tail.capture()
        goodbyeVoice = goodbyeVoice.copy(
            tail = captured.tail,
            sessionDelegator = goodbyeVoice.sessionDelegator.copy(
                activeSessionID = "ses_voice",
                turns = listOf(
                    VoiceSessionDelegator.State.Turn(
                        callID = "del_bye",
                        capture = captured.capture,
                        sessionID = "ses_voice",
                        title = "Voice",
                        promptID = "msg_bye",
                        saidTurn = goodbyeVoice.replyWatch.userTurns,
                    ),
                ),
            ),
            delegatorWork = listOf("del_bye"),
        )
            .stepping(ReplyWatch.Event.DelegatorStarted(requestID = "del_bye"))
            .stepping(ReplyWatch.Event.DelegatorWaiting(sessionID = "ses_voice", requestID = "del_bye", isWaiting = true))
            .stepping(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_bye", sessionID = "ses_voice", title = "Voice"))))

        val completed = Locked(listOf<Pair<String, String>>())
        val endedCalls = Locked(0)
        val goodbyeClock = TestClock()
        // Effects share this test's single-threaded event loop, so once the goodbye timer has
        // called `sleep`, its sleeper is registered on `goodbyeClock` before the test resumes.
        val goodbyeTimerSleeping = CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val goodbyeStore = store(shared, goodbyeVoice, effects, now = 3_100) {
            this[Clock] = object : Clock {
                override fun now() = goodbyeClock.now()
                override suspend fun sleep(duration: Duration) {
                    goodbyeTimerSleeping.complete(Unit)
                    goodbyeClock.sleep(duration)
                }
            }
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { id, out -> completed.update { it + (id to out) } },
                end = { endedCalls.update { it + 1 } },
            )
        }
        goodbyeStore.exhaustive = false
        val endCallEvents = listOf(
            OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m_bye", toolID = "t_end", name = "ios_voice_end_call"),
            OpenCodeLiveContentEvent.ToolCalled(messageID = "m_bye", toolID = "t_end", input = OpenCodeJSONValue.Object(emptyMap())),
        )
        goodbyeStore.send(VoiceFeature.Action.SessionDelegatorTools(sessionID = "ses_voice", events = endCallEvents))
        assertTrue(goodbyeStore.state.endsAfterDelegation)
        assertEquals("Call stays live until the delegator's spoken goodbye reply settles", VoiceFeature.State.Phase.Live, goodbyeStore.state.phase)

        goodbyeStore.send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_bye", reply = "Talk soon!")))
        goodbyeStore.eventuallyReducing { goodbyeStore.state.phase == VoiceFeature.State.Phase.EndingAfterGoodbye(spoken = false) }
        assertEquals(VoiceFeature.State.Phase.EndingAfterGoodbye(spoken = false), goodbyeStore.state.phase)

        withTimeout(5.seconds) { goodbyeTimerSleeping.await() }
        goodbyeClock.advance(by = 8.seconds)
        goodbyeStore.receive({ it == VoiceFeature.Action.EndTapped }, 5.seconds)
        goodbyeStore.eventuallyReducing { goodbyeStore.state.phase == VoiceFeature.State.Phase.Ended }
        assertEquals(VoiceFeature.State.Phase.Ended, goodbyeStore.state.phase)
        assertEquals(1, endedCalls.value)
        goodbyeStore.finish()
        effects.skipInFlightEffects()
    }

    // MARK: - Point 6: Cancellation, Reconnect Reconciliation, Deleted Session & Cross-Server Pick

    @Test fun endingCallMidAdmissionCancelsEffectsAndReleasesTailCaptureExhaustively() = runBlocking {
        val shared = preserveSettings()
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.ViewedSession)
        val state = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Deploy staging.", "u1")

        val admissionGate = CompletableDeferred<Unit>()
        val endedCalls = Locked(0)

        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 4_000) {
            this[ServerIDClient] = mockServerID(session = { "ses_auto" }, message = { "msg_1" })
            this[VoiceSessionDelegator.Client] = VoiceSessionDelegator.Client(submit = {
                admissionGate.await()
                throw OpenCodeClientError("Cancelled")
            })
            this[VoiceCallClient] = testVoiceCall(end = { endedCalls.update { it + 1 } })
        }

        store.send(functionCall("del_cancel")) {
            val acted = it.copy(replyWatch = it.replyWatch.noteUserActed())
            val capture = acted.tail.capture()
            acted.copy(
                tail = capture.tail,
                sessionDelegator = acted.sessionDelegator.copy(
                    activeSessionID = "ses_view",
                    turns = listOf(
                        VoiceSessionDelegator.State.Turn(
                            callID = "del_cancel",
                            capture = capture.capture,
                            sessionID = "ses_view",
                            admitting = true,
                            saidTurn = 1,
                        ),
                    ),
                ),
                delegatorWork = listOf("del_cancel"),
            ).stepping(ReplyWatch.Event.DelegatorStarted(requestID = "del_cancel"))
        }

        // Hang up while admission is still in flight: cancels workAdmission cleanly and releases tail capture.
        store.send(VoiceFeature.Action.EndTapped) {
            val ended = it.stepping(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.ended))
            val (sessionDelegator, captures) = ended.sessionDelegator.resetForCallEnd()
            val tail = captures.fold(ended.tail) { tail, capture -> tail.release(capture) }
            ended.copy(delegatorWork = emptyList(), sessionDelegator = sessionDelegator, tail = tail)
                .withPhase(VoiceFeature.State.Phase.Ended)
        }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))

        // Verify the unadmitted capture was released back into tail so a retry or next call still has it!
        assertEquals("Deploy staging.", store.state.tail.capture().capture.userText)
        assertEquals(1, endedCalls.value)
        admissionGate.complete(Unit)
        store.finish()
        effects.skipInFlightEffects()
    }

    @Test fun sseReconnectMidTurnReconcilesMissedPhoneToolEffectsFromRESTWithoutDuplicates() = runBlocking {
        val shared = preserveSettings()
        val base = liveDelegatorState(shared)
        val captured = base.tail.capture()
        val state = base.copy(
            tail = captured.tail,
            sessionDelegator = base.sessionDelegator.copy(
                activeSessionID = "ses_voice",
                appliedToolCallIDs = setOf("t_already_applied"),
                turns = listOf(
                    VoiceSessionDelegator.State.Turn(
                        callID = "del_1",
                        capture = captured.capture,
                        sessionID = "ses_voice",
                        title = "Voice",
                        promptID = "msg_anchor",
                        saidTurn = 0,
                    ),
                ),
            ),
            delegatorWork = listOf("del_1"),
        )

        val target = OpenCodeSession(
            id = "ses_missed",
            serverTitle = "Missed Open",
            projectID = "p",
            directory = "/Users/alex/missed",
            updatedAt = VoiceFeatureTestSupport.distantPast,
        )
        val announced = Locked(listOf<String>())

        val effects = detachedScope(coroutineContext)
        val store = store(shared, state, effects, now = 0) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                runMessages = { _, sessionID, _ ->
                    assertEquals("ses_voice", sessionID)
                    OpenCodeRunMessagesPage(
                        messages = listOf(
                            OpenCodeRunMessage(
                                id = "msg_older", type = "assistant", text = "Old",
                                toolCalls = listOf(
                                    OpenCodeRunMessage.ToolCall(id = "t_before_anchor", name = "ios_voice_say", input = OpenCodeJSONValue.Object(mapOf("text" to OpenCodeJSONValue.Str("Old turn")))),
                                ),
                            ),
                            OpenCodeRunMessage(id = "msg_anchor", type = "user", text = "Prompt"),
                            OpenCodeRunMessage(
                                id = "msg_reply", type = "assistant", text = "",
                                toolCalls = listOf(
                                    OpenCodeRunMessage.ToolCall(id = "t_already_applied", name = "ios_voice_say", input = OpenCodeJSONValue.Object(mapOf("text" to OpenCodeJSONValue.Str("Already spoken")))),
                                    OpenCodeRunMessage.ToolCall(id = "t_missed_open", name = "ios_voice_open_session", input = OpenCodeJSONValue.Object(mapOf("session_id" to OpenCodeJSONValue.Str(target.id)))),
                                ),
                            ),
                        ),
                        next = null,
                    )
                },
                getSession = { _, _ -> target },
            )
            this[VoiceCallClient] = testVoiceCall().copy(announce = { _, _, text -> announced.update { it + text } })
        }

        val expectedCalls = listOf(
            OpenCodeRunMessage.ToolCall(id = "t_already_applied", name = "ios_voice_say", input = OpenCodeJSONValue.Object(mapOf("text" to OpenCodeJSONValue.Str("Already spoken")))),
            OpenCodeRunMessage.ToolCall(id = "t_missed_open", name = "ios_voice_open_session", input = OpenCodeJSONValue.Object(mapOf("session_id" to OpenCodeJSONValue.Str(target.id)))),
        )
        store.send(VoiceFeature.Action.SessionDelegatorStreamReconnected) {
            it.copy(sessionDelegator = it.sessionDelegator.copy(turns = it.sessionDelegator.turns.map { turn -> turn.copy(reconnectedMidTurn = true) }))
        }
        store.receive(VoiceFeature.Action.SessionDelegatorReconciledTools(expectedCalls), timeout = 5.seconds) {
            it.copy(sessionDelegator = it.sessionDelegator.copy(appliedToolCallIDs = it.sessionDelegator.appliedToolCallIDs + "t_missed_open"))
        }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.OpenSessionRequested(toolID = "t_missed_open", targetID = target.id)))
        assertTrue("Pre-anchor and already-applied tool IDs must not re-fire on reconnect", announced.value.isEmpty())
        store.finish()
        effects.skipInFlightEffects()
    }

    @Test fun crossServerPickedSessionDeletedSessionAndStaleAutomaticCacheRecoverCleanly() = runBlocking {
        val shared = preserveSettings()
        // 1. Picked session on another server fails immediately with clear guidance and releases capture.
        val wrongServerPick = VoiceDelegatorChoice.PickedSession(
            VoiceDelegatorChoice.Picked(
                session = OpenCodeSession(
                    id = "ses_remote",
                    serverTitle = "Remote HOME",
                    projectID = "p",
                    directory = "/remote",
                    updatedAt = VoiceFeatureTestSupport.distantPast,
                ),
                server = otherServer,
            ),
        )
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, wrongServerPick)
        val crossState = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Check status.", "u1")

        val completed = Locked(listOf<Pair<String, String>>())
        val crossEffects = detachedScope(coroutineContext)
        val crossStore = store(shared, crossState, crossEffects, now = 5_000) {
            this[ServerIDClient] = mockServerID(session = { "ses_x" }, message = { "msg_x" })
            this[VoiceCallClient] = testVoiceCall(completeFunction = { id, out -> completed.update { it + (id to out) } })
        }
        crossStore.exhaustive = false
        crossStore.send(functionCall("del_cross"))
        crossStore.eventuallyReducing { completed.value.isNotEmpty() }
        crossStore.skipReceivedActions()
        assertEquals(1, completed.value.size)
        assertTrue(completed.value[0].second, completed.value[0].second.contains("belongs to another server"))
        assertEquals("Failed cross-server delegation releases its tail capture", "Check status.", crossStore.state.tail.capture().capture.userText)
        crossStore.finish()
        crossEffects.skipInFlightEffects()

        // 2. Stale cached Automatic session returning 404 clears cache, creates fresh "Voice" session, and succeeds.
        completed.set(emptyList())
        val serverKey = VoiceDelegatorWorkspace.serverID(server)
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.AutomaticSession)
        shared.set(VoiceSharedKeys.voiceDelegatorSessionIDs, mapOf(serverKey to "ses_deleted_voice"))
        val staleState = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Start fresh.", "u2")

        val staleEffects = detachedScope(coroutineContext)
        val staleStore = store(shared, staleState, staleEffects, now = 5_100) {
            this[ServerIDClient] = mockServerID(session = { "ses_fresh_voice" }, message = { "msg_fresh" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    if (sessionID == "ses_deleted_voice") throw OpenCodeClientError("Not found", statusCode = 404)
                    PromptContext.messageID.current() ?: "msg_fresh"
                },
                searchSessions = { _, _, _ -> emptyList() },
                listDirectory = { _, _ -> OpenCodeDirectoryListing(directory = "/Users/alex/workspace", folders = emptyList()) },
                createSession = { _, dir, id ->
                    OpenCodeSession(id = id ?: "ses_fresh_voice", serverTitle = "Untitled", projectID = "p", directory = dir, updatedAt = VoiceFeatureTestSupport.distantPast)
                },
                renameSession = { _, _, _ -> },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, sessionID, _ ->
                assertEquals("ses_fresh_voice", sessionID)
                "Recovered onto a new Voice session."
            })
            this[VoiceCallClient] = testVoiceCall(completeFunction = { id, out -> completed.update { it + (id to out) } })
        }
        staleStore.exhaustive = false
        staleStore.send(functionCall("del_recover"))
        staleStore.eventuallyReducing { completed.value.isNotEmpty() }
        assertEquals("ses_fresh_voice", shared.get(VoiceSharedKeys.voiceDelegatorSessionIDs)[serverKey])
        assertEquals("[COMPLETE] Recovered onto a new Voice session.", completed.value.firstOrNull()?.second)
        staleStore.finish()
        staleEffects.skipInFlightEffects()

        // 3. Active delegator session deleted mid-turn via SSE cancels the wait and reports deletion.
        completed.set(emptyList())
        val waitStream = CompletableDeferred<String?>()
        shared.set(VoiceSharedKeys.voiceDelegatorSessionIDs, mapOf(serverKey to "ses_live_voice"))
        val midDeleteState = liveDelegatorState(shared).recording(VoiceTranscriptTail.Role.user, "Long job.", "u3")

        val deleteEffects = detachedScope(coroutineContext)
        val deleteStore = store(shared, midDeleteState, deleteEffects, now = 5_200) {
            this[ServerIDClient] = mockServerID(session = { "ses_unused" }, message = { "msg_live" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, _, _, _ -> PromptContext.messageID.current() ?: "msg_live" },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, _, _ -> waitStream.await() })
            this[VoiceCallClient] = testVoiceCall(completeFunction = { id, out -> completed.update { it + (id to out) } })
        }
        deleteStore.exhaustive = false
        deleteStore.send(functionCall("del_mid"))
        deleteStore.receive(::isAdmitted, 5.seconds)

        deleteStore.send(VoiceFeature.Action.SessionDelegatorSessionDeleted("ses_live_voice"))
        deleteStore.eventuallyReducing { completed.value.isNotEmpty() }
        assertNull(shared.get(VoiceSharedKeys.voiceDelegatorSessionIDs)[serverKey])
        assertTrue(deleteStore.state.sessionDelegator.turns.isEmpty())
        assertEquals(1, completed.value.size)
        assertTrue(completed.value[0].second, completed.value[0].second.contains("The delegator session was deleted."))

        waitStream.complete(null)
        deleteStore.finish()
        deleteEffects.skipInFlightEffects()
    }

    // MARK: - Point 3: GPT-Live Wire Framing & 900 ms Action-Claim Backstop

    @Test fun sessionDelegatorAnswersBoundDelegationWithCommentaryAndUnboundSpeechAndClaimBackstopWithInstructions() = runTest {
        val harness = LiveHarness(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 5.seconds,
                liveAcknowledgementWait = Duration.ZERO,
                liveUnbackedClaimWait = VoiceCallTiming.sessionDelegatorUnbackedClaimWait,
                liveAppendQuiet = Duration.ZERO,
            ),
        )
        val events = EventIterator(harness.start(), backgroundScope)
        // The shared harness's start takes no such flag, so the call's config gets it before
        // anything is appended.
        harness.coordinator.serialized {
            harness.coordinator.config = harness.coordinator.config?.copy(unboundSpeechUsesInstructions = true)
        }
        val socket = harness.nextOpened()
        socket.yieldEvent(LiveServerEvent.SessionStarted(id = "live_sdel", expiresAt = null))
        assertEquals(VoiceCallEvent.Connected, events.next())

        // 1. Open delegation.created -> answered via session.commentary.append carrying delegation_id.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "What broke?", startMilliseconds = 0, endMilliseconds = 400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "What broke?", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_bound", target = "client", offsetMilliseconds = 400))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_bound", name = "delegate", arguments = RealtimeJSONs.obj()), events.next())
        harness.coordinator.completeFunction(callID = "del_bound", output = "[COMPLETE] The parser test broke.")
        assertTrue(waitUntil {
            socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == "del_bound" && it.content.contains("The parser test broke.") }
        })

        // 2. Unbound interim speech (ios_voice_say / async [Session update]) -> session.instructions.append with delegation_id: nil.
        harness.coordinator.announce("Say this brief status update aloud: Checking the parser.")
        assertTrue(waitUntil {
            socket.commands.any { it is LiveCommand.AppendInstructions && it.delegationID == null && it.content.contains("Checking the parser.") }
        })
        assertFalse(
            "gpt-live-1 rejects commentary without an active delegation_id; unbound speech must use instructions",
            socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == null },
        )

        // 3. Unbacked action claim ("Let me check the logs for you.") fires at 900 ms and answers via session.instructions.append.
        delay(2.seconds)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the server logs", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Check the server logs", final = true), events.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Let me check the logs for you.", startMilliseconds = 1_600, endMilliseconds = 2_000))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Let me check the logs for you."), events.next())
        harness.audioEvent(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Let me check the logs for you."), events.next())
        assertEquals(VoiceCallEvent.AudioInterrupted, events.next())
        harness.audioEvent(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, events.next())

        delay(899.milliseconds)
        delay(1.milliseconds)
        assertEquals(
            VoiceCallEvent.FunctionCall(
                callID = "client_claim_1",
                name = "delegate",
                arguments = RealtimeJSONs.obj("text" to RealtimeJSONs.string("Let me check the logs for you.")),
            ),
            events.next(),
        )

        harness.coordinator.completeFunction(callID = "client_claim_1", output = "[COMPLETE] The server logs are clean.")
        assertTrue(waitUntil {
            socket.commands.any { it is LiveCommand.AppendInstructions && it.delegationID == null && it.content.contains("The server logs are clean.") }
        })
        assertFalse(socket.commands.any { it is LiveCommand.AppendCommentary && it.delegationID == null })
        harness.coordinator.end()
    }
}
