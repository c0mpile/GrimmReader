import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension

internal const val COMPILE_SDK = 37

// libarchive-android 1.1.7 (libarchive 3.8.8) requires compile SDK 37.2.
internal const val COMPILE_SDK_MINOR = 2
internal const val MIN_SDK = 31
internal const val TARGET_SDK = 37
internal const val JDK = 21

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

/** Robolectric's native SQLite and file descriptors need java.io opened and jdk.internal.access exported on JDK 17+. */
internal fun Project.configureUnitTests() {
    tasks.withType(Test::class.java).configureEach {
        // Hilt/KSP generate test sources even in modules without tests; that is not a misconfiguration.
        failOnNoDiscoveredTests.set(false)
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    }
}

internal fun Project.configureKotlinToolchain() {
    extensions.configure<KotlinProjectExtension> { jvmToolchain(JDK) }
}
