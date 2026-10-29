//go:build amd64 || arm64

package kinetic

import (
	"bytes"
	"fmt"
	"os/exec"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	rtmpmsg "github.com/yutopp/go-rtmp/message"
)

// TestRTMPServerStartStop tests basic server lifecycle
func TestRTMPServerStartStop(t *testing.T) {
	server := NewRTMPServer(0) // Random port
	if err := server.Start(); err != nil {
		t.Fatalf("Failed to start server: %v", err)
	}

	port := server.Port()
	if port == 0 {
		t.Fatal("Server port should not be 0 after start")
	}
	t.Logf("Server started on port %d", port)

	server.Stop()
}

// TestRTMPServerWithFFmpeg tests receiving a stream from ffmpeg
func TestRTMPServerWithFFmpeg(t *testing.T) {
	// Check if ffmpeg is available
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not found, skipping test")
	}

	server := NewRTMPServer(0)
	if err := server.Start(); err != nil {
		t.Fatalf("Failed to start server: %v", err)
	}
	defer server.Stop()

	port := server.Port()
	t.Logf("Server started on port %d", port)

	// Start ffmpeg to publish a test stream
	// Generate 3 seconds of test video and audio
	rtmpURL := fmt.Sprintf("rtmp://localhost:%d/live/test", port)
	cmd := exec.Command("ffmpeg",
		"-f", "lavfi", "-i", "testsrc=duration=3:size=320x240:rate=30",
		"-f", "lavfi", "-i", "sine=frequency=1000:duration=3",
		"-c:v", "libx264",
		"-pix_fmt", "yuv420p", // Required for baseline profile
		"-preset", "ultrafast",
		"-tune", "zerolatency",
		"-profile:v", "baseline",
		"-c:a", "aac",
		"-b:a", "64k",
		"-f", "flv",
		rtmpURL,
	)

	// Capture stderr for debugging
	var stderr bytes.Buffer
	cmd.Stderr = &stderr

	if err := cmd.Start(); err != nil {
		t.Fatalf("Failed to start ffmpeg: %v", err)
	}
	defer func() {
		cmd.Process.Kill()
		cmd.Wait()
	}()

	// Wait for source to connect
	source := server.WaitForSource(5 * time.Second)
	if source == nil {
		t.Logf("ffmpeg stderr: %s", stderr.String())
		t.Fatal("No source connected within timeout")
	}
	t.Log("Source connected")

	// Read frames
	var videoFrames, audioFrames int
	var totalVideoBytes, totalAudioBytes int
	var firstVideoPTS, lastVideoPTS int64
	var firstAudioPTS, lastAudioPTS int64

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		for frame := source.ReadVideoFrame(); frame != nil; frame = source.ReadVideoFrame() {
			if videoFrames == 0 {
				firstVideoPTS = frame.PTS
			}
			videoFrames++
			totalVideoBytes += len(frame.Data)
			lastVideoPTS = frame.PTS
		}
	}()
	go func() {
		defer wg.Done()
		for frame := source.ReadAudioFrame(); frame != nil; frame = source.ReadAudioFrame() {
			if audioFrames == 0 {
				firstAudioPTS = frame.PTS
			}
			audioFrames++
			totalAudioBytes += len(frame.Data)
			lastAudioPTS = frame.PTS
		}
	}()

	// Let it run for 2 seconds, then close the source to stop the readers
	time.Sleep(2 * time.Second)
	source.Close()
	wg.Wait()

	t.Logf("Received %d video frames (%d bytes)", videoFrames, totalVideoBytes)
	t.Logf("Received %d audio frames (%d bytes)", audioFrames, totalAudioBytes)
	t.Logf("Video PTS range: %d - %d us", firstVideoPTS, lastVideoPTS)
	t.Logf("Audio PTS range: %d - %d us", firstAudioPTS, lastAudioPTS)

	if totalVideoBytes == 0 || totalAudioBytes == 0 {
		t.Error("Received empty frames")
	}
	// The stream is 3 seconds long and timestamps start at ~0.
	if lastVideoPTS > 10*1000*1000 || lastAudioPTS > 10*1000*1000 {
		t.Errorf("Implausible PTS: video %d, audio %d", lastVideoPTS, lastAudioPTS)
	}

	if videoFrames == 0 {
		t.Logf("ffmpeg stderr: %s", stderr.String())
		t.Error("No video frames received")
	}
	if audioFrames == 0 {
		t.Logf("ffmpeg stderr: %s", stderr.String())
		t.Error("No audio frames received")
	}

	// Verify H.264 frames have start codes
	if videoFrames > 0 {
		// We should have received at least some keyframes with SPS/PPS
		t.Log("Video frames received successfully")
	}
}

