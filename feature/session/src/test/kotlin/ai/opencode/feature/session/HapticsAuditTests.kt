package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.LocationClient
import ai.opencode.feature.composer.LocationClientError
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The `SessionView.composerSwipeYields` / `performSwipeUpFocus` window and text-view checks of
 * `swipingUpOnTheComposerTapsAndRequestsFocusUnlessDictating` (its reducer half is here).
 */
class HapticsAuditTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)
    private val json = Json { ignoreUnknownKeys = true }

    private fun Harness.sessionState(isRunning: Boolean = false): SessionFeature.State =
        state { SessionFeature.State.session(server = server, session = session, cachedItems = emptyList(), isRunning = isRunning) }

    private fun Harness.sessionStore(
        state: SessionFeature.State?,
        log: HapticsRecorder,
        dependencies: DependencyValues.Builder.() -> Unit = {},
    ): TestStore<SessionFeature.State, Action> {
        val store = store(state ?: sessionState()) {
            this[UuidClient] = UuidClient.incrementing()
            this[HapticsClient] = log.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
            dependencies()
        }
        store.exhaustive = false
        return store
    }

    private val HapticsRecorder.names: List<String> get() = log.map { it.name }

    /** The names played from here on. */
    private class Since(private val log: HapticsRecorder) {
        private var start = log.log.size
        fun reset() { start = log.log.size }
        val names: List<String> get() = log.log.drop(start).map { it.name }
    }

    private fun DependencyValues.Builder.dictation(
        start: suspend (Int) -> kotlinx.coroutines.channels.ReceiveChannel<String>,
        stop: suspend (Int) -> Unit = {},
        cancel: suspend (Int) -> Unit = {},
    ) {
        this[DictationClient] = DictationClient(start = start, stop = stop, cancel = cancel)
    }

    private val dictationReady: (Action) -> Boolean = { it == Action.dictationReady }

    @Test fun sendingSteeredOrQueuedIsOneTap() {
        for (action in listOf(Action.SendTapped, Action.QueueTapped)) sessionTest { h ->
            val log = HapticsRecorder()
            val store = h.sessionStore(h.sessionState().withDraft("Ship it"), log)
            store.send(action)
            assertEquals("$action", listOf("tap"), log.names)
            h.skipInFlightEffects()
        }
    }

    @Test fun aFailedSendIsAnError() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(
            Action.PromptFailed(
                sessionID = session.id,
                error = OpenCodeClientError(message = "Offline"),
                pending = PendingPrompt(messageID = "msg_1", text = "Ship it", attachments = emptyList(), requestEpoch = 1),
            ),
        )
        assertEquals(listOf("error"), log.names)
        h.skipInFlightEffects()
    }

    private val permission: OpenCodePermissionRequest
        get() = json.decodeFromString(
            OpenCodePermissionRequest.serializer(),
            """{"id":"per_1","sessionID":"ses_1","action":"shell","resources":["git status"],"source":{"type":"tool","messageID":"msg_1","id":"call_1"}}""",
        )

    @Test fun answeringARequestIsOneSuccessNotATapAndASuccess() = sessionTest { h ->
        val permission = permission
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(replyPermission = { _, _, _, _, _ -> })
        }
        // A request that arrives while the session is open docks with a single attention tap.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))))
        assertNull(store.state.activePicker)
        assertEquals(permission.id, store.state.dockRequestID)
        assertEquals(listOf("attention"), log.names)
        store.send(Action.permissionReplyTapped(permission.id, OpenCodePermissionReply.Once))
        store.receive({ (it as? Action.RequestDock)?.action is ai.opencode.feature.composer.RequestDockFeature.Action.ResponseFinished }, "requestResponseFinished")
        assertEquals(listOf("attention", "success"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun aCoveredScreenLeavesTheArrivalToTheScreenOnTop() = sessionTest { h ->
        val permission = permission
        val log = HapticsRecorder()
        val store = h.sessionStore(h.sessionState().copy(isCovered = true), log)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()))))
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(permission), forms = emptyList()))))
        assertEquals(permission.id, store.state.dockRequestID)
        assertTrue(log.names.isEmpty())
        h.skipInFlightEffects()
    }

    @Test fun anIncompleteFormIsAnError() = sessionTest { h ->
        val form = json.decodeFromString(
            OpenCodeFormRequest.serializer(),
            """{"id":"frm_1","sessionID":"ses_1","title":"Deploy","fields":[{"key":"note","type":"string","title":"Note","required":true}]}""",
        )
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form)))))
        val since = Since(log)
        store.send(Action.formSubmitTapped(form.id))
        assertNotNull(store.state.requestErrors[form.id])
        assertEquals(listOf("error"), since.names)
        h.skipInFlightEffects()
    }

    @Test fun dictationStartsWithATapAndLocksWithASelection() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            dictation(start = { transcripts }, stop = { transcripts.close() })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        store.send(Action.micPressLocked)
        assertEquals(listOf("tap", "selection"), log.names)
        transcripts.close()
        h.skipInFlightEffects()
    }

    @Test fun armingAHeldMicCrossesAThresholdAndDisarmingTicks() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            dictation(start = { transcripts }, stop = { transcripts.close() })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        val since = Since(log)
        store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Lock))
        store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Lock))
        store.send(Action.micPressArmed(null))
        store.send(Action.micPressArmed(DictationFeature.Hold.Arm.Cancel))
        // Arming crosses a threshold and disarming ticks, the same for lock and cancel.
        assertEquals(listOf("threshold", "selection", "threshold"), since.names)
        transcripts.close()
        h.skipInFlightEffects()
    }

    @Test fun cancellingDictationBacksOut() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            dictation(start = { transcripts }, stop = { transcripts.close() }, cancel = { transcripts.close() })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        store.send(Action.micPressLocked)
        val since = Since(log)
        store.send(Action.micPressCancelled)
        assertEquals(listOf("cancel"), since.names)
        transcripts.close()
        h.skipInFlightEffects()
    }

    @Test fun stopBacksOut() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(h.sessionState(isRunning = true), log) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(interrupt = { _, _ -> true })
        }
        store.send(Action.InterruptTapped)
        assertEquals(listOf("cancel"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun pickingAModelIsASelection() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        val model = OpenCodeModel(modelID = "m1", providerID = "p1", name = "Model One")
        store.send(Action.ModelPicked(model))
        assertEquals(listOf("selection"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun aDictatedSendTapsOnceAtThePress() = sessionTest { h ->
        val log = HapticsRecorder()
        val transcripts = Channel<String>(Channel.UNLIMITED)
        val store = h.sessionStore(null, log) {
            this[DateClient] = DateClient.constant(Instant.ofEpochSecond(1_000))
            dictation(start = { transcripts }, stop = {
                transcripts.send("Ship it")
                transcripts.close()
            })
        }
        store.send(Action.micPressBegan)
        store.receive(dictationReady, "dictationReady")
        store.send(Action.micPressLocked)
        val since = Since(log)
        store.send(Action.dictationSendTapped)
        store.receive(Action.DeferredSendReady::class)
        assertEquals(listOf("tap"), since.names)
        h.skipInFlightEffects()
    }

    @Test fun reChoosingTheCurrentModelOrLevelIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val model = OpenCodeModel(modelID = "m1", providerID = "p1", name = "Model One", variants = listOf("low", "high"))
        val store = h.sessionStore(h.sessionState().copy(currentModel = model.ref("high")), log)
        store.send(Action.ModelPicked(model))
        store.send(Action.VariantPicked("high"))
        assertTrue(log.names.isEmpty())
        store.send(Action.VariantPicked("low"))
        assertEquals(listOf("selection"), log.names)
        h.skipInFlightEffects()
    }

    private val projects = listOf("opencode-ios", "opencode-slack").map { OpenCodeProject(id = it, name = it, directory = "/srv/$it") }

    private fun Harness.newSessionStore(log: HapticsRecorder, picker: Boolean = false): TestStore<SessionFeature.State, Action> {
        var state = state { SessionFeature.State.draft(server = server, draftProject = projects[0], projects = projects) }
        if (picker) state = state.openProjectPicker()
        state.agentSelection.isEnabled.set(false)
        return store(state) { this[HapticsClient] = log.client }
    }

    @Test fun pickingAnotherRecentProjectIsASelectionAndTheChosenOneIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log)
        store.send(Action.DraftProjectTapped(projects[0]))
        assertTrue("The project already chosen is a no-op", log.names.isEmpty())
        store.send(Action.DraftProjectTapped(projects[1])) { it.copy(draftProject = projects[1]) }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse }, "agentsResponse")
        assertEquals(listOf("selection"), log.names)
        store.send(Action.DraftProjectTapped(projects[1]))
        assertEquals(listOf("selection"), log.names)
        store.finish()
    }

    @Test fun allProjectsOpensQuietlyAndAPickInsideItIsASelection() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log)
        store.send(Action.ProjectButtonTapped) { it.openProjectPicker() }
        assertTrue("Opening the sheet is navigation", log.names.isEmpty())
        store.send(Action.ProjectPicked(projects[1])) { it.copy(draftProject = projects[1], activePicker = null) }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse }, "agentsResponse")
        assertEquals(listOf("selection"), log.names)
        store.finish()
    }

    @Test fun pickingTheChosenProjectInTheSheetIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log, picker = true)
        store.send(Action.ProjectPicked(projects[0])) { it.copy(activePicker = null) }
        assertTrue(log.names.isEmpty())
        store.finish()
    }

    @Test fun aSystemActionMovingTheDraftIsQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.newSessionStore(log)
        store.send(Action.ProjectPicked(projects[1])) { it.copy(draftProject = projects[1]) }
        store.receive(Action.AgentsRequested)
        store.receive({ it is Action.AgentsResponse }, "agentsResponse")
        assertTrue("A shortcut or the voice agent moving the draft isn't a press", log.names.isEmpty())
        store.finish()
    }

    /** The reducer half. */
    @Test fun swipingUpOnTheComposerTapsAndRequestsFocusUnlessDictating() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(Action.ComposerSwipedUp) { it.copy(composerFocusRequest = 1) }
        assertEquals(listOf("tap"), log.names)

        // While dictating, the folded bar owns the composer: swipe-up does nothing.
        val dictatingStore = h.sessionStore(h.sessionState().withDictationPhase(DictationFeature.Phase.Recording), log)
        dictatingStore.send(Action.ComposerSwipedUp)
        assertEquals(0, dictatingStore.state.composerFocusRequest)
        assertEquals(listOf("tap"), log.names)
        h.skipInFlightEffects()
    }

    @Test fun compactingCompletesWithSuccessAndForkingNavigatesQuietly() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.store(SessionSheetFeature.State(server = server, sessionID = session.id), { SessionSheetFeature() }) {
            this[HapticsClient] = log.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(compactSession = { _, _ -> })
        }
        store.exhaustive = false
        store.send(SessionSheetFeature.Action.CompactTapped)
        store.receive({ it is SessionSheetFeature.Action.CompactFinished }, "compactFinished")
        assertEquals(listOf("success"), log.names)
        store.send(SessionSheetFeature.Action.ForkFinished(Result.success(session)))
        assertEquals(listOf("success"), log.names)
        store.skipReceivedActions()
        h.skipInFlightEffects()
    }

    @Test fun copyingPlaysATapWhileOpeningLocationSettingsStaysQuiet() = sessionTest { h ->
        val log = HapticsRecorder()
        val copied = LockIsolated(listOf<String>())
        val copiedURLs = LockIsolated(listOf<String>())
        val opened = LockIsolated(listOf<String>())
        val state = h.sessionState().withLocationError(LocationClientError.Denied.message)
        val store = h.sessionStore(state, log) {
            this[PasteboardClient] = PasteboardClient(setURL = { copiedURLs.append(it) }, setString = { copied.append(it) })
            this[OpenURLClient] = OpenURLClient(open = { opened.append(it); true })
            this[LocationClient] = LocationClient(current = { throw LocationClientError.Denied }, openSettings = { opened.append("app-settings:") })
        }
        store.send(Action.CopyTextTapped(""))
        assertTrue("An empty copy is a no-op", log.names.isEmpty())
        store.send(Action.CopyTextTapped("markdown reply"))
        assertEquals(listOf("markdown reply"), copied.value)
        assertEquals(listOf("tap"), log.names)
        store.send(Action.LinkMenu(LinkMenuAction.Copy, "https://opencode.ai/docs"))
        assertEquals(listOf("https://opencode.ai/docs"), copiedURLs.value)
        assertEquals(listOf("tap", "tap"), log.names)
        store.send(Action.SessionInfoTapped)
        assertEquals(SessionFeature.State.ActivePicker.session, store.state.activePicker)
        assertEquals("Opening the session sheet from the title taps", listOf("tap", "tap", "tap"), log.names)
        store.send(Action.locationSettingsTapped)
        assertNull(store.state.locationError)
        settle()
        assertEquals(1, opened.value.size)
        assertEquals("Opening location settings is navigation, so quiet", listOf("tap", "tap", "tap"), log.names)
        h.skipInFlightEffects()

        reopened { h2 ->
            val sheetStore = h2.store(SessionSheetFeature.State(server = server, sessionID = session.id), { SessionSheetFeature() }) {
                this[HapticsClient] = log.client
                this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
            }
            sheetStore.send(SessionSheetFeature.Action.CopySessionIDTapped)
            sheetStore.finish()
            assertEquals(listOf("markdown reply", session.id), copied.value)
            assertEquals(listOf("tap", "tap", "tap", "tap"), log.names)

            val shellState = ShellOutputFeature.State(
                server = server,
                directory = session.directory,
                shell = OpenCodeShell(id = "sh_1", command = "ls", sessionID = session.id, status = OpenCodeShell.Status.Exited),
            ).copy(output = "README.md\n")
            val shellStore = h2.store(shellState, { ShellOutputFeature() }) {
                this[HapticsClient] = log.client
                this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { copied.append(it) })
            }
            shellStore.send(ShellOutputFeature.Action.CopyOutputTapped)
            shellStore.finish()
            assertEquals("README.md\n", copied.value.last())
            assertEquals(listOf("tap", "tap", "tap", "tap", "tap"), log.names)
        }
    }

    @Test fun quotingSelectedTextPlaysOneTap() = sessionTest { h ->
        val log = HapticsRecorder()
        val store = h.sessionStore(null, log)
        store.send(Action.QuoteTextTapped("Keep the existing parser boundary"))
        assertEquals(listOf("tap"), log.names)
        store.send(Action.QuoteTextTapped("   "))
        assertEquals(listOf("tap"), log.names)
        h.skipInFlightEffects()
    }

    private suspend fun reopened(block: suspend (Harness) -> Unit) {
        kotlinx.coroutines.coroutineScope {
            val harness = Harness(this)
            try {
                block(harness)
            } finally {
                harness.skipInFlightEffects()
            }
        }
    }
}
