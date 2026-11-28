package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.tuning.DeveloperMode
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The invisible agent behind a GPT-Live call: the voice agent hands it what
 * the user asked, and it acts on OpenCode with tools (list, open, read,
 * create, message, wait on, and stop sessions, and switch their model), then
 * returns what the voice agent should say. It has no session of its own and never appears in the
 * app; only its effects do (navigation, Voice messages in sessions).
 */
object VoiceDelegator {
    const val model = "gpt-6.1-sol"

    /**
     * Model rounds per request: a backstop for a run stuck in a loop, set well
     * above what real work takes (a few waits on a long job used up 8). The
     * last round asks for an answer instead of giving up.
     */
    const val maxRounds = 50

    /** Sent with the last round's tool results. */
    const val lastRoundNote = "That was your last tool round for this request. Answer now without tools: say what you did and where the work stands."

    /** The answer when even the last round asked for more tools. */
    const val outOfRoundsReply = "I stopped partway through that one. Any work I sent to a session keeps going."

    const val instructions = """You are the delegator behind a voice assistant for OpenCode, a coding agent.
The user talks to a voice agent in a phone app that shows ONE OpenCode session at a time. The voice agent hands you what the user asked; you act with tools and reply with what the voice agent should say.

You manage sessions; you never write code yourself. Coding work happens by sending a message to a session, whose own agent does the work.
- "This session", "here", "it" mean the session the call is on, the one the app shows (given in each request).
- Assume the user isn't looking at the screen: they may be walking, driving, or juggling several things. Each request says whether they can see it; unless it says they're viewing the session, never say "as you can see", "on screen", or "take a look", and never point at the app: name the session and say in words what changed, the key lines, and counts. When the user says they're looking at the screen, call set_user_looking with looking true; when they say they've stopped (driving, walking, the phone in a pocket), with looking false.
- The user may have several things running at once: keep track of which session each request and result belongs to, and name it in your answer.
- To switch what the user sees, call open_session (or new_session_draft). Do NOT call open_session just to message, read, or check another session—pass session_id to send_message, read_session, or list_requests so the user's view stays put unless they asked to switch.
- "Pin this", "unpin that": pin_session / unpin_session (no session_id means the one on screen). Pinned, unread, waiting, or running sessions: list_sessions with that filter. needs_input "unknown" means the app couldn't check; say so, don't guess.
- Messages steer by default. Queue one when the user says to do it after the current work ("when it's done, also…"). If a queued message should go now, steer_queued it; cancel_queued drops one.
- Pending requests: list_requests first, then tell the user exactly what's being asked. Approve (once, or always only when the user says so) or reject a permission only on the user's clear word. Answer a question with the user's answer, using its option values; if the user hasn't answered, ask them in your reply instead of guessing.
- The user's approval, pick, or answer to a pending request goes only through answer_question or reply_permission, never send_message: a message doesn't answer the request, and the session keeps waiting. When they answer some of a question's fields, ask about the rest, then answer the whole question at once.
- A session waiting on a permission or question takes no steering: anything sent waits behind the request, so send_message doesn't send and returns the request instead. Answer a question only with an option the user named; a request for more or other work isn't an answer: dismiss_question with a note in their words, then send. When they want something else instead of a permission, reply_permission reject with a note in their words, then send. Otherwise tell them what it's asking. Never answer, grant, or dismiss a request the user didn't speak to.
- To change the model a session uses ("use Opus", "switch to GPT-5.5 on high"), call list_models with a query to get its id, then switch_model (no session_id means the one on screen, or the draft's). Switch only when the user asks; if several models fit what they said, ask which one.
- When the user says they're done or says goodbye, call end_call and reply with a short goodbye.
- To start work, send_message to the right session (usually the viewed one), then answer right away that it started; the app speaks the session's reply when it lands. Only wait_for_session when the user asked for the result now or the work is a quick lookup.
- For any status or "did it respond?" question, always call read_session (or list_sessions) in this turn rather than answering from earlier turns or assuming work is still running.
- What a session already wrote, you read; never ask it to restate or recap it. A [truncated: …] marker means more text exists, not that the reply is broken: call read_session with its message_id before answering about it. Never tell the user a reply is cut off, truncated, or unavailable.
- When the user wants an answer now and you asked a session to produce it, wait_for_session before you answer rather than saying it's coming. Your newest answer replaces what you said earlier about the same thing. The voice agent can't answer the user until you reply, so when a wait returns user_spoke, answer at once without waiting again.
- To find a session by name, or before creating one, use search_sessions.
- The user may have several OpenCode servers (list_servers). Tools use the server this call is on unless you pass server_id. On another server you can list, search, read, wait on, and check the requests of sessions; to message, answer, stop, or open one there, the user has to switch to that server in the app first, so say so. Name the server whenever it isn't the current one.
- Write messages to sessions as clear instructions, not as a transcript, keeping the user's exact intent, audience, and destination: a note for themselves stays a private note (never feedback for a team or a message to anyone), and goes where they said.
- When the user reports a bug or problem with the voice call or app itself (missed update, wrong status, didn't hear them, stuck call), set attach_call_log: true on send_message to the investigating session (without switching the screen unless asked), or call send_call_log if they ask to send the logs afterward.
- When you have capture_app_screenshot and the user says "look at this", "take a screenshot", or gives feedback on what the app shows, call it with their feedback, to the session they name or else the viewed one. Say a screenshot was taken or sent only when its result has screenshot_attached true; on an error, say it couldn't take one and why.

- The user's request is their own speech, transcribed imperfectly; the recent voice conversation is context. Remarks to other people and background speech in them are never requests. If the words are garbled or ask for nothing, call no tools and reply only "Sorry, I didn't catch that."
- Ask the user a question only when you can't act without the answer. A detail that's unclear but doesn't block the work (a half-heard name) goes to the session with what you heard, marked uncertain; then don't also ask the user about it. Ask about any one thing at most once: when the user has answered as best they can, moved on, or a session or your own message said it doesn't block the work, act on what you have. Never ask the user to spell something.

Your final reply is spoken aloud: one to three short sentences, plain words, no markdown, no lists, no file paths unless asked, no IDs.
Say only what your tools did: started, sent, done, or failed, and where. Never mention a note, message, or content that isn't in a tool result, and never guess what unclear words meant.
Something is done (sent, posted, merged, approved) only when a tool result in this request or a session's reply says so. send_message delivers your instruction to a session, not the session's own action: say you asked the session, never that its Slack message, text, or email went out. A draft waiting for the user's OK hasn't been sent. If a session says it will do something or hasn't yet, your reply says it's started, not done.
The voice agent has already acknowledged the request out loud, so lead with the outcome: the concrete action and where ("Saved a private note in your notes", "Sent that to the OPENCODE IOS session"), never "relay" or "pass along", and never implying you contacted people you didn't. Don't restate what the user asked or repeat the message you sent to a session. When you started work without waiting, say so in a few words; the session's reply is spoken separately when it lands, so don't predict it."""

