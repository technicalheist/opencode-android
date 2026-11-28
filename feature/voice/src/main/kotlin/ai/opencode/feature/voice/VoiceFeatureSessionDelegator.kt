package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.CancelID
import ai.opencode.feature.voice.VoiceFeature.DelegateAction
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

// GPT-Live delegations steered into a real OpenCode session (the `voiceSessionDelegator`
// experiment).

private suspend fun isCancelled(): Boolean = !currentCoroutineContext().isActive

val State.hasActiveSessionDelegatorTurn: Boolean
    get() = usesSessionDelegator && sessionDelegator.hasActiveTurns

fun State.tracksSessionDelegatorTools(sessionID: String): Boolean =
    usesSessionDelegator && !phase.isTerminal && sessionDelegator.tracksTools(sessionID)

/**
 * The session this call's delegator steers, or will on its first turn, when it isn't the call's
 * own: the phone's client tools open a socket in its directory too.
 */
val State.sessionDelegatorElsewhereID: String?
    get() {
        if (!usesSessionDelegator || phase.isTerminal) return null
        val serverScopeKey = VoiceDelegatorWorkspace.serverID(target.server)
        var id = sessionDelegator.activeSessionID
        if (id == null) {
            val choice = voiceDelegatorChoice
            if (choice == VoiceDelegatorChoice.AutomaticSession) {
                id = voiceDelegatorSessionIDs[serverScopeKey]
            } else if (choice is VoiceDelegatorChoice.PickedSession && choice.picked.serverID == serverScopeKey) {
                id = choice.picked.sessionID
            }
        }
        if (id == null || id == target.sessionID || id in sessionDelegator.deletedSessionIDs) return null
        return id
    }

private fun State.withDelegator(change: (VoiceSessionDelegator.State) -> VoiceSessionDelegator.State): State =
    copy(sessionDelegator = change(sessionDelegator))

private fun State.withTurn(index: Int, change: (VoiceSessionDelegator.State.Turn) -> VoiceSessionDelegator.State.Turn): State =
    withDelegator { delegator ->
        delegator.copy(turns = delegator.turns.toMutableList().also { it[index] = change(it[index]) })
    }

/** The next state and the watch's effects. */
private fun State.stepWatch(event: ReplyWatch.Event): Pair<State, List<ReplyWatch.Effect>> {
    val (watch, effects) = replyWatch.transition(event)
    return copy(replyWatch = watch) to effects
}

private fun VoiceSessionDelegator.State.withCreatingViewedDraft(): VoiceSessionDelegator.State =
    copy(creatingViewedDraft = turns.any { it.createsViewedDraft && it.promptID == null })

internal fun VoiceFeature.delegateToSessionDelegator(state: State, callID: String, rawFraming: String?): Reduced<State, Action> {
    val isVerify = callID.startsWith("client_verify_")
    val captured = if (isVerify) VoiceTranscriptTail.Captured(state.tail, VoiceTranscriptTail.Capture.empty) else state.tail.capture()
    var next = state.copy(tail = captured.tail)
    val capture = captured.capture
    val promisedClaim = if (isVerify) {
        rawFraming?.trim()
    } else if (callID.startsWith("client_claim_")) {
        VoiceHandoff.framing(rawFraming)
    } else {
        null
    }
    val request = capture.userText.trim()
    if (request.isEmpty() && promisedClaim.isNullOrEmpty()) {
        next = next.copy(tail = next.tail.release(capture))
        return Reduced(next, completeFunction(callID, VoiceHandoff.failure(VoiceHandoff.nothingNewToSend)))
    }

    val choice = next.voiceDelegatorChoice
    if (choice == VoiceDelegatorChoice.ViewedSession && next.isOnDraft && next.sessionDelegator.creatingViewedDraft) {
        next = next.copy(tail = next.tail.release(capture))
        return Reduced(
            next,
            completeFunction(callID, VoiceHandoff.failure("The new session is still starting. Say that again in a moment.")),
        )
    }

    val serverScopeKey = VoiceDelegatorWorkspace.serverID(next.target.server)
    val cachedAutomatic = next.voiceDelegatorSessionIDs[serverScopeKey]

    var delegator = next.sessionDelegator
    var knownSessionID: String? = null
    var createsViewedDraft = false
    when (choice) {
        VoiceDelegatorChoice.BuiltIn -> Unit
        is VoiceDelegatorChoice.PickedSession ->
            if (choice.picked.serverID == serverScopeKey) knownSessionID = choice.picked.sessionID
        VoiceDelegatorChoice.ViewedSession -> {
            val id = next.target.sessionID
            if (id != null) {
                knownSessionID = id
            } else {
                createsViewedDraft = true
                delegator = delegator.copy(creatingViewedDraft = true)
            }
        }
        VoiceDelegatorChoice.AutomaticSession ->
            if (cachedAutomatic != null && cachedAutomatic !in delegator.deletedSessionIDs) knownSessionID = cachedAutomatic
    }
    if (knownSessionID != null) delegator = delegator.copy(activeSessionID = knownSessionID)

    val turnNow = next.replyWatch.userTurns
    delegator = delegator.copy(
        turns = delegator.turns + VoiceSessionDelegator.State.Turn(
            callID = callID,
            capture = capture,
            promisedClaim = promisedClaim,
            sessionID = knownSessionID,
            createsViewedDraft = createsViewedDraft,
            saidTurn = turnNow,
        ),
    )
    next = next.copy(
        sessionDelegator = delegator,
        delegatorWork = next.delegatorWork + callID,
        replyWatch = next.replyWatch.transition(ReplyWatch.Event.DelegatorStarted(requestID = callID)).first,
    )
    return startPendingSessionDelegatorAdmissions(next)
}

