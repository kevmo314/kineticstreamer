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
	"encoding/binary"
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

// encodeSocketOption converts a URL option value into the raw bytes libsrt
// expects for the option's type.
func encodeSocketOption(so socketOption, val string) ([]byte, error) {
	switch so.dataType {
	case tInteger32:
		v, err := strconv.ParseInt(val, 10, 32)
		if err != nil {
			return nil, fmt.Errorf("option %s: invalid integer %q", so.name, val)
		}
		return binary.NativeEndian.AppendUint32(nil, uint32(v)), nil
	case tInteger64:
		v, err := strconv.ParseInt(val, 10, 64)
		if err != nil {
			return nil, fmt.Errorf("option %s: invalid integer %q", so.name, val)
		}
		return binary.NativeEndian.AppendUint64(nil, uint64(v)), nil
	case tString:
		return []byte(val), nil
	case tBoolean:
		v, err := strconv.ParseBool(val)
		if err != nil {
			return nil, fmt.Errorf("option %s: invalid boolean %q", so.name, val)
		}
		if v {
			return []byte{1}, nil
		}
		return []byte{0}, nil
	case tTransType:
		switch val {
		case "live":
			return binary.NativeEndian.AppendUint32(nil, C.SRTT_LIVE), nil
		case "file":
			return binary.NativeEndian.AppendUint32(nil, C.SRTT_FILE), nil
		}
		return nil, fmt.Errorf("option %s: invalid value %q (want live or file)", so.name, val)
	}
	return nil, fmt.Errorf("option %s: unsupported type", so.name)
}

// validateSocketOptions reports the first known option with an unparseable
// value. Unknown option names are ignored.
func validateSocketOptions(options map[string]string) error {
	for _, so := range SocketOptions {
		if val, ok := options[so.name]; ok {
			if _, err := encodeSocketOption(so, val); err != nil {
				return err
			}
		}
	}
	return nil
}