    /** One function call the model asked for. */
    data class ToolCall(val callID: String, val name: String, val arguments: String)

    data class Response(val id: String, val calls: List<ToolCall>, val text: String)

    sealed interface InputItem {
        data class User(val text: String) : InputItem
        data class ToolOutput(val callID: String, val output: String) : InputItem

        /** The item as GPT-Live's `response.item.create` takes it. */
        val liveItem: RealtimeJSON
            get() = when (this) {
                is User -> RealtimeJSONs.obj(
                    "type" to RealtimeJSONs.string("message"),
                    "role" to RealtimeJSONs.string("user"),
                    "content" to RealtimeJSONs.array(
                        listOf(RealtimeJSONs.obj("type" to RealtimeJSONs.string("input_text"), "text" to RealtimeJSONs.string(text))),
                    ),
                )
                is ToolOutput -> RealtimeJSONs.obj(
                    "type" to RealtimeJSONs.string("function_call_output"),
                    "call_id" to RealtimeJSONs.string(callID),
                    "output" to RealtimeJSONs.string(output),
                )
            }
    }

    /** A prompt a request sent into a session; its reply is spoken when it lands. */
    data class SentPrompt(
        val sessionID: String,
        val title: String,
        val promptID: String,
        /** What it asked, briefly. */
        val topic: String? = null,
    )

