package ai.opencode.feature.system.liveactivity

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes.ContentState.Phase
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How a run started from this phone reads in its ongoing notification.
 *
 * Home (`:feature:drawer`) owns the tracking; the types above this module reach the mapping as
 * parameters, as the dictation mapping's do: [Run.outcome] is `WorkingRun.Outcome` mapped onto
 * [Outcome], and [preview] takes the Markdown's prose lines from the transcript's parser.
 */
object SessionLiveActivity {
    typealias Content = SessionActivityAttributes.ContentState

    /** How the run's turn ended. */
    enum class Outcome { done, failed, interrupted }

    /** What Home tracks for the one run the activity follows: from an accepted send until it settles or another send takes over. */
    data class Tracked(
        val attributes: SessionActivityAttributes,
        val content: Content,
        /** The agent's latest step, from the session's live events. */
        val step: String? = null,
        /** The server reported the run running at least once. */
        val sawServerRun: Boolean = false,
        /** The background wait found the session idle: these are its last items, read then. The stream may have stopped before its end arrived. */
        val settledItems: List<OpenCodeTranscriptItem>? = null,
    )

    /** The part of Home's state the activity shows; observing only this keeps transcript traffic from re-running the mapping. */
    data class Run(
        val title: String,
        /** The server lists the session running. */
        val isServerActive: Boolean,
        val isWorking: Boolean,
        val needsInput: Boolean,
        val step: String? = null,
        /** How the run's turn ended, once it stopped working, when its items are here. */
        val outcome: Outcome? = null,
        val preview: String? = null,
    )

    /** Items read after the background wait: enough for the last turn. */
    const val SETTLED_READ_LIMIT = 20
    /** How long the background wait may hold the app. */
    val backgroundWait: Duration = 25.seconds
    /** The Done preview's longest, in characters, ellipsis included. */
    const val PREVIEW_LENGTH = 80

    fun content(run: Run?, previous: Content?): Content? {
        if (run == null || previous == null) return null
        var next = previous.copy(title = run.title)
        // Over: only its preview (and title) can still catch up.
        if (next.phase.isSettled) {
            if (next.phase == Phase.done && run.preview != null) next = next.copy(preview = run.preview)
            return next
        }
        return when {
            run.needsInput -> next.copy(phase = Phase.needsInput, step = null)
            run.isWorking -> next.copy(phase = Phase.working, step = run.step)
            else -> when (run.outcome) {
                Outcome.failed -> next.copy(phase = Phase.failed, step = null)
                // Stopped from the app: nothing left to follow.
                Outcome.interrupted -> null
                Outcome.done, null -> next.copy(phase = Phase.done, step = null, preview = run.preview)
            }
        }
    }

    /**
     * The step a live event names: the tool by its row title, else what the agent is doing.
     * Never the text itself. Null leaves the step as it was.
     */
    fun step(events: List<OpenCodeLiveContentEvent>): String? {
        for (event in events.asReversed()) {
            when (event) {
                is OpenCodeLiveContentEvent.ToolInputStarted -> return OpenCodeToolCall.displayName(event.name)
                is OpenCodeLiveContentEvent.ReasoningStarted -> return localized(R.string.system_session_step_thinking)
                is OpenCodeLiveContentEvent.TextStarted -> return localized(R.string.system_session_step_replying)
                else -> continue
            }
        }
        return null
    }

