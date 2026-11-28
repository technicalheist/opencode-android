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
import ai.opencode.feature.media.BrowserFeature
import ai.opencode.feature.media.BrowserLinkPolicy
import ai.opencode.feature.media.BrowserLinkRoute
import ai.opencode.feature.media.BrowserReplyStatus
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.feature.media.LinkMenuAction
import ai.opencode.feature.media.LinkProbe
import ai.opencode.feature.transcript.markdown.OpenCodeMarkdownBlock
import ai.opencode.server.model.SessionLink
import ai.opencode.feature.transcript.markdown.sessionLinkIDs
import ai.opencode.feature.transcript.markdown.sessionLinkTarget
import ai.opencode.feature.transcript.SessionLinkTarget
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.keepingFirstPrompts
import java.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The browser, links, background work, and family updates.

/**
 * Opens a public web link in the app that claims it as a universal link, falling back to the in-app
 * browser or the system browser when none does.
 */
private fun SessionFeature.openLink(url: String, state: State): Reduced<State, Action> {
    // Pairing links are redeemed by AppFeature directly; never hand a single-use
    // `/auth/connect/<code>` URL to the browser or the in-app browser.
    if (OpenCodePairingLink.shouldHandleOpenURL(url)) return dropLinkProbe(state)
    // Excluded links (private hosts, credentials, signed queries) never reach the system's
    // universal-link lookup.
    if (!BrowserLinkPolicy.isPublicWebPage(url)) {
        val dropped = dropLinkProbe(state)
        return Reduced(dropped.state, Effect.merge(dropped.effect, openWithoutApps(url)))
    }
    // A later tap supersedes this one.
    val probe = LinkProbe(id = uuid.next(), url = url, sessionID = state.session?.id)
    val universalLinks = universalLinks
    return Reduced(
        state.copy(linkProbe = probe),
        Effect.run<Action> { send ->
            send(Action.UniversalLinkResponse(probe.id, openedInApp = universalLinks.open(url)))
        }.cancellable(CancelID.UniversalLink, cancelInFlight = true),
    )
}

/**
 * A reply's web link as it opens without Open Links in Apps: the in-app browser when it's on and
 * takes the link, else the system browser.
 */
private fun SessionFeature.openWithoutApps(url: String): Effect<Action> {
    val route = BrowserLinkPolicy.route(url, inAppBrowser = experimentalFeatures(ExperimentalFeature.inAppBrowser))
    if (route is BrowserLinkRoute.InAppBrowser) {
        return Effect.send(Action.Browser(BrowserFeature.Action.Open(route.url)))
    }
    val openURL = openURL
    return Effect.run { openURL.open(url) }
}

/**
 * Session IDs this transcript links, in its replies' Markdown, for resolving before their rows draw
 * (a fresh REST window).
 */
internal fun SessionFeature.sessionLinksIn(items: List<OpenCodeTranscriptItem>): Effect<Action> {
    val seen = HashSet<String>()
    val ids = items.flatMap { item ->
        (item as? OpenCodeTranscriptItem.AssistantMarkdown)?.let { sessionLinkIDs(OpenCodeMarkdownBlock.blocks(it.text)) }.orEmpty()
    }.filter { seen.add(it) }
    return if (ids.isEmpty()) Effect.none() else Effect.send(Action.SessionLinksAppeared(ids))
}

/**
 * A linked session ID. Home opens it (a subagent pushed over this screen); the session on screen
 * opens nothing.
 */
private fun SessionFeature.openSessionLink(id: String, state: State): Reduced<State, Action> {
    val drop = dropLinkProbe(state)
    if (id == drop.state.session?.id) return drop
    return Reduced(drop.state, Effect.merge(drop.effect, Effect.send(Action.Delegate(SessionFeature.Delegate.OpenSessionLink(id)))))
}

/** Forgets a link tap still waiting on the system, so its answer opens nothing. */
private fun dropLinkProbe(state: State): Reduced<State, Action> {
    if (state.linkProbe == null) return Reduced(state)
    return Reduced(state.copy(linkProbe = null), Effect.cancel(CancelID.UniversalLink))
}

