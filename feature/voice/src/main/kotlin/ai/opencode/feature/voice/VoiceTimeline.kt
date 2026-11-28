package ai.opencode.feature.voice

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer

/**
 * Marks each turn of a call on the diagnostics timeline: timing only, plus short excerpts while
 * Include Transcripts is on. Composed ahead of [VoiceFeature]'s own reducer, so it sees the state
 * an action arrives to. It never changes state or starts effects.
 */
class VoiceTimeline : Reducer<VoiceFeature.State, VoiceFeature.Action> {
    override fun reduce(state: VoiceFeature.State, action: VoiceFeature.Action): Reduced<VoiceFeature.State, VoiceFeature.Action> {
        val diagnostics = dependency(VoiceDiagnostics)
        fun mark(name: String, detail: String? = null, excerpt: String? = null) =
            diagnostics.voice(name, state.target.sessionID, detail, excerpt)

        val sessionID = state.target.sessionID
        when (action) {
            VoiceFeature.Action.Start -> {
                mark("voice_call_start", if (state.isOnDraft) "target=draft" else "target=session")
                // Lab UI tests count starts: Settings over a call must never restart it.
                diagnostics.labMark("voice_call_start")
            }
            VoiceFeature.Action.RejoinTapped -> mark("voice_rejoin")
            VoiceFeature.Action.CallDropped -> if (!state.phase.isTerminal) mark("voice_dropped")
            is VoiceFeature.Action.CallEnded ->
                if (!state.phase.isTerminal) mark("voice_call_ended", action.errorMessage?.let { "error=$it" })
            is VoiceFeature.Action.WorkAdmitted -> mark("voice_work_admitted", "callID=${action.callID} promptID=${action.promptID}")
            is VoiceFeature.Action.SessionStarted -> mark("voice_session_created", "callID=${action.callID} session=${action.session.id}")
            is VoiceFeature.Action.DraftSessionCreated -> if (state.isOnDraft) mark("voice_session_adopted", "session=${action.session.id}")
            VoiceFeature.Action.TargetChanged -> {
                if (state.adoptedDraftSessionID == null || state.target.sessionID != state.adoptedDraftSessionID || state.abandonedCallIDs.isNotEmpty()) {
                    mark("voice_moved", if (state.isOnDraft) "target=draft" else "target=${sessionID ?: "-"}")
                }
            }
            // Whether a page is up, never which one.
            is VoiceFeature.Action.ViewingChanged ->
                mark("voice_viewing", action.page?.let { if (it.isOpen) "page=open" else "page=collapsed" } ?: "page=none")
            is VoiceFeature.Action.AppVisibilityChanged ->
                if (action.visible != state.isAppVisible) mark("voice_app_visible", if (action.visible) "visible=yes" else "visible=no")
            is VoiceFeature.Action.DelegatorNotedLooking ->
                if (action.looking != state.userLooking) mark("voice_user_looking", if (action.looking) "looking=yes" else "looking=no")
            is VoiceFeature.Action.WorkReplied -> mark("voice_reply_received", "callID=${action.callID}", excerpt = action.reply)
            is VoiceFeature.Action.WorkFailed -> mark("voice_work_failed", "callID=${action.callID} error=${action.message}")
            VoiceFeature.Action.MuteTapped -> mark(if (state.isMuted) "voice_unmuted" else "voice_muted")
            VoiceFeature.Action.EndTapped -> if (!state.phase.isTerminal) mark("voice_end_tapped")
            is VoiceFeature.Action.CallEvent -> callEvent(action.event, state, ::mark)
            is VoiceFeature.Action.DelegatorOpened -> mark("voice_delegator_opened", "session=${action.session.id}")
            is VoiceFeature.Action.DelegatorSwitchedModel ->
                mark("voice_delegator_switched_model", "session=${action.sessionID ?: "draft"} model=${action.model.providerID}/${action.model.id}")
            is VoiceFeature.Action.DelegatorFinished -> {
                val outcome = action.result.getOrNull()
                if (outcome != null) {
                    mark("voice_delegator_done", "callID=${action.callID} watching=${outcome.unanswered.size}", excerpt = outcome.reply)
                } else {
                    mark("voice_delegator_failed", "callID=${action.callID} error=${action.result.exceptionOrNull()?.clientMessage ?: ""}")
                }
            }
            is VoiceFeature.Action.ReplyWatch -> {
                val event = action.event
                if (event is ReplyWatch.Event.WaitSucceeded) mark("voice_delegated_reply", "promptID=${event.promptID}", excerpt = event.reply)
            }
            is VoiceFeature.Action.SessionDelegatorAdmitted -> mark(
                "voice_session_delegator_admitted",
                "callID=${action.callID} session=${action.admission.sessionID} promptID=${action.admission.promptID}",
            )
            is VoiceFeature.Action.SessionDelegatorAdmissionFailed ->
                mark("voice_session_delegator_failed", "callID=${action.callID} error=${action.error.message}")
            is VoiceFeature.Action.ScreenRequest -> when (action.action) {
                is VoiceScreenRequestFeature.Action.Requested -> mark("voice_screen_requested")
                VoiceScreenRequestFeature.Action.ShareTapped ->
                    if (state.screenRequest.pending != null) mark("voice_screen_shared") else null
                VoiceScreenRequestFeature.Action.NotNowTapped ->
                    if (state.screenRequest.pending != null) mark("voice_screen_declined") else null
                VoiceScreenRequestFeature.Action.TimedOut ->
                    if (state.screenRequest.pending != null) mark("voice_screen_request_timed_out") else null
                else -> null
            }
            is VoiceFeature.Action.OpenLink -> when (action.action) {
                is VoiceOpenLinkFeature.Action.Requested -> mark("voice_open_link_requested")
                VoiceOpenLinkFeature.Action.OpenTapped ->
                    if (state.openLink.pending != null) mark("voice_open_link_opened") else null
                VoiceOpenLinkFeature.Action.NotNowTapped ->
                    if (state.openLink.pending != null) mark("voice_open_link_declined") else null
                VoiceOpenLinkFeature.Action.TimedOut ->
                    if (state.openLink.pending != null) mark("voice_open_link_timed_out") else null
                else -> null
            }
            is VoiceFeature.Action.CallAudio -> {
                val audio = action.action
                if (audio is VoiceCallAudioFeature.Action.Started) {
                    val result = audio.result
                    if (result is CallAudioResult.Failed) {
                        mark("voice_audio_failed", result.failure::class.simpleName)
                    } else if (audio.replied) {
                        mark("voice_audio_started")
                    } else {
                        mark("voice_audio_failed", "socket closed")
                    }
                }
                val ends = audio == VoiceCallAudioFeature.Action.StopTapped ||
                    (audio is VoiceCallAudioFeature.Action.Player && audio.event is CallAudioEvent.Finished)
                if (ends && state.callAudio.track != null) mark("voice_audio_stopped")
            }
            is VoiceFeature.Action.CallRouteResolved -> action.note?.let { mark("voice_delegator_fallback", it) }
            VoiceFeature.Action.StartIfNeeded, VoiceFeature.Action.TryAgainTapped, VoiceFeature.Action.EndPressed, VoiceFeature.Action.ReconnectingShown,
            is VoiceFeature.Action.SessionRenamed,
            is VoiceFeature.Action.CompactionObserved, is VoiceFeature.Action.DelegatorExplained, is VoiceFeature.Action.DelegatorPinned,
            is VoiceFeature.Action.DelegatorOpenedDraft, VoiceFeature.Action.DelegatorRequestedEnd, is VoiceFeature.Action.DelegatorSettledRequest,
            is VoiceFeature.Action.PendingModelApplied, is VoiceFeature.Action.FloatingStage,
            is VoiceFeature.Action.DelegatorKeptPick, is VoiceFeature.Action.SessionDelegatorAutomaticResolved,
            is VoiceFeature.Action.SessionDelegatorTools, VoiceFeature.Action.SessionDelegatorStreamReconnected,
            is VoiceFeature.Action.SessionDelegatorReconciledTools, is VoiceFeature.Action.SessionDelegatorSessionDeleted,
            is VoiceFeature.Action.AtEarChanged, is VoiceFeature.Action.ServerVoiceSupportUpdated,
            is VoiceFeature.Action.CallPlaceChanged, is VoiceFeature.Action.CallBrokerMoved, is VoiceFeature.Action.FailureFixTapped,
            is VoiceFeature.Action.CallCommand, is VoiceFeature.Action.Delegate,
            -> Unit
        }
        return Reduced(state)
    }

