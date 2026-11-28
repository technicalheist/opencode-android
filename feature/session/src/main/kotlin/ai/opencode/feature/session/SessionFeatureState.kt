package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.UndoSnapshot
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerFileAttachment
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptSubagent
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceLabDemo
import ai.opencode.feature.voice.VoiceReadiness
import ai.opencode.feature.voice.VoiceRoute
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeCompaction
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.isUntitled
import ai.opencode.server.model.keepingFirstPrompt
import ai.opencode.server.model.learningFirstPrompt
import ai.opencode.server.model.model
import java.time.Instant
import java.util.UUID
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.shared

// Computed properties as extension `val`s, `mutating func`s as `fun State.x(): State`. Get/set
// pairs are a getter plus a `withX(value)` setter. Nested types live in `State`
// (`SessionFeature.kt`), since Kotlin keeps nested declarations in the class body. Dictation,
// models, requests and transcript state are in `SessionFeatureState<Area>.kt`.

// region Draft

/** An edit drops [State.nonSendNewlineDraft]. */
fun State.withDraft(text: String): State =
    copy(draft = text, nonSendNewlineDraft = if (nonSendNewlineDraft != text) null else nonSendNewlineDraft)

// endregion

// region State members

val State.browserAskIsLive: Boolean
    get() {
        if (!browser.isOpen) return false
        val id = browser.peekMessageID ?: return false
        if (failedSends.withID(id) != null || id in cancelledInputIDs) return false
        return promptStates[id] != null || queuedInputs.any { it.id == id } || isRunning
    }

/** Null when there's nothing to show: the send failed or was cancelled, or the turn ended without prose. */
val State.browserPeek: State.BrowserPeek?
    get() {
        if (!browser.isOpen) return null
        val id = browser.peekMessageID ?: return null
        if (failedSends.withID(id) != null || id in cancelledInputIDs) return null
        val sent = items.indexOfLast { TranscriptEntry.promptID(it) == id }
        if (sent >= 0) {
            val reply = items.subList(sent + 1, items.size).asReversed().asSequence()
                .mapNotNull { item -> (item as? OpenCodeTranscriptItem.AssistantMarkdown)?.let { State.peekText(it.text) } }
                .firstOrNull { it.isNotEmpty() }
            if (reply != null) return State.BrowserPeek(reply)
        }
        // Working while the message is on its way or its turn runs.
        val waiting = promptStates[id] != null || queuedInputs.any { it.id == id }
        return if (waiting || isRunning || isAwaitingReply) State.BrowserPeek(null) else null
    }

fun State.syncShellToolStatuses(): State {
    val next = State.shellToolStatuses(items)
    val nextSubagents = State.subagentToolSessionIDs(items)
    if (shellToolStatuses == next && subagentToolSessionIDs == nextSubagents) return this
    return copy(shellToolStatuses = next, subagentToolSessionIDs = nextSubagents)
}

/**
 * Shells that outlive their tool call, matching the TUI's shell count minus commands the agent is
 * still waiting on in the foreground.
 */
val State.backgroundShells: List<OpenCodeShell>
    get() {
        val sessionID = session?.id ?: return emptyList()
        if (shells.isEmpty()) return emptyList()
        return shells.filter { shell ->
            if (shell.sessionID != sessionID) return@filter false
            if (shell.isUserCommand) return@filter true
            val status = shellToolStatuses[shell.id] ?: return@filter shell.isListed
            status == OpenCodeToolCall.Status.Completed || status == OpenCodeToolCall.Status.Error
        }
    }

/** Something the person opened over this session. */
val State.presentsOverSession: Boolean
    get() = activePicker != null || voice != null || folderBrowser != null || shellOutput != null ||
        browser.isOpen || requestDock.isAnswering

/** Only the transcript's tail can hold the run's current tool. */
val State.foregroundWork: State.ForegroundWork?
    get() {
        if (!isRunning || session == null) return null
        for (item in items.takeLast(64).asReversed()) {
            if (item !is OpenCodeTranscriptItem.Tool) continue
            val call = item.call
            if (call.messageID == null || call.status != OpenCodeToolCall.Status.Running) continue
            if (!call.isShell) continue
            return State.ForegroundWork(item.id, call.startedAt)
        }
        return null
    }