// TestAVCConfigParsing tests parsing of AVCDecoderConfigurationRecord
func TestAVCConfigParsing(t *testing.T) {
	// Example AVCDecoderConfigurationRecord
	// This is a minimal valid config for testing
	config := []byte{
		0x01,       // configurationVersion
		0x42,       // AVCProfileIndication (Baseline)
		0x00,       // profile_compatibility
		0x1f,       // AVCLevelIndication (3.1)
		0xff,       // lengthSizeMinusOne (3 = 4 bytes)
		0xe1,       // numOfSequenceParameterSets (1)
		0x00, 0x04, // SPS length
		0x67, 0x42, 0x00, 0x1f, // SPS data (simplified)
		0x01,       // numOfPictureParameterSets
		0x00, 0x02, // PPS length
		0x68, 0xce, // PPS data (simplified)
	}

	source := newRTMPSource(1, 1)
	handler := &rtmpHandler{source: source}
	handler.parseAVCConfig(config)

	if len(source.sps) != 1 || len(source.pps) != 1 {
		t.Fatalf("Expected 1 SPS and 1 PPS, got %d and %d", len(source.sps), len(source.pps))
	}
	if !bytes.Equal(source.sps[0], []byte{0x67, 0x42, 0x00, 0x1f}) {
		t.Errorf("SPS mismatch: %x", source.sps[0])
	}
	if !bytes.Equal(source.pps[0], []byte{0x68, 0xce}) {
		t.Errorf("PPS mismatch: %x", source.pps[0])
	}
	if source.lengthSize != 4 {
		t.Errorf("Expected length size 4, got %d", source.lengthSize)
	}
}

// TestAVCCToAnnexB tests AVCC to Annex B conversion
func TestAVCCToAnnexB(t *testing.T) {
	source := newRTMPSource(1, 1)
	source.sps = [][]byte{{0x67, 0x42, 0x00, 0x1f}}
	source.pps = [][]byte{{0x68, 0xce}}
	handler := &rtmpHandler{source: source}

	// Create AVCC data with two NALUs
	avcc := make([]byte, 0)
	// First NALU: 3 bytes
	nalu1 := []byte{0x65, 0x88, 0x84} // IDR slice
	avcc = append(avcc, 0, 0, 0, 3)   // length prefix
	avcc = append(avcc, nalu1...)
	// Second NALU: 2 bytes
	nalu2 := []byte{0x41, 0x9a} // Non-IDR slice
	avcc = append(avcc, 0, 0, 0, 2)
	avcc = append(avcc, nalu2...)

	// Test with keyframe (should include SPS/PPS)
	annexB := handler.avccToAnnexB(avcc, true)

	// Verify start codes and data
	startCode := []byte{0, 0, 0, 1}

	// Should be: startCode + SPS + startCode + PPS + startCode + nalu1 + startCode + nalu2
	expected := make([]byte, 0)
	expected = append(expected, startCode...)
	expected = append(expected, source.sps[0]...)
	expected = append(expected, startCode...)
	expected = append(expected, source.pps[0]...)
	expected = append(expected, startCode...)
	expected = append(expected, nalu1...)
	expected = append(expected, startCode...)
	expected = append(expected, nalu2...)

	if !bytes.Equal(annexB, expected) {
		t.Errorf("Annex B mismatch.\nExpected: %x\nGot:      %x", expected, annexB)
	}

	// Test without keyframe (should not include SPS/PPS)
	annexBNonKey := handler.avccToAnnexB(avcc, false)
	expectedNonKey := make([]byte, 0)
	expectedNonKey = append(expectedNonKey, startCode...)
	expectedNonKey = append(expectedNonKey, nalu1...)
	expectedNonKey = append(expectedNonKey, startCode...)
	expectedNonKey = append(expectedNonKey, nalu2...)

	if !bytes.Equal(annexBNonKey, expectedNonKey) {
		t.Errorf("Annex B (non-keyframe) mismatch.\nExpected: %x\nGot:      %x", expectedNonKey, annexBNonKey)
	}
}

