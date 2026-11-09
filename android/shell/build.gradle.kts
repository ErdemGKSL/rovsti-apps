plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
    id("rovsti.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.shell"
    testFixtures { enable = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Source-scanning tests (ShellManifestTest) read src/main: re-run when it changes.
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
            }
        }
    }
}

// The licence pages read THIRD_PARTY_NOTICES.md and the repository LICENSE as assets. Both stay single-sourced: they are
// copied into a generated asset directory at build time, never kept as second copies in src/.
abstract class CopyLicenceAssets : DefaultTask() {
    @get:InputFile
    abstract val notices: RegularFileProperty

    @get:InputFile
    abstract val licence: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("licences")
        dir.mkdirs()
        notices.get().asFile.copyTo(dir.resolve("THIRD_PARTY_NOTICES.md"), overwrite = true)
        licence.get().asFile.copyTo(dir.resolve("LICENSE"), overwrite = true)
    }
}

val copyLicenceAssets by tasks.registering(CopyLicenceAssets::class) {
    notices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    licence.set(rootProject.layout.projectDirectory.file("../LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyLicenceAssets, CopyLicenceAssets::outputDir)
    }
}

dependencies {
    // The app modules (E4-E7) see the whole platform through :shell.
    api(project(":design"))
    api(project(":platform"))
    api(project(":wa"))
    api(libs.activity.compose)
    api(libs.coroutines.core)
    implementation(libs.navigation3.runtime)
    testFixturesApi(project(":core-bridge"))
    testFixturesApi(libs.coroutines.core)
    testImplementation(testFixtures(project))
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":wa")))
    testImplementation(libs.compose.ui.test.junit4)
}
