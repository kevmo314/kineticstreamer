package kinetic

import (
	"bytes"
	"net/http"
	"os"
	"slices"
	"testing"

	"github.com/bluenviron/mediacommon/pkg/codecs/mpeg4audio"
	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

const configFlag = int32(MediaCodecBufferFlagCodecConfig)

// The encoder emits SPS/PPS and the AAC config once, before the first keyframe. Every
// file must start with them, and playback readers must not see them as samples.
func TestBinaryDumpRepeatsCodecConfig(t *testing.T) {
	sink := NewBinaryDumpSink(t.TempDir(), "video/avc;audio/mp4a-latm")
	buf := []byte{0, 0, 0, 1, 0x67, 1}
	for _, w := range []struct {
		track int
		data  []byte
		pts   int64
		flags int32
	}{
		{0, buf, 0, configFlag},
		{1, []byte{0x12, 0x08}, 0, configFlag},
		{0, []byte("idr1"), 100, keyFrame},
		{0, []byte("p"), 133, 0},
		{0, []byte("idr2"), 200, keyFrame},
	} {
		if err := sink.WriteSample(w.track, w.data, w.pts, w.flags); err != nil {
			t.Fatal(err)
		}
		buf[5] = 0 // the caller reuses its buffer
	}
	sink.Close()

	manifest, err := sink.ReadManifest()
	if err != nil || len(manifest) != 2 {
		t.Fatalf("manifest %v, %v", manifest, err)
	}
	for i, e := range manifest {
		f, err := os.Open(e.FileAbsolutePath)
		if err != nil {
			t.Fatal(err)
		}
		var got []*BinaryDumpSample
		for {
			s, err := decodeRecord(f)
			if err != nil {
				break
			}
			got = append(got, s)
		}
		f.Close()
		if len(got) < 3 || got[0].Track != 0 || got[0].Flags != MediaCodecBufferFlagCodecConfig ||
			!bytes.Equal(got[0].Data, []byte{0, 0, 0, 1, 0x67, 1}) || got[0].PTS != e.PTS ||
			got[1].Track != 1 || !bytes.Equal(got[1].Data, []byte{0x12, 0x08}) ||
			got[2].Flags != MediaCodecBufferFlagKeyFrame {
			t.Errorf("file %d does not start with the config: %+v", i, got)
		}
	}

	var data []string
	for _, s := range readAll(t, sink, 0) {
		data = append(data, string(s.Data))
	}
	if want := []string{"idr1", "p", "idr2"}; !slices.Equal(data, want) {
		t.Errorf("reader returned %q, want %q", data, want)
	}
}

func getTS(t *testing.T, sink *HLSSink, name string) *mpegts.Reader {
	t.Helper()
	status, _, body := get(t, sink, "/"+name)
	if status != http.StatusOK {
		t.Fatalf("%s: %d", name, status)
	}
	r, err := mpegts.NewReader(bytes.NewReader([]byte(body)))
	if err != nil {
		t.Fatal(err)
	}
	return r
}

// The IDR frames below carry no SPS/PPS of their own, as with MediaCodec. Each segment
// must still start with them so that it can be decoded alone.
func TestHLSSegmentsStartWithParameterSets(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "video/avc")
	sps := []byte{0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f, 0x96, 0x35, 0x40, 0xa0, 0x0b, 0x6a}
	pps := []byte{0, 0, 0, 1, 0x68, 0xce, 0x06, 0xe2}
	idr := []byte{0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x33}
	if err := disk.WriteSample(0, append(append([]byte{}, sps...), pps...), 0, configFlag); err != nil {
		t.Fatal(err)
	}
	for _, pts := range []int64{1_000_000, 2_000_000} {
		if err := disk.WriteSample(0, idr, pts, keyFrame); err != nil {
			t.Fatal(err)
		}
	}
	disk.Close()
	sink, err := NewHLSSink(disk, "127.0.0.1:0", "", "video/avc")
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()

	for _, name := range []string{"1000000.ts", "2000000.ts"} {
		r := getTS(t, sink, name)
		var types []byte
		r.OnDataH26x(r.Tracks()[0], func(_, _ int64, au [][]byte) error {
			if types != nil {
				return nil
			}
			for _, n := range au {
				types = append(types, n[0]&0x1f)
			}
			return nil
		})
		for r.Read() == nil {
		}
		if !bytes.Equal(types, []byte{7, 8, 5}) {
			t.Errorf("%s: first access unit has NAL types %v, want SPS, PPS, IDR", name, types)
		}
	}
}

// The ADTS headers of a segment must follow the recorded AudioSpecificConfig.
func TestHLSSegmentUsesRecordedAACConfig(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/mp4a-latm")
	// AAC-LC, 44.1kHz, mono.
	if err := disk.WriteSample(0, []byte{0x12, 0x08}, 0, configFlag); err != nil {
		t.Fatal(err)
	}
	for i := int64(0); i < 3; i++ {
		if err := disk.WriteSample(0, []byte("aac-frame"), 1_000_000+i*23_220, keyFrame); err != nil {
			t.Fatal(err)
		}
	}
	disk.Close()
	sink, err := NewHLSSink(disk, "127.0.0.1:0", "", "audio/mp4a-latm")
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()

	// twice: the config of one request must not leak into the codec used by the next.
	for range 2 {
		r := getTS(t, sink, "1000000.ts")
		track := r.Tracks()[0]
		r.OnDataMPEG4Audio(track, func(int64, [][]byte) error { return nil })
		for r.Read() == nil {
		}
		conf := track.Codec.(*mpegts.CodecMPEG4Audio).Config
		if conf.SampleRate != 44100 || conf.ChannelCount != 1 || conf.Type != mpeg4audio.ObjectTypeAACLC {
			t.Errorf("config %+v want 44100 Hz mono", conf)
		}
	}
}

// Each video track keeps its own parameter sets.
func TestTSMuxParameterSetsPerTrack(t *testing.T) {
	a := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	b := &mpegts.Track{Codec: &mpegts.CodecH264{}}
	w := &flakyWriter{}
	m := newTSMux(w, 1316, []*mpegts.Track{a, b})
	idr := []byte{0, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x33}
	for _, s := range []struct {
		t     *mpegts.Track
		data  []byte
		flags int32
	}{
		{a, []byte{0, 0, 0, 1, 0x67, 0xaa}, configFlag},
		{b, []byte{0, 0, 0, 1, 0x67, 0xbb}, configFlag},
		{a, idr, keyFrame},
	} {
		if s.flags == keyFrame {
			m.reset() // e.g. after a write error: the encoder won't repeat its config
		}
		if err := m.writeSample(s.t, s.data, 0, s.flags); err != nil {
			t.Fatal(err)
		}
	}
	if got := w.got.Bytes(); !bytes.Contains(got, []byte{0x67, 0xaa}) || bytes.Contains(got, []byte{0x67, 0xbb}) {
		t.Error("track a's keyframe did not get exactly its own SPS")
	}
}
