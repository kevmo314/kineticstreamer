package kinetic

import (
	"net"
	"strconv"
	"testing"
	"time"

	"github.com/bluenviron/gortsplib/v4"
	"github.com/bluenviron/gortsplib/v4/pkg/base"
	"github.com/bluenviron/gortsplib/v4/pkg/description"
	"github.com/bluenviron/gortsplib/v4/pkg/format"
	"github.com/bluenviron/gortsplib/v4/pkg/headers"
	"github.com/pion/rtp"
)

// rtspAddrs returns free TCP and (adjacent) UDP addresses for the server to bind.
func rtspAddrs(t *testing.T) (rtsp, rtp, rtcp string) {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	rtsp = l.Addr().String()
	l.Close()
	for {
		c, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		port := c.LocalAddr().(*net.UDPAddr).Port
		c.Close()
		// gortsplib wants an even RTP port followed by the RTCP port.
		if port%2 == 0 && port < 65535 {
			return rtsp, net.JoinHostPort("127.0.0.1", strconv.Itoa(port)), net.JoinHostPort("127.0.0.1", strconv.Itoa(port+1))
		}
	}
}

func TestRTSPServerCloseReleasesPorts(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/opus")
	rtsp, rtpAddr, rtcp := rtspAddrs(t)
	for i := 0; i < 3; i++ {
		s, err := newRTSPServerSink(disk, "audio/opus", rtsp, rtpAddr, rtcp)
		if err != nil {
			t.Fatalf("start %d: %v", i, err)
		}
		if err := s.WriteSample(0, opusPacket, 1000); err != nil {
			t.Fatal(err)
		}
		if err := s.WriteSample(5, opusPacket, 1000); err == nil {
			t.Fatal("expected out of range error")
		}
		if err := s.Close(); err != nil {
			t.Fatal(err)
		}
		// closing again is harmless.
		if err := s.Close(); err != nil {
			t.Fatal(err)
		}
	}
	l, err := net.Listen("tcp", rtsp)
	if err != nil {
		t.Fatalf("rtsp port still bound: %v", err)
	}
	l.Close()
}

func TestRTSPServerInvalidMimeTypeReleasesPorts(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/opus")
	rtsp, rtpAddr, rtcp := rtspAddrs(t)
	if _, err := newRTSPServerSink(disk, "audio/opus;bogus", rtsp, rtpAddr, rtcp); err == nil {
		t.Fatal("expected error")
	}
	s, err := newRTSPServerSink(disk, "audio/opus", rtsp, rtpAddr, rtcp)
	if err != nil {
		t.Fatalf("ports leaked by failed start: %v", err)
	}
	s.Close()
}

func TestRTSPServerRecordedPlayback(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/opus")
	const origin = int64(1_000_000)
	const samples = 6
	for i := int64(0); i < samples; i++ {
		flags := int32(0)
		if i == 0 {
			flags = keyFrame
		}
		// track 1 is the only track, so a swapped track/flags header would index out of range.
		if err := disk.WriteSample(0, opusPacket, origin+i*50_000, flags); err != nil {
			t.Fatal(err)
		}
	}
	disk.Close()

	rtspAddr, rtpAddr, rtcp := rtspAddrs(t)
	s, err := newRTSPServerSink(disk, "audio/opus", rtspAddr, rtpAddr, rtcp)
	if err != nil {
		t.Fatal(err)
	}
	// the first live sample sets the origin that Range offsets are relative to.
	if err := s.WriteSample(0, opusPacket, origin); err != nil {
		t.Fatal(err)
	}

	tcp := gortsplib.TransportTCP
	c := gortsplib.Client{Transport: &tcp}
	u, err := base.ParseURL("rtsp://" + rtspAddr + "/stream")
	if err != nil {
		t.Fatal(err)
	}
	if err := c.Start(u.Scheme, u.Host); err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	desc, _, err := c.Describe(u)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.SetupAll(desc.BaseURL, desc.Medias); err != nil {
		t.Fatal(err)
	}
	timestamps := make(chan uint32, 64)
	c.OnPacketRTPAny(func(_ *description.Media, _ format.Format, pkt *rtp.Packet) {
		timestamps <- pkt.Timestamp
	})
	start := time.Now()
	if _, err := c.Play(&headers.Range{Value: &headers.RangeNPT{Start: time.Millisecond}}); err != nil {
		t.Fatal(err)
	}
	var received []uint32
	for i := 0; i < samples; i++ {
		select {
		case ts := <-timestamps:
			received = append(received, ts)
		case <-time.After(5 * time.Second):
			t.Fatalf("got %d of %d packets", i, samples)
		}
	}
	// 5 intervals of 50ms: unpaced playback would be near-instant.
	if elapsed := time.Since(start); elapsed < 200*time.Millisecond {
		t.Errorf("playback not paced: %v", elapsed)
	}
	// the opus clock rate is 48kHz.
	if d := received[1] - received[0]; d != 48000/20 {
		t.Errorf("timestamp delta %d, want %d", d, 48000/20)
	}

	// once the recording is played out, live streaming resumes.
	deadline := time.Now().Add(2 * time.Second)
	for s.recordedPlaybackRequestId.Load() != 0 {
		if time.Now().After(deadline) {
			t.Fatal("live streaming did not resume after playback ended")
		}
		time.Sleep(10 * time.Millisecond)
	}

	// Close must stop playback and release the ports.
	closed := make(chan struct{})
	go func() {
		s.Close()
		close(closed)
	}()
	select {
	case <-closed:
	case <-time.After(5 * time.Second):
		t.Fatal("Close hung")
	}
}
