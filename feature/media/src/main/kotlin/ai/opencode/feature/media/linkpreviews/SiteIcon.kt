package ai.opencode.feature.media

import ai.opencode.server.retry.NetworkPathMonitor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The only part of a link a site icon request ever carries: its registrable domain (eTLD+1), so
 * `docs.exe.dev/regions` asks for `exe.dev` and `secret.evil.com` for `evil.com`. Path, query,
 * port, and subdomains never leave the phone.
 */
object SiteIconDomain {
    /**
     * The registrable domain of a previewable link, as lowercase ASCII (IDNs in their `xn--`
     * form); null for anything [LinkPreviewPolicy] refuses or a host that isn't a plain DNS name.
     */
    fun registrable(url: String): String? {
        if (!LinkPreviewPolicy.isPreviewable(url)) return null
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return registrableHost(host)
    }

    fun registrableHost(host: String): String? {
        var name = host.lowercase()
        while (name.endsWith(".")) name = name.dropLast(1)
        val labels = name.split(".")
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        val tld = labels.last()
        if (tld.none { it.isLetter() }) return null
        val suffixLength = if (secondLevelSuffixes[tld]?.contains(labels[labels.size - 2]) == true) 2 else 1
        // A public suffix itself (`co.uk`) has no registrable domain.
        if (labels.size <= suffixLength) return null
        val kept = labels.takeLast(suffixLength + 1)
        if (!kept.all(::isHostname)) return null
        val domain = kept.joinToString(".")
        return if (domain.length <= 253) domain else null
    }

    /** Letters, digits, and inner hyphens, at most 63 of them: nothing that could reach past the request's path segment. */
    private fun isHostname(label: String): Boolean =
        label.length in 1..63 && !label.startsWith("-") && !label.endsWith("-") &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /**
     * The ICANN second-level suffixes under which sites commonly register, by top-level domain.
     * The full list's private section (`github.io`, `ngrok.app`, wildcards) would hand anyone
     * with a shared host a label of their choosing. A suffix missing here only ever sends fewer
     * labels (`co.za`, not `example.co.za`): the wrong icon or none, never more of the link.
     */
    val secondLevelSuffixes: Map<String, Set<String>> = mapOf(
        "ae" to setOf("ac", "co", "gov", "net", "org"),
        "ar" to setOf("com", "edu", "gob", "gov", "net", "org"),
        "au" to setOf("asn", "com", "edu", "gov", "id", "net", "org"),
        "br" to setOf("com", "edu", "gov", "net", "org"),
        "cn" to setOf("ac", "com", "edu", "gov", "net", "org"),
        "co" to setOf("com", "edu", "gov", "net", "org"),
        "eg" to setOf("com", "edu", "gov", "net", "org"),
        "es" to setOf("com", "edu", "gob", "nom", "org"),
        "gr" to setOf("com", "edu", "gov", "net", "org"),
        "hk" to setOf("com", "edu", "gov", "idv", "net", "org"),
        "id" to setOf("ac", "co", "go", "net", "or", "sch", "web"),
        "il" to setOf("ac", "co", "gov", "muni", "net", "org"),
        "in" to setOf("ac", "co", "edu", "firm", "gen", "gov", "ind", "net", "org", "res"),
        "jp" to setOf("ac", "ad", "co", "ed", "go", "gr", "lg", "ne", "or"),
        "ke" to setOf("ac", "co", "go", "ne", "or"),
        "kr" to setOf("ac", "co", "go", "ne", "or", "re"),
        "mx" to setOf("com", "edu", "gob", "net", "org"),
        "my" to setOf("com", "edu", "gov", "name", "net", "org"),
        "ng" to setOf("com", "edu", "gov", "net", "org"),
        "nz" to setOf("ac", "co", "geek", "gen", "govt", "net", "org", "school"),
        "pe" to setOf("com", "edu", "gob", "net", "org"),
        "ph" to setOf("com", "edu", "gov", "net", "org"),
        "pk" to setOf("com", "edu", "gov", "net", "org"),
        "pl" to setOf("com", "net", "org"),
        "pt" to setOf("com", "edu", "gov", "org"),
        "qa" to setOf("com", "edu", "gov", "net", "org"),
        "sa" to setOf("com", "edu", "gov", "net", "org"),
        "sg" to setOf("com", "edu", "gov", "net", "org", "per"),
        "th" to setOf("ac", "co", "go", "in", "net", "or"),
        "tr" to setOf("av", "bel", "biz", "com", "edu", "gen", "gov", "info", "net", "org"),
        "tw" to setOf("com", "edu", "gov", "idv", "net", "org"),
        "ua" to setOf("com", "edu", "gov", "in", "net", "org"),
        "uk" to setOf("ac", "co", "gov", "ltd", "me", "mod", "net", "nhs", "org", "plc", "police", "sch"),
        "vn" to setOf("com", "edu", "gov", "net", "org"),
        "za" to setOf("ac", "co", "gov", "net", "org", "web"),
    )
}

