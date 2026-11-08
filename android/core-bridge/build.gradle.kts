plugins {
    id("rovsti.android.library")
    id("rovsti.cargo")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.rovsti.core"
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

// The real-library tests: plain JUnit on the host JVM against the host build of librovsti_ffi (no Robolectric, so
// System.loadLibrary is the JDK's). Sources are in src/testRust/kotlin and compile with the unit tests; the Robolectric
// run (testDebugUnitTest) skips them. One JVM for all classes: the library keeps process-wide state.
val rustTestPackage = "dev.rovsti.core.rust"

// The contract fixtures E2.7 (crates/rovsti_ffi/fixtures): ContractFixturesTest reads them as a plain directory.
val contractFixtures = rootProject.projectDir.parentFile.resolve("crates/rovsti_ffi/fixtures")

android {
    testOptions {
        unitTests.all {
            it.systemProperty("rovsti.contract", contractFixtures.absolutePath)
            it.inputs.dir(contractFixtures).withPropertyName("contractFixtures")
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
    // The headless goldens (crates/rovsti_ffi/fixtures) and the seed dirs they run against (rovsti_app_core).
    val seeds = rootProject.projectDir.parentFile.resolve("crates/rovsti_app_core/tests/fixtures")
    systemProperty("rovsti.fixtures", seeds.absolutePath)
    systemProperty("rovsti.contract", contractFixtures.absolutePath)
    inputs.dir(seeds).withPropertyName("seeds")
    inputs.dir(contractFixtures).withPropertyName("contractFixtures")
    inputs.dir(rustHostLib).withPropertyName("rustHostLib")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
