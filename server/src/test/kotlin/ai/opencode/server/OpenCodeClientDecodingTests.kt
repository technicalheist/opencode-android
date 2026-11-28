package ai.opencode.server

import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.diagnostics.DiagnosticsEvent
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.live.ACTIVE_ANCESTRY_CONCURRENCY
import ai.opencode.server.live.OpenCodeReadCache
import ai.opencode.server.live.ParsedMessageCache
import ai.opencode.server.live.decodeTranscript
import ai.opencode.server.live.decodeTranscriptPage
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeQuestion
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.model.TranscriptNoticeKind
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.NetworkRetry
import ai.opencode.server.retry.withRetryPolicy
import ai.opencode.server.testing.WireFixture
import ai.opencode.server.testing.WireFixture.Reply
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class OpenCodeClientDecodingTests {
    @get:Rule val isolation = TestIsolationRule()

    private val rootSession = OpenCodeSession(
        id = "ses_root",
        serverTitle = "Root",
        projectID = "p",
        directory = "/project",
        updatedAt = Instant.ofEpochSecond(1),
    )

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun decode(text: String): List<OpenCodeTranscriptItem> = decodeTranscript(text.toByteArray())

    private fun toolCalls(items: List<OpenCodeTranscriptItem>): List<OpenCodeToolCall> =
        items.filterIsInstance<OpenCodeTranscriptItem.Tool>().map { it.call }

    private fun toolCall(
        callID: String? = null,
        name: String,
        status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed,
        input: OpenCodeJSONValue = OpenCodeJSONValue.Null,
    ) = OpenCodeToolCall(callID = callID ?: name, name = name, status = status, input = input, output = emptyList(), metadata = emptyMap())

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
    fun liveServerReadContract() = runTest {
        assumeTrue(System.getenv("OPENCODE_LIVE_SERVER") != null)
        TestNetworkBlock.withRealNetworkAllowed {
            val url = requireNotNull(System.getenv("OPENCODE_LIVE_SERVER"))
            val password = requireNotNull(System.getenv("OPENCODE_LIVE_PASSWORD"))
            val sessionID = requireNotNull(System.getenv("OPENCODE_LIVE_SESSION_ID"))
            val directory = requireNotNull(System.getenv("OPENCODE_LIVE_DIRECTORY"))
            val server = OpenCodeServer(url = url, username = "opencode", password = password)
            val client = OpenCodeClient.liveValue
            client.health(server)
            val snapshot = client.homeSnapshot(server)
            assertTrue(snapshot.sessions.all { it.parentID == null })
            val session = client.getSession(server, sessionID)
            assertEquals(directory, session.directory)
            client.familyPendingRequests(server, listOf(session.id), session.directory)
            client.pendingInputs(server, session.id)
            client.contextUsage(server, session.id)
        }
    }

    @Test
    fun liveServerTransferDiagnostics() = runTest {
        assumeTrue(System.getenv("OPENCODE_LIVE_TRANSFER_TEST") == "1")
        TestNetworkBlock.withRealNetworkAllowed {
            val server = OpenCodeServer(
                url = requireNotNull(System.getenv("OPENCODE_LIVE_SERVER")),
                username = "opencode",
                password = requireNotNull(System.getenv("OPENCODE_LIVE_PASSWORD")),
            )
            val sessionID = requireNotNull(System.getenv("OPENCODE_LIVE_SESSION_ID"))
            val recorder = DiagnosticsRecorder.shared
            recorder.clear()
            recorder.setEnabled(true)
            try {
                OpenCodeClient.liveValue.transcript(server, sessionID, 30)
                val events = recorder.snapshot()
                val end = events.last { it.kind == DiagnosticsEvent.Kind.RequestEnd && it.path?.contains("/message") == true }
                val metrics = events.last { it.kind == DiagnosticsEvent.Kind.NetworkMetrics && it.requestID == end.requestID }
                assertEquals("gzip", end.contentEncoding?.lowercase())
                assertTrue((end.decodedBytes ?: 0) > 300_000)
                assertTrue((metrics.wireBytes ?: Long.MAX_VALUE) < (end.decodedBytes ?: 0))
                assertNotNull(metrics.protocolName)
                println(
                    "LIVE_TRANSFER protocol=${metrics.protocolName ?: "unknown"} wire=${metrics.wireBytes ?: 0} " +
                        "decoded=${end.decodedBytes ?: 0} encoding=${end.contentEncoding ?: "identity"}",
                )
            } finally {
                recorder.clear()
                recorder.setEnabled(false)
            }
        }
    }

    private object Key : CoroutineContext.Key<AbstractCoroutineContextElement>

    private suspend fun assertParsesOffTheCallingThread(page: Boolean, read: suspend (OpenCodeServer) -> Unit) {
        val message = """{"id":"msg_${UUID.randomUUID()}","type":"assistant","content":[{"type":"text","text":"Older"}]}"""
        val body = if (page) """{"data":[$message]}""" else """{"data":$message}"""
        val fixture = WireFixture { Reply.Json(200, body) }
        withFixture(fixture) {
            val caller = Thread.currentThread()
            val ranOn = mutableSetOf<Thread>()
            val tracker = object : ThreadContextElement<Unit>, AbstractCoroutineContextElement(Key) {
                override fun updateThreadContext(context: CoroutineContext) {
                    synchronized(ranOn) { ranOn += Thread.currentThread() }
                }
                override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit
            }
            val tally = AtomicInteger()
            withContext(tracker) {
                ParsedMessageCache.parsed.withValue(tally) { read(it.server) }
            }
            assertEquals(1, tally.get())
            assertTrue(ranOn.any { thread -> thread != caller })
        }
    }

    @Test
    fun transcriptReadsParseOffTheCallingThread() = runTest {
        val client = OpenCodeClient.liveValue
        assertParsesOffTheCallingThread(page = true) { client.historyPage(it, "ses_root", null) }
        assertParsesOffTheCallingThread(page = true) { client.transcript(it, "ses_root", 30) }
        assertParsesOffTheCallingThread(page = false) { client.message(it, "ses_root", "msg_root") }
    }

    @Test
    fun viewedProtocolUsesExactIdleWatermark() = runTest {
        val fixture = WireFixture { request ->
            if (request.method == "POST") Reply.status(204)
            else Reply.Json(200, """{"data":{"id":"ses_view","projectID":"p","title":"Viewed","location":{"directory":"/project"},"time":{"updated":1789440123999,"idle":1789440123456,"viewed":1789440000000}}}""")
        }
        withFixture(fixture) {
            val client = OpenCodeClient.liveValue

            val session = client.getSession(fixture.server, "ses_view")
            assertEquals(1789440123456L, session.idleMilliseconds)
            assertEquals(1789440000000L, session.viewedMilliseconds)
            client.viewSession(fixture.server, session.id, session.idleMilliseconds!!)

            assertEquals(listOf("GET /api/session/ses_view", "POST /api/session/ses_view/view"), fixture.requests.map { it.route })
            assertEquals(json("""{"idle":1789440123456}"""), fixture.requests[1].json())
        }
    }

    @Test
    fun archivedTimeDecodes() = runTest {
        val fixture = WireFixture {
            Reply.Json(200, """{"data":{"id":"ses_archived","projectID":"p","title":"Archived","location":{"directory":"/project"},"time":{"updated":1789440123999,"archived":1789440124000}}}""")
        }
        withFixture(fixture) {
            val session = OpenCodeClient.liveValue.getSession(fixture.server, "ses_archived")
            assertEquals(1789440124000L, session.archivedMilliseconds)
        }
    }

    /**
     * Recents and search ask for unarchived roots, and drop any archived one
     * a server that ignores `archived=false` still lists.
     */
    @Test
    fun homeSnapshotAndSearchLeaveArchivedSessionsOut() = runTest {
        val archived = """{"id":"ses_archived","projectID":"p","title":"Old","location":{"directory":"/project"},"time":{"updated":2000,"archived":1500}}"""
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/project" -> Reply.Json(200, "[]")
                "GET /api/session/active" -> Reply.Json(200, """{"data":{}}""")
                "GET /api/session" -> Reply.Json(200, "{\"data\":[$archived,${sessionJSON("ses_live")}]}")
                else -> Reply.status(404)
            }
        }
        withFixture(fixture) {
            val snapshot = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
            assertEquals(listOf("ses_live"), snapshot.sessions.map { it.id })
            val found = OpenCodeClient.liveValue.searchSessions(fixture.server, "o", 20)
            assertEquals(listOf("ses_live"), found.map { it.id })
            val lists = fixture.requests.filter { it.route == "GET /api/session" }
            assertEquals(2, lists.size)
            assertTrue(lists.all { it.queryValue("archived") == "false" })
        }

        val fetched = WireFixture { Reply.Json(200, "{\"data\":$archived}") }
        withFixture(fetched) {
            assertTrue(OpenCodeClient.liveValue.getSession(fetched.server, "ses_archived").isArchived)
        }
    }

    /** The Archived page asks for archived roots, searched on the server; Unarchive patches `archived: false`. */
    @Test
    fun archivedSessionsListAndUnarchive() = runTest {
        val archived = """{"id":"ses_archived","projectID":"p","title":"Old","location":{"directory":"/project"},"time":{"updated":2000,"archived":1500}}"""
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/session" -> Reply.Json(200, "{\"data\":[$archived]}")
                "PATCH /api/session/ses_archived" -> Reply.status(204)
                else -> Reply.status(404)
            }
        }
        withFixture(fixture) {
            val listed = OpenCodeClient.liveValue.archivedSessions(fixture.server, "", 50)
            assertEquals(listOf("ses_archived"), listed.map { it.id })
            val list = fixture.requests[0]
            assertEquals("true", list.queryValue("archived"))
            assertEquals("null", list.queryValue("parentID"))
            assertEquals("50", list.queryValue("limit"))
            assertEquals(null, list.queryValue("search"))

            OpenCodeClient.liveValue.archivedSessions(fixture.server, "old", 50)
            assertEquals("old", fixture.requests[1].queryValue("search"))

            OpenCodeClient.liveValue.unarchiveSession(fixture.server, "ses_archived")
            assertEquals(json("""{"archived":false}"""), fixture.requests[2].json())
        }
    }

    @Test
    fun homeActivityResolvesOnlyActiveAncestorsIncludingNestedChildren() = runTest {
        val responses = mapOf(
            "GET /api/project" to "[]",
            "GET /api/session" to "{\"data\":[${sessionJSON("ses_root")}]}",
            "GET /api/session/active" to """{"data":{"ses_deep":{"type":"busy"},"ses_sibling":{"type":"busy"}}}""",
            "GET /api/session/ses_deep" to "{\"data\":${sessionJSON("ses_deep", parentID = "ses_child")}}",
            "GET /api/session/ses_child" to "{\"data\":${sessionJSON("ses_child", parentID = "ses_root")}}",
            "GET /api/session/ses_sibling" to "{\"data\":${sessionJSON("ses_sibling", parentID = "ses_root")}}",
        )
        val fixture = WireFixture { request -> responses[request.route]?.let { Reply.Json(200, it) } ?: Reply.status(404) }
        withFixture(fixture) {
            val snapshot = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
            assertEquals(listOf("ses_root"), snapshot.sessions.map { it.id })
            assertEquals(setOf("ses_deep", "ses_sibling"), snapshot.activeSessionIDs)
            assertNull("Snapshot publishes without blocking on unknown ancestry lookups", snapshot.activeSessionParents)
            assertEquals(3, fixture.requests.size)
            assertEquals(setOf("GET /api/project", "GET /api/session", "GET /api/session/active"), fixture.requests.map { it.route }.toSet())

            val parents = OpenCodeClient.liveValue.activeSessionParents(fixture.server, snapshot.activeSessionIDs, snapshot.sessions.map { it.id })
            assertEquals(mapOf("ses_deep" to "ses_child", "ses_child" to "ses_root", "ses_sibling" to "ses_root"), parents)
            assertEquals(6, fixture.requests.size)
            assertEquals(responses.keys, fixture.requests.map { it.route }.toSet())

            // Parents never change: the next refresh is one wave, with no lookups.
            val again = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
            assertEquals(parents, again.activeSessionParents)
            assertEquals(9, fixture.requests.size)
            assertEquals(setOf("GET /api/project", "GET /api/session", "GET /api/session/active"), fixture.requests.drop(6).map { it.route }.toSet())
        }
    }

    /** Step 1 only. */
    @Test
    fun completeHomeSnapshotResolvesActiveAncestryForOtherServersAndVoiceListing() = runTest {
        val responses = mapOf(
            "GET /api/project" to "[]",
            "GET /api/session" to "{\"data\":[${sessionJSON("ses_root")},${sessionJSON("ses_idle")}]}",
            "GET /api/session/active" to """{"data":{"ses_deep":{"type":"busy"}}}""",
            "GET /api/session/ses_deep" to "{\"data\":${sessionJSON("ses_deep", parentID = "ses_child")}}",
            "GET /api/session/ses_child" to "{\"data\":${sessionJSON("ses_child", parentID = "ses_root")}}",
        )
        val fixture = WireFixture { request -> responses[request.route]?.let { Reply.Json(200, it) } ?: Reply.status(404) }
        withFixture(fixture) {
            // 1. Complete snapshot resolves unknown active ancestry in one step so `ses_root` is marked busy.
            val snapshot = OpenCodeClient.liveValue.completeHomeSnapshot(fixture.server)
            assertEquals(mapOf("ses_deep" to "ses_child", "ses_child" to "ses_root"), snapshot.activeSessionParents)
            val busy = SessionActivity.ancestors(snapshot.activeSessionIDs, snapshot.activeSessionParents ?: emptyMap())
            assertTrue("ses_root" in busy)
            assertFalse("ses_idle" in busy)
        }
    }

    @Test
    fun sessionFamilyCommitsSixItemAncestryBatchWhenSeventhFails() = runTest {
        val activeEntries = (1..7).joinToString(",") { "\"ses_sub_$it\":{\"type\":\"busy\"}" }
        val subPayloads = (1..6).associate { index ->
            "GET /api/session/ses_sub_$index" to "{\"data\":${sessionJSON("ses_sub_$index", parentID = "ses_root")}}"
        }
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/session" -> Reply.Json(200, "{\"data\":[]}")
                "GET /api/session/active" -> Reply.Json(200, "{\"data\":{$activeEntries}}")
                "GET /api/session/ses_sub_7" -> Reply.status(500)
                else -> subPayloads[request.route]?.let { Reply.Json(200, it) } ?: Reply.status(404)
            }
        }
        withFixture(fixture) {
            val family = OpenCodeClient.liveValue.sessionFamily(fixture.server, rootSession)
            assertEquals(
                listOf("ses_root", "ses_sub_1", "ses_sub_2", "ses_sub_3", "ses_sub_4", "ses_sub_5", "ses_sub_6"),
                family.sessions.map { it.id },
            )
        }
    }

    @Test
    fun ancestryLookupsSurviveAFailedRefresh() = runTest {
        val root = sessionJSON("ses_root")
        val deep = sessionJSON("ses_deep", parentID = "ses_child")
        val child = sessionJSON("ses_child", parentID = "ses_root")
        val failChild = AtomicBoolean(true)
        val fixture = WireFixture { request ->
            when (request.route) {
                "GET /api/project" -> Reply.Json(200, "[]")
                "GET /api/session" -> Reply.Json(200, "{\"data\":[$root]}")
                "GET /api/session/active" -> Reply.Json(200, """{"data":{"ses_deep":{"type":"busy"}}}""")
                "GET /api/session/ses_deep" -> Reply.Json(200, "{\"data\":$deep}")
                "GET /api/session/ses_child" -> if (failChild.get()) Reply.status(503) else Reply.Json(200, "{\"data\":$child}")
                else -> Reply.status(404)
            }
        }
        withFixture(fixture) {
            val snapshot = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
            assertEquals(setOf("ses_deep"), snapshot.activeSessionIDs)
            expectClientError {
                OpenCodeClient.liveValue.activeSessionParents(fixture.server, snapshot.activeSessionIDs, snapshot.sessions.map { it.id })
            }
            failChild.set(false)
            val parents = OpenCodeClient.liveValue.activeSessionParents(fixture.server, snapshot.activeSessionIDs, snapshot.sessions.map { it.id })
            assertEquals(mapOf("ses_deep" to "ses_child", "ses_child" to "ses_root"), parents)
            // The first attempt's answer about ses_deep was kept.
            assertEquals(1, fixture.requests.count { it.route == "GET /api/session/ses_deep" })
        }
    }

    @Test
    fun activeAncestryOnlyIgnoresDeletedSessions() = runTest {
        for (status in listOf(404, 503)) {
            val rootJSON = sessionJSON("ses_root")
            val childJSON = sessionJSON("ses_child", parentID = "ses_root")
            val childAvailable = AtomicBoolean(false)
            val fixture = WireFixture { request ->
                when (request.route) {
                    "GET /api/project" -> Reply.Json(200, "[]")
                    "GET /api/session" -> Reply.Json(200, "{\"data\":[$rootJSON]}")
                    "GET /api/session/active" -> Reply.Json(200, """{"data":{"ses_child":{"type":"busy"}}}""")
                    "GET /api/session/ses_child" -> if (childAvailable.get()) Reply.Json(200, "{\"data\":$childJSON}") else Reply.status(status)
                    else -> Reply.status(status)
                }
            }
            withFixture(fixture) {
                val snapshot = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
                assertEquals(setOf("ses_child"), snapshot.activeSessionIDs)
                try {
                    val parents = OpenCodeClient.liveValue.activeSessionParents(fixture.server, snapshot.activeSessionIDs, snapshot.sessions.map { it.id })
                    assertEquals(404, status)
                    assertTrue(parents.isEmpty())
                    // Within the same active epoch (before negative-cache expiry), a 404 is not hammered again.
                    val repeatParents = OpenCodeClient.liveValue.activeSessionParents(fixture.server, snapshot.activeSessionIDs, snapshot.sessions.map { it.id })
                    assertTrue(repeatParents.isEmpty())
                    assertEquals(1, fixture.requests.count { it.route == "GET /api/session/ses_child" })

                    // A 404 is never cached forever as a root: when a later active snapshot epoch
                    // sees `ses_child` still active and the server now returns it, ancestry revalidates.
                    childAvailable.set(true)
                    val laterSnapshot = OpenCodeClient.liveValue.homeSnapshot(fixture.server)
                    assertNull(laterSnapshot.activeSessionParents)
                    val resolvedParents = OpenCodeClient.liveValue.activeSessionParents(
                        fixture.server, laterSnapshot.activeSessionIDs, laterSnapshot.sessions.map { it.id },
                    )
                    assertEquals(mapOf("ses_child" to "ses_root"), resolvedParents)
                    assertEquals(2, fixture.requests.count { it.route == "GET /api/session/ses_child" })
                } catch (error: OpenCodeClientError) {
                    assertEquals(503, status)
                    assertEquals(503, error.statusCode)
                }
            }
        }
    }

    @Test
    fun activeAncestryBoundsConcurrencyToTwoAcrossDisjointAndOverlappingTraversals() = runTest {
        // 1. Disjoint Home + family traversals share the per-server permit pool of 2.
        val disjoint = DelayedAncestryFixture(host = "ancestry-disjoint-${UUID.randomUUID().toString().lowercase()}.test", familyActiveIDs = listOf("ses_b1", "ses_b2", "ses_b3"))
        try {
            val disjointHome = async {
                OpenCodeClient.liveValue.activeSessionParents(disjoint.server, setOf("ses_a1", "ses_a2", "ses_a3"), listOf(rootSession.id))
            }
            val disjointFamily = async { OpenCodeClient.liveValue.sessionFamily(disjoint.server, rootSession) }
            assertEquals(mapOf("ses_a1" to "ses_root", "ses_a2" to "ses_root", "ses_a3" to "ses_root"), disjointHome.await())
            assertEquals(setOf("ses_root", "ses_b1", "ses_b2", "ses_b3"), disjointFamily.await().sessions.map { it.id }.toSet())
            val metrics = disjoint.metrics()
            assertTrue(metrics.maxInFlight <= ACTIVE_ANCESTRY_CONCURRENCY)
            assertEquals(6, metrics.requestCount)
        } finally {
            disjoint.close()
        }

        // 2. Partially overlapping Home + family traversals deduplicate shared IDs and bound total in-flight requests to 2.
        val overlap = DelayedAncestryFixture(host = "ancestry-overlap-${UUID.randomUUID().toString().lowercase()}.test", familyActiveIDs = listOf("ses_shared_1", "ses_shared_2", "ses_b1"))
        try {
            val overlapHome = async {
                OpenCodeClient.liveValue.activeSessionParents(overlap.server, setOf("ses_shared_1", "ses_shared_2", "ses_a1"), listOf(rootSession.id))
            }
            val overlapFamily = async { OpenCodeClient.liveValue.sessionFamily(overlap.server, rootSession) }
            assertEquals(mapOf("ses_shared_1" to "ses_root", "ses_shared_2" to "ses_root", "ses_a1" to "ses_root"), overlapHome.await())
            assertEquals(setOf("ses_root", "ses_shared_1", "ses_shared_2", "ses_b1"), overlapFamily.await().sessions.map { it.id }.toSet())
            val metrics = overlap.metrics()
            assertTrue(metrics.maxInFlight <= ACTIVE_ANCESTRY_CONCURRENCY)
            assertEquals("Overlapping Home and family lookups share in-flight requests", 4, metrics.requestCount)
        } finally {
            overlap.close()
        }

        // 3. The shared deduped lookup runs its network attempt under `.once` (policy-independent),
        // while each caller owns its own retry around awaiting the shared result. If a `.once`
        // caller starts a shared lookup that fails with 503 while a `.request` caller
        // (`sessionFamily`) joins it, the `.once` caller fails after 1 attempt while
        // `sessionFamily` retries and succeeds.
        val retry = DelayedAncestryFixture(
            host = "ancestry-retry-${UUID.randomUUID().toString().lowercase()}.test",
            familyActiveIDs = listOf("ses_shared_retry"),
            transient503IDs = setOf("ses_shared_retry"),
        )
        try {
            val onceAncestry = async {
                try {
                    Result.success(
                        withRetryPolicy(RetryPolicy.once) {
                            OpenCodeClient.liveValue.activeSessionParents(retry.server, setOf("ses_shared_retry"), listOf(rootSession.id))
                        },
                    )
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
            }
            val retryingFamily = async {
                withRetryPolicy(RetryPolicy.request) {
                    NetworkRetry.clock.withValue(ImmediateClock()) {
                        OpenCodeClient.liveValue.sessionFamily(retry.server, rootSession)
                    }
                }
            }
            val onceResult = onceAncestry.await()
            val familyAfterRetry = retryingFamily.await()
            assertTrue(onceResult.exceptionOrNull() is OpenCodeClientError)
            assertEquals(setOf("ses_root", "ses_shared_retry"), familyAfterRetry.sessions.map { it.id }.toSet())
            assertEquals(
                "Shared attempt 1 failed once under .once, and the .request caller retried once",
                2,
                retry.metrics().requestCount,
            )
        } finally {
            retry.close()
        }
    }

    @Test
    fun forgettingModelCatalogsDropsOnlyThatServersCatalogs() {
        val cache = OpenCodeReadCache()
        val catalog = OpenCodeModelCatalog(listOf(OpenCodeModel(modelID = "m", providerID = "p", name = "M")), defaultModel = null)
        cache.storeModelCatalog(catalog, "srv|")
        cache.storeModelCatalog(catalog, "srv|/work/repo")
        cache.storeModelCatalog(catalog, "other|/work/repo")
        cache.forgetModelCatalogs("srv")
        assertNull(cache.modelCatalog("srv|"))
        assertNull(cache.modelCatalog("srv|/work/repo"))
        assertEquals(catalog, cache.modelCatalog("other|/work/repo"))
    }

    @Test
    fun sessionLookupRegistryPreservesRemainingWaitersOnCancelAndRestartsWhenAllWaitersCancel() = runTest {
        val serverKey = "lookup-registry-${UUID.randomUUID().toString().lowercase()}"
        val expected = OpenCodeSession(
            id = "ses_target",
            serverTitle = "Target",
            projectID = "p",
            directory = "/project",
            updatedAt = Instant.ofEpochSecond(1),
            parentID = "ses_root",
        )

        // 1. Two waiters share one lookup; cancelling Waiter 1 ends only Waiter 1's wait, keeps
        // the underlying operation alive, and delivers the result to Waiter 2.
        val firstGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = AtomicBoolean(false)
        val firstCallCount = AtomicInteger(0)

        val waiter1 = async {
            OpenCodeReadCache.shared.lookupSession(serverKey, "ses_shared") {
                firstCallCount.incrementAndGet()
                firstStarted.complete(Unit)
                try {
                    firstGate.await()
                } catch (cancelled: CancellationException) {
                    firstCancelled.set(true)
                    throw cancelled
                }
                Result.success(expected)
            }
        }
        firstStarted.await()

        val waiter2 = async {
            OpenCodeReadCache.shared.lookupSession(serverKey, "ses_shared") {
                firstCallCount.incrementAndGet()
                Result.success(null)
            }
        }
        runCurrent()

        waiter1.cancel()
        waiter1.join()
        assertTrue(waiter1.isCancelled)
        assertFalse("Underlying lookup stays alive while another waiter remains", firstCancelled.get())

        firstGate.complete(Unit)
        assertEquals(expected, waiter2.await().getOrThrow())
        assertEquals(1, firstCallCount.get())

        // 2. When the only waiter cancels, the underlying task is cancelled and removed, so a
        // subsequent caller starts a fresh lookup and receives its result.
        val soloStarted = CompletableDeferred<Unit>()
        val soloCancelled = CompletableDeferred<Unit>()
        val soloCallCount = AtomicInteger(0)

        val soloWaiter = async {
            OpenCodeReadCache.shared.lookupSession(serverKey, "ses_solo") {
                soloCallCount.incrementAndGet()
                soloStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    soloCancelled.complete(Unit)
                }
            }
        }
        soloStarted.await()
        soloWaiter.cancel()
        soloCancelled.await()
        soloWaiter.join()
        assertTrue(soloWaiter.isCancelled)

        val freshResult = OpenCodeReadCache.shared.lookupSession(serverKey, "ses_solo") {
            soloCallCount.incrementAndGet()
            Result.success(expected)
        }.getOrThrow()
        assertEquals(expected, freshResult)
        assertEquals(2, soloCallCount.get())
    }

    @Test
    fun syntheticInboxNeverRendersModelFacingShellPayloadAsUserText() = runTest {
        val fixture = pendingRequestsFixture(
            mapOf(
                "GET /api/session/ses_child/inbox" to Reply.Json(200, """{"data":[{"id":"msg_user","type":"user","payload":{"text":"Queued user prompt"}},{"id":"msg_shell","type":"synthetic","payload":{"text":"<shell>model-facing result</shell>","description":"xcodebuild build-for-testing","metadata":{"source":"shell","state":"completed"}}},{"id":"msg_invisible","type":"synthetic","payload":{"text":"hidden instruction"}}]}"""),
            ),
        )
        withFixture(fixture) {
            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_child")
            assertEquals(listOf("msg_user", "msg_shell"), inputs.map { it.id })
            assertEquals("Queued user prompt", inputs[0].text)
            assertTrue(inputs[1].text.isEmpty())
            val shellNotice = OpenCodeTranscriptItem.Notice(
                "msg_shell", "Shell finished · xcodebuild build-for-testing",
                kind = TranscriptNoticeKind(TranscriptNoticeKind.Source.Shell, TranscriptNoticeKind.Outcome.Finished),
            )
            assertEquals(shellNotice, inputs[1].notice)

            val transcript = decode("""{"data":[{"id":"msg_shell","type":"synthetic","text":"<shell state=\"completed\">raw tool output</shell>","description":"xcodebuild build-for-testing","metadata":{"source":"shell","state":"completed"}}]}""")
            assertEquals(listOf(shellNotice), transcript)
        }
    }

    /**
     * A subagent's completion, as a live V2 server reported it: first in the parent's inbox for
     * about 30 s while the parent was busy, then in history under the same ID. Both decode to
     * the same notice, so the row it folds into never changes on delivery.
     */
    @Test
    fun subagentCompletionDecodesToOneNoticeBeforeAndAfterDelivery() = runTest {
        val fixture = pendingRequestsFixture(mapOf("GET /api/session/ses_parent/inbox" to Reply.Json(200, PENDING_SUBAGENT_NOTICE)))
        withFixture(fixture) {
            val notice = OpenCodeTranscriptItem.Notice(
                id = "msg_11a3e095b001b2ncKsYKEYDBv2",
                text = "General finished · Small fixes: labels, rename test, awake",
                kind = TranscriptNoticeKind(TranscriptNoticeKind.Source.Subagent, TranscriptNoticeKind.Outcome.Finished),
            )

            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_parent")
            assertEquals(1, inputs.size)
            assertEquals(notice, inputs[0].notice)
            assertTrue(inputs[0].text.isEmpty())

            val history = decode(DELIVERED_SUBAGENT_NOTICE)
            assertEquals(listOf(notice), history)
        }
    }

    @Test
    fun syntheticNoticesSayHowTheWorkEnded() {
        for ((metadata, text, isError) in listOf(
            Triple("""{"source":"subagent","childID":"ses_child","agent":"General","state":"error"}""", "General failed · Review", true),
            Triple("""{"source":"subagent","childID":"ses_child","agent":"explore","state":"cancelled"}""", "Explore interrupted · Review", false),
            Triple("""{"source":"subagent","childID":"ses_child","agent":"code-reviewer","state":"completed"}""", "Code-Reviewer finished · Review", false),
            Triple("""{"source":"subagent","childID":"ses_child","state":"completed"}""", "Subagent finished · Review", false),
            Triple("""{"source":"subagent","childID":"ses_child","agent":"","state":"error"}""", "Subagent failed · Review", true),
            Triple("""{"source":"shell","shellID":"sh_1","state":"completed","truncated":false,"exit":0}""", "Shell finished · Review", false),
            Triple("""{"source":"shell","shellID":"sh_1","state":"completed","truncated":false,"exit":1}""", "Shell failed · Review", true),
            Triple("""{"source":"shell","shellID":"sh_1","state":"completed","truncated":false,"timeout":true}""", "Shell failed · Review", true),
            Triple("""{"source":"shell","state":"error"}""", "Shell failed · Review", true),
        )) {
            val history = decode("""{"data":[{"id":"msg_n","type":"synthetic","text":"<raw>model-facing</raw>","description":"Review","metadata":$metadata}]}""")
            val notice = history.singleOrNull() as? OpenCodeTranscriptItem.Notice
            val kind = notice?.kind
            if (notice == null || notice.id != "msg_n" || kind == null) {
                fail("Expected one notice: $history")
                return
            }
            assertEquals(text, notice.text)
            assertEquals(isError, notice.isError)
            val outcome = when {
                isError -> TranscriptNoticeKind.Outcome.Failed
                text.contains("interrupted") -> TranscriptNoticeKind.Outcome.Cancelled
                else -> TranscriptNoticeKind.Outcome.Finished
            }
            assertEquals(outcome, kind.outcome)
        }
    }

    /** Prompts another session's agent forwarded, and a subagent's first prompt from its parent, are known as agents' messages; typed ones aren't. */
    @Test
    fun userMessagesKnowWhenAnAgentSentThem() = runTest {
        val transcript = decode("""{"data":[{"id":"msg_typed","type":"user","text":"Typed here","metadata":{"notification":{"callbackURL":"https://relay"}}},{"id":"msg_forwarded","type":"user","text":"Rerun the tests","metadata":{"anomaly.session":{"originSessionID":"ses_other"}}},{"id":"msg_spawned","type":"user","text":"You are a subagent spawned by another session.\nAudit the spacing."}]}""")
        // Pages arrive newest first; only each message's source matters here.
        val byID = transcript.associateBy { it.id }
        assertEquals(3, byID.size)
        assertEquals(OpenCodeTranscriptItem.UserText("msg_typed", "Typed here"), byID["msg_typed"])
        assertEquals(OpenCodeTranscriptItem.UserText("msg_forwarded", "Rerun the tests", OpenCodePromptSource.Session("ses_other")), byID["msg_forwarded"])
        assertEquals(
            OpenCodeTranscriptItem.UserText("msg_spawned", "You are a subagent spawned by another session.\nAudit the spacing.", OpenCodePromptSource.ParentAgent),
            byID["msg_spawned"],
        )

        val fixture = pendingRequestsFixture(
            mapOf(
                "GET /api/session/ses_child/inbox" to Reply.Json(200, """{"data":[{"id":"msg_typed","type":"user","payload":{"text":"Typed"}},{"id":"msg_forwarded","type":"user","delivery":"queue","payload":{"text":"From another session","metadata":{"anomaly.session":{"originSessionID":"ses_other"}}}}]}"""),
            ),
        )
        withFixture(fixture) {
            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_child")
            assertEquals(listOf(null, OpenCodePromptSource.Session("ses_other")), inputs.map { it.source })
        }
    }

    /** Android's transcript cache format: an item saved before message sources still decodes. */
    @Test
    fun transcriptSnapshotsSavedBeforeMessageSourcesStillLoad() {
        val saved = """{"type":"userText","id":"msg_old","text":"Saved before sources"}"""
        assertEquals(
            OpenCodeTranscriptItem.UserText("msg_old", "Saved before sources"),
            CachedTranscriptSnapshots.persistenceJson.decodeFromString(OpenCodeTranscriptItem.serializer(), saved),
        )
    }

    @Test
    fun inboxRetainsDeliveryModeForPresentation() = runTest {
        val fixture = pendingRequestsFixture(
            mapOf(
                "GET /api/session/ses_child/inbox" to Reply.Json(200, """{"data":[{"id":"msg_steer","type":"user","delivery":"steer","payload":{"text":"Use this approach"}},{"id":"msg_queue","type":"user","delivery":"queue","payload":{"text":"Do this afterwards"}}]}"""),
            ),
        )
        withFixture(fixture) {
            val inputs = OpenCodeClient.liveValue.pendingInputs(fixture.server, "ses_child")
            assertEquals(listOf(OpenCodeDelivery.Steer, OpenCodeDelivery.Queue), inputs.map { it.delivery })
        }
    }

    @Test
    fun currentServerStatusAndRenamedRequests() = runTest {
        val fixture = pendingRequestsFixture(
            mapOf(
                "GET /api/info" to Reply.Json(200, """{"version":"test-current","pid":123,"urls":[],"paths":{"tmp":"/tmp"}}"""),
                "GET /api/form" to Reply.Json(200, FORMS),
                "GET /api/session/ses_child/inbox" to Reply.Json(200, """{"data":[{"id":"msg_queued","type":"user","payload":{"text":"Queued message"}}]}"""),
                "POST /api/experimental/session/ses_child/wait" to Reply.status(204),
                "DELETE /api/session/ses_child/form/frm_child" to Reply.status(204),
                "DELETE /api/session/ses_child" to Reply.status(204),
            ),
        )
        withFixture(fixture) {
            val client = OpenCodeClient.liveValue
            client.health(fixture.server)
            val pending = client.familyPendingRequests(fixture.server, listOf("ses_root", "ses_child"), "/project")
            assertEquals(listOf("frm_root", "frm_child", "frm_global"), pending.forms.map { it.id })
            assertEquals(listOf("per_child"), pending.permissions.map { it.id })
            val inputs = client.pendingInputs(fixture.server, "ses_child")
            assertEquals("Queued message", inputs.firstOrNull()?.text)
            client.waitForIdle(fixture.server, "ses_child")
            client.cancelForm(fixture.server, "ses_child", "frm_child", null)
            client.deleteSession(fixture.server, "ses_child")

            // Each call is one request to the current route.
            assertEquals(
                listOf(
                    "DELETE /api/session/ses_child",
                    "DELETE /api/session/ses_child/form/frm_child",
                    "GET /api/form",
                    "GET /api/info",
                    "GET /api/permission/request",
                    "GET /api/session/ses_child/inbox",
                    "POST /api/experimental/session/ses_child/wait",
                ),
                fixture.requests.map { it.route }.sorted(),
            )
        }
    }

    @Test
    fun pendingSnapshotFiltersFormsAndPermissionsToTheFamily() = runTest {
        val fixture = pendingRequestsFixture()
        withFixture(fixture) {
            val pending = OpenCodeClient.liveValue.familyPendingRequests(fixture.server, listOf("ses_root", "ses_child"), "/project")

            assertEquals(listOf("per_child"), pending.permissions.map { it.id })
            assertEquals(listOf("frm_root", "frm_child", "frm_global"), pending.forms.map { it.id })
            assertEquals("ses_child", pending.forms.firstOrNull { it.id == "frm_child" }?.sessionID)
            val field = pending.forms.first().fields.first()
            assertNull(field.options?.firstOrNull()?.description)
        }
    }

    @Test
    fun pendingSnapshotDoesNotTreatRealFailuresAsEmpty() = runTest {
        for ((route, reply, message) in listOf(
            Triple("GET /api/form", Reply.status(401), "The server rejected these credentials."),
            Triple("GET /api/form", Reply.status(500), "The server returned HTTP 500."),
            Triple("GET /api/form", Reply.Json(200, """{"data":[{"id":"frm_bad"}]}"""), "The server response does not match the current OpenCode V2 contract."),
            Triple("GET /api/permission/request", Reply.status(404), "The server returned HTTP 404."),
        )) {
            val fixture = pendingRequestsFixture(mapOf(route to reply))
            withFixture(fixture) {
                try {
                    OpenCodeClient.liveValue.familyPendingRequests(fixture.server, listOf("ses_root", "ses_child"), "/project")
                    fail("Expected the pending snapshot to fail when $route fails")
                } catch (error: OpenCodeClientError) {
                    assertEquals(message, error.message)
                }
            }
        }
    }

    @Test
    fun formAndPermissionRepliesUseTheirOwningChildSession() = runTest {
        withFixture(WireFixture { Reply.status(204) }) { fixture ->
            val client = OpenCodeClient.liveValue

            client.replyPermission(fixture.server, "ses_child", "per_child", OpenCodePermissionReply.Once, null)
            client.replyForm(fixture.server, "ses_child", "frm_child", mapOf("q0" to OpenCodeFormValue.Str("Ship")))
            client.cancelForm(fixture.server, "ses_child", "frm_child", null)

            assertEquals(
                listOf(
                    "POST /api/session/ses_child/permission/per_child/reply",
                    "POST /api/session/ses_child/form/frm_child/reply",
                    "DELETE /api/session/ses_child/form/frm_child",
                ),
                fixture.requests.map { it.route },
            )
            assertEquals(json("""{"decision":"once"}"""), fixture.requests[0].json())
            assertEquals(json("""{"answer":{"q0":"Ship"}}"""), fixture.requests[1].json())
            assertNull(fixture.requests[2].queryValue("message"))
        }
    }

    @Test
    fun denialsAndDismissalsCarryTheirReason() = runTest {
        withFixture(WireFixture { Reply.status(204) }) { fixture ->
            val client = OpenCodeClient.liveValue

            client.replyPermission(fixture.server, "ses_child", "per_child", OpenCodePermissionReply.Reject, "Write to run-2 instead")
            client.cancelForm(fixture.server, "ses_child", "frm_child", "Don’t ask, draft it")
            client.replyPermission(fixture.server, "ses_child", "per_child", OpenCodePermissionReply.Reject, "")

            assertEquals(json("""{"decision":"reject","message":"Write to run-2 instead"}"""), fixture.requests[0].json())
            assertEquals("DELETE /api/session/ses_child/form/frm_child", fixture.requests[1].route)
            assertEquals("Don’t ask, draft it", fixture.requests[1].queryValue("message"))
            assertEquals(json("""{"decision":"reject"}"""), fixture.requests[2].json())
        }
    }

    @Test
    fun globalFormRepliesAndCancellationsScopeToLocationDirectory() = runTest {
        withFixture(WireFixture { Reply.status(204) }) { fixture ->
            val client = OpenCodeClient.liveValue

            client.replyForm(fixture.server, "global", "frm_global", mapOf("mode" to OpenCodeFormValue.Str("anon")), directory = "/project")
            client.cancelForm(fixture.server, "global", "frm_global", "Skip for now", directory = "/project")
            client.replyForm(fixture.server, "ses_child", "frm_child", mapOf("q0" to OpenCodeFormValue.Str("Ship")), directory = "/project")

            assertEquals(
                listOf(
                    "POST /api/session/global/form/frm_global/reply",
                    "DELETE /api/session/global/form/frm_global",
                    "POST /api/session/ses_child/form/frm_child/reply",
                ),
                fixture.requests.map { it.route },
            )
            assertEquals("/project", fixture.requests[0].queryValue("location[directory]"))
            assertEquals(json("""{"answer":{"mode":"anon"}}"""), fixture.requests[0].json())
            assertEquals("/project", fixture.requests[1].queryValue("location[directory]"))
            assertEquals("Skip for now", fixture.requests[1].queryValue("message"))
            assertNull(fixture.requests[2].queryValue("location[directory]"))
        }
    }

    @Test
    fun anAgentsMalformedModelDecodesAsNoModel() {
        val text = """[{"name":"build","mode":"primary","model":"opencode/flash"},{"name":"plan","mode":"primary","model":{"providerID":"opencode","id":"opus"}}]"""
        val agents = WIRE_JSON.decodeFromString(ListSerializer(OpenCodeAutocompleteAgent.serializer()), text)
        assertNull(agents[0].model)
        assertEquals(OpenCodeModelRef(id = "opus", providerID = "opencode"), agents[1].model)
    }

    @Test
    fun permissionRequestsDecodeTheirSourceAndEditPreview() {
        val text = """
            {"id":"per_edit","sessionID":"ses_child","action":"edit","resources":["App.swift"],"save":["*"],
             "metadata":{"files":[{"file":"App.swift","patch":"@@ -1 +1 @@\n-old\n+new","additions":1,"deletions":1,"status":"modified"}]},
             "source":{"type":"tool","messageID":"msg_1","id":"call_1"},"message":"ignored"}
        """.trimIndent()
        val request = WIRE_JSON.decodeFromString(OpenCodePermissionRequest.serializer(), text)
        assertEquals(OpenCodePermissionRequest.Source(messageID = "msg_1", id = "call_1"), request.source)
        assertEquals("App.swift", request.filePreview?.first)
        assertEquals(true, request.filePreview?.second?.contains("+new"))
    }

    @Test
    fun declinesReadAsAnswersNotFailures() {
        val items = decode(
            """
            {"data":[{"id":"msg_a","type":"assistant","content":[
              {"type":"tool","id":"call_q","name":"question","state":{"status":"completed","input":{"questions":[{"question":"Bring Slack forward?","header":"Send in Slack","options":[]}]},"metadata":{"answers":[["Bring Slack forward"]]}}},
              {"type":"tool","id":"call_d","name":"shell","state":{"status":"error","input":{"command":"rm -rf .lab-output"},"error":{"type":"aborted","message":"The user declined this tool call"}}},
              {"type":"tool","id":"call_r","name":"shell","state":{"status":"error","input":{"command":"rm -rf .lab-output"},"error":{"type":"permission.rejected","message":"Use run-2"}}},
              {"type":"tool","id":"call_x","name":"question","state":{"status":"error","input":{"questions":[{"question":"Which?","header":"","options":[]}]},"error":{"type":"aborted","message":"The user dismissed this question"}}},
              {"type":"tool","id":"call_f","name":"shell","state":{"status":"error","input":{"command":"false"},"error":{"type":"tool.execution","message":"exit 1"}}}
            ]}]}
            """.trimIndent(),
        )
        val calls = toolCalls(items)
        assertEquals(
            listOf(null, OpenCodeToolCall.Decline.Denied, OpenCodeToolCall.Decline.Denied, OpenCodeToolCall.Decline.Dismissed, null),
            calls.map { it.decline },
        )
        assertEquals(listOf(listOf("Bring Slack forward")), calls[0].questionAnswers)
        assertNull(calls[1].declineReason)
        assertEquals("Use run-2", calls[2].declineReason)
        assertNull(calls[3].declineReason)
    }

    @Test
    fun decodesDetailedToolStatesAndShellOutput() {
        val items = decode(
            """
            {"data":[
              {"id":"msg_shell","type":"shell","shellID":"sh_1","command":"git status","status":"exited","exit":0,"output":{"output":"clean"}},
              {"id":"msg_failed_shell","type":"shell","shellID":"sh_2","command":"false","status":"exited","exit":1,"output":{"output":""}},
              {"id":"msg_assistant","type":"assistant","content":[
                {"type":"reasoning","text":"Thinking","state":{"itemId":"reasoning_1","reasoningEncryptedContent":"opaque"}},
                {"type":"tool","id":"call_1","name":"edit","state":{"status":"completed","input":{"path":"App.swift"},"content":[{"type":"text","text":"Edited App.swift"}],"metadata":{"files":[{"file":"App.swift","patch":"@@ -1 +1 @@"}]}}},
                {"type":"tool","id":"call_2","name":"shell","state":{"status":"error","input":{"command":"false"},"error":{"type":"tool.execution","message":"Exited 1"},"content":[{"type":"text","text":"partial"}]}}
              ]}
            ]}
            """.trimIndent(),
        )

        assertEquals(4, items.size)
        val calls = toolCalls(items)
        val shell = calls.firstOrNull { it.callID == "sh_1" }
        val failedShell = calls.firstOrNull { it.callID == "sh_2" }
        val edit = calls.firstOrNull { it.callID == "call_1" }
        val failed = calls.firstOrNull { it.callID == "call_2" }
        if (shell == null || failedShell == null || edit == null || failed == null) {
            fail("Expected three tool transcript items")
            return
        }
        assertEquals("git status", shell.subtitle)
        assertEquals(listOf("clean"), shell.output)
        assertEquals(OpenCodeToolCall.Status.Error, failedShell.status)
        assertEquals("Command exited with code 1", failedShell.error)
        assertEquals("call_1", edit.callID)
        assertEquals("msg_assistant", edit.messageID)
        assertEquals("App.swift", edit.subtitle)
        assertEquals(listOf("Edited App.swift"), edit.output)
        assertNotNull(edit.metadata["files"])
        assertEquals(OpenCodeToolCall.Status.Error, failed.status)
        assertEquals("Exited 1", failed.error)
        assertEquals(listOf("partial"), failed.output)
    }

    @Test
    fun toolRowsUseDesktopNamesDetailsAndReadableArguments() {
        fun call(name: String, input: Map<String, OpenCodeJSONValue>) = toolCall(callID = "c", name = name, input = OpenCodeJSONValue.Object(input))
        val shell = call(
            "shell",
            mapOf(
                "command" to OpenCodeJSONValue.Str("swift test --filter Layout\necho done"),
                "description" to OpenCodeJSONValue.Str("Check session layout"),
                "timeout" to OpenCodeJSONValue.Number(120_000.0),
            ),
        )
        assertEquals("Shell", shell.displayName)
        assertEquals("Shell", call("bash", emptyMap()).displayName)
        assertEquals("Shell rows show the command, like the desktop app", "swift test --filter Layout", shell.subtitle)
        assertEquals("The command and description are not repeated as arguments", listOf("timeout"), shell.arguments.map { it.first })
        assertEquals("120000", shell.arguments.firstOrNull()?.second)

        val read = call("read", mapOf("path" to OpenCodeJSONValue.Str("/repo/OpenCode/SessionView.swift"), "offset" to OpenCodeJSONValue.Number(40.0)))
        assertEquals("SessionView.swift", read.subtitle)
        assertEquals(listOf("offset", "path"), read.arguments.map { it.first })
        assertEquals("Todo write", call("todo_write", emptyMap()).displayName)
        assertEquals(
            """["*.swift"]""",
            call("grep", mapOf("pattern" to OpenCodeJSONValue.Str("TODO"), "include" to OpenCodeJSONValue.Array(listOf(OpenCodeJSONValue.Str("*.swift")))))
                .arguments.firstOrNull { it.first == "include" }?.second,
        )

        val streaming = call("edit", emptyMap()).copy(input = OpenCodeJSONValue.Str("""{"path": "a.sw"""))
        assertEquals("Partial streamed input stays readable text", listOf("input"), streaming.arguments.map { it.first })
    }

    @Test
    fun toolResultFileBlocksProjectImageAttachments() {
        // Any PNG bytes do here.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52)
        val base64 = Base64.getEncoder().encodeToString(png)
        val payload = """
            {"data":[{"id":"msg_reply","type":"assistant","content":[{"type":"tool","id":"call_read","name":"read","state":{
              "status":"completed","input":{"path":"preview.png"},"content":[
                {"type":"text","text":"Image read successfully"},
                {"type":"file","mime":"image/png","name":"preview.png","uri":"data:image/png;base64,$base64"},
                {"type":"file","mime":"application/pdf","uri":"data:application/pdf;base64,AA=="},
                {"type":"file","mime":"IMAGE/PNG","name":"preview-upper.png","uri":"data:IMAGE/PNG;base64,$base64"},
                {"type":"file","mime":"image/png","uri":"https://example.test/remote.png"}
              ]}}]}]}
        """.trimIndent()
        val call = toolCalls(decode(payload)).first()
        assertEquals(listOf("Image read successfully"), call.output)
        assertEquals(listOf("msg_reply-tool-call_read-image-1", "msg_reply-tool-call_read-image-3"), call.imageAttachments.map { it.id })
        assertTrue(call.imageAttachments.all { it.data.contentEquals(png) })
        assertEquals(2, call.imageAttachments.size)

        // Older persisted transcript snapshots have no `images` key.
        val json = CachedTranscriptSnapshots.persistenceJson
        val old = json.encodeToString(
            OpenCodeToolCall.serializer(),
            OpenCodeToolCall(callID = "call_old", name = "read", status = OpenCodeToolCall.Status.Completed, input = OpenCodeJSONValue.Null, output = listOf("Done"), metadata = emptyMap()),
        )
        assertFalse(old.contains("\"images\""))
        val legacy = json.decodeFromString(OpenCodeToolCall.serializer(), old)
        assertTrue(legacy.imageAttachments.isEmpty())
    }

    @Test
    fun transcriptPageKeepsOpaqueCursorAndChronologicalItems() {
        val page = decodeTranscriptPage(
            """{"data":[{"id":"newer","type":"user","text":"Newer"},{"id":"older","type":"user","text":"Older"}],"cursor":{"previous":"newer-page","next":"opaque/older+page="}}""".toByteArray(),
            null,
        )
        assertEquals(listOf(OpenCodeTranscriptItem.UserText("older", "Older"), OpenCodeTranscriptItem.UserText("newer", "Newer")), page.items)
        assertEquals("opaque/older+page=", page.next)
        val last = decodeTranscriptPage("""{"data":[],"cursor":{}}""".toByteArray(), null)
        assertNull(last.next)
    }

    @Test
    fun boundedJSONKeepsSmallValuesAndTrimsLargeOnesDeterministically() {
        // Multi-byte text is bounded by characters, not bytes.
        assertEquals(OpenCodeJSONValue.Str("ééé"), OpenCodeJSONValue.Str("ééé").bounded(stringLimit = 3))
        assertEquals(OpenCodeJSONValue.Str("abc\n… output truncated"), OpenCodeJSONValue.Str("abcd").bounded(stringLimit = 3))

        val small = OpenCodeJSONValue.Object(
            mapOf("b" to OpenCodeJSONValue.Array(listOf(OpenCodeJSONValue.Str("abcd"), OpenCodeJSONValue.Null)), "a" to OpenCodeJSONValue.Bool(true)),
        )
        assertEquals(
            OpenCodeJSONValue.Object(
                mapOf("b" to OpenCodeJSONValue.Array(listOf(OpenCodeJSONValue.Str("abc\n… output truncated"), OpenCodeJSONValue.Null)), "a" to OpenCodeJSONValue.Bool(true)),
            ),
            small.bounded(stringLimit = 3, collectionLimit = 2),
        )

        val large = OpenCodeJSONValue.Object((0 until 5).associate { "k$it" to OpenCodeJSONValue.Number(it.toDouble()) })
        assertEquals(
            OpenCodeJSONValue.Object(mapOf("k0" to OpenCodeJSONValue.Number(0.0), "k1" to OpenCodeJSONValue.Number(1.0), "k2" to OpenCodeJSONValue.Number(2.0))),
            large.bounded(collectionLimit = 3),
        )
        assertEquals(
            OpenCodeJSONValue.Array(listOf(OpenCodeJSONValue.Number(0.0), OpenCodeJSONValue.Number(1.0), OpenCodeJSONValue.Number(2.0))),
            OpenCodeJSONValue.Array((0 until 5).map { OpenCodeJSONValue.Number(it.toDouble()) }).bounded(collectionLimit = 3),
        )
    }

    @Test
    fun runErrorsReadInPlainWordsWithTheirRawCodeKept() {
        val items = decode(
            """
            {"data":[
              {"id":"msg_idle","type":"idle","outcome":"failed","error":{"type":"provider.no-route","message":"Model unavailable: fixture/x"}},
              {"id":"msg_dropped","type":"assistant","content":[],"error":{"type":"provider.transport","message":"Connection lost while reading the response: ECONNRESET"}},
              {"id":"msg_filter","type":"assistant","content":[],"error":{"type":"provider.content-filter","message":"Provider blocked the response"}},
              {"id":"msg_long","type":"assistant","content":[],"error":{"type":"provider.invalid-request","message":"prompt is too long: 214733 tokens > 200000 maximum","status":400}},
              {"id":"msg_rate","type":"assistant","content":[],"error":{"type":"provider.rate-limit","message":"Rate limited {\"code\":429}","status":429}},
              {"id":"msg_stopped","type":"assistant","content":[],"error":{"type":"aborted","message":"Step interrupted"}},
              {"id":"msg_ok","type":"idle","outcome":"succeeded"}
            ]}
            """.trimIndent(),
        )
        val errors = items.filterIsInstance<OpenCodeTranscriptItem.RunError>().map { it.id to it.error }
        // The server lists newest first; the transcript is chronological.
        assertEquals(
            listOf("msg_stopped-error", "msg_rate-error", "msg_long-error", "msg_filter-error", "msg_dropped-error", "msg_idle"),
            errors.map { it.first },
        )
        assertEquals(
            listOf(
                "Interrupted",
                "Rate limit reached",
                "Session too long for this model",
                "Safety filter blocked the reply",
                "Connection to the model dropped",
                "This model isn’t available",
            ),
            errors.map { it.second.line },
        )
        assertEquals(
            listOf(
                null, OpenCodeRunError.Action.TryAgain, OpenCodeRunError.Action.Compact, OpenCodeRunError.Action.Edit,
                OpenCodeRunError.Action.TryAgain, OpenCodeRunError.Action.SwitchModel,
            ),
            errors.map { it.second.action },
        )
        assertEquals("provider.rate-limit · 429\nRate limited {\"code\":429}", errors[1].second.details)
        assertTrue(errors[5].second.beforeReply)
        assertTrue(errors[0].second.isStop && errors[0].second.actions.isEmpty())
    }

    @Test
    fun aRetryingStepCountsDownOnTheWorkingLine() {
        val items = decode(
            """
            {"data":[
              {"id":"msg_a","type":"assistant","content":[],"retry":{"attempt":2,"at":1760000030000,"error":{"type":"provider.rate-limit","message":"Rate limited","status":429}}}
            ]}
            """.trimIndent(),
        )
        val retryItem = items.lastOrNull() as? OpenCodeTranscriptItem.RunRetry ?: return fail("no retry: $items")
        assertEquals("msg_a-retry", retryItem.id)
        assertEquals(2, retryItem.retry.attempt)
        assertEquals("Rate limit reached · retrying in 4 s", retryItem.retry.title(Instant.ofEpochSecond(1_760_000_026)))
        assertEquals("Rate limit reached · retrying", retryItem.retry.title(Instant.ofEpochSecond(1_760_000_031)))
    }

    @Test
    fun decodesCompactionStatesLikeTheTUI() {
        val tokens = """"tokens":{"input":1000,"output":200,"reasoning":50,"cache":{"read":300,"write":0}}"""
        val text = """
            {"data":[
              {"id":"c_run","type":"compaction","status":"running","reason":"auto","summary":"","recent":""},
              {"id":"c_done","type":"compaction","status":"completed","reason":"manual","summary":"  ## Summary\nKept the plan.  ","recent":"",$tokens},
              {"id":"c_provider","type":"compaction","status":"completed","reason":"auto","summary":"","recent":"","providerContext":{"id":"ctx"}},
              {"id":"c_abort","type":"compaction","status":"failed","reason":"manual","error":{"type":"aborted","message":"Aborted"}},
              {"id":"c_fail","type":"compaction","status":"failed","reason":"auto","error":{"type":"unknown","message":"Summary hit the output limit"},$tokens}
            ]}
        """.trimIndent()
        val infos = decode(text).filterIsInstance<OpenCodeTranscriptItem.Compaction>().mapNotNull { it.info }
        // The server lists newest first; the transcript is chronological.
        assertEquals(
            listOf(
                OpenCodeCompaction(status = OpenCodeCompaction.Status.Running),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed, summary = "## Summary\nKept the plan.", inputTokens = 1300, outputTokens = 250),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.Completed, isProvider = true),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.Cancelled),
                OpenCodeCompaction(status = OpenCodeCompaction.Status.Failed, errorMessage = "Summary hit the output limit", inputTokens = 1300, outputTokens = 250),
            ),
            infos.reversed(),
        )

        // A snapshot persisted before compaction states still decodes.
        val legacy = CachedTranscriptSnapshots.persistenceJson.decodeFromString(OpenCodeTranscriptItem.serializer(), """{"type":"compaction","id":"old"}""")
        assertEquals(OpenCodeTranscriptItem.Compaction("old"), legacy)
    }

    @Test
    fun questionToolInputSupportsChoiceMultipleAndFreeResponsePrompts() {
        val input = OpenCodeJSONValue.of(
            Json.parseToJsonElement(
                """
                {"questions":[
                  {"header":"Scope","question":"What should ship?","multiple":false,"options":[
                    {"label":"App only","description":"Smallest change"},
                    {"label":"Everything","description":"Include related work"}
                  ]},
                  {"header":"Checks","question":"Which checks should run?","multiple":true,"custom":true,"options":[
                    {"label":"Unit tests","description":"Fast"},
                    {"label":"Simulator","description":"Full UI"}
                  ]},
                  {"question":"Anything else?","custom":true}
                ]}
                """.trimIndent(),
            ),
        )
        val call = toolCall(callID = "call_question", name = "question", status = OpenCodeToolCall.Status.Running, input = input)

        assertEquals(
            listOf(
                OpenCodeQuestion(question = "What should ship?", header = "Scope"),
                OpenCodeQuestion(question = "Which checks should run?", header = "Checks"),
                OpenCodeQuestion(question = "Anything else?", header = ""),
            ),
            call.questions,
        )
    }

    @Test
    fun malformedQuestionToolInputFallsBackToGenericToolUI() {
        val input = OpenCodeJSONValue.of(Json.parseToJsonElement("""{"questions":[{"header":"Missing question"},{"question":"  "}]}"""))
        val call = toolCall(callID = "call_question", name = "question", status = OpenCodeToolCall.Status.Running, input = input)

        assertNull(call.questions)
    }

    @Test
    fun formConditionsAndAcknowledgementsFollowV2Semantics() {
        // Decoded inside a form: `OpenCodeFormField`'s serializer isn't public on its own.
        val fields = WIRE_JSON.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """
            {"id":"frm","sessionID":"ses","title":"Fields","fields":[
              {"key":"conditional","type":"string","when":[{"key":"choice","op":"neq","value":"skip"}]},
              {"key":"member","type":"string","when":[{"key":"choices","op":"eq","value":"one"}]},
              {"key":"external","type":"external","url":"https://example.com"},
              {"key":"boolean","type":"boolean","required":true}
            ]}
            """.trimIndent(),
        ).fields

        assertFalse(fields[0].isVisible(emptyMap()))
        assertTrue(fields[0].isVisible(mapOf("choice" to OpenCodeFormValue.Str("other"))))
        assertTrue(fields[1].isVisible(mapOf("choices" to OpenCodeFormValue.Strings(listOf("one", "two")))))
        assertEquals("Complete this step", fields[2].validationError(OpenCodeFormValue.Boolean(false)))
        assertNull(fields[2].validationError(OpenCodeFormValue.Boolean(true)))
        assertNull(fields[3].validationError(OpenCodeFormValue.Boolean(false)))
    }

    @Test
    fun formVisibilityIsProgressiveAcrossHiddenFields() {
        val form = WIRE_JSON.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """
            {"id":"frm_1","sessionID":"ses_1","title":"Conditional","fields":[
              {"key":"a","type":"boolean"},
              {"key":"b","type":"string","when":[{"key":"a","op":"eq","value":true}]},
              {"key":"c","type":"string","when":[{"key":"b","op":"eq","value":"show"}]},
              {"key":"external","type":"external","url":"https://example.com"}
            ]}
            """.trimIndent(),
        )
        val answers = mapOf("a" to OpenCodeFormValue.Boolean(false), "b" to OpenCodeFormValue.Str("show"))
        assertEquals(listOf("a", "external"), form.activeFields(answers).map { it.key })
        assertEquals(OpenCodeFormValue.Boolean(false), form.defaultAnswers["external"])
    }

    // Pending-request server

    /**
     * The server's pending requests for `/project`. Each [overrides] entry replaces one route's
     * reply, keyed by `Request.route`.
     */
    private fun pendingRequestsFixture(overrides: Map<String, Reply> = emptyMap()): WireFixture = WireFixture { request ->
        val locationReads = listOf("/api/permission/request", "/api/form")
        if (request.path in locationReads && (request.method != "GET" || request.queryValue("location[directory]") != "/project")) {
            return@WireFixture Reply.status(400)
        }
        overrides[request.route]?.let { return@WireFixture it }
        when (request.route) {
            "GET /api/permission/request" ->
                Reply.Json(200, """{"data":[{"id":"per_child","sessionID":"ses_child","action":"shell","resources":["git status"]},{"id":"per_other","sessionID":"ses_other","action":"shell","resources":["pwd"]}]}""")
            "GET /api/form" -> Reply.Json(200, FORMS)
            else -> Reply.status(404)
        }
    }

    private companion object {
        /** Like the client's wire codec: unknown keys ignored. */
        val WIRE_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        const val FORMS = """{"data":[{"id":"frm_root","sessionID":"ses_root","title":"Questions","metadata":{"kind":"question"},"fields":[{"key":"q0","type":"string","options":[{"value":"Ship","label":"Ship"}],"custom":true}]},{"id":"frm_child","sessionID":"ses_child","title":"Child questions","fields":[{"key":"q0","type":"string"}]},{"id":"frm_global","sessionID":"global","title":"Sign in","fields":[{"key":"token","type":"string"}]},{"id":"frm_other","sessionID":"ses_other","title":"Other session","fields":[{"key":"q0","type":"string"}]}]}"""

        /** Captured from `GET /api/session/<id>/inbox` on October 7, 2026; the subagent's model-facing report is shortened. */
        const val PENDING_SUBAGENT_NOTICE = """{"data":[{"id":"msg_11a3e095b001b2ncKsYKEYDBv2","sessionID":"ses_parent","time":{"created":1791442444550},"type":"synthetic","payload":{"text":"<subagent sessionID=\"ses_child\" state=\"completed\" description=\"Small fixes: labels, rename test, awake\">\nThe baseline rename UI run on unmodified main is still going.\n</subagent>","description":"Small fixes: labels, rename test, awake","metadata":{"source":"subagent","childID":"ses_child","agent":"General","state":"completed"}},"delivery":"steer"}]}"""

        /** The same notice in `GET /api/session/<id>/message` once delivered. */
        const val DELIVERED_SUBAGENT_NOTICE = """{"data":[{"id":"msg_11a3e095b001b2ncKsYKEYDBv2","metadata":{"source":"subagent","childID":"ses_child","agent":"General","state":"completed"},"time":{"created":1791442474575},"text":"<subagent sessionID=\"ses_child\" state=\"completed\" description=\"Small fixes: labels, rename test, awake\">\nThe baseline rename UI run on unmodified main is still going.\n</subagent>","description":"Small fixes: labels, rename test, awake","type":"synthetic"}]}"""
    }
}

