package ai.opencode.app

import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.log.AppLog
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sharing.Shared
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.installHistory
import ai.opencode.core.sharing.shared
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.store.Effect
import ai.opencode.core.store.PresentationAction
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.store.cancellable
import ai.opencode.core.store.combine
import ai.opencode.core.store.ifLet
import ai.opencode.core.store.onChange
import ai.opencode.core.store.scope
import ai.opencode.core.strings.localized
import ai.opencode.core.ui.ThisDevice
import ai.opencode.feature.drawer.HomeConnectionFeature
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.feature.drawer.InboxSidebarFeature
import ai.opencode.feature.drawer.keepingSettings
import ai.opencode.feature.drawer.persistingSnapshot
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.session.SessionFeature
import ai.opencode.feature.settings.onboarding.WelcomeFeature
import ai.opencode.feature.system.NotificationDestination
import ai.opencode.feature.system.SessionDirectory
import ai.opencode.feature.system.SystemAction
import ai.opencode.feature.system.SystemIntegrationClient
import ai.opencode.feature.system.clienttools.ClientToolsFeature
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.SessionLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.system.share.ReceivedShare
import ai.opencode.feature.system.share.ShareInboxClient
import ai.opencode.feature.voice.VoiceOpenSessionFeature
import ai.opencode.feature.voice.ScreenAwakeClient
import ai.opencode.feature.voice.VoiceCallLogClient
import ai.opencode.feature.voice.VoiceCallServiceClient
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.server.CachedHomeSnapshot
import ai.opencode.server.ConnectionFailureDetail
import ai.opencode.server.LocalNetworkPermission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.ServerSharedKeys
import ai.opencode.server.friendlyName
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.keepingFirstPrompts
import ai.opencode.server.remember
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Routes between the connection screen and Home, owns the manual server form, pairing (scan, paste,
 * opened links with confirmation), saved-server edit and removal, the connect → health → Home
 * snapshot state machine, and routes notification taps and system actions to Home.
 *
 * Home is the drawer's [HomeFeature]; its delegates (server switch, rename, edit, removal,
 * pairing, adding) are handled in [home]. Shares from the share sheet are `AppFeatureShares.kt`.
 * See `docs/parity/app.md` for what is still missing (the session screen's parts, round 2b).
 */
class AppFeature : Reducer<AppFeature.State, AppFeature.Action> {
    data class State(
        val serverURL: String = "",
        val username: String = "opencode",
        val password: String = "",
        /** Blank shows the server's default name. */
        val serverName: String = "",
        /** The form's header rows, blank ones included while being typed. */
        val headers: List<OpenCodeServer.Header> = emptyList(),
        /** The last manual entry that failed, restored when the form reopens. */
        val manualEntryDraft: OpenCodeServer? = null,
        /** The server that was open when the user left it to pick another. */
        val returnServer: OpenCodeServer? = null,
        /** Why the welcome's Paste didn't pair, shown beside it. */
        val pasteError: String? = null,
        /** The first-run welcome, with no saved server (Welcome Preview). */
        val welcome: WelcomeFeature.State = WelcomeFeature.State(),
        /** The phone's client tools' socket (Share Screen with Agents and the other call tools). */
        val clientTools: ClientToolsFeature.State = ClientToolsFeature.State(),
        /** Its second socket, for a call's delegator session in another directory. */
        val delegatorClientTools: ClientToolsFeature.State = ClientToolsFeature.State(slot = ClientToolsFeature.State.Slot.Delegator),
        /** Where that delegator session lives, once read. */
        val voiceDelegatorLocation: ClientToolsLocation? = null,
        val voiceOpenSession: VoiceOpenSessionFeature.State = VoiceOpenSessionFeature.State(),
        val connection: Connection = Connection.Idle,
        val connectionGeneration: Int = 0,
        val connectionAttempt: ConnectionAttempt? = null,
        val home: HomeFeature.State? = null,
        /** A tapped notification's session, opened once its server's home loads. */
        val pendingNotification: NotificationDestination? = null,
        val notificationError: String? = null,
        /** An intent, shortcut or link, waiting for its server's home to load. */
        val pendingSystemAction: SystemAction? = null,
        val systemActionError: String? = null,
        /** Share-sheet items whose server is still connecting. */
        val pendingShares: List<ReceivedShare> = emptyList(),
        val shareError: String? = null,
        /** Not in the background. */
        val isInForeground: Boolean = true,
        /** Manual entry is opt-in; first launch presents the pairing actions. */
        val isEditingServer: Boolean = false,
        /** The saved entry being edited, replaced only once the edited target loads. */
        val editingSavedServer: OpenCodeServer? = null,
        /** Addresses discovered from a pairing code, tried alongside `serverURL`. */
        val alternateURLs: List<String> = emptyList(),
        /** The pairing code's own addresses, preferred over the others (`OpenCodeServer.preferredURLs`). */
        val preferredURLs: List<String> = emptyList(),
        val isScanningPairingCode: Boolean = false,
        val pendingPairing: PendingPairing? = null,
        val ignoredPendingPairingCount: Int = 0,
        val pairingError: String? = null,
        val savedServer: Shared<OpenCodeServer?>,
        val savedServers: Shared<List<OpenCodeServer>>,
        val cachedHomeSnapshots: Shared<Map<String, CachedHomeSnapshot>>,
        val lastViewedSessionIDs: Shared<Map<String, String>>,
    ) {
        sealed interface Connection {
            data object Idle : Connection
            /** Redeeming a one-time `opencode pair` link for a session token. */
            data object Pairing : Connection
            data object Connecting : Connection
            data class LoadingHome(val server: OpenCodeServer) : Connection
            data object Connected : Connection
            data class Failed(
                val message: String,
                val phase: FailureContext.Phase = FailureContext.Phase.Connect,
                val context: FailureContext? = null,
                /** Android 17's local network access is off for this server; Settings can turn it on. */
                val localNetworkDenied: Boolean = false,
            ) : Connection
            /** Left the session list on purpose to pick a server; must not auto-reconnect. */
            data object Picking : Connection
        }

        val knownServers: List<OpenCodeServer> get() = savedServers.value + listOfNotNull(savedServer.value)

        val homeServer: OpenCodeServer? get() = home?.server

        /**
         * Cold launch keeps the splash until there's something to show: no Home; an open session's
         * rows or its first content frame; a draft at once; else Home's list. A failed connection
         * shows at once too; the splash gives up after `LaunchBloomController.TIMEOUT_MILLIS` (1.5
         * s) regardless.
         */
        val isColdLaunchContentReady: Boolean
            get() {
                val home = home ?: return true
                if (connection is Connection.Failed) return true
                val detail = home.sessionDetail ?: return home.snapshotLoaded
                if (detail.session == null) return true
                return detail.items.isNotEmpty() || detail.hasRenderedFirstContentFrame
            }

        /** Home has heard from its server once. */
        val isHomeSnapshotLoaded: Boolean get() = home?.snapshotLoaded == true

        /** A blank username field means the server's default user, like the web app. */
        val server: OpenCodeServer
            get() {
                val name = serverName.trim()
                return OpenCodeServer(
                    url = serverURL,
                    username = if (username.isBlank()) "opencode" else username,
                    password = password,
                    alternateURLs = alternateURLs,
                    name = name.ifEmpty { null },
                    headers = headers.mapNotNull { header ->
                        val headerName = header.name.trim()
                        if (headerName.isEmpty()) null else OpenCodeServer.Header(headerName, header.value.trim())
                    },
                    preferredURLs = preferredURLs,
                )
            }

        /** Why the form's headers can't be sent, if they can't. */
        val headerProblem: String?
            get() {
                server.headers.firstOrNull { !it.hasValidName }?.let { return localized(R.string.app_server_form_header_invalid_name, it.name) }
                server.headers.firstOrNull { !it.hasValidValue }?.let { return localized(R.string.app_server_form_header_invalid_value, it.name) }
                return null
            }

        /** Placeholder for the Name field: the address's friendly name once typed, or "Optional". */
        val credentialNamePrompt: String
            get() {
                val address = withDefaultScheme(serverURL.trim())
                if (address.isEmpty()) return localized(R.string.app_server_form_name_placeholder)
                var target = server.copy(name = null, url = address)
                val original = editingSavedServer
                if (original != null && address != original.url) target = target.copy(alternateURLs = emptyList(), preferredURLs = emptyList())
                return target.friendlyName
            }

        val connectingServerID: String?
            get() = when (val connection = connection) {
                Connection.Pairing, Connection.Connecting -> connectionAttempt?.server?.id
                is Connection.LoadingHome -> connection.server.id
                else -> null
            }

        val connectionProgressLabel: String?
            get() = when (connection) {
                Connection.Pairing -> localized(R.string.app_connection_progress_pairing)
                Connection.Connecting -> localized(R.string.app_connection_progress_connecting)
                is Connection.LoadingHome -> localized(R.string.app_connection_progress_loading_sessions)
                else -> null
            }

        /**
         * Anything shown over Home, from the app or from Home: [presentsOverApp],
         * `HomeFeature.State.presentsOverHome`, and the open session's own presentations
         * (`sessionDetail.presentsOverSession`). The server switch toast isn't one.
         */
        val isPresentingAnything: Boolean
            get() = presentsOverApp || home?.presentsOverHome == true || home?.presentsOverSession == true

        /**
         * Anything the app itself shows over Home: the servers and setup sheets, the pairing scan
         * and confirmation, and the share, notification and system action alerts. Read by what
         * must not appear beneath it (the drawer, a client tool's consent card).
         */
        val presentsOverApp: Boolean
            get() = isEditingServer || isScanningPairingCode || pendingPairing != null || pairingError != null ||
                shareError != null || notificationError != null || systemActionError != null

        /** A call that is connecting or connected keeps the screen on, but only in the foreground. */
        val keepsScreenAwake: Boolean get() = isInForeground && home?.hasLiveCall == true

        /** A call is on while the app is in the background, where nobody sees it. */
        val callIsOutOfSight: Boolean get() = !isInForeground && home?.sessionDetail?.voice != null

        val errorMessage: String? get() = (connection as? Connection.Failed)?.message
        val errorContext: FailureContext? get() = (connection as? Connection.Failed)?.context
        val errorPhase: FailureContext.Phase? get() = (connection as? Connection.Failed)?.let { it.context?.phase ?: it.phase }
        val errorNeedsLocalNetwork: Boolean get() = (connection as? Connection.Failed)?.localNetworkDenied == true

        /** Fills the connection form with a complete target. */
        fun load(server: OpenCodeServer): State = copy(
            serverURL = server.url,
            username = server.username,
            password = server.password,
            alternateURLs = server.alternateURLs,
            preferredURLs = server.preferredURLs,
            serverName = server.name ?: "",
            headers = server.headers,
        )

        /** Generated only when copying: captured safe context, never form credentials. */
        fun errorReport(appVersion: String, build: String, generatedAt: java.time.Instant): String? {
            val message = errorMessage ?: return null
            val lines = mutableListOf(
                "OpenCode connection error",
                "App: OpenCode $appVersion ($build)",
                "Report generated at: ${java.time.format.DateTimeFormatter.ISO_INSTANT.format(generatedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))}",
            )
            errorContext?.let { context ->
                lines += listOf("Phase: ${context.phase.rawValue}", "Server: ${context.target}", "Attempt: ${context.attempt}")
                if (context.details.isNotEmpty()) {
                    if (context.phase == FailureContext.Phase.Connect) lines += listOf("", "Couldn’t connect to ${context.friendlyName}")
                    lines += listOf("", context.detailText)
                }
            }
            return (lines + listOf("", "Message:", message)).joinToString("\n")
        }

        companion object {
            /** Restores the last server and its cached Home so launch shows it at once. */
            fun initial(): State {
                migrateBeforeRestore()
                val saved = shared(ServerSharedKeys.savedServer)
                val cached = shared(ServerSharedKeys.cachedHomeSnapshots)
                var state = State(
                    savedServer = saved,
                    savedServers = shared(ServerSharedKeys.savedServers),
                    cachedHomeSnapshots = cached,
                    lastViewedSessionIDs = shared(SharedKeys.lastViewedSessionIDs),
                )
                val server = saved.value ?: return state
                state = state.load(server)
                val cache = cached.value[server.persistenceID]
                state = state.withHome(server, cache?.snapshot)
                // A restored Home reconnects, and its cached run state expires on its own.
                val home = state.home ?: return state
                return state.copy(
                    home = home.copy(
                        connection = home.connection.copy(
                            connectionStatus = HomeConnectionFeature.State.ConnectionStatus.reconnecting,
                            lastKnownActivityDeadline = HomeFeature.lastKnownActivityDeadline(cache?.capturedAt, dependency(HomeSnapshotDate).now()),
                        ),
                    ),
                )
            }
        }

        internal fun withHome(server: OpenCodeServer, snapshot: OpenCodeHomeSnapshot?): State = copy(
            home = HomeFeature.State(
                server = server,
                snapshot = snapshot ?: OpenCodeHomeSnapshot(projects = emptyList(), sessions = emptyList(), activeSessionIDs = emptySet()),
                loaded = snapshot != null,
            ),
        )

        internal fun withoutHome(): State = copy(home = null)

        /**
         * Whether Home can stay up while another server connects: it shows the switch in the drawer
         * ([HomeFeature.State.switchingTo], set by [showingSwitch]), and that server's Home
         * replaces it when ready. Not for the server in use (a reconnect, or an edit of it), nor
         * during a call or a recording (`voiceCallActivity`/`dictationActivity`), which end with
         * this Home.
         */
        fun keepsHome(switchingTo: OpenCodeServer): Boolean {
            val home = home ?: return false
            if (home.server.persistenceID == switchingTo.persistenceID) return false
            if (editingSavedServer?.persistenceID == home.server.persistenceID) return false
            // A call or a recording belongs to this Home and ends with it.
            return !home.hasLiveCallOrRecording
        }

        /** The kept Home shows the switch to [server]. */
        fun showingSwitch(server: OpenCodeServer): State = copy(home = home?.copy(switchingTo = server))
    }

    /**
     * An unsolicited pairing link (an opened link, a transcript or browser tap) waiting for
     * explicit confirmation before redeeming, navigating, or replacing saved credentials.
     */
    data class PendingPairing(
        val id: String,
        val link: OpenCodePairingLink,
        val replacedServerNames: List<String> = emptyList(),
    ) {
        val replacedServerName: String? get() = replacedServerNames.firstOrNull()

        val host: String get() = link.server.host

        /** Distinct display hosts across `link.redeemOrigins`, preserving order. */
        val hosts: List<String>
            get() {
                val seen = HashSet<String>()
                return link.redeemOrigins
                    .map { OpenCodeServer(url = it, username = "opencode", password = "").host }
                    .filter { seen.add(it.lowercase()) }
            }

        val title: String get() = localized(R.string.app_pairing_confirm_title, host)

        val message: String
            get() {
                val allHosts = hosts
                val action = when (allHosts.size) {
                    0, 1 -> if (link.redeemOrigins.size > 1) {
                        localized(R.string.app_pairing_confirm_try_one, host)
                    } else {
                        localized(R.string.app_pairing_confirm_pair_one, host)
                    }
                    2 -> localized(R.string.app_pairing_confirm_try_two, allHosts[0], allHosts[1])
                    else -> {
                        val leading = allHosts.dropLast(1).joinToString(", ")
                        localized(R.string.app_pairing_confirm_try_many, leading, allHosts.last())
                    }
                }
                if (replacedServerNames.isEmpty()) return action
                return localized(R.string.app_pairing_confirm_replaces, action, replacedServerNames.joinToString(", "))
            }
    }

    /** Form edits cannot change the target of work already in flight. */
    data class ConnectionAttempt(
        val id: Int,
        val server: OpenCodeServer,
        /** A pairing: where its Home lands, and its handshake ([landPairing]). */
        val pairing: PairingArrival? = null,
        /** Server Flow: a drawer switch, which goes back to the Home it left on failure. */
        val switchesBack: Boolean = false,
    )

    /** Safe to display or copy: no full URL, credentials, or raw network payload. */
    data class FailureContext(
        val phase: Phase,
        val target: String,
        val attempt: Int,
        /** The server's name, when it says more than the address does. */
        val name: String?,
        val details: List<ConnectionFailureDetail>,
        val friendlyName: String,
    ) {
        enum class Phase(val rawValue: String) {
            Pair("Pair"), Connect("Connect"), LoadSessions("Load sessions");

            /** The phase as the error card shows it; [rawValue] stays English for the copied report. */
            val title: String
                get() = when (this) {
                    Pair -> localized(R.string.app_error_phase_pair)
                    Connect -> localized(R.string.app_error_phase_connect)
                    LoadSessions -> localized(R.string.app_error_phase_load_sessions)
                }
        }

        /** For the error card: the name with the address, or just the address. */
        val serverLabel: String get() = name?.let { "$it ($target)" } ?: target

        val detailText: String
            get() = details.withIndex().joinToString("\n\n") { (index, detail) ->
                if (details.size > 1) localized(R.string.app_error_candidate_detail, index + 1, detail.text) else detail.text
            }

        companion object {
            private val ALLOWED_HOST = Regex("^[A-Za-z0-9.\\-:\\[\\]]+$")

            /** Host and port only; anything else reads "Unavailable (invalid address)". */
            fun safeTarget(address: String): String = validTarget(address) ?: localized(R.string.app_error_invalid_address)

            /** Host and port only, or null when the address isn't a valid one. */
            private fun validTarget(address: String): String? {
                val uri = runCatching { java.net.URI(address) }.getOrNull() ?: return null
                if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
                val host = uri.host?.takeIf { it.isNotEmpty() && ALLOWED_HOST.matches(it) } ?: return null
                val port = uri.port.takeIf { it != -1 }
                if (port != null && port !in 1..65535) return null
                val hostname = if (host.contains(":") && !host.startsWith("[")) "[$host]" else host
                return port?.let { "$hostname:$it" } ?: hostname
            }

            fun of(server: OpenCodeServer, phase: Phase, attempt: Int, details: List<ConnectionFailureDetail> = emptyList()): FailureContext {
                val target = safeTarget(server.url)
                val chosen = server.name?.trim().orEmpty()
                val valid = validTarget(server.url) != null
                val name = if (chosen.isNotEmpty()) chosen else if (valid) server.displayName else localized(R.string.app_server_fallback_name)
                return FailureContext(
                    phase = phase,
                    target = target,
                    attempt = attempt,
                    name = if (name == OpenCodeServer.machineName(target)) null else name,
                    details = details.sortedBy { it.endpoint },
                    friendlyName = if (chosen.isNotEmpty()) chosen else if (valid) server.friendlyName else localized(R.string.app_server_fallback_name),
                )
            }
        }
    }

    sealed interface Action {
        data object Task : Action
        data class ForegroundChanged(val isInForeground: Boolean) : Action
        data class ServerURLChanged(val value: String) : Action
        data class UsernameChanged(val value: String) : Action
        data class PasswordChanged(val value: String) : Action
        data class ServerNameChanged(val value: String) : Action
        data class HeadersChanged(val headers: List<OpenCodeServer.Header>) : Action
        data class NotificationOpened(val destination: NotificationDestination) : Action
        data class NotificationSessionLoaded(val destination: NotificationDestination, val result: Result<OpenCodeSession>) : Action
        data object NotificationErrorDismissed : Action
        /** Listens for shortcuts, widgets and intents for as long as the app runs. */
        data object SystemActionsTask : Action
        /** The app-wide work moved to another window's store (`AppWideWork`): stop listening. */
        data object SystemActionsTaskEnded : Action
        /** From an app shortcut, a widget, an intent or an `opencode://` link. */
        data class SystemActionReceived(val action: SystemAction) : Action
        /** A session the action names that the list doesn't have. */
        data class SystemSessionLoaded(val action: SystemAction, val sessionID: String, val result: Result<OpenCodeSession>) : Action
        data object SystemActionErrorDismissed : Action
        /** The app became active: take what the share sheet left. */
        data object PendingSharesRequested : Action
        data class SharesReceived(val shares: List<ReceivedShare>) : Action
        data object ShareErrorDismissed : Action
        data object ConnectButtonTapped : Action
        data object AddServerTapped : Action
        data class EditServerTapped(val server: OpenCodeServer) : Action
        data object CancelEditingTapped : Action
        data class ServerTapped(val server: OpenCodeServer) : Action
        data object ReturnToServerTapped : Action
        data class ServerDeleted(val server: OpenCodeServer) : Action
        data object ScanPairingCodeTapped : Action
        data object ScannerDismissed : Action
        data object PastePairingCodeTapped : Action
        data class PairingCodeScanned(val text: String) : Action
        data class PairingURLOpened(val url: String) : Action
        data class PairingConfirmed(val id: String) : Action
        data class PairingCancelled(val id: String) : Action
        data object PairingErrorDismissed : Action
        data class PairingRedeemed(val attemptID: Int, val result: Result<OpenCodeServer>) : Action
        data class HealthResponse(val attemptID: Int, val result: Result<OpenCodeServer>) : Action
        data class CopyTextTapped(val text: String) : Action
        data object LocalNetworkSettingsTapped : Action
        data class Home(val action: HomeFeature.Action) : Action
        data class Welcome(val action: WelcomeFeature.Action) : Action
        data class ClientTools(val action: ClientToolsFeature.Action) : Action
        data class DelegatorClientTools(val action: ClientToolsFeature.Action) : Action
        data class VoiceDelegatorLocated(val location: ClientToolsLocation) : Action
        data class VoiceOpenSession(val action: VoiceOpenSessionFeature.Action) : Action
        data class HomeResponse(val attemptID: Int, val result: Result<OpenCodeHomeSnapshot>, val archived: List<OpenCodeSession> = emptyList()) : Action
        /** Server Flow: the switch's server didn't answer in time. */
        data class ServerSwitchTimedOut(val attemptID: Int) : Action
    }

    internal enum class CancelID { Health, Home, Notification, SystemActions, SystemSession, SwitchTimeout }

    private val core = Reducer<State, Action> { state, action -> reduceCore(state, action) }

    /** `.ifLet(\.home, action: \.home)` (plain optional: Home runs first on its own actions). */
    private val body: Reducer<State, Action> = ifLet(
        parent = core,
        state = { it.home },
        setState = { s, home -> s.copy(home = home) },
        action = { (it as? Action.Home)?.action },
        embed = Action::Home,
        child = HomeFeature(),
        childID = { it.id },
    )

    private val whatsNewBody: Reducer<State, Action> = body.keepingWhatsNewAcrossHomes()

    private val welcomeBody: Reducer<State, Action> = combine(
        whatsNewBody,
        scope(
            state = { it.welcome },
            setState = { s, welcome -> s.copy(welcome = welcome) },
            action = { (it as? Action.Welcome)?.action },
            embed = Action::Welcome,
            child = WelcomeFeature(),
        ),
        welcomeRequests,
    )

    /**
     * Leaving Home cancels a call or a recording without Home seeing it end, so their ongoing
     * notifications are ended, and the call's log saved, here. Android's call notification is
     * voice's foreground service, keyed by the call's presence ID. Then the screen stays on during
     * a call, set at once rather than from an effect so a quick on-then-off can't land out of order
     * (watched from here so leaving Home mid-call lets the screen sleep again too).
     */
    private val liveBody: Reducer<State, Action> = sharingScreenWithAgents(welcomeBody)
        .onChange({ it.home?.sessionDetail?.voice?.callPresenceID }) { old, _, state ->
            if (old == null || state.home != null) return@onChange Reduced(state)
            val service = dependency(VoiceCallServiceClient)
            val liveActivity = dependency(VoiceLiveActivityClient)
            Reduced(state, Effect.run { service.stop(old); liveActivity.update(null, null) })
        }
        .onChange({ it.home?.dictationActivity != null }) { old, _, state ->
            if (!old || state.home != null) return@onChange Reduced(state)
            val dictationLiveActivity = dependency(DictationLiveActivityClient)
            Reduced(state, Effect.run { dictationLiveActivity.update(null, null) })
        }
        // And a followed run's, which can't update once its Home is gone: Home leaving, or another
        // server's replacing it.
        .onChange({ state -> state.home?.takeIf { it.sessionActivity != null }?.id }) { old, _, state ->
            if (old == null || state.home?.id == old) return@onChange Reduced(state)
            val sessionLiveActivity = dependency(SessionLiveActivityClient)
            Reduced(state, Effect.run { sessionLiveActivity.update(null, null) })
        }
        // A call that goes with its Home never reaches a terminal phase, so its log is saved here.
        .onChange({ it.home?.sessionDetail?.voice?.callLogID }) { old, _, state ->
            if (old == null || state.home?.sessionDetail?.voice?.callLogID == old) return@onChange Reduced(state)
            val voiceCallLog = dependency(VoiceCallLogClient)
            Reduced(state, Effect.run { voiceCallLog.finish(old) })
        }
        .onChange({ it.keepsScreenAwake }) { _, keeps, state ->
            dependency(ScreenAwakeClient).set(keeps)
            Reduced(state)
        }
        // Also covers a call that starts while the app is in the background.
        .onChange({ it.callIsOutOfSight }) { _, _, state ->
            if (state.home?.sessionDetail?.voice == null) return@onChange Reduced(state)
            Reduced(state, Effect.send(voiceAction(VoiceFeature.Action.AppVisibilityChanged(state.isInForeground))))
        }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        val reduced = liveBody.reduce(state, action)
        // The pairing's drawer never opens under anything the app presents.
        val home = reduced.state.home
        if (home?.drawerOpenRequest == null || !reduced.state.isPresentingAnything) return reduced
        AppLog.app.info { "paired computer drawer open dropped under an app presentation" }
        return Reduced(reduced.state.copy(home = home.copy(drawerOpenRequest = null)), reduced.effect)
    }

    private fun reduceCore(state: State, action: Action): Reduced<State, Action> = when (action) {
        Action.Task -> {
            migrateSettingsIfNeeded(state.knownServers)
            if (dependency(ExperimentalFeatures).isEnabled(ExperimentalFeature.soundEffects)) dependency(SoundEffectsClient).prepare()
            // Restored home owns revalidation; never replace its cached list with setup.
            val saved = state.savedServer.value
            if (state.connection != State.Connection.Idle || state.home != null) Reduced(state)
            else if (saved != null) connect(state.load(saved))
            // With no last-used server, wait for a scan or an explicit selection.
            else Reduced(state)
        }
        is Action.ForegroundChanged -> {
            if (state.isInForeground != action.isInForeground) AppLog.app.info { if (action.isInForeground) "Foreground" else "Background" }
            Reduced(state.copy(isInForeground = action.isInForeground))
        }
        is Action.ServerURLChanged -> serverEdited(state, state.copy(serverURL = action.value))
        is Action.UsernameChanged -> serverEdited(state, state.copy(username = action.value))
        is Action.PasswordChanged -> serverEdited(state, state.copy(password = action.value))
        is Action.ServerNameChanged -> serverEdited(state, state.copy(serverName = action.value))
        is Action.HeadersChanged -> serverEdited(state, state.copy(headers = action.headers))
        is Action.NotificationOpened -> notificationOpened(state, action.destination)
        is Action.NotificationSessionLoaded -> notificationSessionLoaded(state, action)
        Action.NotificationErrorDismissed -> Reduced(state.copy(notificationError = null))
        Action.SystemActionsTask -> {
            val actions = dependency(SystemIntegrationClient).actions
            Reduced(
                state,
                Effect.run<Action> { send -> actions().collect { send(Action.SystemActionReceived(it)) } }
                    .cancellable(CancelID.SystemActions, cancelInFlight = true),
            )
        }
        Action.SystemActionsTaskEnded -> Reduced(state, Effect.cancel(CancelID.SystemActions))
        is Action.SystemActionReceived -> systemActionReceived(state, action.action)
        is Action.SystemSessionLoaded -> systemSessionLoaded(state, action)
        Action.SystemActionErrorDismissed -> Reduced(state.copy(systemActionError = null))
        Action.PendingSharesRequested -> {
            val inbox = dependency(ShareInboxClient)
            Reduced(
                state,
                Effect.run { send ->
                    val shares = inbox.consume()
                    if (shares.isNotEmpty()) send(Action.SharesReceived(shares))
                },
            )
        }
        is Action.SharesReceived -> receiveShares(action.shares, state)
        Action.ShareErrorDismissed -> Reduced(state.copy(shareError = null))
        Action.ConnectButtonTapped -> connectButtonTapped(state)
        Action.AddServerTapped -> cancelConnection(beginNewServer(state))
        is Action.EditServerTapped -> {
            val cancelled = cancelConnection(state)
            Reduced(
                cancelled.state.copy(editingSavedServer = action.server, manualEntryDraft = null, isEditingServer = true).load(action.server),
                cancelled.effect,
            )
        }
        Action.CancelEditingTapped -> {
            val next = state.copy(isEditingServer = false, editingSavedServer = null)
            if (next.home != null) Reduced(next) else cancelConnection(next)
        }
        is Action.ServerTapped -> selectServer(action.server, state)
        Action.ReturnToServerTapped -> state.returnServer?.let { selectServer(it, state) } ?: Reduced(state)
        is Action.ServerDeleted -> serverDeleted(state, action.server)
        Action.ScanPairingCodeTapped -> cancelConnection(beginScanningPairingCode(state.copy(pasteError = null)))
        Action.ScannerDismissed -> {
            val next = state.copy(isScanningPairingCode = false)
            // Opened over Home, there's no attempt to cancel and Home carries on.
            if (next.home != null) Reduced(next) else cancelConnection(next)
        }
        Action.PastePairingCodeTapped -> pastePairingCode(state)
        is Action.PairingCodeScanned -> pairingCodeScanned(state, action.text)
        is Action.PairingURLOpened -> {
            LaunchBloomController.completeColdLaunch()
            requestPairing(action.url, state)
        }
        is Action.PairingConfirmed -> {
            val pending = state.pendingPairing
            if (pending == null || pending.id != action.id) Reduced(state)
            else beginPairing(pending.link, state.copy(pendingPairing = null, ignoredPendingPairingCount = 0))
        }
        is Action.PairingCancelled -> {
            if (state.pendingPairing?.id != action.id) Reduced(state)
            else Reduced(state.copy(pendingPairing = null, ignoredPendingPairingCount = 0))
        }
        Action.PairingErrorDismissed -> Reduced(state.copy(pairingError = null))
        is Action.PairingRedeemed -> pairingRedeemed(state, action)
        Action.LocalNetworkSettingsTapped -> {
            val localNetwork = dependency(LocalNetworkPermission)
            Reduced(state, Effect.run { localNetwork.openSettings() })
        }
        is Action.CopyTextTapped -> {
            if (action.text.isEmpty()) Reduced(state)
            else {
                dependency(HapticsClient).play(Haptic.tap)
                val pasteboard = dependency(PasteboardClient)
                Reduced(state, Effect.run { pasteboard.setString(action.text) })
            }
        }
        is Action.HealthResponse -> healthResponse(state, action)
        is Action.HomeResponse -> homeResponse(state, action)
        is Action.ServerSwitchTimedOut -> serverSwitchTimedOut(action.attemptID, state)
        is Action.Home -> home(state, action.action)
        is Action.Welcome, is Action.ClientTools, is Action.DelegatorClientTools, is Action.VoiceDelegatorLocated, is Action.VoiceOpenSession -> Reduced(state)
    }

    /**
     * Only an actual edit invalidates the attempt in flight. While connecting a manual entry, a
     * field flushing its final value after Connect retries with the completed entry instead of
     * silently abandoning the attempt.
     */
    private fun serverEdited(old: State, new: State): Reduced<State, Action> {
        if (old.server == new.server || new.connectionAttempt == null) return Reduced(new)
        if (new.manualEntryDraft != null) return connect(new.copy(manualEntryDraft = new.server))
        return cancelConnection(new)
    }

    private fun connectButtonTapped(state: State): Reduced<State, Action> {
        // The link `opencode pair` prints (or its QR payload), pasted as a new server's address,
        // pairs rather than being taken for an address with no password.
        val typed = state.serverURL.trim()
        if (state.editingSavedServer == null &&
            (OpenCodePairingLink.decodeCopied(typed) != null || OpenCodePairingLink.isPairingEndpoint(typed))
        ) {
            return Reduced(state.copy(serverURL = ""), Effect.send(Action.PairingCodeScanned(typed)))
        }
        var next = state
        // Opened over Home from the drawer: a failure offers the way back.
        state.homeServer?.let { next = next.copy(returnServer = it) }
        next = next.copy(serverURL = withDefaultScheme(state.serverURL.trim()))
        val original = next.editingSavedServer
        // A changed address replaces the old one; stale pairing alternates must not silently
        // reconnect to the address the user just edited.
        if (original != null && next.serverURL != original.url) next = next.copy(alternateURLs = emptyList(), preferredURLs = emptyList())
        next = next.copy(manualEntryDraft = next.server)
        // A server added from the drawer is switched to like a saved one; an edit of the server
        // in use reconnects it, which ends its Home first.
        val target = next.server
        val keepsHome = next.keepsHome(switchingTo = target)
        if (keepsHome) next = next.showingSwitch(target)
        return connect(next, keepingHome = keepsHome)
    }

    private fun home(state: State, action: HomeFeature.Action): Reduced<State, Action> = when (action) {
        is HomeFeature.Action.Delegate -> homeDelegate(state, action.action)
        // Home has taken its first snapshot (children run first): actions that waited for the
        // list can resolve now.
        is HomeFeature.Action.SnapshotRefreshed -> {
            val pending = state.pendingSystemAction
            val home = state.home
            if (pending == null || home == null || pending.serverKey != SessionDirectory.serverKey(home.server.persistenceID)) Reduced(state)
            else routeSystemAction(pending, state)
        }
        // Pairing links tapped in a session's transcript or its in-app browser (the open screen's
        // or a pushed subagent's) pair, as one opened from outside the app does.
        is HomeFeature.Action.SessionDetail -> {
            val url = (action.action as? PresentationAction.Presented)?.action?.let(::pairingLinkURL)
            if (url != null) requestPairing(url, state) else Reduced(state)
        }
        is HomeFeature.Action.SubagentStack -> {
            val url = pairingLinkURL(action.action)
            if (url != null) requestPairing(url, state) else Reduced(state)
        }
        else -> Reduced(state)
    }

    private fun pairingLinkURL(action: SessionFeature.Action): String? {
        val url = when (action) {
            is SessionFeature.Action.LinkTapped -> action.url
            is SessionFeature.Action.Browser -> (action.action as? BrowserFeature.Action.PairingLinkTapped)?.url
            else -> null
        } ?: return null
        return url.takeIf { OpenCodePairingLink.shouldHandleOpenURL(it) }
    }

    private fun homeDelegate(state: State, action: HomeFeature.DelegateAction): Reduced<State, Action> = when (action) {
        is HomeFeature.DelegateAction.ServerSwitchTapped -> switchServer(action.server, state)
        is HomeFeature.DelegateAction.ServerRenamed -> Reduced(renameServer(action.server, action.name, state))
        is HomeFeature.DelegateAction.EditServerRequested -> Reduced(editServer(action.server, state))
        is HomeFeature.DelegateAction.ServerRemovalConfirmed -> Reduced(state, confirmRemoval(action.server, state))
        HomeFeature.DelegateAction.PairServerRequested -> Reduced(beginScanningPairingCode(state))
        HomeFeature.DelegateAction.AddServerRequested -> Reduced(beginNewServer(state))
    }

    // MARK: Notifications and system actions

    private fun notificationOpened(state: State, destination: NotificationDestination): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        if (!destination.isValid) return Reduced(state)
        val server = state.knownServers.firstOrNull { it.persistenceID == destination.serverKey }
            ?: return Reduced(state.copy(notificationError = serverNoLongerSavedMessage))
        val next = state.copy(notificationError = null, pendingNotification = destination)
        // A home for this server opens the session itself, even while it is still reconnecting
        // after a cold launch: rebuilding it would flash "Connecting…" over the restored list.
        val home = next.home
        val homeServer = next.homeServer
        if (home != null && homeServer?.persistenceID == destination.serverKey) {
            if (home.sessions.any { it.id == destination.sessionID }) {
                val opened = next.copy(pendingNotification = null)
                return Reduced(opened, openSession(opened, destination.sessionID))
            }
            return Reduced(next, loadNotificationSession(destination, homeServer))
        }
        return selectServer(server, next, preservingPending = true)
    }

    private fun notificationSessionLoaded(state: State, action: Action.NotificationSessionLoaded): Reduced<State, Action> {
        val destination = action.destination
        if (state.pendingNotification != destination || state.homeServer?.persistenceID != destination.serverKey) return Reduced(state)
        val next = state.copy(pendingNotification = null)
        val session = action.result.getOrNull()
        if (session == null || session.id != destination.sessionID) {
            return Reduced(next.copy(notificationError = sessionNoLongerAvailableMessage))
        }
        val inserted = next.insertingSession(session)
        return Reduced(inserted, openSession(inserted, session.id))
    }

    private fun systemActionReceived(state: State, action: SystemAction): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        val key = action.serverKey
        val server = if (key != null) {
            state.knownServers.firstOrNull { SessionDirectory.serverKey(it.persistenceID) == key }
                ?: state.knownServers.firstOrNull {
                    dependency(ExperimentalFeatures)(ExperimentalFeature.serverAddressLinks) && it.matchesOrigin(key)
                }
        } else {
            state.homeServer ?: state.savedServer.value
        }
        if (server == null) {
            // Nothing to open before a server is paired; the app is open now.
            if (key == null) return Reduced(state)
            return Reduced(state.copy(systemActionError = serverNoLongerSavedMessage))
        }
        val next = state.copy(systemActionError = null)
        // As with notifications, a home for this server handles it even while still reconnecting.
        if (next.homeServer?.persistenceID == server.persistenceID) return routeSystemAction(action, next)
        val pending = action.copy(serverKey = SessionDirectory.serverKey(server.persistenceID))
        val waiting = next.copy(pendingSystemAction = pending)
        if (waiting.connectingServerID == server.id) return Reduced(waiting)
        return selectServer(server, waiting, preservingPending = true)
    }

    private fun systemSessionLoaded(state: State, action: Action.SystemSessionLoaded): Reduced<State, Action> {
        if (state.home == null) return Reduced(state)
        val session = action.result.getOrNull()
        if (session != null && session.id == action.sessionID) {
            val inserted = state.insertingSession(session)
            return Reduced(inserted, openInHome(action.action, SystemAction.Destination.Session(session.id)))
        }
        // A remembered session that's gone falls back to the newest one.
        if (action.action.destination == SystemAction.Destination.LastViewed) {
            return Reduced(state, openInHome(action.action, SystemAction.Destination.LastViewed))
        }
        return Reduced(state.copy(systemActionError = sessionNoLongerAvailableMessage))
    }

    /**
     * Hands an action to the open server's home once its list has loaded (until then it waits
     * in `pendingSystemAction`), first fetching a session the list doesn't have.
     */
    internal fun routeSystemAction(action: SystemAction, state: State): Reduced<State, Action> {
        val home = state.home ?: return Reduced(state)
        val server = state.homeServer ?: return Reduced(state)
        if (!state.isHomeSnapshotLoaded) {
            return Reduced(state.copy(pendingSystemAction = action.copy(serverKey = SessionDirectory.serverKey(server.persistenceID))))
        }
        val next = state.copy(pendingSystemAction = null)
        val sessionID = when (val destination = action.destination) {
            is SystemAction.Destination.Session -> destination.id
            SystemAction.Destination.LastViewed -> next.lastViewedSessionIDs.value[server.persistenceID]
            is SystemAction.Destination.Draft -> null
        }
        if (sessionID != null && home.sessions.none { it.id == sessionID }) {
            val client = dependency(OpenCodeClient)
            return Reduced(
                next,
                Effect.run<Action> { send ->
                    val result = try {
                        Result.success(client.getSession(server, sessionID))
                    } catch (error: OpenCodeClientError) {
                        Result.failure(error)
                    }
                    send(Action.SystemSessionLoaded(action, sessionID, result))
                }.cancellable(CancelID.SystemSession, cancelInFlight = true),
            )
        }
        return Reduced(next, openInHome(action, action.destination))
    }

    private fun loadNotificationSession(destination: NotificationDestination, server: OpenCodeServer): Effect<Action> {
        val client = dependency(OpenCodeClient)
        return Effect.run<Action> { send ->
            val result = try {
                Result.success(client.getSession(server, destination.sessionID))
            } catch (error: OpenCodeClientError) {
                Result.failure(error)
            }
            send(Action.NotificationSessionLoaded(destination, result))
        }.cancellable(CancelID.Notification, cancelInFlight = true)
    }

    private fun State.insertingSession(session: OpenCodeSession): State {
        val home = home ?: return this
        if (home.sessions.any { it.id == session.id }) return this
        return copy(home = home.copy(sessions = listOf(session) + home.sessions))
    }

    private fun openSession(state: State, sessionID: String): Effect<Action> {
        return Effect.send(Action.Home(HomeFeature.Action.SessionTapped(sessionID)))
    }

    private fun openInHome(action: SystemAction, destination: SystemAction.Destination): Effect<Action> =
        Effect.send(
            Action.Home(
                HomeFeature.Action.SystemActionOpened(destination, startsCall = action.startsCall, inBackground = action.startsInBackground, origin = action.origin),
            ),
        )

    // MARK: Pairing

    private fun pairingCodeScanned(state: State, text: String): Reduced<State, Action> {
        LaunchBloomController.completeColdLaunch()
        val next = leaveHome(
            state.copy(
                pasteError = null,
                pendingPairing = null,
                ignoredPendingPairingCount = 0,
                pairingError = null,
                isScanningPairingCode = false,
                isEditingServer = false,
                editingSavedServer = null,
                manualEntryDraft = null,
            ),
        )
        val haptics = dependency(HapticsClient)
        return validatePairing(text).fold(
            onSuccess = { link ->
                // The code was read; `success` waits for the pairing to land.
                haptics.play(Haptic.tap)
                redeem(link, next)
            },
            onFailure = { error ->
                haptics.play(Haptic.error)
                cancelConnection(next, State.Connection.Failed(message = error.message.orEmpty(), phase = FailureContext.Phase.Pair))
            },
        )
    }

    private fun pairingRedeemed(state: State, action: Action.PairingRedeemed): Reduced<State, Action> {
        val attempt = state.connectionAttempt
        if (attempt?.id != action.attemptID || state.connection != State.Connection.Pairing) return Reduced(state)
        val server = action.result.getOrElse { error ->
            val clientError = error as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    connection = failed(clientError, attempt.server, FailureContext.of(attempt.server, FailureContext.Phase.Pair, action.attemptID, clientError.connectionFailures)),
                ),
            )
        }
        val history = shared(SharedKeys.installHistory).value
        val arrival = PairingArrival.of(server, state.knownServers, history)
        return connect(state.load(server.keepingLocalSettings(state.knownServers)), pairing = arrival)
    }

    // MARK: Connecting

    private fun healthResponse(state: State, action: Action.HealthResponse): Reduced<State, Action> {
        val attempt = state.connectionAttempt
        if (attempt?.id != action.attemptID || state.connection != State.Connection.Connecting) return Reduced(state)
        val server = action.result.getOrElse { error ->
            val clientError = error as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            if (attempt.switchesBack) return switchBack(state, after = clientError)
            // A switch kept Home up while it tried; the error needs the picker.
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    connection = failed(clientError, attempt.server, FailureContext.of(attempt.server, FailureContext.Phase.Connect, action.attemptID, clientError.connectionFailures)),
                ).withoutHome(),
            )
        }
        // Adopt the complete winning target, not a URL combined with form auth, in the address
        // order it was entered or paired with.
        val saved = server.keepingAddressOrder(attempt.server)
        var next = state.copy(connection = State.Connection.LoadingHome(server)).load(saved)
        // An edited entry is replaced only after its sessions load.
        val forgotten = ArrayList<Effect<Action>>()
        if (next.editingSavedServer == null) {
            // Re-pairing can give a saved server a new identity; forget the old one's now, since
            // remembering this one drops it whatever the sessions' read does.
            val superseded = next.knownServers
                .filter { server.supersedes(it) && it.persistenceID != server.persistenceID }
                .map { it.persistenceID }.toSet().sorted()
            superseded.forEach { id ->
                val reduced = forgetIdentity(id, next)
                next = reduced.state
                forgotten += reduced.effect
            }
            saved.remember()
        }
        val client = dependency(OpenCodeClient)
        val readsArchived = attempt.switchesBack
        return Reduced(
            next,
            Effect.merge(
                forgotten + Effect.run<Action> { send ->
                    coroutineScope {
                        val archived = async {
                            if (!readsArchived) return@async emptyList()
                            try {
                                client.archivedSessions(server, "", InboxSidebarFeature.ARCHIVED_LIMIT)
                            } catch (error: OpenCodeClientError) {
                                emptyList()
                            }
                        }
                        val result = try {
                            Result.success(client.homeSnapshot(server))
                        } catch (error: OpenCodeClientError) {
                            Result.failure(error)
                        }
                        send(Action.HomeResponse(action.attemptID, result, archived.await()))
                    }
                }.cancellable(CancelID.Home, cancelInFlight = true),
            ),
        )
    }

    private fun homeResponse(state: State, action: Action.HomeResponse): Reduced<State, Action> {
        val loading = state.connection as? State.Connection.LoadingHome
        if (state.connectionAttempt?.id != action.attemptID || loading == null) return Reduced(state)
        val server = loading.server
        val snapshot = action.result.getOrElse { error ->
            val clientError = error as? OpenCodeClientError ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
            if (state.connectionAttempt?.switchesBack == true) return switchBack(state, after = clientError)
            val message = clientError.message
            return Reduced(
                state.copy(
                    connectionAttempt = null,
                    editingSavedServer = null,
                    connection = State.Connection.Failed(
                        message = message,
                        context = FailureContext.of(server, FailureContext.Phase.LoadSessions, action.attemptID),
                    ),
                ).withoutHome(),
            )
        }
        // An edit is saved only now; until then the original stays saved under its own identity,
        // so a failed read keeps what it had.
        var next = state
        var forgotten: Effect<Action> = Effect.cancel(CancelID.SwitchTimeout)
        next.editingSavedServer?.let { original ->
            if (original.persistenceID != server.persistenceID) {
                val reduced = forgetIdentity(original.persistenceID, next)
                next = reduced.state
                forgotten = Effect.merge(forgotten, reduced.effect)
            }
            server.remember(original)
            next = next.copy(editingSavedServer = null)
        }
        // A switch made from Settings stays in Settings: the connection card shows the new server arrive.
        val previous = next.home
        val keepsSettings = previous?.isSettingsShown == true && previous.switchingTo?.persistenceID == server.persistenceID
        val pairing = next.connectionAttempt?.pairing
        val reachability = previous?.serverReachability?.filterKeys { it != server.persistenceID }.orEmpty()
        // Server copies don't carry the first prompt an unnamed session is shown by; keep what this
        // server's cached list knew, or reconnecting would name them "Untitled session" again.
        val known = state.cachedHomeSnapshots.value[server.persistenceID]?.snapshot?.sessions.orEmpty()
        next = next.withHome(server, snapshot.copy(sessions = snapshot.sessions.keepingFirstPrompts(known)))
        next = next.copy(
            home = next.home
                ?.let { if (keepsSettings) it.keepingSettings(previous) else it }
                ?.let { it.copy(serverReachability = reachability, inboxSidebarStatus = it.inboxSidebarStatus.copy(archived = action.archived)) }
                ?.persistingSnapshot(dependency(HomeSnapshotDate).now()),
        )
        next = next.copy(
            isEditingServer = false,
            connection = State.Connection.Connected,
            connectionAttempt = null,
            manualEntryDraft = null,
            returnServer = null,
        )
        // Anything the person asked for on the way in wins over the drawer.
        val asked = next.pendingShares.isNotEmpty() || next.pendingSystemAction != null || next.pendingNotification != null
        val delivered = deliverPendingShares(next)
        next = delivered.state
        val shares = delivered.effect
        var system: Effect<Action> = Effect.none()
        val pendingAction = next.pendingSystemAction
        if (pendingAction != null && pendingAction.serverKey == SessionDirectory.serverKey(server.persistenceID)) {
            val reduced = routeSystemAction(pendingAction, next)
            next = reduced.state
            system = reduced.effect
        }
        val landing = landPairing(pairing, server, asked)
        val destination = next.pendingNotification
        if (destination != null && destination.serverKey == server.persistenceID) {
            return Reduced(next, Effect.merge(forgotten, shares, system, landing, loadNotificationSession(destination, server)))
        }
        return Reduced(next, Effect.merge(forgotten, shares, system, landing))
    }

    /**
     * Races every address at once ([ServerLinkClient.probe]): the first that answers as this server
     * is used now, while the server is saved with the address order it was paired or entered with.
     */
    internal fun connect(
        state: State,
        preservingPending: Boolean = false,
        keepingHome: Boolean = false,
        pairing: PairingArrival? = null,
    ): Reduced<State, Action> {
        var next = state
        if (!preservingPending) next = next.copy(pendingNotification = null, pendingSystemAction = null)
        val generation = next.connectionGeneration + 1
        val attempt = ConnectionAttempt(id = generation, server = next.server, pairing = pairing)
        next = next.copy(connection = State.Connection.Connecting, connectionGeneration = generation, connectionAttempt = attempt)
        if (!keepingHome) next = next.withoutHome()
        val link = dependency(ServerLinkClient)
        val localNetwork = dependency(LocalNetworkPermission)
        val health = Effect.run<Action> { send ->
            // Android 17 asks on the first local network connection.
            if (localNetwork.blocks(attempt.server)) localNetwork.request()
            val result = try {
                Result.success(link.probe(attempt.server))
            } catch (error: OpenCodeClientError) {
                Result.failure(error)
            }
            send(Action.HealthResponse(attempt.id, result))
        }
        return Reduced(
            next,
            Effect.merge(
                Effect.cancel(CancelID.Notification),
                Effect.cancel(CancelID.Home),
                health.cancellable(CancelID.Health, cancelInFlight = true),
            ),
        )
    }

    /** A failure while local network access is off says so, instead of the bare timeout. */
    private fun failed(error: OpenCodeClientError, server: OpenCodeServer, context: FailureContext): State.Connection.Failed {
        if (!dependency(LocalNetworkPermission).blocks(server)) return State.Connection.Failed(message = error.message, context = context)
        return State.Connection.Failed(message = LocalNetworkPermission.DENIED_MESSAGE, context = context, localNetworkDenied = true)
    }

    companion object {
        /** A typed address without a scheme means plain HTTP on a LAN or tailnet. */
        fun withDefaultScheme(address: String): String {
            if (address.isEmpty() || address.contains("://")) return address
            return "http://$address"
        }

        val EMPTY_CLIPBOARD_MESSAGE: String get() = localized(R.string.app_paste_clipboard_empty)
        val NOT_A_PAIRING_LINK_MESSAGE: String get() = localized(R.string.app_paste_not_a_pairing_link)
    }
}

internal val serverNoLongerSavedMessage: String
    get() = localized(R.string.app_server_no_longer_saved, ThisDevice.name)

internal val sessionNoLongerAvailableMessage: String get() = localized(R.string.app_session_no_longer_available)
