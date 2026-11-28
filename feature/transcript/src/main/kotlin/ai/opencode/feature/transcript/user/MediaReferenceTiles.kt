package ai.opencode.feature.transcript.user

import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.media.CachedImage
import ai.opencode.feature.media.FileAttachmentCard
import ai.opencode.feature.media.FileAttachmentDetail
import ai.opencode.feature.media.MediaAttachmentFailure
import ai.opencode.feature.media.MediaAttachmentRetryState
import ai.opencode.feature.media.MediaAttachmentUnavailableView
import ai.opencode.feature.media.MediaImageResult
import ai.opencode.feature.media.MediaReferenceLoadResult
import ai.opencode.feature.media.MediaReferenceResolver
import ai.opencode.feature.media.MediaThumbnail
import ai.opencode.feature.media.MediaZoomSources
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.media.mediaZoomSource
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.rows.JustifiedImageRows
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.PastedText
import ai.opencode.server.model.PastedTextSummary
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The transcript's `media:<hash>` tiles (iOS `Media/MediaReferenceClient.swift`'s
 * `UserImageAttachmentTile`, `ToolImageMosaicTile` and `TranscriptMediaFileChip`). They live here,
 * not in `:feature:media`, because they draw with the transcript's corners, cards and drag
 * payloads. Decoding and loading run off the main thread: Android decodes eagerly, where UIKit
 * decodes on draw.
 */

/** A tile's spinner: small, as iOS's `ProgressView().controlSize(.small)`. */
@Composable
private fun TileSpinner() {
    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OpenCodeTheme.colors.secondaryText)
}

/** The image a tile loads (iOS `MediaReferenceResolver.loadImage`), decoded off the main thread. */
private suspend fun loadImage(server: OpenCodeServer?, attachment: OpenCodeImageAttachment): MediaImageResult =
    withContext(Dispatchers.Default) { MediaReferenceResolver.loadImage(server, attachment) }

/**
 * One image of a user message's strip (iOS `UserImageAttachmentTile`): inline bytes, a
 * `media:<hash>` reference loaded through the media client, or a clear unavailable state on a
 * 404 or a `[media unavailable: …]` placeholder. A failure that may pass is a tile to tap again.
 */
@Composable
internal fun UserImageAttachmentTile(
    messageID: String?,
    images: List<OpenCodeImageAttachment>,
    attachment: OpenCodeImageAttachment,
    index: Int,
    side: Dp,
    corners: UserSurfaceCorners,
    isPending: Boolean,
    onPreviewImage: (TranscriptImagePreview) -> Unit,
) {
    val server = LocalTranscriptRowContext.current.server
    val shape = corners.shape(TranscriptMetrics.cardCornerRadius)
    // By position, as the strip lays them out: a steering message's local copies hand over to
    // the server's without the tile reloading.
    var loadedImage by remember { mutableStateOf<Bitmap?>(null) }
    var retryState by remember { mutableStateOf(MediaAttachmentRetryState()) }
    val cached = remember(attachment.id, attachment.mediaHash) { CachedImage.cachedThumbnail(attachment.id, attachment.mediaHash) }
    val image = loadedImage ?: cached
    val isUnavailable = attachment.isUnavailable ||
        retryState.failure == MediaAttachmentFailure.Unavailable ||
        MediaReferenceResolver.isUnavailable(attachment, server)
    val tile = Modifier.size(side).clip(shape)
    when {
        image != null && !isUnavailable -> {
            val dragData = MediaReferenceResolver.resolvedData(attachment)
            val bitmap = remember(image) { image.asImageBitmap() }
            val label = if (images.size == 1) stringResource(R.string.transcript_attached_image_open_description) else stringResource(R.string.transcript_attached_image_open_numbered_description, index + 1, images.size)
            val attachedImageLabel = stringResource(R.string.transcript_attached_image)
            Box(
                Modifier
                    .transcriptDragSource(
                        if (isPending || dragData == null) null else TranscriptDragPayload.ImageAttachment(attachment.id, dragData, attachment.name ?: "image-${index + 1}"),
                        cornerRadius = TranscriptMetrics.cardCornerRadius,
                    )
                    .then(tile)
                    .mediaZoomSource(attachment.id, TranscriptMetrics.cardCornerRadius, showsMedia = true)
                    .clickable(enabled = !isPending) {
                        TranscriptImagePreview.fromAttachments(images, attachment.id, attachedImageLabel, server)?.let(onPreviewImage)
                    }
                    .semantics {
                        contentDescription = label
                        role = Role.Button
                    },
            ) {
                // The anchor full-screen media zooms out of and back into; it's hidden while its
                // image is up full screen.
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (MediaZoomSources.shared.isHidden(listOf(attachment.id))) 0f else 1f },
                )
            }
        }
        isUnavailable -> MediaAttachmentUnavailableView(attachment.name, tile)
        retryState.isTransientFailure ->
            MediaAttachmentUnavailableView(attachment.name, tile, onRetry = { retryState = retryState.retry() }, isTransientFailure = true)
        else -> {
            Box(tile.background(cardFill()), contentAlignment = Alignment.Center) { TileSpinner() }
            LaunchedEffect("${server?.persistenceID ?: ""}|${attachment.mediaHash ?: attachment.id}#${retryState.attempt}") {
                retryState = retryState.beginLoad()
                when (val result = loadImage(server, attachment)) {
                    is MediaImageResult.Success -> loadedImage = result.image
                    is MediaImageResult.Failure -> retryState = retryState.copy(failure = result.failure)
                }
            }
        }
    }
}

