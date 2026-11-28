package ai.opencode.feature.session

import ai.opencode.core.strings.localized
import ai.opencode.core.R as CoreR

import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.AccessibilityFocusTarget
import ai.opencode.core.ui.OpenCodeColors
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.accessibilityFocusRequest
import ai.opencode.core.ui.accessibilityFocusTarget
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.core.ui.rememberAccessibilityFocusRequest
import ai.opencode.feature.composer.EscapeHandler
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.header.BreathingTitleHold
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionContextGauge
import ai.opencode.feature.session.header.SessionContextText
import ai.opencode.server.model.OpenCodeExportFormat
import ai.opencode.server.model.isUntitled
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max

// The header row (drawer or Back, the title capsule, Back to main session and the … session menu),
// and the session menu's items. The header's Liquid Glass controls are M3 tonal surfaces; the …
// menu is a Material dropdown that runs the Session sheet's actions without opening it
// (`Action.SessionMenu`).

/** The header's controls: the 44 pt glass circle, as a 48 dp tonal circle (the minimum target). */
internal val headerButtonSize: Dp = max(HeaderBlurTunables.buttonSize.value, 48f).dp

/** The AX sizes start at about 1.65× body. */
internal fun isAccessibilityFontScale(fontScale: Float): Boolean = fontScale >= 1.65f

/** What the header reads from the session, observed as one value so the header recomposes only when it changes. */
@Immutable
internal data class SessionHeaderModel(
    val titleText: String?,
    val draftProjectName: String?,
    val isDraft: Boolean,
    val isSyncing: Boolean,
    val isLastKnown: Boolean,
    val autoRenameFailed: Boolean,
    val isBusy: Boolean,
    val isResyncing: Boolean,
    val refreshFailed: Boolean,
    val canChooseProject: Boolean,
    val identity: TranscriptIdentity,
    val sessionID: String?,
    val sessionTitle: String?,
    val familyRootID: String?,
    val contextFraction: Double?,
    val contextTokens: Int?,
    val contextLimit: Int?,
    /** What Rename starts from: the server's title, or nothing while the session reads as its first prompt. */
    val renameTitle: String = "",
) {
    /** A subagent family's root is elsewhere: Back to main session. */
    val backToRootID: String? get() = familyRootID?.takeIf { it != sessionID }

    companion object {
        fun from(state: State): SessionHeaderModel {
            val titleText = headerTitleText(state)
            val isDraft = state.isDraft
            // A session just made from this draft reads New Session as the draft did, without a resync's breathing.
            val isSyncing = !isDraft &&
                !(state.session?.id == state.draftBecameSessionID && readsNewSession(state)) &&
                (state.isResyncing || state.sessionSheet?.operation == SessionSheetFeature.Operation.AutoRenaming)
            return SessionHeaderModel(
                titleText = titleText,
                draftProjectName = if (isDraft || state.session?.id == state.draftBecameSessionID) state.draftProject?.name else null,
                isDraft = isDraft,
                isSyncing = isSyncing,
                isLastKnown = !isDraft && state.refreshFailed,
                autoRenameFailed = state.sessionSheet?.autoRenameFailed == true,
                isBusy = state.isBusy,
                isResyncing = state.isResyncing,
                refreshFailed = state.refreshFailed,
                canChooseProject = state.canChooseProject,
                identity = state.transcriptIdentity,
                sessionID = state.session?.id,
                sessionTitle = state.session?.title,
                familyRootID = state.familyRootID,
                contextFraction = state.contextFraction,
                contextTokens = state.contextUsage?.tokens,
                contextLimit = state.contextLimit,
                renameTitle = state.session?.let { if (it.isUntitled) "" else it.title }.orEmpty(),
            )
        }

        /**
         * What the header capsule reads: a draft's New Session, and once its session exists the
         * session's title. The server's placeholder title reads the same as the draft's, so the
         * first send changes nothing here; a real title replaces it in the same capsule.
         */
        fun headerTitleText(state: State): String? {
            val session = state.session ?: return if (state.isDraft) localized(R.string.session_new_session_title) else null
            if (readsNewSession(state)) return localized(R.string.session_new_session_title)
            return session.title
        }

        /** True when a session's title is empty or the server's “New session” placeholder, which the header reads as New Session. */
        private fun readsNewSession(state: State): Boolean {
            val session = state.session ?: return state.isDraft
            val title = session.title.trim()
            return title.isEmpty() || title.equals("New session", ignoreCase = true)
        }
    }
}

