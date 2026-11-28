package ai.opencode.feature.drawer

import ai.opencode.core.log.SystemActionOrigin
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.feature.transcript.SessionSeenWatermarks
import ai.opencode.feature.transcript.sessionSeenWatermarks
import ai.opencode.core.sharing.shared
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.devtools.traces.TraceUploadsFeature
import ai.opencode.feature.composer.ComposerPersistence
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.UnsentDraft
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.SharedComposerDraft
import ai.opencode.feature.composer.SharedDraftTarget
import ai.opencode.feature.composer.SharedDrafts
import ai.opencode.feature.composer.parkPersistedDraft
import ai.opencode.feature.composer.syncAbsorbedDraft
import ai.opencode.feature.session.*
import ai.opencode.feature.system.liveactivity.DictationActivityAttributes
import ai.opencode.feature.system.liveactivity.VoiceCallActivityAttributes
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceSettingsPage
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.liveactivity.DictationCommand
import ai.opencode.feature.system.liveactivity.VoiceCallCommand
import ai.opencode.feature.system.notifications.NotificationPermission
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.settings.HomeSettingsFeature
import ai.opencode.feature.system.liveactivity.SessionLiveActivity
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.settings.SettingsFeature
import ai.opencode.feature.settings.SettingsPage
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.feature.settings.whatsnew.WhatsNewFeature
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import ai.opencode.server.model.SessionTitle
import ai.opencode.server.model.learningFirstPrompt
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import ai.opencode.feature.composer.restorePersistedPick

/**
 * One server's Home. The body is assembled in `HomeFeatureComposition.kt`; Home's own actions route
 * through `HomeFeatureCore.kt`. Home matches session screens' actions after they've reduced.
 */
class HomeFeature : Reducer<HomeFeature.State, HomeFeature.Action> {
    /**
     * This window's key for [ai.opencode.server.EventStreamFocusClient]: each window's store builds
     * its own Home reducer.
     */
    internal val streamFocusOwner: java.util.UUID = java.util.UUID.randomUUID()

