package ai.opencode.feature.session

import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.feature.composer.modelpicker.ModelDisplayNames
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.DraftState
import ai.opencode.core.dependencies.BackgroundTaskClient
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.scope
import ai.opencode.feature.media.AgentFileChecks
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.composer.ComposerAttachmentsFeature
import ai.opencode.feature.composer.ComposerAutocompleteAction
import ai.opencode.feature.composer.ComposerAutocompleteState
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.core.files.ComposerStagedFile
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DictationFeature
import ai.opencode.core.experiments.key
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkProbe
import ai.opencode.server.model.ModelVisibility
import ai.opencode.feature.transcript.list.NewMessagesMark
import ai.opencode.feature.media.OpenURLClient
import ai.opencode.feature.media.PageReaderClient
import ai.opencode.feature.system.SceneWindowClient
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.feature.system.notifications.SessionNotificationsClient
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.composer.SharedLocation
import ai.opencode.feature.composer.LocationClientError
import ai.opencode.feature.media.UniversalLinkClient
import ai.opencode.feature.voice.LiveSocketWarmerClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.media.MediaReferenceClient
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerIDClient
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeContextUsage
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeRunError
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import ai.opencode.server.retry.NetworkPathClient
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import ai.opencode.feature.transcript.list.NewMessagesFold
import ai.opencode.feature.transcript.list.NewMessagesEarlierBaseline

/**
 * The Working line's manual Move to Background action (and its TalkBack action). Off for now:
 * a steer moves blocking work on its own (Move to Background When Steering), so offering it too
 * adds nothing.
 */
object MoveToBackground {
    const val showsManualAction = false
}

/**
 * One session screen (or a new session's draft): its transcript, composer, run, requests, call and
 * sheets. This file keeps [State], [Action], dependencies and `reduce`; composition is in
 * `SessionFeatureComposition.kt`, each area's reducer in `SessionFeature<Area>.kt` (`reduceX`
 * returning null passes the action on), and pure state changes in `SessionFeatureState*.kt`. Writes
 * to [State.draft] go through [withDraft], which drops [State.nonSendNewlineDraft] on an edit.
 */
class SessionFeature : Reducer<SessionFeature.State, SessionFeature.Action> {

