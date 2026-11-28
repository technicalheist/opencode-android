package ai.opencode.server

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.FileDirectory
import ai.opencode.core.sharing.InstallHistory
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.installHistory
import ai.opencode.core.sharing.shared
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@Serializable
data class CachedHomeSnapshot(
    val serverID: String,
    val snapshot: OpenCodeHomeSnapshot,
    /** When the snapshot's running state was last known live; a cold launch shows it only while fresh. */
    @Serializable(with = InstantSerializer::class) val capturedAt: Instant? = null,
)

/** The transcript cache: the last windows of recently opened sessions, bounded in count, age and bytes. */
@Serializable
data class CachedTranscriptSnapshots(
    val version: Int = CURRENT_VERSION,
    val entries: List<Entry> = emptyList(),
) {
    @Serializable
    data class Priority(
        val needsInput: Boolean = false,
        val isWorking: Boolean = false,
        val isPinned: Boolean = false,
        val isUnread: Boolean = false,
    ) : Comparable<Priority> {
        val rank: Int
            get() = (if (needsInput) 8 else 0) or (if (isWorking) 4 else 0) or (if (isPinned) 2 else 0) or (if (isUnread) 1 else 0)

        val nonEmpty: Priority? get() = if (rank == 0) null else this

        override fun compareTo(other: Priority): Int = rank.compareTo(other.rank)
    }

    @Serializable
    data class Entry(
        val serverID: String,
        val sessionID: String,
        @Serializable(with = InstantSerializer::class) val updatedAt: Instant,
        val items: List<OpenCodeTranscriptItem>,
        @Serializable(with = InstantSerializer::class) val lastAccessedAt: Instant? = null,
        val priority: Priority? = null,
    ) {
        val recencyDate: Instant get() = lastAccessedAt ?: updatedAt
        val effectivePriority: Priority get() = priority ?: Priority()
    }

    fun upsertEntries(
        serverID: String,
        updates: List<Entry>,
        validUpdatedAt: Map<String, Instant>? = null,
        priorities: Map<String, Priority> = emptyMap(),
        now: Instant? = null,
    ): CachedTranscriptSnapshots {
        if (version != CURRENT_VERSION) return CachedTranscriptSnapshots().upsertEntries(serverID, updates, validUpdatedAt, priorities, now)
        val entries = entries.toMutableList()
        for (update in updates) {
            if (update.serverID != serverID) continue
            var entry = update.copy(priority = (priorities[update.sessionID] ?: update.priority)?.nonEmpty)
            val index = entries.indexOfFirst { it.serverID == serverID && it.sessionID == entry.sessionID }
            if (index >= 0) {
                entry = entry.copy(lastAccessedAt = entry.lastAccessedAt ?: entries[index].lastAccessedAt)
                entries[index] = entry
            } else {
                entries += entry
            }
        }
        if (validUpdatedAt != null) {
            entries.removeAll { entry ->
                entry.serverID == serverID && (validUpdatedAt[entry.sessionID]?.let { it < entry.updatedAt } ?: true)
            }
        }
        for (index in entries.indices) {
            if (entries[index].serverID != serverID) continue
            priorities[entries[index].sessionID]?.let { entries[index] = entries[index].copy(priority = it.nonEmpty) }
        }
        return copy(entries = entries).evict(now)
    }

    fun replaceEntries(serverID: String, replacements: List<Entry>, now: Instant? = null): CachedTranscriptSnapshots {
        if (version != CURRENT_VERSION) return CachedTranscriptSnapshots().replaceEntries(serverID, replacements, now)
        return copy(entries = entries.filter { it.serverID != serverID }).upsertEntries(serverID, replacements, now = now)
    }

    fun touch(serverID: String, sessionID: String, priority: Priority? = null, now: Instant): CachedTranscriptSnapshots {
        if (version != CURRENT_VERSION) return this
        val index = entries.indexOfFirst { it.serverID == serverID && it.sessionID == sessionID }
        if (index < 0) return this
        val entries = entries.toMutableList()
        var entry = entries[index].copy(lastAccessedAt = now)
        if (priority != null) entry = entry.copy(priority = priority.nonEmpty)
        entries[index] = entry
        return copy(entries = entries).evict(now)
    }

    fun evict(now: Instant? = null): CachedTranscriptSnapshots {
        var kept = entries
        cutoffDate(now)?.let { cutoff -> kept = kept.filter { !it.recencyDate.isBefore(cutoff) } }
        kept = kept.sortedWith(PRECEDENCE)
        if (kept.size > MAXIMUM_ENTRIES) kept = kept.take(MAXIMUM_ENTRIES)
        return copy(entries = kept)
    }

    /**
     * Encodes each entry once and splices the retained ones into the envelope's `entries`
     * array, so a multi-megabyte snapshot is never re-encoded while enforcing the on-disk cap.
     */
    fun encodedForPersistence(): ByteArray? {
        val empty = try { persistenceJson.encodeToString(serializer(), CachedTranscriptSnapshots(version, emptyList())) } catch (_: Exception) {
            return null
        }
        val marker = "\"entries\":[]"
        val markerIndex = empty.indexOf(marker)
        if (markerIndex < 0) return null
        val emptyBytes = empty.toByteArray()
        var bytes = emptyBytes.size
        val retained = mutableListOf<ByteArray>()
        for (entry in entries.take(MAXIMUM_ENTRIES)) {
            val separator = if (retained.isEmpty()) 0 else 1
            val encoded = try { encodedWithin(entry, MAXIMUM_BYTES - bytes - separator) } catch (_: Exception) { continue } ?: break
            retained += encoded
            bytes += encoded.size + separator
        }
        // Entries go just before the closing bracket of `"entries":[]`.
        val insertion = empty.substring(0, markerIndex + marker.length - 1).toByteArray().size
        val envelope = java.io.ByteArrayOutputStream(bytes)
        envelope.write(emptyBytes, 0, insertion)
        retained.forEachIndexed { index, entry ->
            if (index > 0) envelope.write(','.code)
            envelope.write(entry)
        }
        envelope.write(emptyBytes, insertion, emptyBytes.size - insertion)
        return envelope.toByteArray()
    }

    fun removeEntry(serverID: String, sessionID: String) =
        copy(entries = entries.filterNot { it.serverID == serverID && it.sessionID == sessionID })

    fun removeEntries(serverID: String) = copy(entries = entries.filterNot { it.serverID == serverID })

    fun validItems(serverID: String, sessions: List<OpenCodeSession>, now: Instant? = null): Map<String, List<OpenCodeTranscriptItem>> {
        if (version != CURRENT_VERSION) return emptyMap()
        val cutoff = cutoffDate(now)
        val updated = sessions.associate { it.id to it.updatedAt }
        val result = LinkedHashMap<String, List<OpenCodeTranscriptItem>>()
        for (entry in entries) {
            if (entry.serverID != serverID) continue
            val sessionUpdatedAt = updated[entry.sessionID] ?: continue
            if (sessionUpdatedAt < entry.updatedAt) continue
            if (cutoff != null && entry.recencyDate < cutoff) continue
            if (!result.containsKey(entry.sessionID)) result[entry.sessionID] = entry.items
        }
        return result
    }

    fun validItems(serverID: String, session: OpenCodeSession, now: Instant? = null): List<OpenCodeTranscriptItem>? =
        validItems(serverID, listOf(session), now)[session.id]

    companion object {
        const val CURRENT_VERSION = 1
        const val MAXIMUM_BYTES = 4_000_000
        const val MAXIMUM_ENTRIES = 16
        const val MAXIMUM_AGE_SECONDS = 14L * 24 * 60 * 60

        private fun cutoffDate(now: Instant?): Instant? {
            if (now == null || !now.isAfter(Instant.EPOCH)) return null
            return now.minusSeconds(MAXIMUM_AGE_SECONDS)
        }

        private val PRECEDENCE = Comparator<Entry> { lhs, rhs ->
            when {
                lhs.effectivePriority != rhs.effectivePriority -> rhs.effectivePriority.compareTo(lhs.effectivePriority)
                lhs.recencyDate != rhs.recencyDate -> rhs.recencyDate.compareTo(lhs.recencyDate)
                else -> rhs.updatedAt.compareTo(lhs.updatedAt)
            }
        }

        internal val persistenceJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** The entry's JSON, or null as soon as it passes [limit] bytes. */
        private fun encodedWithin(entry: Entry, limit: Int): ByteArray? {
            if (minimumEncodedSize(entry, limit) > limit) return null
            val stream = CappedOutputStream(limit)
            try {
                persistenceJson.encodeToStream(Entry.serializer(), entry, stream)
            } catch (_: CappedOutputStream.Full) {
                return null
            }
            return stream.toByteArray()
        }

        /** A lower bound on the entry's JSON bytes from its images and long texts, without encoding any of them. */
        private fun minimumEncodedSize(entry: Entry, limit: Int): Long {
            var size = 0L
            for (item in entry.items) {
                when (item) {
                    is OpenCodeTranscriptItem.UserText -> size += item.text.length
                    is OpenCodeTranscriptItem.AssistantMarkdown -> size += item.text.length
                    is OpenCodeTranscriptItem.UserImages -> for (image in item.images) size += base64Length(image.data.size)
                    is OpenCodeTranscriptItem.UserFiles -> for (file in item.files) size += file.text?.length ?: 0
                    is OpenCodeTranscriptItem.Tool -> {
                        for (output in item.call.output) size += output.length
                        for (image in item.call.images.orEmpty()) size += base64Length(image.data.size)
                    }
                    else -> {}
                }
                if (size > limit) return size
            }
            return size
        }

        private fun base64Length(bytes: Int): Long = (bytes.toLong() + 2) / 3 * 4
    }
}

