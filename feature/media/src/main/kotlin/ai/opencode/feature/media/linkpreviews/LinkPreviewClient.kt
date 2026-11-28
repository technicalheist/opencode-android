package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.retry.NetworkPathMonitor
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** First-party metadata for recognised links, and domain-only site icons. */
class LinkPreviewClient(
    /** A fresh result already in memory, without waiting, so a reopened session draws its cards at once. */
    val cached: (LinkPreviewTarget) -> LinkPreviewResult? = { null },
    /**
     * Last known rich metadata, even after its fresh TTL has expired, so an expired card keeps
     * its rich view while revalidating. Memory only: a cold miss is null rather than a disk read.
     */
    val stale: (LinkPreviewTarget) -> LinkPreviewMetadata? = { null },
    val load: suspend (LinkPreviewTarget) -> LinkPreviewResult = { LinkPreviewResult.Unavailable },
    /** The same for a registrable domain's icon. */
    val cachedIcon: (String) -> SiteIconResult? = { null },
    val loadIcon: suspend (String) -> SiteIconResult = { SiteIconResult.Missing },
) {
    constructor(loader: LinkPreviewLoader, icons: SiteIconLoader) : this(
        cached = loader::cached,
        stale = loader::stale,
        load = { loader.load(it) },
        cachedIcon = icons::cached,
        loadIcon = { icons.load(it) },
    )

    companion object Key : DependencyKey<LinkPreviewClient> {
        override val liveValue: LinkPreviewClient by lazy {
            if (LinkPreviewLab.isEnabled) return@lazy LinkPreviewLab.client
            if (LabLaunch.serverURL != null) {
                // Other lab runs never ask the icon service.
                return@lazy LinkPreviewClient(
                    LinkPreviewLoader.shared,
                    SiteIconLoader(fetch = { throw IOException("not connected to internet") }, directory = null),
                )
            }
            LinkPreviewClient(LinkPreviewLoader.shared, SiteIconLoader.shared)
        }

        override val testValue: LinkPreviewClient get() = LinkPreviewClient()
    }
}

interface ExpiringCacheEntry {
    /** Epoch milliseconds. */
    val expires: Long
    val retentionDate: Long get() = expires
}

/**
 * Memory (locked map) and SHA-256 JSON disk cache shared by [LinkPreviewLoader] and
 * [SiteIconLoader]. Memory keeps at most [memoryLimit] entries, the soonest to expire going first
 * (never the one just remembered), and a memory warning empties it; every entry stays on disk.
 */
