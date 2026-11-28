package ai.opencode.feature.drawer

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.TestClock
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.ExperimentalFeature
import ai.opencode.core.sharing.ExperimentalFeatures
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.store.TestStore
import ai.opencode.core.testing.TestIsolationRule
import ai.opencode.feature.drawer.catchup.CatchUpFeature
import ai.opencode.feature.drawer.catchup.CatchUpFinishTimeline
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/** Catch Up's half: Catch Up Done with the finish's success. */
class SoundEffectsTests {
    @get:Rule val isolation = TestIsolationRule()

    private fun features(on: Boolean) = ExperimentalFeatures.enabling(if (on) setOf(ExperimentalFeature.soundEffects) else emptySet())

    private fun log(): MutableList<String> = Collections.synchronizedList(mutableListOf())

    private fun recording(log: MutableList<String>) = SoundEffectsClient(
        play = { log += "sound.${it.rawValue}" },
        prepare = { log += "sound.prepare" },
    )

    @Test fun finishingCatchUpPlaysCatchUpDoneWithItsSuccess() {
        for ((on, expected) in listOf(true to listOf("success", "sound.catchUpDone"), false to listOf("success"))) runTest {
            val clock = TestClock()
            val log = log()
            val stack = CatchUpFeature.Stack(queue = emptyList(), returnSessionID = "read")
            val store = TestStore(CatchUpFeature.State(stack = stack), CatchUpFeature(), this) {
                this[Clock] = clock
                this[HapticsClient] = HapticsClient(play = { log += it.name })
                this[SoundEffectsClient] = recording(log)
                this[ExperimentalFeatures] = features(on)
            }
            val timeline = CatchUpFinishTimeline.current(reducedMotion = false)

            store.send(CatchUpFeature.Action.Finished(reducedMotion = false)) {
                it.copy(stack = null, exitingStack = stack, justFinished = true, finishes = 1)
            }
            clock.advance(timeline.checkTime.seconds)
            store.receive(CatchUpFeature.Action.FinishChecked)
            assertEquals("on=$on", expected, log.toList())
            clock.advance(timeline.restEnd.seconds)
            store.receive(CatchUpFeature.Action.FinishRested) { it.copy(justFinished = false) }
            assertEquals("on=$on", expected, log.toList())
            store.finish()
        }
    }
}
