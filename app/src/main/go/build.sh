#!/bin/bash
# Build the Go kinetic JNI library (libkinetic.so) for all Android ABIs.
#
# Stages:
#   1. third-party C deps (libusb, OpenSSL, SRT) via third_party/CMakeLists.txt.
#      Runs automatically if third_party/output/ is missing or if --rebuild-deps
#      is passed. Outputs to third_party/output/<abi>/.
#   2. Go shared library via `go build -buildmode=c-shared` per ABI.
#      Outputs to ../jniLibs/<abi>/libkinetic.so plus the third-party .so's
#      alongside, where the Android Gradle plugin picks them up.

set -eo pipefail

# ---- arg parsing -----------------------------------------------------------
REBUILD_DEPS=0
for arg in "$@"; do
    case "$arg" in
        --rebuild-deps) REBUILD_DEPS=1 ;;
        -h|--help)
            sed -n '2,9p' "$0" | sed 's/^# \?//'
            echo
            echo "Usage: $0 [--rebuild-deps]"
            exit 0
            ;;
        *) echo "Unknown arg: $arg"; exit 1 ;;
    esac
done


# ---- shared environment ----------------------------------------------------
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
THIRD_PARTY_DIR="$SCRIPT_DIR/third_party/output"
JNI_LIBS_DIR="$(cd "$SCRIPT_DIR/.." && pwd)/jniLibs"
INCLUDE_DIR="$SCRIPT_DIR/include"
ANDROID_API=24
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

case "$OSTYPE" in
    linux*)            NDK_HOST_TAG="linux-x86_64" ;;
    darwin*)           NDK_HOST_TAG="darwin-x86_64" ;;
    msys*|cygwin*|win*) NDK_HOST_TAG="windows-x86_64" ;;
    *) echo "Unsupported host: $OSTYPE"; exit 1 ;;
esac

if command -v nproc &> /dev/null; then
    NUM_CORES=$(nproc)
else
    NUM_CORES=$(sysctl -n hw.ncpu 2>/dev/null || echo 4)
fi

# Resolve ANDROID_NDK from environment, ANDROID_HOME, or local.properties.
if [ -z "$ANDROID_NDK" ]; then
    if [ -z "$ANDROID_HOME" ]; then
        LOCAL_PROPS="$( cd "$SCRIPT_DIR/../../../.." && pwd )/local.properties"
        if [ -f "$LOCAL_PROPS" ]; then
            ANDROID_HOME=$(grep '^sdk.dir=' "$LOCAL_PROPS" | cut -d= -f2-)
        fi
    fi
    if [ -z "$ANDROID_HOME" ] || [ ! -d "$ANDROID_HOME/ndk" ]; then
        echo "ANDROID_NDK not set and could not be derived from ANDROID_HOME / local.properties"
        exit 1
    fi
    ANDROID_NDK="$ANDROID_HOME/ndk/$(ls "$ANDROID_HOME/ndk" | sort -V | tail -1)"
fi
NDK_BIN="$ANDROID_NDK/toolchains/llvm/prebuilt/$NDK_HOST_TAG/bin"

if [[ "$OSTYPE" == msys* || "$OSTYPE" == cygwin* || "$OSTYPE" == win* ]]; then
    if command -v cygpath &> /dev/null; then
        export PATH="$(cygpath -u "$NDK_BIN"):$PATH"
    else
        export PATH="$NDK_BIN:$PATH"
    fi
fi

echo "Using ANDROID_NDK=$ANDROID_NDK"
echo "Using NDK_HOST_TAG=$NDK_HOST_TAG"
echo

# ---- stage 1: third-party C deps ------------------------------------------
build_third_party_arch() {
    local abi=$1
    (
        echo "[${abi}] Starting third-party build"
        mkdir -p "third_party/build/$abi" "third_party/output/$abi"
        cd "third_party/build/$abi"

        cmake -DCMAKE_INSTALL_PREFIX="../../output/$abi" \
              -DANDROID_ABI="$abi" \
              -DANDROID_API="$ANDROID_API" \
              -DANDROID_PLATFORM="android-$ANDROID_API" \
              -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="../../output/$abi/lib" \
              -DCMAKE_BUILD_TYPE=Release \
              -DANDROID_NDK="$ANDROID_NDK" \
              -DCMAKE_C_FLAGS="-fPIC" \
              -DCMAKE_CXX_FLAGS="-fPIC" \
              -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
              -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
              -DCMAKE_MAKE_PROGRAM=ninja \
              -GNinja \
              -Wno-dev \
              ../../

        cmake --build . -- -j"$NUM_CORES"
        echo "[${abi}] Third-party build completed"
    ) 2>&1 | while IFS= read -r line; do
        echo "[${abi}] ${line}"
    done
    return ${PIPESTATUS[0]}
}