/**
 * Answers `GET /api/session/{id}` 35 ms later (on OkHttp's thread) with a child of `ses_root`,
 * counting in-flight lookups.
 */
private class DelayedAncestryFixture(
    val host: String,
    familyActiveIDs: List<String> = emptyList(),
    transient503IDs: Set<String> = emptySet(),
) : AutoCloseable {
    data class Metrics(
        var requestCount: Int = 0,
        var inFlight: Int = 0,
        var maxInFlight: Int = 0,
        val familyActiveIDs: List<String> = emptyList(),
        val transient503IDs: Set<String> = emptySet(),
        val attemptsByID: MutableMap<String, Int> = HashMap(),
    )

    private val lock = Any()
    private val metrics = Metrics(familyActiveIDs = familyActiveIDs, transient503IDs = transient503IDs)

    val server: OpenCodeServer get() = OpenCodeServer(url = "https://$host", username = "opencode", password = "secret")

    fun metrics(): Metrics = synchronized(lock) { metrics.copy(attemptsByID = HashMap(metrics.attemptsByID)) }

    init {
        TestNetworkBlock.registerMock(host, Interceptor { chain -> respond(chain) })
    }

    override fun close() = TestNetworkBlock.unregisterMock(host)

    private fun respond(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val path = request.url.encodedPath
        if (path == "/api/session") return response(request, 200, """{"data":[]}""")
        if (path == "/api/session/active") {
            val entries = metrics.familyActiveIDs.joinToString(",") { "\"$it\":{\"type\":\"busy\"}" }
            return response(request, 200, "{\"data\":{$entries}}")
        }
        val id = path.removePrefix("/api/session/")
        val shouldFail503 = synchronized(lock) {
            metrics.requestCount += 1
            metrics.inFlight += 1
            metrics.maxInFlight = maxOf(metrics.maxInFlight, metrics.inFlight)
            val attempt = (metrics.attemptsByID[id] ?: 0) + 1
            metrics.attemptsByID[id] = attempt
            id in metrics.transient503IDs && attempt == 1
        }
        Thread.sleep(35)
        synchronized(lock) { metrics.inFlight -= 1 }
        if (shouldFail503) return response(request, 503, """{"error":{"message":"Unavailable"}}""")
        return response(request, 200, "{\"data\":${sessionJSON(id, parentID = "ses_root")}}")
    }

    private fun response(request: okhttp3.Request, status: Int, body: String): okhttp3.Response = okhttp3.Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message("")
        .header("Content-Type", "application/json")
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()
}

private fun sessionJSON(id: String, parentID: String? = null): String {
    val parentFragment = parentID?.let { ",\"parentID\":\"$it\"" } ?: ""
    return "{\"id\":\"$id\",\"projectID\":\"p\",\"title\":\"$id\",\"location\":{\"directory\":\"/project\"},\"time\":{\"updated\":1000}$parentFragment}"
}
