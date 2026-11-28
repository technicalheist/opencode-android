package ai.opencode.feature.session

import ai.opencode.core.ui.inContainer
import androidx.activity.compose.BackHandler
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.feature.composer.modelpicker.ComposerFocusRestore
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.composer.modelpicker.ModelListDetent
import ai.opencode.feature.composer.modelpicker.ModelPickerEvent
import ai.opencode.feature.composer.modelpicker.ModelPickerState
import ai.opencode.feature.composer.modelpicker.OnePickerController
import ai.opencode.feature.composer.modelpicker.OnePickerGlassBackdrop
import ai.opencode.feature.composer.modelpicker.OnePickerSheetHost
import ai.opencode.feature.composer.modelpicker.OnePickerSimulation
import ai.opencode.feature.composer.modelpicker.OnePickerSurface
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope

// The session's glue for:feature:composer's model pickers. The pickers read a `ModelPickerState`
// built from `SessionFeature.State` and send `ModelPickerEvent`s, which map to the `SessionFeature`
// actions of the same names. The composer's `OnePickerChip` grows One Picker out of the composer
// ([SessionModelPanels], [SessionModelPickerOverlays]); while the composer is covered it opens in
// `ModelPickerDetents`' sheet ([SessionModelPicker]). The plain picker and its Experimental switch
// are gone.

internal fun SessionFeature.State.modelPickerState(
    favorites: Set<String> = favoriteModelIDs.value,
    recents: List<String> = recentModelIDs.value,
    variants: Map<String, String> = modelVariants.value,
    displayNames: Map<String, ModelDisplayNames> = modelDisplayNamesByServer.value,
): ModelPickerState = ModelPickerState(
    models = models,
    currentModel = currentModel,
    favoriteModelIDs = favorites,
    recentModelIDs = recents,
    modelVariants = variants,
    modelFilter = modelFilter,
    isLoadingModels = isLoadingModels,
    modelsLoadError = modelsLoadError,
    modelVisibility = modelVisibility,
    showsAllModels = showsAllModels,
    modelDisplayNames = displayNames[server.persistenceID] ?: ModelDisplayNames(),
)

/** The `SessionFeature` action a picker event stands for; null when none does. */
internal fun modelPickerAction(event: ModelPickerEvent): SessionFeature.Action? = when (event) {
    is ModelPickerEvent.ModelPicked -> SessionFeature.Action.ModelPicked(event.model)
    is ModelPickerEvent.ModelPickedWithLevel -> SessionFeature.Action.ModelPickedWithLevel(event.model, event.level, event.ticked)
    is ModelPickerEvent.ModelLevelPicked -> SessionFeature.Action.ModelLevelPicked(event.model, event.level)
    is ModelPickerEvent.ModelRowLevelChanged -> SessionFeature.Action.ModelRowLevelChanged(event.model, event.level)
    is ModelPickerEvent.VariantPicked -> SessionFeature.Action.VariantPicked(event.variant)
    is ModelPickerEvent.ToggleFavoriteModel -> SessionFeature.Action.ToggleFavoriteModel(event.model)
    is ModelPickerEvent.FilterChanged -> SessionFeature.Action.Binding(BindingAction.ModelFilter(event.text))
    // Never sent.
    ModelPickerEvent.ModelSearchSubmitted -> null
    ModelPickerEvent.ShowAllModelsToggled -> SessionFeature.Action.ShowAllModelsToggled
    ModelPickerEvent.ModelListOpened -> SessionFeature.Action.ModelListOpened
    ModelPickerEvent.ModelListClosed -> SessionFeature.Action.ModelListClosed
    ModelPickerEvent.Dismiss -> SessionFeature.Action.Binding(BindingAction.ActivePicker(null))
}

/** What the picker's state is built from, observed narrowly so a streaming reply doesn't redraw it. */
private data class PickerInputs(
    val models: List<OpenCodeModel>,
    val currentModel: OpenCodeModelRef?,
    val modelFilter: String,
    val isLoadingModels: Boolean,
    val modelsLoadError: String?,
    val showsAllModels: Boolean,
    val serverID: String,
)

