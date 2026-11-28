package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.BackgroundTimeClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.log.AppLog
import ai.opencode.core.log.logSummary
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.ServerFlowTunables
import ai.opencode.server.ConnectionRoute
import ai.opencode.server.EventStreamOverflow
import ai.opencode.server.LocalNetworkPermission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.ServerLinkReset
import ai.opencode.server.retry.NetworkPathClient
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.RetrySchedule
import ai.opencode.server.retry.RetryVerdict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The event-stream lifecycle, retry timers, and last-known activity deadline. Home reconciles REST
 * snapshots into its session list and coordinates requests on open screens; those operations can
 * settle this child's connection state (`HomeFeatureConnection.kt`).
 *
 * Its cancellation identities are Home's ([HomeFeature.CancelID]): Home also cancels retries after
 * an authoritative snapshot settles, so both sides name the same effects.
 */
class HomeConnectionFeature : Reducer<HomeConnectionFeature.State, HomeConnectionFeature.Action> {
    data class State(
        val server: OpenCodeServer,
        val connectionStatus: ConnectionStatus = ConnectionStatus.connected,
        /** Why the event stream last failed for good. */
        val connectionErrorMessage: String? = null,
        /**
         * The server refused the credentials with a 401: retrying can't help until they're edited
         * or the server is paired again.
         */
        val credentialsRejected: Boolean = false,
        /** A live stream has connected in this process. */
        val hasConnected: Boolean = false,
        /** The view's task owns the event stream, so foregrounding can restart it. */
        val isStreaming: Boolean = false,
        val isEventStreamLive: Boolean = false,
        /** Bookkeeping, not shown. */
        val snapshotFailures: Int = 0,
        val lastKnownActivityDeadline: Instant? = null,
        /** A reconnect has outlasted `ServerFlowTunables.grace`, or the person asked for it: screens may say so. */
        val showsRecovery: Boolean = false,
        /**
         * Between losing the server (or launching without it) and hearing from it again, timed by
         * [HomeFeature.CancelID.ConnectionGrace].
         */
        val isRecovering: Boolean = false,
        /** Attempts that failed since the reconnect began. */
        val recoveryFailures: Int = 0,
        /** `ServerFlowTunables.offlineGrace` has passed: the next failure, or the second in all, reads as Offline. */
        val offlineGraceElapsed: Boolean = false,
        /** Left for the background and not yet active again. */
        val isInBackground: Boolean = false,
    ) {
        @Suppress("EnumEntryName")
        enum class ConnectionStatus { reconnecting, connected, disconnected }

        val isConnected: Boolean get() = connectionStatus == ConnectionStatus.connected

        /**
         * What screens show. A reconnect stays quiet through its grace, so a handoff, a cold launch
         * or a return to the app that recovers in a moment shows nothing at all.
         */
        val displayedStatus: ConnectionStatus
            get() = if (connectionStatus == ConnectionStatus.reconnecting && !showsRecovery) ConnectionStatus.connected else connectionStatus

        /**
         * Whether becoming active should restart the event stream and read Home again: after the
         * background (the stream may have been cut meanwhile), without a live stream, or after a
         * stream silent long enough ([foregroundSilence]) to have missed its heartbeats. A
         * notification shade or a system dialog over the app leaves a healthy stream alone.
         */
        fun resyncsWhenActive(silence: Duration?): Boolean {
            if (credentialsRejected) return false
            return isInBackground || !isEventStreamLive || (silence ?: Duration.ZERO) >= foregroundSilence
        }

        /** Heard from the server: the reconnect, and its timer, are over. */
        fun settleRecovery(): State =
            copy(isRecovering = false, showsRecovery = false, recoveryFailures = 0, offlineGraceElapsed = false)
    }

    sealed interface Action {
        data object Task : Action
        data object SceneEnteredBackground : Action

        /** The app became active again (from the background, or from a brief interruption such as the notification shade). */
        data object SceneBecameActive : Action
        data class ServerConnected(val server: OpenCodeServer) : Action
        data class EventStreamFailed(val error: OpenCodeClientError) : Action
        data object ConnectionLost : Action
        data object SnapshotFailed : Action
        data object SnapshotRetryRequested : Action
        data object LastKnownActivityExpired : Action
        data object NetworkRerouted : Action
        data object RetryConnectionTapped : Action
        data object ReconnectingGraceElapsed : Action

