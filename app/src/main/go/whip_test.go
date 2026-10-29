//go:build !android

package kinetic

import (
	"bytes"
	"io"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

type whipTestMode int

const (
	whipOK       whipTestMode = iota // real pion answerer
	whipNoICE                        // valid answer with candidates stripped, so ICE never completes
	whipHTTPFail                     // 500 (with a Location, so the sink should DELETE it)
)

type whipTestServer struct {
	*httptest.Server
	posts, deletes atomic.Int32
	pcs            sync.Map         // answerer peer connections, closed on DELETE
	packets        chan *rtp.Packet // video RTP received by the answerer
}

func newWHIPTestServer(t *testing.T, mode whipTestMode) *whipTestServer {
	t.Helper()
	s := &whipTestServer{packets: make(chan *rtp.Packet, 1024)}
	mux := http.NewServeMux()
	mux.HandleFunc("/resource", func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodDelete {
			s.deletes.Add(1)
			s.pcs.Range(func(k, _ any) bool { k.(*webrtc.PeerConnection).Close(); return true })
			w.WriteHeader(http.StatusOK)
		}
	})
	mux.HandleFunc("/whip", func(w http.ResponseWriter, r *http.Request) {
		s.posts.Add(1)
		w.Header().Set("Location", "/resource")
		if mode == whipHTTPFail {
			http.Error(w, "boom", http.StatusInternalServerError)
			return
		}
		offer, _ := io.ReadAll(r.Body)
		pc, err := webrtc.NewPeerConnection(webrtc.Configuration{})
		if err != nil {
			t.Error(err)
			return
		}
		s.pcs.Store(pc, nil)
		t.Cleanup(func() { pc.Close() })
		pc.OnTrack(func(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
			for {
				pkt, _, err := track.ReadRTP()
				if err != nil {
					return
				}
				if track.Kind() == webrtc.RTPCodecTypeVideo {
					select {
					case s.packets <- pkt:
					default:
					}
				}
			}
		})
		if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: string(offer)}); err != nil {
			t.Error(err)
			return
		}
		answer, err := pc.CreateAnswer(nil)
		if err != nil {
			t.Error(err)
			return
		}
		gathered := webrtc.GatheringCompletePromise(pc)
		if err := pc.SetLocalDescription(answer); err != nil {
			t.Error(err)
			return
		}
		<-gathered
		sdp := pc.LocalDescription().SDP
		if mode == whipNoICE {
			var kept []string
			for _, l := range strings.Split(sdp, "\r\n") {
				if !strings.HasPrefix(l, "a=candidate") {
					kept = append(kept, l)
				}
			}
			sdp = strings.Join(kept, "\r\n")
		}
		w.Header().Set("Content-Type", "application/sdp")
		w.WriteHeader(http.StatusCreated)
		w.Write([]byte(sdp))
	})
	s.Server = httptest.NewServer(mux)
	t.Cleanup(s.Server.Close)
	return s
}

const whipTestMimeTypes = string(MediaFormatMimeTypeVideoH264) + ";" + string(MediaFormatMimeTypeAudioOpus)

func shortWHIPTimeouts(t *testing.T, d time.Duration) {
	old := whipConnectTimeout
	whipConnectTimeout = d
	t.Cleanup(func() { whipConnectTimeout = old })
}

func nal(header byte, n int) []byte {
	b := append([]byte{0, 0, 0, 1, header}, bytes.Repeat([]byte{0xAA}, n)...)
	return b
}

func TestWHIPSink_ConnectAndWrite(t *testing.T) {
	srv := newWHIPTestServer(t, whipOK)
	sink, err := NewWHIPSink(srv.URL+"/whip", "tok", whipTestMimeTypes)
	if err != nil {
		t.Fatalf("NewWHIPSink: %v", err)
	}
	if got := sink.GetICEConnectionState(); got != "connected" {
		t.Errorf("ICE state = %q, want connected", got)
	}
	if err := sink.WriteOpus([]byte{1, 2, 3}, 0); err != nil {
		t.Errorf("WriteOpus: %v", err)
	}
	if _, err := sink.WriteH264(append(nal(0x65, 100), nal(0x41, 100)...), 0); err != nil {
		t.Errorf("WriteH264: %v", err)
	}
	if err := sink.Close(); err != nil {
		t.Errorf("Close: %v", err)
	}
	if srv.deletes.Load() != 1 {
		t.Errorf("DELETEs = %d, want 1", srv.deletes.Load())
	}
}

