#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <turbojpeg.h>
#include <libusb-1.0/libusb.h>
#include <libuvc/libuvc.h>

// JNI function to initialize native libraries
JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_StreamingService_initNativeLibraries(JNIEnv *env, jobject thiz) {
    // Initialize libusb
    int res = libusb_init(NULL);
    if (res < 0) {
        return -1;
    }
    
    // Initialize libuvc
    uvc_context_t *ctx;
    res = uvc_init(&ctx, NULL);
    if (res < 0) {
        libusb_exit(NULL);
        return -2;
    }
    
    // Clean up
    uvc_exit(ctx);
    
    return 0;
}