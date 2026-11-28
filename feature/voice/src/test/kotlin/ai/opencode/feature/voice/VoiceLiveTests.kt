package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `LiveProtocolTests`. */
class LiveProtocolTests {
    private fun s(value: String) = RealtimeJSONs.string(value)

    @Test fun sessionStartEncodesClientDelegationAudioFormatAndSeededInput() {
        val config = LiveSessionConfig(
            instructions = "Be concise.",
            input = listOf(
                LiveInputMessage(LiveInputMessage.Role.developer, "Continuing an earlier call."),
                LiveInputMessage(LiveInputMessage.Role.user, "Check the upload test."),
                LiveInputMessage(LiveInputMessage.Role.assistant, "Looking now."),
            ),
        )
        val json = decodeJSON(LiveCommand.SessionStart(config, eventID = "evt_start").encoded())
        assertEquals(s("session.start"), json["type"])
        assertEquals(s("evt_start"), json["event_id"])
        val session = json["session"]!!
        assertEquals(s("gpt-live-1.1"), session["model"])
        assertEquals(s("Be concise."), session["instructions"])
        assertEquals(s("audio/pcm"), session["audio"]?.get("format")?.get("type"))
        assertEquals(RealtimeJSONs.number(24_000), session["audio"]?.get("format")?.get("rate"))
        assertEquals(s("marin"), session["audio"]?.get("output")?.get("voice"))
        assertEquals(s("client"), session["delegation"]?.get("type"))

        val input = session["input"] as? JsonArray
        if (input == null || input.size != 3) throw AssertionError("Expected 3 seeded input messages")
        assertEquals(s("developer"), input[0]["role"])
        assertEquals(RealtimeJSONs.array(listOf(RealtimeJSONs.obj("type" to s("input_text"), "text" to s("Continuing an earlier call.")))), input[0]["content"])
        assertEquals(s("user"), input[1]["role"])
        assertEquals(RealtimeJSONs.array(listOf(RealtimeJSONs.obj("type" to s("input_text"), "text" to s("Check the upload test.")))), input[1]["content"])
        assertEquals(s("assistant"), input[2]["role"])
        assertEquals(RealtimeJSONs.array(listOf(RealtimeJSONs.obj("type" to s("output_text"), "text" to s("Looking now.")))), input[2]["content"])
    }

    @Test fun commandsEncodeDocumentedWireShapes() {
        // Odd byte count is aligned down to complete 16-bit samples.
        val audio = decodeJSON(LiveCommand.AppendAudio(byteArrayOf(1, 2, 3)).encoded())
        assertEquals(s("session.input_audio.append"), audio["type"])
        assertEquals(s(Base64.getEncoder().encodeToString(byteArrayOf(1, 2))), audio["audio"])

        val thinking = decodeJSON(LiveCommand.AppendThinking(eventID = "th_1", delegationID = "item_123", content = "Running tests.").encoded())
        assertEquals(s("session.thinking.append"), thinking["type"])
        assertEquals(s("th_1"), thinking["event_id"])
        assertEquals(s("item_123"), thinking["delegation_id"])
        assertEquals(s("Running tests."), thinking["content"])

        val commentary = decodeJSON(LiveCommand.AppendCommentary(eventID = "cm_1", delegationID = "item_123", content = "All tests pass.").encoded())
        assertEquals(s("session.commentary.append"), commentary["type"])
        assertEquals(s("item_123"), commentary["delegation_id"])
        assertEquals(s("All tests pass."), commentary["content"])

        val instructions = decodeJSON(LiveCommand.AppendInstructions(eventID = "in_1", delegationID = null, content = "Stay brief.").encoded())
        assertEquals(s("session.instructions.append"), instructions["type"])
        assertEquals(JsonNull, instructions["delegation_id"])
        assertEquals(s("Stay brief."), instructions["content"])

        val mute = decodeJSON(LiveCommand.Mute(eventID = "m_1").encoded())
        assertEquals(RealtimeJSONs.obj("type" to s("session.input_audio.mute"), "event_id" to s("m_1")), mute)

        val unmute = decodeJSON(LiveCommand.Unmute(eventID = "u_1").encoded())
        assertEquals(RealtimeJSONs.obj("type" to s("session.input_audio.unmute"), "event_id" to s("u_1")), unmute)

        val close = decodeJSON(LiveCommand.Close(eventID = "cl_1").encoded())
        assertEquals(RealtimeJSONs.obj("type" to s("session.close"), "event_id" to s("cl_1")), close)
    }

