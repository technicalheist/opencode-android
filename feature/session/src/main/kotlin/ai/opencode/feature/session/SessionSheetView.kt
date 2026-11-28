package ai.opencode.feature.session

import androidx.annotation.StringRes
import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeTextField
import ai.opencode.core.ui.OpenCodeSheetHandle
import ai.opencode.core.ui.OpenCodeSwitch
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionRollDirection
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.ui.AlertTextFields
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.RollingNumber
import ai.opencode.core.ui.RollingNumberAlignment
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.feature.composer.OpenCodeBottomSheet
import ai.opencode.feature.session.header.BreathingTitleHold
import ai.opencode.feature.session.header.SessionContextRing
import ai.opencode.feature.session.header.SessionContextText
import ai.opencode.feature.session.header.titleBreathing
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.server.model.ModelVariant
import ai.opencode.server.model.isUntitled
import ai.opencode.server.model.OpenCodeContextUsage
import ai.opencode.server.model.OpenCodeExportFormat
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.currentStateAsState
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.animation.core.snap
import androidx.compose.ui.unit.max
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** What Home knows about the open session that its screen shows. */
data class SessionListContext(
    /** The session's project, unless it has none (`global`). */
    val project: OpenCodeProject? = null,
    /** Null when the session isn't a list row (a subagent). */
    val isPinned: Boolean? = null,
    val isUnread: Boolean = false,
    /** Kept out of Catch Up and unread badges. */
    val isMuted: Boolean = false,
    /** Another session needs input or is unread: the drawer button shows it. */
    val elsewhere: DrawerBadge? = null,
    /** Server Flow: the server dropped; a pill above the composer says so. */
    val connection: ServerConnectionNotice? = null,
) {
    companion object {
        const val globalProjectID = "global"

        fun project(session: OpenCodeSession?, projects: List<OpenCodeProject>): OpenCodeProject? {
            if (session == null || session.projectID == globalProjectID) return null
            return projects.firstOrNull { it.id == session.projectID }
        }
    }
}

enum class DrawerBadge { Attention, Unread }

/** How the sheet writes what it shows. */
object SessionSheetFormat {
    /** A home directory reads as `~`: `/Users/kit/code/app` is `~/code/app`. */
    fun displayPath(directory: String): String {
        val parts = directory.split("/")
        val homeDepth = when (if (parts.size > 1) parts[1] else "") {
            "Users", "home" -> if (parts.size > 2 && parts[2].isNotEmpty()) 3 else null
            "root" -> 2
            else -> null
        } ?: return directory
        val rest = parts.drop(homeDepth).joinToString("/")
        return if (rest.isEmpty()) "~" else "~/$rest"
    }

    fun cost(value: Double, locale: Locale = appFormattingLocale()): String =
        NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("USD") }.format(value)
}

/** The sheet's status orb: the session's own activity. */
enum class SessionSheetStatus(@StringRes val textRes: Int) {
    Working(R.string.session_status_working),
    Waiting(R.string.session_status_needs_input),
    Idle(R.string.session_activity_idle),
}

/**
 * What the Session sheet reads from `SessionFeature.State`. The SessionView group builds this from
 * the session store.
 *
 * - [session]: `state.session`.
 * - [hasPendingRequests]: `permissionRequests` or `formRequests` is non-empty.
 * - [isBusy]: `state.isBusy`.
 * - [currentModel], [currentModelInfo], [currentModelName], [currentModelLabel]: the same-named
 *   computed state (`+Models`).
 * - [contextUsage], [contextFraction], [contextLimit]: the same-named computed state.
 * - [sessionCost]: `state.sessionCost`.
 */
data class SessionSheetDetails(
    val session: OpenCodeSession?,
    val hasPendingRequests: Boolean,
    val isBusy: Boolean,
    val currentModel: OpenCodeModelRef?,
    val currentModelInfo: OpenCodeModel?,
    val currentModelName: String,
    val currentModelLabel: String,
    val contextUsage: OpenCodeContextUsage?,
    val contextFraction: Double?,
    val contextLimit: Int?,
    val sessionCost: Double?,
) {
    val status: SessionSheetStatus
        get() = when {
            hasPendingRequests -> SessionSheetStatus.Waiting
            isBusy -> SessionSheetStatus.Working
            else -> SessionSheetStatus.Idle
        }
}

