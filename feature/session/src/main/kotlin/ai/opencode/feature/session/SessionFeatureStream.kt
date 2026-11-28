package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.map
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DictationHoldRelease
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.diagnostics.PerfFrameLatency
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlin.time.Duration
import ai.opencode.feature.transcript.list.NewMessagesFold
import ai.opencode.feature.transcript.list.TranscriptScan

/**
 * Splices a freshly fetched window of newest messages into what we show.
 *
 * Every fetch returns the *newest N messages*, so a poll's window is often shorter than a
 * transcript already expanded by the full-history fetch — and its item count can even shrink as
 * the window slides over tool-heavy messages. Comparing counts therefore froze the transcript and
 * new messages stopped appearing, so instead: keep everything older than the window, adopt the
 * window, and carry client-only rows forward.
 */
internal fun SessionFeature.Companion.mergeTranscript(
    fetched: List<OpenCodeTranscriptItem>,
    existing: List<OpenCodeTranscriptItem>,
    optimisticPromptIDs: Set<String> = emptySet(),
    projectedMessageIDs: Set<String> = emptySet(),
    echoedPromptIDs: Set<String> = emptySet(),
    pendingNoticeIDs: Set<String> = emptySet(),
    complete: Boolean = false,
    preserveInProgressText: Boolean = false,
): List<OpenCodeTranscriptItem> {
    if (!complete && fetched.isEmpty()) return existing
    val windowIDs = fetched.mapTo(HashSet(fetched.size)) { it.id }
    fun isOptimistic(item: OpenCodeTranscriptItem): Boolean =
        optimisticPromptIDs.isNotEmpty() && TranscriptEntry.promptID(item)?.let { it in optimisticPromptIDs } == true
    // The usual watchdog/reconnect response is the same trailing window. Prove only that window's
    // content; the older prefix needs ID checks, not classification, copying, or deep equality.
    // Rewritten history and local rows that must move to the tail still take the general merge below.
    if (existing.size == fetched.size || (!complete && existing.size > fetched.size)) {
        val prefixCount = existing.size - fetched.size
        var sameWindow = true
        for (index in fetched.indices) {
            if (existing[prefixCount + index] != fetched[index]) { sameWindow = false; break }
        }
        if (sameWindow) {
            for (index in 0 until prefixCount) {
                val item = existing[index]
                if (item.id in windowIDs || item.id.startsWith("info-") || isOptimistic(item)) { sameWindow = false; break }
            }
        }
        if (sameWindow) return existing
    }
    // REST has no unfinished text/tool payload until its durable boundary. A page fetched while an
    // SSE projection is live must not erase a row merely because that message's part is still
    // absent from the snapshot.
    // A complete page covers the whole transcript, even when it shares no IDs with the cache.
    val windowStart = if (complete) 0 else existing.indexOfFirst { it.id == fetched[0].id }.takeIf { it >= 0 }
    val older = ArrayList<OpenCodeTranscriptItem>()
    val trailing = ArrayList<OpenCodeTranscriptItem>()
    val retainedIDs = HashSet<String>()
    var hasServerRows = false
    val protectedMessages = projectedMessageIDs + echoedPromptIDs
    // Classify once. Only the overlapping suffix can contain unpersisted parts; don't build two
    // full-history arrays and then filter them again.
    for ((index, item) in existing.withIndex()) {
        if (item.id in windowIDs) {
            hasServerRows = true
        } else if (index >= (windowStart ?: 0) &&
            ((preserveInProgressText && item.isInProgressText) || item.id in pendingNoticeIDs ||
                (protectedMessages.isNotEmpty() && messageID(item) in protectedMessages))
        ) {
            retainedIDs += item.id
        } else if (item.id.startsWith("info-") || isOptimistic(item)) {
            trailing += item
        } else {
            hasServerRows = true
            if (windowStart == null || index < windowStart) older += item
        }
    }

    val merged = older + fetched
    if (retainedIDs.isEmpty()) return merged + trailing
    // Rows held for the stream all sit at or after the window's first row, and so do the rows
    // they're placed against: link only that span (the window, a screen or two), never every row
    // of a long session. With no overlap at all, that span is everything.
    val existingStart = windowStart ?: 0
    val mergedStart = if (windowStart == null) 0 else older.size
    var linked = 0
    // Past the window's first row, only the window survives.
    val survivingIDs = if (windowStart == null) merged.mapTo(HashSet(merged.size)) { it.id } else windowIDs
    // Keep an unpersisted part before the next surviving row, rather than moving it behind later
    // steering messages on every page refresh.
    val nextSurvivor = HashMap<String, String?>()
    var successor: String? = null
    for (index in existing.indices.reversed()) {
        if (index < existingStart) break
        val item = existing[index]
        linked += 1
        if (item.id in retainedIDs) {
            nextSurvivor[item.id] = successor
        } else if (item.id in survivingIDs) {
            successor = item.id
        }
    }
    // A new steering echo may have no old successor. Anchor after the part's own fetched siblings,
    // then fall back to its predecessor's whole message.
    val messageEnds = HashMap<String, Int>()
    val positions = HashMap<String, Int>(merged.size - mergedStart)
    for (index in mergedStart until merged.size) {
        linked += 1
        val item = merged[index]
        positions[item.id] = index
        messageEnds[messageID(item)] = index + 1
    }
    // A message with no row in the span ends where its last older row does.
    fun messageEnd(messageID: String): Int? {
        messageEnds[messageID]?.let { return it }
        for (index in mergedStart - 1 downTo 0) {
            if (messageID(merged[index]) == messageID) return index + 1
        }
        return null
    }
    val buckets = HashMap<Int, MutableList<OpenCodeTranscriptItem>>()
    var predecessor: OpenCodeTranscriptItem? = null
    for (index in existingStart until existing.size) {
        val item = existing[index]
        linked += 1
        if (item.id in retainedIDs) {
            val insertion = nextSurvivor[item.id]?.let { positions[it] }
                ?: messageEnd(messageID(item))
                ?: predecessor?.let { messageEnd(messageID(it)) }
                // An echo older than a window that jumped ahead of held history goes before it;
                // with no history held (a session still loading) it's the newest row.
                ?: if (messageID(item) in echoedPromptIDs && hasServerRows) 0 else merged.size
            buckets.getOrPut(insertion) { ArrayList() } += item
        } else if (item.id in survivingIDs) {
            predecessor = item
        }
    }
    TranscriptScan.record(linked)
    val result = ArrayList<OpenCodeTranscriptItem>(merged.size + retainedIDs.size + trailing.size)
    // Rows go in only from the first bucket on (an echo can go first of all).
    val plainEnd = minOf(mergedStart, buckets.keys.minOrNull() ?: merged.size)
    result.addAll(merged.subList(0, plainEnd))
    for (index in plainEnd until merged.size) {
        buckets[index]?.let(result::addAll)
        result += merged[index]
    }
    buckets[merged.size]?.let(result::addAll)
    result += trailing
    return result
}

