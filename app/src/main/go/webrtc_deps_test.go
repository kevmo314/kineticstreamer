package kinetic

import (
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

// TestWebRTCLoopbackDataChannel exercises the pion ICE/DTLS/SCTP stack end to
// end so that dependency bumps (dtls, stun, sctp, ...) are covered by a test.
func TestWebRTCLoopbackDataChannel(t *testing.T) {
	se := webrtc.SettingEngine{}
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	api := webrtc.NewAPI(webrtc.WithSettingEngine(se))

	offerer, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer offerer.Close()
	answerer, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	defer answerer.Close()

	got := make(chan string, 1)
	answerer.OnDataChannel(func(dc *webrtc.DataChannel) {
		dc.OnMessage(func(m webrtc.DataChannelMessage) { got <- string(m.Data) })
	})

	dc, err := offerer.CreateDataChannel("test", nil)
	if err != nil {
		t.Fatal(err)
	}
	dc.OnOpen(func() { _ = dc.SendText("hello") })

	offer, err := offerer.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	offerDone := webrtc.GatheringCompletePromise(offerer)
	if err := offerer.SetLocalDescription(offer); err != nil {
		t.Fatal(err)
	}
	<-offerDone
	if err := answerer.SetRemoteDescription(*offerer.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, err := answerer.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	answerDone := webrtc.GatheringCompletePromise(answerer)
	if err := answerer.SetLocalDescription(answer); err != nil {
		t.Fatal(err)
	}
	<-answerDone
	if err := offerer.SetRemoteDescription(*answerer.LocalDescription()); err != nil {
		t.Fatal(err)
	}

	select {
	case msg := <-got:
		if msg != "hello" {
			t.Fatalf("got %q, want hello", msg)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for data channel message")
	}
}
