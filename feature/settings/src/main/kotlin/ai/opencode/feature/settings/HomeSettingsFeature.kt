package ai.opencode.feature.settings

import ai.opencode.core.experiments.key
import ai.opencode.core.dependencies.Dependency
import ai.opencode.core.experiments.ExperimentalFeature
import ai.opencode.core.haptics.Haptic
import ai.opencode.core.haptics.HapticsClient
import ai.opencode.core.sharing.AllExperiments
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.allExperimentsSkips
import ai.opencode.core.sharing.restoreFromAll
import ai.opencode.core.sharing.shared
import ai.opencode.core.sharing.switchAllOn
import ai.opencode.core.sound.SoundEffect
import ai.opencode.core.sound.SoundEffectsClient
import ai.opencode.core.sound.soundEffectsSend
import ai.opencode.core.sound.soundEffectsVolume
import ai.opencode.core.store.Effect
import ai.opencode.core.store.Reduced
import ai.opencode.core.store.Reducer
import ai.opencode.core.ui.AppTheme
import ai.opencode.core.ui.AppearanceColorScheme
import ai.opencode.core.ui.ThemeSharedKeys
import ai.opencode.feature.voice.CallVoice
import ai.opencode.feature.voice.ServerVoiceClient
import ai.opencode.feature.voice.ServerVoiceProvider
import ai.opencode.feature.voice.ServerVoiceSupport
import ai.opencode.feature.voice.ServerVoiceModelsAnswer
import ai.opencode.feature.voice.VoiceBroker
import ai.opencode.feature.voice.VoiceDelegatorChoice
import ai.opencode.feature.voice.VoiceEngine
import ai.opencode.feature.voice.VoiceFeature
import ai.opencode.feature.voice.VoiceModelsCache
import ai.opencode.feature.voice.VoiceReadiness
import ai.opencode.feature.voice.VoiceServerChoice
import ai.opencode.feature.voice.VoiceSharedKeys
import ai.opencode.server.OpenCodeServer
import java.util.Locale

/**
 * Moved down to `:feature:settings`: preferences and their platform effects. Every write goes
 * through [setShared], which skips unchanged values.
 *
 * Developer Mode and Demo Call Audio are written by `:devtools`' `DevToolsFeature` instead.
 */
class HomeSettingsFeature : Reducer<HomeSettingsFeature.State, HomeSettingsFeature.Action> {
    /** Home's server, for the voice broker check; the sidebar flag is a shared key. */
    data class State(val server: OpenCodeServer? = null)

    sealed interface Action {
        data object SidebarToggled : Action
        data class ExperimentalFeatureChanged(val feature: ExperimentalFeature, val on: Boolean) : Action
        /** Settings › Experimental › All Experiments. */
        data class AllExperimentsChanged(val on: Boolean) : Action
        /** All Experiments switched this experiment; its side effects run here. */
        data class ExperimentalFeatureApplied(val feature: ExperimentalFeature, val on: Boolean) : Action
        /** Settings › Experimental › Sound Effects › Volume (0…1). */
        data class SoundEffectsVolumeChanged(val volume: Double) : Action
        /** Settings › Experimental › Sound Effects › Send Sound. */
        data class SoundEffectsSendChanged(val on: Boolean) : Action
        data class VoiceModeEnabledChanged(val on: Boolean) : Action
        data class RaiseToTalkChanged(val on: Boolean) : Action
        data class VoiceEngineChanged(val engine: VoiceEngine) : Action
        data class ServerVoiceProviderChanged(val provider: ServerVoiceProvider) : Action
        /** Settings › Voice › Voice Provider. */
        data class VoiceServerChanged(val choice: VoiceServerChoice) : Action
        /** Settings › Voice › Delegator (OpenCode Session Delegator). */
        data class VoiceDelegatorChoiceChanged(val choice: VoiceDelegatorChoice) : Action
        /** Asks the server that brokers calls whether it offers the voice routes. */
        data object CheckServerVoiceSupport : Action
        data class ServerVoiceSupportLoaded(val serverID: String, val support: ServerVoiceSupport) : Action
        /**
         * The connected server's voice models, so Automatic knows before a call whether it can take
         * one or this phone's key should.
         */
        data class VoiceModelsLoaded(val serverID: String, val asked: Int, val answer: ServerVoiceModelsAnswer) : Action
        data class CallVoiceChanged(val voice: CallVoice) : Action
        data class OpenAIAPIKeyChanged(val key: String?) : Action
        data class XaiAPIKeyChanged(val key: String?) : Action
        data class VerifiedVoiceKeyFingerprintChanged(val fingerprint: String?) : Action
        data object OnboardingPlaygroundOpened : Action
        /** Settings › Experimental › Show Welcome (Welcome Preview). */
        data object WelcomeOpened : Action
        data class AppThemeChanged(val theme: AppTheme) : Action
        data class AppearanceColorSchemeChanged(val scheme: AppearanceColorScheme) : Action
        /** Settings › Language: null follows the system. */
        data class AppLanguageChanged(val locale: Locale?) : Action
        data class ReturnKeySendsChanged(val on: Boolean) : Action
        data class AgentSelectionChanged(val on: Boolean) : Action
        /** Agent Selection: the agent new sessions start with ("" is the server's). */
        data class DefaultAgentChanged(val id: String) : Action
    }

