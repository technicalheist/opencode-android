package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Send
import ai.opencode.core.store.cancellable
import ai.opencode.core.tuning.DeveloperMode
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.seconds

private typealias Action = VoiceFeature.Action
private typealias State = VoiceFeature.State

private suspend fun isCancelled(): Boolean = !currentCoroutineContext().isActive

/** An effect's failure. */
internal fun Throwable.asClientError(): OpenCodeClientError =
    this as? OpenCodeClientError ?: OpenCodeClientError(message ?: toString())

/**
 * One delegation: the user's own words since the previous one are the
 * request; the voice agent's `framing` (Realtime's `delegate` text,
 * GPT-Live's spoken acknowledgement) is what the session shows.
 */
internal fun VoiceFeature.delegateWork(state: State, callID: String, rawFraming: String?): Reduced<State, Action> {
    if (state.usesSessionDelegator) return delegateToSessionDelegator(state, callID, rawFraming)
    if (state.usesDelegator) return delegateToDelegator(state, callID, rawFraming = rawFraming)
    val isClientDelegation = state.activeEngine == VoiceEngine.gptLive
    // One request creates a draft's session; a second one spoken while it is
    // being created would make another.
    if (state.isOnDraft && state.pendingWork.any(state::isHere)) {
        return Reduced(state, completeFunction(callID, VoiceHandoff.failure("The new session is still starting. Say that again in a moment.")))
    }
    val captured = if (callID.startsWith("client_verify_")) VoiceTranscriptTail.Captured(state.tail, VoiceTranscriptTail.Capture.empty) else state.tail.capture()
    var next = state.copy(tail = captured.tail)
    val capture = captured.capture
    var framing = VoiceHandoff.framing(rawFraming)
    // Nothing new was said (the voice agent delegated again on its own): its
    // framing is the request.
    var resolvedInstruction = capture.userText
    if (resolvedInstruction.isEmpty()) resolvedInstruction = framing ?: ""
    if (resolvedInstruction.isEmpty()) {
        resolvedInstruction = next.captions.lastOrNull { it.role == VoiceTranscriptTail.Role.user }?.text?.trim() ?: ""
    }
    if (resolvedInstruction.isEmpty()) {
        next = next.copy(tail = next.tail.release(capture))
        return Reduced(
            next,
            completeFunction(callID, VoiceHandoff.failure("No spoken request was available. Call delegate again with the exact request text.")),
        )
    }
    if (framing == resolvedInstruction) framing = null
    val (headed, explainsHeader) = needsHeader(next)
    next = headed.copy(
        pendingWork = headed.pendingWork + VoiceFeature.State.PendingWork(
            callID = callID,
            capture = capture,
            explainsHeader = explainsHeader,
            target = headed.target,
            move = headed.moves,
        ),
    )
    val text = VoiceHandoff.format(
        instruction = resolvedInstruction,
        transcriptDelta = capture.lines,
        context = if (isClientDelegation) next.appContextSummary else null,
        framing = framing,
        header = if (explainsHeader) VoiceHandoff.Header.full else VoiceHandoff.Header.continued,
    )
    val target = next.target.copy(
        model = next.pendingModel,
        newSessionID = if (next.isOnDraft) next.draftSessionID else null,
    )
    val voiceWork = dependency(VoiceWorkClient)
    val voiceCall = dependency(VoiceCallClient)
    val outbox = dependency(VoiceAdmissionRecorder)
    val effect = Effect.run<Action> { send ->
        val admission = try {
            voiceWork.submit(target, text)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isCancelled()) return@run
            send(VoiceFeature.Action.WorkFailed(callID = callID, message = error.asClientError().message))
            return@run
        }
        val created = admission.createdSession
        val sessionID = created?.id ?: target.sessionID
        val promptID = admission.promptID
        if (promptID != null && sessionID != null) {
            recordAdmission(
                VoiceAdmittedPrompt(messageID = promptID, text = text),
                sessionID = sessionID,
                serverKey = target.server.persistenceID,
                outbox = outbox,
                send = send,
            )
        }
        // A call that ended or moved during admission leaves the prompt with
        // the session; nothing is left to adopt, wait for, or speak.
        if (isCancelled()) return@run
        if (created != null) send(VoiceFeature.Action.SessionStarted(callID = callID, session = created))
        // Refused, the model stays waiting on the session (a draft's, on the
        // one it just created) and nothing was sent.
        admission.modelError?.let { error ->
            send(VoiceFeature.Action.WorkFailed(callID = callID, message = "Couldn’t switch model: ${error.message}"))
            return@run
        }
        val model = target.model
        if (model != null && sessionID != null) send(VoiceFeature.Action.PendingModelApplied(sessionID = sessionID, model = model))
        if (isCancelled() || sessionID == null || promptID == null) return@run
        send(VoiceFeature.Action.WorkAdmitted(callID = callID, promptID = promptID))
    }.cancellable(VoiceFeature.CancelID.WorkAdmission(callID), cancelInFlight = true)
    return Reduced(next, effect)
}

