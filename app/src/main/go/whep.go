package kinetic

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/kevmo314/kinetic/pkg/androidnet"
	"github.com/pion/interceptor"
	"github.com/pion/webrtc/v4"
	"github.com/pion/webrtc/v4/pkg/media"
)

const whepDefaultAddr = ":8081"

// WHEPSink is a minimal WHEP (WebRTC-HTTP egress protocol) server: a client POSTs an SDP offer
// and gets the SDP answer back with a Location header for the session. DELETE on that location
// ends the session. Not supported: trickle ICE (PATCH), bearer token authentication
// and ICE restarts. The answer is only sent once ICE gathering is complete.
type WHEPSink struct {
	server     *http.Server
	listener   net.Listener
	iceServers []webrtc.ICEServer
	tracks     []*whepTrack
	trackMu    sync.Mutex
	sessionsMu sync.Mutex
	sessions   map[string]*webrtc.PeerConnection
	closed     bool
}

type whepTrack struct {
	track *webrtc.TrackLocalStaticSample

	ptsMicroseconds int64
}

// NewWHEPSink starts serving on addr (":8081" if empty).
func NewWHEPSink(addr, bearerToken, encodedMediaFormatMimeTypes string) (*WHEPSink, error) {
	return newWHEPSink(addr, encodedMediaFormatMimeTypes, []webrtc.ICEServer{{URLs: []string{"stun:stun.l.google.com:19302"}}})
}

func newWHEPSink(addr, encodedMediaFormatMimeTypes string, iceServers []webrtc.ICEServer) (*WHEPSink, error) {
	if addr == "" {
		addr = whepDefaultAddr
	}
	mediaFormatMimeTypes := strings.Split(encodedMediaFormatMimeTypes, ";")
	s := &WHEPSink{
		iceServers: iceServers,
		tracks:     make([]*whepTrack, len(mediaFormatMimeTypes)),
		sessions:   map[string]*webrtc.PeerConnection{},
	}
	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		track, err := webrtc.NewTrackLocalStaticSample(webrtc.RTPCodecCapability{
			MimeType: MediaFormatMimeType(mediaFormatMimeType).PionMimeType(),
		}, uuid.NewString(), uuid.NewString())
		if err != nil {
			return nil, err
		}
		s.tracks[i] = &whepTrack{track: track}
	}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /{$}", s.handleOffer)
	mux.HandleFunc("DELETE /{id}", func(w http.ResponseWriter, r *http.Request) {
		s.sessionsMu.Lock()
		pc, ok := s.sessions[r.PathValue("id")]
		delete(s.sessions, r.PathValue("id"))
		s.sessionsMu.Unlock()
		if !ok {
			http.NotFound(w, r)
			return
		}
		pc.Close()
	})

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	s.listener = listener
	s.server = &http.Server{Handler: mux}
	go func() {
		if err := s.server.Serve(listener); !errors.Is(err, http.ErrServerClosed) {
			log.Printf("whep server: %v", err)
		}
	}()
	return s, nil
}

// Addr is the address the sink is listening on.
func (s *WHEPSink) Addr() net.Addr {
	return s.listener.Addr()
}

func (s *WHEPSink) newPeerConnection() (*webrtc.PeerConnection, error) {
	net, err := androidnet.NewNet()
	if err != nil {
		return nil, err
	}
	settingEngine := webrtc.SettingEngine{}
	settingEngine.SetNet(net)
	settingEngine.SetICERenomination()

	m := &webrtc.MediaEngine{}
	if err := m.RegisterDefaultCodecs(); err != nil {
		return nil, err
	}
	i := &interceptor.Registry{}
	if err := webrtc.RegisterDefaultInterceptors(m, i); err != nil {
		return nil, err
	}
	api := webrtc.NewAPI(webrtc.WithSettingEngine(settingEngine), webrtc.WithMediaEngine(m), webrtc.WithInterceptorRegistry(i))
	return api.NewPeerConnection(webrtc.Configuration{ICEServers: s.iceServers})
}

func (s *WHEPSink) handleOffer(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, 1<<20))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	pc, err := s.newPeerConnection()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// the peer connection is only kept once the exchange succeeds.
	ok := false
	defer func() {
		if !ok {
			pc.Close()
		}
	}()

	for _, track := range s.tracks {
		rtpSender, err := pc.AddTrack(track.track)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		go func() {
			// drain RTCP, returns when the peer connection is closed.
			rtcpBuf := make([]byte, 1500)
			for {
				if _, _, err := rtpSender.Read(rtcpBuf); err != nil {
					return
				}
			}
		}()
	}
	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: string(body)}); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	answer, err := pc.CreateAnswer(nil)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(answer); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	select {
	case <-gathered:
	case <-r.Context().Done():
		return
	}

	id := uuid.NewString()
	s.sessionsMu.Lock()
	if s.closed {
		s.sessionsMu.Unlock()
		http.Error(w, "closed", http.StatusServiceUnavailable)
		return
	}
	s.sessions[id] = pc
	s.sessionsMu.Unlock()
	ok = true

	// drop dead sessions so that clients that never DELETE don't leak.
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		if state == webrtc.PeerConnectionStateFailed || state == webrtc.PeerConnectionStateClosed {
			s.sessionsMu.Lock()
			delete(s.sessions, id)
			s.sessionsMu.Unlock()
			pc.Close()
		}
	})

	w.Header().Set("Content-Type", "application/sdp")
	w.Header().Set("Location", "/"+id)
	w.WriteHeader(http.StatusCreated)
	fmt.Fprint(w, pc.LocalDescription().SDP)
}

func (s *WHEPSink) WriteSample(i int, buf []byte, ptsMicroseconds int64) (bool, error) {
	if i < 0 || i >= len(s.tracks) {
		return false, fmt.Errorf("track index out of range: %d", i)
	}
	t := s.tracks[i]

	s.trackMu.Lock()
	if t.ptsMicroseconds == 0 {
		t.ptsMicroseconds = ptsMicroseconds
	}
	duration := time.Duration(ptsMicroseconds-t.ptsMicroseconds) * time.Microsecond
	t.ptsMicroseconds = ptsMicroseconds
	s.trackMu.Unlock()
	return false, t.track.WriteSample(media.Sample{Data: buf, Duration: duration})
}

func (s *WHEPSink) Close() error {
	err := s.server.Shutdown(context.Background())
	// Serve may not have started yet, in which case nothing else would close the listener.
	s.listener.Close()
	s.sessionsMu.Lock()
	s.closed = true
	sessions := s.sessions
	s.sessions = map[string]*webrtc.PeerConnection{}
	s.sessionsMu.Unlock()
	for _, pc := range sessions {
		pc.Close()
	}
	return err
}
