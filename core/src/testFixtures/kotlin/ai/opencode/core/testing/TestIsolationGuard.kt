package ai.opencode.core.testing

import ai.opencode.core.dependencies.DependencyValues
import ai.opencode.core.diagnostics.DiagnosticsRecorder
import ai.opencode.core.sharing.SharedKey
import ai.opencode.core.sharing.SharedKeys
import ai.opencode.core.sharing.shared
import ai.opencode.core.tuning.AnimationTimeScale
import ai.opencode.core.tuning.TuningAccess
import ai.opencode.core.tuning.TuningStore
import ai.opencode.core.ui.ThemeSharedKeys
import kotlinx.coroutines.sync.Mutex
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.MultipleFailureException
import org.junit.runners.model.Statement
import java.util.ServiceLoader
import java.util.UUID

/**
 * Catches a test that leaks process-wide state into the next one, resets it, and fails the test
 * that leaked.
 *
 * Shared keys and clients live in each test's dependency container ([DependencyValues.testing], a
 * `TestStore`'s), so they can't leak. What can: the process-wide singletons
 * ([AnimationTimeScale.shared], [TuningStore.shared], [DiagnosticsRecorder.shared],
 * [TuningAccess]), values written through the process root outside a store (`shared(key)` in a
 * plain test, which resolves the root's test container), and unmocked network (`:server`'s
 * `TestNetworkBlock`, plugged in by its test fixtures).
 *
 * Apply [TestIsolationRule] to a test class (`@get:Rule val isolation = TestIsolationRule()`).
 * After each test it reports blocked network requests and dirty [probes] as failures, resets
 * them, and drops the root's resolved test values ([DependencyValues.resetTestCache]).
 *
 * Modules add their own probes or violation sources with a [Plugin], found through
 * `META-INF/services/ai.opencode.core.testing.TestIsolationGuard$Plugin` on the test classpath
 * (`:server`'s test fixtures register the network block this way).
 */
object TestIsolationGuard {
    /** A piece of process-wide state and how to tell it leaked. */
    class Probe(
        val name: String,
        val expected: String,
        val isDirty: () -> Boolean,
        val snapshot: () -> String,
        val reset: () -> Unit,
    )

    /** Extends the guard from another module's test fixtures. */
    fun interface Plugin {
        fun install(guard: TestIsolationGuard)
    }

    /** Serializes top-level scopes so interleaving async tests can't mix state. */
    class ScopeGate {
        private val mutex = Mutex()
        suspend fun acquire() = mutex.lock()
        fun release() = mutex.unlock()
    }

    class TestContext internal constructor(
        val name: String,
        val isTopLevelScope: Boolean,
        val isCapturingScope: Boolean,
    ) {
        val id: UUID = UUID.randomUUID()
        private val networkViolations = mutableListOf<String>()

        fun addNetworkViolation(message: String) = synchronized(networkViolations) {
            if (message !in networkViolations) networkViolations += message
        }

        fun drainNetworkViolations(): List<String> = synchronized(networkViolations) {
            networkViolations.toList().also { networkViolations.clear() }
        }
    }

    private val lock = Any()
    private val activeContexts = mutableListOf<TestContext>()
    private val probeList = mutableListOf<Probe>()
    @Volatile private var installed = false

    val topLevelScopeGate = ScopeGate()

    /** Every registered probe, the core ones first. */
    val probes: List<Probe> get() = installIfNeeded().let { synchronized(lock) { probeList.toList() } }

    fun register(vararg probe: Probe) = synchronized(lock) { probeList += probe }

    fun installIfNeeded() {
        if (installed) return
        synchronized(lock) {
            if (installed) return
            installed = true
            probeList += coreProbes()
        }
        ServiceLoader.load(Plugin::class.java, TestIsolationGuard::class.java.classLoader).forEach { it.install(this) }
    }

    fun latestActiveContext(): TestContext? = synchronized(lock) { activeContexts.lastOrNull() }

    /** Host only, never the path, query or credentials. */
    fun recordBlockedNetworkRequest(scheme: String, host: String, method: String) {
        val context = latestActiveContext()
        val message = "TestIsolationGuard [network]: ${context?.name ?: "<unknown-test>"} made unmocked network request ($method $scheme://$host)"
        context?.addNetworkViolation(message) ?: System.err.println(message)
    }

    fun beginTest(name: String, isTopLevelScope: Boolean = true, isCapturingScope: Boolean = false): TestContext {
        installIfNeeded()
        return synchronized(lock) {
            TestContext(name, isTopLevelScope && activeContexts.isEmpty(), isCapturingScope).also { activeContexts += it }
        }
    }

    /** Ends [context]; the violations it found (and reset) when it's the outermost or a capturing scope. */
    fun endTest(context: TestContext): List<String> {
        val (shouldCheck, isOutermost) = synchronized(lock) {
            activeContexts.removeAll { it.id == context.id }
            val outermost = activeContexts.isEmpty()
            (context.isCapturingScope || (context.isTopLevelScope && outermost)) to outermost
        }
        if (!shouldCheck) {
            // A nested scope hands its network violations to the scope around it.
            val pending = context.drainNetworkViolations()
            latestActiveContext()?.let { outer -> pending.forEach(outer::addNetworkViolation) }
            return emptyList()
        }
        val violations = context.drainNetworkViolations().toMutableList()
        for (probe in probes) {
            if (!probe.isDirty()) continue
            violations += "TestIsolationGuard [${probe.name}]: ${context.name} leaked state '${probe.snapshot()}' (expected '${probe.expected}')"
            probe.reset()
        }
        if (isOutermost && !context.isCapturingScope) DependencyValues.resetTestCache()
        return violations
    }

