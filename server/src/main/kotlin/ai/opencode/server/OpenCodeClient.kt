package ai.opencode.server

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.server.live.MarkdownImageCache
import ai.opencode.server.live.markdownImageCacheKey
import ai.opencode.server.live.requestActiveSessionParents
import ai.opencode.server.live.requestAgents
import ai.opencode.server.live.requestArchiveSession
import ai.opencode.server.live.requestArchivedSessions
import ai.opencode.server.live.requestAssistantReply
import ai.opencode.server.live.requestCancelForm
import ai.opencode.server.live.requestCancelIntegrationOAuth
import ai.opencode.server.live.requestCancelPendingInput
import ai.opencode.server.live.requestClearRevert
import ai.opencode.server.live.requestCommands
import ai.opencode.server.live.requestCompactSession
import ai.opencode.server.live.requestCompleteIntegrationOAuth
import ai.opencode.server.live.requestContextUsage
import ai.opencode.server.live.requestCreateSession
import ai.opencode.server.live.requestDeleteSession
import ai.opencode.server.live.requestExportSession
import ai.opencode.server.live.requestFamilyPendingRequests
import ai.opencode.server.live.requestFindFiles
import ai.opencode.server.live.requestForkSession
import ai.opencode.server.live.requestGetSession
import ai.opencode.server.live.requestHealth
import ai.opencode.server.live.requestHistoryPage
import ai.opencode.server.live.requestHomeAttentionSharingWindows
import ai.opencode.server.live.requestHomeSnapshotSharingWindows
import ai.opencode.server.live.requestIntegrationOAuthStatus
import ai.opencode.server.live.requestInterrupt
import ai.opencode.server.live.requestIsSessionActive
import ai.opencode.server.live.requestListDirectory
import ai.opencode.server.live.requestMarkdownImage
import ai.opencode.server.live.requestMentionSessions
import ai.opencode.server.live.requestMessage
import ai.opencode.server.live.requestModelCatalog
import ai.opencode.server.live.requestMoveSession
import ai.opencode.server.live.requestMoveToBackground
import ai.opencode.server.live.requestNotifyPluginStatus
import ai.opencode.server.live.requestPendingInputs
import ai.opencode.server.live.requestPrimaryAgents
import ai.opencode.server.live.requestRedeemPairing
import ai.opencode.server.live.requestRenameSession
import ai.opencode.server.live.requestReplyForm
import ai.opencode.server.live.requestReplyPermission
import ai.opencode.server.live.requestRunMessages
import ai.opencode.server.live.requestRunReply
import ai.opencode.server.live.requestRunningShells
import ai.opencode.server.live.requestSearchSessions
import ai.opencode.server.live.requestSessionPage
import ai.opencode.server.live.requestSendCommand
import ai.opencode.server.live.requestSendDeliveredPrompt
import ai.opencode.server.live.requestSendPrompt
import ai.opencode.server.live.requestServerCapabilities
import ai.opencode.server.live.requestSessionFamily
import ai.opencode.server.live.requestSetAlwaysNotify
import ai.opencode.server.live.requestSetMCPConnected
import ai.opencode.server.live.requestSetPendingInputDelivery
import ai.opencode.server.live.requestShell
import ai.opencode.server.live.requestShellOutput
import ai.opencode.server.live.requestSkills
import ai.opencode.server.live.requestStageRevert
import ai.opencode.server.live.requestStartIntegrationOAuth
import ai.opencode.server.live.requestStopShell
import ai.opencode.server.live.requestRunningJobs
import ai.opencode.server.live.requestCancelJob
import ai.opencode.server.live.requestSwitchAgent
import ai.opencode.server.live.requestSwitchModel
import ai.opencode.server.live.requestTranscript
import ai.opencode.server.live.requestUnarchiveSession
import ai.opencode.server.live.requestViewSession
import ai.opencode.server.live.requestWaitForIdle
import ai.opencode.server.live.subscribeEventsSharingWindows
import ai.opencode.server.live.uploadFile
import ai.opencode.server.model.FileReadTarget
import ai.opencode.server.model.OpenCodeAutocompleteAgent
import ai.opencode.server.model.OpenCodeAutocompleteCommand
import ai.opencode.server.model.OpenCodeAutocompleteFile
import ai.opencode.server.model.OpenCodeAutocompleteMentions
import ai.opencode.server.model.OpenCodeAutocompleteSkill
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeDirectoryListing
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeNotifyPluginStatus
import ai.opencode.server.model.OpenCodeOAuthAttempt
import ai.opencode.server.model.OpenCodeOAuthStatus
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodeRunMessagesPage
import ai.opencode.server.model.OpenCodeServerCapabilities
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionPage
import ai.opencode.server.model.OpenCodeSessionPageRequest
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeShellOutputPage
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import ai.opencode.server.model.OpenCodeUploadEvent
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * The V2 OpenCode server client: a value of suspend functions, read with
 * `dependency(OpenCodeClient)` and overridden in tests with `copy(...)`. Every call throws
 * [OpenCodeClientError] (or `CancellationException`); every request goes through `NetworkRetry`
 * exactly once. Run a loop that owns its retries under `withRetryPolicy(RetryPolicy.once) { … }`.
 */