/**
 * Session details and actions, opened from the header's title or the … menu, in a Material bottom
 * sheet just tall enough for its content (drag up for more). Includes [SessionActionPresentations];
 * when the sheet is closed the SessionView shows those on its own (actions started from the header
 * menu).
 *
 * @param sheet the session's `sessionSheet` child store.
 * @param details what the sheet reads from the session ([SessionSheetDetails]).
 * @param onModelTapped `SessionFeature.Action.modelButtonTapped`.
 * @param onMediaTapped `SessionFeature.Action.SessionMediaTapped`.
 * @param onDismissRequest the sheet was swiped or tapped away.
 * @param statusOrb Draws the status orb; the default is a plain dot.
 * @param providerIcon Draws the model's provider mark, sized by the caller.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheetView(
    sheet: StoreOf<SessionSheetFeature.State, SessionSheetFeature.Action>,
    context: SessionListContext,
    details: SessionSheetDetails,
    onModelTapped: () -> Unit,
    onMediaTapped: () -> Unit,
    onDismissRequest: () -> Unit,
    statusOrb: @Composable (status: SessionSheetStatus, size: Dp) -> Unit = { status, size -> DefaultStatusOrb(status, size) },
    providerIcon: (@Composable (providerID: String, canonicalProviderID: String?, size: Dp) -> Unit)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val openMedia = {
        onMediaTapped()
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
    }
    OpenCodeBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        SessionSheetContent(sheet, context, details, onModelTapped, { openMedia() }, statusOrb, providerIcon)
        SessionActionPresentations(sheet, title = details.session?.title)
    }
}

@Composable
private fun SessionSheetContent(
    sheet: StoreOf<SessionSheetFeature.State, SessionSheetFeature.Action>,
    context: SessionListContext,
    details: SessionSheetDetails,
    onModelTapped: () -> Unit,
    onMediaTapped: () -> Unit,
    statusOrb: @Composable (SessionSheetStatus, Dp) -> Unit,
    providerIcon: (@Composable (String, String?, Dp) -> Unit)?,
) {
    val state by sheet.collectState()
    val isListRow = context.isPinned != null
    val notificationsAvailable = remember { dependency(SessionNotificationsClient).isAvailable }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
            .padding(top = 12.dp, bottom = 24.dp)
            .navigationBarsPadding()
            .testTag("session-sheet"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Hero(state, details, context, statusOrb, onRename = { sheet.send(SessionSheetFeature.Action.RenameTapped(details.session?.let { if (it.isUntitled) "" else it.title }.orEmpty())) })
        Tiles(state, isListRow, context, details.session?.title, sheet::send)
        DetailsCard(details, onModelTapped, providerIcon)
        MediaCard(onMediaTapped)
        if (isListRow && notificationsAvailable) {
            AlwaysNotifyCard(isOn = state.alwaysNotifyPending ?: details.session?.alwaysNotify ?: false, isPending = state.alwaysNotifyPending != null) {
                sheet.send(SessionSheetFeature.Action.AlwaysNotifyToggled(it))
            }
        }
        if (isListRow) {
            DeleteRow(enabled = state.operation == null) { sheet.send(SessionSheetFeature.Action.DeleteTapped) }
        }
    }
}

/** Once per process: the sheet's content has been composed and measured ahead of its first open. */
private var sessionSheetPrewarmed = false
private var sessionSheetPrewarming = false

/** The prewarm's sections, one per frame, so no single frame carries all of it. */
private const val PREWARM_SECTIONS = 4

/** After the session has opened and settled, so the warm-up never shares a frame with that. */
private const val PREWARM_DELAY_MS = 1_500L

