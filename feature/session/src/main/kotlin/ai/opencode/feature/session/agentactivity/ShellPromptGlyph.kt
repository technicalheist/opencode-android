package ai.opencode.feature.session.agentactivity

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

// The shell glyph's engine; the tuning lives in `ShellGlyphTuning.glide`.
//
// A `>` prompt on the orb's 7-wide LED disc, moving as a body on a 7-cell torus: output chunks fill
// a push reservoir that drains into its speed, and friction (linear plus a little quadratic,
// capping the top speed) slows it. It laps while the flow (a leaky count of chunks per second) is
// above `enter` and until it falls below `exit`; below that it rests on the prompt and each chunk
// nudges it on a spring. Out of a lap it coasts, then brakes forward into the next home position,
// never backwards.

/**
 * R1 Glide's constants, named as the prototype names them. impulse: speed one chunk adds
 * (cells/s); tau: friction's time constant (s); mu: its dry term when coasting (cells/s²); quad:
 * quadratic drag; push: how long a chunk's push takes to land (s); flowTau: the flow meter's
 * memory (s); enter/exit: flow (chunks/s) that starts and stops lapping; cruise: least lapping
 * speed (cells/s); nudge: a resting chunk's kick.
 */
data class ShellGlyphTuning(
    val impulse: Double,
    val tau: Double,
    val mu: Double,
    val quad: Double,
    val push: Double,
    val flowTau: Double,
    val enter: Double,
    val exit: Double,
    val cruise: Double,
    val nudge: Double,
) {
    companion object {
        val glide = ShellGlyphTuning(
            impulse = 0.75, tau = 0.9, mu = 3.0, quad = 0.012, push = 0.14, flowTau = 0.9, enter = 3.2, exit = 1.6, cruise = 2.4, nudge = 2.4,
        )
    }
}

class ShellPromptGlyph(state: State, val reduceMotion: Boolean = false, val tuning: ShellGlyphTuning = ShellGlyphTuning.glide) {
    /** Output: the capture grew in the last 3 s. Idle: it didn't. */
    enum class State { idle, output }
    enum class Phase { rest, lap, coast }

    var state: State = state
        private set
    var phase = Phase.rest
        private set

    /** The chevron's offset in cells (a whole number of laps when it is home), and its speed. */
    var x = 0.0
        private set
    var v = 0.0
        private set

    /** Cells travelled in all, for measuring laps. */
    var travelled = 0.0
        private set

    /** The flow meter: a leaky, saturating count of chunks per second. */
    var flow = 0.0
        private set
    private var reservoir = 0.0
    private var lapAge = 0.0
    private var home: Double? = null
    private val nudge = GyroValue(0.0, config = nudgeSpring, precision = 0.001)
    private val body = GyroValue(0.0, config = fade, precision = 0.002)
    private val glow = GyroValue(0.0, config = fade, precision = 0.002)
    private val chev = GyroValue(0.0, config = fade, precision = 0.002)

    /** Each dot's phosphor. */
    private var light = DoubleArray(0)

    /** The last [fill] left every dot's phosphor where it was heading. */
    private var phosphorSettled = false

    init {
        set(state, immediate = true)
    }

    val isLapping: Boolean get() = phase != Phase.rest

    /** Home on the prompt with every spring, dot and the flow meter settled: stepping on draws the same field. */
    val isResting: Boolean
        get() = phase == Phase.rest && flow < 0.01 && phosphorSettled &&
            nudge.settled && body.settled && glow.settled && chev.settled

    /** The chevron's offset as drawn, nudge included. */
    val offset: Double get() = x + nudge.value

    fun set(state: State, immediate: Boolean = false) {
        this.state = state
        val now = immediate || reduceMotion
        val cfg = if (reduceMotion) still else fade
        // The orb's resting grid, at the orb's level, so the two discs match.
        body.to(if (state == State.idle) 0.68 else 0.85, cfg, immediate = now)
        chev.to(if (state == State.idle) 0.82 else 1.0, cfg, immediate = now)
        glow.to(if (state == State.idle) 0.16 else 0.3, cfg, immediate = now)
    }

    /** A chunk of output, [w] its weight (about 1). Reduce Motion holds still. */
    fun event(w: Double = 1.0) {
        if (reduceMotion) return
        val c = tuning
        // The flow meter is a gate, not a speed: it saturates, so it falls below `exit` soon after a burst ends.
        flow = min(c.enter * 1.6, flow + w / c.flowTau)
        if (phase == Phase.rest) {
            if (flow >= c.enter) startLap(w) else nudge.velocity += c.nudge * w
        } else {
            reservoir += c.impulse * w
            if (phase == Phase.coast) home = null
        }
    }

