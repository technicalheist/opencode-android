package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.moveToBackgroundBeforeSteer
import ai.opencode.server.retry.RetryBudget
import ai.opencode.server.sendPromptConfirming
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Steers GPT-Live client delegations directly into a real OpenCode session on the connected server
 * (`voiceSessionDelegator` experiment), tracking watermarked `[Voice delta]` turns, live tool
 * captions, and `ios_voice_*` phone-side tool effects. The `ios_voice_*` names are the server
 * plugin's, so they stay.
 */
object VoiceSessionDelegator {
    const val voiceSessionTitle = "Voice"

    val activeCallBlock = """
        [Voice call active]
        The user is on a live voice call on their phone, and your reply is read aloud. Keep it short by default, a few spoken sentences. When they ask for detail, an explanation, a walkthrough, or to be taught something, answer at whatever length it needs; it is read in full.
        Either way, write plain spoken prose with no Markdown, lists, code blocks, or raw IDs. Put very long material (full code, logs, long reports) in a file and speak a summary of it.
        When walking through a list one item at a time, end with a single line naming the next item ("Next is X; keep, drop, or note?").
        Assume the user isn't looking at the screen (they may be walking, driving, or juggling several things) unless the [Phone context] line says "Viewing" or they say this call that they're looking: name sessions and describe results in words (what changed, the key lines, counts), never "as you can see". They may have several things running at once: say which session each result belongs to.
    """.trimIndent()

    data class State(
        val turns: List<Turn> = emptyList(),
        val activeSessionID: String? = null,
        val provisionalSessionIDs: Set<String> = emptySet(),
        val lastContextBySession: Map<String, String> = emptyMap(),
        val deletedSessionIDs: Set<String> = emptySet(),
        val creatingViewedDraft: Boolean = false,
        val toolNames: Map<String, String> = emptyMap(),
        val runningTools: List<RunningTool> = emptyList(),
        val appliedToolCallIDs: Set<String> = emptySet(),
    ) {
        data class Turn(
            val callID: String,
            val capture: VoiceTranscriptTail.Capture,
            val promisedClaim: String? = null,
            val sessionID: String?,
            val provisionalSessionID: String? = null,
            val createsViewedDraft: Boolean = false,
            val reportedCreatedAutomatic: Boolean = false,
            val title: String = voiceSessionTitle,
            val promptID: String? = null,
            val admitting: Boolean = false,
            val saidTurn: Int,
            val superseded: Boolean = false,
            val didSayInterim: Boolean = false,
            val reconnectedMidTurn: Boolean = false,
        )

        data class RunningTool(
            val id: String,
            val name: String,
            val label: String?,
            /** The delegator session it runs in. */
            val sessionID: String = "",
        )

        val hasActiveTurns: Boolean get() = turns.isNotEmpty()
        val inFlightCallIDs: List<String> get() = turns.map { it.callID }

        val progressCaption: String? get() = runningTools.asReversed().firstNotNullOfOrNull { it.label }

        fun tracksTools(sessionID: String): Boolean =
            activeSessionID == sessionID ||
                sessionID in provisionalSessionIDs ||
                turns.any { it.sessionID == sessionID }

        /** The newest running tool's caption in one session. */
        fun progressCaption(sessionID: String): String? =
            runningTools.asReversed().filter { it.sessionID == sessionID }.firstNotNullOfOrNull { it.label }

        fun clearRunningTools(): State = copy(runningTools = emptyList(), toolNames = emptyMap())

        /**
         * The delegation a session's tools work for: its admitted turn that
         * will speak (a superseded one never does).
         */
        fun progressCallID(sessionID: String): String? =
            turns.lastOrNull { it.sessionID == sessionID && it.promptID != null && !it.superseded }?.callID

        /** One session's turns settled or it went away: only its tools stop. */
        fun clearRunningTools(sessionID: String): State {
            val ids = runningTools.filter { it.sessionID == sessionID }.map { it.id }.toSet()
            return copy(
                runningTools = runningTools.filterNot { it.sessionID == sessionID },
                toolNames = toolNames - ids,
            )
        }

        fun resetForCallStart(): State = State()

        /** The reset state and the captures of turns never admitted, newest first. */
        fun resetForCallEnd(): Pair<State, List<VoiceTranscriptTail.Capture>> {
            val unreleased = turns.filter { it.promptID == null }.asReversed().map { it.capture }
            return copy(
                turns = emptyList(),
                activeSessionID = null,
                provisionalSessionIDs = emptySet(),
                creatingViewedDraft = false,
                toolNames = emptyMap(),
                runningTools = emptyList(),
            ) to unreleased
        }
    }

