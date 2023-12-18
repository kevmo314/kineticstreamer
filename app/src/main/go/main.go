package kinetic

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/google/uuid"
	"github.com/kevmo314/kinetic/pkg/androidnet"
	"github.com/pion/interceptor"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
	"github.com/pion/webrtc/v4/pkg/media/h264reader"
)

type WHIPSink struct {
	url, bearerToken string
	pc               *webrtc.PeerConnection

	iceConnectedCtx context.Context
}

func NewWHIPSink(url, bearerToken string) (*WHIPSink, error) {
	net, err := androidnet.NewNet()
	if err != nil {
		return nil, err
	}

	settingEngine := webrtc.SettingEngine{}

	settingEngine.SetNet(net)

	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		return nil, err
	}

	i := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		return nil, err
	}

	api := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine), webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))

	// Create a new RTCPeerConnection
	peerConnection, err := api.NewPeerConnection(webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{
				URLs: []string{"stun:stun.l.google.com:19302"},
			},
		},
	})
	if err != nil {
		return nil, err
	}

	iceConnectedCtx, iceConnectedCtxCancel := context.WithCancel(context.Background())

	peerConnection.OnICEConnectionStateChange(func(connectionState webrtc.ICEConnectionState) {
		fmt.Printf("Connection State has changed %s \n", connectionState.String())
		if connectionState == webrtc.ICEConnectionStateConnected {
			iceConnectedCtxCancel()
		}
	})

	return &WHIPSink{
		url:             url,
		bearerToken:     bearerToken,
		pc:              peerConnection,
		iceConnectedCtx: iceConnectedCtx,
	}, nil
}

func (s *WHIPSink) AddH264Track() (*H264Track, error) {
	track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{
		MimeType: webrtc.MimeTypeH264,
	}, uuid.NewString(), uuid.NewString())
	if err != nil {
		return nil, err
	}
	rtpSender, err := s.pc.AddTrack(track)
	if err != nil {
		return nil, err
	}
	go func() {
		rtcpBuf := make([]byte, 1500)
		for {
			if _, _, rtcpErr := rtpSender.Read(rtcpBuf); rtcpErr != nil {
				return
			}
		}
	}()
	return NewH264Track(track)
}

type H264Track struct {
	track *webrtc.TrackLocalStaticSample

	buffer *bytes.Buffer
	reader *h264reader.H264Reader

	ptsMicroseconds int64
}

func NewH264Track(track *webrtc.TrackLocalStaticSample) (*H264Track, error) {
	buffer := &bytes.Buffer{}
	reader, err := h264reader.NewReader(buffer)
	if err != nil {
		return nil, err
	}
	return &H264Track{
		track:  track,
		buffer: buffer,
		reader: reader,
	}, nil
}

func (t *H264Track) WriteH264AnnexBSample(buf []byte, ptsMicroseconds int64) error {
	// append to buffer
	if _, err := t.buffer.Write(buf); err != nil {
		return err
	}
	// calculate the duration
	if t.ptsMicroseconds == 0 {
		t.ptsMicroseconds = ptsMicroseconds
	}
	duration := time.Duration(ptsMicroseconds-t.ptsMicroseconds) * time.Microsecond
	t.ptsMicroseconds = ptsMicroseconds
	// drain the reader
	for i := 0; ; i++ {
		nalu, err := t.reader.NextNAL()
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
		if nalu == nil {
			return nil
		}
		time.Sleep(time.Millisecond * 33)
		log.Printf("%d bytes, duration %v", len(nalu.Data), duration)
		if err := t.track.WriteSample(media.Sample{Data: nalu.Data, Duration: time.Millisecond * 33}); err != nil {
			return err
		}
	}
}

func (s *WHIPSink) Connect() error {
	offer, err := s.pc.CreateOffer(nil)
	if err != nil {
		return err
	}
	if err := s.pc.SetLocalDescription(offer); err != nil {
		return err
	}
	req, err := http.NewRequest(http.MethodPost, "https://b.siobud.com/api/whip", bytes.NewReader([]byte(offer.SDP)))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/sdp")
	req.Header.Set("Authorization", "Bearer kevmo311")
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
	if err := s.pc.SetRemoteDescription(answer); err != nil {
		return err
	}
	// wait until connected
	<-s.iceConnectedCtx.Done()
	log.Printf("connected")
	return nil
}

func (s *WHIPSink) Close() error {
	return s.pc.Close()
}

