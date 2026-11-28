package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.R as CoreR
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.LocalDrawerKeyboardSignals
import ai.opencode.core.ui.OpenCodeErrorText
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeNotice
import ai.opencode.core.ui.OpenCodeNoticePresence
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.AttachmentMenuAnchors
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompletePanel
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.ComposerAutocompleteTextBridge
import ai.opencode.feature.composer.ComposerFileSource
import ai.opencode.feature.composer.ComposerImageSource
import ai.opencode.feature.composer.ComposerKeyboardMorph
import ai.opencode.feature.composer.ComposerSideMargins
import ai.opencode.feature.composer.ComposerSwipeDown
import ai.opencode.feature.composer.ComposerSwipeOwnership
import ai.opencode.feature.composer.ComposerTextInput
import ai.opencode.feature.composer.DictationDrag
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.feature.composer.DictationLatency
import ai.opencode.feature.composer.LocalComposerKeyboardMorph
import ai.opencode.feature.composer.QueuedMessagesSheet
import ai.opencode.feature.composer.RequestBar
import ai.opencode.feature.composer.RequestDockContext
import ai.opencode.feature.composer.RequestDockView
import ai.opencode.feature.composer.RequestPanelFit
import ai.opencode.feature.composer.RequestPanelGesture
import ai.opencode.feature.composer.RequestPanelReveal
import ai.opencode.feature.composer.SyncComposerAutocomplete
import ai.opencode.feature.composer.rememberAutocompletePanelHeight
import ai.opencode.feature.composer.composerKeyboardSpacing
import ai.opencode.feature.composer.layoutProgress
import ai.opencode.feature.composer.requestPanelCap
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.feature.media.MediaReferenceResolver
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.list.TranscriptBorrowedDrag
import ai.opencode.feature.transcript.list.passesDragsToTranscript
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.VoiceCallRowsCard
import ai.opencode.feature.voice.ComposerSurface as VoiceComposerSurface
import ai.opencode.feature.voice.VoiceStoreOf
import ai.opencode.feature.voice.hiddenUnderFloatingCall
import ai.opencode.feature.voice.holdsClientToolCard
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeQueuedInput
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.PastedText
import android.content.res.Configuration
import android.os.Process
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Port of iOS `Session/SessionView+Composer.swift`: the composer area (pills, banners, the composer's
// surface, the dictation bar) and its presentation rules. The controls live in
// `SessionViewComposerControls.kt`.

// region Host

/**
 * What the screen around the composer shares with it (iOS reads these from `SessionView`'s other
 * extensions: the transcript's scroll edge, the attachment menu, the model picker's panels).
 * VIEW-MAIN and VIEW-PICKERS set them through [LocalSessionComposerHost]; every field defaults to
 * the plain behaviour, so [SessionComposer] works without a host. State-backed: setting a field
 * recomposes only what reads it.
 */
@Stable
class SessionComposerHost {
    internal val sendReset = ComposerSendReset()
    /** Server Flow: the server dropped; a pill above the composer says so (iOS `listContext.connection`). */
    var connection: ServerConnectionNotice? by mutableStateOf(null)
    /** iOS `onConnectionAction`: the connection pill was acted on. */
    var onConnectionAction: (ServerConnectionNotice.Action) -> Unit by mutableStateOf({})
    /** iOS `scrollEdgeBridge.showsJumpButton`. */
    var showsJumpButton by mutableStateOf(false)
    /**
     * Android: the transcript stays invisible while a row saved across process death loads.
     * The jump pill keeps its state but isn't drawn or touchable, so it shows with the rows.
     */
    var veilsJumpButton by mutableStateOf(false)
    /** iOS `scrollEdgeBridge.jumpRequest += 1`. */
    var onJumpToLatest: () -> Unit by mutableStateOf({})
    /** iOS `passesDragsToTranscript(scrollEdgeBridge)`: the transcript a pill's drag scrolls. */
    var borrowedDrag: TranscriptBorrowedDrag? by mutableStateOf(null)
    /** Where [BackgroundWorkPresentation] draws the capsule (iOS `backgroundActivityAnchor()`). */
    var backgroundWorkAnchor: BackgroundWorkAnchor? by mutableStateOf(null)
    /** Catch Up's card: no project list, no swipe-up focus, its own side clearance. */
    var isCatchUpCard by mutableStateOf(false)

    /**
     * The resting orb's room above the composer (iOS `orbClearance`), in dp: what stands above
     * the composer's top (the jump button, a draft's projects) stands above the orb too.
     */
    var orbClearance by mutableFloatStateOf(0f)
    /** The composer surface's top in the window, in px (NaN when it isn't shown), for `SessionBottomScrollEdge`. */
    var surfaceTopInWindow by mutableFloatStateOf(Float.NaN)
    /** The composer surface's bounds in the window (null when it isn't shown), which a quote's comment panel spans. */
    var surfaceInWindow by mutableStateOf<Rect?>(null)
    /** The quote whose comment panel is open over the composer. */
    var commentingQuoteID by mutableStateOf<String?>(null)
    /** iOS `toggleAttachmentMenu` (VIEW-PICKERS); null opens nothing. */
    var onPlusTapped: (() -> Unit)? by mutableStateOf(null)
    /** The attachment menu is open (iOS `attachmentMenu != .closed`): + shows open, the composer stays expanded. */
    var isAttachmentMenuOpen by mutableStateOf(false)
    /** iOS `imageImporter.isLoading || fileImporter.isLoading`. */
    var isImportingAttachments by mutableStateOf(false)
    /** iOS `imageImporter.isLoading`: only an image import holds Send and Return-key send. */
    var isImportingImages by mutableStateOf(false)
    /** iOS `pendingAttachmentsRow` (VIEW-PICKERS): shown above the field when the composer has attachments. */
    var pendingAttachmentsRow: (@Composable (composerExpanded: Boolean) -> Unit)? by mutableStateOf(null)
    /** One Picker's panel (iOS `onePicker`), set by `SessionModelPickerOverlays`. */
    internal var modelPanels: SessionModelPanels? by mutableStateOf(null)
    /** Composer Autocomplete's suggestions are open (iOS `autocompleteController.isOpen`), set by the composer. */
    internal var autocompleteCovers by mutableStateOf(false)
    /**
     * iOS `floatingCallCovers`: the Living Orb's card or sheet covers the composer, set by
     * `SessionFloatingCallCoverage`. The composer and its pills hide under it.
     */
    internal var floatingCallCovers by mutableStateOf(false)
    /** iOS `showsAutocomplete`: no request panel holds the composer's glass, so its suggestions can be drawn there. Set by the capsule. */
    internal var showsAutocomplete by mutableStateOf(true)
    /** iOS `composerPanelCovers`: a panel grown out of the composer (One Picker, suggestions, a quote's comment panel), or the Living Orb's card, stands over what sits above it. */
    val composerPanelCovers: Boolean get() = modelPanels?.covers == true || autocompleteCovers || floatingCallCovers || commentingQuoteID != null
    /** One Picker is shown: the composer stays expanded under it. */
    val keepsComposerExpanded: Boolean get() = modelPanels?.keepsComposerExpanded == true
    val keepsDraftProjectsFolded: Boolean get() = modelPanels?.keepsDraftProjectsFolded == true
    /** A panel or overlay owns downward drags now (iOS `composerSwipeYields`' panels). */
    val swipeYields: Boolean get() = modelPanels?.swipeYields == true
    /** iOS `UIAccessibility.post(.announcement)`, set by the composer (the view announces). */
    internal var announcer: (String) -> Unit = {}

    /** iOS `UIAccessibility.post(notification: .announcement, argument:)`. */
    fun announce(text: String) = announcer(text)

    /** The screen's imports (iOS `imageImporter`/`fileImporter`): pastes and drops on the field start them. */
    var importer: SessionAttachmentImporter? by mutableStateOf(null)
    val fieldLink = ai.opencode.feature.composer.ComposerFieldLink()
    /** The composer's frame (root px). */
    val attachmentAnchors = AttachmentMenuAnchors()
    /** The + menu, drawn hanging from the + button; set by the screen's attachment menu. */
    var attachmentDropdown: @Composable () -> Unit by mutableStateOf({})
    /** iOS `closeAttachmentMenu`: a request docking closes the menu. */
    var closeAttachmentMenu: () -> Unit = {}
    /**
     * The screen's height between the top safe area and the keyboard (or the bottom safe area),
     * and the header's height, both dp: the request panel's cap (iOS `requestPanelInputs.room`, `headerHeight`).
     */
    var availableHeight by mutableFloatStateOf(0f)
    var headerHeight by mutableFloatStateOf(0f)

    /** The message field has the keyboard (iOS `messageFieldFocused`); the field follows it. */
    var messageFieldFocused by mutableStateOf(false)
        internal set

    /** The trailing control pressed last (`true` Stop, `false` Send) and when ([System.nanoTime]); see `TrailingButton`. */
    internal var trailingPress: Pair<Boolean, Long>? = null
    internal var focusAction: () -> Unit = {}
    internal var dismissAction: () -> Unit = {}

    /** iOS `focusComposer()`. */
    fun focusComposer() {
        // Under the Living Orb's card the field is hidden: it waits for the orb.
        if (floatingCallCovers) return
        focusAction()
    }

    /** iOS `dismissComposerKeyboard()`. */
    fun dismissComposerKeyboard() = dismissAction()
}

/** The screen's [SessionComposerHost]; null: the composer makes its own. */
val LocalSessionComposerHost = compositionLocalOf<SessionComposerHost?> { null }

// endregion

// region View state

/** iOS `SessionView`'s composer `@State`s, owned by [SessionComposer]. */
@Stable
internal class SessionComposerViewState {
    var showsQueuedMessages by mutableStateOf(false)
    var dictationDrag by mutableStateOf(DictationDrag())
    /** Root px (iOS `dictationRowSpace`). */
    var dictationMicFrame by mutableStateOf(Rect.Zero)
    var dictationBarFrame by mutableStateOf(Rect.Zero)
    var dictationRetention by mutableStateOf(DictationHoldRetention())
    var dictationHoldVisuals by mutableStateOf(DictationHoldVisuals())
    /** The composer's controls as a press began, kept until its recording ends. */
    var heldComposerPresentation by mutableStateOf<ComposerPresentation?>(null)
    var holdGuides: Job? = null
    /** The text field's bounds in root px. */
    var textFieldBounds by mutableStateOf(Rect.Zero)
    var surfaceBounds by mutableStateOf(Rect.Zero)
    val capsuleSwipeOwnership = ComposerSwipeOwnership()
    var handledFocusRequest by mutableStateOf<Pair<Int, Int>?>(null)
    val autocompleteBridge = ComposerAutocompleteTextBridge()
    /** The composer area outside the panel's reserved room (dp), measured in one pass. */
    var requestChromeHeight by mutableFloatStateOf(0f)
    /** Actual room reserved by the capsule this pass; read by its parent after measurement. */
    var requestPanelRoomPx = 0
    /** iOS `requestPanelHeight`'s model value: the panel's measured height (dp). */
    var requestPanelHeight by mutableFloatStateOf(0f)
    /** The docked question's fold under a finger or settling, which the surface follows frame by frame. */
    val requestPanelGesture = RequestPanelGesture()
    /** iOS `requestPanelBottom`: the docked panel's bottom edge (root px; NaN without one). */
    var requestPanelBottom = Float.NaN
    /** iOS `requestPanelHeight` as drawn: animated as iOS's `withAnimation` changes it (dp). */
    val requestPanelShown = androidx.compose.animation.core.Animatable(0f)
    /** How much of the panel's height the surface reveals (1 docked or lingering, folding to 0 as it leaves). */
    val requestPanelReveal = androidx.compose.animation.core.Animatable(0f)
    val requestPanelEasing = RequestPanelFit.Easing()
    /** iOS `requestRoomHeld`, `requestPanelLingers`, `requestPanelIdentity`, `requestPanelLeaveTask`. */
    var requestRoomHeld by mutableStateOf(false)
    var requestPanelLingers by mutableStateOf(false)
    var requestPanelIdentity by mutableStateOf<SessionFeature.State.TaskIdentity?>(null)
    var requestPanelLeaveJob: Job? = null
    /** iOS `isImageDropTargeted`. */
    var isImageDropTargeted by mutableStateOf(false)
}

/**
 * Whether the message field had the keyboard when the activity was recreated, for the session it
 * belonged to (a field the drawer holds with its keys down has none; on a hardware keyboard the
 * focused field has it with no soft keys up, and the system keeps them down when it comes back).
 * The field loses its focus as the old activity's views go, after the first save, so later saves
 * of the same switch keep what that one saw. A field still being restored (its focus not
 * yet back, or its keys not yet up) when the next switch comes keeps it too. Restored only within
 * the same process: after process death iOS starts without the keyboard too.
 */
internal class ComposerKeyboardRestore(private val restored: SessionFeature.State.TaskIdentity? = null) {
    var hadKeyboard: () -> Boolean = { false }
    var changingConfigurations: () -> Boolean = { false }
    var identity: SessionFeature.State.TaskIdentity? = null
    /** The restored field still has its focus (and isn't held by the drawer). */
    var keepsRestoredFocus: () -> Boolean = { false }
    /** The field took the keyboard back and hasn't let go of its focus since. */
    var isRestoring = false
    private var pending = restored != null
    private var savedKeyboard = false
    /** The last save came while configurations were changing (the same switch saves more than once). */
    private var savedWhileChanging = false

    /** A recreation saving now, before or during this restore, keeps the field's keyboard. */
    private fun stillRestoring(): Boolean =
        (pending && restored == identity) || (isRestoring && keepsRestoredFocus())

    /** Whether the field should take the keyboard back on [current]; true at most once. */
    fun takeRestore(current: SessionFeature.State.TaskIdentity): Boolean {
        val restores = pending && restored == current
        pending = false
        return restores
    }

    companion object {
        private val process: String get() = "${Process.myPid()}:${Process.getStartUptimeMillis()}"

        val Saver: Saver<ComposerKeyboardRestore, ArrayList<String>> = Saver(
            save = { restore ->
                // Only an earlier save of this same switch carries over, never one from before it
                // (leaving the screen, going to the background).
                val changing = restore.changingConfigurations()
                val carries = restore.savedKeyboard && changing && restore.savedWhileChanging
                restore.savedKeyboard = restore.hadKeyboard() || restore.stillRestoring() || carries
                restore.savedWhileChanging = changing
                val identity = restore.identity?.takeIf { restore.savedKeyboard }
                identity?.let { arrayListOf(process, it.serverID, it.visit.toString(), it.sessionID.orEmpty()) }
            },
            restore = { saved ->
                val identity = saved.takeIf { it.size == 4 && it[0] == process }?.let {
                    SessionFeature.State.TaskIdentity(it[1], it[2].toInt(), it[3].ifEmpty { null })
                }
                ComposerKeyboardRestore(identity)
            },
        )
    }
}

/** iOS `composerPresentation`'s tuple. */
data class ComposerPresentation(val expanded: Boolean, val controls: ComposerControls)

/** What the composer reads from [SessionFeature.State], compared as one value so it recomposes only on change. */
internal data class ComposerSnapshot(
    /** iOS `draftShape`: only its edges, so typing doesn't recompose the composer ([ComposerInput] reads the text). */
    val draftShape: DraftShape,
    val isDraft: Boolean,
    val isRunning: Boolean,
    val hasPromptContent: Boolean,
    val canSendPrompt: Boolean,
    val hasPendingRequests: Boolean,
    val isUploadingFiles: Boolean,
    val isWaitingForUploads: Boolean,
    val hasFailedFiles: Boolean,
    val hasNonLocationAttachments: Boolean,
    val wantsLocationAttachment: Boolean,
    /** Not the words, which change with every speech result and draw nothing here. */
    val dictationHold: DictationFeature.Hold.Pose?,
    val dictationPhase: DictationFeature.Phase,
    val isDictating: Boolean,
    val isFinishingDictation: Boolean,
    val isPreparingDictation: Boolean,
    val dictationError: String?,
    val isAnsweringRequest: Boolean,
    val requestBar: RequestBar?,
    /** The docked request's last send didn't reach the server: Send reads Retry. */
    val requestRetries: Boolean,
    /** The docked request was settled on the server already: nothing more can send. */
    val requestSettled: Boolean,
    val takesPasteAttachments: Boolean,
    val hasSessionOrProject: Boolean,
    val isInterrupting: Boolean,
    val isVoiceAvailable: Boolean,
    val hasCall: Boolean,
    val callOwnsAudio: Boolean,
    val isCovered: Boolean,
    val taskIdentity: SessionFeature.State.TaskIdentity,
    val navigationID: Int,
    val modelLabel: String,
    val modelShortName: String,
    val chipLevelLabel: String?,
    val backgroundAgents: Int,
    val backgroundShells: Int,
    val backgroundScripts: Int,
    val queuedMessages: List<OpenCodeQueuedInput>,
    val settledRequestLoadError: String?,
    val canChooseProject: Boolean,
    val recentProjects: List<OpenCodeProject>,
    val starterPrompts: List<String>,
    val draftProject: OpenCodeProject?,
    val dockResponding: Boolean,
    val composerFocusRequest: Int,
) {
    val hasBackgroundWork: Boolean get() = backgroundAgents > 0 || backgroundShells > 0 || backgroundScripts > 0

    companion object {
        fun of(state: SessionFeature.State): ComposerSnapshot {
            val dock = state.dockRequestID
            return ComposerSnapshot(
                draftShape = state.draftShape,
                isDraft = state.isDraft,
                isRunning = state.isRunning,
                hasPromptContent = state.hasPromptContent,
                canSendPrompt = state.canSendPrompt,
                hasPendingRequests = state.hasPendingRequests,
                isUploadingFiles = state.isUploadingFiles,
                isWaitingForUploads = state.isWaitingForUploads,
                hasFailedFiles = state.pendingFiles.any { it.isFailed },
                hasNonLocationAttachments = state.pendingAttachments.isNotEmpty() || state.pendingFiles.isNotEmpty() || state.pendingQuotes.isNotEmpty(),
                wantsLocationAttachment = state.pendingLocation != null || state.isLocating,
                dictationHold = state.dictationHold?.pose,
                dictationPhase = state.dictationPhase,
                isDictating = state.isDictating,
                isFinishingDictation = state.isFinishingDictation,
                isPreparingDictation = state.isPreparingDictation,
                dictationError = state.dictationError,
                isAnsweringRequest = state.isAnsweringRequest,
                requestBar = state.requestBar,
                requestRetries = state.requestDock.dockReplyFailed,
                requestSettled = state.dockRequestID?.let { state.requestDock.requestFailures[it] }?.isSettled == true,
                takesPasteAttachments = state.takesPasteAttachments,
                hasSessionOrProject = state.session != null || state.draftProject != null,
                isInterrupting = state.isInterrupting,
                isVoiceAvailable = state.isVoiceAvailable,
                hasCall = state.voice != null,
                callOwnsAudio = state.callOwnsAudio,
                isCovered = state.isCovered,
                taskIdentity = state.taskIdentity,
                navigationID = state.navigationID,
                modelLabel = state.currentModelLabel,
                modelShortName = state.currentModelShortName,
                chipLevelLabel = chipLevelLabel(state),
                backgroundAgents = state.activeSubagents.size,
                backgroundShells = state.backgroundShells.size,
                backgroundScripts = state.backgroundScripts.size,
                queuedMessages = state.queuedInputRows.filter { it.waitsInQueuedPill },
                settledRequestLoadError = state.settledRequestLoadError,
                canChooseProject = state.canChooseProject,
                recentProjects = state.recentProjects,
                starterPrompts = state.starterPrompts,
                draftProject = state.draftProject,
                dockResponding = dock != null && dock in state.respondingRequestIDs,
                composerFocusRequest = state.composerFocusRequest,
            )
        }
    }
}

// endregion

/**
 * iOS `composerArea`: the pill row (background work, queued messages, jump to latest), the request
 * load error banner, a draft's recent projects (an overlay above, never moving the transcript's
 * insets) and the composer itself, with its dictation bar.
 *
 * Embedded by `SessionView` at the bottom of the screen; it applies its own side margins, top 6 dp
 * and bottom gap (keyboard gap when focused). The screen supplies IME and navigation-bar insets.
 */
@Composable
fun SessionComposer(
    store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    modifier: Modifier = Modifier,
    callStore: VoiceStoreOf? = null,
) {
    val host = LocalSessionComposerHost.current ?: remember { SessionComposerHost() }
    val view = remember { SessionComposerViewState() }
    val snapshot by store.observe { ComposerSnapshot.of(it) }
    val returnKeySends by remember(store) { store.state.returnKeySends.flow }.collectAsState(store.state.returnKeySends.value)
    val agentSelection by remember(store) { store.state.agentSelection.isEnabled.flow }.collectAsState(store.state.agentSelection.isEnabled.value)
    val autocomplete by store.observe { it.autocomplete }
    val reducedMotion = rememberReduceMotion()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val callShowsCard by remember(callStore) { callStore?.let { call -> call.states.map { it.phase.showsCard } } ?: flowOf(false) }
        .collectAsState(callStore?.state?.phase?.showsCard ?: false)
    val holdsClientToolCard by remember(callStore) { callStore?.let { call -> call.states.map { it.holdsClientToolCard } } ?: flowOf(false) }
        .collectAsState(callStore?.state?.holdsClientToolCard ?: false)

    val env = SessionComposerEnv(
        store = store,
        host = host,
        view = view,
        s = snapshot,
        reducedMotion = reducedMotion,
        returnKeySends = returnKeySends,
        agentSelection = agentSelection,
        autocomplete = autocomplete,
        focus = {
            // The field follows `messageFieldFocused` (iOS `focusComposer()`); the keyboard comes up even if it already had focus.
            host.messageFieldFocused = true
            keyboard?.show()
        },
        dismissKeyboard = {
            host.messageFieldFocused = false
            focusManager.clearFocus()
            keyboard?.hide()
        },
        scope = scope,
        callStore = callStore,
        holdsClientToolCard = holdsClientToolCard,
        callOwnsAudio = callStore != null && !callShowsCard,
        attachmentMenu = LocalSessionAttachmentMenu.current,
        windowSize = LocalWindowInfo.current.containerSize,
    )

    SyncComposerAutocomplete(
        state = autocomplete,
        isEnabled = true,
        bridge = view.autocompleteBridge,
        send = { store.send(Action.Autocomplete(it)) },
    )
    host.focusAction = env.focus
    // The background-work capsule above rides the request panel's room as it animates.
    host.backgroundWorkAnchor?.motion = { view.requestPanelShown.value * 1000f + view.requestPanelReveal.value }
    host.dismissAction = env.dismissKeyboard
    SideEffect {
        host.autocompleteCovers = autocomplete.isOpen
    }
    // A request docking, or dictation taking the composer, closes what grew out of it (iOS
    // `closeModelPanels()` on each).
    LaunchedEffect(env.docksRequest) { if (env.docksRequest) host.modelPanels?.closeModelPanels() }

    // A share or quote continued in OpenCode lands with the keyboard up (iOS `composerFocusRequest`,
    // handled here because the composer owns the field). Catch Up's cards never take it.
    LaunchedEffect(snapshot.navigationID, snapshot.composerFocusRequest) {
        val request = snapshot.navigationID to snapshot.composerFocusRequest
        if (host.isCatchUpCard || snapshot.composerFocusRequest <= 0 || request == view.handledFocusRequest) return@LaunchedEffect
        view.handledFocusRequest = request
        env.focus()
    }
    // A dark/light or text size switch recreates the activity, where iOS's field only redraws and
    // keeps the keyboard: the field that had it (a draft, or a question's answer) takes it back once.
    val keyboardRestore = rememberSaveable(saver = ComposerKeyboardRestore.Saver) { ComposerKeyboardRestore() }
    val activity = LocalActivity.current
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val drawerHold = LocalDrawerKeyboardSignals.current
    // iOS keeps the first responder across a trait change with or without the software keyboard.
    val typesOnHardwareKeys = configuration.keyboard != Configuration.KEYBOARD_NOKEYS &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    keyboardRestore.hadKeyboard = {
        host.messageFieldFocused && (ime.getBottom(density) > 0 || (typesOnHardwareKeys && drawerHold?.holdsCardField != true))
    }
    keyboardRestore.keepsRestoredFocus = { host.messageFieldFocused && drawerHold?.holdsCardField != true }
    keyboardRestore.changingConfigurations = { activity?.isChangingConfigurations == true }
    keyboardRestore.identity = snapshot.taskIdentity
    LaunchedEffect(keyboardRestore) {
        if (!keyboardRestore.takeRestore(snapshot.taskIdentity) || host.isCatchUpCard) return@LaunchedEffect
        env.focus()
        keyboardRestore.isRestoring = true
        snapshotFlow { host.messageFieldFocused }.first { !it }
        keyboardRestore.isRestoring = false
    }
    // iOS `resetDictationPresentation()` and the hold's `onChange`.
    val recordingNow = snapshot.isPreparingDictation || snapshot.isDictating || snapshot.dictationHold != null
    LaunchedEffect(recordingNow) { if (recordingNow) host.modelPanels?.closeModelPanels() }
    LaunchedEffect(snapshot.taskIdentity) {
        view.dictationRetention = view.dictationRetention.syncIdentity(snapshot.taskIdentity, snapshot.dictationHold, recordingNow)
        if (snapshot.dictationHold == null) {
            view.dictationHoldVisuals = view.dictationHoldVisuals.end()
            view.heldComposerPresentation = null
        }
    }
    LaunchedEffect(snapshot.dictationHold) {
        val hold = snapshot.dictationHold
        view.dictationRetention = view.dictationRetention.holdChanged(hold, snapshot.taskIdentity, recordingNow)
        if (hold == null) {
            view.dictationHoldVisuals = view.dictationHoldVisuals.end()
            view.heldComposerPresentation = null
        }
    }
    // Controls the row hides leave once the fold has landed ([dictationFoldLanded]); until then,
    // a recording that changed on this screen has no settled state.
    val recording = snapshot.dictationHold != null
    LaunchedEffect(snapshot.taskIdentity, recording) {
        val settled = view.dictationRetention.settledRecording(recording, snapshot.taskIdentity)
        if (recording == settled) return@LaunchedEffect
        view.setSettledRecording(null, snapshot)
    }

    env.ComposerArea(modifier)

    if (view.showsQueuedMessages) {
        QueuedMessagesSheet(
            messages = snapshot.queuedMessages,
            onSendNow = { store.send(Action.PendingInputSendNowTapped(it)) },
            onCopy = { store.send(Action.CopyTextTapped(it)) },
            onCancel = { store.send(Action.PendingInputCancelTapped(it)) },
            onClose = { view.showsQueuedMessages = false },
            // A call's delegation shows the voice agent's words, as the transcript does.
            displayText = { ai.opencode.feature.transcript.user.VoiceHandoff.parse(it)?.displayText ?: it },
            server = store.state.server,
        )
    }
}

/**
 * iOS `currentChipLevelLabel` (`ModelPickerSections.swift`): the level word (“High”, “Default”), or
 * null when the current model has no adjustable variants. Computed here so the composer doesn't
 * depend on the picker's level scale.
 */
private fun chipLevelLabel(state: SessionFeature.State): String? {
    val model = state.currentModelInfo ?: return null
    if (model.variants.none { it != "default" }) return null
    val level = state.currentModel?.variant?.takeIf { it != "default" } ?: return localized(R.string.session_model_level_default)
    return ai.opencode.server.model.ModelVariant.label(level)
}

/**
 * iOS `dictationFoldLanded(recording:)`: the fold toward [recording] has landed, so the controls it
 * hides leave, unless the recording changed again meanwhile.
 */
internal fun SessionComposerEnv.dictationFoldLanded(recording: Boolean) {
    val current = store.state
    if ((current.dictationHold != null) != recording) return
    if (view.dictationRetention.settledRecording(recording, current.taskIdentity) == recording) return
    view.setSettledRecording(recording, s)
}

/** iOS `dictationBarLeft()`: the last recording's bar has faded out, so it leaves the tree. */
internal fun SessionComposerEnv.dictationBarLeft() {
    if (store.state.dictationHold != null) return
    if (view.dictationRetention.leavingHold == null) return
    view.dictationRetention = view.dictationRetention.copy(leavingHold = null)
}

/**
 * iOS `composerColumnAnimation(opening:)`: a row joining or leaving the composer's column (the
 * pill row, the connection pill, a draft's projects): its height and the column's top move on one
 * spring, `layout` opening and `collapse` closing. Reduce Motion fades.
 */
internal fun <T> SessionComposerEnv.composerColumnAnimation(opening: Boolean, visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
    Motion.layout.animation(if (opening) MotionPhase.Enter else MotionPhase.Exit, reducedMotion, visibilityThreshold = visibilityThreshold)

/** iOS `dictationFoldAnimation(recording:)`: the composer folds (`layout` closing) and opens again (`layout`). */
internal fun <T> SessionComposerEnv.dictationFoldAnimation(recording: Boolean): FiniteAnimationSpec<T> =
    Motion.layout.animation(if (recording) MotionPhase.Exit else MotionPhase.Enter, reducedMotion)

internal fun SessionComposerViewState.setSettledRecording(value: Boolean?, s: ComposerSnapshot) {
    val recording = s.isPreparingDictation || s.isDictating || s.dictationHold != null
    dictationRetention = dictationRetention.syncIdentity(s.taskIdentity, s.dictationHold, recording).copy(settledRecording = value)
}

/** Everything the composer's parts read, gathered once per composition (iOS `SessionView`'s members). */
internal class SessionComposerEnv(
    val store: StoreOf<SessionFeature.State, SessionFeature.Action>,
    val host: SessionComposerHost,
    val view: SessionComposerViewState,
    val s: ComposerSnapshot,
    val reducedMotion: Boolean,
    val returnKeySends: Boolean,
    val agentSelection: Boolean,
    val autocomplete: ComposerAutocompleteState,
    val focus: () -> Unit,
    val dismissKeyboard: () -> Unit,
    val scope: kotlinx.coroutines.CoroutineScope,
    /** iOS `callStore`: the screen's call, or its root's. */
    val callStore: VoiceStoreOf? = null,
    /** iOS `holdsClientToolCard`: an agent's screen request or link waits on the user. */
    val holdsClientToolCard: Boolean = false,
    /** iOS `callOwnsAudio`: the screen's call (its own or its root's) owns audio. */
    val callOwnsAudio: Boolean = false,
    /** The + menu's state, null outside a session screen. */
    val attachmentMenu: SessionAttachmentMenuState? = null,
    /** The window's size (px): a sent photo's copy flies only from a thumbnail on screen. */
    val windowSize: IntSize = IntSize.Zero,
) {
    /** iOS `hasCall`: the screen's own call or its root's. */
    val hasCall: Boolean get() = s.hasCall || callStore != null

    val messageFieldFocused: Boolean get() = host.messageFieldFocused

    /** iOS `settledRecording`. */
    val settledRecording: Boolean? get() = view.dictationRetention.settledRecording(s.dictationHold != null, s.taskIdentity)

    /** The recording's hold, or the last one while its bar fades out. */
    val shownDictationHold: DictationFeature.Hold.Pose? get() = view.dictationRetention.shownHold(s.dictationHold, s.taskIdentity)

    fun dictationPose(hold: DictationFeature.Hold.Pose) = DictationBarPose(
        hold = hold,
        phase = s.dictationPhase,
        drag = view.dictationDrag,
        reducedMotion = reducedMotion,
        visuals = view.dictationHoldVisuals,
    )

    /** iOS `canSend`: mid-run sends steer; Send stays available while files upload. */
    val canSend: Boolean get() = s.hasPromptContent && !s.hasFailedFiles

    internal val hasComposerAttachments: Boolean
        get() = s.hasNonLocationAttachments || s.wantsLocationAttachment

    /** iOS `callLanding`: a hung-up floating call's shape is still flying into the call button. */
    val callLanding: Boolean get() = FloatingCallCenter.shared.isLanding

    /** iOS `docksRequest`: answering a request, its panel grows out of the composer. */
    val docksRequest: Boolean get() = s.isAnsweringRequest

    /** iOS `showsVoiceControl`: Voice is set up and no call is running. */
    val showsVoiceControl: Boolean get() = s.isVoiceAvailable && !hasCall

    /** iOS `holdingStopStartsCall`: mid-run, holding Stop starts the call the empty composer would offer. */
    val holdingStopStartsCall: Boolean get() = showsVoiceControl && !s.hasPromptContent && s.isRunning

    /** iOS `composerPresentation`. */
    val composerPresentation: ComposerPresentation
        get() {
            // A held mic keeps the composer exactly as it was: the touch belongs to the mic button,
            // which must stay mounted in place until it lifts.
            val holding = s.dictationHold != null
            val held = view.heldComposerPresentation
            if (holding && held != null) return held
            val dictationActive = (s.isDictating || s.isFinishingDictation) && !holding
            // Answering a request, the field stays on the control row; a draft keeps its control
            // row showing, so the model is at hand.
            val expanded = !s.isAnsweringRequest && (
                s.isDraft || messageFieldFocused || dictationActive || !s.draftShape.isEmpty ||
                    hasComposerAttachments || s.dictationError != null || panelsHoldExpanded
                )
            var controls = ComposerControls.make(
                isDictating = s.isDictating && !holding,
                isFinishing = s.isFinishingDictation && !holding,
                isRunning = s.isRunning,
                canSend = canSend,
                expanded = expanded,
                canCall = showsVoiceControl && !s.hasPromptContent,
                keepsSendVisible = attachmentMenu != null,
                isInCall = attachmentMenu != null && callOwnsAudio,
            )
            // A floating call holds the mic: the composer offers only typing.
            val holdsMic = callOwnsAudio || callLanding
            if (holdsMic && (controls.trailing == ComposerTrailingControl.Mic || controls.secondaryMic != ComposerControls.MicPlacement.none)) {
                val trailing = if (controls.trailing == ComposerTrailingControl.Mic) ComposerTrailingControl.Send(enabled = false) else controls.trailing
                controls = controls.copy(trailing = trailing, secondaryMic = ComposerControls.MicPlacement.none)
            }
            return ComposerPresentation(expanded, controls)
        }

    /**
     * The model controls live in the expanded composer: it stays expanded while their panels
     * are drawn, or the + menu that will hand the keyboard back.
     */
    private val panelsHoldExpanded: Boolean
        get() {
            val menu = attachmentMenu
            // The + menu keeps the composer open only when it'll hand the keyboard back.
            val menuKeepsOpen = host.isAttachmentMenuOpen && (menu == null || menu.attachmentMenuRestoresFocus)
            return host.keepsComposerExpanded || menuKeepsOpen
        }

    /**
     * iOS `composerPinnedExpanded`: what holds the row open apart from focus (a draft, attachments,
     * dictation, a panel), so [ComposerKeyboardMorph] moves it on the keyboard's curve.
     */
    val composerPinnedExpanded: Boolean
        get() {
            val held = view.heldComposerPresentation
            if (s.dictationHold != null && held != null) return held.expanded
            return !s.isAnsweringRequest && (
                s.isDraft || !s.draftShape.isEmpty || hasComposerAttachments || s.isDictating ||
                    s.isFinishingDictation || s.dictationError != null || panelsHoldExpanded
                )
        }

    /** iOS `sendMessage(_:)`. */
    fun sendMessage(action: Action = Action.SendTapped) {
        host.fieldLink.input?.flushEdits()
        if (action == Action.ReturnKeyTapped && !canSend) {
            store.send(action)
            return
        }
        val state = store.state
        if (!state.canSendPrompt || state.hasPendingRequests) return
        // Decoded under the IDs the bubble reads, so its first frame shows the photos.
        val sentData = state.pendingAttachments.map { it.data }
        if (!reducedMotion) host.sendReset.start()
        store.send(action)
        host.fieldLink.input?.takeParentText()
        if (sentData.isEmpty()) return
        val sent = store.state
        val images = ArrayList<OpenCodeImageAttachment>()
        val newest = sent.items.lastOrNull { it is OpenCodeTranscriptItem.UserImages }
        if (newest is OpenCodeTranscriptItem.UserImages) images += newest.images
        for (queued in sent.queuedInputImages.values) images += queued
        for (image in images) {
            if (sentData.none { it === image.data }) continue
            MediaReferenceResolver.thumbnail(image)
        }
    }

    /** iOS `showsDraftProjects`: a draft lists its recent projects above the composer until its first send leaves. */
    val showsDraftProjects: Boolean get() = s.canChooseProject && !host.isCatchUpCard && !holdsClientToolCard

    /** iOS `showsStarterPrompts`: the first computer's starter prompts stand above the projects until composing. */
    val showsStarterPrompts: Boolean get() = s.starterPrompts.isNotEmpty() && !isComposingDraft

    /** iOS `isComposingDraft`: composing, the list folds down to the chosen project. */
    val isComposingDraft: Boolean
        get() {
            if (s.hasCall) return false
            return messageFieldFocused || s.hasPromptContent || s.dictationHold != null || s.isDictating ||
                s.isFinishingDictation || host.isAttachmentMenuOpen || host.keepsDraftProjectsFolded
        }

    /** iOS `composerSwipeYields(from:)`: a touch another control owns. */
    fun composerSwipeYields(start: Offset?): Boolean {
        val panelBottom = if (s.isAnsweringRequest && !view.requestPanelBottom.isNaN()) view.requestPanelBottom else null
        if (
            Companion.composerSwipeYields(
                dictationHold = s.dictationHold,
                attachmentMenuOpen = host.isAttachmentMenuOpen,
                panelsShown = host.swipeYields,
                requestPanelTracking = view.requestPanelGesture.isTracking,
                requestPanelBottom = panelBottom,
                start = start,
            )
        ) return true
        if (start == null) return false
        val mic = view.dictationMicFrame
        return mic != Rect.Zero && mic.inflate(4f).contains(start)
    }

    /** iOS `composerSwipeUpBandContains(_:)` (root px). */
    fun composerSwipeUpBandContains(point: Offset): Boolean {
        val rect = view.surfaceBounds
        if (rect == Rect.Zero || !rect.contains(point) || composerSwipeYields(point)) return false
        val text = view.textFieldBounds
        if (text != Rect.Zero) {
            if (composerPresentation.expanded && s.requestBar == null) return point.y >= text.top && point.y <= text.bottom
            return point.x >= text.left && point.x <= text.right
        }
        return false
    }

    /** iOS `focusComposerFromSwipe()` (`performSwipeUpFocus`). */
    fun focusComposerFromSwipe() {
        if (messageFieldFocused || host.isCatchUpCard || composerSwipeYields(null) || s.isPreparingDictation || s.isDictating) return
        store.send(Action.ComposerSwipedUp)
    }

    /** iOS `beginDictationHold()`: a new recording's hold starts from nothing, before its bar mounts. */
    fun beginDictationHold() {
        DictationLatency.mark("press_began")
        val hold = store.state.dictationHold
        if (hold == null) view.heldComposerPresentation = composerPresentation
        if (hold == null || store.state.isFinishingDictation) view.dictationHoldVisuals = view.dictationHoldVisuals.begin()
        store.send(Action.micPressBegan)
        // Counted from the touch: a hold grows the circle and offers the lock target about 0.1 s
        // in, and a quick tap usually locks before then and never shows them.
        view.holdGuides?.cancel()
        view.holdGuides = scope.launch {
            delay(90)
            val current = store.state.dictationHold ?: return@launch
            if (current.isLocked || store.state.isFinishingDictation) return@launch
            view.dictationHoldVisuals = view.dictationHoldVisuals.copy(showsGuides = true)
        }
    }

    /** The row has faded out under the dictation bar. */
    val foldsComposerControls: Boolean get() = s.dictationHold != null && settledRecording == true

    /** A held mic owns its finger until it lifts; then it folds like the rest. */
    val foldsHeldMic: Boolean
        get() {
            val hold = s.dictationHold ?: return false
            return foldsComposerControls && (hold.isLocked || s.isFinishingDictation)
        }

    companion object {
        /**
         * iOS `SessionView.composerSwipeYields(...)`: a touch another control owns (a held mic, the
         * attachment menu, a panel grown out of the composer): a downward drag there is that
         * control's, never the keyboard's dismissal.
         */
        fun composerSwipeYields(
            dictationHold: DictationFeature.Hold.Pose? = null,
            attachmentMenuOpen: Boolean = false,
            panelsShown: Boolean = false,
            requestPanelTracking: Boolean = false,
            requestPanelBottom: Float? = null,
            start: Offset? = null,
        ): Boolean {
            if (dictationHold != null || attachmentMenuOpen || panelsShown) return true
            // A docked question's fold has the finger, or the touch began on its panel (whose
            // drags fold it or scroll its list).
            if (requestPanelTracking) return true
            return requestPanelBottom != null && start != null && start.y < requestPanelBottom
        }
    }
}

// region Area

@Composable
private fun SessionComposerEnv.ComposerArea(modifier: Modifier) {
    // iOS `composerArea`: only this subtree follows the keyboard's frames. The field's focus
    // opens the row on the keyboard's spring.
    ComposerKeyboardMorph(
        focused = messageFieldFocused,
        expanded = composerPresentation.expanded,
        pinned = composerPinnedExpanded,
        reducedMotion = reducedMotion,
        snapsUnpin = host.sendReset.isResetting,
    ) {
        ComposerAreaContent(modifier)
    }
}

@Composable
private fun SessionComposerEnv.ComposerAreaContent(modifier: Modifier) {
    val pillGap = ComposerTunables.pillGap
    // iOS `composerSideMargin` / `composerKeyboardSideMargin`: with the keys up the composer
    // widens to the keyboard gap's margin; a Catch Up card keeps its concentric clearance.
    val sides = if (host.isCatchUpCard) {
        ComposerSideMargins(CATCH_UP_COMPOSER_CLEARANCE.dp, CATCH_UP_COMPOSER_CLEARANCE.dp)
    } else {
        ComposerSideMargins(ComposerTunables.horizontalMargin.dp, ComposerTunables.keyboardHorizontalMargin.dp)
    }
    val morph = LocalComposerKeyboardMorph.current
    Box(
        modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val area = measurable.measure(constraints)
                // iOS chrome = area - (capsule - bar). Read the room actually reserved by
                // the child in this pass, never a mix of old/new onSizeChanged callbacks.
                val chrome = Math.round((area.height - view.requestPanelRoomPx).toDp().value).toFloat()
                if (view.requestChromeHeight != chrome) view.requestChromeHeight = chrome
                layout(area.width, area.height) { area.placeRelative(0, 0) }
            }
            .padding(top = 6.dp)
            // The bottom gap grows to the keyboard gap with the keys, and the side margins
            // shrink to it, frame by frame.
            .composerKeyboardSpacing(
                morph,
                top = false,
                innerVertical = ComposerTunables.innerVertical.dp,
                bottomGap = ComposerTunables.bottomGap.dp,
                keyboardGap = ComposerTunables.keyboardGap.dp,
                sides = sides,
            ),
    ) {
        ai.opencode.feature.composer.RequestDockPrewarm()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(pillGap.dp)) {
            // The pill row joining or leaving moves the composer's top, and the transcript riding
            // it, on the row's own curve.
            AnimatedVisibility(
                visible = s.hasBackgroundWork || s.queuedMessages.isNotEmpty(),
                enter = expandVertically(composerColumnAnimation(opening = true, IntSize.VisibilityThreshold), Alignment.Bottom, clip = false),
                exit = shrinkVertically(composerColumnAnimation(opening = false, IntSize.VisibilityThreshold), Alignment.Bottom, clip = false),
            ) {
                HiddenUnderComposerPanel(host.composerPanelCovers) { ComposerPills() }
            }
            OpenCodeNoticePresence(s.settledRequestLoadError) { error ->
                HiddenUnderComposerPanel(host.composerPanelCovers) {
                    OpenCodeNotice(
                        title = stringResource(R.string.session_requests_load_failed_title),
                        detail = error,
                        action = stringResource(CoreR.string.common_retry),
                        onClick = { store.send(Action.refreshRequestsTapped) },
                        modifier = Modifier.semantics { testTag = "request-load-error" },
                    )
                }
            }
            ServerConnection()
            // iOS `callRows`: none while a docked request's panel holds the composer's surface.
            VoiceCallRowsCard(
                store = callStore,
                hidden = docksRequest,
                reduceMotion = reducedMotion,
            )
            Box {
                ComposerCapsule()
            }
        }
        // Without pills, the jump button floats where their row would be, so showing it never
        // changes the transcript's insets.
        if (!s.hasBackgroundWork && s.queuedMessages.isEmpty()) {
            HiddenUnderComposerPanel(
                host.composerPanelCovers,
                Modifier
                    .align(Alignment.TopEnd)
                    // Above a resting orb too, which stands where the pills would.
                    .offset(y = -(48 + pillGap + host.orbClearance).dp),
            ) {
                // The call's open transcript covers where it would float.
                val jumpShown = host.showsJumpButton && !holdsClientToolCard
                JumpToLatestPill(
                    isShown = jumpShown,
                    onClick = host.onJumpToLatest,
                    veiled = { host.veilsJumpButton },
                    modifier = Modifier.passesDragsToTranscript(host.borrowedDrag, isEnabled = jumpShown && !host.veilsJumpButton),
                )
            }
        }
        // A draft's recent projects stand above the composer as an overlay, so folding them, or
        // their leaving on the first send, never changes the transcript's insets.
        DraftProjectsOverlay(Modifier.align(Alignment.TopCenter))
    }
}

