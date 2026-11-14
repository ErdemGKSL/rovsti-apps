plugins {
    `kotlin-dsl`
}

group = "dev.rosty.buildlogic"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.roborazzi.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "rosty.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidApp") {
            id = "rosty.android.app"
            implementationClass = "AndroidAppConventionPlugin"
        }
        register("compose") {
            id = "rosty.compose"
            implementationClass = "ComposeConventionPlugin"
        }
        register("cargo") {
            id = "rosty.cargo"
            implementationClass = "CargoConventionPlugin"
        }
        register("screenshots") {
            id = "rosty.screenshots"
            implementationClass = "ScreenshotsConventionPlugin"
        }
    }
}
