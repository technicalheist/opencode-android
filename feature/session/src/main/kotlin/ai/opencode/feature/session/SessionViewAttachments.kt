package ai.opencode.feature.session

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.files.ComposerFileStaging
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.strings.localized
import ai.opencode.core.strings.localizedPlural
import ai.opencode.core.ui.OpenCodeMinTapTarget
import ai.opencode.core.ui.drawerPanHorizontalScroller
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.composer.AttachmentCameraView
import ai.opencode.feature.composer.AttachmentMenuItem
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.AttachmentRemoval
import ai.opencode.feature.composer.AttachmentRemoval.attachmentRemovalExit
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerFileChip
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.feature.transcript.user.quoteCardHeight
import ai.opencode.feature.composer.ComposerFileImporter
import ai.opencode.feature.composer.ComposerFileRouting
import ai.opencode.feature.composer.ComposerFileSource
import ai.opencode.feature.composer.ComposerImageImporter
import ai.opencode.feature.composer.ComposerImageSource
import ai.opencode.feature.composer.ComposerLocationAttachment
import ai.opencode.feature.composer.ComposerPhotoViewer
import ai.opencode.feature.composer.ComposerPickers
import ai.opencode.feature.composer.PastedTextCard
import ai.opencode.feature.composer.PlusMenuTunables
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.composer.plusmenu.VideoDurationBadge
import ai.opencode.feature.composer.plusmenu.formatVideoDuration
import ai.opencode.feature.media.FileAttachmentCard
import ai.opencode.feature.media.ImageDrawingAdd
import ai.opencode.feature.media.MediaFlightAccessory
import ai.opencode.feature.media.MediaImageResult
import ai.opencode.feature.media.MediaReferenceResolver
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.mediaZoomSource
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.PastedText
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import ai.opencode.feature.composer.SharedLocationCard
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Port of iOS `Session/SessionView+Attachments.swift`: the composer's pending attachments row
// (images, pasted text, inline PDFs, uploading files, the location card), image and file imports
// with their errors and the large-upload confirmation, dropped text, and the + menu's items. The
// imports run on :feature:composer's `ComposerImageImporter` / `ComposerFileImporter`; the cards
// are the composer's (`PastedTextCard`, `ComposerFileChip`, `ComposerLocationAttachment`) and
// media's `FileAttachmentCard`. The + menu's open/close/morph state lives in `SessionView`.

// region Imports (iOS `startImageImport`, `startFileImport`, `importPickedFiles`, drops)

/**
 * The session screen's attachment imports, iOS `SessionView`'s `imageImporter`, `fileImporter`,
 * `attachmentError` and `largeUpload`. Owned by the screen ([rememberSessionAttachmentImporter]
 * in `SessionView`) so an import outlives the picker or sheet that started it; its results go to
 * the reducer as `attachmentAdded` / `filesStaged`. One image import runs at a time (a new one
 * supersedes it); file imports each finish on their own. Results for a composer that's no longer
 * on screen (another session, or a draft that became one) are dropped.
 */