/**
 * GPT-Live with the delegator: the request goes to an invisible agent that
 * works across sessions, moving the screen and sending Voice messages.
 *
 * [onCall]: Server Voice, where GPT-Live already handed the request to
 * the delegator on its Responses backend; the phone runs its tools.
 */
internal fun VoiceFeature.delegateToDelegator(
    state: State,
    callID: String,
    onCall: Boolean = false,
    rawFraming: String? = null,
): Reduced<State, Action> {
    val apiKey = state.activeEngine?.let { state.callAPIKey(it) }?.trim() ?: ""
    if (!onCall && apiKey.isEmpty()) {
        return Reduced(state, completeFunction(callID, VoiceHandoff.failure("No OpenAI API key.")))
    }
    val isVerify = callID.startsWith("client_verify_")
    val captured = if (isVerify) VoiceTranscriptTail.Captured(state.tail, VoiceTranscriptTail.Capture.empty) else state.tail.capture()
    var next = state.copy(tail = captured.tail)
    val capture = captured.capture
    // Only the user's own words are a request, except on a client_claim_N / client_verify_N backstop.
    val promisedClaim = if (callID.startsWith("client_claim_")) VoiceHandoff.framing(rawFraming) else null
    val userRequest = capture.userText.trim()
    val request = if (isVerify) {
        rawFraming?.trim() ?: ""
    } else {
        userRequest.ifEmpty { promisedClaim?.let { "Voice promised: \"$it\"" } ?: "" }
    }
    if (!onCall && request.isEmpty()) {
        next = next.copy(tail = next.tail.release(capture))
        return Reduced(next, completeFunction(callID, VoiceHandoff.failure(VoiceHandoff.nothingNewToSend)))
    }
    // The delegator has it now; later words travel with the next request.
    // Its prompts count from now, the delegation; one for a turn GPT-Live
    // held back counts from that turn once it's unambiguous (`heldBackTurns`).
    val saidTurns = capture.userWords.filter { it != VoiceTranscriptTail.omittedNote }
    val turnNow = next.replyWatch.userTurns
    next = next.copy(
        tail = next.tail.accept(capture),
        delegatorWork = next.delegatorWork + callID,
        replyWatch = next.replyWatch.transition(ReplyWatch.Event.DelegatorStarted(requestID = callID)).first,
    )
    val now = dependency(DateClient).now()
    val viewing = next.viewingSession(at = now)
    val explained = next.explainedSessionIDs(at = now)
    val recorder = dependency(VoiceCallLogClient).recorder
    val log = next.callLogID
    // Resolved prompts too: their results may still be waiting to be spoken,
    // and a read of the session cancels them.
    val watchedPromptIDs = LinkedHashMap<String, List<String>>()
    for ((sessionID, promptID) in next.replyWatch.completed.map { it.sessionID to it.promptID } + next.replyWatch.watched.map { it.sessionID to it.promptID }) {
        val earlier = watchedPromptIDs[sessionID]
        watchedPromptIDs[sessionID] = if (earlier == null) listOf(promptID) else earlier + listOf(promptID).filter { it !in earlier }
    }
    val server = next.target.server
    val serverID = server.persistenceID
    val pinnedSessionKeys = next.pinnedSessionKeys
    val pinnedOrder = VoiceDelegatorWorkspace.pins(pinnedSessionKeys, server)
    val board = dependency(VoiceSessionBoardClient)
    val developerMode = DeveloperMode.isEnabled
    val voiceScreenshot = dependency(VoiceScreenshotClient)
    val voiceCallCues = dependency(VoiceCallCueClient)
    val experimentalFeatures = dependency(ExperimentalFeatures)
    val start = VoiceDelegatorWorkspace.Start(
        server = server,
        viewing = viewing,
        draftDirectory = next.target.directory,
        draftModel = if (next.isOnDraft) next.pendingModel else null,
        draftSessionID = if (next.isOnDraft) next.draftSessionID else null,
        viewingModel = if (next.isOnDraft) null else next.pendingModel,
        transcriptDelta = capture.lines,
        explained = explained,
        pinned = pinnedOrder.toSet(),
        pinnedOrder = pinnedOrder,
        servers = VoiceFeature.savedServers,
        pinnedSessionKeys = pinnedSessionKeys,
        board = { board.current(serverID) },
        viewingNeedsInput = next.targetNeedsInput,
        watchedPromptIDs = watchedPromptIDs,
        callLogSnapshot = { if (recorder != null && log != null) recorder.snapshot(log) else null },
        developerMode = developerMode,
        appState = if (developerMode) {
            VoiceAppState(
                next,
                experiments = ExperimentalFeature.entries.filter { experimentalFeatures(it) }.map { it.rawValue },
                developerMode = true,
            )
        } else {
            null
        },
        captureScreenshot = voiceScreenshot.capture,
        appLog = VoiceAppLog::recentLines,
        favoriteModelIDs = next.favoriteModelIDs,
        modelVariants = next.modelVariants,
    )
    val viewingText = delegatorContext(next, otherRequests = maxOf(0, next.delegatorWork.size - 1))
    val text = VoiceDelegator.requestText(viewing = viewingText, conversation = next.tail.lines, request = request)
    val previous = next.delegatorResponseID
    if (recorder != null && log != null) recorder.note(log, VoiceCallLog.Note.Kind.received, text)
    val client = dependency(OpenCodeClient)
    val delegator = dependency(VoiceDelegatorClient)
    val voiceCall = dependency(VoiceCallClient)
    val work = dependency(VoiceWorkClient)
    val outbox = dependency(VoiceAdmissionRecorder)
    val effect = Effect.run<Action> { rawSend ->
        val tracked = java.util.Collections.synchronizedList(mutableListOf<VoiceDelegator.SentPrompt>())
        // Its running tools, newest last, for the spoken "still working" line.
        val running = mutableListOf<Pair<String, String?>>()
        // Its tools send from inside their retry budget; what those actions
        // start (a session opening, its reads) runs on the default policy.
        val send = rawSend.leavingRetryScope()
        val workspace = VoiceDelegatorWorkspace(
            client = client,
            start = start,
            effects = VoiceDelegatorWorkspace.Effects(
                open = { session -> send(VoiceFeature.Action.DelegatorOpened(session)) },
                setPinned = { session, pinned -> send(VoiceFeature.Action.DelegatorPinned(session, pinned)) },
                openDraft = { directory -> send(VoiceFeature.Action.DelegatorOpenedDraft(directory)) },
                switchedModel = { sessionID, model -> send(VoiceFeature.Action.DelegatorSwitchedModel(sessionID, model)) },
                appliedPick = { sessionID, model -> send(VoiceFeature.Action.PendingModelApplied(sessionID, model)) },
                keptPick = { sessionID, model -> send(VoiceFeature.Action.DelegatorKeptPick(sessionID, model)) },
                settledRequest = { sessionID -> send(VoiceFeature.Action.DelegatorSettledRequest(sessionID)) },
                endCall = { send(VoiceFeature.Action.DelegatorRequestedEnd) },
                setUserLooking = { looking -> send(VoiceFeature.Action.DelegatorNotedLooking(looking)) },
                explained = { sessionID -> send(VoiceFeature.Action.DelegatorExplained(sessionID)) },
                sent = { sessionID, prompt ->
                    recordAdmission(prompt, sessionID = sessionID, serverKey = serverID, outbox = outbox, send = send)
                },
                answered = { sessionID, reply, promptIDs ->
                    send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorAnswered(sessionID = sessionID, reply = reply, promptIDs = promptIDs, requestID = callID)))
                },
                tracked = { prompt ->
                    tracked += prompt
                    send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(prompt, saidTurn = turnNow)))))
                },
                waiting = { sessionID, isWaiting ->
                    send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DelegatorWaiting(sessionID = sessionID, requestID = callID, isWaiting = isWaiting)))
                },
                userSpoke = { voiceCall.nextUserWords() },
                awaitIdle = work.awaitIdle,
                // The call log only: any append mid-turn makes GPT-Live start talking
                // from half the picture, and the delegator's answer then waits behind it.
                note = { note -> if (recorder != null && log != null) recorder.note(log, VoiceCallLog.Note.Kind.marker, note) },
                trace = { trace ->
                    if (!isVerify && trace is VoiceDelegator.Trace.ToolStarted) voiceCallCues.toolCalled(trace.name, trace.arguments)
                    // Outer null: no change (a round); inner null: no named tool running.
                    val activity: Pair<String?, Unit>? = when (trace) {
                        is VoiceDelegator.Trace.ToolStarted -> synchronized(running) {
                            running += trace.id to VoiceProgressLine.activityForDelegatorTool(trace.name)
                            running.lastOrNull()?.second to Unit
                        }
                        is VoiceDelegator.Trace.ToolEnded -> synchronized(running) {
                            running.removeAll { it.first == trace.id }
                            running.lastOrNull()?.second to Unit
                        }
                        is VoiceDelegator.Trace.RoundStarted, is VoiceDelegator.Trace.RoundEnded -> null
                    }
                    // A tool finishing after the call ended tells no later call.
                    if (activity != null && !isCancelled()) voiceCall.noteProgressActivity(callID, activity.first)
                    if (recorder != null && log != null) {
                        when (trace) {
                            is VoiceDelegator.Trace.RoundStarted -> recorder.span(
                                log, VoiceCallLog.Track.delegator, spanID = "$callID-r${trace.round}",
                                label = if (trace.round == 0) "Thinking" else "Thinking (after tools)",
                            )
                            is VoiceDelegator.Trace.RoundEnded -> recorder.span(
                                log, VoiceCallLog.Track.delegator, spanID = "$callID-r${trace.round}",
                                label = trace.label, detail = trace.detail, end = true, failed = trace.failed,
                            )
                            is VoiceDelegator.Trace.ToolStarted -> recorder.span(
                                log, VoiceCallLog.Track.tool, spanID = trace.id, label = trace.name, detail = trace.arguments,
                            )
                            is VoiceDelegator.Trace.ToolEnded -> recorder.span(
                                log, VoiceCallLog.Track.tool, spanID = trace.id, detail = trace.result, end = true,
                                failed = trace.result.startsWith("{\"error\""),
                            )
                        }
                    }
                },
            ),
        )
        val result: Result<VoiceDelegator.Outcome> = try {
            Result.success(
                if (onCall) {
                    delegator.runOnCall(delegationID = callID, rounds = voiceCall.delegatorRounds, tools = workspace.tools)
                } else {
                    delegator.run(apiKey = apiKey, request = text, previousResponseID = previous, tools = workspace.tools)
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error.asClientError())
        }
        if (isCancelled()) return@run
        // Before its answer releases the results held for it.
        val heldBack = ReplyWatch.heldBackTurns(tracked.toList().map { it.promptID to it.topic }, among = saidTurns, now = turnNow)
        if (heldBack.isNotEmpty()) send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.HeldBack(heldBack)))
        send(VoiceFeature.Action.DelegatorFinished(callID, result))
    }.cancellable(VoiceFeature.CancelID.WorkCall(callID), cancelInFlight = true)
    return Reduced(next, effect)
}