/** Server Flow: iOS `ServerConnectionPill` above the capsule while the session's server is away. */
@Composable
private fun SessionComposerEnv.ServerConnection() {
    OpenCodeNoticePresence(host.connection) { notice ->
        HiddenUnderComposerPanel(host.composerPanelCovers) {
            ServerConnectionPill(notice, host.onConnectionAction)
        }
    }
}

/** iOS `hiddenUnderComposerPanel(_:)`: a panel grown out of the composer stands over this. */
@Composable
private fun HiddenUnderComposerPanel(covered: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (covered) 0f else 1f, Motion.easeOut(Motion.fadeFast), label = "underPanel")
    Box(modifier.alpha(alpha).then(if (covered) Modifier.clearAndSetSemantics { }.blockTouches() else Modifier)) { content() }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SessionComposerEnv.DraftProjectsOverlay(modifier: Modifier) {
    // The list arriving and leaving with the composer's column (`layout` opening, `collapse` closing).
    // It leaves that way only when a draft's own first send creates its session; switching to another
    // existing session or Catch Up card swaps at once so the draft list never ghosts over it.
    val state = store.state
    val animatesExit = !host.isCatchUpCard && (state.session == null || state.session.id == state.draftBecameSessionID)
    val enter = fadeIn(composerColumnAnimation(opening = true))
    val exit = if (!animatesExit) ExitTransition.None else fadeOut(composerColumnAnimation(opening = false))
    // A zero-height edge at the area's top that the list stands on, a stack's spacing above.
    Box(
        modifier
            .fillMaxWidth()
            .height(0.dp)
            .wrapContentHeight(Alignment.Bottom, unbounded = true)
            // A stack's spacing above whatever comes first, and the resting orb's room: it stands over the area's top.
            .offset(y = -(ComposerTunables.pillGap + host.orbClearance).dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = showsDraftProjects, enter = enter, exit = exit) {
            // In a short window (landscape with the keys up, a split screen) the list gives way to
            // the header, as the header gives way to the composer.
            val density = LocalDensity.current
            val headerBottom = WindowInsets.statusBarsIgnoringVisibility.getTop(density) + with(density) { host.headerHeight.dp.toPx() }
            var listTop by remember { mutableFloatStateOf(Float.POSITIVE_INFINITY) }
            val givesWay = listTop < headerBottom
            val shown by animateFloatAsState(if (givesWay) 0f else 1f, Motion.easeOut(Motion.fadeFast), label = "givesWay")
            // Faded out, it's measured but not placed, so taps reach the header under it (iOS
            // `allowsHitTesting(false)`): the draft's title chooses the project there instead.
            Layout(
                content = {
                    HiddenUnderComposerPanel(host.composerPanelCovers || givesWay) {
                        Column(verticalArrangement = Arrangement.spacedBy(StarterPromptTunables.listGap.dp)) {
                            AnimatedVisibility(visible = showsStarterPrompts, enter = fadeIn(), exit = fadeOut()) {
                                StarterPromptList(s.starterPrompts, onPick = { store.send(Action.StarterPromptTapped(it)) })
                            }
                            DraftProjectList(
                                identity = s.taskIdentity,
                                projects = s.recentProjects,
                                chosen = s.draftProject,
                                isComposing = isComposingDraft,
                                onPick = { store.send(Action.DraftProjectTapped(it)) },
                                onAll = { store.send(Action.ProjectButtonTapped) },
                            )
                        }
                    }
                },
                modifier = Modifier.onGloballyPositioned { listTop = it.positionInWindow().y },
            ) { measurables, constraints ->
                val list = measurables.single().measure(constraints)
                layout(list.width, list.height) { if (!givesWay || shown > 0f) list.place(0, 0) }
            }
        }
    }
}

