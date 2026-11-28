package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.feature.drawer.HomeFeature.State.StatusSnapshot
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.CachedTranscriptSnapshots
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import java.time.Instant

// Home's cached snapshot, the inbox baseline, and the in-memory and on-disk transcript caches.

/** The cached Home snapshot for this server. */
val HomeFeature.State.cachedHomeSnapshot: CachedHomeSnapshot?
    get() = shared(ServerSharedKeys.cachedHomeSnapshots).value[server.persistenceID]

fun HomeFeature.State.persistingSnapshot(now: Instant): HomeFeature.State {
    val serverID = server.persistenceID
    val parents = SessionActivity.ancestry(activeSessionIDs, knownParents)
    val snapshots = shared(ServerSharedKeys.cachedHomeSnapshots)
    val snapshot = OpenCodeHomeSnapshot(
        projects = projects,
        sessions = sessions,
        activeSessionIDs = activeSessionIDs,
        activeSessionParents = parents.ifEmpty { null },
    )
    val existing = snapshots.value[serverID]
    val changed = existing?.snapshot != snapshot
    val age = existing?.capturedAt?.let { now.toEpochMilli() - it.toEpochMilli() }
    val recentlyStamped = age != null && age >= 0 && age < HomeFeature.snapshotStampInterval.inWholeMilliseconds
    // Running state, shown on a relaunch only while fresh, re-stamps every time.
    val stamps = isConnected && (changed || activeSessionIDs.isNotEmpty() || !recentlyStamped)
    val capturedAt = if (stamps) now else existing?.capturedAt
    if (!changed && existing?.capturedAt == capturedAt) return this
    snapshots.update { it + (serverID to CachedHomeSnapshot(serverID = serverID, snapshot = snapshot, capturedAt = capturedAt)) }
    return this
}

fun HomeFeature.State.initializingInbox(): HomeFeature.State {
    if (server.persistenceID in initializedInboxServerIDs) return this
    val prefix = server.persistenceID + "|"
    return updatingSessionReadDates { dates -> dates + sessions.associate { (prefix + it.id) to it.updatedAt } }
        .updatingInitializedInboxServerIDs { it + server.persistenceID }
}

private fun snapshotNow(): Instant = dependency(HomeSnapshotDate).now()

fun HomeFeature.State.restoringTranscriptCache(): HomeFeature.State {
    val missing = sessions.filter { it.id !in transcriptCache }
    if (missing.isEmpty()) return this
    val snapshots = ServerSharedKeys.cachedTranscriptSnapshots().value
    val restored = snapshots.validItems(server.persistenceID, missing, snapshotNow())
    if (restored.isEmpty()) return this
    var unopened = unopenedSeen
    val rememberedID = lastViewedSessionIDs[server.persistenceID]
    val entryUpdatedAt = HashMap<String, Instant>()
    for (entry in snapshots.entries) {
        if (entry.serverID == server.persistenceID) entryUpdatedAt.putIfAbsent(entry.sessionID, entry.updatedAt)
    }
    for (session in missing) {
        val items = restored[session.id]
        if (items.isNullOrEmpty() ||
            session.id in unopened ||
            session.id in sessionSnapshots ||
            sessionSeenWatermarks[sessionKey(session.id)] != null
        ) {
            continue
        }
        val serverViewed = unseenCutoffMilliseconds(session, wasUnread = false)
        val lastID = items.lastOrNull { !it.id.startsWith("info-") }?.id ?: items.last().id
        if (serverViewed != null && TranscriptTimeStamps.isBefore(serverViewed, lastID) == true) continue
        if (session.id == rememberedID || entryUpdatedAt[session.id]?.let { it < session.updatedAt } == true) {
            unopened = unopened + (session.id to items.map { it.id }.toSet())
        }
    }
    val merged = transcriptCache.toMutableMap()
    for ((id, items) in restored) merged.putIfAbsent(id, items)
    return copy(transcriptCache = merged, unopenedSeen = unopened).trimmingTranscriptCache()
}

/**
 * Whether each screen's transcript is covered: everything but the top screen, and that one under
 * its browser or Settings.
 */
private fun HomeFeature.State.coversTranscript(detail: SessionFeature.State, onTop: Boolean): Boolean =
    !onTop || isSettingsShown || detail.browser.isOpen

