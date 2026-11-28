package ai.opencode.feature.voice

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant

/** Which voice engine new calls use. */
@Serializable
enum class VoiceEngine(val rawValue: String) {
    @SerialName("gpt-live") gptLive("gpt-live"),
    @SerialName("realtime") realtime("realtime"),

    /**
     * xAI's Grok Voice. It speaks the Realtime protocol, so it runs on the
     * Realtime coordinator and delegates straight into the session on screen.
     */
    @SerialName("grok") grok("grok");

    val id: VoiceEngine get() = this

    val title: String
        get() = when (this) {
            gptLive -> "GPT-Live"
            realtime -> "Realtime"
            grok -> "Grok Voice"
        }

    val modelID: String
        get() = when (this) {
            gptLive -> LiveSessionConfig.defaultModel
            realtime -> RealtimeSessionConfig.defaultModel
            grok -> RealtimeSessionConfig.grokModel
        }

    val provider: VoiceProvider
        get() = when (this) {
            gptLive, realtime -> VoiceProvider.openAI
            grok -> VoiceProvider.xAI
        }

    /**
     * How the voice model names itself when asked.
     */
    val selfDescription: String
        get() = when (this) {
            gptLive -> "GPT-Live ($modelID)"
            realtime -> "the Realtime model ($modelID)"
            grok -> "Grok Voice ($modelID) from xAI"
        }

    /** The saved key this engine's calls use. */
    fun apiKey(openAI: String?, xAI: String?): String? = when (provider) {
        VoiceProvider.openAI -> openAI
        VoiceProvider.xAI -> xAI
    }

    /**
     * Whether this engine's saved key is non-empty and matches the stored
     * preflight fingerprint for this engine's model.
     */
    fun hasVerifiedAPIKey(openAI: String?, xAI: String?, verifiedFingerprint: String?): Boolean {
        val key = (apiKey(openAI, xAI) ?: "").trim()
        if (key.isEmpty() || verifiedFingerprint == null) return false
        return verifiedFingerprint == VoiceKeyFingerprint.make(key = key, model = modelID)
    }

    /**
     * The engine to move to during the one-time GPT-Live default migration:
     * Realtime always moves (same OpenAI provider); Grok Voice moves only when
     * the saved OpenAI key is already verified for GPT-Live, so a Grok-only or
     * unverified OpenAI setup keeps working.
     */
    fun migratedToGPTLive(openAI: String?, verifiedFingerprint: String?): VoiceEngine = when (this) {
        gptLive, realtime -> gptLive
        grok -> if (gptLive.hasVerifiedAPIKey(openAI = openAI, xAI = null, verifiedFingerprint = verifiedFingerprint)) gptLive else grok
    }

    /** Provider and model shown on the Settings › Voice Model row. */
    val providerModelTitle: String get() = "${provider.displayName} · $title"

