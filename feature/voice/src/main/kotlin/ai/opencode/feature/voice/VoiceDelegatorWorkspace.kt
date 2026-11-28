package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.completeHomeSnapshot
import ai.opencode.server.friendlyName
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.moveToBackgroundBeforeSteer
import ai.opencode.server.retry.RetryBudget
import ai.opencode.server.sendPromptConfirming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The delegator's tools for one request, acting on OpenCode. Holds where the
 * request thinks the user is (it moves with `open_session`) and the prompts
 * it sent, so their replies can be spoken later.
 *
 * Every entry point runs on one confined dispatcher, so state is touched by one coroutine at a time
 * and other calls interleave only at suspension points.
 */
class VoiceDelegatorWorkspace(
    private val client: OpenCodeClient,
    private val start: Start,
    private val effects: Effects,
) {
    /** What a request starts with, copied from the call. */
    data class Start(
        val server: OpenCodeServer,
        val viewing: OpenCodeSession?,
        /** The draft's folder and model while no session is open. */
        val draftDirectory: String,
        val draftModel: OpenCodeModelRef?,
        /**
         * The session the screen is already creating in the draft's folder
         * (a client-minted ID): the first session created there is that one.
         */
        val draftSessionID: String? = null,
        /**
         * A model picked on the viewed session's screen that waits for the next
         * send there, as a typed send would apply it.
         */
        val viewingModel: OpenCodeModelRef? = null,
        val transcriptDelta: List<String>,
        /** Sessions that already have the long handoff header this call. */
        val explained: Set<String>,
        /** Sessions pinned in the drawer, by ID. */
        val pinned: Set<String> = emptySet(),
        /** The same pins in drawer order. */
        val pinnedOrder: List<String> = emptyList(),
        /**
         * The user's saved servers. Read tools can look at sessions on any of
         * them, each with its own credentials; everything else stays on [server].
         */
        val servers: List<OpenCodeServer> = emptyList(),
        /** Drawer pins on every server (`persistenceID|sessionID`), in drawer order. */
        val pinnedSessionKeys: List<String> = emptyList(),
        /**
         * What the drawer knows now (unread, waiting, running), read at each
         * tool call; null when Home hasn't published one.
         */
        val board: () -> VoiceSessionBoard? = { null },
        /** The session on screen shows a pending request. */
        val viewingNeedsInput: Boolean = false,
        /**
         * This call's prompts by session, oldest first: the ones still watched
         * and the ones resolved whose results may still be waiting to be spoken.
         * `read_session` and `wait_for_session` read after the newest, and a read
         * covers them all.
         */
        val watchedPromptIDs: Map<String, List<String>> = emptyMap(),
        /** Snapshot of the current call's debug log, when requested for a bug report. */
        val callLogSnapshot: () -> VoiceCallLog? = { null },
        /**
         * Developer Mode was on when the request started: `capture_app_screenshot`
         * works only then.
         */
        val developerMode: Boolean = false,
        /** What the call knows of the app, for a screenshot's message. */
        val appState: VoiceAppState? = null,
        /** The app's own window as a JPEG ([VoiceScreenshotClient]). */
        val captureScreenshot: suspend () -> VoiceScreenshotClient.Result = { VoiceScreenshotClient.Result.Failed(VoiceScreenshotClient.Failure.noWindow) },
        /** Recent `AppLog` lines, oldest first. */
        val appLog: () -> List<String> = { emptyList() },
        /** The model picker's favorites (`OpenCodeModel.id`), listed first. */
        val favoriteModelIDs: Set<String> = emptySet(),
        /**
         * The picker's remembered level per model (`modelVariants`), applied to
         * the server's default model when a session starts on it.
         */
        val modelVariants: Map<String, String> = emptyMap(),
        /** How long a tool may take; tests shorten it. */
        val latency: Latency = Latency.standard,
    )

    /**
     * What one tool call may spend on the network while the caller waits for
     * the delegator's answer. Required steps share [total]; optional ones get
     * a slice of it and are skipped once it passes.
     */
    data class Latency(
        /**
         * Every required step of one tool call (create, a chosen model, send),
         * together: the request policy's window, once.
         */
        val total: Duration = 30.seconds,
        /**
         * Enrichment a tool can do without: remote search when the drawer
         * already matched, the server's default model, a fresh read of a
         * session just created.
         */
        val optional: Duration = 1_500.milliseconds,
        /**
         * Uploading the call's log before the prompt that carries it; past it
         * the log goes inline instead.
         */
        val upload: Duration = 4.seconds,
    ) {
        companion object {
            val standard = Latency()
        }
    }

    /** What the workspace asks of the app. */
    class Effects(
        val open: suspend (OpenCodeSession) -> Unit,
        val setPinned: suspend (OpenCodeSession, Boolean) -> Unit = { _, _ -> },
        val openDraft: suspend (directory: String?) -> Unit = {},
        /**
         * A session's model changed, or the draft's when `sessionID` is null, so
         * a screen showing it can update its model button.
         */
        val switchedModel: suspend (sessionID: String?, model: OpenCodeModelRef) -> Unit = { _, _ -> },
        /** A session's waiting pick went to the server ahead of a prompt. */
        val appliedPick: suspend (sessionID: String, model: OpenCodeModelRef) -> Unit = { _, _ -> },
        /**
         * A session this request created couldn't take the draft's model: it
         * waits on the session's screen for the next send.
         */
        val keptPick: suspend (sessionID: String, model: OpenCodeModelRef) -> Unit = { _, _ -> },
        /**
         * A permission or question was answered or dismissed: the screens
         * read their requests again rather than wait for the server's event.
         */
        val settledRequest: suspend (sessionID: String) -> Unit = {},
        /** Hang up once the delegator's answer (the goodbye) has been spoken. */
        val endCall: suspend () -> Unit = {},
        /** The user said they're looking at the screen (true) or aren't. */
        val setUserLooking: suspend (looking: Boolean) -> Unit = {},
        /** A Voice message carried the long header into this session. */
        val explained: suspend (sessionID: String) -> Unit,
        /** The server admitted a Voice message into a session. */
        val sent: suspend (sessionID: String, prompt: VoiceAdmittedPrompt) -> Unit = { _, _ -> },
        /**
         * A tool read or waited on a finished session and handed the delegator
         * its reply to these prompts. It counts once the delegator answers.
         */
        val answered: suspend (sessionID: String, reply: String?, promptIDs: List<String>) -> Unit = { _, _, _ -> },
        /**
         * A prompt this request sent, tracked from the moment it's sent so a
         * failed later round or an overlapping request still sees it.
         */
        val tracked: suspend (VoiceDelegator.SentPrompt) -> Unit = {},
        /**
         * A wait on a session began or ended: its result waits for this
         * request's answer rather than speaking over it.
         */
        val waiting: suspend (sessionID: String, isWaiting: Boolean) -> Unit = { _, _ -> },
        /** Returns when the user next says words: a wait gives way to them. */
        val userSpoke: suspend () -> Unit = { awaitCancellation() },
        /**
         * `wait_for_session`'s wait, shared with the session's reply watches so
         * a session has one `/wait`. Without it, the client's own.
         */
        val awaitIdle: (suspend (server: OpenCodeServer, sessionID: String) -> Unit)? = null,
        val note: suspend (String) -> Unit,
        val trace: suspend (VoiceDelegator.Trace) -> Unit = {},
    )

    /** The actor's executor: one coroutine at a time touches the state below. */
    private val confined = Dispatchers.Default.limitedParallelism(1)

    private var viewing: OpenCodeSession? = start.viewing

    /** The model the draft's session starts with; `switch_model` changes it. */
    private var draftModel: OpenCodeModelRef? = start.draftModel

    /**
     * Picks waiting for the next send into their session, by session ID:
     * the viewed screen's, and a new session's model that couldn't be set.
     * A prompt applies its session's first; `switch_model` replaces it.
     */
    private val pendingPicks = HashMap<String, OpenCodeModelRef>()
    private var usedDraftSessionID = false
    private val explained = LinkedHashSet(start.explained)

    /** Pins and unpins made during this request, which the board may not show yet. */
    private val pinChanges = HashMap<String, Boolean>()
    private val prompts = mutableListOf<VoiceDelegator.SentPrompt>()
    private val answered = HashSet<String>()

    init {
        val id = start.viewing?.id
        val model = start.viewingModel
        if (id != null && model != null) pendingPicks[id] = model
    }

    val tools: VoiceDelegatorTools
        get() = VoiceDelegatorTools(
            run = { call -> run(call) },
            sent = { unanswered() },
            delivered = { withContext(confined) { prompts.toList() } },
            note = { text -> effects.note(text) },
            trace = { trace -> effects.trace(trace) },
        )

    private suspend fun unanswered(): List<VoiceDelegator.SentPrompt> = withContext(confined) {
        prompts.filter { it.promptID !in answered }
    }

    suspend fun run(call: VoiceDelegator.ToolCall): String = withContext(confined) {
        val arguments = try {
            Json.parseToJsonElement(call.arguments) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: JsonObject(emptyMap())
        try {
            // One budget for the whole tool call: every request in it, a step
            // marked required or not, retries within what's left of it.
            RetryBudget(start.latency.total).scope { perform(call.name, arguments) }
        } catch (error: OpenCodeClientError) {
            // Requests already rode out brief drops (`NetworkRetry`); this is final.
            val result = linkedMapOf<String, Any?>("error" to error.message)
            if (error.attempts > 1) result["attempts"] = error.attempts
            addUnconfirmed(error, result)
            json(result)
        }
    }

    private suspend fun perform(name: String, arguments: JsonObject): String {
        val server = server(arguments)
        val elsewhere = server.persistenceID != start.server.persistenceID
        if (elsewhere && name !in crossServerTools) {
            throw OpenCodeClientError("That needs ${server.friendlyName} open in the app. On another server, sessions can only be listed, searched, read, waited on, and checked for requests.")
        }
        return when (name) {
            "list_servers" -> return json(
                savedServers.map { saved ->
                    mapOf(
                        "server_id" to serverID(saved),
                        "name" to saved.friendlyName,
                        "current" to (saved.persistenceID == start.server.persistenceID),
                    )
                },
            )

            "list_sessions" -> {
                val board = if (elsewhere) null else start.board()
                val (sessions, running) = listed(board, server)
                val limit = minOf(arguments.int("limit") ?: 15, 40)
                val filter = if (arguments.bool("pinned_only") ?: false) "pinned" else arguments.string("filter")
                val pins = pinOrder(board, server)
                var rows = sessions.filter { it.parentID == null }.map { status(it, it.id in running, board, pins, server) }
                when (filter) {
                    "pinned" -> rows = rows.filter { it.pinOrder != null }.sortedBy { it.pinOrder!! }
                    "unread" -> rows = rows.filter { it.unread == VoiceSessionBoard.Flag.yes }
                    "needs_input" -> rows = rows
                        .filter { it.needsInput != VoiceSessionBoard.Flag.no }
                        .sortedBy { if (it.needsInput == VoiceSessionBoard.Flag.yes) 0 else 1 }
                    "running", "working" -> rows = rows.filter { it.working }
                    null, "", "all" -> Unit
                    else -> throw OpenCodeClientError("Unknown filter $filter; use pinned, unread, needs_input, or running.")
                }
                return json(rows.take(maxOf(0, limit)).map { it.json })
            }

            "search_sessions" -> {
                val query = string(arguments, "query")
                val limit = minOf(arguments.int("limit") ?: 8, 20)
                val board = if (elsewhere) null else start.board()
                val budget = RetryBudget(start.latency.total)
                val (recent, running) = budget.required { listed(board, server, preferBoard = true) }
                val search = VoiceSessionSearch(query)
                val local = recent.filter { it.parentID == null && search.score(it) != null }
                // Both queries share one budget. With drawer matches in hand, the
                // server's search is extra: one quick try, never its retries.
                val remote: suspend () -> List<OpenCodeSession> = {
                    val found = client.searchSessions(server, query, 20)
                    val term = search.longestTerm
                    if (found.isEmpty() && term != null && term != search.phrase) client.searchSessions(server, term, 20) else found
                }
                var found: List<OpenCodeSession> = emptyList()
                var searchFailed = false
                if (local.isEmpty()) {
                    // Each query retries within what's left, not a window apiece.
                    try {
                        found = budget.required { client.searchSessions(server, query, 20) }
                        val term = search.longestTerm
                        if (found.isEmpty() && term != null && term != search.phrase) {
                            found = budget.required { client.searchSessions(server, term, 20) }
                        }
                    } catch (_: OpenCodeClientError) {
                        searchFailed = true
                    }
                } else {
                    found = budget.optional(start.latency.optional, remote) ?: emptyList()
                }
                val seen = HashSet<String>()
                val matches = (local + found)
                    .filter { seen.add(it.id) }
                    .sortedWith { a, b ->
                        val sa = search.score(a) ?: 0
                        val sb = search.score(b) ?: 0
                        if (sa != sb) sb.compareTo(sa) else b.updatedAt.compareTo(a.updatedAt)
                    }
                if (matches.isEmpty() && searchFailed) {
                    throw OpenCodeClientError("Couldn’t search sessions, and no recent session matched.")
                }
                val pins = pinOrder(board, server)
                return json(matches.take(maxOf(0, limit)).map { status(it, it.id in running, board, pins, server).json })
            }

            "pin_session", "unpin_session" -> {
                val session = target(arguments)
                val pin = name == "pin_session"
                pinChanges[session.id] = pin
                effects.setPinned(session, pin)
                effects.note("${if (pin) "Pinned" else "Unpinned"} “${session.title}”.")
                return json(mapOf((if (pin) "pinned" else "unpinned") to session.title))
            }

            "new_session_draft" -> {
                val directory = arguments.string("directory")
                viewing = null
                effects.openDraft(directory)
                effects.note("Opened a new session draft${directory?.let { " in ${folder(it)}" } ?: ""} on the user’s screen.")
                return json(mapOf("opened" to "new session draft", "project" to (directory?.let(::folder) ?: "most recent")))
            }

            "list_projects" -> {
                val snapshot = client.homeSnapshot(server)
                return json(
                    snapshot.projects.take(20)
                        .map { mapOf<String, Any?>("name" to it.name, "directory" to it.directory) }
                        .map { if (elsewhere) tagged(it, server) else it },
                )
            }

            "open_session" -> {
                val session = client.getSession(server, string(arguments, "session_id"))
                open(session)
                return json(mapOf("opened" to session.title))
            }

            "read_session" -> {
                val session = target(arguments, server)
                val messageID = arguments.string("message_id")
                if (!messageID.isNullOrEmpty()) {
                    // One message whole, by the ID a cut one named, paged past `messageReadLimit`.
                    val serverMessageID = messageID.split("-text-").first()
                    val items = client.message(server, session.id, serverMessageID)
                    val text = VoiceWorkClient.messageText(items, messageID)
                        ?: throw OpenCodeClientError("No text in message $messageID of “${session.title}”; pass a message_id from a read.")
                    val offset = maxOf(0, arguments.int("offset") ?: 0)
                    val isUser = items.any { it is OpenCodeTranscriptItem.UserText && it.id == messageID }
                    val page = mapOf<String, Any?>(
                        "title" to session.title,
                        "message_id" to messageID,
                        "role" to if (isUser) "user" else "assistant",
                        "total_characters" to text.length,
                        "offset" to offset,
                        "text" to VoiceWorkClient.messagePage(text, messageID, offset),
                    )
                    return json(if (elsewhere) tagged(page, server) else page)
                }
                val limit = arguments.int("limit") ?: 8
                // Idle first, then the transcript: a run ending in between still has its reply in it.
                val isSessionRunning = client.isSessionActive(server, session.id)
                val items = client.transcript(server, session.id, 40)
                val messages = VoiceWorkClient.formatRecentMessages(items, fullLatestReply = true).takeLast(maxOf(0, limit))
                // This call's prompts only ever go to its own server.
                if (!isSessionRunning && !elsewhere) {
                    reportAnswered(session.id, items, showedRecentMessages = true)
                }
                val read = mapOf<String, Any?>("title" to session.title, "working" to isSessionRunning, "messages" to messages)
                return json(if (elsewhere) tagged(read, server) else read)
            }

            "send_message" -> {
                // One budget for the tool: finding the session, the pick, the prompt.
                val budget = RetryBudget(start.latency.total)
                val session = budget.required { target(arguments) }
                val delivery = if (arguments.string("delivery") == "queue") OpenCodeDelivery.Queue else OpenCodeDelivery.Steer
                val attachCallLog = arguments.bool("attach_call_log") ?: false
                if (delivery == OpenCodeDelivery.Steer) heldByRequest(session, budget)?.let { return it }
                val outcome = send(
                    string(arguments, "text"),
                    to = session,
                    delivery = delivery,
                    attachCallLog = attachCallLog,
                    budget = budget,
                )
                val result = linkedMapOf<String, Any?>(
                    "sent_to" to session.title,
                    "delivery" to delivery.rawValue,
                    "message_id" to outcome.promptID,
                )
                if (outcome.callLogAttached) result["call_log_attached"] = true
                return json(result)
            }

            "send_call_log" -> {
                val budget = RetryBudget(start.latency.total)
                val session = budget.required { target(arguments) }
                val instruction = note(arguments) ?: "Inspect the attached voice call log from this call."
                heldByRequest(session, budget)?.let { return it }
                val outcome = send(instruction, to = session, delivery = OpenCodeDelivery.Steer, attachCallLog = true, budget = budget)
                return json(
                    mapOf(
                        "sent_to" to session.title,
                        "message_id" to outcome.promptID,
                        "call_log_attached" to outcome.callLogAttached,
                    ),
                )
            }

            "capture_app_screenshot" -> {
                if (!start.developerMode) {
                    throw OpenCodeClientError("Screenshots need Developer Mode, in Settings › Developer.")
                }
                // The screen as the user sees it now, before any read moves on.
                val capture = when (val taken = start.captureScreenshot()) {
                    is VoiceScreenshotClient.Result.Taken -> taken.capture
                    is VoiceScreenshotClient.Result.Failed -> {
                        AppLog.voice.notice("Delegator screenshot refused: ${taken.failure.name}")
                        return json(mapOf("error" to taken.failure.message, "screenshot_attached" to false))
                    }
                }
                val budget = RetryBudget(start.latency.total)
                val session = budget.required { target(arguments) }
                heldByRequest(session, budget)?.let { return it }
                val appState = start.appState?.copy(
                    screen = capture.screen,
                    appearance = capture.appearance,
                    home = start.board()?.home,
                )
                val screenshot = OpenCodeOutgoingAttachment(data = capture.jpeg, mime = "image/jpeg", name = "screenshot.jpg")
                val appLog = if (arguments.bool("attach_app_log") == true) VoiceAppLog.attachment(start.appLog()) else null
                val feedback = arguments.string("text")?.trim().orEmpty()
                val outcome = send(
                    feedback.ifEmpty { "Look at the attached screenshot of the app." },
                    to = session,
                    delivery = OpenCodeDelivery.Steer,
                    attachCallLog = arguments.bool("attach_call_log") ?: false,
                    attachments = listOfNotNull(screenshot, appLog),
                    appState = appState,
                    budget = budget,
                )
                val result = linkedMapOf<String, Any?>("sent_to" to session.title, "message_id" to outcome.promptID, "screenshot_attached" to true)
                capture.screen?.let { result["screen"] = it }
                if (outcome.callLogAttached) result["call_log_attached"] = true
                if (appLog != null) result["app_log_attached"] = true
                return json(result)
            }

            "list_requests" -> {
                val session = target(arguments, server)
                val pending = client.familyPendingRequests(server, listOf(session.id), session.directory)
                val requests = requests(pending, session)
                return json(if (elsewhere) tagged(requests, server) else requests)
            }

            "reply_permission" -> {
                val session = target(arguments)
                val requestID = string(arguments, "request_id")
                val decision = string(arguments, "decision")
                val reply = permissionReply(decision)
                    ?: throw OpenCodeClientError("decision must be once, always, or reject.")
                client.replyPermission(server, session.id, requestID, reply, if (reply == OpenCodePermissionReply.Reject) note(arguments) else null)
                effects.settledRequest(session.id)
                effects.note(
                    if (reply == OpenCodePermissionReply.Reject) {
                        "Denied a permission in “${session.title}”."
                    } else {
                        "Approved a permission in “${session.title}” ($decision)."
                    },
                )
                return json(mapOf("replied" to decision, "session" to session.title))
            }

            "answer_question" -> {
                val session = target(arguments)
                val formID = string(arguments, "form_id")
                val raw = arguments["answers"] as? JsonObject
                if (raw == null || raw.isEmpty()) {
                    throw OpenCodeClientError("answers must map each field key to its value.")
                }
                val answers = LinkedHashMap<String, OpenCodeFormValue>()
                for ((key, value) in raw) answers[key] = formValue(value)
                client.replyForm(server, session.id, formID, answers)
                effects.settledRequest(session.id)
                effects.note("Answered a question in “${session.title}”.")
                return json(mapOf("answered" to formID, "session" to session.title))
            }

            "dismiss_question" -> {
                val session = target(arguments)
                client.cancelForm(server, session.id, string(arguments, "form_id"), note(arguments))
                effects.settledRequest(session.id)
                effects.note("Dismissed a question in “${session.title}”.")
                return json(mapOf("dismissed" to true))
            }

            "list_queued" -> {
                val session = target(arguments)
                val inputs = client.pendingInputs(server, session.id)
                return json(
                    inputs.filter { !it.isCompaction && it.notice == null }.map {
                        mapOf(
                            "message_id" to it.id,
                            "delivery" to it.delivery.rawValue,
                            "text" to VoiceWorkClient.excerpt(VoiceHandoff.parse(it.text)?.instruction ?: it.text, limit = 300),
                        )
                    },
                )
            }

            "steer_queued" -> {
                val session = target(arguments)
                val messageID = string(arguments, "message_id")
                // A steer applies a waiting pick first, as a typed Send Now does.
                val pick = pendingPicks[session.id]
                if (pick != null) {
                    switchPendingPick(pick, session.id)
                    pendingPicks.remove(session.id)
                    effects.appliedPick(session.id, pick)
                }
                client.moveToBackgroundBeforeSteer(server, session.id)
                client.setPendingInputDelivery(server, session.id, messageID, OpenCodeDelivery.Steer)
                effects.note("Switched a queued message in “${session.title}” to steering.")
                return json(mapOf("delivery" to "steer"))
            }

            "cancel_queued" -> {
                val session = target(arguments)
                client.cancelPendingInput(server, session.id, string(arguments, "message_id"))
                effects.note("Cancelled a queued message in “${session.title}”.")
                return json(mapOf("cancelled" to true))
            }

            "end_call" -> {
                effects.endCall()
                return json(mapOf("ending" to "after your reply is spoken; make it a short goodbye"))
            }

            "set_user_looking" -> {
                val looking = arguments.bool("looking") ?: throw OpenCodeClientError("Pass looking: true or false.")
                effects.setUserLooking(looking)
                effects.note(if (looking) "The user is looking at the screen." else "The user isn’t looking at the screen.")
                return json(
                    if (looking) {
                        mapOf("looking" to true, "note" to "While the app is in front, requests say they're viewing the session; still say results in words.")
                    } else {
                        mapOf("looking" to false, "note" to "Describe results in words from now on, without pointing at the screen.")
                    },
                )
            }

            "create_session" -> return createSession(arguments, server)

            "wait_for_session" -> {
                val session = target(arguments, server)
                // While it waits, the session's result waits for this request's answer
                // (bounded), so the two never say the same thing twice.
                if (!elsewhere) effects.waiting(session.id, true)
                try {
                    // GPT-Live takes no new turn while this request is open, so the user
                    // speaking ends the wait: the answer frees the voice agent for them.
                    val awaitIdle = effects.awaitIdle
                    val end = waitEnd(90.seconds, userSpoke = effects.userSpoke) {
                        if (awaitIdle != null) awaitIdle(server, session.id) else client.waitForIdle(server, session.id)
                    }
                    val finished = end == WaitEnd.idle
                    if (end == WaitEnd.userSpoke) {
                        AppLog.voice.notice("Delegator wait ended early: the user spoke")
                        effects.note("Stopped waiting on “${session.title}”: the user spoke.")
                        // The user is waiting for the answer: no read that could hold it
                        // up or lose it. The reply is spoken when it lands.
                        if (!elsewhere) effects.waiting(session.id, false)
                        val yielded = mapOf<String, Any?>("finished" to false, "user_spoke" to userSpokeNote)
                        return json(if (elsewhere) tagged(yielded, server) else yielded)
                    }
                    // The wait may have used up the tool's budget: the read after it
                    // gets one of its own.
                    val items = RetryBudget(start.latency.total).scope { client.transcript(server, session.id, 40) }
                    if (finished && !elsewhere) {
                        reportAnswered(session.id, items, showedRecentMessages = false)
                    }
                    if (!elsewhere) effects.waiting(session.id, false)
                    // Every text part of the reply, not just the line after its last tool.
                    val latestReply = VoiceWorkClient.fullReply(items, afterPromptID = if (elsewhere) null else latestPromptID(session.id))
                    val waited = mapOf<String, Any?>("finished" to finished, "latest_reply" to (latestReply ?: ""))
                    return json(if (elsewhere) tagged(waited, server) else waited)
                } catch (error: Throwable) {
                    if (!elsewhere) withContext(NonCancellable) { effects.waiting(session.id, false) }
                    throw error
                }
            }

            "interrupt_session" -> {
                val session = target(arguments)
                client.interrupt(server, session.id)
                effects.note("Interrupted “${session.title}”.")
                return json(mapOf("interrupted" to session.title))
            }

            "list_models" -> {
                val session = modelTarget(arguments)
                val catalog = client.modelCatalog(server, session?.directory ?: start.draftDirectory)
                val current = (if (session == null) draftModel else pickedModel(session) ?: session.model) ?: catalog.defaultModel?.ref
                val favorites = start.favoriteModelIDs
                var models = catalog.models
                arguments.string("query")?.let { query -> models = matching(models, query) }
                // Favorites first, as the picker shows them; otherwise the order stays.
                models = models.filter { it.id in favorites } + models.filter { it.id !in favorites }
                val limit = minOf(arguments.int("limit") ?: 20, 50)
                return json(
                    mapOf(
                        "session" to (session?.title ?: "new session draft"),
                        "matches" to models.size,
                        "models" to models.take(maxOf(0, limit)).map { model ->
                            val row = linkedMapOf<String, Any?>(
                                "id" to model.id,
                                "name" to model.name,
                                "provider" to model.providerID,
                                "variants" to ModelVariant.sorted(model.variants),
                                "favorite" to (model.id in favorites),
                                "current" to (current?.let(model::matches) ?: false),
                            )
                            val variant = current?.variant
                            if (current != null && model.matches(current) && variant != null) row["current_variant"] = variant
                            row
                        },
                    ),
                )
            }

            "switch_model" -> {
                val session = modelTarget(arguments)
                val catalog = client.modelCatalog(server, session?.directory ?: start.draftDirectory)
                val wanted = string(arguments, "model")
                val model = catalog.models.firstOrNull { it.id == wanted }
                    ?: catalog.models.firstOrNull { it.id.equals(wanted, ignoreCase = true) }
                    ?: throw OpenCodeClientError("No model $wanted; pass an id from list_models.")
                val previous = if (session == null) draftModel else pickedModel(session) ?: session.model
                val variant: String?
                val requested = arguments.string("variant")
                if (!requested.isNullOrEmpty()) {
                    if (requested !in model.variants) {
                        val offered = ModelVariant.sorted(model.variants)
                        throw OpenCodeClientError(
                            if (offered.isEmpty()) {
                                "${model.name} has no variants; omit variant."
                            } else {
                                "${model.name} has no $requested variant; it offers ${offered.joinToString(", ")}."
                            },
                        )
                    }
                    variant = requested
                } else {
                    // Keep the reasoning variant when the new model offers it too, as the picker does.
                    variant = previous?.variant?.takeIf { it in model.variants }
                }
                val ref = model.ref(variant)
                if (session != null) {
                    client.switchModel(server, session.id, ref)
                    pendingPicks.remove(session.id)
                } else {
                    draftModel = ref
                }
                effects.switchedModel(session?.id, ref)
                val label = model.name + (variant?.let { " (${ModelVariant.label(it)})" } ?: "")
                effects.note(session?.let { "Switched “${it.title}” to $label." } ?: "Set the new session draft’s model to $label.")
                return json(
                    mapOf(
                        "switched_to" to model.name,
                        "variant" to (variant ?: "default"),
                        "session" to (session?.title ?: "new session draft"),
                    ),
                )
            }

            else -> throw OpenCodeClientError("Unknown tool $name")
        }
    }

    private suspend fun createSession(arguments: JsonObject, server: OpenCodeServer): String {
        // One budget for the whole call. Creating and sending are required, and
        // so is a model the user chose; the server's default model and a
        // fresh read are extras.
        val budget = RetryBudget(start.latency.total)
        val directory = string(arguments, "directory")
        val model = newSessionModel(directory, budget)
        var sessionID = dependency(ServerIDClient).sessionID()
        val draftSessionID = start.draftSessionID
        if (!usedDraftSessionID && draftSessionID != null && directory == start.draftDirectory) {
            usedDraftSessionID = true
            sessionID = draftSessionID
        }
        val created = budget.required { client.createSession(server, directory, sessionID) }
        var session = created
        val result = linkedMapOf<String, Any?>("created" to created.id, "project" to folder(created.directory))
        var appliedModel: OpenCodeModelRef? = null
        var refusedModel: OpenCodeModelRef? = null
        if (model != null) {
            if (model.chosen) {
                try {
                    budget.required { client.switchModel(server, created.id, model.ref) }
                    appliedModel = model.ref
                } catch (error: OpenCodeClientError) {
                    // Never on another model: the delegator says so, nothing is
                    // sent, and the pick waits on the session for the next send.
                    refusedModel = model.ref
                    pendingPicks[created.id] = model.ref
                    result["model_error"] = "The session was created, but its model couldn’t be set, so nothing was sent to it: ${error.message}"
                }
            } else if (budget.optional(start.latency.optional) { client.switchModel(server, created.id, model.ref) } != null) {
                appliedModel = model.ref
            }
        }
        budget.optional(start.latency.optional) { client.getSession(server, created.id) }?.let { session = it }
        // What was set, even when the fresh read was skipped or lagged it.
        if (appliedModel != null) session = session.copy(model = appliedModel)
        open(session)
        if (refusedModel != null) {
            effects.keptPick(created.id, refusedModel)
            return json(result)
        }
        val text = arguments.string("text")
        if (!text.isNullOrEmpty()) {
            // The session exists and is on screen whatever happens to its first
            // message, so the delegator never creates it again.
            try {
                send(text, to = session, delivery = OpenCodeDelivery.Steer, isNewSession = true, budget = budget)
            } catch (error: OpenCodeClientError) {
                result["send_error"] = error.message
                addUnconfirmed(error, result, key = "send_error")
            }
        }
        return json(result)
    }

    /**
     * The sessions to list and which of them run: a fresh snapshot (REST is
     * authoritative), or the drawer's own list when the snapshot fails or, for
     * search, when the drawer's is live. Never a location-scoped read.
     */
    private suspend fun listed(
        board: VoiceSessionBoard?,
        server: OpenCodeServer,
        preferBoard: Boolean = false,
    ): Pair<List<OpenCodeSession>, Set<String>> {
        if (preferBoard && board != null && board.isLive) return board.sessions to board.busyIDs
        return try {
            val snapshot = client.completeHomeSnapshot(server)
            val running = SessionActivity.ancestors(snapshot.activeSessionIDs, snapshot.activeSessionParents ?: emptyMap())
            snapshot.sessions to running
        } catch (error: OpenCodeClientError) {
            if (board == null || !board.isLive) throw error
            board.sessions to board.busyIDs
        }
    }

    /** Drawer order on `server`, with this request's own pins and unpins applied. */
    private fun pinOrder(board: VoiceSessionBoard?, server: OpenCodeServer): List<String> {
        if (server.persistenceID != start.server.persistenceID) return pins(start.pinnedSessionKeys, server)
        val order = (board?.pinnedIDs ?: if (start.pinnedOrder.isEmpty()) start.pinned.sorted() else start.pinnedOrder).toMutableList()
        order.removeAll { pinChanges[it] == false }
        for ((id, pin) in pinChanges.entries.sortedBy { it.key }) {
            if (pin && id !in order) order += id
        }
        return order
    }

    data class SessionStatus(
        val session: OpenCodeSession,
        val working: Boolean,
        val unread: VoiceSessionBoard.Flag,
        val needsInput: VoiceSessionBoard.Flag,
        val pinOrder: Int?,
        val onScreen: Boolean,
        /** Another server's, named so sessions on different servers can't be confused. */
        val server: OpenCodeServer? = null,
    ) {
        val json: Map<String, Any?>
            get() {
                val row = linkedMapOf<String, Any?>(
                    "id" to session.id,
                    "title" to session.title,
                    "project" to folder(session.directory),
                    "working" to working,
                    "unread" to unread.json,
                    "needs_input" to needsInput.json,
                    "pinned" to (pinOrder != null),
                    "on_screen" to onScreen,
                )
                pinOrder?.let { row["pinned_order"] = it }
                return server?.let { tagged(row, it) } ?: row
            }
    }

    private fun status(
        session: OpenCodeSession,
        running: Boolean,
        board: VoiceSessionBoard?,
        pins: List<String>,
        server: OpenCodeServer,
    ): SessionStatus {
        val elsewhere = server.persistenceID != start.server.persistenceID
        var needsInput = board?.needsInput(session, running)
            ?: if (running) VoiceSessionBoard.Flag.unknown else VoiceSessionBoard.Flag.no
        if (start.viewingNeedsInput && !elsewhere && session.id == start.viewing?.id) needsInput = VoiceSessionBoard.Flag.yes
        val unread = board?.unread(session)
            ?: session.idleMilliseconds?.let { VoiceSessionBoard.Flag.of(it > (session.viewedMilliseconds ?: -1)) }
            ?: VoiceSessionBoard.Flag.unknown
        val index = pins.indexOf(session.id)
        return SessionStatus(
            session = session,
            working = running,
            unread = unread,
            needsInput = needsInput,
            pinOrder = if (index >= 0) index + 1 else null,
            onScreen = !elsewhere && session.id == viewing?.id,
            server = if (elsewhere) server else null,
        )
    }

    private suspend fun open(session: OpenCodeSession) {
        viewing = session
        effects.open(session)
        effects.note("Opened “${session.title}” on the user’s screen.")
    }

    private data class SendOutcome(val promptID: String, val callLogAttached: Boolean)

    /**
     * A read or wait of an idle session: reports the reply it read and the
     * prompts that reply answers, and returns the reply. With prompts this
     * request sent there, those and the reply after the last of them (other
     * tracked prompts in that session still speak). Otherwise the session's
     * newest reply, covering every prompt the call tracks there when the
     * delegator was also shown the recent messages (`read_session`), else
     * only the prompts that reply answers (`wait_for_session`), so earlier
     * ones with replies of their own still speak.
     */
    private suspend fun reportAnswered(
        sessionID: String,
        items: List<OpenCodeTranscriptItem>,
        showedRecentMessages: Boolean,
    ): String? {
        val sent = prompts.filter { it.sessionID == sessionID }.map { it.promptID }
        val reply: String?
        val promptIDs: List<String>
        if (sent.isEmpty()) {
            val tracked = start.watchedPromptIDs[sessionID] ?: emptyList()
            val newest = VoiceWorkClient.newestReply(items, tracked)
            reply = newest.reply
            promptIDs = if (showedRecentMessages) tracked else tracked.filter { it in newest.promptIDs }
        } else {
            answered += sent
            reply = VoiceWorkClient.replyAfterPrompt(items, afterPromptID = sent.last())
            promptIDs = sent
        }
        effects.answered(sessionID, reply, promptIDs)
        return reply
    }

    /**
     * The prompt a read or wait reports the reply to: the last one this
     * request sent there, else null, the session's newest prompt (typed ones
     * and others this call didn't track included).
     */
    private fun latestPromptID(sessionID: String): String? = prompts.lastOrNull { it.sessionID == sessionID }?.promptID

    /**
     * A steer into a session blocked on a permission or question would wait
     * behind it, and a steered "yes" never answers the request, so it isn't
     * sent: the delegator gets the requests to answer or dismiss first. Every
     * steer reads its one target's requests (Home's flags lag a request that
     * just arrived); an unreadable session Home or the screen flags still
     * holds the message. A global (MCP) form belongs to no session, so it
     * never holds one.
     */
    private suspend fun heldByRequest(session: OpenCodeSession, budget: RetryBudget): String? {
        val board = start.board()
        var flag = board?.let { it.needsInput(session, running = it.isRunning(session.id)) } ?: VoiceSessionBoard.Flag.no
        if (start.viewingNeedsInput && session.id == start.viewing?.id) flag = VoiceSessionBoard.Flag.yes
        val server = start.server
        val pending = budget.optional(start.latency.optional) {
            client.familyPendingRequests(server, listOf(session.id), session.directory)
        }?.let { it.copy(forms = it.forms.filter { form -> !form.isGlobal }) }
        val waiting = pending?.let { it.permissions.isNotEmpty() || it.forms.isNotEmpty() } ?: (flag == VoiceSessionBoard.Flag.yes)
        if (!waiting) return null
        AppLog.voice.notice(
            "Delegator held a steer: ${pending?.let { "${it.permissions.size} permissions, ${it.forms.size} questions" } ?: "requests unread"} pending",
        )
        val result = LinkedHashMap(pending?.let { requests(it, session) } ?: mapOf("session" to session.title))
        result["not_sent"] = true
        result["note"] = heldNote(listed = pending != null)
        return json(result)
    }

    /**
     * A Voice message: the delegator's instruction, rendered in the session
     * like any voice delegation. The instruction is already the voice side's
     * written request, so it is what the message shows; the spoken
     * acknowledgement stays in the transcript delta.
     */
    private suspend fun send(
        instruction: String,
        to: OpenCodeSession,
        delivery: OpenCodeDelivery,
        attachCallLog: Boolean = false,
        attachments: List<OpenCodeOutgoingAttachment> = emptyList(),
        appState: VoiceAppState? = null,
        isNewSession: Boolean = false,
        budget: RetryBudget,
    ): SendOutcome {
        val session = to
        val fresh = session.id !in explained
        val files = mutableListOf<OpenCodeFileReference>()
        var inlineCallLog: String? = null
        var callLogURL: String? = null
        var callLogAttached = false

        val log = if (attachCallLog) start.callLogSnapshot() else null
        if (log != null) {
            val data = log.boundedJSON()
            val fileName = "voice-call-${log.id.toString().lowercase()}.json"
            // `java.io.tmpdir` is the app's cache directory on Android.
            val tempFile = File(System.getProperty("java.io.tmpdir") ?: "", "${UUID.randomUUID().toString().uppercase()}-$fileName")
            var uploadedPath: String? = null
            try {
                val written = try {
                    tempFile.writeBytes(data)
                    true
                } catch (_: Exception) {
                    false
                }
                if (written) {
                    // Optional: past its deadline (or on any failure) the log goes
                    // inline in the one prompt instead.
                    val server = start.server
                    val directory = session.directory
                    uploadedPath = budget.optional(start.latency.upload) {
                        val finished = try {
                            client.uploadFile(server, directory, tempFile, fileName)
                                .filterIsInstance<OpenCodeUploadEvent.Finished>()
                                .firstOrNull()
                        } catch (error: OpenCodeClientError) {
                            throw error
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            throw OpenCodeClientError(error.localizedMessage ?: error.toString())
                        }
                        finished?.path ?: throw OpenCodeClientError("The upload ended without a file.")
                    }
                }
            } finally {
                tempFile.delete()
            }
            val path = uploadedPath
            if (!path.isNullOrEmpty()) {
                files += OpenCodeFileReference(name = fileName, mime = "application/json", path = path)
            } else {
                val inlineText = log.boundedJSON(maxBytes = 16_000).toString(Charsets.UTF_8)
                if (inlineText.isNotEmpty()) inlineCallLog = inlineText
            }
            callLogAttached = files.isNotEmpty() || inlineCallLog != null
            val uploader = dependency(VoiceLogUploader)
            if (callLogAttached && uploader.isEnabled() &&
                RetryBudget.bounded(minOf(start.latency.upload, budget.remaining)) { uploader.upload(log) } == true
            ) {
                callLogURL = uploader.url(log.id)
            }
        }

        val text = VoiceHandoff.format(
            instruction = instruction,
            transcriptDelta = start.transcriptDelta,
            context = "Session \"${session.title}\" in project \"${folder(session.directory)}\".",
            framing = null,
            header = if (fresh) VoiceHandoff.Header.full else VoiceHandoff.Header.continued,
            callLogAttached = callLogAttached,
            callLogURL = callLogURL,
            inlineCallLog = inlineCallLog,
            appState = appState,
            screenshotAttached = attachments.any { it.mime.startsWith("image/") },
            appLogAttached = attachments.any { it.name == VoiceAppLog.fileName },
        )
        // Like a typed send: the session's waiting pick goes just before the
        // prompt, within the tool's budget, and a refused one sends nothing. A
        // queued message leaves it waiting, as a typed queued send does: it
        // mustn't switch the run it waits behind.
        val server = start.server
        val pick = if (delivery == OpenCodeDelivery.Queue && !isNewSession) null else pendingPicks[session.id]
        if (pick != null) {
            budget.required { switchPendingPick(pick, session.id) }
        }
        if (delivery == OpenCodeDelivery.Steer && !isNewSession) client.moveToBackgroundBeforeSteer(server, session.id)
        // One ID for every attempt, read back if its sending ends in doubt.
        val promptID = budget.required {
            client.sendPromptConfirming(server, session.id, text, delivery, files = files.toList(), attachments = attachments, readBack = start.latency.optional)
        }
        // The pick waits until a prompt that carried it was taken.
        if (pick != null) {
            pendingPicks.remove(session.id)
            effects.appliedPick(session.id, pick)
        }
        effects.sent(
            session.id,
            VoiceAdmittedPrompt(messageID = promptID, text = text, attachments = attachments, files = files.toList(), delivery = delivery),
        )
        if (fresh) {
            explained += session.id
            effects.explained(session.id)
        }
        val sent = VoiceDelegator.SentPrompt(
            sessionID = session.id,
            title = session.title,
            promptID = promptID,
            topic = VoiceWorkClient.excerpt(instruction, limit = 90),
        )
        prompts += sent
        effects.tracked(sent)
        effects.note("${if (delivery == OpenCodeDelivery.Queue) "Queued for" else "Sent to"} “${session.title}”: $instruction")
        return SendOutcome(promptID, callLogAttached)
    }

    private suspend fun target(arguments: JsonObject, on: OpenCodeServer? = null): OpenCodeSession {
        val server = on ?: start.server
        val id = arguments.string("session_id")
        if (!id.isNullOrEmpty()) {
            return client.getSession(server, id)
        }
        if (server.persistenceID != start.server.persistenceID) {
            throw OpenCodeClientError("Pass session_id for a session on ${server.friendlyName}.")
        }
        return viewing
            ?: throw OpenCodeClientError("The user is on a new session draft in ${folder(start.draftDirectory)}; use create_session or pass session_id.")
    }

    /** This call's server and the user's other saved ones, each once. */
    private val savedServers: List<OpenCodeServer>
        get() {
            val seen = HashSet<String>()
            return (listOf(start.server) + start.servers).filter { seen.add(it.persistenceID) }
        }

    /**
     * The server a tool acts on: `server_id` from list_servers, or a saved
     * server's name; this call's by default. Never an address the model makes up.
     */
    private fun server(arguments: JsonObject): OpenCodeServer {
        val requested = arguments.string("server_id")?.trim()
        if (requested.isNullOrEmpty()) return start.server
        savedServers.firstOrNull { serverID(it) == requested }?.let { return it }
        val named = savedServers.filter { it.friendlyName.equals(requested, ignoreCase = true) }
        if (named.size == 1) return named[0]
        throw OpenCodeClientError("No saved server “$requested”; pass a server_id from list_servers.")
    }

    /**
     * The model a session this request creates starts with, as a typed new
     * session's: the draft's on a draft, otherwise the server's default
     * (`OpenCodeModelCatalog.newSessionModel`), or null to leave it to the server.
     * `chosen` for the draft's, which the user sees on its model button, so
     * it must be applied; the default only adds a remembered level, read briefly.
     */
    private suspend fun newSessionModel(directory: String, budget: RetryBudget): NewSessionModel? {
        val draft = draftModel
        if (viewing == null && draft != null) return NewSessionModel(draft, chosen = true)
        val server = start.server
        val catalog = budget.optional(start.latency.optional) { client.modelCatalog(server, directory) } ?: return null
        val model = catalog.newSessionModel(start.modelVariants) ?: return null
        return NewSessionModel(model, chosen = false)
    }

    private data class NewSessionModel(val ref: OpenCodeModelRef, val chosen: Boolean)

    private suspend fun switchPendingPick(pick: OpenCodeModelRef, sessionID: String) {
        try {
            client.switchModel(start.server, sessionID, pick)
        } catch (error: OpenCodeClientError) {
            throw error.copy(message = "Couldn’t switch model: ${error.message}")
        }
    }

    /** The model the viewed session's screen picked and hasn't sent yet. */
    private fun pickedModel(session: OpenCodeSession?): OpenCodeModelRef? = session?.let { pendingPicks[it.id] }

    /**
     * The session whose model the request means, read fresh for its current
     * model, or null for the draft on screen.
     */
    private suspend fun modelTarget(arguments: JsonObject): OpenCodeSession? {
        val id = arguments.string("session_id")?.takeIf { it.isNotEmpty() } ?: viewing?.id ?: return null
        return client.getSession(start.server, id)
    }

    companion object {
        /** Tools that may look at another saved server: they only read. */
        val crossServerTools: Set<String> = setOf(
            "list_servers", "list_sessions", "search_sessions", "read_session", "wait_for_session", "list_requests", "list_projects",
        )

        /** One server's drawer pins, in order, from every server's `persistenceID|sessionID` keys. */
        fun pins(keys: List<String>, server: OpenCodeServer): List<String> = keys.mapNotNull { key ->
            if (key.startsWith(server.persistenceID + "|")) key.drop(server.persistenceID.length + 1) else null
        }

        /** A short, stable name for a saved server that carries none of its addresses or credentials. */
        fun serverID(server: OpenCodeServer): String = voiceServerID(server)

        /** A result from another server, named so it's never taken for this call's. */
        fun tagged(row: Map<String, Any?>, server: OpenCodeServer): Map<String, Any?> =
            mapOf("server_id" to serverID(server), "server" to server.friendlyName) + row

        /**
         * Models matching a spoken query, most words matched first: each word
         * matches the start of a word in the name, provider, or catalog key, so
         * "opus", "gpt 5.5", and "anthropic sonnet" all find theirs.
         */
        fun matching(models: List<OpenCodeModel>, query: String): List<OpenCodeModel> {
            val terms = words(query)
            if (terms.isEmpty()) return models
            return models.withIndex()
                .mapNotNull { (index, model) ->
                    val haystack = words("${model.name} ${model.id}")
                    val hits = terms.count { term -> haystack.any { it.startsWith(term) } }
                    if (hits > 0) Triple(index, hits, model) else null
                }
                .sortedWith { a, b -> if (a.second != b.second) b.second.compareTo(a.second) else a.first.compareTo(b.first) }
                .map { it.third }
        }

        /**
         * Lowercased words, keeping a version's dots ("5.5"), so "gpt 5.5"
         * doesn't also match GPT-5.4 through a lone "5".
         */
        private fun words(text: String): List<String> {
            val pieces = mutableListOf<String>()
            val current = StringBuilder()
            for (char in text.lowercase()) {
                if (char.isLetter() || isNumber(char) || char == '.') {
                    current.append(char)
                } else if (current.isNotEmpty()) {
                    pieces += current.toString()
                    current.clear()
                }
            }
            if (current.isNotEmpty()) pieces += current.toString()
            return pieces.map { it.trim('.') }.filter { it.isNotEmpty() }
        }

        /** How the request describes where the user is. */
        fun viewingText(
            session: OpenCodeSession?,
            isSessionRunning: Boolean,
            needsInput: Boolean,
            draftDirectory: String,
        ): String {
            val activeIDs = if (session != null && isSessionRunning) setOf(session.id) else emptySet()
            return ReplyWatch(activeSessionIDs = activeIDs).statusSummary(
                viewing = session,
                needsInput = needsInput,
                draftDirectory = draftDirectory,
            )
        }

        @Suppress("EnumEntryName")
        enum class WaitEnd { idle, notIdle, userSpoke }

        /** Told to the delegator when the user's words ended its wait. */
        const val userSpokeNote = "The user started talking while you waited, so the wait ended. Answer now in a few words: the work is still going and its reply is spoken when it lands. Don't wait again in this request."

        fun heldNote(listed: Boolean): String =
            "This session is waiting on the request${if (listed) "" else " (list_requests shows it)"} and would hold anything sent until it's resolved. A message never answers it. When the user approved, picked, or answered, that is the answer: answer_question with their option values, or reply_permission. If they answered only some of a question's fields, ask about the rest before answering. A request for more or other work isn't an answer: dismiss_question with a note in their words. A permission they want something else instead of: reply_permission reject with a note in their words. Otherwise ask them. Then send again."

        private suspend fun waitEnd(within: Duration, userSpoke: suspend () -> Unit, wait: suspend () -> Unit): WaitEnd = coroutineScope {
            val waited = async {
                try {
                    wait()
                    WaitEnd.idle
                } catch (_: OpenCodeClientError) {
                    WaitEnd.notIdle
                }
            }
            val timer = async {
                delay(within)
                WaitEnd.notIdle
            }
            val spoke = async {
                userSpoke()
                WaitEnd.userSpoke
            }
            val first = select {
                waited.onAwait { it }
                timer.onAwait { it }
                spoke.onAwait { it }
            }
            coroutineContext.cancelChildren()
            first
        }

        /** An optional `note` argument, trimmed; null when empty. */
        private fun note(arguments: JsonObject): String? = arguments.string("note")?.trim()?.takeIf { it.isNotEmpty() }

        fun requests(pending: OpenCodePendingRequests, session: OpenCodeSession): Map<String, Any?> = mapOf(
            "session" to session.title,
            "permissions" to pending.permissions.map {
                mapOf("request_id" to it.id, "action" to it.action, "resources" to it.resources)
            },
            "questions" to pending.forms.map { form ->
                mapOf(
                    "form_id" to form.id,
                    "title" to form.title,
                    "fields" to form.fields.map { field ->
                        val item = linkedMapOf<String, Any?>("key" to field.key, "type" to field.type.name.lowercase())
                        field.title?.let { item["title"] = it }
                        field.description?.let { item["description"] = it }
                        field.options?.let { options -> item["options"] = options.map { mapOf("value" to it.value, "label" to it.label) } }
                        if (field.required == true) item["required"] = true
                        item
                    },
                )
            },
        )

        private fun string(arguments: JsonObject, key: String): String {
            val value = arguments.string(key)
            if (value.isNullOrEmpty()) throw OpenCodeClientError("Missing $key.")
            return value
        }

        /** The last path component, as `URL(fileURLWithPath:).lastPathComponent`. */
        fun folder(directory: String): String {
            var trimmed = directory
            while (trimmed.length > 1 && trimmed.endsWith("/")) trimmed = trimmed.dropLast(1)
            if (trimmed == "/") return "/"
            return trimmed.substringAfterLast('/')
        }

        /**
         * A prompt whose sending ended in doubt may still arrive: the delegator
         * hears that, with its ID, so it checks before sending it again.
         */
        fun addUnconfirmed(error: OpenCodeClientError, result: MutableMap<String, Any?>, key: String = "error") {
            val id = error.unconfirmedPromptID ?: return
            result[key] = error.message + " Check with read_session before sending it again."
            result["may_have_arrived"] = true
            result["message_id"] = id
        }

        /**
         * Compact JSON with sorted keys, as `JSONSerialization` (`.sortedKeys`)
         * writes it, slashes escaped (`\/`) as Foundation does.
         */
        fun json(value: Any?): String = Json.encodeToString(JsonElement.serializer(), jsonElement(value)).replace("/", "\\/")

        private fun jsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Int -> JsonPrimitive(value)
            is Long -> JsonPrimitive(value)
            is Double -> if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(
                value.entries.map { it.key.toString() to jsonElement(it.value) }.sortedBy { it.first }.toMap(LinkedHashMap()),
            )
            is Iterable<*> -> JsonArray(value.map(::jsonElement))
            is Array<*> -> JsonArray(value.map(::jsonElement))
            else -> JsonPrimitive(value.toString())
        }

        private fun permissionReply(raw: String): OpenCodePermissionReply? = when (raw) {
            "once" -> OpenCodePermissionReply.Once
            "always" -> OpenCodePermissionReply.Always
            "reject" -> OpenCodePermissionReply.Reject
            else -> null
        }

        /**
         * A form answer: a JSON bool (or a number Foundation bridges to `Bool`, exactly 0 or 1), a
         * number, a string, a list of strings, any other list stringified, else its text.
         */
        private fun formValue(value: JsonElement): OpenCodeFormValue = when (value) {
            is JsonPrimitive -> when {
                value.isString -> OpenCodeFormValue.Str(value.content)
                value.booleanOrNull != null -> OpenCodeFormValue.Boolean(value.booleanOrNull!!)
                value.doubleOrNull == 0.0 -> OpenCodeFormValue.Boolean(false)
                value.doubleOrNull == 1.0 -> OpenCodeFormValue.Boolean(true)
                value.doubleOrNull != null -> OpenCodeFormValue.Number(value.doubleOrNull!!)
                else -> OpenCodeFormValue.Str(value.content)
            }
            is JsonArray -> OpenCodeFormValue.Strings(value.map { (it as? JsonPrimitive)?.content ?: it.toString() })
            else -> OpenCodeFormValue.Str(value.toString())
        }

        /** Digits and other numeric characters. */
        private fun isNumber(char: Char): Boolean = char.isDigit() ||
            char.category == CharCategory.LETTER_NUMBER || char.category == CharCategory.OTHER_NUMBER
    }
}

/** `arguments[key] as? String`: a JSON string only. */
private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `arguments[key] as? Int`: a JSON number with a whole value (or a bool, as Foundation bridges it). */
private fun JsonObject.int(key: String): Int? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
    primitive.booleanOrNull?.let { return if (it) 1 else 0 }
    val number = primitive.doubleOrNull ?: return null
    if (number % 1.0 != 0.0 || number < Int.MIN_VALUE || number > Int.MAX_VALUE) return null
    return number.toInt()
}

/** `arguments[key] as? Bool`: a JSON bool, or a number Foundation bridges to one (0 or 1). */
private fun JsonObject.bool(key: String): Boolean? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull } ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.doubleOrNull) {
        0.0 -> false
        1.0 -> true
        else -> null
    }
}
