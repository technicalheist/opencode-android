package ai.opencode.feature.session

import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.composer.modelpicker.ModelPickerState
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.DraftState
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.modelpicker.ModelLevelScale
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.model
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

// Models, projects, the family, shells, and the Session sheet.

/** Models, projects, the family, shells, and the Session sheet. Null: the action passes on. */
internal fun SessionFeature.reduceModelsAndFamily(state: State, action: Action): Reduced<State, Action>? = when (action) {
    // Sent once the list has grown (the view taps as it opens): every action here costs the whole
    // app's reducers, and the growth's first frame must not wait for them.
    Action.ModelListOpened -> reloadModels(state)

    Action.ModelListClosed -> Reduced(state.copy(modelFilter = "", showsAllModels = false))

    Action.ShowAllModelsToggled -> Reduced(state.copy(showsAllModels = !state.showsAllModels))

    // With models already listed, refresh them behind the open picker so providers or config
    // changed on the computer show up.
    Action.ModelButtonTapped ->
        reloadModels(state.copy(activePicker = State.ActivePicker.model, modelFilter = "", showsAllModels = false))

    Action.ModelsChanged -> reloadModels(state)

    is Action.ModelsResponse -> {
        val catalog = action.result.getOrNull()
        if (catalog != null) {
            val serverID = state.server.persistenceID
            val remembered = state.modelDisplayNamesByServer.value[serverID]
            val names = (remembered ?: ModelDisplayNames()).remember(catalog.models)
            if (names.entries.isNotEmpty() && remembered != names) {
                state.modelDisplayNamesByServer.update { it + (serverID to names) }
            }
            var next = state.copy(isLoadingModels = false, modelsLoadError = null)
            // A momentarily empty answer never replaces a list already shown.
            if ((catalog.models.isNotEmpty() || next.models.isEmpty()) && next.models != catalog.models) {
                next = next.copy(models = catalog.models)
            }
            // A pick whose provider or model went away (often one saved before a relaunch) falls
            // back to the session's model rather than fail the send.
            val pending = next.pendingModel
            if (pending != null && next.models.isNotEmpty() && !SessionFeature.catalog(next.models, offers = pending)) {
                next = next.copy(pendingModel = null, currentModel = next.session?.model ?: catalog.defaultModel?.ref)
            }
            if (next.session == null && next.agentSelection.defaultModel != catalog.defaultModel) {
                next = next.copy(agentSelection = next.agentSelection.copy(defaultModel = catalog.defaultModel))
            }
            // Until the catalog lands a draft has no model, and a send leaves the choice to the server.
            if (next.currentModel == null) {
                val model = if (next.session == null) {
                    next.draftDefaultModel
                } else {
                    catalog.defaultModel?.ref
                }
                next = next.copy(currentModel = model)
            }
            Reduced(next)
        } else {
            var next = state.copy(isLoadingModels = false)
            if (next.models.isEmpty()) next = next.copy(modelsLoadError = action.result.exceptionOrNull()?.message)
            Reduced(next)
        }
    }

    is Action.ModelPicked -> {
        val model = action.model
        // Keep the reasoning variant when the new model offers it too.
        val variant = state.currentModel?.variant?.takeIf { it in model.variants }
        val ref = model.ref(variant)
        if (ref != state.currentModel) haptics.play(Haptic.selection)
        Reduced(pick(ref, state).copy(activePicker = null))
    }

    is Action.ModelPickedWithLevel -> Reduced(pickWithLevel(action.model, action.level, action.ticked, state))

    is Action.ModelLevelPicked -> {
        val picker = state.activePicker
        Reduced(pickWithLevel(action.model, action.level, ticked = false, state).copy(activePicker = picker))
    }

    is Action.ModelRowLevelChanged -> Reduced(modelRowLevelChanged(action.model, action.level, state))

    is Action.VariantPicked -> {
        // The same model at another reasoning effort; the picker stays open.
        val current = state.currentModel
        if (current == null || current.variant == action.variant) {
            Reduced(state)
        } else {
            haptics.play(Haptic.selection)
            Reduced(pick(OpenCodeModelRef(current.id, current.providerID, action.variant), state))
        }
    }

    is Action.ToggleFavoriteModel -> {
        haptics.play(Haptic.selection)
        val id = action.model.id
        state.favoriteModelIDs.update { ids -> if (id in ids) ids - id else ids + id }
        Reduced(state)
    }

    Action.ProjectButtonTapped -> Reduced(if (state.canChooseProject) state.openProjectPicker() else state)

    // Only the picker's own choice; a system action opening a draft is quiet.
    is Action.ProjectPicked -> pickProject(action.project, byHand = state.activePicker == State.ActivePicker.project, state)

    is Action.DraftProjectTapped -> pickProject(action.project, byHand = true, state)

    is Action.StarterPromptTapped -> {
        // Typed in, not sent: the person sends it, as with What’s New’s Try It.
        if (!state.canChooseProject || action.text !in state.starterPrompts || state.hasPromptContent) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            Reduced(state.setTyped(action.text).copy(composerFocusRequest = state.composerFocusRequest + 1))
        }
    }

    is Action.SubagentTapped -> {
        val selected = state.familySessions.firstOrNull { it.id == action.sessionID }
        if (selected == null || selected.id == state.session?.id) {
            Reduced(state)
        } else {
            Reduced(state.copy(activePicker = null), Effect.send(Action.Delegate(SessionFeature.Delegate.OpenSubagent(selected))))
        }
    }

    is Action.FamilyPicked -> familyPicked(action.sessionID, state)

    // Subagents starting in a burst each ask. Shells and jobs aren't family: a new stream relists them.
    Action.FamilyRefreshRequested -> refreshFamily(state, coalescing = true)

    is Action.ShellsResponse -> {
        val listed = action.result.getOrNull()
        if (listed == null || state.session?.id != action.sessionID) {
            Reduced(state)
        } else {
            val sessionID = action.sessionID
            val kept = listed.filter { it.sessionID == sessionID && it.id !in state.endedShellIDs }
            val listedIDs = kept.map { it.id }.toSet()
            // A shell can start while the list is in flight; keep live arrivals.
            val shells = kept + state.shells.filter { !it.isListed && it.id !in listedIDs && it.id !in state.restoredShellIDs }
            Reduced(state.copy(shells = shells, restoredShellIDs = emptySet()))
        }
    }

    is Action.ShellStarted -> {
        val shell = action.shell
        if (shell.sessionID != state.session?.id || state.shells.any { it.id == shell.id }) Reduced(state)
        else Reduced(state.copy(shells = state.shells + shell))
    }

    is Action.ShellEnded ->
        Reduced(state.copy(shells = state.shells.filter { it.id != action.shellID }, endedShellIDs = state.endedShellIDs + action.shellID))

    Action.SessionInfoTapped -> {
        val (next, hasSheet) = state.ensureSessionSheet()
        if (!hasSheet) {
            Reduced(next)
        } else {
            haptics.play(Haptic.tap)
            Reduced(next.copy(activePicker = State.ActivePicker.session))
        }
    }

    Action.ReportProblemTapped -> Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.ReportProblem)))

    Action.SessionMediaTapped -> {
        if (state.session == null) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            Reduced(state.copy(sessionMediaFilter = SessionMedia.Filter.media))
        }
    }

    is Action.SessionMediaFilterPicked -> {
        if (state.sessionMediaFilter == null || state.sessionMediaFilter == action.filter) {
            Reduced(state)
        } else {
            haptics.play(Haptic.selection)
            Reduced(state.copy(sessionMediaFilter = action.filter, sessionMediaEarlier = null, sessionMediaNothingNew = false))
        }
    }

    is Action.SessionMediaLayoutPicked -> {
        if (state.sessionMediaFilter != SessionMedia.Filter.media || state.sessionMediaLayout == action.layout) {
            Reduced(state)
        } else {
            haptics.play(Haptic.selection)
            Reduced(state.copy(sessionMediaLayout = action.layout))
        }
    }

    Action.SessionMediaDismissed -> Reduced(state.copy(sessionMediaFilter = null, sessionMediaEarlier = null, sessionMediaNothingNew = false))

    is Action.SessionMenu -> {
        val (next, hasSheet) = state.ensureSessionSheet()
        if (!hasSheet) Reduced(next) else Reduced(next, Effect.send(Action.SessionSheet(action.action)))
    }

    is Action.SessionSheet -> {
        val delegate = (action.action as? SessionSheetFeature.Action.Delegate)?.delegate
        if (delegate == null) Reduced(state) else sessionSheetDelegate(delegate, state)
    }

    is Action.Delegate -> Reduced(state)

    Action.AgentsRequested, Action.AgentsChanged, is Action.AgentsResponse, is Action.AgentPicked, is Action.AgentApplied -> reduceAgents(state, action)

    else -> null
}

