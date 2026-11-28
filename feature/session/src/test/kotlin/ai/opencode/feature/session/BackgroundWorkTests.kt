package ai.opencode.feature.session

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.dependencies.PasteboardClient
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.UuidClient
import ai.opencode.core.store.TestStore
import ai.opencode.feature.session.SessionFeature.Action
import ai.opencode.feature.session.SessionTestSupport.Harness
import ai.opencode.feature.session.SessionTestSupport.LockIsolated
import ai.opencode.feature.session.SessionTestSupport.append
import ai.opencode.feature.session.SessionTestSupport.receive
import ai.opencode.feature.session.SessionTestSupport.sessionTest
import ai.opencode.feature.session.SessionTestSupport.testServer
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeDelivery
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodeJob
import ai.opencode.server.model.OpenCodeModelCatalog
import ai.opencode.server.model.OpenCodePendingInput
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeSessionFamily
import ai.opencode.server.model.OpenCodeShell
import ai.opencode.server.model.OpenCodeShellOutputPage
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val server = testServer
private val session = OpenCodeSession(id = "ses_bg", serverTitle = "Background", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH)

private fun runningTool(
    key: String = "msg_a-tool-call_1",
    name: String = "shell",
    status: OpenCodeToolCall.Status = OpenCodeToolCall.Status.Running,
    startedAt: Instant? = null,
): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
    id = key,
    call = OpenCodeToolCall(
        callID = "call_1", messageID = "msg_a", name = name, status = status,
        input = OpenCodeJSONValue.Object(mapOf("command" to OpenCodeJSONValue.Str("bun run build --watch"))), output = emptyList(), error = null,
        metadata = mapOf("shellID" to OpenCodeJSONValue.Str("sh_1")), startedAt = startedAt,
    ),
)

/** A shell's capture file: ASCII bytes the mock pages like the server does. */
private class Capture {
    val bytes = LockIsolated(ByteArray(0))
    val status = LockIsolated(OpenCodeShell.Status.Running)
    val reads = LockIsolated(listOf<Pair<Int, Int>>())
    val stops = LockIsolated(0)

    fun append(text: String) = bytes.update { it + text.toByteArray() }

    fun page(cursor: Int, limit: Int): OpenCodeShellOutputPage {
        reads.append(cursor to limit)
        val all = bytes.value
        if (cursor >= all.size) return OpenCodeShellOutputPage(output = "", cursor = all.size, size = all.size)
        val end = minOf(all.size, cursor + limit)
        return OpenCodeShellOutputPage(output = String(all, cursor, end - cursor), cursor = end, size = all.size)
    }

    fun shell() = OpenCodeShell(
        id = "sh_1", command = "bun dev", sessionID = "ses_bg", status = status.value,
        exitCode = if (status.value == OpenCodeShell.Status.Exited) 0 else null,
    )

    fun install(client: OpenCodeClient): OpenCodeClient = client.copy(
        shellOutput = { _, directory, id, cursor, limit ->
            assertTrue(directory == "/project" && id == "sh_1")
            page(cursor, limit)
        },
        shell = { _, _, _ -> shell() },
        stopShell = { _, _, _ -> stops.update { it + 1 } },
    )
}

private fun outputState(status: OpenCodeShell.Status = OpenCodeShell.Status.Running) = ShellOutputFeature.State(
    server = server, directory = "/project",
    shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, status = status),
)

private fun Harness.state(
    cachedItems: List<OpenCodeTranscriptItem> = emptyList(),
    isRunning: Boolean = false,
): SessionFeature.State = state { SessionFeature.State.session(server, session, cachedItems = cachedItems, isRunning = isRunning) }

private fun Harness.outputStore(
    state: ShellOutputFeature.State,
    dependencies: ai.opencode.core.dependencies.DependencyValues.Builder.() -> Unit = {},
): TestStore<ShellOutputFeature.State, ShellOutputFeature.Action> = store(state, { ShellOutputFeature() }, dependencies)

/** Delivers a REST window, the way a refresh changes the transcript. */
private suspend fun show(
    items: List<OpenCodeTranscriptItem>,
    store: TestStore<SessionFeature.State, Action>,
    assert: ((SessionFeature.State) -> SessionFeature.State)? = null,
) {
    store.send(Action.TranscriptResponse(session.id, Result.success(items))) {
        val next = it.copy(items = items).syncShellToolStatuses()
        assert?.invoke(next) ?: next
    }
    store.receive(Action.AcceptedTranscriptUpdated(session.id, items))
}

private fun draft(text: String): Action = Action.Binding(BindingAction.Draft(text))

/** Non-exhaustive receives of actions whose effects race. */
private suspend fun TestStore<SessionFeature.State, Action>.receiveInAnyOrder(vararg cases: kotlin.reflect.KClass<out Action>) {
    val remaining = cases.toMutableList()
    while (remaining.isNotEmpty()) {
        var matched: kotlin.reflect.KClass<out Action>? = null
        receive({ action -> remaining.firstOrNull { it.isInstance(action) }?.also { matched = it } != null }, remaining.joinToString { it.simpleName ?: "" }, 5.seconds)
        remaining.remove(matched)
    }
}

private val missing = OpenCodeClientError(message = "Missing", statusCode = 404)

/** Move to Background, the shell output sheet and background jobs. */
class BackgroundWorkTests {
    // region Move to Background

