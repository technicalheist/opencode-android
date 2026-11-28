package ai.opencode.feature.voice

import ai.opencode.server.model.VoiceViewingPage
import ai.opencode.core.sharing.InstantSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The conversation tail that travels with each delegation, ported from
 * opencode-voice's `transcript.ts`. Provider utterance IDs identify turns;
 * text equality never does. A re-transcribed utterance is a revision of its
 * line, not a new line. Lines not yet delivered stay whole, so a delegation
 * carries everything said since the previous one, up to their own larger
 * bound (`maxUndeliveredEntries`, `maxUndeliveredCharacters`), past which
 * the next capture notes what it dropped. Delivered context is bounded by
 * `maxLines` and `maxCharacters`, as is `lines`, the continuity tail.
 *
 * Immutable: the mutating methods return the updated tail.
 */
data class VoiceTranscriptTail private constructor(
    private val entries: List<Entry>,
    private val nextKey: Int,
    private val evictedIDs: Set<String>,
    /** Undelivered turns were dropped since the last capture. */
    private val omittedUndelivered: Boolean,
) {
    @Serializable
    enum class Role(val rawValue: String) {
        @SerialName("user") user("user"),
        @SerialName("assistant") assistant("assistant"),
    }

    private data class Entry(
        /**
         * Stable local identity: captures outlive evictions that shift array
         * positions, and not every line has a provider ID.
         */
        val key: Int,
        val providerID: String? = null,
        val role: Role,
        val text: String,
        val version: Int = 1,
        /** The version most recently delivered to the session, and its text. */
        val sent: Int = 0,
        val sentText: String? = null,
        /** The version captured for an admission that has not yet been accepted, and its text. */
        val reserved: Int? = null,
        val reservedText: String? = null,
    ) {
        /** Sent, and no capture of it is in flight: only context now. */
        val isDelivered: Boolean get() = version <= sent && reserved == null

        /** Waiting for the next capture: nothing has taken this version yet. */
        val isPending: Boolean get() = version > maxOf(sent, reserved ?: 0)
    }

    enum class RecordOutcome { added, updated, duplicate, outsideTail, empty }

    data class Capture(
        val lines: List<String>,
        /** Captured version and text by entry key. */
        internal val versions: Map<Int, Int>,
        internal val texts: Map<Int, String> = emptyMap(),
        /**
         * What the user said since the previous delegation, in order: new
         * utterances whole, and only the words an utterance gained since it was
         * delivered (a revision that rewrote it travels whole).
         */
        val userWords: List<String> = emptyList(),
        /** Undelivered turns were dropped before this capture. */
        internal val omitted: Boolean = false,
    ) {
        /** The user's own words since the previous delegation: the request. */
        val userText: String get() = userWords.joinToString(" ")

        companion object {
            val empty = Capture(lines = emptyList(), versions = emptyMap())
        }
    }

    /** [record]'s result: the updated tail and what happened. */
    data class Recorded(val tail: VoiceTranscriptTail, val outcome: RecordOutcome)

    /** [capture]'s result: the tail with the capture reserved, and the capture. */
    data class Captured(val tail: VoiceTranscriptTail, val capture: Capture)

    fun record(role: Role, text: String, id: String? = null): Recorded {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Recorded(this, RecordOutcome.empty)
        val providerID = id
        if (providerID != null) {
            val index = entries.indexOfFirst { it.providerID == providerID }
            if (index >= 0) {
                if (entries[index].text == trimmed) return Recorded(this, RecordOutcome.duplicate)
                val updated = entries.toMutableList()
                updated[index] = updated[index].copy(text = trimmed, version = updated[index].version + 1)
                return Recorded(copy(entries = updated).evicted(), RecordOutcome.updated)
            }
            if (providerID in evictedIDs) return Recorded(this, RecordOutcome.outsideTail)
        }
        val appended = entries + Entry(key = nextKey, providerID = providerID, role = role, text = trimmed)
        return Recorded(copy(entries = appended, nextKey = nextKey + 1).evicted(), RecordOutcome.added)
    }

    /**
     * Lines changed since the last accepted capture, reserved until the
     * caller either accepts (admitted) or releases (failed) them.
     */
    fun capture(): Captured {
        val lines = mutableListOf<String>()
        val versions = mutableMapOf<Int, Int>()
        val texts = mutableMapOf<Int, String>()
        val userWords = mutableListOf<String>()
        val updated = entries.toMutableList()
        for (index in entries.indices) {
            val entry = entries[index]
            val delivered = maxOf(entry.sent, entry.reserved ?: 0)
            if (entry.version <= delivered) continue
            val label = if (entry.sent > 0) "${entry.role.rawValue} (updated utterance)" else entry.role.rawValue
            lines += "$label: ${entry.text}"
            versions[entry.key] = entry.version
            texts[entry.key] = entry.text
            if (entry.role == Role.user) {
                var words = entry.text
                val previous = entry.reservedText ?: entry.sentText
                if (previous != null && entry.text.startsWith(previous)) {
                    words = entry.text.substring(previous.length).trim()
                }
                if (words.isNotEmpty()) userWords += words
            }
            updated[index] = entry.copy(reserved = entry.version, reservedText = entry.text)
        }
        var omitted = false
        var stillOmitted = omittedUndelivered
        // The note leads what it precedes; with nothing captured it waits.
        if (omittedUndelivered && lines.isNotEmpty()) {
            stillOmitted = false
            omitted = true
            lines.add(0, omittedNote)
            if (userWords.isNotEmpty()) userWords.add(0, omittedNote)
        }
        val capture = Capture(lines, versions, texts, userWords, omitted)
        return Captured(copy(entries = updated, omittedUndelivered = stillOmitted), capture)
    }

    fun accept(capture: Capture): VoiceTranscriptTail {
        val updated = entries.map { entry ->
            val version = capture.versions[entry.key] ?: return@map entry
            var next = entry
            if (version > next.sent) next = next.copy(sent = version, sentText = capture.texts[entry.key])
            if (next.reserved == version) next = next.copy(reserved = null, reservedText = null)
            next
        }
        return copy(entries = updated).evicted()
    }

    fun release(capture: Capture): VoiceTranscriptTail {
        val updated = entries.map { entry ->
            val version = capture.versions[entry.key] ?: return@map entry
            if (entry.reserved == version) entry.copy(reserved = null, reservedText = null) else entry
        }
        return copy(entries = updated, omittedUndelivered = omittedUndelivered || capture.omitted)
    }

    /**
     * Plain `role: text` lines for persistence and next-call continuity: the
     * newest that fit `maxLines` and `maxCharacters`, always including the
     * newest line and the newest user line, their starts elided when too long.
     */
    val lines: List<String>
        get() {
            val kept = mutableListOf<String>()
            var characters = 0
            var keptUser = false
            for (entry in entries.asReversed()) {
                val line = "${entry.role.rawValue}: ${entry.text}"
                val separator = if (kept.isEmpty()) 0 else 1
                if (kept.size >= maxLines || characters + separator + line.length > maxCharacters) {
                    val prefix = "${entry.role.rawValue}: …"
                    val room = maxCharacters - characters - separator - prefix.length
                    if (kept.size < maxLines && (kept.isEmpty() || (entry.role == Role.user && !keptUser)) && room > 0) {
                        kept += prefix + entry.text.takeLast(room)
                    }
                    break
                }
                kept += line
                characters += separator + line.length
                keptUser = keptUser || entry.role == Role.user
            }
            return kept.asReversed().toList()
        }

    /**
     * Drops the oldest delivered lines past the bounds, except the newest
     * user line, which a revision may still extend. Undelivered words are the
     * next delegation's request, so only their own, larger bound drops them,
     * oldest first and never the newest user turn or what followed it.
     */
    private fun evicted(): VoiceTranscriptTail {
        val entries = entries.toMutableList()
        val evictedIDs = evictedIDs.toMutableSet()
        var omittedUndelivered = omittedUndelivered
        fun remove(index: Int) {
            val removed = entries.removeAt(index)
            removed.providerID?.let(evictedIDs::add)
        }
        fun characterCount(): Int = entries.fold(maxOf(0, entries.size - 1)) { total, entry ->
            total + entry.role.rawValue.length + 2 + entry.text.length
        }
        val newestUser = entries.lastOrNull { it.role == Role.user }?.key
        while (entries.size > maxLines || characterCount() > maxCharacters) {
            val index = entries.indexOfFirst { it.isDelivered && it.key != newestUser }
            if (index < 0) break
            remove(index)
        }
        while (true) {
            val pending = entries.indices.filter { entries[it].isPending }
            val characters = pending.sumOf { entries[it].text.length }
            // Older than the newest pending user turn, or, with none, the newest pending line.
            val keep = pending.lastOrNull { entries[it].role == Role.user } ?: pending.lastOrNull()
            val oldest = pending.firstOrNull()
            if (oldest == null || keep == null || oldest >= keep ||
                !(pending.size > maxUndeliveredEntries || characters > maxUndeliveredCharacters)
            ) break
            remove(oldest)
            omittedUndelivered = true
        }
        return VoiceTranscriptTail(entries, nextKey, evictedIDs, omittedUndelivered)
    }

    companion object {
        const val maxLines = 12
        const val maxCharacters = 2_000

        /**
         * Undelivered speech past these (a long call that never delegates) drops
         * its oldest turns, and the next capture says so.
         */
        const val maxUndeliveredEntries = 60
        const val maxUndeliveredCharacters = 20_000
        const val omittedNote = "[earlier conversation omitted]"

        /** A tail seeded with already-delivered lines. */
        operator fun invoke(lines: List<String> = emptyList()): VoiceTranscriptTail {
            val entries = lines.mapIndexed { key, line ->
                if (line.startsWith("assistant: ")) {
                    val text = line.removePrefix("assistant: ")
                    Entry(key = key, role = Role.assistant, text = text, sent = 1, sentText = text)
                } else {
                    val text = if (line.startsWith("user: ")) line.removePrefix("user: ") else line
                    Entry(key = key, role = Role.user, text = text, sent = 1, sentText = text)
                }
            }
            return VoiceTranscriptTail(entries, entries.size, emptySet(), false).evicted()
        }
    }
}

