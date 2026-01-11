package kinetic

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/kevmo314/kinetic/pkg/androidnet"
	"github.com/pion/interceptor"
	"github.com/pion/interceptor/pkg/cc"
	"github.com/pion/interceptor/pkg/gcc"
	"github.com/pion/interceptor/pkg/nack"
	"github.com/pion/rtcp"
	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media/h264reader"
)

type WHIPSink struct {
	pc            *webrtc.PeerConnection
	tracks        []*whipTrack
	onPLICallback func()
	estimator     cc.BandwidthEstimator

	// Reconnection fields
	mu              sync.RWMutex
	url             string
	bearerToken     string
	mimeTypes       string
	connectionState webrtc.ICEConnectionState
	reconnecting    bool
}

type whipTrack struct {
	track           *webrtc.TrackLocalStaticRTP
	packetizer      rtp.Packetizer
	ptsMicroseconds int64
	sps             []byte // Store last SPS NALU
	pps             []byte // Store last PPS NALU
	clockRate       uint32
	ssrc            uint32
	payloadType     uint8
}

const (
	// Playout delay RTP header extension URI
	playoutDelayExtensionURI = "http://www.webrtc.org/experiments/rtp-hdrext/playout-delay"
	// Extension ID to use (must match what's negotiated in SDP)
	playoutDelayExtensionID = 6
	// Playout delay values in 10ms units (100 = 1000ms = 1s)
	minPlayoutDelay = 100 // 1 second
	maxPlayoutDelay = 100 // 1 second
)

// addPlayoutDelayExtension adds the playout delay header extension to an RTP packet
func addPlayoutDelayExtension(pkt *rtp.Packet) {
	ext := rtp.PlayoutDelayExtension{
		MinDelay: minPlayoutDelay,
		MaxDelay: maxPlayoutDelay,
	}
	payload, err := ext.Marshal()
	if err != nil {
		return
	}
	pkt.Header.Extension = true
	pkt.Header.ExtensionProfile = 0xBEDE // One-byte header extension
	pkt.Header.SetExtension(playoutDelayExtensionID, payload)
}

func NewWHIPSink(url, bearerToken, encodedMediaFormatMimeTypes string) (*WHIPSink, error) {
	s := &WHIPSink{
		url:         url,
		bearerToken: bearerToken,
		mimeTypes:   encodedMediaFormatMimeTypes,
	}

	err := s.connect()
	if err != nil {
		return nil, err
	}

	return s, nil
}

