package ai.opencode.devtools

import ai.opencode.core.ui.OpenCodeFonts
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.diagnostics.DiagnosticsEvent
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.collectState
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeSwitchDefaults
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.SFSymbol
import ai.opencode.devtools.perf.TimeScaleLabel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Settings › Developer. [onClose] shows a back arrow on the main page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevToolsView(
    store: StoreOf<DevToolsFeature.State, DevToolsFeature.Action>,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    onOpenPlaygrounds: (() -> Unit)? = null,
    onOpenServerDetails: (() -> Unit)? = null,
) {
    LaunchedEffect(store) { store.send(DevToolsFeature.Action.Task) }
    val state by store.collectState()
    val send = store::send
    BackHandler(enabled = state.route != DevToolsFeature.Route.Main) { send(DevToolsFeature.Action.BackTapped) }
    BackHandler(enabled = state.route == DevToolsFeature.Route.Main && onClose != null) { onClose?.invoke() }
    val title = when (state.route) {
        DevToolsFeature.Route.Main -> "Developer"
        DevToolsFeature.Route.Troubleshooting -> "Troubleshooting"
        DevToolsFeature.Route.Logs -> "App Log"
        DevToolsFeature.Route.Network -> "Network"
        DevToolsFeature.Route.Lab -> "Session Lab"
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            OpenCodeTopBar(
                title = { Text(title, Modifier.semantics { heading() }) },
                navigation = {
                    val back = if (state.route != DevToolsFeature.Route.Main) ({ send(DevToolsFeature.Action.BackTapped) }) else onClose
                    if (back != null) OpenCodeTopBarBackButton(onClick = back)
                },
                actions = { Actions(state, send) },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (state.route) {
                DevToolsFeature.Route.Main -> MainPage(state, send, onOpenPlaygrounds, onOpenServerDetails)
                DevToolsFeature.Route.Troubleshooting -> TroubleshootingPage(state)
                DevToolsFeature.Route.Logs -> LogsPage(state, send)
                DevToolsFeature.Route.Network -> NetworkPage(state, send)
                DevToolsFeature.Route.Lab -> LabPage(state, send)
            }
            state.message?.let { message ->
                Text(
                    message,
                    Modifier.align(Alignment.BottomCenter).padding(24.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun Actions(state: DevToolsFeature.State, send: (DevToolsFeature.Action) -> Unit) {
    when (state.route) {
        DevToolsFeature.Route.Troubleshooting, DevToolsFeature.Route.Network -> {
            var menu by remember { mutableStateOf(false) }
            Box {
                OpenCodeIconButton(SFSymbol.SquareAndArrowUp, "Share", onClick = { menu = true }, style = OpenCodeIconButtonStyle.Standard, enabled = state.events.isNotEmpty())
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Text") }, onClick = { menu = false; send(DevToolsFeature.Action.ShareTimelineTapped(json = false)) })
                    DropdownMenuItem(text = { Text("JSON") }, onClick = { menu = false; send(DevToolsFeature.Action.ShareTimelineTapped(json = true)) })
                }
            }
            OpenCodeIconButton(
                SFSymbol.Trash,
                "Clear",
                onClick = { send(DevToolsFeature.Action.ClearTapped) },
                style = OpenCodeIconButtonStyle.Standard,
                tint = MaterialTheme.colorScheme.error,
                enabled = state.events.isNotEmpty(),
            )
        }
        DevToolsFeature.Route.Logs -> {
            OpenCodeIconButton(SFSymbol.ArrowClockwise, "Refresh", onClick = { send(DevToolsFeature.Action.Reload) }, style = OpenCodeIconButtonStyle.Standard)
            OpenCodeIconButton(SFSymbol.DocOnDoc, "Copy", onClick = { send(DevToolsFeature.Action.CopyLogTapped) }, style = OpenCodeIconButtonStyle.Standard)
            OpenCodeIconButton(SFSymbol.SquareAndArrowUp, "Share", onClick = { send(DevToolsFeature.Action.ShareLogTapped) }, style = OpenCodeIconButtonStyle.Standard)
        }
        else -> Unit
    }
}

// Main page

@Composable
private fun MainPage(
    state: DevToolsFeature.State,
    send: (DevToolsFeature.Action) -> Unit,
    onOpenPlaygrounds: (() -> Unit)?,
    onOpenServerDetails: (() -> Unit)?,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        section("Recording and Diagnostics")
        item {
            SwitchRow(
                "Developer Mode",
                null,
                state.developerMode,
                hint = "Toggle adding Report a Problem to the session menu and uploading voice calls to the OpenCode log server",
            ) { send(DevToolsFeature.Action.DeveloperModeChanged(it)) }
        }
        item {
            SwitchRow(
                "Record Diagnostics",
                null,
                state.recordDiagnostics,
                enabled = !state.developerMode,
                hint = "Toggle recording a bounded timeline of timing and network metadata on this phone",
            ) { send(DevToolsFeature.Action.RecordDiagnosticsChanged(it)) }
        }
        if (state.isInternalBuild && state.recordDiagnostics) {
            item {
                SwitchRow("Include Transcripts", null, state.includesTranscripts, hint = "Toggle keeping a short excerpt of each voice turn in the timeline") {
                    send(DevToolsFeature.Action.IncludeTranscriptsChanged(it))
                }
            }
        }
        if (state.developerMode) {
            item {
                SwitchRow("Demo Call Audio", "Play the next voice call as media so a screen recording captures it", state.demoCallAudio) {
                    send(DevToolsFeature.Action.DemoCallAudioChanged(it))
                }
            }
        }
        item { NavigationRow("Troubleshooting", SFSymbol.WaveformPathEcg) { send(DevToolsFeature.Action.Navigate(DevToolsFeature.Route.Troubleshooting)) } }
        footer(
            if (state.developerMode) "Adds Report a Problem to the session menu and uploads voice calls (kept 30 days)."
            else "Nothing leaves this phone while Developer Mode is off.",
        )

        if (state.toolsAvailable) {
            section("Tuning")
            item {
                SwitchRow("Live Tuning", "Read springs, fades and spacing from the Tuning Panel", state.tuningEnabled) {
                    send(DevToolsFeature.Action.TuningEnabledChanged(it))
                }
            }
            item { NavigationRow("Open Tuning Panel", SFSymbol.SliderHorizontal3) { send(DevToolsFeature.Action.OpenTuningPanelTapped) } }
            if (onOpenPlaygrounds != null) {
                item { NavigationRow("Playgrounds", SFSymbol.PlayRectangleOnRectangle, onClick = onOpenPlaygrounds) }
            }
            footer("Use three-finger double tap, or hold four fingers, to tune springs and spacing live.")

            section("Motion and Rendering")
            item { SlowMotionRow(state.timeScale) { send(DevToolsFeature.Action.TimeScaleChanged(it)) } }
            item {
                SwitchRow("Performance HUD", "Frame rate, janky frames and recompositions over the app", state.perfHud) {
                    send(DevToolsFeature.Action.PerfHudChanged(it))
                }
            }
            item {
                SwitchRow("Layout Bounds", "Outline every element; touch targets under 48 dp turn red", state.layoutBounds) {
                    send(DevToolsFeature.Action.LayoutBoundsChanged(it))
                }
            }
            footer("Slow motion applies to the app’s shared springs and fades. Perfetto traces include “oc:” sections for timing marks.")

            section("Inspect")
            item { NavigationRow("App Log", SFSymbol.DocText) { send(DevToolsFeature.Action.Navigate(DevToolsFeature.Route.Logs)) } }
            item { NavigationRow("Network", SFSymbol.Network) { send(DevToolsFeature.Action.Navigate(DevToolsFeature.Route.Network)) } }
            item { NavigationRow("Session Lab", SFSymbol.Flask) { send(DevToolsFeature.Action.Navigate(DevToolsFeature.Route.Lab)) } }
            item {
                NavigationRow(if (state.isExporting) "Exporting…" else "Export Diagnostics", SFSymbol.DocZipper, chevron = false) {
                    send(DevToolsFeature.Action.ExportBundleTapped)
                }
            }
            footer("Exports the timeline, app log, device and build as a zip. No server addresses, credentials or message text.")
        }

        if (onOpenServerDetails != null) {
            item(key = "server-details-gap") { Spacer(Modifier.height(16.dp)) }
            item(key = "server-details") { NavigationRow("Server Details", SFSymbol.ServerRack, onClick = onOpenServerDetails) }
        }
    }
}

private fun LazyListScope.section(title: String) {
    item(key = "section-$title") {
        Text(
            title,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun LazyListScope.footer(text: String) {
    item(key = "footer-$text") {
        Text(
            text,
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = OpenCodeTheme.colors.secondaryText,
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    hint: String? = null,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier.fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch, onClickLabel = hint) { onChange(!checked) }
            // On/Off is spelled out so a change goes out as a state description, which TalkBack
            // speaks.
            .semantics {
                toggleableState = ToggleableState(checked)
                stateDescription = if (checked) "On" else "Off"
            }
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else OpenCodeTheme.colors.secondaryText)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = OpenCodeTheme.colors.secondaryText)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = OpenCodeSwitchDefaults.colors())
    }
}

@Composable
private fun NavigationRow(title: String, symbol: SFSymbol, chevron: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OpenCodeIcon(symbol, null, tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (chevron) OpenCodeIcon(SFSymbol.ChevronRight, null, tint = OpenCodeTheme.colors.secondaryText)
    }
}

@Composable
private fun SlowMotionRow(scale: Double, onChange: (Double) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Slow Motion", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(8.dp))
        val scales = AnimationTimeScale.supportedScales
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            scales.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = scale == option,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, scales.size),
                ) { Text(TimeScaleLabel.format(option)) }
            }
        }
    }
}

