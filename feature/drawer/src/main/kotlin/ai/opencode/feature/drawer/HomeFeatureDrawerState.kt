package ai.opencode.feature.drawer

import ai.opencode.feature.session.*
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKey
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.UnsentDraft
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.DrawerBadge
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.pruneRequests
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.model.learningFirstPrompt
import ai.opencode.feature.drawer.HomeFeature.State.Indicator
import ai.opencode.feature.drawer.HomeFeature.State.StatusSnapshot
import ai.opencode.feature.drawer.HomeFeature.State.Viewing
import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.feature.transcript.SessionSeenWatermarks
import ai.opencode.feature.transcript.sessionSeenWatermarks
import ai.opencode.server.model.OpenCodeProject
import java.time.Instant

/*
 * The drawer's derived lists, read state, pins and mutes, Catch Up's queue upkeep, and what a
 * deleted session leaves behind. These return the next state. Shared keys are written through (only
 * when changed) along with their mirrors in [HomeFeature.State].
 */

// region Shared-key write-through

/**
 * `$key.withLock { … }`: transforms the live shared value (another store may have written it since
 * this state's mirror was read), then mirrors the result into [HomeFeature.State].
 */
private fun <T> HomeFeature.State.writingThrough(
    key: SharedKey<T>,
    mirror: T,
    transform: (T) -> T,
    mirroring: HomeFeature.State.(T) -> HomeFeature.State,
): HomeFeature.State {
    val reference = shared(key)
    reference.update(transform)
    val live = reference.value
    return if (live == mirror) this else mirroring(live)
}

/** `$pinnedSessionKeys.withLock { … }`. */
internal fun HomeFeature.State.updatingPinnedSessionKeys(transform: (List<String>) -> List<String>): HomeFeature.State =
    writingThrough(SharedKeys.pinnedSessionKeys, pinnedSessionKeys, transform) { copy(pinnedSessionKeys = it) }

/** `$lastViewedSessionIDs.withLock { … }`. */
internal fun HomeFeature.State.updatingLastViewedSessionIDs(transform: (Map<String, String>) -> Map<String, String>): HomeFeature.State =
    if (isWindowLocal) copy(lastViewedSessionIDs = transform(lastViewedSessionIDs))
    else writingThrough(SharedKeys.lastViewedSessionIDs, lastViewedSessionIDs, transform) { copy(lastViewedSessionIDs = it) }

/** `$sessionReadDates.withLock { … }`. */
internal fun HomeFeature.State.updatingSessionReadDates(transform: (Map<String, Instant>) -> Map<String, Instant>): HomeFeature.State =
    writingThrough(SharedKeys.sessionReadDates, sessionReadDates, transform) { copy(sessionReadDates = it) }

/** `$manuallyUnreadSessionKeys.withLock { … }`. */
internal fun HomeFeature.State.updatingManuallyUnreadSessionKeys(transform: (Set<String>) -> Set<String>): HomeFeature.State =
    writingThrough(SharedKeys.manuallyUnreadSessionKeys, manuallyUnreadSessionKeys, transform) { copy(manuallyUnreadSessionKeys = it) }

/** `$mutedSessionKeys.withLock { … }`. */
/** `$sessionSeenWatermarks.withLock { … }`. */
internal fun HomeFeature.State.updatingSessionSeenWatermarks(transform: (SessionSeenWatermarks) -> SessionSeenWatermarks): HomeFeature.State =
    writingThrough(SharedKeys.sessionSeenWatermarks, sessionSeenWatermarks, transform) { copy(sessionSeenWatermarks = it) }

internal fun HomeFeature.State.updatingMutedSessionKeys(transform: (Set<String>) -> Set<String>): HomeFeature.State =
    writingThrough(SharedKeys.mutedSessionKeys, mutedSessionKeys, transform) { copy(mutedSessionKeys = it) }

/** `$hiddenSessionKeys.withLock { … }`. */
internal fun HomeFeature.State.updatingHiddenSessionKeys(transform: (Set<String>) -> Set<String>): HomeFeature.State =
    writingThrough(SharedKeys.hiddenSessionKeys, hiddenSessionKeys, transform) { copy(hiddenSessionKeys = it) }

/** `$initializedInboxServerIDs.withLock { … }`. */
internal fun HomeFeature.State.updatingInitializedInboxServerIDs(transform: (Set<String>) -> Set<String>): HomeFeature.State =
    writingThrough(SharedKeys.initializedInboxServerIDs, initializedInboxServerIDs, transform) { copy(initializedInboxServerIDs = it) }

/** `$isSidebarShown.withLock { … }`. */
internal fun HomeFeature.State.settingSidebarShown(shown: Boolean): HomeFeature.State =
    if (isWindowLocal) copy(isSidebarShown = shown)
    else writingThrough(SharedKeys.sidebarShown, isSidebarShown, { shown }) { copy(isSidebarShown = it) }

