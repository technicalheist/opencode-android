package ai.opencode.feature.voice

import ai.opencode.core.R as CoreR
import ai.opencode.core.ui.OpenCodeContentUnavailable
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.rememberOverscrollIfContentScrolls
import ai.opencode.core.ui.appFormattingLocale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.AmbientRate
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.opencode.core.ui.OpenCodeAlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.drawText
import ai.opencode.core.ui.OpenCodeFonts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Settings → Voice → Call History: saved calls and uploaded diagnostics. Settings hosts it as a
 * page; navigation and the Developer Mode links are the host's.
 *
 * Reads and deletes the logs through [VoiceCallLogClient] off the main thread.
 *
 * @param onBack the top bar's Back.
 * @param onOpenCall pushes [VoiceCallReplayView] for a saved call.
 * @param onOpenWeb Developer Mode's Open on the Web; Diagnostics sit above Voice, so the host opens
 *     the page. `null` hides it.
 * @param onShareLink Developer Mode's Share Link; the host shares it. `null` hides it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallLogList(
    onBack: () -> Unit,
    onOpenCall: (VoiceCallLog) -> Unit,
    modifier: Modifier = Modifier,
    onOpenWeb: (() -> Unit)? = null,
    onShareLink: (() -> Unit)? = null,
) {
    val voiceCallLog = remember { dependency(VoiceCallLogClient) }
    val developerMode by remember { shared(SharedKeys.developerMode).flow }.collectAsState()
    var logs by remember { mutableStateOf<List<VoiceCallLog>>(emptyList()) }
    var confirmingDeleteAll by remember { mutableStateOf(false) }
    var showsOptions by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { logs = withContext(Dispatchers.IO) { voiceCallLog.list() } }

    Scaffold(
        modifier = modifier,
        topBar = {
            OpenCodeTopBar(
                title = { Text(stringResource(R.string.voice_call_history_title), modifier = Modifier.semantics { heading() }) },
                navigation = { OpenCodeTopBarBackButton(onClick = onBack) },
                centerTitle = true,
                actions = {
                    if (developerMode || logs.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showsOptions = true }, modifier = Modifier.testTag("diagnostics-history-options")) {
                                OpenCodeIcon(SFSymbol.Ellipsis, contentDescription = stringResource(R.string.voice_call_history_options_description))
                            }
                            DropdownMenu(expanded = showsOptions, onDismissRequest = { showsOptions = false }) {
                                if (developerMode) {
                                    onOpenWeb?.let { open ->
                                        DropdownMenuItem(
                                            text = { Text("Open on the Web") },
                                            leadingIcon = { OpenCodeIcon(SFSymbol.Safari, null) },
                                            onClick = { showsOptions = false; open() },
                                        )
                                    }
                                    onShareLink?.let { share ->
                                        DropdownMenuItem(
                                            text = { Text("Share Link") },
                                            leadingIcon = { OpenCodeIcon(SFSymbol.SquareAndArrowUp, null) },
                                            onClick = { showsOptions = false; share() },
                                        )
                                    }
                                }
                                if (logs.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.voice_call_history_delete_all), color = OpenCodeTheme.colors.destructive) },
                                        leadingIcon = { OpenCodeIcon(SFSymbol.Trash, null, tint = OpenCodeTheme.colors.destructive) },
                                        onClick = { showsOptions = false; confirmingDeleteAll = true },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (logs.isEmpty()) {
            OpenCodeContentUnavailable(
                symbol = SFSymbol.Waveform,
                title = stringResource(R.string.voice_call_history_empty_title),
                modifier = Modifier.fillMaxSize().padding(padding),
                description = stringResource(R.string.voice_call_history_empty_message),
            )
        } else {
            val historyList = rememberLazyListState()
            LazyColumn(
                Modifier.fillMaxSize(),
                state = historyList,
                contentPadding = padding,
                overscrollEffect = rememberOverscrollIfContentScrolls(historyList),
            ) {
                items(logs, key = { it.id.toString() }) { log ->
                    VoiceCallLogRow(
                        log = log,
                        onOpen = { onOpenCall(log) },
                        onDelete = {
                            logs = logs.filterNot { it.id == log.id }
                            scope.launch { withContext(Dispatchers.IO) { voiceCallLog.delete(log.id) } }
                        },
                        modifier = Modifier.animateItem(),
                    )
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = OpenCodeTheme.colors.border)
                }
            }
        }
    }

    if (confirmingDeleteAll) {
        OpenCodeAlertDialog(
            onDismissRequest = { confirmingDeleteAll = false },
            title = { Text(stringResource(R.string.voice_call_history_delete_all_confirm_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDeleteAll = false
                    logs = emptyList()
                    scope.launch { withContext(Dispatchers.IO) { voiceCallLog.deleteAll() } }
                }) { Text(stringResource(R.string.voice_call_history_delete_all), color = OpenCodeTheme.colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmingDeleteAll = false }) { Text(stringResource(CoreR.string.common_cancel)) } },
        )
    }
}