    @Test fun decodesDocumentedServerEvents() {
        val cases: List<Pair<String, LiveServerEvent>> = listOf(
            """{"type":"session.started","event_id":"e1","client_event_id":"evt_start","session":{"id":"live_1","expires_at":1791314434}}""" to
                LiveServerEvent.SessionStarted(id = "live_1", expiresAt = Instant.ofEpochSecond(1_791_314_434)),
            """{"type":"session.input_transcript.delta","event_id":"e2","delta":"What is","start_ms":1000,"end_ms":1200}""" to
                LiveServerEvent.InputTranscriptDelta(delta = "What is", startMilliseconds = 1_000, endMilliseconds = 1_200),
            """{"type":"session.output_transcript.delta","event_id":"e3","delta":" Checking","start_ms":1400,"end_ms":1600}""" to
                LiveServerEvent.OutputTranscriptDelta(delta = " Checking", startMilliseconds = 1_400, endMilliseconds = 1_600),
            """{"type":"session.output_audio.delta","delta":"AAD//w==","start_ms":1400,"end_ms":1500}""" to
                LiveServerEvent.OutputAudio(pcm = byteArrayOf(0, 0, -1, -1), startMilliseconds = 1_400, endMilliseconds = 1_500),
            """{"type":"session.delegation.created","event_id":"e4","offset_ms":1000,"delegation":{"id":"item_9tA","type":"delegation","target":"client"}}""" to
                LiveServerEvent.DelegationCreated(id = "item_9tA", target = "client", offsetMilliseconds = 1_000),
            """{"type":"session.input_audio.muted","event_id":"e5","client_event_id":"m_1"}""" to
                LiveServerEvent.InputAudioMuted(clientEventID = "m_1"),
            """{"type":"session.input_audio.unmuted","event_id":"e6","client_event_id":"u_1"}""" to
                LiveServerEvent.InputAudioUnmuted(clientEventID = "u_1"),
            """{"type":"session.thinking.appended","event_id":"e7","client_event_id":"th_1","start_ms":600,"end_ms":800}""" to
                LiveServerEvent.ContextAppended(kind = LiveAppendKind.thinking, clientEventID = "th_1", startMilliseconds = 600, endMilliseconds = 800),
            """{"type":"session.commentary.appended","event_id":"e8","client_event_id":"cm_1","start_ms":800,"end_ms":1000}""" to
                LiveServerEvent.ContextAppended(kind = LiveAppendKind.commentary, clientEventID = "cm_1", startMilliseconds = 800, endMilliseconds = 1_000),
            """{"type":"session.instructions.appended","event_id":"e9","client_event_id":"in_1","start_ms":800,"end_ms":1000}""" to
                LiveServerEvent.ContextAppended(kind = LiveAppendKind.instructions, clientEventID = "in_1", startMilliseconds = 800, endMilliseconds = 1_000),
            """{"type":"session.usage.updated","event_id":"e10","usage":{"seconds":12},"context_window":{"usage_ratio":0.42}}""" to
                LiveServerEvent.UsageUpdated(seconds = 12, contextUsageRatio = 0.42),
            """{"type":"session.closed","event_id":"e11","reason":"expired","usage":{"seconds":7200}}""" to
                LiveServerEvent.SessionClosed(reason = LiveCloseReason.Expired, usageSeconds = 7_200),
            """{"type":"error","event_id":"e12","error":{"type":"invalid_request_error","code":"immutable_field_update","message":"Cannot change delegation.","param":"session.delegation.type","client_event_id":"evt_u"}}""" to
                LiveServerEvent.Error(
                    LiveServerError(
                        message = "Cannot change delegation.",
                        code = "immutable_field_update",
                        type = "invalid_request_error",
                        clientEventID = "evt_u",
                    ),
                ),
        )
        for ((json, expected) in cases) {
            assertEquals(expected, LiveServerEvent.decode(json.toByteArray()))
        }
    }

