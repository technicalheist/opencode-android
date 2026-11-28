package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeature.State
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionPage
import ai.opencode.server.model.OpenCodeSessionPageRequest
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException

/*
 * The drawer's list beyond Home's snapshot page, older pages as the list scrolls to its end, and a
 * search the server runs over every session.
 */

/** The drawer's older pages and the server's search results. */
data class SessionPages(
    val older: SessionPageCursor = SessionPageCursor(),
    /**
     * Older pages appended so far. Once there are any, a snapshot keeps every session it doesn't
     * list (deletion arrives as its own event).
     */
    val olderPagesLoaded: Int = 0,
    /** The server's answer for the drawer's search, null while it isn't searching. */
    val search: SessionSearch? = null,
    /** Page answers so far, read or failed: the list checks its footer again after each, even one that changed no row. */
    val pagesRead: Int = 0,
    /** Search results the person acted on: listed like the snapshot's own sessions, they stay through later snapshots. */
    val adoptedIDs: Set<String> = emptySet(),
    /**
     * Deleted or archived while an older page or a search page was being read: that answer may
     * predate it, so it leaves them out. A read started afterwards is authoritative and starts
     * with none.
     */
    val olderGoneIDs: Set<String> = emptySet(),
    val searchGoneIDs: Set<String> = emptySet(),
) {
    /** Removes a deleted or archived session from the search results. */
    fun removingResult(sessionID: String): SessionPages {
        val found = search ?: return this
        return copy(search = found.copy(results = found.results.filterNot { it.id == sessionID }))
    }

    fun renamingResult(sessionID: String, title: String): SessionPages {
        val found = search ?: return this
        if (found.results.none { it.id == sessionID }) return this
        val results = found.results.map { if (it.id == sessionID) it.copy(serverTitle = title) else it }
        return copy(search = found.copy(results = results))
    }

    /** Deleted or archived, so answers to page reads already in flight leave it out. */
    fun markingGone(sessionID: String): SessionPages {
        var pages = this
        if (older.isLoading) pages = pages.copy(olderGoneIDs = pages.olderGoneIDs + sessionID)
        if (search?.page?.isLoading == true) pages = pages.copy(searchGoneIDs = pages.searchGoneIDs + sessionID)
        return pages
    }
}

data class SessionPageCursor(
    /** The next page's cursor; null at the end of the list (or before the first live snapshot says otherwise). */
    val next: String? = null,
    val isLoading: Boolean = false,
    /** The last read failed: the list waits for Try Again. */
    val failed: Boolean = false,
) {
    val canLoad: Boolean get() = next != null && !isLoading && !failed
}

data class SessionSearch(
    /** What the results answer, trimmed. */
    val query: String,
    /** Root sessions the server matched, newest first. Kept while a new query is read; the list shows only those that still match. */
    val results: List<OpenCodeSession> = emptyList(),
    val page: SessionPageCursor = SessionPageCursor(),
)

/** What the drawer shows under its last row. */
enum class DrawerListFooter { loading, failed }

sealed interface SessionPagesAction {
    /** The list's last row came into view. */
    data object LoadMoreRequested : SessionPagesAction
    data object RetryTapped : SessionPagesAction
    data class OlderLoaded(val result: Result<OpenCodeSessionPage>) : SessionPagesAction
    data class SearchLoaded(val query: String, val appending: Boolean, val result: Result<OpenCodeSessionPage>) : SessionPagesAction
}

val HomeFeature.Companion.sessionPageSize: Int get() = 50
val HomeFeature.Companion.sessionSearchDebounce: Duration get() = 250.milliseconds

