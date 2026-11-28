package ai.opencode.feature.voice

import ai.opencode.core.log.CallTrigger
import ai.opencode.server.model.VoiceViewingPage
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.strings.localized
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.concatenate
import ai.opencode.core.store.map
import ai.opencode.core.dependencies.Clock
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeRunMessage
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionTitle
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** One caption line the transcript shows: a finished caption or the agent's live reply. */
data class CaptionTarget(
    val id: String,
    val isUser: Boolean,
    val text: String,
    /** Still streaming: the last word may be cut mid-word. */
    val isLive: Boolean = false,
)

/**
 * One voice call attached to the open session. It follows the screen: the drawer moves it to
 * another session, and New Session moves it to the draft, where the first spoken request creates
 * the session. The voice model converses; the target session does the work; its reply comes back as
 * speech.
 *
 * Composed: [VoiceTimeline] sees the state an action arrives to, then the call's own reducer, then
 * [VoiceCallLogging] writes the call log from the result. On Android the call also owns its
 * ongoing-call presence: [presenceEffect] starts, updates and stops it from what the state shows.
 *
 * The extension files hold the rest of the reducer: `VoiceFeatureStart.kt`,
 * `VoiceFeatureDelegation.kt`, `VoiceFeatureInstructions.kt`, `VoiceFeatureContext.kt`,
 * `VoiceFeatureAdmission.kt`.
 */