/** iOS `ToolImageMosaicTile`'s statics. */
object ToolImageMosaicTile {
    /**
     * The load task's identity. It includes whether the image is shown, so the task runs again
     * when a memory warning empties the caches under a tile shown warm, not only for a new hash
     * or a retry.
     */
    fun loadKey(server: OpenCodeServer?, hash: String?, attempt: Int, hasImage: Boolean): String =
        "${server?.persistenceID ?: ""}|${hash ?: ""}#$attempt|$hasImage"
}

/**
 * One tile of the tool image mosaic (iOS `ToolImageMosaicTile`): inline bytes, a `media:<hash>`
 * reference, or an unavailable placeholder. [onLoad] reports a loaded image's size, for the
 * mosaic to lay out its shape.
 */
@Composable
internal fun ToolImageMosaicTile(
    attachment: OpenCodeImageAttachment,
    initialImage: Bitmap?,
    index: Int,
    total: Int,
    onPreview: (String) -> Unit,
    onLoad: (IntSize) -> Unit = {},
) {
    val server = LocalTranscriptRowContext.current.server
    var loadedImage by remember(attachment.id) { mutableStateOf<Bitmap?>(null) }
    var retryState by remember(attachment.id) { mutableStateOf(MediaAttachmentRetryState()) }
    val resolved = loadedImage ?: initialImage
    val isUnavailable = attachment.isUnavailable ||
        retryState.failure == MediaAttachmentFailure.Unavailable ||
        MediaReferenceResolver.isUnavailable(attachment, server)
    val radius = JustifiedImageRows.cornerRadius.dp
    val shape = RoundedCornerShape(radius)
    // Keeps a white image's edge visible on a white transcript.
    val separator = OpenCodeTheme.colors.border.let { it.copy(alpha = it.alpha * 0.6f) }
    val framed = Modifier.fillMaxSize().clip(shape).border(0.5.dp, separator, shape)
    when {
        isUnavailable -> MediaAttachmentUnavailableView(attachment.name, framed)
        retryState.isTransientFailure && resolved == null ->
            MediaAttachmentUnavailableView(attachment.name, framed, onRetry = { retryState = retryState.retry() }, isTransientFailure = true)
        else -> {
            val dragData = MediaReferenceResolver.resolvedData(attachment)
            val openDescription = stringResource(R.string.transcript_tool_image_open_numbered_description, index + 1, total)
            Box(
                Modifier
                    .transcriptDragSource(
                        dragData?.let { TranscriptDragPayload.ImageAttachment(attachment.id, it, attachment.name ?: "tool-image-${index + 1}") },
                        cornerRadius = radius,
                    )
                    .then(framed)
                    // Behind transparent images, and while one loads.
                    .background(cardFill())
                    .clickable { onPreview(attachment.id) }
                    .clearAndSetSemantics {
                        contentDescription = openDescription
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (resolved != null) {
                    MediaThumbnail(resolved, listOf(attachment.id), Modifier.fillMaxSize(), fits = true, cornerRadius = radius)
                } else if (attachment.mediaHash != null) {
                    TileSpinner()
                }
            }
            LaunchedEffect(ToolImageMosaicTile.loadKey(server, attachment.mediaHash, retryState.attempt, resolved != null)) {
                if (attachment.mediaHash == null) return@LaunchedEffect
                // Shown warm: the mosaic keeps the shape for when the caches are emptied.
                if (resolved != null) {
                    onLoad(IntSize(resolved.width, resolved.height))
                    return@LaunchedEffect
                }
                retryState = retryState.beginLoad()
                when (val result = loadImage(server, attachment)) {
                    is MediaImageResult.Success -> {
                        loadedImage = result.image
                        onLoad(IntSize(result.image.width, result.image.height))
                    }
                    is MediaImageResult.Failure -> retryState = retryState.copy(failure = result.failure)
                }
            }
        }
    }
}

/**
 * A non-image `media:<hash>` file, or one the server couldn't send, among a message's file chips
 * (iOS `TranscriptMediaFileChip`). Text it loads becomes a Pasted Text card.
 */
@Composable
internal fun TranscriptMediaFileChip(file: OpenCodeTranscriptFile, corners: UserSurfaceCorners) {
    val server = LocalTranscriptRowContext.current.server
    var loadedText by remember(file.id) { mutableStateOf<Pair<String, PastedTextSummary>?>(null) }
    var unavailable by remember(file.id) { mutableStateOf(false) }
    val isUnavailable = file.isUnavailable || unavailable || MediaReferenceResolver.isUnavailable(file, server)
    val loaded = loadedText
    if (loaded != null) {
        PastedTextCard(file.id, loaded.first, loaded.second, corners)
        return
    }
    val symbol = if (isUnavailable) "exclamationmark.triangle" else file.symbolName
    val label = if (isUnavailable) stringResource(R.string.transcript_media_unavailable) else file.typeLabel
    val attachedFileDescription = stringResource(R.string.transcript_attached_file_description, file.name)
    val shape = corners.shape(TranscriptMetrics.cardCornerRadius)
    FileAttachmentCard(
        name = file.name,
        symbolName = symbol,
        modifier = Modifier
            .clip(shape)
            .background(cardFill(), shape)
            .clearAndSetSemantics {
                contentDescription = attachedFileDescription
                stateDescription = label
                testTag = if (isUnavailable) "media-unavailable" else "attached-file-${file.name}"
            },
    ) { FileAttachmentDetail(label) }
    LaunchedEffect("${server?.persistenceID ?: ""}|${file.mediaHash ?: ""}") {
        val hash = file.mediaHash
        if (file.isUnavailable || hash == null || !PastedText.isText(file.mime)) return@LaunchedEffect
        val data = MediaReferenceResolver.cachedData(hash) ?: run {
            server ?: return@LaunchedEffect
            when (val result = MediaReferenceResolver.loadData(server, hash)) {
                is MediaReferenceLoadResult.Loaded -> result.data
                MediaReferenceLoadResult.Unavailable -> {
                    unavailable = true
                    null
                }
                is MediaReferenceLoadResult.Failed -> null
            }
        } ?: return@LaunchedEffect
        loadedText = withContext(Dispatchers.Default) {
            PastedText.text(file.mime, data)?.let { it to PastedTextSummary(it) }
        }
    }
}