// Troubleshooting

@Composable
private fun TroubleshootingPage(state: DevToolsFeature.State) {
    if (!state.recordDiagnostics) {
        EmptyNote("Turn on Record Diagnostics to keep a timeline of timing and network metadata.")
        return
    }
    val events = state.events
    val groups = remember(events) { Troubleshooting.groupedEpochs(events) }
    val metrics = remember(events) { Troubleshooting.metricsByRequest(events) }
    LazyColumn(Modifier.fillMaxSize()) {
        section("Summary")
        item { SummaryCard(events) }
        for (group in groups) {
            section(group.title)
            items(group.events.filter { it.kind != DiagnosticsEvent.Kind.RequestStart && it.kind != DiagnosticsEvent.Kind.NetworkMetrics }, key = { it.id }) { event ->
                EventRow(event, events.firstOrNull()?.monotonicSeconds, event.requestID?.let(metrics::get))
                HorizontalDivider(Modifier.padding(start = 16.dp))
            }
        }
    }
}

@Composable
private fun SummaryCard(events: List<DiagnosticsEvent>) {
    val summary = remember(events) { Troubleshooting.summary(events) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((label, value) in summary) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                // Two columns that each wrap: a long value never squeezes its label.
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(
                    value,
                    Modifier.weight(1f).padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenCodeTheme.colors.secondaryText,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun EventRow(event: DiagnosticsEvent, origin: Double?, metrics: DiagnosticsEvent?) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = OpenCodeFonts.monospace)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) {}) {
        if (event.kind == DiagnosticsEvent.Kind.RequestEnd) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${event.method ?: "GET"} ${event.path ?: "unknown"}", Modifier.weight(1f), style = mono, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(Troubleshooting.ms(event.durationMilliseconds), style = MaterialTheme.typography.bodySmall, color = OpenCodeTheme.colors.secondaryText)
            }
            Text(Troubleshooting.transferLine(event, metrics), style = MaterialTheme.typography.labelSmall, color = OpenCodeTheme.colors.secondaryText)
            if (metrics != null) Text(Troubleshooting.phasesLine(metrics), style = MaterialTheme.typography.labelSmall, color = OpenCodeTheme.colors.secondaryText)
            event.errorCategory?.let { Text(it.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Troubleshooting.relative(event, origin), style = MaterialTheme.typography.labelSmall.copy(fontFamily = OpenCodeFonts.monospace), color = OpenCodeTheme.colors.secondaryText)
                Text(event.name.replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                event.durationMilliseconds?.let { Text(Troubleshooting.ms(it), style = MaterialTheme.typography.bodySmall, color = OpenCodeTheme.colors.secondaryText) }
            }
            event.reason?.let { Text(it, style = mono, color = OpenCodeTheme.colors.secondaryText) }
        }
    }
}