/**
 * DuckDuckGo's icon service: it fetches the icon, so the site never sees the phone, and it is told
 * only the registrable domain. It serves the site's own favicon at the site's sizes; there is no
 * size to ask for.
 */
object SiteIconService {
    const val host = "icons.duckduckgo.com"

    fun request(domain: String): Request? {
        if (SiteIconDomain.registrableHost(domain) != domain) return null
        val url = "https://$host/ip3/$domain.ico".toHttpUrlOrNull() ?: return null
        return Request.Builder()
            .url(url)
            .tag(LinkPreviewTimeout::class.java, LinkPreviewTimeout(10, TimeUnit.SECONDS))
            .header("Accept", "image/*")
            .build()
    }
}

/**
 * A site's icon, ready to draw: square, at most [maximumPixels] a side, and never smaller than
 * [minimumPixels], blank, or a single flat colour.
 */
class SiteIcon private constructor(
    val image: Bitmap,
    /** What the disk cache keeps. */
    val png: ByteArray,
    val tone: Tone?,
) {
    /** A mark that would vanish on one appearance's card: a dark glyph on transparency (GitHub's) in dark mode, or a light one in light mode. */
    enum class Tone { Dark, Light }

    override fun equals(other: Any?): Boolean = other is SiteIcon && other.png.contentEquals(png)
    override fun hashCode(): Int = png.contentHashCode()
    override fun toString(): String = "SiteIcon(${image.width}px, tone=$tone)"

    private sealed interface Inspection {
        data object Blank : Inspection
        /** A mark, with the tone that needs a plate, if any. */
        data class Mark(val tone: Tone?) : Inspection
    }

    companion object {
        /** Smaller icons (a 16 px favicon) are blurry at the card's size. */
        const val minimumPixels = 32
        /** The slot is 28 dp at most, at 3x. */
        const val maximumPixels = 96

        /**
         * The largest frame of an ICO (Skia's ICO codec decodes its largest entry), PNG, or other
         * image `BitmapFactory` reads, or null when it is too small, blank, or one flat colour.
         */
        fun of(data: ByteArray): SiteIcon? {
            if (data.size > 1 shl 20) return null
            val best = BitmapFactory.decodeByteArray(
                data, 0, data.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
            ) ?: return null
            if (min(best.width, best.height) < minimumPixels) return null
            val side = min(max(best.width, best.height), maximumPixels)
            val square = render(best, side)
            val sample = render(best, 16)
            val inspection = inspect(sample) as? Inspection.Mark ?: return null
            val output = ByteArrayOutputStream()
            if (!square.compress(Bitmap.CompressFormat.PNG, 100, output)) return null
            return SiteIcon(square, output.toByteArray(), inspection.tone)
        }

        /** A `data:image/…;base64,` URL from server-supplied metadata. */
        fun ofDataURL(dataURL: String): SiteIcon? {
            if (!dataURL.startsWith("data:image/")) return null
            val comma = dataURL.indexOf(',')
            if (comma < 0 || !dataURL.substring(0, comma).endsWith(";base64")) return null
            val data = runCatching { Base64.decode(dataURL.substring(comma + 1), Base64.DEFAULT) }.getOrNull() ?: return null
            return of(data)
        }

        /** Aspect-fitted into a transparent square. */
        private fun render(image: Bitmap, side: Int): Bitmap {
            val output = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            val scale = side.toFloat() / max(image.width, image.height)
            val width = image.width * scale
            val height = image.height * scale
            val left = (side - width) / 2
            val top = (side - height) / 2
            Canvas(output).drawBitmap(
                image, null, RectF(left, top, left + width, top + height),
                Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
            )
            return output
        }

        private fun inspect(sample: Bitmap): Inspection {
            val count = sample.width * sample.height
            val colors = IntArray(count)
            // Unpremultiplied ARGB; the flat test compares premultiplied values.
            sample.getPixels(colors, 0, sample.width, 0, 0, sample.width, sample.height)
            val alpha = IntArray(count) { colors[it] ushr 24 }
            val red = IntArray(count) { (colors[it] shr 16) and 0xFF }
            val green = IntArray(count) { (colors[it] shr 8) and 0xFF }
            val blue = IntArray(count) { colors[it] and 0xFF }
            fun premultiplied(value: Int, index: Int) = (value * alpha[index] + 127) / 255
            val maxAlpha = alpha.maxOrNull() ?: return Inspection.Blank
            if (maxAlpha < 16) return Inspection.Blank
            val firstRed = premultiplied(red[0], 0)
            val firstGreen = premultiplied(green[0], 0)
            val firstBlue = premultiplied(blue[0], 0)
            val flat = (0 until count).all {
                abs(premultiplied(red[it], it) - firstRed) <= 4 && abs(premultiplied(green[it], it) - firstGreen) <= 4 &&
                    abs(premultiplied(blue[it], it) - firstBlue) <= 4 && abs(alpha[it] - alpha[0]) <= 4
            }
            if (flat) return Inspection.Blank
            val transparent = alpha.count { it < 32 }.toDouble() / count
            val visible = (0 until count).filter { alpha[it] >= 128 }
            if (transparent < 0.25 || visible.isEmpty()) return Inspection.Mark(null)
            val luminance = visible.sumOf { (0.2126 * red[it] + 0.7152 * green[it] + 0.0722 * blue[it]) / 255.0 } / visible.size
            return Inspection.Mark(if (luminance < 0.25) Tone.Dark else if (luminance > 0.85) Tone.Light else null)
        }
    }
}

