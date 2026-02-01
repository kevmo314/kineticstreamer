//go:build amd64 || arm64

package main

// #include <stdint.h>
// #include <stdlib.h>
import "C"
import (
	"log"
	"runtime/debug"
	"time"
	"unsafe"

	"github.com/kevmo314/kinetic"
)

// RTMP-specific storage (only on 64-bit platforms)
var (
	rtmpServers = make(map[int64]*kinetic.RTMPServer)
	rtmpSources = make(map[int64]*kinetic.RTMPSource)
)

// RTMP Server exports

//export GoCreateRTMPServer
func GoCreateRTMPServer(port int32) (handle int64) {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoCreateRTMPServer: %v\nStack trace:\n%s", r, debug.Stack())
			handle = 0
		}
	}()

	server := kinetic.NewRTMPServer(int(port))

	mu.Lock()
	handle = nextHandle
	nextHandle++
	rtmpServers[handle] = server
	mu.Unlock()

	return handle
}

//export GoRTMPServerStart
func GoRTMPServerStart(handle int64) int32 {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoRTMPServerStart: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()

	mu.RLock()
	server, ok := rtmpServers[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	if err := server.Start(); err != nil {
		log.Printf("RTMP server start error: %v", err)
		return 0
	}

	return 1
}

//export GoRTMPServerStop
func GoRTMPServerStop(handle int64) {
	mu.Lock()
	server, ok := rtmpServers[handle]
	if ok {
		server.Stop()
		delete(rtmpServers, handle)
	}
	mu.Unlock()
}

//export GoRTMPServerGetPort
func GoRTMPServerGetPort(handle int64) int32 {
	mu.RLock()
	server, ok := rtmpServers[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	return int32(server.Port())
}

//export GoRTMPServerGetSource
func GoRTMPServerGetSource(handle int64) int64 {
	mu.RLock()
	server, ok := rtmpServers[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	source := server.GetSource()
	if source == nil {
		return 0
	}

	// Check if we already have a handle for this source
	mu.Lock()
	defer mu.Unlock()

	for h, s := range rtmpSources {
		if s == source {
			return h
		}
	}

	// Create new handle for the source
	sourceHandle := nextHandle
	nextHandle++
	rtmpSources[sourceHandle] = source

	return sourceHandle
}

//export GoRTMPServerWaitForSource
func GoRTMPServerWaitForSource(handle int64, timeoutMs int32) int64 {
	mu.RLock()
	server, ok := rtmpServers[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	source := server.WaitForSource(time.Duration(timeoutMs) * time.Millisecond)
	if source == nil {
		return 0
	}

	mu.Lock()
	sourceHandle := nextHandle
	nextHandle++
	rtmpSources[sourceHandle] = source
	mu.Unlock()

	return sourceHandle
}

//export GoRTMPSourceReadVideoFrame
func GoRTMPSourceReadVideoFrame(handle int64, dataPtr *unsafe.Pointer, sizePtr *int32) int32 {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoRTMPSourceReadVideoFrame: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()

	mu.RLock()
	source, ok := rtmpSources[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	frame := source.ReadVideoFrame()
	if frame == nil {
		return 0
	}

	*dataPtr = C.CBytes(frame.Data)
	*sizePtr = int32(len(frame.Data))

	return 1
}

//export GoRTMPSourceReadAudioFrame
func GoRTMPSourceReadAudioFrame(handle int64, dataPtr *unsafe.Pointer, sizePtr *int32) int32 {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoRTMPSourceReadAudioFrame: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()

	mu.RLock()
	source, ok := rtmpSources[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	frame := source.ReadAudioFrame()
	if frame == nil {
		return 0
	}

	*dataPtr = C.CBytes(frame.Data)
	*sizePtr = int32(len(frame.Data))

	return 1
}

//export GoRTMPSourceGetVideoPTS
func GoRTMPSourceGetVideoPTS(handle int64) int64 {
	mu.RLock()
	source, ok := rtmpSources[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	return source.GetVideoPTS()
}

//export GoRTMPSourceGetAudioPTS
func GoRTMPSourceGetAudioPTS(handle int64) int64 {
	mu.RLock()
	source, ok := rtmpSources[handle]
	mu.RUnlock()

	if !ok {
		return 0
	}

	return source.GetAudioPTS()
}

//export GoRTMPSourceIsClosed
func GoRTMPSourceIsClosed(handle int64) int32 {
	mu.RLock()
	source, ok := rtmpSources[handle]
	mu.RUnlock()

	if !ok {
		return 1 // Treat missing as closed
	}

	if source.IsClosed() {
		return 1
	}
	return 0
}

//export GoRTMPSourceClose
func GoRTMPSourceClose(handle int64) {
	mu.Lock()
	source, ok := rtmpSources[handle]
	if ok {
		source.Close()
		delete(rtmpSources, handle)
	}
	mu.Unlock()
}
