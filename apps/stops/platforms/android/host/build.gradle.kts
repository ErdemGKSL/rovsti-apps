
plugins { id("com.android.library") }


android {
    namespace = "dev.gpui.android"
    compileSdk {
        version = release(rootProject.extra["gpuiCompileSdkMajor"] as Int) {
            minorApiLevel = rootProject.extra["gpuiCompileSdkMinor"] as Int
        }
    }
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
}

dependencies {
    implementation("androidx.core:core:1.18.0")
}
