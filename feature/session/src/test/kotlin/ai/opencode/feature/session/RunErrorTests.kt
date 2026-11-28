package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.feature.composer.AutocompleteItem
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.feature.composer.ComposerAutocompleteChip
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.ComposerPersistenceStorage
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.LocationClient
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.composer.UndoSnapshot
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePromptSource
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptFile
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * A failed run's line: what it offers, Edit's undo through V2's revert, Redo, and how prompting
 * again leaves only the quiet record.
 */
class RunErrorTests {
    private val server = OpenCodeServer(url = "http://127.0.0.1:9", username = "opencode", password = "pw")
    private val filter = OpenCodeRunError(type = "provider.content-filter", message = "Provider blocked the response")
    private val chatSession = OpenCodeSession(id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

    private fun Harness.state(items: List<OpenCodeTranscriptItem>, draft: String = ""): SessionFeature.State = state {
        SessionFeature.State.session(server = server, session = chatSession, cachedItems = items).withDraft(draft)
    }

    private val failedTurn: List<OpenCodeTranscriptItem>
        get() = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Hi"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_hello-text-0", text = "Hello"),
            OpenCodeTranscriptItem.UserText(id = "msg_ask", text = "Tighten the drawer"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "On it"),
            OpenCodeTranscriptItem.RunError(id = "msg_reply-error", error = filter),
            OpenCodeTranscriptItem.RunError(id = "msg_idle", error = OpenCodeRunError(type = filter.type, message = filter.message, beforeReply = true)),
        )

    private val pngData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)

    private fun SessionFeature.State.withSessionRevert(messageID: String?): SessionFeature.State =
        copy(session = session?.copy(revertMessageID = messageID))

    private val acceptedTranscriptUpdated = Action.AcceptedTranscriptUpdated::class

    @Test fun theNewestErrorOffersItsActionUntilThereIsSomethingNewer() = sessionTest { h ->
        var state = h.state(failedTurn)
        assertEquals("msg_reply-error", state.actionableRunErrorID)
        assertEquals(
            listOf(OpenCodeRunError.Action.Edit, OpenCodeRunError.Action.TryAgain, OpenCodeRunError.Action.SwitchModel),
            state.actionableRunErrorActions,
        )
        assertEquals("msg_ask", state.editableLastMessage?.id)

        state = state.copy(items = state.items + OpenCodeTranscriptItem.UserText(id = "msg_next", text = "Never mind"))
        assertNull("Prompting again leaves only the record", state.actionableRunErrorID)
        state = state.copy(items = state.items.dropLast(1))

        state = state.copy(isAwaitingReply = true)
        assertNull("Nothing to act on while a run goes", state.actionableRunErrorID)
        state = state.copy(isAwaitingReply = false)

        // A stop is no failure: nothing to do about it.
        val items = state.items.toMutableList()
        items[4] = OpenCodeTranscriptItem.RunError(id = "msg_reply-error", error = OpenCodeRunError(type = "aborted", message = "Aborted"))
        items.removeAt(items.lastIndex)
        state = state.copy(items = items)
        assertNull(state.actionableRunErrorID)
    }

    @Test fun editIsOfferedOnlyForTheReadersOwnWords() = sessionTest { h ->
        val withImageItems = failedTurn.toMutableList().apply { add(2, OpenCodeTranscriptItem.UserImages(id = "msg_ask-images", images = emptyList())) }
        val withImage = h.state(withImageItems)
        assertNull("An image couldn't come back with the words", withImage.editableLastMessage)
        assertEquals(listOf(OpenCodeRunError.Action.TryAgain, OpenCodeRunError.Action.SwitchModel), withImage.actionableRunErrorActions)

        val fromAgentItems = failedTurn.toMutableList().apply {
            set(2, OpenCodeTranscriptItem.UserText(id = "msg_ask", text = "Tighten the drawer", source = OpenCodePromptSource.Session(id = "ses_other")))
        }
        val fromAgent = h.state(fromAgentItems)
        assertNull(fromAgent.editableLastMessage)
    }

    @Test fun editUndoesTheMessageAndGivesItsWordsBack() = sessionTest { h ->
        val staged = LockIsolated(listOf<String>())
        val store = h.store(h.state(failedTurn, draft = "")) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, sessionID, messageID -> staged.append("$sessionID $messageID") })
        }
        store.exhaustive = false

        store.send(Action.RunErrorActionTapped(OpenCodeRunError.Action.Edit)) { it.copy(isUndoingLastMessage = true) }
        store.receive(Action.LastMessageUndone::class) { it.copy(isUndoingLastMessage = false).withSessionRevert("msg_ask") }
        assertEquals(listOf("ses_1 msg_ask"), staged.value)
        assertEquals("Tighten the drawer", store.state.draft)
        assertEquals("Tighten the drawer", store.state.undoSnapshot?.restoredDraft)
        // The message and what followed it give way to one row offering Redo.
        assertEquals(listOf("msg_earlier", "msg_hello-text-0", "undone-msg_ask"), store.state.visibleItems.map { it.id })
        assertNull(store.state.actionableRunErrorID)
    }

    @Test fun redoBringsBackWhatTheUndoHid() = sessionTest { h ->
        val cleared = LockIsolated(0)
        val initial = h.state(failedTurn, draft = "Tighten the drawer")
            .withSessionRevert("msg_ask")
            .withUndoSnapshot(UndoSnapshot(restoredDraft = "Tighten the drawer"))
        val store = h.store(initial) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(clearRevert = { _, _ -> cleared.increment() })
        }
        store.exhaustive = false

        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) { it.withSessionRevert(null).withUndoSnapshot(null) }
        assertEquals(1, cleared.value)
        assertTrue("The untouched words leave the composer with the turn back", store.state.draft.isEmpty())
        assertEquals(failedTurn.map { it.id }, store.state.visibleItems.map { it.id })
    }

    @Test fun aFailedUndoSaysSoAndChangesNothing() = sessionTest { h ->
        val store = h.store(h.state(failedTurn)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                stageRevert = { _, _, _ -> throw OpenCodeClientError(message = "Session is busy", statusCode = 409) },
            )
            this[UuidClient] = UuidClient.incrementing()
        }
        store.exhaustive = false
        store.send(Action.RunErrorActionTapped(OpenCodeRunError.Action.Edit))
        store.receive(Action.LastMessageUndone::class)
        assertNull(store.state.session?.revertMessageID)
        assertTrue(store.state.draft.isEmpty())
        assertEquals(
            OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t undo the message: Session is busy"),
            store.state.items.last(),
        )
    }

    @Test fun anotherClientsUndoAndItsCommitReachTheScreen() = sessionTest { h ->
        val store = h.store(h.state(failedTurn)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> awaitCancellation() },
                transcript = { _, _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(Action.RevertChanged(sessionID = "ses_1", messageID = "msg_ask", committed = false)) { it.withSessionRevert("msg_ask") }
        store.send(Action.RevertChanged(sessionID = "ses_1", messageID = null, committed = true)) {
            it.withSessionRevert(null).copy(items = failedTurn.take(2))
        }
        h.skipInFlightEffects()
    }

    @Test fun onlyActionsTheErrorOffersRun() = sessionTest { h ->
        val store = h.store(h.state(failedTurn))
        // Compact isn't one of a content filter's actions.
        store.send(Action.RunErrorActionTapped(OpenCodeRunError.Action.Compact))
        store.finish()
    }

    @Test fun revertToHereStagesRevertRestoresPromptAndFocusesComposer() = sessionTest { h ->
        val staged = LockIsolated(listOf<String>())
        val haptics = HapticsRecorder()
        val items = listOf(
            OpenCodeTranscriptItem.UserImages(id = "msg_earlier-images", images = listOf(OpenCodeImageAttachment(id = "img_1", data = pngData))),
            OpenCodeTranscriptItem.UserFiles(
                id = "msg_earlier-files",
                files = listOf(
                    OpenCodeTranscriptFile(id = "file_pasted", name = "Pasted text", mime = "text/plain", text = "line 1\nline 2"),
                    OpenCodeTranscriptFile(id = "file_pdf", name = "spec.pdf", mime = "application/pdf", path = "/project/spec.pdf"),
                ),
            ),
            OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Hi"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_hello-text-0", text = "Hello"),
            OpenCodeTranscriptItem.UserText(id = "msg_ask", text = "Tighten the drawer"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "On it"),
        )
        val store = h.store(h.state(items)) {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, sessionID, messageID -> staged.append("$sessionID $messageID") })
        }

        val expectedImageID = UUID(0, 0)
        val expectedPastedID = UUID(0, 1)
        val expectedFileID = UUID(0, 2)
        val expectedAttachments = listOf(
            OpenCodeOutgoingAttachment(id = expectedImageID.toString(), data = pngData, mime = "image/png"),
            PastedText.attachment("line 1\nline 2", id = expectedPastedID.toString()),
        )
        val expectedFiles = listOf(
            ComposerFileAttachment(OpenCodeFileReference(name = "spec.pdf", mime = "application/pdf", path = "/project/spec.pdf"), id = expectedFileID),
        )

        store.send(Action.RevertToHereTapped("msg_earlier")) { it.copy(isUndoingLastMessage = true) }
        store.receive(Action.MessageReverted::class) {
            it.copy(isUndoingLastMessage = false)
                .setRevertMessageID("msg_earlier")
                .withDraft("Hi")
                .withPendingAttachments(expectedAttachments)
                .withPendingFiles(expectedFiles)
                .withUndoSnapshot(
                    UndoSnapshot(
                        restoredDraft = "Hi",
                        priorDraft = "",
                        priorLocation = null,
                        attachmentIDs = setOf(expectedImageID, expectedPastedID),
                        fileIDs = setOf(expectedFileID),
                    ),
                )
                .copy(composerFocusRequest = 1)
        }
        store.receive(acceptedTranscriptUpdated)

        assertEquals(listOf("ses_1 msg_earlier"), staged.value)
        assertEquals(listOf("confirm"), haptics.log.map { it.name })
        assertEquals(listOf("undone-msg_earlier"), store.state.visibleItems.map { it.id })
        store.finish()
    }

    @Test fun undoAfterRevertToHereRestoresPriorDraftAndTranscript() = sessionTest { h ->
        val cleared = LockIsolated(0)
        val haptics = HapticsRecorder()
        val store = h.store(h.state(failedTurn, draft = "My earlier draft")) {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                stageRevert = { _, _, _ -> },
                clearRevert = { _, _ -> cleared.increment() },
            )
        }

        store.send(Action.RevertToHereTapped("msg_ask")) { it.copy(isUndoingLastMessage = true) }
        store.receive(Action.MessageReverted::class) {
            it.copy(isUndoingLastMessage = false)
                .setRevertMessageID("msg_ask")
                .withDraft("My earlier draft\n\nTighten the drawer")
                .withUndoSnapshot(UndoSnapshot(restoredDraft = "My earlier draft\n\nTighten the drawer", priorDraft = "My earlier draft"))
                .copy(composerFocusRequest = 1)
        }
        store.receive(acceptedTranscriptUpdated)

        // Reverting an earlier turn before touching the draft replaces the previous
        // revert's words while still preserving the original pre-revert draft.
        store.send(Action.RevertToHereTapped("msg_earlier")) { it.copy(isUndoingLastMessage = true) }
        store.receive(Action.MessageReverted::class) {
            it.copy(isUndoingLastMessage = false)
                .setRevertMessageID("msg_earlier")
                .withDraft("My earlier draft\n\nHi")
                .withUndoSnapshot(UndoSnapshot(restoredDraft = "My earlier draft\n\nHi", priorDraft = "My earlier draft"))
                .copy(composerFocusRequest = 2)
        }
        store.receive(acceptedTranscriptUpdated)

        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) {
            it.setRevertMessageID(null).withDraft("My earlier draft").withUndoSnapshot(null)
        }
        store.receive(acceptedTranscriptUpdated)

        assertEquals(1, cleared.value)
        assertEquals(listOf("confirm", "confirm", "tap"), haptics.log.map { it.name })
        assertEquals(failedTurn.map { it.id }, store.state.visibleItems.map { it.id })
        store.finish()
    }

    @Test fun undoLastMessageAlsoPreservesPriorDraftOnRedo() = sessionTest { h ->
        val store = h.store(h.state(failedTurn, draft = "My earlier draft")) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, _, _ -> }, clearRevert = { _, _ -> })
        }

        store.send(Action.RunErrorActionTapped(OpenCodeRunError.Action.Edit)) { it.copy(isUndoingLastMessage = true) }
        store.receive(Action.LastMessageUndone::class) {
            it.copy(isUndoingLastMessage = false)
                .setRevertMessageID("msg_ask")
                .withDraft("My earlier draft\n\nTighten the drawer")
                .withUndoSnapshot(UndoSnapshot(restoredDraft = "My earlier draft\n\nTighten the drawer", priorDraft = "My earlier draft"))
        }
        store.receive(acceptedTranscriptUpdated)

        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) {
            it.setRevertMessageID(null).withDraft("My earlier draft").withUndoSnapshot(null)
        }
        store.receive(acceptedTranscriptUpdated)
        store.finish()
    }

    @Test fun revertToHerePrefersRevertedLocationAndUndoRestoresPriorLocation() = sessionTest { h ->
        val priorSpot = SharedLocation(
            latitude = 37.7749, longitude = -122.4194, accuracy = 10.0,
            place = "Ferry Building", address = "1 Ferry Building, San Francisco, CA",
            capturedAt = Instant.ofEpochSecond(1_800_000_000),
        )
        val revertedSpot = SharedLocation(
            latitude = 37.3349, longitude = -122.00902, accuracy = 12.0,
            place = "Apple Park", address = "1 Apple Park Way, Cupertino, CA",
            capturedAt = Instant.ofEpochSecond(1_800_000_500),
        )
        val attachedAfterRevertSpot = SharedLocation(
            latitude = 37.8024, longitude = -122.4058, accuracy = 8.0,
            place = "Coit Tower", address = "1 Telegraph Hill Blvd, San Francisco, CA",
            capturedAt = Instant.ofEpochSecond(1_800_001_000),
        )
        val items = listOf(
            OpenCodeTranscriptItem.UserText(id = "msg_loc", text = revertedSpot.prompt("Find coffee nearby")),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "Found one"),
        )
        val initial = h.state(items, draft = "My earlier draft").withPendingLocation(priorSpot)

        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[LocationClient] = LocationClient(current = { attachedAfterRevertSpot })
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, _, _ -> }, clearRevert = { _, _ -> })
        }

        suspend fun revert(focus: Int, priorLocation: SharedLocation?) {
            store.send(Action.RevertToHereTapped("msg_loc")) { it.copy(isUndoingLastMessage = true) }
            store.receive(Action.MessageReverted::class) {
                it.copy(isUndoingLastMessage = false)
                    .setRevertMessageID("msg_loc")
                    .withDraft("My earlier draft\n\nFind coffee nearby")
                    .withPendingLocation(revertedSpot)
                    .withUndoSnapshot(
                        UndoSnapshot(
                            restoredDraft = "My earlier draft\n\nFind coffee nearby",
                            priorDraft = "My earlier draft",
                            restoredLocation = revertedSpot,
                            priorLocation = priorLocation,
                        ),
                    )
                    .copy(composerFocusRequest = focus)
            }
            store.receive(acceptedTranscriptUpdated)
        }

        revert(focus = 1, priorLocation = priorSpot)

        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) {
            it.setRevertMessageID(null).withDraft("My earlier draft").withPendingLocation(priorSpot).withUndoSnapshot(null)
        }
        store.receive(acceptedTranscriptUpdated)

        // When no location was staged before the revert, Undo clears the reverted
        // message's location so it doesn't stay stuck in the composer.
        store.send(Action.locationRemoved) { it.withPendingLocation(null) }
        revert(focus = 2, priorLocation = null)

        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) {
            it.setRevertMessageID(null).withDraft("My earlier draft").withPendingLocation(null).withUndoSnapshot(null)
        }
        store.receive(acceptedTranscriptUpdated)

        // Attaching a new location after reverting is preserved on Undo instead
        // of being clobbered by priorLocation.
        revert(focus = 3, priorLocation = null)

        store.send(Action.locationTapped) { it.withIsLocating(true) }
        store.receive({ it is Action.ComposerAttachments && it.action is ComposerAttachmentsFeature.Action.LocationResponse }, "locationResponse") {
            it.withIsLocating(false).withPendingLocation(attachedAfterRevertSpot)
        }
        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) {
            it.setRevertMessageID(null).withDraft("My earlier draft").withUndoSnapshot(null)
        }
        store.receive(acceptedTranscriptUpdated)
        assertEquals(attachedAfterRevertSpot, store.state.pendingLocation)
        store.finish()
    }

    @Test fun undoSnapshotClearsOnDraftEditQuoteAndCommitFromAnotherClient() = sessionTest { h ->
        // 1. dropUndone() clears undoSnapshot unconditionally even when revertMessageID is nil.
        val standalone = h.state(failedTurn, draft = "Tighten the drawer")
            .withUndoSnapshot(UndoSnapshot(restoredDraft = "Tighten the drawer", priorDraft = "Earlier"))
            .withSessionRevert(null)
            .dropUndone()
        assertNull(standalone.undoSnapshot)

        // 2. Editing the draft away from restoredDraft clears undoSnapshot immediately.
        val initial = h.state(failedTurn, draft = "Earlier").copy(streamIsHealthy = true)
        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                stageRevert = { _, _, _ -> },
                clearRevert = { _, _ -> },
                getSession = { _, _ -> chatSession },
                transcript = { _, _, _ -> awaitCancellation() },
            )
        }

        suspend fun revert(draft: String, prior: String, focus: Int) {
            store.send(Action.RevertToHereTapped("msg_ask")) { it.copy(isUndoingLastMessage = true) }
            store.receive(Action.MessageReverted::class) {
                it.copy(isUndoingLastMessage = false)
                    .setRevertMessageID("msg_ask")
                    .withDraft(draft)
                    .withUndoSnapshot(UndoSnapshot(restoredDraft = draft, priorDraft = prior))
                    .copy(composerFocusRequest = focus)
            }
            store.receive(acceptedTranscriptUpdated)
        }

        revert("Earlier\n\nTighten the drawer", "Earlier", 1)

        store.send(Action.Binding(BindingAction.Draft("Tighten the drawer and add tests"))) {
            it.withDraft("Tighten the drawer and add tests").withUndoSnapshot(null)
        }
        // Even if edited back to match the original restored text later, Undo leaves it alone.
        store.send(Action.Binding(BindingAction.Draft("Earlier\n\nTighten the drawer"))) { it.withDraft("Earlier\n\nTighten the drawer") }
        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) { it.setRevertMessageID(null) }
        store.receive(acceptedTranscriptUpdated)
        assertEquals("Earlier\n\nTighten the drawer", store.state.draft)

        // 3. Quoting text into the composer clears undoSnapshot.
        revert("Earlier\n\nTighten the drawer\n\nTighten the drawer", "Earlier\n\nTighten the drawer", 2)

        val quoteID = UUID(0, 0).toString()
        store.send(Action.QuoteTextTapped("Hello")) {
            it.withPendingQuotes(listOf(ComposerQuote(id = quoteID, text = "Hello")))
                .withUndoSnapshot(null)
                .copy(composerFocusRequest = 3)
        }
        store.send(Action.quoteRemoved(quoteID)) { it.withPendingQuotes(emptyList()) }
        store.send(Action.Binding(BindingAction.Draft("Earlier"))) { it.withDraft("Earlier") }
        store.send(Action.RedoTapped)
        store.receive(Action.Redone::class) { it.setRevertMessageID(null) }
        store.receive(acceptedTranscriptUpdated)

        // 4. External revert clear (`session.revert.cleared` or session snapshot with nil)
        // and external commit (`committed: true`) restore the untouched pre-revert draft.
        revert("Earlier\n\nTighten the drawer", "Earlier", 4)

        val clearedSnapshot = OpenCodeSession(
            id = "ses_1", serverTitle = "Chat", projectID = "p", directory = "/project",
            updatedAt = store.state.session!!.updatedAt, revertMessageID = null,
        )
        store.send(Action.SessionSnapshotUpdated(clearedSnapshot)) { it.updateObservedSession(clearedSnapshot) }

        revert("Earlier\n\nTighten the drawer", "Earlier", 5)

        // Another client commits the revert by sending a message: untouched reverted
        // words leave this composer before `dropUndone()` trims the transcript.
        store.send(Action.RevertChanged(sessionID = "ses_1", messageID = null, committed = true)) {
            it.setRevertMessageID(null).withDraft("Earlier").withUndoSnapshot(null).copy(items = failedTurn.take(2))
        }
        store.receive(Action.RefreshRequested::class) {
            it.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Invalidation))
        }
        h.skipInFlightEffects()
    }

    @Test fun revertToHereBlocksUnrestorableInlineBinaryAttachment() = sessionTest { h ->
        val staged = LockIsolated(listOf<String>())
        val haptics = HapticsRecorder()
        val inlinePDF = OpenCodeTranscriptFile(id = "msg_pdf-file-0", name = "inline.pdf", mime = "application/pdf")
        val items = listOf(
            OpenCodeTranscriptItem.UserFiles(id = "msg_pdf-files", files = listOf(inlinePDF)),
            OpenCodeTranscriptItem.UserText(id = "msg_pdf", text = "Summarize this PDF"),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "Done"),
        )
        val initial = h.state(items)
        assertNull(initial.restoredPrompt("msg_pdf", UuidClient.incrementing()))
        val entry = TranscriptEntry.build(items).first()
        assertEquals(false, TranscriptUserMessage.from(entry)?.canRestoreToComposer)

        val store = h.store(initial) {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, sessionID, messageID -> staged.append("$sessionID $messageID") })
        }

        store.send(Action.RevertToHereTapped("msg_pdf"))
        assertTrue(staged.value.isEmpty())
        assertTrue(haptics.log.isEmpty())
        store.finish()
    }

    @Test fun failedRevertPlaysErrorReadsCouldntRevertAndLeavesTranscriptWhole() = sessionTest { h ->
        val haptics = HapticsRecorder()
        val store = h.store(h.state(failedTurn, draft = "Untouched")) {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                stageRevert = { _, _, _ -> throw OpenCodeClientError(message = "Session is busy", statusCode = 409) },
            )
        }

        store.send(Action.RevertToHereTapped("msg_earlier")) { it.copy(isUndoingLastMessage = true) }
        // While in flight, the transcript is never half-removed.
        assertEquals(failedTurn.map { it.id }, store.state.visibleItems.map { it.id })

        store.receive(Action.MessageReverted::class) {
            it.copy(
                isUndoingLastMessage = false,
                items = it.items + OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t revert: Session is busy"),
            )
        }

        assertEquals(listOf("confirm", "error"), haptics.log.map { it.name })
        assertNull(store.state.session?.revertMessageID)
        assertEquals("Untouched", store.state.draft)
        assertEquals(0, store.state.composerFocusRequest)
        store.finish()
    }

    @Test fun revertToHereIsDisabledWhileWorkingOrWhenFlagIsOffOrForNonUserMessages() = sessionTest { h ->
        val haptics = HapticsRecorder()
        val staged = LockIsolated(listOf<String>())
        val base = h.state(failedTurn)
        val initial = base.copy(
            items = base.items +
                OpenCodeTranscriptItem.UserText(id = "msg_agent", text = "From subagent", source = OpenCodePromptSource.Session(id = "ses_child")) +
                OpenCodeTranscriptItem.UserText(id = "msg_pending", text = "Still sending"),
            optimisticPromptIDs = base.optimisticPromptIDs + "msg_pending",
            isAwaitingReply = true,
        )
        val dependencies: ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, sessionID, messageID -> staged.append("$sessionID $messageID") })
        }
        val store = h.store(initial, dependencies)

        // Disabled while Working (`isRunning == true`).
        store.send(Action.RevertToHereTapped("msg_earlier"))
        assertTrue(staged.value.isEmpty())
        assertTrue(haptics.log.isEmpty())
        store.finish()

        // Disabled on optimistic/pending or agent-authored messages even when idle.
        val idleStore = h.store(initial.copy(isAwaitingReply = false), dependencies)
        idleStore.send(Action.RevertToHereTapped("msg_pending"))
        idleStore.send(Action.RevertToHereTapped("msg_agent"))
        assertTrue(staged.value.isEmpty())
        assertTrue(haptics.log.isEmpty())
        idleStore.finish()
    }

    @Test fun inFlightPageRefreshDoesNotClobberRevertMessageID() = sessionTest { h ->
        val initial = h.state(failedTurn, draft = "Earlier").copy(refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))

        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(stageRevert = { _, _, _ -> }, clearRevert = { _, _ -> })
        }

        // 1. A revert lands while a `.page` refresh (whose `getSession` read nil
        // before the revert) is still in flight.
        store.send(Action.RevertToHereTapped("msg_ask")) { it.copy(isUndoingLastMessage = true) }
        store.receive(Action.MessageReverted::class) {
            it.copy(isUndoingLastMessage = false)
                .setRevertMessageID("msg_ask")
                .withDraft("Earlier\n\nTighten the drawer")
                .withUndoSnapshot(UndoSnapshot(restoredDraft = "Earlier\n\nTighten the drawer", priorDraft = "Earlier"))
                .copy(composerFocusRequest = 1)
        }
        store.receive(acceptedTranscriptUpdated)

        val staleNilRead = store.state.session!!.copy(revertMessageID = null)
        store.send(
            Action.RefreshResponse(
                sessionID = "ses_1",
                requestID = 1,
                result = Result.success(
                    SessionFeature.AuthoritativeRefresh(
                        observed = staleNilRead,
                        items = failedTurn,
                        scope = SessionFeature.AuthoritativeRefresh.Scope.Page,
                        observedIsRead = true,
                    ),
                ),
            ),
        ) { it.copy(refresh = null, initialTranscriptIsFresh = true) }
        store.receive(acceptedTranscriptUpdated)
        assertEquals("msg_ask", store.state.session?.revertMessageID)
        assertEquals("Earlier\n\nTighten the drawer", store.state.draft)
        assertNotNull(store.state.undoSnapshot)
        store.finish()

        // 2. An Undo (`redoTapped`) lands while another `.page` refresh (whose
        // `getSession` read the stale non-nil `revertMessageID`) is in flight.
        val withSecondRefresh = store.state.copy(refresh = SessionFeature.State.Refresh(id = 2, reason = RefreshReason.Watchdog))
        val secondStore = h.store(withSecondRefresh) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(clearRevert = { _, _ -> })
        }

        secondStore.send(Action.RedoTapped)
        secondStore.receive(Action.Redone::class) {
            it.setRevertMessageID(null).withDraft("Earlier").withUndoSnapshot(null)
        }
        secondStore.receive(acceptedTranscriptUpdated)

        val staleRevertRead = secondStore.state.session!!.copy(revertMessageID = "msg_ask")
        secondStore.send(
            Action.RefreshResponse(
                sessionID = "ses_1",
                requestID = 2,
                result = Result.success(
                    SessionFeature.AuthoritativeRefresh(
                        observed = staleRevertRead,
                        items = failedTurn,
                        scope = SessionFeature.AuthoritativeRefresh.Scope.Page,
                        observedIsRead = true,
                    ),
                ),
            ),
        ) { it.copy(refresh = null) }
        secondStore.receive(acceptedTranscriptUpdated)
        assertNull(secondStore.state.session?.revertMessageID)
        assertEquals("Earlier", secondStore.state.draft)
        secondStore.finish()
    }

    @Test fun undoSnapshotAndUploadedFilesSurviveSessionSwitchFamilySwitchAndRelaunch() = sessionTest { h ->
        val tempDir = Files.createTempDirectory("RunErrorTests-").toFile()
        try {
            val firstPersistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds)
            val items = listOf(
                OpenCodeTranscriptItem.UserImages(id = "msg_earlier-images", images = listOf(OpenCodeImageAttachment(id = "img_1", data = pngData))),
                OpenCodeTranscriptItem.UserFiles(
                    id = "msg_earlier-files",
                    files = listOf(OpenCodeTranscriptFile(id = "file_pdf", name = "spec.pdf", mime = "application/pdf", path = "/project/spec.pdf")),
                ),
                OpenCodeTranscriptItem.UserText(id = "msg_earlier", text = "Hi"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_hello-text-0", text = "Hello"),
                OpenCodeTranscriptItem.UserText(id = "msg_ask", text = "Tighten the drawer"),
                OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_reply-text-0", text = "On it"),
            )

            val sibling = OpenCodeSession(
                id = "ses_2", serverTitle = "Subagent", projectID = "p", directory = "/project",
                updatedAt = Instant.ofEpochSecond(1_000), parentID = "ses_1",
            )
            val base = h.state(items, draft = "Earlier")
            val rootSession = base.session!!.copy(updatedAt = Instant.ofEpochSecond(1_000))
            val initial = base.copy(
                session = rootSession,
                familySessions = listOf(rootSession, sibling),
                familyRootID = "ses_1",
                familyTranscriptCache = mapOf("ses_1" to items, "ses_2" to emptyList()),
            )

            val store = h.store(initial) {
                this[UuidClient] = UuidClient.incrementing()
                this[ComposerPersistence] = firstPersistence
                this[PromptOutbox] = PromptOutbox()
                this[UndeliveredPrompts] = UndeliveredPrompts()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    stageRevert = { _, _, _ -> },
                    clearRevert = { _, _ -> },
                    getSession = { _, _ -> sibling },
                    transcript = { _, _, _ -> awaitCancellation() },
                    familyPendingRequests = { _, _, _ -> awaitCancellation() },
                    pendingInputs = { _, _ -> awaitCancellation() },
                    runningShells = { _, _ -> awaitCancellation() },
                    runningJobs = { _, _ -> awaitCancellation() },
                )
            }

            val expectedImageID = UUID(0, 0)
            val expectedFileID = UUID(0, 1)
            val expectedAttachments = listOf(OpenCodeOutgoingAttachment(id = expectedImageID.toString(), data = pngData, mime = "image/png"))
            val expectedFiles = listOf(
                ComposerFileAttachment(OpenCodeFileReference(name = "spec.pdf", mime = "application/pdf", path = "/project/spec.pdf"), id = expectedFileID),
            )
            val expectedSnapshot = UndoSnapshot(
                restoredDraft = "Earlier\n\nHi",
                priorDraft = "Earlier",
                attachmentIDs = setOf(expectedImageID),
                fileIDs = setOf(expectedFileID),
            )

            store.send(Action.RevertToHereTapped("msg_earlier")) { it.copy(isUndoingLastMessage = true) }
            store.receive(Action.MessageReverted::class) {
                it.copy(isUndoingLastMessage = false)
                    .setRevertMessageID("msg_earlier")
                    .withDraft("Earlier\n\nHi")
                    .withPendingAttachments(expectedAttachments)
                    .withPendingFiles(expectedFiles)
                    .withUndoSnapshot(expectedSnapshot)
                    .copy(composerFocusRequest = 1)
            }
            store.receive(acceptedTranscriptUpdated)

            // 1. Switch to a sibling in the family and back: `familySessions` keeps
            // `revertMessageID` and `familyDrafts` preserves `undoSnapshot`.
            store.send(Action.FamilyPicked("ses_2")) {
                it.copy(
                    familyTranscriptCache = it.familyTranscriptCache + ("ses_1" to items),
                    session = sibling,
                    items = emptyList(),
                    currentModel = null,
                    runWorkGeneration = 1,
                    historyGeneration = 1,
                    earlierHistoryGeneration = 1,
                    pendingWorkPollSeconds = 2,
                )
                    .withDraft("")
                    .withPendingAttachments(emptyList())
                    .withPendingFiles(emptyList())
                    .withUndoSnapshot(null)
                    .withRequestLoadAttempt(ai.opencode.feature.composer.RequestDockFeature.State.LoadAttempt.Initial)
            }
            store.receive(Action.RefreshRequested::class) {
                it.copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Open))
            }
            store.receive(Action.PendingInputsRequested::class)

            store.send(Action.FamilyPicked("ses_1")) {
                val restoredSession = rootSession.copy(revertMessageID = "msg_earlier")
                it.copy(
                    familyTranscriptCache = it.familyTranscriptCache + ("ses_2" to emptyList()),
                    session = restoredSession,
                    items = items,
                    newMessagesBaseline = items.map { item -> item.id }.toSet(),
                    runWorkGeneration = 2,
                    historyGeneration = 2,
                    earlierHistoryGeneration = 2,
                    refresh = null,
                )
                    .withDraft("Earlier\n\nHi")
                    .withPendingAttachments(expectedAttachments)
                    .withPendingFiles(expectedFiles)
                    .withUndoSnapshot(expectedSnapshot)
                    .withRequestLoadAttempt(ai.opencode.feature.composer.RequestDockFeature.State.LoadAttempt.Initial)
            }
            store.receive(Action.RefreshRequested::class) {
                it.copy(nextRefreshID = 2, refresh = SessionFeature.State.Refresh(id = 2, reason = RefreshReason.Open))
            }
            store.receive(Action.PendingInputsRequested::class)
            h.skipInFlightEffects()

            // 2. Capture SwitchSnapshot (with equal `updatedAt`) and flush persistence to disk,
            // then restore into a fresh relaunched store: `revertMessageID`, `undoSnapshot`,
            // and uploaded files all survive, and Undo removes the reverted items cleanly.
            val switchSnapshot = assertNotNullValue(h.state { SessionFeature.SwitchSnapshot.from(store.state) })
            firstPersistence.flush()

            val relaunchedPersistence = ComposerPersistence.make(ComposerPersistenceStorage.live(tempDir), debounce = 60.seconds)
            val relaunchedBase = h.state(items, draft = "")
            var relaunchedState = relaunchedBase.copy(
                session = relaunchedBase.session!!.copy(updatedAt = Instant.ofEpochSecond(1_000), revertMessageID = null),
                initialTranscriptRequestIsExternal = true,
            )
            relaunchedState = h.state { switchSnapshot.restore(relaunchedState) }
            assertEquals("msg_earlier", relaunchedState.session?.revertMessageID)

            // The relaunched store's effects run in a fresh harness scope (the first was cancelled).
            sessionTestScopeless(h) { h2 ->
                val relaunchedStore = h2.store(relaunchedState) {
                    this[ComposerPersistence] = relaunchedPersistence
                    this[PromptOutbox] = PromptOutbox()
                    this[UndeliveredPrompts] = UndeliveredPrompts()
                    this[DictationClient] = DictationClient(prepare = {})
                    this[OpenCodeClient] = this[OpenCodeClient].copy(clearRevert = { _, _ -> })
                }

                val expectedRelaunchedFiles = listOf(
                    ComposerFileAttachment(
                        ComposerStagedFile(
                            id = expectedFileID,
                            name = "spec.pdf",
                            mime = "application/pdf",
                            size = 0,
                            url = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "spec.pdf"),
                        ),
                        status = ComposerFileAttachment.Status.Uploaded("/project/spec.pdf"),
                    ),
                )

                relaunchedStore.send(Action.Task) {
                    it.withDraft("Earlier\n\nHi")
                        .withPendingAttachments(expectedAttachments)
                        .withPendingFiles(expectedRelaunchedFiles)
                        .withUndoSnapshot(expectedSnapshot)
                }

                relaunchedStore.send(Action.RedoTapped)
                relaunchedStore.receive(Action.Redone::class) {
                    it.setRevertMessageID(null)
                        .withDraft("Earlier")
                        .withPendingAttachments(emptyList())
                        .withPendingFiles(emptyList())
                        .withUndoSnapshot(null)
                }
                relaunchedStore.receive(acceptedTranscriptUpdated)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test fun revertAndRedoCancelInFlightEffectsAndGuardAgainstRunningSession() = sessionTest { h ->
        val revertGate = CompletableDeferred<Unit>()
        val clearGate = CompletableDeferred<Unit>()

        val initial = h.state(failedTurn, draft = "Earlier").setRevertMessageID("msg_ask").copy(refresh = null)

        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                stageRevert = { _, _, _ -> revertGate.await() },
                clearRevert = { _, _ -> },
            )
        }

        // 1. `revertToHereTapped` starts a cancellable `CancelID.revert` effect, and
        // `redoTapped` cancels it in flight while resetting `isUndoingLastMessage`.
        store.send(Action.RevertToHereTapped("msg_earlier")) { it.copy(isUndoingLastMessage = true) }
        store.send(Action.RedoTapped) { it.copy(isUndoingLastMessage = false) }
        revertGate.complete(Unit)
        store.receive(Action.Redone::class) { it.setRevertMessageID(null) }
        store.receive(acceptedTranscriptUpdated)
        store.finish()

        // 2. Sending a message cancels an in-flight `redoTapped` (`CancelID.revert`).
        val sendState = h.state(failedTurn, draft = "Send now").setRevertMessageID("msg_ask").copy(refresh = null)
        val sendStore = h.store(sendState) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                clearRevert = { _, _ -> clearGate.await() },
                sendPrompt = { _, _, _, _, _, _, _ -> },
                waitForIdle = { _, _ -> throw OpenCodeClientError(message = "Idle wait not under test") },
            )
        }

        val sent = "msg_00000000000100000000000000"
        sendStore.send(Action.RedoTapped)
        sendStore.send(Action.SendTapped) {
            val oldSignal = it.workingRunSignal
            it.setRevertMessageID(null)
                .copy(
                    items = failedTurn.take(2) + OpenCodeTranscriptItem.UserText(id = sent, text = "Send now"),
                    runWorkGeneration = 1,
                    isAwaitingReply = true,
                    nextPromptRequestEpoch = 1,
                    promptStates = it.promptStates + (sent to SessionFeature.State.PromptState(requestEpoch = 1, admission = SessionFeature.State.PromptState.Admission.sent)),
                    optimisticPromptIDs = setOf(sent),
                    lastSentItemID = sent,
                    sendGeneration = 1,
                )
                .withDraft("")
                .updateWorkingRun(oldSignal)
        }
        clearGate.complete(Unit)
        sendStore.receive(Action.PromptAdmitted::class) {
            it.copy(promptStates = it.promptStates + (sent to it.promptStates[sent]!!.copy(admission = SessionFeature.State.PromptState.Admission.admitted)))
        }
        sendStore.skipReceivedActions()
        h.skipInFlightEffects()

        // 3. `messageReverted` and `lastMessageUndone` are ignored when `isRunning`
        // or when `isUndoingLastMessage` is false.
        sessionTestScopeless(h) { h2 ->
            val runningState = h2.state(failedTurn, draft = "Untouched").copy(isUndoingLastMessage = true, isExternallyActive = true)
            val guardStore = h2.store(runningState)
            val dummyRestored = PendingPrompt(messageID = "msg_ask", text = "Tighten the drawer", attachments = emptyList(), requestEpoch = 0)
            guardStore.send(Action.MessageReverted(sessionID = "ses_1", messageID = "msg_ask", restored = dummyRestored, error = null)) {
                it.copy(isUndoingLastMessage = false)
            }
            assertNull(guardStore.state.session?.revertMessageID)
            assertEquals("Untouched", guardStore.state.draft)

            guardStore.send(Action.LastMessageUndone(sessionID = "ses_1", messageID = "msg_ask", text = "Tighten the drawer", error = null))
            assertNull(guardStore.state.session?.revertMessageID)
            assertEquals("Untouched", guardStore.state.draft)
            guardStore.finish()
        }
    }

    @Test fun slashCommandSentWhileRevertIsStagingCancelsTheRevert() = sessionTest { h ->
        val stageGate = CompletableDeferred<Unit>()
        val idleGate = CompletableDeferred<Unit>()
        val commandSent = LockIsolated(0)
        val command = OpenCodeAutocompleteCommand(name = "review", description = "Review changes")
        val base = h.state(failedTurn, draft = "/review check")
        val initial = base.copy(
            autocomplete = base.autocomplete.copy(chips = listOf(ComposerAutocompleteChip(AutocompleteItem.command(command), id = UUID(0, 0)))),
        )
        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                stageRevert = { _, _, _ -> stageGate.await() },
                sendCommand = { _, _, _, _, _, _ -> commandSent.increment() },
                waitForIdle = { _, _ -> idleGate.await() },
                getSession = { _, _ -> chatSession },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }

        store.send(Action.RevertToHereTapped("msg_ask")) { it.copy(isUndoingLastMessage = true) }
        store.exhaustive = false
        store.send(Action.SendTapped)
        assertFalse(store.state.isUndoingLastMessage)

        stageGate.complete(Unit)
        idleGate.complete(Unit)
        store.finish()

        assertEquals(1, commandSent.value)
        assertNull(store.state.session?.revertMessageID)
        assertNull(store.state.undoSnapshot)
        assertEquals("", store.state.draft)
    }

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    /** A fresh [Harness] (own scope and shared store) for a later phase of a test whose first harness's effects were skipped. */
    private suspend fun sessionTestScopeless(parent: Harness, block: suspend (Harness) -> Unit) {
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
