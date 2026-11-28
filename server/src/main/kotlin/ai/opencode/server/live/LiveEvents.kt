package ai.opencode.server.live

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.DiagnosticsErrorCategory
import ai.opencode.core.diagnostics.DiagnosticsFailurePhase
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.diagnostics.DiagnosticsStreamName
import ai.opencode.core.diagnostics.DiagnosticsStreamProgress
import ai.opencode.core.strings.localized
import ai.opencode.server.EventBatcher
import ai.opencode.server.EventBatcherHooks
import ai.opencode.server.EventEmission
import ai.opencode.server.EventStreamFocusClient
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.OpenCodeTransport
import ai.opencode.server.R
import ai.opencode.server.SelfViewReceipts
import ai.opencode.server.ServerLinkClient
import ai.opencode.core.log.AppLog
import ai.opencode.server.retry.TransportFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ai.opencode.server.internal.ElementSerializer
import ai.opencode.server.internal.WireJson
import ai.opencode.server.internal.obj
import ai.opencode.server.internal.opt
import ai.opencode.server.internal.str
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeProjectIcon
import ai.opencode.server.model.OpenCodeToolBounds
import ai.opencode.server.model.bounded
import ai.opencode.server.model.utf8Length
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.ServerContact
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Request

private const val MAX_FRAME_BYTES = 16 * 1024 * 1024

/**
 * Long-lived SSE subscription to `/api/event`. The flow throws on disconnect (an
 * [OpenCodeClientError], or [EventStreamOverflow] when the collector fell 128 events behind);
 * the caller owns the reconnect-and-rehydrate loop.
 */
internal fun subscribeEvents(server: OpenCodeServer): Flow<OpenCodeServerEvent> = flow {
    // The events sent and not yet taken by the collector. `callbackFlow` doesn't expose its
    // buffer's fill level; the batcher sheds deltas for sessions off screen once half the buffer is
    // waiting.
    val waiting = AtomicInteger(0)
    emitAll(eventSource(server, waiting).onEach { waiting.decrementAndGet() })
}

