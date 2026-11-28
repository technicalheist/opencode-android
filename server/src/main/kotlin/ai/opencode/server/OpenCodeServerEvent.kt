package ai.opencode.server

import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeShell

/** The event subscriber fell behind: the rest of this connection is incomplete. Reconnect and hydrate from REST. */
class EventStreamOverflow : Exception("The event stream overflowed.") {
    override fun equals(other: Any?): Boolean = other is EventStreamOverflow
    override fun hashCode(): Int = 0
}

/** One semantic event from `/api/event`. */
sealed interface OpenCodeServerEvent {
    data object Connected : OpenCodeServerEvent
    data class ExecutionStarted(val sessionID: String) : OpenCodeServerEvent
    data class ExecutionEnded(val sessionID: String) : OpenCodeServerEvent

    /** [parentID] is set for a subagent: its family parent, which never changes. */
    data class SessionCreated(val sessionID: String, val parentID: String?) : OpenCodeServerEvent
    data class SessionRenamed(val sessionID: String, val title: String) : OpenCodeServerEvent

    /** `session.moved`: the session now lives in [directory] (Move to, or the server reviving one whose folder is gone). */
    data class SessionMoved(val sessionID: String, val directory: String) : OpenCodeServerEvent

    /** Another client (or this one) read a session up to [idle]. */
    data class SessionViewChanged(val sessionID: String, val idle: Long) : OpenCodeServerEvent
    data class SessionDeleted(val sessionID: String) : OpenCodeServerEvent

    /** `session.archived` / `session.unarchived`, from any client. */
    data class SessionArchiveChanged(val sessionID: String, val isArchived: Boolean) : OpenCodeServerEvent

    /** A delivery: [inputID] moved from the inbox into history. */
    data class InputQueueChanged(val sessionID: String, val inputID: String?) : OpenCodeServerEvent

    /** [input] is the admitted message itself, decoded like an inbox row, when the event carries one. */
    data class InputEnqueued(val sessionID: String, val input: OpenCodePendingInput?) : OpenCodeServerEvent
    data class InputCancelled(val sessionID: String, val inputID: String) : OpenCodeServerEvent

    /** A bounded, ordered projection batch: [EventBatcher] coalesces noisy deltas first. */
    data class ContentBatch(val sessionID: String, val events: List<OpenCodeLiveContentEvent>) : OpenCodeServerEvent

    /** A durable event changed projected history in a way cheaper and safer to settle from REST. */
    data class MessageInvalidated(val sessionID: String) : OpenCodeServerEvent

    /** An undo was staged ([messageID]), redone (null), or made final by a new prompt ([committed]). */
    data class RevertChanged(val sessionID: String, val messageID: String?, val committed: Boolean) : OpenCodeServerEvent

    /** A pending request appeared or was settled. [askedSessionID] is set only for a fresh ask. */
    data class AttentionChanged(val askedSessionID: String?) : OpenCodeServerEvent

    /** Shell events are ephemeral; reconnects re-list running shells. */
    data class ShellStarted(val shell: OpenCodeShell) : OpenCodeServerEvent
    data class ShellEnded(val shellID: String) : OpenCodeServerEvent

    /** `experimental.job.updated`: a job started, moved to the background, or ended. Ephemeral; reconnects re-list running jobs. */
    data class JobUpdated(val job: OpenCodeJob) : OpenCodeServerEvent

    /** A project's name, repository, or icon metadata changed on the server. */
    data class ProjectUpdated(val project: OpenCodeProject) : OpenCodeServerEvent

    /** `model.updated` or `provider.updated`: the server's model catalog changed (plugins loaded, config, a login). */
    data object ModelsChanged : OpenCodeServerEvent

    /** `agent.updated`: the server's agents changed. */
    data object AgentsChanged : OpenCodeServerEvent

    /** The single session this event is scoped to, when its ordering depends only on that session's pending deltas. */
    val targetSessionID: String?
        get() = when (this) {
            is ExecutionStarted -> sessionID
            is ExecutionEnded -> sessionID
            is SessionRenamed -> sessionID
            is SessionMoved -> sessionID
            is SessionViewChanged -> sessionID
            is SessionDeleted -> sessionID
            is SessionArchiveChanged -> sessionID
            is InputQueueChanged -> sessionID
            is InputEnqueued -> sessionID
            is InputCancelled -> sessionID
            is ContentBatch -> sessionID
            is MessageInvalidated -> sessionID
            is RevertChanged -> sessionID
            is ShellStarted -> shell.sessionID
            Connected, is SessionCreated, is AttentionChanged, is ShellEnded, is JobUpdated, is ProjectUpdated, ModelsChanged, AgentsChanged -> null
        }
}

sealed interface OpenCodeLiveContentEvent {
    val messageID: String