private fun VoiceFeature.startPendingSessionDelegatorAdmissions(
    state: State,
    coalesceReleasedIntoFirstWaiting: Boolean = false,
): Reduced<State, Action> {
    if (state.phase.isTerminal) return Reduced(state)
    var next = state
    val serverScopeKey = VoiceDelegatorWorkspace.serverID(next.target.server)
    val cachedAutomatic = next.voiceDelegatorSessionIDs[serverScopeKey]
    var coalesced = false
    val effects = mutableListOf<Effect<Action>>()

    for (index in next.sessionDelegator.turns.indices) {
        val waiting = next.sessionDelegator.turns[index]
        if (waiting.promptID != null || waiting.admitting) continue

        if (waiting.sessionID == null) {
            when (next.voiceDelegatorChoice) {
                VoiceDelegatorChoice.AutomaticSession ->
                    if (cachedAutomatic != null && cachedAutomatic !in next.sessionDelegator.deletedSessionIDs) {
                        next = next.withTurn(index) { it.copy(sessionID = cachedAutomatic) }
                    }
                VoiceDelegatorChoice.ViewedSession -> {
                    val id = next.target.sessionID
                    if (id != null) next = next.withTurn(index) { it.copy(sessionID = id, createsViewedDraft = false) }
                }
                VoiceDelegatorChoice.BuiltIn, is VoiceDelegatorChoice.PickedSession -> Unit
            }
        }

        val turns = next.sessionDelegator.turns
        val targetSessionID = turns[index].sessionID
        val blockedByEarlierAdmission = (0 until index).any { earlier ->
            val previous = turns[earlier]
            if (previous.promptID != null || !previous.admitting) return@any false
            val previousSession = previous.sessionID
            if (previousSession == null || targetSessionID == null) true else previousSession == targetSessionID
        }
        if (blockedByEarlierAdmission) continue

        if (coalesceReleasedIntoFirstWaiting && !coalesced && !turns[index].callID.startsWith("client_verify_")) {
            coalesced = true
            val previousCapture = turns[index].capture
            val merged = next.tail.release(previousCapture).capture()
            next = next.copy(tail = merged.tail)
            val capture = if (merged.capture.lines.isNotEmpty()) merged.capture else previousCapture
            next = next.withTurn(index) { it.copy(capture = capture) }
        }

        val started = startSessionDelegatorAdmission(next, index)
        next = started.state
        effects += started.effect
    }
    return Reduced(next, if (effects.isEmpty()) Effect.none() else Effect.merge(effects))
}

private fun VoiceFeature.startSessionDelegatorAdmission(state: State, index: Int): Reduced<State, Action> {
    var next = state
    val turn = next.sessionDelegator.turns[index]
    val callID = turn.callID
    val capture = turn.capture
    val promisedClaim = turn.promisedClaim
    val choice = next.voiceDelegatorChoice
    val server = next.target.server
    val serverScopeKey = VoiceDelegatorWorkspace.serverID(server)
    val cachedAutomatic = next.voiceDelegatorSessionIDs[serverScopeKey]
    val serverIDs = dependency(ServerIDClient)

    var provisionalAutomaticID: String? = null
    var draftSessionID: String? = null
    when (choice) {
        VoiceDelegatorChoice.AutomaticSession -> if (turn.sessionID == null) {
            val minted = serverIDs.sessionID()
            provisionalAutomaticID = minted
            next = next.withTurn(index) { it.copy(provisionalSessionID = minted) }
                .withDelegator { it.copy(provisionalSessionIDs = it.provisionalSessionIDs + minted) }
        }
        VoiceDelegatorChoice.ViewedSession -> if (next.isOnDraft) {
            val minted = next.draftSessionID ?: serverIDs.sessionID()
            draftSessionID = minted
            next = next.withTurn(index) { it.copy(provisionalSessionID = minted) }
                .withDelegator { it.copy(provisionalSessionIDs = it.provisionalSessionIDs + minted) }
        }
        VoiceDelegatorChoice.BuiltIn, is VoiceDelegatorChoice.PickedSession -> Unit
    }

    val messageID = serverIDs.messageID()
    next = next.withTurn(index) { it.copy(admitting = true) }
    val knownSessionID = next.sessionDelegator.turns[index].sessionID
    if (knownSessionID != null) next = next.withDelegator { it.copy(activeSessionID = knownSessionID) }

    val now = dependency(DateClient).now()
    val viewing = next.viewingSession(at = now)
    val contextLine = VoiceSessionDelegator.formatContextLine(
        viewing = viewing,
        needsInput = next.targetNeedsInput,
        draftDirectory = next.target.directory,
        screen = next.screenVisibility,
    )
    val explained = next.explainedSessionIDs(at = now)
    val recorder = dependency(VoiceCallLogClient).recorder
    val log = next.callLogID
    if (recorder != null && log != null) {
        recorder.span(log, VoiceCallLog.Track.delegator, spanID = "sdel-$callID", label = "Sending to OpenCode session")
    }
    val delegatorClient = dependency(VoiceSessionDelegator.Client)
    val outbox = dependency(VoiceAdmissionRecorder)
    val serverPersistenceID = server.persistenceID
    val targetDirectory = next.target.directory
    val draftModel = if (next.isOnDraft) next.pendingModel else null
    val preferredVoiceModel = next.pendingModel
    val lastContextBySession = next.sessionDelegator.lastContextBySession
    val deletedSessionIDs = next.sessionDelegator.deletedSessionIDs

    val effect = Effect.run<Action> { rawSend ->
        val send = rawSend.leavingRetryScope()
        val submission = VoiceSessionDelegator.Submission(
            server = server,
            choice = choice,
            viewing = viewing,
            draftDirectory = targetDirectory,
            draftModel = draftModel,
            draftSessionID = draftSessionID,
            preferredVoiceModel = preferredVoiceModel,
            cachedAutomaticSessionID = cachedAutomatic,
            provisionalAutomaticSessionID = provisionalAutomaticID,
            messageID = messageID,
            deltaLines = VoiceSessionDelegator.formatDeltaLines(capture.lines),
            promisedClaim = promisedClaim,
            contextLine = contextLine,
            lastContextBySession = lastContextBySession,
            explainedSessionIDs = explained,
            deletedSessionIDs = deletedSessionIDs,
            onAutomaticSessionResolved = { resolvedScopeKey, resolvedSessionID, created ->
                send(
                    Action.SessionDelegatorAutomaticResolved(
                        callID = callID,
                        serverScopeKey = resolvedScopeKey,
                        sessionID = resolvedSessionID,
                        created = created,
                    ),
                )
            },
        )
        val admission = try {
            delegatorClient.submit(submission)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isCancelled()) return@run
            send(Action.SessionDelegatorAdmissionFailed(callID = callID, error = error.asClientError()))
            return@run
        }
        recordAdmission(
            VoiceAdmittedPrompt(messageID = admission.promptID, text = admission.promptText),
            sessionID = admission.sessionID,
            serverKey = serverPersistenceID,
            outbox = outbox,
            send = send,
        )
        if (isCancelled()) return@run
        send(Action.SessionDelegatorAdmitted(callID = callID, admission = admission))
    }.cancellable(CancelID.WorkAdmission(callID), cancelInFlight = true)
    return Reduced(next, effect)
}

