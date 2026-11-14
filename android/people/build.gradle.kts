plugins {
    id("rovsti.android.library")
    id("rovsti.compose")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.people"
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // PeopleConventionTest scans src/main: re-run when it changes.
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
            }
        }
    }
}

dependencies {
    // PersonRow is the people.* wire model; no Rust runs here unless a caller hands in a PeopleOps (X.1a).
    api(project(":core-bridge"))
    // PhoneNumbers: the home-region normalization of the numbers the index is asked about.
    implementation(project(":platform"))
    // ContactAvatar (the one avatar composable of every app) draws through the design system.
    implementation(project(":design"))
    // The people sync runner (people sync v2) talks to the gateway through the app's WaRepository.
    implementation(project(":wa"))
    api(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project(":wa")))
}
