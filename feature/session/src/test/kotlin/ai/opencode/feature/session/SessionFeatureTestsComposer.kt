package ai.opencode.feature.session

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeUploadEvent
import android.content.ContextWrapper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Port of iOS `SessionFeatureTests+Composer.swift`.
 *
 * Not ported (UIKit-only): `composerTextViewReturnKeyTypeHardwareShortcutsAndPaste` probes
 * `ImagePasteTextView`'s `returnKeyType`, `UIKeyCommand`s and `UITextView` paste handling; the
 * Compose composer's key handling has no such view to host in a JVM test.
 *
 * Not ported: the line-growth tests (`aTypedLineRidesTheLineSpringHeldToTheFieldsBottom`,
 * `lineGrowthKeepsAnEarlierCaretLineInView`, `aComposingLineLandsAtOnceAndASendsClearCommitsTheComposition`):
 * the field takes its new height at once and the composer card's `animateContentSize` animates it.
 */
class SessionFeatureTestsComposer {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val session = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "proj_1", directory = "/tmp/demo", updatedAt = Instant.EPOCH)
    private val sentID = FailedSendsSupport.sentID

    private fun Harness.sessionState(): State = state { State.session(server = server, session = session, cachedItems = emptyList()) }

    private fun DependencyBuilder.sendClient() {
        this[UuidClient] = UuidClient.incrementing()
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            sendPrompt = { _, _, _, _, _, _, _ -> },
            waitForIdle = { _, _ -> },
            getSession = { _, _ -> session },
            transcript = { _, _, _ -> emptyList() },
            familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
            pendingInputs = { _, _ -> emptyList() },
        )
    }

    private fun State.sent(text: String): State = copy(
        optimisticPromptIDs = setOf(sentID),
        items = listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = text)),
    ).withDraft("")

    // Draw on Image and the session that opened the viewer (iOS `ImageDrawingFeatureTests`).

    private val drawnImage = OpenCodeOutgoingAttachment(id = UUID(0, 2).toString(), data = byteArrayOf(0xFF.toByte(), 0xD8.toByte()), mime = "image/jpeg")

    private fun Harness.drawingSession(answering: Boolean = false): State {
        val state = sessionState()
            .withDraft("Check this detail")
            .withPendingAttachments(listOf(OpenCodeOutgoingAttachment(id = UUID(0, 1).toString(), data = byteArrayOf(1), mime = "image/png")))
        return if (answering) state { state.beginAnswering() } else state
    }

    @Test fun aDrawingJoinsTheDraftThatOpenedItAndNothingElse() = sessionTest { h ->
        val state = h.drawingSession()
        val store = h.store(state)
        store.send(Action.DrawingAdded(drawnImage, from = state.taskIdentity)) {
            it.withPendingAttachments(it.pendingAttachments + drawnImage)
        }
        store.finish()
    }

    @Test fun aDrawingOnAPendingPhotoTakesItsPlace() = sessionTest { h ->
        val state = h.drawingSession()
        val photo = state.pendingAttachments.first()
        val pasted = OpenCodeOutgoingAttachment(id = UUID(0, 3).toString(), data = byteArrayOf(2), mime = "image/png")
        val store = h.store(state.withPendingAttachments(listOf(photo, pasted)))
        store.send(Action.DrawingAdded(drawnImage, from = state.taskIdentity, replacing = photo.id)) {
            it.withPendingAttachments(listOf(drawnImage, pasted))
        }
        store.finish()
    }

    @Test fun aDrawingForAnotherComposerAddsNothing() = sessionTest { h ->
        val state = h.drawingSession()
        val elsewhere = state.taskIdentity.copy(visit = state.taskIdentity.visit + 1)
        val store = h.store(state)
        store.send(Action.DrawingAdded(drawnImage, from = elsewhere))
    }

    @Test fun aDrawingWhileTheComposerAnswersARequestAddsNothing() = sessionTest { h ->
        val state = h.drawingSession(answering = true)
        assertFalse(state.takesPasteAttachments)
        val store = h.store(state)
        store.send(Action.DrawingAdded(drawnImage, from = state.taskIdentity))
    }

    private val clip = ComposerStagedFile(
        id = UUID(0, 9),
        name = "clip.mov",
        mime = "video/quicktime",
        size = 100,
        url = File("/nonexistent/ComposerFiles/${UUID(0, 9)}/clip.mov"),
    )

    /** A finished upload's cleanup reads the staging folder under the app's cache. */
    private val appContext = object : ContextWrapper(null) {
        override fun getCacheDir(): File = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }

    private fun Harness.sending(state: State, upload: Channel<OpenCodeUploadEvent>, sent: LockIsolated<List<List<OpenCodeOutgoingAttachment>>>) =
        store(state) {
            this[AppContext] = appContext
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                uploadFile = { _, _, _, _ -> upload.receiveAsFlow() },
                sendPrompt = { _, _, _, attachments, _, _, _ -> sent.append(attachments) },
                waitForIdle = { _, _ -> },
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
            )
        }

    private val uploadFinished: (Action) -> Boolean =
        { (it as? Action.ComposerAttachments)?.action is ComposerAttachmentsFeature.Action.FileUploadFinished }

    /** Add lands while Send waits on an upload: the image goes with that message, like a paste. */
    @Test fun aDrawingWhileASendWaitsOnUploadsIsAddedLikeAPaste() = sessionTest { h ->
        val state = h.drawingSession()
        val photo = state.pendingAttachments
        val upload = Channel<OpenCodeUploadEvent>(Channel.UNLIMITED)
        val sent = LockIsolated(listOf<List<OpenCodeOutgoingAttachment>>())
        val store = h.sending(state, upload, sent)
        store.exhaustive = false
        store.send(Action.filesStaged(listOf(clip)))
        store.send(Action.SendTapped) { it.withSendAfterUploads(OpenCodeDelivery.Steer) }
        store.skipReceivedActions()

        store.exhaustive = true
        store.send(Action.DrawingAdded(drawnImage, from = state.taskIdentity)) {
            it.withPendingAttachments(it.pendingAttachments + drawnImage)
        }

        store.exhaustive = false
        upload.send(OpenCodeUploadEvent.Finished(path = "/tmp/opencode/uploads/u/clip.mov"))
        upload.close()
        store.receive(uploadFinished, "fileUploadFinished")
        store.receive(Action.DeferredSendReady::class)
        store.receive(Action.PromptAdmitted::class)
        store.finish()
        assertEquals(listOf(photo + drawnImage), sent.value)
    }

    @Test fun returnKeySubmitsInsteadOfInsertingNewline() = sessionTest { h ->
        h.state { shared(SharedKeys.returnKeySends) }.set(true)
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Ship it\n")
        val store = h.store(state) { sendClient() }
        store.exhaustive = false

        store.send(Action.ReturnKeyTapped) { it.withDraft("Ship it") }
        store.receive(Action.SendTapped) { it.sent("Ship it") }
    }

    @Test fun returnKeyInsertsNewlineWhenReturnKeySendsIsOffAndSendButtonSubmits() = sessionTest { h ->
        assertFalse("Return inserts a newline without opting into a setting", h.state { shared(SharedKeys.returnKeySends) }.value)
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Line one\n")
        val store = h.store(state) { sendClient() }
        store.exhaustive = false

        // With Return Key Sends off, Return leaves the newline in the draft.
        store.send(Action.ReturnKeyTapped)
        assertEquals("Line one\n", store.state.draft)
        assertTrue(store.state.items.isEmpty())

        store.send(Action.Binding(BindingAction.Draft("Line one\nLine two"))) { it.withDraft("Line one\nLine two") }
        store.send(Action.SendTapped) { it.sent("Line one\nLine two") }
    }

    @Test fun commandReturnAlwaysSubmitsRegardlessOfReturnKeySendsSetting() {
        for (sendsOnReturn in listOf(true, false)) commandReturnAlwaysSubmitsRegardlessOfReturnKeySendsSetting(sendsOnReturn)
    }

    private fun commandReturnAlwaysSubmitsRegardlessOfReturnKeySendsSetting(sendsOnReturn: Boolean) = sessionTest { h ->
        h.state { shared(SharedKeys.returnKeySends) }.set(sendsOnReturn)
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("First line\nSecond line")
        val store = h.store(state) { sendClient() }
        store.exhaustive = false

        store.send(Action.CommandReturnTapped)
        store.receive(Action.SendTapped) { it.sent("First line\nSecond line") }

        // Empty draft: ⌘↩ is a no-op.
        store.send(Action.CommandReturnTapped)
        assertEquals("sendsOnReturn=$sendsOnReturn", 1, store.state.items.size)
    }

    @Test fun commandReturnDoesNothingDuringDictationOrPendingUploads() = sessionTest { h ->
        h.state { shared(SharedKeys.returnKeySends) }.set(true)
        val state = h.sessionState().withDraft("Existing draft")
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "Dictated words", isLocked = false))
        val store = h.store(state)

        // ⌘↩ while hold-to-dictate is active does nothing and leaves recording intact.
        store.send(Action.CommandReturnTapped)
        assertEquals(DictationFeature.Phase.Recording, store.state.dictationPhase)
        assertEquals("Dictated words", store.state.dictationHold?.text)
        assertTrue(store.state.items.isEmpty())

        // ⇧↩ during dictation is ignored so it never swallows the next Return after dictation ends.
        store.send(Action.NonSendNewlineInserted("Existing draft\n"))
        assertNull(store.state.nonSendNewlineDraft)

        val afterDictationState = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Existing draft")
            .withDictationPhase(DictationFeature.Phase.Recording)
            .withDictationHold(DictationFeature.Hold(text = "Dictated words", isLocked = false))
        val afterDictationStore = h.store(afterDictationState) { sendClient() }
        afterDictationStore.exhaustive = false
        afterDictationStore.send(Action.NonSendNewlineInserted("Existing draft\n"))
        afterDictationStore.send(Action.micPressCancelled)
        afterDictationStore.send(Action.Binding(BindingAction.Draft("Existing draft\n")))
        afterDictationStore.send(Action.ReturnKeyTapped)
        afterDictationStore.receive(Action.SendTapped)
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Existing draft")), afterDictationStore.state.items)

        // ⌘↩ while waiting for uploads or with a failed attachment does nothing.
        val stagedFile = ComposerStagedFile(
            id = UUID(0, 1),
            name = "clip.mov",
            mime = "video/quicktime",
            size = 100,
            url = File("/nonexistent/clip.mov"),
        )
        val uploadState = h.sessionState().withDraft("Ready text")
            .withPendingFiles(listOf(ComposerFileAttachment(stagedFile)))
            .withSendAfterUploads(OpenCodeDelivery.Steer)
        val uploadStore = h.store(uploadState)
        uploadStore.send(Action.CommandReturnTapped)
        assertTrue(uploadStore.state.items.isEmpty())

        val failedAttachment = ComposerFileAttachment(stagedFile).copy(status = ComposerFileAttachment.Status.Failed("Couldn’t upload"))
        val failedFileState = h.sessionState().withDraft("Ready text").withPendingFiles(listOf(failedAttachment))
        val failedFileStore = h.store(failedFileState)
        failedFileStore.send(Action.CommandReturnTapped)
        assertTrue(failedFileStore.state.items.isEmpty())
    }

    @Test fun dictationStreamsIntoDraftAfterTypedText() = sessionTest { h ->
        val state = h.sessionState().withDraft("Fix")
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(state) {
            this[DictationClient] = DictationClient(
                start = { feed },
                // Stopping finalizes: the engine delivers its last transcript, then
                // the stream ends — mirroring finalizeAndFinishThroughEndOfInput.
                stop = {
                    feed.trySend("the spinner ring!")
                    feed.close()
                },
            )
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        feed.trySend("the spinner")
        store.receive(Action.dictationUpdated("the spinner")) { it.withDraft("Fix the spinner") }
        feed.trySend("the spinner ring")
        store.receive(Action.dictationUpdated("the spinner ring")) { it.withDraft("Fix the spinner ring") }
        // An intentional stop keeps the stream alive: the finalized transcript
        // (often the only accurate one) still lands in the draft.
        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = null)) }
        store.receive(Action.dictationUpdated("the spinner ring!")) { it.withDraft("Fix the spinner ring!") }
        store.receive(Action.dictationStopped(errorMessage = null)) { it.withDictationPhase(DictationFeature.Phase.Idle) }
        // Late results after teardown must not clobber the draft.
        store.send(Action.dictationUpdated("ghost"))
        assertEquals("Fix the spinner ring!", store.state.draft)
    }

    @Test fun micRetapWhileFinishingIsIgnored() = sessionTest { h ->
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(h.sessionState()) {
            this[DictationClient] = DictationClient(
                start = { feed },
                // Engine finalizes slowly; the final transcript arrives later.
                stop = {},
            )
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.receive(Action.dictationReady) { it.withDictationPhase(DictationFeature.Phase.Recording) }
        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Finishing(send = null)) }
        // A double-tap on stop must not restart dictation mid-teardown.
        store.send(Action.micTapped)
        assertFalse(store.state.isDictating)

        feed.trySend("final words")
        store.receive(Action.dictationUpdated("final words")) { it.withDraft("final words") }
        feed.close()
        store.receive(Action.dictationStopped(errorMessage = null)) { it.withDictationPhase(DictationFeature.Phase.Idle) }
    }

    /**
     * Send Report? waits out dictation and a call on its bar, then returns:
     * hiding it never touches the pending report.
     */
    @Test fun sendingWhileDictatingStopsTheMic() = sessionTest { h ->
        val state = h.sessionState().copy(hasRenderedFirstContentFrame = true).withDraft("Deploy")
        val feed = Channel<String>(Channel.UNLIMITED)
        val store = h.store(state) {
            sendClient()
            this[DictationClient] = DictationClient(start = { feed }, stop = { feed.close() })
        }
        store.exhaustive = false

        store.send(Action.micTapped) { it.withDictationPhase(DictationFeature.Phase.Preparing) }
        store.send(Action.SendTapped) {
            it.copy(optimisticPromptIDs = setOf(sentID)).withDictationPhase(DictationFeature.Phase.Idle).withDraft("")
        }
        assertEquals(listOf(OpenCodeTranscriptItem.UserText(id = sentID, text = "Deploy")), store.state.items)
    }
}

private typealias DependencyBuilder = ai.opencode.core.dependencies.DependencyValues.Builder
