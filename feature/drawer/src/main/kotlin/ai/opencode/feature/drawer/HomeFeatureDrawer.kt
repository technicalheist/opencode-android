package ai.opencode.feature.drawer

import ai.opencode.core.log.CallTrigger
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.diagnostics.PerfTimeline
import ai.opencode.core.experiments.key
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.composer.unsentDrafts
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.session.*
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/*
 * The drawer's own actions — opening sessions and drafts, pins, mutes, read state, deletion, and
 * the transcript reads around a tap.
 */


internal fun HomeFeature.reduceDrawer(state: State, action: Action): HomeReduced {
    val haptics = dependency(HapticsClient)
    when (action) {
        Action.SettingsTapped -> return Reduced(state.showingSettings())

        Action.DrawerCallTapped -> {
            if (!state.drawerShowsCallButton) return Reduced(state)
            if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(state)
            // The screen on top's own call button: dictation yields first, and a pushed subagent
            // asks the root, which owns the call. No haptic here: the call's start plays it. The
            // drawer stays open under the orb.
            val detail = state.sessionDetail
            if (detail != null && (detail.session != null || detail.draftProject != null)) {
                var s = state
                if (detail.voice?.phase?.isTerminal == true) s = s.updatingDetail { it.copy(voice = null) }
                CallTrigger.Drawer.log()
                val top = s.subagentStack.lastOrNull()
                if (top != null) return Reduced(s, Effect.send(Action.SubagentStack(top.navigationID, SessionFeature.Action.VoiceTapped)))
                return Reduced(s, Effect.send(Action.detail(SessionFeature.Action.VoiceTapped)))
            }
            // Nothing behind it to call: Start Voice Call's Last Viewed path.
            return openSystemDestination(state, SystemAction.Destination.LastViewed, startsCall = true, trigger = CallTrigger.Drawer)
        }

        Action.ManageServersTapped ->
            return Reduced(if (state.settings != null) state else state.presentingSettings(SettingsFeature.State.focusingServers(state.server)))

        is Action.SessionDrawerArrived -> {
            var s = state.copy(isDrawerOpen = action.isOpen)
            // Server Flow: a switch-back note is seen once, with the drawer.
            if (!action.isOpen) return Reduced(s.copy(serverSwitchNote = null))
            // The reader is about to choose a session: read the likeliest ones again now, so the
            // one they open already has what's new (ahead of any read still waiting out its delay).
            val likely = s.sessionsByPriority().take(HomeFeature.prefetchCount).map { it.id }
            val effects = likely.map { id ->
                val reduced = refreshCachedTranscript(s, id, freshFor = freshFor(5), force = true)
                s = reduced.state
                reduced.effect
            }
            return Reduced(s, Effect.merge(effects))
        }

        // The tap usually follows within a few hundred milliseconds.
        is Action.DrawerRowPressed -> return refreshCachedTranscript(state, action.sessionID, freshFor = freshFor(2), force = true)

        is Action.CachedTranscriptRefreshed -> {
            var s = state.copy(fetchingTranscripts = state.fetchingTranscripts - action.sessionID)
            if (action.sessionID !in s.refreshingTranscripts) return Reduced(s)
            s = s.copy(refreshingTranscripts = s.refreshingTranscripts - action.sessionID)
            // The opened session owns its entry.
            val items = action.items ?: return Reduced(s)
            if (!canCacheBackgroundTranscript(s, action.sessionID)) return Reduced(s)
            s = s.copy(transcriptReadAt = s.transcriptReadAt + (action.sessionID to dependency(DateClient).now()))
            s = s.notingUnopenedCopy(action.sessionID)
            val refreshed = if (action.sessionID in s.activeSessionIDs) {
                SessionFeature.preservingInProgressText(items, s.transcriptCache[action.sessionID].orEmpty())
            } else items
            s = s.publishingTranscript(action.sessionID, refreshed).persistingTranscriptCache(action.sessionID)
            return Reduced(s)
        }

        is Action.ViewAcknowledged -> return Reduced(state.notingViewAcknowledged(action.sessionID, action.idle))

        is Action.TranscriptPrefetched -> {
            // Fill an empty slot only; an opened session owns its entry.
            if (!canCacheBackgroundTranscript(state, action.sessionID) || action.sessionID in state.transcriptCache) return Reduced(state)
            return Reduced(state.publishingTranscript(action.sessionID, action.items).persistingTranscriptCache(action.sessionID))
        }

        is Action.SelectedTranscriptResponse -> {
            val fetch = state.selectedTranscriptFetch
            if (fetch == null || fetch.sessionID != action.sessionID || fetch.transition != state.detailGeneration ||
                state.sessionDetail?.session?.id != action.sessionID
            ) {
                return Reduced(state)
            }
            var s = state.copy(selectedTranscriptFetch = null)
            action.result.getOrNull()?.let { items ->
                s = s.publishingTranscript(action.sessionID, items).persistingTranscriptCache(action.sessionID)
            }
            return Reduced(s, Effect.send(Action.detail(SessionFeature.Action.TranscriptResponse(action.sessionID, action.result, action.pageCursor))))
        }

        is Action.SessionNavigationReady -> {
            if (state.selectedTranscriptFetch?.sessionID != action.sessionID) return Reduced(state)
            val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return Reduced(state)
            val reason = state.nextMoveReason
            var s = state.openingDetail(session, externalTranscript = true)
            s = s.copy(selectedTranscriptFetch = s.selectedTranscriptFetch?.copy(transition = s.detailGeneration))
            s = s.pushingPendingSubagent(reason)
            LabMarks.mark("session_navigation_started", sessionID = action.sessionID)
            return Reduced(s)
        }

        Action.NewSessionTapped -> {
            // A draft is local; the server is only needed on first send. Only a confirmed outage blocks it.
            if (state.connectionStatus == HomeConnectionFeature.State.ConnectionStatus.disconnected) return Reduced(state)
            return Reduced(state.leavingCatchUp().openingDraft(reason = "you tapped New Session"))
        }

        Action.PairedComputerOpened -> {
            // A computer just paired opens the drawer on its sessions, newest first, for the person
            // to pick one. With none, or something already on screen, nothing moves.
            val roots = state.sessions.count { it.parentID == null }
            if (roots <= 0 || state.sessionDetail?.isDraft == false || state.catchUp != null) {
                AppLog.app.info { "paired computer keeps draft sessions=$roots" }
                return Reduced(state)
            }
            AppLog.app.info { "paired computer opens drawer sessions=$roots" }
            return Reduced(state.copy(drawerOpenRequest = state.detailGeneration))
        }

        Action.FirstComputerPaired -> {
            // The install's first computer opens a new session in its latest project, with starter
            // prompts, ready to send. With no project but the home folder, or something already on
            // screen, it lands as any new computer does.
            val project = state.firstSessionProject
            if (state.sessionDetail?.isDraft == false || state.catchUp != null || project == null) {
                AppLog.app.info { "first computer has no project to open projects=${state.projects.size}" }
                return reduceDrawer(state, Action.PairedComputerOpened)
            }
            AppLog.app.info { "first computer opens a ready session projects=${state.projects.size}" }
            val (s, draft) = state.makeDraft(directory = project.directory, reason = "you paired your first computer")
            return Reduced(s.copy(sessionDetail = draft.copy(starterPrompts = SessionFeature.State.firstSessionStarters)))
        }

        Action.DrawerOpenRequestHandled -> return Reduced(state.copy(drawerOpenRequest = null))

        is Action.SessionTapped -> {
            val unsent = state.unsentDraft(action.sessionID) ?: return sessionTapped(state, action.sessionID)
            return Reduced(state.leavingCatchUp().openingUnsentDraft(unsent))
        }

        Action.UnsentDraftsChanged -> {
            val drafts = dependency(ComposerPersistence).unsentDrafts(state.server.persistenceID)
            return Reduced(if (drafts == state.unsentDrafts) state else state.copy(unsentDrafts = drafts))
        }

        is Action.TogglePin -> {
            if (state.sessions.none { it.id == action.sessionID }) return Reduced(state)
            haptics.play(Haptic.selection)
            val key = state.sessionKey(action.sessionID)
            return Reduced(
                if (state.isPinned(action.sessionID)) state.updatingPinnedSessionKeys { keys -> keys.filterNot { it == key } }
                else state.updatingPinnedSessionKeys { it + key },
            )
        }

        is Action.ToggleMute -> {
            if (state.sessions.none { it.id == action.sessionID }) return Reduced(state)
            haptics.play(Haptic.selection)
            val key = state.sessionKey(action.sessionID)
            val s = if (state.isMuted(action.sessionID)) state.updatingMutedSessionKeys { it - key }
            else state.updatingMutedSessionKeys { it + key }
            // A muted card leaves the stack unless it is the one on screen.
            return Reduced(s.reconcilingCatchUp())
        }

        is Action.ToggleHidden -> {
            if (state.sessions.none { it.id == action.sessionID }) return Reduced(state)
            haptics.play(Haptic.selection)
            val key = state.sessionKey(action.sessionID)
            return Reduced(
                if (state.isHidden(action.sessionID)) state.updatingHiddenSessionKeys { it - key }
                else state.updatingHiddenSessionKeys { it + key },
            )
        }

        is Action.MovePinUp -> {
            val ids = state.pinnedSessions.map { it.id }
            val index = ids.indexOf(action.sessionID)
            if (index <= 0) return Reduced(state)
            return movePin(state, action.sessionID, before = ids[index - 1])
        }

        is Action.MovePinDown -> {
            val ids = state.pinnedSessions.map { it.id }
            val index = ids.indexOf(action.sessionID)
            if (index < 0 || index + 1 >= ids.size) return Reduced(state)
            return movePin(state, action.sessionID, before = ids.getOrNull(index + 2))
        }

        is Action.DrawerPinZoneCrossed -> {
            haptics.play(Haptic.threshold)
            return Reduced(state)
        }

        Action.DrawerPinSlotChanged -> {
            haptics.play(Haptic.selection)
            return Reduced(state)
        }

        is Action.DrawerRowDropReleased -> {
            if (willChangePins(state, action.sessionID, action.pinnedIndex)) haptics.play(Haptic.snap)
            return Reduced(state)
        }

        is Action.DrawerRowDropped -> return Reduced(dropPin(state, action.sessionID, action.pinnedIndex))

        is Action.DeleteSessionTapped -> {
            val session = state.unsentDraft(action.sessionID)?.placeholderSession ?: state.sessions.firstOrNull { it.id == action.sessionID }
            return Reduced(state.copy(pendingDeletion = session, deletionError = null))
        }

        Action.DeleteSessionCancelled -> return Reduced(state.copy(pendingDeletion = null))

        Action.DeleteSessionConfirmed -> {
            val session = state.pendingDeletion ?: return Reduced(state)
            val unsent = state.unsentDraft(session.id)
            if (unsent != null) {
                // Only this phone has it: its messages leave the outbox and the list.
                haptics.play(Haptic.confirm)
                dependency(UndeliveredPrompts).resolve(unsent.messageIDs)
                val outbox = dependency(PromptOutbox)
                for (id in unsent.messageIDs) outbox.withdraw(id)
                val persistence = dependency(ComposerPersistence)
                persistence.settlePrompts(unsent.messageIDs)
                var s = state.copy(pendingDeletion = null, unsentDrafts = persistence.unsentDrafts(state.server.persistenceID))
                val open = s.sessionDetail
                if (open != null && open.session == null && open.outboxDraftID == unsent.draftID) s = s.openingDraft(reason = "you deleted it")
                return Reduced(s)
            }
            val hidden = hideSession(state, session) ?: return Reduced(state.copy(pendingDeletion = null))
            var (s, backup) = hidden
            s = s.copy(pendingDeletion = null, deletionError = null)
            haptics.play(Haptic.confirm)
            val server = s.server
            val client = dependency(OpenCodeClient)
            val deletion = Effect.run<Action> { send ->
                val error = try {
                    client.deleteSession(server, session.id)
                    null
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: OpenCodeClientError) {
                    failure
                }
                send(Action.DeleteSessionResponse(session.id, backup, error))
            }.cancellable(HomeFeature.CancelID.DeleteSession(session.id), cancelInFlight = true)
            val cancelSelected = if (s.selectedTranscriptFetch?.sessionID == session.id) {
                s = s.copy(selectedTranscriptFetch = null)
                true
            } else {
                backup.replacementDraftID != null
            }
            return Reduced(
                s,
                Effect.merge(
                    Effect.cancel(HomeFeature.CancelID.Prefetch),
                    if (cancelSelected) Effect.cancel(HomeFeature.CancelID.SelectedTranscript) else Effect.none(),
                    deletion,
                ),
            )
        }

        is Action.DeleteSessionResponse -> {
            val error = action.error
            if (error == null) {
                val s = state.purgingSession(action.sessionID)
                dependency(ShareInboxClient).removeSuggestions(s.server.persistenceID, listOf(action.sessionID))
                return Reduced(s)
            }
            var s = state.copy(recentlyDeletedIDs = state.recentlyDeletedIDs - action.sessionID)
            s = restoreSession(s, action.backup)
            val failure = DrawerFailure(DrawerFailure.Action.delete, action.backup.session.title, error.message)
            s = s.copy(deletionError = failure.joining(state.deletionError))
            haptics.play(Haptic.error)
            val clock = dependency(Clock)
            return Reduced(
                s,
                Effect.run<Action> { send ->
                    clock.sleep(5.seconds)
                    send(Action.ClearDeletionError)
                }.cancellable(HomeFeature.CancelID.DeletionError, cancelInFlight = true),
            )
        }

        Action.ClearDeletionError -> return Reduced(state.copy(deletionError = null))

        is Action.MarkRead -> {
            val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return Reduced(state)
            haptics.play(Haptic.selection)
            return Reduced(state.markingReadLocally(session).clearingUnseenBaseline(session.id), acknowledgeView(session, state.server))
        }

        is Action.MarkUnread -> {
            val session = state.sessions.firstOrNull { it.id == action.sessionID } ?: return Reduced(state)
            haptics.play(Haptic.selection)
            // A receipt still in flight would otherwise mark it read again.
            return Reduced(state.markingUnreadLocally(session), Effect.cancel(HomeFeature.CancelID.ViewReceipt(action.sessionID)))
        }

        is Action.PlaygroundSetSessionDetail -> return Reduced(state.copy(sessionDetail = action.detail))

        is Action.PlaygroundReplaceState -> return Reduced(action.state)

        else -> return Reduced(state)
    }
}

