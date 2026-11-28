package ai.opencode.feature.composer

import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.server.model.OpenCodeDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant

/**
 * The composer's dictation: press tokens, tap vs hold decided at the lift, lock, cancel, and where
 * the words land. The session reducer embeds it and owns the draft: the helpers ([start], [stop],
 * [finish], [ends], [discardPending]) take the draft's text and return the next state with their
 * effect.
 */
object DictationFeature : Reducer<DictationFeature.State, DictationFeature.Action> {
    sealed interface Phase {
        data object Idle : Phase

        /** Mic is on but the engine is still setting up (possibly installing the language): no words yet. */
        data object Preparing : Phase
        data object Recording : Phase

        /**
         * The mic was stopped intentionally and the engine is finalizing; its last
         * (often only) transcript is still welcome in the draft. With a delivery,
         * the draft is sent that way once it lands.
         */
        data class Finishing(val send: OpenCodeDelivery?) : Phase
    }

    data class Sending(
        val recording: Int,
        /** The draft it sends; this reducer only rewrites its text. */
        val draft: DraftState,
        val delivery: OpenCodeDelivery,
        val base: String = "",
        /** The session it was sent in; null for a draft. It goes out only there. */
        val sessionID: String? = null,
        /** Stopped from the notification, or the app left: its words wait as a draft instead of sending. */
        val keepsAsDraft: Boolean = false,
    )

    data class Hold(
        /** The draft as it will read with the words so far, typed text first. */
        val text: String,
        /** Slid up or tapped: recording continues hands-free until Send or Cancel. */
        val isLocked: Boolean = false,
        /**
         * What letting go will do instead of sending, while the finger is still
         * down: sliding only arms; releasing commits. A recording locked by
         * sliding keeps [Arm.Lock]; a tap leaves it null.
         */
        val armed: Arm? = null,
    ) {
        enum class Arm { Lock, Cancel }

        /** What the screen draws of a hold: everything but its words. */
        data class Pose(val isLocked: Boolean = false, val armed: Arm? = null)

        val pose: Pose get() = Pose(isLocked, armed)
    }

    data class Discarded(val recording: Int, val press: Int)

    data class Carried(
        val phase: Phase,
        val recording: Int,
        val press: Int,
        val hold: Hold?,
        val transcript: String,
        /** An earlier recording still finalizing for the session it was sent in. */
        val sending: Sending?,
        /** It was answering a pending request here: it ends with this screen instead of following to the next. */
        val isAnswer: Boolean = false,
    )

    data class State(
        val phase: Phase = Phase.Idle,
        val error: String? = null,
        /** What was typed before the recording, spaced for its words to follow. */
        val base: String = "",
        /**
         * Which recording the dictation events belong to. A press while the last
         * recording finalizes to send starts a new one; the last keeps finalizing
         * as [sending] and goes out on its own.
         */
        val recording: Int = 0,
        /** The client's token for the live recording's press, so a late stop or cancel only ends its own press. */
        val press: Int = 0,
        /** A recording let go to send whose last words are still finalizing while a newer press records. */
        val sending: Sending? = null,
        /** When the last recording ended (sent, stopped, cancelled or yielded), for Raise to Talk's grace after it. */
        val endedAt: Instant? = null,
        /**
         * Recording a message by voice, Telegram style: the mic was held (and maybe
         * locked), words collect here instead of in the draft, and they are sent
         * when recording ends. Null for TalkBack's tap-to-dictate, which streams
         * into the draft.
         */
        val hold: Hold? = null,
        /** `stopped` scheduled `deferredSendReady`; meanwhile the draft edit observer must not strip the attachments. */
        val sendPending: Boolean = false,
        /** An answer's recording carried from the last screen, to be discarded when this screen's task starts. */
        val discarded: Discarded? = null,
    ) {
        val isDictating: Boolean get() = phase == Phase.Preparing || phase == Phase.Recording
        val isPreparing: Boolean get() = phase == Phase.Preparing
        val isFinishing: Boolean get() = phase is Phase.Finishing

        /** Recording, or finalizing its last words. */
        val hasLive: Boolean get() = isDictating || isFinishing

        /**
         * Raise to Talk never starts a call while the mic records or finalizes a
         * message, nor within [RAISE_TO_TALK_GRACE] after one ends.
         */
        fun blocksRaise(now: () -> Instant): Boolean {
            if (hasLive || sending != null) return true
            val ended = endedAt ?: return false
            return Duration.between(ended, now()).toMillis() < RAISE_TO_TALK_GRACE.toMillis()
        }

        /** The words heard so far, without what was typed before them. */
        fun transcript(draft: String): String {
            val text = hold?.text ?: draft
            return if (text.startsWith(base)) text.substring(base.length) else ""
        }

        /**
         * Shows the words after what was typed: in the hold while the mic is held
         * or locked (the returned text is null), otherwise the updated draft text.
         */
        fun show(transcript: String): Pair<State, String?> {
            val text = base + transcript
            if (hold != null) return copy(hold = hold.copy(text = text)) to null
            return this to text
        }

        /**
         * The typed text changed under a running recording (something shared, Catch
         * Up's composer coming back): its words follow the new text. Returns the
         * next state and the draft text to set, if any.
         */
        fun rebase(typed: String, draft: String): Pair<State, String?> {
            val current = transcript(draft)
            val rebased = copy(base = base(after = typed))
            if (rebased.hold != null) return rebased.show(current).first to typed
            return rebased.show(current)
        }

        fun enterBackground(): State {
            var next = this
            val phase = phase
            if (phase is Phase.Finishing && phase.send != null) next = next.copy(phase = Phase.Finishing(send = null))
            return next.copy(sending = next.sending?.copy(keepsAsDraft = true))
        }

        companion object {
            /**
             * How long after a recording ends Raise to Talk stays quiet: lifting the
             * phone to read what was just dictated often covers the proximity
             * sensor. 3 s outlasts that.
             */
            val RAISE_TO_TALK_GRACE: Duration = Duration.ofSeconds(3)

            fun base(after: String): String =
                if (after.isEmpty() || after.endsWith(" ") || after.endsWith("\n")) after else "$after "
        }
    }

