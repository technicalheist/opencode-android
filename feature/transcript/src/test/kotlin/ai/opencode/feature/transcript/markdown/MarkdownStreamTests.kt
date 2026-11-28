package ai.opencode.feature.transcript.markdown

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.media.MemoryWarningCenter
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/*
 * The streaming Markdown guards (docs/transcript.md, "Streaming Markdown"): completed blocks parse
 * once, per-delta work is bounded by the live tail, and the result always equals a full parse of
 * the same text. Android's [MarkdownStream.Cost] counts UTF-16 chars (the generated replies are
 * ASCII, so the numbers are the same). Unicode-scalar prefixes → code point prefixes.
 */
class MarkdownStreamTests {
    @get:Rule val isolation = TestIsolationRule()

    // Equivalence with a full parse

    @Test fun streamingEqualsAFullParseAtEveryBoundary() {
        for (document in corpus) {
            val live = MarkdownStream(inlineCache = null)
            val settled = MarkdownStream(inlineCache = null)
            val ends = codePointEnds(document)
            // Rewind too: a refresh may replace the whole text, not just append.
            for (end in ends + ends.reversed()) {
                val prefix = document.substring(0, end)
                val visible = OpenCodeMarkdownBlock.holdingPendingMarker(prefix)
                val streamed = live.update(visible, streaming = true)
                val full = OpenCodeMarkdownBlock.blocks(visible, inlineCache = null)
                val committed = live.committedBlockCount
                assertTrue(committed <= streamed.size)
                assertEquals("committed blocks differ at \"$visible\"", full.take(committed), streamed.take(committed))

                val whole = settled.update(prefix, streaming = false)
                val expected = OpenCodeMarkdownBlock.blocks(prefix, inlineCache = null)
                assertEquals("settled blocks differ at \"$prefix\"", expected, whole)
                assertEquals(highlightedCode(expected), highlightedCode(whole))
            }
            assertEquals(OpenCodeMarkdownBlock.blocks(document, inlineCache = null), live.update(document, streaming = false))
        }
    }

    @Test fun chunkedDeltasEndInAFullParse() {
        val random = SplitMix(7u)
        for (document in corpus + reply(words = 1_500)) {
            val ends = codePointEnds(document)
            val scalars = ends.size - 1
            repeat(4) {
                val stream = MarkdownStream()
                var index = 0
                while (index < scalars) {
                    val end = minOf(scalars, index + 1 + (random.next() % 40u).toInt())
                    val text = document.substring(0, ends[end])
                    index = end
                    val visible = OpenCodeMarkdownBlock.holdingPendingMarker(text)
                    val streamed = stream.update(visible, streaming = true)
                    val full = OpenCodeMarkdownBlock.blocks(visible, inlineCache = null)
                    assertEquals(full.take(stream.committedBlockCount), streamed.take(stream.committedBlockCount))
                }
                val final = stream.update(document, streaming = false)
                val expected = OpenCodeMarkdownBlock.blocks(document, inlineCache = null)
                assertEquals(expected, final)
                assertEquals(highlightedCode(expected), highlightedCode(final))
                assertEquals(OpenCodeMarkdownBlock.markdown(expected), OpenCodeMarkdownBlock.markdown(final))
            }
        }
    }

    // Parse once, bounded by the tail

