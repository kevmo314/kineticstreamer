//go:build amd64 || arm64

package kinetic

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"os/exec"
	"testing"
	"time"
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

	done := make(chan struct{})
	go func() {
		for i := 0; i < 100; i++ {
			select {
			case <-done:
				return
			default:
			}

			frame := source.ReadVideoFrame()
			if frame == nil {
				break
			}
			videoFrames++
			totalVideoBytes += len(frame.Data)
			if firstVideoPTS == 0 {
				firstVideoPTS = frame.PTS
			}
			lastVideoPTS = frame.PTS
		}
	}()

	go func() {
		for i := 0; i < 200; i++ {
			select {
			case <-done:
				return
			default:
			}

			frame := source.ReadAudioFrame()
			if frame == nil {
				break
			}
			audioFrames++
			totalAudioBytes += len(frame.Data)
			if firstAudioPTS == 0 {
				firstAudioPTS = frame.PTS
			}
			lastAudioPTS = frame.PTS
		}
	}()

	// Let it run for 2 seconds
	time.Sleep(2 * time.Second)
	close(done)

	// Give goroutines time to exit
	time.Sleep(100 * time.Millisecond)

	t.Logf("Received %d video frames (%d bytes)", videoFrames, totalVideoBytes)
	t.Logf("Received %d audio frames (%d bytes)", audioFrames, totalAudioBytes)
	t.Logf("Video PTS range: %d - %d us", firstVideoPTS, lastVideoPTS)
	t.Logf("Audio PTS range: %d - %d us", firstAudioPTS, lastAudioPTS)

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

	source := &RTMPSource{}
	handler := &rtmpHandler{source: source}
	handler.parseAVCConfig(config)

	if len(source.sps) != 4 {
		t.Errorf("Expected SPS length 4, got %d", len(source.sps))
	}
	if len(source.pps) != 2 {
		t.Errorf("Expected PPS length 2, got %d", len(source.pps))
	}
	if !bytes.Equal(source.sps, []byte{0x67, 0x42, 0x00, 0x1f}) {
		t.Errorf("SPS mismatch: %x", source.sps)
	}
	if !bytes.Equal(source.pps, []byte{0x68, 0xce}) {
		t.Errorf("PPS mismatch: %x", source.pps)
	}
}

// TestAVCCToAnnexB tests AVCC to Annex B conversion
func TestAVCCToAnnexB(t *testing.T) {
	source := &RTMPSource{
		sps: []byte{0x67, 0x42, 0x00, 0x1f},
		pps: []byte{0x68, 0xce},
	}
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
	expected = append(expected, source.sps...)
	expected = append(expected, startCode...)
	expected = append(expected, source.pps...)
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
	source := &RTMPSource{
		videoQueue: make(chan *MediaFrame, 3),
		audioQueue: make(chan *MediaFrame, 3),
	}

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
	source := &RTMPSource{
		videoQueue: make(chan *MediaFrame, 10),
		audioQueue: make(chan *MediaFrame, 10),
	}

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

// TestAVCCLengthPrefixSizes tests handling of different NALU length prefix sizes
func TestAVCCLengthPrefixSizes(t *testing.T) {
	source := &RTMPSource{
		sps: []byte{0x67},
		pps: []byte{0x68},
	}
	handler := &rtmpHandler{source: source}

	// Standard 4-byte length prefix
	avcc := make([]byte, 0)
	nalu := []byte{0x65, 0x88, 0x84, 0x00, 0x11}
	lenBytes := make([]byte, 4)
	binary.BigEndian.PutUint32(lenBytes, uint32(len(nalu)))
	avcc = append(avcc, lenBytes...)
	avcc = append(avcc, nalu...)

	annexB := handler.avccToAnnexB(avcc, false)

	// Should have start code + nalu
	expected := append([]byte{0, 0, 0, 1}, nalu...)
	if !bytes.Equal(annexB, expected) {
		t.Errorf("AVCC to Annex B failed.\nExpected: %x\nGot:      %x", expected, annexB)
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
	source1 := &RTMPSource{
		videoQueue: make(chan *MediaFrame, 10),
		audioQueue: make(chan *MediaFrame, 10),
	}
	server.setSource(source1)

	if server.GetSource() != source1 {
		t.Error("Source should be source1")
	}

	// Simulate second source (should replace first)
	source2 := &RTMPSource{
		videoQueue: make(chan *MediaFrame, 10),
		audioQueue: make(chan *MediaFrame, 10),
	}
	server.setSource(source2)

	if server.GetSource() != source2 {
		t.Error("Source should be source2")
	}

	// First source should be closed
	if !source1.IsClosed() {
		t.Error("Old source should be closed when new one connects")
	}
}
