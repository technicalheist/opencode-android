package ai.opencode.feature.transcript.markdown

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.tuning.Tunable
import ai.opencode.core.tuning.TuneValue
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.feature.media.MemoryWarningCenter
import ai.opencode.feature.media.SyntaxHighlighter
import ai.opencode.feature.transcript.TranscriptFontWeight
import ai.opencode.feature.transcript.TranscriptLayoutTuning
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.feature.transcript.markdown.ui.InlineMath
import ai.opencode.feature.transcript.markdown.ui.MarkdownPresentation
import ai.opencode.feature.transcript.markdown.ui.MarkdownStyle
import ai.opencode.feature.transcript.markdown.ui.PresentationOptions
import ai.opencode.feature.transcript.markdown.ui.TranscriptTextReveal
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/*
 * Dropped (can't exist on Android):
 * - `multilineCodeMeasuredHeightEqualsSettledHeight`, `LineHoldTests.streamedBlockCollapsesToPlainTextAfterFadesSettle`:
 *   `UIHostingController` measurement; Android's `ArrivingText` lays the text out once and only
 *   erases arriving glyphs in the draw phase, so streaming never changes a block's height.
 * - Android erases each chunk's glyph path over one laid-out text instead.
 * - Android lays transcript text out with greedy `LineBreak.Simple`, which never moves a laid-out
 *   word.
 * - `TranscriptTextRevealWaitTests` (2): `TranscriptTextReveal.wait(until:)`; Android's
 *   `rememberRevealFrame` ticks frames until the clock passes the fade's end (no early wake).
 * - `standaloneMarkdownImagesBecomeViewableBlocks`'s and
 *   `cachedCodeBlocksPersistSourceOnlyAndRehighlight`'s JSON round trip: Android never persists
 *   parsed blocks (rows keep raw text).
 */
class MarkdownBlockTests {
    @get:Rule val isolation = TestIsolationRule()

    /** Streamed word by word, a block's marker waits for its first word, so each block keeps its kind as it grows. */
    @Test fun streamingTextHoldsABlockMarkerUntilItsFirstWord() {
        assertEquals("Done.\n\n", OpenCodeMarkdownBlock.holdingPendingMarker("Done.\n\n-"))
        assertEquals("", OpenCodeMarkdownBlock.holdingPendingMarker("##"))
        assertEquals("Steps:\n\n", OpenCodeMarkdownBlock.holdingPendingMarker("Steps:\n\n12."))
        assertEquals("Done.\n\n- The", OpenCodeMarkdownBlock.holdingPendingMarker("Done.\n\n- The"))
        assertEquals("Done.\n\n---", OpenCodeMarkdownBlock.holdingPendingMarker("Done.\n\n---"))
        assertEquals("Costs 5 -", OpenCodeMarkdownBlock.holdingPendingMarker("Costs 5 -"))

        val source = "## What changed\n\nThe transcript grows in place.\n\n- One item.\n- Two items.\n\n1. First\n2. Second\n\n> Quoted\n\nThe end."
        val kinds = HashMap<Int, String>()
        for (match in Regex("\\s*\\S+").findAll(source)) {
            val shown = OpenCodeMarkdownBlock.holdingPendingMarker(source.substring(0, match.range.last + 1))
            for (block in OpenCodeMarkdownBlock.blocks(shown, inlineCache = null)) {
                val kind = kindName(block)
                assertEquals("block ${block.id} changed kind at \"$shown\"", kinds[block.id] ?: kind, kind)
                kinds[block.id] = kind
            }
        }
    }

    @Test fun fenceScanPreservesTheOriginalUTF8Segments() {
        val boundaries = listOf("", "`", "``", "```", "````", "\r\n", "\u0000", "é", "e\u0301", "\u0301", "\u200D", "🧑🏽‍💻", "\u1FEF")
        val extenders = setOf("\u0301", "\u200D")
        fun plain(s: String) = s.none { it == '`' || it == '\u1FEF' }
        for (prefix in boundaries) for (middle in boundaries) for (suffix in boundaries) {
            val source = prefix + "```" + middle + "```" + suffix
            val segments = OpenCodeMarkdownBlock.codeFenceSegments(source)
            // Every segment is the original text, byte for byte, between fences of three backticks
            // (U+1FEF is canonically one).
            var at = 0
            for ((index, segment) in segments.withIndex()) {
                if (index > 0) {
                    for (k in 0 until 3) assertTrue(source[at + k] == '`' || source[at + k] == '\u1FEF')
                    at += 3
                }
                assertTrue(source.regionMatches(at, segment, 0, segment.length))
                at += segment.length
            }
            assertEquals(source.length, at)
            if (plain(prefix) && plain(middle) && plain(suffix) && middle !in extenders && suffix !in extenders) {
                assertEquals(listOf(prefix, middle, suffix).map { it.toByteArray().toList() }, segments.map { it.toByteArray().toList() })
            }
        }
    }

    @Test fun codeFencesRespectUnicodeGraphemeBoundaries() {
        val source = "let café = \"🧑🏽‍💻\""
        // A joiner on the last backtick makes it part of that grapheme rather than a plain closing fence.
        for (suffix in listOf("\u200D", "\u0301")) {
            val code = (OpenCodeMarkdownBlock.blocks("```swift\n$source\n```$suffix").firstOrNull()?.kind as? Kind.Code)?.code
            if (code == null) {
                fail("Expected an open code fence")
                continue
            }
            assertEquals(source + "\n```" + suffix, code.source)
        }
        val blocks = OpenCodeMarkdownBlock.blocks("```swift\n$source\n```\n\nTail e\u0301")
        assertEquals(2, blocks.size)
        val code = (blocks[0].kind as? Kind.Code)?.code ?: return fail("Expected a code block")
        assertEquals(source.toByteArray().toList(), code.source.toByteArray().toList())
        assertEquals("Tail e\u0301", blocks[1].markdown)
    }

    @Test(timeout = 2_000) fun coalescingManyAdjacentRunsStaysLinear() {
        val runs = ArrayList<InlineRun>()
        repeat(200_000) { runs.add(InlineRun("snake_")) }
        val text = InlineText.coalesced(runs)
        assertEquals(1, text.runs.size)
        assertEquals(1_200_000, text.text.length)
    }

