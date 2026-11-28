package ai.opencode.server.model

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.server.R
import ai.opencode.server.TaskLocal
import ai.opencode.server.internal.ElementSerializer
import ai.opencode.server.internal.WireJson
import ai.opencode.server.internal.lenient
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.bool
import ai.opencode.server.internal.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.Serializable
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * A file the model receives as a path on the server rather than inline bytes. Same shape
 * as desktop's `PromptAttachmentReference`, which travels in `metadata.attachments`.
 */
@Serializable
data class OpenCodeFileReference(val name: String, val mime: String, val path: String)

/**
 * Text quoted from the transcript, as desktop sends it: a block in the model text and a
 * `{"type":"quote"}` entry in `metadata.comments`.
 */
@Serializable
data class OpenCodeQuote(val text: String, val comment: String? = null)

/**
 * How desktop folds path attachments into a prompt: the model reads a reference line per
 * file, while clients render `displayText` and chips.
 */
object OpenCodePromptPresentation {
    fun referenceLine(file: OpenCodeFileReference): String = "Attached file: `${file.path}`"

    /** The model-visible text: the typed text, then one reference line per file. */
    fun modelText(text: String, files: List<OpenCodeFileReference>): String {
        val typed = if (text.trimmedWhitespace().isEmpty()) emptyList() else listOf(text)
        return (typed + files.map(::referenceLine)).joinToString("\n")
    }

    fun quoteBlock(quote: OpenCodeQuote): String {
        val lines = quote.text.split("\n").joinToString("\n") { "> $it" }
        val block = "The user quoted the following from earlier in the conversation:\n$lines"
        val comment = quote.comment?.trimmedWhitespace()?.nonEmpty ?: return block
        return "$block\n\nThe user made the following comment regarding this quote: $comment"
    }

    /** The typed text and file references, then each quote's block, then the page block. */
    fun modelText(
        text: String,
        files: List<OpenCodeFileReference>,
        page: OpenCodePageContext?,
        quotes: List<OpenCodeQuote> = emptyList(),
    ): String {
        val parts = ArrayList<String>()
        val typed = modelText(text, files)
        if (typed.isNotEmpty()) parts += typed
        for (quote in quotes) parts += quoteBlock(quote)
        val base = parts.joinToString("\n")
        page ?: return base
        return if (base.isEmpty()) pageBlock(page) else base + "\n\n" + pageBlock(page)
    }

    /** Reads `metadata.attachments`, skipping malformed entries like desktop does. */
    fun files(metadata: Map<String, OpenCodeJSONValue>?): List<OpenCodeFileReference> {
        val items = (metadata?.get("attachments") as? OpenCodeJSONValue.Array)?.values ?: return emptyList()
        return items.mapNotNull { item ->
            val o = item.obj ?: return@mapNotNull null
            val name = o["name"]?.string ?: return@mapNotNull null
            val mime = o["mime"]?.string ?: return@mapNotNull null
            val path = o["path"]?.string ?: return@mapNotNull null
            OpenCodeFileReference(name, mime, path)
        }
    }

    fun quotes(metadata: Map<String, OpenCodeJSONValue>?): List<OpenCodeQuote> {
        val items = (metadata?.get("comments") as? OpenCodeJSONValue.Array)?.values ?: return emptyList()
        val quotes = ArrayList<OpenCodeQuote>()
        for (item in items) {
            val o = item.obj ?: continue
            if (o["type"]?.string != "quote") continue
            val text = o["text"]?.string ?: continue
            quotes += OpenCodeQuote(text, o["comment"]?.string?.trimmedWhitespace()?.nonEmpty)
        }
        return quotes
    }

    /** The text a client shows: the model text without its reference lines. */
    fun displayText(text: String, files: List<OpenCodeFileReference>): String {
        if (files.isEmpty()) return text
        val references = files.map(::referenceLine).toSet()
        return text.split("\n").filter { it !in references }.joinToString("\n").trimmedWhitespace()
    }

