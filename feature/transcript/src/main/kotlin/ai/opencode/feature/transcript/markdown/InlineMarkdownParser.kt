package ai.opencode.feature.transcript.markdown

/**
 * Inline Markdown as Foundation's
 * `AttributedString(markdown:options:.inlineOnlyPreservingWhitespace)` reads it. Android has no
 * counterpart, so this is a CommonMark inline parser (cmark-gfm's algorithm, which Foundation
 * wraps) limited to what that option interprets.
 *
 * - backslash escapes, entity and numeric character references;
 * - code spans (line endings become spaces, one padding space stripped);
 * - emphasis and strong emphasis by the delimiter-run rules (left/right flanking, `_` never
 *   intraword, the rule of three), and GFM `~strike~` / `~~strike~~` (runs of equal length);
 * - inline links and images `[text](dest "title")` with balanced parentheses and `<dest>`;
 *   images keep only their alt text (no block, so nothing to draw);
 * - autolinks `<https://…>` and `<name@host>`;
 * - GFM extended autolinks (cmark-gfm's autolink extension, which Foundation enables): bare
 *   `http://`, `https://` and `ftp://` URLs and `www.` addresses, outside link text, with the
 *   extension's domain check and trailing-punctuation trim (bare email addresses aren't linked);
 * - nothing else: no block syntax, no reference links, raw HTML stays text (as Foundation
 *   keeps it), whitespace and newlines are preserved as written.
 *
 * Single forward scan plus the delimiter-stack emphasis pass; runs per streamed delta.
 */
object InlineMarkdownParser {
    fun parse(text: String): InlineText {
        if (text.isEmpty()) return InlineText.empty
        if (!mayHaveMarkup(text)) return InlineText.plain(text)
        return Parser(text).run()
    }

    /** Plain prose (no character that can start inline syntax) parses to itself. */
    private fun mayHaveMarkup(text: String): Boolean {
        for (c in text) if (isSpecial(c)) return true
        return text.contains("://") || text.contains("www.")
    }

    private fun isSpecial(c: Char): Boolean = when (c) {
        '\\', '`', '*', '_', '~', '[', ']', '!', '<', '&' -> true
        else -> false
    }

    // MARK: Tree

    private const val ROOT = 0
    private const val TEXT = 1
    private const val CODE = 2
    private const val EMPH = 3
    private const val STRONG = 4
    private const val STRIKE = 5
    private const val LINK = 6
    private const val IMAGE = 7

    private class Node(val type: Int, var text: String = "") {
        var url: String? = null
        var parent: Node? = null
        var prev: Node? = null
        var next: Node? = null
        var first: Node? = null
        var last: Node? = null

        fun append(child: Node) {
            child.parent = this
            child.prev = last
            child.next = null
            if (last != null) last!!.next = child else first = child
            last = child
        }

        fun insertAfter(sibling: Node) {
            val parent = parent ?: return
            sibling.parent = parent
            sibling.prev = this
            sibling.next = next
            if (next != null) next!!.prev = sibling else parent.last = sibling
            next = sibling
        }

        fun unlink() {
            val parent = parent
            if (prev != null) prev!!.next = next else parent?.first = next
            if (next != null) next!!.prev = prev else parent?.last = prev
            this.parent = null
            prev = null
            next = null
        }
    }

    private class Delimiter(
        val node: Node,
        val char: Char,
        var count: Int,
        val original: Int,
        val canOpen: Boolean,
        val canClose: Boolean,
    ) {
        var prev: Delimiter? = null
        var next: Delimiter? = null
    }

    private class Bracket(val node: Node, val image: Boolean, val previousDelimiter: Delimiter?, val prev: Bracket?) {
        var active = true
    }

    private class Parser(val s: String) {
        val n = s.length
        val root = Node(ROOT)
        val buffer = StringBuilder()
        var pos = 0
        var delimiters: Delimiter? = null
        var brackets: Bracket? = null
        /** Backtick run lengths with no closer after some point, so none after any later point either. */
        val unclosedTicks = HashSet<Int>()