/** A quiet line under a send waiting behind one that failed. */
val State.deliveryNotes: Map<String, DeliveryNote>
    get() {
        if (failedSends.isEmpty()) return emptyMap()
        return promptStates.filterValues { it.admission == State.PromptState.Admission.sent }.mapValues { DeliveryNote.waiting }
    }

/** The newest finished compaction in this screen's session. */
val State.completedCompactionMark: VoiceFeature.State.CompactionMark?
    get() {
        val session = session ?: return null
        for (item in items.asReversed()) {
            if (item is OpenCodeTranscriptItem.Compaction &&
                (item.info?.status ?: OpenCodeCompaction.Status.Completed) == OpenCodeCompaction.Status.Completed
            ) {
                return VoiceFeature.State.CompactionMark(session.id, item.id)
            }
        }
        return null
    }

fun State.items(excluding: Set<String>): List<OpenCodeTranscriptItem> {
    if (excluding.isEmpty()) return items
    return items.filter { item -> TranscriptEntry.promptID(item)?.let { it !in excluding } ?: true }
}

val State.browsingStartIndex: Int?
    get() {
        val id = browsingWindowStartID ?: return null
        if (items.firstOrNull()?.id == id) return 0
        items.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { return it }
        return items.indexOfFirst {
            val messageID = SessionFeature.messageID(it)
            messageID == id || id.startsWith("$messageID-")
        }.takeIf { it >= 0 }
    }

// endregion

// region Loading and errors

val State.isResyncing: Boolean get() = resync?.isShown == true

val State.errorMessage: String?
    get() {
        val loadError = loadError ?: return null
        if (isDraft || initialTranscriptIsFresh || isRunning || queuedInputs.isNotEmpty() || isTranscriptLoading || items.isNotEmpty()) {
            return null
        }
        return loadError
    }

/** Writes [State.loadError]. */
fun State.withErrorMessage(value: String?): State = copy(loadError = value)

val State.effectiveDirectory: String? get() = session?.directory ?: draftProject?.directory

// endregion

// region Dictation (forwarding to `dictation`)

fun State.updateDictation(transform: (DictationFeature.State) -> DictationFeature.State): State = copy(dictation = transform(dictation))

val State.dictationPhase: DictationFeature.Phase get() = dictation.phase
fun State.withDictationPhase(value: DictationFeature.Phase): State = updateDictation { it.copy(phase = value) }
val State.dictationError: String? get() = dictation.error
fun State.withDictationError(value: String?): State = updateDictation { it.copy(error = value) }
val State.dictationBase: String get() = dictation.base
fun State.withDictationBase(value: String): State = updateDictation { it.copy(base = value) }
val State.dictationRecording: Int get() = dictation.recording
fun State.withDictationRecording(value: Int): State = updateDictation { it.copy(recording = value) }
val State.dictationPress: Int get() = dictation.press
fun State.withDictationPress(value: Int): State = updateDictation { it.copy(press = value) }
val State.sendingDictation: DictationFeature.Sending? get() = dictation.sending
fun State.withSendingDictation(value: DictationFeature.Sending?): State = updateDictation { it.copy(sending = value) }
val State.dictationEndedAt: Instant? get() = dictation.endedAt
fun State.withDictationEndedAt(value: Instant?): State = updateDictation { it.copy(endedAt = value) }
val State.dictationHold: DictationFeature.Hold? get() = dictation.hold
fun State.withDictationHold(value: DictationFeature.Hold?): State = updateDictation { it.copy(hold = value) }

// endregion

// region Attachments (forwarding to `composerAttachments`)

fun State.updateComposerAttachments(transform: (ComposerAttachmentsFeature.State) -> ComposerAttachmentsFeature.State): State =
    copy(composerAttachments = transform(composerAttachments))

val State.pendingAttachments: List<OpenCodeOutgoingAttachment> get() = composerAttachments.pendingAttachments
fun State.withPendingAttachments(value: List<OpenCodeOutgoingAttachment>): State =
    updateComposerAttachments { it.copy(pendingAttachments = value) }