    /** Where a coast ends: the home ahead whose friction is closest to the tuning's own, or null when none is in reach yet (a slow chevron cruises on until one is). */
    private fun plan(): Double? {
        val c = tuning
        val first = LAP * ceil((x + 0.05) / LAP)
        var best: Double? = null
        var score = Double.POSITIVE_INFINITY
        for (k in 0 until 5) {
            val h = first + k * LAP
            val d = h - x
            // Too little friction leaves a long exponential tail: only homes it can stop on with at least 0.6 μ.
            if (d >= stopDistance(v, c.tau, c.mu * 0.6)) break
            val mu = friction(v, c.tau, d)
            if (mu > c.mu * 12) continue
            val sc = abs(ln(mu / c.mu))
            if (sc < score) { score = sc; best = h }
        }
        return best
    }

    private fun startLap(w: Double) {
        // Continue from wherever the nudge has the chevron, so nothing jumps.
        x += nudge.value
        v = max(0.0, nudge.velocity)
        nudge.set(0.0)
        reservoir += tuning.impulse * w
        phase = Phase.lap
        lapAge = 0.0
    }

    /** The chevron arrives home: it seats with a small overshoot. */
    private fun land() {
        val vIn = v
        // Home is a whole number of laps, which draws as zero: rebase there.
        x = 0.0
        v = 0.0
        reservoir = 0.0
        phase = Phase.rest
        home = null
        nudge.velocity += Gyro.clamp(vIn * 0.6 + 0.9, 0.9, 2.2)
    }

    fun step(dt: Double) {
        val c = tuning
        flow *= exp(-dt / c.flowTau)

        if (phase != Phase.rest) {
            lapAge += dt
            val start = x
            // The push reservoir drains into speed; friction takes it away.
            val v0 = v
            val dR = reservoir * (1 - exp(-dt / c.push))
            reservoir -= dR
            v += dR
            // Lapping, friction only takes the speed above the cruise floor; coasting, all of it.
            val base = if (phase == Phase.lap) min(v, c.cruise) else 0.0
            v = base + (v - base) * exp(-dt / c.tau)
            if (phase == Phase.lap) {
                v -= c.quad * v * v * dt
                if (v < c.cruise) v += (c.cruise - v) * (1 - exp(-dt / 0.35))
                if (flow < c.exit && lapAge > 0.8) { phase = Phase.coast; home = null }
            } else if (flow >= c.enter) {
                phase = Phase.lap
            }
            if (phase == Phase.coast) {
                // Friction with a dry term, so it stops in a finite distance; re-solved every frame so it stops on a home.
                if (home == null) home = plan()
                val h = home
                if (h != null) {
                    val d = h - x
                    val mu = if (d > 1e-3 && v > 1e-3) min(friction(v, c.tau, d), c.mu * 12) else c.mu
                    v = max(0.0, v - mu * dt)
                    x = min(h, x + v * dt)
                    travelled += x - start
                    if (h - x < 0.006 || v < 0.03) land()
                } else {
                    // No home in friction's reach yet: it carries on at no less than the cruise speed until one is.
                    v = max(v, v0)
                    x += v * dt
                    travelled += x - start
                }
            } else {
                x += v * dt
                travelled += x - start
            }
        }

        if (state == State.output && !reduceMotion) glow.to(0.3 + 0.2 * Gyro.clamp(v / 20, 0.0, 1.0))

        nudge.step(dt)
        body.step(dt)
        glow.step(dt)
        chev.step(dt)
    }

    /** The LED field for [cells], through each dot's phosphor. */
    fun fill(cells: List<GyroCell>, dt: Double = 1.0 / 60): GyroField {
        val off = offset
        val vel = if (phase == Phase.rest) nudge.velocity else v
        val speed = abs(vel)
        val comet = if (reduceMotion) 0.0 else Gyro.clamp((speed - 1) / 7, 0.0, 1.0)
        val wM = 0.3 + 0.08 * Gyro.clamp(speed / 6, 0.0, 1.0)
        val mk = chev.value
        val bodyLight = body.value
        // Ink lifts any afterglow to its floor, so the faster it goes the shorter the afterglow: a short tail, not a smear.
        val decay = 0.12 / (1 + speed / 4)
        val first = light.size != cells.size
        if (first) light = DoubleArray(cells.size)
        val dots = ArrayList<GyroField.Dot>(cells.size)
        var unsettled = 0.0
        cells.forEachIndexed { i, cell ->
            val cx = cell.column.toDouble()
            val cy = cell.row.toDouble()
            var lit = 0.0
            var fresh = 0.0
            if (mk > 0.003) {
                val g = chevron.wrappedLight(cx, cy, wM, off) * mk
                lit = combine(lit, g)
                fresh = max(fresh, g * 0.6)
                if (comet > 0.01) {
                    for (j in 1..2) {
                        val behind = off - min(1.2, vel * 0.03) * j
                        lit = combine(lit, chevron.wrappedLight(cx, cy, wM, behind) * mk * comet * 0.4.pow(j))
                    }
                }
            }
            // The orb's resting grid under the chevron.
            val p = (0.14 + 0.4 * cell.shade.pow(1.2)) * bodyLight + 0.95 * lit
            if (first || reduceMotion) light[i] = p
            val lv = light[i]
            val nv = lv + (p - lv) * (1 - exp(-dt / (if (p > lv) 0.03 else decay)))
            light[i] = nv
            unsettled = max(unsettled, abs(p - nv))
            dots.add(GyroField.Dot(light = nv, fresh = fresh * 0.4, accent = 0.0, size = cell.size))
        }
        phosphorSettled = unsettled < 0.001
        return GyroField(dots = dots, glow = glow.value, weights = emptyMap())
    }