/**
 * A delegator's answer, then the results it released out loud: they reach
 * the call after it, so they wait briefly for it to be spoken and never
 * cut it off. Only that speech waits behind the answer, in one task scoped
 * to the call: ending the call stops it before any result goes out, so
 * none reaches a call started later on the same host. The reply watch's
 * bookkeeping (watches, timers and their cancellations) runs at once, so
 * none of it is left behind.
 */
internal fun answerThenRelease(
    callID: String,
    output: String,
    watchEffects: List<ReplyWatch.Effect>,
    state: State,
): Effect<Action> {
    val announcements = watchEffects.filterIsInstance<ReplyWatch.Effect.Announce>()
    val bookkeeping = watchEffects.filter { it !is ReplyWatch.Effect.Announce }
    val voiceCall = dependency(VoiceCallClient)
    val speech = Effect.run<Action> {
        if (isCancelled()) return@run
        voiceCall.completeFunction(callID, output)
        for (announcement in announcements) {
            if (isCancelled()) return@run
            voiceCall.announce(sessionID = announcement.sessionID, promptIDs = announcement.promptIDs, text = announcement.text)
        }
    }
    return Effect.merge(runReplyWatch(bookkeeping, state), speech.cancellable(VoiceFeature.CancelID.CallScope))
}