/** Non-image files: uploading, uploaded (sent as path references), or failed. */
val State.pendingFiles: List<ComposerFileAttachment> get() = composerAttachments.pendingFiles
fun State.withPendingFiles(value: List<ComposerFileAttachment>): State = updateComposerAttachments { it.copy(pendingFiles = value) }
/** A location from the + menu, waiting to go with the next message. */
val State.pendingLocation: SharedLocation? get() = composerAttachments.pendingLocation
fun State.withPendingLocation(value: SharedLocation?): State = updateComposerAttachments { it.copy(pendingLocation = value) }
val State.pendingQuotes: List<ComposerQuote> get() = composerAttachments.pendingQuotes
fun State.withPendingQuotes(value: List<ComposerQuote>): State = updateComposerAttachments { it.copy(pendingQuotes = value) }
/** The + menu's Location is finding the phone. */
val State.isLocating: Boolean get() = composerAttachments.isLocating
fun State.withIsLocating(value: Boolean): State = updateComposerAttachments { it.copy(isLocating = value) }
/** Why the last Location couldn't be shared, until dismissed. */
val State.locationError: String? get() = composerAttachments.locationError
fun State.withLocationError(value: String?): State = updateComposerAttachments { it.copy(locationError = value) }
/** Send was tapped while files were still uploading; the message goes with this delivery once they land. */
val State.sendAfterUploads: OpenCodeDelivery? get() = composerAttachments.sendAfterUploads
fun State.withSendAfterUploads(value: OpenCodeDelivery?): State = updateComposerAttachments { it.copy(sendAfterUploads = value) }
/** Shared files absorbed into this composer whose uploads haven't started. */
val State.unstartedUploadIDs: List<UUID> get() = composerAttachments.unstartedUploadIDs
fun State.withUnstartedUploadIDs(value: List<UUID>): State = updateComposerAttachments { it.copy(unstartedUploadIDs = value) }

// endregion

// region Requests (forwarding to `requestDock`)

fun State.updateRequestDock(transform: (RequestDockFeature.State<DraftState>) -> RequestDockFeature.State<DraftState>): State =
    copy(requestDock = transform(requestDock))

/**
 * [answerDrafts] with the words on the bar now, which are only filed there when the bar moves to
 * another page or request.
 */
val State.answerDraftsIncludingBar: Map<String, String>
    get() {
        val shown = answerShownTarget
        return if (parkedComposer != null && shown != null) answerDrafts + (shown to draft) else answerDrafts
    }

/** The composer as an undo/revert left it (the parked composer's while a request holds the bar). */
val State.undoSnapshot: UndoSnapshot?
    get() = if (parkedComposer != null) parkedComposer?.undoSnapshot else composerAttachments.undoSnapshot

fun State.withUndoSnapshot(value: UndoSnapshot?): State {
    val parked = parkedComposer
    return if (parked != null) withParkedComposer(parked.copy(undoSnapshot = value))
    else updateComposerAttachments { it.copy(undoSnapshot = value) }
}

/** The request the dock shows: the selected one while it's pending, else the first. */
val State.dockRequestID: String? get() = requestDock.dockRequestID

/** Drops requests matching [removing] and any answers, pages, errors, or selection for requests no longer pending. */
fun State.pruneRequests(
    removing: (id: String, sessionID: String) -> Boolean = { _, _ -> false },
    keepingSelection: Set<String> = emptySet(),
): State {
    val (dock, clearedSelection) = requestDock.prune(removing, keepingSelection)
    var next = copy(requestDock = dock)
    if (clearedSelection && next.activePicker == State.ActivePicker.requests) next = next.copy(activePicker = null)
    return next
}

/** Some request dock is (or should be) replacing the composer. */
val State.hasPendingRequests: Boolean get() = requestDock.hasPendingRequests

val State.reviewRequestIDs: List<String> get() = requestDock.reviewRequestIDs

/** Sessions a pending request waits on: their activity glyphs show Waiting for Input. */
val State.waitingSessionIDs: Set<String> get() = requestDock.waitingSessionIDs

/** Transient request failures stay quiet while connectivity is already explaining the outage. */
val State.settledRequestLoadError: String?
    get() = if (!requestsConnectionIsAvailable || requestLoadAttempt != null) null else requestLoadError

