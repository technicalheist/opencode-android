package ai.opencode.feature.voice

import ai.opencode.core.dependencies.*
import android.annotation.SuppressLint
import android.content.Context
import android.hardware.*
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Proximity covered while held up. Monitoring owns a proximity-only screen-off lock (not a
 * CPU/screen-on lock), released immediately in monitor's finally.
 */
class RaiseToEarDetector(
    private val context: Context? = null,
    private val clock: Clock = dependency(Clock),
    private val heldUp: (() -> Boolean)? = null,
    private val gravitySource: (() -> Gravity?)? = null,
    private val onMotion: ((Boolean) -> Unit)? = null,
    private val onNote: (String) -> Unit = { dependency(VoiceCallLogClient).noteCurrent(VoiceCallLog.Note.Kind.marker, it) },
) : SensorEventListener {
    private val manager = context?.getSystemService(SensorManager::class.java)
    private val motionSensor = manager?.let { it.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: it.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    private var isMotionOn = false
    private var awaitsFirstSample = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var onRaise: (() -> Boolean)? = null
    private var onNear: ((Boolean) -> Unit)? = null
    private var callIsLive: () -> Boolean = { false }
    var isNear = false; private set
    var isAtEar = false; private set
    private var started = false
    private var gravity: Gravity? = null
    private var filtered = doubleArrayOf(0.0, 0.0, 0.0)
    private var hasAccelerometer = false
    private var proximityScreen: PowerManager.WakeLock? = null
    data class Gravity(val x: Double, val y: Double, val z: Double)
    /**
     * [onRaise] reports whether a call took the raise (started, or already running); one refused
     * (while dictating) doesn't leave the phone at the ear, so the next raise counts at once.
     * [callIsLive] picks the posture: starting a call needs [isAtEarPosture], a call already
     * running moves to the receiver on the looser [isHeldUp].
     */
    @SuppressLint("WakelockTimeout") // Proximity monitor lifetime, released by stop in monitor's finally.
    fun start(onRaise: () -> Boolean, onNear: (Boolean) -> Unit, callIsLive: () -> Boolean = { false }) {
        if (started) return
        this.onRaise = onRaise; this.onNear = onNear; this.callIsLive = callIsLive
        val proximity = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY) ?: return
        started = manager.registerListener(this, proximity, SensorManager.SENSOR_DELAY_NORMAL, Handler(Looper.getMainLooper()))
        if (!started) return
        context?.getSystemService(PowerManager::class.java)?.let { power ->
            if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximityScreen = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "opencode:raise-to-ear").apply {
                    setReferenceCounted(false); acquire()
                }
            }
        }
    }
    fun stop() {
        pending?.cancel(); pending = null; manager?.unregisterListener(this); started = false; gravity = null; hasAccelerometer = false
        proximityScreen?.let { if (it.isHeld) it.release() }; proximityScreen = null
        if (isAtEar) onNear?.invoke(false)
        isAtEar = false; isNear = false; updateMotion(); onRaise = null; onNear = null; callIsLive = { false }
        scope.cancel()
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            proximityChanged(event.values[0] < event.sensor.maximumRange && event.values[0] < 5f)
        } else {
            for (i in 0..2) {
                val value = event.values[i].toDouble()
                filtered[i] = if (event.sensor.type == Sensor.TYPE_GRAVITY || !hasAccelerometer) value else filtered[i] * 0.8 + value * 0.2
            }
            hasAccelerometer = true
            // CoreMotion gravity points down; Android's gravity sensor reports the
            // acceleration opposing gravity. Negate and normalize to units of g.
            gravity = Gravity(-filtered[0] / SensorManager.GRAVITY_EARTH, -filtered[1] / SensorManager.GRAVITY_EARTH, -filtered[2] / SensorManager.GRAVITY_EARTH)
            motionSampled()
        }
    }
    fun proximityChanged(near: Boolean) {
        if (near == isNear) return
        isNear = near; val hadPending = pending != null; pending?.cancel(); pending = null; awaitsFirstSample = false
        if (near && isAtEar) {
            if (hadPending) onNote("Cancelled lower (proximity re-covered at ear)")
        } else if (near) {
            // Motion starts with the cover, so the posture as it's covered comes from its first samples.
            pending = scope.launch {
                clock.sleep(motionPoll)
                awaitsFirstSample = heldUp == null && currentGravity() == null
                if (!isHeldUpForRaise()) { pending = null; updateMotion(); return@launch }
                clock.sleep(dwell - motionPoll)
                completeRaise()
            }
        } else if (isAtEar) {
            val maxPolls = ((lowerCap - lowerDwell) / motionPoll).roundToInt().coerceAtLeast(0)
            pending = scope.launch {
                clock.sleep(lowerDwell)
                var polls = 0
                while (polls < maxPolls) {
                    if (isNear || !isAtEar) return@launch
                    if (!isHeldUp(false)) break
                    polls++; clock.sleep(motionPoll)
                }
                completeLower(polls >= maxPolls)
            }
        }
        updateMotion()
    }
    /** A cover with no sample yet at [motionPoll] takes its posture from the first one. */
    internal fun motionSampled() {
        if (!awaitsFirstSample) return
        awaitsFirstSample = false
        if (isAtEar || pending == null || isHeldUpForRaise()) return
        pending?.cancel(); pending = null; updateMotion()
    }
    private fun completeRaise() {
        pending = null
        if (isNear && isHeldUpForRaise()) {
            isAtEar = true; onNear?.invoke(true)
            if (onRaise?.invoke() != true) isAtEar = false
        }
        updateMotion()
    }
    private fun completeLower(reachedCap: Boolean) {
        if (isNear || !isAtEar) return
        val capped = reachedCap && isHeldUp(false); isAtEar = false; pending = null
        val duration = if (capped) lowerCap else lowerDwell
        onNote("Debounced lower (${duration.inWholeMilliseconds} ms clear${if (capped) " cap reached" else ", phone lowered"})")
        onNear?.invoke(false)
        updateMotion()
    }
    /** Motion runs while a raise or a lower is being decided, or at the ear. */
    private fun updateMotion() {
        val on = isAtEar || pending != null
        if (on == isMotionOn) return
        isMotionOn = on
        onMotion?.invoke(on)
        val sensor = motionSensor ?: return
        if (on) {
            gravity = null; hasAccelerometer = false
            manager?.registerListener(this, sensor, 100000, Handler(Looper.getMainLooper()))
        } else {
            manager?.unregisterListener(this, sensor)
        }
    }
    /**
     * To start a call: held up with the screen near vertical ([isAtEarPosture]), not tipped back
     * to read. A live call keeps the looser [isHeldUp] for moving to the receiver. No motion
     * data: trust proximity.
     */
    private fun isHeldUpForRaise(): Boolean {
        heldUp?.let { return it() }
        val gravity = currentGravity() ?: return true
        return if (callIsLive()) isHeldUp(gravity) else isAtEarPosture(gravity)
    }
    private fun currentGravity(): Gravity? = if (gravitySource != null) gravitySource.invoke() else gravity
    private fun isHeldUp(default: Boolean) = heldUp?.invoke() ?: currentGravity()?.let(::isHeldUp) ?: default
    companion object {
        /** How long the phone must be up and covered before it counts as raised. At 350 ms a
         * finger or palm passing over the sensor while reading could start a call. */
        val dwell = 600.milliseconds; val lowerDwell = 800.milliseconds; val lowerCap = 2500.milliseconds; val motionPoll = 100.milliseconds
        fun isSupported(context: Context) = context.getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_PROXIMITY) != null
        /** Upright or tilted on its side (with the top never pointing down into a pocket), rather
         * than face up or down on a table. Keeps a call at the ear. */
        fun isHeldUp(gravity: Gravity) = abs(gravity.z) < 0.75 && (gravity.y < -0.35 || (abs(gravity.x) > 0.55 && gravity.y < -0.15))
        /** Held up with the screen within about 30° of vertical, as against a cheek. Reading tips
         * the screen back 40° or more (|z| ≥ 0.65), where a hand over the sensor isn't a raise. */
        fun isAtEarPosture(gravity: Gravity) = abs(gravity.z) < 0.5 && isHeldUp(gravity)
    }
}
class RaiseToEarClient(
    val isSupported: () -> Boolean,
    /** onRaise, onNear, callIsLive ([RaiseToEarDetector.start]). */
    val monitor: suspend (() -> Boolean, (Boolean) -> Unit, () -> Boolean) -> Unit,
) {
    companion object Key : DependencyKey<RaiseToEarClient> {
        override val liveValue by lazy {
            val context = dependency(AppContext)
            RaiseToEarClient({ RaiseToEarDetector.isSupported(context) }, { onRaise, onNear, callIsLive ->
                withContext(Dispatchers.Main.immediate) {
                    val detector = RaiseToEarDetector(context)
                    try { detector.start(onRaise, onNear, callIsLive); awaitCancellation() } finally { detector.stop() }
                }
            })
        }
        override val testValue = RaiseToEarClient({ false }, { _, _, _ -> })
    }
}
class VoiceAtEarBox(initial: Boolean = false) {
    @Volatile var current = initial
    override fun equals(other: Any?) = other is VoiceAtEarBox
    override fun hashCode() = 0
}