internal fun HomeFeature.reduceSessionPages(state: State, action: SessionPagesAction): HomeReduced {
    when (action) {
        SessionPagesAction.LoadMoreRequested -> return loadMoreSessions(state)

        SessionPagesAction.RetryTapped -> {
            var s = state
            val search = s.sessionPages.search
            if (search != null) {
                if (!search.page.failed) return Reduced(s)
                if (search.page.next == null) {
                    // The first page failed: there's no cursor, so ask again.
                    dependency(HapticsClient).play(Haptic.tap)
                    val page = search.page.copy(failed = false, isLoading = true)
                    s = s.copy(sessionPages = s.sessionPages.copy(search = search.copy(page = page), searchGoneIDs = emptySet()))
                    return Reduced(s, searchSessions(search.query, s.server, delay = Duration.ZERO))
                }
                s = s.copy(sessionPages = s.sessionPages.copy(search = search.copy(page = search.page.copy(failed = false))))
            } else {
                if (!s.sessionPages.older.failed) return Reduced(s)
                s = s.copy(sessionPages = s.sessionPages.copy(older = s.sessionPages.older.copy(failed = false)))
            }
            dependency(HapticsClient).play(Haptic.tap)
            return loadMoreSessions(s)
        }

        is SessionPagesAction.OlderLoaded -> {
            val pages = state.sessionPages
            val gone = pages.olderGoneIDs
            var s = state.copy(
                sessionPages = pages.copy(
                    older = pages.older.copy(isLoading = false),
                    pagesRead = pages.pagesRead + 1,
                    olderGoneIDs = emptySet(),
                ),
            )
            val page = action.result.getOrNull()
            if (page != null) {
                AppLog.network.info { "drawer older sessions page count=${page.sessions.size} more=${page.next != null}" }
                s = s.copy(
                    sessionPages = s.sessionPages.copy(
                        older = s.sessionPages.older.copy(next = page.next),
                        olderPagesLoaded = s.sessionPages.olderPagesLoaded + 1,
                    ),
                )
                s = s.appendingListedSessions(page.sessions.filterNot { it.id in gone })
            } else {
                AppLog.network.error { "drawer older sessions failed ${logSummary(action.result.exceptionOrNull())}" }
                s = s.copy(sessionPages = s.sessionPages.copy(older = s.sessionPages.older.copy(failed = true)))
            }
            return Reduced(s)
        }

        is SessionPagesAction.SearchLoaded -> {
            val current = state.sessionPages.search
            if (current == null || current.query != action.query) return Reduced(state)
            var search = current.copy(page = current.page.copy(isLoading = false))
            val gone = state.sessionPages.searchGoneIDs + state.recentlyDeletedIDs
            var s = state.copy(sessionPages = state.sessionPages.copy(pagesRead = state.sessionPages.pagesRead + 1, searchGoneIDs = emptySet()))
            val page = action.result.getOrNull()
            if (page != null) {
                AppLog.network.info { "drawer search page count=${page.sessions.size} more=${page.next != null}" }
                val found = page.sessions.filterNot { it.id in gone }
                s = s.seedingReadDates(found)
                val results = if (action.appending) {
                    val known = HashSet<String>()
                    for (result in search.results) known += result.id
                    search.results + found.filterNot { it.id in known }
                } else {
                    found
                }
                search = search.copy(results = results, page = search.page.copy(next = page.next))
            } else {
                AppLog.network.error { "drawer search failed ${logSummary(action.result.exceptionOrNull())}" }
                // The last answer stays; the list offers Try Again.
                val next = if (action.appending) search.page.next else null
                search = search.copy(page = search.page.copy(failed = true, next = next))
            }
            s = s.copy(sessionPages = s.sessionPages.copy(search = search))
            return Reduced(s)
        }
    }
}

/** The drawer's search text changed: after a pause, ask the server. */
internal fun HomeFeature.sessionSearchChanged(state: State): HomeReduced {
    val query = state.search.trim()
    val pages = state.sessionPages
    if (query.isEmpty()) {
        if (pages.search == null) return Reduced(state)
        return Reduced(state.copy(sessionPages = pages.copy(search = null)), Effect.cancel(HomeFeature.CancelID.SessionSearch))
    }
    if (pages.search?.query == query) return Reduced(state)
    // With no older page left to read the drawer already holds every session, and its own filter answers.
    if (pages.older.next == null && !pages.older.failed) {
        if (pages.search == null) return Reduced(state)
        return Reduced(state.copy(sessionPages = pages.copy(search = null)), Effect.cancel(HomeFeature.CancelID.SessionSearch))
    }
    // Earlier results stay only where they match this query too.
    val kept = (pages.search?.results ?: emptyList()).filter { it.title.contains(query, ignoreCase = true) }
    val search = SessionSearch(query = query, results = kept, page = SessionPageCursor(isLoading = true))
    val s = state.copy(sessionPages = pages.copy(search = search, searchGoneIDs = emptySet()))
    return Reduced(s, searchSessions(query, s.server, delay = HomeFeature.sessionSearchDebounce))
}

/** The first page of a search, after [delay]. */
private fun searchSessions(query: String, server: OpenCodeServer, delay: Duration): Effect<Action> {
    val client = dependency(OpenCodeClient)
    val clock = dependency(Clock)
    return Effect.run<Action> { send ->
        if (delay > Duration.ZERO) clock.sleep(delay)
        val result = readSessionPage(client, server, OpenCodeSessionPageRequest.Search(query))
        send(Action.SessionPages(SessionPagesAction.SearchLoaded(query, appending = false, result)))
    }.cancellable(HomeFeature.CancelID.SessionSearch, cancelInFlight = true)
}

