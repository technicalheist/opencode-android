package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Gate
import ai.opencode.feature.drawer.HomeFeatureTestSupport.LockIsolated
import ai.opencode.feature.drawer.HomeFeatureTestSupport.append
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.detail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.distantPast
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receive
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveDetail
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.list.DrawerPinDrag
import ai.opencode.feature.drawer.list.DrawerPinDragHost
import ai.opencode.feature.drawer.list.DrawerPinDragItem
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.session.withPendingFiles
import ai.opencode.server.CachedTranscriptSnapshots
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ai.opencode.core.testing.TestIsolationRule
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** The drawer's part: pins, mutes, the pin drag, deletion, local sends and read state. */
class HomeFeatureTestsDrawer {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val otherServer = OpenCodeServer(url = "http://localhost:4097", username = "", password = "")

    private fun session(id: String, title: String, updatedAt: Instant, projectID: String = "p", directory: String = "/project", idle: Long? = null, viewed: Long? = null) =
        OpenCodeSession(id = id, serverTitle = title, projectID = projectID, directory = directory, updatedAt = updatedAt, idleMilliseconds = idle, viewedMilliseconds = viewed)

    private fun key(id: String) = "${server.persistenceID}|$id"

    @Test
    fun pinsArePersistentServerScopedAndReturnToRecentWhenUnpinned() = homeTest { h ->
        val recent = session("recent", "Recent", Instant.now())
        val older = session("older", "Older", distantPast, projectID = "q", directory = "/other")
        val snapshot = snapshot(listOf(recent, older))
        val store = h.store(h.state { HomeFeature.State(server, snapshot) })
        store.send(Action.TogglePin(older.id)) { it.copy(pinnedSessionKeys = listOf(key(older.id))) }
        assertEquals(listOf(older), store.state.pinnedSessions)
        assertEquals(listOf(recent), store.state.recentSessions)
        assertEquals(listOf(older), h.state { HomeFeature.State(server, snapshot) }.pinnedSessions)
        assertTrue(h.state { HomeFeature.State(otherServer, snapshot) }.pinnedSessions.isEmpty())
        store.send(Action.TogglePin(older.id)) { it.copy(pinnedSessionKeys = emptyList()) }
        assertEquals(listOf(recent, older), store.state.recentSessions)
    }

    @Test
    fun mutesArePersistentServerScopedAndForgottenWithTheSession() = homeTest { h ->
        val recent = session("recent", "Recent", Instant.now())
        val older = session("older", "Older", distantPast, projectID = "q", directory = "/other")
        val snapshot = snapshot(listOf(recent, older))
        val store = h.store(h.state { HomeFeature.State(server, snapshot) })
        store.send(Action.ToggleMute(older.id)) { it.copy(mutedSessionKeys = setOf(key(older.id))) }
        assertTrue(store.state.isMuted(older.id))
        assertFalse(store.state.isMuted(recent.id))
        assertEquals("muted sessions stay listed", listOf(recent, older), store.state.recentSessions)
        assertEquals(setOf(key(older.id)), h.shared(SharedKeys.mutedSessionKeys))
        val restored = h.state { HomeFeature.State(server, snapshot) }
        assertTrue("a relaunch reads the same file", restored.isMuted(older.id))
        assertFalse(h.state { HomeFeature.State(otherServer, snapshot) }.isMuted(older.id))
        store.send(Action.ToggleMute(older.id)) { it.copy(mutedSessionKeys = emptySet()) }
        store.send(Action.ToggleMute("missing"))

        store.send(Action.ToggleMute(recent.id)) { it.copy(mutedSessionKeys = setOf(key(recent.id))) }
        store.exhaustive = false
        h.state { restored.purgingSession(recent.id) }
        assertTrue("deleting a session forgets its mute", h.shared(SharedKeys.mutedSessionKeys).isEmpty())
    }

    @Test
    fun hiddenSessionsLeaveTheListButStayInSearch() = homeTest { h ->
        val recent = session("recent", "Recent", Instant.now())
        val older = session("older", "Older", distantPast, projectID = "q", directory = "/other")
        val snapshot = snapshot(listOf(recent, older))
        val store = h.store(h.state { HomeFeature.State(server, snapshot) })
        store.send(Action.TogglePin(older.id)) { it.copy(pinnedSessionKeys = listOf(key(older.id))) }
        store.send(Action.ToggleHidden(older.id)) { it.copy(hiddenSessionKeys = setOf(key(older.id))) }
        assertTrue(store.state.isHidden(older.id))
        assertTrue("a hidden pin leaves Pinned too", store.state.pinnedSessions.isEmpty())
        assertEquals(listOf(recent), store.state.recentSessions)
        val searching = store.state.copy(search = "Old")
        assertEquals("search still finds it", listOf(older), searching.pinnedSessions)
        assertEquals("a relaunch reads the same file", listOf(recent), h.state { HomeFeature.State(server, snapshot) }.recentSessions)
        assertEquals(listOf(recent, older), h.state { HomeFeature.State(otherServer, snapshot) }.recentSessions)
        store.send(Action.ToggleHidden(older.id)) { it.copy(hiddenSessionKeys = emptySet()) }
        assertEquals(listOf(older), store.state.pinnedSessions)
        store.send(Action.ToggleHidden("missing"))

        store.send(Action.ToggleHidden(recent.id)) { it.copy(hiddenSessionKeys = setOf(key(recent.id))) }
        store.exhaustive = false
        h.state { store.state.purgingSession(recent.id) }
        assertTrue("deleting a session forgets that it was hidden", h.shared(SharedKeys.hiddenSessionKeys).isEmpty())
    }