/** Picks [model] at [explicitLevel]; remembers the level and the model as recent. */
private fun SessionFeature.pickWithLevel(model: OpenCodeModel, explicitLevel: String?, ticked: Boolean, state: State): State {
    val scale = ModelLevelScale(model.variants)
    val normalized = explicitLevel?.takeIf { it != "default" && it in scale.levels }
    val ref = model.ref(normalized)
    val changes = state.currentModel?.let(model::matches) != true || state.currentLevel != normalized
    if (changes && !ticked) haptics.play(Haptic.selection)
    var next = pick(ref, state)
    if (scale.isAdjustable) {
        val storedVariant = normalized ?: "default"
        if (next.modelVariants.value[model.id] != storedVariant) next.modelVariants.update { it + (model.id to storedVariant) }
    }
    val updatedRecents = (listOf(model.id) + next.recentModelIDs.value.filter { it != model.id }).take(10)
    if (next.recentModelIDs.value != updatedRecents) next.recentModelIDs.set(updatedRecents)
    next = next.copy(activePicker = null)
    return next
}

/** Scrubs or adjusts a model's remembered reasoning level in place. */
private fun SessionFeature.modelRowLevelChanged(model: OpenCodeModel, level: String?, state: State): State {
    val scale = ModelLevelScale(model.variants)
    if (!scale.isAdjustable) return state
    val normalized = level?.takeIf { it != "default" && it in scale.levels }
    val storedVariant = normalized ?: "default"
    val isCurrent = state.currentModelInfo?.id == model.id
    val previousLevel = if (isCurrent) state.currentLevel else state.rememberedLevel(model)
    if (normalized == previousLevel && state.modelVariants.value[model.id] == storedVariant) return state
    if (normalized != previousLevel) haptics.play(Haptic.selection)
    if (state.modelVariants.value[model.id] != storedVariant) state.modelVariants.update { it + (model.id to storedVariant) }
    return if (isCurrent) pick(model.ref(normalized), state) else state
}