class CoalescedDiskCache<MemoryEntry : ExpiringCacheEntry, DiskRecord : ExpiringCacheEntry>(
    val directory: File?,
    private val serializer: KSerializer<DiskRecord>,
    private val memoryLimit: Int,
    val now: () -> Long = System::currentTimeMillis,
    memoryWarnings: MemoryWarningCenter? = null,
) {
    private val memory = HashMap<String, MemoryEntry>()

    init {
        memoryWarnings?.observe { withMemory { it.clear() } }
    }

    val memoryCount: Int get() = withMemory { it.size }

    fun <R> withMemory(block: (MutableMap<String, MemoryEntry>) -> R): R = synchronized(memory) { block(memory) }

    fun freshMemory(key: String): MemoryEntry? {
        val current = now()
        return withMemory { it[key] }?.takeIf { it.expires > current }
    }

    fun remember(entry: MemoryEntry, key: String) {
        withMemory { values ->
            values[key] = entry
            if (values.size > memoryLimit) {
                values.entries.filter { it.key != key }.sortedBy { it.value.expires }.take(values.size - memoryLimit)
                    .map { it.key }.forEach(values::remove)
            }
        }
    }

    fun store(entry: MemoryEntry, disk: DiskRecord, key: String) {
        remember(entry, key)
        writeDisk(disk, key)
    }

    fun file(key: String): File? {
        val directory = directory ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    fun readDisk(key: String): DiskRecord? {
        val file = file(key) ?: return null
        return runCatching { LinkPreviewJson.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    fun writeDisk(record: DiskRecord, key: String) {
        val directory = directory ?: return
        val file = file(key) ?: return
        runCatching {
            val text = LinkPreviewJson.encodeToString(serializer, record)
            directory.mkdirs()
            val temporary = File(directory, "${file.name}.${System.nanoTime()}.tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(file)) temporary.delete()
        }
    }

    fun sweep(maxBytes: Long? = null) {
        val directory = directory ?: return
        val files = directory.listFiles() ?: return
        val current = now()
        for (file in files) {
            if (file.extension != "json") continue
            val record = runCatching { LinkPreviewJson.decodeFromString(serializer, file.readText()) }.getOrNull()
            if ((record?.retentionDate ?: Long.MIN_VALUE) <= current) file.delete()
        }
        maxBytes ?: return
        val records = files.filter { it.extension == "json" && it.exists() }
            .map { Triple(it, it.length(), it.lastModified()) }
            .sortedByDescending { it.third }
        var size = 0L
        for ((file, bytes, _) in records) {
            size += bytes
            if (size > maxBytes) file.delete()
        }
    }
}

/**
 * One in-flight resolve per key however many callers ask. The resolve is unstructured: a cancelled
 * caller stops waiting, the work finishes for the others and the caches.
 */
abstract class CoalescedLoaderActor<Output> {
    private val inFlight = HashMap<String, Deferred<Output>>()
    protected val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    protected suspend fun coalesce(key: String, resolve: suspend () -> Output): Output {
        val task = synchronized(inFlight) {
            inFlight[key] ?: loaderScope.async { resolve() }.also { task ->
                inFlight[key] = task
                task.invokeOnCompletion { synchronized(inFlight) { if (inFlight[key] === task) inFlight.remove(key) } }
            }
        }
        return task.await()
    }
}

/**
 * A serial, per-key read queue whose callers own the work. Cancelling one waiter releases it
 * immediately; cancelling the last also stops the transport. Queued reads have no job until
 * admitted, so they never wait on a cancelled predecessor's job. Progress is replayed to joining
 * waiters.
 */
class CoalescedReadQueue<Output, Progress>(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private class Waiter<Output, Progress>(val continuation: CancellableContinuation<Output>, val report: (Progress) -> Unit)

    private class Read<Output, Progress>(
        val waiters: LinkedHashMap<Long, Waiter<Output, Progress>>,
        val resolve: suspend (report: (Progress) -> Unit) -> Output,
        var progress: Progress? = null,
    )

    private class Active(val key: String, val read: Any, val job: Job)

    private val lock = Any()
    private val reads = HashMap<String, Read<Output, Progress>>()
    private val queue = ArrayDeque<String>()
    private var active: Active? = null
    private var nextWaiter = 0L

    suspend fun load(key: String, report: (Progress) -> Unit, resolve: suspend (report: (Progress) -> Unit) -> Output): Output {
        currentCoroutineContext().ensureActive()
        return suspendCancellableCoroutine { continuation ->
            val waiter = Waiter(continuation, report)
            val (id, replay) = synchronized(lock) {
                val id = nextWaiter++
                val read = reads[key]
                if (read != null) {
                    read.waiters[id] = waiter
                    id to read.progress
                } else {
                    reads[key] = Read(linkedMapOf(id to waiter), resolve)
                    queue.addLast(key)
                    id to null
                }
            }
            replay?.let(report)
            continuation.invokeOnCancellation { cancel(key, id) }
            pump()
        }
    }

    private fun cancel(key: String, waiter: Long) {
        val job = synchronized(lock) {
            val read = reads[key] ?: return
            read.waiters.remove(waiter) ?: return
            if (read.waiters.isNotEmpty()) return
            reads.remove(key)
            queue.remove(key)
            active?.takeIf { it.read === read }?.job
        }
        job?.cancel()
    }

    private fun pump() {
        synchronized(lock) {
            if (active != null) return
            while (queue.isNotEmpty()) {
                val key = queue.removeFirst()
                val read = reads[key] ?: continue
                val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    val result = runCatching { read.resolve { value -> progress(value, key, read) } }
                    finish(result, key, read)
                }
                active = Active(key, read, job)
                job.start()
                return
            }
        }
    }

    private fun progress(value: Progress, key: String, read: Read<Output, Progress>) {
        val waiters = synchronized(lock) {
            if (reads[key] !== read) return
            read.progress = value
            read.waiters.values.toList()
        }
        waiters.forEach { it.report(value) }
    }

    private fun finish(result: Result<Output>, key: String, read: Read<Output, Progress>) {
        val waiters = synchronized(lock) {
            val waiters = if (reads[key] === read) {
                reads.remove(key)
                read.waiters.values.toList()
            } else {
                emptyList()
            }
            if (active?.read === read) active = null
            waiters
        }
        for (waiter in waiters) {
            result.fold({ waiter.continuation.resume(it) }, { waiter.continuation.resumeWithException(it) })
        }
        pump()
    }
}

/** A preview service request through [LinkPreviewTransport]. */
typealias LinkPreviewFetch = suspend (Request) -> LinkPreviewResponse

/**
 * Memory and disk caches keyed by the normalised URL, with one request in flight per link however
 * many rows ask.
 */
class LinkPreviewLoader(
    private val fetch: LinkPreviewFetch,
    directory: File?,
    now: () -> Long = System::currentTimeMillis,
    private val isConstrained: () -> Boolean = { false },
    private val shouldDeferArtwork: () -> Boolean = { false },
    memoryWarnings: MemoryWarningCenter? = null,
) : CoalescedLoaderActor<LinkPreviewResult>() {
    @Serializable
    class Record(
        val metadata: LinkPreviewMetadata? = null,
        override val expires: Long,
        val staleUntil: Long? = null,
    ) : ExpiringCacheEntry {
        val result: LinkPreviewResult get() = metadata?.let(LinkPreviewResult::Loaded) ?: LinkPreviewResult.Unavailable
        override val retentionDate: Long get() = staleUntil ?: expires
    }

    private val cache = CoalescedDiskCache<Record, Record>(directory, Record.serializer(), MEMORY_LIMIT, now, memoryWarnings)
    @Volatile private var lastSweep = 0L

    val memoryCount: Int get() = cache.memoryCount

    fun cached(target: LinkPreviewTarget): LinkPreviewResult? = cache.freshMemory(target.key)?.result

    /**
     * The last known rich metadata, from memory only: a card asks this while it draws, so it
     * must never read the disk. A cold miss is null, and the record on disk reaches memory
     * inside [load].
     */
    fun stale(target: LinkPreviewTarget): LinkPreviewMetadata? {
        val record = cache.withMemory { it[target.key] } ?: return null
        if (record.retentionDate <= cache.now()) return null
        return record.metadata
    }

    suspend fun load(target: LinkPreviewTarget): LinkPreviewResult {
        if (!target.loadsMetadata) return LinkPreviewResult.Unavailable
        cached(target)?.let { return it }
        return coalesce(target.key) { resolve(target) }
    }

    private fun keepStale(key: String, existing: Record?, currentTime: Long) {
        if (existing != null && existing.metadata != null && existing.retentionDate > currentTime) {
            cache.remember(existing, key)
        }
    }

    private suspend fun resolve(target: LinkPreviewTarget): LinkPreviewResult {
        val key = target.key
        val currentTime = cache.now()
        val existing = cache.withMemory { it[key] } ?: cache.readDisk(key)
        if (existing != null && existing.expires > currentTime) {
            cache.remember(existing, key)
            return existing.result
        }
        if (isConstrained()) {
            keepStale(key, existing, currentTime)
            return LinkPreviewResult.Deferred
        }
        val source = RichLinkSource.of(target.url)
        val request = when {
            target is LinkPreviewTarget.XPost -> XOEmbed.request(target.handle, target.id)
            source != null -> source.request
            else -> return LinkPreviewResult.Unavailable
        }
        val response = try {
            fetch(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Offline, Data Saver, a timeout: keep any stale record in memory/disk and report
            // `Deferred` so callers retry after the short backoff.
            keepStale(key, existing, currentTime)
            return LinkPreviewResult.Deferred
        }
        val record = when (response.statusCode) {
            200 -> {
                var metadata = if (target is LinkPreviewTarget.XPost) XOEmbed.metadata(response.data, target.handle)
                else source?.metadata(response.data)
                if (metadata != null) {
                    // Only constructed first-party URLs, never URLs from response fields. A
                    // refresh whose picture didn't come keeps the last one.
                    metadata = metadata.copy(image = artwork(source?.imageURL, pixels = 960) ?: existing?.metadata?.image)
                }
                val fetchedAt = cache.now()
                val lifetime = freshLifetime(source)
                Record(
                    metadata = metadata,
                    expires = fetchedAt + if (metadata == null) UNAVAILABLE_LIFETIME else lifetime,
                    staleUntil = if (metadata == null) null else fetchedAt + LOADED_LIFETIME,
                )
            }
            404, 410 -> Record(metadata = null, expires = cache.now() + UNAVAILABLE_LIFETIME)
            403 -> if (source == null) {
                Record(metadata = null, expires = cache.now() + UNAVAILABLE_LIFETIME)
            } else {
                keepStale(key, existing, cache.now())
                return LinkPreviewResult.Deferred
            }
            else -> {
                keepStale(key, existing, cache.now())
                return LinkPreviewResult.Deferred
            }
        }
        cache.store(record, record, key)
        // Sweeping reads every record: at most every ten minutes.
        if (cache.now() - lastSweep > SWEEP_INTERVAL) sweep()
        return record.result
    }

    private suspend fun artwork(url: String?, pixels: Int): ByteArray? {
        if (url == null || isConstrained() || shouldDeferArtwork()) return null
        val request = RichLinkSource.request(url).newBuilder().header("Accept", "image/*").build()
        val response = try {
            fetch(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        if (response.statusCode != 200) return null
        return LinkPreviewArtwork.thumbnail(response.data, pixels)
    }

    /**
     * Deletes expired and unreadable records, so the disk holds at most a week of posts. Runs
     * once when the loader is first used, then at most every ten minutes after a store.
     */
    fun sweep() {
        lastSweep = cache.now()
        // Images live in the same records. Keep the newest 50 MB, even when someone has
        // visited more links than their expiration window.
        cache.sweep(maxBytes = DISK_LIMIT)
    }

    companion object {
        /** A post's text rarely changes; a missing post may come back. */
        const val LOADED_LIFETIME: Long = 7L * 24 * 3600 * 1000
        const val UNAVAILABLE_LIFETIME: Long = 24L * 3600 * 1000
        /** GitHub and video metadata: five minutes. */
        const val RICH_LIFETIME: Long = 5L * 60 * 1000

        /**
         * How long an answer stays fresh: a post's text a week, a page's metadata (a repository's
         * stars, a video's title) five minutes. The store reads this for its own retry window.
         */
        fun freshLifetime(source: RichLinkSource?): Long = if (source == null) LOADED_LIFETIME else RICH_LIFETIME
        const val MEMORY_LIMIT = 64
        const val DISK_LIMIT: Long = 50L shl 20
        private const val SWEEP_INTERVAL: Long = 600L * 1000

        /** The app's cache directory's [name] folder; null without a context. */
        internal fun cachesDirectory(name: String): File? =
            runCatching { File(dependency(AppContext).cacheDir, name) }.getOrNull()

        internal val shared: LinkPreviewLoader by lazy {
            LinkPreviewLoader(
                fetch = LinkPreviewTransport::fetch,
                directory = cachesDirectory("link-previews"),
                isConstrained = { NetworkPathMonitor.shared?.isConstrained == true },
                shouldDeferArtwork = { NetworkPathMonitor.shared?.shouldDeferLargePrefetch == true },
                memoryWarnings = MemoryWarningCenter.default,
            ).also { loader -> CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loader.sweep() } }
        }
    }
}

/**
 * Offline link preview fixtures shared by the session lab (`debug.opencode.lab_link_previews`) and
 * in-app playgrounds. Fictional authors and example domains only.
 */
object LinkPreviewFixtures {
    fun load(target: LinkPreviewTarget): LinkPreviewResult {
        when (val source = RichLinkSource.of(target.url)) {
            is RichLinkSource.Github ->
                return github[source.link.reference ?: ""]?.let(LinkPreviewResult::Loaded) ?: LinkPreviewResult.Unavailable
            is RichLinkSource.Youtube -> return LinkPreviewResult.Loaded(
                LinkPreviewMetadata(
                    kind = LinkPreviewMetadata.Kind.Video, title = "Resilient uploads on bad networks",
                    text = "Example Talks", site = "YouTube", image = videoPoster,
                ),
            )
            null -> Unit
        }
        if (target !is LinkPreviewTarget.XPost) return LinkPreviewResult.Unavailable
        return posts[target.handle]?.let(LinkPreviewResult::Loaded) ?: LinkPreviewResult.Unavailable
    }

    fun iconData(hostOrFile: String): ByteArray? {
        val key = if (hostOrFile.endsWith(".ico")) hostOrFile else "$hostOrFile.ico"
        return when (key) {
            "example.com.ico" -> iconPNG(side = 64, letter = "E")
            "example.org.ico" -> iconPNG(side = 16, letter = "D")
            "github.com.ico" -> iconPNG(side = 64, letter = "G", color = gray(0.14f))
            "youtube.com.ico" -> iconPNG(side = 64, letter = "Y", color = Color.argb(1f, 1f, 0f, 0.2f))
            // Link Icons' lab reply: a dark mark like the real one.
            "vercel.com.ico" -> iconPNG(side = 64, letter = "▲", color = gray(0.06f))
            // Linear issue links: a round white mark with diagonal stripes on a dark square.
            "linear.app.ico" -> linearIconPNG(side = 48)
            else -> null
        }
    }

    /** An answer from the icon service stand-in: the fixture icon, else a 404. */
    internal fun iconResponse(request: Request, serves: Boolean = true): LinkPreviewResponse {
        val url = request.url
        val icon = if (serves && url.host == SiteIconService.host) iconData(url.pathSegments.last()) else null
        return LinkPreviewResponse(icon ?: ByteArray(0), if (icon == null) 404 else 200, "image/png")
    }

    private val immediateIcons = SiteIconLoader(fetch = { iconResponse(it) }, directory = null)

    /** Immediate, offline client for playgrounds: rich cards and domain icons with no network requests or artificial delay. */
    val immediateClient = LinkPreviewClient(
        cached = { load(it) },
        stale = { (load(it) as? LinkPreviewResult.Loaded)?.metadata },
        load = { load(it) },
        cachedIcon = immediateIcons::cached,
        loadIcon = { immediateIcons.load(it) },
    )

    val videoPoster: ByteArray by lazy {
        val bitmap = Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.argb(1f, 0.08f, 0.17f, 0.23f))
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 38f
            strokeCap = Paint.Cap.ROUND
        }
        for (index in 0 until 7) {
            stroke.color = Color.argb(0.2f + index * 0.1f, 0.35f, 0.8f, 0.75f)
            val x = 80f + index * 100
            canvas.drawLine(x, 420f, x, 330f - index * 35, stroke)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
        canvas.drawText("Resilient uploads", 60f, 48f - text.ascent(), text)
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private fun gray(white: Float) = Color.argb(1f, white, white, white)

    /** A rounded square, orange unless told otherwise, with a white letter. */
    fun iconPNG(side: Int, letter: String, color: Int = Color.argb(1f, 1f, 0.42f, 0.1f)): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), side * 0.22f, side * 0.22f, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = side * 0.6f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val baseline = side / 2f - (text.descent() + text.ascent()) / 2
        canvas.drawText(letter, side / 2f, baseline, text)
        return png(bitmap)
    }

    /** Linear's favicon shape: a dark square with a centered white circle and three diagonal stripes across its lower-left half. */
    fun linearIconPNG(side: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val size = side.toFloat()
        val dark = gray(0.06f)
        canvas.drawRoundRect(RectF(0f, 0f, size, size), size * 0.18f, size * 0.18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark })
        val inset = size * 0.135f
        val circle = Path().apply { addOval(RectF(inset, inset, size - inset, size - inset), Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(circle)
        canvas.drawPath(circle, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val stripe = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dark
            style = Paint.Style.STROKE
            strokeWidth = size * 0.068f
        }
        for (offset in listOf(-0.26f, -0.13f, 0f)) {
            val d = size * offset
            canvas.drawLine(d, size * 0.28f - d, size * 0.72f + d, size - d, stripe)
        }
        canvas.restore()
        return png(bitmap)
    }

    private fun png(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** GitHub answers by reference; the repository's is "". */
    val github: Map<String, LinkPreviewMetadata> = mapOf(
        "" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Github, title = "acme/widget", text = "A small, resumable upload client for Swift.", site = "GitHub",
            github = GitHubPreview(repository = "acme/widget", kind = GitHubLink.Kind.Repository, author = "acme", stars = 4200, language = "Swift"),
        ),
        "1234" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Github, title = "Upload retries forever on flaky networks", site = "GitHub",
            github = GitHubPreview(
                repository = "acme/widget", kind = GitHubLink.Kind.Issue, reference = "1234", state = GitHubPreview.State.Open,
                author = "mlee", labels = listOf("bug", "networking"), comments = 12,
            ),
        ),
        "1240" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Github, title = "Move the upload queue to a background session", site = "GitHub",
            github = GitHubPreview(
                repository = "acme/widget", kind = GitHubLink.Kind.PullRequest, reference = "1240", state = GitHubPreview.State.Open,
                author = "kai", additions = 412, deletions = 198,
            ),
        ),
        "1251" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Github, title = "Add jittered backoff to upload retries", site = "GitHub",
            github = GitHubPreview(
                repository = "acme/widget", kind = GitHubLink.Kind.PullRequest, reference = "1251", state = GitHubPreview.State.Merged,
                author = "alex", labels = listOf("networking"), additions = 84, deletions = 12,
            ),
        ),
        "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Github, title = "Retry with full jitter instead of a fixed delay", site = "GitHub",
            github = GitHubPreview(
                repository = "acme/widget", kind = GitHubLink.Kind.Commit, reference = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678",
                author = "janedoe", additions = 41, deletions = 9,
            ),
        ),
    )

    val posts: Map<String, LinkPreviewMetadata> = mapOf(
        "example_dev" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Post, title = "Example Dev", handle = "example_dev",
            text = "Spent the week tuning rubber-band scrolling. The trick: let the spring keep its velocity when the target moves, and never snap. Demo below.",
        ),
        "pixel_fixture" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Post, title = "Pixel Fixture", handle = "pixel_fixture",
            text = "Hot take: overscroll should feel like pulling a sheet of paper, not a rubber band. Less stretch, more friction.",
        ),
        "labnotes_demo" to LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Post, title = "Lab Notes", handle = "labnotes_demo",
            text = "We measured 40 apps. The ones that feel fastest settle within 350 ms of the finger lifting, whatever their curve.",
        ),
    )
}