// endregion

// region Pills

/**
 * One row (background work on the left; queued messages and the jump button on the right), stacking
 * only when the two pills overflow it at full width. The jump button never decides that: it joins the
 * queued pill's row when its slot fits too, and otherwise floats above the rows' top end, so its coming
 * and going never changes their height.
 */
@Composable
private fun SessionComposerEnv.ComposerPills() {
    val hasActivity = s.hasBackgroundWork
    val hasQueued = s.queuedMessages.isNotEmpty()
    if (!hasActivity && !hasQueued) return
    val showsJump = host.showsJumpButton && !holdsClientToolCard
    val activity: @Composable () -> Unit = { if (hasActivity) BackgroundActivity() }
    val queued: @Composable () -> Unit = { QueuedActivity(hasQueued) }
    // Beside the queued pill the jump slot carries its own gap, so the queued pill slides over as it closes.
    val jump: @Composable (Boolean) -> Unit = { collapsesSlot ->
        JumpToLatestPill(isShown = showsJump, onClick = host.onJumpToLatest, collapsesSlot = collapsesSlot, veiled = { host.veilsJumpButton }, modifier = Modifier.passesDragsToTranscript(host.borrowedDrag, isEnabled = showsJump && !host.veilsJumpButton))
    }
    val jumpInRow: @Composable () -> Unit = { jump(true) }
    val jumpAlone: @Composable () -> Unit = { jump(false) }
    val height = remember { PillRowsHeight() }
    val scope = rememberCoroutineScope()
    SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val activityPill = subcompose(PillSlot.Activity, activity).firstOrNull()?.measure(loose)
        val queuedPill = subcompose(PillSlot.Queued, queued).firstOrNull()?.measure(loose)
        val activityWidth = if (activityPill != null) activityPill.width + 8.dp.roundToPx() else 0
        val queuedWidth = queuedPill?.width ?: 0
        // The slot fully open, so the jump button showing or not never changes where it stands.
        val jumpSlot = (if (queuedPill != null) 48f + JumpToLatestPillMotion.gap else 48f).dp.roundToPx()
        val stacks = activityPill != null && queuedPill != null && activityWidth + queuedWidth > width
        val jumpFits = if (stacks) queuedWidth + jumpSlot <= width else activityWidth + queuedWidth + jumpSlot <= width
        val jumpContent = if (queuedPill != null && jumpFits) jumpInRow else jumpAlone
        val jumpPill = subcompose(PillSlot.Jump, jumpContent).single().measure(loose)
        val jumpWidth = if (jumpFits) jumpPill.width else 0
        // The row that holds the queued pill (and the jump button when it fits); background work stands above it when stacked.
        val bottomRow = maxOf(queuedPill?.height ?: 0, if (jumpFits) jumpPill.height else 0, if (stacks) 0 else activityPill?.height ?: 0)
        val target = if (stacks) activityPill.height + 10.dp.roundToPx() + bottomRow else bottomRow
        val shown = height.measure(target, scope) { opening -> composerColumnAnimation(opening, Int.VisibilityThreshold) }
        val floatingY = -(48 + ComposerTunables.pillGap + host.orbClearance).dp.roundToPx()
        layout(width, shown) {
            val bottomRowTop = shown - bottomRow
            if (activityPill != null) {
                activityPill.placeRelative(0, if (stacks) 0 else (bottomRow - activityPill.height) / 2)
            }
            queuedPill?.placeRelative(width - jumpWidth - queuedPill.width, bottomRowTop + (bottomRow - queuedPill.height) / 2)
            jumpPill.placeRelative(width - jumpPill.width, if (jumpFits) bottomRowTop + (bottomRow - jumpPill.height) / 2 else floatingY)
        }
    }
}

