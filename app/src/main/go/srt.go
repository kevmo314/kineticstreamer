package kinetic

/*
#cgo android,arm64 CFLAGS: -I${SRCDIR}/../third_party/output/arm64-v8a/include
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/../third_party/output/arm64-v8a/lib -lsrt
#cgo android,arm CFLAGS: -I${SRCDIR}/../third_party/output/armeabi-v7a/include
#cgo android,arm LDFLAGS: -L${SRCDIR}/../third_party/output/armeabi-v7a/lib -lsrt
#cgo android,386 CFLAGS: -I${SRCDIR}/../third_party/output/x86/include
#cgo android,386 LDFLAGS: -L${SRCDIR}/../third_party/output/x86/lib -lsrt
#cgo android,amd64 CFLAGS: -I${SRCDIR}/../third_party/output/x86_64/include
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/../third_party/output/x86_64/lib -lsrt
#cgo linux,amd64 CFLAGS: -I${SRCDIR}/../third_party/output/x86_64/include

#include <stdint.h>
#include <srt/srt.h>

// Helper to get bandwidth from SRT stats
static double srt_get_bandwidth_mbps(SRTSOCKET sock) {
    SRT_TRACEBSTATS stats;
    if (srt_bstats(sock, &stats, 0) == 0) {
        return stats.mbpsBandwidth;
    }
    return 0.0;
}

// Helper to get send loss from SRT stats
static int srt_get_snd_loss(SRTSOCKET sock) {
    SRT_TRACEBSTATS stats;
    if (srt_bstats(sock, &stats, 0) == 0) {
        return stats.pktSndLoss;
    }
    return 0;
}
*/
import "C"
import (
	"bufio"
	"fmt"
	"log"
	"net"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

type socketOption struct {
	name     string
	level    int
	option   int
	binding  int
	dataType int
}

const (
	bindingPre  = 0
	bindingPost = 1
)

const (
	tInteger32 = 0
	tInteger64 = 1
	tString    = 2
	tBoolean   = 3
	tTransType = 4
)

// List of possible srt socket options
var SocketOptions = []socketOption{
	{"transtype", 0, C.SRTO_TRANSTYPE, bindingPre, tTransType},
	{"maxbw", 0, C.SRTO_MAXBW, bindingPre, tInteger64},
	{"pbkeylen", 0, C.SRTO_PBKEYLEN, bindingPre, tInteger32},
	{"passphrase", 0, C.SRTO_PASSPHRASE, bindingPre, tString},
	{"mss", 0, C.SRTO_MSS, bindingPre, tInteger32},
	{"fc", 0, C.SRTO_FC, bindingPre, tInteger32},
	{"sndbuf", 0, C.SRTO_SNDBUF, bindingPre, tInteger32},
	{"rcvbuf", 0, C.SRTO_RCVBUF, bindingPre, tInteger32},
	{"ipttl", 0, C.SRTO_IPTTL, bindingPre, tInteger32},
	{"iptos", 0, C.SRTO_IPTOS, bindingPre, tInteger32},
	{"inputbw", 0, C.SRTO_INPUTBW, bindingPost, tInteger64},
	{"oheadbw", 0, C.SRTO_OHEADBW, bindingPost, tInteger32},
	{"latency", 0, C.SRTO_LATENCY, bindingPre, tInteger32},
	{"tsbpdmode", 0, C.SRTO_TSBPDMODE, bindingPre, tBoolean},
	{"tlpktdrop", 0, C.SRTO_TLPKTDROP, bindingPre, tBoolean},
	{"snddropdelay", 0, C.SRTO_SNDDROPDELAY, bindingPost, tInteger32},
	{"nakreport", 0, C.SRTO_NAKREPORT, bindingPre, tBoolean},
	{"conntimeo", 0, C.SRTO_CONNTIMEO, bindingPre, tInteger32},
	{"lossmaxttl", 0, C.SRTO_LOSSMAXTTL, bindingPre, tInteger32},
	{"rcvlatency", 0, C.SRTO_RCVLATENCY, bindingPre, tInteger32},
	{"peerlatency", 0, C.SRTO_PEERLATENCY, bindingPre, tInteger32},
	{"minversion", 0, C.SRTO_MINVERSION, bindingPre, tInteger32},
	{"streamid", 0, C.SRTO_STREAMID, bindingPre, tString},
	{"congestion", 0, C.SRTO_CONGESTION, bindingPre, tString},
	{"messageapi", 0, C.SRTO_MESSAGEAPI, bindingPre, tBoolean},
	{"payloadsize", 0, C.SRTO_PAYLOADSIZE, bindingPre, tInteger32},
	{"kmrefreshrate", 0, C.SRTO_KMREFRESHRATE, bindingPre, tInteger32},
	{"kmpreannounce", 0, C.SRTO_KMPREANNOUNCE, bindingPre, tInteger32},
	{"enforcedencryption", 0, C.SRTO_ENFORCEDENCRYPTION, bindingPre, tBoolean},
	{"peeridletimeo", 0, C.SRTO_PEERIDLETIMEO, bindingPre, tInteger32},
	{"packetfilter", 0, C.SRTO_PACKETFILTER, bindingPre, tString},
}

func setSocketOptions(s C.int, binding int, options map[string]string) error {
	for _, so := range SocketOptions {
		if val, ok := options[so.name]; ok {
			if so.binding == binding {
				if so.dataType == tInteger32 {
					v, err := strconv.Atoi(val)
					v32 := int32(v)
					if err == nil {
						result := C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(&v32), C.int32_t(unsafe.Sizeof(v32)))
						if result == -1 {
							return fmt.Errorf("warning - error setting option %s to %s, %w", so.name, val, srtGetAndClearError())
						}
					}
				} else if so.dataType == tInteger64 {
					v, err := strconv.ParseInt(val, 10, 64)
					if err == nil {
						result := C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(&v), C.int32_t(unsafe.Sizeof(v)))
						if result == -1 {
							return fmt.Errorf("warning - error setting option %s to %s, %w", so.name, val, srtGetAndClearError())
						}
					}
				} else if so.dataType == tString {
					sval := C.CString(val)
					defer C.free(unsafe.Pointer(sval))
					result := C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(sval), C.int32_t(len(val)))
					if result == -1 {
						return fmt.Errorf("warning - error setting option %s to %s, %w", so.name, val, srtGetAndClearError())
					}

				} else if so.dataType == tBoolean {
					var result C.int
					if val == "1" {
						v := C.char(1)
						result = C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(&v), C.int32_t(unsafe.Sizeof(v)))
					} else if val == "0" {
						v := C.char(0)
						result = C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(&v), C.int32_t(unsafe.Sizeof(v)))
					}
					if result == -1 {
						return fmt.Errorf("warning - error setting option %s to %s, %w", so.name, val, srtGetAndClearError())
					}
				} else if so.dataType == tTransType {
					var result C.int
					if val == "live" {
						var v int32 = C.SRTT_LIVE
						result = C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(&v), C.int32_t(unsafe.Sizeof(v)))
					} else if val == "file" {
						var v int32 = C.SRTT_FILE
						result = C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(&v), C.int32_t(unsafe.Sizeof(v)))
					}
					if result == -1 {
						return fmt.Errorf("warning - error setting option %s to %s: %w", so.name, val, srtGetAndClearError())
					}
				}
			}
		}
	}
	return nil
}

