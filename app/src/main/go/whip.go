package kinetic

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/google/uuid"
	"github.com/kevmo314/kinetic/pkg/androidnet"
	"github.com/pion/interceptor"
	"github.com/pion/interceptor/pkg/cc"
	"github.com/pion/interceptor/pkg/gcc"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"
)

// WHIPSink publishes media to a WHIP endpoint. The current connection lives in
// an atomic pointer that the reconnect goroutine swaps; it is nil while
// reconnecting, during which writes are dropped.
type WHIPSink struct {
	conn          atomic.Pointer[whipConn]
	onPLICallback atomic.Pointer[func()]

	url         string
	bearerToken string
	mimeTypes   string

	done      chan struct{} // closed by Close
	closeOnce sync.Once
}

// whipConn is one negotiated peer connection and everything bound to it.
type whipConn struct {
	pc          *webrtc.PeerConnection
	tracks      []*whipTrack
	estimator   cc.BandwidthEstimator
	resourceURL string // WHIP resource URL for DELETE on close

	token    string
	shutOnce sync.Once
}

type whipTrack struct {
	mu         sync.Mutex // guards everything below; a track is written by one capture thread
	track      *webrtc.TrackLocalStaticRTP
	packetizer rtp.Packetizer
	sps        []byte // Store last SPS NALU
	pps        []byte // Store last PPS NALU
	frameCount uint64
	bitrate    int // cached target bitrate, refreshed every few frames
	lastLogPTS int64
}

const (
	// Playout delay RTP header extension URI
	playoutDelayExtensionURI = "http://www.webrtc.org/experiments/rtp-hdrext/playout-delay"
	// Extension ID to use (must match what's negotiated in SDP)
	playoutDelayExtensionID = 6
	// Playout delay values in 10ms units (100 = 1000ms = 1s)
	minPlayoutDelay = 100 // 1 second
	maxPlayoutDelay = 100 // 1 second

	// How many video frames to reuse a bandwidth estimate for.
	bitrateRefreshFrames = 15
)

var (
	// whipConnectTimeout bounds one connect() attempt (HTTP + ICE).
	whipConnectTimeout = 15 * time.Second
	// whipMaxBackoff caps the delay between reconnect attempts.
	whipMaxBackoff = 5 * time.Second

	whipHTTPClient = &http.Client{Timeout: 10 * time.Second}

	// playoutDelayPayload is the marshalled extension, identical for every packet.
	playoutDelayPayload = func() []byte {
		p, err := (&rtp.PlayoutDelayExtension{MinDelay: minPlayoutDelay, MaxDelay: maxPlayoutDelay}).Marshal()
		if err != nil {
			panic(err)
		}
		return p
	}()
)

// addPlayoutDelayExtension adds the playout delay header extension to an RTP packet
func addPlayoutDelayExtension(pkt *rtp.Packet) {
	pkt.Header.Extension = true
	pkt.Header.ExtensionProfile = 0xBEDE // One-byte header extension
	pkt.Header.SetExtension(playoutDelayExtensionID, playoutDelayPayload)
}

// WHIPSinkOption configures the WHIP sink
type WHIPSinkOption func(*WHIPSink)

func NewWHIPSink(url, bearerToken, encodedMediaFormatMimeTypes string, opts ...WHIPSinkOption) (*WHIPSink, error) {
	s := &WHIPSink{
		url:         url,
		bearerToken: bearerToken,
		mimeTypes:   encodedMediaFormatMimeTypes,
		done:        make(chan struct{}),
	}

	for _, opt := range opts {
		opt(s)
	}

	c, err := s.connect()
	if err != nil {
		return nil, err
	}
	s.install(c)

	return s, nil
}

