package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.feature.composer.ComposerQuote
import ai.opencode.feature.composer.DraftState
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionFeature.State
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.Gate
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertEquals
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertFalse
import ai.opencode.feature.session.SessionFeatureTestsSendingSupport.assertTrue
import ai.opencode.feature.session.SessionTestSupport.HapticsRecorder
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.date
import ai.opencode.feature.session.SessionTestSupport.increment
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testSession
import ai.opencode.feature.transcript.markdown.MarkdownQuote
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeContextReading
import ai.opencode.server.model.OpenCodeFormField
import ai.opencode.server.model.OpenCodeFormFieldType
import ai.opencode.server.model.OpenCodeFormRequest
import ai.opencode.server.model.OpenCodeFormValue
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeOutgoingAttachment
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import org.junit.Test
import java.util.UUID

class SessionFeatureTestsRendering {
    private fun user(id: String, text: String) = OpenCodeTranscriptItem.UserText(id = id, text = text)
    private fun assistant(id: String, text: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = text)
    private fun info(id: String, text: String) = OpenCodeTranscriptItem.Info(id = id, text = text)

    private fun toolCall(name: String) = OpenCodeToolCall(
        callID = name, name = name, status = OpenCodeToolCall.Status.Completed, input = OpenCodeJSONValue.Null, output = emptyList(), metadata = emptyMap(),
    )

    @Test
    fun restPageDuringStreamPreservesUnsettledAssistantRowsWithoutDuplicatingEchoes() {
        val user = user("msg_user", "First prompt")
        val liveText = assistant("msg_reply-text-0", "Still typing")
        val liveTool = OpenCodeTranscriptItem.Tool(id = "msg_reply-tool-call", call = toolCall("shell"))
        val existing = listOf(user, liveText, liveTool)

        // A queued second prompt can trigger a page read before these ephemeral
        // parts have durable REST values. Neither the answer nor its tool vanishes.
        assertEquals(existing, SessionFeature.mergeTranscript(fetched = listOf(user), existing = existing, projectedMessageIDs = setOf("msg_reply")))

        // Once REST echoes text, it replaces the SSE part exactly once, while an
        // unpersisted tool stays projected until the message settles.
        val echoed = assistant("msg_reply-text-0", "Server text")
        assertEquals(
            listOf(user, echoed, liveTool),
            SessionFeature.mergeTranscript(fetched = listOf(user, echoed), existing = existing, projectedMessageIDs = setOf("msg_reply")),
        )

        // With no active projection, the authoritative page can remove rows.
        assertEquals(listOf<OpenCodeTranscriptItem>(user), SessionFeature.mergeTranscript(fetched = listOf(user), existing = existing))
    }

    @Test
    fun mergeRetiresEchoedOptimisticRowsAndKeepsInfoRows() {
        val existing = listOf(user("m1", "one"), info("info-a", "Run failed: aborted"), user("msg_2", "two"))
        val window = listOf<OpenCodeTranscriptItem>(user("m1", "one"), user("msg_2", "two"))

        assertEquals(
            listOf(user("m1", "one"), user("msg_2", "two"), info("info-a", "Run failed: aborted")),
            SessionFeature.mergeTranscript(fetched = window, existing = existing, optimisticPromptIDs = setOf("msg_2")),
        )
        // Until the server echoes it, the optimistic bubble survives refreshes.
        assertEquals(
            existing,
            SessionFeature.mergeTranscript(fetched = listOf(user("m1", "one")), existing = existing, optimisticPromptIDs = setOf("msg_2")),
        )
    }

    @Test
    fun mergeAppendsAWindowThatJumpedPastWhatWeHold() {
        val existing = listOf<OpenCodeTranscriptItem>(user("m1", "one"))
        val window = listOf<OpenCodeTranscriptItem>(user("m9", "nine"), user("m10", "ten"))

        assertEquals(existing + window, SessionFeature.mergeTranscript(fetched = window, existing = existing))
    }

