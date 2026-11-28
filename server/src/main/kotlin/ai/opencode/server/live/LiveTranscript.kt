package ai.opencode.server.live

import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.TaskLocal
import ai.opencode.server.internal.WireJson
import ai.opencode.server.internal.bool
import ai.opencode.server.internal.list
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.req
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeContextUsage
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeMediaReference
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePromptPresentation
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeRunRetry
import ai.opencode.server.model.OpenCodeToolBounds
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import ai.opencode.server.model.TranscriptNoticeKind
import ai.opencode.server.model.bounded
import ai.opencode.server.model.instantFromMillis
import ai.opencode.server.model.nonEmpty
import ai.opencode.server.model.trimmedWhitespace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The context reading and next cursor of the transcript window just decoded, for `transcriptWithContextUsageAndCursor`. */
internal class TranscriptContextCapture {
    /** [messageCount]: messages in the page, so a short page reads as reaching the session's start. */
    data class Captured(val reading: OpenCodeContextReading, val cursor: CursorPayload?, val messageCount: Int)

    @Volatile private var captured: Captured? = null

    fun record(value: OpenCodeContextReading, cursor: CursorPayload?, messageCount: Int) {
        captured = Captured(value, cursor, messageCount)
    }

    fun take(): Captured? = captured

    companion object {
        val current = TaskLocal<TranscriptContextCapture?> { null }
    }
}

/** A run the user stopped ends on this line, never a failure. */
internal const val STOPPED_RUN_TEXT = "Interrupted"

private const val CONTEXT_USAGE_MESSAGE_LIMIT = 8

internal suspend fun requestTranscript(server: OpenCodeServer, sessionID: String, limit: Int): List<OpenCodeTranscriptItem> {
    val data = perform(
        "/api/session/$sessionID/message",
        query = listOf("order" to "desc", "limit" to "$limit"),
        server = server,
    )
    return withContext(Dispatchers.Default) { decodeTranscriptPage(data.data, TranscriptContextCapture.current.current()).items }
}

internal suspend fun requestMessage(server: OpenCodeServer, sessionID: String, messageID: String): List<OpenCodeTranscriptItem> {
    val data = perform("/api/session/$sessionID/message/$messageID", server = server)
    return withContext(Dispatchers.Default) {
        val message = decoded(data.data, "message") { MessagePayload.decode(it.obj().req("data") { d -> d }) }
        parsedItems(message)
    }
}

/** Messages per older page: two of the reader's top-edge steps (`showEarlierTapped`), so every other step pages from memory. */
internal const val HISTORY_PAGE_LIMIT = 80

internal suspend fun requestHistoryPage(server: OpenCodeServer, sessionID: String, cursor: String?): OpenCodeTranscriptPage {
    LabMarks.mark("history_page_request", sessionID)
    val data = perform(
        "/api/session/$sessionID/message",
        query = listOf("limit" to "$HISTORY_PAGE_LIMIT", if (cursor != null) "cursor" to cursor else "order" to "desc"),
        // 80 messages can be a few hundred KB on a slow link.
        timeout = 20.seconds,
        server = server,
    )
    LabMarks.mark("history_page_response", sessionID)
    LabMarks.mark("history_page_bytes(${data.data.size})", sessionID)
    LabMarks.mark("history_page_decode_start", sessionID)
    val page = withContext(Dispatchers.Default) { decodeTranscriptPage(data.data, TranscriptContextCapture.current.current()) }
    LabMarks.mark("history_page_decode_end", sessionID)
    return page
}

/** Records the page's context and cursor for an enclosing [TranscriptContextCapture]. */
fun decodeTranscript(data: ByteArray): List<OpenCodeTranscriptItem> =
    decodeTranscriptPage(data, TranscriptContextCapture.current.currentBlocking()).items

internal fun decodeTranscriptPage(data: ByteArray, capture: TranscriptContextCapture?): OpenCodeTranscriptPage {
    val payload = decoded(data, "transcript", MessagesPayload::decode)
    capture?.record(contextUsage(payload), payload.cursor, payload.data.size)
    return OpenCodeTranscriptPage(payload.data.asReversed().flatMap(::parsedItems), payload.cursor?.next)
}

/**
 * Rows parsed from messages already read, keyed by message ID and the message's content: a
 * refresh of 30 messages where one changed parses (Markdown, syntax highlighting) only that one.
 */
private val parsedMessages = ParsedMessageCache()