/**
 * The header row: the drawer button (or Back on a pushed subagent, nothing on a Catch Up card), the
 * title capsule centred on the header, and the trailing controls. Reports its laid-out height for
 * the backdrop.
 *
 * @param navigationIcon The drawer button Home supplies.
 * @param onHeaderHeight the row's height in dp, whenever it changes.
 */
@Composable
internal fun SessionHeaderControls(
    store: StoreOf<State, Action>,
    listContext: SessionListContext,
    navigationIcon: @Composable () -> Unit,
    onBack: (() -> Unit)?,
    onCatchUpOpen: (() -> Unit)?,
    onHeaderHeight: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model by store.observe(SessionHeaderModel::from)
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val showsSessionMenu = onCatchUpOpen == null && model.sessionID != null
    // A draft keeps the session menu's place, so the menu fading in as its session arrives moves nothing.
    val holdsSessionMenuSlot = onCatchUpOpen == null && model.isDraft

    // A Material top app bar: start-aligned title, plain icon buttons, 64 dp tall.
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            // Measured with the card's top inset, so the scrolled bar and the transcript's top
            // clear the whole header.
            .onSizeChanged { onHeaderHeight(with(density) { it.height.toDp().value }) }
            .padding(top = if (onCatchUpOpen == null) 0.dp else CATCH_UP_COMPOSER_INSET.dp)
            .heightIn(min = TOP_APP_BAR_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            onBack != null -> BackControl(onBack)
            onCatchUpOpen == null -> navigationIcon()
        }
        Box(
            Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            HeaderTitle(store, model, listContext, onCatchUpOpen, reducedMotion)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            model.backToRootID?.let { rootID ->
                FloatingControl(SFSymbol.ArrowUturnBackward, stringResource(R.string.session_back_to_main_description)) { store.send(Action.FamilyPicked(rootID)) }
            }
            when {
                // It fades into the slot a draft kept for it.
                showsSessionMenu -> AnimatedVisibility(
                    visibleState = remember { MutableTransitionState(false).apply { targetState = true } },
                    enter = fadeIn(Motion.microIn.animation(MotionPhase.Enter, reducedMotion)),
                    exit = fadeOut(Motion.microIn.animation(MotionPhase.Exit, reducedMotion)),
                ) { SessionMenuButton(store, model, listContext) }
                holdsSessionMenuSlot -> Spacer(Modifier.size(headerButtonSize).clearAndSetSemantics {})
            }
        }
    }
}

private const val CATCH_UP_COMPOSER_INSET = 12f

/** Material 3's small top app bar height. */
private val TOP_APP_BAR_HEIGHT = 64.dp

/** A pushed subagent's way back to its parent, where the drawer button is on the root session: the same circle. */
@Composable
private fun BackControl(onBack: () -> Unit) {
    OpenCodeIconButton(
        symbol = SFSymbol.ChevronBackward,
        contentDescription = stringResource(CoreR.string.common_back),
        onClick = onBack,
        size = headerButtonSize,
        style = OpenCodeIconButtonStyle.Standard,
        modifier = Modifier.testTag("subagent-back"),
    )
}

@Composable
internal fun FloatingControl(symbol: SFSymbol, label: String, onClick: () -> Unit) {
    OpenCodeIconButton(symbol = symbol, contentDescription = label, onClick = onClick, size = headerButtonSize, style = OpenCodeIconButtonStyle.Standard)
}

/** Secondary under high contrast, else primary at 75 %. */
internal fun headerProjectColor(colors: OpenCodeColors): Color =
    if (colors.highContrast) colors.secondaryText else colors.text.copy(alpha = 0.75f)

/**
 * The header's one title capsule. A draft and the session it becomes share it, so creating the
 * session never replaces or moves it. A draft's capsule chooses its project; a session's shows
 * its details; a Catch Up card's opens the session.
 */