    @Test fun commentaryChunkerStripsCompletionPrefixCollapsesCodeAndRespectsTokenLimit() {
        val raw = """
            [COMPLETE] Fixed the flaky upload test by binding an ephemeral port.

            ```swift
            let port = try server.bindEphemeral()
            ```

            All 42 tests in the suite now pass.
        """.trimIndent()
        val chunks = LiveContentChunker.commentaryChunks(raw)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("Fixed the flaky upload test"))
        assertTrue(chunks[0].contains("All 42 tests in the suite now pass."))
        assertFalse(chunks[0].contains("[COMPLETE]"))
        assertFalse(chunks[0].contains("bindEphemeral"))

        val longSentence = "The test suite verified another module cleanly. ".repeat(50)
        val split = LiveContentChunker.commentaryChunks("[COMPLETE] $longSentence", maxCharacters = 400)
        assertTrue(split.size > 2)
        assertTrue(split.all { it.length <= 400 })
        assertEquals("Every chunk is kept", longSentence.trim(), split.joinToString(" "))
    }

    @Test fun liveInstructionsFollowPromptingGuideTemplate() {
        val context = VoiceInstructions.Context(
            sessionTitle = "Voice lab",
            directoryName = "opencode-ios",
            recentMessages = listOf("user: check the build"),
            previousTail = listOf("user: hello", "assistant: hi there"),
            previousEndedAt = Instant.ofEpochSecond(0),
        )
        val prompt = VoiceInstructions.build(context, engine = VoiceEngine.gptLive)
        assertTrue(prompt.contains("Backchannel policy:"))
        assertTrue(prompt.contains("Interruption policy:"))
        assertTrue(prompt.contains("Delegation policy:"))
        assertTrue(prompt.contains("Tools:"))
        assertTrue(prompt.contains("Delegate when:"))
        assertTrue(prompt.contains("Do not delegate when:"))
        assertTrue(prompt.contains("\"Voice lab\""))
        assertTrue(prompt.contains("\"opencode-ios\""))
        assertTrue(prompt.contains("# Session So Far"))
        assertTrue(prompt.contains("# Previous Voice Call"))
        assertTrue(prompt.contains("Always delegate any check on whether a session has responded, finished, or is still working"))

        val managed = context.copy(managesSessions = true)
        val managedPrompt = VoiceInstructions.build(managed, engine = VoiceEngine.gptLive)
        assertTrue(managedPrompt.contains("You can message, read, or check any session in place without moving the user's screen; only open a session or draft when the user asks to view or switch to it."))
        assertTrue(VoiceDelegator.instructions.contains("Do NOT call open_session just to message, read, or check another session"))
        assertTrue(VoiceDelegator.instructions.contains("always call read_session (or list_sessions) in this turn"))
    }

    @Test fun quietGateNeverCutsOffSpeechAndHasNoCap() {
        assertEquals(900.milliseconds, VoiceCallTiming.live.liveAppendQuiet)
        assertEquals(1_500.milliseconds, VoiceCallTiming.live.liveAppendReplyWait)
        assertEquals("Nothing forces a held update out while the user talks", Duration.ZERO, VoiceCallTiming.live.liveAppendMaxHold)
        assertEquals(2.seconds, VoiceCallTiming.live.liveUserQuietGap)
        assertEquals(0.35f, VoiceCallTiming.live.liveMicVoiceLevel)
        assertEquals(600.milliseconds, VoiceCallTiming.live.liveBargeIn)

        val t0 = Duration.ZERO

        // 1. Assistant's 5 s response with a playback drain: the full 900 ms
        // post-speech `quietWindow` is still enforced after playback drains at 5 s.
        var assistantGate = VoiceCallTiming.live.quietGate
        assistantGate = assistantGate.noteAssistantSpeaking(true, t0)
        assistantGate = assistantGate.noteHeld(t0)
        assertEquals(900.milliseconds, assistantGate.delay(t0 + 3.seconds))
        assistantGate = assistantGate.noteAssistantSpeaking(false, t0 + 5.seconds)
        assertEquals(
            "Draining after a 5 s assistant reply must still enforce the 900 ms post-speech quiet window",
            900.milliseconds, assistantGate.delay(t0 + 5.seconds),
        )
        assertEquals(400.milliseconds, assistantGate.delay(t0 + 5_500.milliseconds))
        assertNull(assistantGate.delay(t0 + 5_900.milliseconds))

        // 2. User's continuous 6 s utterance holds throughout, then the 2 s
        // quiet gap applies before releasing at 8 s.
        var userGate = VoiceCallTiming.live.quietGate
        userGate = userGate.noteUserSpoke(t0)
        userGate = userGate.noteHeld(t0)
        for (step in 1..12) {
            val at = t0 + (step * 500).milliseconds
            userGate = userGate.noteUserSpoke(at)
            assertEquals("Continuous user speech at ${step * 500} ms holds", 2.seconds, userGate.delay(at))
        }
        assertEquals(1_500.milliseconds, userGate.delay(t0 + 6_500.milliseconds))
        assertNull(userGate.delay(t0 + 8_000.milliseconds))

        // 3. A dictation's transcript fragments 1.2 s apart for 20 s never release a held update.
        var dictation = VoiceCallTiming.live.quietGate
        dictation = dictation.noteUserSpoke(t0)
        dictation = dictation.noteHeld(t0)
        for (fragment in 1..16) {
            val at = t0 + (fragment * 1_200).milliseconds
            assertNotNull("Held between fragments at ${fragment * 1_200} ms", dictation.delay(at - 1.milliseconds))
            dictation = dictation.noteUserSpoke(at)
        }
        // The microphone hears them talk on with no fragment for 2.5 s.
        val lastFragment = t0 + 19_200.milliseconds
        dictation = dictation.noteUserVoice(lastFragment + 2_500.milliseconds)
        assertEquals(1_500.milliseconds, dictation.delay(lastFragment + 3_000.milliseconds))
        assertEquals("user speaking", dictation.holdReason(lastFragment + 3_000.milliseconds))
        assertNull("Out after 2 s of real quiet", dictation.delay(lastFragment + 4_500.milliseconds))
    }
}

