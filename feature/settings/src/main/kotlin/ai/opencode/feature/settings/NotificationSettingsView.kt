package ai.opencode.feature.settings

import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * As Settings shows it: Home reads the server's notify plugin and passes the result in
 * [SettingsFeature.Action.NotificationsChanged].
 */
sealed interface NotifyPluginCheck {
    data object Checking : NotifyPluginCheck
    data class Checked(val status: OpenCodeNotifyPluginStatus) : NotifyPluginCheck
    data object Failed : NotifyPluginCheck
}

/**
 * Settings → Notifications, this phone's permission, whether the server runs the notify plugin
 * (with Set Up with OpenCode to have OpenCode install it).
 */
@Composable
fun NotificationSettingsView(store: SettingsStore) {
    val permission by store.observe { it.notificationPermission }
    val isRequesting by store.observe { it.isRequestingNotifications }
    val setupError by store.observe { it.notificationSetupError }
    val notifyPlugin by store.observe { it.notifyPlugin }
    val disconnected by store.observe { it.connectionStatus == ConnectionStatus.disconnected }
    LaunchedEffect(store) {
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotificationSettingsRequested))
        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyPluginCheckRequested))
    }
    val enabled = permission == NotificationPermission.Enabled
    val permissionHeader = stringResource(R.string.settings_notifications_device_header, ThisDeviceName)
    val permissionFooter = stringResource(R.string.settings_notifications_device_footer, ThisDeviceName)
    val serverHeader = stringResource(R.string.settings_notifications_server_header)
    val setUpFooter = stringResource(R.string.settings_notifications_set_up_footer)
    val pluginNeededFooter = stringResource(R.string.settings_notifications_plugin_needed_footer)

    SettingsPageScaffold(title = stringResource(R.string.settings_notifications_title), store = store) {
        settingsSection(
            key = "permission",
            header = permissionHeader,
            footer = if (permission == NotificationPermission.Disabled) {
                null
            } else {
                footerText(permissionFooter)
            },
        ) {
            SettingsRowFrame(Modifier.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.settings_notifications_allow), style = SettingsText.title, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                Text(if (enabled) stringResource(R.string.settings_value_on) else stringResource(R.string.settings_value_off), style = SettingsText.title, color = OpenCodeTheme.colors.secondaryText)
            }
            if (!enabled) {
                SettingsDivider()
                SettingsButtonRow(
                    if (permission == NotificationPermission.Disabled) stringResource(R.string.settings_notifications_open_android_settings) else stringResource(R.string.settings_notifications_turn_on),
                    onClick = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.EnableNotificationsTapped))
                    },
                    enabled = !isRequesting,
                )
            }
            setupError?.let { error ->
                SettingsDivider()
                SettingsRowFrame {
                    OpenCodeErrorText(error, style = SettingsText.summary)
                }
            }
        }
        val canSetUp = notifyPlugin == NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Missing) ||
            notifyPlugin == NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Inactive)
        settingsSection(
            key = "server",
            header = serverHeader,
            footer = when {
                canSetUp -> footerText(setUpFooter)
                notifyPlugin == NotifyPluginCheck.Checked(OpenCodeNotifyPluginStatus.Active) -> null
                else -> footerText(pluginNeededFooter)
            },
        ) {
            SettingsRowFrame(Modifier.semantics(mergeDescendants = true) {}) {
                Text(stringResource(R.string.settings_notifications_notify_plugin), style = SettingsText.title, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                when (val check = notifyPlugin) {
                    NotifyPluginCheck.Checking, null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    is NotifyPluginCheck.Checked -> Text(
                        when (check.status) {
                            OpenCodeNotifyPluginStatus.Active -> stringResource(R.string.settings_notifications_plugin_installed)
                            OpenCodeNotifyPluginStatus.Inactive -> stringResource(R.string.settings_notifications_plugin_not_running)
                            OpenCodeNotifyPluginStatus.Missing -> stringResource(R.string.settings_notifications_plugin_not_installed)
                        },
                        style = SettingsText.title,
                        color = OpenCodeTheme.colors.secondaryText,
                    )
                    NotifyPluginCheck.Failed ->
                        Text(stringResource(R.string.settings_notifications_plugin_check_failed), style = SettingsText.title, color = OpenCodeTheme.colors.secondaryText)
                }
            }
            if (canSetUp) {
                SettingsDivider()
                SettingsButtonRow(
                    stringResource(R.string.settings_notifications_set_up),
                    onClick = {
                        store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyPluginSetUpTapped))
                    },
                    enabled = !disconnected,
                )
            }
            SettingsDivider()
            SettingsButtonRow(
                stringResource(R.string.settings_notifications_check_again),
                onClick = {
                    store.send(SettingsFeature.Action.Delegate(SettingsFeature.SettingsDelegate.NotifyPluginCheckRequested))
                },
                enabled = notifyPlugin != NotifyPluginCheck.Checking,
            )
        }
    }
}

/** “phone” or “tablet”. */
val ThisDeviceName: String get() = ai.opencode.core.ui.ThisDevice.name