func setSocketOptions(s C.int, binding int, options map[string]string) error {
	for _, so := range SocketOptions {
		val, ok := options[so.name]
		if !ok || so.binding != binding {
			continue
		}
		b, err := encodeSocketOption(so, val)
		if err != nil {
			return err
		}
		// SliceData is non-nil even for an empty (string) value.
		if C.srt_setsockflag(s, C.SRT_SOCKOPT(so.option), unsafe.Pointer(unsafe.SliceData(b)), C.int32_t(len(b))) == -1 {
			return fmt.Errorf("error setting option %s to %s: %w", so.name, val, srtGetAndClearError())
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

	mux         *tsMux
	fd          C.int // srtInvalidSock while reconnecting
	tracks      []*mpegts.Track
	pliCallback SRTPLICallback
	closed      bool
	done        chan struct{} // closed by Close to interrupt reconnect backoff

	// reconnecting is set by the one writer that owns the reconnect loop;
	// other writers drop their samples until it clears.
	reconnecting bool

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

// srtInvalidSock is libsrt's SRT_INVALID_SOCK (a C++ const cgo cannot link).
const srtInvalidSock = -1

// srtDebugStats enables per-probe SRT stats logging.
var srtDebugStats = false

// srtRefs counts live sinks so srt_startup/srt_cleanup bracket them.
var srtRefs struct {
	sync.Mutex
	n int
}

func srtAcquire() {
	srtRefs.Lock()
	defer srtRefs.Unlock()
	if srtRefs.n == 0 {
		C.srt_startup()
	}
	srtRefs.n++
}

func srtRelease() {
	srtRefs.Lock()
	defer srtRefs.Unlock()
	srtRefs.n--
	if srtRefs.n == 0 {
		C.srt_cleanup()
	}
}

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
	if err := validateSocketOptions(options); err != nil {
		return nil, fmt.Errorf("SRT: %w", err)
	}

	tracks := []*mpegts.Track{}
	for _, v := range strings.Split(encodedMediaFormatMimeTypes, ";") {
		codec := MediaFormatMimeType(v).MPEGTSCodec()
		tracks = append(tracks, &mpegts.Track{Codec: codec})
	}

	srtAcquire()
	sink := &SRTSink{
		tracks:        tracks,
		done:          make(chan struct{}),
		ip:            ip,
		port:          uint16(port),
		options:       options,
		targetBitrate: startBitrateBps,
	}

	sck, err := sink.connect()
	if err != nil {
		srtRelease()
		return nil, err
	}
	sink.fd = sck.fd
	sink.mux = newTSMux(sck, sck.payloadSize, tracks)

	log.Printf("SRT: sink created with %d tracks\n", len(tracks))
	return sink, nil
}

// connect creates a new SRT socket and connects it. It touches no mutable sink
// state, so it must be called without holding the lock (it can block for the
// connect timeout).
func (s *SRTSink) connect() (SRTSocket, error) {
	sa, salen, err := sockAddrFromIp(s.ip, s.port)
	if err != nil {
		return SRTSocket{}, fmt.Errorf("SRT: failed to create sockaddr: %w", err)
	}

	fd := C.srt_create_socket()
	if fd == srtInvalidSock {
		return SRTSocket{}, fmt.Errorf("SRT: failed to create socket: %w", srtGetAndClearError())
	}

	if err := setSocketOptions(fd, bindingPre, s.options); err != nil {
		C.srt_close(fd)
		return SRTSocket{}, fmt.Errorf("SRT: failed to set pre-bind options: %w", err)
	}

	log.Printf("SRT: connecting to %s:%d...\n", s.ip, s.port)
	if res := C.srt_connect(fd, sa, C.int(salen)); res == -1 {
		err := srtGetAndClearError()
		C.srt_close(fd)
		return SRTSocket{}, fmt.Errorf("SRT: connect failed: %w", err)
	}
	log.Printf("SRT: connected\n")

	if err := setSocketOptions(fd, bindingPost, s.options); err != nil {
		C.srt_close(fd)
		return SRTSocket{}, fmt.Errorf("SRT: failed to set post-bind options: %w", err)
	}

	var payloadSize, optlen C.int = 0, C.int(unsafe.Sizeof(C.int(0)))
	if res, _ := C.srt_getsockflag(fd, C.SRTO_PAYLOADSIZE, unsafe.Pointer(&payloadSize), &optlen); res == -1 || payloadSize <= 0 {
		C.srt_close(fd)
		return SRTSocket{}, fmt.Errorf("SRT: failed to get payload size")
	}

	return SRTSocket{fd: fd, payloadSize: int(payloadSize)}, nil
}

// reconnect replaces the broken connection. The caller must be the sole owner
// (s.reconnecting set, old socket closed) and must not hold the lock.
func (s *SRTSink) reconnect() error {
	for attempt := 1; ; attempt++ {
		delay := time.Duration(attempt) * 500 * time.Millisecond
		if delay > 5*time.Second {
			delay = 5 * time.Second
		}
		log.Printf("SRT: reconnecting (attempt %d) in %v...\n", attempt, delay)

		select {
		case <-time.After(delay):
		case <-s.done:
			srtRelease() // Close left the reference to us
			return fmt.Errorf("SRT: sink closed during reconnect")
		}

		select {
		case <-s.done:
			srtRelease() // Close left the reference to us
			return fmt.Errorf("SRT: sink closed during reconnect")
		default:
		}

		sck, err := s.connect()

		s.Lock()
		if s.closed {
			s.Unlock()
			if err == nil {
				C.srt_close(sck.fd)
			}
			srtRelease() // Close left the reference to us
			return fmt.Errorf("SRT: sink closed during reconnect")
		}
		if err != nil {
			s.Unlock()
			log.Printf("SRT: reconnect attempt %d failed: %v\n", attempt, err)
			continue
		}
		s.fd = sck.fd
		paramSets := s.mux.paramSets // the encoder won't send its config again
		s.mux = newTSMux(sck, sck.payloadSize, s.tracks)
		s.mux.paramSets = paramSets
		s.lastPktSndLossTotal = 0 // stats are per socket
		s.reconnecting = false
		pli := s.pliCallback
		s.Unlock()

		log.Printf("SRT: reconnected\n")
		// The new connection's muxer drops video until a keyframe; ask for one now.
		if pli != nil {
			go pli.OnPLI()
		}
		return nil
	}
}

// WriteSample muxes one sample. If the connection breaks, a background
// goroutine reconnects and samples are dropped meanwhile (the muxer state is
// reset on reconnect anyway), so the caller never blocks on the network.
func (s *SRTSink) WriteSample(i int, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	s.Lock()
	if s.closed {
		s.Unlock()
		return fmt.Errorf("SRT: sink closed")
	}
	if i < 0 || i >= len(s.tracks) {
		s.Unlock()
		return fmt.Errorf("SRT: invalid track index %d", i)
	}
	t := s.tracks[i]
	if t.Codec == nil {
		s.Unlock()
		return fmt.Errorf("SRT: track %d has nil codec", i)
	}
	if s.reconnecting {
		s.Unlock()
		return nil
	}

	err := s.mux.writeSample(t, buf, ptsMicroseconds, mediaCodecFlags)
	if err == nil {
		s.Unlock()
		return nil
	}
	log.Printf("SRT: write failed: %v, reconnecting...\n", err)
	s.reconnecting = true
	C.srt_close(s.fd)
	s.fd = srtInvalidSock
	s.Unlock()
	// Drop this sample; the reconnect result is only logged.
	go func() {
		if err := s.reconnect(); err != nil {
			log.Printf("SRT: %v\n", err)
		}
	}()
	return nil
}

func (s *SRTSink) Close() error {
	s.Lock()
	if s.closed {
		s.Unlock()
		return nil
	}
	s.closed = true
	close(s.done)
	// An in-flight reconnect still uses libsrt; it releases our reference
	// when it notices the sink is closed.
	release := !s.reconnecting
	if s.fd != srtInvalidSock {
		C.srt_close(s.fd)
		s.fd = srtInvalidSock
	}
	s.Unlock()
	if release {
		srtRelease()
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

	if s.closed || s.fd == srtInvalidSock {
		return s.targetBitrate
	}

	now := time.Now()

	// Rate limit probes
	if !s.lastProbeTime.IsZero() && now.Sub(s.lastProbeTime) < bweProbeInterval {
		return s.targetBitrate
	}
	s.lastProbeTime = now

	// Get SRT stats
	var stats C.srt_bwe_stats_t
	if C.srt_get_bwe_stats(s.fd, &stats) != 0 {
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

	if srtDebugStats {
		log.Printf("SRT stats: RTT=%.0fms rate=%.2fMbps estBW=%.2fMbps cwnd=%d flight=%d sndbuf=%d",
			rtt, sendRateMbps, estBwMbps, cwnd, flightSize, sndBuf)
		log.Printf("SRT stats: loss=%d/%d retrans=%d/%d drops=%d/%d target=%dKbps",
			lossInterval, lossTotal, retrans, retransTotal, drops, dropsTotal, s.targetBitrate/1000)
	}

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
		return s.targetBitrate
	}

	// Additive increase when stable
	if s.targetBitrate < maxBitrateBps {
		s.targetBitrate += bweIncreaseBps
		if s.targetBitrate > maxBitrateBps {
			s.targetBitrate = maxBitrateBps
		}
	}

	return s.targetBitrate
}