internal fun runReplyWatch(effects: List<ReplyWatch.Effect>, state: State): Effect<Action> {
    if (effects.isEmpty()) return Effect.none()
    val server = state.target.server
    val voiceWork = dependency(VoiceWorkClient)
    val voiceCall = dependency(VoiceCallClient)
    val clock = dependency(Clock)
    val tcaEffects = mutableListOf<Effect<Action>>()
    for (effect in effects) {
        when (effect) {
            is ReplyWatch.Effect.StartWait -> tcaEffects += watchPrompt(effect.prompt, server, delaySeconds = null, settledOnly = false, usesSessionDelegator = state.usesSessionDelegator)
            is ReplyWatch.Effect.RetryWait ->
                tcaEffects += watchPrompt(
                    effect.prompt, server, delaySeconds = effect.delaySeconds, settledOnly = effect.prompt.isFetchingSettledReply,
                    usesSessionDelegator = state.usesSessionDelegator,
                )
            is ReplyWatch.Effect.FetchSettledReply -> tcaEffects += watchPrompt(effect.prompt, server, delaySeconds = null, settledOnly = true, usesSessionDelegator = state.usesSessionDelegator)
            is ReplyWatch.Effect.ProbeStepEnded -> tcaEffects += probeWatchedStep(effect.prompt, server)
            is ReplyWatch.Effect.CancelWatch -> tcaEffects += Effect.cancel(
                listOf(VoiceFeature.CancelID.ReplyWatch(effect.promptID), VoiceFeature.CancelID.ReplyStepProbe(effect.promptID)),
            )
            is ReplyWatch.Effect.RefreshActiveSessions -> {
                val source = effect.source
                tcaEffects += Effect.run<Action> { send ->
                    val ids = try {
                        voiceWork.activeSessionIDs(server)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        return@run
                    }
                    if (isCancelled()) return@run
                    send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.ActiveSessionsSnapshot(ids, source = source)))
                }.cancellable(VoiceFeature.CancelID.ReplyActiveRefresh, cancelInFlight = true)
            }
            is ReplyWatch.Effect.Announce -> tcaEffects += Effect.run {
                if (isCancelled()) return@run
                voiceCall.announce(sessionID = effect.sessionID, promptIDs = effect.promptIDs, text = effect.text)
            }
            is ReplyWatch.Effect.CancelWireAnnouncements -> tcaEffects += Effect.run { voiceCall.cancelPendingAnnouncements(effect.promptIDs) }
            is ReplyWatch.Effect.ExpireDeferral -> tcaEffects += Effect.run<Action> { send ->
                clock.sleep(effect.after)
                send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.DeferralExpired(key = effect.key)))
            }.cancellable(VoiceFeature.CancelID.ReplyDeferral(effect.key), cancelInFlight = true)
            is ReplyWatch.Effect.CancelDeferralExpiry -> tcaEffects += Effect.cancel(VoiceFeature.CancelID.ReplyDeferral(effect.key))
            is ReplyWatch.Effect.FindBackgroundWork -> tcaEffects += Effect.run<Action> { send ->
                val found = try {
                    voiceWork.backgroundWork(server, effect.sessionID, effect.promptID, effect.reply)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@run
                }
                if (found.isEmpty() || isCancelled()) return@run
                send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Sent(found.map { ReplyWatch.Prompt(it, title = effect.title, saidTurn = effect.turn) })))
            }.cancellable(VoiceFeature.CancelID.CallScope)
            is ReplyWatch.Effect.Log -> recordReplyWatchLog(effect.entry, state)
        }
    }
    return if (tcaEffects.isEmpty()) Effect.none() else Effect.merge(tcaEffects)
}

