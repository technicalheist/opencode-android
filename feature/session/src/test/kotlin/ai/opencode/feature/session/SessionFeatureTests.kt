package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.eventually
import ai.opencode.feature.composer.RequestDockFeature
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.AuthoritativeRefresh
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.settle
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.server.EventBatcher
import ai.opencode.server.EventEmission
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.model.OpenCodeImageAttachment
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodeModelRef
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.SessionTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SessionFeatureTests {
    private fun markdown(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text)

    private fun toolCall(name: String, status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Completed) =
        OpenCodeToolCall(callID = name, name = name, status = status, input = OpenCodeJSONValue.Null, output = emptyList(), error = null, metadata = emptyMap())

    private fun refreshed(session: ai.opencode.server.model.OpenCodeSession, items: List<OpenCodeTranscriptItem>, scope: AuthoritativeRefresh.Scope) =
        Result.success(AuthoritativeRefresh(observed = session, items = items, scope = scope))

    @Test fun workingDescendantsRemainVisibleAfterParentSettles() = sessionTest { h ->
        val root = testSession(id = "root", title = "Main")
        val child = testSession(id = "child", title = "Research", parentID = root.id)
        val nested = testSession(id = "nested", title = "Check sources", parentID = child.id)
        val unrelated = testSession(id = "other", title = "Other session")
        val state = h.state { State.session(testServer, root) }.copy(familySessions = listOf(root, child, nested, unrelated))
        val store = h.store(state)
        store.send(Action.FamilyRunStateChanged(sessionID = nested.id, isRunning = true)) {
            it.copy(activeFamilySessionIDs = setOf(nested.id), familyRunChanges = mapOf(nested.id to true))
        }
        assertEquals(listOf(nested), store.state.activeSubagents)
        assertTrue(store.state.isBusy)
        assertFalse("Do not offer to interrupt an idle parent's run", store.state.isRunning)
        store.send(Action.FamilyRunStateChanged(sessionID = unrelated.id, isRunning = true)) {
            it.copy(activeFamilySessionIDs = it.activeFamilySessionIDs + unrelated.id, familyRunChanges = it.familyRunChanges + (unrelated.id to true))
        }
        assertEquals(listOf(nested), store.state.activeSubagents)
        store.send(Action.FamilyRunStateChanged(sessionID = nested.id, isRunning = false)) {
            it.copy(activeFamilySessionIDs = it.activeFamilySessionIDs - nested.id, familyRunChanges = it.familyRunChanges + (nested.id to false))
        }
        assertTrue(store.state.activeSubagents.isEmpty())
        assertFalse(store.state.isBusy)
    }

    /**
     * A family read's active status is taken when it starts. A subagent that
     * starts (or ends) before its reply lands must not be reverted by it,
     * even when the read is the one that first lists that subagent.
     */
    @Test fun runChangesDuringAFamilyReadSurviveItsOlderActiveStatus() = sessionTest { h ->
        val root = testSession(id = "root", title = "Main")
        val early = testSession(id = "early", title = "Plan", parentID = root.id)
        val late = testSession(id = "late", title = "Dungeon", parentID = root.id)
        val state = h.state { State.session(testServer, root) }.copy(
            familyRootID = root.id,
            familySessions = listOf(root, early),
            activeFamilySessionIDs = setOf(early.id),
            familyRunChanges = mapOf("stale" to true),
        )
        val family = Channel<OpenCodeSessionFamily>(Channel.UNLIMITED)
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sessionFamily = { _, _ -> family.receiveCatching().getOrNull() ?: throw OpenCodeClientError("Closed") },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.FamilyRefreshRequested) { it.copy(familyRunChanges = emptyMap()) }
        // Home forwards both, though `late` isn't listed yet.
        store.send(Action.FamilyRunStateChanged(sessionID = late.id, isRunning = true))
        store.send(Action.FamilyRunStateChanged(sessionID = early.id, isRunning = false))
        family.trySend(OpenCodeSessionFamily(sessions = listOf(root, early, late), activeSessionIDs = setOf(early.id)))
        family.close()
        store.receive(Action.FamilyResponse::class) {
            it.copy(familySessions = listOf(root, early, late), activeFamilySessionIDs = setOf(late.id))
        }
        assertEquals(listOf(late), store.state.activeSubagents)
        h.skipInFlightEffects()
    }

    /** A family read that waits for [releaseFirst], counting starts and cancellations. */
    private class HeldReads {
        val started = LockIsolated(0)
        val cancelled = LockIsolated(0)
        private val gates = Channel<Unit>(Channel.UNLIMITED)

        suspend fun hold() {
            started.increment()
            try {
                gates.receive()
            } catch (error: CancellationException) {
                cancelled.increment()
                throw error
            }
        }

        /** Lets the oldest held read answer. */
        fun releaseFirst() {
            gates.trySend(Unit)
        }
    }

    private fun heldFamilyState(h: SessionTestSupport.Harness): State {
        val root = testSession(id = "root", title = "Main")
        return h.state { State.session(testServer, root) }.copy(familyRootID = root.id, familySessions = listOf(root))
    }

    private fun heldFamilyClient(client: OpenCodeClient, reads: HeldReads, shells: LockIsolated<Int>) = client.copy(
        sessionFamily = { _, root ->
            reads.hold()
            OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet())
        },
        runningShells = { _, _ ->
            shells.increment()
            emptyList()
        },
        familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
        pendingInputs = { _, _ -> emptyList() },
    )

    /** Subagents starting in a burst each ask for the family: the walk in flight finishes and one more follows it, and nobody lists shells. */
    @Test fun aBurstOfSubagentsWalksTheFamilyTwice() = sessionTest { h ->
        val reads = HeldReads()
        val shells = LockIsolated(0)
        val store = h.store(heldFamilyState(h)) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = heldFamilyClient(this[OpenCodeClient], reads, shells)
        }
        store.exhaustive = false

        repeat(10) { store.send(Action.FamilyRefreshRequested) }
        assertTrue(eventually { reads.started.value == 1 })
        assertTrue(store.state.familyRead.isQueued)

        reads.releaseFirst()
        store.receive(Action.FamilyResponse::class)
        assertTrue(eventually { reads.started.value == 2 })
        reads.releaseFirst()
        store.receive(Action.FamilyResponse::class)
        settle()
        assertEquals(2, reads.started.value)
        assertEquals(0, reads.cancelled.value)
        assertFalse(store.state.familyRead.isInFlight)
        assertEquals(0, shells.value)
        h.skipInFlightEffects()
    }

    /**
     * An answer with a read queued behind it doesn't make the family fresh: only the last one does,
     * so coming back within the freshness window still reads the family.
     */
    @Test fun onlyTheLastQueuedFamilyAnswerMakesItFresh() = sessionTest { h ->
        val reads = HeldReads()
        val store = h.store(heldFamilyState(h)) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = heldFamilyClient(this[OpenCodeClient], reads, LockIsolated(0))
        }
        store.exhaustive = false

        store.send(Action.FamilyRefreshRequested)
        store.send(Action.FamilyRefreshRequested)
        assertTrue(eventually { reads.started.value == 1 })
        reads.releaseFirst()
        store.receive(Action.FamilyResponse::class)
        assertNull(store.state.familyReadAt)
        assertTrue(eventually { reads.started.value == 2 })
        reads.releaseFirst()
        store.receive(Action.FamilyResponse::class)
        assertEquals(date(100), store.state.familyReadAt)
        h.skipInFlightEffects()
    }

    /** Leaving the screen cancels the walk and what was queued behind it, so the next ask starts a read. */
    @Test fun leavingTheScreenForgetsTheFamilyWalk() = sessionTest { h ->
        val reads = HeldReads()
        val store = h.store(heldFamilyState(h).copy(isVisible = true)) {
            this[DateClient] = DateClient.constant(date(100))
            this[OpenCodeClient] = heldFamilyClient(this[OpenCodeClient], reads, LockIsolated(0))
        }
        store.exhaustive = false

        store.send(Action.FamilyRefreshRequested)
        store.send(Action.FamilyRefreshRequested)
        assertTrue(eventually { reads.started.value == 1 })
        store.send(Action.VisibilityChanged(false))
        assertTrue(eventually { reads.cancelled.value == 1 })
        assertTrue(!store.state.familyRead.isInFlight && !store.state.familyRead.isQueued)
        store.send(Action.FamilyRefreshRequested)
        assertTrue(eventually { reads.started.value == 2 })
        h.skipInFlightEffects()
    }

    /** A new stream relists shells itself (an exit may have been missed), now that family reads don't. */
    @Test fun aNewStreamRelistsShells() = sessionTest { h ->
        val state = h.state { State.session(testServer, testSession(id = "root", title = "Main")) }
            .copy(refresh = State.Refresh(id = 1, reason = RefreshReason.Open))
        val shells = LockIsolated(0)
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[Clock] = TestClock()
            this[OpenCodeClient] = heldFamilyClient(this[OpenCodeClient], HeldReads(), shells)
        }
        store.exhaustive = false

        store.send(Action.EventStreamConnected(testServer))
        store.receive(Action.ShellsResponse::class)
        assertEquals(1, shells.value)
        h.skipInFlightEffects()
    }

    @Test fun backgroundShellsAreThoseThatOutliveTheirToolCall() = sessionTest { h ->
        val root = testSession(id = "root", title = "Main")
        fun tool(shellID: String, status: OpenCodeToolCall.Status) = OpenCodeTranscriptItem.Tool(
            id = "m-tool-$shellID",
            call = OpenCodeToolCall(
                callID = shellID, name = "shell", status = status, input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null,
                metadata = mapOf("shellID" to OpenCodeJSONValue.Str(shellID)),
            ),
        )
        val justStarted = OpenCodeShell(id = "sh_new", command = "ls", sessionID = root.id)
        val foreground = OpenCodeShell(id = "sh_fg", command = "bun test", sessionID = root.id, isListed = true)
        val backgrounded = OpenCodeShell(id = "sh_bg", command = "bun dev", sessionID = root.id)
        val predating = OpenCodeShell(id = "sh_old", command = "tail -f log", sessionID = root.id, isListed = true)
        val userCommand = OpenCodeShell(id = "sh_user", command = "make", sessionID = root.id, isUserCommand = true)
        val otherSession = OpenCodeShell(id = "sh_other", command = "sleep 9", sessionID = "child", isListed = true)
        val state = h.state {
            State.session(
                server = testServer,
                session = root,
                cachedItems = listOf(tool("sh_fg", OpenCodeToolCall.Status.Running), tool("sh_bg", OpenCodeToolCall.Status.Completed)),
            )
        }.copy(shells = listOf(justStarted, foreground, backgrounded, predating, userCommand, otherSession))

        // A live shell waits for its tool call's verdict, so a quick command
        // never flashes the indicator before its progress event arrives.
        assertEquals(listOf("sh_bg", "sh_old", "sh_user"), state.backgroundShells.map { it.id })
    }

    @Test fun shellEventsAndListingsTrackOnlyTheSelectedSession() = sessionTest { h ->
        val root = testSession(id = "root", title = "Main")
        val store = h.store(h.state { State.session(testServer, root) })
        val dev = OpenCodeShell(id = "sh_dev", command = "bun dev", sessionID = root.id)
        val exited = OpenCodeShell(id = "sh_done", command = "ls", sessionID = root.id, isListed = true)
        val listed = OpenCodeShell(id = "sh_log", command = "tail -f log", sessionID = root.id, isListed = true)
        val foreign = OpenCodeShell(id = "sh_x", command = "sleep 9", sessionID = "elsewhere", isListed = true)

        store.send(Action.ShellStarted(dev)) { it.copy(shells = listOf(dev)) }
        store.send(Action.ShellStarted(foreign))
        store.send(Action.ShellEnded("sh_done")) { it.copy(endedShellIDs = setOf("sh_done")) }
        // The list was requested before sh_done exited and before sh_dev started.
        store.send(Action.ShellsResponse(sessionID = root.id, result = Result.success(listOf(exited, listed, foreign)))) {
            it.copy(shells = listOf(listed, dev))
        }
        store.send(Action.ShellsResponse(sessionID = "stale", result = Result.success(emptyList())))
        store.send(Action.ShellEnded("sh_dev")) {
            it.copy(shells = listOf(listed), endedShellIDs = it.endedShellIDs + "sh_dev")
        }
    }

    @Test fun textDeltaBatchProjectsWithoutREST() = sessionTest { h ->
        val session = testSession(id = "ses_stream", title = "Stream")
        val reads = LockIsolated(0)
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(transcript = { _, _, _ -> reads.increment(); emptyList() })
        }
        store.exhaustive = false

        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(
                    OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = "Hel"),
                    OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = "lo"),
                ),
            ),
        )
        assertEquals(listOf(markdown("msg_reply-text-0", "Hello").copy(inProgress = true)), store.state.items)
        assertEquals("Hello", store.state.liveText["msg_reply-text-0"])
        assertEquals(0, reads.value)
    }

    @Test fun openingMidReplyProjectsThePrefixIncludingShedDeltas() = sessionTest { h ->
        val session = testSession(id = "ses_stream", title = "Stream")
        var focused = false
        val queued = mutableListOf<OpenCodeServerEvent>()
        val batcher = EventBatcher(
            scope = h.scope,
            clock = TestClock(),
            capacity = 4,
            isFocused = { focused },
            emit = { event ->
                queued += event
                EventEmission.Enqueued(remaining = 4 - queued.size)
            },
        )
        fun flush(vararg events: OpenCodeLiveContentEvent) {
            batcher.receive(OpenCodeServerEvent.ContentBatch(session.id, events.toList()))
            batcher.flush()
        }
        batcher.receive(OpenCodeServerEvent.Connected)
        flush(OpenCodeLiveContentEvent.TextStarted("m", 0), OpenCodeLiveContentEvent.TextDelta("m", 0, "One"))
        // Half the buffer is occupied, so the next off-screen batch is shed.
        flush(OpenCodeLiveContentEvent.TextDelta("m", 0, " two"))
        focused = true
        flush(OpenCodeLiveContentEvent.TextDelta("m", 0, " th"), OpenCodeLiveContentEvent.TextDelta("m", 0, "ree"))
        flush(OpenCodeLiveContentEvent.TextDelta("m", 0, "!"))
        // Connection and first batch predate opening the detail.
        val batches = queued.drop(2)
        val store = h.store(h.makeState(session = session, cachedItems = emptyList()))
        store.exhaustive = false
        assertEquals(2, batches.size)
        for ((batch, expected) in batches.zip(listOf("One two three", "One two three!"))) {
            val events = (batch as OpenCodeServerEvent.ContentBatch).events
            store.send(Action.ContentBatch(sessionID = session.id, events = events))
            assertEquals(listOf(markdown("m-text-0", expected).copy(inProgress = true)), store.state.items)
        }
    }

    @Test fun aRunningMessagesNewPartsStayAheadOfAModelSwitchRecordedMidStep() = sessionTest { h ->
        // A steering send applied a model while msg_1a… was under way: history
        // keeps that message's parts together before the switch, and a later
        // step's message goes below it.
        val session = testSession(id = "ses_stream", title = "Stream")
        val model = OpenCodeModelRef(id = "pro", providerID = "fixture", variant = "high")
        val switched = OpenCodeTranscriptItem.ModelSwitch(id = "msg_1b", model = model, previous = OpenCodeModelRef(id = "pro", providerID = "fixture"))
        val prompt = OpenCodeTranscriptItem.UserText(id = "msg_19", text = "Go")
        val store = h.store(h.makeState(session = session, cachedItems = listOf(prompt, switched), isRunning = true)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(transcript = { _, _, _ -> emptyList() })
        }
        store.exhaustive = false

        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(
                    OpenCodeLiveContentEvent.TextDelta(messageID = "msg_1a", ordinal = 0, delta = "First part"),
                    OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_1a", toolID = "call_1", name = "read"),
                    OpenCodeLiveContentEvent.TextDelta(messageID = "msg_1c", ordinal = 0, delta = "Next step"),
                ),
            ),
        )
        assertEquals(listOf("msg_19", "msg_1a-text-0", "msg_1a-tool-call_1", "msg_1b", "msg_1c-text-0"), store.state.items.map { it.id })
    }

    @Test fun endedBoundaryReplacesDeltaTextAndSettlesOnce() = sessionTest { h ->
        val session = testSession(id = "ses_stream", title = "Stream")
        val pageReads = LockIsolated(0)
        val messageReads = LockIsolated(0)
        val settled = listOf<OpenCodeTranscriptItem>(markdown("msg_reply-text-0", "Server value"))
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> pageReads.increment(); settled },
                message = { _, _, messageID ->
                    assertEquals("msg_reply", messageID)
                    messageReads.increment()
                    settled
                },
            )
        }
        store.exhaustive = false

        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(
                    OpenCodeLiveContentEvent.TextDelta(messageID = "msg_reply", ordinal = 0, delta = "Client"),
                    OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Ended value"),
                ),
            ),
        )
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
        store.receive(Action.RefreshResponse::class)
        assertEquals(0, pageReads.value)
        assertEquals(1, messageReads.value)
        assertEquals(settled, store.state.items)
    }

    @Test fun completedReadToolImageSettlesIntoTheOpenTranscript() = sessionTest { h ->
        val session = testSession(id = "ses_image", title = "Image")
        val image = OpenCodeImageAttachment(id = "msg_reply-tool-call_read-image-1", data = byteArrayOf(1, 2, 3))
        val settled = listOf<OpenCodeTranscriptItem>(
            OpenCodeTranscriptItem.Tool(
                id = "msg_reply-tool-call_read",
                call = OpenCodeToolCall(
                    callID = "call_read", messageID = "msg_reply", name = "read", status = OpenCodeToolCall.Status.Completed,
                    input = OpenCodeJSONValue.Object(mapOf("path" to OpenCodeJSONValue.Str("preview.png"))),
                    output = listOf("Image read successfully"), error = null, metadata = emptyMap(), images = listOf(image),
                ),
            ),
        )
        val messageReads = LockIsolated(0)
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                message = { _, _, messageID ->
                    assertEquals("msg_reply", messageID)
                    messageReads.increment()
                    settled
                },
                transcript = { _, _, _ -> throw AssertionError("Image settlement must read only the message") },
            )
        }
        store.exhaustive = false

        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(
                    OpenCodeLiveContentEvent.ToolInputStarted(messageID = "msg_reply", toolID = "call_read", name = "read"),
                    OpenCodeLiveContentEvent.ToolSucceeded(messageID = "msg_reply", toolID = "call_read", output = listOf("Image read successfully"), metadata = emptyMap()),
                ),
            ),
        )
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
        store.receive(Action.RefreshResponse::class)
        assertEquals(1, messageReads.value)
        assertEquals(settled, store.state.items)
        assertEquals(listOf(1), store.state.items.mapNotNull { (it as? OpenCodeTranscriptItem.Tool)?.call?.imageAttachments?.size })
    }

    @Test fun nearbySettlementsForOneMessageCoalesceIntoOneRead() = sessionTest { h ->
        val clock = TestClock()
        val session = testSession(id = "ses_stream", title = "Stream")
        val reads = LockIsolated(0)
        val settled = listOf<OpenCodeTranscriptItem>(markdown("msg_reply-text-0", "Done"))
        val store = h.store(h.makeState(session = session, cachedItems = settled)) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, _ -> reads.increment(); settled },
                transcript = { _, _, _ -> throw AssertionError("Settlement must not read a page") },
            )
        }
        store.exhaustive = false

        store.send(Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.TextEnded(messageID = "msg_reply", ordinal = 0, text = "Done"))))
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
        clock.advance(100.milliseconds)
        store.send(Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.StepEnded(messageID = "msg_reply"))))
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_reply")))
        clock.advance(100.milliseconds)
        store.receive(Action.RefreshResponse::class)
        assertEquals(1, reads.value)
    }

    @Test fun settlementOutsideCurrentWindowFallsBackToPage() = sessionTest { h ->
        val session = testSession(id = "ses_stream", title = "Stream")
        val pageReads = LockIsolated(0)
        val store = h.store(h.makeState(session = session, cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "msg_user", text = "Hello")))) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                message = { _, _, _ -> throw AssertionError("Unknown message must use overlap-safe page reconciliation") },
                transcript = { _, _, _ -> pageReads.increment(); emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.StepEnded(messageID = "msg_missing"))))
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_missing")))
        store.receive(Action.RefreshResponse::class)
        assertEquals(1, pageReads.value)
    }

    @Test fun pendingWorkPollSlowsOnceAnAlreadyConnectedStreamProvesHealthy() = sessionTest { h ->
        // Home reports `eventStreamConnected` only when the server connects, so a
        // session opened on an established stream starts its poll unhealthy.
        val session = testSession(id = "ses_poll", title = "Poll")
        val reads = LockIsolated(0)
        val store = h.store(h.makeState(session = session)) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, _ ->
                    reads.increment()
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.FamilyResponse(Result.failure(OpenCodeClientError("offline")))) { it.copy(pendingWorkPollSeconds = 2) }
        store.receive(Action.PendingRequestsResponse::class)
        store.send(Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.StepStarted(messageID = "msg_1")))) {
            it.copy(streamIsHealthy = true, streamSupportsProjection = true, pendingWorkPollSeconds = 12)
        }
        store.receive(Action.PendingRequestsResponse::class)
        assertEquals(2, reads.value)
        h.skipInFlightEffects()
    }

    @Test fun staleMessageResponseCannotOverwriteNewerPageGeneration() = sessionTest { h ->
        val session = testSession(id = "ses_stream", title = "Stream")
        val old = listOf<OpenCodeTranscriptItem>(markdown("msg_reply-text-0", "Old"))
        val fresh = listOf<OpenCodeTranscriptItem>(markdown("msg_reply-text-0", "Fresh"))
        val state = h.makeState(session = session, cachedItems = fresh).copy(refresh = State.Refresh(id = 2, reason = RefreshReason.Open))
        val store = h.store(state)
        store.exhaustive = false

        store.send(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = refreshed(session, old, AuthoritativeRefresh.Scope.Message("msg_reply"))))
        assertEquals(fresh, store.state.items)
        store.send(Action.RefreshResponse(sessionID = session.id, requestID = 2, result = refreshed(session, fresh, AuthoritativeRefresh.Scope.Page)))
        assertEquals(fresh, store.state.items)
    }

    @Test fun messageSettlementKeepsTheTitleReceivedDuringItsRead() = sessionTest { h ->
        val session = testSession(title = SessionTitle.placeholder).copy(firstPrompt = "Explain todo.js")
        val state = h.makeState(session = session).copy(refresh = State.Refresh(id = 1, reason = RefreshReason.Settlement("msg_reply")))
        val store = h.store(state)
        store.exhaustive = false

        store.send(Action.FamilySessionRenamed(session.id, "Todo CLI walkthrough"))
        store.send(Action.RefreshResponse(session.id, 1, refreshed(session, emptyList(), AuthoritativeRefresh.Scope.Message("msg_reply"))))
        assertEquals("Todo CLI walkthrough", store.state.session?.title)
        assertEquals("Todo CLI walkthrough", store.state.familySessions.single().title)
    }

    @Test fun messageSettlementKeepsLaterRowsAndRacedLiveContent() = sessionTest { h ->
        val session = testSession(id = "ses_settle", title = "Settle", idleMilliseconds = 2000, viewedMilliseconds = 1000)
        val first = OpenCodeTranscriptItem.UserText(id = "msg_a", text = "First")
        val draft = markdown("msg_b-text-0", "Draft")
        val later = OpenCodeTranscriptItem.UserText(id = "msg_c", text = "Later")
        val settled = markdown("msg_b-text-0", "Final")
        val streaming = markdown("msg_d-text-0", "Streaming").copy(inProgress = true)
        val tool = OpenCodeTranscriptItem.Tool(
            id = "msg_d-tool-t1",
            call = OpenCodeToolCall(
                callID = "t1", messageID = "msg_d", name = "tool", status = OpenCodeToolCall.Status.Running,
                input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = emptyMap(),
            ),
        )
        val state = h.makeState(session = session, cachedItems = listOf(first, draft, later)).copy(isVisible = true)
        val gate = CompletableDeferred<Unit>()
        val receipts = LockIsolated(listOf<Long>())
        val store = h.store(state) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                message = { _, _, messageID ->
                    assertEquals("msg_b", messageID)
                    gate.await()
                    listOf(settled)
                },
                transcript = { _, _, _ -> throw AssertionError("A message settlement must not read a page") },
                viewSession = { _, _, idle -> receipts.append(idle) },
            )
        }
        store.exhaustive = false

        store.send(Action.ContentBatch(sessionID = session.id, events = listOf(OpenCodeLiveContentEvent.TextEnded(messageID = "msg_b", ordinal = 0, text = "Final"))))
        store.receive(Action.RefreshRequested(RefreshReason.Settlement(messageID = "msg_b")))
        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(
                    OpenCodeLiveContentEvent.TextDelta(messageID = "msg_d", ordinal = 0, delta = "Streaming"),
                    OpenCodeLiveContentEvent.ToolProgress(messageID = "msg_d", toolID = "t1", metadata = emptyMap()),
                ),
            ),
        )
        gate.complete(Unit)

        val expected = listOf(first, settled, later, streaming, tool)
        store.receive(Action.RefreshResponse::class)
        store.receive(Action.AcceptedTranscriptUpdated(sessionID = session.id, items = expected))
        store.receive(Action.ViewAcknowledged(sessionID = session.id, idle = 2000))
        store.finish()
        assertEquals(expected, store.state.items)
        assertEquals("Streaming", store.state.liveText["msg_d-text-0"])
        assertEquals(listOf(2000L), receipts.value)
    }

    @Test fun siblingSettlementDoesNotEraseAnUnfinishedTool() {
        for (whileRunning in listOf(true, false)) siblingSettlementDoesNotEraseAnUnfinishedTool(whileRunning)
    }

    private fun siblingSettlementDoesNotEraseAnUnfinishedTool(whileRunning: Boolean) = sessionTest { h ->
        val session = testSession(id = "ses_parts", title = "Parts")
        val text = markdown("msg_reply-text-0", "Checking now")
        val tool = OpenCodeTranscriptItem.Tool(
            id = "msg_reply-tool-call",
            call = OpenCodeToolCall(
                callID = "call", messageID = "msg_reply", name = "shell", status = OpenCodeToolCall.Status.Running,
                input = OpenCodeJSONValue.Object(emptyMap()), output = emptyList(), error = null, metadata = emptyMap(),
            ),
        )
        val state = h.makeState(session = session, cachedItems = listOf(text, tool)).copy(
            isExternallyActive = whileRunning,
            projectedMessageIDs = setOf("msg_reply"),
            refresh = State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_reply")),
        )
        val store = h.store(state)
        store.exhaustive = false

        // text.ended is durable for the text, not every sibling part. The tool
        // started before the read, so it is not in racedEvents either.
        store.send(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = refreshed(session, listOf(text), AuthoritativeRefresh.Scope.Message("msg_reply"))))
        assertEquals("whileRunning=$whileRunning", if (whileRunning) listOf(text, tool) else listOf(text), store.state.items)
    }

    @Test fun reconnectKeepsLiveRowsUntilRESTCanEchoThem() = sessionTest { h ->
        val session = testSession(id = "ses_reconnect", title = "Reconnect")
        val user = OpenCodeTranscriptItem.UserText(id = "msg_user", text = "Check")
        val text = markdown("msg_reply-text-0", "Checking")
        val tool = OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-call", call = toolCall(name = "shell"))
        val state = h.makeState(session = session, cachedItems = listOf(user, text, tool)).copy(
            isExternallyActive = true,
            projectedMessageIDs = setOf("msg_reply"),
            refresh = State.Refresh(id = 1, reason = RefreshReason.Resume),
        )
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(runningShells = { _, _ -> emptyList() }, runningJobs = { _, _ -> emptyList() })
        }
        store.exhaustive = false
        store.send(Action.EventStreamConnected(testServer))
        store.send(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = refreshed(session, listOf(user), AuthoritativeRefresh.Scope.Page)))
        assertEquals(listOf(user, text, tool), store.state.items)
        store.finish()
    }

    @Test fun retainedToolDoesNotReplayItsInputDeltaTwice() {
        for (toolIsInSnapshot in listOf(false, true)) retainedToolDoesNotReplayItsInputDeltaTwice(toolIsInSnapshot)
    }

    private fun retainedToolDoesNotReplayItsInputDeltaTwice(toolIsInSnapshot: Boolean) = sessionTest { h ->
        val session = testSession(id = "ses_input", title = "Input")
        val text = markdown("msg_reply-text-0", "Checking")
        val tool = OpenCodeTranscriptItem.Tool(
            id = "msg_reply-tool-call",
            call = OpenCodeToolCall(
                callID = "call", messageID = "msg_reply", name = "shell", status = OpenCodeToolCall.Status.Streaming,
                input = OpenCodeJSONValue.Str("{"), output = emptyList(), error = null, metadata = emptyMap(),
            ),
        )
        val state = h.makeState(session = session, cachedItems = listOf(text, tool)).copy(
            isExternallyActive = true,
            projectedMessageIDs = setOf("msg_reply"),
            refresh = State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_reply")),
        )
        val store = h.store(state)
        store.exhaustive = false
        store.send(
            Action.ContentBatch(
                sessionID = session.id,
                events = listOf(OpenCodeLiveContentEvent.ToolInputDelta(messageID = "msg_reply", toolID = "call", delta = "\"command\":")),
            ),
        )
        val projected = store.state.items
        store.send(
            Action.RefreshResponse(
                sessionID = session.id, requestID = 1,
                result = refreshed(session, if (toolIsInSnapshot) listOf(text, tool) else listOf(text), AuthoritativeRefresh.Scope.Message("msg_reply")),
            ),
        )
        assertEquals("toolIsInSnapshot=$toolIsInSnapshot", projected, store.state.items)
    }

    @Test fun refreshKeepsProjectedPartsBeforeLaterSteeringEchoes() = sessionTest { h ->
        val session = testSession(id = "ses_order", title = "Order")
        val user = OpenCodeTranscriptItem.UserText(id = "msg_user", text = "First")
        val tool = OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-call", call = toolCall(name = "subagent"))
        val steer = OpenCodeTranscriptItem.UserText(id = "msg_steer", text = "Also check this")
        val state = h.makeState(session = session, cachedItems = listOf(user, tool)).copy(
            queuedInputs = listOf(OpenCodePendingInput(id = "msg_steer", text = "Also check this")),
            promptStates = mapOf("msg_steer" to State.PromptState(requestEpoch = 1, admission = State.PromptState.Admission.admitted)),
            isExternallyActive = true,
            projectedMessageIDs = setOf("msg_reply"),
            refresh = State.Refresh(id = 1, reason = RefreshReason.RunState),
        )
        val store = h.store(state)
        store.exhaustive = false
        store.send(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = refreshed(session, listOf(user, steer), AuthoritativeRefresh.Scope.Page)))
        assertEquals(listOf(user, tool, steer), store.state.items)
    }

    @Test fun queuedSendRefreshKeepsCurrentLiveToolUntilRestEchoesIt() = sessionTest { h ->
        val session = testSession(id = "ses_steer", title = "Steer")
        val user = OpenCodeTranscriptItem.UserText(id = "msg_user", text = "First prompt")
        val tool = OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-call", call = toolCall(name = "shell"))
        val state = h.makeState(session = session, cachedItems = listOf(user, tool)).copy(
            isExternallyActive = true,
            projectedMessageIDs = setOf("msg_reply"),
            refresh = State.Refresh(id = 1, reason = RefreshReason.RunState),
        )
        val store = h.store(state)
        store.exhaustive = false

        store.send(Action.RefreshResponse(sessionID = session.id, requestID = 1, result = refreshed(session, listOf(user), AuthoritativeRefresh.Scope.Page)))
        assertEquals(listOf(user, tool), store.state.items)
    }

    @Test fun invalidationDuringRefreshQueuesExactlyOneFollowup() = sessionTest { h ->
        val session = testSession(id = "ses_refresh", title = "Refresh")
        val reads = LockIsolated(0)
        val first = CompletableDeferred<Unit>()
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    val number = reads.increment()
                    if (number == 1) first.await()
                    emptyList()
                },
            )
        }
        store.exhaustive = false

        store.send(Action.RefreshRequested(RefreshReason.Open))
        store.send(Action.RefreshRequested(RefreshReason.Snapshot))
        store.send(Action.RefreshRequested(RefreshReason.Invalidation))
        settle()
        assertEquals(1, reads.value)
        first.complete(Unit)
        store.receive(Action.RefreshResponse::class)
        store.receive(Action.RefreshRequested(RefreshReason.Invalidation))
        store.receive(Action.RefreshResponse::class)
        assertEquals(2, reads.value)
    }

    @Test fun streamReconnectCoalescesWithResumeRead() = sessionTest { h ->
        val session = testSession(id = "ses_resume", title = "Resume")
        val reads = LockIsolated(0)
        val gate = CompletableDeferred<Unit>()
        val server = testServer.at(url = "http://alternate.test")
        val store = h.store(h.makeState(session = session, cachedItems = emptyList())) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    reads.increment()
                    gate.await()
                    emptyList()
                },
                sessionFamily = { _, _ -> throw OpenCodeClientError("Offline") },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> throw OpenCodeClientError("Offline") },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.RefreshRequested(RefreshReason.Resume))
        store.send(Action.EventStreamConnected(server))
        settle()
        assertEquals(1, reads.value)
        assertEquals(server, store.state.server)
        gate.complete(Unit)
        store.receive(Action.RefreshResponse::class)
        assertEquals(1, reads.value)
        store.skipReceivedActions()
    }

    /**
     * Without a healthy stream, reconciliation is the only content path, so a
     * running session the server answers for falls back to the short cadence;
     * a healthy projecting stream only needs a slow watchdog. (A server that
     * isn't answering gets none: SessionFeatureTestsQuietPolling.)
     */
    @Test fun watchdogCadenceFollowsStreamHealth() = sessionTest { h ->
        val session = testSession(id = "ses_watchdog", title = "Watchdog")
        val clock = TestClock()
        val reads = LockIsolated(0)
        val state = h.state {
            State.session(testServer, session, cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi")), isRunning = true)
        }.withRequestsConnectionIsAvailable(false)
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(watchdog = true)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
                getSession = { _, _ -> session },
                transcript = { _, _, _ ->
                    reads.increment()
                    listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "Hi"))
                },
            )
        }
        store.exhaustive = false

        // Home hears from the server before the stream proves itself: the
        // watchdog runs at the running cadence (3 s; jitter is 0 at 3 s).
        store.send(Action.RequestsConnectionChanged(true))
        clock.advance(4.seconds)
        store.receive(Action.RefreshRequested::class)
        store.receive(Action.RefreshResponse::class)
        assertEquals(1, reads.value)
        clock.advance(4.seconds)
        store.receive(Action.RefreshRequested::class)
        store.receive(Action.RefreshResponse::class)
        assertEquals(2, reads.value)
        h.skipInFlightEffects()
    }

    @Test fun pastedAttachmentCanBeAddedAndRemoved() = sessionTest { h ->
        val attachment = OpenCodeOutgoingAttachment(
            id = "00000000-0000-0000-0000-000000000001",
            data = byteArrayOf(1, 2, 3),
            mime = "image/jpeg",
        )
        val store = h.store(h.makeState(session = testSession(id = "ses_paste", title = "Paste test")))

        store.send(Action.attachmentAdded(attachment)) { it.withPendingAttachments(listOf(attachment)) }
        store.send(Action.attachmentRemoved(0)) { it.withPendingAttachments(emptyList()) }
    }

    @Test fun requestFailureWaitsForReconnectRetryBeforeBecomingVisible() = sessionTest { h ->
        val session = testSession(id = "ses_requests", title = "Requests")
        val error = OpenCodeClientError("Could not reach the OpenCode server")
        val state = h.makeState(session = session).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        val clock = TestClock()
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(requestRetry = true)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, _ -> throw error },
                pendingInputs = { _, _ -> throw error },
            )
        }

        store.send(Action.PendingRequestsResponse(sessionID = session.id, result = Result.failure(error))) {
            it.withRequestLoadAttempt(null).withRequestFailureAwaitingReconnect(true)
        }
        assertNull(store.state.settledRequestLoadError)

        // One automatic retry while still connected; only its failure is settled.
        // Let the reducer effect register its injected TestClock sleep before the
        // clock advances; this keeps the assertion independent of suite load.
        settle()
        clock.advance(2.seconds)
        store.receive(Action.requestsAutoRetry) {
            it.withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Reconnect).copy(pendingWorkPollSeconds = 2)
        }
        assertReconnectFailureSettlesAndClearsOnDisconnect(store, sessionID = session.id, error = error)
    }

    @Test fun disconnectCancelsThePendingAutomaticRetry() = sessionTest { h ->
        val session = testSession(id = "ses_requests", title = "Requests")
        val error = OpenCodeClientError("Could not reach the OpenCode server")
        val state = h.makeState(session = session).withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Initial)
        val clock = TestClock()
        val store = h.store(state) {
            this[Clock] = clock
            this[SessionTimers] = SessionTimers.testValue.copy(requestRetry = true)
        }

        store.send(Action.PendingRequestsResponse(sessionID = session.id, result = Result.failure(error))) {
            it.withRequestLoadAttempt(null).withRequestFailureAwaitingReconnect(true)
        }
        store.send(Action.RequestsConnectionChanged(false)) { it.withRequestsConnectionIsAvailable(false) }
        assertNull(store.state.settledRequestLoadError)
        store.finish()
    }

    @Test fun failedReconnectRetryBecomesVisibleOnlyAfterItSettles() = sessionTest { h ->
        val session = testSession(id = "ses_requests", title = "Requests")
        val error = OpenCodeClientError("Could not reach the OpenCode server")
        val state = h.makeState(session = session).withRequestsConnectionIsAvailable(false).withRequestFailureAwaitingReconnect(true)
        val clock = TestClock()
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, _, _ -> throw error },
                pendingInputs = { _, _ -> throw error },
            )
        }

        store.send(Action.RequestsConnectionChanged(true)) {
            it.withRequestsConnectionIsAvailable(true)
                .withRequestLoadAttempt(RequestDockFeature.State.LoadAttempt.Reconnect)
                .copy(pendingWorkPollSeconds = 2)
        }
        assertReconnectFailureSettlesAndClearsOnDisconnect(store, sessionID = session.id, error = error)
    }

    private suspend fun assertReconnectFailureSettlesAndClearsOnDisconnect(
        store: TestStore<State, Action>,
        sessionID: String,
        error: OpenCodeClientError,
    ) {
        assertNull(store.state.settledRequestLoadError)
        store.receive(Action.PendingRequestsResponse(sessionID = sessionID, result = Result.failure(error))) {
            it.withRequestLoadAttempt(null).withRequestLoadError(error.message)
        }
        assertEquals(error.message, store.state.settledRequestLoadError)
        store.receive(Action.PendingInputsRequested(sessionID = sessionID))
        store.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false).withRequestLoadError(null).copy(pendingWorkPollSeconds = null)
        }
        assertNull(store.state.settledRequestLoadError)
        // The poll loop is cancelled by the disconnect; give the sleeping task a beat to unwind.
        store.finish(timeout = 2.seconds)
    }

    @Test fun laterRequestSnapshotClearsSettledFailure() = sessionTest { h ->
        val session = testSession(id = "ses_requests", title = "Requests")
        val state = h.makeState(session = session)
            .withRequestFailureAwaitingReconnect(true)
            .withRequestLoadError("Could not reach the OpenCode server")
        val store = h.store(state)

        store.send(Action.PendingRequestsResponse(sessionID = session.id, result = Result.success(OpenCodePendingRequests(permissions = emptyList())))) {
            it.withRequestFailureAwaitingReconnect(false).withRequestLoadError(null).withRequestArrivalsAreLive(true)
        }
        assertNull(store.state.settledRequestLoadError)
    }

    @Test fun initialContentOwnsTheCriticalLaneUntilItsFirstFrame() = sessionTest { h ->
        val session = testSession(id = "ses_critical", title = "Critical")
        val calls = LockIsolated(listOf<String>())
        val state = h.state {
            State.session(
                server = testServer,
                session = session,
                cachedItems = listOf(OpenCodeTranscriptItem.UserText(id = "m1", text = "Ready")),
                initialTranscriptRequestIsExternal = true,
            )
        }
        val store = h.store(state) {
            this[DateClient] = DateClient.constant(date(100))
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ -> calls.append("models"); OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                sessionFamily = { _, root -> calls.append("family"); OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet()) },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> calls.append("requests"); OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> calls.append("inbox"); emptyList() },
                getSession = { _, _ -> calls.append("metadata"); session },
                transcript = { _, _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.Task)
        store.send(Action.VisibilityChanged(true))
        settle()
        assertTrue(calls.value.toString(), calls.value.isEmpty())

        store.send(Action.FirstContentFrameRendered)
        // This TestStore reduces effect actions when asked.
        repeat(10) {
            if (calls.value.size < 5) {
                store.skipReceivedActions()
                settle()
            }
        }
        assertTrue(calls.value.toString(), calls.value.toSet().containsAll(listOf("models", "family", "requests", "inbox", "metadata")))
        h.skipInFlightEffects()
    }
}