private fun HomeFeature.sessionTapped(state: State, sessionID: String): HomeReduced {
    // Navigation and cached transcripts do not require a live event stream.
    val session = state.sessions.firstOrNull { it.id == sessionID } ?: return Reduced(state)
    val moveReason = state.nextMoveReason
    // A session opened on the way back (a notification, a link) is what the return shows.
    var s = state.forgettingAppLeft()
    if (s.pendingSubagentPush?.parentID != sessionID) s = s.copy(pendingSubagentPush = null)
    // A subagent (a link, a notification, the voice agent) lands pushed over its parent when the
    // parent is at hand; otherwise on its own. Already pushed: go back to it.
    val pushedIndex = s.subagentStack.indexOfFirst { it.session?.id == sessionID }
    if (session.parentID != null && s.catchUp == null && pushedIndex >= 0) {
        s = s.copy(nextMoveReason = null).poppingSubagents(toDepth = pushedIndex + 1, reason = moveReason)
        return Reduced(s, acknowledgeView(session, s.server))
    }
    val parentID = session.parentID
    if (parentID != null && s.topDetail?.session?.id != sessionID) {
        val parentIndex = s.subagentStack.indexOfFirst { it.session?.id == parentID }
        if (parentIndex >= 0 && s.catchUp == null) {
            s = s.copy(nextMoveReason = null).poppingSubagents(toDepth = parentIndex + 1, reason = moveReason)
                .pushingSubagent(session, reason = moveReason)
            return cancelBackgroundTranscriptReads(s, setOf(sessionID))
        }
        if (s.sessionDetail?.session?.id == parentID && s.catchUp == null) {
            s = s.copy(nextMoveReason = null).poppingSubagents(toDepth = 0, reason = moveReason)
                .pushingSubagent(session, reason = moveReason)
            return cancelBackgroundTranscriptReads(s, setOf(sessionID))
        }
        if (s.sessions.any { it.id == parentID }) {
            s = s.copy(pendingSubagentPush = session)
            // An authorized voice open navigates in this turn, never through a queued action.
            if (s.voiceNavigationToolID != null) return sessionTapped(s, parentID)
            return Reduced(s, Effect.send(Action.SessionTapped(parentID)))
        }
    }
    // Opening a session leaves the stack for the session it names.
    s = s.leavingCatchUp()
    // Sessions you open are the Share Sheet's suggestions.
    if (session.parentID == null) dependency(ShareInboxClient).donateSuggestion(s.server.persistenceID, sessionID, session.title)
    // Re-opening the session already on screen (a repeated row tap) must not rebuild it and lose the composer draft.
    if (s.sessionDetail?.session?.id == sessionID) {
        val viewed = openViewedSession(s, session, cachedItems = null, opensDetail = false)
        s = viewed.state.copy(nextMoveReason = null).promotingCatchUpDetail()
        // The drawer's row for the session on screen goes back to it, from under any page or subagent.
        if (s.sessionDetail?.browser?.isOpen == true) s = s.updatingDetail { it.copy(browser = it.browser.close()) }
        s = s.poppingSubagents(toDepth = 0, reason = moveReason).pushingPendingSubagent(moveReason)
        val pushedID = s.subagentStack.lastOrNull()?.session?.id ?: return Reduced(s, viewed.effect)
        val cancelled = cancelBackgroundTranscriptReads(s, setOf(sessionID, pushedID))
        return Reduced(cancelled.state, Effect.merge(cancelled.effect, viewed.effect))
    }
    LabMarks.mark("session_tap", sessionID = sessionID)
    PerfTimeline.switchStarted(sessionID)
    // The opened session reads itself; background reads step aside so it gets the connection.
    val cachedItems = s.cachedTranscriptItems(session)
    if (cachedItems == null) {
        val viewed = openViewedSession(s, session, cachedItems = null, opensDetail = false)
        s = viewed.state.copy(selectedTranscriptFetch = HomeFeature.SelectedTranscriptFetch(sessionID, viewed.state.detailGeneration))
        // An authorized voice open commits in the guarded Home action. Only the transcript load is
        // asynchronous; no delayed navigation action can arrive after the shared operation times
        // out or is refused.
        val voiceNavigation = s.voiceNavigationToolID != null
        if (voiceNavigation) {
            val reason = s.nextMoveReason
            s = s.openingDetail(session, externalTranscript = true)
            s = s.copy(selectedTranscriptFetch = s.selectedTranscriptFetch?.copy(transition = s.detailGeneration))
            s = s.pushingPendingSubagent(reason)
        }
        val cancelled = cancelBackgroundTranscriptReads(s, setOf(sessionID))
        val server = s.server
        val client = dependency(OpenCodeClient)
        val load = Effect.run<Action> { send ->
            LabMarks.mark("selected_transcript_effect_started", sessionID = sessionID)
            coroutineScope {
                val loaded = async { selectedTranscript(client, server, sessionID) }
                if (!voiceNavigation) send(Action.SessionNavigationReady(sessionID))
                val (result, pageCursor) = loaded.await()
                send(Action.SelectedTranscriptResponse(sessionID, result, pageCursor))
            }
        }.cancellable(HomeFeature.CancelID.SelectedTranscript, cancelInFlight = true)
        return Reduced(cancelled.state, Effect.merge(cancelled.effect, viewed.effect, load))
    }
    val viewed = openViewedSession(s, session, cachedItems = cachedItems)
    LabMarks.mark("session_navigation_started", sessionID = sessionID)
    return Reduced(viewed.state, Effect.merge(viewed.effect, Effect.cancel(HomeFeature.CancelID.SelectedTranscript)))
}