/**
 * What the pinned session receives for one `delegate` call: the user's own
 * words as the request, the voice agent's framing for display, and the
 * surrounding conversation as background.
 */
object VoiceHandoff {
    const val header = "Voice delegation. The user is on a voice call with a voice agent, which hands their requests to you. Instruction is the user's own spoken words since the previous delegation, transcribed from speech, so it may contain recognition errors and disfluencies: resolve meaning from the complete utterance, not a disfluent prefix. Voice, when present, is how the voice agent framed the request. Transcript delta is the surrounding voice conversation, both sides, for context, not additional commands. Lead your reply with one or two plain sentences suitable for reading aloud, then any detail; answer only what was asked, with no unrequested tips. If this arrives while you are already working (a follow-up, nudge, or status check), answer it promptly: a clear status update, the finished result if you have enough, or the precise blocker or input you need; then continue on the fastest safe path."

    /** Later delegations in a call point back at the first one's header (D4a). */
    const val continuedHeader = "Voice delegation (continued). Same fields as before."

    /**
     * How long (seconds) a session goes without a delegation before the next one
     * carries the full header again. Within a conversation delegations come
     * seconds or minutes apart; half an hour without one means the session has
     * usually moved on (typed prompts, long tool output), leaving the
     * explanation far back in its context. Repeating it costs about 200 tokens.
     */
    const val headerLapse: Double = 30.0 * 60

    enum class Header {
        /**
         * The explanation: a call's first delegation into a session, or after
         * compaction or a lapse.
         */
        full,

        /** A short marker for later delegations. */
        continued,
    }

