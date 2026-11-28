@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.strings.localized
import ai.opencode.core.experiments.key
import ai.opencode.core.motion.Motion
import ai.opencode.core.motion.MotionPhase
import ai.opencode.core.motion.MotionRollDirection
import ai.opencode.core.motion.MotionTransition
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.motion.transitionBlurEffect
import ai.opencode.core.store.StoreOf
import ai.opencode.core.store.observe
import ai.opencode.core.ui.OpenCodeIcon
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.core.ui.ProviderIcon
import ai.opencode.core.ui.SFSymbol
import ai.opencode.core.ui.appFormattingLocale
import ai.opencode.core.ui.inContainer
import ai.opencode.feature.composer.PastedTextViewer
import ai.opencode.feature.media.TranscriptImagePreview
import ai.opencode.feature.media.AgentFileChecks
import ai.opencode.feature.media.AgentFileDestination
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LocalMediaPresenter
import ai.opencode.feature.media.MermaidTheme
import ai.opencode.feature.media.MediaPresenter
import ai.opencode.feature.media.TranscriptMetrics
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.header.HeaderBlurTunables
import ai.opencode.feature.session.header.SessionHeaderStyle
import ai.opencode.feature.session.header.titleBreathing
import ai.opencode.feature.transcript.NewMessagesPlacement
import ai.opencode.feature.transcript.TranscriptAction
import ai.opencode.feature.transcript.TranscriptConfiguration
import ai.opencode.feature.transcript.TranscriptEntry
import ai.opencode.feature.transcript.TranscriptTunables
import ai.opencode.feature.transcript.list.LocalTranscriptTimelinePaused
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.feature.transcript.list.TranscriptList
import ai.opencode.feature.transcript.list.TranscriptSlots
import ai.opencode.feature.transcript.rows.AgentGlyphState
import ai.opencode.feature.transcript.rows.LocalTranscriptRowSlots
import ai.opencode.feature.transcript.rows.ReplyCopyGlyph
import ai.opencode.feature.transcript.rows.TranscriptFonts
import ai.opencode.feature.transcript.rows.TranscriptRowSlots
import ai.opencode.feature.transcript.rows.TranscriptTimeStamps
import ai.opencode.feature.transcript.user.TranscriptSendFailure
import ai.opencode.feature.transcript.user.TranscriptUserMessage
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeTranscriptItem
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Port of iOS `Session/SessionTranscriptView.swift`: the transcript host below the header and
// above the composer, and the session-owned pieces the transcript draws (the title, the Working
// line, cached attachment images).
//
// iOS hosts its UIKit `TranscriptListView` with a `Configuration` projected from the store
// through a `ProjectionCache` (the visible window, entries, time stamps, the New divider's
// placement). Android projects the same `TranscriptConfiguration` here and hands it to
// `:feature:transcript`'s `TranscriptList`; the list's `TranscriptAction`s map to the
// `SessionFeature` actions of the same names. The Working line and the rows' Session-owned views
// (Since You Left, the activity orb, provider marks) go in through the list's slots.

/**
 * Owns everything below the header and above the composer. The list runs full-bleed under both
 * (its insets come from [top] and [scrollEdgeBridge]'s composer height), so the header backdrop
 * fades content rather than the screen clipping it.
 *
 * @param store the session screen's store.
 * @param scrollEdgeBridge geometry shared with the chrome, read per frame.
 * @param top where content rests below the header, the edge, and the backdrop's reveal distance.
 */