/**
 * Catch Up's card becomes the open session as it is: it reads on view again, keeping any subagent
 * the reader opened. What was typed on the card joins what waited for the session (a share, or its
 * persisted draft), and the family members' card drafts join theirs.
 */
internal fun State.promotingCatchUpDetail(): State {
    var detail = sessionDetail ?: return this
    val wasCatchUpCard = !detail.readsOnView
    detail = detail.copy(readsOnView = true)
    if (!wasCatchUpCard) return copy(sessionDetail = detail)
    val cardComposer = detail.sessionComposer
    val persistence = dependency(ComposerPersistence)
    val serverID = server.persistenceID
    var s = this
    for ((memberID, draft) in detail.catchUpFamilyDrafts) {
        if (draft.isEmpty || memberID == detail.session?.id) continue
        val waiting = s.sharedDrafts.removeValue(SharedDraftTarget(memberID))
        val persisted = if (waiting == null) persistence.loadDraft(serverID, memberID) else null
        val merged = when {
            waiting != null -> SessionFeature.State.draft(s.server, null, s.projects).absorb(waiting).absorb(SharedComposerDraft(draft))
            persisted != null -> SessionFeature.State.draft(s.server, null, s.projects).applyPersistedDraft(persisted).absorb(SharedComposerDraft(draft))
            else -> null
        }
        persistence.scheduleDraft(serverID, memberID, null, merged?.sessionComposer ?: draft)
    }
    detail = detail.copy(catchUpFamilyDrafts = emptyMap())
    val waiting = s.sharedDrafts.removeValue(SharedDraftTarget(detail.session?.id))
    val persisted = if (waiting == null) persistence.loadDraft(serverID, detail.session?.id) else null
    if (waiting != null) {
        detail = detail.setSessionComposer(DraftState()).absorb(waiting)
        if (!cardComposer.isEmpty) detail = detail.absorb(SharedComposerDraft(cardComposer))
    } else if (persisted != null) {
        detail = detail.setSessionComposer(DraftState()).applyPersistedDraft(persisted)
        if (!cardComposer.isEmpty) detail = detail.absorb(SharedComposerDraft(cardComposer))
    }
    s = s.copy(sessionDetail = detail)
    s.syncAbsorbedDraft(detail)
    return s
}

