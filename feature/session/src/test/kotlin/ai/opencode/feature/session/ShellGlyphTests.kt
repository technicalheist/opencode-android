package ai.opencode.feature.session

import ai.opencode.feature.session.agentactivity.GyroCell
import ai.opencode.feature.session.agentactivity.GyroField
import ai.opencode.feature.session.agentactivity.ShellGlyphModel
import ai.opencode.feature.session.agentactivity.ShellOutputFeed
import ai.opencode.feature.session.agentactivity.ShellPromptGlyph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

/**
 * The shell glyph's physics (R1 Glide), fed as the app feeds it: a byte rate becomes Poisson
 * chunks, stepped at a fixed rate.
 */
class ShellGlyphTests {
    /** Runs segments of (seconds, bytes per second) and records when each lap completes. */
    private class Run {
        val glyph = ShellPromptGlyph(ShellPromptGlyph.State.output)
        val feed = ShellOutputFeed(seed = 11)
        var t = 0.0
        val lapTimes = mutableListOf<Double>()
        var peakSpeed = 0.0
        val phases = mutableListOf<Pair<Double, ShellPromptGlyph.Phase>>()

        fun play(seconds: Double, bytesPerSecond: Double) {
            val dt = ShellGlyphModel.STEP
            val rate = ShellOutputFeed.chunkRate(bytesPerSecond)
            val end = t + seconds
            while (t < end - 1e-9) {
                val before = glyph.travelled
                for (w in feed.chunks(rate = rate, dt = dt)) glyph.event(w)
                glyph.step(dt)
                t += dt
                if (floor(glyph.travelled / ShellPromptGlyph.LAP) > floor(before / ShellPromptGlyph.LAP)) lapTimes += t
                peakSpeed = max(peakSpeed, glyph.v)
                if (phases.lastOrNull()?.second != glyph.phase) phases += t to glyph.phase
            }
        }
    }

    @Test fun steadyOutputLapsAboutOnceEveryOneAndAHalfSeconds() {
        val run = Run()
        run.play(30.0, bytesPerSecond = 800.0)
        // Past the spin-up, the lap time settles around 1.5 s.
        val settled = run.lapTimes.filter { it > 5 }
        val laps = settled.zipWithNext { a, b -> b - a }
        val mean = laps.sum() / laps.size
        assertTrue("${laps.size} laps", laps.size >= 12)
        assertTrue("Mean lap $mean s", abs(mean - 1.5) < 0.25)
        assertTrue(abs(ShellOutputFeed.chunkRate(800.0) - 4.11) < 0.05)
    }

    @Test fun aBurstSpinsItUpThenItCoastsHome() {
        val run = Run()
        run.play(1.0, bytesPerSecond = 0.0)
        assertEquals(ShellPromptGlyph.Phase.rest, run.glyph.phase)
        run.play(2.4, bytesPerSecond = 30_000.0)
        // Whipped round about three times a second.
        assertTrue("Peak ${run.peakSpeed} cells/s", run.peakSpeed > 18)
        assertEquals(ShellPromptGlyph.Phase.lap, run.glyph.phase)
        val lapsInBurst = run.lapTimes.size
        assertTrue(lapsInBurst >= 4)

        run.play(8.0, bytesPerSecond = 0.0)
        // It coasts on for a lap or two after the output stops, slowing down,
        // and lands forward on the prompt.
        val coasting = run.lapTimes.size - lapsInBurst
        assertTrue("$coasting laps while coasting", coasting in 1..3)
        val coastedAt = run.phases.firstOrNull { it.second == ShellPromptGlyph.Phase.coast && it.first > 3.4 }?.first
        val restedAt = run.phases.lastOrNull { it.second == ShellPromptGlyph.Phase.rest }?.first
        assertNotNull(coastedAt)
        if (coastedAt != null && restedAt != null) assertTrue(restedAt > coastedAt && restedAt - 3.4 < 4)
        assertEquals(ShellPromptGlyph.Phase.rest, run.glyph.phase)
        assertTrue(abs(run.glyph.offset) < 0.01)
    }

    @Test fun noOutputRestsOnThePrompt() {
        val run = Run()
        run.glyph.set(ShellPromptGlyph.State.idle, immediate = true)
        run.play(6.0, bytesPerSecond = 0.0)
        assertEquals(ShellPromptGlyph.Phase.rest, run.glyph.phase)
        assertEquals(0.0, run.glyph.travelled, 0.0)
        assertEquals(0.0, run.glyph.offset, 0.0)
        assertTrue(run.lapTimes.isEmpty())
    }

