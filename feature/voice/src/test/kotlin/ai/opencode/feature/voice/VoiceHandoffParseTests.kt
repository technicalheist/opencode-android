package ai.opencode.feature.voice

import ai.opencode.server.model.OpenCodeFileReference
import ai.opencode.server.model.OpenCodePromptPresentation
import ai.opencode.server.model.OpenCodeTranscriptItem
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceHandoffParseTests {
    @Test fun readsBackWhatFormatWrote() {
        val prompt = VoiceHandoff.format(instruction = "Check why the upload test is flaky.", transcriptDelta = listOf("user: the upload test", "assistant: On it."))
        assertEquals(
            VoiceHandoff.Delegation(
                instruction = "Check why the upload test is flaky.",
                transcriptDelta = listOf("user: the upload test", "assistant: On it."),
            ),
            VoiceHandoff.parse(prompt),
        )
    }

    @Test fun roundTripsTheVoiceAgentsFraming() {
        val prompt = VoiceHandoff.format(
            instruction = "um can you check why the upload test fails on staging",
            transcriptDelta = listOf("user: um can you check why the upload test fails on staging", "assistant: Checking the upload test."),
            context = "Session \"Uploads\" in project \"app\".",
            framing = " Checking the upload test. ",
        )
        val delegation = VoiceHandoff.parse(prompt)
        assertEquals(
            VoiceHandoff.Delegation(
                instruction = "um can you check why the upload test fails on staging",
                transcriptDelta = listOf("user: um can you check why the upload test fails on staging", "assistant: Checking the upload test."),
                context = "Session \"Uploads\" in project \"app\".",
                framing = "Checking the upload test.",
            ),
            delegation,
        )
        assertEquals("Checking the upload test.", delegation?.displayText)
    }

    @Test fun withoutFramingTheSessionShowsTheUsersWords() {
        val prompt = VoiceHandoff.format(instruction = "Open a PR.", transcriptDelta = emptyList(), framing = "  ")
        assertFalse(prompt.contains("\"voice\""))
        assertNull(VoiceHandoff.parse(prompt)?.framing)
        assertEquals("Open a PR.", VoiceHandoff.parse(prompt)?.displayText)
    }

    @Test fun theHeaderSaysTheRequestIsTheUsersOwnWords() {
        val header = VoiceHandoff.header
        assertTrue(header.contains("Instruction is the user's own spoken words since the previous delegation"))
        assertTrue(header.contains("recognition errors and disfluencies"))
        assertTrue(header.contains("Voice, when present, is how the voice agent framed the request."))
        assertTrue(header.contains("Transcript delta is the surrounding voice conversation, both sides, for context"))
        assertFalse(header.contains("cleaned interpretation"))
    }

    @Test fun aContinuedDelegationIsShortAndStillAVoiceMessage() {
        val prompt = VoiceHandoff.format(
            instruction = "and the retry test",
            transcriptDelta = listOf("user: and the retry test"),
            framing = "Checking the retry test.",
            header = VoiceHandoff.Header.continued,
        )
        assertTrue(prompt.startsWith(VoiceHandoff.continuedHeader + "\n{"))
        assertFalse("The explanation goes only with the first", prompt.contains("recognition errors"))
        assertEquals(
            VoiceHandoff.Delegation(
                instruction = "and the retry test",
                transcriptDelta = listOf("user: and the retry test"),
                framing = "Checking the retry test.",
            ),
            VoiceHandoff.parse(prompt),
        )
        // `TranscriptUserMessage.voiceDelegation` is asserted in :feature:transcript's port.
        assertEquals(
            listOf("user: and the retry test"),
            VoiceWorkClient.formatRecentMessages(listOf(OpenCodeTranscriptItem.UserText(id = "msg_2", text = prompt))),
        )
    }

    @Test fun recentMessagesGiveTheVoiceAgentTheWordsNotThePrompt() {
        val prompt = VoiceHandoff.format(instruction = "check the upload test", transcriptDelta = emptyList(), framing = "Checking the upload test.")
        assertEquals(
            listOf("user: check the upload test"),
            VoiceWorkClient.formatRecentMessages(listOf(OpenCodeTranscriptItem.UserText(id = "msg_1", text = prompt))),
        )
    }

    @Test fun toleratesARewordedHeaderAndAMissingDelta() {
        val prompt = "Voice delegation. Some newer wording.\n{\"instruction\": \"  Open a PR.  \"}"
        assertEquals(VoiceHandoff.Delegation(instruction = "Open a PR.", transcriptDelta = emptyList()), VoiceHandoff.parse(prompt))
    }

    @Test fun malformedPromptsStayAsTheyAre() {
        assertNull(VoiceHandoff.parse("Voice delegation. Header only"))
        assertNull(VoiceHandoff.parse("Voice delegation. Header\n{\"instruction\": "))
        assertNull(VoiceHandoff.parse("Voice delegation. Header\n{\"transcript_delta\": []}"))
        assertNull(VoiceHandoff.parse("Voice delegation. Header\n{\"instruction\": \"   \"}"))
    }

    @Test fun plainTextAboutVoiceDelegationIsNotOne() {
        assertNull(VoiceHandoff.parse("How does voice delegation work?\n{\"instruction\": \"x\"}"))
        assertNull(VoiceHandoff.parse("Can you explain the Voice delegation. header format?"))
        assertNull(VoiceHandoff.parse("Voice delegation (continued) is what?\n{\"instruction\": \"x\"}"))
    }

    @Test fun recentMessagesExtractUserWordsFromStoredVoiceDelegationPrompts() {
        val delegated = VoiceHandoff.format(
            instruction = "Check why the upload test is flaky in OpenCodeTests.",
            transcriptDelta = listOf("user: Check why the upload test is flaky in OpenCodeTests."),
            context = "Session \"Voice lab\" in project \"opencode-ios\".",
        )
        val items = listOf(
            OpenCodeTranscriptItem.UserText(id = "m1", text = delegated),
            OpenCodeTranscriptItem.AssistantMarkdown(id = "m2", text = "It races the progress callback."),
            OpenCodeTranscriptItem.UserText(id = "m3", text = "Typed follow-up from the keyboard."),
        )
        val recent = VoiceWorkClient.formatRecentMessages(items)
        assertEquals(
            listOf(
                "user: Check why the upload test is flaky in OpenCodeTests.",
                "assistant: It races the progress callback.",
                "user: Typed follow-up from the keyboard.",
            ),
            recent,
        )

        val context = VoiceInstructions.Context(
            sessionTitle = "Voice lab",
            directoryName = "opencode-ios",
            recentMessages = recent,
            previousTail = emptyList(),
            previousEndedAt = null,
        )
        for (engine in VoiceEngine.entries) {
            val startPrompt = VoiceInstructions.build(context, engine = engine)
            assertTrue(startPrompt.contains("user: Check why the upload test is flaky in OpenCodeTests."))
            assertFalse(startPrompt.contains(VoiceHandoff.header))
            val retargetPrompt = VoiceInstructions.buildRetarget(context, engine = engine)
            assertTrue(retargetPrompt.contains("user: Check why the upload test is flaky in OpenCodeTests."))
            assertFalse(retargetPrompt.contains(VoiceHandoff.header))
        }
    }

    @Test fun parseToleratesTrailingAttachedFileReferencesAndDetectsCallLogAttachment() {
        val file = OpenCodeFileReference(
            name = "voice-call-11111111-2222-3333-4444-555555555555.json",
            mime = "application/json",
            path = "/tmp/uploads/1/voice-call-11111111-2222-3333-4444-555555555555.json",
        )
        val formatted = VoiceHandoff.format(
            instruction = "Investigate why the voice completion notice was missed.",
            transcriptDelta = listOf("user: tell the iOS session to investigate"),
            callLogAttached = true,
        )
        val modelText = OpenCodePromptPresentation.modelText(formatted, files = listOf(file))
        val parsed = VoiceHandoff.parse(modelText)
        assertEquals("Investigate why the voice completion notice was missed.", parsed?.instruction)
        assertEquals(true, parsed?.callLogAttached)
        assertEquals("Investigate why the voice completion notice was missed.", parsed?.displayText)
        // `TranscriptUserMessage.voiceDelegation` with the transcript file is asserted in :feature:transcript's port.
    }
}

