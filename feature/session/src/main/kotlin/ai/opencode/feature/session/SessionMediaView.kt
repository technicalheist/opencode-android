package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeIconSize
import ai.opencode.core.ui.OpenCodeRadius
import ai.opencode.core.ui.OpenCodeSecondaryButton
import ai.opencode.core.ui.OpenCodeTextStyles
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.OpenCodeTopBar
import ai.opencode.core.ui.OpenCodeTopBarBackButton
import ai.opencode.core.ui.openCodeHairline
import ai.opencode.core.ui.openCodeTapTarget
import ai.opencode.feature.media.AgentFileChecks
import ai.opencode.feature.media.AgentImagePreviews
import ai.opencode.feature.media.CachedImage
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.feature.media.MermaidTheme
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.media.mediaZoomSource
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.AgentFileReference
import ai.opencode.server.model.OpenCodeTranscriptItem
import androidx.compose.foundation.Image
import ai.opencode.core.R as CoreR
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import ai.opencode.feature.media.AgentImageViewport
import ai.opencode.feature.media.AgentImageVisibility
import ai.opencode.feature.media.LocalAgentImageViewport
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import ai.opencode.core.dependencies.DateClient
import ai.opencode.feature.media.AgentImageMarker
import ai.opencode.feature.media.AgentImageSighting
import ai.opencode.feature.media.MediaPoster
import ai.opencode.feature.media.MediaPosterClient
import ai.opencode.feature.media.fileReadRequest
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.PageTransitions
import ai.opencode.core.ui.OpenCodeIconButton
import ai.opencode.core.ui.OpenCodeIconButtonStyle
import ai.opencode.core.ui.OpenCodeSurfaces
import ai.opencode.core.ui.SFSymbol
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import ai.opencode.core.ui.drawerPanHorizontalScroller
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.lerp

/** What Session Media reads from the session while it's open. */
@Immutable
private data class SessionMediaModel(
    val items: List<OpenCodeTranscriptItem>,
    val server: OpenCodeServer,
    val directory: String?,
    val hasEarlier: Boolean,
    val isLoadingEarlier: Boolean,
    /** The last older page failed; the button tries again. */
    val earlierFailed: Boolean,
    /** The last Load Earlier brought nothing for this tab. */
    val earlierNothingNew: Boolean,
) {
    companion object {
        fun from(state: State) = SessionMediaModel(
            items = state.sessionMediaItems,
            server = state.server,
            directory = state.session?.directory,
            hasEarlier = state.hasEarlierItems && state.earlierHistory != State.EarlierHistory.Exhausted,
            isLoadingEarlier = state.isLoadingEarlier,
            earlierFailed = state.earlierHistoryError != null || state.earlierHistoryRetriesOnReconnect,
            earlierNothingNew = state.sessionMediaNothingNew,
        )
    }
}

/**
 * Session Media: the session's attachments, files, media and web links as one list of cards over
 * the session, filtered All, Files or Links. A card opens what the transcript's would: the viewer
 * (which flies from the card and lands back on it) or the link's browser. Back closes it.
 */