        fun run(): InlineText {
            while (pos < n) {
                when (s[pos]) {
                    '\\' -> backslash()
                    '`' -> backticks()
                    '*', '_', '~' -> delimiterRun()
                    '[' -> {
                        val node = textNode("[")
                        brackets = Bracket(node, image = false, previousDelimiter = delimiters, prev = brackets)
                        pos++
                    }
                    '!' -> if (pos + 1 < n && s[pos + 1] == '[') {
                        val node = textNode("![")
                        brackets = Bracket(node, image = true, previousDelimiter = delimiters, prev = brackets)
                        pos += 2
                    } else {
                        buffer.append('!')
                        pos++
                    }
                    ']' -> closeBracket()
                    '<' -> if (!autolink()) {
                        buffer.append('<')
                        pos++
                    }
                    '&' -> entity()
                    ':' -> if (!urlAutolink()) {
                        buffer.append(':')
                        pos++
                    }
                    'w' -> if (!wwwAutolink()) {
                        buffer.append('w')
                        pos++
                    }
                    else -> {
                        var end = pos + 1
                        while (end < n && !isSpecial(s[end]) && s[end] != ':' && s[end] != 'w') end++
                        buffer.append(s, pos, end)
                        pos = end
                    }
                }
            }
            flush()
            processEmphasis(null)
            val runs = ArrayList<InlineRun>()
            flatten(root, Style(), runs)
            return InlineText.coalesced(runs)
        }

        // MARK: Nodes

        fun flush() {
            if (buffer.isEmpty()) return
            val last = root.last
            if (last != null && last.type == TEXT && !isDelimiterNode(last)) last.text += buffer.toString()
            else root.append(Node(TEXT, buffer.toString()))
            buffer.setLength(0)
        }

        /** Delimiter and bracket text nodes stay separate so they can be trimmed or replaced. */
        private val markerNodes = HashSet<Node>()

        fun isDelimiterNode(node: Node): Boolean = node in markerNodes

        fun textNode(text: String): Node {
            flush()
            val node = Node(TEXT, text)
            markerNodes.add(node)
            root.append(node)
            return node
        }

        fun appendLiteral(text: String) {
            buffer.append(text)
        }

        // MARK: Escapes and references

        fun backslash() {
            if (pos + 1 < n && isAsciiPunctuation(s[pos + 1])) {
                buffer.append(s[pos + 1])
                pos += 2
            } else if (pos + 1 < n && s[pos + 1] == '\n') {
                // A hard line break: the newline stays, the backslash goes.
                buffer.append('\n')
                pos += 2
            } else {
                buffer.append('\\')
                pos++
            }
        }

        fun entity() {
            val decoded = decodeEntity(s, pos)
            if (decoded != null) {
                buffer.append(decoded.first)
                pos = decoded.second
            } else {
                buffer.append('&')
                pos++
            }
        }

        // MARK: Code spans

        fun backticks() {
            var end = pos
            while (end < n && s[end] == '`') end++
            val length = end - pos
            val close = if (length in unclosedTicks) null else closingTicks(end, length)
            if (close == null) {
                unclosedTicks.add(length)
                buffer.append(s, pos, end)
                pos = end
                return
            }
            var content = s.substring(end, close).replace('\n', ' ')
            if (content.length >= 2 && content[0] == ' ' && content[content.length - 1] == ' ' && content.any { it != ' ' }) {
                content = content.substring(1, content.length - 1)
            }
            flush()
            root.append(Node(CODE, content))
            pos = close + length
        }

        fun closingTicks(from: Int, length: Int): Int? {
            var i = from
            while (i < n) {
                if (s[i] != '`') {
                    i++
                    continue
                }
                var end = i
                while (end < n && s[end] == '`') end++
                if (end - i == length) return i
                i = end
            }
            return null
        }

        // MARK: Emphasis

        fun delimiterRun() {
            val c = s[pos]
            var end = pos
            while (end < n && s[end] == c) end++
            val count = end - pos
            if (c == '~' && count > 2) {
                buffer.append(s, pos, end)
                pos = end
                return
            }
            val before = if (pos == 0) '\n'.code else Character.codePointBefore(s, pos)
            val after = if (end >= n) '\n'.code else Character.codePointAt(s, end)
            val beforeSpace = isUnicodeWhitespace(before)
            val afterSpace = isUnicodeWhitespace(after)
            val beforePunct = isUnicodePunctuation(before)
            val afterPunct = isUnicodePunctuation(after)
            val left = !afterSpace && (!afterPunct || beforeSpace || beforePunct)
            val right = !beforeSpace && (!beforePunct || afterSpace || afterPunct)
            val canOpen: Boolean
            val canClose: Boolean
            if (c == '_') {
                canOpen = left && (!right || beforePunct)
                canClose = right && (!left || afterPunct)
            } else {
                canOpen = left
                canClose = right
            }
            val node = textNode(s.substring(pos, end))
            pos = end
            if (canOpen || canClose) {
                val delimiter = Delimiter(node, c, count, count, canOpen, canClose)
                delimiter.prev = delimiters
                delimiters?.next = delimiter
                delimiters = delimiter
            }
        }

