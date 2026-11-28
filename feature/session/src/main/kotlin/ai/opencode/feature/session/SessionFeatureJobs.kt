package ai.opencode.feature.session

import ai.opencode.core.haptics.Haptic
import ai.opencode.core.log.AppLog
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.cancellable
import ai.opencode.core.strings.localized
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.CancelID
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.sheet.clientError
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionActivity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

// Background jobs, as the desktop's activity pill reads them: listed when the session opens, comes
// back, reconnects or finds a new subagent, then kept by `experimental.job.updated` events. Never
// polled.

/** A script the session moved to the background, while its job runs. */
data class BackgroundScript(val id: String, val label: String, val startedAt: Instant?)

/** Lists the open session's running jobs, its subagents' included. */
internal fun SessionFeature.refreshJobs(state: State): Reduced<State, Action> {
    val sessionID = state.session?.id ?: return Reduced(state)
    val server = state.server
    val client = openCodeClient
    val next = if (state.jobChangesDuringRefresh.isEmpty()) state else state.copy(jobChangesDuringRefresh = emptyMap())
    return Reduced(
        next,
        Effect.run<Action> { send ->
            try {
                send(Action.JobsResponse(sessionID, client.runningJobs(server, sessionID)))
            } catch (error: OpenCodeClientError) {
                AppLog.network.notice { "Couldn’t list background jobs: ${error.logSummary}" }
            }
        }.cancellable(CancelID.Jobs, cancelInFlight = true),
    )
}

internal fun SessionFeature.reduceJobs(state: State, action: Action): Reduced<State, Action>? = when (action) {
    is Action.JobsResponse -> {
        if (state.session?.id != action.sessionID) {
            Reduced(state)
        } else {
            val jobs = action.jobs.filter { it.isRunning }.associateByTo(LinkedHashMap()) { it.id }
            for ((id, job) in state.jobChangesDuringRefresh) if (job != null) jobs[id] = job else jobs.remove(id)
            Reduced(state.copy(jobs = jobs, jobChangesDuringRefresh = emptyMap()))
        }
    }

    is Action.JobUpdated -> {
        val job = action.job
        val running = job.takeIf { it.isRunning }
        if (running != null && !state.ownsJob(running) && job.id !in state.jobs) {
            Reduced(state)
        } else {
            val jobs = if (running != null) state.jobs + (job.id to running) else state.jobs - job.id
            Reduced(state.copy(jobs = jobs, jobChangesDuringRefresh = state.jobChangesDuringRefresh + (job.id to running)))
        }
    }

    is Action.BackgroundScriptStopTapped -> {
        val jobID = action.jobID
        if (jobID in state.stoppingIDs) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            val server = state.server
            val client = openCodeClient
            Reduced(
                state.copy(stoppingIDs = state.stoppingIDs + jobID),
                Effect.run<Action> { send ->
                    val failed = stopFailed(listOf(suspend { client.cancelJob(server, jobID) }))
                    send(Action.BackgroundStopFinished(jobID, isSubagent = false, failed = failed))
                },
            )
        }
    }

    is Action.BackgroundSubagentStopTapped -> {
        val sessionID = action.sessionID
        if (sessionID in state.stoppingIDs) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            val jobs = state.subagentJobIDs(sessionID)
            val interrupts = sessionID in state.activeFamilySessionIDs
            val server = state.server
            val client = openCodeClient
            val calls = buildList<suspend () -> Unit> {
                if (interrupts) add { client.interrupt(server, sessionID) }
                for (id in jobs) add { client.cancelJob(server, id) }
            }
            Reduced(
                state.copy(stoppingIDs = state.stoppingIDs + sessionID),
                Effect.run<Action> { send ->
                    send(Action.BackgroundStopFinished(sessionID, isSubagent = true, failed = stopFailed(calls)))
                },
            )
        }
    }

    is Action.BackgroundShellStopTapped -> {
        val shellID = action.shellID
        val directory = state.session?.directory
        if (directory == null || shellID in state.stoppingIDs) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            val server = state.server
            val client = openCodeClient
            Reduced(
                state.copy(stoppingIDs = state.stoppingIDs + shellID),
                Effect.run<Action> { send ->
                    val error = clientError { client.stopShell(server, directory, shellID) }
                    if (error != null) AppLog.network.notice { "Couldn’t stop background work: ${error.logSummary}" }
                    // 404: the shell already ended.
                    send(Action.BackgroundShellStopFinished(shellID, failed = error != null && error.statusCode != 404))
                },
            )
        }
    }

    is Action.BackgroundShellStopFinished -> {
        val shellID = action.shellID
        val next = state.copy(stoppingIDs = state.stoppingIDs - shellID)
        if (action.failed) {
            haptics.play(Haptic.error)
            Reduced(appendInfo(next, localized(R.string.session_shell_stop_failed)))
        } else {
            Reduced(next.copy(shells = next.shells.filter { it.id != shellID }, endedShellIDs = next.endedShellIDs + shellID))
        }
    }

    is Action.BackgroundStopFinished -> {
        val next = state.copy(stoppingIDs = state.stoppingIDs - action.id)
        if (!action.failed) {
            Reduced(next)
        } else {
            haptics.play(Haptic.error)
            val text = localized(if (action.isSubagent) R.string.session_background_work_stop_subagent_failed else R.string.session_background_work_stop_script_failed)
            Reduced(appendInfo(next, text))
        }
    }

    else -> null
}

