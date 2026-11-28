import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/** Shared Android/Kotlin settings for every module. */
object OpenCodeBuild {
    const val COMPILE_SDK = 37
    const val COMPILE_SDK_MINOR = 1
    const val MIN_SDK = 33
    const val TARGET_SDK = 37
}

private fun Project.catalog() = extensions.getByType<VersionCatalogsExtension>().named("libs")

private fun Project.configureKotlin() {
    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(17)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xcontext-parameters",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            )
        }
    }
}

private fun CommonExtension.configureCommon() {
    compileSdk {
        version = release(OpenCodeBuild.COMPILE_SDK) { minorApiLevel = OpenCodeBuild.COMPILE_SDK_MINOR }
    }
    defaultConfig.minSdk = OpenCodeBuild.MIN_SDK
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
    buildFeatures.compose = true
    testOptions.unitTests.isReturnDefaultValues = true
    // Robolectric tests render screens that read strings.xml.
    testOptions.unitTests.isIncludeAndroidResources = true
    lint.abortOnError = true
    lint.warningsAsErrors = false
    lint.checkDependencies = false
    lint.disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    // A string with no translation yet shows in English (docs/translating.md), so new English
    // text doesn't have to wait for every language.
    lint.warning += "MissingTranslation"
}

private fun Project.addCommonDependencies() {
    val libs = catalog()
    dependencies {
        add("implementation", platform(libs.findLibrary("compose-bom").get()))
        add("implementation", libs.findLibrary("kotlinx-coroutines-android").get())
        add("testImplementation", libs.findLibrary("junit").get())
        add("testImplementation", libs.findLibrary("kotlinx-coroutines-test").get())
        add("testImplementation", libs.findLibrary("turbine").get())
    }
}

/** `opencode.android.library`: an Android library with Compose enabled (`:core`, `:server`, `:devtools`). */
class OpenCodeLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<LibraryExtension> {
            configureCommon()
            namespace = "ai.opencode." + path.removePrefix(":").replace(":", ".").replace("-", "")
        }
        configureKotlin()
        addCommonDependencies()
    }
}

/** `opencode.android.feature`: a library plus `:core`, `:server` and the Compose/M3 stack. */
class OpenCodeFeaturePlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("opencode.android.library")
        val libs = catalog()
        dependencies {
            add("api", project(":core"))
            add("api", project(":server"))
            add("implementation", libs.findLibrary("compose-material3").get())
            add("implementation", libs.findLibrary("compose-ui").get())
            add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
            add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("kotlinx-serialization-json").get())
            // TestIsolationGuard, WireFixture and the network block (see AGENTS.md "Tests").
            add("testImplementation", testFixtures(project(":server")))
        }
    }
}

/** `opencode.android.application`: the `:app` module. */
class OpenCodeApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.configure<ApplicationExtension> {
            configureCommon()
            defaultConfig.targetSdk = OpenCodeBuild.TARGET_SDK
        }
        configureKotlin()
        addCommonDependencies()
    }
}