deps_built() {
    for abi in "${ABIS[@]}"; do
        for lib in libusb-1.0.so libsrt.so libcrypto.so libssl.so; do
            [ -f "$THIRD_PARTY_DIR/$abi/lib/$lib" ] || return 1
        done
    done
    return 0
}

if [ "$REBUILD_DEPS" -eq 1 ] || ! deps_built; then
    cd "$SCRIPT_DIR"
    # Build sequentially. Parallel builds combined with OpenSSL's 'make -j'
    # (no job limit) fork-bomb the host.
    echo "Building third-party C dependencies..."
    for abi in "${ABIS[@]}"; do
        build_third_party_arch "$abi"
    done
    rm -rf third_party/build
    echo
fi

# ---- stage 2: Go shared library --------------------------------------------
mkdir -p "$INCLUDE_DIR"
for hdr in libusb-1.0 srt openssl; do
    if [ ! -d "$INCLUDE_DIR/$hdr" ] && [ -d "$THIRD_PARTY_DIR/arm64-v8a/include/$hdr" ]; then
        cp -r "$THIRD_PARTY_DIR/arm64-v8a/include/$hdr" "$INCLUDE_DIR/"
    fi
done

# ABI metadata: GOARCH, GOARM, NDK clang prefix
abi_meta() {
    case "$1" in
        arm64-v8a)   echo "arm64 _ aarch64-linux-android" ;;
        armeabi-v7a) echo "arm 7 armv7a-linux-androideabi" ;;
        x86)         echo "386 _ i686-linux-android" ;;
        x86_64)      echo "amd64 _ x86_64-linux-android" ;;
    esac
}

build_native_arch() {
    local abi=$1
    read -r goarch goarm cc_prefix <<< "$(abi_meta "$abi")"
    [ "$goarm" = "_" ] && goarm=""

    echo "[${abi}] Building libkinetic.so"
    mkdir -p "$JNI_LIBS_DIR/$abi"

    GOOS=android GOARCH="$goarch" GOARM="$goarm" CGO_ENABLED=1 \
        CC="$NDK_BIN/${cc_prefix}${ANDROID_API}-clang" \
        CXX="$NDK_BIN/${cc_prefix}${ANDROID_API}-clang++" \
        CGO_CFLAGS="-I$INCLUDE_DIR -I$THIRD_PARTY_DIR/$abi/include -D__ANDROID__" \
        CGO_LDFLAGS="-L$THIRD_PARTY_DIR/$abi/lib -lusb-1.0 -lsrt -lcrypto -lssl -static-libstdc++" \
        go build -C "$SCRIPT_DIR/cmd/jni" -buildmode=c-shared \
            -ldflags="-s -w -checklinkname=0 -extldflags '-Wl,-soname,libkinetic.so -Wl,-z,max-page-size=16384'" \
            -o "$JNI_LIBS_DIR/$abi/libkinetic.so" \
            .

    cp "$THIRD_PARTY_DIR/$abi/lib/libusb-1.0.so" \
       "$THIRD_PARTY_DIR/$abi/lib/libsrt.so" \
       "$THIRD_PARTY_DIR/$abi/lib/libcrypto.so" \
       "$THIRD_PARTY_DIR/$abi/lib/libssl.so" \
       "$JNI_LIBS_DIR/$abi/"
    echo "[${abi}] Done"
}

echo "Building libkinetic.so for all ABIs..."
pids=()
for abi in "${ABIS[@]}"; do
    build_native_arch "$abi" &
    pids+=($!)
done

failed=0
for pid in "${pids[@]}"; do
    if ! wait "$pid"; then failed=1; fi
done

# Generated by `go build -buildmode=c-shared`; not used.
rm -f "$SCRIPT_DIR/cmd/jni/libkinetic.h"

if [ "$failed" -eq 1 ]; then
    echo "One or more native builds failed"
    exit 1
fi

echo
echo "Native libraries built to: $JNI_LIBS_DIR"