    private fun combine(a: Double, b: Double): Double = 1 - (1 - a) * (1 - min(1.0, b))

    /** A polyline of three points, lit as a gaussian of the distance to it. */
    private class Stroke(val xs: DoubleArray, val ys: DoubleArray) {
        val l0 = sqrt((xs[1] - xs[0]) * (xs[1] - xs[0]) + (ys[1] - ys[0]) * (ys[1] - ys[0]))
        val l1 = sqrt((xs[2] - xs[1]) * (xs[2] - xs[1]) + (ys[2] - ys[1]) * (ys[2] - ys[1]))

        fun light(x: Double, y: Double, w: Double): Double {
            var best = 0.0
            for (s in 0 until 2) {
                val ax = xs[s]; val ay = ys[s]; val bx = xs[s + 1]; val by = ys[s + 1]
                val l = if (s == 0) l0 else l1
                var t = 0.0
                if (l > 1e-4) t = Gyro.clamp(((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / (l * l), 0.0, 1.0)
                val px = ax + (bx - ax) * t
                val py = ay + (by - ay) * t
                best = max(best, Gyro.gauss(sqrt((x - px) * (x - px) + (y - py) * (y - py)), w))
            }
            return best
        }

        /** Shifted right by [offset] on the torus: what leaves the right edge enters on the left. */
        fun wrappedLight(x: Double, y: Double, w: Double, offset: Double): Double {
            if (abs(offset) < 1e-4) return light(x, y, w)
            val o = ((offset % LAP) + LAP) % LAP
            return max(light(x - o, y, w), light(x - o + LAP, y, w))
        }
    }

    companion object {
        /** One lap is the grid's width: the field is a torus left to right. */
        const val LAP = 7.0

        /** Centred on the disc's middle cell (3, 3): its two arms' mass at x = 3. */
        private val chevron = Stroke(doubleArrayOf(2.0, 4.0, 2.0), doubleArrayOf(1.0, 3.0, 5.0))

        private val fade = GyroSpring.smooth(0.45)
        private val still = GyroSpring.smooth(0.35)
        private val nudgeSpring = GyroSpring(response = 0.38, damping = 0.72)

        /** Distance to stop from speed v under dv/dt = −v/τ − μ. */
        fun stopDistance(v: Double, tau: Double, mu: Double): Double = tau * v - mu * tau * tau * ln(1 + v / (mu * tau))

        /** The dry friction μ that stops a chevron at speed v in exactly d (d < τv). */
        fun friction(v: Double, tau: Double, d: Double): Double {
            var lo = 1e-3
            var hi = 1e3
            repeat(40) {
                val m = sqrt(lo * hi)
                if (stopDistance(v, tau, m) > d) lo = m else hi = m
            }
            return sqrt(lo * hi)
        }
    }
}

/** Output as chunks: Poisson arrivals at a rate, each with a weight, as the prototype's output simulator feeds its glyphs. */
class ShellOutputFeed(seed: Int = 7) {
    private var seed: Int = seed

    /** The chunks that arrive in [dt] at [rate] per second, as weights. */
    fun chunks(rate: Double, dt: Double): List<Double> {
        if (rate <= 0 || dt <= 0) return emptyList()
        var k = 0
        val l = exp(-rate * dt)
        var p = random()
        while (p > l && k < 12) { k += 1; p *= random() }
        // A trickle comes in small chunks.
        val lo = if (rate < 3) 0.5 else 0.6
        val hi = if (rate < 3) 1.0 else 1.4
        return List(k) { lo + (hi - lo) * random() }
    }

    /** mulberry32, as the prototype's `rng` (UInt32 arithmetic in Int's two's complement). */
    private fun random(): Double {
        seed += 0x6D2B79F5
        var t = (seed xor (seed ushr 15)) * (1 or seed)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4_294_967_296.0
    }

    companion object {
        /**
         * Chunks per second for a byte rate: a trickle of log lines (100 B/s) is about one, steady
         * output (800 B/s) about four, which laps every 1.5 s, and a burst (30 KB/s) about forty,
         * which whips it round.
         */
        fun chunkRate(bytesPerSecond: Double): Double = if (bytesPerSecond < 1) 0.0 else min(48.0, (bytesPerSecond / 100).pow(0.68))
    }
}
