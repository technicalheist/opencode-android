package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

private fun obj(vararg pairs: Pair<String, JsonElement>): JsonElement = JsonObject(linkedMapOf(*pairs))
private fun str(value: String): JsonElement = JsonPrimitive(value)
private fun arr(vararg values: JsonElement): JsonElement = JsonArray(values.toList())
private fun parsed(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString())

class ServerVoiceTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret")
    private val session = OpenCodeSession(
        id = "ses_voice",
        serverTitle = "Server Voice",
        projectID = "proj_1",
        directory = "/Users/kit/code/open-source/opencode-ios",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )

    /** The first ID the incrementing UUID client hands out: Android's call-presence ID. */
    private val presenceID = UUID(0, 0)

    /** Every test gets a fresh in-memory store, which starts at those same defaults. */
    private fun resetSharedVoiceState(): SpeechTestShared = SpeechTestShared()

    private fun startedTarget() = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory)

    private fun transcriptRecord(shared: SpeechTestShared): VoiceTranscriptRecord? =
        shared.run { shared(VoiceSharedKeys.voiceTranscripts).value["${server.id}:${session.id}"] }

    @Test fun openAPIDetectsWhetherServerSupportsVoiceSessionEndpoint() = runBlocking {
        val supportedSpec = """
            {
              "openapi": "3.1.0",
              "paths": {
                "/api/info": { "get": {} },
                "/api/experimental/voice/session": { "post": {} }
              }
            }
        """.trimIndent().toByteArray()
        val unsupportedSpec = """
            {
              "openapi": "3.1.0",
              "paths": {
                "/api/info": { "get": {} },
                "/api/session": { "get": {} }
              }
            }
        """.trimIndent().toByteArray()

        assertEquals(ServerVoiceSupport.supported, ServerVoiceSupportDetector.support(openAPIJSON = supportedSpec))
        assertEquals(ServerVoiceSupport.unsupported, ServerVoiceSupportDetector.support(openAPIJSON = unsupportedSpec))
        assertEquals(ServerVoiceSupport.unknown, ServerVoiceSupportDetector.support(openAPIJSON = "not-json".toByteArray()))

        val values = DependencyValues.testing()
        val invalidServer = OpenCodeServer(url = "://invalid", username = "", password = "")
        val invalidURLResult = DependencyValues.withValues(values) { runBlocking { ServerVoiceClient.liveValue.checkSupport(invalidServer) } }
        assertEquals(ServerVoiceSupport.unknown, invalidURLResult)

        val unreachableServer = OpenCodeServer(url = "http://127.0.0.1:1", username = "", password = "")
        // Loopback port 1 refuses the connection: a real transport error, never off the device.
        val transportErrorResult = DependencyValues.withValues(values) { runBlocking { ServerVoiceClient.liveValue.checkSupport(unreachableServer) } }
        assertEquals(ServerVoiceSupport.unknown, transportErrorResult)
    }

    @Test fun serverVoiceRequestAndResponseShapesMatchServerContract() {
        val webrtcRequest = ServerVoiceSessionRequest(
            model = "gpt-live-1.1",
            session = obj("instructions" to str("Be concise.")),
            transport = ServerVoiceTransportRequest.Webrtc(sdp = "v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n", dcid = 4),
        )
        val webrtcJSON = parsed(webrtcRequest.json.encodedBytes())
        assertEquals(str("gpt-live-1.1"), webrtcJSON["model"])
        assertEquals(str("Be concise."), webrtcJSON["session"]?.get("instructions"))
        assertEquals(str("webrtc"), webrtcJSON["transport"]?.get("type"))
        assertEquals(str("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\n"), webrtcJSON["transport"]?.get("sdp"))
        assertEquals(4.0, webrtcJSON["transport"]?.get("dcid")?.jsonPrimitive?.content?.toDouble())

        val wsRequest = ServerVoiceSessionRequest(model = "gemini-3.8-live", session = null, transport = ServerVoiceTransportRequest.Websocket)
        val wsJSON = parsed(wsRequest.json.encodedBytes())
        assertEquals(str("gemini-3.8-live"), wsJSON["model"])
        assertNull(wsJSON["session"])
        assertEquals(str("websocket"), wsJSON["transport"]?.get("type"))

        val webrtcResponseJSON = """
            {
              "data": {
                "transport": {
                  "type": "webrtc",
                  "sdp": "v=0\r\na=answer\r\n"
                }
              }
            }
        """.trimIndent().toByteArray()
        assertEquals(ServerVoiceTransportResponse.Webrtc(sdp = "v=0\r\na=answer\r\n"), ServerVoiceSessionEnvelope.transport(webrtcResponseJSON))

        val wsResponseJSON = """
            {
              "data": {
                "transport": {
                  "type": "websocket",
                  "url": "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained",
                  "token": "auth_tokens/ephemeral-123",
                  "expiresAt": 1760800000
                }
              }
            }
        """.trimIndent().toByteArray()
        assertEquals(
            ServerVoiceTransportResponse.Websocket(
                url = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained",
                token = "auth_tokens/ephemeral-123",
                expiresAt = 1_760_800_000,
            ),
            ServerVoiceSessionEnvelope.transport(wsResponseJSON),
        )
    }

    @Test fun serverVoiceErrorMappingFormatsMissingServerKeyAnd404Fallback() {
        val notFound = ServerVoiceError.fromHTTPResponse(statusCode = 404, data = "Not Found".toByteArray())
        assertEquals(ServerVoiceError.UnsupportedRoute, notFound)
        assertEquals("This server needs a newer OpenCode for voice calls", notFound.errorDescription)

        val missingOpenAIKeyBody = """
            {
              "name": "ServiceUnavailableError",
              "message": "Voice needs an OpenAI API key on the server (a stored key or OPENAI_API_KEY). ChatGPT sign-in can't start voice sessions."
            }
        """.trimIndent().toByteArray()
        val missingOpenAI = ServerVoiceError.fromHTTPResponse(statusCode = 503, data = missingOpenAIKeyBody)
        assertEquals(
            ServerVoiceError.Rejected(
                status = 503,
                message = "Couldn’t start voice: Voice needs an OpenAI API key on the server (a stored key or OPENAI_API_KEY). ChatGPT sign-in can’t start voice sessions.",
            ),
            missingOpenAI,
        )
        assertTrue(missingOpenAI.errorDescription.contains("Couldn’t start voice"))
        assertTrue(missingOpenAI.errorDescription.contains("Voice needs an OpenAI API key on the server"))

        val nestedErrorBody = """
            {
              "error": {
                "message": "Voice needs a Google API key on the server (a stored key, GEMINI_API_KEY, GOOGLE_GENERATIVE_AI_API_KEY, or GOOGLE_API_KEY)."
              }
            }
        """.trimIndent().toByteArray()
        val missingGoogle = ServerVoiceError.fromHTTPResponse(statusCode = 503, data = nestedErrorBody)
        assertEquals(
            "Couldn’t start voice: Voice needs a Google API key on the server (a stored key, GEMINI_API_KEY, GOOGLE_GENERATIVE_AI_API_KEY, or GOOGLE_API_KEY).",
            missingGoogle.errorDescription,
        )
    }

    /** Android adds the presence ID. */
    private fun serverVoiceDependencies(
        shared: SpeechTestShared,
        at: Long,
        start: suspend (String, RealtimeSessionConfig) -> kotlinx.coroutines.flow.Flow<VoiceCallEvent>,
        end: suspend () -> Unit = {},
        serverVoice: ServerVoiceClient? = null,
    ): ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {
        this[SharedStore] = shared.store
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(at))
        this[UuidClient] = UuidClient.incrementing()
        // The call's start cue and its ongoing-notification linger read the clock.
        this[Clock] = TestClock()
        serverVoice?.let { this[ServerVoiceClient] = it }
        this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
            VoiceWorkClient(submit = work.submit, awaitReply = work.awaitReply, checkSessionActive = work.checkSessionActive, recentMessages = { _, _, _ -> emptyList() })
        }
        this[VoiceCallClient] = speechVoiceCall(start = start, end = end)
    }

    @Test fun serverVoiceStartsOpenAIAndGeminiCallsWithoutPhoneKey() = runBlocking {
        val capturedConfigs = Locked(listOf<RealtimeSessionConfig>())

        val shared = resetSharedVoiceState()
        val openAIStream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val openAIState = shared.run {
            shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.openAI)
            VoiceFeature.State(server, session).copy(serverVoiceSupport = ServerVoiceSupport.supported)
        }
        val openAIEffects = detachedScope(coroutineContext)
        val openAIStore = TestStore(
            openAIState, VoiceFeature(), openAIEffects,
            serverVoiceDependencies(shared, at = 1_000, start = { key, config ->
                assertTrue(key.isEmpty())
                capturedConfigs.update { it + config }
                openAIStream.trySend(VoiceCallEvent.Connected)
                openAIStream.consumeAsFlow()
            }, end = { openAIStream.close() }),
        )

        openAIStore.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Server 1", VoiceProviderOption.Brand.openAI),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = false,
                callPresenceID = presenceID,
            )
        }
        openAIStore.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(1_000))
        }
        openAIStream.close()
        openAIStore.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(1_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        openAIStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        openAIStore.finish()
        openAIEffects.cancel()

        assertEquals(1, capturedConfigs.value.size)
        assertEquals(server, capturedConfigs.value[0].serverVoice)
        assertEquals(ServerVoiceProvider.openAI, capturedConfigs.value[0].provider)
        assertEquals("gpt-live-1.1", capturedConfigs.value[0].model)

        val geminiShared = resetSharedVoiceState()
        val geminiStream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val geminiState = geminiShared.run {
            shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.geminiLive)
            VoiceFeature.State(server, session).copy(serverVoiceSupport = ServerVoiceSupport.supported)
        }
        val geminiEffects = detachedScope(coroutineContext)
        val geminiStore = TestStore(
            geminiState, VoiceFeature(), geminiEffects,
            serverVoiceDependencies(geminiShared, at = 2_000, start = { key, config ->
                assertTrue(key.isEmpty())
                capturedConfigs.update { it + config }
                geminiStream.trySend(VoiceCallEvent.Connected)
                geminiStream.consumeAsFlow()
            }, end = { geminiStream.close() }),
        )

        geminiStore.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Server 1", VoiceProviderOption.Brand.gemini),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.GeminiLive),
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = false,
                callPresenceID = presenceID,
            )
        }
        geminiStore.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(2_000))
        }
        geminiStream.close()
        geminiStore.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(2_000), tail = emptyList(), log = emptyList()), transcriptRecord(geminiShared))
        geminiStore.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        geminiStore.finish()
        geminiEffects.cancel()

        assertEquals(2, capturedConfigs.value.size)
        assertEquals(server, capturedConfigs.value[1].serverVoice)
        assertEquals(ServerVoiceProvider.geminiLive, capturedConfigs.value[1].provider)
        assertEquals("gemini-3.8-live", capturedConfigs.value[1].model)
        assertEquals(listOf("delegate", "end_voice_session"), capturedConfigs.value[1].tools.map { it.name })
    }

    @Test fun serverVoiceFallsBackToPhoneKeyWhenServerDoesNotSupportEndpoint() = runBlocking {
        val shared = resetSharedVoiceState()
        val capturedStarts = Locked(listOf<Pair<String, OpenCodeServer?>>())
        val fallbackStream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        // Holds the call until the probe's actions are received, so the order below is fixed.
        val connectGate = CompletableDeferred<Unit>()

        val state = shared.run {
            shared(SharedKeys.openAIAPIKey).set("sk-phone-fallback")
            shared(SharedKeys.verifiedVoiceKeyFingerprint).set(VoiceKeyFingerprint.make(key = "sk-phone-fallback", model = VoiceEngine.gptLive.modelID))
            shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.openAI)
            VoiceFeature.State(server, session)
        }
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            state, VoiceFeature(), effects,
            serverVoiceDependencies(
                shared, at = 3_000,
                start = { key, config ->
                    capturedStarts.update { it + (key to config.serverVoice) }
                    connectGate.await()
                    fallbackStream.trySend(VoiceCallEvent.Connected)
                    fallbackStream.consumeAsFlow()
                },
                end = { fallbackStream.close() },
                serverVoice = ServerVoiceClient.testValue.let { ServerVoiceClient(checkSupport = { ServerVoiceSupport.unsupported }, models = it.models, createSession = it.createSession) },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Server 1", VoiceProviderOption.Brand.openAI),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = false,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.unsupported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.unsupported)
        }
        assertEquals(ServerVoiceSupport.unsupported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[server.persistenceID] })
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.unsupported)))
        store.receive(VoiceFeature.Action.CallPlaceChanged(null)) { it.copy(callPlace = null) }
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.PhoneKey(VoiceEngine.gptLive), usesDelegator = false)) {
            it.copy(activeRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive))
        }
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(3_000))
        }
        fallbackStream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(3_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertEquals(1, capturedStarts.value.size)
        assertEquals("sk-phone-fallback", capturedStarts.value[0].first)
        assertNull(capturedStarts.value[0].second)
    }

    @Test fun serverVoiceReprobesOnceWhenUnsupportedAndClearsCacheOnReconnect() = runBlocking {
        val shared = resetSharedVoiceState()
        val probeCount = Locked(0)
        val callStream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        // Holds the call until the probe's actions are received, so the order below is fixed.
        val connectGate = CompletableDeferred<Unit>()

        // 1. Even if previously marked `.unsupported`, `.start` probes once and
        // upgrades to `.supported` when the server now advertises the route.
        val state = shared.run {
            shared(VoiceSharedKeys.serverVoiceSupportByServer).update { it + (server.persistenceID to ServerVoiceSupport.unsupported) }
            shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.geminiLive)
            VoiceFeature.State(server, session).copy(serverVoiceSupport = ServerVoiceSupport.unsupported)
        }
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            state, VoiceFeature(), effects,
            serverVoiceDependencies(
                shared, at = 3_500,
                start = { key, config ->
                    assertTrue(key.isEmpty())
                    assertEquals(server, config.serverVoice)
                    assertEquals(ServerVoiceProvider.geminiLive, config.provider)
                    connectGate.await()
                    callStream.trySend(VoiceCallEvent.Connected)
                    callStream.consumeAsFlow()
                },
                end = { callStream.close() },
                serverVoice = ServerVoiceClient.testValue.let {
                    ServerVoiceClient(checkSupport = { probeCount.update { it + 1 }; ServerVoiceSupport.supported }, models = it.models, createSession = it.createSession)
                },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                activeRoute = VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported),
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = false,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.supported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        }
        assertEquals(ServerVoiceSupport.supported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[server.persistenceID] })
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.supported)))
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.Server(VoiceRoute.ServerRoute.GeminiLive), usesDelegator = false)) {
            it.copy(activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.GeminiLive))
        }
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(3_500))
        }
        callStream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(3_500), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        assertEquals(1, probeCount.value)
        store.finish()
        effects.cancel()
    }

    @Test fun serverVoiceSurfacesReadableMissingServerKeyError() = runBlocking {
        val shared = resetSharedVoiceState()
        val state = shared.run {
            shared(VoiceSharedKeys.serverVoiceProvider).set(ServerVoiceProvider.openAI)
            VoiceFeature.State(server, session).copy(serverVoiceSupport = ServerVoiceSupport.supported)
        }
        val message = "Couldn’t start voice: Voice needs an OpenAI API key on the server (a stored key or OPENAI_API_KEY). ChatGPT sign-in can’t start voice sessions."
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            state, VoiceFeature(), effects,
            serverVoiceDependencies(shared, at = 4_000, start = { _, _ ->
                throw ServerVoiceError.fromHTTPResponse(
                    statusCode = 503,
                    data = """{"message":"Voice needs an OpenAI API key on the server (a stored key or OPENAI_API_KEY). ChatGPT sign-in can't start voice sessions."}""".toByteArray(),
                )
            }),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Server 1", VoiceProviderOption.Brand.openAI),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = false,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = message)) {
            it.withPhase(VoiceFeature.State.Phase.Failed(message))
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(4_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        // Android: the failed call's “Call ended” notification lingers on the test clock.
        effects.cancel()
    }

    @Test fun geminiLiveProtocolEncodesSetupAudioAndToolResponsesAndDecodesFixtures() {
        val config = GeminiLiveSessionConfig(
            model = "gemini-3.8-live",
            voice = CallVoice.marin,
            instructions = "You are a voice coding assistant.",
            tools = VoiceInstructions.tools,
        )
        val setupJSON = parsed(GeminiLiveCommand.Setup(config).encoded())
        assertEquals(str("models/gemini-3.8-live"), setupJSON["setup"]?.get("model"))
        assertEquals(arr(str("AUDIO")), setupJSON["setup"]?.get("generationConfig")?.get("responseModalities"))
        assertEquals(
            str("Aoede"),
            setupJSON["setup"]?.get("generationConfig")?.get("speechConfig")?.get("voiceConfig")?.get("prebuiltVoiceConfig")?.get("voiceName"),
        )
        assertEquals(arr(obj("text" to str("You are a voice coding assistant."))), setupJSON["setup"]?.get("systemInstruction")?.get("parts"))
        assertEquals(obj(), setupJSON["setup"]?.get("inputAudioTranscription"))
        assertEquals(obj(), setupJSON["setup"]?.get("outputAudioTranscription"))
        assertEquals(
            arr(
                obj(
                    "functionDeclarations" to arr(
                        obj(
                            "name" to str(VoiceInstructions.tools[0].name),
                            "description" to str(VoiceInstructions.tools[0].description),
                            "parametersJsonSchema" to VoiceInstructions.tools[0].parameters,
                        ),
                        obj(
                            "name" to str(VoiceInstructions.tools[1].name),
                            "description" to str(VoiceInstructions.tools[1].description),
                            "parametersJsonSchema" to VoiceInstructions.tools[1].parameters,
                        ),
                    ),
                ),
            ),
            setupJSON["setup"]?.get("tools"),
        )

        val pcm16k = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val audioJSON = parsed(GeminiLiveCommand.RealtimeInputAudio(pcm16k).encoded())
        assertEquals(str("audio/pcm;rate=16000"), audioJSON["realtimeInput"]?.get("audio")?.get("mimeType"))
        assertEquals(str(Base64.getEncoder().encodeToString(pcm16k)), audioJSON["realtimeInput"]?.get("audio")?.get("data"))

        val toolResponseJSON = parsed(
            GeminiLiveCommand.ToolResponse(
                listOf(GeminiLiveFunctionResponse(id = "call_42", name = "delegate", response = obj("result" to str("Tests passed.")))),
            ).encoded(),
        )
        assertEquals(
            arr(obj("id" to str("call_42"), "name" to str("delegate"), "response" to obj("result" to str("Tests passed.")))),
            toolResponseJSON["toolResponse"]?.get("functionResponses"),
        )

        assertEquals(listOf(GeminiLiveServerEvent.SetupComplete), GeminiLiveServerEvent.decodeAll("""{"setupComplete":{}}""".toByteArray()))

        val outputPCM = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val serverContentFixture = """
            {
              "serverContent": {
                "inputTranscription": { "text": "Run the voice tests", "finished": true },
                "modelTurn": {
                  "parts": [
                    {
                      "inlineData": {
                        "mimeType": "audio/pcm;rate=24000",
                        "data": "${Base64.getEncoder().encodeToString(outputPCM)}"
                      }
                    }
                  ]
                },
                "outputTranscription": { "text": "Running the voice tests now.", "finished": true },
                "turnComplete": true
              }
            }
        """.trimIndent().toByteArray()
        assertEquals(
            listOf(
                GeminiLiveServerEvent.InputTranscription(text = "Run the voice tests", finished = true),
                GeminiLiveServerEvent.Audio(outputPCM),
                GeminiLiveServerEvent.OutputTranscription(text = "Running the voice tests now.", finished = true),
                GeminiLiveServerEvent.TurnComplete,
            ),
            GeminiLiveServerEvent.decodeAll(serverContentFixture),
        )

        assertEquals(listOf(GeminiLiveServerEvent.Interrupted), GeminiLiveServerEvent.decodeAll("""{"serverContent":{"interrupted":true}}""".toByteArray()))

        val toolCallFixture = """
            {
              "toolCall": {
                "functionCalls": [
                  {
                    "id": "fn_1",
                    "name": "delegate",
                    "args": { "request": "Run the voice tests" }
                  },
                  {
                    "id": "fn_2",
                    "name": "end_voice_session",
                    "args": {}
                  }
                ]
              }
            }
        """.trimIndent().toByteArray()
        assertEquals(
            listOf(
                GeminiLiveServerEvent.FunctionCall(GeminiLiveFunctionCall(id = "fn_1", name = "delegate", args = obj("request" to str("Run the voice tests")))),
                GeminiLiveServerEvent.FunctionCall(GeminiLiveFunctionCall(id = "fn_2", name = "end_voice_session", args = obj())),
            ),
            GeminiLiveServerEvent.decodeAll(toolCallFixture),
        )
    }

    @Test fun geminiPCMResamplerDownsamples24kHzTo16kHzAcrossChunkBoundaries() {
        val samples24k = ByteArray(480)
        for (i in 0 until 240) {
            val sample = i * 10
            samples24k[i * 2] = (sample and 0xFF).toByte()
            samples24k[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        val resampler = GeminiPCMResampler()
        val part1 = resampler.downsample24kTo16k(samples24k.copyOfRange(0, 100)) // 50 samples (16 triplets + 2 remainder)
        val part2 = resampler.downsample24kTo16k(samples24k.copyOfRange(100, samples24k.size)) // 190 + 2 = 192 samples (64 triplets)
        val combined = part1 + part2
        // 240 input samples at 24 kHz -> 160 output samples (320 bytes) at 16 kHz.
        assertEquals(320, combined.size)
    }

    @Test fun geminiCallCoordinatorStreamsAudioTranscriptsToolCallsAndInterruptions() = runBlocking {
        val serverEvents = Channel<GeminiLiveServerEvent>(Channel.UNLIMITED)
        val micCapture = Channel<ByteArray>(Channel.UNLIMITED)
        val audioEvents = Channel<VoiceAudioEvent>(Channel.UNLIMITED)
        val sentCommands = Locked(listOf<GeminiLiveCommand>())
        val enqueuedAudio = Locked(listOf<ByteArray>())
        val flushedCount = Locked(0)

        val values = DependencyValues.testing().update {
            this[Clock] = TestClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }
        val coordinator = DependencyValues.withValues(values) { GeminiCallCoordinator() }
        val geminiClient = GeminiLiveClient { _, _ ->
            GeminiLiveConnection(
                events = serverEvents.consumeAsFlow(),
                send = { command -> sentCommands.update { it + command } },
                close = {},
            )
        }
        val audioClient = VoiceAudioClient(start = {
            VoiceAudioSession(
                capture = micCapture.consumeAsFlow(),
                events = audioEvents.consumeAsFlow(),
                enqueue = { pcm, _ -> enqueuedAudio.update { it + pcm } },
                flush = { flushedCount.update { it + 1 }; 120 },
                setMuted = {},
                stop = {},
            )
        })

        val callEvents = coordinator.start(
            apiKey = "auth_tokens/test",
            config = GeminiLiveSessionConfig(instructions = "Test instructions", tools = VoiceInstructions.tools),
            gemini = geminiClient,
            audio = audioClient,
        )
        val received = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val collector = launch { callEvents.collect { received.send(it) } }
        suspend fun next(): VoiceCallEvent = withTimeout(5.seconds) { received.receive() }

        serverEvents.trySend(GeminiLiveServerEvent.SetupComplete)
        assertEquals(VoiceCallEvent.Connected, next())

        // Send 6 samples (12 bytes) at 24 kHz -> 4 samples (8 bytes) at 16 kHz.
        micCapture.trySend(ByteArray(12) { 0x02 })
        eventually { sentCommands.value.isNotEmpty() }
        val first = sentCommands.value.first()
        if (first is GeminiLiveCommand.RealtimeInputAudio) {
            assertEquals(8, first.pcm16k.size)
        } else {
            fail("Expected realtimeInputAudio command")
        }

        val sampleOutPCM = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        serverEvents.trySend(GeminiLiveServerEvent.InputTranscription(text = "Check the build", finished = true))
        serverEvents.trySend(GeminiLiveServerEvent.Audio(sampleOutPCM))
        serverEvents.trySend(GeminiLiveServerEvent.OutputTranscription(text = "Checking now.", finished = true))
        serverEvents.trySend(GeminiLiveServerEvent.TurnComplete)

        assertEquals(VoiceCallEvent.UserSpeechStarted, next())
        assertEquals(VoiceCallEvent.UserTranscript(itemID = "gemini-user-1", text = "Check the build", final = true), next())
        assertEquals(VoiceCallEvent.AssistantDelta(itemID = "gemini-assistant-2", delta = "Checking now."), next())
        assertEquals(VoiceCallEvent.AssistantDone(itemID = "gemini-assistant-2", text = "Checking now."), next())
        assertEquals(1, enqueuedAudio.value.size)
        assertTrue(enqueuedAudio.value[0].contentEquals(sampleOutPCM))

        serverEvents.trySend(
            GeminiLiveServerEvent.FunctionCall(GeminiLiveFunctionCall(id = "call_1", name = "delegate", args = obj("request" to str("Check the build")))),
        )
        assertEquals(VoiceCallEvent.FunctionCall(callID = "call_1", name = "delegate", arguments = obj("request" to str("Check the build"))), next())

        coordinator.completeFunction(callID = "call_1", output = "Build succeeded.")
        assertEquals(
            GeminiLiveCommand.ToolResponse(
                listOf(GeminiLiveFunctionResponse(id = "call_1", name = "delegate", response = obj("result" to str("Build succeeded.")))),
            ),
            sentCommands.value.last(),
        )

        serverEvents.trySend(GeminiLiveServerEvent.Interrupted)
        assertEquals(VoiceCallEvent.UserSpeechStarted, next())
        assertEquals(1, flushedCount.value)

        coordinator.end()
        collector.cancel()
    }

    // MARK: Responses delegation (the delegator on the server's key)

    @Test fun responsesDelegationEncodesTheDocumentedShapes() {
        val backend = LiveResponsesDelegation(
            model = "gpt-6.1-sol",
            instructions = "Run OpenCode tools.",
            tools = arr(obj("type" to str("function"), "name" to str("list_projects"))),
            reasoningEffort = "low",
        )
        val payload = LiveSessionConfig(instructions = "Be brief.", delegation = LiveDelegation.responses(backend))
            .sessionPayload(includeAudioFormat = false)
        assertEquals(
            obj(
                "type" to str("responses"),
                "responses" to obj(
                    "model" to str("gpt-6.1-sol"),
                    "instructions" to str("Run OpenCode tools."),
                    "tools" to arr(obj("type" to str("function"), "name" to str("list_projects"))),
                    "reasoning" to obj("effort" to str("low")),
                ),
            ),
            payload["delegation"],
        )
        assertEquals(obj("type" to str("client")), LiveSessionConfig(instructions = "Be brief.").sessionPayload(includeAudioFormat = false)["delegation"])

        // The real delegator: its model, prompt with the screen, and every tool.
        val delegator = VoiceDelegator.responsesDelegation(context = "Viewing session \"Server Voice\".")
        assertEquals(VoiceDelegator.model, delegator.model)
        assertTrue(delegator.instructions.startsWith(VoiceDelegator.instructions))
        assertTrue(delegator.instructions.contains("Viewing session \"Server Voice\"."))
        val tools = delegator.tools as? JsonArray ?: return fail("No tools")
        assertEquals(VoiceDelegator.tools.mapNotNull { it["name"]?.jsonPrimitive?.content }, tools.mapNotNull { it["name"]?.stringValue })
        assertTrue(tools.all { it["type"] == str("function") && it["parameters"]?.get("type") == str("object") })

        fun decoded(command: LiveCommand): JsonElement = parsed(command.encoded())
        assertEquals(
            obj(
                "type" to str("response.item.create"),
                "event_id" to str("i1"),
                "item" to obj(
                    "type" to str("function_call_output"),
                    "call_id" to str("call_1"),
                    "output" to str("{\"ok\":true}"),
                ),
            ),
            decoded(LiveCommand.ResponseItemCreate(eventID = "i1", item = VoiceDelegator.InputItem.ToolOutput(callID = "call_1", output = "{\"ok\":true}").liveItem)),
        )
        assertEquals(
            obj(
                "type" to str("message"),
                "role" to str("user"),
                "content" to arr(obj("type" to str("input_text"), "text" to str("Answer now."))),
            ),
            decoded(LiveCommand.ResponseItemCreate(eventID = "i2", item = VoiceDelegator.InputItem.User("Answer now.").liveItem))["item"],
        )
        assertEquals(obj("type" to str("response.create"), "event_id" to str("c1")), decoded(LiveCommand.ResponseCreate(eventID = "c1")))
        assertEquals(
            obj(
                "type" to str("session.update"),
                "event_id" to str("u1"),
                "session" to obj(
                    "delegation" to obj(
                        "type" to str("responses"),
                        "responses" to obj("instructions" to str("New screen.")),
                    ),
                ),
            ),
            decoded(LiveCommand.UpdateDelegatorInstructions(eventID = "u1", instructions = "New screen.")),
        )
    }

    @Test fun responsesDelegationDecodesNestedBackendEvents() {
        val cases: List<Pair<String, LiveServerEvent>> = listOf(
            """{"type":"session.delegation.created","event_id":"e1","offset_ms":3600,"delegation":{"id":"del_1","type":"delegation","target":"responses","response_id":"resp_1"}}""" to
                LiveServerEvent.DelegationCreated(id = "del_1", target = "responses", offsetMilliseconds = 3_600),
            """{"type":"response.event","event_id":"e2","delegation_id":"del_1","event":{"type":"response.created","sequence_number":0,"response":{"id":"resp_1","status":"in_progress","output":[],"tools":[],"instructions":null}}}""" to
                LiveServerEvent.ResponseEvent(delegationID = "del_1", event = LiveResponseEvent.Created(responseID = "resp_1")),
            """{"type":"response.event","event_id":"e3","delegation_id":"del_1","event":{"type":"response.output_item.done","output_index":0,"sequence_number":5,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"list_projects","arguments":"{}","status":"completed"}}}""" to
                LiveServerEvent.ResponseEvent(delegationID = "del_1", event = LiveResponseEvent.FunctionCall(VoiceDelegator.ToolCall(callID = "call_1", name = "list_projects", arguments = "{}"))),
            """{"type":"response.event","event_id":"e4","delegation_id":"del_1","event":{"type":"response.output_item.done","output_index":0,"sequence_number":9,"item":{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"You have one project.","annotations":[]}]}}}""" to
                LiveServerEvent.ResponseEvent(delegationID = "del_1", event = LiveResponseEvent.Message("You have one project.")),
            """{"type":"response.event","event_id":"e5","delegation_id":"del_1","event":{"type":"response.completed","sequence_number":10,"response":{"id":"resp_2","status":"completed","output":[]}}}""" to
                LiveServerEvent.ResponseEvent(delegationID = "del_1", event = LiveResponseEvent.Finished(responseID = "resp_2", status = "completed", error = null)),
            """{"type":"response.event","event_id":"e6","delegation_id":"del_1","event":{"type":"response.failed","sequence_number":3,"response":{"id":"resp_3","status":"failed","error":{"code":"server_error","message":"The backend failed."}}}}""" to
                LiveServerEvent.ResponseEvent(delegationID = "del_1", event = LiveResponseEvent.Finished(responseID = "resp_3", status = "failed", error = "The backend failed.")),
            """{"type":"response.event","event_id":"e7","delegation_id":"del_1","event":{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"You have","sequence_number":4,"logprobs":[]}}""" to
                LiveServerEvent.Ignored,
        )
        for ((json, expected) in cases) {
            assertEquals(json, expected, LiveServerEvent.decode(json.toByteArray()))
        }
    }

    /**
     * Server Voice with GPT-Live: the session asks OpenAI to run the delegator
     * (Responses delegation on the server's key), the phone's delegator never
     * runs, and each round's tool calls run here and go back over the call.
     */
    @Test fun serverVoiceRunsTheDelegatorOnTheServerAndItsToolsOnThePhone() = runBlocking {
        val shared = resetSharedVoiceState()
        val project = OpenCodeProject(id = "ios", name = "opencode-ios", directory = "/Users/kit/code/open-source/opencode-ios")
        val configs = Locked(listOf<RealtimeSessionConfig>())
        val submitted = Locked(listOf<List<VoiceDelegator.InputItem>>())
        val completed = Locked(listOf<List<String>>())
        val rounds = Locked(
            listOf(
                Result.success(VoiceDelegator.Response(id = "resp_1", calls = listOf(VoiceDelegator.ToolCall(callID = "call_1", name = "list_projects", arguments = "{}")), text = "")),
                Result.success(VoiceDelegator.Response(id = "resp_2", calls = emptyList(), text = "You have one project, opencode-ios.")),
            ),
        )
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val state = shared.run { VoiceFeature.State(server, session).copy(serverVoiceSupport = ServerVoiceSupport.supported) }
        val phoneDelegatorRan = Locked(false)

        val effects = detachedScope(coroutineContext)
        val store = TestStore(state, VoiceFeature(), effects) {
            serverVoiceDependencies(shared, at = 4_000, start = { _, _ -> stream.consumeAsFlow() }).invoke(this)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ ->
                phoneDelegatorRan.set(true)
                throw OpenCodeClientError("unused")
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet()) },
            )
            this[VoiceCallClient] = speechVoiceCall(
                start = { key, config ->
                    assertTrue(key.isEmpty())
                    configs.update { it + config }
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                delegatorRound = { delegationID ->
                    assertEquals("del_1", delegationID)
                    rounds.withValue { it.drop(1) to it.first() }
                },
                submitDelegatorInput = { delegationID, input ->
                    assertEquals("del_1", delegationID)
                    submitted.update { it + listOf(input) }
                    true
                },
                completeFunction = { callID, output -> completed.update { it + listOf(listOf(callID, output)) } },
                end = { stream.close() },
            )
        }

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Server 1", VoiceProviderOption.Brand.openAI),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = true,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(4_000))
        }

        stream.trySend(VoiceCallEvent.ServerDelegation(callID = "del_1"))
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.ServerDelegation(callID = "del_1"))) {
            val captured = it.tail.capture()
            it.copy(
                replyWatch = it.replyWatch.noteUserActed().transition(ReplyWatch.Event.DelegatorStarted(requestID = "del_1")).first,
                tail = captured.tail.accept(captured.capture),
                delegatorWork = listOf("del_1"),
            )
        }
        val reply = "You have one project, opencode-ios."
        store.receive(
            VoiceFeature.Action.DelegatorFinished(
                callID = "del_1",
                result = Result.success(VoiceDelegator.Outcome(reply = reply, responseID = "resp_2", unanswered = emptyList(), spoken = true)),
            ),
            timeout = 5.seconds,
        ) {
            it.copy(
                delegatorWork = emptyList(),
                delegatorResponseID = "resp_2",
                replyWatch = it.replyWatch
                    .transition(ReplyWatch.Event.Sent(emptyList())).first
                    .transition(ReplyWatch.Event.DelegatorFinished(requestID = "del_1", spoken = reply)).first,
            )
        }

        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(4_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertTrue("The phone's delegator must not run on a Server Voice call", !phoneDelegatorRan.value)
        // The session JSON the server passes through asks for Responses delegation.
        assertEquals(1, configs.value.size)
        assertEquals(server, configs.value[0].serverVoice)
        val delegation = configs.value[0].liveDelegation as? LiveDelegation.Responses ?: return@runBlocking fail("Server Voice must delegate to Responses")
        assertEquals(VoiceDelegator.model, delegation.backend.model)
        assertTrue(delegation.backend.instructions.contains("\"Server Voice\""))
        assertEquals(str("responses"), configs.value[0].liveSessionConfig().sessionPayload(includeAudioFormat = false)["delegation"]?.get("type"))
        // The tool ran on the phone and its result went back over the call.
        assertEquals(1, submitted.value.size)
        val output = submitted.value.firstOrNull()?.firstOrNull() as? VoiceDelegator.InputItem.ToolOutput ?: return@runBlocking fail("No tool result went back")
        assertEquals("call_1", output.callID)
        assertTrue(output.output.contains(""""name":"opencode-ios""""))
        // The backend's answer reached GPT-Live without the phone: nothing to append.
        assertEquals(listOf(listOf("del_1", "")), completed.value)
    }

    /**
     * On the phone's own key (This Phone) GPT-Live keeps client delegation
     * and the phone's delegator.
     */
    @Test fun phoneKeyCallsKeepClientDelegationAndThePhoneDelegator() = runBlocking {
        val shared = resetSharedVoiceState()
        val configs = Locked(listOf<RealtimeSessionConfig>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val state = shared.run {
            shared(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
            shared(SharedKeys.openAIAPIKey).set("sk-phone")
            shared(SharedKeys.verifiedVoiceKeyFingerprint).set(VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
            VoiceFeature.State(server, session)
        }

        val effects = detachedScope(coroutineContext)
        val store = TestStore(state, VoiceFeature(), effects) {
            serverVoiceDependencies(
                shared, at = 5_000,
                start = { key, config ->
                    assertEquals("sk-phone", key)
                    configs.update { it + config }
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ).invoke(this)
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { _, _, _ -> throw OpenCodeClientError("unused") }
        }

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                startedTarget = startedTarget(),
                activeVoice = CallVoice.marin,
                usesDelegator = true,
                diagnosesFailure = true,
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(5_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(5_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertEquals(1, configs.value.size)
        assertNull(configs.value[0].serverVoice)
        assertEquals(LiveDelegation.client, configs.value[0].liveDelegation)
        assertEquals(obj("type" to str("client")), configs.value[0].liveSessionConfig().sessionPayload(includeAudioFormat = false)["delegation"])
    }

    /**
     * A phone-key delegation still runs the phone's delegator against
     * `/v1/responses` with the phone's key, and never asks the call for rounds.
     */
    @Test fun phoneKeyDelegationRunsThePhoneDelegator() = runBlocking {
        val shared = resetSharedVoiceState()
        val project = OpenCodeProject(id = "ios", name = "opencode-ios", directory = "/Users/kit/code/open-source/opencode-ios")
        val requests = Locked(listOf<List<VoiceDelegator.InputItem>>())
        val completed = Locked(listOf<List<String>>())
        val callRoundAsked = Locked(false)
        val state = shared.run {
            shared(SharedKeys.openAIAPIKey).set("sk-phone")
            VoiceFeature.State(server, session)
                .withPhase(VoiceFeature.State.Phase.Live)
                .copy(usesDelegator = true)
                .recording(VoiceTranscriptTail.Role.user, "Which projects do I have?", id = "u1")
        }

        val effects = detachedScope(coroutineContext)
        val store = TestStore(state, VoiceFeature(), effects) {
            this[SharedStore] = shared.store
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(6_000))
            this[VoiceDelegatorClient] = VoiceDelegatorClient(isEnabled = true) { key, input, previous ->
                assertEquals("sk-phone", key)
                val round = requests.withValue { (it + listOf(input)).let { all -> all to all.size } }
                if (round == 1) {
                    assertNull(previous)
                    VoiceDelegator.Response(id = "resp_1", calls = listOf(VoiceDelegator.ToolCall(callID = "call_1", name = "list_projects", arguments = "{}")), text = "")
                } else {
                    assertEquals("resp_1", previous)
                    VoiceDelegator.Response(id = "resp_2", calls = emptyList(), text = "You have one project, opencode-ios.")
                }
            }
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeSnapshot = { OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet()) },
            )
            this[VoiceCallClient] = speechVoiceCall(
                delegatorRound = {
                    callRoundAsked.set(true)
                    Result.failure(OpenCodeClientError("unused"))
                },
                completeFunction = { callID, output -> completed.update { it + listOf(listOf(callID, output)) } },
            )
        }

        store.send(VoiceFeature.Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = obj()))) {
            val captured = it.tail.capture()
            it.copy(
                replyWatch = it.replyWatch.noteUserActed().transition(ReplyWatch.Event.DelegatorStarted(requestID = "d1")).first,
                tail = captured.tail.accept(captured.capture),
                delegatorWork = listOf("d1"),
            )
        }
        val reply = "You have one project, opencode-ios."
        store.receive(
            VoiceFeature.Action.DelegatorFinished(callID = "d1", result = Result.success(VoiceDelegator.Outcome(reply = reply, responseID = "resp_2", unanswered = emptyList()))),
            timeout = 5.seconds,
        ) {
            it.copy(
                delegatorWork = emptyList(),
                delegatorResponseID = "resp_2",
                replyWatch = it.replyWatch
                    .transition(ReplyWatch.Event.Sent(emptyList())).first
                    .transition(ReplyWatch.Event.DelegatorFinished(requestID = "d1", spoken = reply)).first,
            )
        }
        store.finish()
        effects.cancel()

        assertTrue("A phone-key call has no rounds on the call", !callRoundAsked.value)
        assertEquals(2, requests.value.size)
        val request = requests.value.firstOrNull()?.firstOrNull() as? VoiceDelegator.InputItem.User ?: return@runBlocking fail("No request")
        assertTrue(request.text.contains("Which projects do I have?"))
        val output = requests.value.lastOrNull()?.firstOrNull() as? VoiceDelegator.InputItem.ToolOutput ?: return@runBlocking fail("No tool result")
        assertEquals("call_1", output.callID)
        assertTrue(output.output.contains(""""name":"opencode-ios""""))
        assertEquals(listOf(listOf("d1", VoiceHandoff.completion(reply))), completed.value)
    }
}
