package ai.opencode.feature.session.agentactivity

import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.feature.session.AgentActivity
import ai.opencode.feature.session.AgentActivityCounts
import ai.opencode.feature.session.AgentActivityOverride
import ai.opencode.feature.session.AgentActivitySource
import ai.opencode.feature.session.ShellOutputMeter
import ai.opencode.feature.session.WorkingActivity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// The glyphs' per-frame simulations: plain objects stepped from the glyph view's frame loop, kept
// across recompositions and remounts.

/**
 * Visual state outlives a mounted glyph, not a reducer or its effects. A returning session
 * resumes its held phase instead of drawing a new seed. Main thread only. Keeps the 64 most
 * recently used.
 */
open class GlyphModelCache<Model>(private val make: () -> Model) {
    private val models = HashMap<String, Model>()
    private val order = ArrayDeque<String>()

    fun model(key: String): Model {
        val model = models[key] ?: make()
        models[key] = model
        if (order.lastOrNull() != key) {
            order.remove(key)
            order.addLast(key)
        }
        while (order.size > 64) models.remove(order.removeFirst())
        return model
    }
}

object AgentActivityGlyphModels : GlyphModelCache<AgentActivityGlyphModel>(::AgentActivityGlyphModel)

/** The glyph's motion outlives a mounted view: a list row reused, or the capsule swapped for the panel, picks up where it was. */
object ShellGlyphModels : GlyphModelCache<ShellGlyphModel>(::ShellGlyphModel)

/** How fast the orb's own time runs: the Working line's Done or Failed plays inside its shorter finale. */
internal val AgentActivitySource.rate: Double
    get() {
        if (this !is AgentActivitySource.Session || working.phase == WorkingActivity.Phase.running) return 1.0
        return WorkingActivity.finaleRate
    }

/** The cached model's key within a scope; null draws with a model of its own. */
val AgentActivitySource.animationKey: String?
    get() = when (this) {
        is AgentActivitySource.Subagent -> subagent.sessionID?.let { "subagent|$it" }
        is AgentActivitySource.Summary -> "summary"
        is AgentActivitySource.Session -> "working|" + (working.key ?: "")
        AgentActivitySource.Running, AgentActivitySource.Lab -> null
    }

/** The glyph's simulation, kept across recompositions: purely visual state. */
class AgentActivityGlyphModel {
    class Frame(
        val field: GyroField? = null,
        /** Nothing moves until the state changes or an event arrives. */
        val resting: Boolean = false,
        val activity: AgentActivity = AgentActivity.idle,
    )

    private var gyro: ActivityGyro? = null
    private var reduceMotion = false
    private var last: Double? = null
    private var counts = AgentActivityCounts()
    private val seed = abs(System.identityHashCode(this) % 97) / 13.0

    /** The last frame drawn. */
    var frame: Frame = Frame()
        private set

    /** Showing [activity] and still. */
    fun rests(activity: AgentActivity): Boolean = gyro?.op == activity && gyro?.isResting == true

    fun advance(now: Double, source: AgentActivitySource, lab: AgentActivityOverride?, reduceMotion: Boolean): Frame {
        val activity = source.activity(now, lab)
        val counts = source.counts(now, lab)
        if (gyro == null || reduceMotion != this.reduceMotion) {
            // First sight: the state as it stands, settled, never an entrance.
            gyro = ActivityGyro(activity, reduceMotion = reduceMotion, seed = seed)
            this.reduceMotion = reduceMotion
            this.counts = counts
            last = now
        }
        val gyro = gyro ?: return Frame()
        if (gyro.op != activity) gyro.set(activity)
        val new = counts.since(this.counts)
        this.counts = counts
        // A burst after a pause arrives a few at a time, as it would have live.
        repeat(min(new.tool, 3)) { gyro.event(ActivityGyro.Event.tool) }
        repeat(min(new.output, 3)) { gyro.event(ActivityGyro.Event.output) }
        // The Working line's finale plays faster, in steps no longer than usual.
        val rawDt = min(1.0 / 30, max(0.0, now - (last ?: now))) * source.rate
        val dt = AnimationTimeScale.shared.scaledDelta(rawDt)
        last = now
        var remaining = dt
        while (remaining > 1e-6) {
            val step = min(1.0 / 30, remaining)
            gyro.step(step)
            remaining -= step
        }
        frame = Frame(field = gyro.render(GyroCell.seven, dt), resting = gyro.isResting, activity = activity)
        return frame
    }
}

/**
 * One shell glyph's simulation, stepped at a fixed rate from the meter's reading: the measured
 * byte rate becomes Poisson chunks, each an impulse.
 */
class ShellGlyphModel(internal var meter: ShellOutputMeter = ShellOutputMeter.shared) {
    private var glyph: ShellPromptGlyph? = null
    private val feed = ShellOutputFeed()
    private var reduceMotion = false
    private var last: Double? = null
    private var pending = 0.0
    private var field: GyroField? = null

    val isLapping: Boolean get() = glyph?.isLapping == true

    /** Still, with nothing coming to move it: stepping on would draw the same field until the shells' output grows. */
    fun isResting(now: Double, shellIDs: List<String>): Boolean {
        val glyph = glyph ?: return false
        if (glyph.state != ShellPromptGlyph.State.idle || !glyph.isResting) return false
        return meter.reading(shellIDs, now).isQuiet
    }

    fun advance(now: Double, shellIDs: List<String>, reduceMotion: Boolean): GyroField {
        val reading = meter.reading(shellIDs, now)
        val state = if (reading.isOutput) ShellPromptGlyph.State.output else ShellPromptGlyph.State.idle
        if (glyph == null || reduceMotion != this.reduceMotion) {
            // First sight: the state as it stands, settled, never an entrance.
            glyph = ShellPromptGlyph(state, reduceMotion = reduceMotion)
            this.reduceMotion = reduceMotion
            last = now
            field = null
        }
        val glyph = glyph ?: return GyroField.empty()
        if (glyph.state != state) glyph.set(state)
        // Resuming from a pause steps on from the held pose, at most 1/30 s.
        val rawStep = min(1.0 / 30, max(0.0, now - (last ?: now)))
        pending += AnimationTimeScale.shared.scaledDelta(rawStep)
        last = now
        val rate = ShellOutputFeed.chunkRate(reading.bytesPerSecond)
        var steppedTime = 0.0
        while (pending >= STEP) {
            pending -= STEP
            steppedTime += STEP
            if (!reduceMotion) for (w in feed.chunks(rate, STEP)) glyph.event(w)
            glyph.step(STEP)
        }
        if (steppedTime > 0 || field == null) field = glyph.fill(GyroCell.seven, max(steppedTime, 1.0 / 120))
        return field ?: GyroField.empty()
    }

    companion object {
        const val STEP = 1.0 / 120
    }
}