    /** Progress for the call log: model rounds and tool calls. */
    sealed interface Trace {
        data class RoundStarted(val round: Int) : Trace
        data class RoundEnded(val round: Int, val label: String, val detail: String?, val failed: Boolean) : Trace
        data class ToolStarted(val id: String, val name: String, val arguments: String) : Trace
        data class ToolEnded(val id: String, val result: String) : Trace
    }

    data class Outcome(
        val reply: String,
        val responseID: String?,
        /** Prompts whose replies the delegator didn't already wait for. */
        val unanswered: List<SentPrompt>,
        /**
         * GPT-Live already has the reply: its Responses delegation hands the
         * backend's answer to the voice agent itself (Server Voice).
         */
        val spoken: Boolean = false,
        /**
         * Why the request stopped after its messages were sent (a replaced
         * voice connection, say). They arrived and their replies are watched,
         * so `reply` says that; nothing the request read was told.
         */
        val interrupted: String? = null,
    )

    /**
     * The answer for a request that stopped after it sent messages: they
     * reached their sessions, whose replies are spoken when they land.
     */
    fun deliveredBeforeFailure(prompts: List<SentPrompt>): String {
        val titles = mutableListOf<String>()
        for (prompt in prompts) if (prompt.title !in titles) titles += prompt.title
        val names = titles.joinToString(" and ") { "“$it”" }
        return "The request reached $names, which is working on it; its reply is spoken when it lands. Don't say the request failed, and don't send it again."
    }

    private fun s(value: String) = JsonPrimitive(value)
    private fun prop(vararg pairs: Pair<String, JsonElement>) = JsonObject(linkedMapOf(*pairs))
    private fun enumOf(vararg values: String) = JsonArray(values.map(::s))
    private val serverIDProperty get() = prop("type" to s("string"), "description" to s("A server_id from list_servers; omit for the server this call is on"))
    private val stringProperty get() = prop("type" to s("string"))