/** The Session sheet's delegate, applied to the session it acts on. */
private fun SessionFeature.sessionSheetDelegate(delegate: SessionSheetFeature.Delegate, state: State): Reduced<State, Action> {
    val sessionID = state.sessionSheet?.sessionID ?: return Reduced(state)
    return when (delegate) {
        is SessionSheetFeature.Delegate.Renamed ->
            Reduced(state, Effect.send(Action.FamilySessionRenamed(sessionID, delegate.title)))

        is SessionSheetFeature.Delegate.Compacted -> {
            var next = state
            delegate.model?.let { next = next.modelApplied(it, sessionID) }
            if (next.activePicker == State.ActivePicker.session) next = next.copy(activePicker = null)
            // The queued compaction shows as a pending row, then the transcript's compaction rule
            // as it runs and settles.
            if (next.session?.id == sessionID) Reduced(next, Effect.send(Action.InputQueueChanged(deliveredID = null))) else Reduced(next)
        }

        is SessionSheetFeature.Delegate.Forked -> {
            val next = if (state.activePicker == State.ActivePicker.session) state.copy(activePicker = null) else state
            Reduced(next, Effect.send(Action.Delegate(SessionFeature.Delegate.OpenSession(delegate.session))))
        }

        SessionSheetFeature.Delegate.TogglePin -> Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.TogglePin(sessionID))))
        SessionSheetFeature.Delegate.ToggleRead -> Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.ToggleRead(sessionID))))
        SessionSheetFeature.Delegate.ToggleMute -> Reduced(state, Effect.send(Action.Delegate(SessionFeature.Delegate.ToggleMute(sessionID))))

        is SessionSheetFeature.Delegate.AlwaysNotifyChanged -> {
            var next = state
            val index = next.familySessions.indexOfFirst { it.id == sessionID }
            if (index >= 0) {
                next = next.copy(
                    familySessions = next.familySessions.toMutableList().also { it[index] = it[index].copy(alwaysNotify = delegate.isOn) },
                )
            }
            val session = next.session
            if (session?.id == sessionID) next = next.copy(session = session.copy(alwaysNotify = delegate.isOn))
            Reduced(next)
        }

        SessionSheetFeature.Delegate.DeleteSession -> {
            val next = if (state.activePicker == State.ActivePicker.session) state.copy(activePicker = null) else state
            Reduced(next, Effect.send(Action.Delegate(SessionFeature.Delegate.DeleteSession(sessionID))))
        }
    }
}

