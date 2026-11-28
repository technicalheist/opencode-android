package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.ServerLinkClient
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

/** Which voice provider new calls use when a server brokers them. */
@Serializable
enum class ServerVoiceProvider(val rawValue: String) {
    @SerialName("openai") openAI("openai"),
    @SerialName("gemini-live") geminiLive("gemini-live");

    val id: ServerVoiceProvider get() = this

    val title: String
        get() = when (this) {
            openAI -> "OpenAI"
            geminiLive -> "Gemini Live"
        }

    companion object {
        val allCases: List<ServerVoiceProvider> get() = entries
    }
}

/** Whether the connected OpenCode server offers `POST /api/experimental/voice/session`. */
@Serializable
enum class ServerVoiceSupport { unknown, checking, supported, unsupported }

/** Request body for `POST /api/experimental/voice/session`. */
data class ServerVoiceSessionRequest(
    val model: String,
    val session: RealtimeJSON? = null,
    val transport: ServerVoiceTransportRequest,
) {
    /** `{model, session?, transport}`; `session` is left out when null. */
    val json: RealtimeJSON
        get() {
            val body = linkedMapOf<String, JsonElement>("model" to JsonPrimitive(model))
            if (session != null) body["session"] = session
            body["transport"] = transport.json
            return JsonObject(body)
        }
}

/** Transport negotiation input for `POST /api/experimental/voice/session`. */
sealed interface ServerVoiceTransportRequest {
    data class Webrtc(val sdp: String, val dcid: Int?) : ServerVoiceTransportRequest
    data object Websocket : ServerVoiceTransportRequest

    val json: RealtimeJSON
        get() = when (this) {
            is Webrtc -> {
                val body = linkedMapOf<String, JsonElement>("type" to JsonPrimitive("webrtc"), "sdp" to JsonPrimitive(sdp))
                if (dcid != null) body["dcid"] = JsonPrimitive(dcid)
                JsonObject(body)
            }
            Websocket -> JsonObject(mapOf("type" to JsonPrimitive("websocket")))
        }
}

/** Transport negotiation output from `POST /api/experimental/voice/session`. */
sealed interface ServerVoiceTransportResponse {
    data class Webrtc(val sdp: String) : ServerVoiceTransportResponse
    data class Websocket(val url: String, val token: String, val expiresAt: Long?) : ServerVoiceTransportResponse

