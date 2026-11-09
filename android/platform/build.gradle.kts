plugins {
    id("rovsti.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.platform"
    testFixtures { enable = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Source-scanning tests (PlatformConventionTest, PrefsNamesTest) read src/main: re-run when it changes.
                it.inputs.files(fileTree("src/main")).withPropertyName("mainSources")
                it.systemProperty(
                    "rovsti.wire.fixtures",
                    rootProject.projectDir.parentFile
                        .resolve("crates/rovsti_core/tests/fixtures")
                        .absolutePath,
                )
                it.inputs
                    .dir(
                        rootProject.projectDir.parentFile.resolve("crates/rovsti_core/tests/fixtures"),
                    ).withPropertyName("wireFixtures")
            }
        }
    }
}

dependencies {
    api(project(":core-bridge"))
    implementation(project(":design"))
    testFixturesApi(project(":core-bridge"))
    testFixturesApi(libs.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    testImplementation(testFixtures(project(":core-bridge")))
    testImplementation(testFixtures(project))
    // The android.jar org.json is a stub on the plain JVM (WireFixturesTest): use the real one.
    testImplementation(libs.org.json)
}