/** Replaces one listed session in place. */
internal inline fun HomeFeature.State.updatingSession(sessionID: String, transform: (OpenCodeSession) -> OpenCodeSession): HomeFeature.State {
    val index = sessions.indexOfFirst { it.id == sessionID }
    if (index < 0) return this
    val next = transform(sessions[index])
    if (next == sessions[index]) return this
    return copy(sessions = sessions.toMutableList().also { it[index] = next })
}

/**
 * Home's list and remembered snapshot learn the first prompt a session's screen just learned
 * ([learningFirstPrompt]), so its row, Catch Up card and system surfaces read the same.
 */
internal fun HomeFeature.State.learningFirstPrompt(session: OpenCodeSession?): HomeFeature.State {
    val preview = session?.firstPrompt ?: return this
    return updatingSession(session.id) { it.learningFirstPrompt(preview) }
        .updatingSnapshot(session.id) { it.copy(session = it.session.learningFirstPrompt(preview)) }
}

/** Replaces one remembered snapshot in place (`sessionSnapshots[id]?.… = …`). */
internal inline fun HomeFeature.State.updatingSnapshot(
    sessionID: String,
    transform: (SessionFeature.SwitchSnapshot) -> SessionFeature.SwitchSnapshot,
): HomeFeature.State {
    val snapshot = sessionSnapshots[sessionID] ?: return this
    val next = transform(snapshot)
    if (next == snapshot) return this
    return copy(sessionSnapshots = sessionSnapshots + (sessionID to next))
}

/** `sessionDetail?.… = …`. */
internal inline fun HomeFeature.State.updatingDetail(transform: (SessionFeature.State) -> SessionFeature.State): HomeFeature.State {
    val detail = sessionDetail ?: return this
    val next = transform(detail)
    return if (next == detail) this else copy(sessionDetail = next)
}

/** `subagentStack[id: id]?.… = …` for each pushed screen. */
internal inline fun HomeFeature.State.updatingSubagents(transform: (SessionFeature.State) -> SessionFeature.State): HomeFeature.State {
    if (subagentStack.isEmpty()) return this
    val next = subagentStack.map(transform)
    return if (next == subagentStack) this else copy(subagentStack = next)
}

/** `subagentStack[id: id]?.… = …`. */
internal inline fun HomeFeature.State.updatingSubagent(id: Int, transform: (SessionFeature.State) -> SessionFeature.State): HomeFeature.State {
    val index = subagentStack.indexOfFirst { it.navigationID == id }
    if (index < 0) return this
    val next = transform(subagentStack[index])
    if (next == subagentStack[index]) return this
    return copy(subagentStack = subagentStack.toMutableList().also { it[index] = next })
}

// endregion

/** Sessions that need you or are running first, then newest. */
fun HomeFeature.State.sessionsByPriority(): List<OpenCodeSession> {
    val busy = busySessionIDs
    fun isHot(session: OpenCodeSession) = session.id in attentionSessionIDs || session.id in busy
    return sessions.sortedWith { a, b ->
        val aHot = isHot(a)
        val bHot = isHot(b)
        if (aHot != bHot) (if (aHot) -1 else 1) else b.updatedAt.compareTo(a.updatedAt)
    }
}

/** Where the first computer's ready session starts: its most recently used project, never the home folder or root sessions fall back to. */
val HomeFeature.State.firstSessionProject: OpenCodeProject?
    get() = projectsByRecency.firstOrNull { !it.isHomeFolder }

/**
 * Root sessions only: subagents' sessions are kept for their family's activity but open from their
 * parent, as on desktop. Archived sessions stay out of every list. Searching adds the server's
 * matches from every session; results of an earlier query show only while they still match. Hidden
 * sessions show only while searching.
 */
val HomeFeature.State.visibleSessions: List<OpenCodeSession>
    get() {
        if (search.isEmpty()) {
            val hidden = hiddenSessionIDs
            return sessions.filter { it.parentID == null && !it.isArchived && it.id !in hidden }
        }
        val local = sessions.filter { it.parentID == null && !it.isArchived && it.title.contains(search, ignoreCase = true) }
        val found = sessionPages.search
        if (found == null || found.results.isEmpty()) return local
        val current = found.query == search.trim()
        val known = HashSet<String>()
        for (session in sessions) known += session.id
        val extra = mutableListOf<OpenCodeSession>()
        for (result in found.results) {
            // A server that ignores the parentID filter still only offers roots here.
            if (result.id in known || result.parentID != null || result.isArchived || result.id in recentlyDeletedIDs) continue
            if (!current && !result.title.contains(search, ignoreCase = true)) continue
            extra += result
        }
        return local + extra
    }

/** This server's hidden sessions by ID, worked out once for a loop over the sessions rather than a key built per session. */
val HomeFeature.State.hiddenSessionIDs: Set<String>
    get() {
        if (hiddenSessionKeys.isEmpty()) return emptySet()
        val prefix = server.persistenceID + "|"
        val ids = HashSet<String>()
        for (key in hiddenSessionKeys) {
            if (key.startsWith(prefix)) ids += key.removePrefix(prefix)
        }
        return ids
    }