internal fun VoiceFeature.reduceSessionDelegatorAutomaticResolved(
    state: State,
    callID: String,
    serverScopeKey: String,
    sessionID: String,
    created: OpenCodeSession?,
): Reduced<State, Action> {
    if (sessionID in state.sessionDelegator.deletedSessionIDs) return Reduced(state)
    if (state.voiceDelegatorSessionIDs[serverScopeKey] != sessionID) {
        shared(VoiceSharedKeys.voiceDelegatorSessionIDs).update { it + (serverScopeKey to sessionID) }
    }
    var next = state
    var shouldReportCreated = created != null
    val index = next.sessionDelegator.turns.indexOfFirst { it.callID == callID }
    if (index >= 0) {
        next = next.withTurn(index) { it.copy(sessionID = sessionID, title = VoiceSessionDelegator.voiceSessionTitle) }
        if (created != null) {
            if (next.sessionDelegator.turns[index].reportedCreatedAutomatic) {
                shouldReportCreated = false
            } else {
                next = next.withTurn(index) { it.copy(reportedCreatedAutomatic = true) }
            }
        }
    }
    if (next.sessionDelegator.activeSessionID == null) next = next.withDelegator { it.copy(activeSessionID = sessionID) }
    if (shouldReportCreated && created != null) {
        return Reduced(next, Effect.send(Action.Delegate(DelegateAction.DelegatorSessionCreated(created))))
    }
    return Reduced(next)
}

