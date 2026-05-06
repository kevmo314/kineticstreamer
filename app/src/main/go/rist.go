//go:build (android || darwin || linux) && cgo

package kinetic

/*
#cgo android,arm64 CFLAGS: -I${SRCDIR}/../third_party/output/arm64-v8a/include
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/../third_party/output/arm64-v8a/lib -lrist
#cgo android,arm CFLAGS: -I${SRCDIR}/../third_party/output/armeabi-v7a/include
#cgo android,arm LDFLAGS: -L${SRCDIR}/../third_party/output/armeabi-v7a/lib -lrist
#cgo android,386 CFLAGS: -I${SRCDIR}/../third_party/output/x86/include
#cgo android,386 LDFLAGS: -L${SRCDIR}/../third_party/output/x86/lib -lrist
#cgo android,amd64 CFLAGS: -I${SRCDIR}/../third_party/output/x86_64/include
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/../third_party/output/x86_64/lib -lrist
// Host build: use the system-installed librist (`brew install librist` on
// macOS, `apt install librist-dev` on Linux). Used by rist_test.go.
#cgo darwin,arm64,!ios CFLAGS: -I/opt/homebrew/opt/librist/include
#cgo darwin,arm64,!ios LDFLAGS: -L/opt/homebrew/opt/librist/lib -lrist
#cgo darwin,amd64,!ios CFLAGS: -I/usr/local/opt/librist/include
#cgo darwin,amd64,!ios LDFLAGS: -L/usr/local/opt/librist/lib -lrist
#cgo linux,!android LDFLAGS: -lrist

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <librist/librist.h>

// Send the given payload as one RIST data block. Returns 0 on success, the
// negative librist error code otherwise.
static int rist_sink_write(struct rist_ctx* ctx, const void* buf, size_t len) {
    struct rist_data_block block = {0};
    block.payload = buf;
    block.payload_len = len;
    return rist_sender_data_write(ctx, &block);
}
*/
import "C"
import (
	"bufio"
	"fmt"
	"log"
	"net/url"
	"strings"
	"sync"
	"time"
	"unsafe"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

// ristProfileFromString maps the URL ?profile= query parameter to
// the librist enum. Defaults to MAIN (the most widely deployed profile).
func ristProfileFromString(s string) C.enum_rist_profile {
	switch strings.ToLower(s) {
	case "simple":
		return C.RIST_PROFILE_SIMPLE
	case "advanced":
		return C.RIST_PROFILE_ADVANCED
	default:
		return C.RIST_PROFILE_MAIN
	}
}

// ristCtxWriter adapts a librist sender context to io.Writer. Each Write is
// chunked into MPEGTS_PACKET_SIZE-aligned blocks (the muxer's natural unit).
type ristCtxWriter struct {
	ctx       *C.struct_rist_ctx
	chunkSize int
}

const ristMPEGTSChunk = 188 * 7 // 7 TS packets per UDP datagram, aka 1316 bytes

func (w *ristCtxWriter) Write(p []byte) (int, error) {
	written := 0
	for i := 0; i < len(p); i += w.chunkSize {
		end := i + w.chunkSize
		if end > len(p) {
			end = len(p)
		}
		chunk := p[i:end]
		ret := C.rist_sink_write(w.ctx,
			unsafe.Pointer(&chunk[0]), C.size_t(len(chunk)))
		if ret < 0 {
			return written, fmt.Errorf("rist_sender_data_write failed: %d", int(ret))
		}
		written += len(chunk)
	}
	return written, nil
}

// RISTSink mirrors SRTSink's shape: an MPEG-TS muxer feeding a buffered
// writer that fans out into the RIST sender.
type RISTSink struct {
	sync.Mutex

	ctx     *C.struct_rist_ctx
	logging *C.struct_rist_logging_settings

	mpw    *mpegts.Writer
	bw     *bufio.Writer
	tracks []*mpegts.Track
	closed bool
}

var ristSinkCount int
var ristSinkMu sync.Mutex

// NewRISTSink builds a sender for `rist://host:port` (and variants thereof).
// The URL is forwarded verbatim to librist's parser, so any query parameters
// supported by `rist_parse_address2` (buffer=, bandwidth=, cname=, ...) are
// honoured. The mimeTypes string is the same `;`-separated codec list used
// by SRTSink.
func NewRISTSink(rawURL, encodedMediaFormatMimeTypes string) (*RISTSink, error) {
	log.Printf("RIST: creating sink for %s with mimeTypes %s", rawURL, encodedMediaFormatMimeTypes)

	parsed, err := url.Parse(rawURL)
	if err != nil {
		return nil, fmt.Errorf("RIST: failed to parse URL: %w", err)
	}
	if parsed.Scheme != "rist" {
		return nil, fmt.Errorf("RIST: expected rist:// scheme, got %q", parsed.Scheme)
	}

	tracks := []*mpegts.Track{}
	for _, v := range strings.Split(encodedMediaFormatMimeTypes, ";") {
		codec := MediaFormatMimeType(v).MPEGTSCodec()
		tracks = append(tracks, &mpegts.Track{Codec: codec})
	}

	q := parsed.Query()
	profile := ristProfileFromString(q.Get("profile"))
	// `profile=` is our own URL extension; librist's parser doesn't know it
	// and would reject the URL outright. Strip it before handing the URL to
	// rist_parse_address2.
	q.Del("profile")
	parsed.RawQuery = q.Encode()
	cURL := C.CString(parsed.String())
	defer C.free(unsafe.Pointer(cURL))

	var peerCfg *C.struct_rist_peer_config
	if rc := C.rist_parse_address2(cURL, &peerCfg); rc != 0 || peerCfg == nil {
		return nil, fmt.Errorf("RIST: rist_parse_address2 failed: %d", int(rc))
	}
	defer C.rist_peer_config_free2(&peerCfg)

	// Logging — librist will reject ctx creation if logging_settings is nil.
	var logging *C.struct_rist_logging_settings
	if rc := C.rist_logging_set(&logging, C.RIST_LOG_WARN, nil, nil, nil, nil); rc != 0 {
		return nil, fmt.Errorf("RIST: rist_logging_set failed: %d", int(rc))
	}

	var ctx *C.struct_rist_ctx
	if rc := C.rist_sender_create(&ctx, profile, 0, logging); rc != 0 {
		C.rist_logging_settings_free2(&logging)
		return nil, fmt.Errorf("RIST: rist_sender_create failed: %d", int(rc))
	}

	var peer *C.struct_rist_peer
	if rc := C.rist_peer_create(ctx, &peer, peerCfg); rc != 0 {
		C.rist_destroy(ctx)
		C.rist_logging_settings_free2(&logging)
		return nil, fmt.Errorf("RIST: rist_peer_create failed: %d", int(rc))
	}

	if rc := C.rist_start(ctx); rc != 0 {
		C.rist_destroy(ctx)
		C.rist_logging_settings_free2(&logging)
		return nil, fmt.Errorf("RIST: rist_start failed: %d", int(rc))
	}

	w := &ristCtxWriter{ctx: ctx, chunkSize: ristMPEGTSChunk}
	bw := bufio.NewWriterSize(w, ristMPEGTSChunk)

	sink := &RISTSink{
		ctx:     ctx,
		logging: logging,
		tracks:  tracks,
		bw:      bw,
		mpw:     mpegts.NewWriter(bw, tracks),
	}

	ristSinkMu.Lock()
	ristSinkCount++
	ristSinkMu.Unlock()

	log.Printf("RIST: sink created with %d tracks", len(tracks))
	return sink, nil
}

// WriteSample mirrors SRTSink.WriteSample: stream index 0 is video, 1+ is
// audio, and the data is MPEG-TS-muxed before being handed to librist.
func (s *RISTSink) WriteSample(i int, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	s.Lock()
	defer s.Unlock()

	if s.closed {
		return fmt.Errorf("RIST: sink closed")
	}
	if i >= len(s.tracks) {
		return fmt.Errorf("RIST: invalid track index %d", i)
	}

	t := s.tracks[i]
	if t.Codec == nil {
		return fmt.Errorf("RIST: track %d has nil codec", i)
	}

	isKeyframe := MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagKeyFrame != 0
	pts := int64((time.Duration(ptsMicroseconds) * time.Microsecond).Seconds() * 90000)

	var err error
	switch t.Codec.(type) {
	case *mpegts.CodecH264, *mpegts.CodecH265:
		nalus := splitNALUs(buf)
		if len(nalus) == 0 {
			return nil
		}
		err = s.mpw.WriteH26x(t, pts, pts, isKeyframe, nalus)
	case *mpegts.CodecOpus:
		err = s.mpw.WriteOpus(t, pts, [][]byte{buf})
	case *mpegts.CodecMPEG4Audio:
		err = s.mpw.WriteMPEG4Audio(t, pts, [][]byte{buf})
	default:
		return nil
	}
	if err != nil {
		return err
	}
	return s.bw.Flush()
}

// Close tears down the librist context and frees its logging settings.
func (s *RISTSink) Close() error {
	s.Lock()
	defer s.Unlock()
	if s.closed {
		return nil
	}
	s.closed = true

	if s.ctx != nil {
		C.rist_destroy(s.ctx)
		s.ctx = nil
	}
	if s.logging != nil {
		C.rist_logging_settings_free2(&s.logging)
		s.logging = nil
	}

	ristSinkMu.Lock()
	ristSinkCount--
	ristSinkMu.Unlock()
	return nil
}