    /**
     * Build one with [State.session] or [State.draft]. `@Shared` properties hold a [Shared]
     * reference (equal by key identity); read them with `.value`.
     */
    data class State(
        val server: OpenCodeServer,
        /** Home assigns a fresh value per opened detail, so a swap in place cancels the old session's effects. */
        val navigationID: Int = 0,
        /** Home assigns a fresh value per screen move (`detailGeneration`). */
        val visit: Int = 0,
        val isVisible: Boolean = false,
        /** What was on screen when the reader last left, until the read after their return settles it. */
        val newMessagesBaseline: Set<String>? = null,
        /**
         * Server-side viewed timestamp (ms) when an unread session opens without a phone-local seen
         * snapshot, until the read after opening settles it.
         */
        val newMessagesBaselineTimestamp: Long? = null,
        /** Whether the session opened unread, so an empty baseline marks the unread turn when no item IDs or timestamps match. */
        val newMessagesUnread: Boolean = false,
        /**
         * Unseen baseline retained while the mark sits at the top of a paged window (`earlierHistory
         * != Exhausted`), so loading an earlier page moves the divider to the true first unseen row.
         */
        val newMessagesEarlierBaseline: NewMessagesEarlierBaseline? = null,
        /** Rows that streamed in while the reader watched, before that read settled: seen, though rows the read brings above them may not be. */
        val newMessagesWatched: Set<String> = emptySet(),
        /** What a Catch Up card's reader had seen as it opened: kept unread, it's what they've seen next time too. */
        val catchUpOpeningSeen: Set<String>? = null,
        /** The New divider: above the first row that arrived while they were away. */
        val newMessages: NewMessagesMark? = null,
        val newMessagesGeneration: Int = 0,
        /** The last row of a mark that fit on screen, seen once the reader is in front of it. */
        val newMessagesFitThrough: String? = null,
        /** The last span of new rows found folded into a group already on screen, so deltas that add no row don't rebuild its turn to find that again. */
        val newMessagesFolded: NewMessagesFold? = null,
        /** False for a catch-up card: the reader's swipe decides whether it was read. */
        val readsOnView: Boolean = true,
        /** Whether a pending-request snapshot has arrived for this screen yet. */
        val hasLoadedPendingRequests: Boolean = false,
        val viewedIdle: Map<String, Long> = emptyMap(),
        /** Null while drafting a new session; set once the first message creates it. */
        val session: OpenCodeSession? = null,
        val draftProject: OpenCodeProject? = null,
        val projects: List<OpenCodeProject> = emptyList(),
        /** A new computer's first session offers these until composing retires them. */
        val starterPrompts: List<String> = emptyList(),
        val items: List<OpenCodeTranscriptItem> = emptyList(),
        val isLoading: Boolean = false,
        val initialTranscriptRequestIsExternal: Boolean = false,
        val initialTranscriptIsFresh: Boolean = false,
        /** `PerfTimeline`'s revision of the last REST window applied. Always null unless measuring. */
        val perfSnapshotRevision: Int? = null,
        val hasRenderedFirstContentFrame: Boolean = false,
        val nextRefreshID: Int = 0,
        /** The authoritative read in flight. */
        val refresh: Refresh? = null,
        val streamIsHealthy: Boolean = false,
        /** The cadence of the running pending-work poll; null while it is stopped. */
        val pendingWorkPollSeconds: Int? = null,
        val watchdogSchedule: WatchdogSchedule? = null,
        val pausedWatchdogDeadline: Duration? = null,
        val watchdogGeneration: Int = 0,
        val streamSupportsProjection: Boolean = false,
        /** Raw live text, retained separately from rendered markdown so batches append exactly once. */
        val liveText: Map<String, String> = emptyMap(),
        val projectedMessageIDs: Set<String> = emptySet(),
        val lastProjectedMessageID: String? = null,
        val resync: ResyncState? = null,
        val refreshFailed: Boolean = false,
        val loadError: String? = null,
        val visibleLimit: Int = SessionFeature.baseVisibleLimit,
        /** The leading edge of the window when the reader leaves the live tail. */
        val browsingWindowStartID: String? = null,
        val earlierHistory: EarlierHistory = EarlierHistory.UnknownEdge,
        val earlierHistoryError: String? = null,
        /** An automatic page failed offline; page again quietly once the connection is back. */
        val earlierHistoryRetriesOnReconnect: Boolean = false,
        val historyGeneration: Int = 0,
        /** Invalidates cursor reads independently of the transcript visit. */
        val earlierHistoryGeneration: Int = 0,
        /** The leading row of the last cursor page. */
        val earlierHistoryInsertionID: String? = null,
        /** The optimistic row most recently appended by an explicit send. */
        val lastSentItemID: String? = null,
        val sendGeneration: Int = 0,
        /** Write through [withDraft]. */
        val draft: String = "",
        /** The draft produced by `⇧↩` or a single-newline paste (`nonSendNewlineInserted`). */
        val nonSendNewlineDraft: String? = null,
        val autocomplete: ComposerAutocompleteState = ComposerAutocompleteState(),
        val isAwaitingReply: Boolean = false,
        val isInterrupting: Boolean = false,
        /** Names the run-state work in flight; superseded by [supersedeRunWork]. */
        val runWorkGeneration: Int = 0,
        val stopAttempt: StopAttempt? = null,
        /** In the background, Stop's request carries on under a background task, but reads wait for the return. */
        val isBackgrounded: Boolean = false,
        /** The Working line's run, for its orb and finale; purely visual. */
        val workingRun: WorkingRun = WorkingRun(),
        val dictation: DictationFeature.State = DictationFeature.State(),
        /** The scene is active. */
        val isSceneActive: Boolean = true,
        /** Home swapped this detail in place under a live call or recording. */
        val inheritsLiveWork: Boolean = false,
        /** The server is running this session (any client or agent). */
        val isExternallyActive: Boolean = false,
        /** Admitted-but-unpromoted work. */
        val queuedInputs: List<OpenCodePendingInput> = emptyList(),
        /** Placement for prompts first seen on this screen. */
        val pendingPromptPositions: Map<String, PendingPromptPosition> = emptyMap(),
        /** Withdrawn here; hidden from inbox reads that started before the cancel. */
        val cancelledInputIDs: Set<String> = emptySet(),
        /** The delivery each mid-run send's POST carried, until it's admitted. */
        val sentDeliveries: Map<String, OpenCodeDelivery> = emptyMap(),
        /** Deliveries changed here that the server hasn't confirmed yet. */
        val deliveryOverrides: Map<String, OpenCodeDelivery> = emptyMap(),
        /** Sends the server never took, in the order they failed. */
        val failedSends: List<FailedSend> = emptyList(),
        /** The failed send whose Move to is on its way; Move to and Retry wait. */
        val movingFailedSendID: String? = null,
        /** Prompt IDs whose client-rendered parts have not appeared in an authoritative window yet. */
        val optimisticPromptIDs: Set<String> = emptySet(),
        /** Durable per-message echoes waiting for a page that includes them. */
        val echoedPromptIDs: Set<String> = emptySet(),
        /** Notices placed in [items] from the inbox and not yet seen in history. */
        val pendingNoticeIDs: Set<String> = emptySet(),
        /** Prompts sent from this screen that have not reached the transcript. */
        val promptStates: Map<String, PromptState> = emptyMap(),
        val nextPromptRequestEpoch: Int = 0,
        /** A draft's outbox lane, named by its first send. */
        val outboxDraftID: String? = null,
        /** The empty session a call started on this draft is creating (a client-minted ID). */
        val callSessionID: String? = null,
        /** The session this screen's draft became. */
        val draftBecameSessionID: String? = null,
        /** Short tombstone window rejecting inbox responses that raced a promotion. */
        val promotedPromptIDs: List<String> = emptyList(),
        /** Local-only image presentation keyed by the authoritative prompt ID. */
        val queuedInputImages: Map<String, List<OpenCodeImageAttachment>> = emptyMap(),
        val models: List<OpenCodeModel> = emptyList(),
        val isLoadingModels: Boolean = false,
        val modelsLoadError: String? = null,
        val modelFilter: String = "",
        /** Show All in the picker (Hide Unused Models). */
        val showsAllModels: Boolean = false,
        /** The composer's model. */
        val currentModel: OpenCodeModelRef? = null,
        /** A model picked in this session that the server may not have yet. */
        val pendingModel: OpenCodeModelRef? = null,
        /** Picks still waiting in this family's other sessions. */
        val familyPendingModels: Map<String, OpenCodeModelRef> = emptyMap(),
        /** The pick each queued send was sent with. */
        val queuedPicks: Map<String, OpenCodeModelRef> = emptyMap(),
        /** A queued send's pick whose message just started (`applyDueModelPick`). */
        val queuedPickDue: OpenCodeModelRef? = null,
        val agentSelection: AgentSelectionState = AgentSelectionState(),
        val projectFilter: String = "",
        val composerAttachments: ComposerAttachmentsFeature.State = ComposerAttachmentsFeature.State(),
        /** Bumped when something asks for the keyboard in this composer. */
        val composerFocusRequest: Int = 0,
        /** A Send Later share the composer took while empty. */
        val sharedSend: SharedComposerDraft.Send? = null,
        /** The IDs the shared send goes under. */
        val sharedMessageID: String? = null,
        val sharedSessionID: String? = null,
        val requestDock: RequestDockFeature.State<DraftState> = RequestDockFeature.State(),
        val familySessions: List<OpenCodeSession> = emptyList(),
        val familyReadAt: Instant? = null,
        /** The family read in flight, and whether another waits behind it. */
        val familyRead: CoalescedRead = CoalescedRead(),
        val familyRootID: String? = null,
        val activeFamilySessionIDs: Set<String> = emptySet(),
        /** Runs that started or ended since the latest family read began. */
        val familyRunChanges: Map<String, Boolean> = emptyMap(),
        val familyTranscriptCache: Map<String, List<OpenCodeTranscriptItem>> = emptyMap(),
        val catchUpFamilyDrafts: Map<String, DraftState> = emptyMap(),
        /** Mutually exclusive sheets. */
        val activePicker: ActivePicker? = null,
        /** Edit's undo is on its way (`stageRevert`). */
        val isUndoingLastMessage: Boolean = false,
        /** A voice call that starts on this session and can retarget across sessions (`@Presents`). */
        val voice: VoiceFeature.State? = null,
        /** A subagent pushed over its parent: its calls start on the root's session. */
        val callsFromRoot: Boolean = false,
        /** A pushed subagent covers this screen. */
        val isCovered: Boolean = false,
        /** Something sits over the transcript (a pushed subagent, its browser, Settings): what arrives isn't in front of the reader. */
        val isTranscriptCovered: Boolean = false,
        val isPollingPaused: Boolean = false,
        /** The in-app browser over this session (Experimental). */
        val browser: BrowserFeature.State = BrowserFeature.State(),
        /** A link tap waiting on the system to say whether an app claims it. */
        val linkProbe: LinkProbe? = null,
        /** What the session IDs linked in this transcript name. */
        val sessionLinks: Map<String, SessionLinkTarget> = emptyMap(),
        val favoriteModelIDs: Shared<Set<String>> = shared(SharedKeys.favoriteModelIDs),
        val modelVisibilityByServer: Shared<Map<String, ModelVisibility>> = shared(ModelVisibility.modelVisibilityByServer),
        val hideModelsExperiment: Shared<Boolean> = shared(ExperimentalFeature.hideModels.key),
        val recentModelIDs: Shared<List<String>> = shared(SharedKeys.recentModelIDs),
        val modelVariants: Shared<Map<String, String>> = shared(SharedKeys.modelVariants),
        val modelDisplayNamesByServer: Shared<Map<String, ModelDisplayNames>> = shared(ModelDisplayNames.modelDisplayNamesByServer),
        val voiceModeEnabled: Shared<Boolean> = shared(SharedKeys.voiceModeEnabled),
        val openAIAPIKey: Shared<String?> = shared(SharedKeys.openAIAPIKey),
        val xaiAPIKey: Shared<String?> = shared(SharedKeys.xaiAPIKey),
        val voiceEngine: Shared<VoiceEngine> = shared(VoiceSharedKeys.voiceEngine),
        val serverVoiceProvider: Shared<ServerVoiceProvider> = shared(VoiceSharedKeys.serverVoiceProvider),
        val voiceServer: Shared<VoiceServerChoice> = shared(VoiceSharedKeys.voiceServerChoice),
        val serverVoiceSupportByServer: Shared<Map<String, ServerVoiceSupport>> = shared(VoiceSharedKeys.serverVoiceSupportByServer),
        val voiceModelsByServer: Shared<Map<String, ServerVoiceModelsAnswer>> = shared(VoiceSharedKeys.voiceModelsByServer),
        val uploadUnsupportedServerIDs: Shared<Set<String>> = shared(SharedKeys.uploadUnsupportedServerIDs),
        val returnKeySends: Shared<Boolean> = shared(SharedKeys.returnKeySends),
        /**
         * Plain optional, not `@Presents`: the Session sheet's state outlives the sheet so the
         * header's `…` menu can run its actions without opening it, and in-flight fork/export work
         * survives dismissal.
         */
        val sessionSheet: SessionSheetFeature.State? = null,
        /** Session Media's page over the session, on its filter; null while it's closed. */
        val sessionMediaFilter: SessionMedia.Filter? = null,
        /** Media as tiles or a list; kept while the screen lives, across the viewer, tabs and reopening. */
        val sessionMediaLayout: SessionMedia.Layout = SessionMedia.Layout.tiles,
        /** Session Media's Load Earlier while it pages for its tab; null otherwise. */
        val sessionMediaEarlier: SessionMedia.EarlierSearch? = null,
        /** The last Load Earlier ended with nothing new for its tab, and more history remains. */
        val sessionMediaNothingNew: Boolean = false,
        /** The server's folders, browsed from the Project sheet (`@Presents`). */
        val folderBrowser: FolderBrowserFeature.State? = null,
        /** What the latest reply filled; null when unknown or nothing fills it. */
        val contextUsage: OpenCodeContextUsage? = null,
        /** A step ended since the last context read, so the next refresh reads it. */
        val contextNeedsRead: Boolean = false,
        /** Running shells of the selected session. */
        val shells: List<OpenCodeShell> = emptyList(),
        /** Running background jobs in the session's tree, by ID. */
        val jobs: Map<String, OpenCodeJob> = emptyMap(),
        /** Jobs that started (the job) or settled (null) since the latest jobs read began. */
        val jobChangesDuringRefresh: Map<String, OpenCodeJob?> = emptyMap(),
        /** Background rows whose Stop is on its way: script job IDs and subagent session IDs. */
        val stoppingIDs: Set<String> = emptySet(),
        /** Restored live arrivals predate the next list and must be removable by it. */
        val restoredShellIDs: Set<String> = emptySet(),
        /** An exit can beat the list response that still reports the shell. */
        val endedShellIDs: Set<String> = emptySet(),
        /** Status of the latest tool call for each shell ID in [items]. */
        val shellToolStatuses: Map<String, OpenCodeToolCall.Status> = emptyMap(),
        /** Session IDs referenced by `subagent` or `task` tool calls in [items]. */
        val subagentToolSessionIDs: Set<String> = emptySet(),
        /** The live output sheet for one shell (`@Presents`). */
        val shellOutput: ShellOutputFeature.State? = null,
        /** Whether the Working line offers Move to Background at all. */
        val offersManualMoveToBackground: Boolean = MoveToBackground.showsManualAction,
        /** The foreground work that has blocked the run long enough to offer moving it. */
        val movableWorkKey: String? = null,
        /** Work already moved (or being moved) from here; never offered again. */
        val movedWorkKeys: Set<String> = emptySet(),
        /** Work a steering send has claimed to move before it's posted, by the send's message ID. */
        val autoBackgroundClaims: Map<String, String> = emptyMap(),
    ) {
        val id: Int get() = navigationID

        /** The authoritative read in flight. Its unique ID rejects the response of any read abandoned before it. */
        data class Refresh(
            val id: Int,
            val reason: RefreshReason,
            /** A newer request that may have raced this read earns one follow-up. */
            val followup: RefreshReason? = null,
            /** Live events that raced this read, replayed onto its result. */
            val racedEvents: List<OpenCodeLiveContentEvent> = emptyList(),
            /** A model was applied or switched while it was on its way. */
            val modelChanged: Boolean = false,
            /** A revert was staged, redone, or committed while it was on its way. */
            val revertChanged: Boolean = false,
            /** The session moved folders while this read was in flight. */
            val directoryChanged: Boolean = false,
        )

        // region Nested types

        /** The title's syncing cue while a page read runs. */
        data class ResyncState(
            /** The read the cue waits on. */
            val refreshID: Int,
            val isShown: Boolean = false,
            val responseReceived: Boolean = false,
            val minimumElapsed: Boolean = false,
        )

        sealed interface EarlierHistory {
            /** The newest authoritative window has not started a cursor chain yet. */
            data object UnknownEdge : EarlierHistory
            /** Pages are walking backward in search of an item already loaded. */
            data class SeekingOverlap(val cursor: String?) : EarlierHistory
            /** An overlap was found, so subsequent pages are known to be older. */
            data class Paging(val cursor: String?) : EarlierHistory
            data class Loading(val cursor: String?, val reachedLoadedItems: Boolean) : EarlierHistory
            data object Exhausted : EarlierHistory
        }

        /** The Stop in flight. */
        data class StopAttempt(
            val steering: Set<String>,
            val acknowledged: Boolean = false,
        )

        data class PendingPromptPosition(
            val precedingPromptIDs: Set<String>,
            val precedingMessageIDs: Set<String>,
            val requestEpoch: Int,
            val isBeforeWindow: Boolean = false,
        )

        data class PromptState(
            val requestEpoch: Int,
            val admission: Admission = Admission.sent,
            /** False for voice admissions: their row is local, their run is not owned by the composer. */
            val ownsRun: Boolean = true,
        ) {
            enum class Admission { sent, admitted }
        }

        /** The reply to what was last sent from the open page: its text, or null while the agent is still working on it. */
        data class BrowserPeek(val text: String?)

        enum class ActivePicker(val rawValue: String) {
            model("model"), project("project"), requests("requests"), session("session");

            val id: String get() = rawValue
        }

        data class TaskIdentity(val serverID: String, val visit: Int, val sessionID: String?)

        /** A running shell tool the current run is waiting on. */
        data class ForegroundWork(val key: String, val startedAt: Instant?)

        // endregion

        companion object {
            /** An existing session. */
            fun session(
                server: OpenCodeServer,
                session: OpenCodeSession,
                projects: List<OpenCodeProject> = emptyList(),
                cachedItems: List<OpenCodeTranscriptItem>? = null,
                initialTranscriptRequestIsExternal: Boolean = false,
                isRunning: Boolean = false,
                navigationID: Int = 0,
                visit: Int? = null,
            ): State {
                val initialItems = cachedItems ?: emptyList()
                return State(
                    server = server,
                    navigationID = navigationID,
                    visit = visit ?: navigationID,
                    session = session,
                    // Every screen knows its projects, so a session whose folder is gone can offer a move.
                    projects = projects,
                    familySessions = listOf(session),
                    familyRootID = session.id,
                    activeFamilySessionIDs = if (isRunning) setOf(session.id) else emptySet(),
                    items = initialItems,
                    shellToolStatuses = shellToolStatuses(initialItems),
                    subagentToolSessionIDs = subagentToolSessionIDs(initialItems),
                    isLoading = cachedItems == null,
                    initialTranscriptRequestIsExternal = initialTranscriptRequestIsExternal,
                    currentModel = session.model,
                    isExternallyActive = isRunning,
                )
            }

            /** A new session's draft. */
            fun draft(
                server: OpenCodeServer,
                draftProject: OpenCodeProject?,
                projects: List<OpenCodeProject>,
                navigationID: Int = 0,
                visit: Int? = null,
            ): State = State(
                server = server,
                navigationID = navigationID,
                visit = visit ?: navigationID,
                session = null,
                draftProject = draftProject,
                projects = projects,
                items = emptyList(),
                isLoading = false,
                // A draft has no history to page; the flag stays true once created.
                earlierHistory = EarlierHistory.Exhausted,
                // The model comes with the catalog and the agents (`draftDefaultModel`).
            )

            internal fun shellToolStatuses(items: List<OpenCodeTranscriptItem>): Map<String, OpenCodeToolCall.Status> {
                val statuses = HashMap<String, OpenCodeToolCall.Status>()
                for (item in items) {
                    if (item !is OpenCodeTranscriptItem.Tool) continue
                    val shellID = item.call.metadata["shellID"]?.string ?: continue
                    statuses[shellID] = item.call.status
                }
                return statuses
            }

            internal fun subagentToolSessionIDs(items: List<OpenCodeTranscriptItem>): Set<String> {
                val ids = HashSet<String>()
                for (item in items) {
                    if (item !is OpenCodeTranscriptItem.Tool) continue
                    val call = item.call
                    if (call.name != "subagent" && call.name != "task") continue
                    val sessionID = call.metadata["sessionID"]?.string
                    if (!sessionID.isNullOrEmpty()) ids += sessionID
                }
                return ids
            }

            /** Seconds of grace after dictation ends before Raise to Talk may start a call. */
            val raiseToTalkGrace: Double = DictationFeature.State.RAISE_TO_TALK_GRACE.toMillis() / 1000.0

            const val recentProjectLimit = 5
        }
    }

