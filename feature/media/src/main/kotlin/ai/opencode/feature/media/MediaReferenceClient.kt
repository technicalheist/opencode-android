package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.live.MediaReferenceFetch
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeMediaReference
import ai.opencode.server.model.OpenCodeTranscriptFile
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import okhttp3.Request
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a `media:<hash>` load gave. */
sealed interface MediaReferenceLoadResult {
    class Loaded(val data: ByteArray) : MediaReferenceLoadResult {
        override fun equals(other: Any?): Boolean = other is Loaded && data.contentEquals(other.data)
        override fun hashCode(): Int = data.contentHashCode()
        override fun toString(): String = "Loaded(${data.size} bytes)"
    }

    /** The server answered 404: the blob is gone from it. */
    data object Unavailable : MediaReferenceLoadResult

    data class Failed(val message: String) : MediaReferenceLoadResult
}

/**
 * Content-addressed media blobs (`media:<sha256>`) by hash: bytes are the same on every server, so
 * memory and disk are shared; a 404 is remembered per server, in memory only. The test value loads
 * nothing and answers [MediaReferenceLoadResult.Unavailable].
 */
class MediaReferenceClient(
    /** Memory only, without waiting, so a redrawn row shows its image at once. */
    val cachedData: (hash: String) -> ByteArray? = { null },
    private val unavailable: (server: OpenCodeServer?, hash: String) -> Boolean = { _, _ -> false },
    private val loader: suspend (server: OpenCodeServer, hash: String) -> MediaReferenceLoadResult = { _, _ ->
        MediaReferenceLoadResult.Unavailable
    },
) {
    constructor(loader: MediaReferenceLoader) : this(
        cachedData = loader::cachedData,
        unavailable = loader::isUnavailable,
        loader = { server, hash -> loader.load(server, hash) },
    )

    fun isUnavailable(server: OpenCodeServer? = null, hash: String): Boolean = unavailable(server, hash)

    suspend fun load(server: OpenCodeServer, hash: String): MediaReferenceLoadResult = loader(server, hash)

    companion object Key : DependencyKey<MediaReferenceClient> {
        override val liveValue: MediaReferenceClient by lazy { MediaReferenceClient(MediaReferenceLoader.shared) }
        override val testValue: MediaReferenceClient get() = MediaReferenceClient()

        fun live(
            directory: File? = null,
            fetch: MediaReferenceLoader.Fetch = MediaReferenceLoader.Fetch(MediaReferenceFetch::live),
            memoryWarnings: MemoryWarningCenter = MemoryWarningCenter.default,
        ): MediaReferenceClient = MediaReferenceClient(MediaReferenceLoader(directory, fetch, memoryWarnings))
    }
}

/**
 * Where memory warnings arrive. [default] is Android's memory trim (`onTrimMemory` ≥ RUNNING_LOW /
 * `onLowMemory`); tests make their own so another test's warning never purges theirs.
 */
class MemoryWarningCenter {
    private val observers = CopyOnWriteArrayList<() -> Unit>()

    /** Returns a call that stops [observer]. */
    fun observe(observer: () -> Unit): () -> Unit {
        observers += observer
        return { observers -= observer }
    }

    fun post() = observers.forEach { it() }

    companion object {
        val default: MemoryWarningCenter by lazy {
            MemoryWarningCenter().also { center ->
                val context = runCatching { dependency(AppContext) }.getOrNull() ?: return@also
                context.registerComponentCallbacks(object : ComponentCallbacks2 {
                    @Suppress("DEPRECATION")
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) center.post()
                    }
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    @Deprecated("Deprecated in Java")
                    override fun onLowMemory() = center.post()
                })
            }
        }
    }
}

/**
 * Memory (LRU, [MAX_MEMORY_BYTES] / [MAX_MEMORY_ENTRIES]) and disk (`<hash>.blob`, [MAX_DISK_BYTES])
 * tiers over one coalesced fetch per server and hash. A memory warning drops the memory tier; the
 * disk tier and the per-server 404s stay.
 */
