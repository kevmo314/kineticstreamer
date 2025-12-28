package main

/*
#cgo LDFLAGS: -landroid -llog

#include <android/log.h>
#include <stdlib.h>
#include <string.h>

int android_log_writer(const char* msg, int len) {
    // Make a copy with null terminator
    char* buf = (char*)malloc(len + 1);
    if (buf == NULL) return -1;
    memcpy(buf, msg, len);
    buf[len] = '\0';
    
    __android_log_print(ANDROID_LOG_INFO, "kinetic", "%s", buf);
    free(buf);
    return len;
}
*/
import "C"
import (
	"log"
	"unsafe"
)

type androidLogWriter struct{}

func (w androidLogWriter) Write(p []byte) (n int, err error) {
	if len(p) == 0 {
		return 0, nil
	}
	
	// Remove trailing newline if present
	msg := p
	if len(msg) > 0 && msg[len(msg)-1] == '\n' {
		msg = msg[:len(msg)-1]
	}
	
	cMsg := C.CString(string(msg))
	defer C.free(unsafe.Pointer(cMsg))
	
	ret := C.android_log_writer(cMsg, C.int(len(msg)))
	if ret < 0 {
		return 0, nil
	}
	return len(p), nil
}

func init() {
	// Redirect all log output to Android logcat
	log.SetOutput(androidLogWriter{})
	log.SetFlags(0) // Remove timestamp since logcat adds its own
}