private fun watchPrompt(
    prompt: ReplyWatch.Prompt,
    server: OpenCodeServer,
    delaySeconds: Int?,
    settledOnly: Boolean,
    usesSessionDelegator: Boolean,
): Effect<Action> {
    val voiceWork = dependency(VoiceWorkClient)
    val clock = dependency(Clock)
    return ownsRetries { send ->
        if (delaySeconds != null && delaySeconds > 0) clock.sleep(delaySeconds.seconds)
        if (isCancelled()) return@ownsRetries
        try {
            val work = prompt.background
            if (work != null && usesSessionDelegator) {
                // Under the session delegator, the parent Voice session receives the
                // `<subagent>` synthetic and relays a call-aware correction; never
                // announce the child's raw text or repeat a reply a delegation turn covers.
                if (!settledOnly) voiceWork.awaitIdle(server, work.sessionID)
                val relayed = voiceWork.relayedBackgroundReply(server, work.parentSessionID, work.sessionID)
                if (isCancelled()) return@ownsRetries
                val cleaned = relayed?.let(VoiceSessionDelegator::spokenReply)?.trim() ?: ""
                if (cleaned.isNotEmpty()) {
                    send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = cleaned)))
                } else {
                    send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.Superseded(promptID = prompt.promptID)))
                }
                return@ownsRetries
            }
            if (prompt.background != null || prompt.readsLatestSessionReply) {
                // Background work or an `ios_voice_watch_session` watch has no prompt
                // ID sent from the phone: its result is the session's final reply.
                if (!settledOnly) voiceWork.awaitIdle(server, prompt.sessionID)
                val reply = voiceWork.backgroundReply(server, prompt.sessionID)
                if (isCancelled()) return@ownsRetries
                send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = reply)))
                return@ownsRetries
            }
            var reply = if (settledOnly) {
                voiceWork.fetchReply(server, prompt.sessionID, prompt.promptID)
            } else {
                voiceWork.awaitReply(server, prompt.sessionID, prompt.promptID)
            }
            if (reply == null) {
                // Steered into a run another delegation joined before any text: its
                // reply is the run's.
                reply = voiceWork.fetchRunReply(server, prompt.sessionID, prompt.promptID)
            }
            if (isCancelled()) return@ownsRetries
            send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = prompt.promptID, reply = reply)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (isCancelled()) return@ownsRetries
            val waitError = ReplyWatch.WaitError(error.asClientError())
            val stillActive: Boolean? = if (waitError is ReplyWatch.WaitError.Retryable && !settledOnly) {
                voiceWork.checkSessionActive(server, prompt.sessionID)
            } else {
                null
            }
            if (isCancelled()) return@ownsRetries
            send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.WaitFailed(promptID = prompt.promptID, error = waitError, sessionStillActive = stillActive)))
        }
    }.cancellable(VoiceFeature.CancelID.ReplyWatch(prompt.promptID), cancelInFlight = true)
}

