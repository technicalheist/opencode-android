package ai.opencode.feature.drawer.list

import ai.opencode.core.R as CoreR
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionReduce
import ai.opencode.core.motion.MotionRole
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeAlertDialog
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.InboxSidebarFeature
import ai.opencode.feature.drawer.R
import ai.opencode.feature.drawer.inboxSidebarArchivedRows
import ai.opencode.feature.drawer.isHidden
import ai.opencode.feature.drawer.isMuted
import ai.opencode.feature.drawer.isPinned
import ai.opencode.feature.drawer.isUnread
import ai.opencode.feature.drawer.visibleSessions
import ai.opencode.feature.system.SessionWindowTarget
import ai.opencode.feature.system.WindowsClient
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The drawer's selection. Holding a row chooses it; while any row is chosen, a tap adds or removes
 * one, and the header shows [DrawerSelectionBar] with the chosen sessions' actions.
 */
@Stable
class DrawerSelection {
    var ids: Set<String> by mutableStateOf(emptySet())
        private set

    /** Delete asks first when several sessions are chosen ([DeleteSelectionConfirmation]). */
    var confirmingDelete: Boolean by mutableStateOf(false)

    val isActive: Boolean get() = ids.isNotEmpty()

    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun clear() {
        ids = emptySet()
        confirmingDelete = false
    }

    /** Where the held row's check sat (root pixels, vertical centre) as selection began: the other rows' checks follow out from it ([SelectionLead]). */
    var originY: Float? = null
}

/** The drawer's [DrawerSelection], for the rows' [SelectionLead]. */
internal val LocalDrawerSelection = staticCompositionLocalOf<DrawerSelection?> { null }

/** A chosen row's background, the same in both lists: the accent, faint. */
@Composable
internal fun checkedRowTint(): Color {
    val colors = OpenCodeTheme.colors
    return colors.signal.copy(alpha = if (colors.isDark) 0.18f else 0.10f)
}

/** The circle each row shows while the drawer selects: filled with a check when chosen, a ring when not. */
@Composable
internal fun SelectionCheck(checked: Boolean, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val colors = OpenCodeTheme.colors
    val reduce = rememberReduceMotion()
    // The fill pops in over the ring when the row is chosen, and shrinks away when it isn't.
    val fill = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        val spec = if (checked) MotionRole.MicroIn.animation<Float>(MotionPhase.Enter, reduce)
        else MotionRole.MicroOut.animation(MotionPhase.Exit, reduce)
        fill.animateTo(if (checked) 1f else 0f, spec)
    }
    Box(modifier.size(size).border(1.5.dp, colors.tertiaryText, CircleShape)) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    alpha = fill.value.coerceIn(0f, 1f)
                    scaleX = MotionReduce.scale(fill.value, reduce)
                    scaleY = scaleX
                }
                .background(colors.signal, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = size * 0.65f, tint = Color.White)
        }
    }
}

/** The header while rows are chosen: ✕, “N Selected”, the main actions as icons, then ⋮. */
@Composable
internal fun DrawerSelectionBar(
    count: Int,
    onClose: () -> Unit,
    actions: List<DrawerRowMenuItem>,
    overflow: List<DrawerRowMenuItem>,
    modifier: Modifier = Modifier,
) {
    val colors = OpenCodeTheme.colors
    var overflowOpen by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        OpenCodeIconButton(SFSymbol.Xmark, contentDescription = stringResource(R.string.drawer_selection_cancel_description), onClick = onClose, style = OpenCodeIconButtonStyle.Standard)
        RollingNumber(
            pluralStringResource(R.plurals.drawer_selection_count, count, count),
            style = OpenCodeTextStyles.headline,
            color = colors.text,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        for (item in actions) {
            OpenCodeIconButton(
                item.symbol,
                contentDescription = item.title,
                onClick = item.action,
                style = OpenCodeIconButtonStyle.Standard,
            )
        }
        if (overflow.isEmpty()) return@Row
        // The menu hangs from ⋮'s right edge, so it stays inside the drawer.
        var buttonWidth by remember { mutableIntStateOf(0) }
        var menuWidth by remember { mutableIntStateOf(0) }
        val shift = with(LocalDensity.current) { (menuWidth - buttonWidth).coerceAtLeast(0).toDp() }
        Box(Modifier.onSizeChanged { buttonWidth = it.width }) {
            OpenCodeIconButton(SFSymbol.Ellipsis, contentDescription = stringResource(R.string.drawer_selection_more_options_description), onClick = { overflowOpen = true }, style = OpenCodeIconButtonStyle.Standard)
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
                offset = DpOffset(-shift, 0.dp),
                modifier = Modifier.onSizeChanged { menuWidth = it.width },
            ) {
                for (item in overflow) {
                    DropdownMenuItem(
                        text = { Text(item.title) },
                        leadingIcon = { OpenCodeIcon(item.symbol, contentDescription = null) },
                        onClick = {
                            overflowOpen = false
                            item.action()
                        },
                    )
                }
            }
        }
    }
}

/**
 * The chosen sessions' actions: Mark as Read (Mark as Unread when none is unread), Archive and
 * Delete as icons; Mute or Unmute, Pin or Unpin (the regular list; the Inbox Sidebar has no pins),
 * Hide or Unhide and, for one session where the device has several windows, Open in New Window behind ⋮.
 * Each action ends the selection.
 */