    /** Phone-side effects triggered immediately from `ios_voice_*` tool inputs. */
    sealed interface PhoneToolEffect {
        data class OpenSession(val sessionID: String, val toolID: String) : PhoneToolEffect
        data class PinSession(val sessionID: String, val pinned: Boolean) : PhoneToolEffect
        data class WatchSession(val toolID: String, val sessionID: String, val note: String?) : PhoneToolEffect
        data class Say(val text: String) : PhoneToolEffect
        data object EndCall : PhoneToolEffect
    }

    private fun shortName(name: String): String =
        name.split('.', '/').lastOrNull { it.isNotEmpty() } ?: name

    /**
     * Matches top-level `ios_voice_*` tools from `state.input` so effects apply as soon as the
     * tool is called and degrade cleanly without the plugin.
     */
    fun parsePhoneToolEffect(toolID: String, name: String, input: OpenCodeJSONValue): PhoneToolEffect? {
        val shortName = shortName(name)
        val obj = input.obj ?: return null
        fun trimmedString(key: String): String? = obj[key]?.string?.trim()?.takeIf { it.isNotEmpty() }
        return when (shortName) {
            "ios_voice_open_session" -> trimmedString("session_id")?.let { PhoneToolEffect.OpenSession(sessionID = it, toolID = toolID) }
            "ios_voice_pin_session" -> {
                val sessionID = trimmedString("session_id") ?: return null
                val pinned = (obj["pinned"] as? OpenCodeJSONValue.Bool)?.value ?: return null
                PhoneToolEffect.PinSession(sessionID, pinned)
            }
            "ios_voice_watch_session" -> trimmedString("session_id")?.let {
                PhoneToolEffect.WatchSession(toolID = toolID, sessionID = it, note = trimmedString("note"))
            }
            "ios_voice_say" -> trimmedString("text")?.let { PhoneToolEffect.Say(it) }
            "ios_voice_end_call" -> PhoneToolEffect.EndCall
            else -> null
        }
    }

    /** One-line `[Phone context]` header describing what's on the phone's screen. */
    fun formatContextLine(
        viewing: OpenCodeSession?,
        needsInput: Boolean,
        draftDirectory: String,
        screen: VoiceScreenVisibility = VoiceScreenVisibility.notLooking,
    ): String {
        val lead = if (screen == VoiceScreenVisibility.visible) "Viewing" else "Call on"
        val line = if (viewing != null) {
            val title = VoiceFeature.State.usableTitle(viewing.title) ?: "New session"
            val suffix = if (needsInput) " · needs input" else ""
            "[Phone context] $lead session \"$title\" (${viewing.id})$suffix"
        } else {
            "[Phone context] $lead new session draft in ${VoiceDelegatorWorkspace.folder(draftDirectory)}"
        }
        val unseen = screen.unseenNote ?: return line
        return "$line · $unseen"
    }

    /** Maps [VoiceTranscriptTail] capture lines into `[Voice delta]` speaker lines. */
    fun formatDeltaLines(tailLines: List<String>): List<String> = tailLines.mapNotNull { raw ->
        val line = raw.trim()
        when {
            line.isEmpty() -> null
            line == VoiceTranscriptTail.omittedNote -> line
            line.startsWith("user (updated utterance): ") ->
                "User (updated utterance): " + line.removePrefix("user (updated utterance): ")
            line.startsWith("user: ") -> "User: " + line.removePrefix("user: ")
            line.startsWith("assistant (updated utterance): ") ->
                "Voice (updated utterance): " + line.removePrefix("assistant (updated utterance): ")
            line.startsWith("assistant: ") -> "Voice: " + line.removePrefix("assistant: ")
            else -> "User: $line"
        }
    }

