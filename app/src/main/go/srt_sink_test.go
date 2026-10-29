//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"fmt"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

func srtRefCount() int {
	srtRefs.Lock()
	defer srtRefs.Unlock()
	return srtRefs.n
}

func TestSRTSocketOptions(t *testing.T) {
	for _, tc := range []struct {
		query string
		ok    bool
	}{
		{"latency=120&transtype=live&tlpktdrop=0&tsbpdmode=true", true},
		{"transtype=file&maxbw=-1&inputbw=5000000", true},
		{"passphrase=&streamid=abc", true},
		{"mode=caller&unknownopt=whatever", true}, // unknown names are ignored
		{"", true},
		{"latency=abc", false},
		{"latency=", false},
		{"latency=1.5", false},
		{"latency=99999999999", false}, // overflows int32
		{"maxbw=lots", false},
		{"tlpktdrop=maybe", false},
		{"tsbpdmode=2", false},
		{"transtype=bogus", false},
		{"transtype=LIVE", false},
	} {
		q, err := url.ParseQuery(tc.query)
		if err != nil {
			t.Fatal(err)
		}
		opts := map[string]string{}
		for k, v := range q {
			opts[k] = v[0]
		}
		err = validateSocketOptions(opts)
		if (err == nil) != tc.ok {
			t.Errorf("%q: err = %v, want ok=%v", tc.query, err, tc.ok)
		}
	}
}

func TestSRTEncodeSocketOption(t *testing.T) {
	byName := func(n string) socketOption {
		for _, so := range SocketOptions {
			if so.name == n {
				return so
			}
		}
		t.Fatalf("no option %s", n)
		return socketOption{}
	}
	for _, tc := range []struct {
		name, val string
		size      int
	}{
		{"latency", "120", 4}, {"maxbw", "-1", 8}, {"tlpktdrop", "1", 1},
		{"transtype", "live", 4}, {"streamid", "abc", 3}, {"passphrase", "", 0},
	} {
		b, err := encodeSocketOption(byName(tc.name), tc.val)
		if err != nil || len(b) != tc.size {
			t.Errorf("%s=%q: len=%d err=%v, want len %d", tc.name, tc.val, len(b), err, tc.size)
		}
	}
}

// Bad options must fail construction up front and not leak a refcount.
func TestNewSRTSink_BadOptions(t *testing.T) {
	before := srtRefCount()
	for _, u := range []string{
		"srt://127.0.0.1:9?latency=abc",
		"srt://127.0.0.1:9?transtype=bogus",
		"srt://127.0.0.1:9?tlpktdrop=maybe",
		"srt://127.0.0.1:notaport",
	} {
		if sink, err := NewSRTSink(u, string(MediaFormatMimeTypeVideoH264)); err == nil {
			sink.Close()
			t.Errorf("%s: expected error", u)
		}
	}
	if n := srtRefCount(); n != before {
		t.Errorf("refcount %d, want %d", n, before)
	}
}

// Close is idempotent and each sink releases exactly one reference, so
// closing one sink twice must not tear down libsrt under another.
func TestSRTSink_CloseRefcount(t *testing.T) {
	before := srtRefCount()
	newSink := func() (*SRTSink, chan []byte) {
		l, err := newSRTTestListener()
		if err != nil {
			t.Fatal(err)
		}
		ch := make(chan []byte, 1)
		go func() {
			c, err := l.Accept()
			if err != nil {
				ch <- nil
				return
			}
			ch <- c.Drain()
			c.Close()
			l.Close()
		}()
		s, err := NewSRTSink(fmt.Sprintf("srt://127.0.0.1:%d?tlpktdrop=0", l.Port()), string(MediaFormatMimeTypeVideoH264))
		if err != nil {
			t.Fatal(err)
		}
		return s, ch
	}
	a, _ := newSink()
	b, bch := newSink()
	if n := srtRefCount(); n != before+2 {
		t.Fatalf("refcount %d, want %d", n, before+2)
	}

	a.Close()
	a.Close()
	if n := srtRefCount(); n != before+1 {
		t.Fatalf("after double close: refcount %d, want %d", n, before+1)
	}
	if err := a.WriteSample(0, testAnnexB, 0, 0); err == nil {
		t.Fatal("write on closed sink should fail")
	}

	// b must still work.
	for i := 0; i < 5; i++ {
		if err := b.WriteSample(0, testAnnexB, int64(i)*33_000, int32(MediaCodecBufferFlagKeyFrame)); err != nil {
			t.Fatalf("b.WriteSample: %v", err)
		}
		time.Sleep(20 * time.Millisecond)
	}
	time.Sleep(300 * time.Millisecond)
	b.Close()
	if n := srtRefCount(); n != before {
		t.Fatalf("refcount %d, want %d", n, before)
	}
	select {
	case got := <-bch:
		assertMPEGTS(t, got)
	case <-time.After(7 * time.Second):
		t.Fatal("listener b did not finish")
	}
}