func TestWHIPSink_MarkerOnlyOnLastPacketOfFrame(t *testing.T) {
	srv := newWHIPTestServer(t, whipOK)
	sink, err := NewWHIPSink(srv.URL+"/whip", "", whipTestMimeTypes)
	if err != nil {
		t.Fatalf("NewWHIPSink: %v", err)
	}
	defer sink.Close()

	// The answerer only sees the track once RTP arrives, so send filler until
	// it does and then drain it.
	for warm := false; !warm; {
		sink.WriteH264(nal(0x41, 10), 0)
		select {
		case <-srv.packets:
			warm = true
		case <-time.After(50 * time.Millisecond):
		}
	}
	time.Sleep(100 * time.Millisecond)
	for len(srv.packets) > 0 {
		<-srv.packets
	}

	const frameUS = 33_333
	frames := [][]byte{
		// SPS + PPS + large IDR (fragmented into several FU-A packets)
		bytes.Join([][]byte{nal(0x67, 10), nal(0x68, 4), nal(0x65, 4000)}, nil),
		// SEI + small slice
		bytes.Join([][]byte{nal(0x06, 8), nal(0x41, 50)}, nil),
		// large slice
		nal(0x41, 4000),
	}
	for i, f := range frames {
		if _, err := sink.WriteH264(f, int64(i+1)*frameUS); err != nil {
			t.Fatalf("WriteH264: %v", err)
		}
	}

	var pkts []*rtp.Packet
	timeout := time.After(10 * time.Second)
	for markers := 0; markers < len(frames); {
		select {
		case p := <-srv.packets:
			pkts = append(pkts, p)
			if p.Marker {
				markers++
			}
		case <-timeout:
			t.Fatalf("timed out; got %d packets, %d frames marked", len(pkts), markers)
		}
	}
	for i, p := range pkts {
		last := i == len(pkts)-1 || pkts[i+1].Timestamp != p.Timestamp
		if p.Marker != last {
			t.Errorf("packet %d (ts %d): marker=%v, want %v", i, p.Timestamp, p.Marker, last)
		}
	}
	if len(pkts) < 2*len(frames) {
		t.Errorf("only %d packets; expected fragmentation", len(pkts))
	}
}

// waitGoroutines polls until the goroutine count is at most max.
func waitGoroutines(t *testing.T, max int) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for runtime.NumGoroutine() > max {
		if time.Now().After(deadline) {
			buf := make([]byte, 1<<16)
			t.Fatalf("goroutines = %d, want <= %d\n%s", runtime.NumGoroutine(), max, buf[:runtime.Stack(buf, true)])
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestWHIPSink_ConnectFailsWithoutLeaking(t *testing.T) {
	shortWHIPTimeouts(t, 2*time.Second)
	http500 := newWHIPTestServer(t, whipHTTPFail)
	noICE := newWHIPTestServer(t, whipNoICE)

	// Warm up so one-time goroutines (http transport, etc.) are in the baseline.
	if _, err := NewWHIPSink(http500.URL+"/whip", "", whipTestMimeTypes); err == nil {
		t.Fatal("expected error from HTTP 500")
	}
	whipHTTPClient.CloseIdleConnections()
	waitGoroutines(t, runtime.NumGoroutine())
	baseline := runtime.NumGoroutine()

	for i := 0; i < 3; i++ {
		if _, err := NewWHIPSink(http500.URL+"/whip", "", whipTestMimeTypes); err == nil {
			t.Fatal("expected error from HTTP 500")
		}
	}
	start := time.Now()
	if _, err := NewWHIPSink(noICE.URL+"/whip", "", whipTestMimeTypes); err == nil {
		t.Fatal("expected ICE timeout error")
	}
	if d := time.Since(start); d > 2*whipConnectTimeout {
		t.Errorf("connect took %v, want ~%v", d, whipConnectTimeout)
	}

	// Keep-alive HTTP connections are not leaks; everything else must be gone.
	whipHTTPClient.CloseIdleConnections()
	waitGoroutines(t, baseline)

	// Every created-but-unusable session must have been DELETEd.
	if p, d := http500.posts.Load(), http500.deletes.Load(); p != d {
		t.Errorf("HTTP-fail server: %d POSTs but %d DELETEs", p, d)
	}
	if p, d := noICE.posts.Load(), noICE.deletes.Load(); p != d {
		t.Errorf("no-ICE server: %d POSTs but %d DELETEs", p, d)
	}
}

func TestWHIPSink_CloseConcurrentWithWrites(t *testing.T) {
	srv := newWHIPTestServer(t, whipOK)
	sink, err := NewWHIPSink(srv.URL+"/whip", "", whipTestMimeTypes)
	if err != nil {
		t.Fatalf("NewWHIPSink: %v", err)
	}
	sink.SetPLICallback(func() {})

	stop := make(chan struct{})
	var wg sync.WaitGroup
	for _, write := range []func(int64){
		func(pts int64) { sink.WriteH264(append(nal(0x65, 2000), nal(0x41, 100)...), pts) },
		func(pts int64) { sink.WriteOpus([]byte{1, 2, 3, 4}, pts) },
		func(int64) { sink.GetICEConnectionState(); sink.GetPeerConnectionState() },
	} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for pts := int64(0); ; pts += 20_000 {
				select {
				case <-stop:
					return
				default:
					write(pts)
				}
			}
		}()
	}

	time.Sleep(200 * time.Millisecond)
	var closers sync.WaitGroup
	for i := 0; i < 2; i++ { // Close is idempotent and safe to race with itself
		closers.Add(1)
		go func() { defer closers.Done(); sink.Close() }()
	}
	closers.Wait()
	time.Sleep(100 * time.Millisecond) // writes after Close must be no-ops
	close(stop)
	wg.Wait()

	if srv.deletes.Load() != 1 {
		t.Errorf("DELETEs = %d, want 1", srv.deletes.Load())
	}
}

func TestSplitAnnexB(t *testing.T) {
	buf := []byte{0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 0}
	got := splitAnnexB(buf)
	want := [][]byte{{0x67, 1, 2}, {0x68, 3}, {0x65, 4}}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if !bytes.Equal(got[i], want[i]) {
			t.Errorf("nal %d = %v, want %v", i, got[i], want[i])
		}
	}
	if len(splitAnnexB([]byte{1, 2, 3})) != 0 {
		t.Error("expected no NALs without a start code")
	}
}