// endregion

// region Working run

fun State.updateWorkingRun(old: WorkingRun.Signal): State =
    copy(workingRun = workingRun.update(old, workingRunSignal, items, failedSends.map { it.id }.toSet()))

/** The Working line's cached orb: per detail, and per draft run. */
val State.workingOrbKey: String get() = "$navigationID|" + (workingRun.key ?: session?.id ?: "draft")

val State.workingRunSignal: WorkingRun.Signal
    get() = WorkingRun.Signal(session?.id, isRunning, isInterrupting, items)

/**
 * A reply's text for the browser peek: its prose, one block per line. Tables and images wait for
 * the transcript.
 */
fun State.Companion.peekText(markdown: String): String {
    val result = StringBuilder()
    for (block in OpenCodeMarkdownBlock.blocks(markdown)) {
        val piece = when (val kind = block.kind) {
            is OpenCodeMarkdownBlock.Kind.Paragraph -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.Blockquote -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.Heading -> kind.text.text
            is OpenCodeMarkdownBlock.Kind.NumberedItem -> "${kind.marker} ${kind.text.text}"
            is OpenCodeMarkdownBlock.Kind.ListItem -> "${kind.marker} ${kind.text.text}"
            is OpenCodeMarkdownBlock.Kind.Code -> kind.code.source
            else -> null
        }
        if (piece.isNullOrEmpty()) continue
        if (result.isNotEmpty()) result.append('\n')
        result.append(piece)
    }
    return result.toString()
}

// endregion

// region Voice

/**
 * Voice is an opt-in feature (Settings → Voice): calls go through a server that brokers them, or
 * the user's own key for the chosen engine; without either, the call button stays hidden.
 */
val State.voiceRoute: VoiceRoute
    get() {
        val hasOpenAIKey = !openAIAPIKey.value.isNullOrBlank()
        val broker = VoiceBroker(
            choice = voiceServer.value,
            provider = serverVoiceProvider.value,
            connected = server,
            saved = VoiceFeature.savedServers,
            readiness = VoiceReadiness(
                answers = voiceModelsByServer.value,
                support = serverVoiceSupportByServer.value,
                hasPhoneKey = hasOpenAIKey,
                engine = voiceEngine.value,
            ),
        )
        return broker.route(
            engine = voiceEngine.value,
            support = broker.support(serverVoiceSupportByServer.value),
            hasOpenAIKey = hasOpenAIKey,
            hasXAIKey = !xaiAPIKey.value.isNullOrBlank(),
        )
    }

val State.isVoiceAvailable: Boolean get() = voiceLabOverride || (voiceModeEnabled.value && voiceRoute.isAvailable)

/**
 * A scripted call, a stand-in server, or the lab's key make the call button available.
 * [VoiceLabDemo] answers nothing in release builds.
 */
private val voiceLabOverride: Boolean
    get() = VoiceLabDemo.isEnabled || VoiceLabDemo.hasStandInServer || VoiceLabDemo.labAPIKey != null

/** The model the next send applies: a draft's model for the session it creates, or a pick still waiting in a session. */
val State.sendModel: OpenCodeModelRef? get() = if (session == null) currentModel else pendingModel

/** Hands the call the model its next delegation applies, while it's on this screen's session or draft. */
fun State.syncVoiceModel(): State {
    val model = sendModel
    val voice = voice ?: return this
    if (voice.target.sessionID != session?.id || voice.pendingModel == model) return this
    return copy(voice = voice.copy(pendingModel = model))
}