@Stable
class SessionAttachmentImporter internal constructor(
    private val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    private val scope: CoroutineScope,
) {
    /** Files over [LARGE_UPLOAD_BYTES] wait for this confirmation (iOS `SessionView.LargeUpload`). */
    data class LargeUpload(val files: List<ComposerStagedFile>, val onStaged: (() -> Unit)?) {
        val title: String get() = localized(R.string.session_large_upload_title, AttachmentFileType.byteCount(files.sumOf { it.size }))

        val message: String
            get() {
                val large = files.filter { it.size > LARGE_UPLOAD_BYTES }
                if (large.size == 1) return localized(R.string.session_large_upload_message_one, large[0].name)
                return localizedPlural(R.plurals.session_large_upload_message_many, large.size, large.size)
            }
    }

    /** iOS `imageImporter`. */
    val imageImporter = ComposerImageImporter(scope)

    /** iOS `fileImporter`. */
    val fileImporter = ComposerFileImporter(scope)

    /** “Couldn’t Add Image” alert's message (iOS `attachmentError`). */
    var attachmentError: String? by mutableStateOf(null)

    /** The large-upload confirmation waiting, if any. */
    var largeUpload: LargeUpload? by mutableStateOf(null)
        private set

    /** iOS `imageImporter.isLoading`. */
    val isLoadingImages: Boolean get() = imageImporter.isLoading

    /** iOS `fileImporter.isLoading`. */
    val isLoadingFiles: Boolean get() = fileImporter.isLoading

    /** The composer an import started in is still the one on screen. */
    private fun sameComposer(identity: SessionFeature.State.TaskIdentity): Boolean = store.state.taskIdentity == identity

    /**
     * iOS `startImageImport`: loads, size-checks (20 MB) and re-encodes each image, adding each as
     * it lands. [onFailure] gets a source's chosen attachment ID when it fails; [onAdded] runs once
     * when at least one was added; otherwise the error explains why none were.
     */
    fun startImageImport(
        sources: List<ComposerImageSource>,
        onAdded: (() -> Unit)? = null,
        onFailure: (UUID?) -> Unit = {},
        onCancel: () -> Unit = {},
        onCompletion: (ComposerImageImporter.Result) -> Unit = {},
    ) {
        val identity = store.state.taskIdentity
        attachmentError = null
        imageImporter.start(
            sources = sources,
            onAttachment = { attachment ->
                if (sameComposer(identity)) store.send(SessionFeature.Action.attachmentAdded(attachment))
            },
            onFailure = onFailure,
            onCancel = onCancel,
            onCompletion = { result ->
                onCompletion(result)
                if (!sameComposer(identity)) return@start
                if (result.addedCount > 0) {
                    onAdded?.invoke()
                } else {
                    attachmentError = if (result.sawOversizedSource) {
                        localized(R.string.session_attachment_image_too_large)
                    } else {
                        localized(R.string.session_attachment_image_load_failed)
                    }
                }
            },
            // iOS `maxPhotoSelection(plusMenu: true)`: the + menu's 8.
            limit = AttachmentPickerButton.MAX_PHOTO_SELECTION,
        )
    }

    /** iOS `cancelImageImport`. */
    fun cancelImageImport() {
        imageImporter.cancel()
        attachmentError = null
    }

    /**
     * iOS `startFileImport`: stages each picked file (a private copy the upload streams from). A
     * file over 200 MB asks first ([largeUpload]); otherwise they go to the reducer as
     * `filesStaged`. A picked video keeps its pick's ID ([ComposerFileSource.PhotosPickerItem]).
     */
    fun startFileImport(
        sources: List<ComposerFileSource>,
        onStaged: (() -> Unit)? = null,
        onFailed: (() -> Unit)? = null,
    ) {
        if (sources.isEmpty()) return
        val identity = store.state.taskIdentity
        attachmentError = null
        fileImporter.start(
            sources = sources,
            onStaged = { staged ->
                if (!sameComposer(identity)) {
                    staged.forEach { ComposerFileStaging.discard(it.url) }
                } else if (staged.any { it.size > LARGE_UPLOAD_BYTES }) {
                    // The server holds an upload in memory, so very large files ask first.
                    largeUpload = LargeUpload(staged, onStaged)
                } else {
                    store.send(SessionFeature.Action.filesStaged(staged))
                    onStaged?.invoke()
                }
            },
            onFailure = { failures ->
                if (!sameComposer(identity)) return@start
                onFailed?.invoke()
                attachmentError = if (failures == 1) localized(R.string.session_attachment_file_add_failed) else localizedPlural(R.plurals.session_attachment_files_add_failed, failures, failures)
            },
        )
    }

    /** Upload in the large-upload alert. */
    fun confirmLargeUpload() {
        val upload = largeUpload ?: return
        store.send(SessionFeature.Action.filesStaged(upload.files))
        upload.onStaged?.invoke()
        largeUpload = null
    }

    /** Cancel in the large-upload alert: the staged copies go. */
    fun cancelLargeUpload() {
        val upload = largeUpload ?: return
        upload.files.forEach { ComposerFileStaging.discard(it.url) }
        largeUpload = null
    }

    /**
     * iOS `importPickedFiles`: from Files, images keep the inline image path and everything else
     * uploads. [onFocus] is `focusComposer`.
     */
    fun importPickedFiles(uris: List<Uri>, onFocus: () -> Unit) {
        if (uris.isEmpty()) return
        scope.launch {
            val images = withContext(Dispatchers.IO) { uris.map { it to ComposerFileRouting.isInlineImage(it) } }
            val inline = images.filter { it.second }.map { it.first }
            val files = images.filterNot { it.second }.map { it.first }
            if (inline.isNotEmpty()) startImageImport(inline.map(ComposerImageSource::ItemProvider), onAdded = onFocus)
            if (files.isNotEmpty()) startFileImport(files.map(ComposerFileSource::Url), onStaged = onFocus)
        }
    }

    /**
     * iOS `handleComposerDrop(_:)`: something dropped anywhere on the composer. Images attach as
     * photos and other files stage as uploads (only while the composer takes attachments, not
     * answering a request); text joins the draft ([insertDroppedText]). False when nothing in
     * [clip] was usable. The caller holds the drop's URI permissions while the imports read.
     */
    fun handleComposerDrop(clip: android.content.ClipData, mimeTypes: List<String>): Boolean {
        val canAttach = store.state.takesPasteAttachments
        val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
        val resolver = dependency(ai.opencode.core.dependencies.AppContext).contentResolver
        val uris = items.mapNotNull { it.uri }
        fun isImage(uri: Uri) = (resolver.getType(uri) ?: mimeTypes.firstOrNull { it.startsWith("image/") })?.startsWith("image/") == true
        val images = if (canAttach) uris.filter(::isImage) else emptyList()
        val files = if (canAttach) uris.filterNot(::isImage) else emptyList()
        val texts = items.filter { it.uri == null }.mapNotNull { it.text?.toString()?.takeIf(String::isNotEmpty) }
        if (images.isEmpty() && files.isEmpty() && texts.isEmpty()) return false
        if (images.isNotEmpty()) startImageImport(images.map(ComposerImageSource::ItemProvider))
        if (files.isNotEmpty()) startFileImport(files.map(ComposerFileSource::Url))
        texts.forEach(::insertDroppedText)
        return true
    }

    /**
     * The text half of iOS `handleComposerDrop` (and a pasted text the field didn't take): a long
     * text becomes a Pasted Text attachment, anything else joins the draft on its own line.
     */
    fun insertDroppedText(text: String) {
        if (text.isEmpty()) return
        val state = store.state
        if (PastedText.isLong(text) && state.takesPasteAttachments) {
            store.send(SessionFeature.Action.attachmentAdded(PastedText.attachment(text)))
        } else if (state.draft.isEmpty()) {
            store.send(SessionFeature.Action.Binding(BindingAction.Draft(text)))
        } else {
            val separator = if (state.draft.endsWith("\n") || state.draft.endsWith(" ")) "" else "\n"
            store.send(SessionFeature.Action.Binding(BindingAction.Draft(state.draft + separator + text)))
        }
    }

    /**
     * iOS `syncPickedPhotos` / `addLibraryPhotos`: photos picked in the system picker attach at
     * once — images on the inline image path, videos as uploads (when the server takes them).
     * At most [AttachmentPickerButton.MAX_PHOTO_SELECTION] (8). [onAdded] is `focusComposer`.
     */
    fun importPickedMedia(uris: List<Uri>, onAdded: (() -> Unit)? = null) {
        if (uris.isEmpty()) return
        scope.launch {
            val picks = uris.take(AttachmentPickerButton.MAX_PHOTO_SELECTION)
            val kinds = withContext(Dispatchers.IO) { picks.map { it to ComposerPickers.isVideo(it) } }
            val videos = kinds.filter { it.second }.map { it.first }
            val images = kinds.filterNot { it.second }.map { it.first }
            val uploads = store.state.supportsFileUploads
            if (videos.isNotEmpty() && uploads) startFileImport(videos.map { ComposerFileSource.PhotosPickerItem(it) }, onStaged = onAdded)
            // A video the server can't take as a file attaches as its first frame, as iOS's loader does.
            val imageSources = images + if (uploads) emptyList() else videos
            if (imageSources.isNotEmpty()) startImageImport(imageSources.map { ComposerImageSource.PhotosPickerItem(it) }, onAdded = onAdded)
        }
    }

    /** iOS `captureCameraPhoto`: a photo taken in the composer's camera attaches like a picked one. */
    fun captureCameraPhoto(data: ByteArray, onAdded: (() -> Unit)? = null) {
        startImageImport(listOf(ComposerImageSource.Data(data)), onAdded = onAdded)
    }

    /** iOS `pasteAttachments`: images from the clipboard (the + menu's Paste). */
    fun pasteAttachments(sources: List<ComposerImageSource>, onAdded: (() -> Unit)? = null) {
        startImageImport(sources, onAdded = onAdded)
    }

    /** The + menu's Paste: the clipboard's images, read through [PasteboardClient] (iOS `PasteButton`'s providers). */
    fun pasteClipboardImages() {
        val uris = dependency(PasteboardClient).imageURIs()
        if (uris.isNotEmpty()) pasteAttachments(uris.map(ComposerImageSource::ItemProvider))
    }

    /** iOS `removeComposerAttachment`: an image, paste or inline file by its ID, or an upload. */
    fun removeComposerAttachment(id: String) {
        val state = store.state
        val index = state.pendingAttachments.indexOfFirst { it.id == id }
        if (index >= 0) {
            store.send(SessionFeature.Action.attachmentRemoved(index))
            return
        }
        val file = state.pendingFiles.firstOrNull { it.id.toString() == id } ?: return
        store.send(SessionFeature.Action.fileRemoved(file.id))
    }

    /** iOS `composerAttachmentIDs`: what the composer holds, by ID. */
    val composerAttachmentIDs: Set<String>
        get() = store.state.pendingAttachments.map { it.id }.toSet() + store.state.pendingFiles.map { it.id.toString() }

    companion object {
        /** iOS `SessionView.largeUploadBytes`. */
        const val LARGE_UPLOAD_BYTES: Long = 200L * 1024 * 1024
    }
}

