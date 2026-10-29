package kinetic

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

const keyFrame = int32(MediaCodecBufferFlagKeyFrame)

func readAll(t *testing.T, sink *BinaryDumpSink, pts int64) []*BinaryDumpSample {
	t.Helper()
	r, err := sink.SampleReader(pts)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	var samples []*BinaryDumpSample
	for {
		s, err := r.Next()
		if errors.Is(err, io.EOF) {
			return samples
		}
		if err != nil {
			t.Fatalf("Next: %v", err)
		}
		samples = append(samples, s)
	}
}

func TestBinaryDumpRoundTrip(t *testing.T) {
	sink := NewBinaryDumpSink(t.TempDir(), "video/avc;audio/opus")
	// dropped: before the first keyframe.
	if err := sink.WriteSample(0, []byte("dropped"), 50, 0); err != nil {
		t.Fatal(err)
	}
	type rec struct {
		track int
		data  string
		pts   int64
		flags int32
	}
	want := []rec{
		{0, "k1", 100, keyFrame},
		{1, "audio1", 110, 0},
		{0, "p1", 120, 0},
		{0, "k2", 200, keyFrame},
		{1, "", 210, 0}, // empty payload
		{3, "p2", 220, 0},
	}
	for _, w := range want {
		if err := sink.WriteSample(w.track, []byte(w.data), w.pts, w.flags); err != nil {
			t.Fatal(err)
		}
	}
	if err := sink.Close(); err != nil {
		t.Fatal(err)
	}

	got := readAll(t, sink, 0)
	if len(got) != len(want) {
		t.Fatalf("got %d samples, want %d", len(got), len(want))
	}
	for i, w := range want {
		g := got[i]
		if g.Track != w.track || string(g.Data) != w.data || g.PTS != w.pts || int32(g.Flags) != w.flags {
			t.Errorf("sample %d: got %+v, want %+v", i, g, w)
		}
	}

	// seeking starts at the file containing the pts.
	if got := readAll(t, sink, 150); len(got) != 6 || got[0].PTS != 100 {
		t.Errorf("seek 150: %+v", got)
	}
	if got := readAll(t, sink, 250); len(got) != 3 || got[0].PTS != 200 {
		t.Errorf("seek 250: %+v", got)
	}
}

func TestBinaryDumpHeaderLayout(t *testing.T) {
	b := encodeRecord(2, MediaCodecBufferFlagKeyFrame, 0x0102030405, []byte{9, 8})
	want := []byte{2, 0, 1, 0, 5, 4, 3, 2, 1, 0, 0, 0, 2, 0, 0, 0, 9, 8}
	if !bytes.Equal(b, want) {
		t.Fatalf("got %v, want %v", b, want)
	}
}

func TestBinaryDumpConcurrentWriters(t *testing.T) {
	sink := NewBinaryDumpSink(t.TempDir(), "video/avc;audio/opus")
	if err := sink.WriteSample(0, []byte("start"), 1, keyFrame); err != nil {
		t.Fatal(err)
	}
	const writers, perWriter = 8, 200
	var wg sync.WaitGroup
	for w := 0; w < writers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for i := 0; i < perWriter; i++ {
				payload := bytes.Repeat([]byte{byte(w)}, 100+i)
				if err := sink.WriteSample(w, payload, int64(w*1000+i), 0); err != nil {
					t.Error(err)
					return
				}
			}
		}()
	}
	wg.Wait()
	sink.Close()

	seen := map[[2]int]bool{}
	for _, s := range readAll(t, sink, 0)[1:] {
		i := int(s.PTS) - s.Track*1000
		if len(s.Data) != 100+i || !bytes.Equal(s.Data, bytes.Repeat([]byte{byte(s.Track)}, len(s.Data))) {
			t.Fatalf("interleaved record: track %d pts %d len %d", s.Track, s.PTS, len(s.Data))
		}
		seen[[2]int{s.Track, i}] = true
	}
	if len(seen) != writers*perWriter {
		t.Fatalf("got %d distinct records, want %d", len(seen), writers*perWriter)
	}
}

func TestBinaryDumpTruncatedRecords(t *testing.T) {
	dir := t.TempDir()
	sink := NewBinaryDumpSink(dir, "video/avc;audio/opus")
	sink.WriteSample(0, []byte("aaaa"), 100, keyFrame)
	sink.WriteSample(0, []byte("bbbb"), 110, 0)
	sink.WriteSample(0, []byte("cccc"), 200, keyFrame)
	sink.Close()

	first := filepath.Join(dir, "100.ucf")
	full, err := os.ReadFile(first)
	if err != nil {
		t.Fatal(err)
	}
	// chop the second record of the first file at various points.
	for _, cut := range []int{1, 4, recordHeaderSize, recordHeaderSize + 1} {
		if err := os.WriteFile(first, full[:len(full)-cut], 0644); err != nil {
			t.Fatal(err)
		}
		got := readAll(t, sink, 0)
		if len(got) != 2 || string(got[0].Data) != "aaaa" || string(got[1].Data) != "cccc" {
			t.Errorf("cut %d: got %d samples %+v", cut, len(got), got)
		}
	}

	if _, err := decodeRecord(bytes.NewReader(encodeRecord(0, 0, 0, []byte("abcd"))[:10])); !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Errorf("truncated header: %v", err)
	}
	if _, err := decodeRecord(bytes.NewReader(nil)); err != io.EOF {
		t.Errorf("empty: %v", err)
	}
}