/** The server has [model] for [sessionID]: a pick it matches no longer waits. */
fun State.modelApplied(model: OpenCodeModelRef, sessionID: String): State {
    var next = this
    val index = familySessions.indexOfFirst { it.id == sessionID }
    if (index >= 0) next = next.copy(familySessions = familySessions.toMutableList().also { it[index] = it[index].copy(model = model) })
    if (next.session?.id == sessionID) {
        var pending = next.pendingModel
        if (pending == model) pending = null
        // An earlier send's model landed after a newer pick went back to this one: the composer's model waits again.
        val current = next.currentModel
        if (current != null && current != model) pending = current
        next = next.copy(
            pendingModel = pending,
            session = next.session?.copy(model = model),
            refresh = next.refresh?.copy(modelChanged = true),
        )
    } else if (next.familyPendingModels[sessionID] == model) {
        next = next.copy(familyPendingModels = next.familyPendingModels - sessionID)
    }
    // A call on a family member off screen holds its own copy.
    val voice = next.voice
    if (voice != null && voice.target.sessionID == sessionID && voice.pendingModel == model) {
        next = next.copy(voice = voice.copy(pendingModel = null))
    }
    return next
}

/** The newest finished compaction in the session a call is on, while the screen shows it. */
val State.voiceCompaction: VoiceFeature.State.CompactionMark?
    get() {
        val session = session ?: return null
        if (voice?.target?.sessionID != session.id) return null
        return completedCompactionMark
    }

/** Projects this screen's (or [targetScreen]'s) model pick, request state, and compaction onto the active call. */
fun State.syncVoiceTarget(targetScreen: State? = null): State {
    val target = targetScreen ?: this
    var voice = voice ?: return this
    if (voice.target.sessionID != target.session?.id) return this
    voice = voice.copy(pendingModel = target.sendModel, targetNeedsInput = target.hasPendingRequests)
    target.completedCompactionMark?.let { mark -> voice = voice.copy(compactions = voice.compactions + (mark.sessionID to mark.compactionID)) }
    return copy(voice = voice)
}

/** Enforces cross-field invariants after a parent constructs, restores, or retargets a screen. */
fun State.reconcileInvariants(targetScreen: State? = null): State {
    var next = this
    if (!next.wantsRequestDock && next.isAnsweringRequest) next = next.endAnswering()
    return next.syncVoiceTarget(targetScreen)
}

/** The call's bar and its notification name its session. */
fun State.renameVoiceTarget(sessionID: String, title: String): State {
    val resolved = VoiceFeature.State.usableTitle(title) ?: return this
    val voice = voice ?: return this
    if (voice.target.sessionID != sessionID || voice.sessionTitle == resolved) return this
    return copy(voice = voice.copy(sessionTitle = resolved))
}

/** A call is connecting, live, or leaving: its audio holds the session. A failed or dropped call's card leaves it free. */
val State.callOwnsAudio: Boolean get() = voice?.let { !it.phase.showsCard } ?: false

/** A voice call vouches for its pinned session from admission until the last delegated prompt answers. */
private val State.isRunningVoiceWork: Boolean
    get() {
        val voice = voice ?: return false
        if (voice.target.sessionID != session?.id) return false
        return voice.hasAdmittedWork
    }

// endregion

// region Session, context and family

val State.contextLimit: Int?
    get() {
        val usage = contextUsage ?: return null
        return models.model(usage.model)?.contextLimit
    }

/** The share of the window used, as the TUI's percent; null hides the ring. */
val State.contextFraction: Double?
    get() {
        val usage = contextUsage ?: return null
        val limit = contextLimit ?: return null
        return usage.tokens.toDouble() / limit.toDouble()
    }

/** A root's spend includes its subagents', as in the TUI. */
val State.sessionCost: Double?
    get() {
        val session = session ?: return null
        if (session.parentID != null) return session.cost
        val total = (session.cost ?: 0.0) + familySessions.filter { it.id != session.id }.sumOf { it.cost ?: 0.0 }
        return if (total > 0) total else null
    }

val State.isDraft: Boolean get() = session == null

/** The screen's task identity: each server, visit, and session runs `task` once, except a draft becoming its own session. */
val State.taskIdentity: State.TaskIdentity
    get() {
        val continues = session != null && session?.id == draftBecameSessionID
        return State.TaskIdentity(server.persistenceID, visit, if (continues) null else session?.id)
    }

/** The Project sheet, from the top: search cleared, no folder pushed. */
fun State.openProjectPicker(): State =
    copy(activePicker = State.ActivePicker.project, projectFilter = "", folderBrowser = FolderBrowserFeature.State(server = server))