class LiveRTCTests {
    @Test fun sessionRequestLeavesTheAudioFormatToSDPAndPreNegotiatesTheEventChannel() {
        val config = LiveSessionConfig(instructions = "Be concise.", input = listOf(LiveInputMessage(LiveInputMessage.Role.user, "Hi")))
        val json = decodeJSON(LiveRTC.requestBody(config = config, sdp = "v=0\r\n"))
        assertEquals(RealtimeJSONs.string("webrtc"), json["transport"]?.get("type"))
        assertEquals(RealtimeJSONs.string("v=0\r\n"), json["transport"]?.get("sdp"))
        assertEquals(RealtimeJSONs.number(4), json["transport"]?.get("dcid"))
        val session = json["session"]!!
        assertEquals(RealtimeJSONs.string("gpt-live-1.1"), session["model"])
        assertNull(session["audio"]?.get("format"))
        assertEquals(RealtimeJSONs.string("marin"), session["audio"]?.get("output")?.get("voice"))
        assertEquals(RealtimeJSONs.string("client"), session["delegation"]?.get("type"))
        assertNotNull(session["input"])
        assertNull("The HTTP request starts the session; no session.start", json["type"])
    }

    /**
     * One second later: `received` packets in, `lost` lost, `concealed` of
     * 48 000 samples concealed.
     */
    private fun step(
        last: LiveRTCLinkSample, received: Double = 50.0, lost: Double = 0.0, concealed: Double = 0.0,
        roundTrip: Double = 0.06, outboundLoss: Double = 0.0,
    ): LiveRTCLinkSample = LiveRTCLinkSample(
        path = "udp", roundTrip = roundTrip,
        packetsReceived = last.packetsReceived + received, packetsLost = last.packetsLost + lost,
        samplesReceived = last.samplesReceived + 48_000, samplesConcealed = last.samplesConcealed + concealed,
        jitter = 0.002, outboundLoss = outboundLoss,
    )

