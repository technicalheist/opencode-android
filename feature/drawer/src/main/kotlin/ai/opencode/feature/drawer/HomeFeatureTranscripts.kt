package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.transcriptWithContextUsageAndCursor
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.Duration as JavaDuration
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/*
 * Background transcript reads (prefetch, cached refreshes), the selected session's first read,
 * active-ancestry discovery, and view receipts. Everything here steps aside while the open session
 * reads its own transcript.
 */

/**
 * Stops speculative drawer, prefetch and active-ancestry reads so the selected session's own
 * foreground requests have the connection to themselves.
 */
internal fun HomeFeature.cancelBackgroundTranscriptReads(
    state: HomeFeature.State,
    including: Set<String> = emptySet(),
): Reduced<HomeFeature.State, HomeFeature.Action> {
    val cancelledCached = state.refreshingTranscripts - including
    val refreshing = state.refreshingTranscripts + including
    var s = state.copy(refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet())
    if (including.isNotEmpty()) s = s.copy(dirtyCachedTranscriptIDs = s.dirtyCachedTranscriptIDs.filterNot { it in including })
    for (sessionID in cancelledCached.sorted()) s = s.notingDirtyCachedTranscript(sessionID)
    val cancelCached = refreshing.sorted().map { HomeFeature.CancelID.CachedTranscript(it) }
    return Reduced(
        s,
        Effect.cancel(listOf<Any>(HomeFeature.CancelID.Prefetch, HomeFeature.CancelID.ActiveAncestry) + cancelCached),
    )
}

/**
 * Re-triggers unresolved active ancestry discovery and drains cached sessions whose background
 * refresh was cancelled or skipped while a foreground transcript load had the connection.
 */
internal fun HomeFeature.settleForegroundTranscriptLoad(state: HomeFeature.State): Reduced<HomeFeature.State, HomeFeature.Action> {
    if (!state.isConnected || state.isInitialTranscriptInFlight) return Reduced(state)
    val drained = drainDirtyCachedTranscripts(state)
    return Reduced(drained.state, Effect.merge(resolveActiveAncestry(state), drained.effect))
}

internal fun HomeFeature.drainDirtyCachedTranscripts(state: HomeFeature.State): Reduced<HomeFeature.State, HomeFeature.Action> {
    if (!state.isConnected || state.isInitialTranscriptInFlight || state.dirtyCachedTranscriptIDs.isEmpty()) return Reduced(state)
    val dirty = state.dirtyCachedTranscriptIDs
    var s = state.copy(dirtyCachedTranscriptIDs = emptyList())
    val effects = dirty.mapIndexed { index, sessionID ->
        val reduced = refreshCachedTranscript(s, sessionID, after = (500 * (index + 1)).milliseconds, force = true)
        s = reduced.state
        reduced.effect
    }
    return Reduced(s, Effect.merge(effects))
}

/**
 * Resolves unknown parent chains for active sessions after the snapshot publishes, stepping aside
 * while the foreground session loads its own transcript.
 */