val HomeFeature.State.pinnedSessions: List<OpenCodeSession> get() = drawerSessions.pinned

val HomeFeature.State.recentSessions: List<OpenCodeSession> get() = drawerSessions.recents

/** The drawer's two lists. */
data class DrawerSessions(val pinned: List<OpenCodeSession>, val recents: List<OpenCodeSession>)

/**
 * Pinned (in pin order) and Recents (newest first), from one pass over `visibleSessions`: the
 * drawer's list builds both on every change.
 */
val HomeFeature.State.drawerSessions: DrawerSessions
    get() {
        // Sessions being archived leave at once, as the Inbox Sidebar's do.
        val archiving = inboxSidebarStatus.archivedIDs
        val visible = if (archiving.isEmpty()) visibleSessions else visibleSessions.filter { it.id !in archiving }
        val prefix = server.persistenceID + "|"
        // This server's pins, by session ID, in order.
        val pinnedIDs = mutableListOf<String>()
        for (key in pinnedSessionKeys) {
            if (key.startsWith(prefix)) pinnedIDs += key.removePrefix(prefix)
        }
        if (pinnedIDs.isEmpty()) return DrawerSessions(emptyList(), visible.sortedByDescending { it.updatedAt })
        val pinnedSet = pinnedIDs.toHashSet()
        val byID = HashMap<String, OpenCodeSession>()
        val recents = mutableListOf<OpenCodeSession>()
        for (session in visible) {
            if (session.id in pinnedSet) {
                if (session.id !in byID) byID[session.id] = session
            } else {
                recents += session
            }
        }
        val pinned = mutableListOf<OpenCodeSession>()
        for (id in pinnedIDs) {
            val session = byID[id] ?: continue
            pinned += session
        }
        return DrawerSessions(pinned, recents.sortedByDescending { it.updatedAt })
    }

/** Waiting sessions first, then newest. */
val HomeFeature.State.catchUpSessions: List<OpenCodeSession> get() = catchUpSessions(statusSnapshot())

private fun HomeFeature.State.catchUpSessions(status: StatusSnapshot): List<OpenCodeSession> {
    val attention = status.attention
    val archiving = inboxSidebarStatus.archivedIDs
    return sessions.filter { session ->
        session.archivedMilliseconds == null && session.id !in archiving && status.needsCatchUp(session)
    }.sortedWith { a, b ->
        val aWaiting = a.id in attention
        val bWaiting = b.id in attention
        if (aWaiting != bWaiting) (if (aWaiting) -1 else 1) else b.updatedAt.compareTo(a.updatedAt)
    }
}

/** The unsent session as a list row: named by its first message until the server names it. */
val UnsentDraft.placeholderSession: OpenCodeSession
    get() = OpenCodeSession(
        id = sessionID,
        serverTitle = SessionTitle.placeholder,
        projectID = "",
        directory = directory,
        updatedAt = updatedAt,
        firstPrompt = title,
    )

/** Back to the session Catch Up was opened from, with the composer it set aside (or a draft). */
fun HomeFeature.State.returningFromCatchUp(): HomeFeature.State {
    val (exiting, catchUpState) = catchUpState.consumeExitingStack()
    var s = copy(catchUpState = catchUpState)
    val stack = exiting ?: s.catchUp ?: return s
    s = s.withCatchUp(null)
    s = s.parkingDraft(stack.returnComposer, stack.composerTargetSessionID)
    val session = stack.returnSessionID?.let { id -> s.sessions.firstOrNull { it.id == id } }
    s = if (session != null) {
        s.openingDetail(session, cachedItems = s.cachedTranscriptItems(session), reason = "you left Catch Up")
    } else {
        s.openingDraft(reason = "you left Catch Up")
    }
    return s.absorbingSharedDrafts()
}

/** Leaving Catch Up any way but Exit: the composer it set aside goes back to its own session. */
fun HomeFeature.State.leavingCatchUp(): HomeFeature.State {
    val stack = catchUp ?: return this
    return withCatchUp(null).parkingDraft(
        stack.returnComposer,
        stack.composerTargetSessionID,
    )
}

/**
 * Opens the stack's top card without marking it read: reading is the reader's swipe, not the card
 * appearing. Returns whether the session changed.
 */
fun HomeFeature.State.openingCatchUpCard(): Pair<HomeFeature.State, Boolean> {
    val id = catchUp?.queue?.firstOrNull() ?: return this to false
    if (sessionDetail?.familyRootID == id && sessionDetail.readsOnView == false) return this to false
    val session = sessions.firstOrNull { it.id == id } ?: return this to false
    val changedSession = sessionDetail?.session?.id != id
    val (next, detail) = makeDetail(session, cachedItems = cachedTranscriptItems(session), reason = "you opened it from Catch Up")
    return next.copy(sessionDetail = detail.copy(readsOnView = false, catchUpOpeningSeen = detail.newMessagesBaseline)) to changedSession
}