/**
 * Marks [session] as the last-viewed, locally read session and, when [opensDetail] is true and it
 * isn't already on screen, opens its detail, pushes any pending subagent, and cancels background
 * transcript reads, then acknowledges the view.
 */
internal fun HomeFeature.openViewedSession(
    state: State,
    session: OpenCodeSession,
    cachedItems: List<OpenCodeTranscriptItem>?,
    opensDetail: Boolean = true,
    reason: String = "you opened it",
): HomeReduced {
    var s = state.updatingLastViewedSessionIDs { it + (state.server.persistenceID to session.id) }.markingReadLocally(session)
    val openedNewDetail = opensDetail && s.sessionDetail?.session?.id != session.id
    var cancel: Effect<Action> = Effect.none()
    if (openedNewDetail) {
        val moveReason = s.nextMoveReason
        s = s.openingDetail(session, cachedItems = cachedItems, reason = reason).pushingPendingSubagent(moveReason)
        val cancelled = cancelBackgroundTranscriptReads(s, setOf(session.id))
        s = cancelled.state
        cancel = cancelled.effect
    }
    return Reduced(s, Effect.merge(cancel, acknowledgeView(session, s.server)))
}

internal fun hideSession(state: State, session: OpenCodeSession): Pair<State, HomeFeature.DeletionBackup>? {
    val sessionIndex = state.sessions.indexOfFirst { it.id == session.id }
    if (sessionIndex < 0) return null
    val wasOpen = state.sessionDetail?.familyRootID == session.id
    val openItems = if (wasOpen) state.sessionDetail?.cacheableItems.orEmpty() else emptyList()
    var backup = HomeFeature.DeletionBackup(
        session = session,
        sessionIndex = sessionIndex,
        wasActive = session.id in state.activeSessionIDs,
        neededAttention = session.id in state.attentionSessionIDs,
        runChangeDuringRefresh = state.runChangesDuringRefresh[session.id],
        openItems = openItems,
    )
    var s = state.copy(
        recentlyDeletedIDs = state.recentlyDeletedIDs + session.id,
        sessions = state.sessions.filterIndexed { index, _ -> index != sessionIndex },
        activeSessionIDs = state.activeSessionIDs - session.id,
        attentionSessionIDs = state.attentionSessionIDs - session.id,
        runChangesDuringRefresh = state.runChangesDuringRefresh - session.id,
    )
    if (wasOpen) {
        s = s.openingDraft(reason = "you deleted the session")
        backup = backup.copy(replacementDraftID = s.sessionDetail?.navigationID)
    }
    return s to backup
}