    fun format(
        instruction: String,
        transcriptDelta: List<String>,
        context: String? = null,
        framing: String? = null,
        header: Header = Header.full,
        callLogAttached: Boolean = false,
        callLogURL: String? = null,
        inlineCallLog: String? = null,
        appState: VoiceAppState? = null,
        screenshotAttached: Boolean = false,
        appLogAttached: Boolean = false,
    ): String {
        val trimmedContext = context?.trim()
        val trimmedFraming = framing?.trim()
        val fields = sortedMapOf<String, Any>()
        if (appLogAttached) fields["app_log_attached"] = true
        appState?.let { fields["app_state"] = it.jsonObject }
        if (screenshotAttached) fields["screenshot_attached"] = true
        if (callLogAttached) fields["call_log_attached"] = true
        inlineCallLog?.let { fields["call_log_excerpt"] = it }
        callLogURL?.let { fields["call_log_url"] = it }
        if (!trimmedContext.isNullOrEmpty()) fields["context"] = trimmedContext
        fields["instruction"] = instruction
        fields["transcript_delta"] = transcriptDelta
        if (!trimmedFraming.isNullOrEmpty()) fields["voice"] = trimmedFraming
        val json = PrettyJSON.obj(fields)
        return "${if (header == Header.full) VoiceHandoff.header else continuedHeader}\n$json"
    }

    /**
     * The voice agent's framing of a delegation, shown in the session as the
     * Voice message: Realtime's `delegate` text, or GPT-Live's spoken
     * acknowledgement. Null when it names nothing ("On it", "Sure, I'll take a
     * look"), so the session shows the user's words instead. A word count
     * can't tell "Checking the README." from "Looking that up now."; whether
     * any word names the task can.
     */
    fun framing(text: String?): String? {
        val trimmed = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val words = trimmed.lowercase()
            .replace("’", "'")
            .splitWhere { !it.isLetter() && !it.isDigit() && it != '\'' }
        return if (words.any { it !in genericWords }) trimmed else null
    }

    /**
     * Acknowledgement filler, pronouns, and verbs that say work is happening
     * without saying which.
     */
    private val genericWords: Set<String> = setOf(
        "a", "about", "alright", "all", "along", "an", "and", "ask", "asking", "at", "away", "backend", "be", "bit",
        "call", "check", "checking", "cool", "dig", "digging", "do", "doing", "fair", "find", "finding", "for", "get",
        "getting", "give", "go", "going", "good", "got", "gotcha", "great", "hear", "heard", "here", "hmm", "i", "i'll",
        "i'm", "into", "is", "it", "it's", "just", "let", "let's", "look", "looking", "makes", "me", "mm", "moment",
        "nice", "noted", "now", "of", "ok", "okay", "on", "one", "our", "out", "over", "pass", "passing", "peek", "point",
        "quick", "quickly", "real", "right", "second", "see", "seeing", "sec", "send", "sending", "sense", "session",
        "sessions", "so", "sounds", "start", "starting", "still", "sure", "take", "taking", "team", "that", "that's",
        "the", "there", "these", "thing", "this", "those", "to", "totally", "up", "us", "we", "we'll", "what", "what's",
        "will", "with", "work", "working", "yeah", "yep", "yes", "you", "your",
    )

    /**
     * The most of a session's reply the voice agent gets, about eight minutes
     * spoken: room for a walkthrough the user asked for (1,600 cut those off
     * at about a minute). Longer material belongs in a file.
     */
    const val replyLimit = 8_000

    fun completion(reply: String): String {
        val trimmed = reply.trim()
        if (trimmed.isEmpty()) return "[COMPLETE] OpenCode finished without a text reply."
        val cleaned = LiveContentChunker.collapseCodeBlocks(trimmed)
        val text = cleaned.take(replyLimit)
        return "[COMPLETE] $text"
    }

    /**
     * A result for a request made before the conversation went on.
     * `topic` names the one request it answers, so it's never heard as the
     * answer to what the user just asked.
     */
    fun earlierRequest(notice: String, topic: String? = null): String {
        if (notice.startsWith(earlierRequestLead)) return notice
        return earlierRequestLead + (topic?.let { "$earlierTopicLead$it”. " } ?: "") + notice
    }

    const val earlierRequestLead = "This answers an earlier request, not what the user said last; open by saying it's about that earlier request and naming it, then give the result. "
    const val earlierTopicLead = "The earlier request: “"

    /**
     * A delegation with nothing the user said since their last request was
     * delivered: the voice agent's own. Worded so it isn't repeated to the user.
     */
    const val nothingNewToSend = "Nothing was sent: the user's last request was already delivered and they haven't said anything since. Don't mention this to the user; carry on the conversation, and if you need their request, ask what they'd like."

    /** How long (seconds) after a move the delegator hears where the screen came from. */
    const val recentMoveWindow: Double = 30.0

    fun recentMove(from: String, sessionID: String?, secondsAgo: Int): String {
        val session = sessionID?.let { "session \"$from\" (id $it)" } ?: "a new session draft"
        return "The screen moved here from $session $secondsAgo s ago; if the user's words were about that session, act there."
    }

    fun failure(message: String): String = "[COMPLETE] The OpenCode session could not take that request: $message"

    /**
     * A prompt `format` wrote, read back for display. Null for anything else,
     * including text that merely mentions a voice delegation.
     */
    data class Delegation(
        /** The user's own words. */
        val instruction: String,
        val transcriptDelta: List<String>,
        val context: String? = null,
        /** The voice agent's framing; prompts from before it was sent have none. */
        val framing: String? = null,
        /** True when a snapshot of the voice call log was attached to this message. */
        val callLogAttached: Boolean = false,
    ) {
        /** What the session shows: the voice agent prompting on the user's behalf. */
        val displayText: String get() = framing ?: instruction
    }

