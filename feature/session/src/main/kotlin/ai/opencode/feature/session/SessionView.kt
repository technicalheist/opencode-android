@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeButtonMenu
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.inContainer
import ai.opencode.feature.composer.composerWindowTouches
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.experiments.key
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticEffect
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Store
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.store.scope
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.ProviderIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.composer.AttachmentMenuDismissArea
import ai.opencode.feature.composer.ComposerDismissTarget
import ai.opencode.feature.composer.AttachmentMenuItem
import ai.opencode.feature.composer.AttachmentPickerButton
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerPhotoViewer
import ai.opencode.feature.composer.ComposerPickers
import ai.opencode.feature.composer.LocationClientError
import ai.opencode.feature.composer.LocationPermissionPrompt
import ai.opencode.feature.composer.PastedTextViewer
import ai.opencode.feature.composer.RequestDockContext
import ai.opencode.feature.composer.RequestReviewSheet
import ai.opencode.feature.composer.composerKeyboardStepHold
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.feature.media.MediaPresenter
import ai.opencode.feature.media.FullscreenViewer
import ai.opencode.feature.media.FullscreenViewerFeature
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionHeaderBackdrop
import ai.opencode.feature.session.header.SessionHeaderStyle
import ai.opencode.feature.voice.FloatingCallCenter
import ai.opencode.feature.voice.FloatingCallRestArea
import ai.opencode.feature.voice.FloatingCallTunables
import ai.opencode.feature.voice.LocalDotScopeCovered
import ai.opencode.feature.voice.VoiceStoreOf
import ai.opencode.feature.voice.floatingCallComposer
import ai.opencode.feature.voice.floatingCallComposerColumn
import ai.opencode.feature.voice.holdsClientToolCard
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalView
import ai.opencode.feature.composer.EscapeHandler
import ai.opencode.core.ui.accessibilityFocusTarget
import ai.opencode.core.ui.AccessibilityFocusTarget
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.mutableLongStateOf
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

// Port of iOS `Session/SessionView.swift`: the session screen scaffold. The transcript runs
// full-bleed under the header and the composer; the header's backdrop and controls sit over its
// top, the composer (`SessionComposer`, SessionView+Composer's port) over its bottom, and the
// background-work capsule and panel over both. Sheets follow `activePicker`; lifecycle changes
// reach the reducer as actions (no side effects here), from `SessionLifecycle`
// (`SessionViewLifecycle.kt`).
//
// iOS's per-view gesture state (the model list, dictation holds, the
// request panel's room) belongs to the composer and picker views on Android, each in its own file.

/**
 * One session screen (or a new session's draft): header, transcript, composer, sheets.
 *
 * @param navigationIcon the drawer button Home supplies (iOS `drawerControl`); unused while
 *   [onBack] or [onCatchUpOpen] is set.
 * @param listContext what Home knows about this session (project, pin, unread, mute).
 * @param onConnectionAction Server Flow: the connection pill was acted on.
 * @param onBack set on a subagent pushed over its parent: the header trades the drawer button for Back.
 * @param onCatchUpOpen set while the session is a catch-up card: the title leaves Catch Up for the session.
 * @param hostedCall the call its root owns, on a pushed subagent (iOS `hostedCall`): its cards and
 *   bar show here, as the screen's own call would.
 */