/**
 * Lab launch switches as `debug.opencode.*` system properties, read only in debuggable builds (`adb
 * shell setprop debug.opencode.lab_link_previews 1`, then relaunch).
 */
internal object LabLaunch {
    val isDebuggable: Boolean by lazy {
        runCatching { dependency(AppContext).applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }.getOrDefault(false)
    }

    @SuppressLint("PrivateApi")
    fun property(name: String): String? {
        if (!isDebuggable) return null
        return runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    fun flag(name: String): Boolean = property(name)?.let { it == "1" || it.equals("true", ignoreCase = true) || it == "YES" } == true

    /** `debug.opencode.lab_server_url`. */
    val serverURL: String? by lazy { property("debug.opencode.lab_server_url") }
}

/**
 * `debug.opencode.lab_link_previews`: the session lab's fictional X posts, answered after a short
 * delay so the placeholder shows, without touching the network. Site icons go through the real
 * loader, from a stand-in for the icon service: `example.com` has an icon, `example.org` only a 16
 * px one (so its card keeps the globe). `debug.opencode.lab_link_preview_icons` = 0 serves none;
 * `…_delay` sets the delay in ms (default 700); `…_held` = 1 holds every answer until the
 * `ai.opencode.lab-link-previews.release` broadcast.
 */
internal object LinkPreviewLab {
    val isEnabled: Boolean by lazy { LabLaunch.flag("debug.opencode.lab_link_previews") }
    val delayMillis: Long by lazy { LabLaunch.property("debug.opencode.lab_link_preview_delay")?.toLongOrNull()?.takeIf { it != 0L } ?: 700L }
    val servesIcons: Boolean by lazy {
        val value = LabLaunch.property("debug.opencode.lab_link_preview_icons")
        value == null || value == "1" || value.equals("true", ignoreCase = true) || value == "YES"
    }