    /** Builds the steer prompt sent to the delegator OpenCode session. */
    fun formatPrompt(
        deltaLines: List<String>,
        promisedClaim: String? = null,
        contextLine: String?,
        includeActiveCallHeader: Boolean,
    ): String {
        val sections = mutableListOf<String>()
        if (includeActiveCallHeader) sections += activeCallBlock
        if (!contextLine.isNullOrEmpty()) sections += contextLine
        val outcomeCheck = promisedClaim?.trim()
        if (outcomeCheck != null && outcomeCheck.startsWith("[Outcome check]")) {
            sections += outcomeCheck
            return sections.joinToString("\n\n")
        }
        val delta = mutableListOf("[Voice delta]")
        for (line in deltaLines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) delta += trimmed
        }
        val claim = promisedClaim?.trim()
        if (!claim.isNullOrEmpty()) delta += "Voice promised: \"$claim\""
        sections += delta.joinToString("\n")
        return sections.joinToString("\n\n")
    }

    /** Framing for an unbound `ios_voice_say` interim spoken update. */
    fun interimSpeechInstruction(raw: String): String =
        "Say this brief status update aloud to the user now while the session keeps working: ${spokenReply(raw)}"

    /** Cleans an assistant reply for spoken commentary: collapses fenced code blocks and strips Markdown syntax. */
    fun spokenReply(raw: String): String = stripMarkdown(LiveContentChunker.collapseCodeBlocks(raw))

    /** Tab and the space separators, not newlines. */
    private fun isBlank(c: Char): Boolean = c == '\t' || Character.getType(c) == Character.SPACE_SEPARATOR.toInt()

    /**
     * Strips Markdown formatting from spoken prose while preserving plain words, numbers, and
     * identifiers such as `foo_bar`.
     */
    fun stripMarkdown(text: String): String {
        val lines = text.split("\n").map { rawLine ->
            var line = stripLinePrefix(rawLine)
            line = unwrapLinksAndImages(line)
            line = unwrapInlineCode(line)
            line = unwrapPairedMarker(line, "**")
            line = unwrapPairedMarker(line, "__")
            line = unwrapPairedMarker(line, "~~")
            line = unwrapSingleMarker(line, '*')
            line = unwrapBoundaryUnderscores(line)
            line.trim(::isBlank)
        }
        val collapsed = mutableListOf<String>()
        var previousBlank = false
        for (line in lines) {
            if (line.isEmpty()) {
                if (!previousBlank && collapsed.isNotEmpty()) collapsed += ""
                previousBlank = true
            } else {
                collapsed += line
                previousBlank = false
            }
        }
        while (collapsed.lastOrNull()?.isEmpty() == true) collapsed.removeAt(collapsed.lastIndex)
        return collapsed.joinToString("\n")
    }

    /** Human-readable progress caption for a running delegator session tool. */
    fun toolCaption(name: String, input: OpenCodeJSONValue?): String? {
        val short = shortName(name)
        val args = input?.obj
        fun fileName(key: String): String? {
            val raw = args?.get(key)?.string?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val last = VoiceFeature.State.lastPathComponent(raw)
            return if (last.isEmpty() || last == "/") null else last
        }
        return when (short) {
            "ios_voice_say" -> null
            "ios_voice_open_session" -> "Opening session"
            "ios_voice_pin_session" ->
                if (args?.get("pinned") == OpenCodeJSONValue.Bool(false)) "Unpinning session" else "Pinning session"
            "ios_voice_watch_session" -> "Watching session"
            "ios_voice_end_call" -> "Ending call"
            "read" -> fileName("path")?.let { "Reading $it" } ?: "Reading file"
            "edit" -> fileName("path")?.let { "Editing $it" } ?: "Editing file"
            "write" -> fileName("path")?.let { "Writing $it" } ?: "Writing file"
            "grep" -> "Searching files"
            "glob" -> "Finding files"
            "shell", "bash" -> "Running shell"
            "subagent", "task" -> {
                val raw = args?.get("description")?.string?.trim()?.takeIf { it.isNotEmpty() }
                if (raw != null) {
                    val clipped = if (raw.length > 36) raw.take(36).trim(::isBlank) + "…" else raw
                    "Starting subagent · $clipped"
                } else {
                    "Starting subagent"
                }
            }
            "websearch" -> "Searching the web"
            "webfetch" -> "Reading web page"
            "skill" -> "Loading skill"
            "execute" -> {
                val code = args?.get("code")?.string.orEmpty()
                when {
                    "tools.seer" in code -> "Checking Seer"
                    "tools.wisp" in code -> "Using Wisp"
                    "tools.slack" in code -> "Checking Slack"
                    "tools.browser" in code -> "Using browser"
                    "tools.rooms" in code -> "Checking Rooms"
                    "tools.opencode" in code -> "Managing OpenCode"
                    else -> "Running script"
                }
            }
            else -> {
                val words = short.replace("_", " ").replace("-", " ").trim()
                if (words.isEmpty()) "Working" else "Running $words"
            }
        }
    }

    private fun stripLinePrefix(line: String): String {
        var rest = line.trimStart(' ', '\t')
        if (rest.startsWith(">")) {
            rest = rest.substring(1)
            if (rest.startsWith(" ")) rest = rest.substring(1)
        }
        var hashes = 0
        var probe = rest
        while (probe.startsWith("#") && hashes < 6) {
            hashes += 1
            probe = probe.substring(1)
        }
        if (hashes > 0 && probe.startsWith(" ")) {
            rest = probe.substring(1)
        } else if (rest.isNotEmpty() && (rest[0] == '-' || rest[0] == '*' || rest[0] == '•') && rest.getOrNull(1) == ' ') {
            rest = rest.substring(2)
            if (rest.startsWith("[ ] ") || rest.startsWith("[x] ") || rest.startsWith("[X] ")) rest = rest.substring(4)
        }
        return rest
    }

    private fun unwrapLinksAndImages(text: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            val isImage = text[index] == '!' && index + 1 < text.length && text[index + 1] == '['
            if (isImage || text[index] == '[') {
                val openBracket = if (isImage) index + 1 else index
                val labelStart = openBracket + 1
                val closeBracket = text.indexOf(']', labelStart)
                if (closeBracket >= 0) {
                    val afterBracket = closeBracket + 1
                    if (afterBracket < text.length && text[afterBracket] == '(') {
                        val closeParen = text.indexOf(')', afterBracket + 1)
                        if (closeParen >= 0) {
                            out.append(text, labelStart, closeBracket)
                            index = closeParen + 1
                            continue
                        }
                    }
                }
            }
            out.append(text[index])
            index += 1
        }
        return out.toString()
    }

    private fun unwrapInlineCode(text: String): String {
        if ('`' !in text) return text
        val out = StringBuilder()
        var remainder = 0
        while (true) {
            val open = text.indexOf('`', remainder)
            if (open < 0) break
            out.append(text, remainder, open)
            val afterOpen = open + 1
            val close = text.indexOf('`', afterOpen)
            if (close >= 0) {
                out.append(text, afterOpen, close)
                remainder = close + 1
            } else {
                out.append('`')
                remainder = afterOpen
            }
        }
        out.append(text, remainder, text.length)
        return out.toString()
    }

    private fun unwrapPairedMarker(text: String, marker: String): String {
        if (marker !in text) return text
        val out = StringBuilder()
        var remainder = 0
        while (true) {
            val open = text.indexOf(marker, remainder)
            if (open < 0) break
            out.append(text, remainder, open)
            val afterOpen = open + marker.length
            val close = text.indexOf(marker, afterOpen)
            if (close > afterOpen) {
                out.append(text, afterOpen, close)
                remainder = close + marker.length
            } else {
                out.append(marker)
                remainder = afterOpen
            }
        }
        out.append(text, remainder, text.length)
        return out.toString()
    }

    private fun unwrapSingleMarker(text: String, marker: Char): String {
        if (marker !in text) return text
        val out = StringBuilder()
        var remainder = 0
        while (true) {
            val open = text.indexOf(marker, remainder)
            if (open < 0) break
            out.append(text, remainder, open)
            val afterOpen = open + 1
            if (text.getOrNull(afterOpen) != ' ') {
                val close = text.indexOf(marker, afterOpen)
                if (close > afterOpen && text[close - 1] != ' ') {
                    out.append(text, afterOpen, close)
                    remainder = close + 1
                    continue
                }
            }
            out.append(marker)
            remainder = afterOpen
        }
        out.append(text, remainder, text.length)
        return out.toString()
    }

    private fun isWord(c: Char): Boolean {
        if (c.isLetter() || c.isDigit()) return true
        val type = Character.getType(c)
        return type == Character.LETTER_NUMBER.toInt() || type == Character.OTHER_NUMBER.toInt()
    }

    private fun unwrapBoundaryUnderscores(text: String): String {
        if ('_' !in text) return text
        val chars = text
        val out = StringBuilder()
        var i = 0
        while (i < chars.length) {
            if (chars[i] == '_' && (i == 0 || !isWord(chars[i - 1])) && i + 1 < chars.length && chars[i + 1] != ' ') {
                var close = i + 2
                while (close < chars.length) {
                    if (chars[close] == '_' && chars[close - 1] != ' ' &&
                        (close + 1 == chars.length || !isWord(chars[close + 1]))
                    ) {
                        break
                    }
                    close += 1
                }
                if (close < chars.length) {
                    out.append(chars, i + 1, close)
                    i = close + 1
                    continue
                }
            }
            out.append(chars[i])
            i += 1
        }
        return out.toString()
    }

    data class Submission(
        val server: OpenCodeServer,
        val choice: VoiceDelegatorChoice,
        val viewing: OpenCodeSession?,
        val draftDirectory: String,
        val draftModel: OpenCodeModelRef?,
        val draftSessionID: String?,
        val preferredVoiceModel: OpenCodeModelRef?,
        val cachedAutomaticSessionID: String?,
        val provisionalAutomaticSessionID: String?,
        val messageID: String,
        val deltaLines: List<String>,
        val promisedClaim: String?,
        val contextLine: String,
        val lastContextBySession: Map<String, String>,
        val explainedSessionIDs: Set<String>,
        val deletedSessionIDs: Set<String>,
        val onAutomaticSessionResolved: (suspend (serverScopeKey: String, sessionID: String, created: OpenCodeSession?) -> Unit)? = null,
    )

    data class Admission(
        val sessionID: String,
        val title: String,
        val promptID: String,
        val promptText: String,
        val explained: Boolean,
        val contextLine: String,
        val createdViewedDraft: OpenCodeSession? = null,
        val createdAutomaticVoice: OpenCodeSession? = null,
        val cachedAutomaticSessionID: String? = null,
        val clearedStaleAutomaticCache: Boolean = false,
        val appliedDraftModel: OpenCodeModelRef? = null,
    )

    data class ResolvedVoiceSession(val sessionID: String, val created: OpenCodeSession?)

    class Client(
        val submit: suspend (submission: Submission) -> Admission,
    ) {
        companion object Key : DependencyKey<Client> {
            override val liveValue: Client by lazy { live() }

            /** The live submit, which reaches only test dependencies under tests. */
            override val testValue: Client get() = live()

            fun live(): Client {
                val gate = VoiceSessionCreationGate()
                return Client(submit = { submission -> submit(submission, gate) })
            }
        }
    }

    private suspend fun ensureNotCancelled() {
        if (!currentCoroutineContext().isActive) throw OpenCodeClientError(message = "Cancelled")
    }

    private fun pickedGone(picked: VoiceDelegatorChoice.Picked) =
        "The picked delegator session “${picked.modelTitle}” was deleted. Pick another session or switch Delegator to Automatic in Voice settings."

    private suspend fun submit(submission: Submission, gate: VoiceSessionCreationGate): Admission {
        val client = dependency(OpenCodeClient)
        val serverID = dependency(ServerIDClient)
        val budget = RetryBudget(VoiceDelegatorWorkspace.Latency.standard.total)
        val server = submission.server

        fun makePrompt(sessionID: String): Pair<String, Boolean> {
            val includeHeader = sessionID !in submission.explainedSessionIDs
            val includeContext = submission.lastContextBySession[sessionID] != submission.contextLine
            val text = formatPrompt(
                deltaLines = submission.deltaLines,
                promisedClaim = submission.promisedClaim,
                contextLine = if (includeContext) submission.contextLine else null,
                includeActiveCallHeader = includeHeader,
            )
            return text to includeHeader
        }

        suspend fun steer(sessionID: String, text: String, messageID: String = submission.messageID): String =
            budget.required {
                client.sendPromptConfirming(server, sessionID, text, OpenCodeDelivery.Steer, messageID = messageID)
            }

        when (val choice = submission.choice) {
            VoiceDelegatorChoice.BuiltIn -> throw OpenCodeClientError(message = "Couldn’t send to the delegator session.")

            is VoiceDelegatorChoice.PickedSession -> {
                val picked = choice.picked
                if (picked.serverID != VoiceDelegatorWorkspace.serverID(server)) {
                    throw OpenCodeClientError(
                        message = "The picked delegator session “${picked.modelTitle}” belongs to another server. Pick a session on this server or switch Delegator to Automatic in Voice settings.",
                    )
                }
                if (picked.sessionID in submission.deletedSessionIDs) throw OpenCodeClientError(message = pickedGone(picked))
                val (text, explained) = makePrompt(picked.sessionID)
                client.moveToBackgroundBeforeSteer(server, picked.sessionID)
                val promptID = try {
                    steer(picked.sessionID, text)
                } catch (error: OpenCodeClientError) {
                    if (error.statusCode == 404) throw OpenCodeClientError(message = pickedGone(picked), statusCode = 404)
                    throw error
                }
                return Admission(
                    sessionID = picked.sessionID,
                    title = picked.modelTitle,
                    promptID = promptID,
                    promptText = text,
                    explained = explained,
                    contextLine = submission.contextLine,
                )
            }

            VoiceDelegatorChoice.ViewedSession -> {
                val viewing = submission.viewing
                if (viewing != null) {
                    if (viewing.id in submission.deletedSessionIDs) {
                        throw OpenCodeClientError(message = "That session was deleted.", statusCode = 404)
                    }
                    val (text, explained) = makePrompt(viewing.id)
                    client.moveToBackgroundBeforeSteer(server, viewing.id)
                    val promptID = try {
                        steer(viewing.id, text)
                    } catch (error: OpenCodeClientError) {
                        if (error.statusCode == 404) throw OpenCodeClientError(message = "That session was deleted.", statusCode = 404)
                        throw error
                    }
                    return Admission(
                        sessionID = viewing.id,
                        title = VoiceFeature.State.usableTitle(viewing.title) ?: "New session",
                        promptID = promptID,
                        promptText = text,
                        explained = explained,
                        contextLine = submission.contextLine,
                    )
                }
                val newID = submission.draftSessionID ?: serverID.sessionID()
                var created = budget.required { client.createSession(server, submission.draftDirectory, newID) }
                var appliedModel: OpenCodeModelRef? = null
                val model = submission.draftModel
                if (model != null) {
                    val createdID = created.id
                    budget.required { client.switchModel(server, createdID, model) }
                    created = created.copy(model = model)
                    appliedModel = model
                }
                val (text, explained) = makePrompt(created.id)
                val promptID = steer(created.id, text)
                return Admission(
                    sessionID = created.id,
                    title = VoiceFeature.State.usableTitle(created.title) ?: "New session",
                    promptID = promptID,
                    promptText = text,
                    explained = explained,
                    contextLine = submission.contextLine,
                    createdViewedDraft = created,
                    appliedDraftModel = appliedModel,
                )
            }

            VoiceDelegatorChoice.AutomaticSession -> {
                val serverKey = VoiceDelegatorWorkspace.serverID(server)
                val optionalBudget = VoiceDelegatorWorkspace.Latency.standard.optional
                for (deletedID in submission.deletedSessionIDs) gate.invalidate(serverKey, deletedID)

                suspend fun finishPendingSetupIfNeeded(sessionID: String) {
                    val pending = gate.pendingSetup(serverKey, sessionID) ?: return
                    ensureNotCancelled()
                    if (!pending.renamed) {
                        val didRename = budget.optional(optionalBudget) {
                            client.renameSession(server, sessionID, voiceSessionTitle)
                            true
                        }
                        ensureNotCancelled()
                        if (didRename == true) gate.markRenamed(serverKey, sessionID)
                    }
                    if (!pending.modelConfigured) {
                        val initialModel = submission.preferredVoiceModel ?: run {
                            val catalog = budget.optional(optionalBudget) { client.modelCatalog(server, null) }
                            ensureNotCancelled()
                            catalog?.defaultModel?.ref
                        }
                        if (initialModel != null) {
                            val switchedModel = budget.optional(optionalBudget) {
                                client.switchModel(server, sessionID, initialModel)
                                initialModel
                            }
                            ensureNotCancelled()
                            if (switchedModel != null) gate.markModelConfigured(serverKey, sessionID, switchedModel)
                        } else {
                            gate.markModelConfigured(serverKey, sessionID, null)
                        }
                    }
                }

                var clearedStale = false
                val cachedID = submission.cachedAutomaticSessionID
                if (cachedID != null && cachedID !in submission.deletedSessionIDs) {
                    finishPendingSetupIfNeeded(cachedID)
                    ensureNotCancelled()
                    val (text, explained) = makePrompt(cachedID)
                    client.moveToBackgroundBeforeSteer(server, cachedID)
                    ensureNotCancelled()
                    try {
                        val promptID = steer(cachedID, text)
                        return Admission(
                            sessionID = cachedID,
                            title = voiceSessionTitle,
                            promptID = promptID,
                            promptText = text,
                            explained = explained,
                            contextLine = submission.contextLine,
                            cachedAutomaticSessionID = cachedID,
                        )
                    } catch (error: OpenCodeClientError) {
                        if (error.statusCode == 404) {
                            gate.invalidate(serverKey, cachedID)
                            clearedStale = true
                        } else {
                            throw error
                        }
                    }
                }

                val staleCachedID = if (clearedStale) submission.cachedAutomaticSessionID else null
                val excludedSnapshot = submission.deletedSessionIDs + listOfNotNull(staleCachedID)

                val resolved = gate.resolve(serverKey) {
                    val remembered = gate.cachedSession(serverKey, excludedSnapshot)
                    if (remembered != null) {
                        val (session, isNewlyCreated) = remembered
                        submission.onAutomaticSessionResolved?.invoke(serverKey, session.id, if (isNewlyCreated) session else null)
                        finishPendingSetupIfNeeded(session.id)
                        val updated = gate.updatedSession(serverKey, session)
                        return@resolve ResolvedVoiceSession(updated.id, if (isNewlyCreated) updated else null)
                    }
                    ensureNotCancelled()
                    val matches = budget.optional(optionalBudget) {
                        client.searchSessions(server, voiceSessionTitle, 20)
                    } ?: emptyList()
                    ensureNotCancelled()
                    val existing = matches.firstOrNull { session ->
                        session.parentID == null &&
                            !session.isArchived &&
                            session.id !in excludedSnapshot &&
                            session.title.trim() == voiceSessionTitle
                    }
                    if (existing != null) {
                        gate.recordExisting(serverKey, existing)
                        submission.onAutomaticSessionResolved?.invoke(serverKey, existing.id, null)
                        return@resolve ResolvedVoiceSession(existing.id, null)
                    }
                    val listing = budget.optional(optionalBudget) { client.listDirectory(server, null) }
                    ensureNotCancelled()
                    val stableDirectory = listing?.directory ?: submission.draftDirectory
                    val provisionalID = submission.provisionalAutomaticSessionID ?: serverID.sessionID()
                    val created = budget.required { client.createSession(server, stableDirectory, provisionalID) }
                        .copy(serverTitle = voiceSessionTitle)
                    gate.recordCreated(serverKey, created)
                    submission.onAutomaticSessionResolved?.invoke(serverKey, created.id, created)
                    ensureNotCancelled()
                    finishPendingSetupIfNeeded(created.id)
                    val updated = gate.updatedSession(serverKey, created)
                    gate.markCreationDelivered(serverKey, updated.id)
                    ResolvedVoiceSession(updated.id, updated)
                }

                ensureNotCancelled()
                if (resolved.created != null) gate.markCreationDelivered(serverKey, resolved.sessionID)
                val messageID = if (clearedStale) serverID.messageID() else submission.messageID
                val (text, explained) = makePrompt(resolved.sessionID)
                if (resolved.created == null) {
                    client.moveToBackgroundBeforeSteer(server, resolved.sessionID)
                    ensureNotCancelled()
                }
                val promptID = steer(resolved.sessionID, text, messageID)
                return Admission(
                    sessionID = resolved.sessionID,
                    title = voiceSessionTitle,
                    promptID = promptID,
                    promptText = text,
                    explained = explained,
                    contextLine = submission.contextLine,
                    createdAutomaticVoice = resolved.created,
                    cachedAutomaticSessionID = resolved.sessionID,
                    clearedStaleAutomaticCache = clearedStale,
                )
            }
        }
    }
}

