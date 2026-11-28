package ai.opencode.feature.session

import ai.opencode.core.dependencies.dependency
import ai.opencode.core.motion.rememberReduceMotion
import ai.opencode.core.ui.OpenCodeTheme
import ai.opencode.feature.session.agentactivity.GyroCell
import ai.opencode.feature.session.agentactivity.GyroField
import ai.opencode.feature.session.agentactivity.ShellGlyphModels
import ai.opencode.feature.session.agentactivity.drawAgentActivityOrb
import ai.opencode.server.OpenCodeClient
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.retry.RetryPolicy
import ai.opencode.server.retry.withRetryPolicy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive

// The meter, its client and the probe type are in `ShellOutputMeter.kt`; the engine in
// `agentactivity/ShellPromptGlyph.kt`.

/**
 * `OpenCodeClient.shellOutput` takes an `Int` cursor, so the probe reads from `Int.MAX_VALUE`,
 * which is equally past any capture: the page is empty and carries the capture's size.
 */
private const val END_OF_CAPTURE_CURSOR = Int.MAX_VALUE

/**
 * The shell glyph: a `>` prompt on the subagent orb's LED disc whose chevron laps with momentum
 * driven by the shell's output rate (`ShellPromptGlyph`, R1 Glide). Steady output laps about once
 * every 1.5 s; a burst whips it round and it coasts down over a couple of laps. Reduce Motion shows
 * the still prompt. It's decorative: the row beside it names the shell.
 *
 * @param shellIDs the shells whose output drives it (the capsule sums several).
 * @param key keeps the motion across remounts; defaults to the shells.
 * @param probe reads the capture's size while visible: every second, every 3 s once all have sat
 *   unchanged for half a minute. Without one, the glyph follows whatever else reads the shell (its
 *   output sheet).
 * @param isPaused hidden behind something that stands in for it: hold the pose, draw nothing new.
 */
@Composable
fun ShellGlyph(
    shellIDs: List<String>,
    modifier: Modifier = Modifier,
    key: String? = null,
    probe: ShellGlyphProbe? = null,
    size: Dp = 18.dp,
    isPaused: Boolean = false,
) {
    val modelKey = key ?: ("shell|" + shellIDs.joinToString(","))
    val model = remember(modelKey) { ShellGlyphModels.model(modelKey) }
    val still = rememberReduceMotion() || rememberTouchExplorationEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val active = !isPaused && lifecycle.isAtLeast(Lifecycle.State.RESUMED) && !still
    val dark = OpenCodeTheme.colors.isDark
    val currentIDs by rememberUpdatedState(shellIDs)
    var tick by remember { mutableLongStateOf(0L) }
    val field = remember(model) { arrayOfNulls<GyroField>(1) }

    LaunchedEffect(model, active, still) {
        fun draw(now: Double) {
            field[0] = model.advance(now, currentIDs, still)
            tick += 1
        }
        if (!active) {
            // Held (or Reduce Motion's still prompt): redrawn only when the shells change.
            snapshotFlow { currentIDs }.collect { draw(AgentActivityClock.now()) }
            return@LaunchedEffect
        }
        var lastDrawn = Double.NEGATIVE_INFINITY
        while (true) {
            withFrameNanos { }
            val now = AgentActivityClock.now()
            if (now - lastDrawn < GLYPH_FRAME_INTERVAL - 0.002) continue
            lastDrawn = now
            val seen = model.meter.growth.value
            draw(now)
            if (model.isResting(now, currentIDs)) {
                val ids = currentIDs
                merge(model.meter.growth.filter { it != seen }, snapshotFlow { currentIDs }.filter { it != ids }).first()
            }
        }
    }

    LaunchedEffect(shellIDs, probe, active) {
        if (!active || probe == null) return@LaunchedEffect
        val client = dependency(OpenCodeClient.Key)
        val meter = ShellOutputMeter.shared
        val gone = HashSet<String>()
        while (isActive) {
            for (id in shellIDs) {
                if (id in gone || !meter.needsProbe(id)) continue
                try {
                    // The next tick is the retry: a decoration never retries into a dead route.
                    val page = withRetryPolicy(RetryPolicy.once) {
                        client.shellOutput(probe.server, probe.directory, id, END_OF_CAPTURE_CURSOR, 1)
                    }
                    meter.record(id, page.size)
                } catch (error: OpenCodeClientError) {
                    if (error.statusCode == 404) gone += id
                }
            }
            delay(meter.probeInterval(shellIDs.filter { it !in gone }, AgentActivityClock.now()))
        }
    }

    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        tick
        drawAgentActivityOrb(field[0], cells = GyroCell.seven, dark = dark)
    }
}