@Composable
fun SessionView(
    store: StoreOf<State, Action>,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    listContext: SessionListContext = SessionListContext(),
    onConnectionAction: (ServerConnectionNotice.Action) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onCatchUpOpen: (() -> Unit)? = null,
    hostedCall: VoiceStoreOf? = null,
    /** Inert Catch Up preview behind the active card: renders visible chrome/transcript only, without sheet/picker prewarms. */
    isInertPreview: Boolean = false,
) {
    val scrollEdgeBridge = rememberSaveable(saver = SessionScrollEdgeBridge.Saver) { SessionScrollEdgeBridge() }
    val backgroundWorkAnchor = rememberBackgroundWorkAnchor()
    // What the composer shares with the screen around it (the capsule's place, jump to latest, the + menu).
    // The pill a session opens with (a cold launch's reconnect) is there from its first frame, as
    // iOS's is, rather than rising in over the last row a frame later.
    // So is the jump button a recreated screen showed (F-38).
    val composerHost = remember {
        SessionComposerHost().apply {
            connection = listContext.connection
            showsJumpButton = scrollEdgeBridge.reportedShowsJumpButton
        }
    }
    val importer = rememberSessionAttachmentImporter(store)
    val colors = OpenCodeTheme.colors
    val density = LocalDensity.current
    val attachmentMenu = rememberSessionAttachmentMenuState()

    // The header controls' laid-out height (dp); the backdrop follows it.
    // Saved with the composer's height (the bridge), so a recreated activity's first frame has the transcript's top inset.
    var headerHeight by rememberSaveable { mutableFloatStateOf(SessionHeaderStyle.DEFAULT_HEADER_HEIGHT) }
    var showsBackgroundWork by remember { mutableStateOf(false) }

    val headerEnvironment = SessionHeaderStyle.Environment(
        increaseContrast = colors.highContrast,
        isDark = colors.isDark,
        sizeCategory = density.fontScale.toString(),
        headerHeight = headerHeight,
    )
    val resolvedHeader = remember(headerEnvironment) { HeaderBlurTunables.resolved(headerEnvironment) }

    if (!isInertPreview) {
        SessionLifecycle(store)
        SessionVoiceLabTriggers(store, isEnabled = onCatchUpOpen == null)
    }
    val callStore = store.rememberCallStore(hostedCall)
    val ownsCall by store.observe { it.voice != null }
    if (!isInertPreview) {
        SessionCallTasks(store, callStore, hasCall = ownsCall || hostedCall != null, isCatchUpCard = onCatchUpOpen != null)
    }
    val isCovered by store.observe { it.isCovered }
    val isTranscriptCovered by store.observe { it.isTranscriptCovered }
    // iOS `floatingCallCoverage(sessionLifecycle)`: what the Living Orb's card covers gives way.
    if (!isInertPreview) {
        SessionFloatingCallCoverage(store, callStore, composerHost, attachmentMenu)
    }
    // iOS `clearOverClientToolCard()`: an agent's screen request or link came up, so the keyboard
    // and the composer's panels would stand over its card.
    val holdsClientToolCard by remember(callStore) { callStore?.let { call -> call.states.map { it.holdsClientToolCard } } ?: flowOf(false) }
        .collectAsState(false)
    LaunchedEffect(holdsClientToolCard) {
        if (!holdsClientToolCard) return@LaunchedEffect
        composerHost.dismissComposerKeyboard()
        // Closing the + menu mustn't bring the keyboard back.
        attachmentMenu.attachmentMenuRestoresFocus = false
        showsBackgroundWork = false
        attachmentMenu.closeAttachmentMenu()
        composerHost.modelPanels?.closeModelPanels()
    }

    // iOS `orbClearance`: the room a resting orb takes above the composer, so it covers none of
    // the transcript's end. Beside the composer's column or dragged well up it makes none; only
    // the screen showing makes room.
    // The composer's frame lives in `orbGeometry` and is read only by `OrbClearance`, during a
    // call, so the composer riding the keyboard doesn't recompose SessionView (iOS d0b24aaa).
    val orbGeometry = remember { OrbClearanceGeometry() }
    OrbClearance(callStore, orbGeometry, isCovered, composerHost)

    val work by store.observe(BackgroundWorkModel::from)
    val agentScope by store.observe { "${it.server.persistenceID}|${it.session?.id ?: "draft"}" }
    val errorMessage by store.observe { it.errorMessage }
    val identity by store.observe { it.transcriptIdentity }
    val showsSessionMedia by store.observe { it.sessionMediaFilter != null }
    // Read in composition, not in the SideEffect: SideEffect reads aren't observed, so an import
    // finishing would leave the host's copy stale.
    val isImportingImages = importer.isLoadingImages
    val isImportingAttachments = isImportingImages || importer.isLoadingFiles

    SideEffect {
        composerHost.backgroundWorkAnchor = backgroundWorkAnchor
        composerHost.isCatchUpCard = onCatchUpOpen != null
        composerHost.connection = listContext.connection
        composerHost.onConnectionAction = onConnectionAction
        composerHost.onJumpToLatest = { scrollEdgeBridge.jumpRequest += 1 }
        composerHost.borrowedDrag = scrollEdgeBridge.borrowedDrag
        composerHost.isImportingAttachments = isImportingAttachments
        composerHost.isImportingImages = isImportingImages
        composerHost.importer = importer
        composerHost.headerHeight = headerHeight
        if (composerHost.pendingAttachmentsRow == null) composerHost.pendingAttachmentsRow = { expanded -> SessionPendingAttachments(store, expanded) }
    }
    if (!isInertPreview) {
        SessionPermissionPrompts(isCovered)
    }

    SessionMediaHost {
    CompositionLocalProvider(
        LocalAgentActivityScope provides agentScope,
        LocalSessionComposerHost provides composerHost,
        // Covered (a pushed subagent, a browser page), every scope underneath stops drawing.
        LocalDotScopeCovered provides isCovered,
        LocalSessionAttachmentMenu provides attachmentMenu,
    ) {
        // The + menu draws above the background-work capsule (iOS: the menu's glass covers it).
        // iOS `ComposerWindowTouchObserver` on the window: the screen's latest touch-down, for the
        // model chip's double-tap check.
        Box(modifier.composerWindowTouches(composerHost.fieldLink.windowTouches)) {
        BackgroundWorkPresentation(
            anchor = backgroundWorkAnchor,
            sessionID = work.sessionID,
            agents = work.agents,
            shells = work.shells,
            scripts = work.scripts,
            onStopScript = { store.send(Action.BackgroundScriptStopTapped(it)) },
            onStopSubagent = { store.send(Action.BackgroundSubagentStopTapped(it)) },
            onStopShell = { store.send(Action.BackgroundShellStopTapped(it)) },
            stoppingIDs = work.stoppingIDs,
            waitingSessionIDs = work.waitingSessionIDs,
            probe = work.probe,
            isPresented = showsBackgroundWork,
            onPresentedChange = { showsBackgroundWork = it },
            onOpenSubagent = { store.send(Action.SubagentTapped(it)) },
            onOpenShell = { store.send(Action.ShellOutputTapped(it)) },
            isCovered = composerHost.composerPanelCovers,
            isPaused = isTranscriptCovered,
            inCall = work.inCall,
            transcript = scrollEdgeBridge.borrowedDrag,
            // The capsule draws above the + menu's dismiss area: tapping it closes the menu.
            // iOS: typing ends, and the capsule draws above the menu's dismiss area: tapping it closes the menu.
            onToggle = {
                composerHost.dismissComposerKeyboard()
                attachmentMenu.closeAttachmentMenu()
            },
            // TalkBack reads the header, then the transcript, then the background-work capsule, then
            // the composer, as VoiceOver reads iOS's top overlay first and the rest top to bottom: the
            // header draws over the transcript, so it came after it (QA P-06). The group is the
            // presentation's, not the screen's, so the capsule drawn over the screen sits in it by
            // its place above the composer (iOS `SessionView+Composer.swift:209-241`), not after
            // everything (QA AB-02).
            // The screen leaves the accessibility tree while Session Media covers it.
            modifier = Modifier
                .semantics { isTraversalGroup = true }
                .then(if (showsSessionMedia) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            Box(Modifier.fillMaxSize().background(colors.canvas)) {
                ComposedAfterComposer {
                    // Read while measuring, after the header has laid out: the first frame rests the
                    // transcript under the header's real height, not the default's.
                    val measuredHeader = headerEnvironment.copy(headerHeight = headerHeight)
                    val transcriptTop = remember(measuredHeader) { HeaderBlurTunables.resolved(measuredHeader).transcriptTop }
                    if (errorMessage != null) {
                        LoadFailure(errorMessage.orEmpty(), onRetry = { store.send(Action.Task) })
                    } else {
                        // A draft's transcript carries on into the session it became, so the first
                        // send's bubble and Working never remount mid-flight.
                        key(identity) {
                            SessionTranscriptView(store, scrollEdgeBridge, transcriptTop, holdsWorking = listContext.connection != null)
                        }
                    }
                }
                SessionHeaderBackdrop(
                    backdrop = resolvedHeader.backdrop,
                    appBlur = resolvedHeader.appBlur,
                    header = resolvedHeader.header,
                    safeTop = WindowInsets.statusBarsIgnoringVisibility.inContainer().asPaddingValues().calculateTopPadding(),
                    scrolledUnderHeader = { scrollEdgeBridge.scrolledUnderHeader },
                    transcriptPlaced = { scrollEdgeBridge.hasPlacedTranscript },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                SessionBottomScrollEdge(
                    surfaceTopInWindow = { composerHost.surfaceTopInWindow },
                    modifier = Modifier.matchParentSize(),
                )
                // Behind the composer, so its × stays reachable: a tap anywhere else or a downward
                // drag dismisses the menu, or Composer Autocomplete's suggestions.
                if (!isInertPreview) {
                    SessionComposerDismissArea(store, composerHost)
                }
                SessionHeaderControls(
                    store = store,
                    listContext = listContext,
                    navigationIcon = navigationIcon,
                    onBack = onBack,
                    onCatchUpOpen = onCatchUpOpen,
                    onHeaderHeight = { headerHeight = it.roundToInt().toFloat() },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
                        .semantics {
                            isTraversalGroup = true
                            traversalIndex = -1f
                        },
                )
                // One Picker's surface grows out of the composer, over the transcript and under the composer.
                if (!isInertPreview) {
                    SessionOnePickerBackdrop(composerHost, Modifier.matchParentSize())
                }
                val statusBar = WindowInsets.statusBarsIgnoringVisibility.inContainer()
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.bottomSafeArea)
                        .imePadding()
                        .padding(bottom = if (onCatchUpOpen == null) 0.dp else CATCH_UP_COMPOSER_CLEARANCE.dp)
                        // The room between the status bar and the keyboard (iOS `requestPanelInputs.room`):
                        // the composer never grows past it. Short of room (landscape with the keys up, a
                        // split screen) the header gives way, then the field's lines, then the
                        // attachments (`ComposerFoldLayout`), never the control row.
                        .layout { measurable, constraints ->
                            val room = if (constraints.hasBoundedHeight) {
                                (constraints.maxHeight - statusBar.getTop(this)).coerceAtLeast(constraints.minHeight)
                            } else {
                                constraints.maxHeight
                            }
                            val placeable = measurable.measure(constraints.copy(maxHeight = room))
                            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                        }
                        // The readable column (iOS `readableColumn()`): 720 wide at most.
                        .widthIn(max = TranscriptMetrics.readableColumnWidth)
                        // iOS `ComposerKeyboardStepHold`: the composer glides after a docked IME's unanimated step.
                        .composerKeyboardStepHold()
                        .onSizeChanged { scrollEdgeBridge.composerHeight = with(density) { it.height.toDp().value } }
                        // The resting orb's room: the transcript's end rises above it.
                        .orbClearancePadding(composerHost)
                        // The composer without that room, for where the orb rests.
                        .onGloballyPositioned {
                            val b = it.boundsInWindow()
                            val d = density.density
                            val frame = Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
                            if (orbGeometry.composer != frame) orbGeometry.composer = frame
                        }
                        // A floating call rests above the composer.
                        .floatingCallComposer()
                        // The column itself, not the pane: whether the call's card covers it.
                        .then(if (!isCovered) Modifier.floatingCallComposerColumn() else Modifier),
                ) {
                    SessionCallCards(callStore)
                    SessionComposer(store, Modifier.modelPanelsComposer(composerHost), callStore = callStore)
                }
                if (!isInertPreview) {
                    RequestPanelRoomProbe(composerHost, Modifier.matchParentSize())
                    SessionModelPickerOverlays(store, headerHeight)
                    QuoteCommentOverlay(store, Modifier.matchParentSize())
                }
            }
        }
        if (!isInertPreview) {
            SessionAttachmentMenu(store, composerHost, importer, attachmentMenu)
            SessionMediaPage(store, Modifier.matchParentSize())
        }
        }
        if (!isInertPreview) {
            SessionSheets(store, listContext)
            LocationErrorAlert(store)
            SessionAttachmentAlerts(importer)
        }
    }
    }
}