    companion object {
        /** Throws on an unknown type or a missing field. */
        fun decode(json: JsonElement): ServerVoiceTransportResponse {
            val container = json as? JsonObject ?: throw IllegalArgumentException("Expected an object")
            fun string(key: String): String =
                (container[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("Missing $key")
            return when (val type = string("type")) {
                "webrtc" -> Webrtc(sdp = string("sdp"))
                "websocket" -> {
                    val expiresAt = when (val value = container["expiresAt"]) {
                        null, kotlinx.serialization.json.JsonNull -> null
                        is JsonPrimitive -> if (value.isString) throw IllegalArgumentException("expiresAt") else value.longOrNull
                            ?: throw IllegalArgumentException("expiresAt")
                        else -> throw IllegalArgumentException("expiresAt")
                    }
                    Websocket(url = string("url"), token = string("token"), expiresAt = expiresAt)
                }
                else -> throw IllegalArgumentException("Unknown voice transport type: $type")
            }
        }
    }
}

/** `{data: {transport}}`. */
object ServerVoiceSessionEnvelope {
    fun transport(data: ByteArray): ServerVoiceTransportResponse {
        val root = VoiceWireJson.parseToJsonElement(data.decodeToString()) as? JsonObject ?: throw IllegalArgumentException()
        val payload = root["data"] as? JsonObject ?: throw IllegalArgumentException()
        return ServerVoiceTransportResponse.decode(payload["transport"] ?: throw IllegalArgumentException())
    }
}

/**
 * One model `GET /api/experimental/voice/models` lists. `available` means
 * the server holds a key for it; `provider` is who runs it ("openai",
 * "opencode" for OpenCode-hosted, "xai", "google").
 */
@Serializable
data class ServerVoiceModel(
    val model: String,
    val provider: String? = null,
    val available: Boolean,
)

/**
 * `GET /api/experimental/voice/models`: the voice models a server knows, and
 * which it holds a key for.
 */
object ServerVoiceModels {
    const val routePath = "/api/experimental/voice/models"

    @Serializable
    data class Envelope(val data: Payload) {
        @Serializable
        data class Payload(val models: List<ServerVoiceModel>)
    }

    /**
     * Whether a server listing `models` can broker a call: it holds a key for GPT-Live, Realtime or
     * Gemini Live. Grok never counts, since a Grok call always uses the phone's xAI key
     * (`VoiceRoute.resolve`). Kept until Settings' picker moves to the Voice Provider page.
     */
    fun offersVoice(models: List<ServerVoiceModel>): Boolean = models.any { it.available && brokered(it.model) != null }

    /**
     * Whether a server listing `models` starts `call` through its OpenCode
     * Console connection, which bills that connection's workspace.
     */
    fun hostedByOpenCode(call: VoiceRoute.ServerRoute, models: List<ServerVoiceModel>): Boolean =
        models.any { it.available && it.provider == "opencode" && brokered(it.model) == call }

    /** Whether a server listing `models` holds a key for `call`. */
    fun offers(call: VoiceRoute.ServerRoute, models: List<ServerVoiceModel>): Boolean =
        models.any { it.available && brokered(it.model) == call }

    /**
     * The call a server's model brokers, by family, as the server resolves a
     * model it's asked for: any `gpt-live-…` (`gpt-live-1`, `gpt-live-1.1`) for GPT-Live.
     */
    private fun brokered(model: String): VoiceRoute.ServerRoute? {
        if (model.startsWith("gpt-live")) return VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.gptLive)
        if (model.startsWith("gpt-realtime")) return VoiceRoute.ServerRoute.OpenAI(VoiceRoute.OpenAI.realtime)
        if (model.startsWith("gemini-") && model.contains("live")) return VoiceRoute.ServerRoute.GeminiLive
        return null
    }
}

/**
 * A short, stable name for a saved server that carries none of its addresses or credentials. Ported
 * here as a top-level function because [VoiceServerChoice] needs it before
 * `VoiceDelegatorWorkspace` exists (wave 3); the workspace should call this rather than redeclare
 * the hash.
 */
fun voiceServerID(server: OpenCodeServer): String =
    "srv_" + MessageDigest.getInstance("SHA-256").digest(server.persistenceID.toByteArray())
        .take(4).joinToString("") { "%02x".format(it) }

/**
 * Settings › Voice › Voice Provider: where Server Voice calls go. Stored
 * without addresses: a saved server by [voiceServerID].
 */
@Serializable(with = VoiceServerChoice.Serializer::class)
sealed interface VoiceServerChoice {
    /**
     * The first provider that can place the call ([VoicePick.resolve]). The
     * default, and what earlier builds' “connected server” reads as: it
     * already moved to the phone's key when the server couldn't.
     */
    data object Automatic : VoiceServerChoice

    /**
     * Read only: an earlier pin of “whichever server is connected”.
     * [pinningConnected] turns it into that server's own pin, so a
     * pick never follows a server switch.
     */
    data object Connected : VoiceServerChoice

    /** None: calls use this iPhone's own OpenAI or xAI key. */
    data object Phone : VoiceServerChoice
    data class Saved(val serverID: String) : VoiceServerChoice

    val rawValue: String
        get() = when (this) {
            Automatic -> ""
            Connected -> "connected"
            Phone -> "phone"
            is Saved -> serverID
        }

    /** A legacy [Connected] pin as the server connected now; any other choice as it is. */
    fun pinningConnected(server: OpenCodeServer?): VoiceServerChoice {
        if (this != Connected || server == null) return this
        return of(server)
    }