/**
 * Composes and measures the Session sheet's content once per process, off screen, before it's
 * first opened: its classes load and verify and its composition, text and measuring code warm
 * up then, not inside the frame the sheet opens in. One section per frame (title, tiles,
 * details, the rest), measured but never placed, so nothing is drawn, hit or read by TalkBack,
 * and it sends nothing (a neutral session, no store). The session's in-window sheet reuses
 * the existing ViewRoot rather than paying for a new dialog window on every open.
 *
 * @param enabled false while a sheet is up: the warm-up waits for the next quiet moment.
 * @param details the session's sheet details at warm-up time (only its model is read).
 * @param providerIcon the sheet's provider mark, as [SessionSheetView] draws it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheetPrewarm(
    enabled: Boolean,
    details: () -> SessionSheetDetails,
    providerIcon: (@Composable (providerID: String, canonicalProviderID: String?, size: Dp) -> Unit)? = null,
) {
    if (sessionSheetPrewarmed) return
    var section by remember { mutableIntStateOf(0) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = enabled && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(active) {
        if (!active) {
            section = 0
            return@LaunchedEffect
        }
        delay(PREWARM_DELAY_MS)
        // One warm-up at a time: session pages composed side by side take turns.
        if (sessionSheetPrewarming || sessionSheetPrewarmed) return@LaunchedEffect
        sessionSheetPrewarming = true
        try {
            for (next in 1..PREWARM_SECTIONS) {
                section = next
                // Composed in this frame, measured in its layout pass.
                withFrameNanos { }
                withFrameNanos { }
            }
            sessionSheetPrewarmed = true
        } finally {
            sessionSheetPrewarming = false
            section = 0
        }
    }
    if (section == 0) return
    val warm = remember { prewarmModel(details()) }
    Box(
        Modifier
            .clearAndSetSemantics { }
            .layout { measurable, constraints ->
                val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 1080
                measurable.measure(Constraints(maxWidth = width))
                // Measured, never placed: nothing draws or takes touches.
                layout(0, 0) { }
            },
    ) {
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (section) {
                1 -> Hero(warm.state, warm.details, warm.context, statusOrb = { _, size ->
                    AgentActivityGlyph(AgentActivitySource.Subagent(SubagentActivity(sessionID = null, phase = SubagentActivity.Phase.idle)), size = size)
                }, onRename = { })
                2 -> Tiles(warm.state, isListRow = true, warm.context, title = null, send = { })
                3 -> DetailsCard(warm.details, onModelTapped = { }, providerIcon)
                else -> {
                    MediaCard { }
                    AlwaysNotifyCard(isOn = false, isPending = false) { }
                    DeleteRow(enabled = true) { }
                    rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    OpenCodeSheetHandle()
                }
            }
        }
    }
}

private class SessionSheetPrewarmModel(val state: SessionSheetFeature.State, val details: SessionSheetDetails, val context: SessionListContext)

/** A neutral session with the real model, so the warm-up lays out what the sheet will. */
private fun prewarmModel(real: SessionSheetDetails) = SessionSheetPrewarmModel(
    state = SessionSheetFeature.State(server = OpenCodeServer(url = "", username = "", password = ""), sessionID = ""),
    details = real.copy(session = null, hasPendingRequests = false, isBusy = false),
    context = SessionListContext(isPinned = false),
)

// MARK: Hero: status orb, title, where it runs

@Composable
private fun Hero(
    state: SessionSheetFeature.State,
    details: SessionSheetDetails,
    context: SessionListContext,
    statusOrb: @Composable (SessionSheetStatus, Dp) -> Unit,
    onRename: () -> Unit,
) {
    val session = details.session
    val isBusy = state.operation != null
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val orbSize = with(density) { (22 * fontScale).dp }
    // From the orb's centre down to the title's first baseline: half a cap height.
    val orbToBaseline = with(density) { (8 * fontScale).dp.roundToPx() }
    val isAutoRenaming = state.operation == SessionSheetFeature.Operation.AutoRenaming
    val renameFailed = stringResource(R.string.session_sheet_rename_failed)
    val statusDescription = stringResource(R.string.session_sheet_status_description)
    val statusText = stringResource(details.status.textRes)
    val renameSessionLabel = stringResource(R.string.session_rename_title)
    val sessionTitle = session?.title
    val renameValue = when {
        sessionTitle != null && state.autoRenameFailed -> stringResource(R.string.session_list_two, sessionTitle, renameFailed)
        state.autoRenameFailed -> renameFailed
        else -> sessionTitle ?: ""
    }
    // The title's own height: its target grows only downward from it, over the location line, so
    // nothing moves.
    var titleHeight by remember { mutableStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                // The orb's centre sits half a cap height above the title's first baseline.
                .alignBy { it.measuredHeight / 2 + orbToBaseline }
                .clearAndSetSemantics {
                    contentDescription = statusDescription
                    stateDescription = statusText
                }
                .testTag("session-sheet-status"),
        ) {
            statusOrb(details.status, orbSize)
        }
        Box(Modifier.alignBy(FirstBaseline).weight(1f)) {
            // The Rename target: the title's height, grown downward to 48 dp over the location
            // line (which takes no touches), so nothing moves. First, so TalkBack reads it before
            // the location; the text above it takes no touches.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(max(with(density) { titleHeight.toDp() }, 48.dp))
                    .clickable(enabled = !isBusy, onClickLabel = renameSessionLabel, role = Role.Button, onClick = onRename)
                    .clearAndSetSemantics {
                        contentDescription = renameSessionLabel
                        stateDescription = renameValue
                        role = Role.Button
                        if (isBusy) disabled()
                        onClick { onRename(); true }
                    }
                    .testTag("session-sheet-title"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.fillMaxWidth().onSizeChanged { titleHeight = it.height }.clearAndSetSemantics { }) {
                    // Auto Rename's new title waits for the breath to ease back to full.
                    BreathingTitleHold(identity = session?.id, title = session?.title ?: "", isBreathing = isAutoRenaming) { title, animates ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AnimatedContent(
                                targetState = title,
                                transitionSpec = {
                                    // Without animation it swaps in place.
                                    if (!animates) fadeIn(snap()) togetherWith fadeOut(snap())
                                    else MotionTransition.roll(MotionRollDirection.Up, reduceMotion, density, Motion.swap).content
                                },
                                label = "sessionSheetTitle",
                                modifier = Modifier.weight(1f, fill = false),
                            ) { shown ->
                                Text(
                                    shown,
                                    style = OpenCodeTextStyles.title2.copy(fontWeight = FontWeight.Bold),
                                    color = OpenCodeTheme.colors.text,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.titleBreathing(isAutoRenaming),
                                )
                            }
                            if (state.operation == SessionSheetFeature.Operation.Renaming) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
                if (session != null) {
                    LocationLine(session, context.project, state.autoRenameFailed)
                }
            }
        }
    }
}