// Audio and video writers hammer the sink while the listener dies and comes
// back. Exactly one reconnect must happen (one new connection accepted), the
// stream must resume, and after Close no connection may stay open.
func TestSRTSink_ConcurrentWritersReconnect(t *testing.T) {
	l1, err := newSRTTestListener()
	if err != nil {
		t.Fatal(err)
	}
	port := l1.Port()
	c1ch := make(chan *srtTestConn, 1)
	go func() {
		c, _ := l1.Accept()
		c1ch <- c
	}()

	sink, err := NewSRTSink(
		fmt.Sprintf("srt://127.0.0.1:%d?transtype=live&tlpktdrop=0&conntimeo=500", port),
		string(MediaFormatMimeTypeVideoH264)+";"+string(MediaFormatMimeTypeAudioOpus))
	if err != nil {
		t.Fatal(err)
	}
	c1 := <-c1ch
	if c1 == nil {
		t.Fatal("first accept failed")
	}
	drained1 := make(chan struct{})
	go func() { c1.Drain(); close(drained1) }()

	stop := make(chan struct{})
	var wg sync.WaitGroup
	for track, frame := range [][]byte{testAnnexB, {0xfc, 0xff, 0xfe}} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for pts := int64(0); ; pts += 20_000 {
				select {
				case <-stop:
					return
				default:
				}
				if err := sink.WriteSample(track, frame, pts, int32(MediaCodecBufferFlagKeyFrame)); err != nil {
					t.Errorf("track %d WriteSample: %v", track, err)
					return
				}
				time.Sleep(10 * time.Millisecond)
			}
		}()
	}

	time.Sleep(300 * time.Millisecond)
	// Kill the listener and the live connection, stay down long enough for
	// at least one failed reconnect attempt.
	c1.Close()
	l1.Close()
	<-drained1
	time.Sleep(1500 * time.Millisecond)

	var l2 *srtTestListener
	for deadline := time.Now().Add(5 * time.Second); ; time.Sleep(100 * time.Millisecond) {
		if l2, err = newSRTTestListenerOn(port); err == nil {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("rebind port %d: %v", port, err)
		}
	}

	var mu sync.Mutex
	var accepted int
	var drains []chan []byte
	go func() {
		for {
			c, err := l2.Accept()
			if err != nil {
				return
			}
			ch := make(chan []byte, 1)
			mu.Lock()
			accepted++
			drains = append(drains, ch)
			mu.Unlock()
			go func() { ch <- c.Drain(); c.Close() }()
		}
	}()

	// Recovery: wait for the stream to resume, then give a duplicate
	// reconnect owner time to show up as a second connection.
	timeout := time.After(20 * time.Second)
	for {
		mu.Lock()
		n := accepted
		mu.Unlock()
		if n > 0 {
			break
		}
		select {
		case <-timeout:
			t.Fatal("sink did not reconnect")
		case <-time.After(50 * time.Millisecond):
		}
	}
	time.Sleep(2 * time.Second)

	close(stop)
	wg.Wait()
	// srt_cleanup (run by the last Close) blocks while an accept is pending.
	l2.Close()
	if err := sink.Close(); err != nil {
		t.Fatal(err)
	}
	sink.Close()

	mu.Lock()
	defer mu.Unlock()
	if accepted != 1 {
		t.Errorf("accepted %d reconnects, want exactly 1", accepted)
	}
	// Every connection must end once the sink is closed; a leaked socket
	// would keep its drain blocked.
	for _, ch := range drains {
		select {
		case got := <-ch:
			assertMPEGTS(t, got)
		case <-time.After(7 * time.Second):
			t.Fatal("connection still open after Close: leaked socket")
		}
	}
}

