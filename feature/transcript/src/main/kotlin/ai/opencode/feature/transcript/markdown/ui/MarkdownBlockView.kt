package ai.opencode.feature.transcript.markdown.ui

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.SFSymbol
import ai.opencode.feature.media.AgentFileCards
import ai.opencode.feature.media.AgentFileChecks
import ai.opencode.feature.media.AgentImageGallery
import ai.opencode.feature.media.CheckedAgentFiles
import ai.opencode.feature.media.LocalLinkPreviewRevision
import ai.opencode.feature.media.LocalLinkPreviewStore
import ai.opencode.feature.media.MermaidDiagramCard
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.transcript.LocalTranscriptRowContext
import ai.opencode.feature.transcript.R
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.markdown.InlineRun
import ai.opencode.feature.transcript.markdown.InlineText
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock.Kind
import ai.opencode.feature.transcript.selection.LocalTranscriptReplySelection
import ai.opencode.feature.transcript.selection.TranscriptSelectionLeafKey
import ai.opencode.server.model.AgentFileReference
import ai.opencode.feature.transcript.user.TranscriptDragContext
import ai.opencode.feature.transcript.user.TranscriptDragPayload
import ai.opencode.feature.transcript.user.TranscriptDragSurfaceStyle
import ai.opencode.feature.transcript.user.transcriptDragSource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * One rendered Markdown block, shared by assistant replies, compaction summaries, and Markdown
 * files in the viewer. Rows read the server, directory and `send` from `LocalTranscriptRowContext`:
 * links and file links send `LinkTapped` / `OpenFile`, images `PreviewImage`.
 *
 * @param arrivals streamed text that just arrived, which fades in (`offset == 0`: the whole
 *   block arrived and fades in whole).
 * @param isGrowingTail the reply's last block while it can still grow: a link still streaming
 *   in at its end keeps the globe.
 * @param selectionItemID The reply row this block is in: its text joins the list's one selection
 *     across the reply instead of being selectable on its own.
 */
@Composable
fun MarkdownBlockView(
    block: OpenCodeMarkdownBlock,
    modifier: Modifier = Modifier,
    gap: Dp = 0.dp,
    arrivals: List<TranscriptTextReveal.Arrival> = emptyList(),
    isGrowingTail: Boolean = false,
    selectionItemID: String? = null,
) {
    val reveal = LocalTranscriptTextReveal.current ?: remember { TranscriptTextReveal() }
    val whole = arrivals.firstOrNull { it.offset == 0 }
    val grown = arrivals.filter { it.offset > 0 }
    val inList = LocalTranscriptReplySelection.current != null
    val selection = if (selectionItemID != null && inList) TranscriptSelectionLeafKey(selectionItemID, block.id) else null
    // File cards carry their own gap: none shows for a file that isn't there.
    val spaced = if (block.kind is Kind.Files) modifier else modifier.padding(top = gap)
    Column(spaced.fillMaxWidth().arrivalFade(whole, reveal)) {
        BlockContent(block.kind, grown, isGrowingTail, selection, gap)
    }
}

@Composable
private fun BlockContent(kind: Kind, grown: List<TranscriptTextReveal.Arrival>, isGrowingTail: Boolean, selection: TranscriptSelectionLeafKey?, gap: Dp) {
    val style = currentMarkdownStyle()
    val colors = style.colors
    val context = LocalTranscriptRowContext.current
    val send = context.send
    when (kind) {
        is Kind.NumberedItem -> ListItem(kind, kind.marker, 0, kind.text, grown, isGrowingTail, selection)
        is Kind.ListItem -> ListItem(kind, kind.marker, kind.level, kind.text, grown, isGrowingTail, selection)
        is Kind.Blockquote -> Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        colors.secondaryText.copy(alpha = colors.secondaryText.alpha * 0.5f),
                        Offset.Zero, Size(3.dp.toPx(), size.height), CornerRadius(2.dp.toPx()),
                    )
                }
                .padding(start = TranscriptMetrics.standard, top = TranscriptMetrics.compact, bottom = TranscriptMetrics.compact),
        ) {
            BlockText(kind, kind.text, style.bodyStyle, colors.secondaryText, grown, isGrowingTail, selection, tonesBody = false)
        }
        is Kind.Paragraph -> BlockText(kind, kind.text, style.bodyStyle, style.bodyColor, grown, isGrowingTail, selection)
        Kind.Divider ->
            // At the prose's width, like the desktop's `hr`, with the block gap on each side.
            HairlineRule(Modifier.clearAndSetSemantics {})
        is Kind.PullRequest -> PullRequestCard(kind.title, kind.url) { send(TranscriptAction.LinkTapped(kind.url)) }
        is Kind.Files -> FileCards(kind.files, gap)
        is Kind.Image -> MarkdownImage(kind.source, kind.alt, fallbackFile = AgentFileReference.fromLink(kind.source))
        is Kind.Heading -> BlockText(
            kind, kind.text, style.headingStyle(kind.level), colors.strongText, grown, isGrowingTail, selection,
            headingLevel = kind.level, modifier = Modifier.semantics { heading() },
        )
        is Kind.Code -> {
            val text = remember(kind.code, colors) { PresentedText.plain(MarkdownPresentation.code(kind.code, colors)) }
            // In a reply a hold selects the code; a hold on code already selected lifts the block
            // to drag.
            var drag: Modifier = Modifier
            if (selection != null) {
                drag = Modifier.transcriptDragSource(
                    TranscriptDragPayload.Code(kind.code.source, kind.code.language),
                    cornerRadius = 10.dp,
                    surface = TranscriptDragSurfaceStyle.Band,
                )
            }
            val source = kind.code.source
            MarkdownBand(
                bandModifier = drag,
                action = { CodeCopyButton(source) { send(TranscriptAction.CopyTextTapped(it)) } },
            ) {
                QuoteSelectionContainer(kind, inReply = selection != null) {
                    ArrivingText(text, grown, MarkdownStyle.codeTextStyle(style.tuning.codeLeading), colors.text, softWrap = false, selectionKey = selection)
                }
            }
        }
        is Kind.Table -> MarkdownTable(kind.header, kind.rows, selection)
        is Kind.Math -> MathDisplayView(kind.math, selection)
        // The diagram drags out as `diagram.png`.
        is Kind.Mermaid -> androidx.compose.runtime.CompositionLocalProvider(
            ai.opencode.feature.media.LocalTranscriptImageDragSource provides MermaidImageDragSource,
        ) {
            MermaidDiagramCard(kind.source) { send(TranscriptAction.PreviewImage(it)) }
        }
    }
}

