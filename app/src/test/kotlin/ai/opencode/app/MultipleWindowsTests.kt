package ai.opencode.app

import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.Store
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemActionInbox
import ai.opencode.feature.system.SessionWindowTarget
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

class MultipleWindowsTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun onlyTheFirstWindowDoesAppWideWork() {
        assertTrue(AppFeature.Action.SystemActionsTask.isAppWide)
        assertTrue(AppFeature.Action.PendingSharesRequested.isAppWide)
        assertTrue(AppFeature.Action.Home(HomeFeature.Action.SystemPublishRequested(voiceAvailable = true)).isAppWide)
        assertFalse(AppFeature.Action.Home(HomeFeature.Action.SessionTapped("a")).isAppWide)
    }

    @Test
    fun aCallBelongsToOneWindow() {
        val first = RootStore()
        val second = RootStore()
        SceneWindows.noteCall(first, active = true)
        try {
            assertTrue(SceneWindows.callIsElsewhere(second))
            assertFalse(SceneWindows.callIsElsewhere(first))
            SceneWindows.noteCall(second, active = true)
            assertTrue("The first call keeps the line", SceneWindows.callIsElsewhere(second))
            SceneWindows.noteCall(first, active = false)
            assertFalse(SceneWindows.callIsElsewhere(second))
        } finally {
            SceneWindows.noteCall(first, active = false)
        }
    }
}

/**
 * `WindowRoster`: roles follow windows (tasks) connecting and disconnecting, never a window's
 * state going away, so no window is left blank or without the app-wide work.
 */
class WindowRosterTests {
    @get:Rule val isolation = TestIsolationRule()

    class Member : WindowRosterMember {
        var role: WindowRoster.Role? = null
        var ownsAppWide = false

        override fun rosterAssigned(role: WindowRoster.Role) {
            this.role = role
        }

        override fun rosterOwnsAppWideWork(owns: Boolean) {
            ownsAppWide = owns
        }

        fun join(roster: WindowRoster) {
            role = roster.join(this)
            roster.attached(this, replacing = emptyList())
        }
    }

    @Test
    fun anIPhoneSceneDisconnectedAndReconnectedShowsTheApp() {
        val roster = WindowRoster(multipleWindows = false)
        val old = Member()
        old.join(roster)
        assertEquals(WindowRoster.Role.Primary, old.role)
        assertTrue(old.ownsAppWide)
        // Reclaimed in the background; the old window's state is kept.
        roster.detached(old)
        assertFalse(old.ownsAppWide)
        // The app comes back in a new task with new window state.
        val new = Member()
        new.join(roster)
        assertEquals("The new window shows the app, not a blank", WindowRoster.Role.Primary, new.role)
        assertTrue(new.ownsAppWide)
        assertSame(new, roster.primary)
    }

    @Test
    fun anIPhoneSceneReconnectingWithItsOldStateKeepsItsRole() {
        val roster = WindowRoster(multipleWindows = false)
        val window = Member()
        window.join(roster)
        roster.detached(window)
        roster.attached(window, replacing = emptyList())
        assertEquals(WindowRoster.Role.Primary, window.role)
        assertTrue(window.ownsAppWide)
    }

    @Test
    fun anIPhoneReloadNeverMakesASecondWindow() {
        val roster = WindowRoster(multipleWindows = false)
        val old = Member()
        old.join(roster)
        // New state for the one window, while the old is still connected.
        val new = Member()
        assertEquals("One window: never a secondary with a store of its own", WindowRoster.Role.Primary, roster.join(new))
        assertEquals(1, roster.members.size)
        assertTrue(new.ownsAppWide)
    }

    @Test
    fun aStaleWindowReattachingAfterANewOneDoesNotTakeTheAppBack() {
        val roster = WindowRoster(multipleWindows = true)
        val old = Member()
        old.join(roster)
        roster.detached(old)
        val new = Member()
        new.join(roster)
        assertEquals(WindowRoster.Role.Primary, new.role)
        roster.attached(old, replacing = emptyList())
        assertEquals(WindowRoster.Role.Secondary, old.role)
        assertEquals(WindowRoster.Role.Primary, new.role)
        assertTrue(new.ownsAppWide && !old.ownsAppWide)
    }

