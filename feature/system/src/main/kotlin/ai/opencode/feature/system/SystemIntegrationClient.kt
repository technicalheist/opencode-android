package ai.opencode.feature.system

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.intents.SystemQuickAction
import ai.opencode.feature.system.intents.SystemSearchIndex
import ai.opencode.feature.system.widgets.OpenCodeControls
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The app's side of the system integrations: where shortcuts, tiles, widgets and links arrive, and
 * where Home publishes the sessions they offer.
 */
class SystemIntegrationClient(
    /** Every action from a shortcut, tile, widget or link, starting with any that arrived before the app listened. */
    val actions: () -> Flow<SystemAction>,
    /** Hands an action to the app (what a shortcut, tile or widget does through `SystemActionActivity`). */
    val post: (SystemAction) -> Unit = {},
    /** Publishes a server's sessions: the directory, the widgets and tiles, and launcher quick actions. Coalesces bursts. */
    val publish: (server: SessionDirectory.Server, voiceAvailable: Boolean) -> Unit,
    /** Forgets a server that is no longer saved. */
    val removeServer: (serverKey: String) -> Unit,
) {
    companion object Key : DependencyKey<SystemIntegrationClient> {
        override val liveValue: SystemIntegrationClient by lazy {
            val context = dependency(AppContext)
            val publisher = SystemIntegrationPublisher { job -> SystemIntegrationEffects.perform(context, job) }
            SystemIntegrationClient(
                actions = { SystemActionInbox.shared.actions() },
                post = { SystemActionInbox.shared.post(it) },
                publish = { server, voiceAvailable -> publisher.schedule(server, voiceAvailable) },
                removeServer = { key -> publisher.remove(key) },
            )
        }

        override val testValue = SystemIntegrationClient(
            actions = { emptyFlow() },
            publish = { _, _ -> },
            removeServer = {},
        )
    }
}

/**
 * The inputs to [systemDirectoryEntry], for change detection. `HomeFeature` (in `:feature:drawer`)
 * builds one from its state after every action and publishes when it changes. The server and the
 * last-viewed table go in whole: deriving the server's ID costs more than comparing them while
 * they're unchanged.
 */
class SystemPublication(
    val server: OpenCodeServer,
    val loaded: Boolean,
    val sessions: List<OpenCodeSession>,
    val projects: List<OpenCodeProject>,
    /** Home's `projectsByRecency`: most recently used first. */
    val projectsByRecency: List<OpenCodeProject>,
    val pinnedSessionKeys: List<String>,
    val lastViewedSessionIDs: Map<String, String>,
    val voiceAvailable: Boolean,
) {
    val serverID: String get() = server.persistenceID
    val lastViewedSessionID: String? get() = lastViewedSessionIDs[serverID]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SystemPublication) return false
        val sameServer = server == other.server
        // A rename in place keeps the ID but changes the name the directory lists
        // (the name, else one of the ID's addresses).
        return (sameServer || (serverID == other.serverID && server.name == other.server.name && server.url == other.server.url)) &&
            loaded == other.loaded &&
            ((sameServer && lastViewedSessionIDs == other.lastViewedSessionIDs) || lastViewedSessionID == other.lastViewedSessionID) &&
            voiceAvailable == other.voiceAvailable &&
            pinnedSessionKeys == other.pinnedSessionKeys &&
            projects == other.projects &&
            projectsByRecency == other.projectsByRecency &&
            sessions.hasSameDirectoryMetadata(other.sessions)
    }

    override fun hashCode(): Int = serverID.hashCode()

    /**
     * This server's entry in the [SessionDirectory]: titles and project names, pins and the
     * last-viewed session; never message text.
     */
    val systemDirectoryEntry: SessionDirectory.Server
        get() {
            val projectsByID = projects.associateBy { it.id }
            fun entry(session: OpenCodeSession) = SessionDirectory.Session(
                id = session.id,
                title = session.title,
                projectID = session.projectID,
                projectName = if (session.projectID == GLOBAL_PROJECT_ID) null else projectsByID[session.projectID]?.name,
            )
            val prefix = server.persistenceID + "|"
            val pinnedIDs = pinnedSessionKeys.mapNotNull { key -> if (key.startsWith(prefix)) key.removePrefix(prefix) else null }
            val roots = sessions.filter { it.parentID == null && !it.isArchived }.sortedByDescending { it.updatedAt }
            val kept = roots.take(SessionDirectory.MAXIMUM_SESSIONS).toMutableList()
            val keptIDs = kept.map { it.id }.toSet()
            kept += roots.filter { it.id in pinnedIDs && it.id !in keptIDs }
            val listed = kept.map { it.id }.toSet()
            return SessionDirectory.Server(
                key = SessionDirectory.serverKey(server.persistenceID),
                name = server.friendlyName,
                lastViewedSessionID = lastViewedSessionIDs[server.persistenceID]?.takeIf { it in listed },
                pinnedIDs = pinnedIDs.filter { it in listed },
                sessions = kept.map(::entry),
                projects = projectsByRecency
                    .filter { it.id != GLOBAL_PROJECT_ID }
                    .take(SessionDirectory.MAXIMUM_PROJECTS)
                    .map { SessionDirectory.Project(it.id, it.name, it.directory) },
            )
        }

    companion object {
        /** Sessions outside any project. */
        const val GLOBAL_PROJECT_ID = "global"
    }
}