    data class StepStarted(override val messageID: String) : OpenCodeLiveContentEvent
    data class StepStreamed(override val messageID: String) : OpenCodeLiveContentEvent
    data class StepEnded(override val messageID: String) : OpenCodeLiveContentEvent
    data class TextStarted(override val messageID: String, val ordinal: Int) : OpenCodeLiveContentEvent
    data class TextDelta(
        override val messageID: String,
        val ordinal: Int,
        val delta: String,
        /** Includes this delta when the socket observed the part from its start. */
        val accumulatedText: String? = null,
    ) : OpenCodeLiveContentEvent
    data class TextEnded(override val messageID: String, val ordinal: Int, val text: String) : OpenCodeLiveContentEvent
    data class ReasoningStarted(override val messageID: String, val ordinal: Int) : OpenCodeLiveContentEvent
    data class ReasoningDelta(override val messageID: String, val ordinal: Int) : OpenCodeLiveContentEvent
    data class ReasoningEnded(override val messageID: String, val ordinal: Int) : OpenCodeLiveContentEvent
    data class ToolInputStarted(override val messageID: String, val toolID: String, val name: String) : OpenCodeLiveContentEvent
    data class ToolInputDelta(override val messageID: String, val toolID: String, val delta: String) : OpenCodeLiveContentEvent
    data class ToolInputEnded(override val messageID: String, val toolID: String, val text: String) : OpenCodeLiveContentEvent
    data class ToolCalled(override val messageID: String, val toolID: String, val input: OpenCodeJSONValue) : OpenCodeLiveContentEvent
    data class ToolProgress(override val messageID: String, val toolID: String, val metadata: Map<String, OpenCodeJSONValue>) : OpenCodeLiveContentEvent
    data class ToolSucceeded(
        override val messageID: String,
        val toolID: String,
        val output: List<String>,
        val metadata: Map<String, OpenCodeJSONValue>,
    ) : OpenCodeLiveContentEvent

    data class ToolFailed(
        override val messageID: String,
        val toolID: String,
        val error: String,
        val errorType: String?,
        val output: List<String>,
        val metadata: Map<String, OpenCodeJSONValue>,
    ) : OpenCodeLiveContentEvent

    val isDurableBoundary: Boolean
        get() = this is StepEnded || this is TextEnded || this is ToolInputEnded || this is ToolCalled ||
            this is ToolSucceeded || this is ToolFailed

    val isToolLifecycleEvent: Boolean
        get() = when (this) {
            is ToolInputStarted, is ToolCalled, is ToolSucceeded, is ToolFailed -> true
            else -> false
        }

    /** The same semantic part identity used by REST decoding and live upserts. */
    val transcriptItemID: String?
        get() = when (this) {
            is TextStarted -> "$messageID-text-$ordinal"
            is TextDelta -> "$messageID-text-$ordinal"
            is TextEnded -> "$messageID-text-$ordinal"
            is ToolInputStarted -> "$messageID-tool-$toolID"
            is ToolInputDelta -> "$messageID-tool-$toolID"
            is ToolInputEnded -> "$messageID-tool-$toolID"
            is ToolCalled -> "$messageID-tool-$toolID"
            is ToolProgress -> "$messageID-tool-$toolID"
            is ToolSucceeded -> "$messageID-tool-$toolID"
            is ToolFailed -> "$messageID-tool-$toolID"
            is StepStarted, is StepStreamed, is StepEnded, is ReasoningStarted, is ReasoningDelta, is ReasoningEnded -> null
        }

    val projectsRows: Boolean get() = transcriptItemID != null

    /** `this` followed by [next] as a single delta, when both stream into the same part. */
    fun appendingDelta(next: OpenCodeLiveContentEvent): OpenCodeLiveContentEvent? = when {
        this is TextDelta && next is TextDelta && accumulatedText == null && next.accumulatedText == null &&
            messageID == next.messageID && ordinal == next.ordinal ->
            TextDelta(messageID, ordinal, delta + next.delta)
        this is ReasoningDelta && next is ReasoningDelta && messageID == next.messageID && ordinal == next.ordinal -> this
        this is ToolInputDelta && next is ToolInputDelta && messageID == next.messageID && toolID == next.toolID ->
            ToolInputDelta(messageID, toolID, delta + next.delta)
        else -> null
    }
}

/**
 * Consecutive deltas for the same part merge into one event, so a burst of tokens re-parses
 * its row once. Only adjacent events merge; ordering relative to every other event is unchanged.
 */
fun MutableList<OpenCodeLiveContentEvent>.appendCoalescingDeltas(events: List<OpenCodeLiveContentEvent>) {
    for (event in events) {
        val merged = lastOrNull()?.appendingDelta(event)
        if (merged != null) this[lastIndex] = merged else add(event)
    }
}