// connect establishes a new WebRTC connection
func (s *WHIPSink) connect() error {
	mediaFormatMimeTypes := strings.Split(s.mimeTypes, ";")
	tracks := make([]*whipTrack, len(mediaFormatMimeTypes))

	ifs, err := androidnet.Interfaces()
	if err != nil {
		return err
	}

	log.Printf("Interfaces: %v", ifs)

	net, err := androidnet.NewNet()
	if err != nil {
		return err
	}

	settingEngine := webrtc.SettingEngine{}

	settingEngine.SetNet(net)
	settingEngine.SetICERenomination()

	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		return err
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

	// Add NACK generator for packet retransmission (part of FEC strategy)
	generatorFactory, err := nack.NewGeneratorInterceptor()
	if err != nil {
		return err
	}
	i.Add(generatorFactory)

	// Add NACK responder to handle retransmission requests
	responderFactory, err := nack.NewResponderInterceptor()
	if err != nil {
		return err
	}
	i.Add(responderFactory)

	// Create congestion controller with Google Congestion Control
	// Initial bitrate: 1 Mbps
	congestionController, err := cc.NewInterceptor(func() (cc.BandwidthEstimator, error) {
		return gcc.NewSendSideBWE(gcc.SendSideBWEInitialBitrate(1_000_000))
	})
	if err != nil {
		return err
	}

	// Capture the bandwidth estimator when peer connection is created
	estimatorChan := make(chan cc.BandwidthEstimator, 1)
	congestionController.OnNewPeerConnection(func(id string, estimator cc.BandwidthEstimator) {
		estimatorChan <- estimator
	})

	i.Add(congestionController)

	// Configure TWCC header extension for congestion control
	if err = webrtc.ConfigureTWCCHeaderExtensionSender(m, i); err != nil {
		return err
	}

	if err := webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		return err
	}

	api := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine), webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))

	// Create a new RTCPeerConnection
	peerConnection, err := api.NewPeerConnection(webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{URLs: []string{"stun:stun.l.google.com:19302"}},
		},
	})
	if err != nil {
		return err
	}

	// add all the tracks
	s.tracks = tracks
	s.pc = peerConnection

	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		codecCap := webrtc.RTPCodecCapability{
			MimeType: MediaFormatMimeType(mediaFormatMimeType).PionMimeType(),
		}

		// Create TrackLocalStaticRTP for direct RTP packet control
		track, err := webrtc.NewTrackLocalStaticRTP(codecCap, uuid.NewString(), uuid.NewString())
		if err != nil {
			return err
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
		packetizer := rtp.NewPacketizer(
			1200, // MTU
			payloadType,
			ssrc,
			payloader,
			rtp.NewRandomSequencer(),
			clockRate,
		)

		tracks[i] = &whipTrack{
			track:           track,
			packetizer:      packetizer,
			ptsMicroseconds: 0,
			clockRate:       clockRate,
			ssrc:            ssrc,
			payloadType:     payloadType,
		}
		rtpSender, err := peerConnection.AddTrack(track)
		if err != nil {
			return err
		}

		// Handle RTCP for video track (index 0)
		if i == 0 {
			go func() {
				for {
					packets, _, err := rtpSender.ReadRTCP()
					if err != nil {
						return
					}
					for _, packet := range packets {
						if _, ok := packet.(*rtcp.PictureLossIndication); ok {
							log.Printf("Received PLI request")
							if s.onPLICallback != nil {
								s.onPLICallback()
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

	offer, err := peerConnection.CreateOffer(nil)
	if err != nil {
		return err
	}
	if err := peerConnection.SetLocalDescription(offer); err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, s.url, bytes.NewReader([]byte(offer.SDP)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/sdp")
	if s.bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+s.bearerToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return err
	}
	answer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeAnswer,
		SDP:  string(body),
	}
	if err := peerConnection.SetRemoteDescription(answer); err != nil {
		return err
	}

	connectedCh := make(chan struct{})

	peerConnection.OnICEConnectionStateChange(func(connectionState webrtc.ICEConnectionState) {
		log.Printf("ICE connection state changed: %s", connectionState.String())

		s.mu.Lock()
		s.connectionState = connectionState
		s.mu.Unlock()

		switch connectionState {
		case webrtc.ICEConnectionStateConnected:
			s.mu.Lock()
			s.reconnecting = false
			s.mu.Unlock()
			log.Printf("WHIP connection established")
			select {
			case <-connectedCh:
			default:
				close(connectedCh)
			}

		case webrtc.ICEConnectionStateDisconnected, webrtc.ICEConnectionStateFailed, webrtc.ICEConnectionStateClosed:
			s.mu.Lock()
			alreadyReconnecting := s.reconnecting
			s.reconnecting = true
			s.mu.Unlock()

			if !alreadyReconnecting {
				log.Printf("WHIP connection lost, initiating reconnection...")
				go s.reconnect()
			}
		}
	})

	<-connectedCh

	// Wait for bandwidth estimator to be available
	select {
	case estimator := <-estimatorChan:
		s.estimator = estimator
		log.Printf("Bandwidth estimator initialized")
	case <-time.After(5 * time.Second):
		log.Printf("Warning: Bandwidth estimator not available, congestion control disabled")
	}

	return nil
}

func (s *WHIPSink) WriteSample(i int, buf []byte, ptsMicroseconds int64) error {
	// Redirect to codec-specific methods for backward compatibility
	// This method is kept for compatibility but should not be used directly
	if i == 0 {
		_, err := s.WriteH264(buf, ptsMicroseconds)
		return err
	} else if i == 1 {
		return s.WriteOpus(buf, ptsMicroseconds)
	}

	return nil
}

// reconnect attempts to re-establish the WHIP connection
func (s *WHIPSink) reconnect() {
	log.Printf("Starting WHIP reconnection...")

	// Close the old connection if it exists
	if s.pc != nil {
		s.pc.Close()
	}

	// Clear estimator
	s.estimator = nil

	// Keep trying to reconnect
	for {
		s.mu.RLock()
		shouldReconnect := s.reconnecting
		s.mu.RUnlock()

		if !shouldReconnect {
			break
		}

		err := s.connect()
		if err != nil {
			log.Printf("Reconnection attempt failed: %v, retrying immediately...", err)
			time.Sleep(100 * time.Millisecond) // Small delay to avoid tight loop
			continue
		}

		log.Printf("WHIP reconnection successful")
		break
	}
}

// WriteH264 processes H.264 data using custom packetizer with absolute timestamps
// Returns the target bitrate in bps (capped at 7.5 Mbps)
func (s *WHIPSink) WriteH264(buf []byte, ptsMicroseconds int64) (int, error) {
	s.mu.RLock()
	reconnecting := s.reconnecting
	s.mu.RUnlock()

	if reconnecting {
		// Drop packet during reconnection
		return 0, nil
	}

	if len(s.tracks) == 0 {
		return 0, nil
	}

	videoTrack := s.tracks[0]

	// Convert microseconds to RTP timestamp units (90kHz clock for video)
	rtpTimestamp := uint32(ptsMicroseconds * 90000 / 1_000_000)

	// Create h264reader from the buffer
	reader := bytes.NewReader(buf)
	h264Reader, err := h264reader.NewReader(reader)
	if err != nil {
		return 0, fmt.Errorf("failed to create h264reader: %v", err)
	}

	nalCount := 0
	for {
		nal, err := h264Reader.NextNAL()
		if err == io.EOF {
			break
		}
		if err != nil {
			return 0, fmt.Errorf("error reading NAL: %v", err)
		}

		nalCount++
		naluType := nal.Data[0] & 0x1F

		// Handle SPS/PPS buffering
		switch naluType {
		case 7: // SPS
			videoTrack.sps = make([]byte, len(nal.Data))
			copy(videoTrack.sps, nal.Data)
			continue // Don't send SPS immediately

		case 8: // PPS
			videoTrack.pps = make([]byte, len(nal.Data))
			copy(videoTrack.pps, nal.Data)
			continue // Don't send PPS immediately

		case 5: // IDR
			// Send SPS first if we have it
			if videoTrack.sps != nil {
				// Packetize and send SPS (pass 0 to not increment internal timestamp)
				packets := videoTrack.packetizer.(rtp.Packetizer).Packetize(videoTrack.sps, 0)
				for _, pkt := range packets {
					pkt.Header.Timestamp = rtpTimestamp
					addPlayoutDelayExtension(pkt)
					if err := videoTrack.track.WriteRTP(pkt); err != nil {
						return 0, fmt.Errorf("error writing SPS RTP: %v", err)
					}
				}
			}
			// Send PPS next if we have it
			if videoTrack.pps != nil {
				// Packetize and send PPS
				packets := videoTrack.packetizer.(rtp.Packetizer).Packetize(videoTrack.pps, 0)
				for _, pkt := range packets {
					pkt.Header.Timestamp = rtpTimestamp
					addPlayoutDelayExtension(pkt)
					if err := videoTrack.track.WriteRTP(pkt); err != nil {
						return 0, fmt.Errorf("error writing PPS RTP: %v", err)
					}
				}
			}
			// Fall through to send IDR
		}

		// Packetize NAL unit (handles fragmentation for MTU)
		// Always pass 0 to not increment internal timestamp - we control it manually
		packets := videoTrack.packetizer.(rtp.Packetizer).Packetize(nal.Data, 0)

		// Override timestamp to use our absolute timestamp
		for _, pkt := range packets {
			pkt.Header.Timestamp = rtpTimestamp
			addPlayoutDelayExtension(pkt)
			if err := videoTrack.track.WriteRTP(pkt); err != nil {
				return 0, fmt.Errorf("error writing RTP packet: %v", err)
			}
		}
	}

	videoTrack.ptsMicroseconds = ptsMicroseconds

	// Get target bitrate from congestion controller
	targetBitrate := 2_000_000 // Default 2 Mbps if no estimator
	if s.estimator != nil {
		targetBitrate = s.estimator.GetTargetBitrate()
		// Cap at 7.5 Mbps maximum
		const maxBitrate = 7_500_000
		if targetBitrate > maxBitrate {
			targetBitrate = maxBitrate
		}
		log.Printf("Congestion control target bitrate: %d bps", targetBitrate)
	}

	return targetBitrate, nil
}

// getNALUTypeName returns a human-readable name for the NALU type
func getNALUTypeName(naluType uint8) string {
	switch naluType {
	case 1:
		return "P-slice"
	case 5:
		return "IDR"
	case 6:
		return "SEI"
	case 7:
		return "SPS"
	case 8:
		return "PPS"
	case 9:
		return "AUD"
	case 28:
		return "FU-A"
	default:
		return fmt.Sprintf("Unknown(%d)", naluType)
	}
}

// WriteOpus processes Opus audio data using custom packetizer with absolute timestamps
func (s *WHIPSink) WriteOpus(buf []byte, ptsMicroseconds int64) error {
	s.mu.RLock()
	reconnecting := s.reconnecting
	s.mu.RUnlock()

	if reconnecting {
		// Drop packet during reconnection
		return nil
	}

	if len(s.tracks) < 2 {
		return nil
	}

	// Send to audio track (assuming track 1 is audio)
	audioTrack := s.tracks[1]

	// Convert microseconds to RTP timestamp units (48kHz clock for Opus)
	rtpTimestamp := uint32(ptsMicroseconds * 48000 / 1_000_000)

	// Packetize Opus data (usually fits in one packet)
	// Pass 0 to not increment internal timestamp - we control it manually
	packets := audioTrack.packetizer.(rtp.Packetizer).Packetize(buf, 0)

	// Override timestamp to use our absolute timestamp
	for _, pkt := range packets {
		pkt.Header.Timestamp = rtpTimestamp
		if err := audioTrack.track.WriteRTP(pkt); err != nil {
			return fmt.Errorf("error writing Opus RTP packet: %v", err)
		}
	}

	audioTrack.ptsMicroseconds = ptsMicroseconds
	return nil
}

func (s *WHIPSink) SetPLICallback(callback func()) {
	s.onPLICallback = callback
}

func (s *WHIPSink) Close() error {
	return s.pc.Close()
}