// connect establishes a new WebRTC connection. It returns an error, with
// everything it created cleaned up, if the connection is not up within
// whipConnectTimeout or the sink is closed.
func (s *WHIPSink) connect() (_ *whipConn, err error) {
	log.Printf("WHIP connect: starting with mimeTypes=%s", s.mimeTypes)
	mediaFormatMimeTypes := strings.Split(s.mimeTypes, ";")

	net, err := androidnet.NewNet()
	if err != nil {
		log.Printf("WHIP connect: failed to create net: %v", err)
		return nil, err
	}

	settingEngine := webrtc.SettingEngine{}

	settingEngine.SetNet(net)
	settingEngine.SetICERenomination()

	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		return nil, err
	}

	// Register playout delay header extension for video
	if err := m.RegisterHeaderExtension(
		webrtc.RTPHeaderExtensionCapability{URI: playoutDelayExtensionURI},
		webrtc.RTPCodecTypeVideo,
		webrtc.RTPTransceiverDirectionSendonly,
	); err != nil {
		log.Printf("Warning: failed to register playout delay extension: %v", err)
	}

	i := &interceptor.Registry{}

	// Create congestion controller
	// Initial bitrate: 1 Mbps, Min: 400 Kbps (IVS requires 200+ for TWCC), Max: 7.5 Mbps
	congestionController, err := cc.NewInterceptor(func() (cc.BandwidthEstimator, error) {
		return gcc.NewSendSideBWE(
			gcc.SendSideBWEInitialBitrate(1_000_000),
			gcc.SendSideBWEMinBitrate(400_000),
			gcc.SendSideBWEMaxBitrate(7_500_000),
		)
	})
	if err != nil {
		return nil, err
	}

	// Capture the bandwidth estimator when peer connection is created
	estimatorChan := make(chan cc.BandwidthEstimator, 1)
	congestionController.OnNewPeerConnection(func(id string, estimator cc.BandwidthEstimator) {
		select {
		case estimatorChan <- estimator:
		default:
		}
	})

	i.Add(congestionController)

	// Configure TWCC header extension for congestion control
	if err = webrtc.ConfigureTWCCHeaderExtensionSender(m, i); err != nil {
		return nil, err
	}

	// The defaults include the NACK generator/responder, RTCP reports and TWCC.
	if err := webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		return nil, err
	}

	api := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine), webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))

	// Create a new RTCPeerConnection
	pc, err := api.NewPeerConnection(webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{URLs: []string{"stun:stun.l.google.com:19302"}},
		},
	})
	if err != nil {
		return nil, err
	}
	c := &whipConn{pc: pc, token: s.bearerToken}
	defer func() {
		if err != nil {
			c.shutdown()
		}
	}()

	// Register state handlers before negotiating so no transition is missed.
	connectedCh := make(chan struct{})
	failedCh := make(chan struct{})
	var connectedOnce, failedOnce sync.Once
	pc.OnICEConnectionStateChange(func(state webrtc.ICEConnectionState) {
		log.Printf("ICE connection state changed: %s", state.String())
		switch state {
		case webrtc.ICEConnectionStateConnected:
			connectedOnce.Do(func() { close(connectedCh) })
		case webrtc.ICEConnectionStateDisconnected, webrtc.ICEConnectionStateFailed, webrtc.ICEConnectionStateClosed:
			failedOnce.Do(func() { close(failedCh) })
			s.lost(c)
		}
	})

	// add all the tracks
	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		pionMimeType := MediaFormatMimeType(mediaFormatMimeType).PionMimeType()
		log.Printf("WHIP connect: track %d: input=%s pion=%s", i, mediaFormatMimeType, pionMimeType)

		// Create TrackLocalStaticRTP for direct RTP packet control
		track, err := webrtc.NewTrackLocalStaticRTP(webrtc.RTPCodecCapability{MimeType: pionMimeType}, uuid.NewString(), uuid.NewString())
		if err != nil {
			return nil, err
		}

		// Set up packetizer based on codec type
		var payloader rtp.Payloader
		var clockRate uint32
		var payloadType uint8

		if i == 0 { // Video track (H264)
			payloader = &codecs.H264Payloader{}
			clockRate = 90000
			payloadType = 96
		} else { // Audio track (Opus)
			payloader = &codecs.OpusPayloader{}
			clockRate = 48000
			payloadType = 111
		}

		// Generate a random SSRC
		uuidObj := uuid.New()
		ssrc := uint32(uuidObj[0])<<24 | uint32(uuidObj[1])<<16 | uint32(uuidObj[2])<<8 | uint32(uuidObj[3])
		c.tracks = append(c.tracks, &whipTrack{
			track:      track,
			packetizer: rtp.NewPacketizer(1200, payloadType, ssrc, payloader, rtp.NewRandomSequencer(), clockRate),
			bitrate:    2_000_000, // Default 2 Mbps if no estimator
		})

		rtpSender, err := pc.AddTrack(track)
		if err != nil {
			return nil, err
		}

		// Handle RTCP for video track (index 0)
		if i == 0 {
			go func() {
				var twccCount uint64
				var lastTWCCLog time.Time
				for {
					packets, _, err := rtpSender.ReadRTCP()
					if err != nil {
						return
					}
					for _, packet := range packets {
						if _, ok := packet.(*rtcp.PictureLossIndication); ok {
							log.Printf("Received PLI request")
							if cb := s.onPLICallback.Load(); cb != nil {
								(*cb)()
							}
						}
						// Count TWCC feedback packets
						if _, ok := packet.(*rtcp.TransportLayerCC); ok {
							twccCount++
							// Log TWCC count every 5 seconds
							if time.Since(lastTWCCLog) > 5*time.Second {
								log.Printf("TWCC feedback received: %d packets total", twccCount)
								lastTWCCLog = time.Now()
							}
						}
					}
				}
			}()
		} else {
			go func() {
				rtcpBuf := make([]byte, 1500)
				for {
					if _, _, err := rtpSender.Read(rtcpBuf); err != nil {
						return
					}
				}
			}()
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), whipConnectTimeout)
	defer cancel()

	offer, err := pc.CreateOffer(nil)
	if err != nil {
		return nil, err
	}
	if err := pc.SetLocalDescription(offer); err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, s.url, bytes.NewReader([]byte(offer.SDP)))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/sdp")
	if s.bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+s.bearerToken)
	}
	resp, err := whipHTTPClient.Do(req)
	if err != nil {
		log.Printf("WHIP: HTTP request failed: %v", err)
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}

	// Store the resource URL from Location header for DELETE on close. This is
	// done before the status check so a created-but-unusable session is deleted.
	if location := resp.Header.Get("Location"); location != "" {
		c.resourceURL = location
		if baseURL, err := url.Parse(s.url); err == nil {
			if locationURL, err := url.Parse(location); err == nil {
				c.resourceURL = baseURL.ResolveReference(locationURL).String()
			}
		}
		log.Printf("WHIP: resource URL: %s", c.resourceURL)
	}

	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		log.Printf("WHIP: server returned HTTP %d: %s", resp.StatusCode, string(body))
		return nil, fmt.Errorf("WHIP server returned HTTP %d: %s", resp.StatusCode, string(body))
	}

	log.Printf("WHIP: got SDP answer (%d bytes)", len(body))
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: string(body)}); err != nil {
		log.Printf("WHIP: failed to set remote description: %v", err)
		return nil, err
	}

	select {
	case <-connectedCh:
	case <-failedCh:
		return nil, errors.New("WHIP ICE connection failed")
	case <-ctx.Done():
		return nil, errors.New("WHIP connect timed out waiting for ICE")
	case <-s.done:
		return nil, errors.New("WHIP sink closed")
	}
	log.Printf("WHIP connection established")

	// Wait for bandwidth estimator to be available
	select {
	case c.estimator = <-estimatorChan:
		log.Printf("Bandwidth estimator initialized")
	case <-time.After(5 * time.Second):
		log.Printf("Warning: Bandwidth estimator not available, congestion control disabled")
	case <-s.done:
		return nil, errors.New("WHIP sink closed")
	}

	return c, nil
}