    @Test(timeout = 2_000) fun parsingManyStrayClosingBracketsStaysLinear() {
        val source = "a ] ".repeat(150_000)
        val text = InlineMarkdownParser.parse(source)
        assertEquals(listOf(InlineRun(source)), text.runs)
    }

    @Test fun inlineReuseMatchesFreshParsingAtEveryStreamingBoundary() {
        val cache = OpenCodeMarkdownBlock.InlineCache()
        var previous = emptyList<OpenCodeMarkdownBlock>()
        val documents = listOf(
            "## Heading\n\n1. **First**\n   - `Sources/One.swift`\n\n> Quote *two*\n> still quoted\n\nAfter 🧑🏽‍💻 e\u0301",
            "Before\n\n```swift\nlet value = \"hello\"\n```\n\n| File | State |\n|---|---|\n| [report](reports/one.pdf) | **Ready** |\n\nAfter",
            "[one](reports/one.pdf)\n\n[again](reports/one.pdf)\n\n[scheme](opencode-file://open?path=reports/two.pdf)\n\n![clip](clips/one.mp4)\n\n---\n\nhttps://github.com/example/project/pull/42",
            "Escaped \\*text\\*, &amp; entities, <https://example.com>, ~~removed~~ and `a\\b`.\n\n- _Unfinished",
            "- [ ] Open **task**\n  - [x] Done `Sources/One.swift`\n- [X] Uppercase\n\nSame **task**",
        )
        for (document in documents) {
            val ends = codePointEnds(document)
            // Rewind too: a refresh may replace the whole text, not just append.
            for (end in ends + ends.reversed()) {
                val text = document.substring(0, end)
                val fresh = OpenCodeMarkdownBlock.blocks(text, inlineCache = null)
                previous = OpenCodeMarkdownBlock.blocks(text, inlineCache = cache, reusing = previous)
                assertEquals(fresh, previous)
                // HighlightedCode equality compares source/language; also compare tokens to catch a wrong-language reuse.
                assertEquals(highlightedCode(fresh), highlightedCode(previous))
            }
        }
    }

    @Test fun codeReuseTracksLanguageSourceAndReplacement() {
        val code = "const value = 'hello' // note"
        val documents = listOf(
            "```swift\n$code\n```", "```typescript\n$code\n```", "```\n$code\n```",
            "```python\n# comment\n```", "```swift\n# comment\n```",
            "```swift\nlet café = 1\n```", "```swift\nlet cafe\u0301 = 1\n```",
            "```swift\nlet value = 1\n```\n\n```swift\nlet value = 1\n```",
            "```swift\nlet value = 2\n```", "No code remains",
        )
        var previous = emptyList<OpenCodeMarkdownBlock>()
        for (document in documents) {
            val fresh = OpenCodeMarkdownBlock.blocks(document, inlineCache = null)
            previous = OpenCodeMarkdownBlock.blocks(document, reusing = previous)
            assertEquals(fresh, previous)
            assertEquals(highlightedCode(fresh), highlightedCode(previous))
            assertEquals(OpenCodeMarkdownBlock.markdown(fresh).toByteArray().toList(), OpenCodeMarkdownBlock.markdown(previous).toByteArray().toList())
        }
    }

    @Test fun inlineReuseDistinguishesCanonicallyEquivalentSourceBytes() {
        val cache = OpenCodeMarkdownBlock.InlineCache()
        for (source in listOf("café", "cafe\u0301", "`café.swift`", "`cafe\u0301.swift`")) {
            val cached = OpenCodeMarkdownBlock.blocks(source, inlineCache = cache)
            val fresh = OpenCodeMarkdownBlock.blocks(source, inlineCache = null)
            assertEquals(fresh, cached)
            assertEquals(OpenCodeMarkdownBlock.markdown(fresh).toByteArray().toList(), OpenCodeMarkdownBlock.markdown(cached).toByteArray().toList())
        }
    }

    @Test fun inlineCacheEmptiesEveryTableOnAMemoryWarning() {
        val center = MemoryWarningCenter()
        val cache = OpenCodeMarkdownBlock.InlineCache(memoryWarnings = center)
        cache.parse("Some **bold** text")
        cache.parseCompletedSegment("A paragraph with `code`.\n\n")
        cache.parseTableRow("| a | **b** |")
        assertTrue(cache.allEntryCount >= 3)
        center.post()
        assertEquals(0, cache.allEntryCount)
        assertEquals(0, cache.retainedSourceBytes)
    }

    @Test fun inlineReuseHasHardEntryAndUTF8SourceBounds() {
        val cache = OpenCodeMarkdownBlock.InlineCache(maximumEntries = 3, maximumSourceBytes = 64)
        for (index in 0 until 30) {
            val source = "**$index** 🧑🏽‍💻"
            assertEquals(OpenCodeMarkdownBlock.blocks(source, inlineCache = null), OpenCodeMarkdownBlock.blocks(source, inlineCache = cache))
            assertTrue(cache.entryCount <= 3)
            assertTrue(cache.retainedSourceBytes <= 64)
        }
        val count = cache.entryCount
        val bytes = cache.retainedSourceBytes
        val oversized = "😀".repeat(100)
        assertEquals(OpenCodeMarkdownBlock.blocks(oversized, inlineCache = null), OpenCodeMarkdownBlock.blocks(oversized, inlineCache = cache))
        assertEquals(count, cache.entryCount)
        assertEquals(bytes, cache.retainedSourceBytes)
        // Evicted entries still parse normally when used again.
        assertEquals(OpenCodeMarkdownBlock.blocks("**0** 🧑🏽‍💻", inlineCache = null), OpenCodeMarkdownBlock.blocks("**0** 🧑🏽‍💻", inlineCache = cache))
        val disabled = OpenCodeMarkdownBlock.InlineCache(maximumEntries = 0)
        OpenCodeMarkdownBlock.blocks("No storage", inlineCache = disabled)
        assertEquals(0, disabled.entryCount)
        assertEquals(0, disabled.retainedSourceBytes)
    }