    @Test
    fun pinsRespectSearchAndAppendInOrder() = homeTest { h ->
        val a = session("a", "Alpha", Instant.now())
        val b = session("b", "Beta", distantPast, projectID = "q", directory = "/other")
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(a, b))) })
        store.exhaustive = false
        store.send(Action.TogglePin(a.id)) { it.copy(pinnedSessionKeys = listOf(key(a.id))) }
        store.send(Action.TogglePin(b.id)) { it.copy(pinnedSessionKeys = listOf(key(a.id), key(b.id))) }
        assertEquals(listOf(a, b), store.state.pinnedSessions)
        store.send(Action.SearchChanged("Beta")) { it.copy(search = "Beta") }
        assertEquals(listOf(b), store.state.pinnedSessions)
        store.send(Action.TogglePin("missing"))
    }

    @Test
    fun pinnedSessionsCanMoveWithAccessibleAndDragActions() = homeTest { h ->
        val sessions = listOf("a", "b", "c").map { session(it, it, Instant.now(), directory = "/p") }
        val state = h.state {
            HomeFeature.State(server, snapshot(sessions)).updatingPinnedSessionKeys { sessions.map { key(it.id) } }
        }
        val store = h.store(state)

        store.send(Action.MovePinDown("a")) { it.copy(pinnedSessionKeys = listOf(key("b"), key("a"), key("c"))) }
        store.send(Action.MovePinUp("c")) { it.copy(pinnedSessionKeys = listOf(key("b"), key("c"), key("a"))) }
        store.send(Action.MovePinUp("b"))
        store.send(Action.MovePinDown("a"))
    }

    @Test
    fun draggingAnUnpinnedSessionIntoPinnedInsertsAtIndexAndDraggingOutUnpins() = homeTest { h ->
        val sessions = listOf("a", "b", "c", "d").mapIndexed { index, id ->
            session(id, id.uppercase(), date(40 - index * 10), directory = "/p")
        }
        val otherServerKey = "other-server|x"
        val state = h.state { HomeFeature.State(server, snapshot(sessions)).updatingPinnedSessionKeys { listOf(otherServerKey) } }
        val store = h.store(state)

        // Dropping an unpinned session back in Recents is a no-op.
        store.send(Action.DrawerRowDropped(sessionID = "c", pinnedIndex = null))

        // Dropping the first pin when nothing on this server is pinned yet.
        store.send(Action.DrawerRowDropped(sessionID = "c", pinnedIndex = 0)) { it.copy(pinnedSessionKeys = listOf(otherServerKey, key("c"))) }
        assertEquals(listOf("c"), store.state.pinnedSessions.map { it.id })
        assertEquals(listOf("a", "b", "d"), store.state.recentSessions.map { it.id })

        // Dragging another unpinned session to the top of Pinned (index 0).
        store.send(Action.DrawerRowDropped(sessionID = "d", pinnedIndex = 0)) { it.copy(pinnedSessionKeys = listOf(otherServerKey, key("d"), key("c"))) }
        assertEquals(listOf("d", "c"), store.state.pinnedSessions.map { it.id })

        // Dragging an unpinned session into the middle of Pinned (index 1).
        store.send(Action.DrawerRowDropped(sessionID = "b", pinnedIndex = 1)) {
            it.copy(pinnedSessionKeys = listOf(otherServerKey, key("d"), key("b"), key("c")))
        }
        assertEquals(listOf("d", "b", "c"), store.state.pinnedSessions.map { it.id })

        // Dropping a pinned session back in its own slot is a no-op.
        store.send(Action.DrawerRowDropped(sessionID = "b", pinnedIndex = 1))

        // Reordering a pinned session to the end of Pinned.
        store.send(Action.DrawerRowDropped(sessionID = "d", pinnedIndex = 2)) {
            it.copy(pinnedSessionKeys = listOf(otherServerKey, key("b"), key("c"), key("d")))
        }
        assertEquals(listOf("b", "c", "d"), store.state.pinnedSessions.map { it.id })

        // Dragging a pinned session down into Recents unpins it and restores recency order.
        store.send(Action.DrawerRowDropped(sessionID = "b", pinnedIndex = null)) {
            it.copy(pinnedSessionKeys = listOf(otherServerKey, key("c"), key("d")))
        }
        assertEquals(listOf("c", "d"), store.state.pinnedSessions.map { it.id })
        assertEquals(listOf("a", "b"), store.state.recentSessions.map { it.id })
    }

    // region Pin drag

    /** The list a [DrawerPinDrag] moves: visible items in list coordinates. */
    private class TestDrawerPinHost(override val scope: CoroutineScope) : DrawerPinDragHost {
        val items = ArrayList<DrawerPinDragItem>()
        override fun visibleItems(): List<DrawerPinDragItem> = items.toList()
        override val width: Float = 390f
        override var viewportTop: Float = 0f
        override fun flushNow() = Unit
        override fun preserveScroll() = Unit
        override val frameDurationSeconds: Double = 1.0 / 60.0

        fun addHeader(key: DrawerPinDrag.ItemKey, minY: Float, height: Float) {
            items += DrawerPinDragItem(key, minY, height)
        }

        fun addRow(sessionID: String, minY: Float, height: Float) {
            items += DrawerPinDragItem(DrawerPinDrag.ItemKey.Row(sessionID), minY, height)
        }

        fun removeRow(sessionID: String) {
            items.removeAll { it.key == DrawerPinDrag.ItemKey.Row(sessionID) }
        }
    }

    /** Frames for the drag's spring loop, as the display link would send them. */
    private class Frames(private val test: TestScope) {
        val clock = BroadcastFrameClock()
        private var nanos = 0L
        fun scope(): CoroutineScope = CoroutineScope(test.coroutineContext + clock + Job())
        fun pump(pinDrag: DrawerPinDrag, limit: Int = 600) {
            test.runCurrent()
            var frames = 0
            while (pinDrag.isDisplayLinkRunning && frames < limit) {
                nanos += 16_666_667
                clock.sendFrame(nanos)
                test.runCurrent()
                frames++
            }
        }
    }

    @Test
    fun cancelledPinDragLeavesPinsUnchangedAndStopsDisplayLinkAfterDismantle() = homeTest { h ->
        val sessions = listOf("a", "b", "c").mapIndexed { index, id -> session(id, id.uppercase(), date(30 - index * 10), directory = "/p") }
        val state = h.state { HomeFeature.State(server, snapshot(sessions)).updatingPinnedSessionKeys { listOf(key("a")) } }
        val hapticsLog = LockIsolated<List<Haptic>>(emptyList())
        val store = h.store(state) { this[HapticsClient] = HapticsClient(play = { hapticsLog.append(it) }) }

        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val stride = pinDrag.rowStride
        val headerH = 56f
        host.addHeader(DrawerPinDrag.ItemKey.PinnedHeader, minY = 0f, height = headerH)
        host.addRow("a", minY = headerH, height = stride)
        host.addHeader(DrawerPinDrag.ItemKey.RecentsHeader, minY = headerH + stride, height = headerH)
        host.addRow("b", minY = headerH * 2 + stride, height = stride)
        host.addRow("c", minY = headerH * 2 + stride * 2, height = stride)

        var liftedID: String? = null
        val pendingActions = ArrayList<Action>()
        fun begin() = pinDrag.beginDrag(
            sessionID = "b",
            isPinnedSection = false,
            pinnedIDs = listOf("a"),
            recentIDs = listOf("b", "c"),
            unpinnedRecentsIndex = 0,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = headerH * 2 + stride * 1.5f,
            onZoneCrossed = { pendingActions += Action.DrawerPinZoneCrossed(entered = it) },
            onSlotChanged = { pendingActions += Action.DrawerPinSlotChanged },
            onDropReleased = { id, index -> pendingActions += Action.DrawerRowDropReleased(sessionID = id, pinnedIndex = index) },
            onDrop = { id, index -> pendingActions += Action.DrawerRowDropped(sessionID = id, pinnedIndex = index) },
            onSetLifted = { liftedID = it },
        )
        begin()
        assertTrue(pinDrag.isDisplayLinkRunning)
        assertEquals("b", liftedID)

        // Drag up into the pinned section, then cancel outside the drawer.
        pinDrag.updateDrag(fingerY = headerH + 8)
        assertEquals(DrawerPinDrag.HoverTarget.Pinned(0), pinDrag.hoverTarget)

        pinDrag.cancelDrag()
        // A late completeDrop after cancellation must be a no-op.
        pinDrag.completeDrop()

        for (action in pendingActions) store.send(action)
        pendingActions.clear()

        assertEquals("A cancelled drag must not pin the dragged session", listOf("a"), store.state.pinnedSessions.map { it.id })
        assertFalse("A cancelled drag must not play the commit snap haptic", Haptic.snap in hapticsLog.value)
        assertNull(liftedID)
        frames.pump(pinDrag)
        assertFalse("Display link stops once a cancelled drag settles", pinDrag.isDisplayLinkRunning)

        // Start a second drag and dismantle the active row mid-drag (`DrawerListView`'s `dismantled`).
        begin()
        assertTrue(pinDrag.isDisplayLinkRunning)
        host.removeRow("b")
        if (pinDrag.activeSessionID == "b") pinDrag.cancelDrag()

        assertNull(pinDrag.activeSessionID)
        assertNull(liftedID)
        assertFalse("Dismantling the active row cancels the drag and stops the display link", pinDrag.isDisplayLinkRunning)
        assertEquals(listOf("a"), store.state.pinnedSessions.map { it.id })
        pinDrag.detach(host)
    }

    @Test
    fun scrolledDrawerDerivesPinAnchorsFromVisibleRowsAndSuppressesOffscreenEmptyZone() = homeTest { h ->
        val frames = Frames(this)
        val host = TestDrawerPinHost(frames.scope())
        val pinDrag = DrawerPinDrag()
        pinDrag.attach(host)
        val stride = pinDrag.rowStride
        val headerH = 56f

        // 1. Empty pins, scrolled down so the top of the list is offscreen: dragging a visible
        // recent row upward must not open the empty-pin zone.
        host.viewportTop = 240f
        host.addRow("d", minY = headerH + stride * 3, height = stride)
        pinDrag.beginDrag(
            sessionID = "d",
            isPinnedSection = false,
            pinnedIDs = emptyList(),
            recentIDs = listOf("a", "b", "c", "d"),
            unpinnedRecentsIndex = 3,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = headerH + stride * 3.5f,
            onZoneCrossed = {},
            onSlotChanged = {},
            onDrop = { _, _ -> },
            onSetLifted = {},
        )
        pinDrag.updateDrag(fingerY = 250f)
        assertEquals("Scrolled drawer must not reveal the empty-pins drop zone mid-list", DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)

        // 2. Existing pins ["p0", "p1"] where "p0" and the Pinned header are scrolled offscreen,
        // leaving only "p1" (index 1) and recents ["r0", "r1", "r2"] with "r1" (index 1) visible.
        host.items.clear()
        host.viewportTop = headerH + stride * 0.5f
        host.addRow("p1", minY = headerH + stride, height = stride)
        host.addHeader(DrawerPinDrag.ItemKey.RecentsHeader, minY = headerH + stride * 2, height = headerH)
        host.addRow("r1", minY = headerH * 2 + stride * 3, height = stride)

        pinDrag.beginDrag(
            sessionID = "p1",
            isPinnedSection = true,
            pinnedIDs = listOf("p0", "p1"),
            recentIDs = listOf("r0", "r1", "r2"),
            unpinnedRecentsIndex = 2,
            showsShortcuts = false,
            reducedMotion = false,
            fingerY = headerH + stride * 1.5f,
            onZoneCrossed = {},
            onSlotChanged = {},
            onDrop = { _, _ -> },
            onSetLifted = {},
        )
        // Hovering in slot 1 (where p1 started) lands at headerH + stride.
        val pinnedSlot1 = pinDrag.landingSlotMinY() ?: Float.NaN
        assertTrue("pinned slot 1 lands at ${headerH + stride}, not $pinnedSlot1", abs(pinnedSlot1 - (headerH + stride)) < 0.5f)

        // Dragging p1 down into Recents (unpinnedRecentsIndex = 2) derives slot 2 even when
        // neither r2 nor r1's predecessor is the first row.
        pinDrag.updateDrag(fingerY = headerH * 2 + stride * 3.5f)
        assertEquals(DrawerPinDrag.HoverTarget.Recent, pinDrag.hoverTarget)
        val recentSlot2 = pinDrag.landingSlotMinY() ?: Float.NaN
        val expectedRecentSlot2 = (headerH * 2 + stride * 4) - stride
        assertTrue("recent slot 2 lands at $expectedRecentSlot2, not $recentSlot2", abs(recentSlot2 - expectedRecentSlot2) < 0.5f)
        pinDrag.cancelDrag()
        frames.pump(pinDrag)
        pinDrag.detach(host)
    }

    // endregion

    @Test
    fun deletingSessionHidesTheRowThenPurgesLocalStateOnceConfirmed() = homeTest { h ->
        val session = session("chat", "Chat", Instant.now())
        val key = key(session.id)
        val items = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "message", text = "Cached"))
        val state = h.state {
            ServerSharedKeys.cachedTranscriptSnapshots().set(
                CachedTranscriptSnapshots(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = session.id, updatedAt = session.updatedAt, items = items))),
            )
            HomeFeature.State(server, snapshot(listOf(session), active = setOf(session.id)))
                .updatingPinnedSessionKeys { listOf(key) }
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
                .updatingSessionReadDates { it + (key to session.updatedAt) }
                .updatingManuallyUnreadSessionKeys { it + key }
                .copy(transcriptCache = mapOf(session.id to items))
        }
        val gate = Gate()
        val store = h.store(state) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(deleteSession = { _, _ -> gate.await() })
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id)) { it.copy(pendingDeletion = session) }
        // Only the row goes; phone-local records wait for the server.
        store.send(Action.DeleteSessionConfirmed) {
            it.copy(pendingDeletion = null, sessions = emptyList(), activeSessionIDs = emptySet(), recentlyDeletedIDs = setOf(session.id))
        }
        assertEquals(listOf(key), store.state.pinnedSessionKeys)
        assertTrue(store.state.pinnedSessions.isEmpty())
        assertEquals(session.id, store.state.lastViewedSessionIDs[server.persistenceID])
        assertEquals(items, store.state.transcriptCache[session.id])
        assertEquals(1, h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.size)

        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class) {
            it.copy(
                transcriptCache = emptyMap(),
                pinnedSessionKeys = emptyList(),
                lastViewedSessionIDs = it.lastViewedSessionIDs - server.persistenceID,
                sessionReadDates = it.sessionReadDates - key,
                manuallyUnreadSessionKeys = it.manuallyUnreadSessionKeys - key,
            )
        }
        assertTrue(store.state.transcriptCache.isEmpty())
        assertTrue(store.state.pinnedSessionKeys.isEmpty())
        assertNull(store.state.lastViewedSessionIDs[server.persistenceID])
        assertNull(store.state.sessionReadDates[key])
        assertFalse(key in store.state.manuallyUnreadSessionKeys)
        assertTrue(h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.isEmpty())
        store.finish()
    }

    @Test
    fun failedSessionDeletionRestoresTheRowAndBookkeeping() = homeTest { h ->
        val session = session("ses_restore", "Restore me", date(20))
        val other = session("ses_other", "Other", date(10))
        val key = key(session.id)
        val openItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "Open"))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session, other), active = setOf(session.id)))
                .copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = openItems), attentionSessionIDs = setOf(session.id))
                .updatingPinnedSessionKeys { listOf(key) }
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
                .updatingSessionReadDates { it + (key to session.updatedAt) }
        }
        val clock = TestClock()
        val gate = Gate()
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                deleteSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError("Offline")
                },
            )
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id))
        store.send(Action.DeleteSessionConfirmed)
        assertEquals(listOf(other), store.state.sessions)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals(listOf(key), store.state.pinnedSessionKeys)
        assertEquals(session.id, store.state.lastViewedSessionIDs[server.persistenceID])

        // Disk writes made while the request is in flight must survive a failure.
        val otherItems = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m2", text = "Other"))
        store.send(Action.TranscriptPrefetched(sessionID = other.id, items = otherItems))
        assertEquals(listOf(other.id), h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.map { it.sessionID })

        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class) {
            it.copy(
                sessions = listOf(session, other),
                activeSessionIDs = setOf(session.id),
                attentionSessionIDs = setOf(session.id),
                recentlyDeletedIDs = emptySet(),
                deletionError = DrawerFailure(DrawerFailure.Action.delete, "Restore me", "Offline"),
            )
        }
        assertEquals(listOf(session, other), store.state.sessions)
        assertEquals("Couldn’t delete “Restore me”", store.state.deletionError?.title)
        assertEquals(session, store.state.sessionDetail?.session)
        assertEquals(openItems, store.state.sessionDetail?.items)
        assertEquals(listOf(other.id), h.state { ServerSharedKeys.cachedTranscriptSnapshots().value }.entries.map { it.sessionID })
        assertEquals(listOf(key), store.state.pinnedSessionKeys)
        assertEquals(session.id, store.state.lastViewedSessionIDs[server.persistenceID])
        assertEquals(session.updatedAt, store.state.sessionReadDates[key])
        clock.advance(5.seconds)
        store.receive(Action.ClearDeletionError) { it.copy(deletionError = null) }
        store.finish()
    }

    @Test
    fun aSessionThatFinishesWhileOpenAfterTheDrawerSwappedItInStaysRead() = homeTest { h ->
        val first = session("ses_first", "First", date(20), idle = 1_000, viewed = 1_000)
        var second = session("ses_second", "Second", date(10), idle = 1_000, viewed = 1_000)
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(first, second), active = setOf(second.id)))
                .openingDetail(first, cachedItems = emptyList())
                // The session screen appeared once, for the first session.
                .updatingDetail { it.copy(isVisible = true) }
                .let { it.copy(transcriptCache = it.transcriptCache + (second.id to listOf(OpenCodeTranscriptItem.UserText(id = "m2", text = "Second")))) }
        }
        val secondAtStart = second
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                getSession = { _, id -> if (id == first.id) first else secondAtStart },
                transcript = { _, _, _ -> emptyList() },
                viewSession = { _, _, _ -> },
            )
        }
        store.exhaustive = false

        store.send(Action.SessionTapped(second.id))
        assertEquals(second.id, store.state.sessionDetail?.session?.id)
        assertEquals("the same screen is still showing", true, store.state.sessionDetail?.isVisible)

        // Its run finishes while the reader watches.
        second = second.copy(updatedAt = date(30), idleMilliseconds = 3_000)
        store.send(Action.SnapshotRefreshed(snapshot(listOf(first, second))))
        assertFalse(store.state.isUnread(store.state.sessions.first { it.id == second.id }))
        h.skipInFlightEffects()
    }

    @Test
    fun failedDeletionLeavesAReaderWhoMovedOnWhereTheyAre() = homeTest { h ->
        val session = session("ses_restore", "Restore me", date(20))
        val other = session("ses_other", "Other", date(10))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(session, other)))
                .copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
                .copy(transcriptCache = mapOf(other.id to listOf(OpenCodeTranscriptItem.UserText(id = "m2", text = "Other"))))
        }
        val gate = Gate()
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                deleteSession = { _, _ ->
                    gate.await()
                    throw OpenCodeClientError("Offline")
                },
            )
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id))
        store.send(Action.DeleteSessionConfirmed)
        store.send(Action.SessionTapped(other.id))
        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class)
        assertEquals(listOf(session, other), store.state.sessions)
        assertEquals(other, store.state.sessionDetail?.session)
        assertEquals(other.id, store.state.lastViewedSessionIDs[server.persistenceID])
        store.receive(Action.ClearDeletionError)
        store.finish()
    }

    @Test
    fun openAndPinnedSessionsSurviveFallingOffTheSnapshotPage() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val pinned = session("ses_pinned", "Pinned", date(20))
        val forgotten = session("ses_old", "Old", date(30))
        val newest = session("ses_new", "New", date(40))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(open, pinned, forgotten)))
                .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()))
                .updatingPinnedSessionKeys { listOf(key(pinned.id)) }
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                homeAttention = { _, _, _ -> emptySet() },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // The newest-50 page now starts with sessions created since; the three we knew have all fallen off it.
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newest))))
        assertEquals(open, store.state.sessionDetail?.session)
        assertEquals(listOf(newest.id, open.id, pinned.id), store.state.sessions.map { it.id })
        assertEquals(listOf(pinned), store.state.pinnedSessions)
        assertEquals(listOf(newest.id, open.id), store.state.recentSessions.map { it.id })

        // A real deletion still lands through its event.
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = pinned.id)))
        assertTrue(store.state.pinnedSessions.isEmpty())
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(sessionID = open.id)))
        assertEquals(true, store.state.sessionDetail?.isDraft)
        h.skipInFlightEffects()
    }

    private fun sendClient(open: OpenCodeSession) = OpenCodeClient.testValue.copy(
        homeAttention = { _, _, _ -> emptySet() },
        message = { _, _, _ -> throw OpenCodeClientError("Missing", statusCode = 404) },
        sendPrompt = { _, _, _, _, _, _, _ -> },
        waitForIdle = { _, _ -> awaitCancellation() },
        getSession = { _, _ -> open },
        pendingInputs = { _, _ -> emptyList() },
        transcript = { _, _, _ -> emptyList() },
    )

    @Test
    fun sendingMovesTheSessionToTheTopOfRecentsBeforeTheServerCatchesUp() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val newer = session("ses_newer", "Newer", date(50))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(newer, open)))
                .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()).withDraft("Ship it"))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = sendClient(open)
        }
        store.exhaustive = false
        assertEquals(listOf(newer.id, open.id), store.state.recentSessions.map { it.id })

        store.send(detail(SessionFeature.Action.SendTapped))
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
        assertFalse("The reader's own send is not unread", store.state.isUnread(store.state.sessions.first { it.id == open.id }))

        // A list fetched before the server recorded the send must not demote it.
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newer, open))))
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })

        // Once the server reports the send, the server wins again.
        val caughtUp = open.copy(updatedAt = date(120))
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newer, caughtUp))))
        assertEquals(caughtUp.updatedAt, store.state.sessions.first { it.id == open.id }.updatedAt)
        assertTrue(store.state.localActivity.isEmpty())
        h.skipInFlightEffects()
    }

    @Test
    fun everySendPathMovesTheSessionToTheTopOfRecents() {
        val paths = listOf(
            SessionFeature.Action.DeferredSendReady(OpenCodeDelivery.Steer),
            SessionFeature.Action.DeferredSendReady(OpenCodeDelivery.Queue),
            SessionFeature.Action.QueueTapped,
        )
        for (send in paths) {
            homeTest { h ->
                val open = session("ses_open", "Open", date(10))
                val newer = session("ses_newer", "Newer", date(50))
                val store = recentsSendStore(h, open, newer, SessionFeature.State.session(server, open, cachedItems = emptyList()).withDraft("Ship it"))

                store.send(detail(send))
                assertEquals("$send", listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
                h.skipInFlightEffects()
            }
        }
    }

    @Test
    fun aSendWaitingOnUploadsMovesTheSessionOnceItGoes() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val newer = session("ses_newer", "Newer", date(50))
        val file = ComposerFileAttachment(id = UUID(0, 1), name = "notes.txt", mime = "text/plain", size = 4, localPath = "/tmp/notes.txt")
        val running = SessionFeature.State.session(server, open, cachedItems = emptyList())
            .withPendingFiles(listOf(file))
            .copy(isAwaitingReply = true)
        val store = recentsSendStore(h, open, newer, running)

        store.send(detail(SessionFeature.Action.SendTapped))
        assertTrue("A send still waiting on its uploads hasn't gone", store.state.localActivity.isEmpty())
        assertEquals(listOf(newer.id, open.id), store.state.recentSessions.map { it.id })

        store.send(detail(SessionFeature.Action.fileUploadFinished(file.id, ComposerAttachmentsFeature.Outcome.Success("/tmp/opencode/uploads/notes.txt"))))
        store.skipReceivedActions()
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
        h.skipInFlightEffects()
    }

    private fun recentsSendStore(h: HomeFeatureTestSupport.Harness, open: OpenCodeSession, newer: OpenCodeSession, detail: SessionFeature.State): TestStore<HomeFeature.State, Action> {
        val state = h.state { HomeFeature.State(server, snapshot(listOf(newer, open))).copy(sessionDetail = detail) }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = sendClient(open)
        }
        store.exhaustive = false
        return store
    }

    @Test
    fun serverTimeBehindAFastPhoneClockStillSettlesTheLocalSend() = homeTest { h ->
        val open = session("ses_open", "Open", date(10))
        val newer = session("ses_newer", "Newer", date(50))
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(newer, open)))
                .copy(sessionDetail = SessionFeature.State.session(server, open, cachedItems = emptyList()).withDraft("Ship it"))
        }
        // The phone's clock runs an hour ahead of the server's.
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(3_600))
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = sendClient(open)
        }
        store.exhaustive = false

        store.send(detail(SessionFeature.Action.SendTapped))
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })

        val caughtUp = open.copy(updatedAt = date(60))
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newer, caughtUp))))
        assertEquals(caughtUp.updatedAt, store.state.sessions.first { it.id == open.id }.updatedAt)
        assertTrue(store.state.localActivity.isEmpty())
        assertEquals(listOf(open.id, newer.id), store.state.recentSessions.map { it.id })
        h.skipInFlightEffects()
    }

    @Test
    fun deletingOpenSessionReturnsToDraftAndStaleSnapshotCannotResurrectIt() = homeTest { h ->
        val session = session("ses_open", "Open", Instant.now())
        val snapshot = snapshot(listOf(session))
        val state = h.state {
            HomeFeature.State(server, snapshot)
                .copy(sessionDetail = SessionFeature.State.session(server, session, cachedItems = emptyList()))
                .updatingLastViewedSessionIDs { it + (server.persistenceID to session.id) }
        }
        val gate = Gate()
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                deleteSession = { _, _ -> gate.await() },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false

        store.send(Action.DeleteSessionTapped(session.id))
        store.send(Action.DeleteSessionConfirmed)
        assertEquals(true, store.state.sessionDetail?.isDraft)
        store.send(Action.SnapshotRefreshed(snapshot))
        assertTrue(store.state.sessions.isEmpty())
        assertEquals(setOf(session.id), store.state.recentlyDeletedIDs)
        gate.yield()
        gate.finish()
        store.receive(Action.DeleteSessionResponse::class)
        assertNull(store.state.lastViewedSessionIDs[server.persistenceID])
        store.finish()
    }

    @Test
    fun idleSnapshotRevalidatesOpenTranscriptEvenWhenAlreadyViewed() = homeTest { h ->
        val session = session("ses_resume", "Resume", date(10), idle = 2_000, viewed = 2_000)
        val snapshot = snapshot(listOf(session))
        val old = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hello"))
        val latest = old + OpenCodeTranscriptItem.AssistantMarkdown(id = "m2", text = "Finished while away")
        val state = h.state {
            HomeFeature.State(server, snapshot)
                .copy(sessionDetail = SessionFeature.State.session(server, session).copy(items = old, isLoading = false))
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> latest },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false
        store.send(Action.SnapshotRefreshed(snapshot))
        store.receiveDetail(SessionFeature.Action.RefreshResponse::class)
        assertEquals(latest, store.state.sessionDetail?.items)
        store.finish()
    }

    @Test
    fun serverViewedWatermarkWinsOverLegacyReadDatesAndDoesNotCoverNewerIdle() = homeTest { h ->
        val session = session("ses_view", "Viewed", date(10), idle = 2_000, viewed = 1_000)
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session))) })
        assertTrue(store.state.isUnread(session))
        store.send(Action.ViewAcknowledged(sessionID = session.id, idle = 1_500)) {
            it.copy(sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 1_500)))
        }
        assertTrue(store.state.isUnread(store.state.sessions[0]))
        store.send(Action.ViewAcknowledged(sessionID = session.id, idle = 2_000)) {
            it.copy(sessions = listOf(it.sessions[0].copy(viewedMilliseconds = 2_000)))
        }
        assertFalse(store.state.isUnread(store.state.sessions[0]))
        store.send(Action.MarkUnread(session.id)) {
            it.copy(
                sessionReadDates = it.sessionReadDates + (key(session.id) to session.updatedAt.minusSeconds(1)),
                manuallyUnreadSessionKeys = it.manuallyUnreadSessionKeys + key(session.id),
            )
        }
        assertTrue(store.state.isUnread(store.state.sessions[0]))
    }

    @Test
    fun staleSnapshotAndFailedReceiptDoNotResurrectOpenedUnreadSession() = homeTest { h ->
        val session = session("ses_stale", "Stale", date(10), idle = 2_000, viewed = 1_000)
        val snapshot = snapshot(listOf(session))
        val state = h.state { HomeFeature.State(server, snapshot).copy(transcriptCache = mapOf(session.id to emptyList())) }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                viewSession = { _, _, _ -> throw OpenCodeClientError("Offline") },
                homeAttention = { _, _, _ -> emptySet() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, root -> ai.opencode.server.model.OpenCodeSessionFamily(listOf(root), emptySet()) },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> ai.opencode.server.model.OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError("Offline") },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.SessionTapped(session.id))
        assertEquals(2_000L, store.state.sessions[0].viewedMilliseconds)
        store.send(Action.SnapshotRefreshed(snapshot))
        assertEquals(2_000L, store.state.sessions[0].viewedMilliseconds)
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(store.state.sessions[0]))
        h.skipInFlightEffects()
    }

    @Test
    fun visibleRunningSessionStaysReadWhenItsIdleWatermarkArrives() = homeTest { h ->
        val running = session("ses_running", "Running", date(10))
        val idle = running.copy(updatedAt = date(20), idleMilliseconds = 3_000)
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(running), active = setOf(running.id))).copy(
                transcriptCache = mapOf(running.id to emptyList()),
                sessionDetail = SessionFeature.State.session(server, running, cachedItems = emptyList(), isRunning = true)
                    .copy(isVisible = true, hasRenderedFirstContentFrame = true),
            )
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                getSession = { _, _ -> idle },
                transcript = { _, _, _ -> emptyList() },
                viewSession = { _, _, watermark -> assertEquals(3_000L, watermark) },
                homeAttention = { _, _, _ -> emptySet() },
            )
        }
        store.exhaustive = false

        store.send(Action.SnapshotRefreshed(snapshot(listOf(idle))))
        assertEquals(3_000L, store.state.sessions[0].viewedMilliseconds)
        assertEquals(HomeFeature.State.Indicator.none, store.state.indicator(store.state.sessions[0]))
        store.receiveDetail(SessionFeature.Action.ViewAcknowledged::class)
        assertEquals(3_000L, store.state.sessions[0].viewedMilliseconds)
        h.skipInFlightEffects()
    }

    @Test
    fun pinningAndReadStateAreLocalReversibleChoices() = homeTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val now = Instant.now()
        val first = OpenCodeSession(id = "ses_1", serverTitle = "First", projectID = "proj_1", directory = "/tmp/demo", updatedAt = now)
        val second = OpenCodeSession(id = "ses_2", serverTitle = "Second", projectID = "proj_1", directory = "/tmp/demo", updatedAt = now)
        val keyFirst = "${server.persistenceID}|${first.id}"
        val keySecond = "${server.persistenceID}|${second.id}"
        val state = h.state {
            HomeFeature.State(server, snapshot(listOf(first, second)))
                .updatingSessionReadDates { it + (keyFirst to first.updatedAt) + (keySecond to second.updatedAt) }
        }
        val store = h.store(state)

        store.send(Action.TogglePin(second.id)) { it.copy(pinnedSessionKeys = listOf(keySecond)) }
        assertEquals(listOf(second), store.state.pinnedSessions)
        store.send(Action.TogglePin(second.id)) { it.copy(pinnedSessionKeys = emptyList()) }
        assertEquals(setOf(first.id, second.id), store.state.visibleSessions.map { it.id }.toSet())
        store.send(Action.MarkUnread(first.id)) { it.copy(sessionReadDates = it.sessionReadDates + (keyFirst to first.updatedAt.minusSeconds(1))) }
        assertEquals(setOf(first.id), store.state.sessions.filter { store.state.isUnread(it) }.map { it.id }.toSet())
        store.send(Action.MarkRead(first.id)) { it.copy(sessionReadDates = it.sessionReadDates + (keyFirst to first.updatedAt)) }
    }

    @Test
    fun indicatorsPrioritizeAttentionThenRunningThenUnread() = homeTest { h ->
        h.state {
            val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
            val session = OpenCodeSession(id = "ses_1", serverTitle = "Work", projectID = "proj_1", directory = "/tmp/demo", updatedAt = date(2_000))
            var state = HomeFeature.State(server, snapshot(listOf(session)))
                .updatingSessionReadDates { it + ("${server.persistenceID}|${session.id}" to date(1_000)) }
            assertEquals(HomeFeature.State.Indicator.unread, state.indicator(session))
            state = state.copy(activeSessionIDs = setOf(session.id))
            assertEquals(HomeFeature.State.Indicator.running, state.indicator(session))
            state = state.copy(attentionSessionIDs = setOf(session.id))
            assertEquals(HomeFeature.State.Indicator.attention, state.indicator(session))
        }
    }

    @Test
    fun runningAndUnreadSessionsRemainInRecents() = homeTest { h ->
        h.state {
            val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
            val now = Instant.now()
            val running = OpenCodeSession(id = "ses_running", serverTitle = "Running", projectID = "proj", directory = "/tmp/demo", updatedAt = now)
            val recent = OpenCodeSession(id = "ses_recent", serverTitle = "Recent", projectID = "proj", directory = "/tmp/demo", updatedAt = now)
            var state = HomeFeature.State(server, snapshot(listOf(running, recent), active = setOf(running.id)))
                .updatingSessionReadDates { it + ("${server.persistenceID}|${running.id}" to running.updatedAt) + ("${server.persistenceID}|${recent.id}" to recent.updatedAt) }

            assertEquals(setOf(running.id, recent.id), state.recentSessions.map { it.id }.toSet())
            state = state.updatingManuallyUnreadSessionKeys { it + "${server.persistenceID}|${recent.id}" }
            assertEquals(setOf(running.id, recent.id), state.recentSessions.map { it.id }.toSet())
        }
    }

    @Test
    fun pinnedChatStaysPinnedWhileItNeedsInput() = homeTest { h ->
        val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
        val session = OpenCodeSession(id = "chat", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.now())
        val store = h.store(h.state { HomeFeature.State(server, snapshot(listOf(session))) })
        store.send(Action.TogglePin(session.id)) { it.copy(pinnedSessionKeys = listOf("${server.persistenceID}|${session.id}")) }
        store.send(Action.AttentionRefreshed(setOf(session.id))) { it.copy(attentionSessionIDs = setOf(session.id)) }
        assertEquals(listOf(session), store.state.pinnedSessions)
        assertTrue(store.state.recentSessions.isEmpty())
        assertTrue(store.state.isPinned(session.id))
        store.send(Action.AttentionRefreshed(emptySet())) { it.copy(attentionSessionIDs = emptySet()) }
        assertEquals(listOf(session), store.state.pinnedSessions)
        assertTrue(store.state.recentSessions.isEmpty())
    }
}