class MediaReferenceLoader(
    private val directory: File?,
    private val fetch: Fetch = Fetch(MediaReferenceFetch::live),
    memoryWarnings: MemoryWarningCenter = MemoryWarningCenter.default,
    private val maxDiskBytes: Long = MAX_DISK_BYTES,
) : CoalescedLoaderActor<MediaReferenceLoadResult>() {
    /** Throws [OpenCodeClientError]. */
    fun interface Fetch {
        suspend operator fun invoke(server: OpenCodeServer, hash: String): MediaReferenceFetch.Response
    }

    private data class UnavailableKey(val serverID: String, val hash: String)

    private val lock = Any()
    private val entries = HashMap<String, ByteArray>()
    private val order = ArrayList<String>()
    private var totalBytes = 0
    private val unavailable = HashSet<UnavailableKey>()
    private val diskLock = Any()
    /** Bytes on disk, counted once from metadata, then kept by writes. */
    private var diskBytes: Long? = null

    init {
        memoryWarnings.observe(::purgeMemory)
    }

    fun purgeMemory() = synchronized(lock) {
        entries.clear()
        order.clear()
        totalBytes = 0
    }

    fun cachedData(rawHash: String): ByteArray? {
        val hash = rawHash.lowercase()
        if (!OpenCodeMediaReference.isValidHash(hash)) return null
        return synchronized(lock) {
            val data = entries[hash] ?: return null
            order.remove(hash)
            order.add(hash)
            data
        }
    }

    fun isUnavailable(server: OpenCodeServer?, rawHash: String): Boolean {
        server ?: return false
        val hash = rawHash.lowercase()
        val key = UnavailableKey(server.persistenceID, hash)
        return synchronized(lock) { entries[hash] == null && key in unavailable }
    }

    suspend fun load(server: OpenCodeServer, rawHash: String): MediaReferenceLoadResult {
        val hash = rawHash.lowercase()
        val prefix = shortHash(hash)
        if (!OpenCodeMediaReference.isValidHash(hash)) {
            AppLog.network.notice { "Media ref $prefix: invalid hash" }
            return MediaReferenceLoadResult.Unavailable
        }
        val unavailableKey = UnavailableKey(server.persistenceID, hash)
        val memory = synchronized(lock) {
            entries[hash]?.let { data ->
                order.remove(hash)
                order.add(hash)
                MediaReferenceLoadResult.Loaded(data)
            } ?: MediaReferenceLoadResult.Unavailable.takeIf { unavailableKey in unavailable }
        }
        when (memory) {
            is MediaReferenceLoadResult.Loaded -> {
                AppLog.network.info { "Media ref $prefix: cache hit (memory, ${memory.data.size} bytes, status 200)" }
                return memory
            }
            MediaReferenceLoadResult.Unavailable -> {
                AppLog.network.info { "Media ref $prefix: cache hit (memory unavailable, 0 bytes, status 404)" }
                return memory
            }
            else -> Unit
        }
        return coalesce("${server.persistenceID}|$hash") { resolve(server, hash) }
    }

    private suspend fun resolve(server: OpenCodeServer, hash: String): MediaReferenceLoadResult {
        val prefix = shortHash(hash)
        val unavailableKey = UnavailableKey(server.persistenceID, hash)
        synchronized(lock) {
            entries[hash]?.let { MediaReferenceLoadResult.Loaded(it) }
                ?: MediaReferenceLoadResult.Unavailable.takeIf { unavailableKey in unavailable }
        }?.let { return it }
        readDisk(hash)?.let { data ->
            storeMemory(hash, data)
            AppLog.network.info { "Media ref $prefix: cache hit (disk, ${data.size} bytes, status 200)" }
            return MediaReferenceLoadResult.Loaded(data)
        }
        val response = try {
            fetch(server, hash)
        } catch (error: CancellationException) {
            throw error
        } catch (thrown: Exception) {
            val error = thrown as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            if (error.statusCode == 404) {
                markUnavailable(server.persistenceID, hash)
                AppLog.network.notice { "Media ref $prefix: HTTP 404 (0 bytes, cache miss)" }
                return MediaReferenceLoadResult.Unavailable
            }
            val statusSummary = error.statusCode?.let { "HTTP $it" } ?: logDescription(error)
            AppLog.network.notice { "Media ref $prefix: failed ($statusSummary, 0 bytes, cache miss)" }
            return MediaReferenceLoadResult.Failed(error.message)
        }
        if (response.data.size > MAX_BLOB_BYTES) {
            AppLog.network.notice {
                "Media ref $prefix: HTTP ${response.statusCode} (${response.data.size} bytes exceeds limit, cache miss)"
            }
            return MediaReferenceLoadResult.Failed("Couldn’t load media")
        }
        storeMemory(hash, response.data)
        writeDisk(hash, response.data)
        AppLog.network.info { "Media ref $prefix: HTTP ${response.statusCode} (${response.data.size} bytes, cache miss)" }
        return MediaReferenceLoadResult.Loaded(response.data)
    }

    private fun storeMemory(hash: String, data: ByteArray) {
        val bytes = data.size
        synchronized(lock) {
            entries.remove(hash)?.let { totalBytes -= it.size }
            order.remove(hash)
            unavailable.removeAll { it.hash == hash }
            if (bytes > MAX_MEMORY_BYTES) return
            while (order.isNotEmpty() && (totalBytes > MAX_MEMORY_BYTES - bytes || order.size >= MAX_MEMORY_ENTRIES)) {
                val oldest = order.removeAt(0)
                entries.remove(oldest)?.let { totalBytes -= it.size }
            }
            entries[hash] = data
            order.add(hash)
            totalBytes += bytes
        }
    }

    private fun markUnavailable(serverID: String, hash: String) = synchronized(lock) {
        unavailable += UnavailableKey(serverID, hash)
    }

    private fun readDisk(hash: String): ByteArray? {
        val directory = directory ?: return null
        val file = File(directory, "$hash.blob")
        if (!file.isFile || file.length() > MAX_BLOB_BYTES) return null
        val data = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.size <= MAX_BLOB_BYTES } ?: return null
        // A read is a use: the trim goes by modification date.
        runCatching { file.setLastModified(System.currentTimeMillis()) }
        return data
    }

    private fun writeDisk(hash: String, data: ByteArray) {
        val directory = directory ?: return
        try {
            directory.mkdirs()
            // Atomic: a reader never sees a half-written blob.
            val partial = File.createTempFile("$hash.", ".partial", directory)
            try {
                partial.writeBytes(data)
                synchronized(diskLock) {
                    val blob = File(directory, "$hash.blob")
                    val replaced = blob.length()
                    if (!partial.renameTo(blob)) throw java.io.IOException("rename failed")
                    val counted = diskBytes?.let { it - replaced + data.size }
                    diskBytes = if (counted != null && counted <= maxDiskBytes) {
                        counted
                    } else {
                        trim(directory, maxDiskBytes, maxDiskBytes / 4 * 3, keeping = hash)
                    }
                }
            } finally {
                partial.delete()
            }
        } catch (error: Exception) {
            AppLog.network.error { "Media ref ${shortHash(hash)}: couldn’t write disk cache (${error::class.java.simpleName})" }
        }
    }

    companion object {
        const val MAX_MEMORY_BYTES = 48 * 1024 * 1024
        const val MAX_MEMORY_ENTRIES = 64
        const val MAX_BLOB_BYTES = 25 * 1024 * 1024
        /**
         * The disk tier's budget. Past it, least recently used blobs go until a quarter of it is free.
         * Every blob here came from a server and can be fetched again.
         */
        const val MAX_DISK_BYTES = 256L * 1024 * 1024

        internal val shared: MediaReferenceLoader by lazy {
            MediaReferenceLoader(LinkPreviewLoader.cachesDirectory("media-references"))
        }

        fun shortHash(hash: String): String = MediaReferenceFetch.shortHash(hash)

        private class DiskBlob(val file: File, val bytes: Long, val used: Long)

        /**
         * Counts the blobs from file metadata and, when they hold more than [budget] bytes, deletes the
         * least recently used ones until at most [limit] remain (never [keeping], just written).
         * Returns the bytes left.
         */
        internal fun trim(directory: File, budget: Long, limit: Long, keeping: String): Long {
            val blobs = ArrayList<DiskBlob>()
            var total = 0L
            for (file in directory.listFiles() ?: return 0) {
                if (!file.name.endsWith(".blob")) continue
                val blob = DiskBlob(file, file.length(), file.lastModified())
                blobs.add(blob)
                total += blob.bytes
            }
            if (total <= budget) return total
            blobs.sortBy { it.used }
            for (blob in blobs) {
                if (total <= limit) break
                if (blob.file.name == "$keeping.blob") continue
                if (blob.file.delete()) total -= blob.bytes else AppLog.network.error { "Media refs: couldn’t trim a blob" }
            }
            AppLog.network.info { "Media refs: trimmed disk cache to $total bytes" }
            return total
        }

        /** The error's kind, never its message. */
        private fun logDescription(error: OpenCodeClientError): String =
            error.transport?.let { "OpenCodeClientError ${it.rawValue}" } ?: "OpenCodeClientError"
    }
}

