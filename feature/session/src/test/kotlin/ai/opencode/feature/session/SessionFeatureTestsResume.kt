package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.Gate
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.flush
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertFalse
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.distantPast
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeTranscriptPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.yield
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionFeatureTestsResume {
    private fun user(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)
    private fun assistant(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text)
    private val page = SessionFeature.AuthoritativeRefresh.Scope.Page
    private val offline = OpenCodeClientError(message = "Offline")

    private fun isRefreshResponse(action: Action) = action is Action.RefreshResponse
    private fun isAccepted(action: Action) = action is Action.AcceptedTranscriptUpdated

    @Test
    fun cachedReplySurvivesAnOmittingOpenReadBeforeItsFirstDelta() = sessionTest { h ->
        val session = testSession(id = "ses_cached", title = "Cached reply")
        val user = user("prompt", "Go")
        val stale = assistant("reply-text-0", "")
        val previous = h.store(h.makeState(session = session, cachedItems = listOf(user, stale)))
        previous.exhaustive = false
        previous.send(Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextDelta("reply", 1, "First words"))))

        // Home stores the cacheable rows; the new visit has neither the old raw text nor its
        // projection IDs.
        val serializer = ListSerializer(OpenCodeTranscriptItem.serializer())
        val cached = Json.decodeFromString(serializer, Json.encodeToString(serializer, previous.state.cacheableItems))
        val partial = cached.last()
        assertTrue(partial.isInProgressText)
        val store = h.store(h.makeState(session = session, cachedItems = cached, isRunning = true))
        store.exhaustive = false
        store.send(Action.TranscriptResponse(session.id, Result.success(listOf(user))))
        assertEquals(listOf(user, partial), store.state.items)
        store.send(Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextDelta("reply", 1, " tail after gap"))))
        assertEquals(listOf(user, partial), store.state.items)
        assertTrue(store.state.liveText.isEmpty())
        val final = assistant(partial.id, "First words, middle, tail after gap")
        store.send(Action.TranscriptResponse(session.id, Result.success(listOf(user, final))))
        assertEquals(listOf(user, final), store.state.items)
    }

    @Test
    fun streamGapRejectsPreGapReadAndReconnectRefetchesWithoutDuplicatingText() = sessionTest { h ->
        val session = testSession(id = "ses_gap", title = "Gap")
        val partial = assistant("msg_reply-text-0", "Hello")
        val complete = assistant("msg_reply-text-0", "Hello world")
        val state = h.makeState(session = session, cachedItems = listOf(partial)).copy(
            streamIsHealthy = true,
            isExternallyActive = true,
            liveText = mapOf("msg_reply-text-0" to "Hello"),
            projectedMessageIDs = setOf("msg_reply"),
            nextRefreshID = 1,
            refresh = State.Refresh(
                id = 1,
                reason = RefreshReason.Watchdog,
                racedEvents = listOf(OpenCodeLiveContentEvent.TextStarted(messageID = "msg_reply", ordinal = 0)),
            ),
        )
        val reads = LockIsolated(0)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    reads.increment()
                    listOf(complete)
                },
                runningShells = { _, _ -> emptyList() },
                runningJobs = { _, _ -> emptyList() },
            )
        }
        store.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false)
                .copy(streamIsHealthy = false, liveText = emptyMap(), refresh = null)
                .withRequestFailureAwaitingReconnect(true)
        }
        store.send(
            Action.RefreshResponse(
                sessionID = session.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(observed = session, items = listOf(partial), scope = page)),
            ),
        )
        // Reconnect must not join the old watchdog read. Keep the full refresh
        // machinery; incidental family/cache publications are covered elsewhere.
        store.exhaustive = false
        store.send(Action.EventStreamConnected(testServer))
        store.receive(Action.RefreshRequested(RefreshReason.Reconnect))
        store.receive(::isRefreshResponse, "refreshResponse")
        store.finish()
        assertEquals(1, reads.value)
        assertEquals(listOf<OpenCodeTranscriptItem>(complete), store.state.items)
        assertTrue(store.state.liveText.isEmpty())
    }

    /**
     * A reconnect mid-reply loses the deltas sent during the gap (`/api/event` has no resume). The
     * row keeps what it showed rather than restarting from the first post-gap delta, and the part's
     * end brings the whole text once.
     */
    @Test
    fun reconnectMidReplyKeepsTheRowUntilItsTextEnds() = sessionTest { h ->
        val session = testSession(id = "ses_gap", title = "Gap")
        val id = "msg_reply-text-0"
        val shown = assistant(id, "The first half of a long")
        val whole = assistant(id, "The first half of a long reply, and its end.")
        val ended = OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "The first half of a long reply, and its end.")
        val state = h.makeState(session = session, cachedItems = listOf(shown)).copy(
            streamIsHealthy = true,
            streamSupportsProjection = true,
            isExternallyActive = true,
            liveText = mapOf(id to "The first half of a long"),
            projectedMessageIDs = setOf("msg_reply"),
            lastProjectedMessageID = "msg_reply",
            // The reconnect's REST read is in flight (REST omits the unfinished part).
            nextRefreshID = 1,
            refresh = State.Refresh(id = 1, reason = RefreshReason.Reconnect),
        )
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(runningShells = { _, _ -> emptyList() }, runningJobs = { _, _ -> emptyList() })
        }

        store.send(Action.EventStreamConnected(testServer)) {
            it.copy(streamSupportsProjection = false, liveText = emptyMap(), lastProjectedMessageID = null)
        }
        // A new stream relists shells and jobs: an exit may have been missed.
        store.receive(Action.ShellsResponse(session.id, Result.success(emptyList())))
        store.receive(Action.JobsResponse(session.id, emptyList()))
        // " reply, and" was sent during the gap; the first delta after it must neither replace the
        // row nor append past the hole.
        store.send(
            Action.ContentBatch(session.id, listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = " its"))),
        ) {
            it.copy(streamSupportsProjection = true, lastProjectedMessageID = "msg_reply")
        }
        store.send(
            Action.ContentBatch(
                session.id,
                listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = " end."), ended),
            ),
        ) {
            it.copy(items = listOf(whole), refresh = it.refresh?.copy(racedEvents = listOf(ended)))
        }
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply"))) {
            it.copy(refresh = it.refresh?.copy(followup = RefreshReason.Settlement(messageID = "msg_reply")))
        }
        assertEquals(listOf<OpenCodeTranscriptItem>(whole), store.state.items.filter { it.id == id })
        store.finish()
    }

    @Test
    fun mergeDropsHeldRowsThatTheWindowAlsoContains() {
        // A restored snapshot can hold a row *before* the window start that the
        // fresh window also contains (history rewritten under the same session).
        // The window wins; the transcript must never carry the id twice.
        val stale = assistant("reply", "old")
        val existing = listOf(user("u0", "old"), stale, user("w1", "w1"), user("w2", "w2"))
        val fetched = listOf(user("w1", "w1"), user("w2", "w2"), user("u9", "new"), assistant("reply", "new"))
        val merged = SessionFeature.mergeTranscript(fetched = fetched, existing = existing)
        assertEquals(listOf("u0", "w1", "w2", "u9", "reply"), merged.map { it.id })
        assertEquals(assistant("reply", "new"), merged.last())
    }

    @Test
    fun replayedOlderPageNeverDuplicatesTranscriptIDs() = sessionTest { h ->
        val session = testSession(id = "ses_history", title = "History", updatedAt = distantPast)
        val older = user("m1", "Older")
        val latest = user("m2", "Latest")
        val state = h.makeState(session = session, cachedItems = listOf(older, latest))
            .copy(earlierHistory = State.EarlierHistory.Loading(cursor = "c1", reachedLoadedItems = true))
        val store = h.store(state)
        // A page whose rows already arrived (overlapping paging, replayed cursor)
        // must not prepend them a second time: the list keys its snapshot on ids.
        store.send(
            Action.EarlierHistoryResponse(session.id, 0, "c1", Result.success(OpenCodeTranscriptPage(items = listOf(older), next = null))),
        ) {
            it.copy(earlierHistory = State.EarlierHistory.Exhausted, earlierHistoryInsertionID = older.id)
        }
        assertEquals(store.state.items.size, store.state.items.map { it.id }.toSet().size)
        store.finish()
    }

    @Test
    fun switchingSessionsCancelsHistoryAndRejectsOldVisitResponses() = sessionTest { h ->
        val root = testSession(id = "ses_root", title = "Root", updatedAt = distantPast)
        val child = testSession(id = "ses_child", title = "Child", updatedAt = distantPast, parentID = root.id)
        val item = user("latest", "Latest")
        val state = h.makeState(session = root, cachedItems = listOf(item), hasRenderedFirstContentFrame = true).copy(
            initialTranscriptIsFresh = true,
            familySessions = listOf(root, child),
            earlierHistory = State.EarlierHistory.Paging(cursor = "older"),
        )
        val pages = Channel<OpenCodeTranscriptPage>(Channel.UNLIMITED)
        val cancelled = LockIsolated(false)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                historyPage = { _, _, _ ->
                    try {
                        pages.receive()
                    } catch (cancellation: CancellationException) {
                        cancelled.setValue(true)
                        throw cancellation
                    }
                },
                getSession = { _, id -> if (id == child.id) child else root },
                transcript = { _, _, _ -> emptyList() },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.ShowEarlierTapped)
        store.send(Action.FamilyPicked(child.id))
        // The page read ends once the switch cancels it.
        for (i in 0 until 100) {
            if (cancelled.value) break
            yield()
        }
        assertTrue(cancelled.value)
        assertFalse(store.state.isLoadingEarlier)
        assertEquals(State.EarlierHistory.UnknownEdge, store.state.earlierHistory)
        store.send(Action.FamilyPicked(root.id))
        store.send(
            Action.EarlierHistoryResponse(
                root.id, 0, "older", Result.success(OpenCodeTranscriptPage(items = listOf(user("stale", "Old visit")), next = null)),
            ),
        )
        assertEquals(listOf<OpenCodeTranscriptItem>(item), store.state.items)
        assertEquals(State.EarlierHistory.UnknownEdge, store.state.earlierHistory)
        pages.close()
        h.skipInFlightEffects()
    }

    @Test
    fun newestWindowJumpReopensExhaustedHistoryAndCursorFillsInteriorGap() {
        for (splitAcrossPages in listOf(false, true)) sessionTest { h ->
            val session = testSession(id = "ses_root", title = "Root")
            val first = user("msg_first", "First")
            val missing = user("msg_middle", "Originally queued")
            val later = user("msg_later", "After the prompt")
            val last = user("msg_last", "Newest authoritative text")
            val state = h.makeState(session = session, cachedItems = listOf(first))
                .copy(earlierHistory = State.EarlierHistory.Exhausted, initialTranscriptIsFresh = true)
            val store = h.store(state) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    historyPage = { _, _, cursor ->
                        when (cursor) {
                            null -> OpenCodeTranscriptPage(
                                items = listOf(later, user("msg_last", "Stale page text")),
                                next = if (splitAcrossPages) "middle" else "oldest",
                            )
                            "middle" -> OpenCodeTranscriptPage(items = listOf(missing), next = "oldest")
                            else -> OpenCodeTranscriptPage(items = if (splitAcrossPages) listOf(first) else listOf(first, missing), next = null)
                        }
                    },
                )
            }
            store.exhaustive = false
            store.send(Action.TranscriptResponse(sessionID = session.id, result = Result.success(listOf(last))))
            assertEquals(State.EarlierHistory.UnknownEdge, store.state.earlierHistory)
            assertEquals(listOf<OpenCodeTranscriptItem>(first, last), store.state.items)
            assertEquals(0, store.state.historyGeneration, "Repairing a gap must not reset the reader's viewport")
            store.send(Action.ShowEarlierTapped)
            store.receive(Action.EarlierHistoryResponse::class)
            store.receive(Action.EarlierHistoryResponse::class)
            if (splitAcrossPages) store.receive(Action.EarlierHistoryResponse::class)
            store.finish()
            assertEquals(listOf<OpenCodeTranscriptItem>(first, missing, later, last), store.state.items)
            assertEquals(State.EarlierHistory.Exhausted, store.state.earlierHistory)
        }
    }

    @Test
    fun historyGapRepairRejectsCursorResponsesFromBeforeTheGap() = sessionTest { h ->
        val session = testSession(id = "ses_root", title = "Root")
        val state = h.makeState(session = session).copy(
            earlierHistoryGeneration = 1,
            earlierHistory = State.EarlierHistory.Loading(cursor = null, reachedLoadedItems = false),
        )
        val store = h.store(state)
        store.send(Action.EarlierHistoryResponse(session.id, 0, null, Result.success(OpenCodeTranscriptPage(items = emptyList(), next = null))))
        assertTrue(store.state.isLoadingEarlier)
    }

    @Test
    fun returningToAlreadyViewedSessionStillLoadsLatestTranscript() = sessionTest { h ->
        val session = testSession(id = "ses_resume", title = "Resume", updatedAt = distantPast, idleMilliseconds = 1234, viewedMilliseconds = 1234)
        val items = listOf<OpenCodeTranscriptItem>(assistant("final", "Finished while away"))
        val store = h.store(h.makeState(session = session, cachedItems = items)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ -> items },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sessionFamily = { _, _ -> throw offline },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw offline },
                pendingInputs = { _, _ -> emptyList() },
                viewSession = { _, _, _ -> throw AssertionError("Already viewed completion needs no new receipt") },
            )
        }
        store.exhaustive = false
        store.send(Action.VisibilityChanged(true))
        store.send(Action.FirstContentFrameRendered)
        store.receive(::isRefreshResponse, "refreshResponse")
        store.receive(::isAccepted, "acceptedTranscriptUpdated")
        assertEquals(items, store.state.items)
        store.finish()
    }

    private fun Harness.makeResumeStore(
        clock: TestClock,
        gate: Gate,
        cachedItems: List<OpenCodeTranscriptItem> = listOf(user("m1", "Ready")),
        transcriptResult: Result<List<OpenCodeTranscriptItem>>? = null,
    ): TestStore<State, Action> {
        val session = testSession(id = "ses_resume", title = "Resume")
        val state = makeState(session = session, cachedItems = cachedItems).copy(hasRenderedFirstContentFrame = true)
        val result = transcriptResult ?: Result.success(cachedItems)
        val store = store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    gate.await()
                    result.getOrThrow()
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sessionFamily = { _, _ -> throw offline },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> throw offline },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        return store
    }

    /** A read that ends within 350 ms (a quick sync) never shows the cue. */
    @Test
    fun quickResumeRefreshNeverShowsSyncCue() = sessionTest { h ->
        val clock = TestClock()
        val gate = Gate()
        val store = h.makeResumeStore(clock, gate)

        store.send(Action.VisibilityChanged(true))
        store.flush()
        assertFalse(store.state.isResyncing)
        clock.advance(349.milliseconds)
        gate.open(); gate.finish()
        store.receive(::isRefreshResponse, "refreshResponse")
        assertFalse(store.state.isResyncing)
        store.receive(::isAccepted, "acceptedTranscriptUpdated")
        clock.advance(2.seconds)
        store.flush()
        assertFalse(store.state.isResyncing)
        store.finish()
    }

    /** Once shown, the cue holds 900 ms even when the read ends just after. */
    @Test
    fun slowResumeRefreshHoldsCueForItsMinimum() = sessionTest { h ->
        val clock = TestClock()
        val gate = Gate()
        val store = h.makeResumeStore(clock, gate)

        store.send(Action.VisibilityChanged(true))
        store.flush()
        clock.advance(349.milliseconds)
        store.flush()
        assertFalse(store.state.isResyncing)
        clock.advance(1.milliseconds)
        store.receive(Action.ResyncDelayElapsed(refreshID = 1))
        assertTrue(store.state.isResyncing)
        clock.advance(50.milliseconds)
        gate.open(); gate.finish()
        store.receive(::isRefreshResponse, "refreshResponse")
        store.receive(::isAccepted, "acceptedTranscriptUpdated")
        clock.advance(849.milliseconds)
        store.flush()
        assertTrue(store.state.isResyncing)
        clock.advance(1.milliseconds)
        store.receive(Action.ResyncMinimumElapsed)
        assertFalse(store.state.isResyncing)
        store.finish()
    }

    /**
     * A read longer than the minimum keeps the cue until it ends, then lets
     * it go at once.
     */
    @Test
    fun longResumeRefreshShowsCueUntilItsResponse() = sessionTest { h ->
        val clock = TestClock()
        val gate = Gate()
        val store = h.makeResumeStore(clock, gate)

        store.send(Action.VisibilityChanged(true))
        store.flush()
        clock.advance(350.milliseconds)
        store.receive(Action.ResyncDelayElapsed(refreshID = 1))
        clock.advance(900.milliseconds)
        store.receive(Action.ResyncMinimumElapsed)
        assertTrue(store.state.isResyncing)
        clock.advance(1.seconds)
        store.flush()
        assertTrue(store.state.isResyncing)
        gate.open(); gate.finish()
        store.receive(::isRefreshResponse, "refreshResponse")
        assertFalse(store.state.isResyncing)
        store.finish()
    }

    /**
     * Back-to-back reads (the resume's, then the reconnect's) keep one cue:
     * the second neither hides it nor waits out the delay again, and the
     * minimum counts from when it first showed.
     */
    @Test
    fun backToBackReadsKeepOneContinuousCue() = sessionTest { h ->
        val clock = TestClock()
        val gate = Gate()
        val store = h.makeResumeStore(clock, gate)

        store.send(Action.VisibilityChanged(true))
        store.flush()
        clock.advance(350.milliseconds)
        store.receive(Action.ResyncDelayElapsed(refreshID = 1))
        clock.advance(150.milliseconds)
        gate.open()
        store.receive(::isRefreshResponse, "refreshResponse")
        assertTrue(store.state.isResyncing)

        store.send(Action.EventStreamConnected(testServer))
        store.receive(Action.RefreshRequested::class)
        store.flush()
        assertEquals(2, store.state.refresh?.id)
        assertTrue(store.state.isResyncing)
        assertEquals(2, store.state.resync?.refreshID)
        clock.advance(749.milliseconds)
        store.flush()
        assertTrue(store.state.isResyncing)
        clock.advance(1.milliseconds)
        store.receive(Action.ResyncMinimumElapsed)
        // The minimum passed during the second read: it waits for that read.
        assertTrue(store.state.isResyncing)
        clock.advance(300.milliseconds)
        store.flush()
        assertTrue(store.state.isResyncing)
        gate.open(); gate.finish()
        store.receive(::isRefreshResponse, "refreshResponse")
        assertFalse(store.state.isResyncing)
        store.finish()
    }

    /**
     * Flapping reads: a quick read just after the cue lets go starts over,
     * unshown, and stays hidden when it ends within the delay.
     */
    @Test
    fun aQuickReadAfterTheCueEndsStaysHidden() = sessionTest { h ->
        val clock = TestClock()
        val gate = Gate()
        val store = h.makeResumeStore(clock, gate)

        store.send(Action.VisibilityChanged(true))
        store.flush()
        clock.advance(350.milliseconds)
        store.receive(Action.ResyncDelayElapsed(refreshID = 1))
        clock.advance(1_000.milliseconds)
        store.receive(Action.ResyncMinimumElapsed)
        gate.open()
        store.receive(::isRefreshResponse, "refreshResponse")
        assertFalse(store.state.isResyncing)

        store.send(Action.EventStreamConnected(testServer))
        store.receive(Action.RefreshRequested::class)
        store.flush()
        assertFalse(store.state.isResyncing)
        clock.advance(200.milliseconds)
        gate.open(); gate.finish()
        store.receive(::isRefreshResponse, "refreshResponse")
        clock.advance(1.seconds)
        store.flush()
        assertFalse(store.state.isResyncing)
        store.finish()
    }

    @Test
    fun failedResumeKeepsCachedRowsAndSettlesIntoLastKnownState() = sessionTest { h ->
        val clock = TestClock()
        val cached = listOf<OpenCodeTranscriptItem>(user("m1", "Cached"))
        val gate = Gate()
        val store = h.makeResumeStore(clock, gate, cachedItems = cached, transcriptResult = Result.failure(offline))

        store.send(Action.VisibilityChanged(true))
        store.flush()
        clock.advance(350.milliseconds)
        store.receive(Action.ResyncDelayElapsed(refreshID = 1))
        gate.open(); gate.finish()
        store.receive(::isRefreshResponse, "refreshResponse")
        assertEquals(cached, store.state.items)
        assertTrue(store.state.refreshFailed)
        assertTrue(store.state.isResyncing)
        clock.advance(900.milliseconds)
        store.receive(Action.ResyncMinimumElapsed)
        assertFalse(store.state.isResyncing)
        store.finish()
    }

    @Test
    fun duplicateVisibleNotificationDoesNotRestartFreshness() = sessionTest { h ->
        val session = testSession(id = "ses_resume", title = "Resume")
        val state = h.makeState(session = session, cachedItems = listOf(user("m1", "Cached"))).copy(hasRenderedFirstContentFrame = true)
        val gate = Gate()
        val reads = LockIsolated(0)
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    reads.increment()
                    gate.await()
                    emptyList()
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sessionFamily = { _, _ -> throw offline },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> throw offline },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.VisibilityChanged(true))
        store.send(Action.VisibilityChanged(true))
        settle()
        assertEquals(1, reads.value)
        gate.finish()
        h.skipInFlightEffects()
    }

    @Test
    fun visibleSessionAcknowledgesOnlyObservedIdleAfterLoadingItsTranscript() = sessionTest { h ->
        val observed = testSession(id = "ses_view", title = "Viewed", updatedAt = distantPast, idleMilliseconds = 1234, viewedMilliseconds = 1000)
        val items = listOf<OpenCodeTranscriptItem>(user("message", "Loaded result"))
        val calls = LockIsolated<List<String>>(emptyList())
        val store = h.store(h.makeState(session = observed, cachedItems = items)) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id ->
                    calls.append("get $id")
                    observed
                },
                transcript = { _, id, _ ->
                    calls.append("transcript $id")
                    items
                },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sessionFamily = { _, _ -> throw offline },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw offline },
                pendingInputs = { _, _ -> emptyList() },
                viewSession = { _, id, idle -> calls.append("view $id $idle") },
            )
        }
        store.exhaustive = false
        store.send(Action.VisibilityChanged(true)) { it.copy(isVisible = true) }
        store.send(Action.FirstContentFrameRendered)
        store.receive(::isRefreshResponse, "refreshResponse")
        store.receive(::isAccepted, "acceptedTranscriptUpdated")
        store.receive(Action.ViewAcknowledged(sessionID = observed.id, idle = 1234)) {
            it.copy(viewedIdle = it.viewedIdle + (observed.id to 1234L), session = it.session?.copy(viewedMilliseconds = 1234))
        }
        store.send(Action.VisibilityChanged(false)) { it.copy(isVisible = false) }
        // A response from before the screen was hidden cannot acknowledge a newer idle.
        val newer = observed.copy(idleMilliseconds = 2000)
        store.send(
            Action.RefreshResponse(
                sessionID = observed.id,
                requestID = 1,
                result = Result.success(SessionFeature.AuthoritativeRefresh(observed = newer, items = items, scope = page)),
            ),
        )
        store.finish()
        assertTrue(calls.value.all { it == "get ${observed.id}" || it == "transcript ${observed.id}" || it == "view ${observed.id} 1234" }, "$calls")
    }

    @Test
    fun catchUpCardShowsTheSessionWithoutSendingAReadReceipt() = sessionTest { h ->
        val observed = testSession(id = "ses_card", title = "Card", updatedAt = distantPast, idleMilliseconds = 1234, viewedMilliseconds = 1000)
        val items = listOf<OpenCodeTranscriptItem>(user("message", "Loaded result"))
        val state = h.makeState(session = observed, cachedItems = items).copy(readsOnView = false)
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> observed },
                transcript = { _, _, _ -> items },
                contextUsage = { _, _ -> OpenCodeContextReading.Empty },
                sessionFamily = { _, _ -> throw offline },
                runningShells = { _, _ -> emptyList() },
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                familyPendingRequests = { _, _, _ -> throw offline },
                pendingInputs = { _, _ -> emptyList() },
                viewSession = { _, _, _ -> throw AssertionError("Only the reader's swipe marks a catch-up card read") },
            )
        }
        store.exhaustive = false
        store.send(Action.VisibilityChanged(true)) { it.copy(isVisible = true) }
        store.send(Action.FirstContentFrameRendered)
        store.receive(::isRefreshResponse, "refreshResponse")
        store.receive(::isAccepted, "acceptedTranscriptUpdated")
        store.send(Action.VisibilityChanged(false))
        store.finish()
        assertTrue(store.state.viewedIdle.isEmpty())
    }
}