    /**
     * The reducer writes each through to its shared key (`HomeFeatureDrawerState.kt`). The big
     * caches (`cachedHomeSnapshots`, `cachedTranscriptSnapshots`) are read from their keys on
     * demand.
     *
     * Build one with `State(server, snapshot, loaded)`.
     */
    data class State(
        val connection: HomeConnectionFeature.State,
        val notificationState: HomeNotificationsFeature.Status = HomeNotificationsFeature.Status(),
        /** The Inbox Sidebar's archives in flight and its last archive error. */
        val inboxSidebarStatus: InboxSidebarFeature.Status = InboxSidebarFeature.Status(),
        /** The server being connected to from the drawer, while this Home stays up until that server's Home replaces it. */
        val switchingTo: OpenCodeServer? = null,
        /** Server Flow: a switch that went back on its own, and why. */
        val serverSwitchNote: ServerSwitchNote? = null,
        /** Server Flow: servers last given up on, by persistence ID. */
        val serverReachability: Map<String, ServerReachability> = emptyMap(),
        val projects: List<OpenCodeProject> = emptyList(),
        val sessions: List<OpenCodeSession> = emptyList(),
        val activeSessionIDs: Set<String> = emptySet(),
        /**
         * Known `childID → parentID` links. A parent never changes, so links are merged in from
         * snapshots, `session.created` and the families this Home has read, never replaced.
         */
        val activeSessionParents: Map<String, String> = emptyMap(),
        /**
         * Bumps only when cached transcript, active run state, or snapshot state changes for the
         * Catch Up preview session ([catchUpPreviewID]). Maintained after each reduce.
         */
        val previewRevision: Int = 0,
        val search: String = "",
        /** Older pages and the server's search results (`HomeFeatureSessionPages.kt`). */
        val sessionPages: SessionPages = SessionPages(),
        val transcriptCache: Map<String, List<OpenCodeTranscriptItem>> = emptyMap(),
        /** Cached transcripts being read again (`refreshCachedTranscript`). */
        val refreshingTranscripts: Set<String> = emptySet(),
        /** Those of [refreshingTranscripts] sent at once rather than after a delay, so a read wanted now joins them. */
        val fetchingTranscripts: Set<String> = emptySet(),
        /**
         * Cached sessions whose background refresh was cancelled or skipped while a foreground
         * transcript read had the connection, oldest first, bounded to [dirtyCachedTranscriptLimit].
         */
        val dirtyCachedTranscriptIDs: List<String> = emptyList(),
        /** When each cached transcript was last read, so a session already fresh isn't read again. */
        val transcriptReadAt: Map<String, Instant> = emptyMap(),
        /**
         * The copy a session would have opened with before a background read replaced it, for
         * sessions not opened yet this run: opening compares with it whether the read or the tap
         * came first.
         */
        val unopenedSeen: Map<String, Set<String>> = emptyMap(),
        /** An uncached session's transcript being read for the tap that opened it (`CancelID.selectedTranscript`). */
        val selectedTranscriptFetch: SelectedTranscriptFetch? = null,
        /** Last-known chrome for the last few visited sessions, scoped to this server. */
        val sessionSnapshots: Map<String, SessionFeature.SwitchSnapshot> = emptyMap(),
        /** Model picks waiting for the next send into a session not on screen yet. */
        val waitingPicks: Map<String, OpenCodeModelRef> = emptyMap(),
        val sessionSnapshotOrder: List<String> = emptyList(),
        val attentionSessionIDs: Set<String> = emptySet(),
        /** Runs that started (true) or ended (false) since the last snapshot landed, re-applied over the next one. */
        val runChangesDuringRefresh: Map<String, Boolean> = emptyMap(),
        /** Mirror of `SharedKeys.pinnedSessionKeys`. */
        val pinnedSessionKeys: List<String> = emptyList(),
        /** Mirror of `SharedKeys.lastViewedSessionIDs`. */
        val lastViewedSessionIDs: Map<String, String> = emptyMap(),
        /** Mirror of `SharedKeys.sessionReadDates`. */
        val sessionReadDates: Map<String, Instant> = emptyMap(),
        /** Mirror of `SharedKeys.sessionSeenWatermarks` (`:feature:transcript`). */
        val sessionSeenWatermarks: SessionSeenWatermarks = SessionSeenWatermarks(),
        /** Mirror of `SharedKeys.manuallyUnreadSessionKeys`. */
        val manuallyUnreadSessionKeys: Set<String> = emptySet(),
        /** How a session read from the drawer stood before the read, for the New divider when it opens. */
        val pendingOpenReadState: Map<String, PendingOpenReadState> = emptyMap(),
        /** Mirror of `SharedKeys.mutedSessionKeys`. */
        val mutedSessionKeys: Set<String> = emptySet(),
        /** Mirror of `SharedKeys.hiddenSessionKeys`. */
        val hiddenSessionKeys: Set<String> = emptySet(),
        /** Mirror of `SharedKeys.sidebarShown`: beside a wide window's sidebar, whether it shows. */
        val isSidebarShown: Boolean = true,
        /**
         * A further window's Home: its [lastViewedSessionIDs] and [isSidebarShown] live in memory,
         * so switching sessions or hiding the sidebar here never changes the first window's.
         */
        val isWindowLocal: Boolean = false,
        /** Mirror of `SharedKeys.initializedInboxServerIDs`. */
        val initializedInboxServerIDs: Set<String> = emptySet(),
        /** Device-local read receipts not yet in an authoritative snapshot: a stale list must not resurrect unread. */
        val locallyViewedMilliseconds: Map<String, Long> = emptyMap(),
        val catchUpState: CatchUpFeature.State = CatchUpFeature.State(),
        /** Developer Mode problem reports. */
        val traces: TraceUploadsFeature.State = TraceUploadsFeature.State(),
        /** Sends the server's session list has not reflected yet. */
        val localActivity: Map<String, LocalActivity> = emptyMap(),
        /** New sessions whose first message only this phone has, listed above Recents. */
        val unsentDrafts: List<UnsentDraft> = emptyList(),
        val pendingDeletion: OpenCodeSession? = null,
        val deletionError: DrawerFailure? = null,
        val recentlyDeletedIDs: Set<String> = emptySet(),
        /**
         * Settings, pushed full screen over the drawer and the session; Back returns to whichever
         * was showing. Presented: its effects end when it closes. What's New from the drawer's row
         * is this with `root = whatsNew`.
         */
        val settings: SettingsFeature.State? = null,
        /**
         * The Voice page Settings opens at, when a call asked for it; the cog and What's New open
         * Settings' root.
         */
        val settingsVoicePage: VoiceSettingsPage? = null,
        /**
         * Settings › Experimental › Show Welcome (Welcome Preview): the first-run welcome over the
         * paired app, closed back to it with nothing unpaired.
         */
        val welcome: WelcomeFeature.State? = null,
        /** The run the Session Live Activity follows (Experimental), from an accepted send until it settles or another send takes over. */
        val sessionActivity: SessionLiveActivity.Tracked? = null,
        val sessionDetail: SessionFeature.State? = null,
        /** A subagent waiting for its parent to open under it (`sessionTapped`). */
        val pendingSubagentPush: OpenCodeSession? = null,
        /** Subagents pushed over [sessionDetail], bottom first; keyed by their (negative) `navigationID`. */
        val subagentStack: List<SessionFeature.State> = emptyList(),
        /** Sessions looked up for links in transcripts (`SessionLink`), by ID. */
        val linkedSessions: Map<String, OpenCodeSession> = emptyMap(),
        val missingLinkedSessionIDs: Set<String> = emptySet(),
        /** Linked IDs being fetched (at most `linkedSessionFetchLimit`), and those waiting their turn, oldest first. */
        val linkedSessionFetches: Set<String> = emptySet(),
        val linkedSessionQueue: List<String> = emptyList(),
        /** A tapped link being looked up, and the screen it was tapped on. */
        val pendingLinkedSessionOpen: PendingLinkedSessionOpen? = null,
        /**
         * The drawer's What's New row: its switch is on and some feature is unseen. Read from
         * What's New's shared keys at start, when Settings closes and when the row is hidden.
         */
        val showsWhatsNewButton: Boolean = false,
        /** Bumped when an action takes the screen to the session from over the open drawer; the drawer closes as it changes. */
        val drawerCloseRequests: Int = 0,
        /** A computer just paired: the drawer opens on its sessions. Holds the [detailGeneration] it was asked on. */
        val drawerOpenRequest: Int? = null,
        /**
         * Where arriving shares are taking the screen. The shares themselves wait for their
         * composer in `:feature:composer`'s persistence, as do composers parked while their screen
         * is away.
         */
        val shareNavigationTarget: SharedDraftTarget? = null,
        /** False while a cold launch without a cached list waits for its first snapshot. */
        val snapshotLoaded: Boolean = true,
        /** Whether Voice is set up, as last observed. */
        val systemVoiceAvailable: Boolean = false,
        /** What the voice call's ongoing notification last showed. */
        val voiceCallActivity: VoiceCallActivityAttributes.ContentState? = null,
        /** What the recording's ongoing notification last showed. */
        val dictationActivity: DictationActivityAttributes.ContentState? = null,
        /** Whether the drawer last arrived open. */
        val isDrawerOpen: Boolean = false,
        /** Whether the drawer button can show its label: not beside a wide window's sidebar. */
        val showsDrawerLabel: Boolean = true,
        /** How far this connection (or return to the app) has read Home. */
        val noticeReadiness: NoticeReadiness = NoticeReadiness.unsettled,
        /** Counts opened details so each presented screen has its own identity. */
        val detailGeneration: Int = 0,
        /** [detailGeneration] when the app left: anything opened since (a notification, a link) wins over the new session a long absence opens. */
        val detailGenerationWhenLeft: Int? = null,
        /** Pushed subagents' identities, counted apart (and negative). */
        val pushedDetailCount: Int = 0,
        /** Why the next screen change happens, when the action that asks for it isn't the one that makes it. */
        val nextMoveReason: String? = null,
        /**
         * App refreshes this from the shared open-session operation before Home handles an action.
         * A queued navigation cannot outlive its authorization.
         */
        val voiceNavigationToolID: String? = null,
    ) {
        enum class NoticeReadiness { unsettled, listed, settled }

        /** A drawer row's status. */
        enum class Indicator {
            attention, running, unread, none;

            /** The gauntlet pairs fixture actions with these names. */
            val labMarkName: String
                get() = when (this) {
                    attention -> "attention"
                    running -> "running"
                    unread, none -> "idle"
                }
        }

        /** Read/mute state and busy sessions, snapshotted once for bulk loops over `sessions`. */
        data class StatusSnapshot(
            val serverID: String,
            val showsActivity: Boolean,
            val busy: Set<String>,
            val attention: Set<String>,
            val mutedKeys: Set<String>,
            val manuallyUnreadKeys: Set<String>,
            val readDates: Map<String, Instant>,
        ) {
            fun key(id: String): String = "$serverID|$id"

            fun isMuted(id: String): Boolean = mutedKeys.isNotEmpty() && key(id) in mutedKeys

            fun isUnread(session: OpenCodeSession): Boolean {
                if (manuallyUnreadKeys.isNotEmpty() && key(session.id) in manuallyUnreadKeys) return true
                session.idleMilliseconds?.let { return it > (session.viewedMilliseconds ?: -1) }
                return readDates[key(session.id)]?.let { it < session.updatedAt } ?: true
            }

            fun indicator(session: OpenCodeSession): Indicator {
                if (!showsActivity) return Indicator.none
                if (session.id in attention) return Indicator.attention
                if (session.id in busy) return Indicator.running
                // A muted session's read state is kept, just never shown.
                if (isUnread(session) && !isMuted(session.id)) return Indicator.unread
                return Indicator.none
            }

            /**
             * Idle with something new, or waiting on the reader. Muted and archived sessions never
             * do, and a subagent's session never does: it reaches the reader from its parent, which
             * a pending request inside it already lights (`attentionSessionIDs` holds family roots).
             */
            fun needsCatchUp(session: OpenCodeSession): Boolean {
                if (session.parentID != null || session.isArchived || isMuted(session.id)) return false
                return when (indicator(session)) {
                    Indicator.attention, Indicator.unread -> true
                    Indicator.running, Indicator.none -> false
                }
            }
        }

        /** The server read and unread state a drawer read replaced. */
        data class PendingOpenReadState(val viewedMilliseconds: Long?, val wasUnread: Boolean)

        /** What the reader views: equal when the threads are. */
        data class Viewing(val sessionID: String? = null, val rootID: String? = null) {
            val threads: Set<String> get() = setOfNotNull(sessionID, rootID)
            override fun equals(other: Any?): Boolean =
                other is Viewing && ((sessionID == other.sessionID && rootID == other.rootID) || threads == other.threads)
            override fun hashCode(): Int = threads.hashCode()
        }

        data class LocalActivity(
            /** The server's `updatedAt` before the send. Any newer server time means it has seen the send. */
            val serverUpdatedAt: Instant,
            val sentAt: Instant,
        )

        data class CatchUpPreviewSlice(
            val sessionID: String,
            val items: List<OpenCodeTranscriptItem>?,
            val isRunning: Boolean,
            val snapshot: SessionFeature.SwitchSnapshot?,
            val activeFamilyIDs: Set<String>,
        )

        /** What `knownParents` merges. */
        data class ParentSources(
            val activeSessionParents: Map<String, String>,
            val sessionSnapshots: Map<String, SessionFeature.SwitchSnapshot>,
            val family: List<OpenCodeSession>,
        ) {
            val knownParents: Map<String, String>
                get() {
                    val parents = activeSessionParents.toMutableMap()
                    for (snapshot in sessionSnapshots.values) {
                        for (session in snapshot.family) {
                            val parent = session.parentID ?: continue
                            if (parents[session.id] != parent) parents[session.id] = parent
                        }
                    }
                    for (session in family) {
                        val parent = session.parentID ?: continue
                        if (parents[session.id] != parent) parents[session.id] = parent
                    }
                    return parents
                }

            /** The running sessions and every ancestor they keep busy. */
            fun busy(activeSessionIDs: Set<String>): Set<String> =
                if (activeSessionIDs.isEmpty()) emptySet() else SessionActivity.ancestors(activeSessionIDs, knownParents)

            companion object {
                val empty = ParentSources(emptyMap(), emptyMap(), emptyList())
            }
        }

        /** One server's Home: replacing it with another server's ends this one's effects. */
        val id: String get() = server.persistenceID

        val server: OpenCodeServer get() = connection.server

        fun withServer(server: OpenCodeServer): State = copy(connection = connection.copy(server = server))

        /**
         * Like the TUI and the desktop app, a root is busy while it or anything in its family runs:
         * subagents run in their own sessions. Shells don't count.
         */
        val busySessionIDs: Set<String>
            get() = if (activeSessionIDs.isEmpty()) emptySet() else parentSources.busy(activeSessionIDs)

        /** Every parent link Home knows, including the open and remembered sessions' families. */
        val knownParents: Map<String, String> get() = parentSources.knownParents

        val parentSources: ParentSources
            get() = ParentSources(activeSessionParents, sessionSnapshots, sessionDetail?.familySessions.orEmpty())

        /** Whether a running session's ancestry reaches a listed root (or it is one). */
        fun isAttributed(sessionID: String, parents: Map<String, String>? = null): Boolean {
            val links = parents ?: knownParents
            var id = sessionID
            val seen = hashSetOf(id)
            while (true) {
                val parent = links[id] ?: break
                if (!seen.add(parent)) break
                id = parent
            }
            return sessions.any { it.id == id }
        }

        /** Running sessions whose known parent chain does not yet reach a listed root. */
        val unattributedActiveSessionIDs: Set<String>
            get() {
                if (activeSessionIDs.isEmpty()) return emptySet()
                val parents = knownParents
                return activeSessionIDs.filterTo(LinkedHashSet()) { !isAttributed(it, parents) }
            }

        /** Top-most busy ancestors and busy listed sessions. */
        val busyTopIDs: Set<String>
            get() {
                if (activeSessionIDs.isEmpty()) return emptySet()
                val parents = knownParents
                val listed = sessions.mapTo(HashSet()) { it.id }
                return SessionActivity.ancestors(activeSessionIDs, parents).filterTo(LinkedHashSet()) {
                    parents[it] == null || it in listed
                }
            }

        val catchUpPreviewID: String? get() = catchUp?.queue?.drop(1)?.firstOrNull()

        /** The Catch Up preview's inputs ([previewRevision] bumps when these change for the same session). */
        val catchUpPreviewSlice: CatchUpPreviewSlice?
            get() {
                val previewID = catchUpPreviewID ?: return null
                val snapshot = sessionSnapshots[previewID]
                val familyIDs = snapshot?.family?.mapTo(HashSet()) { it.id } ?: setOf(previewID)
                return CatchUpPreviewSlice(
                    sessionID = previewID,
                    items = transcriptCache[previewID],
                    isRunning = previewID in activeSessionIDs,
                    snapshot = snapshot,
                    activeFamilyIDs = if (activeSessionIDs.isEmpty()) emptySet() else activeSessionIDs intersect familyIDs,
                )
            }

        val credentialsRejected: Boolean get() = connection.credentialsRejected
        val isConnected: Boolean get() = connection.isConnected
        val connectionStatus: HomeConnectionFeature.State.ConnectionStatus get() = connection.connectionStatus
        val connectionErrorMessage: String? get() = connection.connectionErrorMessage
        val hasConnected: Boolean get() = connection.hasConnected
        val isStreaming: Boolean get() = connection.isStreaming
        val isEventStreamLive: Boolean get() = connection.isEventStreamLive
        val snapshotFailures: Int get() = connection.snapshotFailures
        val lastKnownActivityDeadline: Instant? get() = connection.lastKnownActivityDeadline

        val notificationPermission: NotificationPermission? get() = notificationState.notificationPermission
        val isRequestingNotifications: Boolean get() = notificationState.isRequestingNotifications
        val notificationSetupError: String? get() = notificationState.notificationSetupError
        val notifyPlugin: HomeNotificationsFeature.NotifyPluginCheck? get() = notificationState.notifyPlugin

        /** Slack-style catch-up: unread and waiting sessions as a card stack. */
        val catchUp: CatchUpFeature.Stack? get() = catchUpState.stack

        fun withCatchUp(stack: CatchUpFeature.Stack?): State = copy(catchUpState = catchUpState.copy(stack = stack))

        /** Mark All as Read receipts in flight, with what each session looked like before. */
        val markAllReceipts: Map<String, CatchUpFeature.ReadBackup> get() = catchUpState.markAllReceipts

        fun withMarkAllReceipts(receipts: Map<String, CatchUpFeature.ReadBackup>): State =
            copy(catchUpState = catchUpState.copy(markAllReceipts = receipts))

        /** Whether the pairing's drawer open still applies. */
        val opensDrawerForPairing: Boolean
            get() = drawerOpenRequest == detailGeneration &&
                sessionDetail?.isDraft != false &&
                sessionDetail?.presentsOverSession != true &&
                !presentsOverHome

        /**
         * Home has moved toward another screen, or shows something over the session: Catch Up,
         * Settings, What's New, a push, a pending open, or a confirmation.
         */
        val presentsOverHome: Boolean
            get() = catchUp != null ||
                isSettingsShown ||
                welcome != null ||
                pendingDeletion != null ||
                selectedTranscriptFetch != null ||
                pendingSubagentPush != null ||
                pendingLinkedSessionOpen != null ||
                subagentStack.isNotEmpty()

        val isSettingsShown: Boolean get() = settings != null

        /**
         * Settings pushed as Settings, never as What's New (a call needing Voice set up, the cog):
         * already up with What's New as its page, it cross-fades to Settings' root.
         */
        fun showingSettings(voicePage: VoiceSettingsPage? = null): State {
            val next = copy(settingsVoicePage = voicePage)
            val shown = settings ?: return next.presentingSettings(SettingsFeature.State(server = server, path = voiceSettingsPath(voicePage)))
            if (shown.root != SettingsFeature.State.Root.whatsNew) return next
            val root = SettingsFeature().reduce(shown, SettingsFeature.Action.ShowSettingsRoot).state
            return next.copy(settings = root.copy(path = voiceSettingsPath(voicePage)))
        }

        /**
         * The pages a call's card opens Settings at: Voice, then Voice Provider (with Your Account
         * over it) when the provider row stands in for the key row (not Grok).
         */
        private fun voiceSettingsPath(voicePage: VoiceSettingsPage?): List<SettingsPage> {
            voicePage ?: return emptyList()
            val showsProviderRow = shared(VoiceSharedKeys.voiceEngine).value != VoiceEngine.grok
            if (!showsProviderRow || voicePage == VoiceSettingsPage.voice) return listOf(SettingsPage.Voice)
            return listOf(SettingsPage.Voice, SettingsPage.VoiceProvider(opensAccount = voicePage == VoiceSettingsPage.account))
        }

        /** A reconnect, a server's first connection, or a return to the app: the next list and attention read set the baseline quietly. */
        fun unsettlingNotices(): State = copy(noticeReadiness = NoticeReadiness.unsettled)

        /** The screen on top: the last pushed subagent, else the session. */
        val topDetail: SessionFeature.State? get() = subagentStack.lastOrNull() ?: sessionDetail

        /** The screen whose recording the ongoing notification shows: a pushed one keeps its own while it stays. */
        val recordingDetail: SessionFeature.State? get() = subagentStack.lastOrNull { it.hasLiveDictation } ?: sessionDetail

        /** Whether an initial or foreground transcript request (`selectedTranscript` or the top screen's `.open`/`.resume`/`.reconnect` refresh) is in flight. */
        val isInitialTranscriptInFlight: Boolean
            get() {
                val fetch = selectedTranscriptFetch
                if (fetch != null && fetch.transition == detailGeneration) return true
                val refresh = topDetail?.refresh ?: return false
                return refresh.reason in foregroundRefreshReasons || refresh.followup?.let { it in foregroundRefreshReasons } == true
            }

        /** The open session's call, while it connects, is live or is leaving (not ended or failed). */
        val hasLiveCall: Boolean get() = sessionDetail?.voice?.phase?.isTerminal == false

        /**
         * A call or a recording owns an ongoing notification: `AppFeature` keeps this Home through
         * a server switch only without one.
         */
        val hasLiveCallOrRecording: Boolean get() = voiceCallActivity != null || dictationActivity != null

        /** The open session drew its first content frame. */
        val hasRenderedFirstContentFrame: Boolean get() = sessionDetail?.hasRenderedFirstContentFrame == true

        /** The open session shows a sheet, alert or page over itself. */
        val presentsOverSession: Boolean get() = sessionDetail?.presentsOverSession == true

        /** What the reader is looking at: the screen on top, the session it shows, and Catch Up's card. */
        val visibleScreen: VisibleScreen
            get() = VisibleScreen(
                navigationID = topDetail?.navigationID,
                sessionID = topDetail?.session?.id,
                stackDepth = subagentStack.size,
                catchUpOpen = catchUp != null,
                catchUpCard = catchUp?.queue?.firstOrNull(),
            )

        /** Projects ordered by the newest session they contain. */
        val projectsByRecency: List<OpenCodeProject>
            get() {
                if (projects.size <= 1) return projects
                val lastUsed = HashMap<String, Instant>(projects.size)
                for (session in sessions) {
                    val existing = lastUsed[session.projectID]
                    if (existing != null && existing >= session.updatedAt) continue
                    lastUsed[session.projectID] = session.updatedAt
                }
                return projects.sortedByDescending { lastUsed[it.id] ?: Instant.MIN }
            }

        companion object {
            /**
             * The cached or loaded list, the transcript cache restored, and the remembered session
             * (or a draft) open. Reads shared keys, so call it from a reducer or effect (a
             * dependency context).
             */
            operator fun invoke(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot, loaded: Boolean = true): State {
                val base = State(
                    connection = HomeConnectionFeature.State(server = server),
                    snapshotLoaded = loaded,
                    projects = snapshot.projects,
                    sessions = snapshot.sessions,
                    activeSessionIDs = snapshot.activeSessionIDs,
                    activeSessionParents = snapshot.activeSessionParents ?: emptyMap(),
                    sessionPages = SessionPages(older = SessionPageCursor(next = snapshot.nextCursor)),
                    pinnedSessionKeys = shared(SharedKeys.pinnedSessionKeys).value,
                    lastViewedSessionIDs = shared(SharedKeys.lastViewedSessionIDs).value,
                    sessionReadDates = shared(SharedKeys.sessionReadDates).value,
                    sessionSeenWatermarks = shared(SharedKeys.sessionSeenWatermarks).value,
                    manuallyUnreadSessionKeys = shared(SharedKeys.manuallyUnreadSessionKeys).value,
                    mutedSessionKeys = shared(SharedKeys.mutedSessionKeys).value,
                    hiddenSessionKeys = shared(SharedKeys.hiddenSessionKeys).value,
                    isSidebarShown = shared(SharedKeys.sidebarShown).value,
                    showsWhatsNewButton = WhatsNewFeature.State().showsButton,
                    initializedInboxServerIDs = shared(SharedKeys.initializedInboxServerIDs).value,
                )
                return base.restoringTranscriptCache().restoringInitialSession()
            }

            /** An inert Home for a view whose parent no longer holds one (never reduced). */
            fun placeholder(server: OpenCodeServer): State = State(connection = HomeConnectionFeature.State(server = server))
        }
    }