class VoiceFeature : Reducer<VoiceFeature.State, VoiceFeature.Action> {
    data class State(
        val target: VoiceWorkTarget,
        val sessionTitle: String,
        /** The page open over the session in the in-app browser. */
        val viewing: VoiceViewingPage? = null,
        /**
         * Why the call last moved sessions, for the call log ("you opened it",
         * "the voice agent switched").
         */
        val moveReason: String? = null,
        /** The session is waiting on a native approval or answer. */
        val targetNeedsInput: Boolean = false,
        /** The app is in front of the user: not in the background, where locking the phone or switching apps puts it. */
        val isAppVisible: Boolean = true,
        /** The user said this call that they're looking at the screen (the delegator's `set_user_looking`), until they say they've stopped. */
        val userLooking: Boolean = false,
        /** Set through [withPhase], which keeps `replyWatch.callPhase` in step. */
        val phase: Phase = Phase.Connecting,
        val isMuted: Boolean = false,
        val isAssistantSpeaking: Boolean = false,
        val pendingWork: List<PendingWork> = emptyList(),
        val captions: List<Caption> = emptyList(),
        val liveAssistantText: String = "",
        val liveAssistantItemID: String? = null,
        /** The call's conversation, continuous across moves. */
        val tail: VoiceTranscriptTail,
        /**
         * An earlier call's last lines in the session on screen, for the voice
         * model; never this call's own lines.
         */
        val previousCallTail: List<String> = emptyList(),
        val previousEndedAt: Instant? = null,
        /** Moves since the call started. */
        val moves: Int = 0,
        /** Sessions this call has been on, so each keeps its tail for the next call. */
        val visitedSessionIDs: List<String> = emptyList(),
        /** Where the screen last moved from, for a request spoken around the move. */
        val lastMove: Move? = null,
        /** How far the floating call (Living Orb) has grown: orb, card or sheet. */
        val floatingStage: FloatingCallStageFeature.State = FloatingCallStageFeature.State(),
        /** An agent asking to see the screen (Share Screen with Agents). */
        val screenRequest: VoiceScreenRequestFeature.State = VoiceScreenRequestFeature.State(),
        /** An agent asking to open a link on the phone (Open Links from Calls). */
        val openLink: VoiceOpenLinkFeature.State = VoiceOpenLinkFeature.State(),
        /** Music or a clip an agent plays on the call (Audio in Calls). */
        val callAudio: VoiceCallAudioFeature.State = VoiceCallAudioFeature.State(),
        /** Another app or call took the audio; the bar shows a pause glyph until it's back. */
        val isAudioInterrupted: Boolean = false,
        /** The call is moving to a new connection and it has taken over a second. */
        val isReconnecting: Boolean = false,
        /**
         * Microphone audio is backing up on the way to the voice model: what the
         * user says may not be heard until it clears.
         */
        val isConnectionPoor: Boolean = false,
        /** When the call first connected; reconnects keep it, a new call clears it. */
        val connectedAt: Instant? = null,
        /** The target the model was configured with when the call started. */
        val startedTarget: VoiceWorkTarget? = null,
        /** The page the model was told about when the call started. */
        val startedViewing: VoiceViewingPage? = null,
        /**
         * Requests left behind when the call moved; the model is told they keep
         * running in their own session.
         */
        val abandonedCallIDs: List<String> = emptyList(),
        /**
         * The model the call's next request applies, as a typed send would: on
         * a draft, the one the session it creates starts with; on a session, a
         * pick still waiting for the next send. Kept out of `target` so picking
         * one isn't a move.
         */
        val pendingModel: OpenCodeModelRef? = null,
        /** The route locked in when the call starts: its engine and provider are what the call runs on. */
        val activeRoute: VoiceRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive),
        /** Whether [supportServer] supports `POST /api/experimental/voice/session`. */
        val serverVoiceSupport: ServerVoiceSupport = ServerVoiceSupport.unknown,
        /**
         * The saved server that brokered the last server-brokered start, when it
         * wasn't the call's own (Settings › Voice › Voice Server).
         */
        val serverVoiceBroker: OpenCodeServer? = null,
        /** The voice locked in when the call starts. */
        val activeVoice: CallVoice = CallVoice.default,
        /**
         * Session created by this draft call's first request; adopting it is the
         * same conversation, not a user retarget.
         */
        val adoptedDraftSessionID: String? = null,
        /**
         * The session the screen is creating for this draft (a client-minted
         * ID): a request made before it lands creates that same session.
         */
        val draftSessionID: String? = null,
        /** Sessions this call has delegated into, by session ID. */
        val handoffs: Map<String, Handoff> = emptyMap(),
        /**
         * GPT-Live delegations run by the delegator, which work across sessions:
         * moving the call never abandons them.
         */
        val delegatorWork: List<String> = emptyList(),
        /** The delegator's conversation, continued across a call's requests. */
        val delegatorResponseID: String? = null,
        /** The call uses the delegator (locked in when it starts). */
        val usesDelegator: Boolean = false,
        /** The call uses the OpenCode Session Delegator (`voiceSessionDelegator`, locked in when it starts). */
        val usesSessionDelegator: Boolean = false,
        /**
         * One-line note shown in the call's captions for this call alone (such as
         * falling back to Built-in when a server broker rejects client delegation).
         */
        val callNote: String? = null,
        /** OpenCode Session Delegator state for this call. */
        val sessionDelegator: VoiceSessionDelegator.State = VoiceSessionDelegator.State(),
        /**
         * The server brokering the call, named while it connects and when it
         * fails. Null on this phone.
         */
        val callPlace: VoiceCallPlace? = null,
        /**
         * A failed start says why and offers the one fix; a Grok call's card
         * shows the error as it came, with Check API Key for its xAI key.
         */
        val diagnosesFailure: Boolean = false,
        /** Pure state machine tracking delegated session prompts and their outcomes. */
        val replyWatch: ReplyWatch = ReplyWatch(),
        /** This call's replay log, while it runs. */
        val callLogID: UUID? = null,
        /** The delegator asked to hang up once its goodbye is spoken. */
        val endsAfterDelegation: Boolean = false,
        /**
         * Raise to Talk's live value for the call's start: one box shared by every copy of this
         * state; equal to any other box.
         */
        val atEarBox: VoiceAtEarBox = VoiceAtEarBox(),
        /**
         * Raise to Talk: the phone is at the user's ear, so the call plays from the earpiece.
         * Applied again on connect: a raise starts the call there. Set through [withAtEar], which
         * keeps [atEarBox] in step.
         */
        val isAtEar: Boolean = false,
        /** The newest finished compaction the session screen has seen, by session ID. */
        val compactions: Map<String, String> = emptyMap(),
        /**
         * Android: the identity of the call's ongoing-call foreground service. Set when a start
         * goes ahead, cleared once the call's card is closed.
         */
        val callPresenceID: UUID? = null,
    ) {
        sealed interface Phase {
            data object Connecting : Phase
            data object Live : Phase

            /**
             * The model asked to hang up; `spoken` flips once its goodbye response
             * has been delivered, so the drain that ends the call is the right one.
             */
            data class EndingAfterGoodbye(val spoken: Boolean) : Phase
            data object Ended : Phase
            data class Failed(val message: String) : Phase

            /** The connection was lost and couldn't be restored; the call can be rejoined. */
            data object Dropped : Phase

            /** A card above the composer replaces the call bar. */
            val showsCard: Boolean
                get() = this is Failed || this == Dropped

            val isTerminal: Boolean
                get() = this == Ended || this is Failed || this == Dropped

            val replyWatchPhase: ReplyWatch.CallPhase
                get() = when (this) {
                    Connecting -> ReplyWatch.CallPhase.connecting
                    Live -> ReplyWatch.CallPhase.live
                    is EndingAfterGoodbye -> ReplyWatch.CallPhase.ending
                    Ended, is Failed, Dropped -> ReplyWatch.CallPhase.ended
                }
        }

        data class Caption(val id: String, val role: VoiceTranscriptTail.Role, val text: String)

        data class PendingWork(
            val callID: String,
            val capture: VoiceTranscriptTail.Capture,
            val promptID: String? = null,
            val superseded: Boolean = false,
            /** It carried the long header (D4a). */
            val explainsHeader: Boolean = false,
            /**
             * Where it was sent: a move never redirects it. A draft's gains its
             * session once created.
             */
            val target: VoiceWorkTarget? = null,
            /**
             * The call's `moves` when it was sent, so a draft's request is
             * adopted only by the draft it was spoken on.
             */
            val move: Int = 0,
        )

        /**
         * D4a: when this call last delegated into a session, and the compaction
         * that session had when it last got the long header.
         */
        data class Handoff(val lastDelegationAt: Instant, val compactionID: String?)

        /** The newest finished compaction the session screen has seen. */
        data class CompactionMark(val sessionID: String, val compactionID: String)

        data class Move(val sessionID: String?, val title: String, val at: Instant)

        /** Moves the user didn't make; every other reason is their own switch. */
        object MoveReason {
            const val agentSwitched = "the voice agent switched"
            const val agentOpenedDraft = "the voice agent opened a draft"
            const val sessionWentAway = "the session went away on the server"
            val unprompted: Set<String> = setOf(agentSwitched, agentOpenedDraft, sessionWentAway)
        }

        /**
         * The last move was one the user didn't make ([MoveReason.unprompted]), keyed by the
         * move: only those get a notice. Their own switches stay quiet.
         */
        val unpromptedMove: Int? get() = if (moveReason != null && moveReason in MoveReason.unprompted) moves else null

        val activeEngine: VoiceEngine? get() = activeRoute.engine
        val activeProvider: ServerVoiceProvider get() = activeRoute.provider

        val directoryName: String get() = lastPathComponent(target.directory)

        /**
         * Where the call is and what it let go of. Home tells the voice model
         * when either changes; New Session from a draft in the same folder
         * leaves the target as it was but still lets work go, while a session
         * changing folders stays the same session.
         */
        data class Placement(val target: Target, val abandonedCallIDs: List<String>) {
            data class Target(val server: OpenCodeServer, val sessionID: String?, val draftDirectory: String?)
        }

        val placement: Placement
            get() = Placement(
                target = Placement.Target(
                    server = target.server,
                    sessionID = target.sessionID,
                    draftDirectory = if (target.sessionID == null) target.directory else null,
                ),
                abandonedCallIDs = abandonedCallIDs,
            )

        /**
         * No start has run yet: the call bar's appearance may start it. After
         * that, only an explicit request (Try Again, Rejoin) starts it again.
         */
        val awaitsFirstStart: Boolean get() = startedTarget == null && phase == Phase.Connecting

        /** The server [serverVoiceSupport] describes. */
        val supportServer: OpenCodeServer get() = serverVoiceBroker ?: target.server

        // Read where they're used, never stored in the state.
        val voiceEngine: VoiceEngine get() = shared(VoiceSharedKeys.voiceEngine).value
        val serverVoiceProvider: ServerVoiceProvider get() = shared(VoiceSharedKeys.serverVoiceProvider).value
        val voiceServer: VoiceServerChoice get() = shared(VoiceSharedKeys.voiceServerChoice).value
        val serverVoiceSupportByServer: Map<String, ServerVoiceSupport>
            get() = shared(VoiceSharedKeys.serverVoiceSupportByServer).value
        val callVoice: CallVoice get() = shared(VoiceSharedKeys.callVoice).value
        val voiceModelsByServer: Map<String, ServerVoiceModelsAnswer> get() = shared(VoiceSharedKeys.voiceModelsByServer).value
        val voiceDelegatorChoice: VoiceDelegatorChoice get() = shared(VoiceSharedKeys.voiceDelegatorChoice).value
        val voiceDelegatorSessionIDs: Map<String, String> get() = shared(VoiceSharedKeys.voiceDelegatorSessionIDs).value

        /** Why the call couldn't start and its one fix, for the card. */
        val failureDiagnosis: VoiceCallDiagnosis?
            get() {
                val failed = phase as? Phase.Failed ?: return null
                if (!diagnosesFailure) return null
                return VoiceCallDiagnosis.diagnose(message = failed.message, route = activeRoute, place = callPlace?.name)
            }

        /** The OpenAI key (GPT-Live, Realtime, and the delegator). */
        val apiKey: String? get() = shared(SharedKeys.openAIAPIKey).value

        /** The xAI key (Grok Voice). */
        val xaiAPIKey: String? get() = shared(SharedKeys.xaiAPIKey).value

        /** Settings' last accepted key and model: a call skips the preflight then. */
        val verifiedKeyFingerprint: String? get() = shared(SharedKeys.verifiedVoiceKeyFingerprint).value

        /** Read by the delegator's tools: which sessions the drawer pins. */
        val pinnedSessionKeys: List<String> get() = shared(SharedKeys.pinnedSessionKeys).value

        /** Read by the delegator's model tools: the picker's favorites. */
        val favoriteModelIDs: Set<String> get() = shared(SharedKeys.favoriteModelIDs).value

        /** Read by `create_session`: the level last picked for each model. */
        val modelVariants: Map<String, String> get() = shared(SharedKeys.modelVariants).value
        val transcripts: Map<String, VoiceTranscriptRecord> get() = shared(VoiceSharedKeys.voiceTranscripts).value

        /** Null on a draft, which has nothing to remember yet. */
        val transcriptKey: String? get() = target.sessionID?.let { "${target.server.id}:$it" }
        val isOnDraft: Boolean get() = target.sessionID == null

        /**
         * The key a call on [engine] uses. The lab's scripted call needs no key;
         * a debug build can take one from the lab (debug builds only).
         */
        fun callAPIKey(engine: VoiceEngine): String? {
            if (VoiceLabFlags.isEnabled || VoiceLabFlags.hasStandInServer) return "lab"
            VoiceLabFlags.labAPIKey?.let { return it }
            return engine.apiKey(openAI = apiKey, xAI = xaiAPIKey)
        }

        val resolvedEngine: VoiceEngine get() = VoiceLabFlags.overrideEngine ?: voiceEngine

        val appContextSummary: String
            get() {
                if (isOnDraft) return "New session draft in project \"$directoryName\"."
                var summary = "Session \"$sessionTitle\" in project \"$directoryName\"."
                if (targetNeedsInput) summary += " Waiting on user input in the app."
                return summary
            }

        val instructionsContext: VoiceInstructions.Context
            get() = VoiceInstructions.Context(
                sessionTitle = sessionTitle,
                directoryName = directoryName,
                recentMessages = emptyList(),
                previousTail = previousCallTail,
                previousEndedAt = previousEndedAt,
                isNewSession = isOnDraft,
                managesSessions = usesDelegator || usesSessionDelegator,
                viewing = viewing,
                userSeesScreen = screenVisibility.userSeesScreen,
                recordsLooking = usesDelegator,
            )

        val isWorking: Boolean
            get() = pendingWork.isNotEmpty() || delegatorWork.isNotEmpty() || sessionDelegator.hasActiveTurns
        val hasAdmittedWork: Boolean get() = pendingWork.any { it.promptID != null && isHere(it) }

        /**
         * Prompts the target admitted for this call and has not answered yet:
         * the call's claim that its session is running. Ending the call drops
         * its work, so a finished call never holds a run open.
         */
        val admittedPromptIDs: List<String> get() = pendingWork.filter(::isHere).mapNotNull { it.promptID }

        /** Work sent to the session on screen (a draft's, from this draft). */
        fun isHere(work: PendingWork): Boolean {
            val workTarget = work.target ?: return true
            return if (workTarget.sessionID == null) isOnDraft && work.move == moves else workTarget.sessionID == target.sessionID
        }

        /** The reply watch follows the call's phase. */
        fun withPhase(phase: Phase): State = copy(phase = phase, replyWatch = replyWatch.copy(callPhase = phase.replyWatchPhase))

        /** The box the call's start reads follows it. */
        fun withAtEar(isAtEar: Boolean): State {
            atEarBox.current = isAtEar
            return copy(isAtEar = isAtEar)
        }

        /**
         * Moves the call to another session when the user switches sessions.
         * Only what the voice model is told changes: the conversation, captions,
         * a reply being spoken, and requests still on their way all carry on.
         * Parents call it from their reducers (it saves the tail it leaves).
         */
        fun retarget(session: OpenCodeSession, reason: String? = null, endedAt: Instant = dependency(DateClient).now()): State {
            val leaving = sessionTitle
            var next = copy(sessionTitle = usableTitle(session.title) ?: newSessionTitle)
            if (next.target.sessionID == session.id) return next
            next = next.leave(title = leaving, reason = reason, at = endedAt)
            // The new screen hands over its own pick, if any.
            next = next.copy(
                pendingModel = null,
                target = next.target.copy(sessionID = session.id, directory = session.directory),
            )
            return next.copy(previousCallTail = emptyList(), previousEndedAt = null)
        }

        /**
         * Moves the call to a new session's draft in [directory]; the first
         * request creates the session there. The conversation carries on.
         */
        fun retargetToDraft(
            directory: String,
            model: OpenCodeModelRef?,
            reason: String? = null,
            endedAt: Instant = dependency(DateClient).now(),
        ): State {
            val next = leave(title = sessionTitle, reason = reason, at = endedAt)
            return next.copy(
                previousCallTail = emptyList(),
                previousEndedAt = null,
                pendingModel = model,
                target = next.target.copy(sessionID = null, directory = directory),
                sessionTitle = newSessionTitle,
            )
        }

        private fun leave(title: String, reason: String?, at: Instant): State {
            // Another session's screen has its own page, if any.
            val next = copy(moveReason = reason, adoptedDraftSessionID = null, draftSessionID = null, viewing = null, targetNeedsInput = false)
            next.transcriptKey?.let { key ->
                // The next call there picks up from here.
                val lines = next.tail.lines
                shared(VoiceSharedKeys.voiceTranscripts).update { records ->
                    val record = records[key] ?: VoiceTranscriptRecord(endedAt = at, tail = emptyList(), log = emptyList())
                    VoiceTranscriptRecord.pruned(records + (key to record.appending(tail = lines, log = emptyList(), endedAt = at)))
                }
            }
            val sessionID = next.target.sessionID
            val visited = if (sessionID != null && sessionID !in next.visitedSessionIDs) next.visitedSessionIDs + sessionID else next.visitedSessionIDs
            return next.copy(visitedSessionIDs = visited, lastMove = Move(sessionID = sessionID, title = title, at = at), moves = next.moves + 1)
        }

        /**
         * The draft the call is on changed project: the same conversation,
         * created somewhere else. A request already creating the session there
         * keeps going on its own.
         */
        fun moveDraft(directory: String): State {
            if (!isOnDraft || target.directory == directory) return this
            return copy(adoptedDraftSessionID = null, draftSessionID = null)
                .abandonWork()
                .let { it.copy(target = it.target.copy(directory = directory)) }
        }

        /**
         * The session the call is on moved folders: the same session, so nothing the voice
         * model was told changes.
         */
        fun sessionMoved(sessionID: String, directory: String): State {
            var next = this
            val started = startedTarget
            if (started?.sessionID == sessionID && started.directory != directory) {
                next = next.copy(startedTarget = started.copy(directory = directory))
            }
            if (next.target.sessionID == sessionID && next.target.directory != directory) {
                next = next.copy(target = next.target.copy(directory = directory))
            }
            return next
        }

        /** Drops a reply still being spoken: it was cut off, or the call moved on. */
        fun clearLiveReply(): State = copy(liveAssistantText = "", liveAssistantItemID = null)

        /** Work already admitted keeps running in its session; the call just stops waiting for it. */
        private fun abandonWork(): State {
            var next = this
            for (work in pendingWork) next = next.releaseUnadmitted(work)
            return next.copy(abandonedCallIDs = next.abandonedCallIDs + pendingWork.map { it.callID }, pendingWork = emptyList())
        }

        /** Releases an unadmitted delegation's transcript capture and header state. */
        fun releaseUnadmitted(work: PendingWork): State {
            if (work.promptID != null) return this
            var next = copy(tail = tail.release(work.capture))
            val sessionID = (work.target ?: target).sessionID
            if (work.explainsHeader && sessionID != null) next = next.copy(handoffs = next.handoffs - sessionID)
            return next
        }

        /** The call's tail and captions, for each session it was on. Writes the shared transcripts. */
        fun persistTranscript(endedAt: Instant) {
            val keys = (visitedSessionIDs + listOfNotNull(target.sessionID)).distinct().map { "${target.server.id}:$it" }
            if (keys.isEmpty()) return
            val lines = captions.map { "${it.role.rawValue}: ${it.text}" }
            val tailLines = tail.lines
            shared(VoiceSharedKeys.voiceTranscripts).update { records ->
                val updated = records.toMutableMap()
                for (key in keys) {
                    val record = updated[key] ?: VoiceTranscriptRecord(endedAt = endedAt, tail = emptyList(), log = emptyList())
                    updated[key] = record.appending(tail = tailLines, log = lines, endedAt = endedAt)
                }
                VoiceTranscriptRecord.pruned(updated)
            }
        }

        val captionTargets: List<CaptionTarget>
            get() {
                val targets = callNote?.let { mutableListOf(CaptionTarget(id = "call-note", isUser = false, text = callNoteDisplayText(it))) } ?: mutableListOf()
                targets += captions.takeLast(2 * CaptionReveal.maxLines).map { CaptionTarget(id = it.id, isUser = it.role == VoiceTranscriptTail.Role.user, text = it.text) }
                val progress = sessionDelegator.progressCaption
                if (liveAssistantText.isNotEmpty()) {
                    // The settled caption reuses this ID, so its words don't appear twice.
                    val id = "assistant-${liveAssistantItemID ?: "live"}"
                    targets += CaptionTarget(id = id, isUser = false, text = liveAssistantText, isLive = true)
                } else if (isWorking && progress != null) {
                    targets += CaptionTarget(id = "delegator-progress", isUser = false, text = progress, isLive = false)
                }
                return targets
            }

        val statusText: String
            get() = when (val phase = phase) {
                Phase.Connecting -> callPlace?.let { localized(R.string.voice_status_connecting_via, it.name) } ?: localized(R.string.voice_notification_connecting)
                Phase.Live -> when {
                    isReconnecting -> localized(R.string.voice_notification_reconnecting)
                    isConnectionPoor -> localized(R.string.voice_status_weak_connection)
                    isAudioInterrupted -> localized(R.string.voice_status_paused)
                    targetNeedsInput -> localized(R.string.voice_status_needs_input)
                    isWorking -> sessionDelegator.progressCaption ?: localized(R.string.voice_status_working)
                    isAssistantSpeaking -> localized(R.string.voice_status_speaking)
                    else -> if (isMuted) localized(R.string.voice_status_muted) else localized(R.string.voice_status_listening)
                }
                is Phase.EndingAfterGoodbye -> localized(R.string.voice_status_ending)
                Phase.Ended -> localized(R.string.voice_status_ended)
                Phase.Dropped -> localized(R.string.voice_notification_call_ended)
                is Phase.Failed -> voiceFailureDisplayText(phase.message)
            }

        companion object {
            /** The title a call names an untitled session by; it also goes to the voice agent, so it stays English. */
            const val newSessionTitle = "New session"

            /** [title] as the person reads it: [newSessionTitle] in the app's language. */
            fun displayTitle(title: String): String = if (title == newSessionTitle) localized(R.string.voice_session_new_title) else title

            /** A call note as the person reads it; the note itself stays English for the call log. */
            fun callNoteDisplayText(note: String): String =
                if (note == VoiceFeature.builtInFallbackNote) localized(R.string.voice_call_note_builtin_fallback) else note

            /** A session's title to name it by, or null while it reads as untitled ([SessionTitle.isPlaceholder]). */
            fun usableTitle(title: String): String? = if (SessionTitle.isPlaceholder(title)) null else title.trim()

            /** A call on [session], picking up the tail an earlier call left there. */
            operator fun invoke(server: OpenCodeServer, session: OpenCodeSession): State {
                val previous = shared(VoiceSharedKeys.voiceTranscripts).value["${server.id}:${session.id}"]
                val previousLines = VoiceTranscriptRecord.continuityLines(previous?.tail ?: emptyList())
                return State(
                    target = VoiceWorkTarget(server = server, sessionID = session.id, directory = session.directory),
                    sessionTitle = usableTitle(session.title) ?: newSessionTitle,
                    tail = VoiceTranscriptTail(lines = previousLines),
                    previousCallTail = previousLines,
                    previousEndedAt = if (previousLines.isEmpty()) null else previous?.endedAt,
                )
            }

            /**
             * A call started on a new session's draft in [draftDirectory]; its first request
             * creates the session there.
             */
            operator fun invoke(server: OpenCodeServer, draftDirectory: String, model: OpenCodeModelRef?): State = State(
                target = VoiceWorkTarget(server = server, sessionID = null, directory = draftDirectory),
                pendingModel = model,
                sessionTitle = newSessionTitle,
                tail = VoiceTranscriptTail(lines = emptyList()),
            )

            internal fun lastPathComponent(path: String): String {
                val trimmed = path.trimEnd('/')
                if (trimmed.isEmpty()) return if (path.isEmpty()) "" else "/"
                return trimmed.substringAfterLast('/')
            }
        }
    }

    sealed interface Action {
        data object Start : Action

        /**
         * The call's view appeared: starts the call once. A view that comes
         * back (a drawer swap, Settings closing) must not restart a live call
         * or retry a failed one.
         */
        data object StartIfNeeded : Action
        data object RejoinTapped : Action
        /** A failed call's Try Again. */
        data object TryAgainTapped : Action
        data object CallDropped : Action
        data object ReconnectingShown : Action
        data class CallEvent(val event: VoiceCallEvent) : Action
        data class CallEnded(val errorMessage: String?) : Action
        data class WorkAdmitted(val callID: String, val promptID: String) : Action

        /** The session screen saw a finished compaction in the call's session. */
        data class CompactionObserved(val mark: State.CompactionMark) : Action
        data class SessionStarted(val callID: String, val session: OpenCodeSession) : Action

        /**
         * The draft the call is on got its session some other way (the screen
         * created it for the call, or a typed send did).
         */
        data class DraftSessionCreated(val session: OpenCodeSession) : Action
        data class SessionRenamed(val title: String) : Action

        /** The call moved to another session or a draft: refresh the model's instructions. */
        data object TargetChanged : Action

        /** The page open over the session in the in-app browser, if any. */
        data class ViewingChanged(val page: VoiceViewingPage?) : Action

        /** The app came to the front (true) or went to the background. */
        data class AppVisibilityChanged(val visible: Boolean) : Action

        /** The delegator recorded the user saying they're looking at the screen (true) or aren't (false). */
        data class DelegatorNotedLooking(val looking: Boolean) : Action
        data class WorkReplied(val callID: String, val reply: String?) : Action
        data class WorkFailed(val callID: String, val message: String) : Action
        data class DelegatorOpened(val session: OpenCodeSession, val toolID: String? = null) : Action
        data class DelegatorPinned(val session: OpenCodeSession, val pinned: Boolean) : Action
        data object DelegatorRequestedEnd : Action

        /** The delegator answered or dismissed a request in this session. */
        data class DelegatorSettledRequest(val sessionID: String) : Action
        data class DelegatorOpenedDraft(val directory: String?) : Action

        /** The delegator switched a session's model, or the draft's when null. */
        data class DelegatorSwitchedModel(val sessionID: String?, val model: OpenCodeModelRef) : Action

        /** A request applied the screen's waiting pick to its session before its prompt. */
        data class PendingModelApplied(val sessionID: String, val model: OpenCodeModelRef) : Action

        /**
         * A session the delegator created couldn't take the draft's model: it
         * waits there for the next send.
         */
        data class DelegatorKeptPick(val sessionID: String, val model: OpenCodeModelRef) : Action
        data class DelegatorExplained(val sessionID: String) : Action

        /** A failure carries an `OpenCodeClientError`. */
        data class DelegatorFinished(val callID: String, val result: Result<VoiceDelegator.Outcome>) : Action
        data class ReplyWatch(val event: ai.opencode.feature.voice.ReplyWatch.Event) : Action
        data object MuteTapped : Action
        data class AtEarChanged(val atEar: Boolean) : Action

        /** The finger's own hang-up: the bar's End, or Close on a call's card. */
        data object EndPressed : Action
        data object EndTapped : Action
        data class FloatingStage(val action: FloatingCallStageFeature.Action) : Action
        data class ScreenRequest(val action: VoiceScreenRequestFeature.Action) : Action
        data class OpenLink(val action: VoiceOpenLinkFeature.Action) : Action
        data class CallAudio(val action: VoiceCallAudioFeature.Action) : Action
        data class ServerVoiceSupportUpdated(val support: ServerVoiceSupport) : Action
        data class CallRouteResolved(
            val route: VoiceRoute,
            val usesDelegator: Boolean,
            val usesSessionDelegator: Boolean = false,
            val note: String? = null,
        ) : Action

        /** The server brokering the call, named as it connects (null on this phone). */
        data class CallPlaceChanged(val place: VoiceCallPlace?) : Action

        /** Automatic moved the call to another server (null: the session's own). */
        data class CallBrokerMoved(val server: OpenCodeServer?, val place: VoiceCallPlace?) : Action

        /** The failure card's one fix. */
        data class FailureFixTapped(val fix: VoiceCallFix) : Action

        data class SessionDelegatorAutomaticResolved(
            val callID: String,
            val serverScopeKey: String,
            val sessionID: String,
            val created: OpenCodeSession?,
        ) : Action
        data class SessionDelegatorAdmitted(val callID: String, val admission: VoiceSessionDelegator.Admission) : Action
        data class SessionDelegatorAdmissionFailed(val callID: String, val error: OpenCodeClientError) : Action
        data class SessionDelegatorTools(val sessionID: String, val events: List<OpenCodeLiveContentEvent>) : Action
        data object SessionDelegatorStreamReconnected : Action
        data class SessionDelegatorReconciledTools(val calls: List<OpenCodeRunMessage.ToolCall>) : Action
        data class SessionDelegatorSessionDeleted(val sessionID: String) : Action

        /** Android: Mute or End from the call's ongoing notification. */
        data class CallCommand(val command: VoiceCallCommands.Action) : Action
        data class Delegate(val action: DelegateAction) : Action
    }

    /** What the parent (Session, Home) handles. */
    sealed interface DelegateAction {
        /** Open Settings › Voice at [page]. */
        data class OpenVoiceSettings(val page: VoiceSettingsPage) : DelegateAction

        /** The session delegator created its Automatic “Voice” session. */
        data class DelegatorSessionCreated(val session: OpenCodeSession) : DelegateAction
        data class ServerVoiceSupportChanged(val support: ServerVoiceSupport) : DelegateAction
        data class SessionCreated(val session: OpenCodeSession) : DelegateAction

        /** A voice send was accepted and recorded in the phone's outbox. */
        data class PromptAdmitted(val sessionID: String) : DelegateAction

        /** The delegator put a session on the user's screen. */
        data class OpenSession(val session: OpenCodeSession, val toolID: String? = null) : DelegateAction

        /** SSE requests are arbitrated with MCP at App scope before any lookup. */
        data class OpenSessionRequested(val toolID: String, val targetID: String) : DelegateAction

        /** The delegator pinned or unpinned a session. */
        data class SetPinned(val session: OpenCodeSession, val pinned: Boolean) : DelegateAction

        /**
         * The delegator answered or dismissed a request in this session: its requests are
         * read again, so the dock and “Needs your input” follow the server.
         */
        data class RequestSettled(val sessionID: String) : DelegateAction

        /** The delegator opened a new session's draft, in a project when given. */
        data class OpenDraft(val directory: String?) : DelegateAction

        /**
         * The delegator switched a session's model (already saved on the
         * server), or the draft's when the ID is null.
         */
        data class ModelSwitched(val sessionID: String?, val model: OpenCodeModelRef) : DelegateAction

        /** A request applied the screen's waiting pick (`pendingModel`). */
        data class PendingModelApplied(val sessionID: String, val model: OpenCodeModelRef) : DelegateAction

        /** The session's model is picked but not set: it waits for the next send. */
        data class PickWaiting(val sessionID: String, val model: OpenCodeModelRef) : DelegateAction
        data object Ended : DelegateAction
    }

    sealed interface CancelID {
        data object Call : CancelID
        data object Goodbye : CancelID
        data object Instructions : CancelID
        data object ScreenInstructions : CancelID
        data object Reconnecting : CancelID
        data object ReplyActiveRefresh : CancelID
        data class WorkAdmission(val callID: String) : CancelID
        data class WorkCall(val callID: String) : CancelID
        data class ReplyWatch(val promptID: String) : CancelID
        data class ReplyStepProbe(val promptID: String) : CancelID
        data class ReplyDeferral(val key: String) : CancelID

        /**
         * Work that belongs to this call alone (a delegator answer and what it
         * releases, the goodbye after it): ending the call cancels it, so none
         * of it reaches a call started later on the same host.
         */
        data object CallScope : CancelID

        /** Android: the ongoing notification's Mute/End commands for this call. */
        data object CallCommands : CancelID

        /** Android: an ended call's notification lingering before it goes. */
        data object PresenceLinger : CancelID
    }

    private val timeline = VoiceTimeline()
    private val floatingStage = FloatingCallStageFeature()
    private val screenRequest = VoiceScreenRequestFeature()
    private val openLink = VoiceOpenLinkFeature()
    private val callAudio = VoiceCallAudioFeature()

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        // VoiceTimeline sees the state the action arrives to.
        timeline.reduce(state, action)
        val scoped = scopeChildren(state, action)
        val reduced = reduceCall(scoped.state, action)
        val core = Reduced(reduced.state, Effect.merge(scoped.effect, reduced.effect))
        val logged = logCall(core.state, action)
        val presence = presenceEffect(state, logged)
        return Reduced(logged, Effect.merge(core.effect, presence))
    }

    /** The floating stage, the screen request, the open link, the call audio. */
    private fun scopeChildren(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            is Action.FloatingStage -> {
                val child = floatingStage.reduce(state.floatingStage, action.action)
                return Reduced(state.copy(floatingStage = child.state), child.effect.map { Action.FloatingStage(it) })
            }
            is Action.ScreenRequest -> {
                val child = screenRequest.reduce(state.screenRequest, action.action)
                return Reduced(state.copy(screenRequest = child.state), child.effect.map { Action.ScreenRequest(it) })
            }
            is Action.OpenLink -> {
                val child = openLink.reduce(state.openLink, action.action)
                return Reduced(state.copy(openLink = child.state), child.effect.map { Action.OpenLink(it) })
            }
            is Action.CallAudio -> {
                val child = callAudio.reduce(state.callAudio, action.action)
                return Reduced(state.copy(callAudio = child.state), child.effect.map { Action.CallAudio(it) })
            }
            else -> return Reduced(state)
        }
    }

    private fun reduceCall(state: State, action: Action): Reduced<State, Action> {
        val voiceCall = dependency(VoiceCallClient)
        val haptics = dependency(HapticsClient)
        return when (action) {
            Action.StartIfNeeded -> {
                if (!state.awaitsFirstStart) Reduced(state) else Reduced(state, Effect.send(Action.Start))
            }

            Action.Start -> startCall(state)

            is Action.CallRouteResolved -> {
                var next = state.setActiveRoute(action.route, action.usesDelegator, action.usesSessionDelegator)
                if (action.note != null) next = next.copy(callNote = action.note)
                Reduced(next)
            }

            is Action.CallPlaceChanged -> Reduced(state.copy(callPlace = action.place))

            is Action.FailureFixTapped -> when (action.fix) {
                VoiceCallFix.setUpOnIPhone -> Reduced(
                    state,
                    Effect.send(Action.Delegate(DelegateAction.OpenVoiceSettings(if (state.diagnosesFailure) VoiceSettingsPage.account else VoiceSettingsPage.voice))),
                )
                VoiceCallFix.chooseProvider -> Reduced(
                    state,
                    Effect.send(Action.Delegate(DelegateAction.OpenVoiceSettings(VoiceSettingsPage.provider))),
                )
                VoiceCallFix.updateOpenCode -> {
                    val opener = dependency(VoiceCallFixClient)
                    Reduced(state, Effect.run { opener.openURL(VoiceCallFix.downloadURL) })
                }
                VoiceCallFix.openSettings -> {
                    val opener = dependency(VoiceCallFixClient)
                    Reduced(state, Effect.run { opener.openAppSettings() })
                }
            }

            is Action.CallBrokerMoved -> Reduced(
                state.copy(
                    serverVoiceBroker = action.server,
                    serverVoiceSupport = state.serverVoiceSupportByServer[action.server?.persistenceID ?: state.target.server.persistenceID]
                        ?: ServerVoiceSupport.unknown,
                    callPlace = action.place,
                ),
            )

            is Action.SessionDelegatorAutomaticResolved ->
                reduceSessionDelegatorAutomaticResolved(state, action.callID, action.serverScopeKey, action.sessionID, action.created)
            is Action.SessionDelegatorAdmitted -> reduceSessionDelegatorAdmitted(state, action.callID, action.admission)
            is Action.SessionDelegatorAdmissionFailed -> reduceSessionDelegatorAdmissionFailed(state, action.callID, action.error)
            is Action.SessionDelegatorTools -> reduceSessionDelegatorTools(state, action.sessionID, action.events)
            Action.SessionDelegatorStreamReconnected -> reduceSessionDelegatorStreamReconnected(state)
            is Action.SessionDelegatorReconciledTools -> reduceSessionDelegatorReconciledTools(state, action.calls)
            is Action.SessionDelegatorSessionDeleted -> reduceSessionDelegatorSessionDeleted(state, action.sessionID)

            is Action.ServerVoiceSupportUpdated -> {
                val support = action.support
                if (support != ServerVoiceSupport.supported && support != ServerVoiceSupport.unsupported) return Reduced(state)
                val serverID = state.supportServer.persistenceID
                val byServer = state.serverVoiceSupportByServer
                val changed = state.serverVoiceSupport != support || byServer[serverID] != support
                if (!changed) return Reduced(state)
                if (byServer[serverID] != support) {
                    shared(VoiceSharedKeys.serverVoiceSupportByServer).update { it + (serverID to support) }
                }
                Reduced(
                    state.copy(serverVoiceSupport = support),
                    Effect.send(Action.Delegate(DelegateAction.ServerVoiceSupportChanged(support))),
                )
            }

            is Action.AtEarChanged -> {
                val atEar = action.atEar
                if (state.isAtEar == atEar) return Reduced(state)
                val next = state.withAtEar(atEar)
                if (next.phase.isTerminal) return Reduced(next)
                Reduced(next, Effect.merge(Effect.run { voiceCall.setAtEar(atEar) }, noteScreenVisibility(from = state.screenVisibility, next)))
            }

            is Action.CallEvent -> reduceCallEvent(state, action.event)

            Action.ReconnectingShown -> {
                if (state.phase.isTerminal) Reduced(state) else Reduced(state.copy(isReconnecting = true))
            }

            Action.CallDropped -> {
                if (state.phase.isTerminal) Reduced(state) else finish(state, State.Phase.Dropped)
            }

            Action.TryAgainTapped -> {
                CallTrigger.TryAgain.log()
                Reduced(state, Effect.send(Action.Start))
            }

            Action.RejoinTapped -> {
                CallTrigger.Rejoin.log()
                // The new call's instructions carry this call's tail as the previous call.
                val next = state.copy(previousEndedAt = dependency(DateClient).now(), captions = emptyList()).clearLiveReply()
                Reduced(next, Effect.send(Action.Start))
            }

            is Action.CallEnded -> {
                if (state.phase.isTerminal) return Reduced(state)
                finish(state, action.errorMessage?.let { State.Phase.Failed(it) } ?: State.Phase.Ended)
            }

            is Action.SessionStarted -> {
                // The draft became this session: the call and the screen follow it,
                // unless the request was let go (the call moved or ended) meanwhile.
                val index = state.pendingWork.indexOfFirst { it.callID == action.callID }
                if (index < 0) return Reduced(state)
                val wasHere = state.isHere(state.pendingWork[index])
                val work = state.pendingWork[index]
                var next = state.copy(
                    pendingWork = state.pendingWork.toMutableList().also {
                        it[index] = work.copy(target = work.target?.copy(sessionID = action.session.id))
                    },
                )
                if (!wasHere || !next.isOnDraft || next.phase.isTerminal) return Reduced(next)
                next = next.adoptDraftSession(action.session)
                Reduced(
                    next,
                    Effect.merge(
                        Effect.send(Action.Delegate(DelegateAction.SessionCreated(action.session))),
                        updateAttachedInstructions(next),
                    ),
                )
            }

            is Action.DraftSessionCreated -> {
                // The call is on that session from here, as if it had started there:
                // not a move. Connecting, the model hears of it on connect.
                val session = action.session
                if (!state.isOnDraft || state.target.directory != session.directory || state.phase.isTerminal) return Reduced(state)
                val next = state.adoptDraftSession(session)
                Reduced(next, updateAttachedInstructions(next))
            }

            is Action.ViewingChanged -> {
                if (state.viewing == action.page) return Reduced(state)
                val next = state.copy(viewing = action.page)
                if (next.phase != State.Phase.Live) return Reduced(next)
                Reduced(next, instructViewing(next))
            }

            is Action.SessionRenamed -> {
                val resolved = State.usableTitle(action.title)
                if (resolved == null || state.isOnDraft) return Reduced(state)
                val next = state.copy(sessionTitle = resolved)
                Reduced(next, updateAttachedInstructions(next))
            }

            Action.TargetChanged -> retargetCall(state)

            is Action.WorkAdmitted -> {
                val index = state.pendingWork.indexOfFirst { it.callID == action.callID }
                if (index < 0) return Reduced(state)
                val work = state.pendingWork[index].copy(promptID = action.promptID)
                val pending = state.pendingWork.mapIndexed { i, item ->
                    when {
                        i < index -> item.copy(superseded = true)
                        i == index -> work
                        else -> item
                    }
                }
                var next = state.copy(pendingWork = pending, tail = state.tail.accept(work.capture))
                val workTarget = work.target ?: next.target
                val sessionID = workTarget.sessionID ?: return Reduced(next)
                if (work.explainsHeader) {
                    // Recorded on admission too, for a draft whose session this request created.
                    next = next.recordHandoff(sessionID, next.handoffs[sessionID]?.lastDelegationAt ?: dependency(DateClient).now())
                }
                Reduced(
                    next,
                    waitForWorkReply(callID = action.callID, server = workTarget.server, sessionID = sessionID, promptID = action.promptID),
                )
            }

            is Action.CompactionObserved -> Reduced(state.copy(compactions = state.compactions + (action.mark.sessionID to action.mark.compactionID)))

            is Action.WorkReplied -> {
                val superseded = state.pendingWork.firstOrNull { it.callID == action.callID }?.superseded ?: false
                val next = state.copy(pendingWork = state.pendingWork.filter { it.callID != action.callID })
                val trimmed = action.reply?.trim() ?: ""
                if (action.callID.startsWith("client_verify_")) {
                    val output = if (VoiceActionClaim.correctsVoice(trimmed)) VoiceHandoff.completion(trimmed) else ""
                    return Reduced(next, completeFunction(action.callID, output))
                }
                if (trimmed.isEmpty() && superseded) {
                    return Reduced(next, Effect.run { voiceCall.closeDelegation(action.callID, VoiceDelegationCloseReason.superseded) })
                }
                Reduced(next, completeFunction(action.callID, VoiceHandoff.completion(trimmed)))
            }

            is Action.DelegatorOpened -> {
                if (state.phase.isTerminal) Reduced(state)
                else Reduced(state, Effect.send(Action.Delegate(DelegateAction.OpenSession(action.session, toolID = action.toolID))))
            }

            is Action.DelegatorPinned -> {
                if (state.phase.isTerminal) Reduced(state)
                else Reduced(state, Effect.send(Action.Delegate(DelegateAction.SetPinned(action.session, action.pinned))))
            }

            Action.DelegatorRequestedEnd -> Reduced(state.copy(endsAfterDelegation = true))

            is Action.AppVisibilityChanged -> {
                if (state.isAppVisible == action.visible) return Reduced(state)
                val next = state.copy(isAppVisible = action.visible)
                Reduced(next, noteScreenVisibility(from = state.screenVisibility, next))
            }

            is Action.DelegatorNotedLooking -> {
                if (state.phase.isTerminal || state.userLooking == action.looking) return Reduced(state)
                val next = state.copy(userLooking = action.looking)
                Reduced(next, noteScreenVisibility(from = state.screenVisibility, next))
            }

            is Action.DelegatorSettledRequest -> Reduced(state, Effect.send(Action.Delegate(DelegateAction.RequestSettled(action.sessionID))))

            is Action.DelegatorOpenedDraft -> {
                if (state.phase.isTerminal) Reduced(state)
                else Reduced(state, Effect.send(Action.Delegate(DelegateAction.OpenDraft(action.directory))))
            }

            is Action.DelegatorSwitchedModel -> {
                if (state.phase.isTerminal) return Reduced(state)
                var next = state
                val sessionID = action.sessionID
                if (sessionID == null && next.isOnDraft) next = next.copy(pendingModel = action.model)
                if (sessionID != null && sessionID == next.target.sessionID) next = next.copy(pendingModel = null)
                Reduced(next, Effect.send(Action.Delegate(DelegateAction.ModelSwitched(sessionID, action.model))))
            }

            is Action.PendingModelApplied -> {
                var next = state
                if (action.sessionID == next.target.sessionID && next.pendingModel == action.model) next = next.copy(pendingModel = null)
                Reduced(next, Effect.send(Action.Delegate(DelegateAction.PendingModelApplied(action.sessionID, action.model))))
            }

            is Action.DelegatorKeptPick -> {
                var next = state
                if (action.sessionID == next.target.sessionID) next = next.copy(pendingModel = action.model)
                Reduced(next, Effect.send(Action.Delegate(DelegateAction.PickWaiting(action.sessionID, action.model))))
            }

            is Action.DelegatorExplained -> Reduced(state.recordHandoff(action.sessionID, dependency(DateClient).now()))

            is Action.DelegatorFinished -> reduceDelegatorFinished(state, action)

            is Action.ReplyWatch -> {
                val (watch, effects) = state.replyWatch.transition(action.event)
                settleSessionDelegatorTurns(state.copy(replyWatch = watch), effects)
            }

            is Action.WorkFailed -> {
                var next = state
                val index = next.pendingWork.indexOfFirst { it.callID == action.callID }
                if (index >= 0) {
                    next = next.releaseUnadmitted(next.pendingWork[index])
                    val remaining = next.pendingWork.toMutableList().also { it.removeAt(index) }
                    if (remaining.isNotEmpty() && remaining.none { !it.superseded }) {
                        remaining[remaining.lastIndex] = remaining.last().copy(superseded = false)
                    }
                    next = next.copy(pendingWork = remaining)
                }
                Reduced(next, completeFunction(action.callID, if (action.callID.startsWith("client_verify_")) "" else VoiceHandoff.failure(action.message)))
            }

            Action.MuteTapped -> {
                val muted = !state.isMuted
                dependency(VoiceCallSounds).voice(VoiceCallHaptic.forMute(isMuted = muted))
                Reduced(state.copy(isMuted = muted), Effect.run { voiceCall.setMuted(muted) })
            }

            Action.EndPressed -> {
                if (!state.phase.showsCard && state.phase.isTerminal) return Reduced(state)
                // The press answers the finger at once; the call's own cue lands
                // later, as the ending sweep reaches End (`cancelPressed` plays `.cancel`
                // and lets the landing skip a cue too close to it).
                dependency(VoiceCallSounds).cancelPressed()
                Reduced(state, Effect.send(Action.EndTapped))
            }

            Action.EndTapped -> {
                // A failed or dropped call stays up as a card until dismissed; ending it then is the
                // same teardown without a second transcript write. It keeps its
                // failure, so the host knows there's no bar to sweep out.
                if (state.phase.showsCard) {
                    // Android: closing the card takes the call's notification down with it.
                    return Reduced(state.copy(callPresenceID = null), Effect.send(Action.Delegate(DelegateAction.Ended)))
                }
                // Already hung up: the bar is sweeping out.
                if (state.phase.isTerminal) return Reduced(state)
                dependency(VoiceCallSounds).voice(VoiceCallHaptic.ended)
                val finished = finish(state, State.Phase.Ended)
                Reduced(finished.state, Effect.merge(finished.effect, Effect.run { voiceCall.end() }))
            }

            is Action.CallCommand -> {
                // An ended call can still be in state while its bar sweeps out.
                if (state.phase.isTerminal) return Reduced(state)
                when (action.command) {
                    VoiceCallCommands.Action.end -> Reduced(state, Effect.send(Action.EndTapped))
                    VoiceCallCommands.Action.mute -> Reduced(state, Effect.send(Action.MuteTapped))
                }
            }

            is Action.ScreenRequest -> {
                val requested = action.action is VoiceScreenRequestFeature.Action.Requested
                Reduced(holdForClientToolCard(state, requested))
            }

            is Action.OpenLink -> {
                val requested = action.action is VoiceOpenLinkFeature.Action.Requested
                Reduced(holdForClientToolCard(state, requested))
            }

            is Action.CallAudio, is Action.FloatingStage, is Action.Delegate -> Reduced(state)
        }
    }

    /**
     * Nothing of the call's covers a client tool's card: on a request, the floating call goes
     * back to the orb, held there until the card goes.
     */
    private fun holdForClientToolCard(state: State, requested: Boolean): State {
        val holds = state.holdsClientToolCard
        var stage = state.floatingStage
        if (requested && holds) {
            stage = stage.copy(dragTarget = null, stage = FloatingCallStage.orb)
        }
        return state.copy(floatingStage = stage.copy(isHeld = holds))
    }

    private fun reduceCallEvent(state: State, event: VoiceCallEvent): Reduced<State, Action> {
        val voiceCall = dependency(VoiceCallClient)
        return when (event) {
            VoiceCallEvent.Connected -> {
                var next = state
                if (next.phase != State.Phase.Live) AppLog.voice.notice(if (next.connectedAt == null) "Call connected" else "Call reconnected")
                next = next.withPhase(State.Phase.Live)
                if (next.connectedAt == null) next = next.copy(connectedAt = dependency(DateClient).now())
                val (watch, watchEffects) = next.replyWatch.transition(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.live))
                val settled = settleSessionDelegatorTurns(next.copy(replyWatch = watch), watchEffects)
                next = settled.state
                val flush = settled.effect
                // A page opened or closed while connecting is news to the model.
                val page: Effect<Action> = if (next.startedViewing == next.viewing) Effect.none() else instructViewing(next)
                next = next.copy(startedViewing = next.viewing)
                val isAtEar = next.isAtEar
                val reroute: Effect<Action> = Effect.run { voiceCall.setAtEar(isAtEar) }
                // Moved while connecting: the model was configured for the old target.
                // A draft that only got its session meanwhile hasn't moved: the model
                // just learns it's attached to it.
                val adopted = next.lastMove == null && !next.isOnDraft &&
                    next.startedTarget?.sessionID == null && next.startedTarget?.directory == next.target.directory
                if (adopted) next = next.copy(startedTarget = next.target)
                val retarget: Effect<Action> = if (next.startedTarget == next.target) {
                    Effect.merge(reroute, page, if (adopted) updateAttachedInstructions(next) else Effect.none())
                } else {
                    Effect.merge(reroute, Effect.send(Action.TargetChanged))
                }
                Reduced(next, Effect.merge(retarget, flush))
            }

            VoiceCallEvent.UserSpeechStarted -> Reduced(state.copy(isAssistantSpeaking = false))

            is VoiceCallEvent.UserTranscript -> {
                if (!event.final) return Reduced(state)
                // Words that land after a delegation wait for the next one: the voice
                // agent delegates again when they change the work.
                val recorded = state.tail.record(VoiceTranscriptTail.Role.user, event.text, id = event.itemID)
                var next = state.copy(tail = recorded.tail)
                val outcome = recorded.outcome
                if (outcome == VoiceTranscriptTail.RecordOutcome.empty || outcome == VoiceTranscriptTail.RecordOutcome.duplicate) return Reduced(next)
                if (outcome == VoiceTranscriptTail.RecordOutcome.added) next = next.copy(replyWatch = next.replyWatch.noteUserActed())
                Reduced(upsertCaption(next, id = "user-${event.itemID}", role = VoiceTranscriptTail.Role.user, text = event.text))
            }

            is VoiceCallEvent.AssistantDelta -> {
                var next = state.copy(isAssistantSpeaking = true)
                if (next.liveAssistantItemID != event.itemID) next = next.copy(liveAssistantItemID = event.itemID, liveAssistantText = "")
                Reduced(next.copy(liveAssistantText = next.liveAssistantText + event.delta))
            }

            is VoiceCallEvent.AssistantDone -> {
                val spoken = event.text.ifEmpty { state.liveAssistantText }
                var next = state.clearLiveReply()
                if (next.phase == State.Phase.EndingAfterGoodbye(spoken = false)) next = next.withPhase(State.Phase.EndingAfterGoodbye(spoken = true))
                val recorded = next.tail.record(VoiceTranscriptTail.Role.assistant, spoken)
                next = next.copy(tail = recorded.tail)
                if (recorded.outcome != VoiceTranscriptTail.RecordOutcome.added) return Reduced(next)
                val id = "assistant-${event.itemID ?: dependency(UuidClient).next().toString().uppercase()}"
                Reduced(upsertCaption(next, id = id, role = VoiceTranscriptTail.Role.assistant, text = spoken))
            }

            VoiceCallEvent.AssistantPlaybackFinished -> {
                val next = state.copy(isAssistantSpeaking = false)
                if (next.phase == State.Phase.EndingAfterGoodbye(spoken = true)) Reduced(next, Effect.send(Action.EndTapped))
                else Reduced(next)
            }

            is VoiceCallEvent.ServerDelegation -> {
                if (!state.usesDelegator) return Reduced(state)
                delegateToDelegator(state.copy(replyWatch = state.replyWatch.noteUserActed()), callID = event.callID, onCall = true)
            }

            is VoiceCallEvent.FunctionCall -> when (event.name) {
                "delegate" -> delegateWork(
                    if (event.callID.startsWith("client_verify_")) state else state.copy(replyWatch = state.replyWatch.noteUserActed()),
                    callID = event.callID,
                    rawFraming = event.arguments["text"]?.stringValue,
                )

                "end_voice_session" -> {
                    // The goodbye is a separate response; end when it has drained, or
                    // after a bounded wait if it never comes.
                    val ending = beginEndingAfterGoodbye(state, timeout = 6.seconds)
                    Reduced(ending.state, Effect.merge(completeFunction(event.callID, "{\"status\":\"ending\"}"), ending.effect))
                }

                else -> Reduced(state, completeFunction(event.callID, VoiceHandoff.failure("Unknown tool")))
            }

            VoiceCallEvent.AudioInterrupted -> Reduced(state.copy(isAudioInterrupted = true, isAssistantSpeaking = false))

            VoiceCallEvent.AudioResumed, VoiceCallEvent.RouteChanged -> Reduced(state.copy(isAudioInterrupted = false))

            VoiceCallEvent.Reconnecting -> {
                // The reply in progress was cut off with the connection.
                val next = state.copy(isAssistantSpeaking = false).clearLiveReply()
                // A quick swap stays invisible; only a slow one says so.
                val clock = dependency(Clock)
                Reduced(
                    next,
                    Effect.run<Action> { send ->
                        clock.sleep(1.seconds)
                        send(Action.ReconnectingShown)
                    }.cancellable(CancelID.Reconnecting, cancelInFlight = true),
                )
            }

            VoiceCallEvent.Reconnected -> {
                val (watch, watchEffects) = state.replyWatch.transition(ReplyWatch.Event.Reconnected)
                val next = state.copy(isReconnecting = false, isConnectionPoor = false, replyWatch = watch)
                Reduced(next, Effect.merge(Effect.cancel(CancelID.Reconnecting), runReplyWatch(watchEffects, next)))
            }

            is VoiceCallEvent.ConnectionPoor -> {
                if (state.isConnectionPoor == event.poor) return Reduced(state)
                if (event.poor) dependency(HapticsClient).play(Haptic.warning)
                Reduced(state.copy(isConnectionPoor = event.poor))
            }

            is VoiceCallEvent.ServiceError -> finish(state, State.Phase.Failed(event.message))
        }
    }

    private fun reduceDelegatorFinished(state: State, action: Action.DelegatorFinished): Reduced<State, Action> {
        val callID = action.callID
        // From a call that has since ended (its work was dropped then).
        if (callID !in state.delegatorWork) return Reduced(state)
        var next = state.copy(delegatorWork = state.delegatorWork.filter { it != callID })
        val isVerify = callID.startsWith("client_verify_")
        val outcome = action.result.getOrNull()
        if (outcome != null) {
            if (outcome.responseID != null) next = next.copy(delegatorResponseID = outcome.responseID)
            val prompts = outcome.unanswered.map { ReplyWatch.Prompt(it) }
            val speaks = !outcome.spoken && (!isVerify || VoiceActionClaim.correctsVoice(outcome.reply))
            // What it read counts as told only now its answer is spoken (never
            // when `interrupted`), and results held for it go out after it.
            val (sentWatch, sentEffects) = next.replyWatch.transition(ReplyWatch.Event.Sent(prompts))
            val (finishedWatch, finishedEffects) = sentWatch.transition(
                ReplyWatch.Event.DelegatorFinished(
                    requestID = callID,
                    spoken = if (outcome.interrupted == null) (if (isVerify) "VERIFIED" else outcome.reply) else null,
                ),
            )
            next = next.copy(replyWatch = finishedWatch)
            var ending: Effect<Action> = Effect.none()
            if (next.endsAfterDelegation && next.phase == State.Phase.Live) {
                // Like Realtime's end_voice_session: end once the goodbye has
                // played, or after a bounded wait if it never comes.
                val begun = beginEndingAfterGoodbye(next.copy(endsAfterDelegation = false), timeout = 8.seconds)
                next = begun.state
                ending = begun.effect
            }
            return Reduced(
                next,
                Effect.merge(
                    answerThenRelease(
                        callID = callID,
                        output = if (speaks) VoiceHandoff.completion(outcome.reply) else "",
                        watchEffects = sentEffects + finishedEffects,
                        state = next,
                    ),
                    ending,
                ),
            )
        }
        val message = action.result.exceptionOrNull()?.clientMessage ?: ""
        val (watch, effects) = next.replyWatch.transition(ReplyWatch.Event.DelegatorFinished(requestID = callID, spoken = null))
        next = next.copy(replyWatch = watch)
        return Reduced(
            next,
            answerThenRelease(callID = callID, output = if (isVerify) "" else VoiceHandoff.failure(message), watchEffects = effects, state = next),
        )
    }

    private fun upsertCaption(state: State, id: String, role: VoiceTranscriptTail.Role, text: String): State {
        val index = state.captions.indexOfFirst { it.id == id }
        val captions = if (index >= 0) {
            state.captions.toMutableList().also { it[index] = it[index].copy(text = text) }
        } else {
            (state.captions + State.Caption(id = id, role = role, text = text)).takeLast(VoiceTranscriptRecord.maxLogLines)
        }
        return state.copy(captions = captions)
    }

    /**
     * One exit for every way a call stops: persist once, drop in-flight work
     * and timers, and tell the host only when the call is actually over.
     * Dropped work is abandoned, not interrupted: the session keeps running.
     */
    private fun finish(state: State, phase: State.Phase): Reduced<State, Action> {
        val inFlightCallIDs = state.pendingWork.map { it.callID } + state.abandonedCallIDs + state.delegatorWork +
            state.sessionDelegator.inFlightCallIDs
        val (watch, watchEffects) = state.replyWatch.transition(ReplyWatch.Event.CallPhaseChanged(ReplyWatch.CallPhase.ended))
        var next = state.copy(replyWatch = watch, delegatorWork = emptyList())
        val (resetDelegator, unreleased) = next.sessionDelegator.resetForCallEnd()
        next = next.copy(sessionDelegator = resetDelegator)
        for (capture in unreleased) next = next.copy(tail = next.tail.release(capture))
        next.persistTranscript(endedAt = dependency(DateClient).now())
        next = next.copy(isAssistantSpeaking = false, isReconnecting = false, isAudioInterrupted = false)
            .clearLiveReply()
            .copy(pendingWork = emptyList(), abandonedCallIDs = emptyList())
            .withPhase(phase)
        // The kind only: a failure's message can carry the service's own text.
        val ended = when (phase) {
            is State.Phase.Failed -> "failed"
            State.Phase.Dropped -> "dropped"
            else -> "ended"
        }
        AppLog.voice.notice("Call $ended")
        val cancel: Effect<Action> = Effect.merge(
            Effect.cancel(
                listOf<Any>(CancelID.Call, CancelID.Goodbye, CancelID.CallScope) +
                    inFlightCallIDs.map { CancelID.WorkAdmission(it) } +
                    inFlightCallIDs.map { CancelID.WorkCall(it) } +
                    listOf(CancelID.Reconnecting, CancelID.Instructions, CancelID.ScreenInstructions, CancelID.ReplyActiveRefresh),
            ),
            runReplyWatch(watchEffects, next),
        )
        // Refused in this pass, so the reply leaves before the socket closes.
        val screen = screenRequest.endCall(next.screenRequest)
        val link = openLink.endCall(next.openLink)
        val audio = callAudio.endCall(next.callAudio)
        next = next.copy(
            screenRequest = screen.state,
            openLink = link.state,
            callAudio = audio.state,
            floatingStage = next.floatingStage.copy(isHeld = false),
        )
        val children = Effect.merge(
            screen.effect.map { Action.ScreenRequest(it) },
            link.effect.map { Action.OpenLink(it) },
            audio.effect.map { Action.CallAudio(it) },
        )
        if (phase == State.Phase.Ended) {
            return Reduced(next, Effect.merge(cancel, children, Effect.send(Action.Delegate(DelegateAction.Ended))))
        }
        return Reduced(next, Effect.merge(cancel, children))
    }

    internal fun completeFunction(callID: String, output: String): Effect<Action> {
        val voiceCall = dependency(VoiceCallClient)
        return Effect.run {
            if (!currentCoroutineContext().isActive) return@run
            voiceCall.completeFunction(callID, output)
        }
    }

    internal fun beginEndingAfterGoodbye(state: State, timeout: Duration): Reduced<State, Action> {
        val clock = dependency(Clock)
        return Reduced(
            state.withPhase(State.Phase.EndingAfterGoodbye(spoken = false)),
            Effect.run<Action> { send ->
                clock.sleep(timeout)
                send(Action.EndTapped)
            }.cancellable(CancelID.Goodbye, cancelInFlight = true),
        )
    }

    // region Call log (composed after the call's reducer)

    /** Writes the call log from what [action] changed; a terminal call's log is finished and let go. */
    private fun logCall(state: State, action: Action): State {
        val logging = VoiceCallLogging()
        val snapshot = VoiceCallLogging.State(
            callLogID = state.callLogID,
            isOnDraft = state.isOnDraft,
            sessionTitle = state.sessionTitle,
            engine = state.activeRoute.logEngine,
            voice = state.activeVoice.rawValue,
            isAtEar = state.isAtEar,
            isMuted = state.isMuted,
            usesDelegator = state.usesDelegator,
            usesSessionDelegator = state.usesSessionDelegator,
            liveAssistantItemID = state.liveAssistantItemID,
            liveAssistantText = state.liveAssistantText,
            moveReason = state.moveReason,
            isTerminal = state.phase.isTerminal,
        )
        val finished = logging.apply(snapshot, loggingEvent(action))
        return if (finished) state.copy(callLogID = null) else state
    }

    private fun loggingEvent(action: Action): VoiceCallLogging.Event = when (action) {
        Action.Start -> VoiceCallLogging.Event.start
        is Action.CallRouteResolved -> VoiceCallLogging.Event.callRouteResolved(action.note)
        is Action.CallEvent -> when (val event = action.event) {
            is VoiceCallEvent.UserTranscript -> VoiceCallLogging.Event.userTranscript(event.itemID, event.text, event.final)
            is VoiceCallEvent.AssistantDelta -> VoiceCallLogging.Event.assistantDelta(event.itemID)
            is VoiceCallEvent.AssistantDone -> VoiceCallLogging.Event.assistantDone(event.itemID, event.text)
            VoiceCallEvent.AssistantPlaybackFinished -> VoiceCallLogging.Event.assistantPlaybackFinished
            is VoiceCallEvent.FunctionCall -> VoiceCallLogging.Event.functionCall(event.callID, event.name, event.arguments["text"]?.stringValue)
            is VoiceCallEvent.ServerDelegation -> VoiceCallLogging.Event.serverDelegation
            is VoiceCallEvent.ServiceError -> VoiceCallLogging.Event.serviceError(event.message)
            is VoiceCallEvent.ConnectionPoor -> VoiceCallLogging.Event.connectionPoor(event.poor)
            else -> VoiceCallLogging.Event.other
        }
        is Action.WorkReplied -> VoiceCallLogging.Event.workReplied(action.callID, action.reply)
        is Action.WorkFailed -> VoiceCallLogging.Event.workFailed(action.callID, action.message)
        is Action.DelegatorFinished -> {
            val outcome = action.result.getOrNull()
            if (outcome != null) {
                VoiceCallLogging.Event.delegatorFinished(reply = outcome.reply, interrupted = outcome.interrupted)
            } else {
                VoiceCallLogging.Event.delegatorFinished(reply = "", failure = action.result.exceptionOrNull()?.clientMessage ?: "")
            }
        }
        is Action.SessionStarted -> VoiceCallLogging.Event.sessionStarted(action.session.title)
        Action.TargetChanged -> VoiceCallLogging.Event.targetChanged
        Action.MuteTapped -> VoiceCallLogging.Event.muteTapped
        is Action.AtEarChanged -> VoiceCallLogging.Event.atEarChanged(action.atEar)
        else -> VoiceCallLogging.Event.other
    }

    // endregion

    // region Ongoing-call presence

    /**
     * Starts, updates or stops the call's foreground service when what it shows changes: never per
     * second (its chronometer counts on its own). No presence for a call the user or the model hung
     * up; “Call ended” for one that failed or dropped, which goes after [endedLinger] or when its
     * card closes.
     */
    private fun presenceEffect(old: State, new: State): Effect<Action> {
        val before = old.presence()
        val after = new.presence()
        if (before == after) return Effect.none()
        val service = dependency(VoiceCallServiceClient)
        if (after == null) {
            val id = before!!.callID
            return Effect.merge(
                Effect.cancel(listOf<Any>(CancelID.CallCommands, CancelID.PresenceLinger)),
                Effect.run { withContext(NonCancellable) { stopPresence(service, id) } },
            )
        }
        if (before == null || before.callID != after.callID) {
            val previous = before?.callID
            val commands = dependency(VoiceCallCommands)
            val id = after.callID
            // Collect the notification's commands before the service starts.
            val collect = Effect.run<Action> { send ->
                commands.events.collect { command -> if (command.callID == id) send(Action.CallCommand(command.action)) }
            }.cancellable(CancelID.CallCommands, cancelInFlight = true)
            val start = Effect.concatenate(
                Effect.run<Action> { if (previous != null) withContext(NonCancellable) { stopPresence(service, previous) } },
                Effect.run { send ->
                    try {
                        service.start(after)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppLog.voice.error("Call notification failed to start: ${error::class.java.simpleName}")
                        send(Action.CallCommand(VoiceCallCommands.Action.end))
                    }
                },
            )
            return Effect.merge(Effect.cancel(CancelID.PresenceLinger), collect, start)
        }
        if (after.phase == VoiceCallServiceState.Phase.ended) {
            // “Call ended” for a moment, then gone; closing the card or a new start ends it sooner.
            val clock = dependency(Clock)
            return Effect.merge(
                Effect.cancel(CancelID.CallCommands),
                Effect.run<Action> {
                    try {
                        updatePresence(service, after)
                        clock.sleep(endedLinger)
                    } finally {
                        withContext(NonCancellable) { stopPresence(service, after.callID) }
                    }
                }.cancellable(CancelID.PresenceLinger, cancelInFlight = true),
            )
        }
        return Effect.run { send ->
            if (!updatePresence(service, after)) {
                send(Action.CallCommand(VoiceCallCommands.Action.end))
            }
        }
    }

    private suspend fun updatePresence(service: VoiceCallServiceClient, state: VoiceCallServiceState): Boolean =
        try {
            service.update(state)
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.voice.error("Call notification failed to update: ${error::class.java.simpleName}")
            false
        }

    private suspend fun stopPresence(service: VoiceCallServiceClient, id: UUID) {
        try {
            service.stop(id)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            AppLog.voice.error("Call notification failed to stop: ${error::class.java.simpleName}")
        }
    }

    // endregion

    companion object {
        /** The call note when a server broker rejects client delegation and the call falls back to Built-in. */
        const val builtInFallbackNote = "Using built-in delegator for this call"

        /** The user's saved servers, most recent first: the delegator can read sessions on any of them, each with its own credentials. */
        val savedServers: List<OpenCodeServer>
            get() {
                val servers = shared(ai.opencode.server.ServerSharedKeys.savedServers).value
                val current = shared(ai.opencode.server.ServerSharedKeys.savedServer).value
                return servers + listOfNotNull(current)
            }

        /** How long an ended call's notification lingers. */
        val endedLinger: Duration = 3.seconds

        const val maxWaitAttempts = 4
        const val maxActiveWaitRetries = 30
    }
}

/**
 * What the call's ongoing notification shows: null when there's no call presence, including a call
 * the user or the model hung up.
 */
internal fun VoiceFeature.State.presence(): VoiceCallServiceState? {
    val id = callPresenceID ?: return null
    val servicePhase = when (phase) {
        VoiceFeature.State.Phase.Connecting -> VoiceCallServiceState.Phase.connecting
        VoiceFeature.State.Phase.Live, is VoiceFeature.State.Phase.EndingAfterGoodbye ->
            // Already delayed: a reconnect shorter than a second never sets it.
            if (isReconnecting) VoiceCallServiceState.Phase.reconnecting else VoiceCallServiceState.Phase.live
        VoiceFeature.State.Phase.Ended -> return null
        is VoiceFeature.State.Phase.Failed, VoiceFeature.State.Phase.Dropped -> VoiceCallServiceState.Phase.ended
    }
    return VoiceCallServiceState(callID = id, phase = servicePhase, muted = isMuted, connectedAt = connectedAt)
}

/** An effect's failure. */
internal val Throwable.clientMessage: String
    get() = message ?: toString()
