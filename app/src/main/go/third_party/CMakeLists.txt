cmake_minimum_required(VERSION 3.22.1)

project(kinetic)

include(ExternalProject)

# 16KB page alignment linker flags for Android 15+
set(PAGE_SIZE_LINKER_FLAGS "-Wl,-z,max-page-size=16384")

# Host tag for NDK prebuilt toolchains (linux-x86_64, darwin-x86_64, windows-x86_64)
# and per-host PATH separator for the OpenSSL build environment.
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(NDK_HOST_TAG "linux-x86_64")
    set(OPENSSL_PATH_EXTRA ":/usr/bin:/bin")
elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(NDK_HOST_TAG "darwin-x86_64")
    set(OPENSSL_PATH_EXTRA ":/usr/bin:/bin")
elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(NDK_HOST_TAG "windows-x86_64")
    # Windows uses ';'. Inherit the rest of PATH via $ENV{PATH} so perl, make, and
    # the Git-Bash shell are reachable from OpenSSL's Configure/make pipeline.
    set(OPENSSL_PATH_EXTRA ";$ENV{PATH}")
else()
    message(FATAL_ERROR "Unsupported host system: ${CMAKE_HOST_SYSTEM_NAME}")
endif()

set(ANDROID_CMAKE_ARGS
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_C_FLAGS=${CMAKE_C_FLAGS}
        -DCMAKE_CXX_FLAGS=${CMAKE_CXX_FLAGS}
        -DCMAKE_SHARED_LINKER_FLAGS=${PAGE_SIZE_LINKER_FLAGS}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_INSTALL_PREFIX=${CMAKE_INSTALL_PREFIX}
        -G${CMAKE_GENERATOR}
        -Wno-dev)

ExternalProject_Add(libusb
        GIT_REPOSITORY "https://github.com/libusb/libusb-cmake.git"
        GIT_TAG "v1.0.27"
        GIT_SHALLOW 1
        CMAKE_ARGS ${ANDROID_CMAKE_ARGS} -DLIBUSB_BUILD_SHARED_LIBS=ON)

if (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(SSL_TARGET "android-arm")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(SSL_TARGET "android-arm64")
elseif (ANDROID_ABI STREQUAL "x86")
    set(SSL_TARGET "android-x86")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(SSL_TARGET "android-x86_64")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
endif()

ExternalProject_Add(libopenssl
        GIT_REPOSITORY "https://github.com/openssl/openssl.git"
        GIT_TAG "openssl-3.3.0"
        GIT_SHALLOW 1
        GIT_SUBMODULES ""  # disable git submodules
        BUILD_IN_SOURCE 1
        # configure an explicit path to improve build reproducibility
        # Add LDFLAGS for 16KB page alignment
        CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK} "PATH=${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin${OPENSSL_PATH_EXTRA}" "LDFLAGS=${PAGE_SIZE_LINKER_FLAGS}" ./Configure ${SSL_TARGET} -D__ANDROID_API__=${ANDROID_API} --prefix=${CMAKE_INSTALL_PREFIX} --openssldir=${CMAKE_INSTALL_PREFIX}
        BUILD_COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK} "PATH=${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin${OPENSSL_PATH_EXTRA}" "LDFLAGS=${PAGE_SIZE_LINKER_FLAGS}" make -j -Wno-macro-redefined  # openssl is horrendously noisy
        INSTALL_COMMAND make install
)

ExternalProject_Add(libsrt
        GIT_REPOSITORY "https://github.com/Haivision/srt.git"
        GIT_TAG "v1.5.1"
        GIT_SHALLOW 1
        CMAKE_ARGS
        -DCMAKE_POLICY_VERSION_MINIMUM=3.5  # SRT v1.5.1 declares cmake_minimum_required(2.8.12); CMake 4+ dropped pre-3.5 compat.
        -DSSL_FOUND=0  # this is a total hack, for some reason cmake keeps picking up openssl via pkg-config so manually disable it.
        -DUSE_OPENSSL_PC=OFF
        -DOPENSSL_INCLUDE_DIR=${CMAKE_INSTALL_PREFIX}/include
        -DOPENSSL_CRYPTO_LIBRARY=${CMAKE_INSTALL_PREFIX}/lib/libcrypto.so
        -DOPENSSL_SSL_LIBRARY=${CMAKE_INSTALL_PREFIX}/lib/libssl.so
        -DCMAKE_SYSTEM_NAME=Android
        -DCMAKE_SYSTEM_VERSION=${ANDROID_API}
        -DCMAKE_ANDROID_ARCH_ABI=${ANDROID_ABI}
        -DCMAKE_ANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_INSTALL_PREFIX=${CMAKE_INSTALL_PREFIX}
        -DCMAKE_C_FLAGS=${CMAKE_C_FLAGS}
        -DCMAKE_CXX_FLAGS=${CMAKE_CXX_FLAGS}
        -DCMAKE_SHARED_LINKER_FLAGS=${PAGE_SIZE_LINKER_FLAGS}
        -DENABLE_BONDING=ON
        -DENABLE_APPS=OFF
        DEPENDS libopenssl
)
