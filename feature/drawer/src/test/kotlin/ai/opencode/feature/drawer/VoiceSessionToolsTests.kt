package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.VoiceHomeTestSupport.live
import ai.opencode.feature.drawer.VoiceHomeTestSupport.voiceEvent
import ai.opencode.feature.drawer.VoiceHomeTestSupport.withCall
import ai.opencode.feature.voice.VoiceCallEvent
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSessionBoard
import ai.opencode.feature.voice.VoiceSessionBoardClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class VoiceSessionToolsTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:4096", username = "", password = "")
    private val math = OpenCodeSession(id = "ses_math", serverTitle = "Math bug", projectID = "math", directory = "/Users/kit/math", updatedAt = Instant.ofEpochSecond(100))

    @Test
    fun homePublishesTheDrawerStateOnlyDuringACall() = homeTest { h ->
        val waiting = OpenCodeSession(id = "ses_wait", serverTitle = "Waiting", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.ofEpochSecond(50))
        h.setShared(SharedKeys.pinnedSessionKeys, listOf("${server.persistenceID}|${math.id}"))
        val home = h.state {
            var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(waiting, math), activeSessionIDs = setOf(waiting.id)))
            assertNull(home.voiceSessionBoard)
            home = home.openingDetail(waiting, cachedItems = emptyList())
            assertNull(home.voiceSessionBoard)
            home.withCall(VoiceFeature.State(server, waiting).live())
        }
        val board: VoiceSessionBoard = requireNotNull(h.state { home.voiceSessionBoard })
        assertEquals(setOf(waiting.id), board.busyIDs)
        assertEquals(listOf(math.id), board.pinnedIDs)
        assertTrue(board.attentionDirectories.contains(waiting.directory))

        val client = VoiceSessionBoardClient.inMemory()
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[VoiceSessionBoardClient] = client
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.AttentionRefreshed(setOf(waiting.id)))
        val published = requireNotNull(client.current(server.persistenceID))
        assertEquals(setOf(waiting.id), published.attentionIDs)
        assertEquals(VoiceSessionBoard.Flag.yes, published.needsInput(waiting, running = true))
        // Until Home's own attention read succeeds, other running sessions are unknown.
        val other = waiting.copy(id = "ses_other")
        assertEquals(VoiceSessionBoard.Flag.unknown, published.needsInput(other, running = true))
        client.attentionRead(server.persistenceID, true)
        assertEquals(VoiceSessionBoard.Flag.no, client.current(server.persistenceID)?.needsInput(other, running = true))
        client.attentionRead(server.persistenceID, false)
        assertEquals(VoiceSessionBoard.Flag.unknown, client.current(server.persistenceID)?.needsInput(other, running = true))
        assertNull(client.current("another server"))
        h.skipInFlightEffects()
    }

    /**
     * The board is built only when what it's built from changes: the voice agent's caption deltas,
     * 20–30 a second while it speaks, never rebuild the drawer's status.
     */
    @Test
    fun captionDeltasNeverRebuildTheBoard() = homeTest { h ->
        val waiting = OpenCodeSession(id = "ses_wait", serverTitle = "Waiting", projectID = "p", directory = "/Users/kit/project", updatedAt = Instant.ofEpochSecond(50))
        val home = h.state {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(waiting, math), activeSessionIDs = setOf(waiting.id, "ses_child")))
                .openingDetail(waiting, cachedItems = emptyList())
                .withCall(VoiceFeature.State(server, waiting).live())
        }
        val published = ArrayList<VoiceSessionBoard?>()
        val store = h.store(home) {
            this[Clock] = ImmediateClock()
            this[VoiceSessionBoardClient] = VoiceSessionBoardClient(publish = { published += it }, attentionRead = { _, _ -> }, current = { null })
        }
        store.exhaustive = false

        for (index in 0 until 200) {
            store.send(voiceEvent(VoiceCallEvent.AssistantDelta(itemID = "a1", delta = "word$index ")))
        }
        assertTrue("No delta changed what the board is built from", published.isEmpty())

        // What it is built from still rebuilds it, once each.
        store.send(HomeFeature.Action.AttentionRefreshed(setOf(waiting.id)))
        assertEquals(1, published.size)
        assertEquals(setOf(waiting.id), published.last()?.attentionIDs)
        store.send(voiceEvent(VoiceCallEvent.AssistantDelta(itemID = "a1", delta = "more")))
        assertEquals(1, published.size)
        store.send(HomeFeature.Action.AttentionRefreshed(emptySet()))
        assertEquals(2, published.size)
        assertEquals(emptySet<String>(), published.last()?.attentionIDs)
        h.skipInFlightEffects()

        val key = "${server.persistenceID}|${math.id}"
        val base = home.copy(sessionReadDates = mapOf(key to Instant.ofEpochSecond(200)))
        val detail = base.sessionDetail!!
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Child", projectID = "math", directory = math.directory, updatedAt = Instant.ofEpochSecond(60), parentID = math.id)
        val changes = listOf(
            base.copy(attentionSessionIDs = setOf(math.id)),
            base.copy(activeSessionIDs = setOf(math.id)),
            base.copy(activeSessionParents = mapOf("ses_child" to math.id)),
            base.copy(sessionDetail = detail.copy(familySessions = listOf(child))),
            base.copy(sessionDetail = detail.copy(session = math)),
            base.copy(sessionReadDates = emptyMap()),
            base.copy(manuallyUnreadSessionKeys = setOf(key)),
            base.copy(mutedSessionKeys = setOf("${server.persistenceID}|${waiting.id}")),
            base.copy(pinnedSessionKeys = listOf(key)),
            base.copy(sessions = listOf(waiting)),
            base.copy(connection = base.connection.copy(connectionStatus = HomeConnectionFeature.State.ConnectionStatus.disconnected)),
            base.copy(connection = base.connection.copy(server = OpenCodeServer(url = "http://localhost:4097", username = "", password = ""))),
        )
        h.state {
            for (changed in changes) {
                assertNotEquals(base.voiceSessionBoard, changed.voiceSessionBoard)
                assertNotEquals(base.voiceSessionBoardInput, changed.voiceSessionBoardInput)
            }
        }
        val board = h.state { base.voiceSessionBoard }
        val input = h.state { base.voiceSessionBoardInput }
        h.setShared(SharedKeys.developerMode, true)
        h.state {
            assertNotEquals(board, base.voiceSessionBoard)
            assertNotEquals(input, base.voiceSessionBoardInput)
        }
    }
}