/**
 * One Automatic `"Voice"` session lookup per server at a time: concurrent turns join the in-flight
 * find-or-create, and the lookup is cancelled only once every waiter has left. Remembers the
 * resolved session and its unfinished setup (rename, model) so a later turn finishes it.
 */
private class VoiceSessionCreationGate {
    data class PendingSetup(val session: OpenCodeSession, val renamed: Boolean, val modelConfigured: Boolean)

    private class InFlight(
        val id: UUID = UUID.randomUUID(),
        val outcome: CompletableDeferred<Result<VoiceSessionDelegator.ResolvedVoiceSession>> = CompletableDeferred(),
        var job: Job? = null,
        var waiters: Int = 0,
    )

    private data class CachedEntry(val session: OpenCodeSession, val isNewlyCreated: Boolean)

    private val lock = Any()
    private val inFlight = HashMap<String, InFlight>()
    private val cachedByServer = HashMap<String, CachedEntry>()
    private val pendingSetupByServer = HashMap<String, PendingSetup>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun cachedSession(serverKey: String, excluding: Set<String>): Pair<OpenCodeSession, Boolean>? = synchronized(lock) {
        val entry = cachedByServer[serverKey] ?: return null
        if (entry.session.id in excluding) return null
        entry.session to entry.isNewlyCreated
    }

