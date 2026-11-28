package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.log.CallTrigger
import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.isDraft
import ai.opencode.feature.session.withDraft
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SystemIntegrationTests {
    @get:Rule val isolation = TestIsolationRule()

    private companion object {
        val server = OpenCodeServer(url = "https://saved.example", username = "kit", password = "test")
        val serverKey = SessionDirectory.serverKey(server.persistenceID)
        val project = OpenCodeProject(id = "prj_app", name = "app", directory = "/work/app")
        val docs = OpenCodeProject(id = "prj_docs", name = "docs", directory = "/work/docs")
        val older = OpenCodeSession(id = "ses_older", serverTitle = "Older work", projectID = "prj_docs", directory = "/work/docs", updatedAt = Instant.ofEpochSecond(100))
        val newer = OpenCodeSession(id = "ses_newer", serverTitle = "Fix login", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(300))
        val loose = OpenCodeSession(id = "ses_loose", serverTitle = "Scratch", projectID = "global", directory = "/", updatedAt = Instant.ofEpochSecond(200))

        fun snapshot(projects: List<OpenCodeProject>, sessions: List<OpenCodeSession>) =
            OpenCodeHomeSnapshot(projects = projects, sessions = sessions, activeSessionIDs = emptySet())
    }

    private fun opened(
        destination: SystemAction.Destination,
        startsCall: Boolean,
        inBackground: Boolean = false,
        origin: SystemActionOrigin = SystemActionOrigin.other,
    ) = HomeFeature.Action.SystemActionOpened(destination, startsCall = startsCall, inBackground = inBackground, origin = origin)

    private val voiceTapped = HomeFeature.Action.detail(SessionFeature.Action.VoiceTapped)

    private fun HomeFeature.State.detail(update: (SessionFeature.State) -> SessionFeature.State) = copy(sessionDetail = update(sessionDetail!!))

    // region SessionDirectoryTests

    @Test
    fun homeBuildsItsEntryFromTheListPinsAndLastViewed() {
        val child = OpenCodeSession(
            id = "ses_child", serverTitle = "Subagent", projectID = "prj_app", directory = "/work/app",
            updatedAt = Instant.ofEpochSecond(400), parentID = "ses_newer",
        )
        var state = HomeFeature.State(server, snapshot(listOf(project, docs), listOf(older, newer, loose, child)))
        state = state.copy(
            pinnedSessionKeys = listOf(state.sessionKey("ses_older"), "elsewhere|ses_newer"),
            lastViewedSessionIDs = mapOf(server.persistenceID to "ses_loose"),
        )

        val entry = state.systemPublication.systemDirectoryEntry
        assertEquals(serverKey, entry.key)
        // The server's name, as the drawer shows it: never an address.
        assertEquals("Saved", entry.name)
        val unnamed = HomeFeature.State(
            OpenCodeServer(url = "http://192.168.1.20:4096", username = "kit", password = "test", name = null),
            snapshot(emptyList(), emptyList()),
        )
        assertEquals("Server 20", unnamed.systemPublication.systemDirectoryEntry.name)
        assertEquals(listOf("ses_newer", "ses_loose", "ses_older"), entry.sessions.map { it.id })
        assertEquals(listOf("app", null, "docs"), entry.sessions.map { it.projectName })
        assertEquals(listOf("ses_older"), entry.pinnedIDs)
        assertEquals("ses_loose", entry.lastViewedSessionID)
        assertEquals(listOf("prj_app", "prj_docs"), entry.projects.map { it.id })
    }

    @Test
    fun entryNeverCarriesMessageText() {
        var state = HomeFeature.State(server, snapshot(listOf(project), listOf(newer)))
        state = state.copy(transcriptCache = state.transcriptCache + (newer.id to emptyList()))
        val json = Json.encodeToString(SessionDirectory.Server.serializer(), state.systemPublication.systemDirectoryEntry)
        assertFalse(json.contains(server.password))
        assertFalse(json.contains(server.url))
        val keys = Json.parseToJsonElement(json).jsonObject.keys
        assertTrue(keys.toString(), setOf("key", "name", "lastViewedSessionID", "pinnedIDs", "sessions", "projects").containsAll(keys))
    }

    @Test
    fun aRunOnTheNewestSessionChangesNoEntryOrShareTargets() {
        val oldest = OpenCodeSession(id = "ses_c", serverTitle = "ses_c", projectID = "prj_app", directory = "/work/app", updatedAt = Instant.ofEpochSecond(50))
        var state = HomeFeature.State(server, snapshot(listOf(project, docs), listOf(newer, older, oldest)))
        val entry = state.systemPublication.systemDirectoryEntry
        val targets = state.shareTargetsPublication.targets()
        // A `session.updated` during a run: the session's time moves on.
        fun touched(state: HomeFeature.State, id: String, seconds: Long) =
            state.copy(sessions = state.sessions.map { if (it.id == id) it.copy(updatedAt = Instant.ofEpochSecond(seconds)) else it })
        for (tick in 1..20) {
            state = touched(state, "ses_newer", 300L + tick)
            assertEquals(entry, state.systemPublication.systemDirectoryEntry)
            assertEquals(targets, state.shareTargetsPublication.targets())
        }
        // Moving another session up the list changes both.
        val moved = touched(state, "ses_c", 1_000)
        assertEquals(listOf("ses_c", "ses_newer", "ses_older"), moved.systemPublication.systemDirectoryEntry.sessions.map { it.id })
        assertEquals(listOf("ses_c", "ses_newer", "ses_older"), moved.shareTargetsPublication.targets().recents.map { it.id })
    }

    // endregion

    // region SystemRoutingTests

    private val context = HomeTestContext()

    private fun home(lastViewed: String? = null, voiceConfigured: Boolean = false): HomeFeature.State {
        if (voiceConfigured) {
            context.read {
                shared(SharedKeys.voiceModeEnabled).set(true)
                shared(SharedKeys.openAIAPIKey).set("sk-test")
            }
        }
        val state = HomeFeature.State(server, snapshot(listOf(project, docs), listOf(older, newer)))
        return state
            .copy(lastViewedSessionIDs = if (lastViewed == null) emptyMap() else mapOf(server.persistenceID to lastViewed))
            .openingDraft()
    }

    private fun homeStore(
        scope: kotlinx.coroutines.CoroutineScope,
        initialState: () -> HomeFeature.State,
        dependencies: ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {},
    ): TestStore<HomeFeature.State, HomeFeature.Action> = context.store(scope, initialState, dependencies)

    @Test
    fun openingASessionTapsItLikeTheDrawer() = runTest {
        val store = homeStore(backgroundScope, { home() }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> older }, transcript = hangingTranscript)
        }
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.Session("ses_older"), startsCall = false))
        store.receive(HomeFeature.Action.SessionTapped("ses_older"))
        store.receive(HomeFeature.Action.SessionNavigationReady("ses_older"))
        assertEquals("ses_older", store.state.sessionDetail?.session?.id)
    }

    @Test
    fun lastViewedOpensTheRememberedSessionElseTheNewest() = runTest {
        val remembered = homeStore(backgroundScope, { home(lastViewed = "ses_older") }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> older }, transcript = hangingTranscript)
        }
        remembered.exhaustive = false
        remembered.send(opened(SystemAction.Destination.LastViewed, startsCall = false))
        remembered.receive(HomeFeature.Action.SessionTapped("ses_older"))

        val fresh = homeStore(backgroundScope, { home() }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> newer }, transcript = hangingTranscript)
        }
        fresh.exhaustive = false
        fresh.send(opened(SystemAction.Destination.LastViewed, startsCall = false))
        fresh.receive(HomeFeature.Action.SessionTapped("ses_newer"))
    }

    @Test
    fun aDraftStartsInTheNamedProject() = runTest {
        val store = homeStore(this, {
            home().copy(sessionDetail = SessionFeature.State.session(server = server, session = newer, cachedItems = emptyList()))
                .showingSettings()
        })
        assertTrue(store.state.isSettingsShown)
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.Draft(projectID = "prj_docs"), startsCall = false))
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertEquals(docs, store.state.sessionDetail?.draftProject)
        assertFalse(store.state.isSettingsShown)
    }

    @Test
    fun anOpenDraftKeepsItsText() = runTest {
        val store = homeStore(this, { home().detail { it.withDraft("Half typed") } })
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.Draft(projectID = null), startsCall = false))
        assertEquals("Half typed", store.state.sessionDetail?.draft)
    }

    @Test
    fun aCallOpensTheSessionAndPressesTheCallButton() = runTest {
        val store = homeStore(backgroundScope, { home(lastViewed = "ses_older", voiceConfigured = true) }) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = TestClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> older }, transcript = hangingTranscript)
        }
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.LastViewed, startsCall = true))
        assertEquals("ses_older", store.state.sessionDetail?.session?.id)
        store.receive(voiceTapped)
        assertEquals("ses_older", store.state.sessionDetail?.voice?.target?.sessionID)
        assertEquals("ses_older", store.state.lastViewedSessionIDs[server.persistenceID])
    }

    @Test
    fun aBackgroundCallStartsWithoutItsViewAppearing() = runTest {
        val store = homeStore(backgroundScope, { home(lastViewed = "ses_older", voiceConfigured = true) }) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = TestClock()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> older }, transcript = hangingTranscript)
        }
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.LastViewed, startsCall = true, inBackground = true))
        store.receive(voiceTapped)
        store.receive(HomeFeature.Action.detail(SessionFeature.Action.voicePresented(VoiceFeature.Action.StartIfNeeded)))
        assertEquals("ses_older", store.state.sessionDetail?.voice?.target?.sessionID)
    }

    @Test
    fun aCallInANewSessionCreatesAnEmptySessionForIt() = runTest {
        val store = homeStore(backgroundScope, { home(voiceConfigured = true) }) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = TestClock()
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(
                createSession = { _, directory, id ->
                    OpenCodeSession(id = id ?: "", serverTitle = "", projectID = "p", directory = directory, updatedAt = Instant.EPOCH)
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.Draft(projectID = null), startsCall = true))
        store.receive(voiceTapped)
        assertNotNull(store.state.sessionDetail?.voice)
        store.receive({ it.detailAction is SessionFeature.Action.SessionCreated }, "sessionDetail.sessionCreated")
        store.receive({ it.voiceAction is VoiceFeature.Action.DraftSessionCreated }, "sessionDetail.voice.draftSessionCreated")
        val sessionID = store.state.sessionDetail?.session?.id
        assertEquals(true, sessionID?.startsWith("ses_"))
        assertEquals(sessionID, store.state.sessionDetail?.voice?.target?.sessionID)
    }

    @Test
    fun aCallWithoutVoiceSetUpOpensSettings() = runTest {
        val store = homeStore(backgroundScope, {
            shared(SharedKeys.voiceModeEnabled).set(false)
            home()
        }) {
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(getSession = { _, _ -> newer }, transcript = hangingTranscript)
        }
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.Session("ses_newer"), startsCall = true))
        assertEquals("ses_newer", store.state.sessionDetail?.session?.id)
        assertNull(store.state.sessionDetail?.voice)
        assertTrue(store.state.isSettingsShown)
    }

    @Test
    fun anUnlistedSessionIsIgnoredByHome() = runTest {
        val store = homeStore(this, { home() })
        store.send(opened(SystemAction.Destination.Session("ses_unknown"), startsCall = false))
        store.finish()
    }

    // endregion

    // region SystemColdLaunchTests

    private val launchSnapshot = snapshot(listOf(project, docs), listOf(older, newer))

    private fun unloadedHome() = HomeFeature.State(server, snapshot(emptyList(), emptyList()), loaded = false)

    @Test
    fun nothingIsPublishedUntilTheFirstSnapshot() = runTest {
        val published = Collections.synchronizedList(mutableListOf<SessionDirectory.Server>())
        val store = homeStore(backgroundScope, { unloadedHome() }) {
            this[SystemIntegrationClient] = SystemIntegrationClient(
                actions = { kotlinx.coroutines.flow.emptyFlow() },
                publish = { entry, _ -> published += entry },
                removeServer = {},
            )
            this[OpenCodeClient] = OpenCodeClient.testValue.copy(homeAttention = { _, _, _ -> emptySet() }, transcript = { _, _, _ -> emptyList() })
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.SystemPublishRequested(voiceAvailable = true))
        assertTrue(published.isEmpty())
        store.send(HomeFeature.Action.SnapshotRefreshed(launchSnapshot))
        assertEquals(listOf("ses_newer", "ses_older"), published.lastOrNull()?.sessions?.map { it.id })
    }

    @Test
    fun anUnloadedListKeepsTheRememberedSession() {
        val state = unloadedHome()
            .copy(lastViewedSessionIDs = mapOf(server.persistenceID to "ses_older"))
            .restoringInitialSession()
        assertEquals("ses_older", state.lastViewedSessionIDs[server.persistenceID])
        assertEquals(true, state.sessionDetail?.isDraft)
    }

    @Test
    fun aDraftForAMissingProjectAsksForOne() = runTest {
        val store = homeStore(this, {
            HomeFeature.State(server, launchSnapshot)
                .copy(sessionDetail = SessionFeature.State.session(server = server, session = newer, cachedItems = emptyList()))
        })
        store.exhaustive = false
        store.send(opened(SystemAction.Destination.Draft(projectID = "prj_gone"), startsCall = false))
        assertEquals(true, store.state.sessionDetail?.isDraft)
        assertNull(store.state.sessionDetail?.draftProject)
    }

    // endregion

    // region SystemVoiceAvailabilityTests

    @Test
    fun settingUpVoiceRepublishesTheQuickActions() = runTest {
        val published = Collections.synchronizedList(mutableListOf<Boolean>())
        val store = homeStore(this, { HomeFeature.State(server, snapshot(listOf(project), listOf(newer))) }) {
            this[SystemIntegrationClient] = SystemIntegrationClient(
                actions = { kotlinx.coroutines.flow.emptyFlow() },
                publish = { _, voice -> published += voice },
                removeServer = {},
            )
        }
        // The view's first appearance, then Settings turning Voice on.
        store.send(HomeFeature.Action.SystemPublishRequested(voiceAvailable = false))
        store.send(HomeFeature.Action.SystemPublishRequested(voiceAvailable = true)) {
            it.copy(systemVoiceAvailable = true)
        }
        assertEquals(listOf(false, true), published.toList())
        store.finish()
    }

    @Test
    fun renamingTheServerInPlaceRepublishesItsName() = runTest {
        // AppFeature persists the name; Home names its own server so the directory's watcher
        // sees the change. Kept to Home, so the test doesn't write the saved servers other tests read.
        val published = Collections.synchronizedList(mutableListOf<String>())
        val store = homeStore(this, { HomeFeature.State(server, snapshot(listOf(project), listOf(newer))) }) {
            this[SystemIntegrationClient] = SystemIntegrationClient(
                actions = { kotlinx.coroutines.flow.emptyFlow() },
                publish = { entry, _ -> published += entry.name },
                removeServer = {},
            )
        }
        val renamed = server.copy(name = "Work Mac")
        store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerRenamed(server, "  Work Mac "))) {
            it.withServer(renamed)
        }
        assertEquals(listOf("Work Mac"), published.toList())
        store.send(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerRenamed(renamed, ""))) {
            it.withServer(server)
        }
        assertEquals(listOf("Work Mac", server.friendlyName), published.toList())
        store.finish()
    }

    // endregion

    // region SystemCallTests

    private fun callStore(
        scope: kotlinx.coroutines.CoroutineScope,
        voiceModeEnabled: Boolean = true,
        detail: () -> SessionFeature.State,
    ): TestStore<HomeFeature.State, HomeFeature.Action> {
        val store = homeStore(scope, {
            shared(SharedKeys.voiceModeEnabled).set(voiceModeEnabled)
            shared(SharedKeys.openAIAPIKey).set("sk-test")
            HomeFeature.State(server, snapshot(listOf(project, docs), listOf(older, newer))).copy(sessionDetail = detail())
        }) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            this[Clock] = TestClock()
        }
        store.exhaustive = false
        return store
    }

    @Test
    fun anEndedCallOnTheTargetIsClearedSoTheNewOneStarts() = runTest {
        val store = callStore(backgroundScope) {
            SessionFeature.State.session(server = server, session = newer, cachedItems = emptyList())
                .copy(voice = VoiceFeature.State(server, newer).withPhase(VoiceFeature.State.Phase.Ended))
        }
        store.send(opened(SystemAction.Destination.Session("ses_newer"), startsCall = true))
        store.receive(voiceTapped)
        assertEquals(false, store.state.sessionDetail?.voice?.phase?.isTerminal)
    }

    @Test
    fun aSubagentOnScreenGivesWayToItsSession() = runTest {
        val child = OpenCodeSession(
            id = "ses_child", serverTitle = "Subagent", projectID = "prj_app", directory = "/work/app",
            updatedAt = Instant.now(), parentID = "ses_newer",
        )
        val store = callStore(backgroundScope) {
            val detail = SessionFeature.State.session(server = server, session = newer, cachedItems = emptyList()).copy(session = child)
            assertEquals("ses_newer", detail.familyRootID)
            detail
        }
        store.send(opened(SystemAction.Destination.Session("ses_newer"), startsCall = true))
        assertEquals("ses_newer", store.state.sessionDetail?.session?.id)
        store.receive(voiceTapped)
        assertEquals("ses_newer", store.state.sessionDetail?.voice?.target?.sessionID)
    }

    /** Runs [body], returning the call triggers it logged. */
    private suspend fun triggers(body: suspend () -> Unit): List<CallTrigger> {
        val seen = Collections.synchronizedList(mutableListOf<CallTrigger>())
        CallTrigger.observing({ seen += it }) { body() }
        return seen.toList()
    }

    @Test
    fun aSystemCallLogsItsTriggerOnlyOnceItStarts() = runTest {
        val store = callStore(backgroundScope) {
            SessionFeature.State.session(server = server, session = newer, cachedItems = emptyList())
        }
        val started = triggers {
            store.send(opened(SystemAction.Destination.Session("ses_newer"), startsCall = true, origin = SystemActionOrigin.quickAction))
            store.receive(voiceTapped)
        }
        assertEquals(listOf<CallTrigger>(CallTrigger.System(SystemActionOrigin.quickAction)), started)
        assertNotNull(store.state.sessionDetail?.voice)

        // A second request while that call is live starts nothing, so logs nothing.
        val again = triggers {
            store.send(opened(SystemAction.Destination.Session("ses_newer"), startsCall = true, origin = SystemActionOrigin.control))
        }
        assertTrue(again.isEmpty())
    }

    @Test
    fun aSystemCallThatOpensSettingsInsteadLogsNothing() = runTest {
        val store = callStore(backgroundScope, voiceModeEnabled = false) {
            SessionFeature.State.session(server = server, session = newer, cachedItems = emptyList())
        }
        val logged = triggers {
            store.send(opened(SystemAction.Destination.Session("ses_newer"), startsCall = true, origin = SystemActionOrigin.shortcut))
        }
        assertTrue(store.state.isSettingsShown)
        assertTrue(logged.isEmpty())
        val unresolved = triggers {
            store.send(opened(SystemAction.Destination.Session("ses_missing"), startsCall = true, origin = SystemActionOrigin.shortcut))
        }
        assertTrue(unresolved.isEmpty())
    }

    // endregion
}
