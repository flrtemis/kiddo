// Top-level build file. The app has no third-party dependencies.
// AGP 7.4.x is chosen because it still tolerates the `package` attribute in
// AndroidManifest.xml that tools/build-apk.sh (aapt2) requires.
plugins {
    id("com.android.application") version "7.4.2" apply false
}