func srtGetAndClearError() error {
	defer C.srt_clearlasterror()
	syserr := C.int(0)
	srterr := C.srt_getlasterror(&syserr)
	if syserr != 0 {
		return fmt.Errorf("srt error %d (syserr: %d)", srterr, syserr)
	}
	return fmt.Errorf("srt error %d", srterr)
}

type SRTSocket struct {
	fd          C.int
	payloadSize int
}

func (s SRTSocket) Write(p []byte) (int, error) {
	totalWritten := 0
	for i := 0; i < len(p); i += s.payloadSize {
		size := s.payloadSize
		if i+size > len(p) {
			size = len(p) - i
		}
		n := C.srt_send(s.fd, (*C.char)(unsafe.Pointer(&p[i])), C.int(size))
		if n == -1 {
			err := srtGetAndClearError()
			log.Printf("SRT: srt_send failed: %v (wrote %d of %d bytes so far)", err, totalWritten, len(p))
			return totalWritten, fmt.Errorf("srt_send failed: %w", err)
		}
		if int(n) != size {
			log.Printf("SRT: srt_send partial write: wrote %d of %d bytes", n, size)
			return totalWritten + int(n), fmt.Errorf("srt_send partial write: %d of %d", n, size)
		}
		totalWritten += int(n)
	}
	return totalWritten, nil
}

// PLICallback is called when a keyframe should be requested due to packet loss
type SRTPLICallback interface {
	OnPLI()
}

type SRTSink struct {
	sync.Mutex

	mpw         *mpegts.Writer
	bw          *bufio.Writer
	sck         SRTSocket
	tracks      []*mpegts.Track
	pliCallback SRTPLICallback

	// For tracking packet loss
	lastPktSndLoss    int64
	lastLossCheckTime time.Time
}

var sinkCount int