/**
 * A block's inline text: presented (session chips, Link Icons, math), selectable with Quote,
 * fading in what arrived, reporting its session IDs and its icons' first fetch.
 */
@Composable
private fun BlockText(
    kind: Kind,
    inline: InlineText,
    textStyle: TextStyle,
    color: Color,
    grown: List<TranscriptTextReveal.Arrival>,
    isGrowingTail: Boolean,
    selection: TranscriptSelectionLeafKey?,
    modifier: Modifier = Modifier,
    secondary: Boolean = false,
    headingLevel: Int? = null,
    tonesBody: Boolean = true,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val style = currentMarkdownStyle()
    val muted = LocalMarkdownSecondary.current
    val tint = if (muted) style.colors.secondaryText else color
    val density = LocalDensity.current
    val sessionLinks = LocalSessionLinks.current
    val linkStyle = LocalInlineLinkStyle.current
    // The list's store, read for Link Icons; the revision redraws the row when an icon arrives.
    val linkStore = LocalLinkPreviewStore.current
    val linkRevision = LocalLinkPreviewRevision.current
    val linkRow = LocalLinkPreviewRow.current
    val siteIcon = remember(linkStore) { linkStore?.let { store -> { url: String -> store.inlineIcon(url) } } }
    val listener = rememberLinkListener()
    val options = PresentationOptions(
        foreground = tint,
        textSize = textStyle.fontSize,
        secondary = secondary || muted,
        headingLevel = headingLevel,
        tonesBody = tonesBody,
        linkStyle = linkStyle,
        isGrowing = isGrowingTail,
    )
    val shownInline = withoutMissingFiles(inline)
    val presented = remember(shownInline, options, sessionLinks.targets, style.colors, density, siteIcon, linkRevision) {
        MarkdownPresentation.present(shownInline, options, style, sessionLinks.targets, density, listener, siteIcon)
    }
    QuoteSelectionContainer(kind, inReply = selection != null, modifier) {
        ArrivingText(presented, presented.mapArrivals(grown), textStyle, tint, selectionKey = selection, onTextLayout = onTextLayout)
    }
    ReportsSessionLinks(presented.sessionIDs)
    val fetchable = presented.fetchableLinks
    if (fetchable.isNotEmpty() && linkStore != null && linkRow != null) {
        LaunchedEffect(linkStore, fetchable, linkRow) { linkStore.inlineLinksAppeared(fetchable, linkRow) }
    }
}

/** A file that isn't there keeps its name in code, without a link. */
@Composable
private fun withoutMissingFiles(inline: InlineText): InlineText {
    val context = LocalTranscriptRowContext.current
    val server = context.server ?: return inline
    val checks = remember { dependency(AgentFileChecks) }
    var runs: MutableList<InlineRun>? = null
    for ((index, run) in inline.runs.withIndex()) {
        val link = run.link ?: continue
        val file = MarkdownLinks.fileReference(link) ?: continue
        if (checks.status(server, context.directory, file) != AgentFileChecks.Status.Missing) continue
        if (runs == null) runs = inline.runs.toMutableList()
        runs[index] = run.copy(link = null)
    }
    return if (runs == null) inline else InlineText(runs)
}

