cmake_minimum_required(VERSION 3.22.1)

project(kinetic)

include(ExternalProject)

# 16KB page alignment linker flags for Android 15+
set(PAGE_SIZE_LINKER_FLAGS "-Wl,-z,max-page-size=16384")

# Host tag for NDK prebuilt toolchains (linux-x86_64, darwin-x86_64, windows-x86_64).
# OPENSSL_PATH_ENV pins the PATH for the OpenSSL Configure/make pipeline so the
# build is reproducible. Windows uses ';' as PATH separator, so libopenssl uses
# ExternalProject's LIST_SEPARATOR to keep PATH as one cmake -E env argument.
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
    set(NDK_HOST_TAG "linux-x86_64")
    set(OPENSSL_PATH_ENV "PATH=${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin:/usr/bin:/bin")
    set(OPENSSL_CONFIGURE ./Configure)
elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
    set(NDK_HOST_TAG "darwin-x86_64")
    set(OPENSSL_PATH_ENV "PATH=${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin:/usr/bin:/bin")
    set(OPENSSL_CONFIGURE ./Configure)
elseif (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(NDK_HOST_TAG "windows-x86_64")
    set(OPENSSL_PATCH_SCRIPT "${CMAKE_BINARY_DIR}/patch-openssl-android.cmake")
    file(WRITE "${OPENSSL_PATCH_SCRIPT}" [=[
if(NOT DEFINED OPENSSL_SOURCE_DIR)
    message(FATAL_ERROR "OPENSSL_SOURCE_DIR is required")
endif()

set(configure_file "${OPENSSL_SOURCE_DIR}/Configure")
file(READ "${configure_file}" configure_content)
string(REPLACE [==[
    if (eval { require IPC::Cmd; 1; }) {
        IPC::Cmd->import();
        return scalar IPC::Cmd::can_run($name);
    } else {
]==] [==[
    if (0) {
    } else {
]==] configure_content "${configure_content}")
string(REPLACE [==[
        foreach (File::Spec->path()) {
            my $fullpath = catfile($_, "$name$target{exe_extension}");
            if (-f $fullpath and -x $fullpath) {
                return $fullpath;
            }
        }
]==] [==[
        foreach (File::Spec->path()) {
            foreach my $suffix ($target{exe_extension} // "", ".cmd", ".exe", "") {
                my $fullpath = catfile($_, "$name$suffix");
                if (-f $fullpath and -x $fullpath) {
                    return $fullpath;
                }
            }
        }
]==] configure_content "${configure_content}")
file(WRITE "${configure_file}" "${configure_content}")

set(config_pm_file "${OPENSSL_SOURCE_DIR}/util/perl/OpenSSL/config.pm")
file(READ "${config_pm_file}" config_pm_content)
string(REPLACE "use IPC::Cmd;\n" "" config_pm_content "${config_pm_content}")
string(REPLACE [==[
my @c_compilers = qw(clang gcc cc);
]==] [==[
my @c_compilers = qw(clang gcc cc);

sub openssl_can_run {
    my $name = shift;
    return $name if (File::Spec->splitpath($name))[1] && -f $name;

    foreach my $dir (File::Spec->path()) {
        foreach my $suffix ("", ".cmd", ".exe") {
            my $fullpath = File::Spec->catfile($dir, "$name$suffix");
            return $fullpath if -f $fullpath;
        }
    }

    return undef;
}
]==] config_pm_content "${config_pm_content}")
string(REPLACE "IPC::Cmd::can_run" "openssl_can_run" config_pm_content "${config_pm_content}")
file(WRITE "${config_pm_file}" "${config_pm_content}")

set(config_file "${OPENSSL_SOURCE_DIR}/Configurations/15-android.conf")
file(READ "${config_file}" content)
string(REPLACE "if (which(\"clang\") =~ m|^$ndk/.*/prebuilt/([^/]+)/|) {\n                my $host=$1;" "if (which(\"clang\")) {\n                my $host=\"windows-x86_64\";" content "${content}")
string(REPLACE "if (which(\"llvm-ar\") =~ m|^$ndk/.*/prebuilt/([^/]+)/|) {" "if (which(\"llvm-ar\")) {" content "${content}")
file(WRITE "${config_file}" "${content}")
]=])
    set(OPENSSL_PATCH_COMMAND ${CMAKE_COMMAND} "-DOPENSSL_SOURCE_DIR=<SOURCE_DIR>" -P "${OPENSSL_PATCH_SCRIPT}")
    string(REPLACE ";" "|" _HOST_PATH "$ENV{PATH}")
    set(OPENSSL_PATH_ENV "PATH=${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin|${_HOST_PATH}")
    # cmd.exe doesn't honour unix shebangs, so invoke the perl script explicitly.
    set(OPENSSL_CONFIGURE perl Configure)
else()
    message(FATAL_ERROR "Unsupported host system: ${CMAKE_HOST_SYSTEM_NAME}")
endif()

if (NOT DEFINED OPENSSL_PATCH_COMMAND)
    set(OPENSSL_PATCH_COMMAND "")
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
    set(OPENSSL_CC_PREFIX "armv7a-linux-androideabi")
    set(MESON_TRIPLE "armv7a-linux-androideabi")
    set(MESON_CPU_FAMILY "arm")
    set(MESON_CPU "armv7-a")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(SSL_TARGET "android-arm64")
    set(OPENSSL_CC_PREFIX "aarch64-linux-android")
    set(MESON_TRIPLE "aarch64-linux-android")
    set(MESON_CPU_FAMILY "aarch64")
    set(MESON_CPU "aarch64")
elseif (ANDROID_ABI STREQUAL "x86")
    set(SSL_TARGET "android-x86")
    set(OPENSSL_CC_PREFIX "i686-linux-android")
    set(MESON_TRIPLE "i686-linux-android")
    set(MESON_CPU_FAMILY "x86")
    set(MESON_CPU "i686")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(SSL_TARGET "android-x86_64")
    set(OPENSSL_CC_PREFIX "x86_64-linux-android")
    set(MESON_TRIPLE "x86_64-linux-android")
    set(MESON_CPU_FAMILY "x86_64")
    set(MESON_CPU "x86_64")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI: ${ANDROID_ABI}")
endif()

set(OPENSSL_CC "${OPENSSL_CC_PREFIX}${ANDROID_API}-clang")
set(OPENSSL_CXX "${OPENSSL_CC_PREFIX}${ANDROID_API}-clang++")

ExternalProject_Add(libopenssl
        GIT_REPOSITORY "https://github.com/openssl/openssl.git"
        GIT_TAG "openssl-3.3.0"
        GIT_SHALLOW 1
        GIT_SUBMODULES ""  # disable git submodules
        LIST_SEPARATOR |
        PATCH_COMMAND ${OPENSSL_PATCH_COMMAND}
        BUILD_IN_SOURCE 1
        CONFIGURE_COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK} ${OPENSSL_PATH_ENV} "CC=${OPENSSL_CC}" "CXX=${OPENSSL_CXX}" "LDFLAGS=${PAGE_SIZE_LINKER_FLAGS}" ${OPENSSL_CONFIGURE} ${SSL_TARGET} -D__ANDROID_API__=${ANDROID_API} --prefix=${CMAKE_INSTALL_PREFIX} --openssldir=${CMAKE_INSTALL_PREFIX}
        BUILD_COMMAND ${CMAKE_COMMAND} -E env ANDROID_NDK_ROOT=${ANDROID_NDK} ${OPENSSL_PATH_ENV} "CC=${OPENSSL_CC}" "CXX=${OPENSSL_CXX}" "LDFLAGS=${PAGE_SIZE_LINKER_FLAGS}" make -j -Wno-macro-redefined  # openssl is horrendously noisy
        INSTALL_COMMAND ${CMAKE_COMMAND} -E env ${OPENSSL_PATH_ENV} make install
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

# librist is built with Meson (it has no CMakeLists.txt). Generate a per-ABI
# cross-file pointing at the NDK toolchain and drive `meson setup / compile /
# install` from ExternalProject.
set(MESON_NDK_BIN "${ANDROID_NDK}/toolchains/llvm/prebuilt/${NDK_HOST_TAG}/bin")
set(MESON_CROSS_FILE "${CMAKE_CURRENT_BINARY_DIR}/meson-android-${ANDROID_ABI}.txt")
set(MESON_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")
set(MESON_INSTALL_COMMAND meson install -C <BINARY_DIR>)
if (CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
    set(_NDK_CLANG_EXT ".cmd")
    set(_NDK_LLVM_EXT ".exe")
    set(MESON_INSTALL_PREFIX "/")
    set(MESON_INSTALL_COMMAND ${CMAKE_COMMAND} -E env "DESTDIR=${CMAKE_INSTALL_PREFIX}" meson install -C <BINARY_DIR>)
else()
    set(_NDK_CLANG_EXT "")
    set(_NDK_LLVM_EXT "")
endif()
file(WRITE "${MESON_CROSS_FILE}"
"[binaries]
c = '${MESON_NDK_BIN}/${MESON_TRIPLE}${ANDROID_API}-clang${_NDK_CLANG_EXT}'
cpp = '${MESON_NDK_BIN}/${MESON_TRIPLE}${ANDROID_API}-clang++${_NDK_CLANG_EXT}'
ar = '${MESON_NDK_BIN}/llvm-ar${_NDK_LLVM_EXT}'
strip = '${MESON_NDK_BIN}/llvm-strip${_NDK_LLVM_EXT}'

[built-in options]
c_link_args = ['${PAGE_SIZE_LINKER_FLAGS}']
cpp_link_args = ['${PAGE_SIZE_LINKER_FLAGS}']

[host_machine]
system = 'android'
cpu_family = '${MESON_CPU_FAMILY}'
cpu = '${MESON_CPU}'
endian = 'little'
")

ExternalProject_Add(librist
        GIT_REPOSITORY "https://code.videolan.org/rist/librist.git"
        GIT_TAG "v0.2.14"
        GIT_SHALLOW 1
        CONFIGURE_COMMAND meson setup --cross-file=${MESON_CROSS_FILE} --prefix=${MESON_INSTALL_PREFIX} --libdir=lib --buildtype=release --default-library=shared -Dbuilt_tools=false -Dtest=false -Duse_mbedtls=true -Dbuiltin_mbedtls=true --reconfigure <BINARY_DIR> <SOURCE_DIR>
        BUILD_COMMAND meson compile -C <BINARY_DIR>
        INSTALL_COMMAND ${MESON_INSTALL_COMMAND}
)