    companion object {
        val allCases: List<VoiceEngine> get() = entries
        fun ofRawValue(rawValue: String): VoiceEngine? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** Whose API a voice engine calls, and so whose key it needs. */
@Serializable
enum class VoiceProvider(val rawValue: String) {
    @SerialName("openAI") openAI("openAI"),
    @SerialName("xAI") xAI("xAI");

    /** Kotlin enums reserve `name`, so it is `displayName` here. */
    val displayName: String
        get() = when (this) {
            openAI -> "OpenAI"
            xAI -> "xAI"
        }

    val id: VoiceProvider get() = this
    val keyRowTitle: String get() = localized(R.string.voice_provider_key_row_title, displayName)
    val keyPlaceholder: String get() = localized(R.string.voice_provider_key_placeholder, displayName)
    val engines: List<VoiceEngine> get() = VoiceEngine.entries.filter { it.provider == this }

    companion object {
        val allCases: List<VoiceProvider> get() = listOf(openAI, xAI)
    }
}

/**
 * The SHA-256 fingerprint of a voice key and the model its preflight accepted it for, moved down to
 * Voice because [VoiceEngine.hasVerifiedAPIKey] needs it and Settings sits above Voice.
 */
object VoiceKeyFingerprint {
    fun make(key: String, model: String): String =
        MessageDigest.getInstance("SHA-256").digest("$model:$key".toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * One startup history item in `session.input` (`live-conversations.md`).
 * Developer and user messages use `input_text`; assistant messages use `output_text`.
 */
data class LiveInputMessage(val role: Role, val text: String) {
    @Serializable
    enum class Role(val rawValue: String) {
        @SerialName("developer") developer("developer"),
        @SerialName("user") user("user"),
        @SerialName("assistant") assistant("assistant"),
    }

    val json: RealtimeJSON
        get() {
            val partType = if (role == Role.assistant) "output_text" else "input_text"
            return RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("message"),
                "role" to RealtimeJSONs.string(role.rawValue),
                "content" to RealtimeJSONs.array(
                    listOf(RealtimeJSONs.obj("type" to RealtimeJSONs.string(partType), "text" to RealtimeJSONs.string(text))),
                ),
            )
        }
}

/**
 * Who runs GPT-Live's delegations (`live-delegation.md`): the app (`client`),
 * or a Responses model OpenAI runs with the session's own key (`responses`),
 * which Server Voice uses so the phone needs no key. Either way the phone
 * runs the delegator's OpenCode tools.
 */
sealed interface LiveDelegation {
    data object Client : LiveDelegation
    data class Responses(val backend: LiveResponsesDelegation) : LiveDelegation

    val json: RealtimeJSON
        get() = when (this) {
            Client -> RealtimeJSONs.obj("type" to RealtimeJSONs.string("client"))
            is Responses -> RealtimeJSONs.obj("type" to RealtimeJSONs.string("responses"), "responses" to backend.json)
        }

    companion object {
        val client: LiveDelegation get() = Client
        fun responses(backend: LiveResponsesDelegation): LiveDelegation = Responses(backend)
    }
}

/** `delegation.responses`: the backend model, its prompt, and its tools. */
data class LiveResponsesDelegation(
    val model: String,
    val instructions: String,
    /** Function tools, Responses shape (`{type: "function", name, description, parameters, strict}`). */
    val tools: RealtimeJSON,
    val reasoningEffort: String? = null,
) {
    val json: RealtimeJSON
        get() {
            val backend = linkedMapOf(
                "model" to RealtimeJSONs.string(model),
                "instructions" to RealtimeJSONs.string(instructions),
                "tools" to tools,
            )
            if (reasoningEffort != null) {
                backend["reasoning"] = RealtimeJSONs.obj("effort" to RealtimeJSONs.string(reasoningEffort))
            }
            return RealtimeJSONs.obj(backend)
        }
}

/** OpenAI GPT-Live session startup configuration (`wss://api.openai.com/v1/live/sessions`). */
data class LiveSessionConfig(
    val model: String = defaultModel,
    val voice: CallVoice = CallVoice.default,
    val instructions: String,
    val input: List<LiveInputMessage> = emptyList(),
    val sampleRate: Int = 24_000,
    /** Raise to Talk: whether the call starts at the ear (receiver) rather than on the speaker. */
    val isAtEar: Boolean = false,
    /** The server brokering the session through `POST /api/experimental/voice/session`. */
    val serverVoice: OpenCodeServer? = null,
    val delegation: LiveDelegation = LiveDelegation.client,
    /**
     * OpenCode Session Delegator: sends unbound spoken updates (`delegationID == null`)
     * via `session.instructions.append` because GPT-Live (measured on `gpt-live-1`) rejects `commentary`
     * without an active `delegation_id`.
     */
    val unboundSpeechUsesInstructions: Boolean = false,
) {
    fun sessionStart(eventID: String = "event_start"): RealtimeJSON = RealtimeJSONs.obj(
        "type" to RealtimeJSONs.string("session.start"),
        "event_id" to RealtimeJSONs.string(eventID),
        "session" to sessionPayload(includeAudioFormat = true),
    )

    /**
     * The `session` object. WebRTC negotiates the audio format in SDP, so it
     * leaves `audio.format` out.
     */
    fun sessionPayload(includeAudioFormat: Boolean): RealtimeJSON {
        val audio = linkedMapOf("output" to RealtimeJSONs.obj("voice" to RealtimeJSONs.string(voice.rawValue)))
        if (includeAudioFormat) {
            audio["format"] = RealtimeJSONs.obj("type" to RealtimeJSONs.string("audio/pcm"), "rate" to RealtimeJSONs.number(sampleRate))
        }
        val session = linkedMapOf(
            "model" to RealtimeJSONs.string(model),
            "instructions" to RealtimeJSONs.string(instructions),
            "audio" to RealtimeJSONs.obj(audio),
            "delegation" to delegation.json,
        )
        if (input.isNotEmpty()) {
            session["input"] = RealtimeJSONs.array(input.take(128).map { it.json })
        }
        return RealtimeJSONs.obj(session)
    }

    companion object {
        const val defaultModel = "gpt-live-1.1"
    }
}

sealed interface LiveCommand {
    data class SessionStart(val config: LiveSessionConfig, val eventID: String = "event_start") : LiveCommand

    /** Mono PCM16 little-endian at 24 kHz; must be an even number of bytes. */
    class AppendAudio(val data: ByteArray) : LiveCommand {
        override fun equals(other: Any?): Boolean = other is AppendAudio && other.data.contentEquals(data)
        override fun hashCode(): Int = data.contentHashCode()
        override fun toString(): String = "AppendAudio(${data.size} bytes)"
    }

    data class AppendInstructions(val eventID: String, val delegationID: String?, val content: String) : LiveCommand
    data class AppendThinking(val eventID: String, val delegationID: String?, val content: String) : LiveCommand
    data class AppendCommentary(val eventID: String, val delegationID: String?, val content: String) : LiveCommand
    data class Mute(val eventID: String) : LiveCommand
    data class Unmute(val eventID: String) : LiveCommand
    data class Close(val eventID: String = "event_close") : LiveCommand

    /** Responses delegation: a new prompt for the backend (`session.update`). */
    data class UpdateDelegatorInstructions(val eventID: String, val instructions: String) : LiveCommand

    /** Responses delegation: an item for the backend, such as a tool result. */
    data class ResponseItemCreate(val eventID: String, val item: RealtimeJSON) : LiveCommand

    /** Responses delegation: continue the backend once every tool result is in. */
    data class ResponseCreate(val eventID: String) : LiveCommand

    val json: RealtimeJSON
        get() = when (this) {
            is SessionStart -> config.sessionStart(eventID)
            is AppendAudio -> {
                val evenCount = data.size - (data.size % 2)
                val aligned = if (evenCount == data.size) data else data.copyOf(evenCount)
                RealtimeJSONs.obj(
                    "type" to RealtimeJSONs.string("session.input_audio.append"),
                    "audio" to RealtimeJSONs.string(aligned.base64Encoded()),
                )
            }
            is AppendInstructions -> appendJSON("session.instructions.append", eventID, delegationID, content)
            is AppendThinking -> appendJSON("session.thinking.append", eventID, delegationID, content)
            is AppendCommentary -> appendJSON("session.commentary.append", eventID, delegationID, content)
            is Mute -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("session.input_audio.mute"),
                "event_id" to RealtimeJSONs.string(eventID),
            )
            is Unmute -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("session.input_audio.unmute"),
                "event_id" to RealtimeJSONs.string(eventID),
            )
            is Close -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("session.close"),
                "event_id" to RealtimeJSONs.string(eventID),
            )
            is UpdateDelegatorInstructions -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("session.update"),
                "event_id" to RealtimeJSONs.string(eventID),
                "session" to RealtimeJSONs.obj(
                    "delegation" to RealtimeJSONs.obj(
                        "type" to RealtimeJSONs.string("responses"),
                        "responses" to RealtimeJSONs.obj("instructions" to RealtimeJSONs.string(instructions)),
                    ),
                ),
            )
            is ResponseItemCreate -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("response.item.create"),
                "event_id" to RealtimeJSONs.string(eventID),
                "item" to item,
            )
            is ResponseCreate -> RealtimeJSONs.obj(
                "type" to RealtimeJSONs.string("response.create"),
                "event_id" to RealtimeJSONs.string(eventID),
            )
        }

    fun encoded(): ByteArray = json.encodedBytes()

    private companion object {
        fun appendJSON(type: String, eventID: String, delegationID: String?, content: String): RealtimeJSON = RealtimeJSONs.obj(
            "type" to RealtimeJSONs.string(type),
            "event_id" to RealtimeJSONs.string(eventID),
            "delegation_id" to (delegationID?.let(RealtimeJSONs::string) ?: RealtimeJSONs.NULL),
            "content" to RealtimeJSONs.string(content),
        )
    }
}

