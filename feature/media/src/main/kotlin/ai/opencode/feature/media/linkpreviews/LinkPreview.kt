package ai.opencode.feature.media

import ai.opencode.server.OpenCodePairingLink
import android.util.Patterns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * A normalised public link. First-party metadata sources are selected by strict URL shapes in
 * [RichLinkSource]; arbitrary web pages aren't fetched.
 */
sealed interface LinkPreviewTarget {
    data class XPost(val handle: String, val id: String) : LinkPreviewTarget
    data class Web(val webURL: String) : LinkPreviewTarget

    /** The normalised URL: the cache key and what a tap opens. */
    val url: String
        get() = when (this) {
            is XPost -> "https://x.com/$handle/status/$id"
            is Web -> webURL
        }

    val key: String get() = url

    val loadsMetadata: Boolean
        get() = this is XPost || RichLinkSource.of(url) != null

    companion object {
        /** A previewable link, normalised; null for anything private. */
        fun of(url: String): LinkPreviewTarget? {
            if (!LinkPreviewPolicy.isPreviewable(url)) return null
            val normalized = LinkPreviewPolicy.normalized(url) ?: return null
            val parsed = normalized.toHttpUrlOrNull() ?: return null
            if (parsed.host == "x.com") xPost(parsed.encodedPath)?.let { return XPost(it.first, it.second) }
            return Web(normalized)
        }

        /** `/<handle>/status/<id>`, optionally followed by `/photo/1` and the like. */
        private fun xPost(path: String): Pair<String, String>? {
            val parts = path.split("/").filter { it.isNotEmpty() }
            if (parts.size < 3 || (parts[1] != "status" && parts[1] != "statuses")) return null
            val handle = parts[0]
            val id = parts[2]
            val handleOK = handle.length in 1..15 && handle.all { it.code < 128 && (it.isLetterOrDigit() || it == '_') }
            val idOK = id.length in 1..25 && id.all { it in '0'..'9' }
            return if (handleOK && idOK) handle to id else null
        }
    }
}

/** Which links may ever be previewed, and the one form each is keyed by. */
object LinkPreviewPolicy {
    /**
     * Public web pages only: never loopback, private or tailnet hosts, IP literals, custom
     * ports, embedded credentials, or URLs that carry a token or signature in their query.
     */
    fun isPreviewable(url: String): Boolean = isPublicWebURL(url)