@Composable
fun SessionTranscriptView(
    store: StoreOf<State, Action>,
    scrollEdgeBridge: SessionScrollEdgeBridge,
    top: SessionHeaderStyle.Resolved.TranscriptTop,
    modifier: Modifier = Modifier,
    /** Server Flow: the server dropped; Working holds still. */
    holdsWorking: Boolean = false,
) {
    val context = LocalContext.current
    val host = LocalSessionComposerHost.current
    val presenter = LocalMediaPresenter.current
    val reducedMotion = rememberReduceMotion()
    val hour24 = remember(context) { DateFormat.is24HourFormat(context) }
    val cache = remember { SessionTranscriptView.ProjectionCache() }

    // iOS projects in `body`: here once per state change, off the cache, so a keystroke that
    // changes nothing the list draws reuses every list and the configuration compares equal.
    fun project(state: State) = SessionTranscriptView.projection(
        state = state,
        cache = cache,
        scrollEdgeBridge = scrollEdgeBridge,
        hour24 = hour24,
        holdsWorking = holdsWorking,
    )
    val projection by remember(store, hour24, holdsWorking) {
        store.states.map(::project).distinctUntilChanged()
    }.collectAsState(remember(store, hour24, holdsWorking) { project(store.state) })
    val latestProjection by rememberUpdatedState(projection)
    val showsMoveToBackground by store.observe { it.showsMoveToBackground }

    val theme = if (OpenCodeTheme.colors.isDark) MermaidTheme.Dark else MermaidTheme.Light
    val send: (TranscriptAction) -> Unit = remember(store, context, host, presenter, reducedMotion, theme) {
        { action -> SessionTranscriptView.handle(action, store, context, host, presenter, reducedMotion, theme) }
    }
    val slots = remember(store) {
        TranscriptSlots(
            workingLine = {
                val working = latestProjection.working
                if (working != null) {
                    WorkingLineRow(
                        activity = working,
                        showsMoveToBackground = showsMoveToBackground,
                        onMoveToBackground = { store.send(Action.MoveToBackgroundTapped) },
                    )
                }
            },
        )
    }
    val rowSlots = remember {
        TranscriptRowSlots(
            agentGlyph = { glyph, glyphModifier ->
                AgentActivityGlyph(
                    source = AgentActivitySource.Subagent(SessionTranscriptView.subagentActivity(glyph)),
                    modifier = glyphModifier,
                    size = glyph.size,
                )
            },
            providerMark = { providerID, markModifier -> ProviderIcon(providerID, markModifier) },
        )
    }
    // The reader's drag takes the list: the keyboard goes (iOS `scrollViewWillBeginDragging`
    // calls `dismissKeyboard`), once per gesture. Programmatic scrolls and flings don't count.
    // A drag borrowed from a pill over the list dismisses the keyboard too (iOS: it begins the list's drag).
    SideEffect { scrollEdgeBridge.borrowedDrag.onBegan = { host?.dismissComposerKeyboard() } }
    val dismissOnDrag = remember(host) {
        object : NestedScrollConnection {
            var dismissed = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y != 0f && !dismissed) {
                    dismissed = true
                    host?.dismissComposerKeyboard()
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                dismissed = false
                return Velocity.Zero
            }
        }
    }
    // A new screen starts without the jump button (iOS `resetShowsJumpButton`).
    DisposableEffect(host) {
        onDispose {
            host?.showsJumpButton = false
            host?.veilsJumpButton = false
            scrollEdgeBridge.reportedShowsJumpButton = false
        }
    }

    // Against a Catch Up card's own edges, not the window's ([inContainer]).
    val safeTop = WindowInsets.statusBarsIgnoringVisibility.inContainer().asPaddingValues().calculateTopPadding()
    val bottomObstruction = WindowInsets.ime.union(WindowInsets.bottomSafeArea).inContainer().asPaddingValues().calculateBottomPadding()
    // Separate from the projection, so geometry and jumps never rebuild the configuration.
    CompositionLocalProvider(LocalTranscriptRowSlots provides rowSlots) {
        TranscriptList(
            configuration = projection.configuration,
            send = send,
            modifier = modifier.fillMaxSize().nestedScroll(dismissOnDrag),
            contentPadding = AboveComposerPadding(safeTop + top.contentTop.dp, bottomObstruction, scrollEdgeBridge),
            jumpRequest = scrollEdgeBridge.jumpRequest,
            onShowsJumpButtonChanged = {
                host?.showsJumpButton = it
                scrollEdgeBridge.reportedShowsJumpButton = it
            },
            slots = slots,
            layoutTuning = TranscriptTunables.layoutSnapshot(),
            onScrolledUnderHeader = { scrollEdgeBridge.scrolled(underHeader = it, revealDistance = top.revealDistance) },
            borrowedDrag = scrollEdgeBridge.borrowedDrag,
            onHidesChanged = { host?.veilsJumpButton = it },
        )
    }

    // Starting Up and the finale end on time, and a remount never replays them.
    val timedRunEvent by store.observe { SessionTranscriptView.timedRunEvent(it) }
    val navigationID by store.observe { it.navigationID }
    LaunchedEffect(timedRunEvent) {
        val event = timedRunEvent ?: return@LaunchedEffect
        val now = AgentActivityClock.now()
        val started = scrollEdgeBridge.workingRunTime(WorkingRun.token(navigationID, event.id), now)
        val remaining = event.duration - (now - started)
        if (remaining > 0) delay((remaining * 1000).roundToLong())
        store.send(Action.WorkingRunElapsed(event.id))
    }
}

/** iOS `SessionTranscriptView`'s statics: the projection, its cache, and the Working line's activity. */
object SessionTranscriptView {
    /** What the list draws, and the Working line beside it (iOS `Configuration.working`). */
    @Immutable
    data class Projection(val configuration: TranscriptConfiguration, val working: WorkingActivity?)

    /**
     * iOS `ProjectionCache`: rebuilds each stage of the projection only when its own inputs
     * change, and patches a streaming reply's last text in place instead of regrouping the
     * transcript. Lists handed out are never mutated afterwards (a new list replaces a patched
     * one), so equal-by-reference checks downstream stay sound.
     */
    class ProjectionCache {
        private data class WindowInputs(
            val items: List<OpenCodeTranscriptItem>,
            val visibleLimit: Int,
            val browsingWindowStartID: String?,
            val revertMessageID: String?,
        )

        private data class StructuralInputs(
            val visibleItems: List<OpenCodeTranscriptItem>,
            val allItems: List<OpenCodeTranscriptItem>,
            val showsWorking: Boolean,
            val failedFinale: Boolean,
            val models: List<OpenCodeModel>,
        )

        private data class StampInputs(
            val allItems: List<OpenCodeTranscriptItem>,
            val sentNow: Boolean?,
            val clock: Long,
            val zoneID: String,
            val localeID: String,
            val hour24: Boolean?,
        )

        private data class FinalInputs(
            val structuralGeneration: Int,
            val stamps: Map<String, String>,
            val newMessages: NewMessagesMark?,
        )

        private var windowInputs: WindowInputs? = null
        private var visibleItems: List<OpenCodeTranscriptItem> = emptyList()
        private var structuralInputs: StructuralInputs? = null
        private var structuralEntries: List<TranscriptEntry> = emptyList()
        private var structuralGeneration = 0
        private var stampInputs: StampInputs? = null
        private var stamps: Map<String, String> = emptyMap()
        private var finalInputs: FinalInputs? = null
        private var entries: List<TranscriptEntry> = emptyList()
        private var newMessages: NewMessagesPlacement? = null