    @Test fun aCleanLinkStaysHealthy() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        repeat(20) {
            sample = step(sample, lost = 1.0, concealed = 400.0)
            assertNull(watch.add(sample))
        }
        assertFalse(watch.isPoor)
        assertTrue(watch.summary()?.startsWith("WebRTC · udp") == true)
    }

    @Test fun lossMakesItWeakAndThreeHealthySecondsRecoverIt() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        sample = step(sample, received = 40.0, lost = 10.0)
        assertNull("one lossy second is hidden by the jitter buffer", watch.add(sample))
        assertTrue("but the log still notes it", watch.summary()?.startsWith("⚠︎") == true)
        sample = step(sample, received = 40.0, lost = 10.0)
        assertEquals("two make it weak", true, watch.add(sample))
        sample = step(sample, received = 40.0, lost = 10.0)
        assertNull("reported once", watch.add(sample))
        sample = step(sample)
        assertNull(watch.add(sample))
        sample = step(sample)
        assertNull(watch.add(sample))
        sample = step(sample)
        assertEquals(false, watch.add(sample))
        assertFalse(watch.isPoor)
    }

    @Test fun concealmentAStalledDownlinkOursBeingLostOrALongRoundTripAreWeak() {
        val tester = LiveRTCLinkWatchTester()
        for (weaken in listOf<(LiveRTCLinkSample, LiveRTCLinkWatchTester) -> LiveRTCLinkSample>(
            { s, t -> t.step(s, concealed = 12_000.0) },
            { s, t -> t.step(s, outboundLoss = 0.2) },
            { s, t -> t.step(s, roundTrip = 1.2) },
        )) {
            val watch = LiveRTCLinkWatch()
            var sample = LiveRTCLinkSample()
            watch.add(sample)
            sample = weaken(sample, tester)
            assertNull("a lone bad second doesn't warn", watch.add(sample))
            sample = weaken(sample, tester)
            assertEquals(true, watch.add(sample))
        }
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        sample = step(sample, received = 0.0)
        assertNull("one quiet second is fine", watch.add(sample))
        sample = step(sample, received = 0.0)
        assertEquals("two are a stalled downlink", true, watch.add(sample))
    }

    @Test fun blipsDontWarnButTwoBadSecondsOfThreeDo() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        repeat(5) {
            sample = step(sample, roundTrip = 1.2)
            assertNull(watch.add(sample))
            sample = step(sample)
            assertNull(watch.add(sample))
            sample = step(sample)
            assertNull("a blip every few seconds never warns", watch.add(sample))
        }
        sample = step(sample, received = 40.0, lost = 10.0)
        assertNull(watch.add(sample))
        sample = step(sample)
        assertNull(watch.add(sample))
        sample = step(sample, concealed = 12_000.0)
        assertEquals("two bad seconds in three warn, whatever went wrong", true, watch.add(sample))
    }

    @Test fun concealmentOverSilenceIsNotWeak() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        repeat(5) {
            val next = step(sample, concealed = 30_000.0)
            sample = next.copy(silentConcealedSamples = sample.silentConcealedSamples + 30_000)
            assertNull(watch.add(sample))
        }
        assertFalse(watch.isPoor)
    }

    @Test fun audibleConcealmentCountsWhenSomeIsOverSilence() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        for (expected in listOf<Boolean?>(null, true)) {
            val next = step(sample, concealed = 30_000.0)
            sample = next.copy(silentConcealedSamples = sample.silentConcealedSamples + 10_000)
            assertEquals("20,000 of 48,000 samples concealed over speech", expected, watch.add(sample))
        }
    }

    @Test fun aResetSilenceCounterDoesntLookLikeConcealment() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample(packetsReceived = 50.0, samplesReceived = 48_000.0, samplesConcealed = 40_000.0, silentConcealedSamples = 40_000.0)
        watch.add(sample)
        repeat(3) {
            sample = LiveRTCLinkSample(
                packetsReceived = sample.packetsReceived + 50, samplesReceived = sample.samplesReceived + 48_000,
                samplesConcealed = sample.samplesConcealed, silentConcealedSamples = 0.0,
            )
            assertNull(watch.add(sample))
        }
        assertFalse(watch.isPoor)
    }

    @Test fun aQuietSecondDoesntCountTowardRecovery() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        sample = step(sample, received = 40.0, lost = 10.0)
        watch.add(sample)
        sample = step(sample, received = 40.0, lost = 10.0)
        assertEquals(true, watch.add(sample))
        for (received in listOf(50.0, 50.0, 0.0, 50.0, 50.0)) {
            sample = step(sample, received = received)
            assertNull("recovery needs 3 seconds that heard speech", watch.add(sample))
        }
        sample = step(sample)
        assertEquals(false, watch.add(sample))
    }

    @Test fun aDroppedLinkOrABackedUpEventChannelIsWeak() {
        val watch = LiveRTCLinkWatch()
        var sample = LiveRTCLinkSample()
        watch.add(sample)
        watch.transportDown = true
        sample = step(sample)
        assertEquals(true, watch.add(sample))
        watch.transportDown = false
        repeat(2) {
            sample = step(sample)
            assertNull(watch.add(sample))
        }
        sample = step(sample)
        assertEquals("3 healthy seconds after it reconnects", false, watch.add(sample))
        // Reconnected before the next sample: still weak until it has recovered.
        watch.transportLost()
        assertTrue(watch.isPoor)
        watch.transportDown = false
        repeat(2) {
            sample = step(sample)
            assertNull(watch.add(sample))
        }
        sample = step(sample)
        assertEquals(false, watch.add(sample))
        val other = LiveRTCLinkWatch()
        var second = LiveRTCLinkSample()
        other.add(second)
        second = step(second)
        assertEquals(true, other.add(second, channelBacklog = 100_000))
    }

    @Test fun onlyAnotherInterfaceCountsAsANetworkChange() {
        val watch = LiveRTCNetworkWatch()
        assertFalse("the first path is the session's", watch.update("wifi en0"))
        assertFalse(watch.update("wifi en0"))
        assertFalse("no network: WebRTC rides out short gaps", watch.update(null))
        assertFalse(watch.update("wifi en0"))
        assertTrue(watch.update("cellular pdp_ip0"))
        assertFalse(watch.update("cellular pdp_ip0"))
    }

    @Test fun aNetworkThatBlocksWebRTCUsesWebSocketsForTenMinutes() {
        val clock = Instant.ofEpochSecond(1_000_000)
        val choice = LiveTransportChoice(DateClient { clock })
        assertTrue(choice.prefersWebRTC)
        choice.webRTCFailed(at = clock)
        assertFalse(choice.prefersWebRTC)
        choice.webRTCConnected()
        assertTrue(choice.prefersWebRTC)
        choice.webRTCFailed(at = clock.minusMillis(((LiveTransportChoice.webSocketFor + 1) * 1_000).toLong()))
        assertTrue("tries WebRTC again after a while", choice.prefersWebRTC)
    }
}

