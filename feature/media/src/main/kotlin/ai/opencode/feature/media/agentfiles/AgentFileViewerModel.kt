package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Loads one file's content and export bytes. The page owns its native player/PDF resources;
 * the fullscreen feature decides what to present, share or draw.
 */
class AgentFileViewerModel(
    val reference: AgentFileReference,
    val server: OpenCodeServer,
    val directory: String?,
    private val playbackAudio: MediaPlaybackAudio = dependency(MediaPlaybackAudio),
    private val context: Context = dependency(AppContext),
    private val fileChecks: AgentFileChecks = dependency(AgentFileChecks),
    /** Full-resolution pixels already on screen for this file: kept when the file is unchanged. */
    private val seed: Bitmap? = null,
    private val images: AgentImageCache = dependency(AgentImageCache),
    private val previews: AgentImagePreviews = dependency(AgentImagePreviews),
) {
    /** The file's one identity across the transcript and the viewer. */
    private val identity: String? = if (reference.kind == AgentFileReference.Kind.Image) AgentImageCache.key(server, reference, directory) else null

    /** Full-resolution pixels some reader already holds for this file: the page's, the decoded cache's, the preview's. */
    private fun resident(identity: String): Bitmap? = seed ?: images.image(identity) ?: if (reference.media == null) {
        target?.let { AgentImageCache.fullResolutionPreview(previews, AgentImageKey(server.persistenceID, it.absolutePath)) }
    } else null

    sealed interface Content {
        class Player(val player: ExoPlayer, val isAudio: Boolean, val isStreaming: Boolean) : Content

        /** Shown like a transcript image: zoomable. */
        class Image(val image: Bitmap) : Content

        /**
         * Android has none: a PDF renders its pages ([pdf]); any other document offers another app
         * (`ACTION_VIEW` through the FileProvider).
         */
        class QuickLook(val file: File, val pdf: AgentPdfDocument?) : Content

        class Text(val document: AgentTextDocument) : Content

        /**
         * Rendered like a reply, with the source a toggle away. The Markdown renderer lives in
         * `:feature:transcript` (above media), which renders [source] through the viewer's slot.
         */
        class Markdown(val source: String, val document: AgentTextDocument) : Content
    }

    sealed interface Phase {
        data object Opening : Phase
        data class Downloading(val received: Long, val expected: Long?) : Phase
        data class ConfirmLarge(val size: Long) : Phase

        /** [localFile] is the downloaded copy, for Share; null while streaming. */
        data class Ready(val content: Content, val localFile: File?) : Phase
        data class Failed(val error: AgentFileError) : Phase
    }

    /** A streamed file downloading for Share. */
    data class ShareProgress(val received: Long, val expected: Long?)

    private var current: Phase by mutableStateOf(Phase.Opening)

    /** Owns the PDF it shows: replacing it closes the one no longer shown. */
    var phase: Phase
        get() = current
        private set(value) {
            val old = ((current as? Phase.Ready)?.content as? Content.QuickLook)?.pdf
            current = value
            if (old != null && old !== ((value as? Phase.Ready)?.content as? Content.QuickLook)?.pdf) old.close()
        }

    var shareProgress: ShareProgress? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var streamLoader: StreamingFileLoader? = null
    private var probe: AgentFileProbe? = null
    private var work: Job? = null
    private var audioHolder: AudioHolder? = null
    /** A downloaded copy is on screen and the server hasn't answered for it yet. */
    private var copyUnchecked = false

    /** One activation; `holder` is set once activation succeeded off the main thread. */
    private class AudioHolder {
        @Volatile var holder: MediaPlaybackAudio.Holder? = null
        @Volatile var released = false
    }

    val target: AgentFileReference.Target? get() = reference.target(directory)

    /** The open file's own folder: a document's relative links and images resolve against it. */
    val fileDirectory: String? get() = target?.let { deletingLastPathComponent(it.absolutePath) }

    /** "opencode-ios/OpenCode", "~/Downloads", or "/tmp": where it lives. */
    val locationLabel: String?
        get() {
            val target = target ?: return null
            val parent = deletingLastPathComponent(target.absolutePath)
            // A Windows target's absolute path is normalized; compare it with the directory normalized the same way.
            val directory = AgentFileReference.comparableDirectory(this.directory)
            if (directory != null && (parent == directory || parent.startsWith("$directory/"))) {
                val project = lastPathComponent(directory)
                return if (parent == directory) project else project + parent.drop(directory.length)
            }
            val home = AgentFileReference.home(this.directory)
            if (home != null && (parent == home || parent.startsWith("$home/"))) return "~" + parent.drop(home.length)
            return parent
        }

    /**
     * Ready at once, before any frame, when some reader holds this file's pixels: the page's first
     * frame shows them rather than Opening. A file that isn't fresh is checked behind them ([open]).
     * Main thread.
     */
    fun openResident(): Boolean {
        val identity = identity ?: return false
        val image = resident(identity) ?: return false
        val destination = original?.let { AgentFileLoader.cacheURL(server, it.cacheKey, reference.name, context.cacheDir) }
        phase = Phase.Ready(Content.Image(image), destination?.takeIf { it.isFile })
        return true
    }

    fun open() {
        if (identity != null && (phase as? Phase.Ready)?.content is Content.Image && images.isFresh(identity)) return
        (phase as? Phase.Ready)?.let {
            if (it.localFile != null && !copyUnchecked && (identity == null || it.content !is Content.Image)) return
        }
        work?.cancel()
        work = scope.launch { run(confirmed = false) }
    }

    fun confirmDownload() {
        work?.cancel()
        work = scope.launch { run(confirmed = true) }
    }

    fun stop() {
        work?.cancel()
        ((phase as? Phase.Ready)?.content as? Content.Player)?.player?.pause()
        releaseAudio()
        streamLoader?.invalidate()
        streamLoader = null
    }

    /** The page is gone for good: frees the player and the PDF renderer. */
    fun release() {
        stop()
        when (val content = (phase as? Phase.Ready)?.content) {
            is Content.Player -> content.player.release()
            is Content.QuickLook -> content.pdf?.close()
            else -> Unit
        }
        scope.cancel()
    }

    private val original: AgentFileLoader.Original?
        get() = AgentFileLoader.original(server, reference, directory)

    private suspend fun run(confirmed: Boolean) {
        val original = original
        if (original == null) {
            phase = Phase.Failed(AgentFileError.Unreadable)
            return
        }
        // A copy already on screen while the server is asked: a check never blanks it.
        var shown: Phase.Ready? = null
        try {
            val identity = identity
            val destination = AgentFileLoader.cacheURL(server, original.cacheKey, reference.name, context.cacheDir)
            if (identity != null && !confirmed) {
                // A preview of the same file under way lands first: its bytes and its check are this read's.
                if (reference.media == null) target?.let { previews.awaitRead(AgentImageKey(server.persistenceID, it.absolutePath)) }
                val fresh = images.isFresh(identity)
                val resident = resident(identity)
                val image = resident ?: if (destination.isFile) images.decodeFile(identity, destination) else null
                if (image != null) {
                    shown = Phase.Ready(Content.Image(image), destination.takeIf { it.isFile })
                    phase = shown
                    if (fresh) return
                }
            }
            if (shown == null && !confirmed && !reference.kind.isTimeBased && reference.media == null && destination.isFile) {
                if ((phase as? Phase.Ready)?.localFile != destination) present(destination)
                shown = phase as? Phase.Ready
                copyUnchecked = shown != null
            }
            // A copy on screen is checked by the conditional GET alone.
            val size = if (shown != null) null else {
                if (!confirmed || probe == null) {
                    if (shown == null) phase = Phase.Opening
                    probe = AgentFileLoader.probe(original.request, server)
                }
                val probe = probe ?: return
                if (!scope.isActive) return
                if (reference.kind.isTimeBased && probe.supportsRanges) {
                    startStreaming(original.request, reference.media?.mime ?: probe.mime)
                    return
                }
                val size = downloadSize(probe)
                if (!confirmed && size != null && size > AgentFileLoader.confirmationBytes) {
                    if (shown == null) phase = Phase.ConfirmLarge(size)
                    return
                }
                size
            }
            if (shown == null) phase = Phase.Downloading(0, size)
            val limit = maxOf(if (confirmed) size ?: 0 else 0, if (shown != null) destination.length() else 0, AgentFileLoader.confirmationBytes)
            var status = 0
            // One download per destination at a time: a second reader finds the first's copy fresh.
            val file = images.exclusive(destination.path) {
                if (identity != null && destination.isFile && images.isFresh(identity)) {
                    status = 304
                    destination
                } else {
                    AgentFileLoader.download(original.request, destination, server, maxBytes = limit, onStatus = { status = it }) { received, expected ->
                        scope.launch { downloaded(received, expected) }
                    }
                }
            }
            copyUnchecked = false
            if (identity != null) {
                images.validated(identity)
                // Unchanged (304): the pixels already resident stay; changed (200): decoded once, for every reader.
                val resident = if (status == 304) (shown?.content as? Content.Image)?.image ?: resident(identity) else null
                val image = resident ?: images.decodeFile(identity, file, replacing = status != 304)
                phase = Phase.Ready(image?.let(Content::Image) ?: quickLook(file), file)
            } else if (shown == null || status != 304) {
                present(file)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AgentFileError) {
            if (error == AgentFileError.NotFound) target?.let { fileChecks.markMissing(server, it.absolutePath) }
            if (shown != null && error != AgentFileError.NotFound) return
            phase = Phase.Failed(error)
        } catch (error: Exception) {
            AppLog.network.notice { "Agent file: ${error::class.java.simpleName}" }
            if (shown != null) return
            phase = Phase.Failed(AgentFileError.network(error))
        }
    }

    /** The original for export. A ranged stream keeps playing while its bytes download. */
    suspend fun exportFile(): File? {
        val ready = phase as? Phase.Ready ?: return null
        ready.localFile?.let { return it }
        if (shareProgress != null) return null
        val original = original ?: return null
        if ((downloadSize(probe) ?: 0L) > AgentFileLoader.confirmationBytes) throw AgentFileLoader.overLimit
        shareProgress = ShareProgress(0, probe?.size)
        try {
            val destination = AgentFileLoader.cacheURL(server, original.cacheKey, reference.name, context.cacheDir)
            val file = AgentFileLoader.download(original.request, destination, server, maxBytes = AgentFileLoader.confirmationBytes) { received, expected ->
                scope.launch { shareDownloaded(received, expected) }
            }
            if (phase !== ready || !scope.isActive) return null
            phase = ready.copy(localFile = file)
            return file
        } finally {
            shareProgress = null
        }
    }

    private fun downloadSize(probe: AgentFileProbe?): Long? {
        val media = reference.media ?: return probe?.size
        return media.downloadSize(probe?.size) ?: throw AgentFileError.Network(localized(R.string.media_file_error_size_unknown))
    }

    private fun shareDownloaded(received: Long, expected: Long?) {
        if (shareProgress == null) return
        shareProgress = ShareProgress(received, expected ?: probe?.size)
    }

    private fun downloaded(received: Long, expected: Long?) {
        if (phase !is Phase.Downloading) return
        phase = Phase.Downloading(received, expected ?: probe?.size)
    }

    @OptIn(UnstableApi::class)
    private fun startStreaming(request: Request, mime: String?) {
        streamLoader?.invalidate()
        val loader = StreamingFileLoader(request, server, mime, reference.name)
        streamLoader = loader
        val player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(loader.dataSourceFactory))
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setMediaItem(loader.makeMediaItem())
        play(player, localFile = null)
    }

    private suspend fun present(file: File) {
        when (reference.kind) {
            AgentFileReference.Kind.Video, AgentFileReference.Kind.Audio -> {
                val player = ExoPlayer.Builder(context).setHandleAudioBecomingNoisy(true).build()
                player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
                play(player, localFile = file)
            }
            AgentFileReference.Kind.Text -> {
                val name = reference.name
                val line = reference.line
                val isMarkdown = AttachmentFileType.fileExtension(name) in AgentTextDocument.markdownExtensions && line == null
                val content = loadTextContent(file, name, line, isMarkdown)
                phase = Phase.Ready(content ?: quickLook(file), file)
            }
            AgentFileReference.Kind.Image -> {
                // Formats Android can't decode (SVG, say) still open as a document.
                val image = loadImage(file)
                phase = Phase.Ready(image?.let(Content::Image) ?: quickLook(file), file)
            }
            AgentFileReference.Kind.Pdf, AgentFileReference.Kind.Document -> phase = Phase.Ready(quickLook(file), file)
        }
    }

    private suspend fun quickLook(file: File): Content.QuickLook {
        val isPDF = reference.kind == AgentFileReference.Kind.Pdf || reference.mime == AttachmentFileType.pdfMime
        return Content.QuickLook(file, if (isPDF) AgentPdfDocument.open(file) else null)
    }

    /**
     * Shows the player at once and starts it once audio is active, activated off the main
     * thread. Plays even if activation fails.
     */
    private fun play(player: ExoPlayer, localFile: File?) {
        ((phase as? Phase.Ready)?.content as? Content.Player)?.player?.takeIf { it !== player }?.release()
        val audio = playbackAudio
        val holder = audioHolder ?: AudioHolder().also { audioHolder = it }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                AppLog.network.notice { "Agent file playback failed: ${error.errorCodeName}" }
                if (localFile == null && phase.let { it is Phase.Ready && it.content is Content.Player && it.content.player === player }) {
                    phase = Phase.Failed((cause as? StreamingFileLoader.StreamingFileException)?.error ?: AgentFileError.network(cause ?: error))
                }
            }
        })
        player.prepare()
        phase = Phase.Ready(Content.Player(player, reference.kind == AgentFileReference.Kind.Audio, localFile == null), localFile)
        audio.perform {
            val active = if (holder.holder != null) {
                true
            } else {
                runCatching {
                    audio.activate(audio.isShared()) { scope.launch { player.pause() } }
                }.onSuccess { holder.holder = it }.isSuccess
            }
            // Closed meanwhile: give it straight back.
            if (holder.released) holder.holder?.let { audio.deactivate(it); holder.holder = null }
            scope.launch { activated(holder, active, player) }
        }
    }

    private fun activated(holder: AudioHolder, active: Boolean, player: ExoPlayer) {
        // Closed meanwhile.
        if (audioHolder !== holder) return
        if (!active) audioHolder = null
        val current = ((phase as? Phase.Ready)?.content as? Content.Player)?.player
        if (current === player) player.play()
    }

    private fun releaseAudio() {
        val holder = audioHolder ?: return
        audioHolder = null
        holder.released = true
        val audio = playbackAudio
        audio.perform {
            holder.holder?.let(audio.deactivate)
            holder.holder = null
        }
    }

    companion object {
        /** The disk copy of [file] the viewer downloaded or a preview stored, if there is one. Off the main thread. */
        internal fun diskCopy(file: AgentFileDestination, server: OpenCodeServer, cacheDirectory: File = dependency(AppContext).cacheDir): File? {
            val original = AgentFileLoader.original(server, file.reference, file.directory) ?: return null
            return AgentFileLoader.cacheURL(server, original.cacheKey, file.reference.name, cacheDirectory).takeIf { it.isFile }
        }

        internal suspend fun loadImage(file: File): Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    // Android won't draw a bitmap over ~100 MB; keep the original otherwise.
                    val pixels = info.size.width.toLong() * info.size.height
                    var sample = 1
                    while (pixels / (sample.toLong() * sample) > MAX_IMAGE_PIXELS) sample *= 2
                    if (sample > 1) decoder.setTargetSampleSize(sample)
                }
            }.getOrNull()
        }

        private const val MAX_IMAGE_PIXELS = 24L * 1024 * 1024

        suspend fun loadTextContent(file: File, fileName: String, focusLine: Int?, isMarkdown: Boolean): Content? =
            withContext(Dispatchers.Default) {
                val source = AgentFileLoader.readText(file) ?: return@withContext null
                val document = AgentTextDocument.of(source, fileName, focusLine)
                val renderMarkdown = isMarkdown && !document.usesLazyTextView
                if (renderMarkdown) Content.Markdown(source, document) else Content.Text(document)
            }

        /** NSString `deletingLastPathComponent`. */
        internal fun deletingLastPathComponent(path: String): String {
            val trimmed = if (path.length > 1) path.trimEnd('/') else path
            val slash = trimmed.lastIndexOf('/')
            return when {
                slash < 0 -> ""
                slash == 0 -> "/"
                else -> trimmed.substring(0, slash)
            }
        }

        /** NSString `lastPathComponent`. */
        internal fun lastPathComponent(path: String): String {
            val trimmed = if (path.length > 1) path.trimEnd('/') else path
            return if (trimmed == "/") "/" else trimmed.substringAfterLast('/')
        }
    }
}

