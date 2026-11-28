package ai.opencode.feature.drawer.catchup

import ai.opencode.feature.transcript.SessionSeenWatermark
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.feature.drawer.CatchUpPreviewStores
import ai.opencode.feature.drawer.HomeFeature
import ai.opencode.feature.drawer.notingUnopenedCopy
import ai.opencode.feature.session.SessionFeature
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.model.OpenCodeHomeSnapshot
import ai.opencode.server.model.OpenCodeSession
import ai.opencode.server.model.OpenCodeTranscriptItem
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DateClient
import ai.opencode.core.dependencies.ImmediateClock
import ai.opencode.core.store.Store
import ai.opencode.core.store.TestStore
import ai.opencode.feature.drawer.HomeLifecycle
import ai.opencode.feature.drawer.HomeSnapshotDate
import ai.opencode.feature.drawer.makeDetail
import ai.opencode.feature.drawer.rememberHomeViewState
import ai.opencode.feature.session.from
import ai.opencode.feature.drawer.openingCatchUpCard
import ai.opencode.feature.drawer.persistingTranscriptCache
import ai.opencode.feature.drawer.publishingTranscript
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeLiveContentEvent
import ai.opencode.server.OpenCodeServerEvent
import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Apart from `CatchUpTests.kt` because the preview's real `Store` needs Android's main looper
 * (Robolectric). Android replaces the preview's state after composition (`commit()`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatchUpPreviewStoresTests {
    private val server = OpenCodeServer(url = "http://localhost:9", username = "", password = "")
    private fun session(id: String, updated: Long) = OpenCodeSession(
        id = id, serverTitle = id, projectID = "p", directory = "/project",
        updatedAt = Instant.ofEpochSecond(updated), idleMilliseconds = 2_000, viewedMilliseconds = 1_000,
    )

    @Test
    fun previewStoresPreserveFirstFrameFlagAcrossSameSessionRefreshesAndResetOnNewSession() = DependencyValues.withValues(DependencyValues.testing()) {
        val newer = session("newer", 20)
        val older = session("older", 10)
        var state = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(older, newer), activeSessionIDs = emptySet()))
        val previews = CatchUpPreviewStores()
        try {
            val store1 = previews.store(newer, state)
            assertFalse(store1.state.hasRenderedFirstContentFrame)
            store1.send(SessionFeature.Action.FirstContentFrameRendered)
            assertTrue(store1.state.hasRenderedFirstContentFrame)

            // Same session refreshed with updated transcript cache preserves hasRenderedFirstContentFrame.
            state = state.copy(transcriptCache = state.transcriptCache + ("newer" to listOf(OpenCodeTranscriptItem.UserText(id = "u1", text = "Updated preview"))))
            val store2 = previews.store(newer, state)
            previews.commit()
            assertSame(store1, store2)
            assertTrue("Same-session cache refresh preserves first-frame state", store2.state.hasRenderedFirstContentFrame)

            // Advancing to a different preview session resets hasRenderedFirstContentFrame for its new transcript.
            val store3 = previews.store(older, state)
            previews.commit()
            assertSame(store1, store3)
            assertFalse(store3.state.hasRenderedFirstContentFrame)
        } finally {
            previews.close()
        }
    }

    @Test fun catchUpPreviewRefreshesWhenPreviewTranscriptIsPrefetchedOrRefreshed() = runTest {
        val now = Instant.ofEpochSecond(100)
        val fixture = CatchUpTests()
        var initial = fixture.home()
        initial = initial.withCatchUp(CatchUpFeature.Stack(queue = listOf("waiting", "newer", "older"), returnSessionID = "read"))
        initial = initial.openingCatchUpCard().first

        val store = TestStore(initial, HomeFeature(), this) {
            this[Clock] = ImmediateClock()
            this[DateClient] = DateClient.constant(now)
            this[HomeSnapshotDate] = DateClient.constant(now)
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                transcript = { _, _, _ -> listOf(OpenCodeTranscriptItem.UserText(id = "u_refreshed", text = "Refreshed preview")) },
            )
        }

        val previews = CatchUpPreviewStores()
        val newer = store.state.sessions.first { it.id == "newer" }
        val preview = previews.store(newer, store.state)
        assertTrue(preview.state.items.isEmpty())
        assertEquals(0, store.state.previewRevision)

        // 1. Prefetching a session behind the preview card ("older") does not bump previewRevision.
        val olderItems = listOf(OpenCodeTranscriptItem.UserText(id = "u_older", text = "Older card"))
        store.send(HomeFeature.Action.TranscriptPrefetched(sessionID = "older", items = olderItems)) {
            store.withDependencies { it.publishingTranscript("older", olderItems).persistingTranscriptCache(including = "older") }
        }
        assertEquals(0, store.state.previewRevision)

        // 2. Prefetching the Catch Up preview session ("newer") bumps previewRevision.
        val prefetchedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u_prefetched", text = "Prefetched preview"))
        store.send(HomeFeature.Action.TranscriptPrefetched(sessionID = "newer", items = prefetchedItems)) {
            store.withDependencies {
                it.publishingTranscript("newer", prefetchedItems).persistingTranscriptCache(including = "newer")
            }.copy(previewRevision = 1)
        }
        assertEquals(1, store.state.previewRevision)
        // The card re-reads its store when the revision changes.
        assertSame(preview, previews.store(newer, store.state))
        previews.commit()
        assertEquals(prefetchedItems, preview.state.items)

        // 3. Refreshing the cached transcript for "newer" bumps previewRevision again.
        val refreshedItems = listOf(OpenCodeTranscriptItem.UserText(id = "u_refreshed", text = "Refreshed preview"))
        store.send(HomeFeature.Action.DrawerRowPressed("newer")) { it.copy(refreshingTranscripts = setOf("newer"), fetchingTranscripts = setOf("newer")) }
        store.receive(HomeFeature.Action.CachedTranscriptRefreshed(sessionID = "newer", items = refreshedItems)) {
            store.withDependencies {
                it.copy(refreshingTranscripts = emptySet(), fetchingTranscripts = emptySet(), transcriptReadAt = it.transcriptReadAt + ("newer" to now))
                    .notingUnopenedCopy("newer")
                    .publishingTranscript("newer", refreshedItems)
                    .persistingTranscriptCache(including = "newer")
            }.copy(previewRevision = 2)
        }
        assertEquals(2, store.state.previewRevision)
        assertSame(preview, previews.store(newer, store.state))
        previews.commit()
        assertEquals(refreshedItems, preview.state.items)
        previews.close()
        store.finish()
    }

    /**
     * The next Catch Up card previews marked as it will open, so it doesn't jump to a divider once
     * it's promoted. Here rather than in `NewMessagesTests` because the preview's `Store` needs the
     * main looper, and the second card's state lands with `commit()` as it does after composition.
     */
    @Test
    fun catchUpPreviewMarksWhatArrivedLikeTheOpenCard() = DependencyValues.withValues(DependencyValues.testing()) {
        fun user(id: String) = OpenCodeTranscriptItem.UserText(id = id, text = id)
        fun reply(id: String) = OpenCodeTranscriptItem.AssistantMarkdown(id = id, text = "Reply $id")
        val next = session("next", 20)
        val copy = session("copy", 10)
        var home = HomeFeature.State(server, OpenCodeHomeSnapshot(projects = emptyList(), sessions = listOf(next, copy), activeSessionIDs = emptySet()))
        val seen = SessionFeature.State.session(server, next, cachedItems = listOf(user("n1")))
        home = home.copy(
            sessionSnapshots = mapOf(next.id to checkNotNull(SessionFeature.SwitchSnapshot.from(seen))),
            transcriptCache = mapOf(next.id to listOf(user("n1"), reply("n2")), copy.id to listOf(user("c1"), reply("c2"))),
            unopenedSeen = mapOf(copy.id to setOf("c1")),
        )
        val previews = CatchUpPreviewStores()
        try {
            val marked = previews.store(next, home).state.newMessages?.itemID
            val unopened = previews.store(copy, home).also { previews.commit() }.state.newMessages?.itemID
            val opened = home.makeDetail(next, cachedItems = home.transcriptCache[next.id]).second.newMessages?.itemID
            assertEquals("n2", opened)
            assertEquals(opened, marked)
            assertEquals("c2", unopened)
        } finally {
            previews.close()
        }
    }

    /**
     * Recreating the activity mid-stream (dark mode, font scale) keeps the live SSE connection on
     * `HomeLifecycle` instead of starting a new socket epoch. A real background-to-foreground
     * return after recreation still reconnects.
     */
    @Test
    fun activityRecreationKeepsLiveStreamAndContinuesStreamingReply() {
        val now = Instant.ofEpochSecond(100)
        val streaming = session("ses_stream", 20)
        val snapshot = OpenCodeHomeSnapshot(
            projects = emptyList(),
            sessions = listOf(streaming),
            activeSessionIDs = setOf(streaming.id),
        )
        var subscriptions = 0
        val stream = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
        val deps = DependencyValues.testing().let { base ->
            DependencyValues.withValues(base) {
                DependencyValues.current
                    .with(Clock, ImmediateClock())
                    .with(DateClient, DateClient.constant(now))
                    .with(HomeSnapshotDate, DateClient.constant(now))
                    .with(
                        OpenCodeClient,
                        DependencyValues.current[OpenCodeClient].copy(
                            homeSnapshot = { snapshot },
                            getSession = { _, _ -> streaming },
                            transcript = { _, _, _ -> emptyList() },
                            events = {
                                subscriptions += 1
                                stream.receiveAsFlow()
                            },
                        ),
                    )
            }
        }
        val initial = DependencyValues.withValues(deps) {
            val base = HomeFeature.State(server, snapshot)
            val (next, detail) = base.makeDetail(streaming, cachedItems = emptyList())
            next.copy(sessionDetail = detail)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = Store(initial, HomeFeature(), dependencies = deps, scope = scope)
        val app = RuntimeEnvironment.getApplication()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                (activity as? ComponentActivity)?.setContent {
                    val view = rememberHomeViewState(store, initiallyOpenDrawer = false)
                    HomeLifecycle(store, view)
                }
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        fun pump() = repeat(4) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)) }
        app.registerActivityLifecycleCallbacks(callbacks)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        try {
            controller.setup()
            pump()
            assertEquals("Initial launch opens exactly one event stream", 1, subscriptions)

            stream.trySend(OpenCodeServerEvent.Connected)
            pump()

            store.send(
                HomeFeature.Action.detail(
                    SessionFeature.Action.ContentBatch(
                        sessionID = streaming.id,
                        events = listOf(
                            OpenCodeLiveContentEvent.TextStarted(messageID = "msg_1", ordinal = 0),
                            OpenCodeLiveContentEvent.TextDelta(messageID = "msg_1", ordinal = 0, delta = "Line 1\n"),
                        ),
                    ),
                ),
            )
            pump()
            assertEquals("Line 1\n", store.state.sessionDetail?.liveText?.get("msg_1-text-0"))

            // Simulate dark/light or font-scale recreation mid-stream.
            controller.recreate()
            pump()
            assertEquals("Activity recreation must not reconnect the event stream", 1, subscriptions)
            assertEquals("In-flight liveText accumulator survives activity recreation", "Line 1\n", store.state.sessionDetail?.liveText?.get("msg_1-text-0"))

            // Subsequent streamed deltas continue updating the reply immediately instead of freezing.
            store.send(
                HomeFeature.Action.detail(
                    SessionFeature.Action.ContentBatch(
                        sessionID = streaming.id,
                        events = listOf(OpenCodeLiveContentEvent.TextDelta(messageID = "msg_1", ordinal = 0, delta = "Line 2")),
                    ),
                ),
            )
            pump()
            val reply = store.state.sessionDetail?.items?.filterIsInstance<OpenCodeTranscriptItem.AssistantMarkdown>()?.singleOrNull()
            assertEquals("Line 1\nLine 2", reply?.text)

            // A real background-to-foreground cycle still reconnects the stream.
            controller.pause().stop()
            pump()
            controller.start().resume()
            pump()
            assertEquals("Returning from the background reconnects the stream", 2, subscriptions)
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
            controller.pause().stop().destroy()
            store.close()
            scope.cancel()
            stream.close()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