    /** The detail move an uncached tap's transcript read belongs to. */
    data class SelectedTranscriptFetch(
        val sessionID: String,
        /** The detail move it belongs to ([State.detailGeneration]). */
        val transition: Int,
    )

    /** The row a pending deletion hid: a failure only needs to put the row back. */
    data class DeletionBackup(
        val session: OpenCodeSession,
        val sessionIndex: Int,
        val wasActive: Boolean,
        val neededAttention: Boolean,
        val runChangeDuringRefresh: Boolean?,
        /** The draft that replaced the open session, if it was open. */
        val replacementDraftID: Int? = null,
        val openItems: List<OpenCodeTranscriptItem>,
    )

    data class VisibleScreen(
        val navigationID: Int?,
        val sessionID: String?,
        val stackDepth: Int,
        /** Whether Catch Up is open, and its open card (null once it ran out). */
        val catchUpOpen: Boolean,
        val catchUpCard: String?,
    )

    /** A tapped session link waiting on its lookup. */
    data class PendingLinkedSessionOpen(val id: String, val screen: Screen?) {
        /** The screen on top when it was tapped. */
        data class Screen(val sessionID: String?, val navigationID: Int, val visit: Int, val depth: Int)
    }

    /** What Home asks its parent (`AppFeature`) to do. */
    sealed interface DelegateAction {
        /** Connects to another saved server. */
        data class ServerSwitchTapped(val server: OpenCodeServer) : DelegateAction
        /** Renames a saved server in place, without reconnecting (Home also takes the name). */
        data class ServerRenamed(val server: OpenCodeServer, val name: String) : DelegateAction
        /** Opens a saved server's address, credentials and headers for editing. */
        data class EditServerRequested(val server: OpenCodeServer) : DelegateAction
        /** Forgets a saved server other than the one in use. */
        data class ServerRemovalConfirmed(val server: OpenCodeServer) : DelegateAction
        /** Opens the scanner for another server's pairing code. */
        data object PairServerRequested : DelegateAction
        /** Opens the form for a server entered by address. */
        data object AddServerRequested : DelegateAction
    }