/** Follows reads, answers, and new activity from any device. The open card stays until the reader moves it along. */
fun HomeFeature.State.reconcilingCatchUp(): HomeFeature.State {
    val stack = catchUp ?: return this
    val status = statusSnapshot()
    val attention = status.attention
    val byID = LinkedHashMap<String, OpenCodeSession>()
    for (session in sessions) byID.putIfAbsent(session.id, session)
    val synced = stack.syncQueue(
        sessionsByID = byID,
        candidates = catchUpSessions(status),
        needsCatchUp = { status.needsCatchUp(it) },
        needsAttention = { it in attention },
    )
    if (synced == stack) return this
    return withCatchUp(synced).openingCatchUpCard().first
}

fun HomeFeature.State.markingUnreadLocally(session: OpenCodeSession): HomeFeature.State {
    var s = withMarkAllReceipts(markAllReceipts - session.id)
        .copy(
            locallyViewedMilliseconds = locallyViewedMilliseconds - session.id,
            pendingOpenReadState = pendingOpenReadState - session.id,
            unopenedSeen = unopenedSeen - session.id,
        )
    s.sessionSnapshots[session.id]?.let { snapshot ->
        s = s.copy(sessionSnapshots = s.sessionSnapshots + (session.id to snapshot.copy(seenItemIDs = emptySet(), hasUnseenItems = true)))
    }
    s = s.clearingSeenWatermark(session.id)
    val key = sessionKey(session.id)
    s = s.updatingSessionReadDates { it + (key to session.updatedAt.minusSeconds(1)) }
    if (session.idleMilliseconds != null) s = s.updatingManuallyUnreadSessionKeys { it + key }
    return s
}

fun HomeFeature.State.readBackup(session: OpenCodeSession, idle: Long): CatchUpFeature.ReadBackup {
    val key = sessionKey(session.id)
    return CatchUpFeature.ReadBackup(
        idle = idle,
        viewedMilliseconds = sessions.firstOrNull { it.id == session.id }?.viewedMilliseconds,
        locallyViewedMilliseconds = locallyViewedMilliseconds[session.id],
        readDate = sessionReadDates[key],
        wasManuallyUnread = key in manuallyUnreadSessionKeys,
    )
}

/** Undoes `markReadLocally` for a receipt the server refused. */
fun HomeFeature.State.restoringRead(sessionID: String, backup: CatchUpFeature.ReadBackup): HomeFeature.State {
    val key = sessionKey(sessionID)
    var s = updatingSession(sessionID) {
        if (it.viewedMilliseconds == maxOf(backup.viewedMilliseconds ?: 0, backup.idle)) {
            it.copy(viewedMilliseconds = backup.viewedMilliseconds)
        } else {
            it
        }
    }
    s = s.copy(
        locallyViewedMilliseconds = backup.locallyViewedMilliseconds?.let { s.locallyViewedMilliseconds + (sessionID to it) }
            ?: (s.locallyViewedMilliseconds - sessionID),
    )
    s = s.updatingSessionReadDates { dates -> backup.readDate?.let { dates + (key to it) } ?: (dates - key) }
    if (backup.wasManuallyUnread) s = s.updatingManuallyUnreadSessionKeys { it + key }
    return s
}

fun HomeFeature.State.notingViewAcknowledged(sessionID: String, idle: Long): HomeFeature.State {
    var s = updatingSession(sessionID) { it.copy(viewedMilliseconds = maxOf(it.viewedMilliseconds ?: 0, idle)) }
    val key = sessionKey(sessionID)
    if (key in s.manuallyUnreadSessionKeys) s = s.updatingManuallyUnreadSessionKeys { it - key }
    return s
}

/** Whether the session had something unread before this phone read it. */
fun HomeFeature.State.hasUnreadWatermark(session: OpenCodeSession): Boolean {
    val key = sessionKey(session.id)
    if (key in manuallyUnreadSessionKeys) return true
    session.idleMilliseconds?.let { idle -> return idle > (session.viewedMilliseconds ?: -1) }
    sessionReadDates[key]?.let { return it < session.updatedAt }
    return false
}

/** The server read unseen rows count from, if it marks any. */
fun HomeFeature.State.unseenCutoffMilliseconds(session: OpenCodeSession, wasUnread: Boolean): Long? {
    if (sessionKey(session.id) in manuallyUnreadSessionKeys) return null
    val listed = sessions.firstOrNull { it.id == session.id }
    val viewed = listed?.viewedMilliseconds ?: session.viewedMilliseconds ?: return null
    if (viewed <= 0) return null
    val idle = listed?.idleMilliseconds ?: session.idleMilliseconds
    if (idle != null && !(viewed < idle || !wasUnread)) return null
    return viewed
}

/** What a session opens with for its New divider. */
data class OpeningParameters(val seen: Set<String>?, val viewedMilliseconds: Long?, val unread: Boolean)

