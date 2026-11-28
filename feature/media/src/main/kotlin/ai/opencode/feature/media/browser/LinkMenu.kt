package ai.opencode.feature.media

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.SessionLink
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Point
import android.view.View
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextPainter
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * What holding a web link in a reply offers (Markdown links, bare URLs, and link cards): Open in
 * Browser, Open in App, Copy Link, and Share…; a tap still opens it as before. With the In-App
 * Browser on, a public page also offers Open in App Browser first, since a tap tries the app that
 * claims the link before the browser. TalkBack gets the same as custom actions. Reads “Open in
 * Browser”: it opens the default browser.
 */
enum class LinkMenuAction(val rawValue: String) {
    OpenInAppBrowser("openInAppBrowser"),
    OpenInSafari("openInSafari"),
    OpenInApp("openInApp"),
    Copy("copy"),
    Share("share"),
    /** A session ID in a reply ([SessionLink]): TalkBack's open action. */
    OpenSession("openSession"),
    CopySessionID("copySessionID");

    val title: String
        get() = when (this) {
            OpenInAppBrowser -> localized(R.string.media_link_open_in_app_browser)
            OpenInSafari -> localized(R.string.media_link_open_in_browser)
            OpenInApp -> localized(R.string.media_link_open_in_app)
            Copy -> localized(R.string.media_link_copy)
            Share -> localized(R.string.media_link_share)
            OpenSession -> localized(R.string.media_link_open_session)
            CopySessionID -> localized(R.string.media_link_copy_session_id)
        }

    val systemImage: String
        get() = when (this) {
            OpenInAppBrowser -> "globe"
            OpenInSafari -> "safari"
            OpenInApp -> "arrow.up.forward.app"
            Copy -> "doc.on.doc"
            Share -> "square.and.arrow.up"
            OpenSession -> "arrow.up.right.square"
            CopySessionID -> "doc.on.doc"
        }

    companion object {
        /** Only web links and valid pairing links get the menu; file links keep opening the viewer. */
        fun offers(url: String): Boolean {
            if (SessionLink.id(url) != null) return true
            if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
                return OpenCodePairingLink.shareableURL(url) != null
            }
            val scheme = url.substringBefore(':', "").lowercase()
            return scheme == "http" || scheme == "https"
        }

        /** What holding [url] offers, in order. */
        fun actions(url: String, experimental: ExperimentalFeatures): List<LinkMenuAction> {
            if (SessionLink.id(url) != null) return listOf(CopySessionID)
            if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
                // Never offer Open in Browser, Open in App, or Open in App Browser for a single-use pairing URL.
                return if (OpenCodePairingLink.shareableURL(url) != null) listOf(Copy, Share) else emptyList()
            }
            val publicWebPage = BrowserLinkPolicy.isPublicWebPage(url)
            val appBrowser = experimental(ai.opencode.core.sharing.ExperimentalFeature.inAppBrowser) && publicWebPage
            return entries.filter { action ->
                when (action) {
                    OpenInAppBrowser -> appBrowser
                    OpenInApp -> publicWebPage
                    OpenInSafari, Copy, Share -> true
                    OpenSession, CopySessionID -> false
                }
            }
        }
    }
}

/**
 * A link tap asking the system whether an app claims it: one tap, from one session. Its answer
 * opens the fallback only while it is still the latest.
 */
data class LinkProbe(
    val id: java.util.UUID,
    val url: String,
    val sessionID: String?,
)

/**
 * Hands a web link to the installed app that claims it (GitHub, X, YouTube…), for Open Links in
 * Apps. Opens nothing when no app claims it, so the caller falls back to the browser.
 */
