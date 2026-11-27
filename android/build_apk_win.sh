#!/bin/bash
# Windows (Git Bash) build script for MiRearPet APK.
# Usage: bash build_apk_win.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

ANDROID_SDK="${ANDROID_HOME:-$HOME/AppData/Local/Android/Sdk}"
BUILD_TOOLS="$ANDROID_SDK/build-tools/${ANDROID_BUILD_TOOLS:-35.0.0}"
ANDROID_JAR="$ANDROID_SDK/platforms/android-35/android.jar"
PLATFORM="$ANDROID_SDK/platforms/android-35"

if [[ ! -f "$ANDROID_JAR" ]]; then
  echo "ERROR: android.jar not found at $ANDROID_JAR"
  exit 1
fi

# Windows-style paths for .bat tools (d8 / apksigner) which skip MSYS path conversion.
W_ANDROID_JAR="$(cygpath -w "$ANDROID_JAR")"
W_FRAMEWORK_AIDL="$(cygpath -w "$PLATFORM/framework.aidl")"
W_BUILD_TOOLS="$(cygpath -w "$BUILD_TOOLS")"
W_AIDL_DIR="$(cygpath -w "$SCRIPT_DIR/aidl")"

BUILD_DIR="$SCRIPT_DIR/build"
GEN_DIR="$BUILD_DIR/gen"
OBJ_DIR="$BUILD_DIR/obj"
DEX_DIR="$BUILD_DIR/dex"
DIST_DIR="$SCRIPT_DIR/dist"
KEYSTORE="$SCRIPT_DIR/mirearpet.jks"

rm -rf "$BUILD_DIR"
mkdir -p "$GEN_DIR" "$OBJ_DIR" "$DEX_DIR" "$DIST_DIR"
W_GEN_DIR="$(cygpath -w "$GEN_DIR")"

echo "[1/6] Generating Shizuku AIDL Java stubs..."
for aidl_file in "$SCRIPT_DIR"/aidl/moe/shizuku/server/*.aidl; do
  "$BUILD_TOOLS/aidl.exe" \
    -p"$W_FRAMEWORK_AIDL" \
    -I"$W_AIDL_DIR" \
    -o"$W_GEN_DIR" \
    "$(cygpath -w "$aidl_file")"
done

echo "[2/6] Compiling & linking Android resources (aapt2)..."
"$BUILD_TOOLS/aapt2.exe" compile --dir "res" -o "$BUILD_DIR/res.zip"
"$BUILD_TOOLS/aapt2.exe" link \
  -o "$BUILD_DIR/unsigned.apk" \
  -I "$W_ANDROID_JAR" \
  --manifest "AndroidManifest.xml" \
  --java "$GEN_DIR" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  "$BUILD_DIR/res.zip"

echo "[3/6] Compiling Java sources (JDK 17 -> Java 11 bytecode)..."
JAVA_FILES=()
while IFS= read -r f; do
  JAVA_FILES+=("$f")
done < <(find "$SCRIPT_DIR/src" "$GEN_DIR" -name "*.java" | sort)
javac \
  --release 11 \
  -encoding UTF-8 \
  -classpath "$W_ANDROID_JAR" \
  -d "$OBJ_DIR" \
  "${JAVA_FILES[@]}"

echo "[4/6] Converting .class bytecode to classes.dex (d8)..."
CLASS_FILES=()
while IFS= read -r f; do
  CLASS_FILES+=("$f")
done < <(find "$OBJ_DIR" -name "*.class" | sort)
W_DEX_DIR="$(cygpath -w "$DEX_DIR")"
W_CLASS_FILES=()
for f in "${CLASS_FILES[@]}"; do
  W_CLASS_FILES+=("$(cygpath -w "$f")")
done
"$BUILD_TOOLS/d8.bat" \
  --min-api 29 \
  --lib "$W_ANDROID_JAR" \
  --output "$W_DEX_DIR" \
  "${W_CLASS_FILES[@]}"

echo "[5/6] Packaging classes.dex & aligning APK..."
python -c "
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, 'a', compression=zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
" "$BUILD_DIR/unsigned.apk" "$DEX_DIR/classes.dex"
"$BUILD_TOOLS/zipalign.exe" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

echo "[6/6] Signing APK (v1 + v2 + v3)..."
if [[ ! -f "$KEYSTORE" ]]; then
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -storepass mirearpet2026 \
    -keypass mirearpet2026 \
    -alias mirearpet \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=MiRearPet, OU=DIY, O=MiRearPet, L=Shanghai, S=Shanghai, C=CN" >/dev/null 2>&1
fi
W_KEYSTORE="$(cygpath -w "$KEYSTORE")"
W_ALIGNED="$(cygpath -w "$BUILD_DIR/aligned.apk")"
W_OUT="$(cygpath -w "$DIST_DIR/MiRearPet.apk")"
"$BUILD_TOOLS/apksigner.bat" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$W_KEYSTORE" \
  --ks-pass pass:mirearpet2026 \
  --key-pass pass:mirearpet2026 \
  --ks-key-alias mirearpet \
  --out "$W_OUT" \
  "$W_ALIGNED"

"$BUILD_TOOLS/apksigner.bat" verify --verbose "$W_OUT" | head -n 6
ls -lh "$DIST_DIR/MiRearPet.apk"
echo "DONE: $DIST_DIR/MiRearPet.apk"