    /**
     * A 4,000-word reply streamed in small deltas: every completed unit is parsed exactly once,
     * and no update parses more than what followed the previous committed prefix (plus a few
     * healing chars).
     */
    @Test fun completedBlocksParseOnceAndDeltasParseOnlyTheTail() {
        val reply = reply(words = 4_000)
        val parsed = java.util.Collections.synchronizedList(ArrayList<String>())
        val stream = MarkdownStream(inlineCache = null) { unit ->
            parsed += unit
            OpenCodeMarkdownBlock.parseTextSegment(unit, null)
        }
        val committed = ArrayList<IntRange>()
        var maxTail = 0
        var totalParsed = 0
        var wholeText = 0
        val random = SplitMix(42u)
        val ends = codePointEnds(reply)
        val scalars = ends.size - 1
        var index = 0
        var deltas = 0
        while (index < scalars) {
            val end = minOf(scalars, index + 4 + (random.next() % 28u).toInt())
            val text = reply.substring(0, ends[end])
            index = end
            deltas++
            val before = stream.committedLength
            stream.update(OpenCodeMarkdownBlock.holdingPendingMarker(text), streaming = true)
            val cost = stream.cost
            assertEquals("a forward stream never re-parses a committed unit", before, cost.reused)
            assertTrue("parse work beyond the tail at delta $deltas", cost.parsed <= text.length - before + 16)
            cost.committed.firstOrNull()?.let { assertEquals(before, it.first) }
            committed += cost.committed
            maxTail = maxOf(maxTail, cost.tail.count())
            totalParsed += cost.parsed
            wholeText += text.length
        }
        // Each completed unit was parsed once, in order, without overlap. Closed code fences are
        // units too; they highlight rather than parse.
        for ((range, next) in committed.zipWithNext()) assertEquals(range.last + 1, next.first)
        assertTrue((committed.lastOrNull()?.let { it.last + 1 } ?: 0) > reply.length - 200)
        val prose = committed.filter { !reply.substring(it.first, it.last + 1).startsWith("```") }
        val units = synchronized(parsed) { parsed.toList() }
        assertEquals(prose.size, units.size)
        assertTrue("the reply has closed code fences", committed.size - prose.size >= 5)
        for ((range, unit) in prose.zip(units)) assertEquals(reply.substring(range.first, range.last + 1), unit)
        // The tail stays local: a block or two, never the reply.
        assertTrue(reply.toByteArray().size > 20_000)
        assertTrue("largest live tail $maxTail chars", maxTail < 2_500)
        // Linear in the reply, not quadratic like re-parsing the whole text.
        assertTrue("parsed $totalParsed of $wholeText whole-text chars", totalParsed * 20 < wholeText)
        assertEquals(OpenCodeMarkdownBlock.blocks(reply, inlineCache = null), stream.update(reply, streaming = false))
    }

    @Test fun storeAnswersPreparedTextsWithoutParsing() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val store = MarkdownStreamStore {
            MarkdownStream(inlineCache = null) { unit ->
                calls.incrementAndGet()
                OpenCodeMarkdownBlock.parseTextSegment(unit, null)
            }
        }
        val client = MarkdownStreamClient(store)
        val first = "Intro paragraph.\n\nSecond paragraph.\n\n- item"
        val next = "$first one\n- item two\n\nTail **bold"
        client.prepare("part", first, true)
        val parsedAfterPrepare = calls.get()
        assertEquals(store.blocks("part", first, true), client.blocks("part", first, true))
        assertEquals("a prepared text is a lookup", parsedAfterPrepare, calls.get())

