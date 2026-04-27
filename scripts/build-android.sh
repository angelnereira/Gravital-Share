#!/usr/bin/env bash
# build-android.sh — Canonical build script for Gravital Share Android
# Usage: ./scripts/build-android.sh [--release] [--abi arm64-v8a,armeabi-v7a,x86_64,x86]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_DIR="$REPO_ROOT/engine"
ANDROID_DIR="$REPO_ROOT/android"
JNI_LIBS="$ANDROID_DIR/app/src/main/jniLibs"
OUTPUTS_DIR="$REPO_ROOT/outputs"

# Defaults
BUILD_TYPE="debug"
ABIS="arm64-v8a,armeabi-v7a,x86_64,x86"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --release) BUILD_TYPE="release"; shift ;;
    --abi)     ABIS="$2"; shift 2 ;;
    *) echo "Unknown argument: $1"; exit 1 ;;
  esac
done

echo "==> Gravital Share build | type=$BUILD_TYPE | abis=$ABIS"
echo ""

# Step 1: Verify toolchain
echo "--- Step 1: Verify toolchain"
"$REPO_ROOT/scripts/verify-toolchain.sh"

# Step 2: Build Rust engine for Android
echo ""
echo "--- Step 2: Build Rust engine (cargo-ndk)"
CARGO_FLAGS=""
[[ "$BUILD_TYPE" == "release" ]] && CARGO_FLAGS="--release"

IFS=',' read -ra ABI_LIST <<< "$ABIS"
CARGO_NDK_TARGETS=""
for abi in "${ABI_LIST[@]}"; do
  CARGO_NDK_TARGETS="$CARGO_NDK_TARGETS -t $abi"
done

(
  cd "$ENGINE_DIR"
  # shellcheck disable=SC2086
  cargo ndk $CARGO_NDK_TARGETS \
    -o "$JNI_LIBS" \
    build $CARGO_FLAGS \
    -p gravital-engine
)
echo "    .so files written to $JNI_LIBS"

# Step 3: Build Android APK
echo ""
echo "--- Step 3: Build Android APK"
GRADLE_TASK="assemble$(echo "${BUILD_TYPE}" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"
(
  cd "$ANDROID_DIR"
  ./gradlew ":app:$GRADLE_TASK" --no-daemon
)

APK_PATH=$(find "$ANDROID_DIR/app/build/outputs/apk/$BUILD_TYPE" -name "*.apk" | head -1)

# ── Copy to outputs/ with versioned filename ──────────────────────────────────
mkdir -p "$OUTPUTS_DIR"

VERSION_NAME=$(grep 'versionName' "$ANDROID_DIR/app/build.gradle.kts" | grep -oP '"\K[^"]+' | head -1)
VERSION_CODE=$(grep 'versionCode' "$ANDROID_DIR/app/build.gradle.kts" | grep -oP '\d+' | head -1)
TIMESTAMP=$(date +%Y%m%d_%H%M)
FIRST_ABI="${ABI_LIST[0]}"

DEST="$OUTPUTS_DIR/GravitalShare-${VERSION_NAME:-dev}-${VERSION_CODE:-0}-${BUILD_TYPE}-${FIRST_ABI}-${TIMESTAMP}.apk"
cp "$APK_PATH" "$DEST"

# Stable symlink for quick adb install
ln -sf "$(basename "$DEST")" "$OUTPUTS_DIR/latest-${BUILD_TYPE}.apk"

echo ""
echo "==> Build complete"
echo "    APK   : $DEST"
echo "    latest: $OUTPUTS_DIR/latest-${BUILD_TYPE}.apk"
echo "    SHA256: $(sha256sum "$DEST" | awk '{print $1}')"
