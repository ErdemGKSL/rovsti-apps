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
    lint.abortOnError = true
    lint.warningsAsErrors = false
    lint.checkDependencies = false
}