/** The screen's [SessionAttachmentImporter]; hoist it to `SessionView` so imports outlive their pickers. */
@Composable
fun rememberSessionAttachmentImporter(store: StoreOf<SessionFeature.State, SessionFeature.Action>): SessionAttachmentImporter {
    val scope = rememberCoroutineScope()
    return remember(store, scope) { SessionAttachmentImporter(store, scope) }
}

/**
 * Draw on Image's Add to Prompt for a viewer opened from this session, or null (no Draw button)
 * while it takes no attachments. The image joins the session's draft as a pasted one would
 * ([SessionFeature.Action.DrawingAdded]), or replaces the pending photo it was drawn on
 * ([drawnOn], the page a viewer of the composer's photos shows).
 */
internal fun drawingHandler(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    drawnOn: () -> TranscriptImagePreview.Page? = { null },
): ImageDrawingAdd? {
    if (!store.state.takesPasteAttachments) return null
    val identity = store.state.taskIdentity
    return { attachment ->
        val page = drawnOn()
        var replacing: String? = null
        // A drawn video frame is added beside its video.
        if (page != null && !page.isVideo) replacing = ComposerPhotoViewer.attachmentID(page.id)
        store.send(SessionFeature.Action.DrawingAdded(attachment, from = identity, replacing = replacing))
        val added = store.state.pendingAttachments.any { it.id == attachment.id }
        if (added) ComposerPhotoViewer.mediaID(attachment.id) else null
    }
}

