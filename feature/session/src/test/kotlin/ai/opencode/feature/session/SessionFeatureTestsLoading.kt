package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.DictationClient
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.composer.PendingPrompt
import ai.opencode.feature.composer.PromptOutbox
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.composer.UndeliveredPrompts
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.Gate
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertFalse
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertNull
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.assertRemembersModelNames
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeModel
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeProject
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield
import org.junit.Test

class SessionFeatureTestsLoading {
    /** Pending requests and inbox reads wait on [hold]. */
    private fun DependencyValues.Builder.holdPendingWork(hold: Gate) {
        this[OpenCodeClient] = this[OpenCodeClient].copy(
            familyPendingRequests = { _, _, _ ->
                hold.await()
                OpenCodePendingRequests(permissions = emptyList())
            },
            pendingInputs = { _, _ ->
                hold.await()
                emptyList()
            },
        )
    }

    private val noModels = OpenCodeModelCatalog(models = emptyList(), defaultModel = null)
    private val model = OpenCodeModel(modelID = "m", providerID = "p", name = "Model")

    private fun user(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)
    private fun assistant(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text)

    /**
     * A running session opened from its cached window must read the live
     * transcript at once, not after the poll's first sleep.
     */
    @Test
    fun openingRunningSessionFromCacheReadsTranscriptImmediately() = sessionTest { h ->
        val session = testSession(id = "running", title = "Running")
        val transcriptReads = LockIsolated(0)
        val state = h.state {
            State.session(server = testServer, session = session, cachedItems = listOf(user("m1", "Cached")), isRunning = true)
        }.copy(isVisible = false)
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ ->
                    transcriptReads.increment()
                    listOf(user("m1", "Cached"), assistant("m2", "Live"))
                },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = setOf(root.id)) },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> noModels },
                getSession = { _, _ -> session },
            )
        }
        store.exhaustive = false

        store.send(Action.FirstContentFrameRendered)
        store.receive(Action.RefreshResponse::class) {
            it.copy(items = listOf(user("m1", "Cached"), assistant("m2", "Live")))
        }
        assertEquals(1, transcriptReads.value)
        h.skipInFlightEffects()
    }

    @Test
    fun firstPendingRequestReadWaitsForFamilyDiscovery() = sessionTest { h ->
        val root = testSession(id = "root", title = "Root")
        val child = testSession(id = "child", title = "Child", parentID = root.id)
        val family = Channel<OpenCodeSessionFamily>(Channel.UNLIMITED)
        val requestedSessionIDs = LockIsolated<List<List<String>>>(emptyList())
        val store = h.store(h.makeState(session = root, cachedItems = listOf(user("m1", "Ready")), hasRenderedFirstContentFrame = false)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sessionFamily = { _, _ ->
                    family.receiveCatching().getOrNull() ?: OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet())
                },
                familyPendingRequests = { _, ids, _ ->
                    requestedSessionIDs.append(ids)
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                getSession = { _, _ -> root },
                transcript = { _, _, _ -> listOf(user("m1", "Ready")) },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                pendingInputs = { _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> noModels },
            )
            this[DateClient] = DateClient.constant(date(100))
        }
        store.exhaustive = false

        store.send(Action.FirstContentFrameRendered)
        repeat(10) { yield() }
        assertTrue(requestedSessionIDs.value.isEmpty())
        family.trySend(OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = emptySet()))
        family.close()
        store.receive(Action.FamilyResponse::class)
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(listOf(listOf(root.id, child.id)), requestedSessionIDs.value)
        h.skipInFlightEffects()
    }

    @Test
    fun inboxReadDoesNotWaitForLocationScopedPendingRequests() = sessionTest { h ->
        val session = testSession(id = "root", title = "Root")
        val location = Gate()
        val inboxReads = LockIsolated(0)
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, _ ->
                    location.await()
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                pendingInputs = { _, _ ->
                    inboxReads.increment()
                    emptyList()
                },
            )
        }
        store.exhaustive = false

        store.send(Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(session), activeSessionIDs = emptySet()))))
        // This TestStore reduces effect actions when asked.
        for (i in 0 until 20) {
            if (inboxReads.value != 0) break
            store.skipReceivedActions()
        }
        assertEquals(1, inboxReads.value)
        location.open()
        location.finish()
        store.receive(Action.PendingRequestsResponse::class)
        h.skipInFlightEffects()
    }

    @Test
    fun refreshedVisibleSessionSnapshotReobservesItsIdleWatermark() = sessionTest { h ->
        val old = testSession(id = "root", title = "Root", updatedAt = distantPast, idleMilliseconds = 1, viewedMilliseconds = 1)
        val refreshed = testSession(id = old.id, title = old.title, idleMilliseconds = 2, viewedMilliseconds = 1)
        val items = listOf<OpenCodeTranscriptItem>(user("m1", "Ready"))
        val viewed = LockIsolated<List<Pair<String, Long>>>(emptyList())
        val state = h.makeState(session = old, cachedItems = items).copy(isVisible = true, hasRenderedFirstContentFrame = true)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> refreshed },
                transcript = { _, _, _ -> items },
                viewSession = { _, id, idle -> viewed.append(id to idle) },
            )
        }
        store.exhaustive = false

        store.send(Action.SessionSnapshotUpdated(refreshed)) {
            it.copy(session = refreshed, familySessions = listOf(refreshed))
        }
        store.receive(Action.RefreshResponse::class)
        store.receive(Action.AcceptedTranscriptUpdated::class)
        store.receive(Action.ViewAcknowledged(sessionID = refreshed.id, idle = 2))
        assertEquals(2L, store.state.viewedIdle[refreshed.id])
        assertTrue(viewed.value.all { it == refreshed.id to 2L })
    }

    @Test
    fun failedEarlierHistoryFetchRemainsRetryable() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val items = (0 until 30).map { user("message-$it", "Message $it") }
        val requests = LockIsolated(0)
        val error = OpenCodeClientError(message = "The server returned HTTP 504.", statusCode = 504)
        val store = h.store(h.makeState(session = session, cachedItems = items)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                historyPage = { _, _, _ ->
                    requests.increment()
                    throw error
                },
            )
        }
        store.exhaustive = false
        store.send(Action.ShowEarlierTapped)
        store.receive(Action.EarlierHistoryResponse(session.id, 0, null, Result.failure(error)))
        assertEquals(State.EarlierHistory.SeekingOverlap(cursor = null), store.state.earlierHistory)
        assertTrue(store.state.hasEarlierItems)
        assertEquals(error.message, store.state.earlierHistoryError)
        assertFalse(store.state.isLoadingEarlier)
        store.send(Action.ShowEarlierTapped)
        store.receive(Action.EarlierHistoryResponse(session.id, 0, null, Result.failure(error)))
        store.finish()
        assertEquals(2, requests.value)
    }

    @Test
    fun restoredCacheWaitsForFreshOverlapBeforeStartingCursorHistory() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val stale = (0 until 30).map { user("stale-$it", "Stale $it") }
        val fresh = (0 until 30).map { user("fresh-$it", "Fresh $it") }
        val state = h.makeState(session = session, cachedItems = stale, hasRenderedFirstContentFrame = true).copy(
            visibleLimit = stale.size,
            isVisible = true,
            refresh = State.Refresh(id = 1, reason = RefreshReason.Open),
        )
        val store = h.store(state)
        store.exhaustive = false

        assertFalse(store.state.hasEarlierItems)
        store.send(Action.ShowEarlierTapped)
        assertFalse(store.state.isLoadingEarlier)

        store.send(
            Action.RefreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(observed = session, items = fresh, scope = page)),
            ),
        )
        assertTrue(store.state.initialTranscriptIsFresh)
        assertTrue(store.state.hasEarlierItems)
    }

    @Test
    fun unreachableEarlierHistoryStaysQuietAndPagesAgainOnReconnect() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val items = (0 until 30).map { user("message-$it", "Message $it") }
        val older = user("message-older", "Older")
        val unreachable = OpenCodeClientError(message = "Could not reach the OpenCode server.")
        val reachable = LockIsolated(false)
        val store = h.store(h.makeState(session = session, cachedItems = items)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                historyPage = { _, _, _ ->
                    if (!reachable.value) throw unreachable
                    OpenCodeTranscriptPage(items = listOf(older) + items, next = null)
                },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        // The automatic page fails because the server is down: no error banner,
        // just a note to try again when it is back.
        store.send(Action.ShowEarlierTapped)
        store.receive(Action.EarlierHistoryResponse(session.id, 0, null, Result.failure(unreachable)))
        assertNull(store.state.earlierHistoryError)
        assertTrue(store.state.earlierHistoryRetriesOnReconnect)
        assertTrue(store.state.hasEarlierItems)

        reachable.setValue(true)
        store.send(Action.RequestsConnectionChanged(false))
        store.send(Action.RequestsConnectionChanged(true))
        store.receive(Action.ShowEarlierTapped)
        store.receive(Action.EarlierHistoryResponse::class)
        assertFalse(store.state.earlierHistoryRetriesOnReconnect)
        assertEquals(State.EarlierHistory.Exhausted, store.state.earlierHistory)
        assertEquals(older, store.state.items.first())
        h.skipInFlightEffects()
    }

    @Test
    fun failedInitialLoadHealsWhenTheConnectionReturns() = sessionTest { h ->
        val session = testSession(id = "ses_cold", title = "Cold", updatedAt = distantPast)
        val unreachable = OpenCodeClientError(message = "Could not reach the OpenCode server.")
        val item = user("message-1", "Hello")
        val reachable = LockIsolated(false)
        val state = h.makeState(session = session, initialTranscriptRequestIsExternal = true, hasRenderedFirstContentFrame = false)
        val store = h.store(state) {
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    if (!reachable.value) throw unreachable
                    listOf(item)
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sessionFamily = { _, _ -> throw unreachable },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> noModels },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(unreachable)))
        assertEquals(unreachable.message, store.state.errorMessage)

        reachable.setValue(true)
        store.send(Action.RequestsConnectionChanged(false))
        store.send(Action.RequestsConnectionChanged(true))
        assertNull(store.state.errorMessage)
        assertTrue(store.state.isLoading)
        store.receive(Action.RefreshResponse::class)
        assertEquals(listOf<OpenCodeTranscriptItem>(item), store.state.items)
        assertFalse(store.state.isLoading)
        assertNull(store.state.errorMessage)
        h.skipInFlightEffects()
    }

    @Test
    fun earlierHistoryAllowsOnlyOneInFlightPage() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val items = (0 until 30).map { user("message-$it", "Message $it") }
        val requests = LockIsolated(0)
        val gate = Gate()
        val store = h.store(h.makeState(session = session, cachedItems = items)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                historyPage = { _, _, _ ->
                    requests.increment()
                    gate.await()
                    OpenCodeTranscriptPage(items = items, next = null)
                },
            )
        }
        store.exhaustive = false

        store.send(Action.ShowEarlierTapped)
        store.send(Action.ShowEarlierTapped)
        settle()
        assertEquals(1, requests.value)
        gate.open()
        gate.finish()
        store.receive(Action.EarlierHistoryResponse::class)
        store.finish()
    }

    @Test
    fun draftSessionHasNoEarlierHistory() = sessionTest { h ->
        val project = OpenCodeProject(id = "p", name = "Project", directory = "/project")
        val state = h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }
        assertTrue(state.isDraft)
        assertEquals(State.EarlierHistory.Exhausted, state.earlierHistory)
        assertFalse(state.hasEarlierItems)
        assertFalse(state.isTranscriptLoading)
        assertFalse(state.showsEmptyState)
    }

    @Test
    fun emptyStateWaitsForFreshSnapshotAndHistoryLoad() = sessionTest { h ->
        val session = testSession(id = "ses_empty", title = "Empty", updatedAt = distantPast)
        val refreshGate = Gate()
        val historyGate = Gate()
        val hold = Gate()
        val now = date(100)
        val initial = h.makeState(session = session, hasRenderedFirstContentFrame = false).copy(models = listOf(model))
        val store = h.store(initial) {
            this[DateClient] = DateClient.constant(now)
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    refreshGate.await()
                    emptyList()
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                runningShells = { _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                historyPage = { _, _, _ ->
                    historyGate.await()
                    OpenCodeTranscriptPage(items = emptyList(), next = null)
                },
            )
            holdPendingWork(hold)
        }

        // Before any REST snapshot succeeds, an empty cached array is still loading.
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        store.send(Action.Task)
        store.receive(Action.RefreshRequested(RefreshReason.Open)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Open))
        }
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // The top history cell starts paging while the transcript is still empty.
        store.send(Action.ShowEarlierTapped) {
            it.copy(visibleLimit = 60, earlierHistory = State.EarlierHistory.Loading(cursor = null, reachedLoadedItems = false))
        }
        assertTrue(store.state.isLoadingEarlier)
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // Even after the page refresh returns [], the empty state waits while the
        // earlier-history spinner is still in flight.
        refreshGate.open()
        refreshGate.finish()
        store.receive(
            Action.RefreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(
                    SessionFeature.AuthoritativeRefresh(
                        observed = session, items = emptyList(), scope = page, context = OpenCodeContextReading.Empty, observedIsRead = true,
                    ),
                ),
            ),
        ) {
            it.copy(refresh = null, initialTranscriptIsFresh = true)
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = emptyList()))
        store.receive(Action.FirstContentFrameRendered) {
            it.copy(hasRenderedFirstContentFrame = true, familyRead = CoalescedRead(isInFlight = true))
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        // Merged effects answer in launch order (family, shells, models).
        store.receive(
            Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(session), activeSessionIDs = emptySet()))),
        ) {
            it.copy(familyReadAt = now, familyRead = CoalescedRead(), pendingWorkPollSeconds = 2)
        }
        store.receive(Action.ShellsResponse(sessionID = session.id, result = Result.success(emptyList())))
        store.receive(Action.JobsResponse(sessionID = session.id, jobs = emptyList()))
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        assertTrue(store.state.isLoadingEarlier)
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // Once the history page also confirms zero messages, the empty state shows.
        historyGate.open()
        historyGate.finish()
        store.receive(
            Action.EarlierHistoryResponse(session.id, 0, null, Result.success(OpenCodeTranscriptPage(items = emptyList(), next = null))),
        ) {
            it.copy(earlierHistory = State.EarlierHistory.Exhausted)
        }
        // An empty page changes nothing Home keeps: not published.
        assertFalse(store.state.isLoadingEarlier)
        assertFalse(store.state.hasEarlierItems)
        assertFalse(store.state.isTranscriptLoading)
        assertTrue(store.state.showsEmptyState)
        h.skipInFlightEffects()
    }

    @Test
    fun slowLoadAndFailedLoadNeverShowEmptyState() = sessionTest { h ->
        val session = testSession(id = "ses_kit_motion", title = "KIT MOTION", updatedAt = distantPast)
        val timeoutError = OpenCodeClientError(message = "Couldn’t load the session transcript.")
        val loadedItems = listOf<OpenCodeTranscriptItem>(
            user("msg_1", "Audit the spring curves across the drawer and transcript"),
            assistant("msg_2-text-0", "Checked `BottomSpring` and `DrawerGeometry`."),
        )
        val shouldFail = LockIsolated(true)
        val refreshGate = Gate()

        val initial = h.makeState(session = session, initialTranscriptRequestIsExternal = true).copy(models = listOf(model))
        val store = h.store(initial) {
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    refreshGate.await()
                    if (shouldFail.value) throw timeoutError
                    loadedItems
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
            )
        }

        // 1. Slow initial load in flight: only loading treatment, never empty state.
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // 2. Initial external load fails: error state is set, never empty state.
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(timeoutError))) {
            it.copy(initialTranscriptRequestIsExternal = false, isLoading = false).withErrorMessage(timeoutError.message)
        }
        store.receive(Action.FirstContentFrameRendered)
        assertEquals(timeoutError.message, store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // 3. Retry (.task) starts a slow refresh: loading treatment returns, never empty state.
        store.send(Action.Task) { it.copy(isLoading = true).withErrorMessage(null) }
        store.receive(Action.RefreshRequested(RefreshReason.Open)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Open))
        }
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // 4. Slow refresh fails: error state returns, never empty state.
        refreshGate.open()
        store.receive(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = Result.failure(timeoutError))) {
            it.copy(refresh = null, isLoading = false).withErrorMessage(timeoutError.message)
        }
        assertEquals(timeoutError.message, store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        // 5. Retry succeeds after a slow load: messages appear, empty state never showed.
        shouldFail.setValue(false)
        store.send(Action.Task) { it.copy(isLoading = true).withErrorMessage(null) }
        store.receive(Action.RefreshRequested(RefreshReason.Open)) {
            it.copy(nextRefreshID = 2, refresh = State.Refresh(id = 2, reason = RefreshReason.Open))
        }
        assertTrue(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        refreshGate.open()
        refreshGate.finish()
        store.receive(
            Action.RefreshResponse(
                sessionID = session.id,
                requestID = 2,
                result = Result.success(
                    SessionFeature.AuthoritativeRefresh(
                        observed = session, items = loadedItems, scope = page, context = OpenCodeContextReading.Empty, observedIsRead = true,
                    ),
                ),
            ),
        ) {
            it.copy(refresh = null, initialTranscriptIsFresh = true, isLoading = false, items = loadedItems)
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = loadedItems))
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)
        store.finish()
    }

    @Test
    fun draftBecomingSessionMarksTranscriptFreshAndShowsEmptyStateWhenCleared() = sessionTest { h ->
        val project = OpenCodeProject(id = "p", name = "Project", directory = "/project")
        val created = testSession(id = "ses_from_draft", title = "New session", updatedAt = distantPast)
        val hold = Gate()

        val initial = h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }
            .copy(models = listOf(model))
        val store = h.store(initial) { holdPendingWork(hold) }

        assertTrue(store.state.isDraft)
        assertFalse(store.state.initialTranscriptIsFresh)
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        store.send(Action.SessionCreated(created)) {
            it.copy(
                draftBecameSessionID = created.id,
                initialTranscriptIsFresh = true,
                session = created,
                familySessions = listOf(created),
                familyRootID = created.id,
                pendingWorkPollSeconds = 2,
            ).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        store.receive(Action.PendingInputsRequested(sessionID = created.id))
        assertFalse(store.state.isDraft)
        assertTrue(store.state.initialTranscriptIsFresh)
        assertFalse(store.state.isTranscriptLoading)
        assertTrue(store.state.showsEmptyState)

        h.skipInFlightEffects()
    }

    @Test
    fun failedRefreshPreservesEmptyOrActiveSessionAndSendOrContentClearsError() = sessionTest { h ->
        val session = testSession(id = "ses_guard", title = "Guard", updatedAt = distantPast)
        val loadError = OpenCodeClientError(message = "Couldn’t load the session transcript.")
        val gate = Gate()

        val initial = h.makeState(session = session, initialTranscriptRequestIsExternal = true).copy(
            models = listOf(model),
            initialTranscriptIsFresh = true,
            isLoading = false,
            earlierHistory = State.EarlierHistory.Exhausted,
        )

        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[PromptOutbox] = PromptOutbox()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    gate.await()
                    throw loadError
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sendPrompt = { _, _, _, _, _, _, _ -> gate.await() },
            )
        }

        // 1. A session already showing its empty state keeps it when a later refresh fails.
        assertTrue(store.state.showsEmptyState)
        store.send(Action.RefreshRequested(RefreshReason.Invalidation)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Invalidation))
        }
        assertFalse(store.state.isTranscriptLoading)
        assertTrue(store.state.showsEmptyState)

        gate.open()
        store.receive(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = Result.failure(loadError))) {
            it.copy(refresh = null)
        }
        assertNull(store.state.errorMessage)
        assertTrue(store.state.showsEmptyState)

        // 2. A session with a queued/steering input or Working never gets replaced by "Couldn't Load Session".
        val queuedState = initial.copy(
            initialTranscriptIsFresh = false,
            initialTranscriptRequestIsExternal = true,
            isLoading = true,
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_q", text = "Queued prompt", delivery = OpenCodeDelivery.Queue)),
        )
        store.send(Action.PreviewStateReplaced(queuedState)) { queuedState }
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(loadError))) {
            it.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = loadError.message)
        }
        store.receive(Action.FirstContentFrameRendered)
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        val runningState = initial.copy(
            initialTranscriptIsFresh = false,
            initialTranscriptRequestIsExternal = false,
            isExternallyActive = true,
        )
        store.send(Action.PreviewStateReplaced(runningState)) {
            runningState.copy(workingRun = WorkingRun(counter = 1, startID = 1, key = session.id, sawStart = true))
        }
        store.send(Action.RefreshRequested(RefreshReason.RunState)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.RunState))
        }
        gate.open()
        store.receive(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = Result.failure(loadError))) {
            it.copy(refresh = null, loadError = loadError.message)
        }
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        // 3. When idle and uncached, a failed read sets errorMessage, and arriving live content clears it.
        val coldFailed = initial.copy(initialTranscriptIsFresh = false, initialTranscriptRequestIsExternal = false)
            .withErrorMessage(loadError.message)
        store.send(Action.PreviewStateReplaced(coldFailed)) { coldFailed }
        assertEquals(loadError.message, store.state.errorMessage)
        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_live", ordinal = 0, delta = "Streaming reply")),
            ),
        ) {
            it.copy(
                isExternallyActive = true,
                workingRun = WorkingRun(counter = 1, startID = 1, key = session.id, sawStart = true, baseline = "msg_live-text-0"),
                streamIsHealthy = true,
                streamSupportsProjection = true,
                projectedMessageIDs = setOf("msg_live"),
                lastProjectedMessageID = "msg_live",
                liveText = mapOf("msg_live-text-0" to "Streaming reply"),
                items = listOf(assistant("msg_live-text-0", "Streaming reply").copy(inProgress = true)),
            )
        }
        assertNull(store.state.errorMessage)

        // 4. Reset to an idle cold-load error and verify sending from the composer clears errorMessage.
        val sendFailed = initial.copy(initialTranscriptIsFresh = false, initialTranscriptRequestIsExternal = false)
            .withErrorMessage(loadError.message)
            .withDraft("Start anyway")
        store.send(Action.PreviewStateReplaced(sendFailed)) { sendFailed }
        assertEquals(loadError.message, store.state.errorMessage)
        store.send(Action.SendTapped) { optimisticSend(it, session.id, "Start anyway") }
        assertNull(store.state.errorMessage)

        h.skipInFlightEffects()
    }

    @Test
    fun draftCreatedSessionHiddenAndShownAgainKeepsEmptyState() = sessionTest { h ->
        val project = OpenCodeProject(id = "p", name = "Project", directory = "/project")
        val created = testSession(id = "ses_from_draft", title = "New session", updatedAt = distantPast)
        val hold = Gate()

        val initial = h.state { State.draft(server = testServer, draftProject = project, projects = listOf(project)) }
            .copy(models = listOf(model), isVisible = true)
        val store = h.store(initial) { holdPendingWork(hold) }

        store.send(Action.SessionCreated(created)) {
            it.copy(
                draftBecameSessionID = created.id,
                initialTranscriptIsFresh = true,
                session = created,
                familySessions = listOf(created),
                familyRootID = created.id,
                pendingWorkPollSeconds = 2,
            ).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        store.receive(Action.PendingInputsRequested(sessionID = created.id))
        assertTrue(store.state.showsEmptyState)
        assertFalse(store.state.isTranscriptLoading)

        store.send(Action.VisibilityChanged(false)) { it.copy(isVisible = false) }
        assertTrue(store.state.initialTranscriptIsFresh)
        assertFalse(store.state.isTranscriptLoading)
        assertTrue(store.state.showsEmptyState)

        store.send(Action.VisibilityChanged(true)) { it.copy(isVisible = true) }
        assertTrue(store.state.initialTranscriptIsFresh)
        assertFalse(store.state.isTranscriptLoading)
        assertTrue(store.state.showsEmptyState)
        assertNull(store.state.errorMessage)

        h.skipInFlightEffects()
    }

    @Test
    fun failedSendDeletedAfterClearingLoadErrorRestoresErrorWithoutSpinner() = sessionTest { h ->
        val session = testSession(id = "ses_send_fail", title = "Send Fail", updatedAt = distantPast)
        val loadError = OpenCodeClientError(message = "Couldn’t load the session transcript.")
        val sendError = OpenCodeClientError(message = "Couldn’t send prompt.")
        val messageID = "msg_00000000000100000000000000"

        val initial = h.makeState(session = session, initialTranscriptRequestIsExternal = true, draft = "Start anyway")
            .copy(models = listOf(model))

        val store = h.store(initial) {
            this[UuidClient] = UuidClient.incrementing()
            this[Clock] = ImmediateClock()
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[PromptOutbox] = PromptOutbox()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sendPrompt = { _, _, _, _, _, _, _ -> throw sendError },
                message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }

        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(loadError))) {
            it.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = loadError.message)
        }
        store.receive(Action.FirstContentFrameRendered)
        assertEquals(loadError.message, store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        store.send(Action.SendTapped) { optimisticSend(it, session.id, "Start anyway") }
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        val pending = PendingPrompt(
            messageID = messageID,
            text = "Start anyway",
            attachments = emptyList(),
            files = emptyList(),
            wasRunning = false,
            delivery = OpenCodeDelivery.Steer,
            requestEpoch = 1,
            model = null,
        )
        store.receive(Action.PromptFailed(sessionID = session.id, error = sendError, pending = pending)) {
            it.copy(isAwaitingReply = false, promptStates = emptyMap(), failedSends = listOf(FailedSend(pending = pending, reason = sendError.message)))
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = emptyList()))
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        store.send(Action.FailedSendDeleteTapped(messageID)) {
            it.copy(failedSends = emptyList(), items = emptyList(), optimisticPromptIDs = emptySet())
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = emptyList()))
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)
        assertEquals(loadError.message, store.state.errorMessage)
    }

    @Test
    fun cancellingQueuedInputAfterFailedLoadShowsErrorWithoutSpinner() = sessionTest { h ->
        val session = testSession(id = "ses_queued_fail", title = "Queued Fail", updatedAt = distantPast)
        val loadError = OpenCodeClientError(message = "Couldn’t load the session transcript.")

        val initial = h.makeState(session = session, initialTranscriptRequestIsExternal = true).copy(
            models = listOf(model),
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_q", text = "Queued prompt", delivery = OpenCodeDelivery.Queue)),
        )

        val store = h.store(initial)

        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(loadError))) {
            it.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = loadError.message)
        }
        store.receive(Action.FirstContentFrameRendered)
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)

        store.send(Action.InputCancelled("msg_q")) {
            it.copy(queuedInputs = emptyList(), cancelledInputIDs = setOf("msg_q"))
        }
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)
        assertEquals(loadError.message, store.state.errorMessage)
    }

    @Test
    fun failedInitialLoadWhileRunningOrQueuedFiresFirstContentFrameRendered() = sessionTest { h ->
        val session = testSession(id = "ses_cold_poll", title = "Cold Poll", updatedAt = distantPast)
        val loadError = OpenCodeClientError(message = "Couldn’t load the session transcript.")
        val now = date(100)
        val hold = Gate()
        val catalog = OpenCodeModelCatalog(models = listOf(model), defaultModel = null)

        val initial = h.makeState(
            session = session, initialTranscriptRequestIsExternal = true, isRunning = true, hasRenderedFirstContentFrame = false,
        ).copy(queuedInputs = listOf(OpenCodePendingInput(id = "msg_q", text = "Queued prompt", delivery = OpenCodeDelivery.Queue)))

        val store = h.store(initial) {
            this[DateClient] = DateClient.constant(now)
            this[DictationClient] = DictationClient(prepare = {})
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ -> catalog },
                runningShells = { _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = setOf(root.id)) },
            )
            holdPendingWork(hold)
        }

        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(loadError))) {
            it.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = loadError.message)
        }
        store.receive(Action.FirstContentFrameRendered) {
            it.copy(hasRenderedFirstContentFrame = true, isLoadingModels = true, familyRead = CoalescedRead(isInFlight = true))
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        // Merged effects answer in launch order (family, shells, models).
        store.receive(
            Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(session), activeSessionIDs = setOf(session.id)))),
        ) {
            it.copy(familyReadAt = now, familyRead = CoalescedRead(), pendingWorkPollSeconds = 2)
        }
        store.receive(Action.ShellsResponse(sessionID = session.id, result = Result.success(emptyList())))
        store.receive(Action.JobsResponse(sessionID = session.id, jobs = emptyList()))
        store.receive(Action.ModelsResponse(Result.success(catalog))) {
            it.copy(isLoadingModels = false, models = listOf(model))
        }
        store.state.assertRemembersModelNames(listOf(model))
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        h.skipInFlightEffects()
    }

    @Test
    fun failedSendDeletedDuringInitialLoadSettlesWithoutPermanentSpinner() = sessionTest { h ->
        val session = testSession(id = "ses_restored_fail", title = "Restored Fail", updatedAt = distantPast)
        val loadError = OpenCodeClientError(message = "Couldn’t load the session transcript.")
        val messageID = "msg_failed_restored"
        val now = date(100)
        val refreshGate = Gate()
        val hold = Gate()
        val pending = PendingPrompt(
            messageID = messageID,
            text = "Restored failed prompt",
            attachments = emptyList(),
            files = emptyList(),
            wasRunning = false,
            delivery = OpenCodeDelivery.Steer,
            requestEpoch = 0,
            model = null,
        )

        val initial = h.makeState(session = session, hasRenderedFirstContentFrame = false).copy(
            models = listOf(model),
            failedSends = listOf(FailedSend(pending = pending, reason = "Couldn’t send prompt.")),
            optimisticPromptIDs = setOf(messageID),
            items = listOf(user(messageID, pending.text)),
        )

        val store = h.store(initial) {
            this[DateClient] = DateClient.constant(now)
            this[Clock] = TestClock()
            this[DictationClient] = DictationClient(prepare = {})
            this[UndeliveredPrompts] = UndeliveredPrompts()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    refreshGate.await()
                    throw loadError
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                runningShells = { _, _ -> emptyList() },
                sessionFamily = { _, root -> OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
            )
            holdPendingWork(hold)
        }

        // 1. Visibility starts `.open` because `cacheableItems` is empty even with a restored failed send.
        store.send(Action.VisibilityChanged(true)) { it.copy(isVisible = true) }
        store.receive(Action.RefreshRequested(RefreshReason.Open)) {
            it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Open), resync = State.ResyncState(refreshID = 1))
        }

        // 2. Deleting the failed send while the initial refresh is in flight shows loading until it settles,
        // then `.refreshResponse` fires `.firstContentFrameRendered` and surfaces the load error.
        store.send(Action.FailedSendDeleteTapped(messageID)) {
            it.copy(failedSends = emptyList(), items = emptyList(), optimisticPromptIDs = emptySet())
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = emptyList()))
        assertTrue(store.state.isTranscriptLoading)

        refreshGate.open()
        refreshGate.finish()
        store.receive(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = Result.failure(loadError))) {
            it.copy(refresh = null, resync = null, loadError = loadError.message)
        }
        store.receive(Action.FirstContentFrameRendered) {
            it.copy(hasRenderedFirstContentFrame = true, familyRead = CoalescedRead(isInFlight = true))
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        }
        // Merged effects answer in launch order (family, shells, models).
        store.receive(
            Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(session), activeSessionIDs = emptySet()))),
        ) {
            it.copy(familyReadAt = now, familyRead = CoalescedRead(), pendingWorkPollSeconds = 2)
        }
        store.receive(Action.ShellsResponse(sessionID = session.id, result = Result.success(emptyList())))
        store.receive(Action.JobsResponse(sessionID = session.id, jobs = emptyList()))
        store.receive(Action.PendingInputsRequested(sessionID = session.id))
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)
        assertEquals(loadError.message, store.state.errorMessage)

        // 3. When an external initial transcript fails while a restored failed send is still on screen,
        // `loadError` is recorded so deleting the failed send later reveals the error instead of a spinner.
        val externalState = initial.copy(hasRenderedFirstContentFrame = true, initialTranscriptRequestIsExternal = true, isLoading = true)
        store.send(Action.PreviewStateReplaced(externalState)) { externalState }
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.failure(loadError))) {
            it.copy(initialTranscriptRequestIsExternal = false, isLoading = false, loadError = loadError.message)
        }
        assertNull(store.state.errorMessage)
        assertFalse(store.state.isTranscriptLoading)

        store.send(Action.FailedSendDeleteTapped(messageID)) {
            it.copy(failedSends = emptyList(), items = emptyList(), optimisticPromptIDs = emptySet())
        }
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = emptyList()))
        assertFalse(store.state.isTranscriptLoading)
        assertFalse(store.state.showsEmptyState)
        assertEquals(loadError.message, store.state.errorMessage)

        h.skipInFlightEffects()
    }

    @Test
    fun earlierHistoryPagesBeyondTwoHundredMessages() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val items = (0 until 240).map { user("message-$it", "Message $it") }
        val requests = LockIsolated<List<String?>>(emptyList())
        val store = h.store(h.makeState(session = session, cachedItems = items.takeLast(30))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                historyPage = { _, _, cursor ->
                    requests.append(cursor)
                    val end = cursor?.toIntOrNull() ?: items.size
                    val start = maxOf(0, end - 40)
                    OpenCodeTranscriptPage(items = items.subList(start, end), next = if (start > 0) start.toString() else null)
                },
            )
        }
        store.exhaustive = false
        for ((index, end) in (240 downTo 40 step 40).withIndex()) {
            val start = end - 40
            val cursor = if (end == 240) null else end.toString()
            val page = OpenCodeTranscriptPage(items = items.subList(start, end), next = if (start > 0) start.toString() else null)
            when (index) {
                0 -> store.send(Action.ShowEarlierTapped)
                1 -> store.receive(Action.FillEarlierHistory)
                else -> store.send(Action.ShowEarlierTapped)
            }
            store.receive(Action.EarlierHistoryResponse(session.id, 0, cursor, Result.success(page)))
        }
        store.send(Action.ShowEarlierTapped)
        assertEquals(items, store.state.items)
        assertEquals(State.EarlierHistory.Exhausted, store.state.earlierHistory)
        assertFalse(store.state.hasEarlierItems)
        assertEquals(listOf(null, "200", "160", "120", "80", "40"), requests.value)
        store.finish()
    }

    @Test
    fun latestSnapshotDoesNotFinishEarlierHistoryRequest() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val item = user("message", "Latest")
        val state = h.makeState(session = session, cachedItems = listOf(item))
            .copy(earlierHistory = State.EarlierHistory.Loading(cursor = null, reachedLoadedItems = false))
        val store = h.store(state)
        store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(item))))
        store.receive(Action.AcceptedTranscriptUpdated::class)
        assertTrue(store.state.isLoadingEarlier)
        // A page of rows already loaded changes nothing Home keeps: not published.
        store.send(
            Action.EarlierHistoryResponse(session.id, 0, null, Result.success(OpenCodeTranscriptPage(items = listOf(item), next = null))),
        ) {
            it.copy(earlierHistory = State.EarlierHistory.Exhausted, earlierHistoryInsertionID = item.id)
        }
        store.finish()
    }

    private val page = SessionFeature.AuthoritativeRefresh.Scope.Page

    private fun optimisticSend(state: State, sessionID: String, text: String): State {
        val id = "msg_00000000000100000000000000"
        return state.withDraftState(DraftState()).copy(
            runWorkGeneration = 1,
            isAwaitingReply = true,
            nextPromptRequestEpoch = 1,
            promptStates = mapOf(id to State.PromptState(requestEpoch = 1)),
            optimisticPromptIDs = setOf(id),
            items = listOf(user(id, text)),
            lastSentItemID = id,
            sendGeneration = 1,
            workingRun = state.workingRun.copy(counter = 1, startID = 1, sawStart = true, startTurn = id, key = sessionID),
        )
    }
}
