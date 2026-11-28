package ai.opencode.server.live

import ai.opencode.core.log.AppLog
import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodePermissionReply
import ai.opencode.server.model.OpenCodePermissionRequest
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeShellOutputPage
import ai.opencode.server.model.PromptContext
import ai.opencode.server.model.nonEmpty
import ai.opencode.server.retry.RequestIdempotency
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/** A location-scoped read boots that location's service graph (MCP processes too) on first touch. */
internal val LOCATION_BOOT_TIMEOUT = 60.seconds

internal suspend fun requestFamilyPendingRequests(server: OpenCodeServer, sessionIDs: List<String>, directory: String): OpenCodePendingRequests {
    val requests = requestLocationPendingRequests(server, directory)
    val family = sessionIDs.toSet()
    return OpenCodePendingRequests(
        permissions = requests.permissions.filter { it.sessionID in family },
        forms = requests.forms.filter { it.sessionID == "global" || it.sessionID in family },
    )
}

private suspend fun requestLocationPendingRequests(server: OpenCodeServer, directory: String): OpenCodePendingRequests {
    val query = locationQuery(directory)
    // Booting a location routinely exceeds the default 10 s: treat that as slow, not unreachable.
    val (permissions, forms) = coroutineScope {
        val permissions = async {
            capturedRequest<DataPayload<List<OpenCodePermissionRequest>>>("/api/permission/request", query, LOCATION_BOOT_TIMEOUT, server)
        }
        val forms = async { capturedRequest<DataPayload<List<OpenCodeFormRequest>>>("/api/form", query, LOCATION_BOOT_TIMEOUT, server) }
        permissions.await() to forms.await()
    }
    return OpenCodePendingRequests(permissions.getOrThrow().data, forms.getOrThrow().data)
}

internal const val CONCURRENT_ATTENTION_DIRECTORIES = 4

private suspend fun requestLocationPendingRequests(server: OpenCodeServer, directories: List<String>): List<OpenCodePendingRequests> {
    val slots = Semaphore(CONCURRENT_ATTENTION_DIRECTORIES)
    return coroutineScope {
        val reads = mutableListOf<Deferred<OpenCodePendingRequests>>()
        for (directory in directories) {
            reads += async { slots.withPermit { requestLocationPendingRequests(server, directory) } }
        }
        reads.awaitAll()
    }
}

internal suspend fun requestHomeAttention(server: OpenCodeServer, roots: List<OpenCodeSession>, directories: List<String>): Set<String> {
    val pendingSessionIDs = LinkedHashSet<String>()
    for (requests in requestLocationPendingRequests(server, directories)) {
        pendingSessionIDs += requests.permissions.map { it.sessionID }
        pendingSessionIDs += requests.forms.map { it.sessionID }.filter { it != "global" }
    }
    val attentionRoots = LinkedHashSet<String>()
    val knownRoots = roots.associate { it.id to it.id }.toMutableMap()
    for (sessionID in pendingSessionIDs) attentionRoots += requestRootSessionID(server, sessionID, knownRoots)
    return attentionRoots
}

private suspend fun requestRootSessionID(server: OpenCodeServer, sessionID: String, knownRoots: MutableMap<String, String>): String {
    var currentID = sessionID
    val visited = HashSet<String>()
    val lineage = mutableListOf<String>()
    while (visited.add(currentID)) {
        knownRoots[currentID]?.let { rootID ->
            for (id in lineage) knownRoots[id] = rootID
            return rootID
        }
        lineage += currentID
        val payload = request<DataPayload<SessionPayload>>("/api/session/$currentID", server = server)
        val parentID = payload.data.parentID
        if (parentID == null) {
            for (id in lineage) knownRoots[id] = payload.data.id
            return payload.data.id
        }
        currentID = parentID
    }
    return sessionID
}

@Serializable private data class PermissionReplyBody(val decision: OpenCodePermissionReply, val message: String? = null)
@Serializable private data class FormReplyBody(val answer: Map<String, OpenCodeFormValue>)

internal suspend fun requestReplyPermission(
    server: OpenCodeServer,
    sessionID: String,
    requestID: String,
    reply: OpenCodePermissionReply,
    message: String?,
) {
    perform(
        "/api/session/$sessionID/permission/$requestID/reply",
        method = "POST",
        body = encodedBody(PermissionReplyBody(reply, message?.nonEmpty), "permission response"),
        server = server,
    )
}

internal suspend fun requestReplyForm(server: OpenCodeServer, sessionID: String, formID: String, answer: Map<String, OpenCodeFormValue>) {
    val body = encodedBody(FormReplyBody(answer), "form response")
    val query = if (sessionID == "global") locationQuery(PromptContext.formLocationDirectory.current()) else emptyList()
    settleForm(server, sessionID, formID, FormSettlement.Answered(answer)) {
        perform("/api/session/$sessionID/form/$formID/reply", query = query, method = "POST", body = body, server = server)
    }
}

internal suspend fun requestCancelForm(server: OpenCodeServer, sessionID: String, formID: String, message: String?) {
    val query = (if (sessionID == "global") locationQuery(PromptContext.formLocationDirectory.current()) else emptyList()).toMutableList()
    val reason = message?.nonEmpty
    if (reason != null) query += "message" to reason
    settleForm(server, sessionID, formID, FormSettlement.Cancelled(reason)) {
        perform("/api/session/$sessionID/form/$formID", query = query, method = "DELETE", server = server)
    }
}

@Serializable
enum class FormStatus {
    @SerialName("pending") Pending,
    @SerialName("answered") Answered,
    @SerialName("cancelled") Cancelled,
}

