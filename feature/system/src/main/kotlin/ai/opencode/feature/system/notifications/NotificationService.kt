package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import ai.opencode.feature.system.R
import ai.opencode.feature.system.intents.SystemActionActivity
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Turns a session push into a notification, and decides how it shows in the foreground and where a
 * tap leads.
 *
 * A push carries only the encrypted `v2` envelope under `opencode`. Its title,
 * body, session and status show only once [NotificationContent.verifiedPayload] opens the box
 * with this phone's key for its ref; anything else ("no key yet", a tampered box, a v1 push)
 * shows the generic "OpenCode" / "New activity" notification, never clear text from the
 * transport. The badge counts verified pushes only.
 *
 * Taps: the content intent is built from the verified payload and targets the unexported
 * [SystemActionActivity], so only this app can send it.
 *
 * The transport (FCM's `onMessageReceived`) calls [handle]; nothing calls it until a push
 * channel exists (see [SessionNotifications]).
 */
object SessionPushNotifications {
    /** Statuses a needs-input notification carries. */
    val needsInputStatuses = setOf("needs-input", "permission", "question", "form")

    private const val EXTRA_SESSION = "opencode.sessionID"
    private const val EXTRA_STATUS = "opencode.status"
    private const val EXTRA_ENVELOPE = "opencode.envelope"

    @SuppressLint("MissingPermission")
    fun handle(context: Context, data: Map<String, String>) {
        val verified = NotificationContent.verifiedPayload(data)
        val shown = NotificationContent.content(data, verified)
        val sessionID = verified?.payload?.sessionID
        val notifications = SessionNotifications.shared
        val presentation = notifications.presentation(sessionID)
        if (presentation == NotificationPresentation.None) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        SystemNotificationChannels.ensure(context)
        val builder = NotificationCompat.Builder(context, SystemNotificationChannels.SESSIONS)
            .setSmallIcon(R.drawable.ic_opencode_mark)
            .setContentTitle(shown.title)
            .setContentText(shown.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shown.body))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // The thread is "<ref>:<session>", set only after verifying.
        shown.thread?.let { builder.setGroup(it) }
        builder.extras.putString(EXTRA_SESSION, sessionID)
        builder.extras.putString(EXTRA_STATUS, verified?.payload?.status)
        builder.extras.putString(EXTRA_ENVELOPE, shown.envelope?.toString())
        if (verified != null) {
            // The badge the ledger counts for its session.
            val defaults = AppBadgeLedger.groupDefaults(context)
            AppBadgeLedger.load(defaults)?.badgeAfterPush(sessionID = verified.payload.sessionID, ref = verified.ref)?.let { (ledger, badge) ->
                ledger.save(defaults)
                builder.setNumber(badge)
            }
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context, verified.payload.sessionID.hashCode(),
                    SystemActionActivity.pushIntent(context, verified.ref, verified.payload.sessionID),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                builder.setContentIntent(PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE))
            }
        }
        val tag = sessionID ?: shown.envelope?.toString()?.hashCode()?.toString()
        runCatching { NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build()) }
            .onFailure { AppLog.app.error { "Session notification not posted: ${it::class.java.simpleName}" } }
    }

    /**
     * Every notification for these sessions, or only their needs-input ones once the request is
     * answered. The drawer's `DeliveredNotificationsClient` calls this.
     */
    fun remove(context: Context, sessionIDs: Set<String>, needsInputOnly: Boolean) {
        if (sessionIDs.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (notification in manager.activeNotifications) {
            if (notification.id != NOTIFICATION_ID) continue
            val extras = notification.notification.extras
            val content = Delivered(
                tag = notification.tag,
                group = notification.notification.group,
                sessionID = extras.getString(EXTRA_SESSION),
                status = extras.getString(EXTRA_STATUS),
                envelope = extras.getString(EXTRA_ENVELOPE),
            )
            if (needsInputOnly && !isNeedsInput(content)) continue
            val id = sessionID(content) ?: continue
            if (id !in sessionIDs) continue
            manager.cancel(notification.tag, notification.id)
        }
    }

    /** What [remove] reads from a shown notification. */
    internal data class Delivered(
        val tag: String? = null,
        val group: String? = null,
        val sessionID: String? = null,
        val status: String? = null,
        val envelope: String? = null,
    )

    /**
     * An encrypted preview names its session by the thread [handle] wrote after verifying, else by
     * verifying again (a push that couldn't be decrypted when it arrived); anything else by its
     * session extra, then its tag.
     */
    internal fun sessionID(
        content: Delivered,
        sharedKey: (String) -> ByteArray = { NotificationKeyStore.live.sharedKey(it) },
    ): String? {
        if (encrypted(content) != null) {
            val envelope = NotificationContent.Envelope.from(content.envelope)
            return NotificationContent.threadSessionID(content.group, envelope)
                ?: envelope?.let { NotificationContent.verify(it, sharedKey)?.payload?.sessionID }
        }
        return content.sessionID ?: content.tag
    }

    internal fun isNeedsInput(content: Delivered): Boolean {
        // Encrypted previews are run completions, not local pending-request notices.
        if (encrypted(content) != null) return false
        return content.status in needsInputStatuses
    }

    /** The `opencode` envelope when it is an encrypted one. */
    private fun encrypted(content: Delivered): JsonObject? =
        content.envelope
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?.takeIf { "v" in it }

    /**
     * The app icon's count: launchers count from the shown notifications' `number`, so the session
     * notifications still in the shade are reposted silently with the ledger's count.
     */
    @SuppressLint("MissingPermission")
    fun applyBadge(context: Context, count: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        for (shown in manager.activeNotifications) {
            if (shown.id != NOTIFICATION_ID || shown.notification.number == count) continue
            runCatching {
                val updated = android.app.Notification.Builder.recoverBuilder(context, shown.notification)
                    .setNumber(count)
                    .setOnlyAlertOnce(true)
                    .build()
                manager.notify(shown.tag, shown.id, updated)
            }.onFailure { AppLog.app.debug { "App badge not set: ${it::class.java.simpleName}" } }
        }
    }

    private const val NOTIFICATION_ID = 4201
}
