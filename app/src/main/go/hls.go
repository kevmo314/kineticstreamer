package kinetic

import (
	"context"
	"encoding/binary"
	"fmt"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
)

type HLSSink struct {
	server *http.Server
}

func NewHLSSink(diskSink *BinaryDumpSink, url, bearerToken, encodedMediaFormatMimeTypes string) (*HLSSink, error) {
	mediaFormatMimeTypes := strings.Split(encodedMediaFormatMimeTypes, ";")
	tracks := make([]*mpegts.Track, len(mediaFormatMimeTypes))
	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		tracks[i] = &mpegts.Track{
			PID:   uint16(i),
			Codec: MediaFormatMimeType(mediaFormatMimeType).MPEGTSCodec(),
		}
	}
	server := http.Server{
		Addr: ":8080",
		Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			if r.Method != http.MethodGet {
				w.WriteHeader(http.StatusMethodNotAllowed)
				return
			}
			if r.URL.Path == "/manifest.m3u8" {
				manifest, err := diskSink.ReadManifest()
				if err != nil {
					w.WriteHeader(http.StatusInternalServerError)
					return
				}
				w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
				w.Write([]byte("#EXTM3U\n"))
				w.Write([]byte("#EXT-X-TARGETDURATION:2\n"))
				w.Write([]byte("#EXT-X-MEDIA-SEQUENCE:0\n"))
				for _, m := range manifest {
					w.Write([]byte(fmt.Sprintf("#EXTINF:%d,\n", (time.Duration(m.PTS) * time.Nanosecond).Seconds())))
					w.Write([]byte(fmt.Sprintf("/%d.ucf\n", diskSink.directory, m.PTS)))
				}
				w.Write([]byte("#EXT-X-ENDLIST\n"))
			} else if strings.HasSuffix(r.URL.Path, ".ts") {
				// parse /%d.mp4
				pts, err := strconv.ParseInt(strings.TrimSuffix(r.URL.Path[1:], ".ts"), 10, 64)
				if err != nil {
					w.WriteHeader(http.StatusBadRequest)
					return
				}
				manifest, err := diskSink.ReadManifest()
				if err != nil {
					w.WriteHeader(http.StatusInternalServerError)
					return
				}
				for _, m := range manifest {
					if m.PTS == pts {
						w.Header().Set("Content-Type", "application/mpegts")
						w.WriteHeader(http.StatusOK)
						f, err := os.Open(fmt.Sprintf("%s/%d.ucf", diskSink.directory, pts))
						if err != nil {
							w.WriteHeader(http.StatusInternalServerError)
							return
						}
						defer f.Close()
						mtw := mpegts.NewWriter(w, tracks)
						header := make([]byte, 16)
						buf := make([]byte, 4096)
						for {
							if _, err := f.Read(header); err != nil {
								break
							}
							track := int(binary.LittleEndian.Uint16(header[2:4]))
							flags := MediaCodecBufferFlag(binary.LittleEndian.Uint16(header[0:2]))
							pts := int64(binary.LittleEndian.Uint64(header[4:12]))
							size := int(binary.LittleEndian.Uint32(header[12:16]))

							if size > len(buf) {
								buf = make([]byte, size)
							}

							t := tracks[track]

							isKeyframe := MediaCodecBufferFlag(flags)&MediaCodecBufferFlagKeyFrame != 0

							switch t.Codec.(type) {
							case *mpegts.CodecH264, *mpegts.CodecH265:
								mtw.WriteH26x(t, pts, pts, isKeyframe, [][]byte{buf})
							case *mpegts.CodecMPEG4Audio:
								mtw.WriteMPEG4Audio(t, pts, [][]byte{buf})
							case *mpegts.CodecOpus:
								mtw.WriteOpus(t, pts, [][]byte{buf})
							}
						}
						return
					}
				}
				w.WriteHeader(http.StatusNotFound)
			} else {
				w.WriteHeader(http.StatusNotFound)
			}
		}),
	}
	go server.ListenAndServe()
	return &HLSSink{server: &server}, nil
}

func (s *HLSSink) Close() error {
	return s.server.Shutdown(context.Background())
}
