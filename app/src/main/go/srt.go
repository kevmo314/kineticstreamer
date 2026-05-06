//go:build (android || darwin || linux) && cgo

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
// Host build: link against a system-installed libsrt (e.g. `brew install srt`
// on macOS or `apt install libsrt-dev` on Linux). Used by srt_test.go.
// Try Apple Silicon brew first, then Intel brew, then system /usr/local.
#cgo darwin,arm64,!ios CFLAGS: -I/opt/homebrew/opt/srt/include
#cgo darwin,arm64,!ios LDFLAGS: -L/opt/homebrew/opt/srt/lib -lsrt
#cgo darwin,amd64,!ios CFLAGS: -I/usr/local/opt/srt/include
#cgo darwin,amd64,!ios LDFLAGS: -L/usr/local/opt/srt/lib -lsrt
#cgo linux,!android LDFLAGS: -lsrt

#include <stdint.h>
#include <srt/srt.h>

// SRT stats for BBR-like bandwidth estimation
typedef struct {
    double msRTT;           // Round-trip time in ms
    int pktFlightSize;      // Packets in flight (unacknowledged)
    int pktSndLoss;         // Packets lost in interval
    int64_t pktSndLossTotal; // Total packets lost
    double mbpsSendRate;    // Current send rate in Mbps
    int pktSndBuf;          // Packets in send buffer
    double mbpsBandwidth;   // Estimated bandwidth from receiver
    int pktCongestionWindow; // Congestion window size
    int pktSndDrop;         // Packets dropped by sender (too late)
    int64_t pktSndDropTotal; // Total packets dropped
    int pktRetrans;         // Retransmitted packets in interval
    int64_t pktRetransTotal; // Total retransmitted packets
    int64_t pktSent;        // Total packets sent
    double mbpsMaxBW;       // Maximum bandwidth setting
} srt_bwe_stats_t;

