package ai.opencode.core.experiments

import ai.opencode.core.dependencies.AppContext
import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.dependencies.dependency
import ai.opencode.core.dependencies.withDependencies
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.InMemorySharedBytes
import ai.opencode.core.sharing.LiveSharedBytes
import ai.opencode.core.sharing.SharedStorageKind
import ai.opencode.core.sharing.SharedStore
import ai.opencode.core.sharing.experimental
import ai.opencode.core.sharing.shared
import ai.opencode.core.testing.TestIsolationRule
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class ExperimentalFeatureTests {
    @get:Rule val isolation = TestIsolationRule()

    /** A fresh test container's storage. */
    private fun <R> withFreshStorage(block: () -> R): R = DependencyValues.withValues(DependencyValues.testing(), block)

    /**
     * A raw saved value, written even when it equals the key's default (a nullable key, so `false`
     * is stored rather than skipped).
     */
    private fun SharedStore.save(id: String, value: Boolean) = reference(SharedKey.appStorage<Boolean?>(id, null)).set(value)

    private fun SharedStore.has(id: String) = contains(SharedKey.appStorage<Boolean?>(id, null))

    private fun SharedStore.bool(id: String) = reference(SharedKey.appStorage(id, false)).value

    @Test
    fun experimentsAreOffByDefaultAndPersistUnderAStableKey() {
        withFreshStorage {
            assertFalse(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            assertFalse(inAppBrowser.value)
            inAppBrowser.set(true)
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
            assertEquals("The key is the phone's saved choice: never rename it", "experimental-in-app-browser", inAppBrowser.key.id)
            assertTrue(dependency(SharedStore).has("experimental-in-app-browser"))
        }
        assertFalse(ExperimentalFeatures.testValue(ExperimentalFeature.inAppBrowser))
        assertTrue(ExperimentalFeatures.enabling(setOf(ExperimentalFeature.inAppBrowser))(ExperimentalFeature.inAppBrowser))
    }

    /** No experiment starts on: graduated features have no switch at all. */
    @Test
    fun everyExperimentIsOffUntilSwitchedOn() {
        withFreshStorage {
            for (feature in ExperimentalFeature.allCases) {
                assertFalse("$feature starts off", ExperimentalFeatures.liveValue(feature))
            }
        }
    }

    /** Welcome Preview moved to Developer › Playgrounds; it's not in the pane. */
    @Test
    fun welcomePreviewLeftExperimental() {
        assertEquals(listOf(ExperimentalFeature.welcomePreview), ExperimentalFeature.allCases.filter { it.group == null })
        assertFalse(ExperimentalGroup.entries.flatMap { it.features }.contains(ExperimentalFeature.welcomePreview))
    }

    @Test
    fun everyExperimentHasACopyForSettings() {
        for (feature in ExperimentalFeature.allCases) {
            // No summary under the title, so only the title is checked.
            assertTrue(feature.title.isNotEmpty())
        }
        assertEquals("In-App Browser", ExperimentalFeature.inAppBrowser.title)
    }

    @Test
    fun everyExperimentSitsInOneGroupInAStableOrder() {
        assertEquals(listOf(ExperimentalGroup.composer, ExperimentalGroup.calls, ExperimentalGroup.drawer, ExperimentalGroup.transcript), ExperimentalGroup.entries)
        assertEquals(
            ExperimentalFeature.allCases.filter { it.group != null }.toSet(),
            ExperimentalGroup.entries.flatMap { it.features }.toSet(),
        )
        assertEquals(
            listOf(ExperimentalFeature.hideModels),
            ExperimentalGroup.composer.features,
        )
        assertEquals(
            listOf(
                ExperimentalFeature.backgroundCallIntent, ExperimentalFeature.voiceSessionDelegator,
                ExperimentalFeature.shareScreenWithAgents,
            ),
            ExperimentalGroup.calls.features,
        )
        assertEquals(
            listOf(
                ExperimentalFeature.inboxSidebar, ExperimentalFeature.sessionLiveActivity, ExperimentalFeature.soundEffects,
                ExperimentalFeature.serverAddressLinks,
            ),
            ExperimentalGroup.drawer.features,
        )
        assertEquals(listOf(ExperimentalFeature.inAppBrowser), ExperimentalGroup.transcript.features)
        assertEquals(listOf("Composer", "Calls", "Drawer", "Transcript"), ExperimentalGroup.entries.map { it.title })
    }

    /** Removed experiments and ones now always on leave no switch behind, whatever was saved. */
    @Test
    fun removedExperimentsLeaveNoSwitchBehind() {
        val retired = listOf(
            "session-switcher", "server-title-switch",
            "composer-autocomplete", "server-flow", "plus-menu-sheet", "project-icons",
            "floating-call", "call-tray", "image-link-previews", "living-orb", "new-messages-divider",
            "open-links-from-calls", "audio-in-calls", "server-details", "server-voice", "one-picker",
            "model-picker", "new-model-picker", "custom-model-picker",
        )
        val leftovers = listOf(
            "experimental-all-saved-living-orb", "experimental-all-skip-image-link-previews", "experimental-all-saved-server-voice",
            "experimental-all-saved-one-picker", "call-style-migrated-living-orb", "call-style-migrated-orb-default",
        )
        withFreshStorage {
            val defaults = dependency(SharedStore)
            for ((index, name) in retired.withIndex()) {
                defaults.save("experimental-$name", index % 2 == 0)
            }
            defaults.save("experimental-in-app-browser", true)
            for (name in retired) {
                assertNull(name, ExperimentalFeature.fromRawValue(name))
            }
            for (key in leftovers) {
                defaults.save(key, true)
            }
            ExperimentalFeature.removeRetiredSwitches(defaults)
            for (name in retired) {
                assertFalse(name, defaults.has("experimental-$name"))
            }
            for (key in leftovers) {
                assertFalse(key, defaults.has(key))
            }
            assertTrue("Live experiments keep their switch", defaults.bool("experimental-in-app-browser"))
        }
    }

    @Test
    fun voiceDemoModeRequiresDeveloperMode() {
        withFreshStorage {
            val developerMode = shared(SharedKeys.developerMode)
            val inAppBrowser = shared(experimental(ExperimentalFeature.inAppBrowser))
            val demoMode = shared(SharedKeys.voiceDemoMode)
            inAppBrowser.set(true)
            demoMode.set(true)
            developerMode.set(false)
            assertTrue(ExperimentalFeature.inAppBrowser.isActive(true))
            assertTrue(ExperimentalFeatures.liveValue(ExperimentalFeature.inAppBrowser))
        }
    }
}

/** The live storage behind shared values, under Robolectric for its Android context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveSharedStorageTests {
    @get:Rule val isolation = TestIsolationRule()

    @Test
    fun anInMemoryValueIsNeverEncoded() {
        var encodes = 0
        val strings = serializer<List<String>>()
        val counting = object : KSerializer<List<String>> by strings {
            override fun serialize(encoder: Encoder, value: List<String>) {
                encodes += 1
                strings.serialize(encoder, value)
            }
        }
        val key = SharedKey("in-memory-strings", emptyList(), counting, SharedStorageKind.InMemory)
        val store = withDependencies({ this[AppContext] = RuntimeEnvironment.getApplication() }) {
            SharedStore(LiveSharedBytes.create())
        }
        val value = store.reference(key)
        value.set(listOf("a"))
        value.update { it + "b" }
        store.flush()
        assertEquals(0, encodes)
        assertEquals(listOf("a", "b"), store.reference(key).value)
    }
}