    sealed interface Action {
        data object MicTapped : Action

        /** Touch down on the mic: dictate while held. */
        data object MicPressBegan : Action

        /** Finger slid onto or off the lock or cancel zone while holding the mic. */
        data class MicPressArmed(val arm: Hold.Arm?) : Action

        /** Let go of a held mic: lock or cancel if armed, otherwise finish and send. */
        data object MicPressReleased : Action

        /** Tapped the mic (didn't hold): lock into hands-free recording. */
        data object MicPressLocked : Action

        /** The system took a held mic's touch (a call, the app leaving): lock so the words wait for Stop or Send. */
        data object MicPressInterrupted : Action

        /** Tapped Cancel in the locked dictation bar, or released on Slide to cancel. */
        data object MicPressCancelled : Action

        /** Tapped Stop in the dictation bar: finish and place the words in the draft. */
        data object InsertTapped : Action

        /** Tapped Send in the dictation bar: finish, then send the draft with the default delivery (steer). */
        data object SendTapped : Action

        /** Chose Queue from Send's menu in the dictation bar mid-run. */
        data object QueueTapped : Action

        /** Stop on the dictation notification: finish into the draft, never send. */
        data object StopRequested : Action
        data object Ready : Action
        data class Updated(val transcript: String, val recording: Int = 0) : Action
        data class Stopped(val errorMessage: String?, val recording: Int = 0) : Action
    }

    data class CancelID(val recording: Int)

    private val dictation by Dependency(DictationClient)
    private val haptics by Dependency(HapticsClient)

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            is Action.MicPressArmed -> {
                val hold = state.hold
                if (hold == null || hold.isLocked || hold.armed == action.arm || !state.isDictating) return Reduced(state)
                // The same for lock and cancel: a tap arming, a tick disarming.
                haptics.play(if (action.arm == null) Haptic.selection else Haptic.threshold)
                return Reduced(state.copy(hold = hold.copy(armed = action.arm)))
            }

            Action.MicPressInterrupted, Action.MicPressLocked -> {
                // The permission prompt, a banner, another finger, or the app leaving:
                // none of them is a choice to stop or send, so recording carries on
                // locked and the user picks Stop or Send.
                val hold = state.hold
                if (hold == null || hold.isLocked || !state.isDictating) return Reduced(state)
                if (action == Action.MicPressLocked) haptics.play(Haptic.selection)
                return Reduced(state.copy(hold = hold.copy(isLocked = true, armed = null)))
            }

            Action.InsertTapped -> {
                if (!state.isDictating) return Reduced(state)
                haptics.play(Haptic.tap)
                return finish(state, send = null, dictation = dictation)
            }

            Action.StopRequested -> {
                val next = state.copy(sending = state.sending?.copy(keepsAsDraft = true))
                // Stop on the notification: like the bar's Stop, the words go to the
                // draft; a send still waiting on its last words doesn't happen.
                val phase = next.phase
                if (phase is Phase.Finishing && phase.send != null) {
                    return Reduced(next.copy(phase = Phase.Finishing(send = null)), ends(next.press, DictationEnd.Insert, dictation))
                }
                return finish(next, send = null, dictation = dictation)
            }