private fun eventSource(server: OpenCodeServer, waiting: AtomicInteger): Flow<OpenCodeServerEvent> = callbackFlow {
    val batches = EventBatcher(
        scope = this,
        emit = { event ->
            val queued = waiting.incrementAndGet()
            val result = trySend(event)
            when {
                result.isSuccess -> EventEmission.Enqueued(remaining = EventBatcher.BUFFER_CAPACITY - queued)
                result.isClosed -> {
                    waiting.decrementAndGet()
                    EventEmission.Terminated
                }
                else -> {
                    waiting.decrementAndGet()
                    // A missing delta makes the rest of this connection incomplete: finish so the
                    // subscriber reconnects and hydrates from REST rather than hiding a gap.
                    close(EventStreamOverflow())
                    EventEmission.Dropped
                }
            }
        },
        hooks = dependency(EventBatcherHooks),
        clock = dependency(Clock),
        isFocused = dependency(EventStreamFocusClient).contains,
        onOverflow = { close(it) },
    )
    val selfViewReceipts = dependency(SelfViewReceipts)
    val activeCall = AtomicReference<Call?>(null)
    val operationID = NetworkRetry.streamOperationID.current() ?: UUID.randomUUID().toString().uppercase()
    val stream = StreamDiagnostics(operationID)
    // On the retry clock, which a test can set (`NetworkRetry.clock`).
    val scriptedClock = NetworkRetry.clock.current()
    val watchdog = EventStreamWatchdog(scriptedClock ?: Clock.liveValue)
    val link = dependency(ServerLinkClient)
    val poolReset = PoolResetWatch(link)
    launch(Dispatchers.IO) {
        try {
            val url = server.endpoint("/api/event") ?: throw OpenCodeClientError(localized(R.string.server_error_invalid_url))
            val builder = Request.Builder().url(url).header("Accept", "text/event-stream")
            server.authorize(builder)
            builder.header("X-OpenCode-Op", operationID).header("X-OpenCode-Req", UUID.randomUUID().toString().uppercase())
            applyCapabilities(builder)
            val call = dependency(OpenCodeTransport).eventStream.newCall(builder.build())
            activeCall.set(call)
            // A deadline that passed before the call existed had nothing to cancel.
            if (watchdog.expiry != null || poolReset.fired) call.cancel()
            stream.record(DiagnosticsStreamName.ConnectStart)
            call.execute().use { response ->
                if (response.code == 401) {
                    stream.record(DiagnosticsStreamName.Disconnected, 401, DiagnosticsErrorCategory.Unauthorized, DiagnosticsFailurePhase.HttpStatus)
                    throw OpenCodeClientError(localized(R.string.server_error_credentials_rejected), statusCode = 401)
                }
                if (response.code !in 200..299) {
                    stream.record(DiagnosticsStreamName.Disconnected, response.code, DiagnosticsErrorCategory.of(response.code), DiagnosticsFailurePhase.HttpStatus)
                    throw OpenCodeClientError(localized(R.string.server_error_event_stream_http, response.code), statusCode = response.code)
                }
                stream.connected(response.code)
                ServerContact.shared.heard(server.persistenceID)
                selfViewReceipts.streamConnected(server.persistenceID)
                // The server frames one `data: {json}` line per event (no event:/id: fields, no
                // multi-line data) plus `: heartbeat` comment lines.
                val source = response.body.source()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    watchdog.noteLine()
                    link.noteStreamActivity(server)
                    stream.line(line)
                    if (!line.startsWith("data:")) continue
                    if (line.length - 5 > MAX_FRAME_BYTES ||
                        (line.length * 3 - 5 > MAX_FRAME_BYTES && utf8Length(line) - 5 > MAX_FRAME_BYTES)
                    ) {
                        throw OpenCodeClientError(localized(R.string.server_error_event_stream_oversized_frame))
                    }
                    // JSON tolerates the whitespace after `data:`.
                    val event = decodeStreamFrame(line.substring(5)) ?: continue
                    if (event is OpenCodeServerEvent.SessionViewChanged &&
                        selfViewReceipts.consumeEcho(server.persistenceID, event.sessionID, event.idle)
                    ) {
                        continue
                    }
                    if (event == OpenCodeServerEvent.ModelsChanged) OpenCodeReadCache.shared.forgetModelCatalogs(server.persistenceID)
                    batches.receive(event)
                }
            }
            batches.flush()
            stream.record(DiagnosticsStreamName.Disconnected)
            // Before the owner hears it ended, so its next request can't count a stream that's
            // gone as live (the finally covers cancellation).
            link.noteStreamEnded(server)
            close(OpenCodeClientError(localized(R.string.server_error_event_stream_ended)))
        } catch (cancelled: CancellationException) {
            stream.record(DiagnosticsStreamName.Disconnected, errorCategory = DiagnosticsErrorCategory.Cancelled, failurePhase = DiagnosticsFailurePhase.Cancelled)
            throw cancelled
        } catch (error: OpenCodeClientError) {
            batches.flush()
            link.noteStreamEnded(server)
            if (error.statusCode == null) stream.record(DiagnosticsStreamName.Disconnected, errorCategory = DiagnosticsErrorCategory.Transport)
            close(error)
        } catch (error: Exception) {
            batches.flush()
            link.noteStreamEnded(server)
            val category = DiagnosticsErrorCategory.of(error)
            if (category == DiagnosticsErrorCategory.Timeout) stream.record(DiagnosticsStreamName.Stalled, errorCategory = category)
            stream.record(DiagnosticsStreamName.Disconnected, errorCategory = category, failurePhase = DiagnosticsFailurePhase.BodyRead)
            val expiry = watchdog.expiry
            if (poolReset.fired) {
                // Fresh pools: a stream on the old one may be on a dead socket.
                AppLog.stream.notice { "Event stream: connections reset; reconnecting" }
                close(OpenCodeClientError.unreachable(TransportFailure.Interrupted))
            } else if (expiry != null) {
                // The watchdog cancelled the read; to the stream's owner it's a timeout, which it
                // retries, not a cancellation.
                AppLog.stream.notice { "Event stream ${if (expiry == EventStreamWatchdog.Expiry.Connect) "didn’t open" else "went silent"}; reconnecting" }
                DiagnosticsRecorder.shared.recordLink("sse_watchdog", expiry.rawValue)
                close(OpenCodeClientError.unreachable(TransportFailure.TimedOut))
            } else {
                close(streamTransportError(error))
            }
        } finally {
            watchdog.stop()
            poolReset.stop()
            link.noteStreamEnded(server)
        }
    }
    // The live clock sleeps with `delay`, so on the collector's dispatcher a test dispatcher would
    // skip its virtual time ahead to the deadline while the read waits on OkHttp's thread,
    // cancelling a stream that's about to answer. It runs on Dispatchers.Default instead, where
    // the deadlines are real seconds. A scripted clock is moved by its test, so it stays here.
    val watchdogScope = if (scriptedClock == null) this + Dispatchers.Default else this
    watchdog.start(watchdogScope) { activeCall.get()?.cancel() }
    poolReset.start(this) { activeCall.get()?.cancel() }
    awaitClose {
        watchdog.stop()
        poolReset.stop()
        activeCall.get()?.cancel()
    }
}.buffer(EventBatcher.BUFFER_CAPACITY)

