package ai.opencode.feature.system.notifications

import ai.opencode.feature.system.NotificationDestination
import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.InstantSerializer
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.shared
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class NotificationPermission { NotDetermined, Disabled, Enabled }

class NotificationPermissionClient(
    val status: suspend () -> NotificationPermission,
    val request: suspend () -> NotificationPermission,
    val openSettings: suspend () -> Boolean,
) {
    companion object Key : DependencyKey<NotificationPermissionClient> {
        override val liveValue: NotificationPermissionClient by lazy {
            NotificationPermissionClient(
                status = { SessionNotifications.shared.permissionStatus() },
                request = { SessionNotifications.shared.requestPermission() },
                openSettings = {
                    val context = dependency(AppContext)
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.isSuccess
                },
            )
        }
        override val testValue = NotificationPermissionClient(
            status = { NotificationPermission.Enabled },
            request = { NotificationPermission.Enabled },
            openSettings = { true },
        )
    }
}

/**
 * This phone's notification keys, off the main thread. The live value is
 * [NotificationKeyStore.live]; the test value has no keys.
 */
class NotificationKeysClient(
    /** This phone's public key for `ref`, made the first time. */
    val prepare: suspend (ref: String) -> String,
    val establish: suspend (ref: String, serverPublicKey: String) -> Unit,
    val remove: suspend (ref: String) -> Unit,
    /** The authenticated payload, or null when the box or key doesn't hold. */
    val verify: suspend (NotificationContent.Envelope) -> NotificationContent.Verified?,
) {
    companion object Key : DependencyKey<NotificationKeysClient> {
        override val liveValue: NotificationKeysClient by lazy {
            NotificationKeysClient(
                prepare = { ref -> withContext(Dispatchers.IO) { NotificationKeyStore.live.prepare(ref) } },
                establish = { ref, serverKey -> withContext(Dispatchers.IO) { NotificationKeyStore.live.establish(ref, serverKey) } },
                remove = { ref -> withContext(Dispatchers.IO) { NotificationKeyStore.live.remove(ref) } },
                verify = { envelope -> withContext(Dispatchers.IO) { NotificationContent.verify(envelope) } },
            )
        }
        override val testValue = NotificationKeysClient(
            prepare = { throw NotificationKeyStore.Failure.MissingKey },
            establish = { _, _ -> throw NotificationKeyStore.Failure.MissingKey },
            remove = {},
            verify = { null },
        )
    }
}

/**
 * The callback URL a send attaches so the server's notify plugin can tell this phone when the run
 * ends. Null means "send without one": notifications only ever enhance a send and never delay or
 * fail it.
 *
 * Senders bind it with `PromptContext.notificationCallbackURL.withValue(url) { … }`, which
 * `:server` reads when building the prompt body.
 */
class SessionNotificationsClient(
    /** Whether this build has a relay and a push channel to offer at all. */
    val isAvailable: Boolean,
    val callbackURL: suspend (OpenCodeServer) -> String?,
    /** Mints ahead of the first send so Send rarely waits on the relay. */
    val prepare: suspend (OpenCodeServer) -> Unit,
    /** Drops a removed server's ref and callback (by persistence ID). */
    val forget: suspend (String) -> Unit,
    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    val ref: (String) -> String = { "" },
    /** Android grants the badge count with notifications. */
    val includeBadges: suspend () -> Unit = {},
    /** Tells notifications which session is on screen so its own don't show. */
    val setVisibleSession: (shown: Boolean, sessionID: String?) -> Unit = { _, _ -> },
) {
    companion object Key : DependencyKey<SessionNotificationsClient> {
        override val liveValue: SessionNotificationsClient by lazy {
            SessionNotificationsClient(
                isAvailable = SessionNotifications.isAvailable,
                callbackURL = { SessionNotifications.shared.callbackURL(it) },
                prepare = { SessionNotifications.shared.callbackURL(it) },
                forget = { SessionNotifications.shared.forget(it) },
                ref = { SessionNotifications.shared.ref(it) },
                includeBadges = {},
                setVisibleSession = { shown, sessionID ->
                    val id = if (shown) sessionID else null
                    if (shown || SessionNotifications.shared.visibleSessionID == sessionID) {
                        SessionNotifications.shared.visibleSessionID = id
                    }
                },
            )
        }
        override val testValue = SessionNotificationsClient(
            isAvailable = true,
            callbackURL = { null },
            prepare = {},
            forget = {},
        )
    }
}

