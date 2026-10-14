#!/bin/bash
# build_lucifer_native.sh - Build Lucifer native library for Android

set -euo pipefail

ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/opt/android-ndk}"
BUILD_DIR="build_android"
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "=========================================="
echo "Building Lucifer Native Library"
echo "=========================================="

echo "[1/5] Checking NDK..."
if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "ERROR: ANDROID_NDK_HOME not set or invalid: $ANDROID_NDK_HOME"
    echo "Set ANDROID_NDK_HOME environment variable"
    exit 1
fi
echo "✓ NDK found: $ANDROID_NDK_HOME"

echo "[2/5] Configuring CMake..."
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

cmake -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a \
      -DANDROID_PLATFORM=android-34 \
      -DANDROID_STL=c++_shared \
      -DCMAKE_BUILD_TYPE=Release \
      "$PROJECT_ROOT/native"

if [ $? -ne 0 ]; then
    echo "ERROR: CMake configure failed"
    exit 1
fi
echo "✓ CMake configured"

echo "[3/5] Building..."
cmake --build . --parallel
if [ $? -ne 0 ]; then
    echo "ERROR: Build failed"
    exit 1
fi
echo "✓ Build successful"

echo "[4/5] Verifying library..."
if [ ! -f "liblucifer_native.so" ]; then
    echo "ERROR: Library not found"
    exit 1
fi
file liblucifer_native.so
echo "✓ Library verified"

echo "[5/5] Deploying to device (if connected)..."
if adb devices | grep -q "device$"; then
    adb push liblucifer_native.so /data/data/com.termux/files/usr/lib/
    adb shell chmod 755 /data/data/com.termux/files/usr/lib/liblucifer_native.so
    echo "✓ Deployed to device"
else
    echo "⚠ No device connected, skipping deploy"
fi

echo ""
echo "=========================================="
echo "Build Complete!"
echo "=========================================="
echo "Library: $BUILD_DIR/liblucifer_native.so"
echo ""
echo "To deploy manually:"
echo "  adb push $BUILD_DIR/liblucifer_native.so /data/data/com.termux/files/usr/lib/"
echo "  adb shell chmod 755 /data/data/com.termux/files/usr/lib/liblucifer_native.so"