package ai.opencode.devtools.perf

import ai.opencode.core.diagnostics.PerfTrace
import ai.opencode.core.log.AppLog
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.ui.OpenCodeFonts
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** One half-second window of frame and recomposition stats. */
data class PerfWindow(
    val fps: Double = 0.0,
    val frames: Int = 0,
    val janky: Int = 0,
    val worstMs: Double = 0.0,
    val recomposedScopesPerSecond: Int = 0,
    val compositions: Int = 0,
)

/**
 * Collects per-frame durations from the window's `FrameMetrics` and counts recomposed scopes. Frame
 * stalls ≥ 250 ms are logged (`frame_stall`).
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
private class PerfSampler(private val window: Window, private val recomposer: Recomposer?, private val publish: (PerfWindow) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val windowMs = 500L
    private var frames = 0
    private var janky = 0
    private var worstNs = 0L
    private val scopes = AtomicInteger(0)
    private var windowStart = System.nanoTime()
    private var registrationHandle: CompositionObserverHandle? = null
    private val compositionHandles = HashMap<ObservableComposition, CompositionObserverHandle>()

    private val frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        val deadline = metrics.getMetric(FrameMetrics.DEADLINE)
        frames += 1
        if (deadline > 0 && total > deadline) janky += 1
        if (total > worstNs) worstNs = total
        if (total >= 250_000_000L) AppLog.perf.info { "frame_stall ms=${total / 1_000_000}" }
    }

    @OptIn(ExperimentalComposeRuntimeApi::class)
    private val scopeObserver = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) { scopes.incrementAndGet() }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val seconds = (now - windowStart) / 1e9
            val recomposed = scopes.getAndSet(0)
            val sample = PerfWindow(
                fps = if (seconds > 0) frames / seconds else 0.0,
                frames = frames,
                janky = janky,
                worstMs = worstNs / 1e6,
                recomposedScopesPerSecond = if (seconds > 0) (recomposed / seconds).toInt() else 0,
                compositions = compositionHandles.size,
            )
            PerfTrace.counter("hud_fps", sample.fps.toLong())
            PerfTrace.counter("hud_recomposed_scopes", recomposed.toLong())
            publish(sample)
            frames = 0
            janky = 0
            worstNs = 0
            windowStart = now
            handler.postDelayed(this, windowMs)
        }
    }

    @OptIn(ExperimentalComposeRuntimeApi::class)
    fun start() {
        window.addOnFrameMetricsAvailableListener(frameListener, handler)
        registrationHandle = recomposer?.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handler.post { compositionHandles[composition] = composition.setObserver(scopeObserver) }
            }

            override fun onCompositionUnregistered(composition: ObservableComposition) {
                handler.post { compositionHandles.remove(composition)?.dispose() }
            }
        })
        handler.postDelayed(tick, windowMs)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        runCatching { window.removeOnFrameMetricsAvailableListener(frameListener) }
        registrationHandle?.dispose()
        compositionHandles.values.forEach { it.dispose() }
        compositionHandles.clear()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The perf HUD: FPS, janky frames, the worst frame, recomposed scopes per second, and the
 * slow-motion scale.
 */
@Composable
fun PerfHud(modifier: Modifier = Modifier) {
    val view = LocalView.current
    var sample by remember { mutableStateOf(PerfWindow()) }
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val recomposer = view.findViewTreeCompositionContext() as? Recomposer
        val sampler = window?.let { PerfSampler(it, recomposer) { next -> sample = next } }
        sampler?.start()
        onDispose { sampler?.stop() }
    }
    val text = buildString {
        append(String.format(Locale.ROOT, "%.0f fps", sample.fps))
        append(" · ${sample.janky}/${sample.frames} janky")
        append(String.format(Locale.ROOT, " · worst %.0f ms", sample.worstMs))
        append("\n${sample.recomposedScopesPerSecond} scopes/s · ${sample.compositions} compositions")
        val scale = AnimationTimeScale.shared.observedScale
        if (scale != 1.0) append(" · ${TimeScaleLabel.format(scale)}")
    }
    val alarming = sample.janky > 0 || sample.worstMs > 34
    Box(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color.Black.copy(alpha = 0.72f),
            contentColor = if (alarming) Color(0xFFFFB74D) else Color(0xFF9CFF9C),
            modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = "Performance HUD" },
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = OpenCodeFonts.monospace))
            }
        }
    }
}

/** "0.25×" for a slow-motion scale. */
object TimeScaleLabel {
    fun format(scale: Double): String {
        val text = if (scale == Math.rint(scale)) scale.toInt().toString() else scale.toString().trimEnd('0')
        return "$text×"
    }
}