        fun resolveVisibleItems(
            items: List<OpenCodeTranscriptItem>,
            visibleLimit: Int,
            browsingWindowStartID: String?,
            revertMessageID: String?,
            compute: () -> List<OpenCodeTranscriptItem>,
        ): List<OpenCodeTranscriptItem> {
            val inputs = WindowInputs(items, visibleLimit, browsingWindowStartID, revertMessageID)
            val prev = windowInputs
            // A streaming reply changes only its last item: patch it rather than re-window.
            if (prev != null &&
                prev.visibleLimit == visibleLimit &&
                prev.browsingWindowStartID == browsingWindowStartID &&
                prev.revertMessageID == revertMessageID &&
                revertMessageID == null &&
                prev.items.size == items.size &&
                items.isNotEmpty() &&
                visibleItems.isNotEmpty()
            ) {
                val lastIdx = items.size - 1
                if (prev.items[lastIdx] != items[lastIdx] &&
                    prev.items[lastIdx].id == items[lastIdx].id &&
                    visibleItems.last().id == items[lastIdx].id &&
                    prev.items.subList(0, lastIdx) == items.subList(0, lastIdx)
                ) {
                    windowInputs = inputs
                    visibleItems = visibleItems.dropLast(1) + items[lastIdx]
                    return visibleItems
                }
            }
            if (inputs != windowInputs) {
                windowInputs = inputs
                visibleItems = compute()
            }
            return visibleItems
        }

        fun resolveEntries(
            visibleItems: List<OpenCodeTranscriptItem>,
            allItems: List<OpenCodeTranscriptItem>,
            showsWorking: Boolean,
            failedFinale: Boolean,
            models: List<OpenCodeModel>,
            sentNow: Boolean?,
            mark: NewMessagesMark?,
            now: Instant = Instant.now(),
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = appFormattingLocale(),
            hour24: Boolean? = null,
        ): Pair<List<TranscriptEntry>, NewMessagesPlacement?> {
            val clock = stampClock(allItems, now)
            val prevStructural = structuralInputs
            val prevStamp = stampInputs
            val prevFinal = finalInputs
            // While a reply streams, only its last text changes: replace that entry in place.
            if (showsWorking && !failedFinale &&
                prevStructural != null && prevStamp != null && prevFinal != null &&
                prevStructural.showsWorking == showsWorking &&
                prevStructural.failedFinale == failedFinale &&
                prevStructural.models == models &&
                prevStamp.sentNow == sentNow &&
                prevStamp.clock == clock &&
                prevStamp.zoneID == zone.id &&
                prevStamp.localeID == locale.toLanguageTag() &&
                prevStamp.hour24 == hour24 &&
                prevFinal.newMessages == mark &&
                visibleItems.isNotEmpty() &&
                visibleItems.size == prevStructural.visibleItems.size &&
                allItems.isNotEmpty() &&
                allItems.size == prevStructural.allItems.size &&
                structuralEntries.isNotEmpty() &&
                entries.isNotEmpty()
            ) {
                val vLast = visibleItems.size - 1
                val aLast = allItems.size - 1
                val newItem = visibleItems[vLast] as? OpenCodeTranscriptItem.AssistantMarkdown
                val oldItem = prevStructural.visibleItems[vLast] as? OpenCodeTranscriptItem.AssistantMarkdown
                val structuralLast = ((structuralEntries.last() as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.AssistantMarkdown)
                val entryLast = ((entries.last() as? TranscriptEntry.Single)?.item as? OpenCodeTranscriptItem.AssistantMarkdown)
                if (newItem != null && oldItem != null && newItem.id == oldItem.id &&
                    allItems[aLast].id == prevStructural.allItems[aLast].id &&
                    TranscriptEntry.promptID(allItems[aLast]) == null &&
                    structuralLast?.id == newItem.id &&
                    entryLast?.id == newItem.id &&
                    visibleItems.subList(0, vLast) == prevStructural.visibleItems.subList(0, vLast) &&
                    (visibleItems.size == allItems.size || allItems.subList(0, aLast) == prevStructural.allItems.subList(0, aLast))
                ) {
                    if (visibleItems[vLast] != prevStructural.visibleItems[vLast]) {
                        val updatedEntry = TranscriptEntry.Single(visibleItems[vLast])
                        structuralInputs = prevStructural.copy(visibleItems = visibleItems, allItems = allItems)
                        structuralEntries = structuralEntries.dropLast(1) + updatedEntry
                        structuralGeneration += 1
                        stampInputs = prevStamp.copy(allItems = allItems)
                        finalInputs = prevFinal.copy(structuralGeneration = structuralGeneration)
                        entries = entries.dropLast(1) + updatedEntry
                    }
                    return entries to newMessages
                }
            }
            val structural = StructuralInputs(visibleItems, allItems, showsWorking, failedFinale, models)
            if (structural != structuralInputs) {
                structuralInputs = structural
                // While the Working line plays Failed, it is the failure: the run's error line
                // takes its place once the flash has played.
                val items = if (failedFinale) withoutTurnRunError(visibleItems) else visibleItems
                structuralEntries = TranscriptEntry.build(
                    items = items,
                    isRunning = showsWorking,
                    models = models,
                    sessionStartModels = TranscriptEntry.sessionStartModelIDs(allItems),
                )
                structuralGeneration += 1
            }
            val stampKey = StampInputs(allItems, sentNow, clock, zone.id, locale.toLanguageTag(), hour24)
            if (stampKey != stampInputs) {
                stampInputs = stampKey
                // Times read from every loaded message, so the window's first one is dated
                // against the messages before it. A new session's sends count as sent now until
                // their replies carry a time.
                stamps = TranscriptTimeStamps.stamps(allItems, sentNow, now, zone, locale, hour24)
            }
            val finalKey = FinalInputs(structuralGeneration, stamps, mark)
            if (finalKey != finalInputs) {
                finalInputs = finalKey
                val built = TranscriptTimeStamps.inserting(stamps, structuralEntries)
                entries = built
                newMessages = NewMessagesMark.resolve(mark, visibleItems, built)
            }
            return entries to newMessages
        }

        /**
         * `now` as finely as the stamps tell it apart: they show minutes, and Today becomes
         * Yesterday on a minute, but a last prompt with no time of its own, or from a server clock
         * ahead of the phone's, reads as now.
         */
        private fun stampClock(items: List<OpenCodeTranscriptItem>, now: Instant): Long {
            val lastPromptID = items.asReversed().firstNotNullOfOrNull(TranscriptEntry::promptID)
            val time = lastPromptID?.let { TranscriptTimeStamps.date(it, now) }
            val readsAsNow = lastPromptID != null && (time == null || !time.isBefore(now))
            return if (readsAsNow) now.epochSecond else now.epochSecond / 60
        }
    }

