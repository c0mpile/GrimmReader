import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** Hilt via KSP for Android modules; plain Dagger/Hilt core for JVM modules. */
class HiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.google.devtools.ksp")
        dependencies { add("ksp", libs.lib("hilt-compiler")) }
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            dependencies { add("implementation", libs.lib("hilt-core")) }
        }
        pluginManager.withPlugin("com.android.base") {
            pluginManager.apply("com.google.dagger.hilt.android")
            dependencies { add("implementation", libs.lib("hilt-android")) }
        }
    }
}