internal fun drawingAccessories(store: StoreOf<SessionFeature.State, SessionFeature.Action>, context: Context): (String) -> List<MediaFlightAccessory> =
    { mediaID -> ComposerPhotoViewer.flightAccessories(mediaID, store.state.pendingAttachments, context) }

/**
 * The two import alerts iOS `SessionView` presents: “Couldn’t Add Image” with the import's
 * error, and “Upload <size>?” for very large files.
 */
@Composable
fun SessionAttachmentAlerts(importer: SessionAttachmentImporter) {
    val error = importer.attachmentError
    if (error != null) {
        OpenCodeAlertDialog(
            onDismissRequest = { importer.attachmentError = null },
            title = { Text(stringResource(R.string.session_attachment_add_image_failed_title)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = { importer.attachmentError = null }) { Text(stringResource(CoreR.string.common_ok)) } },
        )
    }
    val upload = importer.largeUpload
    if (upload != null) {
        OpenCodeAlertDialog(
            onDismissRequest = importer::cancelLargeUpload,
            title = { Text(upload.title) },
            text = { Text(upload.message) },
            confirmButton = { TextButton(onClick = importer::confirmLargeUpload) { Text(stringResource(R.string.session_attachment_upload)) } },
            dismissButton = { TextButton(onClick = importer::cancelLargeUpload) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
    }
}

// endregion

// region + menu items

/**
 * iOS `attachmentMenuItems`: Camera (when there is one), Photos, Files (when the server takes
 * uploads), Location (one at a time; kept while the menu closes after choosing it), and Paste
 * (when the clipboard has an image).
 */
fun sessionAttachmentMenuItems(
    hasCamera: Boolean,
    supportsFileUploads: Boolean,
    wantsLocationAttachment: Boolean,
    keepingLocationRowWhileMenuCloses: Boolean,
    clipboardHasImages: Boolean,
): List<AttachmentMenuItem> = buildList {
    if (hasCamera) add(AttachmentMenuItem.Camera)
    add(AttachmentMenuItem.Photos)
    if (supportsFileUploads) add(AttachmentMenuItem.Files)
    if (!wantsLocationAttachment || keepingLocationRowWhileMenuCloses) add(AttachmentMenuItem.Location)
    if (clipboardHasImages) add(AttachmentMenuItem.Paste)
}

/**
 * iOS `AttachmentCameraView` in a `fullScreenCover` (black, dark): the + menu's Camera. A photo
 * attaches like a picked one and brings the keyboard back ([onAdded], iOS `focusComposer`);
 * Close or system Back puts the camera away.
 */
@Composable
fun SessionAttachmentCamera(importer: SessionAttachmentImporter, onDismiss: () -> Unit, onAdded: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AttachmentCameraView(
                onCapture = { data ->
                    onDismiss()
                    importer.captureCameraPhoto(data, onAdded = onAdded)
                },
                onBack = onDismiss,
            )
        }
    }
}

// endregion

// region Pending attachments row

object PendingAttachmentMetrics {
    val slotSide: Dp = 76.dp
    val slotRadius: Dp = 22.dp
}

// The × sits over a card's top trailing corner; its whole tap target fits past the row's last card.
private val RemoveTargetReach = ComposerPhotoViewer.removeBadgeOffset.x + OpenCodeMinTapTarget / 2

private suspend fun LazyListState.revealFully(key: Any, index: Int) {
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }
    if (item == null) {
        animateScrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
    }
    val end = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    when {
        item.offset < 0 -> animateScrollBy(item.offset.toFloat())
        item.offset + item.size > end -> animateScrollBy(minOf(item.offset + item.size - end, item.offset).toFloat())
    }
}

// The full-screen viewer's image flies home into this slot, so all of it has to be on screen.
private suspend fun LazyListState.reveal(index: Int) {
    val end = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    val shown = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (shown != null && shown.offset >= 0 && shown.offset + shown.size <= end) return
    scrollToItem(index)
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    scrollBy((item.offset + item.size - end).toFloat())
}