        // The batcher ran ahead: an older text still reuses committed units and leaves the newer stream in place.
        client.prepare("part", next, true)
        val older = client.blocks("part", "$first one", true)
        val expected = OpenCodeMarkdownBlock.blocks("$first one", inlineCache = null)
        assertEquals(expected.take(2), older.take(2))
        assertTrue((store.cost("part")?.reused ?: 0) > 0)
        assertEquals(OpenCodeMarkdownBlock.blocks(next, inlineCache = null), client.blocks("part", next, false))
    }

    /**
     * A session opened mid-reply accumulates only what arrives after it opened, while the
     * batcher holds the whole reply: each keeps its own stream instead of re-parsing everything
     * for the other on every delta.
     */
    @Test fun aSessionOpenedMidReplyKeepsItsOwnStream() {
        val store = MarkdownStreamStore()
        val reply = reply(words = 800)
        var whole = reply.substring(0, reply.length / 2)
        var opened = ""
        var index = reply.length / 2
        var reused = 0
        store.blocks("part", whole, true)
        while (index < reply.length) {
            val end = minOf(reply.length, index + 24)
            val delta = reply.substring(index, end)
            index = end
            whole += delta
            opened += delta
            store.blocks("part", whole, true)
            assertTrue("the batcher's stream continues", (store.cost("part")?.reused ?: 0) > 0)
            val blocks = store.blocks("part", opened, true)
            val cost = store.cost("part")!!
            assertTrue(cost.parsed <= opened.length - cost.reused + 16)
            reused = cost.reused
            // Only the last block is healed.
            val full = OpenCodeMarkdownBlock.blocks(opened, inlineCache = null)
            assertEquals(full.take(maxOf(0, blocks.size - 1)), blocks.dropLast(1))
        }
        assertTrue(reused > opened.length / 2)
    }

    @Test fun storeKeepsABoundedNumberOfParts() {
        val store = MarkdownStreamStore(maximumParts = 4)
        for (index in 0 until 20) store.blocks("part-$index", "Text $index", true)
        assertTrue(store.partCount <= 4)
    }

    @Test fun markdownStreamsGoOnAMemoryWarning() {
        val center = MemoryWarningCenter()
        val store = MarkdownStreamStore(memoryWarnings = center)
        val text = (0 until 4).joinToString("\n\n") { "Paragraph $it of part 0, with **bold** and `code`." }
        val blocks = store.blocks("part", text, true)
        assertEquals(1, store.partCount)
        center.post()
        assertEquals(0, store.partCount)
        assertEquals("The next text parses the same from the start", blocks, store.blocks("part", text, true))
    }

    // Healing the live tail

    @Test fun healsTheTailLikeRemend() {
        for ((raw, healed) in listOf(
            "hello **world" to "hello **world**",
            "say `code" to "say `code`",
            "*foo*bar" to "*foo*bar",
            "foo*bar" to "foo*bar",
            "*foo*bar*baz" to "*foo*bar*baz*",
            "\\(P(x_{t+1})\\)" to "\\(P(x_{t+1})\\)",
            "\\(w^{*}\\) and *next" to "\\(w^{*}\\) and *next*",
            "see [docs](https://example.com/gu" to "see docs",
            "see ![chart](https://example.com/c" to "see ",
            "Paragraph **" to "Paragraph",
            "**Bold heading:** details" to "**Bold heading:** details",
            "***both" to "***both***",
            "~~gone" to "~~gone~~",
            "snake_case_name" to "snake_case_name",
            "costs \$5 and **x" to "costs \$5 and **x**",
            "`a` and `b" to "`a` and `b`",
            "5 * 3 = 15" to "5 * 3 = 15",
            "math \$x*y\$ and _it" to "math \$x*y\$ and _it_",
            "Café **naïve" to "Café **naïve**",
        )) {
            assertEquals(raw, healed, MarkdownTailHeal.heal(raw))
        }
    }

    @Test fun healsOnlyTheLastBlock() {
        assertEquals("- a **b\n- c *d*", MarkdownTailHeal.healLastBlock("- a **b\n- c *d"))
        assertEquals("Para **open\n\n", MarkdownTailHeal.healLastBlock("Para **open\n\n"))
        assertEquals("Line one **bold\ncontinues**", MarkdownTailHeal.healLastBlock("Line one **bold\ncontinues"))
        assertEquals("# Title\nBody *it*", MarkdownTailHeal.healLastBlock("# Title\nBody *it"))
        assertEquals("| a | b |\n|---|---|\n| **x | `y`", MarkdownTailHeal.healLastBlock("| a | b |\n|---|---|\n| **x | `y"))
        assertEquals("> q **a\n> b**", MarkdownTailHeal.healLastBlock("> q **a\n> b"))
        assertEquals("\$\$\n\\sum_{i}^{*}", MarkdownTailHeal.healLastBlock("\$\$\n\\sum_{i}^{*}"))
    }

    @Test fun healedTailDrawsFormattingNotMarkers() {
        val stream = MarkdownStream(inlineCache = null)
        val text = (stream.update("Done.\n\nHello **world", streaming = true).lastOrNull()?.kind as? Kind.Paragraph)?.text
            ?: return fail("Expected a paragraph")
        assertEquals("Hello world", text.text)
        assertTrue(text.runs.any { it.strong })
    }

    /** A half-written `**` never shows as `****`, literal stars, or a divider. */
    @Test fun partialMarkersNeverFlashWhileStreaming() {
        val markdown = "Paragraph one.\n\n**Bold heading:** details here.\n\n- **Item** with `code` and [link](https://example.com/x)\n"
        val stream = MarkdownStream(inlineCache = null)
        for (end in codePointEnds(markdown).drop(1)) {
            val blocks = stream.update(OpenCodeMarkdownBlock.holdingPendingMarker(markdown.substring(0, end)), streaming = true)
            for (block in blocks) {
                assertFalse("divider at $end", block.kind == Kind.Divider)
                val plain = plainText(block)
                assertFalse("literal markers at $end: $plain", plain.contains("**"))
                assertFalse("raw link syntax at $end: $plain", plain.contains("]("))
            }
        }
    }

    companion object {
        /** Tricky Markdown whose block boundaries can move as text arrives. */
        val corpus: List<String> = listOf(
            "## Plan\n\nKeep **stable identities** while the reply grows. Read `Sources/Session.swift` and [the spec](docs/spec.pdf).\nA second line of the *same* paragraph.\n\nThird paragraph with [a link that is long](https://example.com/a/long/path?query=1) inside.",
            "1. **First**\n   - `Sources/One.swift`\n   - nested two\n     - deeper\n2. Second\n\n- [ ] Open **task**\n  - [x] Done `Sources/One.swift`\n- [X] Uppercase\n\nAfter list\n  - indented after a paragraph\n",
            "- a\n\n  - b after a blank\n\n> quote between\n\n  - c keeps the nesting\n\nPara resets\n\n  - d starts fresh\n\n12. twelve\n13. thirteen\n\n    deep continuation\n",
            "| File | State |\n|---|---|\n| [report](reports/one.pdf) | **Ready** |\n| `a.swift` | done |\nAfter table\n\n| x |\n|--|\n| y |\n\n| not | a table\nbecause no separator",
            "Before\n\n```swift\nlet value = \"hello\"\n```\n\n```mermaid\ngraph TD; A-->B\n```\n\nText ```inline``` mid\n\n````\nfour\n````\n\n```\n```\n\n``````\n\nAfter\n\n```python\nprint('unclosed')\n",
            "Inline \$x^2\$ and \\(y\\), costs \$5 and \$6.\n\n\$\$\n\\sum_{i=1}^n i\n\$\$\n\n\\[\na = b\n\\]\n\n\$\$\na\n- b inside math\n## not a heading\n\$\$\n\n\$\$\nnot closed\n\nAfter math\n\$\$x\$\$\n- item after math\n## heading after\n",
            "> A **quoted** line\n> continuation\n>\n> Second\nAfter\n\n---\n\n***\n\n- - -\n\nEnd\n___\n",
            "[one](reports/one.pdf)\n\n[again](reports/one.pdf)\n\n![clip](clips/one.mp4)\n\nhttps://github.com/example/project/pull/42\n\n- see `Sources/Feature.swift`\n- and `Sources/Feature.swift` again\n\n[scheme](opencode-file://open?path=reports/two.pdf)",
            "Café 🧑🏽‍💻 e\u0301\n\n`日本`語 and `é` and ``a``\u0301\n\n``\u0301`x\n\nCRLF line\r\nnext\r\n\r\n- item\r\n\nGreek \u1FEF``fence?\n\nend\n\n```\u0301\ncode\n```\n\nlast",
            "# One\nPara under\n## Two\n- item\n- item two\ncontinuation lazy\n3. three\n#hashtag not heading\n#### Four\n####### seven\n# \nTail",
            "Escaped \\*text\\*, &amp; entities, <https://example.com>, ~~removed~~ and `a\\b`.\n\n- _Unfinished\n\n<div>\n\nhtml\n\n</div>\n\n**bold *nested italic* still bold** and __under__ and *a*b",
            "![alt](https://x.com/a.png)\n![two](b.png \"title\")\n\nText\n\nPara\n    indented continuation\n\n    indented para start\n\nNext\n\n\u00A0\n\nAfter a non-breaking space line",
            "Steps:\n\n1. Run `bun test`\n\n   ```sh\n   bun test\n   ```\n\n2. Then **check** [the log](logs/run.txt)\n\n| A | B |\n|---|---|\n| `x` | [y](https://y.com) |\n\nDone.",
        )

        private fun plainText(block: OpenCodeMarkdownBlock): String = when (val kind = block.kind) {
            is Kind.Paragraph -> kind.text.text
            is Kind.Blockquote -> kind.text.text
            is Kind.Heading -> kind.text.text
            is Kind.NumberedItem -> kind.text.text
            is Kind.ListItem -> kind.text.text
            else -> ""
        }

        /** A deterministic reply of about [words] words: headings, prose with inline formatting and links, lists, code fences and tables. */
        fun reply(words: Int, seed: ULong = 1u): String {
            val random = SplitMix(seed)
            val vocabulary = listOf(
                "stream", "block", "parse", "reducer", "tail", "cache", "layout", "render", "commit", "boundary",
                "fence", "paragraph", "delta", "batch", "session", "transcript", "identity", "settle", "proof", "heal",
            )
            fun word() = vocabulary[(random.next() % vocabulary.size.toULong()).toInt()]
            fun sentence(): String {
                val parts = ArrayList<String>()
                for (index in 0 until 8 + (random.next() % 10u).toInt()) {
                    var piece = word()
                    when ((random.next() % 23u).toInt()) {
                        0 -> piece = "**$piece ${word()}**"
                        1 -> piece = "`Sources/${piece.replaceFirstChar { it.uppercase() }}.swift`"
                        2 -> piece = "[$piece](https://example.com/$piece/$index)"
                        3 -> piece = "*$piece*"
                    }
                    parts += piece
                }
                val joined = parts.joinToString(" ")
                return joined.take(1).uppercase() + joined.drop(1) + "."
            }
            val sections = ArrayList<String>()
            var count = 0
            var section = 0
            while (count < words) {
                section++
                val lines = arrayListOf("## Section $section: ${word()} ${word()}", "")
                repeat(2 + (random.next() % 2u).toInt()) {
                    val paragraph = (0 until 3 + (random.next() % 3u).toInt()).joinToString(" ") { sentence() }
                    lines += listOf(paragraph, "")
                }
                when ((random.next() % 4u).toInt()) {
                    0 -> lines += (1..4).map { "- ${sentence()}" } + ""
                    1 -> lines += listOf("```swift") + (0 until 8).map { "let ${word()}$it = ${word()}(index: $it) // ${word()}" } + listOf("```", "")
                    2 -> lines += listOf("| Stage | State |", "|---|---|") + (0 until 4).map { "| ${word()} | **${word()}** |" } + ""
                    else -> lines += (1..3).map { "$it. ${sentence()}" } + ""
                }
                val text = lines.joinToString("\n")
                count += text.split(' ', '\n').count { it.isNotEmpty() }
                sections += text
            }
            return sections.joinToString("\n") + "\nThat's the whole reply."
        }
    }
}