/** Why an attachment shows no image. */
enum class MediaAttachmentFailure { Unavailable, Failed }

/**
 * A tile's load state: a failed load offers Retry, which bumps [attempt] so the load runs again.
 */
data class MediaAttachmentRetryState(val failure: MediaAttachmentFailure? = null, val attempt: Int = 0) {
    val isTransientFailure: Boolean get() = failure == MediaAttachmentFailure.Failed

    fun beginLoad(): MediaAttachmentRetryState = copy(failure = null)

    fun retry(): MediaAttachmentRetryState = copy(failure = null, attempt = attempt + 1)
}

/**
 * An attachment's image, decoded once and cached, filling its bounds and cropping. A `media:<hash>`
 * reference with no bytes in memory loads from [server] behind a spinner; one the server no longer
 * has shows [MediaAttachmentUnavailableView] (with Retry after a transient failure). Lives here,
 * beside [CachedImage.decoded], so the composer's queued messages and the session share it.
 */
@Composable
fun CachedImage(
    id: String,
    data: ByteArray,
    modifier: Modifier = Modifier,
    mediaHash: String? = null,
    name: String? = null,
    isUnavailable: Boolean = false,
    server: OpenCodeServer? = null,
    compact: Boolean = false,
) {
    var loadedImage by remember(id, mediaHash) { mutableStateOf<Bitmap?>(null) }
    var retryState by remember(id, mediaHash) { mutableStateOf(MediaAttachmentRetryState()) }
    val attachment = remember(id, data, mediaHash, name, isUnavailable) {
        OpenCodeImageAttachment(id = id, data = data, mediaHash = mediaHash, name = name, isUnavailable = isUnavailable)
    }
    val resolvedUnavailable = isUnavailable ||
        retryState.failure == MediaAttachmentFailure.Unavailable ||
        MediaReferenceResolver.isUnavailable(attachment, server)
    val decoded = loadedImage ?: remember(id, data, mediaHash) { CachedImage.cachedThumbnail(id, mediaHash) }
    val hasLocalData = data.isNotEmpty() || MediaReferenceResolver.cachedData(mediaHash) != null
    when {
        resolvedUnavailable -> MediaAttachmentUnavailableView(name, modifier, compact = compact)
        decoded != null -> Image(decoded.asImageBitmap(), contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
        retryState.isTransientFailure -> MediaAttachmentUnavailableView(
            name,
            modifier,
            compact = compact,
            onRetry = { retryState = retryState.retry() },
            isTransientFailure = true,
        )
        hasLocalData || (mediaHash != null && server != null) -> {
            Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                if (!hasLocalData) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OpenCodeTheme.colors.secondaryText)
                }
            }
            LaunchedEffect("${server?.persistenceID}|${mediaHash ?: id}#${retryState.attempt}") {
                retryState = retryState.beginLoad()
                when (val result = withContext(Dispatchers.Default) { MediaReferenceResolver.loadImage(server, attachment) }) {
                    is MediaImageResult.Success -> loadedImage = result.image
                    is MediaImageResult.Failure -> retryState = retryState.copy(failure = result.failure)
                }
            }
        }
    }
}

