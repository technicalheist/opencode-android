package ai.opencode.feature.system.notifications

import ai.opencode.core.log.AppLog
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM, the push channel behind [PushTokenProvider]. Inert without a Firebase configuration:
 * Firebase's init provider makes the default `FirebaseApp` from the `google_app_id`,
 * `gcm_defaultSenderId`, `google_api_key` and `project_id` string resources (what the
 * google-services plugin generates; plain resources in `:app` work too). None ship, so no
 * `FirebaseApp` exists, [FcmPushTokenProvider] fails at once and this service never runs.
 *
 * The relay is expected to send a data-only, high-priority FCM message whose `data` is the
 * APNs alert's fields (`title`, `body`, `sessionID`, `status`, `ref`), so the app builds the
 * notification itself ([SessionPushNotifications]: the visible session, the badge count)
 * whether it runs or not.
 */
class OpenCodeMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        SessionNotifications.shared.didRegister(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // The relay's payload: title, body, sessionID, status and ref, as data.
        SessionPushNotifications.handle(applicationContext, message.data)
    }
}

/** The FCM token, when the build carries a Firebase configuration. */
class FcmPushTokenProvider(private val context: Context) : PushTokenProvider {
    override fun register(onToken: (String) -> Unit, onFailure: () -> Unit) {
        if (!isConfigured(context)) return onFailure()
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> if (token.isNullOrEmpty()) onFailure() else onToken(token) }
                .addOnFailureListener {
                    AppLog.app.debug("Push token unavailable")
                    onFailure()
                }
        }.onFailure { onFailure() }
    }

    companion object {
        /** Whether the build carries a Firebase configuration, so FCM can mint tokens. */
        fun isConfigured(context: Context): Boolean =
            runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    }
}