internal fun parsedItems(message: MessagePayload): List<OpenCodeTranscriptItem> =
    parsedMessages.items(message.id, message.hashCode()) {
        ParsedMessageCache.noteParsed()
        transcriptItems(message)
    }

/** Benchmarks of a cold decode start from nothing. */
internal fun clearParsedMessages() = parsedMessages.clear()

/**
 * The rows each message read so far parsed into, for messages whose content hasn't changed
 * since. One entry per message (a message still streaming replaces its own), bounded, and never
 * one holding inline image bytes or a large pasted file.
 */
internal class ParsedMessageCache {
    private class Entry(val key: Int, val items: List<OpenCodeTranscriptItem>, var tick: Long)

    private val entries = HashMap<String, Entry>()
    private var tick = 0L

    fun items(id: String, key: Int, parse: () -> List<OpenCodeTranscriptItem>): List<OpenCodeTranscriptItem> {
        synchronized(this) {
            val entry = entries[id]
            if (entry != null && entry.key == key) {
                tick += 1
                entry.tick = tick
                return entry.items
            }
        }
        val items = parse()
        if (holdsInlineBytes(items)) return items
        synchronized(this) {
            tick += 1
            entries[id] = Entry(key, items, tick)
            // Past the limit, the least recently read half goes.
            if (entries.size > LIMIT) {
                val newestFirst = entries.entries.sortedByDescending { it.value.tick }
                val keep = HashSet<String>()
                for (index in 0 until LIMIT / 2) keep += newestFirst[index].key
                entries.keys.retainAll(keep)
            }
        }
        return items
    }

    fun clear() = synchronized(this) {
        entries.clear()
        tick = 0
    }

    companion object {
        const val LIMIT = 256

        /** A pasted file bigger than this is parsed again rather than kept. */
        const val LARGE_TEXT_BYTES = 64 * 1024

        /** Counts messages parsed (not reused) while a test holds it. */
        val parsed = TaskLocal<AtomicInteger?> { null }

        fun noteParsed() {
            parsed.currentBlocking()?.incrementAndGet()
        }

        private fun holdsInlineBytes(items: List<OpenCodeTranscriptItem>): Boolean {
            for (item in items) {
                when (item) {
                    is OpenCodeTranscriptItem.UserImages ->
                        if (item.images.any { it.data.isNotEmpty() }) return true
                    is OpenCodeTranscriptItem.Tool ->
                        if (item.call.imageAttachments.any { it.data.isNotEmpty() }) return true
                    is OpenCodeTranscriptItem.UserFiles ->
                        if (item.files.any { (it.text?.toByteArray()?.size ?: 0) > LARGE_TEXT_BYTES }) return true
                    else -> {}
                }
            }
            return false
        }
    }
}

private fun runErrorText(error: ErrorPayload): String {
    // Provider errors embed raw JSON; keep only the human-readable prefix.
    val raw = error.message ?: ""
    // The first non-empty one.
    val human = raw.split("{").firstOrNull { it.isNotEmpty() }?.trim(' ', ':') ?: raw
    val kind = error.type ?: "error"
    return if (human.isEmpty()) "Run failed: $kind" else "Run failed: $kind — $human"
}

private fun runError(error: ErrorPayload, beforeReply: Boolean = false) =
    OpenCodeRunError(type = error.type ?: "unknown", message = error.message ?: "", status = error.status, beforeReply = beforeReply)

