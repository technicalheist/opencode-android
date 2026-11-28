package ai.opencode.feature.voice

import ai.opencode.core.dependencies.TestClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class IdleVoiceSensorsTests {
    private class Probe {
        val motion = mutableListOf<Boolean>()
        var raises = 0
        val near = mutableListOf<Boolean>()
    }

    @Before fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun detector(probe: Probe, clock: TestClock, heldUp: () -> Boolean = { true }, takesRaise: Boolean = true): RaiseToEarDetector {
        val detector = RaiseToEarDetector(clock = clock, heldUp = heldUp, onMotion = { probe.motion += it }, onNote = {})
        detector.start(
            onRaise = { probe.raises += 1; takesRaise },
            onNear = { probe.near += it },
        )
        return detector
    }

    @Test fun motionStaysOffWhileTheSensorIsClear() = runBlocking {
        val probe = Probe()
        val clock = TestClock()
        val detector = detector(probe, clock)
        clock.advance(3_600.seconds)
        assertTrue("An hour on an idle session reads no motion", probe.motion.isEmpty())
        detector.stop()
        assertTrue(probe.motion.isEmpty())
    }

    @Test fun motionRunsFromCoverThroughTheEarAndStopsOnceLowered() = runBlocking {
        val probe = Probe()
        val clock = TestClock()
        var heldUp = true
        val detector = detector(probe, clock, heldUp = { heldUp })

        detector.proximityChanged(true)
        assertEquals("Starts with the cover, ahead of the dwell's posture check", listOf(true), probe.motion)
        clock.advance(RaiseToEarDetector.dwell)
        assertEquals(1, probe.raises)
        assertEquals(listOf(true), probe.near)
        assertEquals("Stays on at the ear, for the lower's posture", listOf(true), probe.motion)

        heldUp = false
        detector.proximityChanged(false)
        clock.advance(RaiseToEarDetector.lowerDwell)
        assertEquals(listOf(true, false), probe.near)
        assertEquals("Off once lowered", listOf(true, false), probe.motion)
        detector.stop()
        assertEquals(listOf(true, false), probe.motion)
    }

    @Test fun aBriefCoverOrARefusedRaiseTurnsMotionStraightOff() = runBlocking {
        val probe = Probe()
        val clock = TestClock()
        val detector = detector(probe, clock, takesRaise = false)

        detector.proximityChanged(true)
        clock.advance(400.milliseconds)
        detector.proximityChanged(false)
        assertEquals(0, probe.raises)
        assertEquals(listOf(true, false), probe.motion)

        // Refused (dictating): not at the ear, so nothing left to decide.
        detector.proximityChanged(true)
        clock.advance(RaiseToEarDetector.dwell)
        assertEquals(1, probe.raises)
        assertEquals(listOf(true, false, true, false), probe.motion)
        assertTrue(detector.isNear)
        assertFalse(detector.isAtEar)
        detector.stop()
    }

    @Test fun aCoverLyingFlatTurnsMotionOffAfterTheDwell() = runBlocking {
        val probe = Probe()
        val clock = TestClock()
        var heldUp = true
        val detector = detector(probe, clock, heldUp = { heldUp })
        // No posture yet when the sensor's covered; flat by the time it's read.
        detector.proximityChanged(true)
        heldUp = false
        clock.advance(RaiseToEarDetector.dwell)
        assertEquals("Flat on a table is no raise", 0, probe.raises)
        assertEquals(listOf(true, false), probe.motion)
        detector.stop()
    }

    @Test fun aFirstSampleLyingFlatAfterTheEarlyCheckIsNoRaise() = runBlocking {
        val probe = Probe()
        val clock = TestClock()
        var gravity: RaiseToEarDetector.Gravity? = null
        val detector = RaiseToEarDetector(clock = clock, gravitySource = { gravity }, onMotion = { probe.motion += it }, onNote = {})
        detector.start(onRaise = { probe.raises += 1; true }, onNear = {})
        // The sensor's cold: no sample at the early check, then flat, then upright before the dwell ends.
        detector.proximityChanged(true)
        clock.advance(RaiseToEarDetector.motionPoll)
        gravity = RaiseToEarDetector.Gravity(0.0, 0.0, -1.0)
        detector.motionSampled()
        gravity = RaiseToEarDetector.Gravity(0.0, -1.0, 0.0)
        clock.advance(RaiseToEarDetector.dwell)
        assertEquals("Covered flat is no raise", 0, probe.raises)
        assertEquals(listOf(true, false), probe.motion)
        detector.stop()
    }

    @Test fun aWarmWindowWarmsSixTimesAndThenNothing() = runTest {
        var warms = 0
        val window = launch { LiveSocketWarmer.warmWindow { warms += 1 } }
        advanceTimeBy(120.seconds)
        runCurrent()
        assertTrue(window.isCompleted)
        assertEquals(6, warms)
        advanceTimeBy(3_600.seconds)
        assertEquals(6, warms)
    }

    @Test fun aCancelledWindowStopsWarming() = runTest {
        var warms = 0
        val window = launch { LiveSocketWarmer.warmWindow { warms += 1 } }
        advanceTimeBy(25.seconds)
        window.cancel()
        advanceTimeBy(120.seconds)
        assertEquals(3, warms)
    }
}