fun HomeFeature.State.openingParameters(
    session: OpenCodeSession,
    pending: HomeFeature.State.PendingOpenReadState? = null,
): OpeningParameters {
    val snapshot = sessionSnapshots[session.id]
    val serverViewed = if (pending != null) pending.viewedMilliseconds else unseenCutoffMilliseconds(session, wasUnread = false)
    val snapshotSeen = snapshot?.seenItemIDs
    val unopened = unopenedSeen[session.id]
    val persisted = sessionSeenWatermarks[sessionKey(session.id)]
    val persistedIsStale = persisted != null && serverViewed != null && persisted.isOlderThanServerViewed(serverViewed)
    val activePersisted = if (persistedIsStale) null else persisted
    val persistedHasUnseen = activePersisted?.acknowledgedViewedMilliseconds != null
    val wasUnread = (pending?.wasUnread ?: hasUnreadWatermark(session)) ||
        snapshot?.hasUnseenItems == true ||
        persistedHasUnseen
    val persistedSeen = activePersisted?.itemID?.let { setOf(it) }
    val viewedMilliseconds = when {
        !wasUnread -> null
        persistedHasUnseen -> activePersisted?.viewedMilliseconds
        else -> activePersisted?.viewedMilliseconds?.let { maxOf(serverViewed ?: 0, it) } ?: serverViewed
    }
    val seen = when {
        snapshotSeen != null && (snapshotSeen.isNotEmpty() || snapshot.hasUnseenItems) -> snapshotSeen
        !unopened.isNullOrEmpty() -> unopened
        !persistedSeen.isNullOrEmpty() -> persistedSeen
        else -> null
    }
    return OpeningParameters(seen = seen, viewedMilliseconds = viewedMilliseconds, unread = wasUnread)
}

fun HomeFeature.State.applyingOpening(
    session: OpenCodeSession,
    pending: HomeFeature.State.PendingOpenReadState? = null,
    detail: SessionFeature.State,
): SessionFeature.State {
    val opening = openingParameters(session, pending)
    return detail.noteOpening(seen = opening.seen, viewedMilliseconds = opening.viewedMilliseconds, unread = opening.unread)
}

fun HomeFeature.State.settingSeenWatermark(watermark: SessionSeenWatermark, sessionID: String): HomeFeature.State {
    val key = sessionKey(sessionID)
    if (sessionSeenWatermarks[key] == watermark && sessionSeenWatermarks.order.lastOrNull() == key) return this
    return updatingSessionSeenWatermarks { it.setting(watermark, key) }
}

fun HomeFeature.State.clearingSeenWatermark(sessionID: String): HomeFeature.State {
    val key = sessionKey(sessionID)
    if (sessionSeenWatermarks[key] == null) return this
    return updatingSessionSeenWatermarks { it.removing(key) }
}

/** The last row's ID, skipping the info rows the transcript adds. */
private fun List<OpenCodeTranscriptItem>.lastSeenRowID(seen: (String) -> Boolean = { true }): String? =
    lastOrNull { seen(it.id) && !it.id.startsWith("info-") }?.id ?: lastOrNull { seen(it.id) }?.id

/**
 * Clears any unseen baseline when a session is marked read from outside (the drawer, Catch Up, or
 * another device's `session.viewed`).
 */
fun HomeFeature.State.clearingUnseenBaseline(sessionID: String): HomeFeature.State {
    var s = copy(pendingOpenReadState = pendingOpenReadState - sessionID, unopenedSeen = unopenedSeen - sessionID)
    val items = (if (s.sessionDetail?.session?.id == sessionID) s.sessionDetail?.items else null)
        ?: s.transcriptCache[sessionID]
    s.sessionSnapshots[sessionID]?.let { snapshot ->
        val cachedIDs = items?.mapTo(HashSet()) { it.id } ?: emptySet()
        s = s.copy(sessionSnapshots = s.sessionSnapshots + (sessionID to snapshot.copy(seenItemIDs = cachedIDs, hasUnseenItems = false)))
    }
    val lastCachedID = items?.lastSeenRowID()
    val session = s.sessions.firstOrNull { it.id == sessionID }
    val viewed = maxOf(s.locallyViewedMilliseconds[sessionID] ?: 0, session?.viewedMilliseconds ?: 0, session?.idleMilliseconds ?: 0)
    val viewedMS = viewed.takeIf { it > 0 }
    s = if (lastCachedID != null || viewedMS != null) {
        s.settingSeenWatermark(SessionSeenWatermark(itemID = lastCachedID, viewedMilliseconds = viewedMS), sessionID)
    } else {
        s.clearingSeenWatermark(sessionID)
    }
    if (s.sessionDetail?.session?.id == sessionID && s.sessionDetail?.readsOnView == true) {
        s = s.updatingDetail { it.noteOwnSend() }
    }
    return s.updatingSubagents { if (it.session?.id == sessionID && it.readsOnView) it.noteOwnSend() else it }
}