/**
 * Swaps the family member on screen in place. Sends still on their way carry on in the outbox, and
 * come back with their session.
 */
private fun SessionFeature.familyPicked(id: String, state: State): Reduced<State, Action> {
    val current = state.session
    val selected = state.familySessions.firstOrNull { it.id == id }
    if (current == null || selected == null || selected.id == current.id) return Reduced(state.copy(activePicker = null))
    // Read the target first: caching the outgoing transcript may evict it.
    val selectedItems = state.familyTranscriptCache[selected.id]
    // What one session's reader saw says nothing about another's.
    var next = state.copy(
        newMessages = null,
        newMessagesBaseline = null,
        newMessagesBaselineTimestamp = null,
        newMessagesUnread = false,
        newMessagesEarlierBaseline = null,
        newMessagesWatched = emptySet(),
        newMessagesFitThrough = null,
    )
    next = cacheFamilyTranscript(next.itemsWithoutFailedSends, current.id, next)
    // A recording follows the screen, as across Home's switches: what was typed beside it stays in
    // this session's composer.
    val carried = next.carriedDictation
    next = next.settingFamilyDraft(current.id, next.sessionComposer)
    next = next.withSendAfterUploads(null)
    next = forgetSharedSend(next)
    // The sheet acts on one session: its in-flight work must not land on the next one. The server
    // still finishes it and announces the result. A call that already ended doesn't follow the
    // screen to another session.
    val callEnded = next.voice?.phase?.isTerminal == true
    val endedCall: Effect<Action> = if (callEnded) Effect.cancel(CancelID.VoiceExit) else Effect.none()
    if (callEnded) next = next.copy(voice = null)
    next = next.copy(sessionSheet = null, session = selected)
    val idle = selected.idleMilliseconds
    if (next.readsOnView && idle != null) {
        next = next.copy(session = next.session?.copy(viewedMilliseconds = maxOf(selected.viewedMilliseconds ?: 0, idle)))
    }
    next = next.copy(items = selectedItems ?: emptyList()).syncShellToolStatuses()
    next = next.copy(isLoading = selectedItems == null)
    val leavingPick = next.pendingModel
    val familyPending = next.familyPendingModels.toMutableMap()
    if (leavingPick != null) familyPending[current.id] = leavingPick else familyPending.remove(current.id)
    val returningPick = familyPending.remove(selected.id)
    next = next.copy(
        familyPendingModels = familyPending,
        pendingModel = returningPick,
        currentModel = returningPick ?: selected.model,
        contextUsage = null,
        contextNeedsRead = false,
        isExternallyActive = selected.id in next.activeFamilySessionIDs,
        isAwaitingReply = false,
        isUndoingLastMessage = false,
    )
    next = next.supersedeRunWork()
    next = next.copy(autocomplete = ComposerAutocompleteState())
    next = next.setSessionComposer(next.familyDrafts[selected.id] ?: DraftState())
    // Answering, the bar and its recording stay as they are.
    if (carried != null && !next.isAnsweringRequest) next = next.adoptDictation(carried)
    next = next.copy(
        queuedInputs = emptyList(),
        pendingPromptPositions = emptyMap(),
        echoedPromptIDs = emptySet(),
        pendingNoticeIDs = emptySet(),
        queuedInputImages = emptyMap(),
        cancelledInputIDs = emptySet(),
        sentDeliveries = emptyMap(),
        deliveryOverrides = emptyMap(),
        failedSends = emptyList(),
        movingFailedSendID = null,
        promptStates = emptyMap(),
    )
    next = restorePersistedComposer(next)
    next = restoreFailedSends(next)
    val outbox = restoreOutbox(next)
    next = outbox.state.copy(
        shells = emptyList(),
        restoredShellIDs = emptySet(),
        endedShellIDs = emptySet(),
        shellOutput = null,
    )
    // The parent's page never stays over a subagent (or back).
    val closesPage = closeBrowser(next)
    next = closesPage.state.copy(
        movableWorkKey = null,
        movedWorkKeys = emptySet(),
        autoBackgroundClaims = emptyMap(),
        loadError = null,
        activePicker = null,
        visibleLimit = SessionFeature.baseVisibleLimit,
        browsingWindowStartID = null,
        earlierHistory = State.EarlierHistory.UnknownEdge,
        earlierHistoryInsertionID = null,
        earlierHistoryError = null,
        earlierHistoryRetriesOnReconnect = false,
        historyGeneration = next.historyGeneration + 1,
        earlierHistoryGeneration = next.earlierHistoryGeneration + 1,
        refreshFailed = false,
        initialTranscriptIsFresh = false,
    )
    next = next.resetLiveProjection().copy(lastSentItemID = null)
    // Its cached rows are the ones the reader had on screen there (left, or popped back from):
    // what the read brings past them is new.
    if (selectedItems != null) next = next.noteOpening(seen = null)
    val abandon = abandonRefresh(next)
    val requests = startPendingWork(abandon.state, RequestDockFeature.State.LoadAttempt.Initial)
    val shells = refreshShells(requests.state.copy(jobs = emptyMap(), stoppingIDs = emptySet()))
    val jobs = refreshJobs(shells.state)
    val watchdog = watchdog(jobs.state)
    return Reduced(
        watchdog.state,
        Effect.merge(
            Effect.cancel(CancelID.EarlierHistory),
            Effect.cancel(CancelID.PromptEcho),
            Effect.cancel(CancelID.InboxFetch),
            Effect.cancel(CancelID.AutocompleteCatalog),
            Effect.cancel(CancelID.AutocompleteCommands),
            Effect.cancel(CancelID.AutocompleteFiles),
            Effect.cancel(CancelID.AutocompleteSessions),
            Effect.cancel(CancelID.AutocompleteAutoRun),
            Effect.cancel(CancelID.AutocompleteCommand),
            Effect.cancel(CancelID.AutocompleteCommandWait),
            Effect.cancel(CancelID.Revert),
            Effect.cancel(CancelID.WindowReset),
            // Clearing sessionSheet above cancels its auto-rename failure through ifLet teardown.
            endedCall,
            closesPage.effect,
            abandon.effect,
            Effect.send(Action.RefreshRequested(RefreshReason.Open)),
            requests.effect,
            shells.effect,
            jobs.effect,
            watchdog.effect,
            outbox.effect,
        ),
    )
}