internal fun VoiceFeature.reduceSessionDelegatorAdmitted(
    state: State,
    callID: String,
    admission: VoiceSessionDelegator.Admission,
): Reduced<State, Action> {
    val index = state.sessionDelegator.turns.indexOfFirst { it.callID == callID }
    if (index < 0) return Reduced(state)
    var next = state
    val provisionalID = next.sessionDelegator.turns[index].provisionalSessionID
    if (provisionalID != null) {
        next = next.withDelegator { it.copy(provisionalSessionIDs = it.provisionalSessionIDs - provisionalID) }
            .withTurn(index) { it.copy(provisionalSessionID = null) }
    }
    next = next.withTurn(index) { it.copy(admitting = false, createsViewedDraft = false) }

    val serverScopeKey = VoiceDelegatorWorkspace.serverID(next.target.server)
    val cachedID = admission.cachedAutomaticSessionID
    if (cachedID != null) {
        if (next.voiceDelegatorSessionIDs[serverScopeKey] != cachedID) {
            shared(VoiceSharedKeys.voiceDelegatorSessionIDs).update { it + (serverScopeKey to cachedID) }
        }
    } else if (admission.clearedStaleAutomaticCache && next.voiceDelegatorSessionIDs[serverScopeKey] != null) {
        shared(VoiceSharedKeys.voiceDelegatorSessionIDs).update { it - serverScopeKey }
    }

    next = next.copy(tail = next.tail.accept(next.sessionDelegator.turns[index].capture))
        .withDelegator { it.copy(lastContextBySession = it.lastContextBySession + (admission.sessionID to admission.contextLine)) }
    if (admission.explained) {
        next = next.recordHandoff(admission.sessionID, at = dependency(DateClient).now())
    }

    val alreadyReportedAutomatic = next.sessionDelegator.turns[index].reportedCreatedAutomatic
    next = next.withTurn(index) {
        it.copy(sessionID = admission.sessionID, title = admission.title, promptID = admission.promptID)
    }.withDelegator { it.copy(activeSessionID = admission.sessionID).withCreatingViewedDraft() }

    val watchEffects = mutableListOf<ReplyWatch.Effect>()
    val effects = mutableListOf<Effect<Action>>()
    val voiceCall = dependency(VoiceCallClient)

    // Supersede any earlier admitted delegation steered into the same session so
    // only the latest delegation speaks when the combined run settles.
    for (earlier in 0 until index) {
        val earlierTurn = next.sessionDelegator.turns[earlier]
        val oldPromptID = earlierTurn.promptID
        if (earlierTurn.sessionID != admission.sessionID || oldPromptID == null || earlierTurn.superseded) continue
        val supersededID = earlierTurn.callID
        next = next.withTurn(earlier) { it.copy(superseded = true) }
        next = next.copy(delegatorWork = next.delegatorWork.filter { it != supersededID })
        next.stepWatch(ReplyWatch.Event.Superseded(promptID = oldPromptID)).let { (s, e) -> next = s; watchEffects += e }
        next.stepWatch(ReplyWatch.Event.DelegatorFinished(requestID = supersededID, spoken = null)).let { (s, e) -> next = s; watchEffects += e }
        effects += Effect.run<Action> { voiceCall.closeDelegation(supersededID, VoiceDelegationCloseReason.superseded) }
    }

    val supersededByLaterTurn = (index + 1 until next.sessionDelegator.turns.size).any { later ->
        val laterTurn = next.sessionDelegator.turns[later]
        laterTurn.sessionID == admission.sessionID && laterTurn.promptID != null
    }
    if (supersededByLaterTurn) {
        next = next.withTurn(index) { it.copy(superseded = true) }
        next = next.copy(delegatorWork = next.delegatorWork.filter { it != callID })
        next.stepWatch(ReplyWatch.Event.DelegatorFinished(requestID = callID, spoken = null)).let { (s, e) -> next = s; watchEffects += e }
        effects += Effect.run<Action> { voiceCall.closeDelegation(callID, VoiceDelegationCloseReason.superseded) }
    } else {
        next.stepWatch(ReplyWatch.Event.DelegatorWaiting(sessionID = admission.sessionID, requestID = callID, isWaiting = true))
            .let { (s, e) -> next = s; watchEffects += e }
        val prompt = ReplyWatch.Prompt(
            promptID = admission.promptID,
            sessionID = admission.sessionID,
            title = admission.title,
            saidTurn = next.sessionDelegator.turns[index].saidTurn,
        )
        next.stepWatch(ReplyWatch.Event.Sent(listOf(prompt))).let { (s, e) -> next = s; watchEffects += e }
        // A follow-up steered into a run already working says what it's doing.
        next.sessionDelegator.progressCaption(admission.sessionID)?.let { caption ->
            effects += noteProgressActivity(callID, caption)
        }
    }

    val createdDraft = admission.createdViewedDraft
    if (createdDraft != null && next.isOnDraft && !next.phase.isTerminal) {
        next = next.adoptDraftSession(createdDraft)
        effects += Effect.send(Action.Delegate(DelegateAction.SessionCreated(createdDraft)))
        effects += updateAttachedInstructions(next)
    }
    admission.appliedDraftModel?.let { model ->
        effects += Effect.send(Action.PendingModelApplied(sessionID = admission.sessionID, model = model))
    }
    val createdVoice = admission.createdAutomaticVoice
    if (createdVoice != null && !alreadyReportedAutomatic) {
        effects += Effect.send(Action.Delegate(DelegateAction.DelegatorSessionCreated(createdVoice)))
    }

    val recorder = dependency(VoiceCallLogClient).recorder
    val log = next.callLogID
    if (recorder != null && log != null) {
        recorder.span(
            log,
            VoiceCallLog.Track.delegator,
            spanID = "sdel-$callID",
            detail = "${admission.sessionID} · ${admission.promptID}",
            end = true,
        )
    }
    AppLog.voice.info("Session delegator admitted: session=${admission.sessionID} prompt=${admission.promptID}")

    val settled = settleSessionDelegatorTurns(next, watchEffects)
    next = settled.state
    effects.add(0, settled.effect)
    val admissions = startPendingSessionDelegatorAdmissions(next)
    next = admissions.state
    effects += admissions.effect
    return Reduced(next, Effect.merge(effects))
}

internal fun VoiceFeature.reduceSessionDelegatorAdmissionFailed(
    state: State,
    callID: String,
    error: OpenCodeClientError,
): Reduced<State, Action> {
    val index = state.sessionDelegator.turns.indexOfFirst { it.callID == callID }
    if (index < 0) return Reduced(state)
    var next = state
    val turn = next.sessionDelegator.turns[index]
    next = next.withDelegator { delegator ->
        var changed = delegator.copy(turns = delegator.turns.toMutableList().also { it.removeAt(index) })
        turn.provisionalSessionID?.let { changed = changed.copy(provisionalSessionIDs = changed.provisionalSessionIDs - it) }
        changed = changed.withCreatingViewedDraft()
        val sessionID = turn.sessionID
        if (error.statusCode == 404 && sessionID != null) changed = changed.copy(deletedSessionIDs = changed.deletedSessionIDs + sessionID)
        changed
    }
    next = next.copy(tail = next.tail.release(turn.capture), delegatorWork = next.delegatorWork.filter { it != callID })

    val nextAdmissions = startPendingSessionDelegatorAdmissions(next, coalesceReleasedIntoFirstWaiting = true)
    next = nextAdmissions.state

    if (!next.sessionDelegator.hasActiveTurns) {
        next = next.withDelegator { it.copy(activeSessionID = null).clearRunningTools() }
    }
    val recorder = dependency(VoiceCallLogClient).recorder
    val log = next.callLogID
    if (recorder != null && log != null) {
        recorder.span(log, VoiceCallLog.Track.delegator, spanID = "sdel-$callID", detail = error.message, end = true, failed = true)
    }
    AppLog.voice.error("Session delegator admission failed: status=${error.statusCode ?: 0}")
    val (watched, watchEffects) = next.stepWatch(ReplyWatch.Event.DelegatorFinished(requestID = callID, spoken = null))
    next = watched
    val failEffect = answerThenRelease(
        callID,
        if (callID.startsWith("client_verify_")) "" else VoiceHandoff.failure(error.message),
        watchEffects,
        next,
    )
    return Reduced(next, Effect.merge(failEffect, nextAdmissions.effect))
}