    @Test fun aTrickleOnlyNudgesIt() {
        val run = Run()
        run.play(10.0, bytesPerSecond = 100.0)
        assertTrue(run.lapTimes.isEmpty())
        assertEquals(ShellPromptGlyph.Phase.rest, run.glyph.phase)
    }

    @Test fun reduceMotionHoldsTheStillPrompt() {
        val glyph = ShellPromptGlyph(ShellPromptGlyph.State.output, reduceMotion = true)
        repeat(600) {
            glyph.event(1.4)
            glyph.step(ShellGlyphModel.STEP)
        }
        assertTrue(glyph.phase == ShellPromptGlyph.Phase.rest && glyph.travelled == 0.0 && glyph.offset == 0.0)
        val cells = GyroCell.seven
        val field = glyph.fill(cells)
        assertEquals(cells.size, field.dots.size)
        // The `>` is lit, centred in the disc: its tip and its arms' ends. No cursor.
        val lit = { column: Int, row: Int -> field.dots[cells.indexOfFirst { it.column == column && it.row == row }].light }
        assertTrue(lit(4, 3) > 0.9 && lit(2, 1) > 0.9 && lit(2, 5) > 0.9)
        // The rest is the orb's resting grid: drawn, well under the chevron.
        assertTrue(lit(4, 6) < 0.6 && lit(5, 1) < 0.6 && lit(4, 6) > 0.05)
    }

    @Test fun theMeterTurnsSizesIntoARateThatGoesStale() {
        val meter = ShellOutputMeter()
        meter.record("sh_a", size = 1_000, now = 100.0)
        assertEquals(ShellOutputMeter.Reading(bytesPerSecond = 0.0, isOutput = false), meter.reading(listOf("sh_a"), now = 100.0))
        assertTrue(!meter.needsProbe("sh_a", now = 100.5))
        meter.record("sh_a", size = 1_800, now = 101.0)
        meter.record("sh_b", size = 50, now = 101.0)
        meter.record("sh_b", size = 450, now = 102.0)
        assertEquals(ShellOutputMeter.Reading(bytesPerSecond = 800.0, isOutput = true), meter.reading(listOf("sh_a"), now = 101.5))
        assertEquals(ShellOutputMeter.Reading(bytesPerSecond = 1_200.0, isOutput = true), meter.reading(listOf("sh_a", "sh_b"), now = 102.0))
        // Two readers a moment apart: the first read stands.
        meter.record("sh_a", size = 1_810, now = 101.1)
        assertEquals(800.0, meter.reading(listOf("sh_a"), now = 101.5).bytesPerSecond, 0.0)
        assertTrue(meter.needsProbe("sh_a", now = 102.0))
        assertEquals(ShellOutputMeter.Reading(bytesPerSecond = 0.0, isOutput = false), meter.reading(listOf("sh_a"), now = 104.5))
    }

    @Test fun theModelLapsWhileTheMeterReadsSteadyOutput() {
        val meter = ShellOutputMeter()
        val model = ShellGlyphModel(meter)
        var size = 0
        meter.record("sh_dev", size = size, now = 0.0)
        var lapped = false
        for (frame in 1..(60 * 12)) {
            val now = frame.toDouble() / 60
            if (frame % 60 == 0) {
                size += 800
                meter.record("sh_dev", size = size, now = now)
            }
            model.advance(now = now, shellIDs = listOf("sh_dev"), reduceMotion = false)
            lapped = lapped || model.isLapping
        }
        assertTrue(lapped)
    }

    // Rest and wake (an idle dev server stops its timeline and its probe slows)

    /** Advances [model] at 60 Hz from [start] for [seconds], returning when it first rested and the last field. */
    private fun play(
        model: ShellGlyphModel,
        start: Double,
        seconds: Double,
        ids: List<String> = listOf("sh_dev"),
        each: (Double) -> Unit = {},
    ): Pair<Double?, GyroField?> {
        var restedAt: Double? = null
        var field: GyroField? = null
        for (frame in 1..(seconds * 60).toInt()) {
            val now = start + frame.toDouble() / 60
            each(now)
            field = model.advance(now = now, shellIDs = ids, reduceMotion = false)
            if (restedAt == null && model.isResting(now, ids)) restedAt = now
        }
        return restedAt to field
    }

