package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor
import java.time.Instant

class TranscriptCachePersistenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val decoder = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val distantPast: Instant = Instant.parse("0001-01-01T00:00:00Z")

    /** `Date(timeIntervalSince1970:)`. */
    private fun date(seconds: Number): Instant = Instant.ofEpochMilli(Math.round(seconds.toDouble() * 1_000))

    @Test
    fun splicedEnvelopeMatchesASingleFullEncode() {
        val entries = (0 until 3).map { index ->
            CachedTranscriptSnapshots.Entry(
                serverID = "server",
                sessionID = "ses_$index",
                updatedAt = date(index),
                items = listOf(
                    OpenCodeTranscriptItem.UserText(id = "u_$index", text = "Hello \"$index\""),
                    OpenCodeTranscriptItem.AssistantMarkdown(id = "a_$index", text = "**Hi**\n\n```swift\nlet n = $index\n```"),
                    OpenCodeTranscriptItem.Compaction(id = "c_$index", info = OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed, summary = "## Kept")),
                ),
            )
        }
        for (snapshot in listOf(CachedTranscriptSnapshots(), CachedTranscriptSnapshots(entries = entries))) {
            val data = requireNotNull(snapshot.encodedForPersistence())
            val full = CachedTranscriptSnapshots.persistenceJson.encodeToString(CachedTranscriptSnapshots.serializer(), snapshot).toByteArray()
            assertEquals(full.size, data.size)
            assertEquals(
                decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), full.decodeToString()),
                decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString()),
            )
        }
    }

    @Test
    fun transcriptSnapshotRoundTripsAndInvalidatesWhenSessionChanges() {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val updatedAt = date(1_000)
        val session = OpenCodeSession(id = "ses_cached", serverTitle = "Cached", projectID = "p", directory = "/p", updatedAt = updatedAt)
        val items = listOf(
            OpenCodeTranscriptItem.UserText(id = "m1", text = "hello"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "m2", text = "**Hi**\n\n```swift\nlet n = 1\n```"),
            OpenCodeTranscriptItem.Compaction(id = "m3"),
        )
        val cache = CachedTranscriptSnapshots().replaceEntries(
            server.persistenceID,
            listOf(CachedTranscriptSnapshots.Entry(serverID = server.persistenceID, sessionID = session.id, updatedAt = updatedAt, items = items)),
        )
        val restored = decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), requireNotNull(cache.encodedForPersistence()).decodeToString())
        val restoredItems = requireNotNull(restored.validItems(server.persistenceID, listOf(session))[session.id])
        assertEquals(items.map { it.id }, restoredItems.map { it.id })
        assertEquals(listOf("hello"), restoredItems.mapNotNull { (it as? OpenCodeTranscriptItem.UserText)?.text })

        val changed = session.copy(updatedAt = updatedAt.plusSeconds(1))
        assertEquals(items.map { it.id }, restored.validItems(server.persistenceID, listOf(changed))[session.id]?.map { it.id })
        assertTrue(restored.validItems(server.persistenceID, emptyList()).isEmpty())
        assertTrue(restored.validItems("another-server", listOf(session)).isEmpty())
    }

    @Test
    fun transcriptPersistenceCoalescesAndKeepsProtectedFormat() {
        val memory = TranscriptCachePersistence.Storage.Memory()
        val storage = TranscriptCachePersistence.Storage.inMemory(memory)
        val persistence = TranscriptCachePersistence.make(storage)
        val first = CachedTranscriptSnapshots(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "first", updatedAt = distantPast, items = emptyList())))
        val second = CachedTranscriptSnapshots(entries = listOf(CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "second", updatedAt = date(1_000), items = emptyList())))

        persistence.schedule(first)
        persistence.schedule(second)
        persistence.flush()

        assertEquals(second, persistence.load())
        assertEquals(second, decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), requireNotNull(storage.data()).decodeToString()))
    }

    @Test
    fun transcriptPersistenceBoundsLargeSnapshotsBeforeItsSingleFinalEncode() {
        val payload = "x".repeat(600_000)
        val entries = (0 until CachedTranscriptSnapshots.MAXIMUM_ENTRIES).map { index ->
            CachedTranscriptSnapshots.Entry(
                serverID = "server",
                sessionID = "ses_$index",
                updatedAt = date(index),
                items = listOf(OpenCodeTranscriptItem.UserText(id = "m_$index", text = payload)),
            )
        }
        val snapshot = CachedTranscriptSnapshots(entries = entries)
        val data = requireNotNull(snapshot.encodedForPersistence())
        val decoded = decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString())

        assertTrue(data.size <= CachedTranscriptSnapshots.MAXIMUM_BYTES)
        assertTrue(decoded.entries.size < entries.size)
        assertEquals(entries.first().sessionID, decoded.entries.firstOrNull()?.sessionID)
    }

    @Test
    fun transcriptPersistenceSkipsAnOversizedEntryWithoutEncodingAllOfIt() {
        val text = "x".repeat(16_000_000)
        val items = (0 until 64).map { index -> OpenCodeTranscriptItem.Info(id = "m_$index", text = text) }
        val entry = CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "ses_big", updatedAt = date(1), items = items)
        val data = requireNotNull(CachedTranscriptSnapshots(entries = listOf(entry)).encodedForPersistence())
        val decoded = decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString())

        assertTrue(decoded.entries.isEmpty())
    }

    @Test
    fun transcriptPersistenceSkipsAnOversizedImageWithoutEncodingIt() {
        val image = OpenCodeImageAttachment(id = "img", data = ByteArray(120_000_000), mime = "image/png")
        val items = listOf(OpenCodeTranscriptItem.UserImages(id = "m_images", images = listOf(image)))
        val entry = CachedTranscriptSnapshots.Entry(serverID = "server", sessionID = "ses_big", updatedAt = date(1), items = items)
        val data = requireNotNull(CachedTranscriptSnapshots(entries = listOf(entry)).encodedForPersistence())
        val decoded = decoder.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString())

        assertTrue(decoded.entries.isEmpty())
    }

    private fun prefetchSnapshot(text: String) = CachedTranscriptSnapshots(
        entries = listOf(
            CachedTranscriptSnapshots.Entry(
                serverID = "server",
                sessionID = "open",
                updatedAt = date(1_800_000_000),
                items = listOf(OpenCodeTranscriptItem.UserText(id = "m", text = text)),
            ),
        ),
    )

    /** Holds the prefetch's read until the test runs it, so each case reads at a known moment. */
    private class HeldReads : Executor {
        val pending = mutableListOf<Runnable>()

        override fun execute(command: Runnable) {
            pending += command
        }

        fun runAll() {
            val runs = pending.toList()
            pending.clear()
            for (run in runs) run.run()
        }
    }

    @Test
    fun aPrefetchedReadMatchesADirectOneAndIsTakenOnce() {
        val memory = TranscriptCachePersistence.Storage.Memory()
        memory.value = prefetchSnapshot("first").encodedForPersistence()
        val reads = HeldReads()
        val persistence = TranscriptCachePersistence.make(TranscriptCachePersistence.Storage.inMemory(memory), prefetchOn = reads)
        persistence.prefetch()
        reads.runAll()
        memory.value = prefetchSnapshot("second").encodedForPersistence()
        assertEquals("The first load takes the prefetched read", prefetchSnapshot("first"), persistence.load())
        assertEquals("Later loads read the file again", prefetchSnapshot("second"), persistence.load())
        persistence.prefetch()
        assertTrue("Only the first load is prefetched", reads.pending.isEmpty())
        assertEquals(prefetchSnapshot("second"), persistence.load())
    }

    @Test
    fun aPrefetchNeverHandsBackAnOlderOrEmptierCache() {
        val written = TranscriptCachePersistence.Storage.Memory()
        written.value = prefetchSnapshot("old").encodedForPersistence()
        val writtenReads = HeldReads()
        val rewritten = TranscriptCachePersistence.make(TranscriptCachePersistence.Storage.inMemory(written), prefetchOn = writtenReads)
        rewritten.prefetch()
        writtenReads.runAll()
        rewritten.schedule(prefetchSnapshot("new"))
        rewritten.flush()
        assertEquals(prefetchSnapshot("new"), rewritten.load())

        val removed = TranscriptCachePersistence.Storage.Memory()
        removed.value = prefetchSnapshot("old").encodedForPersistence()
        val removedReads = HeldReads()
        val cleared = TranscriptCachePersistence.make(TranscriptCachePersistence.Storage.inMemory(removed), prefetchOn = removedReads)
        cleared.prefetch()
        removedReads.runAll()
        cleared.remove()
        assertNull(cleared.load())

        val late = TranscriptCachePersistence.Storage.Memory()
        val lateReads = HeldReads()
        val unlocked = TranscriptCachePersistence.make(TranscriptCachePersistence.Storage.inMemory(late), prefetchOn = lateReads)
        unlocked.prefetch()
        lateReads.runAll()
        late.value = prefetchSnapshot("readable").encodedForPersistence()
        assertEquals(prefetchSnapshot("readable"), unlocked.load())
    }
}