        fun removeDelimiter(delimiter: Delimiter) {
            delimiter.prev?.next = delimiter.next
            if (delimiter.next != null) delimiter.next!!.prev = delimiter.prev else delimiters = delimiter.prev
            delimiter.prev = null
            delimiter.next = null
        }

        fun processEmphasis(stackBottom: Delimiter?) {
            // The first delimiter above the bottom.
            var closer = delimiters
            while (closer != null && closer.prev != stackBottom) closer = closer.prev
            if (closer === stackBottom) closer = null
            val openersBottom = HashMap<Int, Delimiter?>()
            while (closer != null) {
                if (!closer.canClose) {
                    closer = closer.next
                    continue
                }
                val key = if (closer.char == '~') 1000 + closer.count
                else closer.char.code * 8 + (if (closer.canOpen) 4 else 0) + closer.original % 3
                val bottom = if (openersBottom.containsKey(key)) openersBottom[key] else stackBottom
                var opener = closer.prev
                var found = false
                while (opener != null && opener !== stackBottom && opener !== bottom) {
                    if (opener.char == closer.char && opener.canOpen) {
                        if (closer.char == '~') {
                            if (opener.count == closer.count) {
                                found = true
                                break
                            }
                        } else {
                            val oddMatch = (closer.canOpen || opener.canClose) &&
                                (opener.original + closer.original) % 3 == 0 &&
                                !(opener.original % 3 == 0 && closer.original % 3 == 0)
                            if (!oddMatch) {
                                found = true
                                break
                            }
                        }
                    }
                    opener = opener.prev
                }
                if (found && opener != null) {
                    val use = if (closer.char == '~') closer.count else if (closer.count >= 2 && opener.count >= 2) 2 else 1
                    val type = if (closer.char == '~') STRIKE else if (use == 2) STRONG else EMPH
                    opener.count -= use
                    closer.count -= use
                    opener.node.text = opener.node.text.substring(0, opener.node.text.length - use)
                    closer.node.text = closer.node.text.substring(use)
                    val wrapper = Node(type)
                    var child = opener.node.next
                    while (child != null && child !== closer.node) {
                        val next = child.next
                        child.unlink()
                        wrapper.append(child)
                        child = next
                    }
                    opener.node.insertAfter(wrapper)
                    // Delimiters between the two are now inside the span: they can't match out of it.
                    var between = closer.prev
                    while (between != null && between !== opener) {
                        val prev = between.prev
                        removeDelimiter(between)
                        between = prev
                    }
                    if (opener.count == 0) {
                        opener.node.unlink()
                        removeDelimiter(opener)
                    }
                    if (closer.count == 0) {
                        val next = closer.next
                        closer.node.unlink()
                        removeDelimiter(closer)
                        closer = next
                    }
                } else {
                    openersBottom[key] = closer.prev
                    val next = closer.next
                    if (!closer.canOpen) removeDelimiter(closer)
                    closer = next
                }
            }
            while (delimiters != null && delimiters !== stackBottom) removeDelimiter(delimiters!!)
        }

        // MARK: Links

        fun closeBracket() {
            val opener = brackets
            if (opener == null) {
                appendLiteral("]")
                pos++
                return
            }
            if (!opener.active) {
                brackets = opener.prev
                appendLiteral("]")
                pos++
                return
            }
            val tail = inlineLinkTail(pos + 1)
            if (tail == null) {
                brackets = opener.prev
                appendLiteral("]")
                pos++
                return
            }
            flush()
            processEmphasis(opener.previousDelimiter)
            val link = Node(if (opener.image) IMAGE else LINK)
            link.url = tail.first
            var child = opener.node.next
            while (child != null) {
                val next = child.next
                child.unlink()
                link.append(child)
                child = next
            }
            opener.node.insertAfter(link)
            opener.node.unlink()
            brackets = opener.prev
            if (!opener.image) {
                // Links can't contain links.
                var earlier = brackets
                while (earlier != null) {
                    if (!earlier.image) earlier.active = false
                    earlier = earlier.prev
                }
            }
            pos = tail.second
        }

