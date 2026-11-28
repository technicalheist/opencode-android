package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** Reply text as link extraction reads it (the transcript maps its Markdown blocks into these). */
internal object LinkBlocks {
    fun text(value: String) = LinkTextRun(value)
    fun link(value: String, url: String) = LinkTextRun(value, link = url)
    fun code(value: String) = LinkTextRun(value, isCode = true)
    fun paragraph(id: Int, vararg runs: LinkTextRun) = LinkTextBlock(id = id, prose = runs.toList())
    /** A code block, image, divider…: no link-bearing text. */
    fun other(id: Int) = LinkTextBlock(id = id)
}

/**
 * `LinkPreviewSummary` (the editorial card's lines) is the transcript's; only its accessibility
 * labels are checked here. `extractionFindsLinksInOrderOnceAndSkipsPrivateOnes` reads bare URLs
 * with the framework's `Patterns.WEB_URL`, so it runs under Robolectric in
 * [LinkPreviewFrameworkTests].
 */
class LinkPreviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun target(string: String): LinkPreviewTarget? = LinkPreviewTarget.of(string)

    // Classification

    @Test
    fun xPostsAreRecognisedInEveryShareForm() {
        assertEquals(LinkPreviewTarget.XPost(handle = "example_dev", id = "1844000000000000001"), target("https://x.com/example_dev/status/1844000000000000001"))
        assertEquals(LinkPreviewTarget.XPost(handle = "labnotes_demo", id = "3"), target("https://twitter.com/labnotes_demo/status/3?s=46&t=AbCdEf"))
        assertEquals(LinkPreviewTarget.XPost(handle = "a_b", id = "42"), target("https://mobile.twitter.com/a_b/statuses/42/photo/1"))
        assertEquals(LinkPreviewTarget.XPost(handle = "i", id = "20"), target("https://x.com/i/status/20"))
        assertEquals("https://x.com/example_dev/status/1", target("https://x.com/example_dev/status/1")?.url)
    }

    @Test
    fun otherLinksArePlainWebTargets() {
        assertEquals(LinkPreviewTarget.Web("https://x.com/example_dev"), target("https://x.com/example_dev"))
        assertEquals(LinkPreviewTarget.Web("https://x.com/example_dev/status/notanumber"), target("https://x.com/example_dev/status/notanumber"))
        assertEquals(LinkPreviewTarget.Web("https://x.com/way_too_long_a_handle/status/1"), target("https://x.com/way_too_long_a_handle/status/1"))
        assertEquals(LinkPreviewTarget.Web("https://blog.example.com/post"), target("https://blog.example.com/post/?utm_source=x"))
        assertEquals(LinkPreviewTarget.Web("https://example.com/x.com/a/status/1"), target("https://example.com/x.com/a/status/1"))
    }

    @Test
    fun privateAndSignedLinksAreNeverPreviewed() {
        for (url in listOf(
            "http://localhost:5173", "http://app.localhost/", "https://printer.local/status", "http://intranet/wiki",
            "http://127.0.0.1/", "http://192.168.1.10/admin", "http://10.0.0.2", "http://[::1]:8080/", "https://100.101.102.103/",
            "https://kits-mac.tail1234.ts.net/", "https://example.com:8443/", "https://user:secret@example.com/",
            "https://bucket.s3.amazonaws.com/f.png?X-Amz-Signature=abc&X-Amz-Credential=def",
            "https://example.com/reset?token=abc", "https://example.com/oauth?code=xyz", "https://example.com/a?api_key=1",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q#frag",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/Ab3_x-9Q?utm_source=x",
            "https://opencode.abc123.opentunnel.xyz/auth/connect/bad!code",
            "opencode-ios://pair?code=Ab3_x-9Q&url=https://mini-trial.taila8e7c7.ts.net:4196",
            "file:///etc/hosts", "ftp://example.com/file",
        )) {
            assertNull(url, target(url))
        }
    }

    @Test
    fun normalisationFoldsXHostsAndDropsTracking() {
        assertEquals("https://x.com/a/status/1", LinkPreviewPolicy.normalized("https://mobile.twitter.com/a/status/1#reply"))
        assertEquals(
            "https://blog.example.com/posts/scroll-physics",
            LinkPreviewPolicy.normalized("HTTPS://Blog.Example.com/posts/scroll-physics/?utm_source=newsletter&fbclid=1"),
        )
        // Meaningful parameters stay; `t` only means a share tracker on X.
        assertEquals("https://www.youtube.com/watch?v=abc&t=42", LinkPreviewPolicy.normalized("https://www.youtube.com/watch?v=abc&t=42"))
    }

    @Test
    fun hostsOkHttpCantReadBackAreNotPreviewed() {
        assertEquals("https://xn--bcher-kva.de/", LinkPreviewPolicy.normalized("https://bücher.de./"))
        for (url in listOf(
            "https://example.com，然后运行命令",
            "https://example.com，然后运行命令。",
            "https://example.com%EF%BC%8C%E7%84%B6%E5%90%8E",
            "https://example.com）里有说明",
            "https://bücher_x.de/",
        )) {
            assertNull(url, LinkPreviewPolicy.normalized(url))
            assertNull(url, target(url))
        }
    }

    // oEmbed

    @Test
    fun oEmbedRequestCarriesOnlyTheHandleAndPostID() {
        val request = XOEmbed.request(handle = "example_dev", id = "1844")
        assertEquals("publish.x.com", request.url.host)
        assertEquals("/oembed", request.url.encodedPath)
        assertEquals("https://x.com/example_dev/status/1844", request.url.queryParameter("url"))
        // The preview transport has no cookie jar.
        assertNull(request.header("Cookie"))
        assertNull(request.body)
    }

    @Test
    fun oEmbedGivesAuthorHandleAndPlainText() {
        val metadata = XOEmbed.metadata(oEmbedFixture, handle = "fallback")
        assertEquals(
            LinkPreviewMetadata(
                kind = LinkPreviewMetadata.Kind.Post,
                title = "Example Dev",
                handle = "example_dev",
                text = "Springs & friction: keep the velocity.\nNever snap — it's jarring. https://example.com/demo",
            ),
            metadata,
        )
        assertNull("No author, no card", XOEmbed.metadata("""{"html":"<p>x</p>"}""".toByteArray(), handle = "a"))
        assertNull(XOEmbed.metadata("not json".toByteArray(), handle = "a"))
    }

    // Display

    @Test
    fun displayFollowsWhatIsKnown() {
        val post = LinkPreviewTarget.XPost(handle = "example_dev", id = "1")
        val loaded = LinkPreview.of(
            post,
            LinkPreviewResult.Loaded(LinkPreviewMetadata(kind = LinkPreviewMetadata.Kind.Post, title = "Example Dev", handle = "example_dev", text = "Springs.")),
        )
        assertEquals(LinkPreview.Display.Post(author = "Example Dev", handle = "example_dev", text = "Springs."), loaded.display)
        assertEquals("Example Dev, Springs., x.com", loaded.accessibilityLabel)
        assertEquals(LinkPreview.Display.Pending, LinkPreview.of(post, null).display)
        assertEquals("@example_dev, Post, x.com", LinkPreview.of(post, null).accessibilityLabel)
        assertEquals(LinkPreview.Display.Plain, LinkPreview.of(post, LinkPreviewResult.Unavailable).display)
        // `x.com/i/status/…` names no one: no "@i" while loading, oEmbed's handle once loaded.
        val anonymous = LinkPreviewTarget.XPost(handle = "i", id = "20")
        assertNull(LinkPreview.of(anonymous, null).handle)
        assertEquals("Post, x.com", LinkPreview.of(anonymous, null).accessibilityLabel)
        assertEquals(
            "example_dev",
            LinkPreview.of(anonymous, LinkPreviewResult.Loaded(LinkPreviewMetadata(kind = LinkPreviewMetadata.Kind.Post, title = "Example Dev", handle = "example_dev"))).handle,
        )
        val web = LinkPreview.of(LinkPreviewTarget.Web("https://docs.example.org/guides/springs"), LinkPreviewResult.Unavailable)
        assertEquals(LinkPreview.Display.Plain, web.display)
        assertEquals("Read as it shows, not as a raw path", "Springs, docs.example.org", web.accessibilityLabel)
    }

    @Test
    fun editorialSummaryShowsWhatTheURLAndMetadataSay() {
        val pr = LinkPreviewTarget.of("https://github.com/acme/widget/pull/1251")!!
        assertEquals("acme/widget #1251, Pull request, github.com", LinkPreview.of(pr, null).accessibilityLabel)
        val video = LinkPreviewTarget.of("https://www.youtube.com/watch?v=aBcD3fGh1jK")!!
        assertEquals("Read as the line shows it", "YouTube, Video", LinkPreview.of(video, null).accessibilityLabel)
    }

    @Test
    fun plainLinksAreNamedFromTheirPathWhenItReads() {
        fun title(string: String) = LinkPreview.readableTitle(string)
        assertEquals("Background transfers", title("https://docs.example.org/guides/background-transfers"))
        assertEquals("Incidents 42", title("https://status.example.net/incidents/42"))
        assertEquals("How we ship", title("https://example.com/blog/how_we_ship.html"))
        assertNull(title("https://example.com/"))
        assertNull("A hash isn't a name", title("https://example.com/a1b2c3d4e5f6"))
        assertNull("An ID alone isn't either", title("https://example.com/42"))
        assertNull("Nor a letter-heavy hash", title("https://example.com/deadbeefcafe1234"))
        assertEquals("An index page is its folder", "Background transfers", title("https://example.com/guides/background-transfers/index.HTML"))
        assertEquals("Guides", title("https://example.com/guides/---"))
        assertEquals("Percent-encoded, non-Latin names read", "入門", title("https://example.com/docs/%E5%85%A5%E9%96%80"))
        assertNull(title("https://example.com/?q=swift"))
    }

    @Test
    fun pendingLinksSayWhatTheyAre() {
        val issue = LinkPreview.of(LinkPreviewTarget.of("https://github.com/acme/widget/issues/5")!!, null)
        assertEquals("Read as it shows", "acme/widget #5, Issue, github.com", issue.accessibilityLabel)
        val bare = LinkPreview.of(LinkPreviewTarget.Web("https://example.com/a1b2c3d4e5f6"), LinkPreviewResult.Unavailable)
        assertEquals("example.com", bare.accessibilityLabel)
    }

    companion object {
        val oEmbedFixture: ByteArray = """
            {"url":"https:\/\/x.com\/example_dev\/status\/1844","author_name":"Example Dev","author_url":"https:\/\/x.com\/example_dev",
             "html":"<blockquote class=\"twitter-tweet\" data-dnt=\"true\"><p lang=\"en\" dir=\"ltr\">Springs &amp; friction: keep the velocity.<br>Never snap &#8212; it&#39;s jarring. <a href=\"https:\/\/t.co\/abc\">https:\/\/example.com\/demo<\/a> <a href=\"https:\/\/t.co\/xyz\">pic.twitter.com\/AbC123<\/a><\/p>&mdash; Example Dev (@example_dev) <a href=\"https:\/\/x.com\/example_dev\/status\/1844\">October 8, 2026<\/a><\/blockquote>\n",
             "width":550,"height":null,"type":"rich","cache_age":"3153600000","provider_name":"X","provider_url":"https:\/\/x.com","version":"1.0"}
        """.trimIndent().toByteArray()
    }
}