    sealed interface Action {
        data class CatchUp(val action: CatchUpFeature.Action) : Action
        data class Traces(val action: TraceUploadsFeature.Action) : Action
        data class InboxSidebar(val action: InboxSidebarFeature.Action) : Action
        data class SessionPages(val action: SessionPagesAction) : Action
        /** The drawer's What's New row: open (`buttonTapped`) or hide (`hideTapped`). */
        data class WhatsNew(val action: WhatsNewFeature.Action) : Action
        /** Preferences (`:feature:settings`' `HomeSettingsFeature`); Home's own sender is the sidebar toggle. */
        data class Settings(val action: HomeSettingsFeature.Action) : Action
        /** The presented Settings ([State.settings]); its delegates are handled in `HomeFeatureSettings.kt`. */
        data class SettingsScreen(val action: PresentationAction<SettingsFeature.Action>) : Action
        /** Show Welcome over Home ([State.welcome]); its pairing requests go to the app. */
        data class Welcome(val action: PresentationAction<WelcomeFeature.Action>) : Action
        data class SearchChanged(val text: String) : Action
        /** The view popped (or pushed) Settings. */
        data class SettingsShownChanged(val isShown: Boolean) : Action
        data object Task : Action
        data object TranscriptCacheFlushRequested : Action
        data object ConnectionLost : Action
        data class EventStreamFailed(val error: OpenCodeClientError) : Action
        data class ServerConnected(val server: OpenCodeServer) : Action
        data object SnapshotFailed : Action
        /** A failed snapshot's backoff elapsed while the stream stayed live. */
        data object SnapshotRetryRequested : Action
        /** Last-known running state is too old to show while reconnecting. */
        data object LastKnownActivityExpired : Action
        data object RetryConnectionTapped : Action
        /** Grace timers and scene activation: see [HomeConnectionFeature.Action]. */
        data object SceneBecameActive : Action
        data object ReconnectingGraceElapsed : Action
        data class OfflineGraceElapsed(val pathDown: Boolean) : Action
        /** Server Flow: the note's Try Again or Pair Again. */
        data object ServerSwitchNoteActionTapped : Action
        /** The network path came back or moved to another interface. */
        data object NetworkRerouted : Action
        data class ServerEvent(val event: OpenCodeServerEvent) : Action
        /** Handled by `AppFeature`. */
        data class Delegate(val action: DelegateAction) : Action
        data object NotificationSettingsRequested : Action
        data class NotificationPermissionLoaded(val permission: NotificationPermission) : Action
        data object EnableNotificationsTapped : Action
        data class NotificationPermissionRequested(val permission: NotificationPermission) : Action
        data class NotificationSettingsOpened(val opened: Boolean) : Action
        data object NotifyPluginCheckRequested : Action
        data class NotifyPluginChecked(val status: OpenCodeNotifyPluginStatus?) : Action
        /** Opens a draft whose prompt asks OpenCode to install the plugin. */
        data object NotifyPluginSetUpTapped : Action
        data object SettingsTapped : Action