private fun probeWatchedStep(prompt: ReplyWatch.Prompt, server: OpenCodeServer): Effect<Action> {
    val voiceWork = dependency(VoiceWorkClient)
    return Effect.run<Action> { send ->
        if (voiceWork.checkSessionActive(server, prompt.sessionID) != false || isCancelled()) return@run
        send(VoiceFeature.Action.ReplyWatch(ReplyWatch.Event.SseExecutionEnded(sessionID = prompt.sessionID, source = ReplyWatch.SettlementSource.stepEnded)))
    }.cancellable(VoiceFeature.CancelID.ReplyStepProbe(prompt.promptID), cancelInFlight = true)
}

private fun recordReplyWatchLog(entry: ReplyWatch.LogEntry, state: State) {
    val recorder = dependency(VoiceCallLogClient).recorder ?: return
    val log = state.callLogID ?: return
    val marker = VoiceCallLog.Note.Kind.marker
    when (entry) {
        is ReplyWatch.LogEntry.WatchStarted -> {
            recorder.span(
                log, VoiceCallLog.Track.delegator, spanID = "watch-${entry.promptID}",
                label = "Waiting on “${entry.title}”", detail = "${entry.sessionID} · ${entry.promptID}",
            )
            recorder.note(log, marker, "Watching “${entry.title}” (${entry.promptID})")
        }
        is ReplyWatch.LogEntry.Settled -> recorder.note(log, marker, "“${entry.title}” settled via ${entry.source.rawValue} (${entry.promptID})")
        is ReplyWatch.LogEntry.Retry -> recorder.note(log, marker, "Wait retry #${entry.attempt} for “${entry.title}”: ${entry.error}")
        is ReplyWatch.LogEntry.Resolved -> {
            recorder.span(
                log, VoiceCallLog.Track.delegator, spanID = "watch-${entry.promptID}",
                detail = entry.outcome.contextSummary, end = true, failed = entry.outcome.isFailure,
            )
            recorder.note(log, marker, entry.outcome.logMarker(title = entry.title, promptID = entry.promptID))
        }
        is ReplyWatch.LogEntry.NoticeSuppressed -> recorder.note(log, marker, "Suppressed held notice for “${entry.title}” (answered by delegator)")
        is ReplyWatch.LogEntry.NoticeDropped -> recorder.note(log, marker, "Dropped notice for “${entry.title}” (phase: ${entry.phase.rawValue})")
    }
}