/** How a session notification arriving in the foreground shows. The session on screen shows nothing. */
enum class NotificationPresentation { None, Banner }

/**
 * Push registration and relay callback URLs.
 *
 * Privacy: the relay is stateless. It seals the push token, the build's push environment and
 * a random per-server `ref` into the callback URL itself, so the OpenCode server never sees
 * the token and the relay keeps nothing. The `ref` exists only so a tap can find the saved
 * server; it is random, never derived from the server's address or credentials.
 *
 * Previews are encrypted: for each ref this phone makes a P-256 key and swaps
 * public keys with the saved server over HTTPS ([OpenCodeClient.pairNotificationPreview]); the
 * server seals each push's text with the derived key, so the relay and FCM only carry a
 * `v2` envelope. Only `/v2/notify/` callbacks on the relay are minted or reused
 * ([isEncryptedCallback]); a missing plugin, unavailable key store or failed exchange can
 * yield a generic "New activity" notification, never a clear-text one.
 *
 * Android pushes ride FCM: the build's push environment is [FCM_ENVIRONMENT], and the token is the
 * FCM registration token. The relay (`packages/notify-relay`) seals APNs tokens only today, so it
 * answers this registration with 400 and every send goes without a callback, failing open as any
 * relay outage does; docs/parity/system.md lists the relay change FCM needs. Until a build carries
 * a Firebase configuration there is no environment at all ([isAvailable] false) and nothing is
 * asked of the relay. The whole path (permission, token wait, mint, saved callbacks, tap routing,
 * the push handler) is in place behind [PushTokenProvider].
 */
class SessionNotifications internal constructor(private val tokens: PushTokenProvider) {
    /** The session on screen while the app is in the foreground. Its own "finished" notifications aren't shown. */
    @Volatile var visibleSessionID: String? = null

    fun presentation(sessionID: String?): NotificationPresentation {
        sessionID ?: return NotificationPresentation.Banner
        if (sessionID == visibleSessionID) return NotificationPresentation.None
        return NotificationPresentation.Banner
    }

    /** A tapped notification's destination, delivered once something routes it. */
    var onOpen: ((NotificationDestination) -> Unit)? = null
        set(value) {
            field = value
            val pending = pendingDestination
            if (value != null && pending != null) {
                pendingDestination = null
                value(pending)
            }
        }

    private var pendingDestination: NotificationDestination? = null
    @Volatile private var confirmedToken: String? = null
    private val lock = Any()
    private val preparations = HashMap<String, Deferred<String?>>()
    /** Server key → the callback last paired and when to try again (a day after a pairing, a minute after a failure). */
    private val pairChecks = HashMap<String, Pair<String, Instant>>()
    private val keys get() = dependency(NotificationKeysClient)
    private val tokenWaiters = mutableListOf<CompletableDeferred<String?>>()
    private val saved get() = shared(savedKey)
    private var permissionRequest: CompletableDeferred<Boolean>? = null
    private val mintScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Serializable
    data class Saved(
        /** Server persistence ID → its random ref. */
        val refs: Map<String, String> = emptyMap(),
        /** Server persistence ID → its current callback. */
        val callbacks: Map<String, Callback> = emptyMap(),
    )

    @Serializable
    data class Callback(
        val url: String,
        @Serializable(with = InstantSerializer::class) val expiresAt: Instant,
        val token: String,
        val environment: String,
        /** The device public key this callback was paired with. */
        val phonePublicKey: String? = null,
    ) {
        fun isReusable(token: String, environment: String, publicKey: String?, relay: HttpUrl?, now: Instant): Boolean =
            this.token == token && this.environment == environment &&
                expiresAt.toEpochMilli() - now.toEpochMilli() > 3.days.inWholeMilliseconds &&
                (publicKey == null || phonePublicKey == null || phonePublicKey == publicKey) &&
                isEncryptedCallback(url, relay)
    }