val HomeFeature.State.transcriptCoverIsStale: Boolean
    get() {
        val detail = sessionDetail
        if (detail != null && detail.isTranscriptCovered != coversTranscript(detail, onTop = subagentStack.isEmpty())) return true
        val top = subagentStack.lastOrNull()?.navigationID
        return subagentStack.any { it.isTranscriptCovered != coversTranscript(it, onTop = it.navigationID == top) }
    }

fun HomeFeature.State.syncingTranscriptCover(): HomeFeature.State {
    val detail = sessionDetail?.let { it.setTranscriptCovered(coversTranscript(it, onTop = subagentStack.isEmpty())) }
    val top = subagentStack.lastOrNull()?.navigationID
    val stack = subagentStack.map { it.setTranscriptCovered(coversTranscript(it, onTop = it.navigationID == top)) }
    return copy(sessionDetail = detail, subagentStack = stack)
}

private fun HomeFeature.State.pausesPolling(detail: SessionFeature.State, onTop: Boolean): Boolean {
    if (detail.session == null || !coversTranscript(detail, onTop)) return false
    if (onTop && !isSettingsShown && detail.browserAskIsLive) return false
    val ownCall = detail.voice
    if (ownCall != null && !ownCall.phase.isTerminal) return false
    val call = sessionDetail?.voice
    if (call == null || call.phase.isTerminal) return true
    return call.target.sessionID != detail.session?.id
}

val HomeFeature.State.pollingPauseIsStale: Boolean
    get() {
        val detail = sessionDetail
        if (detail != null && detail.isPollingPaused != pausesPolling(detail, onTop = subagentStack.isEmpty())) return true
        val top = subagentStack.lastOrNull()?.navigationID
        return subagentStack.any { it.isPollingPaused != pausesPolling(it, onTop = it.navigationID == top) }
    }

fun HomeFeature.State.syncingPollingPause(): Reduced<HomeFeature.State, HomeFeature.Action> {
    val effects = mutableListOf<Effect<HomeFeature.Action>>()
    var detail = sessionDetail
    if (detail != null) {
        val paused = pausesPolling(detail, onTop = subagentStack.isEmpty())
        if (detail.isPollingPaused != paused) {
            detail = detail.copy(isPollingPaused = paused)
            effects.add(Effect.send(HomeFeature.Action.detail(SessionFeature.Action.PollingPauseChanged(paused))))
        }
    }
    val top = subagentStack.lastOrNull()?.navigationID
    val stack = mutableListOf<SessionFeature.State>()
    for (child in subagentStack) {
        val paused = pausesPolling(child, onTop = child.navigationID == top)
        if (child.isPollingPaused == paused) {
            stack.add(child)
            continue
        }
        stack.add(child.copy(isPollingPaused = paused))
        effects.add(Effect.send(HomeFeature.Action.SubagentStack(child.navigationID, SessionFeature.Action.PollingPauseChanged(paused))))
    }
    return Reduced(copy(sessionDetail = detail, subagentStack = stack), Effect.merge(effects))
}

/**
 * A background read is about to replace the copy a session not opened this run would open with:
 * that copy stays what it's compared with.
 */
fun HomeFeature.State.notingUnopenedCopy(sessionID: String): HomeFeature.State {
    if (sessionID in sessionSnapshots || sessionID in unopenedSeen) return this
    val session = sessions.firstOrNull { it.id == sessionID } ?: return this
    val copy = cachedTranscriptItems(session)
    if (copy.isNullOrEmpty()) return this
    return copy(unopenedSeen = unopenedSeen + (sessionID to copy.map { it.id }.toSet()))
}

fun HomeFeature.State.cachedTranscriptItems(session: OpenCodeSession): List<OpenCodeTranscriptItem>? =
    transcriptCache[session.id]
        ?: ServerSharedKeys.cachedTranscriptSnapshots().value.validItems(server.persistenceID, session, snapshotNow())

fun HomeFeature.State.transcriptCachePriority(session: OpenCodeSession, status: StatusSnapshot): CachedTranscriptSnapshots.Priority =
    CachedTranscriptSnapshots.Priority(
        needsInput = session.id in status.attention,
        isWorking = session.id in status.busy,
        isPinned = isPinned(session.id),
        isUnread = status.isUnread(session) && !status.isMuted(session.id),
    )

