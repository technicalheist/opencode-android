package ai.opencode.feature.transcript.user

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.testing.WireFixture
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Base64

class PastedTextTests {
    @get:Rule
    val isolation = TestIsolationRule()

    private val log = (1..30).joinToString("\n") { "2026-10-09 12:00:${"%02d".format(it)} worker[$it] retrying upload" }

    // PastedTextRoundTripTests

    @Test
    fun theStoredMessageComesBackAsACard() {
        // As V2 stores it: `materializeAttachment` keeps the bytes inline and detects `text/plain`.
        val data = Base64.getEncoder().encodeToString(log.toByteArray())
        val items = OpenCodeClient.decodeTranscript(
            """
            {"data":[{"id":"msg_1","type":"user","text":"Why does this loop?",
              "files":[{"data":"$data","mime":"text/plain","source":{"type":"inline"},"name":"Pasted Text"}]}]}
            """.trimIndent().toByteArray(),
        )
        assertEquals(
            listOf(
                OpenCodeTranscriptItem.UserFiles(
                    "msg_1-files",
                    listOf(OpenCodeTranscriptFile(id = "msg_1-file-inline-0", name = "Pasted Text", mime = "text/plain", text = log)),
                ),
                OpenCodeTranscriptItem.UserText("msg_1", "Why does this loop?"),
            ),
            items,
        )
        val message = TranscriptEntry.build(items).firstOrNull()?.let(TranscriptUserMessage::from)
        assertNotNull(message)
        assertEquals(listOf(log), message!!.files.map { it.text })
        assertEquals("Why does this loop?", message.displayText)
    }

    @Test
    fun theInboxListsAPendingPasteAsACard() = runTest {
        val data = Base64.getEncoder().encodeToString(log.toByteArray())
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/session/ses_1/inbox" -> WireFixture.Reply.Json(
                    200,
                    """
                    {"data":[{"id":"msg_1","type":"user","delivery":"steer","payload":{"text":"",
                      "files":[{"data":"$data","mime":"text/plain","source":{"type":"inline"},"name":"Pasted Text"}]}}]}
                    """.trimIndent(),
                )
                else -> WireFixture.Reply.status(404)
            }
        }
        try {
            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_1")
            assertEquals(
                listOf(OpenCodeTranscriptFile(id = "msg_1-file-inline-0", name = "Pasted Text", mime = "text/plain", text = log)),
                inputs.firstOrNull()?.inlineFiles,
            )
            val message = inputs.firstOrNull()?.let { TranscriptUserMessage.from(OpenCodeQueuedInput(it)) }
            assertNotNull(message)
            assertEquals(listOf(log), message!!.files.map { it.text })
        } finally {
            fixture.close()
        }
    }

    // UserBubbleFoldTests

    /** The body style at the default font scale: 22 sp lines, 2 sp apart, at density 1. */
    private val metrics = UserBubbleFold.Metrics(lineHeight = 22f, lineSpacing = UserBubbleFold.lineSpacing)

    private fun lines(count: Int): String = (1..count).joinToString("\n") { "Line $it" }

    @Test
    fun foldsPastTwelveLines() {
        assertFalse(UserBubbleFold.folds(metrics.height(12), metrics))
        assertTrue(UserBubbleFold.folds(metrics.height(13), metrics))
        assertTrue(metrics.folded < metrics.height(9))
        assertTrue(metrics.folded > metrics.height(8))
    }

    @Test
    fun closedLinesStillFold() {
        // At density 1 and the bubble's own line height, less one line for trimmed leading.
        val body = UserBubbleFold.Metrics(OpenCodeTextStyles.body.lineHeight.value, UserBubbleFold.lineSpacing)
        val closed = (UserBubbleFold.closedLines - 1) * UserBubbleTextStyle.lineHeight.value
        assertTrue(UserBubbleFold.folds(closed, body))
        assertTrue(closed > body.folded)
        assertTrue(UserBubbleFold.closedLines < 20)
    }

    @Test
    fun copyingASentUserMessageCopiesItsFullText() {
        val foldedText = lines(30)
        val entries = TranscriptEntry.build(
            listOf(
                OpenCodeTranscriptItem.UserText("msg_1", "Check the upload retry schedule."),
                OpenCodeTranscriptItem.UserFiles(
                    "msg_2-files",
                    listOf(OpenCodeTranscriptFile(id = "msg_2-file-0", name = "Pasted Text", mime = "text/plain", text = "stack trace")),
                ),
                OpenCodeTranscriptItem.UserText("msg_2", foldedText),
            ),
        )
        val messages = entries.mapNotNull(TranscriptUserMessage::from)
        assertEquals(listOf("Check the upload retry schedule.", foldedText), messages.map { it.displayText })
        assertEquals(1, messages.last().files.size)
    }
}