    data class Minted(val url: String, val expiresAt: Instant)

    fun permissionStatus(): NotificationPermission {
        val context = dependency(AppContext)
        if (NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return NotificationPermission.Enabled
        val asked = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ASKED, false)
        return if (asked) NotificationPermission.Disabled else NotificationPermission.NotDetermined
    }

    /** Asks once (Android's dialog, from a see-through activity), then registers for pushes when allowed. */
    suspend fun requestPermission(): NotificationPermission {
        if (permissionStatus() == NotificationPermission.NotDetermined) {
            val request = synchronized(lock) {
                permissionRequest ?: CompletableDeferred<Boolean>().also { created ->
                    permissionRequest = created
                    val context = dependency(AppContext)
                    context.startActivity(
                        Intent(context, NotificationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            request.await()
        }
        val status = permissionStatus()
        if (status == NotificationPermission.Enabled) registerForRemoteNotifications()
        return status
    }

    internal fun permissionAnswered(granted: Boolean) {
        val context = dependency(AppContext)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()
        val request = synchronized(lock) { permissionRequest.also { permissionRequest = null } }
        request?.complete(granted)
    }

    /** The random `ref` a server's pushes carry, made and saved the first time it's asked for. */
    fun ref(key: String): String {
        var made: String? = null
        saved.update { current ->
            val existing = current.refs[key]
            if (existing != null) {
                made = existing
                current
            } else {
                val ref = UUID.randomUUID().toString().lowercase()
                made = ref
                current.copy(refs = current.refs + (key to ref))
            }
        }
        return made ?: saved.value.refs[key].orEmpty()
    }

    /**
     * A callback URL for [server], minting one when the saved one is missing, stale, for an
     * older token or another device key, then pairing this phone's notification key with the
     * server. Null whenever notifications are unavailable.
     */
    suspend fun callbackURL(server: OpenCodeServer): String? {
        val environment = environment
        if (!isAvailable || environment == null) return null
        if (permissionStatus() != NotificationPermission.Enabled || !currentCoroutineContext().isActive) return null
        registerForRemoteNotifications()
        val token = waitForToken() ?: return null
        if (!currentCoroutineContext().isActive) return null
        val key = server.persistenceID
        synchronized(lock) { preparations[key] }?.let { return awaitPreparation(it) }
        val ref = ref(key)
        val task = synchronized(lock) {
            preparations[key] ?: mintScope.async {
                val publicKey = runCatching { keys.prepare(ref) }.getOrNull()
                // Forgotten while the key was made: don't leave it behind.
                if (saved.value.refs[key] != ref) {
                    runCatching { keys.remove(ref) }
                    return@async null
                }
                prepareCallback(server, key, ref, publicKey, token, environment)
            }.also { preparations[key] = it }
        }
        val url = awaitPreparation(task)
        if (saved.value.refs[key] == ref) synchronized(lock) { if (preparations[key] === task) preparations.remove(key) }
        return url
    }

    /** A preparation forgotten meanwhile is cancelled; the send goes on without a callback. */
    private suspend fun awaitPreparation(task: Deferred<String?>): String? = try {
        task.await()
    } catch (cancelled: CancellationException) {
        if (!currentCoroutineContext().isActive) throw cancelled
        null
    }

    private suspend fun prepareCallback(
        server: OpenCodeServer, key: String, ref: String, publicKey: String?, token: String, environment: String,
    ): String? {
        suspend fun current() = currentCoroutineContext().isActive && confirmedToken == token && saved.value.refs[key] == ref
        val cached = saved.value.callbacks[key]
        var callback = if (cached != null && cached.isReusable(token, environment, publicKey, RELAY_URL.toHttpUrlOrNull(), Instant.now())) {
            cached
        } else {
            val minted = mint(token, environment, ref) ?: return null
            if (!current()) return null
            Callback(minted.url, minted.expiresAt, token, environment, phonePublicKey = publicKey ?: cached?.phonePublicKey)
        }
        if (!current()) return null
        if (publicKey != null) callback = callback.copy(phonePublicKey = publicKey)
        if (saved.value.callbacks[key] != callback) saved.update { it.copy(callbacks = it.callbacks + (key to callback)) }
        synchronized(lock) { pairChecks[key] }?.let { (url, retryAt) ->
            if (url == callback.url && retryAt.isAfter(Instant.now())) return callback.url
        }

        // Only public keys cross the direct server connection. A missing plugin, locked key
        // store or failed handshake can yield a generic v2 notification, never a v1 callback.
        var paired = false
        if (publicKey != null) {
            val serverKey = runCatching { OpenCodeClient.pairNotificationPreview(server, callback.url, ref, publicKey) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (serverKey != null) {
                if (!current()) return null
                paired = runCatching { keys.establish(ref, serverKey) }.onFailure { if (it is CancellationException) throw it }.isSuccess
            }
        }
        if (!current()) return null
        val retry = if (paired) 86_400.seconds else 60.seconds
        synchronized(lock) { pairChecks[key] = callback.url to Instant.now().plusMillis(retry.inWholeMilliseconds) }
        return callback.url
    }

    /** Forgets a removed server's ref, callback and notification key. */
    suspend fun forget(serverKey: String) {
        synchronized(lock) {
            preparations.remove(serverKey)?.cancel()
            pairChecks.remove(serverKey)
        }
        val ref = saved.value.refs[serverKey]
        saved.update { it.copy(refs = it.refs - serverKey, callbacks = it.callbacks - serverKey) }
        if (ref != null) runCatching { keys.remove(ref) }
    }

    /** The authenticated payload of a push envelope. */
    suspend fun verify(envelope: NotificationContent.Envelope): NotificationContent.Verified? = keys.verify(envelope)

    fun didRegister(token: String) {
        if (token.isEmpty()) return
        confirmedToken = token
        finishTokenWaiters(token)
    }

    fun registrationFailed() {
        confirmedToken = null
        finishTokenWaiters(null)
        AppLog.app.debug("Remote notification registration unavailable; sending without a callback.")
    }

    /** The saved server a tapped notification's `ref` was minted for, with its session; null when unknown or invalid. */
    fun destination(ref: String, sessionID: String): NotificationDestination? {
        val serverKey = saved.value.refs.entries.firstOrNull { it.value == ref }?.key ?: return null
        return NotificationDestination(serverKey, sessionID).takeIf { it.isValid }
    }

    /** A tapped notification: its envelope is authenticated again, then routed. */
    suspend fun open(envelope: NotificationContent.Envelope) {
        val verified = verify(envelope) ?: return
        open(verified.ref, verified.payload.sessionID)
    }

    /** Routes a tapped notification's `ref` to the server it was minted for. */
    fun open(ref: String, sessionID: String) {
        val destination = destination(ref, sessionID) ?: return
        val onOpen = onOpen
        if (onOpen != null) onOpen(destination) else pendingDestination = destination
    }

    private fun registerForRemoteNotifications() {
        tokens.register(::didRegister, ::registrationFailed)
    }

    private suspend fun waitForToken(): String? {
        confirmedToken?.let { return it }
        val waiter = CompletableDeferred<String?>()
        synchronized(lock) { tokenWaiters += waiter }
        return try {
            withTimeoutOrNull(1500.milliseconds) { waiter.await() }
        } finally {
            synchronized(lock) { tokenWaiters.remove(waiter) }
        }
    }

    private fun finishTokenWaiters(token: String?) {
        val waiters = synchronized(lock) { tokenWaiters.toList().also { tokenWaiters.clear() } }
        for (waiter in waiters) waiter.complete(token)
    }

    companion object {
        /** The relay's origin (`NotifyRelayURL`). */
        const val RELAY_URL = "https://opencode-notify-relay.anomalyco.workers.dev"

        /** The push environment an Android registration asks the relay to seal. */
        const val FCM_ENVIRONMENT = "fcm"

        /**
         * The push environment the relay seals: [FCM_ENVIRONMENT] when the build carries a Firebase
         * configuration (FCM can mint tokens), else null and the build offers no callback.
         */
        val environment: String? by lazy {
            FCM_ENVIRONMENT.takeIf { FcmPushTokenProvider.isConfigured(dependency(AppContext).applicationContext) }
        }

        /** Whether this build can offer notifications at all. */
        val isAvailable: Boolean get() = environment != null && RELAY_URL.toHttpUrlOrNull()?.isHttps == true

        private const val PREFERENCES = "session-notifications"
        private const val ASKED = "permission-asked"

        private val savedKey = SharedKey.keychain("session-notification-callbacks", Saved())

        val shared: SessionNotifications by lazy { SessionNotifications(FcmPushTokenProvider(dependency(AppContext).applicationContext)) }

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Only a `/v2/notify/<token>` URL on the relay's own HTTPS origin, with no user, password,
         * query or fragment, is ever used as a callback. Old (`/v1/`) or foreign capabilities never
         * reach a prompt.
         */
        fun isEncryptedCallback(url: String, relay: HttpUrl? = RELAY_URL.toHttpUrlOrNull()): Boolean {
            relay ?: return false
            val parts = runCatching { java.net.URI(url) }.getOrNull() ?: return false
            if (parts.scheme != "https" || parts.host?.lowercase() != relay.host.lowercase()) return false
            if ((parts.port.takeIf { it != -1 } ?: 443) != relay.port) return false
            if (parts.rawUserInfo != null || parts.rawQuery != null || parts.rawFragment != null) return false
            val prefix = "/v2/notify/"
            val path = parts.rawPath ?: return false
            if (!path.startsWith(prefix)) return false
            val token = path.removePrefix(prefix).toByteArray(Charsets.UTF_8)
            return token.size in 1..1024 && token.all {
                it in 65..90 || it in 97..122 || it in 48..57 || it.toInt() == 45 || it.toInt() == 95
            }
        }

        /**
         * `POST /v2/register` on the relay. Fails open within three seconds, never follows a
         * redirect, and sends nothing but the token, environment, and ref.
         */
        internal fun mint(token: String, environment: String, ref: String): Minted? {
            val relay = RELAY_URL.toHttpUrlOrNull() ?: return null
            val body = buildJsonObject {
                put("token", token)
                put("environment", environment)
                put("ref", ref)
            }.toString()
            val client = OkHttpClient.Builder()
                .callTimeout(3, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .cache(null)
                .build()
            val request = Request.Builder()
                .url(relay.newBuilder().addPathSegments("v2/register").build())
                .header("Content-Type", "application/json")
                .cacheControl(okhttp3.CacheControl.FORCE_NETWORK)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                client.newCall(request).execute().use { response ->
                    if (response.code != 200) return null
                    val output = json.parseToJsonElement(response.body.string()) as? JsonObject ?: return null
                    val url = output["callbackURL"]?.jsonPrimitive?.content ?: return null
                    val expiresAt = output["expiresAt"]?.jsonPrimitive?.content?.let(::parseDate) ?: return null
                    if (!isEncryptedCallback(url, relay) || !expiresAt.isAfter(Instant.now())) return null
                    Minted(url, expiresAt)
                }
            } catch (_: Exception) {
                // Never log the token, the callback URL, or the response.
                AppLog.app.debug("Notification relay unavailable; sending without a callback.")
                null
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }

        /** The relay writes `Date.toISOString()`, which carries milliseconds. */
        private fun parseDate(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }
}

/**
 * The push channel's token, behind an interface. The live provider is FCM's
 * ([FcmPushTokenProvider]); it reports failure at once until a Firebase configuration ships with
 * the build, and every send goes without a callback.
 */
fun interface PushTokenProvider {
    /** Asks for the token; exactly one of the callbacks runs, now or later. */
    fun register(onToken: (String) -> Unit, onFailure: () -> Unit)

    companion object {
        /** No push channel: every registration fails at once. */
        val inert: PushTokenProvider = PushTokenProvider { _, onFailure -> onFailure() }
    }
}
