package ai.opencode.feature.session

import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.strings.localized
import ai.opencode.feature.media.AgentFileDestination
import ai.opencode.feature.media.AgentImageKey
import ai.opencode.feature.media.AgentImageRequest
import ai.opencode.feature.media.CachedImage
import ai.opencode.feature.media.LinkPreview
import ai.opencode.feature.media.LinkPreviewExtractor
import ai.opencode.feature.media.LinkPreviewPolicy
import ai.opencode.feature.media.LinkTextBlock
import ai.opencode.feature.media.LinkTextRun
import ai.opencode.feature.media.MediaReferenceResolver
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.transcript.links.linkTextBlocks
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedTextSummary
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.util.TimeZone
import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** The rows Session Media reads: those the server confirmed, without the turns an undo hid. */
val SessionFeature.State.sessionMediaItems: List<OpenCodeTranscriptItem>
    get() = hidingUndone(cacheableItems)

/**
 * Session Media: what a session's messages shared, newest first, in three lists: Media (images,
 * video, audio), Files (documents and pastes) and Links (public web links); each item once.
 * I/O-free: [collect] reads the transcript it's given, so the caller decides which rows are shown.
 */
object SessionMedia {
    /** The page's three lists; there is no mixed view. */
    enum class Filter(val rawValue: String) {
        /** Images, video and audio, paged through together in the viewer. */
        media("media"),
        /** Documents (PDF, text, other files) and pastes, each opened on its own. */
        files("files"),
        /** Public web links. */
        links("links");

        val id: String get() = rawValue
    }

    /** How the Media tab lays its items out; both show the same items in the same order. */
    enum class Layout(val rawValue: String) {
        tiles("tiles"), list("list");

        val id: String get() = rawValue
    }

    enum class Origin { user, agent }

    /** A Load Earlier under way: the tab, how many items it had, and the pages it may still read. */
    data class EarlierSearch(val filter: Filter, val baseline: Int, val pagesLeft: Int)

    /** The most older pages one Load Earlier reads while they bring nothing for its tab. */
    const val EARLIER_PAGES_PER_TAP = 3

    /** How many items [filter] lists for [state], as the page counts them (reply files unchecked). */
    fun count(state: SessionFeature.State, filter: Filter): Int =
        filtered(collect(state.sessionMediaItems, state.session?.directory, Instant.EPOCH), filter).size

    @Immutable
    data class Item(
        val id: String,
        val content: Content,
        val origin: Origin,
        /** The transcript row it was first shared in, counted from the oldest. */
        val row: Int = 0,
        /** When it was shared: its row's time, or the nearest earlier row's; null before any. */
        val date: Instant? = null,
    ) {
        val isLink: Boolean get() = content is Content.Link
        val filter: Filter get() = filter(content)
    }

    @Immutable
    sealed interface Content {
        /** An image attached to a message or returned by a tool: inline pixels or a `media:<hash>`. */
        data class Image(val attachment: OpenCodeImageAttachment) : Content

        /** A file by path or upload, opened in the file viewer. */
        data class File(
            val reference: AgentFileReference,
            val name: String,
            val mime: String,
            /** The pixels a tool's read of it returned, for the card. */
            val thumbnail: OpenCodeImageAttachment? = null,
        ) : Content

        /** A long paste sent as a file. */
        data class PastedText(val text: String, val summary: PastedTextSummary) : Content

        data class Link(val url: String, val label: String?) : Content
    }

