package ai.opencode.feature.settings.whatsnew

import ai.opencode.core.ui.OpenCodeSwitch
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.AmbientClock
import ai.opencode.core.motion.AmbientRate
import ai.opencode.core.motion.AmbientTimeline
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodePrimaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.sfSymbol
import ai.opencode.feature.settings.R
import ai.opencode.feature.settings.SettingsDivider
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPage
import ai.opencode.feature.settings.SettingsPageScaffold
import ai.opencode.feature.settings.SettingsRow
import ai.opencode.feature.settings.SettingsRowFrame
import ai.opencode.feature.settings.SettingsStore
import ai.opencode.feature.settings.SettingsText
import ai.opencode.feature.settings.SettingsTint
import ai.opencode.feature.settings.isAccessibilitySize
import ai.opencode.feature.settings.settingsSection
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [WhatsNewListPage] is then the root page and Back closes it. `WhatsNewServerPage` is Settings'
 * own server page ([SettingsPage.Server]), which Settings' stack pushes for `open(.servers)`.
 * `WhatsNewButton` (the drawer's row) belongs to the drawer: it shows while
 * [WhatsNewFeature.State.showsButton], sends [WhatsNewFeature.Action.ButtonTapped] (then opens
 * `SettingsFeature.State.whatsNew(server)`), and its Hide sends
 * [WhatsNewFeature.Action.HideTapped]; [WhatsNewFeature.State.unseenFeatures] is what it counts.
 * `.screenName` (Scrawl diagnostics tags) has no Android counterpart.
 */

/**
 * The features, newest first, grouped by the day they shipped, each opening its own page; with
 * Developer Mode on, Developer Updates follow; Smaller Changes is the last row.
 */
@Composable
fun WhatsNewListPage(store: SettingsStore) {
    val root by store.observe { it.root }
    val whatsNew by store.observe { it.whatsNew }
    val seenIDs by remember { shared(WhatsNewFeature.seenIDsKey).flow }.collectAsState()
    val isButtonEnabled by remember { shared(WhatsNewFeature.buttonEnabledKey).flow }.collectAsState()
    val developerMode by remember { shared(SharedKeys.developerMode).flow }.collectAsState()
    val days = remember(whatsNew.entries) { WhatsNewDay.group(whatsNew.features) }
    val push = { page: SettingsPage -> store.send(SettingsFeature.Action.PagePushed(page)) }
    val developerUpdatesHeader = stringResource(R.string.settings_whatsnew_developer_updates)
    val smallerChangesTitle = stringResource(R.string.settings_whatsnew_smaller_changes)

    LaunchedEffect(Unit) { store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.Viewed)) }

    SettingsPageScaffold(
        title = stringResource(R.string.settings_whatsnew_title),
        store = store,
        actions = {
            // In Settings only; from the drawer's row the page is the root and has no menu.
            if (root == SettingsFeature.State.Root.settings) {
                WhatsNewOptionsMenu(isButtonEnabled) { enabled ->
                    store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.ButtonEnabledChanged(enabled)))
                }
            }
        },
    ) {
        for (day in days) {
            settingsSection(key = "day-${day.id}", header = whatsNewDayTitle(day.day)) {
                day.entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider(inset = true)
                    WhatsNewFeatureRow(entry, isNew = whatsNew.isNew(entry, seenIDs)) {
                        push(SettingsPage.WhatsNewDetail(entry.id))
                    }
                }
            }
        }
        if (developerMode && whatsNew.developerFeatures.isNotEmpty()) {
            settingsSection(key = "developer", header = developerUpdatesHeader) {
                whatsNew.developerFeatures.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider(inset = true)
                    WhatsNewFeatureRow(entry, isNew = whatsNew.isNew(entry, seenIDs)) {
                        push(SettingsPage.WhatsNewDetail(entry.id))
                    }
                }
            }
        }
        if (whatsNew.smallerChanges.isNotEmpty()) {
            settingsSection(key = "smaller") {
                SettingsRow(
                    smallerChangesTitle,
                    symbol = null,
                    value = NumberFormat.getIntegerInstance(appFormattingLocale()).format(whatsNew.smallerChanges.size),
                ) { push(SettingsPage.WhatsNewSmallerChanges) }
            }
        }
    }
}

