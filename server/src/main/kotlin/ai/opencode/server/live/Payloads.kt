package ai.opencode.server.live

import ai.opencode.core.strings.localized
import ai.opencode.server.ConnectionRoute
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.internal.ElementSerializer
import ai.opencode.server.internal.WireJson
import ai.opencode.server.internal.bool
import ai.opencode.server.internal.dbl
import ai.opencode.server.internal.int
import ai.opencode.server.internal.list
import ai.opencode.server.internal.lng
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.internal.stringMap
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeMediaReference
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeProjectIcon
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.PastedText
import ai.opencode.server.model.ProjectMark
import ai.opencode.server.model.instantFromMillis
import ai.opencode.server.model.trimmedWhitespace
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.util.Base64

// Wire payloads. Synthesized `Decodable`s are `@Serializable`; custom `init(from:)`s are
// JSON-element walkers with the same fallbacks.

@Serializable
internal data class DataPayload<T>(val data: T)

@Serializable
internal data class PendingInputsPayload(val data: List<Item>) {
    @Serializable
    data class Item(
        val id: String,
        /** "user", "synthetic", or "compaction" — only the first two carry text. */
        val type: String,
        val payload: Payload? = null,
        val delivery: String? = null,
    )

    @Serializable
    data class Payload(
        val text: String? = null,
        val description: String? = null,
        val metadata: Map<String, OpenCodeJSONValue>? = null,
        val files: List<FilePayload>? = null,
    )
}

@Serializable
internal data class ShellInfo(
    val id: String,
    val status: String,
    val command: String,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val exit: Double? = null,
    val time: Time? = null,
) {
    @Serializable
    data class Time(val started: Double? = null, val completed: Double? = null)

    fun shell(isListed: Boolean) = OpenCodeShell(
        id = id,
        command = command,
        sessionID = metadata?.get("sessionID")?.string,
        isUserCommand = metadata?.get("background") == OpenCodeJSONValue.Bool(true),
        isListed = isListed,
        // An unknown future status still reads as finished, never as running.
        status = OpenCodeShell.Status.of(status) ?: OpenCodeShell.Status.Exited,
        exitCode = exit?.toInt(),
        startedAt = time?.started?.let(::instantFromMillis),
        completedAt = time?.completed?.let(::instantFromMillis),
    )
}

/** `Job.Info`: only the fields the background-work capsule reads. */
@Serializable
internal data class JobInfo(
    val id: String,
    val kind: String,
    val sessionID: String,
    val status: String,
    val childSessionID: String? = null,
    val time: Time? = null,
) {
    @Serializable
    data class Time(val started: Double? = null)

    fun job() = OpenCodeJob(
        id = id,
        kind = OpenCodeJob.Kind.of(kind),
        sessionID = sessionID,
        isRunning = status == "running",
        childSessionID = childSessionID,
        startedAt = time?.started?.let(::instantFromMillis),
    )
}

/**
 * V2 `GET /api/info`: the fields every V2 server sends, and the addresses it listens on. Required
 * fields only OpenCode reports make decoding reject other servers.
 */
@Serializable
internal data class ServerInfoPayload(val version: String, val pid: Int, val urls: List<String>? = null) {
    /**
     * See [requestHealth]; `server.url` is the address that answered. A password, or a server
     * with one address, needs nothing more. Without a password, an answer on a local network could
     * come from any OpenCode server that happens to use the same address on whatever network the
     * phone is on, so it must list one of our other addresses: one off the local network when we
     * have one (another LAN address could collide too), and it can't stay quiet about its addresses.
     */
    fun identifies(server: OpenCodeServer): Boolean {
        if (server.password.isNotEmpty() || server.candidateURLs.size <= 1) return true
        val others = server.candidateURLs.filter { it != server.url }
        if (!isLocal(server.url)) {
            val listed = urls ?: return true
            return overlap(listed, server.candidateURLs)
        }
        val remote = others.filter { !isLocal(it) }
        val listed = urls ?: return remote.isEmpty()
        return overlap(listed, if (remote.isEmpty()) others else remote)
    }

}