// TestMediaFrameQueue tests frame queue behavior
func TestMediaFrameQueue(t *testing.T) {
	source := newRTMPSource(3, 3)

	// Test video queue
	for i := 0; i < 3; i++ {
		frame := &MediaFrame{
			Data: []byte{byte(i)},
			PTS:  int64(i * 33333), // ~30fps
		}
		select {
		case source.videoQueue <- frame:
		default:
			t.Error("Should be able to queue frame")
		}
	}

	// Queue should be full now
	select {
	case source.videoQueue <- &MediaFrame{}:
		t.Error("Queue should be full")
	default:
		// Expected
	}

	// Read frames
	for i := 0; i < 3; i++ {
		frame := source.ReadVideoFrame()
		if frame == nil {
			t.Fatal("Frame should not be nil")
		}
		if frame.Data[0] != byte(i) {
			t.Errorf("Frame data mismatch: expected %d, got %d", i, frame.Data[0])
		}
	}
}

// TestSourceClose tests proper cleanup on close
func TestSourceClose(t *testing.T) {
	source := newRTMPSource(10, 10)

	// Add some frames
	source.videoQueue <- &MediaFrame{Data: []byte{1}}
	source.audioQueue <- &MediaFrame{Data: []byte{2}}

	// Close
	source.Close()

	if !source.IsClosed() {
		t.Error("Source should be closed")
	}

	// Double close should be safe
	source.Close()

	// First read returns buffered frames (channels drain before returning nil)
	if frame := source.ReadVideoFrame(); frame == nil {
		t.Error("Should return buffered frame")
	}
	if frame := source.ReadAudioFrame(); frame == nil {
		t.Error("Should return buffered frame")
	}

	// After draining, read should return nil (channel closed)
	if frame := source.ReadVideoFrame(); frame != nil {
		t.Error("Should return nil after drain")
	}
	if frame := source.ReadAudioFrame(); frame != nil {
		t.Error("Should return nil after drain")
	}
}

// TestServerReconnect tests that a new publisher replaces the old one
func TestServerReconnect(t *testing.T) {
	server := NewRTMPServer(0)
	if err := server.Start(); err != nil {
		t.Fatalf("Failed to start server: %v", err)
	}
	defer server.Stop()

	// Simulate first source
	source1 := newRTMPSource(10, 10)
	server.setSource(source1)

	if server.GetSource() != source1 {
		t.Error("Source should be source1")
	}

	// Simulate second source (should replace first)
	source2 := newRTMPSource(10, 10)
	server.setSource(source2)

	if server.GetSource() != source2 {
		t.Error("Source should be source2")
	}

	// First source should be closed
	if !source1.IsClosed() {
		t.Error("Old source should be closed when new one connects")
	}
}

// flvVideo builds an FLV AVC video tag body.
func flvVideo(key bool, avcPacketType byte, cts int, data []byte) *bytes.Reader {
	ft := byte(0x27) // inter frame, AVC
	if key {
		ft = 0x17
	}
	b := []byte{ft, avcPacketType, byte(cts >> 16), byte(cts >> 8), byte(cts)}
	return bytes.NewReader(append(b, data...))
}

// flvAAC builds an FLV AAC audio tag body.
func flvAAC(aacPacketType byte, data []byte) *bytes.Reader {
	return bytes.NewReader(append([]byte{0xAF, aacPacketType}, data...))
}

// avcConfig builds an AVCDecoderConfigurationRecord.
func avcConfig(lengthSize int, sps, pps [][]byte) []byte {
	c := []byte{0x01, 0x42, 0x00, 0x1f, 0xfc | byte(lengthSize-1), 0xe0 | byte(len(sps))}
	for _, p := range sps {
		c = append(c, byte(len(p)>>8), byte(len(p)))
		c = append(c, p...)
	}
	c = append(c, byte(len(pps)))
	for _, p := range pps {
		c = append(c, byte(len(p)>>8), byte(len(p)))
		c = append(c, p...)
	}
	return c
}

// avccNALs prefixes each NAL with a big-endian length of the given size.
func avccNALs(lengthSize int, nals ...[]byte) []byte {
	var out []byte
	for _, n := range nals {
		for i := lengthSize - 1; i >= 0; i-- {
			out = append(out, byte(len(n)>>(8*i)))
		}
		out = append(out, n...)
	}
	return out
}

func annexB(nals ...[]byte) []byte {
	var out []byte
	for _, n := range nals {
		out = append(append(out, 0, 0, 0, 1), n...)
	}
	return out
}

