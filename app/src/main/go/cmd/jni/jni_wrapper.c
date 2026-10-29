#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <pthread.h>
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

static JavaVM* g_jvm = NULL;
static jmethodID g_onPLI = NULL;  // PLICallback.onPLI, cached in Kinetic.init
static pthread_key_t g_detachKey;

// Detaches a Go-owned thread when it exits (we attach it lazily, see get_env).
static void detach_thread(void* unused) {
    (*g_jvm)->DetachCurrentThread(g_jvm);
}

// Returns the JNIEnv for the calling thread, attaching it (once, detached on
// thread exit) if it is a Go thread the JVM has not seen yet.
static JNIEnv* get_env(void) {
    JNIEnv* env = NULL;
    if (g_jvm == NULL) return NULL;
    if ((*g_jvm)->GetEnv(g_jvm, (void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_jvm)->AttachCurrentThread(g_jvm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(g_detachKey, env);
    return env;
}

// Called from Go: invoke PLICallback.onPLI on the global reference `ref`.
void kinetic_pli_call(uintptr_t ref) {
    JNIEnv* env = get_env();
    if (env == NULL || g_onPLI == NULL) return;
    (*env)->CallVoidMethod(env, (jobject)ref, g_onPLI);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

// Called from Go: delete a global reference created by a setPLICallback wrapper.
void kinetic_pli_release(uintptr_t ref) {
    JNIEnv* env = get_env();
    if (env != NULL) (*env)->DeleteGlobalRef(env, (jobject)ref);
}

// Creates the global reference handed to Go (NULL stays NULL = clear).
static uintptr_t new_pli_ref(JNIEnv* env, jobject callback) {
    return callback == NULL ? 0 : (uintptr_t)(*env)->NewGlobalRef(env, callback);
}

// JNI wrapper functions
JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_Kinetic_init(JNIEnv* env, jclass clazz) {
    (*env)->GetJavaVM(env, &g_jvm);
    pthread_key_create(&g_detachKey, detach_thread);
    jclass cls = (*env)->FindClass(env, "com/kevmo314/kineticstreamer/kinetic/PLICallback");
    if (cls != NULL) {
        g_onPLI = (*env)->GetMethodID(env, cls, "onPLI", "()V");
    }
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
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_writeSample(JNIEnv* env, jobject obj, jlong handle, jint streamIndex, jbyteArray data, jlong pts, jint flags) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    if (bytes == NULL) return;
    GoSRTSinkWriteSample(handle, streamIndex, bytes, (*env)->GetArrayLength(env, data), pts, flags);
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_close(JNIEnv* env, jobject obj, jlong handle) {
    GoSRTSinkClose(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_getBandwidth(JNIEnv* env, jobject obj, jlong handle) {
    return GoSRTSinkGetBandwidth(handle);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_SRTSink_setPLICallback(JNIEnv* env, jobject obj, jlong handle, jobject callback) {
    GoSRTSinkSetPLICallback(handle, new_pli_ref(env, callback));
}

// ---- RIST sink JNI bridge ---------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RISTSink_create(JNIEnv* env, jclass clazz, jstring url, jstring mimeTypes) {
    const char* urlStr = (*env)->GetStringUTFChars(env, url, NULL);
    const char* mimeTypesStr = (*env)->GetStringUTFChars(env, mimeTypes, NULL);
    jlong handle = GoCreateRISTSink((char*)urlStr, (char*)mimeTypesStr);
    (*env)->ReleaseStringUTFChars(env, url, urlStr);
    (*env)->ReleaseStringUTFChars(env, mimeTypes, mimeTypesStr);
    return handle;
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RISTSink_writeSample(JNIEnv* env, jobject obj, jlong handle, jint streamIndex, jbyteArray data, jlong pts, jint flags) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    if (bytes == NULL) return;
    GoRISTSinkWriteSample(handle, streamIndex, bytes, (*env)->GetArrayLength(env, data), pts, flags);
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RISTSink_close(JNIEnv* env, jobject obj, jlong handle) {
    GoRISTSinkClose(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCSource_create(JNIEnv* env, jclass clazz, jint fd) {
    return GoCreateUVCSource(fd);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCSource_startStreaming(JNIEnv* env, jobject obj, jlong handle, jint format, jint width, jint height, jint fps) {
    return GoUVCSourceStartStreaming(handle, format, width, height, fps);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCSource_close(JNIEnv* env, jobject obj, jlong handle) {
    GoUVCSourceClose(handle);
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

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_UVCStream_getArrivalTimeNs(JNIEnv* env, jobject obj, jlong handle) {
    return GoUVCStreamGetArrivalTimeNs(handle);
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
    if (bytes == NULL) return 0;
    jint bitrate = GoWHIPSinkWriteH264(handle, bytes, (*env)->GetArrayLength(env, data), pts);
    release_bytes(env, data, bytes);
    return bitrate;
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_writeOpus(JNIEnv* env, jobject obj, jlong handle, jbyteArray data, jlong pts) {
    jbyte* bytes = jbyteArray_to_bytes(env, data);
    if (bytes == NULL) return;
    GoWHIPSinkWriteOpus(handle, bytes, (*env)->GetArrayLength(env, data), pts);
    release_bytes(env, data, bytes);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_close(JNIEnv* env, jobject obj, jlong handle) {
    GoWHIPSinkClose(handle);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_setPLICallback(JNIEnv* env, jobject obj, jlong handle, jobject callback) {
    GoWHIPSinkSetPLICallback(handle, new_pli_ref(env, callback));
}

JNIEXPORT jstring JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_getICEConnectionState(JNIEnv* env, jobject obj, jlong handle) {
    char* state = GoWHIPSinkGetICEConnectionState(handle);
    jstring result = (*env)->NewStringUTF(env, state);
    free(state);  // Free the C string allocated by Go
    return result;
}

JNIEXPORT jstring JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_WHIPSink_getPeerConnectionState(JNIEnv* env, jobject obj, jlong handle) {
    char* state = GoWHIPSinkGetPeerConnectionState(handle);
    jstring result = (*env)->NewStringUTF(env, state);
    free(state);  // Free the C string allocated by Go
    return result;
}

// RTMP Server JNI wrappers (64-bit platforms only)
#if defined(__aarch64__) || defined(__x86_64__)

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeCreate(JNIEnv* env, jobject obj, jint port) {
    return GoCreateRTMPServer(port);
}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeStart(JNIEnv* env, jobject obj, jlong handle) {
    return GoRTMPServerStart(handle);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeStop(JNIEnv* env, jobject obj, jlong handle) {
    GoRTMPServerStop(handle);
}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeGetPort(JNIEnv* env, jobject obj, jlong handle) {
    return GoRTMPServerGetPort(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeGetSource(JNIEnv* env, jobject obj, jlong handle) {
    return GoRTMPServerGetSource(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeWaitForSource(JNIEnv* env, jobject obj, jlong handle, jint timeoutMs) {
    return GoRTMPServerWaitForSource(handle, timeoutMs);
}

// RTMP Source JNI wrappers

JNIEXPORT jbyteArray JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeReadVideoFrame(JNIEnv* env, jobject obj, jlong handle) {
    void* dataPtr = NULL;
    int32_t size = 0;

    int32_t success = GoRTMPSourceReadVideoFrame(handle, &dataPtr, &size);
    if (success == 0 || dataPtr == NULL || size == 0) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, size);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, size, (jbyte*)dataPtr);
    }

    free(dataPtr);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeReadAudioFrame(JNIEnv* env, jobject obj, jlong handle) {
    void* dataPtr = NULL;
    int32_t size = 0;

    int32_t success = GoRTMPSourceReadAudioFrame(handle, &dataPtr, &size);
    if (success == 0 || dataPtr == NULL || size == 0) {
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, size);
    if (result != NULL) {
        (*env)->SetByteArrayRegion(env, result, 0, size, (jbyte*)dataPtr);
    }

    free(dataPtr);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeGetVideoPTS(JNIEnv* env, jobject obj, jlong handle) {
    return GoRTMPSourceGetVideoPTS(handle);
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeGetAudioPTS(JNIEnv* env, jobject obj, jlong handle) {
    return GoRTMPSourceGetAudioPTS(handle);
}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeIsClosed(JNIEnv* env, jobject obj, jlong handle) {
    return GoRTMPSourceIsClosed(handle);
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeClose(JNIEnv* env, jobject obj, jlong handle) {
    GoRTMPSourceClose(handle);
}

#else
// Stub implementations for 32-bit platforms

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeCreate(JNIEnv* env, jobject obj, jint port) {
    return 0; // RTMP not supported on 32-bit
}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeStart(JNIEnv* env, jobject obj, jlong handle) {
    return 0;
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeStop(JNIEnv* env, jobject obj, jlong handle) {}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeGetPort(JNIEnv* env, jobject obj, jlong handle) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeGetSource(JNIEnv* env, jobject obj, jlong handle) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPServer_nativeWaitForSource(JNIEnv* env, jobject obj, jlong handle, jint timeoutMs) {
    return 0;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeReadVideoFrame(JNIEnv* env, jobject obj, jlong handle) {
    return NULL;
}

JNIEXPORT jbyteArray JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeReadAudioFrame(JNIEnv* env, jobject obj, jlong handle) {
    return NULL;
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeGetVideoPTS(JNIEnv* env, jobject obj, jlong handle) {
    return 0;
}

JNIEXPORT jlong JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeGetAudioPTS(JNIEnv* env, jobject obj, jlong handle) {
    return 0;
}

JNIEXPORT jint JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeIsClosed(JNIEnv* env, jobject obj, jlong handle) {
    return 1; // Always closed on 32-bit
}

JNIEXPORT void JNICALL
Java_com_kevmo314_kineticstreamer_kinetic_RTMPSource_nativeClose(JNIEnv* env, jobject obj, jlong handle) {}

#endif // __aarch64__ || __x86_64__