/**
 * Splits or summarizes `session.*.append` text to stay within GPT-Live's
 * 500-token per-append limit (`live-delegation.md`).
 */
object LiveContentChunker {
    /** Conservative character ceiling well under 500 tokens even for dense prose. */
    const val maxCharactersPerAppend = 1_400

    /**
     * Cleans up a session reply for spoken commentary and splits it into
     * chunks within the 500-token limit. Every chunk is kept, so a long reply
     * is read in full; `VoiceHandoff.replyLimit` bounds its length.
     */
    fun commentaryChunks(rawOutput: String, maxCharacters: Int = maxCharactersPerAppend): List<String> {
        var text = rawOutput.trim()
        if (text.startsWith("[COMPLETE]")) {
            text = text.removePrefix("[COMPLETE]").trim()
        }
        text = collapseCodeBlocks(text)
        return chunks(text, maxCharacters)
    }

    /**
     * Splits arbitrary text into chunks of at most `maxCharacters`, preferring
     * paragraph, newline, sentence, or word boundaries.
     */
    fun chunks(text: String, maxCharacters: Int = maxCharactersPerAppend): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || maxCharacters <= 0) return emptyList()
        if (trimmed.length <= maxCharacters) return listOf(trimmed)

        var remaining = trimmed
        val result = mutableListOf<String>()
        while (remaining.isNotEmpty()) {
            remaining = remaining.dropWhile(Char::isWhitespace)
            if (remaining.isEmpty()) break
            if (remaining.length <= maxCharacters) {
                result += remaining
                break
            }
            val window = remaining.substring(0, maxCharacters)
            val splitAt = bestBoundary(window) ?: maxCharacters
            val chunk = remaining.substring(0, splitAt).trim()
            if (chunk.isNotEmpty()) result += chunk
            remaining = remaining.substring(splitAt)
        }
        return result
    }

    private fun bestBoundary(window: String): Int? {
        val floor = maxOf(1, window.length / 3)
        val newline = window.lastIndexOf('\n')
        if (newline >= floor) return newline + 1
        for (index in window.length - 1 downTo floor) {
            val char = window[index]
            if (char == '.' || char == '!' || char == '?') {
                val next = index + 1
                if (next == window.length || window[next].isWhitespace()) return next
            }
        }
        for (index in window.length - 1 downTo floor) {
            if (window[index].isWhitespace()) return index
        }
        return null
    }

    fun collapseCodeBlocks(text: String): String {
        if (!text.contains("```")) return text
        val output = StringBuilder()
        var remainder = text
        while (true) {
            val open = remainder.indexOf("```")
            if (open < 0) break
            output.append(remainder, 0, open)
            val afterOpen = remainder.substring(open + 3)
            val close = afterOpen.indexOf("```")
            remainder = if (close >= 0) afterOpen.substring(close + 3) else ""
        }
        output.append(remainder)
        val cleaned = output.toString().trim()
        return cleaned.ifEmpty { "The session returned a code block in the transcript." }
    }
}

