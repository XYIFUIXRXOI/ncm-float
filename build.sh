#!/usr/bin/env bash
# Build script: aapt2 + javac + d8 + zipalign + apksigner (no Gradle)
set -euo pipefail

SDK="$HOME/android-sdk"
BT="$SDK/build-tools/34.0.0"
PLAT="$SDK/platforms/android-34/android.jar"
JAVA="$HOME/jdk17/bin"
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/dist"
BUILD="$ROOT/build"

mkdir -p "$OUT" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

echo "==> compile resources"
"$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"

echo "==> link apk (manifest + resources + R.java)"
"$BT/aapt2" link \
    -o "$BUILD/base.apk" \
    -I "$PLAT" \
    --manifest "$ROOT/AndroidManifest.xml" \
    -R "$BUILD/res.zip" \
    --java "$BUILD/gen" \
    --min-sdk-version 23 \
    --target-sdk-version 34 \
    --version-code 4 \
    --version-name 1.3 \
    --auto-add-overlay

echo "==> javac"
"$JAVA/javac" --release 8 \
    -classpath "$PLAT" \
    -d "$BUILD/classes" \
    $(find "$ROOT/src" "$BUILD/gen" -name '*.java')

echo "==> d8 (dex)"
"$BT/d8" --release --min-api 23 --lib "$PLAT" --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

echo "==> package dex into apk"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -j "$BUILD/unsigned.apk" classes.dex)

echo "==> zipalign"
"$BT/zipalign" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> sign"
KS="$ROOT/release.keystore"
if [ ! -f "$KS" ]; then
    "$JAVA/keytool" -genkeypair -v \
        -keystore "$KS" -alias ncmfloat \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass ncmfloat123 -keypass ncmfloat123 \
        -dname "CN=NCM Float, OU=Dev, O=NCM Float, L=City, ST=State, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign \
    --ks "$KS" --ks-pass pass:ncmfloat123 --key-pass pass:ncmfloat123 \
    --out "$OUT/NCMFloat-v1.3.apk" "$BUILD/aligned.apk"

echo "==> verify"
"$BT/apksigner" verify --print-certs "$OUT/NCMFloat-v1.3.apk"
ls -lh "$OUT/NCMFloat-v1.3.apk"
