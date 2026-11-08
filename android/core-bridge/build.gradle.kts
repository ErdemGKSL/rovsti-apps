plugins {
    id("rovsti.android.library")
    id("rovsti.cargo")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.core"
    testFixtures { enable = true }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.coroutines.core)
    testFixturesApi(libs.kotlinx.serialization.json)
    testFixturesApi(libs.coroutines.core)
    testImplementation(testFixtures(project))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}

// The real-library tests: plain JUnit on the host JVM against the host build of librovsti_ffi (no Robolectric, so
// System.loadLibrary is the JDK's). Sources are in src/testRust/kotlin and compile with the unit tests; the Robolectric
// run (testDebugUnitTest) skips them. One JVM for all classes: the library keeps process-wide state.
val rustTestPackage = "dev.rovsti.core.rust"

android {
    testOptions {
        unitTests.all {
            if (it.name == "testDebugUnitTest" || it.name == "testReleaseUnitTest") {
                it.filter.excludeTestsMatching("$rustTestPackage.*")
            }
        }
    }
}

android.sourceSets
    .getByName("test")
    .kotlin.directories
    .add("src/testRust/kotlin")

val rustHostLib = tasks.named("cargoBuildHost").flatMap { (it as CargoBuildTask).outputDir }

tasks.register<Test>("testRustBridge") {
    group = "verification"
    description = "Plain JUnit tests against the real host librovsti_ffi (E2.6)."
    val unit = tasks.named<Test>("testDebugUnitTest")
    dependsOn("cargoBuildHost", "compileDebugUnitTestKotlin")
    testClassesDirs = files(unit.map { it.testClassesDirs })
    classpath = files(unit.map { it.classpath })
    filter.includeTestsMatching("$rustTestPackage.*")
    forkEvery = 0
    maxParallelForks = 1
    maxHeapSize = "512m"
    systemProperty("java.library.path", rustHostLib.get().asFile.absolutePath)
    // The Step 5 goldens of the headless handlers (crates/rovsti_app_core/tests/fixtures).
    val fixtures = rootProject.projectDir.parentFile.resolve("crates/rovsti_app_core/tests/fixtures")
    systemProperty("rovsti.fixtures", fixtures.absolutePath)
    inputs.dir(fixtures).withPropertyName("fixtures")
    inputs.dir(rustHostLib).withPropertyName("rustHostLib")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
