package ai.opencode.devtools.traces

import ai.opencode.core.motion.MotionSpring
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeShape
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.OpenCodeSwitchDefaults
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.devtools.traces.TraceUploadsFeature.Action
import ai.opencode.devtools.traces.TraceUploadsFeature.State
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * Home's host for the Report a Problem sheet, its toast and the launch-time crash-marker check.
 * Compose it once over Home, after Home's content.
 */
@Composable
fun TraceUploadsHost(store: StoreOf<State, Action>) {
    LaunchedEffect(store) { store.send(Action.Task) }
    val report by store.observe { it.report }
    val toast by store.observe { it.toast }
    val sheetShown = report != null
    // Over Home; while the sheet is up, the sheet's own window shows it above its top edge.
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentAlignment = Alignment.TopCenter) {
        ProblemReportToastView(if (sheetShown) null else toast)
    }
    if (sheetShown) ProblemReportSheet(store, toast)
}

/** A note, Include Screenshot, what's sent, and Send / Sending… / Sent / Try Again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProblemReportSheet(store: StoreOf<State, Action>, toast: State.Toast?) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val report by store.observe { it.report }
    ModalBottomSheet(
        onDismissRequest = { store.send(Action.Dismissed) },
        sheetState = sheetState,
        containerColor = OpenCodeTheme.colors.groupedCanvas,
        // The canvas isn't a scheme colour, so contentColorFor would give a dimmed default.
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag("problem-report-sheet"),
    ) {
        ToastAboveSheet(toast)
        val current = report ?: return@ModalBottomSheet
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(top = 8.dp, bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Report a Problem",
                    style = OpenCodeTextStyles.title3.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(onClick = { store.send(Action.CancelTapped) }, modifier = Modifier.testTag("problem-report-cancel")) {
                    Text("Cancel", style = OpenCodeTextStyles.body)
                }
            }
            val editable = current.phase == State.Report.Phase.Composing || current.phase == State.Report.Phase.Failed
            NoteField(current.note, editable) { store.send(Action.NoteChanged(it)) }
            ScreenshotToggle(current.includesScreenshot, editable) { store.send(Action.IncludeScreenshotChanged(it)) }
            Text(
                State.summaryLine,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.testTag("problem-report-summary"),
            )
            Footer(current, send = store::send)
        }
    }
}

@Composable
private fun NoteField(note: String, enabled: Boolean, onChange: (String) -> Unit) {
    TextField(
        value = note,
        onValueChange = onChange,
        enabled = enabled,
        placeholder = { Text("Note (optional)") },
        minLines = 3,
        maxLines = 6,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = OpenCodeShape.panel,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().testTag("problem-report-note"),
    )
}

@Composable
private fun ScreenshotToggle(isOn: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Surface(
        shape = OpenCodeShape.panel,
        color = OpenCodeSurfaces.color,
        border = OpenCodeSurfaces.border,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .toggleable(value = isOn, enabled = enabled, role = Role.Switch, onValueChange = onChange)
                .semantics { stateDescription = if (isOn) "On" else "Off" }
                .padding(horizontal = 14.dp)
                .testTag("problem-report-include-screenshot"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Include Screenshot", style = OpenCodeTextStyles.body, modifier = Modifier.weight(1f))
            Switch(checked = isOn, onCheckedChange = null, enabled = enabled, colors = OpenCodeSwitchDefaults.colors())
        }
    }
}

@Composable
private fun Footer(report: State.Report, send: (Action) -> Unit) {
    when (report.phase) {
        State.Report.Phase.Composing -> OpenCodePrimaryButton(
            text = "Send",
            onClick = { send(Action.SendTapped) },
            modifier = Modifier.fillMaxWidth().testTag("problem-report-send"),
        )

        State.Report.Phase.Sending -> Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {}
                .testTag("problem-report-sending"),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Sending…", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium), color = OpenCodeTheme.colors.secondaryText)
        }

        State.Report.Phase.Sent -> Surface(
            shape = OpenCodeShape.panel,
            color = OpenCodeSurfaces.color,
            border = OpenCodeSurfaces.border,
            modifier = Modifier.fillMaxWidth().testTag("problem-report-sent"),
        ) {
            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    OpenCodeIcon(SFSymbol.CheckmarkCircleFill, contentDescription = null, size = OpenCodeIconSize.small, tint = systemGreen())
                    Spacer(Modifier.size(8.dp))
                    Text("Sent", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold))
                }
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { send(Action.CopyReportIDTapped) }
                        .padding(horizontal = 12.dp)
                        .clearAndSetSemantics {
                            contentDescription = "Copy Report ID"
                            stateDescription = report.shortID
                        }
                        .testTag("problem-report-copy-id"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(report.shortID, style = OpenCodeTextStyles.subheadline.copy(fontFamily = OpenCodeFonts.monospace))
                    OpenCodeIcon(
                        if (report.copiedReportID) SFSymbol.Checkmark else SFSymbol.DocOnDoc,
                        contentDescription = null,
                        size = OpenCodeIconSize.small,
                    )
                }
            }
        }

        State.Report.Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.semantics(mergeDescendants = true) {}.testTag("problem-report-error"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OpenCodeIcon(SFSymbol.ExclamationmarkTriangleFill, contentDescription = null, size = OpenCodeIconSize.small, tint = OpenCodeTheme.colors.warning)
                Spacer(Modifier.size(8.dp))
                Text("Couldn’t send report", style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium))
            }
            OpenCodePrimaryButton(
                text = "Try Again",
                onClick = { send(Action.TryAgainTapped) },
                modifier = Modifier.fillMaxWidth().testTag("problem-report-try-again"),
            )
        }
    }
}

/** The toast just above the sheet's top edge, in the sheet's own window (it's drawn above Home's). */
@Composable
private fun ToastAboveSheet(toast: State.Toast?) {
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val provider = remember(gap) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
                IntOffset((windowSize.width - popupContentSize.width) / 2, (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(0))
        }
    }
    // Kept composed while it leaves, so it slides out.
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    if (shown == null) return
    Box(Modifier.fillMaxWidth()) {
        Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
            ProblemReportToastView(toast)
        }
    }
}

