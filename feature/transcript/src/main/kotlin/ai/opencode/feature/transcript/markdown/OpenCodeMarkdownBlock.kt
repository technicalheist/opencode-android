package ai.opencode.feature.transcript.markdown

import ai.opencode.feature.media.MemoryWarningCenter
import ai.opencode.feature.media.SyntaxHighlighter
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.SessionLink
import androidx.compose.runtime.Immutable

/** One rendered Markdown block. Inline text is [InlineText], parsed by [InlineMarkdownParser]. */
@Immutable
data class OpenCodeMarkdownBlock(val id: Int, val kind: Kind) {
    sealed interface Kind {
        data class Paragraph(val text: InlineText) : Kind
        data class NumberedItem(val marker: String, val text: InlineText) : Kind
        /** A bullet (`•`), a task box (`[ ]`, `[x]`) or a nested numbered item; `level` 0 is top level. */
        data class ListItem(val marker: String, val level: Int, val text: InlineText) : Kind
        data class Blockquote(val text: InlineText) : Kind
        data class Heading(val level: Int, val text: InlineText) : Kind
        data class Code(val code: HighlightedCode) : Kind
        data class Table(val header: List<InlineText>, val rows: List<List<InlineText>>) : Kind
        data class Image(val alt: String, val source: String) : Kind
        data class PullRequest(val title: String, val url: String) : Kind
        /** A thematic break (`---`, `***`, `___`). */
        data object Divider : Kind
        /** Cards for media and documents the agent pointed at. */
        data class Files(val files: List<AgentFileReference>) : Kind
        /** Display math (`$$…$$`, `\[…\]`), typeset natively. */
        data class Math(val math: MathBlock) : Kind
        /** A finished ```` ```mermaid ```` fence, drawn as a diagram. While its fence is still open it streams as code. */
        data class Mermaid(val source: String) : Kind
    }

    @Immutable
    data class MathBlock(val source: String, /** The lines as written, delimiters included, for copying. */ val raw: String)

    /**
     * A fenced block with its highlighted tokens. Equality is source and language only: the tokens
     * are derived from them. [reusing] continues a previous highlight of the same language whose
     * safe prefix this source still starts with.
     */
    @Immutable
    class HighlightedCode(val source: String, val language: String?, reusing: HighlightedCode? = null) {
        private val result: SyntaxHighlighter.Result = SyntaxHighlighter.highlightIncremental(
            source,
            language,
            previous = reusing?.takeIf { it.language == language }?.let {
                SyntaxHighlighter.Previous(it.source, it.result.safePrefixUTF8Count, it.result.safePrefixTokens)
            },
        )

        val tokens: List<SyntaxToken> get() = result.tokens
        val safePrefixUTF8Count: Int get() = result.safePrefixUTF8Count
        /** The safe prefix in UTF-16 chars, for reuse checks against Kotlin strings. */
        internal val safePrefixLength: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SyntaxHighlighter.utf16Offset(source, result.safePrefixUTF8Count) ?: 0
        }

        /** Whether [code] still starts with this highlight's safe prefix. */
        internal fun prefixMatches(code: String): Boolean =
            safePrefixUTF8Count > 0 && code.length >= safePrefixLength && code.regionMatches(0, source, 0, safePrefixLength)