    /**
     * The opening prose line of the last turn's last reply text, on one line and short, like a
     * notification preview. Null before that text arrives, or when it has none (only tools or
     * code). Never logged. [proseLines] gives a reply's paragraph, list item, numbered item,
     * blockquote and heading texts in order.
     */
    fun preview(
        items: List<OpenCodeTranscriptItem>,
        maxLength: Int = PREVIEW_LENGTH,
        proseLines: (markdown: String) -> Sequence<String>,
    ): String? {
        var reply: String? = null
        for (item in items.asReversed()) {
            if (item is OpenCodeTranscriptItem.UserText || item is OpenCodeTranscriptItem.UserImages || item is OpenCodeTranscriptItem.UserFiles) break
            if (item is OpenCodeTranscriptItem.AssistantMarkdown) {
                reply = item.text
                break
            }
        }
        reply ?: return null
        val line = proseLines(reply)
            .map { text -> text.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") }
            .firstOrNull { it.isNotEmpty() } ?: return null
        if (line.length <= maxLength) return line
        return line.take(maxLength - 1).trim(' ') + "…"
    }
}

class SessionLiveActivityClient(
    /**
     * Shows `content` in the run's one ongoing notification, starting it if needed; other
     * attributes end the last run's first. A settled phase ends it, still showing, a few
     * minutes later; null ends it at once. `attributes` are only read on a start.
     */
    val update: suspend (SessionActivityAttributes.ContentState?, SessionActivityAttributes?) -> Unit,
    /** Notifications a previous launch left behind: nothing tracks their runs now. */
    val endAll: suspend () -> Unit,
) {
    companion object Key : DependencyKey<SessionLiveActivityClient> {
        override val liveValue: SessionLiveActivityClient by lazy {
            SessionLiveActivityClient(
                update = { content, attributes -> sessionActivityStepThrottle.update(content, attributes) },
                endAll = { LiveSessionActivity.shared.enqueue(LiveSessionActivity.Request.EndAll) },
            )
        }

        override val testValue = SessionLiveActivityClient(update = { _, _ -> }, endAll = {})
    }
}

/**
 * Sends a working run's step changes at most once per [INTERVAL], since Android drops notification
 * updates that come too fast. The latest held step goes out when the interval ends or the app leaves
 * the foreground (Android reports that about 0.7 s after the last activity pauses); any other change
 * goes at once in its place.
 */
class SessionActivityStepThrottle(
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val send: suspend (SessionActivityAttributes.ContentState?, SessionActivityAttributes?) -> Unit,
) {
    private class Pushed(val content: SessionActivityAttributes.ContentState?, val attributes: SessionActivityAttributes?)

    private val mutex = Mutex()
    private var sent: Pushed? = null
    private var held: Pushed? = null
    private var window: Job? = null

    suspend fun flush() = mutex.withLock { sendHeld() }

    suspend fun update(content: SessionActivityAttributes.ContentState?, attributes: SessionActivityAttributes?) = mutex.withLock {
        if (window != null && content != null && isStepOnly(content, attributes)) {
            held = Pushed(content, attributes)
            return@withLock
        }
        held = null
        deliver(Pushed(content, attributes))
    }

    private fun isStepOnly(content: SessionActivityAttributes.ContentState, attributes: SessionActivityAttributes?): Boolean {
        val shown = sent?.takeIf { it.attributes == attributes }?.content ?: return false
        return shown.phase == Phase.working && content.phase == Phase.working && shown.copy(step = content.step) == content
    }

    private suspend fun sendHeld() {
        val held = held ?: return
        this.held = null
        deliver(held)
    }

    private suspend fun deliver(pushed: Pushed) {
        sent = pushed
        window?.cancel()
        window = if (pushed.content?.phase == Phase.working) openWindow() else null
        send(pushed.content, pushed.attributes)
    }

    private fun openWindow(): Job = scope.launch {
        clock.sleep(INTERVAL)
        mutex.withLock {
            window = null
            sendHeld()
        }
    }

    companion object {
        val INTERVAL: Duration = 1500.milliseconds
    }
}

private val sessionActivityStepThrottle: SessionActivityStepThrottle by lazy {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val throttle = SessionActivityStepThrottle(Clock.liveValue, scope) { content, attributes ->
        LiveSessionActivity.shared.enqueue(LiveSessionActivity.Request.Update(content, attributes))
    }
    scope.launch {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onPause(owner: LifecycleOwner) {
                scope.launch { throttle.flush() }
            }
        })
    }
    throttle
}

/**
 * Owns the one run notification. Requests run in order, so an end never overtakes the update before
 * it. While working, a heartbeat moves the stale date (the notification's timeout) so a run the app
 * still follows never reads as stale.
 *
 * Android has no background task to hold: the settle window runs on this object's scope, and a
 * settled run's notification is re-posted not ongoing with [SETTLED_LINGER] as its timeout.
 */
class LiveSessionActivity internal constructor(private val system: LiveUpdateTracker, private val context: android.content.Context) {
    typealias Content = SessionActivityAttributes.ContentState

    sealed interface Request {
        data class Update(val content: Content?, val attributes: SessionActivityAttributes?) : Request
        data object EndAll : Request
        data object Refresh : Request
        data class FinishSettled(val activityID: String) : Request
        data class Dismissed(val activityID: String) : Request
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runner = LiveActivityRunner(Clock.liveValue, scope)
    private var activityID: String? = null
    private var attributes: SessionActivityAttributes? = null
    /** The run on show, kept after it ends so its late updates change nothing. */
    private var content: Content? = null
    /** Swiped away, couldn't start, or already ended: not asked again for this run. */
    private var isDeclined = false
    private var pendingEnd: Job? = null