sealed interface SiteIconResult {
    data class Icon(val icon: SiteIcon) : SiteIconResult
    /** No usable icon (none, too small, blank): remembered, not asked again. */
    data object Missing : SiteIconResult
    /** Not fetched now (offline, Data Saver); a later view tries again. */
    data object Deferred : SiteIconResult
}

/**
 * Memory and disk caches keyed by registrable domain, with one request in flight per domain however
 * many cards ask. Missing icons are remembered too.
 */
class SiteIconLoader(
    private val fetch: LinkPreviewFetch,
    directory: File?,
    now: () -> Long = System::currentTimeMillis,
    private val isConstrained: () -> Boolean = { false },
    memoryWarnings: MemoryWarningCenter? = null,
) : CoalescedLoaderActor<SiteIconResult>() {
    @Serializable
    class Record(
        @Serializable(with = Base64ByteArraySerializer::class) val png: ByteArray? = null,
        override val expires: Long,
    ) : ExpiringCacheEntry

    class Entry(val icon: SiteIcon?, override val expires: Long) : ExpiringCacheEntry {
        val result: SiteIconResult get() = icon?.let(SiteIconResult::Icon) ?: SiteIconResult.Missing
    }

    private val cache = CoalescedDiskCache<Entry, Record>(directory, Record.serializer(), MEMORY_LIMIT, now, memoryWarnings)

    val memoryCount: Int get() = cache.memoryCount

    fun cached(domain: String): SiteIconResult? = cache.freshMemory(domain)?.result

    suspend fun load(domain: String): SiteIconResult {
        val request = SiteIconService.request(domain) ?: return SiteIconResult.Missing
        cached(domain)?.let { return it }
        return coalesce(domain) { resolve(domain, request) }
    }

    private suspend fun resolve(domain: String, request: Request): SiteIconResult {
        cache.readDisk(domain)?.takeIf { it.expires > cache.now() }?.let { record ->
            val entry = Entry(record.png?.let(SiteIcon::of), record.expires)
            cache.remember(entry, domain)
            return entry.result
        }
        if (isConstrained()) return SiteIconResult.Deferred
        val response = try {
            fetch(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return SiteIconResult.Deferred
        }
        val icon = when (response.statusCode) {
            // The service answers a missing icon with a 404 and a placeholder.
            200 -> SiteIcon.of(response.data)
            403, 404, 410 -> null
            else -> return SiteIconResult.Deferred
        }
        val entry = Entry(icon, cache.now() + if (icon == null) MISSING_LIFETIME else ICON_LIFETIME)
        cache.store(entry, Record(icon?.png, entry.expires), domain)
        return entry.result
    }

    fun sweep() = cache.sweep()

    companion object {
        const val ICON_LIFETIME: Long = 14L * 24 * 3600 * 1000
        const val MISSING_LIFETIME: Long = 3L * 24 * 3600 * 1000
        const val MEMORY_LIMIT = 128

        val shared: SiteIconLoader by lazy {
            SiteIconLoader(
                fetch = LinkPreviewTransport::fetch,
                directory = LinkPreviewLoader.cachesDirectory("site-icons"),
                isConstrained = { NetworkPathMonitor.shared?.isConstrained == true },
                memoryWarnings = MemoryWarningCenter.default,
            ).also { loader -> CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { loader.sweep() } }
        }
    }
}