/**
 * Moves the draft to [project]. A choice made by hand that changes it plays `.selection`; the
 * project already chosen plays nothing. Moving to another folder asks for that folder's agents.
 */
private fun SessionFeature.pickProject(project: OpenCodeProject, byHand: Boolean, state: State): Reduced<State, Action> {
    // Once the first send has left, it goes where it was sent.
    if (!state.canChooseProject) {
        return Reduced(if (state.activePicker == State.ActivePicker.project) state.copy(activePicker = null) else state)
    }
    val changes = state.draftProject?.let { it.id != project.id && it.directory != project.directory } ?: true
    if (byHand && changes) haptics.play(Haptic.selection)
    val movesFolder = state.draftProject?.directory != project.directory
    var next = state.copy(draftProject = project, activePicker = null, voice = state.voice?.moveDraft(project.directory))
    // Each folder has its own agents: the last folder's list isn't this one's, so it's dropped and
    // asked for again from the new folder.
    if (!movesFolder) return Reduced(next)
    if (next.primaryAgents.isNotEmpty()) next = next.withPrimaryAgents(emptyList())
    return Reduced(next, Effect.send(Action.AgentsRequested))
}

/**
 * Family switching retains only a small working set. Eight sessions covers the largest lab family
 * with room for the root while bounding duplicated transcript rows; the selected session itself
 * remains fully expanded.
 */
