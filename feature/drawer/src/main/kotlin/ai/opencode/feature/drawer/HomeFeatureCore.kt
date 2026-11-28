package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.server.OpenCodeServerEvent

/*
 * Home's own reducer, routing each action to the area that handles it, then absorbing shared drafts
 * and persisting the snapshot after list-changing actions.
 */

internal fun HomeFeature.core(state: State, action: Action): Reduced<State, Action> {
    val reduced = coreRoute(state, action)
    var s = reduced.state.absorbingSharedDrafts()
    val persists = when (action) {
        is Action.SnapshotRefreshed, is Action.DeleteSessionConfirmed, is Action.DeleteSessionResponse,
        Action.TranscriptCacheFlushRequested -> true
        is Action.ServerEvent -> action.event is OpenCodeServerEvent.SessionRenamed ||
            action.event is OpenCodeServerEvent.SessionDeleted ||
            action.event is OpenCodeServerEvent.SessionArchiveChanged ||
            action.event is OpenCodeServerEvent.ProjectUpdated ||
            action.event is OpenCodeServerEvent.SessionMoved
        is Action.SessionDetail -> when ((action.action as? PresentationAction.Presented)?.action) {
            is SessionFeature.Action.SessionCreated, is SessionFeature.Action.MissingFolderMoved -> true
            // A session that just learned its first prompt keeps it across launches.
            is SessionFeature.Action.AcceptedTranscriptUpdated -> reduced.state.sessions != state.sessions
            else -> false
        }
        else -> false
    }
    if (persists) s = s.persistingSnapshot(dependency(HomeSnapshotDate).now())
    return Reduced(s, reduced.effect)
}