/**
 * What [BrowserPage][ai.opencode.feature.media.BrowserPage]'s reply capsule shows, without the page
 * reading the session.
 */
val State.browserReplyStatus: BrowserReplyStatus?
    get() {
        val peek = browserPeek ?: return null
        if (peek.text != null) return BrowserReplyStatus.Ready
        return if (hasPendingRequests) BrowserReplyStatus.NeedsInput else BrowserReplyStatus.Working
    }

/** Closes the in-app browser at once and forgets its page. */
internal fun SessionFeature.closeBrowser(state: State): Reduced<State, Action> {
    val id = state.browser.pageID ?: return Reduced(state)
    val pageReader = pageReader
    return Reduced(state.copy(browser = state.browser.close()), Effect.run { pageReader.discard(id) })
}

/**
 * Local items need IDs that survive server refreshes replacing `items`; count-based IDs would
 * collide after a refresh resets the count.
 */
internal fun SessionFeature.appendInfo(state: State, text: String): State =
    state.copy(items = state.items + OpenCodeTranscriptItem.Info(id = "info-${uuid.next()}", text = text))

/**
 * How long a shell must block the run before the UI offers to move it to the background, in
 * seconds.
 */
internal const val backgroundHintDelay: Double = 3.0

/** The browser, links, background work, and family updates. Null: the action passes on. */
internal fun SessionFeature.reduceBrowserAndBackgroundWork(state: State, action: Action): Reduced<State, Action>? = when (action) {
    is Action.ShellOutputTapped -> {
        val session = state.session
        val shell = state.shells.firstOrNull { it.id == action.shellID }
        if (session == null || shell == null) Reduced(state)
        else Reduced(state.copy(shellOutput = ShellOutputFeature.State(server = state.server, directory = session.directory, shell = shell)))
    }

    is Action.LinkTapped -> {
        val id = SessionLink.id(action.url)
        if (id != null) openSessionLink(id, state) else openLink(action.url, state)
    }

    is Action.SessionLinksAppeared -> {
        val links = state.sessionLinks.toMutableMap()
        val unknown = mutableListOf<String>()
        for (id in action.ids) {
            if (links[id] != null) continue
            val session = if (state.session?.id == id) state.session else state.familySessions.firstOrNull { it.id == id }
            if (session != null) {
                links[id] = sessionLinkTarget(session)
            } else {
                links[id] = SessionLinkTarget.Resolving
                unknown += id
            }
        }
        val next = state.copy(sessionLinks = links)
        if (unknown.isEmpty()) Reduced(next)
        else Reduced(next, Effect.send(Action.Delegate(SessionFeature.Delegate.ResolveSessionLinks(unknown))))
    }

    is Action.UniversalLinkResponse -> {
        // Only the latest tap's own answer, on the session it was tapped in. Leaving the session
        // ends its effects, so a tap whose answer comes after a switch opens nothing, here or in the
        // browser: it belonged to the session the reader left. An app that took it has opened
        // regardless. A screen pushed over this one (a subagent) hides it: nothing opens under the
        // reader, in the browser or in this screen's hidden browser.
        val probe = state.linkProbe
        if (probe == null || probe.id != action.id || probe.sessionID != state.session?.id || state.isCovered) {
            Reduced(state)
        } else {
            val next = state.copy(linkProbe = null)
            if (action.openedInApp) Reduced(next) else Reduced(next, openWithoutApps(probe.url))
        }
    }

    Action.VisibleScreenChanged -> dropLinkProbe(state)

    is Action.LinkMenu -> linkMenu(action.action, action.url, state)

    is Action.Browser -> when (val browser = action.action) {
        BrowserFeature.Action.AskSent -> askSent(state)
        is BrowserFeature.Action.LinkMenu -> linkMenu(browser.action, browser.url, state)
        // A page opening is a later navigation than any tap still waiting.
        is BrowserFeature.Action.Open -> dropLinkProbe(state)
        else -> Reduced(state)
    }

    is Action.ShellOutput -> Reduced(state)

    is Action.BackgroundHintElapsed ->
        if (state.foregroundWork?.key != action.key) Reduced(state) else Reduced(state.copy(movableWorkKey = action.key))

    Action.MoveToBackgroundTapped -> moveToBackgroundTapped(state)

    is Action.AutoBackgroundClaimLapsed -> {
        // Never a retry's fresh claim. A delivery can settle the send (clearing its state) before
        // the outbox reports the claim unused; that claim still lapses.
        val prompt = state.promptStates[action.messageID]
        if (prompt != null && prompt.requestEpoch != action.requestEpoch) Reduced(state)
        else Reduced(releaseAutoBackgroundClaim(action.messageID, state))
    }

    is Action.MoveToBackgroundFinished -> {
        if (state.session?.id != action.sessionID) {
            Reduced(state)
        } else {
            val key = action.key
            var next = state.copy(autoBackgroundClaims = state.autoBackgroundClaims.filterValues { it != key })
            val error = action.error
            if (error != null) {
                next = next.copy(movedWorkKeys = next.movedWorkKeys - key)
                if (action.isAutomatic) {
                    Reduced(next)
                } else {
                    haptics.play(Haptic.error)
                    Reduced(appendInfo(next, localized(R.string.session_move_to_background_failed, error.message)))
                }
            } else {
                // Moved even if its send let the claim go meanwhile.
                next = next.copy(movedWorkKeys = next.movedWorkKeys + key)
                // The moved shell (or script) now outlives its tool call; list it for the capsule.
                val shells = refreshShells(next)
                val jobs = refreshJobs(shells.state)
                Reduced(jobs.state, Effect.merge(shells.effect, jobs.effect))
            }
        }
    }

    is Action.FamilyResponse -> {
        val queued = state.familyRead.isQueued
        val finished = state.copy(familyRead = CoalescedRead())
        val family = action.result.getOrNull()
        val applied = if (family != null) {
            // Only the last answer of a queued run makes the family fresh.
            familyResponse(family, finished, isLatest = !queued)
        } else {
            val error = action.result.exceptionOrNull()
            val summary = (error as? OpenCodeClientError)?.logSummary ?: error?.let { it::class.java.simpleName } ?: "client error"
            AppLog.network.notice("Family read failed: $summary")
            if (!finished.requestsConnectionIsAvailable || finished.pendingWorkPollSeconds != null) Reduced(finished) else pollPendingWork(finished)
        }
        if (!queued) {
            applied
        } else {
            // After applying: a new read forgets the run changes this one keeps.
            val again = refreshFamily(applied.state)
            Reduced(again.state, Effect.merge(applied.effect, again.effect))
        }
    }

    is Action.SessionSnapshotUpdated -> {
        val selected = state.session
        if (selected == null || selected.id != action.session.id) {
            Reduced(state)
        } else {
            val next = state.updateObservedSession(action.session)
            // Home's list snapshot is metadata only. The event/reconnect/resume that caused it
            // already routes through the single content owner.
            if (next.streamIsHealthy) Reduced(next) else Reduced(next, Effect.send(Action.RefreshRequested(RefreshReason.Snapshot)))
        }
    }

    is Action.FamilyRunStateChanged -> {
        val sessionID = action.sessionID
        val next = state.copy(
            familyRunChanges = state.familyRunChanges + (sessionID to action.isRunning),
            activeFamilySessionIDs = if (action.isRunning) state.activeFamilySessionIDs + sessionID else state.activeFamilySessionIDs - sessionID,
        )
        if (next.session?.id != sessionID) Reduced(next) else Reduced(next, Effect.send(Action.ExternalRunStateChanged(action.isRunning)))
    }

    is Action.FamilySessionRenamed -> {
        val sessionID = action.sessionID
        var next = state.renameVoiceTarget(sessionID, action.title)
        val index = next.familySessions.indexOfFirst { it.id == sessionID }
        if (index >= 0) {
            next = next.copy(familySessions = next.familySessions.toMutableList().also { it[index] = it[index].copy(serverTitle = action.title) })
            val session = next.session
            if (session?.id == sessionID) next = next.copy(session = session.copy(serverTitle = action.title))
        }
        Reduced(next)
    }

    is Action.FamilySessionDeleted -> {
        val sessionID = action.sessionID
        var next = state.copy(
            familySessions = state.familySessions.filter { it.id != sessionID },
            activeFamilySessionIDs = state.activeFamilySessionIDs - sessionID,
            familyTranscriptCache = state.familyTranscriptCache - sessionID,
        ).pruningJobs(ownedBy = sessionID)
        // Its requests went with it. Left here, reconciliation would keep them for an owner it never
        // rediscovers, docked forever.
        next = next.pruneRequests(removing = { _, owner -> owner == sessionID })
        val rootID = next.familyRootID
        if (next.session?.id == sessionID && rootID != null && next.familySessions.any { it.id == rootID }) {
            Reduced(next, Effect.send(Action.FamilyPicked(rootID)))
        } else {
            pollPendingWork(next)
        }
    }

    else -> null
}