/** Lets the weak-link cases share `LiveRTCTests.step` without capturing the suite. */
private class LiveRTCLinkWatchTester {
    fun step(last: LiveRTCLinkSample, concealed: Double = 0.0, roundTrip: Double = 0.06, outboundLoss: Double = 0.0): LiveRTCLinkSample =
        LiveRTCLinkSample(
            path = "udp", roundTrip = roundTrip,
            packetsReceived = last.packetsReceived + 50, packetsLost = last.packetsLost,
            samplesReceived = last.samplesReceived + 48_000, samplesConcealed = last.samplesConcealed + concealed,
            jitter = 0.002, outboundLoss = outboundLoss,
        )
}

class RealtimeRTCTests {
    private val config = RealtimeSessionConfig(instructions = "Be brief.", tools = emptyList())

    @Test fun callRequestIsMultipartWithTheSessionAndPreNegotiatedChannel() {
        val request = DependencyValues.withValues(DependencyValues.testing().update { this[UuidClient] = UuidClient.incrementing() }) {
            RealtimeRTC.request(apiKey = "sk-test", config = config, sdp = "v=0")
        }
        assertEquals("https://api.openai.com/v1/realtime/calls?dcid=4", request.url.toString())
        assertEquals("Bearer sk-test", request.header("Authorization"))
        // OkHttp carries the content type on the body rather than as a header.
        assertTrue(request.body?.contentType()?.toString()?.startsWith("multipart/form-data; boundary=") == true)
        val body = okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertTrue(body.contains("name=\"sdp\"\r\n\r\nv=0\r\n"))
        assertTrue(body.contains("name=\"session\""))
        assertTrue(body.contains("\"model\":\"gpt-realtime-2.1\""))
        assertFalse("WebRTC negotiates the format in SDP", body.contains("audio/pcm"))
    }

    @Test fun updatesOverWebRTCLeaveTheAudioFormatOut() {
        val rtc = config.sessionUpdate(includeAudioFormat = false)
        assertNull(rtc["session"]?.get("audio")?.get("input")?.get("format"))
        assertNull(rtc["session"]?.get("audio")?.get("output")?.get("format"))
        assertEquals(RealtimeJSONs.string("marin"), rtc["session"]?.get("audio")?.get("output")?.get("voice"))
        assertEquals(RealtimeJSONs.string("semantic_vad"), rtc["session"]?.get("audio")?.get("input")?.get("turn_detection")?.get("type"))
        assertEquals(RealtimeJSONs.string("audio/pcm"), config.sessionUpdate["session"]?.get("audio")?.get("input")?.get("format")?.get("type"))
    }

