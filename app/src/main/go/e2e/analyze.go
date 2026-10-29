// Package e2e analyzes MPEG-TS recordings made by the end-to-end streaming test: stream
// structure (codecs, resolution, fps, monotonic PTS, keyframes) is checked in Go, and A/V
// sync is measured by decoding with ffmpeg and comparing white-flash frames (video) with
// 1 kHz tone onsets (audio).
package e2e

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"os/exec"
	"strconv"
	"strings"

	"github.com/bluenviron/mediacommon/pkg/codecs/h264"
	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

// Expectations are the thresholds a recording is checked against.
type Expectations struct {
	Width, Height  int
	MinFPS, MaxFPS float64 // accepted frame rate
	SampleRate     int
	Channels       int
	MinDuration    float64 // seconds
	MaxPTSGap      float64 // largest allowed gap between consecutive frames, seconds
	MaxKeyFrames   int     // largest allowed number of frames between keyframes
	MaxSyncMeanMs  float64 // |mean(audio - video)| over flash/tone pairs
	MaxDriftMs     float64 // offset change over the run
	MinPairs       int     // minimum number of paired flash/tone events
}

// DefaultExpectations matches the app's fixed 1080p30 H.264 + 48 kHz stereo AAC output.
func DefaultExpectations() Expectations {
	return Expectations{
		Width: 1920, Height: 1080, MinFPS: 27, MaxFPS: 33,
		SampleRate: 48000, Channels: 2,
		MinDuration: 8, MaxPTSGap: 0.5, MaxKeyFrames: 180, // the app asks for a keyframe every 150 frames

		MaxSyncMeanMs: 40, MaxDriftMs: 20, MinPairs: 10,
	}
}

type Report struct {
	File string `json:"file"`

	VideoCodec     string  `json:"video_codec"`
	Width          int     `json:"width"`
	Height         int     `json:"height"`
	FPS            float64 `json:"fps"`
	VideoFrames    int     `json:"video_frames"`
	DurationSec    float64 `json:"duration_sec"`
	Keyframes      int     `json:"keyframes"`
	MaxVideoGapSec float64 `json:"max_video_gap_sec"`
	MaxKeyFrames   int     `json:"max_frames_between_keyframes"`
	FirstKeyframe  bool    `json:"first_frame_keyframe"`
	FirstHasSPSPPS bool    `json:"first_keyframe_has_sps_pps"`

	AudioCodec     string  `json:"audio_codec"`
	SampleRate     int     `json:"sample_rate"`
	Channels       int     `json:"channels"`
	AudioPackets   int     `json:"audio_packets"`
	MaxAudioGapSec float64 `json:"max_audio_gap_sec"`

	Flashes      int       `json:"flashes"`
	Tones        int       `json:"tones"`
	Pairs        int       `json:"pairs"`
	OffsetsMs    []float64 `json:"offsets_ms,omitempty"` // audio minus video, per pair
	MeanOffsetMs float64   `json:"mean_offset_ms"`
	MaxAbsOffset float64   `json:"max_abs_offset_ms"`
	DriftMs      float64   `json:"drift_ms"`

	Failures []string `json:"failures"`
}

func (r *Report) fail(format string, a ...any) {
	r.Failures = append(r.Failures, fmt.Sprintf(format, a...))
}

// Analyze checks the recording at path against exp. Environment errors (missing ffmpeg, an
// unreadable file) are returned as err; failed expectations are listed in Report.Failures.
func Analyze(path string, exp Expectations) (*Report, error) {
	r := &Report{File: path}
	if err := checkStructure(path, exp, r); err != nil {
		return r, err
	}
	if r.VideoFrames > 0 && r.AudioPackets > 0 {
		if err := checkSync(path, exp, r); err != nil {
			return r, err
		}
	}
	return r, nil
}