/**
 * The picker's state from the session, recomputed when the session's picker inputs or the
 * shared favorites, recents, remembered levels or model visibility change.
 */
@Composable
internal fun rememberModelPickerState(store: StoreOf<SessionFeature.State, SessionFeature.Action>): ModelPickerState {
    val inputs by store.observe {
        PickerInputs(it.models, it.currentModel, it.modelFilter, it.isLoadingModels, it.modelsLoadError, it.showsAllModels, it.server.persistenceID)
    }
    val favorites by remember(store) { store.state.favoriteModelIDs.flow }.collectAsState(store.state.favoriteModelIDs.value)
    val recents by remember(store) { store.state.recentModelIDs.flow }.collectAsState(store.state.recentModelIDs.value)
    val variants by remember(store) { store.state.modelVariants.flow }.collectAsState(store.state.modelVariants.value)
    val visibility by remember(store) { store.state.modelVisibilityByServer.flow }.collectAsState(store.state.modelVisibilityByServer.value)
    val hideModels by remember(store) { store.state.hideModelsExperiment.flow }.collectAsState(store.state.hideModelsExperiment.value)
    val displayNames by remember(store) { store.state.modelDisplayNamesByServer.flow }.collectAsState(store.state.modelDisplayNamesByServer.value)
    return remember(inputs, favorites, recents, variants, visibility, hideModels, displayNames) {
        store.state.modelPickerState(favorites, recents, variants, displayNames)
    }
}

/**
 * The model picker, shown by `SessionView` while `activePicker == model`: `OnePickerSheetHost`.
 * Dismissing it sets `activePicker` to null; however it goes, its search is cleared
 * (`modelListClosed`).
 */
@Composable
fun SessionModelPicker(store: StoreOf<SessionFeature.State, SessionFeature.Action>) {
    val state = rememberModelPickerState(store)
    val onEvent: (ModelPickerEvent) -> Unit = { event -> modelPickerAction(event)?.let(store::send) }
    // With the composer on screen the route opens One Picker out of it
    // ([SessionModelPickerOverlays]), so no sheet shows meanwhile.
    val panels = LocalSessionComposerHost.current?.modelPanels
    if (panels?.canPresentOnePickerMorph != true) {
        OnePickerSheetHost(
            state = state,
            onEvent = onEvent,
            onDismiss = { store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null))) },
            onPickModel = { model, level -> store.send(SessionFeature.Action.ModelPickedWithLevel(model, level)) },
        )
    }
}

/**
 * One Picker's panel grown out of the composer. A plain object; [SessionModelPickerOverlays]
 * creates it and hands it to the composer through [SessionComposerHost.modelPanels].
 */
