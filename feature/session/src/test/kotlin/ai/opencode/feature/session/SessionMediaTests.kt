package ai.opencode.feature.session

import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.media.CachedImage
import ai.opencode.feature.media.DecodedImageCache
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionMedia.Content
import ai.opencode.feature.session.SessionMedia.Filter
import ai.opencode.feature.session.SessionMedia.Origin
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/** Under Robolectric: bare URLs are found with `Patterns.WEB_URL`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionMediaTests {
    @get:Rule val isolation = TestIsolationRule()
    private val server = OpenCodeServer("https://fixture.invalid", "", "")
    private val dir = "/project"
    private val hash = "c".repeat(64)

    private fun tool(id: String, path: String, images: List<OpenCodeImageAttachment> = emptyList(), videos: List<OpenCodeTranscriptFile> = emptyList(), status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed) =
        OpenCodeTranscriptItem.Tool(id, OpenCodeToolCall("call-$id", name = "read", status = status,
            input = OpenCodeJSONValue.Object(mapOf("path" to OpenCodeJSONValue.Str(path))), output = emptyList(), metadata = emptyMap(),
            images = images, videos = videos))

    private fun session(revert: String? = null) = OpenCodeSession(
        id = "ses_media", serverTitle = "Media", projectID = "prj", directory = dir,
        updatedAt = Instant.ofEpochSecond(100), revertMessageID = revert,
    )

    private fun ids(items: List<SessionMedia.Item>) = items.map { it.id }

    @Test fun collectsAttachmentsFilesAndLinksNewestFirstOnceEach() {
        val pasted = "line\n".repeat(40)
        val items = listOf(
            OpenCodeTranscriptItem.UserText("msg_1", "Compare https://example.com/spec and http://localhost:3000/dev"),
            OpenCodeTranscriptItem.UserImages("msg_1-images", listOf(
                OpenCodeImageAttachment("att", byteArrayOf(1, 2), name = "photo.png", mime = "image/png"),
                OpenCodeImageAttachment("gone", isUnavailable = true),
            )),
            OpenCodeTranscriptItem.UserFiles("msg_1-files", listOf(
                OpenCodeTranscriptFile("report", "report.pdf", "application/pdf", "/project/docs/report.pdf"),
                OpenCodeTranscriptFile("paste", "Pasted Text", "text/plain", text = pasted),
                OpenCodeTranscriptFile("lost", "lost.mov", "video/quicktime", mediaHash = hash, isUnavailable = true),
            )),
            tool("tool_1", "media/amber.png", images = listOf(OpenCodeImageAttachment("read-pixels", byteArrayOf(3)))),
            OpenCodeTranscriptItem.AssistantMarkdown(
                "msg_2-text",
                "Here it is:\n\n`docs/report.pdf`\n\n`media/orbit.mp4`\n\nSee [the spec](https://example.com/spec?utm_source=x) and [the **OpenCode** docs](https://opencode.ai/docs). " +
                    "Not https://example.com/reset?token=abc.",
            ),
        )
        val media = SessionMedia.collect(items, dir)
        assertEquals(
            listOf(
                // The reply, newest: what it shared first (report.pdf and the spec were the user's).
                "file:/project/media/orbit.mp4", "link:https://opencode.ai/docs",
                // The image read: one card for the file, not a second for its pixels.
                "file:/project/media/amber.png",
                "file:/project/docs/report.pdf", "pasted:paste",
                "image:att",
                "link:https://example.com/spec",
            ),
            ids(media),
        )
        val origins = media.associate { it.id to it.origin }
        assertEquals(Origin.agent, origins["file:/project/media/orbit.mp4"])
        assertEquals(Origin.agent, origins["link:https://opencode.ai/docs"])
        assertEquals(Origin.user, origins["file:/project/docs/report.pdf"])
        assertEquals(Origin.user, origins["link:https://example.com/spec"])
        assertEquals(Content.Link("https://opencode.ai/docs", "the OpenCode docs"), media[1].content)
        assertEquals(Content.Link("https://example.com/spec", "the spec"), media.last().content)
        assertEquals("the OpenCode docs", SessionMedia.title(media[1].content))
        assertEquals("the spec", SessionMedia.title(media.last().content))
        assertEquals("Spec", SessionMedia.title(Content.Link("https://example.com/spec", null)))
        assertEquals("example.com", SessionMedia.title(Content.Link("https://example.com/", null)))
        assertEquals("report.pdf", SessionMedia.title(media[3].content))
        assertTrue(media[4].content is Content.PastedText)
        assertEquals("read-pixels", (media[2].content as Content.File).thumbnail?.id)
    }

    @Test fun privateLinksAndRunningToolsStayOut() {
        val items = listOf(
            OpenCodeTranscriptItem.UserText("msg_1", "Preview at http://localhost:5173 and https://box.tail1234.ts.net/app, token https://example.com/a?token=secret"),
            tool("tool_running", "/project/docs/spec.pdf", status = OpenCodeToolCall.Status.Running),
            tool("tool_failed", "/project/docs/notes.pdf", status = OpenCodeToolCall.Status.Error),
            OpenCodeTranscriptItem.AssistantMarkdown("msg_2-text", "![code](src/App.kt)"),
        )
        assertEquals(emptyList<SessionMedia.Item>(), SessionMedia.collect(items, dir))
        // Once done, a document or audio a tool read earns a card; a code file it read doesn't.
        val done = listOf(tool("tool_code", "src/App.kt"), tool("tool_spec", "/project/docs/spec.pdf"), tool("tool_note", "/project/notes/memo.m4a"))
        val media = SessionMedia.collect(done, dir)
        assertEquals(listOf("file:/project/notes/memo.m4a"), ids(SessionMedia.filtered(media, Filter.media)))
        assertEquals(listOf("file:/project/docs/spec.pdf"), ids(SessionMedia.filtered(media, Filter.files)))
    }

    @Test fun aToolsPageImagesStayBesideItsDocument() {
        val pages = listOf(OpenCodeImageAttachment("page-1", byteArrayOf(1)), OpenCodeImageAttachment("page-2", byteArrayOf(2)))
        val media = SessionMedia.collect(listOf(tool("tool_report", "/project/docs/report.pdf", images = pages)), dir)
        assertEquals(listOf("image:page-1", "image:page-2"), ids(SessionMedia.filtered(media, Filter.media)))
        assertEquals(listOf("file:/project/docs/report.pdf"), ids(SessionMedia.filtered(media, Filter.files)))
        assertNull((media.last().content as Content.File).thumbnail)
    }

    @Test fun filtersSplitMediaFilesAndLinksWithNoMixedView() {
        val items = listOf(
            OpenCodeTranscriptItem.UserText("msg_1", "https://example.com/a"),
            OpenCodeTranscriptItem.UserImages("msg_1-images", listOf(OpenCodeImageAttachment("att", mediaHash = hash))),
            OpenCodeTranscriptItem.UserFiles("msg_1-files", listOf(OpenCodeTranscriptFile("paste", "Pasted Text", "text/plain", text = "a\nb"))),
            OpenCodeTranscriptItem.AssistantMarkdown("msg_2-text", "`notes/plan.pdf`, `media/memo.m4a`, `media/clip.mp4`, `media/shot.png` and https://example.org/b"),
        )
        val all = SessionMedia.collect(items, dir)
        assertEquals(listOf(Filter.media, Filter.files, Filter.links), Filter.entries)
        assertEquals(
            listOf("file:/project/media/memo.m4a", "file:/project/media/clip.mp4", "file:/project/media/shot.png", "media:$hash"),
            ids(SessionMedia.filtered(all, Filter.media)),
        )
        assertEquals(listOf("file:/project/notes/plan.pdf", "pasted:paste"), ids(SessionMedia.filtered(all, Filter.files)))
        assertEquals(listOf("link:https://example.org/b", "link:https://example.com/a"), ids(SessionMedia.filtered(all, Filter.links)))
        // Every item is in exactly one list.
        assertEquals(all.size, Filter.entries.sumOf { SessionMedia.filtered(all, it).size })
        assertEquals(emptyList<SessionMedia.Item>(), SessionMedia.filtered(SessionMedia.collect(emptyList(), dir), Filter.links))
    }

    @Test fun mediaOpensOnItsCardInAGalleryOfOnlyTheSessionsMedia() {
        val items = listOf(
            OpenCodeTranscriptItem.UserImages("msg_1-images", listOf(OpenCodeImageAttachment("att", byteArrayOf(1, 2), name = "hero.png"))),
            OpenCodeTranscriptItem.UserFiles("msg_1-files", listOf(OpenCodeTranscriptFile("brief", "brief.pdf", "application/pdf", "/project/docs/brief.pdf"))),
            OpenCodeTranscriptItem.AssistantMarkdown("msg_2-text", "`media/clip.mp4`\n\n`media/memo.m4a`\n\nhttps://example.org/b"),
        )
        val all = SessionMedia.collect(items, dir)
        val memo = all.first { it.id == "file:/project/media/memo.m4a" }
        val gallery = SessionMedia.gallery(all, memo, server, dir)!!
        // The session's order, oldest first; no PDF, paste or link.
        assertEquals(listOf("att", "file:media/clip.mp4", "file:media/memo.m4a"), gallery.pages.map { it.id })
        assertEquals(SessionMedia.zoomSourceID(memo), gallery.id)
        val audio = gallery.pages.last()
        assertEquals(AgentFileReference.Kind.Audio, audio.file?.reference?.kind)
        assertEquals(dir, audio.file?.directory)
        assertTrue(gallery.pages[1].isVideo)
        assertEquals("hero.png", gallery.pages[0].label)
        assertTrue(gallery.pages[0].original!!.contentEquals(byteArrayOf(1, 2)))
        assertNull(SessionMedia.gallery(all, all.first { it.id == "file:/project/docs/brief.pdf" }, server, dir))
        assertNull(SessionMedia.gallery(all, all.first { it.isLink }, server, dir))
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun aMediaCardOpensOnTheThumbnailItsTileShows() {
        val stream = java.io.ByteArrayOutputStream()
        android.graphics.Bitmap.createBitmap(3200, 1800, android.graphics.Bitmap.Config.ARGB_8888)
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
        val data = stream.toByteArray()
        withDependencies({ this[DecodedImageCache] = DecodedImageCache() }) {
            val shown = CachedImage.thumbnail("att", data)
            val all = SessionMedia.collect(listOf(OpenCodeTranscriptItem.UserImages("msg_1-images", listOf(OpenCodeImageAttachment("att", data)))), dir)
            val page = SessionMedia.gallery(all, all.single(), server, dir)!!.pages.single()
            assertTrue(page.image === shown)
            assertTrue(page.original === data)
            assertEquals(ai.opencode.feature.media.AgentImageSize(3200.0, 1800.0), page.placeholderSize)
        }
    }

    @Test fun replyFileCardsShowOnlyOnceTheirCheckFindsThem() {
        val items = listOf(OpenCodeTranscriptItem.AssistantMarkdown("msg_1-text", "`docs/missing.pdf`\n\n`docs/found.pdf`\n\n`moved.pdf`"))
        assertEquals(
            listOf("docs/missing.pdf", "docs/found.pdf", "moved.pdf"),
            SessionMedia.replyFiles(items).map { it.path },
        )
        val media = SessionMedia.collect(items, dir) { ref ->
            when (ref.path) {
                "docs/missing.pdf" -> null
                "moved.pdf" -> ref.copy(path = "archive/moved.pdf")
                else -> ref
            }
        }
        assertEquals(listOf("file:/project/docs/found.pdf", "file:/project/archive/moved.pdf"), ids(media))
        assertEquals(AgentFileReference("archive/moved.pdf"), (media[1].content as Content.File).reference)
    }

    @Test fun uploadedFilesKeyByTheirBlobAndOpenFromTheServersCopy() {
        val movie = OpenCodeTranscriptFile("clip", "clip.mov", "video/quicktime", mediaHash = hash)
        val items = listOf(
            OpenCodeTranscriptItem.UserFiles("msg_1-files", listOf(movie)),
            tool("tool_1", "notes.md", videos = listOf(movie)),
        )
        val media = SessionMedia.collect(items, dir)
        assertEquals(listOf("media:$hash"), ids(media))
        val file = media.single().content as Content.File
        assertEquals(Origin.user, media.single().origin)
        assertEquals("clip.mov", file.name)
        assertEquals(hash, file.reference.media?.hash)
        assertEquals(file.reference.zoomSourceID, SessionMedia.zoomSourceID(media.single()))
    }

    @Test fun undoneTurnsAndUnconfirmedPromptsStayOut() = sessionTest { h ->
        val items = listOf(
            OpenCodeTranscriptItem.UserText("msg_1", "Kept https://example.com/kept"),
            OpenCodeTranscriptItem.UserImages("msg_1-images", listOf(OpenCodeImageAttachment("kept", byteArrayOf(1)))),
            OpenCodeTranscriptItem.UserText("msg_2", "Undone https://example.com/undone"),
            OpenCodeTranscriptItem.UserFiles("msg_2-files", listOf(OpenCodeTranscriptFile("undone", "secret.pdf", "application/pdf", "/project/secret.pdf"))),
            OpenCodeTranscriptItem.AssistantMarkdown("msg_3-text", "`undone/reply.pdf`"),
        )
        val reverted = h.state { State.session(server, session(revert = "msg_2"), cachedItems = items) }
        assertEquals(listOf("image:kept", "link:https://example.com/kept"), ids(SessionMedia.collect(reverted.sessionMediaItems, dir)))

        val pending = h.state {
            State.session(server, session(), cachedItems = items.take(2) + OpenCodeTranscriptItem.UserText("msg_local", "https://example.com/pending"))
                .copy(optimisticPromptIDs = setOf("msg_local"))
        }
        assertEquals(listOf("image:kept", "link:https://example.com/kept"), ids(SessionMedia.collect(pending.sessionMediaItems, dir)))
    }

    @Test fun pageOpensOnMediaFiltersAndCloses() = sessionTest { h ->
        val store = h.store(h.state { State.session(server, session()) })
        store.send(Action.SessionMediaFilterPicked(Filter.links))
        store.send(Action.SessionMediaTapped) { it.copy(sessionMediaFilter = Filter.media) }
        store.send(Action.SessionMediaFilterPicked(Filter.links)) { it.copy(sessionMediaFilter = Filter.links) }
        store.send(Action.SessionMediaFilterPicked(Filter.links))
        store.send(Action.SessionMediaFilterPicked(Filter.files)) { it.copy(sessionMediaFilter = Filter.files) }
        store.send(Action.SessionMediaDismissed) { it.copy(sessionMediaFilter = null) }
        store.send(Action.SessionMediaTapped) { it.copy(sessionMediaFilter = Filter.media) }
        store.finish()
    }

    /** An OpenCode ID minted at [at] (`<prefix>_` + 12 hex of `ms × 4096` + 14 characters). */
    private fun minted(prefix: String, at: Instant, counter: Int = 0) =
        prefix + "_" + "%012x".format(((at.toEpochMilli() * 0x1000) + counter) and 0xFFFF_FFFF_FFFFL) + "Abcdefghijklmn"

    @Test fun mediaRunsByMonthNewestFirstWithItsLabels() {
        val zone = java.time.ZoneId.of("UTC")
        val now = Instant.parse("2026-11-17T12:00:00Z")
        val september = minted("msg", Instant.parse("2025-09-03T10:00:00Z"))
        val october = minted("msg", Instant.parse("2026-10-20T10:00:00Z"))
        val november = minted("msg", Instant.parse("2026-11-02T10:00:00Z"))
        val items = listOf(
            OpenCodeTranscriptItem.UserImages("$september-images", listOf(OpenCodeImageAttachment("old", byteArrayOf(1)))),
            OpenCodeTranscriptItem.UserImages("$october-images", listOf(OpenCodeImageAttachment("a", byteArrayOf(1)), OpenCodeImageAttachment("b", byteArrayOf(2)))),
            // The app's own prompt ID carries no time: it takes the October row's.
            OpenCodeTranscriptItem.UserImages("msg_local-images", listOf(OpenCodeImageAttachment("local", byteArrayOf(3)))),
            OpenCodeTranscriptItem.AssistantMarkdown("$november-text", "`media/clip.mp4`"),
        )
        val media = SessionMedia.filtered(SessionMedia.collect(items, dir, now), Filter.media)
        assertEquals(Instant.parse("2026-10-20T10:00:00Z"), media.first { it.id == "image:local" }.date)
        val months = SessionMedia.months(media, now, zone, java.util.Locale.US)
        assertEquals(listOf("November", "October", "September 2025"), months.map { it.label })
        assertEquals(listOf(listOf("file:/project/media/clip.mp4"), listOf("image:local", "image:a", "image:b"), listOf("image:old")), months.map { ids(it.items) })
        // No times at all: one run, no heading.
        val untimed = SessionMedia.months(SessionMedia.collect(listOf(items[2]), dir, now), now, zone, java.util.Locale.US)
        assertEquals(listOf(SessionMedia.Month(null, untimed.single().items)), untimed)
        assertEquals("novembre", SessionMedia.monthLabel(java.time.YearMonth.of(2026, 11), now, zone, java.util.Locale.FRENCH).lowercase())
    }

    @Test fun tileLengthsAndWhichFilesATileMayRead() {
        assertEquals("0:42", SessionMedia.durationText(42_000))
        assertEquals("12:05", SessionMedia.durationText(725_400))
        assertEquals("1:02:09", SessionMedia.durationText(3_729_000))
        assertTrue(ai.opencode.feature.media.MediaPosterClient.reads(AgentFileReference("media/clip.mp4"), dir))
        assertTrue(ai.opencode.feature.media.MediaPosterClient.reads(AgentFileReference("/project/media/memo.m4a"), dir))
        // Outside the session's folder a file is read only once tapped; images and documents have no poster.
        assertFalse(ai.opencode.feature.media.MediaPosterClient.reads(AgentFileReference("/elsewhere/clip.mp4"), dir))
        assertFalse(ai.opencode.feature.media.MediaPosterClient.reads(AgentFileReference("media/shot.png"), dir))
        assertFalse(ai.opencode.feature.media.MediaPosterClient.reads(AgentFileReference("docs/brief.pdf"), dir))
        val upload = OpenCodeTranscriptFile("clip", "clip.mov", "video/quicktime", mediaHash = hash).mediaViewerReference!!
        assertTrue(ai.opencode.feature.media.MediaPosterClient.reads(upload, null))
    }

    @Test fun anImageFileTileShowsTheFileTheViewerOpensNotAnOlderReadOfIt() {
        // The agent read shots/exit.png, wrote it again, and read it again: one tile, at its first read.
        val items = listOf(
            tool("tool_1", "shots/exit.png", images = listOf(OpenCodeImageAttachment("read-1", byteArrayOf(1)))),
            tool("tool_2", "shots/other.png", images = listOf(OpenCodeImageAttachment("read-other", byteArrayOf(2)))),
            tool("tool_3", "shots/exit.png", images = listOf(OpenCodeImageAttachment("read-2", byteArrayOf(3)))),
        )
        val media = SessionMedia.collect(items, dir)
        val exit = media.single { it.id == "file:/project/shots/exit.png" }
        assertEquals("read-1", (exit.content as Content.File).thumbnail?.id)
        // The viewer opens the file as it is now.
        val page = SessionMedia.gallery(media, exit, server, dir)!!.pages.single { it.id == SessionMedia.zoomSourceID(exit) }
        assertNull(page.original)
        assertNull(page.image)
        assertEquals("shots/exit.png", page.file?.reference?.path)
        // The tile reads the same file.
        val key = SessionMedia.imageFileKey(exit.content, server, dir)
        assertEquals(ai.opencode.feature.media.AgentImageKey(server.persistenceID, "/project/shots/exit.png"), key)
        // A tile on screen reads on demand, Data Saver or not.
        assertTrue(SessionMedia.tileImageRequest(okhttp3.Request.Builder().url("http://lab/api/fs/read/exit.png").build()).allowsConstrainedNetworkAccess)
        // Where the file's pixels are shown, a read's never stand in for them.
        assertNull(SessionMedia.tileAttachment(exit.content, showsFile = true))
        assertEquals("read-1", SessionMedia.tileAttachment(exit.content, showsFile = false)?.id)
        // Images held in the transcript have no file to read.
        val held = SessionMedia.collect(listOf(picture("att")), dir).single().content
        assertNull(SessionMedia.imageFileKey(held, server, dir))
        assertEquals("att", SessionMedia.tileAttachment(held, showsFile = true)?.id)
    }

    @Test fun mediaLayoutDefaultsToTilesAndIsKeptAcrossTabsAndReopening() = sessionTest { h ->
        val store = h.store(h.state { State.session(server, session()) })
        assertEquals(SessionMedia.Layout.tiles, store.state.sessionMediaLayout)
        // Closed, or on Files or Links, there's no switch to answer.
        store.send(Action.SessionMediaLayoutPicked(SessionMedia.Layout.list))
        store.send(Action.SessionMediaTapped) { it.copy(sessionMediaFilter = Filter.media) }
        store.send(Action.SessionMediaLayoutPicked(SessionMedia.Layout.list)) { it.copy(sessionMediaLayout = SessionMedia.Layout.list) }
        store.send(Action.SessionMediaLayoutPicked(SessionMedia.Layout.list))
        store.send(Action.SessionMediaFilterPicked(Filter.files)) { it.copy(sessionMediaFilter = Filter.files) }
        store.send(Action.SessionMediaLayoutPicked(SessionMedia.Layout.tiles))
        store.send(Action.SessionMediaFilterPicked(Filter.media)) { it.copy(sessionMediaFilter = Filter.media) }
        store.send(Action.SessionMediaDismissed) { it.copy(sessionMediaFilter = null) }
        store.send(Action.SessionMediaTapped) { it.copy(sessionMediaFilter = Filter.media) }
        assertEquals(SessionMedia.Layout.list, store.state.sessionMediaLayout)
        store.send(Action.SessionMediaLayoutPicked(SessionMedia.Layout.tiles)) { it.copy(sessionMediaLayout = SessionMedia.Layout.tiles) }
        store.finish()
    }

    @Test fun bothLayoutsShowTheSameMediaInTheSameOrder() {
        val now = Instant.parse("2026-11-17T12:00:00Z")
        val items = listOf(
            OpenCodeTranscriptItem.UserImages("${minted("msg", Instant.parse("2026-10-02T10:00:00Z"))}-images", listOf(OpenCodeImageAttachment("a", byteArrayOf(1)))),
            OpenCodeTranscriptItem.AssistantMarkdown("${minted("msg", Instant.parse("2026-11-03T10:00:00Z"))}-text", "`media/clip.mp4`\n\n`media/memo.m4a`\n\n`docs/brief.pdf`"),
        )
        val media = SessionMedia.filtered(SessionMedia.collect(items, dir, now), Filter.media)
        val months = SessionMedia.months(media, now, java.time.ZoneId.of("UTC"), java.util.Locale.US)
        assertEquals(ids(media), months.flatMap { ids(it.items) })
        assertEquals(listOf("file:/project/media/clip.mp4", "file:/project/media/memo.m4a", "image:a"), ids(media))
    }

    // Load Earlier Messages

    private fun texts(prefix: String, count: Int) = (0 until count).map { OpenCodeTranscriptItem.UserText("$prefix-$it", "Message $it") }
    private fun picture(id: String) = OpenCodeTranscriptItem.UserImages("$id-images", listOf(OpenCodeImageAttachment(id, byteArrayOf(1))))

    /** 120 loaded rows: more than the transcript's window, so a window-only tap would read nothing. */
    private fun SessionTestSupport.Harness.loadedStore(
        page: suspend (cursor: String?) -> ai.opencode.server.model.OpenCodeTranscriptPage,
        requests: SessionTestSupport.LockIsolated<Int>,
    ) = store(makeState(session = session(), cachedItems = texts("loaded", 120))) {
        this[ai.opencode.server.OpenCodeClient] = this[ai.opencode.server.OpenCodeClient].copy(
            historyPage = { _, _, cursor -> requests.increment(); page(cursor) },
        )
    }.also { it.exhaustive = false }

    @Test fun loadEarlierAlwaysReadsAnOlderPageAndTakesOneTapAtATime() = sessionTest { h ->
        val requests = SessionTestSupport.LockIsolated(0)
        val gate = SessionFeatureTestsDictation.Gate()
        val store = h.loadedStore({ cursor ->
            gate.await()
            ai.opencode.server.model.OpenCodeTranscriptPage(listOf(picture("older")) + texts("loaded", 120), next = "c1".takeIf { cursor == null })
        }, requests)
        assertTrue(store.state.visibleLimit < store.state.items.size)
        store.send(Action.SessionMediaTapped)
        store.send(Action.SessionMediaLoadEarlierTapped)
        // Loading from the first tap, before the page answers; more taps ask for nothing more.
        assertTrue(store.state.isLoadingEarlier)
        assertEquals(SessionMedia.EarlierSearch(Filter.media, 0, SessionMedia.EARLIER_PAGES_PER_TAP - 1), store.state.sessionMediaEarlier)
        store.send(Action.SessionMediaLoadEarlierTapped)
        store.send(Action.SessionMediaLoadEarlierTapped)
        SessionTestSupport.settle()
        assertEquals(1, requests.value)
        gate.open()
        store.receive(Action.EarlierHistoryResponse::class)
        // The page brought a picture: done after one page.
        assertFalse(store.state.isLoadingEarlier)
        assertNull(store.state.sessionMediaEarlier)
        assertFalse(store.state.sessionMediaNothingNew)
        assertEquals(1, SessionMedia.count(store.state, Filter.media))
        store.finish()
        assertEquals(1, requests.value)
    }

    @Test fun sparsePagesAreReadAtMostThreePerTapThenSaySo() = sessionTest { h ->
        val requests = SessionTestSupport.LockIsolated(0)
        val store = h.loadedStore({ cursor ->
            val n = requests.value
            // Every older page is plain text, and there is always more.
            val rows = if (cursor == null) texts("page$n", 80) + texts("loaded", 120) else texts("page$n", 80)
            ai.opencode.server.model.OpenCodeTranscriptPage(rows, next = "c$n")
        }, requests)
        store.send(Action.SessionMediaTapped)
        store.send(Action.SessionMediaLoadEarlierTapped)
        repeat(SessionMedia.EARLIER_PAGES_PER_TAP) { store.receive(Action.EarlierHistoryResponse::class) }
        SessionTestSupport.settle()
        assertEquals(3, requests.value)
        assertFalse(store.state.isLoadingEarlier)
        assertNull(store.state.sessionMediaEarlier)
        assertTrue(store.state.sessionMediaNothingNew)
        // The next tap reads the next three, never more.
        store.send(Action.SessionMediaLoadEarlierTapped)
        assertFalse(store.state.sessionMediaNothingNew)
        repeat(SessionMedia.EARLIER_PAGES_PER_TAP) { store.receive(Action.EarlierHistoryResponse::class) }
        SessionTestSupport.settle()
        assertEquals(6, requests.value)
        assertTrue(store.state.sessionMediaNothingNew)
        // Another tab starts over.
        store.send(Action.SessionMediaFilterPicked(Filter.links))
        assertFalse(store.state.sessionMediaNothingNew)
        store.finish()
    }

    @Test fun theStartOfHistoryEndsTheSearchAndHidesTheButton() = sessionTest { h ->
        val requests = SessionTestSupport.LockIsolated(0)
        val store = h.loadedStore({ ai.opencode.server.model.OpenCodeTranscriptPage(texts("first", 10) + texts("loaded", 120), next = null) }, requests)
        store.send(Action.SessionMediaTapped)
        store.send(Action.SessionMediaLoadEarlierTapped)
        store.receive(Action.EarlierHistoryResponse::class)
        SessionTestSupport.settle()
        assertEquals(1, requests.value)
        assertEquals(State.EarlierHistory.Exhausted, store.state.earlierHistory)
        assertFalse(store.state.sessionMediaNothingNew)
        assertNull(store.state.sessionMediaEarlier)
        // Exhausted: Session Media's button is gone, and a stray tap reads nothing.
        assertFalse(store.state.hasEarlierItems && store.state.earlierHistory != State.EarlierHistory.Exhausted)
        store.send(Action.SessionMediaLoadEarlierTapped)
        SessionTestSupport.settle()
        assertEquals(1, requests.value)
        store.finish()
    }

    @Test fun aFailedPageStopsAndTheNextTapRetries() = sessionTest { h ->
        val requests = SessionTestSupport.LockIsolated(0)
        val error = ai.opencode.server.OpenCodeClientError(message = "The server returned HTTP 503.", statusCode = 503)
        val store = h.loadedStore({ cursor ->
            if (requests.value == 1) throw error
            ai.opencode.server.model.OpenCodeTranscriptPage(listOf(picture("older")) + texts("loaded", 120), next = "c1".takeIf { cursor == null })
        }, requests)
        store.send(Action.SessionMediaTapped)
        store.send(Action.SessionMediaLoadEarlierTapped)
        store.receive(Action.EarlierHistoryResponse::class)
        SessionTestSupport.settle()
        assertEquals(1, requests.value)
        assertEquals(error.message, store.state.earlierHistoryError)
        assertNull(store.state.sessionMediaEarlier)
        assertFalse(store.state.sessionMediaNothingNew)
        store.send(Action.SessionMediaLoadEarlierTapped)
        assertTrue(store.state.isLoadingEarlier)
        store.receive(Action.EarlierHistoryResponse::class)
        SessionTestSupport.settle()
        assertEquals(2, requests.value)
        assertNull(store.state.earlierHistoryError)
        assertEquals(1, SessionMedia.count(store.state, Filter.media))
        store.finish()
    }

    @Test fun aDraftHasNoSessionMedia() = sessionTest { h ->
        val store = h.store(h.state { State.draft(server, draftProject = null, projects = emptyList()) })
        store.send(Action.SessionMediaTapped)
        assertNull(store.state.sessionMediaFilter)
        store.finish()
    }
}
