package ai.opencode.feature.session

import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * `projectionCacheTailPathAndConfigurationDiffersOnlyInTailAssistantMarkdown` drops its
 * `TranscriptListViewController.Configuration.differsOnlyInTailAssistantMarkdown` half.
 */
class TranscriptBenchmarkTests {
    @Test
    fun projectionCacheTailPathRebuildsWhenRevertBoundaryChangesTail() {
        val before = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello"))
        val after = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello world"))
        val cache = SessionTranscriptView.ProjectionCache()
        var computeCalls = 0
        cache.resolveVisibleItems(items = before, visibleLimit = 50, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            before
        }
        val reverted = cache.resolveVisibleItems(items = after, visibleLimit = 50, browsingWindowStartID = null, revertMessageID = "u1") {
            computeCalls += 1
            after.take(0)
        }
        assertEquals(2, computeCalls)
        assertTrue(reverted.isEmpty())
        val cleared = cache.resolveVisibleItems(items = after, visibleLimit = 50, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            after
        }
        assertEquals(3, computeCalls)
        assertEquals(after, cleared)
    }

    @Test
    fun projectionCacheTailPathAndConfigurationDiffersOnlyInTailAssistantMarkdown() {
        val baseTime = Instant.ofEpochSecond(1_760_000_000)
        val items1 = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello"))
        val items2 = listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Prompt"), OpenCodeTranscriptItem.AssistantMarkdown(id = "a1-text-0", text = "Hello world"))

        val cache = SessionTranscriptView.ProjectionCache()
        var computeCalls = 0
        val vis1 = cache.resolveVisibleItems(items = items1, visibleLimit = 20, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            items1
        }
        val vis2 = cache.resolveVisibleItems(items = items2, visibleLimit = 20, browsingWindowStartID = null, revertMessageID = null) {
            computeCalls += 1
            items2
        }
        assertEquals("In-place tail AssistantMarkdown update must update visibleItems in place without calling compute()", 1, computeCalls)
        assertEquals(2, vis1.size)
        assertEquals(items2, vis2)

        val (entries1, _) = cache.resolveEntries(
            visibleItems = vis1, allItems = items1, showsWorking = true, failedFinale = false, models = emptyList(),
            sentNow = null, mark = null, now = baseTime,
        )
        val (entries2, _) = cache.resolveEntries(
            visibleItems = vis2, allItems = items2, showsWorking = true, failedFinale = false, models = emptyList(),
            sentNow = null, mark = null, now = baseTime,
        )
        // What the dropped `differsOnlyInTailAssistantMarkdown` compared: only the tail reply changed.
        assertEquals(entries1.size, entries2.size)
        assertEquals(entries1.dropLast(1), entries2.dropLast(1))
        assertEquals("a1-text-0", entries2.last().id)
        assertEquals(items2.last(), (entries2.last() as? TranscriptEntry.Single)?.item)
    }

}

/** The stamp half of iOS `TranscriptStreamWorkTests` (Robolectric: stamps format with `android.icu`). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptStreamWorkTests {
    /** An OpenCode ID minted at [at]: 12 hex digits of `ms × 4096`, then 14 characters. */
    private fun id(at: Instant, salt: Int): String {
        val value = (at.toEpochMilli() * 0x1000 + salt % 0x1000) and 0xFFFF_FFFF_FFFFL
        return "msg_" + String.format(Locale.ROOT, "%012x", value) + String.format(Locale.ROOT, "%014d", salt)
    }

    /** 50 turns two hours apart, the last one's reply at [lastReply]. */
    private fun history(lastReply: Instant): List<OpenCodeTranscriptItem> {
        val items = ArrayList<OpenCodeTranscriptItem>()
        for (turn in 0 until 50) {
            val time = lastReply.minusSeconds((49 - turn) * 2 * 3600L + 5)
            items += OpenCodeTranscriptItem.UserText(id = id(time, turn * 2), text = "Prompt $turn")
            items += OpenCodeTranscriptItem.AssistantMarkdown(id = id(time.plusSeconds(5), turn * 2 + 1) + "-text-0", text = "Reply $turn")
        }
        return items
    }

    /** Streams a growing reply after [prompt], one batch per call. */
    private class Stream(val history: List<OpenCodeTranscriptItem>, var prompt: String, val reply: String, val sentNow: Boolean?) {
        val cache = SessionTranscriptView.ProjectionCache()
        var text = "Hello"

        fun entries(now: Instant): List<TranscriptEntry> {
            text += " word"
            val items = history + OpenCodeTranscriptItem.UserText(id = prompt, text = "Prompt") +
                OpenCodeTranscriptItem.AssistantMarkdown(id = reply, text = text)
            return cache.resolveEntries(
                visibleItems = items, allItems = items, showsWorking = true, failedFinale = false, models = emptyList(),
                sentNow = sentNow, mark = null, now = now, zone = ZoneOffset.UTC, locale = Locale.US,
            ).first
        }
    }

    /** The seconds in [seconds] whose batch rebuilt the stamps (the first one is a stamp). */
    private fun rebuilds(stream: Stream, start: Instant, seconds: LongRange): List<Long> {
        var previous = stream.entries(start.plusSeconds(seconds.first - 1))
        assertTrue(TranscriptTimeStamps.isStamp(previous.first()))
        val result = ArrayList<Long>()
        for (second in seconds) {
            val entries = stream.entries(start.plusSeconds(second))
            if (entries.first() !== previous.first()) result += second
            previous = entries
        }
        return result
    }

    /** A new minute rebuilds the stamps once; seconds don't. */
    @Test
    fun stampsRebuildOnTheMinuteNotTheSecond() {
        val start = Instant.ofEpochSecond(1_760_000_040)
        val stream = Stream(history(start.minusSeconds(3600)), id(start.minusSeconds(1), 9998), id(start, 9999) + "-text-0", sentNow = null)
        assertEquals(listOf(60L), rebuilds(stream, start, 1L..70L))
    }

    /** An untimed last prompt reads as sent now, so its stamps follow the second until it has a time. */
    @Test
    fun anUntimedLastPromptRebuildsStampsEachSecond() {
        val start = Instant.ofEpochSecond(1_760_000_040)
        val stream = Stream(history(start.minusSeconds(3600)), "msg_local", "msg_reply-text-0", sentNow = true)
        assertEquals((1L..5L).toList(), rebuilds(stream, start, 1L..5L))
        stream.prompt = id(start, 9998)
        assertEquals(emptyList<Long>(), rebuilds(stream, start, 7L..20L))
    }

    /** A prompt from a server clock ahead of the phone's reads as now, so its pause stamp lands on the second. */
    @Test
    fun aPromptFromAServerAheadGetsItsPauseStampOnTheSecond() {
        val minute = Instant.ofEpochSecond(1_760_000_040)
        val lastReply = minute.plusSeconds(30)
        val prompt = id(minute.plusSeconds(3660), 9998)
        val stream = Stream(history(lastReply), prompt, id(minute.plusSeconds(3665), 9999) + "-text-0", sentNow = null)
        fun stamped(second: Long): Boolean = stream.entries(minute.plusSeconds(3600 + second)).any {
            TranscriptTimeStamps.isStamp(it) && it.id.endsWith(prompt)
        }
        assertEquals(false, stamped(25))
        assertEquals(false, stamped(29))
        assertEquals(true, stamped(31))
    }
}