internal fun transcriptItems(message: MessagePayload): List<OpenCodeTranscriptItem> = when (message.type) {
    "user" -> {
        val items = mutableListOf<OpenCodeTranscriptItem>()
        val images = FilePayload.attachedImages(message.files ?: emptyList(), message.id)
        if (images.isNotEmpty()) items += OpenCodeTranscriptItem.UserImages("${message.id}-images", images)
        val references = OpenCodePromptPresentation.files(message.metadata)
        val inlineFiles = FilePayload.attachedFiles(message.files ?: emptyList(), message.id)
        val files = references.mapIndexed { index, file ->
            OpenCodeTranscriptFile(id = "${message.id}-file-$index", name = file.name, mime = file.mime, path = file.path)
        } + inlineFiles
        if (files.isNotEmpty()) items += OpenCodeTranscriptItem.UserFiles("${message.id}-files", files)
        val text = OpenCodePromptPresentation.displayText(message.text ?: "", references, message.metadata)
        val quotes = OpenCodePromptPresentation.quotes(message.metadata)
        val shown = text.trimmedWhitespace()
        if (shown.isNotEmpty() || quotes.isNotEmpty()) {
            items += OpenCodeTranscriptItem.UserText(message.id, shown, OpenCodePromptSource.of(message.text, message.metadata), quotes)
        }
        items
    }

    "assistant" -> {
        var textOrdinal = 0
        val parts = (message.content ?: emptyList()).mapNotNull<ContentPartPayload, OpenCodeTranscriptItem> { part ->
            when (part.type) {
                "text" -> {
                    val partID = "${message.id}-text-$textOrdinal"
                    textOrdinal += 1
                    val text = part.text?.trimmedWhitespace()?.nonEmpty ?: return@mapNotNull null
                    OpenCodeTranscriptItem.AssistantMarkdown(partID, text)
                }
                "tool" -> {
                    val partID = "${message.id}-tool-${part.id ?: "unknown"}"
                    val state = part.state
                    OpenCodeTranscriptItem.Tool(
                        partID,
                        OpenCodeToolCall(
                            callID = part.id ?: partID,
                            messageID = message.id,
                            name = part.name ?: "tool",
                            status = OpenCodeToolCall.Status.tool(state?.status),
                            input = (state?.input ?: OpenCodeJSONValue.Null).bounded(),
                            output = OpenCodeToolBounds.output(state?.content?.mapNotNull { if (it.type == "text") it.text else null } ?: emptyList()),
                            error = state?.error?.message,
                            errorType = state?.error?.type,
                            metadata = state?.metadata?.let(OpenCodeToolBounds::metadata) ?: emptyMap(),
                            images = toolImages(state?.content, partID),
                            videos = toolVideos(state?.content, partID),
                            startedAt = (part.time?.ran ?: part.time?.created)?.let(::instantFromMillis),
                            completedAt = part.time?.completed?.let(::instantFromMillis),
                        ),
                    )
                }
                else -> null
            }
        }.toMutableList()
        val error = message.error
        val retry = message.retry
        if (error != null) {
            parts += OpenCodeTranscriptItem.RunError("${message.id}-error", runError(error))
        } else if (retry?.error != null) {
            parts += OpenCodeTranscriptItem.RunRetry(
                "${message.id}-retry",
                OpenCodeRunRetry(attempt = retry.attempt ?: 1, at = instantFromMillis(retry.at), error = runError(retry.error)),
            )
        }
        parts
    }

    "shell" -> listOf(
        OpenCodeTranscriptItem.Tool(
            message.id,
            OpenCodeToolCall(
                callID = message.shellID ?: message.id,
                name = "shell",
                status = OpenCodeToolCall.Status.shell(message.status, message.exit),
                input = OpenCodeJSONValue.Object(mapOf("command" to OpenCodeJSONValue.Str(message.command ?: ""))),
                output = listOfNotNull(message.output?.output?.bounded(20_000)),
                error = if (message.status == "killed") localized(R.string.server_tool_shell_killed)
                else message.exit?.let { if (it == 0) null else localized(R.string.server_tool_shell_exit_code, it) },
                metadata = message.exit?.let { mapOf("exit" to OpenCodeJSONValue.Number(it.toDouble())) } ?: emptyMap(),
            ),
        ),
    )

    "skill" -> listOf(
        OpenCodeTranscriptItem.Tool(
            message.id,
            OpenCodeToolCall(
                callID = message.id,
                name = "skill",
                status = OpenCodeToolCall.Status.Completed,
                input = message.name?.let { OpenCodeJSONValue.Object(mapOf("name" to OpenCodeJSONValue.Str(it))) } ?: OpenCodeJSONValue.Null,
                output = emptyList(),
                error = null,
                metadata = emptyMap(),
            ),
        ),
    )

    "compaction" -> listOf(OpenCodeTranscriptItem.Compaction(message.id, compaction(message)))

    "model-switched" -> {
        val model = message.model?.ref
        if (model == null) emptyList() else listOf(OpenCodeTranscriptItem.ModelSwitch(message.id, model, message.previous?.ref))
    }

    "synthetic" -> listOfNotNull(syntheticNotice(message.id, message.description, message.metadata))

    // The run's end. A failed one repeats its last step's error, which that step already shows;
    // only a run that failed before any step is new here.
    "idle" -> {
        val error = message.error
        if (message.outcome == "failed" && error != null) listOf(OpenCodeTranscriptItem.RunError(message.id, runError(error, beforeReply = true)))
        else emptyList()
    }

    else -> emptyList()
}