class UniversalLinkClient(
    /** Whether an app took the link. */
    val open: suspend (String) -> Boolean,
) {
    companion object Key : DependencyKey<UniversalLinkClient> {
        override val liveValue: UniversalLinkClient by lazy {
            UniversalLinkClient { url ->
                // Lab runs stand in for an installed app: `all` claims every link, `none` none.
                when (labUniversalLinks()) {
                    "all" -> return@UniversalLinkClient true
                    "none" -> return@UniversalLinkClient false
                }
                withContext(Dispatchers.Main) {
                    val context = dependency(AppContext)
                    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
                    try {
                        context.startActivity(intent)
                        true
                    } catch (_: ActivityNotFoundException) {
                        false
                    }
                }
            }
        }
        override val testValue = UniversalLinkClient {
            throw AssertionError("Unimplemented: UniversalLinkClient.open")
        }

        /** Debuggable builds only. */
        private fun labUniversalLinks(): String? {
            val context = runCatching { dependency(AppContext) }.getOrNull() ?: return null
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
            return System.getenv("OPENCODE_LAB_UNIVERSAL_LINKS") ?: runCatching {
                @Suppress("PrivateApi")
                Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, "debug.opencode.lab_universal_links") as? String
            }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * Opens a URL in the system: [open] hands it to whatever claims it (an app or the browser);
 * [openInBrowser] is “Open in Browser”: the default browser as a Custom Tab. Both return whether
 * something took it. Lives here because no lower module has a URL opener yet; the session reducer
 * reads it too.
 */
class OpenURLClient(
    val open: suspend (String) -> Boolean,
    val openInBrowser: suspend (String) -> Boolean = open,
) {
    companion object Key : DependencyKey<OpenURLClient> {
        override val liveValue: OpenURLClient by lazy {
            OpenURLClient(
                open = { url ->
                    withContext(Dispatchers.Main) {
                        val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            dependency(AppContext).startActivity(intent)
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    }
                },
                openInBrowser = { url ->
                    withContext(Dispatchers.Main) {
                        val context = dependency(AppContext)
                        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
                        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            tab.launchUrl(context, url.toUri())
                            true
                        } catch (_: ActivityNotFoundException) {
                            false
                        }
                    }
                },
            )
        }
        override val testValue = OpenURLClient(open = { throw AssertionError("Unimplemented: OpenURLClient.open") })
    }
}

/**
 * Presents the share sheet for a link: `ACTION_SEND` through the system chooser. Pairing links
 * share only their shareable form.
 */
class LinkShareSheet(val present: (String) -> Unit) {
    companion object Key : DependencyKey<LinkShareSheet> {
        override val liveValue: LinkShareSheet by lazy {
            LinkShareSheet { url ->
                val shareURL = OpenCodePairingLink.shareableURL(url) ?: return@LinkShareSheet
                val context = dependency(AppContext)
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareURL)
                val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(chooser)
                } catch (_: ActivityNotFoundException) {
                }
            }
        }
        override val testValue = LinkShareSheet {}
    }
}

/**
 * How a row's links run their menu: set by the transcript row ([LocalLinkMenu]), read by the text
 * overlay and the link cards.
 */
class LinkMenuHandler(val perform: (LinkMenuAction, String) -> Unit) {
    /** Read as the menu opens, so it follows the Experimental switches. */
    fun actions(url: String): List<LinkMenuAction> = LinkMenuAction.actions(url, dependency(ExperimentalFeatures))

    /** A session link also opens from TalkBack's actions, named for its session. */
    fun accessibilityActions(url: String): List<LinkMenuAction> =
        (if (SessionLink.id(url) != null) listOf(LinkMenuAction.OpenSession) else emptyList()) + actions(url)
}

val LocalLinkMenu = staticCompositionLocalOf<LinkMenuHandler?> { null }

/** The most custom actions one node may carry: past it Android throws as the tree is read (VoiceOver has no cap). */
const val MAX_CUSTOM_ACCESSIBILITY_ACTIONS = 32

/** [this], cut to what one node may carry ([MAX_CUSTOM_ACCESSIBILITY_ACTIONS]). */
fun List<CustomAccessibilityAction>.withinNodeLimit(): List<CustomAccessibilityAction> =
    if (size <= MAX_CUSTOM_ACCESSIBILITY_ACTIONS) this else take(MAX_CUSTOM_ACCESSIBILITY_ACTIONS)