private fun isLocal(url: String): Boolean {
    val route = ConnectionRoute.of(url)
    return route == ConnectionRoute.LocalNetwork || route == ConnectionRoute.Loopback
}

private fun overlap(urls: List<String>, candidates: List<String>): Boolean {
    val ours = HashSet<String>()
    for (candidate in candidates) {
        val origin = OpenCodeServer.origin(candidate) ?: continue
        ours.add(origin)
    }
    for (url in urls) {
        val origin = OpenCodeServer.origin(url) ?: continue
        if (origin in ours) return true
    }
    return false
}

/** `{token, device?: {name}}`; a missing or malformed device name never fails pairing. */
internal data class PairingSessionPayload(val token: String, val deviceName: String?) {
    companion object {
        fun decode(element: JsonElement): PairingSessionPayload {
            val o = element.obj()
            val token = o.req("token") { it.str() }
            val name = try {
                o.opt("device") { it.obj().opt("name") { n -> n.str() } }?.trimmedWhitespace()
            } catch (_: RuntimeException) { null }
            return PairingSessionPayload(token, name?.ifEmpty { null })
        }
    }
}

@Serializable
internal data class InfoPayload(val urls: List<String>? = null, val paths: Paths? = null) {
    @Serializable
    data class Paths(val tmp: String)
}

@Serializable
internal data class ProjectPayload(
    val id: String,
    val canonical: String,
    val name: String? = null,
    val repository: String? = null,
    val icon: OpenCodeProjectIcon? = null,
) {
    fun project() = OpenCodeProject(
        id = id,
        name = ProjectMark.displayName(name, repository, canonical),
        directory = canonical,
        repository = ProjectMark.repositorySlug(repository),
        icon = icon?.takeUnless { it.isEmpty },
    )
}

@Serializable
internal data class SessionPayload(
    val id: String,
    val projectID: String,
    val title: String? = null,
    val time: TimePayload,
    val location: LocationPayload,
    val model: OpenCodeModelRef? = null,
    val parentID: String? = null,
    val agent: String? = null,
    val cost: Double? = null,
    @Serializable(with = SessionMetadataPayloadSerializer::class) val metadata: SessionMetadataPayload? = null,
    val revert: RevertPayload? = null,
)

@Serializable
internal data class RevertPayload(val messageID: String)

/** Only `metadata.notification.always`; anything else, or another shape, decodes as off. */
internal data class SessionMetadataPayload(val alwaysNotify: Boolean)

internal object SessionMetadataPayloadSerializer : ElementSerializer<SessionMetadataPayload>(
    decode = { element ->
        val always = try {
            (element as? JsonObject)?.opt("notification") { it.obj() }?.opt("always") { it.bool() }
        } catch (_: RuntimeException) { null }
        SessionMetadataPayload(always == true)
    },
    encode = { JsonNull },
)

@Serializable
internal data class ModelInfoPayload(
    val id: String? = null,
    val modelID: String,
    val providerID: String,
    val canonical: String? = null,
    val name: String,
    val enabled: Boolean,
    val status: String,
    val capabilities: Capabilities? = null,
    val limit: Limit? = null,
    val variants: List<Variant>? = null,
) {
    @Serializable data class Capabilities(val input: List<String>? = null)
    @Serializable data class Limit(val context: Int? = null)
    @Serializable data class Variant(val id: String)

    val model: OpenCodeModel
        get() = OpenCodeModel(
            modelID = modelID,
            providerID = providerID,
            name = name,
            inputs = capabilities?.input?.toSet() ?: emptySet(),
            contextLimit = limit?.context?.takeIf { it > 0 },
            catalogID = id?.takeIf { it != modelID },
            variants = (variants ?: emptyList()).map { it.id }.filter { it != "default" },
            canonicalProviderID = canonical,
        )
}