/**
 * Ask about this page. The question goes with the page attached, and its reply shows in the page's
 * capsule.
 */
private fun SessionFeature.askSent(state: State): Reduced<State, Action> {
    if (state.session == null && state.draftProject == null) return Reduced(state)
    val serverID = serverID
    val ask = state.browser.takeAsk { serverID.messageID() } ?: return Reduced(state)
    haptics.play(Haptic.tap)
    return submit(
        messageID = ask.messageID,
        text = ask.text,
        attachments = emptyList(),
        files = emptyList(),
        delivery = OpenCodeDelivery.Steer,
        page = ask.page,
        isRetry = false,
        state = state.copy(browser = ask.state),
    )
}

/** A choice from a link's menu. */
private fun SessionFeature.linkMenu(action: LinkMenuAction, url: String, state: State): Reduced<State, Action> = when (action) {
    LinkMenuAction.OpenInAppBrowser -> {
        // Navigation: quiet, like a tap on the link.
        val drop = dropLinkProbe(state)
        val route = BrowserLinkPolicy.route(url, inAppBrowser = experimentalFeatures(ExperimentalFeature.inAppBrowser))
        if (route !is BrowserLinkRoute.InAppBrowser) drop
        else Reduced(drop.state, Effect.merge(drop.effect, Effect.send(Action.Browser(BrowserFeature.Action.Open(route.url)))))
    }

    LinkMenuAction.OpenInSafari -> {
        if (OpenCodePairingLink.shouldHandleOpenURL(url)) {
            dropLinkProbe(state)
        } else {
            haptics.play(Haptic.selection)
            val drop = dropLinkProbe(state)
            val openURL = openURL
            Reduced(drop.state, Effect.merge(drop.effect, Effect.run { openURL.open(url) }))
        }
    }

    // Navigation: quiet, like a tap on the link.
    LinkMenuAction.OpenInApp -> openLink(url, state)

    LinkMenuAction.Copy -> {
        val shareURL = OpenCodePairingLink.shareableURL(url)
        if (shareURL == null) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            val pasteboard = pasteboard
            Reduced(state, Effect.run { pasteboard.setURL(shareURL) })
        }
    }

    LinkMenuAction.Share -> {
        if (OpenCodePairingLink.shareableURL(url) != null) haptics.play(Haptic.selection)
        Reduced(state)
    }

    LinkMenuAction.OpenSession -> {
        val id = SessionLink.id(url)
        if (id == null) Reduced(state) else openSessionLink(id, state)
    }

    LinkMenuAction.CopySessionID -> {
        val id = SessionLink.id(url)
        if (id == null) {
            Reduced(state)
        } else {
            haptics.play(Haptic.tap)
            val pasteboard = pasteboard
            Reduced(state, Effect.run { pasteboard.setString(id) })
        }
    }
}