fun HomeFeature.State.touchingCachedTranscript(session: OpenCodeSession): HomeFeature.State {
    val now = snapshotNow()
    if (!now.isAfter(Instant.EPOCH)) return this
    val priority = transcriptCachePriority(session, statusSnapshot())
    val cache = ServerSharedKeys.cachedTranscriptSnapshots()
    val updated = cache.value.touch(server.persistenceID, session.id, priority, now)
    if (updated != cache.value) cache.set(updated)
    return this
}

fun HomeFeature.State.publishingTranscript(sessionID: String, items: List<OpenCodeTranscriptItem>): HomeFeature.State {
    val s = copy(dirtyCachedTranscriptIDs = dirtyCachedTranscriptIDs - sessionID)
    val bounded = items.takeLast(SessionFeature.initialFetchLimit)
    if (s.transcriptCache[sessionID] == bounded) return s
    return s.copy(transcriptCache = s.transcriptCache + (sessionID to bounded)).trimmingTranscriptCache()
}

fun HomeFeature.State.notingDirtyCachedTranscript(sessionID: String): HomeFeature.State {
    if (sessionID == topDetail?.session?.id ||
        sessionID == sessionDetail?.session?.id ||
        sessionID !in transcriptCache ||
        sessions.none { it.id == sessionID } ||
        sessionID in recentlyDeletedIDs
    ) {
        return this
    }
    if (sessionID in dirtyCachedTranscriptIDs) return this
    return copy(dirtyCachedTranscriptIDs = (dirtyCachedTranscriptIDs + sessionID).takeLast(HomeFeature.dirtyCachedTranscriptLimit))
}

fun HomeFeature.State.trimmingTranscriptCache(): HomeFeature.State {
    if (transcriptCache.size <= HomeFeature.transcriptMemoryLimit) return this
    val hot = sessionsByPriority()
    val priority = listOfNotNull(sessionDetail?.session?.id) +
        catchUp?.queue.orEmpty() +
        hot.take(HomeFeature.prefetchCount).map { it.id } +
        sessionSnapshotOrder.reversed() +
        hot.map { it.id }
    val retained = HashSet<String>(HomeFeature.transcriptMemoryLimit)
    for (id in priority) {
        if (id !in transcriptCache) continue
        retained += id
        if (retained.size == HomeFeature.transcriptMemoryLimit) break
    }
    return copy(
        transcriptCache = transcriptCache.filterKeys { it in retained },
        unopenedSeen = unopenedSeen.filterKeys { it in retained },
    )
}

fun HomeFeature.State.persistingTranscriptCache(including: String): HomeFeature.State {
    val now = snapshotNow()
    val validNow = if (now.isAfter(Instant.EPOCH)) now else null
    val status = statusSnapshot()
    val openID = sessionDetail?.session?.id
    val validUpdatedAt = HashMap<String, Instant>()
    val priorities = HashMap<String, CachedTranscriptSnapshots.Priority>()
    for (session in sessions) {
        validUpdatedAt[session.id] = session.updatedAt
        priorities[session.id] = transcriptCachePriority(session, status)
    }
    val session = sessions.firstOrNull { it.id == including }
    val items = transcriptCache[including]
    val cache = ServerSharedKeys.cachedTranscriptSnapshots()
    val updates = if (session != null && items != null) {
        // An unchanged read keeps the access time opening the session stamped.
        val stamps = session.id == openID && cache.value.entries.firstOrNull {
            it.serverID == server.persistenceID && it.sessionID == session.id
        }?.items != items
        listOf(
            CachedTranscriptSnapshots.Entry(
                serverID = server.persistenceID,
                sessionID = session.id,
                updatedAt = session.updatedAt,
                items = items,
                lastAccessedAt = if (stamps) validNow else null,
                priority = priorities[session.id]?.nonEmpty,
            ),
        )
    } else {
        emptyList()
    }
    val updated = cache.value.upsertEntries(server.persistenceID, updates, validUpdatedAt, priorities, validNow)
    if (updated != cache.value) cache.set(updated)
    return this
}
