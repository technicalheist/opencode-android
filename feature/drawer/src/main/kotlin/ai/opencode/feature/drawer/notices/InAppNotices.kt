package ai.opencode.feature.drawer.notices

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.feature.system.notifications.SessionPushNotifications
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Removes delivered notifications from the notification shade: `:feature:system`'s
 * [SessionPushNotifications.remove], which knows how it posts them.
 */
class DeliveredNotificationsClient(
    /** Every notification for these sessions (their thread identifiers). */
    val removeAll: suspend (sessionIDs: Set<String>) -> Unit,
    /** Only their needs-input notifications, once the request is answered. */
    val removeNeedsInput: suspend (sessionIDs: Set<String>) -> Unit,
) {
    companion object Key : DependencyKey<DeliveredNotificationsClient> {
        override val liveValue: DeliveredNotificationsClient by lazy {
            val context = dependency(AppContext)
            DeliveredNotificationsClient(
                removeAll = { ids -> withContext(Dispatchers.Default) { SessionPushNotifications.remove(context, ids, needsInputOnly = false) } },
                removeNeedsInput = { ids -> withContext(Dispatchers.Default) { SessionPushNotifications.remove(context, ids, needsInputOnly = true) } },
            )
        }
        override val testValue = DeliveredNotificationsClient(removeAll = {}, removeNeedsInput = {})
    }
}

/** TalkBack announcements that don't move focus. Main thread. */
class AccessibilityAnnouncer(val announce: (text: String, urgent: Boolean) -> Unit) {
    companion object Key : DependencyKey<AccessibilityAnnouncer> {
        override val liveValue: AccessibilityAnnouncer by lazy {
            val context = dependency(AppContext)
            AccessibilityAnnouncer { text, _ ->
                // Android has no announcement priority; an urgent one reads the same.
                val manager = context.getSystemService(AccessibilityManager::class.java)
                if (manager == null || !manager.isEnabled) return@AccessibilityAnnouncer
                @Suppress("DEPRECATION")
                val event = AccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
                    packageName = context.packageName
                    this.text.add(text)
                }
                manager.sendAccessibilityEvent(event)
            }
        }
        override val testValue = AccessibilityAnnouncer { _, _ -> }
    }
}