class VoiceFramingTests {
    @Test fun anAcknowledgementThatNamesTheTaskIsTheFraming() {
        for (ack in listOf(
            "Checking the upload test.", "Running unit tests.", "Checking the README.", "Renaming fetchUser.",
            "Checking the session drawer.", "Check why the upload test fails on staging",
        )) {
            assertEquals(ack, ack, VoiceHandoff.framing(ack))
        }
    }

    /** What GPT-Live said before it was asked to name the task, from the live timing runs. */
    @Test fun aVagueAcknowledgementFallsBackToTheUsersWords() {
        for (ack in listOf(
            "On it.", "Sure, I'll take a look.", "Alright, I'll get on it.", "Okay, I'll look into it.",
            "Okay, I'll get right on it.", "I'll take a look.", "Sure, I'll see what's there.", "Checking that now.",
            "Looking that up now.", "Working on that.", "Sure", "  ", "",
            "Let me look into that.", "Checking.", "I'm checking on that.", "Let me dig into that.",
            "Checking your sessions.", "Checking with the team.", "Sending that to the session.",
            "Yeah, I heard you.", "Yeah, good call.", "Still digging into that.", "Yeah.", "Makes sense, noted.",
        )) {
            assertNull(ack, VoiceHandoff.framing(ack))
        }
        assertNull(VoiceHandoff.framing(null))
        assertNull("Curly apostrophes too", VoiceHandoff.framing("Sure, I’ll take a look."))
    }
}

