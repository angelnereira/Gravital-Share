#!/usr/bin/env bash
# verify-toolchain.sh — Verifies all required tools are installed at the correct versions.
set -euo pipefail

PASS=0
FAIL=0

check() {
  local name="$1"
  local expected="$2"
  local actual="$3"

  if [[ "$actual" == *"$expected"* ]]; then
    echo "  ✓ $name $expected"
    PASS=$((PASS+1))
  else
    echo "  ✗ $name — expected $expected, got: $actual"
    FAIL=$((FAIL+1))
  fi
}

echo "Verifying Gravital Share toolchain..."
echo ""

# Rust
check "rustc" "1.84" "$(rustc --version 2>/dev/null || echo 'NOT FOUND')"
check "cargo" "1.84" "$(cargo --version 2>/dev/null || echo 'NOT FOUND')"
check "cargo-ndk" "3.5.4" "$(cargo ndk --version 2>/dev/null || echo 'NOT FOUND')"
check "cargo-audit" "0.21" "$(cargo audit --version 2>/dev/null || echo 'NOT FOUND')"

# Java
check "java" "21" "$(java -version 2>&1 | head -1 || echo 'NOT FOUND')"
check "gradle" "" "$(gradle --version 2>/dev/null | head -1 || echo 'NOT FOUND')"

# Android NDK
NDK_DIR="${ANDROID_NDK_HOME:-${NDK_HOME:-NOT_SET}}"
if [[ -d "$NDK_DIR" ]]; then
  NDK_VERSION=$(cat "$NDK_DIR/source.properties" 2>/dev/null | grep "Pkg.Revision" | cut -d= -f2 | xargs || echo "UNKNOWN")
  check "NDK" "27.2" "$NDK_VERSION"
else
  echo "  ✗ Android NDK — ANDROID_NDK_HOME or NDK_HOME not set"
  FAIL=$((FAIL+1))
fi

# Rust targets
for TARGET in aarch64-linux-android armv7-linux-androideabi x86_64-linux-android; do
  if rustup target list --installed 2>/dev/null | grep -q "$TARGET"; then
    echo "  ✓ rust target $TARGET"
    PASS=$((PASS+1))
  else
    echo "  ✗ rust target $TARGET — not installed"
    FAIL=$((FAIL+1))
  fi
done

echo ""
echo "Results: $PASS passed, $FAIL failed"

if [[ $FAIL -gt 0 ]]; then
  echo "Run: rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android"
  exit 1
fi
