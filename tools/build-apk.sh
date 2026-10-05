#!/usr/bin/env bash
#
# Offline, Gradle-free Android APK build for Kiddo.
#
# Pipeline: aapt2 compile -> aapt2 link -> ECJ (javac) -> d8 -> zip (dex in)
#           -> zipalign -> apksigner -> apksigner verify
#
# Requires the toolchain in $KIDDO_TOOLS (run tools/setup-tools.sh once).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="${KIDDO_TOOLS:-$HOME/.cache/kiddo-tools}"

JAVA="$TOOLS/java-runtime/bin/java"
KEYTOOL="$TOOLS/java-runtime/bin/keytool"
AAPT2="$TOOLS/aapt2"
ANDROID_JAR="$TOOLS/android.jar"
D8_JAR="$TOOLS/d8.jar"
ECJ_JAR="$TOOLS/ecj.jar"
APKSIGNER_JAR="$TOOLS/apksigner.jar"
ZIPALIGN="$TOOLS/zipalign"

for f in "$JAVA" "$KEYTOOL" "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$ECJ_JAR" "$APKSIGNER_JAR" "$ZIPALIGN"; do
  [ -e "$f" ] || { echo "[build] missing $f - run tools/setup-tools.sh" >&2; exit 1; }
done

SRC="$ROOT/app/src/main"
BUILD="$ROOT/build"
DIST="$ROOT/dist"

PKG=ai.arena.kiddo
VERSION_CODE=1
VERSION_NAME=1.0
OUT_NAME="kiddo-v${VERSION_NAME}.apk"

rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/obj" "$BUILD/dex" "$DIST"

echo "[build] 1/8 aapt2 compile"
"$AAPT2" compile --dir "$SRC/res" -o "$BUILD/res/compiled.zip"

echo "[build] 2/8 aapt2 link"
"$AAPT2" link -o "$BUILD/app-unaligned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/res/compiled.zip"

echo "[build] 3/8 ECJ (javac) compile"
find "$SRC/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVA" -jar "$ECJ_JAR" -source 1.8 -target 1.8 -encoding UTF-8 -proc:none \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD/obj" "@$BUILD/sources.txt"

echo "[build] 4/8 d8 -> classes.dex"
find "$BUILD/obj" -name '*.class' | tr '\n' ' ' > "$BUILD/classes.txt"
# shellcheck disable=SC2046
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" --min-api 24 --output "$BUILD/dex" $(cat "$BUILD/classes.txt")

echo "[build] 5/8 package dex into apk"
python3 "$ROOT/tools/add_dex.py" "$BUILD/app-unaligned.apk" "$BUILD/dex" "$BUILD/app-unsigned.apk"

echo "[build] 6/8 zipalign"
LD_LIBRARY_PATH="$TOOLS" "$ZIPALIGN" -f -p 4 "$BUILD/app-unsigned.apk" "$BUILD/app-aligned.apk"

echo "[build] 7/8 sign"
KS_DIR="$ROOT/keystore"
mkdir -p "$KS_DIR"
KS="$KS_DIR/kiddo.keystore"
if [ ! -f "$KS" ]; then
  echo "[build] generating release keystore (stored in keystore/, git-ignored)"
  PASS="$(head -c 48 /dev/urandom | base64 | tr -dc 'a-zA-Z0-9' | head -c 24)"
  printf 'storepass=%s\nkeypass=%s\nalias=kiddo\n' "$PASS" "$PASS" > "$KS_DIR/keystore.properties"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias kiddo -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=Kiddo, OU=Android, O=Kiddo, C=US"
fi
PASS="$(sed -n 's/^storepass=//p' "$KS_DIR/keystore.properties")"

"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-key-alias kiddo \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --v4-signing-enabled false \
  --min-sdk-version 24 \
  --out "$DIST/$OUT_NAME" "$BUILD/app-aligned.apk"
rm -f "$DIST/$OUT_NAME.idsig"

echo "[build] 8/8 verify"
LD_LIBRARY_PATH="$TOOLS" "$ZIPALIGN" -c 4 "$DIST/$OUT_NAME" && echo "zipalign OK"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$DIST/$OUT_NAME" | sed -n '1,6p'

echo
echo "=== APK ==="
ls -la "$DIST"
echo
echo "=== badging ==="
"$AAPT2" dump badging "$DIST/$OUT_NAME" | sed -n '1,14p'
echo
echo "DONE: $DIST/$OUT_NAME"
