package ai.opencode.feature.system

import ai.opencode.feature.system.share.ShareConversation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What app shortcuts, tiles, widgets and launcher quick actions know about your sessions, so they
 * can offer and resolve them without the network. Titles and project names only: never message text
 * or credentials.
 *
 * The controls' copy ([forControls], [ControlDirectoryStore]) is kept so a change to what they show
 * (and only that) redraws them.
 */
@Serializable
data class SessionDirectory(
    val version: Int = CURRENT_VERSION,
    /** The server the app last showed; choices without a server mean this one. */
    val currentServerKey: String? = null,
    val servers: Map<String, Server> = emptyMap(),
) {
    /** No times: sessions are listed newest first, so a run's activity changes nothing here unless it moves one up. */
    @Serializable
    data class Session(
        val id: String,
        val title: String,
        val projectID: String,
        /** Null for sessions outside any project. */
        val projectName: String? = null,
    )

    @Serializable
    data class Project(
        val id: String,
        val name: String,
        /** Where a session in it starts; Ask OpenCode creates one in the background. */
        val directory: String,
    )

    /** One saved server's sessions. */
    @Serializable
    data class Server(
        /** `SessionDirectory.serverKey(...)` of the server's persistence ID. */
        val key: String,
        val name: String,
        val lastViewedSessionID: String? = null,
        /** In drawer order. */
        val pinnedIDs: List<String>,
        /** Newest first, pinned ones included. */
        val sessions: List<Session>,
        /** Most recently used first. */
        val projects: List<Project>,
    ) {
        fun session(id: String): Session? = sessions.firstOrNull { it.id == id }
        fun project(id: String): Project? = projects.firstOrNull { it.id == id }

        val pinned: List<Session> get() = pinnedIDs.mapNotNull(::session)
        val recents: List<Session> get() = sessions.filter { it.id !in pinnedIDs }

        /** The session you'd see on opening the app: the last one viewed, else the newest. */
        val lastViewed: Session? get() = lastViewedSessionID?.let(::session) ?: sessions.firstOrNull()
    }

    val current: Server? get() = currentServerKey?.let { servers[it] }

    /** Records a server's sessions and makes it the current one. Returns the directory and the entry it replaced. */
    fun publish(server: Server): SessionDirectory =
        copy(currentServerKey = server.key, servers = servers + (server.key to server))

    fun removeServer(key: String): SessionDirectory =
        copy(currentServerKey = if (currentServerKey == key) null else currentServerKey, servers = servers - key)

    /**
     * What the controls get: every server's pinned sessions, its newest recents and the last
     * viewed one, with project names but not their folders or the server's name.
     */
    val forControls: SessionDirectory
        get() = copy(
            servers = servers.mapValues { (_, server) ->
                val kept = server.pinnedIDs.toSet() +
                    server.recents.take(MAXIMUM_CONTROL_SESSIONS).map { it.id } +
                    listOfNotNull(server.lastViewedSessionID)
                server.copy(
                    // Controls never show it, and an unnamed server's name can be its address.
                    name = "",
                    sessions = server.sessions.filter { it.id in kept },
                    projects = server.projects.map { Project(it.id, it.name, "") },
                )
            },
        )

    fun write(file: File) {
        val data = runCatching { json.encodeToString(serializer(), this) }.getOrNull() ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, ".${file.name}.tmp")
            temporary.writeText(data)
            java.nio.file.Files.move(
                temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    companion object {
        const val CURRENT_VERSION = 1
        /** Recent sessions kept per server, besides pinned ones. */
        const val MAXIMUM_SESSIONS = 100
        const val MAXIMUM_PROJECTS = 30
        /** Recent sessions a control can offer per server, besides pinned ones. */
        const val MAXIMUM_CONTROL_SESSIONS = 50

        const val FILE_NAME = "system-sessions.json"

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * A short, stable, non-reversible name for a saved server, so session IDs handed to the
         * system never carry its address or username.
         */
        fun serverKey(persistenceID: String): String = ShareConversation.serverKey(persistenceID)

        fun file(filesDir: File): File = File(filesDir, FILE_NAME)

        fun load(file: File): SessionDirectory? {
            val text = runCatching { file.readText() }.getOrNull() ?: return null
            val directory = runCatching { json.decodeFromString(serializer(), text) }.getOrNull() ?: return null
            return directory.takeIf { it.version == CURRENT_VERSION }
        }

        fun loadOrEmpty(file: File): SessionDirectory = load(file) ?: SessionDirectory()
    }
}

/**
 * The controls' copy of the directory, an app-private file used to tell when the controls need
 * redrawing.
 */
class ControlDirectoryStore(
    val load: () -> SessionDirectory?,
    val save: (SessionDirectory) -> Unit,
) {
    companion object {
        const val FILE_NAME = "system-controls.json"

        fun file(filesDir: File): ControlDirectoryStore {
            val file = File(filesDir, FILE_NAME)
            return ControlDirectoryStore(load = { SessionDirectory.load(file) }, save = { it.write(file) })
        }

        fun inMemory(): ControlDirectoryStore {
            var stored: SessionDirectory? = null
            return ControlDirectoryStore(load = { stored }, save = { stored = it })
        }
    }
}

/** A server-scoped session or project reference, as the system stores it: `<serverKey>:<id>`. */
data class ScopedID(val serverKey: String, val id: String) {
    val rawValue: String get() = "$serverKey:$id"

    companion object {
        fun parse(raw: String): ScopedID? {
            val colon = raw.indexOf(':')
            if (colon < 0) return null
            val key = raw.substring(0, colon)
            val id = raw.substring(colon + 1)
            if (key.isEmpty() || id.isEmpty()) return null
            return ScopedID(key, id)
        }
    }
}