fun HomeFeature.State.syncingSeenItemsFromOpenDetail(sessionID: String): HomeFeature.State {
    val detail = sessionDetail ?: return this
    if (detail.session?.id != sessionID) return this
    return syncingSeenWatermark(detail, sessionID)
}

/** Keeps how far the reader had seen [detail], for a cold launch into it. */
fun HomeFeature.State.syncingSeenWatermark(detail: SessionFeature.State, sessionID: String): HomeFeature.State {
    if (!detail.readsOnView) return this
    val sessionViewed = maxOf(
        locallyViewedMilliseconds[sessionID] ?: 0,
        detail.viewedIdle[sessionID] ?: 0,
        sessions.firstOrNull { it.id == sessionID }?.viewedMilliseconds ?: 0,
        detail.session?.viewedMilliseconds ?: 0,
        detail.session?.idleMilliseconds ?: 0,
    )
    val sessionViewedMS = sessionViewed.takeIf { it > 0 }
    val lastSeenID: String?
    val viewedMS: Long?
    val acknowledgedMS: Long?
    val mark = detail.newMessages
    val markIndex = mark?.let { m -> detail.items.indexOfFirst { it.id == m.itemID } } ?: -1
    val baseline = detail.newMessagesBaseline
    if (mark != null && markIndex >= 0) {
        lastSeenID = detail.items.subList(0, markIndex).lastSeenRowID()
        viewedMS = detail.newMessagesBaselineTimestamp
        acknowledgedMS = sessionViewedMS
    } else if (baseline != null &&
        (baseline.isNotEmpty() || detail.newMessagesUnread || detail.newMessagesBaselineTimestamp != null ||
            detail.isTranscriptCovered || detail.isBackgrounded || detail.items.isEmpty())
    ) {
        val seen = baseline + detail.newMessagesWatched
        lastSeenID = detail.items.lastSeenRowID { it in seen } ?: seen.maxOrNull()
        val hasUnseen = detail.newMessagesUnread ||
            detail.newMessagesBaselineTimestamp != null ||
            NewMessagesMark.firstUnseen(
                items = detail.items,
                seen = seen,
                watched = detail.newMessagesWatched,
                viewedMilliseconds = detail.newMessagesBaselineTimestamp,
                unread = detail.newMessagesUnread,
            ) != null
        viewedMS = if (hasUnseen) detail.newMessagesBaselineTimestamp else sessionViewedMS
        acknowledgedMS = if (hasUnseen) sessionViewedMS else null
    } else {
        lastSeenID = detail.items.lastSeenRowID()
        viewedMS = sessionViewedMS
        acknowledgedMS = null
    }
    if (lastSeenID == null && viewedMS == null && acknowledgedMS == null) return this
    return settingSeenWatermark(
        SessionSeenWatermark(itemID = lastSeenID, viewedMilliseconds = viewedMS, acknowledgedViewedMilliseconds = acknowledgedMS),
        sessionID,
    )
}

fun HomeFeature.State.sessionKey(id: String): String = "${server.persistenceID}|$id"
fun HomeFeature.State.isPinned(id: String): Boolean = sessionKey(id) in pinnedSessionKeys
fun HomeFeature.State.isHidden(id: String): Boolean = sessionKey(id) in hiddenSessionKeys

/**
 * One implementation, in `StatusSnapshot`, so the drawer's bulk loops and a single question here
 * can't drift. Neither reads the busy set, so it isn't computed for them.
 */
fun HomeFeature.State.isMuted(id: String): Boolean = statusSnapshot(busy = emptySet()).isMuted(id)
fun HomeFeature.State.isUnread(session: OpenCodeSession): Boolean = statusSnapshot(busy = emptySet()).isUnread(session)

/**
 * Moves a session to the top of Recents the moment the reader sends, rather than when the next list
 * refresh carries the server's time. The reader's own send must not make the chat look unread.
 */
fun HomeFeature.State.notingLocalActivity(sessionID: String, at: Instant): HomeFeature.State {
    val session = sessions.firstOrNull { it.id == sessionID } ?: return this
    if (session.updatedAt >= at) return this
    val serverUpdatedAt = localActivity[sessionID]?.serverUpdatedAt ?: session.updatedAt
    return copy(localActivity = localActivity + (sessionID to HomeFeature.State.LocalActivity(serverUpdatedAt, at)))
        .updatingSession(sessionID) { it.copy(updatedAt = at) }
        .updatingSessionReadDates { it + (sessionKey(sessionID) to at) }
}

