package ai.opencode.feature.voice

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.admittingFirstTurn
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.liveDelegatorState
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.mockServerID
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.preserveSettings
import ai.opencode.feature.voice.VoiceSessionDelegatorTestSupport.store
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.PromptContext
import ai.opencode.server.ServerIDClient
import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The voice agent saying it's acting without a delegation, on a test clock: a loaded machine
 * stretching the wait for GPT-Live's own delegation can't make the call delegate a request twice.
 */
class VoiceClaimTests {
    private fun obj(vararg pairs: Pair<String, RealtimeJSON>) = RealtimeJSONs.obj(*pairs)

    private suspend fun TestScope.claimCall(): VoiceClockedCall = clockedCall(
        VoiceCallTiming(
            rotateAfter = 3_000.0,
            forceRotateAfter = 3_500.0,
            retryDelays = listOf(0.01),
            liveDelegationSettle = Duration.ZERO,
            liveTranscriptSettle = 40.milliseconds,
            liveAcknowledgementWait = Duration.ZERO,
        ).copy(
            liveUnbackedClaimWait = 150.milliseconds,
            liveAnswerPressedAfter = 50.milliseconds,
        ),
    )

    /**
     * The call delegates the request itself, and its answer goes out without a delegation ID
     * GPT-Live never made.
     */
    @Test fun aClaimWithoutADelegationIsDelegatedByTheCall() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Open code iOS", final = true), iterator.next())
        call.advance(80)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Opening."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(149)
        assertFalse("Its own delegation may still follow", call.harness.callLogMarkers.any { it.contains("without delegating") })
        call.advance(1)
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string("Opening."))),
            iterator.next(),
        )
        assertTrue(call.harness.callLogMarkers.any { it.contains("said “Opening.” without delegating") })

        call.coordinator.completeFunction(callID = "client_claim_1", output = "[COMPLETE] Opened OPENCODE IOS.")
        call.waitFor { socket.commands.any { it is LiveCommand.AppendCommentary } }
        assertTrue(socket.commands.any {
            it is LiveCommand.AppendCommentary && it.delegationID == null &&
                it.content == "Result of the request you acknowledged with “Opening.”: Opened OPENCODE IOS."
        })
        call.coordinator.end()
    }

    /**
     * A claim GPT-Live backed with its own delegation, or one with nothing
     * asked since the last delegation, is left alone.
     */
    @Test fun aBackedClaimIsNotDelegatedAgain() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Open code iOS", startMilliseconds = 1_000, endMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Open code iOS", final = true), iterator.next())
        call.advance(80)
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_open", target = "client", offsetMilliseconds = 1_500))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_open", name = "delegate", arguments = obj()), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Opening."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        // Said again later with nothing new asked: still nothing to delegate.
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Checking.", startMilliseconds = 5_000, endMilliseconds = 5_400))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Checking."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = "Checking."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(300)
        assertFalse(call.harness.callLogMarkers.any { it.contains("without delegating") })
        call.coordinator.end()
        while (true) {
            val event = iterator.next() ?: break
            if (event is VoiceCallEvent.FunctionCall) fail("Delegated again: $event")
        }
    }

    /**
     * "Checking." acknowledged a delegation GPT-Live had made, the user kept talking over it, and
     * the call delegated "Checking." again, so GAME ENGINE got the request twice. Either order of
     * the acknowledgement and the delegation event.
     */
    @Test fun anAcknowledgementTheUserTalksOverIsNotDelegatedAgain() {
        for (acknowledgedFirst in listOf(false, true)) anAcknowledgementTheUserTalksOverIsNotDelegatedAgain(acknowledgedFirst)
    }

    private fun anAcknowledgementTheUserTalksOverIsNotDelegatedAgain(acknowledgedFirst: Boolean) = runTest {
        val call = claimCall()
        // Every event so far.
        val received = { call.events.remaining() }
        call.userSays("Make Dark Shroud the default", from = 1_000, to = 1_800)
        call.advance(80)
        if (!acknowledgedFirst) call.delegates("del_shroud", at = 1_800)
        call.agentSpeaks("Checking.", from = 2_000, to = 2_400)
        if (acknowledgedFirst) {
            call.advance(20)
            call.delegates("del_shroud", at = 1_800)
        }
        call.advance(100)
        call.userSays("Maybe a little wobble too", from = 2_200, to = 3_000)
        call.advance(80)
        call.playbackDrains()
        call.advance(40)
        call.waitFor { VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Checking.") in received() }
        call.advance(300)
        assertFalse("acknowledgedFirst: $acknowledgedFirst", call.harness.callLogMarkers.any { it.contains("without delegating") })
        val delegations = received().filterIsInstance<VoiceCallEvent.FunctionCall>()
        assertEquals("acknowledgedFirst: $acknowledgedFirst", listOf(VoiceCallEvent.FunctionCall(callID = "del_shroud", name = "delegate", arguments = obj())), delegations)
        call.coordinator.end()
    }

    /**
     * Words the user said before the claim, transcribed after it started,
     * are still what it claims to act on.
     */
    @Test fun aClaimOnWordsTranscribedLateIsStillDelegated() = runTest {
        val call = claimCall()
        val received = { call.events.remaining() }
        call.socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 2_000, endMilliseconds = 2_400))
        call.userSays("Open code iOS", from = 1_000, to = 1_500)
        call.advance(40)
        call.waitFor { VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Opening.") in received() }
        call.advance(150)
        call.waitFor { received().any { it is VoiceCallEvent.FunctionCall } }
        assertTrue(
            VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string("Opening."))) in received(),
        )
        call.coordinator.end()
    }

    private fun isDelegatorFinished(action: VoiceFeature.Action) = action is VoiceFeature.Action.DelegatorFinished

    @Test fun unboundAnswerUnderSessionDelegatorCarriesExplicitSpeakDirective() = runTest {
        val call = VoiceClockedCall.start(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 5.seconds,
                liveAcknowledgementWait = Duration.ZERO,
            ).copy(
                liveUnbackedClaimWait = VoiceCallTiming.sessionDelegatorUnbackedClaimWait,
                liveAppendQuiet = Duration.ZERO,
            ),
            unboundSpeechUsesInstructions = true,
        )
        val socket = call.socket
        val iterator = call.events

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Check the tests", startMilliseconds = 0, endMilliseconds = 400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Check the tests", final = true), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Let me check the tests.", startMilliseconds = 500, endMilliseconds = 900))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Let me check the tests."), iterator.next())
        call.harness.audioEvent(VoiceAudioEvent.interrupted)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Let me check the tests."), iterator.next())
        assertEquals(VoiceCallEvent.AudioInterrupted, iterator.next())
        call.harness.audioEvent(VoiceAudioEvent.resumed)
        assertEquals(VoiceCallEvent.AudioResumed, iterator.next())

        call.advance(900)
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string("Let me check the tests."))),
            iterator.next(),
        )

        call.coordinator.completeFunction(callID = "client_claim_1", output = "[COMPLETE] All 42 tests passed.")
        call.coordinator.updateInstructions(full = "Base instructions", delta = "[Context] Viewing HOME")

        val instructions = socket.commands.mapNotNull { (it as? LiveCommand.AppendInstructions)?.takeIf { command -> command.delegationID == null }?.content }
        assertEquals(2, instructions.size)
        assertEquals("${LiveCallCoordinator.spokenDirectiveLead}All 42 tests passed.", instructions[0])
        assertFalse(instructions[0].contains(LiveCallCoordinator.claimLeadPrefix))
        assertEquals("[Context] Viewing HOME", instructions[1])
        assertFalse(instructions[1].startsWith(LiveCallCoordinator.spokenDirectiveLead))

        call.coordinator.end()
    }

    @Test fun builtInDelegatorExecutesClientClaimWhenCaptureIsEmpty() = runBlocking<Unit> {
        val shared = preserveSettings()
        val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
        val session = OpenCodeSession(id = "ses_ios", serverTitle = "OPENCODE IOS", projectID = "p", directory = "/tmp/ios", updatedAt = VoiceFeatureTestSupport.distantPast)
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.BuiltIn)
        shared.set(SharedKeys.openAIAPIKey, "sk-test")
        val voice = shared.read { VoiceFeature.State(server, session) }
            .withPhase(VoiceFeature.State.Phase.Live)
            .copy(usesDelegator = true)

        val receivedInput = Locked(listOf<VoiceDelegator.InputItem>())
        val completed = Locked(listOf<String>())
        val effects = detachedScope(coroutineContext)
        val store = store(shared, voice, effects, now = 0) {
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
                receivedInput.update { input }
                VoiceDelegator.Response(id = "resp_claim", calls = emptyList(), text = "The server logs are clean.")
            }
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { _, output -> completed.update { it + output } },
            )
        }

        store.send(
            VoiceFeature.Action.CallEvent(
                VoiceCallEvent.FunctionCall(
                    callID = "client_claim_1",
                    name = "delegate",
                    arguments = obj("text" to RealtimeJSONs.string("Let me check the logs.")),
                ),
            ),
        ) {
            it.copy(replyWatch = it.replyWatch.noteUserActed(), delegatorWork = it.delegatorWork + "client_claim_1")
                .stepping(ReplyWatch.Event.DelegatorStarted(requestID = "client_claim_1"))
        }
        store.receive(::isDelegatorFinished, timeout = 5.seconds) {
            it.copy(delegatorWork = it.delegatorWork.filter { id -> id != "client_claim_1" }, delegatorResponseID = "resp_claim")
                .stepping(ReplyWatch.Event.DelegatorFinished(requestID = "client_claim_1", spoken = "The server logs are clean."))
        }
        store.finish(timeout = 5.seconds)

        val userTexts = receivedInput.value.mapNotNull { (it as? VoiceDelegator.InputItem.User)?.text }
        assertTrue(userTexts.toString(), userTexts.any { it.contains("Voice promised: \"Let me check the logs.\"") })
        assertEquals(listOf(VoiceHandoff.completion("The server logs are clean.")), completed.value)
        effects.skipInFlightEffects()
    }

    @Test fun outcomeClaimDetectorMatchesFirstPersonActionsAndDoneClauses() {
        assertTrue(VoiceActionClaim.isOutcomeClaim("I took your workout clothes out and put in your running shoes."))
        assertTrue(VoiceActionClaim.isOutcomeClaim("Yeah, I added the pad see ew to your cart."))
        assertTrue(VoiceActionClaim.isOutcomeClaim("I've sent that over, and it's done."))
        assertTrue(VoiceActionClaim.isOutcomeClaim("That went through."))
        assertTrue(VoiceActionClaim.isOutcomeClaim("We just booked the flight and ordered dinner."))
        assertTrue(VoiceActionClaim.isOutcomeClaim("I removed the draft, moved the note, deleted the duplicate, texted Alex, and emailed Sam."))

        assertFalse(VoiceActionClaim.isOutcomeClaim("Only to Swift."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("Just to Settings."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("Just to Olive."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("I haven't added it yet."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("Nothing was sent."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("I checked the README and looked at the logs."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("I'm adding it to your cart now."))
        assertFalse(VoiceActionClaim.isOutcomeClaim("If you confirm, I'll send it."))

        assertFalse(VoiceActionClaim.correctsVoice("VERIFIED"))
        assertFalse(VoiceActionClaim.correctsVoice("[COMPLETE] VERIFIED"))
        assertFalse(VoiceActionClaim.correctsVoice("   "))
        assertTrue(VoiceActionClaim.correctsVoice("[COMPLETE] Confirmed, it also went to Sam."))
    }

    @Test fun packingOutcomeClaimVerifiesOnceAndSuppressesOnlyExactVerified() = runTest {
        val call = VoiceClockedCall.start(
            this,
            VoiceCallTiming(
                rotateAfter = 3_000.0,
                forceRotateAfter = 3_500.0,
                retryDelays = listOf(0.01),
                liveDelegationSettle = Duration.ZERO,
                liveTranscriptSettle = 40.milliseconds,
                liveAcknowledgementWait = Duration.ZERO,
            ).copy(
                liveUnbackedClaimWait = 150.milliseconds,
                liveAppendQuiet = Duration.ZERO,
            ),
            unboundSpeechUsesInstructions = true,
        )
        val socket = call.socket
        val iterator = call.events

        // Packing call: false swap claim triggers a single deduped outcome check and speaks the correction without verifyLead framing.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Swap workout clothes for running shoes", startMilliseconds = 100, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Swap workout clothes for running shoes", final = true), iterator.next())
        val packingClaim = "I took your workout clothes out and put in your running shoes."
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = packingClaim, startMilliseconds = 600, endMilliseconds = 1_100))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = packingClaim), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = packingClaim), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(150)
        val expectedPackingPrompt = VoiceActionClaim.verifyPrompt(question = "Swap workout clothes for running shoes", claim = packingClaim)
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_verify_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string(expectedPackingPrompt))),
            iterator.next(),
        )
        call.coordinator.completeFunction(
            callID = "client_verify_1",
            output = "[COMPLETE] The packing list still has workout clothes; nothing was changed yet.",
        )

        // Second turn: completed-action claim whose verification returns exact VERIFIED stays silent.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Did that go out?", startMilliseconds = 4_000, endMilliseconds = 4_400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Did that go out?", final = true), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "That went through.", startMilliseconds = 4_500, endMilliseconds = 4_800))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "That went through."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = "That went through."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(150)
        val expectedSecondPrompt = VoiceActionClaim.verifyPrompt(question = "Did that go out?", claim = "That went through.")
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_verify_2", name = "delegate", arguments = obj("text" to RealtimeJSONs.string(expectedSecondPrompt))),
            iterator.next(),
        )
        call.coordinator.completeFunction(callID = "client_verify_2", output = "[COMPLETE] VERIFIED")

        val instructions = socket.commands.mapNotNull { (it as? LiveCommand.AppendInstructions)?.takeIf { command -> command.delegationID == null }?.content }
        assertEquals(
            listOf("${LiveCallCoordinator.spokenDirectiveLead}The packing list still has workout clothes; nothing was changed yet."),
            instructions,
        )
        assertFalse(instructions[0].contains(LiveCallCoordinator.verifyLead))

        call.coordinator.end()
    }

    /**
     * A past call's tail line ("Voice: No. Just to Olive.") is dropped at the source by continuity
     * stripping and mid-call retarget, so it's never replayed.
     */
    @Test fun oliveCallPastVoiceLinesAreNotReplayedAcrossCallsOrRetarget() {
        val shared = preserveSettings()
        val server = OpenCodeServer(url = "https://example.test", username = "", password = "")
        val ios = OpenCodeSession(id = "ses_olive_a", serverTitle = "OPENCODE IOS", projectID = "p", directory = "/tmp/ios", updatedAt = VoiceFeatureTestSupport.distantPast)
        val hex = OpenCodeSession(id = "ses_olive_b", serverTitle = "HEX", projectID = "p", directory = "/tmp/hex", updatedAt = VoiceFeatureTestSupport.distantPast)
        val key = "${server.id}:${hex.id}"

        val pastOliveTail = listOf(
            "user: Did you check notifications?",
            "assistant: And that text to Olive went through, delivered.",
            "Voice: No. Just to Olive.",
        )
        val record = VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(100), tail = emptyList(), log = emptyList())
            .appending(tail = pastOliveTail, log = pastOliveTail, endedAt = Instant.ofEpochSecond(200))
        assertEquals(listOf("user: Did you check notifications?"), record.tail)

        // Even if an older persisted record still held the raw past-call Voice lines, neither a new call nor a mid-call retarget replays them.
        shared.set(
            VoiceSharedKeys.voiceTranscripts,
            mapOf(key to VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(200), tail = pastOliveTail, log = emptyList())),
        )
        val freshCall = shared.read { VoiceFeature.State(server, hex) }
        assertEquals(listOf("user: Did you check notifications?"), freshCall.previousCallTail)
        val built = VoiceInstructions.build(freshCall.instructionsContext, engine = VoiceEngine.gptLive)
        assertFalse(built.contains("Olive"))
        assertFalse(built.contains("went through"))

        val activeCall = shared.read {
            VoiceFeature.State(server, ios)
                .withPhase(VoiceFeature.State.Phase.Live)
                .copy(connectedAt = Instant.ofEpochSecond(300))
                .retarget(hex, reason = "you opened it", endedAt = Instant.ofEpochSecond(310))
        }
        assertTrue(activeCall.previousCallTail.isEmpty())
        assertFalse(VoiceInstructions.buildRetarget(activeCall.instructionsContext, engine = VoiceEngine.gptLive).contains("Olive"))
    }

    /** Ordinary capitalised prepositional answers must not trigger a verify. */
    @Test fun ordinaryCapitalisedPrepositionalAnswersDoNotTriggerVerify() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Where does this apply?", startMilliseconds = 100, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Where does this apply?", final = true), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Only to Swift.", startMilliseconds = 600, endMilliseconds = 900))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "Only to Swift."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "Only to Swift."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(300)

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Where did the toggle move?", startMilliseconds = 4_000, endMilliseconds = 4_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Where did the toggle move?", final = true), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Just to Settings.", startMilliseconds = 4_600, endMilliseconds = 4_900))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Just to Settings."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = "Just to Settings."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(300)

        assertFalse(call.harness.callLogMarkers.any { it.contains("verifying silently") })
        call.coordinator.end()
        while (true) {
            val event = iterator.next() ?: break
            if (event is VoiceCallEvent.FunctionCall) fail("Unexpected verify delegation: $event")
        }
    }

    @Test fun openDelegationDoesNotSkipOutcomeCheck() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Add pad see ew to my cart", startMilliseconds = 100, endMilliseconds = 500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Add pad see ew to my cart", final = true), iterator.next())
        socket.yieldEvent(LiveServerEvent.DelegationCreated(id = "del_cart", target = "client", offsetMilliseconds = 500))
        assertEquals(VoiceCallEvent.FunctionCall(callID = "del_cart", name = "delegate", arguments = obj()), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "On it, asking now.", startMilliseconds = 600, endMilliseconds = 900))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "On it, asking now."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "On it, asking now."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())

        // `del_cart` is still open when the user asks and Voice falsely claims it finished.
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Did you add the pad see ew?", startMilliseconds = 4_000, endMilliseconds = 4_500))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-2", text = "Did you add the pad see ew?", final = true), iterator.next())
        val cartClaim = "Yep, I added the pad see ew to your cart."
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = cartClaim, startMilliseconds = 4_600, endMilliseconds = 5_000))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = cartClaim), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = cartClaim), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())

        call.advance(150)
        val expectedPrompt = VoiceActionClaim.verifyPrompt(question = "Did you add the pad see ew?", claim = cartClaim)
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_verify_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string(expectedPrompt))),
            iterator.next(),
        )
        call.coordinator.end()
    }

    @Test fun outcomeVerifyCancelsOnNewerUserWordsAndPreservesWatermarks() = runTest {
        val call = claimCall()
        val socket = call.socket
        val iterator = call.events

        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = "Did you send the note?", startMilliseconds = 100, endMilliseconds = 400))
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Did you send the note?", final = true), iterator.next())
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "I sent the note.", startMilliseconds = 500, endMilliseconds = 800))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-1", delta = "I sent the note."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-1", text = "I sent the note."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())

        // Newer user words arrive during the 150ms wait -> cancels `client_verify_`, keeps `live-u-1` undelegated (480ms > 200ms), and subsequent unbacked action claim still delegates.
        call.advance(80)
        socket.yieldEvent(LiveServerEvent.InputTranscriptDelta(delta = " Actually open settings first", startMilliseconds = 880, endMilliseconds = 1_300))
        assertEquals(
            VoiceCallEvent.UserTranscript(itemID = "live-u-1", text = "Did you send the note? Actually open settings first", final = true),
            iterator.next(),
        )
        call.advance(200)
        socket.yieldEvent(LiveServerEvent.OutputTranscriptDelta(delta = "Opening.", startMilliseconds = 1_400, endMilliseconds = 1_700))
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "live-a-2", delta = "Opening."), iterator.next())
        call.advance(40)
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "live-a-2", text = "Opening."), iterator.next())
        assertEquals(VoiceCallEvent.AssistantPlaybackFinished, iterator.next())
        call.advance(150)
        assertEquals(
            VoiceCallEvent.FunctionCall(callID = "client_claim_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string("Opening."))),
            iterator.next(),
        )
        call.coordinator.end()
    }

    /**
     * The session delegator's `VERIFIED` settles with no ReplyWatch `"finished: VERIFIED"` notice
     * and leaves `state.tail` untouched.
     */
    @Test fun sessionDelegatorVerifiedSettlesSilentlyAndPreservesTailWatermark() = runBlocking<Unit> {
        val shared = preserveSettings()
        val now = Instant.ofEpochSecond(10_000)
        val server = OpenCodeServer(url = "https://example.test", username = "", password = "")
        val serverKey = VoiceDelegatorWorkspace.serverID(server)
        val viewed = OpenCodeSession(id = "ses_view", serverTitle = "Session View", projectID = "proj_1", directory = "/Users/alex/app", updatedAt = now)
        shared.set(VoiceSharedKeys.voiceDelegatorChoice, VoiceDelegatorChoice.AutomaticSession)
        shared.set(VoiceSharedKeys.voiceDelegatorSessionIDs, mapOf(serverKey to "ses_voice"))

        val initial = liveDelegatorState(shared, server, viewed)
            .recording(VoiceTranscriptTail.Role.user, "Also pack a charger", id = "u_pending")

        val verifyNote = VoiceActionClaim.verifyPrompt(question = "Did that go out?", claim = "That went through.")
        val expectedContext = """[Phone context] Call on session "Session View" (ses_view) · """ + VoiceScreenVisibility.notLooking.unseenNote!!
        val expectedPrompt = VoiceSessionDelegator.formatPrompt(
            deltaLines = emptyList(),
            promisedClaim = verifyNote,
            contextLine = expectedContext,
            includeActiveCallHeader = true,
        )
        val expectedAdmission = VoiceSessionDelegator.Admission(
            sessionID = "ses_voice",
            title = "Voice",
            promptID = "msg_verify",
            promptText = expectedPrompt,
            explained = true,
            contextLine = expectedContext,
            cachedAutomaticSessionID = "ses_voice",
        )

        val completed = Locked(listOf<Pair<String, String>>())
        val announced = Locked(listOf<String>())
        val sentPrompts = Locked(listOf<String>())

        val effects = detachedScope(coroutineContext)
        val store = store(shared, initial, effects, now = 10_000) {
            this[ServerIDClient] = mockServerID(session = { "ses_unused" }, message = { "msg_verify" })
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                renameSession = { _, _, _ -> },
                moveToBackground = { _, _ -> },
                sendDeliveredPrompt = { _, _, text, _ ->
                    sentPrompts.update { it + text }
                    PromptContext.messageID.current() ?: "msg_verify"
                },
            )
            this[VoiceWorkClient] = testVoiceWork(awaitReply = { _, sessionID, _ ->
                assertEquals("ses_voice", sessionID)
                "VERIFIED"
            })
            this[VoiceCallClient] = testVoiceCall(
                completeFunction = { id, out -> completed.update { it + (id to out) } },
            ).copy(announce = { _, _, text -> announced.update { it + text } })
        }

        store.send(
            VoiceFeature.Action.CallEvent(
                VoiceCallEvent.FunctionCall(callID = "client_verify_1", name = "delegate", arguments = obj("text" to RealtimeJSONs.string(verifyNote))),
            ),
        ) {
            // `userTurns` stays 0 (`noteUserActed()` skipped) and `tail` stays uncaptured.
            it.copy(
                sessionDelegator = it.sessionDelegator.copy(
                    activeSessionID = "ses_voice",
                    turns = listOf(
                        VoiceSessionDelegator.State.Turn(
                            callID = "client_verify_1",
                            capture = VoiceTranscriptTail.Capture.empty,
                            promisedClaim = verifyNote,
                            sessionID = "ses_voice",
                            admitting = true,
                            saidTurn = 0,
                        ),
                    ),
                ),
                delegatorWork = listOf("client_verify_1"),
            ).stepping(ReplyWatch.Event.DelegatorStarted(requestID = "client_verify_1"))
        }
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID = "ses_voice")), timeout = 5.seconds)
        store.receive(VoiceFeature.Action.SessionDelegatorAdmitted(callID = "client_verify_1", admission = expectedAdmission), timeout = 5.seconds) {
            it.admittingFirstTurn("ses_voice", "Voice", "msg_verify", saidTurn = 0, contextLine = expectedContext, now = now)
        }
        store.receive(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_verify", reply = "VERIFIED")), timeout = 5.seconds) {
            val succeeded = it.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_verify", reply = "VERIFIED"))
            succeeded.copy(
                sessionDelegator = succeeded.sessionDelegator.copy(turns = emptyList(), activeSessionID = null),
                delegatorWork = emptyList(),
                replyWatch = succeeded.replyWatch.removeCompleted(setOf("msg_verify")),
            )
                .stepping(ReplyWatch.Event.DelegatorAnswered(sessionID = "ses_voice", reply = null, promptIDs = listOf("msg_verify"), requestID = "client_verify_1"))
                .stepping(ReplyWatch.Event.DelegatorFinished(requestID = "client_verify_1", spoken = "VERIFIED"))
        }
        store.finish(timeout = 5.seconds)

        assertEquals(listOf(expectedPrompt), sentPrompts.value)
        assertEquals(1, completed.value.size)
        assertEquals("client_verify_1", completed.value.firstOrNull()?.first)
        assertEquals("", completed.value.firstOrNull()?.second)
        assertTrue("Session-delegator VERIFIED must never emit a ReplyWatch notice", announced.value.isEmpty())
        assertTrue(store.state.replyWatch.completed.isEmpty())
        assertTrue(store.state.replyWatch.deferredNotices.isEmpty())
        assertEquals("client_verify_N must not consume pending user words", "Also pack a charger", store.state.tail.capture().capture.userText)
        effects.skipInFlightEffects()
    }
}