    fun parse(text: String): Delegation? {
        // The header's wording may change; its opening and the body's shape won't.
        if (!(text.startsWith("Voice delegation.") || text.startsWith("Voice delegation (continued)."))) return null
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        var rawBody = text.substring(newline + 1)
        var hadAttachedCallLogLine = false
        while (true) {
            val lineBreak = rawBody.lastIndexOf('\n')
            if (lineBreak < 0) break
            val lastLine = rawBody.substring(lineBreak + 1).trim()
            if (lastLine.isEmpty()) {
                rawBody = rawBody.substring(0, lineBreak)
                continue
            }
            if (!(lastLine.startsWith("Attached file: `") && lastLine.endsWith("`"))) break
            if (lastLine.contains("voice-call-")) hadAttachedCallLogLine = true
            rawBody = rawBody.substring(0, lineBreak)
        }
        val body = try { VoiceWireJson.parseToJsonElement(rawBody) as? JsonObject } catch (_: Exception) { null } ?: return null
        fun string(key: String): String? {
            val value = body[key] ?: return null
            if (value is kotlinx.serialization.json.JsonNull) return null
            return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException()
        }
        return try {
            val instructionValue = string("instruction") ?: return null
            val delta = when (val value = body["transcript_delta"]) {
                null, is kotlinx.serialization.json.JsonNull -> null
                is JsonArray -> value.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException() }
                else -> throw IllegalArgumentException()
            }
            val attached = when (val value = body["call_log_attached"]) {
                null, is kotlinx.serialization.json.JsonNull -> null
                is JsonPrimitive -> if (value.isString) throw IllegalArgumentException() else value.booleanOrNull ?: throw IllegalArgumentException()
                else -> throw IllegalArgumentException()
            }
            val contextValue = string("context")
            val voiceValue = string("voice")
            val instruction = instructionValue.trim()
            if (instruction.isEmpty()) return null
            val framing = voiceValue?.trim()
            Delegation(
                instruction = instruction,
                transcriptDelta = delta ?: emptyList(),
                context = contextValue?.trim(),
                framing = framing?.takeIf { it.isNotEmpty() },
                callLogAttached = attached == true || hadAttachedCallLogLine,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Splits on characters matching [isSeparator], dropping empty pieces. */
internal inline fun String.splitWhere(isSeparator: (Char) -> Boolean): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    for (char in this) {
        if (isSeparator(char)) {
            if (current.isNotEmpty()) result += current.toString()
            current.clear()
        } else {
            current.append(char)
        }
    }
    if (current.isNotEmpty()) result += current.toString()
    return result
}

/**
 * Writes JSON: two-space indent, `"key": value`, and an empty array as `[\n\n<indent>]`. Values:
 * String, Boolean, List<String>, maps.
 */
internal object PrettyJSON {
    fun obj(fields: Map<String, Any>): String = StringBuilder().also { write(it, fields, 0) }.toString()

    private fun write(out: StringBuilder, value: Any?, level: Int) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(if (value) "true" else "false")
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append("{\n")
                var first = true
                for ((key, element) in value) {
                    if (!first) out.append(",\n")
                    first = false
                    indent(out, level + 1)
                    string(out, key.toString())
                    out.append(" : ")
                    write(out, element, level + 1)
                }
                out.append("\n")
                indent(out, level)
                out.append("}")
            }
            is List<*> -> {
                out.append("[\n")
                var first = true
                for (element in value) {
                    if (!first) out.append(",\n")
                    first = false
                    indent(out, level + 1)
                    write(out, element, level + 1)
                }
                out.append("\n")
                indent(out, level)
                out.append("]")
            }
            else -> string(out, value.toString())
        }
    }

    private fun indent(out: StringBuilder, level: Int) {
        repeat(level) { out.append("  ") }
    }

    private fun string(out: StringBuilder, value: String) {
        out.append('"')
        for (char in value) {
            when (char) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (char < ' ') out.append("\\u%04x".format(char.code)) else out.append(char)
            }
        }
        out.append('"')
    }
}

/** A record of one session's voice conversations on this phone. */
@Serializable
data class VoiceTranscriptRecord(
    @Serializable(with = InstantSerializer::class) val endedAt: Instant,
    /** Continuity tail injected into the next call. */
    val tail: List<String>,
    /** Everything said on this phone about this session, newest last, bounded. */
    val log: List<String>,
) {
    /** The updated record. */
    fun appending(tail: List<String>, log: List<String>, endedAt: Instant): VoiceTranscriptRecord {
        val combined = this.log + log
        return VoiceTranscriptRecord(endedAt, continuityLines(tail), combined.takeLast(maxLogLines))
    }

    companion object {
        const val maxLogLines = 400
        const val maxSessions = 64

        fun pruned(records: Map<String, VoiceTranscriptRecord>): Map<String, VoiceTranscriptRecord> {
            if (records.size <= maxSessions) return records
            val oldest = records.entries.sortedBy { it.value.endedAt }.take(records.size - maxSessions)
            val kept = records.toMutableMap()
            for (entry in oldest) {
                kept.remove(entry.key)
            }
            return kept
        }

        /** The tail lines a later call may carry: the user's own turns, never the voice agent's. */
        fun continuityLines(lines: List<String>): List<String> = lines.filter { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed == VoiceTranscriptTail.omittedNote) return@filter false
            val lower = trimmed.lowercase()
            !lower.startsWith("assistant:") && !lower.startsWith("voice:")
        }
    }
}

val VoiceViewingPage.host: String?
    get() = try {
        java.net.URI(url).host?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    } catch (_: Exception) {
        null
    }

object VoiceInstructions {
    data class Context(
        val sessionTitle: String,
        val directoryName: String,
        val recentMessages: List<String>,
        val previousTail: List<String>,
        val previousEndedAt: Instant?,
        /** A new session's draft: nothing exists until the first delegation. */
        val isNewSession: Boolean = false,
        /**
         * GPT-Live delegates to the delegator, which manages every session,
         * rather than straight into the one on screen.
         */
        val managesSessions: Boolean = false,
        /** The page open over the session in the in-app browser. */
        val viewing: VoiceViewingPage? = null,
        /** "working" or "idle", when a move knows it. */
        val status: String? = null,
        /** Why the call moved, when the user didn't move it themselves. */
        val unpromptedMoveReason: String? = null,
        /** What the voice model calls itself (Realtime and Grok Voice). */
        val voiceLayer: String = VoiceEngine.realtime.selfDescription,
        /** The user said they're looking at the screen and the app is in front ([VoiceScreenVisibility.visible]). */
        val userSeesScreen: Boolean = false,
        /** The built-in delegator can record the user saying they're looking (`set_user_looking`). */
        val recordsLooking: Boolean = false,
    )