/** One saved call: its title and when it was, swiped away to delete. */
@Composable
private fun VoiceCallLogRow(log: VoiceCallLog, onOpen: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().background(OpenCodeTheme.colors.destructive).padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(CoreR.string.common_delete), color = Color.White, style = OpenCodeTextStyles.body)
            }
        },
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onOpen)
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("voice-call-log-${log.id.toString().uppercase()}"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(if (log.title == VoiceFeature.State.newSessionTitle) stringResource(R.string.voice_session_new_title) else log.title, style = OpenCodeTextStyles.body, color = OpenCodeTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${VoiceCallLogFormat.startedAt(log)} · ${VoiceCallLogFormat.duration(log.duration)}",
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
            )
        }
    }
}

/** The list's and replay's text formats. */
object VoiceCallLogFormat {
    private val started = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

    fun startedAt(log: VoiceCallLog, locale: Locale = appFormattingLocale()): String =
        started.withLocale(locale).format(log.startedAt.atZone(ZoneId.systemDefault()))

    /** “42s”, or “3m 5s” from a minute. */
    fun duration(seconds: Double): String {
        val total = seconds.roundToInt()
        return if (total < 60) localized(R.string.voice_call_log_duration_seconds, total) else localized(R.string.voice_call_log_duration_minutes, total / 60, total % 60)
    }

    fun speed(value: Double, locale: Locale = appFormattingLocale()): String = NumberFormat.getInstance(locale).format(value)
}

/**
 * Replays one call: the four tracks fill in as the playhead passes, with what happened so far
 * listed beneath. Settings hosts it as a page.
 *
 * @param onBack the top bar's Back; playback stops when the view leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceCallReplayView(log: VoiceCallLog, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val playback = remember(log) { VoiceCallReplayPlayback(log.duration) }

    // The playhead runs at 60 Hz while playing.
    LaunchedEffect(playback, playback.playing) {
        if (!playback.playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (last == 0L || (nanos - last) / 1e9 >= AmbientRate.Foreground.minimumInterval - 0.001) {
                    last = nanos
                    playback.tick(nanos)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            OpenCodeTopBar(
                title = { Text(if (log.title == VoiceFeature.State.newSessionTitle) stringResource(R.string.voice_session_new_title) else log.title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
                navigation = { OpenCodeTopBarBackButton(onClick = onBack) },
                centerTitle = true,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ReplayTimeline(log, playback)
            ReplayControls(log, playback)
            HorizontalDivider(color = OpenCodeTheme.colors.border)
            ReplayEvents(log, playback, reduceMotion, Modifier.weight(1f))
        }
    }
}

/**
 * The replay's clock: where playback is, whether it plays, and how fast. A plain object with
 * snapshot state; main thread only.
 */
private class VoiceCallReplayPlayback(val duration: Double) {
    var time by mutableDoubleStateOf(0.0)
        private set
    var playing by mutableStateOf(false)
        private set
    var speed by mutableDoubleStateOf(1.0)
        private set

    /** Where playback last (re)started: the frame clock and call time. */
    private var anchor: Pair<Long, Double>? = null
    private var lastNanos: Long = 0

    /** The current call time; frame-accurate while playing. */
    var now by mutableDoubleStateOf(0.0)
        private set

    fun tick(nanos: Long) {
        lastNanos = nanos
        val start = anchor ?: (nanos to time).also { anchor = it }
        now = min(duration, start.second + (nanos - start.first) / 1e9 * speed)
        if (now >= duration && playing) pause(duration)
    }

    fun toggle() {
        if (playing) pause(now) else play(if (now >= duration) 0.0 else now)
    }