private enum class PillSlot { Activity, Queued, Jump }

/** The pill rows' height, moving on the composer column's curve when they stack or unstack, and at rest at first. */
private class PillRowsHeight {
    private var animation: Animatable<Int, AnimationVector1D>? = null

    fun measure(target: Int, scope: CoroutineScope, spec: (opening: Boolean) -> AnimationSpec<Int>): Int {
        val current = animation
        if (current == null) {
            animation = Animatable(target, Int.VectorConverter)
            return target
        }
        if (current.targetValue != target) {
            scope.launch { current.animateTo(target, spec(target > current.value)) }
        }
        return current.value
    }
}

/** iOS `backgroundActivity`: the capsule's place in the pill row; [BackgroundWorkPresentation] draws it. */
@Composable
private fun SessionComposerEnv.BackgroundActivity() {
    val anchor = host.backgroundWorkAnchor
    BackgroundWorkCapsuleSpace(
        agents = s.backgroundAgents,
        shells = s.backgroundShells,
        scripts = s.backgroundScripts,
        modifier = if (anchor != null) Modifier.backgroundActivityAnchor(anchor) else Modifier,
    )
}

/** iOS `queuedActivity`: delivered or sent now, a message lifts out toward the transcript. */
@Composable
private fun SessionComposerEnv.QueuedActivity(hasQueued: Boolean) {
    val count = s.queuedMessages.size
    AnimatedVisibility(
        visible = hasQueued,
        enter = if (reducedMotion) fadeIn(composerColumnAnimation(opening = true)) else scaleIn(composerColumnAnimation(opening = true), initialScale = 0.92f) + fadeIn(composerColumnAnimation(opening = true)),
        exit = if (reducedMotion) fadeOut(composerColumnAnimation(opening = false)) else slideOutVertically(composerColumnAnimation(opening = false)) { -it } + fadeOut(composerColumnAnimation(opening = false)),
    ) {
        ComposerPillLabel(
            title = pluralStringResource(R.plurals.session_queued_count, count, count),
            rolls = true,
            onClick = { view.showsQueuedMessages = true },
            onClickLabel = stringResource(R.string.session_queued_show_label),
            accessibilityLabel = pluralStringResource(R.plurals.session_queued_messages_description, count, count),
            modifier = Modifier
                .passesDragsToTranscript(host.borrowedDrag)
                .semantics { testTag = "queued-messages" },
        ) {
            OpenCodeIcon(SFSymbol.TextBubble, contentDescription = null, size = 17.dp, modifier = Modifier.offset(y = ComposerTunables.queuedGlyphDrop.dp))
        }
    }
}

