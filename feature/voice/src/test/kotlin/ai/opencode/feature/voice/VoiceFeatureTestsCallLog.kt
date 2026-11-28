package ai.opencode.feature.voice

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.feature.voice.VoiceFeature.Action
import ai.opencode.feature.voice.VoiceFeature.State
import ai.opencode.feature.voice.VoiceFeatureTestSupport.Harness
import ai.opencode.feature.voice.VoiceFeatureTestSupport.LockIsolated
import ai.opencode.feature.voice.VoiceFeatureTestSupport.append
import ai.opencode.feature.voice.VoiceFeatureTestSupport.arguments
import ai.opencode.feature.voice.VoiceFeatureTestSupport.call
import ai.opencode.feature.voice.VoiceFeatureTestSupport.copy
import ai.opencode.feature.voice.VoiceFeatureTestSupport.date
import ai.opencode.feature.voice.VoiceFeatureTestSupport.delegator
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.response
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.settle
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceWork
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodeJSONValue
import ai.opencode.server.model.OpenCodePendingRequests
import ai.opencode.server.model.OpenCodeToolCall
import ai.opencode.server.model.OpenCodeTranscriptItem
import ai.opencode.server.model.OpenCodeUploadEvent
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class VoiceFeatureTestsCallLog {
    private lateinit var h: Harness

    private fun tempDirectory(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun recorder(directory: File, now: Instant = Instant.EPOCH) =
        VoiceCallLogRecorder(directory = directory, date = DateClient.constant(now), upload = VoiceCallLogUploadClient())

    /** The recorder writes a finished log off the caller's thread; wait for it (bounded). */
    private fun saved(recorder: VoiceCallLogRecorder): VoiceCallLog? {
        repeat(400) {
            recorder.list().firstOrNull()?.let { return it }
            Thread.sleep(5)
        }
        return null
    }

    private fun record(state: State, role: VoiceTranscriptTail.Role, text: String, id: String? = null): State =
        state.copy(tail = state.tail.record(role, text, id).tail)

    private fun State.stepping(event: ReplyWatch.Event): State = copy(replyWatch = replyWatch.transition(event).first)

    /**
     * A call is saved as a replayable log: the user and voice tracks,
     * delegations as markers, everything closed when the call ends.
     */
    @Test fun aCallIsSavedAsAReplayableLog() = voiceTest { harness ->
        h = harness
        val directory = tempDirectory("voice-logs-")
        try {
            val recorder = recorder(directory)
            val voice = h.state { State(server, session) }
            val store = h.store(voice) {
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[DateClient] = DateClient.constant(Instant.EPOCH)
                this[Clock] = ImmediateClock()
                voiceCall { copy(start = { _, _ -> flow { awaitCancellation() } }, end = {}) }
                this[RealtimePreflightClient] = RealtimePreflightClient(verify = { _, _ -> })
            }
            store.exhaustive = false
            store.setAPIKey("sk-test")
            store.send(Action.Start)
            store.send(Action.CallEvent(VoiceCallEvent.Connected))
            store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u1", text = "Check the upload test", final = true)))
            store.send(Action.CallEvent(VoiceCallEvent.AssistantDelta(itemID = "a1", delta = "Checking the upload test.")))
            store.send(Action.CallEvent(VoiceCallEvent.AssistantDone(itemID = "a1", text = "Checking the upload test.")))
            store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments("Checking the upload test."))))
            store.send(Action.EndTapped)

            val saved = saved(recorder) ?: throw AssertionError("No saved log")
            assertEquals("Voice lab", saved.title)
            assertEquals(listOf(VoiceCallLog.Track.user, VoiceCallLog.Track.voice, VoiceCallLog.Track.delegator), saved.spans.map { it.track })
            assertEquals("Check the upload test", saved.spans.firstOrNull()?.label)
            assertTrue("Ending the call closes every span", saved.spans.all { it.end != null })
            assertTrue(saved.notes.any { it.kind == VoiceCallLog.Note.Kind.marker && it.text.startsWith("Delegated") })
            assertNull(store.state.callLogID)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun voiceReconnectRefreshesActiveSessionsAndSettlesFinishedWatch() = voiceTest { harness ->
        h = harness
        val blog = VoiceFeatureTestSupport.session("ses_blog", "Blog game", "/Users/kit/blog-game")
        val voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
            .stepping(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_blog", sessionID = blog.id, title = blog.title))))

        val announced = LockIsolated(listOf<String>())
        val store = h.store(voice) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceWork { copy(activeSessionIDs = { emptySet() }, fetchReply = { _, _, _ -> "Recovered on voice socket reconnect." }) }
            voiceCall { copy(announce = { _, _, text -> announced.append(text) }) }
        }
        store.exhaustive = false

        store.send(Action.CallEvent(VoiceCallEvent.Reconnected))
        store.receive(Action.ReplyWatch::class)
        store.receive(Action.ReplyWatch::class)
        settle()

        assertTrue(store.state.replyWatch.watched.isEmpty())
        assertEquals(true, announced.value.firstOrNull()?.contains("Recovered on voice socket reconnect."))
        store.finish()
    }

    private data class OutcomeCase(
        val label: String,
        val reply: String?,
        val failure: OpenCodeClientError?,
        val expectsFailedSpan: Boolean,
        val expectedNoticeSubstring: String,
    ) {
        companion object {
            val all = listOf(
                OutcomeCase(
                    label = "replied",
                    reply = "Updated the parser.\n\n```swift\nlet x = 1\n```\n\nAll tests pass.",
                    failure = null,
                    expectsFailedSpan = false,
                    expectedNoticeSubstring = "The session “Blog game” finished the work you started there.",
                ),
                OutcomeCase(
                    label = "finishedWithoutText",
                    reply = "",
                    failure = null,
                    expectsFailedSpan = false,
                    expectedNoticeSubstring = "The session “Blog game” finished the work you started there without a text reply.",
                ),
                OutcomeCase(
                    label = "replyUnreadable",
                    reply = null,
                    failure = OpenCodeClientError(message = "The voice request is no longer in the recent transcript."),
                    expectsFailedSpan = true,
                    expectedNoticeSubstring = "its latest reply couldn’t be read",
                ),
                OutcomeCase(
                    label = "failed",
                    reply = null,
                    failure = OpenCodeClientError(message = "Run failed: error — tool crashed"),
                    expectsFailedSpan = true,
                    expectedNoticeSubstring = "stopped with an error on the work you started there: Run failed: error — tool crashed",
                ),
                OutcomeCase(
                    label = "unreachable",
                    reply = null,
                    failure = OpenCodeClientError(message = "Couldn’t reach the OpenCode server"),
                    expectsFailedSpan = true,
                    expectedNoticeSubstring = "Couldn’t reach the OpenCode server while waiting for “Blog game”",
                ),
            )
        }
    }

    /** One run per case. */
    @Test fun everyWatchedReplyOutcomeAnnouncesAndLogsWithoutSilentDrops() = voiceTest { harness ->
        h = harness
        for (outcomeCase in OutcomeCase.all) {
            val label = outcomeCase.label
            val directory = tempDirectory("voice-watch-outcomes-")
            try {
                val recorder = recorder(directory)
                val logID = UUID.randomUUID()
                recorder.begin(logID, title = "Voice lab", engine = "gptLive")

                val voice = h.state { State(server, session) }.live().copy(usesDelegator = true, callLogID = logID)
                val initialFailures = if (label == "unreachable") ReplyWatch.maxRetryAttempts - 1 else 0
                val prompt = ReplyWatch.Prompt(promptID = "msg_$label", sessionID = "ses_blog", title = "Blog game", failures = initialFailures)

                val announced = LockIsolated(listOf<String>())
                val store = h.store(voice) {
                    this[Clock] = ImmediateClock()
                    this[DateClient] = DateClient.constant(Instant.EPOCH)
                    this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                    voiceWork {
                        copy(
                            awaitReply = { _, _, _ ->
                                outcomeCase.failure?.let { throw it }
                                outcomeCase.reply
                            },
                            checkSessionActive = { _, _ -> true },
                        )
                    }
                    voiceCall { copy(announce = { _, _, text -> announced.append(text) }, end = {}) }
                }
                store.exhaustive = false

                store.send(Action.ReplyWatch(ReplyWatch.Event.Sent(listOf(prompt))))
                store.receive(Action.ReplyWatch::class)
                settle()

                assertTrue(label, store.state.replyWatch.watched.isEmpty())
                assertEquals(label, listOf("msg_$label"), store.state.replyWatch.completed.map { it.promptID })
                assertEquals(label, 1, announced.value.size)
                assertTrue(label, announced.value[0].contains(outcomeCase.expectedNoticeSubstring))
                assertFalse("Code blocks are collapsed before announcing", announced.value[0].contains("let x = 1"))

                store.send(Action.EndTapped)
                val saved = saved(recorder) ?: throw AssertionError("$label: no saved log")
                val span = saved.spans.firstOrNull { it.id == "watch-msg_$label" } ?: throw AssertionError("$label: no watch span")
                assertNotNull(label, span.end)
                assertEquals(label, outcomeCase.expectsFailedSpan, span.failed)
                assertTrue(label, saved.notes.any { it.kind == VoiceCallLog.Note.Kind.marker && it.text.contains("Watching “Blog game”") })
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test fun replyResolvingWhileConnectingIsQueuedUntilConnected() = voiceTest { harness ->
        h = harness
        val prompt = ReplyWatch.Prompt(promptID = "msg_early", sessionID = "ses_blog", title = "Blog game")
        val voice = h.state { State(server, session) }.withPhase(State.Phase.Connecting).copy(usesDelegator = true)
            .stepping(ReplyWatch.Event.Sent(listOf(prompt)))

        val announced = LockIsolated(listOf<String>())
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall { copy(announce = { _, _, text -> announced.append(text) }) }
        }
        store.exhaustive = false

        store.send(Action.ReplyWatch(ReplyWatch.Event.WaitSucceeded(promptID = "msg_early", reply = "Landed during connect.")))
        settle()
        assertTrue("Held while the call is still connecting", announced.value.isEmpty())
        assertEquals(1, store.state.replyWatch.connectingNotices.size)

        store.send(Action.CallEvent(VoiceCallEvent.Connected))
        settle()
        assertTrue(store.state.replyWatch.connectingNotices.isEmpty())
        assertEquals(1, announced.value.size)
        assertTrue(announced.value[0].contains("Landed during connect."))
        store.finish()
    }

    @Test fun delegatorRequestContextReflectsLiveSessionRunStateAndSeparatesInFlightDelegatorWork() = voiceTest { harness ->
        h = harness
        val blog = VoiceFeatureTestSupport.session("ses_blog", "Blog game", "/Users/kit/blog-game")
        var voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        voice = voice.copy(replyWatch = voice.replyWatch.copy(activeSessionIDs = setOf(session.id)))
        voice = voice.stepping(ReplyWatch.Event.Sent(listOf(ReplyWatch.Prompt(promptID = "msg_blog", sessionID = blog.id, title = blog.title))))
        voice = voice.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_blog", reply = "Finished the scoring loop."))
        voice = voice.copy(replyWatch = voice.replyWatch.copy(activeSessionIDs = setOf(session.id)), delegatorWork = listOf("earlier_call"))
        voice = record(voice, VoiceTranscriptTail.Role.user, "How is this session doing, and did Blog game finish?", id = "u1")

        val capturedRequests = LockIsolated(listOf<String>())
        val store = h.store(voice) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                (input.firstOrNull() as? VoiceDelegator.InputItem.User)?.let { capturedRequests.append(it.text) }
                response("r1", text = "Voice lab is working and Blog game finished.")
            }
            voiceCall { copy(completeFunction = { _, _ -> }) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d2", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)

        val firstRequest = capturedRequests.value.firstOrNull() ?: throw AssertionError("No request")
        assertTrue(firstRequest.contains("The call is attached to session \"Voice lab\" (id ses_voice, project project, working)."))
        assertTrue(firstRequest.contains("Tracked session work from this call:"))
        assertTrue(firstRequest.contains("- \"Blog game\" (id ses_blog, prompt msg_blog): finished — replied: Finished the scoring loop."))
        assertTrue(firstRequest.contains("Other voice delegator requests currently in flight: 1."))

        // Now the viewed session is idle even while another delegator request is in flight.
        store.send(Action.ReplyWatch(ReplyWatch.Event.ActiveSessionsSnapshot(emptySet(), source = ReplyWatch.SettlementSource.snapshot)))
        store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "u2", text = "Check again", final = true)))
        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d3", name = "delegate", arguments = arguments())))
        store.receive(Action.DelegatorFinished::class)

        val secondRequest = capturedRequests.value.lastOrNull() ?: throw AssertionError("No request")
        assertTrue(secondRequest.contains("The call is attached to session \"Voice lab\" (id ses_voice, project project, idle)."))
        store.finish()
    }

    @Test fun readSessionSurfacesStatusAndTranscriptErrorsInsteadOfReportingWorkingFalse() = voiceTest { harness ->
        h = harness
        val directory = tempDirectory("voice-read-err-")
        try {
            val recorder = recorder(directory)
            val logID = UUID.randomUUID()
            recorder.begin(logID, title = "Voice lab", engine = "gptLive")

            var voice = h.state { State(server, session) }.live().copy(usesDelegator = true, callLogID = logID)
            voice = record(voice, VoiceTranscriptTail.Role.user, "Did it finish?", id = "u1")

            val toolOutputs = LockIsolated(listOf<String>())
            val store = h.store(voice) {
                this[Clock] = ImmediateClock()
                this[DateClient] = DateClient.constant(Instant.EPOCH)
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[VoiceDelegatorClient] = delegator { _, input, _ ->
                    val first = input.firstOrNull()
                    if (first is VoiceDelegator.InputItem.ToolOutput) {
                        toolOutputs.append(first.output)
                        return@delegator response("r2", text = "I couldn’t reach the server to check.")
                    }
                    response("r1", listOf(call("t_read", "read_session", "{}")))
                }
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    transcript = { _, _, _ -> listOf(OpenCodeTranscriptItem.AssistantMarkdown(id = "m1", text = "Earlier reply")) },
                    isSessionActive = { _, _ -> throw OpenCodeClientError(message = "Couldn’t reach the OpenCode server") },
                )
                voiceCall { copy(completeFunction = { _, _ -> }, end = {}) }
            }
            store.exhaustive = false
            store.setAPIKey("sk-test")

            store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d1", name = "delegate", arguments = arguments())))
            store.receive(Action.DelegatorFinished::class)

            val output = toolOutputs.value.firstOrNull() ?: throw AssertionError("No tool output")
            assertEquals("""{"error":"Couldn’t reach the OpenCode server"}""", output)
            assertFalse(output.contains(""""working":false"""))

            store.send(Action.EndTapped)
            val saved = saved(recorder) ?: throw AssertionError("No saved log")
            val toolSpan = saved.spans.firstOrNull { it.id == "t_read" } ?: throw AssertionError("No tool span")
            assertTrue(toolSpan.failed)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun sendMessageWithAttachCallLogAndSendCallLogUploadRedactedTrimmedSnapshot() = voiceTest { harness ->
        h = harness
        val directory = tempDirectory("voice-attach-log-")
        try {
            val startTime = date(1_000)
            val recorder = recorder(directory, now = startTime.plusSeconds(900))
            val logID = UUID.randomUUID()
            recorder.begin(logID, title = "Voice lab (https://opencode:secretpw@host.example:4096)", engine = "gptLive", at = startTime)
            recorder.span(logID, VoiceCallLog.Track.user, spanID = "old-span", label = "Too old", end = true, at = startTime.plusSeconds(10))
            recorder.note(logID, VoiceCallLog.Note.Kind.marker, "Old marker", at = startTime.plusSeconds(10))
            recorder.span(
                logID,
                VoiceCallLog.Track.delegator,
                spanID = "watch-msg_blog",
                label = "Waiting on “Blog game”",
                detail = "Bearer secret_bearer_token_123 and sk-live-secret1234567890",
                end = false,
                at = startTime.plusSeconds(800),
            )
            recorder.note(
                logID,
                VoiceCallLog.Note.Kind.received,
                "Auth key sk-live-secret1234567890 and token 1791314434.abcdef1234567890abcdef",
                at = startTime.plusSeconds(810),
            )

            var voice = h.state { State(server, session) }.live().copy(usesDelegator = true, callLogID = logID)
            voice = record(voice, VoiceTranscriptTail.Role.user, "That missed the update, tell this session to investigate and send the call logs", id = "u1")

            val uploadedPayload = LockIsolated<ByteArray?>(null)
            val sentFiles = LockIsolated(listOf<List<OpenCodeFileReference>>())
            val sentTexts = LockIsolated(listOf<String>())
            val store = h.store(voice) {
                this[Clock] = ImmediateClock()
                this[DateClient] = DateClient.constant(startTime.plusSeconds(900))
                this[VoiceCallLogClient] = VoiceCallLogClient(recorder = recorder)
                this[VoiceDelegatorClient] = delegator { _, input, _ ->
                    if (input.firstOrNull() is VoiceDelegator.InputItem.ToolOutput) {
                        return@delegator response("r2", text = "Sent the bug report with the call log attached.")
                    }
                    response(
                        "r1",
                        listOf(
                            call("t1", "send_message", """{"text":"Investigate missed completion notice.","attach_call_log":true}"""),
                            call("t2", "send_call_log", """{"note":"Check why watch-msg_blog stayed open."}"""),
                        ),
                    )
                }
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    uploadFile = { _, _, file, name ->
                        flow {
                            runCatching { file.readBytes() }.getOrNull()?.let { uploadedPayload.setValue(it) }
                            emit(OpenCodeUploadEvent.Finished(path = "/tmp/uploads/$name"))
                        }
                    },
                    sendPrompt = { _, _, text, _, files, _, _ ->
                        sentTexts.append(text)
                        sentFiles.append(files)
                    },
                    message = { _, _, _ -> throw OpenCodeClientError(message = "Missing", statusCode = 404) },
                    pendingInputs = { _, _ -> emptyList() },
                    isSessionActive = { _, _ -> false },
                    getSession = { _, _ -> session },
                    familyPendingRequests = { _, _, _ -> OpenCodePendingRequests(permissions = emptyList(), forms = emptyList()) },
                )
                voiceWork { copy(awaitReply = { _, _, _ -> "Root cause identified." }) }
                voiceCall { copy(completeFunction = { _, _ -> }, announce = { _, _, _ -> }) }
            }
            store.exhaustive = false
            store.setAPIKey("sk-test")

            store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d_log", name = "delegate", arguments = arguments())))
            store.receive(Action.DelegatorFinished::class)
            store.skipReceivedActions()

            assertEquals(2, sentFiles.value.size)
            val fileName = "voice-call-${logID.toString().lowercase()}.json"
            assertEquals(fileName, sentFiles.value[0].firstOrNull()?.name)
            assertEquals("/tmp/uploads/$fileName", sentFiles.value[1].firstOrNull()?.path)
            val parsedFirst = VoiceHandoff.parse(sentTexts.value[0]) ?: throw AssertionError("Not a voice delegation")
            assertTrue(parsedFirst.callLogAttached)
            assertEquals("Investigate missed completion notice.", parsedFirst.displayText)

            val uploadedData = uploadedPayload.value ?: throw AssertionError("Nothing uploaded")
            val uploadedString = uploadedData.decodeToString()
            assertFalse(uploadedString.contains("sk-live-secret1234567890"))
            assertFalse(uploadedString.contains("secret_bearer_token_123"))
            assertFalse(uploadedString.contains("secretpw"))
            assertFalse(uploadedString.contains("1791314434.abcdef1234567890abcdef"))
            assertTrue(uploadedString.contains("sk-<redacted>"))
            assertTrue(uploadedString.contains("Bearer <redacted>"))

            val decodedLog = VoiceCallLog.json.decodeFromString<VoiceCallLog>(uploadedString)
            assertFalse("Spans older than 10 minutes are trimmed", decodedLog.spans.any { it.id == "old-span" })
            val openWatch = decodedLog.spans.firstOrNull { it.id == "watch-msg_blog" } ?: throw AssertionError("No open watch span")
            assertNull("Open spans stay open in the snapshot", openWatch.end)
            assertEquals("Snapshotting does not close the live call log", logID, store.state.callLogID)
            store.finish()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun unrelatedDelegationDoesNotCancelHeldNoticesAndReadSessionReconcilesToolOnlyTurn() = voiceTest { harness ->
        h = harness
        val blog = VoiceFeatureTestSupport.session("ses_blog", "Blog game", "/Users/kit/blog-game")
        var voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        voice = voice.stepping(
            ReplyWatch.Event.Sent(
                listOf(
                    ReplyWatch.Prompt(promptID = "msg_done", sessionID = "ses_done", title = "Earlier session"),
                    ReplyWatch.Prompt(promptID = "msg_blog_2", sessionID = blog.id, title = blog.title),
                ),
            ),
        )
        voice = voice.stepping(ReplyWatch.Event.WaitSucceeded(promptID = "msg_done", reply = "Earlier session finished."))
        voice = record(voice, VoiceTranscriptTail.Role.user, "Check how Blog game is doing", id = "u1")

        val cancelled = LockIsolated(listOf<List<String>>())
        val toolOutputs = LockIsolated(listOf<String>())
        val store = h.store(voice) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                val first = input.firstOrNull()
                if (first is VoiceDelegator.InputItem.ToolOutput) {
                    toolOutputs.append(first.output)
                    return@delegator response("r2", text = "Blog game finished its tool steps.")
                }
                response("r1", listOf(call("t_read", "read_session", """{"session_id":"ses_blog"}""")))
            }
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                getSession = { _, id -> if (id == blog.id) blog else session },
                isSessionActive = { _, _ -> false },
                transcript = { _, _, _ ->
                    listOf(
                        OpenCodeTranscriptItem.UserText(id = "msg_blog_1", text = "First prompt"),
                        OpenCodeTranscriptItem.AssistantMarkdown(id = "msg_a1-text-0", text = "Reply from first turn"),
                        OpenCodeTranscriptItem.UserText(id = "msg_blog_2", text = "Second tool-only prompt"),
                        OpenCodeTranscriptItem.Tool(
                            id = "msg_a2-tool-1",
                            call = OpenCodeToolCall(
                                callID = "t1", name = "bash", status = OpenCodeToolCall.Status.Completed,
                                input = OpenCodeJSONValue.Null, output = emptyList(), error = null, metadata = emptyMap(),
                            ),
                        ),
                    )
                },
            )
            voiceCall {
                copy(
                    completeFunction = { _, _ -> },
                    cancelPendingAnnouncements = { promptIDs -> cancelled.append(promptIDs) },
                )
            }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d_check", name = "delegate", arguments = arguments())))
        store.receive(Action.ReplyWatch::class)
        store.receive(Action.DelegatorFinished::class)
        settle()

        // Only the session that `read_session` actually read (`ses_blog`) is reconciled and cancelled;
        // the unrelated `ses_done` completion is untouched.
        assertEquals(listOf(listOf("msg_blog_2")), cancelled.value)
        // Because `msg_blog_2` had only tool calls after it, `read_session` resolves it as `.finishedWithoutText`
        // rather than stealing `"Reply from first turn"` from before `msg_blog_2`.
        assertTrue(store.state.replyWatch.watched.isEmpty())
        val resolvedBlog = store.state.replyWatch.completed.firstOrNull { it.promptID == "msg_blog_2" } ?: throw AssertionError("Not resolved")
        assertEquals(ReplyWatch.Outcome.FinishedWithoutText, resolvedBlog.outcome)
        store.finish()
    }

    @Test fun inlineCallLogFallbackProducesValidBoundedJSONWithNotesAndRedactsURLsAndParams() {
        val raw = """
            wss://opencode:secret_ws_pw@host.example:4096/api/event?token=tok_123&api_key=key_456&password=pw_789
            https://host.example:4096/auth/connect/PAIRCODE987654?key=secret_q
        """.trimIndent()
        val redacted = VoiceCallLog.redactText(raw)
        assertFalse(redacted.contains("secret_ws_pw"))
        assertFalse(redacted.contains("tok_123"))
        assertFalse(redacted.contains("key_456"))
        assertFalse(redacted.contains("pw_789"))
        assertFalse(redacted.contains("PAIRCODE987654"))
        assertFalse(redacted.contains("secret_q"))
        assertTrue(redacted.contains("wss://<redacted>@host.example:4096"))
        assertTrue(redacted.contains("/auth/connect/<redacted>?key=<redacted>"))

        var log = VoiceCallLog(id = UUID.randomUUID(), startedAt = date(1_000), title = "Voice lab", engine = "gptLive")
        for (index in 0 until 40) {
            log = log.copy(
                spans = log.spans + VoiceCallLog.Span(
                    id = "s-$index", track = VoiceCallLog.Track.tool, start = index.toDouble(), end = index + 0.5,
                    label = "tool", detail = "x".repeat(600),
                ),
                notes = log.notes + VoiceCallLog.Note(id = index, t = index.toDouble(), kind = VoiceCallLog.Note.Kind.marker, text = "Marker $index: " + "y".repeat(400)),
            )
        }
        val bounded = log.boundedJSON(maxBytes = 16_000)
        assertTrue(bounded.size <= 16_000)
        val decoded = VoiceCallLog.json.decodeFromString<VoiceCallLog>(bounded.decodeToString())
        assertTrue(decoded.spans.isNotEmpty())
        assertTrue(decoded.notes.isNotEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xxhdpi")
class VoiceCallReplayViewTests {
    /** An hour-long call's replay opens. */
    @Test fun anHourLongCallOpens() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val view = ComposeView(activity)
        activity.setContentView(view)
        val log = VoiceCallLog(id = UUID(0, 1), startedAt = Instant.EPOCH, endedAt = Instant.EPOCH.plusSeconds(3_600), title = "Call", engine = "test")
        view.setContent { VoiceCallReplayView(log, onBack = {}) }
        shadowOf(Looper.getMainLooper()).idle()
        view.disposeComposition()
    }
}