/** The marker hangs, so wrapped lines align with the text, not under it. */
@Composable
private fun ListItem(
    kind: Kind,
    marker: String,
    level: Int,
    text: InlineText,
    grown: List<TranscriptTextReveal.Arrival>,
    isGrowingTail: Boolean,
    selection: TranscriptSelectionLeafKey?,
) {
    val style = currentMarkdownStyle()
    val colors = style.colors
    val done = marker == OpenCodeMarkdownBlock.doneTask
    val isTask = done || marker == OpenCodeMarkdownBlock.openTask
    val bodyStyle = style.bodyStyle
    val iconSize = with(LocalDensity.current) { (style.tuning.bodySize * 1.2f).sp.toDp() }
    // alignByBaseline() crashes Compose UI 1.12 here (b/549552303); restore it on 1.13.
    val markerBaseline = remember { mutableIntStateOf(0) }
    val bodyBaseline = remember { mutableIntStateOf(0) }
    val onMarkerLayout = { layout: TextLayoutResult -> markerBaseline.intValue = layout.firstBaseline.roundToInt() }
    Row(
        Modifier.fillMaxWidth().padding(start = MarkdownStyle.listIndent * level).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.widthIn(min = 20.dp).alignBy { markerBaseline.intValue }, contentAlignment = Alignment.TopEnd) {
            if (isTask) {
                // An unseen line of body text carries the first baseline the icon sits on.
                Text(" ", style = bodyStyle, color = Color.Transparent, onTextLayout = onMarkerLayout)
                OpenCodeIcon(
                    if (done) SFSymbol.CheckmarkSquareFill else SFSymbol.Square,
                    contentDescription = if (done) stringResource(R.string.transcript_task_done_description) else stringResource(R.string.transcript_task_not_done_description),
                    size = iconSize,
                    tint = colors.secondaryText,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            } else {
                Text(marker, style = if (marker == "•") bodyStyle else style.listNumberStyle, color = colors.secondaryText, onTextLayout = onMarkerLayout)
            }
        }
        Box(Modifier.weight(1f).alignBy { bodyBaseline.intValue }) {
            BlockText(
                kind, text, bodyStyle, if (done) colors.secondaryText else style.bodyColor, grown, isGrowingTail, selection,
                secondary = done,
                onTextLayout = { bodyBaseline.intValue = it.firstBaseline.roundToInt() },
            )
        }
    }
}

private val MermaidImageDragSource = { image: android.graphics.Bitmap, name: String, radius: androidx.compose.ui.unit.Dp, onLift: () -> Unit, onDragStart: () -> Unit ->
    Modifier.transcriptDragSource(
        TranscriptDragPayload.Image(image.asImageBitmap(), name),
        cornerRadius = radius,
        onLift = onLift,
        onDragStart = onDragStart,
    )
}

/** Purple branch glyph, the title, and an outward arrow. */
@Composable
private fun PullRequestCard(title: String, url: String, onOpen: () -> Unit) {
    val colors = currentMarkdownStyle().colors
    val shape = RoundedCornerShape(TranscriptMetrics.cardCornerRadius)
    val purple = if (colors.isDark) Color(0xFFBF5AF2) else Color(0xFFAF52DE)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TranscriptMetrics.minimumHitSide)
            .transcriptDragSource(TranscriptDragPayload.Url(url), cornerRadius = TranscriptMetrics.cardCornerRadius)
            .clip(shape)
            .background(colors.userBubble, shape)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = TranscriptMetrics.standard),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OpenCodeIcon(SFSymbol.ArrowTrianglePull, contentDescription = null, size = 18.dp, tint = purple)
        Text(
            title,
            style = OpenCodeTextStyles.subheadline.copy(fontWeight = FontWeight.Medium),
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.weight(0.0001f))
        OpenCodeIcon(SFSymbol.ArrowUpRight, contentDescription = null, size = 14.dp, tint = colors.tertiaryText)
    }
}

/**
 * A `.files` block: image previews in the transcript, file cards on other Markdown surfaces (and
 * without a server to read the images from).
 */
@Composable
private fun FileCards(files: List<AgentFileReference>, gap: Dp) {
    val context = LocalTranscriptRowContext.current
    val server = context.server
    val onOpenFile = { file: AgentFileReference -> context.send(TranscriptAction.OpenFile(file)) }
    // Media's gallery tiles and file cards drag their files (`.agentFile`) and read the server and
    // folder from here.
    val directory = context.directory
    androidx.compose.runtime.CompositionLocalProvider(ai.opencode.feature.media.LocalMediaDragContext provides ai.opencode.feature.media.MediaDragContext(server, directory)) {
        CheckedAgentFiles(files, server, directory) { shown ->
            Column(Modifier.padding(top = gap)) {
                FileCardsContent(shown, server, context.imagePreviews, directory, onOpenFile)
            }
        }
    }
}

@Composable
private fun FileCardsContent(
    files: List<AgentFileReference>,
    server: ai.opencode.server.OpenCodeServer?,
    imagePreviews: Boolean,
    directory: String?,
    onOpenFile: (AgentFileReference) -> Unit,
) {
    val context = LocalTranscriptRowContext.current
    if (imagePreviews && server != null && files.any { it.kind == AgentFileReference.Kind.Image }) {
        AgentImageGallery(
            files = files,
            server = server,
            directory = directory,
            onPreviewImage = { context.send(TranscriptAction.PreviewImage(it)) },
            onOpenFile = onOpenFile,
        )
    } else {
        AgentFileCards(files, onOpenFile)
    }
}