    /**
     * Hidden for now (`MoveToBackground.showsManualAction`): blocking work
     * arms no hint, nothing offers the move, and a steer still moves it.
     */
    @Test fun manualMoveToBackgroundIsHiddenByDefault() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state().copy(isExternallyActive = true)
        assertFalse(state.offersManualMoveToBackground)
        val calls = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = clock
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, text, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}:$text") },
                runningShells = { _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        show(listOf(runningTool()), store)
        assertEquals("msg_a-tool-call_1", store.state.foregroundWork?.key)
        clock.advance(10.seconds)
        assertNull(store.state.movableWorkKey)
        assertFalse(store.state.showsMoveToBackground)
        store.send(Action.MoveToBackgroundTapped)
        assertTrue(calls.value.isEmpty())

        store.exhaustive = false
        store.send(draft("Check the failing snapshot"))
        store.send(Action.SendTapped)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", null, isAutomatic = true))
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("background:ses_bg", "prompt:ses_bg:steer:Check the failing snapshot"), calls.value)
    }

    @Test fun cachedBlockingWorkArmsItsHintOnActivation() = sessionTest { h ->
        val clock = TestClock()
        val now = Instant.ofEpochSecond(100)
        val state = h.state(cachedItems = listOf(runningTool(startedAt = now.minusMillis(2_500))), isRunning = true).copy(
            initialTranscriptIsFresh = true,
            offersManualMoveToBackground = true,
            familyReadAt = now,
        )
        val store = h.store(state) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(now)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                modelCatalog = { _, _ -> OpenCodeModelCatalog(models = emptyList(), defaultModel = null) },
                runningShells = { _, _ -> emptyList() },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        store.send(Action.FirstContentFrameRendered)
        clock.advance(500.milliseconds)
        store.receive(Action.BackgroundHintElapsed("msg_a-tool-call_1"))
        assertTrue(store.state.showsMoveToBackground)
    }

    @Test fun blockingShellOffersMoveToBackgroundAfterThreeSecondsAndMovesIt() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state().copy(isExternallyActive = true, offersManualMoveToBackground = true)
        val moved = LockIsolated(listOf<String>())
        val listed = OpenCodeShell(id = "sh_1", command = "bun run build --watch", sessionID = session.id, isListed = true)
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> moved.append(id) },
                runningShells = { _, directory ->
                    assertEquals("/project", directory)
                    listOf(listed)
                },
            )
        }
        val tool = runningTool()
        show(listOf(tool), store)
        assertEquals("msg_a-tool-call_1", store.state.foregroundWork?.key)
        clock.advance(2_900.milliseconds)
        assertFalse("The TUI waits three seconds before offering", store.state.showsMoveToBackground)
        clock.advance(100.milliseconds)
        store.receive(Action.BackgroundHintElapsed("msg_a-tool-call_1")) { it.copy(movableWorkKey = "msg_a-tool-call_1") }
        assertTrue(store.state.showsMoveToBackground)

        store.send(Action.MoveToBackgroundTapped) { it.copy(movedWorkKeys = setOf("msg_a-tool-call_1")) }
        assertFalse("The offer leaves as soon as it is taken", store.state.showsMoveToBackground)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", null))
        store.receive(Action.ShellsResponse::class) { it.copy(shells = listOf(listed)) }
        // A moved script outlives its call too: the jobs are listed with the shells.
        store.receive(Action.JobsResponse(session.id, emptyList()))
        assertEquals(listOf(session.id), moved.value)

        // The server settles the tool; the shell now outlives it in the capsule.
        val settled = runningTool(status = OpenCodeToolCall.Status.Completed)
        show(listOf(settled), store) { it.copy(movableWorkKey = null) }
        assertEquals(listOf(listed), store.state.backgroundShells)
        store.finish()
    }

    @Test fun serverStartTimeShortensTheWaitForAShell() = sessionTest { h ->
        val clock = TestClock()
        val now = Instant.ofEpochSecond(1_800_000_000)
        val state = h.state().copy(isExternallyActive = true, offersManualMoveToBackground = true)
        val store = h.store(state) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(now)
        }
        show(listOf(runningTool(startedAt = now.minusMillis(2_500))), store)
        clock.advance(500.milliseconds)
        store.receive(Action.BackgroundHintElapsed("msg_a-tool-call_1")) { it.copy(movableWorkKey = "msg_a-tool-call_1") }

        // A refresh that only learns the start time keeps the offer up.
        val refreshed = runningTool(startedAt = now.minusSeconds(10))
        show(listOf(refreshed), store)
        assertTrue(store.state.showsMoveToBackground)
    }

    @Test fun quickToolsAndIdleSessionsNeverOfferIt() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state().copy(isExternallyActive = true, offersManualMoveToBackground = true)
        val store = h.store(state) { this[Clock] = clock }
        val tool = runningTool()
        show(listOf(tool), store)
        clock.advance(1.seconds)
        val done = runningTool(status = OpenCodeToolCall.Status.Completed)
        show(listOf(done), store)
        clock.advance(5.seconds)

        val read = runningTool(name = "read")
        show(listOf(read), store)
        assertNull("Only shells can move", store.state.foregroundWork)

        val idle = h.state(cachedItems = listOf(runningTool())).copy(isExternallyActive = false)
        assertNull("An idle session has nothing blocking it", idle.foregroundWork)
    }

    @Test fun failedMoveRestoresTheOffer() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state(cachedItems = listOf(runningTool())).copy(
            isExternallyActive = true,
            offersManualMoveToBackground = true,
            movableWorkKey = "msg_a-tool-call_1",
        )
        val store = h.store(state) {
            this[Clock] = clock
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, _ -> throw OpenCodeClientError(message = "Not found", statusCode = 404) },
            )
        }
        store.send(Action.MoveToBackgroundTapped) { it.copy(movedWorkKeys = setOf("msg_a-tool-call_1")) }
        val error = OpenCodeClientError(message = "Not found", statusCode = 404)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", error)) {
            it.copy(
                movedWorkKeys = emptySet(),
                items = it.items + OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t move to background: Not found"),
            )
        }
        assertTrue(store.state.showsMoveToBackground)
    }

    @Test fun steeringWithBlockingShellOverThreeSecondsMovesItToBackgroundThenSteersInOrder() = sessionTest { h ->
        val clock = TestClock()
        val now = Instant.ofEpochSecond(1_800_000_000)
        val state = h.state().copy(isExternallyActive = true, offersManualMoveToBackground = true, draft = "Check the failing snapshot")
        val calls = LockIsolated(listOf<String>())
        val listed = OpenCodeShell(id = "sh_1", command = "bun run build --watch", sessionID = session.id, isListed = true)
        val store = h.store(state) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(now)
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, text, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}:$text") },
                runningShells = { _, _ -> listOf(listed) },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        show(listOf(runningTool()), store)
        clock.advance(3.seconds)
        store.receive(Action.BackgroundHintElapsed("msg_a-tool-call_1"))
        assertTrue(store.state.showsMoveToBackground)

        store.send(Action.SendTapped)
        assertFalse(store.state.showsMoveToBackground)
        assertEquals(setOf("msg_a-tool-call_1"), store.state.movedWorkKeys)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", null, isAutomatic = true))
        // The two effects race, so either order.
        store.receiveInAnyOrder(Action.ShellsResponse::class, Action.PromptAdmitted::class)
        assertEquals(listOf(listed), store.state.shells)
        assertEquals(listOf("background:ses_bg", "prompt:ses_bg:steer:Check the failing snapshot"), calls.value)
    }

    /**
     * The three seconds only gate the Working line's action: a steer moves
     * the work as soon as it runs, and only once for two steers.
     */
    @Test fun steeringBeforeTheHintMovesTheWorkOnceForTwoSteers() = sessionTest { h ->
        val clock = TestClock()
        val now = Instant.ofEpochSecond(1_800_000_000)
        val state = h.state().copy(isExternallyActive = true, draft = "Quick follow-up")
        val calls = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(now)
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, text, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}:$text") },
                runningShells = { _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        show(listOf(runningTool(startedAt = now.minusMillis(1_500))), store)
        clock.advance(1.seconds)
        assertFalse(store.state.showsMoveToBackground)

        store.send(Action.SendTapped)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", null, isAutomatic = true))
        store.receive(Action.PromptAdmitted::class)
        store.send(draft("And the header"))
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(
            listOf("background:ses_bg", "prompt:ses_bg:steer:Quick follow-up", "prompt:ses_bg:steer:And the header"),
            calls.value,
        )
        assertEquals(setOf("msg_a-tool-call_1"), store.state.movedWorkKeys)
    }

    @Test fun steeringWithNothingRunningInTheForegroundMovesNothing() = sessionTest { h ->
        val state = h.state(
            cachedItems = listOf(
                runningTool(status = OpenCodeToolCall.Status.Completed),
                runningTool("msg_a-tool-call_2", status = OpenCodeToolCall.Status.Running),
            ),
        ).copy(
            isExternallyActive = true,
            // The running one was already moved from the Working line.
            movedWorkKeys = setOf("msg_a-tool-call_2"),
            draft = "Keep going",
        )
        val calls = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = TestClock()
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, _, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}") },
                runningShells = { _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("prompt:ses_bg:steer"), calls.value)
    }

    @Test fun queuingAMessageDoesNotMoveToBackground() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state(cachedItems = listOf(runningTool())).copy(
            isExternallyActive = true,
            offersManualMoveToBackground = true,
            movableWorkKey = "msg_a-tool-call_1",
            draft = "After the watcher finishes",
        )
        val calls = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = clock
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, text, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}:$text") },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(Action.QueueTapped)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("prompt:ses_bg:queue:After the watcher finishes"), calls.value)
        assertTrue(store.state.movedWorkKeys.isEmpty())
        assertTrue(store.state.showsMoveToBackground)
    }

    @Test fun steeringWithNonShellToolDoesNotMoveToBackground() {
        for (name in listOf("read", "subagent", "task")) steeringWithNonShellToolDoesNotMoveToBackground(name)
    }

    private fun steeringWithNonShellToolDoesNotMoveToBackground(name: String) = sessionTest { h ->
        val clock = TestClock()
        val now = Instant.ofEpochSecond(1_800_000_000)
        val state = h.state(cachedItems = listOf(runningTool(name = name, startedAt = now.minusSeconds(10)))).copy(
            isExternallyActive = true,
            draft = "Also check the header",
        )
        val calls = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = clock
            this[DateClient] = DateClient.constant(now)
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, text, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}:$text") },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        assertNull("Only shells can block a steer: $name", store.state.foregroundWork)
        store.send(Action.SendTapped)
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("prompt:ses_bg:steer:Also check the header"), calls.value)
        assertTrue(store.state.movedWorkKeys.isEmpty())
    }

    @Test fun backgroundFailureStillSendsTheSteerAndKeepsTheFallback() = sessionTest { h ->
        val clock = TestClock()
        val state = h.state(cachedItems = listOf(runningTool())).copy(
            isExternallyActive = true,
            offersManualMoveToBackground = true,
            movableWorkKey = "msg_a-tool-call_1",
            draft = "Stop watching and run once",
        )
        val calls = LockIsolated(listOf<String>())
        val error = OpenCodeClientError(message = "Not found", statusCode = 404)
        val store = h.store(state) {
            this[Clock] = clock
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id ->
                    calls.append("background:$id")
                    throw error
                },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, id, text, _, _, _, delivery -> calls.append("prompt:$id:${delivery.rawValue}:$text") },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(Action.SendTapped)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", error, isAutomatic = true))
        store.receive(Action.PromptAdmitted::class)
        assertEquals(listOf("background:ses_bg", "prompt:ses_bg:steer:Stop watching and run once"), calls.value)
        assertTrue("The manual fallback stays when auto-backgrounding fails", store.state.showsMoveToBackground)
        assertEquals(listOf("Stop watching and run once"), store.state.queuedInputs.map { it.text })
        assertFalse(store.state.items.any { it is OpenCodeTranscriptItem.Info })
    }

    /**
     * A steer waiting behind another send claims the work; moved to the
     * queue before it's posted, it lets the claim go, so the Working line
     * offers the move again and nothing is moved.
     */
    @Test fun aSteerMovedToTheQueueBeforeItsPostReleasesItsClaim() = sessionTest { h ->
        val state = h.state(cachedItems = listOf(runningTool())).copy(
            isExternallyActive = true,
            offersManualMoveToBackground = true,
            movableWorkKey = "msg_a-tool-call_1",
        )
        val calls = LockIsolated(listOf<String>())
        val gate = Channel<Unit>()
        val store = h.store(state) {
            this[Clock] = TestClock()
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, _, text, _, _, _, delivery ->
                    calls.append("prompt:${delivery.rawValue}:$text")
                    if (text == "First") gate.receiveCatching()
                },
                runningShells = { _, _ -> emptyList() },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(draft("First"))
        store.send(Action.QueueTapped)
        store.send(draft("Second"))
        store.send(Action.SendTapped)
        assertEquals(setOf("msg_a-tool-call_1"), store.state.movedWorkKeys)
        assertFalse(store.state.showsMoveToBackground)
        val steer = store.state.queuedInputs.lastOrNull { it.text == "Second" }
        assertNotNull(steer)
        store.send(Action.PendingInputMoveToQueueTapped(steer?.id ?: ""))
        assertTrue(store.state.movedWorkKeys.isEmpty())
        assertTrue(store.state.autoBackgroundClaims.isEmpty())
        assertTrue("The Working line offers it again", store.state.showsMoveToBackground)

        // Send Now claims it afresh, and the steer moves it when it's posted.
        store.send(Action.PendingInputSendNowTapped(steer?.id ?: ""))
        assertEquals(mapOf((steer?.id ?: "") to "msg_a-tool-call_1"), store.state.autoBackgroundClaims)
        gate.close()
        // The first send's admission and the steer's move race to the store.
        repeat(200) { if (calls.value.size < 3) delay(10) }
        delay(50)
        store.skipReceivedActions()
        assertEquals(listOf("prompt:queue:First", "background:ses_bg", "prompt:steer:Second"), calls.value)
        assertTrue(store.state.autoBackgroundClaims.isEmpty())
        assertEquals(setOf("msg_a-tool-call_1"), store.state.movedWorkKeys)
    }

    /** A steer deleted before it's posted lets its claim go too. */
    @Test fun aSteerDeletedBeforeItsPostReleasesItsClaim() = sessionTest { h ->
        val state = h.state(cachedItems = listOf(runningTool())).copy(
            isExternallyActive = true,
            movableWorkKey = "msg_a-tool-call_1",
        )
        val gate = Channel<Unit>()
        val calls = LockIsolated(listOf<String>())
        val store = h.store(state) {
            this[Clock] = TestClock()
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                message = { _, _, _ -> throw missing },
                sendPrompt = { _, _, text, _, _, _, _ -> if (text == "First") gate.receiveCatching() },
                cancelPendingInput = { _, _, _ -> },
                pendingInputs = { _, _ -> emptyList() },
                waitForIdle = { _, _ -> awaitCancellation() },
            )
        }
        store.exhaustive = false
        store.send(draft("First"))
        store.send(Action.QueueTapped)
        store.send(draft("Second"))
        store.send(Action.SendTapped)
        assertEquals(1, store.state.autoBackgroundClaims.size)
        val steer = store.state.queuedInputs.lastOrNull { it.text == "Second" }
        assertNotNull(steer)
        store.send(Action.PendingInputCancelTapped(steer?.id ?: ""))
        assertTrue(store.state.movedWorkKeys.isEmpty())
        assertTrue(store.state.autoBackgroundClaims.isEmpty())
        assertTrue(calls.value.isEmpty())
        gate.close()
    }

    @Test fun sendNowToSteeringMovesBlockingWorkToBackgroundFirst() = sessionTest { h ->
        val clock = TestClock()
        val queued = OpenCodePendingInput(id = "msg_queued", text = "Run this now", delivery = OpenCodeDelivery.Queue)
        val state = h.state(cachedItems = listOf(runningTool())).copy(
            isExternallyActive = true,
            offersManualMoveToBackground = true,
            movableWorkKey = "msg_a-tool-call_1",
            queuedInputs = listOf(queued),
        )
        val calls = LockIsolated(listOf<String>())
        val listed = OpenCodeShell(id = "sh_1", command = "bun run build --watch", sessionID = session.id, isListed = true)
        val store = h.store(state) {
            this[Clock] = clock
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                moveToBackground = { _, id -> calls.append("background:$id") },
                setPendingInputDelivery = { _, id, inputID, delivery -> calls.append("delivery:$id:$inputID:${delivery.rawValue}") },
                runningShells = { _, _ ->
                    calls.append("shells")
                    listOf(listed)
                },
                pendingInputs = { _, _ -> listOf(OpenCodePendingInput(id = queued.id, text = queued.text, delivery = OpenCodeDelivery.Steer)) },
            )
        }
        store.exhaustive = false
        store.send(Action.PendingInputSendNowTapped(queued.id))
        assertFalse(store.state.showsMoveToBackground)
        store.receive(Action.MoveToBackgroundFinished(session.id, "msg_a-tool-call_1", null, isAutomatic = true))
        store.finish()
        // The shells re-list (racing the PATCH) once the work has moved.
        assertEquals("background:ses_bg", calls.value.first())
        assertEquals(setOf("shells", "delivery:ses_bg:msg_queued:steer"), calls.value.drop(1).toSet())
    }

    @Test fun tappingAShellOpensItsOutput() = sessionTest { h ->
        val shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, isListed = true)
        val state = h.state().copy(shells = listOf(shell))
        val store = h.store(state)
        store.send(Action.ShellOutputTapped("sh_missing"))
        store.send(Action.ShellOutputTapped("sh_1")) {
            it.copy(shellOutput = ShellOutputFeature.State(server = server, directory = "/project", shell = shell))
        }
        store.finish()
    }

    // endregion

    // region Shell output

    @Test fun tailsGrowingOutputUntilTheCommandExits() = sessionTest { h ->
        val clock = TestClock()
        val capture = Capture()
        capture.append("$ bun dev\n")
        val store = h.outputStore(outputState()) {
            this[Clock] = clock
            this[OpenCodeClient] = capture.install(this[OpenCodeClient])
        }
        store.send(ShellOutputFeature.Action.Task)
        store.receive(ShellOutputFeature.Action.TailLocated(start = 0)) { it.copy(endCursor = 0) }
        store.receive(ShellOutputFeature.Action.StatusReceived::class)
        store.receive(ShellOutputFeature.Action.OutputReceived(OpenCodeShellOutputPage(output = "$ bun dev\n", cursor = 10, size = 10))) {
            it.withOutput("$ bun dev\n").copy(endCursor = 10)
        }

        capture.append("ready on :3000\n")
        clock.advance(ShellOutputFeature.pollInterval)
        store.receive(ShellOutputFeature.Action.StatusReceived::class)
        store.receive(ShellOutputFeature.Action.OutputReceived::class) {
            it.withOutput("$ bun dev\nready on :3000\n").copy(endCursor = 25)
        }

        capture.append("bye\n")
        capture.status.setValue(OpenCodeShell.Status.Exited)
        clock.advance(ShellOutputFeature.pollInterval)
        store.receive(ShellOutputFeature.Action.StatusReceived::class) {
            it.copy(shell = it.shell.copy(status = OpenCodeShell.Status.Exited, exitCode = 0))
        }
        store.receive(ShellOutputFeature.Action.OutputReceived::class) {
            it.withOutput("$ bun dev\nready on :3000\nbye\n").copy(endCursor = 29)
        }
        // Exited and drained: the poll ends by itself.
        store.finish()
        assertFalse(store.state.isRunning)
    }

    @Test fun firstReadShowsOnlyTheTailInPages() = sessionTest { h ->
        val capture = Capture()
        capture.append("0123456789abcde\n".repeat(20_000)) // 320,000 bytes
        capture.status.setValue(OpenCodeShell.Status.Exited)
        val store = h.outputStore(outputState()) {
            this[Clock] = ImmediateClock()
            this[OpenCodeClient] = capture.install(this[OpenCodeClient])
        }
        store.exhaustive = false
        store.send(ShellOutputFeature.Action.Task)
        store.finish()
        val start = 320_000 - ShellOutputFeature.initialTailBytes
        assertEquals(start, store.state.startCursor)
        assertEquals(320_000, store.state.endCursor)
        assertEquals(ShellOutputFeature.initialTailBytes, store.state.output.toByteArray().size)
        assertTrue(store.state.hasEarlierOutput)
        // Android's route takes an Int, so the probe reads at Int.MAX_VALUE.
        assertEquals("The first read only learns the size", Int.MAX_VALUE, capture.reads.value.first().first)
        assertTrue(capture.reads.value.drop(1).all { it.second == ShellOutputFeature.pageBytes })
    }

    @Test fun pollingStopsWhenTheViewGoesAway() = sessionTest { h ->
        val clock = TestClock()
        val capture = Capture()
        capture.append("x\n")
        val store = h.outputStore(outputState()) {
            this[Clock] = clock
            this[OpenCodeClient] = capture.install(this[OpenCodeClient])
        }
        store.exhaustive = false
        store.send(ShellOutputFeature.Action.Task)
        store.receive(ShellOutputFeature.Action.OutputReceived::class)
        val reads = capture.reads.value.size
        // The view going away ends `.task`.
        store.send(ShellOutputFeature.Action.TaskEnded)
        capture.append("more\n")
        clock.advance(10.seconds)
        assertEquals(reads, capture.reads.value.size)
        assertEquals("x\n", store.state.output)
    }

    @Test fun retainedOutputDropsWholeLinesOffTheFront() = sessionTest { h ->
        val line = "a".repeat(99) + "\n"
        var state = outputState().withOutput(line.repeat(ShellOutputFeature.retainedBytes / 100))
        state = state.copy(startCursor = 5_000, endCursor = 5_000 + state.output.toByteArray().size)
        val end = state.endCursor!!
        val store = h.outputStore(state)
        store.exhaustive = false
        store.send(ShellOutputFeature.Action.OutputReceived(OpenCodeShellOutputPage(output = "b".repeat(149) + "\n", cursor = end + 150, size = end + 150)))
        assertTrue(store.state.output.toByteArray().size <= ShellOutputFeature.retainedBytes)
        assertTrue("The cut lands on a line boundary", store.state.output.startsWith(line))
        assertEquals(end + 150, store.state.startCursor + store.state.output.toByteArray().size)
        // A stale page never re-appends bytes already shown.
        val output = store.state.output
        store.send(ShellOutputFeature.Action.OutputReceived(OpenCodeShellOutputPage(output = "dup", cursor = end + 150, size = end + 150)))
        assertEquals(output, store.state.output)
    }

    @Test fun loadEarlierPrependsTheBytesBeforeTheWindow() = sessionTest { h ->
        val capture = Capture()
        capture.append("e".repeat(300_000))
        val state = outputState(status = OpenCodeShell.Status.Exited).withOutput("tail line\n").copy(startCursor = 300_000, endCursor = 300_010)
        val store = h.outputStore(state) {
            this[OpenCodeClient] = capture.install(this[OpenCodeClient])
        }
        store.exhaustive = false
        store.send(ShellOutputFeature.Action.LoadEarlierTapped) { it.copy(isLoadingEarlier = true) }
        store.receive(ShellOutputFeature.Action.EarlierOutputReceived::class)
        val start = 300_000 - ShellOutputFeature.initialTailBytes
        assertEquals(start, capture.reads.value.last().first)
        assertEquals(ShellOutputFeature.initialTailBytes, capture.reads.value.last().second)
        assertEquals(start, store.state.startCursor)
        assertTrue(store.state.output.endsWith("eeee" + "tail line\n"))
        assertFalse(store.state.isLoadingEarlier)

        // A page that no longer joins the window is dropped.
        store.send(ShellOutputFeature.Action.EarlierOutputReceived(start = 0, Result.success(OpenCodeShellOutputPage(output = "zz", cursor = 2, size = 300_000))))
        assertFalse(store.state.output.startsWith("zz"))
    }

    @Test fun stopConfirmsReadsTheLastBytesThenEndsTheShell() = sessionTest { h ->
        val capture = Capture()
        capture.append("serving\n")
        val state = outputState().withOutput("serving\n").copy(endCursor = 8)
        val store = h.outputStore(state) {
            this[OpenCodeClient] = capture.install(this[OpenCodeClient])
        }
        capture.append("^C\n")
        store.send(ShellOutputFeature.Action.StopTapped) { it.copy(isConfirmingStop = true) }
        store.send(ShellOutputFeature.Action.StopConfirmed) { it.copy(isConfirmingStop = false, isStopping = true) }
        store.receive(ShellOutputFeature.Action.OutputReceived(OpenCodeShellOutputPage(output = "^C\n", cursor = 11, size = 11))) {
            it.withOutput("serving\n^C\n").copy(endCursor = 11)
        }
        store.receive(ShellOutputFeature.Action.StopFinished(null)) {
            it.copy(isStopping = false, shell = it.shell.copy(status = OpenCodeShell.Status.Killed), isUnavailable = true)
        }
        assertEquals(1, capture.stops.value)
        assertFalse(store.state.isRunning)
        store.send(ShellOutputFeature.Action.StopTapped)
        store.finish()
    }

    @Test fun exitingOrRemovedShellDismissesStopConfirmation() = sessionTest { h ->
        val running = outputState().copy(endCursor = 0)
        val exited = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = null, isListed = true, status = OpenCodeShell.Status.Exited, exitCode = 0)
        val store = h.outputStore(running)
        store.send(ShellOutputFeature.Action.StopTapped) { it.copy(isConfirmingStop = true) }
        store.send(ShellOutputFeature.Action.StatusReceived(exited)) {
            it.copy(shell = it.shell.copy(status = OpenCodeShell.Status.Exited, exitCode = 0), isConfirmingStop = false)
        }

        val vanishing = outputState().copy(endCursor = 0)
        val vanishingStore = h.outputStore(vanishing)
        vanishingStore.send(ShellOutputFeature.Action.StopTapped) { it.copy(isConfirmingStop = true) }
        vanishingStore.send(ShellOutputFeature.Action.PollFailed(OpenCodeClientError(message = "Shell command not found", statusCode = 404))) {
            it.copy(isUnavailable = true, isConfirmingStop = false)
        }
    }

    @Test fun failedStopKeepsTheShellRunning() = sessionTest { h ->
        val capture = Capture()
        val state = outputState().copy(endCursor = 0)
        val failure = OpenCodeClientError(message = "Offline", statusCode = 500)
        val store = h.outputStore(state) {
            this[Clock] = TestClock()
            this[OpenCodeClient] = capture.install(this[OpenCodeClient]).copy(stopShell = { _, _, _ -> throw failure })
        }
        store.exhaustive = false
        store.send(ShellOutputFeature.Action.StopConfirmed) { it.copy(isStopping = true) }
        store.receive(ShellOutputFeature.Action.StopFinished(failure)) { it.copy(isStopping = false, errorMessage = "Couldn’t stop shell") }
        assertEquals(OpenCodeShell.Status.Running, store.state.shell.status)
    }

    @Test fun removedShellIsUnavailable() = sessionTest { h ->
        val store = h.outputStore(outputState()) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                shellOutput = { _, _, _, _, _ -> throw OpenCodeClientError(message = "Shell command not found", statusCode = 404) },
            )
        }
        store.send(ShellOutputFeature.Action.Task)
        store.receive(ShellOutputFeature.Action.PollFailed::class) { it.copy(isUnavailable = true, endCursor = 0) }
        store.finish()
        assertFalse(store.state.isRunning)
    }

    @Test fun copyOutputWritesRetainedOutputAndPollingRecordsMeterSize() = sessionTest { h ->
        val copied = LockIsolated(listOf<String>())
        val metered = LockIsolated(listOf<Pair<String, Int>>())
        val state = outputState(status = OpenCodeShell.Status.Exited).withOutput("hello\n").copy(endCursor = 6)
        val store = h.outputStore(state) {
            this[PasteboardClient] = PasteboardClient(setURL = {}, setString = { text -> copied.append(text) })
            this[ShellOutputMeterClient] = ShellOutputMeterClient(record = { id, size -> metered.append(id to size) })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                shellOutput = { _, _, _, cursor, _ -> OpenCodeShellOutputPage(output = if (cursor == 6) "world\n" else "", cursor = 12, size = 12) },
            )
        }
        store.send(ShellOutputFeature.Action.CopyOutputTapped)
        store.finish()
        assertEquals(listOf("hello\n"), copied.value)

        val unseeded = outputState(status = OpenCodeShell.Status.Exited).copy(endCursor = null)
        val unseededShell = unseeded.shell
        val pollStore = h.outputStore(unseeded) {
            this[ShellOutputMeterClient] = ShellOutputMeterClient(record = { id, size -> metered.append(id to size) })
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                shell = { _, _, _ -> unseededShell },
                shellOutput = { _, _, _, cursor, _ ->
                    if (cursor == Int.MAX_VALUE) OpenCodeShellOutputPage(output = "", cursor = 6, size = 6)
                    else OpenCodeShellOutputPage(output = "hello\n", cursor = 6, size = 6)
                },
            )
        }
        pollStore.send(ShellOutputFeature.Action.Task)
        pollStore.receive(ShellOutputFeature.Action.TailLocated(start = 0)) { it.copy(endCursor = 0) }
        pollStore.receive(ShellOutputFeature.Action.OutputReceived(OpenCodeShellOutputPage(output = "hello\n", cursor = 6, size = 6))) {
            it.withOutput("hello\n").copy(endCursor = 6)
        }
        pollStore.finish()
        assertEquals(listOf("sh_1", "sh_1"), metered.value.map { it.first })
        assertEquals(listOf(6, 6), metered.value.map { it.second })
    }

    // endregion

    // region Background jobs

    private fun reconnecting(h: Harness) =
        h.state().copy(nextRefreshID = 1, refresh = SessionFeature.State.Refresh(id = 1, reason = RefreshReason.Reconnect))

    private fun job(id: String, kind: OpenCodeJob.Kind, owner: String = session.id, child: String? = null, running: Boolean = true) =
        OpenCodeJob(id = id, kind = kind, sessionID = owner, isRunning = running, childSessionID = child, startedAt = Instant.ofEpochMilli(1))

    private fun execute(jobID: String, code: String): OpenCodeTranscriptItem = OpenCodeTranscriptItem.Tool(
        id = "msg_a-$jobID",
        call = OpenCodeToolCall(
            callID = jobID, messageID = "msg_a", name = "execute", status = OpenCodeToolCall.Status.Completed,
            input = OpenCodeJSONValue.Object(mapOf("code" to OpenCodeJSONValue.Str(code))), output = emptyList(), error = null,
            metadata = mapOf("jobID" to OpenCodeJSONValue.Str(jobID)),
        ),
    )

    private val child = OpenCodeSession(id = "ses_child", serverTitle = "Child", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH, parentID = session.id)
    private val grandchild = OpenCodeSession(id = "ses_grand", serverTitle = "Grand", projectID = "p", directory = "/project", updatedAt = Instant.EPOCH, parentID = child.id)

    @Test fun backgroundScriptsAreTheSessionsOwnCodeModeJobsNamedByTheirFirstLine() = sessionTest { h ->
        val state = h.state().copy(
            items = listOf(execute("cm_1", "\n  const a = 1\nreturn a"), execute("cm_2", "  ")),
            familySessions = listOf(session, child),
            jobs = listOf(
                job("cm_1", OpenCodeJob.Kind.CodeMode),
                job("cm_2", OpenCodeJob.Kind.CodeMode),
                job("cm_3", OpenCodeJob.Kind.CodeMode, owner = child.id),
                job("img", OpenCodeJob.Kind.Image),
            ).associateBy { it.id },
        )
        assertEquals(listOf("const a = 1", "cm_2"), state.backgroundScripts.map { it.label })
        assertTrue(state.hasBackgroundWork)
    }

    @Test fun aScriptStillBlockingTheTurnIsNotBackgroundWork() = sessionTest { h ->
        // Every script runs as a job; its execute call only ends with the job's ID once it moves to the background.
        val blocking = h.state().copy(jobs = mapOf("cm_1" to job("cm_1", OpenCodeJob.Kind.CodeMode)))
        assertTrue(blocking.backgroundScripts.isEmpty())
        assertFalse(blocking.hasBackgroundWork)
        val moved = blocking.copy(items = listOf(execute("cm_1", "await build()")))
        assertEquals(listOf("cm_1"), moved.backgroundScripts.map { it.id })
        assertTrue(moved.hasBackgroundWork)
    }

    @Test fun anIdleSubagentStaysListedWhileItsTreeRunsAJob() = sessionTest { h ->
        val base = h.state().copy(familySessions = listOf(session, child, grandchild))
        assertTrue(base.activeSubagents.isEmpty())
        val nested = base.copy(jobs = mapOf("img" to job("img", OpenCodeJob.Kind.Image, owner = grandchild.id)))
        assertEquals(listOf(child.id, grandchild.id), nested.activeSubagents.map { it.id })
        assertTrue(nested.backgroundScripts.isEmpty())
        val video = base.copy(jobs = mapOf("v" to job("v", OpenCodeJob.Kind.Video)))
        assertFalse("The session's own media never shows alone", video.hasBackgroundWork)
        val subagentJob = base.copy(jobs = mapOf("j" to job("j", OpenCodeJob.Kind.Subagent, child = child.id)))
        assertEquals(listOf(child.id), subagentJob.activeSubagents.map { it.id })
    }

    @Test fun stoppingASubagentInterruptsItAndCancelsItsTreesJobs() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val jobs = listOf(
            job("j_child", OpenCodeJob.Kind.Subagent, child = child.id),
            job("img", OpenCodeJob.Kind.Image, owner = grandchild.id),
            job("own", OpenCodeJob.Kind.CodeMode),
        ).associateBy { it.id }
        val state = h.state().copy(familySessions = listOf(session, child, grandchild), activeFamilySessionIDs = setOf(child.id), jobs = jobs)
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                interrupt = { _, id -> calls.append("interrupt:$id"); true },
                cancelJob = { _, id -> calls.append("cancel:$id") },
            )
        }
        store.send(Action.BackgroundSubagentStopTapped(child.id)) { it.copy(stoppingIDs = setOf(child.id)) }
        store.receive(Action.BackgroundStopFinished(child.id, isSubagent = true, failed = false)) { it.copy(stoppingIDs = emptySet()) }
        assertEquals(setOf("interrupt:${child.id}", "cancel:j_child", "cancel:img"), calls.value.toSet())
        assertEquals(3, calls.value.size)
    }

    @Test fun stoppingAShellFromItsRowEndsIt() = sessionTest { h ->
        val calls = LockIsolated(listOf<String>())
        val shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, isListed = true)
        val store = h.store(h.state().copy(shells = listOf(shell))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(stopShell = { _, directory, id -> calls.append("$directory:$id") })
        }
        store.send(Action.BackgroundShellStopTapped("sh_1")) { it.copy(stoppingIDs = setOf("sh_1")) }
        store.receive(Action.BackgroundShellStopFinished("sh_1", failed = false)) {
            it.copy(stoppingIDs = emptySet(), shells = emptyList(), endedShellIDs = setOf("sh_1"))
        }
        assertEquals(listOf("${session.directory}:sh_1"), calls.value)
    }

    @Test fun aFailedShellStopSaysSo() = sessionTest { h ->
        val shell = OpenCodeShell(id = "sh_1", command = "bun dev", sessionID = session.id, isListed = true)
        val store = h.store(h.state().copy(shells = listOf(shell))) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(stopShell = { _, _, _ -> throw OpenCodeClientError("Down") })
        }
        store.send(Action.BackgroundShellStopTapped("sh_1")) { it.copy(stoppingIDs = setOf("sh_1")) }
        store.receive(Action.BackgroundShellStopFinished("sh_1", failed = true)) {
            it.copy(
                stoppingIDs = emptySet(),
                items = it.items + OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t stop shell"),
            )
        }
    }

    @Test fun aFailedScriptStopSaysSo() = sessionTest { h ->
        val state = h.state().copy(jobs = mapOf("cm_1" to job("cm_1", OpenCodeJob.Kind.CodeMode)))
        val store = h.store(state) {
            this[UuidClient] = UuidClient.incrementing()
            this[OpenCodeClient] = this[OpenCodeClient].copy(cancelJob = { _, _ -> throw OpenCodeClientError("Down") })
        }
        store.send(Action.BackgroundScriptStopTapped("cm_1")) { it.copy(stoppingIDs = setOf("cm_1")) }
        store.receive(Action.BackgroundStopFinished("cm_1", isSubagent = false, failed = true)) {
            it.copy(
                stoppingIDs = emptySet(),
                items = it.items + OpenCodeTranscriptItem.Info(id = "info-00000000-0000-0000-0000-000000000000", text = "Couldn’t stop script"),
            )
        }
    }

    @Test fun jobUpdatesDuringAListOutliveIt() = sessionTest { h ->
        val running = job("cm_1", OpenCodeJob.Kind.CodeMode)
        val store = h.store(h.state())
        // Started while the list was in flight: the list doesn't have it yet.
        store.send(Action.JobUpdated(running)) { it.copy(jobs = mapOf(running.id to running), jobChangesDuringRefresh = mapOf(running.id to running)) }
        store.send(Action.JobsResponse(session.id, emptyList())) { it.copy(jobChangesDuringRefresh = emptyMap()) }
        assertEquals(setOf(running.id), store.state.jobs.keys)
        // Ended while the list was in flight: the list still has it.
        store.send(Action.JobUpdated(running.copy(isRunning = false))) { it.copy(jobs = emptyMap(), jobChangesDuringRefresh = mapOf(running.id to null)) }
        store.send(Action.JobsResponse(session.id, listOf(running))) { it.copy(jobChangesDuringRefresh = emptyMap()) }
        assertTrue(store.state.jobs.isEmpty())
        // Another tree's job never lands here.
        store.send(Action.JobUpdated(job("other", OpenCodeJob.Kind.CodeMode, owner = "ses_elsewhere")))
    }

    /** A child's job that started before the family walk found the child shows once it does. */
    @Test fun aNewSubagentsEarlyJobShowsOnceTheFamilyFindsIt() = sessionTest { h ->
        val early = job("cm_child", OpenCodeJob.Kind.CodeMode, owner = child.id)
        val state = h.state().copy(familyRootID = session.id, familySessions = listOf(session))
        val store = h.store(state) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                runningJobs = { _, _ -> listOf(early) },
                familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                pendingInputs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false

        store.send(Action.JobUpdated(early))
        assertTrue(store.state.jobs.isEmpty())
        store.send(Action.FamilyResponse(Result.success(OpenCodeSessionFamily(sessions = listOf(session, child), activeSessionIDs = setOf(child.id)))))
        store.receive(Action.JobsResponse(session.id, listOf(early)))
        assertEquals(setOf(early.id), store.state.jobs.keys)
        h.skipInFlightEffects()
    }

    /** A new stream's re-list drops a settle the stream missed. */
    @Test fun aSettleMissedWhileTheStreamWasDownIsGoneAfterTheReconnect() = sessionTest { h ->
        val stale = job("cm_1", OpenCodeJob.Kind.CodeMode)
        val store = h.store(reconnecting(h).copy(items = listOf(execute(stale.id, "await build()")), jobs = mapOf(stale.id to stale))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                runningShells = { _, _ -> emptyList() },
                runningJobs = { _, _ -> emptyList() },
            )
        }
        store.exhaustive = false
        assertEquals(listOf("cm_1"), store.state.backgroundScripts.map { it.id })

        store.send(Action.EventStreamConnected(server))
        store.receive(Action.JobsResponse(session.id, emptyList())) { it.copy(jobs = emptyMap()) }
        assertFalse(store.state.hasBackgroundWork)
        h.skipInFlightEffects()
    }

    @Test fun eventsDuringTheReconnectsListWinOverItsStaleResponse() = sessionTest { h ->
        val settled = job("cm_old", OpenCodeJob.Kind.CodeMode)
        val started = job("cm_new", OpenCodeJob.Kind.CodeMode)
        val listed = Channel<List<OpenCodeJob>>(Channel.UNLIMITED)
        val moved = listOf(execute(settled.id, "await old()"), execute(started.id, "await new()"))
        val store = h.store(reconnecting(h).copy(items = moved, jobs = mapOf(settled.id to settled))) {
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                runningShells = { _, _ -> emptyList() },
                runningJobs = { _, _ -> listed.receive() },
            )
        }
        store.exhaustive = false

        store.send(Action.EventStreamConnected(server))
        store.send(Action.JobUpdated(settled.copy(isRunning = false)))
        store.send(Action.JobUpdated(started))
        // The read began before both: it still lists the settled job and not the new one.
        listed.trySend(listOf(settled))
        store.receive(Action.JobsResponse(session.id, listOf(settled))) {
            it.copy(jobs = mapOf(started.id to started), jobChangesDuringRefresh = emptyMap())
        }
        assertEquals(listOf(started.id), store.state.backgroundScripts.map { it.id })
        h.skipInFlightEffects()
    }

    @Test fun aSwitchSnapshotKeepsItsTreesJobsWhileAway() = sessionTest { h ->
        val running = job("cm_1", OpenCodeJob.Kind.CodeMode)
        val left = h.state().copy(familySessions = listOf(session, child), jobs = mapOf(running.id to running))
        var snapshot = requireNotNull(h.state { SessionFeature.SwitchSnapshot.from(left) })
        assertEquals(left.jobs, snapshot.jobs)

        val childJob = job("img", OpenCodeJob.Kind.Image, owner = child.id)
        snapshot = snapshot.applying(childJob)
        // Another tree's job never joins it.
        assertEquals(snapshot, snapshot.applying(job("other", OpenCodeJob.Kind.CodeMode, owner = "ses_elsewhere")))
        snapshot = snapshot.applying(running.copy(isRunning = false))
        assertEquals(setOf(childJob.id), snapshot.jobs.keys)

        val restored = h.state { snapshot.restoreSessionData(h.state().copy(familySessions = listOf(session, child))) }
        assertEquals(setOf(childJob.id), restored.jobs.keys)
        assertEquals(listOf(child.id), restored.activeSubagents.map { it.id })
    }

    @Test fun aSubagentJobWhoseChildIsntListedYetIsKept() = sessionTest { h ->
        // The family read hasn't reached the child the job runs in; its owner is the child.
        val pending = job("j", OpenCodeJob.Kind.Subagent, child = "ses_unlisted")
        val store = h.store(h.state())
        store.send(Action.JobUpdated(pending)) {
            it.copy(jobs = mapOf(pending.id to pending), jobChangesDuringRefresh = mapOf(pending.id to pending))
        }
    }

    // endregion

    // endregion
}