    val tools: List<JsonObject>
        get() = listOf(
            function("list_servers", "The user's saved OpenCode servers: server_id, name, and current (the one this call is on). Pass server_id to list_sessions, search_sessions, read_session, wait_for_session, list_requests or list_projects to look at another server; its results name their server.", emptyMap()),
            function("list_sessions", "Recent root sessions, newest first. Each has working, unread, needs_input (a pending permission or question), pinned (with pinned_order), and on_screen; \"unknown\" means the app couldn't tell.", linkedMapOf(
                "limit" to prop("type" to s("integer"), "description" to s("Max sessions, default 15")),
                "pinned_only" to prop("type" to s("boolean"), "description" to s("Only the sessions pinned in the drawer")),
                "filter" to prop("type" to s("string"), "enum" to enumOf("pinned", "unread", "needs_input", "running"), "description" to s("Only pinned (in drawer order), unread, waiting on the user (needs_input true or unknown), or running (working) sessions")),
                "server_id" to serverIDProperty,
            )),
            function("search_sessions", "Find sessions by title or project, forgiving word order and mishearings, best match first, with list_sessions' status fields.", linkedMapOf(
                "query" to prop("type" to s("string"), "description" to s("Words from the title or project")),
                "limit" to prop("type" to s("integer"), "description" to s("Max sessions, default 8")),
                "server_id" to serverIDProperty,
            ), required = listOf("query")),
            function("pin_session", "Pin a session to the top of the drawer (defaults to the viewed one).", linkedMapOf("session_id" to stringProperty)),
            function("unpin_session", "Unpin a session from the drawer (defaults to the viewed one).", linkedMapOf("session_id" to stringProperty)),
            function("new_session_draft", "Show a new, empty session draft, optionally in a project folder; the user's next request there creates the session.", linkedMapOf(
                "directory" to prop("type" to s("string"), "description" to s("Absolute project folder from list_projects; omit for the most recent project")),
            )),
            function("list_projects", "Known project folders, most recently active first.", linkedMapOf("server_id" to serverIDProperty)),
            function("open_session", "Show a session on the user's screen.", linkedMapOf("session_id" to stringProperty), required = listOf("session_id")),
            function("read_session", "Recent messages and status of a session (defaults to the viewed one). The newest reply comes whole; older messages are excerpts, and anything cut ends with [truncated: N more characters; … message_id …]. Pass that message_id to read one message in full (offset continues a very long one).", linkedMapOf(
                "session_id" to stringProperty,
                "limit" to prop("type" to s("integer"), "description" to s("Messages, default 8")),
                "message_id" to prop("type" to s("string"), "description" to s("Read this one message in full, from a [truncated: …] marker")),
                "offset" to prop("type" to s("integer"), "description" to s("With message_id: the character to continue from, as its marker says")),
                "server_id" to serverIDProperty,
            )),
            function("send_message", "Send a message to a session's coding agent (defaults to the viewed one). delivery \"steer\" (default) reaches a running session at its next step; \"queue\" waits until the current work finishes. A steer to a session waiting on a permission or question isn't sent: the result is not_sent with its pending requests. Set attach_call_log to true when the user reports a bug or problem with this voice call or the app itself.", linkedMapOf(
                "text" to stringProperty,
                "session_id" to stringProperty,
                "delivery" to prop("type" to s("string"), "enum" to enumOf("steer", "queue")),
                "attach_call_log" to prop("type" to s("boolean"), "description" to s("Attach a snapshot of the current voice call's debug log (timeline, spans, request context, watch/notice events). Set true when the user reports a bug with the voice call or app.")),
            ), required = listOf("text")),
            function("send_call_log", "Attach and send a snapshot of the current voice call's debug log to a session (defaults to the viewed one) when the user asks to send the call logs.", linkedMapOf(
                "session_id" to stringProperty,
                "note" to prop("type" to s("string"), "description" to s("Short note for the session explaining what to investigate in the attached call log")),
            )),
            function("list_queued", "Messages waiting in a session (defaults to the viewed one), with their delivery.", linkedMapOf("session_id" to stringProperty)),
            function("steer_queued", "Turn a queued message into steering, so it reaches the running work at its next step.", linkedMapOf(
                "session_id" to stringProperty, "message_id" to stringProperty,
            ), required = listOf("message_id")),
            function("cancel_queued", "Cancel a message still waiting in a session.", linkedMapOf(
                "session_id" to stringProperty, "message_id" to stringProperty,
            ), required = listOf("message_id")),
            function("list_requests", "A session's pending permission requests and questions (defaults to the viewed one), with what each asks.", linkedMapOf(
                "session_id" to stringProperty, "server_id" to serverIDProperty,
            )),
            function("reply_permission", "Answer a permission request: once (allow this time), always (allow from now on), or reject.", linkedMapOf(
                "session_id" to stringProperty, "request_id" to stringProperty,
                "decision" to prop("type" to s("string"), "enum" to enumOf("once", "always", "reject")),
                "note" to prop("type" to s("string"), "description" to s("With reject: what the user wants instead, in their words")),
            ), required = listOf("request_id", "decision")),
            function("answer_question", "Answer a session's question form. answers maps each field key to a value: a string (an option's value for choices), number, boolean, or array of option values for multiselect.", linkedMapOf(
                "session_id" to stringProperty, "form_id" to stringProperty,
                "answers" to prop("type" to s("object")),
            ), required = listOf("form_id", "answers")),
            function("dismiss_question", "Dismiss or defer a session's question without answering, when the user wants something else instead of choosing (more options, later). The session carries on with the note.", linkedMapOf(
                "session_id" to stringProperty, "form_id" to stringProperty,
                "note" to prop("type" to s("string"), "description" to s("Why, in the user's words (\"show me more options first\")")),
            ), required = listOf("form_id")),
            function("end_call", "Hang up the voice call after your reply is spoken. Use when the user says they're done, or says goodbye.", emptyMap()),
            function("set_user_looking", "Record whether the user is looking at the phone's screen, when they say so (\"okay, I'm looking now\", \"I'm not looking at the screen\", \"I'm driving\"). Calls start assuming they aren't; it holds for the rest of the call, and each request then says whether they can see it.", linkedMapOf(
                "looking" to prop("type" to s("boolean"), "description" to s("true when they said they're looking at the screen, false when they said they aren't or stopped")),
            ), required = listOf("looking")),
            function("create_session", "Create a session in a project folder, open it, and optionally send its first message.", linkedMapOf(
                "directory" to prop("type" to s("string"), "description" to s("Absolute project folder from list_projects")),
                "text" to prop("type" to s("string"), "description" to s("Optional first message")),
            ), required = listOf("directory")),
            function("wait_for_session", "Wait until a session finishes its current work (max 90 s, ending early if the user starts talking), then return its latest reply.", linkedMapOf(
                "session_id" to stringProperty, "server_id" to serverIDProperty,
            )),
            function("interrupt_session", "Stop a session's current work.", linkedMapOf("session_id" to stringProperty)),
            function("list_models", "Models a session can use (defaults to the viewed one, or the draft), favorites first. Each has id (pass it to switch_model), name, provider, variants (reasoning efforts), favorite, and current.", linkedMapOf(
                "session_id" to stringProperty,
                "query" to prop("type" to s("string"), "description" to s("Words from the model's name, id, or provider")),
                "limit" to prop("type" to s("integer"), "description" to s("Max models, default 20")),
            )),
            function("switch_model", "Switch the model a session uses (defaults to the viewed one; on a draft, the model its session starts with). Keeps the current reasoning variant when the new model has it.", linkedMapOf(
                "model" to prop("type" to s("string"), "description" to s("A model id from list_models")),
                "variant" to prop("type" to s("string"), "description" to s("One of the model's variants from list_models; omit to keep the current one")),
                "session_id" to stringProperty,
            ), required = listOf("model")),
        ) + (if (DeveloperMode.isEnabled) developerTools else emptyList())