        /** The drawer's call button: a call on the session behind the drawer. */
        data object DrawerCallTapped : Action
        /** Manage Servers… in the drawer's title menu: Settings arrives showing the server page. */
        data object ManageServersTapped : Action
        /** The drawer visually reached the other edge: its haptic. */
        data class SessionDrawerArrived(val isOpen: Boolean) : Action
        data object NewSessionTapped : Action
        /** A computer paired for the first time: open the drawer on its sessions. */
        data object PairedComputerOpened : Action
        /** The install's first computer paired: a ready session in its latest project, with starter prompts. */
        data object FirstComputerPaired : Action
        /** The background wait found the followed run's session idle and read its last items. */
        data class SessionActivitySettled(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : Action
        /** The view opened the drawer `drawerOpenRequest` asked for. */
        data object DrawerOpenRequestHandled : Action
        data class SessionTapped(val sessionID: String) : Action
        /** The unsent outbox changed: the list's unsent sessions are read again. */
        data object UnsentDraftsChanged : Action
        data class TogglePin(val sessionID: String) : Action
        data class ToggleMute(val sessionID: String) : Action
        data class ToggleHidden(val sessionID: String) : Action
        data class MovePinUp(val sessionID: String) : Action
        data class MovePinDown(val sessionID: String) : Action
        data class DrawerPinZoneCrossed(val entered: Boolean) : Action
        data object DrawerPinSlotChanged : Action
        data class DrawerRowDropReleased(val sessionID: String, val pinnedIndex: Int?) : Action
        data class DrawerRowDropped(val sessionID: String, val pinnedIndex: Int?) : Action
        data class DeleteSessionTapped(val sessionID: String) : Action
        data object DeleteSessionCancelled : Action
        data object DeleteSessionConfirmed : Action
        data class DeleteSessionResponse(val sessionID: String, val backup: DeletionBackup, val error: OpenCodeClientError?) : Action
        data object ClearDeletionError : Action
        data class MarkRead(val sessionID: String) : Action
        data class ViewAcknowledged(val sessionID: String, val idle: Long) : Action
        data class MarkUnread(val sessionID: String) : Action
        data class SnapshotRefreshed(val snapshot: OpenCodeHomeSnapshot) : Action
        data class ActiveAncestryResolved(val parents: Map<String, String>) : Action
        data class AttentionRefreshed(val sessionIDs: Set<String>) : Action
        /** The open session's own actions (`ifLet`); Home matches them after the screen has reduced them. */
        data class SessionDetail(val action: PresentationAction<SessionFeature.Action>) : Action
        /** A pushed subagent's own actions, by its `navigationID` (`forEach`). */
        data class SubagentStack(val id: Int, val action: SessionFeature.Action) : Action
        /** The screen went back until [depth] subagents remain pushed. */
        data class SubagentsPopped(val depth: Int) : Action
        /** A linked session ID looked up for its title. */
        data class LinkedSessionFetched(val id: String, val result: Result<OpenCodeSession>) : Action
        /** A tapped session link the list didn't hold, looked up to open it. */
        data class LinkedSessionOpened(val id: String, val result: Result<OpenCodeSession>) : Action
        /** Items from the share sheet, oldest first, all for this server. */
        data class SharesReceived(val shares: List<ReceivedShare>) : Action
        data class TranscriptPrefetched(val sessionID: String, val items: List<OpenCodeTranscriptItem>) : Action
        /** A cached transcript read again, so it opens up to date. */
        data class CachedTranscriptRefreshed(val sessionID: String, val items: List<OpenCodeTranscriptItem>?) : Action
        /** A finger came down on a drawer row: its tap may follow. */
        data class DrawerRowPressed(val sessionID: String) : Action
        /** The app left the screen: the stream stays live a little longer. */
        data object SceneEnteredBackground : Action
        data class SessionNavigationReady(val sessionID: String) : Action
        data class SelectedTranscriptResponse(
            val sessionID: String,
            val result: Result<List<OpenCodeTranscriptItem>>,
            val pageCursor: SessionFeature.AuthoritativeRefresh.PageCursor? = null,
        ) : Action
        data class VoiceCallCommandReceived(val command: VoiceCallCommand) : Action
        data class DictationCommandReceived(val command: DictationCommand) : Action
        /** Shortcuts, a launcher action or a link, for this server; `AppFeature` has fetched any session the list lacked. */
        data class SystemActionOpened(
            val destination: SystemAction.Destination,
            val startsCall: Boolean,
            val inBackground: Boolean = false,
            /** Which system surface asked, for the call's trigger log. */
            val origin: SystemActionOrigin = SystemActionOrigin.other,
        ) : Action
        /** From the view, on appearing and whenever Voice is set up or not: publishes to the system. */
        data class SystemPublishRequested(val voiceAvailable: Boolean) : Action
        /** From the view: whether the drawer button can show the label. */
        data class DrawerLabelAvailabilityChanged(val shows: Boolean) : Action
        /** Used by playgrounds to swap fixture states in place. */
        data class PlaygroundSetSessionDetail(val detail: SessionFeature.State) : Action
        data class PlaygroundReplaceState(val state: State) : Action

        companion object {
            val catchUpTapped: Action get() = CatchUp(CatchUpFeature.Action.Tapped)
            fun catchUpCardDismissed(sessionID: String, disposition: CatchUpFeature.Disposition): Action =
                CatchUp(CatchUpFeature.Action.CardDismissed(sessionID, disposition))
            val catchUpUndoTapped: Action get() = CatchUp(CatchUpFeature.Action.UndoTapped)
            val catchUpExitTapped: Action get() = CatchUp(CatchUpFeature.Action.ExitTapped)
            fun catchUpFinished(reducedMotion: Boolean): Action = CatchUp(CatchUpFeature.Action.Finished(reducedMotion))
            val catchUpOpenTapped: Action get() = CatchUp(CatchUpFeature.Action.OpenTapped)
            val catchUpMarkAllReadTapped: Action get() = CatchUp(CatchUpFeature.Action.MarkAllReadTapped)

            /** `.sessionDetail(.presented(action))`. */
            fun detail(action: SessionFeature.Action): Action = SessionDetail(PresentationAction.Presented(action))
        }
    }

    /** Cancellation identities, shared with `HomeConnectionFeature`. */
    sealed interface CancelID {
        data object Events : CancelID
        data object Refresh : CancelID
        data object ActiveAncestry : CancelID
        data object Attention : CancelID
        data object Prefetch : CancelID
        data object SelectedTranscript : CancelID
        data object DeletionError : CancelID
        data object VoiceCallCommands : CancelID
        data object DictationCommands : CancelID
        data object UnsentDrafts : CancelID
        data object SnapshotRetry : CancelID
        data object LastKnownActivity : CancelID
        data object NetworkPath : CancelID
        data object ConnectionGrace : CancelID
        data object LiveUpdatesAfterLeaving : CancelID
        data object SessionActivityWait : CancelID
        data class CachedTranscript(val sessionID: String) : CancelID
        data object LinkedSessionOpen : CancelID
        data class DeleteSession(val sessionID: String) : CancelID
        data class ViewReceipt(val sessionID: String) : CancelID
        data object SessionSearch : CancelID
        data object OlderSessions : CancelID
    }

    /** The assembled body (`HomeFeatureComposition.kt`). */
    private val body: Reducer<State, Action> by lazy { builtBody() }

    override fun reduce(state: State, action: Action): Reduced<State, Action> = body.reduce(state, action)

