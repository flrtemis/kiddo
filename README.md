# Kiddo

A single-purpose Android app: it opens **https://arena.ai/agent** in a WebView and behaves
like a real app around it. That's the whole product.

- Package: `ai.arena.kiddo`
- Min SDK 24 (Android 7.0), target SDK 34
- No third-party / AndroidX dependencies — a pure platform WebView shell

## Getting the APK

A built, signed, zipaligned APK is at:

    dist/kiddo-v1.0.apk

Install it with `adb install dist/kiddo-v1.0.apk` or by copying it to a device.

## What it does

- Loads `https://arena.ai/agent` on launch.
- Back walks the WebView history first, exits only when there is no history (Android 13+
  predictive back included).
- Links to `arena.ai` (and subdomains) stay in-app; everything else opens in your browser.
- `mailto:` / `tel:` / `intent:` links are handed to the system.
- File-picker (`<input type=file>`) works, including multi-select.
- Microphone is granted (after a runtime permission prompt) when the page asks; camera is denied.
- Fullscreen video plays fullscreen.
- Offline / failed loads / untrusted certificates show a friendly retry screen instead of a
  Chromium error page.
- Renderer crashes rebuild the WebView instead of blanking the app.
- Dark mode follows the system theme (API 33+ algorithmic darkening).

## Rebuilding from scratch (no Gradle, no Google servers)

The normal Android toolchain (sdkmanager/Gradle/Google Maven) is not required. The build uses
only a JRE plus `aapt2`, `d8`, `zipalign`, `apksigner`, and ECJ (javac), assembled in
`tools/setup-tools.sh` from PyPI/npm/GitHub.

```bash
tools/setup-tools.sh     # one-time: installs toolchain to $HOME/.cache/kiddo-tools
tools/build-apk.sh       # compile, dex, align, sign, verify -> dist/kiddo-v1.0.apk
```

Icons are regenerated from `design/icon-source.png` with `tools/make_icons.py` (needs Pillow).

## Android Studio

Gradle files are included as a convenience (`settings.gradle.kts`, `build.gradle.kts`,
`app/build.gradle.kts`), but they are **unverified** in this environment (no route to Gradle
repos). `tools/build-apk.sh` is the authoritative build. See the note in `app/build.gradle.kts`.

## Layout

    app/src/main/AndroidManifest.xml        manifest (package, perms, activity)
    app/src/main/java/ai/arena/kiddo/       MainActivity (the WebView shell)
    app/src/main/res/                       layout, themes, strings, launcher icons
    tools/build-apk.sh                      dependency-free build -> dist/
    tools/setup-tools.sh                    assembles the toolchain
    tools/make_icons.py                     regenerates launcher icons
    design/icon-source.png                  master icon art

## Signing

`build-apk.sh` creates a local release keystore under `keystore/` (git-ignored) on first run and
signs v2/v3. For Play or to pin a key, supply your own keystore and edit the sign step.
