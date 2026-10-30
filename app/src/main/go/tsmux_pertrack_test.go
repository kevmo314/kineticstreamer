//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"testing"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

// With several video tracks, one track's keyframe must not let delta frames through on the others.
func TestTSMux_KeyframeGatingIsPerTrack(t *testing.T) {
	w := &flakyWriter{}
	a := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	b := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	m := newTSMux(w, 1316, []*mpegts.Track{a, b})
	idr := []byte{0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x33}
	deltaFrame := []byte{0, 0, 0, 1, 0x41, 0x9a, 0x00}

	if err := m.writeSample(a, idr, 0, int32(MediaCodecBufferFlagKeyFrame)); err != nil {
		t.Fatal(err)
	}
	n := w.got.Len()
	if err := m.writeSample(b, deltaFrame, 33_000, 0); err != nil || w.got.Len() != n {
		t.Fatalf("delta frame on track b written before its keyframe: err=%v bytes=%d->%d", err, n, w.got.Len())
	}
	if err := m.writeSample(a, deltaFrame, 33_000, 0); err != nil || w.got.Len() == n {
		t.Fatalf("delta frame on track a dropped after its keyframe: err=%v", err)
	}
}