    @Test
    fun closingTheFirstIPadWindowHandsTheAppWideWorkOver() {
        val roster = WindowRoster(multipleWindows = true)
        val first = Member()
        val second = Member()
        first.join(roster)
        second.join(roster)
        assertEquals(WindowRoster.Role.Secondary, second.role)
        assertTrue(first.ownsAppWide && !second.ownsAppWide)
        roster.detached(first)
        assertTrue("The survivor takes the inbox, shares and publishing", second.ownsAppWide)
        // Reopened: the new window shows the app's store again.
        val reopened = Member()
        reopened.join(roster)
        assertEquals(WindowRoster.Role.Primary, reopened.role)
        assertTrue(reopened.ownsAppWide && !second.ownsAppWide)
    }

    @Test
    fun aReloadReplacesTheWindowStateInTheSameScene() {
        val roster = WindowRoster(multipleWindows = true)
        val old = Member()
        old.join(roster)
        val new = Member()
        new.role = roster.join(new)
        assertEquals(WindowRoster.Role.Secondary, new.role)
        roster.attached(new, replacing = listOf(old))
        assertEquals(WindowRoster.Role.Primary, new.role)
        assertTrue(new.ownsAppWide)
        assertEquals(1, roster.members.size)
    }

    @Test
    fun aWindowWhoseStateWentAwayLeaves() {
        val roster = WindowRoster(multipleWindows = true)
        val first = Member()
        val second = Member()
        first.join(roster)
        second.join(roster)
        // Android removes the released member explicitly: a ViewModel isn't released at
        // `onCleared`.
        roster.left(first)
        assertEquals(WindowRoster.Role.Secondary, second.role)
        assertTrue(second.ownsAppWide)
    }
}