    @Test fun concurrentInlineCacheMissesKeepResultsAndAccountingConsistent() = runBlocking {
        val cache = OpenCodeMarkdownBlock.InlineCache()
        val texts = (0 until 8).map { "Value **$it** [file](files/$it.pdf)" }
        val expected = texts.map { OpenCodeMarkdownBlock.blocks(it, inlineCache = null) }
        val matches = (0 until 64).map { index ->
            async(Dispatchers.Default) { OpenCodeMarkdownBlock.blocks(texts[index % 8], inlineCache = cache) == expected[index % 8] }
        }.awaitAll()
        assertTrue(matches.all { it })
        assertEquals(texts.size, cache.entryCount)
        assertEquals(texts.sumOf { it.toByteArray().size }, cache.retainedSourceBytes)
    }

    @Test fun thematicBreaksRenderAsDividersNotText() {
        fun kinds(text: String) = OpenCodeMarkdownBlock.blocks(text).map {
            when (it.kind) {
                Kind.Divider -> "divider"
                is Kind.Paragraph -> "paragraph"
                is Kind.Table -> "table"
                else -> "other"
            }
        }
        assertEquals(listOf("paragraph", "divider", "paragraph"), kinds("First answer.\n\n---\n\nSecond answer."))
        assertEquals(listOf("paragraph", "divider", "paragraph", "divider", "paragraph"), kinds("A\n\n***\n\nB\n\n_ _ _\n\nC"))
        assertEquals(listOf("paragraph", "divider", "paragraph"), kinds("A\n\n- - -\n\nB"))
        // Still streaming: two dashes are not yet a break.
        assertEquals(listOf("paragraph", "paragraph"), kinds("A\n\n--"))
        assertEquals(listOf("table"), kinds("| a | b |\n|---|---|\n| 1 | 2 |"))
        // A bullet is still a bullet.
        assertFalse(kinds("- item").contains("divider"))
        assertEquals("A\n\n---\n\nB", OpenCodeMarkdownBlock.markdown(OpenCodeMarkdownBlock.blocks("A\n\n---\n\nB")))
    }

    @Test fun numberedMarkersAreSeparateFromWrappedContent() {
        val blocks = OpenCodeMarkdownBlock.blocks("9. A **bold** item\n   with a continuation\n10. Next item\n\nAfter")
        assertEquals(3, blocks.size)
        val first = blocks[0].kind as? Kind.NumberedItem
        val next = blocks[1].kind as? Kind.NumberedItem
        if (first == null || next == null || blocks[2].kind !is Kind.Paragraph) {
            return fail("Expected two numbered items followed by ordinary prose")
        }
        assertEquals("9.", first.marker)
        assertEquals("10.", next.marker)
        assertTrue(first.text.text.contains("with a continuation"))
        assertTrue(first.text.runs.any { it.strong })
    }

    @Test fun blockquoteSeparatesProseAndPreservesInlineFormatting() {
        val blocks = OpenCodeMarkdownBlock.blocks("Before\n> A **quoted** line\n> continuation\n>\n> Second paragraph\nAfter")
        assertEquals(3, blocks.size)
        val quote = (blocks.getOrNull(1)?.kind as? Kind.Blockquote)?.text ?: return fail("Expected a blockquote between prose blocks")
        assertEquals("A quoted line\ncontinuation\n\nSecond paragraph", quote.text)
        assertTrue(quote.runs.any { it.strong })
    }

    @Test fun splitsParagraphsAndFencedCode() {
        val blocks = OpenCodeMarkdownBlock.blocks("Here is **bold** text.\n\n```swift\nlet x = 1\n```\n\nTrailing paragraph.")
        assertEquals(3, blocks.size)
        assertTrue("expected paragraph", blocks[0].kind is Kind.Paragraph)
        val code = (blocks[1].kind as? Kind.Code)?.code ?: return fail("expected code")
        assertEquals("let x = 1", code.source)
        assertTrue("expected paragraph", blocks[2].kind is Kind.Paragraph)
    }

    @Test fun standaloneMarkdownImagesBecomeViewableBlocks() {
        val blocks = OpenCodeMarkdownBlock.blocks("Before\n\n![Build graph](artifacts/build-graph.png)\n\nAfter")
        assertEquals(3, blocks.size)
        val image = blocks[1].kind as? Kind.Image ?: return fail("expected an image between prose blocks")
        assertEquals("Build graph", image.alt)
        assertEquals("artifacts/build-graph.png", image.source)
    }

    /** The Android highlight is likewise derived from them. */
    @Test fun cachedCodeBlocksPersistSourceOnlyAndRehighlight() {
        val block = OpenCodeMarkdownBlock(0, Kind.Code(OpenCodeMarkdownBlock.HighlightedCode("let x = 1", "swift")))
        val rebuilt = OpenCodeMarkdownBlock(0, Kind.Code(OpenCodeMarkdownBlock.HighlightedCode("let x = 1", "swift")))
        assertEquals(block, rebuilt)
        val code = (rebuilt.kind as Kind.Code).code
        assertEquals(SyntaxHighlighter.tokens("let x = 1", "swift"), code.tokens)
    }

    @Test fun plainTextIsASingleParagraph() {
        assertEquals(1, OpenCodeMarkdownBlock.blocks("just text").size)
    }

    @Test fun blankLinesAndListItemsSplitBlocks() {
        val blocks = OpenCodeMarkdownBlock.blocks("First paragraph.\n\nSecond paragraph.\n- item one\n- item two")
        assertEquals(4, blocks.size)
        val item = blocks[2].kind as? Kind.ListItem
        if (item == null || item.marker != "•" || item.level != 0) return fail("expected a bullet")
        assertEquals("item one", item.text.text)
    }

    @Test fun nestedListItemsKeepTheirLevel() {
        fun shape(text: String) = OpenCodeMarkdownBlock.blocks(text).map {
            when (val kind = it.kind) {
                is Kind.NumberedItem -> "${kind.marker}0"
                is Kind.ListItem -> "${kind.marker}${kind.level}"
                is Kind.Paragraph -> "p"
                else -> "other"
            }
        }
        // Bullets under a numbered item, at three spaces (the item's text column).
        assertEquals(
            listOf("1.0", "2.0", "•1", "•1", "3.0"),
            shape("1. **Architecture:** thin\n2. **Routing:** focus first\n   - The focused session\n   - `HOME` is bound\n3. Last"),
        )
        // Two- and four-space nesting, a third level, and a return to the top.
        assertEquals(listOf("•0", "•1", "•2", "•0"), shape("- a\n  - b\n    - c\n- d"))
        assertEquals(listOf("•0", "•1", "•0"), shape("- a\n    - b\n- c"))
        // A nested numbered list keeps its numbers, indented.
        assertEquals(listOf("•0", "1.1", "2.1"), shape("- a\n  1. b\n  2. c"))
        // Prose ends the list; the next list starts at the top again.
        assertEquals(listOf("•0", "•1", "p", "•0"), shape("- a\n  - b\n\nAfter\n\n  - c"))
    }

