package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.map
import ai.opencode.core.strings.localized
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.core.files.AttachmentFileType
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.server.model.OpenCodeOutgoingAttachment

// Attachments, uploads, and Location as the session sees them. `:feature:composer`'s
// `ComposerAttachmentsFeature` reduces the rest of each action first.

private fun SessionFeature.appendAttachment(state: State, attachment: OpenCodeOutgoingAttachment, haptic: Boolean = true): Reduced<State, Action> {
    val parked = state.parkedComposer
    return when {
        // An import that finishes while the bar answers belongs to the waiting message.
        parked != null -> Reduced(state.withParkedComposer(parked.copy(attachments = parked.attachments + attachment)))
        !state.takesPasteAttachments -> Reduced(state)
        else -> {
            if (haptic) haptics.play(Haptic.selection)
            Reduced(state.withPendingAttachments(state.pendingAttachments + attachment))
        }
    }
}

/** Attachments, uploads, and Location. Null: the action passes on. */
internal fun SessionFeature.reduceAttachments(state: State, action: Action): Reduced<State, Action>? {
    if (action is Action.DrawingAdded) {
        if (state.taskIdentity != action.from || !state.takesPasteAttachments) return Reduced(state)
        val index = state.pendingAttachments.indexOfFirst { it.id == action.replacing }
        if (index < 0) return appendAttachment(state, action.attachment)
        haptics.play(Haptic.selection)
        val attachments = state.pendingAttachments.toMutableList()
        attachments[index] = action.attachment
        return Reduced(state.withPendingAttachments(attachments))
    }
    val attachments = (action as? Action.ComposerAttachments)?.action ?: return null
    return when (attachments) {
        is ComposerAttachmentsFeature.Action.AttachmentAdded -> appendAttachment(state, attachments.attachment)

        // Add already answered the press: one haptic per Add, not one per photo.
        is ComposerAttachmentsFeature.Action.AttachmentLanded -> appendAttachment(state, attachments.attachment, haptic = false)

        is ComposerAttachmentsFeature.Action.AttachmentRemoved,
        ComposerAttachmentsFeature.Action.LocationTapped,
        ComposerAttachmentsFeature.Action.LocationRemoved,
        is ComposerAttachmentsFeature.Action.QuoteRemoved,
        is ComposerAttachmentsFeature.Action.QuoteCommented,
        ComposerAttachmentsFeature.Action.LocationErrorDismissed,
        ComposerAttachmentsFeature.Action.LocationSettingsTapped,
        -> Reduced(state)

        is ComposerAttachmentsFeature.Action.FilesStaged -> {
            var next = state
            val effects = mutableListOf<Effect<Action>>()
            var staged = false
            for (file in attachments.files) {
                // Like desktop, media the model reads natively travels inline.
                if (file.mime == AttachmentFileType.pdfMime && file.size <= AttachmentFileType.maxInlineBytes && next.currentModelReads("pdf")) {
                    effects += ComposerAttachmentsFeature.readInlineFile(file).map(Action::ComposerAttachments)
                    continue
                }
                val parked = next.parkedComposer
                next = if (parked != null) {
                    next.withParkedComposer(parked.copy(files = parked.files + ComposerFileAttachment(file)))
                } else {
                    next.withPendingFiles(next.pendingFiles + ComposerFileAttachment(file))
                }
                val uploaded = upload(file.id, next)
                next = uploaded.state
                effects += uploaded.effect
                staged = true
            }
            // An inline file ticks as `attachmentAdded` when its bytes are read.
            if (staged) haptics.play(Haptic.selection)
            Reduced(next, Effect.merge(effects))
        }

        ComposerAttachmentsFeature.Action.QueuedUploadsStarted -> {
            val ids = state.unstartedUploadIDs
            var next = state.withUnstartedUploadIDs(emptyList())
            val effects = mutableListOf<Effect<Action>>()
            for (id in ids) {
                val uploaded = upload(id, next)
                next = uploaded.state
                effects += uploaded.effect
            }
            Reduced(next, Effect.merge(effects))
        }

        is ComposerAttachmentsFeature.Action.FileUploadProgress ->
            Reduced(
                updateFile(attachments.id, state) { file ->
                    if (!file.isUploading) file else file.copy(status = ComposerFileAttachment.Status.Uploading(attachments.sent))
                },
            )

        is ComposerAttachmentsFeature.Action.FileUploadFinished -> {
            val id = attachments.id
            val result = attachments.result
            if (result is ComposerAttachmentsFeature.Outcome.Success) {
                val path = result.value
                sendIfUploadsFinished(updateFile(id, state) { it.copy(status = ComposerFileAttachment.Status.Uploaded(path)) })
            } else {
                val isUnsupported = (result as? ComposerAttachmentsFeature.Outcome.Failure)?.error?.isUnsupported == true
                if (isUnsupported) {
                    val serverID = state.server.persistenceID
                    state.uploadUnsupportedServerIDs.update { it + serverID }
                }
                var next = updateFile(id, state) {
                    it.copy(status = ComposerFileAttachment.Status.Failed(if (isUnsupported) localized(R.string.session_attachment_files_unsupported) else localized(R.string.session_attachment_upload_failed)))
                }
                if (next.pendingFiles.any { it.id == id } || next.parkedComposer?.files?.any { it.id == id } == true) {
                    next = forgetSharedSend(next.withSendAfterUploads(null))
                }
                haptics.play(Haptic.error)
                Reduced(next)
            }
        }

        is ComposerAttachmentsFeature.Action.FileRetryTapped -> {
            val id = attachments.id
            val file = (state.pendingFiles + (state.parkedComposer?.files ?: emptyList())).firstOrNull { it.id == id }
            if (!state.supportsFileUploads || file == null || !file.isFailed) {
                Reduced(state)
            } else {
                haptics.play(Haptic.tap)
                upload(id, state)
            }
        }

        is ComposerAttachmentsFeature.Action.LocationResponse -> {
            val found = state.pendingLocation
            val parked = state.parkedComposer
            // Asked for the message: it waits there while the bar answers.
            if (attachments.result is ComposerAttachmentsFeature.Outcome.Success && parked != null && found != null) {
                Reduced(state.withParkedComposer(parked.copy(location = found)).withPendingLocation(null))
            } else {
                Reduced(state)
            }
        }

        is ComposerAttachmentsFeature.Action.FileRemoved -> {
            val index = state.pendingFiles.indexOfFirst { it.id == attachments.id }
            if (index < 0) {
                Reduced(state)
            } else {
                val file = state.pendingFiles[index]
                val next = state.withPendingFiles(state.pendingFiles.filterIndexed { i, _ -> i != index })
                val send = sendIfUploadsFinished(next)
                Reduced(
                    send.state,
                    Effect.merge(
                        ComposerAttachmentsFeature.cancelUpload(file.id, discardPath = file.localPath).map(Action::ComposerAttachments),
                        send.effect,
                    ),
                )
            }
        }
    }
}