// endregion

// region Capsule

/**
 * iOS `composerCapsule`: the composer's surface ([ComposerSurface]). A pending request's panel grows up out of it,
 * drawn above the bar in the room the capsule makes for it (layout-phase top padding),
 * which shrinks with the surface as it folds.
 */
@Composable
private fun SessionComposerEnv.ComposerCapsule() {
    RequestPanelPresence()
    val showsRequestPanel = holdsRequestPanelRoom || lingersRequestPanel
    // With no panel, Composer Autocomplete's suggestions grow there instead.
    val showsAutocomplete = !showsRequestPanel
    SideEffect { if (host.showsAutocomplete != showsAutocomplete) host.showsAutocomplete = showsAutocomplete }
    // Read where it's used (layout, or the panel's own scope), never here: the room under the
    // header changes on every frame of the keyboard's animation.
    val cap = { requestPanelCap() }
    val activeHeight = { activeRequestPanelHeight(cap()) }
    val room = { if (showsRequestPanel) activeHeight() * view.requestPanelReveal.value else 0f }
    DisposableEffect(Unit) {
        onDispose {
            host.surfaceTopInWindow = Float.NaN
            host.surfaceInWindow = null
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                host.surfaceTopInWindow = bounds.top
                host.surfaceInWindow = bounds
            }
            .layout { measurable, constraints ->
                val bar = measurable.measure(constraints.offset(vertical = -room().dp.roundToPx()))
                // Measuring a self-animating permission updates its height. Reserve that
                // same frame's room so the pills cannot lag over the panel's caption.
                val top = room().dp.roundToPx()
                val height = constraints.constrainHeight(bar.height + top)
                view.requestPanelRoomPx = height - bar.height
                layout(bar.width, height) {
                    bar.placeRelative(0, top)
                }
            },
    ) {
        // iOS `composerSurfacePanel`: the request's panel, else the suggestions, on the surface.
        val autocompleteHeight = rememberAutocompletePanelHeight(autocomplete)
        val autocompleteExtent = { if (showsAutocomplete) autocompleteHeight.value else 0f }
        ComposerSurface(
            panel = when {
                showsRequestPanel || docksRequest -> {
                    { RequestPanelContent(isShown = docksRequest, cap = cap()) }
                }
                showsAutocomplete -> {
                    {
                        ComposerAutocompletePanel(
                            state = autocomplete,
                            height = { autocompleteHeight.value },
                            onSelectRow = { store.send(Action.Autocomplete(ComposerAutocompleteAction.RowChosen(it))) },
                        )
                    }
                }
                else -> null
            },
            // Laid out from its arrival until its surface has folded.
            panelExtent = { if (showsRequestPanel) activeHeight() else autocompleteExtent() },
            // Under Reduce Motion the surface keeps its reach while the panel fades.
            panelReveal = { if (showsRequestPanel) activeHeight() * view.requestPanelReveal.value else autocompleteExtent() },
            panelShown = docksRequest || (showsAutocomplete && autocomplete.isOpen),
        )
    }
}