/** The top bar's menu in Settings: Show in Drawer. */
@Composable
private fun WhatsNewOptionsMenu(isButtonEnabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val optionsDescription = stringResource(R.string.settings_whatsnew_options_description)
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = optionsDescription },
        ) {
            OpenCodeIcon(SFSymbol.Ellipsis, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_whatsnew_show_in_drawer)) },
                trailingIcon = { OpenCodeSwitch(checked = isButtonEnabled, onCheckedChange = null) },
                onClick = { onEnabledChange(!isButtonEnabled) },
                modifier = Modifier.semantics { toggleableState = ToggleableState(isButtonEnabled) },
            )
        }
    }
}

/** A section's day header. */
private fun whatsNewDayTitle(day: LocalDate): String {
    val locale = appFormattingLocale()
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMMd")
    return day.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** The entry's tint as a Settings tile colour. */
private val WhatsNewEntry.Tint.settingsTint: SettingsTint
    get() = when (this) {
        WhatsNewEntry.Tint.blue -> SettingsTint.Blue
        WhatsNewEntry.Tint.brown -> SettingsTint.Brown
        WhatsNewEntry.Tint.cyan -> SettingsTint.Cyan
        WhatsNewEntry.Tint.gray -> SettingsTint.Gray
        WhatsNewEntry.Tint.green -> SettingsTint.Green
        WhatsNewEntry.Tint.indigo -> SettingsTint.Indigo
        WhatsNewEntry.Tint.mint -> SettingsTint.Mint
        WhatsNewEntry.Tint.orange -> SettingsTint.Orange
        WhatsNewEntry.Tint.pink -> SettingsTint.Pink
        WhatsNewEntry.Tint.purple -> SettingsTint.Purple
        WhatsNewEntry.Tint.red -> SettingsTint.Red
        WhatsNewEntry.Tint.teal -> SettingsTint.Teal
    }

/**
 * A feature's row: its tile, its title, and a short summary, with a dot while it's new. Its
 * action is on its page.
 */
@Composable
private fun WhatsNewFeatureRow(entry: WhatsNewEntry, isNew: Boolean, onClick: () -> Unit) {
    val accessibilitySize = isAccessibilitySize()
    val newState = stringResource(R.string.settings_whatsnew_new_state)
    SettingsRowFrame(
        Modifier
            .semantics { if (isNew) stateDescription = newState }
            .padding(vertical = 4.dp),
        onClick = onClick,
    ) {
        WhatsNewTile(entry)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = SettingsText.title, color = OpenCodeTheme.colors.text)
            Text(
                entry.summary,
                style = OpenCodeTextStyles.subheadline,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = if (accessibilitySize) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isNew) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

/** A Settings row's tile, a size up for two lines. */
@Composable
private fun WhatsNewTile(entry: WhatsNewEntry) {
    val color = entry.tint.settingsTint.color()
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Brush.verticalGradient(listOf(lerpWhite(color, 0.15f), color))),
        contentAlignment = Alignment.Center,
    ) {
        OpenCodeIcon(entry.symbol, contentDescription = null, size = 21.dp, tint = Color.White)
    }
}

private fun lerpWhite(color: Color, amount: Float) = Color(
    red = color.red + (1 - color.red) * amount,
    green = color.green + (1 - color.green) * amount,
    blue = color.blue + (1 - color.blue) * amount,
    alpha = color.alpha,
)

/**
 * An entry's page: its symbol lit on the dot grid, its details, what to try, and its action in
 * the foot where the app can take you there.
 */