@Stable
internal class SessionModelPanels(
    scope: CoroutineScope,
    private val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    private val host: SessionComposerHost,
) {
    private val focusRestore = ComposerFocusRestore(scope)

    // The screen around the panel, kept current by [SessionModelPickerOverlays].
    var reducedMotion = false
    var density = 1f
    var talkBackOn = false
    var sceneActive = true

    /** One Picker's controller. */
    val onePicker = OnePickerController()

    /** One Picker's space: the session's root box, its origin in root px. */
    var spaceOrigin = Offset.Zero
    /** How high it may grow. */
    var onePickerCeiling = 0.0

    /** Where the focused composer sat as the session sheet opened (One Picker's space). */
    var sessionSheetComposer: Rect? = null

    /**
     * The model chip put the field's keyboard away for the picker sheet, so the sheet's dismissal
     * hands it back ([SessionSheets]).
     */
    var sheetRestoresFocus = false

    /** The composer is on screen to morph from. */
    val canPresentOnePickerMorph: Boolean get() = !store.state.isAnsweringRequest

    /** The composer fades as One Picker's glass grows out of it. */
    val composerOpacity: Float
        get() = if (onePicker.isShown) onePicker.motion.pose.composerOpacity.toFloat().coerceIn(0f, 1f) else 1f

    /** One Picker stands over what sits above the composer. */
    val covers: Boolean get() = onePicker.isOpen

    /** The composer stays expanded while One Picker is drawn. */
    val keepsComposerExpanded: Boolean get() = onePicker.isShown

    /** The draft's project list stays folded while One Picker owes the keyboard back to the field. */
    val keepsDraftProjectsFolded: Boolean get() = onePicker.restoresComposerFocus

    /** One Picker owns downward drags. */
    val swipeYields: Boolean get() = onePicker.isShown

    /** The composer is behind One Picker: hidden from TalkBack. */
    val hidesComposer: Boolean get() = onePicker.isOpen

    /**
     * The message field takes the keyboard back from the model list, unless something else took
     * the composer meanwhile (another screen, a panel or menu, a request, dictation, a call).
     */
    fun restoreFocusAfterModelList(screen: SessionFeature.State.TaskIdentity): Boolean {
        val state = store.state
        if (state.taskIdentity != screen || !sceneActive || onePicker.isOpen ||
            host.isAttachmentMenuOpen || host.floatingCallCovers || state.isAnsweringRequest ||
            state.dictationHold != null || state.isDictating || state.isPreparingDictation
        ) return false
        host.focusComposer()
        return true
    }

    /** The composer's frame in One Picker's space (dp); zero while it isn't on screen. */
    fun composerInSpace(): Rect {
        val composer = host.attachmentAnchors.composer
        if (composer.width <= 0f) return Rect.Zero
        return Rect(
            (composer.left - spaceOrigin.x) / density, (composer.top - spaceOrigin.y) / density,
            (composer.right - spaceOrigin.x) / density, (composer.bottom - spaceOrigin.y) / density,
        )
    }

    /**
     * One Picker grows out of the composer, taking the keyboard's place. [keyboardComposer] is the
     * composer with its keyboard up, when One Picker opens with the keyboard already down but owes
     * it back (the session sheet). The Android transcript has no such hold, so the keyboard's
     * leaving moves it as any dismissal does.
     */
    fun openOnePicker(keyboardComposer: Rect? = null) {
        if (onePicker.isOpen) return
        closeOthersForModelPanel()
        val viewport = onePicker.latestViewportInSpace
        val fallbackSource = Rect(
            Offset(viewport.left + 20, viewport.bottom - 96),
            Size(max(200f, viewport.width - 40), 56f),
        )
        val composer = composerInSpace()
        val hadKeyboard = host.messageFieldFocused
        onePicker.open(
            sourceRect = if (composer.width > 0f) composer else fallbackSource,
            viewport = viewport,
            ceiling = onePickerCeiling,
            recentModelIDs = store.state.recentModelIDs.value,
            hadKeyboard = hadKeyboard,
            restoresFocusTo = if (hadKeyboard) null else keyboardComposer,
            initialKeyboardTop = null,
            initialDetent = if (talkBackOn) ModelListDetent.Large else ModelListDetent.Medium,
            reduceMotion = reducedMotion,
            onLaunch = { if (hadKeyboard) host.dismissComposerKeyboard() },
            onOpened = { store.send(SessionFeature.Action.ModelListOpened) },
        )
    }

    fun pickOnePickerModel(model: OpenCodeModel, level: String?) {
        store.send(SessionFeature.Action.ModelPickedWithLevel(model, level))
        closeOnePicker()
    }

    /**
     * One Picker folds back onto the composer. If the message field had the keyboard as it opened,
     * it folds onto the composer as it sat with the keyboard up and the field takes the keyboard
     * back (straight from Search Models when that has it); otherwise it folds onto the resting
     * composer, and a keyboard Search Models raised goes down with it.
     */
    fun closeOnePicker() {
        val restores = onePicker.restoresComposerFocus
        val searchHad = onePicker.searchFocused
        onePicker.restoresComposerFocus = false
        val screen = store.state.taskIdentity
        // The rows already built for the open list, not a fresh catalogue.
        val freeze = { onePicker.catalogCache.shownSections ?: emptyList() }
        val composer = composerInSpace()
        val bottomDest = when {
            onePicker.restingBottomComposer.width > 0f -> onePicker.restingBottomComposer
            composer.width > 0f -> composer
            else -> onePicker.openedFromComposer
        }
        val keyboardUpDest = if (onePicker.openedFromComposer.width > 0f) onePicker.openedFromComposer else bottomDest
        val alreadyClosing = onePicker.simulation.phase == OnePickerSimulation.Phase.Closing

        if (restores && searchHad) {
            // Search already holds the keyboard: fold onto the keyboard-up composer and hand the
            // keyboard straight from Search to the message field, so it never drops and rises.
            onePicker.handedKeyboardToComposer = true
            if (alreadyClosing) {
                onePicker.retargetCloseDestination(keyboardUpDest, keepsKeyboard = true, matchingKeyboardSpring = false)
            } else {
                onePicker.close(keyboardUpDest, keepsKeyboard = true, matchingKeyboardSpring = false, freezeSections = freeze)
            }
            if (!restoreFocusAfterModelList(screen)) {
                onePicker.handedKeyboardToComposer = false
                onePicker.retargetCloseDestination(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = true)
                host.dismissComposerKeyboard()
            } else {
                focusRestore.schedule(50.milliseconds) { restoreFocusAfterModelList(screen) }
            }
            return
        }
        if (restores) {
            // The message field had the keyboard as One Picker opened (it's down now): start the
            // fold onto the keyboard-up composer, then raise the field's keyboard.
            if (alreadyClosing) {
                onePicker.retargetCloseDestination(keyboardUpDest, keepsKeyboard = false, matchingKeyboardSpring = true)
            } else {
                onePicker.close(keyboardUpDest, keepsKeyboard = false, matchingKeyboardSpring = true, freezeSections = freeze)
            }
            if (!restoreFocusAfterModelList(screen)) {
                onePicker.retargetCloseDestination(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = false)
            } else {
                focusRestore.schedule(50.milliseconds) { restoreFocusAfterModelList(screen) }
            }
            return
        }
        if (searchHad) {
            // The message field didn't have the keyboard: Search's keyboard retreats in step as
            // One Picker folds onto the resting composer.
            if (alreadyClosing) {
                onePicker.retargetCloseDestination(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = true)
            } else {
                onePicker.close(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = true, freezeSections = freeze)
            }
            host.dismissComposerKeyboard()
            return
        }
        if (!alreadyClosing) {
            onePicker.close(bottomDest, keepsKeyboard = false, matchingKeyboardSpring = false, freezeSections = freeze)
        }
    }

    /** The attachment menu gives way to a model panel. */
    fun closeOthersForModelPanel() {
        focusRestore.cancel()
        if (host.isAttachmentMenuOpen) host.closeAttachmentMenu()
    }

    /** A session switch, a call, a request, or dictation taking the composer closes what grew out of it, at once. */
    fun closeModelPanels() {
        focusRestore.cancel()
        onePicker.reset()
    }
}

