package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.system.liveactivity.DictationActivityAttributes
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.session.DraftProjectOrder
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.currentLevelScale
import ai.opencode.feature.session.currentModelName
import ai.opencode.feature.session.recentProjects
import ai.opencode.feature.session.setTyped
import ai.opencode.feature.session.taskIdentity
import ai.opencode.feature.session.typeBelow
import ai.opencode.core.store.PresentationAction
import ai.opencode.feature.settings.SettingsFeature.SettingsDelegate
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.whatsnew.WhatsNewEntry
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NewSessionDraftTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private val projects = (1..7).map { OpenCodeProject(id = "p$it", name = "project-$it", directory = "/srv/code/project-$it") }

    /** Nothing in these tests may create a session or send a prompt. */
    private fun noSends(client: OpenCodeClient) = client.copy(
        createSession = { _, _, _ -> throw AssertionError("Choosing a project must not create a session") },
        sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Choosing a project must not send") },
    )

    @Test
    fun aNewDraftVisitRestoresCurrentProjectRecency() {
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = projects, sessions = emptyList(), activeSessionIDs = emptySet()))
        // Home keeps the state it returns.
        val first = home.makeDraft().let { (next, draft) -> home = next; draft }
        var order = DraftProjectOrder()
        order = order.update(first.recentProjects, chosen = first.draftProject, identity = first.taskIdentity)
        val original = order.projects

        home = home.copy(
            sessions = listOf(
                OpenCodeSession(
                    id = "ses_recent", serverTitle = "Recent", projectID = projects[6].id,
                    directory = projects[6].directory, updatedAt = Instant.ofEpochSecond(100),
                ),
            ),
        )
        val next = home.makeDraft().let { (state, draft) -> home = state; draft }
        assertNotEquals(first.taskIdentity, next.taskIdentity)
        // A refresh within the original visit must still leave its slots alone.
        order = order.update(next.recentProjects, chosen = null, identity = first.taskIdentity)
        assertEquals(original, order.projects)
        order = order.update(next.recentProjects, chosen = next.draftProject, identity = next.taskIdentity)
        assertEquals(next.recentProjects, order.projects)
        assertNotEquals(original, order.projects)
    }

    @Test
    fun aDraftTheVoiceAgentOpensInAFolderStartsThere() {
        val project = OpenCodeProject(id = "p1", name = "project-1", directory = "/srv/code/project-1")
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(project), sessions = emptyList(), activeSessionIDs = emptySet()))
        val known = home.makeDraft(directory = "/srv/code/project-1").second
        assertEquals(project, known.draftProject)
        val folder = home.makeDraft(directory = "/srv/scratch").second
        assertEquals(
            "Not the first project",
            OpenCodeProject(id = "folder:/srv/scratch", name = "scratch", directory = "/srv/scratch"),
            folder.draftProject,
        )
        assertEquals(project, home.makeDraft().second.draftProject)
    }

    /**
     * Every entry point that opens a fresh draft from an existing draft advances `taskIdentity`
     * so the screen's task runs once and loads `models`.
     */
    @Test
    fun everyDraftFromADraftGetsAFreshTaskIdentityAndLoadsModels() = runTest {
        val model = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4", variants = listOf("low", "high"))
        val prior = OpenCodeSession(
            id = "ses_prior", serverTitle = "Prior session", projectID = projects[0].id, directory = projects[0].directory,
            updatedAt = Instant.EPOCH, model = model.ref("high"),
        )
        val catalog = OpenCodeModelCatalog(models = listOf(model), defaultModel = model)
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listOf(projects[0], projects[1]), sessions = listOf(prior), activeSessionIDs = emptySet()))
        val initialIdentity = home.sessionDetail?.taskIdentity
        assertEquals(SessionFeature.State.TaskIdentity(serverID = server.persistenceID, visit = 1, sessionID = null), initialIdentity)
        assertNull("Not the prior session’s model: the server’s default comes with the catalog", home.sessionDetail?.currentModel)
        assertEquals("Model", home.sessionDetail?.currentModelName)

        val store = TestStore(home, HomeFeature(), this) {
            this[OpenCodeClient] = noSends(OpenCodeClient.testValue).copy(modelCatalog = { _, _ -> catalog })
        }
        fun HomeFeature.State.detail(update: (SessionFeature.State) -> SessionFeature.State) = copy(sessionDetail = update(sessionDetail!!))
        suspend fun runTaskAndLoadModels() {
            store.send(HomeFeature.Action.detail(SessionFeature.Action.Task)) { it.detail { d -> d.copy(isLoadingModels = true) } }
            store.receive(HomeFeature.Action.detail(SessionFeature.Action.AgentsRequested))
            store.receive(
                { it == HomeFeature.Action.detail(SessionFeature.Action.ModelsResponse(Result.success(catalog))) },
                "modelsResponse.success",
            ) {
                it.detail { d ->
                    d.copy(isLoadingModels = false, models = listOf(model), currentModel = model.ref, agentSelection = d.agentSelection.copy(defaultModel = model))
                }
            }
            store.receive(HomeFeature.Action.detail(SessionFeature.Action.AgentsResponse(Result.success(emptyList()))))
            assertEquals(ModelDisplayNames(listOf(model)), store.state.sessionDetail?.modelDisplayNamesByServer?.value?.get(server.persistenceID))
            assertEquals("Cirrus 4", store.state.sessionDetail?.currentModelName)
            assertNotNull(store.state.sessionDetail?.currentLevelScale)
        }
        // Built with the store's dependencies: the screen's `Shared` values are its store's references.
        fun draft(project: OpenCodeProject, listed: List<OpenCodeProject>, navigationID: Int) = store.withDependencies {
            SessionFeature.State.draft(
                server = server, draftProject = project, projects = listed, navigationID = navigationID,
            )
        }

        // 1. Initial draft runs .task and loads models.
        runTaskAndLoadModels()

        // 2. New Session from a draft gets a new taskIdentity and loads models when .task runs.
        store.send(HomeFeature.Action.NewSessionTapped) {
            it.copy(detailGeneration = 2, sessionDetail = draft(projects[0], listOf(projects[0], projects[1]), 2))
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 2, null), store.state.sessionDetail?.taskIdentity)
        assertNotEquals(initialIdentity, store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()

        // 3. What's New "Try It" from a draft gets a new taskIdentity and loads models. On Android
        // What's New lives in Settings, so Try It reaches Home as Settings' `TryWhatsNew` delegate.
        store.exhaustive = false
        store.send(HomeFeature.Action.WhatsNew(WhatsNewFeature.Action.ButtonTapped))
        store.exhaustive = true
        val tryIt = SettingsFeature.Action.Delegate(SettingsDelegate.TryWhatsNew(WhatsNewEntry.Destination.Prompt("Try this"), "math-and-diagrams"))
        store.send(HomeFeature.Action.SettingsScreen(PresentationAction.Presented(tryIt))) {
            it.copy(
                settings = null,
                drawerCloseRequests = 1,
                detailGeneration = 3,
                sessionDetail = draft(projects[0], listOf(projects[0], projects[1]), 3).setTyped("Try this"),
            )
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 3, null), store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()

        // 4. Notify plugin "Set Up" over a draft keeps what's typed there and adds its prompt below.
        store.send(HomeFeature.Action.NotifyPluginSetUpTapped) {
            it.copy(drawerCloseRequests = it.drawerCloseRequests + 1).detail { detail -> detail.typeBelow(HomeFeature.notifyPluginSetUpPrompt) }
        }
        assertTrue(store.state.sessionDetail?.draft?.startsWith("Try this") == true)
        assertTrue(store.state.sessionDetail?.draft?.contains(OpenCodeNotifyPluginStatus.PACKAGE_ENTRY) == true)
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 3, null), store.state.sessionDetail?.taskIdentity)

        // 5. Voice/deep-link openDraft from a draft gets a new taskIdentity and loads models.
        store.send(HomeFeature.Action.detail(SessionFeature.Action.Delegate(SessionFeature.Delegate.OpenDraft(projects[1].directory)))) {
            it.copy(detailGeneration = 4, sessionDetail = draft(projects[1], listOf(projects[1], projects[0]), 4))
        }
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 4, null), store.state.sessionDetail?.taskIdentity)
        runTaskAndLoadModels()
        store.finish()
    }

    /**
     * When a live call or carried dictation keeps `navigationID` unchanged (`keepsIdentity`),
     * `visit` still advances on draft → draft and draft-created session → draft, so `taskIdentity`
     * changes, the task runs `stopSessionWork`, and `models` loads for the new draft.
     */
    @Test
    fun keepsIdentityMovesAdvanceVisitAndRunTask() = runTest {
        val model = OpenCodeModel(modelID = "cirrus-4", providerID = "opencode", name = "Cirrus 4", variants = listOf("low", "high"))
        val prior = OpenCodeSession(
            id = "ses_prior", serverTitle = "Prior session", projectID = projects[0].id, directory = projects[0].directory,
            updatedAt = Instant.EPOCH, model = model.ref("high"),
        )
        val created = OpenCodeSession(
            id = "ses_created", serverTitle = "Created session", projectID = projects[0].id, directory = projects[0].directory,
            updatedAt = Instant.EPOCH, model = model.ref("high"),
        )
        val catalog = OpenCodeModelCatalog(models = listOf(model), defaultModel = model)
        val listed = listOf(projects[0], projects[1])
        val recording = DictationFeature.State(phase = DictationFeature.Phase.Recording)
        fun HomeFeature.State.detail(update: (SessionFeature.State) -> SessionFeature.State) = copy(sessionDetail = update(sessionDetail!!))

        // 1. Carried dictation: draft -> draft keeps navigationID == 1, advances visit to 2, and runs the task.
        val home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listed, sessions = listOf(prior), activeSessionIDs = emptySet()))
            .detail { it.copy(dictation = recording) }
        assertEquals(1, home.sessionDetail?.navigationID)
        assertEquals(1, home.sessionDetail?.visit)
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 1, null), home.sessionDetail?.taskIdentity)
        val store = TestStore(home, HomeFeature(), backgroundScope) {
            this[OpenCodeClient] = noSends(OpenCodeClient.testValue).copy(modelCatalog = { _, _ -> catalog })
        }
        store.exhaustive = false
        store.send(HomeFeature.Action.NewSessionTapped)
        assertEquals(2, store.state.detailGeneration)
        assertEquals("The ifLet identity stays 1 so the recording's effect lives on", 1, store.state.sessionDetail?.navigationID)
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 2, null), store.state.sessionDetail?.taskIdentity)
        assertEquals(true, store.state.sessionDetail?.inheritsLiveWork)
        assertEquals(DictationFeature.Phase.Recording, store.state.sessionDetail?.dictation?.phase)
        assertEquals(projects[0], store.state.sessionDetail?.draftProject)
        store.send(HomeFeature.Action.detail(SessionFeature.Action.Task))
        assertEquals(false, store.state.sessionDetail?.inheritsLiveWork)
        assertEquals(1, store.state.sessionDetail?.runWorkGeneration)
        store.receive({ it == HomeFeature.Action.detail(SessionFeature.Action.ModelsResponse(Result.success(catalog))) }, "modelsResponse")
        assertEquals(listOf(model), store.state.sessionDetail?.models)
        assertEquals(ModelDisplayNames(listOf(model)), store.state.sessionDetail?.modelDisplayNamesByServer?.value?.get(server.persistenceID))
        assertEquals("Cirrus 4", store.state.sessionDetail?.currentModelName)
        assertNotNull(store.state.sessionDetail?.currentLevelScale)

        // 2. Carried dictation on a draft-created session -> draft: navigationID 1, visit 3, the old
        // session's work stopped, models loaded for the new draft.
        val createdHome = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listed, sessions = listOf(prior), activeSessionIDs = emptySet()))
            .copy(detailGeneration = 2)
            .detail {
                it.copy(
                    navigationID = 1, visit = 2, draftBecameSessionID = created.id, session = created,
                    familySessions = listOf(created), familyRootID = created.id, dictation = recording,
                )
            }
        val createdSessionIdentity = createdHome.sessionDetail?.taskIdentity
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 2, null), createdSessionIdentity)
        val createdStore = TestStore(createdHome, HomeFeature(), backgroundScope) {
            this[OpenCodeClient] = noSends(OpenCodeClient.testValue).copy(modelCatalog = { _, _ -> catalog })
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }
        createdStore.exhaustive = false
        createdStore.send(HomeFeature.Action.NewSessionTapped)
        assertEquals(3, createdStore.state.detailGeneration)
        assertEquals(DictationActivityAttributes.ContentState(title = "New session", startedAt = Instant.EPOCH), createdStore.state.dictationActivity)
        assertEquals(1, createdStore.state.sessionDetail?.navigationID)
        assertNull(createdStore.state.sessionDetail?.session)
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 3, null), createdStore.state.sessionDetail?.taskIdentity)
        assertNotEquals(createdSessionIdentity, createdStore.state.sessionDetail?.taskIdentity)
        assertEquals(true, createdStore.state.sessionDetail?.inheritsLiveWork)
        createdStore.send(HomeFeature.Action.detail(SessionFeature.Action.Task))
        assertEquals(false, createdStore.state.sessionDetail?.inheritsLiveWork)
        assertEquals(1, createdStore.state.sessionDetail?.runWorkGeneration)
        createdStore.receive({ it == HomeFeature.Action.detail(SessionFeature.Action.ModelsResponse(Result.success(catalog))) }, "modelsResponse")
        assertEquals("Cirrus 4", createdStore.state.sessionDetail?.currentModelName)
        assertNotNull(createdStore.state.sessionDetail?.currentLevelScale)

        // 3. Live call on a draft-created session -> draft: navigationID 1, visit 3, the previous
        // session's work stopped (runWorkGeneration superseded), models loaded.
        val callHome = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = listed, sessions = listOf(created), activeSessionIDs = emptySet()))
            .copy(detailGeneration = 2)
            .detail {
                it.copy(
                    navigationID = 1, visit = 2, draftBecameSessionID = created.id, session = created,
                    familySessions = listOf(created), familyRootID = created.id, runWorkGeneration = 4,
                    voice = VoiceFeature.State(server, created).withPhase(VoiceFeature.State.Phase.Live),
                )
            }
        val callStore = TestStore(callHome, HomeFeature(), backgroundScope) {
            this[OpenCodeClient] = noSends(OpenCodeClient.testValue).copy(modelCatalog = { _, _ -> catalog })
            this[DateClient] = DateClient.constant(Instant.EPOCH)
        }
        callStore.exhaustive = false
        callStore.send(HomeFeature.Action.NewSessionTapped)
        assertEquals("The ifLet identity stays 1 so the live call's effects survive", 1, callStore.state.sessionDetail?.navigationID)
        assertEquals(3, callStore.state.sessionDetail?.visit)
        assertEquals(true, callStore.state.sessionDetail?.inheritsLiveWork)
        assertEquals(SessionFeature.State.TaskIdentity(server.persistenceID, 3, null), callStore.state.sessionDetail?.taskIdentity)
        assertNotEquals(
            "Moving from a draft-created session to a new draft under keepsIdentity still changes taskIdentity",
            createdSessionIdentity, callStore.state.sessionDetail?.taskIdentity,
        )
        callStore.send(HomeFeature.Action.detail(SessionFeature.Action.Task))
        assertEquals("stopSessionWork ran for the previous session", false, callStore.state.sessionDetail?.inheritsLiveWork)
        assertEquals("supersedeRunWork advanced the new detail's runWorkGeneration", 1, callStore.state.sessionDetail?.runWorkGeneration)
        callStore.receive({ it == HomeFeature.Action.detail(SessionFeature.Action.ModelsResponse(Result.success(catalog))) }, "modelsResponse")
        assertEquals(listOf(model), callStore.state.sessionDetail?.models)
        assertEquals("Cirrus 4", callStore.state.sessionDetail?.currentModelName)
        assertNotNull(callStore.state.sessionDetail?.currentLevelScale)
    }
}