class VoiceSteeringInstructionsTests {
    private val context = VoiceInstructions.Context(
        sessionTitle = "Upload test", directoryName = "app", recentMessages = emptyList(),
        previousTail = emptyList(), previousEndedAt = null,
    )

    @Test fun runningWorkNeverBlocksAFollowUpDelegation() {
        for (engine in VoiceEngine.entries) {
            val text = VoiceInstructions.build(context, engine = engine)
            assertTrue(text.contains("Never refuse because something is running"))
            assertTrue(text.contains("Asking how it's going, to hurry, to wrap up with what it has, or to change course is a new delegation"))
            assertFalse("Steering is how the call changes running work", text.contains("interrupt its work"))
            assertFalse("A nudge or status check must not read as a repeat", text.contains("already in flight"))
        }
    }

    @Test fun personalMessagesActLikeASecretaryForBothEngines() {
        for (engine in VoiceEngine.entries) {
            val text = VoiceInstructions.build(context, engine = engine)
            assertTrue(text.contains("# Personal messages"))
            assertTrue(text.contains("act like a secretary: preserve the user's meaning, tone, intent, profanity, and exact wording, verbatim when asked"))
            assertTrue(text.contains("Never invent content, moralize, or rewrite it into counseling or therapeutic language"))
            assertTrue(text.contains("Ordinary profanity or criticism isn't a reason to refuse"))
            assertTrue(text.contains("If the dictation is incomplete, ask for the missing words"))
            assertTrue(text.contains("Drafting is not sending: delegate the user's exact words to the session and say the draft and its destination come back to them before anything is sent"))
            assertTrue(text.contains("Only if a real safety limit applies, name the narrow issue and offer a clearly labeled adjustment for the user's approval, never a silent substitution"))
        }
    }