    /**
     * Bindings: see [BindingAction] (`SessionFeatureBindings.kt`). Convenience constructors for
     * child actions live in the companion.
     */
    sealed interface Action {
        data class Binding(val action: BindingAction) : Action
        data object Task : Action
        data object FirstContentFrameRendered : Action
        data class PreviewStateReplaced(val state: State) : Action
        /** The Working line drew a run's Starting Up or finale for as long as it lasts. */
        data class WorkingRunElapsed(val id: Int) : Action
        data class VisibilityChanged(val isVisible: Boolean) : Action
        data class ResyncDelayElapsed(val refreshID: Int) : Action
        data object ResyncMinimumElapsed : Action
        data class ViewAcknowledged(val sessionID: String, val idle: Long) : Action
        data class RefreshRequested(val reason: RefreshReason) : Action
        data class WatchdogElapsed(val generation: Int) : Action
        data class RefreshResponse(val sessionID: String, val requestID: Int, val result: Result<AuthoritativeRefresh>) : Action
        data class AcceptedTranscriptUpdated(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : Action
        data class EventStreamConnected(val server: OpenCodeServer) : Action
        data class ContentBatch(val sessionID: String, val events: List<OpenCodeLiveContentEvent>) : Action
        data class MessageInvalidated(val sessionID: String) : Action
        data class ExternalRunStateChanged(val isRunning: Boolean) : Action
        data class TranscriptBrowsingChanged(val isBrowsing: Boolean) : Action
        /** The reader has been back at the end for [windowResetDelay]. */
        data object RenderedWindowSettled : Action
        /** The reader reached the end with the New divider scrolled out of view. */
        data class NewMessagesPassed(val generation: Int) : Action
        /** Everything the mark covered fit on screen. */
        data class NewMessagesFit(val generation: Int) : Action
        data object ShowEarlierTapped : Action
        data object FillEarlierHistory : Action
        data class EarlierHistoryResponse(
            val sessionID: String,
            val generation: Int,
            val cursor: String?,
            val result: Result<OpenCodeTranscriptPage>,
        ) : Action
        data object SendTapped : Action
        /** Sends the Send Later share the composer took ([State.sharedSend]). */
        data object SharedSendRequested : Action
        data object QueueTapped : Action
        data object ReturnKeyTapped : Action
        data object CommandReturnTapped : Action
        data class NonSendNewlineInserted(val text: String) : Action
        data class Dictation(val action: DictationFeature.Action) : Action
        /** The scene became active, or stopped being active. */
        data class SceneActiveChanged(val isActive: Boolean) : Action
        /** The scene went to the background (not merely inactive). */
        data object SceneEnteredBackground : Action
        data class PollingPauseChanged(val paused: Boolean) : Action
        /** The empty composer's call button: logs its trigger, then [VoiceTapped]. */
        data object CallButtonTapped : Action
        data object VoiceTapped : Action
        /** Dictation let go of the audio session: the call it waited for starts. */
        data class CallAudioYielded(val atEar: Boolean) : Action
        /** Held Stop mid-run: starts a call on the working session without stopping it. */
        data object StopLongPressed : Action
        /** Raise to Talk: the phone came up to the user's ear. */
        data object RaisedToEar : Action
        /** Raise to Talk: the phone is at the ear, or left it. */
        data class AtEarChanged(val atEar: Boolean) : Action
        data class CopyTextTapped(val text: String) : Action
        /** Selected text in an assistant reply quoted into the composer draft. */
        data class QuoteTextTapped(val text: String) : Action
        data object VoiceExitFinished : Action
        data class Voice(val action: PresentationAction<VoiceFeature.Action>) : Action
        data class Browser(val action: BrowserFeature.Action) : Action
        /** A choice from a reply link's menu. Share is presented by the view. */
        data class LinkMenu(val action: LinkMenuAction, val url: String) : Action
        /** A public web link tapped in a reply with Open Links in Apps on. */
        data class LinkTapped(val url: String) : Action
        /** The system's answer for the probe [id]. */
        data class UniversalLinkResponse(val id: UUID, val openedInApp: Boolean) : Action
        /** Session IDs a reply shows; Home resolves their titles. */
        data class SessionLinksAppeared(val ids: List<String>) : Action
        /** Home: what's on screen changed; a link tap still waiting here opens nothing. */
        data object VisibleScreenChanged : Action
        /** A send asked for earlier, once its last words or uploads landed. */
        data class DeferredSendReady(val delivery: OpenCodeDelivery) : Action
        data object InterruptTapped : Action
        /** The server took Stop: [interrupted] is false when nothing was running. */
        data class InterruptAcknowledged(val sessionID: String, val generation: Int, val interrupted: Boolean) : Action
        /** Stop didn't take. */
        data class InterruptFailed(val sessionID: String, val generation: Int) : Action
        /** A next run took the steering messages waiting at Stop: it took. */
        data class InterruptResumed(val sessionID: String, val generation: Int) : Action
        /** The server's own word on whether the session runs. */
        data class RunStateRead(val sessionID: String, val generation: Int, val isActive: Boolean) : Action
        /** Send Now couldn't wake the session with a steering message left behind. */
        data class StrandedSteerSendFailed(
            val sessionID: String,
            val generation: Int,
            val inputID: String,
            val error: OpenCodeClientError,
            val restoring: OpenCodeDelivery? = null,
        ) : Action
        data class SessionCreated(val session: OpenCodeSession) : Action
        /** The empty session a call started on a draft asked for. */
        data class CallSessionResponse(val result: Result<OpenCodeSession>) : Action
        data class PromptFailed(val sessionID: String?, val error: OpenCodeClientError, val pending: PendingPrompt) : Action
        data class PromptAdmitted(val sessionID: String, val messageID: String, val requestEpoch: Int) : Action
        data class PromptEchoResponse(
            val sessionID: String,
            val messageID: String,
            val requestEpoch: Int,
            val items: List<OpenCodeTranscriptItem>,
        ) : Action
        data class RemotePromptMissing(val sessionID: String, val messageID: String, val requestEpoch: Int) : Action
        data class ReplyCompleted(val sessionID: String) : Action
        data class TranscriptResponse(
            val sessionID: String,
            val result: Result<List<OpenCodeTranscriptItem>>,
            val pageCursor: AuthoritativeRefresh.PageCursor? = null,
        ) : Action
        data class VoicePromptAdmitted(val sessionID: String) : Action
        data object ModelButtonTapped : Action
        /** The composer grew into the model list: refresh the catalog. */
        data object ModelListOpened : Action
        /** The model list has gone: its search is cleared. */
        data object ModelListClosed : Action
        data class ModelsResponse(val result: Result<OpenCodeModelCatalog>) : Action
        /** The server's model catalog changed (`model.updated`, `provider.updated`). */
        data object ModelsChanged : Action
        data class ModelPicked(val model: OpenCodeModel) : Action
        /** Picks a model and reasoning level together. [ticked]: a drag already played a selection per stop. */
        data class ModelPickedWithLevel(val model: OpenCodeModel, val level: String?, val ticked: Boolean = false) : Action
        /** A level chosen from a row's menu in the sheet picker. */
        data class ModelLevelPicked(val model: OpenCodeModel, val level: String?) : Action
        /** Scrubs or adjusts a model's remembered reasoning level in place. */
        data class ModelRowLevelChanged(val model: OpenCodeModel, val level: String?) : Action
        /** A reasoning variant for the current model; null is its default. */
        data class VariantPicked(val variant: String?) : Action
        data class ToggleFavoriteModel(val model: OpenCodeModel) : Action
        /** Show All or Show Fewer at the foot of the picker. */
        data object ShowAllModelsToggled : Action
        /** A send the server admitted applied this model first. */
        data class ModelApplied(val sessionID: String, val model: OpenCodeModelRef) : Action
        /** Agent Selection (`SessionFeatureAgents.kt`). */
        data class AgentApplied(val sessionID: String, val agent: String) : Action
        data object AgentsRequested : Action
        /** The server's agents changed (`agent.updated`). */
        data object AgentsChanged : Action
        data class AgentsResponse(val result: Result<List<OpenCodeAutocompleteAgent>>) : Action
        data class AgentPicked(val agent: String) : Action
        data object ProjectButtonTapped : Action
        /** The Project sheet finished sliding away. */
        data object ProjectPickerDismissed : Action
        data class ProjectPicked(val project: OpenCodeProject) : Action
        /** A recent project above a draft's composer was tapped. */
        data class DraftProjectTapped(val project: OpenCodeProject) : Action
        /** A starter prompt was tapped: it's typed into the composer, not sent. */
        data class StarterPromptTapped(val text: String) : Action
        /** Swaps the family member on screen in place. */
        data class FamilyPicked(val sessionID: String) : Action
        /** A subagent's card or row was tapped: Home pushes it over this screen. */
        data class SubagentTapped(val sessionID: String) : Action
        data object FamilyRefreshRequested : Action
        data class FamilyResponse(val result: Result<OpenCodeSessionFamily>) : Action
        data class SessionSnapshotUpdated(val session: OpenCodeSession) : Action
        data class FamilyRunStateChanged(val sessionID: String, val isRunning: Boolean) : Action
        data class ActiveSessionsRead(val sessionIDs: Set<String>) : Action
        data class FamilySessionRenamed(val sessionID: String, val title: String) : Action
        data class FamilySessionDeleted(val sessionID: String) : Action
        data class ShellsResponse(val sessionID: String, val result: Result<List<OpenCodeShell>>) : Action
        data class ShellStarted(val shell: OpenCodeShell) : Action
        data class ShellEnded(val shellID: String) : Action
        data class ShellOutputTapped(val shellID: String) : Action
        data class JobsResponse(val sessionID: String, val jobs: List<OpenCodeJob>) : Action
        data class JobUpdated(val job: OpenCodeJob) : Action
        /** Stops a background script: cancels its job. */
        data class BackgroundScriptStopTapped(val jobID: String) : Action
        /** Interrupts the subagent if it runs and cancels every running job in its tree. */
        data class BackgroundSubagentStopTapped(val sessionID: String) : Action
        /** Stops a background shell from its row. */
        data class BackgroundShellStopTapped(val shellID: String) : Action
        data class BackgroundShellStopFinished(val shellID: String, val failed: Boolean) : Action
        /** A row's Stop settled; [id] is the script's job or the subagent's session. */
        data class BackgroundStopFinished(val id: String, val isSubagent: Boolean, val failed: Boolean) : Action
        data class ShellOutput(val action: PresentationAction<ShellOutputFeature.Action>) : Action
        data class BackgroundHintElapsed(val key: String) : Action
        data object MoveToBackgroundTapped : Action
        data class MoveToBackgroundFinished(
            val sessionID: String,
            val key: String,
            val error: OpenCodeClientError?,
            val isAutomatic: Boolean = false,
        ) : Action
        /** A send's delivery ended: its claim, if it never moved the work, lapses. */
        data class AutoBackgroundClaimLapsed(val messageID: String, val requestEpoch: Int) : Action
        data class ComposerAttachments(val action: ComposerAttachmentsFeature.Action) : Action
        /**
         * A drawn image for the session whose viewer it came from ([from]), added by the rules a
         * pasted image follows. It takes the place of the pending photo it was drawn on
         * ([replacing], an attachment ID). A session action, since [State.TaskIdentity] is this
         * module's, above the composer's.
         */
        data class DrawingAdded(val attachment: OpenCodeOutgoingAttachment, val from: State.TaskIdentity, val replacing: String? = null) : Action
        /** An upward swipe on the unfocused composer asks for the keyboard. */
        data object ComposerSwipedUp : Action
        /** Send a failed message again, with its ID and delivery. */
        data class FailedSendRetryTapped(val id: String) : Action
        /** Put a failed message's text and attachments back in the composer. */
        data class FailedSendEditTapped(val id: String) : Action
        data class FailedSendDeleteTapped(val id: String) : Action
        /** Folder gone: move to `missingFolderMoveTarget`, then send again. */
        data class FailedSendMoveTapped(val id: String) : Action
        data class MissingFolderMoved(val sessionID: String, val messageID: String, val directory: String) : Action
        data class MissingFolderMoveFailed(val sessionID: String, val messageID: String, val error: OpenCodeClientError) : Action
        /** A family session now lives in [directory] (this phone's move or `session.moved`). */
        data class SessionMoved(val sessionID: String, val directory: String) : Action
        /** A run error's action: Try Again, Edit, Compact, or Switch Model. */
        data class RunErrorActionTapped(val action: OpenCodeRunError.Action) : Action
        /** Edit's undo landed (or failed): the message's words go back to the composer. */
        data class LastMessageUndone(val sessionID: String, val messageID: String, val text: String, val error: OpenCodeClientError?) : Action
        /** Revert the session to just before one of the reader's messages. */
        data class RevertToHereTapped(val messageID: String) : Action
        /** A staged revert from a message's context menu landed (or failed). */
        data class MessageReverted(
            val sessionID: String,
            val messageID: String,
            val restored: PendingPrompt,
            val error: OpenCodeClientError?,
        ) : Action
        /** Redo brings back what an undo hid. */
        data object RedoTapped : Action
        data class Redone(val sessionID: String, val error: OpenCodeClientError?) : Action
        /** The server staged an undo, redid it, or made it final (any client's). */
        data class RevertChanged(val sessionID: String, val messageID: String?, val committed: Boolean) : Action
        data class PendingRequestsResponse(val sessionID: String, val result: Result<OpenCodePendingRequests>) : Action
        data class RequestsConnectionChanged(val isAvailable: Boolean) : Action
        data class PendingInputsResponse(val sessionID: String, val inputs: List<OpenCodePendingInput>) : Action
        data class PendingInputsRequested(val sessionID: String) : Action
        /** A live `session.inbox.delivered` for this session. */
        data class InputQueueChanged(val deliveredID: String?) : Action
        data class PendingInputSendNowTapped(val id: String) : Action
        /** A steering message waits for the response to finish instead. */
        data class PendingInputMoveToQueueTapped(val id: String) : Action
        data class PendingInputCancelTapped(val id: String) : Action
        /** [restoring] puts back the delivery an optimistic switch replaced; null for a cancellation. */
        data class PendingInputUpdateFailed(
            val sessionID: String,
            val inputID: String,
            val restoring: OpenCodeDelivery?,
            val error: OpenCodeClientError,
        ) : Action
        /** A live `session.inbox.enqueued` (or delivery change). */
        data class InputEnqueued(val input: OpenCodePendingInput?) : Action
        data class InputCancelled(val id: String) : Action
        /** A live permission/question/form event; the request list is stale. */
        data object RequestsChanged : Action
        data class RequestDock(val action: RequestDockFeature.Action) : Action
        data object SessionInfoTapped : Action
        data object ReportProblemTapped : Action
        /** The header menu's Session Media: what the session shared, opening on Media. */
        data object SessionMediaTapped : Action
        data class SessionMediaFilterPicked(val filter: SessionMedia.Filter) : Action
        /** Session Media's Load Earlier Messages: always an older page, never just the transcript's window. */
        data object SessionMediaLoadEarlierTapped : Action
        /** Media's Tiles / List switch. */
        data class SessionMediaLayoutPicked(val layout: SessionMedia.Layout) : Action
        data object SessionMediaDismissed : Action
        /** A header-menu item: the Session sheet's action without opening it. */
        data class SessionMenu(val action: SessionSheetFeature.Action) : Action
        data class SessionSheet(val action: SessionSheetFeature.Action) : Action
        data class FolderBrowser(val action: PresentationAction<FolderBrowserFeature.Action>) : Action
        data class Autocomplete(val action: ComposerAutocompleteAction) : Action
        data class Delegate(val delegate: SessionFeature.Delegate) : Action

        companion object {
            val micTapped: Action get() = Dictation(DictationFeature.Action.MicTapped)
            val micPressBegan: Action get() = Dictation(DictationFeature.Action.MicPressBegan)
            fun micPressArmed(arm: DictationFeature.Hold.Arm?): Action = Dictation(DictationFeature.Action.MicPressArmed(arm))
            val micPressReleased: Action get() = Dictation(DictationFeature.Action.MicPressReleased)
            val micPressLocked: Action get() = Dictation(DictationFeature.Action.MicPressLocked)
            val micPressInterrupted: Action get() = Dictation(DictationFeature.Action.MicPressInterrupted)
            val micPressCancelled: Action get() = Dictation(DictationFeature.Action.MicPressCancelled)
            val dictationInsertTapped: Action get() = Dictation(DictationFeature.Action.InsertTapped)
            val dictationSendTapped: Action get() = Dictation(DictationFeature.Action.SendTapped)
            val dictationQueueTapped: Action get() = Dictation(DictationFeature.Action.QueueTapped)
            val dictationStopRequested: Action get() = Dictation(DictationFeature.Action.StopRequested)
            val dictationReady: Action get() = Dictation(DictationFeature.Action.Ready)
            fun dictationUpdated(transcript: String, recording: Int = 0): Action =
                Dictation(DictationFeature.Action.Updated(transcript, recording))
            fun dictationStopped(errorMessage: String?, recording: Int = 0): Action =
                Dictation(DictationFeature.Action.Stopped(errorMessage, recording))

            fun permissionReplyTapped(id: String, reply: OpenCodePermissionReply, message: String? = null): Action =
                RequestDock(RequestDockFeature.Action.PermissionReplyTapped(id, reply, message))
            fun requestResponseFinished(sessionID: String, requestID: String, error: OpenCodeClientError?, dismissal: Boolean = false): Action =
                RequestDock(RequestDockFeature.Action.ResponseFinished(sessionID, requestID, error, dismissal))
            fun reviewRequestSelected(id: String): Action = RequestDock(RequestDockFeature.Action.ReviewRequestSelected(id))
            fun requestSheetTapped(id: String): Action = RequestDock(RequestDockFeature.Action.SheetTapped(id))
            val refreshRequestsTapped: Action get() = RequestDock(RequestDockFeature.Action.RefreshTapped)
            val requestsAutoRetry: Action get() = RequestDock(RequestDockFeature.Action.AutoRetry)
            fun questionPageChanged(requestID: String, index: Int): Action =
                RequestDock(RequestDockFeature.Action.QuestionPageChanged(requestID, index))
            fun requestCollapseChanged(requestID: String, collapsed: Boolean): Action =
                RequestDock(RequestDockFeature.Action.CollapseChanged(requestID, collapsed))
            fun formValueChanged(formID: String, key: String, value: OpenCodeFormValue?): Action =
                RequestDock(RequestDockFeature.Action.FormValueChanged(formID, key, value))
            fun formSubmitTapped(id: String): Action = RequestDock(RequestDockFeature.Action.FormSubmitTapped(id))
            fun formCancelTapped(id: String, message: String? = null): Action =
                RequestDock(RequestDockFeature.Action.FormCancelTapped(id, message))
            val requestDismissTapped: Action get() = RequestDock(RequestDockFeature.Action.DismissTapped)
            val requestCustomMarkTapped: Action get() = RequestDock(RequestDockFeature.Action.CustomMarkTapped)
            fun requestBarSent(requestID: String): Action = RequestDock(RequestDockFeature.Action.BarSent(requestID))

            fun attachmentAdded(attachment: OpenCodeOutgoingAttachment): Action =
                ComposerAttachments(ComposerAttachmentsFeature.Action.AttachmentAdded(attachment))
            fun attachmentRemoved(index: Int): Action = ComposerAttachments(ComposerAttachmentsFeature.Action.AttachmentRemoved(index))
            fun filesStaged(files: List<ComposerStagedFile>): Action = ComposerAttachments(ComposerAttachmentsFeature.Action.FilesStaged(files))
            fun fileUploadProgress(id: UUID, sent: Long): Action =
                ComposerAttachments(ComposerAttachmentsFeature.Action.FileUploadProgress(id, sent))
            fun fileUploadFinished(id: UUID, result: ComposerAttachmentsFeature.Outcome<String, OpenCodeClientError>): Action =
                ComposerAttachments(ComposerAttachmentsFeature.Action.FileUploadFinished(id, result))
            fun fileRetryTapped(id: UUID): Action = ComposerAttachments(ComposerAttachmentsFeature.Action.FileRetryTapped(id))
            fun fileRemoved(id: UUID): Action = ComposerAttachments(ComposerAttachmentsFeature.Action.FileRemoved(id))
            val locationTapped: Action get() = ComposerAttachments(ComposerAttachmentsFeature.Action.LocationTapped)
            fun locationResponse(result: ComposerAttachmentsFeature.Outcome<SharedLocation, LocationClientError>): Action =
                ComposerAttachments(ComposerAttachmentsFeature.Action.LocationResponse(result))
            val locationRemoved: Action get() = ComposerAttachments(ComposerAttachmentsFeature.Action.LocationRemoved)
            fun quoteRemoved(id: String): Action = ComposerAttachments(ComposerAttachmentsFeature.Action.QuoteRemoved(id))
            fun quoteCommented(id: String, comment: String): Action = ComposerAttachments(ComposerAttachmentsFeature.Action.QuoteCommented(id, comment))
            val locationErrorDismissed: Action get() = ComposerAttachments(ComposerAttachmentsFeature.Action.LocationErrorDismissed)
            val locationSettingsTapped: Action get() = ComposerAttachments(ComposerAttachmentsFeature.Action.LocationSettingsTapped)
            val queuedUploadsStarted: Action get() = ComposerAttachments(ComposerAttachmentsFeature.Action.QueuedUploadsStarted)

            /** `.voice(.presented(action))`. */
            fun voicePresented(action: VoiceFeature.Action): Action = Voice(PresentationAction.Presented(action))
        }
    }