/**
 * A PDF's pages for the viewer (Android's stand-in for Quick Look's PDF preview). One page
 * renders at a time; [close] when the page goes.
 */
class AgentPdfDocument private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    /** Each page's width over height. */
    val pageAspects: List<Float>,
) {
    private val mutex = Mutex()
    private var closed = false

    val pageCount: Int get() = pageAspects.size

    /** Page [index] [width] pixels wide, white behind it, or null once closed. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || width <= 0) return@withLock null
            renderer.openPage(index).use { page ->
                val height = maxOf(1, (width / pageAspects[index]).toInt())
                val bitmap = createBitmap(width, height)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    fun close() {
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock {
                if (closed) return@withLock
                closed = true
                renderer.close()
                descriptor.close()
            }
        }
    }

    companion object {
        /** Null when it isn't a PDF this device can render. */
        suspend fun open(file: File): AgentPdfDocument? {
            var document: AgentPdfDocument? = null
            try {
                return withContext(Dispatchers.IO) { openPages(file).also { document = it } }
            } catch (error: CancellationException) {
                document?.close()
                throw error
            }
        }

        private fun openPages(file: File): AgentPdfDocument? =
            runCatching {
                val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    val renderer = PdfRenderer(descriptor)
                    val aspects = (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page -> page.width.toFloat() / maxOf(page.height, 1) }
                    }
                    AgentPdfDocument(descriptor, renderer, aspects)
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            }.getOrNull()
    }
}

