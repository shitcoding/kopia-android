#!/usr/bin/env bash
set -euo pipefail

# Build Kopia for Android ARM64 (PIE, CGO disabled) and copy into app assets.
# Requirements:
#  - Go 1.21+ installed
#  - git installed
#  - Internet access to fetch modules
#
# Output:
#  - app/src/main/assets/kopia (Android aarch64 PIE)

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS_DIR="$ROOT_DIR/app/src/main/assets"
JNILIB_ARM64_DIR="$ROOT_DIR/app/src/main/jniLibs/arm64-v8a"
JNILIB_X86_64_DIR="$ROOT_DIR/app/src/main/jniLibs/x86_64"
BUILD_DIR="$ROOT_DIR/.build/kopia-android"
SRC_DIR="$BUILD_DIR/src"
OUT_BIN_ASSET="$ASSETS_DIR/kopia"
OUT_BIN_JNI_ARM64="$JNILIB_ARM64_DIR/libkopia.so"
OUT_BIN_JNI_X86_64="$JNILIB_X86_64_DIR/libkopia.so"

mkdir -p "$ASSETS_DIR" "$BUILD_DIR" "$JNILIB_ARM64_DIR" "$JNILIB_X86_64_DIR" "$SRC_DIR"

if ! command -v go >/dev/null 2>&1; then
  echo "ERROR: Go toolchain not found. Please install Go (1.21+) and ensure it's in PATH." >&2
  exit 1
fi

KOPIA_VERSION_DEFAULT="v0.21.1"
KVER="${KOPIA_VERSION:-$KOPIA_VERSION_DEFAULT}"

set -x
cd "$SRC_DIR"
if [ ! -d kopia ]; then
  git clone --depth 1 --branch "$KVER" https://github.com/kopia/kopia.git
else
  cd kopia
  git fetch --tags --depth 1 origin "$KVER" || true
  git checkout -f "$KVER"
  cd ..
fi

cd kopia
echo "Checked out kopia $(git describe --tags --always)"

# Build ARM64 PIE
ARM_OUT_BIN="$BUILD_DIR/kopia-android-arm64"
env GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -buildmode=pie -o "$ARM_OUT_BIN" .
install -m 0755 "$ARM_OUT_BIN" "$OUT_BIN_ASSET"
install -m 0755 "$ARM_OUT_BIN" "$OUT_BIN_JNI_ARM64"

# Build x86_64 PIE (emulator)
X64_OUT_BIN="$BUILD_DIR/kopia-android-x86_64"
env GOOS=android GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -buildmode=pie -o "$X64_OUT_BIN" .
install -m 0755 "$X64_OUT_BIN" "$OUT_BIN_JNI_X86_64"
set +x

echo "Built Kopia binaries at:"
ls -l "$OUT_BIN_ASSET" || true
ls -l "$OUT_BIN_JNI_ARM64" || true
ls -l "$OUT_BIN_JNI_X86_64" || true
if command -v file >/dev/null 2>&1; then
  echo "file(1):"; file "$OUT_BIN_ASSET" "$OUT_BIN_JNI_ARM64" 2>/dev/null || true
fi
