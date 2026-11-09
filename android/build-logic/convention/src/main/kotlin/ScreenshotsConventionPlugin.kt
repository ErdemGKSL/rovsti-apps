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

            // `:catalog:contactSheet` writes docs/kotlin-ui/screenshots/design.png from the light goldens (E1.22). Every
            // other module that applies the plugin (:wa, :shell, ...) writes <module>.png, so two sheets never share a file.
            val sheetName = if (name == "catalog") "design" else name
            tasks.register("contactSheet", ContactSheetTask::class.java) {
                group = "documentation"
                description = "Tiles the light goldens into docs/kotlin-ui/screenshots/design.png"
                screenshots.set(layout.projectDirectory.dir("screenshots"))
                output.set(rootProject.layout.projectDirectory.file("../docs/kotlin-ui/screenshots/$sheetName.png"))
                include.set("-light\\.png$")
                exclude.set("-(rtl|fs2|landscape|360x640|dark)-|-(rtl|fs2|landscape|360x640)-light|-\\+\\d+ms-")
                columns.set(12)
                tileWidth.set(150)
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
