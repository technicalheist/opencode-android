package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.sharing.shared
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.hasActiveSessionDelegatorTurn
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceModelsCache
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.waitingSessionIDs
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeProject

/*
 * The event stream projected onto Home's list and forwarded to the screens that care. REST
 * snapshots stay authoritative: anything an event can't settle on its own asks for a fresh snapshot
 * (`refreshSnapshot`).
 */


internal fun HomeFeature.reduceServerEvent(state: State, event: OpenCodeServerEvent): HomeReduced {
    when (event) {
        is OpenCodeServerEvent.SessionViewChanged -> {
            // The event carries the watermark, so unread updates in place rather than refetching
            // the list. `OpenCodeClient` consumes the SSE echo of this device's own `POST /view`,
            // so an event here means another client read the session.
            var becameRead = true
            var s = state.updatingSession(event.sessionID) { session ->
                val next = maxOf(session.viewedMilliseconds ?: 0, event.idle)
                session.idleMilliseconds?.let { becameRead = event.idle >= it }
                if (session.viewedMilliseconds == next) session else session.copy(viewedMilliseconds = next)
            }
            val key = s.sessionKey(event.sessionID)
            if (key in s.manuallyUnreadSessionKeys) s = s.updatingManuallyUnreadSessionKeys { it - key }
            if (becameRead) s = s.clearingUnseenBaseline(event.sessionID)
            return Reduced(s)
        }

        OpenCodeServerEvent.Connected -> {
            // A new stream may have missed events; its snapshot is the truth.
            val supportByServer = shared(VoiceSharedKeys.serverVoiceSupportByServer)
            val serverID = state.server.persistenceID
            if (supportByServer.value[serverID] != null) supportByServer.update { it - serverID }
            var s = state.copy(runChangesDuringRefresh = emptyMap())
            val support = s.sessionDetail?.voice?.serverVoiceSupport
            if (support != null && support != ServerVoiceSupport.unknown) {
                s = s.updatingDetail { it.copy(voice = it.voice?.copy(serverVoiceSupport = ServerVoiceSupport.unknown)) }
            }
            // It may have been set up, updated or come back since: what Voice Provider knew of it,
            // and anything still being asked, no longer counts.
            VoiceModelsCache.invalidate(serverID)
            val voiceReconnect: Effect<Action> = if (s.sessionDetail?.voice?.hasActiveSessionDelegatorTurn == true) {
                Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.SessionDelegatorStreamReconnected)))
            } else {
                Effect.none()
            }
            val refreshed = familyAndListRefresh(s)
            return Reduced(refreshed.state, Effect.merge(refreshed.effect, voiceReconnect))
        }

        is OpenCodeServerEvent.SessionCreated -> {
            val parentID = event.parentID ?: return familyAndListRefresh(state)
            // A subagent: the root list is unchanged, and its parent never changes.
            var s = state
            if (s.activeSessionParents[event.sessionID] != parentID) {
                s = s.copy(activeSessionParents = s.activeSessionParents + (event.sessionID to parentID))
            }
            val parents = s.knownParents
            s = s.invalidatingSnapshotFamilyReads { snapshot -> snapshot.family.any { it.id == parentID } }
            val detail = s.sessionDetail
            val rereadDetail = detail != null && (
                detail.session == null ||
                    detail.familySessions.any { it.id == parentID } ||
                    joinsUnlisted(detail, event.sessionID, parents) ||
                    !s.isAttributed(parentID, parents)
                )
            if (rereadDetail && s.sessionDetail?.familyReadAt != null) s = s.updatingDetail { it.copy(familyReadAt = null) }
            s = s.updatingSubagents { screen ->
                if (screen.familySessions.any { it.id == parentID } || joinsUnlisted(screen, event.sessionID, parents)) {
                    screen.copy(familyReadAt = null)
                } else {
                    screen
                }
            }
            return Reduced(
                s,
                Effect.merge(
                    if (rereadDetail) sendToDetail(s, SessionFeature.Action.FamilyRefreshRequested) else Effect.none(),
                    forwardToSubagentFamilies(s, parentID, SessionFeature.Action.FamilyRefreshRequested),
                ),
            )
        }

        is OpenCodeServerEvent.ContentBatch -> {
            // A cached session's copy follows its live events, one read per pause.
            var s = state.notingSessionActivityStep(event.sessionID, event.events)
            val refresh = if (event.sessionID !in s.transcriptCache) {
                Effect.none()
            } else {
                val reduced = refreshCachedTranscript(s, event.sessionID, after = HomeFeature.cachedTranscriptRefreshDelay, force = true)
                s = reduced.state
                reduced.effect
            }
            val notify = Effect.merge(notifyVoiceContentBatch(s, event.sessionID, event.events), refresh)
            // Like the TUI's subagent footer, a new step means the session runs, even when its start
            // came before this stream connected.
            if (event.sessionID in s.activeSessionIDs || event.events.none { it is OpenCodeLiveContentEvent.StepStarted }) {
                return Reduced(s, notify)
            }
            val started = noteRunStarted(s, event.sessionID)
            return Reduced(started.state, Effect.merge(started.effect, notify))
        }

        is OpenCodeServerEvent.MessageInvalidated -> {
            val s = state.notingDirtyCachedTranscript(event.sessionID)
            return Reduced(s, forwardToSession(s, event.sessionID, SessionFeature.Action.MessageInvalidated(event.sessionID)))
        }

        is OpenCodeServerEvent.RevertChanged -> {
            val s = state.updatingSession(event.sessionID) { it.copy(revertMessageID = event.messageID) }
                .updatingSnapshot(event.sessionID) { it.copy(session = it.session.copy(revertMessageID = event.messageID)) }
                .notingDirtyCachedTranscript(event.sessionID)
            if (s.sessionDetail?.session?.id != event.sessionID) return Reduced(s)
            return Reduced(s, sendToDetail(s, SessionFeature.Action.RevertChanged(event.sessionID, event.messageID, event.committed)))
        }

        is OpenCodeServerEvent.InputQueueChanged -> {
            // A send of this phone's delivered while its session was off screen shows as sent when it opens.
            var s = state
            event.inputID?.let { inputID ->
                dependency(PromptOutbox).markDelivered(inputID)
                // Off screen, its pending row would otherwise come back on entry.
                s = removingSnapshotQueuedInput(s, event.sessionID, inputID)
            }
            // Queued work lives outside projected history, so only the open session cares; the list
            // already reorders on execution events.
            return Reduced(s, forwardToSession(s, event.sessionID, SessionFeature.Action.InputQueueChanged(event.inputID)))
        }

        is OpenCodeServerEvent.InputEnqueued ->
            return Reduced(state, forwardToSession(state, event.sessionID, SessionFeature.Action.InputEnqueued(event.input)))

        is OpenCodeServerEvent.InputCancelled -> {
            dependency(PromptOutbox).forgetAdmitted(event.inputID)
            val s = removingSnapshotQueuedInput(state, event.sessionID, event.inputID)
            return Reduced(s, forwardToSession(s, event.sessionID, SessionFeature.Action.InputCancelled(event.inputID)))
        }

        is OpenCodeServerEvent.AttentionChanged -> {
            // A request on a root we already list can light up now; the authoritative refetch settles
            // child-owned requests and removals.
            var s = state
            val asked = event.askedSessionID
            if (asked != null && s.sessions.any { it.id == asked }) s = s.copy(attentionSessionIDs = s.attentionSessionIDs + asked)
            return Reduced(
                s,
                Effect.merge(refreshAttention(s), forwardToScreens(s, SessionFeature.Action.RequestsChanged) { it.session != null }),
            )
        }

        is OpenCodeServerEvent.ShellStarted -> {
            val sessionID = event.shell.sessionID ?: return Reduced(state)
            return Reduced(state, forwardToSession(state, sessionID, SessionFeature.Action.ShellStarted(event.shell)))
        }

        is OpenCodeServerEvent.ShellEnded -> {
            val s = state.copy(
                sessionSnapshots = state.sessionSnapshots.mapValues { (_, snapshot) ->
                    if (snapshot.shells.any { it.id == event.shellID }) snapshot.copy(shells = snapshot.shells.filterNot { it.id == event.shellID })
                    else snapshot
                },
            )
            return Reduced(s, forwardToScreens(s, SessionFeature.Action.ShellEnded(event.shellID)))
        }

        is OpenCodeServerEvent.JobUpdated -> {
            val snapshots = state.sessionSnapshots.mapValues { (_, snapshot) -> snapshot.applying(event.job) }
            val s = if (snapshots == state.sessionSnapshots) state else state.copy(sessionSnapshots = snapshots)
            // Each screen keeps the jobs of its own tree.
            return Reduced(s, forwardToScreens(s, SessionFeature.Action.JobUpdated(event.job)) { it.session != null })
        }

        is OpenCodeServerEvent.ExecutionStarted -> {
            val sessionID = event.sessionID
            var s = state
            val parents = s.knownParents
            val unattributed = !s.isAttributed(sessionID, parents)
            if (unattributed) s = s.invalidatingSnapshotFamilyReads { snapshot -> snapshot.family.none { it.id == sessionID } }
            s = s.updatingDetail { detail ->
                val voice = detail.voice ?: return@updatingDetail detail
                detail.copy(voice = voice.copy(replyWatch = voice.replyWatch.setSessionActive(sessionID, true)))
            }
            val changed = SessionFeature.Action.FamilyRunStateChanged(sessionID, isRunning = true)
            val started = noteRunStarted(s, sessionID)
            s = started.state
            // Every open screen whose family it may belong to hears it, listed or not. One whose family
            // Home's parent links say it joins, but that hasn't listed it, reads again.
            val reread = { screen: SessionFeature.State -> joinsUnlisted(screen, sessionID, parents) }
            val receivesRunState = { screen: SessionFeature.State -> shouldForwardFamilyRunState(screen, sessionID, unattributed, parents) }
            val pushed = Effect.merge(
                forwardToSubagents(s, changed, receivesRunState),
                forwardToSubagents(s, SessionFeature.Action.FamilyRefreshRequested, reread),
            )
            val detail = s.sessionDetail
            if (detail?.session == null) return Reduced(s, Effect.merge(started.effect, pushed))
            return Reduced(
                s,
                Effect.merge(
                    started.effect,
                    pushed,
                    if (receivesRunState(detail)) sendToDetail(s, changed) else Effect.none(),
                    if (reread(detail)) sendToDetail(s, SessionFeature.Action.FamilyRefreshRequested) else Effect.none(),
                ),
            )
        }

        is OpenCodeServerEvent.ExecutionEnded -> {
            val sessionID = event.sessionID
            var s = state
            if (sessionID in s.activeSessionIDs) {
                s = s.copy(activeSessionIDs = s.activeSessionIDs - sessionID).persistingSnapshot(dependency(HomeSnapshotDate).now())
            }
            s = s.copy(runChangesDuringRefresh = s.runChangesDuringRefresh + (sessionID to false))
            val parents = s.knownParents
            val unattributed = !s.isAttributed(sessionID, parents)
            val receivesRunState = { screen: SessionFeature.State -> shouldForwardFamilyRunState(screen, sessionID, unattributed, parents) }
            val ended = SessionFeature.Action.FamilyRunStateChanged(sessionID, isRunning = false)
            val voiceEnded = notifyVoiceExecutionEnded(s, sessionID)
            s = voiceEnded.state
            val notify = Effect.merge(voiceEnded.effect, forwardToSubagents(s, ended, receivesRunState))
            // Refetch for updatedAt/ordering; if the finished run belongs to the open session, stop its
            // working indicator and pull the reply. Unlisted members too, as for a start.
            val refreshed = refreshSnapshot(s)
            s = refreshed.state
            val detail = s.sessionDetail
            if (detail?.session == null) return Reduced(s, Effect.merge(refreshed.effect, notify))
            return Reduced(
                s,
                Effect.merge(
                    refreshed.effect,
                    if (receivesRunState(detail)) sendToDetail(s, ended) else Effect.none(),
                    notify,
                ),
            )
        }

        is OpenCodeServerEvent.SessionRenamed -> {
            val sessionID = event.sessionID
            val title = event.title
            val s = state.copy(
                sessionSnapshots = state.sessionSnapshots.mapValues { (id, snapshot) ->
                    snapshot.copy(
                        session = if (id == sessionID) snapshot.session.copy(serverTitle = title) else snapshot.session,
                        family = snapshot.family.map { if (it.id == sessionID) it.copy(serverTitle = title) else it },
                    )
                },
            ).updatingSession(sessionID) { it.copy(serverTitle = title) }
                .let { it.copy(sessionPages = it.sessionPages.renamingResult(sessionID, title)) }
            val renamed = SessionFeature.Action.FamilySessionRenamed(sessionID, title)
            val pushed = forwardToSubagentFamilies(s, sessionID, renamed)
            val detail = s.sessionDetail
            if (detail == null || (detail.familySessions.none { it.id == sessionID } && detail.voice?.target?.sessionID != sessionID)) {
                return Reduced(s, pushed)
            }
            return Reduced(s, Effect.merge(sendToDetail(s, renamed), pushed))
        }

        is OpenCodeServerEvent.SessionDeleted -> {
            val sessionID = event.sessionID
            var s = state
            // A pushed subagent that went away goes back to its parent, before the purge, so popping can't save it again.
            val index = s.subagentStack.indexOfFirst { it.session?.id == sessionID }
            if (index >= 0) s = s.poppingSubagents(toDepth = index)
            val cancelSelected = if (s.selectedTranscriptFetch?.sessionID == sessionID) {
                s = s.copy(selectedTranscriptFetch = null)
                Effect.cancel(HomeFeature.CancelID.SelectedTranscript)
            } else {
                Effect.none()
            }
            s = s.purgingSession(sessionID)
            s = s.copy(recentlyDeletedIDs = s.recentlyDeletedIDs - sessionID)
            val voiceDeleted: Effect<Action> = if (s.sessionDetail?.voice?.usesSessionDelegator == true) {
                Effect.send(Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.SessionDelegatorSessionDeleted(sessionID))))
            } else {
                Effect.none()
            }
            val deleted = SessionFeature.Action.FamilySessionDeleted(sessionID)
            val pushed = forwardToSubagentFamilies(s, sessionID, deleted)
            // A subagent the family read hasn't listed may still own a docked request.
            val detail = s.sessionDetail
            if (detail == null || (detail.familySessions.none { it.id == sessionID } && sessionID !in detail.waitingSessionIDs)) {
                return Reduced(s, Effect.merge(cancelSelected, voiceDeleted, pushed))
            }
            if (detail.familyRootID == sessionID) {
                return Reduced(s.openingDraft(reason = VoiceFeature.State.MoveReason.sessionWentAway), Effect.merge(cancelSelected, voiceDeleted))
            }
            return Reduced(s, Effect.merge(cancelSelected, voiceDeleted, sendToDetail(s, deleted), pushed))
        }

        is OpenCodeServerEvent.SessionArchiveChanged -> {
            // Kept in place, so an open session stays open; the lists hide it. The refresh replaces
            // a snapshot read before the change, and brings back one unarchived elsewhere that fell
            // off the listed page.
            var s = state.updatingSession(event.sessionID) { session ->
                if (session.isArchived == event.isArchived) session
                else session.copy(archivedMilliseconds = if (event.isArchived) dependency(DateClient).now().toEpochMilli() else null)
            }
            val pages = s.sessionPages
            s = if (event.isArchived) {
                s.copy(sessionPages = pages.removingResult(event.sessionID).markingGone(event.sessionID))
            } else {
                s.copy(
                    sessionPages = pages.copy(
                        olderGoneIDs = pages.olderGoneIDs - event.sessionID,
                        searchGoneIDs = pages.searchGoneIDs - event.sessionID,
                    ),
                )
            }
            return refreshSnapshot(s)
        }

        is OpenCodeServerEvent.SessionMoved -> {
            // REST stays authoritative: the next snapshot and reads replace this.
            val moved = sessionMoved(state, event.sessionID, event.directory)
            val invalidated = forwardToSession(moved.state, event.sessionID, SessionFeature.Action.MessageInvalidated(event.sessionID))
            return Reduced(moved.state, Effect.merge(moved.effect, invalidated))
        }

        is OpenCodeServerEvent.ProjectUpdated -> {
            val project = event.project
            val s = state.copy(projects = state.projects.upserting(project))
                .updatingDetail { detail ->
                    detail.copy(
                        projects = detail.projects.upserting(project),
                        draftProject = if (detail.draftProject?.id == project.id) project else detail.draftProject,
                    )
                }
                .updatingSubagents { screen -> screen.copy(projects = screen.projects.upserting(project)) }
            return Reduced(s)
        }

        OpenCodeServerEvent.ModelsChanged -> return Reduced(state, forwardToScreens(state, SessionFeature.Action.ModelsChanged))

        OpenCodeServerEvent.AgentsChanged -> return Reduced(state, forwardToScreens(state, SessionFeature.Action.AgentsChanged))
    }
}