/**
 * A reply wait's loop is its only retry layer (`ReplyWatch`, the work
 * call's loop): the reads and waits inside go once each, so a failing
 * connection costs one attempt per pass, not a request policy's window
 * at every layer. A long poll that times out while the run goes on still
 * re-arms inside `waitForIdle`.
 */
internal fun ownsRetries(operation: suspend (Send<Action>) -> Unit): Effect<Action> = runOwningRetries(operation)

/**
 * Keep every voice engine's accepted sends visible both now and on entry. Voice hands a
 * [VoiceAdmittedPrompt] to [VoiceAdmissionRecorder], which the app installs over the composer's
 * outbox.
 */
internal suspend fun recordAdmission(
    prompt: VoiceAdmittedPrompt,
    sessionID: String,
    serverKey: String,
    outbox: VoiceAdmissionRecorder,
    send: Send<VoiceFeature.Action>,
) {
    // Even a call cancelled during submission leaves its accepted prompt.
    outbox.recordAdmitted(prompt, sessionID, serverKey)
    if (isCancelled()) return
    send(VoiceFeature.Action.Delegate(VoiceFeature.DelegateAction.PromptAdmitted(sessionID)))
}

/**
 * D4a: the long header goes with a call's first delegation into a session,
 * and again once the session has compacted (the summary drops it) or
 * after `VoiceHandoff.headerLapse` without a delegation. Records this one.
 */
