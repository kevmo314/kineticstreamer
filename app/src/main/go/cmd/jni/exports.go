package main

// #include <stdint.h>
// #include <stdlib.h>
// void GoWHIPOnPLI(int64_t handle);
// void GoSRTOnPLI(int64_t handle);
import "C"
import (
	"log"
	"runtime"
	"runtime/debug"
	"sync"
	"unsafe"
	
	"github.com/kevmo314/kinetic"
)

// Store references to prevent garbage collection
var (
	mu          sync.RWMutex
	srtSinks    = make(map[int64]*kinetic.SRTSink)
	uvcSources  = make(map[int64]*kinetic.UVCSource)
	uvcStreams  = make(map[int64]*kinetic.UVCStream)
	whipSinks   = make(map[int64]*kinetic.WHIPSink)
	whipPLICallbacks = make(map[int64]func())
	rtspServers = make(map[int64]*kinetic.RTSPServerSink)
	nextHandle  int64 = 1
)

//export GoInit
func GoInit() {
	runtime.LockOSThread()
}

//export GoCreateSRTSink
func GoCreateSRTSink(urlStr *C.char, mimeTypesStr *C.char) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateSRTSink: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()
	
	url := C.GoString(urlStr)
	mimeTypes := C.GoString(mimeTypesStr)
	
	sink, err := kinetic.NewSRTSink(url, mimeTypes)
	if err != nil {
		return 0
	}
	
	mu.Lock()
	handle = nextHandle
	nextHandle++
	srtSinks[handle] = sink
	mu.Unlock()
	
	return handle
}

//export GoSRTSinkWriteSample
func GoSRTSinkWriteSample(handle int64, streamIndex int32, data unsafe.Pointer, length int32, ptsMicroseconds int64, flags int32) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoSRTSinkWriteSample: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()
	
	mu.RLock()
	sink, ok := srtSinks[handle]
	mu.RUnlock()
	
	if !ok {
		return
	}
	
	// Convert C bytes to Go slice without copying
	goData := (*[1 << 30]byte)(data)[:length:length]
	sink.WriteSample(int(streamIndex), goData, ptsMicroseconds, flags)
}

//export GoSRTSinkWriteH264
func GoSRTSinkWriteH264(handle int64, data unsafe.Pointer, length int32, pts int64) {
	// For backward compatibility, map to WriteSample with stream index 0
	GoSRTSinkWriteSample(handle, 0, data, length, pts, 0)
}

//export GoSRTSinkWriteH265
func GoSRTSinkWriteH265(handle int64, data unsafe.Pointer, length int32, pts int64) {
	// For backward compatibility, map to WriteSample with stream index 0
	GoSRTSinkWriteSample(handle, 0, data, length, pts, 0)
}

//export GoSRTSinkWriteOpus
func GoSRTSinkWriteOpus(handle int64, data unsafe.Pointer, length int32, pts int64) {
	// For backward compatibility, map to WriteSample with stream index 1 (audio)
	GoSRTSinkWriteSample(handle, 1, data, length, pts, 0)
}

//export GoSRTSinkClose
func GoSRTSinkClose(handle int64) {
	mu.Lock()
	sink, ok := srtSinks[handle]
	if ok {
		sink.Close()
		delete(srtSinks, handle)
	}
	mu.Unlock()
}

//export GoSRTSinkGetBandwidth
func GoSRTSinkGetBandwidth(handle int64) int64 {
	mu.Lock()
	sink, ok := srtSinks[handle]
	mu.Unlock()

	if !ok {
		return 0
	}

	return sink.GetStatsAndBandwidth()
}

//export GoSRTSinkSetPLICallback
func GoSRTSinkSetPLICallback(handle int64) {
	mu.Lock()
	sink, ok := srtSinks[handle]
	if ok {
		sink.SetPLICallback(&srtPLICallbackWrapper{handle: handle})
	}
	mu.Unlock()
}

type srtPLICallbackWrapper struct {
	handle int64
}

func (w *srtPLICallbackWrapper) OnPLI() {
	C.GoSRTOnPLI(C.int64_t(w.handle))
}

//export GoCreateUVCSource
func GoCreateUVCSource(fd int32) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateUVCSource: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()
	
	log.Printf("GoCreateUVCSource called with fd=%d", fd)
	source, err := kinetic.NewUVCSource(int(fd))
	if err != nil {
		log.Printf("Failed to create UVC source: %v", err)
		return 0
	}
	log.Printf("Successfully created UVC source")
	
	mu.Lock()
	handle = nextHandle
	nextHandle++
	uvcSources[handle] = source
	mu.Unlock()
	
	return handle
}

//export GoUVCSourceStartStreaming
func GoUVCSourceStartStreaming(handle int64, format, width, height, fps int32) (streamHandle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoUVCSourceStartStreaming: %v\nStack trace:\n%s", r, debug.Stack())
			streamHandle = 0
		}
	}()
	
	mu.RLock()
	source, ok := uvcSources[handle]
	mu.RUnlock()
	
	if !ok {
		return 0
	}
	
	stream, err := source.StartStreaming(int(format), int(width), int(height), int(fps))
	if err != nil {
	    log.Printf("got error: %s", err)
		return 0
	}
	
	mu.Lock()
	streamHandle = nextHandle
	nextHandle++
	uvcStreams[streamHandle] = stream
	mu.Unlock()
	
	return streamHandle
}

