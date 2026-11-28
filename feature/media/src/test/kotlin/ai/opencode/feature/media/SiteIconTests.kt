package ai.opencode.feature.media

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.testing.eventually
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.IOException

class SiteIconDomainTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun domain(string: String): String? = SiteIconDomain.registrable(string)

    @Test
    fun onlyTheRegistrableDomainRemains() {
        assertEquals("exe.dev", domain("https://docs.exe.dev/regions?tab=eu#top"))
        assertEquals("evil.com", domain("https://secret.evil.com/"))
        assertEquals("evil.com", domain("https://aGVsbG8.c2VjcmV0.evil.com/x"))
        assertEquals("exe.dev", domain("https://exe.dev"))
        assertEquals("example.com", domain("https://WWW.Example.COM/Path"))
        assertEquals("Dropped labels needn't be hostnames", "example.com", domain("http://a_b.example.com/"))
    }

    @Test
    fun multiPartSuffixesKeepOneMoreLabel() {
        assertEquals("bbc.co.uk", domain("https://www.bbc.co.uk/news"))
        assertEquals("bbc.co.uk", domain("https://news.bbc.co.uk"))
        assertEquals("example.com.au", domain("https://shop.example.com.au/"))
        assertEquals("example.co.jp", domain("https://a.b.example.co.jp/"))
        assertNull("A public suffix has no registrable domain", SiteIconDomain.registrableHost("co.uk"))
        assertNull(SiteIconDomain.registrableHost("com"))
        // A suffix the list lacks sends fewer labels, never more.
        assertEquals("blogspot.com", SiteIconDomain.registrableHost("secret.example.blogspot.com"))
        assertEquals("github.io", SiteIconDomain.registrableHost("secret.user.github.io"))
    }

    @Test
    fun trailingDotsAndMalformedHosts() {
        assertEquals("exe.dev", domain("https://docs.exe.dev./regions"))
        assertEquals("exe.dev", SiteIconDomain.registrableHost("exe.dev.."))
        assertNull(SiteIconDomain.registrableHost("a..exe.dev"))
        assertNull("A kept label must be a hostname", SiteIconDomain.registrableHost("ex_e.dev"))
        assertNull(SiteIconDomain.registrableHost("-exe.dev"))
        assertNull(SiteIconDomain.registrableHost("exe.dev/x"))
        assertNull(SiteIconDomain.registrableHost("exe.123"))
    }

    @Test
    fun internationalNamesAreSentAsPunycode() {
        assertEquals("xn--bcher-kva.de", domain("https://shop.bücher.de/x"))
        assertEquals("xn--bcher-kva.de", domain("https://shop.xn--bcher-kva.de/x"))
        assertEquals("xn--r8jz45g.jp", domain("https://www.例え.jp/"))
        assertNull("Unencoded Unicode is never sent", SiteIconDomain.registrableHost("bücher.de"))
    }

    @Test
    fun onlyPreviewableLinksHaveOne() {
        for (link in listOf(
            "http://localhost:5173/", "http://127.0.0.1/", "http://192.168.1.4/admin", "https://[::1]/",
            "https://nas.local/", "https://box.tail1234.ts.net/", "https://intranet/", "https://example.com:8443/",
            "https://user:pass@example.com/", "https://example.com/file?X-Amz-Signature=abc", "https://example.com/?token=abc",
            "ftp://example.com/", "file:///etc/hosts",
        )) {
            assertNull(link, domain(link))
        }
    }

    @Test
    fun theRequestCarriesOnlyTheDomain() {
        val request = SiteIconService.request("exe.dev")!!
        assertEquals("https://icons.duckduckgo.com/ip3/exe.dev.ico", request.url.toString())
        // The preview transport has no cookie jar.
        assertNull(request.header("Cookie"))
        assertNull("Never a subdomain", SiteIconService.request("docs.exe.dev"))
        assertNull(SiteIconService.request("evil.com/secret"))
        assertEquals("https://icons.duckduckgo.com/ip3/bbc.co.uk.ico", SiteIconService.request("bbc.co.uk")?.url?.toString())
    }
}