private fun needsHeader(state: State): Pair<State, Boolean> {
    // A draft's first request creates its session: always explained.
    val sessionID = state.target.sessionID ?: return state to true
    val now = dependency(DateClient).now()
    val needs = !state.isSessionExplained(sessionID, at = now)
    return state.recordHandoff(sessionID, at = now) to needs
}

internal fun waitForWorkReply(callID: String, server: OpenCodeServer, sessionID: String, promptID: String): Effect<Action> {
    val voiceWork = dependency(VoiceWorkClient)
    val clock = dependency(Clock)
    return ownsRetries { send ->
        var attempts = 0
        var activeRetries = 0
        while (true) {
            val error = try {
                val reply = voiceWork.awaitReply(server, sessionID, promptID)
                send(VoiceFeature.Action.WorkReplied(callID = callID, reply = reply))
                return@ownsRetries
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error.asClientError()
            }
            if (isCancelled()) return@ownsRetries
            if (!error.isRetryableWaitError) {
                send(VoiceFeature.Action.WorkFailed(callID = callID, message = error.message))
                return@ownsRetries
            }
            val delay: Long
            if (voiceWork.checkSessionActive(server, sessionID) == true) {
                if (isCancelled()) return@ownsRetries
                activeRetries += 1
                if (activeRetries >= VoiceFeature.maxActiveWaitRetries) {
                    send(VoiceFeature.Action.WorkFailed(callID = callID, message = error.message))
                    return@ownsRetries
                }
                attempts = 0
                delay = RetryPolicy.sessionWait.ceiling(activeRetries).inWholeSeconds
            } else {
                if (isCancelled()) return@ownsRetries
                attempts += 1
                if (attempts >= VoiceFeature.maxWaitAttempts) {
                    send(VoiceFeature.Action.WorkFailed(callID = callID, message = error.message))
                    return@ownsRetries
                }
                delay = RetryPolicy.request.ceiling(attempts).inWholeSeconds
            }
            clock.sleep(delay.seconds)
        }
    }.cancellable(VoiceFeature.CancelID.WorkCall(callID), cancelInFlight = true)
}

/**
 * A loop that is its own retry layer: the client calls inside go once
 * each (`NetworkRetry.policy` is `.once`). The actions it sends start
 * effects of their own, which run outside that scope: a coroutine-local
 * policy would otherwise pass to every effect they start, and their
 * requests would never be retried.
 */
internal fun <A> runOwningRetries(operation: suspend (Send<A>) -> Unit): Effect<A> = Effect.run { send ->
    val outside = send.leavingRetryScope()
    withRetryPolicy(RetryPolicy.once) { operation(outside) }
}

/**
 * Sends with no retry scope, so the effects an action starts never
 * inherit the sender's `.once` or budget (`NetworkRetry.outsideScope`).
 */
internal fun <A> Send<A>.leavingRetryScope(): Send<A> = Send { action -> NetworkRetry.outsideScope { this(action) } }
