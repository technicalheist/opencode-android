package ai.opencode.feature.voice

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Whether the user can see the app during a call. */
enum class VoiceScreenVisibility {
    /** The app is in front and the user said, this call, that they're looking. */
    visible,

    /** The app is in the background (the phone is locked or another app is in front), or the phone is at the user's ear. */
    appHidden,

    /** The app is in front, but the user hasn't said they're looking at it (or said they stopped): the default. */
    notLooking,
    ;

    val userSeesScreen: Boolean get() = this == visible

    /** What the delegator is told while the user can't see the screen. */
    val unseenNote: String?
        get() = when (this) {
            visible -> null
            appHidden -> "The user can't see the screen right now (the phone is locked or the app is in the background): $IN_WORDS"
            notLooking -> "Assume the user isn't looking at the screen (they haven't said they are): $IN_WORDS"
        }

    private companion object {
        const val IN_WORDS = "refer to sessions by name and describe results in words (what changed, the key lines, counts), never \"as you can see\" or anything on screen."
    }
}

/** Seen only when the app is in front, away from the ear, and the user said they're looking. */
val VoiceFeature.State.screenVisibility: VoiceScreenVisibility
    get() = when {
        !isAppVisible || isAtEar -> VoiceScreenVisibility.appHidden
        userLooking -> VoiceScreenVisibility.visible
        else -> VoiceScreenVisibility.notLooking
    }

fun VoiceFeature.State.viewingSession(at: Instant): OpenCodeSession? =
    target.sessionID?.let { OpenCodeSession(id = it, serverTitle = sessionTitle, projectID = "", directory = target.directory, updatedAt = at) }

/** Adopts the session created for the current draft without treating it as a move. */
fun VoiceFeature.State.adoptDraftSession(session: OpenCodeSession): VoiceFeature.State {
    val started = startedTarget
    return copy(
        target = target.copy(sessionID = session.id),
        draftSessionID = null,
        sessionTitle = VoiceFeature.State.usableTitle(session.title) ?: sessionTitle,
        adoptedDraftSessionID = session.id,
        startedTarget = if (phase == VoiceFeature.State.Phase.Live && started != null && started.sessionID == null && started.directory == session.directory) {
            started.copy(sessionID = session.id)
        } else {
            started
        },
    )
}

/** Whether this call explained itself to the session recently, with no compaction since (D4a). */
fun VoiceFeature.State.isSessionExplained(sessionID: String, at: Instant): Boolean {
    val handoff = handoffs[sessionID] ?: return false
    return secondsBetween(handoff.lastDelegationAt, at) <= VoiceHandoff.headerLapse &&
        handoff.compactionID == compactions[sessionID]
}

fun VoiceFeature.State.explainedSessionIDs(at: Instant): Set<String> =
    handoffs.keys.filterTo(LinkedHashSet()) { isSessionExplained(it, at) }

fun VoiceFeature.State.recordHandoff(sessionID: String, at: Instant): VoiceFeature.State =
    copy(handoffs = handoffs + (sessionID to VoiceFeature.State.Handoff(lastDelegationAt = at, compactionID = compactions[sessionID])))

/**
 * How long optional context (a session's recent messages) may hold back
 * a call's start or its instructions after a move.
 */
internal val voiceContextDeadline: Duration = 600.milliseconds

/** The work's result, or null if it takes longer than [limit]. */
internal suspend fun <T> voiceBounded(limit: Duration, work: suspend () -> T): T? = withTimeoutOrNull(limit) { work() }

internal suspend fun fetchRecentMessages(target: VoiceWorkTarget): List<String> {
    val sessionID = target.sessionID ?: return emptyList()
    val voiceWork = dependency(VoiceWorkClient)
    return voiceBounded(voiceContextDeadline) {
        try {
            voiceWork.recentMessages(target.server, sessionID, 12)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    } ?: emptyList()
}

/**
 * What the delegator is told the app shows: the session on screen (or the
 * draft) and its status, work in flight, and a recent move.
 */
internal fun delegatorContext(state: VoiceFeature.State, otherRequests: Int): String {
    val now = dependency(DateClient).now()
    var text = state.replyWatch.statusSummary(
        viewing = state.viewingSession(at = now),
        needsInput = state.targetNeedsInput,
        draftDirectory = state.target.directory,
        hasDirectVoiceWork = state.pendingWork.isNotEmpty(),
        otherDelegatorRequestsInFlight = otherRequests,
        screen = state.screenVisibility,
    )
    val move = state.lastMove
    if (move != null) {
        val ago = secondsBetween(move.at, now)
        if (ago >= 0 && ago <= VoiceHandoff.recentMoveWindow) {
            text += "\n" + VoiceHandoff.recentMove(from = move.title, sessionID = move.sessionID, secondsAgo = ago.toInt())
        }
    }
    return text
}

/** In seconds. */
internal fun secondsBetween(earlier: Instant, later: Instant): Double = java.time.Duration.between(earlier, later).toNanos() / 1e9