    companion object {
        /** Asks the user's own OpenCode to install the plugin; they review it before sending. */
        val notifyPluginSetUpPrompt: String =
            "Set up OpenCode iOS notifications on this server. Run `opencode plugin add " +
                "${OpenCodeNotifyPluginStatus.PACKAGE_ENTRY}`, which installs the plugin and adds it to my global OpenCode " +
                "config. If that command isn't available, add \"${OpenCodeNotifyPluginStatus.PACKAGE_ENTRY}\" to the \"plugins\" " +
                "array of ~/.config/opencode/opencode.json (or opencode.jsonc, whichever exists), keeping every other entry " +
                "and any comments. If the config lists \"${OpenCodeNotifyPluginStatus.LEGACY_PACKAGE_ENTRY}\", remove that entry. " +
                "Don't restart the server: OpenCode reloads config changes on its own. Then confirm a plugin with ID " +
                "\"${OpenCodeNotifyPluginStatus.PLUGIN_ID}\" is active (`opencode api get /api/plugin`). If it's listed but not " +
                "active, tell me why (the server log says) before changing anything else. Tell me if anything went wrong."

        /** Mark All as Read receipts in flight at once. */
        const val receiptConcurrency = CatchUpFeature.receiptConcurrency

        const val credentialsRejectedStatus = 401

        /** How long last-known running state is shown without a live connection. */
        val lastKnownActivityLifetime: Duration = 120.seconds

        /** How stale an unchanged idle snapshot's stamp may get before it's written again. */
        val snapshotStampInterval: Duration = 30.seconds

        /** A cached snapshot's running state may show until this deadline, if it hasn't passed. */
        fun lastKnownActivityDeadline(capturedAt: Instant?, now: Instant): Instant? {
            if (capturedAt == null || capturedAt > now) return null
            val deadline = capturedAt.plusMillis(lastKnownActivityLifetime.inWholeMilliseconds)
            return if (deadline > now) deadline else null
        }

        /** How long the event stream stays live after the app leaves the screen. */
        val liveUpdatesAfterLeaving: Duration = 30.seconds

        /** A cached session's live events, coalesced into one read this late. */
        val cachedTranscriptRefreshDelay: Duration = 2.seconds

        /** Parent links kept beyond those above running sessions. */
        const val parentLinkLimit = 512

        /** Retry delays for a failed snapshot while the stream is live, in seconds. */
        val snapshotRetryDelays: List<Int> = listOf(1, 2, 4, 8, 16, 30)

        /** How many uncached rows to warm after each snapshot. */
        const val prefetchCount = 3

        /** Bounds the in-memory transcripts. */
        const val transcriptMemoryLimit = 8

        /** How long the app stays in the background before coming back opens a new session instead of the last one viewed. */
        val newSessionAfterAway: java.time.Duration = java.time.Duration.ofMinutes(30)

        /** Bound of [State.dirtyCachedTranscriptIDs]. */
        const val dirtyCachedTranscriptLimit = 16

        /** Lookups in flight at once; the rest wait in `linkedSessionQueue`. */
        const val linkedSessionFetchLimit = 6
    }
}

/**
 * Stamps Home's cached snapshot and times how long last-known running state shows while
 * reconnecting. Tests read a fixed instant unless they set one.
 */
object HomeSnapshotDate : DependencyKey<DateClient> {
    override val liveValue: DateClient = DateClient { Instant.now() }
    override val testValue: DateClient = DateClient.constant(Instant.EPOCH)
}

/** Times leaving the app and coming back. Fixed in tests, so a return there never reads as a long absence unless a test sets it. */
object AppAwayDate : DependencyKey<DateClient> {
    override val liveValue: DateClient = DateClient { Instant.now() }
    override val testValue: DateClient = DateClient.constant(Instant.EPOCH)
}

/** The `RefreshReason`s that make a foreground read (`.open`, `.resume`, `.reconnect`). */
internal val foregroundRefreshReasons: Set<RefreshReason> = setOf(RefreshReason.Open, RefreshReason.Resume, RefreshReason.Reconnect)

// region State navigation

/**
 * Shares waiting for their composer, by target, kept by `:feature:composer`'s persistence: a share
 * outlives the app. Reads the [ComposerPersistence] dependency, so call it from a reducer.
 */
val HomeFeature.State.sharedDrafts: SharedDrafts
    get() = SharedDrafts(server.persistenceID, projects, liveComposerTargets, dependency(ComposerPersistence))

/** Composers on screen holding a draft, whose persisted draft isn't read back as a share. */
private val HomeFeature.State.liveComposerTargets: Set<SharedDraftTarget>
    get() {
        val targets = HashSet<SharedDraftTarget>()
        val detail = sessionDetail
        if (catchUp == null && detail != null && detail.readsOnView && !detail.sessionComposer.isEmpty) {
            targets += SharedDraftTarget(detail.session?.id)
        }
        for (child in subagentStack) {
            if (child.readsOnView && !child.sessionComposer.isEmpty) targets += SharedDraftTarget(child.session?.id)
        }
        val stack = catchUp
        if (stack != null && stack.returnComposer?.isEmpty == false) targets += SharedDraftTarget(stack.composerTargetSessionID)
        return targets
    }

/**
 * Moves shared items into the open composer when it is their target. Catch Up cards are left
 * alone: their composers are not kept.
 */
fun HomeFeature.State.absorbingSharedDrafts(): HomeFeature.State {
    if (sharedDrafts.isEmpty || catchUp != null) return this
    var s = this
    for (child in s.subagentStack) {
        val key = SharedDraftTarget(child.session?.id ?: continue)
        val shared = s.sharedDrafts.removeValue(key) ?: continue
        s = s.updatingSubagent(child.navigationID) { it.absorb(shared) }
        s.subagentStack.firstOrNull { it.navigationID == child.navigationID }?.let { s.syncAbsorbedDraft(it) }
    }
    val detail = s.sessionDetail ?: return s
    val key = SharedDraftTarget(detail.session?.id)
    val target = s.shareNavigationTarget
    if (target != null) {
        if (key != target || detail.isDraft != (target == SharedDraftTarget.NewSession)) return s
        s = s.copy(shareNavigationTarget = null)
    }
    val shared = s.sharedDrafts.removeValue(key) ?: return s
    val projectID = shared.projectID
    if (detail.isDraft && projectID != null) {
        val project = detail.projects.firstOrNull { it.id == projectID } ?: s.projects.firstOrNull { it.id == projectID }
        if (project != null) s = s.updatingDetail { it.copy(draftProject = project) }
    }
    s = s.updatingDetail { it.absorb(shared) }
    s.sessionDetail?.let { s.syncAbsorbedDraft(it) }
    return s
}

/** A composer that took a share is saved (or cleared) at once. */
internal fun HomeFeature.State.syncAbsorbedDraft(detail: SessionFeature.State) {
    if (catchUp != null || !detail.readsOnView) return
    dependency(ComposerPersistence).syncAbsorbedDraft(server.persistenceID, detail.session, detail.draftProject, detail.sessionComposer)
}

/** Opens the remembered session for this server, else a draft. */
fun HomeFeature.State.restoringInitialSession(): HomeFeature.State {
    if (!isWindowLocal && wasAwayLongEnoughForNewSession(dependency(AppAwayDate).now())) return openingDraft(reason = "you came back after a while")
    val rememberedID = lastViewedSessionIDs[server.persistenceID]
    val session = rememberedID?.let { id -> sessions.firstOrNull { it.id == id } }
    if (session != null) {
        return markingReadLocally(session).let {
            it.openingDetail(session, cachedItems = it.cachedTranscriptItems(session), reason = "the app restored it")
        }
    }
    // An unloaded list can't say the session is gone.
    val s = if (snapshotLoaded) updatingLastViewedSessionIDs { it - server.persistenceID } else this
    return s.openingDraft(reason = "the app restored a draft")
}

/** The next state (outgoing screen remembered) and the new screen. */
fun HomeFeature.State.makeDetail(
    session: OpenCodeSession,
    cachedItems: List<OpenCodeTranscriptItem>? = null,
    externalTranscript: Boolean = false,
    reason: String = "you opened it",
): Pair<HomeFeature.State, SessionFeature.State> {
    val (begun, transition) = beginningDetailTransition(defaultReason = reason)
    var s = begun
    var detail = SessionFeature.State.session(
        server = server,
        session = session,
        projects = projects,
        cachedItems = cachedItems,
        initialTranscriptRequestIsExternal = externalTranscript,
        isRunning = session.id in s.activeSessionIDs,
        navigationID = transition.navID,
        visit = transition.visit,
    )
    detail = s.seedingSessionSnapshot(detail)
    detail = detail.copy(streamIsHealthy = s.isEventStreamLive)
    val pending = s.pendingOpenReadState[session.id]
    if (pending != null) s = s.copy(pendingOpenReadState = s.pendingOpenReadState - session.id)
    detail = s.applyingOpening(session, pending, detail)
    if (session.id in s.unopenedSeen) s = s.copy(unopenedSeen = s.unopenedSeen - session.id)
    val pick = s.waitingPicks[session.id]
    if (pick != null) {
        s = s.copy(waitingPicks = s.waitingPicks - session.id)
        detail = detail.copy(currentModel = pick, pendingModel = pick)
    }
    // A live call follows the screen.
    val voice = transition.activeVoice?.retarget(session, reason = transition.reason)
    detail = s.finishingDetailTransition(detail, voice, transition.keepsIdentity)
    val (restored, withDraft) = s.restoringPersistedDraft(detail)
    s = restored
    if (cachedItems != null) s = s.touchingCachedTranscript(session)
    return s to withDraft
}

/** `sessionDetail = makeDetail(…)`. */
fun HomeFeature.State.openingDetail(
    session: OpenCodeSession,
    cachedItems: List<OpenCodeTranscriptItem>? = null,
    externalTranscript: Boolean = false,
    reason: String = "you opened it",
): HomeFeature.State {
    val (s, detail) = makeDetail(session, cachedItems, externalTranscript, reason)
    return s.copy(sessionDetail = detail)
}

fun HomeFeature.State.makeDraft(
    directory: String? = null,
    reason: String = "you started a new session",
    outboxDraftID: String? = null,
): Pair<HomeFeature.State, SessionFeature.State> {
    val outgoingDirectory = sessionDetail?.session?.directory
    val (begun, transition) = beginningDetailTransition(defaultReason = reason, targetIsDraft = true)
    var s = begun
    var projects = s.projectsByRecency
    // The voice agent can ask for a draft in a particular project, or in a folder no project holds yet.
    if (directory != null) {
        val index = projects.indexOfFirst { it.directory == directory }
        if (index >= 0) projects = listOf(projects[index]) + projects.filterIndexed { i, _ -> i != index }
    }
    var draft = SessionFeature.State.draft(
        server = s.server,
        draftProject = directory?.let { OpenCodeProject.folder(it, projects) } ?: projects.firstOrNull(),
        projects = projects,
        navigationID = transition.navID,
        visit = transition.visit,
    ).copy(outboxDraftID = outboxDraftID)
    val voice = transition.activeVoice?.let { current ->
        val callDirectory = draft.draftProject?.directory ?: outgoingDirectory ?: current.target.directory
        current.retargetToDraft(directory = callDirectory, model = draft.currentModel, reason = transition.reason)
    }
    draft = s.finishingDetailTransition(draft, voice, transition.keepsIdentity)
    val (restored, withDraft) = s.restoringPersistedDraft(draft, preservesExplicitProject = directory != null)
    s = restored
    return s to withDraft
}

/** An unsent new session from the list: a draft on its own outbox lane, which shows its messages. */
fun HomeFeature.State.openingUnsentDraft(unsent: UnsentDraft): HomeFeature.State {
    val open = sessionDetail
    if (catchUp == null && open != null && open.session == null && open.outboxDraftID == unsent.draftID) return this
    val (s, draft) = makeDraft(unsent.directory.ifEmpty { null }, reason = "you opened it", outboxDraftID = unsent.draftID)
    return s.copy(sessionDetail = draft)
}

/** The unsent session whose row is [sessionID], unless the server has the session by now. */
fun HomeFeature.State.unsentDraft(sessionID: String): UnsentDraft? {
    if (sessions.any { it.id == sessionID }) return null
    return unsentDrafts.firstOrNull { it.sessionID == sessionID }
}

/** `sessionDetail = makeDraft(…)`. */
fun HomeFeature.State.openingDraft(directory: String? = null, reason: String = "you started a new session"): HomeFeature.State {
    val (s, draft) = makeDraft(directory, reason)
    return s.copy(sessionDetail = draft)
}

/**
 * The server has [model] for [sessionID]: the list's copy says so. Its snapshot (a session
 * visited before) and a pick waiting for its screen follow: a pick it matches no longer waits,
 * and an explicit switch ([replacesPick], the voice delegator's) replaces any.
 */
fun HomeFeature.State.notingModel(model: OpenCodeModelRef, sessionID: String, replacesPick: Boolean = false): HomeFeature.State {
    var s = updatingSession(sessionID) { it.copy(model = model) }
    if (replacesPick || s.waitingPicks[sessionID] == model) s = s.copy(waitingPicks = s.waitingPicks - sessionID)
    s = s.updatingSubagents { child ->
        var next = child
        if (replacesPick && next.session?.id == sessionID) next = next.copy(currentModel = model, pendingModel = null)
        next.modelApplied(model, sessionID)
    }
    return s.updatingSnapshot(sessionID) { snapshot ->
        var next = snapshot.copy(
            session = snapshot.session.copy(model = model),
            family = snapshot.family.map { if (it.id == sessionID) it.copy(model = model) else it },
        )
        if (replacesPick || next.pendingModel == model) next = next.copy(pendingModel = null)
        if (next.pendingModel == null) next = next.copy(currentModel = model)
        next
    }
}

internal data class DetailTransition(
    val navID: Int,
    val visit: Int,
    val reason: String,
    /** The outgoing screen's call, while it isn't over: it follows the screen. */
    val activeVoice: VoiceFeature.State?,
    val keepsIdentity: Boolean,
)

private fun HomeFeature.State.beginningDetailTransition(
    defaultReason: String,
    targetIsDraft: Boolean = false,
): Pair<HomeFeature.State, DetailTransition> {
    // Another session replaces the root: whatever was pushed over it goes.
    var s = poppingSubagents(toDepth = 0, reason = nextMoveReason).rememberingSession().syncingOutgoingDetailDraft(targetIsDraft)
    val reason = s.nextMoveReason ?: defaultReason
    // Keeping the identity keeps a live call's or recording's effects alive.
    val activeVoice = s.sessionDetail?.voice?.takeIf { !it.phase.isTerminal }
    val keepsIdentity = activeVoice != null || s.sessionDetail?.carriedDictation != null
    // Every move counts, identity kept or not (`selectedTranscriptFetch`).
    val fresh = s.detailGeneration + 1
    s = s.copy(nextMoveReason = null, detailGeneration = fresh)
    val navID = if (keepsIdentity) (s.sessionDetail?.navigationID ?: fresh) else fresh
    return s to DetailTransition(navID, fresh, reason, activeVoice, keepsIdentity)
}

private fun HomeFeature.State.finishingDetailTransition(
    detail: SessionFeature.State,
    activeVoice: VoiceFeature.State?,
    keepsIdentity: Boolean,
): SessionFeature.State {
    var next = detail
    if (activeVoice != null) {
        next = next.copy(
            voice = activeVoice.copy(
                replyWatch = activeVoice.replyWatch.copy(activeSessionIDs = activeSessionIDs),
                viewing = next.browser.viewing,
            ),
        )
    }
    next = next.reconcileInvariants()
    val outgoing = sessionDetail
    // The screen swaps details in place without appearing again, so the new one inherits whether it is on screen.
    next = next.copy(isVisible = outgoing?.isVisible ?: false, inheritsLiveWork = keepsIdentity)
    // Its timers wait, as the last one's did, for the server and the return.
    next = next.withRequestsConnectionIsAvailable(isConnected).copy(
        isSceneActive = outgoing?.isSceneActive ?: true,
        isBackgrounded = outgoing?.isBackgrounded ?: false,
    )
    // The same identity names the Working line's runs, so their numbers carry on.
    if (keepsIdentity) next = next.copy(workingRun = next.workingRun.copy(counter = outgoing?.workingRun?.counter ?: 0))
    // Raise to Talk's grace after a recording follows the screen too.
    next = next.withDictationEndedAt(outgoing?.dictationEndedAt)
    return carryingDictation(next)
}

/**
 * A recording follows the screen to the next session, as a call does: the new detail keeps the old
 * one's identity (see [makeDetail]), so the recording's effect lives on, and it is sent wherever it
 * is when Send is tapped. What was typed beside it stays with its own session and is back in the
 * composer there. Catch Up keeps its own composer.
 */
private fun HomeFeature.State.carryingDictation(detail: SessionFeature.State): SessionFeature.State {
    val old = sessionDetail ?: return detail
    val carried = old.carriedDictation ?: return detail
    if (catchUp == null && old.readsOnView) parkingDraft(old.sessionComposer, old.session?.id)
    return detail.copy(isSceneActive = old.isSceneActive).adoptDictation(carried)
}

/**
 * The outgoing screen's composer waits for its session. New Session over a draft starts empty: the
 * old draft's words go. A screen carrying a recording leaves its composer to [carryingDictation].
 */
private fun HomeFeature.State.syncingOutgoingDetailDraft(targetIsDraft: Boolean = false): HomeFeature.State {
    val old = sessionDetail ?: return this
    if (catchUp != null || !old.readsOnView || old.carriedDictation != null) return this
    val persistence = dependency(ComposerPersistence)
    if (targetIsDraft && old.isDraft) {
        persistence.clearDraft(server.persistenceID, null, false)
        return this
    }
    if (old.sessionComposer.isEmpty) return this
    if (persistence.sharedDraft(server.persistenceID, old.session?.id) != null) {
        parkingDraft(old.sessionComposer, old.session?.id)
    } else {
        syncAbsorbedDraft(old)
    }
    return this
}

/**
 * A relaunch brings back the session's waiting model pick, unless the server's list (once loaded)
 * no longer offers it.
 */
internal fun HomeFeature.State.restoringPersistedPick(detail: SessionFeature.State): SessionFeature.State =
    dependency(ComposerPersistence).restorePersistedPick(
        serverID = server.persistenceID,
        session = detail.session,
        pendingModel = detail.pendingModel,
        hasSnapshot = detail.session?.let { sessionSnapshots[it.id] } != null,
        models = detail.models,
    )?.let { pick -> detail.copy(currentModel = pick, pendingModel = pick) } ?: detail

/**
 * A parked or persisted composer comes back with its session, and a draft whose send already
 * created its session becomes that session.
 */
private fun HomeFeature.State.restoringPersistedDraft(
    detail: SessionFeature.State,
    preservesExplicitProject: Boolean = false,
): Pair<HomeFeature.State, SessionFeature.State> {
    // Catch Up's cards keep no composer: the parked one waits for the session's own screen.
    if (catchUp != null || !detail.readsOnView) return this to detail
    val persistence = dependency(ComposerPersistence)
    var s = this
    var next = detail
    next = restoringPersistedPick(next)
    if (next.session == null && next.outboxDraftID != null) {
        val entry = persistence.loadOutbox(server.persistenceID, null).firstOrNull { (it.draftID ?: it.pending.messageID) == next.outboxDraftID }
        if (entry != null) {
            val alreadyInOutbox = dependency(PromptOutbox).contains(entry.pending.messageID)
            // The prompt it was created with names it until the server does.
            val created = entry.createdSession?.learningFirstPrompt(SessionTitle.preview(entry.pending.text))
            val entryDirectory = entry.directory
            if (!alreadyInOutbox && created != null) {
                if (s.sessions.none { it.id == created.id }) s = s.copy(sessions = listOf(created) + s.sessions)
                s = s.updatingLastViewedSessionIDs { it + (server.persistenceID to created.id) }
                next = next.copy(
                    draftBecameSessionID = created.id,
                    session = created,
                    familySessions = listOf(created),
                    familyRootID = created.id,
                    outboxDraftID = null,
                )
                persistence.promoteDraftOutboxToSession(server.persistenceID, entry.draftID, created.id)
                val nilDraft = persistence.loadDraft(server.persistenceID, null)
                if (nilDraft != null) {
                    persistence.clearDraft(server.persistenceID, null, false)
                    if (persistence.loadDraft(server.persistenceID, created.id) == null) {
                        persistence.scheduleDraft(server.persistenceID, created.id, null, nilDraft.draft)
                    }
                }
            } else if (!preservesExplicitProject && !entryDirectory.isNullOrEmpty()) {
                next = next.copy(draftProject = OpenCodeProject.folder(entryDirectory, next.projects))
            }
        }
    }
    if (!next.sessionComposer.isEmpty) return s to next
    if (persistence.sharedDraft(server.persistenceID, next.session?.id) != null) return s to next
    val restored = persistence.loadDraft(server.persistenceID, next.session?.id) ?: return s to next
    val explicitProject = next.draftProject
    next = next.applyPersistedDraft(restored)
    if (preservesExplicitProject && explicitProject != null) next = next.copy(draftProject = explicitProject)
    return s to next
}

fun HomeFeature.State.rememberingSession(): HomeFeature.State = sessionDetail?.let(::remembering) ?: this

fun HomeFeature.State.remembering(detail: SessionFeature.State): HomeFeature.State {
    val snapshot = SessionFeature.SwitchSnapshot.from(detail) ?: return this
    val id = snapshot.session.id
    if (id in recentlyDeletedIDs) return this
    if (sessions.none { it.id == id } && snapshot.session.parentID == null) return this
    val snapshots = sessionSnapshots.toMutableMap()
    snapshots[id] = snapshot
    // Deltas update the detail between cache publications. On leaving, keep the words now on screen
    // (or the row that just settled them).
    val cached = if (detail.items.takeLast(SessionFeature.initialFetchLimit).any { it.isInProgressText } ||
        transcriptCache[id]?.any { it.isInProgressText } == true
    ) publishingTranscript(id, detail.cacheableItems) else this
    val watermarked = cached.syncingSeenWatermark(detail, id)
    val rootID = detail.familyRootID
    if (rootID != null && rootID != id) {
        snapshots[rootID]?.let { root ->
            snapshots[rootID] = root.copy(
                family = snapshot.family,
                familyReadAt = snapshot.familyReadAt,
                permissions = snapshot.permissions,
                forms = snapshot.forms,
                formAnswers = snapshot.formAnswers,
                questionPageIndices = snapshot.questionPageIndices,
                collapsedRequests = snapshot.collapsedRequests,
                answerDrafts = snapshot.answerDrafts,
                hasLoadedRequests = snapshot.hasLoadedRequests,
                completedRequests = snapshot.completedRequests,
                selectedRequest = snapshot.selectedRequest,
            )
        }
    }
    val order = (sessionSnapshotOrder - id + id).toMutableList()
    while (order.size > HomeFeature.transcriptMemoryLimit) snapshots.remove(order.removeAt(0))
    return watermarked.copy(sessionSnapshots = snapshots, sessionSnapshotOrder = order)
}

/**
 * Pushes [session], a subagent of the screen on top, over it. Its first frame comes from what the
 * parent already knows (its transcript, its own subagents, their requests), so nothing new is
 * read to show it.
 */
fun HomeFeature.State.pushingSubagent(session: OpenCodeSession, reason: String? = null): HomeFeature.State {
    val parent = topDetail ?: return this
    if (parent.session?.id == session.id) return this
    val pushedCount = pushedDetailCount + 1
    var s = copy(pushedDetailCount = pushedCount)
    var detail = SessionFeature.State.session(
        server = server,
        session = session,
        projects = projects,
        cachedItems = parent.familyTranscriptCache[session.id] ?: s.cachedTranscriptItems(session),
        isRunning = session.id in s.activeSessionIDs || session.id in parent.activeFamilySessionIDs,
        navigationID = -pushedCount,
    )
    detail = s.seedingSessionSnapshot(detail)
    detail = detail.copy(streamIsHealthy = s.isEventStreamLive || parent.streamIsHealthy)
    detail = detail.withRequestsConnectionIsAvailable(parent.requestsConnectionIsAvailable)
        .copy(isBackgrounded = parent.isBackgrounded)
    detail = s.applyingOpening(session, detail = detail)
    if (session.id in s.unopenedSeen) s = s.copy(unopenedSeen = s.unopenedSeen - session.id)
    val pick = s.waitingPicks[session.id]
    if (pick != null) {
        s = s.copy(waitingPicks = s.waitingPicks - session.id)
        detail = detail.copy(currentModel = pick, pendingModel = pick)
    } else if (s.sessionSnapshots[session.id] == null) {
        parent.familyPendingModels[session.id]?.let { detail = detail.copy(currentModel = it, pendingModel = it) }
    }
    val known = detail.familySessions.mapTo(HashSet()) { it.id }
    detail = detail.copy(
        familySessions = detail.familySessions +
            SessionActivity.descendants(session.id, parent.familySessions).filter { it.id !in known },
    )
    if (detail.familyReadAt == null) detail = detail.copy(familyReadAt = parent.familyReadAt)
    // Its tree's running jobs, until its own list answers.
    if (detail.jobs.isEmpty()) {
        val tree = detail.familySessions.mapTo(HashSet()) { it.id } + session.id
        detail = detail.copy(jobs = parent.jobs.filterValues { it.ownerSessionID in tree })
    }
    if (!detail.hasLoadedPendingRequests) {
        val members = detail.familySessions.mapTo(HashSet()) { it.id }
        val forms = parent.formRequests.filter { it.sessionID in members }
        val formIDs = forms.mapTo(HashSet()) { it.id }
        detail = detail.withPermissionRequests(parent.permissionRequests.filter { it.sessionID in members })
            .withFormRequests(forms)
            .withFormAnswers(parent.formAnswers.filterKeys { it in formIDs })
            // A question folded or paged in the parent stays so in its subagent.
            .withQuestionPageIndices(parent.questionPageIndices.filterKeys { it in formIDs })
            .updateRequestDock { it.copy(collapsedRequestIDs = parent.requestDock.collapsedRequestIDs.intersect(formIDs)) }
            .copy(hasLoadedPendingRequests = parent.hasLoadedPendingRequests)
    }
    detail = detail.copy(
        activeFamilySessionIDs = detail.activeFamilySessionIDs + parent.activeFamilySessionIDs,
        callsFromRoot = true,
        isVisible = s.sessionDetail?.isVisible ?: false,
        isSceneActive = s.sessionDetail?.isSceneActive ?: true,
    )
    // A request it inherits docks from the first frame, as a restored snapshot's does, so its
    // parked draft comes back as the message.
    if (detail.wantsRequestDock && !detail.isAnsweringRequest) detail = detail.beginAnswering()
    val (restored, withDraft) = s.restoringPersistedDraft(detail)
    s = restored.copy(subagentStack = restored.subagentStack + withDraft)
    return s.markingTopScreen().absorbingSharedDrafts().retargetingCallToTop(reason = reason ?: "you opened a subagent")
}

/** Only the screen on top of the stack plays a request's arrival. */
private fun HomeFeature.State.markingTopScreen(): HomeFeature.State {
    val covered = subagentStack.isNotEmpty()
    val top = subagentStack.lastOrNull()?.navigationID
    return updatingDetail { it.copy(isCovered = covered) }
        .updatingSubagents { it.copy(isCovered = it.navigationID != top) }
}

/**
 * Takes pushed subagents off down to [toDepth]. Their composers wait for their sessions like shared
 * items, their transcripts stay with the parent for the next push, and a recording there ends
 * with its words.
 */
fun HomeFeature.State.poppingSubagents(toDepth: Int, reason: String? = null): HomeFeature.State {
    val targetDepth = maxOf(toDepth, 0)
    if (subagentStack.size <= targetDepth) return this
    var s = copy(
        subagentStack = subagentStack.mapIndexed { index, child ->
            if (index < targetDepth) return@mapIndexed child
            val left = if (child.hasRenderedFirstContentFrame && child.isSceneActive) child.noteLeaving() else child
            left.copy(isVisible = false)
        },
    )
    while (s.subagentStack.size > targetDepth) {
        var child = s.subagentStack.last()
        s = s.copy(subagentStack = s.subagentStack.dropLast(1))
        // A recording's words so far live in its hold until it ends: they park with the composer,
        // as a call taking the mic keeps them.
        child.dictationHold?.let { child = child.withDraft(it.text) }
        s = s.remembering(child)
        val sessionID = child.session?.id ?: continue
        // While its bar answers a request, the message is the one parked aside; the bar's words are
        // an answer, never a message draft.
        s = s.parkingDraft(child.parkedComposer ?: child.draftState, sessionID)
        val items = child.cacheableItems.takeLast(SessionFeature.initialFetchLimit)
        val parentID = s.subagentStack.lastOrNull()?.navigationID
        s = if (parentID != null) {
            s.updatingSubagent(parentID) { it.copy(familyTranscriptCache = it.familyTranscriptCache + (sessionID to items)) }
        } else {
            s.updatingDetail { it.copy(familyTranscriptCache = it.familyTranscriptCache + (sessionID to items)) }
        }
    }
    return s.markingTopScreen().retargetingCallToTop(reason = reason ?: "you went back")
}

/** Shared by the selected detail and Catch Up's inert next-card preview. */
fun HomeFeature.State.seedingSessionSnapshot(detail: SessionFeature.State): SessionFeature.State {
    val session = detail.session ?: return detail
    val snapshot = sessionSnapshots[session.id] ?: return detail
    if (snapshot.session.directory != session.directory) return detail
    // Home receives run events even while this session is off screen.
    val restored = snapshot.restore(detail).copy(activeFamilySessionIDs = activeSessionIDs)
    return if (!isConnected) restored.copy(familyReadAt = null) else restored
}

/**
 * Parks [composer] for [sessionID] (or the draft) until its screen comes back:
 * `:feature:composer`'s persistence keeps it, a New Session draft with its project.
 */
fun HomeFeature.State.parkingDraft(composer: DraftState?, sessionID: String?): HomeFeature.State {
    if (composer == null) return this
    dependency(ComposerPersistence).parkPersistedDraft(server.persistenceID, composer, sessionID, sessionDetail?.draftProject)
    return this
}

// endregion

/** What Home's helpers return. */
internal typealias HomeReduced = Reduced<HomeFeature.State, HomeFeature.Action>
