package ai.opencode.feature.media

import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.MediaReferenceFetch
import ai.opencode.server.model.OpenCodeImageAttachment
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Robolectric's native graphics decode the sample PNG. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaReferenceTests {
    @get:Rule val isolation = TestIsolationRule()

    private val hashA = "a1".repeat(32)
    private val hashB = "b2".repeat(32)

    private fun samplePNGData(color: Int = Color.BLUE): ByteArray {
        val image = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { out ->
            image.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("media-ref-tests-").toFile()

    @Test
    fun loaderKeepsCachedDataInMemoryOnlyAndScopes404PerServerInMemory() = runTest {
        val tempDir = tempDir()
        try {
            val server1 = OpenCodeServer(url = "https://server-one.invalid", username = "user", password = "secret")
            val server2 = OpenCodeServer(url = "https://server-two.invalid", username = "user", password = "secret")
            val blobData = samplePNGData()
            val calls = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            val fetch = MediaReferenceLoader.Fetch { server, hash ->
                calls += server.url to hash
                if (hash == hashB && server.url == server1.url) {
                    throw OpenCodeClientError("The server returned HTTP 404.", statusCode = 404)
                }
                MediaReferenceFetch.Response(200, blobData)
            }

            // A private center: another test's memory warning would purge the memory tier mid-check.
            val center = MemoryWarningCenter()
            val client = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)

            // 1. Cold miss -> 200 fetch, stores in memory and disk
            val first = client.load(server1, hashA)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), first)
            assertEquals(1, calls.size)
            assertArrayEquals(blobData, client.cachedData(hashA))

            // 2. Warm memory hit -> no network request, shared across servers by hash
            val second = client.load(server2, hashA)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), second)
            assertEquals(1, calls.size)

            // 3. Fresh loader backed by the same disk directory: `cachedData` is memory-only
            //    (null before a load), and `load` reads disk without the network.
            val diskClient = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)
            assertNull(diskClient.cachedData(hashA))
            val fromDisk = diskClient.load(server1, hashA)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), fromDisk)
            assertArrayEquals(blobData, diskClient.cachedData(hashA))
            assertEquals(1, calls.size)

            // 4. HTTP 404 on server1 -> cached in memory for server1 only, not server2 and not on disk
            val missingOnServer1 = client.load(server1, hashB)
            assertEquals(MediaReferenceLoadResult.Unavailable, missingOnServer1)
            assertTrue(client.isUnavailable(server1, hashB))
            assertFalse(client.isUnavailable(server2, hashB))
            assertEquals(2, calls.size)

            // Repeat on server1 hits per-server in-memory 404 cache
            val missingAgainOnServer1 = client.load(server1, hashB)
            assertEquals(MediaReferenceLoadResult.Unavailable, missingAgainOnServer1)
            assertEquals(2, calls.size)

            // Fresh loader (simulating relaunch) does not retain 404 across launches
            val relaunchedClient = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)
            assertFalse(relaunchedClient.isUnavailable(server1, hashB))

            // Loading hashB from server2 succeeds and stores positive content-addressed bytes
            val loadedOnServer2 = client.load(server2, hashB)
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), loadedOnServer2)
            assertEquals(3, calls.size)
            assertFalse(client.isUnavailable(server1, hashB))
            assertArrayEquals(blobData, client.cachedData(hashB))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun memoryWarningPurgesTheMemoryTierAndKeepsDiskAnd404s() = runTest {
        val tempDir = tempDir()
        try {
            val server = OpenCodeServer(url = "https://server-one.invalid", username = "user", password = "secret")
            val blobData = samplePNGData()
            var calls = 0
            val fetch = MediaReferenceLoader.Fetch { _, hash ->
                synchronized(this@MediaReferenceTests) { calls += 1 }
                if (hash == hashB) throw OpenCodeClientError("The server returned HTTP 404.", statusCode = 404)
                MediaReferenceFetch.Response(200, blobData)
            }
            // A private center, so other tests' warnings never purge this loader mid-test.
            val center = MemoryWarningCenter()
            val client = MediaReferenceClient.live(directory = tempDir, fetch = fetch, memoryWarnings = center)

            assertEquals(MediaReferenceLoadResult.Loaded(blobData), client.load(server, hashA))
            assertEquals(MediaReferenceLoadResult.Unavailable, client.load(server, hashB))
            assertArrayEquals(blobData, client.cachedData(hashA))
            assertEquals(2, calls)

            center.post()
            assertNull(client.cachedData(hashA))
            assertTrue(client.isUnavailable(server, hashB))

            // The disk tier answers without the network and refills memory.
            assertEquals(MediaReferenceLoadResult.Loaded(blobData), client.load(server, hashA))
            assertArrayEquals(blobData, client.cachedData(hashA))
            assertEquals(MediaReferenceLoadResult.Unavailable, client.load(server, hashB))
            assertEquals(2, calls)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun theDiskTierTrimsLeastRecentlyUsedBlobsPastItsBudget() = runTest {
        val tempDir = tempDir()
        try {
            val server = OpenCodeServer(url = "https://server-one.invalid", username = "user", password = "secret")
            val hashes = listOf("c3", "d4", "e5", "f6").map { it.repeat(32) }
            var fetches = 0
            val loader = MediaReferenceLoader(
                directory = tempDir,
                fetch = MediaReferenceLoader.Fetch { _, _ ->
                    synchronized(this@MediaReferenceTests) { fetches += 1 }
                    MediaReferenceFetch.Response(200, ByteArray(1024))
                },
                memoryWarnings = MemoryWarningCenter(),
                maxDiskBytes = 3 * 1024,
            )
            fun onDisk(hash: String) = File(tempDir, "$hash.blob").isFile

            for (hash in hashes.take(3)) loader.load(server, hash)
            assertTrue("at the budget, nothing goes", hashes.take(3).all(::onDisk))
            val now = System.currentTimeMillis()
            for ((index, hash) in hashes.take(3).withIndex()) {
                File(tempDir, "$hash.blob").setLastModified(now - 60_000 + index * 10_000)
            }
            val inFlight = File(tempDir, "${"a1".repeat(32)}.123.partial")
            inFlight.writeBytes(ByteArray(1024))
            inFlight.setLastModified(now - 120_000)
            // The oldest is read again from disk, which makes it the newest.
            loader.purgeMemory()
            loader.load(server, hashes[0])
            assertEquals(3, fetches)

            loader.load(server, hashes[3])
            assertTrue(onDisk(hashes[0]) && onDisk(hashes[3]))
            assertTrue(!onDisk(hashes[1]) && !onDisk(hashes[2]))
            assertTrue("another write in flight stays", inFlight.isFile)
            assertEquals(2L * 1024, MediaReferenceLoader.trim(tempDir, Long.MAX_VALUE, 0, keeping = ""))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun retryStateClearsTransientFailureAndImageViewerRetriesFailedPage() = runTest {
        var retryState = MediaAttachmentRetryState(failure = MediaAttachmentFailure.Failed, attempt = 0)
        assertTrue(retryState.isTransientFailure)
        retryState = retryState.retry()
        assertNull(retryState.failure)
        assertFalse(retryState.isTransientFailure)
        assertEquals(1, retryState.attempt)

        val pngData = samplePNGData()
        val server = OpenCodeServer(url = "https://example.invalid", username = "user", password = "secret")
        val attachments = listOf(
            OpenCodeImageAttachment(id = "img-1", data = ByteArray(0), mediaHash = hashA, name = "diagram.png", mime = "image/png"),
            OpenCodeImageAttachment(id = "img-2", data = ByteArray(0), mediaHash = hashB, name = "missing.png", mime = "image/png"),
        )
        val decodedCache = DecodedImageCache()
        val shouldFailFirst = AtomicBoolean(true)
        val mediaReferences = MediaReferenceClient(
            cachedData = { null },
            unavailable = { _, _ -> false },
            loader = { _, hash ->
                when {
                    hash != hashA -> MediaReferenceLoadResult.Unavailable
                    shouldFailFirst.getAndSet(false) -> MediaReferenceLoadResult.Failed("Couldn’t load image")
                    else -> MediaReferenceLoadResult.Loaded(pngData)
                }
            },
        )
        val preview = withDependencies({
            this[DecodedImageCache] = decodedCache
            this[MediaReferenceClient] = mediaReferences
        }) {
            TranscriptImagePreview.fromAttachments(attachments, selectedID = "img-1", label = "Attached image", server = server)
        }
        assertNotNull(preview)

        val store = TestStore(FullscreenViewerFeature.State(preview!!), FullscreenViewerFeature(), this) {
            this[DecodedImageCache] = decodedCache
            this[MediaReferenceClient] = mediaReferences
        }

        store.send(FullscreenViewerFeature.Action.Task)
        store.receive({ it is FullscreenViewerFeature.Action.MediaLoaded }) {
            it.withPage(0) { page -> page.copy(loadFailed = true) }
        }

        val expectedImage = store.withDependencies { CachedImage.decoded("img-1", pngData, hashA) }
        assertNotNull(expectedImage)

        store.send(FullscreenViewerFeature.Action.RetryPageTapped("img-1")) {
            it.withPage(0) { page -> page.copy(loadFailed = false) }
        }
        store.receive({ it is FullscreenViewerFeature.Action.MediaLoaded }) {
            it.withPage(0) { page -> page.copy(image = expectedImage, loadFailed = false) }
        }
        assertTrue(store.state.preview.pages[0].image === expectedImage)

        store.send(FullscreenViewerFeature.Action.PageSelected("img-2")) {
            it.copy(selectedID = "img-2")
        }
        store.receive({ it is FullscreenViewerFeature.Action.MediaLoaded }) {
            it.withPage(1) { page -> page.copy(isUnavailable = true) }
        }

        // Saving an unavailable page is a no-op.
        store.send(FullscreenViewerFeature.Action.SaveTapped)
        store.finish()
    }

    @Test
    fun coldPagesLoadOnlyOnSelectionAndRemovalStartsTheReplacement() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val pixels = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val pages = (1..4).map { n ->
            val id = "link$n"
            TranscriptImagePreview.Page(id, image = if (n == 4) pixels else null, label = id,
                imageSource = SessionVisualImageSource.Link(id))
        }
        val state = FullscreenViewerFeature.State(TranscriptImagePreview("link1", pages), allowsRemove = true)
        val store = TestStore(state, FullscreenViewerFeature(), this) {
            this[ImageLinkPreviewClient] = ImageLinkPreviewClient(load = { id, _ -> calls.add(id); pixels })
        }
        store.send(FullscreenViewerFeature.Action.Task) { it.withPage(3) { p -> p.copy(image = null) } }
        store.receive(FullscreenViewerFeature.Action.SourceLoaded("link1", pixels)) { it.withPage(0) { p -> p.copy(image = pixels) } }
        store.send(FullscreenViewerFeature.Action.RemoveTapped) {
            it.copy(preview = it.preview.copy(pages = it.preview.pages.drop(1)), selectedID = "link2")
        }
        store.receive(FullscreenViewerFeature.Action.Delegate(FullscreenViewerFeature.Action.DelegateAction.Removed("link1")))
        store.receive(FullscreenViewerFeature.Action.SourceLoaded("link2", pixels)) { it.withPage(0) { p -> p.copy(image = pixels) } }
        store.send(FullscreenViewerFeature.Action.SourceLoaded("link3", pixels)) // stale completion
        store.finish()
        assertEquals(listOf("link1", "link2"), calls)
    }

    @Test
    fun selectionAndCloseCancelPendingLoadsAndIgnoreLateResults() = runTest {
        val started = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val pages = listOf("a", "b").map { TranscriptImagePreview.Page(it, null, it, imageSource = SessionVisualImageSource.Link(it)) }
        val store = TestStore(FullscreenViewerFeature.State(TranscriptImagePreview("a", pages)), FullscreenViewerFeature(), this) {
            this[ImageLinkPreviewClient] = ImageLinkPreviewClient(load = { id, _ ->
                started += id
                try { kotlinx.coroutines.awaitCancellation() } finally { cancelled += id }
            })
        }
        store.send(FullscreenViewerFeature.Action.Task)
        testScheduler.runCurrent()
        store.send(FullscreenViewerFeature.Action.PageSelected("b")) { it.copy(selectedID = "b") }
        testScheduler.runCurrent()
        store.send(FullscreenViewerFeature.Action.SourceLoaded("a", null))
        store.send(FullscreenViewerFeature.Action.CloseTapped) { it.copy(isDismissed = true) }
        store.send(FullscreenViewerFeature.Action.SourceLoaded("b", null))
        store.finish()
        assertEquals(listOf("a", "b"), started)
        assertEquals(started, cancelled)
    }

    @Test
    fun revisitedFileImagePagesComeBackDecodedFromABoundedCache() = runTest {
        val server = OpenCodeServer(url = "https://fixture.invalid", username = "", password = "")
        val pages = listOf("a", "b", "c", "d").map {
            TranscriptImagePreview.Page(AgentFileDestination(ai.opencode.server.model.AgentFileReference(path = "shots/$it.png"), "/fixture"))
        }
        val pixels = pages.map { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
        val bytes = pixels[0].allocationByteCount.toLong()
        val memory = coil3.memory.MemoryCache.Builder().maxSizeBytes(bytes * 3).weakReferencesEnabled(false).build()
        val cache = DecodedImageCache(memory)
        val (a, b, c, d) = pages.map { it.id }
        val store = TestStore(FullscreenViewerFeature.State(TranscriptImagePreview(a, pages, server)), FullscreenViewerFeature(), this) {
            this[DecodedImageCache] = cache
            this[AgentImageCache] = AgentImageCache(cache)
        }
        store.send(FullscreenViewerFeature.Action.Task)
        store.send(FullscreenViewerFeature.Action.FileImageLoaded(pages[0].file!!, pixels[0])) { it.withPage(0) { p -> p.copy(image = pixels[0]) } }
        store.send(FullscreenViewerFeature.Action.PageSelected(b)) { it.copy(selectedID = b) }
        store.send(FullscreenViewerFeature.Action.FileImageLoaded(pages[1].file!!, pixels[1])) { it.withPage(1) { p -> p.copy(image = pixels[1]) } }
        store.send(FullscreenViewerFeature.Action.PageSelected(c)) { it.copy(selectedID = c).withPage(0) { p -> p.copy(image = null) } }
        store.send(FullscreenViewerFeature.Action.FileImageLoaded(pages[2].file!!, pixels[2])) { it.withPage(2) { p -> p.copy(image = pixels[2]) } }
        // Back one step: `b` kept its pixels, and `a`, a neighbour again, is back from the cache before it's reached.
        store.send(FullscreenViewerFeature.Action.PageSelected(b)) { it.copy(selectedID = b).withPage(0) { p -> p.copy(image = pixels[0]) } }
        store.send(FullscreenViewerFeature.Action.PageSelected(a)) { it.copy(selectedID = a).withPage(2) { p -> p.copy(image = null) } }
        assertTrue(store.state.preview.pages[0].image === pixels[0])
        // Only the selection's neighbours hold pixels, and the cache drops its oldest entry (`b`) past its budget.
        store.send(FullscreenViewerFeature.Action.PageSelected(d)) {
            it.copy(selectedID = d).withPage(0) { p -> p.copy(image = null) }.withPage(1) { p -> p.copy(image = null) }.withPage(2) { p -> p.copy(image = pixels[2]) }
        }
        store.send(FullscreenViewerFeature.Action.FileImageLoaded(pages[3].file!!, pixels[3])) { it.withPage(3) { p -> p.copy(image = pixels[3]) } }
        store.send(FullscreenViewerFeature.Action.PageSelected(b)) {
            it.copy(selectedID = b).withPage(3) { p -> p.copy(image = null) }.withPage(0) { p -> p.copy(image = pixels[0]) }
        }
        assertNull(store.state.preview.pages[1].image)
        store.finish()
        assertEquals(bytes * 3, memory.size)
    }

    // The shared session image cache (AgentImageCache)

    @Test
    fun sharedImageFreshnessEndsOnlyOnInvalidationAndStaysPerServer() {
        val images = AgentImageCache(DecodedImageCache())
        val server = OpenCodeServer(url = "https://one.invalid", username = "", password = "")
        val other = OpenCodeServer(url = "https://two.invalid", username = "", password = "")
        val reference = ai.opencode.server.model.AgentFileReference(path = "shots/a.png")
        val key = AgentImageCache.key(server, reference, "/fixture")!!
        val otherKey = AgentImageCache.key(other, reference, "/fixture")!!
        // One identity per server and absolute path, the same one the preview cache's keys make.
        assertEquals(AgentImageCache.key(AgentImageKey(server.persistenceID, "/fixture/shots/a.png")), key)
        assertFalse(key == otherKey)
        assertFalse(images.isFresh(key))
        images.validated(key)
        images.validated(otherKey)
        assertTrue(images.isFresh(key))
        // The server's files may have changed: its files need a check again, another server's don't.
        images.invalidate(server.persistenceID)
        assertFalse(images.isFresh(key))
        assertTrue(images.isFresh(otherKey))
        images.validated(key)
        assertTrue(images.isFresh(key))
        // An upload is addressed by its content: always fresh.
        val upload = ai.opencode.server.model.AgentFileReference(path = "media:" + "c".repeat(64), media = ai.opencode.server.model.MediaFileOriginal("c".repeat(64), "u.png", "image/png", 3))
        assertTrue(images.isFresh(AgentImageCache.key(server, upload, null)!!))
    }

    @Test
    fun sharedImageDecodesCoalesceAndAChangedFileReplacesThePixels() = kotlinx.coroutines.runBlocking {
        val memory = coil3.memory.MemoryCache.Builder().maxSizeBytes(1024 * 1024).weakReferencesEnabled(false).build()
        val images = AgentImageCache(DecodedImageCache(memory))
        val file = File.createTempFile("shared", ".png").apply { deleteOnExit() }
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val first = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val decode: suspend (File) -> Bitmap? = { calls.incrementAndGet(); gate.await(); first }
        val readers = (1..4).map { kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) { images.decode("s\u0000/a.png", file, decode = decode) } }
        kotlinx.coroutines.delay(100)
        gate.complete(Unit)
        val results = readers.map { it.await() }
        // Four readers at once: one decode, one bitmap, kept in the shared decoded budget.
        assertEquals(1, calls.get())
        assertTrue(results.all { it === first })
        assertTrue(images.image("s\u0000/a.png") === first)
        assertTrue(images.decode("s\u0000/a.png", file) { error("resident: no decode") } === first)
        // A changed file (a 200, not a 304) decodes again and replaces what's resident.
        val second = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        assertTrue(images.decode("s\u0000/a.png", file, replacing = true) { second } === second)
        assertTrue(images.image("s\u0000/a.png") === second)
        assertEquals(second.allocationByteCount.toLong(), memory.size)
    }

    @Test
    fun sharedImageDiskCopiesKeepTheAppCacheWithinItsQuotaOldestFirst() {
        val root = Files.createTempDirectory("agent-files").toFile().apply { deleteOnExit() }
        val copies = (0 until 4).map { index ->
            AgentFileLoader.cacheURL("server", "/fixture/$index.png", "$index.png", root).also { file ->
                AgentFileLoader.store(file, ByteArray(100), "\"tag$index\"", null)
                file.setLastModified(1_000L * (index + 1))
            }
        }
        assertTrue(copies.all { it.isFile && File(it.path + ".validators").isFile })
        // Another cache's file and a download still being written, both older than every copy.
        val other = File(root, "other.bin").apply { writeBytes(ByteArray(100)); setLastModified(1) }
        val writing = File(copies[0].parentFile, "agent-file-WRITING").apply { writeBytes(ByteArray(50)); setLastModified(1) }
        val copy = 100 + File(copies[0].path + ".validators").length()
        // No quota known: nothing is trimmed.
        AgentImageCache(DecodedImageCache(), cacheQuota = { null }).trim(root)
        AgentImageCache(DecodedImageCache(), cacheQuota = { 0 }).trim(root)
        assertTrue(copies.all { it.isFile })
        // The quota covers the whole cache: the two oldest copies go, with their validators.
        AgentImageCache(DecodedImageCache(), cacheQuota = { other.length() + writing.length() + 2 * copy }).trim(root)
        assertEquals(listOf(false, false, true, true), copies.map { it.isFile })
        assertFalse(File(copies[0].path + ".validators").exists())
        assertTrue(other.isFile && writing.isFile)
    }

    @Test
    fun aFreshFileShowsItsResidentPixelsWithoutAnyRequest() {
        val server = OpenCodeServer(url = "https://fixture.invalid", username = "", password = "")
        val reference = ai.opencode.server.model.AgentFileReference(path = "shots/a.png")
        val images = AgentImageCache(DecodedImageCache())
        val seed = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        images.validated(AgentImageCache.key(server, reference, "/fixture")!!)
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        // The fixture host answers nothing: any probe or GET would fail the page.
        val model = AgentFileViewerModel(reference, server, "/fixture", context = context, seed = seed, images = images)
        model.open()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val ready = model.phase as AgentFileViewerModel.Phase.Ready
        assertTrue((ready.content as AgentFileViewerModel.Content.Image).image === seed)
        model.release()
    }

    @Test
    fun aFileThatNeedsACheckShowsItsDiskCopyAndKeepsItWhenTheServerCantBeReached() {
        // Offline after the file was downloaded: the check fails, the copy stays on screen.
        val (image, requests) = checkStaleCopy(ai.opencode.server.testing.WireFixture.Reply.Failure(ai.opencode.server.UrlErrorCode.NOT_CONNECTED_TO_INTERNET))
        assertEquals(6, image.width)
        assertTrue(requests.isNotEmpty())
    }

    @Test
    fun aStaleCopyIsCheckedByOneConditionalGetAndA304KeepsItsPixels() {
        val (image, requests) = checkStaleCopy(ai.opencode.server.testing.WireFixture.Reply.status(304))
        assertEquals(6, image.width)
        // No range probe first: one GET carrying the copy's validator.
        assertEquals(1, requests.size)
        assertEquals("\"v1\"", requests[0].header("If-None-Match"))
        assertNull(requests[0].header("Range"))
    }

    /** A downloaded copy that isn't fresh, opened against [reply]: what the page shows and what it sent. */
    private fun checkStaleCopy(reply: ai.opencode.server.testing.WireFixture.Reply): Pair<Bitmap, List<ai.opencode.server.testing.WireFixture.Request>> =
        ai.opencode.server.testing.WireFixture { reply }.use { fixture ->
            val server = fixture.server
            val reference = ai.opencode.server.model.AgentFileReference(path = "shots/a.png")
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val copy = AgentFileLoader.cacheURL(server, AgentFileLoader.original(server, reference, "/fixture")!!.cacheKey, reference.name, context.cacheDir)
            AgentFileLoader.store(copy, pngBytes(Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888)), "\"v1\"", null)
            // Never validated in this run (or invalidated since): not fresh.
            val model = AgentFileViewerModel(reference, server, "/fixture", context = context, images = AgentImageCache(DecodedImageCache(), fileDecoder = ::decodeFile))
            model.open()
            val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            var waited = 0
            while (fixture.requests.isEmpty() || model.phase !is AgentFileViewerModel.Phase.Ready && model.phase !is AgentFileViewerModel.Phase.Failed) {
                looper.idle(); Thread.sleep(20)
                if (++waited > 250) break
            }
            repeat(20) { looper.idle(); Thread.sleep(20) }
            val ready = model.phase as? AgentFileViewerModel.Phase.Ready
            assertNotNull("phase ${model.phase}", ready)
            val image = (ready!!.content as AgentFileViewerModel.Content.Image).image
            model.release()
            image to fixture.requests
        }

    @Test
    fun aDownloadedImageOrDocumentOpensFromItsCopy() {
        val offline = ai.opencode.server.testing.WireFixture.Reply.Failure(ai.opencode.server.UrlErrorCode.NOT_CONNECTED_TO_INTERNET)
        assertEquals("cached notes", text(openCopy("docs/notes.txt", offline).first))
        // Unchanged: one conditional GET, no range probe, and the copy stays.
        val (kept, requests) = openCopy("docs/notes.txt", ai.opencode.server.testing.WireFixture.Reply.status(304))
        assertEquals("cached notes", text(kept))
        assertEquals(1, requests.size)
        assertEquals("\"v1\"", requests[0].header("If-None-Match"))
        assertNull(requests[0].header("Range"))
        val changed = openCopy("docs/notes.txt", ai.opencode.server.testing.WireFixture.Reply.Json(200, "new notes", mapOf("ETag" to "\"v2\""))) { text(it) == "new notes" }.first
        assertEquals("new notes", text(changed))
        val gone = openCopy("docs/notes.txt", ai.opencode.server.testing.WireFixture.Reply.status(404)) { it is AgentFileViewerModel.Phase.Failed }.first
        assertEquals(AgentFileError.NotFound, (gone as AgentFileViewerModel.Phase.Failed).error)
    }

    @Test
    fun streamedMediaAndMissingCopiesOpenAsBefore() {
        val offline = ai.opencode.server.testing.WireFixture.Reply.Failure(ai.opencode.server.UrlErrorCode.NOT_CONNECTED_TO_INTERNET)
        val failed = { phase: AgentFileViewerModel.Phase -> phase is AgentFileViewerModel.Phase.Failed }
        assertTrue(failed(openCopy("recordings/clip.mp4", offline, settled = failed).first))
        assertTrue(failed(openCopy("docs/never.txt", offline, stored = false, settled = failed).first))
    }

    @Test
    fun aCopyLeftBeforeTheServerAnsweredIsCheckedAgainOnReturn() {
        // Following a link stops the page mid-check; coming back asks again rather than trusting the copy.
        val changed = openCopy("docs/notes.txt", ai.opencode.server.testing.WireFixture.Reply.Json(200, "new notes", mapOf("ETag" to "\"v2\"")), meanwhile = { it.stop(); it.open() }) { text(it) == "new notes" }.first
        assertEquals("new notes", text(changed))
    }

    private fun text(phase: AgentFileViewerModel.Phase): String? =
        ((phase as? AgentFileViewerModel.Phase.Ready)?.content as? AgentFileViewerModel.Content.Text)?.document?.text

    /**
     * Opens [path] with a downloaded copy (unless not [stored]) while the server's [reply] is held,
     * runs [meanwhile], then releases the reply and waits until the page is [settled].
     */
    private fun openCopy(
        path: String,
        reply: ai.opencode.server.testing.WireFixture.Reply,
        stored: Boolean = true,
        meanwhile: (AgentFileViewerModel) -> Unit = {},
        settled: (AgentFileViewerModel.Phase) -> Boolean = { true },
    ): Pair<AgentFileViewerModel.Phase, List<ai.opencode.server.testing.WireFixture.Request>> {
        val held = java.util.concurrent.CountDownLatch(1)
        return ai.opencode.server.testing.WireFixture { held.await(5, java.util.concurrent.TimeUnit.SECONDS); reply }.use { fixture ->
            val server = fixture.server
            val reference = ai.opencode.server.model.AgentFileReference(path = path)
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val copy = AgentFileLoader.cacheURL(server, AgentFileLoader.original(server, reference, "/fixture")!!.cacheKey, reference.name, context.cacheDir)
            if (stored) AgentFileLoader.store(copy, "cached notes".toByteArray(), "\"v1\"", null)
            val model = AgentFileViewerModel(reference, server, "/fixture", context = context)
            val looper = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            fun waitFor(condition: () -> Boolean) {
                var waited = 0
                while (!condition() && ++waited < 250) { looper.idle(); Thread.sleep(20) }
            }
            model.open()
            waitFor { fixture.requests.isNotEmpty() }
            // The copy is on screen before the server answers.
            if (stored && reference.kind == ai.opencode.server.model.AgentFileReference.Kind.Text) assertEquals("cached notes", text(model.phase))
            meanwhile(model)
            held.countDown()
            waitFor { settled(model.phase) }
            repeat(10) { looper.idle(); Thread.sleep(20) }
            val phase = model.phase
            model.release()
            copy.parentFile?.deleteRecursively()
            phase to fixture.requests
        }
    }

    @Test
    fun selectingAPageDecodesItsNeighboursFromTheirDiskCopiesAhead(): Unit = kotlinx.coroutines.runBlocking {
        ai.opencode.server.testing.WireFixture { ai.opencode.server.testing.WireFixture.Reply.Failure(ai.opencode.server.UrlErrorCode.NOT_CONNECTED_TO_INTERNET) }.use { fixture ->
            val server = fixture.server
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val pages = listOf("a", "b").map {
                TranscriptImagePreview.Page(AgentFileDestination(ai.opencode.server.model.AgentFileReference(path = "shots/n$it.png"), "/fixture"))
            }
            val neighbour = pages[1].file!!
            val copy = AgentFileLoader.cacheURL(server, AgentFileLoader.original(server, neighbour.reference, "/fixture")!!.cacheKey, neighbour.reference.name, context.cacheDir)
            AgentFileLoader.store(copy, pngBytes(Bitmap.createBitmap(5, 3, Bitmap.Config.ARGB_8888)), "\"v1\"", null)
            val store = TestStore(FullscreenViewerFeature.State(TranscriptImagePreview(pages[0].id, pages, server)), FullscreenViewerFeature(), this) {
                this[AgentImageCache] = AgentImageCache(DecodedImageCache(), fileDecoder = ::decodeFile)
                this[ai.opencode.core.dependencies.AppContext] = context
            }
            store.send(FullscreenViewerFeature.Action.Task)
            // The neighbour comes in drawn: its disk copy decoded before it's swiped to, without a request.
            var prepared: Bitmap? = null
            store.receive({ action ->
                (action as? FullscreenViewerFeature.Action.NeighbourPrepared)?.takeIf { it.pageID == pages[1].id && it.image.width == 5 }?.also { prepared = it.image } != null
            }, "neighbour prepared", timeout = 5.seconds) { state -> state.withPage(1) { p -> p.copy(image = prepared) } }
            assertNotNull(store.state.preview.pages[1].image)
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test
    fun aFileImageOpensOnItsSharedPreviewWhileItsFullResolutionPixelsDecode(): Unit = kotlinx.coroutines.runBlocking {
        ai.opencode.server.testing.WireFixture { ai.opencode.server.testing.WireFixture.Reply.Failure(ai.opencode.server.UrlErrorCode.NOT_CONNECTED_TO_INTERNET) }.use { fixture ->
            val server = fixture.server
            val context = org.robolectric.RuntimeEnvironment.getApplication()
            val page = TranscriptImagePreview.Page(AgentFileDestination(ai.opencode.server.model.AgentFileReference(path = "shots/tall.png"), "/fixture"))
            val other = TranscriptImagePreview.Page(AgentFileDestination(ai.opencode.server.model.AgentFileReference(path = "shots/other.png"), "/fixture"))
            val full = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
            val small = Bitmap.createBitmap(20, 40, Bitmap.Config.ARGB_8888)
            val previews = AgentImagePreviews(
                sizes = AgentImageSizeCache.inMemory,
                loader = { _, _, _ -> AgentImageResponse.File(pngBytes(full), AgentImageValidators(entityTag = "\"v1\"")) },
                decoder = { small },
                sleep = {},
                shouldDeferPrefetch = { false },
                pathUpdates = { kotlinx.coroutines.flow.emptyFlow() },
                dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            )
            // The transcript read the file as it is now: a preview smaller than the file.
            val key = AgentImageKey(server.persistenceID, "/fixture/shots/tall.png")
            previews.show(AgentImageSighting(key, "transcript"), AgentImageRequest(okhttp3.Request.Builder().url("http://lab/api/fs/read/tall.png").build()))
            assertTrue(ai.opencode.core.testing.eventually(wallClock = true) { previews.entry(key).phase == AgentImagePreviews.Phase.Loaded })
            val requestsBefore = fixture.requests.size
            val store = TestStore(FullscreenViewerFeature.State(TranscriptImagePreview(page.id, listOf(page, other), server)), FullscreenViewerFeature(), this) {
                this[AgentImagePreviews] = previews
                this[AgentImageCache] = AgentImageCache(DecodedImageCache(), fileDecoder = ::decodeFile)
                this[ai.opencode.core.dependencies.AppContext] = context
            }
            store.exhaustive = false
            store.send(FullscreenViewerFeature.Action.Task)
            // Selected: the preview stands in at once, rather than Opening.
            assertTrue(store.state.preview.pages[0].image === small)
            // A file the preview never read gets no one else's pixels.
            assertNull(store.state.preview.pages[1].image)
            // The full-resolution pixels replace it.
            store.send(FullscreenViewerFeature.Action.FileImageLoaded(page.file!!, full))
            assertTrue(store.state.preview.pages[0].image === full)
            assertEquals(requestsBefore, fixture.requests.size)
            previews.close()
        }
    }

    /** Robolectric decodes files with BitmapFactory only (its ImageDecoder is Android-only). */
    private fun decodeFile(file: File): Bitmap? = android.graphics.BitmapFactory.decodeFile(file.path)

    private fun pngBytes(image: Bitmap): ByteArray = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test
    fun failedOriginalKeepsThumbnailAndDoesNotAutomaticallyRetry() = runTest {
        val thumbnail = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val page = TranscriptImagePreview.Page("photo", thumbnail, "Photo", original = byteArrayOf(0))
        val state = FullscreenViewerFeature.State(TranscriptImagePreview(page.id, listOf(page)))
        val store = TestStore(state, FullscreenViewerFeature(), this)
        store.send(FullscreenViewerFeature.Action.Task)
        store.receive(FullscreenViewerFeature.Action.SourceLoaded(page.id, null)) {
            it.withPage(0) { p -> p.copy(loadFailed = true, originalFailed = true) }
        }
        assertTrue(store.state.selectedPage?.image === thumbnail)
        store.send(FullscreenViewerFeature.Action.Task)
        store.finish()
    }

    private fun png(width: Int, height: Int): ByteArray = pngBytes(Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })

    @Test
    fun aFiveKThumbnailKeepsItsPointSizeDecodesOnceAndFitsTheCache() {
        val data = png(5120, 2880)
        withDependencies({ this[DecodedImageCache] = DecodedImageCache() }) {
            val first = requireNotNull(CachedImage.thumbnail("shot", data))
            assertEquals(1600 to 900, first.width to first.height)
            assertTrue(first.byteCount <= 6 * 1024 * 1024)
            // A second row showing it is a cache hit, not a second decode.
            assertTrue(CachedImage.thumbnail("shot", data) === first)
            assertTrue(CachedImage.cachedThumbnail("shot") === first)
            assertNull(CachedImage.cached("shot"))
            // A full-size original the viewer decoded never stands in for a tile's thumbnail.
            CachedImage.decoded("viewer", data)
            assertNull(CachedImage.cachedThumbnail("viewer"))
        }
    }

    @Test
    fun aTallThumbnailKeepsItsShortEdgeForASquareCrop() {
        withDependencies({ this[DecodedImageCache] = DecodedImageCache() }) {
            val tall = requireNotNull(CachedImage.thumbnail("tall", png(1000, 6000)))
            assertEquals(800 to 4800, tall.width to tall.height)
            // Small images keep every pixel, as their original.
            val small = requireNotNull(CachedImage.thumbnail("small", png(400, 300)))
            assertEquals(400 to 300, small.width to small.height)
            assertTrue(CachedImage.cached("small") === small)
        }
    }

    @Test
    fun theViewerOpensOnTheThumbnailAndTakesTheOriginalOffTheMainActor() = runTest {
        val data = png(3200, 1800)
        val cache = DecodedImageCache()
        val attachment = OpenCodeImageAttachment(id = "img", data = data, mime = "image/png")
        val preview = requireNotNull(withDependencies({ this[DecodedImageCache] = cache }) {
            // The row shows the thumbnail first, as a tap would find it.
            MediaReferenceResolver.thumbnail(attachment)
            TranscriptImagePreview.fromAttachments(listOf(attachment), selectedID = "img", label = "Attached image")
        })
        val shown = requireNotNull(preview.pages[0].image)
        assertEquals(1600 to 900, shown.width to shown.height)
        assertEquals(AgentImageSize(3200.0, 1800.0), preview.pages[0].placeholderSize)
        assertTrue(preview.pages[0].original === data)
        assertTrue(preview.pages[0].originalPending)

        val store = TestStore(FullscreenViewerFeature.State(preview), FullscreenViewerFeature(), this) {
            this[DecodedImageCache] = cache
        }
        store.send(FullscreenViewerFeature.Action.Task)
        var original: Bitmap? = null
        store.receive({ action -> (action as? FullscreenViewerFeature.Action.SourceLoaded)?.image?.also { original = it } != null }) {
            it.withPage(0) { page -> page.copy(image = original, hasDecodedOriginal = true, placeholderSize = null) }
        }
        val decoded = requireNotNull(original)
        assertEquals(3200 to 1800, decoded.width to decoded.height)
        store.finish()
    }

    @Test
    fun saveAndShareExportTheOriginalNeverTheThumbnail() = runTest {
        val thumbnail = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        val pending = TranscriptImagePreview.Page("photo", thumbnail, "Photo", original = png(40, 20))
        val broken = TranscriptImagePreview.Page("broken", thumbnail, "Broken", original = byteArrayOf(0), originalFailed = true)
        val saved = Collections.synchronizedList(mutableListOf<Bitmap>())
        val shared = Collections.synchronizedList(mutableListOf<Bitmap>())
        val store = TestStore(FullscreenViewerFeature.State(TranscriptImagePreview(pending.id, listOf(pending, broken))), FullscreenViewerFeature(), this) {
            this[PhotoSaveClient] = PhotoSaveClient(saveImage = { saved += it })
            this[ImageShareClient] = ImageShareClient(share = { image, _ -> shared += image })
        }
        store.send(FullscreenViewerFeature.Action.SaveTapped) { it.copy(isSaving = true) }
        store.receive(FullscreenViewerFeature.Action.SaveFinished(true)) { it.copy(isSaving = false) }
        store.send(FullscreenViewerFeature.Action.ShareTapped())
        assertTrue(ai.opencode.core.testing.eventually(wallClock = true) { shared.size == 1 })
        assertEquals(listOf(40 to 20, 40 to 20), (saved + shared).map { it.width to it.height })

        store.send(FullscreenViewerFeature.Action.PageSelected("broken")) { it.copy(selectedID = "broken") }
        store.send(FullscreenViewerFeature.Action.SaveTapped) { it.copy(isSaving = true) }
        store.receive(FullscreenViewerFeature.Action.SaveFinished(false)) { it.copy(isSaving = false) }
        store.send(FullscreenViewerFeature.Action.ShareTapped())
        store.finish()
        assertEquals(2, saved.size + shared.size)
    }

    private fun FullscreenViewerFeature.State.withPage(
        index: Int,
        update: (TranscriptImagePreview.Page) -> TranscriptImagePreview.Page,
    ): FullscreenViewerFeature.State =
        copy(preview = preview.copy(pages = preview.pages.toMutableList().also { it[index] = update(it[index]) }))
}