object LinkMenuText {
    /** The text's web links, once each, in order. */
    fun webLinks(text: AnnotatedString): List<String> {
        val seen = HashSet<String>()
        return linkRanges(text).mapNotNull { (_, url) -> url.takeIf { LinkMenuAction.offers(it) && seen.add(it) } }
    }

    fun displayHost(url: String): String =
        OpenCodePairingLink.decode(url)?.server?.host
            ?: BrowserLinkPolicy.displayHost(url)
            ?: url

    /**
     * One link: the menu's own titles. Several: each names its host, and a host that several
     * links share also names what tells each apart ([accessibilityTarget]). [linkText] is a link's
     * own text in the reply.
     */
    fun accessibilityName(
        action: LinkMenuAction,
        url: String,
        among: List<String>,
        linkText: (String) -> String? = { null },
    ): String {
        val id = SessionLink.id(url)
        if (id != null) {
            val name = SessionLink.title(url) ?: SessionLink.shortID(id)
            return when (action) {
                LinkMenuAction.CopySessionID -> if (among.size > 1) localized(R.string.media_link_copy_session_id_of_description, name) else localized(R.string.media_link_copy_session_id)
                else -> localized(R.string.media_link_open_session_named_description, name)
            }
        }
        if (among.size <= 1) return action.title.replace("…", "")
        val host = accessibilityTarget(url, among, linkText)
        return when (action) {
            LinkMenuAction.OpenInAppBrowser -> localized(R.string.media_link_open_host_in_app_browser_description, host)
            LinkMenuAction.OpenInSafari -> localized(R.string.media_link_open_host_in_browser_description, host)
            LinkMenuAction.OpenInApp -> localized(R.string.media_link_open_host_in_app_description, host)
            LinkMenuAction.Copy -> localized(R.string.media_link_copy_host_description, host)
            LinkMenuAction.Share -> localized(R.string.media_link_share_host_description, host)
            LinkMenuAction.OpenSession, LinkMenuAction.CopySessionID -> action.title
        }
    }

    /**
     * What names a web link among the text's links: its host, or, when another link has the same
     * host, also what tells it apart: its own text if that isn't its address (“docs on
     * example.com”), else its path (“example.net/n-3”), else its place (“example.net link 3”).
     * Android-only: TalkBack shows one of several actions with the same label, VoiceOver all.
     */
    fun accessibilityTarget(url: String, among: List<String>, linkText: (String) -> String? = { null }): String {
        val host = displayHost(url)
        if (SessionLink.id(url) != null) return host
        val sameHost = among.filter { SessionLink.id(it) == null && displayHost(it) == host }
        if (sameHost.size <= 1) return host
        fun ownText(link: String): String? {
            val text = linkText(link)?.filterNot { it in invisibleLinkChars }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // A bare URL shows its own address (scheme dropped, maybe shortened): that names nothing new.
            val bare = text.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
            return text.takeUnless { bare.startsWith(host.lowercase().removePrefix("www.")) }
        }
        ownText(url)?.let { text -> if (sameHost.count { ownText(it) == text } == 1) return localized(R.string.media_link_text_on_host_description, text, host) }
        path(url)?.let { path -> if (sameHost.count { path(it) == path } == 1) return "$host$path" }
        return localized(R.string.media_link_host_numbered_description, host, among.indexOf(url) + 1)
    }

    /** [url]'s path and query, without a trailing slash; null when it has none. */
    private fun path(url: String): String? {
        val rest = url.substringAfter("://", "").substringBefore('#')
        val slash = rest.indexOfAny(charArrayOf('/', '?')).takeIf { it >= 0 } ?: return null
        return rest.substring(slash).trimEnd('/').takeIf { it.isNotEmpty() }
    }

    /** The word joiner and object placeholders a link's inline icon slot adds to its text. */
    private val invisibleLinkChars = setOf('\u2060', '\uFFFC', '\uFFFD', '\u200B')

    /** TalkBack's name for the action that lists the links whose actions don't fit on the text. */
    val moreLinks: String
        get() = localized(R.string.media_link_more_links_description)