/** A form's state on the server: what settled it, if anything did. */
@Serializable
data class FormState(
    val status: FormStatus,
    val answer: Map<String, OpenCodeFormValue>? = null,
    val message: String? = null,
)

/** How a reply or dismissal asks a form to settle. */
sealed interface FormSettlement {
    data class Answered(val answer: Map<String, OpenCodeFormValue>) : FormSettlement
    data class Cancelled(val message: String?) : FormSettlement

    /** The form settled this way: the same answer, or the same reason. */
    fun matches(state: FormState): Boolean = when (this) {
        is Answered -> state.status == FormStatus.Answered && state.answer == answer
        is Cancelled -> state.status == FormStatus.Cancelled && state.message?.nonEmpty == message
    }

    /** Why a form settled otherwise can't take this one. */
    fun conflict(state: FormState): String = when (this) {
        is Answered -> if (state.status == FormStatus.Answered) localized(R.string.server_request_already_answered_elsewhere) else localized(R.string.server_request_already_dismissed)
        is Cancelled -> if (state.status == FormStatus.Answered) localized(R.string.server_request_already_answered) else localized(R.string.server_request_already_dismissed_elsewhere)
    }

    val logName: String
        get() = when (this) {
            is Answered -> "reply"
            is Cancelled -> "dismissal"
        }
}

@Serializable private data class FormDetail(val state: FormState)

/** Where a form stands on the server now. */
internal suspend fun requestFormState(server: OpenCodeServer, sessionID: String, formID: String): FormState =
    request<DataPayload<FormDetail>>(
        "/api/session/$sessionID/form/$formID",
        query = if (sessionID == "global") locationQuery(PromptContext.formLocationDirectory.current()) else emptyList(),
        server = server,
    ).data.state

/**
 * Replies to and dismissals of a form aren't repeatable: one that landed answers a repeat with
 * 409. When the connection broke after sending (a pooled connection the phone's sleep left dead
 * fails the first POST while the event stream stays up), or the server says it's settled, the
 * form's state says whether it took: settled with this answer (or reason) is success, still
 * pending sends once more, settled otherwise or gone is final ([OpenCodeClientError.requestSettled]),
 * and anything else stays the failure.
 */
private suspend fun settleForm(
    server: OpenCodeServer,
    sessionID: String,
    formID: String,
    wanted: FormSettlement,
    send: suspend () -> Unit,
) {
    var resent = false
    while (true) {
        val error = try {
            send()
            return
        } catch (failure: OpenCodeClientError) {
            failure
        }
        if (isGone(error)) throw settled(error, localized(R.string.server_request_no_longer_waiting))
        if (!error.mayHaveReachedServer && error.statusCode != 409) throw error
        val state = try {
            requestFormState(server, sessionID, formID)
        } catch (read: OpenCodeClientError) {
            throw if (isGone(read)) settled(error, localized(R.string.server_request_no_longer_waiting)) else error
        }
        if (wanted.matches(state)) return
        if (state.status != FormStatus.Pending) throw settled(error, wanted.conflict(state))
        if (error.statusCode == 409 || resent) throw error
        AppLog.send.notice { "form ${wanted.logName} still pending after ${error.logSummary}: resending" }
        resent = true
    }
}

/** The form, or its session, no longer exists on the server. */
private fun isGone(error: OpenCodeClientError): Boolean = error.statusCode == 404 && !error.isLocationMissing

private fun settled(error: OpenCodeClientError, message: String): OpenCodeClientError =
    error.copy(message = message, requestSettled = true)

// Background jobs (experimental).

/** The session's running jobs and those of every session below it. */
internal suspend fun requestRunningJobs(server: OpenCodeServer, sessionID: String): List<OpenCodeJob> =
    request<DataPayload<List<JobInfo>>>(
        "/api/experimental/job",
        query = listOf("sessionID" to sessionID, "descendants" to "true", "status" to "running"),
        server = server,
    ).data.map { it.job() }.filter { it.isRunning }

internal suspend fun requestCancelJob(server: OpenCodeServer, jobID: String) {
    // Cancelling a job that already finished does nothing, so a second copy is harmless.
    perform("/api/experimental/job/$jobID/cancel", method = "POST", idempotency = RequestIdempotency.Idempotent, server = server)
}

// Shells.

internal suspend fun requestRunningShells(server: OpenCodeServer, directory: String): List<OpenCodeShell> =
    request<DataPayload<List<ShellInfo>>>("/api/shell", query = locationQuery(directory), server = server)
        .data.filter { it.status == "running" }.map { it.shell(isListed = true) }

internal suspend fun requestShell(server: OpenCodeServer, directory: String, shellID: String): OpenCodeShell =
    request<DataPayload<ShellInfo>>("/api/shell/$shellID", query = locationQuery(directory), server = server).data.shell(isListed = true)

internal suspend fun requestShellOutput(server: OpenCodeServer, directory: String, shellID: String, cursor: Int, limit: Int): OpenCodeShellOutputPage =
    request<DataPayload<OpenCodeShellOutputPage>>(
        "/api/shell/$shellID/output",
        query = locationQuery(directory) + listOf("cursor" to cursor.toString(), "limit" to limit.toString()),
        timeout = 20.seconds,
        server = server,
    ).data

internal suspend fun requestStopShell(server: OpenCodeServer, directory: String, shellID: String) {
    perform("/api/shell/$shellID", query = locationQuery(directory), method = "DELETE", server = server)
}
