plugins {
    id("rosty.android.library")
    id("rosty.cargo")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rosty.core"
    testFixtures { enable = true }
    defaultConfig { consumerProguardFiles("consumer-rules.pro") }
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

// The real-library tests: plain JUnit on the host JVM against the host build of librosty_ffi (no Robolectric, so
// System.loadLibrary is the JDK's). Sources are in src/testRust/kotlin and compile with the unit tests; the Robolectric
// run (testReleaseUnitTest) skips them. One JVM for all classes: the library keeps process-wide state.
val rustTestPackage = "dev.rosty.core.rust"

// The contract fixtures E2.7 (crates/rosty_ffi/fixtures): ContractFixturesTest reads them as a plain directory.
val contractFixtures = rootProject.projectDir.parentFile.resolve("crates/rosty_ffi/fixtures")

// The masking cases `rosty_core::redact` and `LogMask` both pass (people sync v2, section 10).
val redactCases = rootProject.projectDir.parentFile.resolve("crates/rosty_core/tests/data/redact_cases.json")

android {
    testOptions {
        unitTests.all {
            it.systemProperty("rosty.contract", contractFixtures.absolutePath)
            it.inputs.dir(contractFixtures).withPropertyName("contractFixtures")
            it.systemProperty("rosty.redact", redactCases.absolutePath)
            it.inputs.file(redactCases).withPropertyName("redactCases")
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
    description = "Plain JUnit tests against the real host librosty_ffi (E2.6)."
    val unit = tasks.named<Test>("testReleaseUnitTest")
    dependsOn("cargoBuildHost", "compileReleaseUnitTestKotlin")
    testClassesDirs = files(unit.map { it.testClassesDirs })
    classpath = files(unit.map { it.classpath })
    filter.includeTestsMatching("$rustTestPackage.*")
    forkEvery = 0
    maxParallelForks = 1
    maxHeapSize = "512m"
    systemProperty("java.library.path", rustHostLib.get().asFile.absolutePath)
    // The headless goldens (crates/rosty_ffi/fixtures) and the seed dirs they run against (rosty_app_core).
    val seeds = rootProject.projectDir.parentFile.resolve("crates/rosty_app_core/tests/fixtures")
    systemProperty("rosty.fixtures", seeds.absolutePath)
    systemProperty("rosty.contract", contractFixtures.absolutePath)
    inputs.dir(seeds).withPropertyName("seeds")
    inputs.dir(contractFixtures).withPropertyName("contractFixtures")
    inputs.dir(rustHostLib).withPropertyName("rustHostLib")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