@Composable
private fun LocationLine(session: OpenCodeSession, project: OpenCodeProject?, autoRenameFailed: Boolean) {
    val name = project?.name ?: session.directory.trimEnd('/').substringAfterLast('/').ifEmpty { session.directory }
    Column(
        Modifier.semantics(mergeDescendants = true) {}.testTag("session-sheet-location"),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (autoRenameFailed) {
            Text(stringResource(R.string.session_sheet_rename_failed), style = OpenCodeTextStyles.footnote, color = OpenCodeTheme.colors.secondaryText)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(name, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, modifier = Modifier.alignByBaseline())
            Text(
                SessionSheetFormat.displayPath(session.directory),
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.tertiaryText,
                maxLines = 1,
                overflow = TextOverflow.StartEllipsis,
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
        }
    }
}

@Composable
private fun DefaultStatusOrb(status: SessionSheetStatus, size: Dp) {
    val color = when (status) {
        SessionSheetStatus.Waiting -> OpenCodeTheme.colors.accent
        SessionSheetStatus.Working -> OpenCodeTheme.colors.text
        SessionSheetStatus.Idle -> OpenCodeTheme.colors.tertiaryText
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 0.45f).background(color, RoundedCornerShape(50)))
    }
}

// MARK: Tiles

@Composable
private fun Tiles(
    state: SessionSheetFeature.State,
    isListRow: Boolean,
    context: SessionListContext,
    title: String?,
    send: (SessionSheetFeature.Action) -> Unit,
) {
    val isBusy = state.operation != null
    val untitledSession = stringResource(R.string.session_untitled_title)
    val tiles = buildList<@Composable () -> Unit> {
        if (isListRow) {
            add { ToggleTile(stringResource(R.string.session_pin), stringResource(R.string.session_pinned), SFSymbol.Pin, SFSymbol.PinFill, context.isPinned == true, "pin") { send(SessionSheetFeature.Action.PinTapped) } }
            add { ToggleTile(stringResource(R.string.session_unread), stringResource(R.string.session_unread), SFSymbol.EnvelopeOpen, SFSymbol.EnvelopeBadgeFill, context.isUnread, "read") { send(SessionSheetFeature.Action.ReadTapped) } }
            add { ToggleTile(stringResource(R.string.session_mute), stringResource(R.string.session_muted), SFSymbol.Bell, SFSymbol.BellSlashFill, context.isMuted, "mute") { send(SessionSheetFeature.Action.MuteTapped) } }
        }
        add {
            ActionTile(stringResource(R.string.session_compact), SFSymbol.RectangleCompressVertical, state.operation == SessionSheetFeature.Operation.Compacting, isBusy, "compact") {
                send(SessionSheetFeature.Action.CompactTapped)
            }
        }
        add {
            ActionTile(stringResource(R.string.session_fork), SFSymbol.ArrowTriangleBranch, state.operation == SessionSheetFeature.Operation.Forking, isBusy, "fork") {
                send(SessionSheetFeature.Action.ForkTapped)
            }
        }
        add { ExportTile(state.operation is SessionSheetFeature.Operation.Exporting, isBusy) { send(SessionSheetFeature.Action.ExportTapped(it, title ?: untitledSession)) } }
    }
    // Six across; at accessibility sizes they wrap into rows of three, then two.
    val fontScale = LocalDensity.current.fontScale
    val across = minOf(tiles.size, if (fontScale >= 2f) 2 else if (isAccessibilityFontScale(fontScale)) 3 else tiles.size)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in tiles.chunked(across)) {
            Row(Modifier.fillMaxWidth()) {
                for (tile in row) Box(Modifier.weight(1f)) { tile() }
                repeat(across - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Pin, Unread and Mute: the caption may read as the state (Pinned), but TalkBack hears one name with its value. */
@Composable
private fun ToggleTile(title: String, onTitle: String, symbol: SFSymbol, onSymbol: SFSymbol, isOn: Boolean, id: String, onClick: () -> Unit) {
    val onState = stringResource(R.string.session_toggle_on_description)
    val offState = stringResource(R.string.session_toggle_off_description)
    Box(
        Modifier
            .testTag("session-sheet-$id")
            .clickable(role = Role.Switch, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = if (isOn) onState else offState
                role = Role.Switch
            },
    ) {
        SessionSheetTile(title = if (isOn) onTitle else title, symbol = if (isOn) onSymbol else symbol, isOn = isOn)
    }
}

@Composable
private fun ActionTile(title: String, symbol: SFSymbol, inFlight: Boolean, isBusy: Boolean, id: String, onClick: () -> Unit) {
    Box(
        Modifier
            .testTag("session-sheet-$id")
            .clickable(enabled = !isBusy, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = title
                role = Role.Button
            },
    ) {
        SessionSheetTile(title = title, symbol = symbol, inFlight = inFlight, dimmed = isBusy && !inFlight, symbolChanges = false)
    }
}

@Composable
private fun ExportTile(inFlight: Boolean, isBusy: Boolean, onExport: (OpenCodeExportFormat) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val exportLabel = stringResource(R.string.session_export)
    Box {
        Box(
            Modifier
                .testTag("session-sheet-export")
                .clickable(enabled = !isBusy, role = Role.Button) { expanded = true }
                .clearAndSetSemantics {
                    contentDescription = exportLabel
                    role = Role.Button
                },
        ) {
            SessionSheetTile(title = exportLabel, symbol = SFSymbol.SquareAndArrowUp, inFlight = inFlight, dimmed = isBusy && !inFlight, symbolChanges = false)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (format in OpenCodeExportFormat.entries) {
                DropdownMenuItem(
                    text = { Text(if (format == OpenCodeExportFormat.Markdown) stringResource(R.string.session_export_markdown) else stringResource(R.string.session_export_json)) },
                    onClick = {
                        expanded = false
                        onExport(format)
                    },
                )
            }
        }
    }
}

/**
 * One of the sheet's quiet tiles: a monochrome glyph over its caption, no fill. A toggle that's
 * on shows its filled glyph and a semibold caption.
 *
 * @param symbolChanges a toggle's glyph cross-fades between its states; an action's never changes,
 *   so it skips the transition's machinery.
 */
@Composable
private fun SessionSheetTile(title: String, symbol: SFSymbol, isOn: Boolean = false, inFlight: Boolean = false, dimmed: Boolean = false, symbolChanges: Boolean = true) {
    val iconSize = with(LocalDensity.current) { (22 * fontScale).dp }
    val reduceMotion = rememberReduceMotion()
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(top = 8.dp, bottom = 6.dp)
            .alpha(if (dimmed) 0.35f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.height(iconSize + 4.dp), contentAlignment = Alignment.Center) {
            if (inFlight) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (!symbolChanges) {
                OpenCodeIcon(symbol, contentDescription = null, size = iconSize, tint = OpenCodeTheme.colors.text)
            } else {
                AnimatedContent(
                    symbol,
                    transitionSpec = { if (reduceMotion) fadeIn(snap()) togetherWith fadeOut(snap()) else fadeIn() togetherWith fadeOut() },
                    label = "tileSymbol",
                ) {
                    OpenCodeIcon(it, contentDescription = null, size = iconSize, tint = OpenCodeTheme.colors.text)
                }
            }
        }
        // A long caption shrinks to 75 % before it truncates.
        val style = OpenCodeTextStyles.caption1.copy(fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal)
        BasicText(
            title,
            style = style.copy(color = if (isOn) OpenCodeTheme.colors.text else OpenCodeTheme.colors.secondaryText),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            autoSize = remember(style.fontSize) { CaptionAutoSize(min = style.fontSize * 0.75f, max = style.fontSize) },
        )
    }
}

/**
 * The caption at full size when it fits, which takes one layout (a step-based search lays each
 * caption out five or six times); only a caption that doesn't fit searches down, in 0.25 sp steps,
 * for the largest size that does.
 */
private data class CaptionAutoSize(val min: TextUnit, val max: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        if (!performLayout(constraints, text, max).overflows()) return max
        var fits = min.value
        var tooBig = max.value
        while (tooBig - fits > 0.25f) {
            val size = (fits + tooBig) / 2
            if (performLayout(constraints, text, size.sp).overflows()) tooBig = size else fits = size
        }
        return fits.sp
    }

    private fun TextLayoutResult.overflows(): Boolean =
        didOverflowWidth || didOverflowHeight || (lineCount > 0 && isLineEllipsized(lineCount - 1))
}

// MARK: Model, context, cost

@Composable
private fun cardFill(): Color {
    val colors = OpenCodeTheme.colors
    return when {
        colors.isDark && colors.highContrast -> colors.text.copy(alpha = 0.11f)
        colors.isDark -> colors.text.copy(alpha = 0.08f)
        colors.highContrast -> colors.text.copy(alpha = 0.1f)
        else -> colors.text.copy(alpha = 0.05f)
    }
}

/** The desktop card: small radius, a hairline ring around its quiet fill. */
private val cardShape = RoundedCornerShape(OpenCodeRadius.card)

@Composable
private fun DetailsCard(details: SessionSheetDetails, onModelTapped: () -> Unit, providerIcon: (@Composable (String, String?, Dp) -> Unit)?) {
    Column(Modifier.fillMaxWidth().background(cardFill(), cardShape).openCodeHairline(cardShape)) {
        ModelRow(details, onModelTapped, providerIcon)
        HorizontalDivider(Modifier.padding(start = 16.dp))
        ContextRow(details)
        HorizontalDivider(Modifier.padding(start = 16.dp))
        CostRow(details)
    }
}

private val isAccessibilitySize: Boolean
    @Composable get() = isAccessibilityFontScale(LocalDensity.current.fontScale)

/** Key and value on one line; stacked at accessibility sizes so the value keeps its room. */
@Composable
private fun DetailRow(key: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    val stacked = isAccessibilitySize
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .padding(horizontal = 16.dp, vertical = if (stacked) 10.dp else 0.dp)
    if (stacked) {
        Column(rowModifier, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            Text(key, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText)
            value()
        }
    } else {
        Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(key, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText)
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            value()
        }
    }
}

