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
import ai.opencode.feature.voice.VoiceFeatureTestSupport.delegator
import ai.opencode.feature.voice.VoiceFeatureTestSupport.eventually
import ai.opencode.feature.voice.VoiceFeatureTestSupport.increment
import ai.opencode.feature.voice.VoiceFeatureTestSupport.live
import ai.opencode.feature.voice.VoiceFeatureTestSupport.receive
import ai.opencode.feature.voice.VoiceFeatureTestSupport.response
import ai.opencode.feature.voice.VoiceFeatureTestSupport.setAPIKey
import ai.opencode.feature.voice.VoiceFeatureTestSupport.server
import ai.opencode.feature.voice.VoiceFeatureTestSupport.session
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceCall
import ai.opencode.feature.voice.VoiceFeatureTestSupport.voiceTest
import ai.opencode.feature.voice.VoiceFeatureTestSupport.withTestDependencies
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.model.OpenCodeModelRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VoiceFeatureTestsScreen {
    private lateinit var h: Harness

    @Test fun movingTheCallKeepsItsCaptionsAndLiveReply() = voiceTest { harness ->
        h = harness
        val other = VoiceFeatureTestSupport.session("ses_other", "Other", "/project")
        var voice = h.state { State(server, session) }
        voice = voice.copy(
            captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")),
            liveAssistantText = "On it",
            liveAssistantItemID = "a1",
        )
        voice = h.state { voice.retarget(other) }
        assertTrue(voice.captions.size == 1 && voice.liveAssistantText == "On it" && voice.liveAssistantItemID == "a1")
        voice = h.state { voice.retargetToDraft(directory = "/project", model = null) }
        assertTrue(voice.captions.size == 1 && voice.liveAssistantText == "On it")
    }

    @Test fun newSessionFromADraftCallKeepsTheConversationAndItsWork() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, draftDirectory = "/project", model = null) }
        voice = voice.copy(captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")))
        val captured = voice.tail.capture()
        voice = voice.copy(
            tail = captured.tail,
            pendingWork = listOf(State.PendingWork(callID = "c1", capture = captured.capture, target = voice.target, move = voice.moves)),
        )
        assertTrue(voice.isHere(voice.pendingWork[0]))
        voice = h.state { voice.retargetToDraft(directory = "/project", model = null) }
        assertEquals(1, voice.captions.size)
        assertEquals(listOf("c1"), voice.pendingWork.map { it.callID })
        assertTrue(voice.abandonedCallIDs.isEmpty())
        // It creates its own draft's session; the new draft won't adopt it.
        assertFalse(voice.isHere(voice.pendingWork[0]))
    }

    @Test fun changingADraftCallsProjectKeepsTheConversation() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, draftDirectory = "/project", model = null) }
        val before = voice.placement.target
        voice = voice.copy(captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")))
        val captured = voice.tail.capture()
        voice = voice.copy(tail = captured.tail, pendingWork = listOf(State.PendingWork(callID = "c1", capture = captured.capture)))
        voice = voice.moveDraft(directory = "/other")
        assertTrue(voice.target.directory == "/other" && voice.directoryName == "other")
        assertNotEquals(before, voice.placement.target)
        assertEquals(1, voice.captions.size)
        assertEquals(listOf("c1"), voice.abandonedCallIDs)
    }

    /** The call's session moved folders (Move to): the same session, so the
     *  call stays on it and only its folder follows. */
    @Test fun aCallFollowsItsSessionToANewFolder() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, session) }
        val before = voice.placement
        voice = voice.copy(startedTarget = voice.target)
        voice = voice.copy(captions = listOf(State.Caption(id = "user-1", role = VoiceTranscriptTail.Role.user, text = "Check the tests")))
        voice = voice.sessionMoved("ses_other", directory = "/elsewhere")
        assertEquals(session.directory, voice.target.directory)
        voice = voice.sessionMoved(session.id, directory = "/work/repo")
        assertTrue(voice.target.sessionID == session.id && voice.target.directory == "/work/repo")
        assertEquals(before, voice.placement)
        assertEquals("/work/repo", voice.startedTarget?.directory)
        assertEquals("repo", voice.directoryName)
        assertEquals(1, voice.captions.size)
    }

    @Test fun aDraftCallCreatesItsSessionWithTheDraftsModel() = voiceTest {
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val calls = LockIsolated(listOf<String>())
        val created = VoiceFeatureTestSupport.session("ses_new", "", "/project")
        val admission = withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, directory, _ ->
                    calls.append("create $directory")
                    created
                },
                switchModel = { _, sessionID, ref -> calls.append("model $sessionID ${ref.id}") },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    calls.append("prompt $sessionID")
                    "msg_1"
                },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = null, directory = "/project", model = model), "Fix it")
        }
        assertEquals("ses_new", admission.createdSession?.id)
        assertEquals(listOf("create /project", "model ses_new fixture-model", "prompt ses_new"), calls.value)
    }

    /**
     * A delegation into a session applies the screen's waiting pick first,
     * as a typed send would.
     */
    @Test fun aDelegationAppliesTheScreensPickBeforeItsPrompt() = voiceTest {
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture", variant = "high")
        val calls = LockIsolated(listOf<String>())
        withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                switchModel = { _, sessionID, ref -> calls.append("model $sessionID ${ref.id}") },
                sendDeliveredPrompt = { _, sessionID, _, _ ->
                    calls.append("prompt $sessionID")
                    "msg_1"
                },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = "ses_1", directory = "/project", model = model), "Fix it")
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = "ses_1", directory = "/project"), "Test it")
        }
        assertEquals(listOf("model ses_1 fixture-model", "prompt ses_1", "prompt ses_1"), calls.value)
    }

    /**
     * A session that lands while the call is connecting isn't a move: on
     * connect the model just learns it's attached to it.
     */
    @Test fun aDraftSessionThatLandsWhileConnectingIsntAMove() = voiceTest { harness ->
        h = harness
        var voice = h.state { State(server, draftDirectory = "/project", model = null) }.withPhase(State.Phase.Connecting)
        voice = voice.copy(startedTarget = voice.target)
        val session = VoiceFeatureTestSupport.session("ses_call", "", "/project")
        val updates = LockIsolated(listOf<String>())
        val deltas = LockIsolated(listOf<String?>())
        val retargets = LockIsolated(0)
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall {
                copy(
                    setAtEar = { },
                    retarget = { retargets.increment() },
                    updateInstructions = { instructions, delta ->
                        updates.append(instructions)
                        deltas.append(delta)
                    },
                )
            }
        }
        store.exhaustive = false
        store.send(Action.DraftSessionCreated(session))
        assertEquals("ses_call", store.state.target.sessionID)
        assertNull("Configured for the draft", store.state.startedTarget?.sessionID)
        store.send(Action.CallEvent(VoiceCallEvent.Connected))
        assertEquals(store.state.target, store.state.startedTarget)
        eventually { updates.value.isNotEmpty() }
        assertEquals(1, updates.value.size)
        assertEquals(false, updates.value.firstOrNull()?.contains("has not started yet"))
        store.skipReceivedActions()
        assertEquals("Not retargeted", 0, retargets.value)
        assertTrue("No “switched screens” for the model", deltas.value.all { it?.contains(VoiceInstructions.retargetPrefix) != true })
        assertNull(store.state.lastMove)
    }

    /**
     * A refused model sends nothing: the call hears why, and the pick waits
     * on the session it created. (The `VoiceWorkClient` half; the screen's half
     * is SessionFeature's.)
     */
    @Test fun aDraftCallsRefusedModelSendsNothing() = voiceTest {
        val created = VoiceFeatureTestSupport.session("ses_new", "Upload test", "/project")
        val model = OpenCodeModelRef(id = "fixture-model", providerID = "fixture")
        val calls = LockIsolated(listOf<String>())
        val admission = withTestDependencies({
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                createSession = { _, _, _ ->
                    calls.append("create")
                    created
                },
                switchModel = { _, _, _ -> throw OpenCodeClientError(message = "Unknown model", statusCode = 400) },
                sendDeliveredPrompt = { _, _, _, _ ->
                    calls.append("prompt")
                    "msg_1"
                },
            )
        }) {
            VoiceWorkClient.liveValue.submit(VoiceWorkTarget(server = server, sessionID = null, directory = "/project", model = model), "Fix it")
        }
        assertEquals(listOf("create"), calls.value)
        assertEquals("ses_new", admission.createdSession?.id)
        assertNull(admission.promptID)
        assertEquals("Unknown model", admission.modelError?.message)
    }

    @Test fun theStatusHeaderAssumesTheUserIsntLookingUntilTheySayTheyAre() {
        val watch = ReplyWatch()
        val assumed = watch.statusSummary(viewing = session, draftDirectory = session.directory)
        assertEquals(
            "The call is attached to session \"Voice lab\" (id ses_voice, project project, idle).\nAssume the user isn't looking at the screen (they haven't said they are): refer to sessions by name and describe results in words (what changed, the key lines, counts), never \"as you can see\" or anything on screen.",
            assumed,
        )

        val visible = watch.statusSummary(viewing = session, draftDirectory = session.directory, screen = VoiceScreenVisibility.visible)
        assertEquals("The user is viewing session \"Voice lab\" (id ses_voice, project project, idle).", visible)

        val hidden = watch.statusSummary(viewing = session, needsInput = true, draftDirectory = session.directory, screen = VoiceScreenVisibility.appHidden)
        assertTrue(hidden, hidden.startsWith("The call is attached to session \"Voice lab\" (id ses_voice, project project, idle). It is waiting on a pending request"))
        assertTrue(hidden, hidden.contains("\nThe user can't see the screen right now (the phone is locked or the app is in the background): refer to sessions by name and describe results in words (what changed, the key lines, counts), never \"as you can see\""))
        assertFalse(hidden.contains("is viewing"))

        val draft = watch.statusSummary(viewing = null, draftDirectory = "/Users/kit/game")
        assertTrue(draft, draft.startsWith("The call is on a new, empty session draft in the project folder /Users/kit/game.\nAssume the user isn't looking at the screen"))
        assertEquals(
            "The user is on a new, empty session draft in the project folder /Users/kit/game.",
            watch.statusSummary(viewing = null, draftDirectory = "/Users/kit/game", screen = VoiceScreenVisibility.visible),
        )
    }

    @Test fun theSessionDelegatorsContextLineAssumesTheUserIsntLooking() {
        val assumed = VoiceSessionDelegator.formatContextLine(viewing = session, needsInput = true, draftDirectory = session.directory)
        assertTrue(assumed, assumed.startsWith("[Phone context] Call on session \"Voice lab\" (ses_voice) · needs input · Assume the user isn't looking at the screen"))
        assertEquals(
            "[Phone context] Viewing session \"Voice lab\" (ses_voice) · needs input",
            VoiceSessionDelegator.formatContextLine(viewing = session, needsInput = true, draftDirectory = session.directory, screen = VoiceScreenVisibility.visible),
        )
        val hidden = VoiceSessionDelegator.formatContextLine(viewing = session, needsInput = false, draftDirectory = session.directory, screen = VoiceScreenVisibility.appHidden)
        assertTrue(hidden, hidden.startsWith("[Phone context] Call on session \"Voice lab\" (ses_voice) · The user can't see the screen right now (the phone is locked"))
        val draft = VoiceSessionDelegator.formatContextLine(viewing = null, needsInput = false, draftDirectory = "/Users/kit/game")
        assertTrue(draft, draft.startsWith("[Phone context] Call on new session draft in game · Assume the user isn't looking"))
        assertTrue(VoiceSessionDelegator.activeCallBlock.contains("Assume the user isn't looking at the screen"))
        assertTrue(VoiceSessionDelegator.activeCallBlock.contains("unless the [Phone context] line says \"Viewing\""))
    }

    @Test fun theDelegatorHasAToolForTheUserSayingTheyreLooking() {
        val tool = VoiceDelegator.tools.first { it["name"] == JsonPrimitive("set_user_looking") }
        assertEquals(JsonArray(listOf(JsonPrimitive("looking"))), tool["parameters"]?.jsonObject?.get("required"))
        assertTrue(VoiceDelegator.instructions.contains("Assume the user isn't looking at the screen"))
        assertTrue(VoiceDelegator.instructions.contains("call set_user_looking with looking true"))
        assertTrue(VoiceDelegator.instructions.contains("keep track of which session each request and result belongs to"))
        assertFalse(VoiceDelegator.instructions.contains("the session the user is viewing"))
    }

    /** The voice model's own prompt: away by default, looking only when told. */
    @Test fun thePromptAssumesTheUserIsntLookingAtTheScreen() {
        var context = VoiceInstructions.Context(
            sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(),
            previousEndedAt = null, managesSessions = true, recordsLooking = true,
        )
        val away = listOf(
            VoiceInstructions.buildLive(context),
            VoiceInstructions.build(context, engine = VoiceEngine.realtime),
            VoiceInstructions.build(context, engine = VoiceEngine.grok),
        )
        for (prompt in away) {
            assertTrue(prompt.contains("Screen policy: Assume the user is NOT looking at the screen: they may be walking, driving, cooking, or juggling several things at once."))
            assertTrue(prompt.contains("Only once you're told the user is looking at the screen (an instruction update says so, or they say so) may you refer to what it shows."))
            assertTrue(prompt.contains("The user may run several things at once across sessions: keep track of which session each request and result belongs to, and name that session when you relay a result."))
            assertFalse(prompt.contains("The user's screen follows"))
            assertFalse(prompt.contains("they'll see the draft"))
            assertFalse(prompt.contains("Screen policy: The user said they're looking"))
        }
        assertTrue(VoiceInstructions.buildLive(context).contains("- The user says they're looking at the screen now, or have stopped: delegate it"))

        context = context.copy(userSeesScreen = true)
        for (prompt in listOf(VoiceInstructions.buildLive(context), VoiceInstructions.build(context, engine = VoiceEngine.realtime))) {
            assertTrue(prompt.contains("Screen policy: The user said they're looking at the screen, and it shows the session you're attached to: you may refer to what's on it, but still say results in words."))
            assertFalse(prompt.contains("Assume the user is NOT looking"))
        }

        context = context.copy(recordsLooking = false)
        assertFalse("Only the built-in delegator can record it", VoiceInstructions.buildLive(context).contains("delegate it, so the work knows"))
    }

    /** "The user switched screens themselves" no longer claims they can see it. */
    @Test fun aMoveTheUserMadeIsNamedUnlessTheyreLooking() {
        var context = VoiceInstructions.Context(
            sessionTitle = "HOME", directoryName = "home", recentMessages = emptyList(), previousTail = emptyList(),
            previousEndedAt = null, managesSessions = true,
        )
        val away = VoiceInstructions.buildRetarget(context, engine = VoiceEngine.gptLive)
        assertTrue(away.contains("The user switched screens themselves but may not be looking now: name the session you're on now in a few words, once, without explaining the move."))
        assertFalse(away.contains("can see where they are"))
        context = context.copy(userSeesScreen = true)
        val looking = VoiceInstructions.buildRetarget(context, engine = VoiceEngine.gptLive)
        assertTrue(looking.contains("The user switched screens themselves and is looking at the screen: don't announce or mention the move."))
        context = context.copy(unpromptedMoveReason = "the session was deleted")
        assertTrue(VoiceInstructions.buildRetarget(context, engine = VoiceEngine.gptLive).contains("The call moved because the session was deleted"))
    }

    /**
     * Server Voice's delegator reads what the app shows from its
     * instructions, and GPT-Live hears a short delta: each follows a change,
     * and only a change; the voice model only whether the user sees the screen.
     */
    @Test fun serverVoiceInstructionsFollowWhetherTheUserCanSeeTheScreen() = voiceTest { harness ->
        h = harness
        val voice = h.state { State(server, session) }.live().copy(
            activeRoute = VoiceRoute.serverOpenAI(VoiceRoute.OpenAI.gptLive),
            usesDelegator = true,
        )
        val sent = LockIsolated(listOf<String>())
        val voiceModel = LockIsolated(listOf<Pair<String, String?>>())
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall {
                copy(
                    updateDelegatorInstructions = { text -> sent.append(text) },
                    updateInstructions = { full, delta -> voiceModel.append(full to delta) },
                )
            }
        }
        assertEquals("A call starts assuming the user isn't looking", VoiceScreenVisibility.notLooking, store.state.screenVisibility)

        store.send(Action.AppVisibilityChanged(true))
        store.send(Action.DelegatorNotedLooking(false))
        assertTrue("Nothing changed", sent.value.isEmpty() && voiceModel.value.isEmpty())

        // "Okay, I'm looking now."
        store.send(Action.DelegatorNotedLooking(true)) { it.copy(userLooking = true) }
        // The phone locks.
        store.send(Action.AppVisibilityChanged(false)) { it.copy(isAppVisible = false) }
        // "I'm not looking any more": still out of sight, nothing new to say.
        store.send(Action.DelegatorNotedLooking(false)) { it.copy(userLooking = false) }
        // Unlocked and in front, but they haven't said they're looking.
        store.send(Action.AppVisibilityChanged(true)) { it.copy(isAppVisible = true) }
        store.finish()

        assertEquals(3, sent.value.size)
        assertTrue(sent.value[0].contains("The user is viewing session \"Voice lab\""))
        assertFalse(sent.value[0].contains("can't see the screen"))
        assertTrue(sent.value[1].contains("The call is attached to session \"Voice lab\""))
        assertTrue(sent.value[1].contains("(the phone is locked or the app is in the background)"))
        assertTrue(sent.value[2].contains("The call is attached to session \"Voice lab\""))
        assertTrue(sent.value[2].contains("Assume the user isn't looking at the screen (they haven't said they are)"))

        // GPT-Live hears only the two changes in whether they see it.
        assertEquals(
            listOf(
                "Screen update: the user is looking at the screen now, with the app in front. You may refer to what it shows; still say results in words.",
                "Screen update: the phone is locked or the app is in the background, so the user can't see the screen. Don't refer to it: name sessions and say results in words.",
            ),
            voiceModel.value.map { it.second },
        )
        assertTrue(voiceModel.value[0].first.contains("Screen policy: The user said they're looking"))
        assertTrue(voiceModel.value[1].first.contains("Screen policy: Assume the user is NOT looking"))
    }

    /**
     * A phone-key call's delegator reads it with each request; the voice
     * model hears nothing while the user never said they're looking.
     */
    @Test fun aPhoneKeyCallSendsNothingWhenTheAppComesAndGoes() = voiceTest { harness ->
        h = harness
        val voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        val sent = LockIsolated(listOf<String>())
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall {
                copy(
                    updateDelegatorInstructions = { sent.append("A phone-key call's delegator reads it per request") },
                    updateInstructions = { _, _ -> sent.append("Not looking either way") },
                )
            }
        }
        store.send(Action.AppVisibilityChanged(false)) { it.copy(isAppVisible = false) }
        store.send(Action.AppVisibilityChanged(true)) { it.copy(isAppVisible = true) }
        store.finish()
        assertEquals(emptyList<String>(), sent.value)
    }

    /**
     * A phone-key GPT-Live call tells the voice model when the user says
     * they're looking, and again when the app leaves the front.
     */
    @Test fun aPhoneKeyCallTellsGPTLiveWhenTheUserStartsLooking() = voiceTest { harness ->
        h = harness
        val voice = h.state { State(server, session) }.live().copy(
            activeRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive),
            usesDelegator = true,
        )
        val deltas = LockIsolated(listOf<String?>())
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall { copy(updateInstructions = { _, delta -> deltas.append(delta) }) }
        }
        store.send(Action.DelegatorNotedLooking(true)) { it.copy(userLooking = true) }
        store.send(Action.AppVisibilityChanged(false)) { it.copy(isAppVisible = false) }
        store.send(Action.AppVisibilityChanged(true)) { it.copy(isAppVisible = true) }
        store.send(Action.DelegatorNotedLooking(false)) { it.copy(userLooking = false) }
        store.finish()
        assertEquals(
            listOf(
                VoiceInstructions.screenUpdate(VoiceScreenVisibility.visible),
                VoiceInstructions.screenUpdate(VoiceScreenVisibility.appHidden),
                VoiceInstructions.screenUpdate(VoiceScreenVisibility.visible),
                VoiceInstructions.screenUpdate(VoiceScreenVisibility.notLooking),
            ),
            deltas.value,
        )
    }

    @Test fun aPhoneAtTheEarHidesTheScreenFromACallTheUserIsLookingAt() = voiceTest { harness ->
        h = harness
        val voice = h.state { State(server, session) }.live().copy(
            activeRoute = VoiceRoute.PhoneKey(VoiceEngine.gptLive),
            usesDelegator = true,
            userLooking = true,
        )
        val deltas = LockIsolated(listOf<String?>())
        val store = h.store(voice) {
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            voiceCall { copy(updateInstructions = { _, delta -> deltas.append(delta) }) }
        }
        store.send(Action.AtEarChanged(true)) { it.withAtEar(true) }
        store.send(Action.AtEarChanged(false)) { it.withAtEar(false) }
        store.finish()
        assertEquals(
            listOf(
                VoiceInstructions.screenUpdate(VoiceScreenVisibility.appHidden),
                VoiceInstructions.screenUpdate(VoiceScreenVisibility.visible),
            ),
            deltas.value,
        )
    }

    /**
     * In front and unlocked isn't looking: only once the user says they are
     * does a request say they're viewing the session, until they say they've stopped.
     */
    @Test fun theUserSayingTheyreLookingHoldsUntilTheySayTheyveStopped() = voiceTest { harness ->
        h = harness
        val voice = h.state { State(server, session) }.live().copy(usesDelegator = true)
        val requests = LockIsolated(listOf<String>())
        val store = h.store(voice) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(Instant.EPOCH)
            this[VoiceDelegatorClient] = delegator { _, input, _ ->
                val user = input.firstOrNull() as? VoiceDelegator.InputItem.User
                    ?: return@delegator response("r${requests.value.size}b", text = "Okay.")
                requests.append(user.text)
                when (val count = requests.value.size) {
                    1 -> response("r1", calls = listOf(call("t1", "set_user_looking", """{"looking":true}""")))
                    3 -> response("r3", calls = listOf(call("t3", "set_user_looking", """{"looking":false}""")))
                    else -> response("r$count", text = "Okay.")
                }
            }
            voiceCall { copy(completeFunction = { _, _ -> }) }
        }
        store.exhaustive = false
        store.setAPIKey("sk-test")

        val turns = listOf("Okay I'm looking at the screen now", "Show me the diff", "I'm driving, not looking any more", "What did the tests say")
        for ((index, words) in turns.withIndex()) {
            store.send(Action.CallEvent(VoiceCallEvent.UserTranscript(itemID = "live-u-$index", text = words, final = true)))
            store.send(Action.CallEvent(VoiceCallEvent.FunctionCall(callID = "d$index", name = "delegate", arguments = arguments())))
            store.receive(Action.DelegatorFinished::class)
            assertTrue(store.state.isAppVisible)
            assertEquals(words, index < 2, store.state.userLooking)
        }

        assertEquals(4, requests.value.size)
        assertTrue(requests.value[0].startsWith("The call is attached to session \"Voice lab\""))
        assertTrue(requests.value[0].contains("Assume the user isn't looking at the screen"))
        for (request in requests.value.subList(1, 3)) {
            assertTrue(request, request.startsWith("The user is viewing session \"Voice lab\""))
            assertFalse(request.contains("isn't looking"))
        }
        assertTrue(requests.value[3].startsWith("The call is attached to session \"Voice lab\""))
        assertTrue(requests.value[3].contains("Assume the user isn't looking at the screen"))
        h.skipInFlightEffects()
    }
}