    fun play(start: Double) {
        time = start
        now = start
        anchor = null
        playing = true
    }

    fun pause(at: Double) {
        time = at
        now = at
        playing = false
        anchor = null
    }

    fun seek(value: Double) {
        val clamped = value.coerceIn(0.0, duration)
        time = clamped
        now = clamped
        if (playing) anchor = null
    }

    fun changeSpeed(value: Double) {
        speed = value
        time = now
        if (playing) anchor = null
    }
}

private const val pointsPerSecond = 40f
private const val rowHeight = 34f
private const val labelWidth = 84f

// Compose can't measure a node wider than 262,142 px.
private const val maxTimelinePixels = 262_000f

@Composable
private fun ReplayTimeline(log: VoiceCallLog, playback: VoiceCallReplayPlayback) {
    val scroll = rememberScrollState()
    val pixelsPerDp = LocalDensity.current.density
    val perSecond = min(pointsPerSecond, (maxTimelinePixels / pixelsPerDp - 40) / max(log.duration, 1.0).toFloat())
    fun x(seconds: Double): Float = seconds.toFloat() * perSecond
    val dark = OpenCodeTheme.colors.isDark
    val secondary = OpenCodeTheme.colors.secondaryText
    val red = VoiceCallLogColors.red(dark)
    val measurer = rememberTextMeasurer()
    val labelStyle = OpenCodeTextStyles.caption2.copy(fontWeight = FontWeight.Medium, color = Color.White)
    val labels = remember(log, labelStyle) { log.spans.associate { it.id to measurer.measure(it.label, labelStyle, maxLines = 1, softWrap = false) } }
    val now = playback.now
    val quarter = (now * 4).toInt()
    val timelineDescription = stringResource(R.string.voice_replay_timeline_description)
    val timelineState = stringResource(R.string.voice_replay_timeline_state, VoiceCallLogFormat.duration(now), VoiceCallLogFormat.duration(log.duration))
    // Keep the playhead in view while playing.
    LaunchedEffect(quarter) {
        if (!playback.playing) return@LaunchedEffect
        scroll.scrollTo((max(0f, x(playback.now) - 120) * pixelsPerDp).roundToInt())
    }
    Row(
        Modifier
            .padding(vertical = 8.dp)
            .clearAndSetSemantics {
                contentDescription = timelineDescription
                stateDescription = timelineState
            },
    ) {
        Column(Modifier.padding(start = 12.dp).width(labelWidth.dp)) {
            for (track in VoiceCallLog.Track.entries) {
                Row(Modifier.height(rowHeight.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(VoiceCallLogColors.color(track, dark), CircleShape))
                    Text(VoiceCallLogColors.name(track), style = OpenCodeTextStyles.caption1, color = secondary, maxLines = 1)
                }
            }
        }
        Box(Modifier.weight(1f).horizontalScroll(scroll)) {
            Canvas(
                Modifier
                    .size(max(x(log.duration) + 40, 1f).dp, (rowHeight * 4).dp)
                    .pointerInput(log, perSecond) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            playback.seek((down.position.x / density / perSecond).toDouble())
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                playback.seek((change.position.x / density / perSecond).toDouble())
                                change.consume()
                            }
                        }
                    },
            ) {
                val d = density
                for ((row, track) in VoiceCallLog.Track.entries.withIndex()) {
                    val y = row * rowHeight
                    drawRect(secondary.copy(alpha = 0.2f), Offset(0f, (y + rowHeight - 0.5f) * d), Size(size.width, 0.5f * d))
                    val color = VoiceCallLogColors.color(track, dark)
                    for (span in log.spans) {
                        if (span.track != track) continue
                        val end = span.end ?: span.start
                        val full = Rect(x(span.start), y + 6, x(span.start) + max(4f, x(end - span.start)), y + rowHeight - 6)
                        // The whole span faintly, and the played part solid: it fills in.
                        drawRoundRect(color.copy(alpha = 0.18f), Offset(full.left * d, full.top * d), Size(full.width * d, full.height * d), CornerRadius(5 * d))
                        if (now <= span.start) continue
                        val playedWidth = max(4f, min(full.width, x(now - span.start)))
                        drawRoundRect(if (span.failed) red else color, Offset(full.left * d, full.top * d), Size(playedWidth * d, full.height * d), CornerRadius(5 * d))
                        val label = labels[span.id] ?: continue
                        clipRect((full.left + 5) * d, full.top * d, (full.left + playedWidth - 5) * d, full.bottom * d) {
                            drawText(label, topLeft = Offset((full.left + 5) * d, full.center.y * d - label.size.height / 2f))
                        }
                    }
                }
                for (note in log.notes) {
                    if (note.kind != VoiceCallLog.Note.Kind.marker) continue
                    drawRect(secondary.copy(alpha = if (now >= note.t) 0.5f else 0.15f), Offset(x(note.t) * d, 0f), Size(1f * d, size.height))
                }
                drawRect(red, Offset((x(now) - 1) * d, 0f), Size(2f * d, size.height))
            }
        }
    }
}