private fun SessionFeature.moveToBackgroundTapped(state: State): Reduced<State, Action> {
    val session = state.session
    val key = state.movableWorkKey
    if (session == null || !state.showsMoveToBackground || key == null) return Reduced(state)
    haptics.play(Haptic.tap)
    val server = state.server
    val client = openCodeClient
    return Reduced(
        state.copy(movedWorkKeys = state.movedWorkKeys + key),
        Effect.run<Action> { send ->
            try {
                client.moveToBackground(server, session.id)
                send(Action.MoveToBackgroundFinished(session.id, key, null))
            } catch (error: OpenCodeClientError) {
                send(Action.MoveToBackgroundFinished(session.id, key, error))
            }
        }.cancellable(CancelID.MoveToBackground, cancelInFlight = true),
    )
}

private fun SessionFeature.familyResponse(
    family: ai.opencode.server.model.OpenCodeSessionFamily,
    state: State,
    isLatest: Boolean,
): Reduced<State, Action> {
    // A previous session's response, still in flight when a live call or recording carried this
    // screen to another session.
    val familyRootID = state.familyRootID
    if (familyRootID != null && family.sessions.none { it.id == familyRootID }) return Reduced(state)
    val selectedWasActive = state.isExternallyActive
    val previousFamilyIDs = state.familySessions.map { it.id }
    // Discovery requests can overlap invalidations. A narrower response must not temporarily forget
    // an already-discovered child: that would disable its task card and reject the child request
    // response already being submitted. Explicit deletion events retire family members.
    val familyIDs = family.sessions.map { it.id }.toSet()
    var members = family.sessions.keepingFirstPrompts(state.familySessions) + state.familySessions.filter { it.id !in familyIDs }
    val current = state.session
    if (current != null) {
        val index = members.indexOfFirst { it.id == current.id }
        if (index >= 0 && members[index].updatedAt <= current.updatedAt) {
            members = members.toMutableList().also { it[index] = it[index].copy(revertMessageID = current.revertMessageID) }
        }
    }
    val familyIDsChanged = members.map { it.id } != previousFamilyIDs
    val active = family.activeSessionIDs.toMutableSet()
    for ((sessionID, isRunning) in state.familyRunChanges) {
        if (isRunning) active += sessionID else active -= sessionID
    }
    val readAt = if (isLatest) date.now() else state.familyReadAt
    var next = state.copy(familyReadAt = readAt, familySessions = members, activeFamilySessionIDs = active)
    val selected = next.session ?: return Reduced(next)
    val rootID = next.familyRootID
    if (family.sessions.none { it.id == selected.id } && rootID != null && family.sessions.any { it.id == rootID }) {
        return Reduced(next, Effect.send(Action.FamilyPicked(rootID)))
    }
    val selectedIsActive = selected.id in active
    val shouldPoll = next.requestsConnectionIsAvailable && (next.pendingWorkPollSeconds == null || familyIDsChanged)
    var poll: Effect<Action> = Effect.none()
    if (shouldPoll) {
        val polled = pollPendingWork(next)
        next = polled.state
        poll = polled.effect
    }
    // A new member's jobs that started before it was known were dropped as another tree's.
    if (members.any { it.id !in previousFamilyIDs }) {
        val jobs = refreshJobs(next)
        next = jobs.state
        poll = Effect.merge(poll, jobs.effect)
    }
    return if (selectedIsActive == selectedWasActive) {
        Reduced(next, poll)
    } else {
        Reduced(next, Effect.merge(poll, Effect.send(Action.ExternalRunStateChanged(selectedIsActive))))
    }
}