    fun isPublicWebURL(
        url: String,
        allowsCustomPort: Boolean = false,
        allowsLoopback: Boolean = false,
        allowsTailnetImages: Boolean = false,
    ): Boolean {
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) return false
        val components = url.toHttpUrlOrNull() ?: return false
        val scheme = components.scheme.lowercase()
        if (scheme != "https" && scheme != "http") return false
        if (components.username.isNotEmpty() || components.password.isNotEmpty()) return false
        val host = components.host.lowercase().trim('.')
        if (host.isEmpty()) return false
        if (allowsLoopback && BrowserLinkPolicy.isLoopback(host)) return true
        if (allowsTailnetImages && scheme == "https" && host.endsWith(".ts.net") && host.split(".").size == 4) {
            return queryNames(components).none(::isCredential)
        }
        // `HttpUrl.port` is the scheme's default when none is named.
        if (!allowsCustomPort && components.port != 443 && components.port != 80) return false
        if (isIPLiteral(host)) return false
        // A single label (`intranet`, `nas`) is a local name.
        if (!host.contains(".")) return false
        if (privateSuffixes.any { host == it.drop(1) || host.endsWith(it) }) return false
        return queryNames(components).none(::isCredential)
    }

    /**
     * Lowercased scheme and host, X's other hosts folded into x.com, without the fragment,
     * trailing slash, or tracking and share parameters.
     */
    fun normalized(url: String): String? {
        val components = url.toHttpUrlOrNull() ?: return null
        var host = components.host.lowercase()
        if (host.endsWith(".")) host = host.dropLast(1)
        if (host in xHosts) host = "x.com"
        val builder = try {
            components.newBuilder().scheme(components.scheme.lowercase()).host(host).fragment(null).query(null)
        } catch (_: IllegalArgumentException) {
            // OkHttp can't read back some punycode hosts it wrote itself, such as one with a full-width comma.
            return null
        }
        for (index in 0 until components.querySize) {
            val name = components.queryParameterName(index).lowercase()
            if (name.startsWith("utm_") || name in trackingParameters) continue
            // X's share links add `s` (the platform) and `t` (a per-share tracker).
            if (host == "x.com" && (name == "s" || name == "t")) continue
            builder.addQueryParameter(components.queryParameterName(index), components.queryParameterValue(index))
        }
        val segments = components.encodedPathSegments
        if (components.encodedPath.length > 1 && components.encodedPath.endsWith("/")) {
            builder.removePathSegment(segments.size - 1)
        }
        return builder.build().toString()
    }

    fun displayHost(url: String): String {
        val host = url.toHttpUrlOrNull()?.host?.lowercase() ?: url
        return if (host.startsWith("www.")) host.drop(4) else host
    }

    private val xHosts = setOf("twitter.com", "www.twitter.com", "mobile.twitter.com", "www.x.com", "mobile.x.com")
    private val trackingParameters = setOf("fbclid", "gclid", "dclid", "igshid", "mc_cid", "mc_eid", "ref_src", "ref_url", "si")
    private val privateSuffixes = listOf(
        ".localhost", ".local", ".internal", ".intranet", ".lan", ".home", ".corp", ".home.arpa",
        ".test", ".invalid", ".ts.net", ".onion",
    )

    private fun queryNames(url: HttpUrl): List<String> = (0 until url.querySize).map(url::queryParameterName)

    private fun isIPLiteral(host: String): Boolean {
        if (host.contains(":") || host.startsWith("[")) return true
        val labels = host.split(".").filter { it.isNotEmpty() }
        return labels.isNotEmpty() && labels.all { label -> label.all { it.isDigit() } }
    }

    private fun isCredential(name: String): Boolean {
        val lower = name.lowercase()
        val exact = setOf(
            "key", "apikey", "api_key", "auth", "authorization", "code", "sig", "sid", "session", "sessionid",
            "jwt", "otp", "ticket", "pass", "pwd", "credential", "credentials", "x-amz-credential", "x-goog-credential",
        )
        return lower in exact || listOf("token", "signature", "secret", "password").any { lower.contains(it) }
    }
}

/**
 * One attribute run of a Markdown block as link extraction reads it. `:feature:transcript` maps its
 * `InlineRun`s into these.
 */
data class LinkTextRun(val text: String, val link: String? = null, val isCode: Boolean = false)

/**
 * The link-bearing text of one Markdown block: [prose] for a paragraph, numbered or list item,
 * blockquote or heading; [cells] (header then rows) for a table; neither for code, images, pull
 * requests, dividers, files, math and Mermaid.
 */
data class LinkTextBlock(
    val id: Int,
    val prose: List<LinkTextRun>? = null,
    val cells: List<List<LinkTextRun>> = emptyList(),
)

/** Each web URL with its character range. */
internal object LinkDetector {
    private val trailing = ".,;:!?'\"".toSet()

    fun matches(plain: String): List<Pair<String, IntRange>> {
        val matcher = Patterns.WEB_URL.matcher(plain)
        val result = mutableListOf<Pair<String, IntRange>>()
        while (matcher.find()) {
            var end = matcher.end()
            // The detector leaves a sentence's closing punctuation and an unbalanced ")" out.
            while (end > matcher.start()) {
                val last = plain[end - 1]
                val match = plain.substring(matcher.start(), end)
                if (last in trailing || (last == ')' && match.count { it == '(' } < match.count { it == ')' })) end-- else break
            }
            if (end <= matcher.start()) continue
            val text = plain.substring(matcher.start(), end)
            val url = if (text.contains("://")) text else "http://$text"
            result += url to (matcher.start() until end)
        }
        return result
    }
}

