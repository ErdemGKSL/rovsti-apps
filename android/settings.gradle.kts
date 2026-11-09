pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rovsti-android"

include(":core-bridge")
include(":design")
include(":catalog")
include(":testing")
include(":platform")
include(":wa")
include(":shell")
include(":contacts")
