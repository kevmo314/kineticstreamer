package kinetic

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"math"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

const hlsDefaultAddr = ":8080"

// HLSSink serves the recordings of a BinaryDumpSink as HLS. Each recorded file
// (one per keyframe) is a segment, served as /<pts>.ts.
type HLSSink struct {
	server   *http.Server
	listener net.Listener
}

// NewHLSSink starts serving on addr (":8080" if empty).
func NewHLSSink(diskSink *BinaryDumpSink, addr, bearerToken, encodedMediaFormatMimeTypes string) (*HLSSink, error) {
	if addr == "" {
		addr = hlsDefaultAddr
	}
	mediaFormatMimeTypes := strings.Split(encodedMediaFormatMimeTypes, ";")

	mux := http.NewServeMux()
	mux.HandleFunc("GET /manifest.m3u8", func(w http.ResponseWriter, r *http.Request) {
		manifest, err := diskSink.ReadManifest()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		// a segment lasts until the next one starts. The last one is still being
		// written, so assume it lasts as long as the one before it.
		durations := make([]float64, len(manifest))
		target := 1.0
		for i := range manifest {
			if i+1 < len(manifest) {
				durations[i] = float64(manifest[i+1].PTS-manifest[i].PTS) / 1e6
			} else if i > 0 {
				durations[i] = durations[i-1]
			} else {
				durations[i] = 1
			}
			target = math.Max(target, durations[i])
		}
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
		fmt.Fprintf(w, "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:%d\n#EXT-X-MEDIA-SEQUENCE:0\n", int(math.Ceil(target)))
		for i, m := range manifest {
			fmt.Fprintf(w, "#EXTINF:%.3f,\n%d.ts\n", durations[i], m.PTS)
		}
		fmt.Fprint(w, "#EXT-X-ENDLIST\n")
	})
	mux.HandleFunc("GET /{segment}", func(w http.ResponseWriter, r *http.Request) {
		name, ok := strings.CutSuffix(r.PathValue("segment"), ".ts")
		pts, err := strconv.ParseInt(name, 10, 64)
		if !ok || err != nil {
			http.NotFound(w, r)
			return
		}
		manifest, err := diskSink.ReadManifest()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		for _, m := range manifest {
			if m.PTS != pts {
				continue
			}
			f, err := os.Open(m.FileAbsolutePath)
			if err != nil {
				http.Error(w, err.Error(), http.StatusInternalServerError)
				return
			}
			defer f.Close()
			w.Header().Set("Content-Type", "video/mp2t")
			if err := writeSegment(w, f, mediaFormatMimeTypes); err != nil {
				log.Printf("hls segment %d: %v", pts, err)
			}
			return
		}
		http.NotFound(w, r)
	})

	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return nil, err
	}
	server := &http.Server{Handler: mux}
	go func() {
		if err := server.Serve(listener); !errors.Is(err, http.ErrServerClosed) {
			log.Printf("hls server: %v", err)
		}
	}()
	return &HLSSink{server: server, listener: listener}, nil
}

// Addr is the address the sink is listening on.
func (s *HLSSink) Addr() net.Addr {
	return s.listener.Addr()
}

func (s *HLSSink) Close() error {
	err := s.server.Shutdown(context.Background())
	// Serve may not have started yet, in which case nothing else would close the listener.
	s.listener.Close()
	return err
}

// writeSegment remuxes the records in r to MPEG-TS. Codec-config records provide the
// SPS/PPS and AAC config, just like for the SRT sink.
func writeSegment(w io.Writer, r io.Reader, mimeTypes []string) error {
	// the writer assigns the PIDs and AAC config is per stream, so every segment needs its own tracks.
	tracks := make([]*mpegts.Track, len(mimeTypes))
	for i, m := range mimeTypes {
		tracks[i] = &mpegts.Track{Codec: MediaFormatMimeType(m).MPEGTSCodec()}
	}
	mux := newTSMux(w, 64<<10, tracks)
	for {
		sample, err := decodeRecord(r)
		if err != nil {
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
				// a truncated tail is treated as the end of the segment.
				return nil
			}
			return err
		}
		if sample.Track >= len(tracks) {
			continue
		}
		if err := mux.writeSample(tracks[sample.Track], sample.Data, sample.PTS, int32(sample.Flags)); err != nil {
			return err
		}
	}
}