fun HomeFeature.State.markingReadLocally(session: OpenCodeSession): HomeFeature.State {
    var s = this
    if (sessionDetail?.session?.id != session.id &&
        pendingOpenReadState[session.id] == null
    ) {
        val current = sessions.firstOrNull { it.id == session.id } ?: session
        val pending = HomeFeature.State.PendingOpenReadState(
            viewedMilliseconds = unseenCutoffMilliseconds(current, wasUnread = false),
            wasUnread = hasUnreadWatermark(current),
        )
        s = s.copy(pendingOpenReadState = s.pendingOpenReadState + (session.id to pending))
    }
    val key = sessionKey(session.id)
    if (s.markAllReceipts[session.id] != null) s = s.withMarkAllReceipts(s.markAllReceipts - session.id)
    val idle = session.idleMilliseconds
    if (idle != null && s.sessions.any { it.id == session.id }) {
        s = s.updatingSession(session.id) { it.copy(viewedMilliseconds = maxOf(it.viewedMilliseconds ?: 0, idle)) }
        val localViewed = maxOf(s.locallyViewedMilliseconds[session.id] ?: 0, idle)
        if (s.locallyViewedMilliseconds[session.id] != localViewed) {
            s = s.copy(locallyViewedMilliseconds = s.locallyViewedMilliseconds + (session.id to localViewed))
        }
    }
    if (s.sessionReadDates[key] != session.updatedAt) s = s.updatingSessionReadDates { it + (key to session.updatedAt) }
    if (key in s.manuallyUnreadSessionKeys) s = s.updatingManuallyUnreadSessionKeys { it - key }
    return s
}

/** Forgets a session the server has deleted: its row and every phone-local record keyed by it. */
fun HomeFeature.State.purgingSession(sessionID: String): HomeFeature.State {
    var s = copy(
        sessions = sessions.filterNot { it.id == sessionID },
        sessionPages = sessionPages.removingResult(sessionID).copy(adoptedIDs = sessionPages.adoptedIDs - sessionID).markingGone(sessionID),
        activeSessionIDs = activeSessionIDs - sessionID,
        activeSessionParents = activeSessionParents - sessionID,
        attentionSessionIDs = attentionSessionIDs - sessionID,
        runChangesDuringRefresh = runChangesDuringRefresh - sessionID,
        locallyViewedMilliseconds = locallyViewedMilliseconds - sessionID,
        pendingOpenReadState = pendingOpenReadState - sessionID,
        unopenedSeen = unopenedSeen - sessionID,
        localActivity = localActivity - sessionID,
        transcriptCache = transcriptCache - sessionID,
        refreshingTranscripts = refreshingTranscripts - sessionID,
        fetchingTranscripts = fetchingTranscripts - sessionID,
        dirtyCachedTranscriptIDs = dirtyCachedTranscriptIDs - sessionID,
        transcriptReadAt = transcriptReadAt - sessionID,
        sessionSnapshots = (sessionSnapshots - sessionID).mapValues { (_, snapshot) ->
            // A deleted subagent's requests leave the cached family too.
            snapshot.copy(family = snapshot.family.filterNot { it.id == sessionID })
                .pruneRequests { it.sessionID == sessionID }
        },
        sessionSnapshotOrder = sessionSnapshotOrder - sessionID,
        inboxSidebarStatus = inboxSidebarStatus.copy(
            archived = inboxSidebarStatus.archived.filterNot { it.id == sessionID },
            archivedMatches = inboxSidebarStatus.archivedMatches?.filterNot { it.id == sessionID },
        ),
    )
    val serverID = server.persistenceID
    ServerSharedKeys.cachedTranscriptSnapshots().update { it.removeEntry(serverID, sessionID) }
    // The deleted session's saved draft goes too.
    dependency(ComposerPersistence).removeSession(serverID, sessionID)
    val key = sessionKey(sessionID)
    s = s.updatingPinnedSessionKeys { keys -> keys.filterNot { it == key } }
        .updatingSessionReadDates { it - key }
        .clearingSeenWatermark(sessionID)
        .updatingManuallyUnreadSessionKeys { it - key }
        .updatingMutedSessionKeys { it - key }
        .updatingHiddenSessionKeys { it - key }
    if (s.lastViewedSessionIDs[serverID] == sessionID) s = s.updatingLastViewedSessionIDs { it - serverID }
    return s
}

fun HomeFeature.State.statusSnapshot(busy: Set<String>? = null): StatusSnapshot =
    // Reconnecting keeps the last-known state on screen while it is fresh (live within
    // `lastKnownActivityLifetime`), so a routine reconnect or a quick relaunch doesn't blank every
    // row. Older state, or an outage, has nothing trustworthy to show.
    StatusSnapshot(
        serverID = server.persistenceID,
        showsActivity = isConnected ||
            (connectionStatus == HomeConnectionFeature.State.ConnectionStatus.reconnecting && lastKnownActivityDeadline != null),
        busy = busy ?: busySessionIDs,
        attention = attentionSessionIDs,
        mutedKeys = mutedSessionKeys,
        manuallyUnreadKeys = manuallyUnreadSessionKeys,
        readDates = sessionReadDates,
    )

fun HomeFeature.State.indicator(session: OpenCodeSession): Indicator = statusSnapshot().indicator(session)

val HomeFeature.State.sessionIndicators: Map<String, Indicator>
    get() {
        val status = statusSnapshot()
        return sessions.associate { it.id to status.indicator(it) }
    }

/** The open session and its family's root. */
val HomeFeature.State.openSessionIDs: Set<String>
    get() = setOfNotNull(sessionDetail?.session?.id, sessionDetail?.familyRootID)