func newTestHandler() (*rtmpHandler, *RTMPSource) {
	src := newRTMPSource(8, 8)
	return &rtmpHandler{source: src}, src
}

// TestOnVideoOnAudioDecode checks that FLV payloads are decoded exactly once:
// frames keep their full contents and PTS is timestamp + composition time.
func TestOnVideoOnAudioDecode(t *testing.T) {
	h, src := newTestHandler()
	sps, pps := []byte{0x67, 1}, []byte{0x68, 2}

	h.OnVideo(0, flvVideo(true, 0, 0, avcConfig(4, [][]byte{sps}, [][]byte{pps})))
	nal := []byte{0x65, 0xaa, 0xbb, 0xcc}
	h.OnVideo(1000, flvVideo(true, 1, 40, avccNALs(4, nal)))

	f := src.ReadVideoFrame()
	if f == nil {
		t.Fatal("no video frame")
	}
	if want := annexB(sps, pps, nal); !bytes.Equal(f.Data, want) {
		t.Errorf("video data = %x, want %x", f.Data, want)
	}
	if f.PTS != 1040*1000 {
		t.Errorf("video PTS = %d, want %d", f.PTS, 1040*1000)
	}

	h.OnAudio(0, flvAAC(0, []byte{0x12, 0x10})) // sequence header: skipped
	aac := []byte{1, 2, 3, 4, 5}
	h.OnAudio(1000, flvAAC(1, aac))
	a := src.ReadAudioFrame()
	if a == nil || !bytes.Equal(a.Data, aac) || a.PTS != 1000*1000 {
		t.Errorf("audio frame = %+v, want data %x PTS %d", a, aac, 1000*1000)
	}
	if src.GetVideoPTS() != 1040*1000 || src.GetAudioPTS() != 1000*1000 {
		t.Errorf("last PTS = %d/%d", src.GetVideoPTS(), src.GetAudioPTS())
	}
}

// TestAVCLengthSizesAndMultipleParameterSets runs 1, 2 and 4 byte NAL length
// prefixes (from the sequence header) through OnVideo with several SPS/PPS.
func TestAVCLengthSizesAndMultipleParameterSets(t *testing.T) {
	sps := [][]byte{{0x67, 1}, {0x67, 2, 2}}
	pps := [][]byte{{0x68, 1}, {0x68, 2}, {0x68, 3, 3}}
	nal1 := []byte{0x65, 0x88}
	nal2 := bytes.Repeat([]byte{0x41}, 200) // needs >1 byte of length for sizes 2/4

	for _, size := range []int{1, 2, 4} {
		t.Run(fmt.Sprintf("%dbyte", size), func(t *testing.T) {
			h, src := newTestHandler()
			nals := [][]byte{nal1}
			if size > 1 {
				nals = append(nals, nal2)
			}
			h.OnVideo(0, flvVideo(true, 0, 0, avcConfig(size, sps, pps)))
			if src.lengthSize != size || len(src.sps) != 2 || len(src.pps) != 3 {
				t.Fatalf("config: lengthSize=%d sps=%d pps=%d", src.lengthSize, len(src.sps), len(src.pps))
			}

			h.OnVideo(0, flvVideo(true, 1, 0, avccNALs(size, nals...)))
			want := annexB(append(append(append([][]byte{}, sps...), pps...), nals...)...)
			if f := src.ReadVideoFrame(); f == nil || !bytes.Equal(f.Data, want) {
				t.Errorf("keyframe = %v, want %x", f, want)
			}

			h.OnVideo(33, flvVideo(false, 1, 0, avccNALs(size, nals...)))
			want = annexB(nals...)
			if f := src.ReadVideoFrame(); f == nil || !bytes.Equal(f.Data, want) {
				t.Errorf("p-frame = %v, want %x", f, want)
			}
		})
	}
}

// TestAVCCTruncatedNAL ensures malformed lengths don't panic or emit garbage.
func TestAVCCTruncatedNAL(t *testing.T) {
	h, src := newTestHandler()
	h.OnVideo(0, flvVideo(false, 1, 0, []byte{0, 0, 0, 9, 1, 2})) // claims 9 bytes, has 2
	h.OnVideo(0, flvVideo(false, 1, 0, nil))
	if len(src.videoQueue) != 0 {
		t.Errorf("expected no frames, got %d", len(src.videoQueue))
	}
}

