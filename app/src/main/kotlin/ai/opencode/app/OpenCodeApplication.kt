package ai.opencode.app

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.haptics.AudioSessionHaptics
import ai.opencode.core.log.AppLog
import ai.opencode.core.scene.ActiveScene
import ai.opencode.devtools.DevTools
import ai.opencode.feature.media.AgentImageSizeCache
import ai.opencode.feature.session.AgentActivityLedger
import ai.opencode.feature.transcript.markdown.withMarkdownStream
import ai.opencode.server.EventBatcherHooks
import ai.opencode.server.retry.AppPhase
import ai.opencode.server.retry.AppPhases
import android.app.Application
import androidx.core.os.UserManagerCompat
import androidx.work.Configuration
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import ai.opencode.core.dependencies.dependency
import ai.opencode.devtools.DevToolsClient
import ai.opencode.feature.settings.AppVersion
import ai.opencode.feature.system.liveactivity.DictationLiveActivityClient
import ai.opencode.feature.system.liveactivity.SessionLiveActivityClient
import ai.opencode.feature.system.liveactivity.VoiceLiveActivityClient
import ai.opencode.feature.voice.VoiceCallLogClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

/**
 * Process entry. Installs the application context as the one process-wide override; no synchronous
 * disk reads beyond what the first frame needs.
 */
class OpenCodeApplication : Application(), Configuration.Provider {
    private val launchScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Some devices report a locked user while storage is readable (issuetracker.google.com/578572401).
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setInitializationExceptionHandler { error ->
                if (UserManagerCompat.isUserUnlocked(this)) throw error
                AppLog.app.error { "WorkManager skipped its start-up checks: user locked" }
            }
            .build()

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate() {
        super.onCreate()
        // Compose Foundation 1.12.1 crashes on mouse presses after the last of multiple selectable texts.
        ComposeFoundationFlags.isMouseSelectionBetweenTextEnabled = false
        DependencyValues.installRoot { this[AppContext] = applicationContext }
        // The batcher prepares streaming Markdown and records every event into the agent activity
        // ledger.
        DependencyValues.installRoot {
            val markdown = this[EventBatcherHooks].withMarkdownStream()
            this[EventBatcherHooks] = EventBatcherHooks(
                prepareMarkdown = markdown.prepareMarkdown,
                record = { event ->
                    markdown.record(event)
                    AgentActivityLedger.shared.record(event)
                },
            )
        }
        // Foreground/background phases for retries and reconnects, from the first activity start.
        AppPhases.install(this)
        // The app's windows, from the first activity: the one in use and the one a URL opens beside.
        ActiveScene.install(this)
        if (BuildConfig.DEBUG) AppLog.minimumPriority = Log.DEBUG
        DevTools.install()
        // Voice's admitted sends, log uploads, diagnostics sinks and screen hold (installed
        // before anything resolves their keys).
        AppDependencies.install(this)
        // Open in New Window (tablet multi-instance windows).
        SceneWindows.install()
        // The haptics diagnostics trace's `dictating=`/`call=`.
        AudioSessionHaptics.isCallActive = { SceneWindows.all.any { it.isResolved && it.store.state.hasLiveCall } }
        AudioSessionHaptics.isDictating = { SceneWindows.all.any { it.isResolved && it.store.state.home?.dictationActivity != null } }
        // Version, distribution, model, system.
        val device = dependency(DevToolsClient).device()
        AppLog.app.notice { "Launch: ${AppVersion.current.label}, ${device.distribution}, ${device.model}, ${device.system}" }
        // A call, a recording or a followed run never outlives its process: end any ongoing
        // notification a killed process left behind.
        val voiceActivity = dependency(VoiceLiveActivityClient)
        val dictationActivity = dependency(DictationLiveActivityClient)
        val sessionActivity = dependency(SessionLiveActivityClient)
        launchScope.launch { voiceActivity.endAll() }
        launchScope.launch { dictationActivity.endAll() }
        launchScope.launch { sessionActivity.endAll() }
        // A call log a previous process left in progress is saved as interrupted, off the main
        // thread. Resolved after AppDependencies so the recorder gets the installed uploader.
        val voiceCallLog = dependency(VoiceCallLogClient)
        launchScope.launch(Dispatchers.IO) { voiceCallLog.recoverInterrupted() }
        // Remembered image shapes are read off the main thread.
        AgentImageSizeCache.live.warm()
        launchScope.launch {
            AppPhases.phases().collect { phase ->
                if (phase == AppPhase.Background) launch(Dispatchers.IO) { voiceCallLog.persistOpenCalls() }
            }
        }
    }
}