@Serializable
internal data class TimePayload(
    val updated: Double,
    val idle: Long? = null,
    val viewed: Long? = null,
    /** Set (epoch milliseconds) while the session is archived. */
    val archived: Double? = null,
)

@Serializable
internal data class LocationPayload(val directory: String)

@Serializable
internal data class ActiveSessionPayload(val type: String)

@Serializable
internal data class CursorPayload(val next: String? = null)

@Serializable
internal data class SessionsPayload(val data: List<SessionPayload>, val cursor: CursorPayload? = null)

internal data class MessagesPayload(val data: List<MessagePayload>, val cursor: CursorPayload?) {
    companion object {
        fun decode(element: JsonElement): MessagesPayload {
            val o = element.obj()
            return MessagesPayload(
                data = o.req("data") { it.list(MessagePayload::decode) },
                cursor = o.opt("cursor") { WireJson.decodeFromJsonElement(CursorPayload.serializer(), it) },
            )
        }
    }
}

/** A message, decoding only the fields its `type` carries. */
internal data class MessagePayload(
    val id: String,
    val type: String,
    val text: String? = null,
    val description: String? = null,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val command: String? = null,
    val shellID: String? = null,
    val status: String? = null,
    val exit: Int? = null,
    val output: ShellOutputPayload? = null,
    val name: String? = null,
    val content: List<ContentPartPayload>? = null,
    val error: ErrorPayload? = null,
    val files: List<FilePayload>? = null,
    val summary: String? = null,
    val hasProviderContext: Boolean = false,
    val tokens: TokensPayload? = null,
    /** `model-switched`: the model it switched to, and from. */
    val model: ModelRefPayload? = null,
    val previous: ModelRefPayload? = null,
    /** `assistant`: the server's pending retry of a failed step. */
    val retry: RetryPayload? = null,
    /** `idle`: how the run ended (`succeeded`, `failed`, `interrupted`). */
    val outcome: String? = null,
) {
    companion object {
        private fun metadata(o: JsonObject) = o.opt("metadata") { it.stringMap(OpenCodeJSONValue::of) }
        private fun <T> decoded(serializer: KSerializer<T>): (JsonElement) -> T = { WireJson.decodeFromJsonElement(serializer, it) }

        fun decode(element: JsonElement): MessagePayload {
            val c = element.obj()
            val id = c.req("id") { it.str() }
            val type = c.req("type") { it.str() }
            val base = MessagePayload(id, type)
            return when (type) {
                "user" -> base.copy(
                    text = c.opt("text") { it.str() },
                    metadata = metadata(c),
                    files = c.opt("files") { it.list(decoded(FilePayload.serializer())) },
                )
                "assistant" -> base.copy(
                    content = c.opt("content") { it.list(ContentPartPayload::decode) },
                    error = c.opt("error", decoded(ErrorPayload.serializer())),
                    retry = c.opt("retry", decoded(RetryPayload.serializer())),
                    tokens = c.opt("tokens", decoded(TokensPayload.serializer())),
                    model = c.opt("model", ModelRefPayload::decode),
                )
                "shell" -> base.copy(
                    shellID = c.opt("shellID") { it.str() },
                    status = c.opt("status") { it.str() },
                    exit = c.opt("exit") { it.int() },
                    command = c.opt("command") { it.str() },
                    output = c.opt("output", decoded(ShellOutputPayload.serializer())),
                )
                "skill" -> base.copy(name = c.opt("name") { it.str() })
                "compaction" -> base.copy(
                    status = c.opt("status") { it.str() },
                    error = c.opt("error", decoded(ErrorPayload.serializer())),
                    tokens = c.opt("tokens", decoded(TokensPayload.serializer())),
                    summary = c.opt("summary") { it.str() },
                    hasProviderContext = c.containsKey("providerContext") && c["providerContext"] !is JsonNull,
                )
                "model-switched" -> base.copy(
                    model = c.opt("model", ModelRefPayload::decode),
                    previous = c.opt("previous", ModelRefPayload::decode),
                )
                // Model-facing `text` is never shown; skip decoding it.
                "synthetic" -> base.copy(description = c.opt("description") { it.str() }, metadata = metadata(c))
                "idle" -> {
                    val outcome = c.opt("outcome") { it.str() }
                    base.copy(
                        outcome = outcome,
                        error = if (outcome == "failed") c.opt("error", decoded(ErrorPayload.serializer())) else null,
                    )
                }
                else -> base
            }
        }
    }
}