internal fun restoreSession(state: State, backup: HomeFeature.DeletionBackup): State {
    if (state.sessions.any { it.id == backup.session.id }) return state
    val sessions = state.sessions.toMutableList()
    sessions.add(minOf(backup.sessionIndex, sessions.size), backup.session)
    var s = state.copy(sessions = sessions)
    if (backup.wasActive) s = s.copy(activeSessionIDs = s.activeSessionIDs + backup.session.id)
    if (backup.neededAttention) s = s.copy(attentionSessionIDs = s.attentionSessionIDs + backup.session.id)
    backup.runChangeDuringRefresh?.let { s = s.copy(runChangesDuringRefresh = s.runChangesDuringRefresh + (backup.session.id to it)) }
    // Reopen only if the reader is still looking at the untouched draft the deletion left behind.
    val detail = s.sessionDetail
    if (backup.replacementDraftID != null && detail != null && detail.navigationID == backup.replacementDraftID &&
        detail.isDraft && !detail.hasPromptContent
    ) {
        s = s.openingDetail(backup.session, cachedItems = backup.openItems, reason = "you undid a delete")
    }
    return s
}

private fun canCacheBackgroundTranscript(state: State, sessionID: String): Boolean =
    state.topDetail?.session?.id != sessionID &&
        state.sessionDetail?.session?.id != sessionID &&
        state.selectedTranscriptFetch?.sessionID != sessionID &&
        state.sessions.any { it.id == sessionID } &&
        sessionID !in state.recentlyDeletedIDs

