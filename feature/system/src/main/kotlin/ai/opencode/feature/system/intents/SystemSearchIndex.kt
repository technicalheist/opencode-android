package ai.opencode.feature.system.intents

import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.feature.system.R
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import android.app.appsearch.AppSearchBatchResult
import android.app.appsearch.AppSearchManager
import android.app.appsearch.AppSearchResult
import android.app.appsearch.AppSearchSchema
import android.app.appsearch.AppSearchSession
import android.app.appsearch.BatchResultCallback
import android.app.appsearch.GenericDocument
import android.app.appsearch.PutDocumentsRequest
import android.app.appsearch.RemoveByDocumentIdRequest
import android.app.appsearch.SearchSpec
import android.app.appsearch.SetSchemaRequest
import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Every published session, by title and project name only, in the platform's on-device search index
 * (AppSearch platform storage).
 *
 * Documents use the shape of AndroidX's `builtin:Thing` (`name`, `alternateNames`,
 * `description`, `url`, `potentialActions`) and are displayed by the system, so system search
 * surfaces that read AppSearch (Pixel Launcher's on-device search, where the device offers it)
 * can find them; a result's `url` is the session's `opencode://session/<id>?server=<key>` link,
 * which the app routes like any link. Launchers without AppSearch search the quick actions.
 *
 * An item's ID is its session's scoped ID (`<serverKey>:<sessionID>`), its namespace (Spotlight's
 * domain) the server key; only what changed is indexed, sessions that left the list are removed,
 * and a forgotten server's whole namespace goes. Failures are logged and otherwise ignored.
 */
internal object SystemSearchIndex {
    private const val DATABASE = "opencode-sessions"
    private const val THING = "builtin:Thing"
    private const val POTENTIAL_ACTION = "builtin:PotentialAction"

    private val executor: Executor = Executors.newSingleThreadExecutor { Thread(it, "SystemSearchIndex") }
    private val lock = Mutex()
    private var session: AppSearchSession? = null

    /** Indexes what changed and removes what left the list (a deletion, or a session that aged out of it). */
    suspend fun index(context: Context, server: SessionDirectory.Server, previous: SessionDirectory.Server?) = lock.withLock {
        val current = server.sessions.mapTo(HashSet()) { it.id }
        val removed = previous?.sessions.orEmpty().map { it.id }.filter { it !in current }
        val unchanged = previous?.sessions.orEmpty().toSet()
        val changed = server.sessions.filter { it !in unchanged }
        if (removed.isEmpty() && changed.isEmpty()) return@withLock
        val session = session(context) ?: return@withLock
        if (removed.isNotEmpty()) {
            val request = RemoveByDocumentIdRequest.Builder(server.key)
                .addIds(removed.map { identifier(server.key, it) })
                .build()
            batch("remove") { callback -> session.remove(request, executor, callback) }
        }
        if (changed.isEmpty()) return@withLock
        val request = PutDocumentsRequest.Builder()
            .addGenericDocuments(changed.map { document(it, server.key) })
            .build()
        batch("index") { callback -> session.put(request, executor, callback) }
        AppLog.app.debug { "System search indexed changed=${changed.size} removed=${removed.size}" }
    }

    /** Removes a forgotten server's sessions. */
    suspend fun remove(context: Context, serverKey: String) = lock.withLock {
        val session = session(context) ?: return@withLock
        val spec = SearchSpec.Builder().addFilterNamespaces(serverKey).build()
        val result = suspendCancellableCoroutine { continuation ->
            session.remove("", spec, executor) { result: AppSearchResult<Void> -> continuation.resume(result) }
        }
        if (!result.isSuccess) AppLog.app.debug { "System search removal failed code=${result.resultCode}" }
    }

    /** The session's scoped ID. */
    fun identifier(serverKey: String, sessionID: String): String = "$serverKey:$sessionID"

    /** The link a search result opens (`SystemAction.fromUrl`'s `opencode://session/<id>?server=<key>`). */
    fun url(serverKey: String, sessionID: String): String =
        "${SystemAction.URL_SCHEME}://session/${encode(sessionID)}?server=${encode(serverKey)}"

    /** Titles and project names only. */
    internal fun document(session: SessionDirectory.Session, serverKey: String): GenericDocument {
        val url = url(serverKey, session.id)
        val open = GenericDocument.Builder<GenericDocument.Builder<*>>(serverKey, "${identifier(serverKey, session.id)}#open", POTENTIAL_ACTION)
            .setPropertyString("name", localized(R.string.system_open_session))
            .setPropertyString("uri", url)
            .build()
        return GenericDocument.Builder<GenericDocument.Builder<*>>(serverKey, identifier(serverKey, session.id), THING)
            .setPropertyString("name", session.title)
            .apply { session.projectName?.let { setPropertyString("description", it) } }
            .setPropertyString("url", url)
            .setPropertyDocument("potentialActions", open)
            .build()
    }

    private suspend fun session(context: Context): AppSearchSession? {
        session?.let { return it }
        val manager = context.getSystemService(AppSearchManager::class.java) ?: return null
        val created = suspendCancellableCoroutine { continuation ->
            manager.createSearchSession(AppSearchManager.SearchContext.Builder(DATABASE).build(), executor) { result ->
                continuation.resume(result)
            }
        }
        val opened = created.resultValue.takeIf { created.isSuccess } ?: run {
            AppLog.app.debug { "System search unavailable code=${created.resultCode}" }
            return null
        }
        val schema = suspendCancellableCoroutine { continuation ->
            opened.setSchema(schemaRequest, executor, executor) { result -> continuation.resume(result) }
        }
        if (!schema.isSuccess) {
            AppLog.app.debug { "System search schema rejected code=${schema.resultCode}" }
            opened.close()
            return null
        }
        session = opened
        return opened
    }

    private val schemaRequest: SetSchemaRequest by lazy {
        fun string(name: String, indexed: Boolean, repeated: Boolean = false) =
            AppSearchSchema.StringPropertyConfig.Builder(name)
                .setCardinality(if (repeated) AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED else AppSearchSchema.PropertyConfig.CARDINALITY_OPTIONAL)
                .setIndexingType(
                    if (indexed) AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_PREFIXES
                    else AppSearchSchema.StringPropertyConfig.INDEXING_TYPE_NONE,
                )
                .setTokenizerType(
                    if (indexed) AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_PLAIN
                    else AppSearchSchema.StringPropertyConfig.TOKENIZER_TYPE_NONE,
                )
                .build()
        val action = AppSearchSchema.Builder(POTENTIAL_ACTION)
            .addProperty(string("name", indexed = false))
            .addProperty(string("description", indexed = false))
            .addProperty(string("uri", indexed = false))
            .build()
        val thing = AppSearchSchema.Builder(THING)
            .addProperty(string("name", indexed = true))
            .addProperty(string("alternateNames", indexed = true, repeated = true))
            .addProperty(string("description", indexed = true))
            .addProperty(string("image", indexed = false))
            .addProperty(string("url", indexed = false))
            .addProperty(
                AppSearchSchema.DocumentPropertyConfig.Builder("potentialActions", POTENTIAL_ACTION)
                    .setCardinality(AppSearchSchema.PropertyConfig.CARDINALITY_REPEATED)
                    .setShouldIndexNestedProperties(false)
                    .build(),
            )
            .build()
        // Displayed by the system is the default; incompatible changes from an earlier build
        // drop the old documents, which the next publish indexes again.
        SetSchemaRequest.Builder().addSchemas(thing, action).setForceOverride(true).build()
    }

    private suspend fun batch(what: String, call: (BatchResultCallback<String, Void>) -> Unit) {
        val failures = suspendCancellableCoroutine { continuation ->
            call(object : BatchResultCallback<String, Void> {
                override fun onResult(result: AppSearchBatchResult<String, Void>) {
                    continuation.resume(result.failures.size)
                }

                override fun onSystemError(throwable: Throwable?) {
                    continuation.resume(-1)
                }
            })
        }
        if (failures != 0) AppLog.app.debug { "System search $what failed count=$failures" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
