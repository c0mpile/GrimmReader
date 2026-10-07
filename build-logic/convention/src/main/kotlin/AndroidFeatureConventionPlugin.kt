import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/** A feature screen module: Android library + Compose + Hilt, with the design system and the data layer. */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) =
        with(target) {
            pluginManager.apply("grimm.android.library")
            pluginManager.apply("grimm.android.compose")
            pluginManager.apply("grimm.hilt")
            dependencies {
                add("implementation", project(":core:designsystem"))
                add("implementation", project(":core:data"))
                add("implementation", libs.lib("androidx-hilt-lifecycle-viewmodel-compose"))
                add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
                add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
                add("testImplementation", libs.lib("kotlinx-coroutines-test"))
                add("testImplementation", libs.lib("turbine"))
            }
        }
}
