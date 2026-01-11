#!/bin/bash

set -e

# Check if running on Windows (non-WSL)
if [[ "$OSTYPE" == "msys" ]] || [[ "$OSTYPE" == "cygwin" ]]; then
    echo "Windows detected. Please use build_third_party.ps1 instead:"
    echo "  PowerShell: .\\build_third_party.ps1"
    echo "  Or from cmd: powershell -ExecutionPolicy Bypass -File build_third_party.ps1"
    exit 1
fi

ANDROID_NDK=/home/kevin/android-ndk-r26d
ANDROID_API=24
ANDROID_PLATFORM=android-$ANDROID_API
CMAKE_C_FLAGS="-fPIC"
CMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384"
CMAKE_MAKE_PROGRAM=ninja
GOMOBILE_COMMAND=gomobile
CMAKE_COMMAND=cmake

# Setup ccache if available
if command -v ccache &> /dev/null; then
    echo "ccache found, enabling for faster rebuilds"
    export CCACHE_DIR=${CCACHE_DIR:-/tmp/android-ccache}
    export CCACHE_MAXSIZE=${CCACHE_MAXSIZE:-5G}
    mkdir -p "$CCACHE_DIR"
    
    # Configure ccache compilers
    export CC="ccache ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
    export CXX="ccache ${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++"
    
    # Show ccache stats at start
    ccache -s
else
    echo "ccache not found, consider installing it for faster rebuilds"
fi

# Function to build for a specific architecture
build_arch() {
    local abi=$1
    (
        echo "[${abi}] Starting build"
        mkdir -p third_party/build/$abi
        mkdir -p third_party/output/$abi
        cd third_party/build/$abi
        
        # Configure with cmake
        cmake -DCMAKE_INSTALL_PREFIX="../../output/$abi" \
              -DANDROID_ABI=$abi \
              -DANDROID_API=$ANDROID_API \
              -DANDROID_PLATFORM=$ANDROID_PLATFORM \
              -DCMAKE_LIBRARY_OUTPUT_DIRECTORY=../../output/$abi/lib \
              -DCMAKE_BUILD_TYPE=Release \
              -DANDROID_NDK=$ANDROID_NDK \
              -DCMAKE_C_FLAGS="$CMAKE_C_FLAGS" \
              -DCMAKE_CXX_FLAGS="$CMAKE_C_FLAGS" \
              -DCMAKE_SHARED_LINKER_FLAGS="$CMAKE_SHARED_LINKER_FLAGS" \
              -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake \
              -DCMAKE_MAKE_PROGRAM=$CMAKE_MAKE_PROGRAM \
              -GNinja \
              -Wno-dev \
              ../../
        
        # Build with ninja, using available cores
        cmake --build . -- -j$(nproc)
        
        echo "[${abi}] Build completed"
    ) 2>&1 | while IFS= read -r line; do
        echo "[${abi}] ${line}"
    done
}

# Export variables and function for parallel execution
export -f build_arch
export ANDROID_NDK ANDROID_API ANDROID_PLATFORM CMAKE_C_FLAGS CMAKE_SHARED_LINKER_FLAGS CMAKE_MAKE_PROGRAM CMAKE_COMMAND
export CC CXX CCACHE_DIR CCACHE_MAXSIZE

# Build all architectures in parallel
echo "Starting parallel builds for all architectures..."

# Track PIDs of background jobs
pids=()
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    build_arch "$abi" &
    pids+=($!)
done

# Wait for all builds to complete and check exit codes
echo "Waiting for all builds to complete..."
failed=0
for pid in "${pids[@]}"; do
    if ! wait $pid; then
        failed=1
        echo "Build process $pid failed"
    fi
done

# Check if all builds succeeded
if [ $failed -eq 1 ]; then
    echo "One or more builds failed"
    exit 1
else
    echo "All builds completed successfully"
fi

# Show ccache stats after build if available
if command -v ccache &> /dev/null; then
    echo "Final ccache statistics:"
    ccache -s
fi

# Remove the build directory
echo "Cleaning up build directories..."
rm -rf third_party/build

# Copy libraries to jniLibs (only the main shared libraries, not engine modules)
echo "Copying libraries to jniLibs..."
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
    mkdir -p ../jniLibs/$abi
    # Only copy the main .so files, skip subdirectories like engines-3 and ossl-modules
    cp third_party/output/$abi/lib/*.so ../jniLibs/$abi/ 2>/dev/null || true
done

echo "Build completed successfully!"