            Action.SendTapped, Action.QueueTapped -> {
                val delivery = if (action == Action.QueueTapped) OpenCodeDelivery.Queue else OpenCodeDelivery.Steer
                if (state.phase == Phase.Finishing(send = null)) {
                    // Send after Stop, while the last words are still landing: they go
                    // out with the draft instead.
                    haptics.play(Haptic.tap)
                    return Reduced(state.copy(phase = Phase.Finishing(send = delivery)), ends(state.press, DictationEnd.Send, dictation))
                }
                if (!state.isDictating) return Reduced(state)
                haptics.play(Haptic.tap)
                return finish(state, send = delivery, dictation = dictation)
            }

            Action.Ready -> {
                if (state.phase != Phase.Preparing) return Reduced(state)
                return Reduced(state.copy(phase = Phase.Recording))
            }

            is Action.Updated -> {
                if (action.recording != state.recording) {
                    val sending = state.sending
                    if (sending != null && sending.recording == action.recording) {
                        return Reduced(state.copy(sending = sending.copy(draft = sending.draft.copy(text = sending.base + action.transcript))))
                    }
                }
                // The live recording's words: the session reducer places them.
                return Reduced(state)
            }

            Action.MicTapped, Action.MicPressBegan, Action.MicPressReleased, Action.MicPressCancelled, is Action.Stopped ->
                return Reduced(state)
        }
    }

    /**
     * Starts a recording after [draft]'s typed text. The mic starts now, from the
     * touch (`DictationClient.begin`); the effect only adopts the press's recording.
     */
    fun start(state: State, draft: String, dictation: DictationClient): Reduced<State, Action> {
        // The engine streams whole-session transcripts; they land after whatever
        // was already typed. The base lives in state: a session switch moves the
        // recording to a composer with other text.
        val recording = state.recording
        val press = dictation.begin()
        val next = state.copy(error = null, phase = Phase.Preparing, base = State.base(after = draft), press = press)
        val effect = Effect.run<Action>(
            onError = { error, send ->
                val message = if (error is CancellationException) null else (error as? DictationError)?.errorDescription ?: error.message
                send(Action.Stopped(errorMessage = message, recording = recording))
            },
        ) { send ->
            DictationLatency.mark("effect_start")
            val transcripts = try {
                dictation.start(press)
            } catch (cancellation: CancellationException) {
                // A cancelled effect sends nothing.
                if (!currentCoroutineContext().isActive) throw cancellation
                // Stopped before it reached the mic, or it yielded the audio to a
                // call: over, with no words.
                send(Action.Stopped(errorMessage = null, recording = recording))
                return@run
            }
            send(Action.Ready)
            // Leaving the effect (cancelled) cancels the channel: capture stops.
            transcripts.consumeEach { send(Action.Updated(it, recording = recording)) }
            send(Action.Stopped(errorMessage = null, recording = recording))
        }.cancellable(CancelID(recording), cancelInFlight = true)
        return Reduced(next, effect)
    }

    /**
     * Discards dictation: tears down the mic at once, drops audio not yet
     * transcribed, and cancels the transcript stream so nothing else lands in the
     * draft. For cancels, sends and manual edits. Safe to call unconditionally.
     */
    fun stop(state: State, endedAt: Instant?, dictation: DictationClient): Reduced<State, Action> {
        val phase = state.phase
        var next = state.copy(phase = Phase.Idle, hold = null)
        if (phase == Phase.Idle) return Reduced(next)
        if (endedAt != null) next = next.copy(endedAt = endedAt)
        val press = state.press
        return Reduced(
            next,
            Effect.merge(
                Effect.cancel(CancelID(state.recording)),
                Effect.run { dictation.cancel(press) },
            ),
        )
    }

    /**
     * Finishes dictation intentionally: stops capture but keeps the transcript
     * stream alive so the engine's finalized text still lands in the draft.
     */
    fun finish(state: State, send: OpenCodeDelivery?, dictation: DictationClient): Reduced<State, Action> {
        if (!state.isDictating) return Reduced(state)
        val press = state.press
        val end = if (send == null) DictationEnd.Insert else DictationEnd.Send
        return Reduced(
            state.copy(phase = Phase.Finishing(send = send)),
            Effect.run {
                dictation.ends(press, end)
                dictation.stop(press)
            },
        )
    }

    /** Tells the client how [press] ends now, for the audio hold after it. */
    fun ends(press: Int, end: DictationEnd, dictation: DictationClient): Effect<Action> =
        Effect.run { dictation.ends(press, end) }

    fun discardPending(state: State, dictation: DictationClient): Reduced<State, Action> {
        val discarded = state.discarded ?: return Reduced(state)
        return Reduced(
            state.copy(discarded = null),
            Effect.merge(
                Effect.cancel(CancelID(discarded.recording)),
                Effect.run { dictation.cancel(discarded.press) },
            ),
        )
    }
}