/**
 * Bounds an event stream that stops talking. The server sends `server.connected` as soon as the
 * stream opens and a `: heartbeat` every 15 s, so a stream that hasn't opened in
 * [CONNECT_DEADLINE], or has been silent for [IDLE_DEADLINE] (three missed heartbeats), is
 * stranded on a dead route: a tunnel or NAT binding that went away without closing the socket
 * leaves a read waiting a minute or more otherwise.
 */
internal class EventStreamWatchdog(private val clock: Clock) {
    enum class Expiry(val rawValue: String) { Connect("connect"), Idle("idle") }

    private val lock = Any()
    private val origin = clock.now()

    /** When the last line arrived, as time since the watchdog was made. */
    private var lastLine: Duration? = null
    private var expiryValue: Expiry? = null
    private var timer: Job? = null
    private var isStopped = false

    /** Which deadline passed, if one did. */
    val expiry: Expiry? get() = synchronized(lock) { expiryValue }

    /** Starts timing in [scope]; [expire] runs once if a deadline passes. */
    fun start(scope: CoroutineScope, expire: () -> Unit) {
        val job = scope.launch {
            clock.sleep(CONNECT_DEADLINE)
            while (true) {
                val last = synchronized(lock) { lastLine }
                if (last == null) {
                    fire(Expiry.Connect, expire)
                    return@launch
                }
                // Measured from the last line, so silence ends it at the deadline however the
                // lines before it fell.
                val remaining = IDLE_DEADLINE - (clock.now() - origin - last)
                if (remaining <= Duration.ZERO) {
                    fire(Expiry.Idle, expire)
                    return@launch
                }
                clock.sleep(remaining)
            }
        }
        val stopped = synchronized(lock) {
            timer = job
            isStopped
        }
        if (stopped) job.cancel()
    }

    /** A line arrived: a frame or a heartbeat. */
    fun noteLine() {
        val now = clock.now() - origin
        synchronized(lock) { lastLine = now }
    }

    fun stop() {
        val job = synchronized(lock) {
            isStopped = true
            timer
        }
        job?.cancel()
    }

    private fun fire(expiry: Expiry, expire: () -> Unit) {
        val fires = synchronized(lock) {
            if (isStopped || expiryValue != null) {
                false
            } else {
                expiryValue = expiry
                true
            }
        }
        if (fires) expire()
    }

    companion object {
        val CONNECT_DEADLINE: Duration = 4500.milliseconds
        val IDLE_DEADLINE: Duration = 45.seconds
    }
}

/**
 * Ends an event stream when the connection pools are reset (`ServerLinkClient.resetConnections`):
 * the pools it was opened on are retired, and the socket under it may be the wedged one that
 * prompted the reset. A move to another address doesn't end it: a stream that's delivering is
 * the best evidence its address works.
 */