/** Typed admission and queued prompts bridge gaps in server execution; a voice call also owns work until its replies arrive. */
val State.isRunning: Boolean get() = isAwaitingReply || isExternallyActive || isRunningVoiceWork

/**
 * Busy descendants are visible even after this session's own run ends, and so is an idle one
 * whose tree still runs a job.
 */
val State.activeSubagents: List<OpenCodeSession>
    get() {
        val selectedID = session?.id ?: return emptyList()
        val parents = HashMap<String, String>()
        for (member in familySessions) member.parentID?.let { parents[member.id] = it }
        return familySessions.filter {
            it.id != selectedID && selectedID in SessionActivity.ancestors(setOf(it.id), parents) &&
                (it.id in activeFamilySessionIDs || (jobs.isNotEmpty() && subagentJobIDs(it.id, parents).isNotEmpty()))
        }.sortedBy { it.id }
    }

val State.isBusy: Boolean get() = isRunning || activeSubagents.isNotEmpty()
val State.hasBackgroundWork: Boolean get() = activeSubagents.isNotEmpty() || backgroundShells.isNotEmpty() || backgroundScripts.isNotEmpty()

val State.transcriptSubagents: Map<String, TranscriptSubagent>
    get() {
        val selectedID = session?.id
        val waiting = waitingSessionIDs
        val result = LinkedHashMap<String, TranscriptSubagent>()
        for (member in familySessions) {
            if (member.id == selectedID) continue
            result[member.id] = TranscriptSubagent(
                title = member.title,
                agent = member.agent,
                isKnown = true,
                isRunning = member.id in activeFamilySessionIDs,
                needsInput = member.id in waiting,
            )
        }
        val hasUnknownActive = activeFamilySessionIDs.any { it != selectedID && result[it] == null }
        val hasUnknownWaiting = waiting.any { it != selectedID && result[it] == null }
        if (!hasUnknownActive && !hasUnknownWaiting) return result
        val referencedIDs = subagentToolSessionIDs
        for (id in activeFamilySessionIDs + waiting) {
            if (id == selectedID || result[id] != null || id !in referencedIDs) continue
            result[id] = TranscriptSubagent(
                title = null,
                agent = null,
                isKnown = false,
                isRunning = id in activeFamilySessionIDs,
                needsInput = id in waiting,
            )
        }
        return result
    }

/** Returns the state with a Session sheet for the current session, and whether there is one. */
fun State.ensureSessionSheet(): Pair<State, Boolean> {
    val session = session ?: return this to false
    var sheet = sessionSheet
    if (sheet?.sessionID != session.id) sheet = SessionSheetFeature.State(server = server, sessionID = session.id)
    return copy(sessionSheet = sheet.copy(pendingModel = pendingModel)) to true
}

/** Records a session in this family as read up to its idle watermark. */
fun State.markViewed(session: OpenCodeSession): State {
    val idle = session.idleMilliseconds ?: return this
    var next = this
    val shown = next.session
    if (shown != null && shown.id == session.id) {
        next = next.copy(session = shown.copy(viewedMilliseconds = maxOf(shown.viewedMilliseconds ?: 0, idle)))
    }
    val index = next.familySessions.indexOfFirst { it.id == session.id }
    if (index >= 0) {
        val member = next.familySessions[index]
        next = next.copy(
            familySessions = next.familySessions.toMutableList().also {
                it[index] = member.copy(viewedMilliseconds = maxOf(member.viewedMilliseconds ?: 0, idle))
            },
        )
    }
    return next
}

/** The composer follows the model the server reports for the session, unless a pick of its own is still waiting. */
fun State.adoptServerModel(model: OpenCodeModelRef?): State {
    if (model == null || pendingModel != null || currentModel == model) return this
    return copy(currentModel = model)
}

fun State.setRevertMessageID(messageID: String?): State {
    var next = copy(session = session?.copy(revertMessageID = messageID))
    val id = next.session?.id
    val index = if (id == null) -1 else next.familySessions.indexOfFirst { it.id == id }
    if (index >= 0) {
        next = next.copy(familySessions = next.familySessions.toMutableList().also { it[index] = it[index].copy(revertMessageID = messageID) })
    }
    return next.copy(refresh = next.refresh?.copy(revertChanged = true))
}