val HomeFeature.State.viewing: Viewing
    get() {
        val detail = sessionDetail ?: return Viewing()
        if (!detail.isVisible) return Viewing()
        return Viewing(detail.session?.id, detail.familyRootID)
    }

/**
 * Every session with a screen, the open one and each pushed subagent: the event stream keeps all
 * of their deltas under load ([ai.opencode.server.EventStreamFocusClient]).
 */
val HomeFeature.State.streamFocusSessionIDs: Set<String>
    get() {
        val ids = LinkedHashSet<String>()
        sessionDetail?.session?.id?.let(ids::add)
        for (screen in subagentStack) {
            screen.session?.id?.let(ids::add)
        }
        return ids
    }

/** The sessions whose delivered notifications go when they're on screen. */
val HomeFeature.State.viewedNotificationThreads: Set<String> get() = viewing.threads

/** The drawer button's dot: whether any session besides the open one needs input or is unread. Muted sessions never light it. */
fun HomeFeature.State.drawerBadge(excluding: String?): DrawerBadge? {
    if (!(isConnected ||
            (connectionStatus == HomeConnectionFeature.State.ConnectionStatus.reconnecting && lastKnownActivityDeadline != null))
    ) {
        return null
    }
    val status = statusSnapshot(busy = emptySet())
    val unreadCandidates = mutableListOf<String>()
    for (session in sessions) {
        if (session.id == excluding || session.isArchived || status.isMuted(session.id)) continue
        if (session.id in status.attention) return DrawerBadge.Attention
        if (status.isUnread(session)) unreadCandidates += session.id
    }
    if (unreadCandidates.isEmpty()) return null
    val busy = busySessionIDs
    return if (unreadCandidates.any { it !in busy }) DrawerBadge.Unread else null
}

/**
 * Records local activity on the family root when [action] was an accepted send on [detail] (composer
 * emptied and awaiting reply); returns the state and the root ID. Mid-run `isAwaitingReply` is
 * already true, so a send still waiting on uploads or an answer to a docked request doesn't count.
 */
fun HomeFeature.State.notingScreenSendIfAccepted(
    detail: SessionFeature.State?,
    action: SessionFeature.Action,
    at: () -> Instant,
): Pair<HomeFeature.State, String?> {
    if (detail == null || !detail.isAwaitingReply) return this to null
    val accepted = if (action == SessionFeature.Action.Browser(BrowserFeature.Action.AskSent)) {
        detail.browser.askDraft.isEmpty()
    } else {
        action.sendsComposer && !detail.hasPromptContent && !detail.isAnsweringRequest
    }
    if (!accepted) return this to null
    val rootID = sessionDetail?.familyRootID ?: sessionDetail?.session?.id ?: detail.familyRootID ?: detail.session?.id
        ?: return this to null
    return notingLocalActivity(rootID, at()) to rootID
}

fun HomeFeature.State.invalidatingSnapshotFamilyReads(
    matching: (SessionFeature.SwitchSnapshot) -> Boolean = { true },
): HomeFeature.State {
    var changed = false
    val next = sessionSnapshots.mapValues { (_, snapshot) ->
        if (snapshot.familyReadAt != null && matching(snapshot)) {
            changed = true
            snapshot.copy(familyReadAt = null)
        } else {
            snapshot
        }
    }
    return if (changed) copy(sessionSnapshots = next) else this
}

/** Merges the ancestry above [sessionIDs] into `activeSessionParents`. */
fun HomeFeature.State.recordingActiveAncestry(sessionIDs: Set<String>, parents: Map<String, String>? = null): HomeFeature.State {
    if (sessionIDs.isEmpty()) return this
    val links = parents ?: knownParents
    var next: MutableMap<String, String>? = null
    for ((childID, parentID) in SessionActivity.ancestry(sessionIDs, links)) {
        if (activeSessionParents[childID] != parentID) {
            if (next == null) next = activeSessionParents.toMutableMap()
            next[childID] = parentID
        }
    }
    return next?.let { copy(activeSessionParents = it) } ?: this
}

/**
 * The open family's links (read by the screen) join the parents kept for running sessions, so a
 * root stays lit after a switch away.
 */
fun HomeFeature.State.syncingBusyAncestryFromFamily(deletedSessionID: String? = null): HomeFeature.State {
    var s = this
    if (deletedSessionID != null && deletedSessionID in s.activeSessionParents) {
        s = s.copy(activeSessionParents = s.activeSessionParents - deletedSessionID)
    }
    if (s.activeSessionIDs.isEmpty()) return s
    return s.recordingActiveAncestry(s.activeSessionIDs)
}

internal val SessionFeature.Action.sendsComposer: Boolean
    get() = this == SessionFeature.Action.SendTapped || this == SessionFeature.Action.QueueTapped ||
        this is SessionFeature.Action.DeferredSendReady || this == SessionFeature.Action.SharedSendRequested