    /** No reference lines, and no page block when the message says it carries one (`metadata.page`). */
    fun displayText(text: String, files: List<OpenCodeFileReference>, metadata: Map<String, OpenCodeJSONValue>?): String {
        // The quote blocks are the model's; `displayText` is what was typed.
        if (quotes(metadata).isNotEmpty()) metadata?.get("displayText")?.string?.let { return it.trimmedWhitespace() }
        val stripped = if (metadata?.get("page") == null) text else removingPageBlock(text)
        return displayText(stripped, files)
    }

    private val PAGE_BLOCK = Regex("\\s*<page url=\"[^\"]*\"[^>]*>[\\s\\S]*</page>\\s*$")

    fun removingPageBlock(text: String): String {
        val match = PAGE_BLOCK.find(text) ?: return text
        return text.substring(0, match.range.first)
    }

    /** `<page url="…" title="…" selection="…">visible text</page>`. */
    fun pageBlock(page: OpenCodePageContext): String {
        val bounded = page.bounded
        var attributes = "url=\"${attribute(bounded.url)}\" title=\"${attribute(bounded.title)}\""
        bounded.selection?.let { attributes += " selection=\"${attribute(it)}\"" }
        val visible = (bounded.visibleText ?: "").replace("</page>", "&lt;/page&gt;")
        return "<page $attributes>\n$visible\n</page>"
    }

    private fun attribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace("\n", " ")
}

/** The page a message was sent from in the in-app browser. */
data class OpenCodePageContext(
    val url: String,
    val title: String,
    /** The page's selected text, if any (never from a form field). */
    val selection: String? = null,
    /** What's on screen, without form fields, capped at [VISIBLE_TEXT_LIMIT]. */
    val visibleText: String? = null,
    /** How far down the page the reader is, 0…1. */
    val scroll: Double? = null,
) {
    /** The same page, capped and trimmed for the wire. */
    val bounded: OpenCodePageContext
        get() = copy(
            title = trimmedClip(title, TITLE_LIMIT),
            selection = selection?.let { trimmedClip(it, SELECTION_LIMIT).nonEmpty },
            visibleText = visibleText?.let { trimmedClip(it, VISIBLE_TEXT_LIMIT).nonEmpty },
            scroll = scroll?.let { minOf(1.0, maxOf(0.0, Math.round(it * 100) / 100.0)) },
        )

    companion object {
        const val VISIBLE_TEXT_LIMIT = 4 * 1024
        const val SELECTION_LIMIT = 1024
        const val TITLE_LIMIT = 200

        private fun trimmedClip(text: String, bytes: Int) = clip(text.trimmedWhitespace(), bytes)

        /** At most [bytes] of UTF-8, cut on a character boundary. */
        fun clip(text: String, bytes: Int): String {
            if (utf8Length(text) <= bytes) return text
            val result = StringBuilder()
            var used = 0
            var index = 0
            while (index < text.length) {
                val end = text.offsetByCodePoints(index, 1)
                val piece = text.substring(index, end)
                val size = utf8Length(piece)
                if (used + size > bytes - 3) break
                result.append(piece)
                used += size
                index = end
            }
            return "$result…"
        }
    }
}

/** A file shown on a sent user message: a path reference, or an inline non-image file. */
@Serializable
data class OpenCodeTranscriptFile(
    val id: String,
    val name: String,
    val mime: String,
    val path: String? = null,
    /** An inline plain-text file's contents: a Pasted Text card. */
    val text: String? = null,
    /** The card's summary, computed once with [text]. */
    val pasted: PastedTextSummary? = text?.let(::PastedTextSummary),
    val mediaHash: String? = null,
    val byteCount: Long? = null,
    /** A `media:<hash>` reference whose bytes the server no longer has. */
    val isUnavailable: Boolean = false,
) {
    val mediaViewerReference: AgentFileReference?
        get() {
            val hash = mediaHash?.takeIf(OpenCodeMediaReference::isValidHash) ?: return null
            val normalizedMime = mime.lowercase()
            if (isUnavailable || PastedText.isText(mime) || normalizedMime.startsWith("image/")) return null
            return AgentFileReference(
                path = OpenCodeMediaReference.uri(hash),
                media = MediaFileOriginal(hash, AttachmentFileType.sanitizedName(name), normalizedMime, byteCount),
            )
        }

    val typeLabel: String get() = if (isUnavailable) localized(R.string.server_media_unavailable) else AttachmentFileType.label(name, mime)
    val symbolName: String get() = if (isUnavailable) "exclamationmark.triangle" else AttachmentFileType.symbolName(name, mime)
}