/**
 * Settles any OpenCode Session Delegator turns whose `ReplyWatch` prompts have now
 * resolved, speaking their cleaned reply on the turn's call and releasing any other
 * watched sessions' notices behind it.
 */
internal fun VoiceFeature.settleSessionDelegatorTurns(
    state: State,
    initialWatchEffects: List<ReplyWatch.Effect>,
): Reduced<State, Action> {
    if (!state.usesSessionDelegator || state.sessionDelegator.turns.isEmpty()) {
        return Reduced(state, runReplyWatch(initialWatchEffects, state))
    }
    var next = state
    val remainingWatchEffects = initialWatchEffects.toMutableList()
    val completionEffects = mutableListOf<Effect<Action>>()
    val voiceCall = dependency(VoiceCallClient)

    while (true) {
        val turn = next.sessionDelegator.turns.firstOrNull { candidate ->
            val id = candidate.promptID
            !candidate.superseded && id != null && id in next.replyWatch.resolvedPromptIDs
        } ?: break
        val promptID = turn.promptID ?: break
        val sessionID = turn.sessionID ?: break

        val noticeOutcome = (next.replyWatch.deferredNotices + next.replyWatch.connectingNotices)
            .firstOrNull { promptID in it.promptIDs }
            ?.outcome
        val outcome = noticeOutcome
            ?: next.replyWatch.completed.lastOrNull { it.promptID == promptID }?.outcome
            ?: ReplyWatch.Outcome.FinishedWithoutText

        val coveredPromptIDs = next.sessionDelegator.turns.mapNotNull { candidate ->
            val id = candidate.promptID
            if (candidate.sessionID == sessionID && id != null && id in next.replyWatch.resolvedPromptIDs) id else null
        }
        val coveredSet = coveredPromptIDs.toSet()

        next = next.withDelegator { delegator ->
            delegator.copy(
                turns = delegator.turns.filterNot { candidate ->
                    candidate.callID == turn.callID || (
                        candidate.sessionID == sessionID &&
                            candidate.promptID != null &&
                            (candidate.superseded || candidate.promptID in coveredSet)
                        )
                },
            )
        }
        next = next.copy(delegatorWork = next.delegatorWork.filter { it != turn.callID })

        if (next.sessionDelegator.turns.none { it.sessionID == sessionID && it.promptID != null }) {
            next = next.withDelegator { it.clearRunningTools(sessionID) }
        }
        if (next.sessionDelegator.turns.none { it.sessionID == sessionID } && next.sessionDelegator.activeSessionID == sessionID) {
            next = next.withDelegator { it.copy(activeSessionID = it.turns.lastOrNull()?.sessionID) }
        }

        remainingWatchEffects.removeAll { effect ->
            when (effect) {
                is ReplyWatch.Effect.Announce -> effect.promptIDs.isNotEmpty() && effect.promptIDs.all { it in coveredSet }
                is ReplyWatch.Effect.ExpireDeferral -> effect.key in coveredSet
                else -> false
            }
        }

        var output: String
        var rawReply: String?
        var suppressionSpoken: String
        var abandonOnly: Boolean
        when (outcome) {
            is ReplyWatch.Outcome.Replied -> {
                val spoken = VoiceSessionDelegator.spokenReply(outcome.reply)
                output = VoiceHandoff.completion(spoken)
                rawReply = outcome.reply
                suppressionSpoken = spoken.ifEmpty { "Done." }
                abandonOnly = false
            }
            ReplyWatch.Outcome.FinishedWithoutText -> {
                if (turn.didSayInterim) {
                    output = ""
                    abandonOnly = true
                } else {
                    output = VoiceHandoff.completion("")
                    abandonOnly = false
                }
                rawReply = null
                suppressionSpoken = "Done."
            }
            is ReplyWatch.Outcome.ReplyUnreadable, is ReplyWatch.Outcome.Failed, is ReplyWatch.Outcome.Unreachable -> {
                val message = when (outcome) {
                    is ReplyWatch.Outcome.ReplyUnreadable -> outcome.reason
                    is ReplyWatch.Outcome.Failed -> outcome.message
                    is ReplyWatch.Outcome.Unreachable -> outcome.message
                    else -> ""
                }
                output = VoiceHandoff.failure(message)
                rawReply = null
                suppressionSpoken = message
                abandonOnly = false
            }
        }
        if (turn.callID.startsWith("client_verify_") && (rawReply == null || !VoiceActionClaim.correctsVoice(output))) {
            output = ""
            rawReply = null
            suppressionSpoken = "VERIFIED"
            abandonOnly = false
            next = next.copy(replyWatch = next.replyWatch.removeCompleted(setOf(promptID)))
            remainingWatchEffects.removeAll { it is ReplyWatch.Effect.FindBackgroundWork && it.promptID == promptID }
        }

        val releaseEffects = mutableListOf<ReplyWatch.Effect>()
        next.stepWatch(
            ReplyWatch.Event.DelegatorAnswered(sessionID = sessionID, reply = rawReply, promptIDs = coveredPromptIDs, requestID = turn.callID),
        ).let { (s, e) -> next = s; releaseEffects += e }
        next.stepWatch(ReplyWatch.Event.DelegatorFinished(requestID = turn.callID, spoken = suppressionSpoken))
            .let { (s, e) -> next = s; releaseEffects += e }

        var ending: Effect<Action> = Effect.none()
        if (next.endsAfterDelegation && next.phase == State.Phase.Live) {
            next = next.copy(endsAfterDelegation = false)
            val ended = beginEndingAfterGoodbye(next, 8.seconds)
            next = ended.state
            ending = ended.effect
        }

        if (turn.reconnectedMidTurn) {
            val earliestPromptID = coveredPromptIDs.firstOrNull() ?: promptID
            completionEffects += reconcileDelegatorToolsFromREST(next.target.server, sessionID, earliestPromptID)
        }

        if (abandonOnly) {
            val callID = turn.callID
            completionEffects += Effect.merge(
                runReplyWatch(releaseEffects, next),
                Effect.run<Action> { voiceCall.closeDelegation(callID, VoiceDelegationCloseReason.coveredByInterimSpeech) },
                ending,
            )
        } else {
            completionEffects += Effect.merge(answerThenRelease(turn.callID, output, releaseEffects, next), ending)
        }
    }

    val base = runReplyWatch(remainingWatchEffects, next)
    return Reduced(next, if (completionEffects.isEmpty()) base else Effect.merge(listOf(base) + completionEffects))
}