private fun loadMoreSessions(state: State): HomeReduced {
    val server = state.server
    val client = dependency(OpenCodeClient)
    val search = state.sessionPages.search
    if (search != null) {
        val cursor = search.page.next
        if (!search.page.canLoad || cursor == null) return Reduced(state)
        val page = search.page.copy(isLoading = true)
        val s = state.copy(sessionPages = state.sessionPages.copy(search = search.copy(page = page), searchGoneIDs = emptySet()))
        val query = search.query
        val effect = Effect.run<Action> { send ->
            val result = readSessionPage(client, server, OpenCodeSessionPageRequest.After(cursor))
            send(Action.SessionPages(SessionPagesAction.SearchLoaded(query, appending = true, result)))
        }.cancellable(HomeFeature.CancelID.SessionSearch, cancelInFlight = true)
        return Reduced(s, effect)
    }
    val older = state.sessionPages.older
    val cursor = older.next
    if (state.search.isNotEmpty() || !older.canLoad || cursor == null) return Reduced(state)
    val s = state.copy(sessionPages = state.sessionPages.copy(older = older.copy(isLoading = true), olderGoneIDs = emptySet()))
    val effect = Effect.run<Action> { send ->
        val result = readSessionPage(client, server, OpenCodeSessionPageRequest.After(cursor))
        send(Action.SessionPages(SessionPagesAction.OlderLoaded(result)))
    }.cancellable(HomeFeature.CancelID.OlderSessions)
    return Reduced(s, effect)
}

/** One page read, its failure kept as a value. */
private suspend fun readSessionPage(
    client: OpenCodeClient,
    server: OpenCodeServer,
    request: OpenCodeSessionPageRequest,
): Result<OpenCodeSessionPage> =
    try {
        Result.success(client.sessionPage(server, request, HomeFeature.sessionPageSize))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: OpenCodeClientError) {
        Result.failure(failure)
    }

private fun logSummary(error: Throwable?): String =
    (error as? OpenCodeClientError)?.logSummary ?: (error?.javaClass?.simpleName ?: "unknown")

/**
 * What the drawer shows under its last row: a spinner while there's more to read (reading starts
 * as it comes into view), or Try Again.
 */
val State.drawerListFooter: DrawerListFooter?
    get() {
        if (search.isNotEmpty()) {
            val found = sessionPages.search ?: return null
            if (found.page.failed) return DrawerListFooter.failed
            return if (found.page.isLoading || found.page.next != null) DrawerListFooter.loading else null
        }
        if (sessionPages.older.failed) return DrawerListFooter.failed
        return if (sessionPages.older.next != null) DrawerListFooter.loading else null
    }

/**
 * Sessions from an older page, or a search result the person acted on, joining the list. Sessions
 * already listed keep their fresher copy.
 */
internal fun State.appendingListedSessions(listed: List<OpenCodeSession>): State {
    val known = HashSet<String>()
    for (session in sessions) known += session.id
    val new = mutableListOf<OpenCodeSession>()
    for (session in listed) {
        if (session.id in known || session.id in recentlyDeletedIDs || session.isArchived) continue
        new += session
    }
    if (new.isEmpty()) return this
    return seedingReadDates(new).let { it.copy(sessions = it.sessions + new) }
}

/**
 * Sessions older than the first page were there when the inbox began. Without the server's idle
 * time, they start read, as the first page did.
 */
internal fun State.seedingReadDates(listed: List<OpenCodeSession>): State {
    val missing = mutableMapOf<String, Instant>()
    for (session in listed) {
        val key = sessionKey(session.id)
        if (session.idleMilliseconds == null && sessionReadDates[key] == null) missing[key] = session.updatedAt
    }
    if (missing.isEmpty()) return this
    return updatingSessionReadDates { it + missing }
}

/**
 * A search result the person acts on (opens, pins, marks) joins the list, so the drawer's actions
 * find it.
 */
internal fun State.adoptingSearchResult(sessionID: String): State {
    if (sessions.any { it.id == sessionID }) return this
    val found = sessionPages.search?.results?.firstOrNull { it.id == sessionID } ?: return this
    val s = appendingListedSessions(listOf(found))
    if (s.sessions.none { it.id == sessionID }) return s
    return s.copy(sessionPages = s.sessionPages.copy(adoptedIDs = s.sessionPages.adoptedIDs + sessionID))
}

/** The drawer actions that adopt the search result they act on. */
internal fun State.adoptingSearchResultFor(action: Action): State {
    val sessionID = when (action) {
        is Action.SessionTapped -> action.sessionID
        is Action.TogglePin -> action.sessionID
        is Action.ToggleMute -> action.sessionID
        is Action.ToggleHidden -> action.sessionID
        is Action.MarkRead -> action.sessionID
        is Action.MarkUnread -> action.sessionID
        is Action.DeleteSessionTapped -> action.sessionID
        is Action.DrawerRowDropReleased -> action.sessionID
        is Action.DrawerRowDropped -> action.sessionID
        else -> return this
    }
    return adoptingSearchResult(sessionID)
}