private class CappedOutputStream(private val limit: Int) : ByteArrayOutputStream() {
    class Full : IOException()

    override fun write(b: Int) {
        if (count + 1 > limit) throw Full()
        super.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (count + len > limit) throw Full()
        super.write(b, off, len)
    }
}

/**
 * Where the transcript cache lives. Live: `filesDir/support/transcript-snapshots.json`, written at
 * most every 500 ms. Test: in memory.
 */
class TranscriptCachePersistence(
    val identity: String,
    val load: () -> CachedTranscriptSnapshots?,
    /** Reads and decodes the file off the main thread for the next [load] (app start). */
    val prefetch: () -> Unit = {},
    val schedule: (CachedTranscriptSnapshots) -> Unit,
    /** Writes whatever is pending now (backgrounding). */
    val flush: () -> Unit,
    val remove: () -> Unit,
) {
    /** File or memory bytes behind the writer. */
    class Storage private constructor(val identity: String, private val file: File?, private val memory: Memory?) {
        class Memory {
            @Volatile var value: ByteArray? = null
            @Volatile var writeAttempts = 0
        }

        fun data(): ByteArray? = memory?.value ?: file?.takeIf(File::exists)?.readBytes()

        /** Whether it was written. */
        fun set(data: ByteArray): Boolean {
            memory?.let {
                it.writeAttempts += 1
                it.value = data
                return true
            }
            val target = file ?: return false
            return try {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.tmp")
                temp.writeBytes(data)
                if (!temp.renameTo(target)) {
                    target.delete()
                    temp.renameTo(target)
                }
                true
            } catch (error: Exception) {
                AppLog.app.error { "Couldn’t write shared value (${data.size} bytes): ${error::class.java.simpleName}" }
                false
            }
        }

        fun remove() {
            memory?.let { it.value = null; return }
            file?.delete()
        }

        companion object {
            fun live(file: File) = Storage(file.path, file, null)
            fun inMemory(memory: Memory = Memory()) = Storage(UUID.randomUUID().toString(), null, memory)
        }
    }

    companion object Key : DependencyKey<TranscriptCachePersistence> {
        override val liveValue: TranscriptCachePersistence by lazy {
            val context = dependency(AppContext).applicationContext
            make(Storage.live(File(File(context.filesDir, "support"), "transcript-snapshots.json")))
        }

        override val testValue: TranscriptCachePersistence get() = make(Storage.inMemory())

        fun make(
            storage: Storage,
            interval: Duration = 500.milliseconds,
            prefetchOn: Executor = Dispatchers.IO.asExecutor(),
        ): TranscriptCachePersistence {
            val writer = TranscriptCacheWriter(storage, interval)
            val read = {
                runCatching {
                    storage.data()?.let { data ->
                        CachedTranscriptSnapshots.persistenceJson.decodeFromString(CachedTranscriptSnapshots.serializer(), data.decodeToString())
                    }
                }.getOrNull()
            }
            val prefetched = TranscriptCachePrefetch(read, prefetchOn)
            return TranscriptCachePersistence(
                identity = storage.identity,
                load = { prefetched.take() ?: read() },
                prefetch = prefetched::start,
                schedule = { snapshot ->
                    prefetched.discard()
                    writer.schedule(snapshot)
                },
                flush = writer::flush,
                remove = {
                    prefetched.discard()
                    storage.remove()
                },
            )
        }
    }
}