        /** `(destination "title")` starting at [start]: the URL and the index after `)`. */
        fun inlineLinkTail(start: Int): Pair<String?, Int>? {
            if (start >= n || s[start] != '(') return null
            var i = skipLinkSpace(start + 1) ?: return null
            val destination: String
            if (i < n && s[i] == '<') {
                var j = i + 1
                while (j < n && s[j] != '>' && s[j] != '\n' && s[j] != '<') {
                    j += if (s[j] == '\\' && j + 1 < n && isAsciiPunctuation(s[j + 1])) 2 else 1
                }
                if (j >= n || s[j] != '>') return null
                destination = s.substring(i + 1, j)
                i = j + 1
            } else {
                var depth = 0
                var j = i
                while (j < n) {
                    val c = s[j]
                    if (c == '\\' && j + 1 < n && isAsciiPunctuation(s[j + 1])) {
                        j += 2
                        continue
                    }
                    if (c == '(') {
                        depth++
                        if (depth > 32) return null
                    } else if (c == ')') {
                        if (depth == 0) break
                        depth--
                    } else if (c.code <= 0x20 || c.code == 0x7F) {
                        break
                    }
                    j++
                }
                if (depth != 0) return null
                destination = s.substring(i, j)
                i = j
            }
            val afterDestination = i
            i = skipLinkSpace(i) ?: return null
            if (i < n && i > afterDestination && (s[i] == '"' || s[i] == '\'' || s[i] == '(')) {
                val close = if (s[i] == '(') ')' else s[i]
                var j = i + 1
                while (j < n && s[j] != close) {
                    if (s[i] == '(' && s[j] == '(') return null
                    j += if (s[j] == '\\' && j + 1 < n && isAsciiPunctuation(s[j + 1])) 2 else 1
                }
                if (j >= n) return null
                i = skipLinkSpace(j + 1) ?: return null
            }
            if (i >= n || s[i] != ')') return null
            val url = urlString(unescape(destination))
            return url to i + 1
        }

        /** Spaces and tabs with at most one line ending; null when a blank line interrupts. */
        fun skipLinkSpace(start: Int): Int? {
            var i = start
            var newlines = 0
            while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n')) {
                if (s[i] == '\n') {
                    newlines++
                    if (newlines > 1) return null
                }
                i++
            }
            return i
        }

        fun autolink(): Boolean {
            val close = s.indexOf('>', pos + 1)
            if (close < 0) return false
            val body = s.substring(pos + 1, close)
            val url = when {
                isURIAutolink(body) -> urlString(body)
                emailAutolink.matches(body) -> "mailto:$body"
                else -> return false
            }
            flush()
            val link = Node(LINK)
            link.url = url
            link.append(Node(TEXT, body))
            root.append(link)
            pos = close + 1
            return true
        }

        // MARK: Extended autolinks (cmark-gfm extensions/autolink.c)

        /** `url_match` at the `:` of `scheme://`: the scheme's letters are the buffer's tail. */
        fun urlAutolink(): Boolean {
            if (brackets != null || n - pos < 4 || s[pos + 1] != '/' || s[pos + 2] != '/') return false
            var rewind = 0
            while (rewind < pos && isAsciiLetter(s[pos - rewind - 1])) rewind++
            val start = pos - rewind
            val scheme = s.substring(start, pos)
            if (!buffer.endsWith(scheme)) return false
            // `sd_autolink_issafe`: a known scheme, then an alphanumeric.
            if (scheme.lowercase() !in safeSchemes || pos + 3 >= n || !isAsciiAlnum(s[pos + 3])) return false
            val domain = checkDomain(pos + 3, allowShort = true)
            if (domain == 0) return false
            val end = autolinkDelimiter(pos, linkEnd(pos + 3 + domain))
            if (end <= pos) return false
            buffer.setLength(buffer.length - rewind)
            appendAutolink(s.substring(start, end), s.substring(start, end))
            pos = end
            return true
        }