enum class LiveAppendKind(val rawValue: String) {
    instructions("instructions"),
    thinking("thinking"),
    commentary("commentary"),
}

sealed interface LiveCloseReason {
    data object CloseRequested : LiveCloseReason
    data object Expired : LiveCloseReason
    data object Content : LiveCloseReason
    data object RemoteHangup : LiveCloseReason
    data object ConnectionLost : LiveCloseReason
    data class Other(val value: String) : LiveCloseReason

    val rawValue: String
        get() = when (this) {
            CloseRequested -> "close_requested"
            Expired -> "expired"
            Content -> "content"
            RemoteHangup -> "remote_hangup"
            ConnectionLost -> "connection_lost"
            is Other -> value
        }

    companion object {
        fun of(rawValue: String?): LiveCloseReason = when (rawValue) {
            "close_requested" -> CloseRequested
            "expired" -> Expired
            "content" -> Content
            "remote_hangup" -> RemoteHangup
            "connection_lost" -> ConnectionLost
            null -> Other("unknown")
            else -> Other(rawValue)
        }
    }
}

sealed interface LiveServerEvent {
    data class SessionStarted(val id: String, val expiresAt: Instant?) : LiveServerEvent
    data class InputTranscriptDelta(val delta: String, val startMilliseconds: Int?, val endMilliseconds: Int?) : LiveServerEvent
    data class OutputTranscriptDelta(val delta: String, val startMilliseconds: Int?, val endMilliseconds: Int?) : LiveServerEvent
    class OutputAudio(val pcm: ByteArray, val startMilliseconds: Int?, val endMilliseconds: Int?) : LiveServerEvent {
        override fun equals(other: Any?): Boolean = other is OutputAudio && other.pcm.contentEquals(pcm) &&
            other.startMilliseconds == startMilliseconds && other.endMilliseconds == endMilliseconds
        override fun hashCode(): Int = (pcm.contentHashCode() * 31 + (startMilliseconds ?: 0)) * 31 + (endMilliseconds ?: 0)
        override fun toString(): String = "OutputAudio(${pcm.size} bytes, start=$startMilliseconds, end=$endMilliseconds)"
    }
    data class DelegationCreated(val id: String, val target: String, val offsetMilliseconds: Int?) : LiveServerEvent