/**
 * Hands a downloaded file to the share sheet, or to another app for a document Android can't
 * preview, through this module's FileProvider.
 */
class AgentFileShareClient(
    val share: (file: File, mime: String) -> Boolean,
    val view: (file: File, mime: String) -> Boolean,
) {
    companion object Key : DependencyKey<AgentFileShareClient> {
        override val liveValue: AgentFileShareClient by lazy {
            val context = dependency(AppContext)
            fun uri(file: File): Uri = FileProvider.getUriForFile(context, AgentFileProvider.authority(context), file)
            fun start(intent: Intent): Boolean = try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (_: ActivityNotFoundException) {
                AppLog.app.notice { "Agent file: no activity for ${intent.action}" }
                false
            } catch (_: IllegalArgumentException) {
                AppLog.app.notice { "Agent file: outside the shared folder" }
                false
            }
            AgentFileShareClient(
                share = { file, mime ->
                    runCatching { uri(file) }.getOrNull()?.let { uri ->
                        val send = Intent(Intent.ACTION_SEND)
                            .setType(mime)
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        send.clipData = ClipData.newRawUri(file.name, uri)
                        start(Intent.createChooser(send, null))
                    } ?: false
                },
                view = { file, mime ->
                    runCatching { uri(file) }.getOrNull()?.let { uri ->
                        start(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } ?: false
                },
            )
        }

        override val testValue: AgentFileShareClient get() = AgentFileShareClient(share = { _, _ -> false }, view = { _, _ -> false })
    }
}

/** Shares the file viewer's downloads (`<cache>/AgentFiles/`) and nothing else. */
class AgentFileProvider : FileProvider(R.xml.agent_file_paths) {
    companion object {
        fun authority(context: Context): String = "${context.packageName}.agentfiles"
    }
}
