package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.Gate
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.failedSendStore
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.hang
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.sentID
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.assertRemembersModelNames
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertFalse
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import org.junit.Test

class SessionFeatureTestsModelPicks {
    /**
     * A retry that finds its message already landed still applies the model
     * it carries (a pick made since it failed), once; if that's refused, the
     * pick keeps waiting.
     */
    @Test
    fun aRetryThatAlreadyLandedAppliesItsPick() {
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val slow = OpenCodeModel(modelID = "slow-sam", providerID = "opencode", name = "Slow Sam")
        for (refused in listOf(false, true)) sessionTest { h ->
            val switched = LockIsolated<List<String>>(emptyList())
            val attempts = LockIsolated<List<Pair<String, OpenCodeDelivery>>>(emptyList())
            val serverHasIt = LockIsolated(false)
            val store = h.failedSendStore(h.makeState(session = testSession(), draft = "hello"), attempts = attempts, serverHasIt = serverHasIt) {
                throw OpenCodeClientError(message = "The server returned HTTP 400.", statusCode = 400)
            }
            store.updateDependencies {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    switchModel = { _, _, ref ->
                        switched.append(ref.id)
                        if (refused && ref.id == "slow-sam") throw OpenCodeClientError(message = "Unknown model", statusCode = 400)
                    },
                )
            }
            store.exhaustive = false

            store.send(Action.ModelPicked(fast))
            store.send(Action.SendTapped)
            store.receive(Action.PromptFailed::class)
            store.send(Action.ModelPicked(slow))
            serverHasIt.setValue(true)
            store.send(Action.FailedSendRetryTapped(sentID))
            store.receive(Action.PromptAdmitted::class)
            assertEquals(listOf("fast-frank", "slow-sam"), switched.value)
            assertEquals(1, attempts.value.size, "The landed prompt isn't posted again")
            assertEquals(if (refused) slow.ref else null, store.state.pendingModel)
            h.skipInFlightEffects()
        }
    }

    /**
     * Every send before the server admits one carries the pick, so a later
     * send never goes without it if the first is deleted or fails.
     */
    @Test
    fun everySendCarriesAPickUntilOneIsAdmitted() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val calls = LockIsolated<List<String>>(emptyList())
        val gate = Gate()
        val store = h.store(h.makeState(session = testSession(), draft = "one")) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, _, ref -> calls.append("model ${ref.id}") },
                sendPrompt = { _, _, text, _, _, _, _ ->
                    if (text == "one") gate.await()
                    calls.append("prompt $text")
                },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false

        store.send(Action.ModelPicked(fast))
        store.send(Action.SendTapped)
        store.send(Action.Binding(BindingAction.Draft("two")))
        store.send(Action.SendTapped)
        gate.open()
        store.receive(Action.ModelApplied::class)
        store.receive(Action.ModelApplied::class)
        assertEquals(listOf("model fast-frank", "prompt one", "model fast-frank", "prompt two"), calls.value)
        assertNull(store.state.pendingModel)
        h.skipInFlightEffects()
    }

    /**
     * A pick made on a draft while its first send creates the session waits
     * for that session's next send.
     */
    @Test
    fun aDraftPickMadeWhileItsSessionIsCreatedWaitsForTheNextSend() = sessionTest { h ->
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val created = testSession(id = "ses_new", title = "Untitled session", directory = "/tmp/demo")
        val first = OpenCodeModelRef(id = "fast-frank", providerID = "opencode")
        val second = OpenCodeModel(modelID = "slow-sam", providerID = "opencode", name = "Slow Sam")
        val state = h.state {
            SessionFeature.State.draft(server = testServer, draftProject = project, projects = listOf(project)).copy(currentModel = first)
        }.withDraft("hello")
        val gate = Gate()
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    gate.await()
                    created
                },
                switchModel = { _, _, _ -> },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                getSession = { _, _ -> created },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.send(Action.ModelPicked(second))
        assertNull(store.state.session)
        gate.open()
        store.receive(Action.SessionCreated::class)
        store.receive(Action.ModelApplied::class)
        assertEquals(second.ref, store.state.pendingModel, "The first send carried the draft's earlier model")
        assertEquals(second.ref, store.state.currentModel)
        h.skipInFlightEffects()
    }

    /**
     * A subagent's screen keeps the root's pick, and the call on the root
     * keeps it too.
     */
    @Test
    fun aSubagentVisitKeepsTheRootsPick() = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root", updatedAt = distantPast)
        val childModel = OpenCodeModelRef(id = "child-model", providerID = "opencode")
        val child = testSession(id = "ses_child", title = "Child", updatedAt = distantPast, model = childModel, parentID = root.id)
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val voice = h.state { VoiceFeature.State(testServer, root) }.copy(phase = VoiceFeature.State.Phase.Live)
        val state = h.makeState(session = root, hasRenderedFirstContentFrame = false)
            .copy(familySessions = listOf(root, child), voice = voice)
        val store = h.store(state) {
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                sessionFamily = { _, _ -> throw OpenCodeClientError(message = "Offline") },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.ModelPicked(fast))
        assertEquals(fast.ref, store.state.voice?.pendingModel)
        store.send(Action.FamilyPicked(child.id))
        assertEquals(childModel, store.state.currentModel)
        assertNull(store.state.pendingModel)
        assertEquals(fast.ref, store.state.voice?.pendingModel, "The call is still on the root")
        store.send(Action.FamilyPicked(root.id))
        assertEquals(fast.ref, store.state.currentModel)
        assertEquals(fast.ref, store.state.pendingModel, "The root's pick came back")
        store.send(Action.FamilyPicked(child.id))
        // A send to the root settles while the subagent is on screen: the call's
        // copy goes too, so its next delegation doesn't apply it again.
        store.send(Action.ModelApplied(sessionID = root.id, model = fast.ref))
        assertNull(store.state.voice?.pendingModel)
        store.send(Action.FamilyPicked(root.id))
        assertEquals(fast.ref, store.state.currentModel, "The root's model, now applied")
        assertNull(store.state.pendingModel)
        h.skipInFlightEffects()
    }

    /**
     * A refresh that kept the copy of the session it started from never
     * takes back a model applied meanwhile; one it read does.
     */
    @Test
    fun aRefreshsOldCopyKeepsAModelAppliedMeanwhile() = sessionTest { h ->
        val old = OpenCodeModelRef(id = "old-model", providerID = "example")
        val new = OpenCodeModelRef(id = "new-model", providerID = "example")
        val session = testSession(id = "ses_stream", title = "Stream", model = old)
        val state = h.makeState(session = session)
            .copy(hasRenderedFirstContentFrame = true, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.ModelApplied(sessionID = session.id, model = new))
        store.send(refreshResponse(session.id, SessionFeature.AuthoritativeRefresh(observed = session, items = emptyList(), scope = page)))
        assertEquals(new, store.state.session?.model)
        assertEquals(new, store.state.familySessions.first().model)

        // A read that started before the model was applied predates it too.
        val reading = h.store(state)
        reading.exhaustive = false
        reading.send(Action.ModelApplied(sessionID = session.id, model = new))
        reading.send(
            refreshResponse(
                session.id,
                SessionFeature.AuthoritativeRefresh(observed = session, items = emptyList(), scope = page, observedIsRead = true),
            ),
        )
        assertEquals(new, reading.state.session?.model)
    }

    /**
     * A session opened from the cached list follows the model the server
     * reports for it (switched on another client meanwhile), unless a pick
     * made here still waits; a copy the refresh didn't read changes nothing.
     */
    @Test
    fun anOpenedSessionAdoptsTheServersModelUnlessAPickWaits() = sessionTest { h ->
        val cached = OpenCodeModelRef(id = "cached-model", providerID = "example")
        val server = OpenCodeModelRef(id = "server-model", providerID = "example", variant = "high")
        val picked = OpenCodeModel(modelID = "picked-model", providerID = "example", name = "Picked")
        val opened = testSession(id = "ses_stream", title = "Stream", model = cached)
        val current = opened.copy(model = server)
        val state = h.makeState(session = opened)
            .copy(hasRenderedFirstContentFrame = true, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))
        assertEquals(cached, state.currentModel)
        val read = refreshResponse(
            opened.id,
            SessionFeature.AuthoritativeRefresh(observed = current, items = emptyList(), scope = page, observedIsRead = true),
        )

        val store = h.store(state)
        store.exhaustive = false
        store.send(read)
        assertEquals(server, store.state.currentModel)
        assertEquals(server, store.state.session?.model)
        assertNull(store.state.pendingModel, "Nothing to apply: the server has it")

        val picking = h.store(state)
        picking.exhaustive = false
        picking.send(Action.ModelPicked(picked))
        picking.send(read)
        assertEquals(picked.ref, picking.state.currentModel, "The pick made here still waits")
        assertEquals(picked.ref, picking.state.pendingModel)

        // A model applied while the read was on its way outranks what it read.
        val applying = h.store(state)
        applying.exhaustive = false
        applying.send(Action.ModelPicked(picked))
        applying.send(Action.ModelApplied(sessionID = opened.id, model = picked.ref))
        applying.send(read)
        assertEquals(picked.ref, applying.state.currentModel)
        assertEquals(picked.ref, applying.state.session?.model)

        val unread = h.store(state)
        unread.exhaustive = false
        unread.send(refreshResponse(opened.id, SessionFeature.AuthoritativeRefresh(observed = current, items = emptyList(), scope = page)))
        assertEquals(cached, unread.state.currentModel)
    }

    /**
     * An earlier send's model landing after a newer pick went back to it
     * leaves the composer's model waiting, so the chip never lies.
     */
    @Test
    fun aModelLandingAfterARepickLeavesTheComposersWaiting() = sessionTest { h ->
        val a = OpenCodeModel(modelID = "model-a", providerID = "opencode", name = "A")
        val b = OpenCodeModel(modelID = "model-b", providerID = "opencode", name = "B")
        val store = h.store(h.makeState(session = testSession()))
        store.exhaustive = false
        store.send(Action.ModelPicked(a))
        store.send(Action.ModelApplied(sessionID = "ses_1", model = a.ref))
        assertNull(store.state.pendingModel)
        // B is picked and sent; A is picked again before B's send lands.
        store.send(Action.ModelPicked(b))
        store.send(Action.ModelPicked(a))
        store.send(Action.ModelApplied(sessionID = "ses_1", model = b.ref))
        assertEquals(b.ref, store.state.session?.model)
        assertEquals(a.ref, store.state.pendingModel, "The next send applies A")
    }

    /**
     * Like the desktop app, a queued send never switches the run it waits
     * behind: its pick waits, and applies when its message starts, so the
     * switch lands with the turn it's for. A pick changed meanwhile isn't
     * taken back.
     */
    @Test
    fun aQueuedSendLeavesTheRunsModelAndAppliesItsPickWhenItStarts() {
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val slow = OpenCodeModel(modelID = "slow-sam", providerID = "opencode", name = "Slow Sam")
        for (repicked in listOf(false, true)) sessionTest { h ->
            val calls = LockIsolated<List<String>>(emptyList())
            val state = h.makeState(session = testSession(), draft = "Do this afterwards").copy(isExternallyActive = true)
            val store = h.store(state) {
                this[UuidClient] = UuidClient.incrementing()
                this[ServerIDClient] = ServerIDClient.incrementing()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    switchModel = { _, _, ref -> calls.append("model ${ref.id}") },
                    sendPrompt = { _, _, text, _, _, _, delivery -> calls.append("prompt $text ${delivery.rawValue}") },
                    transcript = { _, _, _ -> emptyList() },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                    pendingInputs = { _, _ -> emptyList() },
                    waitForIdle = { _, _ -> hang() },
                    message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                )
            }
            store.exhaustive = false

            store.send(Action.ModelPicked(fast))
            store.send(Action.QueueTapped)
            store.receive(Action.PromptAdmitted::class)
            assertEquals(listOf("prompt Do this afterwards queue"), calls.value, "The running turn keeps its model")
            assertEquals(fast.ref, store.state.pendingModel, "The pick still waits")
            assertEquals(fast.ref, store.state.queuedPicks[sentID])

            if (repicked) store.send(Action.ModelPicked(slow))
            // The run ends and the queued message starts: history has it.
            val epoch = store.state.promptStates[sentID]?.requestEpoch ?: store.state.pendingPromptPositions[sentID]?.requestEpoch
            store.send(
                Action.PromptEchoResponse(
                    sessionID = "ses_1",
                    messageID = sentID,
                    requestEpoch = epoch ?: -1,
                    items = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Do this afterwards")),
                ),
            )
            assertTrue(store.state.queuedPicks.isEmpty())
            if (repicked) {
                assertEquals(1, calls.value.size, "A newer pick waits for the next send instead")
                assertEquals(slow.ref, store.state.pendingModel)
            } else {
                store.receive(Action.ModelApplied::class)
                assertEquals("model fast-frank", calls.value.last())
                assertNull(store.state.pendingModel)
                assertEquals(fast.ref, store.state.session?.model)
            }
            h.skipInFlightEffects()
        }
    }

    /**
     * Send Now applies a queued send's pick even while its post is still on
     * its way, and Move to Queue makes a waiting pick wait with the message.
     */
    @Test
    fun sendNowMidPostAndMoveToQueueKeepTheQueuedPick() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val calls = LockIsolated<List<String>>(emptyList())
        val gate = Gate()
        val state = h.makeState(session = testSession(), draft = "Do this afterwards").copy(isExternallyActive = true)
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, _, ref -> calls.append("model ${ref.id}") },
                sendPrompt = { _, _, text, _, _, _, delivery ->
                    gate.await()
                    calls.append("prompt $text ${delivery.rawValue}")
                },
                setPendingInputDelivery = { _, _, _, delivery -> calls.append("deliver ${delivery.rawValue}") },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(Action.ModelPicked(fast))
        store.send(Action.QueueTapped)
        store.send(Action.PendingInputSendNowTapped(sentID))
        store.receive(Action.ModelApplied::class)
        assertTrue("model fast-frank" in calls.value, "Applied while the queued post waits on the wire")
        assertFalse(calls.value.any { it.startsWith("prompt") })
        gate.open()
        store.receive(Action.PromptAdmitted::class)

        // Move to Queue with a newer pick waiting: it waits with the message.
        val slow = OpenCodeModel(modelID = "slow-sam", providerID = "opencode", name = "Slow Sam")
        store.send(Action.ModelPicked(slow))
        store.send(Action.PendingInputMoveToQueueTapped(sentID))
        assertEquals(slow.ref, store.state.queuedPicks[sentID])
        h.skipInFlightEffects()
    }

    /** A queued send's pick survives leaving the session and coming back. */
    @Test
    fun aQueuedPickSurvivesASessionSwitch() = sessionTest { h ->
        val pick = OpenCodeModelRef(id = "fast-frank", providerID = "opencode")
        val state = h.makeState(session = testSession()).copy(
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_q", text = "Later", delivery = OpenCodeDelivery.Queue)),
            pendingModel = pick,
            queuedPicks = mapOf("msg_q" to pick),
        )
        val snapshot = h.state { SessionFeature.SwitchSnapshot.from(state) }
        val returned = h.state { snapshot!!.restore(h.makeState(session = testSession())) }
        assertEquals(mapOf("msg_q" to pick), returned.queuedPicks)
        assertEquals(pick, returned.pendingModel)
    }

    /** Send Now makes a queued send a steer, which applies its pick at once. */
    @Test
    fun sendNowAppliesAQueuedSendsPick() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val calls = LockIsolated<List<String>>(emptyList())
        val state = h.makeState(session = testSession(), draft = "Do this afterwards").copy(isExternallyActive = true)
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, _, ref -> calls.append("model ${ref.id}") },
                sendPrompt = { _, _, text, _, _, _, delivery -> calls.append("prompt $text ${delivery.rawValue}") },
                setPendingInputDelivery = { _, _, _, delivery -> calls.append("deliver ${delivery.rawValue}") },
                transcript = { _, _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
            )
        }
        store.exhaustive = false
        store.send(Action.ModelPicked(fast))
        store.send(Action.QueueTapped)
        store.receive(Action.PromptAdmitted::class)
        store.send(Action.PendingInputSendNowTapped(sentID))
        store.receive(Action.ModelApplied::class)
        assertTrue("model fast-frank" in calls.value)
        assertNull(store.state.pendingModel)
        assertTrue(store.state.queuedPicks.isEmpty())
        h.skipInFlightEffects()
    }

    /**
     * A model the server refuses fails the send, as in the TUI, rather than
     * running it on another model.
     */
    @Test
    fun aRefusedModelFailsTheSendWithoutPostingIt() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = "fast-frank", providerID = "opencode", name = "Fast Frank")
        val attempts = LockIsolated<List<Pair<String, OpenCodeDelivery>>>(emptyList())
        val store = h.failedSendStore(h.makeState(session = testSession(), draft = "hello"), attempts = attempts) {}
        store.updateDependencies {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, _, _ -> throw OpenCodeClientError(message = "Model not found", statusCode = 400) },
            )
        }
        store.exhaustive = false

        store.send(Action.ModelPicked(fast))
        store.send(Action.SendTapped)
        store.receive(Action.PromptFailed::class)
        assertTrue(attempts.value.isEmpty())
        assertEquals("Couldn’t switch model: Model not found", store.state.failedSends.firstOrNull()?.reason)
        assertEquals(fast.ref, store.state.pendingModel)
    }

    /**
     * A draft's model goes with its sends: applied to the session the first
     * creates, before that send's prompt.
     */
    @Test
    fun aDraftAppliesItsModelBeforeTheFirstPrompt() = sessionTest { h ->
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val created = testSession(id = "ses_new", title = "Untitled session", directory = "/tmp/demo")
        val model = OpenCodeModelRef(id = "fast-frank", providerID = "opencode")
        val state = h.state {
            SessionFeature.State.draft(server = testServer, draftProject = project, projects = listOf(project)).copy(currentModel = model)
        }.withDraft("hello")
        val calls = LockIsolated<List<String>>(emptyList())
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    calls.append("create")
                    created
                },
                switchModel = { _, sessionID, ref -> calls.append("model $sessionID ${ref.id}") },
                sendPrompt = { _, sessionID, _, _, _, _, _ -> calls.append("prompt $sessionID") },
                getSession = { _, _ -> created },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> hang() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.SessionCreated::class)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("create", "model ses_new fast-frank", "prompt ses_new"), calls.value)
        assertNull(store.state.pendingModel)
        h.skipInFlightEffects()
    }

    @Test
    fun loadingCatalogShowsDefaultModelForImplicitSessionSelection() = sessionTest { h ->
        val defaultModel = OpenCodeModel(modelID = "example-model", providerID = "example", name = "Example Model")
        val directories = LockIsolated<List<String?>>(emptyList())
        val store = h.store(h.makeState(session = testSession(directory = "/tmp/demo"))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, directory ->
                    directories.append(directory)
                    OpenCodeModelCatalog(models = listOf(defaultModel), defaultModel = defaultModel)
                },
            )
        }

        assertEquals("Model", store.state.currentModelName)
        store.send(Action.ModelButtonTapped) {
            it.copy(activePicker = SessionFeature.State.ActivePicker.model, isLoadingModels = true)
        }
        store.receive(Action.ModelsResponse::class) {
            it.copy(isLoadingModels = false, models = listOf(defaultModel), currentModel = defaultModel.ref)
        }
        store.state.assertRemembersModelNames(listOf(defaultModel))
        assertEquals(listOf<String?>("/tmp/demo"), directories.value)
        assertEquals("Example Model", store.state.currentModelName)
    }

    /**
     * A new session starts on the server's default model (`model` in the
     * person's OpenCode config), which carries no variant, at the level last
     * picked for that model.
     */
    @Test
    fun aDraftStartsOnTheServersDefaultAtItsRememberedLevel() {
        for (remembered in listOf(null, "high")) sessionTest { h ->
            val opus = OpenCodeModel(modelID = "opus", providerID = "example", name = "Opus", variants = listOf("low", "high"))
            val other = OpenCodeModel(modelID = "other", providerID = "example", name = "Other", variants = listOf("low", "high"))
            val bareDefault = OpenCodeModel(modelID = "opus", providerID = "example", name = "Opus")
            val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
            val state = h.state { SessionFeature.State.draft(server = testServer, draftProject = project, projects = listOf(project)) }
            state.modelVariants.set(mapOf(opus.id to (remembered ?: "default"), other.id to "low"))
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    modelCatalog = { _, _ -> OpenCodeModelCatalog(models = listOf(other, opus), defaultModel = bareDefault) },
                )
            }

            assertNull(store.state.currentModel)
            store.send(Action.ModelButtonTapped) {
                it.copy(activePicker = SessionFeature.State.ActivePicker.model, isLoadingModels = true)
            }
            store.receive(Action.ModelsResponse::class) {
                it.copy(
                    isLoadingModels = false,
                    models = listOf(other, opus),
                    currentModel = opus.ref(remembered),
                    agentSelection = it.agentSelection.copy(defaultModel = bareDefault),
                )
            }
            store.state.assertRemembersModelNames(listOf(other, opus))
        }
    }

    /** A model picked on the draft stays when the catalog is read again. */
    @Test
    fun aDraftsPickOutlastsTheServersDefault() = sessionTest { h ->
        val opus = OpenCodeModel(modelID = "opus", providerID = "example", name = "Opus")
        val other = OpenCodeModel(modelID = "other", providerID = "example", name = "Other")
        val project = OpenCodeProject(id = "proj_1", name = "demo", directory = "/tmp/demo")
        val state = h.state { SessionFeature.State.draft(server = testServer, draftProject = project, projects = listOf(project)) }
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = listOf(opus, other), defaultModel = opus) },
            )
        }

        store.send(Action.ModelButtonTapped) {
            it.copy(activePicker = SessionFeature.State.ActivePicker.model, isLoadingModels = true)
        }
        store.receive(Action.ModelsResponse::class) {
            it.copy(
                isLoadingModels = false,
                models = listOf(opus, other),
                currentModel = opus.ref,
                agentSelection = it.agentSelection.copy(defaultModel = opus),
            )
        }
        store.state.assertRemembersModelNames(listOf(opus, other))
        store.send(Action.ModelPicked(other)) { it.copy(currentModel = other.ref, activePicker = null) }
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = SessionFeature.State.ActivePicker.model) }
        store.receive(Action.ModelsResponse::class)
        assertEquals(other.ref, store.state.currentModel)
    }

    @Test
    fun openingThePickerRefreshesModelsAlreadyListed() = sessionTest { h ->
        val tiny = OpenCodeModel(modelID = "example-model-tiny", providerID = "opencode", name = "Example Model Tiny")
        val added = OpenCodeModel(modelID = "example-model-added", providerID = "example", name = "Example Model Added")
        val catalogs = LockIsolated(
            listOf(
                OpenCodeModelCatalog(models = listOf(tiny, added), defaultModel = null),
                OpenCodeModelCatalog(models = emptyList(), defaultModel = null),
            ),
        )
        val state = h.makeState(session = testSession(directory = "/tmp/demo")).copy(models = listOf(tiny))
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ -> catalogs.withValue { it.drop(1) to it.first() } },
            )
        }

        // The listed models stay up (no spinner) while the server is asked again.
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = SessionFeature.State.ActivePicker.model) }
        store.receive(Action.ModelsResponse::class) { it.copy(models = listOf(tiny, added)) }
        store.state.assertRemembersModelNames(listOf(tiny, added))

        // A momentarily empty answer keeps what is listed.
        store.send(Action.ModelButtonTapped)
        store.receive(Action.ModelsResponse::class)
    }

    /**
     * A model and its Fast mode share an API model ID and differ only by
     * catalog key: the picker lists each once, checks only the session's
     * model, and switching names the key.
     */
    @Test
    fun modesSharingAnAPIModelIDAreDistinctPickerRows() = sessionTest { h ->
        val base = OpenCodeModel(modelID = "model-a", providerID = "example", name = "Model A", variants = listOf("high"))
        val fast = OpenCodeModel(
            modelID = "model-a", providerID = "example", name = "Model A Fast", catalogID = "model-a-fast", variants = listOf("high"),
        )
        val catalog = OpenCodeModelCatalog(models = listOf(base, fast), defaultModel = null)
        val state = h.makeState(
            session = testSession(directory = "/tmp/demo", model = OpenCodeModelRef(id = "model-a", providerID = "example", variant = "high")),
        ).copy(models = catalog.models)
        state.favoriteModelIDs.set(setOf(fast.id))
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(modelCatalog = { _, _ -> catalog })
        }
        // Opening refreshes the list behind the picker; the answer replaces it.
        store.send(Action.ModelButtonTapped) { it.copy(activePicker = SessionFeature.State.ActivePicker.model) }
        store.receive(Action.ModelsResponse::class)
        store.state.assertRemembersModelNames(catalog.models)

        fun rows() = store.withDependencies { store.state.favoriteModels + store.state.unfavoriteModels }
        assertEquals(listOf("example/model-a-fast", "example/model-a"), rows().map { it.id })
        assertEquals(listOf(fast), store.state.favoriteModels)
        assertEquals(listOf(base), rows().filter { it.id == store.state.currentModelInfo?.id })
        assertEquals("Model A · High", store.state.currentModelLabel)

        store.send(Action.ModelPicked(fast)) {
            it.copy(
                currentModel = OpenCodeModelRef(id = "model-a-fast", providerID = "example", variant = "high"),
                pendingModel = OpenCodeModelRef(id = "model-a-fast", providerID = "example", variant = "high"),
                activePicker = null,
            )
        }
        assertEquals(listOf(fast), rows().filter { it.id == store.state.currentModelInfo?.id })
        assertEquals("Model A Fast · High", store.state.currentModelLabel)
    }

    @Test
    fun togglingFavoritePinsModel() = sessionTest { h ->
        val fast = OpenCodeModel(modelID = "example-model-fast", providerID = "opencode", name = "Example Model Fast")
        val tiny = OpenCodeModel(modelID = "example-model-tiny", providerID = "opencode", name = "Example Model Tiny")
        val state = h.makeState().copy(models = listOf(tiny, fast))
        val store = h.store(state)

        store.send(Action.ToggleFavoriteModel(fast))
        assertEquals(setOf(fast.id), store.state.favoriteModelIDs.value)
        assertEquals(listOf(fast), store.state.favoriteModels)
        assertEquals(listOf(tiny), store.state.unfavoriteModels)

        store.send(Action.ToggleFavoriteModel(fast))
        assertEquals(emptySet(), store.state.favoriteModelIDs.value)
    }

    private val page = SessionFeature.AuthoritativeRefresh.Scope.Page

    private fun refreshResponse(sessionID: String, refresh: SessionFeature.AuthoritativeRefresh): Action =
        Action.RefreshResponse(sessionID = sessionID, requestID = 1, result = Result.success(refresh))
}