internal fun VoiceFeature.reduceSessionDelegatorTools(
    state: State,
    sessionID: String,
    events: List<OpenCodeLiveContentEvent>,
): Reduced<State, Action> {
    if (!state.tracksSessionDelegatorTools(sessionID)) return Reduced(state)
    val captionBefore = state.sessionDelegator.progressCaption(sessionID)
    var next = state
    val effects = mutableListOf<Effect<Action>>()
    val recorder = dependency(VoiceCallLogClient).recorder
    val log = next.callLogID
    val voiceCallCues = dependency(VoiceCallCueClient)
    for (event in events) {
        when (event) {
            is OpenCodeLiveContentEvent.ToolInputStarted -> {
                next = next.withDelegator { it.copy(toolNames = it.toolNames + (event.toolID to event.name)) }
                val label = VoiceSessionDelegator.toolCaption(event.name, null)
                next = upsertRunningTool(next, event.toolID, event.name, label, sessionID)
            }

            is OpenCodeLiveContentEvent.ToolCalled -> {
                val name = next.sessionDelegator.toolNames[event.toolID] ?: "tool"
                val label = VoiceSessionDelegator.toolCaption(name, event.input)
                next = upsertRunningTool(next, event.toolID, name, label, sessionID)
                if (recorder != null && log != null) {
                    recorder.span(log, VoiceCallLog.Track.tool, spanID = event.toolID, label = name)
                    if (label != null) recorder.note(log, VoiceCallLog.Note.Kind.marker, label)
                }
                val code = event.input.obj?.get("code")?.string
                effects += Effect.run<Action> { voiceCallCues.toolCalled(name, code) }
                val phoneEffect = VoiceSessionDelegator.parsePhoneToolEffect(event.toolID, name, event.input)
                if (phoneEffect != null && event.toolID !in next.sessionDelegator.appliedToolCallIDs) {
                    next = next.withDelegator { it.copy(appliedToolCallIDs = it.appliedToolCallIDs + event.toolID) }
                    val applied = applyPhoneToolEffect(next, phoneEffect, allowInterimSpeech = true)
                    next = applied.state
                    effects += applied.effect
                }
            }

            is OpenCodeLiveContentEvent.ToolSucceeded -> {
                next = next.withDelegator { delegator ->
                    delegator.copy(
                        runningTools = delegator.runningTools.filter { it.id != event.toolID },
                        toolNames = delegator.toolNames - event.toolID,
                    )
                }
                if (recorder != null && log != null) recorder.span(log, VoiceCallLog.Track.tool, spanID = event.toolID, end = true)
            }

            is OpenCodeLiveContentEvent.ToolFailed -> {
                next = next.withDelegator { delegator ->
                    delegator.copy(
                        runningTools = delegator.runningTools.filter { it.id != event.toolID },
                        toolNames = delegator.toolNames - event.toolID,
                    )
                }
                if (recorder != null && log != null) {
                    recorder.span(log, VoiceCallLog.Track.tool, spanID = event.toolID, end = true, failed = true)
                }
            }

            else -> Unit
        }
    }
    val caption = next.sessionDelegator.progressCaption(sessionID)
    val progressCallID = next.sessionDelegator.progressCallID(sessionID)
    if (caption != captionBefore && progressCallID != null) {
        effects += noteProgressActivity(progressCallID, caption)
    }
    return Reduced(next, if (effects.isEmpty()) Effect.none() else Effect.merge(effects))
}