    fun recordExisting(serverKey: String, session: OpenCodeSession) = synchronized(lock) {
        cachedByServer[serverKey] = CachedEntry(session, isNewlyCreated = false)
        if (pendingSetupByServer[serverKey]?.session?.id == session.id) pendingSetupByServer.remove(serverKey)
    }

    fun recordCreated(serverKey: String, session: OpenCodeSession) = synchronized(lock) {
        cachedByServer[serverKey] = CachedEntry(session, isNewlyCreated = true)
        pendingSetupByServer[serverKey] = PendingSetup(session, renamed = false, modelConfigured = false)
    }

    fun markCreationDelivered(serverKey: String, sessionID: String) = synchronized(lock) {
        val entry = cachedByServer[serverKey]
        if (entry != null && entry.session.id == sessionID) cachedByServer[serverKey] = entry.copy(isNewlyCreated = false)
    }

    fun pendingSetup(serverKey: String, sessionID: String): PendingSetup? = synchronized(lock) {
        pendingSetupByServer[serverKey]?.takeIf { it.session.id == sessionID }
    }

    private fun updatePending(serverKey: String, sessionID: String, change: (PendingSetup) -> PendingSetup): Unit = synchronized(lock) {
        val pending = pendingSetupByServer[serverKey]?.takeIf { it.session.id == sessionID } ?: return@synchronized
        val next = change(pending)
        val isNew = cachedByServer[serverKey]?.isNewlyCreated ?: true
        cachedByServer[serverKey] = CachedEntry(next.session, isNew)
        if (next.renamed && next.modelConfigured) {
            pendingSetupByServer.remove(serverKey)
        } else {
            pendingSetupByServer[serverKey] = next
        }
    }

