package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.withDependenciesSuspending
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.LOCATION_BOOT_TIMEOUT
import ai.opencode.server.live.WAIT_POLL_TIMEOUT
import ai.opencode.server.model.FileReadTarget
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeShellOutputPage
import ai.opencode.server.model.PromptContext
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import okhttp3.ResponseBody.Companion.toResponseBody

/** Exact request bodies and failure handling for the live HTTP transport, captured below OkHttp. */
class WireRequestTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private suspend fun <T> withFixture(fixture: WireFixture, block: suspend (WireFixture) -> T): T =
        try { block(fixture) } finally { fixture.close() }

    private suspend fun expectClientError(block: suspend () -> Unit): OpenCodeClientError {
        try {
            block()
        } catch (error: OpenCodeClientError) {
            return error
        }
        fail("Expected an OpenCodeClientError")
        throw AssertionError()
    }

    @Test
    fun phoneSendCarriesItsNotificationCallback() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            val callback = "https://relay.example/v2/notify/sealed"

            PromptContext.notificationCallbackURL.withValue(callback) {
                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer)
            }

            val request = fixture.requests.first()
            assertEquals(
                json("""{"id":"msg_1","text":"hello","delivery":"steer","metadata":{"notification":{"callbackURL":"$callback"}}}"""),
                request.json(),
            )
        }
    }

    @Test
    fun aSendToAFolderThatIsGoneSaysSo() = runTest {
        val body = """{"_tag":"LocationNotFoundError","location":{"directory":"/work/linked"},"message":"Location not found: /work/linked"}"""
        withFixture(WireFixture { Reply.Json(404, body) }) { fixture ->
            val error = expectClientError {
                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", emptyList(), emptyList(), "msg_1", OpenCodeDelivery.Steer)
            }
            assertTrue(error.isLocationMissing)
            assertEquals(404, error.statusCode)
            assertEquals(OpenCodeClientError.FOLDER_GONE_MESSAGE, error.message)
        }
        // A bare 404 (a missing route or message) isn't a missing folder.
        assertFalse(OpenCodeClientError.isLocationMissing(status = 404, body = ByteArray(0)))
        assertFalse(OpenCodeClientError.isLocationMissing(status = 404, body = """{"_tag":"NotFoundError"}""".encodeToByteArray()))
        assertFalse(OpenCodeClientError.isLocationMissing(status = 400, body = """{"_tag":"LocationNotFoundError"}""".encodeToByteArray()))
    }

    @Test
    fun movingASessionPostsItsNewFolderOnce() = runTest {
        withFixture(WireFixture { Reply.status(204) }) { fixture ->
            OpenCodeClient.liveValue.moveSession(fixture.server, "ses_1", "/work/repo")

            val request = fixture.requests.first()
            assertEquals(1, fixture.requests.size)
            assertEquals("POST /api/session/ses_1/move", request.route)
            assertEquals(json("""{"directory":"/work/repo"}"""), request.json())
        }
    }

    @Test
    fun previewEnrollmentExchangesOnlyPublicKeysOnTheSavedHTTPSOrigin() = runTest {
        withFixture(WireFixture { Reply.Json(200, """{"output":{"publicKey":"server-public"}}""") }) { fixture ->
            val url = "https://relay.example/v2/notify/sealed"
            var server = fixture.server.copy(url = "http://local.example", alternateURLs = listOf(fixture.server.url))
            val key = OpenCodeClient.pairNotificationPreview(server, callbackURL = url, ref = "ref", publicKey = "phone-public")
            assertEquals("server-public", key)
            val request = fixture.requests.first()
            assertEquals("POST /api/rpc/mobile-notifications/pair", request.route)
            assertEquals(json("""{"input":{"ref":"ref","publicKey":"phone-public","callbackURL":"$url"}}"""), request.json())
            server = server.copy(alternateURLs = emptyList())
            expectClientError { OpenCodeClient.pairNotificationPreview(server, callbackURL = url, ref = "ref", publicKey = "phone-public") }
            assertEquals(1, fixture.requests.size)
        }
    }

    @Test
    fun notifyPluginStatusReadsThePluginList() = runTest {
        for ((body, expected) in listOf(
            """{"data":[{"id":"opencode.plan","state":{"status":"active"}},{"source":{"type":"x"}},{"id":"opencode-notify-callback","state":{"status":"active"}}]}""" to OpenCodeNotifyPluginStatus.Active,
            """{"data":[{"id":"opencode-notify-callback","state":{"status":"failed"}}]}""" to OpenCodeNotifyPluginStatus.Inactive,
            """{"data":[{"id":"opencode.plan","state":{"status":"active"}}]}""" to OpenCodeNotifyPluginStatus.Missing,
        )) {
            withFixture(WireFixture { Reply.Json(200, body) }) { fixture ->
                val status = OpenCodeClient.liveValue.notifyPluginStatus(fixture.server)

                assertEquals(expected, status)
                val request = fixture.requests.first()
                assertEquals("GET", request.method)
                assertEquals("/api/plugin", request.path)
                assertNull("A location-scoped read would boot that location's services", request.query)
            }
        }
    }

    /** Stop asks the server to resume steering messages, as the web app and the TUI do, and reads whether a run was going. */
    @Test
    fun interruptResumesSteeringAndReadsWhetherARunWasGoing() = runTest {
        for (interrupted in listOf(true, false)) {
            withFixture(WireFixture { Reply.Json(200, """{"interrupted":$interrupted}""") }) { fixture ->
                val result = OpenCodeClient.liveValue.interrupt(fixture.server, "ses_1")

                assertEquals(interrupted, result)
                val request = fixture.requests.first()
                assertEquals(1, fixture.requests.size)
                assertEquals("POST", request.method)
                assertEquals("/api/session/ses_1/interrupt", request.path)
                assertEquals(listOf("resume" to "true"), request.queryItems)
            }
        }
    }

    @Test
    fun interruptWithoutItsAnswerIsAnError() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            expectClientError { OpenCodeClient.liveValue.interrupt(fixture.server, "ses_1") }
        }
    }

    @Test
    fun sendsWithoutACallbackCarryNoNotificationMetadata() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            OpenCodeClient.liveValue.sendDeliveredPrompt(fixture.server, "ses_1", "steer", OpenCodeDelivery.Steer)

            val body = fixture.requests.first().json()
            assertNull(body["metadata"])
        }
    }

    @Test
    fun composerPromptPostsFilesAndExplicitDelivery() = runTest {
        for (delivery in listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue)) {
            withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
                val image = OpenCodeOutgoingAttachment(
                    data = byteArrayOf(1, 2), mime = "image/png", videoDuration = 4.0,
                    localVideoURI = "content://media/example/movie",
                )

                OpenCodeClient.liveValue.sendPrompt(fixture.server, "ses_1", "hello", listOf(image), emptyList(), "msg_1", delivery)

                val request = fixture.requests.first()
                assertEquals(1, fixture.requests.size)
                assertEquals("POST", request.method)
                assertEquals("/api/session/ses_1/prompt", request.path)
                assertEquals(
                    json("""{"id":"msg_1","text":"hello","files":[{"uri":"${image.dataURL}"}],"delivery":"${delivery.rawValue}"}"""),
                    request.json(),
                )
            }
        }
    }

    @Test
    fun voicePromptPostsDeliveryWithGeneratedID() = runTest {
        withFixture(WireFixture { Reply.Json(200, "{}") }) { fixture ->
            val messageID = OpenCodeClient.liveValue.sendDeliveredPrompt(fixture.server, "ses_1", "steer", OpenCodeDelivery.Steer)

            val request = fixture.requests.first()
            assertTrue(messageID.startsWith("msg_"))
            assertEquals("/api/session/ses_1/prompt", request.path)
            assertEquals(json("""{"id":"$messageID","text":"steer","delivery":"steer"}"""), request.json())
        }
    }

    @Test
    fun runningShellsReadOnlyTheSessionLocation() = runTest {
        val fixture = WireFixture {
            Reply.Json(200, """{"location":{"directory":"/a b"},"data":[{"id":"sh_1","status":"running","command":"bun dev","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/o","metadata":{"sessionID":"ses_1"},"time":{"started":1}},{"id":"sh_2","status":"exited","command":"ls","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/p","metadata":{},"time":{"started":1}}]}""")
        }
        withFixture(fixture) {
            val shells = OpenCodeClient.liveValue.runningShells(fixture.server, "/a b")

            assertEquals(
                listOf(OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = "ses_1", isListed = true, startedAt = Instant.ofEpochMilli(1))),
                shells,
            )
            val request = fixture.requests.first()
            assertEquals("GET", request.method)
            assertEquals("/api/shell", request.path)
            assertEquals(listOf("location[directory]" to "/a b"), request.queryItems)
        }
    }

    @Test
    fun runningJobsReadTheSessionTreeAndCancelPosts() = runTest {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/experimental/job" -> Reply.Json(
                    200,
                    """{"data":[{"kind":"codemode","id":"cm_1","sessionID":"ses_1","status":"running","time":{"started":5}},{"kind":"subagent","id":"job_2","sessionID":"ses_1","status":"running","childSessionID":"ses_2","agent":"general","description":"Look","time":{"started":6}}]}""",
                )
                else -> Reply.Json(204, "")
            }
        }
        withFixture(fixture) {
            val jobs = OpenCodeClient.liveValue.runningJobs(fixture.server, "ses_1")
            assertEquals(
                listOf(
                    OpenCodeJob(id = "cm_1", kind = OpenCodeJob.Kind.CodeMode, sessionID = "ses_1", isRunning = true, startedAt = Instant.ofEpochMilli(5)),
                    OpenCodeJob(
                        id = "job_2", kind = OpenCodeJob.Kind.Subagent, sessionID = "ses_1", isRunning = true,
                        childSessionID = "ses_2", startedAt = Instant.ofEpochMilli(6),
                    ),
                ),
                jobs,
            )
            OpenCodeClient.liveValue.cancelJob(fixture.server, "cm_1")

            val requests = fixture.requests
            assertEquals(listOf("GET /api/experimental/job", "POST /api/experimental/job/cm_1/cancel"), requests.map { it.route })
            assertEquals(listOf("sessionID" to "ses_1", "descendants" to "true", "status" to "running"), requests[0].queryItems)
            assertTrue(requests[1].queryItems.isEmpty())
        }
    }

    @Test
    fun shellDetailOutputAndStopUseTheShellRoutes() = runTest {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/shell/sh_1" ->
                    Reply.Json(200, """{"location":{"directory":"/a b"},"data":{"id":"sh_1","status":"exited","command":"bun test","cwd":"/a b","shell":"/bin/zsh","file":"/tmp/o","exit":1,"metadata":{"sessionID":"ses_1"},"time":{"started":1000,"completed":4000}}}""")
                "GET" to "/api/shell/sh_1/output" ->
                    Reply.Json(200, """{"location":{"directory":"/a b"},"data":{"output":"FAIL\n","cursor":105,"size":105,"truncated":false}}""")
                else -> Reply.Json(204, "")
            }
        }
        withFixture(fixture) {
            val shell = OpenCodeClient.liveValue.shell(fixture.server, "/a b", "sh_1")
            assertEquals(OpenCodeShell.Status.Exited, shell.status)
            assertEquals(1, shell.exitCode)
            assertEquals(Instant.ofEpochSecond(1), shell.startedAt)
            assertEquals(Instant.ofEpochSecond(4), shell.completedAt)
            val page = OpenCodeClient.liveValue.shellOutput(fixture.server, "/a b", "sh_1", 100, 65_536)
            assertEquals(OpenCodeShellOutputPage(output = "FAIL\n", cursor = 105, size = 105), page)
            OpenCodeClient.liveValue.stopShell(fixture.server, "/a b", "sh_1")
            OpenCodeClient.liveValue.moveToBackground(fixture.server, "ses_1")

            val requests = fixture.requests
            assertEquals(
                listOf("GET /api/shell/sh_1", "GET /api/shell/sh_1/output", "DELETE /api/shell/sh_1", "POST /api/session/ses_1/background"),
                requests.map { it.route },
            )
            assertEquals(
                listOf("location[directory]" to "/a b", "cursor" to "100", "limit" to "65536"),
                requests[1].queryItems,
            )
            assertEquals(listOf("location[directory]" to "/a b"), requests[2].queryItems)
        }
    }

    @Test
    fun serverEndpointReplacesPathQueryAndFragment() {
        val server = OpenCodeServer(url = "  https://host.example:4096/base?token=x#part \n", username = "u", password = "p")
        assertEquals("https://host.example:4096/api/event", server.endpoint("/api/event")?.toString())
        assertEquals(
            "https://host.example:4096/api/model?directory=/a%20b",
            server.endpoint("/api/model", listOf("directory" to "/a b"))?.toString(),
        )
        assertEquals("Basic " + Base64.getEncoder().encodeToString("u:p".toByteArray()), server.basicAuthorization)
        assertNull(OpenCodeServer(url = "https://host.example", username = "u", password = "").basicAuthorization)
        for (url in listOf("ftp://example.invalid", "https://", "not a url")) {
            assertNull(url, OpenCodeServer(url = url, username = "", password = "").endpoint("/api/event"))
        }
    }

    @Test
    fun imageRequestReadsProjectFilesThroughTheServer() {
        val server = OpenCodeServer(url = "https://host.example:4096", username = "u", password = "p")
        val file = server.imageRequest("/work/proj/shots/a b/ü 100%.png", "/work/proj")
        assertEquals(
            "https://host.example:4096/api/fs/read/shots/a%20b/%C3%BC%20100%25.png?location%5Bdirectory%5D=/work/proj",
            file?.url?.toString(),
        )
        assertEquals(server.basicAuthorization, file?.header("Authorization"))
        fun decodedPath(source: String) = server.imageRequest(source, "/work/proj")?.url?.let { "/" + it.pathSegments.joinToString("/") }
        assertEquals("/api/fs/read/a b.png", decodedPath("file:///work/proj/a%20b.png"))
        assertEquals("/api/fs/read/docs/x.png", decodedPath("sandbox:docs/x.png"))
        assertNull(server.imageRequest("/elsewhere/x.png", "/work/proj"))
        assertNull(server.imageRequest("x.png", null))

        val remote = server.imageRequest("https://cdn.example/x.png", null)
        assertEquals("https://cdn.example/x.png", remote?.url?.toString())
        assertNull(remote?.header("Authorization"))
    }

    @Test
    fun retryReadsAnOutsideMarkdownImageFromItsOwnFolderAgain() = runTest {
        withFixture(WireFixture { Reply.status(404) }) { fixture ->
            val client = OpenCodeClient.testValue
            val target = FileReadTarget("/elsewhere", "missing.png")
            repeat(2) {
                assertNull(client.markdownImage(fixture.server, "/work/proj", "/elsewhere/missing.png", target))
            }
            assertEquals(2, fixture.requests.size)
            for (request in fixture.requests) {
                assertEquals("GET /api/fs/read/missing.png", request.route)
                assertEquals(listOf("location[directory]" to "/elsewhere"), request.queryItems)
            }
        }
    }

    private fun voiceHandoff(instruction: String): String =
        "Voice delegation. The user is on a voice call with a voice agent, which hands their requests to you.\n" +
            "{\n  \"instruction\" : \"$instruction\",\n  \"transcript_delta\" : [\n\n  ]\n}"

    @Test
    fun assistantReplyStopsAtNextUserMessageSoSteersDoNotDuplicateFinalAnswer() = runTest {
        fun message(id: String, type: String, text: String?): String {
            val part = if (text == null) """{"type":"text"}""" else """{"type":"text","text":${Json.encodeToString(String.serializer(), text)}}"""
            return """{"id":"$id","type":"$type","time":{"created":1},"content":[$part]}"""
        }
        val body = """{"data":[""" + listOf(
            message("msg_a4", "assistant", "Final answer after typed steer."),
            message("msg_typed", "user", "Typed composer steer mid-run"),
            message("msg_a3", "assistant", null),
            message("msg_3", "user", voiceHandoff("Third voice request")),
            message("msg_a2", "assistant", "Interim answer for second request."),
            message("msg_2", "user", voiceHandoff("Second voice request")),
            message("msg_a1", "assistant", null),
            message("msg_1", "user", voiceHandoff("First voice request")),
        ).joinToString(",") + "]}"
        withFixture(WireFixture { Reply.Json(200, body) }) { fixture ->
            val first = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_1")
            val second = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_2")
            val third = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_3")

            assertNull("msg_1 had no text before the next voice delegation msg_2; must not steal msg_2's reply", first)
            assertEquals("Interim answer for second request.", second)
            assertEquals(
                "A typed composer steer mid-run must still return the final assistant reply for msg_3",
                "Final answer after typed steer.",
                third,
            )
        }
    }

    @Test
    fun waitForIdleRewaitsWhenWaitTimesOutWhileSessionRemainsActive() = runTest {
        val waitAttempts = AtomicInteger(0)
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" ->
                    if (waitAttempts.incrementAndGet() < 3) Reply.Json(500, "{}") else Reply.Json(204, "")
                "GET" to "/api/session/active" -> Reply.Json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                OpenCodeClient.liveValue.waitForIdle(fixture.server, "ses_1")
            }
            assertEquals(3, waitAttempts.get())
        }
    }

    @Test
    fun waitForIdleDoesNotRetryClientErrorsAndBoundsFastActiveRetries() = runTest {
        val fourXXAttempts = AtomicInteger(0)
        val activeChecks = AtomicInteger(0)
        val nonRetryable = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    fourXXAttempts.incrementAndGet()
                    Reply.Json(404, "{}")
                }
                "GET" to "/api/session/active" -> {
                    activeChecks.incrementAndGet()
                    Reply.Json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                }
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(nonRetryable) {
            expectClientError { OpenCodeClient.liveValue.waitForIdle(nonRetryable.server, "ses_1") }
            assertEquals("4xx (other than 408/429) must not retry", 1, fourXXAttempts.get())
            assertEquals(0, activeChecks.get())
        }

        val fastFailAttempts = AtomicInteger(0)
        val wedgedActiveChecks = AtomicInteger(0)
        val wedged = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    fastFailAttempts.incrementAndGet()
                    Reply.Json(500, "{}")
                }
                "GET" to "/api/session/active" -> {
                    wedgedActiveChecks.incrementAndGet()
                    Reply.Json(200, """{"data":{"ses_1":{"type":"busy"}}}""")
                }
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(wedged) {
            expectClientError {
                withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                    OpenCodeClient.liveValue.waitForIdle(wedged.server, "ses_1")
                }
            }
            assertEquals("Fast-failing /wait with active=true must terminate at the pass bound", 12, fastFailAttempts.get())
            assertEquals(11, wedgedActiveChecks.get())
        }
    }

    @Test
    fun waitForIdleReturnsImmediatelyWhenWaitFailsAndSessionIsNoLongerActive() = runTest {
        assertEquals(25.seconds, WAIT_POLL_TIMEOUT)
        val waitAttempts = AtomicInteger(0)
        val activeChecks = AtomicInteger(0)
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "POST" to "/api/experimental/session/ses_1/wait" -> {
                    waitAttempts.incrementAndGet()
                    Reply.Json(502, "{}")
                }
                "GET" to "/api/session/active" -> {
                    activeChecks.incrementAndGet()
                    Reply.Json(200, """{"data":{}}""")
                }
                "GET" to "/api/session/ses_1/message" -> Reply.Json(
                    200,
                    """
                    {"data":[
                      {"id":"msg_a1","type":"assistant","time":{"created":2},"content":[{"type":"text","text":"Finished during the network blip."}]},
                      {"id":"msg_1","type":"user","time":{"created":1},"content":[{"type":"text","text":"Run the tests"}]}
                    ]}
                    """.trimIndent(),
                )
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            withDependenciesSuspending({ this[Clock] = ImmediateClock() }) {
                OpenCodeClient.liveValue.waitForIdle(fixture.server, "ses_1")
                val reply = OpenCodeClient.liveValue.assistantReply(fixture.server, "ses_1", "msg_1")
                assertEquals("Finished during the network blip.", reply)
            }
            assertEquals(1, waitAttempts.get())
            assertEquals(1, activeChecks.get())
        }
    }

    @Test
    fun modelCatalogUsesLocationBootTimeout() = runTest {
        val fixture = WireFixture { request ->
            when (request.method to request.path) {
                "GET" to "/api/model" -> Reply.Json(
                    200,
                    """{"data":[{"id":"gpt-5.4","modelID":"gpt-5.4","providerID":"openai","name":"GPT-5.4","enabled":true,"status":"active"}]}""",
                )
                "GET" to "/api/model/default" -> Reply.Json(
                    200,
                    """{"data":{"id":"gpt-5.4","modelID":"gpt-5.4","providerID":"openai","name":"GPT-5.4","enabled":true,"status":"active"}}""",
                )
                else -> Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            val catalog = OpenCodeClient.liveValue.modelCatalog(fixture.server, "/work/cold-boot")
            assertEquals(listOf("gpt-5.4"), catalog.models.map { it.modelID })
            assertEquals("gpt-5.4", catalog.defaultModel?.modelID)
            assertEquals(listOf("GET /api/model", "GET /api/model/default"), fixture.requests.map { it.route }.sorted())
            assertTrue(fixture.requests.all { it.queryValue("location[directory]") == "/work/cold-boot" })
            assertTrue(fixture.requests.all { it.timeoutInterval == LOCATION_BOOT_TIMEOUT.inWholeMilliseconds / 1000.0 })
        }
    }

    @Test fun homeAttentionAsksHotDirectoriesAtOnce() = runBlocking {
        val fixture = WireFixture { request ->
            if (request.queryValue("location[directory]") == "/booting") {
                Reply.Open(200, "")
            } else if (request.route == "GET /api/permission/request" || request.route == "GET /api/form") {
                Reply.Json(200, """{"data":[]}""")
            } else {
                Reply.Json(404, "{}")
            }
        }
        withFixture(fixture) {
            val held = launch(Dispatchers.Default) { OpenCodeClient.liveValue.homeAttention(fixture.server, emptyList(), listOf("/booting", "/ready")) }
            for (attempt in 0 until 500) {
                if (fixture.requests.count { it.queryValue("location[directory]") == "/ready" } >= 2) break
                delay(10)
            }
            held.cancel()
            assertEquals(2, fixture.requests.count { it.queryValue("location[directory]") == "/ready" })
        }
    }

    @Test fun modelCatalogReadsTheListAndDefaultTogether() = runBlocking {
        val fixture = WireFixture { request -> if (request.route == "GET /api/model") Reply.Open(200, "") else Reply.Json(200, "{}") }
        withFixture(fixture) {
            val held = launch(Dispatchers.Default) { OpenCodeClient.liveValue.modelCatalog(fixture.server, "/work/held") }
            for (attempt in 0 until 500) {
                if (fixture.requests.size >= 2) break
                delay(10)
            }
            held.cancel()
            assertEquals(setOf("GET /api/model", "GET /api/model/default"), fixture.requests.map { it.route }.toSet())
        }

        val failing = WireFixture { request ->
            if (request.route == "GET /api/model") {
                Reply.Json(200, """{"data":[{"id":"gpt-5.4","modelID":"gpt-5.4","providerID":"openai","name":"GPT-5.4","enabled":true,"status":"active"}]}""")
            } else {
                Reply.Json(404, "{}")
            }
        }
        withFixture(failing) {
            val catalog = OpenCodeClient.liveValue.modelCatalog(failing.server, "/work/no-default")
            assertEquals(listOf("gpt-5.4"), catalog.models.map { it.modelID })
            assertNull(catalog.defaultModel)
        }
    }

    /**
     * Plain HTTP reaches private networks and overlays only; the live guard refuses the rest as the
     * platform's cleartext refusal, before anything is sent.
     */
    @Test
    fun transportSecurityAllowsPrivateOverlays() {
        val allowed = listOf(
            "http://100.64.0.1:9", "http://100.96.0.1:9", "http://100.127.255.254:9",
            "http://[fc00::1]:9", "http://[fd7a:115c:a1e0::1]:9", "http://[fdff:ffff::1]:9",
            "http://peer.tailnet-0000.ts.net:9", "http://peer.netbird.cloud:9", "http://peer.netbird.selfhosted:9",
            "http://nas.home.arpa:9", "http://build.internal:9",
            // NSAllowsLocalNetworking itself: RFC 1918, loopback, unqualified and .local names.
            "http://10.0.2.2:4096", "http://192.168.1.20:4096", "http://172.31.7.134:4096", "http://127.0.0.1:4096",
            "http://my-mac:4096", "http://my-mac.local:4096",
        )
        val blocked = listOf(
            "http://100.63.255.254:9", "http://100.128.0.1:9", "http://8.8.8.8:9",
            "http://[fbff::1]:9", "http://[fe00::1]:9", "http://[2606:4700:4700::1111]:9",
            "http://opencode-ats-probe.example.com:9", "http://ts.net.example.com:9",
        )
        fun refused(address: String): Boolean {
            val client = okhttp3.OkHttpClient.Builder().addInterceptor(TransportSecurity.interceptor)
                .addInterceptor { chain ->
                    okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(204).message("").body(ByteArray(0).toResponseBody()).build()
                }
                .build()
            return try {
                client.newCall(okhttp3.Request.Builder().url(address).build()).execute().close()
                false
            } catch (error: java.net.UnknownServiceException) {
                error.message.orEmpty().startsWith("CLEARTEXT communication to ")
            }
        }
        for (address in allowed) assertTrue("should allow $address", !refused(address))
        for (address in blocked) assertTrue("should block $address", refused(address))
        assertTrue("HTTPS is never refused", !refused("https://8.8.8.8:9"))
    }
}