/**
 * One Picker over the session: the picker grown out of the composer. Also creates the screen's
 * [SessionModelPanels] and hands it to the composer.
 */
@Composable
internal fun SessionModelPickerOverlays(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    headerHeight: Float,
) {
    val host = LocalSessionComposerHost.current ?: return
    val scope = rememberCoroutineScope()
    val panels = remember(store) { SessionModelPanels(scope, store, host) }
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(AccessibilityManager::class.java) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val sceneActive = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val statusTop = WindowInsets.statusBars.inContainer().getTop(density)
    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    // In One Picker's space (the session's box, under the status bar).
    val onePickerCeiling = statusTop / density.density + headerHeight + 8.0

    SideEffect {
        panels.spaceOrigin = screenOrigin
        panels.onePickerCeiling = onePickerCeiling
        panels.reducedMotion = reduceMotion
        panels.density = density.density
        panels.talkBackOn = accessibility?.isTouchExplorationEnabled == true
        panels.sceneActive = sceneActive
        host.modelPanels = panels
    }
    DisposableEffect(panels) { onDispose { if (host.modelPanels === panels) host.modelPanels = null } }
    // Leaving the foreground settles a drag the system took mid-way.
    LaunchedEffect(sceneActive) {
        if (!sceneActive) panels.onePicker.settleInteraction()
    }
    // One Picker opening out of the composer taps, like the attachment menu.
    HapticEffect(Haptic.tap, panels.onePicker.isOpen) { _, open -> open }
    // Folded away (settled, or reset at once), its search is cleared.
    val onePickerShown = panels.onePicker.isShown
    var onePickerWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(onePickerShown) {
        if (onePickerWasShown && !onePickerShown) store.send(SessionFeature.Action.ModelListClosed)
        onePickerWasShown = onePickerShown
    }
    // With the composer on screen, the model route (the header, the session sheet's Model row)
    // opens One Picker out of it instead of a sheet. Remembers the focused composer as the session
    // sheet opens, so One Picker's close hands the keyboard back to it.
    val activePicker by store.observe { it.activePicker }
    var previousPicker by remember { mutableStateOf(activePicker) }
    LaunchedEffect(activePicker) {
        val previous = previousPicker
        previousPicker = activePicker
        if (previous == activePicker) return@LaunchedEffect
        if (activePicker == SessionFeature.State.ActivePicker.session) {
            val composer = panels.composerInSpace()
            panels.sessionSheetComposer = if (host.messageFieldFocused && composer.width > 0f) composer else null
        }
        if (activePicker == SessionFeature.State.ActivePicker.model && panels.canPresentOnePickerMorph) {
            store.send(SessionFeature.Action.Binding(BindingAction.ActivePicker(null)))
            panels.openOnePicker(
                keyboardComposer = if (previous == SessionFeature.State.ActivePicker.session) panels.sessionSheetComposer else null,
            )
        }
        if (activePicker != SessionFeature.State.ActivePicker.session) panels.sessionSheetComposer = null
    }
    // Another screen in place closes what grew out of the composer.
    val taskIdentity by store.observe { it.taskIdentity }
    var seenIdentity by remember { mutableStateOf(taskIdentity) }
    LaunchedEffect(taskIdentity) {
        if (seenIdentity == taskIdentity) return@LaunchedEffect
        seenIdentity = taskIdentity
        panels.closeModelPanels()
    }
    // Android-ify: system back folds an open One Picker away first, as it would close any sheet.
    BackHandler(enabled = panels.onePicker.isOpen) { panels.closeOnePicker() }

    Box(Modifier.fillMaxSize().onGloballyPositioned { screenOrigin = it.boundsInRoot().topLeft })
    val state = rememberModelPickerState(store)
    OnePickerSurface(
        state = state,
        controller = panels.onePicker,
        ceiling = onePickerCeiling,
        liveComposer = panels.composerInSpace(),
        onEvent = { event -> modelPickerAction(event)?.let(store::send) },
        onHideKeyboard = host::dismissComposerKeyboard,
        onDismiss = panels::closeOnePicker,
        onPickModelAndClose = panels::pickOnePickerModel,
    )
}

/**
 * One Picker's surface, mounted over the transcript and under the composer, so the composer's
 * controls and the picker's rows draw over it.
 */
@Composable
internal fun SessionOnePickerBackdrop(host: SessionComposerHost, modifier: Modifier = Modifier) {
    val panels = host.modelPanels ?: return
    if (!panels.canPresentOnePickerMorph) return
    OnePickerGlassBackdrop(panels.onePicker, modifier)
}

/**
 * The composer under a model panel: it fades as One Picker's glass grows out of it and is hidden
 * from TalkBack while a panel stands over it.
 */
internal fun Modifier.modelPanelsComposer(host: SessionComposerHost): Modifier = composed {
    val hides = host.modelPanels?.hidesComposer == true
    graphicsLayer { alpha = host.modelPanels?.composerOpacity ?: 1f }
        .then(if (hides) Modifier.clearAndSetSemantics {} else Modifier)
}