/** A model ref where one may appear; anything else (an agent switch's `previous` string) reads as null. */
internal data class ModelRefPayload(val ref: OpenCodeModelRef?) {
    companion object {
        fun decode(element: JsonElement) = ModelRefPayload(
            try { WireJson.decodeFromJsonElement(OpenCodeModelRef.serializer(), element) } catch (_: Exception) { null },
        )
    }
}

@Serializable
internal data class TokensPayload(val input: Double, val output: Double, val reasoning: Double, val cache: Cache) {
    @Serializable
    data class Cache(val read: Double, val write: Double)

    val total: Int get() = (input + output + reasoning + cache.read + cache.write).toInt()
}

@Serializable
internal data class ShellOutputPayload(val output: String)

@Serializable
internal data class FilePayload(
    val data: String? = null,
    val uri: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val bytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val mention: OpenCodeJSONValue? = null,
    val source: Source? = null,
) {
    @Serializable
    data class Source(val type: String, val uri: String? = null)

    val mediaHash: String?
        get() = OpenCodeMediaReference.hash(uri) ?: OpenCodeMediaReference.hash(source?.uri)

    /** Server placeholder emitted when a referenced media blob was missing or released. */
    val unavailablePlaceholder: OpenCodeMediaReference.UnavailablePlaceholder?
        get() {
            if (mediaHash != null || mime?.lowercase()?.startsWith("image/") == true) return null
            val encoded = data ?: return null
            if (encoded.toByteArray(Charsets.UTF_8).size > MAX_PLACEHOLDER_DATA_LENGTH) return null
            val decoded = decodeBase64(encoded)?.let(::strictUtf8) ?: encoded
            return OpenCodeMediaReference.unavailablePlaceholder(decoded, fallbackName = name)
        }

    /**
     * Desktop's `attached()`: inline bytes or a media reference the user attached, not a
     * mentioned or context file.
     */
    val isAttached: Boolean
        get() {
            if (mention != null) return false
            if (mediaHash != null) return true
            val source = source ?: return data != null
            return source.type == "inline" || source.uri?.startsWith("data:") == true ||
                source.uri?.startsWith(OpenCodeMediaReference.PREFIX) == true
        }

    companion object {
        /**
         * The server's placeholder is a short `text/plain` file, so an inline image or a big
         * paste is never decoded just to rule it out.
         */
        const val MAX_PLACEHOLDER_DATA_LENGTH = 4096

        /** The last non-empty `/` component. */
        private fun cleanName(name: String?): String? = (name ?: "").split("/").lastOrNull { it.isNotEmpty() }

        /** Attached images: inline base64, `media:<hash>`, or the unavailable placeholder. Indices count every file. */
        fun attachedImages(files: List<FilePayload>, messageID: String): List<OpenCodeImageAttachment> =
            files.mapIndexedNotNull { index, file ->
                val cleanName = cleanName(file.name)
                val placeholder = file.unavailablePlaceholder
                if (placeholder != null && placeholder.mime.lowercase().startsWith("image/")) {
                    return@mapIndexedNotNull OpenCodeImageAttachment(
                        id = "$messageID-img-$index",
                        name = cleanName(placeholder.name) ?: cleanName,
                        mime = placeholder.mime,
                        isUnavailable = true,
                    )
                }
                val mime = file.mime ?: return@mapIndexedNotNull null
                if (!mime.lowercase().startsWith("image/")) return@mapIndexedNotNull null
                val hash = file.mediaHash
                if (hash != null) {
                    return@mapIndexedNotNull OpenCodeImageAttachment(
                        id = "$messageID-img-$index",
                        mediaHash = hash,
                        name = cleanName,
                        mime = mime,
                        byteCount = file.bytes,
                        pixelWidth = file.width,
                        pixelHeight = file.height,
                    )
                }
                val data = file.data?.let(::decodeBase64) ?: return@mapIndexedNotNull null
                OpenCodeImageAttachment(
                    id = "$messageID-img-$index",
                    data = data,
                    name = cleanName,
                    byteCount = file.bytes,
                    pixelWidth = file.width,
                    pixelHeight = file.height,
                )
            }

        /**
         * Desktop's `attached()`: inline files that aren't images or @-mentions get a chip,
         * and plain text a Pasted Text card; an unavailable placeholder gets its own chip.
         * Indices count every file, images included.
         */
        fun attachedFiles(files: List<FilePayload>, messageID: String): List<OpenCodeTranscriptFile> =
            files.mapIndexedNotNull { index, file ->
                val placeholder = file.unavailablePlaceholder
                if (placeholder != null) {
                    if (placeholder.mime.lowercase().startsWith("image/")) return@mapIndexedNotNull null
                    val cleanName = (placeholder.name ?: "").split("/").last()
                    return@mapIndexedNotNull OpenCodeTranscriptFile(
                        id = "$messageID-file-inline-$index",
                        name = cleanName.ifEmpty { localized(R.string.server_transcript_file_fallback) },
                        mime = placeholder.mime,
                        isUnavailable = true,
                    )
                }
                val mime = file.mime ?: return@mapIndexedNotNull null
                if (mime.lowercase().startsWith("image/") || !file.isAttached) return@mapIndexedNotNull null
                val name = (file.name ?: "").split("/").lastOrNull { it.isNotEmpty() } ?: ""
                val text = file.data?.let(::decodeBase64)?.let { PastedText.text(mime, it) }
                OpenCodeTranscriptFile(
                    id = "$messageID-file-inline-$index",
                    name = name.ifEmpty { if (text == null) localized(R.string.server_transcript_file_fallback) else PastedText.NAME },
                    mime = mime,
                    text = text,
                    mediaHash = file.mediaHash,
                    byteCount = file.bytes,
                )
            }
    }
}