    private val icons by lazy {
        SiteIconLoader(
            fetch = { request ->
                LinkPreviewLabGate.wait()
                delay(delayMillis)
                LinkPreviewFixtures.iconResponse(request, serves = servesIcons)
            },
            directory = null,
        )
    }

    val client: LinkPreviewClient by lazy {
        LinkPreviewClient(
            cached = { null },
            load = { target ->
                LinkPreviewLabGate.wait()
                delay(delayMillis)
                LinkPreviewFixtures.load(target)
            },
            cachedIcon = { icons.cached(it) },
            loadIcon = { icons.load(it) },
        )
    }
}

/**
 * A lab-only barrier so both comparison builds receive metadata at the marked frame. Released with
 * `adb shell am broadcast -a ai.opencode.lab-link-previews.release`.
 */
private object LinkPreviewLabGate {
    private const val RELEASE = "ai.opencode.lab-link-previews.release"
    private val released = CompletableDeferred<Unit>()

    init {
        if (!LabLaunch.flag("debug.opencode.lab_link_preview_held")) {
            released.complete(Unit)
        } else {
            runCatching {
                dependency(AppContext).applicationContext.registerReceiver(
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            released.complete(Unit)
                        }
                    },
                    IntentFilter(RELEASE),
                    Context.RECEIVER_EXPORTED,
                )
            }.onFailure { released.complete(Unit) }
        }
    }

    suspend fun wait() = released.await()
}