        /** [pathDown]: the phone has had no network path for `ServerFlowTunables.unsatisfiedOfflineGrace`. */
        data class OfflineGraceElapsed(val pathDown: Boolean) : Action
        data class ServerEvent(val event: OpenCodeServerEvent) : Action
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            var next = state.copy(isStreaming = true)
            val networkPath = dependency(NetworkPathClient)
            var recovery: Effect<Action> = Effect.none()
            if (next.connectionStatus == State.ConnectionStatus.reconnecting) {
                val begun = beginRecovery(next, visible = false)
                next = begun.state
                recovery = begun.effect
            }
            Reduced(
                next,
                Effect.merge(
                    eventStream(next.server),
                    Effect.run<Action> { send ->
                        networkPath.reroutes().collect { send(Action.NetworkRerouted) }
                    }.cancellable(HomeFeature.CancelID.NetworkPath, cancelInFlight = true),
                    if (next.isConnected) Effect.none() else lastKnownActivityExpiry(next),
                    recovery,
                ),
            )
        }

        Action.SceneEnteredBackground -> {
            // Nothing reads as Offline while nobody is looking; coming back starts the reconnect over.
            val next = state.copy(isInBackground = true).settleRecovery()
            val grace: Effect<Action> = Effect.cancel(HomeFeature.CancelID.ConnectionGrace)
            if (!next.isEventStreamLive) {
                Reduced(next, Effect.merge(grace, Effect.cancel(HomeFeature.CancelID.Events)))
            } else {
                // Hold the live stream briefly so a quick return has already caught up.
                val backgroundTime = dependency(BackgroundTimeClient)
                Reduced(
                    next,
                    Effect.merge(
                        grace,
                        Effect.run<Action> { backgroundTime.hold("Live updates", HomeFeature.liveUpdatesAfterLeaving) }
                            .cancellable(HomeFeature.CancelID.LiveUpdatesAfterLeaving, cancelInFlight = true),
                    ),
                )
            }
        }

        Action.SceneBecameActive -> {
            val silence = dependency(ServerLinkClient).streamSilence(state.server.persistenceID)
            val resyncs = state.resyncsWhenActive(silence)
            val next = state.copy(isInBackground = false)
            if (!resyncs) Reduced(next) else resync(next, visible = false, reason = ServerLinkReset.Foreground)
        }

        is Action.ServerConnected -> {
            // The stream started before any rename since; the name on screen wins.
            val server = action.server.copy(name = state.server.name)
            // Offline no longer holds; the snapshot that follows says connected.
            val status = if (state.connectionStatus == State.ConnectionStatus.disconnected) {
                State.ConnectionStatus.reconnecting
            } else {
                state.connectionStatus
            }
            PerfTimeline.streamConnected()
            Reduced(
                state.copy(
                    server = server, connectionStatus = status, hasConnected = true, isEventStreamLive = true,
                    connectionErrorMessage = null, credentialsRejected = false,
                ).settleRecovery(),
                Effect.cancel(HomeFeature.CancelID.ConnectionGrace),
            )
        }

        is Action.EventStreamFailed -> {
            if (state.isEventStreamLive) PerfTimeline.streamLost()
            goOffline(
                state.copy(
                    connectionErrorMessage = action.error.message,
                    credentialsRejected = action.error.statusCode == HomeFeature.credentialsRejectedStatus,
                    isEventStreamLive = false,
                ),
            )
        }

        Action.ConnectionLost -> {
            val wasConnected = state.isConnected
            if (state.isEventStreamLive) PerfTimeline.streamLost()
            var next = state.copy(isEventStreamLive = false)
            if (wasConnected) {
                val now = dependency(HomeSnapshotDate).now()
                next = next.copy(
                    connectionStatus = State.ConnectionStatus.reconnecting,
                    lastKnownActivityDeadline = now.plusMillis(HomeFeature.lastKnownActivityLifetime.inWholeMilliseconds),
                )
            }
            val expiry = if (wasConnected) lastKnownActivityExpiry(next) else Effect.none()
            if (next.isInBackground) {
                // No reconnect loop in the background: becoming active starts one.
                Reduced(
                    next,
                    Effect.merge(
                        expiry,
                        Effect.cancel(HomeFeature.CancelID.Events),
                        Effect.cancel(HomeFeature.CancelID.LiveUpdatesAfterLeaving),
                    ),
                )
            } else {
                val failed = recoveryFailed(next)
                Reduced(failed.state, Effect.merge(expiry, failed.effect))
            }
        }