    /** Responses delegation: one event from the backend's stream (`response.event`). */
    data class ResponseEvent(val delegationID: String?, val event: LiveResponseEvent) : LiveServerEvent
    data class InputAudioMuted(val clientEventID: String?) : LiveServerEvent
    data class InputAudioUnmuted(val clientEventID: String?) : LiveServerEvent
    data class ContextAppended(
        val kind: LiveAppendKind,
        val clientEventID: String?,
        val startMilliseconds: Int?,
        val endMilliseconds: Int?,
    ) : LiveServerEvent
    data class UsageUpdated(val seconds: Int, val contextUsageRatio: Double?) : LiveServerEvent
    data class SessionClosed(val reason: LiveCloseReason, val usageSeconds: Int?) : LiveServerEvent
    data class Error(val error: LiveServerError) : LiveServerEvent

    /**
     * From the WebRTC transport, not the service: the path is losing or
     * delaying audio (true), or healthy again.
     */
    data class LinkQuality(val poor: Boolean) : LiveServerEvent
    data object Ignored : LiveServerEvent

    @Serializable
    private data class Envelope(
        val type: String,
        @SerialName("client_event_id") val clientEventID: String? = null,
        val delta: String? = null,
        @SerialName("start_ms") val startMs: Int? = null,
        @SerialName("end_ms") val endMs: Int? = null,
        @SerialName("offset_ms") val offsetMs: Int? = null,
        val reason: String? = null,
        val session: SessionPayload? = null,
        val delegation: DelegationPayload? = null,
        val usage: UsagePayload? = null,
        @SerialName("context_window") val contextWindow: ContextWindowPayload? = null,
        val error: ErrorPayload? = null,
        @SerialName("delegation_id") val delegationID: String? = null,
        val event: JsonElement? = null,
    ) {
        @Serializable data class SessionPayload(val id: String? = null, @SerialName("expires_at") val expiresAt: Double? = null)
        @Serializable data class DelegationPayload(val id: String? = null, val target: String? = null)
        @Serializable data class UsagePayload(val seconds: Int? = null)
        @Serializable data class ContextWindowPayload(@SerialName("usage_ratio") val usageRatio: Double? = null)
        @Serializable data class ErrorPayload(
            val message: String? = null,
            val code: String? = null,
            val type: String? = null,
            @SerialName("client_event_id") val clientEventID: String? = null,
        )
    }

    companion object {
        /** Throws when the frame isn't JSON or has no `type`. */
        fun decode(data: ByteArray): LiveServerEvent = decode(data.decodeToString())

        fun decode(text: String): LiveServerEvent {
            val envelope = VoiceWireJson.decodeFromString(Envelope.serializer(), text)
            return when (envelope.type) {
                "session.started" -> SessionStarted(
                    id = envelope.session?.id ?: "",
                    expiresAt = envelope.session?.expiresAt?.let(::instantOfEpochSeconds),
                )
                "session.input_transcript.delta" -> InputTranscriptDelta(envelope.delta ?: "", envelope.startMs, envelope.endMs)
                "session.output_transcript.delta" -> OutputTranscriptDelta(envelope.delta ?: "", envelope.startMs, envelope.endMs)
                "session.output_audio.delta" -> {
                    val pcm = envelope.delta?.let(::decodeBase64OrNull) ?: return Ignored
                    OutputAudio(pcm, envelope.startMs, envelope.endMs)
                }
                "session.delegation.created" -> DelegationCreated(
                    id = envelope.delegation?.id ?: "",
                    target = envelope.delegation?.target ?: "client",
                    offsetMilliseconds = envelope.offsetMs,
                )
                "response.event" -> {
                    val event = envelope.event?.let(LiveResponseEvent::decode) ?: return Ignored
                    if (event == LiveResponseEvent.Ignored) return Ignored
                    ResponseEvent(envelope.delegationID, event)
                }
                "session.input_audio.muted" -> InputAudioMuted(envelope.clientEventID)
                "session.input_audio.unmuted" -> InputAudioUnmuted(envelope.clientEventID)
                "session.instructions.appended" ->
                    ContextAppended(LiveAppendKind.instructions, envelope.clientEventID, envelope.startMs, envelope.endMs)
                "session.thinking.appended" ->
                    ContextAppended(LiveAppendKind.thinking, envelope.clientEventID, envelope.startMs, envelope.endMs)
                "session.commentary.appended" ->
                    ContextAppended(LiveAppendKind.commentary, envelope.clientEventID, envelope.startMs, envelope.endMs)
                "session.usage.updated" -> UsageUpdated(
                    seconds = envelope.usage?.seconds ?: 0,
                    contextUsageRatio = envelope.contextWindow?.usageRatio,
                )
                "session.closed" -> SessionClosed(
                    reason = LiveCloseReason.of(envelope.reason),
                    usageSeconds = envelope.usage?.seconds,
                )
                "error" -> Error(
                    LiveServerError(
                        message = envelope.error?.message ?: "The voice service reported an error.",
                        code = envelope.error?.code,
                        type = envelope.error?.type,
                        clientEventID = envelope.error?.clientEventID,
                    ),
                )
                else -> Ignored
            }
        }
    }
}