func sockAddrFromIp4(ip net.IP, port uint16) (*C.struct_sockaddr, int, error) {
	var raw syscall.RawSockaddrInet4
	raw.Family = syscall.AF_INET

	p := (*[2]byte)(unsafe.Pointer(&raw.Port))
	p[0] = byte(port >> 8)
	p[1] = byte(port)

	copy(raw.Addr[:], ip.To4())

	return (*C.struct_sockaddr)(unsafe.Pointer(&raw)), int(syscall.SizeofSockaddrInet4), nil
}

func sockAddrFromIp6(ip net.IP, port uint16) (*C.struct_sockaddr, int, error) {
	var raw syscall.RawSockaddrInet6
	raw.Family = syscall.AF_INET6

	p := (*[2]byte)(unsafe.Pointer(&raw.Port))
	p[0] = byte(port >> 8)
	p[1] = byte(port)

	copy(raw.Addr[:], ip.To16())

	return (*C.struct_sockaddr)(unsafe.Pointer(&raw)), int(syscall.SizeofSockaddrInet6), nil
}

func sockAddrFromIp(ip net.IP, port uint16) (*C.struct_sockaddr, int, error) {
	if ip.To4() != nil {
		return sockAddrFromIp4(ip, port)
	} else if ip.To16() != nil {
		return sockAddrFromIp6(ip, port)
	}
	return nil, 0, fmt.Errorf("unknown address family")
}

func NewSRTSink(s, encodedMediaFormatMimeTypes string) (*SRTSink, error) {
	log.Printf("SRT: creating sink for %s with mimeTypes %s", s, encodedMediaFormatMimeTypes)

	parsed, err := url.Parse(s)
	if err != nil {
		log.Printf("SRT: failed to parse URL: %v", err)
		return nil, err
	}

	ip := net.ParseIP(parsed.Hostname())
	if ip == nil {
		ips, err := net.LookupIP(parsed.Hostname())
		if err != nil {
			log.Printf("SRT: failed to resolve hostname %s: %v", parsed.Hostname(), err)
			return nil, fmt.Errorf("failed to resolve hostname: %w", err)
		}
		ip = ips[0]
		log.Printf("SRT: resolved %s to %s", parsed.Hostname(), ip)
	}

	iport, err := strconv.Atoi(parsed.Port())
	if err != nil {
		log.Printf("SRT: failed to parse port: %v", err)
		return nil, err
	}

	sa, salen, err := sockAddrFromIp(ip, uint16(iport))
	if err != nil {
		log.Printf("SRT: failed to create sockaddr: %v", err)
		return nil, err
	}

	options := map[string]string{}
	for _, v := range parsed.Query() {
		options[v[0]] = v[1]
	}

	if sinkCount == 0 {
		C.srt_startup()
		log.Printf("SRT: library initialized")
	}
	sinkCount++
	fd := C.srt_create_socket()
	log.Printf("SRT: created socket fd=%d", fd)

	if err := setSocketOptions(fd, bindingPre, options); err != nil {
		log.Printf("SRT: failed to set pre-bind options: %v", err)
		return nil, err
	}

	log.Printf("SRT: connecting to %s:%d...", ip, iport)
	if res := C.srt_connect(fd, sa, C.int(salen)); res == -1 {
		err := srtGetAndClearError()
		log.Printf("SRT: connect failed: %v", err)
		C.srt_close(fd)
		return nil, fmt.Errorf("srt_connect: %w", err)
	}
	log.Printf("SRT: connected successfully")

	if err := setSocketOptions(fd, bindingPost, options); err != nil {
		log.Printf("SRT: failed to set post-bind options: %v", err)
		return nil, err
	}

	var payloadSize C.int
	if res, err := C.srt_getsockflag(fd, C.SRTO_PAYLOADSIZE, unsafe.Pointer(&payloadSize), (*C.int)(unsafe.Pointer(&payloadSize))); res == -1 {
		return nil, fmt.Errorf("failed to get socket option: %w", err)
	}
	log.Printf("SRT: payload size = %d", payloadSize)

	sck := SRTSocket{fd: fd, payloadSize: int(payloadSize)}

	tracks := []*mpegts.Track{}
	for _, v := range strings.Split(encodedMediaFormatMimeTypes, ";") {
		codec := MediaFormatMimeType(v).MPEGTSCodec()
		log.Printf("SRT: track %d: mimeType=%s codec=%T", len(tracks), v, codec)
		tracks = append(tracks, &mpegts.Track{
			Codec: codec,
		})
	}

	bw := bufio.NewWriterSize(sck, int(payloadSize))
	log.Printf("SRT: sink created with %d tracks", len(tracks))
	return &SRTSink{mpw: mpegts.NewWriter(bw, tracks), bw: bw, sck: sck, tracks: tracks}, nil
}



