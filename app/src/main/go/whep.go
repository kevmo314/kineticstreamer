package kinetic

import (
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/kevmo314/kinetic/pkg/androidnet"
	"github.com/pion/interceptor"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

type WHEPSink struct {
	tracks []*whepTrack
}

type whepTrack struct {
	track *webrtc.TrackLocalStaticSample

	ptsMicroseconds int64
}

func NewWHEPSink(url, bearerToken, encodedMediaFormatMimeTypes string) (*WHEPSink, error) {
	mediaFormatMimeTypes := strings.Split(encodedMediaFormatMimeTypes, ";")
	tracks := make([]*whepTrack, len(mediaFormatMimeTypes))

	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		mimeType := MediaFormatMimeType(mediaFormatMimeType).PionMimeType()
		track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{
			MimeType: mimeType,
		}, uuid.NewString(), uuid.NewString())
		if err != nil {
			return nil, err
		}
		tracks[i] = &whepTrack{track: track}
	}

	go http.ListenAndServe(":8080", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			w.WriteHeader(http.StatusMethodNotAllowed)
			return
		}

		net, err := androidnet.NewNet()
		if err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

		settingEngine := webrtc.SettingEngine{}

		settingEngine.SetNet(net)
		settingEngine.SetICERenomination()

		m := &webrtc.MediaEngine{}
		if err := m.RegisterDefaultCodecs(); err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

		i := &interceptor.Registry{}
		if err := webrtc.RegisterDefaultInterceptors(m, i); err != nil {
			w.WriteHeader(http.StatusInternalServerError)
			return
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
			w.WriteHeader(http.StatusInternalServerError)
			return
		}

		for _, track := range tracks {
			rtpSender, err := peerConnection.AddTrack(track.track)
			if err != nil {
				w.WriteHeader(http.StatusInternalServerError)
				return
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
	}))

	return &WHEPSink{tracks: tracks}, nil
}

func (s *WHEPSink) WriteSample(i int, buf []byte, ptsMicroseconds int64) (bool, error) {
	t := s.tracks[i]

	if t.ptsMicroseconds == 0 {
		t.ptsMicroseconds = ptsMicroseconds
	}
	duration := time.Duration(ptsMicroseconds-t.ptsMicroseconds) * time.Microsecond
	t.ptsMicroseconds = ptsMicroseconds
	return false, t.track.WriteSample(media.Sample{Data: buf, Duration: duration})
}

func (s *WHEPSink) Close() error {
	// TODO: close the http server
	return nil
}