/** iOS `holdsRequestPanelRoom`. */
private val SessionComposerEnv.holdsRequestPanelRoom: Boolean
    get() = docksRequest || (view.requestRoomHeld && view.requestPanelIdentity == s.taskIdentity)

/** iOS `lingersRequestPanel`. */
private val SessionComposerEnv.lingersRequestPanel: Boolean
    get() = docksRequest || (view.requestPanelLingers && view.requestPanelIdentity == s.taskIdentity)

/**
 * iOS `requestPanelMaxHeight`: the tallest the request panel may be so the composer stays under
 * the header, from the room between the top safe area and the keyboard, the header and
 * the composer's chrome outside the panel.
 */
private fun SessionComposerEnv.requestPanelCap(): Float? {
    return RequestPanelFit.panelCap(room = host.availableHeight, header = host.headerHeight, chrome = view.requestChromeHeight)
}

/**
 * iOS `activeRequestPanelHeight`: the panel's room, its height as drawn, never more than the cap the
 * panel is laid out under in this same pass.
 */
private fun SessionComposerEnv.activeRequestPanelHeight(cap: Float?): Float {
    val height = if (docksRequest || view.requestPanelIdentity == s.taskIdentity) view.requestPanelShown.value else 0f
    return RequestPanelFit.room(measured = height, cap = cap)
}

/**
 * iOS's request panel bookkeeping on the capsule: `onAppear`, `onChange(of: docksRequest)`,
 * `requestPanelLeft()` and `resetRequestPanel()` (on another screen in place).
 */
@Composable
private fun SessionComposerEnv.RequestPanelPresence() {
    val current by rememberUpdatedState(this)
    val docks = docksRequest
    LaunchedEffect(Unit) {
        if (!docks) return@LaunchedEffect
        view.requestPanelIdentity = s.taskIdentity
        view.requestRoomHeld = true
        view.requestPanelReveal.snapTo(1f)
    }
    var lastDocks by remember { mutableStateOf(docks) }
    LaunchedEffect(docks) {
        if (lastDocks == docks) return@LaunchedEffect
        lastDocks = docks
        // The keyboard never carries over: the message's into an answer, or an answer's back into the message.
        host.messageFieldFocused = false
        view.requestPanelLeaveJob?.cancel()
        view.requestPanelLeaveJob = null
        if (!docks) return@LaunchedEffect current.requestPanelLeft()
        view.requestPanelIdentity = s.taskIdentity
        view.requestPanelLingers = false
        view.requestRoomHeld = true
        // The call's captions and card make way in `SessionFloatingCallCoverage`.
        host.closeAttachmentMenu()
        // The surface grows back over the panel from where a fold left it.
        if (reducedMotion || view.requestPanelShown.value <= 0.5f) {
            view.requestPanelReveal.snapTo(1f)
        } else {
            view.requestPanelReveal.animateTo(1f, RequestPanelReveal.animation(opening = true, reducedMotion = false))
        }
    }
    var seenIdentity by remember { mutableStateOf(s.taskIdentity) }
    LaunchedEffect(s.taskIdentity) {
        if (seenIdentity == s.taskIdentity) return@LaunchedEffect
        seenIdentity = s.taskIdentity
        current.resetRequestPanel()
    }
}

/**
 * iOS `requestPanelLeft()`: the request left (answered, withdrawn, or a call took the composer's
 * place). The surface folds over the panel on `panelOpen`'s exit (under Reduce Motion it lingers
 * while the panel fades on the reduced role); once the fold lands, [requestPanelFolded] lets the
 * room go.
 */
private fun SessionComposerEnv.requestPanelLeft() {
    view.requestPanelLeaveJob?.cancel()
    if (!holdsRequestPanelRoom && !lingersRequestPanel && activeRequestPanelHeight(null) <= 0f) return
    val leaving = s.taskIdentity
    view.requestPanelIdentity = leaving
    if (reducedMotion) view.requestPanelLingers = true
    val reduced = reducedMotion
    // Cancelled when a request docks again or the screen changes.
    view.requestPanelLeaveJob = scope.launch {
        if (reduced) {
            delay(Motion.millis(Motion.reduced.curve(reduceMotion = true).duration).toLong())
        } else {
            view.requestPanelReveal.animateTo(0f, RequestPanelReveal.animation(opening = false, reducedMotion = false))
        }
        requestPanelFolded(leaving)
    }
}

/**
 * iOS `requestPanelFolded(fold:)`: the leaving request's fold has landed. Unless another request
 * docked (iOS `!docksRequest`, read now) or the screen changed meanwhile, its height is
 * forgotten, so the next request grows from nothing.
 */
private suspend fun SessionComposerEnv.requestPanelFolded(leaving: SessionFeature.State.TaskIdentity?) {
    if (store.state.isAnsweringRequest || view.requestPanelIdentity != leaving) return
    view.requestPanelLingers = false
    view.requestPanelReveal.snapTo(0f)
    view.requestRoomHeld = false
    view.requestPanelHeight = 0f
    view.requestPanelShown.snapTo(0f)
}

/** iOS `resetRequestPanel()`: another screen in place keeps nothing of the last one's panel. */
private fun SessionComposerEnv.resetRequestPanel() {
    view.requestPanelGesture.reset()
    view.requestPanelBottom = Float.NaN
    view.requestPanelLeaveJob?.cancel()
    view.requestPanelLeaveJob = null
    val docks = docksRequest
    view.requestPanelIdentity = if (docks) s.taskIdentity else null
    view.requestPanelLingers = false
    view.requestRoomHeld = docks
    scope.launch {
        view.requestPanelReveal.snapTo(if (docks) 1f else 0f)
        if (!docks) {
            view.requestPanelHeight = 0f
            view.requestPanelShown.snapTo(0f)
        }
    }
}

/**
 * The composer's surface (iOS `ComposerSurface`): a request's [panel] on the bar, autocomplete,
 * the composer and the dictation hold layer.
 */
@Composable
private fun SessionComposerEnv.ComposerSurface(
    panel: (@Composable () -> Unit)?,
    panelExtent: () -> Float,
    panelReveal: () -> Float,
    panelShown: Boolean,
) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(this)
    val dropTarget = rememberComposerDropTarget()
    Box(Modifier.fillMaxWidth()) {
        VoiceComposerSurface(
            modifier = Modifier
                .fillMaxWidth()
                // Under the Living Orb's card it hides, out of reach and out of TalkBack.
                .hiddenUnderFloatingCall(host.floatingCallCovers)
                // iOS `.onDrop(of: TranscriptComposerDrop.acceptedTypes, isTargeted:)`: anywhere on the composer.
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .onGloballyPositioned {
                    view.surfaceBounds = it.boundsInRoot()
                    host.attachmentAnchors.composer = view.surfaceBounds
                }
                .composerSwipes({ current }, density.density)
                .semantics { testTag = "session-composer" },
            panel = panel,
            panelExtent = panelExtent,
            panelReveal = panelReveal,
            panelShown = panelShown,
            controlsTakeTouches = composerPresentation.expanded,
            panelHidden = host.floatingCallCovers,
        ) {
            Box(Modifier.fillMaxWidth()) { Composer() }
        }
        // The drop highlight: decoration, taking no touches (iOS accent stroke, 2 pt, on swap).
        val dropAlpha by animateFloatAsState(
            if (view.isImageDropTargeted) 1f else 0f,
            Motion.swap.animation(if (view.isImageDropTargeted) MotionPhase.Enter else MotionPhase.Exit, reducedMotion),
            label = "composerDropTarget",
        )
        if (dropAlpha > 0f) {
            val accent = OpenCodeTheme.colors.accent
            Box(
                Modifier
                    .matchParentSize()
                    .alpha(dropAlpha)
                    .border(2.dp, accent, RoundedCornerShape(ComposerTunables.cornerRadius.dp)),
            )
        }
        // Above the surface, so its edge never crosses the hold circle.
        val hold = shownDictationHold
        if (hold != null) {
            DictationBarPresence(
                isLeaving = s.dictationHold == null,
                modifier = Modifier.matchParentSize(),
                onLeft = { dictationBarLeft() },
            ) {
                DictationHoldLayer(
                    pose = dictationPose(hold),
                    micFrame = view.dictationMicFrame,
                    barFrame = view.dictationBarFrame,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
}

/**
 * The composer's drop target: what's dropped outside the field (whose own receiver takes drops on
 * it first) goes through [SessionAttachmentImporter.handleComposerDrop]; while a drag is over the
 * composer it shows the drop highlight (iOS `isImageDropTargeted`).
 */
@Composable
private fun SessionComposerEnv.rememberComposerDropTarget(): DragAndDropTarget {
    val activity = LocalActivity.current
    val currentView by rememberUpdatedState(view)
    val currentHost by rememberUpdatedState(host)
    return remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                currentView.isImageDropTargeted = true
            }

            override fun onExited(event: DragAndDropEvent) {
                currentView.isImageDropTargeted = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                currentView.isImageDropTargeted = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                currentView.isImageDropTargeted = false
                val drag = event.toAndroidDragEvent()
                val clip = drag.clipData ?: return false
                // Another app's URIs are readable once the activity asks; held until it goes.
                activity?.requestDragAndDropPermissions(drag)
                val description = drag.clipDescription
                val mimeTypes = if (description == null) emptyList() else (0 until description.mimeTypeCount).map(description::getMimeType)
                return currentHost.importer?.handleComposerDrop(clip, mimeTypes) == true
            }
        }
    }
}