    private fun event(json: String): ByteArray = json.toByteArray()
    private val chunk = ByteArray(1_920) // 40 ms

    @Test fun speechIsWhatTheServiceSaysItIsSendingTaggedWithItsResponseAndItem() {
        val speech = RealtimeRTCSpeech()
        assertNull("silence before a reply is dropped", speech.event(chunk))
        speech.observe(event("""{"type":"response.created","response":{"id":"resp_1"}}"""))
        speech.observe(event("""{"type":"response.output_item.added","item":{"id":"item_1","type":"message"}}"""))
        speech.observe(event("""{"type":"output_audio_buffer.started","response_id":"resp_1"}"""))
        assertEquals(RealtimeServerEvent.OutputAudio(responseID = "resp_1", itemID = "item_1", pcm = chunk), speech.event(chunk))
        speech.observe(event("""{"type":"output_audio_buffer.stopped","response_id":"resp_1"}"""))
        var tail = 0
        while (speech.event(chunk) != null) tail += 1
        assertEquals("a short tail lets the last words through", RealtimeRTCSpeech.tailMilliseconds / 40, tail)
    }

    @Test fun aClearedBufferStopsAtOnce() {
        val speech = RealtimeRTCSpeech()
        speech.observe(event("""{"type":"output_audio_buffer.started","response_id":"resp_1"}"""))
        assertNotNull(speech.event(chunk))
        speech.observe(event("""{"type":"output_audio_buffer.cleared","response_id":"resp_1"}"""))
        assertNull(speech.event(chunk))
    }
}

class VoiceDelegatorRoundTests {
    /**
     * Runs a request whose model asks for a wait every round until
     * `answerOnLastRound` lets it answer, returning the outcome and every input.
     */
    private suspend fun run(answerOnLastRound: Boolean): Pair<VoiceDelegator.Outcome, List<List<VoiceDelegator.InputItem>>> {
        val inputs = mutableListOf<List<VoiceDelegator.InputItem>>()
        val client = VoiceDelegatorClient(isEnabled = true) { _, input, _ ->
            inputs += input
            val round = inputs.size
            if (answerOnLastRound && input.contains(VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote))) {
                VoiceDelegator.Response(id = "r$round", calls = emptyList(), text = "The build is still running.")
            } else {
                VoiceDelegator.Response(id = "r$round", calls = listOf(VoiceDelegator.ToolCall(callID = "c$round", name = "wait_for_session", arguments = "{}")), text = "")
            }
        }
        val tools = VoiceDelegatorTools(
            run = { """{"finished":false,"latest_reply":""}""" },
            sent = { emptyList() },
            note = { },
        )
        val outcome = client.run(apiKey = "key", request = "Wait for the build.", previousResponseID = null, tools = tools)
        return outcome to inputs
    }

    /**
     * Many waits on a long job fit, and the last round answers with where
     * things stand rather than giving up.
     */
    @Test fun longWorkGetsManyRoundsAndTheLastOneAnswers() = runTest {
        val (outcome, inputs) = run(answerOnLastRound = true)
        assertTrue(VoiceDelegator.maxRounds >= 30)
        assertEquals(VoiceDelegator.maxRounds, inputs.size)
        assertEquals("The build is still running.", outcome.reply)
        assertEquals("r${VoiceDelegator.maxRounds}", outcome.responseID)
        // Only the last round is told to answer.
        assertTrue(inputs.dropLast(1).all { !it.contains(VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote)) })
        assertEquals(VoiceDelegator.InputItem.ToolOutput(callID = "c${VoiceDelegator.maxRounds - 1}", output = """{"finished":false,"latest_reply":""}"""), inputs.last().first())
        assertEquals(VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote), inputs.last().last())
    }

    @Test fun aRunThatNeverAnswersStopsAtTheCapSayingWorkContinues() = runTest {
        val (outcome, inputs) = run(answerOnLastRound = false)
        assertEquals(VoiceDelegator.maxRounds, inputs.size)
        assertTrue("Never chain onto unanswered calls", outcome.reply == VoiceDelegator.outOfRoundsReply && outcome.responseID == null)
    }
}