    /** What the text's links give TalkBack: [actions] on the text, and the links behind [moreLinks]. */
    data class AccessibilityActions(val actions: List<Pair<LinkMenuAction, String>>, val more: List<String>)

    /**
     * Every link's actions, in order. Android allows one node only
     * [MAX_CUSTOM_ACCESSIBILITY_ACTIONS], less the [reserved] slots the caller's own actions
     * (Quote) take, so when they don't all fit, the first links keep all of theirs and the rest are
     * listed behind one [moreLinks] action, whose chooser opens a link's own menu.
     */
    fun accessibilityActions(
        links: List<String>,
        actionsFor: (String) -> List<LinkMenuAction>,
        reserved: Int = 0,
    ): AccessibilityActions {
        val limit = MAX_CUSTOM_ACCESSIBILITY_ACTIONS - reserved
        val perLink = links.map { url -> url to actionsFor(url) }
        val all = perLink.flatMap { (url, actions) -> actions.map { it to url } }
        if (all.size <= limit) return AccessibilityActions(all, emptyList())
        val kept = ArrayList<Pair<LinkMenuAction, String>>()
        var cut = perLink.size
        for ((index, entry) in perLink.withIndex()) {
            val (url, actions) = entry
            // One slot stays for More Links.
            if (kept.size + actions.size > limit - 1) {
                cut = index
                break
            }
            actions.mapTo(kept) { it to url }
        }
        return AccessibilityActions(kept, perLink.drop(cut).map { it.first })
    }

    /**
     * The text's TalkBack actions on its one node: the links' ([accessibilityActions]), More Links
     * when they don't all fit, then the caller's [trailing] ones, never more than
     * [MAX_CUSTOM_ACCESSIBILITY_ACTIONS].
     */
    fun accessibilityCustomActions(
        links: List<String>,
        actionsFor: (String) -> List<LinkMenuAction>,
        linkText: (String) -> String?,
        trailing: List<CustomAccessibilityAction>,
        perform: (LinkMenuAction, String) -> Unit,
        showMore: (List<String>) -> Unit,
    ): List<CustomAccessibilityAction> {
        val plan = accessibilityActions(links, actionsFor, reserved = trailing.size)
        return buildList {
            for ((action, url) in plan.actions) {
                add(CustomAccessibilityAction(accessibilityName(action, url, links, linkText)) { perform(action, url); true })
            }
            if (plan.more.isNotEmpty()) add(CustomAccessibilityAction(moreLinks) { showMore(plan.more); true })
            addAll(trailing)
        }.withinNodeLimit()
    }

    /** A link's row in the More Links chooser: a session's name, or the link named as its actions name it. */
    fun chooserTitle(url: String, among: List<String>, linkText: (String) -> String? = { null }): String {
        val id = SessionLink.id(url) ?: return accessibilityTarget(url, among, linkText)
        return SessionLink.title(url) ?: SessionLink.shortID(id)
    }

    /** Each link's own text in [text], by URL (its first run). */
    fun linkTexts(text: AnnotatedString, ranges: List<Pair<IntRange, String>> = linkRanges(text)): Map<String, String> {
        val texts = LinkedHashMap<String, String>()
        for ((range, url) in ranges) {
            if (url in texts) continue
            val end = (range.last + 1).coerceAtMost(text.length)
            if (range.first in 0 until end) texts[url] = text.text.substring(range.first, end)
        }
        return texts
    }

    /**
     * Every link run in [text] (`LinkAnnotation.Url`), in order, with adjacent runs of the same
     * URL joined: the rest of a link whose first character carries its icon slot is the same link.
     */
    fun linkRanges(text: AnnotatedString): List<Pair<IntRange, String>> {
        val runs = text.getLinkAnnotations(0, text.length)
            .mapNotNull { range -> (range.item as? LinkAnnotation.Url)?.let { (range.start until range.end) to it.url } }
            .sortedBy { it.first.first }
        val joined = ArrayList<Pair<IntRange, String>>()
        for ((range, url) in runs) {
            val last = joined.lastOrNull()
            if (last != null && last.second == url && last.first.last + 1 >= range.first) {
                joined[joined.lastIndex] = (last.first.first..maxOf(last.first.last, range.last)) to url
            } else {
                joined += range to url
            }
        }
        return joined
    }
}