/** The links a reply names, in order, once each. */
object LinkPreviewExtractor {
    fun targets(blocks: List<LinkTextBlock>, limit: Int = 6): List<LinkPreviewTarget> =
        labeledTargets(blocks, limit).map { it.first }

    /**
     * The same links, each with the text a Markdown link gave it (`[Docs](…)` is “Docs”); null for
     * a URL found in plain text, or a label that is blank, contains `://` or is just the host.
     */
    fun labeledTargets(blocks: List<LinkTextBlock>, limit: Int = 6): List<Pair<LinkPreviewTarget, String?>> {
        val seen = HashSet<String>()
        val result = mutableListOf<Pair<LinkPreviewTarget, String?>>()
        fun add(url: String, label: String? = null) {
            if (result.size >= limit) return
            val target = LinkPreviewTarget.of(url) ?: return
            if (!seen.add(target.key)) return
            val trimmed = label?.trim()
            val readable = if (trimmed.isNullOrEmpty() || trimmed.contains("://") || trimmed == url.toHttpUrlOrNull()?.host) null else trimmed
            result += target to readable
        }
        for (block in blocks) {
            // Appended PR blocks are derived by an older prefix matcher. Read the original prose
            // URL only: a derived block may have lost a credential query or matched just a
            // prefix of a longer URL.
            for (text in texts(block)) {
                // In reading order: a run's link, or bare URLs the Markdown left as its text. A
                // URL in inline code is code, not a link. A label styled in parts (`[**Docs** page](…)`)
                // is several runs, so a link is added with its whole span's text once the span ends.
                var spanLink: String? = null
                val span = StringBuilder()
                for (run in text) {
                    if (run.link != spanLink) {
                        if (spanLink != null) add(spanLink, span.toString())
                        spanLink = run.link
                        span.clear()
                    }
                    if (run.link != null) {
                        span.append(run.text)
                        continue
                    }
                    if (run.isCode) continue
                    if (!run.text.contains("://")) continue
                    for ((url, _) in LinkDetector.matches(run.text)) add(url)
                }
                if (spanLink != null) add(spanLink, span.toString())
            }
        }
        return result
    }

    private fun texts(block: LinkTextBlock): List<List<LinkTextRun>> = block.prose?.let { listOf(it) } ?: block.cells
}

/**
 * What is known about a link beyond its URL. Posts come from X's oEmbed; pages are the seam for
 * metadata the server supplies with a reply.
 */
@Serializable
class LinkPreviewMetadata(
    val kind: Kind,
    /** A post's author, or a page's title. */
    val title: String,
    /** A post's handle, without the @. */
    val handle: String? = null,
    /** A post's text, or a page's description. */
    val text: String? = null,
    /** A page's site name. */
    val site: String? = null,
    /** The site's icon as a `data:image/…;base64,` URL; it takes the place of the one from the icon service. */
    val icon: String? = null,
    val github: GitHubPreview? = null,
    /** Bounded, downsampled image bytes, never a URL a view fetches itself. */
    @Serializable(with = Base64ByteArraySerializer::class)
    val image: ByteArray? = null,
) {
    @Serializable
    enum class Kind {
        @SerialName("post") Post,
        @SerialName("page") Page,
        @SerialName("github") Github,
        @SerialName("video") Video,
    }

    fun copy(image: ByteArray? = this.image): LinkPreviewMetadata =
        LinkPreviewMetadata(kind, title, handle, text, site, icon, github, image)

    override fun equals(other: Any?): Boolean =
        other is LinkPreviewMetadata && other.kind == kind && other.title == title && other.handle == handle &&
            other.text == text && other.site == site && other.icon == icon && other.github == github &&
            (other.image?.contentEquals(image) ?: (image == null))

    override fun hashCode(): Int {
        var hash = kind.hashCode()
        hash = hash * 31 + title.hashCode()
        hash = hash * 31 + (handle?.hashCode() ?: 0)
        hash = hash * 31 + (text?.hashCode() ?: 0)
        hash = hash * 31 + (github?.hashCode() ?: 0)
        hash = hash * 31 + (image?.contentHashCode() ?: 0)
        return hash
    }

    override fun toString(): String = "LinkPreviewMetadata(kind=$kind, image=${image?.size ?: 0} bytes)"
}