    init {
        scope.launch {
            LiveActivityActionReceiver.dismissalEvents
                .filter { it.kind == LiveActivityActionReceiver.Kind.SessionRun }
                .collect { enqueue(Request.Dismissed(it.activityID)) }
        }
    }

    suspend fun enqueue(request: Request) = runner.enqueue { apply(request) }

    private fun apply(request: Request) {
        when (request) {
            Request.EndAll -> endEverything()
            is Request.Update -> {
                val content = request.content
                if (content == null) {
                    endEverything()
                    return
                }
                // Another send took over (a run is its session and its start): the last run's notification goes at once.
                val attributes = request.attributes
                if (attributes != null && (attributes != this.attributes || content.startedAt != this.content?.startedAt)) {
                    endEverything()
                    this.attributes = attributes
                }
                show(content)
            }
            Request.Refresh -> {
                val content = content
                val id = activityID
                if (content == null || content.phase.isSettled || id == null || system.state(id)?.isShowing != true) {
                    runner.stopHeartbeat()
                    return
                }
                post(id, content, LiveVoiceActivity.staleDate())
            }
            is Request.FinishSettled -> {
                val id = request.activityID
                val content = content
                pendingEnd = null
                if (activityID != id || content == null || !content.phase.isSettled) return
                activityID = null
                isDeclined = true
                post(id, content, Instant.now().plusMillis(SETTLED_LINGER.inWholeMilliseconds), ended = true)
                record("session_activity_ended", "id=$id phase=${content.phase.rawValue}")
            }
            is Request.Dismissed -> system.dismissed(request.activityID)
        }
    }

    private fun show(content: Content) {
        this.content = content
        if (!content.phase.isSettled) cancelPendingEnd()
        val id = activityID
        if (id != null) {
            if (system.state(id)?.isShowing == true) {
                post(id, content, staleDate(content))
                if (content.phase.isSettled) settle(id) else runner.startHeartbeat { apply(Request.Refresh) }
                return
            }
            // Gone while it showed this run: the user swiped it away.
            activityID = null
            isDeclined = true
            runner.stopHeartbeat()
            cancelPendingEnd()
        }
        // A run that settled before it showed has nothing to follow.
        val attributes = attributes
        if (isDeclined || content.phase.isSettled || attributes == null) return
        try {
            val newID = system.request(LiveVoiceActivity.staleDate()) { activityID, timeout ->
                SessionRunLiveActivity.notification(context, activityID, attributes, content, timeout)
            }
            activityID = newID
            record("session_activity_started", "id=$newID")
            runner.startHeartbeat { apply(Request.Refresh) }
        } catch (error: Exception) {
            // Notifications off: the send goes without one.
            isDeclined = true
            record("session_activity_failed", LiveVoiceActivity.describe(error))
        }
    }

    private fun post(id: String, content: Content, staleDate: Instant, ended: Boolean = false) {
        val attributes = attributes ?: return
        system.update(id, staleDate) { activityID, timeout ->
            SessionRunLiveActivity.notification(context, activityID, attributes, content, timeout, ended)
        }
    }

    /** Ends the settled notification once its preview has had time to arrive. */
    private fun settle(id: String) {
        runner.stopHeartbeat()
        if (pendingEnd != null) return
        pendingEnd = scope.launch {
            delay(SETTLE_WINDOW)
            enqueue(Request.FinishSettled(id))
        }
    }

    /** A settled run shows until it's dismissed, even if the app is stopped before it ends. */
    private fun staleDate(content: Content): Instant =
        if (content.phase.isSettled) Instant.now().plusMillis(SETTLED_LINGER.inWholeMilliseconds) else LiveVoiceActivity.staleDate()

    private fun endEverything() {
        runner.stopHeartbeat()
        cancelPendingEnd()
        activityID?.let { record("session_activity_ended", "id=$it") }
        activityID = null
        attributes = null
        content = null
        isDeclined = false
        system.endAll()
    }

    private fun cancelPendingEnd() {
        pendingEnd?.cancel()
        pendingEnd = null
    }

    private fun record(name: String, detail: String) = AppLog.voice.info { "$name $detail" }

    companion object {
        /** A settled run's preview may still arrive (its settlement read) this long after; then it ends, still showing, for [SETTLED_LINGER]. */
        val SETTLE_WINDOW: Duration = 3.seconds
        val SETTLED_LINGER: Duration = 4.minutes

        internal val shared: LiveSessionActivity by lazy {
            val context = dependency(AppContext).applicationContext
            LiveSessionActivity(LiveUpdateSystem.sessionRun(context), context)
        }
    }
}