/**
 * Initial cached work does not trigger onChange; activation arms its hint too, using the server's
 * start time rather than restarting three seconds.
 */
internal fun SessionFeature.scheduleBackgroundHint(state: State): Reduced<State, Action> {
    val work = state.foregroundWork
    if (!state.offersManualMoveToBackground || work == null) {
        return Reduced(state.copy(movableWorkKey = null), Effect.cancel(CancelID.BackgroundHint))
    }
    if (state.movableWorkKey == work.key) return Reduced(state)
    val elapsed = work.startedAt?.let { Duration.between(it, date.now()).toMillis() / 1000.0 } ?: 0.0
    val delay = minOf(backgroundHintDelay, maxOf(0.0, backgroundHintDelay - elapsed))
    val clock = clock
    return Reduced(
        state.copy(movableWorkKey = null),
        Effect.run<Action> { send ->
            clock.sleep((delay * 1_000).toInt().milliseconds)
            send(Action.BackgroundHintElapsed(work.key))
        }.cancellable(CancelID.BackgroundHint, cancelInFlight = true),
    )
}

/**
 * Re-reads the open session's subagent family and running shells on resume and (when visible with
 * active or background work) watchdog reconciliation, bounded to the open session, so subagents,
 * shells, or external active runs that finished while away or without a live event clear promptly
 * without polling idle locations.
 */