/**
 * One early read of the transcript cache, taken by the first load. A write or removal discards it,
 * and a read that found nothing isn't used, so a prefetch never hands back an older or emptier cache
 * than a direct read would.
 */
private class TranscriptCachePrefetch(private val read: () -> CachedTranscriptSnapshots?, private val executor: Executor) {
    private val lock = Any()
    private var reading: CompletableFuture<CachedTranscriptSnapshots?>? = null
    private var spent = false

    fun start() {
        synchronized(lock) {
            if (reading != null || spent) return
            reading = CompletableFuture.supplyAsync(read, executor)
        }
    }

    fun take(): CachedTranscriptSnapshots? {
        val early = synchronized(lock) {
            if (reading == null) spent = true
            reading
        } ?: return null
        val value = early.get()
        synchronized(lock) {
            val current = reading === early
            spent = true
            reading = null
            return if (current) value else null
        }
    }

    fun discard() {
        synchronized(lock) {
            spent = true
            reading = null
        }
    }
}

/** Writes the transcript cache at most every [interval]: later snapshots replace a pending one. */
private class TranscriptCacheWriter(private val storage: TranscriptCachePersistence.Storage, private val interval: Duration) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: CachedTranscriptSnapshots? = null
    private var generation = 0
    private var job: Job? = null

    fun schedule(snapshot: CachedTranscriptSnapshots) {
        val current = synchronized(lock) {
            pending = snapshot
            generation += 1
            job?.cancel()
            job = null
            generation
        }
        val task = scope.launch {
            delay(interval)
            writePending(current)
        }
        synchronized(lock) {
            if (generation == current) job = task else task.cancel()
        }
    }

    fun flush() {
        val snapshot = synchronized(lock) {
            job?.cancel()
            job = null
            pending.also { pending = null }
        }
        write(snapshot)
    }

    private fun writePending(expected: Int) {
        val snapshot = synchronized(lock) {
            if (generation != expected) return
            job = null
            pending.also { pending = null }
        }
        write(snapshot)
    }

    private fun write(snapshot: CachedTranscriptSnapshots?) {
        snapshot ?: return
        val data = snapshot.encodedForPersistence() ?: return storage.remove()
        if (storage.set(data)) return
        // Refused: keep it pending unless a newer one replaced it, so the next schedule or flush
        // writes it.
        synchronized(lock) { if (pending == null) pending = snapshot }
    }
}