sealed interface OpenCodeUploadEvent {
    data class Progress(val sent: Long) : OpenCodeUploadEvent
    /** The absolute path the server wrote. */
    data class Finished(val path: String) : OpenCodeUploadEvent
}

/** A long paste, sent as an inline `text/plain` file named “Pasted Text”. */
object PastedText {
    /** The file name the paste is sent under; it is data, so it stays English. */
    const val NAME = "Pasted Text"

    /** What a paste is called on screen. */
    val title: String get() = localized(R.string.server_pasted_text_title)
    const val MIME = "text/plain"
    const val LINE_LIMIT = 12
    const val CHARACTER_LIMIT = 1_000
    const val PREVIEW_WINDOW_BYTES = 4 * 1024

    /** More than [LINE_LIMIT] lines or [CHARACTER_LIMIT] characters. */
    fun isLong(text: String): Boolean {
        if (text.codePointCount(0, text.length) > CHARACTER_LIMIT) return true
        var newlines = 0
        for (c in text) if (c == '\n') {
            newlines += 1
            if (newlines > LINE_LIMIT) return true
        }
        return newlines == LINE_LIMIT && !text.endsWith("\n")
    }

    /** Lines as an editor numbers them: a trailing newline adds none. */
    fun lineCount(text: String): Int = PastedTextSummary.scan(text).first

    /** The composer attachment for a paste, line endings normalised like desktop's. */
    fun attachment(text: String, id: String = java.util.UUID.randomUUID().toString()): OpenCodeOutgoingAttachment {
        val normalized = if (text.contains('\r')) text.replace("\r\n", "\n").replace("\r", "\n") else text
        return OpenCodeOutgoingAttachment(
            id = id, data = normalized.toByteArray(Charsets.UTF_8), mime = MIME, name = NAME,
            pastedText = normalized, pasted = PastedTextSummary(normalized),
        )
    }

    /** The text of a file that's plain text, else null. */
    fun text(mime: String, data: ByteArray): String? {
        if (!isText(mime)) return null
        val decoder = Charsets.UTF_8.newDecoder()
        return try { decoder.decode(java.nio.ByteBuffer.wrap(data)).toString() } catch (_: Exception) { null }
    }

    fun isText(mime: String): Boolean = mime.split(";").firstOrNull()?.trim()?.lowercase() == MIME

    /** The first lines with words on them, tabs as spaces, each at most 200 characters. */
    fun preview(text: String, lines: Int = 2): List<String> {
        val window = utf8Prefix(text, PREVIEW_WINDOW_BYTES)
        val result = mutableListOf<String>()
        for (line in window.split("\n")) {
            if (line.isEmpty()) continue
            val trimmed = line.take(400).replace("\t", "  ").trimmedWhitespace()
            if (trimmed.isEmpty()) continue
            result += trimmed.take(200)
            if (result.size == lines) break
        }
        return result
    }

    private fun utf8Prefix(text: String, bytes: Int): String {
        var used = 0
        var index = 0
        while (index < text.length) {
            val end = text.offsetByCodePoints(index, 1)
            val size = utf8Length(text.substring(index, end))
            if (used + size > bytes) break
            used += size
            index = end
        }
        return text.substring(0, index)
    }
}