/** Times are milliseconds. */
class LinkPreviewLoaderTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Server(val status: Int = 200, val delayMillis: Long = 0, val fails: Boolean = false) {
        val requests = Locked(listOf<Request>())
        val count: Int get() = requests.current.size
        val fetch: LinkPreviewFetch = { request ->
            requests.update { it + request }
            if (delayMillis > 0) delay(delayMillis)
            if (fails) throw IOException("The Internet connection appears to be offline.")
            LinkPreviewResponse(LinkPreviewTests.oEmbedFixture, status)
        }
    }

    private val post = LinkPreviewTarget.XPost(handle = "example_dev", id = "1844")

    @Test
    fun concurrentLoadsShareOneRequestAndThenHitMemory() = runTest {
        val server = Server(delayMillis = 50)
        val loader = LinkPreviewLoader(fetch = server.fetch, directory = null)
        val first = async { loader.load(post) }
        val second = async { loader.load(post) }
        val results = listOf(first.await(), second.await())
        assertEquals(1, server.count)
        val metadata = (results[0] as? LinkPreviewResult.Loaded)?.metadata
        assertEquals("Example Dev", metadata?.title)
        assertEquals(results[0], results[1])
        assertEquals(results[0], loader.cached(post))
        assertEquals(results[0], loader.load(post))
        assertEquals("Memory answers the next load", 1, server.count)
    }

    @Test
    fun diskHitsStayWithinTheMemoryLimit() = runTest {
        val directory = temporaryDirectory("link-preview-tests")
        try {
            val posts = (0 until 100).map { LinkPreviewTarget.XPost(handle = "example_dev", id = "$it") }
            val server = Server()
            val first = LinkPreviewLoader(fetch = server.fetch, directory = directory, memoryWarnings = MemoryWarningCenter())
            for (post in posts) first.load(post)
            assertEquals(64, first.memoryCount)
            val restarted = LinkPreviewLoader(fetch = server.fetch, directory = directory, memoryWarnings = MemoryWarningCenter())
            for (post in posts) restarted.load(post)
            assertEquals("The restarted loader read every record from disk", 100, server.count)
            assertEquals("Records read from disk keep the limit too", 64, restarted.memoryCount)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aMemoryWarningEmptiesTheLoaderButNotTheDisk() = runTest {
        val directory = temporaryDirectory("link-preview-tests")
        try {
            val center = MemoryWarningCenter()
            val server = Server()
            val loader = LinkPreviewLoader(fetch = server.fetch, directory = directory, memoryWarnings = center)
            val result = loader.load(post)
            assertEquals(result, loader.cached(post))
            center.post()
            assertNull(loader.cached(post))
            assertEquals(result, loader.load(post))
            assertEquals("Read back from disk, not fetched", 1, server.count)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun diskOutlivesTheLoader() = runTest {
        val directory = temporaryDirectory("link-preview-tests")
        try {
            val server = Server()
            val result = LinkPreviewLoader(fetch = server.fetch, directory = directory).load(post)
            val offline = Server(fails = true)
            val next = LinkPreviewLoader(fetch = offline.fetch, directory = directory)
            assertNull("Memory starts empty", next.cached(post))
            assertEquals(result, next.load(post))
            assertEquals(0, offline.count)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun expiredRecordsAreFetchedAgain() = runTest {
        val clock = Locked(0L)
        val server = Server()
        val loader = LinkPreviewLoader(fetch = server.fetch, directory = null, now = { clock.current })
        loader.load(post)
        clock.update { it + LinkPreviewLoader.LOADED_LIFETIME + 1000 }
        assertNull(loader.cached(post))
        loader.load(post)
        assertEquals(2, server.count)
    }

    @Test
    fun aMissingPostIsRememberedButAFailureIsNot() = runTest {
        val missing = Server(status = 404)
        val loader = LinkPreviewLoader(fetch = missing.fetch, directory = null)
        assertEquals(LinkPreviewResult.Unavailable, loader.load(post))
        assertEquals(LinkPreviewResult.Unavailable, loader.load(post))
        assertEquals(1, missing.count)

        val offline = Server(fails = true)
        val other = LinkPreviewLoader(fetch = offline.fetch, directory = null)
        assertEquals(LinkPreviewResult.Deferred, other.load(post))
        assertNull(other.cached(post))
        assertEquals(LinkPreviewResult.Deferred, other.load(post))
        assertEquals("Offline or Data Saver: tried again next time", 2, offline.count)
    }

    @Test
    fun sweepDeletesExpiredRecords() = runTest {
        val directory = temporaryDirectory("link-preview-tests")
        try {
            val clock = Locked(0L)
            val loader = LinkPreviewLoader(fetch = Server().fetch, directory = directory, now = { clock.current })
            loader.load(post)
            File(directory, "junk.json").writeText("not a record")
            loader.sweep()
            assertEquals("A fresh record stays; junk goes", 1, directory.listFiles()!!.size)
            clock.update { it + LinkPreviewLoader.LOADED_LIFETIME + 1000 }
            loader.sweep()
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun otherLinksAreNeverFetched() = runTest {
        val server = Server()
        val loader = LinkPreviewLoader(fetch = server.fetch, directory = null)
        assertEquals(LinkPreviewResult.Unavailable, loader.load(LinkPreviewTarget.Web("https://blog.example.com/post")))
        assertEquals(0, server.count)
    }
}

/**
 * Times are milliseconds; `targetsAreExtractedOncePerReply` is in [LinkPreviewStoreFrameworkTests].
 */
class LinkPreviewStoreTests {
    @get:Rule val isolation = TestIsolationRule()

    private val post = LinkPreviewTarget.XPost(handle = "example_dev", id = "1844")
    private val metadata = LinkPreviewMetadata(kind = LinkPreviewMetadata.Kind.Post, title = "Example Dev", handle = "example_dev", text = "Springs.")
    private val shown = LinkPreview.Display.Post(author = "Example Dev", handle = "example_dev", text = "Springs.")

    @Test
    fun firstViewFetchesOnceAndReloadsEveryRowShowingIt() = runTest {
        onMain {
            val loads = Locked(0)
            val gate = CompletableDeferred<Unit>()
            val store = LinkPreviewStore(
                client = LinkPreviewClient(
                    cached = { null },
                    load = {
                        loads.update { it + 1 }
                        gate.await()
                        LinkPreviewResult.Loaded(metadata)
                    },
                ),
            )
            val reloaded = mutableListOf<List<String>>()
            store.onArrival = { reloaded += it }
            assertEquals(LinkPreview.Display.Pending, store.preview(post).display)
            store.appeared(post, row = "reply-end-b")
            store.appeared(post, row = "reply-end-a")
            store.appeared(post, row = "reply-end-a")
            gate.complete(Unit)
            assertTrue(eventually { reloaded.isNotEmpty() })
            assertEquals(1, loads.current)
            assertEquals(listOf(listOf("reply-end-a", "reply-end-b")), reloaded)
            assertTrue("A reloaded row redraws", store.revision("reply-end-a") == 1 && store.revision("reply-end-b") == 1)
            assertEquals(0, store.revision("reply-end-c"))
            assertEquals(shown, store.preview(post).display)
            store.appeared(post, row = "reply-end-c")
            delay(20)
            assertEquals("Known metadata isn't fetched again", 1, loads.current)
            store.close()
        }
    }

    @Test
    fun cachedMetadataNeedsNoReloadAndWebLinksStayPlain() = runTest {
        onMain {
            val store = LinkPreviewStore(client = LinkPreviewClient(cached = { LinkPreviewResult.Loaded(metadata) }, load = { LinkPreviewResult.Unavailable }))
            var arrivals = 0
            store.onArrival = { arrivals += 1 }
            assertEquals(shown, store.preview(post).display)
            store.appeared(post, row = "reply-end-a")
            val web = LinkPreviewTarget.Web("https://docs.example.org/guides")
            store.appeared(web, row = "reply-end-a")
            assertEquals(LinkPreview.Display.Plain, store.preview(web).display)
            assertEquals(0, arrivals)
            store.close()
        }
    }

    @Test
    fun aRowDrawnBeforeAnotherLoadedItRedrawsOnAppearance() = runTest {
        onMain {
            val cache = Locked<LinkPreviewResult?>(null)
            val store = LinkPreviewStore(client = LinkPreviewClient(cached = { cache.current }, load = { LinkPreviewResult.Unavailable }))
            val reloaded = mutableListOf<List<String>>()
            store.onArrival = { reloaded += it }
            assertEquals("Drawn before the answer", LinkPreview.Display.Pending, store.preview(post).display)
            cache.set(LinkPreviewResult.Loaded(metadata))
            store.appeared(post, row = "reply-end-a")
            assertEquals("The row drawn pending reloads", listOf(listOf("reply-end-a")), reloaded)
            assertEquals(1, store.revision("reply-end-a"))
            store.appeared(post, row = "reply-end-a")
            assertEquals("Once", 1, reloaded.size)
            store.close()
        }
    }

    @Test
    fun staleMetadataShowsWhileItRevalidates() = runTest {
        onMain {
            val loads = Locked(0)
            val store = LinkPreviewStore(
                client = LinkPreviewClient(
                    cached = { null },
                    stale = { metadata },
                    load = {
                        loads.update { it + 1 }
                        LinkPreviewResult.Deferred
                    },
                ),
            )
            var arrivals = 0
            store.onArrival = { arrivals += 1 }
            assertEquals(shown, store.preview(post).display)
            store.appeared(post, row = "reply-end-a")
            delay(30)
            assertEquals("Expired metadata is fetched again", 1, loads.current)
            assertEquals("A failed refresh changes nothing on screen", 0, arrivals)
            assertEquals(shown, store.preview(post).display)
            store.close()
        }
    }

    @Test
    fun anExpiredFailureStaysPlainWhileItsRetried() = runTest {
        onMain {
            val clock = Locked(0L)
            val loads = Locked(0)
            val store = LinkPreviewStore(
                client = LinkPreviewClient(cached = { null }, load = {
                    loads.update { it + 1 }
                    LinkPreviewResult.Deferred
                }),
                now = { clock.current },
            )
            var arrivals = 0
            store.onArrival = { arrivals += 1 }
            store.appeared(post, row = "reply-end-a")
            delay(30)
            assertEquals(LinkPreview.Display.Plain, store.preview(post).display)
            clock.update { it + 31_000 }
            assertEquals("Never back to pending", LinkPreview.Display.Plain, store.preview(post).display)
            store.appeared(post, row = "reply-end-a")
            delay(30)
            assertEquals("Retried after the backoff", 2, loads.current)
            assertEquals("Only the first answer changed the row", 1, arrivals)
            store.close()
        }
    }

    @Test
    fun suppliedMetadataIsTheSeamForServerPages() = runTest {
        onMain {
            val store = LinkPreviewStore(client = LinkPreviewClient())
            val url = "https://blog.example.com/posts/scroll-physics?utm_source=x"
            store.supply(
                LinkPreviewMetadata(kind = LinkPreviewMetadata.Kind.Page, title = "Designing scroll physics", text = "Springs.", site = "Example Blog"),
                url = url,
            )
            assertEquals(
                LinkPreview.Display.Page(title = "Designing scroll physics", site = "Example Blog", text = "Springs."),
                store.preview(LinkPreviewTarget.of(url)!!).display,
            )
            store.close()
        }
    }

    @Test
    fun expiredEntryKeepsLastGoodPreviewDuringAndAfterFailedRefresh() = runTest {
        onMain {
            val clock = Locked(0L)
            val pr = LinkPreviewTarget.of("https://github.com/acme/widget/pull/42")!!
            val initial = LinkPreviewMetadata(
                kind = LinkPreviewMetadata.Kind.Github,
                title = "Add jittered backoff",
                site = "GitHub",
                github = GitHubPreview(repository = "acme/widget", kind = GitHubLink.Kind.PullRequest, reference = "42", state = GitHubPreview.State.Open, author = "alex"),
            )
            val updated = LinkPreviewMetadata(
                kind = LinkPreviewMetadata.Kind.Github,
                title = "Add jittered backoff",
                site = "GitHub",
                github = GitHubPreview(repository = "acme/widget", kind = GitHubLink.Kind.PullRequest, reference = "42", state = GitHubPreview.State.Merged, author = "alex"),
            )
            val calls = Locked(0)
            val refreshGate = CompletableDeferred<Unit>()
            val store = LinkPreviewStore(
                client = LinkPreviewClient(
                    cached = { null },
                    load = {
                        val call = calls.withValue { (it + 1) to (it + 1) }
                        when (call) {
                            1 -> LinkPreviewResult.Loaded(initial)
                            2 -> {
                                refreshGate.await()
                                LinkPreviewResult.Deferred
                            }
                            else -> LinkPreviewResult.Loaded(updated)
                        }
                    },
                ),
                now = { clock.current },
            )
            val reloaded = mutableListOf<List<String>>()
            store.onArrival = { reloaded += it }
            store.appeared(pr, row = "row-1")
            assertTrue(eventually { calls.current == 1 && reloaded.isNotEmpty() })
            val first = LinkPreview.Display.Github(title = "Add jittered backoff", details = initial.github!!, text = null)
            assertEquals(first, store.preview(pr).display)

            // Expire the 5-minute TTL and trigger a refresh while offline/rate-limited.
            clock.update { it + 301_000 }
            assertEquals(first, store.preview(pr).display)
            store.appeared(pr, row = "row-1")
            // While the background refresh is in flight, the last good rich metadata stays visible.
            assertEquals(first, store.preview(pr).display)
            refreshGate.complete(Unit)
            delay(30)
            assertEquals(2, calls.current)
            // After the refresh fails with Deferred, the rich card is still preserved.
            assertEquals(first, store.preview(pr).display)
            assertEquals(1, reloaded.size)

            // After the 30 s retry window, a successful refresh updates the card in place.
            clock.update { it + 31_000 }
            store.appeared(pr, row = "row-1")
            assertTrue(eventually { reloaded.size == 2 })
            assertEquals(3, calls.current)
            assertEquals(LinkPreview.Display.Github(title = "Add jittered backoff", details = updated.github!!, text = null), store.preview(pr).display)
            store.close()
        }
    }
}