/** The server-typed shared keys. Mutate only from reducers, only when the value changes. */
object ServerSharedKeys {
    /** The last successfully connected server, restored on launch. */
    val savedServer = SharedKey.keychain<OpenCodeServer?>("saved-server", null)

    /** Every server you've connected to, most recent first. */
    val savedServers = SharedKey.keychain<List<OpenCodeServer>>("saved-servers", emptyList())

    /** Agent Selection: the primary agents last listed, for Settings › Composer › Default Agent. */
    val knownPrimaryAgents = SharedKey.fileStorage<List<ai.opencode.server.model.OpenCodeAutocompleteAgent>>(
        FileDirectory.Documents,
        "primary-agents.json",
        emptyList(),
    )

    /** Agent Selection: the agent picked in each session. */
    val sessionAgents = SharedKey.fileStorage<Map<String, String>>(FileDirectory.Documents, "session-agents.json", emptyMap())

    /** Each saved server's last Home snapshot, keyed by its persistence ID. */
    val cachedHomeSnapshots = SharedKey.fileStorage<Map<String, CachedHomeSnapshot>>(FileDirectory.Caches, "home-snapshots.json", emptyMap())

    /** The single snapshot builds before [cachedHomeSnapshots] kept. */
    val legacyCachedHomeSnapshot = SharedKey.fileStorage<CachedHomeSnapshot?>(FileDirectory.Caches, "home-snapshot.json", null)