/**
 * iOS `pendingAttachmentsRow`: the composer's attachments in one horizontal row — images, pasted
 * texts, inline files (PDFs the model reads), uploading files, then the location card — each with
 * a remove button over its top trailing corner. Values in, actions out; no store.
 *
 * @param attachments `state.pendingAttachments`.
 * @param files `state.pendingFiles`.
 * @param canRetryFiles `state.supportsFileUploads` (a failed upload offers retry).
 * @param showsLocation whether the location card is mounted (iOS `locationGate.isMounted`).
 * @param location `state.pendingLocation`; null while locating.
 * @param isLocating `state.isLocating`.
 * @param onRemoveAttachment `.attachmentRemoved(index)`.
 * @param onRemoveFile `.fileRemoved(id)` (cancels an upload in flight).
 * @param onRetryFile `.fileRetryTapped(id)`.
 * @param onRemoveLocation `.locationRemoved`.
 * @param onOpenPastedText opens a paste's viewer (iOS `PastedTextCard` tap).
 * @param onOpenLocation opens the card's maps URL (iOS `openURL(location.mapsURL)`).
 */
@Composable
fun PendingAttachmentsRow(
    attachments: List<OpenCodeOutgoingAttachment>,
    files: List<ComposerFileAttachment>,
    canRetryFiles: Boolean,
    showsLocation: Boolean,
    location: SharedLocation?,
    isLocating: Boolean,
    onRemoveAttachment: (index: Int) -> Unit,
    onRemoveFile: (UUID) -> Unit,
    onRetryFile: (UUID) -> Unit,
    onRemoveLocation: () -> Unit,
    onOpenPastedText: (OpenCodeOutgoingAttachment) -> Unit,
    onOpenLocation: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** iOS `presentComposerPhoto(id)`: a tap opens the pending photo full screen. */
    onOpenPhoto: ((String) -> Unit)? = null,
    /** iOS `composerExpanded`: the composer pads its content at the top only when collapsed. */
    composerExpanded: Boolean = false,
    /** iOS `store.server`: restored `media:<hash>` photos load their bytes from it. */
    server: OpenCodeServer? = null,
    quotes: List<ComposerQuote> = emptyList(),
    onRemoveQuote: (String) -> Unit = {},
    commentingQuoteID: String? = null,
    onOpenQuote: (String) -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val openedQuoteID = commentingQuoteID
    // iOS `styled`: the tiles sit `attachmentInset` from the surface's top and leading edges and
    // stand as tall as the row's location or file.
    val inset = ComposerTunables.attachmentInset.dp
    val inner = ComposerTunables.innerHorizontal.dp
    val parentTop = if (composerExpanded) 0.dp else ComposerTunables.innerVertical.dp
    val radius = ComposerTunables.attachmentRadius.dp
    val locationHeight = SharedLocationCard.compactMapHeight + SharedLocationCard.compactFooterHeight()
    val quoteHeight = quoteCardHeight()
    // Quotes alone keep the row as short as their cards; beside tiles they sit at the tiles' top.
    val onlyQuotes = attachments.isEmpty() && files.isEmpty() && !showsLocation
    val rowHeightTarget = when {
        onlyQuotes -> quoteHeight
        else -> maxOf(PendingAttachmentMetrics.slotSide, ComposerFileChip.height, quoteHeight, if (showsLocation) locationHeight else 0.dp)
    }
    val rowHeight by animateDpAsState(
        rowHeightTarget,
        Motion.layout.animation(if (showsLocation) MotionPhase.Enter else MotionPhase.Exit, reduceMotion),
        label = "attachmentRowHeight",
    )
    val listState = rememberLazyListState()
    val viewingIndex = attachments.indexOfFirst { ComposerPhotoViewer.isShowing(it.id) }
    LaunchedEffect(viewingIndex) {
        if (viewingIndex >= 0) listState.reveal(viewingIndex)
    }
    val tileHeight = maxOf(rowHeight, PendingAttachmentMetrics.slotSide)
    val itemKeys = buildList {
        for (attachment in attachments) add("attachment-${attachment.id}")
        for (file in files) add("file-${file.id}")
        for (quote in quotes) add("quote-${quote.id}")
        if (showsLocation) add("location")
    }
    // Adding a card scrolls the row to its end, unless that would push the new card off the start.
    var knownKeys by remember { mutableStateOf(itemKeys.toSet()) }
    LaunchedEffect(itemKeys) {
        val added = itemKeys.lastOrNull { it !in knownKeys }
        val grew = itemKeys.size > knownKeys.size
        knownKeys = itemKeys.toSet()
        if (added != null && grew) {
            withFrameNanos { }
            listState.revealFully(itemKeys.last(), itemKeys.lastIndex)
            listState.revealFully(added, itemKeys.indexOf(added))
        }
    }
    // The quote being commented on stays in full view under its panel.
    LaunchedEffect(openedQuoteID) {
        val key = "quote-${openedQuoteID ?: return@LaunchedEffect}"
        val index = itemKeys.indexOf(key)
        if (index >= 0) listState.revealFully(key, index)
    }
    // A row scrolled to its end stays there as the composer narrows.
    LaunchedEffect(listState) {
        var width = 0
        var atEnd = true
        snapshotFlow { listState.layoutInfo.viewportSize.width to listState.canScrollForward }.collect { (newWidth, canScrollForward) ->
            if (newWidth < width && atEnd && canScrollForward) listState.scrollBy((width - newWidth).toFloat())
            width = newWidth
            atEnd = !listState.canScrollForward
        }
    }
    val departing = rememberDepartingAttachments()
    val placement: FiniteAnimationSpec<IntOffset> = AttachmentRemoval.animation(reduceMotion)
    fun LazyItemScope.itemMotion(key: String): Modifier =
        Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null).then(departing.retained(key))
    fun removing(key: String, remove: () -> Unit): () -> Unit = {
        departing.depart(key, reduceMotion)
        remove()
    }
    Box(modifier) {
    // The removed attachments' last frames play their exits under the row as it reflows.
    Spacer(Modifier.matchParentSize().drawBehind { departing.draw(this) })
    LazyRow(
        state = listState,
        // Room for the remove buttons, which sit over the top trailing corner.
        contentPadding = PaddingValues(top = (inset - parentTop).coerceAtLeast(0.dp), start = maxOf(inset, inner), end = maxOf(8.dp + inner, RemoveTargetReach)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier
            // Edge to edge in the card, fading where photos run under its edges.
            .outsetHorizontally(inner)
            .drawerPanHorizontalScroller({ listState.canScrollBackward }, { listState.canScrollForward })
            .horizontalScrollEdgeFade(listState)
            .onGloballyPositioned { departing.row = it },
    ) {
        itemsIndexed(attachments, key = { _, attachment -> "attachment-${attachment.id}" }) { index, attachment ->
            val key = "attachment-${attachment.id}"
            val item = itemMotion(key)
            val text = attachment.pastedText
            val summary = attachment.pasted
            when {
                attachment.isImage -> {
                    // Out of the composer while it's up full screen, like a transcript image.
                    val viewing = ComposerPhotoViewer.isShowing(attachment.id)
                    RemovableAttachment(
                        label = stringResource(R.string.session_attachment_remove_image_description, index + 1),
                        identifier = "remove-pending-attachment-$index",
                        reduceMotion = reduceMotion,
                        onRemove = removing(key) { onRemoveAttachment(index) },
                        viewing = viewing,
                        modifier = item,
                    ) {
                        AttachmentThumbnail(
                            attachment,
                            index,
                            Modifier
                                .mediaZoomSource(ComposerPhotoViewer.mediaID(attachment.id), radius, showsMedia = true)
                                .alpha(if (viewing) 0f else 1f),
                            // No press effect: the photo flies out of the tile exactly as drawn.
                            onOpen = onOpenPhoto?.let { open -> { open(attachment.id) } },
                            height = { tileHeight },
                            radius = radius,
                            server = server,
                        )
                    }
                }
                text != null && summary != null -> RemovableAttachment(
                    label = stringResource(R.string.session_attachment_remove_description, PastedText.title),
                    identifier = "remove-pasted-text-$index",
                    reduceMotion = reduceMotion,
                    onRemove = removing(key) { onRemoveAttachment(index) },
                    modifier = item,
                ) {
                    // iOS `pastedTextChip`: 220 × 76 on the secondary fill, room for the remove button.
                    PastedTextCard(
                        summary = summary,
                        onOpen = { onOpenPastedText(attachment) },
                        width = ComposerFileChip.width,
                        height = ComposerFileChip.height,
                        fill = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(ComposerFileChip.cornerRadius),
                        // iOS 14 pt, plus the × reaching further in here: its 48 dp target (iOS
                        // 44 pt) centres the 22 dp disc 2 dp further in, and the disc is ~1 dp wider.
                        headerTrailingInset = 17.dp,
                    )
                }
                else -> {
                    // A PDF the selected model reads natively: sent inline, no upload.
                    val fileName = attachment.name ?: "File"
                    val name = attachment.name ?: stringResource(R.string.session_attachment_file_fallback_name)
                    val fileDescription = stringResource(R.string.session_attachment_file_description, name)
                    RemovableAttachment(
                        label = stringResource(R.string.session_attachment_remove_description, name),
                        identifier = "remove-pending-file-$fileName",
                        reduceMotion = reduceMotion,
                        onRemove = removing(key) { onRemoveAttachment(index) },
                        modifier = item,
                    ) {
                        FileAttachmentCard(
                            name = name,
                            symbolName = AttachmentFileType.symbolName(fileName, attachment.mime),
                            width = ComposerFileChip.width,
                            height = ComposerFileChip.height,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(ComposerFileChip.cornerRadius))
                                .clearAndSetSemantics { contentDescription = fileDescription }
                                .testTag("pending-file-$name"),
                        ) {
                            Text(AttachmentFileType.subtitle(name, attachment.mime, attachment.byteCount ?: attachment.data.size.toLong()))
                        }
                    }
                }
            }
        }
        items(files, key = { "file-${it.id}" }) { file ->
            val key = "file-${file.id}"
            RemovableAttachment(
                label = if (file.isUploading) stringResource(R.string.session_attachment_cancel_upload_description, file.name) else stringResource(R.string.session_attachment_remove_description, file.name),
                identifier = "remove-pending-file-${file.name}",
                reduceMotion = reduceMotion,
                onRemove = removing(key) { onRemoveFile(file.id) },
                modifier = itemMotion(key),
            ) {
                ComposerFileChip(
                    file = file,
                    canRetry = canRetryFiles,
                    onRetry = { onRetryFile(file.id) },
                    cornerRadius = radius,
                    onOpen = onOpenPhoto?.takeUnless { file.isFailed }?.let { open -> { open(file.id.toString()) } },
                )
            }
        }
        items(quotes, key = { "quote-${it.id}" }) { quote ->
            val key = "quote-${quote.id}"
            RemovableAttachment(
                label = stringResource(R.string.session_quote_remove_description),
                identifier = "remove-quote-${quote.id}",
                reduceMotion = reduceMotion,
                onRemove = removing(key) { onRemoveQuote(quote.id) },
                modifier = itemMotion(key),
            ) {
                Box(Modifier.height(rowHeight), contentAlignment = Alignment.TopStart) {
                    ComposerQuoteCard(quote, radius = radius, onOpen = { onOpenQuote(quote.id) })
                }
            }
        }
        if (showsLocation) {
            item(key = "location") {
                ComposerLocationAttachment(
                    location = location,
                    isLocating = isLocating,
                    onRemove = onRemoveLocation,
                    onOpen = onOpenLocation,
                    modifier = Modifier.animateItem(fadeInSpec = null, placementSpec = placement, fadeOutSpec = null),
                )
            }
        }
    }
    }
}

