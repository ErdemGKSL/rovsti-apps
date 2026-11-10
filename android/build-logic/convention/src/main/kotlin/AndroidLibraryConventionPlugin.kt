import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                configureCommon()
                // Libraries build unit tests only for testBuildType (debug by default). The checks run release only
                // (no debug builds, docs/kotlin-ui/toolchain.md), so testReleaseUnitTest must be the one that exists.
                testBuildType = "release"
            }
        }
    }
}
