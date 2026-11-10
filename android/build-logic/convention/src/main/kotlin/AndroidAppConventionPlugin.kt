import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Common config for the app modules plus release signing from the environment (docs/kotlin-ui/toolchain.md):
 * `ROVSTI_KEYSTORE` (path), `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, alias `rovsti`, the same names the
 * GPUI apps' `gpuiforge.json` use. When any of them is missing the release build type stays unsigned and the build
 * still succeeds: CI and the agent machine have no keystore.
 */
class AndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureCommon()
                // Unit tests exist only for testBuildType (debug by default); the checks run release only.
                testBuildType = "release"
                defaultConfig.targetSdk = RovstiSdk.TARGET

                // Release: R8 shrinks, optimises and obfuscates the dex (43 MB APK without it, mostly unused library
                // code), drops unused resources, and ships arm64-v8a only (every target phone is arm64; the debug
                // build keeps x86_64 for emulators). Keep rules: each module's `proguard-rules.pro` plus the library
                // consumer rules (`:core-bridge` keeps the JNI surface).
                buildTypes.getByName("release") {
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                    ndk.abiFilters += RELEASE_ABI
                }

                val keystore = providers.environmentVariable("ROVSTI_KEYSTORE").orNull
                val storePassword = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull
                val keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
                if (!keystore.isNullOrBlank() && !storePassword.isNullOrEmpty() && !keyPassword.isNullOrEmpty()) {
                    val release =
                        signingConfigs.create("release") {
                            storeFile = file(keystore)
                            this.storePassword = storePassword
                            keyAlias = RELEASE_KEY_ALIAS
                            this.keyPassword = keyPassword
                        }
                    buildTypes.getByName("release").signingConfig = release
                }
            }
        }
    }

    private companion object {
        const val RELEASE_KEY_ALIAS = "rovsti"
        const val RELEASE_ABI = "arm64-v8a"
    }
}