    /**
     * Runs [body] while capturing leak and network violations instead of failing the current
     * test, so `TestIsolationGuardTests` can prove a deliberately leaking test is caught and
     * cleaned up.
     */
    suspend fun capturingViolations(
        testName: String = "deliberateLeakSample()",
        gate: ScopeGate? = null,
        body: suspend () -> Unit,
    ): List<String> {
        gate?.acquire()
        try {
            val context = beginTest(testName, isTopLevelScope = false, isCapturingScope = true)
            val failure = runCatching { body() }.exceptionOrNull()
            val violations = endTest(context)
            if (failure != null) throw failure
            return violations
        } finally {
            gate?.release()
        }
    }

    /** A probe on a shared key written through the process root (outside any store). */
    fun <T> sharedProbe(name: String, key: SharedKey<T>, expected: String, describe: (T) -> String = { it.toString() }): Probe {
        fun read(): T = DependencyValues.withValues(DependencyValues.root) { shared(key).value }
        return Probe(
            name = name,
            expected = expected,
            isDirty = { read() != key.default },
            snapshot = { describe(read()) },
            reset = { DependencyValues.withValues(DependencyValues.root) { shared(key).set(key.default) } },
        )
    }

    private fun coreProbes(): List<Probe> = listOf(
        Probe(
            name = "AnimationTimeScale.shared.scale",
            expected = "1.0",
            isDirty = { AnimationTimeScale.shared.scale != 1.0 },
            snapshot = { AnimationTimeScale.shared.scale.toString() },
            reset = { AnimationTimeScale.shared.reset() },
        ),
        Probe(
            name = "TuningStore.shared",
            expected = "enabled=false,overrides=0",
            isDirty = { TuningStore.shared.isEnabled || TuningStore.shared.overrides.isNotEmpty() },
            snapshot = { "enabled=${TuningStore.shared.isEnabled},overrides=${TuningStore.shared.overrides.size}" },
            reset = {
                TuningStore.shared.setEnabled(false)
                TuningStore.shared.resetAll()
            },
        ),
        Probe(
            name = "DiagnosticsRecorder.shared.isEnabled",
            expected = "false",
            isDirty = { DiagnosticsRecorder.shared.isEnabled },
            snapshot = { DiagnosticsRecorder.shared.isEnabled.toString() },
            reset = {
                DiagnosticsRecorder.shared.setEnabled(false)
                DiagnosticsRecorder.shared.clear()
            },
        ),
        Probe(
            name = "TuningAccess.actsAsReleaseOverride",
            expected = "false",
            isDirty = { TuningAccess.actsAsReleaseOverride },
            snapshot = { TuningAccess.actsAsReleaseOverride.toString() },
            reset = { TuningAccess.actsAsReleaseOverride = false },
        ),
        sharedProbe("Shared.uploadUnsupportedServerIDs", SharedKeys.uploadUnsupportedServerIDs, "[]"),
        sharedProbe("Shared.pinnedSessionKeys", SharedKeys.pinnedSessionKeys, "[]"),
        sharedProbe("Shared.mutedSessionKeys", SharedKeys.mutedSessionKeys, "[]"),
        sharedProbe("Shared.hiddenSessionKeys", SharedKeys.hiddenSessionKeys, "[]"),
        sharedProbe("Shared.favoriteModelIDs", SharedKeys.favoriteModelIDs, "[]"),
        sharedProbe("Shared.appTheme", ThemeSharedKeys.appTheme, "System"),
        sharedProbe("Shared.appearanceColorScheme", ThemeSharedKeys.appearanceColorScheme, "System"),
        sharedProbe("Shared.returnKeySends", SharedKeys.returnKeySends, "false"),
        sharedProbe("Shared.developerMode", SharedKeys.developerMode, "false"),
        sharedProbe("Shared.voiceDemoMode", SharedKeys.voiceDemoMode, "false"),
        sharedProbe("Shared.voiceModeEnabled", SharedKeys.voiceModeEnabled, "false"),
        sharedProbe("Shared.raiseToTalk", SharedKeys.raiseToTalk, "false"),
        sharedProbe("Shared.defaultAgentID", SharedKeys.defaultAgentID, ""),
    )
}

/** The JUnit side of [TestIsolationGuard]: `@get:Rule val isolation = TestIsolationRule()`. */
class TestIsolationRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val context = TestIsolationGuard.beginTest("${description.methodName}()")
            val failure = runCatching { base.evaluate() }.exceptionOrNull()
            val violations = TestIsolationGuard.endTest(context).map(::AssertionError)
            val all = listOfNotNull(failure) + violations
            MultipleFailureException.assertEmpty(all)
        }
    }
}
