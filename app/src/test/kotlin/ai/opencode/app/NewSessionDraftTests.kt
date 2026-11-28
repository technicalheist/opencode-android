package ai.opencode.app

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.session.currentLevelScale
import ai.opencode.feature.session.currentModelName
import ai.opencode.feature.session.taskIdentity
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** The `AppFeature` part: switching servers from a draft. */
class NewSessionDraftTests {
    @get:Rule val isolation = TestIsolationRule()

    private val shared = AppTestShared()
    private val projects = (1..7).map { OpenCodeProject(id = "p$it", name = "project-$it", directory = "/srv/code/project-$it") }

    /** Nothing in these tests may create a session or send a prompt. */
    private fun noSends(client: OpenCodeClient) = client.copy(
        createSession = { _, _, _ -> throw AssertionError("Choosing a project must not create a session") },
        sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Choosing a project must not send") },
    )

    private fun HomeFeature.State.detail(update: (SessionFeature.State) -> SessionFeature.State) = copy(sessionDetail = update(sessionDetail!!))

    private fun AppFeature.State.detail(update: (SessionFeature.State) -> SessionFeature.State) = copy(home = home?.detail(update))

    private fun detailAction(action: SessionFeature.Action) = AppFeature.Action.Home(HomeFeature.Action.detail(action))

    /**
     * Two initial drafts still differ in `taskIdentity` by `server.persistenceID`, so the new
     * server's draft loads its catalog.
     */
    @Test
    fun switchingServersFromADraftAdvancesTaskIdentityAndLoadsTheNewServersModels() = runTest {
        val laptop = OpenCodeServer(url = "http://192.168.1.20:4096", username = "opencode", password = "a")
        val studio = OpenCodeServer(url = "http://10.0.0.4:4096", username = "opencode", password = "b", name = "Studio")
        val laptopModel = OpenCodeModel(modelID = "laptop-4", providerID = "opencode", name = "Laptop 4", variants = listOf("low", "high"))
        val studioModel = OpenCodeModel(modelID = "studio-4", providerID = "opencode", name = "Studio 4", variants = listOf("low", "high"))
        val laptopPrior = OpenCodeSession(
            id = "ses_laptop", serverTitle = "Laptop", projectID = projects[0].id, directory = projects[0].directory,
            updatedAt = Instant.EPOCH, model = laptopModel.ref("high"),
        )
        val studioPrior = OpenCodeSession(
            id = "ses_studio", serverTitle = "Studio", projectID = projects[1].id, directory = projects[1].directory,
            updatedAt = Instant.EPOCH, model = studioModel.ref("high"),
        )
        val laptopSnapshot = OpenCodeHomeSnapshot(projects = listOf(projects[0]), sessions = listOf(laptopPrior), activeSessionIDs = emptySet())
        val studioSnapshot = OpenCodeHomeSnapshot(projects = listOf(projects[1]), sessions = listOf(studioPrior), activeSessionIDs = emptySet())

        val app = shared.appState().copy(
            connection = AppFeature.State.Connection.Connected,
            home = shared.run { HomeFeature.State(laptop, laptopSnapshot) },
        )
        shared.run { shared(ServerSharedKeys.savedServers).set(listOf(laptop, studio)) }

        val laptopIdentity = app.home?.sessionDetail?.taskIdentity
        assertEquals(SessionFeature.State.TaskIdentity(serverID = laptop.persistenceID, visit = 1, sessionID = null), laptopIdentity)

        val gate = GatedHome()
        val effects = detachedScope(coroutineContext)
        val store: TestStore<AppFeature.State, AppFeature.Action> = shared.appStore(app, effects) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = noSends(gate.client()).copy(
                archivedSessions = { _, _, _ -> emptyList() },
                modelCatalog = { server, _ ->
                    val model = if (server.persistenceID == studio.persistenceID) studioModel else laptopModel
                    OpenCodeModelCatalog(models = listOf(model), defaultModel = model)
                },
            )
        }