    val delegateTool = RealtimeFunctionTool(
        name = "delegate",
        description = "Delegate the user's request to the OpenCode coding session: inspecting or changing code, running commands, research, or status of ongoing work. Returns the session's reply. While earlier work is still running, a new request or follow-up steers the session at its next step.",
        parameters = RealtimeJSONs.obj(
            "type" to RealtimeJSONs.string("object"),
            "additionalProperties" to RealtimeJSONs.bool(false),
            "properties" to RealtimeJSONs.obj(
                "text" to RealtimeJSONs.obj(
                    "type" to RealtimeJSONs.string("string"),
                    "description" to RealtimeJSONs.string("A self-contained interpretation of the user's complete request, resolving references when known. Clean up speech artifacts without adding tasks or constraints. Keep your own speech separate from the user's instructions. The session shows it as your framing; the client sends the user's own words and the transcript alongside it, so do not copy them into this field."),
                ),
            ),
            "required" to RealtimeJSONs.array(listOf(RealtimeJSONs.string("text"))),
        ),
    )

    val endCallTool = RealtimeFunctionTool(
        name = "end_voice_session",
        description = "End this voice call when the user explicitly asks to hang up or clearly signs off. Never call it for a bare \"stop\", a request to pause, or thanks alone.",
        parameters = RealtimeJSONs.obj(
            "type" to RealtimeJSONs.string("object"),
            "additionalProperties" to RealtimeJSONs.bool(false),
            "properties" to RealtimeJSONs.obj(),
        ),
    )

    val tools: List<RealtimeFunctionTool> get() = listOf(delegateTool, endCallTool)

    fun attachment(context: Context): String {
        val session = if (context.isNewSession) {
            "You are attached to a new coding session in the project folder \"${context.directoryName}\" that has not started yet; the first request you delegate starts it."
        } else {
            "You are attached to the coding session \"${context.sessionTitle}\" in the project folder \"${context.directoryName}\"."
        }
        val page = context.viewing ?: return session
        return session + " " + viewing(page)
    }

    /** What the user is looking at in the in-app browser. */
    fun viewing(page: VoiceViewingPage): String {
        val host = page.host ?: ""
        val title = pageTitle(page.title, host)
        return if (page.isOpen) {
            "The user has a web page open over the session and is reading it: \"$title\" ($host). When they say \"this\" or \"this page\", they mean it."
        } else {
            "The user tucked away the web page \"$title\" ($host) and is looking at the session; they can bring it back."
        }
    }

    /**
     * A page's title as data, not instructions: one line, no quotes or
     * control characters, at most 120 characters.
     */
    fun pageTitle(title: String, host: String): String {
        val replaced = buildString {
            var index = 0
            while (index < title.length) {
                val codePoint = title.codePointAt(index)
                val type = Character.getType(codePoint)
                val isControl = type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()
                val isNewline = codePoint in listOf(0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0x2028, 0x2029)
                if (isControl || isNewline || codePoint in "\"“”`".map { it.code }) append(' ') else appendCodePoint(codePoint)
                index += Character.charCount(codePoint)
            }
        }
        val cleaned = replaced.splitWhere(Char::isWhitespace).joinToString(" ")
        val clipped = if (cleaned.length > 120) cleaned.take(119) + "…" else cleaned
        return clipped.ifEmpty { host }
    }

    /** GPT-Live's short update when the page changes mid-call. */
    fun viewingUpdate(page: VoiceViewingPage?): String {
        if (page == null) return "The user closed the web page and is looking at the session."
        return viewing(page)
    }

    fun build(context: Context, engine: VoiceEngine): String = when (engine) {
        VoiceEngine.gptLive -> buildLive(context)
        VoiceEngine.realtime, VoiceEngine.grok -> build(context.copy(voiceLayer = engine.selfDescription))
    }

    fun build(context: Context, route: VoiceRoute): String {
        route.engine?.let { return build(context, it) }
        return build(context.copy(voiceLayer = "Gemini Live (${GeminiLiveSessionConfig.defaultModel}) from Google"))
    }

    fun buildRetarget(context: Context, route: VoiceRoute): String {
        route.engine?.let { return buildRetarget(context, it) }
        return build(context, route)
    }

    /**
     * For a real session retarget mid-call: Realtime replaces `session.instructions`
     * via `session.update`, while GPT-Live sends a concise session-context delta
     * rather than re-injecting the full startup prompt.
     * Opens a GPT-Live retarget delta; the coordinator drops the old session's waits on it.
     */
    const val retargetPrefix = "Session changed."

    fun buildRetarget(context: Context, engine: VoiceEngine): String = when (engine) {
        VoiceEngine.realtime, VoiceEngine.grok -> build(context.copy(previousTail = emptyList(), previousEndedAt = null), engine)
        VoiceEngine.gptLive -> {
            val status = context.status?.let { " It is $it." } ?: ""
            val move = context.unpromptedMoveReason?.let { "The call moved because $it; unless you already said so, mention in a few words where you are now." }
                ?: if (context.userSeesScreen) {
                    "The user switched screens themselves and is looking at the screen: don't announce or mention the move."
                } else {
                    "The user switched screens themselves but may not be looking now: name the session you're on now in a few words, once, without explaining the move."
                }
            val sections = mutableListOf(
                "$retargetPrefix ${attachment(context)}$status $move Otherwise nothing changes: the call and conversation continue, and earlier requests keep going in their own sessions.",
            )
            if (context.recentMessages.isNotEmpty()) {
                sections += "Recent messages from this session:\n" + context.recentMessages.takeLast(6).joinToString("\n")
            }
            // Switching sessions mid-call never replays raw lines from a previous call.
            sections.joinToString("\n\n")
        }
    }

