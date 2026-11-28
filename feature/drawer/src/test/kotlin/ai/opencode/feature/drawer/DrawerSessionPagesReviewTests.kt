package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature.Action
import ai.opencode.feature.drawer.HomeFeatureTestSupport.date
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.receiveBounded
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.feature.drawer.list.DrawerSelection
import ai.opencode.feature.drawer.list.selectionActions
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionPage
import ai.opencode.server.model.OpenCodeSessionPageRequest
import java.time.Instant
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The drawer's older pages and server search at their edges. */
class DrawerSessionPagesReviewTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, title: String? = null, updated: Number) = HomeFeatureTestSupport.session(
        id = id, title = title ?: id, directory = "/project", updatedAt = date(updated), idle = 1_000, viewed = 1_000,
    )

    private fun HomeFeatureTestSupport.Harness.state(sessions: List<OpenCodeSession>, next: String? = null): HomeFeature.State {
        val state = state { HomeFeature.State(server, snapshot(sessions)) }
        return state.copy(sessionPages = state.sessionPages.copy(older = state.sessionPages.older.copy(next = next)))
    }

    private fun client(block: OpenCodeClient.() -> OpenCodeClient): OpenCodeClient = OpenCodeClient.testValue.block()

    private fun searching(text: String): Action = Action.SearchChanged(text)

    private fun pages(action: SessionPagesAction): Action = Action.SessionPages(action)

    @Test
    fun tryAgainRepeatsAFailedFirstSearch() = homeTest { h ->
        val found = session("a", "Alpha", updated = 9)
        val fails = HomeFeatureTestSupport.LockIsolated(true)
        val store = h.store(h.state(emptyList(), next = "c1")) {
            this[Clock] = ImmediateClock()
            this[HapticsClient] = HapticsClient(play = {})
            this[OpenCodeClient] = client {
                copy(sessionPage = { _, request, _ ->
                    assertEquals(OpenCodeSessionPageRequest.Search("Alpha"), request)
                    if (fails.value) throw OpenCodeClientError("Offline")
                    OpenCodeSessionPage(listOf(found), next = null)
                })
            }
        }
        store.exhaustive = false
        store.send(searching("Alpha"))
        store.receivePages(SessionPagesAction.SearchLoaded::class)
        assertEquals(DrawerListFooter.failed, store.state.drawerListFooter)
        fails.setValue(false)
        store.send(pages(SessionPagesAction.RetryTapped))
        store.receivePages(SessionPagesAction.SearchLoaded::class)
        assertEquals(listOf(found), store.state.sessionPages.search?.results)
        assertNull(store.state.drawerListFooter)
    }

    @Test
    fun aPageThatAddsNoRowStillChangesTheList() = homeTest { h ->
        val listed = session("a", "Alpha", updated = 9)
        val store = h.store(h.state(listOf(listed), next = "c1")) {
            this[OpenCodeClient] = client { copy(sessionPage = { _, _, _ -> OpenCodeSessionPage(listOf(listed), next = "c2") }) }
        }
        store.exhaustive = false
        val inputs = DrawerListInputs(selectedSessionID = null, reducedMotion = false, increasedContrast = false)
        fun model(): DrawerListModel = h.state {
            DrawerListModel.build(
                state = store.state, inputs = inputs, liftedID = null,
                now = Instant.ofEpochSecond(100), timestamp = { _, _ -> "" }, project = { _, _ -> null },
            )
        }
        val before = model()
        store.send(pages(SessionPagesAction.LoadMoreRequested))
        store.receivePages(SessionPagesAction.OlderLoaded::class)
        assertNotEquals("the list sees the answer and checks its footer for the next page", before, model())
    }

    @Test
    fun aNewQueryDropsResultsThatDontMatchIt() = homeTest { h ->
        val alpha = session("a", "Alpha", updated = 9)
        val alphaBeta = session("ab", "Alpha Beta", updated = 8)
        val initial = h.state(emptyList(), next = "c1")
        val searched = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(search = SessionSearch("Alpha", results = listOf(alpha, alphaBeta))),
        )
        val store = h.store(searched) { this[Clock] = TestClock() }
        store.exhaustive = false
        store.send(searching("Beta"))
        assertEquals(listOf("ab"), store.state.recentSessions.map { it.id })
        assertEquals(listOf(alphaBeta), store.state.sessionPages.search?.results)
        h.skipInFlightEffects()
    }

    @Test
    fun aRefreshWhileTheFirstOlderPageIsReadKeepsTheBoundaryRow() = homeTest { h ->
        val newest = session("new", updated = 40)
        val first = session("1", updated = 30)
        val boundary = session("2", updated = 20)
        val initial = h.state(listOf(first, boundary), next = "c1")
        val reading = initial.copy(sessionPages = initial.sessionPages.copy(older = initial.sessionPages.older.copy(isLoading = true)))
        val store = h.store(reading) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client {
                copy(homeAttention = { _, _, _ -> emptySet() }, transcript = { _, _, _ -> emptyList() })
            }
        }
        store.exhaustive = false
        store.send(Action.SnapshotRefreshed(pageSnapshot(listOf(newest, first), nextCursor = "n1")))
        assertEquals("the page in flight continues after row 2", listOf("new", "1", "2"), store.state.sessions.map { it.id })
        assertEquals("c1", store.state.sessionPages.older.next)
        h.skipInFlightEffects()
    }

    @Test
    fun droppingAServerResultIntoPinnedPinsIt() = homeTest { h ->
        val found = session("old", "Alpha", updated = 2)
        val initial = h.state(listOf(session("new", updated = 30)))
        val searched = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(search = SessionSearch("Alpha", results = listOf(found))),
        )
        val store = h.store(searched)
        store.exhaustive = false
        store.send(Action.DrawerRowDropped(sessionID = found.id, pinnedIndex = 0))
        assertTrue(store.state.isPinned(found.id))
    }

    @Test
    fun unhidingAServerResultFromTheSelectionUnhidesIt() = homeTest { h ->
        val found = session("old", "Alpha", updated = 2)
        val initial = h.state(listOf(session("new", updated = 30)))
        val searched = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(search = SessionSearch("Alpha", results = listOf(found))),
            hiddenSessionKeys = setOf(initial.sessionKey(found.id)),
        )
        val store = h.store(searched)
        store.exhaustive = false
        val sent = mutableListOf<Action>()
        val view = object : StoreOf<HomeFeature.State, Action> {
            override val state: HomeFeature.State get() = store.state
            override val states = flowOf(store.state)
            override fun send(action: Action) {
                sent += action
            }
        }
        val selection = DrawerSelection()
        selection.toggle(found.id)
        val (_, overflow) = selectionActions(view, selection, pins = true)
        overflow.first { it.title == "Unhide" }.action()
        assertEquals(listOf<Action>(Action.ToggleHidden(found.id)), sent)
        store.send(Action.ToggleHidden(found.id))
        assertFalse(store.state.isHidden(found.id))
    }

    @Test
    fun aSearchResultMarkedUnreadSurvivesARefresh() = homeTest { h ->
        val newest = session("new", updated = 30)
        val found = session("old", "Alpha", updated = 2)
        val initial = h.state(listOf(newest))
        val searched = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(search = SessionSearch("Alpha", results = listOf(found))),
        )
        val store = h.store(searched) {
            this[Clock] = ImmediateClock()
            this[HapticsClient] = HapticsClient(play = {})
            this[OpenCodeClient] = client {
                copy(homeAttention = { _, _, _ -> emptySet() }, transcript = { _, _, _ -> emptyList() })
            }
        }
        store.exhaustive = false
        store.send(Action.MarkUnread(found.id))
        store.send(Action.SnapshotRefreshed(snapshot(listOf(newest))))
        assertEquals(listOf("new", "old"), store.state.sessions.map { it.id })
        h.skipInFlightEffects()
    }

    @Test
    fun theFirstSnapshotsCursorPagesAtOnce() = homeTest { h ->
        val state = h.state { HomeFeature.State(server, pageSnapshot(listOf(session("a", updated = 1)), nextCursor = "c1")) }
        assertEquals("c1", state.sessionPages.older.next)
        assertEquals(DrawerListFooter.loading, state.drawerListFooter)
    }

    @Test
    fun aPageReadBeforeADeletionDoesntBringItBack() = homeTest { h ->
        val newest = session("new", updated = 30)
        val deleted = session("gone", "Alpha gone", updated = 20)
        val archived = session("shelved", "Alpha shelved", updated = 10)
        val initial = h.state(listOf(newest), next = "c1")
        val reading = initial.copy(
            search = "Alpha",
            sessionPages = initial.sessionPages.copy(
                older = initial.sessionPages.older.copy(isLoading = true),
                search = SessionSearch("Alpha", page = SessionPageCursor(isLoading = true)),
            ),
        )
        val store = h.store(reading) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client {
                copy(
                    homeSnapshot = { _ -> snapshot(emptyList()) },
                    sessionPage = { _, _, _ -> OpenCodeSessionPage(emptyList(), next = null) },
                    transcript = { _, _, _ -> emptyList() },
                    getSession = { _, _ -> throw OpenCodeClientError("Gone") },
                    homeAttention = { _, _, _ -> emptySet() },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionDeleted(deleted.id)))
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionArchiveChanged(archived.id, isArchived = true)))
        val stale = OpenCodeSessionPage(listOf(deleted, archived), next = null)
        store.send(pages(SessionPagesAction.SearchLoaded("Alpha", appending = false, Result.success(stale))))
        store.send(pages(SessionPagesAction.OlderLoaded(Result.success(stale))))
        assertEquals(true, store.state.sessionPages.search?.results?.isEmpty())
        assertEquals(listOf("new"), store.state.sessions.map { it.id })
        h.skipInFlightEffects()
    }

    @Test
    fun aFreshReadBringsBackASessionUnarchivedElsewhere() = homeTest { h ->
        val newest = session("new", "Alpha new", updated = 30)
        val back = session("back", "Alpha back", updated = 10)
        val store = h.store(h.state(listOf(newest), next = "c1")) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = client {
                copy(
                    homeSnapshot = { _ -> pageSnapshot(listOf(newest), nextCursor = "c1") },
                    homeAttention = { _, _, _ -> emptySet() },
                    transcript = { _, _, _ -> emptyList() },
                    sessionPage = { _, request, _ ->
                        val found = if (request == OpenCodeSessionPageRequest.Search("Alpha")) listOf(newest, back) else listOf(back)
                        OpenCodeSessionPage(found, next = null)
                    },
                )
            }
        }
        store.exhaustive = false
        // Archived here, then unarchived on another client while this phone was offline.
        store.send(Action.ServerEvent(OpenCodeServerEvent.SessionArchiveChanged(back.id, isArchived = true)))
        store.skipReceivedActions()
        store.send(pages(SessionPagesAction.LoadMoreRequested))
        store.receivePages(SessionPagesAction.OlderLoaded::class)
        assertEquals("a page read after the archive is authoritative", listOf("new", "back"), store.state.sessions.map { it.id })
        h.skipInFlightEffects()
    }

    private suspend fun <T : SessionPagesAction> TestStore<HomeFeature.State, Action>.receivePages(case: KClass<T>) =
        receiveBounded({ case.isInstance((it as? Action.SessionPages)?.action) }, "sessionPages.${case.simpleName}")

    private fun pageSnapshot(sessions: List<OpenCodeSession>, nextCursor: String?) =
        OpenCodeHomeSnapshot(projects = emptyList(), sessions = sessions, activeSessionIDs = emptySet(), nextCursor = nextCursor)
}