// splitNALUs splits an Annex B byte stream into individual NAL units.
// It handles both 3-byte (0x00 0x00 0x01) and 4-byte (0x00 0x00 0x00 0x01) start codes.
func splitNALUs(buf []byte) [][]byte {
	var nalus [][]byte
	start := -1

	for i := 0; i < len(buf); i++ {
		// Check for 4-byte start code
		if i+3 < len(buf) && buf[i] == 0x00 && buf[i+1] == 0x00 && buf[i+2] == 0x00 && buf[i+3] == 0x01 {
			if start >= 0 {
				nalus = append(nalus, buf[start:i])
			}
			start = i + 4
			i += 3
			continue
		}
		// Check for 3-byte start code
		if i+2 < len(buf) && buf[i] == 0x00 && buf[i+1] == 0x00 && buf[i+2] == 0x01 {
			if start >= 0 {
				nalus = append(nalus, buf[start:i])
			}
			start = i + 3
			i += 2
			continue
		}
	}

	// Append the last NAL unit
	if start >= 0 && start < len(buf) {
		nalus = append(nalus, buf[start:])
	}

	return nalus
}

func (s *SRTSink) WriteSample(i int, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	s.Lock()
	defer s.Unlock()

	if i >= len(s.tracks) {
		log.Printf("SRT: invalid track index %d (have %d tracks)", i, len(s.tracks))
		return fmt.Errorf("invalid track index %d", i)
	}

	t := s.tracks[i]
	if t.Codec == nil {
		log.Printf("SRT: track %d has nil codec", i)
		return fmt.Errorf("track %d has nil codec", i)
	}

	isKeyframe := MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagKeyFrame != 0

	pts := int64((time.Duration(ptsMicroseconds) * time.Microsecond).Seconds() * 90000)

	var err error
	switch t.Codec.(type) {
	case *mpegts.CodecH264, *mpegts.CodecH265:
		nalus := splitNALUs(buf)
		if len(nalus) == 0 {
			log.Printf("SRT: no NALUs found in %d byte buffer", len(buf))
			return nil
		}
		err = s.mpw.WriteH26x(t, pts, pts, isKeyframe, nalus)
		if err != nil {
			log.Printf("SRT: WriteH26x error: %v", err)
		}
	case *mpegts.CodecOpus:
		err = s.mpw.WriteOpus(t, pts, [][]byte{buf})
		if err != nil {
			log.Printf("SRT: WriteOpus error: %v", err)
		}
	case *mpegts.CodecMPEG4Audio:
		err = s.mpw.WriteMPEG4Audio(t, pts, [][]byte{buf})
		if err != nil {
			log.Printf("SRT: WriteMPEG4Audio error: %v", err)
		}
	default:
		log.Printf("SRT: unknown codec type for track %d: %T", i, t.Codec)
		return nil
	}

	if err != nil {
		return err
	}

	// Flush the buffer to ensure data is sent over the network
	buffered := s.bw.Buffered()
	if err := s.bw.Flush(); err != nil {
		log.Printf("SRT: flush error: %v (had %d bytes buffered)", err, buffered)
		return err
	}

	return nil
}

func (s *SRTSink) Close() error {
	C.srt_close(s.sck.fd)
	sinkCount--
	if sinkCount == 0 {
		C.srt_cleanup()
	}
	return nil
}

func (s *SRTSink) SetPLICallback(callback SRTPLICallback) {
	s.Lock()
	defer s.Unlock()
	s.pliCallback = callback
}

// GetStatsAndBandwidth returns the estimated bandwidth in bits per second
// and triggers a PLI callback if packet loss is detected
func (s *SRTSink) GetStatsAndBandwidth() int64 {
	s.Lock()
	defer s.Unlock()

	// Get bandwidth from SRT stats (in Mb/s)
	bandwidthMbps := float64(C.srt_get_bandwidth_mbps(s.sck.fd))

	// Convert Mb/s to bits/sec, cap at 7.5 Mbps max
	bandwidthBps := int64(bandwidthMbps * 1000000)
	const maxBandwidthBps = 7500000 // 7.5 Mbps cap
	if bandwidthBps > maxBandwidthBps {
		bandwidthBps = maxBandwidthBps
	}

	// Check for packet loss and trigger PLI if needed
	currentLoss := int64(C.srt_get_snd_loss(s.sck.fd))
	now := time.Now()

	// Trigger PLI if we see new packet loss (check at most once per second)
	if currentLoss > s.lastPktSndLoss && now.Sub(s.lastLossCheckTime) > time.Second {
		if s.pliCallback != nil {
			log.Printf("SRT: packet loss detected (%d -> %d), requesting keyframe", s.lastPktSndLoss, currentLoss)
			go s.pliCallback.OnPLI() // Call asynchronously to avoid deadlock
		}
		s.lastPktSndLoss = currentLoss
		s.lastLossCheckTime = now
	}

	return bandwidthBps
}