        /** `www_match`: `www.` at the start, after whitespace, or after `*`, `_`, `~` or `(`. */
        fun wwwAutolink(): Boolean {
            if (brackets != null || !s.startsWith("www.", pos)) return false
            if (pos > 0 && s[pos - 1] !in "*_~(" && !isAsciiSpace(s[pos - 1])) return false
            val domain = checkDomain(pos, allowShort = false)
            if (domain == 0) return false
            val end = autolinkDelimiter(pos, linkEnd(pos + domain))
            if (end <= pos) return false
            val text = s.substring(pos, end)
            appendAutolink(text, "http://$text")
            pos = end
            return true
        }

        fun appendAutolink(text: String, url: String) {
            flush()
            val link = Node(LINK)
            link.url = urlString(url)
            link.append(Node(TEXT, text))
            root.append(link)
        }

        /** `check_domain`: the domain's length, or 0 when an underscore sits in its last two labels (or, unless [allowShort], it has no dot). */
        fun checkDomain(start: Int, allowShort: Boolean): Int {
            val size = n - start
            var np = 0
            var underscore1 = 0
            var underscore2 = 0
            var i = 1
            while (i < size - 1) {
                if (s[start + i] == '\\' && i < size - 2) i++
                val c = s[start + i]
                if (c == '_') {
                    underscore2++
                } else if (c == '.') {
                    underscore1 = underscore2
                    underscore2 = 0
                    np++
                } else if (!isHostChar(start + i) && c != '-') {
                    break
                }
                i++
            }
            if (underscore1 > 0 || underscore2 > 0) return 0
            return if (allowShort || np > 0) i else 0
        }

        /** Up to the next whitespace or `<`. */
        fun linkEnd(from: Int): Int {
            var end = from
            while (end < n && !isAsciiSpace(s[end]) && s[end] != '<') end++
            return end
        }

        /** `autolink_delim`: trims trailing punctuation, unbalanced `)` and a trailing entity from `[base, end)`; returns the new end. */
        fun autolinkDelimiter(base: Int, end: Int): Int {
            var linkEnd = end
            var opening = 0
            var closing = 0
            for (i in base until linkEnd) {
                when (s[i]) {
                    '<' -> {
                        linkEnd = i
                        break
                    }
                    '(' -> opening++
                    ')' -> closing++
                }
            }
            while (linkEnd > base) {
                when (s[linkEnd - 1]) {
                    ')' -> {
                        if (closing <= opening) return linkEnd
                        closing--
                        linkEnd--
                    }
                    '?', '!', '.', ',', ':', '*', '_', '~', '\'', '"' -> linkEnd--
                    ';' -> {
                        var newEnd = linkEnd - 2
                        while (newEnd > base && isAsciiLetter(s[newEnd])) newEnd--
                        linkEnd = if (newEnd < linkEnd - 2 && s[newEnd] == '&') newEnd else linkEnd - 1
                    }
                    else -> return linkEnd
                }
            }
            return linkEnd
        }

        /** `is_valid_hostchar`: neither whitespace nor punctuation. */
        fun isHostChar(index: Int): Boolean {
            val cp = s.codePointAt(index)
            return !isUnicodeWhitespace(cp) && !isUnicodePunctuation(cp)
        }

        // MARK: Flattening

        data class Style(
            val emphasized: Boolean = false,
            val strong: Boolean = false,
            val strikethrough: Boolean = false,
            val link: String? = null,
        )