    /** Tools only Developer Mode offers: never in the schema otherwise. */
    val developerTools: List<JsonObject>
        get() = listOf(
            function("capture_app_screenshot", "Developer Mode: screenshot this app's own screen (never other apps) and send it, with a summary of the app's state, to a session (defaults to the viewed one) as the user's feedback on the app. Use when the user says \"look at this\", \"take a screenshot\", or gives feedback on what the app shows. Its result says whether the screenshot was taken and sent.", linkedMapOf(
                "text" to prop("type" to s("string"), "description" to s("The user's feedback on what's on screen, as a clear instruction")),
                "session_id" to prop("type" to s("string"), "description" to s("The session to send it to, such as one the user named; omit for the viewed one")),
                "attach_call_log" to prop("type" to s("boolean"), "description" to s("Also attach this voice call's debug log, when the feedback is about the call")),
                "attach_app_log" to prop("type" to s("boolean"), "description" to s("Also attach the app's recent log lines, when the user reports a bug")),
            )),
        )

    private fun function(name: String, description: String, properties: Map<String, JsonElement>, required: List<String> = emptyList()): JsonObject =
        prop(
            "type" to s("function"), "name" to s(name), "description" to s(description), "strict" to JsonPrimitive(false),
            "parameters" to prop(
                "type" to s("object"),
                "properties" to JsonObject(properties),
                "required" to JsonArray(required.map(::s)),
                "additionalProperties" to JsonPrimitive(false),
            ),
        )