/** What a Pasted Text card and the viewer's header show, computed once from the text. */
@Serializable
data class PastedTextSummary(
    val lineCount: Int,
    val byteCount: Int,
    /** Bytes in the longest line; the viewer opens very long lines as plain text. */
    val longestLineBytes: Int,
    val preview: List<String>,
) {
    constructor(text: String) : this(
        lineCount = scan(text).first,
        byteCount = utf8Length(text),
        longestLineBytes = scan(text).second,
        preview = PastedText.preview(text),
    )

    /** “42 lines · 3 KB”. */
    val summary: String get() = localizedPlural(R.plurals.server_pasted_text_summary, lineCount, lineCount, size)

    /** “42 lines”, or the size of one long line. */
    val length: String get() = if (lineCount > 1) localizedPlural(R.plurals.server_pasted_text_lines, lineCount, lineCount) else size

    /** Like `ByteCountFormatter` `.file`: decimal units. */
    val size: String get() = formatFileSize(byteCount.toLong())

    /** Too big, or a line too long, for the numbered code viewer. */
    val opensAsPlainText: Boolean get() = byteCount > PLAIN_TEXT_BYTES || longestLineBytes > PLAIN_LINE_BYTES

    companion object {
        const val PLAIN_TEXT_BYTES = 2 * 1024 * 1024
        const val PLAIN_LINE_BYTES = 16 * 1024

        /** One pass: lines (a trailing newline adds none) and the longest line in UTF-8 bytes. */
        fun scan(text: String): Pair<Int, Int> {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) return 1 to 0
            var newlines = 0
            var longest = 0
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == '\n'.code.toByte()) {
                    longest = maxOf(longest, i - start)
                    newlines += 1
                    start = i + 1
                }
            }
            if (start < bytes.size) longest = maxOf(longest, bytes.size - start)
            val endsWithNewline = bytes.last() == '\n'.code.toByte()
            return maxOf(newlines + if (endsWithNewline) 0 else 1, 1) to longest
        }

        internal fun formatFileSize(bytes: Long): String {
            if (bytes == 0L) return localized(R.string.server_file_size_zero)
            if (bytes < 1000) return localizedPlural(R.plurals.server_file_size_bytes, bytes.toInt(), bytes)
            val units = listOf(
                R.string.server_file_size_kilobytes,
                R.string.server_file_size_megabytes,
                R.string.server_file_size_gigabytes,
                R.string.server_file_size_terabytes,
            )
            var value = bytes / 1000.0
            var unit = 0
            while (value >= 1000 && unit < units.lastIndex) {
                value /= 1000
                unit++
            }
            val separator = DecimalFormatSymbols.getInstance(appFormattingLocale()).decimalSeparator
            val text = if (unit == 0) Math.round(value).toString() else String.format(Locale.US, "%.1f", value).removeSuffix(".0").replace('.', separator)
            return localized(units[unit], text)
        }
    }
}

/** A skill returned by `GET /api/skill`. */
@Serializable
data class OpenCodeAutocompleteSkill(val id: String, val name: String, val description: String? = null)

/** An agent returned by `GET /api/agent`; `id` falls back to `name` and vice versa. */
@Serializable(with = OpenCodeAutocompleteAgentSerializer::class)
data class OpenCodeAutocompleteAgent(
    val id: String,
    val name: String,
    val description: String? = null,
    val mode: String = "subagent",
    val hidden: Boolean = false,
    /** The model the person's config gives this agent (`agents.<id>.model`). */
    val model: OpenCodeModelRef? = null,
) {
    /** “build” → “Build”: servers name built-in agents in lowercase. */
    val displayName: String get() = if (name == id) AgentDisplayName.titleCased(name) else name

    companion object {
        fun named(name: String, description: String? = null, mode: String = "subagent", hidden: Boolean = false) =
            OpenCodeAutocompleteAgent(name, name, description, mode, hidden)

        /** Primary agents a person can choose (`mode` primary or all, not hidden). */
        fun choosable(agents: List<OpenCodeAutocompleteAgent>) =
            agents.filter { !it.hidden && (it.mode == "primary" || it.mode == "all") }
    }
}

/** A slash command returned by `GET /api/command`. */
@Serializable
data class OpenCodeAutocompleteCommand(val name: String, val description: String? = null) {
    val id: String get() = name
}