/** A deterministic 64-bit generator. */
class SplitMix(private var state: ULong) {
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
}

/**
 * The nightly streaming parse-cost metric: a 4,000-word reply streamed in small deltas through
 * [MarkdownStream], with the chars each delta parses, beside the whole-text re-parse it replaced.
 * Runs only with `MARKDOWN_STREAM_COST=1`.
 */
class MarkdownStreamCostTests {
    @Test fun testStreamingParseCost() {
        assumeTrue(System.getenv("MARKDOWN_STREAM_COST") == "1")
        val reply = MarkdownStreamTests.reply(words = 4_000)
        val random = SplitMix(9u)
        val ends = codePointEnds(reply)
        val scalars = ends.size - 1
        val stream = MarkdownStream(inlineCache = OpenCodeMarkdownBlock.InlineCache())
        val parsed = ArrayList<Int>()
        var wholeText = 0
        var index = 0
        while (index < scalars) {
            val end = minOf(scalars, index + 8 + (random.next() % 32u).toInt())
            index = end
            val visible = OpenCodeMarkdownBlock.holdingPendingMarker(reply.substring(0, ends[end]))
            stream.update(visible, streaming = true)
            parsed += stream.cost.parsed
            wholeText += visible.length
        }
        assertEquals(OpenCodeMarkdownBlock.blocks(reply, inlineCache = null), stream.update(reply, streaming = false))
        val sorted = parsed.sorted()
        println("METRIC stream_parse_bytes_p50=${sorted[sorted.size / 2]} p95=${sorted[sorted.size * 95 / 100]} max=${sorted.last()} n=${sorted.size}")
        println("METRIC stream_parse_total_kb=${parsed.sum() / 1024} n=1")
        println("METRIC stream_parse_permille_of_whole_text=${parsed.sum() * 1000 / maxOf(wholeText, 1)} n=1")
    }
}