internal fun HomeFeature.resolveActiveAncestry(state: HomeFeature.State): Effect<HomeFeature.Action> {
    if (!state.isConnected || state.isInitialTranscriptInFlight) return Effect.none()
    val unattributed = state.unattributedActiveSessionIDs
    if (unattributed.isEmpty()) return Effect.none()
    val server = state.server
    val roots = state.sessions.map { it.id }
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    return Effect.run<HomeFeature.Action> { send ->
        val policy = NetworkRetry.currentPolicy(RetryPolicy.request)
        val parents = try {
            NetworkRetry.run(
                policy = policy,
                idempotency = RequestIdempotency.Idempotent,
                label = "GET /api/session/{id} (activeAncestry)",
                clock = clock,
            ) { _ ->
                try {
                    withRetryPolicy(RetryPolicy.once) { client.activeSessionParents(server, unattributed, roots) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: OpenCodeClientError) {
                    throw error
                } catch (_: Exception) {
                    throw OpenCodeClientError("Couldn’t load active session ancestry")
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return@run
        }
        if (!currentCoroutineContext().isActive || parents.isEmpty()) return@run
        send(HomeFeature.Action.ActiveAncestryResolved(parents))
    }.cancellable(HomeFeature.CancelID.ActiveAncestry, cancelInFlight = true)
}

/**
 * Reads a session's transcript again, outside the open one, so opening it shows what's new at
 * once: one read per session at a time. [freshFor] skips a session read that recently; [force]
 * replaces a read in flight, except that a read wanted now joins one already sent without a delay.
 */
internal fun HomeFeature.refreshCachedTranscript(
    state: HomeFeature.State,
    sessionID: String,
    after: Duration = Duration.ZERO,
    freshFor: Duration? = null,
    force: Boolean = false,
): Reduced<HomeFeature.State, HomeFeature.Action> {
    if (sessionID == state.topDetail?.session?.id ||
        sessionID == state.sessionDetail?.session?.id ||
        state.sessions.none { it.id == sessionID } ||
        sessionID in state.recentlyDeletedIDs
    ) {
        return Reduced(state)
    }
    if (freshFor != null) {
        val readAt = state.transcriptReadAt[sessionID]
        if (readAt != null && JavaDuration.between(readAt, dependency(DateClient).now()).toMillis() < freshFor.inWholeMilliseconds) {
            return Reduced(state)
        }
    }
    // Offline, the reconnect's snapshot drains what's marked here.
    if (state.isInitialTranscriptInFlight || !state.isConnected) return Reduced(state.notingDirtyCachedTranscript(sessionID))
    var s = state.copy(dirtyCachedTranscriptIDs = state.dirtyCachedTranscriptIDs - sessionID)
    if (sessionID in s.refreshingTranscripts) {
        if (!force) return Reduced(s)
        if (after == Duration.ZERO && sessionID in s.fetchingTranscripts) return Reduced(s)
    }
    s = s.copy(
        refreshingTranscripts = s.refreshingTranscripts + sessionID,
        fetchingTranscripts = if (after == Duration.ZERO) s.fetchingTranscripts + sessionID else s.fetchingTranscripts - sessionID,
    )
    val server = s.server
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    return Reduced(
        s,
        Effect.run<HomeFeature.Action> { send ->
            if (after > Duration.ZERO) clock.sleep(after)
            if (!currentCoroutineContext().isActive) return@run
            val items = try {
                client.transcript(server, sessionID, SessionFeature.initialFetchLimit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                null
            }
            if (!currentCoroutineContext().isActive) return@run
            send(HomeFeature.Action.CachedTranscriptRefreshed(sessionID, items))
        }.cancellable(HomeFeature.CancelID.CachedTranscript(sessionID), cancelInFlight = true),
    )
}

/**
 * Warms the transcript cache for the rows most likely to be opened next, so the first tap renders
 * at once. Only the top few are ever fetched, staggered behind the snapshot and the open session.
 */
internal fun HomeFeature.prefetchTranscripts(state: HomeFeature.State): Effect<HomeFeature.Action> {
    // Keep speculative history off the connection while an initial or foreground request is in
    // flight, and off a server that isn't answering.
    if (state.isInitialTranscriptInFlight || !state.isConnected) return Effect.none()
    val openIDs = setOfNotNull(state.sessionDetail?.session?.id, state.topDetail?.session?.id, state.selectedTranscriptFetch?.sessionID)
    val byID = state.sessions.associateBy { it.id }
    val catchUp = state.catchUp
    val targets = if (catchUp != null) {
        catchUp.queue.filter { it in byID && it !in state.transcriptCache && it !in openIDs }.take(HomeFeature.prefetchCount)
    } else {
        state.sessionsByPriority().take(HomeFeature.prefetchCount)
            .filter { it.id !in state.transcriptCache && it.id !in openIDs }
            .map { it.id }
    }
    if (targets.isEmpty()) return Effect.none()
    val server = state.server
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    return Effect.run<HomeFeature.Action> { send ->
        for ((index, sessionID) in targets.withIndex()) {
            if (index > 0) clock.sleep(50.milliseconds)
            if (!currentCoroutineContext().isActive) return@run
            val items = try {
                client.transcript(server, sessionID, SessionFeature.initialFetchLimit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                if (!currentCoroutineContext().isActive) return@run
                continue
            }
            if (!currentCoroutineContext().isActive) return@run
            send(HomeFeature.Action.TranscriptPrefetched(sessionID, items))
        }
    }.cancellable(HomeFeature.CancelID.Prefetch, cancelInFlight = true)
}

/** The selected session's first frame, with the next page's cursor from the same response. */
internal suspend fun selectedTranscript(
    client: OpenCodeClient,
    server: OpenCodeServer,
    sessionID: String,
): Pair<Result<List<OpenCodeTranscriptItem>>, SessionFeature.AuthoritativeRefresh.PageCursor?> = try {
    val window = client.transcriptWithContextUsageAndCursor(server, sessionID, SessionFeature.firstFrameFetchLimit)
    Result.success(window.items) to window.pageCursor?.let { SessionFeature.AuthoritativeRefresh.PageCursor(it.next, it.reachesStart, it.isComplete) }
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (error: OpenCodeClientError) {
    Result.failure<List<OpenCodeTranscriptItem>>(error) to null
}

/** Routed through the session screen so it records the receipt as its own. */
internal fun HomeFeature.acknowledgeShownSubagent(sessionID: String, idle: Long, server: OpenCodeServer): Effect<HomeFeature.Action> {
    val client = dependency(OpenCodeClient)
    return Effect.run<HomeFeature.Action> { send ->
        try {
            client.viewSession(server, sessionID, idle)
            send(HomeFeature.Action.detail(SessionFeature.Action.ViewAcknowledged(sessionID, idle)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            val description = (error as? OpenCodeClientError)?.logSummary ?: error::class.java.simpleName
            AppLog.network.notice { "Subagent view receipt failed: $description" }
        }
    }.cancellable(HomeFeature.CancelID.ViewReceipt(sessionID), cancelInFlight = true)
}

internal fun HomeFeature.acknowledgeView(session: OpenCodeSession, server: OpenCodeServer): Effect<HomeFeature.Action> {
    val idle = session.idleMilliseconds ?: return Effect.none()
    val client = dependency(OpenCodeClient)
    return Effect.run<HomeFeature.Action> { send ->
        try {
            client.viewSession(server, session.id, idle)
            send(HomeFeature.Action.ViewAcknowledged(session.id, idle))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // Keep the server unread state until an acknowledgment succeeds.
        }
    }.cancellable(HomeFeature.CancelID.ViewReceipt(session.id), cancelInFlight = true)
}

/** Seconds as a [Duration], for `freshFor`. */
internal fun freshFor(seconds: Int): Duration = seconds.seconds