/** A file returned by `GET /api/fs/find`. */
@Serializable
data class OpenCodeAutocompleteFile(val relativePath: String, val absolutePath: String, val directory: String) {
    val id: String get() = relativePath
    val name: String get() = relativePath.trimEnd('/').substringAfterLast('/')
    val folder: String
        get() {
            val trimmed = relativePath.trimEnd('/')
            val parent = if (trimmed.contains('/')) trimmed.substringBeforeLast('/') else ""
            if (parent.isEmpty() || parent == ".") {
                val dirName = directory.trimEnd('/').substringAfterLast('/')
                return dirName.ifEmpty { localized(R.string.server_autocomplete_folder_project) }
            }
            return parent
        }
}

/** Character span of a mention in the prompt's `text` (`Prompt.Mention`). */
@Serializable
data class OpenCodeMention(val text: String, val start: Int, val end: Int)

/** An `@agent` mention in `POST /api/session/{id}/prompt` or `/command`. */
@Serializable
data class OpenCodeAgentMention(val name: String, val mention: OpenCodeMention? = null)

/** A `@skill` mention. */
@Serializable
data class OpenCodeSkillMention(val id: String, val mention: OpenCodeMention? = null)

/** A `@file` mention. */
@Serializable
data class OpenCodeFileMention(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mention: OpenCodeMention? = null,
)

/** Structured autocomplete mentions carried by a prompt admission request. */
@Serializable
data class OpenCodeAutocompleteMentions(
    val files: List<OpenCodeFileMention> = emptyList(),
    val agents: List<OpenCodeAgentMention> = emptyList(),
    val skills: List<OpenCodeSkillMention> = emptyList(),
) {
    val isEmpty: Boolean get() = files.isEmpty() && agents.isEmpty() && skills.isEmpty()
}

/** Per-send context the prompt body folds in without changing every client signature. */
object PromptContext {
    val page = TaskLocal<OpenCodePageContext?> { null }
    /** The notify plugin's callback: POSTs here when this run ends. */
    val notificationCallbackURL = TaskLocal<String?> { null }
    val mentions = TaskLocal<OpenCodeAutocompleteMentions?> { null }
    val quotes = TaskLocal<List<OpenCodeQuote>> { emptyList() }
    /** The ID a prompt sent with `sendDeliveredPrompt` is admitted under; unset, each call makes a fresh one. */
    val messageID = TaskLocal<String?> { null }
    /** The directory a `"global"` (MCP elicitation) form reply or cancellation targets. */
    val formLocationDirectory = TaskLocal<String?> { null }
}

internal object OpenCodeAutocompleteAgentSerializer : ElementSerializer<OpenCodeAutocompleteAgent>(
    decode = { element ->
        val o = element.obj()
        val decodedName = o.opt("name") { it.str() }
        val decodedID = o.opt("id") { it.str() }
        val resolvedName = decodedName ?: decodedID ?: ""
        OpenCodeAutocompleteAgent(
            id = decodedID ?: resolvedName,
            name = resolvedName,
            description = o.opt("description") { it.str() },
            mode = o.opt("mode") { it.str() } ?: "subagent",
            hidden = o.opt("hidden") { it.bool() } ?: false,
            model = o.lenient("model") { WireJson.decodeFromJsonElement(OpenCodeModelRef.serializer(), it) },
        )
    },
    encode = { agent ->
        JsonObject(buildMap {
            put("id", JsonPrimitive(agent.id))
            put("name", JsonPrimitive(agent.name))
            agent.description?.let { put("description", JsonPrimitive(it)) }
            put("mode", JsonPrimitive(agent.mode))
            put("hidden", JsonPrimitive(agent.hidden))
            agent.model?.let { put("model", WireJson.encodeToJsonElement(OpenCodeModelRef.serializer(), it)) }
        })
    },
)

/**
 * What a send asks of the in-app browser's open page. Here because the composer's `PendingPrompt`
 * carries it and `:feature:composer` sits beside, not above, the browser's module.
 */
data class PageShareRequest(
    val pageID: java.util.UUID,
    val url: String,
    val title: String,
)