/**
 * `sessionID` now lives in `directory` (this phone's Move to, or the server's `session.moved`):
 * Home's copies follow, so attention reads stop asking the old folder, and every screen holding it
 * hears.
 */
internal fun HomeFeature.sessionMoved(state: State, sessionID: String, directory: String): HomeReduced {
    val s = state.updatingSession(sessionID) { if (it.directory == directory) it else it.copy(directory = directory) }
    var changed = false
    val snapshots = s.sessionSnapshots.mapValues { (id, snapshot) ->
        val movesSelf = id == sessionID && snapshot.session.directory != directory
        val movesMember = snapshot.family.any { it.id == sessionID && it.directory != directory }
        if (!movesSelf && !movesMember) return@mapValues snapshot
        changed = true
        snapshot.copy(
            session = if (movesSelf) snapshot.session.copy(directory = directory) else snapshot.session,
            family = if (movesMember) snapshot.family.map { if (it.id == sessionID) it.copy(directory = directory) else it } else snapshot.family,
        )
    }
    val next = if (changed) s.copy(sessionSnapshots = snapshots) else s
    return Reduced(
        next,
        forwardToScreens(next, SessionFeature.Action.SessionMoved(sessionID, directory)) { screen ->
            screen.familySessions.any { it.id == sessionID && it.directory != directory } ||
                (screen.voice?.target?.sessionID == sessionID && screen.voice?.target?.directory != directory)
        },
    )
}