    fun buildLive(context: Context): String {
        val sections = mutableListOf<String>()
        val tools = if (context.managesSessions) managedTools else attachedTools
        val blocked = if (context.managesSessions) {
            "When a session is blocked on a permission or a question, say what it's asking, and on the user's answer delegate it (delegating can approve, deny, or answer). Never approve or answer without the user's word."
        } else {
            "You cannot approve permissions or answer the session's own questions; if the session is blocked on one of those, say so and tell the user to open the session."
        }
        sections += """You are OpenCode, a relaxed, friendly voice coding assistant. ${attachment(context)} If the session you're attached to changes mid-call, you receive updated instructions.
You are ${VoiceEngine.gptLive.selfDescription}—that is your voice layer, inside OpenCode, whose sessions do the coding. No human team exists: never say "the team", "the backend", "relay", "passing it along", "have them", or "someone will", never call a session "they" or "them", and never imply contacting people a tool didn't message. Questions about GPT-Live, Realtime, or which voice model or provider you are refer to this voice engine: answer directly that you are ${VoiceEngine.gptLive.selfDescription}. For anything else you do not know, say unknown rather than guessing.
Talk like a sharp friend on the phone: relaxed, direct, a little dry, at a natural pace. Keep your own replies to one or two short sentences, then stop; relayed session results keep their full facts.
Treat the user as a capable adult: no unsolicited warnings, safety tips, reminders about obvious everyday things, moralizing, or lectures. Speak up only for a serious, immediate risk they seem to have missed.
Relay the session's replies faithfully, in order: keep every fact, drop only code, IDs, paths and URLs, add nothing. Anything beyond what a result says (the next item, a detail, a number) and any decision on it goes to `delegate`; if you were interrupted, answer what the user just said, and give the rest only if asked.
You NEVER write code, edit files, or run commands yourself; the attached coding session does that work when you delegate. Present all work as your own.
If the user is frustrated, acknowledge it briefly and get to the fix. If they tease you, take it in stride and riff back; never get defensive or passive-aggressive. Don't fawn or agree just to please; disagree plainly when they're wrong.

Backchannel policy: No backchannels. While the user is talking, stay silent: no "mm-hmm", "okay", laugh, or reaction over their words.

Interruption policy: Stop speaking when the user interrupts and listen to what they say, unless the user explicitly asks you to speak at the same time or keep talking while they speak. This holds for updates and results too: if the user starts talking while you give one, stop at once.

Turn-taking policy: The user often dictates long requests and messages, pausing to think. A pause, an "um", or a sentence that trails off is not the end of their turn: keep listening until they've clearly finished. Never start speaking, an update included, while they're mid-thought, and never fill a pause with "okay" or "getting that together".

Attention policy: Respond when the user talks to you or asks you for something. Otherwise, keep listening and say nothing: the user often talks to people near them mid-call. See What You Hear.

${if (context.userSeesScreen) screenLookingPolicy else screenAwayPolicy}

$multitaskPolicy

Delegation policy:
Tools:
$tools

Delegate when:
- The request needs the project, its files, its history, running work, commands, tests, external tools, or careful reasoning.
- The user asks for data or an action and you don't know whether the session can do it: delegate it to the session to find out and use its tools. Report a limitation only after the session reports a real one, describing the narrow blocker; never ask the user to paste data the session may be able to read; never claim access or success you didn't get; never bypass the session's approval gates.
- Work already running never blocks a delegation. Delegate follow-ups and status checks immediately even while work is running; they steer the running work at its next step. Asking how it's going, to hurry, to wrap up with what it has, or to change course is a new delegation, not a repeat: delegate it. Never refuse because something is running, and never tell the user to open or cancel the session for this.
- Clarifications steer the existing task; status requests ask the session for its state; a repeated read-only request is just delegated again. Never say "I can't submit the same request again while it's running".
- Always delegate any check on whether a session has responded, finished, or is still working; never assume work is still running just because no completion notice has been spoken yet.
- Only for an external write with an uncertain outcome (a send, post, or purchase), delegate a status check first and retry the write only if it didn't happen.
- The user answers a question you asked (a session's name, a yes, a choice): the answer completes their earlier request, so delegate that request now. "It didn't work" or "nothing happened" is a request to check: delegate it.
- "Don't wait for it" means delegate now: never hold a request back to send with the next one.${if (context.recordsLooking) lookingDelegation else ""}

Do not delegate when:
- You can answer from a completed result you already delivered on this call, your voice engine identity, or plain conversation (such as greetings meant for you, small talk, or repeating what was just said). Any status or outcome question ("did that go out?", "is it done?", "what's in it?") goes to `delegate` unless a completed result on this call directly answers it.
- You need a brief clarification to understand an incomplete or ambiguous request.
- The speech isn't meant for you or makes no sense: say nothing (What You Hear).

Delegate before giving an answer that depends on session work.
Nothing happens unless you delegate. If you say you're doing something, delegate it in that same turn; if you won't delegate, ask or answer instead.

What you say around a delegation:
- When you delegate, react the way a friend on the phone would: quickly and naturally, to what the user just said. Name the specific thing you understood them to want, in your own words, so they hear how you read it; this matters most when they rambled or circled. Say it in the first person as work you're starting, not as done and not as asking, sending, or handing it to anyone, unless the user asked for exactly that; never "Done". Only a result decides what happened. Never use the same stock word or phrase twice in a row. After a long turn, even one that ends by asking whether you're there, name what they asked for; a stock line that names nothing doesn't cover it. When the user is mid-thought or it's a quick follow-up, a simple yeah or nothing at all is fine.
- A result's own words set the claim. "Asked", "started" or "will" stays that; never upgrade it to "done", and never add a date, deadline or urgency the result didn't say.
- A result describing what would or will happen (or work still running) is not done: say it's conditional or in progress, never that you did it.
- Say it once, without saying how the request will be framed or passed on, and without predicting the session's steps or what will be found: the result decides.
- That reaction already told the user what's happening. When the result comes back, give only the outcome, stating the concrete action and where ("Saved a private note in your notes", "Sent that to the OPENCODE IOS session"); don't restate the request, the plan, or the message that was sent. When a reply lands after you already reported its result, say only what's new; if nothing is new, stay quiet or confirm it's done in a few words.
- Keep any status to one short line—no repeated "it's still running" warnings, no "just wait", and no "open the session" unless the session is blocked on an approval or question only the user can answer there.
- Ask the user about any one thing at most once. If a result asks something the user already answered as best they could, or has moved on from, leave the question out.
Call sessions "sessions". Do not guess the result while waiting. While a request is out, never answer it from earlier results; if the user presses, say you're getting it now.
The newest result about something replaces everything earlier about it: if an earlier result said something was missing, cut off, or unavailable and a newer one has it, give the newer one and never repeat the earlier gap. Never tell the user a result was cut off or truncated.
$blocked

# Fragments & Unclear Speech

- Wait for a complete utterance. If a request meant for you is a fragment or unclear, say briefly that you didn't catch it and ask the user to finish or repeat once—never answer or delegate a speculative interpretation of a fragment. Speech that makes no sense, or may be meant for someone else, gets silence instead.
- A short answer to your own question, or a short report about earlier work, is not a fragment: it completes the request.

$whatYouHearSection

$personalMessagesSection"""
        appendContextSections(sections, context)
        return sections.joinToString("\n\n")
    }