    @Test fun anIdleShellRestsAtOnceAndSteppingOnDrawsNothingNew() {
        val meter = ShellOutputMeter()
        val model = ShellGlyphModel(meter)
        meter.record("sh_dev", size = 4_096, now = 0.0)
        val (restedAt, rested) = play(model, start = 0.0, seconds = 1.0)
        // First sight is settled: it needs only its phosphor to land.
        val at = checkNotNull(restedAt)
        assertTrue("Rested at $at s", at < 0.5)
        // What the paused timeline would have drawn over the next 10 s, with the probe finding the
        // size unchanged: the same field, so pausing is invisible.
        var size = 4_096
        val (_, later) = play(model, start = 1.0, seconds = 10.0) { now ->
            if ((now * 60).roundToInt() % 60 == 0) meter.record("sh_dev", size = size, now = now)
        }
        assertTrue(model.isResting(11.0, listOf("sh_dev")))
        val before = checkNotNull(rested)
        val after = checkNotNull(later)
        for (i in before.dots.indices) {
            assertTrue(abs(before.dots[i].light - after.dots[i].light) < 0.002 && before.dots[i].fresh == after.dots[i].fresh)
        }
        assertTrue(abs(before.glow - after.glow) < 0.002)
        // Output growing wakes it on the read that sees it.
        size += 800
        meter.record("sh_dev", size = size, now = 11.5)
        assertTrue(!model.isResting(11.5, listOf("sh_dev")))
    }

    @Test fun afterABurstItRestsOnlyOnceHomeAndDrained() {
        val meter = ShellOutputMeter()
        val model = ShellGlyphModel(meter)
        var size = 0
        meter.record("sh_dev", size = size, now = 0.0)
        play(model, start = 0.0, seconds = 3.0) { now ->
            if ((now * 60).roundToInt() % 60 == 0) {
                size += 30_000
                meter.record("sh_dev", size = size, now = now)
            }
        }
        assertTrue(model.isLapping)
        assertTrue(!model.isResting(3.0, listOf("sh_dev")))
        val (restedAt, _) = play(model, start = 3.0, seconds = 15.0)
        // Still: it never woke on its own.
        assertTrue(model.isResting(18.0, listOf("sh_dev")))
        val at = checkNotNull(restedAt)
        // Not while it coasts, lands or the flow meter drains; well before forever.
        assertTrue("Rested at $at s", at > 6 && at < 17)
        assertTrue(!model.isLapping)
    }

    @Test fun aRestingGlyphWakesOnTheNextReadThatSeesGrowth() {
        val meter = ShellOutputMeter()
        val model = ShellGlyphModel(meter)
        meter.record("sh_dev", size = 4_096, now = 0.0)
        meter.record("sh_dev", size = 4_096, now = 1.0)
        val (restedAt, _) = play(model, start = 1.0, seconds = 1.0)
        assertNotNull(restedAt)
        val seen = meter.growth.value
        // A probe that finds the size unchanged leaves it asleep.
        meter.record("sh_dev", size = 4_096, now = 2.0)
        assertEquals(seen, meter.growth.value)
        // The sheet's read a moment after the probe is dropped, so the probe's next read sees the growth.
        meter.record("sh_dev", size = 5_000, now = 2.1)
        assertEquals(seen, meter.growth.value)
        meter.record("sh_dev", size = 5_000, now = 3.0)
        assertTrue(meter.growth.value != seen)
        assertTrue(!model.isResting(3.0, listOf("sh_dev")))
    }

    @Test fun theProbeSlowsOnlyOnceEveryShellSitsUnchanged() {
        val meter = ShellOutputMeter()
        assertEquals("Never read: read it now", 1.seconds, meter.probeInterval(listOf("sh_a"), now = 0.0))
        meter.record("sh_a", size = 100, now = 0.0)
        meter.record("sh_a", size = 100, now = 29.0)
        assertEquals(1.seconds, meter.probeInterval(listOf("sh_a"), now = 29.5))
        meter.record("sh_a", size = 100, now = 30.0)
        assertEquals(ShellOutputMeter.idleProbeInterval, meter.probeInterval(listOf("sh_a"), now = 30.0))
        // Another shell still growing keeps the pace.
        meter.record("sh_b", size = 0, now = 10.0)
        meter.record("sh_b", size = 50, now = 25.0)
        assertEquals(1.seconds, meter.probeInterval(listOf("sh_a", "sh_b"), now = 40.0))
        // Growth puts it straight back to a second.
        meter.record("sh_a", size = 900, now = 40.0)
        assertEquals(1.seconds, meter.probeInterval(listOf("sh_a"), now = 40.0))
    }
}