// Helper to get all stats needed for bandwidth estimation
// Uses clear=1 to get interval-based stats (pktSndLoss resets each call)
static int srt_get_bwe_stats(SRTSOCKET sock, srt_bwe_stats_t* out) {
    SRT_TRACEBSTATS stats;
    if (srt_bstats(sock, &stats, 1) == 0) {
        out->msRTT = stats.msRTT;
        out->pktFlightSize = stats.pktFlightSize;
        out->pktSndLoss = stats.pktSndLoss;
        out->pktSndLossTotal = stats.pktSndLossTotal;
        out->mbpsSendRate = stats.mbpsSendRate;
        out->pktSndBuf = stats.pktSndBuf;
        out->mbpsBandwidth = stats.mbpsBandwidth;
        out->pktCongestionWindow = stats.pktCongestionWindow;
        out->pktSndDrop = stats.pktSndDrop;
        out->pktSndDropTotal = stats.pktSndDropTotal;
        out->pktRetrans = stats.pktRetrans;
        out->pktRetransTotal = stats.pktRetransTotal;
        out->pktSent = stats.pktSent;
        out->mbpsMaxBW = stats.mbpsMaxBW;
        return 0;
    }
    return -1;
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
			log.Printf("SRT: srt_send failed: %v (wrote %d of %d bytes so far)\n", err, totalWritten, len(p))
			return totalWritten, fmt.Errorf("srt_send failed: %w", err)
		}
		if int(n) != size {
			log.Printf("SRT: srt_send partial write: wrote %d of %d bytes\n", n, size)
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

// Simple AIMD-style bandwidth estimation constants
// Less aggressive than BBR to avoid congestion collapse on lossy links
const (
	minBitrateBps     = 1_500_000              // 1.5 Mbps minimum
	maxBitrateBps     = 7_500_000              // 7.5 Mbps maximum
	startBitrateBps   = 4_000_000              // 4 Mbps starting point
	bweIncreaseBps    = 200_000                // Additive increase: +200 Kbps per probe
	bweDecreaseFactor = 0.9                    // Multiplicative decrease: -10% on loss/congestion
	bweProbeInterval  = 500 * time.Millisecond // Probe every 500ms
	bweLossCooldown   = 2 * time.Second        // Wait 2s after loss before probing again
)

type SRTSink struct {
	sync.Mutex

	mpw         *mpegts.Writer
	bw          *bufio.Writer
	sck         SRTSocket
	tracks      []*mpegts.Track
	pliCallback SRTPLICallback
	closed      bool

	// Connection parameters for reconnect
	ip      net.IP
	port    uint16
	options map[string]string

	// AIMD bandwidth estimation state
	targetBitrate       int64     // Current target bitrate in bps
	lastProbeTime       time.Time // Last time we probed/updated
	lastLossTime        time.Time // Last time we saw packet loss
	lastPktSndLossTotal int64     // For detecting new packet loss
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
	log.Printf("SRT: creating sink for %s with mimeTypes %s\n", s, encodedMediaFormatMimeTypes)

	parsed, err := url.Parse(s)
	if err != nil {
		return nil, fmt.Errorf("SRT: failed to parse URL: %w", err)
	}

	ip := net.ParseIP(parsed.Hostname())
	if ip == nil {
		ips, err := net.LookupIP(parsed.Hostname())
		if err != nil {
			return nil, fmt.Errorf("SRT: failed to resolve hostname %s: %w", parsed.Hostname(), err)
		}
		ip = ips[0]
		log.Printf("SRT: resolved %s to %s\n", parsed.Hostname(), ip)
	}

	port, err := strconv.Atoi(parsed.Port())
	if err != nil {
		return nil, fmt.Errorf("SRT: failed to parse port: %w", err)
	}

	options := map[string]string{}
	for k, v := range parsed.Query() {
		if len(v) > 0 {
			options[k] = v[0]
		}
	}

	tracks := []*mpegts.Track{}
	for _, v := range strings.Split(encodedMediaFormatMimeTypes, ";") {
		codec := MediaFormatMimeType(v).MPEGTSCodec()
		tracks = append(tracks, &mpegts.Track{Codec: codec})
	}

	if sinkCount == 0 {
		C.srt_startup()
	}
	sinkCount++

	sink := &SRTSink{
		tracks:        tracks,
		ip:            ip,
		port:          uint16(port),
		options:       options,
		targetBitrate: startBitrateBps,
	}

	if err := sink.connect(); err != nil {
		sinkCount--
		if sinkCount == 0 {
			C.srt_cleanup()
		}
		return nil, err
	}

	log.Printf("SRT: sink created with %d tracks\n", len(tracks))
	return sink, nil
}

// connect creates a new SRT socket and connects. Caller must hold the lock or
// be in the constructor (no concurrent access yet).
func (s *SRTSink) connect() error {
	sa, salen, err := sockAddrFromIp(s.ip, s.port)
	if err != nil {
		return fmt.Errorf("SRT: failed to create sockaddr: %w", err)
	}

	fd := C.srt_create_socket()

	if err := setSocketOptions(fd, bindingPre, s.options); err != nil {
		C.srt_close(fd)
		return fmt.Errorf("SRT: failed to set pre-bind options: %w", err)
	}

	log.Printf("SRT: connecting to %s:%d...\n", s.ip, s.port)
	if res := C.srt_connect(fd, sa, C.int(salen)); res == -1 {
		err := srtGetAndClearError()
		C.srt_close(fd)
		return fmt.Errorf("SRT: connect failed: %w", err)
	}
	log.Printf("SRT: connected\n")

	if err := setSocketOptions(fd, bindingPost, s.options); err != nil {
		C.srt_close(fd)
		return fmt.Errorf("SRT: failed to set post-bind options: %w", err)
	}

	var payloadSize C.int
	if res, _ := C.srt_getsockflag(fd, C.SRTO_PAYLOADSIZE, unsafe.Pointer(&payloadSize), (*C.int)(unsafe.Pointer(&payloadSize))); res == -1 {
		C.srt_close(fd)
		return fmt.Errorf("SRT: failed to get payload size")
	}

	sck := SRTSocket{fd: fd, payloadSize: int(payloadSize)}
	bw := bufio.NewWriterSize(sck, int(payloadSize))

	s.sck = sck
	s.bw = bw
	s.mpw = mpegts.NewWriter(bw, s.tracks)
	return nil
}

// reconnect closes the old socket and tries to establish a new connection.
// Caller must hold the lock.
func (s *SRTSink) reconnect() error {
	C.srt_close(s.sck.fd)

	for attempt := 1; ; attempt++ {
		delay := time.Duration(attempt) * 500 * time.Millisecond
		if delay > 5*time.Second {
			delay = 5 * time.Second
		}
		log.Printf("SRT: reconnecting (attempt %d) in %v...\n", attempt, delay)

		// Unlock while sleeping so Close() can proceed
		s.Unlock()
		time.Sleep(delay)
		s.Lock()

		if s.closed {
			return fmt.Errorf("SRT: sink closed during reconnect")
		}

		if err := s.connect(); err != nil {
			log.Printf("SRT: reconnect attempt %d failed: %v\n", attempt, err)
			continue
		}

		log.Printf("SRT: reconnected\n")
		return nil
	}
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

	if s.closed {
		return fmt.Errorf("SRT: sink closed")
	}

	if i >= len(s.tracks) {
		return fmt.Errorf("SRT: invalid track index %d", i)
	}

	t := s.tracks[i]
	if t.Codec == nil {
		return fmt.Errorf("SRT: track %d has nil codec", i)
	}

	if err := s.writeSampleLocked(t, buf, ptsMicroseconds, mediaCodecFlags); err != nil {
		log.Printf("SRT: write failed: %v, reconnecting...\n", err)
		if reconnErr := s.reconnect(); reconnErr != nil {
			return reconnErr
		}
		// Drop this sample - the mpegts writer state was reset on reconnect
		return nil
	}
	return nil
}

func (s *SRTSink) writeSampleLocked(t *mpegts.Track, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
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

	if err := s.bw.Flush(); err != nil {
		return err
	}
	return nil
}

func (s *SRTSink) Close() error {
	s.Lock()
	defer s.Unlock()
	s.closed = true
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

// GetStatsAndBandwidth returns the estimated target bandwidth in bits per second
// using a simple AIMD algorithm: additive increase, multiplicative decrease.
// Also triggers PLI callback on packet loss.
func (s *SRTSink) GetStatsAndBandwidth() int64 {
	s.Lock()
	defer s.Unlock()

	now := time.Now()

	// Rate limit probes
	if !s.lastProbeTime.IsZero() && now.Sub(s.lastProbeTime) < bweProbeInterval {
		return s.targetBitrate
	}
	s.lastProbeTime = now

	// Get SRT stats
	var stats C.srt_bwe_stats_t
	if C.srt_get_bwe_stats(s.sck.fd, &stats) != 0 {
		return s.targetBitrate
	}

	lossTotal := int64(stats.pktSndLossTotal)
	lossInterval := int(stats.pktSndLoss)
	rtt := float64(stats.msRTT)
	sendRateMbps := float64(stats.mbpsSendRate)
	flightSize := int(stats.pktFlightSize)
	sndBuf := int(stats.pktSndBuf)
	estBwMbps := float64(stats.mbpsBandwidth)
	cwnd := int(stats.pktCongestionWindow)
	drops := int(stats.pktSndDrop)
	dropsTotal := int64(stats.pktSndDropTotal)
	retrans := int(stats.pktRetrans)
	retransTotal := int64(stats.pktRetransTotal)

	// Always log stats for debugging
	log.Printf("SRT stats: RTT=%.0fms rate=%.2fMbps estBW=%.2fMbps cwnd=%d flight=%d sndbuf=%d",
		rtt, sendRateMbps, estBwMbps, cwnd, flightSize, sndBuf)
	log.Printf("SRT stats: loss=%d/%d retrans=%d/%d drops=%d/%d target=%dKbps",
		lossInterval, lossTotal, retrans, retransTotal, drops, dropsTotal, s.targetBitrate/1000)

	// Check for packet loss
	if lossTotal > s.lastPktSndLossTotal {
		lostPackets := lossTotal - s.lastPktSndLossTotal
		s.lastPktSndLossTotal = lossTotal
		s.lastLossTime = now

		// Trigger PLI for keyframe
		if s.pliCallback != nil {
			go s.pliCallback.OnPLI()
		}

		// Multiplicative decrease on loss
		oldBitrate := s.targetBitrate
		s.targetBitrate = int64(float64(s.targetBitrate) * bweDecreaseFactor)
		if s.targetBitrate < minBitrateBps {
			s.targetBitrate = minBitrateBps
		}

		log.Printf("SRT BWE: LOSS (%d pkts) -> %d Kbps (was %d Kbps)",
			lostPackets, s.targetBitrate/1000, oldBitrate/1000)
		return s.targetBitrate
	}
	s.lastPktSndLossTotal = lossTotal

	// Don't probe up during cooldown after loss
	if !s.lastLossTime.IsZero() && now.Sub(s.lastLossTime) < bweLossCooldown {
		log.Printf("SRT BWE: in cooldown (%.1fs since loss), holding at %d Kbps",
			now.Sub(s.lastLossTime).Seconds(), s.targetBitrate/1000)
		return s.targetBitrate
	}

	// Additive increase when stable
	if s.targetBitrate < maxBitrateBps {
		oldBitrate := s.targetBitrate
		s.targetBitrate += bweIncreaseBps
		if s.targetBitrate > maxBitrateBps {
			s.targetBitrate = maxBitrateBps
		}
		log.Printf("SRT BWE: probe +%d Kbps -> %d Kbps",
			bweIncreaseBps/1000, s.targetBitrate/1000)
		_ = oldBitrate // silence unused warning
	}

	return s.targetBitrate
}
