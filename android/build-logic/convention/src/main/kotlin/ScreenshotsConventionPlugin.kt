import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** Roborazzi plus the Robolectric and Compose test stack. Goldens live in `<module>/screenshots`. */
class ScreenshotsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")
            val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

            extensions.configure<RoborazziExtension> {
                outputDir.set(layout.projectDirectory.dir("screenshots"))
            }

            dependencies {
                add("testImplementation", platform(libs.findLibrary("compose-bom").get()))
                listOf(
                    "robolectric",
                    "roborazzi",
                    "roborazzi-compose",
                    "roborazzi-junit-rule",
                    "compose-ui-test-junit4",
                    "junit",
                ).forEach { add("testImplementation", libs.findLibrary(it).get()) }
            }
        }
    }
}
