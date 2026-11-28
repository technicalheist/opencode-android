package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.time.Instant
import java.util.UUID

class VoiceServerPickerTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:4096", username = "opencode", password = "secret", name = "My Mac")
    private val studio = OpenCodeServer(url = "http://studio.local:4096", username = "opencode", password = "studio", name = "Studio")
    private val session = OpenCodeSession(
        id = "ses_voice",
        serverTitle = "Voice Server",
        projectID = "proj_1",
        directory = "/Users/kit/code/open-source/opencode-ios",
        updatedAt = Instant.ofEpochSecond(1_700_000_000),
    )
    private val gptLive = ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = true)

    private val presenceID = UUID(0, 0)

    /** Each test here gets a fresh in-memory store. */
    private fun resetSharedState(): SpeechTestShared = SpeechTestShared()

    /** `servers` saved and `server` the one in use, with `choice` in Voice Server. */
    private fun SpeechTestShared.save(servers: List<OpenCodeServer>, choice: VoiceServerChoice = VoiceServerChoice.Automatic) = run {
        shared(ServerSharedKeys.savedServers).set(servers)
        shared(ServerSharedKeys.savedServer).set(server)
        shared(VoiceSharedKeys.voiceServerChoice).set(choice)
    }

    private fun SpeechTestShared.setSupport(support: Map<String, ServerVoiceSupport>) = run {
        shared(VoiceSharedKeys.serverVoiceSupportByServer).set(support)
    }

    private fun SpeechTestShared.savePhoneKey() = run {
        shared(SharedKeys.openAIAPIKey).set("sk-phone")
        shared(SharedKeys.verifiedVoiceKeyFingerprint).set(VoiceKeyFingerprint.make(key = "sk-phone", model = VoiceEngine.gptLive.modelID))
    }

    private fun transcriptRecord(shared: SpeechTestShared): VoiceTranscriptRecord? =
        shared.run { shared(VoiceSharedKeys.voiceTranscripts).value["${server.id}:${session.id}"] }

    // MARK: Which servers offer it

    /**
     * The documented model list decodes. A server can broker a call only with
     * a key for GPT-Live, Realtime or Gemini Live: Grok alone never counts.
     */
    @Test fun aServerOffersVoiceWithAKeyForAModelItCanBroker() {
        val list = """
            { "data": { "models": [
              { "model": "gpt-live-1", "provider": "openai", "transports": ["webrtc"], "available": false },
              { "model": "gpt-realtime-2.1", "provider": "opencode", "transports": ["webrtc"], "available": false },
              { "model": "grok-voice-1", "provider": "xai", "transports": ["websocket"], "available": true },
              { "model": "gemini-3.8-live", "provider": "google", "transports": ["websocket"], "available": false }
            ], "default": "gpt-live-1" } }
        """.trimIndent()
        val models = Json { ignoreUnknownKeys = true }.decodeFromString(ServerVoiceModels.Envelope.serializer(), list).data.models
        assertEquals(
            listOf(
                ServerVoiceModel(model = "gpt-live-1", provider = "openai", available = false),
                ServerVoiceModel(model = "gpt-realtime-2.1", provider = "opencode", available = false),
                ServerVoiceModel(model = "grok-voice-1", provider = "xai", available = true),
                ServerVoiceModel(model = "gemini-3.8-live", provider = "google", available = false),
            ),
            models,
        )
        assertFalse("Only Grok has a key", ServerVoiceModels.offers(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive), models))
        assertTrue(ServerVoiceModels.offers(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive), listOf(ServerVoiceModel(model = "gpt-live-1", available = true))))
        assertTrue(
            "A server listing either GPT-Live version brokers the phone's gpt-live-1.1 calls",
            ServerVoiceModels.offers(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive), listOf(ServerVoiceModel(model = "gpt-live-1.1", available = true))),
        )
        assertTrue(
            ServerVoiceModels.offers(
                VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.realtime),
                listOf(ServerVoiceModel(model = "gpt-realtime-2.1", available = true)),
            ),
        )
        assertTrue(ServerVoiceModels.offers(VoiceRoute.ServerRoute.GeminiLive, listOf(ServerVoiceModel(model = "gemini-3.8-live", available = true))))
        assertFalse(ServerVoiceModels.offers(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive), listOf(ServerVoiceModel(model = "gpt-live-1", available = false))))
        assertFalse("A server without the list (a 404)", ServerVoiceModels.offers(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive), emptyList()))
    }

    private sealed interface Reply {
        data class Json(val status: Int, val body: String) : Reply
        data class Status(val status: Int) : Reply
        data object Failure : Reply
    }

    /**
     * The models come from `GET /api/experimental/voice/models`. A server
     * without the route (404) offers none; one that doesn't answer, or
     * answers otherwise, says nothing.
     *
     * An OkHttp interceptor on the transport answers the same way.
     */
    @Test fun onlyAnAnswerSaysWhichModelsAServerHas() = runBlocking {
        fun ask(reply: Reply): Pair<List<ServerVoiceModel>?, List<String>> {
            val requests = mutableListOf<String>()
            val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                val (status, body) = when (reply) {
                    is Reply.Json -> reply.status to reply.body
                    is Reply.Status -> reply.status to ""
                    Reply.Failure -> throw ConnectException("Network is unreachable")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }).build()
            val values = DependencyValues.testing().update { this[OpenCodeTransport] = OpenCodeTransport(client, client, client, client) }
            val models = DependencyValues.withValues(values) { runBlocking { ServerVoiceClient.liveValue.models(server) } }
            return models to requests
        }
        val list = """{ "data": { "models": [{ "model": "gpt-live-1", "provider": "openai", "transports": ["webrtc"], "available": true }], "default": "gpt-live-1" } }"""

        val (models, requests) = ask(Reply.Json(status = 200, body = list))
        assertEquals(listOf(gptLive), models)
        assertEquals("GET /api/experimental/voice/models", requests.first())
        assertEquals(emptyList<ServerVoiceModel>(), ask(Reply.Status(404)).first)
        assertNull(ask(Reply.Status(503)).first)
        assertNull(ask(Reply.Json(status = 200, body = "<html>Sign in</html>")).first)
        assertNull(ask(Reply.Failure).first)
    }

    /**
     * Settings, the session and Home route by the chosen server's support,
     * never the connected server's. A picked server without the voice routes
     * fails rather than move to the phone's key. (Home's `VoiceRoutePreferences` here.)
     */
    @Test fun everyEntryPointRoutesByTheChosenServersSupport() {
        val shared = resetSharedState()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(studio))
        shared.savePhoneKey()
        shared.run { shared(SharedKeys.voiceModeEnabled).set(true) }
        val preferences = shared.run { VoiceRoutePreferences() }

        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.unsupported))
        assertEquals(VoiceRoute.Unavailable(VoiceRoute.Reason.ServerUnsupported), shared.run { preferences.route(server) })

        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.unsupported, studio.persistenceID to ServerVoiceSupport.supported))
        assertEquals(VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)), shared.run { preferences.route(server) })
    }

    // MARK: Calls

    private fun callDependencies(
        shared: SpeechTestShared,
        at: Long,
        checkSupport: suspend (OpenCodeServer) -> ServerVoiceSupport,
        recentMessages: suspend (OpenCodeServer, String, Int) -> List<String> = { _, _, _ -> emptyList() },
        start: suspend (String, RealtimeSessionConfig) -> kotlinx.coroutines.flow.Flow<VoiceCallEvent>,
        end: suspend () -> Unit,
    ): DependencyValues.Builder.() -> Unit = {
        this[SharedStore] = shared.store
        this[DateClient] = DateClient.constant(Instant.ofEpochSecond(at))
        // Android: the call's ongoing-notification ID and the start cue's clock.
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = TestClock()
        this[ServerVoiceClient] = ServerVoiceClient.testValue.let { ServerVoiceClient(checkSupport = checkSupport, models = it.models, createSession = it.createSession) }
        this[VoiceWorkClient] = VoiceWorkClient.testValue.let { work ->
            VoiceWorkClient(submit = work.submit, awaitReply = work.awaitReply, checkSessionActive = work.checkSessionActive, recentMessages = recentMessages)
        }
        this[VoiceCallClient] = speechVoiceCall(start = start, end = end)
    }

    /**
     * A chosen saved server is asked, keeps its support and brokers the
     * session (`config.serverVoice`). The call's target, recent messages and
     * transcript stay on the call's own server.
     */
    @Test fun aChosenServerBrokersTheCallWhileItsWorkStaysOnTheCallsServer() = runBlocking {
        val shared = resetSharedState()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.of(studio))
        val configs = Locked(listOf<RealtimeSessionConfig>())
        val probed = Locked(listOf<String>())
        val read = Locked(listOf<String>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val connectGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 1_000,
                checkSupport = { requested -> probed.update { it + requested.persistenceID }; ServerVoiceSupport.supported },
                recentMessages = { requested, _, _ -> read.update { it + requested.persistenceID }; emptyList() },
                start = { key, config ->
                    assertTrue(key.isEmpty())
                    configs.update { it + config }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                serverVoiceBroker = studio,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("Studio", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.supported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        }
        assertEquals(ServerVoiceSupport.supported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[studio.persistenceID] })
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.supported)))
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)), usesDelegator = false))
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(1_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(1_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertEquals(listOf(studio.persistenceID), probed.value)
        assertEquals(listOf(server.persistenceID), read.value)
        assertEquals(listOf(studio), configs.value.map { it.serverVoice })
    }

    /**
     * What a call knew of one broker's support isn't another's: with the
     * choice back on the connected server, the next start asks it.
     */
    @Test fun theNextStartAsksANewBrokerAfresh() = runBlocking {
        val shared = resetSharedState()
        shared.save(listOf(server, studio))
        val state = shared.run { VoiceFeature.State(server, session) }.copy(serverVoiceBroker = studio, serverVoiceSupport = ServerVoiceSupport.supported)
        val configs = Locked(listOf<RealtimeSessionConfig>())
        val probed = Locked(listOf<String>())
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val connectGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            state, VoiceFeature(), effects,
            callDependencies(
                shared, at = 2_000,
                checkSupport = { requested -> probed.update { it + requested.persistenceID }; ServerVoiceSupport.supported },
                start = { _, config ->
                    configs.update { it + config }
                    connectGate.await()
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                serverVoiceBroker = null,
                serverVoiceSupport = ServerVoiceSupport.unknown,
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.ServerVoiceSupportUpdated(ServerVoiceSupport.supported)) {
            it.copy(serverVoiceSupport = ServerVoiceSupport.supported)
        }
        assertEquals(ServerVoiceSupport.supported, shared.run { shared(VoiceSharedKeys.serverVoiceSupportByServer).value[server.persistenceID] })
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.ServerVoiceSupportChanged(ServerVoiceSupport.supported)))
        store.receive(VoiceFeature.Action.CallRouteResolved(VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)), usesDelegator = false))
        connectGate.complete(Unit)
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(2_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(2_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertEquals(listOf(server.persistenceID), probed.value)
        assertEquals(listOf(server), configs.value.map { it.serverVoice })
    }

    /**
     * This iPhone: with Server Voice on, calls use the phone's own key with no
     * probe or broker, as Settings, the session and Home agree. Without a key
     * nothing offers a call.
     */
    @Test fun thisIPhoneUsesThePhonesOwnKey() = runBlocking {
        val shared = resetSharedState()
        shared.save(listOf(server, studio), choice = VoiceServerChoice.Phone)
        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported, studio.persistenceID to ServerVoiceSupport.supported))
        shared.run { shared(SharedKeys.voiceModeEnabled).set(true) }
        assertFalse(shared.run { VoiceRoutePreferences().isAvailable(server) })

        shared.savePhoneKey()
        assertEquals(VoiceRoute.PhoneKey(VoiceEngine.gptLive), shared.run { VoiceRoutePreferences().route(server) })

        val configs = Locked(listOf<RealtimeSessionConfig>())
        val asked = Locked(false)
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 3_000,
                checkSupport = { asked.set(true); ServerVoiceSupport.supported },
                start = { key, config ->
                    assertEquals("sk-phone", key)
                    configs.update { it + config }
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(3_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(3_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertFalse("This iPhone asks no server", asked.value)
        assertEquals(listOf<OpenCodeServer?>(null), configs.value.map { it.serverVoice })
    }

    /**
     * A chosen server that's no longer saved: calls go through the connected
     * server, as they do by default.
     */
    @Test fun aChoiceNoLongerSavedBrokersThroughTheConnectedServer() = runBlocking {
        val shared = resetSharedState()
        shared.save(listOf(server), choice = VoiceServerChoice.of(studio))
        shared.setSupport(mapOf(server.persistenceID to ServerVoiceSupport.supported))
        val configs = Locked(listOf<RealtimeSessionConfig>())
        val asked = Locked(false)
        val stream = Channel<VoiceCallEvent>(Channel.UNLIMITED)
        val effects = detachedScope(coroutineContext)
        val store = TestStore(
            shared.run { VoiceFeature.State(server, session) }, VoiceFeature(), effects,
            callDependencies(
                shared, at = 4_000,
                checkSupport = { asked.set(true); ServerVoiceSupport.supported },
                start = { key, config ->
                    assertTrue(key.isEmpty())
                    configs.update { it + config }
                    stream.trySend(VoiceCallEvent.Connected)
                    stream.consumeAsFlow()
                },
                end = { stream.close() },
            ),
        )

        store.send(VoiceFeature.Action.Start) {
            it.copy(
                diagnosesFailure = true,
                callPlace = VoiceCallPlace("My Mac", VoiceProviderOption.Brand.openAI),
                startedTarget = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                activeRoute = VoiceRoute.Server(VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)),
                callPresenceID = presenceID,
            )
        }
        store.receive(VoiceFeature.Action.CallEvent(VoiceCallEvent.Connected)) {
            it.withPhase(VoiceFeature.State.Phase.Live).copy(connectedAt = Instant.ofEpochSecond(4_000))
        }
        stream.close()
        store.receive(VoiceFeature.Action.CallEnded(errorMessage = null)) {
            it.withPhase(VoiceFeature.State.Phase.Ended)
        }
        assertEquals(VoiceTranscriptRecord(endedAt = Instant.ofEpochSecond(4_000), tail = emptyList(), log = emptyList()), transcriptRecord(shared))
        store.receive(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.Ended))
        store.finish()
        effects.cancel()

        assertFalse("The connected server's support is known", asked.value)
        assertEquals(listOf(server), configs.value.map { it.serverVoice })
    }
}