// lost is called when c's ICE connection drops. If c is the live connection it
// is retired (writes are dropped) and a reconnect starts; otherwise (c was
// never installed, or was already retired) nothing happens.
func (s *WHIPSink) lost(c *whipConn) {
	if !s.conn.CompareAndSwap(c, nil) {
		return
	}
	select {
	case <-s.done:
		c.shutdown() // Close may have missed c, having already swapped it out
		return
	default:
	}
	log.Printf("WHIP connection lost, initiating reconnection...")
	go s.reconnect(c)
}

// install publishes a freshly connected c, covering the two events that could
// have been missed before it was visible: Close, and an ICE drop.
func (s *WHIPSink) install(c *whipConn) {
	s.conn.Store(c)
	select {
	case <-s.done:
		if c := s.conn.Swap(nil); c != nil {
			c.shutdown()
		}
		return
	default:
	}
	switch c.pc.ICEConnectionState() {
	case webrtc.ICEConnectionStateDisconnected, webrtc.ICEConnectionStateFailed, webrtc.ICEConnectionStateClosed:
		s.lost(c)
	}
}

// reconnect replaces the lost connection old, retrying with exponential
// backoff until it succeeds or the sink is closed.
func (s *WHIPSink) reconnect(old *whipConn) {
	go old.shutdown() // DELETE may be slow; don't delay the retry

	backoff := 100 * time.Millisecond
	for {
		c, err := s.connect()
		if err == nil {
			s.install(c)
			log.Printf("WHIP reconnection successful")
			return
		}
		log.Printf("Reconnection attempt failed: %v, retrying in %v", err, backoff)
		select {
		case <-s.done:
			return
		case <-time.After(backoff):
		}
		if backoff *= 2; backoff > whipMaxBackoff {
			backoff = whipMaxBackoff
		}
	}
}