private fun insertingPinKey(state: State, key: String, before: String?): State = state.updatingPinnedSessionKeys { current ->
    val keys = current.filterNot { it == key }.toMutableList()
    val targetIndex = before?.let(keys::indexOf) ?: -1
    if (targetIndex >= 0) {
        keys.add(targetIndex, key)
    } else {
        // Server-scoped keys for other servers retain their relative order; this server's pin
        // moves to the end of its visible sequence.
        val last = keys.indexOfLast { it.startsWith("${state.server.persistenceID}|") }
        keys.add(if (last >= 0) last + 1 else keys.size, key)
    }
    keys
}

internal fun movePin(state: State, id: String, before: String?): HomeReduced {
    val key = state.sessionKey(id)
    if (key !in state.pinnedSessionKeys) return Reduced(state)
    val targetKey = before?.let(state::sessionKey)
    if (targetKey == key) return Reduced(state)
    val s = insertingPinKey(state, key, targetKey)
    dependency(HapticsClient).play(Haptic.selection)
    return Reduced(s)
}

internal fun willChangePins(state: State, id: String, pinnedIndex: Int?): Boolean {
    if (state.sessions.none { it.id == id }) return false
    val wasPinned = state.isPinned(id)
    if (pinnedIndex == null) return wasPinned
    if (!wasPinned) return true
    val pinned = state.pinnedSessions
    val otherCount = pinned.size - 1
    val clamped = minOf(maxOf(pinnedIndex, 0), maxOf(otherCount, 0))
    return pinned.indexOfFirst { it.id == id } != clamped
}