/**
 * A replacement read (one message, or Home's bounded cache) carries only unfinished text across.
 * Matching rows anchor it in read order.
 */
fun SessionFeature.Companion.preservingInProgressText(
    fetched: List<OpenCodeTranscriptItem>, existing: List<OpenCodeTranscriptItem>,
): List<OpenCodeTranscriptItem> {
    if (existing.none { it.isInProgressText }) return fetched
    val ids = fetched.mapTo(HashSet()) { it.id }
    val anchorsAndText = existing.filter { it.isInProgressText || it.id in ids }
    return mergeTranscript(fetched, anchorsAndText, preserveInProgressText = true)
}

/** The server message an item belongs to. */
internal fun SessionFeature.Companion.messageID(item: OpenCodeTranscriptItem): String {
    TranscriptEntry.promptID(item)?.let { return it }
    return when (item) {
        is OpenCodeTranscriptItem.Tool ->
            item.call.messageID ?: item.id.indexOf("-tool-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: item.id
        is OpenCodeTranscriptItem.AssistantMarkdown ->
            item.id.lastIndexOf("-text-").takeIf { it >= 0 }?.let { item.id.substring(0, it) } ?: item.id
        // An assistant message's error carries its ID with `-error`; an idle message's is its own.
        is OpenCodeTranscriptItem.RunError -> if (item.error.beforeReply) item.id else item.id.dropLast("-error".length)
        is OpenCodeTranscriptItem.RunRetry -> item.id.dropLast("-retry".length)
        else -> item.id
    }
}

/**
 * Which of [ids] are rows in [items], looking from the end (live parts are recent) and stopping
 * once all are found.
 */
internal fun SessionFeature.Companion.ids(ids: Set<String>, items: List<OpenCodeTranscriptItem>): Set<String> {
    if (ids.isEmpty()) return emptySet()
    val found = HashSet<String>()
    for (index in items.indices.reversed()) {
        val id = items[index].id
        if (id !in ids) continue
        found += id
        if (found.size == ids.size) break
    }
    return found
}

internal fun SessionFeature.Companion.promptIDs(items: List<OpenCodeTranscriptItem>): Set<String> =
    items.mapNotNullTo(HashSet()) { TranscriptEntry.promptID(it) }

/** The event stream, transcript refreshes, and the screen's lifecycle. Null: the action passes on. */
internal fun SessionFeature.reduceStream(
    state: SessionFeature.State,
    action: Action,
): Reduced<SessionFeature.State, Action>? = when (action) {
    is Action.EventStreamConnected -> eventStreamConnected(state, action)
    is Action.ContentBatch -> contentBatch(state, action)
    is Action.WatchdogElapsed -> watchdogElapsed(state, action.generation)
    is Action.MessageInvalidated ->
        if (state.session?.id != action.sessionID) Reduced(state)
        else Reduced(state, Effect.send(Action.RefreshRequested(RefreshReason.Invalidation)))
    is Action.RefreshRequested -> {
        val refreshed = refreshRequested(state, action.reason)
        Reduced(refreshed.state, Effect.merge(refreshed.effect, recheckFiles(state, action.reason)))
    }
    is Action.RefreshResponse -> refreshResponse(state, action)
    is Action.SceneActiveChanged -> sceneActiveChanged(state, action.isActive)
    is Action.ActiveSessionsRead -> Reduced(state.copy(activeFamilySessionIDs = action.sessionIDs))
    is Action.PollingPauseChanged -> pollingPauseChanged(state, action.paused)
    is Action.VisibilityChanged -> visibilityChanged(state, action.isVisible)
    is Action.ResyncDelayElapsed -> resyncDelayElapsed(state, action.refreshID)
    is Action.ResyncMinimumElapsed -> {
        // Counts from when the cue first showed, across the reads it carries; clearing the cue
        // cancels it, so it never outlives its cue.
        val resync = state.resync
        when {
            resync == null || !resync.isShown -> Reduced(state)
            resync.responseReceived -> Reduced(state.copy(resync = null))
            else -> Reduced(state.copy(resync = resync.copy(minimumElapsed = true)))
        }
    }
    is Action.AcceptedTranscriptUpdated -> Reduced(if (action.sessionID == state.session?.id) state.learningFirstPrompt() else state)
    is Action.ViewAcknowledged -> {
        val viewed = maxOf(state.viewedIdle[action.sessionID] ?: 0L, action.idle)
        var s = state.copy(viewedIdle = state.viewedIdle + (action.sessionID to viewed))
        val session = s.session
        if (session?.id == action.sessionID) {
            s = s.copy(session = session.copy(viewedMilliseconds = maxOf(session.viewedMilliseconds ?: 0L, action.idle)))
        }
        Reduced(s)
    }
    is Action.Task -> task(state)
    is Action.WorkingRunElapsed -> Reduced(state.copy(workingRun = state.workingRun.elapsed(action.id)))
    is Action.PreviewStateReplaced -> Reduced(action.state)
    is Action.FirstContentFrameRendered -> firstContentFrameRendered(state)
    else -> null
}

private fun SessionFeature.pollingPauseChanged(
    state: SessionFeature.State,
    paused: Boolean,
): Reduced<SessionFeature.State, Action> {
    var s = state.copy(isPollingPaused = paused)
    if (paused) s = s.copy(pausedWatchdogDeadline = s.watchdogSchedule?.deadline)
    if (!s.pollsServer) return quietServerPolling(s)
    val deadline = s.pausedWatchdogDeadline
    s = s.copy(pausedWatchdogDeadline = null)
    val clock = watchdogClock
    val due = deadline != null && (clock == null || clock.now() >= deadline)
    val reads = due && s.hasRenderedFirstContentFrame && s.session != null
    val resumed = resumeServerPolling(s)
    return Reduced(
        resumed.state,
        Effect.merge(resumed.effect, if (reads) Effect.send(Action.RefreshRequested(RefreshReason.Watchdog)) else Effect.none()),
    )
}

private fun SessionFeature.eventStreamConnected(
    state: SessionFeature.State,
    action: Action.EventStreamConnected,
): Reduced<SessionFeature.State, Action> {
    var s = state.copy(server = action.server, streamIsHealthy = true)
    val support = s.voice?.serverVoiceSupport
    if (support != null && support != ServerVoiceSupport.unknown) {
        s = s.copy(voice = s.voice?.copy(serverVoiceSupport = ServerVoiceSupport.unknown))
    }
    val retune = pendingWorkAfterStreamConnected(s)
    // A new socket epoch invalidates delta accumulators, not rendered parts: unfinished
    // text/tools are still absent from REST. Keep their row protection until reconciliation can
    // echo them or the run ends. Shell events are ephemeral: an exit may have been missed. The
    // reconnect's re-list replaces every listed shell. A text row streamed before the gap keeps its
    // words: a text delta leaves a row it has no accumulator for until its text ends.
    s = retune.state.copy(
        streamSupportsProjection = false,
        liveText = emptyMap(),
        lastProjectedMessageID = null,
        shells = retune.state.shells.map { if (it.isListed) it else it.copy(isListed = true) },
    )
    // A failed first read used to leave the chip on its slug until the picker was opened. Retry
    // after contact returns, even if a transcript refresh is already in flight. Keep any
    // last-known display names.
    val models = if (s.modelsLoadError != null) loadModelsIfNeeded(s) else Reduced(s)
    s = models.state
    // Shell and job events may have been missed while the stream was down.
    val shells = refreshShells(s)
    val jobs = refreshJobs(shells.state)
    s = jobs.state
    // A resume read that beat the socket handshake is already the REST hydration for this epoch.
    // Do not cancel it and start the same bytes again; subsequent events are buffered against
    // that in-flight read.
    val watched = watchdog(s)
    val relists = Effect.merge(shells.effect, jobs.effect)
    if (watched.state.refresh != null) return Reduced(watched.state, Effect.merge(models.effect, retune.effect, relists, watched.effect))
    return Reduced(
        watched.state,
        Effect.merge(models.effect, Effect.send(Action.RefreshRequested(RefreshReason.Reconnect)), retune.effect, relists, watched.effect),
    )
}

private fun SessionFeature.contentBatch(
    state: SessionFeature.State,
    action: Action.ContentBatch,
): Reduced<SessionFeature.State, Action> {
    val sessionID = action.sessionID
    val events = action.events
    if (state.session?.id != sessionID) return Reduced(state)
    PerfFrameLatency.begin("delta_batch", mapOf("events" to events.size))
    var s = state.copy(
        workingRun = state.workingRun.batchArriving(events, sessionID = sessionID, isRunning = state.isRunning, items = state.items),
        isExternallyActive = true,
        streamIsHealthy = true,
        streamSupportsProjection = true,
    )
    val retune = retunePendingWork(s)
    s = retune.state
    // Streaming text survives a refresh through `liveText`; replaying its deltas afterwards would
    // append them a second time. Reasoning events project no transcript items and need not be
    // buffered either.
    s.refresh?.let { refresh ->
        var raced = refresh.racedEvents + events.filter {
            it !is OpenCodeLiveContentEvent.TextDelta &&
                it !is OpenCodeLiveContentEvent.ReasoningStarted &&
                it !is OpenCodeLiveContentEvent.ReasoningDelta &&
                it !is OpenCodeLiveContentEvent.ReasoningEnded
        }
        if (raced.size > 512) raced = raced.drop(raced.size - 512)
        s = s.copy(refresh = refresh.copy(racedEvents = raced))
    }
    val previousFirstID = s.firstVisibleItemID
    if (!s.isSceneActive && s.hasRenderedFirstContentFrame && s.newMessagesBaseline == null && events.any { it.projectsRows }) {
        s = s.noteLeaving()
    }
    // Rows streaming in front of the reader before the return's read settles are seen: that read
    // mustn't mark them.
    val watchesBatch = s.watchesTranscript && s.newMessagesBaseline != null
    val watchedIDs = ArrayList<String>()
    val projection = LiveProjection(s)
    var projected = s.projectedMessageIDs
    var lastProjected = s.lastProjectedMessageID
    var contextNeedsRead = s.contextNeedsRead
    for (event in events) {
        if (event.projectsRows) {
            if (event.messageID !in projected) projected = projected + event.messageID
            lastProjected = event.messageID
        }
        if (event is OpenCodeLiveContentEvent.StepEnded) contextNeedsRead = true
        val insertedID = applyLiveEvent(event, projection)
        if (insertedID != null && watchesBatch) watchedIDs += insertedID
    }
    s = projection.applied(s).copy(
        projectedMessageIDs = projected,
        lastProjectedMessageID = lastProjected,
        contextNeedsRead = contextNeedsRead,
    )
    for (id in watchedIDs) s = s.noteWatched(itemID = id)
    s = s.retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    // Kept alive in the background, the stream brings what arrives while the reader is away.
    if (!s.watchesTranscript) {
        s = s.updateNewMessagesMark(settles = false, keptShapes = NewMessagesFold.keepsShapes(events))
    }
    // Live events add only the agent's rows, never the reader's own message: nothing here can
    // clear the mark (a read's merge can).
    LabMarks.mark("stream_delta_batch", sessionID = sessionID)
    // A flush can contain text.ended followed by step.ended for a different bookkeeping message.
    // Prefer the durable boundary that actually projected a row; that is the message REST must settle.
    val settledEvent = events.lastOrNull { it.isDurableBoundary && it.projectsRows }
        ?: events.lastOrNull { it.isDurableBoundary }
    val settledMessageID = settledEvent?.let {
        if (it.projectsRows) it.messageID else (s.lastProjectedMessageID ?: it.messageID)
    }
    if (settledMessageID != null) LabMarks.mark("stream_settled", sessionID = sessionID)
    val watched = watchdog(s)
    return Reduced(
        watched.state,
        Effect.merge(
            settledMessageID?.let { Effect.send(Action.RefreshRequested(RefreshReason.Settlement(it))) } ?: Effect.none(),
            retune.effect,
            watched.effect,
        ),
    )
}

/** Opening the session, and each reply the agent finishes, may leave files that weren't there before. */
private fun SessionFeature.recheckFiles(state: SessionFeature.State, reason: RefreshReason): Effect<Action> {
    val directory = state.session?.directory ?: return Effect.none()
    val rechecks = when (reason) {
        RefreshReason.Open, RefreshReason.Resume, RefreshReason.RunState, is RefreshReason.Settlement -> true
        else -> false
    }
    if (!rechecks) return Effect.none()
    val checks = fileChecks
    val server = state.server
    return Effect.run { checks.invalidate(server, directory) }
}

private fun SessionFeature.refreshRequested(
    state: SessionFeature.State,
    reason: RefreshReason,
): Reduced<SessionFeature.State, Action> {
    val session = state.session ?: return Reduced(state)
    val backgroundWork = refreshBackgroundWork(state, reason)
    var s = backgroundWork.state
    val inFlight = s.refresh
    if (inFlight != null) {
        // A terminal page read must not be downgraded by a later part or inbox invalidation while
        // this older request is still in flight.
        if (inFlight.followup == RefreshReason.RunState) return backgroundWork
        // Lifecycle duplicates coalesce into the read already in flight. Only a content/run
        // invalidation proves that read may have raced a newer server mutation and therefore
        // earns one follow-up.
        if (reason is RefreshReason.Settlement && inFlight.reason == RefreshReason.Settlement(reason.messageID)) {
            // Text/tool/step boundaries for one assistant message commonly arrive together. The
            // in-flight read has a 200 ms coalescing delay and will observe all of them.
        } else if (reason.messageID != null && inFlight.followup != null && inFlight.followup.messageID == null) {
            // A whole page read already follows (the return's, which settles what's new): it
            // covers this message too.
        } else if (inFlight.reason is RefreshReason.Settlement) {
            // A later request for a complete window must run after the targeted read; neither
            // scope may overwrite a newer read.
            s = s.copy(refresh = inFlight.copy(followup = reason))
        } else if (reason.requiresFollowup) {
            s = s.copy(refresh = inFlight.copy(followup = reason))
        }
        return Reduced(s, backgroundWork.effect)
    }
    val requestID = s.nextRefreshID + 1
    s = s.copy(nextRefreshID = requestID, refresh = SessionFeature.State.Refresh(id = requestID, reason = reason))
    LabMarks.mark("refresh_start(${reason.label})", sessionID = session.id)
    var cue: Effect<Action> = Effect.none()
    if (s.isVisible &&
        (reason == RefreshReason.Resume || reason == RefreshReason.Reconnect || (reason == RefreshReason.Open && !s.initialTranscriptIsFresh))
    ) {
        val began = beginResync(s, refreshID = requestID)
        s = began.state
        cue = began.effect
    }
    return Reduced(
        s,
        Effect.merge(
            cue,
            backgroundWork.effect,
            authoritativeRefresh(
                server = s.server,
                session = session,
                requestID = requestID,
                reason = reason,
                messageIsInWindow = reason.messageID?.let { SessionFeature.containsMessage(it, s.items) } ?: false,
                readsContext = s.contextNeedsRead,
            ),
        ),
    )
}

private fun SessionFeature.refreshResponse(
    state: SessionFeature.State,
    action: Action.RefreshResponse,
): Reduced<SessionFeature.State, Action> {
    val sessionID = action.sessionID
    val inFlight = state.refresh
    if (state.session?.id != sessionID || inFlight == null || inFlight.id != action.requestID) {
        LabMarks.mark("refresh_rejected_stale", sessionID = sessionID)
        return Reduced(state)
    }
    var s = state.copy(refresh = null)
    val followup: Effect<Action> = inFlight.followup?.let { Effect.send(Action.RefreshRequested(it)) } ?: Effect.none()
    LabMarks.mark("refresh_end(${inFlight.reason.label})", sessionID = sessionID)
    val settled = settleResync(s, refreshID = action.requestID)
    s = settled.state
    val settleCue = settled.effect
    val refresh = action.result.getOrElse { failure ->
        val error = failure.asClientError()
        s = s.copy(isLoading = false, refreshFailed = s.items.isNotEmpty())
        // A failed read after the return can't say what's new; a later one mustn't call what
        // arrived in front of the reader new.
        if (s.isVisible && inFlight.followup == null) {
            s = s.copy(
                newMessagesBaseline = null,
                newMessagesBaselineTimestamp = null,
                newMessagesUnread = false,
                newMessagesEarlierBaseline = null,
            )
        }
        if (s.cacheableItems.isEmpty() && !s.initialTranscriptIsFresh) s = s.copy(loadError = error.message)
        return Reduced(
            s,
            Effect.merge(
                settleCue,
                followup,
                if (!s.hasRenderedFirstContentFrame && s.items.isEmpty()) Effect.send(Action.FirstContentFrameRendered) else Effect.none(),
            ),
        )
    }
    if (!s.isExternallyActive) {
        // Any read can be the first to echo a prompt delivered after idle. Retire its wait before
        // deciding whether projection can settle.
        s = reconcilePromptEchoes(refresh.items, s)
        if (!s.hasPendingLocalPrompt) s = s.copy(isAwaitingReply = false)
    }
    val preserveProjection = s.isRunning || inFlight.followup == RefreshReason.RunState
    // Only the rows this read could touch are indexed: the raced events' parts, and the settled
    // message's own rows while the reader watches. Never a set of every row in a long session.
    val racedIDs = HashSet<String>()
    if (preserveProjection) {
        for (event in inFlight.racedEvents) event.transcriptItemID?.let { racedIDs += it }
        for (item in refresh.items) racedIDs -= item.id
    }
    val racedBefore = SessionFeature.ids(racedIDs, s.items)
    val scope = refresh.scope
    val watchedMessage = if (scope is SessionFeature.AuthoritativeRefresh.Scope.Message &&
        s.watchesTranscript && s.newMessagesBaseline != null
    ) {
        MessageRows(scope.messageID)
    } else {
        null
    }
    val messageRowsBefore = HashSet<String>()
    if (watchedMessage != null) {
        for (item in s.items) if (watchedMessage.contains(item)) messageRowsBefore += item.id
    }
    s = when (scope) {
        SessionFeature.AuthoritativeRefresh.Scope.Page ->
            applyAuthoritative(refresh.items, refresh.pageCursor, s, preserveProjection)
        is SessionFeature.AuthoritativeRefresh.Scope.Message ->
            applyAuthoritativeMessage(refresh.items, scope.messageID, s, preserveProjection)
    }
    s = s.copy(refreshFailed = false)
    // A carried row already contains the events received during this read. Replay only onto
    // replaced/missing parts; otherwise a tool's input deltas append twice (or input.started
    // resets its payload).
    val retainedIDs = SessionFeature.ids(racedBefore, s.items)
    val replayEvents = inFlight.racedEvents.filter { event ->
        preserveProjection && event.transcriptItemID?.let { it in retainedIDs } != true
    }
    val replayedRows = ArrayList<String>()
    if (replayEvents.isNotEmpty()) {
        val previousFirstID = s.firstVisibleItemID
        val projection = LiveProjection(s)
        for (event in replayEvents) applyLiveEvent(event, projection)?.let { replayedRows += it }
        s = projection.applied(s).retainRenderedWindowStart(previousFirstID).pinBrowsingWindowIfNeeded()
    }
    s = when (val context = refresh.context) {
        is OpenCodeContextReading.Measured -> s.copy(contextUsage = context.usage, contextNeedsRead = false)
        OpenCodeContextReading.Empty -> s.copy(contextUsage = null, contextNeedsRead = false)
        OpenCodeContextReading.Inconclusive -> s
    }
    // The page read after the reader's return settles what arrived while they were away. One
    // message's read in front of the reader brings what they're watching: only the page read
    // settles, or a read while away marks.
    val returned = s.watchesTranscript
    val settles = returned && refresh.scope == SessionFeature.AuthoritativeRefresh.Scope.Page
    if (settles || !returned) {
        s = s.updateNewMessagesMark(settles = settles)
    } else if (watchedMessage != null) {
        // Only a message read gets here: what it added is that message's rows, and any row a
        // raced event brought back.
        for (item in s.items) {
            if (watchedMessage.contains(item) && item.id !in messageRowsBefore) s = s.noteWatched(itemID = item.id)
        }
        for (id in replayedRows) s = s.noteWatched(itemID = id)
    }
    if (s.newMessages != null) s = s.clearNewMessagesAfterOwnPrompt()
    var observed = refresh.observed
    val current = s.session
    if (current != null && observed.id == current.id) {
        // A message settlement carries the session it started with, not a title read from the server.
        if (!refresh.observedIsRead) observed = observed.copy(serverTitle = current.serverTitle)
        if (!refresh.observedIsRead || inFlight.revertChanged) {
            observed = observed.copy(revertMessageID = current.revertMessageID)
        }
        // Only a page read reads the session; a message read carries the copy it started with,
        // which may predate a move.
        if (!refresh.observedIsRead || inFlight.directoryChanged) {
            observed = observed.copy(directory = current.directory)
        }
        val model = current.model
        if ((!refresh.observedIsRead || inFlight.modelChanged) && model != null) {
            observed = observed.copy(model = model)
        } else if (refresh.observedIsRead) {
            s = s.adoptServerModel(observed.model)
        }
    }
    s = s.updateObservedSession(observed)
    return Reduced(
        s,
        Effect.merge(
            settleCue,
            Effect.send(Action.AcceptedTranscriptUpdated(sessionID = sessionID, items = s.cacheableItems)),
            acknowledgeViewed(refresh.observed, s),
            followup,
            if (!s.hasRenderedFirstContentFrame && s.items.isEmpty()) Effect.send(Action.FirstContentFrameRendered) else Effect.none(),
        ),
    )
}

private fun SessionFeature.sceneActiveChanged(state: SessionFeature.State, isActive: Boolean): Reduced<SessionFeature.State, Action> {
    val wasBackgrounded = state.isBackgrounded
    // A screen swapped in while away may not have seen the scene go.
    val returning = isActive && (!state.isSceneActive || wasBackgrounded)
    var s = state.copy(isSceneActive = isActive)
    if (isActive) s = s.copy(isBackgrounded = false)
    if (s.watchesTranscript) s = s.absorbFittingNewMessages()
    if (!returning) return Reduced(s)
    // Polls stopped for the background, not for a glance at the notification shade.
    var polls: Effect<Action> = Effect.none()
    if (wasBackgrounded) {
        val resumed = resumeServerPolling(s)
        s = resumed.state
        polls = resumed.effect
    }
    // Reads wait out the background: a Stop the server took reads on from here, and a read that
    // failed or was dropped must not leave Working up with nothing running.
    if (s.isInterrupting && s.stopAttempt?.acknowledged == true) return Reduced(s, Effect.merge(polls, settleStop(s)))
    if (!s.isAwaitingReply || s.isExternallyActive || s.isSubmittingPrompt) return Reduced(s, polls)
    return Reduced(s, Effect.merge(polls, confirmRunState(s, after = Duration.ZERO)))
}

private fun SessionFeature.visibilityChanged(state: SessionFeature.State, visible: Boolean): Reduced<SessionFeature.State, Action> {
    if (visible == state.isVisible) return Reduced(state)
    val isForegroundResume = visible && !state.isVisible && state.hasRenderedFirstContentFrame
    var s = state.copy(isVisible = visible)
    if (isForegroundResume) LabMarks.mark("session_resume", sessionID = s.session?.id)
    // In front of the reader again: what fit on screen while they were away is what they're looking at.
    if (s.watchesTranscript) s = s.absorbFittingNewMessages()
    if (!visible) {
        // Leaving the screen; a glance at the notification shade (the scene going inactive) isn't
        // leaving, but going on into the background is.
        if (s.hasRenderedFirstContentFrame && s.isSceneActive) s = s.noteLeaving()
        if (!s.initialTranscriptRequestIsExternal) s = s.copy(isLoading = false)
        s = s.copy(familyRead = CoalescedRead())
        val abandoned = abandonRefresh(s)
        return Reduced(
            abandoned.state,
            Effect.merge(
                releaseDictationHold(DictationHoldRelease.Left),
                Effect.cancel(CancelID.ViewReceipt),
                Effect.cancel(CancelID.FamilyRefresh),
                Effect.cancel(CancelID.Shells),
                Effect.cancel(CancelID.Jobs),
                abandoned.effect,
            ),
        )
    }
    val models = if (s.modelsLoadError != null) loadModelsIfNeeded(s) else Reduced(s)
    s = models.state
    if (!s.hasRenderedFirstContentFrame) {
        if (s.initialTranscriptIsFresh && s.newMessagesBaseline != null) s = s.updateNewMessagesMark(settles = true)
        val opens = s.session != null && s.cacheableItems.isEmpty() && !s.initialTranscriptIsFresh &&
            !s.initialTranscriptRequestIsExternal && !s.inheritsLiveWork && s.refresh == null && s.loadError == null
        return Reduced(s, Effect.merge(models.effect, if (opens) Effect.send(Action.RefreshRequested(RefreshReason.Open)) else Effect.none()))
    }
    // A message's read started while away doesn't read the page: the return still needs one, after it.
    val refresh = s.refresh
    if (refresh != null && !s.initialTranscriptIsFresh && refresh.reason.messageID == null) {
        val began = beginResync(s, refreshID = refresh.id)
        val background = refreshBackgroundWork(began.state, RefreshReason.Resume)
        return Reduced(background.state, Effect.merge(models.effect, began.effect, background.effect))
    }
    return Reduced(
        s,
        Effect.merge(models.effect, Effect.send(Action.RefreshRequested(if (isForegroundResume) RefreshReason.Resume else RefreshReason.Open))),
    )
}

private fun SessionFeature.resyncDelayElapsed(state: SessionFeature.State, refreshID: Int): Reduced<SessionFeature.State, Action> {
    val resync = state.resync
    if (resync == null || resync.refreshID != refreshID) return Reduced(state)
    if (resync.responseReceived) return Reduced(state.copy(resync = null))
    val clock = clock
    return Reduced(
        state.copy(resync = resync.copy(isShown = true)),
        Effect.run<Action> { send ->
            clock.sleep(SessionFeature.resyncCueMinimum)
            send(Action.ResyncMinimumElapsed)
        }.cancellable(CancelID.ResyncMinimum, cancelInFlight = true),
    )
}

private fun SessionFeature.task(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    // Failed sends, and sends still on their way, outlive the screen that sent them.
    var s = restorePersistedComposer(state)
    s = restoreFailedSends(s)
    val outbox = restoreOutbox(s)
    s = outbox.state
    // A screen that opens on a waiting request answers it from the bar.
    if (s.wantsRequestDock && !s.isAnsweringRequest) s = s.beginAnswering()
    // An answer's recording carried from the last screen ends here. Audio held after dictating
    // into the last session's draft lets go.
    val discarded = DictationFeature.discardPending(s.dictation, dictation)
    s = s.copy(dictation = discarded.state)
    val discard: Effect<Action> = Effect.merge(
        discarded.effect.map { Action.Dictation(it) },
        releaseDictationHold(DictationHoldRelease.Left),
    )
    // A live call or recording carried here keeps this screen's identity, so the previous
    // session's loops must stop before this one's start.
    val stopSessionWork: Effect<Action> = Effect.merge(
        discard,
        if (!s.inheritsLiveWork) {
            Effect.none()
        } else {
            Effect.cancel(
                listOf(
                    CancelID.Models, CancelID.Requests, CancelID.Inbox, CancelID.InboxFetch, CancelID.PromptEcho,
                    CancelID.RequestsRetry, CancelID.RequestsChanged, CancelID.Freshness, CancelID.Watchdog,
                    CancelID.EarlierHistory, CancelID.FamilyRefresh, CancelID.Shells, CancelID.Jobs, CancelID.Revert,
                    CancelID.BackgroundHint, CancelID.ViewReceipt, CancelID.ResyncDelay, CancelID.ResyncMinimum,
                    CancelID.AutocompleteCatalog, CancelID.AutocompleteCommands,
                    CancelID.AutocompleteFiles, CancelID.AutocompleteSessions, CancelID.AutocompleteAutoRun,
                    CancelID.AutocompleteCommand, CancelID.AutocompleteCommandWait, CancelID.WindowReset,
                ),
            )
        },
    )
    if (s.inheritsLiveWork) {
        if (sessionTimers.watchdog || s.watchdogSchedule != null) {
            s = s.copy(watchdogSchedule = null, watchdogGeneration = s.watchdogGeneration + 1)
        }
        s = s.copy(isUndoingLastMessage = false, familyRead = CoalescedRead()).supersedeRunWork()
    }
    s = s.copy(inheritsLiveWork = false)
    val dictation = dictation
    if (s.session == null) {
        val loadModels = loadModelsIfNeeded(s)
        return Reduced(
            loadModels.state,
            Effect.merge(
                stopSessionWork,
                loadModels.effect,
                Effect.send(Action.AgentsRequested),
                outbox.effect,
                Effect.run<Action> { dictation.prepare() },
            ),
        )
    }
    if (s.loadError != null && s.items.isEmpty()) s = s.copy(isLoading = true)
    s = s.copy(loadError = null)
    if (s.initialTranscriptRequestIsExternal) {
        return Reduced(s, Effect.merge(stopSessionWork, outbox.effect))
    }
    // Navigation activation owns freshness; cached paint does not wait for visibility or the first frame.
    return Reduced(
        s,
        Effect.merge(
            Effect.concatenate(stopSessionWork, Effect.send(Action.RefreshRequested(RefreshReason.Open))),
            outbox.effect,
        ),
    )
}

private fun SessionFeature.firstContentFrameRendered(state: SessionFeature.State): Reduced<SessionFeature.State, Action> {
    if (state.hasRenderedFirstContentFrame || state.session == null) return Reduced(state)
    LabMarks.mark(
        "session_chrome_first_frame:background=${state.hasBackgroundWork},context=${state.contextFraction != null}," +
            "queued=${state.queuedInputs.size},requests=${state.permissionRequests.size + state.formRequests.size}," +
            "working=${state.isRunning}",
        sessionID = state.session.id,
    )
    var s = state.copy(hasRenderedFirstContentFrame = true, initialTranscriptRequestIsExternal = false)
    val loadModels = loadModelsIfNeeded(s)
    s = loadModels.state
    // Pending work polls once the family is known (see familyResponse); record the attempt so a
    // failure there counts as the initial one.
    s = s.withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        .withRequestLoadError(null)
    val backgroundHint = scheduleBackgroundHint(s)
    s = backgroundHint.state
    val familyIsFresh = s.familyReadAt?.let { (date.now().toEpochMilli() - it.toEpochMilli()) / 1000.0 < 5 } == true
    val family = if (familyIsFresh) pollPendingWork(s) else refreshFamily(s)
    s = family.state
    val shells = refreshShells(s)
    s = shells.state
    val jobs = refreshJobs(s)
    s = jobs.state
    val dictation = dictation
    return Reduced(
        s,
        Effect.merge(
            Effect.run<Action> { dictation.prepare() },
            family.effect,
            shells.effect,
            jobs.effect,
            backgroundHint.effect,
            loadModels.effect,
            if (s.initialTranscriptIsFresh || s.loadError != null) Effect.none() else Effect.send(Action.RefreshRequested(RefreshReason.Open)),
        ),
    )
}

/** A failure that isn't one reads as a generic client error. */
internal fun Throwable.asClientError(): ai.opencode.server.OpenCodeClientError =
    this as? ai.opencode.server.OpenCodeClientError ?: ai.opencode.server.OpenCodeClientError(message ?: localized(R.string.session_something_went_wrong))

// region Live projection

/**
 * The rows a run of live events writes, copied out of [SessionFeature.State] once and written back
 * once ([applied]), so a batch of deltas doesn't copy the whole state per event.
 */
internal class LiveProjection(state: SessionFeature.State) {
    val items: MutableList<OpenCodeTranscriptItem> = ArrayList(state.items)
    val liveText: MutableMap<String, String> = LinkedHashMap(state.liveText)
    private val shellToolStatuses = HashMap(state.shellToolStatuses)
    private val subagentToolSessionIDs = HashSet(state.subagentToolSessionIDs)
    private var shellsChanged = false
    private var subagentsChanged = false
    /** A live event replaced or inserted a row. */
    var itemsChanged = false

    fun noteShell(shellID: String, status: OpenCodeToolCall.Status) {
        if (shellToolStatuses[shellID] != status) {
            shellToolStatuses[shellID] = status
            shellsChanged = true
        }
    }

    fun noteSubagent(sessionID: String) {
        if (subagentToolSessionIDs.add(sessionID)) subagentsChanged = true
    }

    fun applied(state: SessionFeature.State): SessionFeature.State {
        var next = state
        if (itemsChanged) next = next.copy(items = items.toList())
        if (liveText != state.liveText) next = next.copy(liveText = liveText.toMap())
        if (shellsChanged) next = next.copy(shellToolStatuses = shellToolStatuses.toMap())
        if (subagentsChanged) next = next.copy(subagentToolSessionIDs = subagentToolSessionIDs.toSet())
        return next
    }
}

/**
 * REST can already be ahead of a raced event. Tool input is writable only while streaming, and
 * progress must never restart a settled tool.
 */
private fun SessionFeature.applyLiveEvent(event: OpenCodeLiveContentEvent, projection: LiveProjection): String? =
    when (event) {
        is OpenCodeLiveContentEvent.TextStarted -> {
            val id = "${event.messageID}-text-${event.ordinal}"
            projection.liveText[id] = projection.liveText[id] ?: ""
            null
        }
        is OpenCodeLiveContentEvent.TextDelta -> {
            val id = "${event.messageID}-text-${event.ordinal}"
            // A row with no accumulator streamed before a gap (a reconnect, or a stream that shed
            // this part's deltas): the deltas it missed are gone, so appending would lose text from
            // its middle and starting from "" would shrink it to its tail. It stays as it is until
            // its text ends with the whole text.
            val accumulated = projection.liveText[id]
            val text = when {
                // The socket may have streamed for a while before this screen opened.
                event.accumulatedText != null -> event.accumulatedText
                accumulated != null -> accumulated + event.delta
                projection.items.none { it.id == id } -> event.delta
                else -> null
            }
            if (text == null) {
                null
            } else {
                projection.liveText[id] = text
                projection.upsertText(upsertLiveTextChange(id, text, streaming = true, items = projection.items), id)
            }
        }
        is OpenCodeLiveContentEvent.TextEnded -> {
            val id = "${event.messageID}-text-${event.ordinal}"
            val insertedID = projection.upsertText(upsertLiveTextChange(id, event.text, items = projection.items), id)
            projection.liveText.remove(id)
            insertedID
        }
        is OpenCodeLiveContentEvent.ToolInputStarted ->
            upsertLiveTool(event.messageID, event.toolID, OpenCodeJSONValue.Str(""), projection) { call ->
                if (call.status != OpenCodeToolCall.Status.Streaming) call else call.copy(name = event.name)
            }
        is OpenCodeLiveContentEvent.ToolInputDelta ->
            upsertLiveTool(event.messageID, event.toolID, OpenCodeJSONValue.Str(""), projection) { call ->
                if (call.status != OpenCodeToolCall.Status.Streaming || call.input !is OpenCodeJSONValue.Str) call
                else call.copy(input = OpenCodeJSONValue.Str((call.input.string ?: "") + event.delta))
            }
        is OpenCodeLiveContentEvent.ToolInputEnded ->
            upsertLiveTool(event.messageID, event.toolID, OpenCodeJSONValue.Str(""), projection) { call ->
                if (call.status != OpenCodeToolCall.Status.Streaming || call.input !is OpenCodeJSONValue.Str) call
                else call.copy(input = OpenCodeJSONValue.Str(event.text))
            }
        is OpenCodeLiveContentEvent.ToolCalled ->
            upsertLiveTool(event.messageID, event.toolID, projection = projection) { call ->
                if (call.status != OpenCodeToolCall.Status.Streaming) call
                else call.copy(status = OpenCodeToolCall.Status.Running, input = event.input)
            }
        is OpenCodeLiveContentEvent.ToolProgress ->
            upsertLiveTool(event.messageID, event.toolID, projection = projection) { call ->
                if (call.status != OpenCodeToolCall.Status.Streaming && call.status != OpenCodeToolCall.Status.Running) call
                else call.copy(status = OpenCodeToolCall.Status.Running, metadata = event.metadata)
            }
        is OpenCodeLiveContentEvent.ToolSucceeded ->
            upsertLiveTool(event.messageID, event.toolID, projection = projection) { call ->
                call.copy(status = OpenCodeToolCall.Status.Completed, output = event.output, metadata = event.metadata)
            }
        is OpenCodeLiveContentEvent.ToolFailed ->
            upsertLiveTool(event.messageID, event.toolID, projection = projection) { call ->
                call.copy(
                    status = OpenCodeToolCall.Status.Error,
                    error = event.error,
                    errorType = event.errorType,
                    output = event.output,
                    metadata = event.metadata,
                )
            }
        is OpenCodeLiveContentEvent.StepStarted, is OpenCodeLiveContentEvent.StepStreamed, is OpenCodeLiveContentEvent.StepEnded,
        is OpenCodeLiveContentEvent.ReasoningStarted, is OpenCodeLiveContentEvent.ReasoningDelta, is OpenCodeLiveContentEvent.ReasoningEnded,
        -> null
    }

/** Marks [items][LiveProjection.items] changed for [change]; the ID when it inserted the row. */
private fun LiveProjection.upsertText(change: LiveUpsert, id: String): String? {
    if (change != LiveUpsert.Unchanged) itemsChanged = true
    return if (change == LiveUpsert.Inserted) id else null
}

/** What an upsert of a live row did. */
internal enum class LiveUpsert { Unchanged, Replaced, Inserted }

/**
 * [streaming] text is still arriving: a last line holding only a block's marker waits for its first
 * word (`holdingPendingMarker`). The Android item carries its text and the transcript parses it.
 * True when [items] changed.
 */
internal fun SessionFeature.upsertLiveText(
    id: String,
    text: String,
    streaming: Boolean = false,
    items: MutableList<OpenCodeTranscriptItem>,
): Boolean = upsertLiveTextChange(id, text, streaming, items) != LiveUpsert.Unchanged

/** [upsertLiveText], saying whether it inserted the row. */
internal fun SessionFeature.upsertLiveTextChange(
    id: String,
    text: String,
    streaming: Boolean = false,
    items: MutableList<OpenCodeTranscriptItem>,
): LiveUpsert {
    val shown = if (streaming) OpenCodeMarkdownBlock.holdingPendingMarker(text) else text
    if (shown.isBlank()) return LiveUpsert.Unchanged
    val index = items.indexOfLast { it.id == id }
    val item = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = shown, inProgress = streaming)
    if (index >= 0) {
        if (items[index] == item) return LiveUpsert.Unchanged
        items[index] = item
        return LiveUpsert.Replaced
    }
    items.add(liveInsertionIndex(SessionFeature.messageID(item), items), item)
    return LiveUpsert.Inserted
}