/**
 * iOS `requestPanel(isShown:)`: the docked request's [RequestDockView], capped so the composer
 * stays under the header ([RequestPanelFit]), set on the bar by the surface, which reveals it from
 * the bar up and folds back over it while a leaving request fades in place. Arriving, and as pages
 * and choices change, the surface follows its height (eased); a change the keyboard or the cap
 * made follows at once.
 */
@Composable
private fun SessionComposerEnv.RequestPanelContent(isShown: Boolean, cap: Float?) {
    val dock by store.observe { it.requestDock }
    val context by store.observe { state ->
        if (!state.isAnsweringRequest && state.dockRequestID == null) {
            RequestDockContext(currentSessionID = state.session?.id, draftBecameSessionID = state.draftBecameSessionID, isVisible = state.isVisible)
        } else {
            RequestDockContext(
                currentSessionID = state.session?.id,
                draftBecameSessionID = state.draftBecameSessionID,
                familySessions = state.familySessions,
                transcripts = listOf(state.items) + state.familyTranscriptCache.values,
                isVisible = state.isVisible,
                isDictating = state.hasLiveDictation || state.dictationHold != null,
            )
        }
    }
    val density = LocalDensity.current
    val current by rememberUpdatedState(this)
    val currentCap by rememberUpdatedState(cap)
    var measuredRequestID by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier
            .fillMaxWidth()
            .requestPanelCap(cap?.dp)
            .onGloballyPositioned { current.view.requestPanelBottom = it.boundsInRoot().bottom }
            .onSizeChanged { size ->
                // A leaving panel keeps its height while the surface folds over it.
                val env = current
                val height = with(density) { size.height.toDp().value }
                if (!isShown || env.store.state.dockRequestID == null || height <= 0f) return@onSizeChanged
                env.view.requestPanelIdentity = env.s.taskIdentity
                if (height == env.view.requestPanelHeight) return@onSizeChanged
                // A question folding, under a finger or springing to rest: the surface follows its
                // height frame by frame.
                if (env.view.requestPanelGesture.takeFollows()) {
                    env.view.requestPanelHeight = height
                    env.scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { env.view.requestPanelShown.snapTo(height) }
                    return@onSizeChanged
                }
                val eases = env.view.requestPanelEasing.eases(height, currentCap, keyboardUp = env.messageFieldFocused)
                val wasZero = env.view.requestPanelHeight == 0f
                val grows = height > env.view.requestPanelHeight
                val requestID = env.store.state.dockRequestID
                // Permission columns already animate their own height. Applying a second
                // spring here makes the surface trail the caption and clip it mid-expansion.
                val followsColumn = requestID == measuredRequestID &&
                    env.store.state.requestDock.permissionRequests.any { it.id == requestID }
                measuredRequestID = requestID
                env.view.requestPanelHeight = height
                env.scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    when {
                        env.reducedMotion || !eases -> env.view.requestPanelShown.snapTo(height)
                        wasZero -> env.view.requestPanelShown.animateTo(height, RequestPanelReveal.animation(opening = true, reducedMotion = false))
                        followsColumn -> env.view.requestPanelShown.snapTo(height)
                        else -> env.view.requestPanelShown.animateTo(
                            height,
                            Motion.layout.animation(if (grows) MotionPhase.Enter else MotionPhase.Exit),
                        )
                    }
                    if (followsColumn && !env.reducedMotion) {
                        // The surface reports only the bar's size, so child remeasurement
                        // cannot propagate to the capsule's reserved room. Queue its state
                        // observers (and the reveal layer) before this layout pass draws.
                        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                    }
                }
            },
    ) {
        RequestDockView(
            dock = dock,
            context = context,
            isKeyboardUp = messageFieldFocused,
            send = { store.send(Action.RequestDock(it)) },
            gesture = view.requestPanelGesture,
        )
    }
}

/**
 * The composer's swipes (iOS's two `simultaneousGesture(DragGesture)`s): a downward drag that began
 * focused dismisses the keyboard; an upward one over the field's band while unfocused raises it.
 * Read in the final pass, so controls that consumed the touch (a held mic, +) keep it.
 */
private fun Modifier.composerSwipes(envNow: () -> SessionComposerEnv, density: Float): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val env = envNow()
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        val ownership = env.view.capsuleSwipeOwnership
        ownership.ended()
        val origin = env.view.surfaceBounds.topLeft
        val start = down.position + origin
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var acted = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val translation = (change.position - down.position) / density
                if (!acted && abs(translation.x) + abs(translation.y) >= 10f) {
                    val velocity = tracker.calculateVelocity()
                    // UIKit's projection at the normal deceleration rate (≈ v × 0.5 s).
                    val predicted = translation + Offset(velocity.x, velocity.y) / density * 0.5f
                    val focused = env.messageFieldFocused
                    val beganFocused = ownership.beganFocused(start, focused)
                    // A touch the field's selecting owns (a handle, a drag from the selection, a
                    // rest first) stays the text's, even just outside the field.
                    val input = env.host.fieldLink.input
                    val yields = ownership.yields(start, focused, textClaims = input?.isSwipeYieldedToText == true) {
                        env.composerSwipeYields(start) || input?.selectionClaims(start) == true || change.isConsumed
                    }
                    if (beganFocused) {
                        if (ComposerSwipeDown.shouldDismissKeyboard(translation, predicted, isFocused = focused, isHoldingMic = yields)) {
                            acted = true
                            env.dismissKeyboard()
                        }
                    } else if (env.composerSwipeUpBandContains(start) &&
                        // Over the field itself, the field's own swipe raises the keyboard.
                        !env.view.textFieldBounds.contains(start) &&
                        ComposerSwipeDown.shouldFocusKeyboard(
                            translation,
                            predicted,
                            isFocused = focused,
                            isHoldingMic = yields,
                            minimumDistance = ComposerTunables.swipeUpDistance,
                            minimumFlickVelocity = ComposerTunables.swipeUpVelocity,
                        )
                    ) {
                        acted = true
                        env.focusComposerFromSwipe()
                    }
                }
                if (!change.pressed) break
            }
        } finally {
            ownership.ended()
        }
    }
}

// endregion

// region Composer

/** iOS `composer`: the fold layout of attachments, the field and its controls, and status lines. */
/**
 * iOS `composerTapped(recording:)`: a tap on the composer around its text focuses the text.
 * Folded while dictating, the field is out of sight, so the keyboard stays down; a tap that's a
 * double tap's second half on the text does nothing else.
 */
private fun SessionComposerEnv.composerTapped(recording: Boolean) {
    if (recording) return
    if (composerTextTakesDoubleTap()) return
    focus()
}

