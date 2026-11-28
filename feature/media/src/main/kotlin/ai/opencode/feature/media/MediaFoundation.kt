package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.core.tuning.Tunable
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import java.security.MessageDigest

/**
 * The transcript's complete spacing vocabulary (iOS `TranscriptMetrics` in
 * `Transcript/TranscriptEntryView.swift`, at the `TranscriptTunables` defaults it reads: the
 * Tuning Panel isn't ported). Rows own their outer rhythm; renderers only use values from this
 * scale so padding never compounds. Lives here because media renderers use it and
 * `:feature:transcript` sits above media; the transcript uses this one.
 */
object TranscriptMetrics {
    val compact: Dp = 4.dp
    val clustered: Dp = 8.dp
    /** `transcript.bubble.verticalPadding` default. */
    val bubbleVertical: Dp = 8.dp
    val standard: Dp = 12.dp
    /** `transcript.row.contentGutter` default. */
    val contentGutter: Dp = 16.dp
    val turn: Dp = 20.dp

    /** `transcript.bubble.cornerRadius` default. */
    val bubbleCornerRadius: Dp = 10.dp
    /** Content surfaces: code, tables, cards, and images (`transcript.card.cornerRadius` default). */
    val cardCornerRadius: Dp = 8.dp
    /** Controls inside sheets and cards: choices and fields. */
    val controlCornerRadius: Dp = 6.dp

    /** `transcript.bubble.maxFraction` default. */
    const val maximumUserBubbleFraction: Float = 0.82f

    /** The widest the transcript and composer grow, gutters included (tablets centre at it). */
    val readableColumnWidth: Dp = 720.dp

    /** Within a reply, paragraphs, Used N, notices and Working are 16 apart (`transcript.row.flowHalf`). */
    val flowHalf: Dp = 8.dp
    /** A user message is 20 from the reply around it (`transcript.row.turnHalf`). */
    val turnHalf: Dp = 12.dp
    /** A compaction is a context boundary: 24 from its neighbours (`transcript.row.sectionHalf`). */
    val sectionHalf: Dp = 16.dp

    /** iOS `MinimumHitRect.side` (44 pt) → Android's 48 dp minimum target. */
    val minimumHitSide: Dp = 48.dp
}

/**
 * What the full-screen photo viewer pages through (iOS `TranscriptImagePreview`, now in
 * `Media/TranscriptImageViewer.swift`). Lives in media because the viewer, the image gallery
 * and Mermaid cards build it; the transcript sits above media. A `media:<hash>` page may
 * arrive without its image: the viewer loads it from [server].
 */
data class TranscriptImagePreview(val id: String, val pages: List<Page>, val server: OpenCodeServer? = null) {
    /** [label] is the accessibility label / title; [image] the decoded original, or `null` while it loads. */
    data class Page(
        val id: String,
        val image: Bitmap?,
        val label: String,
        /** A line under the title, such as dimensions and size. */
        val detail: String? = null,
        val mediaHash: String? = null,
        val name: String? = null,
        val isUnavailable: Boolean = false,
        /** A video's poster frame, which Draw on Image skips. */
        val isVideo: Boolean = false,
        val loadFailed: Boolean = false,
        /** The app wrote [label] itself ("Image 1 of 3"), so it isn't a name worth showing as a title. */
        val labelIsPlaceholder: Boolean = false,
        val file: AgentFileDestination? = null,
        val videoURL: Uri? = null,
        val imageSource: SessionVisualImageSource? = null,
        /** Immutable encoded bytes, kept so distant pages can release their full-size pixels. */
        val original: ByteArray? = null,
        val hasDecodedOriginal: Boolean = false,
        val originalFailed: Boolean = false,
        val showsSource: Boolean = false,
        /**
         * [image] is the shared preview of [file], smaller than the file, standing in until the
         * full-resolution pixels decode; laid out at this, the file's own size.
         */
        val placeholderSize: AgentImageSize? = null,
    ) {
        constructor(file: AgentFileDestination) : this(
            id = file.reference.zoomSourceID,
            image = null,
            label = file.reference.name,
            name = file.reference.name,
            isVideo = file.reference.kind == AgentFileReference.Kind.Video,
            file = file,
        )

        val usesFileViewer: Boolean get() = file != null || videoURL != null
        val originalPending: Boolean get() = original != null && !hasDecodedOriginal && !originalFailed

        // Snapshots compare arrays and bitmaps by identity: never scan pixels on the UI thread.
        override fun hashCode(): Int = id.hashCode()

        internal fun evicted(): Page = if (image != null && !isUnavailable && !originalFailed && (
                !usesFileViewer && (original != null || mediaHash != null || imageSource != null) ||
                    videoURL == null && file?.reference?.kind == AgentFileReference.Kind.Image
            )
        ) copy(image = null, hasDecodedOriginal = false, placeholderSize = null) else this
    }