/**
 * Finds which link, if any, is under a point of laid-out text. [links] and [fileLinks] are
 * character ranges in the laid-out text.
 */
class LinkHitLayout(
    private val layout: TextLayoutResult,
    private val links: List<Pair<IntRange, String>>,
    private val fileLinks: List<Pair<IntRange, AgentFileReference>> = emptyList(),
    /** Touch slop around each line rect, in pixels. */
    private val slopX: Float,
    private val slopY: Float,
) {
    data class Hit<T>(val value: T, val rects: List<Rect>)

    /** The link under [point], with the rects it covers, allowing a little slop. */
    fun link(point: Offset): Hit<String>? = hit(links, point)

    /** The inline `opencode-file://` link under [point], with the rects it covers. */
    fun fileLink(point: Offset): Hit<AgentFileReference>? = hit(fileLinks, point)

    /** The em session chips are sized by: the text's base font size. */
    val sessionChipEm: Float
        get() {
            val size = layout.layoutInput.style.fontSize
            return with(layout.layoutInput.density) { if (size.isSpecified) size.toPx() else 17.sp.toPx() }
        }

    /**
     * The session chip under [point]: its box on each line it covers, as the transcript's chip
     * renderer draws it, for the pressed highlight.
     */
    fun sessionChipRects(point: Offset): List<Rect>? {
        val rects = hit(links, point) { SessionLink.id(it) != null }?.rects ?: return null
        val em = sessionChipEm
        return rects.map { rect ->
            val line = layout.getLineForVerticalPosition(rect.center.y)
            SessionChipMetrics.box(rect.left, rect.right, layout.getLineBaseline(line), em)
        }
    }

    private fun <T> hit(items: List<Pair<IntRange, T>>, point: Offset, predicate: (T) -> Boolean = { true }): Hit<T>? {
        val near = layout.getLineForVerticalPosition(point.y - slopY) - 1..layout.getLineForVerticalPosition(point.y + slopY)
        for ((range, value) in items) {
            if (!predicate(value)) continue
            if (rects(range, leadingBelow = true, near).any { Rect(it.left - slopX, it.top - slopY, it.right + slopX, it.bottom + slopY).contains(point) }) {
                return Hit(value, rects(range))
            }
        }
        return null
    }

    /** One rect per line the range covers (TextKit's enclosing rects). */
    fun rects(range: IntRange): List<Rect> = rects(range, leadingBelow = false)

    /**
     * With [leadingBelow], each line's rect starts at its glyphs' top and carries the leading down
     * to the next line's glyphs, as TextKit's line fragments do. Compose spreads the leading mostly
     * above each line, so the slop around a wrapped link's second line reached into the words of
     * the line above. The text's first line is trimmed to its glyphs, so its baseline
     * gives the ascent.
     */
    private fun rects(range: IntRange, leadingBelow: Boolean, lines: IntRange = 0 until layout.lineCount): List<Rect> {
        val textLength = layout.layoutInput.text.length
        val start = range.first.coerceIn(0, textLength)
        val end = (range.last + 1).coerceIn(start, textLength)
        if (start >= end) return emptyList()
        val result = ArrayList<Rect>()
        val firstLine = maxOf(layout.getLineForOffset(start), lines.first)
        val lastLine = minOf(layout.getLineForOffset(end - 1), lines.last)
        for (line in firstLine..lastLine) {
            val lineStart = maxOf(start, layout.getLineStart(line))
            val lineEnd = minOf(end, layout.getLineEnd(line, visibleEnd = true))
            if (lineStart >= lineEnd) continue
            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (offset in lineStart until lineEnd) {
                val box = layout.getBoundingBox(offset)
                left = minOf(left, box.left)
                right = maxOf(right, box.right)
            }
            result += if (leadingBelow) {
                val bottom = if (line + 1 < layout.lineCount) maxOf(layout.getLineBottom(line), glyphTop(line + 1)) else layout.getLineBottom(line)
                Rect(left, glyphTop(line), right, bottom)
            } else {
                Rect(left, layout.getLineTop(line), right, layout.getLineBottom(line))
            }
        }
        return result
    }

    private val ascent: Float get() = layout.getLineBaseline(0) - layout.getLineTop(0)

    private fun glyphTop(line: Int): Float = maxOf(layout.getLineTop(line), layout.getLineBaseline(line) - ascent)
}

