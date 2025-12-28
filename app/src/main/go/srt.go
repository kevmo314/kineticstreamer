package kinetic

/*
#cgo android,arm64 CFLAGS: -I${SRCDIR}/third_party/srt/scripts/build-android/arm64-v8a/include
#cgo android,arm64 LDFLAGS: -L${SRCDIR}/third_party/srt/scripts/build-android/arm64-v8a/lib -lsrt
#cgo android,arm CFLAGS: -I${SRCDIR}/third_party/srt/scripts/build-android/armeabi-v7a/include
#cgo android,arm LDFLAGS: -L${SRCDIR}/third_party/srt/scripts/build-android/armeabi-v7a/lib -lsrt
#cgo android,386 CFLAGS: -I${SRCDIR}/third_party/srt/scripts/build-android/x86/include
#cgo android,386 LDFLAGS: -L${SRCDIR}/third_party/srt/scripts/build-android/x86/lib -lsrt
#cgo android,amd64 CFLAGS: -I${SRCDIR}/third_party/srt/scripts/build-android/x86_64/include
#cgo android,amd64 LDFLAGS: -L${SRCDIR}/third_party/srt/scripts/build-android/x86_64/lib -lsrt

#include <srt/srt.h>
*/
import "C"
import (
	"fmt"
	"net"
	"net/url"
	"strconv"
	"strings"
	"syscall"
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

type SRTSocket C.int

func (s SRTSocket) Write(buf []byte) (int, error) {
	n := int(C.srt_sendmsg2(C.int(s), (*C.char)(unsafe.Pointer(&buf[0])), C.int(len(buf)), nil))
	if n < 0 {
		return 0, fmt.Errorf("srt_sendmsg2: %w", srtGetAndClearError())
	}
	return n, nil
}

type SRTSink struct {
	mpw    *mpegts.Writer
	sck    SRTSocket
	tracks []*mpegts.Track
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
	parsed, err := url.Parse(s)
	if err != nil {
		return nil, err
	}

	ip := net.ParseIP(parsed.Hostname())
	if ip == nil {
		ips, err := net.LookupIP(parsed.Hostname())
		if err != nil {
			return nil, fmt.Errorf("failed to resolve hostname: %w", err)
		}
		ip = ips[0]
	}

	iport, err := strconv.Atoi(parsed.Port())
	if err != nil {
		return nil, err
	}

	sa, salen, err := sockAddrFromIp(ip, uint16(iport))
	if err != nil {
		return nil, err
	}

	options := map[string]string{}
	for _, v := range parsed.Query() {
		options[v[0]] = v[1]
	}

	tracks := []*mpegts.Track{}
	for i, v := range strings.Split(encodedMediaFormatMimeTypes, ";") {
		tracks = append(tracks, &mpegts.Track{
			PID:   uint16(i),
			Codec: MediaFormatMimeType(v).MPEGTSCodec(),
		})
	}

	if sinkCount == 0 {
		C.srt_startup()
	}
	sinkCount++

	sck := SRTSocket(C.srt_create_socket())

	if err := setSocketOptions(C.int(sck), bindingPre, options); err != nil {
		return nil, err
	}

	if res := C.srt_connect(C.int(sck), sa, C.int(salen)); res == -1 {
		C.srt_close(C.int(sck))
		return nil, fmt.Errorf("srt_connect: %w", srtGetAndClearError())
	}

	if err := setSocketOptions(C.int(sck), bindingPost, options); err != nil {
		return nil, err
	}

	return &SRTSink{mpw: mpegts.NewWriter(sck, tracks), sck: sck, tracks: tracks}, nil
}

func (s *SRTSink) WriteSample(i int, buf []byte, ptsMicroseconds int64) (bool, error) {
	t := s.tracks[i]

	switch t.Codec.(type) {
	case *mpegts.CodecH264, *mpegts.CodecH265:
		return false, s.mpw.WriteH26x(t, ptsMicroseconds, ptsMicroseconds, false, [][]byte{buf})
	case *mpegts.CodecOpus:
		return false, s.mpw.WriteOpus(t, ptsMicroseconds, [][]byte{buf})
	case *mpegts.CodecMPEG4Audio:
		return false, s.mpw.WriteMPEG4Audio(t, ptsMicroseconds, [][]byte{buf})
	default:
		return false, nil
	}
}

func (s *SRTSink) Close() error {
	C.srt_close(C.int(s.sck))
	sinkCount--
	if sinkCount == 0 {
		C.srt_cleanup()
	}
	return nil
}