/**
 * iOS `removableAttachment`'s removal transition. A removed attachment leaves the row at once (the
 * durable state changes immediately and its siblings reflow) while its last frame stays where it
 * sat and plays the exit: [AttachmentRemoval]'s blur, scale and fade in the New Plus Menu, and a
 * fade toward 0.72 scale over 0.2 s otherwise (nothing under Reduce Motion). A send's attachments
 * leave without it (iOS `.identity` while `images.isActive`): only a tapped × departs.
 */
@Stable
private class DepartingAttachments(private val graphics: GraphicsContext, private val scope: CoroutineScope, private val density: Density) {
    private class Ghost(val layer: GraphicsLayer, val topLeft: Offset, val exit: Animatable<Float, AnimationVector1D>, val pose: GraphicsLayer.(Float) -> Unit)

    private val layers = HashMap<String, GraphicsLayer>()
    private val positions = HashMap<String, Offset>()
    private val ghosts = mutableStateListOf<Ghost>()

    /** The row the attachments sit in; ghosts are drawn in its space. */
    var row: LayoutCoordinates? = null

    /** Keeps [key]'s attachment's last frame (and where it sat) for a removal. */
    fun retained(key: String): Modifier = Modifier
        .onGloballyPositioned { item -> row?.takeIf { it.isAttached }?.let { positions[key] = it.localPositionOf(item, Offset.Zero) } }
        .drawWithContent {
            val layer = layers.getOrPut(key) { graphics.createGraphicsLayer() }
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
        .then(Modifier.releasing(key))

    private fun Modifier.releasing(key: String): Modifier = this.then(ReleaseOnDetach { drop(key) })

    /** The × was tapped: [key]'s last frame plays the exit where it sat. */
    fun depart(key: String, reduceMotion: Boolean) {
        val layer = layers.remove(key) ?: return
        val position = positions.remove(key)
        if (position == null) {
            graphics.releaseGraphicsLayer(layer)
            return
        }
        val spec: FiniteAnimationSpec<Float> = AttachmentRemoval.exitAnimation(reduceMotion)
        val pose: GraphicsLayer.(Float) -> Unit = { exit -> attachmentRemovalExit(exit, reduceMotion, density) }
        val ghost = Ghost(layer, position, Animatable(0f), pose)
        ghosts += ghost
        scope.launch {
            try {
                ghost.exit.animateTo(1f, spec)
            } finally {
                ghosts.remove(ghost)
                graphics.releaseGraphicsLayer(layer)
            }
        }
    }

    fun draw(scope: DrawScope) {
        for (ghost in ghosts) {
            ghost.pose(ghost.layer, ghost.exit.value)
            scope.translate(ghost.topLeft.x, ghost.topLeft.y) { drawLayer(ghost.layer) }
        }
    }

    private fun drop(key: String) {
        layers.remove(key)?.let(graphics::releaseGraphicsLayer)
        positions.remove(key)
    }
}

@Composable
private fun rememberDepartingAttachments(): DepartingAttachments {
    val graphics = LocalGraphicsContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(graphics, scope, density) { DepartingAttachments(graphics, scope, density) }
}

/** Runs [onDetach] when the element leaves the layout (an item scrolled away or removed). */
private class ReleaseOnDetach(val onDetach: () -> Unit) : ModifierNodeElement<ReleaseOnDetach.Node>() {
    class Node(var release: () -> Unit) : Modifier.Node() {
        override fun onDetach() = release()
    }