/** iOS `CatchUpMetrics.composerClearance.bottom`. */
internal const val CATCH_UP_COMPOSER_CLEARANCE = 12f

/**
 * What the composer rests on: the navigation bar, but at least 8 dp. Android-only: some phones
 * (HyperOS with the gesture hint hidden) report a 0 navigation bar, where iOS always has a safe area.
 */
internal val WindowInsets.Companion.bottomSafeArea: WindowInsets
    @Composable get() = navigationBars.union(WindowInsets(bottom = 8.dp))

/**
 * iOS's soft scroll edge under the composer: rows fade into the canvas over the corner radius
 * below the composer surface's top, so nothing shows around its corners, beside it or under the
 * navigation bar.
 */
@Composable
private fun SessionBottomScrollEdge(surfaceTopInWindow: () -> Float, modifier: Modifier) {
    val canvas = OpenCodeTheme.colors.canvas
    var originInWindow by remember { mutableFloatStateOf(0f) }
    Spacer(
        modifier
            .clearAndSetSemantics {}
            .onGloballyPositioned { originInWindow = it.boundsInWindow().top }
            .drawBehind {
                val surfaceTop = surfaceTopInWindow()
                if (surfaceTop.isNaN()) return@drawBehind
                val fadeTop = surfaceTop - originInWindow
                if (fadeTop <= 0f || fadeTop >= size.height) return@drawBehind
                val solidTop = fadeTop + ComposerTunables.cornerRadius.dp.toPx()
                // Eases out: the corner curves away fastest just under the top edge, so a row
                // beside it is mostly covered within the first few dp.
                val stops = arrayOf(
                    0f to canvas.copy(alpha = 0f),
                    0.3f to canvas.copy(alpha = 0.85f),
                    1f to canvas,
                )
                drawRect(
                    brush = Brush.verticalGradient(*stops, startY = fadeTop, endY = solidTop),
                    topLeft = Offset(0f, fadeTop),
                    size = Size(size.width, size.height - fadeTop),
                )
            },
    )
}