        Action.SnapshotFailed -> {
            if (!state.isEventStreamLive) {
                recoveryFailed(state)
            } else {
                val failures = state.snapshotFailures + 1
                val delays = HomeFeature.snapshotRetryDelays
                val delay = delays[minOf(failures, delays.size) - 1]
                val clock = dependency(Clock)
                Reduced(
                    state.copy(snapshotFailures = failures),
                    Effect.run<Action> { send ->
                        clock.sleep(delay.seconds)
                        send(Action.SnapshotRetryRequested)
                    }.cancellable(HomeFeature.CancelID.SnapshotRetry, cancelInFlight = true),
                )
            }
        }

        Action.LastKnownActivityExpired -> Reduced(state.copy(lastKnownActivityDeadline = null))

        Action.NetworkRerouted -> {
            AppLog.stream.info("Network route changed")
            if (!state.isStreaming || state.credentialsRejected) {
                Reduced(state)
            } else if (state.isInBackground) {
                // A stream on the old route is stranded; reconnecting waits for the app to be
                // active again. What runs meanwhile (a call's requests) still gets fresh pools and
                // races the addresses again.
                startConnectionsOver(state.server, ServerLinkReset.Reroute)
                Reduced(
                    state.copy(isEventStreamLive = false),
                    Effect.merge(
                        Effect.cancel(HomeFeature.CancelID.Events),
                        Effect.cancel(HomeFeature.CancelID.LiveUpdatesAfterLeaving),
                    ),
                )
            } else {
                val next = state.copy(isEventStreamLive = false)
                startConnectionsOver(next.server, ServerLinkReset.Reroute)
                Reduced(next, eventStream(next.server))
            }
        }

        Action.RetryConnectionTapped -> {
            val localNetwork = dependency(LocalNetworkPermission)
            if (state.credentialsRejected) {
                Reduced(state)
            } else if (localNetwork.blocks(state.server)) {
                // Android 17 local network access is off: ask again, else open Settings.
                Reduced(state, Effect.run { send -> if (localNetwork.request()) send(Action.RetryConnectionTapped) else localNetwork.openSettings() })
            } else {
                resync(state, visible = true, reason = ServerLinkReset.Retry)
            }
        }

        Action.ReconnectingGraceElapsed -> {
            if (!state.isRecovering || state.connectionStatus != State.ConnectionStatus.reconnecting) {
                Reduced(state)
            } else {
                Reduced(state.copy(showsRecovery = true))
            }
        }

        is Action.OfflineGraceElapsed -> {
            if (!state.isRecovering || state.connectionStatus != State.ConnectionStatus.reconnecting) {
                Reduced(state)
            } else {
                val next = state.copy(offlineGraceElapsed = true)
                if (action.pathDown || next.recoveryFailures >= 2) goOffline(next) else Reduced(next)
            }
        }