    override fun create() = Node(onDetach)
    override fun update(node: Node) {
        node.release = onDetach
    }
    override fun equals(other: Any?) = other is ReleaseOnDetach
    override fun hashCode() = 0
}

/**
 * iOS `removableAttachment`: [content] with a remove button (×) over its top trailing corner,
 * entering scaled from 0.72 with a fade (a fade under reduce motion).
 */
@Composable
fun RemovableAttachment(
    label: String,
    identifier: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    reduceMotion: Boolean = rememberReduceMotion(),
    /** Full screen, the viewer's flight carries the × (`ComposerPhotoViewer.flightAccessories`), so this one stands aside. */
    viewing: Boolean = false,
    content: @Composable () -> Unit,
) {
    val entrance = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(viewing) {
        if (viewing) entrance.snapTo(1f)
        if (entrance.value < 1f) entrance.animateTo(1f, Motion.microPop.spec())
    }
    Box(
        modifier.graphicsLayer {
            val progress = entrance.value.coerceIn(0f, 1f)
            alpha = progress
            val scale = 0.72f + 0.28f * entrance.value
            scaleX = scale
            scaleY = scale
        },
    ) {
        content()
        if (!viewing) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = ComposerPhotoViewer.removeBadgeOffset.x, y = ComposerPhotoViewer.removeBadgeOffset.y)
                    .clickable(role = Role.Button, onClickLabel = label, onClick = onRemove)
                    .openCodeTapTarget()
                    .semantics { contentDescription = label }
                    .testTag(identifier),
                contentAlignment = Alignment.Center,
            ) {
                ComposerPhotoViewer.RemoveBadge()
            }
        }
    }
}