/** Replaces the project with its ID, or puts a new one first. */
private fun List<OpenCodeProject>.upserting(project: OpenCodeProject): List<OpenCodeProject> {
    val index = indexOfFirst { it.id == project.id }
    if (index < 0) return listOf(project) + this
    if (this[index] == project) return this
    return toMutableList().also { it[index] = project }
}

/**
 * Home's record of a run starting: kept over the next snapshot, with the family link that keeps its
 * root lit after a switch away, and persisted. A newly spawned child may not be discovered yet:
 * refreshes metadata so its ancestors light up even when that parent isn't open.
 */
internal fun HomeFeature.noteRunStarted(state: State, sessionID: String): HomeReduced {
    val parents = state.knownParents
    var s = state.recordingActiveAncestry(setOf(sessionID), parents)
    val started = sessionID !in s.activeSessionIDs
    s = s.copy(activeSessionIDs = s.activeSessionIDs + sessionID, runChangesDuringRefresh = s.runChangesDuringRefresh + (sessionID to true))
    if (started) s = s.persistingSnapshot(dependency(HomeSnapshotDate).now())
    return if (s.isAttributed(sessionID, parents)) Reduced(s) else refreshSnapshot(s)
}

/** A new stream or a new root: every family may have changed. */
internal fun HomeFeature.familyAndListRefresh(state: State): HomeReduced {
    val s = state.invalidatingSnapshotFamilyReads()
        .updatingDetail { it.copy(familyReadAt = null) }
        .updatingSubagents { it.copy(familyReadAt = null) }
    val refreshed = refreshSnapshot(s)
    val refresh = Effect.merge(refreshed.effect, forwardToSubagents(refreshed.state, SessionFeature.Action.FamilyRefreshRequested))
    if (refreshed.state.sessionDetail == null) return Reduced(refreshed.state, refresh)
    return Reduced(refreshed.state, Effect.merge(refresh, sendToDetail(refreshed.state, SessionFeature.Action.FamilyRefreshRequested)))
}

private fun removingSnapshotQueuedInput(state: State, sessionID: String, inputID: String): State =
    state.updatingSnapshot(sessionID) { snapshot ->
        snapshot.copy(queued = snapshot.queued.filterNot { it.id == inputID }, positions = snapshot.positions - inputID)
    }