// Closing while a writer owns the reconnect loop must interrupt it and still
// release the library reference exactly once.
func TestSRTSink_CloseDuringReconnect(t *testing.T) {
	before := srtRefCount()
	l, err := newSRTTestListener()
	if err != nil {
		t.Fatal(err)
	}
	cch := make(chan *srtTestConn, 1)
	go func() { c, _ := l.Accept(); cch <- c }()
	sink, err := NewSRTSink(fmt.Sprintf("srt://127.0.0.1:%d?conntimeo=500", l.Port()), string(MediaFormatMimeTypeVideoH264))
	if err != nil {
		t.Fatal(err)
	}
	c := <-cch
	c.Close()
	l.Close()
	time.Sleep(300 * time.Millisecond)

	done := make(chan error, 1)
	go func() {
		var err error
		for i := 0; err == nil && i < 500; i++ {
			err = sink.WriteSample(0, testAnnexB, int64(i)*33_000, int32(MediaCodecBufferFlagKeyFrame))
			time.Sleep(10 * time.Millisecond)
		}
		done <- err
	}()
	time.Sleep(800 * time.Millisecond) // writer is now in the reconnect loop
	sink.Close()
	select {
	case err := <-done:
		if err == nil {
			t.Fatal("expected writer to fail after Close")
		}
	case <-time.After(10 * time.Second):
		t.Fatal("reconnect loop did not stop on Close")
	}
	// The reconnect goroutine releases the reference when it notices the close.
	deadline := time.Now().Add(5 * time.Second)
	for srtRefCount() != before {
		if time.Now().After(deadline) {
			t.Fatalf("refcount %d, want %d", srtRefCount(), before)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// A broken connection must not stall the caller: WriteSample drops samples
// and returns immediately while the reconnect runs in the background. (The
// first reconnect attempt only starts after a 500ms backoff, so a blocking
// WriteSample takes at least that long.)
func TestSRTSink_WriteSampleDoesNotBlockWhileReconnecting(t *testing.T) {
	l, err := newSRTTestListener()
	if err != nil {
		t.Fatal(err)
	}
	cch := make(chan *srtTestConn, 1)
	go func() { c, _ := l.Accept(); cch <- c }()
	sink, err := NewSRTSink(fmt.Sprintf("srt://127.0.0.1:%d?conntimeo=500", l.Port()), string(MediaFormatMimeTypeVideoH264))
	if err != nil {
		t.Fatal(err)
	}
	c := <-cch
	go c.Drain()
	go func() {
		time.Sleep(300 * time.Millisecond)
		c.Close()
		l.Close()
	}()

	// The listener dies 300ms in; keep writing well past that.
	var slowest time.Duration
	done := make(chan error, 1)
	go func() {
		for i := 0; i < 300; i++ {
			start := time.Now()
			if err := sink.WriteSample(0, testAnnexB, int64(i)*33_000, int32(MediaCodecBufferFlagKeyFrame)); err != nil {
				done <- err
				return
			}
			slowest = max(slowest, time.Since(start))
			time.Sleep(10 * time.Millisecond)
		}
		done <- nil
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("WriteSample: %v", err)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("WriteSample stalled while reconnecting")
	}
	if slowest > 200*time.Millisecond {
		t.Errorf("WriteSample blocked for %v while reconnecting", slowest)
	}
	sink.Close()
}

// MediaCodec emits its codec-config buffer once; one that arrives while the
// sink is reconnecting must still be kept and carried over.
func TestSRTSink_CodecConfigDuringReconnect(t *testing.T) {
	sink := &SRTSink{
		tracks: []*mpegts.Track{{Codec: &mpegts.CodecH264{}}},
		done:   make(chan struct{}),
		fd:     srtInvalidSock,
	}
	sink.mux = newTSMux(&flakyWriter{}, 1316, sink.tracks)
	sink.reconnecting = true

	if err := sink.WriteSample(0, testAnnexB[:22], 0, int32(MediaCodecBufferFlagCodecConfig)); err != nil {
		t.Fatal(err)
	}
	if len(sink.mux.paramSets) != 2 {
		t.Errorf("param sets = %d, want 2: config buffer dropped while reconnecting", len(sink.mux.paramSets))
	}
}