    /** List-level changes Home owns. */
    sealed interface Delegate {
        /** [toolID]: a voice open (`android_voice_open_session`) App authorized; Home navigates only for it. */
        data class OpenSession(val session: OpenCodeSession, val toolID: String? = null) : Delegate
        /** Push a subagent of this session's family over this screen. */
        data class OpenSubagent(val session: OpenCodeSession) : Delegate
        /** Titles for linked session IDs the family doesn't hold. */
        data class ResolveSessionLinks(val ids: List<String>) : Delegate
        /** A linked session ID was tapped: open it on this server. */
        data class OpenSessionLink(val id: String) : Delegate
        /** A pushed subagent's call button: the root starts the call here. */
        data object CallRequested : Delegate
        data class TogglePin(val sessionID: String) : Delegate
        /** From the voice agent: pin or unpin, not toggle. */
        data class SetPinned(val session: OpenCodeSession, val pinned: Boolean) : Delegate
        /** From the voice agent: a new session's draft, in a project when given. */
        data class OpenDraft(val directory: String?) : Delegate
        data class ToggleRead(val sessionID: String) : Delegate
        data class ToggleMute(val sessionID: String) : Delegate
        data class DeleteSession(val sessionID: String) : Delegate
        /** A recording's words for another session's composer, unsent. */
        data class KeepDraft(val target: SharedDraftTarget, val draft: SharedComposerDraft) : Delegate
        data object ReportProblem : Delegate
    }