        fun flatten(node: Node, style: Style, out: MutableList<InlineRun>) {
            var child = node.first
            while (child != null) {
                when (child.type) {
                    TEXT -> out.add(InlineRun(child.text, style.emphasized, style.strong, false, style.strikethrough, style.link))
                    CODE -> out.add(InlineRun(child.text, style.emphasized, style.strong, true, style.strikethrough, style.link))
                    EMPH -> flatten(child, style.copy(emphasized = true), out)
                    STRONG -> flatten(child, style.copy(strong = true), out)
                    STRIKE -> flatten(child, style.copy(strikethrough = true), out)
                    LINK -> flatten(child, style.copy(link = child.url ?: style.link), out)
                    IMAGE -> flatten(child, style, out)
                }
                child = child.next
            }
        }
    }

    // MARK: Character classes

    private val safeSchemes = setOf("http", "https", "ftp")

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun isAsciiAlnum(c: Char): Boolean = isAsciiLetter(c) || c in '0'..'9'

    /** `cmark_isspace`. */
    private fun isAsciiSpace(c: Char): Boolean = c == ' ' || c in '\t'..'\r'

    private fun isAsciiPunctuation(c: Char): Boolean =
        c in '!'..'/' || c in ':'..'@' || c in '['..'`' || c in '{'..'~'

    private fun isUnicodeWhitespace(cp: Int): Boolean =
        cp == ' '.code || cp == '\t'.code || cp == '\n'.code || cp == '\r'.code || cp == 0x0C ||
            Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isUnicodePunctuation(cp: Int): Boolean {
        if (cp < 0x80) return isAsciiPunctuation(cp.toChar())
        return when (Character.getType(cp)) {
            Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
    }

    private val emailAutolink = Regex(
        "[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*",
    )

    private fun isURIAutolink(body: String): Boolean {
        val colon = body.indexOf(':')
        if (colon < 2 || colon > 32) return false
        if (body[0] !in 'a'..'z' && body[0] !in 'A'..'Z') return false
        for (i in 1 until colon) {
            val c = body[i]
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '.' || c == '-')) return false
        }
        for (i in colon + 1 until body.length) {
            val c = body[i]
            if (c.code <= 0x20 || c == '<' || c == '>') return false
        }
        return true
    }

    /** Backslash escapes and entities resolved, as in a link destination. */
    private fun unescape(text: String): String {
        if (!text.contains('\\') && !text.contains('&')) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length && isAsciiPunctuation(text[i + 1])) {
                out.append(text[i + 1])
                i += 2
            } else if (c == '&') {
                val decoded = decodeEntity(text, i)
                if (decoded != null) {
                    out.append(decoded.first)
                    i = decoded.second
                } else {
                    out.append('&')
                    i++
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private const val URL_ALLOWED = "-._~:/?#[]@!$&'()*+,;=%"

    /**
     * The destination as `URL(string:)` keeps it, then `absoluteString`: characters a URL can't
     * hold are percent-encoded. Null when empty (no URL, so no link).
     */
    private fun urlString(destination: String): String? {
        if (destination.isEmpty()) return null
        var clean = true
        for (i in destination.indices) {
            val c = destination[i]
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in URL_ALLOWED) ||
                (c == '%' && !isPercentEscape(destination, i))
            ) {
                clean = false
                break
            }
        }
        if (clean) return destination
        val out = StringBuilder(destination.length + 8)
        val hex = "0123456789ABCDEF"
        var i = 0
        while (i < destination.length) {
            val c = destination[i]
            if ((c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in URL_ALLOWED) && (c != '%' || isPercentEscape(destination, i))) {
                out.append(c)
                i++
                continue
            }
            val end = if (Character.isHighSurrogate(c) && i + 1 < destination.length) i + 2 else i + 1
            for (byte in destination.substring(i, end).toByteArray(Charsets.UTF_8)) {
                val b = byte.toInt() and 0xFF
                out.append('%').append(hex[b shr 4]).append(hex[b and 0xF])
            }
            i = end
        }
        return out.toString()
    }

    private fun isPercentEscape(s: String, i: Int): Boolean =
        i + 2 < s.length && Character.digit(s[i + 1], 16) >= 0 && Character.digit(s[i + 2], 16) >= 0

    /** An entity or numeric character reference at [start] (`&`): its text and the index after `;`. */
    internal fun decodeEntity(s: String, start: Int): Pair<String, Int>? {
        var i = start + 1
        if (i >= s.length) return null
        if (s[i] == '#') {
            i++
            val hex = i < s.length && (s[i] == 'x' || s[i] == 'X')
            if (hex) i++
            val digitsStart = i
            while (i < s.length && i - digitsStart < 8 && Character.digit(s[i], if (hex) 16 else 10) >= 0) i++
            val digits = i - digitsStart
            if (digits == 0 || digits > (if (hex) 6 else 7) || i >= s.length || s[i] != ';') return null
            val value = s.substring(digitsStart, i).toInt(if (hex) 16 else 10)
            val cp = if (value == 0 || value > 0x10FFFF || value in 0xD800..0xDFFF) 0xFFFD else value
            return String(Character.toChars(cp)) to i + 1
        }
        val nameStart = i
        while (i < s.length && i - nameStart <= 32 && (s[i].isLetterOrDigit() && s[i].code < 0x80)) i++
        if (i == nameStart || i >= s.length || s[i] != ';') return null
        val value = HtmlEntities.named[s.substring(nameStart, i)] ?: return null
        return value to i + 1
    }
}