        // Transport output consumed by Home's snapshot/session coordination.
        Action.SnapshotRetryRequested, is Action.ServerEvent -> Reduced(state)
    }

    /**
     * Restarts the stream and, unless connected, the reconnect. [visible] skips the quiet grace:
     * the person asked (Try Again) and expects to see it.
     */
    private fun resync(state: State, visible: Boolean, reason: ServerLinkReset): Reduced<State, Action> {
        var next = state.copy(isEventStreamLive = false)
        var recovery: Effect<Action> = Effect.none()
        if (!next.isConnected) {
            next = next.copy(connectionStatus = State.ConnectionStatus.reconnecting).settleRecovery()
            val begun = beginRecovery(next, visible)
            next = begun.state
            recovery = begun.effect
        }
        if (!next.isStreaming) return Reduced(next, Effect.merge(Effect.cancel(HomeFeature.CancelID.LiveUpdatesAfterLeaving), recovery))
        startConnectionsOver(next.server, reason)
        return Reduced(
            next,
            Effect.merge(
                Effect.cancel(HomeFeature.CancelID.LiveUpdatesAfterLeaving),
                recovery,
                eventStream(next.server),
            ),
        )
    }

    /**
     * Fresh pools and the addresses raced again while the action is reduced, so the stream, Home's
     * snapshot and an open screen's resume reads that start after it never go out on the old pool.
     * A reset can be skipped (too soon after another, a short trip away); forgetting the address can't.
     */
    private fun startConnectionsOver(server: OpenCodeServer, reason: ServerLinkReset) {
        val link = dependency(ServerLinkClient)
        link.resetConnections(reason)
        link.invalidate(server, null)
    }

    /**
     * Starts the reconnect's timer unless one is running: quiet until `grace`, Offline once
     * `offlineGrace` has passed with two failed attempts, or `unsatisfiedOfflineGrace` with no
     * network path.
     */
    private fun beginRecovery(state: State, visible: Boolean): Reduced<State, Action> {
        var next = state
        if (visible) next = next.copy(showsRecovery = true)
        if (next.isRecovering) return Reduced(next)
        next = next.copy(isRecovering = true, recoveryFailures = 0, offlineGraceElapsed = false)
        val networkPath = dependency(NetworkPathClient)
        val clock = dependency(Clock)
        val quiet = ServerFlowTunables.grace.seconds
        val pathDown = ServerFlowTunables.unsatisfiedOfflineGrace.seconds
        val offline = ServerFlowTunables.offlineGrace.seconds
        val effect = Effect.run<Action> { send ->
            var elapsed = Duration.ZERO
            if (!visible) {
                clock.sleep(quiet)
                elapsed = quiet
                send(Action.ReconnectingGraceElapsed)
            }
            if (pathDown > elapsed) {
                clock.sleep(pathDown - elapsed)
                elapsed = pathDown
            }
            if (networkPath.isOffline()) {
                send(Action.OfflineGraceElapsed(pathDown = true))
                return@run
            }
            if (offline > elapsed) clock.sleep(offline - elapsed)
            send(Action.OfflineGraceElapsed(pathDown = networkPath.isOffline()))
        }.cancellable(HomeFeature.CancelID.ConnectionGrace, cancelInFlight = true)
        return Reduced(next, effect)
    }

    /** One more attempt failed while reconnecting. */
    private fun recoveryFailed(state: State): Reduced<State, Action> {
        if (state.connectionStatus == State.ConnectionStatus.disconnected || state.isInBackground) return Reduced(state)
        val begun = beginRecovery(state.copy(connectionStatus = State.ConnectionStatus.reconnecting), visible = false)
        val next = begun.state.copy(recoveryFailures = begun.state.recoveryFailures + 1)
        if (!next.offlineGraceElapsed || next.recoveryFailures < 2) return Reduced(next, begun.effect)
        val offline = goOffline(next)
        return Reduced(offline.state, Effect.merge(begun.effect, offline.effect))
    }

    private fun goOffline(state: State): Reduced<State, Action> = Reduced(
        state.copy(connectionStatus = State.ConnectionStatus.disconnected).settleRecovery(),
        Effect.cancel(HomeFeature.CancelID.ConnectionGrace),
    )

    /**
     * The event stream, reconnecting on the stream schedule (sooner when the route changes or the
     * server answers a request), or on the overflow schedule at the same address when the app fell
     * behind. A 401 stops it for good.
     */
    fun eventStream(server: OpenCodeServer): Effect<Action> {
        val events = dependency(OpenCodeClient).events
        val networkPath = dependency(NetworkPathClient)
        val link = dependency(ServerLinkClient)
        val clock = dependency(Clock)
        return Effect.run<Action> { send ->
            val schedule = RetrySchedule(RetryPolicy.stream, clock, networkPath.retryEnvironment)
            val overflowSchedule = RetrySchedule(RetryPolicy.streamOverflow, clock, networkPath.retryEnvironment)
            // Every fresh start races the addresses: the one that answered last may be on a
            // network the phone just left. An overflow reconnects to the same address.
            var races = server.candidateURLs.size > 1
            var target = link.resolve(server)
            // Read only after an overflow, so a clean stream never reads the clock.
            var overflowReconnectedAt: Duration? = null
            val operationID = UUID.randomUUID().toString().uppercase()
            val log = AppLog.stream
            while (currentCoroutineContext().isActive) {
                if (races) target = link.resolve(server)
                var connected = false
                var overflowed = false
                var raceFailed = false
                if (races) {
                    try {
                        target = link.probe(server)
                    } catch (error: OpenCodeClientError) {
                        if (!currentCoroutineContext().isActive) return@run
                        if (error.statusCode == 401) {
                            log.error("Stopped: the server rejected the credentials")
                            send(Action.EventStreamFailed(error))
                            return@run
                        }
                        log.notice { "No address answered: ${error.logSummary}" }
                        raceFailed = true
                    }
                }
                if (!raceFailed) {
                    val opened = clock.now()
                    val candidate = target
                    try {
                        NetworkRetry.streamOperationID.withValue(operationID) {
                            events(candidate).collect { event ->
                                if (event == OpenCodeServerEvent.Connected) {
                                    connected = true
                                    schedule.reset()
                                    link.adopt(candidate)
                                    log.info { "Connected over ${ConnectionRoute.of(candidate.url).rawValue}" }
                                    send(Action.ServerConnected(candidate))
                                } else {
                                    send(Action.ServerEvent(event))
                                }
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        if (!currentCoroutineContext().isActive) return@run
                        overflowed = error is EventStreamOverflow
                        val lasted = (clock.now() - opened).logSummary
                        val outcome = if (connected) "dropped after $lasted" else "failed"
                        if (error is OpenCodeClientError) {
                            val reason = error.statusCode?.let { "HTTP $it" } ?: error.transport?.rawValue ?: "stream ended"
                            log.notice { "Stream $outcome: $reason" }
                        } else {
                            log.notice { "Stream $outcome: ${logDescription(error)}" }
                        }
                        if (error is OpenCodeClientError && error.statusCode == 401) {
                            log.error("Stopped: the server rejected the credentials")
                            send(Action.EventStreamFailed(error))
                            return@run
                        }
                    }
                }
                if (!currentCoroutineContext().isActive) return@run
                // A connection that ends without local data loss can clear the overload history,
                // as can one that kept up for a quiet stretch before it fell behind: that overflow
                // is a new burst, not the last one still draining. Merely receiving `.connected`
                // cannot.
                val sinceOverflowReconnect = overflowReconnectedAt?.let { clock.now() - it } ?: Duration.ZERO
                if (connected && (!overflowed || sinceOverflowReconnect >= overflowQuietStretch)) overflowSchedule.reset()
                // Requests meanwhile race again rather than follow the stream's address into the
                // same failure (an overflow is local: keep it).
                if (!overflowed && !raceFailed) link.invalidate(server, target.url)
                send(Action.ConnectionLost)
                if (overflowed) {
                    races = false
                    val pause = overflowSchedule.next(RetryVerdict.Transient(retryAfter = null)) ?: return@run
                    log.notice { "Reconnecting after overflow: ${pause.kind.logSummary}" }
                    if (!pause.run()) return@run
                    overflowReconnectedAt = clock.now()
                    continue
                }
                races = server.candidateURLs.size > 1
                val pause = schedule.next(RetryVerdict.Transient(retryAfter = null)) ?: return@run
                log.notice { "Reconnecting: ${pause.kind.logSummary}, sooner if the route changes or the server answers" }
                val cued = AtomicBoolean(false)
                val resumed = pause.run(wake = {
                    networkPath.nextReconnectCue(server.persistenceID)
                    if (currentCoroutineContext().isActive) cued.set(true)
                })
                if (!resumed) return@run
                // The route changed, the network came back, or the server answered: whatever kept
                // it away is gone, so the backoff starts over.
                if (cued.get() || pause.kind.waitsForNetwork) schedule.reset()
            }
        }.cancellable(HomeFeature.CancelID.Events, cancelInFlight = true)
    }

    /** Ends last-known running state at its deadline. */
    fun lastKnownActivityExpiry(state: State): Effect<Action> {
        val deadline = state.lastKnownActivityDeadline ?: return Effect.cancel(HomeFeature.CancelID.LastKnownActivity)
        val remaining = JavaDuration.between(dependency(HomeSnapshotDate).now(), deadline).toMillis()
        val clock = dependency(Clock)
        return Effect.run<Action> { send ->
            if (remaining > 0) clock.sleep(remaining.milliseconds)
            send(Action.LastKnownActivityExpired)
        }.cancellable(HomeFeature.CancelID.LastKnownActivity, cancelInFlight = true)
    }

    companion object {
        /** How long a connection keeps up before its overflow restarts the overflow backoff from its first, shortest wait. */
        val overflowQuietStretch: Duration = 30.seconds

        /** A stream silent this long has missed its heartbeats (every 15 s). */
        val foregroundSilence: Duration = 20.seconds

        /**
         * An exception's type and its cause's, never its message (which can carry the URL). Same
         * rule as `:server`'s internal helper.
         */
        private fun logDescription(error: Throwable): String {
            var description = error::class.java.simpleName
            error.cause?.let { description += " (${it::class.java.simpleName})" }
            return description
        }
    }
}