// Logs

@Composable
private fun LogsPage(state: DevToolsFeature.State, send: (DevToolsFeature.Action) -> Unit) {
    val lines = remember(state.logLines, state.logQuery, state.logCategory) { DevToolsFeature.filteredLog(state).asReversed() }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.logQuery,
            onValueChange = { send(DevToolsFeature.Action.LogQueryChanged(it)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            placeholder = { Text("Filter") },
            singleLine = true,
            leadingIcon = { OpenCodeIcon(SFSymbol.Magnifyingglass, null) },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.logCategory == null, onClick = { send(DevToolsFeature.Action.LogCategoryChanged(null)) }, label = { Text("All") })
            var more by remember { mutableStateOf(false) }
            Box {
                FilterChip(
                    selected = state.logCategory != null,
                    onClick = { more = true },
                    label = { Text(state.logCategory?.replaceFirstChar { it.uppercase(Locale.ROOT) } ?: "Category") },
                    trailingIcon = { OpenCodeIcon(SFSymbol.ChevronDown, null) },
                )
                DropdownMenu(more, onDismissRequest = { more = false }) {
                    for (category in DevToolsFeature.logCategories) {
                        DropdownMenuItem(text = { Text(if (category == "session") "Perf" else category.replaceFirstChar { it.uppercase(Locale.ROOT) }) }, onClick = {
                            more = false
                            send(DevToolsFeature.Action.LogCategoryChanged(category))
                        })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${lines.size} lines", Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.labelMedium, color = OpenCodeTheme.colors.secondaryText)
        }
        if (lines.isEmpty()) {
            EmptyNote("No log lines.")
        } else {
            val listState = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                itemsIndexed(lines, key = { index, _ -> lines.size - index }) { _, line ->
                    val isError = " error " in line
                    Text(
                        line,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = OpenCodeFonts.monospace),
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// Network

@Composable
private fun NetworkPage(state: DevToolsFeature.State, send: (DevToolsFeature.Action) -> Unit) {
    if (!state.recordDiagnostics) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Requests are recorded while Record Diagnostics is on.", style = MaterialTheme.typography.bodyMedium, color = OpenCodeTheme.colors.secondaryText)
            TextButton(onClick = { send(DevToolsFeature.Action.RecordDiagnosticsChanged(true)) }) { Text("Turn On Recording") }
        }
        return
    }
    val metrics = remember(state.events) { Troubleshooting.metricsByRequest(state.events) }
    val rows = remember(state.events, state.networkFailuresOnly) {
        state.events.filter { event ->
            val isRequest = event.kind == DiagnosticsEvent.Kind.RequestEnd || event.kind == DiagnosticsEvent.Kind.Stream && event.path == "/api/event"
            isRequest && (!state.networkFailuresOnly || event.errorCategory != null || (event.statusCode ?: 200) >= 400)
        }.asReversed()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.networkFailuresOnly, onClick = { send(DevToolsFeature.Action.NetworkFailuresOnlyToggled) }, label = { Text("Failures") })
            Spacer(Modifier.weight(1f))
            Text(Troubleshooting.lastMinute(state.events), style = MaterialTheme.typography.labelMedium, color = OpenCodeTheme.colors.secondaryText)
        }
        if (rows.isEmpty()) {
            EmptyNote("No requests recorded yet.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.id }) { event ->
                    NetworkRow(event, event.requestID?.let(metrics::get))
                    HorizontalDivider(Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(event: DiagnosticsEvent, metrics: DiagnosticsEvent?) {
    var expanded by remember { mutableStateOf(false) }
    val failed = event.errorCategory != null || (event.statusCode ?: 200) >= 400
    Column(
        Modifier.fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide Details" else "Show Details") { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (failed) "Failed" else "OK" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                event.statusCode?.toString() ?: if (event.kind == DiagnosticsEvent.Kind.Stream) "SSE" else "—",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = OpenCodeFonts.monospace, fontWeight = FontWeight.SemiBold),
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                if (event.kind == DiagnosticsEvent.Kind.Stream) event.name.replace('_', ' ') else "${event.method ?: "GET"} ${event.path ?: "unknown"}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = OpenCodeFonts.monospace),
                maxLines = if (expanded) 4 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(Troubleshooting.ms(event.durationMilliseconds), style = MaterialTheme.typography.labelSmall, color = OpenCodeTheme.colors.secondaryText)
        }
        if (expanded) {
            val small = MaterialTheme.typography.labelSmall
            val color = OpenCodeTheme.colors.secondaryText
            if (event.kind == DiagnosticsEvent.Kind.RequestEnd) {
                Text(Troubleshooting.transferLine(event, metrics), style = small, color = color)
                if (metrics != null) {
                    Text(Troubleshooting.phasesLine(metrics), style = small, color = color)
                    Text(Troubleshooting.addressLine(metrics), style = small, color = color)
                }
            } else {
                Text("${event.streamLines ?: 0} lines · ${Troubleshooting.bytes(event.streamLineBytes)}", style = small, color = color)
            }
            Text(listOfNotNull(event.operationID?.let { "op ${it.take(8)}" }, event.attempt?.let { "attempt $it" }, event.sessionID).joinToString(" · "), style = small, color = color)
            event.errorCategory?.let { Text("${it.description}${event.failurePhase?.let { phase -> " · ${phase.name}" }.orEmpty()}", style = small, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// Session Lab

@Composable
private fun LabPage(state: DevToolsFeature.State, send: (DevToolsFeature.Action) -> Unit) {
    val lab = state.lab
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = lab.address,
                    onValueChange = { send(DevToolsFeature.Action.LabAddressChanged(it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Fixture Address") },
                    singleLine = true,
                    supportingText = { Text("The emulator reaches this Mac at 10.0.2.2. Start a lab with session-lab.py serve.") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { send(DevToolsFeature.Action.LabConnectTapped) }) { Text(if (lab.state == null) "Connect" else "Refresh") }
                    TextButton(onClick = { send(DevToolsFeature.Action.PointAppAtLabTapped) }) { Text("Use as Server") }
                }
                val status = when (val s = lab.status) {
                    DevToolsFeature.Lab.Status.Idle -> "Not connected"
                    DevToolsFeature.Lab.Status.Loading -> "Connecting…"
                    DevToolsFeature.Lab.Status.Connected -> "session/${lab.state?.checkpoint} · revision ${lab.state?.generation ?: 0}"
                    is DevToolsFeature.Lab.Status.Failed -> "Couldn’t reach the lab: ${s.message}"
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (lab.status is DevToolsFeature.Lab.Status.Failed) MaterialTheme.colorScheme.error else OpenCodeTheme.colors.secondaryText)
                Text("Use as Server saves this lab as the current server (empty password) and relaunches the app.", style = MaterialTheme.typography.bodySmall, color = OpenCodeTheme.colors.secondaryText)
            }
        }
        val checkpoints = lab.state?.checkpoints.orEmpty()
        if (checkpoints.isNotEmpty()) section("Checkpoints")
        items(checkpoints, key = { it.id }) { checkpoint ->
            val selected = checkpoint.id == lab.state?.checkpoint
            Row(
                Modifier.fillMaxWidth()
                    .clickable(role = Role.RadioButton) { send(DevToolsFeature.Action.LabCheckpointSelected(checkpoint.id)) }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics(mergeDescendants = true) { contentDescription = checkpoint.title; stateDescription = if (selected) "Selected" else "" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OpenCodeIcon(if (selected) SFSymbol.CheckmarkCircleFill else SFSymbol.Circle, null, tint = if (selected) MaterialTheme.colorScheme.primary else OpenCodeTheme.colors.secondaryText)
                Column(Modifier.weight(1f)) {
                    Text(checkpoint.title, style = MaterialTheme.typography.bodyLarge)
                    if (checkpoint.description.isNotEmpty()) Text(checkpoint.description, style = MaterialTheme.typography.bodySmall, color = OpenCodeTheme.colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = OpenCodeTheme.colors.secondaryText)
    }
}