func (s *WHIPSink) Demo() { //nolint
	// Assert that we have an audio or video file
	_, err := os.Stat("/data/data/com.kevmo314.kineticstreamer/files/encoder.h264")
	haveVideoFile := !os.IsNotExist(err)

	net, err := androidnet.NewNet()
	if err != nil {
		panic(err)
	}

	settingEngine := webrtc.SettingEngine{}

	settingEngine.SetNet(net)

	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		panic(err)
	}

	i := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		panic(err)
	}

	api := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine), webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))

	// Create a new RTCPeerConnection
	peerConnection, err := api.NewPeerConnection(webrtc.Configuration{
		ICEServers: []webrtc.ICEServer{
			{
				URLs: []string{"stun:stun.l.google.com:19302"},
			},
		},
	})
	if err != nil {
		panic(err)
	}
	defer func() {
		if cErr := peerConnection.Close(); cErr != nil {
			fmt.Printf("cannot close peerConnection: %v\n", cErr)
		}
	}()

	iceConnectedCtx, iceConnectedCtxCancel := context.WithCancel(context.Background())

	if haveVideoFile {
		// Create a video track
		videoTrack, videoTrackErr := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeH264}, "video", "pion")
		if videoTrackErr != nil {
			panic(videoTrackErr)
		}

		rtpSender, videoTrackErr := peerConnection.AddTrack(videoTrack)
		if videoTrackErr != nil {
			panic(videoTrackErr)
		}

		// Read incoming RTCP packets
		// Before these packets are returned they are processed by interceptors. For things
		// like NACK this needs to be called.
		go func() {
			rtcpBuf := make([]byte, 1500)
			for {
				if _, _, rtcpErr := rtpSender.Read(rtcpBuf); rtcpErr != nil {
					return
				}
			}
		}()

		go func() {
			// Open a H264 file and start reading using our IVFReader
			file, h264Err := os.Open("/data/data/com.kevmo314.kineticstreamer/files/encoder.h264")
			if h264Err != nil {
				panic(h264Err)
			}

			h264, h264Err := h264reader.NewReader(file)
			if h264Err != nil {
				panic(h264Err)
			}

			// Wait for connection established
			<-iceConnectedCtx.Done()

			// Send our video file frame at a time. Pace our sending so we send it at the same speed it should be played back as.
			// This isn't required since the video is timestamped, but we will such much higher loss if we send all at once.
			//
			// It is important to use a time.Ticker instead of time.Sleep because
			// * avoids accumulating skew, just calling time.Sleep didn't compensate for the time spent parsing the data
			// * works around latency issues with Sleep (see https://github.com/golang/go/issues/44343)
			ticker := time.NewTicker(33 * time.Millisecond)
			for ; true; <-ticker.C {
				nal, h264Err := h264.NextNAL()
				if h264Err == io.EOF {
					fmt.Printf("All video frames parsed and sent")
					os.Exit(0)
				}

				if h264Err != nil {
					panic(h264Err)
				}

				if h264Err = videoTrack.WriteSample(media.Sample{Data: nal.Data, Duration: 33 * time.Millisecond}); h264Err != nil {
					panic(h264Err)
				}
			}
		}()
	}

	// Set the handler for ICE connection state
	// This will notify you when the peer has connected/disconnected
	peerConnection.OnICEConnectionStateChange(func(connectionState webrtc.ICEConnectionState) {
		fmt.Printf("Connszdfsdection State has changed %s \n", connectionState.String())
		if connectionState == webrtc.ICEConnectionStateConnected {
			iceConnectedCtxCancel()
		}
	})

	// Set the handler for Peer connection state
	// This will notify you when the peer has connected/disconnected
	peerConnection.OnConnectionStateChange(func(s webrtc.PeerConnectionState) {
		fmt.Printf("zsdfzsdf Peer Connection State has changed: %s\n", s.String())

		if s == webrtc.PeerConnectionStateFailed {
			// Wait until PeerConnection has had no network activity for 30 seconds or another failure. It may be reconnected using an ICE Restart.
			// Use webrtc.PeerConnectionStateDisconnected if you are interested in detecting faster timeout.
			// Note that the PeerConnection may come back from PeerConnectionStateDisconnected.
			fmt.Println("Peer Connection has gone to failed exiting")
			os.Exit(0)
		}
	})

	// Wait for the offer to be pasted
	offer, err := peerConnection.CreateOffer(nil)
	if err != nil {
		panic(err)
	}
	if err := peerConnection.SetLocalDescription(offer); err != nil {
		panic(err)
	}
	req, err := http.NewRequest(http.MethodPost, "https://b.siobud.com/api/whip", bytes.NewReader([]byte(offer.SDP)))
	if err != nil {
		panic(err)
	}
	req.Header.Set("Content-Type", "application/sdp")
	req.Header.Set("Authorization", "Bearer kevmo314")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		panic(err)
	}
	answer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeAnswer,
		SDP:  string(body),
	}
	if err := peerConnection.SetRemoteDescription(answer); err != nil {
		panic(err)
	}
	// Block forever
	select {}
}
