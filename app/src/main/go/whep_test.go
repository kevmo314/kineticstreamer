package kinetic

import (
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

// whepOffer creates a recvonly offer with ICE gathering complete.
func whepOffer(t *testing.T) (*webrtc.PeerConnection, string) {
	t.Helper()
	pc, err := webrtc.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	if _, err := pc.AddTransceiverFromKind(webrtc.RTPCodecTypeVideo, webrtc.RTPTransceiverInit{Direction: webrtc.RTPTransceiverDirectionRecvonly}); err != nil {
		t.Fatal(err)
	}
	offer, err := pc.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	gathered := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	<-gathered
	return pc, pc.LocalDescription().SDP
}

func TestWHEPOfferAnswer(t *testing.T) {
	sink, err := newWHEPSink("127.0.0.1:0", "video/x-vnd.on2.vp8", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()
	base := "http://" + sink.Addr().String()

	client, offer := whepOffer(t)
	resp, err := http.Post(base+"/", "application/sdp", strings.NewReader(offer))
	if err != nil {
		t.Fatal(err)
	}
	answer, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("got %d: %s", resp.StatusCode, answer)
	}
	if resp.Header.Get("Content-Type") != "application/sdp" {
		t.Errorf("content type %q", resp.Header.Get("Content-Type"))
	}
	location := resp.Header.Get("Location")
	if location == "" {
		t.Fatal("missing Location")
	}
	if err := client.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: string(answer)}); err != nil {
		t.Fatalf("answer rejected by client: %v\n%s", err, answer)
	}
	if !strings.Contains(string(answer), "VP8") || !strings.Contains(string(answer), "sendonly") {
		t.Errorf("unexpected answer:\n%s", answer)
	}

	sink.sessionsMu.Lock()
	n := len(sink.sessions)
	sink.sessionsMu.Unlock()
	if n != 1 {
		t.Fatalf("%d sessions, want 1", n)
	}

	// writing samples with an active session (and out of range tracks) is safe.
	if _, err := sink.WriteSample(0, []byte{1, 2, 3}, 1000); err != nil {
		t.Fatal(err)
	}
	if _, err := sink.WriteSample(0, []byte{1, 2, 3}, 34000); err != nil {
		t.Fatal(err)
	}
	if _, err := sink.WriteSample(1, nil, 0); err == nil {
		t.Fatal("expected out of range error")
	}

	// DELETE tears the session down; a second DELETE is a 404.
	for _, want := range []int{http.StatusOK, http.StatusNotFound} {
		req, _ := http.NewRequest(http.MethodDelete, base+location, nil)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != want {
			t.Fatalf("DELETE: got %d, want %d", resp.StatusCode, want)
		}
	}
	sink.sessionsMu.Lock()
	n = len(sink.sessions)
	sink.sessionsMu.Unlock()
	if n != 0 {
		t.Errorf("%d sessions left", n)
	}
}

func TestWHEPBadRequests(t *testing.T) {
	sink, err := newWHEPSink("127.0.0.1:0", "video/x-vnd.on2.vp8", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()
	base := "http://" + sink.Addr().String()

	resp, err := http.Post(base+"/", "application/sdp", strings.NewReader("not sdp"))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("garbage offer: got %d", resp.StatusCode)
	}
	if resp, err = http.Get(base + "/"); err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("GET: got %d", resp.StatusCode)
	}
	sink.sessionsMu.Lock()
	defer sink.sessionsMu.Unlock()
	if len(sink.sessions) != 0 {
		t.Errorf("failed offer leaked %d sessions", len(sink.sessions))
	}
}

func TestWHEPCloseClosesSessionsAndPort(t *testing.T) {
	sink, err := newWHEPSink("127.0.0.1:0", "video/x-vnd.on2.vp8", nil)
	if err != nil {
		t.Fatal(err)
	}
	addr := sink.Addr().String()
	_, offer := whepOffer(t)
	resp, err := http.Post("http://"+addr+"/", "application/sdp", strings.NewReader(offer))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("got %d", resp.StatusCode)
	}
	sink.sessionsMu.Lock()
	var pc *webrtc.PeerConnection
	for _, pc = range sink.sessions {
	}
	sink.sessionsMu.Unlock()

	if err := sink.Close(); err != nil {
		t.Fatal(err)
	}
	if pc.ConnectionState() != webrtc.PeerConnectionStateClosed {
		t.Errorf("session state %v after Close", pc.ConnectionState())
	}
	var l net.Listener
	for i := 0; i < 20; i++ {
		if l, err = net.Listen("tcp", addr); err == nil {
			l.Close()
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("port still bound: %v", err)
}