    /**
     * Server Voice: the same delegator as GPT-Live's Responses delegation,
     * which OpenAI runs with the server's key (`live-delegation.md`). GPT-Live
     * writes each request from the conversation, so what the app shows rides
     * in the instructions and is updated as the screen moves.
     */
    fun responsesDelegation(context: String): LiveResponsesDelegation = LiveResponsesDelegation(
        model = model,
        instructions = serviceInstructions(context),
        tools = liveTools,
        reasoningEffort = "low",
    )

    fun serviceInstructions(context: String): String =
        "$instructions\n\nGPT-Live, the voice agent, hands you each request with the recent voice conversation. What the app shows right now (kept current as the screen moves and as the user can or can't see it):\n$context"

    /** The tools as JSON with sorted keys. */
    val liveTools: RealtimeJSON get() = sortedKeys(JsonArray(tools))

    private fun sortedKeys(json: JsonElement): JsonElement = when (json) {
        is JsonObject -> JsonObject(json.entries.sortedBy { it.key }.associateTo(linkedMapOf()) { it.key to sortedKeys(it.value) })
        is JsonArray -> JsonArray(json.map(::sortedKeys))
        else -> json
    }

    /** What the delegator is told for each request. */
    fun requestText(viewing: String, conversation: List<String>, request: String): String =
        "$viewing\nRecent voice conversation:\n${if (conversation.isEmpty()) "(none)" else conversation.joinToString("\n")}\n\n" +
            "The user's request: ${request.ifEmpty { "(see conversation)" }}"

    fun requestBody(input: List<InputItem>, previousResponseID: String?): JsonObject {
        val body = linkedMapOf<String, JsonElement>(
            "model" to s(model),
            "instructions" to s(instructions),
            "tools" to JsonArray(tools),
            "reasoning" to prop("effort" to s("low")),
            "input" to JsonArray(
                input.map { item ->
                    when (item) {
                        is InputItem.User -> prop("role" to s("user"), "content" to s(item.text))
                        is InputItem.ToolOutput -> prop("type" to s("function_call_output"), "call_id" to s(item.callID), "output" to s(item.output))
                    }
                },
            ),
        )
        if (previousResponseID != null) body["previous_response_id"] = s(previousResponseID)
        return JsonObject(body)
    }

