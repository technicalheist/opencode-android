package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.BufferConverter
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.DictationAudioFormat
import ai.opencode.feature.composer.DictationBuffer
import ai.opencode.feature.composer.DictationCapture
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationEngine
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DictationInput
import ai.opencode.feature.composer.DictationInputSink
import ai.opencode.feature.composer.DictationModel
import ai.opencode.feature.composer.DictationResult
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.LiveDictation
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parameterized tests loop over their arguments. `PressMic` drives the real `LiveDictation` against
 * a fake microphone and model.
 */
class SessionFeatureTestsDictation {
    private val sentID = FailedSendsSupport.sentID

    private fun DependencyValues.Builder.sendClient(session: ai.opencode.server.model.OpenCodeSession, expectedText: String) {
        this[UuidClient] = UuidClient.incrementing()
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            sendPrompt = { _, _, text, _, _, _, _ -> assertEquals(expectedText, text) },
            waitForIdle = { _, _ -> },
            getSession = { _, _ -> session },
            transcript = { _, _, _ -> emptyList() },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
            pendingInputs = { _, _ -> emptyList() },
        )
    }

    private fun State.withHoldText(text: String): State = withDictationHold(dictationHold?.copy(text = text))

    @Test fun dictationSendWaitsForFinalTranscript() = sessionTest { h ->
        val session = testSession(directory = "/tmp/demo")
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState(session = session)) {
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    feed.trySend("Send all final words")
                    feed.close()
                },
            )
            sendClient(session, "Send all final words")
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.send(Action.dictationSendTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer)) }
        store.receive(Action.dictationUpdated("Send all final words")) { it.withDraft("Send all final words") }
        store.receive(Action.dictationStopped(errorMessage = null)) { it.withDictationPhase(DictationFeature.Phase.Idle) }
        store.receive(Action.DeferredSendReady(OpenCodeDelivery.Steer)) {
            it.copy(
                optimisticPromptIDs = setOf(sentID),
                items = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Send all final words")),
            ).withDraft("")
        }
    }

    @Test fun heldMicKeepsWordsOffTheDraftAndSendsOnRelease() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState()) {
            this[DictationClient] = DictationClient(
                start = { feed },
                stop = {
                    feed.trySend("Rename the tab bar")
                    feed.close()
                },
            )
            sendClient(testSession(), "Rename the tab bar")
        }
        store.exhaustive = false

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(DictationFeature.Hold(text = ""))
        }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.trySend("Rename the")
        // Recording: words wait off the draft, as Telegram shows only the timer.
        store.receive(Action.dictationUpdated("Rename the")) { it.withHoldText("Rename the") }
        assertEquals("", store.state.draft)
        store.send(Action.micPressReleased) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer)) }
        store.receive(Action.dictationUpdated("Rename the tab bar")) { it.withHoldText("Rename the tab bar") }
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationHold(null).withDraft("Rename the tab bar")
        }
        store.receive(Action.DeferredSendReady(OpenCodeDelivery.Steer)) {
            it.copy(
                optimisticPromptIDs = setOf(sentID),
                items = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Rename the tab bar")),
            ).withDraft("")
        }
    }

    /** Holds an async step until the test opens it. */
    internal class Gate(open: Boolean = false) {
        private val opened = CompletableDeferred<Unit>().apply { if (open) complete(Unit) }
        suspend fun await() = opened.await()
        fun open() {
            opened.complete(Unit)
        }
    }

    /**
     * A microphone and a speech model that load on cue: the mic hears from
     * the moment capture starts; the model gets whatever it heard on connect.
     */
    internal class PressMic(permits: Boolean = true) {
        val format = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)
        private val analyzerFormat = DictationAudioFormat(sampleRate = 16_000, channelCount = 1)
        val input = LockIsolated<DictationInput?>(null)
        val claims = LockIsolated(0)
        val captures = LockIsolated(0)
        val micsStopped = LockIsolated(0)
        val modelsStarted = LockIsolated(0)

        /** Stops and cancels that have reached the dictation actor. */
        val ended = LockIsolated(0)

        /** What reached the model, one value per buffer. */
        val heard = LockIsolated(listOf<Short>())
        val modelLoaded = Gate()
        val permitted = Gate(open = permits)
        private val background = CoroutineScope(Dispatchers.Default)

        val micIsOn: Boolean get() = captures.value > micsStopped.value

        val client: DictationClient by lazy {
            val owner = LockIsolated(0)
            val live = LiveDictation(
                DictationEngine(
                    requestPermission = {
                        permitted.await()
                        true
                    },
                    claimSession = {
                        claims.increment()
                        owner.increment()
                    },
                    sessionOwner = { owner.value },
                    startCapture = { input, _ ->
                        captures.increment()
                        this.input.setValue(input)
                        DictationCapture(format = format) { micsStopped.increment() }
                    },
                    releaseSession = { _, _ -> },
                    loadModel = {
                        modelLoaded.await()
                        DictationModel(
                            start = { input, format ->
                                modelsStarted.increment()
                                val audio = Channel<ShortArray>(Channel.UNLIMITED)
                                input.connect(
                                    BufferConverter(format, analyzerFormat),
                                    object : DictationInputSink {
                                        override fun yield(samples: ShortArray) {
                                            audio.trySend(samples)
                                        }

                                        override fun finish() {
                                            audio.close()
                                        }
                                    },
                                )
                                val results = Channel<DictationResult>(Channel.UNLIMITED)
                                background.launch {
                                    for (samples in audio) {
                                        if (samples.isEmpty()) continue
                                        heard.append(samples.last())
                                    }
                                    results.close()
                                }
                                results
                            },
                            finish = {},
                            cancel = {},
                        )
                    },
                ),
            )
            DictationClient(
                prepare = { live.prepare() },
                begin = { live.begin() },
                start = { live.start(it) },
                stop = {
                    live.stop(it)
                    ended.increment()
                },
                cancel = {
                    live.cancel(it)
                    ended.increment()
                },
            )
        }

        /** The mic hears a buffer whose samples are all `value`. */
        fun hear(value: Short) {
            input.value?.append(DictationBuffer(ShortArray(160) { value }, format))
        }

        suspend fun until(condition: () -> Boolean) {
            repeat(500) {
                if (condition()) return
                delay(2)
            }
        }
    }

    /**
     * A hold records from touch-down: the press alone starts capture, before
     * any lift could tell a hold from a tap, and the model is not awaited.
     */
    @Test fun aHeldMicStartsCaptureOnTouchDown() = sessionTest { h ->
        val mic = PressMic()
        val store = h.store(h.makeState()) { this[DictationClient] = mic.client }
        store.exhaustive = false

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(DictationFeature.Hold(text = ""))
        }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        assertNotNull("Capture started with the finger still down", mic.input.value)
        assertEquals("Capture never waits on the model", 0, mic.modelsStarted.value)
        assertEquals("Still a hold", false, store.state.dictationHold?.isLocked)

        store.send(Action.micPressCancelled)
        mic.until { mic.micsStopped.value == 1 }
    }

    /**
     * Words spoken while the model loads are kept and reach it first, in
     * order, once it's ready; letting go then finalizes them.
     */
    @Test fun audioHeardBeforeTheModelIsReadyReachesIt() = sessionTest { h ->
        val mic = PressMic()
        val store = h.store(h.makeState()) {
            this[DictationClient] = mic.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(sendPrompt = { _, _, _, _, _, _, _ -> })
        }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        mic.hear(1)
        mic.hear(2)
        assertEquals(0, mic.modelsStarted.value)
        mic.modelLoaded.open()
        mic.until { mic.heard.value.size == 2 }
        mic.hear(3)
        mic.until { mic.heard.value.size == 3 }
        assertEquals(listOf<Short>(1, 2, 3), mic.heard.value)

        store.send(Action.micPressReleased) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer)) }
        store.receive({ it is Action.Dictation && it.action is DictationFeature.Action.Stopped }, "dictationStopped")
        assertEquals(DictationFeature.Phase.Idle, store.state.dictationPhase)
        assertEquals(1, mic.micsStopped.value)
    }

    /**
     * Cancelling the press throws away what the mic heard before the model
     * was ready: nothing reaches it, and the mic stops at once.
     */
    @Test fun aCancelledPressDiscardsItsBufferedAudio() = sessionTest { h ->
        val mic = PressMic()
        val store = h.store(h.makeState(draft = "Check")) {
            this[DictationClient] = mic.client
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("A cancelled press must not send") },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady)
        mic.hear(1)
        mic.hear(2)
        store.send(Action.micPressCancelled) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationHold(null)
        }
        mic.until { mic.micsStopped.value == 1 }
        assertEquals(true, mic.input.value?.isDiscarded)
        mic.modelLoaded.open()
        delay(50)
        assertEquals("A discarded press never feeds the model", 0, mic.modelsStarted.value)
        assertTrue(mic.heard.value.isEmpty())
        assertEquals("Check", store.state.draft)
    }

    /**
     * A press cancelled, or stopped, before its recording reached the mic
     * (here held at the permission prompt) never turns it on afterwards.
     */
    @Test fun aPressEndedBeforeItRecordsNeverStartsTheMic() {
        for (end in listOf(Action.micPressCancelled, Action.dictationInsertTapped)) sessionTest { h ->
            val mic = PressMic(permits = false)
            val store = h.store(h.makeState()) { this[DictationClient] = mic.client }
            store.exhaustive = false

            store.send(Action.micPressBegan) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
            store.send(end)
            // Wait until the end's effect has reached the dictation actor before the grant (a fixed
            // number of yields lost the race under load).
            mic.until { mic.ended.value > 0 }
            assertEquals("$end never reached the dictation actor", 1, mic.ended.value)
            mic.permitted.open()
            delay(80)
            store.skipReceivedActions()
            assertEquals("$end left the session claimed", 0, mic.claims.value)
            assertEquals("$end turned the mic on afterwards", 0, mic.captures.value)
            assertEquals("$end left the bar waiting", DictationFeature.Phase.Idle, store.state.dictationPhase)
        }
    }

    /**
     * The previous press's cancel lands after the next press began: only
     * its own press ends, and the new one records.
     */
    @Test fun anEarlierPressesLateCancelLeavesTheNextPressRecording() = sessionTest { h ->
        val mic = PressMic(permits = false)
        val store = h.store(h.makeState()) { this[DictationClient] = mic.client }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        val first = store.state.dictationPress
        store.send(Action.micPressCancelled)
        store.send(Action.micPressBegan)
        assertNotEquals(first, store.state.dictationPress)
        mic.permitted.open()
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        mic.until { mic.captures.value == 1 }
        delay(50)
        assertEquals("Only the second press records", 1, mic.captures.value)
        assertTrue("The first press's cancel stopped the second's mic", mic.micIsOn)
        assertTrue(store.state.isDictating)

        store.send(Action.micPressCancelled)
        mic.until { !mic.micIsOn }
        assertFalse(mic.micIsOn)
    }

    /** A call owns the audio session while it lasts: the mic takes no press. */
    @Test fun aPressDuringACallNeverTakesTheMic() = sessionTest { h ->
        val mic = PressMic()
        var state = h.makeState()
        val call = h.state { VoiceFeature.State(state.server, state.session!!) }.withPhase(VoiceFeature.State.Phase.Live)
        state = state.copy(voice = call)
        val store = h.store(state) { this[DictationClient] = mic.client }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.send(Action.micTapped)
        delay(50)
        assertEquals(DictationFeature.Phase.Idle, store.state.dictationPhase)
        assertNull(store.state.dictationHold)
        assertEquals(0, mic.claims.value)
    }

    /**
     * The microphone permission prompt takes the touch, so the finger lifts
     * while setup is still waiting on Allow. Recording must carry on locked.
     */
    @Test fun releasingWhilePreparingLocksTheRecordingInsteadOfStopping() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val allow = CompletableDeferred<Unit>()
        val stops = LockIsolated(0)
        val store = h.store(h.makeState()) {
            this[DictationClient] = DictationClient(
                start = {
                    allow.await()
                    feed
                },
                stop = { stops.increment() },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Letting go before the mic was ready must not send") },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(DictationFeature.Hold(text = ""))
        }
        store.send(Action.micPressReleased) { it.withDictationHold(it.dictationHold?.copy(isLocked = true)) }
        assertEquals(DictationFeature.Phase.Preparing, store.state.dictationPhase)
        assertEquals("Setup must not be stopped", 0, stops.value)

        allow.complete(Unit)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        assertEquals(true, store.state.dictationHold?.isLocked)
        feed.trySend("Rename the tab bar")
        store.receive(Action.dictationUpdated("Rename the tab bar")) { it.withHoldText("Rename the tab bar") }
        assertEquals("", store.state.draft)
        assertTrue(store.state.items.isEmpty())
        assertEquals(0, stops.value)
        feed.close()
        store.receive(Action.dictationStopped(errorMessage = null))
        assertEquals("Rename the tab bar", store.state.draft)
        assertTrue(store.state.items.isEmpty())
    }

    /**
     * Background audio keeps the mic on when the app leaves, and so does the recording: nothing
     * stops, nothing is sent, and the words keep coming. A held mic's touch that ends as the scene
     * goes inactive, as a lift or a cancel, locks the recording instead of sending it.
     */
    @Test fun leavingTheAppKeepsRecordingAndNeverSends() {
        for (touchEnd in listOf("tap", "lift", "cancel")) sessionTest { h ->
            val feed = Channel<String>(Channel.UNLIMITED)
            val stops = LockIsolated(0)
            // Not yet polling, so the return has no pending work to restart.
            val store = h.store(h.makeState(draft = "Check", hasRenderedFirstContentFrame = false)) {
                this[DictationClient] = DictationClient(
                    start = { feed },
                    stop = {
                        stops.increment()
                        feed.trySend("the failing test suite")
                        feed.close()
                    },
                )
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Leaving the app must not send a dictation") },
                )
            }
            store.exhaustive = false

            store.send(if (touchEnd == "tap") Action.micTapped else Action.micPressBegan)
            store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
            feed.trySend("the failing")
            store.receive(Action.dictationUpdated("the failing"))
            store.send(Action.SceneActiveChanged(false)) { it.copy(isSceneActive = false) }
            if (touchEnd == "lift") store.send(Action.micPressReleased)
            if (touchEnd == "cancel") store.send(Action.micPressInterrupted)
            if (touchEnd != "tap") assertEquals(touchEnd, true, store.state.dictationHold?.isLocked)
            store.send(Action.SceneEnteredBackground)
            assertEquals(touchEnd, DictationFeature.Phase.Recording, store.state.dictationPhase)
            assertEquals(touchEnd, 0, stops.value)
            // Still listening in the background.
            feed.trySend("the failing test")
            store.receive(Action.dictationUpdated("the failing test"))
            store.send(Action.SceneActiveChanged(true)) { it.copy(isSceneActive = true) }
            assertTrue(touchEnd, store.state.isDictating)
            val shown = store.state.dictationHold?.text ?: store.state.draft
            assertEquals(touchEnd, "Check the failing test", shown)
            store.send(Action.dictationInsertTapped)
            store.receive({ it is Action.Dictation && it.action is DictationFeature.Action.Stopped }, "dictationStopped")
            store.finish()
            assertEquals(DictationFeature.Phase.Idle, store.state.dictationPhase)
            assertEquals(touchEnd, "Check the failing test suite", store.state.draft)
            assertTrue(touchEnd, store.state.items.isEmpty())
        }
    }

    /**
     * A phone call, Siri, or a call taking the audio session ends the
     * recording from the client: the stream finishes while recording. The
     * words wait in the draft, unsent.
     */
    @Test fun anInterruptionKeepsTheWordsAsADraft() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.makeState(draft = "Check")) {
            this[DictationClient] = DictationClient(start = { feed })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("An interrupted recording must not send") },
            )
        }
        store.exhaustive = false
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.micPressLocked) { it.withDictationHold(it.dictationHold?.copy(isLocked = true)) }
        feed.trySend("the failing test")
        store.receive(Action.dictationUpdated("the failing test"))
        feed.close()
        store.receive(Action.dictationStopped(errorMessage = null)) {
            it.withDictationPhase(DictationFeature.Phase.Idle).withDictationHold(null).withDraft("Check the failing test")
        }
        store.finish()
        assertTrue(store.state.items.isEmpty())
    }

    /**
     * The speech model can still be loading (or downloading) when a press
     * lands: the mic starts on the press anyway, the bar records, and the
     * words heard meanwhile arrive once the model catches up.
     */
    @Test fun aPressRecordsWhileTheModelIsStillPreparing() = sessionTest { h ->
        val project = OpenCodeProject(id = "p", name = "project", directory = "/project")
        val feed = Channel<String>(Channel.UNLIMITED)
        val modelReady = CompletableDeferred<Unit>()
        val preparing = LockIsolated(false)
        val store = h.store(h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }) {
            this[DictationClient] = DictationClient(
                prepare = {
                    preparing.setValue(true)
                    modelReady.await()
                    preparing.setValue(false)
                },
                start = { feed },
                stop = { feed.close() },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) })
        }
        store.exhaustive = false

        store.send(Action.Task)
        repeat(100) { if (!preparing.value) settle(1) }
        assertTrue(preparing.value)
        store.send(Action.micPressBegan) {
            it.withDictationPhase(DictationFeature.Phase.Preparing).withDictationHold(DictationFeature.Hold(text = ""))
        }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        assertTrue("The mic doesn't wait for the model", preparing.value)
        modelReady.complete(Unit)
        // The first words, heard before the model was ready, come with its first result.
        feed.trySend("first words")
        store.receive(Action.dictationUpdated("first words")) { it.withHoldText("first words") }
        store.send(Action.dictationInsertTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = null)) }
        store.receive(Action.dictationStopped(errorMessage = null))
        assertEquals("first words", store.state.draft)
    }

    /**
     * A switch to a subagent or back to the main session carries a running
     * recording like Home's switches do: a held mic goes on locked (lifting
     * sends nothing), a send waiting on its last words lands as a draft
     * there, and what was typed stays with the session it was typed in.
     */
    @Test fun aFamilySwitchCarriesTheRecordingWithoutSending() {
        for (released in listOf(false, true)) aFamilySwitchCarriesTheRecordingWithoutSending(released)
    }

    private fun aFamilySwitchCarriesTheRecordingWithoutSending(released: Boolean) = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root")
        val child = testSession(id = "ses_child", title = "Child", updatedAt = distantPast, parentID = root.id)
        val persistence = ComposerPersistence.inMemory()
        val withPersistence = h.values.update { this[ComposerPersistence] = persistence }
        val state = DependencyValues.withValues(withPersistence) {
            h.makeState(session = root, draft = "Root typed")
                .copy(familySessions = listOf(root, child))
                .settingFamilyDraft(child.id, DraftState(text = "Child typed"))
        }
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(state) {
            this[ComposerPersistence] = persistence
            this[DictationClient] = DictationClient(start = { feed }, stop = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("A recording carried across a family switch must not send") },
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.trySend("the failing")
        store.receive(Action.dictationUpdated("the failing"))
        if (released) {
            store.send(Action.micPressReleased) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer)) }
        }
        store.send(Action.FamilyPicked(child.id))
        assertEquals(child, store.state.session)
        assertEquals("Root typed", store.withDependencies { store.state.familyDrafts[root.id]?.text })
        assertEquals(true, store.state.dictationHold?.isLocked)
        assertEquals("Child typed the failing", store.state.dictationHold?.text)
        if (released) {
            assertEquals(DictationFeature.Phase.Finishing(send = null), store.state.dictationPhase)
        } else {
            store.send(Action.micPressReleased)
            assertEquals("lifting after the switch sends nothing", DictationFeature.Phase.Recording, store.state.dictationPhase)
            store.send(Action.dictationInsertTapped)
        }
        feed.trySend("the failing test")
        feed.close()
        store.receive({ it is Action.Dictation && it.action is DictationFeature.Action.Stopped }, "dictationStopped")
        assertEquals(DictationFeature.Phase.Idle, store.state.dictationPhase)
        assertEquals("released=$released", "Child typed the failing test", store.state.draft)
        h.skipInFlightEffects()
    }

    /**
     * A message let go to send whose last words are still landing while the
     * next recording runs goes out only while the user is here to see it, in
     * the session it was sent in. Leaving the app, Stop on the Live Activity,
     * or a switch to a subagent leaves its words as a draft instead.
     */
    @Test fun aFinalizingMessageNeverSendsUnseen() {
        for (path in listOf("background", "activityStop", "familySwitch")) aFinalizingMessageNeverSendsUnseen(path)
    }

    private fun aFinalizingMessageNeverSendsUnseen(path: String) = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root")
        val child = testSession(id = "ses_child", title = "Child", updatedAt = distantPast, parentID = root.id)
        val persistence = ComposerPersistence.inMemory()
        val state = h.makeState(session = root).copy(familySessions = listOf(root, child))
        val first = Channel<String>(Channel.UNLIMITED)
        val second = Channel<String>(Channel.UNLIMITED)
        val streams = LockIsolated(listOf<ReceiveChannel<String>>(first, second))
        val store = h.store(state) {
            this[ComposerPersistence] = persistence
            this[DictationClient] = DictationClient(start = { streams.withValue { it.drop(1) to it.first() } })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("A finalizing message must not send unseen ($path)") },
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        first.trySend("first message")
        store.receive(Action.dictationUpdated("first message"))
        store.send(Action.micPressReleased) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = OpenCodeDelivery.Steer)) }
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady)
        assertEquals(root.id, store.state.sendingDictation?.sessionID)

        when (path) {
            "background" -> {
                store.send(Action.SceneActiveChanged(false))
                store.send(Action.SceneEnteredBackground)
            }
            "activityStop" -> store.send(Action.dictationStopRequested)
            else -> store.send(Action.FamilyPicked(child.id))
        }
        first.trySend("first message, all of it")
        first.close()
        store.receive(Action.dictationStopped(errorMessage = null, recording = 0))
        assertNull(path, store.state.sendingDictation)
        if (path == "familySwitch") {
            assertEquals(path, "first message, all of it", store.withDependencies { store.state.familyDrafts[root.id]?.text })
            assertEquals(path, "", store.state.draft)
        } else {
            assertEquals(path, "first message, all of it", store.state.draft)
        }
        h.skipInFlightEffects()
    }

    /**
     * A live call or recording carries the screen's identity to another
     * session, so the previous session's loops stop when this one's task
     * starts, and its family response, still in flight, is ignored.
     */
    @Test fun aCarriedScreenStopsThePreviousSessionsWork() = sessionTest { h ->
        val previous = testSession(id = "ses_previous", title = "Previous")
        val current = testSession(id = "ses_current", title = "Current")
        val state = h.makeState(session = current).copy(initialTranscriptRequestIsExternal = true, inheritsLiveWork = true)
        val familyCancelled = LockIsolated(false)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sessionFamily = { _, _ ->
                    try {
                        awaitCancellation()
                    } catch (cancelled: CancellationException) {
                        familyCancelled.setValue(true)
                        throw cancelled
                    }
                },
                runningShells = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // Stands in for the previous session's refresh, still running under this identity.
        store.send(Action.FamilyRefreshRequested)
        store.send(Action.Task) { it.copy(inheritsLiveWork = false, familyRead = CoalescedRead()) }
        repeat(100) { if (!familyCancelled.value) settle(1) }
        assertTrue(familyCancelled.value)

        store.send(Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(previous), activeSessionIDs = setOf(previous.id)))))
        assertEquals(listOf(current), store.state.familySessions)
        assertTrue(store.state.activeFamilySessionIDs.isEmpty())
        // Nothing of the previous session's is left running.
        store.finish()
    }

    /**
     * Try Again on a failed call takes the mic: the words heard so far wait
     * in the draft instead of being discarded.
     */
    @Test fun tryAgainOnACallCardKeepsTheWords() = sessionTest { h ->
        h.sharedStore.reference(VoiceSharedKeys.voiceServerChoice).set(VoiceServerChoice.Phone)
        val voice = h.state { VoiceFeature.State(testServer, testSession()) }.withPhase(VoiceFeature.State.Phase.Failed("Couldn’t connect"))
        val state = h.makeState(draft = "Check").copy(voice = voice)
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(state) {
            this[DictationClient] = DictationClient(start = { feed })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw AssertionError("Starting a call must not send a recording") },
            )
        }
        store.exhaustive = false
        store.send(Action.micPressBegan)
        store.receive(Action.dictationReady)
        feed.trySend("the failing test")
        store.receive(Action.dictationUpdated("the failing test"))
        store.send(Action.voicePresented(VoiceFeature.Action.Start))
        assertEquals(DictationFeature.Phase.Idle, store.state.dictationPhase)
        assertEquals("Check the failing test", store.state.draft)
        h.skipInFlightEffects()
    }
}