        override fun equals(other: Any?): Boolean =
            other is HighlightedCode && other.source == source && other.language == language
        override fun hashCode(): Int = source.hashCode() * 31 + (language?.hashCode() ?: 0)
        override fun toString(): String = "HighlightedCode(language=$language, ${source.length} chars)"
    }

    /** The block back as Markdown, for copying a reply; null for blocks derived from text already in the reply. */
    val markdown: String?
        get() = when (val kind = kind) {
            is Kind.Paragraph -> markdown(kind.text)
            is Kind.NumberedItem -> "${kind.marker} ${markdown(kind.text)}"
            is Kind.ListItem -> "  ".repeat(kind.level) +
                (if (kind.marker == "•") "-" else if (kind.marker in taskMarkers) "- ${kind.marker}" else kind.marker) +
                " " + markdown(kind.text)
            is Kind.Blockquote -> markdown(kind.text).split('\n').joinToString("\n") { "> $it" }
            is Kind.Heading -> "#".repeat(kind.level) + " " + markdown(kind.text)
            is Kind.Code -> "```${kind.code.language ?: ""}\n${kind.code.source}\n```"
            is Kind.Table -> (listOf(kind.header.map(::markdown), kind.header.map { "---" }) + kind.rows.map { row -> row.map(::markdown) })
                .joinToString("\n") { "| " + it.joinToString(" | ") + " |" }
            is Kind.Image -> "![${kind.alt}](${kind.source})"
            Kind.Divider -> "---"
            is Kind.Math -> kind.math.raw
            is Kind.Mermaid -> "```mermaid\n${kind.source}\n```"
            // Derived from text that is already in the reply.
            is Kind.PullRequest, is Kind.Files -> null
        }

    /**
     * Inline parsing is a pure function of its source. Share only those results, not block
     * structure: a later character can still turn prose into a table. Keys are the exact source
     * (Kotlin string equality never folds Unicode), bounded by entry count and retained UTF-8
     * bytes, evicted oldest first. A memory warning empties all three tables.
     */
    class InlineCache(
        private val maximumEntries: Int = 512,
        private val maximumSourceBytes: Int = 128 * 1024,
        memoryWarnings: MemoryWarningCenter? = null,
    ) {
        init {
            require(maximumEntries >= 0 && maximumSourceBytes >= 0)
        }

        data class Entry(val attributed: InlineText, val cardFiles: List<AgentFileReference>)

        sealed interface SegmentItem {
            data class Block(val kind: Kind, val cardFiles: List<AgentFileReference>) : SegmentItem
            data class CardFiles(val files: List<AgentFileReference>) : SegmentItem
        }

        data class TableRowEntry(val cells: List<InlineText>, val cardFiles: List<AgentFileReference>)

        private class Table<V> {
            val values = LinkedHashMap<String, V>()
            val sizes = HashMap<String, Int>()
            var bytes = 0

            fun clear() {
                values.clear()
                sizes.clear()
                bytes = 0
            }

            fun insert(value: V, key: String, keyBytes: Int, maximumEntries: Int, maximumBytes: Int) {
                if (values.containsKey(key)) return
                while (values.isNotEmpty() && (values.size >= maximumEntries || bytes + keyBytes > maximumBytes)) {
                    val evicted = values.keys.first()
                    values.remove(evicted)
                    bytes -= sizes.remove(evicted) ?: 0
                }
                values[key] = value
                sizes[key] = keyBytes
                bytes += keyBytes
            }
        }

        private val lock = Any()
        private val inlines = Table<Entry>()
        private val segments = Table<List<SegmentItem>>()
        private val tableRows = Table<TableRowEntry>()

        init {
            memoryWarnings?.observe(::clear)
        }

        val entryCount: Int get() = synchronized(lock) { inlines.values.size }
        val retainedSourceBytes: Int get() = synchronized(lock) { inlines.bytes }
        val allEntryCount: Int get() = synchronized(lock) { inlines.values.size + segments.values.size + tableRows.values.size }

        fun clear() {
            synchronized(lock) {
                inlines.clear()
                segments.clear()
                tableRows.clear()
            }
        }

        fun parse(text: String): Entry {
            synchronized(lock) { inlines.values[text] }?.let { return it }
            val bytes = SwiftText.utf8Length(text)
            if (maximumEntries <= 0 || bytes > maximumSourceBytes) return uncachedInlineEntry(text)
            // Live projection and a row's lookup can miss simultaneously. Neither holds the lock
            // while parsing; a duplicate miss is harmless.
            val parsed = uncachedInlineEntry(text)
            synchronized(lock) { inlines.insert(parsed, text, bytes, maximumEntries, maximumSourceBytes) }
            return parsed
        }

        fun parseCompletedSegment(segment: String): List<SegmentItem> {
            synchronized(lock) { segments.values[segment] }?.let { return it }
            val bytes = SwiftText.utf8Length(segment)
            if (maximumEntries <= 0 || bytes > maximumSourceBytes) return parseTextSegment(segment, this)
            val parsed = parseTextSegment(segment, this)
            synchronized(lock) { segments.insert(parsed, segment, bytes, maximumEntries, maximumSourceBytes) }
            return parsed
        }

        fun parseTableRow(line: String): TableRowEntry {
            synchronized(lock) { tableRows.values[line] }?.let { return it }
            val bytes = SwiftText.utf8Length(line)
            if (maximumEntries <= 0 || bytes > maximumSourceBytes) return uncachedTableRow(line, this)
            val parsed = uncachedTableRow(line, this)
            synchronized(lock) { tableRows.insert(parsed, line, bytes, maximumEntries, maximumSourceBytes) }
            return parsed
        }

        companion object {
            /** The process-wide cache: pure parse results, no business state. */
            val shared: InlineCache = InlineCache(memoryWarnings = MemoryWarningCenter.default)
        }
    }

    companion object {
        /** List markers of open and done task items. */
        const val openTask = "[ ]"
        const val doneTask = "[x]"
        val taskMarkers: List<String> = listOf(openTask, doneTask)

        /** The blocks back as Markdown, for copying a reply. */
        fun markdown(blocks: List<OpenCodeMarkdownBlock>): String {
            val result = StringBuilder()
            var previousIsListItem = false
            for (block in blocks) {
                val markdown = block.markdown ?: continue
                val isListItem = block.kind is Kind.NumberedItem || block.kind is Kind.ListItem
                if (result.isNotEmpty()) result.append(if (previousIsListItem && isListItem) "\n" else "\n\n")
                result.append(markdown)
                previousIsListItem = isListItem
            }
            return result.toString()
        }

        /** Inline formatting back as Markdown: bold, italic, code, and links. */
        fun markdown(inline: InlineText): String {
            val result = StringBuilder()
            for (run in inline.runs) {
                var piece = run.text
                val math = MathSyntax.Delimiter.from(run.languageIdentifier)
                if (math != null) piece = math.open + piece + math.close
                if (run.code && math == null) {
                    piece = "`$piece`"
                } else {
                    if (run.emphasized) piece = "*$piece*"
                    if (run.strong) piece = "**$piece**"
                }
                val link = run.link
                if (link != null && AgentFileReference.fromLinkURL(link) == null && SessionLink.id(link) == null) piece = "[$piece]($link)"
                result.append(piece)
            }
            return result.toString()
        }

        /**
         * [text] with CRLF line endings as LF. Models sometimes stream `\r\n` (seen from
         * opencode/big-pickle on a live server), and lines here split on `\n` never inside `\r\n`,
         * so a reply's lists and paragraphs collapsed into one paragraph showing literal `- `
         * markers.
         */
        fun lineFeeds(text: String): String = if (text.indexOf('\r') < 0) text else text.replace("\r\n", "\n")

        /**
         * The blocks of [text]. [reusing] is the row's previous blocks: their code highlights
         * continue instead of starting over.
         */
        fun blocks(
            text: String,
            inlineCache: InlineCache? = InlineCache.shared,
            reusing: List<OpenCodeMarkdownBlock> = emptyList(),
        ): List<OpenCodeMarkdownBlock> {
            val text = lineFeeds(text)
            // The previous row already owns these values. No growing code cache or incremental
            // grammar state survives a stream or a navigation change.
            val previousCodes = reusing.mapNotNull { (it.kind as? Kind.Code)?.code }
            var codeOrdinal = 0
            val blocks = ArrayList<OpenCodeMarkdownBlock>()
            val seenFiles = HashSet<String>()
            val segments = codeFenceSegments(text)
            for ((index, segment) in segments.withIndex()) {
                if (index % 2 == 0) {
                    val items = if (index + 1 < segments.size && inlineCache != null) {
                        inlineCache.parseCompletedSegment(segment)
                    } else {
                        parseTextSegment(segment, inlineCache)
                    }
                    appendSegmentItems(items, blocks, seenFiles)
                } else {
                    val (language, code) = fence(segment)
                    if (code.isEmpty()) continue
                    // Only a closed fence is drawn; an open one is still streaming.
                    if (index + 1 < segments.size && language?.lowercase() == "mermaid") {
                        blocks.add(OpenCodeMarkdownBlock(blocks.size, Kind.Mermaid(code)))
                        continue
                    }
                    val currentOrdinal = codeOrdinal
                    codeOrdinal++
                    val atOrdinal = previousCodes.getOrNull(currentOrdinal)
                    val candidate = if (atOrdinal != null && atOrdinal.language == language &&
                        (atOrdinal.source == code || atOrdinal.prefixMatches(code))
                    ) {
                        atOrdinal
                    } else {
                        previousCodes.firstOrNull { it.language == language && it.source == code }
                            ?: previousCodes.lastOrNull { it.language == language && it.prefixMatches(code) }
                    }
                    val highlighted = if (candidate != null && candidate.source == code) candidate
                    else HighlightedCode(code, language, candidate)
                    blocks.add(OpenCodeMarkdownBlock(blocks.size, Kind.Code(highlighted)))
                }
            }
            return blocks
        }

        /** A fenced segment's language hint and trimmed source. */
        fun fence(segment: String): Pair<String?, String> {
            var newline = -1
            for (i in segment.indices) {
                if (segment[i] == '\n' && (i == 0 || segment[i - 1] != '\r')) {
                    newline = i
                    break
                }
            }
            if (newline < 0) return null to SwiftText.trimWhitespaceAndNewlines(segment)
            val hint = SwiftText.trimWhitespace(segment.substring(0, newline))
            return (hint.ifEmpty { null }) to SwiftText.trimWhitespaceAndNewlines(segment.substring(newline + 1))
        }

        private fun isTick(c: Char): Boolean = c == '`' || c == '\u1FEF' // U+1FEF GREEK VARIA is canonically "`".

        /**
         * The text split at every ```` ``` ````, on grapheme clusters, so a fence whose last
         * backtick carries a combining mark or joiner is not a fence.
         */
        fun codeFenceSegments(text: String): List<String> {
            val n = text.length
            var segments: ArrayList<String>? = null
            var segmentStart = 0
            var i = 0
            while (i + 2 < n) {
                if (isTick(text[i]) && isTick(text[i + 1]) && isTick(text[i + 2]) &&
                    !(i + 3 < n && SwiftText.extendsGrapheme(text[i + 3]))
                ) {
                    if (segments == null) segments = ArrayList()
                    segments.add(text.substring(segmentStart, i))
                    segmentStart = i + 3
                    i += 3
                    continue
                }
                i++
            }
            if (segments == null) return listOf(text)
            segments.add(text.substring(segmentStart))
            return segments
        }

        fun appendSegmentItems(items: List<InlineCache.SegmentItem>, blocks: MutableList<OpenCodeMarkdownBlock>, seenFiles: MutableSet<String>) {
            for (item in items) {
                val cardFiles = when (item) {
                    is InlineCache.SegmentItem.Block -> {
                        blocks.add(OpenCodeMarkdownBlock(blocks.size, item.kind))
                        item.cardFiles
                    }
                    is InlineCache.SegmentItem.CardFiles -> item.files
                }
                if (cardFiles.isEmpty()) continue
                val unseen = cardFiles.filter { seenFiles.add(it.path) }
                if (unseen.isNotEmpty()) blocks.add(OpenCodeMarkdownBlock(blocks.size, Kind.Files(unseen)))
            }
        }

        fun parseTextSegment(segment: String, cache: InlineCache?): List<InlineCache.SegmentItem> {
            val items = ArrayList<InlineCache.SegmentItem>()
            val paragraph = ArrayList<String>()
            var listMarker: String? = null
            var listLevel = 0
            // Indents of the open list's levels, so `-` under `2.` nests whether the author
            // indented two, three, or four spaces.
            val listIndents = ArrayList<Int>()

            fun flushParagraph() {
                var text = SwiftText.trimWhitespaceAndNewlines(paragraph.joinToString("\n"))
                paragraph.clear()
                try {
                    if (text.isEmpty()) return
                    // A task item (`- [ ] ...`, `- [x] ...`) takes its box as the marker.
                    if (listMarker == "•") {
                        val lowered = text.lowercase()
                        val box = taskMarkers.firstOrNull { lowered.startsWith("$it ") }
                        if (box != null) {
                            listMarker = box
                            text = SwiftText.trimWhitespace(text.substring(box.length))
                        }
                    }
                    val parsed = inlineEntry(text, cache)
                    val marker = listMarker
                    val kind: Kind = when {
                        marker != null && marker != "•" && marker !in taskMarkers && listLevel == 0 -> Kind.NumberedItem(marker, parsed.attributed)
                        marker != null -> Kind.ListItem(marker, listLevel, parsed.attributed)
                        else -> Kind.Paragraph(parsed.attributed)
                    }
                    items.add(InlineCache.SegmentItem.Block(kind, parsed.cardFiles))
                    appendPullRequests(text, items)
                } finally {
                    listMarker = null
                }
            }

            val lines = SwiftText.lines(segment)
            var index = 0
            while (index < lines.size) {
                val trimmed = SwiftText.trimWhitespace(lines[index])

                // Blank lines end the current paragraph so real paragraph gaps render.
                if (trimmed.isEmpty()) {
                    flushParagraph()
                    index++
                    continue
                }

                if (trimmed.startsWith("$$") || trimmed.startsWith("\\[")) {
                    val math = MathSyntax.display(lines, index)
                    if (math != null) {
                        flushParagraph()
                        items.add(InlineCache.SegmentItem.Block(Kind.Math(MathBlock(math.source, math.raw)), emptyList()))
                        index = math.end
                        continue
                    }
                }

                val image = markdownImage(trimmed)
                if (image != null) {
                    flushParagraph()
                    if (image is Kind.Files) items.add(InlineCache.SegmentItem.CardFiles(image.files))
                    else items.add(InlineCache.SegmentItem.Block(image, emptyList()))
                    index++
                    continue
                }

                if (trimmed.startsWith(">")) {
                    flushParagraph()
                    val quote = ArrayList<String>()
                    while (index < lines.size) {
                        val line = SwiftText.trimWhitespace(lines[index])
                        if (!line.startsWith(">")) break
                        var content = line.substring(1)
                        if (content.startsWith(" ")) content = content.substring(1)
                        quote.add(content)
                        index++
                    }
                    val parsed = inlineEntry(quote.joinToString("\n"), cache)
                    items.add(InlineCache.SegmentItem.Block(Kind.Blockquote(parsed.attributed), parsed.cardFiles))
                    continue
                }

                // Checked before list items: `- - -` is a break, not a bullet.
                if (isThematicBreak(trimmed)) {
                    flushParagraph()
                    items.add(InlineCache.SegmentItem.Block(Kind.Divider, emptyList()))
                    index++
                    continue
                }

                // Each top-level list item becomes its own block for breathing room.
                if (isListItem(trimmed)) {
                    flushParagraph()
                    var indent = 0
                    for (c in lines[index]) {
                        if (c == ' ') indent += 1 else if (c == '\t') indent += 4 else break
                    }
                    while (listIndents.isNotEmpty() && listIndents.last() > indent) listIndents.removeAt(listIndents.size - 1)
                    if (listIndents.lastOrNull() != indent) listIndents.add(indent)
                    listLevel = minOf(listIndents.size - 1, 3)
                    val space = trimmed.indexOf(' ')
                    if (SwiftText.isNumber(trimmed[0]) && space >= 0) {
                        listMarker = trimmed.substring(0, space)
                        paragraph.add(trimmed.substring(space + 1))
                    } else {
                        listMarker = "•"
                        paragraph.add(trimmed.substring(2))
                    }
                    index++
                    continue
                }
                // Anything but an item or its indented continuation ends the list.
                val firstChar = lines[index].firstOrNull()
                if (firstChar != ' ' && firstChar != '\t') listIndents.clear()

                if (trimmed.startsWith("|") && index + 1 < lines.size && isTableSeparator(SwiftText.trimWhitespace(lines[index + 1]))) {
                    flushParagraph()
                    val tableCardFiles = ArrayList<AgentFileReference>()
                    val header = tableCells(trimmed, cache, tableCardFiles)
                    val rows = ArrayList<List<InlineText>>()
                    var next = index + 2
                    while (next < lines.size) {
                        val rowLine = SwiftText.trimWhitespace(lines[next])
                        if (!rowLine.startsWith("|")) break
                        rows.add(tableCells(rowLine, cache, tableCardFiles))
                        next++
                    }
                    items.add(InlineCache.SegmentItem.Block(Kind.Table(header, rows), tableCardFiles))
                    index = next
                    continue
                }

                if (trimmed.startsWith("#")) {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val rest = trimmed.substring(level)
                    val heading = SwiftText.trimWhitespace(rest)
                    // A heading's hashes are followed by a space, so "#114 merged" or "#hashtag"
                    // stays prose rather than a title (CommonMark ATX).
                    if (heading.isNotEmpty() && level <= 6 && (rest.startsWith(" ") || rest.startsWith("\t"))) {
                        flushParagraph()
                        val parsed = inlineEntry(heading, cache)
                        items.add(InlineCache.SegmentItem.Block(Kind.Heading(level, parsed.attributed), parsed.cardFiles))
                        index++
                        continue
                    }
                }

                paragraph.add(lines[index])
                index++
            }
            flushParagraph()
            return items
        }

        /** Three or more of the same `-`, `*` or `_`, optionally spaced. */
        fun isThematicBreak(line: String): Boolean {
            val mark = line.firstOrNull() ?: return false
            if (mark != '-' && mark != '*' && mark != '_') return false
            var count = 0
            for (c in line) {
                if (c == ' ' || c == '\t') continue
                if (c != mark) return false
                count++
            }
            return count >= 3
        }

        private fun isTableSeparator(line: String): Boolean {
            if (!line.startsWith("|") || !line.contains('-')) return false
            return line.all { it == '|' || it == '-' || it == ':' || it == ' ' }
        }

        private fun tableCells(line: String, cache: InlineCache?, cardFiles: MutableList<AgentFileReference>): List<InlineText> {
            val row = cache?.parseTableRow(line) ?: uncachedTableRow(line, null)
            if (row.cardFiles.isNotEmpty()) cardFiles.addAll(row.cardFiles)
            return row.cells
        }

        fun uncachedTableRow(line: String, cache: InlineCache?): InlineCache.TableRowEntry {
            var body = line
            if (body.startsWith("|")) body = body.substring(1)
            if (body.endsWith("|")) body = body.substring(0, body.length - 1)
            val cardFiles = ArrayList<AgentFileReference>()
            val cells = body.split('|').map { cell ->
                val parsed = inlineEntry(SwiftText.trimWhitespace(cell), cache)
                if (parsed.cardFiles.isNotEmpty()) cardFiles.addAll(parsed.cardFiles)
                tableCellLineBreaks(parsed.attributed)
            }
            return InlineCache.TableRowEntry(cells, cardFiles)
        }

        private val tableCellBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

        /**
         * A `<br>` (`<br/>`, `<br />`) in a table cell's prose breaks the line, as GitHub renders
         * it, since a pipe row can't hold a newline. Code, links and math keep their text; no other
         * HTML is read.
         */
        private fun tableCellLineBreaks(cell: InlineText): InlineText {
            if (cell.runs.none { it.breaksTableCellLines() }) return cell
            return InlineText(
                cell.runs.map { run ->
                    if (run.breaksTableCellLines()) run.copy(text = run.text.replace(tableCellBreak, "\n")) else run
                },
            )
        }

        private fun InlineRun.breaksTableCellLines(): Boolean =
            !code && link == null && languageIdentifier == null && sessionChip == null && tableCellBreak.containsMatchIn(text)

        private fun inlineEntry(text: String, cache: InlineCache?): InlineCache.Entry = cache?.parse(text) ?: uncachedInlineEntry(text)

        fun uncachedInlineEntry(text: String): InlineCache.Entry {
            // Math is lifted out first, so Markdown never reads its `*`, `_` or `\`.
            val (masked, math) = MathSyntax.extractInline(text)
            val parsed = SessionLink.mark(InlineMarkdownParser.parse(masked), masked)
            val runs = ArrayList(parsed.runs)

            val cardCandidates = ArrayList<Pair<Int, AgentFileReference>>()

            // Inline code: monospaced, one text style smaller (mono reads optically larger),
            // dimmed instead of boxed — it should read as text, not a chip.
            var offset = 0
            for (i in runs.indices) {
                val run = runs[i]
                if (run.code) {
                    // A file path opens in the viewer; it takes the link tint instead.
                    val file = if (run.link == null) AgentFileReference.fromInlineCode(run.text) else null
                    if (file != null) {
                        runs[i] = run.copy(link = file.linkURL)
                        if (file.kind.showsCard) cardCandidates.add(offset to file)
                    } else {
                        runs[i] = run.copy(secondary = true)
                    }
                }
                offset += run.text.length
            }

            // Links to local files (`[spec](docs/spec.pdf)`, `file://…`) open in the viewer too;
            // web links keep opening in the browser.
            offset = 0
            for (i in runs.indices) {
                val run = runs[i]
                val link = run.link
                if (link != null) {
                    if (AgentFileReference.urlScheme(link) == AgentFileReference.LINK_SCHEME) {
                        val file = AgentFileReference.fromLinkURL(link)
                        if (file != null && file.kind.showsCard) cardCandidates.add(offset to file)
                    } else {
                        val file = AgentFileReference.fromLink(link)
                        if (file != null) {
                            runs[i] = run.copy(link = file.linkURL)
                            if (file.kind.showsCard) cardCandidates.add(offset to file)
                        }
                    }
                }
                offset += run.text.length
            }

            for (index in math.indices.reversed()) {
                val span = math[index]
                val joined = runs.joinToString("") { it.text }
                val start = joined.indexOf(MathSyntax.placeholder(index))
                if (start < 0) continue
                // Styled as inline code, which is how anything that doesn't typeset math
                // (previews, peeks) shows it.
                replaceRange(runs, start, start + MathSyntax.placeholder(index).length) { attributes ->
                    attributes.copy(
                        text = span.source,
                        code = true,
                        languageIdentifier = span.delimiter.languageIdentifier,
                        link = null,
                        secondary = true,
                    )
                }
            }

            val cardFiles: List<AgentFileReference> = when {
                cardCandidates.isEmpty() -> emptyList()
                math.isEmpty() -> if (cardCandidates.size == 1) listOf(cardCandidates[0].second)
                else cardCandidates.sortedBy { it.first }.map { it.second }
                else -> runs.mapNotNull { run -> run.link?.let(AgentFileReference::fromLinkURL) }.filter { it.kind.showsCard }
            }
            return InlineCache.Entry(InlineText.coalesced(runs), cardFiles)
        }

        /** Replaces the characters `[start, end)` with one run made from the attributes of the run at [start]. */
        private fun replaceRange(runs: MutableList<InlineRun>, start: Int, end: Int, make: (InlineRun) -> InlineRun) {
            var offset = 0
            var i = 0
            val out = ArrayList<InlineRun>(runs.size + 2)
            var inserted = false
            while (i < runs.size) {
                val run = runs[i]
                val runStart = offset
                val runEnd = offset + run.text.length
                offset = runEnd
                i++
                if (runEnd <= start || runStart >= end) {
                    out.add(run)
                    continue
                }
                if (runStart < start) out.add(run.copy(text = run.text.substring(0, start - runStart)))
                if (!inserted) {
                    out.add(make(run))
                    inserted = true
                }
                if (runEnd > end) out.add(run.copy(text = run.text.substring(end - runStart)))
            }
            runs.clear()
            runs.addAll(out)
        }

        /**
         * Agent-authored images are normally their own Markdown block. Keeping the original
         * source string lets the view resolve public URLs, data URIs and project-relative files
         * through the authenticated filesystem endpoint.
         */
        private fun markdownImage(line: String): Kind? {
            if (!line.startsWith("![") || !line.endsWith(")")) return null
            val separator = line.indexOf("](")
            if (separator < 0 || separator + 2 > line.length - 1) return null
            val alt = line.substring(2, separator)
            var source = SwiftText.trimWhitespaceAndNewlines(line.substring(separator + 2, line.length - 1))
            if (source.startsWith("<") && source.endsWith(">") && source.length >= 2) {
                source = source.substring(1, source.length - 1)
            } else {
                val title = source.indexOf(" \"")
                if (title >= 0) source = source.substring(0, title)
            }
            if (source.isEmpty()) return null
            // `![demo](demo.mp4)` names a video, not an image: give it a card.
            val file = AgentFileReference.fromLink(source)
            if (file != null && file.kind != AgentFileReference.Kind.Image && file.kind.showsCard) return Kind.Files(listOf(file))
            return Kind.Image(alt, source)
        }

        /**
         * Streaming text without a last line that holds only the start of a block (`-`, `##`,
         * `1.`, `>`, a fence's first backticks). That line waits for its first word: drawn now,
         * it would show as a paragraph and then turn into a list item, heading or quote,
         * reflowing the row and changing its height. The ended text is shown whole.
         */
        fun holdingPendingMarker(text: String): String {
            val lineStart = text.lastIndexOf('\n') + 1
            val line = SwiftText.trimWhitespace(text.substring(lineStart))
            if (line.isEmpty() || !isPendingMarker(line)) return text
            return text.substring(0, lineStart)
        }

        private fun isPendingMarker(line: String): Boolean {
            val first = line.firstOrNull() ?: return false
            if (line.codePointCount(0, line.length) > 10) return false
            if (first in "#`~" || first in "-*_") {
                return line.all { it == first } && line.length <= (if (first == '#') 6 else 2)
            }
            if (line == "+" || line == "•" || line == ">") return true
            val digits = line.takeWhile { SwiftText.isNumber(it) }
            if (digits.isEmpty()) return false
            val rest = line.substring(digits.length)
            return rest.isEmpty() || rest == "." || rest == ")"
        }

        fun isListItem(line: String): Boolean {
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("• ")) return true
            val digits = line.takeWhile { SwiftText.isNumber(it) }
            return digits.isNotEmpty() && line.startsWith(". ", digits.length)
        }

        private val pullRequestPattern = Regex(
            "https://github\\.com/([\\p{L}\\p{M}\\p{Nd}\\p{Pc}.-]+)/([\\p{L}\\p{M}\\p{Nd}\\p{Pc}.-]+)/pull/(\\p{Nd}+)",
        )

        private fun appendPullRequests(text: String, items: MutableList<InlineCache.SegmentItem>) {
            if (!text.contains("github.com/")) return
            for (match in pullRequestPattern.findAll(text)) {
                val (owner, repo, number) = match.destructured
                items.add(InlineCache.SegmentItem.Block(Kind.PullRequest("$owner/$repo #$number", match.value), emptyList()))
            }
        }
    }
}