/** The flags change nothing the screen shows, but the app's `Store` must still keep them. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class SessionFeatureStoreTests {
    @get:org.junit.Rule val isolation = ai.opencode.core.testing.TestIsolationRule()

    @Test fun aBurstOfSubagentsQueuesOneFamilyReadInTheAppStore() {
        val started = java.util.concurrent.atomic.AtomicInteger(0)
        val cancelled = java.util.concurrent.atomic.AtomicInteger(0)
        val values = ai.opencode.core.dependencies.DependencyValues.testing().update {
            this[OpenCodeClient] = this[OpenCodeClient].copy(sessionFamily = { _, _ ->
                started.incrementAndGet()
                try {
                    kotlinx.coroutines.awaitCancellation()
                } catch (error: CancellationException) {
                    cancelled.incrementAndGet()
                    throw error
                }
            })
        }
        ai.opencode.core.dependencies.DependencyValues.withValues(values) {
            val root = testSession(id = "root", title = "Main")
            val state = State.session(testServer, root).copy(familyRootID = root.id, familySessions = listOf(root))
            val store = ai.opencode.core.store.Store(state, SessionFeature(), dependencies = values)
            try {
                store.send(Action.FamilyRefreshRequested)
                store.send(Action.FamilyRefreshRequested)
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertEquals(1, started.get())
                assertEquals(0, cancelled.get())
                assertTrue(store.state.familyRead.isQueued)
            } finally {
                store.close()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
        }
    }
}
