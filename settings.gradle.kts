// Standard Android Studio layout. This sandbox cannot reach the Gradle/AGP
// repositories, so the APK committed to dist/ was produced by tools/build-apk.sh.
// Open this project in Android Studio (network available) to build with Gradle instead.
pluginManagement {
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

rootProject.name = "kiddo"
include(":app")