/**
 * Holding a web link in [text] opens the link menu with a preview of the link; tapping it still
 * opens it as before ([onOpen], the row's link opener). Touches anywhere else go to the text
 * underneath (selection included). TalkBack gets the menu's actions on the text; when they pass
 * Android's 32-per-node cap, the rest of the links sit behind More Links, a chooser that opens a
 * link's menu ([LinkMenuText.accessibilityActions]; Compose's own link nodes take no custom
 * actions). Wrap the text's `Text` in it and pass its latest [TextLayoutResult].
 */
@Composable
fun LinkMenu(
    text: AnnotatedString,
    textLayout: () -> TextLayoutResult?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The text's own TalkBack actions (Quote), on the same node after the links' (only when it has links). */
    trailingActions: List<CustomAccessibilityAction> = emptyList(),
    content: @Composable () -> Unit,
) {
    val handler = LocalLinkMenu.current
    val ranges = remember(text) { LinkMenuText.linkRanges(text) }
    val webRanges = remember(ranges) { ranges.filter { LinkMenuAction.offers(it.second) } }
    val links = remember(webRanges) { webRanges.map { it.second }.distinct() }
    val linkTexts = remember(text, webRanges) { LinkMenuText.linkTexts(text, webRanges) }
    if (handler == null || links.isEmpty()) {
        Box(modifier) { content() }
        return
    }
    val density = LocalDensity.current
    val view = LocalView.current
    val slopX = with(density) { 6.dp.toPx() }
    val slopY = with(density) { 5.dp.toPx() }
    var menu by remember { mutableStateOf<LinkHitLayout.Hit<String>?>(null) }
    var chooser by remember { mutableStateOf<List<String>?>(null) }
    var pressed by remember { mutableStateOf<Path?>(null) }
    val pressAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val reduceMotion = rememberReduceMotion()
    val colors = OpenCodeTheme.colors
    val pressColor = colors.text.copy(alpha = if (colors.isDark) 0.16f else 0.1f)
    val liftFill = MaterialTheme.colorScheme.surface
    HapticEffect(Haptic.threshold, menu?.value) { old, new -> old == null && new != null }
    val accessibilityActions = remember(links, linkTexts, trailingActions, handler) {
        LinkMenuText.accessibilityCustomActions(
            links,
            handler::accessibilityActions,
            linkTexts::get,
            trailingActions,
            perform = handler.perform,
            showMore = { chooser = it },
        )
    }
    val accessibilitySemantics: SemanticsPropertyReceiver.() -> Unit = remember(accessibilityActions) {
        { customActions = accessibilityActions }
    }

    Box(
        modifier
            // Remembered, so a streaming paragraph's chunks leave the node's semantics alone.
            .semantics(properties = accessibilitySemantics)
            // A session chip darkens while a finger is on it: over the text.
            .drawWithContent {
                drawContent()
                val alpha = pressAlpha.value
                val path = pressed
                if (alpha <= 0f || path == null) return@drawWithContent
                drawPath(path, pressColor.copy(alpha = pressColor.alpha * alpha))
            }
            .pointerInput(webRanges, handler, onOpen, liftFill) {
                awaitEachGesture {
                    // Only a link's own touches; everything else reaches the text.
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val layout = textLayout() ?: return@awaitEachGesture
                    val hits = LinkHitLayout(layout, webRanges, slopX = slopX, slopY = slopY)
                    val hit = hits.link(down.position) ?: return@awaitEachGesture
                    down.consume()
                    hits.sessionChipRects(down.position)?.let { boxes ->
                        val em = hits.sessionChipEm
                        pressed = Path().apply { for (box in boxes) addPath(SessionChipMetrics.shape(box, em)) }
                        scope.launch { pressAlpha.snapTo(1f) }
                    }
                    val slop = viewConfiguration.touchSlop
                    // A tap opens; moving first leaves the touch to the list's scroll; holding opens the menu.
                    val tapped: Boolean? = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var result: Boolean? = null
                        while (result == null) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            result = when {
                                change == null -> false
                                !change.pressed -> { change.consume(); true }
                                change.isConsumed || (change.position - down.position).getDistance() > slop -> false
                                else -> null
                            }
                        }
                        result
                    }
                    when (tapped) {
                        true -> onOpen(hit.value)
                        false -> Unit
                        null -> {
                            menu = hit
                            // Held: the rest of the touch belongs to the menu, until the finger
                            // moves and drags the link out instead.
                            val drag = linkDrag(hit, layout)
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                event.changes.forEach { it.consume() }
                                if (change == null || !change.pressed) break
                                if (!dragged && drag != null && (change.position - down.position).getDistance() > slop) {
                                    dragged = true
                                    menu = null
                                    drag.start(view, change.position, liftFill)
                                }
                            }
                        }
                    }
                    if (pressAlpha.value > 0f) scope.launch { pressAlpha.animateTo(0f, Motion.swap.animation(MotionPhase.Exit, reduceMotion)) }
                }
            },
    ) {
        content()
        chooser?.let { more ->
            DropdownMenu(expanded = true, onDismissRequest = { chooser = null }) {
                for (url in more) {
                    DropdownMenuItem(
                        text = { Text(LinkMenuText.chooserTitle(url, links, linkTexts::get), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = {
                            OpenCodeIcon(if (SessionLink.id(url) == null) "globe" else "bubble.left.and.text.bubble.right", contentDescription = null)
                        },
                        onClick = {
                            chooser = null
                            val layout = textLayout()
                            val range = webRanges.firstOrNull { it.second == url }?.first
                            val rects = if (layout != null && range != null) LinkHitLayout(layout, webRanges, slopX = slopX, slopY = slopY).rects(range) else emptyList()
                            menu = LinkHitLayout.Hit(url, rects)
                        },
                    )
                }
            }
        }
        menu?.let { hit ->
            val anchor = hit.rects.firstOrNull() ?: Rect.Zero
            Box(
                Modifier
                    .offset { IntOffset(anchor.left.roundToInt(), anchor.top.roundToInt()) }
                    .size(with(density) { anchor.width.toDp() }, with(density) { anchor.height.toDp() }),
            ) {
                LinkMenuPopup(
                    url = hit.value,
                    actions = handler.actions(hit.value),
                    expanded = true,
                    onAction = { action ->
                        menu = null
                        handler.perform(action, hit.value)
                    },
                    onOpen = {
                        menu = null
                        onOpen(hit.value)
                    },
                    onDismiss = { menu = null },
                )
            }
        }
    }
}

/**
 * The chip's box around a run of text, shared by the transcript's chip renderer and the pressed
 * highlight ([LinkHitLayout.sessionChipRects]) so both agree. Lives here, below the transcript, so
 * the transcript's renderer can adopt it.
 */
object SessionChipMetrics {
    /** SF's ascent plus descent, in ems. */
    const val lineEms = 1.178f
    /** The chip's height and how far its centre sits above the baseline, in ems. */
    const val height = 1.16f
    const val rise = 0.31f
    /** The chip's fill opacity. */
    const val fill = 0.08f
    /** The chip's corner radius at body size (17 pt); it scales with the text. */
    const val radius = 4.5f

    /** The corner radius for text whose em is [em]. */
    fun cornerRadius(em: Float): Float = radius * em / 17f

    fun box(minX: Float, maxX: Float, baseline: Float, em: Float): Rect {
        val h = em * height
        val centre = baseline - em * rise
        return Rect(minX, centre - h / 2, maxX, centre + h / 2)
    }

    /** Rounded corners, the radius scaled with the text. */
    fun shape(box: Rect, em: Float): Path {
        val r = minOf(cornerRadius(em), box.height / 2)
        return Path().apply { addRoundRect(RoundRect(box, CornerRadius(r, r))) }
    }
}

/** A held web link's drag: its URL out to other apps, lifting the link's own lines. */
private class LinkDrag(
    private val url: String,
    private val layout: TextLayoutResult,
    private val rects: List<Rect>,
) {
    fun start(view: View, at: Offset, fill: Color) {
        val clip = MediaDragPayload.Url(url).clipData(view.context) ?: return
        markDragBegin()
        view.startDragAndDrop(clip, LinkDragShadow(layout, rects, at, fill, view.resources.displayMetrics.density), null, View.DRAG_FLAG_GLOBAL)
    }
}

/** Session links don't drag. */
private fun linkDrag(hit: LinkHitLayout.Hit<String>, layout: TextLayoutResult): LinkDrag? =
    if (SessionLink.id(hit.value) != null || hit.rects.isEmpty()) null else LinkDrag(hit.value, layout, hit.rects)

/**
 * The lifted link: each line it covers, a little outset (4 × 2 points), on the surface, with the
 * link's text drawn in it.
 */
private class LinkDragShadow(
    private val layout: TextLayoutResult,
    rects: List<Rect>,
    private val touch: Offset,
    private val fill: Color,
    density: Float,
) : View.DragShadowBuilder() {
    private val lines = rects.map { Rect(it.left - 4 * density, it.top - 2 * density, it.right + 4 * density, it.bottom + 2 * density) }
    private val bounds = lines.reduce { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
    private val corner = 4 * density

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(bounds.width.roundToInt().coerceAtLeast(1), bounds.height.roundToInt().coerceAtLeast(1))
        outShadowTouchPoint.set(
            (touch.x - bounds.left).roundToInt().coerceIn(0, outShadowSize.x),
            (touch.y - bounds.top).roundToInt().coerceIn(0, outShadowSize.y),
        )
    }

    override fun onDrawShadow(canvas: android.graphics.Canvas) {
        val shape = Path().apply { for (line in lines) addRoundRect(RoundRect(line, CornerRadius(corner, corner))) }
        val compose = Canvas(canvas)
        compose.translate(-bounds.left, -bounds.top)
        compose.clipPath(shape)
        compose.drawPath(shape, Paint().apply { color = fill })
        TextPainter.paint(compose, layout)
    }
}

/**
 * The hold menu: the link's preview card over its actions. Anchor it in a box covering the held
 * link or card.
 */
@Composable
fun LinkMenuPopup(
    url: String,
    actions: List<LinkMenuAction>,
    expanded: Boolean,
    onAction: (LinkMenuAction) -> Unit,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        LinkPreviewCardContent(url, Modifier.clickable(onClick = onOpen))
        if (actions.isNotEmpty()) HorizontalDivider()
        for (action in actions) {
            DropdownMenuItem(
                text = { Text(action.title) },
                leadingIcon = { OpenCodeIcon(action.systemImage, contentDescription = null) },
                onClick = { onAction(action) },
            )
        }
    }
}

/**
 * The menu's preview: the site and the address, like a browser's link preview without loading the
 * page.
 */
@Composable
fun LinkPreviewCardContent(url: String, modifier: Modifier = Modifier) {
    val sessionID = SessionLink.id(url)
    Row(
        modifier
            .width(320.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(
                if (sessionID == null) "globe" else "bubble.left.and.text.bubble.right",
                contentDescription = null,
                size = 28.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (sessionID != null) {
                Text(SessionLink.title(url) ?: stringResource(R.string.media_link_session_fallback_title), style = OpenCodeTextStyles.headline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    sessionID,
                    style = OpenCodeTextStyles.footnote.copy(fontFamily = OpenCodeFonts.monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            } else {
                Text(LinkMenuText.displayHost(url), style = OpenCodeTextStyles.headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    url,
                    style = OpenCodeTextStyles.footnote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.weight(1f, fill = false))
    }
}