/**
 * The parts of a Responses delegation's nested stream the phone acts on
 * (`live-delegation.md`, Handle Responses delegation). Function calls come
 * only from `response.output_item.done`: the forwarded lifecycle snapshots
 * carry an empty `output`.
 */
sealed interface LiveResponseEvent {
    data class Created(val responseID: String) : LiveResponseEvent
    data class FunctionCall(val call: VoiceDelegator.ToolCall) : LiveResponseEvent

    /** The backend's text, which GPT-Live speaks itself. */
    data class Message(val text: String) : LiveResponseEvent

    /** `completed`, `failed`, `incomplete`, or `cancelled`, with the reason when it didn't complete. */
    data class Finished(val responseID: String?, val status: String, val error: String?) : LiveResponseEvent
    data object Ignored : LiveResponseEvent

    companion object {
        /** Never throws: anything unreadable is [Ignored]. */
        fun decode(json: JsonElement): LiveResponseEvent {
            val payload = json as? JsonObject ?: return Ignored
            val type = payload.str("type") ?: return Ignored
            val response = payload["response"] as? JsonObject
            return when (type) {
                "response.created" -> response?.str("id")?.let(::Created) ?: Ignored
                "response.output_item.done" -> {
                    val item = payload["item"] as? JsonObject ?: return Ignored
                    when (item.str("type")) {
                        "function_call" -> {
                            val callID = item.str("call_id") ?: return Ignored
                            val name = item.str("name") ?: return Ignored
                            FunctionCall(VoiceDelegator.ToolCall(callID, name, item.str("arguments") ?: "{}"))
                        }
                        "message" -> {
                            val text = ((item["content"] as? JsonArray) ?: JsonArray(emptyList()))
                                .mapNotNull { it as? JsonObject }
                                .filter { it.str("type") == "output_text" }
                                .mapNotNull { it.str("text") }
                                .joinToString(" ")
                                .trim()
                            if (text.isEmpty()) Ignored else Message(text)
                        }
                        else -> Ignored
                    }
                }
                "response.completed", "response.failed", "response.incomplete", "response.cancelled" -> {
                    val status = response?.str("status") ?: type.removePrefix("response.")
                    val error = (response?.get("error") as? JsonObject)?.str("message")
                        ?: (response?.get("incomplete_details") as? JsonObject)?.str("reason")
                    Finished(response?.str("id"), status, error)
                }
                "error" -> Finished(
                    null,
                    "failed",
                    payload.str("message") ?: payload.str("code") ?: "The delegator reported an error.",
                )
                else -> Ignored
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}

data class LiveServerError(
    val message: String,
    val code: String? = null,
    val type: String? = null,
    val clientEventID: String? = null,
) {
    val disposition: RealtimeServerError.Disposition
        get() = RealtimeServerError.Disposition.classify(type, code, message)

    /**
     * `live-conversations.md`: non-terminal moderation/output errors cut off
     * assistant audio for the remainder of its current speech without ending
     * the session; rejected client commands carry `clientEventID` instead.
     */
    val cutsOffOutputAudio: Boolean
        get() {
            if (clientEventID != null || disposition != RealtimeServerError.Disposition.harmless) return false
            val classification = listOfNotNull(type, code, message).joinToString(" ") { it.lowercase() }
            val markers = listOf("moderation", "content_filter", "safety", "output_audio", "interrupted", "cut_off", "truncated")
            return markers.any(classification::contains) || code == null
        }
}
