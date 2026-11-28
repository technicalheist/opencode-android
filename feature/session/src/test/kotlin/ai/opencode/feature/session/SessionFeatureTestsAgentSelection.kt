package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.Store
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.core.ui.OpenCodeAppTheme
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.session.FailedSendsSupport.expectOptimisticSend
import ai.opencode.feature.session.FailedSendsSupport.sentID
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import java.time.Duration
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Agent Selection. Each test's shared keys live in its own in-memory store. `Shared`
 * values aren't compared by the store, so their asserts are checked on the shared value after the
 * step.
 */
class SessionFeatureTestsAgentSelection {
    private val agents = listOf(
        OpenCodeAutocompleteAgent(id = "build", name = "build", mode = "primary"),
        OpenCodeAutocompleteAgent(id = "plan", name = "plan", mode = "primary"),
    )

    /**
     * Records each switch and prompt; the reply's wait and reads never end,
     * so only the send itself is under test.
     */
    private fun Harness.agentStore(
        state: State,
        calls: LockIsolated<List<String>>,
        refuseAgent: Boolean = false,
    ): TestStore<State, Action> = store(state) {
        this[UuidClient] = UuidClient.incrementing()
        this[Clock] = ImmediateClock()
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            switchAgent = { _, sessionID, agent ->
                calls.append("agent $sessionID $agent")
                if (refuseAgent) throw OpenCodeClientError("Agent not found", statusCode = 400)
            },
            sendPrompt = { _, _, text, _, _, _, delivery -> calls.append("prompt $text ${delivery.rawValue}") },
            setPendingInputDelivery = { _, _, _, delivery -> calls.append("deliver ${delivery.rawValue}") },
            pendingInputs = { _, _ -> awaitCancellation() },
            waitForIdle = { _, _ -> awaitCancellation() },
            message = { _, _, _ -> awaitCancellation() },
            getSession = { _, _ -> awaitCancellation() },
            transcript = { _, _, _ -> awaitCancellation() },
            familyPendingRequests = { _, _, _ -> awaitCancellation() },
        )
    }

    private fun agentSession(agent: String? = "build", id: String = "ses_1"): OpenCodeSession = testSession(id = id, agent = agent)

    /** `$0.session?.agent = x; $0.familySessions[0].agent = x`. */
    private fun State.withAgent(agent: String): State = copy(
        session = session?.copy(agent = agent),
        familySessions = familySessions.mapIndexed { index, session -> if (index == 0) session.copy(agent = agent) else session },
    )

    /** `$0.agentSelection.listedDirectory = .init(directory: $0.effectiveDirectory)`. */
    private fun State.listed(): State = copy(agentSelection = agentSelection.copy(listedDirectory = ListedDirectory(effectiveDirectory)))

    /** `$0.promptStates[sentID]?.admission = .admitted`. */
    private fun State.admitted(): State = copy(
        promptStates = promptStates.mapValues { (id, prompt) ->
            if (id == sentID) prompt.copy(admission = State.PromptState.Admission.admitted) else prompt
        },
    )

    @Test fun onlyVisiblePrimaryAgentsAreChoosable() {
        val agents = listOf(
            OpenCodeAutocompleteAgent(id = "build", name = "build", mode = "primary"),
            OpenCodeAutocompleteAgent(id = "general", name = "general", mode = "subagent"),
            OpenCodeAutocompleteAgent(id = "title", name = "title", mode = "primary", hidden = true),
            OpenCodeAutocompleteAgent(id = "reviewer", name = "Reviewer", mode = "all"),
        )
        assertEquals(listOf("build", "reviewer"), OpenCodeAutocompleteAgent.choosable(agents).map { it.id })
        assertEquals("Build", agents[0].displayName)
        assertEquals("Reviewer", agents[3].displayName)
    }

    /**
     * A failed list request retries, so the menu doesn't stay on "Loading
     * Agents…" until the app restarts.
     */
    @Test fun aFailedAgentsRequestRetries() = sessionTest { h ->
        val tries = LockIsolated(0)
        val agents = agents
        val store = h.store(h.makeState(session = agentSession())) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                primaryAgents = { _, _ ->
                    tries.increment()
                    if (tries.value < 3) throw OpenCodeClientError("offline")
                    agents
                },
            )
        }
        store.send(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isSuccess }, "agentsResponse.success") {
            it.withPrimaryAgents(agents).listed()
        }
        assertEquals(agents, store.state.knownPrimaryAgents)
        assertEquals(3, tries.value)
    }

    /**
     * The chip mounts each time the composer expands: once its folder's list
     * is in, mounting again asks the server for nothing.
     */
    @Test fun expandingTheComposerAgainDoesNotListAgentsAgain() = sessionTest { h ->
        val asked = LockIsolated(0)
        val agents = agents
        val store = h.store(h.makeState(session = agentSession())) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                primaryAgents = { _, _ ->
                    asked.increment()
                    agents
                },
            )
        }
        store.send(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isSuccess }, "agentsResponse.success") {
            it.withPrimaryAgents(agents).listed()
        }
        store.send(Action.AgentsRequested)
        store.send(Action.AgentsRequested)
        assertEquals("Listed once for the folder", 1, asked.value)
    }

    /** A change to the server's agents lists them again, even for a folder already listed. */
    @Test fun anAgentsChangeListsAgentsAgain() = sessionTest { h ->
        val asked = LockIsolated(0)
        val first = agents.take(1)
        val all = agents
        val store = h.store(h.makeState(session = agentSession())) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                primaryAgents = { _, _ -> if (asked.increment() == 1) first else all },
            )
        }
        store.send(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isSuccess }, "agentsResponse.success") {
            it.withPrimaryAgents(first).listed()
        }
        store.send(Action.AgentsChanged) { it.copy(agentSelection = it.agentSelection.copy(listedDirectory = null)) }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isSuccess }, "agentsResponse.success") {
            it.withPrimaryAgents(all).listed()
        }
        assertEquals(2, asked.value)
    }

    /** After the last try the failure is reported once, not retried forever. */
    @Test fun agentsRequestGivesUpAfterTheLastTry() = sessionTest { h ->
        val tries = LockIsolated(0)
        val store = h.store(h.makeState(session = agentSession())) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                primaryAgents = { _, _ ->
                    tries.increment()
                    throw OpenCodeClientError("offline")
                },
            )
        }
        store.send(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isFailure }, "agentsResponse.failure")
        assertEquals(agentsAttempts, tries.value)
    }

    /**
     * Moving a new session's draft to another project lists that project's
     * agents, not the ones of the project it started in.
     */
    @Test fun aDraftMovedToAnotherProjectListsThatProjectsAgents() = sessionTest { h ->
        val first = OpenCodeProject(id = "p1", name = "one", directory = "/srv/one")
        val second = OpenCodeProject(id = "p2", name = "two", directory = "/srv/two")
        val manager = listOf(OpenCodeAutocompleteAgent(id = "manager", name = "Manager", mode = "primary"))
        val asked = LockIsolated(listOf<String?>())
        val agents = agents
        val state = h.state {
            State.draft(server = testServer, draftProject = first, projects = listOf(first, second)).withPrimaryAgents(agents)
        }
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                primaryAgents = { _, directory ->
                    asked.append(directory)
                    manager
                },
            )
        }
        store.send(Action.DraftProjectTapped(second)) {
            it.copy(draftProject = second).withPrimaryAgents(emptyList())
        }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isSuccess }, "agentsResponse.success") {
            it.withPrimaryAgents(manager).listed()
        }
        assertEquals(listOf<String?>("/srv/two"), asked.value)
    }

    /** The list is kept for Settings; a pick is remembered for its session. */
    @Test fun aPickIsRememberedForItsSession() = sessionTest { h ->
        val store = h.agentStore(h.makeState(session = agentSession()), calls = LockIsolated(emptyList()))
        store.send(Action.AgentsResponse(Result.success(agents))) { it.withPrimaryAgents(agents).listed() }
        assertEquals(agents, store.state.knownPrimaryAgents)
        assertEquals("Build", store.state.currentAgentName)
        store.send(Action.AgentPicked("plan"))
        assertEquals(mapOf("ses_1" to "plan"), store.state.sessionAgents)
        assertEquals("Plan", store.state.currentAgentName)
        assertEquals("plan", store.state.sendAgent)
        // The one already chosen is no change.
        store.send(Action.AgentPicked("plan"))
    }

    /**
     * A pick is applied just before the prompt; once the session has it,
     * the next send switches nothing.
     */
    @Test fun aPickedAgentIsAppliedOnceBeforeThePrompt() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val store = h.agentStore(h.makeState(session = agentSession(), draft = "hello"), calls = calls)
        store.send(Action.AgentPicked("plan"))
        assertEquals(mapOf("ses_1" to "plan"), store.state.sessionAgents)
        store.send(Action.SendTapped) { it.expectOptimisticSend() }
        store.receive(Action.AgentApplied(sessionID = "ses_1", agent = "plan")) { it.withAgent("plan") }
        store.receive(Action.PromptAdmitted(sessionID = "ses_1", messageID = sentID, requestEpoch = 1)) { it.admitted() }
        assertEquals(listOf("agent ses_1 plan", "prompt hello steer"), calls.value)
        assertNull("The session has it now", store.state.sendAgent)
        h.skipInFlightEffects()
    }

    /**
     * Back to the session's first agent: it's switched again, never left on
     * the one picked in between.
     */
    @Test fun switchingBackToTheInitialAgentAppliesIt() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val state = h.makeState(session = agentSession("plan"), draft = "hello")
        state.agentSelection.sessionAgents.set(mapOf("ses_1" to "plan"))
        val store = h.agentStore(state, calls = calls)
        assertNull(store.state.sendAgent)
        store.send(Action.AgentPicked("build"))
        assertEquals(mapOf("ses_1" to "build"), store.state.sessionAgents)
        store.send(Action.SendTapped) { it.expectOptimisticSend() }
        store.receive(Action.AgentApplied(sessionID = "ses_1", agent = "build")) { it.withAgent("build") }
        store.receive(Action.PromptAdmitted(sessionID = "ses_1", messageID = sentID, requestEpoch = 1)) { it.admitted() }
        assertEquals(listOf("agent ses_1 build", "prompt hello steer"), calls.value)
        h.skipInFlightEffects()
    }

    /**
     * A refused agent fails the send, saying why, rather than posting it to
     * the old agent. The pick keeps waiting.
     */
    @Test fun aRefusedAgentFailsTheSendWithoutPostingIt() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val state = h.makeState(session = agentSession(), draft = "hello")
        state.agentSelection.sessionAgents.set(mapOf("ses_1" to "plan"))
        val store = h.agentStore(state, calls = calls, refuseAgent = true)
        store.send(Action.SendTapped) { it.expectOptimisticSend() }
        store.receive(Action.PromptFailed::class) {
            it.copy(
                promptStates = emptyMap(),
                isAwaitingReply = false,
                failedSends = listOf(
                    FailedSend(
                        pending = PendingPrompt(messageID = sentID, text = "hello", attachments = emptyList(), requestEpoch = 1, agent = "plan"),
                        reason = "Couldn’t switch agent: Agent not found",
                    ),
                ),
            )
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = "ses_1", items = emptyList()))
        assertEquals("Never posted", listOf("agent ses_1 plan"), calls.value)
        assertEquals("build", store.state.session?.agent)
        assertEquals("plan", store.state.sendAgent)
    }

    /** Off, nothing changes: even a remembered pick sends no switch. */
    @Test fun agentSelectionOffSendsNoAgent() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val state = h.makeState(session = agentSession(), draft = "hello")
        state.agentSelection.sessionAgents.set(mapOf("ses_1" to "plan"))
        state.agentSelection.isEnabled.set(false)
        val store = h.agentStore(state, calls = calls)
        store.send(Action.AgentsRequested)
        store.send(Action.SendTapped) { it.expectOptimisticSend() }
        store.receive(Action.PromptAdmitted(sessionID = "ses_1", messageID = sentID, requestEpoch = 1)) { it.admitted() }
        assertEquals(listOf("prompt hello steer"), calls.value)
        h.skipInFlightEffects()
    }

    /**
     * A queued send never switches the run it waits behind: its agent applies
     * when its message starts.
     */
    @Test fun aQueuedSendAppliesItsAgentWhenItStarts() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val state = h.makeState(session = agentSession(), draft = "Do this afterwards").copy(isExternallyActive = true)
        state.agentSelection.sessionAgents.set(mapOf("ses_1" to "plan"))
        val store = h.agentStore(state, calls = calls)
        store.exhaustive = false
        store.send(Action.QueueTapped)
        store.receive(Action.PromptAdmitted::class)
        store.exhaustive = true
        assertEquals("The running turn keeps its agent", listOf("prompt Do this afterwards queue"), calls.value)
        assertEquals(mapOf(sentID to "plan"), store.state.queuedAgentPicks)

        // The run ends and the queued message starts: history has it.
        val epoch = store.state.promptStates[sentID]?.requestEpoch ?: store.state.pendingPromptPositions[sentID]?.requestEpoch
        store.exhaustive = false
        store.send(
            Action.PromptEchoResponse(
                sessionID = "ses_1", messageID = sentID, requestEpoch = epoch ?: -1,
                items = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Do this afterwards")),
            ),
        )
        store.exhaustive = true
        assertTrue(store.state.queuedAgentPicks.isEmpty())
        store.receive(Action.AgentApplied(sessionID = "ses_1", agent = "plan")) { it.withAgent("plan") }
        assertEquals("agent ses_1 plan", calls.value.last())
        h.skipInFlightEffects()
    }

    /**
     * Send Now makes a queued send a steer, which applies its agent at once;
     * Move to Queue makes a waiting pick wait with the message again.
     */
    @Test fun sendNowAppliesAndMoveToQueueKeepsTheQueuedAgent() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val state = h.makeState(session = agentSession(), draft = "Do this afterwards").copy(isExternallyActive = true)
        state.agentSelection.sessionAgents.set(mapOf("ses_1" to "plan"))
        val store = h.agentStore(state, calls = calls)
        store.exhaustive = false
        store.send(Action.QueueTapped)
        store.receive(Action.PromptAdmitted::class)
        store.send(Action.PendingInputSendNowTapped(sentID))
        store.exhaustive = true
        assertTrue(store.state.queuedAgentPicks.isEmpty())
        store.receive(Action.PendingInputsRequested(sessionID = "ses_1"))
        store.receive(Action.AgentApplied(sessionID = "ses_1", agent = "plan")) { it.withAgent("plan") }
        assertTrue(calls.value.contains("agent ses_1 plan"))

        // A newer pick waits; Move to Queue takes it along.
        store.send(Action.AgentPicked("build"))
        assertEquals(mapOf("ses_1" to "build"), store.state.sessionAgents)
        store.exhaustive = false
        store.send(Action.PendingInputMoveToQueueTapped(sentID))
        store.exhaustive = true
        assertEquals(mapOf(sentID to "build"), store.state.queuedAgentPicks)
        store.receive(Action.PendingInputsRequested(sessionID = "ses_1"))
        h.skipInFlightEffects()
    }

    /** A draft starts with the Settings default, which its session then keeps. */
    @Test fun aDraftStartsWithTheDefaultAgent() = sessionTest { h ->
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val created = testSession(id = "ses_new", title = "Untitled session", directory = "/tmp/demo").copy(agent = "build")
        val state = h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }.withDraft("hello")
        state.agentSelection.defaultAgentID.set("plan")
        val calls = LockIsolated(listOf<String>())
        val store = h.agentStore(state, calls = calls)
        store.updateDependencies {
            this[OpenCodeClient] = this[OpenCodeClient].copy(createSession = { _, _, _ -> created })
        }
        assertEquals("plan", store.state.currentAgentID)
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.SessionCreated::class)
        store.exhaustive = true
        assertEquals(mapOf("ses_new" to "plan"), store.state.sessionAgents)
        assertNull(store.state.draftAgent)
        store.receive(Action.AgentApplied(sessionID = "ses_new", agent = "plan")) { it.withAgent("plan") }
        store.receive(Action.PromptAdmitted(sessionID = "ses_new", messageID = sentID, requestEpoch = 1)) { it.admitted() }
        store.receive(Action.PendingInputsRequested(sessionID = "ses_new"))
        assertEquals(listOf("agent ses_new plan", "prompt hello steer"), calls.value)
        h.skipInFlightEffects()
    }

    private val zen = OpenCodeModel(modelID = "big-pickle", providerID = "opencode", name = "Big Pickle")
    private val flash = OpenCodeModel(modelID = "flash", providerID = "opencode", name = "Flash", variants = listOf("high", "max"))
    private val opus = OpenCodeModel(modelID = "opus", providerID = "opencode", name = "Opus")
    private val mini = OpenCodeModel(modelID = "mini", providerID = "opencode", name = "Mini")
    private val catalog = OpenCodeModelCatalog(models = listOf(zen, flash, opus, mini), defaultModel = zen)
    private val modelAgents = listOf(
        OpenCodeAutocompleteAgent(id = "build", name = "build", mode = "primary", model = flash.ref("max")),
        OpenCodeAutocompleteAgent(id = "plan", name = "plan", mode = "primary", model = opus.ref),
        OpenCodeAutocompleteAgent(id = "ask", name = "ask", mode = "primary"),
    )

    private fun Harness.modelDraft(): State = state {
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        State.draft(server = testServer, draftProject = project, projects = listOf(project))
    }

    private fun State.catalogued(): State = copy(models = catalog.models, agentSelection = agentSelection.copy(defaultModel = zen))

    /** A new session starts on its agent's model, whether the agents or the catalog arrive first. */
    @Test fun aDraftStartsOnItsAgentsModel() {
        for (agentsFirst in listOf(true, false)) sessionTest { h ->
            val store = h.store(h.modelDraft())
            if (agentsFirst) {
                store.send(Action.AgentsResponse(Result.success(modelAgents))) { it.withPrimaryAgents(modelAgents).listed() }
                store.send(Action.ModelsResponse(Result.success(catalog))) { it.catalogued().copy(currentModel = flash.ref("max")) }
            } else {
                store.send(Action.ModelsResponse(Result.success(catalog))) { it.catalogued().copy(currentModel = zen.ref) }
                store.send(Action.AgentsResponse(Result.success(modelAgents))) {
                    it.copy(currentModel = flash.ref("max"), agentSelection = it.agentSelection.copy(primaryAgents = modelAgents)).listed()
                }
            }
        }
    }

    /** The level last picked for the agent's model wins over the agent's own. */
    @Test fun aSavedLevelWinsOverTheAgentsLevel() = sessionTest { h ->
        val state = h.modelDraft().withPrimaryAgents(modelAgents)
        state.modelVariants.set(mapOf(flash.id to "high"))
        val store = h.store(state)
        store.send(Action.ModelsResponse(Result.success(catalog))) { it.catalogued().copy(currentModel = flash.ref("high")) }
    }

    /** A level the agent's model doesn't offer is dropped. */
    @Test fun anAgentsLevelTheModelDoesNotOfferIsDropped() = sessionTest { h ->
        val build = OpenCodeAutocompleteAgent(id = "build", name = "build", mode = "primary", model = opus.ref("max"))
        val store = h.store(h.modelDraft().withPrimaryAgents(listOf(build)))
        store.send(Action.ModelsResponse(Result.success(catalog))) { it.catalogued().copy(currentModel = opus.ref) }
    }

    /** An agent's model the server doesn't offer leaves the draft on the server's default. */
    @Test fun anAgentsModelTheServerDoesNotOfferFallsBackToTheDefault() = sessionTest { h ->
        val missing = OpenCodeAutocompleteAgent(id = "build", name = "build", mode = "primary", model = OpenCodeModelRef(id = "gone", providerID = "opencode"))
        val store = h.store(h.modelDraft().withPrimaryAgents(listOf(missing)))
        store.send(Action.ModelsResponse(Result.success(catalog))) { it.catalogued().copy(currentModel = zen.ref) }
    }

    /** Switching a draft's agent switches its model; a model picked by hand comes back with its agent. */
    @Test fun switchingADraftsAgentSwitchesItsModelAndKeepsAHandPick() = sessionTest { h ->
        val state = h.modelDraft().withPrimaryAgents(modelAgents).catalogued().copy(currentModel = flash.ref("max"))
        val store = h.store(state)
        store.send(Action.AgentPicked("plan")) { it.withDraftAgent("plan").copy(currentModel = opus.ref) }
        store.send(Action.AgentPicked("build")) { it.withDraftAgent("build").copy(currentModel = flash.ref("max")) }
        store.send(Action.ModelPicked(mini)) { it.copy(currentModel = mini.ref) }
        store.send(Action.AgentPicked("ask")) {
            it.copy(currentModel = zen.ref, agentSelection = it.agentSelection.copy(draftAgent = "ask", draftModelPicks = mapOf("build" to mini.ref)))
        }
        store.send(Action.AgentPicked("build")) { it.withDraftAgent("build").copy(currentModel = mini.ref) }
    }

    /**
     * With Agent Selection off a draft still lists agents, and starts on the model of the
     * server's default agent, which it lists first.
     */
    @Test fun agentSelectionOffStartsADraftOnTheServersDefaultAgentsModel() = sessionTest { h ->
        val state = h.modelDraft().catalogued().copy(currentModel = zen.ref)
        state.agentSelection.isEnabled.set(false)
        state.agentSelection.defaultAgentID.set("build")
        val planFirst = listOf(modelAgents[1], modelAgents[0])
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(primaryAgents = { _, _ -> planFirst })
        }
        store.send(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse && it.result.isSuccess }, "agentsResponse.success") {
            it.copy(currentModel = opus.ref, agentSelection = it.agentSelection.copy(primaryAgents = planFirst)).listed()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AgentSelectionComposerTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test fun switchingServersFromADraftListsTheNewServersAgents() {
        val values = DependencyValues.testing().update {
            this[OpenCodeClient] = this[OpenCodeClient].copy(primaryAgents = { server, _ ->
                val id = if (server == testServer) "build" else "review"
                listOf(OpenCodeAutocompleteAgent(id = id, name = id, mode = "primary"))
            })
        }
        DependencyValues.withValues(values) {
            val draft = State.draft(testServer, null, emptyList(), visit = 1)
            val store = Store(draft, SessionFeature(), dependencies = values)
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val view = ComposeView(activity.get())
                activity.get().setContentView(view)
                view.setContent { OpenCodeAppTheme { SessionComposer(store) } }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                assertEquals("Build", store.state.currentAgentName)

                // Home replaced its initial draft in place: same visit and no session ID on either server.
                store.send(Action.PreviewStateReplaced(draft.copy(server = testServer.copy(url = "http://localhost:10"))))
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                assertEquals("Review", store.state.currentAgentName)
            } finally {
                activity.pause().stop().destroy()
                store.close()
                // Drain the composition's teardown from the process-wide `AndroidUiDispatcher.Main`:
                // Robolectric drops the main looper's messages between tests, which would strand its
                // scheduled dispatch and stall the next test's effects.
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }
}