    @Test fun extractsPullRequestCards() {
        val blocks = OpenCodeMarkdownBlock.blocks("PR: https://github.com/anomalyco/opencode/pull/41189 — all green.")
        assertEquals(2, blocks.size)
        val card = blocks[1].kind as? Kind.PullRequest ?: return fail("expected PR card")
        assertEquals("anomalyco/opencode #41189", card.title)
        assertEquals("https://github.com/anomalyco/opencode/pull/41189", card.url)
    }

    @Test fun parsesPipeTables() {
        val blocks = OpenCodeMarkdownBlock.blocks(
            "Intro.\n\n| Runtime | Notes |\n|---|---|\n| gVisor | default |\n| runc | weaker isolation |\n\nAfter.",
        )
        assertEquals(3, blocks.size)
        val table = blocks[1].kind as? Kind.Table ?: return fail("expected table")
        assertEquals(listOf("Runtime", "Notes"), table.header.map { it.text })
        assertEquals(2, table.rows.size)
        assertEquals(listOf("runc", "weaker isolation"), table.rows[1].map { it.text })
    }

    // Android breaks the line there.
    @Test fun tableCellBreakTagsBecomeLineBreaks() {
        val blocks = OpenCodeMarkdownBlock.blocks(
            "| Command | Output |\n|---|---|\n" +
                "| list | `1. [ ] Beta`<br>`2. [ ] Gamma` |\n" +
                "| stats | total: 2<br/>done: 0<BR />remaining: 2 |\n" +
                "| code | `a<br>b` |",
        )
        val table = blocks.single().kind as? Kind.Table ?: return fail("expected table")
        assertEquals("1. [ ] Beta\n2. [ ] Gamma", table.rows[0][1].text)
        assertEquals("total: 2\ndone: 0\nremaining: 2", table.rows[1][1].text)
        assertEquals("a<br>b", table.rows[2][1].text)
    }

    @Test fun breakTagsOutsideTablesStayLiteral() {
        val blocks = OpenCodeMarkdownBlock.blocks("one<br>two")
        val paragraph = blocks.single().kind as? Kind.Paragraph ?: return fail("expected paragraph")
        assertEquals("one<br>two", paragraph.text.text)
    }

    @Test fun pipeTableAppearsAndAddsRowsBeforeTheStreamEnds() {
        var streamed = ""
        val chunks = listOf("| Fruit | Color |\n", "|---|---|\n", "| Apple | Red |\n", "| Pear | Gre", "en |\n")
        for ((index, chunk) in chunks.withIndex()) {
            streamed += chunk
            val blocks = OpenCodeMarkdownBlock.blocks(streamed)
            if (index == 0) {
                assertFalse(blocks.any { it.kind is Kind.Table })
                continue
            }
            val table = blocks.firstNotNullOfOrNull { it.kind as? Kind.Table }
                ?: return fail("The table must appear when the separator arrives, not at text.ended")
            assertEquals(listOf("Fruit", "Color"), table.header.map { it.text })
            assertEquals(listOf(0, 0, 1, 2, 2)[index], table.rows.size)
            if (index == 3) assertEquals("Gre", table.rows[1][1].text)
            if (index == 4) assertEquals("Green", table.rows[1][1].text)
        }
    }

    @Test fun extractsHeadings() {
        val blocks = OpenCodeMarkdownBlock.blocks("## Section\n\nBody text.")
        assertEquals(2, blocks.size)
        val heading = blocks[0].kind as? Kind.Heading ?: return fail("expected heading")
        assertEquals(2, heading.level)
        assertEquals("Section", heading.text.text)
        assertTrue("expected paragraph", blocks[1].kind is Kind.Paragraph)
    }

    @Test fun hashesWithoutASpaceStayProse() {
        val blocks = OpenCodeMarkdownBlock.blocks("#114 merged, #26 closed — both stories unlocked.\n\n##not a heading either")
        assertEquals(2, blocks.size)
        for (block in blocks) assertTrue("expected paragraph, got ${block.kind}", block.kind is Kind.Paragraph)
    }

    @Test fun aTabAfterTheHashesStillMakesAHeading() {
        val heading = OpenCodeMarkdownBlock.blocks("###\tSection").firstOrNull()?.kind as? Kind.Heading ?: return fail("expected heading")
        assertEquals(3, heading.level)
        assertEquals("Section", heading.text.text)
    }

    @Test fun inlineCodeInHeadingsUsesTheHeadingsSizeMonospacedAndSemibold() {
        val heading = OpenCodeMarkdownBlock.blocks("# Upgrade `rc.118` now", inlineCache = null).firstOrNull()?.kind as? Kind.Heading
            ?: return fail("Expected a heading block")
        assertEquals(1, heading.level)

        val style = markdownStyle(standardColors)
        // In prose, inline code is `.subheadline` monospaced.
        val paragraphCode = codeSpans(present(heading.text, style))
        assertEquals(listOf(OpenCodeFonts.monospace), paragraphCode.map { it.style.fontFamily })
        assertEquals(listOf(OpenCodeTextStyles.subheadline.fontSize), paragraphCode.map { it.style.fontSize })
        assertTrue(paragraphCode.all { it.style.fontWeight == null })

        // In a heading it takes the heading's size, monospaced and semibold. Headings are the
        // desktop's scale (H1 19, H2 17, H3 and deeper 15).
        for ((level, size) in listOf(1 to 19f, 2 to 17f, 3 to 15f, 4 to 15f)) {
            assertEquals(size.sp, style.headingStyle(level).fontSize)
            val codeRuns = codeSpans(present(heading.text, style, headingLevel = level))
            assertEquals(1, codeRuns.size)
            val run = codeRuns.first().style
            assertEquals(OpenCodeFonts.monospace, run.fontFamily)
            assertEquals(FontWeight.SemiBold, run.fontWeight)
            assertFalse("The heading's size, not .subheadline", run.fontSize.isSpecified)
            assertEquals(standardColors.inlineCodeFill, run.background)
            assertEquals(standardColors.strongText, run.color)
        }

        // Inline math inside H3 uses the 15 sp H3 point size (not the 14 sp subheadline).
        assertEquals(15f, style.headingSize(3))
        assertEquals(InlineMath.fontSize(15f), InlineMath.fontSize(style.headingSize(3)))
        assertTrue(InlineMath.fontSize(style.headingSize(3)) > InlineMath.fontSize(14f))
    }