/** Foundation's `Data(base64Encoded:)`: strict padding-aware base64, else null. */
internal fun decodeBase64(text: String): ByteArray? {
    // Foundation requires the padding Java's decoder would let go.
    if (text.length % 4 != 0) return null
    return try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { null }
}

/** Foundation's `String(data:encoding: .utf8)`: null for malformed UTF-8. */
private fun strictUtf8(data: ByteArray): String? =
    try { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString() } catch (_: java.nio.charset.CharacterCodingException) { null }

@Serializable
internal data class ErrorPayload(val type: String? = null, val message: String? = null, val status: Int? = null)

/** `Assistant.retry`: the server retries the failed step at [at] (ms). */
@Serializable
internal data class RetryPayload(val attempt: Int? = null, val at: Double, val error: ErrorPayload? = null)

internal data class ContentPartPayload(
    val type: String,
    val id: String? = null,
    val text: String? = null,
    val name: String? = null,
    val state: ToolStatePayload? = null,
    val time: PartTime? = null,
) {
    @Serializable
    data class PartTime(val created: Double? = null, val ran: Double? = null, val completed: Double? = null)

    companion object {
        fun decode(element: JsonElement): ContentPartPayload {
            val c = element.obj()
            val type = c.req("type") { it.str() }
            return when (type) {
                "text" -> ContentPartPayload(type, text = c.opt("text") { it.str() })
                "tool" -> ContentPartPayload(
                    type,
                    id = c.opt("id") { it.str() },
                    name = c.opt("name") { it.str() },
                    state = c.opt("state", ToolStatePayload::decode),
                    time = c.opt("time") { WireJson.decodeFromJsonElement(PartTime.serializer(), it) },
                )
                // Skip `"reasoning"` text and encrypted state payloads.
                else -> ContentPartPayload(type)
            }
        }
    }
}