    /** In-memory backing of [cachedTranscriptSnapshots]; [TranscriptCachePersistence] owns the disk. */
    private val transcriptSnapshotsBacking = SharedKey.inMemory("cached-transcript-snapshots", CachedTranscriptSnapshots())

    private val installed = WeakHashMap<SharedStore, Boolean>()
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * The transcript cache: loaded once per store from [TranscriptCachePersistence], and every
     * change scheduled on its 500 ms writer.
     */
    fun cachedTranscriptSnapshots(): Shared<CachedTranscriptSnapshots> {
        val store = dependency(SharedStore)
        val reference = store.reference(transcriptSnapshotsBacking)
        synchronized(installed) {
            if (installed.put(store, true) == null) {
                val persistence = dependency(TranscriptCachePersistence)
                persistence.load()?.let(reference::set)
                persistenceScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    reference.flow.drop(1).collect(persistence.schedule)
                }
            }
        }
        return reference
    }
}

/**
 * Saves this as the last-used server at the top of the list and marks the install as paired. An
 * edited address gets a new ID: replace the old entry ([original]) after the new connection loads.
 * A saved entry keeps its address order.
 */
fun OpenCodeServer.remember(original: OpenCodeServer? = null) {
    val savedServer = shared(ServerSharedKeys.savedServer)
    val savedServers = shared(ServerSharedKeys.savedServers)
    val installHistory = shared(SharedKeys.installHistory)
    // What's on disk first: an edit's `original` came from Home, whose copy may be at the address
    // in use.
    var existing = savedServers.value.firstOrNull { it.persistenceID == persistenceID }
    if (existing == null) existing = savedServer.value?.takeIf { it.persistenceID == persistenceID }
    if (existing == null) existing = original?.takeIf { it.persistenceID == persistenceID }
    val entry = keepingAddressOrder(existing)
    if (installHistory.value != InstallHistory.paired) installHistory.set(InstallHistory.paired)
    if (savedServer.value != entry) savedServer.set(entry)
    if (savedServers.value.firstOrNull() == entry && original == null) return
    savedServers.update { servers ->
        listOf(entry) + servers.filterNot { entry.supersedes(it) || it.persistenceID == original?.persistenceID }
    }
}

/** Renames the saved entries for this server in place: a name never needs a reconnect or reorders the list. */
fun OpenCodeServer.rememberName() {
    val savedServer = shared(ServerSharedKeys.savedServer)
    val savedServers = shared(ServerSharedKeys.savedServers)
    val current = savedServer.value
    if (current?.persistenceID == persistenceID && current.name != name) savedServer.set(current.copy(name = name))
    if (savedServers.value.none { it.persistenceID == persistenceID && it.name != name }) return
    savedServers.update { servers -> servers.map { if (it.persistenceID == persistenceID) it.copy(name = name) else it } }
}