@Composable
internal fun SessionMediaPage(store: StoreOf<State, Action>, modifier: Modifier = Modifier) {
    val filter by store.observe { it.sessionMediaFilter }
    val reducedMotion = rememberReduceMotion()
    val host = LocalSessionComposerHost.current
    val isOpen = filter != null
    LaunchedEffect(isOpen) { if (isOpen) host?.dismissComposerKeyboard() }
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(isOpen) { if (isOpen) backProgress.snapTo(0f) }
    PredictiveBackHandler(enabled = isOpen) { events ->
        try {
            events.collect { event ->
                backEdge = event.swipeEdge
                backProgress.snapTo(event.progress)
            }
            store.send(Action.SessionMediaDismissed)
        } catch (cancel: CancellationException) {
            scope.launch { backProgress.animateTo(0f) }
            throw cancel
        }
    }
    val density = LocalDensity.current
    val slidePx = with(density) { PageTransitions.SlideDistance.roundToPx() }
    val leanPx = with(density) { PageTransitions.BackPreviewShift.toPx() }
    val cornerPx = with(density) { PageTransitions.BackPreviewCorner.toPx() }
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val slide = tween<IntOffset>(Motion.millis(PageTransitions.DURATION_SECONDS), easing = FastOutSlowInEasing)
    val reducedFade = tween<Float>(Motion.millis(PageTransitions.REDUCED_FADE_SECONDS))
    AnimatedVisibility(
        visible = isOpen,
        modifier = modifier,
        enter = if (reducedMotion) {
            fadeIn(reducedFade)
        } else {
            slideInHorizontally(slide) { slidePx * sign } + fadeIn(
                tween(
                    Motion.millis(PageTransitions.DURATION_SECONDS - PageTransitions.FADE_OUT_SECONDS),
                    delayMillis = Motion.millis(PageTransitions.FADE_OUT_SECONDS),
                    easing = LinearOutSlowInEasing,
                ),
            )
        },
        exit = if (reducedMotion) {
            fadeOut(reducedFade)
        } else {
            slideOutHorizontally(slide) { slidePx * sign } + fadeOut(tween(Motion.millis(PageTransitions.FADE_OUT_SECONDS), easing = FastOutLinearInEasing))
        },
    ) {
        val lastFilter = remember { arrayOf(SessionMedia.Filter.media) }
        filter?.let { lastFilter[0] = it }
        val paneLabel = stringResource(R.string.session_media_title)
        Box(
            Modifier
                .fillMaxSize()
                .semantics { paneTitle = paneLabel }
                .graphicsLayer {
                    val progress = backProgress.value
                    if (reducedMotion || progress <= 0f) return@graphicsLayer
                    val scale = 1f - (1f - PageTransitions.BACK_PREVIEW_SCALE) * progress
                    scaleX = scale
                    scaleY = scale
                    translationX = leanPx * progress * if (backEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerPx * progress)
                    clip = true
                },
        ) {
            SessionMediaContent(store, lastFilter[0])
        }
    }
}

