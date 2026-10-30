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
	rtmpServers kinetic.Handles[*kinetic.RTMPServer]
	rtmpSources kinetic.Handles[*kinetic.RTMPSource]
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

	return rtmpServers.Add(server)
}

//export GoRTMPServerStart
func GoRTMPServerStart(handle int64) int32 {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoRTMPServerStart: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()

	server, ok := rtmpServers.Get(handle)

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
	// Stop can block on the network; never do it under the global lock.
	server, ok := rtmpServers.Remove(handle)
	if ok {
		server.Stop()
	}
}

//export GoRTMPServerGetPort
func GoRTMPServerGetPort(handle int64) int32 {
	server, ok := rtmpServers.Get(handle)

	if !ok {
		return 0
	}

	return int32(server.Port())
}

//export GoRTMPServerGetSource
func GoRTMPServerGetSource(handle int64) int64 {
	server, ok := rtmpServers.Get(handle)

	if !ok {
		return 0
	}

	source := server.GetSource()
	if source == nil {
		return 0
	}

	return rtmpSources.FindOrAdd(source, func(s *kinetic.RTMPSource) bool { return s == source })
}

//export GoRTMPServerWaitForSource
func GoRTMPServerWaitForSource(handle int64, timeoutMs int32) int64 {
	server, ok := rtmpServers.Get(handle)

	if !ok {
		return 0
	}

	source := server.WaitForSource(time.Duration(timeoutMs) * time.Millisecond)
	if source == nil {
		return 0
	}

	return rtmpSources.FindOrAdd(source, func(s *kinetic.RTMPSource) bool { return s == source })
}

//export GoRTMPSourceReadVideoFrame
func GoRTMPSourceReadVideoFrame(handle int64, dataPtr *unsafe.Pointer, sizePtr *int32) int32 {
	defer func() {
		if r := recover(); r != nil {
			log.Printf("PANIC in GoRTMPSourceReadVideoFrame: %v\nStack trace:\n%s", r, debug.Stack())
		}
	}()

	source, ok := rtmpSources.Get(handle)

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

	source, ok := rtmpSources.Get(handle)

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
	source, ok := rtmpSources.Get(handle)

	if !ok {
		return 0
	}

	return source.GetVideoPTS()
}

//export GoRTMPSourceGetAudioPTS
func GoRTMPSourceGetAudioPTS(handle int64) int64 {
	source, ok := rtmpSources.Get(handle)

	if !ok {
		return 0
	}

	return source.GetAudioPTS()
}

//export GoRTMPSourceIsClosed
func GoRTMPSourceIsClosed(handle int64) int32 {
	source, ok := rtmpSources.Get(handle)

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
	source, ok := rtmpSources.Remove(handle)
	if ok {
		source.Close()
	}
}
