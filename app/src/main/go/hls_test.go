package kinetic

import (
	"io"
	"net"
	"net/http"
	"strings"
	"testing"
)

// opusPacket is a minimal valid Opus packet (TOC byte 0xfc: 20ms, one frame).
var opusPacket = []byte{0xfc, 1, 2, 3, 4}

func get(t *testing.T, sink *HLSSink, path string) (int, http.Header, string) {
	t.Helper()
	resp, err := http.Get("http://" + sink.Addr().String() + path)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	return resp.StatusCode, resp.Header, string(body)
}

func TestHLSSink(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/opus")
	// two segments of 2 and (assumed) 2 seconds, with several samples each.
	for _, base := range []int64{1_000_000, 3_000_000} {
		for i := int64(0); i < 5; i++ {
			flags := int32(0)
			if i == 0 {
				flags = keyFrame
			}
			if err := disk.WriteSample(0, opusPacket, base+i*20_000, flags); err != nil {
				t.Fatal(err)
			}
		}
	}
	disk.Close()

	sink, err := NewHLSSink(disk, "127.0.0.1:0", "", "audio/opus")
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()

	status, header, body := get(t, sink, "/manifest.m3u8")
	if status != http.StatusOK || header.Get("Content-Type") != "application/vnd.apple.mpegurl" {
		t.Fatalf("manifest: %d %v", status, header)
	}
	for _, want := range []string{"#EXTM3U\n", "#EXT-X-TARGETDURATION:2\n", "#EXTINF:2.000,\n1000000.ts\n", "#EXTINF:2.000,\n3000000.ts\n", "#EXT-X-ENDLIST\n"} {
		if !strings.Contains(body, want) {
			t.Errorf("manifest missing %q:\n%s", want, body)
		}
	}
	if strings.Contains(body, ".ucf") {
		t.Errorf("manifest advertises raw files:\n%s", body)
	}

	// every segment advertised in the manifest can be fetched and is valid MPEG-TS.
	for _, name := range []string{"1000000.ts", "3000000.ts"} {
		status, header, body := get(t, sink, "/"+name)
		if status != http.StatusOK || header.Get("Content-Type") != "video/mp2t" {
			t.Fatalf("%s: %d %v", name, status, header)
		}
		if len(body) == 0 || len(body)%188 != 0 {
			t.Fatalf("%s: %d bytes is not a whole number of TS packets", name, len(body))
		}
		for i := 0; i < len(body); i += 188 {
			if body[i] != 0x47 {
				t.Fatalf("%s: missing sync byte at %d", name, i)
			}
		}
		// the payload of every sample must have been muxed, not zeros.
		if !strings.Contains(body, string(opusPacket[1:])) {
			t.Errorf("%s: payload not found in segment", name)
		}
	}

	for _, path := range []string{"/2000000.ts", "/abc.ts", "/1000000.ucf", "/nope", "/a/b.ts"} {
		if status, _, _ := get(t, sink, path); status != http.StatusNotFound {
			t.Errorf("%s: got %d, want 404", path, status)
		}
	}
	resp, err := http.Post("http://"+sink.Addr().String()+"/manifest.m3u8", "text/plain", nil)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusMethodNotAllowed {
		t.Errorf("POST: got %d", resp.StatusCode)
	}
}

func TestHLSSinkListenErrorAndClose(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/opus")
	sink, err := NewHLSSink(disk, "127.0.0.1:0", "", "audio/opus")
	if err != nil {
		t.Fatal(err)
	}
	addr := sink.Addr().String()
	if _, err := NewHLSSink(disk, addr, "", "audio/opus"); err == nil {
		t.Fatal("expected an error when the address is in use")
	}
	if err := sink.Close(); err != nil {
		t.Fatal(err)
	}
	// the port is released.
	l, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	l.Close()
	// missing directory is reported rather than served as an empty playlist.
	sink, err = NewHLSSink(NewBinaryDumpSink(t.TempDir()+"/missing", "audio/opus"), "127.0.0.1:0", "", "audio/opus")
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()
	if status, _, _ := get(t, sink, "/manifest.m3u8"); status != http.StatusInternalServerError {
		t.Errorf("got %d", status)
	}
}

func TestHLSSinkAAC(t *testing.T) {
	disk := NewBinaryDumpSink(t.TempDir(), "audio/mp4a-latm")
	frame := []byte("aac-frame-payload")
	for i := int64(0); i < 3; i++ {
		if err := disk.WriteSample(0, frame, 1_000_000+i*21_333, keyFrame); err != nil {
			t.Fatal(err)
		}
	}
	disk.Close()
	sink, err := NewHLSSink(disk, "127.0.0.1:0", "", "audio/mp4a-latm")
	if err != nil {
		t.Fatal(err)
	}
	defer sink.Close()
	status, _, body := get(t, sink, "/1000000.ts")
	if status != http.StatusOK || len(body) == 0 || len(body)%188 != 0 || !strings.Contains(body, string(frame)) {
		t.Fatalf("status %d, %d bytes", status, len(body))
	}
}