@Composable
private fun ModelRow(details: SessionSheetDetails, onModelTapped: () -> Unit, providerIcon: (@Composable (String, String?, Dp) -> Unit)?) {
    // The current reasoning level, when the model has levels and one is chosen.
    val level = details.currentModel?.variant?.takeIf { it != "default" }
    val variants = details.currentModelInfo?.variants?.takeIf { it.isNotEmpty() }
    val currentLevel = if (level != null && variants != null) ModelVariant.sorted(variants) to level else null
    val iconSize = with(LocalDensity.current) { (18 * fontScale).dp }
    val modelLabel = stringResource(R.string.session_sheet_model)
    DetailRow(
        modelLabel,
        Modifier
            .testTag("session-sheet-model")
            .clickable(role = Role.Button, onClick = onModelTapped)
            .clearAndSetSemantics {
                contentDescription = modelLabel
                stateDescription = details.currentModelLabel
                role = Role.Button
            },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            val providerID = details.currentModel?.providerID
            if (providerID != null && providerIcon != null) providerIcon(providerID, details.currentModelInfo?.canonicalProviderID, iconSize)
            Text(details.currentModelName, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (currentLevel != null) {
                ModelLevelBars(currentLevel.first, currentLevel.second, Modifier.padding(start = 3.dp), color = OpenCodeTheme.colors.text)
                Text(ModelVariant.label(currentLevel.second), style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText, maxLines = 1)
            }
            OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
        }
    }
}

