package ai.opencode.core.sound

import ai.opencode.core.dependencies.Clock
import ai.opencode.core.dependencies.DependencyKey
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.diagnostics.LabMarks
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The in-app sounds of Sound Effects (experimental), bundled as WAVs in `res/raw`. Each plays with
 * its haptic; a sound never carries meaning on its own.
 */
enum class SoundEffect(val rawValue: String) {
    /** A reply finished in another session. B4 + G5. */
    replyDone("replyDone"),

    /** A pending request docked. D5 → A5. */
    needsInput("needsInput"),

    /** Catch Up's last card cleared. A G arpeggio. */
    catchUpDone("catchUpDone"),

    /** A send failed after it left the composer. B♭4 → G4. */
    error("error"),

    /** A message went. Off unless Send Sound is on. */
    send("send");

    /** A more urgent sound plays inside another's burst; an equal or lesser one doesn't. */
    val priority: Int
        get() = when (this) {
            send -> 0
            replyDone -> 1
            catchUpDone -> 2
            error -> 3
            needsInput -> 4
        }
}

/**
 * Plays the vocabulary from reducers, beside `haptics.play`. The live client adds volume and one
 * sound per burst.
 */
class SoundEffectsClient(
    val play: (SoundEffect) -> Unit = {},
    /** Loads every sound so the first plays without delay. */
    val prepare: () -> Unit = {},
) {
    companion object Key : DependencyKey<SoundEffectsClient> {
        override val liveValue: SoundEffectsClient by lazy {
            val sounds by lazy { LiveSoundEffects() }
            SoundEffectsClient(play = { sounds.play(it) }, prepare = { sounds.prepare() })
        }
        override val testValue: SoundEffectsClient get() = SoundEffectsClient()
    }
}

/** Settings › Experimental › Sound Effects › Volume, 0–1 of the rendered level. */
val SharedKeys.soundEffectsVolume: SharedKey<Double> get() = SoundEffectsKeys.volume

/** Settings › Experimental › Sound Effects › Send Sound. Off: the `.tap` already answers the finger. */
val SharedKeys.soundEffectsSend: SharedKey<Boolean> get() = SoundEffectsKeys.send

private object SoundEffectsKeys {
    val volume = SharedKey.appStorage("sound-effects-volume", 1.0)
    val send = SharedKey.appStorage("sound-effects-send", false)
}

/**
 * One sound per burst: inside [WINDOW] of the last sound, only a more urgent one plays (a request
 * over a reply, never two replies). Times are [Clock.now] readings.
 */
class SoundEffectThrottle {
    private var last: SoundEffect? = null
    private var lastAt: Duration? = null

    fun admits(effect: SoundEffect, now: Duration): Boolean {
        val last = last
        val lastAt = lastAt
        if (last != null && lastAt != null && now - lastAt < WINDOW && effect.priority <= last.priority) return false
        this.last = effect
        this.lastAt = now
        return true
    }

    companion object {
        val WINDOW: Duration = 1.seconds
    }
}

/** Where admitted sounds go: the bundled players, or a recorder in tests. */
interface SoundEffectOutput {
    fun prepare()
    fun play(effect: SoundEffect, volume: Float)
}

/**
 * The gate in front of the players: the volume and the throttle. Android's ringer mode is checked
 * by the players.
 */
class LiveSoundEffects(
    private val now: () -> Duration = { dependency(Clock).now() },
    private val volume: () -> Double = { shared(SharedKeys.soundEffectsVolume).value },
    output: SoundEffectOutput? = null,
) {
    private val output: SoundEffectOutput = output ?: SoundEffectsPlayback.live()
    private val throttle = SoundEffectThrottle()
    private val lock = Any()

    fun prepare() {
        output.prepare()
    }

    /**
     * Whether [effect] went to the players. Never at zero volume or inside a burst; the players
     * then skip it unless the app is active and no call, dictation or media holds the session.
     */
    fun play(effect: SoundEffect): Boolean {
        val level = volume().coerceIn(0.0, 1.0)
        val admitted = level > 0 && synchronized(lock) { throttle.admits(effect, now()) }
        if (!admitted) return false
        LabMarks.mark("sound_effect_${effect.rawValue}")
        output.play(effect, level.toFloat())
        return true
    }
}
