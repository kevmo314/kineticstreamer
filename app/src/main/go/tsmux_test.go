//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"bytes"
	"errors"
	"testing"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

var testAnnexB = []byte{
	0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
	0x96, 0x35, 0x40, 0xa0, 0x0b, 0x6a,
	0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x06, 0xe2,
	0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x00, 0x33,
}

func TestPTSTo90k(t *testing.T) {
	for _, tc := range []struct{ us, want int64 }{
		{0, 0},
		{1_000_000, 90_000},
		{33_333, 2_999},
		{-1_000_000, -90_000},
		{8_640_000_000_000, 777_600_000_000},
		{1<<53 + 1, (1<<53 + 1) * 9 / 100},
	} {
		if got := ptsTo90k(tc.us); got != tc.want {
			t.Errorf("ptsTo90k(%d) = %d, want %d", tc.us, got, tc.want)
		}
	}
}

// flakyWriter fails every write while fail is set, and records the rest.
type flakyWriter struct {
	fail bool
	got  bytes.Buffer
}

func (w *flakyWriter) Write(p []byte) (int, error) {
	if w.fail {
		return 0, errors.New("boom")
	}
	return w.got.Write(p)
}

// A write error must not poison the muxer: bufio keeps its first error, so
// without a reset every later sample would fail forever.
func TestTSMux_RecoversAfterWriteError(t *testing.T) {
	w := &flakyWriter{}
	track := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	m := newTSMux(w, 1316, []*mpegts.Track{track})
	key := int32(MediaCodecBufferFlagKeyFrame)

	if err := m.writeSample(track, testAnnexB, 0, key); err != nil {
		t.Fatalf("healthy write: %v", err)
	}

	w.fail = true
	w.got.Reset()
	for i := 0; i < 2; i++ {
		if err := m.writeSample(track, testAnnexB, 33_000, key); err == nil {
			t.Fatal("expected write error")
		}
	}

	w.fail = false
	if err := m.writeSample(track, testAnnexB, 66_000, key); err != nil {
		t.Fatalf("write after recovery: %v", err)
	}
	assertMPEGTS(t, w.got.Bytes())
}

func TestTSMux_IgnoresSampleWithoutNALUs(t *testing.T) {
	w := &flakyWriter{}
	track := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	m := newTSMux(w, 1316, []*mpegts.Track{track})
	if err := m.writeSample(track, []byte{1, 2, 3}, 0, 0); err != nil {
		t.Fatalf("no start code: %v", err)
	}
	if w.got.Len() != 0 {
		t.Fatalf("expected no output, got %d bytes", w.got.Len())
	}
}
