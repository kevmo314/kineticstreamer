//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"fmt"
	"testing"
	"time"
)

// TestSRTSink_RoundTrip starts a libsrt listener on localhost, connects an
// SRTSink to it, writes a fake H.264 keyframe, and verifies the listener
// actually receives MPEG-TS packets.
func TestSRTSink_RoundTrip(t *testing.T) {
	listener, err := newSRTTestListener()
	if err != nil {
		t.Fatalf("listener: %v", err)
	}
	t.Logf("SRT listener bound to 127.0.0.1:%d", listener.Port())

	type recvResult struct {
		bytes []byte
		err   error
	}
	recvCh := make(chan recvResult, 1)
	go func() {
		// Drain at least 8 packets so we can verify multiple sync bytes.
		got, err := listener.AcceptAndDrain(188 * 8)
		recvCh <- recvResult{bytes: got, err: err}
	}()

	// transtype=live + tlpktdrop=0: don't drop packets we couldn't deliver in
	// the SRT latency window. We're feeding only one keyframe worth of data,
	// and tlpktdrop's default would discard those packets if the test holds
	// the lock (e.g. under -race) for longer than ~80ms.
	sinkURL := fmt.Sprintf("srt://127.0.0.1:%d?transtype=live&tlpktdrop=0", listener.Port())
	sink, err := NewSRTSink(sinkURL, string(MediaFormatMimeTypeVideoH264))
	if err != nil {
		t.Fatalf("NewSRTSink: %v", err)
	}

	// A minimal Annex-B keyframe: SPS + PPS + IDR slice. The byte values
	// don't form a decodable stream — the sink just needs to mux them
	// into a TS payload.
	annexB := []byte{
		0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
		0x96, 0x35, 0x40, 0xa0, 0x0b, 0x6a,
		0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x06, 0xe2,
		0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x00, 0x33,
	}

	// Send a few frames so the muxer emits PAT/PMT plus a couple of PES
	// packets and the listener can verify multiple TS sync bytes.
	for i := 0; i < 5; i++ {
		pts := int64(i) * 33_000 // ~30fps
		if err := sink.WriteSample(0, annexB, pts, int32(MediaCodecBufferFlagKeyFrame)); err != nil {
			t.Fatalf("WriteSample[%d]: %v", i, err)
		}
	}

	// Give SRT time to flush the live queue before we tear it down.
	time.Sleep(250 * time.Millisecond)
	if err := sink.Close(); err != nil {
		t.Fatalf("sink.Close: %v", err)
	}

	select {
	case res := <-recvCh:
		if res.err != nil {
			t.Fatalf("listener: %v", res.err)
		}
		assertMPEGTS(t, res.bytes)
	case <-time.After(7 * time.Second):
		t.Fatal("listener did not return in time")
	}
}

func assertMPEGTS(t *testing.T, got []byte) {
	t.Helper()
	if len(got) < 188 {
		t.Fatalf("expected at least one MPEG-TS packet (188 bytes), got %d", len(got))
	}
	if got[0] != 0x47 {
		t.Fatalf("expected MPEG-TS sync byte 0x47 at offset 0, got 0x%02x", got[0])
	}
	for off := 188; off < len(got); off += 188 {
		if got[off] != 0x47 {
			end := off + 8
			if end > len(got) {
				end = len(got)
			}
			t.Fatalf("expected 0x47 at offset %d, got 0x%02x (context: %x)",
				off, got[off], got[off:end])
		}
	}
	t.Logf("received %d bytes (%d MPEG-TS packets) over SRT", len(got), len(got)/188)
}