    const val attachedTools = "- Coding session: inspect or change code, edit files, run commands or tests, search the codebase, draft or send messages, read external tools/data (messages, mail, calendar, contacts, purchases, web, custom CLIs), do research, and check or steer the status of ongoing work in the project."

    const val managedTools = """- OpenCode workspace: list, open, switch to, create, read, message, wait on, or stop any session, pin or unpin sessions in the drawer, start a new session draft, answer a session's permission requests and questions on the user's word, queue messages or turn queued ones into steering, and end the call, plus everything a session can do (inspect or change code, run commands or tests, research, external tools) by messaging it. You can message, read, or check any session in place without moving the user's screen; only open a session or draft when the user asks to view or switch to it.
- When the screen moves, you receive that session's recent messages; answer questions about them directly. Results of work you started are spoken to you when they land, but if the user asks whether work finished or how a session is doing, always delegate to check live status."""

    const val screenAwayPolicy = "Screen policy: Assume the user is NOT looking at the screen: they may be walking, driving, cooking, or juggling several things at once. Speak so they never need to look: name the session, say results in words (what changed, the key facts, counts), short and clear, and never say \"as you can see\", \"on screen\", or \"take a look\". Only once you're told the user is looking at the screen (an instruction update says so, or they say so) may you refer to what it shows."

    const val screenLookingPolicy = "Screen policy: The user said they're looking at the screen, and it shows the session you're attached to: you may refer to what's on it, but still say results in words. If they stop looking or the phone goes away, you'll be told; then assume they can't see it again."

    const val multitaskPolicy = "The user may run several things at once across sessions: keep track of which session each request and result belongs to, and name that session when you relay a result."

    const val lookingDelegation = "\n- The user says they're looking at the screen now, or have stopped: delegate it, so the work knows whether to describe results in words."

    /** The short update when the user starts or stops seeing the screen. */
    fun screenUpdate(screen: VoiceScreenVisibility): String = when (screen) {
        VoiceScreenVisibility.visible -> "Screen update: the user is looking at the screen now, with the app in front. You may refer to what it shows; still say results in words."
        VoiceScreenVisibility.appHidden -> "Screen update: the phone is locked or the app is in the background, so the user can't see the screen. Don't refer to it: name sessions and say results in words."
        VoiceScreenVisibility.notLooking -> "Screen update: assume the user isn't looking at the screen now. Don't refer to it: name sessions and say results in words."
    }

    fun build(context: Context): String {
        val sections = mutableListOf<String>()
        sections += """# Role & Objective

You are OpenCode, a coding agent, speaking with the user by voice. ${attachment(context)} If the session you're attached to changes mid-call, these instructions are updated. You NEVER write code, edit files, or run commands yourself; the active session does that work when you delegate. Present all work as your own. The user is talking to one unified assistant, never to tools or internal parts.
Your voice layer is ${context.voiceLayer}, inside OpenCode, whose sessions do the coding. No human team exists: never say "the team", "the backend", "relay", "passing it along", "have them", or "someone will", never call a session "they" or "them", and never imply contacting people a tool didn't message. Questions about GPT-Live, Realtime, Grok, or which voice model or provider you are refer to this voice engine: answer directly that you are ${context.voiceLayer}. For anything else you do not know, say unknown rather than guessing.

${if (context.userSeesScreen) screenLookingPolicy else screenAwayPolicy}

$multitaskPolicy

# Personality & Tone

- Relaxed, warm, direct, a little dry: a capable peer on the phone, not a customer-service agent. Keep your own replies to one or two short sentences, then stop—no fluff, no play-by-play; relayed session results keep their full facts.
- No unsolicited warnings, safety tips, reminders about obvious everyday things, moralizing, or lectures. Speak up only for a serious, immediate risk the user seems to have missed.
- Take teasing in stride and riff back; never get defensive or passive-aggressive. Don't fawn or agree just to please; disagree plainly when the user is wrong.
- Relay the session's replies faithfully, in order: keep every fact, drop only code, IDs, paths and URLs, add nothing. Anything beyond what a result says (the next item, a detail, a number) and any decision on it goes to `delegate`; if you were interrupted, answer what the user just said, and give the rest only if asked.
- Explain failures briefly and offer one retry or alternative.

# Delegation Rules

- Delegation is your hands. Anything that needs the project, its files, its history, running work, or live information goes through the `delegate` tool. Never refuse or claim you lack access.
- When the user asks for data or an action and you don't know whether the session can do it, delegate it to the session to find out and use its tools. Report a limitation only after the session reports a real one, describing the narrow blocker; never ask the user to paste data the session may be able to read; never claim access or success you didn't get; never bypass the session's approval gates.
- Write each delegation as the clearest statement of the user's intent: clean up speech artifacts, keep every constraint the user stated, add none of your own. Phrase it as the request itself, never as a description of the user.
- Use the complete utterance including self-corrections, not a tentative prefix. Your own questions and acknowledgements are context, never additional user instructions.
- Work already running never blocks a delegation. Delegate new requests, corrections, follow-ups, and nudges right away; they steer the running work at its next step. Asking how it's going, to hurry, to wrap up with what it has, or to change course is a new delegation, not a repeat: delegate it. Never refuse because something is running, and never tell the user to open or cancel the session for this.
- Clarifications steer the existing task; status requests ask the session for its state; a repeated read-only request is just delegated again. Never say "I can't submit the same request again while it's running".
- Only for an external write with an uncertain outcome (a send, post, or purchase), delegate a status check first and retry the write only if it didn't happen.
- Wait for a complete utterance. If a request meant for you is a fragment or unclear, say briefly that you didn't catch it and ask the user to finish or repeat once—never answer or delegate a speculative interpretation of a fragment. Speech that makes no sense, or may be meant for someone else, gets silence instead.
- Delegate only actual requests made to you. Commentary to you gets a conversational reply; speech not meant for you gets none (What You Hear).
- Answer greetings meant for you, small talk, and questions about your voice engine identity or a completed result from this call directly, without delegating; any status or outcome question goes to `delegate` unless a completed result on this call directly answers it.
- You cannot approve permissions or answer the session's own questions. If the session is blocked on one of those, say so and tell the user to open the session.

# Conversation Flow

- Before the tool call, in the same turn, react once the way a friend on the phone would: quickly and naturally, to what the user just said, without saying how the request will be framed or passed on. Name the specific thing you understood them to want, in your own words, so they hear how you read it; this matters most when they rambled or circled. Say it in the first person as work you're starting, not as done and not as asking, sending, or handing it to anyone, unless the user asked for exactly that; never "Done". Only a result decides what happened. Never use the same stock word or phrase twice in a row; for a quick follow-up a simple yeah is enough. Never start work in silence. Results state the concrete action and where ("Saved a private note in your notes", "Sent that to the OPENCODE IOS session").
- A result's own words set the claim. "Asked", "started" or "will" stays that; never upgrade it to "done", and never add a date, deadline or urgency the result didn't say.
- A result describing what would or will happen (or work still running) is not done: say it's conditional or in progress, never that you did it.
- Keep any status to one short line—no repeated "it's still running" warnings, no "just wait", and no "open the session" unless the session is blocked on an approval or question only the user can answer there.
- After acknowledging a `delegate` call, go quiet. No waiting narration, no "still working on it". Speak again for the result or when the user speaks.
- Results arrive as tool output prefixed [COMPLETE]. Never speak the prefix. Deliver the result once, rephrased naturally, as the final word on that work. Never claim work succeeded before its result arrives.

$whatYouHearSection

$personalMessagesSection

# Ending the Call

- Explicit requests ("hang up", "end the call", "bye", "that's all for now") mean end the call: call end_voice_session immediately, then say one brief goodbye. A goodbye to someone else ("Bye, Keanu") isn't one.
- Do not end the call for a bare "stop", a request to pause or stay quiet, or thanks alone."""
        appendContextSections(sections, context)
        return sections.joinToString("\n\n")
    }