internal fun dropPin(state: State, id: String, pinnedIndex: Int?): State {
    if (!willChangePins(state, id, pinnedIndex)) return state
    val key = state.sessionKey(id)
    if (pinnedIndex == null) return state.updatingPinnedSessionKeys { keys -> keys.filterNot { it == key } }
    val otherIDs = state.pinnedSessions.map { it.id }.filter { it != id }
    val clamped = minOf(maxOf(pinnedIndex, 0), otherIDs.size)
    val targetKey = if (clamped < otherIDs.size) state.sessionKey(otherIDs[clamped]) else null
    return insertingPinKey(state, key, targetKey)
}

/**
 * The drawer offers a call: with Voice set up, and with something to call: the session or project
 * draft behind the drawer, or the session Start Voice Call's Last Viewed path would open. A draft
 * with no project and no session to fall back on offers none, rather than opening a project picker
 * behind the drawer.
 */
val HomeFeature.State.drawerOffersCall: Boolean
    get() {
        val detail = sessionDetail ?: return lastViewedOrNewestSession != null
        if (!detail.isVoiceAvailable) return false
        return detail.session != null || detail.draftProject != null || lastViewedOrNewestSession != null
    }

/** The drawer's call button: offered, and never while a call is live (the orb is the call then). */
val HomeFeature.State.drawerShowsCallButton: Boolean
    get() = drawerOffersCall && (sessionDetail?.voice?.phase?.isTerminal ?: true)

/** The session Start Voice Call's Last Viewed path opens: the last one viewed on this server, else the newest unarchived root session. */
val HomeFeature.State.lastViewedOrNewestSession: OpenCodeSession?
    get() = lastViewedSessionIDs[server.persistenceID]?.let { id -> sessions.firstOrNull { it.id == id } }
        ?: sessions.filter { it.parentID == null && !it.isArchived }.maxByOrNull { it.updatedAt }
