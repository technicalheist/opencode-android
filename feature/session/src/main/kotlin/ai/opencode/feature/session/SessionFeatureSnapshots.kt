package ai.opencode.feature.session

import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.server.model.keepingFirstPrompt
import ai.opencode.server.model.keepingFirstPrompts

// Switch snapshots. `SwitchSnapshot` and `State.Refresh` stay nested in `SessionFeature.kt` (Kotlin
// nested types can't live in another file).

fun SessionFeature.SwitchSnapshot.Companion.from(state: State): SessionFeature.SwitchSnapshot? {
    val session = state.session ?: return null
    val queued = state.queuedInputs.filter { state.promptStates[it.id]?.admission != State.PromptState.Admission.sent }
    val queuedIDs = queued.map { it.id }.toSet()
    val seenItemIDs: Set<String>
    var hasUnseenItems = false
    val mark = state.newMessages
    val markIndex = mark?.let { m -> state.items.indexOfLast { it.id == m.itemID } } ?: -1
    val baseline = state.newMessagesBaseline
    if (!state.readsOnView) {
        seenItemIDs = state.catchUpOpeningSeen ?: baseline ?: emptySet()
        hasUnseenItems = mark != null || state.newMessagesUnread
    } else if (markIndex >= 0) {
        seenItemIDs = SessionFeature.SwitchSnapshot.seenTail(state.items.subList(0, markIndex))
        hasUnseenItems = true
    } else if (baseline != null && (baseline.isNotEmpty() || state.newMessagesUnread || state.newMessagesBaselineTimestamp != null ||
            state.isTranscriptCovered || state.isBackgrounded || state.items.isEmpty())
    ) {
        seenItemIDs = baseline + state.newMessagesWatched
        hasUnseenItems = state.newMessagesUnread
    } else {
        seenItemIDs = SessionFeature.SwitchSnapshot.seenTail(state.items)
    }
    return SessionFeature.SwitchSnapshot(
        session = session,
        family = state.familySessions,
        familyReadAt = state.familyReadAt,
        shells = state.shells,
        jobs = state.jobs,
        permissions = state.permissionRequests,
        forms = state.formRequests,
        formAnswers = state.formAnswers,
        questionPageIndices = state.questionPageIndices,
        collapsedRequests = state.requestDock.collapsedRequestIDs,
        answerDrafts = state.answerDraftsIncludingBar,
        hasLoadedRequests = state.hasLoadedPendingRequests,
        completedRequests = state.completedRequestIDs,
        selectedRequest = state.selectedRequestID,
        queued = queued,
        positions = state.pendingPromptPositions.filterKeys { it in queuedIDs },
        models = state.models,
        currentModel = state.currentModel,
        pendingModel = state.pendingModel,
        queuedPicks = state.queuedPicks,
        context = state.contextUsage,
        movableWorkKey = state.movableWorkKey,
        // A claim no send has acted on yet isn't a move.
        movedWorkKeys = state.movedWorkKeys - state.autoBackgroundClaims.values.toSet(),
        seenItemIDs = seenItemIDs,
        hasUnseenItems = hasUnseenItems,
    )
}

/**
 * The New divider goes after the last row the reader saw, so only the newest seen rows matter:
 * the session reopens with its newest `initialFetchLimit` rows, and this keeps a few windows' worth
 * in case some went meanwhile (a revert, a rewrite), never every row paged in.
 */
val SessionFeature.SwitchSnapshot.Companion.seenTailLimit: Int get() = 4 * SessionFeature.initialFetchLimit

/** The IDs of the newest [seenTailLimit] rows. */
fun SessionFeature.SwitchSnapshot.Companion.seenTail(items: List<OpenCodeTranscriptItem>): Set<String> {
    val start = maxOf(0, items.size - seenTailLimit)
    val ids = HashSet<String>()
    for (index in start until items.size) ids += items[index].id
    return ids
}

