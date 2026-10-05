// Best-effort Gradle build for Android Studio.
//
// NOTE: this sandbox has no route to the Gradle / Google Maven repositories, so this file is
// unverified here. The APK committed to dist/ was produced by tools/build-apk.sh, which is the
// authoritative, dependency-free build. If you build in Android Studio and your AGP version
// complains about the `package` attribute in AndroidManifest.xml (AGP >= 8 removed it), delete
// that attribute; the offline script is the only consumer that reads it.
plugins {
    id("com.android.application")
}

android {
    namespace = "ai.arena.kiddo"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.arena.kiddo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    // Intentionally empty: the app is a self-contained platform WebView shell.
}
