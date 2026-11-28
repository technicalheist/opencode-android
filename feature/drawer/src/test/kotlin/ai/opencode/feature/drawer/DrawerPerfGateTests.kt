package ai.opencode.feature.drawer

import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.catchup.CatchUpDriftRenderer
import ai.opencode.feature.drawer.icons.OpenCodeProjectLookup
import ai.opencode.feature.drawer.icons.resolved
import ai.opencode.feature.drawer.list.DrawerListInputs
import ai.opencode.feature.drawer.list.DrawerListItem
import ai.opencode.feature.drawer.list.DrawerListModel
import ai.opencode.feature.drawer.list.DrawerListModelHolder
import ai.opencode.feature.drawer.list.DrawerListSection
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/**
 * The drawer's work, counted so it can't creep back. No list builds while the drawer is hidden, one
 * prewarm build, and the one-pass session and project lookups.
 */
class DrawerPerfGateTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")

    private fun session(id: String, updated: Long, project: String = "p", directory: String = "/project") = OpenCodeSession(
        id = id, serverTitle = id, projectID = project, directory = directory,
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = 2_000, viewedMilliseconds = 1_000,
    )

    private fun home(sessions: List<OpenCodeSession>, projects: List<OpenCodeProject> = emptyList()) = HomeFeature.State(
        server,
        OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = emptySet()),
    )

    private fun build(state: HomeFeature.State) = DrawerListModel.build(
        state = state,
        inputs = DrawerListInputs(selectedSessionID = null, reducedMotion = false, increasedContrast = false),
        liftedID = null,
        now = Instant.ofEpochSecond(100),
        timestamp = { _, _ -> "" },
        project = { _, _ -> null },
    )

    // Drawer list

    /**
     * Status changes while the drawer is shut build nothing; the drawer starting to show builds it
     * once, with everything that changed.
     */
    @Test fun hiddenDrawerBuildsNothingUntilItShows() {
        var state = home((0 until 20).map { session("s$it", updated = it.toLong()) })
        val holder = DrawerListModelHolder()
        holder.model(isHidden = false) { build(state) }
        assertEquals("The visible drawer builds its rows", 1, holder.builds)

        for (index in 0 until 10) {
            state = state.copy(mutedSessionKeys = state.mutedSessionKeys + "${server.persistenceID}|s$index")
            holder.model(isHidden = true) { build(state) }
        }
        assertEquals("Ten status changes on a shut drawer build nothing", 1, holder.builds)

        val model = holder.model(isHidden = false) { build(state) }
        assertEquals("Showing the drawer builds once, for all ten", 2, holder.builds)
        assertTrue("Recents' twenty rows", (model.sections[DrawerListSection.recents]?.size ?: 0) >= 20)
        assertTrue("What changed while hidden lands in place", holder.isRevealing)
    }

    /** A list made inside a shut drawer builds its rows once, so the first open has them. */
    @Test fun shutDrawerPrewarmsItsRowsOnce() {
        val state = home((0 until 8).map { session("s$it", updated = it.toLong()) })
        val holder = DrawerListModelHolder()
        val model = holder.model(isHidden = true) { build(state) }
        assertEquals("Built once, still hidden", 1, holder.builds)
        holder.model(isHidden = true) { build(state) }
        assertEquals(1, holder.builds)
        assertTrue((model.sections[DrawerListSection.recents]?.size ?: 0) >= 8)
        assertFalse("Not a reveal yet", holder.isRevealing)
    }

    /**
     * Catch Up's row is in the list once the drawer shows, whether its sessions turned unread
     * before the rows were built, while the drawer was shut, or with it open.
     */
    @Test fun catchUpRowShowsWithTheDrawer() {
        for (case in listOf("visible", "prewarmed", "whileHidden")) {
            var state = home((0 until 6).map { session("s$it", updated = it.toLong()) })
            val keys = (0 until 6).map { "${server.persistenceID}|s$it" }.toSet()
            if (case == "whileHidden") state = state.copy(mutedSessionKeys = keys)
            val holder = DrawerListModelHolder()
            var model = holder.model(isHidden = case != "visible") { build(state) }
            if (case != "visible") {
                if (case == "whileHidden") {
                    state = state.copy(mutedSessionKeys = emptySet())
                    holder.model(isHidden = true) { build(state) }
                }
                model = holder.model(isHidden = false) { build(state) }
            }
            val top = model.sections[DrawerListSection.top].orEmpty()
            assertTrue("Catch Up above the sections ($case)", top.contains(DrawerListItem.CatchUp))
        }
    }

    // Sessions and projects

    @Test fun drawerSessionsMatchPinsAndRecents() {
        val prefix = server.persistenceID + "|"
        val state = home(
            listOf(session("a", 1), session("b", 5), session("c", 3), session("d", 4)),
        ).copy(pinnedSessionKeys = listOf(prefix + "c", "other|b", prefix + "a"))
        val sections = state.drawerSessions
        assertEquals("This server's pins, in pin order", listOf("c", "a"), sections.pinned.map { it.id })
        assertEquals("The rest, newest first", listOf("b", "d"), sections.recents.map { it.id })
        assertEquals(listOf("c", "a"), state.pinnedSessions.map { it.id })
        assertEquals(listOf("b", "d"), state.recentSessions.map { it.id })
    }

    @Test fun hiddenSessionsMatchTheirKeys() {
        val prefix = server.persistenceID + "|"
        val state = home((0 until 6).map { session("s$it", updated = it.toLong()) })
            .copy(hiddenSessionKeys = setOf(prefix + "s1", prefix + "s4", "other|s2"))
        assertEquals(setOf("s1", "s4"), state.hiddenSessionIDs)
        assertEquals(setOf("s0", "s2", "s3", "s5"), state.visibleSessions.map { it.id }.toSet())
        val model = build(state.copy(search = "s"))
        assertEquals(listOf("s1", "s4"), model.rows.values.filter { it.hidden }.map { it.session.id }.sorted())
    }

    @Test fun projectLookupMatchesResolved() {
        val projects = listOf(
            OpenCodeProject(id = "p1", name = "One", directory = "/one"),
            OpenCodeProject(id = "p2", name = "Two", directory = "/two"),
            OpenCodeProject(id = "p1", name = "Duplicate", directory = "/dup"),
        )
        val sessions = listOf(
            session("a", 1, project = "p1", directory = "/elsewhere"),
            session("b", 1, project = "global", directory = "/two"),
            session("c", 1, project = "global", directory = "/nowhere/folder"),
            session("d", 1, project = "p9", directory = "/dup"),
            session("e", 1, project = "", directory = "/x/y"),
        )
        val lookup = OpenCodeProjectLookup(projects)
        for (session in sessions) {
            assertEquals(session.id, OpenCodeProject.resolved(session, projects), lookup.resolved(session))
        }
    }

    @Test fun driftFieldDrawsAtThreeQuartersDensityAndThirtyFPS() {
        assertEquals(30, CatchUpDriftRenderer.FRAMES_PER_SECOND)
    }
}