    /** The saved server this choice names, if it's still saved. */
    fun savedServer(servers: List<OpenCodeServer>): OpenCodeServer? {
        val saved = this as? Saved ?: return null
        return servers.firstOrNull { voiceServerID(it) == saved.serverID }
    }

    companion object {
        fun of(rawValue: String): VoiceServerChoice = when (rawValue) {
            "" -> Automatic
            "connected" -> Connected
            "phone" -> Phone
            else -> Saved(rawValue)
        }

        fun of(server: OpenCodeServer): VoiceServerChoice = Saved(voiceServerID(server))
    }

    /** Stored as its raw value. */
    object Serializer : KSerializer<VoiceServerChoice> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("VoiceServerChoice", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: VoiceServerChoice) = encoder.encodeString(value.rawValue)
        override fun deserialize(decoder: Decoder): VoiceServerChoice = of(decoder.decodeString())
    }
}

sealed class ServerVoiceError(message: String) : Exception(message) {
    data object UnsupportedRoute : ServerVoiceError(unsupportedServerNotice)

    data object InvalidServerURL : ServerVoiceError("Couldn’t start voice: Enter a valid HTTP or HTTPS server URL.")

    data class Rejected(val status: Int, override val message: String) : ServerVoiceError(message)

    data object InvalidResponse : ServerVoiceError("Couldn’t start voice: The server returned an invalid response.")

    val errorDescription: String get() = message ?: ""

    override fun fillInStackTrace(): Throwable = this

    companion object {
        const val unsupportedServerNotice = "This server needs a newer OpenCode for voice calls"

        fun fromHTTPResponse(statusCode: Int, data: ByteArray): ServerVoiceError {
            if (statusCode == 404) return UnsupportedRoute
            val extracted = extractServerMessage(data)
            return Rejected(status = statusCode, message = formattedMessage(extracted, statusCode))
        }

        fun formattedMessage(serverMessage: String?, statusCode: Int): String {
            val raw = serverMessage?.trim()
            if (raw.isNullOrEmpty()) return "Couldn’t start voice (HTTP $statusCode)."
            val normalized = raw.replace("'", "’")
            if (normalized.startsWith("Couldn’t start voice")) return normalized
            return "Couldn’t start voice: $normalized"
        }

        fun extractServerMessage(data: ByteArray): String? {
            if (data.isEmpty()) return null
            val text = data.decodeToString()
            val json = try { VoiceWireJson.parseToJsonElement(text) } catch (_: Exception) { null }
            if (json != null) extractMessage(json)?.let { return it }
            val trimmed = text.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("<")) return trimmed
            return null
        }

        private fun extractMessage(json: JsonElement): String? = when (json) {
            is JsonPrimitive -> if (json.isString) json.content.trim().ifEmpty { null } else null
            is JsonObject -> {
                var found: String? = null
                for (key in listOf("message", "detail", "reason")) {
                    val value = json[key]?.stringValue?.trim()
                    if (!value.isNullOrEmpty()) {
                        found = value
                        break
                    }
                }
                if (found == null) {
                    for (key in listOf("error", "data")) {
                        val nested = json[key] ?: continue
                        val value = extractMessage(nested)
                        if (value != null) {
                            found = value
                            break
                        }
                    }
                }
                found
            }
            is JsonArray -> null
        }
    }
}

object ServerVoiceSupportDetector {
    const val routePath = "/api/experimental/voice/session"

    /** Parses an OpenAPI 3 document to determine whether `POST /api/experimental/voice/session` is supported. */
    fun support(openAPIJSON: ByteArray): ServerVoiceSupport {
        val root = try { VoiceWireJson.parseToJsonElement(openAPIJSON.decodeToString()) } catch (_: Exception) { null }
        val top = root as? JsonObject ?: return ServerVoiceSupport.unknown
        val paths = top["paths"] as? JsonObject ?: return ServerVoiceSupport.unknown
        val operations = paths[routePath] as? JsonObject
        if (operations != null && operations["post"] != null) return ServerVoiceSupport.supported
        return ServerVoiceSupport.unsupported
    }
}