// shutdown closes the peer connection and DELETEs the WHIP resource. Safe to
// call more than once.
func (c *whipConn) shutdown() {
	c.shutOnce.Do(func() {
		c.pc.Close()
		if c.resourceURL != "" {
			req, err := http.NewRequest(http.MethodDelete, c.resourceURL, nil)
			if err != nil {
				log.Printf("WHIP: failed to create DELETE request: %v", err)
			} else {
				if c.token != "" {
					req.Header.Set("Authorization", "Bearer "+c.token)
				}
				resp, err := whipHTTPClient.Do(req)
				if err != nil {
					log.Printf("WHIP: DELETE request failed: %v", err)
				} else {
					resp.Body.Close()
					log.Printf("WHIP: DELETE returned %d", resp.StatusCode)
				}
			}
		}
	})
}

// splitAnnexB splits an Annex-B buffer into NAL units without start codes.
func splitAnnexB(buf []byte) [][]byte {
	var nals [][]byte
	start := -1
	for i := 0; i+2 < len(buf); i++ {
		if buf[i] != 0 || buf[i+1] != 0 || buf[i+2] != 1 {
			continue
		}
		if start >= 0 {
			nals = append(nals, bytes.TrimRight(buf[start:i], "\x00"))
		}
		start = i + 3
		i += 2
	}
	if start >= 0 && start < len(buf) {
		nals = append(nals, bytes.TrimRight(buf[start:], "\x00"))
	}
	return nals
}

