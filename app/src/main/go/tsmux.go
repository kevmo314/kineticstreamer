package kinetic

import (
	"bufio"
	"io"

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
}

// newTSMux muxes into w, flushing after every sample in chunks of at most size.
func newTSMux(w io.Writer, size int, tracks []*mpegts.Track) *tsMux {
	m := &tsMux{w: w, size: size, tracks: tracks}
	m.reset()
	return m
}

// reset discards buffered bytes, the sticky bufio error and the muxer state.
func (m *tsMux) reset() {
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

	var err error
	switch t.Codec.(type) {
	case *mpegts.CodecH264, *mpegts.CodecH265:
		nalus := splitNALUs(buf)
		if len(nalus) == 0 {
			return nil
		}
		err = m.mpw.WriteH26x(t, pts, pts, isKeyframe, nalus)
	case *mpegts.CodecOpus:
		err = m.mpw.WriteOpus(t, pts, [][]byte{buf})
	case *mpegts.CodecMPEG4Audio:
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
