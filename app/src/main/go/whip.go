package kinetic

import (
	"bytes"
	"io"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/kevmo314/kinetic/pkg/androidnet"
	"github.com/pion/interceptor"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

type WHIPSink struct {
	pc     *webrtc.PeerConnection
	tracks []*whipTrack
}

type whipTrack struct {
	track           *webrtc.TrackLocalStaticSample
	ptsMicroseconds int64
}

func NewWHIPSink(url, bearerToken, encodedMediaFormatMimeTypes string) (*WHIPSink, error) {
	mediaFormatMimeTypes := strings.Split(encodedMediaFormatMimeTypes, ";")
	tracks := make([]*whipTrack, len(mediaFormatMimeTypes))

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
			{URLs: []string{"stun:stun.l.google.com:19302"}},
		},
	})
	if err != nil {
		return nil, err
	}

	// add all the tracks
	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{
			MimeType: MediaFormatMimeType(mediaFormatMimeType).PionMimeType(),
		}, uuid.NewString(), uuid.NewString())
		if err != nil {
			return nil, err
		}
		tracks[i] = &whipTrack{track: track, ptsMicroseconds: 0}
		rtpSender, err := peerConnection.AddTrack(track)
		if err != nil {
			return nil, err
		}
		go func() {
			rtcpBuf := make([]byte, 1500)
			for {
				if _, _, err := rtpSender.Read(rtcpBuf); err != nil {
					return
				}
			}
		}()
	}

	offer, err := peerConnection.CreateOffer(nil)
	if err != nil {
		return nil, err
	}
	log.Printf("offer: %+v", offer)
	if err := peerConnection.SetLocalDescription(offer); err != nil {
		return nil, err
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader([]byte(offer.SDP)))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/sdp")
	if bearerToken != "" {
		req.Header.Set("Authorization", "Bearer "+bearerToken)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	answer := webrtc.SessionDescription{
		Type: webrtc.SDPTypeAnswer,
		SDP:  string(body),
	}
	log.Printf("answer: %+v", answer)
	if err := peerConnection.SetRemoteDescription(answer); err != nil {
		return nil, err
	}

	return &WHIPSink{tracks: tracks, pc: peerConnection}, nil
}

func (s *WHIPSink) WriteSample(i int, buf []byte, ptsMicroseconds int64) error {
	t := s.tracks[i]

	if t.ptsMicroseconds == 0 {
		t.ptsMicroseconds = ptsMicroseconds
	}
	duration := time.Duration(ptsMicroseconds-t.ptsMicroseconds) * time.Microsecond
	t.ptsMicroseconds = ptsMicroseconds
	return t.track.WriteSample(media.Sample{Data: buf, Duration: duration})
}

func (s *WHIPSink) Close() error {
	return s.pc.Close()
}
