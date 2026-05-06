//go:build cgo && !android && (darwin || linux)

package kinetic

import (
	"fmt"
	"net"
	"testing"
	"time"
)

// TestRISTSink_RoundTrip starts a librist receiver on localhost, points an
// RISTSink at it, writes a few keyframes, and verifies the receiver
// actually gets MPEG-TS-bearing UDP datagrams.
func TestRISTSink_RoundTrip(t *testing.T) {
	port, err := pickFreeUDPPort()
	if err != nil {
		t.Fatalf("pickFreeUDPPort: %v", err)
	}

	listener, err := newRISTTestListener(port)
	if err != nil {
		t.Fatalf("newRISTTestListener: %v", err)
	}
	defer listener.Close()
	t.Logf("RIST receiver listening on 127.0.0.1:%d", port)

	type recvResult struct {
		bytes []byte
		err   error
	}
	recvCh := make(chan recvResult, 1)
	go func() {
		// 200ms per read attempt, up to 6s total. The handshake takes a
		// noticeable fraction of a second on cold start.
		got, err := listener.Drain(188*8, 200, 6000)
		recvCh <- recvResult{bytes: got, err: err}
	}()

	sinkURL := fmt.Sprintf("rist://127.0.0.1:%d?profile=main", port)
	sink, err := NewRISTSink(sinkURL, string(MediaFormatMimeTypeVideoH264))
	if err != nil {
		t.Fatalf("NewRISTSink: %v", err)
	}

	// Same minimal Annex-B keyframe shape used by srt_test.go.
	annexB := []byte{
		0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1f,
		0x96, 0x35, 0x40, 0xa0, 0x0b, 0x6a,
		0x00, 0x00, 0x00, 0x01, 0x68, 0xce, 0x06, 0xe2,
		0x00, 0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x00, 0x33,
	}

	// Send a handful of keyframes to give librist time to handshake and
	// produce visible MPEG-TS output the receiver can verify.
	for i := 0; i < 60; i++ {
		pts := int64(i) * 33_000
		if err := sink.WriteSample(0, annexB, pts, int32(MediaCodecBufferFlagKeyFrame)); err != nil {
			t.Fatalf("WriteSample[%d]: %v", i, err)
		}
		time.Sleep(20 * time.Millisecond)
	}

	res := <-recvCh
	if err := sink.Close(); err != nil {
		t.Fatalf("sink.Close: %v", err)
	}
	if res.err != nil {
		t.Fatalf("listener: %v", res.err)
	}
	assertMPEGTS(t, res.bytes)
}

// pickFreeUDPPort grabs a random free UDP port on localhost. We bind+close
// to discover the OS-assigned port, then trust nothing else snags it before
// the librist receiver opens.
func pickFreeUDPPort() (int, error) {
	addr, err := net.ResolveUDPAddr("udp4", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	c, err := net.ListenUDP("udp4", addr)
	if err != nil {
		return 0, err
	}
	port := c.LocalAddr().(*net.UDPAddr).Port
	_ = c.Close()
	return port, nil
}
