//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"bytes"
	"testing"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

// readVideoAUs demuxes the H.264 access units (as lists of NAL unit types) from MPEG-TS.
func readVideoAUs(t *testing.T, ts []byte) [][]byte {
	t.Helper()
	r, err := mpegts.NewReader(bytes.NewReader(ts))
	if err != nil {
		t.Fatal(err)
	}
	var aus [][]byte
	r.OnDataH26x(r.Tracks()[0], func(_, _ int64, au [][]byte) error {
		var types []byte
		for _, n := range au {
			types = append(types, n[0]&0x1f)
		}
		aus = append(aus, types)
		return nil
	})
	for r.Read() == nil {
	}
	return aus
}

// A stream must start on a keyframe, codec-config buffers are not frames, and keyframes that
// arrive without parameter sets (some encoders don't prepend them) get the cached ones.
func TestTSMux_StartsOnKeyframeWithParameterSets(t *testing.T) {
	w := &flakyWriter{}
	track := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	m := newTSMux(w, 1316, []*mpegts.Track{track})
	paramSets := testAnnexB[:22] // SPS + PPS
	idr := []byte{0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x33}
	deltaFrame := []byte{0, 0, 0, 1, 0x41, 0x9a, 0x00}
	key, config := int32(MediaCodecBufferFlagKeyFrame), int32(MediaCodecBufferFlagCodecConfig)

	for i, s := range []struct {
		buf   []byte
		flags int32
	}{{append([]byte(nil), paramSets...), config}, {deltaFrame, 0}, {idr, key}, {deltaFrame, 0}} {
		if err := m.writeSample(track, s.buf, int64(i)*33_000, s.flags); err != nil {
			t.Fatal(err)
		}
		if i == 0 {
			clear(s.buf) // the caller reuses its buffer after the call
		}
	}
	got := readVideoAUs(t, w.got.Bytes())
	want := [][]byte{{7, 8, 5}, {1}}
	if len(got) != len(want) {
		t.Fatalf("access units %v, want %v", got, want)
	}
	for i := range want {
		if !bytes.Equal(got[i], want[i]) {
			t.Fatalf("access units %v, want %v", got, want)
		}
	}

	// After a reset (reconnect) the stream must again wait for a keyframe.
	w.got.Reset()
	m.reset()
	if err := m.writeSample(track, deltaFrame, 200_000, 0); err != nil || w.got.Len() != 0 {
		t.Fatalf("delta frame written on a fresh stream: err=%v bytes=%d", err, w.got.Len())
	}
}

func TestTSMux_DropsAudioConfig(t *testing.T) {
	w := &flakyWriter{}
	track := &mpegts.Track{Codec: &mpegts.CodecMPEG4Audio{}}
	m := newTSMux(w, 1316, []*mpegts.Track{track})
	if err := m.writeSample(track, []byte{0x11, 0x90}, 0, int32(MediaCodecBufferFlagCodecConfig)); err != nil {
		t.Fatal(err)
	}
	if w.got.Len() != 0 {
		t.Fatalf("audio config written to the stream (%d bytes)", w.got.Len())
	}
}