private fun toolVideos(content: List<ToolContentPayload>?, idPrefix: String): List<OpenCodeTranscriptFile>? =
    content?.mapIndexedNotNull { index, part ->
        val mime = part.mime ?: return@mapIndexedNotNull null
        if (part.type != "file" || !mime.startsWith("video/", ignoreCase = true)) return@mapIndexedNotNull null
        val hash = OpenCodeMediaReference.hash(part.uri) ?: return@mapIndexedNotNull null
        OpenCodeTranscriptFile(id = "$idPrefix-video-$index", name = part.name ?: "Video", mime = mime, mediaHash = hash, byteCount = part.bytes)
    }?.ifEmpty { null }

/**
 * Tool results encode screenshots as `file` blocks with data-URI sources, decoded at the
 * protocol boundary, or as `media:<hash>` references (and the server's unavailable
 * placeholder text). A tool's total resident image bytes stay bounded.
 */
private fun toolImages(content: List<ToolContentPayload>?, idPrefix: String): List<OpenCodeImageAttachment> {
    val maxImages = 16
    val maxImageBytes = 20_000_000
    val maxTotalBytes = 32_000_000
    var totalBytes = 0
    val images = mutableListOf<OpenCodeImageAttachment>()
    for ((index, part) in (content ?: emptyList()).withIndex()) {
        if (images.size >= maxImages) continue
        if (part.type == "text") {
            val placeholder = part.text?.let { OpenCodeMediaReference.unavailablePlaceholder(it) }
            if (placeholder != null && placeholder.mime.lowercase().startsWith("image/")) {
                images += OpenCodeImageAttachment(
                    id = "$idPrefix-image-$index",
                    name = placeholder.name,
                    mime = placeholder.mime,
                    isUnavailable = true,
                )
                continue
            }
        }
        if (part.type != "file") continue
        val mime = part.mime ?: continue
        if (!mime.lowercase().startsWith("image/")) continue
        val uri = part.uri ?: continue
        val hash = OpenCodeMediaReference.hash(uri)
        if (hash != null) {
            images += OpenCodeImageAttachment(
                id = "$idPrefix-image-$index",
                mediaHash = hash,
                name = (part.name ?: "").split("/").lastOrNull { it.isNotEmpty() },
                mime = mime,
                byteCount = part.bytes,
                pixelWidth = part.width,
                pixelHeight = part.height,
            )
            continue
        }
        val comma = uri.indexOf(',')
        if (comma < 0) continue
        val header = uri.substring(0, comma).lowercase()
        if (!header.startsWith("data:${mime.lowercase()}") || !header.endsWith(";base64")) continue
        val encoded = uri.substring(comma + 1)
        if (encoded.length > (maxImageBytes * 4 / 3) + 4) continue
        val data = decodeBase64(encoded) ?: continue
        if (data.size > maxImageBytes || totalBytes + data.size > maxTotalBytes) continue
        totalBytes += data.size
        images += OpenCodeImageAttachment("$idPrefix-image-$index", data)
    }
    return images
}

internal suspend fun requestAssistantReply(server: OpenCodeServer, sessionID: String, afterMessageID: String): String? =
    assistantReply(recentMessages(server, sessionID).data, afterMessageID)

internal suspend fun requestRunReply(server: OpenCodeServer, sessionID: String, afterMessageID: String): String? =
    assistantReply(recentMessages(server, sessionID).data, afterMessageID, acrossSteers = true)

private suspend fun recentMessages(server: OpenCodeServer, sessionID: String): MeasuredResponse =
    perform("/api/session/$sessionID/message", query = listOf("order" to "desc", "limit" to "40"), server = server)

private class ReplyMessage(val id: String, val type: String, val text: String?, val parts: List<Pair<String, String?>>?, val error: ErrorPayload?)

private fun decodeReplyMessage(element: JsonElement): ReplyMessage {
    val c = element.obj()
    val id = c.req("id") { it.str() }
    val type = c.req("type") { it.str() }
    if (type != "user" && type != "assistant") return ReplyMessage(id, type, null, null, null)
    val text = c.opt("text") { it.str() }
    val content = c.opt("content") { content ->
        content.list { part ->
            val p = part.obj()
            val partType = p.req("type") { it.str() }
            partType to if (partType == "text") p.opt("text") { it.str() } else null
        }
    }
    val error = if (type == "assistant") c.opt("error") { WireJson.decodeFromJsonElement(ErrorPayload.serializer(), it) } else null
    return ReplyMessage(id, type, text, content, error)
}