@Composable
private fun SessionMediaContent(store: StoreOf<State, Action>, filter: SessionMedia.Filter) {
    val model by store.observe(SessionMediaModel::from)
    val layout by store.observe { it.sessionMediaLayout }
    val checks = remember { dependency(AgentFileChecks) }
    val previews = remember { dependency(AgentImagePreviews) }
    val now = remember(model.items) { dependency(DateClient).now() }
    val all by remember(model.items, model.server, model.directory, now) {
        derivedStateOf { SessionMedia.collect(model.items, model.directory, now) { checks.shown(model.server, model.directory, it) } }
    }
    val directory = model.directory
    if (directory != null) {
        val replyFiles = remember(model.items, directory) { SessionMedia.replyFiles(model.items).filter { AgentFileChecks.checks(it, directory) } }
        val generation = checks.generation(model.server, directory)
        DisposableEffect(replyFiles, model.server, directory, generation) {
            val release = checks.watch(model.server, directory, replyFiles)
            onDispose { release() }
        }
    }
    val open = rememberSessionMediaOpener(store) { all }
    val tabs = SessionMedia.Filter.entries
    val pager = rememberPagerState(initialPage = tabs.indexOf(filter)) { tabs.size }
    val scope = rememberCoroutineScope()
    // Each tab keeps its own place while the page is open, swiped away or covered by the viewer.
    val gridState = rememberLazyGridState()
    val mediaListState = rememberLazyListState()
    val filesListState = rememberLazyListState()
    val linksListState = rememberLazyListState()
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page -> store.send(Action.SessionMediaFilterPicked(tabs[page])) }
    }
    val pickTab: (SessionMedia.Filter) -> Unit = { picked ->
        store.send(Action.SessionMediaFilterPicked(picked))
        val page = tabs.indexOf(picked)
        scope.launch { pager.scrollToPage(page) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(OpenCodeTheme.colors.canvas)
            // Touches stop here rather than reaching the transcript underneath.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
            .testTag("session-media"),
    ) {
        OpenCodeTopBar(
            title = { Text(stringResource(R.string.session_media_title), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            navigation = { OpenCodeTopBarBackButton(onClick = { store.send(Action.SessionMediaDismissed) }) },
            actions = {
                if (filter == SessionMedia.Filter.media) {
                    SessionMediaLayoutAction(layout, onPick = { store.send(Action.SessionMediaLayoutPicked(it)) })
                }
            },
            hairline = false,
        )
        SessionMediaFilters(
            pager = pager,
            counts = tabs.associateWith { SessionMedia.filtered(all, it).size },
            onPick = pickTab,
        )
        HorizontalPager(
            state = pager,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The drawer still opens from Media; on the other tabs a rightward swipe goes back a tab.
                .drawerPanHorizontalScroller({ pager.canScrollBackward }, { pager.canScrollForward })
                .testTag("session-media-pager"),
            key = { tabs[it].id },
            verticalAlignment = Alignment.Top,
        ) { page ->
            val pageFilter = tabs[page]
            val shown = remember(all, pageFilter) { SessionMedia.filtered(all, pageFilter) }
            Box(Modifier.fillMaxSize().testTag("session-media-page-${pageFilter.id}"), contentAlignment = Alignment.TopCenter) {
                val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                if (pageFilter == SessionMedia.Filter.media && shown.isNotEmpty()) {
                    // One list of months for both layouts: the same items in the same order.
                    val months = remember(shown, now) { SessionMedia.months(shown, now) }
                    when (layout) {
                        SessionMedia.Layout.tiles -> SessionMediaGrid(months, model, previews, bottom, gridState, open, earlier = { SessionMediaEarlier(store, model) })
                        SessionMedia.Layout.list -> LazyColumn(
                            Modifier.fillMaxSize().widthIn(max = TranscriptMetrics.readableColumnWidth).testTag("session-media-media-list"),
                            state = mediaListState,
                            contentPadding = PaddingValues(bottom = bottom + 24.dp),
                        ) {
                            for ((index, month) in months.withIndex()) {
                                month.label?.let { label -> stickyHeader(key = "month-$index-$label", contentType = "month") { SessionMediaMonthLabel(label) } }
                                items(month.items, key = { it.id }, contentType = { "card" }) { item ->
                                    SessionMediaCard(item, model.server, model.directory, previews, onOpen = { open(item) })
                                }
                            }
                            if (model.hasEarlier) item(key = "earlier") { SessionMediaEarlier(store, model) }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().widthIn(max = TranscriptMetrics.readableColumnWidth).testTag("session-media-list"),
                        state = when (pageFilter) {
                            SessionMedia.Filter.files -> filesListState
                            SessionMedia.Filter.links -> linksListState
                            SessionMedia.Filter.media -> mediaListState
                        },
                        contentPadding = PaddingValues(top = 8.dp, bottom = bottom + 24.dp),
                    ) {
                        if (shown.isEmpty()) {
                            item(key = "empty") { SessionMediaEmpty(pageFilter, model.hasEarlier) }
                        }
                        items(shown, key = { it.id }) { item ->
                            SessionMediaCard(item, model.server, model.directory, previews, onOpen = { open(item) })
                        }
                        if (model.hasEarlier) item(key = "earlier") { SessionMediaEarlier(store, model) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionMediaEarlier(store: StoreOf<State, Action>, model: SessionMediaModel) {
    val loading = model.isLoadingEarlier
    val note = when {
        loading -> R.string.session_media_earlier_note
        model.earlierFailed -> R.string.session_media_earlier_failed
        model.earlierNothingNew -> R.string.session_media_earlier_nothing_new
        else -> R.string.session_media_earlier_note
    }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(note),
            style = OpenCodeTextStyles.footnote,
            color = if (!loading && model.earlierFailed) OpenCodeTheme.colors.destructive else OpenCodeTheme.colors.secondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("session-media-earlier-note"),
        )
        // Busy, it keeps its label and width: a spinner takes the icon's place, and it takes no taps.
        val loadingDescription = stringResource(R.string.session_media_loading_earlier)
        val reducedMotion = rememberReduceMotion()
        OpenCodeSecondaryButton(
            onClick = { store.send(Action.SessionMediaLoadEarlierTapped) },
            enabled = !loading,
            modifier = Modifier
                .semantics { if (loading) stateDescription = loadingDescription }
                .testTag("session-media-load-earlier"),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (loading && !reducedMotion) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OpenCodeTheme.colors.secondaryText)
                } else {
                    OpenCodeIcon(if (model.earlierFailed) "arrow.clockwise" else "clock.arrow.circlepath", contentDescription = null, size = 16.dp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (!loading && model.earlierFailed) stringResource(CoreR.string.common_try_again) else stringResource(R.string.session_media_load_earlier),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

/** Media as square tiles, at least [SessionMediaTileMinSize] across, under month headings. */
@Composable
private fun SessionMediaGrid(
    months: List<SessionMedia.Month>,
    model: SessionMediaModel,
    previews: AgentImagePreviews,
    bottom: Dp,
    grid: LazyGridState,
    open: (SessionMedia.Item) -> Unit,
    earlier: @Composable () -> Unit,
) {
    // Image files are read as their tiles come on screen, as the transcript's previews are.
    val viewport = remember { SessionMediaImageViewport() }
    val visibility = remember { dependency(AgentImageVisibility) }
    DisposableEffect(viewport) {
        visibility.register(viewport)
        onDispose { visibility.unregister(viewport) }
    }
    LaunchedEffect(grid) {
        snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { visibility.setNeedsUpdate() }
    }
    CompositionLocalProvider(LocalAgentImageViewport provides viewport) {
    LazyVerticalGrid(
        GridCells.Adaptive(SessionMediaTileMinSize),
        Modifier.fillMaxSize().testTag("session-media-grid").onGloballyPositioned {
            viewport.bounds = it.boundsInWindow()
            visibility.setNeedsUpdate()
        },
        grid,
        contentPadding = PaddingValues(start = SessionMediaGridInset, end = SessionMediaGridInset, bottom = bottom + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(SessionMediaTileGutter),
        verticalArrangement = Arrangement.spacedBy(SessionMediaTileGutter),
    ) {
        for ((index, month) in months.withIndex()) {
            val label = month.label
            if (label != null) {
                stickyHeader(key = "month-$index-$label", contentType = "month") { _ -> SessionMediaMonthLabel(label, inset = SessionMediaGridInset) }
            }
            gridItems(month.items, key = { it.id }, contentType = { "tile" }) { item ->
                SessionMediaTile(item, model.server, model.directory, previews, onOpen = { open(item) })
            }
        }
        if (model.hasEarlier) item(key = "earlier", span = { GridItemSpan(maxLineSpan) }) { earlier() }
    }
    }
}

private class SessionMediaImageViewport : AgentImageViewport {
    var bounds: Rect = Rect.Zero
    override val imageViewportVisibleRect: Rect get() = bounds
    override val imageViewportIsSettling: Boolean get() = false
    override val imageViewportIsVisible: Boolean get() = true
    override fun imageContentRevised(row: Any?) {}
}

internal val SessionMediaTileMinSize = 104.dp
internal val SessionMediaTileGutter = 2.dp
private val SessionMediaGridInset = 2.dp

/** A month's sticky header. */
@Composable
private fun SessionMediaMonthLabel(label: String, inset: Dp = 0.dp) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .background(OpenCodeTheme.colors.canvas)
            .semantics { heading() }
            .padding(start = 16.dp - inset, end = 16.dp - inset, top = 12.dp, bottom = 6.dp),
        style = OpenCodeTextStyles.footnote.copy(fontWeight = FontWeight.Medium),
        color = OpenCodeTheme.colors.secondaryText,
    )
}

/**
 * One square tile: an image's own pixels, a video's poster frame with its length, or an audio
 * tile (waveform, name, length). The viewer flies from it and lands back on it.
 */
@Composable
private fun SessionMediaTile(
    item: SessionMedia.Item,
    server: OpenCodeServer,
    directory: String?,
    previews: AgentImagePreviews,
    onOpen: () -> Unit,
) {
    val content = item.content
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(OpenCodeRadius.sm)
    val reference = (content as? SessionMedia.Content.File)?.reference
    val kind = reference?.kind ?: AgentFileReference.Kind.Image
    val title = SessionMedia.title(content)
    val imageKey = SessionMedia.imageFileKey(content, server, directory)
    imageKey?.let { previews.entry(it) }
    val cached = imageKey?.let(previews::image)
    // The file as the viewer opens it, never an older read's pixels while it loads.
    val attachment = SessionMedia.tileAttachment(content, showsFile = cached != null || (imageKey != null && directory != null))
    var poster by remember(item.id) { mutableStateOf<MediaPoster?>(null) }
    if (reference != null && kind.isTimeBased) {
        LaunchedEffect(item.id) { poster = dependency(MediaPosterClient).poster(server, reference, directory) }
    }
    val frame = poster?.frame
    val showsMedia = attachment != null || cached != null || frame != null
    val duration = poster?.durationMs?.let(SessionMedia::durationText)
    val description = listOfNotNull(title, SessionMedia.detail(content), duration).joinToString(", ")
    val openLabel = stringResource(R.string.session_media_open_click_label)
    val audio = kind == AgentFileReference.Kind.Audio
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(if (audio) OpenCodeTheme.colors.link.copy(alpha = 0.14f) else OpenCodeTheme.colors.inlineCodeFill, shape)
            .then(if (showsMedia) Modifier else Modifier.openCodeHairline(shape))
            .mediaZoomSource(SessionMedia.zoomSourceID(item), OpenCodeRadius.sm, showsMedia = showsMedia, preferred = true)
            .clickable(onClickLabel = openLabel, onClick = onOpen)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            }
            .testTag("session-media-tile"),
    ) {
        if (imageKey != null && cached == null && directory != null) {
            server.fileReadRequest(reference?.target(directory) ?: return@Box)?.let { request ->
                AgentImageMarker(AgentImageSighting(imageKey, "session-media"), SessionMedia.tileImageRequest(request), Modifier.fillMaxSize())
            }
        }
        when {
            attachment != null -> CachedImage(
                attachment.id, attachment.data, Modifier.fillMaxSize(),
                mediaHash = attachment.mediaHash, name = attachment.name, server = server,
            )
            cached != null -> Image(remember(cached) { cached.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            frame != null -> Image(remember(frame) { frame.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            audio -> Column(
                Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            ) {
                OpenCodeIcon("waveform", contentDescription = null, size = 36.dp, tint = OpenCodeTheme.colors.link)
                Text(title, style = OpenCodeTextStyles.caption2, color = OpenCodeTheme.colors.secondaryText, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            }
            else -> OpenCodeIcon(
                SessionMedia.symbolName(content), contentDescription = null, size = OpenCodeIconSize.medium,
                tint = OpenCodeTheme.colors.tertiaryText, modifier = Modifier.align(Alignment.Center),
            )
        }
        when {
            audio -> SessionMediaBadge("music.note", duration, Modifier.align(Alignment.BottomStart))
            kind == AgentFileReference.Kind.Video -> SessionMediaBadge("play.fill", duration, Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun SessionMediaBadge(symbol: String, text: String?, modifier: Modifier) {
    Row(
        modifier
            .padding(5.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(OpenCodeRadius.xs))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        OpenCodeIcon(symbol, contentDescription = null, size = 10.dp, tint = Color.White)
        if (text != null) {
            Text(text, style = OpenCodeTextStyles.caption2.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"), color = Color.White, maxLines = 1)
        }
    }
}

/**
 * What a card opens. Media: the session's full-screen viewer, paging through only the session's
 * media (audio too), starting on the card. Files: the file viewer on its own, or the paste
 * viewer. Links: a tapped link's route.
 */
@Composable
private fun rememberSessionMediaOpener(store: StoreOf<State, Action>, items: () -> List<SessionMedia.Item>): (SessionMedia.Item) -> Unit {
    val context = LocalContext.current
    val host = LocalSessionComposerHost.current
    val presenter = LocalMediaPresenter.current
    val reducedMotion = rememberReduceMotion()
    val theme = if (OpenCodeTheme.colors.isDark) MermaidTheme.Dark else MermaidTheme.Light
    val currentItems by rememberUpdatedState(items)
    return remember(store, context, host, presenter, reducedMotion, theme) {
        { item ->
            fun handle(action: TranscriptAction) = SessionTranscriptView.handle(action, store, context, host, presenter, reducedMotion, theme)
            val state = store.state
            val gallery = SessionMedia.gallery(currentItems(), item, state.server, state.session?.directory)
            when (val content = item.content) {
                is SessionMedia.Content.Image, is SessionMedia.Content.File -> if (gallery != null) {
                    presenter?.presentPreview(gallery, drawingHandler(store), flightAccessories = drawingAccessories(store, context))
                } else if (content is SessionMedia.Content.File) {
                    handle(TranscriptAction.OpenFile(content.reference))
                }
                is SessionMedia.Content.PastedText -> handle(TranscriptAction.OpenPastedText(content.text, content.summary, SessionMedia.zoomSourceID(item)))
                is SessionMedia.Content.Link -> store.send(Action.LinkTapped(content.url))
            }
        }
    }
}

/**
 * Media's Tiles / List choice as a top app bar action (as Android's Files and Photos switch a
 * view): the icon shows the layout a press switches to; TalkBack reads the current one.
 */
@Composable
private fun SessionMediaLayoutAction(selected: SessionMedia.Layout, onPick: (SessionMedia.Layout) -> Unit) {
    val description = stringResource(R.string.session_media_layout_description)
    val current = when (selected) {
        SessionMedia.Layout.tiles -> stringResource(R.string.session_media_layout_tiles)
        SessionMedia.Layout.list -> stringResource(R.string.session_media_layout_list)
    }
    val next = when (selected) {
        SessionMedia.Layout.tiles -> SessionMedia.Layout.list
        SessionMedia.Layout.list -> SessionMedia.Layout.tiles
    }
    OpenCodeIconButton(
        if (next == SessionMedia.Layout.list) SFSymbol.ListBullet else SFSymbol.SquareGrid2x2,
        contentDescription = description,
        onClick = { onPick(next) },
        style = OpenCodeIconButtonStyle.Standard,
        modifier = Modifier
            .semantics { stateDescription = current }
            .testTag("session-media-layout-${next.id}"),
    )
}

/**
 * Media, Files and Links as Material fixed tabs over the [pager]: the indicator follows the pager's
 * offset while it's swiped or animating, and the label nearest it reads as selected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionMediaFilters(
    pager: PagerState,
    counts: Map<SessionMedia.Filter, Int>,
    onPick: (SessionMedia.Filter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val index = pager.currentPage
    val colors = OpenCodeTheme.colors
    PrimaryTabRow(
        selectedTabIndex = index,
        modifier = modifier.fillMaxWidth().testTag("session-media-tabs"),
        containerColor = colors.canvas,
        contentColor = colors.text,
        indicator = {
            TabRowDefaults.PrimaryIndicator(
                Modifier.tabIndicatorLayout { measurable, constraints, positions ->
                    val position = (pager.currentPage + pager.currentPageOffsetFraction).coerceIn(0f, (positions.size - 1).toFloat())
                    val from = positions[position.toInt()]
                    val to = positions[minOf(position.toInt() + 1, positions.size - 1)]
                    val fraction = position - position.toInt()
                    val width = lerp(from.contentWidth, to.contentWidth, fraction)
                    val start = lerp(from.left + (from.width - from.contentWidth) / 2, to.left + (to.width - to.contentWidth) / 2, fraction)
                    val placeable = measurable.measure(constraints.copy(minWidth = width.roundToPx(), maxWidth = width.roundToPx()))
                    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(start.roundToPx(), 0) }
                },
                width = Dp.Unspecified,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        divider = { HorizontalDivider(thickness = OpenCodeSurfaces.hairline, color = colors.border) },
    ) {
        for ((tab, filter) in SessionMedia.Filter.entries.withIndex()) {
            val isSelected = tab == index
            val label = when (filter) {
                SessionMedia.Filter.media -> stringResource(R.string.session_media_filter_media)
                SessionMedia.Filter.files -> stringResource(R.string.session_media_filter_files)
                SessionMedia.Filter.links -> stringResource(R.string.session_media_filter_links)
            }
            val count = counts[filter] ?: 0
            Tab(
                selected = isSelected,
                onClick = { onPick(filter) },
                selectedContentColor = colors.text,
                unselectedContentColor = colors.secondaryText,
                modifier = Modifier.testTag("session-media-filter-${filter.id}"),
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            label,
                            style = OpenCodeTextStyles.subheadline.copy(fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium),
                            maxLines = 1,
                        )
                        Text(
                            count.toString(),
                            style = OpenCodeTextStyles.caption1.copy(fontFeatureSettings = "tnum"),
                            color = colors.tertiaryText,
                            maxLines = 1,
                        )
                    }
                },
            )
        }
    }
}

/**
 * One row of a Material list: a leading thumbnail, the title with what it is and who shared it
 * under it, full width with a ripple. Links end in an open-externally mark; other rows, like
 * Material list rows, carry no chevron.
 */
@Composable
private fun SessionMediaCard(
    item: SessionMedia.Item,
    server: OpenCodeServer,
    directory: String?,
    previews: AgentImagePreviews,
    onOpen: () -> Unit,
) {
    val content = item.content
    val title = SessionMedia.title(content)
    val detail = SessionMedia.detail(content)
    val origin = when (item.origin) {
        SessionMedia.Origin.user -> stringResource(R.string.session_media_origin_user)
        SessionMedia.Origin.agent -> stringResource(R.string.session_media_origin_agent)
    }
    val clickLabel = if (item.isLink) stringResource(R.string.session_media_open_link_click_label) else stringResource(R.string.session_media_open_click_label)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .mediaZoomSource(SessionMedia.zoomSourceID(item), preferred = true)
            .clickable(onClickLabel = clickLabel, onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("session-media-item"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbShape = androidx.compose.foundation.shape.RoundedCornerShape(OpenCodeRadius.sm)
        Box(
            Modifier.size(48.dp).clip(thumbShape).background(OpenCodeTheme.colors.inlineCodeFill, thumbShape),
            contentAlignment = Alignment.Center,
        ) {
            // An image file already read keeps its pixels here; nothing is read for the card.
            val cached = SessionMedia.imageFileKey(content, server, directory)?.let(previews::image)
            val attachment = SessionMedia.tileAttachment(content, showsFile = cached != null)
            // A video's row shows the poster its tile does.
            val video = (content as? SessionMedia.Content.File)?.reference?.takeIf { it.kind == AgentFileReference.Kind.Video }
            var poster by remember(item.id) { mutableStateOf<MediaPoster?>(null) }
            if (video != null) LaunchedEffect(item.id) { poster = dependency(MediaPosterClient).poster(server, video, directory) }
            val frame = poster?.frame
            when {
                attachment != null -> CachedImage(
                    attachment.id, attachment.data, Modifier.fillMaxSize(),
                    mediaHash = attachment.mediaHash, name = attachment.name, server = server, compact = true,
                )
                cached != null -> Image(
                    remember(cached) { cached.asImageBitmap() }, contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                frame != null -> Image(remember(frame) { frame.asImageBitmap() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> OpenCodeIcon(SessionMedia.symbolName(content), contentDescription = null, size = OpenCodeIconSize.medium, tint = OpenCodeTheme.colors.secondaryText)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = OpenCodeTextStyles.callout.copy(fontWeight = FontWeight.Medium),
                color = OpenCodeTheme.colors.text,
                maxLines = 1,
                overflow = if (item.isLink) TextOverflow.Ellipsis else TextOverflow.MiddleEllipsis,
            )
            Text(
                detail,
                style = OpenCodeTextStyles.footnote,
                color = OpenCodeTheme.colors.secondaryText,
                maxLines = 1,
                overflow = if (item.isLink) TextOverflow.MiddleEllipsis else TextOverflow.Ellipsis,
            )
            Text(origin, style = OpenCodeTextStyles.caption1, color = OpenCodeTheme.colors.tertiaryText, maxLines = 1)
        }
        if (item.isLink) {
            OpenCodeIcon("arrow.up.right", contentDescription = null, size = 18.dp, tint = OpenCodeTheme.colors.secondaryText)
        }
    }
}

@Composable
private fun SessionMediaEmpty(filter: SessionMedia.Filter, hasEarlier: Boolean) {
    val (title, message) = when (filter) {
        SessionMedia.Filter.media -> R.string.session_media_empty_media_title to R.string.session_media_empty_media_message
        SessionMedia.Filter.files -> R.string.session_media_empty_files_title to R.string.session_media_empty_files_message
        SessionMedia.Filter.links -> R.string.session_media_empty_links_title to R.string.session_media_empty_links_message
    }
    Column(
        Modifier.fillMaxWidth().padding(top = if (hasEarlier) 32.dp else 96.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OpenCodeIcon(
            when (filter) {
                SessionMedia.Filter.media -> "photo.on.rectangle"
                SessionMedia.Filter.files -> "doc"
                SessionMedia.Filter.links -> "link"
            },
            contentDescription = null,
            size = OpenCodeIconSize.hero,
            tint = OpenCodeTheme.colors.tertiaryText,
        )
        Text(stringResource(title), style = OpenCodeTextStyles.headline, color = OpenCodeTheme.colors.text, textAlign = TextAlign.Center)
        Text(stringResource(message), style = OpenCodeTextStyles.subheadline, color = OpenCodeTheme.colors.secondaryText, textAlign = TextAlign.Center)
    }
}