internal fun SessionFeature.refreshBackgroundWork(state: State, reason: RefreshReason): Reduced<State, Action> {
    val refreshes = when (reason) {
        RefreshReason.Resume -> true
        RefreshReason.Watchdog -> state.isVisible &&
            (state.hasBackgroundWork || state.isExternallyActive || state.activeFamilySessionIDs.isNotEmpty() || state.shells.isNotEmpty())
        else -> false
    }
    if (!refreshes) return Reduced(state)
    val family = refreshFamily(state)
    val shells = refreshShells(family.state)
    // Jobs arrive as events; only a return re-lists them.
    val jobs = if (reason == RefreshReason.Resume) refreshJobs(shells.state) else Reduced(shells.state)
    return Reduced(jobs.state, Effect.merge(family.effect, shells.effect, jobs.effect))
}

/** Reads the family again. `coalescing`: while a read is in flight, one more follows it instead of this replacing it. */
internal fun SessionFeature.refreshFamily(state: State, coalescing: Boolean = false): Reduced<State, Action> {
    val rootID = state.familyRootID ?: return Reduced(state)
    val root = state.familySessions.firstOrNull { it.id == rootID } ?: return Reduced(state)
    if (coalescing && state.familyRead.isInFlight) {
        return Reduced(state.copy(familyRead = CoalescedRead(isInFlight = true, isQueued = true)))
    }
    // The new read's active status postdates every change seen so far.
    val server = state.server
    val client = openCodeClient
    return Reduced(
        state.copy(familyRead = CoalescedRead(isInFlight = true), familyRunChanges = emptyMap()),
        Effect.run<Action> { send ->
            try {
                send(Action.FamilyResponse(Result.success(client.sessionFamily(server, root))))
            } catch (error: OpenCodeClientError) {
                send(Action.FamilyResponse(Result.failure(error)))
            }
        }.cancellable(CancelID.FamilyRefresh, cancelInFlight = true),
    )
}

internal fun SessionFeature.refreshShells(state: State): Reduced<State, Action> {
    val session = state.session ?: return Reduced(state)
    var next = state
    if (next.shells.isNotEmpty()) next = next.copy(restoredShellIDs = next.shells.map { it.id }.toSet())
    val server = next.server
    val client = openCodeClient
    return Reduced(
        next,
        Effect.run<Action> { send ->
            try {
                val shells = client.runningShells(server, session.directory)
                send(Action.ShellsResponse(session.id, Result.success(shells)))
            } catch (error: OpenCodeClientError) {
                send(Action.ShellsResponse(session.id, Result.failure(error)))
            }
        }.cancellable(CancelID.Shells, cancelInFlight = true),
    )
}

// region `SessionFeature.State` members

/** Home's answers for linked session IDs this screen asked about; others are ignored. */
fun State.applySessionLinks(targets: Map<String, SessionLinkTarget>): State {
    var links: MutableMap<String, SessionLinkTarget>? = null
    for ((id, target) in targets) {
        val existing = sessionLinks[id]
        if (existing != null && existing != target) {
            if (links == null) links = sessionLinks.toMutableMap()
            links[id] = target
        }
    }
    return if (links == null) this else copy(sessionLinks = links)
}

/** Drops IDs still waiting on a lookup that failed, so the next look asks again. */
fun State.forgetSessionLinks(ids: List<String>): State {
    val dropped = ids.filter { sessionLinks[it] == SessionLinkTarget.Resolving }
    return if (dropped.isEmpty()) this else copy(sessionLinks = sessionLinks - dropped.toSet())
}

// endregion

/** One background read at a time: asking while one is in flight queues a single read after it, instead of cancelling it and starting over. */
data class CoalescedRead(val isInFlight: Boolean = false, val isQueued: Boolean = false)