fun List<OpenCodeSession>.hasSameDirectoryMetadata(other: List<OpenCodeSession>): Boolean {
    if (size != other.size) return false
    if (this === other || this == other) return true
    return zip(other).all { (a, b) ->
        a.id == b.id && a.title == b.title && a.projectID == b.projectID &&
            a.directory == b.directory && a.updatedAt == b.updatedAt && a.parentID == b.parentID &&
            a.isArchived == b.isArchived
    }
}

/**
 * Serializes publishes and removals, debounced per server: each server's latest publish runs half a
 * second after its last change, and removing a server cancels its pending publish, so a deleted
 * server is never published again. A publish that comes out the same as the last job (a run's
 * activity that moved nothing) is dropped: it would change nothing the system shows.
 */
class SystemIntegrationPublisher(
    private val delay: Duration = 500.milliseconds,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Runs one job at a time, in order. */
    perform: suspend (SystemIntegrationPublisher.Job) -> Unit,
) {
    sealed interface Job {
        data class Publish(
            val server: SessionDirectory.Server,
            val voiceAvailable: Boolean,
            /** The quick actions' labels are in it: changing the app's language republishes. */
            val locale: Locale = Locale.getDefault(),
        ) : Job
        data class Remove(val key: String) : Job
    }

    private class Pending(val id: UUID, val task: kotlinx.coroutines.Job)

    private val lock = Any()
    private val pending = HashMap<String, Pending>()
    private var last: Job? = null
    private val jobs = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch { for (job in jobs) perform(job) }
    }

    fun schedule(server: SessionDirectory.Server, voiceAvailable: Boolean) {
        val id = UUID.randomUUID()
        val key = server.key
        synchronized(lock) {
            pending[key]?.task?.cancel()
            val task = scope.launch {
                delay(delay)
                enqueue(Job.Publish(server, voiceAvailable), key, id)
            }
            pending[key] = Pending(id, task)
        }
    }

    fun remove(key: String) = synchronized(lock) {
        pending.remove(key)?.task?.cancel()
        last = Job.Remove(key)
        jobs.trySend(Job.Remove(key))
    }

    /** Under the same lock as [remove], so a removal either cancels this publish or is queued after it. */
    private fun enqueue(job: Job, key: String, id: UUID) = synchronized(lock) {
        if (pending[key]?.id != id) return@synchronized
        pending.remove(key)
        if (job == last) return@synchronized
        last = job
        jobs.trySend(job)
    }
}

/**
 * Writes the directory and the controls' copy, then brings system search, the widgets, tiles and
 * quick actions in line with it.
 */
internal object SystemIntegrationEffects {
    suspend fun perform(context: Context, job: SystemIntegrationPublisher.Job) {
        val file = SessionDirectory.file(context.filesDir)
        when (job) {
            is SystemIntegrationPublisher.Job.Publish -> {
                var directory = SessionDirectory.loadOrEmpty(file)
                val previous = directory.servers[job.server.key]
                if (previous != job.server || directory.currentServerKey != job.server.key) {
                    directory = directory.publish(job.server)
                    directory.write(file)
                    AppLog.app.debug { "System directory published sessions=${job.server.sessions.size}" }
                    SystemSearchIndex.index(context, job.server, previous)
                }
                updateControls(context, directory)
                setQuickActions(context, SystemQuickAction.items(job.server, job.voiceAvailable))
            }
            is SystemIntegrationPublisher.Job.Remove -> {
                var directory = SessionDirectory.load(file) ?: return
                val wasCurrent = directory.currentServerKey == job.key
                directory = directory.removeServer(job.key)
                directory.write(file)
                updateControls(context, directory)
                SystemSearchIndex.remove(context, job.key)
                if (wasCurrent) setQuickActions(context, emptyList())
            }
        }
    }

    /**
     * Brings the controls' copy in line (written on first publish too, so an upgrade fills it)
     * and redraws the widgets and tiles that name a session.
     */
    suspend fun updateControls(
        context: Context,
        directory: SessionDirectory,
        store: ControlDirectoryStore = ControlDirectoryStore.file(context.filesDir),
    ) {
        val controls = directory.forControls
        if (store.load() == controls) return
        store.save(controls)
        OpenCodeControls.reloadSessionControls(context)
    }

    fun setQuickActions(context: Context, items: List<SystemQuickAction>) {
        runCatching { SystemQuickAction.apply(context, items) }
            .onFailure { AppLog.app.error { "Couldn’t update quick actions: ${it::class.java.simpleName}" } }
    }
}