    /**
     * [items] as the reader sees them (undone turns already left out), oldest first. [shownFile]
     * is the file a reply's card opens, or null while its card isn't shown (missing, unchecked).
     */
    fun collect(
        items: List<OpenCodeTranscriptItem>,
        directory: String?,
        now: Instant = Instant.now(),
        shownFile: (AgentFileReference) -> AgentFileReference? = { it },
    ): List<Item> {
        var date: Instant? = null
        val rows = mutableListOf<List<Item>>()
        val seen = HashSet<String>()
        val linkLabels = HashMap<String, String?>()
        // Each item where it was first shared, by whoever shared it; the newest row first.
        for ((index, item) in items.withIndex()) {
            // The app's own `msg_<UUID>` rows carry no time: they take the last one seen.
            TranscriptTimeStamps.date(item.id, now)?.let { date = it }
            val row = mutableListOf<Item>()
            fun add(key: String, content: Content, origin: Origin) {
                if (seen.add(key)) row += Item(key, content, origin, index, date)
            }
            fun file(ref: AgentFileReference, origin: Origin, name: String = ref.name, mime: String = ref.mime, thumbnail: OpenCodeImageAttachment? = null) {
                val hash = ref.media?.hash
                val key = if (hash != null) "media:$hash" else "file:" + (ref.target(directory)?.absolutePath ?: ref.path)
                add(key, Content.File(ref, name, mime, thumbnail), origin)
            }
            fun image(image: OpenCodeImageAttachment, origin: Origin) {
                if (image.isUnavailable || (image.mediaHash == null && image.data.isEmpty())) return
                add(image.mediaHash?.let { "media:$it" } ?: "image:${image.id}", Content.Image(image), origin)
            }
            fun transcriptFile(file: OpenCodeTranscriptFile, origin: Origin) {
                val text = file.text
                val summary = file.pasted
                if (text != null && summary != null) return add("pasted:${file.id}", Content.PastedText(text, summary), origin)
                if (file.isUnavailable) return
                val ref = file.path?.let { AgentFileReference(it) } ?: file.mediaViewerReference ?: return
                file(ref, origin, file.name, file.mime)
            }
            fun links(blocks: List<LinkTextBlock>, origin: Origin) {
                for ((target, label) in LinkPreviewExtractor.labeledTargets(blocks, limit = Int.MAX_VALUE)) {
                    linkLabels["link:${target.key}"] = label
                    add("link:${target.key}", Content.Link(target.url, label), origin)
                }
            }
            when (item) {
                is OpenCodeTranscriptItem.UserImages -> item.images.forEach { image(it, Origin.user) }
                is OpenCodeTranscriptItem.UserFiles -> item.files.forEach { transcriptFile(it, Origin.user) }
                is OpenCodeTranscriptItem.UserText -> links(listOf(LinkTextBlock(0, prose = listOf(LinkTextRun(item.text)))), Origin.user)
                is OpenCodeTranscriptItem.Tool -> {
                    val read = item.call.fileReference?.takeIf { item.call.status == OpenCodeToolCall.Status.Completed && it.kind.showsCard }
                    // An image read returns the same pixels as an attachment: one card, the file.
                    val pixels = item.call.imageAttachments.filter { !it.isUnavailable && (it.mediaHash != null || it.data.isNotEmpty()) }
                    if (read != null && read.kind.isVisual) {
                        file(read, Origin.agent, thumbnail = pixels.firstOrNull())
                    } else {
                        pixels.forEach { image(it, Origin.agent) }
                        if (read != null) file(read, Origin.agent)
                    }
                    item.call.videos.orEmpty().forEach { transcriptFile(it, Origin.agent) }
                }
                is OpenCodeTranscriptItem.AssistantMarkdown -> {
                    val blocks = OpenCodeMarkdownBlock.blocks(item.text)
                    for (block in blocks) {
                        when (val kind = block.kind) {
                            is Kind.Files -> kind.files.forEach { ref -> shownFile(ref)?.let { file(it, Origin.agent) } }
                            is Kind.Image -> AgentFileReference.fromLink(kind.source)?.takeIf { it.kind.showsCard }?.let { file(it, Origin.agent) }
                            else -> Unit
                        }
                    }
                    links(blocks.linkTextBlocks(), Origin.agent)
                }
                else -> Unit
            }
            if (row.isNotEmpty()) rows += row
        }
        // A link is titled by its newest mention's label.
        val result = mutableListOf<Item>()
        for (item in rows.asReversed().flatten()) {
            val content = item.content
            result += if (content is Content.Link) item.copy(content = content.copy(label = linkLabels[item.id])) else item
        }
        return result
    }

    /** The files replies name as cards, which are checked before their cards show. */
    fun replyFiles(items: List<OpenCodeTranscriptItem>): List<AgentFileReference> = buildList {
        for (item in items) {
            if (item !is OpenCodeTranscriptItem.AssistantMarkdown) continue
            for (block in OpenCodeMarkdownBlock.blocks(item.text)) (block.kind as? Kind.Files)?.let { addAll(it.files) }
        }
    }

    /** The media ID the viewer opens for [item], so it flies from the card and lands back on it. */
    fun zoomSourceID(item: Item): String = when (val content = item.content) {
        is Content.Image -> content.attachment.id
        is Content.File -> content.reference.zoomSourceID
        is Content.PastedText, is Content.Link -> "session-media|${item.id}"
    }

    fun filter(content: Content): Filter = when (content) {
        is Content.Image -> Filter.media
        is Content.File -> if (content.reference.kind.isMedia) Filter.media else Filter.files
        is Content.PastedText -> Filter.files
        is Content.Link -> Filter.links
    }

    fun filtered(items: List<Item>, filter: Filter): List<Item> = items.filter { it.filter == filter }