@Composable
private fun ReplayControls(log: VoiceCallLog, playback: VoiceCallReplayPlayback) {
    var showsSpeeds by remember { mutableStateOf(false) }
    val now = playback.now
    val positionDescription = stringResource(R.string.voice_replay_position_description)
    val speedDescription = stringResource(R.string.voice_replay_speed_description)
    Row(
        Modifier.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconButton(onClick = { playback.toggle() }, modifier = Modifier.size(48.dp)) {
            Crossfade(playback.playing, animationSpec = Motion.easeOut(0.18), label = "replayPlayGlyph") { playing ->
                OpenCodeIcon(if (playing) SFSymbol.PauseFill else SFSymbol.PlayFill, contentDescription = if (playing) stringResource(R.string.voice_replay_pause) else stringResource(R.string.voice_replay_play), size = OpenCodeIconSize.large)
            }
        }
        Slider(
            value = now.toFloat(),
            onValueChange = { playback.seek(it.toDouble()) },
            valueRange = 0f..max(log.duration, 0.1).toFloat(),
            modifier = Modifier.weight(1f).semantics { contentDescription = positionDescription },
        )
        Text(
            VoiceCallLogFormat.duration(now),
            style = OpenCodeTextStyles.footnote.copy(fontFeatureSettings = "tnum"),
            color = OpenCodeTheme.colors.secondaryText,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp),
        )
        Box {
            TextButton(
                onClick = { showsSpeeds = true },
                modifier = Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).semantics { contentDescription = speedDescription },
            ) {
                Text("${VoiceCallLogFormat.speed(playback.speed)}×", style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.SemiBold))
            }
            DropdownMenu(expanded = showsSpeeds, onDismissRequest = { showsSpeeds = false }) {
                for (speed in listOf(0.5, 1.0, 2.0, 4.0)) {
                    DropdownMenuItem(
                        text = { Text("${VoiceCallLogFormat.speed(speed)}×") },
                        trailingIcon = if (speed == playback.speed) ({ OpenCodeIcon(SFSymbol.Checkmark, null) }) else null,
                        onClick = {
                            showsSpeeds = false
                            playback.changeSpeed(speed)
                        },
                    )
                }
            }
        }
    }
}

/** One row of the replay's list: a span starting or a note. */
private data class ReplayEvent(val id: String, val t: Double, val who: String, val color: Color, val text: String, val block: Boolean)

private fun replayEvents(log: VoiceCallLog, dark: Boolean, secondary: Color): List<ReplayEvent> {
    val spans = log.spans.map { span ->
        ReplayEvent(
            id = "s-${span.id}",
            t = span.start,
            who = VoiceCallLogColors.name(span.track),
            color = VoiceCallLogColors.color(span.track, dark),
            text = listOfNotNull(span.label, span.detail).filter { it.isNotEmpty() }.joinToString(" — "),
            block = false,
        )
    }
    val notes = log.notes.map { note ->
        val (who, color) = when (note.kind) {
            VoiceCallLog.Note.Kind.received -> localized(R.string.voice_replay_to_delegator) to VoiceCallLogColors.color(VoiceCallLog.Track.delegator, dark)
            VoiceCallLog.Note.Kind.injected -> localized(R.string.voice_replay_to_voice) to VoiceCallLogColors.color(VoiceCallLog.Track.voice, dark)
            VoiceCallLog.Note.Kind.audio -> localized(R.string.voice_replay_audio) to if (note.text.startsWith("⚠︎")) VoiceCallLogColors.red(dark) else VoiceCallLogColors.teal(dark)
            VoiceCallLog.Note.Kind.marker -> "•" to secondary
        }
        ReplayEvent(
            id = "n-${note.id}",
            t = note.t,
            who = who,
            color = color,
            text = note.text,
            block = note.kind == VoiceCallLog.Note.Kind.received || note.kind == VoiceCallLog.Note.Kind.injected,
        )
    }
    return (spans + notes).sortedBy { it.t }
}

