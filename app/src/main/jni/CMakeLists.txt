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

ExternalProject_Add(libjpeg-turbo
        GIT_REPOSITORY "https://github.com/libjpeg-turbo/libjpeg-turbo.git"
        GIT_TAG "3.0.2"
        CMAKE_ARGS ${ANDROID_CMAKE_ARGS})

ExternalProject_Add(libusb
        GIT_REPOSITORY "https://github.com/libusb/libusb-cmake.git"
        GIT_TAG "v1.0.27"
        CMAKE_ARGS ${ANDROID_CMAKE_ARGS} -DBUILD_SHARED_LIBS=ON)

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
)

link_directories(${CMAKE_BINARY_DIR}/lib)
include_directories(${CMAKE_BINARY_DIR}/include)

add_library(kinetic SHARED kinetic.c)
add_dependencies(kinetic libjpeg-turbo libusb libuvc)
# libjpeg.so has to be manually added to the linker for some reason.
target_link_libraries(kinetic turbojpeg usb-1.0 uvc ${CMAKE_BINARY_DIR}/lib/libjpeg.so)
