import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Common config for the app modules plus release signing from the environment (docs/kotlin-ui/toolchain.md):
 * `ROSTY_KEYSTORE` (path), `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_PASSWORD` and `ROSTY_KEY_ALIAS` (default `rovsti`,
 * the alias inside the existing key), the same names the GPUI apps' `gpuiforge.json` use. The pre-rename
 * `ROVSTI_KEYSTORE` and `ROVSTI_KEY_ALIAS` are read when the new name is unset (old signing.env files). When any of
 * them is missing the release build type stays unsigned and the build still succeeds: CI and the agent machine have
 * no keystore.
 */
class AndroidAppConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.application")
            extensions.configure<ApplicationExtension> {
                configureCommon()
                // Unit tests exist only for testBuildType (debug by default); the checks run release only.
                testBuildType = "release"
                defaultConfig.targetSdk = RostySdk.TARGET

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

                val keystore = env("ROSTY_KEYSTORE", LEGACY_KEYSTORE)
                val keyAlias = env("ROSTY_KEY_ALIAS", LEGACY_KEY_ALIAS) ?: RELEASE_KEY_ALIAS
                val storePassword = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull
                val keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
                if (!keystore.isNullOrBlank() && !storePassword.isNullOrEmpty() && !keyPassword.isNullOrEmpty()) {
                    val release =
                        signingConfigs.create("release") {
                            storeFile = file(keystore)
                            this.storePassword = storePassword
                            this.keyAlias = keyAlias
                            this.keyPassword = keyPassword
                        }
                    buildTypes.getByName("release").signingConfig = release
                }
            }
        }
    }

    /** The variable [name], else its pre-rename spelling [legacy]; blank counts as unset. */
    private fun Project.env(
        name: String,
        legacy: String,
    ): String? =
        providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
            ?: providers.environmentVariable(legacy).orNull?.takeIf { it.isNotBlank() }

    private companion object {
        /** The alias inside the existing release keystore (made before the rename, so it keeps the old name). */
        const val RELEASE_KEY_ALIAS = "rovsti"
        const val LEGACY_KEYSTORE = "ROVSTI_KEYSTORE"
        const val LEGACY_KEY_ALIAS = "ROVSTI_KEY_ALIAS"
        const val RELEASE_ABI = "arm64-v8a"
    }
}