    /** One authoritative read's result. */
    data class AuthoritativeRefresh(
        val observed: OpenCodeSession,
        val items: List<OpenCodeTranscriptItem>,
        val scope: Scope,
        val context: OpenCodeContextReading = OpenCodeContextReading.Inconclusive,
        /** [observed] was read now; otherwise it's the copy the refresh started from. */
        val observedIsRead: Boolean = false,
        /** The server's `cursor.next` when decoded from a `/message` response (`null` when exhausted). */
        val pageCursor: PageCursor? = null,
    ) {
        /** [reachesStart]: the window holds the session's first message (`OpenCodeTranscriptWindow.PageCursor`). */
        data class PageCursor(val next: String?, val reachesStart: Boolean = next == null, val isComplete: Boolean = next == null)

        sealed interface Scope {
            data object Page : Scope
            data class Message(val messageID: String) : Scope
        }
    }

    /**
     * Presentation data only. A new visit gets a new cancellation identity; known work paints
     * immediately while its authoritative reads run again. Build with `SwitchSnapshot.from(state)`;
     * apply with `restore(state)`.
     */
    data class SwitchSnapshot(
        val session: OpenCodeSession,
        val family: List<OpenCodeSession>,
        val familyReadAt: Instant?,
        val shells: List<OpenCodeShell>,
        /** Running jobs in the tree, kept by `experimental.job.updated` while away. */
        val jobs: Map<String, OpenCodeJob> = emptyMap(),
        val permissions: List<ai.opencode.server.model.OpenCodePermissionRequest>,
        val forms: List<ai.opencode.server.model.OpenCodeFormRequest>,
        val formAnswers: Map<String, Map<String, OpenCodeFormValue>>,
        val questionPageIndices: Map<String, Int>,
        /** Docked questions left dragged down to their bar. */
        val collapsedRequests: Set<String> = emptySet(),
        val hasLoadedRequests: Boolean,
        val completedRequests: Set<String>,
        /** The docked request the reader pinned (`selectedRequestID`). */
        val selectedRequest: String?,
        val queued: List<OpenCodePendingInput>,
        val positions: Map<String, State.PendingPromptPosition>,
        val models: List<OpenCodeModel>,
        val currentModel: OpenCodeModelRef?,
        val pendingModel: OpenCodeModelRef?,
        val queuedPicks: Map<String, OpenCodeModelRef>,
        val context: OpenCodeContextUsage?,
        val movableWorkKey: String?,
        val movedWorkKeys: Set<String>,
        /** What was on screen as the reader left, for the New divider. */
        val seenItemIDs: Set<String>,
        /** Something they hadn't seen was still there as they left. */
        val hasUnseenItems: Boolean = false,
        /** The request bar's unsent answers, the one on screen included. */
        val answerDrafts: Map<String, String> = emptyMap(),
    ) {
        companion object
    }