/** “Sent to session”, for a moment; announced, never hit-tested. */
@Composable
fun ProblemReportToastView(toast: State.Toast?) {
    val reduceMotion = rememberReduceMotion()
    var last by remember { mutableStateOf(toast) }
    if (toast != null) last = toast
    val spring = SNAPPY
    AnimatedVisibility(
        visible = toast != null,
        enter = if (reduceMotion) fadeIn(spring.spec()) else slideInVertically(spring.spec()) { -it } + fadeIn(spring.spec()),
        exit = if (reduceMotion) fadeOut(spring.spec()) else slideOutVertically(spring.spec()) { -it } + fadeOut(spring.spec()),
    ) {
        val shown = last ?: return@AnimatedVisibility
        val sent = shown == State.Toast.SentToSession
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .testTag("problem-report-toast"),
        ) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OpenCodeIcon(
                    if (sent) SFSymbol.CheckmarkCircleFill else SFSymbol.ExclamationmarkTriangleFill,
                    contentDescription = null,
                    size = OpenCodeIconSize.small,
                    tint = if (sent) systemGreen() else OpenCodeTheme.colors.warning,
                )
                Spacer(Modifier.size(8.dp))
                Text(shown.message, style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/** A 0.5 s spring with a little bounce. */
private val SNAPPY = MotionSpring(0.5, 0.15)

@Composable
private fun systemGreen(): Color = if (OpenCodeTheme.colors.isDark) Color(0.1882f, 0.8196f, 0.3451f) else Color(0.2039f, 0.7804f, 0.349f)