/**
 * Where a message's new live part goes: at the end, but ahead of a model switch recorded while
 * the message was under way (a steering send's), since history keeps the message's parts together
 * before it. IDs are ascending, so a switch with a later ID than the message came after it began;
 * a later step's message starts after the switch and goes below.
 */
private fun liveInsertionIndex(messageID: String, items: List<OpenCodeTranscriptItem>): Int {
    val rows = MessageRows(messageID)
    val floor = items.indexOfLast { rows.contains(it) }.takeIf { it >= 0 }
        ?: items.indexOfLast { TranscriptEntry.promptID(it) != null }.takeIf { it >= 0 }
    val start = floor?.let { it + 1 } ?: 0
    for (index in start until items.size) {
        val item = items[index]
        if (item is OpenCodeTranscriptItem.ModelSwitch &&
            messageID.startsWith("msg_") && item.id.startsWith("msg_") && item.id > messageID
        ) {
            return index
        }
    }
    return items.size
}

private fun upsertLiveTool(
    messageID: String,
    toolID: String,
    defaultInput: OpenCodeJSONValue = OpenCodeJSONValue.Object(emptyMap()),
    projection: LiveProjection,
    update: (OpenCodeToolCall) -> OpenCodeToolCall,
): String? {
    val items = projection.items
    val id = "$messageID-tool-$toolID"
    val index = items.indexOfLast { it.id == id }
    val existing = (items.getOrNull(index) as? OpenCodeTranscriptItem.Tool)?.call
    val call = update(
        existing ?: OpenCodeToolCall(
            callID = toolID,
            messageID = messageID,
            name = "tool",
            status = OpenCodeToolCall.Status.Streaming,
            input = defaultInput,
            output = emptyList(),
            error = null,
            metadata = emptyMap(),
        ),
    )
    val item = OpenCodeTranscriptItem.Tool(id = id, call = call)
    if (index >= 0) {
        if (items[index] != item) {
            items[index] = item
            projection.itemsChanged = true
        }
    } else {
        items.add(liveInsertionIndex(messageID, items), item)
        projection.itemsChanged = true
    }
    call.metadata["shellID"]?.string?.let { projection.noteShell(it, call.status) }
    if (call.name == "subagent" || call.name == "task") {
        val sessionID = call.metadata["sessionID"]?.string
        if (!sessionID.isNullOrEmpty()) projection.noteSubagent(sessionID)
    }
    return if (index < 0) id else null
}

// endregion