    /**
     * A call once acknowledged with "Checking with the team." and promised
     * "the team will fix it": there is no team, only OpenCode's sessions.
     */
    @Test fun acknowledgementsAreBriefAndNeverMentionATeamOrBackend() {
        val managed = context.copy(managesSessions = true)
        for (context in listOf(context, managed)) {
            for (engine in VoiceEngine.entries) {
                val text = VoiceInstructions.build(context, engine = engine)
                assertTrue(text.contains("No human team exists: never say \"the team\", \"the backend\", \"relay\", \"passing it along\", \"have them\", or \"someone will\", never call a session \"they\" or \"them\", and never imply contacting people a tool didn't message."))
                // "backend" appears only in that ban, never as something to echo.
                assertEquals("$engine", 2, text.split("backend").size)
                assertFalse(text.contains("Backend"))
                assertFalse(text.contains("OpenCode iOS"))
                // A natural reaction is described, never quoted: GPT-Live repeats a quoted phrase.
                assertTrue(
                    "$engine",
                    text.contains("react the way a friend on the phone would: quickly and naturally, to what the user just said") ||
                        text.contains("react once the way a friend on the phone would: quickly and naturally, to what the user just said"),
                )
                // A rambled request's acknowledgement says what was understood, as a statement, not
                // a question.
                assertTrue("$engine", text.contains("Name the specific thing you understood them to want, in your own words, so they hear how you read it; this matters most when they rambled or circled. Say it in the first person as work you're starting, not as done and not as asking, sending, or handing it to anyone, unless the user asked for exactly that; never \"Done\". Only a result decides what happened. Never use the same stock word or phrase twice in a row"))
                assertFalse("$engine", text.contains("asking for the check-in"))
                assertTrue("$engine", text.contains("A result's own words set the claim. \"Asked\", \"started\" or \"will\" stays that; never upgrade it to \"done\", and never add a date, deadline or urgency the result didn't say."))
                assertFalse("$engine", text.contains("It can mention the thing itself."))
                assertTrue(
                    "$engine",
                    !text.contains("\"Checking.\"") && !text.contains("\"Opening.\"") &&
                        !text.contains("\"Let me look into that.\"") && !text.contains("\"I'll take a look.\""),
                )
                assertTrue("$engine", !text.contains("two to four words") && !text.contains("never a bare"))
            }
        }
        val live = VoiceInstructions.build(context, engine = VoiceEngine.gptLive)
        assertTrue(live.contains("When the user is mid-thought or it's a quick follow-up, a simple yeah or nothing at all is fine."))
        // A long ask ending "Are you listening or no" got "Listening. I've got that." and nothing
        // about the ask.
        assertTrue(live.contains("After a long turn, even one that ends by asking whether you're there, name what they asked for; a stock line that names nothing doesn't cover it."))
        assertTrue(live.contains("- Say it once, without saying how the request will be framed or passed on"))
        // One place for what's said around a delegation.
        assertEquals(2, live.split("What you say around a delegation:").size)
        assertFalse(live.contains("Never say the same thing twice"))
        val realtime = VoiceInstructions.build(context, engine = VoiceEngine.realtime)
        assertTrue(realtime.contains("for a quick follow-up a simple yeah is enough. Never start work in silence."))
    }

    @Test fun requestsKeepTheirAudienceAndResultsNameTheConcreteAction() {
        val managed = context.copy(managesSessions = true)
        for (context in listOf(context, managed)) {
            for (engine in VoiceEngine.entries) {
                val text = VoiceInstructions.build(context, engine = engine)
                assertTrue("$engine", text.contains("\"relay\"") && text.contains("\"have them\"") && text.contains("never call a session \"they\" or \"them\""))
                assertTrue("$engine", text.contains("without saying how the request will be framed or passed on"))
                assertTrue("$engine", text.contains("(\"Saved a private note in your notes\", \"Sent that to the OPENCODE IOS session\")"))
            }
        }
        val delegator = VoiceDelegator.instructions
        assertTrue(delegator.contains("If a session says it will do something or hasn't yet, your reply says it's started, not done."))
        assertTrue(delegator.contains("keeping the user's exact intent, audience, and destination: a note for themselves stays a private note (never feedback for a team or a message to anyone)"))
        assertTrue(delegator.contains("never \"relay\" or \"pass along\", and never implying you contacted people you didn't"))
        val send = VoiceDelegator.tools.firstOrNull { it["name"] == JsonPrimitive("send_message") }
        val description = send?.get("description")?.jsonPrimitive?.content ?: ""
        assertTrue(description.contains("when the user reports a bug"))
        assertFalse(description.contains("relaying"))
        // The attribution is described, never given as a sentence to repeat.
        assertFalse(VoiceHandoff.earlierRequestLead.contains("\""))
        assertFalse(VoiceHandoff.earlierRequestLead.contains("upload tests"))
        assertEquals("This answers an earlier request, not what the user said last; open by saying it's about that earlier request and naming it, then give the result. ", VoiceHandoff.earlierRequestLead)
        assertTrue(delegator.contains("when a wait returns user_spoke, answer at once without waiting again"))
        val wait = VoiceDelegator.tools.firstOrNull { it["name"] == JsonPrimitive("wait_for_session") }
        val waitDescription = wait?.get("description")?.jsonPrimitive?.content ?: ""
        assertTrue(waitDescription.contains("ending early if the user starts talking"))
        assertTrue(delegator.contains("A session waiting on a permission or question takes no steering"))
        assertTrue(delegator.contains("Never answer, grant, or dismiss a request the user didn't speak to."))
        assertTrue(delegator.contains("A draft waiting for the user's OK hasn't been sent."))
        assertTrue(description.contains("the result is not_sent with its pending requests"))
        for (engine in VoiceEngine.entries) {
            val text = VoiceInstructions.build(context, engine)
            assertTrue("$engine", text.contains("Something is done (sent, posted, merged, deleted, approved) only when a result says it happened."))
            assertTrue("$engine", text.contains("The user's OK on a draft (\"yes\", \"send it\") is a new request"))
        }
    }