internal fun VoiceFeature.reduceSessionDelegatorStreamReconnected(state: State): Reduced<State, Action> {
    if (!state.hasActiveSessionDelegatorTurn) return Reduced(state)
    var next = state
    val effects = mutableListOf<Effect<Action>>()
    val seenSessions = mutableSetOf<String>()
    for (index in next.sessionDelegator.turns.indices) {
        next = next.withTurn(index) { it.copy(reconnectedMidTurn = true) }
        val turn = next.sessionDelegator.turns[index]
        val sessionID = turn.sessionID ?: continue
        val promptID = turn.promptID ?: continue
        if (!seenSessions.add(sessionID)) continue
        effects += reconcileDelegatorToolsFromREST(next.target.server, sessionID, promptID)
    }
    return Reduced(next, if (effects.isEmpty()) Effect.none() else Effect.merge(effects))
}

internal fun VoiceFeature.reduceSessionDelegatorReconciledTools(
    state: State,
    calls: List<OpenCodeRunMessage.ToolCall>,
): Reduced<State, Action> {
    if (!state.usesSessionDelegator || state.phase.isTerminal) return Reduced(state)
    val allowInterim = state.sessionDelegator.hasActiveTurns
    var next = state
    val effects = mutableListOf<Effect<Action>>()
    for (call in calls) {
        val phoneEffect = VoiceSessionDelegator.parsePhoneToolEffect(call.id, call.name, call.input) ?: continue
        if (call.id in next.sessionDelegator.appliedToolCallIDs) continue
        next = next.withDelegator { it.copy(appliedToolCallIDs = it.appliedToolCallIDs + call.id) }
        val applied = applyPhoneToolEffect(next, phoneEffect, allowInterimSpeech = allowInterim)
        next = applied.state
        effects += applied.effect
    }
    return Reduced(next, if (effects.isEmpty()) Effect.none() else Effect.merge(effects))
}

internal fun VoiceFeature.reduceSessionDelegatorSessionDeleted(state: State, sessionID: String): Reduced<State, Action> {
    var next = state.withDelegator {
        it.copy(
            deletedSessionIDs = it.deletedSessionIDs + sessionID,
            lastContextBySession = it.lastContextBySession - sessionID,
        )
    }
    val serverScopeKey = VoiceDelegatorWorkspace.serverID(next.target.server)
    if (next.voiceDelegatorSessionIDs[serverScopeKey] == sessionID) {
        shared(VoiceSharedKeys.voiceDelegatorSessionIDs).update { it - serverScopeKey }
    }
    if (next.sessionDelegator.activeSessionID == sessionID) {
        next = next.withDelegator { it.copy(activeSessionID = null) }
    }
    next = next.withDelegator { it.clearRunningTools(sessionID) }

    val affected = next.sessionDelegator.turns.filter { it.sessionID == sessionID }
    if (affected.isEmpty()) return Reduced(next)
    next = next.withDelegator { delegator -> delegator.copy(turns = delegator.turns.filter { it.sessionID != sessionID }) }
    for (turn in affected.asReversed()) {
        if (turn.promptID != null) continue
        turn.provisionalSessionID?.let { id -> next = next.withDelegator { it.copy(provisionalSessionIDs = it.provisionalSessionIDs - id) } }
        next = next.copy(tail = next.tail.release(turn.capture))
    }
    next = next.withDelegator { it.withCreatingViewedDraft() }

    val deletedMessage = "The delegator session was deleted."
    var watchEffects = mutableListOf<ReplyWatch.Effect>()
    val effects = mutableListOf<Effect<Action>>()
    val voiceCall = dependency(VoiceCallClient)

    for (turn in affected) {
        next = next.copy(delegatorWork = next.delegatorWork.filter { it != turn.callID })
        effects += Effect.cancel(CancelID.WorkAdmission(turn.callID))
        effects += Effect.cancel(CancelID.WorkCall(turn.callID))
        turn.promptID?.let { promptID ->
            next.stepWatch(ReplyWatch.Event.WaitFailed(promptID = promptID, error = ReplyWatch.WaitError.Failed(deletedMessage)))
                .let { (s, e) -> next = s; watchEffects += e }
        }
        if (turn.superseded) {
            next.stepWatch(ReplyWatch.Event.DelegatorFinished(requestID = turn.callID, spoken = null))
                .let { (s, e) -> next = s; watchEffects += e }
            val callID = turn.callID
            effects += Effect.run<Action> { voiceCall.closeDelegation(callID, VoiceDelegationCloseReason.superseded) }
        } else {
            val promptIDs = listOfNotNull(turn.promptID)
            val release = watchEffects.toMutableList()
            next.stepWatch(
                ReplyWatch.Event.DelegatorAnswered(sessionID = sessionID, reply = null, promptIDs = promptIDs, requestID = turn.callID),
            ).let { (s, e) -> next = s; release += e }
            next.stepWatch(ReplyWatch.Event.DelegatorFinished(requestID = turn.callID, spoken = deletedMessage))
                .let { (s, e) -> next = s; release += e }
            watchEffects = mutableListOf()
            effects += answerThenRelease(turn.callID, VoiceHandoff.failure(deletedMessage), release, next)
        }
    }
    if (watchEffects.isNotEmpty()) effects.add(0, runReplyWatch(watchEffects, next))
    val admissions = startPendingSessionDelegatorAdmissions(next)
    next = admissions.state
    effects += admissions.effect
    return Reduced(next, Effect.merge(effects))
}