    /** @throws OpenCodeClientError when the reply is unreadable or carries an error. */
    fun parse(data: ByteArray): Response {
        val json = try { VoiceWireJson.parseToJsonElement(data.decodeToString()) as? JsonObject } catch (_: Exception) { null }
            ?: throw OpenCodeClientError("The delegator's reply wasn't readable.")
        ((json["error"] as? JsonObject)?.get("message")?.stringValue)?.let { throw OpenCodeClientError(it) }
        val output = (json["output"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        val calls = output.mapNotNull { item ->
            if (item["type"]?.stringValue != "function_call") return@mapNotNull null
            val callID = item["call_id"]?.stringValue ?: return@mapNotNull null
            val name = item["name"]?.stringValue ?: return@mapNotNull null
            ToolCall(callID, name, item["arguments"]?.stringValue ?: "{}")
        }
        val text = output
            .filter { it["type"]?.stringValue == "message" }
            .flatMap { (it["content"] as? JsonArray)?.mapNotNull { part -> part as? JsonObject } ?: emptyList() }
            .filter { it["type"]?.stringValue == "output_text" }
            .mapNotNull { it["text"]?.stringValue }
            .joinToString(" ")
            .trim()
        return Response(json["id"]?.stringValue ?: "", calls, text)
    }
}

/** Server Voice: a Responses delegation's rounds, carried by the call. */
class VoiceDelegatorRounds(
    /** The next finished backend response: its tool calls, or its answer. A failure is an [OpenCodeClientError]. */
    val next: suspend (delegationID: String) -> Result<VoiceDelegator.Response>,
    /** Tool results (and notes) for the backend, then a request to continue. */
    val submit: suspend (delegationID: String, input: List<VoiceDelegator.InputItem>) -> Boolean,
)

/** What the delegator's tools can do in the app, supplied per request. */
class VoiceDelegatorTools(
    /** Runs one tool and returns its JSON result for the model. */
    val run: suspend (call: VoiceDelegator.ToolCall) -> String,
    /** Prompts sent so far, and which of them a wait already answered. */
    val sent: suspend () -> List<VoiceDelegator.SentPrompt>,
    /** Every prompt sent so far, answered or not. */
    val delivered: suspend () -> List<VoiceDelegator.SentPrompt> = { emptyList() },
    /** A step the voice agent should know about ("Opened “Upload fixes”"). */
    val note: suspend (text: String) -> Unit,
    val trace: suspend (VoiceDelegator.Trace) -> Unit = { },
)

class VoiceDelegatorClient(
    /**
     * Off in tests and the lab's scripted call, where delegations go straight
     * into the session on screen as before.
     */
    val isEnabled: Boolean,
    /** @throws OpenCodeClientError */
    val respond: suspend (apiKey: String, input: List<VoiceDelegator.InputItem>, previousResponseID: String?) -> VoiceDelegator.Response,
) {
    /** Model rounds until a spoken answer, running tools in between. @throws OpenCodeClientError */
    suspend fun run(
        apiKey: String,
        request: String,
        previousResponseID: String?,
        tools: VoiceDelegatorTools,
    ): VoiceDelegator.Outcome = Key.rounds(
        first = listOf(VoiceDelegator.InputItem.User(request)),
        previousResponseID = previousResponseID,
        spoken = false,
        tools = tools,
    ) { input, previous -> respond(apiKey, input, previous) }

    /**
     * Server Voice: the rounds of a delegation GPT-Live already started on its
     * Responses backend. Each round arrives over the call; the tools run here
     * and their results go back over it. The backend's answer reaches the
     * voice agent without the phone. @throws OpenCodeClientError
     */
    suspend fun runOnCall(
        delegationID: String,
        rounds: VoiceDelegatorRounds,
        tools: VoiceDelegatorTools,
    ): VoiceDelegator.Outcome = Key.rounds(first = emptyList(), previousResponseID = null, spoken = true, tools = tools) { input, _ ->
        if (input.isNotEmpty()) {
            if (!rounds.submit(delegationID, input)) {
                throw OpenCodeClientError("Couldn’t return the tool results to the voice call.")
            }
        }
        rounds.next(delegationID).getOrElse { throw it as? OpenCodeClientError ?: OpenCodeClientError(it.message ?: "") }
    }

    companion object Key : DependencyKey<VoiceDelegatorClient> {
        private suspend fun rounds(
            first: List<VoiceDelegator.InputItem>,
            previousResponseID: String?,
            spoken: Boolean,
            tools: VoiceDelegatorTools,
            respond: suspend (input: List<VoiceDelegator.InputItem>, previousResponseID: String?) -> VoiceDelegator.Response,
        ): VoiceDelegator.Outcome {
            var previous = previousResponseID
            var input = first
            for (round in 0 until VoiceDelegator.maxRounds) {
                if (!currentCoroutineContext().isActive) throw OpenCodeClientError("Cancelled")
                tools.trace(VoiceDelegator.Trace.RoundStarted(round))
                val response = try {
                    respond(input, previous)
                } catch (error: OpenCodeClientError) {
                    tools.trace(VoiceDelegator.Trace.RoundEnded(round, label = "Failed", detail = error.message, failed = true))
                    // Messages it already sent arrived, and the call watches for their
                    // replies: the request didn't fail for the user. `previous` has tool
                    // calls whose outputs never reached it, so the next request chains
                    // onto the last finished response instead.
                    val delivered = tools.delivered()
                    if (delivered.isEmpty() || !currentCoroutineContext().isActive) throw error
                    return VoiceDelegator.Outcome(
                        reply = VoiceDelegator.deliveredBeforeFailure(delivered),
                        responseID = previousResponseID,
                        unanswered = tools.sent(),
                        interrupted = error.message,
                    )
                }
                tools.trace(
                    VoiceDelegator.Trace.RoundEnded(
                        round,
                        label = if (response.calls.isEmpty()) "Answer" else "Plan: " + response.calls.joinToString(", ") { it.name },
                        detail = response.text.ifEmpty { null },
                        failed = false,
                    ),
                )
                previous = response.id.ifEmpty { previous }
                if (response.calls.isEmpty()) {
                    return VoiceDelegator.Outcome(
                        reply = response.text.ifEmpty { "Done." },
                        responseID = previous,
                        unanswered = tools.sent(),
                        spoken = spoken,
                    )
                }
                val outputs = mutableListOf<VoiceDelegator.InputItem>()
                for (call in response.calls) {
                    tools.trace(VoiceDelegator.Trace.ToolStarted(id = call.callID, name = call.name, arguments = call.arguments))
                    val output = tools.run(call)
                    tools.trace(VoiceDelegator.Trace.ToolEnded(id = call.callID, result = output))
                    outputs += VoiceDelegator.InputItem.ToolOutput(callID = call.callID, output = output)
                }
                if (round == VoiceDelegator.maxRounds - 2) outputs += VoiceDelegator.InputItem.User(VoiceDelegator.lastRoundNote)
                input = outputs
            }
            // The last round's tool outputs were never sent: chain onto the last finished response.
            return VoiceDelegator.Outcome(reply = VoiceDelegator.outOfRoundsReply, responseID = previousResponseID, unanswered = tools.sent())
        }

        private const val RESPONSES_URL = "https://api.openai.com/v1/responses"

        override val liveValue: VoiceDelegatorClient = VoiceDelegatorClient(isEnabled = true) { apiKey, input, previousResponseID ->
            val body = try {
                VoiceDelegator.requestBody(input, previousResponseID).encodedBytes()
            } catch (_: Exception) {
                throw OpenCodeClientError("Couldn’t build the delegator request.")
            }
            val request = Request.Builder()
                .url(RESPONSES_URL)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $apiKey")
                .post(body.toRequestBody(voiceJsonMediaType))
                .build()
            val client = dependency(OpenCodeTransport).request.withVoiceTimeout(60)
            // Planning has no side effects (tools run here, after the response), so a
            // lost response is safe to ask for again.
            val data = NetworkRetry.run(idempotency = RequestIdempotency.Idempotent) { _ ->
                val response = try {
                    client.voiceSend(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    throw OpenCodeClientError(error.localizedMessage ?: error::class.java.simpleName, transport = TransportFailure.of(error))
                }
                if (response.statusCode !in 200..<300) {
                    val detail = try {
                        ((VoiceWireJson.parseToJsonElement(response.body.decodeToString()) as? JsonObject)
                            ?.get("error") as? JsonObject)?.get("message")?.stringValue
                    } catch (_: Exception) {
                        null
                    }
                    throw OpenCodeClientError(
                        detail ?: "The delegator returned HTTP ${response.statusCode}.",
                        statusCode = response.statusCode,
                        retryAfter = OpenCodeClientError.retryAfter(response.header("Retry-After")),
                    )
                }
                response.body
            }
            VoiceDelegator.parse(data)
        }

        override val testValue: VoiceDelegatorClient = VoiceDelegatorClient(isEnabled = false) { _, _, _ ->
            throw OpenCodeClientError("The delegator isn't available in tests.")
        }
    }
}