/**
 * The reply to one prompt from a newest-first `/message` page. By default a voice delegation
 * steered in before any text ends it; [acrossSteers] reads on past such delegations.
 */
fun assistantReply(data: ByteArray, afterMessageID: String, acrossSteers: Boolean = false): String? {
    val messages = decoded(data, "transcript") { it.obj().req("data") { d -> d.list(::decodeReplyMessage) } }.asReversed()
    val anchor = messages.indexOfFirst { it.id == afterMessageID }
    if (anchor < 0) throw OpenCodeClientError("The voice request is no longer in the recent transcript.")
    var reply: String? = null
    for (message in messages.drop(anchor + 1)) {
        val parts = (message.parts ?: emptyList()).filter { it.first == "text" }.mapNotNull { it.second }.joinToString("\n")
        // A user message's words are its `text` when it has one, else its text parts.
        val text = (if (message.type == "user") message.text?.ifEmpty { null } ?: parts else parts).trimmedWhitespace()
        // Once an assistant reply exists, any later user message ends the span. Before one,
        // stop at a later voice delegation (which has its own wait) but let a typed steer share it.
        if (message.type == "user") {
            if (reply != null || (!acrossSteers && isVoiceDelegation(text))) break
            continue
        }
        if (message.type != "assistant") continue
        message.error?.let { throw OpenCodeClientError(runErrorText(it)) }
        if (text.isNotEmpty()) reply = text
    }
    return reply
}

/**
 * Whether [text] is a prompt the voice delegator wrote: the header line, then a JSON body with a
 * non-empty `instruction`, optionally followed by `Attached file:` lines.
 */
internal fun isVoiceDelegation(text: String): Boolean {
    if (!text.startsWith("Voice delegation.") && !text.startsWith("Voice delegation (continued).")) return false
    val newline = text.indexOf('\n')
    if (newline < 0) return false
    var body = text.substring(newline + 1)
    while (true) {
        val lineBreak = body.lastIndexOf('\n')
        if (lineBreak < 0) break
        val lastLine = body.substring(lineBreak + 1).trimmedWhitespace()
        if (lastLine.isEmpty()) {
            body = body.substring(0, lineBreak)
            continue
        }
        if (!(lastLine.startsWith("Attached file: `") && lastLine.endsWith("`"))) break
        body = body.substring(0, lineBreak)
    }
    return try {
        val o = WireJson.parseToJsonElement(body).obj()
        o.opt("call_log_attached") { it.bool() }
        o.opt("context") { it.str() }
        o.opt("transcript_delta") { d -> d.list { it.str() } }
        o.opt("voice") { it.str() }
        o.req("instruction") { it.str() }.trimmedWhitespace().isNotEmpty()
    } catch (_: Exception) {
        false
    }
}

internal suspend fun requestContextUsage(server: OpenCodeServer, sessionID: String): OpenCodeContextReading {
    val data = perform(
        "/api/session/$sessionID/message",
        query = listOf("order" to "desc", "limit" to "$CONTEXT_USAGE_MESSAGE_LIMIT"),
        server = server,
    )
    return decodeContextUsage(data.data)
}

/**
 * Newest first. Mirrors the TUI's `lastAssistantWithUsage`: the first assistant with usage,
 * unless a completed compaction comes first. A full window without either says nothing; a
 * window that ends the history means nothing fills the context.
 */
internal fun decodeContextUsage(data: ByteArray): OpenCodeContextReading {
    class Message(val type: String, val status: String?, val model: OpenCodeModelRef?, val tokens: TokensPayload?)
    val (messages, next) = decoded(data, "transcript") { element ->
        val o = element.obj()
        val list = o.req("data") { d ->
            d.list { m ->
                val c = m.obj()
                when (val type = c.req("type") { it.str() }) {
                    "compaction" -> Message(type, c.opt("status") { it.str() }, null, null)
                    "assistant" -> Message(
                        type, null,
                        c.opt("model") { WireJson.decodeFromJsonElement(OpenCodeModelRef.serializer(), it) },
                        c.opt("tokens") { WireJson.decodeFromJsonElement(TokensPayload.serializer(), it) },
                    )
                    else -> Message(type, null, null, null)
                }
            }
        }
        val cursor = o.opt("cursor") { WireJson.decodeFromJsonElement(CursorPayload.serializer(), it) }
        list to cursor?.next
    }
    for (message in messages) {
        if (message.type == "compaction" && message.status == "completed") return OpenCodeContextReading.Empty
        if (message.type != "assistant") continue
        val tokens = message.tokens ?: continue
        val model = message.model ?: continue
        val total = tokens.total
        if (total <= 0) return OpenCodeContextReading.Empty
        return OpenCodeContextReading.Measured(OpenCodeContextUsage(total, model))
    }
    return if (next == null) OpenCodeContextReading.Empty else OpenCodeContextReading.Inconclusive
}