sealed interface LinkPreviewResult {
    data class Loaded(val metadata: LinkPreviewMetadata) : LinkPreviewResult

    /** Nothing to show beyond the URL (a deleted or protected post). */
    data object Unavailable : LinkPreviewResult

    /** Not fetched now (offline, Data Saver); a later view tries again. */
    data object Deferred : LinkPreviewResult
}

/** Shared JSON for preview services' answers and the disk caches. */
internal val LinkPreviewJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** X's oEmbed endpoint: a request carrying only the handle and post ID, and the author and text from its answer. */
object XOEmbed {
    fun request(handle: String, id: String): Request {
        val url = "https://publish.x.com/oembed".toHttpUrl().newBuilder()
            .addQueryParameter("url", "https://x.com/$handle/status/$id")
            .addQueryParameter("omit_script", "1")
            .addQueryParameter("dnt", "true")
            .build()
        return Request.Builder()
            .url(url)
            .tag(LinkPreviewTimeout::class.java, LinkPreviewTimeout(10, TimeUnit.SECONDS))
            .header("Accept", "application/json")
            .build()
    }

    @Serializable
    private class Response(
        @SerialName("author_name") val authorName: String? = null,
        @SerialName("author_url") val authorURL: String? = null,
        val html: String? = null,
    )

    fun metadata(data: ByteArray, handle: String): LinkPreviewMetadata? {
        val response = runCatching { LinkPreviewJson.decodeFromString(Response.serializer(), data.decodeToString()) }
            .getOrNull() ?: return null
        val author = response.authorName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val authorHandle = response.authorURL?.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotEmpty() }
        return LinkPreviewMetadata(
            kind = LinkPreviewMetadata.Kind.Post,
            title = author,
            handle = authorHandle?.takeIf { it.isNotEmpty() && it != "/" } ?: handle,
            text = response.html?.let(::postText),
        )
    }

    /**
     * The text of the embed's first paragraph, as plain text: tags removed, line breaks kept,
     * entities decoded, and a trailing media link dropped.
     */
    fun postText(html: String): String? {
        val open = html.indexOf("<p")
        if (open < 0) return null
        val start = html.indexOf('>', open + 2)
        if (start < 0) return null
        val close = html.indexOf("</p>", start)
        if (close < 0) return null
        var text = html.substring(start + 1, close)
        text = text.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        text = text.replace(Regex("<[^>]+>"), "")
        text = decodeEntities(text)
        text = text.replace(Regex("\\s*(pic\\.(twitter|x)\\.com/\\w+|https://t\\.co/\\w+)\\s*$"), "")
        return text.trim().takeIf { it.isNotEmpty() }
    }

    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    )

    private fun decodeEntities(text: String): String =
        text.replace(Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")) { match ->
            val name = match.groupValues[1]
            val scalar = when {
                name.startsWith("#x") -> name.drop(2).toLongOrNull(16)
                name.startsWith("#") -> name.drop(1).toLongOrNull()
                else -> null
            }
            if (scalar != null && scalar <= 0x10FFFF && scalar !in 0xD800..0xDFFF) {
                String(Character.toChars(scalar.toInt()))
            } else {
                named[name] ?: match.value
            }
        }
}