/**
 * An attachment's decoded original, from the shared [DecodedImageCache]. A reference with no inline
 * bytes decodes the blob already in memory. Moved down from the session because the media resolver
 * needs it.
 */
object CachedImage {
    fun decoded(id: String, data: ByteArray, mediaHash: String? = null): Bitmap? {
        val cache = dependency(DecodedImageCache)
        val key = key(id, mediaHash)
        cache.image(key)?.let { return it }
        val resolved = if (data.isEmpty()) MediaReferenceResolver.cachedData(mediaHash) else data
        if (resolved == null || resolved.isEmpty()) return null
        val image = BitmapFactory.decodeByteArray(resolved, 0, resolved.size) ?: return null
        cache.insert(image, key)
        return image
    }

    /**
     * [decoded] at the size rows and tiles show: at most 1600 px on the long edge, unless that
     * leaves the short edge under 800 px, so a square crop stays sharp. A smaller image is its original.
     */
    fun thumbnail(id: String, data: ByteArray, mediaHash: String? = null): Bitmap? {
        cachedThumbnail(id, mediaHash)?.let { return it }
        val resolved = if (data.isEmpty()) MediaReferenceResolver.cachedData(mediaHash) else data
        if (resolved == null || resolved.isEmpty()) return null
        val size = pixelSize(resolved) ?: return null
        val long = max(size.width, size.height)
        val target = thumbnailLongEdge(size.width, size.height)
        if (target >= long) return decoded(id, resolved, mediaHash)
        var sample = 1
        while (long / (sample * 2) >= target) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val sampled = BitmapFactory.decodeByteArray(resolved, 0, resolved.size, options) ?: return null
        val scale = target / max(sampled.width, sampled.height)
        val image = if (scale >= 1) sampled else {
            Bitmap.createScaledBitmap(sampled, max(1, (sampled.width * scale).roundToInt()), max(1, (sampled.height * scale).roundToInt()), true)
        }
        if (image !== sampled) sampled.recycle()
        dependency(DecodedImageCache).insert(image, "thumb:" + key(id, mediaHash))
        return image
    }