internal class PoolResetWatch(private val link: ServerLinkClient) {
    private val lock = Any()
    private val pools = link.epoch().pools
    private var firedValue = false
    private var isStopped = false
    private var task: Job? = null

    val fired: Boolean get() = synchronized(lock) { firedValue }

    fun start(scope: CoroutineScope, expire: () -> Unit) {
        val job = scope.launch {
            while (true) {
                val now = link.epoch()
                if (now.pools != pools) {
                    val fires = synchronized(lock) {
                        if (isStopped || firedValue) {
                            false
                        } else {
                            firedValue = true
                            true
                        }
                    }
                    if (fires) expire()
                    return@launch
                }
                link.changed(now.changes)
            }
        }
        val stopped = synchronized(lock) {
            task = job
            isStopped
        }
        if (stopped) job.cancel()
    }

    fun stop() {
        val job = synchronized(lock) {
            isStopped = true
            task
        }
        job?.cancel()
    }
}

/** The event stream's diagnostics marks: line counts and timings only. */
private class StreamDiagnostics(private val operationID: String) {
    private val recorder = DiagnosticsRecorder.shared
    private val requestID = UUID.randomUUID().toString().uppercase()
    private val progress = DiagnosticsStreamProgress(startedAt = recorder.now())
    private var disconnected = false

    fun record(
        name: DiagnosticsStreamName,
        statusCode: Int? = null,
        errorCategory: DiagnosticsErrorCategory? = null,
        failurePhase: DiagnosticsFailurePhase? = null,
    ) {
        if (name == DiagnosticsStreamName.Disconnected) {
            if (disconnected) return
            disconnected = true
        }
        recorder.stream(name, requestID, operationID, progress, statusCode, errorCategory, failurePhase)
    }

    fun connected(statusCode: Int) {
        progress.connectedAt = recorder.now()
        record(DiagnosticsStreamName.Connected, statusCode)
    }

    fun line(line: String) {
        if (!recorder.isEnabled) return
        val now = recorder.now()
        // Silence of 30 s or more between lines (heartbeats included) is a stall.
        val last = progress.lastLineAt
        if (last != null && now - last >= 30) record(DiagnosticsStreamName.Stalled)
        if (progress.receiveLine(utf8Length(line), now)) record(DiagnosticsStreamName.Resumed)
    }
}

private fun streamTransportError(error: Exception): OpenCodeClientError =
    transportError(error, ai.opencode.server.retry.TransportFailure.of(error))

/** The fields events carry; any type mismatch drops `data` for the frame. */
@Serializable
internal data class EventPayload(
    val sessionID: String? = null,
    val parentID: String? = null,
    /** `session.viewed`'s idle watermark, in epoch milliseconds. */
    val idle: Double? = null,
    val inboxID: String? = null,
    val title: String? = null,
    val form: Form? = null,
    val assistantMessageID: String? = null,
    val ordinal: Int? = null,
    val delta: String? = null,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    /** `session.execution.interrupted`'s cause (`"superseded"` when a steer interrupts the active step to start the next one). */
    val reason: String? = null,
    val canonical: String? = null,
    val repository: String? = null,
    val icon: OpenCodeProjectIcon? = null,
    val input: OpenCodeJSONValue? = null,
    val metadata: Map<String, OpenCodeJSONValue>? = null,
    val content: List<Content>? = null,
    val error: ErrorPayload? = null,
    val info: ShellInfo? = null,
    /** `session.revert.staged`'s boundary. */
    val revert: RevertPayload? = null,
    /** `session.moved`'s destination; other events' locations never spoil the rest of their payload. */
    @Serializable(with = LenientLocationSerializer::class) val location: Location? = null,
    /** `session.inbox.enqueued`'s admitted item; one the app can't read leaves the rest intact. */
    @Serializable(with = LenientInboxItemSerializer::class) val item: InboxItem? = null,
) {
    @Serializable data class Form(val sessionID: String)
    @Serializable data class Location(val directory: String? = null)
    @Serializable data class Content(val type: String, val text: String? = null)
    @Serializable data class InboxItem(val type: String, val payload: PendingInputsPayload.Payload? = null, val delivery: String? = null)

    val textOutput: List<String> get() = OpenCodeToolBounds.output(content?.filter { it.type == "text" }?.mapNotNull { it.text } ?: emptyList())
    val boundedMetadata: Map<String, OpenCodeJSONValue> get() = metadata?.let(OpenCodeToolBounds::metadata) ?: emptyMap()
}