fun State.updateObservedSession(fresh: OpenCodeSession): State {
    val observed = fresh.keepingFirstPrompt(session)
    var next = this
    if (observed.revertMessageID == null) next = next.clearUntouchedUndoDraft()
    next = next.copy(session = observed)
    val index = next.familySessions.indexOfFirst { it.id == observed.id }
    if (index >= 0) next = next.copy(familySessions = next.familySessions.toMutableList().also { it[index] = observed })
    return next.renameVoiceTarget(observed.id, observed.title)
}

/**
 * The session on screen (and its family copy) learn their first prompt once the loaded transcript
 * reaches the start, so an unnamed session reads as it in the header and, through Home, everywhere.
 */
fun State.learningFirstPrompt(): State {
    val shown = session ?: return this
    if (shown.firstPrompt != null || !shown.isUntitled) return this
    return learningFirstPrompt(SessionTitle.firstPrompt(items, reachesStart = earlierHistory == State.EarlierHistory.Exhausted))
}

/** The session on screen (and its family copy) learn [preview] as their first prompt, if unnamed and unknown. */
fun State.learningFirstPrompt(preview: String?): State {
    val shown = session ?: return this
    if (preview == null || shown.firstPrompt != null || !shown.isUntitled) return this
    val learned = shown.learningFirstPrompt(preview)
    val index = familySessions.indexOfFirst { it.id == learned.id }
    return copy(
        session = learned,
        familySessions = if (index < 0) familySessions else familySessions.toMutableList().also { it[index] = it[index].learningFirstPrompt(preview) },
    )
}

/** Whether [id] (an attachment's `UUID` string) was among the attachments the undo restored. */
private fun UndoSnapshot.hasAttachment(id: String): Boolean =
    runCatching { UUID.fromString(id) }.getOrNull()?.let { it in attachmentIDs } == true

fun State.clearUntouchedUndoDraft(): State {
    val currentDraft = parkedComposer?.text ?: draft
    val snapshot = undoSnapshot
    var next = this
    if (snapshot != null && currentDraft == snapshot.restoredDraft) {
        next = next.applySessionComposer { composer ->
            composer.copy(
                text = snapshot.priorDraft,
                location = if (composer.location == snapshot.restoredLocation) snapshot.priorLocation else composer.location,
                attachments = composer.attachments.filterNot { snapshot.hasAttachment(it.id) },
                files = composer.files.filterNot { it.id in snapshot.fileIDs },
            )
        }
        if (next.parkedComposer != null) {
            next = next.withPendingAttachments(next.pendingAttachments.filterNot { snapshot.hasAttachment(it.id) })
                .withPendingFiles(next.pendingFiles.filterNot { it.id in snapshot.fileIDs })
        }
    }
    return next.withUndoSnapshot(null)
}

/** The session moved on: Stop and every run-state read in flight are stale. */
fun State.supersedeRunWork(): State = copy(runWorkGeneration = runWorkGeneration + 1, isInterrupting = false, stopAttempt = null)

fun State.resetLiveProjection(): State = copy(liveText = emptyMap(), projectedMessageIDs = emptySet(), lastProjectedMessageID = null)

val State.showsMoveToBackground: Boolean
    get() {
        val key = movableWorkKey ?: return false
        if (!offersManualMoveToBackground || key in movedWorkKeys) return false
        return foregroundWork?.key == key
    }

// endregion

// region Sending

/** A send the server hasn't taken yet. It never blocks the next one: the outbox delivers them in order. */
val State.isSubmittingPrompt: Boolean get() = promptStates.values.any { it.admission == State.PromptState.Admission.sent }

val State.hasPendingLocalPrompt: Boolean
    get() = isSubmittingPrompt || queuedInputs.any { promptStates[it.id]?.ownsRun == true }

/** A send from here that will still reach the agent without the reader acting. */
val State.hasMovingLocalPrompt: Boolean
    get() = queuedInputs.any {
        promptStates[it.id]?.ownsRun == true && promptStates[it.id]?.admission == State.PromptState.Admission.admitted
    } || (isSubmittingPrompt && failedSends.isEmpty())