    /**
     * iOS `configuration(using:at:)`: everything the list draws, from the store. [now] is
     * `AgentActivityClock` seconds (the Working line's clock); time stamps read the wall clock.
     */
    fun projection(
        state: State,
        cache: ProjectionCache,
        scrollEdgeBridge: SessionScrollEdgeBridge,
        now: Double = AgentActivityClock.now(),
        hour24: Boolean? = null,
        holdsWorking: Boolean = false,
    ): Projection {
        val visibleItems = cache.resolveVisibleItems(
            items = state.items,
            visibleLimit = state.visibleLimit,
            browsingWindowStartID = state.browsingWindowStartID,
            revertMessageID = state.session?.revertMessageID,
            compute = { state.visibleItems },
        )
        val working = workingActivity(state, visibleItems, scrollEdgeBridge, now, holdsWorking)
        // The finale holds the transcript as it was running, so Working leaves and the reply's
        // end arrives together, as they do without it.
        val showsWorking = working != null
        val isFailedFinale = working?.phase == WorkingActivity.Phase.failed
        val sentNow: Boolean? = if (state.isDraft || state.draftBecameSessionID != null) true else null
        val (entries, newMessages) = cache.resolveEntries(
            visibleItems = visibleItems,
            allItems = state.items,
            showsWorking = showsWorking,
            failedFinale = isFailedFinale,
            models = state.models,
            sentNow = sentNow,
            mark = state.newMessages,
            hour24 = hour24,
        )
        val queuedRows = if (state.queuedInputs.isEmpty()) emptyList() else state.queuedInputRows
        val actionableErrorID = state.actionableRunErrorID
        val configuration = TranscriptConfiguration(
            server = state.server,
            sessionID = state.session?.id,
            directory = state.session?.directory,
            historyGeneration = state.historyGeneration,
            entries = entries,
            hasEarlierItems = state.hasEarlierItems,
            isLoadingEarlier = state.isLoadingEarlier,
            earlierHistoryError = state.earlierHistoryError,
            isLoading = state.isTranscriptLoading,
            listsAllHistory = state.initialTranscriptIsFresh && state.earlierHistory == SessionFeature.State.EarlierHistory.Exhausted &&
                !state.hasEarlierItems && !state.isTranscriptLoading,
            showsEmptyState = state.showsEmptyState,
            isRunning = showsWorking,
            workingHandsOffToCopy = working?.handsOffToCopy == true,
            hasPendingRequests = state.hasPendingRequests,
            showsMoveToBackground = state.showsMoveToBackground,
            actionableRunErrorID = actionableErrorID,
            actionableRunErrorActions = if (actionableErrorID == null) emptyList() else state.actionableRunErrorActions,
            subagents = state.transcriptSubagents,
            sessionLinks = state.sessionLinks,
            isDraft = state.isDraft,
            // Steering messages land at the next step, so they sit in the transcript as dim
            // bubbles; queued ones wait in the pill.
            queuedInputs = if (queuedRows.isEmpty()) emptyList() else queuedRows.filter { !it.waitsInQueuedPill },
            failedSends = if (state.failedSends.isEmpty()) {
                emptyMap()
            } else {
                val moveTo = state.missingFolderMoveTarget?.name
                buildMap {
                    for (failed in state.failedSends) {
                        putIfAbsent(
                            failed.id,
                            TranscriptSendFailure(
                                reason = failed.displayReason,
                                moveTo = if (failed.isFolderGone) moveTo else null,
                                // The whole session is moving: every row whose folder is gone waits.
                                isMoving = failed.isFolderGone && state.movingFailedSendID != null,
                            ),
                        )
                    }
                }
            },
            deliveryNotes = if (state.failedSends.isEmpty()) emptyMap() else state.deliveryNotes.mapValues { it.value.label },
            queuedPillTexts = if (queuedRows.isEmpty()) {
                emptyMap()
            } else {
                queuedRows.filter { it.waitsInQueuedPill }.distinctBy { it.id }
                    .associate { it.id to (TranscriptUserMessage.from(it)?.displayText ?: it.text) }
            },
            sentItemID = state.lastSentItemID,
            sendGeneration = state.sendGeneration,
            newMessages = newMessages,
            snapshotRevision = state.perfSnapshotRevision,
        )
        return Projection(configuration, working)
    }

