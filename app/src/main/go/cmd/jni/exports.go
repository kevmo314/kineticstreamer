package main

// #include <stdint.h>
// #include <stdlib.h>
// void kinetic_pli_call(uintptr_t ref);
// void kinetic_pli_release(uintptr_t ref);
import "C"
import (
	"log"
	"runtime/debug"
	"sync"
	"unsafe"

	"github.com/kevmo314/kinetic"
)

// Handle tables. Closing always happens after the entry is removed, so a slow
// Close (HTTP DELETE, SRT reconnect) never blocks other handles.
var (
	srtSinks   kinetic.Handles[*srtEntry]
	ristSinks  kinetic.Handles[*kinetic.RISTSink]
	uvcSources kinetic.Handles[*kinetic.UVCSource]
	uvcStreams kinetic.Handles[*kinetic.UVCStream]
	whipSinks  kinetic.Handles[*whipEntry]
)

// mu and nextHandle are used by the RTMP tables in exports_rtmp.go.
var (
	mu         sync.RWMutex
	nextHandle int64 = 1
)

// pliHook holds the JNI global reference of a Kotlin PLICallback. The
// reference is released under the same lock that guards calls into Java, so a
// concurrent close can never delete it while a callback is in flight.
type pliHook struct {
	mu     sync.Mutex
	ref    C.uintptr_t
	closed bool
}

// set replaces the callback reference (0 clears it), releasing the old one.
// After close, new references are released immediately instead of stored.
func (p *pliHook) set(ref C.uintptr_t) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.ref != 0 {
		C.kinetic_pli_release(p.ref)
		p.ref = 0
	}
	if p.closed && ref != 0 {
		C.kinetic_pli_release(ref)
		return
	}
	p.ref = ref
}

// close releases the reference and rejects future ones.
func (p *pliHook) close() {
	p.mu.Lock()
	p.closed = true
	p.mu.Unlock()
	p.set(0)
}

func (p *pliHook) fire() {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.ref != 0 {
		C.kinetic_pli_call(p.ref)
	}
}

type srtEntry struct {
	sink *kinetic.SRTSink
	pli  pliHook
}

// OnPLI implements kinetic.SRTPLICallback.
func (e *srtEntry) OnPLI() { e.pli.fire() }

type whipEntry struct {
	sink *kinetic.WHIPSink
	pli  pliHook
}

// cbytes views C memory as a Go slice without copying.
func cbytes(data unsafe.Pointer, length int32) []byte {
	if data == nil || length <= 0 {
		return nil
	}
	return unsafe.Slice((*byte)(data), int(length))
}

func recoverLog(name string) {
	if r := recover(); r != nil {
		log.Printf("PANIC in %s: %v\nStack trace:\n%s", name, r, debug.Stack())
	}
}

//export GoCreateSRTSink
func GoCreateSRTSink(urlStr *C.char, mimeTypesStr *C.char) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateSRTSink: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()

	sink, err := kinetic.NewSRTSink(C.GoString(urlStr), C.GoString(mimeTypesStr))
	if err != nil {
		log.Printf("Failed to create SRT sink: %v", err)
		return 0
	}

	e := &srtEntry{sink: sink}
	sink.SetPLICallback(e)
	return srtSinks.Add(e)
}

//export GoSRTSinkWriteSample
func GoSRTSinkWriteSample(handle int64, streamIndex int32, data unsafe.Pointer, length int32, ptsMicroseconds int64, flags int32) {
	defer recoverLog("GoSRTSinkWriteSample")

	e, ok := srtSinks.Get(handle)
	if !ok {
		return
	}
	if err := e.sink.WriteSample(int(streamIndex), cbytes(data, length), ptsMicroseconds, flags); err != nil {
		log.Printf("SRT: WriteSample: %v", err)
	}
}

//export GoSRTSinkClose
func GoSRTSinkClose(handle int64) {
	e, ok := srtSinks.Remove(handle)
	if !ok {
		return
	}
	e.pli.close()
	e.sink.Close()
}

//export GoSRTSinkGetBandwidth
func GoSRTSinkGetBandwidth(handle int64) int64 {
	e, ok := srtSinks.Get(handle)
	if !ok {
		return 0
	}
	return e.sink.GetStatsAndBandwidth()
}

// GoSRTSinkSetPLICallback takes ownership of ref, a JNI global reference
// (0 clears the callback).
//
//export GoSRTSinkSetPLICallback
func GoSRTSinkSetPLICallback(handle int64, ref uintptr) {
	e, ok := srtSinks.Get(handle)
	if !ok {
		if ref != 0 {
			C.kinetic_pli_release(C.uintptr_t(ref))
		}
		return
	}
	e.pli.set(C.uintptr_t(ref))
}

//export GoCreateRISTSink
func GoCreateRISTSink(urlStr *C.char, mimeTypesStr *C.char) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateRISTSink: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()

	sink, err := kinetic.NewRISTSink(C.GoString(urlStr), C.GoString(mimeTypesStr))
	if err != nil {
		log.Printf("Failed to create RIST sink: %v", err)
		return 0
	}
	return ristSinks.Add(sink)
}

//export GoRISTSinkWriteSample
func GoRISTSinkWriteSample(handle int64, streamIndex int32, data unsafe.Pointer, length int32, ptsMicroseconds int64, flags int32) {
	defer recoverLog("GoRISTSinkWriteSample")

	sink, ok := ristSinks.Get(handle)
	if !ok {
		return
	}
	if err := sink.WriteSample(int(streamIndex), cbytes(data, length), ptsMicroseconds, flags); err != nil {
		log.Printf("RIST: WriteSample: %v", err)
	}
}

