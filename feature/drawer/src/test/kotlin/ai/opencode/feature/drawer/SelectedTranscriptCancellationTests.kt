package ai.opencode.feature.drawer

import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.session.*
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Collections
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tapping an uncached session reads its transcript (`CancelID.SelectedTranscript`), riding out
 * drops for up to the request policy's window. Leaving it for anywhere else ends that read, and a
 * late answer never lands on where the reader went instead. Foreground reads step background reads
 * (prefetch, cached refreshes, active ancestry) aside and drain them once they settle.
 *
 * Android's `HomeFeature.State` mirrors them as plain fields (`sessionReadDates`,
 * `lastViewedSessionIDs`, `initializedInboxServerIDs`), so expectations set those, and keys Home
 * keeps out of state (`cachedHomeSnapshots`, `cachedTranscriptSnapshots`) aren't part of the
 * comparison.
 */
class SelectedTranscriptCancellationTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, updated: Long, idle: Long = 1_000, viewed: Long = 1_000) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = idle, viewedMilliseconds = viewed,
    )

    private fun HomeFeature.State.connected(status: HomeConnectionFeature.State.ConnectionStatus = HomeConnectionFeature.State.ConnectionStatus.connected) =
        copy(connection = connection.copy(connectionStatus = status))

    private fun text(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)

    /** `slow` is uncached and read; `other` is unread, so Catch Up has a card; `home` is on screen. */
    private fun state(): HomeFeature.State {
        val sessions = listOf(
            session("other", 30, idle = 2_000),
            session("slow", 20),
            session("home", 10),
        )
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), sessions, emptySet())).connected()
        state = state.openingDetail(sessions[2], cachedItems = emptyList())
        return state.copy(transcriptCache = state.transcriptCache + ("other" to listOf(text("o1", "Other"))))
    }

    /** A read that waits for its release (or its cancellation, reported to [onCancel]). */
    private class Gate {
        @Volatile private var waiting: CompletableDeferred<Unit>? = null

        suspend fun wait(onCancel: () -> Unit = {}) {
            val deferred = CompletableDeferred<Unit>()
            waiting = deferred
            try {
                deferred.await()
            } catch (cancellation: CancellationException) {
                onCancel()
                throw cancellation
            }
        }

        fun release() {
            waiting?.complete(Unit)
        }
    }

    private class Counter {
        @Volatile var value = 0
        @Synchronized fun increment() { value += 1 }
    }

    private suspend fun tapUncached(session: OpenCodeSession, store: TestStore<HomeFeature.State, Action>) {
        store.send(Action.SessionTapped(session.id)) {
            val transition = it.detailGeneration
            store.withDependencies {
                it.copy(lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to session.id))
                    .markingReadLocally(session)
            }.copy(selectedTranscriptFetch = HomeFeature.SelectedTranscriptFetch(session.id, transition))
        }
        store.receive(Action.ViewAcknowledged(sessionID = session.id, idle = session.idleMilliseconds ?: 1_000))
        store.receive(Action.SessionNavigationReady(session.id)) {
            val opened = store.withDependencies { it.openingDetail(session, externalTranscript = true) }
            opened.copy(selectedTranscriptFetch = opened.selectedTranscriptFetch?.copy(transition = opened.detailGeneration))
        }
    }

    private suspend fun receiveUncachedTranscript(
        sessionID: String,
        items: List<OpenCodeTranscriptItem>,
        store: TestStore<HomeFeature.State, Action>,
    ) {
        store.receive(Action.SelectedTranscriptResponse(sessionID, Result.success(items))) {
            store.withDependencies {
                it.copy(selectedTranscriptFetch = null).publishingTranscript(sessionID, items).persistingTranscriptCache(including = sessionID)
            }
        }
        store.receive(Action.detail(SessionFeature.Action.TranscriptResponse(sessionID, Result.success(items)))) {
            it.updatingDetail { d ->
                d.copy(initialTranscriptRequestIsExternal = false, initialTranscriptIsFresh = true, isLoading = false, items = items)
            }
        }
        store.receive(Action.detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID, items))) {
            store.withDependencies { it.settingSeenWatermark(SessionSeenWatermark(itemID = items.lastOrNull()?.id, viewedMilliseconds = 1_000), sessionID) }
        }
    }

    private suspend fun receiveDrainedRefresh(
        id: String,
        remaining: MutableSet<String>,
        store: TestStore<HomeFeature.State, Action>,
    ) {
        val freshItems = listOf(text("$id-1", "Fresh $id"))
        remaining.remove(id)
        val expectedRemaining = remaining.toSet()
        store.receive(Action.CachedTranscriptRefreshed(sessionID = id, items = freshItems)) {
            store.withDependencies {
                it.copy(refreshingTranscripts = expectedRemaining, transcriptReadAt = it.transcriptReadAt + (id to Instant.ofEpochSecond(500)))
                    .notingUnopenedCopy(id)
                    .publishingTranscript(id, freshItems)
                    .persistingTranscriptCache(including = id)
            }
        }
    }

    private fun CoroutineScope.store(started: Counter, cancelled: Counter): TestStore<HomeFeature.State, Action> {
        val store = TestStore(state(), HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, sessionID, _ ->
                    if (sessionID != "slow") return@copy emptyList()
                    started.increment()
                    try {
                        delay(60.seconds)
                    } catch (cancellation: CancellationException) {
                        cancelled.increment()
                        throw cancellation
                    }
                    listOf(text("late", "Late"))
                },
                viewSession = { _, _, _ -> },
                getSession = { _, id -> session(id, 20) },
                homeAttention = { _, _, _ -> emptySet() },
                deleteSession = { _, _ -> delay(60.seconds) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
            )
        }
        store.exhaustive = false
        return store
    }

    /**
     * Taps `slow`, then leaves it by [route]; the read must stop, and its answer (were it to
     * arrive) must not touch the destination.
     */
    private suspend fun CoroutineScope.leaving(
        route: suspend (TestStore<HomeFeature.State, Action>) -> Unit,
        destination: (HomeFeature.State) -> Unit,
    ) {
        val started = Counter()
        val cancelled = Counter()
        val store = store(started, cancelled)
        store.send(Action.SessionTapped("slow"))
        store.receive({ it is Action.SessionNavigationReady }, "sessionNavigationReady")
        assertEquals("slow", store.state.sessionDetail?.session?.id)
        assertTrue(eventually { started.value == 1 })

        route(store)
        assertNull(store.state.selectedTranscriptFetch)
        assertTrue("Leaving must cancel the read and its retries", eventually { cancelled.value == 1 })

        // A late answer for `slow` never replaces where the reader went.
        val before = store.state.sessionDetail
        store.send(Action.SelectedTranscriptResponse(sessionID = "slow", result = Result.success(listOf(text("late", "Late")))))
        assertEquals(before?.navigationID, store.state.sessionDetail?.navigationID)
        assertNotEquals(true, store.state.sessionDetail?.items?.any { it.id == "late" })
        assertNull(store.state.transcriptCache["slow"])
        destination(store.state)
        store.skipInFlightEffects()
    }

    @Test fun newSessionEndsTheRead() = runTest {
        leaving({ it.send(Action.NewSessionTapped) }) { assertEquals(true, it.sessionDetail?.isDraft) }
    }

    @Test fun aVoiceDraftEndsTheRead() = runTest {
        leaving({ it.send(Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenDraft(null)))) }) {
            assertEquals(true, it.sessionDetail?.isDraft)
        }
    }

    @Test fun catchUpEndsTheRead() = runTest {
        leaving({ it.send(Action.catchUpTapped) }) {
            assertNotNull(it.catchUp)
            assertEquals("other", it.sessionDetail?.session?.id)
        }
    }

    @Test fun deletingTheSessionEndsTheRead() = runTest {
        leaving({ store ->
            store.send(Action.DeleteSessionTapped("slow"))
            store.send(Action.DeleteSessionConfirmed)
        }) {
            assertEquals(true, it.sessionDetail?.isDraft)
            assertFalse(it.sessions.any { s -> s.id == "slow" })
        }
    }

    @Test fun anotherSessionEndsTheRead() = runTest {
        leaving({ it.send(Action.SessionTapped("other")) }) { assertEquals("other", it.sessionDetail?.session?.id) }
    }

    @Test fun theReadStillLandsOnTheSessionItOpened() = runTest {
        var initial = state()
        initial = initial.openingDetail(initial.sessions[2], cachedItems = emptyList())
        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> listOf(text("u1", "Loaded")) },
                viewSession = { _, _, _ -> },
                getSession = { _, id -> session(id, 20) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false
        store.send(Action.SessionTapped("slow"))
        store.receive({ it is Action.SessionNavigationReady }, "sessionNavigationReady")
        store.receive({ it is Action.SelectedTranscriptResponse }, "selectedTranscriptResponse")
        assertEquals(listOf("u1"), store.state.transcriptCache["slow"]?.map { it.id })
        assertNull(store.state.selectedTranscriptFetch)
        store.skipInFlightEffects()
    }

    /**
     * Foreground transcript starts (`sessionTapped`, Catch Up entry/advance, subagent push, and
     * `.open`/`.resume`/`.reconnect` refreshes) cancel in-flight prefetch, cached refreshes, and
     * active ancestry without scheduling a resume, and suppress natural background triggers while
     * `isInitialTranscriptInFlight` is true.
     */
    @Test fun foregroundStartCancelsPrefetchRefreshAndAncestryWithoutAutoResume() = runTest {
        val homeSession = session("home", 40)
        val otherSession = session("other", 30, idle = 2_000, viewed = 1_000)
        val uncachedSession = session("uncached", 20, idle = 2_000, viewed = 1_000)
        val childSession = OpenCodeSession(
            id = "child", serverTitle = "Child", projectID = "p", directory = "/project",
            updatedAt = Instant.ofEpochSecond(25), parentID = "home",
        )
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(homeSession, otherSession, uncachedSession), setOf("ses_subagent"))
        var initial = HomeFeature.State(server, snapshot).connected()
        initial = initial.copy(
            sessionDetail = null,
            transcriptCache = initial.transcriptCache + ("home" to listOf(text("h0", "Home"))) + ("other" to listOf(text("o0", "Other"))),
        )

        val refreshedItems = listOf(text("h0", "Home"), text("h1", "Refreshed"))
        val otherRefreshed = listOf(text("o1", "Other refreshed"))
        val uncachedItems = listOf(text("u1", "Uncached loaded"))
        val transcriptGates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
        val startedTranscripts = Collections.synchronizedList(mutableListOf<String>())
        val cancelledTranscripts = Collections.synchronizedList(mutableListOf<String>())
        val startedAncestry = Counter()
        val cancelledAncestry = Counter()
        val ancestryGate = Gate()

        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id ->
                    when (id) {
                        "other" -> otherSession
                        "uncached" -> uncachedSession
                        "child" -> childSession
                        else -> homeSession
                    }
                },
                transcript = { _, sessionID, _ ->
                    startedTranscripts += sessionID
                    val gate = CompletableDeferred<Unit>()
                    transcriptGates[sessionID] = gate
                    try {
                        gate.await()
                    } catch (cancellation: CancellationException) {
                        cancelledTranscripts += sessionID
                        throw cancellation
                    }
                    when (sessionID) {
                        "other" -> otherRefreshed
                        "uncached" -> uncachedItems
                        else -> refreshedItems
                    }
                },
                activeSessionParents = { _, _, _ ->
                    startedAncestry.increment()
                    ancestryGate.wait { cancelledAncestry.increment() }
                    mapOf("ses_subagent" to "home")
                },
                viewSession = { _, _, _ -> },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }

        // 1. Start prefetch ("uncached"), active ancestry ("ses_subagent"), and a cached drawer
        // refresh ("other") in parallel.
        store.send(Action.SnapshotRefreshed(snapshot)) {
            it.copy(
                noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                sessionReadDates = it.sessionReadDates +
                    (it.sessionKey("home") to homeSession.updatedAt) +
                    (it.sessionKey("other") to otherSession.updatedAt) +
                    (it.sessionKey("uncached") to uncachedSession.updatedAt),
                initializedInboxServerIDs = setOf(server.persistenceID),
            )
        }
        store.receive(Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }
        store.send(Action.DrawerRowPressed("other")) { it.copy(refreshingTranscripts = setOf("other"), fetchingTranscripts = setOf("other")) }
        assertTrue(eventually { startedTranscripts.toSet() == setOf("uncached", "other") && startedAncestry.value == 1 })

        // 2. Tapping cached "home" cancels in-flight prefetch, cached refresh, and active ancestry
        // immediately, recording "other" in `dirtyCachedTranscriptIDs`.
        store.send(Action.SessionTapped("home")) {
            store.withDependencies {
                it.copy(
                    refreshingTranscripts = emptySet(),
                    fetchingTranscripts = emptySet(),
                    dirtyCachedTranscriptIDs = listOf("other"),
                    lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to "home"),
                ).markingReadLocally(homeSession).let { s -> s.openingDetail(homeSession, cachedItems = listOf(text("h0", "Home"))) }
            }
        }
        store.receive(Action.ViewAcknowledged(sessionID = "home", idle = 1_000))
        assertTrue(eventually { cancelledTranscripts.toSet() == setOf("uncached", "other") && cancelledAncestry.value == 1 })

        // 3. Start another cached refresh ("other"), then start a foreground `.reconnect` refresh on
        // "home": it cancels "other" and sets `isInitialTranscriptInFlight`.
        store.send(Action.DrawerRowPressed("other")) {
            it.copy(dirtyCachedTranscriptIDs = emptyList(), refreshingTranscripts = setOf("other"), fetchingTranscripts = setOf("other"))
        }
        assertTrue(eventually { startedTranscripts.count { it == "other" } == 2 })
        store.send(Action.detail(SessionFeature.Action.RefreshRequested(RefreshReason.Reconnect))) {
            it.copy(refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet(), dirtyCachedTranscriptIDs = listOf("other")).updatingDetail { d ->
                d.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Reconnect))
            }
        }
        assertTrue(store.state.isInitialTranscriptInFlight)
        assertTrue(eventually { cancelledTranscripts.count { it == "other" } == 2 })

        // 4. While `isInitialTranscriptInFlight` is true, natural background triggers
        // (`.contentBatch` and `.sessionDrawerArrived(isOpen: true)`) do not start background reads.
        store.send(
            Action.ServerEvent(
                OpenCodeServerEvent.ContentBatch(
                    sessionID = "other",
                    events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "m_other", ordinal = 0, delta = "New")),
                ),
            ),
        )
        store.send(Action.SessionDrawerArrived(isOpen = true)) { it.copy(isDrawerOpen = true) }
        assertTrue(store.state.refreshingTranscripts.isEmpty())
        assertEquals(listOf("other"), store.state.dirtyCachedTranscriptIDs)
        assertEquals(listOf("uncached", "other", "other", "home"), startedTranscripts.toList())

        // 5. Completing the foreground refresh clears `isInitialTranscriptInFlight`, re-triggers
        // `resolveActiveAncestry` once for unresolved "ses_subagent", and drains
        // `dirtyCachedTranscriptIDs` ("other") once before `acceptedTranscriptUpdated` runs
        // `prefetchTranscripts` for "uncached".
        transcriptGates["home"]?.complete(Unit)
        store.receive({ it is Action.SessionDetail && (it.action as? ai.opencode.core.store.PresentationAction.Presented)?.action is SessionFeature.Action.RefreshResponse }, "refreshResponse") {
            it.copy(dirtyCachedTranscriptIDs = emptyList(), refreshingTranscripts = setOf("other")).updatingDetail { d ->
                d.copy(refresh = null, initialTranscriptIsFresh = true, items = refreshedItems)
            }
        }
        assertFalse(store.state.isInitialTranscriptInFlight)
        store.receive(Action.detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = "home", items = refreshedItems))) {
            it.copy(transcriptCache = it.transcriptCache + ("home" to refreshedItems))
                .settingSeenWatermark(SessionSeenWatermark(itemID = "h0", viewedMilliseconds = 1_000), "home")
        }
        assertTrue(
            eventually {
                startedTranscripts.count { it == "uncached" } == 2 &&
                    startedTranscripts.count { it == "other" } == 3 &&
                    startedAncestry.value == 2
            },
        )

        // 6. Catch Up entry cancels the in-flight prefetch, cached refresh ("other"), and active
        // ancestry; subagent push cancels a newly started cached refresh.
        store.send(Action.catchUpTapped) {
            store.withDependencies {
                it.copy(isDrawerOpen = false, refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet())
                    .withCatchUp(
                        CatchUpFeature.Stack(
                            queue = listOf("other", "uncached"),
                            returnSessionID = "home",
                            returnComposer = ai.opencode.feature.composer.DraftState(),
                        ),
                    )
                    .openingCatchUpCard().first
            }
        }
        assertTrue(
            eventually {
                cancelledTranscripts.count { it == "uncached" } == 2 &&
                    cancelledTranscripts.count { it == "other" } == 3 &&
                    cancelledAncestry.value == 2
            },
        )
        store.send(Action.catchUpExitTapped) {
            store.withDependencies {
                var s = it.returningFromCatchUp()
                if (s.showsDrawerLabel) s = s.copy(isDrawerOpen = true)
                s
            }
        }

        store.send(Action.DrawerRowPressed("other")) { it.copy(refreshingTranscripts = setOf("other"), fetchingTranscripts = setOf("other")) }
        assertTrue(eventually { startedTranscripts.count { it == "other" } == 4 })
        store.send(Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenSubagent(childSession)))) {
            store.withDependencies {
                it.copy(refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet(), dirtyCachedTranscriptIDs = listOf("other")).pushingSubagent(childSession).syncingTranscriptCover().syncingPollingPause().state
            }
        }
        store.receive(Action.detail(SessionFeature.Action.PollingPauseChanged(true)))
        assertTrue(eventually { cancelledTranscripts.count { it == "other" } == 4 })
        store.skipInFlightEffects()
    }

    /**
     * `isInitialTranscriptInFlight` clears on every completion, failure, cancellation,
     * `visibilityChanged(false)`, and deletion path so natural background triggers are never
     * permanently blocked.
     */
    @Test fun inFlightFlagClearsAcrossSuccessFailureCancelVisibilityAndDeletion() = runTest {
        val first = session("first", 30)
        val second = session("second", 20)
        val cached = session("cached", 10)
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(first, second, cached), emptySet())
        var initial = HomeFeature.State(server, snapshot).connected()
        initial = initial.openingDetail(cached, cachedItems = listOf(text("c0", "Cached")))
        initial = initial.copy(
            sessionDetail = initial.sessionDetail?.copy(isVisible = true),
            transcriptCache = initial.transcriptCache + ("cached" to listOf(text("c0", "Cached"))),
        )

        val gate = Gate()
        var gateCached = false
        var shouldFail = false
        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> if (id == "first") first else if (id == "second") second else cached },
                transcript = { _, sessionID, _ ->
                    if (sessionID == "first" || sessionID == "second" || (sessionID == "cached" && gateCached)) gate.wait()
                    if (shouldFail) throw OpenCodeClientError("Failed")
                    listOf(text("$sessionID-1", sessionID))
                },
                viewSession = { _, _, _ -> },
                homeAttention = { _, _, _ -> emptySet() },
                deleteSession = { _, _ -> },
                sessionFamily = { _, _ -> awaitCancellation() },
                runningShells = { _, _ -> awaitCancellation() },
                runningJobs = { _, _ -> awaitCancellation() },
            )
        }

        // 1. Uncached tap -> failure clears `isInitialTranscriptInFlight`.
        shouldFail = true
        tapUncached(first, store)
        assertTrue(store.state.isInitialTranscriptInFlight)
        store.send(Action.PlaygroundReplaceState(store.state.updatingDetail { it.copy(hasRenderedFirstContentFrame = true) })) {
            it.updatingDetail { d -> d.copy(hasRenderedFirstContentFrame = true) }
        }
        gate.release()
        store.receive(Action.SelectedTranscriptResponse("first", Result.failure(OpenCodeClientError("Failed")))) {
            it.copy(selectedTranscriptFetch = null)
        }
        store.receive(Action.detail(SessionFeature.Action.TranscriptResponse("first", Result.failure(OpenCodeClientError("Failed"))))) {
            it.updatingDetail { d -> d.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = "Failed") }
        }
        store.receive(Action.detail(SessionFeature.Action.FirstContentFrameRendered))
        assertFalse(store.state.isInitialTranscriptInFlight)

        // 2. Uncached tap -> `visibilityChanged(false)` clears `isInitialTranscriptInFlight` and
        // cancels `selectedTranscriptFetch`.
        shouldFail = false
        tapUncached(second, store)
        assertTrue(store.state.isInitialTranscriptInFlight)
        store.send(Action.detail(SessionFeature.Action.VisibilityChanged(false))) {
            it.copy(selectedTranscriptFetch = null).updatingDetail { d ->
                d.copy(isVisible = false, initialTranscriptRequestIsExternal = false, isLoading = false)
            }
        }
        assertNull(store.state.selectedTranscriptFetch)
        assertFalse(store.state.isInitialTranscriptInFlight)

        // 3. Uncached tap -> server deletion while `selectedTranscriptFetch` is in flight clears
        // `isInitialTranscriptInFlight`.
        tapUncached(first, store)
        assertTrue(store.state.isInitialTranscriptInFlight)
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = "first"))) {
            store.withDependencies {
                val purged = it.copy(selectedTranscriptFetch = null).purgingSession("first")
                purged.copy(recentlyDeletedIDs = purged.recentlyDeletedIDs - "first")
                    .openingDraft(reason = "the session went away on the server")
                    .persistingSnapshot(Instant.EPOCH)
            }
        }
        assertNull(store.state.selectedTranscriptFetch)
        assertFalse(store.state.isInitialTranscriptInFlight)

        // 4. `SessionFeature` `.open` refresh -> failure clears `isInitialTranscriptInFlight`, and
        // `.resume` refresh -> `visibilityChanged(false)` clears it.
        store.send(Action.SessionTapped("cached")) {
            store.withDependencies {
                it.copy(lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to "cached"))
                    .markingReadLocally(cached)
                    .openingDetail(cached, cachedItems = listOf(text("c0", "Cached")))
            }
        }
        store.receive(Action.ViewAcknowledged(sessionID = "cached", idle = 1_000))
        shouldFail = true
        store.send(Action.detail(SessionFeature.Action.RefreshRequested(RefreshReason.Open))) {
            it.updatingDetail { d -> d.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open)) }
        }
        assertTrue(store.state.isInitialTranscriptInFlight)
        store.receive({ it.isDetailAction<SessionFeature.Action.RefreshResponse>() }, "refreshResponse") {
            it.updatingDetail { d -> d.copy(refresh = null, refreshFailed = true) }
        }
        assertFalse(store.state.isInitialTranscriptInFlight)

        gateCached = true
        store.send(Action.detail(SessionFeature.Action.VisibilityChanged(true))) {
            it.updatingDetail { d -> d.copy(isVisible = true) }
        }
        store.send(Action.detail(SessionFeature.Action.RefreshRequested(RefreshReason.Resume))) {
            it.updatingDetail { d ->
                d.copy(
                    nextRefreshID = 2,
                    refresh = SessionFeature.State.Refresh(id = 2, reason = RefreshReason.Resume),
                    resync = SessionFeature.State.ResyncState(refreshID = 2, isShown = false, responseReceived = false, minimumElapsed = false),
                    familyRead = CoalescedRead(isInFlight = true),
                )
            }
        }
        assertTrue(store.state.isInitialTranscriptInFlight)
        // Android's `ImmediateClock` returns at once: the resync cue shows and holds its minimum
        // here.
        store.receive(Action.detail(SessionFeature.Action.ResyncDelayElapsed(refreshID = 2))) {
            it.updatingDetail { d -> d.copy(resync = d.resync?.copy(isShown = true)) }
        }
        store.receive(Action.detail(SessionFeature.Action.ResyncMinimumElapsed)) {
            it.updatingDetail { d -> d.copy(resync = d.resync?.copy(minimumElapsed = true)) }
        }
        assertTrue(store.state.isInitialTranscriptInFlight)
        store.send(Action.detail(SessionFeature.Action.VisibilityChanged(false))) {
            it.updatingDetail { d -> d.copy(isVisible = false, refresh = null, resync = null, familyRead = CoalescedRead()) }
        }
        assertFalse(store.state.isInitialTranscriptInFlight)

        // 5. After clearing, a natural drawer trigger starts a background refresh normally.
        gateCached = false
        shouldFail = false
        tapUncached(second, store)
        gate.release()
        val secondItems = listOf(text("second-1", "second"))
        receiveUncachedTranscript("second", secondItems, store)
        assertFalse(store.state.isInitialTranscriptInFlight)
        val cachedItems = listOf(text("cached-1", "cached"))
        store.send(Action.DrawerRowPressed("cached")) { it.copy(refreshingTranscripts = setOf("cached"), fetchingTranscripts = setOf("cached")) }
        store.receive(Action.CachedTranscriptRefreshed(sessionID = "cached", items = cachedItems)) {
            store.withDependencies {
                it.copy(refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet(), transcriptReadAt = it.transcriptReadAt + ("cached" to Instant.ofEpochSecond(100)))
                    .notingUnopenedCopy("cached")
                    .publishingTranscript("cached", cachedItems)
                    .persistingTranscriptCache(including = "cached")
            }
        }
        assertEquals(cachedItems, store.state.transcriptCache["cached"])
        store.skipInFlightEffects()
    }

    /**
     * `snapshotRefreshed` publishes roots and `activeSessionIDs` immediately, then resolves unknown
     * active ancestry asynchronously and retries transient failures under `RetryPolicy.request`.
     */
    @Test fun snapshotPublishesBeforeAncestryAndRetriesTransientAncestryFailures() = runTest {
        val root = session("root", 20)
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(root), setOf("ses_subagent"))
        var initial = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), listOf(root), emptySet())).connected()
        initial = initial.copy(sessionDetail = null, transcriptCache = initial.transcriptCache + ("root" to listOf(text("r0", "Root"))))

        val clock = TestClock()
        var attempts = 0
        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                homeAttention = { _, _, _ -> emptySet() },
                activeSessionParents = { _, activeIDs, roots ->
                    assertEquals(setOf("ses_subagent"), activeIDs)
                    assertEquals(listOf("root"), roots)
                    attempts += 1
                    if (attempts == 1) throw OpenCodeClientError("Temporary failure", statusCode = 503)
                    mapOf("ses_subagent" to "root")
                },
            )
        }

        withRetryPolicy(RetryPolicy.request) {
            store.send(Action.SnapshotRefreshed(snapshot)) {
                it.copy(
                    noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                    activeSessionIDs = setOf("ses_subagent"),
                    sessionReadDates = it.sessionReadDates + (it.sessionKey("root") to root.updatedAt),
                    initializedInboxServerIDs = setOf(server.persistenceID),
                )
            }
            assertEquals(
                "Root indicator waits for async ancestry while snapshot is already published",
                HomeFeature.State.Indicator.none,
                store.state.indicator(root),
            )
            clock.advance(150.milliseconds)
            store.receive(Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }
            clock.advance(2.seconds)
            store.receive(Action.ActiveAncestryResolved(mapOf("ses_subagent" to "root"))) {
                it.copy(activeSessionParents = mapOf("ses_subagent" to "root"))
            }
            clock.advance(150.milliseconds)
            store.receive(Action.AttentionRefreshed(emptySet()))
            assertEquals(2, attempts)
            assertEquals(HomeFeature.State.Indicator.running, store.state.indicator(root))
        }
        store.finish()
    }

    /**
     * When an in-flight `resolveActiveAncestry` discovery is cancelled by a foreground transcript
     * load, it is called once when the foreground load ends (cancellation, removal, failure, or
     * success).
     */
    @Test fun activeAncestryRetriggersOnceWhenForegroundLoadingEnds() = runTest {
        val root = session("root", 30)
        val uncached = session("uncached", 20)
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(root, uncached), setOf("ses_subagent"))
        var initial = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), listOf(root, uncached), emptySet())).connected()
        initial = initial.copy(sessionDetail = null, transcriptCache = initial.transcriptCache + ("root" to listOf(text("r0", "Root"))))

        val transcriptGate = Gate()
        val ancestryGate = Gate()
        val ancestryStarts = Counter()
        val ancestryCancels = Counter()

        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                homeAttention = { _, _, _ -> emptySet() },
                viewSession = { _, _, _ -> },
                transcript = { _, sessionID, _ ->
                    if (sessionID == "uncached") transcriptGate.wait()
                    listOf(text("$sessionID-1", sessionID))
                },
                activeSessionParents = { _, activeIDs, roots ->
                    assertEquals(setOf("ses_subagent"), activeIDs)
                    assertEquals(setOf("root", "uncached"), roots.toSet())
                    ancestryStarts.increment()
                    ancestryGate.wait { ancestryCancels.increment() }
                    mapOf("ses_subagent" to "root")
                },
            )
        }

        // 1. Snapshot starts active ancestry discovery (#1) and prefetch for "uncached" (which
        // suspends on `transcriptGate`).
        store.send(Action.SnapshotRefreshed(snapshot)) {
            it.copy(
                noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                activeSessionIDs = setOf("ses_subagent"),
                sessionReadDates = it.sessionReadDates +
                    (it.sessionKey("root") to root.updatedAt) +
                    (it.sessionKey("uncached") to uncached.updatedAt),
                initializedInboxServerIDs = setOf(server.persistenceID),
            )
        }
        store.receive(Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }
        assertTrue(eventually { ancestryStarts.value == 1 })

        // 2. Tap "uncached": cancels in-flight prefetch and ancestry discovery (#1).
        tapUncached(uncached, store)
        assertTrue(eventually { ancestryCancels.value == 1 })

        // 3. Completing the foreground load re-triggers `resolveActiveAncestry` exactly once (#2)
        // and resolves "ses_subagent" -> "root".
        transcriptGate.release()
        val items = listOf(text("uncached-1", "uncached"))
        receiveUncachedTranscript("uncached", items, store)
        assertTrue(eventually { ancestryStarts.value == 2 })
        assertEquals(
            "acceptedTranscriptUpdated must not cancel and restart the settled ancestry read",
            1,
            ancestryCancels.value,
        )

        ancestryGate.release()
        store.receive(Action.ActiveAncestryResolved(mapOf("ses_subagent" to "root"))) {
            store.withDependencies { it.copy(activeSessionParents = mapOf("ses_subagent" to "root")).persistingSnapshot(Instant.EPOCH) }
        }
        assertEquals(HomeFeature.State.Indicator.running, store.state.indicator(root))
        store.skipInFlightEffects()
    }

    /**
     * Cancelled and skipped cached session refreshes are recorded in `dirtyCachedTranscriptIDs`
     * (bounded to 16, dropping oldest), drained once with 500 ms staggered pacing when foreground
     * loading settles, and cleared immediately.
     */
    @Test fun cancelledAndSkippedCachedRefreshesDrainOnceAfterSettleAndStayBounded() = runTest {
        val home = session("home", 100)
        val cachedSessions = (1..18).map { index -> session("s$index", 90L - index) }
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(home) + cachedSessions, emptySet())
        var initial = HomeFeature.State(server, snapshot).connected()
        initial = initial.openingDetail(home, cachedItems = listOf(text("h0", "Home")))
        initial = initial.copy(transcriptCache = initial.transcriptCache + ("home" to listOf(text("h0", "Home"))))
        for (s in cachedSessions) {
            initial = initial.copy(transcriptCache = initial.transcriptCache + (s.id to listOf(text("${s.id}-0", "Old ${s.id}"))))
        }

        val clock = TestClock()
        val homeGate = Gate()
        val refreshedOrder = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(500))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> home },
                transcript = { _, sessionID, _ ->
                    if (sessionID == "home") {
                        homeGate.wait()
                        return@copy listOf(text("h0", "Home"))
                    }
                    refreshedOrder += sessionID
                    listOf(text("$sessionID-1", "Fresh $sessionID"))
                },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.withDependencies {
            initial.persistingTranscriptCache(including = "home")
            for (s in cachedSessions) initial.persistingTranscriptCache(including = s.id)
        }

        // 1. Start a cached refresh on "s1", then cancel it by starting a foreground `.open` refresh
        // on "home": "s1" enters `dirtyCachedTranscriptIDs`.
        store.send(contentBatch("s1", "m1", "A")) { it.copy(refreshingTranscripts = setOf("s1")) }
        store.send(Action.detail(SessionFeature.Action.RefreshRequested(RefreshReason.Open))) {
            it.copy(refreshingTranscripts = emptySet(), dirtyCachedTranscriptIDs = listOf("s1")).updatingDetail { d ->
                d.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))
            }
        }
        assertTrue(store.state.isInitialTranscriptInFlight)

        // 2. While `isInitialTranscriptInFlight` is true, skip refreshes for "s2"..."s18". The dirty
        // set caps at 16 (`dirtyCachedTranscriptLimit`), dropping the oldest ("s1", "s2").
        for (index in 2..18) {
            val id = "s$index"
            store.send(contentBatch(id, "m$index", "B")) { it.notingDirtyCachedTranscript(id) }
        }
        val expectedDirty = (3..18).map { "s$it" }
        assertEquals(HomeFeature.dirtyCachedTranscriptLimit, store.state.dirtyCachedTranscriptIDs.size)
        assertEquals(expectedDirty, store.state.dirtyCachedTranscriptIDs)

        // 3. When "home"'s foreground refresh settles, `dirtyCachedTranscriptIDs` is cleared and the
        // 16 retained cached sessions are scheduled once with 500 ms staggered delays.
        homeGate.release()
        val homeFresh = listOf(text("h0", "Home"))
        store.receive({ it.isDetailAction<SessionFeature.Action.RefreshResponse>() }, "refreshResponse") {
            it.copy(dirtyCachedTranscriptIDs = emptyList(), refreshingTranscripts = expectedDirty.toSet()).updatingDetail { d ->
                d.copy(refresh = null, initialTranscriptIsFresh = true)
            }
        }
        store.receive(Action.detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = "home", items = homeFresh))) {
            it.settingSeenWatermark(SessionSeenWatermark(itemID = "h0", viewedMilliseconds = 1_000), "home")
        }

        // 4. Advance the clock in 500 ms steps: each dirty cached session refreshes in order.
        val remaining = expectedDirty.toMutableSet()
        for (id in expectedDirty) {
            clock.advance(500.milliseconds)
            receiveDrainedRefresh(id, remaining, store)
        }
        assertEquals(expectedDirty, refreshedOrder.toList())
        assertTrue(store.state.dirtyCachedTranscriptIDs.isEmpty())
        assertTrue(store.state.refreshingTranscripts.isEmpty())
        store.skipInFlightEffects()
    }

    /**
     * Reopening the session already on screen (such as from a notification, deep link, or repeated
     * row tap) starts no foreground load and must not cancel in-flight active ancestry or cached
     * transcript refreshes.
     */
    @Test fun reopeningAlreadyOpenSessionDoesNotCancelAncestryOrCachedRefreshes() = runTest {
        val home = session("home", 30)
        val cached = session("cached", 20)
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(home, cached), setOf("ses_subagent"))
        var initial = HomeFeature.State(server, snapshot).connected()
        initial = initial.openingDetail(home, cachedItems = listOf(text("h0", "Home")))
        initial = initial.copy(
            sessionDetail = initial.sessionDetail?.copy(streamIsHealthy = true),
            transcriptCache = initial.transcriptCache + ("home" to listOf(text("h0", "Home"))) + ("cached" to listOf(text("c0", "Old cached"))),
        )

        val ancestryStarts = Counter()
        val ancestryCancels = Counter()
        val ancestryGate = Gate()
        val cachedStarts = Counter()
        val cachedCancels = Counter()
        val cachedGate = Gate()

        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(200))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                activeSessionParents = { _, _, _ ->
                    ancestryStarts.increment()
                    ancestryGate.wait { ancestryCancels.increment() }
                    mapOf("ses_subagent" to "cached")
                },
                transcript = { _, sessionID, _ ->
                    cachedStarts.increment()
                    cachedGate.wait { cachedCancels.increment() }
                    listOf(text("$sessionID-1", "Fresh $sessionID"))
                },
                viewSession = { _, _, _ -> },
                getSession = { _, id -> session(id, 20) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.withDependencies {
            initial.persistingTranscriptCache(including = "home")
            initial.persistingTranscriptCache(including = "cached")
        }

        // 1. Start active ancestry discovery and a cached refresh on "cached".
        store.send(Action.SnapshotRefreshed(snapshot)) {
            store.withDependencies {
                it.copy(
                    noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                    activeSessionIDs = setOf("ses_subagent"),
                    sessionReadDates = it.sessionReadDates +
                        (it.sessionKey("home") to home.updatedAt) +
                        (it.sessionKey("cached") to cached.updatedAt),
                    initializedInboxServerIDs = setOf(server.persistenceID),
                ).persistingSnapshot(Instant.EPOCH)
            }
        }
        store.receive(Action.detail(SessionFeature.Action.ActiveSessionsRead(setOf("ses_subagent")))) {
            it.updatingDetail { d -> d.copy(activeFamilySessionIDs = setOf("ses_subagent")) }
        }
        store.receive(Action.detail(SessionFeature.Action.SessionSnapshotUpdated(home)))
        store.receive(Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }
        store.send(Action.DrawerRowPressed("cached")) { it.copy(refreshingTranscripts = setOf("cached"), fetchingTranscripts = setOf("cached")) }
        assertTrue(eventually { ancestryStarts.value == 1 && cachedStarts.value == 1 })

        // 2. Reopen "home" (already on screen) and pick "home" in its own family: no foreground
        // load starts, so neither ancestry nor "cached" is cancelled.
        store.send(Action.SessionTapped("home")) {
            store.withDependencies {
                it.copy(lastViewedSessionIDs = it.lastViewedSessionIDs + (server.persistenceID to "home")).markingReadLocally(home)
            }
        }
        store.receive(Action.ViewAcknowledged(sessionID = "home", idle = 1_000))
        store.send(Action.detail(SessionFeature.Action.FamilyPicked("home"))) { it.rememberingSession() }
        assertEquals(0, ancestryCancels.value)
        assertEquals(0, cachedCancels.value)
        assertEquals(setOf("cached"), store.state.refreshingTranscripts)
        assertTrue(store.state.dirtyCachedTranscriptIDs.isEmpty())

        // 3. Both in-flight background reads finish normally.
        ancestryGate.release()
        store.receive(Action.ActiveAncestryResolved(mapOf("ses_subagent" to "cached"))) {
            store.withDependencies { it.copy(activeSessionParents = mapOf("ses_subagent" to "cached")).persistingSnapshot(Instant.EPOCH) }
        }
        val freshCached = listOf(text("cached-1", "Fresh cached"))
        cachedGate.release()
        store.receive(Action.CachedTranscriptRefreshed(sessionID = "cached", items = freshCached)) {
            store.withDependencies {
                it.copy(refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet(), transcriptReadAt = it.transcriptReadAt + ("cached" to Instant.ofEpochSecond(200)))
                    .notingUnopenedCopy("cached")
                    .publishingTranscript("cached", freshCached)
                    .persistingTranscriptCache(including = "cached")
            }
        }
        assertEquals(HomeFeature.State.Indicator.running, store.state.indicator(cached))
        assertEquals(freshCached, store.state.transcriptCache["cached"])
        store.skipInFlightEffects()
    }

    /**
     * If a foreground transcript load settles while Home is `.reconnecting`,
     * `dirtyCachedTranscriptIDs` stays retained and drains as soon as `snapshotRefreshed` restores
     * `.connected` (behind `!isInitialTranscriptInFlight`).
     */
    @Test fun dirtyCachedRefreshesSettledWhileReconnectingDrainWhenSnapshotRestoresConnection() = runTest {
        val home = session("home", 30)
        val s1 = session("s1", 20)
        val s2 = session("s2", 10)
        val snapshot = OpenCodeHomeSnapshot(emptyList(), listOf(home, s1, s2), emptySet())
        var initial = HomeFeature.State(server, snapshot).connected(HomeConnectionFeature.State.ConnectionStatus.reconnecting)
        initial = initial.openingDetail(home, cachedItems = listOf(text("h0", "Home")))
        initial = initial.copy(
            // About the cache drain: the open session's polls on reconnect are covered by
            // SessionFeatureTestsQuietPolling.
            sessionDetail = initial.sessionDetail?.copy(streamIsHealthy = true)?.withRequestsConnectionIsAvailable(true),
            transcriptCache = initial.transcriptCache +
                ("home" to listOf(text("h0", "Home"))) +
                ("s1" to listOf(text("s1-0", "Old s1"))) +
                ("s2" to listOf(text("s2-0", "Old s2"))),
        )

        val clock = TestClock()
        val homeGate = Gate()
        val refreshedOrder = Collections.synchronizedList(mutableListOf<String>())
        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(500))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> home },
                transcript = { _, sessionID, _ ->
                    if (sessionID == "home") {
                        homeGate.wait()
                        return@copy listOf(text("h0", "Home"))
                    }
                    refreshedOrder += sessionID
                    listOf(text("$sessionID-1", "Fresh $sessionID"))
                },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.withDependencies {
            initial.persistingTranscriptCache(including = "home")
            initial.persistingTranscriptCache(including = "s1")
            initial.persistingTranscriptCache(including = "s2")
        }

        // 1. While `.reconnecting`, live events mark "s1" for later instead of reading it, and a
        // foreground `.open` refresh on "home" starts; "s2" waits the same way.
        store.send(contentBatch("s1", "m1", "A")) { it.copy(dirtyCachedTranscriptIDs = listOf("s1")) }
        store.send(Action.detail(SessionFeature.Action.RefreshRequested(RefreshReason.Open))) {
            it.updatingDetail { d ->
                d.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))
            }
        }
        store.send(contentBatch("s2", "m2", "B")) { it.copy(dirtyCachedTranscriptIDs = listOf("s1", "s2")) }

        // 2. "home"'s foreground load settles while Home is still `.reconnecting`:
        // `dirtyCachedTranscriptIDs` remains ["s1", "s2"] until connection readiness returns.
        homeGate.release()
        val homeFresh = listOf(text("h0", "Home"))
        store.receive({ it.isDetailAction<SessionFeature.Action.RefreshResponse>() }, "refreshResponse") {
            it.updatingDetail { d -> d.copy(refresh = null, initialTranscriptIsFresh = true) }
        }
        store.receive(Action.detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = "home", items = homeFresh))) {
            it.settingSeenWatermark(SessionSeenWatermark(itemID = "h0", viewedMilliseconds = 1_000), "home")
        }
        assertFalse(store.state.isInitialTranscriptInFlight)
        assertFalse(store.state.isConnected)
        assertEquals(listOf("s1", "s2"), store.state.dirtyCachedTranscriptIDs)
        assertTrue(store.state.refreshingTranscripts.isEmpty())

        // 3. `snapshotRefreshed` marks the connection `.connected` (even with unchanged `updatedAt`
        // timestamps) and drains ["s1", "s2"] behind `!isInitialTranscriptInFlight`.
        store.send(Action.SnapshotRefreshed(snapshot)) {
            store.withDependencies {
                it.connected().copy(
                    noticeReadiness = HomeFeature.State.NoticeReadiness.listed,
                    dirtyCachedTranscriptIDs = emptyList(),
                    refreshingTranscripts = setOf("s1", "s2"),
                    sessionReadDates = it.sessionReadDates +
                        (it.sessionKey("home") to home.updatedAt) +
                        (it.sessionKey("s1") to s1.updatedAt) +
                        (it.sessionKey("s2") to s2.updatedAt),
                    initializedInboxServerIDs = setOf(server.persistenceID),
                ).persistingSnapshot(Instant.EPOCH)
            }
        }
        store.receive(Action.detail(SessionFeature.Action.SessionSnapshotUpdated(home)))
        store.receive(Action.detail(SessionFeature.Action.RequestsConnectionChanged(true)))
        clock.advance(150.milliseconds)
        store.receive(Action.AttentionRefreshed(emptySet())) { it.copy(noticeReadiness = HomeFeature.State.NoticeReadiness.settled) }

        // 4. Advance the clock to 500 ms and 1000 ms: "s1" then "s2" refresh in order.
        val remaining = mutableSetOf("s1", "s2")
        for ((index, id) in listOf("s1", "s2").withIndex()) {
            clock.advance((if (index == 0) 350 else 500).milliseconds)
            receiveDrainedRefresh(id, remaining, store)
        }
        assertEquals(listOf("s1", "s2"), refreshedOrder.toList())
        assertTrue(store.state.dirtyCachedTranscriptIDs.isEmpty())
        assertTrue(store.state.refreshingTranscripts.isEmpty())
        store.skipInFlightEffects()
    }

    private fun CoroutineScope.drawerReadsStore(
        state: HomeFeature.State,
        clock: Clock,
        started: MutableList<String>,
        cancelled: MutableList<String>,
        gates: MutableMap<String, CompletableDeferred<Unit>>,
    ): TestStore<HomeFeature.State, Action> {
        val store = TestStore(state, HomeFeature(), this) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, sessionID, _ ->
                    started += sessionID
                    val gate = CompletableDeferred<Unit>()
                    gates[sessionID] = gate
                    try {
                        gate.await()
                    } catch (cancellation: CancellationException) {
                        cancelled += sessionID
                        throw cancellation
                    }
                    listOf(text("o1", "Other"), text("o2", "New"))
                },
            )
        }
        store.exhaustive = false
        return store
    }

    /** The drawer opening reads `other`; a finger on its row joins that read instead of sending it again. */
    @Test fun pressingARowJoinsTheReadTheDrawerStarted() = runTest {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        val gates = Collections.synchronizedMap(mutableMapOf<String, CompletableDeferred<Unit>>())
        val store = drawerReadsStore(state(), ImmediateClock(), started, cancelled, gates)

        store.send(Action.SessionDrawerArrived(isOpen = true))
        assertTrue(eventually { started.count { it == "other" } == 1 })
        store.send(Action.DrawerRowPressed("other"))
        store.send(Action.DrawerRowPressed("other"))
        repeat(20) { yield() }
        assertEquals(1, started.count { it == "other" })
        assertTrue(cancelled.isEmpty())

        gates.getValue("other").complete(Unit)
        store.receive({ it is Action.CachedTranscriptRefreshed && it.sessionID == "other" }, "cachedTranscriptRefreshed")
        assertEquals(listOf("o1", "o2"), store.state.transcriptCache["other"]?.map { it.id })
        store.skipInFlightEffects()
    }

    /** A read still waiting out its delay (live events' 2 s pause) is replaced by the press with one now. */
    @Test fun pressingARowStillReplacesADelayedRead() = runTest {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val clock = TestClock()
        val store = drawerReadsStore(state(), clock, started, mutableListOf(), Collections.synchronizedMap(mutableMapOf()))

        store.send(contentBatch("other", "m", "A"))
        assertEquals(setOf("other"), store.state.refreshingTranscripts)
        assertTrue(store.state.fetchingTranscripts.isEmpty())
        store.send(Action.DrawerRowPressed("other"))
        assertEquals(setOf("other"), store.state.fetchingTranscripts)
        assertTrue(eventually { started.count { it == "other" } == 1 })
        clock.advance(HomeFeature.cachedTranscriptRefreshDelay)
        repeat(20) { yield() }
        assertEquals("The delayed read was replaced, not run as well", 1, started.count { it == "other" })
        store.skipInFlightEffects()
    }

    /** A message invalidated off screen marks a cached copy dirty, so it's read again before it's trusted. */
    @Test fun anInvalidatedCopyIsMarkedDirty() = runTest {
        val store = TestStore(state(), HomeFeature(), this)
        store.exhaustive = false
        store.send(Action.ServerEvent(OpenCodeServerEvent.MessageInvalidated(sessionID = "other")))
        assertEquals(listOf("other"), store.state.dirtyCachedTranscriptIDs)
    }

    /** Offline, the drawer marks what it would read and reads none of it, for the reconnect's snapshot to drain. */
    @Test fun speculativeReadsWaitForTheServer() = runTest {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val state = state().connected(HomeConnectionFeature.State.ConnectionStatus.reconnecting)
        val store = drawerReadsStore(state, ImmediateClock(), started, mutableListOf(), Collections.synchronizedMap(mutableMapOf()))

        store.send(Action.SessionDrawerArrived(isOpen = true))
        store.send(Action.DrawerRowPressed("other"))
        // The open session's update would prefetch uncached "slow".
        store.send(Action.detail(SessionFeature.Action.AcceptedTranscriptUpdated(sessionID = "home", items = listOf(text("h0", "Home")))))
        repeat(20) { yield() }
        assertTrue(started.isEmpty())
        assertEquals(listOf("other"), store.state.dirtyCachedTranscriptIDs)
        store.skipInFlightEffects()
    }

    /** Losing the connection cancels a speculative read in flight rather than leaving it to retry, and marks it dirty. */
    @Test fun losingTheConnectionCancelsSpeculativeReads() = runTest {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val cancelled = Collections.synchronizedList(mutableListOf<String>())
        // The reconnect's timers wait; only the read matters here.
        val store = drawerReadsStore(state(), TestClock(), started, cancelled, Collections.synchronizedMap(mutableMapOf()))

        store.send(Action.DrawerRowPressed("other"))
        assertTrue(eventually { started.count { it == "other" } == 1 })
        store.send(Action.ConnectionLost)
        assertTrue(eventually { cancelled == listOf("other") })
        assertTrue(store.state.refreshingTranscripts.isEmpty())
        assertTrue(store.state.fetchingTranscripts.isEmpty())
        assertEquals(listOf("other"), store.state.dirtyCachedTranscriptIDs)
        store.skipInFlightEffects()
    }

    private fun contentBatch(sessionID: String, messageID: String, delta: String): Action = Action.ServerEvent(
        OpenCodeServerEvent.ContentBatch(
            sessionID = sessionID,
            events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = messageID, ordinal = 0, delta = delta)),
        ),
    )

    private inline fun <reified T : SessionFeature.Action> Action.isDetailAction(): Boolean =
        this is Action.SessionDetail && (action as? ai.opencode.core.store.PresentationAction.Presented)?.action is T
}