    /**
     * Maps what a row or the list asks for to the `SessionFeature` action of the same name (iOS
     * rows send them directly). Share, files and image previews are the screen's: Share opens
     * the system share sheet with the link; files and images end text focus first, as iOS does
     * before presenting their viewers (media's [MediaPresenter]: one fullscreen viewer for an image or file, iOS `presentImage` / `presentFile`).
     */
    fun handle(
        action: TranscriptAction,
        store: StoreOf<State, Action>,
        context: Context,
        host: SessionComposerHost?,
        presenter: MediaPresenter? = null,
        reducedMotion: Boolean = false,
        theme: MermaidTheme = MermaidTheme.Light,
    ) {
        when (action) {
            is TranscriptAction.LinkMenu -> {
                store.send(Action.LinkMenu(action.action, action.url))
                if (action.action == LinkMenuAction.Share) {
                    val url = OpenCodePairingLink.shareableURL(action.url) ?: return
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    }
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            is TranscriptAction.PreviewImage -> {
                // The keyboard would stay up over the image.
                host?.dismissComposerKeyboard()
                val state = store.state
                presenter?.presentPreview(
                    SessionVisualMedia.preview(action.preview, state.hidingUndone(state.items), state.server, state.session?.directory, theme) {
                        dependency(AgentFileChecks).shown(state.server, state.session?.directory, it)
                    },
                    drawingHandler(store),
                    flightAccessories = drawingAccessories(store, context),
                )
            }
            is TranscriptAction.OpenFile -> {
                host?.dismissComposerKeyboard()
                val addDrawing = drawingHandler(store)
                val server = store.state.server
                val directory = store.state.session?.directory
                val checks = dependency(AgentFileChecks)
                val file = checks.shown(server, directory, action.file) ?: action.file
                val target = AgentFileDestination(file, directory)
                val items = store.state.hidingUndone(store.state.items)
                val gallery = { destination: AgentFileDestination ->
                    SessionVisualMedia.preview(destination, items, server, directory, theme) { checks.shown(server, directory, it) }
                }
                val preview = gallery(target) ?: TranscriptImagePreview(target, server)
                presenter?.presentPreview(preview, addDrawing, gallery, drawingAccessories(store, context))
            }
            is TranscriptAction.OpenPastedText -> {
                host?.dismissComposerKeyboard()
                presenter?.present(sourceID = action.sourceID, isMedia = false, reducesMotion = reducedMotion, screenName = localized(R.string.session_pasted_text_viewer_screen_title)) { viewer ->
                    PastedTextViewer(
                        text = action.text,
                        summary = action.summary,
                        onCopy = { store.send(Action.CopyTextTapped(it)) },
                        onShare = { shareText(context, it) },
                        onDone = { viewer.dismiss() },
                    )
                }
            }
            else -> sessionAction(action)?.let(store::send)
        }
    }

    /** The `SessionFeature` action a [TranscriptAction] stands for; null for the screen's own. */
    fun sessionAction(action: TranscriptAction): Action? = when (action) {
        TranscriptAction.FirstContentFrameRendered -> Action.FirstContentFrameRendered
        is TranscriptAction.TranscriptBrowsingChanged -> Action.TranscriptBrowsingChanged(action.isBrowsing)
        is TranscriptAction.NewMessagesPassed -> Action.NewMessagesPassed(action.generation)
        is TranscriptAction.NewMessagesFit -> Action.NewMessagesFit(action.generation)
        // Since You Left was removed in iOS cd6be7c6; the transcript's action goes with it.
        TranscriptAction.ShowEarlierTapped -> Action.ShowEarlierTapped
        is TranscriptAction.CopyTextTapped -> Action.CopyTextTapped(action.text)
        is TranscriptAction.QuoteTextTapped -> Action.QuoteTextTapped(action.text)
        is TranscriptAction.LinkMenu -> Action.LinkMenu(action.action, action.url)
        is TranscriptAction.LinkTapped -> Action.LinkTapped(action.url)
        is TranscriptAction.SessionLinksAppeared -> Action.SessionLinksAppeared(action.ids)
        is TranscriptAction.SubagentTapped -> Action.SubagentTapped(action.sessionID)
        TranscriptAction.MoveToBackgroundTapped -> Action.MoveToBackgroundTapped
        // Move to needs the session's missing-folder move (iOS b629d780), not ported here yet.
        is TranscriptAction.FailedSendMoveTapped -> Action.FailedSendMoveTapped(action.messageID)
        is TranscriptAction.FailedSendRetryTapped -> Action.FailedSendRetryTapped(action.messageID)
        is TranscriptAction.FailedSendEditTapped -> Action.FailedSendEditTapped(action.messageID)
        is TranscriptAction.FailedSendDeleteTapped -> Action.FailedSendDeleteTapped(action.messageID)
        is TranscriptAction.RunErrorActionTapped -> Action.RunErrorActionTapped(action.action)
        is TranscriptAction.RevertToHereTapped -> Action.RevertToHereTapped(action.messageID)
        TranscriptAction.RedoTapped -> Action.RedoTapped
        is TranscriptAction.PendingInputSendNowTapped -> Action.PendingInputSendNowTapped(action.inputID)
        is TranscriptAction.PendingInputMoveToQueueTapped -> Action.PendingInputMoveToQueueTapped(action.inputID)
        is TranscriptAction.PendingInputCancelTapped -> Action.PendingInputCancelTapped(action.inputID)
        is TranscriptAction.OpenFile, is TranscriptAction.PreviewImage, is TranscriptAction.OpenPastedText -> null
    }

    /** A transcript row's subagent glyph as the activity orb reads it. */
    fun subagentActivity(glyph: AgentGlyphState) = SubagentActivity(
        sessionID = glyph.sessionID,
        phase = when (glyph.phase) {
            AgentGlyphState.Phase.Spawning -> SubagentActivity.Phase.spawning
            AgentGlyphState.Phase.Running -> SubagentActivity.Phase.running
            AgentGlyphState.Phase.Done -> SubagentActivity.Phase.done
            AgentGlyphState.Phase.Failed -> SubagentActivity.Phase.failed
        },
        needsInput = glyph.needsInput,
    )

    /** A Working-line event that ends on time: Starting Up, or a finale. */
    @Immutable
    data class TimedRunEvent(val id: Int, val duration: Double)

    fun timedRunEvent(state: State): TimedRunEvent? {
        val run = state.workingRun
        run.finale?.let { return TimedRunEvent(it.id, it.duration) }
        if (!state.isRunning) return null
        return run.startID?.let { TimedRunEvent(it, WorkingActivity.STARTUP) }
    }

    /**
     * The Working line's orb, or null when the line isn't shown: while the session runs, and for
     * its finale's flash after. [now] is `AgentActivityClock` seconds.
     */
    fun workingActivity(
        state: State,
        visibleItems: List<OpenCodeTranscriptItem>,
        scrollEdgeBridge: SessionScrollEdgeBridge,
        now: Double,
        holdsWorking: Boolean = false,
    ): WorkingActivity? {
        val run = state.workingRun
        val key = state.workingOrbKey
        fun token(id: Int) = WorkingRun.token(state.navigationID, id)
        val running = if (state.isRunning) {
            WorkingActivity(
                phase = if (state.isInterrupting) WorkingActivity.Phase.stopping else WorkingActivity.Phase.running,
                startedAt = run.startID?.let { scrollEdgeBridge.workingRunTime(token(it), now) },
                needsInput = state.hasPendingRequests,
                key = key,
                retry = WorkingActivity.retry(visibleItems),
                isHeld = holdsWorking,
            )
        } else {
            null
        }
        // The flash plays on through events that trail the run's end.
        val finale = run.finale ?: return running
        val started = scrollEdgeBridge.workingRunTime(token(finale.id), now)
        if (now - started >= finale.duration) return running
        // Waiting on the settlement read: the line stays as it was.
        if (finale.kind == WorkingRun.Finale.Kind.pending) return running ?: WorkingActivity(phase = WorkingActivity.Phase.running, key = key)
        // A run's error can land a moment after its end.
        val live = WorkingRun.outcome(visibleItems)
        if (live == WorkingRun.Outcome.interrupted) return running
        // The line ends in the reply's copy button when the turn has reply text. A failed run's
        // line fades instead: its error line arrives in its place, with the copy button below it.
        val failed = finale.kind == WorkingRun.Finale.Kind.failed || live == WorkingRun.Outcome.failed
        val replyEnds = !failed && TranscriptEntry.build(visibleItems, isRunning = false).lastOrNull() is TranscriptEntry.ReplyEnd
        return WorkingActivity(
            phase = if (failed) WorkingActivity.Phase.failed else WorkingActivity.Phase.done,
            key = key,
            finaleStartedAt = started,
            handsOffToCopy = replyEnds,
            finaleDuration = finale.finaleDuration,
            handoffDuration = finale.handoffDuration,
        )
    }

    /** The last turn's items without its run error. */
    fun withoutTurnRunError(items: List<OpenCodeTranscriptItem>): List<OpenCodeTranscriptItem> {
        val last = items.indexOfLast {
            it is OpenCodeTranscriptItem.UserText || it is OpenCodeTranscriptItem.UserImages || it is OpenCodeTranscriptItem.UserFiles
        }
        val start = if (last >= 0) last + 1 else 0
        return items.subList(0, start) + items.subList(start, items.size).filter { it !is OpenCodeTranscriptItem.RunError }
    }
}

// MARK: - Shared pieces

/** What keeps a transcript's list: its visit and session (see `transcriptIdentity`). */
@Immutable
data class TranscriptIdentity(val visit: Int, val sessionID: String?)

/**
 * The transcript's identity: the session on screen, except that a draft's transcript carries on
 * into the session it became. Drafts (and that session) are told apart by their visit
 * (`navigationID`), so one draft's transcript never carries into the next.
 */
val State.transcriptIdentity: TranscriptIdentity
    get() {
        val continues = session != null && session.id == draftBecameSessionID
        if (session != null && !continues) return TranscriptIdentity(visit = 0, sessionID = session.id)
        return TranscriptIdentity(visit = navigationID, sessionID = null)
    }

/**
 * Decodes image bytes once and caches the result (iOS `CachedImage`); decoding in a list row
 * would otherwise run on every recomposition. Fills its bounds, cropping. A `media:<hash>`
 * reference loads from [server]; the view moved down to `:feature:media`, which the composer's
 * queued messages share.
 */
@Composable
fun CachedImage(
    id: String,
    data: ByteArray,
    modifier: Modifier = Modifier,
    mediaHash: String? = null,
    name: String? = null,
    isUnavailable: Boolean = false,
    server: ai.opencode.server.OpenCodeServer? = null,
    compact: Boolean = false,
) = ai.opencode.feature.media.CachedImage(id, data, modifier, mediaHash, name, isUnavailable, server, compact)

object CachedImage {
    /** iOS `CachedImage.decoded(id:data:mediaHash:)`, moved down to `:feature:media` for its resolver. */
    fun decoded(id: String, data: ByteArray, mediaHash: String? = null): ImageBitmap? =
        ai.opencode.feature.media.CachedImage.decoded(id, data, mediaHash)?.asImageBitmap()
}

/**
 * The header's title: a new title replaces the old one word-softly (the old drifts up and blurs
 * out, the new rises in), breathing while the session syncs or the server writes a title.
 *
 * @param isLastKnown the last refresh failed: the title is secondary until it syncs again.
 * @param animates false on the frame a session switched in place first shows: it swaps at once.
 */
@Composable
fun SyncingSessionTitle(
    title: String,
    isSyncing: Boolean,
    isLastKnown: Boolean,
    modifier: Modifier = Modifier,
    animates: Boolean = true,
    style: TextStyle = HeaderBlurTunables.titleFont.textStyle.copy(fontWeight = FontWeight.Bold),
) {
    val reducedMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val colors = OpenCodeTheme.colors
    val color = if (isLastKnown && !isSyncing) colors.secondaryText else colors.text
    AnimatedContent(
        targetState = title,
        modifier = modifier.titleBreathing(isSyncing),
        transitionSpec = {
            // iOS `.titleRoll` timed by `Motion.swap`; a title swapped in place without animation doesn't move.
            if (!animates) (fadeIn(snap()) togetherWith fadeOut(snap())) using null
            else MotionTransition.roll(MotionRollDirection.Up, reducedMotion, density, Motion.swap).content
        },
        label = "syncingSessionTitle",
    ) { shown ->
        Text(
            shown,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


/**
 * The session's run status: the status orb, then “Working” (or “Needs your input”) as still
 * text, since the orb carries the motion. While the server waits to retry, the text counts down
 * once a second.
 */
@Composable
fun WorkingIndicator(activity: WorkingActivity, modifier: Modifier = Modifier) {
    val fontScale = LocalDensity.current.fontScale
    // The subagent cards' orb, scaled with the body text it leads.
    val orbSize = (18 * fontScale).dp
    val spacing = (8 * fontScale).dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        AgentActivityGlyph(
            source = AgentActivitySource.Session(activity),
            size = orbSize,
            isPaused = activity.phase == WorkingActivity.Phase.stopping || activity.isHeld,
            modifier = Modifier.clearAndSetSemantics {},
        )
        val style = TranscriptFonts.metaFont(FontWeight.Medium)
        if (activity.retry != null && activity.phase == WorkingActivity.Phase.running && !activity.needsInput) {
            // The server's retry counts down once a second.
            var now by remember { mutableStateOf(Instant.now()) }
            LaunchedEffect(activity.retry) {
                while (true) {
                    now = Instant.now()
                    delay(1000)
                }
            }
            Text(activity.title(now), style = style.copy(fontFeatureSettings = "tnum"), color = OpenCodeTheme.colors.secondaryText)
        } else {
            Text(activity.title, style = style, color = OpenCodeTheme.colors.secondaryText)
        }
    }
}

/**
 * The transcript's Working line. It lays out as the reply's end does (the copy glyph's slot
 * between the reply end's insets), so the two rows are one height and the handoff moves nothing:
 * after the Done or Failed flash the indicator blurs and shrinks into the copy glyph's centre
 * while the glyph sharpens in where the reply's copy button then takes over.
 *
 * Once a shell has blocked the run for three seconds, the line's trailing end offers
 * Move to Background when [showsMoveToBackground] (`MoveToBackground.showsManualAction`, off).
 */
@Composable
fun WorkingLineRow(
    activity: WorkingActivity,
    modifier: Modifier = Modifier,
    showsMoveToBackground: Boolean = false,
    onMoveToBackground: () -> Unit = {},
) {
    val reduceMotion = rememberReduceMotion()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // In the list its timeline follows the list's gate (iOS `transcriptTimelineGate`).
    val active = lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !LocalTranscriptTimelinePaused.current
    val offersMove = activity.finaleStartedAt == null && !activity.needsInput && showsMoveToBackground
    // One clock throughout, so the indicator keeps its identity (and its orb its pose) when the
    // finale starts it. It ticks only during the finale, while the app is active; read in the
    // draw phase so frames neither recompose nor invalidate layout/semantics bounds.
    val now = remember { mutableDoubleStateOf(AgentActivityClock.now()) }
    LaunchedEffect(activity.finaleStartedAt, active) {
        now.doubleValue = AgentActivityClock.now()
        if (activity.finaleStartedAt == null || !active) return@LaunchedEffect
        while (true) {
            withFrameNanos { now.doubleValue = AgentActivityClock.now() }
            if (activity.handoff(now.doubleValue) >= 1) break
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = TranscriptMetrics.flowHalf, bottom = TranscriptMetrics.turnHalf),
    ) {
        WorkingLine(activity, { now.doubleValue }, reduceMotion, offersMove, onMoveToBackground)
        // An overlay, so offering it never changes the row's height.
        AnimatedVisibility(
            visible = offersMove,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = if (reduceMotion) fadeIn(Motion.microIn.animation(MotionPhase.Enter, reduceMotion = true))
            else fadeIn(Motion.microIn.animation(MotionPhase.Enter)) + slideInHorizontally(Motion.microIn.animation(MotionPhase.Enter)) { 8 },
            exit = if (reduceMotion) fadeOut(Motion.microIn.animation(MotionPhase.Exit, reduceMotion = true))
            else fadeOut(Motion.microIn.animation(MotionPhase.Exit)) + slideOutHorizontally(Motion.microIn.animation(MotionPhase.Exit)) { 8 },
        ) {
            MoveToBackgroundAction(activity, onMoveToBackground)
        }
    }
}

/** SwiftUI `.snappy(duration: 0.35)`. */

/** The copy glyph's centre from the text edge (its slot starts [ReplyCopyGlyph.leading] out). */
private val COPY_CENTRE = ReplyCopyGlyph.slot / 2 + ReplyCopyGlyph.leading

@Composable
private fun WorkingLine(
    activity: WorkingActivity,
    now: () -> Double,
    reduceMotion: Boolean,
    offersMove: Boolean,
    onMove: () -> Unit,
) {
    val copyLayer = rememberGraphicsLayer()
    val indicatorLayer = rememberGraphicsLayer()
    val moveToBackgroundLabel = stringResource(R.string.session_move_to_background)
    Box {
        ReplyCopyGlyph.Glyph(
            Modifier
                .offset(x = ReplyCopyGlyph.leading)
                .drawWithContent {
                    if (activity.finaleStartedAt == null) return@drawWithContent
                    val pose = WorkingHandoffPose.of(activity.handoff(now()), activity.handsOffToCopy, reduceMotion)
                    val alpha = pose.copyOpacity.toFloat()
                    if (alpha <= 0.001f) return@drawWithContent
                    val scale = pose.copyScale.toFloat()
                    copyLayer.alpha = alpha
                    copyLayer.scaleX = scale
                    copyLayer.scaleY = scale
                    copyLayer.renderEffect = transitionBlurEffect(pose.copyBlur.toFloat() * density)
                    copyLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(copyLayer)
                },
        )
        // Shrinks about the copy glyph's centre, 9 dp in, where the orb sits. Drawn through a
        // GraphicsLayer rather than Modifier.offset + Modifier.graphicsLayer so the finale never
        // mutates layout bounds or fires per-frame subtree accessibility events.
        WorkingIndicator(
            activity,
            Modifier
                .align(Alignment.CenterStart)
                .drawWithContent {
                    if (activity.finaleStartedAt == null) {
                        drawContent()
                    } else {
                        val pose = WorkingHandoffPose.of(activity.handoff(now()), activity.handsOffToCopy, reduceMotion)
                        val alpha = pose.indicatorOpacity.toFloat()
                        if (alpha > 0.001f) {
                            val scale = pose.indicatorScale.toFloat()
                            indicatorLayer.alpha = alpha
                            indicatorLayer.scaleX = scale
                            indicatorLayer.scaleY = scale
                            indicatorLayer.pivotOffset = Offset(0f, size.height / 2f)
                            indicatorLayer.translationX = (COPY_CENTRE * (1 - scale)).toPx()
                            indicatorLayer.renderEffect = transitionBlurEffect(pose.indicatorBlur.toFloat() * density)
                            indicatorLayer.record { this@drawWithContent.drawContent() }
                            drawLayer(indicatorLayer)
                        }
                    }
                }
                .semantics(mergeDescendants = true) {
                    if (offersMove) customActions = listOf(CustomAccessibilityAction(moveToBackgroundLabel) { onMove(); true })
                },
        )
    }
}

/** A quiet text action at the Used N rows' size. It gives way to its glyph alone before it would reach Working. */
@Composable
private fun MoveToBackgroundAction(activity: WorkingActivity, onMove: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val measurer = rememberTextMeasurer()
    val workingStyle = TranscriptFonts.metaFont(FontWeight.Medium)
    val labelStyle = TranscriptFonts.metaFont()
    val iconSize = 16.dp
    val moveToBackgroundLabel = stringResource(R.string.session_move_to_background)
    val moveToBackgroundHint = stringResource(R.string.session_move_to_background_hint)
    BoxWithConstraints {
        val density = LocalDensity.current
        // Working's footprint (orb slot + its text) and the 12 dp before the action, without a
        // second animating orb; the title shows only where the whole label fits beside it
        // (iOS `ViewThatFits`).
        val workingText = stringResource(if (activity.needsInput) R.string.session_status_needs_input else R.string.session_status_working)
        val working = remember(workingText, workingStyle, density) {
            measurer.measure(workingText, workingStyle).size.width
        }
        val title = remember(labelStyle, moveToBackgroundLabel, density) { measurer.measure(moveToBackgroundLabel, labelStyle, maxLines = 1).size.width }
        val showsTitle = with(density) {
            val reserved = (26 * fontScale).dp + working.toDp() + 12.dp
            val label = iconSize + 6.dp + title.toDp()
            maxWidth - reserved >= label
        }
        Row(
            Modifier
                .widthIn(min = 48.dp)
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    // iOS `accessibilityHint`: TalkBack's click label.
                    onClickLabel = moveToBackgroundHint,
                    onClick = onMove,
                )
                .semantics { contentDescription = moveToBackgroundLabel }
                .testTag("move-to-background"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            OpenCodeIcon(SFSymbol.TrayAndArrowDown, contentDescription = null, size = iconSize, tint = OpenCodeTheme.colors.secondaryText)
            if (showsTitle) {
                Text(
                    moveToBackgroundLabel,
                    style = labelStyle,
                    color = OpenCodeTheme.colors.secondaryText,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Reads the composer's height while the list measures, after the composer has measured in the same frame. */
private class AboveComposerPadding(
    private val top: Dp,
    private val bottomObstruction: Dp,
    private val bridge: SessionScrollEdgeBridge,
) : PaddingValues {
    override fun calculateTopPadding(): Dp = top
    override fun calculateBottomPadding(): Dp = bottomObstruction + bridge.composerHeight.dp
    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp = 0.dp
    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp = 0.dp
}