    constructor(file: AgentFileDestination, server: OpenCodeServer) : this(file.reference.zoomSourceID, listOf(Page(file)), server)

    constructor(id: String, image: Bitmap, label: String, server: OpenCodeServer? = null, labelIsPlaceholder: Boolean = false) :
        this(id, listOf(Page(id, image, label, labelIsPlaceholder = labelIsPlaceholder)), server)

    companion object {
        /**
         * iOS `init?(attachments:selectedID:label:server:)`: every decodable attachment, and
         * every `media:<hash>` or unavailable one (shown loading or unavailable), each named or
         * labelled "label i of n"; `null` when [selectedID] isn't among them.
         */
        fun fromAttachments(
            attachments: List<OpenCodeImageAttachment>,
            selectedID: String,
            label: String,
            server: OpenCodeServer? = null,
        ): TranscriptImagePreview? {
            val items = attachments.mapNotNull { attachment ->
                val image = CachedImage.cached(attachment.id, attachment.mediaHash)
                if (image != null || attachment.data.isNotEmpty() || attachment.mediaHash != null || attachment.isUnavailable) {
                    attachment to image
                } else null
            }
            if (items.none { it.first.id == selectedID }) return null
            return TranscriptImagePreview(
                selectedID,
                items.mapIndexed { index, (attachment, image) ->
                    val bytes = MediaReferenceResolver.resolvedData(attachment)
                    val thumbnail = if (image == null && bytes != null) CachedImage.cachedThumbnail(attachment.id, attachment.mediaHash) else null
                    Page(
                        id = attachment.id,
                        image = image ?: thumbnail,
                        label = attachment.name ?: localized(R.string.media_image_page_label, label, index + 1, items.size),
                        original = if (thumbnail != null) bytes else attachment.data.takeIf { it.isNotEmpty() },
                        hasDecodedOriginal = image != null && attachment.data.isNotEmpty(),
                        placeholderSize = if (thumbnail != null && bytes != null) CachedImage.pixelSize(bytes) else null,
                        mediaHash = attachment.mediaHash,
                        name = attachment.name,
                        isUnavailable = attachment.isUnavailable || MediaReferenceResolver.isUnavailable(attachment, server),
                        labelIsPlaceholder = attachment.name == null,
                    )
                },
                server,
            )
        }

        /** Already-decoded images by ID, each labelled "label i of n" (the agent image gallery and lab). */
        fun fromImages(
            images: List<Pair<String, Bitmap?>>,
            selectedID: String,
            label: String,
        ): TranscriptImagePreview? {
            val decoded = images.mapNotNull { (id, image) -> image?.let { id to it } }
            if (decoded.none { it.first == selectedID }) return null
            return TranscriptImagePreview(
                selectedID,
                decoded.mapIndexed { index, (id, image) -> Page(id, image, localized(R.string.media_image_page_label, label, index + 1, decoded.size), labelIsPlaceholder = true) },
            )
        }
    }
}

/**
 * Decoded images the app keeps for the transcript and viewers, stored in Coil's memory cache so
 * they share one budget and memory-pressure purge with everything Coil loads.
 */
class DecodedImageCache(
    private val memory: MemoryCache = MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build(),
) {
    fun image(source: String): Bitmap? = (memory[memoryKey(source)]?.image as? BitmapImage)?.bitmap

    fun insert(image: Bitmap, source: String) {
        memory[memoryKey(source)] = MemoryCache.Value(image.asImage())
    }

    companion object Key : DependencyKey<DecodedImageCache> {
        override val liveValue: DecodedImageCache by lazy {
            DecodedImageCache(checkNotNull(SingletonImageLoader.get(dependency(AppContext)).memoryCache))
        }
        override val testValue: DecodedImageCache get() = DecodedImageCache()

        /** Data URLs must not become multi-megabyte map keys. */
        fun memoryKey(source: String): MemoryCache.Key =
            MemoryCache.Key(MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { "%02x".format(it) })
    }
}