    private val haptics by Dependency(HapticsClient)
    private val appLanguage by Dependency(AppLanguageClient)
    private val serverVoice by Dependency(ServerVoiceClient)
    private val soundEffects by Dependency(SoundEffectsClient)

    private fun <T> setShared(key: SharedKey<T>, next: T): Boolean {
        val current = shared(key)
        if (current.value == next) return false
        current.set(next)
        return true
    }

    /**
     * What switching one experiment does beyond its saved switch. Home ends a Session Live
     * Activity switched off (`HomeFeatureSettings`).
     */
    private fun experimentalFeatureApplied(feature: ExperimentalFeature, on: Boolean): Effect<Action> {
        if (feature == ExperimentalFeature.soundEffects) {
            if (ExperimentalFeature.soundEffects.isActive(on)) soundEffects.prepare()
        }
        return Effect.none()
    }

    override fun reduce(state: State, action: Action): Reduced<State, Action> {
        when (action) {
            Action.SidebarToggled -> shared(SharedKeys.sidebarShown).update { !it }
            is Action.ExperimentalFeatureChanged -> {
                if (!setShared(action.feature.key, action.on)) return Reduced(state)
                // Switched by hand while All Experiments is on: off is a skip, kept for next time.
                if (shared(AllExperiments.key).value && action.feature.joinsAll) {
                    setShared(allExperimentsSkips(action.feature), !action.on)
                }
                return Reduced(state, experimentalFeatureApplied(action.feature, action.on))
            }
            is Action.AllExperimentsChanged -> {
                if (!setShared(AllExperiments.key, action.on)) return Reduced(state)
                val changed = if (action.on) ExperimentalFeature.switchAllOn() else ExperimentalFeature.restoreFromAll()
                val effects = mutableListOf<Effect<Action>>()
                for (change in changed) effects.add(Effect.send(Action.ExperimentalFeatureApplied(change.feature, change.on)))
                return Reduced(state, Effect.merge(effects))
            }
            is Action.ExperimentalFeatureApplied ->
                return Reduced(state, experimentalFeatureApplied(action.feature, action.on))
            is Action.SoundEffectsVolumeChanged ->
                // Each new level plays a sample at it.
                if (setShared(SharedKeys.soundEffectsVolume, action.volume.coerceIn(0.0, 1.0))) {
                    soundEffects.play(SoundEffect.replyDone)
                }
            is Action.SoundEffectsSendChanged -> setShared(SharedKeys.soundEffectsSend, action.on)
            is Action.VoiceModeEnabledChanged -> setShared(SharedKeys.voiceModeEnabled, action.on)
            is Action.RaiseToTalkChanged -> setShared(SharedKeys.raiseToTalk, action.on)
            is Action.VoiceEngineChanged -> {
                setShared(SharedKeys.voiceEngineMigratedToGPTLive, true)
                setShared(VoiceSharedKeys.voiceEngine, action.engine)
            }
            is Action.ServerVoiceProviderChanged -> setShared(VoiceSharedKeys.serverVoiceProvider, action.provider)
            is Action.VoiceServerChanged -> setShared(VoiceSharedKeys.voiceServerChoice, action.choice)
            is Action.VoiceDelegatorChoiceChanged -> setShared(VoiceSharedKeys.voiceDelegatorChoice, action.choice)
            Action.CheckServerVoiceSupport -> {
                val choice = shared(VoiceSharedKeys.voiceServerChoice)
                if (choice.value == VoiceServerChoice.Connected) choice.update { it.pinningConnected(state.server) }
                val provider = shared(VoiceSharedKeys.serverVoiceProvider).value
                val readiness = VoiceReadiness(
                    answers = shared(VoiceSharedKeys.voiceModelsByServer).value,
                    support = shared(VoiceSharedKeys.serverVoiceSupportByServer).value,
                    hasPhoneKey = !shared(SharedKeys.openAIAPIKey).value.orEmpty().isBlank(),
                    engine = shared(VoiceSharedKeys.voiceEngine).value,
                )
                val pick = VoiceBroker(
                    choice = choice.value,
                    provider = provider,
                    connected = state.server,
                    saved = VoiceFeature.savedServers,
                    readiness = readiness,
                )
                // The state can be empty before a server connects.
                val home = state.server
                val asksModels = home != null && pick.isAutomatic && provider == ServerVoiceProvider.openAI
                val broker = pick.server
                if (!asksModels && broker == null) return Reduced(state)
                val epoch = home?.let { VoiceModelsCache.epoch(it.persistenceID) } ?: 0
                val client = serverVoice
                return Reduced(
                    state,
                    Effect.run { send ->
                        if (asksModels && home != null) {
                            val models = client.models(home)
                            send(
                                Action.VoiceModelsLoaded(
                                    serverID = home.persistenceID,
                                    asked = epoch,
                                    answer = models?.let(ServerVoiceModelsAnswer::Listed) ?: ServerVoiceModelsAnswer.NoAnswer,
                                ),
                            )
                        }
                        if (broker == null) return@run
                        val support = client.checkSupport(broker)
                        send(Action.ServerVoiceSupportLoaded(serverID = broker.persistenceID, support = support))
                    },
                )
            }
            is Action.VoiceModelsLoaded -> VoiceModelsCache.record(action.answer, action.serverID, action.asked)
            is Action.ServerVoiceSupportLoaded -> {
                if (action.support != ServerVoiceSupport.supported && action.support != ServerVoiceSupport.unsupported) return Reduced(state)
                val supportByServer = shared(VoiceSharedKeys.serverVoiceSupportByServer)
                if (supportByServer.value[action.serverID] != action.support) {
                    supportByServer.update { it + (action.serverID to action.support) }
                }
            }
            is Action.CallVoiceChanged -> setShared(VoiceSharedKeys.callVoice, action.voice)
            is Action.OpenAIAPIKeyChanged -> setShared(SharedKeys.openAIAPIKey, action.key)
            is Action.XaiAPIKeyChanged -> setShared(SharedKeys.xaiAPIKey, action.key)
            is Action.VerifiedVoiceKeyFingerprintChanged ->
                setShared(SharedKeys.verifiedVoiceKeyFingerprint, action.fingerprint)
            Action.OnboardingPlaygroundOpened, Action.WelcomeOpened -> Unit
            is Action.AppThemeChanged ->
                // The theme cross-fades in place: OpenCodeAppTheme observes the key.
                if (setShared(ThemeSharedKeys.appTheme, action.theme)) haptics.play(Haptic.selection)
            is Action.AppearanceColorSchemeChanged ->
                if (setShared(ThemeSharedKeys.appearanceColorScheme, action.scheme)) haptics.play(Haptic.selection)
            is Action.AppLanguageChanged -> {
                val client = appLanguage
                if (client.current() == action.locale) return Reduced(state)
                haptics.play(Haptic.selection)
                return Reduced(state, Effect.run { client.set(action.locale) })
            }
            is Action.ReturnKeySendsChanged -> setShared(SharedKeys.returnKeySends, action.on)
            is Action.AgentSelectionChanged -> setShared(SharedKeys.agentSelection, action.on)
            is Action.DefaultAgentChanged -> setShared(SharedKeys.defaultAgentID, action.id)
        }
        return Reduced(state, Effect.none())
    }
}