    sealed interface SendTarget {
        data class Existing(val sessionID: String) : SendTarget
        data class NewSession(val directory: String) : SendTarget
    }

    sealed interface CancelID {
        data object UniversalLink : CancelID
        data object Models : CancelID
        data object Requests : CancelID
        data object Inbox : CancelID
        data object InboxFetch : CancelID
        data object PromptEcho : CancelID
        data object RequestsRetry : CancelID
        data object RequestsChanged : CancelID
        data object Freshness : CancelID
        data object Watchdog : CancelID
        data object EarlierHistory : CancelID
        data object FamilyRefresh : CancelID
        data object Shells : CancelID
        data object Jobs : CancelID
        data object Revert : CancelID
        data object MissingFolderMove : CancelID
        data class Interrupt(val generation: Int) : CancelID
        data class InterruptSettle(val generation: Int) : CancelID
        data class RunEndCheck(val generation: Int) : CancelID
        data class StrandedSteer(val generation: Int) : CancelID
        data object BackgroundHint : CancelID
        data object MoveToBackground : CancelID
        data object ViewReceipt : CancelID
        data object ResyncDelay : CancelID
        data object ResyncMinimum : CancelID
        data object VoiceExit : CancelID
        data object VoiceViewing : CancelID
        data object AutocompleteCatalog : CancelID
        data object AutocompleteCommands : CancelID
        data object AutocompleteFiles : CancelID
        data object AutocompleteSessions : CancelID
        data object AutocompleteAutoRun : CancelID
        data object AutocompleteCommand : CancelID
        data object AutocompleteCommandWait : CancelID
        data object Agents : CancelID
        data object WindowReset : CancelID
    }