    @Test
    fun androidDivergenceOnlyAnExplicitlyCompletePageReplacesDisjointCachedRows() = sessionTest { h ->
        val cached = user("old", "Cached")
        val session = testSession()
        for ((cursor, expected) in listOf(
            "" to listOf("old", "new"),
            ""","cursor":{"next":"older"}""" to listOf("old", "new"),
            ""","cursor":{}""" to listOf("new"),
        )) {
            val payload = """{"data":[{"id":"new","type":"user","text":"Server"}]$cursor}""".toByteArray()
            val store = h.store(h.makeState(session = session, cachedItems = listOf(cached))) {
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> session },
                    transcript = { _, _, _ -> OpenCodeClient.decodeTranscript(payload) },
                )
            }
            store.exhaustive = false
            store.send(Action.RefreshRequested(RefreshReason.Open))
            store.receive(Action.RefreshResponse::class)
            store.receive(Action.AcceptedTranscriptUpdated::class)
            assertEquals(expected, store.state.items.map { it.id })
            store.finish()
        }
    }

    @Test
    fun androidDivergenceEmptyCompletePageKeepsOnlyPendingAndProtectedRows() = sessionTest { h ->
        val stale = user("old", "Cached")
        val live = assistant("msg_reply-text-0", "Streaming")
        val pending = user("msg_pending", "Sending")
        val state = h.makeState(cachedItems = listOf(stale, live, pending), isRunning = true).copy(
            optimisticPromptIDs = setOf(pending.id), projectedMessageIDs = setOf("msg_reply"),
        )
        val refreshed = SessionFeature().acceptWindow(emptyList(), SessionFeature.AuthoritativeRefresh.PageCursor(null), state)
        assertEquals(listOf(live, pending), refreshed.items)
        assertEquals(State.EarlierHistory.Exhausted, refreshed.earlierHistory)
    }

    @Test
    fun quotingSelectionAddsQuoteCardsAndFocusesComposer() = sessionTest { h ->
        val haptics = HapticsRecorder()
        val store = h.store(h.makeState()) {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
        }
        val first = ComposerQuote(id = UUID(0, 0).toString(), text = "First line\nSecond line")

        store.send(Action.QuoteTextTapped("\nFirst line\nSecond line\n\n")) {
            it.withPendingQuotes(listOf(first)).copy(composerFocusRequest = 1)
        }
        assertEquals(listOf(Haptic.tap), haptics.log)

        store.send(Action.Binding(BindingAction.Draft("I agree with this part."))) {
            it.withDraft("I agree with this part.")
        }

        val fenced = MarkdownQuote.fencedCode("let x = 1\nreturn x", language = "swift")
        val second = ComposerQuote(id = UUID(0, 1).toString(), text = fenced)
        store.send(Action.QuoteTextTapped(fenced)) {
            it.withPendingQuotes(listOf(first, second)).copy(composerFocusRequest = 2)
        }
        assertEquals("I agree with this part.", store.state.draft)
        assertEquals(listOf(Haptic.tap, Haptic.tap), haptics.log)

        // Whitespace-only selections are a no-op and stay quiet.
        store.send(Action.QuoteTextTapped("   \n  "))
        assertEquals(listOf(Haptic.tap, Haptic.tap), haptics.log)

        store.send(Action.quoteCommented(second.id, "  Why two?  ")) {
            it.withPendingQuotes(listOf(first, second.copy(comment = "Why two?")))
        }
        store.send(Action.quoteCommented(second.id, " ")) { it.withPendingQuotes(listOf(first, second)) }
        store.send(Action.quoteRemoved(first.id)) { it.withPendingQuotes(listOf(second)) }
    }

    @Test
    fun quotingWhileRequestBarIsShowingOrAttachmentIsStagedUpdatesDraftCleanly() = sessionTest { h ->
        val haptics = HapticsRecorder()
        val form = OpenCodeFormRequest(
            id = "frm_1",
            sessionID = "ses_1",
            title = "Question",
            fields = listOf(OpenCodeFormField(key = "answer", type = OpenCodeFormFieldType.String, title = "Answer", required = true)),
        )
        val attachment = OpenCodeOutgoingAttachment(data = byteArrayOf(0x89.toByte(), 0x50), mime = "image/png")
        val initial = h.makeState().withPendingAttachments(listOf(attachment))

        val store = h.store(initial) {
            this[HapticsClient] = haptics.client
            this[UuidClient] = UuidClient.incrementing()
        }
        val staged = ComposerQuote(id = UUID(0, 0).toString(), text = "Staged note")

        // 1. Quoting while an attachment is staged keeps the attachment and adds a quote card.
        store.send(Action.QuoteTextTapped("Staged note")) {
            it.withPendingQuotes(listOf(staged)).copy(composerFocusRequest = 1)
        }
        assertEquals(listOf(attachment), store.state.pendingAttachments)
        assertEquals(listOf(Haptic.tap), haptics.log)

        // 2. Docking a request parks the message draft/attachment; quoting while the request
        // bar is showing writes into the request answer bar and syncs `formAnswers`.
        store.send(
            Action.PendingRequestsResponse(
                sessionID = "ses_1",
                result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = listOf(form))),
            ),
        ) {
            it.withFormRequests(listOf(form))
                .withFormAnswers(it.formAnswers + (form.id to emptyMap()))
                .withParkedComposer(DraftState(attachments = listOf(attachment), quotes = listOf(staged)))
                .withPendingAttachments(emptyList())
                .withPendingQuotes(emptyList())
                .withAnswerShownTarget("frm_1/answer")
                .withRequestArrivalsAreLive(true)
        }
        store.send(Action.QuoteTextTapped("Use the safe flag")) {
            it.withDraft("> Use the safe flag\n\n")
                .withAnswerBarBaseline("> Use the safe flag\n\n")
                .withFormAnswers(it.formAnswers + (form.id to mapOf("answer" to OpenCodeFormValue.Str("> Use the safe flag\n\n"))))
                .copy(composerFocusRequest = 2)
        }
        assertEquals(listOf(staged), store.state.parkedComposer?.quotes)
        assertEquals(listOf(attachment), store.state.parkedComposer?.attachments)
        assertEquals(listOf(Haptic.tap, Haptic.attention, Haptic.tap), haptics.log)
    }

    private val now = date(1_800_000_000)
    private fun root() = testSession(id = "ses_root", title = "Root", directory = "/work/root", updatedAt = now)
    private fun child(root: OpenCodeSession) =
        testSession(id = "ses_child", title = "Subagent", directory = "/work/root", updatedAt = now, parentID = root.id)
    private fun staleShell(root: OpenCodeSession) =
        OpenCodeShell(id = "sh_stale", command = "bun run dev", sessionID = root.id, isUserCommand = true, isListed = false)

    private fun pageRead(session: OpenCodeSession, requestID: Int, items: List<OpenCodeTranscriptItem>) = Action.RefreshResponse(
        sessionID = session.id,
        requestID = requestID,
        result = Result.success(
            SessionFeature.AuthoritativeRefresh(
                observed = session,
                items = items,
                scope = SessionFeature.AuthoritativeRefresh.Scope.Page,
                context = OpenCodeContextReading.Inconclusive,
                observedIsRead = true,
            ),
        ),
    )

    private fun familyRead(sessions: List<OpenCodeSession>, active: Set<String> = emptySet()) =
        Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = sessions, activeSessionIDs = active)))

    /** A stub that waits until cancelled, recording that it was. */
    private suspend fun hangUntilCancelled(started: Gate, cancelled: LockIsolated<Boolean>): Nothing {
        started.open()
        try {
            awaitCancellation()
        } finally {
            cancelled.setValue(true)
        }
    }

    /**
     * Resume and watchdog reconciliation re-read the open session's subagent
     * family and running shells so stale running pills clear after a server
     * restart or missed exit event, and leaving the session cancels them.
     */
    @Test
    fun resumeAndWatchdogReconcileStaleSubagentsAndShellsAndCancelOnLeave() {
        for (reason in listOf<RefreshReason>(RefreshReason.Resume, RefreshReason.Watchdog)) sessionTest { h ->
            val root = root()
            val child = child(root)
            val staleShell = staleShell(root)
            val initialItems = listOf<OpenCodeTranscriptItem>(user("msg_1", "Run task"))

            val initial = h.makeState(session = root, cachedItems = initialItems).copy(
                hasRenderedFirstContentFrame = true,
                initialTranscriptIsFresh = true,
                isVisible = true,
                pendingWorkPollSeconds = 12,
                familySessions = listOf(root, child),
                activeFamilySessionIDs = setOf(child.id),
                shells = listOf(staleShell),
            )

            val familyRequests = LockIsolated<List<String>>(emptyList())
            val shellDirectories = LockIsolated<List<String>>(emptyList())

            val store = h.store(initial) {
                this[DateClient] = DateClient.constant(now)
                this[Clock] = TestClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> root },
                    transcript = { _, _, _ -> initialItems },
                    sessionFamily = { _, observedRoot ->
                        familyRequests.append(observedRoot.id)
                        OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = emptySet())
                    },
                    runningShells = { _, directory ->
                        shellDirectories.append(directory)
                        emptyList()
                    },
                )
            }

            assertEquals(listOf(child.id), store.state.activeSubagents.map { it.id })
            assertEquals(listOf(staleShell.id), store.state.backgroundShells.map { it.id })
            assertTrue(store.state.hasBackgroundWork)

            store.send(Action.RefreshRequested(reason)) {
                var next = it.copy(
                    nextRefreshID = 1,
                    refresh = State.Refresh(id = 1, reason = reason),
                    restoredShellIDs = setOf(staleShell.id),
                    familyRead = CoalescedRead(isInFlight = true),
                )
                if (reason == RefreshReason.Resume) next = next.copy(resync = State.ResyncState(refreshID = 1))
                next
            }
            if (reason == RefreshReason.Resume) {
                // The reads answer in launch order.
                store.receive(familyRead(listOf(root, child))) { it.copy(familyReadAt = now, familyRead = CoalescedRead(), activeFamilySessionIDs = emptySet()) }
                store.receive(Action.ShellsResponse(sessionID = root.id, result = Result.success(emptyList()))) {
                    it.copy(shells = emptyList(), restoredShellIDs = emptySet())
                }
                store.receive(Action.JobsResponse(sessionID = root.id, jobs = emptyList()))
                store.receive(pageRead(root, 1, initialItems)) { it.copy(refresh = null, resync = null) }
                store.receive(Action.AcceptedTranscriptUpdated(sessionID = root.id, items = initialItems))
            } else {
                store.receive(familyRead(listOf(root, child))) { it.copy(familyReadAt = now, familyRead = CoalescedRead(), activeFamilySessionIDs = emptySet()) }
                store.receive(Action.ShellsResponse(sessionID = root.id, result = Result.success(emptyList()))) {
                    it.copy(shells = emptyList(), restoredShellIDs = emptySet())
                }
                store.receive(pageRead(root, 1, initialItems)) { it.copy(refresh = null) }
                store.receive(Action.AcceptedTranscriptUpdated(sessionID = root.id, items = initialItems))
            }

            assertTrue(store.state.activeSubagents.isEmpty())
            assertTrue(store.state.backgroundShells.isEmpty())
            assertFalse(store.state.hasBackgroundWork)
            assertEquals(listOf(root.id), familyRequests.value)
            assertEquals(listOf(root.directory), shellDirectories.value)

            // Cancellation: leaving the session while a resume reconciliation is in
            // flight cancels family, shells, and transcript reads.
            val familyStarted = Gate()
            val shellsStarted = Gate()
            val transcriptStarted = Gate()
            val familyCancelled = LockIsolated(false)
            val shellsCancelled = LockIsolated(false)
            val transcriptCancelled = LockIsolated(false)
            val jobsStarted = Gate()
            val jobsCancelled = LockIsolated(false)

            val cancelStore = h.store(initial.copy(isVisible = true)) {
                this[DateClient] = DateClient.constant(now)
                this[Clock] = TestClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> root },
                    transcript = { _, _, _ -> hangUntilCancelled(transcriptStarted, transcriptCancelled) },
                    sessionFamily = { _, _ -> hangUntilCancelled(familyStarted, familyCancelled) },
                    runningShells = { _, _ -> hangUntilCancelled(shellsStarted, shellsCancelled) },
                    runningJobs = { _, _ -> hangUntilCancelled(jobsStarted, jobsCancelled) },
                )
            }

            cancelStore.send(Action.RefreshRequested(RefreshReason.Resume)) {
                it.copy(
                    nextRefreshID = 1,
                    refresh = State.Refresh(id = 1, reason = RefreshReason.Resume),
                    resync = State.ResyncState(refreshID = 1),
                    restoredShellIDs = setOf(staleShell.id),
                    familyRead = CoalescedRead(isInFlight = true),
                )
            }
            familyStarted.await()
            shellsStarted.await()
            jobsStarted.await()
            transcriptStarted.await()

            cancelStore.send(Action.VisibilityChanged(false)) {
                it.copy(isVisible = false, newMessagesBaseline = setOf("msg_1"), refresh = null, resync = null, familyRead = CoalescedRead())
            }
            cancelStore.finish()
            assertTrue(familyCancelled.value)
            assertTrue(shellsCancelled.value)
            assertTrue(jobsCancelled.value)
            assertTrue(transcriptCancelled.value)
        }
    }

    @Test
    fun watchdogTicksMakeZeroFamilyShellOrPendingWorkRequestsWhenIdleOrInvisible() = sessionTest { h ->
        val root = root()
        val child = child(root)
        val staleShell = staleShell(root)
        val initialItems = listOf<OpenCodeTranscriptItem>(user("msg_1", "Hello"))

        val familyCalls = LockIsolated(0)
        val shellCalls = LockIsolated(0)
        val pendingRequestCalls = LockIsolated(0)
        val inboxCalls = LockIsolated(0)

        // Finished subagents remain in familySessions for the life of the session;
        // an idle session with a finished subagent must still make zero watchdog
        // family, shell, or pending-work requests.
        val idleVisible = h.makeState(session = root, cachedItems = initialItems).copy(
            hasRenderedFirstContentFrame = true,
            initialTranscriptIsFresh = true,
            isVisible = true,
            pendingWorkPollSeconds = 12,
            familySessions = listOf(root, child),
            activeFamilySessionIDs = emptySet(),
            shells = emptyList(),
        )

        fun countingStore(state: State) = h.store(state) {
            this[DateClient] = DateClient.constant(now)
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, _ -> root },
                transcript = { _, _, _ -> initialItems },
                sessionFamily = { _, _ ->
                    familyCalls.increment()
                    OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = emptySet())
                },
                runningShells = { _, _ ->
                    shellCalls.increment()
                    emptyList()
                },
                familyPendingRequests = { _, _, _ ->
                    pendingRequestCalls.increment()
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                pendingInputs = { _, _ ->
                    inboxCalls.increment()
                    emptyList()
                },
            )
        }

        val idleStore = countingStore(idleVisible)
        assertFalse(idleStore.state.hasBackgroundWork)
        for (tick in 1..3) {
            idleStore.send(Action.RefreshRequested(RefreshReason.Watchdog)) {
                it.copy(nextRefreshID = tick, refresh = State.Refresh(id = tick, reason = RefreshReason.Watchdog))
            }
            idleStore.receive(pageRead(root, tick, initialItems)) { it.copy(refresh = null) }
            idleStore.receive(Action.AcceptedTranscriptUpdated(sessionID = root.id, items = initialItems))
        }
        idleStore.finish()
        assertEquals(0, familyCalls.value)
        assertEquals(0, shellCalls.value)
        assertEquals(0, pendingRequestCalls.value)
        assertEquals(0, inboxCalls.value)

        // Invisible session with background work: watchdog ticks still make zero
        // family, shell, or pending-work requests.
        val invisibleBusy = idleVisible.copy(
            isVisible = false,
            familySessions = listOf(root, child),
            activeFamilySessionIDs = setOf(child.id),
            shells = listOf(staleShell),
        )
        val invisibleStore = countingStore(invisibleBusy)
        assertTrue(invisibleStore.state.hasBackgroundWork)
        for (tick in 1..2) {
            invisibleStore.send(Action.RefreshRequested(RefreshReason.Watchdog)) {
                it.copy(nextRefreshID = tick, refresh = State.Refresh(id = tick, reason = RefreshReason.Watchdog))
            }
            invisibleStore.receive(pageRead(root, tick, initialItems)) { it.copy(refresh = null) }
            invisibleStore.receive(Action.AcceptedTranscriptUpdated(sessionID = root.id, items = initialItems))
        }
        invisibleStore.finish()
        assertEquals(0, familyCalls.value)
        assertEquals(0, shellCalls.value)
        assertEquals(0, pendingRequestCalls.value)
        assertEquals(0, inboxCalls.value)
    }

    @Test
    fun familyResponseDoesNotRestartPendingWorkPollWhenFamilyIDsAreUnchanged() = sessionTest { h ->
        val root = root()
        val child = child(root)
        val grandchild = testSession(id = "ses_grandchild", title = "Grandchild", directory = "/work/root", updatedAt = now, parentID = child.id)

        val initial = h.makeState(session = root, cachedItems = listOf(user("msg_1", "Hello"))).copy(
            hasRenderedFirstContentFrame = true,
            streamIsHealthy = true,
            pendingWorkPollSeconds = 12,
            familySessions = listOf(root, child),
            activeFamilySessionIDs = setOf(child.id),
        )

        val polledFamilies = LockIsolated<List<List<String>>>(emptyList())
        val inboxCalls = LockIsolated(0)

        val store = h.store(initial) {
            this[DateClient] = DateClient.constant(now)
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                familyPendingRequests = { _, ids, _ ->
                    polledFamilies.append(ids)
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                pendingInputs = { _, _ ->
                    inboxCalls.increment()
                    emptyList()
                },
            )
        }

        store.send(familyRead(listOf(root, child), setOf(child.id))) { it.copy(familyReadAt = now) }
        // Failed family read while poll is already running: also keeps the running poll untouched.
        store.send(Action.FamilyResponse(Result.failure(OpenCodeClientError(message = "offline"))))
        assertTrue(polledFamilies.value.isEmpty())
        assertEquals(0, inboxCalls.value)

        // When a new subagent joins the family, the poll restarts so its requests are watched.
        store.send(familyRead(listOf(root, child, grandchild), setOf(grandchild.id))) {
            it.copy(familySessions = listOf(root, child, grandchild), activeFamilySessionIDs = setOf(grandchild.id))
        }
        store.receive(
            Action.PendingRequestsResponse(
                sessionID = root.id,
                result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())),
            ),
        ) { it.withRequestArrivalsAreLive(true) }
        store.receive(Action.PendingInputsRequested(sessionID = root.id))
        store.receive(Action.JobsResponse(sessionID = root.id, jobs = emptyList()))
        store.receive(Action.PendingInputsResponse(sessionID = root.id, inputs = emptyList()))
        assertEquals(listOf(listOf(root.id, child.id, grandchild.id)), polledFamilies.value)
        assertEquals(1, inboxCalls.value)

        store.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false)
                .copy(streamIsHealthy = false, pendingWorkPollSeconds = null)
                .withRequestFailureAwaitingReconnect(true)
                .withRequestArrivalsAreLive(false)
        }
        store.finish()
    }

    @Test
    fun watchdogClearsStaleSingleSessionExternallyActivePill() {
        for (includeStaleActiveRootInFamily in listOf(false, true)) sessionTest { h ->
            val root = root()
            val initialItems = listOf<OpenCodeTranscriptItem>(user("msg_1", "Hello"))

            val initial = h.makeState(session = root, cachedItems = initialItems).copy(
                hasRenderedFirstContentFrame = true,
                initialTranscriptIsFresh = true,
                isVisible = true,
                pendingWorkPollSeconds = 12,
                isExternallyActive = true,
                familySessions = listOf(root),
                activeFamilySessionIDs = if (includeStaleActiveRootInFamily) setOf(root.id) else emptySet(),
            )

            val familyCalls = LockIsolated(0)
            val shellCalls = LockIsolated(0)

            val store = h.store(initial) {
                this[DateClient] = DateClient.constant(now)
                this[Clock] = TestClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    getSession = { _, _ -> root },
                    transcript = { _, _, _ -> initialItems },
                    sessionFamily = { _, _ ->
                        familyCalls.increment()
                        OpenCodeSessionFamily(sessions = listOf(root), activeSessionIDs = emptySet())
                    },
                    runningShells = { _, _ ->
                        shellCalls.increment()
                        emptyList()
                    },
                )
            }

            assertFalse(store.state.hasBackgroundWork)
            assertTrue(store.state.isRunning)

            store.send(Action.RefreshRequested(RefreshReason.Watchdog)) {
                it.copy(nextRefreshID = 1, refresh = State.Refresh(id = 1, reason = RefreshReason.Watchdog), familyRead = CoalescedRead(isInFlight = true))
            }
            store.receive(familyRead(listOf(root))) { it.copy(familyReadAt = now, familyRead = CoalescedRead(), activeFamilySessionIDs = emptySet()) }
            store.receive(Action.ExternalRunStateChanged(isRunning = false)) {
                it.copy(
                    isExternallyActive = false,
                    refresh = it.refresh?.copy(followup = RefreshReason.RunState),
                    runWorkGeneration = 1,
                )
            }
            store.receive(Action.RefreshRequested(RefreshReason.RunState))
            store.receive(Action.ShellsResponse(sessionID = root.id, result = Result.success(emptyList())))
            store.receive(pageRead(root, 1, initialItems)) { it.copy(refresh = null) }
            store.receive(Action.AcceptedTranscriptUpdated(sessionID = root.id, items = initialItems))
            store.receive(Action.RefreshRequested(RefreshReason.RunState)) {
                it.copy(nextRefreshID = 2, refresh = State.Refresh(id = 2, reason = RefreshReason.RunState))
            }
            store.receive(pageRead(root, 2, initialItems)) { it.copy(refresh = null) }
            store.receive(Action.AcceptedTranscriptUpdated(sessionID = root.id, items = initialItems))
            store.finish()

            assertFalse(store.state.isExternallyActive)
            assertFalse(store.state.isRunning)
            assertTrue(store.state.activeFamilySessionIDs.isEmpty())
            assertEquals(1, familyCalls.value)
            assertEquals(1, shellCalls.value)
        }
    }

    @Test
    fun resumeDuringInFlightRefreshStillReconcilesSubagentsAndShells() {
        for (inFlightFollowupIsRunState in listOf(true, false)) sessionTest { h ->
            val root = root()
            val child = child(root)
            val staleShell = staleShell(root)
            val initialItems = listOf<OpenCodeTranscriptItem>(user("msg_1", "Hello"))

            val initial = h.makeState(session = root, cachedItems = initialItems).copy(
                hasRenderedFirstContentFrame = true,
                initialTranscriptIsFresh = true,
                isVisible = true,
                pendingWorkPollSeconds = 12,
                nextRefreshID = 1,
                refresh = if (inFlightFollowupIsRunState) {
                    State.Refresh(id = 1, reason = RefreshReason.Invalidation, followup = RefreshReason.RunState)
                } else {
                    State.Refresh(id = 1, reason = RefreshReason.Settlement(messageID = "msg_1"))
                },
                familySessions = listOf(root, child),
                activeFamilySessionIDs = setOf(child.id),
                shells = listOf(staleShell),
            )

            val familyCalls = LockIsolated(0)
            val shellCalls = LockIsolated(0)

            val store = h.store(initial) {
                this[DateClient] = DateClient.constant(now)
                this[Clock] = TestClock()
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    sessionFamily = { _, _ ->
                        familyCalls.increment()
                        OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = emptySet())
                    },
                    runningShells = { _, _ ->
                        shellCalls.increment()
                        emptyList()
                    },
                )
            }

            store.send(Action.RefreshRequested(RefreshReason.Resume)) {
                var next = it.copy(restoredShellIDs = setOf(staleShell.id), familyRead = CoalescedRead(isInFlight = true))
                if (!inFlightFollowupIsRunState) next = next.copy(refresh = next.refresh?.copy(followup = RefreshReason.Resume))
                next
            }
            store.receive(familyRead(listOf(root, child))) { it.copy(familyReadAt = now, familyRead = CoalescedRead(), activeFamilySessionIDs = emptySet()) }
            store.receive(Action.ShellsResponse(sessionID = root.id, result = Result.success(emptyList()))) {
                it.copy(shells = emptyList(), restoredShellIDs = emptySet())
            }
            store.receive(Action.JobsResponse(sessionID = root.id, jobs = emptyList()))
            if (!inFlightFollowupIsRunState) {
                // Even if a later .runState overwrites the settlement's followup before
                // the settlement finishes, the .resume background refresh already ran.
                store.send(Action.RefreshRequested(RefreshReason.RunState)) {
                    it.copy(refresh = it.refresh?.copy(followup = RefreshReason.RunState))
                }
            }
            store.finish()

            assertTrue(store.state.activeSubagents.isEmpty())
            assertTrue(store.state.backgroundShells.isEmpty())
            assertEquals(1, familyCalls.value)
            assertEquals(1, shellCalls.value)
        }
    }

    @Test
    fun becomingVisibleAfterInvisibleReconnectReconcilesAndStartsPendingWorkPoll() = sessionTest { h ->
        val root = root()
        val child = child(root)
        val staleShell = staleShell(root)
        val initialItems = listOf<OpenCodeTranscriptItem>(user("msg_1", "Hello"))

        val initial = h.makeState(session = root, cachedItems = initialItems).copy(
            hasRenderedFirstContentFrame = true,
            initialTranscriptIsFresh = false,
            isVisible = false,
            streamIsHealthy = true,
            pendingWorkPollSeconds = null,
            nextRefreshID = 1,
            refresh = State.Refresh(id = 1, reason = RefreshReason.Reconnect),
            familySessions = listOf(root, child),
            activeFamilySessionIDs = setOf(child.id),
            shells = listOf(staleShell),
        ).withRequestsConnectionIsAvailable(true)

        val familyCalls = LockIsolated(0)
        val shellCalls = LockIsolated(0)
        val pendingRequestCalls = LockIsolated(0)
        val inboxCalls = LockIsolated(0)

        val store = h.store(initial) {
            this[DateClient] = DateClient.constant(now)
            this[Clock] = TestClock()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                sessionFamily = { _, _ ->
                    familyCalls.increment()
                    OpenCodeSessionFamily(sessions = listOf(root, child), activeSessionIDs = emptySet())
                },
                runningShells = { _, _ ->
                    shellCalls.increment()
                    emptyList()
                },
                familyPendingRequests = { _, _, _ ->
                    pendingRequestCalls.increment()
                    OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())
                },
                pendingInputs = { _, _ ->
                    inboxCalls.increment()
                    emptyList()
                },
            )
        }

        store.send(Action.VisibilityChanged(true)) {
            it.copy(
                isVisible = true,
                resync = State.ResyncState(refreshID = 1),
                restoredShellIDs = setOf(staleShell.id),
                familyRead = CoalescedRead(isInFlight = true),
            )
        }
        store.receive(familyRead(listOf(root, child))) {
            it.copy(familyReadAt = now, familyRead = CoalescedRead(), activeFamilySessionIDs = emptySet(), pendingWorkPollSeconds = 60)
        }
        store.receive(Action.ShellsResponse(sessionID = root.id, result = Result.success(emptyList()))) {
            it.copy(shells = emptyList(), restoredShellIDs = emptySet())
        }
        store.receive(Action.JobsResponse(sessionID = root.id, jobs = emptyList()))
        store.receive(
            Action.PendingRequestsResponse(
                sessionID = root.id,
                result = Result.success(OpenCodePendingRequests(permissions = emptyList(), forms = emptyList())),
            ),
        ) { it.withRequestArrivalsAreLive(true) }
        store.receive(Action.PendingInputsRequested(sessionID = root.id))
        store.receive(Action.PendingInputsResponse(sessionID = root.id, inputs = emptyList()))

        assertTrue(store.state.activeSubagents.isEmpty())
        assertTrue(store.state.backgroundShells.isEmpty())
        assertEquals(60, store.state.pendingWorkPollSeconds)
        assertEquals(1, familyCalls.value)
        assertEquals(1, shellCalls.value)
        assertEquals(1, pendingRequestCalls.value)
        assertEquals(1, inboxCalls.value)

        store.send(Action.VisibilityChanged(false)) {
            it.copy(isVisible = false, newMessagesBaseline = setOf("msg_1"), refresh = null, resync = null)
        }
        store.send(Action.RequestsConnectionChanged(false)) {
            it.withRequestsConnectionIsAvailable(false)
                .copy(streamIsHealthy = false, pendingWorkPollSeconds = null)
                .withRequestFailureAwaitingReconnect(true)
                .withRequestArrivalsAreLive(false)
        }
        store.finish()
    }
}