@Composable
private fun HeaderTitle(
    store: StoreOf<State, Action>,
    model: SessionHeaderModel,
    listContext: SessionListContext,
    onCatchUpOpen: (() -> Unit)?,
    reducedMotion: Boolean,
) {
    val latestTitle = model.titleText ?: return
    // The capsule's second line: the project, which a draft knows before the session list does.
    val project = listContext.project?.name ?: model.draftProjectName
    val projectColor = headerProjectColor(OpenCodeTheme.colors)
    val fontScale = LocalDensity.current.fontScale
    // TalkBack goes back to the title once Session Info, opened from it, closes, as VoiceOver
    // returns to the button that presented the sheet; with the keyboard up it landed on the
    // drawer button.
    var returnsFocusAfterInfo by remember { mutableStateOf(false) }
    var infoCloses by remember { mutableIntStateOf(0) }
    val showsInfo by store.observe { it.activePicker == State.ActivePicker.session }
    LaunchedEffect(showsInfo) {
        if (showsInfo || !returnsFocusAfterInfo) return@LaunchedEffect
        returnsFocusAfterInfo = false
        infoCloses++
    }
    val infoFocus = rememberAccessibilityFocusRequest(infoCloses.takeIf { it > 0 })
    // Auto Rename's new title waits for the breath to ease back to full; another session switched
    // in place shows its own title at once.
    BreathingTitleHold(identity = model.identity, title = latestTitle, isBreathing = model.isSyncing) { title, animates ->
        val subtitle = if (model.autoRenameFailed) stringResource(R.string.session_sheet_rename_failed) else project
        val titleDescription = if (subtitle == null) title else stringResource(R.string.session_list_two, title, subtitle)
        val onClick: () -> Unit = onCatchUpOpen
            ?: {
                if (!model.isDraft) returnsFocusAfterInfo = true
                store.send(if (model.isDraft) Action.ProjectButtonTapped else Action.SessionInfoTapped)
            }
        val hint = when {
            onCatchUpOpen != null -> stringResource(R.string.session_header_open_from_catch_up_label)
            model.isDraft -> if (model.canChooseProject) stringResource(R.string.session_draft_project_choose_label) else null
            else -> stringResource(R.string.session_header_show_details_label)
        }
        val value = if (model.isDraft) {
            ""
        } else {
            val activity = if (model.isBusy) stringResource(R.string.session_activity_running_description) else stringResource(R.string.session_header_idle_description)
            val freshness = if (model.isResyncing) stringResource(R.string.session_header_syncing_description) else if (model.refreshFailed) stringResource(R.string.session_header_last_known_description) else null
            if (freshness == null) activity else stringResource(R.string.session_list_two, activity, freshness)
        }
        // The title sits on the bar itself (no capsule); a press shows the ripple in a rounded
        // rectangle around it.
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClickLabel = hint, onClick = onClick)
                .openCodeTapTarget()
                .testTag(if (model.isDraft) "draft-title" else "session-title")
                // Ahead of the clearing below, which would drop its marker.
                .accessibilityFocusRequest(infoFocus)
                .clearAndSetSemantics {
                    contentDescription = titleDescription
                    if (value.isNotEmpty()) stateDescription = value
                },
        ) {
            Column(
                Modifier
                    // The header doesn't carry the screen's transactions: the capsule animates its own width.
                    .then(if (!reducedMotion && animates) Modifier.animateContentSize(Motion.layout.animation(reduceMotion = false)) else Modifier)
                    .heightIn(min = headerButtonSize)
                    .padding(horizontal = 8.dp, vertical = if (isAccessibilityFontScale(fontScale)) 8.dp else 3.dp),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SyncingSessionTitle(
                        title = title,
                        isSyncing = model.isSyncing,
                        isLastKnown = model.isLastKnown,
                        animates = animates,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onCatchUpOpen != null) {
                        OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 12.dp, tint = OpenCodeTheme.colors.tertiaryText)
                    }
                }
                AnimatedVisibility(
                    visible = subtitle != null,
                    enter = fadeIn(Motion.layout.animation(reduceMotion = reducedMotion)),
                    exit = fadeOut(Motion.layout.animation(reduceMotion = reducedMotion)),
                ) {
                    val shown = subtitle ?: ""
                    if (model.autoRenameFailed) {
                        Text(
                            shown,
                            style = MaterialTheme.typography.labelMedium,
                            color = projectColor,
                            maxLines = 1,
                        )
                    } else {
                        ProjectHeaderSubtitle(shown, animates = animates) {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelMedium,
                                color = projectColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Session actions; the rim doubles as the context gauge. */
@Composable
private fun SessionMenuButton(store: StoreOf<State, Action>, model: SessionHeaderModel, listContext: SessionListContext) {
    var expanded by remember { mutableStateOf(false) }
    val value = model.contextTokens?.let { SessionContextText.accessibilityValue(it, model.contextLimit) } ?: ""
    var anchorBottom by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    // TalkBack goes back to More once Session Info, opened from the menu, closes (as VoiceOver
    // returns to the menu's button); otherwise the sheet leaving dropped it into the transcript.
    var returnsFocusAfterInfo by remember { mutableStateOf(false) }
    val showsInfo by store.observe { it.activePicker == State.ActivePicker.session }
    val more = remember { AccessibilityFocusTarget() }
    // Likewise once a dialog the menu opened (Rename, Delete) closes, as VoiceOver returns to the
    // button behind an alert; with the keyboard up it landed on the title.
    var returnsFocusAfterDialog by remember { mutableStateOf(false) }
    val showsDialog by store.observe { it.sessionSheet?.let { sheet -> sheet.isRenaming || sheet.confirmsDeletion } == true }
    LaunchedEffect(showsDialog) {
        if (showsDialog || !returnsFocusAfterDialog) return@LaunchedEffect
        returnsFocusAfterDialog = false
        view.focusMenuButtonForAccessibility(more)
    }
    LaunchedEffect(showsInfo) {
        if (showsInfo || !returnsFocusAfterInfo) return@LaunchedEffect
        returnsFocusAfterInfo = false
        view.focusMenuButtonForAccessibility(more)
    }
    Box(Modifier.onGloballyPositioned { anchorBottom = it.boundsInWindow().bottom.toInt() }) {
        OpenCodeIconButton(
            symbol = SFSymbol.Ellipsis,
            contentDescription = stringResource(R.string.session_menu_more_description),
            onClick = { expanded = true },
            size = headerButtonSize,
            style = OpenCodeIconButtonStyle.Standard,
            modifier = Modifier
                .testTag("session-menu")
                .accessibilityFocusTarget(more)
                .semantics { if (value.isNotEmpty()) stateDescription = value },
        )
        val fraction = model.contextFraction
        AnimatedVisibility(visible = fraction != null, modifier = Modifier.matchParentSize(), enter = fadeIn(), exit = fadeOut()) {
            SessionContextGauge(
                fraction = fraction ?: 0.0,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
        SessionMenu(
            expanded = expanded,
            anchorBottom = { anchorBottom },
            onDismiss = { expanded = false },
            title = model.sessionTitle ?: stringResource(R.string.session_untitled_title),
            renameTitle = model.renameTitle,
            listContext = listContext,
            more = more,
            send = {
                expanded = false
                if (it == Action.SessionInfoTapped) returnsFocusAfterInfo = true
                val opensDialog = (it as? Action.SessionMenu)?.action
                if (opensDialog is SessionSheetFeature.Action.RenameTapped || opensDialog == SessionSheetFeature.Action.DeleteTapped) {
                    returnsFocusAfterDialog = true
                }
                store.send(it)
            },
        )
    }
}

/** The session's own actions, its work, and deletion. */
@Composable
private fun SessionMenu(
    expanded: Boolean,
    anchorBottom: () -> Int,
    onDismiss: () -> Unit,
    title: String,
    renameTitle: String,
    listContext: SessionListContext,
    more: AccessibilityFocusTarget,
    send: (Action) -> Unit,
) {
    val developerMode by remember { shared(SharedKeys.developerMode) }.flow.collectAsState()
    var exportOpen by remember(expanded) { mutableStateOf(false) }
    fun menu(action: SessionSheetFeature.Action) = send(Action.SessionMenu(action))
    // No title heading: the header already shows it; exports still name the file by it.
    // Not focusable: the composer keeps the keyboard while the menu is open. A focusable popup took
    // focus, so the keyboard left as the menu was placed against the room above it, and the menu
    // stayed up over the ⋮. With the keyboard up the menu still hangs from the ⋮, capped
    // to the room above the keys (it scrolls), rather than moving up over the button to fit. A
    // non-focusable popup gets neither Back nor a hardware Escape: both close it here.
    BackHandler(enabled = expanded, onBack = onDismiss)
    EscapeHandler(enabled = expanded, onEscape = onDismiss)
    // Accessibility focus isn't input focus, so TalkBack still treats the open menu as a pane.
    val hostView = LocalView.current
    val firstRow = remember { AccessibilityFocusTarget() }
    MenuReturnsTalkBackFocus(expanded, more)
    val density = LocalDensity.current
    val windowHeight = LocalWindowInfo.current.containerSize.height
    // Insets are read only while open, so the keyboard's animation doesn't recompose the header.
    val room = if (expanded) {
        val keyboard = WindowInsets.ime.getBottom(density)
        if (keyboard > WindowInsets.navigationBars.getBottom(density)) {
            val bottom = windowHeight - keyboard - WindowInsets.statusBars.getTop(density)
            with(density) { (bottom - anchorBottom()).toDp() } - menuWindowMargin
        } else {
            Dp.Unspecified
        }
    } else {
        Dp.Unspecified
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = if (room != Dp.Unspecified) Modifier.heightIn(max = room.coerceAtLeast(headerButtonSize)) else Modifier,
        properties = PopupProperties(focusable = false),
    ) {
        MenuTakesTalkBackFocus(hostView, expanded, firstRow)
        listContext.isPinned?.let { isPinned ->
            MenuItem(
                if (isPinned) stringResource(R.string.session_unpin) else stringResource(R.string.session_pin),
                if (isPinned) SFSymbol.PinSlash else SFSymbol.Pin,
                Modifier.accessibilityFocusTarget(firstRow),
            ) {
                menu(SessionSheetFeature.Action.PinTapped)
            }
        }
        MenuItem(
            stringResource(CoreR.string.common_rename),
            SFSymbol.Pencil,
            if (listContext.isPinned == null) Modifier.accessibilityFocusTarget(firstRow) else Modifier,
        ) { menu(SessionSheetFeature.Action.RenameTapped(currentTitle = renameTitle)) }
        MenuItem(stringResource(R.string.session_copy_session_id), SFSymbol.SquareOnSquare) { menu(SessionSheetFeature.Action.CopySessionIDTapped) }
        if (listContext.isPinned != null) {
            MenuItem(
                if (listContext.isUnread) stringResource(R.string.session_mark_as_read) else stringResource(R.string.session_mark_as_unread),
                if (listContext.isUnread) SFSymbol.EnvelopeOpen else SFSymbol.EnvelopeBadge,
            ) { menu(SessionSheetFeature.Action.ReadTapped) }
            MenuItem(if (listContext.isMuted) stringResource(R.string.session_unmute) else stringResource(R.string.session_mute), if (listContext.isMuted) SFSymbol.Bell else SFSymbol.BellSlash) {
                menu(SessionSheetFeature.Action.MuteTapped)
            }
        }
        HorizontalDivider()
        MenuItem(stringResource(R.string.session_compact), SFSymbol.RectangleCompressVertical) { menu(SessionSheetFeature.Action.CompactTapped) }
        MenuItem(stringResource(R.string.session_fork), SFSymbol.ArrowTriangleBranch) { menu(SessionSheetFeature.Action.ForkTapped) }
        val exportExpandedState = stringResource(R.string.session_menu_export_expanded_description)
        val exportCollapsedState = stringResource(R.string.session_menu_export_collapsed_description)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.session_export)) },
            leadingIcon = { OpenCodeIcon(SFSymbol.SquareAndArrowUp, contentDescription = null, size = OpenCodeIconSize.small) },
            trailingIcon = {
                OpenCodeIcon(if (exportOpen) SFSymbol.ChevronUp else SFSymbol.ChevronDown, contentDescription = null, size = OpenCodeIconSize.small)
            },
            onClick = { exportOpen = !exportOpen },
            modifier = Modifier.semantics { stateDescription = if (exportOpen) exportExpandedState else exportCollapsedState },
        )
        if (exportOpen) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_export_markdown)) },
                onClick = { menu(SessionSheetFeature.Action.ExportTapped(OpenCodeExportFormat.Markdown, title)) },
                contentPadding = PaddingWithIndent,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_export_json)) },
                onClick = { menu(SessionSheetFeature.Action.ExportTapped(OpenCodeExportFormat.Json, title)) },
                contentPadding = PaddingWithIndent,
            )
        }
        MenuItem(stringResource(R.string.session_media_title), SFSymbol.PhotoOnRectangle, Modifier.testTag("session-menu-media")) {
            send(Action.SessionMediaTapped)
        }
        MenuItem(stringResource(R.string.session_info), SFSymbol.InfoCircle) { send(Action.SessionInfoTapped) }
        if (developerMode) {
            MenuItem("Report a Problem", SFSymbol.ExclamationmarkBubble, Modifier.testTag("session-menu-report-problem")) {
                send(Action.ReportProblemTapped)
            }
        }
        if (listContext.isPinned != null) {
            HorizontalDivider()
            val red = OpenCodeTheme.colors.destructive
            DropdownMenuItem(
                text = { Text(stringResource(R.string.session_delete_session), color = red) },
                leadingIcon = { OpenCodeIcon(SFSymbol.Trash, contentDescription = null, size = OpenCodeIconSize.small, tint = red) },
                onClick = { menu(SessionSheetFeature.Action.DeleteTapped) },
            )
        }
    }
}

private val PaddingWithIndent = PaddingValues(start = 48.dp, end = 12.dp)