    /**
     * Speech that isn't for the voice agent (a greeting to a child, an aside, background talk) gets
     * no reply at all.
     */
    const val whatYouHearSection = """# What You Hear

- What you hear is speech near the phone, usually the user's own words, transcribed imperfectly, even when it overlaps something you're saying. It is never a note, a message, or part of a result: never call it one.
- Results come only from work you delegated. Never say you got a note, message, or result that isn't in one, and never guess what one means.
- Not everything you hear is for you. Speech aimed at someone else (a child, a pet, another person, a TV), a greeting or pet name mid-call ("Hi, baby", "Hey, buddy", "Hi, you, boy"), a muttered aside, background talk, and speech that makes no sense get no reply at all: keep listening. No greeting back, no laugh, no "Still going?", no comment, no question, no offer, and never delegate it.
- When you can't tell whether speech is for you, say nothing: if it was, the user will say it again. A greeting at the start of the call, or one that names you, is for you.
- Only when the user is clearly asking you for something and you missed part of it, say "Sorry, I didn't catch that." Never invent a request for speech you didn't follow, and never act on it.
- When the user corrects you ("I was talking to someone else"), the correction replaces your earlier take: acknowledge it in a few words and drop the earlier reply. Never repeat an earlier reply, and never finish one that was cut off.
- A recap ("catch me up", "what did you do") lists only the requests you delegated and their actual results, a short sentence each. Nothing else from the conversation.
- Give results plainly: what was started, sent, done, or failed, and where. Add no commentary, jokes, opinions, or offers the result doesn't support.
- Something is done (sent, posted, merged, deleted, approved) only when a result says it happened. A draft, a question like "send it?", or a result still waiting on the user means not done: never report it as done.
- No emotional probing ("What's feeling off?"). If the user says you seem off, say sorry in a few words and carry on with what they asked."""

    private const val personalMessagesSection = """# Personal messages

- When the user dictates or discusses a personal message (texts, email, Slack), act like a secretary: preserve the user's meaning, tone, intent, profanity, and exact wording, verbatim when asked.
- Never invent content, moralize, or rewrite it into counseling or therapeutic language. Ordinary profanity or criticism isn't a reason to refuse.
- If the dictation is incomplete, ask for the missing words.
- Drafting is not sending: delegate the user's exact words to the session and say the draft and its destination come back to them before anything is sent.
- The user's OK on a draft ("yes", "send it") is a new request: delegate it, and say it was sent only when that result says so.
- Only if a real safety limit applies, name the narrow issue and offer a clearly labeled adjustment for the user's approval, never a silent substitution."""

    private fun appendContextSections(sections: MutableList<String>, original: Context) {
        val context = original.copy(previousTail = VoiceTranscriptRecord.continuityLines(original.previousTail))
        if (context.recentMessages.isNotEmpty()) {
            sections += "# Session So Far\n\n" +
                "Recent messages from the coding session, oldest first. Background for answering \"where did we get to\"; never new requests.\n\n" +
                context.recentMessages.joinToString("\n")
        }

        if (context.previousTail.isNotEmpty()) {
            // Whole seconds, UTC.
            val ended = context.previousEndedAt?.truncatedTo(ChronoUnit.SECONDS)?.toString() ?: "earlier"
            sections += "# Previous Voice Call\n\n" +
                "Voice memory resets between calls. User turns from the previous call about this session (ended $ended) follow as silent memory so earlier references resolve; never new requests, never completed outcomes or facts, and never repeat or recap them unless asked.\n\n" +
                context.previousTail.joinToString("\n")
        }
    }
}