    fun markRenamed(serverKey: String, sessionID: String) = updatePending(serverKey, sessionID) {
        it.copy(renamed = true, session = it.session.copy(serverTitle = VoiceSessionDelegator.voiceSessionTitle))
    }

    fun markModelConfigured(serverKey: String, sessionID: String, model: OpenCodeModelRef?) =
        updatePending(serverKey, sessionID) {
            it.copy(modelConfigured = true, session = if (model != null) it.session.copy(model = model) else it.session)
        }

    fun updatedSession(serverKey: String, fallback: OpenCodeSession): OpenCodeSession = synchronized(lock) {
        cachedByServer[serverKey]?.takeIf { it.session.id == fallback.id }?.session ?: fallback
    }

    fun invalidate(serverKey: String, sessionID: String) = synchronized(lock) {
        if (cachedByServer[serverKey]?.session?.id == sessionID) cachedByServer.remove(serverKey)
        if (pendingSetupByServer[serverKey]?.session?.id == sessionID) pendingSetupByServer.remove(serverKey)
    }

    suspend fun resolve(
        serverKey: String,
        operation: suspend () -> VoiceSessionDelegator.ResolvedVoiceSession,
    ): VoiceSessionDelegator.ResolvedVoiceSession {
        if (!currentCoroutineContext().isActive) throw OpenCodeClientError(message = "Cancelled")
        val context = currentCoroutineContext().minusKey(Job)
        val (entry, starts) = synchronized(lock) {
            val existing = inFlight[serverKey]
            if (existing != null) {
                existing.waiters += 1
                existing to false
            } else {
                val entry = InFlight(waiters = 1)
                inFlight[serverKey] = entry
                entry to true
            }
        }
        if (starts) {
            // An unstructured job: it keeps the caller's dependencies and retry policy.
            val job = scope.launch(context, start = CoroutineStart.LAZY) {
                val outcome = try {
                    Result.success(operation())
                } catch (error: CancellationException) {
                    entry.outcome.complete(Result.failure(OpenCodeClientError(message = "Cancelled")))
                    throw error
                } catch (error: Throwable) {
                    Result.failure(error)
                }
                complete(serverKey, entry, outcome)
            }
            val cancelled = synchronized(lock) {
                if (inFlight[serverKey] !== entry) {
                    true
                } else {
                    entry.job = job
                    false
                }
            }
            if (cancelled) job.cancel() else job.start()
        }
        try {
            return entry.outcome.await().getOrThrow()
        } catch (error: CancellationException) {
            leave(serverKey, entry)
            throw error
        }
    }

    private fun leave(serverKey: String, entry: InFlight) {
        val job = synchronized(lock) {
            if (inFlight[serverKey] !== entry) return
            entry.waiters -= 1
            if (entry.waiters > 0) return
            inFlight.remove(serverKey)
            entry.job
        }
        job?.cancel()
    }

    private fun complete(serverKey: String, entry: InFlight, outcome: Result<VoiceSessionDelegator.ResolvedVoiceSession>) {
        synchronized(lock) {
            if (inFlight[serverKey] === entry) inFlight.remove(serverKey)
        }
        entry.outcome.complete(outcome)
    }
}