    private fun thumbnailLongEdge(width: Double, height: Double): Double {
        val long = max(width, height)
        return min(long, max(1600.0, ceil(800 * long / min(width, height))))
    }

    /** The encoded image's pixel size, read from its header alone. */
    fun pixelSize(data: ByteArray): AgentImageSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return AgentImageSize(bounds.outWidth.toDouble(), bounds.outHeight.toDouble())
    }

    /** [decoded]'s cache hit alone, without decoding. */
    fun cached(id: String, mediaHash: String? = null): Bitmap? =
        dependency(DecodedImageCache).image(key(id, mediaHash))

    /**
     * [thumbnail]'s cache hit alone, without decoding: what a tile shows on its first frame while it
     * decodes off the main thread.
     */
    fun cachedThumbnail(id: String, mediaHash: String? = null): Bitmap? {
        dependency(DecodedImageCache).image("thumb:" + key(id, mediaHash))?.let { return it }
        val original = cached(id, mediaHash) ?: return null
        val width = original.width.toDouble()
        val height = original.height.toDouble()
        return original.takeIf { thumbnailLongEdge(width, height) >= max(width, height) }
    }

    private fun key(id: String, mediaHash: String?): String = mediaHash?.let { "media:$it" } ?: "attachment:$id"
}

/** Reads and loads `media:<hash>` blobs for views without each holding the client. */
object MediaReferenceResolver {
    /** Authenticated original for a range probe, playback, or export; never a workspace read. */
    fun originalRequest(server: OpenCodeServer, hash: String): Request? {
        if (!OpenCodeMediaReference.isValidHash(hash)) return null
        val url = server.endpoint("/api/experimental/media/$hash") ?: return null
        return server.authorize(Request.Builder().url(url).header("Accept", "*/*")).build()
    }

    fun cachedData(hash: String?): ByteArray? {
        hash ?: return null
        return dependency(MediaReferenceClient).cachedData(hash)
    }

    fun isUnavailable(attachment: OpenCodeImageAttachment, server: OpenCodeServer? = null): Boolean {
        if (attachment.isUnavailable) return true
        val hash = attachment.mediaHash ?: return false
        return dependency(MediaReferenceClient).isUnavailable(server, hash)
    }

