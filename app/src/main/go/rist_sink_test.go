//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"fmt"
	"testing"
)

// After a write error the sink must keep working: bufio's sticky error used
// to make every later sample fail.
func TestRISTSink_RecoversAfterWriteError(t *testing.T) {
	port, err := pickFreeUDPPort()
	if err != nil {
		t.Fatal(err)
	}
	sink, err := NewRISTSink(fmt.Sprintf("rist://127.0.0.1:%d", port), string(MediaFormatMimeTypeVideoH264))
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()

	// Swap in a muxer whose output fails, as librist would on a send error.
	w := &flakyWriter{fail: true}
	sink.mux = newTSMux(w, ristMPEGTSChunk, sink.tracks)
	key := int32(MediaCodecBufferFlagKeyFrame)
	if err := sink.WriteSample(0, testAnnexB, 0, key); err == nil {
		t.Fatal("expected write error")
	}

	w.fail = false
	if err := sink.WriteSample(0, testAnnexB, 33_000, key); err != nil {
		t.Fatalf("write after error: %v", err)
	}
	assertMPEGTS(t, w.got.Bytes())
}

func TestRISTSink_CloseIdempotent(t *testing.T) {
	port, err := pickFreeUDPPort()
	if err != nil {
		t.Fatal(err)
	}
	sink, err := NewRISTSink(fmt.Sprintf("rist://127.0.0.1:%d", port), string(MediaFormatMimeTypeVideoH264))
	if err != nil {
		t.Fatal(err)
	}
	if err := sink.Close(); err != nil {
		t.Fatal(err)
	}
	if err := sink.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
	if err := sink.WriteSample(0, testAnnexB, 0, 0); err == nil {
		t.Fatal("write on closed sink should fail")
	}
}
