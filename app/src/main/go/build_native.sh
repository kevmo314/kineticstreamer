#!/bin/bash

set -e

# Check if running on Windows (non-WSL)
if [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "cygwin" ]]; then
    echo "Windows detected. Please use build_native.ps1 instead:"
    echo "  PowerShell: .\\build_native.ps1"
    echo "  Or from cmd: powershell -ExecutionPolicy Bypass -File build_native.ps1"
    exit 1
fi

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

# Set up paths
THIRD_PARTY_DIR="$SCRIPT_DIR/third_party/output"
OUTPUT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/jniLibs"

# Use the NDK from Android SDK
ANDROID_NDK=${ANDROID_NDK:-/home/kevin/android/ndk/27.0.12077973}
ANDROID_API=24

# Check if third_party libraries exist
if [ ! -d "$THIRD_PARTY_DIR" ]; then
    echo "Error: Third party libraries not found at $THIRD_PARTY_DIR"
    echo "Please run ./build_third_party.sh first"
    exit 1
fi

# Create include directory and copy headers (for cross-platform compatibility)
INCLUDE_DIR="$SCRIPT_DIR/include"
mkdir -p "$INCLUDE_DIR"

# Copy libusb headers from arm64-v8a (headers are the same across architectures)
if [ ! -d "$INCLUDE_DIR/libusb-1.0" ]; then
    if [ -d "$THIRD_PARTY_DIR/arm64-v8a/include/libusb-1.0" ]; then
        echo "Copying libusb headers..."
        cp -r "$THIRD_PARTY_DIR/arm64-v8a/include/libusb-1.0" "$INCLUDE_DIR/"
    fi
fi

# Copy SRT headers
if [ ! -d "$INCLUDE_DIR/srt" ]; then
    if [ -d "$THIRD_PARTY_DIR/arm64-v8a/include/srt" ]; then
        echo "Copying SRT headers..."
        cp -r "$THIRD_PARTY_DIR/arm64-v8a/include/srt" "$INCLUDE_DIR/"
    fi
fi

# Copy OpenSSL headers (needed by SRT)
if [ ! -d "$INCLUDE_DIR/openssl" ]; then
    if [ -d "$THIRD_PARTY_DIR/arm64-v8a/include/openssl" ]; then
        echo "Copying OpenSSL headers..."
        cp -r "$THIRD_PARTY_DIR/arm64-v8a/include/openssl" "$INCLUDE_DIR/"
    fi
fi

# Function to build for a specific architecture
build_arch() {
    local ARCH=$1
    local GOARCH=$2
    local GOARM=$3
    local CC_PREFIX=$4
    local PLATFORM=$5
    
    echo "Building for $ARCH..."
    
    # Set up output directory
    mkdir -p "$OUTPUT_DIR/$ARCH"
    
    # Set up environment variables
    export GOOS=android
    export GOARCH=$GOARCH
    export GOARM=$GOARM
    export CGO_ENABLED=1
    export CC="${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/${CC_PREFIX}${ANDROID_API}-clang"
    export CXX="${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/${CC_PREFIX}${ANDROID_API}-clang++"
    # Include paths for all third-party libraries
    export CGO_CFLAGS="-I$INCLUDE_DIR -I$THIRD_PARTY_DIR/$ARCH/include -D__ANDROID__"
    export CGO_LDFLAGS="-L$THIRD_PARTY_DIR/$ARCH/lib -lusb-1.0 -lsrt -lcrypto -lssl"
    
    # Build the shared library
    # Note: Go will compile both the Go and C files together
    # -checklinkname=0 is needed for Go 1.24+ compatibility with anet package
    cd cmd/jni
    go build -buildmode=c-shared \
        -ldflags="-s -w -checklinkname=0 -extldflags '-Wl,-soname,libkinetic.so'" \
        -o "$OUTPUT_DIR/$ARCH/libkinetic.so" \
        .
    cd ../..

    # Copy third-party shared libraries
    cp "$THIRD_PARTY_DIR/$ARCH/lib/libusb-1.0.so" "$OUTPUT_DIR/$ARCH/"
    cp "$THIRD_PARTY_DIR/$ARCH/lib/libsrt.so" "$OUTPUT_DIR/$ARCH/"
    cp "$THIRD_PARTY_DIR/$ARCH/lib/libcrypto.so" "$OUTPUT_DIR/$ARCH/"
    cp "$THIRD_PARTY_DIR/$ARCH/lib/libssl.so" "$OUTPUT_DIR/$ARCH/"

    echo "Built $ARCH/libkinetic.so"
}

# Build for all architectures in parallel for speed
echo "Starting parallel builds for all architectures..."

# Track PIDs of background jobs
pids=()
build_arch "arm64-v8a" "arm64" "" "aarch64-linux-android" "android-arm64" &
pids+=($!)
build_arch "armeabi-v7a" "arm" "7" "armv7a-linux-androideabi" "android-arm" &
pids+=($!)
build_arch "x86" "386" "" "i686-linux-android" "android-x86" &
pids+=($!)
build_arch "x86_64" "amd64" "" "x86_64-linux-android" "android-x86_64" &
pids+=($!)

# Wait for all builds to complete and check exit codes
echo "Waiting for all builds to complete..."
failed=0
for pid in "${pids[@]}"; do
    if ! wait $pid; then
        failed=1
        echo "Build process $pid failed"
    fi
done

if [ $failed -eq 1 ]; then
    echo "Build failed for one or more architectures!"
    exit 1
fi

echo "Build completed successfully!"
echo ""
echo "Native libraries built to: $OUTPUT_DIR"
echo "You can now build the Android app with Gradle."

# Clean up build artifacts if needed
if [ -f cmd/jni/libkinetic.h ]; then
    rm -f cmd/jni/libkinetic.h
fi