    private fun callEvent(event: VoiceCallEvent, state: VoiceFeature.State, mark: (String, String?, String?) -> Unit) {
        when (event) {
            VoiceCallEvent.Connected -> mark("voice_connected", null, null)
            VoiceCallEvent.UserSpeechStarted -> {
                if (state.isAssistantSpeaking) mark("voice_barge_in", null, null)
                mark("voice_user_speech_started", null, null)
            }
            is VoiceCallEvent.UserTranscript -> if (event.final) mark("voice_user_transcript", "item=${event.itemID}", event.text)
            is VoiceCallEvent.AssistantDelta -> {
                if (state.liveAssistantItemID != event.itemID || (event.itemID == null && state.liveAssistantText.isEmpty())) {
                    mark("voice_assistant_started", event.itemID?.let { "item=$it" }, null)
                }
            }
            is VoiceCallEvent.AssistantDone ->
                mark("voice_assistant_done", event.itemID?.let { "item=$it" }, event.text.ifEmpty { state.liveAssistantText })
            VoiceCallEvent.AssistantPlaybackFinished -> mark("voice_assistant_playback_finished", null, null)
            is VoiceCallEvent.FunctionCall -> {
                if (event.name == "delegate") {
                    mark("voice_delegate_requested", "callID=${event.callID}", event.arguments["text"]?.stringValue)
                } else {
                    mark("voice_function_call", "name=${event.name} callID=${event.callID}", null)
                }
            }
            is VoiceCallEvent.ServerDelegation -> mark("voice_delegate_requested", "callID=${event.callID} target=responses", null)
            VoiceCallEvent.AudioInterrupted -> mark("voice_audio_interrupted", null, null)
            VoiceCallEvent.AudioResumed -> mark("voice_audio_resumed", null, null)
            VoiceCallEvent.RouteChanged -> mark("voice_route_changed", null, null)
            VoiceCallEvent.Reconnecting -> mark("voice_reconnecting", null, null)
            VoiceCallEvent.Reconnected -> mark("voice_reconnected", null, null)
            is VoiceCallEvent.ConnectionPoor -> mark(if (event.poor) "voice_connection_poor" else "voice_connection_recovered", null, null)
            is VoiceCallEvent.ServiceError -> mark("voice_service_error", "error=${event.message}", null)
        }
    }
}