/** The target rides an `Intent` (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MultipleWindowsFrameworkTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun windowTargetRoundTripsThroughItsActivity() {
        val target = SessionWindowTarget(serverID = "server", sessionID = "ses_1")
        val intent = SceneWindows.intent(RuntimeEnvironment.getApplication(), target)
        assertEquals(SessionWindowTarget.ACTION, intent.action)
        assertEquals(target, RootStore.target(intent, saved = null))
        assertNull(RootStore.target(Intent("other"), saved = null))
        // Restored after a relaunch: the window's saved value.
        val saved = Bundle().apply {
            putString(SessionWindowTarget.EXTRA_SERVER_ID, target.serverID)
            putString(SessionWindowTarget.EXTRA_SESSION_ID, target.sessionID)
        }
        assertEquals(target, RootStore.target(null, saved))
    }

    @Test
    fun aLongAbsenceSkipsTheWindowsSavedSession() {
        val shared = AppTestShared()
        val target = SessionWindowTarget(serverID = "server", sessionID = "ses_1")
        val saved = Bundle().apply {
            putString(SessionWindowTarget.EXTRA_SERVER_ID, target.serverID)
            putString(SessionWindowTarget.EXTRA_SESSION_ID, target.sessionID)
        }
        shared.run {
            shared(SharedKeys.appLeftAt).set(Instant.EPOCH - HomeFeature.newSessionAfterAway)
            assertNull("Restored after a long absence: a new session", RootStore.target(null, saved))
            val intent = SceneWindows.intent(RuntimeEnvironment.getApplication(), target)
            assertEquals("Open in New Window still opens its session", target, RootStore.target(intent, saved))
        }
    }
}

/**
 * [AppWideWork] with real window stores. Robolectric, because a [Store] sends on Android's main
 * looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AppWideWorkTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val inbox = SystemActionInbox()
    private val handled = Locked<List<String>>(emptyList())
    private val listens = Locked(0)
    private val made = Locked(0)
    private val stores = ArrayList<Store<AppFeature.State, AppFeature.Action>>()

    @After fun closeStores() {
        stores.forEach { it.close() }
        idle()
    }

    /** `AppFeature`, noting which store each intent reached. */
    private fun recording(name: String) = object : Reducer<AppFeature.State, AppFeature.Action> {
        val base = AppFeature()
        override fun reduce(state: AppFeature.State, action: AppFeature.Action): Reduced<AppFeature.State, AppFeature.Action> {
            if (action is AppFeature.Action.SystemActionReceived) handled.update { it + name }
            return base.reduce(state, action)
        }
    }

    private fun store(name: String, state: AppFeature.State = shared.appState()): Store<AppFeature.State, AppFeature.Action> =
        Store(
            state,
            recording(name),
            dependencies = shared.values.update {
                this[SystemIntegrationClient] = SystemIntegrationClient(
                    actions = {
                        listens.update { it + 1 }
                        inbox.actions()
                    },
                    publish = { _, _ -> },
                    removeServer = {},
                )
            },
        ).also { stores += it }

    private fun window(roster: WindowRoster, work: AppWideWork, secondary: String = "secondary") =
        RootStore(roster, work) { _, _ ->
            made.update { it + 1 }
            store(secondary)
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Posts an intent once the app-wide work has moved, and waits until a store handles it. */
    private fun intent() {
        idle()
        val before = handled.value.size
        inbox.post(SystemAction(serverKey = null, destination = SystemAction.Destination.LastViewed))
        repeat(100) {
            if (handled.value.size != before) return
            idle()
        }
    }

    @Test
    fun closingAndReopeningTheFirstIPadWindowMovesTheInboxBothWays() {
        val roster = WindowRoster(multipleWindows = true)
        val work = AppWideWork(roster)
        val primary = store("primary")
        val first = window(roster, work)
        val second = window(roster, work)
        first.resolve(null) { primary }
        second.resolve(null) { primary }
        assertEquals(WindowRoster.Role.Secondary, second.role)
        intent()

        // The first window closes: an App Action still lands, in the survivor.
        roster.detached(first)
        intent()

        // Reopened from the launcher: the new window shows the app's store, and the inbox goes with it.
        val reopened = window(roster, work)
        reopened.resolve(null) { primary }
        assertEquals(WindowRoster.Role.Primary, reopened.role)
        intent()

        assertEquals(listOf("primary", "secondary", "primary"), handled.value)
    }

    @Test
    fun aReloadKeepsTheInboxInThePrimaryStoreWithoutRestartingIt() {
        val roster = WindowRoster(multipleWindows = true)
        val work = AppWideWork(roster)
        val primary = store("primary")
        val window = window(roster, work)
        window.resolve(null) { primary }
        intent()

        // A configuration change: the retained window resolves again for its new activity.
        assertSame(primary, window.resolve(null) { store("reloaded") })
        assertEquals(WindowRoster.Role.Primary, window.role)
        intent()

        assertEquals(listOf("primary", "primary"), handled.value)
        assertEquals("The work carried on; no view going away stranded or restarted it", 1, listens.value)
    }

    @Test
    fun theInboxListensFromTheFirstWindowAndThroughADisconnect() {
        val roster = WindowRoster(multipleWindows = false)
        val work = AppWideWork(roster)
        val primary = store("primary")
        val window = window(roster, work)
        window.resolve(null) { primary }
        assertSame("Listening as soon as the window joins, not on a later turn", primary, work.store)
        // Disconnected before anything else ran: no owner now, but the work stays.
        roster.detached(window)
        intent()
        assertEquals("An intent running in the background is handled with no window connected", listOf("primary"), handled.value)
        assertEquals(1, listens.value)
    }

    @Test
    fun aNotificationNeverGoesToAClosedWindow() {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
        val session = OpenCodeSession(id = "ses_shown", serverTitle = "Shown", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(300))
        val home = shared.run {
            HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(session), activeSessionIDs = emptySet()))
                .copy(sessionDetail = SessionFeature.State.session(server, session))
        }
        val showing = Store(shared.appState().copy(home = home), AppFeature(), dependencies = shared.values).also { stores += it }

        val roster = WindowRoster(multipleWindows = true)
        val work = AppWideWork(roster)
        val primary = store("primary")
        val first = window(roster, work)
        val second = RootStore(roster, work) { _, _ -> showing }
        first.resolve(null) { primary }
        second.resolve(null) { primary }
        val destination = NotificationDestination(serverKey = server.persistenceID, sessionID = session.id)
        assertSame(showing, SceneWindows.window(destination, roster, work, active = null)?.store)

        // Closed, its state kept. The tap goes to the window doing the app-wide work.
        roster.detached(second)
        assertSame(primary, SceneWindows.window(destination, roster, work, active = null)?.store)
    }

    @Test
    fun anIPhoneKeepsOneStoreThroughADisconnectAndReturn() {
        val roster = WindowRoster(multipleWindows = false)
        val work = AppWideWork(roster)
        val primary = store("primary")
        val old = window(roster, work)
        old.resolve(null) { primary }
        roster.detached(old)
        intent()

        val new = window(roster, work)
        assertSame(primary, new.resolve(null) { primary })
        intent()

        assertEquals(listOf("primary", "primary"), handled.value)
        assertEquals("A phone never makes a second store", 0, made.value)
        assertEquals(1, listens.value)
    }
}
