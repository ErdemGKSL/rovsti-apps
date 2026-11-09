plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
    id("rovsti.screenshots")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.shell"
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

dependencies {
    // The app modules (E4-E7) see the whole platform through :shell.
    api(project(":design"))
    api(project(":platform"))
    api(project(":wa"))
    api(libs.activity.compose)
    api(libs.coroutines.core)
    implementation(libs.navigation3.runtime)
    testImplementation(project(":testing"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project(":platform")))
    testImplementation(testFixtures(project(":wa")))
    testImplementation(libs.compose.ui.test.junit4)
}