internal object LenientLocationSerializer : ElementSerializer<EventPayload.Location?>(
    decode = { element -> runCatching { WireJson.decodeFromJsonElement(EventPayload.Location.serializer(), element) }.getOrNull() },
    encode = { location -> location?.let { WireJson.encodeToJsonElement(EventPayload.Location.serializer(), it) } ?: JsonNull },
)

internal object LenientInboxItemSerializer : ElementSerializer<EventPayload.InboxItem?>(
    decode = { element -> runCatching { WireJson.decodeFromJsonElement(EventPayload.InboxItem.serializer(), element) }.getOrNull() },
    encode = { item -> item?.let { WireJson.encodeToJsonElement(EventPayload.InboxItem.serializer(), it) } ?: JsonNull },
)

private val HANDLED_TYPES = setOf(
    "server.connected",
    "session.execution.started",
    "session.execution.succeeded", "session.execution.failed", "session.execution.interrupted",
    "session.created", "session.forked", "session.viewed",
    "session.revert.staged", "session.revert.cleared", "session.revert.committed",
    "session.renamed", "session.deleted", "session.archived", "session.unarchived",
    "session.inbox.cancelled", "session.inbox.enqueued", "session.inbox.delivery.changed", "session.inbox.delivered",
    "session.step.started", "session.step.streamed", "session.step.ended", "session.step.failed",
    "session.text.started", "session.text.delta", "session.text.ended",
    "session.reasoning.started", "session.reasoning.delta", "session.reasoning.ended",
    "session.tool.input.started", "session.tool.input.delta", "session.tool.input.ended",
    "session.tool.called", "session.tool.progress", "session.tool.success", "session.tool.failed",
    "session.message.content.updated", "session.synthetic", "session.instructions.updated",
    "session.agent.selected", "session.model.selected", "session.moved", "session.permissions",
    "session.retry.scheduled", "session.compaction.started",
    "session.compaction.ended", "session.compaction.failed", "session.shell.started", "session.shell.ended",
    "permission.asked", "form.created", "permission.replied", "form.replied", "form.cancelled",
    "shell.created", "shell.exited", "shell.deleted", "experimental.job.updated",
    "project.updated",
    "model.updated", "provider.updated", "agent.updated",
)

/**
 * One SSE `data:` payload: throws for a server failure frame (`_tag`), null for a frame the
 * app ignores. Unhandled event types skip decoding `data`.
 */
internal fun decodeStreamFrame(text: String): OpenCodeServerEvent? {
    val envelope = try { WireJson.parseToJsonElement(text).obj() } catch (_: Exception) { return null }
    val type: String?
    val tag: String?
    try {
        type = envelope.opt("type") { it.str() }
        tag = envelope.opt("_tag") { it.str() }
    } catch (_: RuntimeException) {
        return null
    }
    if (tag != null) throw OpenCodeClientError(localized(R.string.server_error_event_stream_server_failure))
    type ?: return null
    if (type !in HANDLED_TYPES) return null
    // A job's `info` isn't a shell's: it decodes on its own.
    if (type == "experimental.job.updated") return decodeJobUpdated(envelope["data"])
    val data: EventPayload? = envelope["data"]?.takeUnless { it is JsonNull }?.let(::decodePayload)
    return serverEvent(type, data)
}

private fun decodeJobUpdated(element: JsonElement?): OpenCodeServerEvent? {
    val info = (element as? JsonObject)?.get("info") ?: return null
    return try {
        OpenCodeServerEvent.JobUpdated(WireJson.decodeFromJsonElement(JobInfo.serializer(), info).job())
    } catch (_: Exception) {
        null
    }
}