private fun HomeFeature.coreRoute(state: State, action: Action): Reduced<State, Action> = when (action) {
    is Action.CatchUp -> reduceCatchUp(state, action.action)
    // The scoped `TraceUploadsFeature` reduces it (`HomeFeature+Composition`).
    is Action.Traces -> Reduced(state)
    // The scoped `InboxSidebarFeature` owns Archive and Unarchive; Home stamps a confirmed archive
    // and restores an unarchived one, refetching the list.
    is Action.InboxSidebar -> when (val inbox = action.action) {
        is InboxSidebarFeature.Action.ArchiveResponse ->
            if (inbox.error == null) archiveConfirmed(state, inbox.sessionID) else Reduced(state)
        is InboxSidebarFeature.Action.ArchivedRowTapped -> Reduced(state.listingArchivedSession(inbox.sessionID))
        is InboxSidebarFeature.Action.UnarchiveResponse ->
            if (inbox.error == null) {
                refreshSnapshot(state.updatingSession(inbox.sessionID) { it.copy(archivedMilliseconds = null) })
            } else {
                Reduced(state)
            }
        else -> Reduced(state)
    }

    Action.NotificationSettingsRequested,
    is Action.NotificationPermissionLoaded,
    Action.EnableNotificationsTapped,
    is Action.NotificationPermissionRequested,
    is Action.NotificationSettingsOpened,
    Action.NotifyPluginCheckRequested,
    is Action.NotifyPluginChecked,
    Action.NotifyPluginSetUpTapped,
    -> reduceNotifications(state, action)

    Action.Task,
    Action.TranscriptCacheFlushRequested,
    Action.SceneEnteredBackground,
    Action.ConnectionLost,
    is Action.EventStreamFailed,
    is Action.ServerConnected,
    Action.SnapshotFailed,
    Action.SnapshotRetryRequested,
    Action.LastKnownActivityExpired,
    Action.RetryConnectionTapped,
    Action.SceneBecameActive,
    Action.ReconnectingGraceElapsed,
    is Action.OfflineGraceElapsed,
    Action.NetworkRerouted,
    is Action.SnapshotRefreshed,
    is Action.ActiveAncestryResolved,
    is Action.AttentionRefreshed,
    -> reduceConnection(state, action)

    is Action.ServerEvent -> reduceServerEvent(state, action.event)

    is Action.SessionPages -> reduceSessionPages(state, action.action)

    Action.ServerSwitchNoteActionTapped -> {
        val note = state.serverSwitchNote
        if (note == null) {
            Reduced(state)
        } else {
            val delegate = if (note.reason == ServerSwitchNote.Reason.SignInFailed) {
                HomeFeature.DelegateAction.PairServerRequested
            } else {
                HomeFeature.DelegateAction.ServerSwitchTapped(note.server)
            }
            Reduced(state.copy(serverSwitchNote = null), Effect.send(Action.Delegate(delegate)))
        }
    }

    is Action.VoiceCallCommandReceived,
    is Action.DictationCommandReceived,
    is Action.SharesReceived,
    is Action.SystemActionOpened,
    is Action.SystemPublishRequested,
    -> reduceSystem(state, action)

    is Action.SessionDetail -> when (val presentation = action.action) {
        is PresentationAction.Presented -> reduceSessionDetail(state, presentation.action)
        PresentationAction.Dismiss -> Reduced(state)
    }

    is Action.SubagentStack -> {
        val reduced = reduceSubagent(state, action.id, action.action)
        when (val detailAction = action.action) {
            SessionFeature.Action.Task -> {
                val detail = reduced.state.subagentStack.firstOrNull { it.navigationID == action.id }
                val sessionID = detail?.session?.id
                if (detail == null || detail.initialTranscriptRequestIsExternal || sessionID == null) {
                    reduced
                } else {
                    val cancelled = cancelBackgroundTranscriptReads(reduced.state, setOf(sessionID))
                    Reduced(cancelled.state, Effect.merge(reduced.effect, cancelled.effect))
                }
            }
            is SessionFeature.Action.RefreshRequested -> {
                if (detailAction.reason !in foregroundRefreshReasons) {
                    reduced
                } else {
                    val sessionID = reduced.state.subagentStack.firstOrNull { it.navigationID == action.id }?.session?.id
                    val cancelled = cancelBackgroundTranscriptReads(reduced.state, setOfNotNull(sessionID))
                    Reduced(cancelled.state, Effect.merge(reduced.effect, cancelled.effect))
                }
            }
            else -> reduced
        }
    }

    is Action.LinkedSessionFetched, is Action.LinkedSessionOpened -> reduceSessionLinks(state, action)

    is Action.SubagentsPopped -> Reduced(state.poppingSubagents(action.depth))

    Action.SettingsTapped,
    Action.ManageServersTapped,
    is Action.SessionDrawerArrived,
    Action.NewSessionTapped,
    is Action.SessionTapped,
    is Action.SessionNavigationReady,
    is Action.SelectedTranscriptResponse,
    is Action.TranscriptPrefetched,
    is Action.CachedTranscriptRefreshed,
    is Action.DrawerRowPressed,
    is Action.TogglePin,
    is Action.ToggleMute,
    is Action.ToggleHidden,
    is Action.MovePinUp,
    is Action.MovePinDown,
    is Action.DrawerPinZoneCrossed,
    Action.DrawerPinSlotChanged,
    is Action.DrawerRowDropReleased,
    is Action.DrawerRowDropped,
    Action.UnsentDraftsChanged,
    is Action.DeleteSessionTapped,
    Action.DeleteSessionCancelled,
    Action.DeleteSessionConfirmed,
    is Action.DeleteSessionResponse,
    Action.ClearDeletionError,
    is Action.MarkRead,
    is Action.MarkUnread,
    is Action.ViewAcknowledged,
    is Action.PlaygroundSetSessionDetail,
    is Action.PlaygroundReplaceState,
    Action.PairedComputerOpened,
    Action.FirstComputerPaired,
    Action.DrawerOpenRequestHandled,
    Action.DrawerCallTapped,
    -> reduceDrawer(state.adoptingSearchResultFor(action), action)

    is Action.DrawerLabelAvailabilityChanged -> {
        var s = state.copy(showsDrawerLabel = action.shows)
        // A sidebar takes over an open drawer without it arriving shut.
        if (!action.shows) s = s.copy(isDrawerOpen = false)
        Reduced(s)
    }

    is Action.WhatsNew -> reduceWhatsNew(state, action.action)
    is Action.SettingsScreen -> reduceSettingsScreen(state, action.action)
    // Show Welcome: WelcomeFeature reduces it; its pairing requests are the app's (AppFeature+Welcome).
    is Action.Welcome -> Reduced(state)

    is Action.Delegate -> when (val delegate = action.action) {
        // Named here as well as in AppFeature (which persists it), so the publications watching this
        // reducer see the new name and republish.
        is HomeFeature.DelegateAction.ServerRenamed -> {
            if (delegate.server.persistenceID != state.server.persistenceID) {
                Reduced(state)
            } else {
                val trimmed = delegate.name.trim()
                Reduced(state.withServer(state.server.copy(name = trimmed.ifEmpty { null })))
            }
        }
        else -> Reduced(state)
    }

    is Action.SearchChanged -> Reduced(state.copy(search = action.text))
    is Action.SettingsShownChanged ->
        Reduced(if (action.isShown) state.showingSettings() else state.copy(settings = null))

    // The Onboarding Playground opens inside Settings (`SettingsFeature`), which sends it there.
    is Action.Settings -> {
        val reduced = reducePreferences(state, action.action)
        if (action.action == HomeSettingsFeature.Action.WelcomeOpened) {
            // Show Welcome, over Settings and Home.
            Reduced(state.copy(welcome = WelcomeFeature.State(isPreview = true)))
        } else if (!action.action.switchesOffSessionLiveActivity) {
            reduced
        } else {
            val stopped = stopSessionActivity(reduced.state)
            Reduced(stopped.state, Effect.merge(reduced.effect, stopped.effect))
        }
    }

    is Action.SessionActivitySettled -> Reduced(state.notingSessionActivitySettled(action.sessionID, action.items))

}