/** A send from here the agent takes without waiting for a run to end well. */
val State.hasSteerOnItsWay: Boolean
    get() = queuedInputs.any {
        it.delivery == OpenCodeDelivery.Steer && promptStates[it.id]?.ownsRun == true &&
            promptStates[it.id]?.admission == State.PromptState.Admission.admitted
    } || (failedSends.isEmpty() && promptStates.any { (id, prompt) ->
        prompt.admission == State.PromptState.Admission.sent && (deliveryOverrides[id] ?: sentDeliveries[id]) != OpenCodeDelivery.Queue
    })

val State.supportsFileUploads: Boolean get() = server.persistenceID !in uploadUnsupportedServerIDs.value
val State.isUploadingFiles: Boolean get() = composerAttachments.isUploadingFiles
val State.isWaitingForUploads: Boolean get() = composerAttachments.isWaitingForUploads

/** Something to send: text, inline media, or files. */
val State.hasPromptContent: Boolean get() = draftShape.hasText || composerAttachments.hasContent

/** The composer's text, inline media, and files, saved while elsewhere. */
val State.draftState: DraftState
    get() = DraftState(
        text = draft,
        attachments = pendingAttachments,
        files = pendingFiles,
        location = pendingLocation,
        chips = autocomplete.chips,
        undoSnapshot = composerAttachments.undoSnapshot,
        quotes = pendingQuotes,
    )

fun State.withDraftState(value: DraftState): State =
    withDraft(value.text).copy(
        autocomplete = autocomplete.copy(chips = value.chips),
        composerAttachments = composerAttachments.copy(
            pendingAttachments = value.attachments,
            pendingFiles = value.files,
            pendingLocation = value.location,
            pendingQuotes = value.quotes,
            undoSnapshot = value.undoSnapshot,
        ),
    )

/** Applies [change] to the session's composer (the parked one while a request holds the bar). */
fun State.applySessionComposer(change: (DraftState) -> DraftState): State {
    val composer = change(sessionComposer)
    if (parkedComposer != null) return withParkedComposer(composer)
    return setTyped(composer.text).copy(
        autocomplete = autocomplete.copy(chips = composer.chips),
        composerAttachments = composerAttachments.copy(
            pendingAttachments = composer.attachments,
            pendingFiles = composer.files,
            pendingLocation = composer.location,
            pendingQuotes = composer.quotes,
            undoSnapshot = composer.undoSnapshot,
        ),
    )
}

/** Restores a saved composer. An upload still in flight when it was saved comes back as failed and retryable. */
fun State.restoreDraft(saved: DraftState): State {
    var next = applySessionComposer {
        saved.copy(
            files = saved.files.map { file ->
                if (!file.isUploading) file else file.copy(status = ComposerFileAttachment.Status.Failed(localized(R.string.session_attachment_upload_failed)))
            },
        )
    }
    if (next.parkedComposer == null && next.hasLiveDictation) next = next.clearEditedUndoDraft()
    return next
}

/** A failed upload blocks sending until it is retried or removed. */
val State.canSendPrompt: Boolean
    get() = !isWaitingForUploads && hasPromptContent && pendingFiles.none { it.isFailed }

/** Whether the selected model reads [input] ("pdf", "image") natively. */
fun State.currentModelReads(input: String): Boolean {
    val current = currentModel ?: return false
    return models.model(current)?.inputs?.contains(input) == true
}

// endregion

// region Models and projects

/**
 * Where a session whose folder is gone can move: its project's folder, when that's somewhere else
 * and isn't the home folder.
 */
val State.missingFolderMoveTarget: OpenCodeProject?
    get() {
        val session = session ?: return null
        val project = SessionListContext.project(session, projects) ?: return null
        if (project.directory == session.directory || project.isHomeFolder) return null
        return project
    }

val State.queuedInputRows: List<OpenCodeQueuedInput>
    get() = queuedInputs.map { input -> OpenCodeQueuedInput(input, queuedInputImages[input.id]?.takeIf { it.isNotEmpty() } ?: input.images) }

// endregion

// region Transcript window

val State.isLoadingEarlier: Boolean get() = earlierHistory is State.EarlierHistory.Loading

// endregion