    fun isUnavailable(file: OpenCodeTranscriptFile, server: OpenCodeServer? = null): Boolean {
        if (file.isUnavailable) return true
        val hash = file.mediaHash ?: return false
        return dependency(MediaReferenceClient).isUnavailable(server, hash)
    }

    fun resolvedData(attachment: OpenCodeImageAttachment): ByteArray? =
        attachment.data.takeIf { it.isNotEmpty() } ?: cachedData(attachment.mediaHash)

    fun thumbnail(attachment: OpenCodeImageAttachment): Bitmap? =
        CachedImage.thumbnail(attachment.id, attachment.data, attachment.mediaHash)

    suspend fun loadData(server: OpenCodeServer, hash: String): MediaReferenceLoadResult =
        dependency(MediaReferenceClient).load(server, hash)

    /** The attachment's image, or why there is none. */
    suspend fun loadImage(server: OpenCodeServer?, attachment: OpenCodeImageAttachment): MediaImageResult {
        if (attachment.isUnavailable) return MediaImageResult.Failure(MediaAttachmentFailure.Unavailable)
        thumbnail(attachment)?.let { return MediaImageResult.Success(it) }
        val hash = attachment.mediaHash
        if (server == null || hash == null) return MediaImageResult.Failure(MediaAttachmentFailure.Unavailable)
        return when (val result = dependency(MediaReferenceClient).load(server, hash)) {
            is MediaReferenceLoadResult.Loaded ->
                CachedImage.thumbnail(attachment.id, result.data, hash)?.let { MediaImageResult.Success(it) }
                    ?: MediaImageResult.Failure(MediaAttachmentFailure.Failed)
            MediaReferenceLoadResult.Unavailable -> MediaImageResult.Failure(MediaAttachmentFailure.Unavailable)
            is MediaReferenceLoadResult.Failed -> MediaImageResult.Failure(MediaAttachmentFailure.Failed)
        }
    }
}

/** [MediaReferenceResolver.loadImage]'s answer. */
sealed interface MediaImageResult {
    data class Success(val image: Bitmap) : MediaImageResult
    data class Failure(val failure: MediaAttachmentFailure) : MediaImageResult
}

/**
 * What a tile shows instead of an image the server no longer has, or couldn't send. A transient
 * failure with [onRetry] is a button that loads again; a [compact] tile is the glyph alone.
 */
@Composable
fun MediaAttachmentUnavailableView(
    name: String?,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onRetry: (() -> Unit)? = null,
    isTransientFailure: Boolean = false,
) {
    val displayTitle = name?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.media_image_title)
    val subtitle = if (isTransientFailure) stringResource(R.string.media_image_load_failed) else stringResource(R.string.media_unavailable)
    val tryAgainLabel = stringResource(R.string.media_try_again_label)
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    val secondary = OpenCodeTheme.colors.secondaryText
    val retry = onRetry?.takeIf { isTransientFailure }
    val semantics = Modifier.clearAndSetSemantics {
        contentDescription = displayTitle
        stateDescription = subtitle
        testTag = "media-unavailable"
        if (retry != null) {
            role = Role.Button
            onClick(label = tryAgainLabel) { retry(); true }
        }
    }
    if (compact) {
        Box(modifier.background(fill).then(semantics), contentAlignment = Alignment.Center) {
            OpenCodeIcon(SFSymbol.PhotoBadgeExclamationmark, null, size = OpenCodeIconSize.small, tint = secondary)
        }
        return
    }
    Column(
        modifier
            .fillMaxSize()
            .background(fill)
            .then(if (retry != null) Modifier.clickable(onClick = retry) else Modifier)
            .then(semantics)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OpenCodeIcon(
            if (isTransientFailure) SFSymbol.ArrowClockwise else SFSymbol.PhotoBadgeExclamationmark,
            null,
            size = OpenCodeIconSize.medium,
            tint = secondary,
        )
        Text(
            displayTitle,
            style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.Medium),
            color = OpenCodeTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Text(subtitle, style = OpenCodeTextStyles.caption2, color = secondary, maxLines = 1)
    }
}
