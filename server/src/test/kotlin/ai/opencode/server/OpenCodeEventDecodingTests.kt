package ai.opencode.server

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeLiveContentEvent.ReasoningDelta
import ai.opencode.server.OpenCodeLiveContentEvent.ReasoningEnded
import ai.opencode.server.OpenCodeLiveContentEvent.TextDelta
import ai.opencode.server.OpenCodeLiveContentEvent.TextEnded
import ai.opencode.server.OpenCodeLiveContentEvent.ToolInputDelta
import ai.opencode.server.live.decodeStreamFrame
import ai.opencode.server.live.decodeTranscriptPage
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolBounds
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.time.Instant

class OpenCodeEventDecodingTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun decodesSanitizedLiveSessionContract() {
        val lines = """
            {"type":"server.connected","data":{}}
            {"type":"session.step.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture"}}
            {"type":"session.text.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0}}
            {"type":"session.text.delta","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"delta":"Hel"}}
            {"type":"session.text.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"text":"Hello"}}
            {"type":"session.reasoning.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0}}
            {"type":"session.reasoning.delta","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"delta":"Why"}}
            {"type":"session.reasoning.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","ordinal":0,"text":"Why"}}
            {"type":"session.tool.input.started","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","name":"shell"}}
            {"type":"session.tool.input.delta","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","delta":"{}"}}
            {"type":"session.tool.input.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","text":"{}"}}
            {"type":"session.tool.called","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","input":{},"executed":false}}
            {"type":"session.tool.progress","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","metadata":{"state":"running"}}}
            {"type":"session.tool.success","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","content":[{"type":"text","text":"done"}],"executed":false}}
            {"type":"session.tool.failed","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture","id":"tool_fixture","error":{"message":"failed"},"executed":false}}
            {"type":"session.step.streamed","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture"}}
            {"type":"session.step.ended","data":{"sessionID":"ses_fixture","assistantMessageID":"msg_fixture"}}
            {"type":"session.inbox.enqueued","data":{"sessionID":"ses_fixture","inboxID":"msg_input","item":{"type":"user"}}}
            {"type":"session.inbox.delivered","data":{"sessionID":"ses_fixture","inboxID":"msg_input"}}
        """.trimIndent().split("\n")