        store.send(detailAction(SessionFeature.Action.Task)) { it.detail { d -> d.copy(isLoadingModels = true) } }
        store.receive(detailAction(SessionFeature.Action.AgentsRequested))
        store.receive(
            { it == detailAction(SessionFeature.Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = listOf(laptopModel), defaultModel = laptopModel)))) },
            "home.sessionDetail.presented.modelsResponse.success",
        ) {
            it.detail { d ->
                d.copy(isLoadingModels = false, models = listOf(laptopModel), currentModel = laptopModel.ref, agentSelection = d.agentSelection.copy(defaultModel = laptopModel))
            }
        }
        store.receive(detailAction(SessionFeature.Action.AgentsResponse(Result.success(emptyList()))))
        assertEquals(ModelDisplayNames(listOf(laptopModel)), store.state.home?.sessionDetail?.modelDisplayNamesByServer?.value?.get(laptop.persistenceID))
        assertEquals("Laptop 4", store.state.home?.sessionDetail?.currentModelName)

        store.send(AppFeature.Action.Home(HomeFeature.Action.Delegate(HomeFeature.DelegateAction.ServerSwitchTapped(studio)))) {
            it.copy(
                returnServer = laptop,
                home = it.home?.copy(switchingTo = studio),
                serverURL = studio.url,
                password = studio.password,
                serverName = "Studio",
                connection = AppFeature.State.Connection.Connecting,
                connectionGeneration = 1,
                connectionAttempt = AppFeature.ConnectionAttempt(id = 1, server = studio, switchesBack = true),
            )
        }
        store.receiveCase<AppFeature.Action.HealthResponse> { it.copy(connection = AppFeature.State.Connection.LoadingHome(studio)) }
        assertEquals(studio, shared.run { shared(ServerSharedKeys.savedServer).value })
        assertEquals(listOf(studio, laptop), shared.run { shared(ServerSharedKeys.savedServers).value })
        gate.yield(studioSnapshot)
        gate.finish()
        store.receiveCase<AppFeature.Action.HomeResponse> {
            it.copy(
                connection = AppFeature.State.Connection.Connected,
                connectionAttempt = null,
                returnServer = null,
                home = shared.run { HomeFeature.State(studio, studioSnapshot) },
            )
        }
        assertEquals(
            CachedHomeSnapshot(serverID = studio.persistenceID, snapshot = studioSnapshot, capturedAt = Instant.EPOCH),
            shared.run { shared(ServerSharedKeys.cachedHomeSnapshots).value[studio.persistenceID] },
        )

        val studioIdentity = store.state.home?.sessionDetail?.taskIdentity
        assertEquals(SessionFeature.State.TaskIdentity(serverID = studio.persistenceID, visit = 1, sessionID = null), studioIdentity)
        assertNotEquals("Both initial drafts have visit 1, but serverID distinguishes them", laptopIdentity, studioIdentity)

        store.send(detailAction(SessionFeature.Action.Task)) { it.detail { d -> d.copy(isLoadingModels = true) } }
        store.receive(detailAction(SessionFeature.Action.AgentsRequested))
        store.receive(
            { it == detailAction(SessionFeature.Action.ModelsResponse(Result.success(OpenCodeModelCatalog(models = listOf(studioModel), defaultModel = studioModel)))) },
            "home.sessionDetail.presented.modelsResponse.success",
        ) {
            it.detail { d ->
                d.copy(isLoadingModels = false, models = listOf(studioModel), currentModel = studioModel.ref, agentSelection = d.agentSelection.copy(defaultModel = studioModel))
            }
        }
        store.receive(detailAction(SessionFeature.Action.AgentsResponse(Result.success(emptyList()))))
        assertEquals(ModelDisplayNames(listOf(studioModel)), store.state.home?.sessionDetail?.modelDisplayNamesByServer?.value?.get(studio.persistenceID))
        assertEquals("Studio 4", store.state.home?.sessionDetail?.currentModelName)
        assertNotNull(store.state.home?.sessionDetail?.currentLevelScale)
        store.finish()
    }
}