private fun contextUsage(payload: MessagesPayload): OpenCodeContextReading {
    for (message in payload.data.take(CONTEXT_USAGE_MESSAGE_LIMIT)) {
        if (message.type == "compaction" && message.status == "completed") return OpenCodeContextReading.Empty
        if (message.type != "assistant") continue
        val tokens = message.tokens ?: continue
        val model = message.model?.ref ?: continue
        val total = tokens.total
        if (total <= 0) return OpenCodeContextReading.Empty
        return OpenCodeContextReading.Measured(OpenCodeContextUsage(total, model))
    }
    val reachedHistoryStart = payload.data.size <= CONTEXT_USAGE_MESSAGE_LIMIT && payload.cursor?.next == null
    return if (reachedHistoryStart) OpenCodeContextReading.Empty else OpenCodeContextReading.Inconclusive
}

private fun compaction(message: MessagePayload): OpenCodeCompaction {
    val status = when {
        message.status == "running" -> OpenCodeCompaction.Status.Running
        message.status == "failed" && message.error?.type == "aborted" -> OpenCodeCompaction.Status.Cancelled
        message.status == "failed" -> OpenCodeCompaction.Status.Failed
        else -> OpenCodeCompaction.Status.Completed
    }
    val tokens = message.tokens?.let {
        (it.input + it.cache.read + it.cache.write).toInt() to (it.output + it.reasoning).toInt()
    }
    val hasUsage = status != OpenCodeCompaction.Status.Running && (tokens?.let { it.first + it.second > 0 } ?: false)
    return OpenCodeCompaction(
        status = status,
        isProvider = status == OpenCodeCompaction.Status.Completed && message.hasProviderContext,
        summary = (message.summary ?: "").trimmedWhitespace().bounded(20_000),
        errorMessage = if (status == OpenCodeCompaction.Status.Failed) message.error?.message?.trimmedWhitespace()?.nonEmpty else null,
        inputTokens = if (hasUsage) tokens.first else null,
        outputTokens = if (hasUsage) tokens.second else null,
    )
}

/**
 * The human-facing notice for a synthetic message, delivered or still in the inbox. Both
 * carry the same ID, so the row keeps its identity. Its model-facing `text` is never shown.
 */
internal fun syntheticNotice(id: String, description: String?, metadata: Map<String, OpenCodeJSONValue>?): OpenCodeTranscriptItem? {
    val text = description?.trimmedWhitespace()?.nonEmpty ?: return null
    val source = metadata?.get("source")?.string
    val state = metadata?.get("state")?.string
    // Like desktop, a shell that completed but exited non-zero or timed out failed.
    val exitedNonZero = (metadata?.get("exit") as? OpenCodeJSONValue.Number)?.let { it.value != 0.0 } ?: false
    val failed = state == "error" ||
        (source == "shell" && state == "completed" && (metadata.get("timeout") == OpenCodeJSONValue.Bool(true) || exitedNonZero))
    val actor = when (source) {
        "shell" -> TranscriptNoticeKind.Source.Shell
        "subagent" -> TranscriptNoticeKind.Source.Subagent
        else -> null
    }
    val kind = actor?.let {
        TranscriptNoticeKind(
            it,
            when {
                failed -> TranscriptNoticeKind.Outcome.Failed
                state == "cancelled" -> TranscriptNoticeKind.Outcome.Cancelled
                else -> TranscriptNoticeKind.Outcome.Finished
            },
        )
    }
    val label = kind?.heading(metadata?.get("agent")?.string) ?: localized(R.string.server_notice_session_update)
    return OpenCodeTranscriptItem.Notice(id, "$label · $text", isError = failed, kind = kind)
}
