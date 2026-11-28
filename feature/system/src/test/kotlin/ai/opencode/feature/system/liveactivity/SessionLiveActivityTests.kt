package ai.opencode.feature.system.liveactivity

import ai.opencode.core.dependencies.TestClock
import ai.opencode.feature.system.liveactivity.SessionActivityAttributes.ContentState.Phase
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SessionLiveActivityTests {
    private typealias Content = SessionActivityAttributes.ContentState
    private typealias Run = SessionLiveActivity.Run

    private val t0 = Instant.ofEpochSecond(3_000)

    private fun working(step: String? = null) = Content(title = "Fix upload", phase = Phase.working, step = step, startedAt = t0)

    private fun reply(text: String): List<OpenCodeTranscriptItem> = listOf(
        OpenCodeTranscriptItem.UserText(id = "msg_u1", text = "Fix the upload test"),
        OpenCodeTranscriptItem.Tool(
            id = "msg_a1-tool-1",
            call = OpenCodeToolCall(
                callID = "t1", name = "read", status = OpenCodeToolCall.Status.Completed,
                input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), metadata = emptyMap(),
            ),
        ),
        OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = text),
    )

    /**
     * The transcript's Markdown parser lives above this module; the preview gets its prose lines
     * as a parameter. Paragraphs split on blank lines, fenced code dropped, as the blocks give them.
     */
    private fun proseLines(markdown: String): Sequence<String> {
        val paragraphs = mutableListOf<String>()
        var current = StringBuilder()
        var inFence = false
        for (line in markdown.lines()) {
            if (line.trimStart().startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (inFence) continue
            if (line.isBlank()) {
                if (current.isNotEmpty()) paragraphs += current.toString()
                current = StringBuilder()
            } else {
                if (current.isNotEmpty()) current.append('\n')
                current.append(line)
            }
        }
        if (current.isNotEmpty()) paragraphs += current.toString()
        return paragraphs.asSequence()
    }

    private fun preview(items: List<OpenCodeTranscriptItem>) = SessionLiveActivity.preview(items, proseLines = ::proseLines)

    @Test fun contentFollowsTheRunUntilItSettles() {
        val run = Run(title = "Renamed", isServerActive = true, isWorking = true, needsInput = false, step = "Shell")
        assertEquals(Content(title = "Renamed", phase = Phase.working, step = "Shell", startedAt = t0), SessionLiveActivity.content(run, working()))

        val asking = run.copy(needsInput = true)
        assertEquals(Phase.needsInput, SessionLiveActivity.content(asking, working(step = "Shell"))?.phase)
        assertNull(SessionLiveActivity.content(asking, working(step = "Shell"))?.step)

        val done = Run(title = "Fix upload", isServerActive = false, isWorking = false, needsInput = false, outcome = SessionLiveActivity.Outcome.done, preview = "All green")
        assertEquals(
            Content(title = "Fix upload", phase = Phase.done, preview = "All green", startedAt = t0),
            SessionLiveActivity.content(done, working(step = "Shell")),
        )

        val failed = done.copy(outcome = SessionLiveActivity.Outcome.failed)
        assertEquals(Phase.failed, SessionLiveActivity.content(failed, working())?.phase)

        val stopped = done.copy(outcome = SessionLiveActivity.Outcome.interrupted)
        assertNull("a stopped run has nothing left to follow", SessionLiveActivity.content(stopped, working()))
        assertNull(SessionLiveActivity.content(done, null))
        assertNull(SessionLiveActivity.content(null, working()))

        // Settled, it stays settled; only a late preview catches up.
        val settled = Content(title = "Fix upload", phase = Phase.done, startedAt = t0)
        assertEquals(Content(title = "Renamed", phase = Phase.done, startedAt = t0), SessionLiveActivity.content(run, settled))
        assertEquals("All green", SessionLiveActivity.content(done, settled)?.preview)
    }

    @Test fun theStepNamesTheToolOrWhatTheAgentIsDoingNeverText() {
        assertEquals(
            "Shell",
            SessionLiveActivity.step(
                listOf(
                    OpenCodeLiveContentEvent.TextStarted(messageID = "m", ordinal = 0),
                    OpenCodeLiveContentEvent.ToolInputStarted(messageID = "m", toolID = "t", name = "bash"),
                    OpenCodeLiveContentEvent.ToolInputDelta(messageID = "m", toolID = "t", delta = "ls"),
                ),
            ),
        )
        assertEquals("Thinking", SessionLiveActivity.step(listOf(OpenCodeLiveContentEvent.ReasoningStarted(messageID = "m", ordinal = 0))))
        assertEquals("Replying", SessionLiveActivity.step(listOf(OpenCodeLiveContentEvent.TextStarted(messageID = "m", ordinal = 1))))
        assertNull(SessionLiveActivity.step(listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "m", ordinal = 0, delta = "secret"))))
    }

    @Test fun thePreviewIsTheLastReplysOpeningLine() {
        assertEquals("The test raced the timer.", preview(reply("The test   raced\nthe timer.\n\nMore detail.")))
        val long = "word ".repeat(40)
        val preview = preview(reply(long))
        assertEquals(SessionLiveActivity.PREVIEW_LENGTH, preview?.length)
        assertEquals(80, SessionLiveActivity.PREVIEW_LENGTH)
        assertTrue(preview?.endsWith("…") == true)
        assertNull("a newer send has no reply yet", preview(reply("Done") + OpenCodeTranscriptItem.UserText(id = "msg_u2", text = "Next")))
        assertNull(preview(reply("```\nlet x = 1\n```")))
    }

    // MARK: Step throttle

    private val attributes = SessionActivityAttributes(serverKey = "0123456789ab", sessionID = "ses_run")
    private val start = working()

    private fun step(name: String) = working(step = name)

    private fun settled(phase: Phase) = working().copy(phase = phase, preview = if (phase == Phase.done) "Fixed it." else null)

    /** The interval's task runs inline when the clock moves. */
    private fun withThrottle(body: suspend (TestClock, MutableList<Content?>, SessionActivityStepThrottle) -> Unit) = runBlocking {
        val clock = TestClock()
        val sent = mutableListOf<Content?>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            body(clock, sent, SessionActivityStepThrottle(clock, scope) { content, _ -> sent += content })
        } finally {
            scope.cancel()
        }
    }

    @Test fun twentyQuickStepsReachTheActivityTwiceAndDoneGoesAtOnce() = withThrottle { clock, sent, throttle ->
        throttle.update(start, attributes)
        val names = listOf("Thinking", "Read", "Grep", "Glob")
        for (i in 0 until 20) throttle.update(step("${names[i % 4]} $i"), attributes)
        assertEquals(listOf(start), sent)

        clock.advance(SessionActivityStepThrottle.INTERVAL)
        assertEquals(listOf(start, step("Glob 19")), sent)

        // Done doesn't wait for the interval the last step opened.
        throttle.update(settled(Phase.done), attributes)
        assertEquals(listOf(start, step("Glob 19"), settled(Phase.done)), sent)
        clock.advance(SessionActivityStepThrottle.INTERVAL * 2)
        assertEquals(3, sent.size)
    }

    @Test fun needsInputFailedAndAnEndTakeAHeldStepsPlace() = withThrottle { clock, sent, throttle ->
        throttle.update(start, attributes)
        throttle.update(step("Read"), attributes)
        throttle.update(settled(Phase.needsInput), attributes)
        assertEquals(listOf(start, settled(Phase.needsInput)), sent)

        // Answered: working again goes at once; its next step waits.
        throttle.update(step("Edit"), attributes)
        throttle.update(step("Bash"), attributes)
        assertEquals(listOf(start, settled(Phase.needsInput), step("Edit")), sent)
        throttle.update(settled(Phase.failed), attributes)
        throttle.update(null, null)
        assertEquals(listOf(start, settled(Phase.needsInput), step("Edit"), settled(Phase.failed), null), sent)

        clock.advance(SessionActivityStepThrottle.INTERVAL * 2)
        assertEquals(5, sent.size)
    }

    @Test fun leavingSendsAHeldStepAtOnce() = withThrottle { _, sent, throttle ->
        throttle.update(start, attributes)
        throttle.update(step("Read"), attributes)
        assertEquals(listOf(start), sent)
        throttle.flush()
        assertEquals(listOf(start, step("Read")), sent)
        // Nothing held, nothing more.
        throttle.flush()
        assertEquals(2, sent.size)
    }

    @Test fun anotherRunsStartIsNeverHeld() = withThrottle { _, sent, throttle ->
        throttle.update(start, attributes)
        val other = SessionActivityAttributes(serverKey = "0123456789ab", sessionID = "ses_next")
        throttle.update(step("Read"), other)
        assertEquals(listOf(start, step("Read")), sent)
    }
}