private fun cacheFamilyTranscript(items: List<OpenCodeTranscriptItem>, sessionID: String, state: State): State {
    val cache = LinkedHashMap(state.familyTranscriptCache)
    cache[sessionID] = items.takeLast(SessionFeature.initialFetchLimit)
    while (cache.size > 8) {
        val protected = setOfNotNull(state.session?.id, state.familyRootID)
        val evicted = cache.keys.firstOrNull { it !in protected } ?: break
        cache.remove(evicted)
    }
    return state.copy(familyTranscriptCache = cache)
}

/**
 * Loads the model catalog once per session view — needed up front so the composer can show the
 * model's display name instead of its raw ID.
 */
internal fun SessionFeature.loadModelsIfNeeded(state: State): Reduced<State, Action> {
    if (state.models.isNotEmpty() || state.isLoadingModels) return Reduced(state)
    return reloadModels(state)
}

/**
 * Starts or restarts a model catalog read for the picker, cancelling any earlier in-flight read so
 * a retry while loading is never dropped.
 */
internal fun SessionFeature.reloadModels(state: State): Reduced<State, Action> {
    var next = state
    if (next.models.isEmpty()) next = next.copy(isLoadingModels = true)
    next = next.copy(modelsLoadError = null)
    return Reduced(next, fetchModels(next))
}

private fun SessionFeature.fetchModels(state: State): Effect<Action> {
    val server = state.server
    val directory = state.session?.directory ?: state.draftProject?.directory
    val client = openCodeClient
    return Effect.run<Action> { send ->
        try {
            val catalog = client.modelCatalog(server, directory)
            if (!currentCoroutineContext().isActive) return@run
            send(Action.ModelsResponse(Result.success(catalog)))
        } catch (error: OpenCodeClientError) {
            if (!currentCoroutineContext().isActive) return@run
            send(Action.ModelsResponse(Result.failure(error)))
        }
    }.cancellable(CancelID.Models, cancelInFlight = true)
}

/** The model picker's inputs as `:feature:composer`'s pickers read them. */
val State.modelPicker: ModelPickerState
    get() = ModelPickerState(
        models = models,
        currentModel = currentModel,
        favoriteModelIDs = favoriteModelIDs.value,
        recentModelIDs = recentModelIDs.value,
        modelVariants = modelVariants.value,
        modelFilter = modelFilter,
        isLoadingModels = isLoadingModels,
        modelsLoadError = modelsLoadError,
        modelVisibility = modelVisibility,
        showsAllModels = showsAllModels,
        modelDisplayNames = modelDisplayNamesByServer.value[server.persistenceID] ?: ModelDisplayNames(),
    )

/** Resolve a label without borrowing another provider's capabilities. */
val State.currentModelName: String get() = modelPicker.currentModelName

/** Only the live catalogue can supply availability and capabilities. */
val State.currentModelInfo: OpenCodeModel? get() = currentModel?.let { models.model(it) }

/** "Claude Haiku 5.5 · High", the model and its reasoning variant, if any. */
val State.currentModelLabel: String get() = modelPicker.currentModelLabel

/** Compact name for the composer chip (`"Opus 5.5"` for `"Claude Opus 5.5"`). */
val State.currentModelShortName: String get() = modelPicker.currentModelShortName

/** The current model's levels, when it has any to choose between. */
val State.currentLevelScale: ModelLevelScale? get() = modelPicker.currentLevelScale

/** The current level; null is the model's default. */
val State.currentLevel: String? get() = modelPicker.currentLevel

/**
 * The level shown for [model] on its row in the list (and picked when tapping its name): a level
 * parsed from the search query when searching, else the active level for the current model, else
 * the remembered variant in `modelVariants`, else null (`"Default"`).
 */
fun State.rememberedLevel(model: OpenCodeModel): String? = modelPicker.rememberedLevel(model)

/** Whether the catalogue still lists `ref`'s model. */
internal fun SessionFeature.Companion.catalog(models: List<OpenCodeModel>, offers: OpenCodeModelRef): Boolean =
    models.any { it.ref.sameModel(offers) }