@Composable
private fun ContextRow(details: SessionSheetDetails) {
    val usage = details.contextUsage
    val alignment = if (isAccessibilitySize) RollingNumberAlignment.Start else RollingNumberAlignment.End
    val ringSize = with(LocalDensity.current) { (16 * fontScale).dp }
    val contextLabel = stringResource(R.string.session_sheet_context)
    val unknownValue = stringResource(R.string.session_sheet_unknown_description)
    DetailRow(
        contextLabel,
        Modifier.testTag("session-sheet-context").clearAndSetSemantics {
            contentDescription = contextLabel
            stateDescription = usage?.let { SessionContextText.accessibilityValue(it.tokens, details.contextLimit) } ?: unknownValue
        },
    ) {
        val fraction = details.contextFraction
        when {
            fraction != null -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SessionContextRing(fraction, Modifier.size(ringSize), lineWidth = 2.2.dp)
                RollingNumber("${SessionContextRing.percent(fraction)}%", style = OpenCodeTextStyles.callout, alignment = alignment)
            }
            usage != null -> RollingNumber(stringResource(R.string.session_sheet_context_tokens, NumberFormat.getIntegerInstance(appFormattingLocale()).format(usage.tokens)), style = OpenCodeTextStyles.callout, alignment = alignment)
            else -> Text("—", style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

@Composable
private fun CostRow(details: SessionSheetDetails) {
    val cost = details.sessionCost
    val alignment = if (isAccessibilitySize) RollingNumberAlignment.Start else RollingNumberAlignment.End
    val costLabel = stringResource(R.string.session_sheet_cost)
    val unknownValue = stringResource(R.string.session_sheet_unknown_description)
    DetailRow(
        costLabel,
        Modifier.testTag("session-sheet-cost").clearAndSetSemantics {
            contentDescription = costLabel
            stateDescription = cost?.let { SessionSheetFormat.cost(it) } ?: unknownValue
        },
    ) {
        if (cost != null) {
            RollingNumber(SessionSheetFormat.cost(cost), style = OpenCodeTextStyles.callout, alignment = alignment)
        } else {
            Text("—", style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

// MARK: Media

@Composable
private fun MediaCard(onClick: () -> Unit) {
    val mediaLabel = stringResource(R.string.session_sheet_media)
    val mediaValue = stringResource(R.string.session_sheet_media_value)
    DetailRow(
        mediaLabel,
        Modifier
            .testTag("session-sheet-media")
            .background(cardFill(), cardShape)
            .openCodeHairline(cardShape)
            .clip(cardShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = mediaLabel
                stateDescription = mediaValue
                role = Role.Button
            },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(mediaValue, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            OpenCodeIcon(SFSymbol.ChevronRight, contentDescription = null, size = 16.dp, tint = OpenCodeTheme.colors.tertiaryText)
        }
    }
}

// MARK: Always Notify

@Composable
private fun AlwaysNotifyCard(isOn: Boolean, isPending: Boolean, onToggle: (Boolean) -> Unit) {
    val message = if (LocalConfiguration.current.smallestScreenWidthDp >= 600) {
        stringResource(R.string.session_always_notify_message_tablet)
    } else {
        stringResource(R.string.session_always_notify_message_phone)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("session-sheet-always-notify")
                .background(cardFill(), cardShape)
                .openCodeHairline(cardShape)
                .clip(cardShape)
                .toggleable(value = isOn, enabled = !isPending, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = 16.dp, vertical = if (isAccessibilitySize) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.session_always_notify), style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
            OpenCodeSwitch(checked = isOn, onCheckedChange = null, enabled = !isPending)
        }
        Text(
            message,
            style = OpenCodeTextStyles.footnote,
            color = OpenCodeTheme.colors.secondaryText,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// MARK: Delete

@Composable
private fun DeleteRow(enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("session-sheet-delete")
            .background(cardFill(), cardShape)
            .clip(cardShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = if (isAccessibilitySize) 10.dp else 0.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            stringResource(R.string.session_delete_session),
            style = OpenCodeTextStyles.callout,
            color = OpenCodeTheme.colors.destructive.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/**
 * The model picker's level bars: one rising bar per reasoning level, filled up to the chosen one.
 * Decorative (hidden from TalkBack); the level's name is spoken beside it.
 */
@Composable
fun ModelLevelBars(variants: List<String>, level: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val height = with(LocalDensity.current) { (13 * fontScale).dp }
    val unit = height / 13
    val filled = ModelLevelBars.filled(variants, level)
    Row(
        modifier.height(height).clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(unit * 2),
        verticalAlignment = Alignment.Bottom,
    ) {
        ModelLevelBars.heights(variants.size).forEachIndexed { index, share ->
            Box(
                Modifier
                    .width(unit * 3)
                    .height(height * share.toFloat())
                    .background(color.copy(alpha = if (index < filled) color.alpha else color.alpha * 0.28f), RoundedCornerShape(unit * 1.2f)),
            )
        }
    }
}

object ModelLevelBars {
    /** Bars filled for [level]: its place among [variants], counting from one. */
    fun filled(variants: List<String>, level: String): Int = variants.indexOf(level).let { if (it >= 0) it + 1 else 0 }

    /** Each bar's height as a share of the tallest: from 5/13 up to the full height. */
    fun heights(count: Int): List<Double> = (0 until count).map { i -> (5 + 8 * i.toDouble() / maxOf(1, count - 1).toDouble()) / 13 }
}

/**
 * Rename, failure, delete confirmation, and export sharing for the Session sheet's actions,
 * wherever they were started (the sheet or the header menu). Shown inside [SessionSheetView]; the
 * SessionView shows it itself while the sheet is closed.
 *
 * @param title the session's title, for the delete confirmation.
 */
@Composable
fun SessionActionPresentations(sheet: StoreOf<SessionSheetFeature.State, SessionSheetFeature.Action>, title: String?) {
    val state by sheet.collectState()
    if (state.isRenaming) {
        // The alert's field takes the keyboard as it opens.
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { focus.requestFocus() }
        OpenCodeAlertDialog(
            onDismissRequest = { sheet.send(SessionSheetFeature.Action.IsRenamingChanged(false)) },
            title = { Text(stringResource(R.string.session_rename_title)) },
            text = {
                OpenCodeTextField(
                    value = state.renameDraft,
                    onValueChange = { sheet.send(SessionSheetFeature.Action.RenameDraftChanged(it)) },
                    placeholder = stringResource(R.string.session_rename_placeholder),
                    trailingIcon = AlertTextFields.clearButton(state.renameDraft) {
                        sheet.send(SessionSheetFeature.Action.RenameDraftChanged(""))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { sheet.send(SessionSheetFeature.Action.RenameSubmitted) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            },
            // Three actions stack, Cancel last.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { sheet.send(SessionSheetFeature.Action.AutoRenameTapped) }) { Text(stringResource(R.string.session_auto_rename)) }
                    TextButton(onClick = { sheet.send(SessionSheetFeature.Action.RenameSubmitted) }) { Text(stringResource(CoreR.string.common_rename)) }
                    TextButton(onClick = { sheet.send(SessionSheetFeature.Action.IsRenamingChanged(false)) }) { Text(stringResource(CoreR.string.common_cancel)) }
                }
            },
        )
    }
    state.failure?.let { failure ->
        OpenCodeAlertDialog(
            onDismissRequest = { sheet.send(SessionSheetFeature.Action.FailureDismissed) },
            title = { Text(failure.title) },
            text = { Text(failure.message) },
            confirmButton = { TextButton(onClick = { sheet.send(SessionSheetFeature.Action.FailureDismissed) }) { Text(stringResource(CoreR.string.common_ok)) } },
        )
    }
    if (state.confirmsDeletion) {
        OpenCodeAlertDialog(
            onDismissRequest = { sheet.send(SessionSheetFeature.Action.ConfirmsDeletionChanged(false)) },
            title = { Text(stringResource(R.string.session_delete_confirm_title, title ?: stringResource(R.string.session_untitled_title)), modifier = Modifier.semantics { heading() }) },
            text = { Text(stringResource(R.string.session_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { sheet.send(SessionSheetFeature.Action.DeleteConfirmed) }) {
                    Text(stringResource(CoreR.string.common_delete), color = OpenCodeTheme.colors.destructive)
                }
            },
            dismissButton = {
                TextButton(onClick = { sheet.send(SessionSheetFeature.Action.ConfirmsDeletionChanged(false)) }) { Text(stringResource(CoreR.string.common_cancel)) }
            },
        )
    }
    ExportShareDismissal(isSharing = state.exportedFile != null) { sheet.send(SessionSheetFeature.Action.ExportDismissed) }
}

/**
 * The share sheet itself is started by [SessionExportClient.share]; this only notices it closing:
 * after the app pauses for it and resumes, the export is dismissed (and its file removed).
 */
@Composable
private fun ExportShareDismissal(isSharing: Boolean, onDismiss: () -> Unit) {
    if (!isSharing) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val dismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(lifecycle) {
        var paused = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> paused = true
                Lifecycle.Event.ON_RESUME -> if (paused) dismiss()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