/**
 * The loader's caching and coalescing. Runs under Robolectric's native graphics so the service
 * answers real icons ([IconFixture]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SiteIconLoaderTests {
    @get:Rule val isolation = TestIsolationRule()

    private class Service(
        val status: Int = 200,
        val body: ByteArray = IconFixture.png(side = 64),
        val delayMillis: Long = 0,
        val fails: Boolean = false,
    ) {
        val requests = Locked(listOf<Request>())
        val count: Int get() = requests.current.size
        val fetch: LinkPreviewFetch = { request ->
            requests.update { it + request }
            if (delayMillis > 0) delay(delayMillis)
            if (fails) throw IOException("The Internet connection appears to be offline.")
            LinkPreviewResponse(body, status)
        }
    }

    @Test
    fun siteIconsKeepAtMost128InMemory() = runTest {
        val loader = SiteIconLoader(fetch = Service().fetch, directory = null, memoryWarnings = MemoryWarningCenter())
        for (index in 0 until 150) loader.load("site$index.com")
        assertEquals(128, loader.memoryCount)
    }

    @Test
    fun aMemoryWarningEmptiesSiteIconMemory() = runTest {
        val directory = temporaryDirectory("site-icon-tests")
        try {
            val center = MemoryWarningCenter()
            val service = Service()
            val loader = SiteIconLoader(fetch = service.fetch, directory = directory, memoryWarnings = center)
            val icon = loader.load("exe.dev")
            assertEquals(1, loader.memoryCount)
            center.post()
            assertEquals(0, loader.memoryCount)
            assertNull(loader.cached("exe.dev"))
            assertEquals(icon, loader.load("exe.dev"))
            assertEquals("Read back from disk, not fetched", 1, service.count)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun onlyRegistrableDomainsAreRequested() = runTest {
        val service = Service()
        val loader = SiteIconLoader(fetch = service.fetch, directory = null)
        assertEquals(SiteIconResult.Missing, loader.load("docs.exe.dev"))
        assertEquals(SiteIconResult.Missing, loader.load("localhost"))
        assertEquals(0, service.count)
    }

    @Test
    fun missingAndUnusableIconsAreRemembered() = runTest {
        val directory = temporaryDirectory("site-icon-tests")
        try {
            val missing = Service(status = 404)
            val loader = SiteIconLoader(fetch = missing.fetch, directory = directory)
            assertEquals(SiteIconResult.Missing, loader.load("example.com"))
            assertEquals(SiteIconResult.Missing, loader.load("example.com"))
            assertEquals(1, missing.count)
            val restarted = SiteIconLoader(fetch = missing.fetch, directory = directory)
            assertEquals(SiteIconResult.Missing, restarted.load("example.com"))
            assertEquals("The disk remembers it too", 1, missing.count)

            val tiny = Service(body = IconFixture.png(side = 16))
            val other = SiteIconLoader(fetch = tiny.fetch, directory = null)
            assertEquals(SiteIconResult.Missing, other.load("example.org"))
            assertEquals(SiteIconResult.Missing, other.load("example.org"))
            assertEquals("A 16 px icon isn't fetched again either", 1, tiny.count)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failuresToReachTheServiceAreNotRemembered() = runTest {
        for (service in listOf(Service(fails = true), Service(status = 503))) {
            val loader = SiteIconLoader(fetch = service.fetch, directory = null)
            assertEquals(SiteIconResult.Deferred, loader.load("exe.dev"))
            assertNull(loader.cached("exe.dev"))
            assertEquals(SiteIconResult.Deferred, loader.load("exe.dev"))
            assertEquals(2, service.count)
        }
    }

    @Test
    fun oneRequestPerDomainThenMemory() = runTest {
        val service = Service(delayMillis = 50)
        val loader = SiteIconLoader(fetch = service.fetch, directory = null)
        val first = async { loader.load("exe.dev") }
        val second = async { loader.load("exe.dev") }
        val results = listOf(first.await(), second.await())
        assertTrue("Expected an icon", results[0] is SiteIconResult.Icon)
        assertEquals(results[0], results[1])
        assertEquals(1, service.count)
        assertEquals(results[0], loader.cached("exe.dev"))
        assertEquals(results[0], loader.load("exe.dev"))
        assertEquals(1, service.count)
        assertEquals(listOf("https://icons.duckduckgo.com/ip3/exe.dev.ico"), service.requests.current.map { it.url.toString() })
    }

    @Test
    fun diskOutlivesTheLoaderAndExpires() = runTest {
        val directory = temporaryDirectory("site-icon-tests")
        try {
            val clock = Locked(0L)
            val service = Service()
            val result = SiteIconLoader(fetch = service.fetch, directory = directory, now = { clock.current }).load("exe.dev")
            val offline = Service(fails = true)
            val next = SiteIconLoader(fetch = offline.fetch, directory = directory, now = { clock.current })
            assertNull("Memory starts empty", next.cached("exe.dev"))
            assertEquals(result, next.load("exe.dev"))
            assertEquals(0, offline.count)
            clock.update { it + SiteIconLoader.ICON_LIFETIME + 1000 }
            assertNull(next.cached("exe.dev"))
            assertEquals(SiteIconResult.Deferred, next.load("exe.dev"))
            assertEquals(1, offline.count)
            next.sweep()
            assertEquals(0, directory.listFiles()?.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}

/**
 * Icons are [stubSiteIcon]s; `aSuppliedIconWins` decodes a real one in
 * [SiteIconStoreFrameworkTests].
 */
class SiteIconStoreTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun store(result: SiteIconResult, loads: Locked<List<String>>): LinkPreviewStore =
        LinkPreviewStore(
            client = LinkPreviewClient(
                cachedIcon = { null },
                loadIcon = { domain ->
                    loads.update { it + domain }
                    delay(20)
                    result
                },
            ),
        )

    private val blog = LinkPreviewTarget.of("https://blog.example.com/posts/1")!!
    private val docs = LinkPreviewTarget.of("https://docs.example.com/guide")!!

    @Test
    fun anArrivingIconReloadsEveryRowShowingItsDomain() = runTest {
        onMain {
            val icon = stubSiteIcon("example")
            val loads = Locked(listOf<String>())
            val store = store(SiteIconResult.Icon(icon), loads)
            val reloaded = mutableListOf<List<String>>()
            store.onArrival = { reloaded += it }
            assertNull("The globe until it arrives", store.preview(blog).icon)
            store.appeared(blog, row = "reply-end-b")
            store.appeared(docs, row = "reply-end-a")
            store.appeared(blog, row = "reply-end-a")
            assertTrue(eventually { reloaded.isNotEmpty() })
            assertEquals("One request for the domain", listOf("example.com"), loads.current)
            assertEquals(listOf(listOf("reply-end-a", "reply-end-b")), reloaded)
            assertEquals(1, store.revision("reply-end-a"))
            assertEquals(icon, store.preview(blog).icon)
            assertEquals(icon, store.preview(docs).icon)
            assertEquals(LinkPreview.Display.Plain, store.preview(blog).display)
            store.appeared(blog, row = "reply-end-c")
            delay(40)
            assertEquals(1, loads.current.size)
            store.close()
        }
    }

    @Test
    fun aMissingIconLeavesTheGlobeWithoutAReload() = runTest {
        onMain {
            val loads = Locked(listOf<String>())
            val store = store(SiteIconResult.Missing, loads)
            var arrivals = 0
            store.onArrival = { arrivals += 1 }
            store.appeared(blog, row = "reply-end-a")
            assertTrue(eventually { loads.current.size == 1 })
            delay(60)
            store.appeared(blog, row = "reply-end-b")
            assertEquals(0, arrivals)
            assertNull(store.preview(blog).icon)
            assertEquals("Remembered for the session", 1, loads.current.size)
            store.close()
        }
    }

    @Test
    fun postsAndPrivateLinksAskForNoIcon() = runTest {
        onMain {
            val loads = Locked(listOf<String>())
            val store = store(SiteIconResult.Missing, loads)
            store.appeared(LinkPreviewTarget.XPost(handle = "example_dev", id = "1844"), row = "reply-end-a")
            store.appeared(LinkPreviewTarget.Web("http://localhost:5173/secret"), row = "reply-end-a")
            store.appeared(LinkPreviewTarget.Web("https://example.com/?token=abc"), row = "reply-end-a")
            delay(60)
            assertTrue(loads.current.isEmpty())
            store.close()
        }
    }

    @Test
    fun inlineLinksAskOnceByRegistrableDomainAndPrivateOnesNever() = runTest {
        onMain {
            val icon = stubSiteIcon("vercel")
            val loads = Locked(listOf<String>())
            val store = store(SiteIconResult.Icon(icon), loads)
            val reloaded = mutableListOf<List<String>>()
            store.onArrival = { reloaded += it }
            val device = "https://vercel.com/oauth/device?user_code=SPTL-FFCD"
            assertNull("The globe until it arrives", store.inlineIcon(device))
            store.inlineLinksAppeared(
                listOf(
                    device,
                    "https://docs.vercel.com/cli",
                    "http://devbox.tail1234.ts.net:3000/preview",
                    "http://localhost:5173/",
                    "https://example.com/?token=abc",
                ),
                row = "reply",
            )
            assertTrue(eventually { reloaded.isNotEmpty() })
            assertEquals("Only the registrable domain, once; never a private or credentialed link", listOf("vercel.com"), loads.current)
            assertEquals(listOf(listOf("reply")), reloaded)
            assertEquals(icon, store.inlineIcon(device))
            assertNull(store.inlineIcon("http://devbox.tail1234.ts.net:3000/preview"))
            store.close()
        }
    }

    @Test
    fun aCachedIconDrawsAtOnce() = runTest {
        onMain {
            val icon = stubSiteIcon("example")
            val store = LinkPreviewStore(client = LinkPreviewClient(cachedIcon = { if (it == "example.com") SiteIconResult.Icon(icon) else null }))
            assertEquals(icon, store.preview(blog).icon)
            assertNull(store.preview(LinkPreviewTarget.of("https://exe.dev/")!!).icon)
            store.close()
        }
    }
}