    companion object {
        const val initialFetchLimit = 30
        /** The window fetched before the first frame; `firstContentFrameRendered` fills to [initialFetchLimit] right after. */
        const val firstFrameFetchLimit = 12
        /** The rows a session opens with, and the least a reader back at the end keeps. */
        const val baseVisibleLimit = 20
        /** A reader back at the end keeps at least this many rows (entries) above it: more than a large tablet's screen even when every row is one line. */
        const val settledWindowEntries = 45
        /** The most rows a settled window reaches back to keep a turn whole. */
        const val settledWindowCap = 120
        /** How long after a browsing reader returns to the end the window lets go of the rows above: after Jump to latest's glide, however far it went. */
        val windowResetDelay = 2.seconds
        /** What Try Again sends after a run error: V2 has no retry of its own. */
        const val tryAgainPrompt = "Continue"

        /**
         * One function per run of cases, tried in this order. A step returns null when it doesn't
         * take the action, and the next one runs.
         */
        internal val coreSteps: List<SessionFeature.(State, Action) -> Reduced<State, Action>?> = listOf(
            SessionFeature::reduceCallAndBindings,
            SessionFeature::reduceStream,
            SessionFeature::reducePendingInputs,
            SessionFeature::reduceSending,
            SessionFeature::reduceDictation,
            SessionFeature::reduceRunLifecycle,
            SessionFeature::reduceModelsAndFamily,
            SessionFeature::reduceBrowserAndBackgroundWork,
            SessionFeature::reduceJobs,
            SessionFeature::reduceAttachments,
            SessionFeature::reduceRequests,
            SessionFeature::reduceHistory,
        )
    }

