package kinetic

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
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

	connectedCtx context.Context
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

	connectedCtx, connectedCtxCancel := context.WithCancel(context.Background())

	peerConnection.OnConnectionStateChange(func(connectionState webrtc.PeerConnectionState) {
		fmt.Printf("Connection State has changed %s \n", connectionState.String())
		if connectionState == webrtc.PeerConnectionStateConnected {
			connectedCtxCancel()
		}
	})

	return &WHIPSink{
		url:          url,
		bearerToken:  bearerToken,
		pc:           peerConnection,
		connectedCtx: connectedCtx,
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
	if err := s.pc.SetRemoteDescription(answer); err != nil {
		return err
	}
	// wait until connected
	<-s.connectedCtx.Done()
	return nil
}

func (s *WHIPSink) Close() error {
	return s.pc.Close()
}