fun SessionFeature.SwitchSnapshot.restore(state: State): State {
    var next = state
    val selected = next.session
    if (selected != null && session.updatedAt >= selected.updatedAt) {
        next = if (session.updatedAt > selected.updatedAt) {
            next.copy(session = session.keepingFirstPrompt(selected))
        } else {
            next.copy(session = selected.copy(revertMessageID = session.revertMessageID))
        }
    }
    next = next.copy(familySessions = family.keepingFirstPrompts(next.familySessions), familyReadAt = familyReadAt, hasLoadedPendingRequests = hasLoadedRequests)
    next = next.updateRequestDock {
        it.copy(
            permissionRequests = permissions,
            formRequests = forms,
            formAnswers = forms.associate { form -> form.id to (formAnswers[form.id] ?: form.defaultAnswers) },
            questionPageIndices = questionPageIndices,
            collapsedRequestIDs = collapsedRequests,
            answerDrafts = if (next.isAnsweringRequest) it.answerDrafts else answerDrafts,
            completedRequestIDs = completedRequests,
            // The pin comes back only while its request is still waiting.
            selectedRequestID = selectedRequest,
        )
    }
    next = next.pruneRequests()
    next = restoreSessionData(next)
    // A waiting request takes the composer from the first frame: the screen, a Catch Up card, or
    // the session Catch Up returns to.
    if (next.wantsRequestDock && !next.isAnsweringRequest) next = next.beginAnswering()
    return next
}

fun SessionFeature.SwitchSnapshot.pruneRequests(
    removing: (PendingRequest) -> Boolean = { false },
): SessionFeature.SwitchSnapshot {
    val permissions = permissions.filterNot { removing(PendingRequest.Permission(it)) }
    val forms = forms.filterNot { removing(PendingRequest.Form(it)) }
    val live = (permissions.map { it.id } + forms.map { it.id }).toSet()
    return copy(
        permissions = permissions,
        forms = forms,
        formAnswers = SessionFeature.prune(formAnswers, live),
        questionPageIndices = SessionFeature.prune(questionPageIndices, live),
        collapsedRequests = collapsedRequests.intersect(live),
        answerDrafts = SessionFeature.pruneAnswerDrafts(answerDrafts, live),
        selectedRequest = selectedRequest?.takeIf { it in live },
    )
}

/** `experimental.job.updated` for a session left: kept while it runs in this tree. */
fun SessionFeature.SwitchSnapshot.applying(job: OpenCodeJob): SessionFeature.SwitchSnapshot {
    val owned = listOf(job.ownerSessionID, job.sessionID).any { owner -> owner == session.id || family.any { it.id == owner } }
    return when {
        job.isRunning && owned -> if (jobs[job.id] == job) this else copy(jobs = jobs + (job.id to job))
        job.id in jobs -> copy(jobs = jobs - job.id)
        else -> this
    }
}

/** Family navigation keeps the family's discovery and requests. */
fun SessionFeature.SwitchSnapshot.restoreSessionData(state: State): State {
    val promoted = state.promotedPromptIDs.toSet() + SessionFeature.promptIDs(state.items)
    // The outbox is the source of truth for this phone's own sends: laid over the snapshot.
    val local = state.queuedInputs.filter { state.promptStates[it.id] != null }
    val localIDs = local.map { it.id }.toSet()
    val positions = positions.filterKeys { it !in promoted && it !in localIDs } +
        state.pendingPromptPositions.filterKeys { it in localIDs }
    var next = state.copy(
        shells = shells,
        restoredShellIDs = shells.map { it.id }.toSet(),
        jobs = jobs,
        queuedInputs = queued.filter { it.id !in promoted && it.id !in localIDs } + local,
        pendingPromptPositions = positions,
        models = models,
        currentModel = currentModel,
        pendingModel = pendingModel,
        queuedPicks = queuedPicks + state.queuedPicks,
        contextUsage = context,
        movableWorkKey = movableWorkKey,
        movedWorkKeys = movedWorkKeys,
    )
    next = next.syncVoiceModel()
    return next.copy(sessionSheet = next.sessionSheet?.copy(pendingModel = pendingModel))
}
