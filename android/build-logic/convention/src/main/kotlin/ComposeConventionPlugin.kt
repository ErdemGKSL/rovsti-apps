import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * Compose compiler plugin, the BOM and the core UI artifacts.
 *
 * `-PcomposeReports` writes the stability and metrics reports to `<module>/build/compose_reports`.
 * Stability overrides live in `android/compose_stability.conf`.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

            extensions.configure<ComposeCompilerGradlePluginExtension> {
                stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
                if (providers.gradleProperty("composeReports").isPresent) {
                    val dir = layout.buildDirectory.dir("compose_reports")
                    reportsDestination.set(dir)
                    metricsDestination.set(dir)
                }
            }

            // Reports exist only for a compile run with -PcomposeReports; `hot` stays empty (task is a no-op list check
            // that fails on missing reports) until a module names its hot composables.
            tasks.register("checkComposeStability", CheckComposeStabilityTask::class.java) {
                group = "verification"
                description = "Fails if a named hot composable is not restartable skippable (needs -PcomposeReports)."
                reports.set(layout.buildDirectory.dir("compose_reports"))
                dependsOn("compileDebugKotlin")
            }

            dependencies {
                add("implementation", platform(libs.findLibrary("compose-bom").get()))
                add("androidTestImplementation", platform(libs.findLibrary("compose-bom").get()))
                add("testImplementation", platform(libs.findLibrary("compose-bom").get()))
                listOf("compose-ui", "compose-ui-graphics", "compose-foundation", "compose-animation")
                    .forEach { add("implementation", libs.findLibrary(it).get()) }
                add("implementation", libs.findLibrary("compose-ui-tooling-preview").get())
                add("debugImplementation", libs.findLibrary("compose-ui-tooling").get())
                add("debugImplementation", libs.findLibrary("compose-ui-test-manifest").get())
            }
        }
    }
}