internal fun selectionActions(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    selection: DrawerSelection,
    pins: Boolean,
): Pair<List<DrawerRowMenuItem>, List<DrawerRowMenuItem>> {
    val state = store.state
    val ids = selection.ids.toList()
    val sessions = state.visibleSessions.filter { it.id in selection.ids }
    var anyUnread = false
    var allMuted = true
    var allPinned = true
    var allHidden = true
    for (session in sessions) {
        if (state.isUnread(session)) anyUnread = true
        if (!state.isMuted(session.id)) allMuted = false
        if (!state.isPinned(session.id)) allPinned = false
        if (!state.isHidden(session.id)) allHidden = false
    }

    val main = listOf(
        DrawerRowMenuItem(
            if (anyUnread) localized(R.string.drawer_mark_as_read) else localized(R.string.drawer_mark_as_unread),
            if (anyUnread) SFSymbol.EnvelopeOpen else SFSymbol.EnvelopeBadge,
        ) {
            for (session in sessions) {
                if (!anyUnread) {
                    store.send(HomeFeature.Action.MarkUnread(session.id))
                } else if (state.isUnread(session)) {
                    store.send(HomeFeature.Action.MarkRead(session.id))
                }
            }
            selection.clear()
        },
        DrawerRowMenuItem(localized(R.string.drawer_archive), SFSymbol.Archivebox) {
            for (session in sessions) {
                store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchiveTapped(session.id, session.title)))
            }
            selection.clear()
        },
        DrawerRowMenuItem(localized(CoreR.string.common_delete), SFSymbol.Trash) {
            if (ids.size == 1) {
                // One session asks in the existing words ([ai.opencode.feature.drawer.HomeDeleteConfirmation]).
                store.send(HomeFeature.Action.DeleteSessionTapped(ids[0]))
                selection.clear()
            } else {
                selection.confirmingDelete = true
            }
        },
    )

    val overflow = mutableListOf<DrawerRowMenuItem>()
    overflow.add(
        DrawerRowMenuItem(if (allMuted) localized(R.string.drawer_unmute) else localized(R.string.drawer_mute), if (allMuted) SFSymbol.Bell else SFSymbol.BellSlash) {
            for (session in sessions) {
                if (state.isMuted(session.id) == allMuted) store.send(HomeFeature.Action.ToggleMute(session.id))
            }
            selection.clear()
        },
    )
    if (pins) {
        overflow.add(
            DrawerRowMenuItem(if (allPinned) localized(R.string.drawer_unpin) else localized(R.string.drawer_pin), if (allPinned) SFSymbol.PinSlash else SFSymbol.Pin) {
                for (session in sessions) {
                    if (state.isPinned(session.id) == allPinned) store.send(HomeFeature.Action.TogglePin(session.id))
                }
                selection.clear()
            },
        )
    }
    overflow.add(
        DrawerRowMenuItem(if (allHidden) localized(R.string.drawer_unhide) else localized(R.string.drawer_hide), if (allHidden) SFSymbol.Eye else SFSymbol.EyeSlash) {
            for (session in sessions) {
                if (state.isHidden(session.id) == allHidden) store.send(HomeFeature.Action.ToggleHidden(session.id))
            }
            selection.clear()
        },
    )
    val windows = dependency(WindowsClient)
    if (ids.size == 1 && windows.isAvailable()) {
        overflow.add(
            DrawerRowMenuItem(localized(R.string.drawer_open_in_new_window), SFSymbol.MacwindowBadgePlus) {
                windows.open(SessionWindowTarget(serverID = state.server.persistenceID, sessionID = ids[0]))
                selection.clear()
            },
        )
    }
    return main to overflow
}

/** The Archived page's chosen sessions: Unarchive and Delete. Each action ends the selection. */
internal fun archivedSelectionActions(
    store: StoreOf<HomeFeature.State, HomeFeature.Action>,
    selection: DrawerSelection,
): List<DrawerRowMenuItem> {
    val rows = store.state.inboxSidebarArchivedRows().filter { it.id in selection.ids }
    return listOf(
        DrawerRowMenuItem(localized(R.string.drawer_unarchive), SFSymbol.TrayAndArrowDown) {
            for (row in rows) {
                store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.UnarchiveTapped(row.id, row.title)))
            }
            selection.clear()
        },
        DrawerRowMenuItem(localized(CoreR.string.common_delete), SFSymbol.Trash) {
            // Delete reads the sessions Home lists, so the archived ones join it first.
            for (row in rows) {
                store.send(HomeFeature.Action.InboxSidebar(InboxSidebarFeature.Action.ArchivedRowTapped(row.id)))
            }
            if (rows.size == 1) {
                store.send(HomeFeature.Action.DeleteSessionTapped(rows[0].id))
                selection.clear()
            } else {
                selection.confirmingDelete = true
            }
        },
    )
}

/** Deleting several sessions asks once, in the single delete's words (`HomeDeleteConfirmation`). */
@Composable
internal fun DeleteSelectionConfirmation(store: StoreOf<HomeFeature.State, HomeFeature.Action>, selection: DrawerSelection) {
    if (!selection.confirmingDelete) return
    OpenCodeAlertDialog(
        onDismissRequest = { selection.confirmingDelete = false },
        title = { Text(pluralStringResource(R.plurals.drawer_delete_sessions_title, selection.ids.size, selection.ids.size)) },
        text = { Text(stringResource(R.string.drawer_delete_sessions_message)) },
        confirmButton = {
            TextButton(onClick = {
                for (id in selection.ids) {
                    store.send(HomeFeature.Action.DeleteSessionTapped(id))
                    store.send(HomeFeature.Action.DeleteSessionConfirmed)
                }
                selection.clear()
            }) {
                Text(stringResource(CoreR.string.common_delete), color = OpenCodeTheme.colors.destructive)
            }
        },
        dismissButton = {
            TextButton(onClick = { selection.confirmingDelete = false }) { Text(stringResource(CoreR.string.common_cancel)) }
        },
    )
}
