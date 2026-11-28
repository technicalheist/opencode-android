package ai.opencode.feature.transcript.markdown

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.media.MemoryWarningCenter
import ai.opencode.server.EventBatcherHooks
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Live assistant text as Markdown blocks, keyed by part ID. The event batcher prepares each flushed
 * text off the reducer's path ([prepare], installed with [withMarkdownStream]), so a row's [blocks]
 * for the same text is a lookup. Any other text (a reconnect, a refresh, a part opened mid-stream)
 * projects incrementally from the part's [MarkdownStream] without disturbing the batcher's.
 */
class MarkdownStreamClient(
    /** The blocks of `text`, already held back by `holdingPendingMarker` while streaming. */
    val blocks: (partID: String, text: String, streaming: Boolean) -> List<OpenCodeMarkdownBlock>,
    /** Projects `text` ahead of the row asking for it. */
    val prepare: (partID: String, text: String, streaming: Boolean) -> Unit,
) {
    constructor(store: MarkdownStreamStore) : this(
        blocks = { partID, text, streaming -> store.blocks(partID, text, streaming) },
        prepare = { partID, text, streaming -> store.blocks(partID, text, streaming) },
    )

    companion object Key : DependencyKey<MarkdownStreamClient> {
        override val liveValue: MarkdownStreamClient by lazy { MarkdownStreamClient(MarkdownStreamStore(memoryWarnings = MemoryWarningCenter.default)) }

        /** Each test gets its own store, so one test's parts never answer another's. */
        override val testValue: MarkdownStreamClient get() = MarkdownStreamClient(MarkdownStreamStore())
    }
}

/**
 * [EventBatcherHooks] with [EventBatcherHooks.prepareMarkdown] projecting through [client]: a
 * streaming part's accumulated text is held back by `holdingPendingMarker` first, exactly as the
 * row asks for it, so the row's lookup hits. Other hooks are kept. Install at startup:
 * `DependencyValues.installRoot { this[EventBatcherHooks] =
 * this[EventBatcherHooks].withMarkdownStream() }`.
 */
fun EventBatcherHooks.withMarkdownStream(client: MarkdownStreamClient = dependency(MarkdownStreamClient)): EventBatcherHooks =
    EventBatcherHooks(
        prepareMarkdown = { partID, text, isStreaming ->
            client.prepare(partID, if (isStreaming) OpenCodeMarkdownBlock.holdingPendingMarker(text) else text, isStreaming)
        },
        record = record,
    )

/**
 * The parts' streams and their latest projections. Parsing happens outside the lock: a projection
 * that loses a race is still returned, and its stream is kept only when nothing newer was stored
 * meanwhile and it doesn't roll the stream back to an older text. A part keeps two streams, since a
 * session opened mid-reply accumulates only the text after it opened while the batcher holds the
 * whole reply; each text continues the stream it shares the most with. Stored streams are never
 * mutated: an update works on a copy. A memory warning empties it; a part's next text parses again
 * from the start.
 */
class MarkdownStreamStore(
    private val maximumParts: Int = 16,
    memoryWarnings: MemoryWarningCenter? = null,
    private val makeStream: () -> MarkdownStream = { MarkdownStream() },
) {
    private class Projection(val text: String, val streaming: Boolean, val blocks: List<OpenCodeMarkdownBlock>)

    private class Slot(var stream: MarkdownStream, var generation: Int = 0, var lastUse: Int = 0)

    private class Part {
        val slots = ArrayList<Slot>()
        /** Newest first. A batch can hold a part's text more than once. */
        val recent = ArrayList<Projection>()
        var lastUse = 0
    }

    private val lock = Any()
    private val parts = HashMap<String, Part>()
    private var clock = 0

    init {
        memoryWarnings?.observe(::clear)
    }

    val partCount: Int get() = synchronized(lock) { parts.size }

    fun clear() = synchronized(lock) { parts.clear() }

    /** The parse cost of the stream that last projected [partID], for guard checks. */
    fun cost(partID: String): MarkdownStream.Cost? =
        synchronized(lock) { parts[partID]?.slots?.maxByOrNull { it.lastUse }?.stream?.cost }

    fun blocks(partID: String, text: String, streaming: Boolean): List<OpenCodeMarkdownBlock> {
        class Found(val streams: List<MarkdownStream>, val generations: List<Int>, val hit: List<OpenCodeMarkdownBlock>?)
        val found = synchronized(lock) {
            clock++
            val part = parts.getOrPut(partID) { Part() }
            part.lastUse = clock
            val hit = part.recent.firstOrNull { it.streaming == streaming && it.text == text }
            Found(part.slots.map { it.stream }, part.slots.map { it.generation }, hit?.blocks)
        }
        found.hit?.let { return it }
        // The stream whose committed units this text keeps, or one it extends.
        var best: Int? = null
        var bestCommitted = 0
        var bestShared = 0
        for ((index, stream) in found.streams.withIndex()) {
            val (shared, committed) = stream.reuse(text)
            if (committed <= 0 && shared != stream.text.length) continue
            if (best == null || committed > bestCommitted || (committed == bestCommitted && shared > bestShared)) {
                best = index
                bestCommitted = committed
                bestShared = shared
            }
        }
        val stream = best?.let { found.streams[it].copy() } ?: makeStream()
        val blocks = stream.update(text, streaming)
        synchronized(lock) {
            val part = parts[partID] ?: return blocks
            clock++
            if (best != null && best < part.slots.size) {
                // Keep the newer stream when an older text was asked for after it.
                val stored = part.slots[best].stream.text
                val older = text.length < stored.length && SwiftText.commonPrefix(stored, text) == text.length
                if (part.slots[best].generation == found.generations[best] && !older) {
                    part.slots[best].stream = stream
                    part.slots[best].generation++
                }
                part.slots[best].lastUse = clock
            } else if (best != null) {
                // The part was dropped and started again meanwhile.
            } else if (part.slots.size < 2) {
                part.slots.add(Slot(stream, lastUse = clock))
            } else {
                val stale = part.slots.minByOrNull { it.lastUse }!!
                stale.stream = stream
                stale.generation++
                stale.lastUse = clock
            }
            part.recent.add(0, Projection(text, streaming, blocks))
            if (part.recent.size > 3) part.recent.removeAt(part.recent.size - 1)
            if (parts.size > maximumParts) {
                parts.minByOrNull { it.value.lastUse }?.key?.let(parts::remove)
            }
        }
        return blocks
    }
}

/**
 * The parsed blocks of an assistant text part. Android items keep raw text, so rows ask here: a
 * lookup in [MarkdownStreamClient] (usually prepared by the event batcher), else an incremental
 * projection that re-parses only the live tail. [isStreaming] text is still arriving: a last line
 * holding only a block's marker waits for its first word (`holdingPendingMarker`) and the tail's
 * last block is healed. Recomposition with the same text is free.
 */
@Composable
fun rememberMarkdownBlocks(partID: String, text: String, isStreaming: Boolean = false): List<OpenCodeMarkdownBlock> {
    val client = remember { dependency(MarkdownStreamClient) }
    return remember(partID, text, isStreaming) {
        val shown = if (isStreaming) OpenCodeMarkdownBlock.holdingPendingMarker(text) else text
        client.blocks(partID, shown, isStreaming)
    }
}