data class OpenCodeClient(
    val health: suspend (server: OpenCodeServer) -> Unit,
    /** Redeems a one-time `opencode pair` link for a token-authenticated server, with its other addresses. */
    val redeemPairing: suspend (link: OpenCodePairingLink) -> OpenCodeServer,
    val homeSnapshot: suspend (server: OpenCodeServer) -> OpenCodeHomeSnapshot,
    /** Resolves `childID → parentID` links for active sessions after the Home snapshot publishes. */
    val activeSessionParents: suspend (server: OpenCodeServer, activeIDs: Set<String>, roots: List<String>) -> Map<String, String>,
    /**
     * Root sessions with a pending request among [directories]. Each directory costs location-scoped
     * reads that boot that location: pass a bounded hot set, never the whole history.
     */
    val homeAttention: suspend (server: OpenCodeServer, roots: List<OpenCodeSession>, directories: List<String>) -> Set<String>,
    /** Root sessions whose title contains `query`, newest first (`GET /api/session?search=`). */
    val searchSessions: suspend (server: OpenCodeServer, query: String, limit: Int) -> List<OpenCodeSession>,
    /**
     * One page of root sessions for the drawer: a search's first page, or the page after a
     * cursor (`GET /api/session`). Not location-scoped.
     */
    val sessionPage: suspend (server: OpenCodeServer, request: OpenCodeSessionPageRequest, limit: Int) -> OpenCodeSessionPage =
        { _, _, _ -> OpenCodeSessionPage(emptyList(), null) },
    val transcript: suspend (server: OpenCodeServer, sessionID: String, limit: Int) -> List<OpenCodeTranscriptItem>,
    /** One authoritative message projection. */
    val message: suspend (server: OpenCodeServer, sessionID: String, messageID: String) -> List<OpenCodeTranscriptItem>,
    /** `text` is what the user typed; `files` are uploaded path references folded into the model-visible text. */
    val sendPrompt: suspend (
        server: OpenCodeServer,
        sessionID: String,
        text: String,
        attachments: List<OpenCodeOutgoingAttachment>,
        files: List<OpenCodeFileReference>,
        messageID: String,
        delivery: OpenCodeDelivery,
    ) -> Unit,
    val sendDeliveredPrompt: suspend (server: OpenCodeServer, sessionID: String, text: String, delivery: OpenCodeDelivery?) -> String,
    /** The first completed assistant text after `afterMessageID`, or null when the run produced none. */
    val assistantReply: suspend (server: OpenCodeServer, sessionID: String, afterMessageID: String) -> String?,
    /** The reply of the run a prompt was steered into, past later voice delegations. */
    val runReply: suspend (server: OpenCodeServer, sessionID: String, afterMessageID: String) -> String?,
    /** Streams a local file into the server's temporary directory and finishes with its absolute path. */
    val uploadFile: (server: OpenCodeServer, directory: String?, file: File, name: String) -> Flow<OpenCodeUploadEvent>,
    val waitForIdle: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    val isSessionActive: suspend (server: OpenCodeServer, sessionID: String) -> Boolean,
    /** One page of a session's history as a voice watch reads a run, newest page first; `cursor` is the previous page's `next`. */
    val runMessages: suspend (server: OpenCodeServer, sessionID: String, cursor: String?) -> OpenCodeRunMessagesPage,
    /** Stops the run and resumes its pending steering messages (`resume=true`). True when a run was going. */
    val interrupt: suspend (server: OpenCodeServer, sessionID: String) -> Boolean,
    /** `id`, when given, names the session: creating it again returns the same one. */
    val createSession: suspend (server: OpenCodeServer, directory: String, id: String?) -> OpenCodeSession,
    val deleteSession: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    val sessionFamily: suspend (server: OpenCodeServer, root: OpenCodeSession) -> OpenCodeSessionFamily,
    val modelCatalog: suspend (server: OpenCodeServer, directory: String?) -> OpenCodeModelCatalog,
    /** The folders in one directory on the server (its default folder when `path` is null). Never location-scoped. */
    val listDirectory: suspend (server: OpenCodeServer, path: String?) -> OpenCodeDirectoryListing,
    val switchModel: suspend (server: OpenCodeServer, sessionID: String, model: OpenCodeModelRef) -> Unit,
    val familyPendingRequests: suspend (server: OpenCodeServer, sessionIDs: List<String>, directory: String) -> OpenCodePendingRequests,
    val pendingInputs: suspend (server: OpenCodeServer, sessionID: String) -> List<OpenCodePendingInput>,
    /** Moves an undelivered message between steering and queued delivery. */
    val setPendingInputDelivery: suspend (server: OpenCodeServer, sessionID: String, inputID: String, delivery: OpenCodeDelivery) -> Unit,
    /** Withdraws an undelivered message. Already delivered items are a no-op. */
    val cancelPendingInput: suspend (server: OpenCodeServer, sessionID: String, inputID: String) -> Unit,
    /** `message` tells the agent what to do instead; with one, a denial lets it continue. */
    val replyPermission: suspend (server: OpenCodeServer, sessionID: String, requestID: String, reply: OpenCodePermissionReply, message: String?) -> Unit,
    val replyForm: suspend (server: OpenCodeServer, sessionID: String, formID: String, answer: Map<String, OpenCodeFormValue>) -> Unit,
    val cancelForm: suspend (server: OpenCodeServer, sessionID: String, formID: String, message: String?) -> Unit,
    val getSession: suspend (server: OpenCodeServer, sessionID: String) -> OpenCodeSession,
    /** Whether the server loaded the notify plugin (`GET /api/plugin`). */
    val notifyPluginStatus: suspend (server: OpenCodeServer) -> OpenCodeNotifyPluginStatus,
    /** Location-scoped: pass only the open session's directory. */
    val runningShells: suspend (server: OpenCodeServer, directory: String) -> List<OpenCodeShell>,
    /** The session's running background jobs, its descendants' included (`GET /api/experimental/job`). */
    val runningJobs: suspend (server: OpenCodeServer, sessionID: String) -> List<OpenCodeJob>,
    /** Cancels a running job and the work it started; one already finished is a no-op. */
    val cancelJob: suspend (server: OpenCodeServer, jobID: String) -> Unit,
    /** One shell's status, including exited ones the server still retains. */
    val shell: suspend (server: OpenCodeServer, directory: String, shellID: String) -> OpenCodeShell,
    val shellOutput: suspend (server: OpenCodeServer, directory: String, shellID: String, cursor: Int, limit: Int) -> OpenCodeShellOutputPage,
    /** Terminates the shell and discards its retained output. */
    val stopShell: suspend (server: OpenCodeServer, directory: String, shellID: String) -> Unit,
    /** Moves the session's blocking shells to the background. */
    val moveToBackground: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    val viewSession: suspend (server: OpenCodeServer, sessionID: String, idle: Long) -> Unit,
    /** Reads the session's newest messages for the context the last reply filled. */
    val contextUsage: suspend (server: OpenCodeServer, sessionID: String) -> OpenCodeContextReading,
    val renameSession: suspend (server: OpenCodeServer, sessionID: String, title: String) -> Unit,
    /** Moves the session to another folder on the computer (`POST …/move`), at once when its own folder is gone. */
    val moveSession: suspend (server: OpenCodeServer, sessionID: String, directory: String) -> Unit,
    /** Archives the session (`PATCH {archived: true}`), as the desktop Inbox does. */
    val archiveSession: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    /** Unarchives the session (`PATCH {archived: false}`), as desktop's Unarchive does. */
    val unarchiveSession: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    /** The newest archived root sessions (`archived=true`) matching `search` (all when empty), as desktop's Archived list. */
    val archivedSessions: suspend (server: OpenCodeServer, search: String, limit: Int) -> List<OpenCodeSession>,
    /** Turns Always Notify on (with the phone's callback URL) or off (null), keeping other metadata. */
    val setAlwaysNotify: suspend (server: OpenCodeServer, sessionID: String, callbackURL: String?) -> Unit,
    /** Queues a compaction; it runs after the session's current run. */
    val compactSession: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    /** Undoes from a message on (V2's `revert/stage`, as the TUI's `/undo`). */
    val stageRevert: suspend (server: OpenCodeServer, sessionID: String, messageID: String) -> Unit,
    /** Redo: brings back what `stageRevert` hid, files included. */
    val clearRevert: suspend (server: OpenCodeServer, sessionID: String) -> Unit,
    /** Copies the whole session into a new root session. */
    val forkSession: suspend (server: OpenCodeServer, sessionID: String) -> OpenCodeSession,
    /** The `SessionTransfer` JSON body (`{"data": {"info", "messages"}}`). */
    val exportSession: suspend (server: OpenCodeServer, sessionID: String) -> ByteArray,
    val historyPage: suspend (server: OpenCodeServer, sessionID: String, cursor: String?) -> OpenCodeTranscriptPage,
    /** Settings › Developer › Server Details: MCP servers, skills, and sign-ins for the server's own location. */
    val serverCapabilities: suspend (server: OpenCodeServer) -> OpenCodeServerCapabilities,
    val setMCPConnected: suspend (server: OpenCodeServer, name: String, connected: Boolean) -> Unit,
    val startIntegrationOAuth: suspend (server: OpenCodeServer, integrationID: String) -> OpenCodeOAuthAttempt,
    val integrationOAuthStatus: suspend (server: OpenCodeServer, integrationID: String, attemptID: String) -> OpenCodeOAuthStatus,
    val completeIntegrationOAuth: suspend (server: OpenCodeServer, integrationID: String, attemptID: String, code: String) -> Unit,
    val cancelIntegrationOAuth: suspend (server: OpenCodeServer, integrationID: String, attemptID: String) -> Unit,
    /** Skills available in `directory` (`GET /api/skill`). */
    val skills: suspend (server: OpenCodeServer, directory: String?) -> List<OpenCodeAutocompleteSkill>,
    /** Subagents available in `directory` (`GET /api/agent`). */
    val agents: suspend (server: OpenCodeServer, directory: String?) -> List<OpenCodeAutocompleteAgent>,
    /** Primary agents a person can choose in `directory`. */
    val primaryAgents: suspend (server: OpenCodeServer, directory: String?) -> List<OpenCodeAutocompleteAgent>,
    /** Switches the agent for the session's next turns (`POST /api/session/{id}/agent`). */
    val switchAgent: suspend (server: OpenCodeServer, sessionID: String, agent: String) -> Unit,
    /** Slash commands available in `directory` (`GET /api/command`). */
    val commands: suspend (server: OpenCodeServer, directory: String?) -> List<OpenCodeAutocompleteCommand>,
    /** Files matching `query` in `directory` (`GET /api/fs/find`). */
    val findFiles: suspend (server: OpenCodeServer, directory: String, query: String, limit: Int) -> List<OpenCodeAutocompleteFile>,
    /** Root, unarchived sessions to @-mention, newest first. */
    val mentionSessions: suspend (server: OpenCodeServer, search: String, limit: Int) -> List<OpenCodeSession>,
    /** Runs a slash command in `sessionID` (`POST /api/session/{id}/command`). */
    val sendCommand: suspend (
        server: OpenCodeServer,
        sessionID: String,
        name: String,
        text: String,
        mentions: OpenCodeAutocompleteMentions,
        delivery: OpenCodeDelivery,
    ) -> Unit,
    /** Previously fetched Markdown image bytes for a [markdownImageCacheKey], if cached in memory. */
    val cachedMarkdownImage: (cacheKey: String) -> ByteArray?,
    /** Decodes a `data:image/` URL or fetches and caches a Markdown image's bytes from `server`. */
    val markdownImage: suspend (server: OpenCodeServer, directory: String?, source: String, fallback: FileReadTarget?) -> ByteArray?,
    /** Long-lived SSE subscription; the flow throws on disconnect and the caller owns reconnecting. */
    val events: (server: OpenCodeServer) -> Flow<OpenCodeServerEvent>,
) {
    companion object Key : DependencyKey<OpenCodeClient> {
        /** The cache key [cachedMarkdownImage] takes for a Markdown image on `server`. */
        fun markdownImageKey(server: OpenCodeServer, directory: String?, source: String): String =
            markdownImageCacheKey(server, directory, source).removePrefix("markdown:")

        /**
         * Sends this phone's notification public key for `ref` to the saved server's HTTPS address
         * and returns the server's. Only public keys cross; throws without an HTTPS address.
         */
        suspend fun pairNotificationPreview(server: OpenCodeServer, callbackURL: String, ref: String, publicKey: String): String =
            ai.opencode.server.live.requestPairNotificationPreview(server, callbackURL, ref, publicKey)

        /** A prompt's reply in a `/message` page. */
        fun assistantReply(messagesJSON: ByteArray, afterMessageID: String, acrossSteers: Boolean = false): String? =
            ai.opencode.server.live.assistantReply(messagesJSON, afterMessageID, acrossSteers)

        /** A `/message` page's transcript items. */
        fun decodeTranscript(data: ByteArray): List<ai.opencode.server.model.OpenCodeTranscriptItem> =
            ai.opencode.server.live.decodeTranscript(data)

        override val liveValue: OpenCodeClient = OpenCodeClient(
            health = ::requestHealth,
            redeemPairing = ::requestRedeemPairing,
            homeSnapshot = ::requestHomeSnapshotSharingWindows,
            activeSessionParents = ::requestActiveSessionParents,
            homeAttention = ::requestHomeAttentionSharingWindows,
            searchSessions = ::requestSearchSessions,
            sessionPage = ::requestSessionPage,
            transcript = ::requestTranscript,
            message = ::requestMessage,
            sendPrompt = ::requestSendPrompt,
            sendDeliveredPrompt = ::requestSendDeliveredPrompt,
            assistantReply = ::requestAssistantReply,
            runReply = ::requestRunReply,
            uploadFile = ::uploadFile,
            waitForIdle = { server, sessionID -> requestWaitForIdle(server, sessionID, dependency(Clock)) },
            isSessionActive = ::requestIsSessionActive,
            runMessages = ::requestRunMessages,
            interrupt = ::requestInterrupt,
            createSession = ::requestCreateSession,
            deleteSession = ::requestDeleteSession,
            sessionFamily = ::requestSessionFamily,
            modelCatalog = ::requestModelCatalog,
            listDirectory = ::requestListDirectory,
            switchModel = ::requestSwitchModel,
            familyPendingRequests = ::requestFamilyPendingRequests,
            pendingInputs = ::requestPendingInputs,
            setPendingInputDelivery = ::requestSetPendingInputDelivery,
            cancelPendingInput = ::requestCancelPendingInput,
            replyPermission = ::requestReplyPermission,
            replyForm = ::requestReplyForm,
            cancelForm = ::requestCancelForm,
            getSession = ::requestGetSession,
            notifyPluginStatus = ::requestNotifyPluginStatus,
            runningShells = ::requestRunningShells,
            runningJobs = ::requestRunningJobs,
            cancelJob = ::requestCancelJob,
            shell = ::requestShell,
            shellOutput = ::requestShellOutput,
            stopShell = ::requestStopShell,
            moveToBackground = ::requestMoveToBackground,
            viewSession = ::requestViewSession,
            contextUsage = ::requestContextUsage,
            renameSession = ::requestRenameSession,
            moveSession = ::requestMoveSession,
            archiveSession = ::requestArchiveSession,
            unarchiveSession = ::requestUnarchiveSession,
            archivedSessions = ::requestArchivedSessions,
            setAlwaysNotify = ::requestSetAlwaysNotify,
            compactSession = ::requestCompactSession,
            stageRevert = ::requestStageRevert,
            clearRevert = ::requestClearRevert,
            forkSession = ::requestForkSession,
            exportSession = ::requestExportSession,
            historyPage = ::requestHistoryPage,
            serverCapabilities = ::requestServerCapabilities,
            setMCPConnected = ::requestSetMCPConnected,
            startIntegrationOAuth = ::requestStartIntegrationOAuth,
            integrationOAuthStatus = ::requestIntegrationOAuthStatus,
            completeIntegrationOAuth = ::requestCompleteIntegrationOAuth,
            cancelIntegrationOAuth = ::requestCancelIntegrationOAuth,
            skills = ::requestSkills,
            agents = ::requestAgents,
            primaryAgents = ::requestPrimaryAgents,
            switchAgent = ::requestSwitchAgent,
            commands = ::requestCommands,
            findFiles = ::requestFindFiles,
            mentionSessions = ::requestMentionSessions,
            sendCommand = ::requestSendCommand,
            cachedMarkdownImage = { dependency(MarkdownImageCache).image("markdown:$it") },
            markdownImage = { server, directory, source, fallback -> requestMarkdownImage(server, directory, source, fallback) },
            events = ::subscribeEventsSharingWindows,
        )

        /**
         * Inert: endpoints a test doesn't override throw `AssertionError`, except routine reads
         * (context, capabilities, autocomplete, background moves, Markdown images), which default
         * to inert values.
         */
        override val testValue: OpenCodeClient
            get() {
                val images = MarkdownImageCache()
                return OpenCodeClient(
                    health = { unimplemented("health") },
                    redeemPairing = { unimplemented("redeemPairing") },
                    homeSnapshot = { unimplemented("homeSnapshot") },
                    activeSessionParents = { _, _, _ -> emptyMap() },
                    homeAttention = { _, _, _ -> unimplemented("homeAttention") },
                    searchSessions = { _, _, _ -> unimplemented("searchSessions") },
                    transcript = { _, _, _ -> unimplemented("transcript") },
                    message = { _, _, _ -> unimplemented("message") },
                    sendPrompt = { _, _, _, _, _, _, _ -> unimplemented("sendPrompt") },
                    sendDeliveredPrompt = { _, _, _, _ -> unimplemented("sendDeliveredPrompt") },
                    assistantReply = { _, _, _ -> unimplemented("assistantReply") },
                    runReply = { _, _, _ -> unimplemented("runReply") },
                    uploadFile = { _, _, _, _ -> unimplemented("uploadFile") },
                    waitForIdle = { _, _ -> unimplemented("waitForIdle") },
                    isSessionActive = { _, _ -> unimplemented("isSessionActive") },
                    runMessages = { _, _, _ -> unimplemented("runMessages") },
                    interrupt = { _, _ -> unimplemented("interrupt") },
                    createSession = { _, _, _ -> unimplemented("createSession") },
                    deleteSession = { _, _ -> unimplemented("deleteSession") },
                    sessionFamily = { _, _ -> unimplemented("sessionFamily") },
                    modelCatalog = { _, _ -> unimplemented("modelCatalog") },
                    listDirectory = { _, _ -> unimplemented("listDirectory") },
                    switchModel = { _, _, _ -> unimplemented("switchModel") },
                    familyPendingRequests = { _, _, _ -> unimplemented("familyPendingRequests") },
                    pendingInputs = { _, _ -> unimplemented("pendingInputs") },
                    setPendingInputDelivery = { _, _, _, _ -> unimplemented("setPendingInputDelivery") },
                    cancelPendingInput = { _, _, _ -> unimplemented("cancelPendingInput") },
                    replyPermission = { _, _, _, _, _ -> unimplemented("replyPermission") },
                    replyForm = { _, _, _, _ -> unimplemented("replyForm") },
                    cancelForm = { _, _, _, _ -> unimplemented("cancelForm") },
                    getSession = { _, _ -> unimplemented("getSession") },
                    notifyPluginStatus = { unimplemented("notifyPluginStatus") },
                    runningShells = { _, _ -> unimplemented("runningShells") },
                    runningJobs = { _, _ -> emptyList() },
                    cancelJob = { _, _ -> },
                    shell = { _, _, _ -> unimplemented("shell") },
                    shellOutput = { _, _, _, _, _ -> unimplemented("shellOutput") },
                    stopShell = { _, _, _ -> unimplemented("stopShell") },
                    moveToBackground = { _, _ -> },
                    viewSession = { _, _, _ -> unimplemented("viewSession") },
                    contextUsage = { _, _ -> OpenCodeContextReading.Inconclusive },
                    renameSession = { _, _, _ -> unimplemented("renameSession") },
                    moveSession = { _, _, _ -> throw OpenCodeClientError("Unstubbed") },
                    archiveSession = { _, _ -> unimplemented("archiveSession") },
                    unarchiveSession = { _, _ -> unimplemented("unarchiveSession") },
                    archivedSessions = { _, _, _ -> unimplemented("archivedSessions") },
                    setAlwaysNotify = { _, _, _ -> unimplemented("setAlwaysNotify") },
                    compactSession = { _, _ -> unimplemented("compactSession") },
                    stageRevert = { _, _, _ -> unimplemented("stageRevert") },
                    clearRevert = { _, _ -> unimplemented("clearRevert") },
                    forkSession = { _, _ -> unimplemented("forkSession") },
                    exportSession = { _, _ -> unimplemented("exportSession") },
                    historyPage = { _, _, _ -> unimplemented("historyPage") },
                    serverCapabilities = { OpenCodeServerCapabilities.empty },
                    setMCPConnected = { _, _, _ -> },
                    startIntegrationOAuth = { _, _ -> throw OpenCodeClientError("Unstubbed") },
                    integrationOAuthStatus = { _, _, _ -> OpenCodeOAuthStatus.Pending },
                    completeIntegrationOAuth = { _, _, _, _ -> },
                    cancelIntegrationOAuth = { _, _, _ -> },
                    skills = { _, _ -> emptyList() },
                    agents = { _, _ -> emptyList() },
                    primaryAgents = { _, _ -> emptyList() },
                    switchAgent = { _, _, _ -> },
                    commands = { _, _ -> emptyList() },
                    findFiles = { _, _, _, _ -> emptyList() },
                    mentionSessions = { _, _, _ -> emptyList() },
                    sendCommand = { _, _, _, _, _, _ -> },
                    cachedMarkdownImage = { images.image("markdown:$it") },
                    markdownImage = { server, directory, source, fallback -> requestMarkdownImage(server, directory, source, fallback, images) },
                    events = { unimplemented("events") },
                )
            }

        private fun unimplemented(name: String): Nothing = throw AssertionError("Unimplemented: OpenCodeClient.$name")
    }
}