    @Test fun filePathInlineCodeKeepsItsBackgroundPillAndLink() {
        val paragraph = (OpenCodeMarkdownBlock.blocks("Check `OpenCode/SessionView.swift` alongside `ConfigPermissionV1`.", inlineCache = null)
            .firstOrNull()?.kind as? Kind.Paragraph)?.text ?: return fail("Expected a paragraph")
        val style = markdownStyle(standardColors)
        val codeRuns = codeSpans(present(paragraph, style))
        assertEquals(2, codeRuns.size)

        val fileRun = codeRuns[0]
        val plainRun = codeRuns[1]
        assertEquals("OpenCode/SessionView.swift", fileRun.text)
        assertNotNull("File-path inline code keeps its viewer link", fileRun.link)
        assertEquals("Linked inline code keeps the link tint", Color.Unspecified, fileRun.style.color)
        assertEquals("Linked inline code keeps its background pill", standardColors.inlineCodeFill, fileRun.style.background)

        assertEquals("ConfigPermissionV1", plainRun.text)
        assertNull(plainRun.link)
        assertEquals(standardColors.strongText, plainRun.style.color)
        assertEquals(standardColors.inlineCodeFill, plainRun.style.background)

        // Inside a heading, file-path inline code also gets the heading's monospaced semibold font and pill fill.
        val headingCodeRuns = codeSpans(present(paragraph, style, headingLevel = 1))
        assertTrue(headingCodeRuns.all { it.style.background == standardColors.inlineCodeFill })
        assertTrue(headingCodeRuns.all { it.style.fontFamily == OpenCodeFonts.monospace && it.style.fontWeight == FontWeight.SemiBold })
        assertNotNull(headingCodeRuns.first().link)
        assertEquals(Color.Unspecified, headingCodeRuns.first().style.color)
    }

    @Test fun consecutiveHeadingsUseHeadingBelowGapAndTabularDigitsAreApplied() {
        val h1 = Kind.Heading(1, InlineText.plain("Title"))
        val h2 = Kind.Heading(2, InlineText.plain("Subtitle"))
        val h3 = Kind.Heading(3, InlineText.plain("Section"))
        val body = Kind.Paragraph(InlineText.plain("Prose"))

        val style = markdownStyle(standardColors)
        assertEquals(24.dp, style.gap(body, h1))
        assertEquals("Consecutive headings use headingBelow, not headingAbove", 8.dp, style.gap(h1, h2))
        assertEquals(8.dp, style.gap(h2, h3))
        assertEquals(8.dp, style.gap(h2, body))
    }

    @Test fun refinedStandardTypographyDefaultsAndIncreaseContrast() {
        // Transcript type follows the OpenCode desktop (15 sp body, H1/H2/H3 19/17/15, 14 sp Used
        // N, 13 sp mono code).
        with(TranscriptTunables.Knobs) {
            assertEquals(15f, bodySize.defaultValue)
            assertEquals(4.5f, bodyLeading.defaultValue)
            assertEquals(0.92, bodyTone.defaultValue, 0.0)
            assertEquals(19f, h1Size.defaultValue)
            assertEquals(17f, h2Size.defaultValue)
            assertEquals(15f, h3Size.defaultValue)
            assertEquals(TranscriptFontWeight.Bold, h3Weight.defaultValue)
            // Used N and Working sit a step under the body, like the desktop's 13 px tool titles.
            assertEquals(14f, metaSize.defaultValue)
            assertEquals(13f, listNumSize.defaultValue)
            assertEquals(3f, codeLeading.defaultValue)
            assertEquals(0f, paragraphGap.defaultValue)
            assertEquals("Paragraph gap (pt, 0 = 2 × flowHalf)", paragraphGap.label)
        }

        val standard = markdownStyle(standardColors)
        assertEquals(15.sp, standard.bodyStyle.fontSize)
        // `.body`'s proportional 15 sp line plus 4.5 sp leading.
        assertEquals((15f * 22f / 17f + 4.5f).sp, standard.bodyStyle.lineHeight)
        // Code is `.footnote` (13/18) mono plus 3 sp leading.
        assertEquals((18f + 3f).sp, MarkdownStyle.codeTextStyle(standard.tuning.codeLeading).lineHeight)
        assertEquals(standardColors.bodyText(0.92), standard.bodyColor)
        assertEquals(19.sp, standard.headingStyle(1).fontSize)
        assertEquals(FontWeight.SemiBold, standard.headingStyle(1).fontWeight)
        assertEquals(17.sp, standard.headingStyle(2).fontSize)
        assertEquals(FontWeight.SemiBold, standard.headingStyle(2).fontWeight)
        assertEquals(15.sp, standard.headingStyle(3).fontSize)
        assertEquals(FontWeight.Bold, standard.headingStyle(3).fontWeight)
        assertEquals(15.sp, standard.headingStyle(4).fontSize)
        assertEquals(FontWeight.SemiBold, standard.headingStyle(4).fontWeight)
        assertEquals(13.sp, standard.listNumberStyle.fontSize)
        assertEquals("tnum", standard.listNumberStyle.fontFeatureSettings)
        assertEquals(16.dp, standard.paragraphGap)

        // Under Increase Contrast, body prose returns to full primary.
        val highContrast = markdownStyle(highContrastColors)
        assertEquals(highContrastColors.strongText, highContrast.bodyColor)

        // Bold spans pop at 100% primary against 92% body prose, while done-task secondary items and links keep their own styles.
        val paragraph = (OpenCodeMarkdownBlock.blocks("Moving fixed **three subtle bugs** in [the stream](https://example.com).", inlineCache = null)
            .firstOrNull()?.kind as? Kind.Paragraph)?.text ?: return fail("Expected a paragraph")
        val boldRuns = boldSpans(present(paragraph, standard))
        assertEquals(1, boldRuns.size)
        assertEquals(standardColors.strongText, boldRuns.first().style.color)

        assertEquals(Color.Unspecified, boldSpans(present(paragraph, standard, secondary = true)).first().style.color)
        assertEquals(Color.Unspecified, boldSpans(present(paragraph, highContrast)).first().style.color)
    }