// TestVideoQueueOverflowDropsUntilKeyframe verifies that after the queue
// overflows, P-frames are dropped until a keyframe arrives.
func TestVideoQueueOverflowDropsUntilKeyframe(t *testing.T) {
	src := newRTMPSource(2, 2)
	frame := func(id byte) *MediaFrame { return &MediaFrame{Data: []byte{id}, PTS: int64(id)} }

	// Nothing is decodable before the first keyframe.
	src.pushVideo(frame(0), false)
	if len(src.videoQueue) != 0 {
		t.Fatal("P-frame before first keyframe should be dropped")
	}

	src.pushVideo(frame(1), true)  // K1
	src.pushVideo(frame(2), false) // P (queue now full)
	src.pushVideo(frame(3), false) // overflow: dropped
	if f := src.ReadVideoFrame(); f.Data[0] != 1 {
		t.Fatalf("got %d, want K1", f.Data[0])
	}
	src.pushVideo(frame(4), false) // room available, but a gap precedes it
	src.pushVideo(frame(5), true)  // K2 resumes
	src.pushVideo(frame(6), false) // queue full again, dropped

	var got []byte
	for len(src.videoQueue) > 0 {
		got = append(got, src.ReadVideoFrame().Data[0])
	}
	if !bytes.Equal(got, []byte{2, 5}) {
		t.Errorf("queued frames = %v, want [2 5]", got)
	}
}

// TestConcurrentCloseDuringOnVideoOnAudio is meant for -race: Close must never
// panic a producer that is mid-send.
func TestConcurrentCloseDuringOnVideoOnAudio(t *testing.T) {
	for i := 0; i < 50; i++ {
		h, src := newTestHandler()
		var wg sync.WaitGroup
		stop := make(chan struct{})
		for j := 0; j < 2; j++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				nal := avccNALs(4, []byte{0x65, 1, 2, 3})
				for {
					select {
					case <-stop:
						return
					default:
					}
					h.OnVideo(1, flvVideo(true, 1, 0, nal))
					h.OnAudio(1, flvAAC(1, []byte{1, 2, 3}))
					src.GetVideoPTS()
				}
			}()
		}
		wg.Add(1)
		go func() { // reader drains until closed
			defer wg.Done()
			for src.ReadVideoFrame() != nil {
			}
		}()
		time.Sleep(time.Millisecond)
		src.Close()
		src.Close()
		close(stop)
		wg.Wait()
	}
}

// TestCloseUnblocksReaders checks blocked readers return nil on Close.
func TestCloseUnblocksReaders(t *testing.T) {
	src := newRTMPSource(1, 1)
	var n atomic.Int32
	var wg sync.WaitGroup
	for _, read := range []func() *MediaFrame{src.ReadVideoFrame, src.ReadAudioFrame} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if read() == nil {
				n.Add(1)
			}
		}()
	}
	time.Sleep(10 * time.Millisecond)
	src.Close()
	wg.Wait()
	if n.Load() != 2 {
		t.Errorf("%d readers returned nil, want 2", n.Load())
	}
}

// TestOnPublishAfterStop: a publisher arriving after Stop must not panic and
// must not become the active source.
func TestOnPublishAfterStop(t *testing.T) {
	server := NewRTMPServer(0)
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	server.Stop()
	server.Stop() // idempotent

	h := &rtmpHandler{server: server}
	err := h.OnPublish(nil, 0, &rtmpmsg.NetStreamPublish{PublishingName: "x", PublishingType: "live"})
	if err != nil {
		t.Fatal(err)
	}
	if !h.source.IsClosed() {
		t.Error("late source should be closed")
	}
	if server.GetSource() != nil {
		t.Error("late source should not be active")
	}
	// Late frames on the rejected source are ignored.
	h.OnVideo(0, flvVideo(true, 1, 0, avccNALs(4, []byte{0x65})))

	start := time.Now()
	if server.WaitForSource(5*time.Second) != nil || time.Since(start) > time.Second {
		t.Error("WaitForSource should return nil promptly after Stop")
	}
}

// TestStopUnblocksWaitForSource checks a waiter is released by Stop.
func TestStopUnblocksWaitForSource(t *testing.T) {
	server := NewRTMPServer(0)
	res := make(chan *RTMPSource)
	go func() { res <- server.WaitForSource(10 * time.Second) }()
	time.Sleep(10 * time.Millisecond)
	server.Stop()
	select {
	case s := <-res:
		if s != nil {
			t.Error("expected nil source")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("WaitForSource not released by Stop")
	}
}
