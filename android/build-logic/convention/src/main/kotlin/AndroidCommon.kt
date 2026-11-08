import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/** Settings shared by library and application modules. */
internal fun CommonExtension.configureCommon() {
    compileSdk = RovstiSdk.COMPILE
    defaultConfig.minSdk = RovstiSdk.MIN
    compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    compileOptions.targetCompatibility = JavaVersion.VERSION_17
    // Compose and Roborazzi tests need merged resources and the manifest on the unit-test classpath.
    testOptions.unitTests.isIncludeAndroidResources = true
    // Robolectric + Compose + software-rendered screenshots overflow the 512 MB default test heap.
    testOptions.unitTests.all { it.maxHeapSize = "2g" }
    lint.abortOnError = true
    lint.warningsAsErrors = false
    lint.checkDependencies = false
}