        val events = lines.mapNotNull(::decodeStreamFrame)
        assertEquals(lines.size, events.size)
        assertEquals(OpenCodeServerEvent.Connected, events.first())
        assertTrue(events.any { it is OpenCodeServerEvent.InputEnqueued && it.sessionID == "ses_fixture" })
        val content = events.flatMap { (it as? OpenCodeServerEvent.ContentBatch)?.events ?: emptyList() }
        assertEquals(16, content.size)
        assertTrue(TextEnded("msg_fixture", 0, "Hello") in content)
        assertTrue(OpenCodeLiveContentEvent.ToolSucceeded("msg_fixture", "tool_fixture", listOf("done"), emptyMap()) in content)
    }

    /**
     * V2's `session.created`, `session.forked` and `session.viewed` frames
     * (packages/schema/src/session-event.ts), as `GET /api/event` sends them.
     */
    @Test
    fun sessionLifecycleFramesCarryAncestryAndTheViewedWatermark() {
        assertEquals(
            OpenCodeServerEvent.SessionCreated("ses_child", "ses_root"),
            decodeStreamFrame("""{"type":"session.created","data":{"sessionID":"ses_child","projectID":"p","location":{"directory":"/p"},"parentID":"ses_root","slug":"s","version":"2"}}"""),
        )
        assertEquals(
            OpenCodeServerEvent.SessionCreated("ses_root", null),
            decodeStreamFrame("""{"type":"session.created","data":{"sessionID":"ses_root","projectID":"p","location":{"directory":"/p"},"slug":"s","version":"2"}}"""),
        )
        // A fork is a new root: its parentID is the session it was copied from.
        assertEquals(
            OpenCodeServerEvent.SessionCreated("ses_fork", null),
            decodeStreamFrame("""{"type":"session.forked","data":{"sessionID":"ses_fork","parentID":"ses_source","boundary":{"type":"through","messageID":"msg_1"}}}"""),
        )
        assertEquals(
            OpenCodeServerEvent.SessionViewChanged("ses_root", 1789440123456),
            decodeStreamFrame("""{"type":"session.viewed","data":{"sessionID":"ses_root","idle":1789440123456}}"""),
        )
        assertNull(decodeStreamFrame("""{"type":"session.viewed","data":{"sessionID":"ses_root"}}"""))
        assertEquals(
            OpenCodeServerEvent.SessionArchiveChanged("ses_root", isArchived = true),
            decodeStreamFrame("""{"type":"session.archived","data":{"sessionID":"ses_root"}}"""),
        )
        assertEquals(
            OpenCodeServerEvent.SessionArchiveChanged("ses_root", isArchived = false),
            decodeStreamFrame("""{"type":"session.unarchived","data":{"sessionID":"ses_root"}}"""),
        )
    }

    /**
     * `session.moved` (Move to, or the server reviving a session whose folder
     * is gone) carries where the session lives now.
     */
    @Test
    fun sessionMovedFramesCarryTheNewFolder() {
        assertEquals(
            OpenCodeServerEvent.SessionMoved("ses_1", "/work/repo"),
            decodeStreamFrame("""{"type":"session.moved","data":{"sessionID":"ses_1","location":{"directory":"/work/repo","workspaceID":"w"},"projectID":"p"}}"""),
        )
        // Without a folder it still settles the session's messages from REST.
        assertEquals(
            OpenCodeServerEvent.MessageInvalidated("ses_1"),
            decodeStreamFrame("""{"type":"session.moved","data":{"sessionID":"ses_1","location":{},"projectID":"p"}}"""),
        )
        // Another event's odd location never spoils its payload.
        assertEquals(
            OpenCodeServerEvent.SessionRenamed("ses_1", "T"),
            decodeStreamFrame("""{"type":"session.renamed","data":{"sessionID":"ses_1","title":"T","location":"/p"}}"""),
        )
    }

    @Test
    fun shellLifecycleFramesNameTheOwnerAndEnd() {
        val created = """{"type":"shell.created","location":{"directory":"/repo"},"data":{"info":{"id":"sh_1","status":"running","command":"bun dev\n--port 3000","cwd":"/repo","shell":"/bin/zsh","file":"/tmp/sh_1.out","pid":4,"metadata":{"sessionID":"ses_1"},"time":{"started":1}}}}"""
        val user = """{"type":"shell.created","data":{"info":{"id":"sh_2","status":"running","command":"ls","cwd":"/repo","shell":"/bin/zsh","file":"/tmp/sh_2.out","metadata":{"sessionID":"ses_1","background":true},"time":{"started":1}}}}"""
        val started = decodeStreamFrame(created)
        assertEquals(
            OpenCodeServerEvent.ShellStarted(OpenCodeShell(id = "sh_1", command = "bun dev\n--port 3000", sessionID = "ses_1", startedAt = Instant.ofEpochMilli(1))),
            started,
        )
        assertEquals("bun dev", (started as OpenCodeServerEvent.ShellStarted).shell.title)
        assertEquals(
            OpenCodeServerEvent.ShellStarted(
                OpenCodeShell(id = "sh_2", command = "ls", sessionID = "ses_1", isUserCommand = true, startedAt = Instant.ofEpochMilli(1)),
            ),
            decodeStreamFrame(user),
        )
        for (type in listOf("shell.exited", "shell.deleted")) {
            val frame = """{"type":"$type","location":{"directory":"/repo"},"data":{"id":"sh_1","exit":0,"status":"exited"}}"""
            assertEquals(OpenCodeServerEvent.ShellEnded("sh_1"), decodeStreamFrame(frame))
        }
    }

    @Test
    fun jobUpdatedFramesDecodeEveryKind() {
        fun frame(info: String) = """{"type":"experimental.job.updated","data":{"info":$info}}"""
        val time = """"time":{"started":7}"""
        val cases = listOf(
            """{"kind":"shell","id":"j1","sessionID":"s","status":"running","shellID":"sh","command":"ls",$time}""" to OpenCodeJob.Kind.Shell,
            """{"kind":"subagent","id":"j2","sessionID":"s","status":"running","childSessionID":"c","agent":"a","description":"d",$time}""" to OpenCodeJob.Kind.Subagent,
            """{"kind":"image","id":"j3","sessionID":"s","status":"running","path":"/p","model":"m",$time}""" to OpenCodeJob.Kind.Image,
            """{"kind":"video","id":"j4","sessionID":"s","status":"running","path":"/p",$time}""" to OpenCodeJob.Kind.Video,
            """{"kind":"codemode","id":"j5","sessionID":"s","status":"running",$time}""" to OpenCodeJob.Kind.CodeMode,
        )
        for ((info, kind) in cases) {
            val job = (decodeStreamFrame(frame(info)) as OpenCodeServerEvent.JobUpdated).job
            assertEquals(kind, job.kind)
            assertTrue(job.isRunning)
            assertEquals(Instant.ofEpochMilli(7), job.startedAt)
        }
        val child = (decodeStreamFrame(frame(cases[1].first)) as OpenCodeServerEvent.JobUpdated).job
        assertEquals("c", child.ownerSessionID)
        for (status in listOf("completed", "error", "cancelled")) {
            val ended = decodeStreamFrame(frame("""{"kind":"codemode","id":"j5","sessionID":"s","status":"$status",$time}"""))
            assertEquals(OpenCodeServerEvent.JobUpdated(OpenCodeJob(id = "j5", kind = OpenCodeJob.Kind.CodeMode, sessionID = "s", isRunning = false, startedAt = Instant.ofEpochMilli(7))), ended)
        }
        assertNull(decodeStreamFrame("""{"type":"experimental.job.updated","data":{"info":{"id":"j"}}}"""))
    }

    @Test
    fun streamFramesDecodeOnceAndThrowOnServerFailure() {
        // The bytes after `data:` keep their leading space; JSON tolerates it.
        val frame = """ {"type":"session.text.delta","data":{"sessionID":"s","assistantMessageID":"m","ordinal":0,"delta":"Hi"}}"""
        assertEquals(OpenCodeServerEvent.ContentBatch("s", listOf(TextDelta("m", 0, "Hi"))), decodeStreamFrame(frame))
        assertNull(decodeStreamFrame("""{"type":"unknown.event"}"""))
        assertNull(decodeStreamFrame("not json"))

        val failure = OpenCodeClientError("The event stream reported a server failure.")
        for (failed in listOf("""{"_tag":"Die","cause":{}}""", """{"type":"server.connected","_tag":"Fail"}""")) {
            try {
                decodeStreamFrame(failed)
                fail("Expected $failed to throw")
            } catch (error: OpenCodeClientError) {
                assertEquals(failure, error)
            }
        }
    }

    @Test
    fun liveToolPayloadsAreBoundedLikeTheSettledRead() {
        val big = "0123456789\n".repeat(20_000)
        val bigJSON = JsonPrimitive(big).toString()
        val content = """[{"type":"text","text":$bigJSON}]"""
        val metadata = """{"diff":$bigJSON,"title":"ok"}"""
        val input = """{"command":$bigJSON}"""
        fun live(type: String, data: String): OpenCodeLiveContentEvent? {
            val frame = """{"type":"$type","data":{"sessionID":"s","assistantMessageID":"m","id":"t",$data}}"""
            return (decodeStreamFrame(frame) as? OpenCodeServerEvent.ContentBatch)?.events?.firstOrNull()
        }
        val state = """{"status":"completed","input":$input,"content":$content,"metadata":$metadata}"""
        val page = decodeTranscriptPage(
            """{"data":[{"id":"m","type":"assistant","content":[{"type":"tool","id":"t","name":"shell","state":$state}]}]}""".toByteArray(),
            null,
        )
        val settled = (page.items.first() as OpenCodeTranscriptItem.Tool).call
        val succeeded = live("session.tool.success", """"content":$content,"metadata":$metadata""") as OpenCodeLiveContentEvent.ToolSucceeded
        val called = live("session.tool.called", """"input":$input""") as OpenCodeLiveContentEvent.ToolCalled
        val inputEnded = live("session.tool.input.ended", """"text":$bigJSON""") as OpenCodeLiveContentEvent.ToolInputEnded
        assertEquals(settled.output, succeeded.output)
        assertEquals(settled.metadata, succeeded.metadata)
        assertEquals(settled.input, called.input)
        assertEquals(OpenCodeJSONValue.Str("ok"), succeeded.metadata["title"])
        val truncated = OpenCodeToolBounds.TEXT_LIMIT + "\n… output truncated".toByteArray().size
        assertEquals(listOf(truncated), succeeded.output.map { it.toByteArray().size })
        assertEquals(truncated, inputEnded.text.toByteArray().size)
    }

    @Test
    fun reasoningFramesDecodeWithoutTheirText() {
        val thinking = "hmm ".repeat(10_000)
        val cases = listOf(
            Triple("session.reasoning.delta", "delta", ReasoningDelta("m", 2)),
            Triple("session.reasoning.ended", "text", ReasoningEnded("m", 2)),
        )
        for ((type, key, event) in cases) {
            val frame = "{\"type\":\"$type\",\"data\":{\"sessionID\":\"s\",\"assistantMessageID\":\"m\",\"ordinal\":2,\"$key\":\"$thinking\"}}"
            assertEquals(OpenCodeServerEvent.ContentBatch("s", listOf(event)), decodeStreamFrame(frame))
        }
    }

    @Test
    fun adjacentDeltasForOnePartCoalesceWithoutReordering() {
        val pending = mutableListOf<OpenCodeLiveContentEvent>()
        pending.appendCoalescingDeltas(
            listOf(
                TextDelta("m", 0, "Hel"),
                TextDelta("m", 0, "lo"),
                TextDelta("m", 1, "Other part"),
                ReasoningDelta("m", 0),
                ReasoningDelta("m", 0),
                ToolInputDelta("m", "t", "{\"a\""),
            ),
        )
        pending.appendCoalescingDeltas(
            listOf(
                ToolInputDelta("m", "t", ":1}"),
                ToolInputDelta("m", "u", "{}"),
                TextDelta("m", 0, "!"),
                TextEnded("m", 0, "Hello!"),
                TextDelta("m", 0, "late"),
            ),
        )

        assertEquals(
            listOf(
                TextDelta("m", 0, "Hello"),
                TextDelta("m", 1, "Other part"),
                ReasoningDelta("m", 0),
                ToolInputDelta("m", "t", "{\"a\":1}"),
                ToolInputDelta("m", "u", "{}"),
                TextDelta("m", 0, "!"),
                TextEnded("m", 0, "Hello!"),
                TextDelta("m", 0, "late"),
            ),
            pending,
        )
    }

    @Test
    fun decodesInboxEvents() {
        for (type in listOf("session.inbox.enqueued", "session.inbox.delivery.changed")) {
            assertEquals(
                OpenCodeServerEvent.InputEnqueued("ses_fixture", null),
                decodeStreamFrame("{\"type\":\"$type\",\"data\":{\"sessionID\":\"ses_fixture\"}}"),
            )
        }
        assertEquals(
            OpenCodeServerEvent.InputCancelled("ses_fixture", "msg_cancel"),
            decodeStreamFrame("{\"type\":\"session.inbox.cancelled\",\"data\":{\"sessionID\":\"ses_fixture\",\"inboxID\":\"msg_cancel\"}}"),
        )
        for (type in listOf("session.inbox.delivered")) {
            assertEquals(
                OpenCodeServerEvent.InputQueueChanged("ses_fixture", "msg_input"),
                decodeStreamFrame("{\"type\":\"$type\",\"data\":{\"sessionID\":\"ses_fixture\",\"inboxID\":\"msg_input\"}}"),
            )
        }
    }

    /**
     * A V2 server's `session.inbox.enqueued` frame (captured from an isolated server) carries
     * the admitted message, so it shows without an inbox read.
     */
    @Test
    fun enqueuedEventCarriesTheAdmittedMessage() {
        val frame = """{"id":"evt_1","created":1791607104311,"type":"session.inbox.enqueued","location":{"directory":"/tmp/demo"},"data":{"inboxID":"msg_ABC123","sessionID":"ses_demo","item":{"type":"user","payload":{"text":"hello there","metadata":{"displayText":"hi"}},"delivery":"queue"}},"durable":{"aggregateID":"ses_demo","seq":1,"version":1}}"""
        val event = decodeStreamFrame(frame)
        val enqueued = event as? OpenCodeServerEvent.InputEnqueued
        val input = enqueued?.input ?: return fail("Expected an enqueued input, got $event")
        assertEquals("ses_demo", enqueued.sessionID)
        assertEquals("msg_ABC123", input.id)
        assertEquals("hello there", input.text)
        assertEquals(OpenCodeDelivery.Queue, input.delivery)

        // An item the app can't read leaves the event itself intact.
        val odd = """{"type":"session.inbox.enqueued","data":{"inboxID":"msg_x","sessionID":"ses_demo","item":{"kind":42}}}"""
        assertEquals(OpenCodeServerEvent.InputEnqueued("ses_demo", null), decodeStreamFrame(odd))
    }

    @Test
    fun compactionLifecycleInvalidatesOnlyOnStartEndAndFailure() {
        for (type in listOf("session.compaction.started", "session.compaction.ended", "session.compaction.failed")) {
            assertEquals(
                OpenCodeServerEvent.MessageInvalidated("ses_compact"),
                decodeStreamFrame("""{"type":"$type","data":{"sessionID":"ses_compact"}}"""),
            )
        }
        assertNull(decodeStreamFrame("""{"type":"session.compaction.delta","data":{"sessionID":"ses_compact","text":"Summary"}}"""))
        assertFalse(OpenCodeLiveContentEvent.ReasoningEnded("m", 0).isDurableBoundary)
        assertTrue(TextEnded("m", 0, "hello").isDurableBoundary)
    }

    @Test
    fun catalogUpdatesDecodeAsCatalogChanges() {
        val location = ""","location":{"directory":"/tmp/demo"}"""
        assertEquals(OpenCodeServerEvent.ModelsChanged, decodeStreamFrame("""{"type":"model.updated"$location,"data":{}}"""))
        assertEquals(OpenCodeServerEvent.ModelsChanged, decodeStreamFrame("""{"type":"provider.updated","data":{"providers":[1,2,3]}}"""))
        assertEquals(OpenCodeServerEvent.AgentsChanged, decodeStreamFrame("""{"type":"agent.updated"$location,"data":{}}"""))
        assertEquals(OpenCodeServerEvent.ModelsChanged, decodeStreamFrame("""{"type":"model.updated"}"""))
    }
}