func checkStructure(path string, exp Expectations, r *Report) error {
	f, err := os.Open(path)
	if err != nil {
		return err
	}
	defer f.Close()
	rd, err := mpegts.NewReader(bufio.NewReader(f))
	if err != nil {
		r.fail("not a valid MPEG-TS stream: %v", err)
		return nil
	}

	var vt, at *mpegts.Track
	for _, t := range rd.Tracks() {
		switch t.Codec.(type) {
		case *mpegts.CodecH264:
			vt = t
			r.VideoCodec = "h264"
		case *mpegts.CodecMPEG4Audio:
			at = t
			r.AudioCodec = "aac"
		default:
			r.fail("unexpected track codec %T", t.Codec)
		}
	}
	if vt == nil {
		r.fail("no H.264 video track")
	}
	if at == nil {
		r.fail("no AAC audio track")
	}
	if vt == nil || at == nil {
		return nil
	}
	if c, ok := at.Codec.(*mpegts.CodecMPEG4Audio); ok {
		r.SampleRate, r.Channels = c.Config.SampleRate, int(c.Config.ChannelCount)
	}

	var (
		sawSPS, sawPPS, haveSPS, sawSlice bool
		sps                               h264.SPS
		lastV, firstV                     float64
		lastKeyFrame                      int
		lastA                             float64
		haveA, haveKey                    bool
		videoMono, audioMono              = true, true
	)
	rd.OnDecodeError(func(err error) { r.fail("decode error: %v", err) })
	rd.OnDataH26x(vt, func(pts, dts int64, au [][]byte) error {
		t := float64(pts) / 90000
		slice := false
		for _, n := range au {
			if len(n) == 0 {
				continue
			}
			switch h264.NALUType(n[0] & 0x1f) {
			case h264.NALUTypeSPS:
				sawSPS = true
				if !haveSPS && sps.Unmarshal(n) == nil {
					haveSPS = true
				}
			case h264.NALUTypePPS:
				sawPPS = true
			case h264.NALUTypeIDR, h264.NALUTypeNonIDR:
				slice = true
			}
		}
		if !slice {
			return nil // parameter-set-only access unit
		}
		idr := h264.IDRPresent(au)
		if !sawSlice {
			sawSlice = true
			firstV = t
			r.FirstKeyframe = idr
			r.FirstHasSPSPPS = sawSPS && sawPPS
		} else {
			if t <= lastV && videoMono {
				videoMono = false
				r.fail("video PTS not strictly increasing at frame %d (%.4f -> %.4f)", r.VideoFrames, lastV, t)
			}
			r.MaxVideoGapSec = math.Max(r.MaxVideoGapSec, t-lastV)
		}
		lastV = t
		r.VideoFrames++
		if idr {
			r.Keyframes++
			if n := r.VideoFrames - 1 - lastKeyFrame; haveKey && n > r.MaxKeyFrames {
				r.MaxKeyFrames = n
			}
			lastKeyFrame, haveKey = r.VideoFrames-1, true
		}
		return nil
	})
	rd.OnDataMPEG4Audio(at, func(pts int64, aus [][]byte) error {
		t := float64(pts) / 90000
		if haveA {
			if t <= lastA && audioMono {
				audioMono = false
				r.fail("audio PTS not strictly increasing at packet %d (%.4f -> %.4f)", r.AudioPackets, lastA, t)
			}
			r.MaxAudioGapSec = math.Max(r.MaxAudioGapSec, t-lastA)
		}
		lastA, haveA = t, true
		r.AudioPackets += len(aus)
		return nil
	})
	for {
		err := rd.Read()
		if err == nil {
			continue
		}
		// A recording cut mid-packet when the connection closed ends in EOF.
		if !errors.Is(err, io.EOF) && !strings.Contains(err.Error(), "EOF") && !strings.Contains(err.Error(), "no more packets") {
			r.fail("read error: %v", err)
		}
		break
	}

	if n := r.VideoFrames - 1 - lastKeyFrame; haveKey && n > r.MaxKeyFrames {
		r.MaxKeyFrames = n // the interval still open when the recording ends
	}
	if haveSPS {
		r.Width, r.Height = sps.Width(), sps.Height()
	}
	if r.VideoFrames > 1 {
		r.DurationSec = lastV - firstV
		r.FPS = float64(r.VideoFrames-1) / r.DurationSec
	}

	if r.VideoFrames == 0 {
		r.fail("no video frames")
		return nil
	}
	if !r.FirstKeyframe {
		r.fail("first video frame is not a keyframe")
	}
	if !r.FirstHasSPSPPS {
		r.fail("SPS/PPS not present before the first video frame")
	}
	if r.Width != exp.Width || r.Height != exp.Height {
		r.fail("resolution %dx%d, want %dx%d", r.Width, r.Height, exp.Width, exp.Height)
	}
	if r.FPS < exp.MinFPS || r.FPS > exp.MaxFPS {
		r.fail("fps %.2f, want %.0f to %.0f", r.FPS, exp.MinFPS, exp.MaxFPS)
	}
	if r.DurationSec < exp.MinDuration {
		r.fail("duration %.1fs, want at least %.1fs", r.DurationSec, exp.MinDuration)
	}
	if r.MaxVideoGapSec > exp.MaxPTSGap {
		r.fail("video PTS gap %.3fs exceeds %.3fs", r.MaxVideoGapSec, exp.MaxPTSGap)
	}
	if r.MaxKeyFrames > exp.MaxKeyFrames {
		r.fail("%d frames between keyframes, want at most %d", r.MaxKeyFrames, exp.MaxKeyFrames)
	}
	if r.AudioPackets == 0 {
		r.fail("no audio packets")
	}
	if r.SampleRate != exp.SampleRate || r.Channels != exp.Channels {
		r.fail("audio %d Hz x%d, want %d Hz x%d", r.SampleRate, r.Channels, exp.SampleRate, exp.Channels)
	}
	if r.MaxAudioGapSec > exp.MaxPTSGap {
		r.fail("audio PTS gap %.3fs exceeds %.3fs", r.MaxAudioGapSec, exp.MaxPTSGap)
	}
	return nil
}