    // region Dependencies (resolved on each read from the store's values)

    internal val openCodeClient by Dependency(OpenCodeClient)
    internal val clock by Dependency(Clock)
    /** The watchdog's clock, captured at init only when [SessionTimers.watchdog] is on. */
    internal val watchdogClock: Clock? = if (dependency(SessionTimers).watchdog) dependency(Clock) else null
    internal val uuid by Dependency(UuidClient)
    internal val serverID by Dependency(ServerIDClient)
    internal val composerPersistence by Dependency(ComposerPersistence)
    internal val undeliveredPrompts by Dependency(UndeliveredPrompts)
    internal val promptOutbox by Dependency(PromptOutbox)
    internal val sessionNotifications by Dependency(SessionNotificationsClient)
    internal val haptics by Dependency(HapticsClient)
    internal val soundEffects by Dependency(SoundEffectsClient)
    internal val mediaReferences by Dependency(MediaReferenceClient)
    internal val fileChecks by Dependency(AgentFileChecks)
    internal val sceneWindow by Dependency(SceneWindowClient)
    internal val dictation by Dependency(DictationClient)
    internal val date by Dependency(DateClient)
    internal val pageReader by Dependency(PageReaderClient)
    internal val networkPath by Dependency(NetworkPathClient)
    internal val backgroundTask by Dependency(BackgroundTaskClient)
    internal val openURL by Dependency(OpenURLClient)
    internal val pasteboard by Dependency(PasteboardClient)
    internal val universalLinks by Dependency(UniversalLinkClient)
    internal val experimentalFeatures by Dependency(ExperimentalFeatures)
    internal val sessionTimers by Dependency(SessionTimers)
    internal val liveSocketWarmer by Dependency(LiveSocketWarmerClient)

    // endregion

    // region Body

    /** Content batches and family run changes skip the full body's hooks (the stream fast path). */
    private val builtBody: Reducer<State, Action> by lazy { composedBody() }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = builtBody.reduce(state, action)

    // endregion
}