//export GoRISTSinkClose
func GoRISTSinkClose(handle int64) {
	if sink, ok := ristSinks.Remove(handle); ok {
		sink.Close()
	}
}

//export GoCreateUVCSource
func GoCreateUVCSource(fd int32) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateUVCSource: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()

	source, err := kinetic.NewUVCSource(int(fd))
	if err != nil {
		log.Printf("Failed to create UVC source: %v", err)
		return 0
	}
	return uvcSources.Add(source)
}

//export GoUVCSourceStartStreaming
func GoUVCSourceStartStreaming(handle int64, format, width, height, fps int32) (streamHandle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoUVCSourceStartStreaming: %v\nStack trace:\n%s", r, debug.Stack())
			streamHandle = 0
		}
	}()

	source, ok := uvcSources.Get(handle)
	if !ok {
		return 0
	}
	stream, err := source.StartStreaming(int(format), int(width), int(height), int(fps))
	if err != nil {
		log.Printf("UVC: StartStreaming: %v", err)
		return 0
	}
	return uvcStreams.Add(stream)
}

//export GoUVCSourceClose
func GoUVCSourceClose(handle int64) {
	if source, ok := uvcSources.Remove(handle); ok {
		source.Close()
	}
}

//export GoUVCStreamReadFrame
func GoUVCStreamReadFrame(handle int64, dataPtr *unsafe.Pointer, sizePtr *int32) (success int32) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoUVCStreamReadFrame: %v\nStack trace:\n%s", r, debug.Stack())
			success = 0
		}
	}()

	stream, ok := uvcStreams.Get(handle)
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
	return 1
}

//export GoUVCStreamGetPTS
func GoUVCStreamGetPTS(handle int64) int64 {
	stream, ok := uvcStreams.Get(handle)
	if !ok {
		return 0
	}
	return stream.GetPTS()
}

//export GoUVCStreamGetArrivalTimeNs
func GoUVCStreamGetArrivalTimeNs(handle int64) int64 {
	stream, ok := uvcStreams.Get(handle)
	if !ok {
		return 0
	}
	return stream.GetArrivalTimeNs()
}

//export GoUVCStreamClose
func GoUVCStreamClose(handle int64) {
	if stream, ok := uvcStreams.Remove(handle); ok {
		stream.Close()
	}
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
	mimeTypes := C.GoString(mimeTypesStr)

	log.Printf("WHIP: creating sink url=%s mimeTypes=%s", url, mimeTypes)

	sink, err := kinetic.NewWHIPSink(url, C.GoString(tokenStr), mimeTypes)
	if err != nil {
		log.Printf("WHIP: failed to create sink: %v", err)
		return 0
	}

	e := &whipEntry{sink: sink}
	sink.SetPLICallback(e.pli.fire)
	return whipSinks.Add(e)
}

// GoWHIPSinkSetPLICallback takes ownership of ref, a JNI global reference
// (0 clears the callback).
//
//export GoWHIPSinkSetPLICallback
func GoWHIPSinkSetPLICallback(handle int64, ref uintptr) {
	e, ok := whipSinks.Get(handle)
	if !ok {
		if ref != 0 {
			C.kinetic_pli_release(C.uintptr_t(ref))
		}
		return
	}
	e.pli.set(C.uintptr_t(ref))
}

//export GoWHIPSinkWriteH264
func GoWHIPSinkWriteH264(handle int64, data unsafe.Pointer, length int32, pts int64) (bitrate int32) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoWHIPSinkWriteH264: %v\nStack trace:\n%s", r, debug.Stack())
			bitrate = 0
		}
	}()

	e, ok := whipSinks.Get(handle)
	if !ok {
		return 0
	}
	bitrateVal, err := e.sink.WriteH264(cbytes(data, length), pts)
	if err != nil {
		log.Printf("Error writing H264: %v", err)
		return 0
	}
	return int32(bitrateVal)
}

//export GoWHIPSinkWriteOpus
func GoWHIPSinkWriteOpus(handle int64, data unsafe.Pointer, length int32, pts int64) {
	defer recoverLog("GoWHIPSinkWriteOpus")

	e, ok := whipSinks.Get(handle)
	if !ok {
		return
	}
	if err := e.sink.WriteOpus(cbytes(data, length), pts); err != nil {
		log.Printf("WHIP: WriteOpus: %v", err)
	}
}

//export GoWHIPSinkClose
func GoWHIPSinkClose(handle int64) {
	e, ok := whipSinks.Remove(handle)
	if !ok {
		return
	}
	e.pli.close()
	e.sink.Close()
}

//export GoWHIPSinkGetICEConnectionState
func GoWHIPSinkGetICEConnectionState(handle int64) *C.char {
	e, ok := whipSinks.Get(handle)
	if !ok {
		return C.CString("unknown")
	}
	return C.CString(e.sink.GetICEConnectionState())
}

//export GoWHIPSinkGetPeerConnectionState
func GoWHIPSinkGetPeerConnectionState(handle int64) *C.char {
	e, ok := whipSinks.Get(handle)
	if !ok {
		return C.CString("unknown")
	}
	return C.CString(e.sink.GetPeerConnectionState())
}

// RTMP Server exports are in exports_rtmp.go (64-bit platforms only)