    @Test fun accessibilityDynamicTypeScalesH3MarkersAndLeading() {
        // Dynamic Type scaling itself is the system font scale on sp sizes. What's left: tuned
        // marker sizes keep tabular digits, and zero code leading stays zero.
        val tuned = MarkdownStyle(TranscriptLayoutTuning(listNumSize = 14f, codeLeading = 0f), standardColors)
        assertEquals(14.sp, tuned.listNumberStyle.fontSize)
        assertEquals("tnum", tuned.listNumberStyle.fontFeatureSettings)
        // Code is `.footnote` mono on Android (the desktop's 13 px), so zero leading is footnote's line.
        assertEquals(OpenCodeTextStyles.footnote.lineHeight, MarkdownStyle.codeTextStyle(tuned.tuning.codeLeading).lineHeight)
    }

    @Test fun quotesAndTablesKeepMainColourPolicyAcrossContrastModes() {
        val quote = (OpenCodeMarkdownBlock.blocks("> Quoted **bold** and `inlineCode` stay on the quote's hierarchy.", inlineCache = null)
            .firstOrNull()?.kind as? Kind.Blockquote)?.text ?: return fail("Expected a blockquote")
        for (colors in listOf(standardColors, highContrastColors)) {
            val styled = present(quote, markdownStyle(colors), tonesBody = false)
            val boldRuns = boldSpans(styled)
            val codeRuns = codeSpans(styled)
            val plainRuns = styled.spanStyles.filter { it.item.fontWeight == null && it.item.fontFamily == null }
            assertEquals(1, boldRuns.size)
            assertEquals("Quoted bold inherits the quote's secondary style", Color.Unspecified, boldRuns.first().style.color)
            assertTrue(plainRuns.all { it.item.color == Color.Unspecified })
            assertEquals(1, codeRuns.size)
            assertEquals(colors.strongText, codeRuns.first().style.color)
            assertEquals(colors.inlineCodeFill, codeRuns.first().style.background)
        }

        // Table headers and body cells keep main's inline code colour and never apply body-tone bold overrides.
        val cellText = (OpenCodeMarkdownBlock.blocks("Header **bold** and `cellCode`", inlineCache = null)
            .firstOrNull()?.kind as? Kind.Paragraph)?.text ?: return fail("Expected cell text")
        for (colors in listOf(standardColors, highContrastColors)) {
            val styled = present(cellText, markdownStyle(colors), tonesBody = false)
            val codeRun = codeSpans(styled).firstOrNull()
            assertEquals("Table header and body inline code stays primary as on main", colors.strongText, codeRun?.style?.color)
            assertEquals(colors.inlineCodeFill, codeRun?.style?.background)
            assertEquals(Color.Unspecified, boldSpans(styled).firstOrNull()?.style?.color)
        }
    }

    @Test fun paragraphGapSurvivesBakeAndExplicitInheritFollowsFlowHalf() {
        val offStore = TuningStore(null)
        // Simulate `tune bake` having rewritten `default:` to 18, with tuning off so `value == defaultValue == 18`.
        val baked18 = Tunable("transcript.typography.paragraphGap", "Paragraph gap (pt)", default = 18f, range = 0.0..24.0, step = 1.0)
        assertEquals(baked18.defaultValue, offStore.value(baked18))
        assertEquals(18f, TranscriptTunables.resolvedParagraphGap(offStore.value(baked18), flowHalf = 8f))

        // The explicit inherit sentinel (`0`, live or baked) follows `flowHalf * 2`.
        val bakedInherit = Tunable("transcript.typography.paragraphGap", "Paragraph gap (pt)", default = 0f, range = 0.0..24.0, step = 1.0)
        assertEquals(20f, TranscriptTunables.resolvedParagraphGap(offStore.value(bakedInherit), flowHalf = 10f))

        val live = TuningStore.shared
        live.setEnabled(true)
        try {
            TranscriptTunables.register(live)
            // `paragraphGap` defaults to `0`, so tuning `transcript.row.flowHalf` alone gives `flowHalf * 2` gaps.
            for ((flowHalf, expectedGap) in listOf(8f to 16f, 6f to 12f, 10f to 20f, 12f to 24f)) {
                live.set("transcript.row.flowHalf", TuneValue.Number(flowHalf.toDouble()))
                assertEquals(0f, live.value(TranscriptTunables.Knobs.paragraphGap))
                assertEquals(expectedGap.dp, MarkdownStyle(TranscriptTunables.layoutSnapshot(), standardColors).paragraphGap)
                assertEquals(expectedGap, TranscriptTunables.layoutSnapshot().paragraphGap)
            }
            // A positive tuned value overrides the inherited gap until reset to `0`.
            live.set("transcript.typography.paragraphGap", TuneValue.Number(18.0))
            assertEquals(18.dp, MarkdownStyle(TranscriptTunables.layoutSnapshot(), standardColors).paragraphGap)
            assertEquals(18f, TranscriptTunables.layoutSnapshot().paragraphGap)
        } finally {
            live.resetAll()
            live.setEnabled(false)
        }
    }

    @Test fun markdownQuoteFormatsABlockquoteAndAppendsItToTheDraft() {
        assertNull(MarkdownQuote.blockquote("   \n "))
        assertEquals("> One\n> Two", MarkdownQuote.blockquote("One\nTwo"))
        assertEquals("> One\n> Two\n> Three", MarkdownQuote.blockquote("One\u2028Two\u2029Three"))
        assertEquals("> One\n\n", MarkdownQuote.append("> One", ""))
        assertEquals("Existing draft\n\n> Two\n\n", MarkdownQuote.append("> Two", "Existing draft\n"))
        assertEquals("```swift\nlet x = 1\n```", MarkdownQuote.fencedCode("let x = 1\n", "swift"))
        assertEquals("````\na ``` b\n````", MarkdownQuote.fencedCode("a ``` b", null))
        // A code selection quotes fenced in the block's language.
        assertEquals("```swift\nlet retry\n```", MarkdownQuote.fencedCode("let retry", "swift"))
    }
}

class TranscriptTextRevealTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun blocks(text: String) = OpenCodeMarkdownBlock.blocks(text)
    private fun arrival(offset: Int, time: Double, duration: Double = 0.2) = TranscriptTextReveal.Arrival(offset, time, duration)

    @Test fun onlyTextThatArrivesAfterTheFirstRenderFades() {
        val reveal = TranscriptTextReveal()
        // History, or a reply's first frame: nothing fades.
        assertTrue(reveal.arrivals("reply", blocks("Hello"), now = 0.0).isEmpty())
        // The same render again records nothing.
        assertTrue(reveal.arrivals("reply", blocks("Hello"), now = 0.01).isEmpty())
        // Growth fades from where the block ended.
        assertEquals(mapOf(0 to listOf(arrival(5, 0.05))), reveal.arrivals("reply", blocks("Hello there"), now = 0.05))
        // Two chunks within the fade keep their own times; earlier text never fades again.
        assertEquals(
            mapOf(0 to listOf(arrival(5, 0.05), arrival(11, 0.1))),
            reveal.arrivals("reply", blocks("Hello there, friend"), now = 0.1),
        )
        // A new block in a reply on screen arrives whole.
        assertEquals(
            mapOf(0 to listOf(arrival(5, 0.05), arrival(11, 0.1)), 1 to listOf(arrival(0, 0.2))),
            reveal.arrivals("reply", blocks("Hello there, friend\n\nNext"), now = 0.2),
        )
        // Once the fade has run, a reload or reused row draws it plainly.
        assertTrue(reveal.arrivals("reply", blocks("Hello there, friend\n\nNext"), now = 1.0).isEmpty())
    }

    @Test fun aBlockChangingKindReplacesItselfWithoutFading() {
        val reveal = TranscriptTextReveal()
        reveal.arrivals("reply", blocks("Intro"), now = 0.0)
        reveal.arrivals("reply", blocks("Intro\n\n| a | b |"), now = 0.5)
        assertTrue(reveal.arrivals("reply", blocks("Intro\n\n| a | b |\n| --- | --- |\n| 1 | 2 |"), now = 1.0).isEmpty())
    }

    @Test fun aBlockMergedAwayAndStreamedInAgainFades() {
        fun paragraph(id: Int, text: String) = OpenCodeMarkdownBlock(id, Kind.Paragraph(InlineText.plain(text)))
        val reveal = TranscriptTextReveal()
        reveal.arrivals("reply", listOf(paragraph(0, "Hello"), paragraph(1, "World")), now = 0.0)
        // The second block merges into the first…
        reveal.arrivals("reply", listOf(paragraph(0, "Hello World")), now = 1.0)
        // …and a new one streams in at its index: it arrives whole.
        assertEquals(
            mapOf(1 to listOf(arrival(0, 2.0))),
            reveal.arrivals("reply", listOf(paragraph(0, "Hello World"), paragraph(1, "Next")), now = 2.0),
        )
    }

    @Test fun shrinkingTextDropsArrivalsPastItsEnd() {
        val reveal = TranscriptTextReveal()
        reveal.arrivals("reply", blocks("Some **bo"), now = 0.0)
        reveal.arrivals("reply", blocks("Some **bold"), now = 0.05)
        // `**bold**` closes: two characters fewer are shown.
        val arrivals = reveal.arrivals("reply", blocks("Some **bold**"), now = 0.06)
        assertTrue((arrivals[0] ?: emptyList()).all { it.offset < 9 })
    }

    /** The per-flush work (arrivals and cached lengths) stays O(1)-ish across a 20 kB stream. */
    @Test fun twentyKilobyteStreamedReplyInTwoHundredChunksStaysWithinPerFlushBudget() {
        val chunk = "token_stream_word ".repeat(5) + "end_chunk " // 100 bytes
        assertEquals(100, chunk.toByteArray().size)
        val reveal = TranscriptTextReveal()
        reveal.chunkDuration = { 0.20 }
        reveal.chunkStagger = { 0.0 }
        val accumulated = StringBuilder(20_000)
        val flushMicros = ArrayList<Double>(200)
        for (step in 0 until 200) {
            accumulated.append(chunk)
            val now = step * 0.05 // 4 active chunks in flight per flush
            val block = OpenCodeMarkdownBlock(0, Kind.Paragraph(InlineText.plain(accumulated.toString())))
            val t0 = System.nanoTime()
            val arrivals = reveal.arrivals("stream-20kb", listOf(block), now)
            val active = (arrivals[0] ?: emptyList()).filter { now - it.time < it.duration }
            val known = reveal.length("stream-20kb", 0)
            flushMicros += (System.nanoTime() - t0) / 1_000.0
            assertEquals(accumulated.length, known)
            if (step > 0) assertTrue(active.isNotEmpty())
        }
        if (System.getenv("CI") != "true") {
            val sorted = flushMicros.sorted()
            val median = sorted[sorted.size / 2]
            val p95 = sorted[((sorted.size - 1) * 0.95).toInt()]
            val lateMedian = flushMicros.subList(180, 200).sorted()[10]
            assertTrue("Median per-flush work exceeded 2 ms: $median us", median < 2_000)
            assertTrue("p95 per-flush work exceeded 5 ms: $p95 us", p95 < 5_000)
            assertTrue("Late-stream (20 kB) per-flush median exceeded 3 ms: $lateMedian us", lateMedian < 3_000)
        }
    }

    /** Android measures blocks in UTF-16 chars (Compose text offsets). */
    @Test fun incrementalCodeMeasurementTracksASCIIUnicodeAndCRLFGrowth() {
        val reveal = TranscriptTextReveal()
        assertTrue(reveal.arrivals("code", blocks("```swift\nlet a = 1\n"), now = 0.0).isEmpty())
        assertEquals(9, reveal.length("code", 0))

        val a1 = reveal.arrivals("code", blocks("```swift\nlet a = 1\nlet b = \"café ☕️\"\n"), now = 0.05)
        assertEquals(mapOf(0 to listOf(arrival(9, 0.05))), a1)
        assertEquals("let a = 1\nlet b = \"café ☕️\"".length, reveal.length("code", 0))

        val crlf = "let a = 1\nlet b = \"café ☕️\"\r\nlet c = 3\r\n"
        reveal.arrivals("code", listOf(OpenCodeMarkdownBlock(0, Kind.Code(OpenCodeMarkdownBlock.HighlightedCode(crlf, "swift")))), now = 0.10)
        assertEquals(crlf.length, reveal.length("code", 0))
    }
}

