package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.merge
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** The page open over the session, for a live call. */
internal fun instructViewing(state: VoiceFeature.State): Effect<VoiceFeature.Action> {
    val page = state.viewing
    return sendInstructions(state) { VoiceInstructions.viewingUpdate(page) }
}

internal fun updateAttachedInstructions(state: VoiceFeature.State): Effect<VoiceFeature.Action> {
    if (state.phase != VoiceFeature.State.Phase.Live) return Effect.none()
    val context = state.instructionsContext
    return sendInstructions(state) { VoiceInstructions.attachment(context) }
}

internal fun retargetCall(state: VoiceFeature.State): Reduced<VoiceFeature.State, VoiceFeature.Action> {
    val adopted = state.adoptedDraftSessionID
    if (adopted != null && state.target.sessionID == adopted && state.abandonedCallIDs.isEmpty()) {
        return Reduced(state.copy(adoptedDraftSessionID = null))
    }
    val abandoned = state.abandonedCallIDs
    val next = state.copy(adoptedDraftSessionID = null, abandonedCallIDs = emptyList())
    val voiceCall = dependency(VoiceCallClient)
    val engine = next.activeEngine
    val abandonedMessage = "The call moved to another session; that request keeps running in its own session."
    val release: Effect<VoiceFeature.Action> = if (abandoned.isEmpty()) {
        Effect.none()
    } else {
        Effect.merge(
            Effect.cancel(abandoned.map { VoiceFeature.CancelID.WorkAdmission(it) } + abandoned.map { VoiceFeature.CancelID.WorkCall(it) }),
            Effect.merge(
                abandoned.map { callID ->
                    if (engine == VoiceEngine.gptLive) {
                        Effect.run<VoiceFeature.Action> { voiceCall.closeDelegation(callID, VoiceDelegationCloseReason.movedSession) }
                    } else {
                        Effect.run {
                            voiceCall.completeFunction(callID, VoiceHandoff.failure(abandonedMessage))
                            voiceCall.closeDelegation(callID, VoiceDelegationCloseReason.movedSession)
                        }
                    }
                },
            ),
        )
    }
    // The call hears of the move before the new session is read; requests
    // already on their way keep going to where they were sent.
    if (next.phase != VoiceFeature.State.Phase.Live) {
        return Reduced(next, Effect.merge(Effect.run { voiceCall.retarget() }, release))
    }
    val sendsDelta = engine == VoiceEngine.gptLive || next.activeProvider == ServerVoiceProvider.geminiLive
    val target = next.target
    var context = next.instructionsContext.copy(unpromptedMoveReason = if (next.unpromptedMove != null) next.moveReason else null)
    target.sessionID?.let { sessionID ->
        context = context.copy(status = if (sessionID in next.replyWatch.activeSessionIDs) "working" else "idle")
    }
    val recorder = dependency(VoiceCallLogClient).recorder
    val log = next.callLogID
    val route = next.activeRoute
    val delegatorInstructions = if (route.usesBrokerDelegator && next.usesDelegator) {
        VoiceDelegator.serviceInstructions(context = delegatorContext(next, otherRequests = next.delegatorWork.size))
    } else {
        null
    }
    val baseContext = context
    val update = Effect.run<VoiceFeature.Action> {
        voiceCall.retarget()
        if (delegatorInstructions != null) voiceCall.updateDelegatorInstructions(delegatorInstructions)
        // Optional context, on the call start's short deadline: the new
        // target's instructions never wait out a read's retries. A newer
        // move cancels this one (`CancelID.Instructions`).
        val withMessages = baseContext.copy(recentMessages = fetchRecentMessages(target))
        if (!currentCoroutineContext().isActive) return@run
        val delta = if (sendsDelta) VoiceInstructions.buildRetarget(withMessages, route = route) else null
        if (recorder != null && log != null && delta != null) recorder.note(log, VoiceCallLog.Note.Kind.injected, delta)
        voiceCall.updateInstructions(VoiceInstructions.build(withMessages, route = route), delta)
    }.cancellable(VoiceFeature.CancelID.Instructions, cancelInFlight = true)
    return Reduced(next, update.merge(release))
}

/**
 * The app came to the front or left it, or the delegator recorded the user
 * saying they're looking at the screen (or have stopped).
 */
internal fun noteScreenVisibility(from: VoiceScreenVisibility, state: VoiceFeature.State): Effect<VoiceFeature.Action> {
    val after = state.screenVisibility
    if (after == from || state.phase.isTerminal) return Effect.none()
    val recorder = dependency(VoiceCallLogClient).recorder
    val log = state.callLogID
    val marker: Effect<VoiceFeature.Action> = if (recorder != null && log != null) {
        val text = when (after) {
            VoiceScreenVisibility.visible -> "The user can see the screen"
            VoiceScreenVisibility.appHidden -> "The user can't see the screen (app in the background or phone at the ear)"
            VoiceScreenVisibility.notLooking -> "The user can't see the screen (not said to be looking)"
        }
        Effect.run { recorder.note(log, VoiceCallLog.Note.Kind.marker, text) }
    } else {
        Effect.none()
    }
    if (state.phase != VoiceFeature.State.Phase.Live) return marker
    // The voice model hears only a change in whether the user sees the screen.
    val voiceModel = if (from.userSeesScreen != after.userSeesScreen) {
        marker.merge(sendInstructions(state, VoiceFeature.CancelID.ScreenInstructions) { VoiceInstructions.screenUpdate(after) })
    } else {
        marker
    }
    // Every other route's delegator reads it with each request.
    if (!state.activeRoute.usesBrokerDelegator || !state.usesDelegator) return voiceModel
    val instructions = VoiceDelegator.serviceInstructions(context = delegatorContext(state, otherRequests = state.delegatorWork.size))
    val voiceCall = dependency(VoiceCallClient)
    val broker = Effect.run<VoiceFeature.Action> { voiceCall.updateDelegatorInstructions(instructions) }
    return voiceModel.merge(broker.cancellable(VoiceFeature.CancelID.CallScope))
}

private fun sendInstructions(
    state: VoiceFeature.State,
    cancelID: VoiceFeature.CancelID = VoiceFeature.CancelID.Instructions,
    delta: () -> String,
): Effect<VoiceFeature.Action> {
    val voiceCall = dependency(VoiceCallClient)
    val route = state.activeRoute
    val context = state.instructionsContext
    val resolvedDelta = if (route.sendsContextDelta) delta() else null
    return Effect.run<VoiceFeature.Action> {
        voiceCall.updateInstructions(VoiceInstructions.build(context, route = route), resolvedDelta)
    }.cancellable(cancelID, cancelInFlight = true)
}