/** What the background-work panel reads from the session. */
@Immutable
private data class BackgroundWorkModel(
    val sessionID: String?,
    val agents: List<OpenCodeSession>,
    val shells: List<OpenCodeShell>,
    val scripts: List<BackgroundScript>,
    val stoppingIDs: Set<String>,
    val waitingSessionIDs: Set<String>,
    val probe: ShellGlyphProbe?,
    val inCall: Boolean,
) {
    companion object {
        fun from(state: State) = BackgroundWorkModel(
            sessionID = state.session?.id,
            agents = state.activeSubagents,
            shells = state.backgroundShells,
            scripts = state.backgroundScripts,
            stoppingIDs = state.stoppingIDs,
            waitingSessionIDs = state.waitingSessionIDs,
            probe = state.shellGlyphProbe,
            inCall = state.voice != null,
        )
    }
}

/** iOS `OrbClearanceGeometry`: the composer's frame in the window, in dp, for where the orb rests. */
private class OrbClearanceGeometry {
    var composer by mutableStateOf(Rect.Zero)
}

/**
 * iOS `orbClearance`: the room a resting orb takes above the composer, so it covers none of the
 * transcript's end. Beside the composer's column or dragged well up it makes none; only the screen
 * showing makes room. A leaf, so following the composer recomposes only this.
 */
@Composable
private fun OrbClearance(
    callStore: VoiceStoreOf?,
    geometry: OrbClearanceGeometry,
    isCovered: Boolean,
    host: SessionComposerHost,
) {
    val floatingCallCenter = remember { FloatingCallCenter.shared }
    val callLive by remember(callStore) { callStore?.let { call -> call.states.map { !it.phase.isTerminal } } ?: flowOf(false) }
        .collectAsState(false)
    var orbRoom = 0f
    if (!isCovered && callLive) {
        val composer = geometry.composer
        val orb = floatingCallCenter.restingOrb(composerTop = composer.top)
        if (orb != null) {
            orbRoom = FloatingCallRestArea.clearance(orb, composer, FloatingCallTunables.composerGap.value.toFloat())
        }
    }
    val reducedMotion = rememberReduceMotion()
    val orbClearance by animateFloatAsState(
        orbRoom,
        Motion.layout.animation(if (orbRoom > 0f) MotionPhase.Enter else MotionPhase.Exit, reducedMotion),
        label = "orbClearance",
    )
    SideEffect { host.orbClearance = orbClearance }
}

/** Top padding of [SessionComposerHost.orbClearance], read while laying out rather than composing. */
private fun Modifier.orbClearancePadding(host: SessionComposerHost): Modifier = layout { measurable, constraints ->
    val top = host.orbClearance.dp.roundToPx()
    val placeable = measurable.measure(constraints.offset(vertical = -top))
    val height = constraints.constrainHeight(placeable.height + top)
    layout(placeable.width, height) { placeable.place(0, top) }
}

/**
 * The + menu (iOS `attachmentMenuOverlay`, `toggleAttachmentMenu` and `chooseAttachment`) and what
 * it opens: Photos (the system photo picker), Files (the system document picker), Location (one at
 * a time), Camera (when the device has one, the composer's full-screen [AttachmentCameraView]) and
 * Paste (while the clipboard holds an image, checked as the menu opens). The menu is a dropdown
 * hanging from the + ([SessionAttachmentDropdown]). It doesn't take focus, so the keyboard stays up
 * while it's open; choosing a picker gives the keyboard up, which returns once something is
 * attached if the field had it.
 */