@Composable
fun WhatsNewDetailPage(store: SettingsStore, entryID: String) {
    val entry by store.observe { state -> state.whatsNew.entries.firstOrNull { it.id == entryID } }
    val listState = rememberLazyListState()
    // The bar names the feature once its own title has scrolled under it.
    val showsTitle by remember {
        derivedStateOf {
            val first = listState.firstVisibleItemIndex
            val title = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "title" }
            when {
                title != null -> title.offset + title.size <= 0
                else -> first > 1
            }
        }
    }
    val current = entry
    SettingsPageScaffold(
        title = current?.title.orEmpty(),
        titleVisible = showsTitle,
        drawsOwnTitle = true,
        store = store,
        listState = listState,
        bottomBar = { if (current != null) WhatsNewTryBar(store, current) },
    ) {
        if (current == null) return@SettingsPageScaffold
        val tint = current.tint.settingsTint
        item(key = "symbol") {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(OpenCodeRadius.panel))
                    .background(OpenCodeTheme.colors.groupedCard)
                    .padding(6.dp),
            ) {
                WhatsNewDotSymbol(current.symbol, tint.color(), Modifier.fillMaxWidth().height(220.dp))
            }
        }
        item(key = "title") {
            Column(
                Modifier.padding(horizontal = 26.dp).padding(top = 22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    current.title,
                    style = OpenCodeTextStyles.title1.copy(fontWeight = FontWeight.Bold),
                    color = OpenCodeTheme.colors.text,
                    modifier = Modifier.semantics { heading() },
                )
                Text(current.summary, style = SettingsText.title, color = OpenCodeTheme.colors.text)
            }
        }
        if (current.details.isNotEmpty()) {
            item(key = "details") {
                Column(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .padding(top = 22.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(OpenCodeRadius.panel))
                        .background(OpenCodeTheme.colors.groupedCard),
                ) {
                    current.details.forEachIndexed { index, detail ->
                        if (index > 0) {
                            androidx.compose.material3.HorizontalDivider(
                                Modifier.padding(start = 47.dp),
                                color = OpenCodeTheme.colors.border,
                                thickness = with(LocalDensity.current) { 1f.toDp() },
                            )
                        }
                        Row(
                            Modifier.padding(horizontal = 18.dp, vertical = 13.dp).semantics(mergeDescendants = true) {},
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OpenCodeIcon(SFSymbol.Checkmark, contentDescription = null, size = 17.dp, tint = tint.color())
                            Text(
                                detail,
                                style = OpenCodeTextStyles.subheadline,
                                color = OpenCodeTheme.colors.text,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        current.example?.let { example ->
            item(key = "example") {
                Row(
                    Modifier.padding(horizontal = 26.dp).padding(top = 22.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OpenCodeIcon(
                        SFSymbol.ArrowTurnDownRight,
                        contentDescription = null,
                        size = 15.dp,
                        tint = OpenCodeTheme.colors.tertiaryText,
                    )
                    Text(example, style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText)
                }
            }
        }
        // The list's own 24 plus this.
        item(key = "foot") { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * The page's action, full width in its foot: a session destination leaves What's New for the
 * session; a Settings page pushes on this stack. Nothing is ever sent.
 */
@Composable
private fun WhatsNewTryBar(store: SettingsStore, entry: WhatsNewEntry) {
    val destination = entry.destination ?: return
    val offers by store.observe { it.offersWhatsNew(destination) }
    if (!offers) return
    val canTry by store.observe { it.canTryWhatsNew(destination) }
    val server by store.observe { it.server }
    val voiceAvailable = rememberWhatsNewVoiceAvailable(server)
    val hint = destination.hint
    Surface(color = OpenCodeTheme.colors.groupedCanvas) {
        Box(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)) {
            val tryIt = {
                store.send(SettingsFeature.Action.WhatsNew(WhatsNewFeature.Action.TryTapped(destination, entry.id)))
            }
            OpenCodePrimaryButton(
                text = destination.title(voiceAvailable),
                onClick = tryIt,
                enabled = canTry,
                // TalkBack's click label (“Double tap to …”).
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        if (hint != null && canTry) {
                            onClick(label = hint) {
                                tryIt()
                                true
                            }
                        }
                    },
            )
        }
    }
}

/** [whatsNewVoiceAvailable], recomputed when any key it reads changes. */
@Composable
private fun rememberWhatsNewVoiceAvailable(server: OpenCodeServer?): Boolean {
    val enabled by remember { shared(SharedKeys.voiceModeEnabled).flow }.collectAsState()
    val openAIKey by remember { shared(SharedKeys.openAIAPIKey).flow }.collectAsState()
    val xaiKey by remember { shared(SharedKeys.xaiAPIKey).flow }.collectAsState()
    val engine by remember { shared(VoiceSharedKeys.voiceEngine).flow }.collectAsState()
    val provider by remember { shared(VoiceSharedKeys.serverVoiceProvider).flow }.collectAsState()
    val support by remember { shared(VoiceSharedKeys.serverVoiceSupportByServer).flow }.collectAsState()
    return remember(server, enabled, openAIKey, xaiKey, engine, provider, support) {
        whatsNewVoiceAvailable(server)
    }
}

/** Fixes and nuances, by day. One with an action opens a page for it. */
@Composable
fun WhatsNewSmallerListPage(store: SettingsStore) {
    val whatsNew by store.observe { it.whatsNew }
    val days = remember(whatsNew.entries) { WhatsNewDay.group(whatsNew.smallerChanges) }
    SettingsPageScaffold(title = stringResource(R.string.settings_whatsnew_smaller_changes), store = store) {
        for (day in days) {
            settingsSection(key = "day-${day.id}", header = whatsNewDayTitle(day.day)) {
                day.entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider(inset = true)
                    if (entry.destination != null) {
                        WhatsNewSmallerRow(entry) {
                            store.send(SettingsFeature.Action.PagePushed(SettingsPage.WhatsNewDetail(entry.id)))
                        }
                    } else {
                        WhatsNewSmallerRow(entry, onClick = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun WhatsNewSmallerRow(entry: WhatsNewEntry, onClick: (() -> Unit)?) {
    SettingsRowFrame(
        Modifier.semantics(mergeDescendants = true) {}.padding(vertical = 2.dp),
        onClick = onClick,
    ) {
        Box(Modifier.width(36.dp).align(Alignment.Top).padding(top = 2.dp), contentAlignment = Alignment.Center) {
            OpenCodeIcon(entry.symbol, contentDescription = null, size = 22.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = SettingsText.title, color = OpenCodeTheme.colors.text)
            Text(entry.summary, style = SettingsText.summary, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * A feature's symbol lit on the app's LED dot grid (3.5 dp pitch), in the feature's colour with a
 * soft glow, under a slow sweep of light on the shared ambient clock. Still under reduce motion,
 * while the app isn't in the foreground, and off screen (it leaves composition with its list item).
 */
@Composable
private fun WhatsNewDotSymbol(symbol: String, tint: Color, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val density = LocalDensity.current
    val isDark = OpenCodeTheme.colors.isDark
    var size by remember { mutableStateOf(IntSize.Zero) }
    val pitch = with(density) { DOT_PITCH.dp.toPx() }
    val field by produceState<DotField?>(null, symbol, size, pitch) {
        if (size.width == 0 || size.height == 0) return@produceState
        val drawable = androidx.core.content.res.ResourcesCompat.getDrawable(resources, sfSymbol(symbol), null) ?: return@produceState
        value = withContext(Dispatchers.Default) {
            DotField.make(drawable, size.width.toFloat(), size.height.toFloat(), pitch)
        }
    }
    val unit = density.density
    val glowPaint = remember(unit) {
        Paint().also { it.nativePaint.maskFilter = BlurMaskFilter(5f * unit, BlurMaskFilter.Blur.NORMAL) }
    }
    // On the shared ambient clock: a large, slow field, so the background rate (30 Hz).
    AmbientTimeline(rate = AmbientRate.Background) { sample ->
        // The band keeps its own slower period, on the clock's (slow-motion aware) time.
        val phase = if (sample.isSettled) null else AmbientClock.phase(AmbientClock.mediaTime(), SWEEP_PERIOD).toFloat()
        Box(
            modifier
                .clip(RoundedCornerShape(OpenCodeRadius.card))
                .background(if (isDark) Color(0.04f, 0.04f, 0.04f) else Color(0.965f, 0.965f, 0.965f))
                .onSizeChanged { size = it },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val field = field ?: return@Canvas
                if (field.width != this.size.width || field.height != this.size.height) return@Canvas
                val ink = if (isDark) Color.White.copy(alpha = 0.075f) else Color.Black.copy(alpha = 0.07f)
                drawPath(field.unlit, ink)
                // A band of light crossing left to right, leaning with the rows.
                val sweep = phase?.let { it * (field.columns + 40) - 20 }
                val levels = Array(4) { Path() }
                for (dot in field.lit) {
                    val band = sweep?.let { exp(-(dot.column - dot.row * 0.35f - it).pow(2) / 60f) } ?: 0f
                    val value = min(1f, dot.coverage * (0.78f + 0.22f * dot.shade) + band * 0.35f * dot.coverage)
                    val radius = (0.95f + 0.45f * value) * unit
                    val level = min(3, (value * 4).toInt())
                    levels[level].addOval(Rect(dot.x - radius, dot.y - radius, dot.x + radius, dot.y + radius))
                }
                val glow = if (isDark) 0.75f else 0.4f
                drawIntoCanvas { canvas ->
                    levels.forEachIndexed { index, path ->
                        glowPaint.color = tint.copy(alpha = levelAlpha(index) * glow)
                        canvas.drawPath(path, glowPaint)
                    }
                }
                levels.forEachIndexed { index, path -> drawPath(path, tint.copy(alpha = levelAlpha(index))) }
            }
        }
    }
}

private fun levelAlpha(index: Int): Float = 0.3f + 0.7f * (index + 0.5f) / 4f

private const val DOT_PITCH = 3.5f
private const val SWEEP_PERIOD = 4.2

/** Which dots a symbol lights on a grid: each dot's share of the symbol's ink, from a 4× supersampled rendering. */
internal class DotField(
    val width: Float,
    val height: Float,
    val columns: Int,
    val rows: Int,
    val lit: List<Dot>,
    val unlit: Path,
) {
    class Dot(
        val column: Int,
        val row: Int,
        val x: Float,
        val y: Float,
        /** How much of the dot's cell the symbol covers, 0…1. */
        val coverage: Float,
        /** Brighter toward the top, like the scope's phosphor. */
        val shade: Float,
    )

    companion object {
        fun make(drawable: Drawable, width: Float, height: Float, pitch: Float, scale: Float = 0.56f): DotField {
            val columns = max(1, (width / pitch).toInt())
            val rows = max(1, (height / pitch).toInt())
            val originX = (width - columns * pitch) / 2
            val originY = (height - rows * pitch) / 2
            val coverage = coverage(drawable, columns, rows, scale)
            val lit = ArrayList<Dot>()
            val unlit = Path()
            val unlitRadius = 0.62f * pitch / DOT_PITCH
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    val x = originX + (column + 0.5f) * pitch
                    val y = originY + (row + 0.5f) * pitch
                    val value = coverage[row * columns + column]
                    if (value > 0.04f) {
                        lit += Dot(column, row, x, y, value, 1 - row.toFloat() / rows)
                    } else {
                        unlit.addOval(Rect(x - unlitRadius, y - unlitRadius, x + unlitRadius, y + unlitRadius))
                    }
                }
            }
            return DotField(width, height, columns, rows, lit, unlit)
        }

        /** The symbol drawn centred at [scale] of the grid's height (at most 80% of its width), averaged per cell. */
        private fun coverage(drawable: Drawable, columns: Int, rows: Int, scale: Float): FloatArray {
            val sample = 4
            val width = columns * sample
            val height = rows * sample
            val result = FloatArray(columns * rows)
            val intrinsicWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return result
            val intrinsicHeight = max(drawable.intrinsicHeight, 1)
            var drawHeight = height * scale
            var drawWidth = drawHeight * intrinsicWidth / intrinsicHeight
            val maxWidth = width * 0.8f
            if (drawWidth > maxWidth) {
                drawHeight *= maxWidth / drawWidth
                drawWidth = maxWidth
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val left = ((width - drawWidth) / 2).toInt()
            val top = ((height - drawHeight) / 2).toInt()
            drawable.mutate().setBounds(left, top, left + drawWidth.toInt(), top + drawHeight.toInt())
            drawable.draw(android.graphics.Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            for (row in 0 until rows) {
                for (column in 0 until columns) {
                    var sum = 0
                    for (y in 0 until sample) {
                        for (x in 0 until sample) {
                            sum += pixels[(row * sample + y) * width + column * sample + x] ushr 24
                        }
                    }
                    result[row * columns + column] = sum.toFloat() / (sample * sample * 255)
                }
            }
            return result
        }
    }
}