private fun VoiceFeature.applyPhoneToolEffect(
    state: State,
    effect: VoiceSessionDelegator.PhoneToolEffect,
    allowInterimSpeech: Boolean,
): Reduced<State, Action> {
    AppLog.voice.info("Applying ios_voice tool effect")
    val server = state.target.server
    val client = dependency(OpenCodeClient)
    suspend fun session(id: String): OpenCodeSession? = try {
        client.getSession(server, id)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
    return when (effect) {
        // SSE requests are arbitrated with MCP at App scope (`VoiceOpenSessionFeature`) before any lookup.
        is VoiceSessionDelegator.PhoneToolEffect.OpenSession -> Reduced(
            state,
            Effect.send(Action.Delegate(DelegateAction.OpenSessionRequested(toolID = effect.toolID, targetID = effect.sessionID))),
        )

        is VoiceSessionDelegator.PhoneToolEffect.PinSession -> Reduced(
            state,
            Effect.run<Action> { send ->
                val session = session(effect.sessionID) ?: return@run
                if (isCancelled()) return@run
                send(Action.DelegatorPinned(session, effect.pinned))
            }.cancellable(CancelID.CallScope),
        )

        is VoiceSessionDelegator.PhoneToolEffect.WatchSession -> {
            val saidTurn = state.replyWatch.userTurns
            Reduced(
                state,
                Effect.run<Action> { send ->
                    val session = session(effect.sessionID)
                    if (isCancelled()) return@run
                    val title = session?.let { State.usableTitle(it.title) } ?: "Session"
                    val prompt = ReplyWatch.Prompt(
                        promptID = "watch-${effect.toolID}",
                        sessionID = effect.sessionID,
                        title = title,
                        topic = effect.note,
                        saidTurn = saidTurn,
                        readsLatestSessionReply = true,
                    )
                    send(Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(prompt))))
                }.cancellable(CancelID.CallScope),
            )
        }

        is VoiceSessionDelegator.PhoneToolEffect.Say -> {
            if (!allowInterimSpeech) return Reduced(state)
            var next = state
            val turns = next.sessionDelegator.turns
            val activeIndex = turns.indexOfLast { it.promptID != null && !it.superseded }.takeIf { it >= 0 }
                ?: turns.indices.lastOrNull()
            var spokeFor: String? = null
            if (activeIndex != null) {
                next = next.withTurn(activeIndex) { it.copy(didSayInterim = true) }
                spokeFor = next.sessionDelegator.turns[activeIndex].callID
            }
            val instruction = VoiceSessionDelegator.interimSpeechInstruction(effect.text)
            val voiceCall = dependency(VoiceCallClient)
            Reduced(
                next,
                Effect.run<Action> {
                    if (isCancelled()) return@run
                    // The delegator gave its own status: no "still working" line on top.
                    if (spokeFor != null) voiceCall.stopProgressLine(spokeFor)
                    voiceCall.announce(instruction)
                }.cancellable(CancelID.CallScope),
            )
        }

        VoiceSessionDelegator.PhoneToolEffect.EndCall -> when {
            state.sessionDelegator.hasActiveTurns -> Reduced(state.copy(endsAfterDelegation = true))
            state.phase != State.Phase.Live -> Reduced(state)
            else -> beginEndingAfterGoodbye(state, 8.seconds)
        }
    }
}

private fun reconcileDelegatorToolsFromREST(server: OpenCodeServer, sessionID: String, afterPromptID: String): Effect<Action> {
    val client = dependency(OpenCodeClient)
    return Effect.run<Action> { send ->
        var messages = emptyList<OpenCodeRunMessage>()
        var cursor: String? = null
        for (page in 0 until VoiceWorkClient.backgroundSearchPages) {
            if (isCancelled()) return@run
            val fetched = try {
                client.runMessages(server, sessionID, cursor)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            } ?: return@run
            if (isCancelled()) return@run
            messages = fetched.messages + messages
            if (messages.any { it.id == afterPromptID }) break
            cursor = fetched.next ?: break
        }
        val anchor = messages.indexOfFirst { it.id == afterPromptID }
        if (anchor < 0) return@run
        val calls = messages.drop(anchor + 1).flatMap { it.toolCalls }
        if (calls.isEmpty() || isCancelled()) return@run
        send(Action.SessionDelegatorReconciledTools(calls))
    }.cancellable(CancelID.CallScope)
}

/**
 * Tells the call what a delegation's work is running, for its spoken
 * "still working" line.
 */
private fun noteProgressActivity(callID: String, caption: String?): Effect<Action> {
    val activity = caption?.let { VoiceProgressLine.activity(caption = it) }
    val voiceCall = dependency(VoiceCallClient)
    return Effect.run<Action> {
        if (isCancelled()) return@run
        voiceCall.noteProgressActivity(callID, activity)
    }.cancellable(CancelID.CallScope)
}

private fun upsertRunningTool(state: State, id: String, name: String, label: String?, sessionID: String): State = state.withDelegator { delegator ->
    val index = delegator.runningTools.indexOfFirst { it.id == id }
    if (index >= 0) {
        delegator.copy(
            runningTools = delegator.runningTools.toMutableList().also { it[index] = it[index].copy(name = name, label = label) },
        )
    } else {
        delegator.copy(runningTools = delegator.runningTools + VoiceSessionDelegator.State.RunningTool(id = id, name = name, label = label, sessionID = sessionID))
    }
}