    /** A tile's length: "0:42", "12:05", "1:02:09". */
    fun durationText(ms: Long): String {
        val seconds = (ms + 500) / 1000
        val hours = seconds / 3600
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    /** A month of the Media grid: its label (null for items with no time) and its items, newest first. */
    @Immutable
    data class Month(val label: String?, val items: List<Item>)

    /** [items] (newest first) in runs by calendar month. */
    fun months(items: List<Item>, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = appFormattingLocale()): List<Month> {
        val months = mutableListOf<Month>()
        var current: YearMonth? = null
        var run = mutableListOf<Item>()
        var started = false
        for (item in items) {
            val month = item.date?.atZone(zone)?.let(YearMonth::from)
            if (started && month == current) {
                run += item
                continue
            }
            if (started) months += Month(current?.let { monthLabel(it, now, zone, locale) }, run)
            current = month
            run = mutableListOf(item)
            started = true
        }
        if (started) months += Month(current?.let { monthLabel(it, now, zone, locale) }, run)
        return months
    }

    /** "November" within this year, "November 2025" before it, in [locale]'s words. */
    fun monthLabel(month: YearMonth, now: Instant, zone: ZoneId, locale: Locale): String {
        val skeleton = if (month.year == now.atZone(zone).year) "MMMM" else "MMMMy"
        val format = DateFormat.getInstanceForSkeleton(skeleton, locale).apply {
            timeZone = TimeZone.getTimeZone(zone.id)
            setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        }
        return format.format(Date.from(month.atDay(15).atStartOfDay(zone).toInstant()))
    }

    /**
     * The viewer's gallery for a Media card: every media item, in the session's order (oldest
     * first, as a transcript gallery pages), opening on [opening]. Pages carry the card's zoom ID.
     */
    fun gallery(items: List<Item>, opening: Item, server: OpenCodeServer, directory: String?): TranscriptImagePreview? {
        if (opening.filter != Filter.media) return null
        val pages = filtered(items, Filter.media).sortedBy { it.row }.mapNotNull { page(it, directory) }.distinctBy { it.id }
        val id = zoomSourceID(opening)
        if (pages.none { it.id == id }) return null
        return TranscriptImagePreview(id, pages, server)
    }

    private fun page(item: Item, directory: String?): TranscriptImagePreview.Page? = when (val content = item.content) {
        is Content.Image -> {
            val image = content.attachment
            val bytes = MediaReferenceResolver.resolvedData(image)
            val thumbnail = if (bytes != null) CachedImage.cachedThumbnail(image.id, image.mediaHash) else null
            TranscriptImagePreview.Page(
                zoomSourceID(item), thumbnail, image.name ?: localized(R.string.session_visual_image), mediaHash = image.mediaHash, name = image.name,
                original = if (thumbnail != null) bytes else image.data.takeIf { it.isNotEmpty() }, labelIsPlaceholder = image.name == null,
                placeholderSize = if (thumbnail != null && bytes != null) CachedImage.pixelSize(bytes) else null,
            )
        }
        is Content.File -> {
            val ref = content.reference
            TranscriptImagePreview.Page(
                zoomSourceID(item), null, content.name, name = content.name,
                isVideo = ref.kind == AgentFileReference.Kind.Video, file = AgentFileDestination(ref, directory),
            )
        }
        is Content.PastedText, is Content.Link -> null
    }

    /**
     * The key a tile reads an image file's pixels under: the file as it is now, which the viewer
     * opens. A tool's read of it may hold an older version of the file.
     */
    fun imageFileKey(content: Content, server: OpenCodeServer, directory: String?): AgentImageKey? {
        val file = content as? Content.File ?: return null
        if (file.reference.kind != AgentFileReference.Kind.Image) return null
        val target = file.reference.target(directory) ?: return null
        return AgentImageKey(server.persistenceID, target.absolutePath)
    }

    /**
     * A tile's read: Session Media is a screen the person opened, so its tiles on screen load on
     * demand even under Data Saver, which restricts only background data.
     */
    fun tileImageRequest(request: okhttp3.Request): AgentImageRequest = AgentImageRequest(request, allowsConstrainedNetworkAccess = true)

    /** The pixels a tile shows from the transcript itself: a read's only where the file's own aren't shown. */
    fun tileAttachment(content: Content, showsFile: Boolean): OpenCodeImageAttachment? = when (content) {
        is Content.Image -> content.attachment
        is Content.File -> content.thumbnail.takeUnless { showsFile }
        is Content.PastedText, is Content.Link -> null
    }

    /** The card's title. */
    fun title(content: Content): String = when (content) {
        is Content.Image -> content.attachment.name ?: localized(R.string.session_visual_image)
        is Content.File -> content.name
        is Content.PastedText -> ai.opencode.server.model.PastedText.title
        is Content.Link -> content.label ?: LinkPreview.readableTitle(content.url) ?: LinkPreviewPolicy.displayHost(content.url)
    }

    /** The card's type line: the file type, a paste's length, or a link's address. */
    fun detail(content: Content): String = when (content) {
        is Content.Image -> {
            val name = content.attachment.name
            val mime = content.attachment.mime ?: name?.let(AttachmentFileType::declaredMime)
            if (mime != null) AttachmentFileType.label(name ?: "", mime) else localized(R.string.session_visual_image)
        }
        is Content.File -> if (content.reference.media == null) content.reference.typeLabel else AttachmentFileType.label(content.name, content.mime)
        is Content.PastedText -> content.summary.length
        is Content.Link -> content.url
    }

    fun symbolName(content: Content): String = when (content) {
        is Content.Image -> "photo"
        is Content.File -> if (content.reference.media == null) content.reference.symbolName else AttachmentFileType.symbolName(content.name, content.mime)
        is Content.PastedText -> "doc.plaintext"
        is Content.Link -> "globe"
    }
}