// WriteH264 processes H.264 data using custom packetizer with absolute timestamps
// Returns the target bitrate in bps (capped at 7.5 Mbps)
func (s *WHIPSink) WriteH264(buf []byte, ptsMicroseconds int64) (int, error) {
	c := s.conn.Load()
	if c == nil || len(c.tracks) == 0 {
		// Drop packet during reconnection
		return 0, nil
	}

	t := c.tracks[0]
	t.mu.Lock()
	defer t.mu.Unlock()

	// Convert microseconds to RTP timestamp units (90kHz clock for video)
	rtpTimestamp := uint32(ptsMicroseconds * 90000 / 1_000_000)

	// Packetize every NAL of the access unit, then mark only the last packet.
	// Pass 0 samples so the packetizer doesn't advance its own timestamp.
	var packets []*rtp.Packet
	nals := splitAnnexB(buf)
	for _, nal := range nals {
		if len(nal) == 0 {
			continue
		}
		switch nal[0] & 0x1F {
		case 7: // SPS: buffer, sent before the next IDR
			t.sps = append(t.sps[:0], nal...)
			continue
		case 8: // PPS
			t.pps = append(t.pps[:0], nal...)
			continue
		case 5: // IDR: send SPS/PPS first
			if t.sps != nil {
				packets = append(packets, t.packetizer.Packetize(t.sps, 0)...)
			}
			if t.pps != nil {
				packets = append(packets, t.packetizer.Packetize(t.pps, 0)...)
			}
		}
		packets = append(packets, t.packetizer.Packetize(nal, 0)...)
	}
	for i, pkt := range packets {
		pkt.Header.Timestamp = rtpTimestamp
		pkt.Header.Marker = i == len(packets)-1
		addPlayoutDelayExtension(pkt)
		if err := t.track.WriteRTP(pkt); err != nil {
			return 0, fmt.Errorf("error writing RTP packet: %v", err)
		}
	}

	// Log stats every ~3 seconds of stream time
	if len(nals) > 0 && ptsMicroseconds-t.lastLogPTS > 3_000_000 {
		log.Printf("WHIP video: %d NALs, %d RTP packets, %d bytes input", len(nals), len(packets), len(buf))
		t.lastLogPTS = ptsMicroseconds
	}

	if c.estimator == nil {
		return t.bitrate, nil
	}

	// GetStats allocates a map, so only refresh the estimate every few frames.
	if t.frameCount%bitrateRefreshFrames == 0 {
		stats := c.estimator.GetStats()
		var targetBitrate int

		// GCC: Use delay-based estimate only (ignore loss-based which IVS corrupts)
		// IVS reports fake 60%+ packet loss at low bitrates, breaking loss-based estimation
		if delayBitrate, ok := stats["delayTargetBitrate"].(int); ok && delayBitrate > 0 {
			targetBitrate = delayBitrate
		} else {
			targetBitrate = c.estimator.GetTargetBitrate()
		}

		// Log GCC stats every 2 refreshes (~1s at 30fps)
		if t.frameCount%(2*bitrateRefreshFrames) == 0 {
			log.Printf("GCC stats: delay=%d kbps, loss=%v, using=%d kbps",
				stats["delayTargetBitrate"], stats["lossTargetBitrate"], targetBitrate/1000)
		}

		// Floor at 400 kbps (336 kbps video + 64 kbps audio), cap at 7.5 Mbps
		t.bitrate = min(max(targetBitrate, 400_000), 7_500_000)
	}
	t.frameCount++

	return t.bitrate, nil
}

// WriteOpus processes Opus audio data using custom packetizer with absolute timestamps
func (s *WHIPSink) WriteOpus(buf []byte, ptsMicroseconds int64) error {
	c := s.conn.Load()
	if c == nil || len(c.tracks) < 2 {
		// Drop packet during reconnection
		return nil
	}

	// Send to audio track (assuming track 1 is audio)
	t := c.tracks[1]
	t.mu.Lock()
	defer t.mu.Unlock()

	// Convert microseconds to RTP timestamp units (48kHz clock for Opus)
	rtpTimestamp := uint32(ptsMicroseconds * 48000 / 1_000_000)

	// Packetize Opus data (usually fits in one packet)
	// Pass 0 to not increment internal timestamp - we control it manually
	for _, pkt := range t.packetizer.Packetize(buf, 0) {
		pkt.Header.Timestamp = rtpTimestamp
		if err := t.track.WriteRTP(pkt); err != nil {
			return fmt.Errorf("error writing Opus RTP packet: %v", err)
		}
	}
	return nil
}

func (s *WHIPSink) SetPLICallback(callback func()) {
	s.onPLICallback.Store(&callback)
}

func (s *WHIPSink) Close() error {
	// Mark as intentionally closed to prevent auto-reconnect
	s.closeOnce.Do(func() { close(s.done) })

	if c := s.conn.Swap(nil); c != nil {
		c.shutdown()
	}
	return nil
}

// GetICEConnectionState returns the current ICE connection state as a string
func (s *WHIPSink) GetICEConnectionState() string {
	if c := s.conn.Load(); c != nil {
		return c.pc.ICEConnectionState().String()
	}
	return webrtc.ICEConnectionStateDisconnected.String()
}

// GetPeerConnectionState returns the current peer connection state as a string
func (s *WHIPSink) GetPeerConnectionState() string {
	if c := s.conn.Load(); c != nil {
		return c.pc.ConnectionState().String()
	}
	return "closed"
}
