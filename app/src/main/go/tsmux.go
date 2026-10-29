package kinetic

import (
	"bufio"
	"io"
	"log"

	"github.com/bluenviron/mediacommon/pkg/codecs/mpeg4audio"
	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

// tsMux is the MPEG-TS muxer shared by the SRT and RIST sinks. It is not safe
// for concurrent use; callers hold their own lock.
type tsMux struct {
	w      io.Writer
	size   int
	tracks []*mpegts.Track
	bw     *bufio.Writer
	mpw    *mpegts.Writer

	// A fresh stream (first connection, or after reset) must start on a keyframe,
	// otherwise a receiver can't decode until the next one.
	sawKeyframe bool
	// Parameter sets from the encoder's codec-config buffer, prepended to keyframes
	// that don't carry their own.
	paramSets [][]byte
}

// newTSMux muxes into w, flushing after every sample in chunks of at most size.
func newTSMux(w io.Writer, size int, tracks []*mpegts.Track) *tsMux {
	m := &tsMux{w: w, size: size, tracks: tracks}
	m.reset()
	return m
}

// reset discards buffered bytes, the sticky bufio error and the muxer state.
func (m *tsMux) reset() {
	// The writer keeps per-track state (leading/PCR track) on the Track
	// structs themselves; clear it so a fresh writer starts clean.
	for _, t := range m.tracks {
		*t = mpegts.Track{Codec: t.Codec}
	}
	m.sawKeyframe = false
	m.bw = bufio.NewWriterSize(m.w, m.size)
	m.mpw = mpegts.NewWriter(m.bw, m.tracks)
}

// ptsTo90k converts microseconds to the 90kHz MPEG-TS clock using integer math.
func ptsTo90k(us int64) int64 { return us * 9 / 100 }

// writeSample muxes and flushes one sample. After an error the muxer is reset
// so the next call starts clean instead of failing on bufio's sticky error.
func (m *tsMux) writeSample(t *mpegts.Track, buf []byte, ptsMicroseconds int64, mediaCodecFlags int32) error {
	isKeyframe := MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagKeyFrame != 0
	pts := ptsTo90k(ptsMicroseconds)

	// MediaCodec's codec-config buffer is not a frame and its timestamp is meaningless (0).
	// Video keeps it as the parameter sets (below); AAC config is already in the ADTS headers.
	isConfig := MediaCodecBufferFlag(mediaCodecFlags)&MediaCodecBufferFlagCodecConfig != 0

	var err error
	switch codec := t.Codec.(type) {
	case *mpegts.CodecH264, *mpegts.CodecH265:
		nalus := splitNALUs(buf)
		if len(nalus) == 0 {
			return nil
		}
		if isConfig {
			// The caller owns buf and may reuse it (JNI), so keep copies.
			m.paramSets = nil
			for _, n := range nalus {
				m.paramSets = append(m.paramSets, append([]byte(nil), n...))
			}
			return nil
		}
		if isKeyframe {
			m.sawKeyframe = true
			if len(m.paramSets) > 0 && !isParamSet(t.Codec, nalus[0]) {
				nalus = append(append([][]byte{}, m.paramSets...), nalus...)
			}
		} else if !m.sawKeyframe {
			return nil
		}
		err = m.mpw.WriteH26x(t, pts, pts, isKeyframe, nalus)
	case *mpegts.CodecOpus:
		if isConfig {
			return nil
		}
		err = m.mpw.WriteOpus(t, pts, [][]byte{buf})
	case *mpegts.CodecMPEG4Audio:
		if isConfig {
			// The AudioSpecificConfig carries the real sample rate and channel
			// count, which the ADTS headers written below must match.
			var conf mpeg4audio.Config
			if err := conf.Unmarshal(buf); err != nil {
				// Not worth tearing the connection down; keep the previous config.
				log.Printf("TS: ignoring invalid AAC config %x: %v", buf, err)
				return nil
			}
			codec.Config = conf
			return nil
		}
		err = m.mpw.WriteMPEG4Audio(t, pts, [][]byte{buf})
	default:
		return nil
	}
	if err == nil {
		err = m.bw.Flush()
	}
	if err != nil {
		m.reset()
	}
	return err
}

// isParamSet reports whether nalu is an SPS/PPS (H.264) or VPS/SPS/PPS (H.265).
func isParamSet(codec mpegts.Codec, nalu []byte) bool {
	if len(nalu) == 0 {
		return false
	}
	if _, ok := codec.(*mpegts.CodecH265); ok {
		typ := (nalu[0] >> 1) & 0x3f
		return typ >= 32 && typ <= 34
	}
	typ := nalu[0] & 0x1f
	return typ == 7 || typ == 8
}

// splitNALUs splits an Annex B byte stream into individual NAL units.
// It handles both 3-byte (0x00 0x00 0x01) and 4-byte (0x00 0x00 0x00 0x01) start codes.
func splitNALUs(buf []byte) [][]byte {
	var nalus [][]byte
	start := -1

	for i := 0; i < len(buf); i++ {
		// Check for 4-byte start code
		if i+3 < len(buf) && buf[i] == 0x00 && buf[i+1] == 0x00 && buf[i+2] == 0x00 && buf[i+3] == 0x01 {
			if start >= 0 {
				nalus = append(nalus, buf[start:i])
			}
			start = i + 4
			i += 3
			continue
		}
		// Check for 3-byte start code
		if i+2 < len(buf) && buf[i] == 0x00 && buf[i+1] == 0x00 && buf[i+2] == 0x01 {
			if start >= 0 {
				nalus = append(nalus, buf[start:i])
			}
			start = i + 3
			i += 2
			continue
		}
	}

	// Append the last NAL unit
	if start >= 0 && start < len(buf) {
		nalus = append(nalus, buf[start:])
	}

	return nalus
}
