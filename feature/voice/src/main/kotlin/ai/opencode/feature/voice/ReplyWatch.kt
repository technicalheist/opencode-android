package ai.opencode.feature.voice

import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.retry.RetryPolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Pure state machine for delegated session prompts: tracks which sessions are
 * running, reconciles `/wait` with SSE and `/api/session/active` snapshots,
 * resolves every prompt to an explicit outcome, and gates spoken notices on
 * call phase and speaker quiet time.
 *
 * An immutable value whose [transition] returns the next value and its effects. The quiet gate
 * ([QuietGate]) lives in `VoiceQuietGate.kt`; the result limits and notice copy in
 * `VoiceWorkClient.kt`.
 */
data class ReplyWatch(
    val watched: List<Prompt> = emptyList(),
    val completed: List<Completed> = emptyList(),
    val activeSessionIDs: Set<String> = emptySet(),
    val callPhase: CallPhase = CallPhase.connecting,
    val resolvedPromptIDs: Set<String> = completed.mapTo(LinkedHashSet()) { it.promptID },
    val connectingNotices: List<HeldNotice> = emptyList(),
    /**
     * What the delegator reported for a session's run, so a reply from the
     * same run asks only for the rest.
     */
    val delegatorReplies: Map<String, DelegatorReply> = emptyMap(),
    /**
     * Results held for a delegator request that read their session or is
     * waiting on it.
     */
    val deferredNotices: List<HeldNotice> = emptyList(),
    /**
     * Delegator requests running, what each has read so far, and the
     * sessions each is waiting on.
     */
    val delegatorsInFlight: Set<String> = emptySet(),
    val pendingAnswers: Map<String, List<PendingAnswer>> = emptyMap(),
    val delegatorWaits: Map<String, Set<String>> = emptyMap(),
    /**
     * Final user turns and delegations so far this call, so a result can say
     * which earlier request it answers.
     */
    val userTurns: Int = 0,
) {
    data class Prompt(
        val promptID: String,
        val sessionID: String,
        val title: String,
        val failures: Int = 0,
        /**
         * True once SSE or an active-session snapshot has reported the session
         * idle and a direct reply fetch is in flight.
         */
        val isFetchingSettledReply: Boolean = false,
        /** What was asked, briefly, so a reply answering several requests can name them. */
        val topic: String? = null,
        /**
         * `ReplyWatch.userTurns` when it was said: a result landing after the
         * conversation went on names what it was about.
         */
        val turn: Int = 0,
        /**
         * `userTurns` when the delegation that sent it was made, when known;
         * otherwise it counts from when it was sent.
         */
        val saidTurn: Int? = null,
        /**
         * Its own read came back (this text, empty for none) while a later
         * prompt steered into the same run hadn't: the run resolves on that one.
         */
        val ownReply: String? = null,
        /**
         * Set when this is work a watched prompt's session started in the
         * background (a background subagent), watched in its own session.
         */
        val background: VoiceBackgroundWork? = null,
        /**
         * Set when watching a session's current turn without a phone-sent prompt ID
         * (`ios_voice_watch_session`).
         */
        val readsLatestSessionReply: Boolean = false,
    ) {
        val id: String get() = promptID

        /**
         * Watched turns not sent as phone-steered prompts in the session's run:
         * they settle on their own without blocking or absorbing turn prompts.
         */
        val isIndependentWatch: Boolean get() = readsLatestSessionReply || background != null

        companion object {
            operator fun invoke(sent: VoiceDelegator.SentPrompt, saidTurn: Int? = null): Prompt =
                Prompt(promptID = sent.promptID, sessionID = sent.sessionID, title = sent.title, topic = sent.topic, saidTurn = saidTurn)

            /** Background work [title]'s session started for a prompt said at [saidTurn]. */
            operator fun invoke(work: VoiceBackgroundWork, title: String, saidTurn: Int): Prompt = Prompt(
                promptID = "background-${work.sessionID}",
                sessionID = work.sessionID,
                title = title,
                topic = work.description,
                saidTurn = saidTurn,
                background = work,
            )
        }
    }

    sealed interface Outcome {
        data class Replied(val reply: String) : Outcome
        data object FinishedWithoutText : Outcome
        data class ReplyUnreadable(val reason: String) : Outcome
        data class Failed(val message: String) : Outcome
        data class Unreachable(val message: String) : Outcome

        val isFailure: Boolean
            get() = when (this) {
                is Replied, FinishedWithoutText -> false
                is ReplyUnreadable, is Failed, is Unreachable -> true
            }

        val contextSummary: String
            get() = when (this) {
                is Replied -> "replied: ${VoiceWorkClient.excerpt(LiveContentChunker.collapseCodeBlocks(reply), limit = 240)}"
                FinishedWithoutText -> "finished without a text reply"
                is ReplyUnreadable -> "finished (reply unreadable: $reason)"
                is Failed -> "failed: $message"
                is Unreachable -> "server unreachable: $message"
            }

        fun logMarker(title: String, promptID: String? = null): String {
            val to = promptID?.let { " to $it" } ?: ""
            return when (this) {
                is Replied -> "Reply from “$title”$to: ${VoiceWorkClient.excerpt(reply, limit = 240)}"
                FinishedWithoutText -> "“$title” finished without text${promptID?.let { " ($it)" } ?: ""}"
                is ReplyUnreadable -> "“$title” reply unreadable: $reason"
                is Failed -> "“$title” failed: $message"
                is Unreachable -> "Gave up waiting on “$title”: $message"
            }
        }
    }

    data class Completed(
        val promptID: String,
        val sessionID: String,
        val title: String,
        val outcome: Outcome,
        /** What the background work was, for background work's result. */
        val background: String? = null,
    ) {
        val id: String get() = promptID
    }

    /**
     * A result waiting to be announced. Its text is written as it goes out,
     * so it says what the delegator reported for the same run by then.
     */
    data class HeldNotice(
        val promptID: String,
        /** Every prompt it answers (one, or several that settled in one run). */
        val promptIDs: List<String>,
        val sessionID: String,
        val title: String,
        val outcome: Outcome,
        val topics: List<String>,
        val turn: Int,
        /** Set while it's held for a delegator request. */
        val deferralKey: String? = null,
        /**
         * The running delegator requests that read its session or are waiting
         * on it: it waits for their answer, up to `claimedResultLimit`. Empty
         * while held only behind other foreground delegator turns.
         */
        val heldBy: Set<String> = emptySet(),
        /**
         * The background work, when it's background work's result: a request
         * reading the session that started it holds it too.
         */
        val background: VoiceBackgroundWork? = null,
    ) {
        val id: String get() = promptID

        /** Its own session's, or background work the session started. */
        fun isAbout(sessionID: String): Boolean = this.sessionID == sessionID || background?.parentSessionID == sessionID

        /** Without the prompts in [answered], unless that would leave none. */
        fun removing(answered: Set<String>): HeldNotice {
            val kept = promptIDs.indices.filter { promptIDs[it] !in answered }
            if (kept.isEmpty() || kept.size >= promptIDs.size) return this
            val keptIDs = kept.map { promptIDs[it] }
            return copy(
                promptIDs = keptIDs,
                topics = if (topics.size == promptIDs.size) kept.map { topics[it] } else topics,
                promptID = keptIDs.lastOrNull() ?: promptID,
            )
        }
    }

    /**
     * What the delegator reported for a session's run, kept only while
     * results of that same run are still to be announced.
     */
    data class DelegatorReply(val reply: String, val promptIDs: Set<String>)

    enum class CallPhase(val rawValue: String) {
        connecting("connecting"),
        live("live"),
        ending("ending"),
        ended("ended"),
    }

    enum class SettlementSource(val rawValue: String) {
        sse("sse"),
        stepEnded("step"),
        snapshot("snapshot"),
        reconnect("reconnect"),
    }

    sealed interface WaitError {
        data class Retryable(val message: String) : WaitError
        data class ReplyUnreadable(val reason: String) : WaitError
        data class Failed(val message: String) : WaitError

        companion object {
            operator fun invoke(error: OpenCodeClientError): WaitError = when {
                error.message == "The voice request is no longer in the recent transcript." ||
                    error.message.startsWith("Couldn’t decode ") -> ReplyUnreadable(error.message)
                error.isRetryableWaitError -> Retryable(error.message)
                else -> Failed(error.message)
            }
        }
    }

    sealed interface LogEntry {
        data class WatchStarted(val promptID: String, val sessionID: String, val title: String) : LogEntry
        data class Settled(val promptID: String, val title: String, val source: SettlementSource) : LogEntry
        data class Retry(val promptID: String, val title: String, val attempt: Int, val error: String) : LogEntry
        data class Resolved(val promptID: String, val title: String, val outcome: Outcome) : LogEntry
        data class NoticeSuppressed(val title: String) : LogEntry
        data class NoticeDropped(val title: String, val phase: CallPhase) : LogEntry
    }

    sealed interface Effect {
        data class StartWait(val prompt: Prompt) : Effect
        data class FetchSettledReply(val prompt: Prompt, val source: SettlementSource) : Effect
        data class ProbeStepEnded(val prompt: Prompt) : Effect
        data class RetryWait(val prompt: Prompt, val delaySeconds: Int) : Effect
        data class CancelWatch(val promptID: String) : Effect
        data class RefreshActiveSessions(val source: SettlementSource) : Effect
        data class Announce(val sessionID: String, val promptIDs: List<String>, val text: String) : Effect

        /**
         * A result held for a delegator request goes out after this long anyway
         * (`deferralExpired`), or the timer is cancelled once it's released.
         */
        data class ExpireDeferral(val key: String, val after: Duration) : Effect
        data class CancelDeferralExpiry(val key: String) : Effect

        /** Drops held announcements that answer only these prompts. */
        data class CancelWireAnnouncements(val promptIDs: List<String>) : Effect

        /**
         * A prompt's run finished: background work it started that's still
         * running is watched too (`sent`), so its result is spoken when it lands.
         */
        data class FindBackgroundWork(val sessionID: String, val promptID: String, val title: String, val turn: Int, val reply: String?) : Effect
        data class Log(val entry: LogEntry) : Effect
    }

    sealed interface Event {
        data class Sent(val prompts: List<Prompt>) : Event

        /**
         * Prompts one delegation sent for turns said before its latest
         * (`heldBackTurns`): each counts from its own turn.
         */
        data class HeldBack(val turns: Map<String, Int>) : Event
        data class SseStepEnded(val sessionID: String) : Event
        data class SseExecutionEnded(val sessionID: String, val source: SettlementSource = SettlementSource.sse) : Event
        data class ActiveSessionsSnapshot(val ids: Set<String>, val source: SettlementSource = SettlementSource.snapshot) : Event
        data class WaitSucceeded(val promptID: String, val reply: String?) : Event
        data class WaitFailed(val promptID: String, val error: WaitError, val sessionStillActive: Boolean? = null) : Event

        /**
         * A newer steer into the same session replaced this prompt: cancel its
         * watch so only the latest steer's settled reply speaks.
         */
        data class Superseded(val promptID: String) : Event
        data object Reconnected : Event
        data class CallPhaseChanged(val phase: CallPhase) : Event

        /**
         * A delegator request read [reply] for exactly these prompts (the ones it
         * waited on or read the reply to); other prompts in the session still
         * speak. It counts once the request's answer is spoken (`delegatorFinished`).
         */
        data class DelegatorAnswered(
            val sessionID: String,
            val reply: String? = null,
            val promptIDs: List<String>,
            val requestID: String,
        ) : Event

        /**
         * A delegator request started, or finished with the answer the voice
         * speaks (null when it failed: then nothing it read counts as told).
         * A result from a session it read or is waiting on waits for it, so the
         * answer and the result never say the same thing twice.
         */
        data class DelegatorStarted(val requestID: String) : Event
        data class DelegatorFinished(val requestID: String, val spoken: String?) : Event

        /** A delegator request began or ended a `wait_for_session` on a session. */
        data class DelegatorWaiting(val sessionID: String, val requestID: String, val isWaiting: Boolean) : Event

        /** A held result's deadline (`claimedResultLimit` from arrival) ran out: it speaks now. */
        data class DeferralExpired(val key: String) : Event
    }

    /** What a delegator request read for a session, applied once it answers. */
    data class PendingAnswer(val sessionID: String, val reply: String?, val promptIDs: List<String>)

    /**
     * The user finished a turn or a delegation started: results for earlier
     * requests name what they were about.
     */
    fun noteUserActed(): ReplyWatch = copy(userTurns = userTurns + 1)

    fun isWatching(sessionID: String): Boolean = watched.any { it.sessionID == sessionID }

    fun awaitsParentRelay(sessionID: String): Boolean = watched.any { it.background?.parentSessionID == sessionID }

    fun hasSettledWatch(activeSessionIDs: Set<String>): Boolean =
        watched.any { it.sessionID !in activeSessionIDs && !it.isFetchingSettledReply }

    fun isSessionRunning(sessionID: String): Boolean = sessionID in activeSessionIDs || isWatching(sessionID)

    /**
     * Pure status header for the delegator's request context, derived from live
     * session run state, tracked prompts, and recent completions rather than
     * whether another delegator tool turn is in flight.
     */
    fun statusSummary(
        viewing: OpenCodeSession?,
        needsInput: Boolean = false,
        draftDirectory: String,
        hasDirectVoiceWork: Boolean = false,
        otherDelegatorRequestsInFlight: Int = 0,
        screen: VoiceScreenVisibility = VoiceScreenVisibility.notLooking,
    ): String {
        val lines = mutableListOf<String>()
        if (viewing != null) {
            val running = isSessionRunning(viewing.id) || hasDirectVoiceWork
            val place = "session \"${viewing.title}\" (id ${viewing.id}, project ${VoiceDelegatorWorkspace.folder(viewing.directory)}, ${if (running) "working" else "idle"})."
            var line = if (screen == VoiceScreenVisibility.visible) "The user is viewing $place" else "The call is attached to $place"
            if (needsInput) {
                line += " It is waiting on a pending request: the user's approval or answer goes through list_requests and answer_question or reply_permission, never send_message."
            }
            lines += line
        } else {
            lines += if (screen == VoiceScreenVisibility.visible) {
                "The user is on a new, empty session draft in the project folder $draftDirectory."
            } else {
                "The call is on a new, empty session draft in the project folder $draftDirectory."
            }
        }
        screen.unseenNote?.let { lines += it }
        if (watched.isNotEmpty() || completed.isNotEmpty()) {
            lines += "Tracked session work from this call:"
            for (prompt in watched) {
                val work = prompt.background
                if (work != null) {
                    lines += "- Background work “${work.description}” that \"${prompt.title}\" started (subagent session id ${prompt.sessionID}): working; its result is spoken when it lands."
                    continue
                }
                val liveState = if (prompt.sessionID in activeSessionIDs) "active on server" else "finishing / checking reply"
                lines += "- \"${prompt.title}\" (id ${prompt.sessionID}, prompt ${prompt.promptID}): working (live status: $liveState)."
            }
            for (item in completed.takeLast(4)) {
                // An excerpt, not the reply: read_session has all of it.
                val more = if (item.outcome.contextSummary.endsWith("…")) " (excerpt; read_session of that session has the whole reply)" else ""
                if (item.background != null) {
                    lines += "- Background work “${item.background}” that \"${item.title}\" started (subagent session id ${item.sessionID}): finished — ${item.outcome.contextSummary}$more"
                    continue
                }
                lines += "- \"${item.title}\" (id ${item.sessionID}, prompt ${item.promptID}): finished — ${item.outcome.contextSummary}$more"
            }
        }
        if (otherDelegatorRequestsInFlight > 0) {
            lines += "Other voice delegator requests currently in flight: $otherDelegatorRequestsInFlight."
        }
        return lines.joinToString("\n")
    }

    fun setSessionActive(sessionID: String, isActive: Boolean): ReplyWatch =
        copy(activeSessionIDs = if (isActive) activeSessionIDs + sessionID else activeSessionIDs - sessionID)

    fun removeCompleted(promptIDs: Set<String>): ReplyWatch = copy(completed = completed.filterNot { it.promptID in promptIDs })

    /** The next state and its effects. */
    fun transition(event: Event): Pair<ReplyWatch, List<Effect>> {
        val machine = Machine(this)
        val effects = machine.step(event)
        return machine.freeze() to effects
    }

    /**
     * The running requests that read this session's run or are waiting on
     * it: their answer will speak for it.
     */
    fun claimers(sessionID: String): Set<String> = Machine(this).claimers(sessionID)

    /** The mutable working copy one transition runs on. */
    private class Machine(watch: ReplyWatch) {
        var watched = watch.watched.toMutableList()
        var completed = watch.completed.toMutableList()
        var resolvedPromptIDs = LinkedHashSet(watch.resolvedPromptIDs)
        var connectingNotices = watch.connectingNotices.toMutableList()
        var delegatorReplies = LinkedHashMap(watch.delegatorReplies)
        var deferredNotices = watch.deferredNotices.toMutableList()
        var delegatorsInFlight = LinkedHashSet(watch.delegatorsInFlight)
        var pendingAnswers = LinkedHashMap(watch.pendingAnswers)
        var delegatorWaits = LinkedHashMap(watch.delegatorWaits)
        val userTurns = watch.userTurns
        var activeSessionIDs = LinkedHashSet(watch.activeSessionIDs)
        var callPhase = watch.callPhase

        fun freeze(): ReplyWatch = ReplyWatch(
            watched = watched.toList(),
            completed = completed.toList(),
            activeSessionIDs = activeSessionIDs.toSet(),
            callPhase = callPhase,
            resolvedPromptIDs = resolvedPromptIDs.toSet(),
            connectingNotices = connectingNotices.toList(),
            delegatorReplies = delegatorReplies.toMap(),
            deferredNotices = deferredNotices.toList(),
            delegatorsInFlight = delegatorsInFlight.toSet(),
            pendingAnswers = pendingAnswers.toMap(),
            delegatorWaits = delegatorWaits.toMap(),
            userTurns = userTurns,
        )

        fun step(event: Event): List<Effect> = when (event) {
            is Event.Sent -> {
                val effects = mutableListOf<Effect>()
                for (sent in event.prompts) {
                    if (watched.any { it.promptID == sent.promptID } || sent.promptID in resolvedPromptIDs) continue
                    val prompt = sent.copy(turn = minOf(sent.saidTurn ?: userTurns, userTurns))
                    watched += prompt
                    activeSessionIDs += prompt.sessionID
                    effects += Effect.Log(LogEntry.WatchStarted(prompt.promptID, prompt.sessionID, prompt.title))
                    effects += Effect.StartWait(prompt)
                }
                effects
            }

            is Event.HeldBack -> {
                for (index in watched.indices) {
                    event.turns[watched[index].promptID]?.let { turn -> watched[index] = watched[index].copy(turn = minOf(watched[index].turn, turn)) }
                }
                deferredNotices = deferredNotices.map { restamp(it, event.turns) }.toMutableList()
                connectingNotices = connectingNotices.map { restamp(it, event.turns) }.toMutableList()
                emptyList()
            }

            is Event.SseExecutionEnded -> {
                activeSessionIDs -= event.sessionID
                val settled = fetchSettled(event.source) { it.sessionID == event.sessionID }
                val relayed = watched.filter {
                    it.background?.parentSessionID == event.sessionID && it.isFetchingSettledReply && it.failures > 0
                }.map { Effect.FetchSettledReply(it, event.source) }
                settled + relayed
            }

            is Event.SseStepEnded -> watched
                .filter { it.sessionID == event.sessionID && !it.isFetchingSettledReply }
                .map(Effect::ProbeStepEnded)

            is Event.ActiveSessionsSnapshot -> {
                activeSessionIDs = LinkedHashSet(event.ids)
                fetchSettled(event.source) { it.sessionID !in event.ids }
            }

            is Event.WaitSucceeded -> waitSucceeded(event)

            is Event.WaitFailed -> waitFailed(event)

            is Event.Superseded -> {
                val index = watched.indexOfFirst { it.promptID == event.promptID }
                if (index < 0) {
                    emptyList()
                } else {
                    val prompt = watched.removeAt(index)
                    resolvedPromptIDs += prompt.promptID
                    listOf<Effect>(Effect.CancelWatch(prompt.promptID)) + resolveAnsweredRest(prompt.sessionID)
                }
            }

            Event.Reconnected ->
                if (watched.isEmpty()) emptyList() else listOf(Effect.RefreshActiveSessions(SettlementSource.reconnect))

            is Event.CallPhaseChanged -> callPhaseChanged(event.phase)

            is Event.DelegatorStarted -> {
                delegatorsInFlight += event.requestID
                emptyList()
            }

            is Event.DelegatorFinished -> {
                val requestID = event.requestID
                delegatorsInFlight -= requestID
                delegatorWaits.remove(requestID)
                val answers = pendingAnswers.remove(requestID) ?: emptyList()
                val effects = mutableListOf<Effect>()
                // Only an answer the voice speaks covers what the request read.
                event.spoken?.let { spoken ->
                    for (answer in answers) effects += apply(answer, spoken)
                }
                // What it held speaks, unless another request holds it too. Results in
                // their grace keep it while another request runs (it may read them);
                // with none left running, nothing can, so they speak now.
                val noneRunning = delegatorsInFlight.isEmpty()
                effects + release { notice ->
                    val removed = requestID in notice.heldBy
                    notice.copy(heldBy = notice.heldBy - requestID) to (removed || noneRunning)
                }
            }

            is Event.DelegatorAnswered -> {
                // Only a running request's read counts; one from a finished request
                // would never be applied.
                if (event.requestID !in delegatorsInFlight) {
                    emptyList()
                } else {
                    activeSessionIDs -= event.sessionID
                    pendingAnswers[event.requestID] = (pendingAnswers[event.requestID] ?: emptyList()) +
                        PendingAnswer(event.sessionID, event.reply, event.promptIDs)
                    claim(event.sessionID, event.requestID)
                }
            }

            is Event.DelegatorWaiting -> delegatorWaiting(event)

            is Event.DeferralExpired -> {
                val index = deferredNotices.indexOfFirst { it.deferralKey == event.key }
                if (index < 0) {
                    emptyList()
                } else {
                    val notice = deferredNotices.removeAt(index)
                    if (callPhase == CallPhase.ending || callPhase == CallPhase.ended) {
                        listOf(Effect.Log(LogEntry.NoticeDropped(notice.title, callPhase)))
                    } else {
                        val effect = announcement(notice)
                        pruneDelegatorReplies()
                        listOf(effect)
                    }
                }
            }
        }

        private fun waitSucceeded(event: Event.WaitSucceeded): List<Effect> {
            val index = watched.indexOfFirst { it.promptID == event.promptID }
            if (index < 0) return emptyList()
            val trimmed = event.reply?.trim() ?: ""
            if (watched[index].isIndependentWatch) {
                val prompt = watched.removeAt(index)
                if (watched.none { it.sessionID == prompt.sessionID }) activeSessionIDs -= prompt.sessionID
                return resolve(prompt, if (trimmed.isEmpty()) Outcome.FinishedWithoutText else Outcome.Replied(trimmed))
            }
            watched[index] = watched[index].copy(ownReply = trimmed)
            // A read lands once the session is idle, so every prompt sent there
            // settled into this run, and each one's own read lands too. The run
            // resolves, as one notice, once they all have: its reply comes after
            // its last prompt, with any earlier text that doesn't repeat it.
            val sessionID = watched[index].sessionID
            if (!watched.all { it.sessionID != sessionID || it.isIndependentWatch || it.ownReply != null }) return emptyList()
            return resolveRun(sessionID)
        }

        private fun waitFailed(event: Event.WaitFailed): List<Effect> {
            val index = watched.indexOfFirst { it.promptID == event.promptID }
            if (index < 0) return emptyList()
            return when (val error = event.error) {
                is WaitError.Retryable -> {
                    if (event.sessionStillActive == false && !watched[index].isFetchingSettledReply) {
                        activeSessionIDs -= watched[index].sessionID
                        return fetchSettled(SettlementSource.snapshot) { it.promptID == event.promptID }
                    }
                    watched[index] = watched[index].copy(failures = watched[index].failures + 1)
                    val attempt = watched[index].failures
                    if (attempt < maxRetryAttempts) {
                        val delaySeconds = RetryPolicy.sessionWait.ceiling(attempt).inWholeSeconds.toInt()
                        val prompt = watched[index]
                        return listOf(
                            Effect.Log(LogEntry.Retry(prompt.promptID, prompt.title, attempt, error.message)),
                            Effect.RetryWait(prompt, delaySeconds),
                        )
                    }
                    val prompt = watched.removeAt(index)
                    resolve(prompt, Outcome.Unreachable(error.message)) + resolveAnsweredRest(prompt.sessionID)
                }
                is WaitError.ReplyUnreadable -> failWatched(index, Outcome.ReplyUnreadable(error.reason))
                is WaitError.Failed -> failWatched(index, Outcome.Failed(error.message))
            }
        }

        private fun callPhaseChanged(newPhase: CallPhase): List<Effect> {
            callPhase = newPhase
            return when (newPhase) {
                CallPhase.connecting, CallPhase.ending -> emptyList()
                CallPhase.live -> {
                    val queued = connectingNotices.toList()
                    connectingNotices.clear()
                    val effects = mutableListOf<Effect>()
                    for (notice in queued) effects += announcement(notice)
                    pruneDelegatorReplies()
                    effects
                }
                CallPhase.ended -> {
                    val cancelled = watched.map { Effect.CancelWatch(it.promptID) }
                    watched.clear()
                    connectingNotices.clear()
                    delegatorReplies.clear()
                    val timers = deferredNotices.mapNotNull { it.deferralKey }.map { Effect.CancelDeferralExpiry(it) }
                    deferredNotices.clear()
                    delegatorsInFlight.clear()
                    pendingAnswers.clear()
                    delegatorWaits.clear()
                    cancelled + timers
                }
            }
        }

        private fun delegatorWaiting(event: Event.DelegatorWaiting): List<Effect> {
            val sessionID = event.sessionID
            val requestID = event.requestID
            if (requestID !in delegatorsInFlight) return emptyList()
            if (event.isWaiting) {
                delegatorWaits[requestID] = (delegatorWaits[requestID] ?: emptySet()) + sessionID
                return claim(sessionID, requestID)
            }
            delegatorWaits[requestID]?.let { delegatorWaits[requestID] = it - sessionID }
            // A wait that read the reply still holds it through that read.
            if (pendingAnswers[requestID]?.any { it.sessionID == sessionID } == true) return emptyList()
            return release { notice ->
                if (notice.isAbout(sessionID) && requestID in notice.heldBy) {
                    notice.copy(heldBy = notice.heldBy - requestID) to true
                } else {
                    notice to false
                }
            }
        }

        fun claimers(sessionID: String): Set<String> = delegatorsInFlight.filterTo(LinkedHashSet()) { request ->
            delegatorWaits[request]?.contains(sessionID) == true ||
                pendingAnswers[request]?.any { it.sessionID == sessionID } == true
        }

        /**
         * A request read this session or began waiting on it: its held results
         * wait for that request now, keeping the single `claimedResultLimit`
         * deadline started when each notice arrived.
         */
        private fun claim(sessionID: String, requestID: String): List<Effect> {
            if (requestID !in delegatorsInFlight) return emptyList()
            for (index in deferredNotices.indices) {
                if (!deferredNotices[index].isAbout(sessionID)) continue
                deferredNotices[index] = deferredNotices[index].copy(heldBy = deferredNotices[index].heldBy + requestID)
            }
            return emptyList()
        }

        /**
         * Speaks the held results [releases] picks (it may update a notice's
         * holders) that no running request holds any more.
         */
        private fun release(releases: (HeldNotice) -> Pair<HeldNotice, Boolean>): List<Effect> {
            val effects = mutableListOf<Effect>()
            val kept = mutableListOf<HeldNotice>()
            for (original in deferredNotices) {
                val (notice, picked) = releases(original)
                if (!picked || notice.heldBy.any { it in delegatorsInFlight }) {
                    kept += notice
                    continue
                }
                notice.deferralKey?.let { effects += Effect.CancelDeferralExpiry(it) }
                if (callPhase == CallPhase.ending || callPhase == CallPhase.ended) {
                    effects += Effect.Log(LogEntry.NoticeDropped(notice.title, callPhase))
                } else {
                    effects += announcement(notice)
                }
            }
            deferredNotices = kept
            pruneDelegatorReplies()
            return effects
        }

        /**
         * A delegator answer covering what its request read: those prompts are
         * resolved without a notice, their held results cancelled, and what it
         * said is kept for results of the same run still to come.
         */
        private fun apply(answer: PendingAnswer, spoken: String): List<Effect> {
            val sessionID = answer.sessionID
            val promptIDs = answer.promptIDs
            val effects = mutableListOf<Effect>()
            val trimmed = answer.reply?.trim() ?: ""
            val outcome: Outcome = if (trimmed.isEmpty()) Outcome.FinishedWithoutText else Outcome.Replied(trimmed)
            val answered = promptIDs.toSet()
            val matching = watched.filter { it.promptID in answered }
            watched.removeAll { it.promptID in answered }
            activeSessionIDs -= sessionID
            // The session is idle: what is still to come from it is this run's.
            val unspoken = (
                watched.filter { it.sessionID == sessionID }.map { it.promptID }.toSet() +
                    (deferredNotices + connectingNotices).filter { it.sessionID == sessionID }.flatMap { it.promptIDs }
                ) - answered
            val told = spoken.trim()
            if (told.isNotEmpty() && unspoken.isNotEmpty()) {
                delegatorReplies[sessionID] = DelegatorReply(VoiceWorkClient.excerpt(told, limit = 300), unspoken)
            }
            for (prompt in matching) {
                effects += recordCompleted(prompt, outcome)
                effects += Effect.Log(LogEntry.NoticeSuppressed(prompt.title))
            }
            effects += findBackgroundWork(matching, outcome)
            val covered = covered@{ notice: HeldNotice ->
                if (notice.promptIDs.all { it in answered }) return@covered true
                val outcomeText = (notice.outcome as? Outcome.Replied)?.reply
                notice.background?.parentSessionID == sessionID && outcomeText != null &&
                    (VoiceWorkClient.isRead(outcomeText, answer.reply) || VoiceWorkClient.isRead(outcomeText, spoken))
            }
            val suppressed = (connectingNotices + deferredNotices).filter(covered)
            for (key in deferredNotices.filter(covered).mapNotNull { it.deferralKey }) {
                effects += Effect.CancelDeferralExpiry(key)
            }
            connectingNotices.removeAll(covered)
            deferredNotices.removeAll(covered)
            // A held notice for a run the answer covered in part names only the rest.
            connectingNotices = connectingNotices.map { it.removing(answered) }.toMutableList()
            deferredNotices = deferredNotices.map { it.removing(answered) }.toMutableList()
            for (notice in suppressed) {
                effects += Effect.Log(LogEntry.NoticeSuppressed(notice.title))
            }
            if (promptIDs.isNotEmpty()) {
                effects += Effect.CancelWireAnnouncements(promptIDs)
            }
            // Earlier prompts of the run waiting on one the delegator just answered.
            effects += resolveAnsweredRest(sessionID)
            pruneDelegatorReplies()
            return effects
        }

        private fun fetchSettled(source: SettlementSource, predicate: (Prompt) -> Boolean): List<Effect> {
            val effects = mutableListOf<Effect>()
            for (index in watched.indices) {
                if (watched[index].isFetchingSettledReply || !predicate(watched[index])) continue
                watched[index] = watched[index].copy(isFetchingSettledReply = true)
                val prompt = watched[index]
                effects += Effect.Log(LogEntry.Settled(prompt.promptID, prompt.title, source))
                effects += Effect.FetchSettledReply(prompt, source)
            }
            return effects
        }

        private fun failWatched(index: Int, outcome: Outcome): List<Effect> {
            val prompt = watched.removeAt(index)
            if (watched.none { it.sessionID == prompt.sessionID }) {
                activeSessionIDs -= prompt.sessionID
            }
            return resolve(prompt, outcome) + resolveAnsweredRest(prompt.sessionID)
        }

        private fun recordCompleted(prompt: Prompt, outcome: Outcome, logged: Outcome? = null): List<Effect> {
            resolvedPromptIDs += prompt.promptID
            completed += Completed(prompt.promptID, prompt.sessionID, prompt.title, outcome, background = prompt.background?.description)
            if (completed.size > maxCompletedHistory) {
                repeat(completed.size - maxCompletedHistory) { completed.removeAt(0) }
            }
            return listOf(
                Effect.CancelWatch(prompt.promptID),
                Effect.Log(LogEntry.Resolved(prompt.promptID, prompt.title, logged ?: outcome)),
            )
        }

        private fun resolve(prompt: Prompt, outcome: Outcome): List<Effect> = resolve(listOf(prompt), outcome)

        /**
         * Resolves every watched prompt of a session's run with the reply after
         * its last prompt, keeping any earlier text the final reply doesn't repeat.
         */
        private fun resolveRun(sessionID: String): List<Effect> {
            val run = watched.filter { it.sessionID == sessionID && !it.isIndependentWatch }
            if (run.isEmpty()) return emptyList()
            watched.removeAll { it.sessionID == sessionID && !it.isIndependentWatch }
            if (watched.none { it.sessionID == sessionID }) activeSessionIDs -= sessionID
            // Prompts after the run's last text got no reply: an earlier prompt's
            // reply, written before they arrived, never answers them.
            val lastAnswered = run.indexOfLast { it.ownReply?.isEmpty() == false }
            if (lastAnswered < 0) return resolve(run, Outcome.FinishedWithoutText)
            val answered = run.subList(0, lastAnswered + 1)
            val unanswered = run.subList(lastAnswered + 1, run.size)
            val final = answered.last().ownReply ?: ""
            val parts = mutableListOf<String>()
            for (text in answered.dropLast(1).mapNotNull { it.ownReply }) {
                if (text.isNotEmpty() && !final.contains(text) && text !in parts) parts += text
            }
            val text = (parts + final).joinToString("\n\n")
            val effects = resolve(answered, Outcome.Replied(text))
            return if (unanswered.isEmpty()) effects else effects + resolve(unanswered, Outcome.FinishedWithoutText)
        }

        /**
         * A run that finished may have started background work that outlives it
         * (a background subagent): its first prompt anchors the search, so the
         * whole run is covered. A failed run, background work's own result, and
         * a call that's ending start nothing.
         */
        private fun findBackgroundWork(prompts: List<Prompt>, outcome: Outcome): List<Effect> {
            if (outcome.isFailure || (callPhase != CallPhase.live && callPhase != CallPhase.connecting)) return emptyList()
            val first = prompts.firstOrNull { it.background == null } ?: return emptyList()
            val reply = (outcome as? Outcome.Replied)?.reply
            return listOf(Effect.FindBackgroundWork(first.sessionID, first.promptID, first.title, first.turn, reply))
        }

        /**
         * Earlier prompts of a run whose own reads already landed, once its last
         * prompt left another way (a failure, or the delegator answering it).
         */
        private fun resolveAnsweredRest(sessionID: String): List<Effect> {
            val rest = watched.filter { it.sessionID == sessionID && !it.isIndependentWatch }
            if (rest.isEmpty() || !rest.all { it.ownReply != null }) return emptyList()
            return resolveRun(sessionID)
        }

        /** Resolves prompts one reply answers, with one notice for them all. */
        private fun resolve(prompts: List<Prompt>, outcome: Outcome): List<Effect> {
            val prompt = prompts.lastOrNull() ?: return emptyList()
            val effects = mutableListOf<Effect>()
            for (item in prompts) {
                // The call log shows each prompt's own reply: the run's text leads
                // with its earliest, which read as every prompt's answer.
                var logged = outcome
                val own = item.ownReply
                if (prompts.size > 1 && outcome is Outcome.Replied && own != null) {
                    logged = if (own.isEmpty()) Outcome.FinishedWithoutText else Outcome.Replied(own)
                }
                effects += recordCompleted(item, outcome, logged)
            }
            val notice = HeldNotice(
                promptID = prompt.promptID,
                promptIDs = prompts.map { it.promptID },
                sessionID = prompt.sessionID,
                title = prompt.title,
                outcome = outcome,
                topics = prompts.map { it.topic ?: untitledTopic(it.title) },
                turn = prompts.minOfOrNull { it.turn } ?: prompt.turn,
                background = prompt.background,
            )
            when {
                callPhase == CallPhase.connecting -> connectingNotices += notice
                (callPhase == CallPhase.live || callPhase == CallPhase.ending) && delegatorsInFlight.isNotEmpty() -> {
                    // A running request that read this run, or is waiting on it, tells
                    // the user in its answer, and this then says only the rest. Unclaimed
                    // notices from other sessions also wait while a foreground request is
                    // running, releasing as soon as none is left (capped at `claimedResultLimit`).
                    val held = notice.copy(
                        deferralKey = notice.promptID,
                        heldBy = claimers(notice.sessionID) + (notice.background?.let { claimers(it.parentSessionID) } ?: emptySet()),
                    )
                    deferredNotices += held
                    effects += Effect.ExpireDeferral(key = notice.promptID, after = claimedResultLimit)
                }
                callPhase == CallPhase.live -> {
                    // Spoken now: the call holds it only so it doesn't cut off speech.
                    effects += announcement(notice)
                    pruneDelegatorReplies()
                }
                else -> effects += Effect.Log(LogEntry.NoticeDropped(prompt.title, callPhase))
            }
            return effects + findBackgroundWork(prompts, outcome)
        }

        /**
         * The notice as it goes out: what the delegator already said of the same
         * run, and which request it answers once the conversation went on.
         */
        private fun announcement(notice: HeldNotice): Effect {
            var alreadyTold: String? = null
            val told = delegatorReplies[notice.sessionID]
            if (told != null && told.promptIDs.any { it in notice.promptIDs }) {
                alreadyTold = told.reply
                delegatorReplies[notice.sessionID] = told.copy(promptIDs = told.promptIDs - notice.promptIDs.toSet())
            }
            var text = notice.outcome.notice(notice.title, answering = notice.topics, alreadyTold = alreadyTold, background = notice.background?.description)
            if (userTurns > notice.turn) {
                // Several topics are already named in the notice.
                val topic = if (notice.topics.size == 1) notice.topics.first().takeIf { it != untitledTopic(notice.title) } else null
                text = VoiceHandoff.earlierRequest(text, topic = topic)
            }
            return Effect.Announce(notice.sessionID, notice.promptIDs, text)
        }

        private fun restamp(notice: HeldNotice, turns: Map<String, Int>): HeldNotice {
            val turn = notice.promptIDs.mapNotNull { turns[it] }.minOrNull() ?: return notice
            return notice.copy(turn = minOf(notice.turn, turn))
        }

        /** Forgets what the delegator reported once nothing of that run is left. */
        private fun pruneDelegatorReplies() {
            val pending = watched.map { it.promptID }.toSet() +
                (deferredNotices + connectingNotices).flatMap { it.promptIDs }
            delegatorReplies.entries.removeAll { (_, told) -> told.promptIDs.none { it in pending } }
        }
    }

    /**
     * Pure quiet-gate policy: holds wire commentary while either side is
     * speaking or a user utterance is awaiting its reply, bounded by [maxHold]
     * across quiet-but-not-yet-settled waiting. Active assistant speech, the
     * assistant's post-speech [quietWindow], and a brief pause after the latest
     * user transcript delta are always enforced and never counted toward [maxHold].
     *
     * Declared here so it stays `ReplyWatch.QuietGate` (Kotlin can't nest a type from an
     * extension); its policy (`delay`, `answerDelay`, `userSpeechHold`, `holdReason`) and `live`
     * are in `VoiceQuietGate.kt`. A value: each `note…` returns the next gate. Instants are
     * `Clock.now()` readings.
     */
    data class QuietGate(
        val quietWindow: Duration = Duration.ZERO,
        val replyWait: Duration = Duration.ZERO,
        val maxHold: Duration = 5.seconds,
        val userPause: Duration = 450.milliseconds,
        /** How long the user must be quiet before anything goes out; zero uses
         * the brief [userPause]. */
        val userQuietGap: Duration = Duration.ZERO,
        val assistantSpeaking: Boolean = false,
        val lastAssistantSpeechAt: Duration? = null,
        val lastUserSpeechAt: Duration? = null,
        /** When the microphone last heard the user's voice. Transcript fragments
         * arrive in bursts with gaps of a second or more while someone talks on. */
        val lastUserVoiceAt: Duration? = null,
        val heldSince: Duration? = null,
        /** When the voice agent's current run of speech began. */
        val assistantSpeechRunStartedAt: Duration? = null,
        internal val speakingDurationWhileHeld: Duration = Duration.ZERO,
        internal val assistantSpeechStartedAt: Duration? = null,
    ) {
        fun noteAssistantSpeaking(speaking: Boolean, now: Duration): QuietGate {
            var runStartedAt = assistantSpeechRunStartedAt
            var speechStartedAt = assistantSpeechStartedAt
            var spokenWhileHeld = speakingDurationWhileHeld
            if (speaking && !assistantSpeaking) runStartedAt = now
            if (speaking) {
                if (heldSince != null && speechStartedAt == null) {
                    speechStartedAt = now
                }
            } else if (speechStartedAt != null) {
                spokenWhileHeld += maxOf(Duration.ZERO, now - speechStartedAt)
                speechStartedAt = null
            }
            return copy(
                assistantSpeaking = speaking,
                lastAssistantSpeechAt = now,
                assistantSpeechRunStartedAt = runStartedAt,
                assistantSpeechStartedAt = speechStartedAt,
                speakingDurationWhileHeld = spokenWhileHeld,
            )
        }

        fun noteUserSpoke(now: Duration): QuietGate {
            var spokenWhileHeld = speakingDurationWhileHeld
            val previous = lastUserSpeechAt
            if (heldSince != null && previous != null && now - previous <= quietWindow) {
                val segmentStart = maxOf(previous, heldSince)
                if (now > segmentStart) {
                    spokenWhileHeld += now - segmentStart
                }
            }
            return copy(lastUserSpeechAt = now, speakingDurationWhileHeld = spokenWhileHeld)
        }

        /** The microphone heard the user's voice: they're still talking, though
         * no words have been transcribed for it yet. */
        fun noteUserVoice(now: Duration): QuietGate = copy(lastUserVoiceAt = now)

        fun noteHeld(now: Duration): QuietGate {
            if (heldSince != null) return this
            return copy(
                heldSince = now,
                speakingDurationWhileHeld = Duration.ZERO,
                assistantSpeechStartedAt = if (assistantSpeaking) now else null,
            )
        }

        fun clearHold(): QuietGate = copy(
            heldSince = null,
            speakingDurationWhileHeld = Duration.ZERO,
            assistantSpeechStartedAt = null,
        )

        companion object
    }

    companion object {
        /** The shared session-wait policy's attempts (about seven minutes). */
        val maxRetryAttempts: Int = RetryPolicy.sessionWait.maxAttempts ?: 30
        const val maxCompletedHistory = 8

        fun milliseconds(duration: Duration): Int = maxOf(0, duration.inWholeMilliseconds.toInt())

        /**
         * The prompts of one delegation that answer a turn said before its
         * latest, each with that turn. GPT-Live can hold a request back and hand
         * it over with the next turn's, so one delegation carries several turns.
         * Every prompt counts from the delegation ([now]) unless it's
         * unambiguous: it shares two or more words with exactly one earlier turn
         * and none with any other, and every turn after that one is another
         * prompt's in the same way. A later turn no other prompt answers may be
         * a correction (“Actually, Alice's instead”), so the prompt is fresh:
         * a fresh answer led as an old one is worse than a missed lead.
         */
        fun heldBackTurns(prompts: List<Pair<String, String?>>, among: List<String>, now: Int): Map<String, Int> {
            val turns = among
            if (turns.size <= 1 || prompts.size <= 1) return emptyMap()
            val words = turns.map(::turnWords)
            val owned: List<Int?> = prompts.map { (_, topic) ->
                if (topic == null) return@map null
                val asked = turnWords(topic)
                val shared = words.map { (asked intersect it).size }
                val matched = shared.indices.filter { shared[it] > 0 }
                val turn = matched.singleOrNull() ?: return@map null
                if (shared[turn] >= 2) turn else null
            }
            val stamps = LinkedHashMap<String, Int>()
            for ((index, prompt) in prompts.withIndex()) {
                val turn = owned[index] ?: continue
                if (turn >= turns.size - 1) continue
                val laterAreOthers = (turn + 1 until turns.size).all { later ->
                    owned.indices.any { it != index && owned[it] == later }
                }
                if (laterAreOthers) stamps[prompt.first] = now - (turns.size - 1 - turn)
            }
            return stamps
        }

        private val commonWords: Set<String> = setOf(
            "the", "and", "for", "with", "this", "that", "you", "your", "what", "can", "please",
            "ask", "tell", "session", "okay", "also", "about", "it's", "from", "have", "are", "was",
            "actually", "instead", "just", "now", "check", "one", "other",
        )

        /** Words of three or more letters, lowercased, possessives dropped. */
        private fun turnWords(text: String): Set<String> {
            val words = mutableSetOf<String>()
            val current = StringBuilder()
            fun flush() {
                if (current.isEmpty()) return
                val trimmed = current.toString().trim('\'')
                current.clear()
                val word = if (trimmed.endsWith("'s")) trimmed.dropLast(2) else trimmed
                if (word.length >= 3 && word !in commonWords) words += word
            }
            for (char in text.lowercase().replace("’", "'")) {
                if (char.isLetter() || char.isDigit() || char == '\'') current.append(char) else flush()
            }
            flush()
            return words
        }

        fun untitledTopic(title: String): String = "an earlier request in “$title”"
    }
}
