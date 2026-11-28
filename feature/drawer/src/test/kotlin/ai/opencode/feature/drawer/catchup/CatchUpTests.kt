package ai.opencode.feature.drawer.catchup

import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.EventStreamFocusClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.drawer.catchUpSessions
import ai.opencode.feature.drawer.drawerBadge
import ai.opencode.feature.drawer.indicator
import ai.opencode.feature.drawer.isMuted
import ai.opencode.feature.drawer.isUnread
import ai.opencode.feature.drawer.openingCatchUpCard
import ai.opencode.feature.drawer.openingDetail
import ai.opencode.feature.drawer.persistingTranscriptCache
import ai.opencode.feature.drawer.publishingTranscript
import ai.opencode.feature.drawer.recentSessions
import ai.opencode.feature.drawer.sessionKey
import ai.opencode.feature.drawer.statusSnapshot
import ai.opencode.feature.drawer.visibleSessions
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.feature.session.*
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoard
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException

/**
 * Catch Up through `HomeFeature` (stack, Undo, Mark All, mutes) and `CatchUpFeature`, plus the
 * swipe decision and snap-back spring.
 *
 * Elsewhere: `catchUpPreviewRefreshesWhenPreviewTranscriptIsPrefetchedOrRefreshed` and
 * `previewStoresPreserveFirstFrameFlagAcrossSameSessionRefreshesAndResetOnNewSession` are in
 * `CatchUpPreviewStoresTests.kt`: the preview's real `Store` needs Android's main looper, so they
 * run under Robolectric.
 */
class CatchUpTests {
    @get:Rule val isolation = TestIsolationRule()