    /**
     * The voice once tacked a "don't put food down the drain" warning onto a
     * Waymo answer and got defensive when teased.
     */
    @Test fun theVoiceIsARelaxedPeerWithoutLectures() {
        val live = VoiceInstructions.build(context, engine = VoiceEngine.gptLive)
        assertTrue(live.contains("You are OpenCode, a relaxed, friendly voice coding assistant."))
        assertTrue(live.contains("Talk like a sharp friend on the phone: relaxed, direct, a little dry, at a natural pace. Keep your own replies to one or two short sentences, then stop; relayed session results keep their full facts."))
        assertTrue(live.contains("Relay the session's replies faithfully, in order: keep every fact, drop only code, IDs, paths and URLs, add nothing. Anything beyond what a result says (the next item, a detail, a number) and any decision on it goes to `delegate`; if you were interrupted, answer what the user just said, and give the rest only if asked."))
        assertTrue(live.contains("goes to `delegate` unless a completed result on this call directly answers it"))
        assertTrue(live.contains("Treat the user as a capable adult: no unsolicited warnings, safety tips, reminders about obvious everyday things, moralizing, or lectures. Speak up only for a serious, immediate risk they seem to have missed."))
        assertTrue(live.contains("If the user is frustrated, acknowledge it briefly and get to the fix. If they tease you, take it in stride and riff back; never get defensive or passive-aggressive. Don't fawn or agree just to please; disagree plainly when they're wrong."))
        assertTrue(!live.contains("calm, friendly") && !live.contains("Speak warmly"))

        val realtime = VoiceInstructions.build(context, engine = VoiceEngine.realtime)
        assertTrue(
            realtime.contains(
                """
                # Personality & Tone

                - Relaxed, warm, direct, a little dry: a capable peer on the phone, not a customer-service agent. Keep your own replies to one or two short sentences, then stop—no fluff, no play-by-play; relayed session results keep their full facts.
                - No unsolicited warnings, safety tips, reminders about obvious everyday things, moralizing, or lectures. Speak up only for a serious, immediate risk the user seems to have missed.
                - Take teasing in stride and riff back; never get defensive or passive-aggressive. Don't fawn or agree just to please; disagree plainly when the user is wrong.
                - Relay the session's replies faithfully, in order: keep every fact, drop only code, IDs, paths and URLs, add nothing. Anything beyond what a result says (the next item, a detail, a number) and any decision on it goes to `delegate`; if you were interrupted, answer what the user just said, and give the rest only if asked.
                - Explain failures briefly and offer one retry or alternative.
                """.trimIndent(),
            ),
        )
        assertTrue(realtime.contains("any status or outcome question goes to `delegate` unless a completed result on this call directly answers it"))
        assertTrue(VoiceSessionDelegator.activeCallBlock.contains("Next is X; keep, drop, or note?"))
        // Short by default, long when asked.
        assertTrue(VoiceSessionDelegator.activeCallBlock.contains("Keep it short by default"))
        assertTrue(VoiceSessionDelegator.activeCallBlock.contains("When they ask for detail, an explanation, a walkthrough, or to be taught something, answer at whatever length it needs"))
        assertFalse(VoiceSessionDelegator.activeCallBlock.contains("1–3"))
        assertFalse(realtime.contains("Warm, witty, concise."))
        // The acknowledgements stay as brief and natural as the rest of the voice.
        for (text in listOf(live, realtime)) {
            assertTrue(text.contains("the way a friend on the phone would: quickly and naturally, to what the user just said"))
        }
        assertTrue(VoiceHandoff.header.contains("answer only what was asked, with no unrequested tips"))
    }

