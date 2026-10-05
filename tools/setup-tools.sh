#!/usr/bin/env bash
#
# Assemble the Android build toolchain into $KIDDO_TOOLS from hosts that are
# reliably reachable (PyPI, npm, GitHub). No Google/Maven/Gradle downloads needed.
#
#   tools/setup-tools.sh          # installs to $HOME/.cache/kiddo-tools
#   KIDDO_TOOLS=/path tools/setup-tools.sh
set -euo pipefail

TOOLS="${KIDDO_TOOLS:-$HOME/.cache/kiddo-tools}"
mkdir -p "$TOOLS"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "[setup] into $TOOLS"

# 1) JDK 17 runtime (JRE) - from PyPI (jdk4py), manylinux x86_64
if [ ! -x "$TOOLS/java-runtime/bin/java" ]; then
  echo "[setup] jdk4py (JRE 17) from PyPI"
  URL="$(curl -fsS https://pypi.org/pypi/jdk4py/17.0.9.2/json | python3 -c "
import json,sys
d=json.load(sys.stdin)
for u in d['urls']:
    if 'manylinux' in u['filename'] and 'x86_64' in u['filename']: print(u['url'])
")"
  curl -fsSL -o "$WORK/jdk4py.whl" "$URL"
  python3 - "$WORK/jdk4py.whl" "$TOOLS" <<'PY'
import zipfile,sys
zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])
PY
  mv "$TOOLS/jdk4py/java-runtime" "$TOOLS/java-runtime"
  rm -rf "$TOOLS/jdk4py"
  chmod -R u+x "$TOOLS/java-runtime/bin" "$TOOLS/java-runtime/lib"
fi

get() { # get <url> <out>
  curl -fsSL -o "$2" "$1"
}

# 2) aapt2 (linux) - from npm aaptjs3
if [ ! -x "$TOOLS/aapt2" ]; then
  echo "[setup] aapt2 from npm aaptjs3"
  get "https://registry.npmjs.org/aaptjs3/-/aaptjs3-2.0.2.tgz" "$WORK/aaptjs3.tgz"
  mkdir -p "$WORK/aaptjs3" && tar xzf "$WORK/aaptjs3.tgz" -C "$WORK/aaptjs3"
  install -m755 "$WORK/aaptjs3/package/bin/x64/linux/aapt2" "$TOOLS/aapt2"
fi

# 3) android.jar + d8.jar + ecj.jar - from npm @drxiaozhi/minapk
if [ ! -f "$TOOLS/android.jar" ] || [ ! -f "$TOOLS/d8.jar" ] || [ ! -f "$TOOLS/ecj.jar" ]; then
  echo "[setup] android.jar/d8.jar/ecj.jar from npm minapk"
  get "https://registry.npmjs.org/@drxiaozhi/minapk/-/minapk-0.4.0.tgz" "$WORK/minapk.tgz"
  mkdir -p "$WORK/minapk" && tar xzf "$WORK/minapk.tgz" -C "$WORK/minapk"
  install -m644 "$WORK/minapk/package/tools/android.jar" "$TOOLS/android.jar"
  install -m644 "$WORK/minapk/package/tools/d8.jar" "$TOOLS/d8.jar"
  install -m644 "$WORK/minapk/package/tools/ecj-3.45.0.jar" "$TOOLS/ecj.jar"
fi

# 4) apksigner.jar - from npm @postar/apktool-node
if [ ! -f "$TOOLS/apksigner.jar" ]; then
  echo "[setup] apksigner.jar from npm apktool-node"
  get "https://registry.npmjs.org/@postar/apktool-node/-/apktool-node-0.3.4.tgz" "$WORK/apktoolnode.tgz"
  mkdir -p "$WORK/an" && tar xzf "$WORK/apktoolnode.tgz" -C "$WORK/an"
  install -m644 "$WORK/an/package/lib/apksigner.jar" "$TOOLS/apksigner.jar"
fi

# 5) zipalign + libc++.so - from GitHub LineageOS prebuilts (sparse clone)
if [ ! -x "$TOOLS/zipalign" ] || [ ! -f "$TOOLS/libc++.so" ]; then
  echo "[setup] zipalign/libc++.so from GitHub (LineageOS prebuilts)"
  git clone --depth 1 --filter=blob:none --sparse \
    https://github.com/LineageOS/android_prebuilts_build-tools.git "$WORK/bt"
  ( cd "$WORK/bt" && git sparse-checkout set linux-x86/bin linux-x86/lib64 )
  install -m755 "$WORK/bt/linux-x86/bin/zipalign" "$TOOLS/zipalign"
  install -m755 "$WORK/bt/linux-x86/lib64/libc++.so" "$TOOLS/libc++.so"
fi

echo "[setup] done. Tools at $TOOLS"
ls -la "$TOOLS"