/**
 * The server's voice routes. The support check and model list answer "unknown"/null on failure, and
 * a session start is not repeated.
 */
class ServerVoiceClient(
    val checkSupport: suspend (server: OpenCodeServer) -> ServerVoiceSupport,
    /**
     * The voice models the server lists: empty from a server without the list
     * (a 404), null when it doesn't answer or answers otherwise.
     */
    val models: suspend (server: OpenCodeServer) -> List<ServerVoiceModel>?,
    /** Throws [ServerVoiceError]. */
    val createSession: suspend (server: OpenCodeServer, request: ServerVoiceSessionRequest) -> ServerVoiceTransportResponse,
) {
    companion object Key : DependencyKey<ServerVoiceClient> {
        private fun makeRequest(
            server: OpenCodeServer,
            path: String,
            method: String = "GET",
            body: ByteArray? = null,
        ): Request? {
            val url = dependency(ServerLinkClient).resolve(server).endpoint(path) ?: return null
            val builder = Request.Builder().url(url).header("Accept", "application/json")
            if (body != null) {
                builder.header("Content-Type", "application/json")
                builder.method(method, body.toRequestBody(voiceJsonMediaType))
            } else {
                builder.method(method, null)
            }
            server.authorize(builder)
            return builder.build()
        }

        private suspend fun send(request: Request, timeoutSeconds: Long): VoiceHttpResponse =
            dependency(OpenCodeTransport).request.withVoiceTimeout(timeoutSeconds).voiceSend(request)

        override val liveValue: ServerVoiceClient = ServerVoiceClient(
            checkSupport = { server ->
                val request = makeRequest(server, "/openapi.json")
                if (request == null) {
                    ServerVoiceSupport.unknown
                } else {
                    try {
                        val response = send(request, 10)
                        when {
                            response.statusCode == 404 -> ServerVoiceSupport.unsupported
                            response.statusCode !in 200..<300 -> ServerVoiceSupport.unknown
                            else -> ServerVoiceSupportDetector.support(response.body)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        ServerVoiceSupport.unknown
                    }
                }
            },
            models = models@{ server ->
                val request = makeRequest(server, ServerVoiceModels.routePath) ?: return@models null
                val response = try {
                    send(request, 10)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@models null
                }
                if (response.statusCode == 404) return@models emptyList()
                if (response.statusCode !in 200..<300) return@models null
                try {
                    VoiceWireJson.decodeFromString(ServerVoiceModels.Envelope.serializer(), response.body.decodeToString()).data.models
                } catch (_: Exception) {
                    null
                }
            },
            createSession = { server, sessionRequest ->
                val body = sessionRequest.json.encodedBytes()
                val request = makeRequest(server, ServerVoiceSupportDetector.routePath, method = "POST", body = body)
                    ?: throw ServerVoiceError.InvalidServerURL
                val response = try {
                    send(request, 15)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw ServerVoiceError.Rejected(
                        status = 0,
                        message = ServerVoiceError.formattedMessage(error.localizedMessage ?: error::class.java.simpleName, 0),
                    )
                }
                if (response.statusCode !in 200..<300) {
                    AppLog.voice.notice { "Server voice session HTTP ${response.statusCode}" }
                    throw ServerVoiceError.fromHTTPResponse(response.statusCode, response.body)
                }
                try {
                    ServerVoiceSessionEnvelope.transport(response.body)
                } catch (_: Exception) {
                    throw ServerVoiceError.InvalidResponse
                }
            },
        )

        override val testValue: ServerVoiceClient = ServerVoiceClient(
            checkSupport = { ServerVoiceSupport.unknown },
            models = { null },
            createSession = { _, _ -> throw ServerVoiceError.UnsupportedRoute },
        )
    }
}