@Composable
private fun SessionAttachmentMenu(
    store: StoreOf<State, Action>,
    host: SessionComposerHost,
    importer: SessionAttachmentImporter,
    menu: SessionAttachmentMenuState,
) {
    val isOpen = menu.plusMenuIsOpen
    var showsCamera by remember { mutableStateOf(false) }
    val pasteboard = remember { dependency(ai.opencode.core.dependencies.PasteboardClient) }
    var opened by remember { mutableIntStateOf(0) }
    var tapped by remember { mutableIntStateOf(0) }
    var dismissedAt by remember { mutableLongStateOf(0L) }
    val taskIdentity by store.observe { it.taskIdentity }
    val hasPendingRequests by store.observe { it.hasPendingRequests }
    val dictating by store.observe { it.isPreparingDictation || it.isDictating || it.dictationHold != null }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importer.importPickedFiles(uris, onFocus = host::focusComposer)
    }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        importer.importPickedMedia(ComposerPickers.uris(result.resultCode, result.data), onAdded = host::focusComposer)
    }

    // Another session in place: the menu closes at once.
    var shownIdentity by remember { mutableStateOf(taskIdentity) }
    LaunchedEffect(taskIdentity) {
        if (shownIdentity == taskIdentity) return@LaunchedEffect
        shownIdentity = taskIdentity
        importer.cancelImageImport()
        menu.attachmentMenuRestoresFocus = false
        menu.closeAttachmentMenu()
    }
    HapticEffect(Haptic.lightTap, opened)
    HapticEffect(Haptic.tap, tapped)

    // iOS `onChange(of: attachmentMenu)`: closed some other way, the keyboard comes back if the
    // menu kept it.
    var wasOpen by remember { mutableStateOf(isOpen) }
    LaunchedEffect(isOpen) {
        if (isOpen == wasOpen) return@LaunchedEffect
        wasOpen = isOpen
        if (isOpen) return@LaunchedEffect
        if (!menu.attachmentMenuRestoresFocus) return@LaunchedEffect
        menu.attachmentMenuRestoresFocus = false
        host.focusComposer()
    }
    // iOS `onChange(of: messageFieldFocused)`: typing takes the menu's place.
    val focused = host.messageFieldFocused
    LaunchedEffect(focused) {
        if (!focused || !menu.plusMenuIsOpen) return@LaunchedEffect
        menu.attachmentMenuRestoresFocus = false
        menu.closeAttachmentMenu()
    }
    // A pending request's dock takes the composer's place; dictation takes the composer.
    LaunchedEffect(hasPendingRequests) { if (hasPendingRequests) menu.closeAttachmentMenu() }
    LaunchedEffect(dictating) {
        if (!dictating || !menu.plusMenuIsOpen) return@LaunchedEffect
        menu.attachmentMenuRestoresFocus = false
        menu.closeAttachmentMenu()
    }

    fun toggle() {
        if (menu.plusMenuIsOpen) {
            menu.closeAttachmentMenu()
            return
        }
        // A tap on the × already closed the menu as a tap outside it.
        if (SystemClock.uptimeMillis() - dismissedAt < 500) return
        menu.keepingLocationRow = false
        menu.attachmentMenuRestoresFocus = host.messageFieldFocused
        menu.clipboardHasImages = pasteboard.hasImages()
        menu.plusMenuIsOpen = true
        opened += 1
    }

    fun dismiss() {
        dismissedAt = SystemClock.uptimeMillis()
        menu.closeAttachmentMenu()
    }

    fun choose(item: AttachmentMenuItem) {
        if (!menu.plusMenuIsOpen) return
        // iOS `pasteAttachments`: the keyboard comes back as the menu closes.
        if (item == AttachmentMenuItem.Paste) {
            menu.attachmentMenuRestoresFocus = true
            importer.pasteClipboardImages()
            menu.closeAttachmentMenu()
            return
        }
        tapped += 1
        menu.attachmentMenuRestoresFocus = false
        when (item) {
            AttachmentMenuItem.Photos -> {
                menu.closeAttachmentMenu()
                photos.launch(
                    ComposerPickers.photosIntent(
                        maxItems = AttachmentPickerButton.MAX_PHOTO_SELECTION,
                        includesVideos = store.state.supportsFileUploads,
                    ),
                )
            }
            AttachmentMenuItem.Files -> {
                menu.closeAttachmentMenu()
                files.launch(arrayOf("*/*"))
            }
            AttachmentMenuItem.Location -> {
                menu.keepingLocationRow = true
                store.send(Action.locationTapped)
                menu.closeAttachmentMenu()
            }
            AttachmentMenuItem.Camera -> {
                showsCamera = true
                menu.closeAttachmentMenu()
            }
            AttachmentMenuItem.Paste -> Unit
        }
    }

    SideEffect {
        host.isAttachmentMenuOpen = isOpen
        host.onPlusTapped = ::toggle
        host.attachmentDropdown = { SessionAttachmentDropdown(store, menu, onChoose = ::choose, onDismiss = ::dismiss) }
        host.closeAttachmentMenu = { menu.closeAttachmentMenu() }
    }
    // Not focusable, the menu gets neither Back nor a hardware Escape: both close it here.
    BackHandler(enabled = isOpen) { menu.closeAttachmentMenu() }
    EscapeHandler(enabled = isOpen) { menu.closeAttachmentMenu() }
    if (showsCamera) {
        SessionAttachmentCamera(importer, onDismiss = { showsCamera = false }, onAdded = host::focusComposer)
    }
}