class VoiceEnginePersistenceTests {
    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val session = OpenCodeSession(id = "ses_voice", serverTitle = "Voice lab", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.EPOCH)

    @Test fun defaultsToGPTLiveAndPersistsSelectionAcrossCalls() = runTest {
        val shared = VoiceTestShared()
        shared.set(VoiceSharedKeys.voiceServerChoice, VoiceServerChoice.Phone)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
        var voiceEngine = shared.get(VoiceSharedKeys.voiceEngine)
        assertEquals(VoiceEngine.gptLive, voiceEngine)
        assertEquals("GPT-Live", voiceEngine.title)
        assertEquals("gpt-live-1.1", voiceEngine.modelID)

        val startedConfigs = mutableListOf<RealtimeSessionConfig>()
        val verifiedModels = mutableListOf<String>()
        val startedKeys = mutableListOf<String>()
        val store = voiceTestStore(shared, { VoiceFeature.State(server, session) }) {
            this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, model -> verifiedModels += model })
            this[VoiceCallClient] = testVoiceCall(start = { key, config ->
                startedKeys += key
                startedConfigs += config
                emptyFlow()
            })
            this[VoiceWorkClient] = testVoiceWork()
        }
        store.exhaustive = false
        shared.set(SharedKeys.openAIAPIKey, "sk-test")

        store.send(VoiceFeature.Action.Start)
        store.receive(::isCallEnded)
        assertEquals(listOf("gpt-live-1.1"), verifiedModels)
        assertEquals(VoiceEngine.gptLive, startedConfigs.first().engine)
        assertEquals("gpt-live-1.1", startedConfigs.first().model)
        assertTrue(startedConfigs.first().tools.isEmpty())

        // Switching to Realtime persists and starts the next call with Realtime's model and tools.
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.realtime)
        voiceEngine = shared.get(VoiceSharedKeys.voiceEngine)
        assertEquals(VoiceEngine.realtime, voiceEngine)
        assertEquals("Realtime", voiceEngine.title)
        assertEquals("gpt-realtime-2.1", voiceEngine.modelID)

        store.send(VoiceFeature.Action.Start)
        store.receive(::isCallEnded)
        assertEquals(listOf("gpt-live-1.1", "gpt-realtime-2.1"), verifiedModels)
        assertEquals(VoiceEngine.realtime, startedConfigs.last().engine)
        assertEquals("gpt-realtime-2.1", startedConfigs.last().model)
        assertEquals(listOf("delegate", "end_voice_session"), startedConfigs.last().tools.map { it.name })

        // Grok Voice needs its own xAI key: the OpenAI one doesn't start it.
        assertEquals(listOf("sk-test", "sk-test"), startedKeys)
        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.grok)
        voiceEngine = shared.get(VoiceSharedKeys.voiceEngine)
        assertEquals("Grok Voice", voiceEngine.title)
        assertEquals(VoiceProvider.xAI, voiceEngine.provider)
        store.send(VoiceFeature.Action.Start)
        assertEquals(VoiceFeature.State.Phase.Failed("Add your xAI API key in Settings to use Voice."), store.state.phase)
        assertEquals(2, startedKeys.size)
        // The voice module's part is that Grok has no key from the OpenAI one.
        assertNull(voiceEngine.apiKey(openAI = "sk-test", xAI = null))

        shared.set(SharedKeys.xaiAPIKey, "xai-test")
        store.send(VoiceFeature.Action.Start)
        store.receive(::isCallEnded)
        assertEquals("grok-voice-3.0", verifiedModels.last())
        assertEquals(listOf("sk-test", "sk-test", "xai-test"), startedKeys)
        assertEquals(VoiceEngine.grok, startedConfigs.last().engine)
        assertEquals("grok-voice-3.0", startedConfigs.last().model)
        assertEquals("Grok delegates through tools, like Realtime", listOf("delegate", "end_voice_session"), startedConfigs.last().tools.map { it.name })
        assertEquals(false, store.state.usesDelegator)
        shared.set(SharedKeys.xaiAPIKey, null)

        shared.set(VoiceSharedKeys.voiceEngine, VoiceEngine.gptLive)
    }
}