/** The growing tail is "streamed"; a reply that stops growing lets go. */
class LineHoldTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun blocks(text: String) = OpenCodeMarkdownBlock.blocks(text)

    @Test fun onlyTheGrowingTailHoldsAndSettlingLetsGo() {
        val reveal = TranscriptTextReveal()
        reveal.chunkDuration = { 0.2 }
        reveal.chunkStagger = { 0.0 }
        reveal.arrivals("reply", blocks("Hello"), now = 0.0)
        assertFalse("History doesn't hold", reveal.streamed("reply", 0))
        reveal.arrivals("reply", blocks("Hello there"), now = 0.05)
        assertTrue(reveal.streamed("reply", 0))
        // A second paragraph starts: the first is no longer the tail.
        val two = blocks("Hello there\n\nA second")
        reveal.arrivals("reply", two, now = 0.1)
        assertFalse(reveal.streamed("reply", two[0].id))
        assertTrue(reveal.streamed("reply", two[1].id))
        // Text streaming into another item leaves this one behind.
        reveal.arrivals("next", blocks("Then"), now = 0.2)
        reveal.arrivals("next", blocks("Then more"), now = 0.3)
        assertFalse(reveal.streamed("reply", two[1].id))
        assertTrue(reveal.streamed("next", 0))
        // The run ends: settled, the block renders through the plain path.
        reveal.settle("next")
        assertFalse(reveal.streamed("next", 0))
        reveal.arrivals("next", blocks("Then more"), now = 1.0)
        assertFalse("Rendering again doesn't hold again", reveal.streamed("next", 0))
    }
}

class TextRevealTuningTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun blocks(text: String) = OpenCodeMarkdownBlock.blocks(text)

    @Test fun aNewDurationAppliesToTheNextChunkOnly() {
        val reveal = TranscriptTextReveal()
        var duration = 0.2
        reveal.chunkDuration = { duration }
        reveal.arrivals("reply", blocks("Hello"), now = 0.0)
        reveal.arrivals("reply", blocks("Hello there"), now = 0.05)
        duration = 0.5
        assertEquals(
            mapOf(0 to listOf(TranscriptTextReveal.Arrival(5, 0.05, 0.2), TranscriptTextReveal.Arrival(11, 0.1, 0.5))),
            reveal.arrivals("reply", blocks("Hello there, friend"), now = 0.1),
        )
        // The first chunk ends on its own clock; the second runs on.
        assertEquals(
            mapOf(0 to listOf(TranscriptTextReveal.Arrival(11, 0.1, 0.5))),
            reveal.arrivals("reply", blocks("Hello there, friend"), now = 0.4),
        )
    }

    @Test fun staggerSpacesChunkStartsAndZeroLeavesThemAlone() {
        val reveal = TranscriptTextReveal()
        var stagger = 0.0
        reveal.chunkStagger = { stagger }
        reveal.arrivals("reply", blocks("A"), now = 0.0)
        reveal.arrivals("reply", blocks("A b"), now = 0.01)
        stagger = 0.08
        val arrivals = reveal.arrivals("reply", blocks("A b c"), now = 0.02)
        val times = arrivals[0]?.map { it.time } ?: emptyList()
        assertEquals(2, times.size)
        assertEquals(0.01, times[0], 1e-9)
        assertEquals(0.09, times[1], 1e-9)
    }
}

// Shared fixtures

private val standardColors = OpenCodeColors.resolve(AppTheme.System, isDark = false)
private val highContrastColors = OpenCodeColors.resolve(AppTheme.System, isDark = false, highContrast = true)

private fun markdownStyle(colors: OpenCodeColors) = MarkdownStyle(TranscriptLayoutTuning(), colors)

internal fun kindName(block: OpenCodeMarkdownBlock): String = block.kind::class.simpleName ?: "?"

internal fun highlightedCode(blocks: List<OpenCodeMarkdownBlock>): List<List<SyntaxToken>> =
    blocks.mapNotNull { (it.kind as? Kind.Code)?.code?.tokens }

/** The end offset of every code-point prefix of [text], 0 through its length. */
internal fun codePointEnds(text: String): List<Int> {
    val ends = arrayListOf(0)
    var i = 0
    while (i < text.length) {
        i += Character.charCount(text.codePointAt(i))
        ends += i
    }
    return ends
}

private fun present(
    inline: InlineText,
    style: MarkdownStyle,
    headingLevel: Int? = null,
    secondary: Boolean = false,
    tonesBody: Boolean = true,
): AnnotatedString = MarkdownPresentation.present(
    inline,
    PresentationOptions(
        foreground = style.bodyColor,
        textSize = headingLevel?.let { style.headingSize(it).sp } ?: style.bodySize,
        secondary = secondary,
        headingLevel = headingLevel,
        tonesBody = tonesBody,
    ),
    style,
    targets = emptyMap(),
    density = Density(1f),
    links = null,
    siteIcon = null,
).text

private class StyledSpan(val text: String, val style: SpanStyle, val link: String?)

private fun spans(text: AnnotatedString): List<StyledSpan> = text.spanStyles.map { range ->
    val link = text.getLinkAnnotations(range.start, range.end).firstNotNullOfOrNull { (it.item as? LinkAnnotation.Clickable)?.tag }
    StyledSpan(text.text.substring(range.start, range.end), range.item, link)
}

private fun codeSpans(text: AnnotatedString) = spans(text).filter { it.style.fontFamily == OpenCodeFonts.monospace }

private fun boldSpans(text: AnnotatedString) = spans(text).filter { it.style.fontWeight == FontWeight.Bold }
