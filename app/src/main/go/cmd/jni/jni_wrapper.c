#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "_cgo_export.h"

// Helper function to convert jstring to C string
const char* jstring_to_cstring(JNIEnv* env, jstring str) {
    if (str == NULL) return NULL;
    return (*env)->GetStringUTFChars(env, str, NULL);
}

// Helper function to release C string from jstring
void release_cstring(JNIEnv* env, jstring str, const char* cstr) {
    if (str != NULL && cstr != NULL) {
        (*env)->ReleaseStringUTFChars(env, str, cstr);
    }
}

// Helper function to convert jbyteArray to C bytes
jbyte* jbyteArray_to_bytes(JNIEnv* env, jbyteArray arr) {
    if (arr == NULL) return NULL;
    return (*env)->GetByteArrayElements(env, arr, NULL);
}

// Helper function to release bytes
void release_bytes(JNIEnv* env, jbyteArray arr, jbyte* bytes) {
    if (arr != NULL && bytes != NULL) {
        (*env)->ReleaseByteArrayElements(env, arr, bytes, JNI_ABORT);
    }
}

// Global references for PLI callbacks
static JavaVM* g_jvm = NULL;
static jobject g_whipPLICallbacks[100] = {NULL};

// JNI wrapper functions
JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_Kinetic_init(JNIEnv* env, jclass clazz) {
    (*env)->GetJavaVM(env, &g_jvm);
    GoInit();
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_create(JNIEnv* env, jclass clazz, jstring url, jstring mimeTypes) {
    const char* urlStr = jstring_to_cstring(env, url);
    const char* mimeTypesStr = jstring_to_cstring(env, mimeTypes);
    
    jlong handle = GoCreateSRTSink((char*)urlStr, (char*)mimeTypesStr);
    
    release_cstring(env, url, urlStr);
    release_cstring(env, mimeTypes, mimeTypesStr);
    
    return handle;
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_writeH264(JNIEnv* env, jobject obj, jlong handle, jbyteArray data, jlong pts) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    jsize length = (*env)->GetArrayLength(env, data);
    
    GoSRTSinkWriteH264(handle, bytes, length, pts);
    
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_writeH265(JNIEnv* env, jobject obj, jlong handle, jbyteArray data, jlong pts) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    jsize length = (*env)->GetArrayLength(env, data);
    
    GoSRTSinkWriteH265(handle, bytes, length, pts);
    
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_writeOpus(JNIEnv* env, jobject obj, jlong handle, jbyteArray data, jlong pts) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    jsize length = (*env)->GetArrayLength(env, data);
    
    GoSRTSinkWriteOpus(handle, bytes, length, pts);
    
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_close(JNIEnv* env, jobject obj, jlong handle) {
    GoSRTSinkClose(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCSource_create(JNIEnv* env, jclass clazz, jint fd) {
    return GoCreateUVCSource(fd);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCSource_startStreaming(JNIEnv* env, jobject obj, jlong handle, jint format, jint width, jint height, jint fps) {
    return GoUVCSourceStartStreaming(handle, format, width, height, fps);
}

JNIEXPORT jbyteArray JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCStream_readFrame(JNIEnv* env, jobject obj, jlong handle) {
    void* dataPtr = NULL;
    int32_t size = 0;
    
    int32_t success = GoUVCStreamReadFrame(handle, &dataPtr, &size);
    if (success == 0 || dataPtr == NULL || size == 0) {
        return NULL;
    }
    
    // Create byte array and copy data
    jbyteArray result = (*env)->NewByteArray(env, size);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, size, (jbyte*)dataPtr);
    }
    
    // Free the allocated memory
    free(dataPtr);
    
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCStream_getPTS(JNIEnv* env, jobject obj, jlong handle) {
    return GoUVCStreamGetPTS(handle);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCStream_close(JNIEnv* env, jobject obj, jlong handle) {
    GoUVCStreamClose(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_create(JNIEnv* env, jclass clazz, jstring url, jstring token, jstring mimeTypes) {
    const char* urlStr = jstring_to_cstring(env, url);
    const char* tokenStr = jstring_to_cstring(env, token);
    const char* mimeTypesStr = jstring_to_cstring(env, mimeTypes);
    
    jlong handle = GoCreateWHIPSink((char*)urlStr, (char*)tokenStr, (char*)mimeTypesStr);
    
    release_cstring(env, url, urlStr);
    release_cstring(env, token, tokenStr);
    release_cstring(env, mimeTypes, mimeTypesStr);
    
    return handle;
}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_writeH264(JNIEnv* env, jobject obj, jlong handle, jbyteArray data, jlong pts) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    jsize length = (*env)->GetArrayLength(env, data);
    
    jint bitrate = GoWHIPSinkWriteH264(handle, bytes, length, pts);
    
    release_bytes(env, data, bytes);
    
    return bitrate;
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_writeOpus(JNIEnv* env, jobject obj, jlong handle, jbyteArray data, jlong pts) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    jsize length = (*env)->GetArrayLength(env, data);
    
    GoWHIPSinkWriteOpus(handle, bytes, length, pts);
    
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_close(JNIEnv* env, jobject obj, jlong handle) {
    // Clean up callback reference
    if (handle < 100 && g_whipPLICallbacks[handle] != NULL) {
        (*env)->DeleteGlobalRef(env, g_whipPLICallbacks[handle]);
        g_whipPLICallbacks[handle] = NULL;
    }
    GoWHIPSinkClose(handle);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_setPLICallback(JNIEnv* env, jobject obj, jlong handle, jobject callback) {
    if (handle < 100) {
        // Store global reference to callback
        if (g_whipPLICallbacks[handle] != NULL) {
            (*env)->DeleteGlobalRef(env, g_whipPLICallbacks[handle]);
        }
        g_whipPLICallbacks[handle] = (*env)->NewGlobalRef(env, callback);
        GoWHIPSinkSetPLICallback(handle);
    }
}

// Called from Go when PLI is received
void GoWHIPOnPLI(int64_t handle) {
    if (g_jvm == NULL || handle >= 100 || g_whipPLICallbacks[handle] == NULL) return;
    
    JNIEnv* env;
    (*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL);
    
    jclass cls = (*env)->GetObjectClass(env, g_whipPLICallbacks[handle]);
    jmethodID mid = (*env)->GetMethodID(env, cls, "onPLI", "()V");
    if (mid != NULL) {
        (*env)->CallVoidMethod(env, g_whipPLICallbacks[handle], mid);
    }
    
    (*g_jvm)->DetachCurrentThread(g_jvm);
}