/** Runs every call at once; true when any failed. */
private suspend fun stopFailed(calls: List<suspend () -> Unit>): Boolean = coroutineScope {
    calls.map { call ->
        async {
            try {
                call()
                false
            } catch (error: OpenCodeClientError) {
                AppLog.network.notice { "Couldn’t stop background work: ${error.logSummary}" }
                true
            }
        }
    }.awaitAll().any { it }
}

/** The session's parents, as far as its family is known. */
private val State.familyParents: Map<String, String>
    get() = HashMap<String, String>().also { parents -> for (member in familySessions) member.parentID?.let { parents[member.id] = it } }

/**
 * A job this screen keeps: owned by its session or a known member of its family, or started by one
 * (a subagent job whose child isn't listed yet).
 */
private fun State.ownsJob(job: OpenCodeJob): Boolean {
    val selectedID = session?.id ?: return false
    return listOf(job.ownerSessionID, job.sessionID).any { owner -> owner == selectedID || familySessions.any { it.id == owner } }
}

/** Running jobs owned by [sessionID] or any session below it; a subagent job belongs to the child it runs. */
internal fun State.subagentJobIDs(sessionID: String, parents: Map<String, String> = familyParents): List<String> =
    jobs.values.filter { job ->
        val owner = job.ownerSessionID
        owner == sessionID || sessionID in SessionActivity.ancestors(setOf(owner), parents)
    }.map { it.id }

/** Drops the jobs a deleted session owned. */
internal fun State.pruningJobs(ownedBy: String): State {
    if (jobs.values.none { it.ownerSessionID == ownedBy }) return this
    return copy(jobs = jobs.filterValues { it.ownerSessionID != ownedBy })
}

/**
 * The session's own Code Mode scripts running in the background, named by their first line. Every script runs as a
 * job, so one still blocking the turn has a running job too; only one whose `execute` call ended with the job's ID
 * has moved to the background.
 */
val State.backgroundScripts: List<BackgroundScript>
    get() {
        val sessionID = session?.id ?: return emptyList()
        val running = jobs.values.filter { it.kind == OpenCodeJob.Kind.CodeMode && it.sessionID == sessionID }
        if (running.isEmpty()) return emptyList()
        val labels = HashMap<String, String?>()
        for (item in items) {
            val call = (item as? OpenCodeTranscriptItem.Tool)?.call ?: continue
            if (call.name != "execute" || call.status != OpenCodeToolCall.Status.Completed) continue
            val jobID = call.metadata["jobID"]?.string?.takeIf { it.isNotEmpty() } ?: continue
            labels[jobID] = firstLine((call.input as? OpenCodeJSONValue.Object)?.values?.get("code")?.string)
        }
        return running.filter { it.id in labels }
            .map { BackgroundScript(it.id, labels[it.id] ?: it.id, it.startedAt) }
            .sortedBy { it.startedAt }
    }

private fun firstLine(code: String?): String? = code?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