// checkSync decodes the recording with ffmpeg and pairs each white flash frame with the
// nearest 1 kHz tone onset.
func checkSync(path string, exp Expectations, r *Report) error {
	vid, err := ffmpegMetadata(path, "-map", "0:v:0", "-vf",
		"scale=32:18,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-")
	if err != nil {
		return fmt.Errorf("decode video: %w", err)
	}
	aud, err := ffmpegMetadata(path, "-map", "0:a:0", "-af",
		"asetnsamples=n=48:p=0,astats=metadata=1:reset=1,ametadata=print:key=lavfi.astats.Overall.RMS_level:file=-")
	if err != nil {
		return fmt.Errorf("decode audio: %w", err)
	}
	flashes := onsets(vid, func(v float64) bool { return v > 128 })
	tones := onsets(aud, func(v float64) bool { return v > -30 }) // dBFS
	r.Flashes, r.Tones = len(flashes), len(tones)

	var ts, offs []float64
	for _, f := range flashes {
		best, ok := 0.0, false
		for _, t := range tones {
			if math.Abs(t-f) < 0.25 && (!ok || math.Abs(t-f) < math.Abs(best-f)) {
				best, ok = t, true
			}
		}
		if ok {
			ts = append(ts, f)
			offs = append(offs, (best-f)*1000)
		}
	}
	r.Pairs = len(offs)
	r.OffsetsMs = offs
	if len(offs) < exp.MinPairs {
		r.fail("only %d flash/tone pairs found (%d flashes, %d tones), want at least %d", len(offs), r.Flashes, r.Tones, exp.MinPairs)
		return nil
	}
	for _, o := range offs {
		r.MeanOffsetMs += o / float64(len(offs))
		r.MaxAbsOffset = math.Max(r.MaxAbsOffset, math.Abs(o))
	}
	slope := regressionSlope(ts, offs) // ms per second
	r.DriftMs = slope * (ts[len(ts)-1] - ts[0])
	if math.Abs(r.MeanOffsetMs) > exp.MaxSyncMeanMs {
		r.fail("A/V offset mean %.1fms exceeds %.0fms (audio minus video)", r.MeanOffsetMs, exp.MaxSyncMeanMs)
	}
	if math.Abs(r.DriftMs) > exp.MaxDriftMs {
		r.fail("A/V drift %.1fms over %.1fs exceeds %.0fms", r.DriftMs, ts[len(ts)-1]-ts[0], exp.MaxDriftMs)
	}
	return nil
}

type sample struct{ t, v float64 }

// onsets returns the times at which the value first satisfies high after not satisfying it.
func onsets(s []sample, high func(float64) bool) []float64 {
	var out []float64
	prev := false
	for _, x := range s {
		h := high(x.v)
		if h && !prev {
			out = append(out, x.t)
		}
		prev = h
	}
	return out
}

func regressionSlope(x, y []float64) float64 {
	n := float64(len(x))
	var sx, sy, sxx, sxy float64
	for i := range x {
		sx += x[i]
		sy += y[i]
		sxx += x[i] * x[i]
		sxy += x[i] * y[i]
	}
	d := n*sxx - sx*sx
	if d == 0 {
		return 0
	}
	return (n*sxy - sx*sy) / d
}

// ffmpegMetadata runs ffmpeg with a filter that prints "pts_time:" / "key=value" metadata
// lines to stdout and returns the (pts_time, value) samples.
func ffmpegMetadata(path string, args ...string) ([]sample, error) {
	full := append([]string{"-v", "error", "-copyts", "-i", path}, args...)
	full = append(full, "-f", "null", "-")
	cmd := exec.Command("ffmpeg", full...)
	var out, stderr bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("ffmpeg: %w: %s", err, stderr.String())
	}
	return parseMetadata(out.String()), nil
}

// parseMetadata reads metadata=print / ametadata=print output:
//
//	frame:0    pts:0       pts_time:0.5
//	lavfi.signalstats.YAVG=16
func parseMetadata(s string) []sample {
	var out []sample
	t := math.NaN()
	for _, line := range strings.Split(s, "\n") {
		if i := strings.Index(line, "pts_time:"); i >= 0 {
			t, _ = strconv.ParseFloat(strings.TrimSpace(line[i+len("pts_time:"):]), 64)
		} else if i := strings.Index(line, "="); i >= 0 && strings.HasPrefix(line, "lavfi.") && !math.IsNaN(t) {
			v, err := strconv.ParseFloat(strings.TrimSpace(line[i+1:]), 64)
			if err != nil || math.IsInf(v, -1) {
				v = -200 // "-inf": digital silence
			}
			out = append(out, sample{t, v})
		}
	}
	return out
}