@Composable
private fun SessionComposerEnv.Composer() {
    val dismissLabel = stringResource(CoreR.string.common_dismiss)
    val (composerExpanded, composerControls) = composerPresentation
    val recording = s.dictationHold != null
    val bar = s.requestBar
    val density = LocalDensity.current
    // Dictating folds the composer to its control row: the edge glides down over what's above it.
    // Controls the row hides leave once the fold has landed: its own completion, so slow motion
    // and a reversal mid-fold hold them.
    val fold by animateFloatAsState(
        if (recording) 1f else 0f,
        dictationFoldAnimation(recording),
        label = "composerFold",
        finishedListener = { dictationFoldLanded(recording = it == 1f) },
    )
    val rowAlpha by animateFloatAsState(
        if (recording) 0f else 1f,
        if (recording) Motion.swap.animation(MotionPhase.Exit, reducedMotion)
        else Motion.swap.animation(reduceMotion = reducedMotion, delaySeconds = Motion.contentDelay),
        label = "composerRowFade",
    )
    val bodyLine = with(density) { OpenCodeTextStyles.body.lineHeight.toDp().value }
    // The field's one line (as `ComposerTextInput` measures it): its floor when short of room.
    val fieldLine = with(density) {
        val style = ComposerTunables.textStyle.textStyle
        if (style.lineHeight.isSp) style.lineHeight.toDp().value else style.fontSize.toDp().value * 1.3f
    }
    val morph = LocalComposerKeyboardMorph.current
    // The keyboard morph moves the row frame by frame; a size animation on top would trail the keys.
    val morphMoving by remember(morph) {
        derivedStateOf { morph != null && (morph.keyboard.isAnimating || morph.expansion > 0f && morph.expansion < 1f) }
    }
    // A drawing added from the full-screen viewer opens the row on its flight's spring.
    val presenter = LocalMediaPresenter.current
    val hasAttachments = hasComposerAttachments
    val handOffReveal = remember(hasAttachments) {
        Animatable(if (hasAttachments && presenter?.isPresenting == true && !reducedMotion) 0f else 1f)
    }
    LaunchedEffect(handOffReveal) {
        if (handOffReveal.value < 1f) handOffReveal.animateTo(1f, Motion.flight.animation(reduceMotion = reducedMotion))
    }
    val attachmentReveal = handOffReveal.value
    val sendReset = host.sendReset
    sendReset.Animate()
    val cardHeight = remember { ComposerCardHeight() }
    val cardScope = rememberCoroutineScope()
    val current by rememberUpdatedState(this)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ComposerTunables.cornerRadius.dp))
            .pointerInput(recording) {
                // Also the gap between the text and the row: Compose hits one point and the
                // pickers' targets end at the glass's bottom edge, so the gap is this tap's (iOS
                // `composerTextReach`).
                detectTapGestures {
                    current.composerTapped(recording)
                }
            }
            .composerSendReset(sendReset)
            .composerCardHeight(
                cardHeight,
                spec = when {
                    reducedMotion || (morphMoving && !sendReset.isResetting) -> null
                    sendReset.isResetting -> SEND_RESET_SLIDE.spec(visibilityThreshold = 1)
                    else -> MotionSpring(0.25).spec(visibilityThreshold = 1)
                },
                animatesInput = sendReset.isResetting,
                scope = cardScope,
            )
            // iOS `ComposerKeyboardSpacing(top: true, recording:)`: the top inset is taken back as
            // the row opens, unless folded for dictation.
            .composerKeyboardSpacing(
                morph,
                top = true,
                innerVertical = ComposerTunables.innerVertical.dp,
                bottomGap = ComposerTunables.bottomGap.dp,
                keyboardGap = ComposerTunables.keyboardGap.dp,
                fold = { fold },
            )
            .padding(horizontal = ComposerTunables.innerHorizontal.dp)
            .padding(bottom = ComposerTunables.innerVertical.dp)
            .semantics {
                // Answering, escape from the bar's controls dismisses as the panel's × does.
                if (s.isAnsweringRequest) {
                    customActions = listOf(CustomAccessibilityAction(dismissLabel) { store.send(Action.requestDismissTapped); true })
                }
            },
    ) {
        ComposerFoldLayout(foldProgress = fold, attachmentRevealProgress = attachmentReveal) {
            val row = host.pendingAttachmentsRow
            // Reserved photo slots (the New Plus Menu's picks still decoding) count too.
            if (row != null && hasComposerAttachments) {
                Box(
                    Modifier
                        .composerFolds()
                        .alpha(rowAlpha)
                        .then(if (recording) Modifier.clearAndSetSemantics { }.blockTouches() else Modifier),
                ) { row(composerExpanded) }
            }
            Box {
                ComposerLayout(
                    // iOS `KeyboardDrivenComposerLayout`: the row's expansion frame by frame.
                    progress = { morph.layoutProgress(inline = bar != null) },
                    inline = bar?.let { ComposerLayoutInline(hasLeading = it is RequestBar.Answer, lineHeight = bodyLine) },
                    inputLineHeight = fieldLine,
                    input = {
                        Box(
                            Modifier
                                .alpha(rowAlpha)
                                .then(if (recording) Modifier.clearAndSetSemantics { } else Modifier)
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints)
                                    cardHeight.input = placeable.height
                                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                                },
                        ) {
                            ComposerInput(composerExpanded, recording, bar)
                        }
                    },
                    leading = {
                        if (bar != null) {
                            AnswerLeadingControls(bar, recording, rowAlpha)
                        } else {
                            ComposerLeadingControls(composerExpanded, recording)
                        }
                    },
                    trailing = {
                        // Everything at the trailing edge travels as one group: Send/Stop rightmost, dictation to its left.
                        Box(
                            Modifier
                                .onGloballyPositioned { sendReset.trailingBounds = it.boundsInRoot() }
                                .alpha(rowAlpha)
                                // The bar replaces these, the run's Stop included.
                                .then(if (recording) Modifier.clearAndSetSemantics { } else Modifier),
                        ) {
                            val dictation = composerControls.dictation
                            when {
                                dictation != null -> DictationTrailingControls(dictation)
                                bar != null -> AnswerTrailingControls(bar)
                                else -> Row(
                                    horizontalArrangement = Arrangement.spacedBy(ComposerTunables.trailingSpacing.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // Collapsed mid-run: the trailing control is Stop, so dictation keeps a home
                                    // beside it; expanded it stays there too, so it never jumps between groups.
                                    if (composerControls.secondaryMic != ComposerControls.MicPlacement.none) SecondaryMicButton()
                                    TrailingButton(composerControls.trailing)
                                }
                            }
                        }
                    },
                )
                val hold = shownDictationHold
                if (hold != null) {
                    DictationBarPresence(
                        isLeaving = !recording,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                        onLeft = { dictationBarLeft() },
                    ) {
                        var barOrigin by remember { mutableStateOf(Offset.Zero) }
                        val mic = view.dictationMicFrame
                        val micCenter = if (mic == Rect.Zero || view.dictationBarFrame == Rect.Zero) {
                            null
                        } else {
                            Offset((mic.center.x - barOrigin.x) / density.density, (mic.center.y - barOrigin.y) / density.density)
                        }
                        DictationScopeBar(
                            pose = dictationPose(hold),
                            // The bar's ↑ answers the request; it never steers.
                            isRunning = s.isRunning && bar == null,
                            micCenter = micCenter,
                            // The words land in the draft; the keyboard stays as it was.
                            onStop = { store.send(Action.dictationInsertTapped) },
                            onSend = { store.send(if (it == ai.opencode.server.model.OpenCodeDelivery.Queue) Action.dictationQueueTapped else Action.dictationSendTapped) },
                            modifier = Modifier
                                .fillMaxWidth()
                                // Centred on the 44 dp control row (the bar's targets are 48 dp).
                                .offset(y = 2.dp)
                                .onGloballyPositioned {
                                    view.dictationBarFrame = it.boundsInRoot()
                                    barOrigin = view.dictationBarFrame.topLeft
                                },
                        )
                    }
                }
            }
            if (s.isPreparingDictation && !recording) {
                Row(
                    Modifier.composerFolds().padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.session_dictation_preparing), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
                }
            } else {
                val error = s.dictationError
                if (error != null) {
                    OpenCodeErrorText(error, Modifier.composerFolds().padding(horizontal = 14.dp))
                }
            }
        }
    }
}

/**
 * The card's height, animated as `animateContentSize(alignment = BottomStart)` would, except the
 * field's own growth (a line added or removed), which lands at once.
 */
private class ComposerCardHeight {
    /** The input region's height, written as the card measures it. */
    var input = 0
    private var lastInput = 0
    /** How much of the height the field's growth added at once, outside the animation. */
    private var unanimated = 0
    private var animation: Animatable<Int, AnimationVector1D>? = null

    /** The height to show for content [height] tall; a null [spec] shows it at once. */
    fun measure(height: Int, spec: AnimationSpec<Int>?, animatesInput: Boolean, scope: CoroutineScope): Int {
        if (!animatesInput) unanimated += input - lastInput
        lastInput = input
        val target = height - unanimated
        val current = animation
        if (spec == null) {
            animation = null
            return height
        }
        if (current == null) {
            animation = Animatable(target, Int.VectorConverter)
            return height
        }
        if (current.targetValue != target) {
            scope.launch { current.animateTo(target, spec) }
        }
        return current.value + unanimated
    }
}

private fun Modifier.composerCardHeight(
    cardHeight: ComposerCardHeight,
    spec: AnimationSpec<Int>?,
    animatesInput: Boolean,
    scope: CoroutineScope,
): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = constraints.constrainHeight(cardHeight.measure(placeable.height, spec, animatesInput, scope))
        layout(placeable.width, height) { placeable.place(0, height - placeable.height) }
    }

/**
 * The placeholder over the field: one at a time, hidden without animation when the draft has
 * text, so a programmatic insert (Quote, Share, paste) never cross-fades “Message” over the words.
 * The field is :feature:composer's [ComposerTextInput], bound to the draft and to
 * [SessionComposerHost.messageFieldFocused].
 */
@Composable
private fun SessionComposerEnv.ComposerInput(expanded: Boolean, recording: Boolean, bar: RequestBar?) {
    val placeholder = bar?.placeholder ?: stringResource(R.string.session_message_placeholder)
    // iOS `ComposerFieldScope`: the text is read here alone, so a keystroke recomposes
    // the field and not the composer around it.
    val draft by store.observe { it.draft }
    // iOS `KeyboardDrivenTextInset`: the keyboard morph scrubs the expanded top inset (none
    // inline); without a mounted morph the field follows [expanded].
    val topInsetFraction = LocalComposerKeyboardMorph.current?.layoutProgress(inline = bar != null)
    Box(Modifier.onGloballyPositioned { coordinates ->
        val surface = view.surfaceBounds
        val bounds = coordinates.boundsInRoot()
        view.textFieldBounds = if (surface == Rect.Zero) Rect.Zero else bounds
    }) {
        if (s.draftShape.isEmpty) {
            Text(
                placeholder,
                style = ComposerTunables.textStyle.textStyle,
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
                modifier = Modifier
                    .padding(top = ComposerTunables.expandedTopInset.dp * (topInsetFraction ?: if (expanded) 1f else 0f))
                    .clearAndSetSemantics { },
            )
        }
        val takesPaste = s.takesPasteAttachments
        ComposerTextInput(
            text = draft,
            // The reducer's draft now, not the snapshot this composition captured.
            currentText = { store.state.draft },
            focusRequest = s.composerFocusRequest,
            // Folded while dictating: keys typed into the hidden field don't reach the draft.
            onTextChange = { new -> onDraftEdited(new, recording) },
            // A keystroke is one action: the draft and the caret it left.
            onEdit = { new, caret -> onDraftEdited(new, recording, caret) },
            isFocused = host.messageFieldFocused,
            onFocusedChange = { host.messageFieldFocused = it },
            expanded = expanded,
            textStyle = ComposerTunables.textStyle.textStyle,
            topInset = ComposerTunables.expandedTopInset.dp,
            topInsetFraction = topInsetFraction,
            onImagePaste = { uris ->
                // An answer or a reason takes text, never an attachment.
                if (store.state.takesPasteAttachments) host.importer?.startImageImport(uris.map(ComposerImageSource::ItemProvider))
            },
            // Null while answering a request: the paste goes in as text.
            onLongTextPaste = if (takesPaste) { text -> store.send(Action.attachmentAdded(PastedText.attachment(text))) } else null,
            onFileDrop = if (takesPaste) { uris -> host.importer?.startFileImport(uris.map(ComposerFileSource::Url)) } else null,
            onDismissKeyboard = dismissKeyboard,
            onFocusKeyboard = ::focusComposerFromSwipe,
            onCommandReturn = {
                if (!recording) {
                    if (s.isAnsweringRequest) store.send(Action.CommandReturnTapped)
                    else if (!host.isImportingImages) sendMessage(Action.CommandReturnTapped)
                }
            },
            onNonSendNewline = { text -> if (!recording) store.send(Action.NonSendNewlineInserted(text)) },
            onDropTargetedChange = { view.isImageDropTargeted = it },
            returnKeySends = returnKeySends,
            autocompleteBridge = view.autocompleteBridge,
            lineLimit = if (bar == null) null else 4,
            accessibilityLabel = bar?.fieldLabel ?: stringResource(R.string.session_message_placeholder),
            isHoldingMic = s.dictationHold != null,
            fieldLink = host.fieldLink,
            // One Picker in the keyboard's place keeps the field from taking it back.
            refusesFocus = { host.modelPanels?.onePicker?.isOpen == true },
            // The + menu draws over the field, which keeps the keyboard.
            isCovered = host.isAttachmentMenuOpen,
        )
    }
}

/**
 * iOS `$store.draft` and its `onChange`: vertical fields swallow the send key as a newline; a single
 * appended "\n" is the return key (pastes never match this shape).
 */
private fun SessionComposerEnv.onDraftEdited(
    new: String,
    recording: Boolean,
    caret: ComposerAutocompleteAction.CaretUpdate? = null,
) {
    // Folded while dictating: keys typed into the hidden field don't reach the draft.
    if (recording) return
    val old = store.state.draft
    if (new == old) return
    if (caret == null) {
        store.send(Action.Binding(BindingAction.Draft(new)))
    } else {
        store.send(Action.Autocomplete(ComposerAutocompleteAction.TextEdited(new, caret)))
    }
    val state = store.state
    if (!returnKeySends || state.nonSendNewlineDraft == new || new != old + "\n") return
    if (state.isAnsweringRequest) store.send(Action.ReturnKeyTapped) else sendMessage(Action.ReturnKeyTapped)
}

// endregion

/** iOS `.snappy(duration: 0.35)`, without its overshoot: a bounce here bounced the transcript. */