//export GoUVCStreamReadFrame
func GoUVCStreamReadFrame(handle int64, dataPtr *unsafe.Pointer, sizePtr *int32) (success int32) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoUVCStreamReadFrame: %v\nStack trace:\n%s", r, debug.Stack())
			success = 0
		}
	}()
	
	mu.RLock()
	stream, ok := uvcStreams[handle]
	mu.RUnlock()
	
	if !ok {
		return 0
	}
	
	data, err := stream.ReadFrame()
	if err != nil {
		return 0
	}

	// Allocate memory and copy data
	*dataPtr = C.CBytes(data)
	*sizePtr = int32(len(data))
	
	return 1 // Success
}

//export GoUVCStreamGetPTS
func GoUVCStreamGetPTS(handle int64) int64 {
	mu.RLock()
	stream, ok := uvcStreams[handle]
	mu.RUnlock()
	
	if !ok {
		return 0
	}
	
	return stream.GetPTS()
}

//export GoUVCStreamClose
func GoUVCStreamClose(handle int64) {
	mu.Lock()
	stream, ok := uvcStreams[handle]
	if ok {
		stream.Close()
		delete(uvcStreams, handle)
	}
	mu.Unlock()
}

//export GoCreateWHIPSink
func GoCreateWHIPSink(urlStr, tokenStr, mimeTypesStr *C.char) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateWHIPSink: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()
	
	url := C.GoString(urlStr)
	token := C.GoString(tokenStr)
	mimeTypes := C.GoString(mimeTypesStr)

	log.Printf("WHIP: creating sink url=%s mimeTypes=%s", url, mimeTypes)

	sink, err := kinetic.NewWHIPSink(url, token, mimeTypes)
	if err != nil {
		log.Printf("WHIP: failed to create sink: %v", err)
		return 0
	}
	log.Printf("WHIP: sink created successfully")
	
	mu.Lock()
	handle = nextHandle
	nextHandle++
	whipSinks[handle] = sink
	mu.Unlock()
	
	return handle
}

// Forward declaration for C callback function
// GoWHIPOnPLI is implemented in jni_wrapper.c

//export GoWHIPSinkSetPLICallback
func GoWHIPSinkSetPLICallback(handle int64) {
	mu.Lock()
	sink, ok := whipSinks[handle]
	if ok {
		sink.SetPLICallback(func() {
			C.GoWHIPOnPLI(C.int64_t(handle))
		})
	}
	mu.Unlock()
}

//export GoWHIPSinkWriteH264
func GoWHIPSinkWriteH264(handle int64, data unsafe.Pointer, length int32, pts int64) (bitrate int32) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoWHIPSinkWriteH264: %v\nStack trace:\n%s", r, debug.Stack())
			bitrate = 0
		}
	}()
	
	mu.RLock()
	sink, ok := whipSinks[handle]
	mu.RUnlock()
	
	if !ok {
		return 0
	}
	
	// Convert C bytes to Go slice without copying
	goData := (*[1 << 30]byte)(data)[:length:length]
	// Make a copy since the original data might be reused
	dataCopy := make([]byte, length)
	copy(dataCopy, goData)
	bitrateVal, err := sink.WriteH264(dataCopy, pts)
	if err != nil {
		log.Printf("Error writing H264: %v", err)
		return 0
	}
	return int32(bitrateVal)
}

//export GoWHIPSinkWriteOpus
func GoWHIPSinkWriteOpus(handle int64, data unsafe.Pointer, length int32, pts int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoWHIPSinkWriteOpus: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()
	
	mu.RLock()
	sink, ok := whipSinks[handle]
	mu.RUnlock()
	
	if !ok {
		return
	}
	
	// Convert C bytes to Go slice without copying
	goData := (*[1 << 30]byte)(data)[:length:length]
	// Make a copy since the original data might be reused
	dataCopy := make([]byte, length)
	copy(dataCopy, goData)
	sink.WriteOpus(dataCopy, pts)
}

//export GoWHIPSinkClose
func GoWHIPSinkClose(handle int64) {
	mu.Lock()
	sink, ok := whipSinks[handle]
	if ok {
		sink.Close()
		delete(whipSinks, handle)
	}
	mu.Unlock()
}

//export GoWHIPSinkGetICEConnectionState
func GoWHIPSinkGetICEConnectionState(handle int64) *C.char {
	mu.RLock()
	sink, ok := whipSinks[handle]
	mu.RUnlock()

	if !ok {
		return C.CString("unknown")
	}

	return C.CString(sink.GetICEConnectionState())
}

//export GoWHIPSinkGetPeerConnectionState
func GoWHIPSinkGetPeerConnectionState(handle int64) *C.char {
	mu.RLock()
	sink, ok := whipSinks[handle]
	mu.RUnlock()

	if !ok {
		return C.CString("unknown")
	}

	return C.CString(sink.GetPeerConnectionState())
}