    @Test fun theSessionAnswersAFollowUpPromptly() {
        val prompt = VoiceHandoff.format(instruction = "How's it going?", transcriptDelta = emptyList())
        assertTrue(prompt.contains("a clear status update, the finished result if you have enough, or the precise blocker or input you need"))
        assertEquals("How's it going?", VoiceHandoff.parse(prompt)?.instruction)
    }

    @Test fun modelIdentityNamesTheActiveVoiceEngineAndAvoidsGuessing() {
        val live = VoiceInstructions.build(context, engine = VoiceEngine.gptLive)
        assertTrue(live.contains("You are GPT-Live (gpt-live-1.1)"))
        assertTrue(live.contains("that is your voice layer, inside OpenCode, whose sessions do the coding"))
        assertTrue(live.contains("Questions about GPT-Live, Realtime, or which voice model or provider you are refer to this voice engine: answer directly that you are GPT-Live (gpt-live-1.1)"))
        assertTrue(live.contains("say unknown rather than guessing"))

        val realtime = VoiceInstructions.build(context, engine = VoiceEngine.realtime)
        assertTrue(realtime.contains("the Realtime model (gpt-realtime-2.1)"))
        assertTrue(realtime.contains("(gpt-realtime-2.1), inside OpenCode, whose sessions do the coding"))
        assertTrue(realtime.contains("Questions about GPT-Live, Realtime, Grok, or which voice model or provider you are refer to this voice engine: answer directly that you are the Realtime model (gpt-realtime-2.1)"))
        assertTrue(realtime.contains("say unknown rather than guessing"))

        // Grok Voice shares Realtime's instructions but names itself.
        val grok = VoiceInstructions.build(context, engine = VoiceEngine.grok)
        assertTrue(grok.contains("Your voice layer is Grok Voice (grok-voice-3.0) from xAI, inside OpenCode, whose sessions do the coding."))
        assertTrue(grok.contains("answer directly that you are Grok Voice (grok-voice-3.0) from xAI."))
        assertFalse(grok.contains("gpt-realtime"))
        assertEquals("A move replaces the whole instructions, as on Realtime", grok, VoiceInstructions.buildRetarget(context, engine = VoiceEngine.grok))
    }

    @Test fun capabilityDiscoveryDelegatesUnknownCapabilitiesWithoutAskingUserToPaste() {
        for (engine in VoiceEngine.entries) {
            val text = VoiceInstructions.build(context, engine = engine)
            assertTrue(text.contains("you don't know whether the session can do it"))
            assertTrue(text.contains("delegate it to the session to find out and use its tools"))
            assertTrue(text.contains("Report a limitation only after the session reports a real one, describing the narrow blocker"))
            assertTrue(text.contains("never ask the user to paste data the session may be able to read"))
            assertTrue(text.contains("never claim access or success you didn't get"))
            assertTrue(text.contains("never bypass the session's approval gates"))
        }
    }

    @Test fun fragmentsWaitForCompleteUtteranceAndNeverGuess() {
        for (engine in VoiceEngine.entries) {
            val text = VoiceInstructions.build(context, engine = engine)
            assertTrue(text.contains("Wait for a complete utterance."))
            assertTrue(text.contains("If a request meant for you is a fragment or unclear, say briefly that you didn't catch it and ask the user to finish or repeat once"))
            assertTrue(text.contains("never answer or delegate a speculative interpretation of a fragment"))
        }
    }

    @Test fun clarificationsStatusChecksRepeatedReadsAndAmbiguousWritesHaveDistinctRules() {
        for (engine in VoiceEngine.entries) {
            val text = VoiceInstructions.build(context, engine = engine)
            assertTrue(text.contains("Clarifications steer the existing task; status requests ask the session for its state; a repeated read-only request is just delegated again."))
            assertTrue(text.contains("Never say \"I can't submit the same request again while it's running\"."))
            assertTrue(text.contains("Only for an external write with an uncertain outcome (a send, post, or purchase), delegate a status check first and retry the write only if it didn't happen."))
            assertTrue(text.contains("Keep any status to one short line—no repeated \"it's still running\" warnings, no \"just wait\", and no \"open the session\" unless the session is blocked on an approval or question only the user can answer there."))
        }
    }
}