func TestBinaryDumpOversizedRecord(t *testing.T) {
	dir := t.TempDir()
	header := make([]byte, recordHeaderSize)
	binary.LittleEndian.PutUint32(header[12:16], 0xFFFFFFFF)
	if err := os.WriteFile(filepath.Join(dir, "100.ucf"), append(header, "xyz"...), 0644); err != nil {
		t.Fatal(err)
	}
	r, err := NewBinaryDumpSink(dir, "video/avc;audio/opus").SampleReader(0)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if _, err := r.Next(); !errors.Is(err, errCorruptRecord) {
		t.Fatalf("got %v, want errCorruptRecord", err)
	}
}

func TestBinaryDumpManifestIgnoresStrayFiles(t *testing.T) {
	dir := t.TempDir()
	sink := NewBinaryDumpSink(dir, "video/avc;audio/opus")
	for _, name := range []string{"100.ucf", "20.ucf", "README.txt", "a", ".ucf", "abc.ucf", "3.ucf.tmp", "1"} {
		if err := os.WriteFile(filepath.Join(dir, name), nil, 0644); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Mkdir(filepath.Join(dir, "7.ucf"), 0755); err != nil {
		t.Fatal(err)
	}
	manifest, err := sink.ReadManifest()
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest) != 2 || manifest[0].PTS != 20 || manifest[1].PTS != 100 {
		t.Fatalf("manifest: %+v", manifest)
	}
	if want := filepath.Join(dir, "20.ucf"); manifest[0].FileAbsolutePath != want {
		t.Errorf("path %q, want %q", manifest[0].FileAbsolutePath, want)
	}
}

func TestBinaryDumpEmptyAndMissingDirectory(t *testing.T) {
	if _, err := NewBinaryDumpSink(t.TempDir(), "video/avc;audio/opus").SampleReader(0); err == nil {
		t.Error("expected error for empty directory")
	}
	if _, err := NewBinaryDumpSink(filepath.Join(t.TempDir(), "missing"), "video/avc").ReadManifest(); err == nil {
		t.Error("expected error for missing directory")
	}
	// the directory is created on the first keyframe.
	sink := NewBinaryDumpSink(filepath.Join(t.TempDir(), "a", "b"), "video/avc")
	if err := sink.WriteSample(0, []byte("x"), 1, keyFrame); err != nil {
		t.Fatal(err)
	}
	sink.Close()
	if got := readAll(t, sink, 0); len(got) != 1 {
		t.Fatalf("got %d samples", len(got))
	}
}

func TestBinaryDumpSameKeyframePTSAppends(t *testing.T) {
	sink := NewBinaryDumpSink(t.TempDir(), "video/avc;audio/opus")
	sink.WriteSample(0, []byte("video"), 100, keyFrame)
	sink.WriteSample(1, []byte("audio"), 100, keyFrame)
	sink.Close()
	got := readAll(t, sink, 0)
	if len(got) != 2 || got[0].Track != 0 || got[1].Track != 1 {
		t.Fatalf("got %+v", got)
	}
}

// Audio encoders flag every sample as a keyframe; that must not start new files.
func TestBinaryDumpOnlyVideoKeyframesSplit(t *testing.T) {
	sink := NewBinaryDumpSink(t.TempDir(), "audio/mp4a-latm;video/avc")
	sink.WriteSample(0, []byte("a0"), 90, keyFrame) // before the first video keyframe: dropped
	sink.WriteSample(1, []byte("v0"), 100, keyFrame)
	sink.WriteSample(0, []byte("a1"), 110, keyFrame)
	sink.WriteSample(0, []byte("a2"), 120, keyFrame)
	sink.WriteSample(1, []byte("v1"), 200, keyFrame)
	sink.WriteSample(0, []byte("a3"), 210, keyFrame)
	sink.Close()

	manifest, err := sink.ReadManifest()
	if err != nil {
		t.Fatal(err)
	}
	if len(manifest) != 2 || manifest[0].PTS != 100 || manifest[1].PTS != 200 {
		t.Fatalf("manifest %+v, want files at 100 and 200", manifest)
	}
	if got := readAll(t, sink, 0); len(got) != 5 {
		t.Fatalf("got %d samples, want 5", len(got))
	}
}
