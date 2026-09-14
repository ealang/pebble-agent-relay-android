#!/usr/bin/env bash
#
# Build TDLib (libtdjni.so + Java API) for Android from source, reproducibly.
#
# Uses TDLib's official example/android build scripts, pinned to a specific
# commit. Outputs are copied into this repository:
#   app/src/main/jniLibs/<ABI>/libtdjni.so
#   app/src/main/java/org/drinkless/tdlib/{TdApi.java,Client.java}
#
# Usage:
#   ./scripts/build-tdlib.sh
#
# Environment overrides:
#   TDLIB_COMMIT  TDLib commit to build (default: pinned below)
#   ABIS          space-separated ABI list (default: "arm64-v8a armeabi-v7a x86_64 x86")
#                 e.g. ABIS="arm64-v8a" ./scripts/build-tdlib.sh
#   ANDROID_HOME  Android SDK root (default: /opt/android-sdk)
#   NDK_VERSION   NDK version directory under $ANDROID_HOME/ndk (default: 23.2.8568313)
#   BUILD_DIR     scratch directory for source + build trees (default: /tmp/tdlib-build)
#
# Host requirements: bash, git, g++, make, ninja, gperf, perl, php, sed,
# JDK (java/javac/javadoc/jar), and CMake 3.22.1 from the Android SDK
# ($ANDROID_HOME/cmake/3.22.1 — added to PATH automatically).
#
set -euo pipefail

TDLIB_COMMIT="${TDLIB_COMMIT:-d1085f9cebc5a62379991ae1652673954f229c1f}"
ABIS="${ABIS:-arm64-v8a armeabi-v7a x86_64 x86}"
ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
NDK_VERSION="${NDK_VERSION:-23.2.8568313}"
BUILD_DIR="${BUILD_DIR:-/tmp/tdlib-build}"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
TD_DIR="$BUILD_DIR/td"

export PATH="$ANDROID_HOME/cmake/3.22.1/bin:$PATH"

echo "==> TDLib commit: $TDLIB_COMMIT"
echo "==> ABIs:         $ABIS"
echo "==> SDK:          $ANDROID_HOME (NDK $NDK_VERSION)"
echo "==> Build dir:    $BUILD_DIR"

# --- Fetch TDLib source, pinned to TDLIB_COMMIT ------------------------------
mkdir -p "$BUILD_DIR"
if [ ! -d "$TD_DIR/.git" ]; then
  git init -q "$TD_DIR"
  git -C "$TD_DIR" remote add origin https://github.com/tdlib/td.git
fi
if ! git -C "$TD_DIR" cat-file -e "$TDLIB_COMMIT^{commit}" 2>/dev/null; then
  git -C "$TD_DIR" fetch --depth 1 origin "$TDLIB_COMMIT"
fi
git -C "$TD_DIR" checkout -qf "$TDLIB_COMMIT"

ANDROID_EXAMPLE_DIR="$TD_DIR/example/android"
cd "$ANDROID_EXAMPLE_DIR"

# --- Patch: restrict stock scripts (hardcoded 4-ABI loops) to $ABIS ----------
# Minimal, idempotent patch instead of maintaining forked scripts.
sed -i "s|^for ABI in arm64-v8a armeabi-v7a x86_64 x86 ; do$|for ABI in $ABIS ; do|" \
  build-openssl.sh build-tdlib.sh

# --- Build OpenSSL per ABI (static, OpenSSL 1.1.1w — TDLib's default) --------
OPENSSL_DIR="$BUILD_DIR/openssl"
if [ ! -d "$OPENSSL_DIR" ]; then
  ./build-openssl.sh "$ANDROID_HOME" "$NDK_VERSION" "$OPENSSL_DIR"
else
  echo "==> Reusing prebuilt OpenSSL in $OPENSSL_DIR"
fi

# --- Build TDLib (Java interface: generates TdApi.java, builds libtdjni.so) --
./build-tdlib.sh "$ANDROID_HOME" "$NDK_VERSION" "$OPENSSL_DIR"

# --- Copy outputs into the repository ----------------------------------------
JAVA_OUT="$REPO_ROOT/app/src/main/java/org/drinkless/tdlib"
mkdir -p "$JAVA_OUT"
cp -p tdlib/java/org/drinkless/tdlib/TdApi.java "$JAVA_OUT/"
cp -p tdlib/java/org/drinkless/tdlib/Client.java "$JAVA_OUT/"

for ABI in $ABIS; do
  mkdir -p "$REPO_ROOT/app/src/main/jniLibs/$ABI"
  cp -p "tdlib/libs/$ABI/libtdjni.so" "$REPO_ROOT/app/src/main/jniLibs/$ABI/"
done

echo "==> Done. Artifacts:"
for ABI in $ABIS; do
  ls -l "$REPO_ROOT/app/src/main/jniLibs/$ABI/libtdjni.so"
done
ls -l "$JAVA_OUT/TdApi.java" "$JAVA_OUT/Client.java"