@Composable
private fun ReplayEvents(log: VoiceCallLog, playback: VoiceCallReplayPlayback, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val dark = OpenCodeTheme.colors.isDark
    val secondary = OpenCodeTheme.colors.secondaryText
    val all = remember(log, dark, secondary) { replayEvents(log, dark, secondary) }
    // Updated at most every quarter second.
    val shown by remember(all) {
        derivedStateOf {
            val now = if (playback.playing) floor(playback.now * 4) / 4 else playback.now
            all.filter { it.t <= now }
        }
    }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size) {
        if (!playback.playing || shown.isEmpty()) return@LaunchedEffect
        if (reduceMotion) list.scrollToItem(shown.lastIndex) else list.animateScrollToItem(shown.lastIndex)
    }
    Box(modifier.fillMaxWidth()) {
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(vertical = 4.dp)) {
            items(shown, key = { it.id }) { event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem(
                            fadeInSpec = Motion.layout.animation(reduceMotion = reduceMotion),
                            placementSpec = Motion.layout.animation(reduceMotion = reduceMotion),
                            fadeOutSpec = Motion.layout.animation(reduceMotion = reduceMotion),
                        )
                        .clickable { playback.seek(event.t) }
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        "%.1fs".format(Locale.ROOT, event.t),
                        style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                        color = OpenCodeTheme.colors.secondaryText,
                        modifier = Modifier.width(44.dp),
                    )
                    Text(event.who, style = OpenCodeTextStyles.caption1.copy(fontWeight = FontWeight.SemiBold), color = event.color, modifier = Modifier.width(78.dp))
                    if (event.block) {
                        Row(
                            Modifier
                                .weight(1f)
                                .height(IntrinsicSize.Min)
                                .background(event.color.copy(alpha = 0.08f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)),
                        ) {
                            Box(Modifier.width(3.dp).fillMaxHeight().background(event.color))
                            Text(
                                event.text,
                                style = OpenCodeTextStyles.caption1.copy(fontFamily = OpenCodeFonts.monospace),
                                color = OpenCodeTheme.colors.text,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        Text(event.text, style = OpenCodeTextStyles.callout, color = OpenCodeTheme.colors.text, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.voice_replay_empty),
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/** The replay's track colours and names. */
object VoiceCallLogColors {
    fun color(track: VoiceCallLog.Track, dark: Boolean): Color = when (track) {
        VoiceCallLog.Track.user -> if (dark) Color(1f, 0.624f, 0.039f) else Color(1f, 0.584f, 0f)
        VoiceCallLog.Track.voice -> if (dark) Color(0.039f, 0.518f, 1f) else Color(0f, 0.478f, 1f)
        VoiceCallLog.Track.delegator -> if (dark) Color(0.749f, 0.353f, 0.949f) else Color(0.686f, 0.322f, 0.871f)
        VoiceCallLog.Track.tool -> if (dark) Color(0.188f, 0.82f, 0.345f) else Color(0.204f, 0.78f, 0.349f)
    }

    fun name(track: VoiceCallLog.Track): String = when (track) {
        VoiceCallLog.Track.user -> localized(R.string.voice_replay_track_user)
        VoiceCallLog.Track.voice -> localized(R.string.voice_replay_track_voice)
        VoiceCallLog.Track.delegator -> localized(R.string.voice_replay_track_delegator)
        VoiceCallLog.Track.tool -> localized(R.string.voice_replay_track_tools)
    }

    /** `UIColor.systemRed`. */
    fun red(dark: Boolean): Color = if (dark) Color(1f, 0.271f, 0.227f) else Color(1f, 0.231f, 0.188f)

    /** `UIColor.systemTeal`. */
    fun teal(dark: Boolean): Color = if (dark) Color(0.251f, 0.784f, 0.878f) else Color(0.188f, 0.69f, 0.78f)
}
