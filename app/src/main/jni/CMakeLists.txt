cmake_minimum_required(VERSION 3.22.1)

project(kinetic)

include(ExternalProject)

set(ANDROID_CMAKE_ARGS
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DANDROID_NDK=${ANDROID_NDK}
        -DCMAKE_CXX_FLAGS=${CMAKE_CXX_FLAGS}
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}
        -G${CMAKE_GENERATOR}
        -Wno-dev)

# Set SRT build directory based on the current ABI
set(SRT_BUILD_DIR ${CMAKE_SOURCE_DIR}/../go/third_party/srt/scripts/build-android/${ANDROID_ABI})

# Build external libraries
ExternalProject_Add(libjpeg-turbo
        GIT_REPOSITORY "https://github.com/libjpeg-turbo/libjpeg-turbo.git"
        GIT_TAG "3.0.2"
        CMAKE_ARGS ${ANDROID_CMAKE_ARGS}
        -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}
        -DENABLE_SHARED=ON
        BUILD_BYPRODUCTS ${CMAKE_BINARY_DIR}/lib/libjpeg.so ${CMAKE_BINARY_DIR}/lib/libturbojpeg.so
)

ExternalProject_Add(libusb
        GIT_REPOSITORY "https://github.com/libusb/libusb-cmake.git"
        GIT_TAG "v1.0.27"
        CMAKE_ARGS ${ANDROID_CMAKE_ARGS} -DBUILD_SHARED_LIBS=ON
        BUILD_BYPRODUCTS ${CMAKE_BINARY_DIR}/lib/libusb-1.0.so
)

ExternalProject_Add(libuvc
        GIT_REPOSITORY "https://github.com/libuvc/libuvc.git"
        GIT_TAG "v0.0.7"
        CMAKE_ARGS ${ANDROID_CMAKE_ARGS}
        -DCMAKE_MODULE_PATH=${CMAKE_SOURCE_DIR}
        -DJPEG_LIBRARY=${CMAKE_BINARY_DIR}/lib/libjpeg.so
        -DJPEG_INCLUDE_DIR=${CMAKE_BINARY_DIR}/include
        -DLIBUSB_INCLUDE_DIR=${CMAKE_BINARY_DIR}/include/libusb-1.0
        -DLIBUSB_LIBRARY=${CMAKE_BINARY_DIR}/lib/libusb-1.0.so
        DEPENDS libjpeg-turbo libusb
        BUILD_BYPRODUCTS ${CMAKE_BINARY_DIR}/lib/libuvc.so
)

# Build SRT with OpenSSL disabled for Android
ExternalProject_Add(srt
        GIT_REPOSITORY "https://github.com/Haivision/srt.git"
        GIT_TAG "v1.5.4"
        CMAKE_ARGS 
            ${ANDROID_CMAKE_ARGS}
            -DENABLE_APPS=OFF
            -DENABLE_LOGGING=ON
            -DENABLE_STDCXX_SYNC=ON
            -DENABLE_ENCRYPTION=OFF
            -DCMAKE_INSTALL_PREFIX=${SRT_BUILD_DIR}
        # Setup the SRT headers and library for the Go binding
        INSTALL_COMMAND 
            ${CMAKE_COMMAND} -E make_directory ${SRT_BUILD_DIR}/include/srt &&
            ${CMAKE_COMMAND} -E make_directory ${SRT_BUILD_DIR}/lib &&
            ${CMAKE_COMMAND} -E copy <BINARY_DIR>/version.h ${SRT_BUILD_DIR}/include/srt/ &&
            ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/srtcore ${SRT_BUILD_DIR}/include/srt &&
            ${CMAKE_COMMAND} -E copy <BINARY_DIR>/libsrt.so ${SRT_BUILD_DIR}/lib/libsrt.so
        BUILD_BYPRODUCTS ${SRT_BUILD_DIR}/lib/libsrt.so
)

# Setup native library 
link_directories(${CMAKE_BINARY_DIR}/lib)
include_directories(${CMAKE_BINARY_DIR}/include)

add_library(kinetic SHARED kinetic.c)
add_dependencies(kinetic libjpeg-turbo libusb libuvc srt)

# Link directly against the libraries
target_link_libraries(kinetic
    ${CMAKE_BINARY_DIR}/lib/libturbojpeg.so
    ${CMAKE_BINARY_DIR}/lib/libusb-1.0.so 
    ${CMAKE_BINARY_DIR}/lib/libuvc.so
    ${CMAKE_BINARY_DIR}/lib/libjpeg.so
)