internal data class ToolStatePayload(
    val status: String?,
    val input: OpenCodeJSONValue?,
    val content: List<ToolContentPayload>?,
    val error: ErrorPayload?,
    val metadata: Map<String, OpenCodeJSONValue>?,
) {
    companion object {
        fun decode(element: JsonElement): ToolStatePayload {
            val c = element.obj()
            return ToolStatePayload(
                status = c.opt("status") { it.str() },
                input = c.opt("input", OpenCodeJSONValue::of),
                content = c.opt("content") { it.list(ToolContentPayload::decode) },
                error = c.opt("error") { WireJson.decodeFromJsonElement(ErrorPayload.serializer(), it) },
                metadata = c.opt("metadata") { it.stringMap(OpenCodeJSONValue::of) },
            )
        }
    }
}

internal data class ToolContentPayload(
    val type: String,
    val text: String? = null,
    val mime: String? = null,
    val uri: String? = null,
    val name: String? = null,
    val bytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    companion object {
        fun decode(element: JsonElement): ToolContentPayload {
            val c = element.obj()
            val type = c.req("type") { it.str() }
            return when (type) {
                "text" -> ToolContentPayload(type, text = c.opt("text") { it.str() })
                "file" -> {
                    val mime = c.opt("mime") { it.str() }
                    if (mime != null && (mime.startsWith("image/", ignoreCase = true) || mime.startsWith("video/", ignoreCase = true))) {
                        ToolContentPayload(
                            type,
                            mime = mime,
                            uri = c.opt("uri") { it.str() },
                            name = c.opt("name") { it.str() },
                            bytes = c.opt("bytes") { it.lng() },
                            width = c.opt("width") { it.int() },
                            height = c.opt("height") { it.int() },
                        )
                    } else {
                        ToolContentPayload(type, mime = mime)
                    }
                }
                else -> ToolContentPayload(type)
            }
        }
    }
}

// Capabilities.

@Serializable
internal data class LocatedPayload<T>(val location: Location? = null, val data: T) {
    @Serializable
    data class Location(val directory: String? = null)
}

@Serializable
internal data class MCPServerPayload(val name: String, val status: Status, val integrationID: String? = null) {
    @Serializable
    data class Status(val status: String, val error: String? = null)
}

@Serializable
internal data class IntegrationPayload(val id: String, val name: String, val connections: List<Connection>) {
    @Serializable
    data class Connection(val type: String, val label: String? = null, val method: String? = null, val name: String? = null)
}

@Serializable
internal data class OAuthMethodPayload(val id: String? = null, val type: String)

@Serializable
internal data class IntegrationMethodsPayload(val methods: List<OAuthMethodPayload>)

@Serializable
internal data class OAuthAttemptPayload(val attemptID: String, val url: String, val instructions: String, val mode: String)

@Serializable
internal data class OAuthStatusPayload(val status: String, val message: String? = null)

// Files.

@Serializable
internal data class WrittenFilePayload(val path: String)

@Serializable
internal data class DirectoryListingPayload(val location: Location, val data: List<Entry>) {
    @Serializable data class Location(val directory: String)
    @Serializable data class Entry(val path: String, val type: String)
}

/** Lenient: the plugin list mixes builtins and packages; only the ID and status matter. */
@Serializable
internal data class PluginPayload(val id: String? = null, val state: State? = null) {
    @Serializable data class State(val status: String? = null)
}

@Serializable
internal data class InterruptPayload(val interrupted: Boolean)

/** `/api/fs/find` entries: `{path}` objects or bare strings. */
internal fun decodeFindEntry(element: JsonElement): String {
    val fromObject = (element as? JsonObject)?.let { o -> try { o.req("path") { it.str() } } catch (_: RuntimeException) { null } }
    return fromObject ?: element.str()
}

internal fun JsonElement.number(): Double = dbl()
