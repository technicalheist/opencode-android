package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.OpenCodeServerEvent
import ai.opencode.server.ServerLinkClient
import ai.opencode.server.ServerLinkReset
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Collections

/** Which of Home's connection actions start connections over before the stream reconnects. */
class ServerLinkResetTriggerTests {
    @get:Rule val isolation = TestIsolationRule()

    private val server = OpenCodeServer(url = "https://home.example", username = "", password = "")

    /** A reroute in the background (a call going on) doesn't reconnect the stream there, but what runs meanwhile gets fresh pools and races again. */
    @Test fun aRerouteInTheBackgroundStillStartsConnectionsOver() = runTest {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val state = HomeConnectionFeature.State(server = server, isStreaming = true, isEventStreamLive = true, isInBackground = true)
        val store = TestStore(state, HomeConnectionFeature(), this) {
            this[ServerLinkClient] = this[ServerLinkClient].copy(
                resetConnections = { reason -> calls += "reset ${reason.rawValue}" },
                invalidate = { _, failing -> calls += "forget ${failing ?: "any"}" },
            )
            this[OpenCodeClient] = this[OpenCodeClient].copy(
                events = { throw AssertionError("No stream in the background") },
            )
        }
        store.send(HomeConnectionFeature.Action.NetworkRerouted) { it.copy(isEventStreamLive = false) }
        store.finish()
        assertEquals(listOf("reset reroute", "forget any"), calls.toList())
    }

    /** Home's snapshot and an open screen's resume reads start in the same turn, so the reset can't wait for the stream's effect. */
    @Test fun eachTriggerStartsConnectionsOverWhileReducing() {
        val cases = listOf(
            HomeConnectionFeature.Action.NetworkRerouted to ServerLinkReset.Reroute,
            HomeConnectionFeature.Action.RetryConnectionTapped to ServerLinkReset.Retry,
            HomeConnectionFeature.Action.SceneBecameActive to ServerLinkReset.Foreground,
        )
        for (isStreaming in listOf(true, false)) {
            for ((action, reason) in cases) {
                val calls = mutableListOf<String>()
                val state = HomeConnectionFeature.State(
                    server = server,
                    isStreaming = isStreaming,
                    isInBackground = action == HomeConnectionFeature.Action.SceneBecameActive,
                )
                withDependencies({
                    this[ServerLinkClient] = this[ServerLinkClient].copy(
                        resetConnections = { received -> calls += "reset ${received.rawValue}" },
                        invalidate = { _, failing -> calls += "forget ${failing ?: "any"}" },
                    )
                }) { HomeConnectionFeature().reduce(state, action) }
                val expected = if (isStreaming) listOf("reset ${reason.rawValue}", "forget any") else emptyList()
                assertEquals("$action streaming=$isStreaming", expected, calls)
            }
        }
    }

    @Test fun eachTriggerResetsBeforeTheStreamReconnects() = runTest {
        val cases = listOf(
            HomeConnectionFeature.Action.NetworkRerouted to ServerLinkReset.Reroute,
            HomeConnectionFeature.Action.RetryConnectionTapped to ServerLinkReset.Retry,
            HomeConnectionFeature.Action.SceneBecameActive to ServerLinkReset.Foreground,
        )
        for ((action, reason) in cases) {
            val order = Collections.synchronizedList(mutableListOf<String>())
            val state = HomeConnectionFeature.State(
                server = server,
                isStreaming = true,
                isInBackground = action == HomeConnectionFeature.Action.SceneBecameActive,
            )
            val store = TestStore(state, HomeConnectionFeature(), this) {
                this[Clock] = TestClock()
                this[ServerLinkClient] = this[ServerLinkClient].copy(resetConnections = { received -> order += "reset ${received.rawValue}" })
                this[OpenCodeClient] = this[OpenCodeClient].copy(
                    events = {
                        order += "stream"
                        val channel = Channel<OpenCodeServerEvent>(Channel.UNLIMITED)
                        channel.trySend(OpenCodeServerEvent.Connected)
                        channel.consumeAsFlow()
                    },
                )
            }
            store.exhaustive = false
            store.send(action)
            store.receive({ it is HomeConnectionFeature.Action.ServerConnected }, "serverConnected")
            assertEquals("$action", listOf("reset ${reason.rawValue}", "stream"), order.toList())
            store.skipInFlightEffects()
        }
    }
}
