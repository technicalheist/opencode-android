package ai.opencode.feature.session

import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.server.OpenCodeServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// The meter, its client and the probe. The glyph view is `agentactivity/ShellGlyph.kt`.

/** Where a running shell's output is read from: its session's server and directory. */
data class ShellGlyphProbe(val server: OpenCodeServer, val directory: String)

/**
 * How fast each shell's capture is growing, from its size read now and then: by the glyph's
 * probe, or by the output sheet as it tails. Kept outside the reducers; glyphs read it while
 * they draw. Times are [AgentActivityClock] seconds.
 */
class ShellOutputMeter {
    data class Sample(
        val size: Int,
        val at: Double,
        val bytesPerSecond: Double = 0.0,
        val grewAt: Double? = null,
        /** When it last grew, or was first read. */
        val changedAt: Double = at,
    )

    data class Reading(val bytesPerSecond: Double = 0.0, val isOutput: Boolean = false) {
        /** Nothing for the glyph to show: no rate, no recent growth. */
        val isQuiet: Boolean get() = bytesPerSecond == 0.0 && !isOutput
    }

    private val lock = Any()
    private val samples = HashMap<String, Sample>()
    private val growthCount = MutableStateFlow(0L)

    /** Counts the reads that saw a shell grow: a glyph at rest waits for the next. */
    val growth: StateFlow<Long> get() = growthCount

    fun record(id: String, size: Int, now: Double = AgentActivityClock.now()) {
        synchronized(lock) {
            val previous = samples[id]
            if (previous == null) {
                samples[id] = Sample(size = size, at = now)
                prune(now)
                return
            }
            // Two readers a moment apart: the earlier read stands.
            if (now - previous.at < 0.25) return
            val grown = max(0, size - previous.size)
            samples[id] = Sample(
                size = size,
                at = now,
                bytesPerSecond = grown.toDouble() / (now - previous.at),
                grewAt = if (grown > 0) now else previous.grewAt,
                changedAt = if (grown > 0) now else previous.changedAt,
            )
            if (grown > 0) growthCount.value += 1
        }
    }

    /** How long the probe waits before reading [ids] again. */
    fun probeInterval(ids: List<String>, now: Double): Duration = synchronized(lock) {
        val idle = ids.all { id -> samples[id]?.let { now - it.changedAt >= IDLE_AFTER } ?: false }
        if (idle) idleProbeInterval else probeInterval
    }

    /** No one has read the shell's size this past interval. */
    fun needsProbe(id: String, now: Double = AgentActivityClock.now()): Boolean = synchronized(lock) {
        val sample = samples[id] ?: return@synchronized true
        now - sample.at >= 0.8
    }

    /** The shells' combined output now. */
    fun reading(ids: List<String>, now: Double): Reading = synchronized(lock) {
        var bytesPerSecond = 0.0
        var isOutput = false
        for (id in ids) {
            val sample = samples[id] ?: continue
            if (now - sample.at <= STALE_AFTER) bytesPerSecond += sample.bytesPerSecond
            val grewAt = sample.grewAt
            if (grewAt != null && now - grewAt < OUTPUT_WINDOW) isOutput = true
        }
        Reading(bytesPerSecond, isOutput)
    }

    fun reset() = synchronized(lock) { samples.clear() }

    private fun prune(now: Double) {
        if (samples.size <= 64) return
        samples.entries.removeAll { now - it.value.at >= 60 }
    }

    companion object {
        /** The process-wide meter, private to this module's glyphs and [ShellOutputMeterClient.liveValue]. */
        internal val shared = ShellOutputMeter()
        val probeInterval: Duration = 1.seconds

        /** The probe's pace once every shell has sat unchanged for [IDLE_AFTER]: output that starts again shows within this. */
        val idleProbeInterval: Duration = 3.seconds
        const val IDLE_AFTER: Double = 30.0

        /** A rate this old no longer describes the shell. */
        const val STALE_AFTER: Double = 2.5

        /** The prototype's rule: output while the capture grew in the last 3 s. */
        const val OUTPUT_WINDOW: Double = 3.0
    }
}

/** The reducers' handle on [ShellOutputMeter]. */
class ShellOutputMeterClient(
    val record: (id: String, size: Int) -> Unit,
) {
    companion object Key : DependencyKey<ShellOutputMeterClient> {
        override val liveValue: ShellOutputMeterClient = ShellOutputMeterClient(record = { id, size -> ShellOutputMeter.shared.record(id, size) })
        override val testValue: ShellOutputMeterClient = ShellOutputMeterClient(record = { _, _ -> })
    }
}
