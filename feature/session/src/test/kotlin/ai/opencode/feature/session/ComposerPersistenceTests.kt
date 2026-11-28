package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.store.TestStore
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.ComposerPersistenceStorage
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.LocationClient
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeUploadEvent
import ai.opencode.server.model.PastedText
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * Android has no per-file backup flag (backup rules are declared in the manifest), so those three
 * assertions have no counterpart.
 */
class ComposerPersistenceTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "opencode", password = "pw")
    private val project = OpenCodeProject(id = "proj_1", name = "OpenCode", directory = "/Users/kit/code/opencode")
    private val session = OpenCodeSession(
        id = "ses_1",
        serverTitle = "Durable drafts",
        projectID = "proj_1",
        directory = "/Users/kit/code/opencode",
        updatedAt = Instant.ofEpochSecond(1_000),
    )
    private val firstID = "msg_00000000000100000000000000"
    private val secondID = "msg_00000000000200000000000000"
    private val claudeModel = OpenCodeModel(modelID = "claude", providerID = "anthropic", name = "Claude")
    private val existing = listOf<OpenCodeTranscriptItem>(OpenCodeTranscriptItem.UserText(id = "msg_existing", text = "Earlier"))

    /** A gate a test opens to let a held request finish. */
    private class Gate {
        private val opened = CompletableDeferred<Unit>()
        suspend fun wait() = opened.await()
        fun open() { opened.complete(Unit) }
    }

    /** Each test's outboxes run their lanes on the test thread for the same order. */
    private lateinit var lanes: CoroutineScope
    private fun newOutbox() = PromptOutbox(scope = lanes)

    private fun persistenceTest(block: suspend CoroutineScope.(Harness) -> Unit) = sessionTest { h ->
        val job = SupervisorJob()
        lanes = CoroutineScope(coroutineContext.minusKey(Job) + job)
        val tempDir = Files.createTempDirectory("ComposerPersistenceTests-").toFile()
        this@ComposerPersistenceTests.tempDir = tempDir
        try {
            block(h)
        } finally {
            job.cancel()
            tempDir.deleteRecursively()
        }
    }

    private lateinit var tempDir: File

    private fun live(): ComposerPersistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds)

    /** A finished upload's cleanup reads the app context's cache folder. */
    private val appContext = object : ContextWrapper(null) {
        override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }

    private fun Harness.sessionState(external: Boolean = true): SessionFeature.State = state {
        SessionFeature.State.session(server = server, session = session, cachedItems = existing, initialTranscriptRequestIsExternal = external)
    }

    private fun Harness.draftState(project: OpenCodeProject, projects: List<OpenCodeProject>): SessionFeature.State = state {
        SessionFeature.State.draft(server = server, draftProject = project, projects = projects)
    }

    private fun draft(text: String): Action = Action.Binding(BindingAction.Draft(text))

    private fun DependencyValues.Builder.idleWaitNotUnderTest() {
        this[OpenCodeClient] = this[OpenCodeClient].copy(waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") })
    }

    private fun SessionFeature.State.sent(id: String, text: String, epoch: Int = 1): SessionFeature.State = copy(
        nextPromptRequestEpoch = epoch,
        promptStates = promptStates + (id to SessionFeature.State.PromptState(requestEpoch = epoch, admission = SessionFeature.State.PromptState.Admission.sent)),
        optimisticPromptIDs = optimisticPromptIDs + id,
        items = items + OpenCodeTranscriptItem.UserText(id = id, text = text),
    )

    private fun SessionFeature.State.admitted(id: String): SessionFeature.State =
        copy(promptStates = promptStates + (id to promptStates[id]!!.copy(admission = SessionFeature.State.PromptState.Admission.admitted)))

    @Test fun draftAttachmentsAndLocationSurviveTerminationAndRelaunchWithoutWritingStagedFiles() = persistenceTest { h ->
        val sourceFile = File(tempDir, "notes.txt")
        val fileBytes = "ship durable drafts".toByteArray()
        sourceFile.writeBytes(fileBytes)
        val staged = ComposerStagedFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000010"),
            name = "notes.txt",
            mime = "text/plain",
            size = fileBytes.size.toLong(),
            url = sourceFile,
        )
        val inFlightSource = File(tempDir, "trace.json").apply { writeText("{}") }
        val inFlightStaged = ComposerStagedFile(
            id = UUID.fromString("00000000-0000-0000-0000-000000000011"),
            name = "trace.json",
            mime = "application/json",
            size = 2,
            url = inFlightSource,
        )
        val imageAttachment = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000020",
            data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47),
            mime = "image/png",
            name = "diagram.png",
            videoDuration = 50.0,
            localVideoURI = "content://picker/local-fixture-movie",
        )
        val pastedAttachment = PastedText.attachment((1..14).joinToString("\n") { "line $it" }, id = "00000000-0000-0000-0000-000000000021")
        val sharedLocation = SharedLocation(
            latitude = 37.7749, longitude = -122.4194, accuracy = 12.0,
            place = "City Hall", address = "San Francisco, CA", capturedAt = Instant.ofEpochSecond(1_000),
        )

        val firstLaunchPersistence = live()
        val upload = Channel<OpenCodeUploadEvent>(Channel.UNLIMITED)

        val firstStore = h.store(h.sessionState().copy(models = listOf(claudeModel))) {
            this[AppContext] = appContext
            this[ComposerPersistence] = firstLaunchPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[DictationClient] = DictationClient(prepare = {})
            this[LocationClient] = LocationClient(current = { sharedLocation })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, name ->
                    if (name == "notes.txt") flowOf(OpenCodeUploadEvent.Finished(path = "/tmp/uploaded-notes.txt")) else upload.receiveAsFlow()
                },
            )
        }

        firstStore.send(draft("Keep this draft across termination")) { it.withDraft("Keep this draft across termination") }
        firstStore.send(Action.attachmentAdded(imageAttachment)) { it.withPendingAttachments(listOf(imageAttachment)) }
        firstStore.send(Action.attachmentAdded(pastedAttachment)) { it.withPendingAttachments(listOf(imageAttachment, pastedAttachment)) }
        firstStore.send(Action.filesStaged(listOf(staged, inFlightStaged))) {
            it.withPendingFiles(
                listOf(
                    ComposerFileAttachment(staged, status = ComposerFileAttachment.Status.Uploading(sent = 0)),
                    ComposerFileAttachment(inFlightStaged, status = ComposerFileAttachment.Status.Uploading(sent = 0)),
                ),
            )
        }
        firstStore.receive(Action.fileUploadFinished(staged.id, ComposerAttachmentsFeature.Outcome.Success("/tmp/uploaded-notes.txt"))) {
            it.withPendingFiles(listOf(it.pendingFiles[0].copy(status = ComposerFileAttachment.Status.Uploaded("/tmp/uploaded-notes.txt")), it.pendingFiles[1]))
        }
        upload.send(OpenCodeUploadEvent.Progress(sent = 1))
        firstStore.receive(Action.fileUploadProgress(inFlightStaged.id, 1)) {
            it.withPendingFiles(listOf(it.pendingFiles[0], it.pendingFiles[1].copy(status = ComposerFileAttachment.Status.Uploading(sent = 1))))
        }
        firstStore.send(Action.locationTapped) { it.withIsLocating(true) }
        firstStore.receive(Action.locationResponse(ComposerAttachmentsFeature.Outcome.Success(sharedLocation))) {
            it.withIsLocating(false).withPendingLocation(sharedLocation)
        }

        val stateFile = File(tempDir, "composer-state.json")
        assertFalse("Debounced write has not flushed to disk yet", stateFile.exists())
        assertFalse("Non-image staged files are not copied into Application Support", File(tempDir, "ComposerFiles").exists())

        firstStore.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true) }
        eventuallyTrue { stateFile.exists() }
        assertTrue("Backgrounding flushes composer state to disk", stateFile.exists())
        val frozenSnapshot = stateFile.readBytes()

        // Let the first store finish its in-flight upload effect cleanly, then restore the frozen background snapshot.
        upload.close()
        firstStore.receive(
            Action.fileUploadFinished(inFlightStaged.id, ComposerAttachmentsFeature.Outcome.Failure(OpenCodeClientError(message = "The upload ended early."))),
        ) {
            it.withPendingFiles(listOf(it.pendingFiles[0], it.pendingFiles[1].copy(status = ComposerFileAttachment.Status.Failed("Couldn’t upload"))))
        }
        firstStore.finish()
        stateFile.writeBytes(frozenSnapshot)

        // Simulate a fresh process launch reading from the same Application Support directory.
        val secondLaunchPersistence = live()
        val secondStore = h.store(h.sessionState().copy(models = listOf(claudeModel))) {
            this[ComposerPersistence] = secondLaunchPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[DictationClient] = DictationClient(prepare = {})
        }

        secondStore.send(Action.Task) {
            it.withDraft("Keep this draft across termination")
                .withPendingAttachments(listOf(imageAttachment, pastedAttachment))
                .withPendingFiles(
                    listOf(
                        ComposerFileAttachment(
                            ComposerStagedFile(
                                id = staged.id, name = staged.name, mime = staged.mime, size = staged.size,
                                url = File(System.getProperty("java.io.tmpdir") ?: "/tmp", staged.name),
                            ),
                            status = ComposerFileAttachment.Status.Uploaded("/tmp/uploaded-notes.txt"),
                        ),
                    ),
                )
                .withPendingLocation(sharedLocation)
        }
        secondStore.finish()
    }

    @Test fun answeringPermissionRequestKeepsParkedSessionDraftInPersistence() = persistenceTest { h ->
        val persistence = ComposerPersistence.inMemory(debounce = 60.seconds)
        val request = OpenCodePermissionRequest(id = "perm_1", sessionID = session.id, action = "bash", resources = listOf("rm -rf build"))
        val store = h.store(h.sessionState().copy(models = listOf(claudeModel))) {
            this[ComposerPersistence] = persistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
        }

        store.send(draft("Real session message draft")) { it.withDraft("Real session message draft") }

        // Dock a permission request: the composer becomes the request answer bar and parks the session draft.
        store.send(Action.PendingRequestsResponse(session.id, Result.success(OpenCodePendingRequests(permissions = listOf(request), forms = emptyList())))) {
            it.updateRequestDock { dock ->
                dock.copy(
                    permissionRequests = listOf(request),
                    parkedComposer = DraftState(text = "Real session message draft"),
                    answerShownTarget = "perm_1",
                    requestArrivalsAreLive = true,
                )
            }.withDraft("")
        }

        store.send(draft("Temporary note for permission rejection")) { it.withDraft("Temporary note for permission rejection") }

        val restored = persistence.loadDraft(server.persistenceID, session.id)
        assertEquals("Real session message draft", restored?.draft?.text)
        store.finish()
    }

    @Test fun pendingOutboxPromptResumesAfterRelaunchWithSameMessageIDWithoutDuplicate() = persistenceTest { h ->
        val firstGate = Gate()
        val postedIDs = LockIsolated(listOf<String>())
        val firstPersistence = live()

        val firstStore = h.store(h.sessionState().withDraft("Ship durable outbox")) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = firstPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, id, _ ->
                    postedIDs.append(id)
                    firstGate.wait()
                },
            )
            idleWaitNotUnderTest()
        }

        firstStore.send(Action.Task)
        firstStore.send(Action.SendTapped) {
            val oldSignal = it.workingRunSignal
            it.copy(runWorkGeneration = 1, isAwaitingReply = true, lastSentItemID = firstID, sendGeneration = 1)
                .withDraft("")
                .sent(firstID, "Ship durable outbox")
                .updateWorkingRun(oldSignal)
        }
        firstStore.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true) }

        val stateFile = File(tempDir, "composer-state.json")
        eventuallyTrue { stateFile.exists() }
        val frozenSnapshot = stateFile.readBytes()

        // Let the first store finish its in-flight effect cleanly, then restore the frozen background snapshot.
        firstGate.open()
        firstStore.receive(Action.PromptAdmitted(sessionID = session.id, messageID = firstID, requestEpoch = 1)) { it.admitted(firstID) }
        firstStore.finish()
        stateFile.writeBytes(frozenSnapshot)

        // Relaunch with fresh in-memory outbox, reading persisted state from disk.
        val secondPersistence = live()
        assertEquals(listOf(firstID), secondPersistence.loadOutbox(server.persistenceID, session.id).map { it.pending.messageID })
        assertNull("Sent draft was cleared on send", secondPersistence.loadDraft(server.persistenceID, session.id))

        val relaunchedPosts = LockIsolated(listOf<Pair<String, String>>())
        val secondStore = h.store(h.sessionState()) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = secondPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
                sendPrompt = { _, _, text, _, _, id, _ -> relaunchedPosts.append(id to text) },
            )
            idleWaitNotUnderTest()
        }

        secondStore.send(Action.Task) {
            val oldSignal = it.workingRunSignal
            it.sent(firstID, "Ship durable outbox").copy(isAwaitingReply = true).updateWorkingRun(oldSignal)
        }
        secondStore.receive(Action.PromptAdmitted(sessionID = session.id, messageID = firstID, requestEpoch = 1)) { it.admitted(firstID) }
        secondStore.finish()

        assertEquals(1, relaunchedPosts.value.size)
        assertEquals("Resumed send reuses the exact client-made messageID", firstID, relaunchedPosts.value.first().first)
        assertEquals("Ship durable outbox", relaunchedPosts.value.first().second)
        eventuallyTrue { secondPersistence.loadOutbox(server.persistenceID, session.id).isEmpty() }
        assertTrue("Settled prompt is removed from persistence", secondPersistence.loadOutbox(server.persistenceID, session.id).isEmpty())
    }

    @Test fun promptThatAlreadyLandedBeforeTerminationReconcilesWithoutResending() = persistenceTest { h ->
        val persistence = live()
        val pending = PendingPrompt(
            messageID = firstID, text = "Already reached server", attachments = emptyList(), files = emptyList(),
            wasRunning = false, delivery = OpenCodeDelivery.Steer, requestEpoch = 1, model = null,
        )
        persistence.recordOutbox(server.persistenceID, session.id, null, session.directory, "ses_unused", null, pending, false, null)
        persistence.flush()

        val relaunchedPersistence = live()
        val sendPromptCalls = LockIsolated(0)
        val store = h.store(h.sessionState()) {
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = relaunchedPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, id -> listOf(OpenCodeTranscriptItem.UserText(id = id, text = "Already reached server")) },
                sendPrompt = { _, _, _, _, _, _, _ -> sendPromptCalls.increment() },
            )
            idleWaitNotUnderTest()
        }

        store.send(Action.Task) {
            val oldSignal = it.workingRunSignal
            it.sent(firstID, "Already reached server").copy(isAwaitingReply = true).updateWorkingRun(oldSignal)
        }
        store.receive(Action.PromptAdmitted(sessionID = session.id, messageID = firstID, requestEpoch = 1)) { it.admitted(firstID) }
        store.finish()

        assertEquals("Reconciliation sees the message on the server and never posts a duplicate", 0, sendPromptCalls.value)
        eventuallyTrue { relaunchedPersistence.loadOutbox(server.persistenceID, session.id).isEmpty() }
        assertTrue(relaunchedPersistence.loadOutbox(server.persistenceID, session.id).isEmpty())
    }

    @Test fun failedPromptAndBlockedLaneSurviveRelaunchAndRetryInOrderWithSameIDs() = persistenceTest { h ->
        val firstPersistence = live()
        val firstOutbox = newOutbox()
        val firstStore = h.store(h.sessionState()) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = firstPersistence
            this[PromptOutbox] = firstOutbox
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
                sendPrompt = { _, _, _, _, _, id, _ -> if (id == firstID) throw OpenCodeClientError(message = "Bad request", statusCode = 400) },
            )
            idleWaitNotUnderTest()
        }

        firstStore.send(Action.Task)
        firstStore.send(draft("First prompt fails")) { it.withDraft("First prompt fails") }
        firstStore.send(Action.SendTapped) {
            val oldSignal = it.workingRunSignal
            it.copy(runWorkGeneration = 1, isAwaitingReply = true, lastSentItemID = firstID, sendGeneration = 1)
                .withDraft("")
                .sent(firstID, "First prompt fails")
                .updateWorkingRun(oldSignal)
        }

        val expectedFailedPending = PendingPrompt(
            messageID = firstID, text = "First prompt fails", attachments = emptyList(), files = emptyList(),
            wasRunning = false, delivery = OpenCodeDelivery.Steer, requestEpoch = 1, model = null,
        )
        firstStore.receive(
            Action.PromptFailed(sessionID = session.id, error = OpenCodeClientError(message = "Bad request", statusCode = 400), pending = expectedFailedPending),
        ) {
            val oldSignal = it.workingRunSignal
            it.copy(
                promptStates = it.promptStates - firstID,
                isAwaitingReply = false,
                failedSends = listOf(FailedSend(pending = expectedFailedPending, reason = "Bad request")),
            ).updateWorkingRun(oldSignal)
        }
        firstStore.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = existing))

        // Queue a second prompt behind the held failed prompt, then terminate in background.
        firstStore.send(draft("Second prompt waits behind first")) { it.withDraft("Second prompt waits behind first") }
        firstStore.send(Action.SendTapped) {
            it.copy(runWorkGeneration = 2, lastSentItemID = secondID, sendGeneration = 2)
                .withDraft("")
                .sent(secondID, "Second prompt waits behind first", epoch = 2)
        }
        firstStore.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true) }

        val stateFile = File(tempDir, "composer-state.json")
        eventuallyTrue { stateFile.exists() }
        val frozenSnapshot = stateFile.readBytes()

        // Withdraw the queued second prompt so firstStore finishes all effects cleanly, then restore the frozen snapshot.
        firstOutbox.withdraw(secondID)
        firstStore.finish()
        stateFile.writeBytes(frozenSnapshot)

        // Relaunch with fresh dependencies reading from disk.
        val secondPersistence = live()
        val relaunchedSent = LockIsolated(listOf<String>())
        val secondStore = h.store(h.sessionState()) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = secondPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
                sendPrompt = { _, _, _, _, _, id, _ ->
                    if (id == secondID) repeat(5) { yield() }
                    relaunchedSent.append(id)
                },
            )
            idleWaitNotUnderTest()
        }

        secondStore.send(Action.Task) {
            it.copy(
                optimisticPromptIDs = setOf(firstID, secondID),
                items = it.items + OpenCodeTranscriptItem.UserText(id = firstID, text = "First prompt fails") +
                    OpenCodeTranscriptItem.UserText(id = secondID, text = "Second prompt waits behind first"),
                failedSends = listOf(FailedSend(pending = expectedFailedPending, reason = "Bad request")),
                nextPromptRequestEpoch = 1,
                promptStates = it.promptStates + (secondID to SessionFeature.State.PromptState(requestEpoch = 1, admission = SessionFeature.State.PromptState.Admission.sent)),
            )
        }

        assertTrue("Blocked second prompt waits until the failed first prompt is retried", relaunchedSent.value.isEmpty())
        assertEquals(mapOf(secondID to DeliveryNote.waiting), secondStore.state.deliveryNotes)

        // Tap Retry on the first prompt: both deliver in order under their original client IDs.
        secondStore.send(Action.FailedSendRetryTapped(firstID)) {
            val oldSignal = it.workingRunSignal
            it.copy(
                runWorkGeneration = 1,
                failedSends = emptyList(),
                isAwaitingReply = true,
                nextPromptRequestEpoch = 2,
                promptStates = it.promptStates + (firstID to SessionFeature.State.PromptState(requestEpoch = 2, admission = SessionFeature.State.PromptState.Admission.sent)),
            ).updateWorkingRun(oldSignal)
        }

        secondStore.receive(Action.PromptAdmitted(sessionID = session.id, messageID = firstID, requestEpoch = 2)) { it.admitted(firstID) }
        secondStore.receive(Action.PromptAdmitted(sessionID = session.id, messageID = secondID, requestEpoch = 1)) { it.admitted(secondID) }
        secondStore.finish()

        assertEquals(listOf(firstID, secondID), relaunchedSent.value)
        eventuallyTrue { secondPersistence.loadOutbox(server.persistenceID, session.id).isEmpty() }
        assertTrue(secondPersistence.loadOutbox(server.persistenceID, session.id).isEmpty())
        assertTrue(secondPersistence.loadUndelivered(server.persistenceID, session.id).isEmpty())
    }

    @Test fun newSessionDraftSendPreservesDirectoryRestoresInProcessLaneAndReconcilesAfterCreateSession() = persistenceTest { h ->
        val secondProject = OpenCodeProject(id = "proj_2", name = "Second", directory = "/Users/kit/code/second")
        val createdSession = OpenCodeSession(
            id = "ses_new_2", serverTitle = "New Session", projectID = secondProject.id,
            directory = secondProject.directory, updatedAt = Instant.ofEpochSecond(2_000),
        )
        val firstGate = Gate()
        val persistence = live()
        val sharedOutbox = newOutbox()
        val sharedUndelivered = UndeliveredPrompts()

        val draftState = h.draftState(secondProject, listOf(project, secondProject)).copy(models = listOf(claudeModel)).withDraft("Start in second project")
        val firstStore = h.store(draftState) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = persistence
            this[PromptOutbox] = sharedOutbox
            this[UndeliveredPrompts] = sharedUndelivered
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, directory, _ ->
                    firstGate.wait()
                    OpenCodeSession(
                        id = createdSession.id, serverTitle = createdSession.title, projectID = secondProject.id,
                        directory = directory, updatedAt = createdSession.updatedAt,
                    )
                },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> throw OpenCodeClientError(message = "Bad gateway", statusCode = 400) },
                familyPendingRequests = { _, _, _ -> awaitCancellation() },
                pendingInputs = { _, _ -> emptyList() },
            )
        }

        firstStore.send(Action.Task)
        firstStore.receive(Action.AgentsRequested)
        firstStore.receive({ it is Action.AgentsResponse }, "agentsResponse")
        firstStore.send(Action.SendTapped) {
            val oldSignal = it.workingRunSignal
            it.copy(runWorkGeneration = 1, outboxDraftID = firstID, isAwaitingReply = true, lastSentItemID = firstID, sendGeneration = 1)
                .withDraft("")
                .sent(firstID, "Start in second project")
                .updateWorkingRun(oldSignal)
        }

        val probeValues = h.values.update {
            this[ComposerPersistence] = persistence
            this[PromptOutbox] = sharedOutbox
            this[UndeliveredPrompts] = sharedUndelivered
        }
        // A new draft starts blank while the send is still in flight.
        val freshState = h.draftState(project, listOf(project, secondProject)).copy(models = listOf(claudeModel))
        val freshRestored = DependencyValues.withValues(probeValues) { SessionFeature().restorePersistedComposer(freshState) }
        assertNull("A new draft doesn't adopt another draft's send", freshRestored.outboxDraftID)
        assertEquals(project, freshRestored.draftProject)
        assertTrue(freshRestored.items.isEmpty())

        // Reopening the unsent draft (`alreadyInOutbox == true`) lets the persisted entry's directory
        // win over `projects.first`.
        var probeState = freshState.copy(outboxDraftID = firstID)
        assertEquals(project, probeState.draftProject)
        probeState = DependencyValues.withValues(probeValues) { SessionFeature().restorePersistedComposer(probeState) }
        assertEquals(firstID, probeState.outboxDraftID)
        assertEquals("Persisted entry's directory wins over projects.first", secondProject, probeState.draftProject)

        firstStore.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true) }

        // Freeze the pre-createSession on-disk snapshot so we can test relaunch reconciliation when `created != nil`.
        val stateFile = File(tempDir, "composer-state.json")
        eventuallyTrue { stateFile.exists() }
        val frozenPreCreate = stateFile.readBytes()

        // Let firstStore finish its attempt (which fails after createSession) cleanly.
        firstStore.exhaustive = false
        firstGate.open()
        val expectedPending = PendingPrompt(
            messageID = firstID, text = "Start in second project", attachments = emptyList(), files = emptyList(),
            wasRunning = false, delivery = OpenCodeDelivery.Steer, requestEpoch = 1, model = null,
        )
        firstStore.receive(Action.SessionCreated(createdSession)) {
            it.copy(
                draftBecameSessionID = createdSession.id,
                session = createdSession,
                familySessions = listOf(createdSession),
                familyRootID = createdSession.id,
                pendingModel = null,
                // Backgrounded: it reads its pending work once; the return polls.
            ).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        firstStore.receive(
            Action.PromptFailed(sessionID = createdSession.id, error = OpenCodeClientError(message = "Bad gateway", statusCode = 400), pending = expectedPending),
        ) {
            val oldSignal = it.workingRunSignal
            it.copy(
                promptStates = it.promptStates - firstID,
                isAwaitingReply = false,
                failedSends = listOf(FailedSend(pending = expectedPending, reason = "Bad gateway")),
            ).updateWorkingRun(oldSignal)
        }
        firstStore.receive(Action.AcceptedTranscriptUpdated(sessionID = createdSession.id, items = emptyList()))
        h.skipInFlightEffects()

        // Restore the pre-createSession snapshot where `sessionID == nil` and `createdSession == nil`,
        // and simulate that the server already received both `createSession` and `sendPrompt(firstID)` before termination.
        stateFile.writeBytes(frozenPreCreate)
        val relaunchedPersistence = live()
        val createdDirectories = LockIsolated(listOf<String>())
        val sendPromptCalls = LockIsolated(0)
        val wrongSessions = LockIsolated(listOf<String>())

        freshHarness { h2 ->
            val relaunchedDraftState = h2.draftState(project, listOf(project, secondProject)).copy(models = listOf(claudeModel), outboxDraftID = firstID)
            val relaunchedStore = h2.store(relaunchedDraftState) {
                this[UuidClient] = UuidClient.incrementing()
                this[Clock] = ImmediateClock()
                this[ComposerPersistence] = relaunchedPersistence
                this[PromptOutbox] = newOutbox()
                this[UndeliveredPrompts] = UndeliveredPrompts()
                this[DictationClient] = DictationClient(prepare = {})
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    createSession = { _, directory, _ ->
                        createdDirectories.append(directory)
                        createdSession
                    },
                    message = { _, sessionID, id ->
                        if (sessionID != createdSession.id) wrongSessions.append(sessionID)
                        listOf(OpenCodeTranscriptItem.UserText(id = id, text = "Start in second project"))
                    },
                    sendPrompt = { _, _, _, _, _, _, _ -> sendPromptCalls.increment() },
                    familyPendingRequests = { _, _, _ -> awaitCancellation() },
                    pendingInputs = { _, _ -> awaitCancellation() },
                )
                idleWaitNotUnderTest()
            }

            relaunchedStore.send(Action.Task) {
                val oldSignal = it.workingRunSignal
                it.copy(draftProject = secondProject, isAwaitingReply = true)
                    .sent(firstID, "Start in second project")
                    .updateWorkingRun(oldSignal)
            }
            relaunchedStore.receive(Action.AgentsRequested)

            relaunchedStore.receive(Action.SessionCreated(createdSession)) {
                it.copy(
                    draftBecameSessionID = createdSession.id,
                    initialTranscriptIsFresh = true,
                    outboxDraftID = null,
                    session = createdSession,
                    familySessions = listOf(createdSession),
                    familyRootID = createdSession.id,
                    pendingModel = null,
                    pendingWorkPollSeconds = 2,
                ).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
            }
            relaunchedStore.receive(Action.PromptAdmitted(sessionID = createdSession.id, messageID = firstID, requestEpoch = 1)) { it.admitted(firstID) }
            relaunchedStore.receive({ it is Action.AgentsResponse }, "agentsResponse")
            relaunchedStore.receive(Action.PendingInputsRequested(sessionID = createdSession.id))
            relaunchedStore.send(Action.RequestsConnectionChanged(false)) {
                it.withRequestsConnectionIsAvailable(false)
                    .withRequestLoadAttempt(null)
                    .withRequestFailureAwaitingReconnect(true)
                    .copy(pendingWorkPollSeconds = null)
            }
            relaunchedStore.finish()
        }

        assertEquals("Persisted directory wins over projects.first", listOf(secondProject.directory), createdDirectories.value)
        assertEquals("reconcileFirst checks serverHas even when created != nil and skips duplicate sendPrompt", 0, sendPromptCalls.value)
        assertTrue(wrongSessions.value.isEmpty())
    }

    @Test fun newSessionDraftKilledBetweenCreateSessionAndSettlementResumesCreatedSessionOnRelaunch() = persistenceTest { h ->
        val secondProject = OpenCodeProject(id = "proj_2", name = "Second", directory = "/Users/kit/code/second")
        val createdSession = OpenCodeSession(
            id = "ses_created_after_post", serverTitle = "New Session", projectID = secondProject.id,
            directory = secondProject.directory, updatedAt = Instant.ofEpochSecond(2_500),
        )
        val sendGate = Gate()
        val firstPersistence = live()

        val initialDraftState = h.draftState(secondProject, listOf(project, secondProject)).copy(models = listOf(claudeModel)).withDraft("Created before crash")
        val firstStore = h.store(initialDraftState) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = firstPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ -> createdSession },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                sendPrompt = { _, _, _, _, _, _, _ -> sendGate.wait() },
                familyPendingRequests = { _, _, _ -> awaitCancellation() },
                pendingInputs = { _, _ -> awaitCancellation() },
            )
            idleWaitNotUnderTest()
        }

        firstStore.send(Action.Task)
        firstStore.receive(Action.AgentsRequested)
        firstStore.receive({ it is Action.AgentsResponse }, "agentsResponse")
        firstStore.send(Action.SendTapped) {
            val oldSignal = it.workingRunSignal
            it.copy(runWorkGeneration = 1, outboxDraftID = firstID, isAwaitingReply = true, lastSentItemID = firstID, sendGeneration = 1)
                .withDraft("")
                .sent(firstID, "Created before crash")
                .updateWorkingRun(oldSignal)
        }

        // Wait until createSession has recorded createdSession into persistence while sendPrompt is blocked.
        settle()
        firstStore.send(Action.SceneEnteredBackground) { it.copy(isBackgrounded = true) }

        val stateFile = File(tempDir, "composer-state.json")
        eventuallyTrue { stateFile.exists() }
        val frozenMidSend = stateFile.readBytes()

        // Let firstStore finish cleanly, then restore the mid-send snapshot where `createdSession` was persisted.
        sendGate.open()
        firstStore.receive(Action.SessionCreated(createdSession)) {
            it.copy(
                draftBecameSessionID = createdSession.id,
                initialTranscriptIsFresh = true,
                outboxDraftID = null,
                session = createdSession,
                familySessions = listOf(createdSession),
                familyRootID = createdSession.id,
                pendingModel = null,
                // Backgrounded: it reads its pending work once; the return polls.
            ).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        firstStore.receive(Action.PromptAdmitted(sessionID = createdSession.id, messageID = firstID, requestEpoch = 1)) { it.admitted(firstID) }
        firstStore.receive(Action.PendingInputsRequested(sessionID = createdSession.id))
        firstStore.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false)
                .withRequestLoadAttempt(null)
                .withRequestFailureAwaitingReconnect(true)
                .copy(pendingWorkPollSeconds = null)
        }
        firstStore.finish()
        stateFile.writeBytes(frozenMidSend)

        // Relaunch into that draft: it must adopt `createdSession` and send to `createdSession.id`
        // without calling `createSession` again.
        val secondPersistence = live()
        val createSessionCalls = LockIsolated(0)
        val postedSessions = LockIsolated(listOf<String>())

        val relaunchedState = h.draftState(project, listOf(project, secondProject))
            .copy(models = listOf(claudeModel), initialTranscriptRequestIsExternal = true, outboxDraftID = firstID)
        val secondStore = h.store(relaunchedState) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[ComposerPersistence] = secondPersistence
            this[PromptOutbox] = newOutbox()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    createSessionCalls.increment()
                    createdSession
                },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
                sendPrompt = { _, sessionID, _, _, _, _, _ -> postedSessions.append(sessionID) },
            )
            idleWaitNotUnderTest()
        }

        secondStore.send(Action.Task) {
            val oldSignal = it.workingRunSignal
            it.copy(
                draftBecameSessionID = createdSession.id,
                session = createdSession,
                familySessions = listOf(createdSession),
                familyRootID = createdSession.id,
                outboxDraftID = null,
                pendingModel = null,
                isAwaitingReply = true,
            ).sent(firstID, "Created before crash").updateWorkingRun(oldSignal)
        }

        secondStore.receive(Action.PromptAdmitted(sessionID = createdSession.id, messageID = firstID, requestEpoch = 1)) { it.admitted(firstID) }
        secondStore.finish()

        assertEquals("Resumed send uses the already-created session without calling createSession again", 0, createSessionCalls.value)
        assertEquals(listOf(createdSession.id), postedSessions.value)
    }

    /** Waits (real time, bounded) for a write on the persistence writer's own thread. */
    private suspend fun eventuallyTrue(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            kotlinx.coroutines.delay(10)
        }
    }

    /** A later phase of a test on a fresh [Harness] after the first's effects were skipped. */
    private suspend fun freshHarness(block: suspend (Harness) -> Unit) {
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