/**
 * iOS `attachmentThumbnail`: the image, 76 × 76 with 22 dp corners, decoded off the main thread,
 * with a video's “▶ 0:50”. [onOpen] opens it full screen (iOS `presentComposerPhoto`).
 */
@Composable
private fun AttachmentThumbnail(
    attachment: OpenCodeOutgoingAttachment,
    index: Int,
    modifier: Modifier = Modifier,
    onOpen: (() -> Unit)? = null,
    /** iOS `GrowingAttachmentImage(height:)`: New Plus Menu's photos grow to the row's height, aspect-filled. */
    height: () -> Dp = { PendingAttachmentMetrics.slotSide },
    radius: Dp = PendingAttachmentMetrics.slotRadius,
    /** iOS `GrowingAttachmentImage(server:)`: a restored `media:<hash>` photo with no bytes loads from here. */
    server: OpenCodeServer? = null,
) {
    val density = LocalDensity.current
    val openPhotoLabel = stringResource(R.string.session_attachment_open_photo_label)
    val imageDescription = stringResource(R.string.session_attachment_image_description, index + 1)
    val videoDescription = stringResource(R.string.session_attachment_video_description, index + 1)
    val target = with(density) { PendingAttachmentMetrics.slotSide.roundToPx() }
    val mediaHash = attachment.mediaHash
    val image by produceState<ImageBitmap?>(null, attachment.id, mediaHash, server?.persistenceID) {
        value = withContext(Dispatchers.Default) { decodeThumbnail(attachment.data, target) }
        if (value == null && mediaHash != null && server != null) {
            val reference = OpenCodeImageAttachment(id = attachment.id, data = attachment.data, mediaHash = mediaHash)
            val loaded = MediaReferenceResolver.loadImage(server, reference)
            if (loaded is MediaImageResult.Success) value = loaded.image.asImageBitmap()
        }
    }
    Box(
        modifier
            .width(PendingAttachmentMetrics.slotSide)
            // The height follows the row's spring in layout, without recomposing the tile.
            .layout { measurable, constraints ->
                val side = height().roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = side, maxHeight = side))
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(
                if (onOpen == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = null,
                        role = Role.Image,
                        onClickLabel = openPhotoLabel,
                        onClick = onOpen,
                    )
                },
            )
            .semantics {
                contentDescription = if (attachment.videoDuration == null) imageDescription else videoDescription
                attachment.videoDuration?.let { stateDescription = formatVideoDuration(it) }
            }
            .testTag("pending-attachment-$index"),
    ) {
        val bitmap = image
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        attachment.videoDuration?.let { duration ->
            VideoDurationBadge(formatVideoDuration(duration), modifier = Modifier.align(Alignment.BottomStart).padding(PlusMenuTunables.VIDEO_BADGE_INSET.dp))
        }
    }
}

/** iOS `.padding(.horizontal, -inner)`: lays the row out [inset] wider on each side than its slot. */
private fun Modifier.outsetHorizontally(inset: Dp): Modifier =
    if (inset == 0.dp) {
        this
    } else {
        layout { measurable, constraints ->
            val extra = inset.roundToPx()
            val wide = if (constraints.hasBoundedWidth) {
                constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra)
            } else {
                constraints
            }
            val placeable = measurable.measure(wide)
            val width = if (constraints.hasBoundedWidth) placeable.width - 2 * extra else placeable.width
            layout(width.coerceAtLeast(0), placeable.height) { placeable.place(-extra, 0) }
        }
    }

private fun decodeThumbnail(data: ByteArray, target: Int): ImageBitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
    BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
} catch (_: Exception) {
    null
}

// endregion

