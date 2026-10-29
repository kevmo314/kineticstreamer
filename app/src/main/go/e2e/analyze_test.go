package e2e

import (
	"fmt"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// makeClip writes a 10s 320x180 30fps clip with a white flash every 500ms and a 60ms 1 kHz
// tone at the same times, the audio delayed by audioDelayMs.
func makeClip(t *testing.T, audioDelayMs int) string {
	t.Helper()
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not installed")
	}
	path := filepath.Join(t.TempDir(), "clip.ts")
	video := "color=c=black:s=320x180:r=30,drawbox=x=0:y=0:w=320:h=180:color=white:t=fill:enable='lt(mod(t,0.5),0.034)'"
	audio := "sine=f=1000:r=48000,volume='if(lt(mod(t,0.5),0.06),1,0)':eval=frame"
	if audioDelayMs > 0 {
		audio += fmt.Sprintf(",adelay=%d|%d", audioDelayMs, audioDelayMs)
	}
	out, err := exec.Command("ffmpeg", "-v", "error", "-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio,
		"-t", "10", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "150", "-bf", "0", "-c:a", "aac", "-ac", "2",
		"-f", "mpegts", path).CombinedOutput()
	if err != nil {
		t.Fatalf("ffmpeg: %v: %s", err, out)
	}
	return path
}

func testExpectations() Expectations {
	e := DefaultExpectations()
	e.Width, e.Height = 320, 180
	return e
}

func TestAnalyzeSyncedClipPasses(t *testing.T) {
	r, err := Analyze(makeClip(t, 0), testExpectations())
	if err != nil {
		t.Fatal(err)
	}
	if len(r.Failures) != 0 {
		t.Fatalf("unexpected failures: %v (report %+v)", r.Failures, r)
	}
	if r.Pairs < 15 || r.Flashes < 15 {
		t.Errorf("pairs=%d flashes=%d, want at least 15", r.Pairs, r.Flashes)
	}
}

func TestAnalyzeDetectsAVOffset(t *testing.T) {
	r, err := Analyze(makeClip(t, 120), testExpectations())
	if err != nil {
		t.Fatal(err)
	}
	if r.MeanOffsetMs < 100 || r.MeanOffsetMs > 140 {
		t.Errorf("mean offset %.1fms, want ~120ms", r.MeanOffsetMs)
	}
	if !strings.Contains(strings.Join(r.Failures, ";"), "A/V offset") {
		t.Errorf("expected an A/V offset failure, got %v", r.Failures)
	}
}

func TestAnalyzeRejectsWrongResolution(t *testing.T) {
	e := testExpectations()
	e.Width = 1920
	r, err := Analyze(makeClip(t, 0), e)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(strings.Join(r.Failures, ";"), "resolution") {
		t.Errorf("expected a resolution failure, got %v", r.Failures)
	}
}

func TestParseMetadata(t *testing.T) {
	got := parseMetadata("frame:0    pts:0       pts_time:0\nlavfi.signalstats.YAVG=16.5\nframe:1 pts:3 pts_time:0.033\nlavfi.astats.Overall.RMS_level=-inf\n")
	if len(got) != 2 || got[0] != (sample{0, 16.5}) || got[1].v != -200 {
		t.Errorf("got %+v", got)
	}
}