/** The + menu's rows, hanging from the + button. */
@Composable
private fun SessionAttachmentDropdown(
    store: StoreOf<State, Action>,
    menu: SessionAttachmentMenuState,
    onChoose: (AttachmentMenuItem) -> Unit,
    onDismiss: () -> Unit,
) {
    val expanded = menu.plusMenuIsOpen
    val supportsFileUploads by store.observe { it.supportsFileUploads }
    val wantsLocation by store.observe { it.pendingLocation != null || it.isLocating }
    val packageManager = LocalContext.current.packageManager
    val hasCamera = remember(packageManager) { packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    val items = sessionAttachmentMenuItems(
        hasCamera = hasCamera,
        supportsFileUploads = supportsFileUploads,
        wantsLocationAttachment = wantsLocation,
        keepingLocationRowWhileMenuCloses = menu.keepingLocationRow,
        clipboardHasImages = menu.clipboardHasImages,
    )
    val hostView = LocalView.current
    val firstRow = remember { AccessibilityFocusTarget() }
    MenuReturnsTalkBackFocus(expanded, menu.plusButton)
    OpenCodeButtonMenu(expanded, onDismiss, Modifier.testTag("attachment-menu")) {
        MenuTakesTalkBackFocus(hostView, expanded, firstRow)
        for ((index, item) in items.withIndex()) {
            var rowModifier = Modifier.testTag("attachment-menu-${item.name.lowercase()}")
            if (index == 0) rowModifier = rowModifier.accessibilityFocusTarget(firstRow)
            MenuItem(item.title, item.systemImage, rowModifier) { onChoose(item) }
        }
    }
}

/**
 * Behind the composer (iOS the capsule's `.background`): while the + menu is open, or Composer
 * Autocomplete's suggestions are, a tap anywhere else or a downward drag dismisses them.
 */
@Composable
private fun SessionComposerDismissArea(store: StoreOf<State, Action>, host: SessionComposerHost) {
    val autocompleteOpen by store.observe { it.autocomplete.isOpen }
    val target = ComposerDismissTarget.current(
        attachmentMenuOpen = host.isAttachmentMenuOpen,
        autocompleteOpen = autocompleteOpen,
        autocompleteDrawn = host.showsAutocomplete && !host.floatingCallCovers,
    )
    when (target) {
        ComposerDismissTarget.AttachmentMenu -> AttachmentMenuDismissArea(onDismiss = host.closeAttachmentMenu)
        ComposerDismissTarget.Autocomplete ->
            AttachmentMenuDismissArea(onDismiss = { store.send(Action.Autocomplete(ComposerAutocompleteAction.Dismissed)) })
        null -> Unit
    }
}

/**
 * The room the request panel's cap is made from (iOS `requestPanelInputs.room`): the screen's
 * height between the top safe area and the keyboard (or the bottom safe area). Its own
 * composable, so the keyboard's frames recompose only it.
 */
@Composable
private fun RequestPanelRoomProbe(host: SessionComposerHost, modifier: Modifier) {
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    // A Catch Up card's own edges, not the window's ([inContainer]).
    val top = WindowInsets.statusBarsIgnoringVisibility.inContainer().getTop(density)
    val bottom = maxOf(WindowInsets.ime.inContainer().getBottom(density), WindowInsets.bottomSafeArea.inContainer().getBottom(density))
    Box(modifier.onSizeChanged { height = it.height })
    SideEffect {
        val room = with(density) { (height - top - bottom).coerceAtLeast(0).toDp().value }
        if (host.availableHeight != room) host.availableHeight = room
    }
}

/**
 * Registers the screen's location prompt while it's on top, for the + menu's Location
 * ([LocationPermissionPrompt.install]), through an activity-result launcher the screen owns. The
 * microphone (dictation and calls, iOS `requestRecordPermission()` as they start) is asked through
 * the requesters the app's activity registers while it's resumed: a screen registering its own
 * would leave none behind when a pushed subagent's screen went.
 */
@Composable
private fun SessionPermissionPrompts(isCovered: Boolean) {
    val location = remember { arrayOfNulls<CompletableDeferred<Boolean>>(1) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        location[0]?.complete(grants.values.any { it })
        location[0] = null
    }
    // Only the screen on top asks; uncovered again (a pushed subagent went), it takes the prompt back.
    DisposableEffect(locationLauncher, isCovered) {
        if (isCovered) return@DisposableEffect onDispose {}
        val remove = LocationPermissionPrompt.install {
            val answer = CompletableDeferred<Boolean>()
            location[0]?.complete(false)
            location[0] = answer
            locationLauncher.launch(LocationPermissionPrompt.permissions)
            answer.await()
        }
        onDispose {
            remove()
            location[0]?.complete(false)
            location[0] = null
        }
    }
}

/**
 * Full-screen media over the session (iOS `MediaPresenter`): the app's one root [MediaView]
 * (`AppView`) provides [LocalMediaPresenter]; the session draws inside it.
 */
@Composable
private fun SessionMediaHost(content: @Composable () -> Unit) = content()

/** What the composer holds, above its field (iOS `pendingAttachmentsRow`). */
@Composable
private fun SessionPendingAttachments(store: StoreOf<State, Action>, composerExpanded: Boolean) {
    val attachments by store.observe { it.pendingAttachments }
    val files by store.observe { it.pendingFiles }
    val canRetry by store.observe { it.supportsFileUploads }
    val location by store.observe { it.pendingLocation }
    val isLocating by store.observe { it.isLocating }
    val quotes by store.observe { it.pendingQuotes }
    val presenter = LocalMediaPresenter.current
    val reducedMotion = rememberReduceMotion()
    val context = LocalContext.current
    val host = LocalSessionComposerHost.current
    PendingAttachmentsRow(
        attachments = attachments,
        files = files,
        canRetryFiles = canRetry,
        showsLocation = location != null || isLocating,
        location = location,
        isLocating = isLocating,
        onRemoveAttachment = { store.send(Action.attachmentRemoved(it)) },
        onRemoveFile = { store.send(Action.fileRemoved(it)) },
        onRetryFile = { store.send(Action.fileRetryTapped(it)) },
        onRemoveLocation = { store.send(Action.locationRemoved) },
        quotes = quotes,
        onRemoveQuote = { store.send(Action.quoteRemoved(it)) },
        commentingQuoteID = host?.commentingQuoteID,
        onOpenQuote = { host?.commentingQuoteID = it },
        onOpenPastedText = { attachment ->
            val text = attachment.pastedText
            val summary = attachment.pasted
            if (text != null && summary != null) {
                presenter?.present(sourceID = null, isMedia = false, reducesMotion = reducedMotion, screenName = localized(R.string.session_pasted_text_screen_title)) { viewer ->
                    PastedTextViewer(
                        text = text,
                        summary = summary,
                        onCopy = { store.send(Action.CopyTextTapped(it)) },
                        onShare = { shareText(context, it) },
                        onDone = { viewer.dismiss() },
                    )
                }
            }
        },
        onOpenLocation = { store.send(Action.LinkTapped(it)) },
        onOpenPhoto = { id -> presentComposerPhoto(store, id, presenter, host, reducedMotion, context) },
        composerExpanded = composerExpanded,
        server = store.state.server,
    )
}

/**
 * iOS `presentComposerPhoto(_:)`: a pending photo full screen, paging through the composer's
 * photos, with Remove handing it back (the row reflows on `AttachmentRemoval`'s spring). The
 * keyboard steps aside while it's up and comes back after, if the composer is still this one.
 */
private fun presentComposerPhoto(
    store: StoreOf<State, Action>,
    id: String,
    presenter: MediaPresenter?,
    host: SessionComposerHost?,
    reducedMotion: Boolean,
    context: android.content.Context,
) {
    presenter ?: return
    val preview = ComposerPhotoViewer.preview(
        attachments = store.state.pendingAttachments, files = store.state.pendingFiles,
        selected = id, server = store.state.server, directory = store.state.session?.directory,
    ) ?: return
    val restoresFocus = host?.messageFieldFocused == true
    if (restoresFocus) host?.dismissComposerKeyboard()
    lateinit var viewerStore: Store<FullscreenViewerFeature.State, FullscreenViewerFeature.Action>
    val addDrawing = drawingHandler(store, drawnOn = { viewerStore.state.selectedPage })
    viewerStore = ComposerPhotoViewer.store(preview, addDrawing) { removed ->
        val index = store.state.pendingAttachments.indexOfFirst { it.id == removed }
        if (index >= 0) store.send(Action.attachmentRemoved(index))
        else store.state.pendingFiles.firstOrNull { it.id.toString() == removed }?.let { store.send(Action.fileRemoved(it.id)) }
    }
    val identity = store.state.taskIdentity
    val presented = presenter.present(
        sourceID = preview.id,
        isMedia = true,
        reducesMotion = reducedMotion,
        flightAccessories = { mediaID -> ComposerPhotoViewer.flightAccessories(mediaID, store.state.pendingAttachments, context) },
        onDismiss = {
            viewerStore.close()
            ComposerPhotoViewer.release(preview)
            if (restoresFocus && store.state.taskIdentity == identity && !store.state.hasPendingRequests) host?.focusComposer()
        },
    ) { viewer ->
        FullscreenViewer(viewerStore, viewer)
    }
    if (!presented) {
        viewerStore.close()
        ComposerPhotoViewer.release(preview)
    }
}

/** The system share sheet with [text] (iOS `ShareLink`). */
internal fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(android.content.Intent.createChooser(send, null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: android.content.ActivityNotFoundException) {
    }
}

/**
 * The transcript, composed while the screen measures, after the composer and the header: the
 * parent Box measures a `matchParentSize` child after its other children, and their size callbacks
 * have written [SessionScrollEdgeBridge.composerHeight] and the header's height by then. So the
 * list's first frame already rests between them (on a cold launch its bottom inset used to be 0 and
 * its top the default header's for the first frames: the last row sat under the composer, then the
 * list jumped), as iOS lays the composer out before the list. Placement, and so drawing order,
 * stays the parent's child order.
 */
@Composable
private fun BoxScope.ComposedAfterComposer(content: @Composable BoxScope.() -> Unit) {
    SubcomposeLayout(Modifier.matchParentSize()) { constraints ->
        val placeables = subcompose(Unit) { Box(Modifier.fillMaxSize(), content = content) }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

@Composable
private fun LoadFailure(message: String, onRetry: () -> Unit) {
    OpenCodeContentUnavailable(
        symbol = SFSymbol.ExclamationmarkTriangle,
        title = stringResource(R.string.session_load_failed_title),
        modifier = Modifier.fillMaxSize(),
        description = message,
        action = stringResource(CoreR.string.common_try_again),
        onAction = onRetry,
    )
}

/**
 * A child store for an optional child state, while it's present (iOS `store.scope(\.x, action: \.x)`
 * returning nil). It keeps the last value while the child leaves, so a closing sheet draws what
 * it had.
 */
@Composable
internal fun <C : Any, CA> StoreOf<State, Action>.rememberOptionalScope(
    state: (State) -> C?,
    action: (CA) -> Action,
): StoreOf<C, CA>? {
    val present by observe { state(it) != null }
    if (!present) return null
    return remember(this) {
        var last: C? = null
        scope({ parent -> (state(parent) ?: last ?: error("rememberOptionalScope read before its state appeared")).also { last = it } }, action)
    }
}

/**
 * The sheets over the session (iOS `.sheet(item: $store.activePicker)`, the shell output sheet,
 * and the menu's prompts while the Session sheet isn't showing its own).
 */
@Composable
private fun SessionSheets(store: StoreOf<State, Action>, listContext: SessionListContext) {
    val activePicker by store.observe { it.activePicker }
    val dismiss = { store.send(Action.Binding(BindingAction.ActivePicker(null))) }

    // iOS `onDismiss`: whichever sheet leaves, the Project sheet's folders reset, and the
    // keyboard the model chip put away comes back.
    val panels = LocalSessionComposerHost.current?.modelPanels
    var shownPicker by remember { mutableStateOf(activePicker) }
    LaunchedEffect(activePicker) {
        if (shownPicker != null && shownPicker != activePicker) {
            store.send(Action.ProjectPickerDismissed)
            if (panels?.sheetRestoresFocus == true) {
                panels.sheetRestoresFocus = false
                panels.restoreFocusAfterModelList(store.state.taskIdentity)
            }
        }
        shownPicker = activePicker
    }

    val sheet = store.rememberOptionalScope({ it.sessionSheet }, Action::SessionSheet)
    // The Session sheet's content, composed and measured ahead of its first open.
    SessionSheetPrewarm(
        enabled = activePicker == null,
        details = { sessionSheetDetails(store.state) },
        providerIcon = { providerID, canonical, size -> ProviderIcon(providerID, Modifier.size(size), canonical) },
    )
    when (activePicker) {
        State.ActivePicker.session -> if (sheet != null) {
            val details by store.observe(::sessionSheetDetails)
            SessionSheetView(
                sheet = sheet,
                context = listContext,
                details = details,
                onModelTapped = { store.send(Action.ModelButtonTapped) },
                onMediaTapped = { store.send(Action.SessionMediaTapped) },
                onDismissRequest = dismiss,
                providerIcon = { providerID, canonical, size -> ProviderIcon(providerID, Modifier.size(size), canonical) },
                statusOrb = { status, size ->
                    // iOS: the session's own activity, drawn like a subagent's elsewhere.
                    val activity = SubagentActivity(
                        sessionID = details.session?.id,
                        phase = if (status == SessionSheetStatus.Idle) SubagentActivity.Phase.idle else SubagentActivity.Phase.running,
                        needsInput = status == SessionSheetStatus.Waiting,
                    )
                    val statusDescription = stringResource(R.string.session_sheet_status_description)
                    val statusText = stringResource(status.textRes)
                    CompositionLocalProvider(
                        LocalAgentActivityScope provides "${sheet.state.server.persistenceID}|${details.session?.id ?: "draft"}|sheet",
                    ) {
                        AgentActivityGlyph(
                            AgentActivitySource.Subagent(activity),
                            Modifier.clearAndSetSemantics { contentDescription = statusDescription; stateDescription = statusText },
                            size = size,
                        )
                    }
                },
            )
        }
        State.ActivePicker.model -> SessionModelPicker(store)
        State.ActivePicker.project -> {
            val project by store.observe { ProjectSheetModel(it.projects, it.draftProject, it.projectFilter) }
            val folderBrowser = store.rememberOptionalScope<FolderBrowserFeature.State, FolderBrowserFeature.Action>(
                { it.folderBrowser },
                { Action.FolderBrowser(PresentationAction.Presented(it)) },
            )
            ProjectPickerSheet(
                projects = project.projects,
                draftProject = project.draftProject,
                projectFilter = project.filter,
                onProjectFilterChange = { store.send(Action.Binding(BindingAction.ProjectFilter(it))) },
                onProjectPicked = { store.send(Action.ProjectPicked(it)) },
                onCancel = dismiss,
                folderBrowser = folderBrowser,
            )
        }
        State.ActivePicker.requests -> {
            val dock by store.observe { it.requestDock }
            val context by store.observe { RequestDockContext(currentSessionID = it.session?.id, familySessions = it.familySessions, isVisible = it.isVisible) }
            // What the composer's row holds goes with Dismiss, as with the panel's ×.
            val dismissMessage by store.observe { if (it.isAnsweringRequest) it.draft else null }
            RequestReviewSheet(
                dock = dock,
                context = context,
                dismissMessage = dismissMessage,
                send = { store.send(Action.RequestDock(it)) },
                onClose = dismiss,
                onOpenURL = { store.send(Action.LinkTapped(it)) },
            )
        }
        null -> Unit
    }

    // The menu's rename, delete, and export prompts, while the sheet isn't showing its own.
    if (activePicker != State.ActivePicker.session && sheet != null) {
        val title by store.observe { it.session?.title }
        SessionActionPresentations(sheet, title)
    }

    store.rememberOptionalScope<ShellOutputFeature.State, ShellOutputFeature.Action>(
        { it.shellOutput },
        { Action.ShellOutput(PresentationAction.Presented(it)) },
    )?.let { shell ->
        ShellOutputView(shell, onClose = { store.send(Action.ShellOutput(PresentationAction.Dismiss)) })
    }
}

@Immutable
private data class ProjectSheetModel(
    val projects: List<ai.opencode.server.model.OpenCodeProject>,
    val draftProject: ai.opencode.server.model.OpenCodeProject?,
    val filter: String,
)

private fun sessionSheetDetails(state: State) = SessionSheetDetails(
    session = state.session,
    hasPendingRequests = state.permissionRequests.isNotEmpty() || state.formRequests.isNotEmpty(),
    isBusy = state.isBusy,
    currentModel = state.currentModel,
    currentModelInfo = state.currentModelInfo,
    currentModelName = state.currentModelName,
    currentModelLabel = state.currentModelLabel,
    contextUsage = state.contextUsage,
    contextFraction = state.contextFraction,
    contextLimit = state.contextLimit,
    sessionCost = state.sessionCost,
)

/** iOS `.alert("Couldn’t Share Location", …)`: Open Settings when access is off. */
@Composable
private fun LocationErrorAlert(store: StoreOf<State, Action>) {
    val error by store.observe { it.locationError }
    val message = error ?: return
    OpenCodeAlertDialog(
        onDismissRequest = { store.send(Action.locationErrorDismissed) },
        title = { Text(stringResource(R.string.session_location_share_failed_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { store.send(Action.locationErrorDismissed) }) { Text(stringResource(CoreR.string.common_ok)) }
        },
        dismissButton = if (message == LocationClientError.Denied.message) {
            { TextButton(onClick = { store.send(Action.locationSettingsTapped) }) { Text(stringResource(CoreR.string.common_open_settings)) } }
        } else {
            null
        },
    )
}