private fun decodePayload(element: JsonElement): EventPayload? =
    try { WireJson.decodeFromJsonElement(EventPayload.serializer(), element) } catch (_: Exception) { null }

private fun serverEvent(type: String, data: EventPayload?): OpenCodeServerEvent? {
    fun content(make: (String) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
        val sessionID = data?.sessionID ?: return null
        val messageID = data.assistantMessageID ?: return null
        return OpenCodeServerEvent.ContentBatch(sessionID, listOf(make(messageID)))
    }
    fun ordinalContent(make: (String, Int) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
        val ordinal = data?.ordinal ?: return null
        return content { make(it, ordinal) }
    }
    fun toolContent(make: (String, String, EventPayload) -> OpenCodeLiveContentEvent): OpenCodeServerEvent? {
        val payload = data ?: return null
        val toolID = payload.id ?: return null
        return content { make(it, toolID, payload) }
    }
    return when (type) {
        "server.connected" -> OpenCodeServerEvent.Connected
        "session.execution.started" -> data?.sessionID?.let(OpenCodeServerEvent::ExecutionStarted)
        "session.execution.succeeded", "session.execution.failed" ->
            data?.sessionID?.let(OpenCodeServerEvent::ExecutionEnded)
        // A steer interrupts the active step with `reason == "superseded"` and immediately starts
        // the next one; the session hasn't settled.
        "session.execution.interrupted" ->
            if (data?.reason == "superseded") null else data?.sessionID?.let(OpenCodeServerEvent::ExecutionEnded)
        "session.created" -> data?.sessionID?.let { OpenCodeServerEvent.SessionCreated(it, data.parentID) }
        // A fork is a new root: its `parentID` names the session it was copied from, not a parent.
        "session.forked" -> data?.sessionID?.let { OpenCodeServerEvent.SessionCreated(it, null) }
        "session.viewed" -> {
            val id = data?.sessionID ?: return null
            val idle = data.idle ?: return null
            OpenCodeServerEvent.SessionViewChanged(id, idle.toLong())
        }
        "session.revert.staged" -> {
            val id = data?.sessionID ?: return null
            val boundary = data.revert?.messageID ?: return null
            OpenCodeServerEvent.RevertChanged(id, boundary, committed = false)
        }
        "session.revert.cleared", "session.revert.committed" ->
            data?.sessionID?.let { OpenCodeServerEvent.RevertChanged(it, null, committed = type == "session.revert.committed") }
        "session.renamed" -> {
            val id = data?.sessionID ?: return null
            val title = data.title ?: return null
            OpenCodeServerEvent.SessionRenamed(id, title)
        }
        "session.moved" -> {
            val id = data?.sessionID ?: return null
            // Home invalidates its messages too, as for the content-free events below.
            val directory = data.location?.directory ?: return OpenCodeServerEvent.MessageInvalidated(id)
            OpenCodeServerEvent.SessionMoved(id, directory)
        }
        "session.deleted" -> data?.sessionID?.let(OpenCodeServerEvent::SessionDeleted)
        "session.archived", "session.unarchived" ->
            data?.sessionID?.let { OpenCodeServerEvent.SessionArchiveChanged(it, isArchived = type == "session.archived") }
        "project.updated" -> {
            val id = data?.id ?: return null
            val canonical = data.canonical ?: return null
            OpenCodeServerEvent.ProjectUpdated(ProjectPayload(id, canonical, data.name, data.repository, data.icon).project())
        }
        "model.updated", "provider.updated" -> OpenCodeServerEvent.ModelsChanged
        "agent.updated" -> OpenCodeServerEvent.AgentsChanged
        "session.inbox.cancelled" -> {
            val id = data?.sessionID ?: return null
            val inputID = data.inboxID ?: return null
            OpenCodeServerEvent.InputCancelled(id, inputID)
        }
        "session.inbox.enqueued", "session.inbox.delivery.changed" -> {
            val id = data?.sessionID ?: return null
            // The admitted message rides on the event, so it can show before an inbox read.
            val input = if (type == "session.inbox.enqueued") {
                val inboxID = data.inboxID
                val item = data.item
                if (inboxID != null && item != null) {
                    pendingInputRows(
                        PendingInputsPayload(listOf(PendingInputsPayload.Item(inboxID, item.type, item.payload, item.delivery))),
                    ).firstOrNull()
                } else null
            } else null
            OpenCodeServerEvent.InputEnqueued(id, input)
        }
        "session.inbox.delivered" -> data?.sessionID?.let { OpenCodeServerEvent.InputQueueChanged(it, data.inboxID) }
        "session.step.started" -> content(OpenCodeLiveContentEvent::StepStarted)
        "session.step.streamed" -> content(OpenCodeLiveContentEvent::StepStreamed)
        "session.step.ended", "session.step.failed" -> content(OpenCodeLiveContentEvent::StepEnded)
        "session.text.started" -> ordinalContent(OpenCodeLiveContentEvent::TextStarted)
        "session.text.delta" -> {
            val delta = data?.delta ?: return null
            ordinalContent { id, ordinal -> OpenCodeLiveContentEvent.TextDelta(id, ordinal, delta) }
        }
        "session.text.ended" -> {
            val text = data?.text ?: return null
            ordinalContent { id, ordinal -> OpenCodeLiveContentEvent.TextEnded(id, ordinal, text) }
        }
        "session.reasoning.started" -> ordinalContent(OpenCodeLiveContentEvent::ReasoningStarted)
        "session.reasoning.delta" -> ordinalContent(OpenCodeLiveContentEvent::ReasoningDelta)
        "session.reasoning.ended" -> ordinalContent(OpenCodeLiveContentEvent::ReasoningEnded)
        "session.tool.input.started" -> {
            val name = data?.name ?: return null
            toolContent { messageID, toolID, _ -> OpenCodeLiveContentEvent.ToolInputStarted(messageID, toolID, name) }
        }
        "session.tool.input.delta" ->
            toolContent { messageID, toolID, payload -> OpenCodeLiveContentEvent.ToolInputDelta(messageID, toolID, payload.delta ?: "") }
        "session.tool.input.ended" ->
            toolContent { messageID, toolID, payload -> OpenCodeLiveContentEvent.ToolInputEnded(messageID, toolID, (payload.text ?: "").bounded(OpenCodeToolBounds.TEXT_LIMIT)) }
        "session.tool.called" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolCalled(messageID, toolID, payload.input?.bounded() ?: OpenCodeJSONValue.Object(emptyMap()))
        }
        "session.tool.progress" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolProgress(messageID, toolID, payload.boundedMetadata)
        }
        "session.tool.success" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolSucceeded(messageID, toolID, payload.textOutput, payload.boundedMetadata)
        }
        "session.tool.failed" -> toolContent { messageID, toolID, payload ->
            OpenCodeLiveContentEvent.ToolFailed(
                messageID, toolID, payload.error?.message ?: localized(R.string.server_tool_failed), payload.error?.type,
                payload.textOutput, payload.boundedMetadata,
            )
        }
        "session.message.content.updated", "session.synthetic", "session.instructions.updated",
        "session.agent.selected", "session.model.selected", "session.permissions",
        "session.retry.scheduled", "session.compaction.started",
        "session.compaction.ended", "session.compaction.failed", "session.shell.started", "session.shell.ended",
        -> data?.sessionID?.let(OpenCodeServerEvent::MessageInvalidated)
        "permission.asked", "form.created" -> {
            val id = data?.sessionID ?: data?.form?.sessionID ?: return null
            if (id == "global") null else OpenCodeServerEvent.AttentionChanged(id)
        }
        "permission.replied", "form.replied", "form.cancelled" ->
            if (data?.sessionID != null) OpenCodeServerEvent.AttentionChanged(null) else null
        "shell.created" -> {
            val info = data?.info ?: return null
            if (info.status != "running") null else OpenCodeServerEvent.ShellStarted(info.shell(isListed = false))
        }
        "shell.exited", "shell.deleted" -> data?.id?.let(OpenCodeServerEvent::ShellEnded)
        else -> null
    }
}
