package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.drawer.HomeFeatureTestSupport.Harness
import ai.opencode.feature.drawer.HomeFeatureTestSupport.homeTest
import ai.opencode.feature.drawer.HomeFeatureTestSupport.snapshot
import ai.opencode.feature.session.isDraft
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** Coming back after `HomeFeature.newSessionAfterAway` opens a new session; a shorter absence returns to the session that was open. */
class HomeFeatureTestsNewSessionAfterAway {
    @get:Rule val isolation = TestIsolationRule()

    private val leftAt: Instant = Instant.ofEpochSecond(10_000)
    private val longAbsence = HomeFeature.newSessionAfterAway

    private fun homeShowingSession(h: Harness): Pair<HomeFeature.State, OpenCodeSession> {
        val server = OpenCodeServer(url = "https://saved.example", username = "", password = "")
        val session = OpenCodeSession(id = "ses_open", serverTitle = "Open", projectID = "p", directory = "/p", updatedAt = Instant.now())
        h.setShared(SharedKeys.lastViewedSessionIDs, mapOf(server.persistenceID to session.id))
        val state = h.state { HomeFeature.State(server, snapshot(listOf(session))) }
        return state to session
    }

    @Test
    fun comingBackAfterALongAbsenceOpensANewSession() = homeTest { h ->
        var (state, session) = homeShowingSession(h)
        assertEquals(session, state.sessionDetail?.session)

        state = h.state { state.appLeft(leftAt).returnedToApp(leftAt + longAbsence.minusSeconds(1)) }
        assertEquals(session, state.sessionDetail?.session)
        assertNull(h.shared(SharedKeys.appLeftAt))

        state = h.state { state.appLeft(leftAt).returnedToApp(leftAt + longAbsence) }
        assertTrue(state.sessionDetail?.isDraft == true)
        assertNull(h.shared(SharedKeys.appLeftAt))
        assertEquals("Last Viewed still names it", session.id, state.lastViewedSessionIDs[state.server.persistenceID])
    }

    @Test
    fun whatOpensOnTheWayBackWinsOverTheNewSession() = homeTest { h ->
        var (state, session) = homeShowingSession(h)
        state = h.state { state.appLeft(leftAt) }
        // A notification tapped to come back opens its session first.
        val (next, detail) = h.state { state.makeDetail(session, cachedItems = emptyList(), reason = "a notification") }
        state = next.copy(sessionDetail = detail)
        state = h.state { state.returnedToApp(leftAt + longAbsence.multipliedBy(2)) }
        assertEquals(session, state.sessionDetail?.session)
    }

    @Test
    fun aSessionOpenedOnTheWayBackWinsOverTheNewSession() = homeTest { h ->
        val (home, session) = homeShowingSession(h)
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Subagent", projectID = "p", directory = "/p", updatedAt = Instant.now(), parentID = session.id)
        val withChild = h.state { home.copy(sessions = home.sessions + child).pushingSubagent(child) }
        val feature = HomeFeature()
        for ((state, opened) in listOf(home to session, withChild to child)) {
            // A notification or link for what is already on screen.
            val tapped = h.state { feature.reduce(state.appLeft(leftAt), HomeFeature.Action.SessionTapped(opened.id)).state }
            val returned = h.state { tapped.returnedToApp(leftAt + longAbsence.multipliedBy(2)) }
            assertEquals(opened, returned.topDetail?.session)
        }
    }

    @Test
    fun aRecordingOnASubagentKeepsItOpenAfterALongAbsence() = homeTest { h ->
        val (home, session) = homeShowingSession(h)
        val child = OpenCodeSession(id = "ses_child", serverTitle = "Subagent", projectID = "p", directory = "/p", updatedAt = Instant.now(), parentID = session.id)
        var state = h.state { home.copy(sessions = home.sessions + child).pushingSubagent(child) }
        val recording = DictationFeature.State(phase = DictationFeature.Phase.Recording)
        state = state.copy(subagentStack = listOf(state.subagentStack[0].copy(dictation = recording)))
        state = h.state { state.appLeft(leftAt).returnedToApp(leftAt + longAbsence.multipliedBy(2)) }
        assertEquals(child, state.topDetail?.session)
    }

    @Test
    fun launchingAfterALongAbsenceOpensANewSession() = homeTest { h ->
        var (state, session) = homeShowingSession(h)
        h.setShared(SharedKeys.appLeftAt, leftAt)

        state = h.state {
            withDependencies({ this[AppAwayDate] = DateClient.constant(leftAt + longAbsence.minusSeconds(1)) }) { state.restoringInitialSession() }
        }
        assertEquals(session, state.sessionDetail?.session)

        state = h.state {
            withDependencies({ this[AppAwayDate] = DateClient.constant(leftAt + longAbsence) }) { state.restoringInitialSession() }
        }
        assertTrue(state.sessionDetail?.isDraft == true)
    }
}