    val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    fun session(id: String, updated: Long, idle: Long = 2_000, viewed: Long = 1_000, parent: String? = null) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = idle, viewedMilliseconds = viewed,
        parentID = parent,
    )

    /** A Home holding [sessions], set up the way the app's own tests hold one. */
    fun home(listing: List<OpenCodeSession>, attention: Set<String> = emptySet()): HomeFeature.State {
        val state = HomeFeature.State(
            server,
            OpenCodeHomeSnapshot(projects = emptyList(), sessions = listing, activeSessionIDs = emptySet()),
        )
        return state.copy(attentionSessionIDs = attention)
    }

    /** unread (older), waiting, running, read, unread (newer). */
    fun home(
        includeWaiting: Boolean = true,
        catchUpQueue: List<String>? = null,
        returnSessionID: String? = null,
    ): HomeFeature.State {
        val sessions = listOf(
            session("older", 10),
            session("waiting", 5, idle = 1_000, viewed = 1_000),
            session("running", 30),
            session("read", 40, idle = 1_000, viewed = 1_000),
            session("newer", 20),
        )
        var state = HomeFeature.State(
            server,
            OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = setOf("running", "waiting")),
        )
        state = state.copy(attentionSessionIDs = if (includeWaiting) setOf("waiting") else emptySet())
        state = state.openingDetail(sessions[3])
        state = state.copy(sessionDetail = state.sessionDetail?.withDraft("half-written thought"))
        if (catchUpQueue != null) {
            state = state.withCatchUp(CatchUpFeature.Stack(queue = catchUpQueue, returnSessionID = returnSessionID))
            state = state.openingCatchUpCard().first
        }
        return state
    }

    fun CoroutineScope.store(
        state: HomeFeature.State,
        receipts: MutableList<String> = Collections.synchronizedList(mutableListOf()),
    ): TestStore<HomeFeature.State, Action> {
        val sessions = state.sessions
        val attention = state.attentionSessionIDs
        val store = TestStore(state, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                viewSession = { _, id, _ -> receipts += id },
                getSession = { _, id -> sessions.firstOrNull { it.id == id } ?: session(id, 20) },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                homeAttention = { _, _, _ -> attention },
            )
        }
        // The mirrors the fixture seeded, in the store's own shared storage too.
        store.withDependencies {
            shared(SharedKeys.manuallyUnreadSessionKeys).update { state.manuallyUnreadSessionKeys }
            shared(SharedKeys.mutedSessionKeys).update { state.mutedSessionKeys }
        }
        store.exhaustive = false
        return store
    }

    private fun TestStore<HomeFeature.State, Action>.client(change: (OpenCodeClient) -> OpenCodeClient) =
        updateDependencies { this[OpenCodeClient] = change(this[OpenCodeClient]) }

    private fun TestStore<HomeFeature.State, Action>.recordHaptics(log: MutableList<String>) =
        updateDependencies { this[HapticsClient] = HapticsClient(play = { log += it.name }) }

    private fun HomeFeature.State.byID(id: String): OpenCodeSession = sessions.first { it.id == id }

    private fun synchronizedList(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Test fun stacksWaitingThenUnreadSessionsAndOpensTheFirstWithoutReadingIt() = runTest {
        val receipts = synchronizedList()
        val store = store(home(), receipts)
        assertEquals(listOf("waiting", "newer", "older"), store.state.catchUpSessions.map { it.id })

        store.send(Action.catchUpTapped)
        assertEquals(listOf("waiting", "newer", "older"), store.state.catchUp?.queue)
        assertEquals("read", store.state.catchUp?.returnSessionID)
        assertEquals("waiting", store.state.sessionDetail?.session?.id)
        assertEquals(false, store.state.sessionDetail?.readsOnView)
        store.finish()
        assertTrue(receipts.isEmpty())
        assertEquals(HomeFeature.State.Indicator.unread, store.state.indicator(store.state.sessions[0]))
    }

    /**
     * The open card deleted elsewhere: Catch Up opens the next one after the detail reducer has
     * run, and the stream and a live call's board still treat it as on screen.
     */
    @Test fun theCardCatchUpOpensAfterADeletionIsStreamFocused() = runTest {
        val focus = EventStreamFocusClient.inMemory()
        val boards = ArrayList<VoiceSessionBoard?>()
        var state = home(includeWaiting = false, catchUpQueue = listOf("newer", "older"), returnSessionID = "read")
        state = state.copy(sessions = state.sessions.map { if (it.id == "older") it.copy(directory = "/older") else it })
        state = state.withCall(VoiceFeature.State(server, state.byID("newer")).live())
        val store = store(state)
        store.updateDependencies {
            this[EventStreamFocusClient] = focus
            this[VoiceSessionBoardClient] = VoiceSessionBoardClient(publish = { boards += it }, attentionRead = { _, _ -> }, current = { null })
        }
        assertEquals("newer", store.state.sessionDetail?.session?.id)
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted("newer")))
        assertEquals("older", store.state.sessionDetail?.session?.id)
        assertTrue(focus.contains("older"))
        assertFalse(focus.contains("newer"))
        assertTrue("/older" in boards.last()!!.attentionDirectories)
        store.skipInFlightEffects()
    }

    @Test fun catchUpCardsLeaveQuietlyAndUndoTicks() = runTest {
        val log = synchronizedList()
        val store = store(home(includeWaiting = false))
        store.recordHaptics(log)
        store.send(Action.catchUpTapped)
        // The card's own flight ticks as it crosses the threshold (CatchUpStackController).
        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.markRead))
        assertTrue(log.isEmpty())
        store.send(Action.catchUpUndoTapped)
        assertEquals(listOf("selection"), log.toList())
        store.finish()
    }

    @Test fun markReadMovesAlongAndUndoBringsTheCardBackUnread() = runTest {
        val receipts = synchronizedList()
        val store = store(home(includeWaiting = false), receipts)

        store.send(Action.catchUpTapped)
        assertEquals(listOf("newer", "older"), store.state.catchUp?.queue)
        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.markRead))
        store.finish()
        assertEquals(listOf("newer"), receipts.toList())
        assertEquals(listOf("older"), store.state.catchUp?.queue)
        assertEquals("older", store.state.sessionDetail?.session?.id)
        assertFalse(store.state.isUnread(store.state.byID("newer")))

        store.send(Action.catchUpUndoTapped)
        assertEquals(listOf("newer", "older"), store.state.catchUp?.queue)
        assertEquals("newer", store.state.sessionDetail?.session?.id)
        assertEquals(false, store.state.sessionDetail?.readsOnView)
        assertTrue(store.state.isUnread(store.state.byID("newer")))
        store.finish()
    }

    @Test fun keepUnreadLeavesTheSessionUnreadAndFinishesTheStack() = runTest {
        val store = store(home(includeWaiting = false))

        store.send(Action.catchUpTapped)
        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.keepUnread))
        store.send(Action.catchUpCardDismissed("older", CatchUpFeature.Disposition.keepUnread))
        assertEquals(emptyList<String>(), store.state.catchUp?.queue)
        assertEquals("still unread in the drawer", listOf("newer", "older"), store.state.catchUpSessions.map { it.id })
        // A stale dismissal for a card no longer on top is ignored.
        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.markRead))
        assertEquals(2, store.state.catchUp?.handled?.size)
        store.finish()
    }

    @Test fun cardsBehindTheOpenOneFollowReadsAndAnswersFromOtherDevices() = runTest {
        val store = store(home())
        store.send(Action.catchUpTapped)
        assertEquals(listOf("waiting", "newer", "older"), store.state.catchUp?.queue)

        // Another device reads "older" and answers the open card's request.
        val sessions = store.state.sessions.toMutableList()
        sessions[0] = sessions[0].copy(viewedMilliseconds = 2_000)
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, setOf("running"))))
        store.send(Action.AttentionRefreshed(emptySet()))
        assertEquals(
            "the open card stays until the reader moves it along",
            listOf("waiting", "newer"),
            store.state.catchUp?.queue,
        )
        assertEquals("waiting", store.state.sessionDetail?.session?.id)
        store.finish()
    }

    @Test fun aHandledCardReturnsWhenItsSessionHasNewActivity() = runTest {
        val store = store(home(includeWaiting = false))
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.markRead))

        // An unrelated refresh does not bring it back…
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), store.state.sessions, setOf("running"))))
        assertEquals(listOf("older"), store.state.catchUp?.queue)

        // …but a reply that finished after the swipe does, at the back.
        val sessions = store.state.sessions.map { if (it.id == "newer") it.copy(idleMilliseconds = 5_000) else it }
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, setOf("running"))))
        assertEquals(listOf("older", "newer"), store.state.catchUp?.queue)
        store.finish()
    }

    @Test fun leavingReturnsToThePreviousSessionWithItsComposer() = runTest {
        val store = store(home())
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpExitTapped)
        assertNull(store.state.catchUp)
        assertTrue("Back to the drawer Catch Up was opened from", store.state.isDrawerOpen)
        assertEquals("read", store.state.sessionDetail?.session?.id)
        assertEquals(true, store.state.sessionDetail?.readsOnView)
        assertEquals("half-written thought", store.state.sessionDetail?.draft)
        store.finish()
    }

    @Test fun openingASessionFromANotificationLeavesTheStack() = runTest {
        val store = store(home())
        store.send(Action.catchUpTapped)
        store.send(Action.SessionTapped("running"))
        assertNull(store.state.catchUp)
        store.receive({ it is Action.SessionNavigationReady }, "sessionNavigationReady")
        store.finish()
        assertEquals("running", store.state.sessionDetail?.session?.id)
        assertEquals(true, store.state.sessionDetail?.readsOnView)
    }

    @Test fun notificationForTheOpenCardRestoresAutomaticReadsWithoutLosingItsDraft() = runTest {
        val receipts = synchronizedList()
        var state = home(includeWaiting = false, catchUpQueue = listOf("newer"))
        state = state.copy(sessionDetail = state.sessionDetail?.withDraft("unfinished reply"))
        val store = store(state, receipts)

        store.send(Action.SessionTapped("newer"))
        assertNull(store.state.catchUp)
        assertEquals(true, store.state.sessionDetail?.readsOnView)
        assertEquals("unfinished reply", store.state.sessionDetail?.draft)
        store.finish()
        assertEquals(listOf("newer"), receipts.toList())
    }

    @Test fun browsingAChildAndReturningDoesNotReadTheCatchUpCard() = runTest {
        var state = home(includeWaiting = false, catchUpQueue = listOf("newer", "older"))
        val root = state.sessionDetail!!.session!!
        val child = session("child", 20).copy(parentID = root.id)
        state = state.copy(sessionDetail = state.sessionDetail?.copy(familySessions = listOf(root, child)))
        val receipts = synchronizedList()
        val store = store(state, receipts)

        for (target in listOf(child, root)) {
            store.send(Action.detail(SessionFeature.Action.FamilyPicked(target.id)))
            assertEquals(target.viewedMilliseconds, store.state.sessionDetail?.session?.viewedMilliseconds)
            assertTrue(store.state.isUnread(root))
        }
        store.send(Action.catchUpCardDismissed(root.id, CatchUpFeature.Disposition.keepUnread))
        assertTrue(store.state.isUnread(root))
        store.finish()
        assertTrue(receipts.isEmpty())
    }

    @Test fun undoCancelsTheInFlightReadReceipt() = runTest {
        val state = home(includeWaiting = false, catchUpQueue = listOf("newer", "older"))
        var cancelled = false
        val store = store(state)
        store.client {
            it.copy(viewSession = { _, _, _ ->
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                }
            })
        }

        store.send(Action.catchUpCardDismissed("newer", CatchUpFeature.Disposition.markRead))
        store.send(Action.catchUpUndoTapped)
        assertTrue(eventually { cancelled })
        assertTrue(store.state.isUnread(store.state.byID("newer")))
        assertEquals("newer", store.state.catchUp?.queue?.firstOrNull())
        store.finish()
    }

    @Test fun markAllAsReadReadsUnreadSessionsAndLeavesWaitingAndRunningOnes() = runTest {
        val receipts = synchronizedList()
        var state = home()
        // Waiting and unread: what led up to the question is still unseen.
        state = state.copy(sessions = state.sessions.toMutableList().also { it[1] = it[1].copy(idleMilliseconds = 2_000) })
        val store = store(state, receipts)
        assertEquals(listOf("waiting", "newer", "older"), store.state.catchUpSessions.map { it.id })

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        assertEquals(setOf("newer", "older"), receipts.toSet())
        assertEquals(listOf("waiting"), store.state.catchUpSessions.map { it.id })
        assertEquals("its pending request still needs you", setOf("waiting"), store.state.attentionSessionIDs)
        assertTrue(store.state.isUnread(store.state.byID("waiting")))
        assertTrue("running sessions join once they finish", store.state.isUnread(store.state.byID("running")))
        assertFalse(store.state.isUnread(store.state.byID("newer")))
        assertFalse(store.state.isUnread(store.state.byID("older")))
    }

    @Test fun markAllAsReadEmptiesCatchUpWhenNothingIsWaiting() = runTest {
        var state = home(includeWaiting = false)
        state = state.copy(manuallyUnreadSessionKeys = setOf(state.sessionKey("read")))
        val store = store(state)
        assertEquals(listOf("read", "newer", "older"), store.state.catchUpSessions.map { it.id })

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        assertTrue("the drawer row hides", store.state.catchUpSessions.isEmpty())
        // Nothing left to read is a no-op.
        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
    }

    @Test fun markAllAsReadSendsAFewReceiptsAtATime() = runTest {
        var state = home(includeWaiting = false)
        state = state.copy(sessions = state.sessions + (0 until 10).map { session("extra$it", 50L + it) })
        var current = 0
        var peak = 0
        val delivered = synchronizedList()
        val store = store(state)
        store.client {
            it.copy(viewSession = { _, id, _ ->
                current += 1
                peak = maxOf(peak, current)
                repeat(20) { yield() }
                current -= 1
                delivered += id
            })
        }

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        store.skipReceivedActions()
        assertEquals(12, delivered.size)
        assertTrue(peak <= HomeFeature.receiptConcurrency)
        assertTrue(store.state.markAllReceipts.isEmpty())
        assertTrue(store.state.catchUpSessions.isEmpty())
    }

    @Test fun aFailedMarkAllReceiptPutsThatSessionBackUnread() = runTest {
        val store = store(home(includeWaiting = false))
        store.client {
            it.copy(viewSession = { _, id, _ -> if (id == "older") throw OpenCodeClientError("Offline") })
        }

        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        store.skipReceivedActions()
        assertEquals("only the refused receipt comes back", listOf("older"), store.state.catchUpSessions.map { it.id })
        assertNull(store.state.locallyViewedMilliseconds["older"])
        assertEquals(2_000L, store.state.locallyViewedMilliseconds["newer"])
        assertTrue(store.state.markAllReceipts.isEmpty())

        // A stale list response can't hide it again either.
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), home().sessions, setOf("running"))))
        assertEquals(listOf("older"), store.state.catchUpSessions.map { it.id })
        store.finish()
    }

    @Test fun markAsUnreadWinsOverALateMarkAllReceipt() = runTest {
        val store = store(home(includeWaiting = false))
        // Each receipt waits for its own release.
        val gates = Collections.synchronizedList(mutableListOf<CompletableDeferred<Unit>>())
        store.client {
            it.copy(viewSession = { _, _, _ ->
                val gate = CompletableDeferred<Unit>()
                gates += gate
                gate.await()
            })
        }

        store.send(Action.catchUpMarkAllReadTapped)
        store.send(Action.MarkUnread("newer"))
        while (gates.size < 2) yield()
        gates.toList().forEach { it.complete(Unit) }
        store.finish()
        store.skipReceivedActions()
        assertTrue(store.state.isUnread(store.state.byID("newer")))
        assertNull(store.state.locallyViewedMilliseconds["newer"])
        assertFalse(store.state.isUnread(store.state.byID("older")))
    }

    @Test fun markAsUnreadCancelsARowsReceiptInFlight() = runTest {
        var cancelled = false
        val store = store(home(includeWaiting = false))
        store.client {
            it.copy(viewSession = { _, _, _ ->
                try {
                    awaitCancellation()
                } catch (cancellation: CancellationException) {
                    cancelled = true
                    throw cancellation
                }
            })
        }

        store.send(Action.MarkRead("newer"))
        store.send(Action.MarkUnread("newer"))
        store.finish()
        assertTrue(cancelled)
        assertTrue(store.state.isUnread(store.state.byID("newer")))
    }

    @Test fun markAllAsReadDuringCatchUpKeepsTheOpenCard() = runTest {
        val store = store(home(includeWaiting = false))
        store.send(Action.catchUpTapped)
        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        assertEquals("the open card stays until the reader moves it along", listOf("newer"), store.state.catchUp?.queue)
        assertEquals("newer", store.state.sessionDetail?.session?.id)
    }

    @Test fun tappingTheCardTitleOpensTheSessionAndLeavesCatchUp() = runTest {
        val receipts = synchronizedList()
        var state = home(includeWaiting = false, catchUpQueue = listOf("newer", "older"), returnSessionID = "read")
        state = state.copy(sessionDetail = state.sessionDetail?.withDraft("reply from the card"))
        val store = store(state, receipts)

        store.send(Action.catchUpOpenTapped)
        assertNull(store.state.catchUp)
        assertEquals("newer", store.state.sessionDetail?.session?.id)
        assertEquals(true, store.state.sessionDetail?.readsOnView)
        assertEquals("reply from the card", store.state.sessionDetail?.draft)
        assertEquals("newer", store.state.lastViewedSessionIDs[store.state.server.persistenceID])
        assertFalse(store.state.isUnread(store.state.byID("newer")))
        assertTrue("the rest stay for next time", store.state.isUnread(store.state.byID("older")))
        store.finish()
        assertEquals(listOf("newer"), receipts.toList())

        // Outside Catch Up the title has nothing to leave.
        store.send(Action.catchUpOpenTapped)
        store.finish()
    }

    @Test fun openingFromTheTitleReadsTheNewestIdleTheCardSaw() = runTest {
        var state = home(includeWaiting = false, catchUpQueue = listOf("newer", "older"))
        // A reply finished on the card after the list was read.
        state = state.copy(
            sessionDetail = state.sessionDetail?.let { it.copy(session = it.session?.copy(idleMilliseconds = 3_000)) },
        )
        val receipts = Collections.synchronizedMap(mutableMapOf<String, Long>())
        val store = store(state)
        store.client { it.copy(viewSession = { _, id, idle -> receipts[id] = idle }) }

        store.send(Action.catchUpOpenTapped)
        store.finish()
        store.skipReceivedActions()
        assertEquals(mapOf("newer" to 3_000L), receipts.toMap())
        assertEquals(3_000L, store.state.sessionDetail?.session?.viewedMilliseconds)
        assertEquals(3_000L, store.state.locallyViewedMilliseconds["newer"])
        // The list catching up to that reply doesn't bring it back unread.
        val sessions = store.state.sessions.map {
            if (it.id == "newer") it.copy(idleMilliseconds = 3_000, viewedMilliseconds = 1_000) else it
        }
        store.send(Action.SnapshotRefreshed(OpenCodeHomeSnapshot(emptyList(), sessions, setOf("running"))))
        assertFalse(store.state.isUnread(store.state.byID("newer")))
        store.finish()
    }

    @Test fun openingFromTheTitleOnASubagentReadsItAndItsSession() = runTest {
        var state = home(includeWaiting = false, catchUpQueue = listOf("newer", "older"))
        val root = state.sessionDetail!!.session!!
        val child = session("child", 20, idle = 2_500, viewed = 1_000).copy(parentID = root.id)
        state = state.copy(sessionDetail = state.sessionDetail?.copy(familySessions = listOf(root, child), session = child))
        val receipts = Collections.synchronizedMap(mutableMapOf<String, Long>())
        val store = store(state)
        store.client { it.copy(viewSession = { _, id, idle -> receipts[id] = idle }) }

        store.send(Action.catchUpOpenTapped)
        store.finish()
        store.skipReceivedActions()
        assertNull(store.state.catchUp)
        assertEquals("the reader stays on the subagent", "child", store.state.sessionDetail?.session?.id)
        assertEquals(mapOf("newer" to 2_000L, "child" to 2_500L), receipts.toMap())
        assertEquals(2_500L, store.state.sessionDetail?.session?.viewedMilliseconds)
        assertEquals(2_500L, store.state.sessionDetail?.viewedIdle?.get("child"))
        assertEquals(2_000L, store.state.sessionDetail?.familySessions?.firstOrNull { it.id == "newer" }?.viewedMilliseconds)
        assertFalse(store.state.isUnread(store.state.byID("newer")))
    }

    /**
     * A subagent's session never joins Catch Up, unread or not: it reaches the reader from its
     * parent, as in Recents.
     */
    @Test fun subagentSessionsNeverJoinCatchUp() {
        val root = session("root", updated = 10)
        val child = session("child", updated = 20, parent = root.id)
        val state = home(listing = listOf(child, root))
        assertTrue("its own session is genuinely unread", state.isUnread(state.byID(child.id)))
        assertEquals(listOf(root.id), state.catchUpSessions.map { it.id })
        assertFalse(state.statusSnapshot().needsCatchUp(child))
        val model = DrawerListModel.build(
            state = state,
            inputs = DrawerListInputs(selectedSessionID = null, reducedMotion = false, increasedContrast = false),
            liftedID = null,
            now = Instant.ofEpochSecond(100),
            timestamp = { _, _ -> "" },
            project = { _, _ -> null },
        )
        assertEquals("the drawer's Catch Up row counts roots", 1, model.shortcuts.catchUpCount)
    }

    /**
     * A request inside a subagent waits in its parent's card: Home reports pending input by family
     * root, and the child's own unread session stays behind it.
     */
    @Test fun aSubagentsPendingRequestWaitsInItsParentsCatchUpCard() {
        val root = session("root", updated = 10, idle = 1_000, viewed = 1_000)
        val child = session("child", updated = 20, parent = root.id)
        val state = home(listing = listOf(child, root), attention = setOf(root.id))
        assertEquals(listOf(root.id), state.catchUpSessions.map { it.id })
        assertEquals(HomeFeature.State.Indicator.attention, state.indicator(state.byID(root.id)))
    }

    @Test fun mutedSessionsStayListedButNeverJoinCatchUpOrBadges() = runTest {
        val store = store(home())
        fun indicator(id: String) = store.state.indicator(store.state.byID(id))

        store.send(Action.ToggleMute("newer"))
        store.send(Action.ToggleMute("waiting"))
        assertTrue(store.state.isMuted("newer") && store.state.isMuted("waiting"))
        assertEquals("even a waiting session stays out", listOf("older"), store.state.catchUpSessions.map { it.id })
        assertEquals("no unread dot", HomeFeature.State.Indicator.none, indicator("newer"))
        assertEquals("its own row still shows the pending request", HomeFeature.State.Indicator.attention, indicator("waiting"))
        assertTrue("read state is kept underneath", store.state.isUnread(store.state.byID("newer")))
        assertEquals("the unmuted older session still counts", DrawerBadge.Unread, store.state.drawerBadge(excluding = "read"))
        assertTrue(store.state.visibleSessions.map { it.id }.contains("newer"))
        store.send(Action.SearchChanged("newer"))
        assertEquals("search still finds it", listOf("newer"), store.state.recentSessions.map { it.id })
        store.send(Action.SearchChanged(""))

        store.send(Action.ToggleMute("older"))
        assertTrue(store.state.catchUpSessions.isEmpty())
        assertNull(store.state.drawerBadge(excluding = "read"))

        store.send(Action.ToggleMute("newer"))
        assertFalse(store.state.isMuted("newer"))
        assertEquals(listOf("newer"), store.state.catchUpSessions.map { it.id })
        assertEquals(HomeFeature.State.Indicator.unread, indicator("newer"))
        assertEquals(DrawerBadge.Unread, store.state.drawerBadge(excluding = "read"))
        store.finish()
    }

    @Test fun mutedSessionsCanStillBeOpened() = runTest {
        val receipts = synchronizedList()
        val store = store(home(), receipts)
        store.send(Action.ToggleMute("newer"))
        store.send(Action.SessionTapped("newer"))
        store.finish()
        store.skipReceivedActions()
        assertEquals("newer", store.state.sessionDetail?.session?.id)
        assertTrue("opening still reads it", receipts.contains("newer"))
    }

    @Test fun mutingDuringCatchUpDropsQueuedCardsButKeepsTheOpenOne() = runTest {
        val store = store(home())
        store.send(Action.catchUpTapped)
        assertEquals(listOf("waiting", "newer", "older"), store.state.catchUp?.queue)
        store.send(Action.ToggleMute("older"))
        assertEquals(listOf("waiting", "newer"), store.state.catchUp?.queue)
        store.send(Action.ToggleMute("waiting"))
        assertEquals("the card on screen stays until moved along", listOf("waiting", "newer"), store.state.catchUp?.queue)
        assertEquals("waiting", store.state.sessionDetail?.session?.id)
        store.send(Action.catchUpCardDismissed("waiting", CatchUpFeature.Disposition.keepUnread))
        assertEquals(listOf("newer"), store.state.catchUp?.queue)
        store.finish()
    }

    @Test fun markAllAsReadLeavesMutedSessionsAlone() = runTest {
        val receipts = synchronizedList()
        val store = store(home(includeWaiting = false), receipts)
        store.send(Action.ToggleMute("older"))
        store.send(Action.catchUpMarkAllReadTapped)
        store.finish()
        store.skipReceivedActions()
        assertFalse("no receipt is sent for a muted session", receipts.contains("older"))
        assertTrue(store.state.catchUpSessions.isEmpty())
    }

    @Test fun projectedLandingDecidesFastShortFlicksAndSlowDrags() {
        val width = 440f
        // Fast short flick right (only 38 pt dragged, 650 pt/s) projects past 24% (105.6 pt) and commits.
        assertEquals(
            CatchUpFeature.Disposition.markRead,
            CatchUpSwipeMath.decision(translation = Offset(38f, 4f), velocity = Offset(650f, 10f), width = width),
        )
        // Fast short flick left commits to keepUnread.
        assertEquals(
            CatchUpFeature.Disposition.keepUnread,
            CatchUpSwipeMath.decision(translation = Offset(-38f, -4f), velocity = Offset(-650f, -10f), width = width),
        )
        // Moderate flick (55 pt, 380 pt/s) projects to ~118 pt >= 105.6 pt and commits.
        assertEquals(
            CatchUpFeature.Disposition.markRead,
            CatchUpSwipeMath.decision(translation = Offset(55f, 0f), velocity = Offset(380f, 0f), width = width),
        )
        // Slow drag past 24% (112 pt) with zero release velocity commits.
        assertEquals(
            CatchUpFeature.Disposition.markRead,
            CatchUpSwipeMath.decision(translation = Offset(112f, 0f), velocity = Offset.Zero, width = width),
        )
        // Slow short drag (70 pt, 60 pt/s) projects to ~80 pt < 105.6 pt and snaps back.
        assertNull(CatchUpSwipeMath.decision(translation = Offset(70f, 0f), velocity = Offset(60f, 0f), width = width))
        // Dragged past threshold (130 pt) then flicked back toward center (-550 pt/s) cancels commit.
        assertNull(CatchUpSwipeMath.decision(translation = Offset(130f, 0f), velocity = Offset(-550f, 0f), width = width))
    }

    @Test fun swipingThroughTwelveCardsAvoidsRedundantPublicationsAndMeasuresMainThreadWork() = runTest {
        val sessions = (0 until 12).map { index -> session("card_$index", 100L + index, idle = 2_000, viewed = 1_000) } +
            listOf(session("read_base", 50, idle = 1_000, viewed = 1_000))
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(emptyList(), sessions, emptySet()))
        state = state.copy(connection = state.connection.copy(hasConnected = true))
        state = state.openingDetail(sessions.last())
        val sampleItems = listOf(
            OpenCodeTranscriptItem.UserText(id = "u1", text = "Check Catch Up swipe physics and projected landing."),
            OpenCodeTranscriptItem.AssistantMarkdown(
                id = "a1",
                text = """
                ### Catch Up Swipe Review

                - Projected landing uses `project(initialVelocity:decelerationRate:)`
                - Commit threshold lowered from `0.30` to `0.24` of width

                ```swift
                let decision = CatchUpSwipeMath.decision(translation: total, velocity: velocity, width: width)
                ```
                """.trimIndent(),
            ),
        )
        state = state.copy(transcriptCache = state.transcriptCache + sessions.associate { it.id to sampleItems })

        var sharePublishCount = 0
        var systemPublishCount = 0
        val store = store(state)
        store.updateDependencies {
            this[ShareInboxClient] = ShareInboxClient(
                consume = { emptyList() },
                publishTargets = { sharePublishCount += 1 },
                publishCredentials = { _, _ -> },
                removeTargets = {},
            )
            this[SystemIntegrationClient] = SystemIntegrationClient(
                actions = { kotlinx.coroutines.flow.emptyFlow() },
                publish = { _, _ -> systemPublishCount += 1 },
                removeServer = {},
            )
        }

        val openStart = System.nanoTime()
        store.send(Action.catchUpTapped)
        val openMs = (System.nanoTime() - openStart) / 1e6
        assertEquals(12, store.state.catchUp?.queue?.size)

        val shareBeforeSwipes = sharePublishCount
        val systemBeforeSwipes = systemPublishCount

        val swipeMs = mutableListOf<Double>()
        val queue = store.state.catchUp?.queue.orEmpty()
        for ((index, sessionID) in queue.withIndex()) {
            val disposition = if (index % 2 == 0) CatchUpFeature.Disposition.markRead else CatchUpFeature.Disposition.keepUnread
            val t0 = System.nanoTime()
            store.send(Action.catchUpCardDismissed(sessionID, disposition))
            if (disposition == CatchUpFeature.Disposition.markRead) {
                store.receive({ it is Action.ViewAcknowledged }, "viewAcknowledged")
            }
            swipeMs += (System.nanoTime() - t0) / 1e6
        }
        assertEquals(true, store.state.catchUp?.queue?.isEmpty())
        assertEquals(
            "Swiping Catch Up cards must not re-sort and write ShareTargets on every card",
            shareBeforeSwipes,
            sharePublishCount,
        )
        assertEquals(
            "Marking cards read in Catch Up must not rebuild SystemPublication on every card",
            systemBeforeSwipes,
            systemPublishCount,
        )

        val exitStart = System.nanoTime()
        store.send(Action.catchUpExitTapped)
        val exitMs = (System.nanoTime() - exitStart) / 1e6
        assertNull(store.state.catchUp)
        assertTrue(store.state.isDrawerOpen)
        store.finish()

        val sorted = swipeMs.sorted()
        val p50 = sorted[sorted.size / 2]
        val p95 = sorted[minOf(sorted.size - 1, (sorted.size * 0.95).toInt())]
        println(
            "[CatchUpPerf:Reducer] open=%.2fms swipe_p50=%.2fms swipe_p95=%.2fms swipe_max=%.2fms exit=%.2fms"
                .format(openMs, p50, p95, sorted.lastOrNull() ?: 0.0, exitMs),
        )
    }

    @Test fun catchUpFeatureReducesUndoExitAndReceiptsExhaustively() = runTest {
        val handled = CatchUpFeature.Stack.Handled(
            sessionID = "newer",
            disposition = CatchUpFeature.Disposition.markRead,
            idleMilliseconds = 2_000,
            updatedAt = Instant.ofEpochSecond(20),
            neededAttention = false,
        )
        val backup = CatchUpFeature.ReadBackup(
            idle = 2_000,
            viewedMilliseconds = 1_000,
            locallyViewedMilliseconds = null,
            readDate = null,
            wasManuallyUnread = false,
        )
        val store = TestStore(
            CatchUpFeature.State(
                stack = CatchUpFeature.Stack(queue = listOf("older"), handled = listOf(handled), returnSessionID = "read"),
                markAllReceipts = mapOf("newer" to backup),
            ),
            CatchUpFeature(),
            this,
        )

        store.send(CatchUpFeature.Action.UndoTapped) {
            it.copy(stack = it.stack?.copy(handled = emptyList(), queue = listOf("newer", "older")), lastUndone = handled)
        }
        store.send(CatchUpFeature.Action.MarkAllReceiptResponse(sessionID = "newer", idle = 2_000, succeeded = true)) {
            it.copy(
                markAllReceipts = emptyMap(),
                lastResolvedReceipt = CatchUpFeature.State.ResolvedReceipt(sessionID = "newer", backup = backup, succeeded = true),
            )
        }
        val exiting = store.state.stack
        store.send(CatchUpFeature.Action.ExitTapped) { it.copy(stack = null, exitingStack = exiting) }
        store.finish()
    }
}
