
if (MSVC OR MINGW)
    return()
endif()

if (NOT TARGET LibUSB::LibUSB)
    set(IMPORTED_LIBUSB_INCLUDE_DIRS ${LIBUSB_INCLUDE_DIR})
    set(IMPORTED_LIBUSB_LIBRARIES ${LIBUSB_LIBRARY})

    include(FindPackageHandleStandardArgs)
    find_package_handle_standard_args(LibUSB
            REQUIRED_VARS IMPORTED_LIBUSB_LIBRARIES IMPORTED_LIBUSB_INCLUDE_DIRS
    )

    set(LibUSB_FOUND TRUE)
    add_library(LibUSB::LibUSB
            UNKNOWN IMPORTED
    )
    if (IMPORTED_LIBUSB_INCLUDE_DIRS)
        set_target_properties(LibUSB::LibUSB PROPERTIES
                INTERFACE_INCLUDE_DIRECTORIES ${IMPORTED_LIBUSB_INCLUDE_DIRS}
        )
    endif()
    if (IMPORTED_LIBUSB_LIBRARIES)
        set_target_properties(LibUSB::LibUSB PROPERTIES
                IMPORTED_LINK_INTERFACE_LANGUAGES "C"
                IMPORTED_LOCATION ${IMPORTED_LIBUSB_LIBRARIES}
        )
    endif()
endif()