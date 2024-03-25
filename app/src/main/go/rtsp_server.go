package kinetic

import (
	"fmt"
	"log"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4"

	"github.com/bluenviron/gortsplib/v4"
	"github.com/bluenviron/gortsplib/v4/pkg/base"
	"github.com/bluenviron/gortsplib/v4/pkg/description"
	"github.com/bluenviron/gortsplib/v4/pkg/format"
	"github.com/bluenviron/gortsplib/v4/pkg/headers"
)

type RTSPServerSink struct {
	disk   *DiskSink
	s      *gortsplib.Server
	stream *gortsplib.ServerStream

	recordedPlaybackRequestId atomic.Uint32

	tracks []*rtspTrack
}

type rtspTrack struct {
	sync.Mutex

	media           *description.Media
	packetizer      rtp.Packetizer
	ptsMicroseconds int64
	pts0            int64
}

// called when a connection is opened.
func (sh *RTSPServerSink) OnConnOpen(ctx *gortsplib.ServerHandlerOnConnOpenCtx) {
	log.Printf("conn opened")
}

// called when a connection is closed.
func (sh *RTSPServerSink) OnConnClose(ctx *gortsplib.ServerHandlerOnConnCloseCtx) {
	log.Printf("conn closed (%v)", ctx.Error)
}

// called when a session is opened.
func (sh *RTSPServerSink) OnSessionOpen(ctx *gortsplib.ServerHandlerOnSessionOpenCtx) {
	log.Printf("session opened")
}

// called when a session is closed.
func (sh *RTSPServerSink) OnSessionClose(ctx *gortsplib.ServerHandlerOnSessionCloseCtx) {
	log.Printf("session closed")
}

// called when receiving a DESCRIBE request.
func (sh *RTSPServerSink) OnDescribe(ctx *gortsplib.ServerHandlerOnDescribeCtx) (*base.Response, *gortsplib.ServerStream, error) {
	log.Printf("describe request")

	return &base.Response{
		StatusCode: base.StatusOK,
	}, sh.stream, nil
}

// called when receiving an ANNOUNCE request.
func (sh *RTSPServerSink) OnAnnounce(ctx *gortsplib.ServerHandlerOnAnnounceCtx) (*base.Response, error) {
	log.Printf("announce request")

	// this server doesn't accept publishes from another client
	return &base.Response{
		StatusCode: base.StatusForbidden,
	}, nil
}

// called when receiving a SETUP request.
func (sh *RTSPServerSink) OnSetup(ctx *gortsplib.ServerHandlerOnSetupCtx) (*base.Response, *gortsplib.ServerStream, error) {
	log.Printf("setup request")

	return &base.Response{
		StatusCode: base.StatusOK,
	}, sh.stream, nil
}

// called when receiving a PLAY request.
func (sh *RTSPServerSink) OnPlay(ctx *gortsplib.ServerHandlerOnPlayCtx) (*base.Response, error) {
	log.Printf("play request %#v", ctx)

	// parse the range header
	rangeHeader := ctx.Request.Header["Range"]
	if len(rangeHeader) == 0 {
		sh.recordedPlaybackRequestId.Store(0)
		return &base.Response{
			StatusCode: base.StatusOK,
		}, nil
	}
	log.Printf("got range header: %#v", rangeHeader)
	h := &headers.Range{}
	if err := h.Unmarshal(rangeHeader); err != nil {
		return &base.Response{
			StatusCode: base.StatusBadRequest,
		}, err
	}
	t0 := time.Now()
	var dpts int64
	switch value := h.Value.(type) {
	case *headers.RangeSMPTE:
		// TODO: implement
		return &base.Response{StatusCode: base.StatusOK}, nil
	case *headers.RangeNPT:
		log.Printf("value %#v", value)
		dpts = value.Start.Microseconds()
	case *headers.RangeUTC:
		// TODO: implement
		return &base.Response{StatusCode: base.StatusOK}, nil
	}
	if dpts == 0 {
		// exit if the range header indicates that we should play live.
		sh.recordedPlaybackRequestId.Store(0)
		return &base.Response{
			StatusCode: base.StatusOK,
		}, nil
	}
	requestId := sh.recordedPlaybackRequestId.Add(1)
	for i, t := range sh.tracks {
		requestedPTS := t.pts0 + dpts
		log.Printf("reading from %d", requestedPTS)
		reader, err := sh.disk.Track(i).SampleReader(requestedPTS)
		if err != nil {
			return &base.Response{StatusCode: base.StatusInternalServerError}, err
		}
		go func(t *rtspTrack) {
			var rpts0 int64
			for sh.recordedPlaybackRequestId.Load() == requestId {
				sample, err := reader.Next()
				if err != nil {
					return
				}

				if sample.PTS < requestedPTS {
					// we cannot discard samples before the requested PTS because it contains
					// the keyframe. instead, push the sample directly to the muxer with zero
					// duration.
					t.Lock()

					packets := t.packetizer.Packetize(sample.Data, 1)
					for _, p := range packets {
						if err := sh.stream.WritePacketRTP(t.media, p); err != nil {
							t.Unlock()
							return
						}
					}

					t.Unlock()
				}

				delta := time.Duration(sample.PTS-requestedPTS) * time.Microsecond
				time.Sleep(time.Until(t0.Add(delta)))

				t.Lock()

				if rpts0 == 0 {
					rpts0 = sample.PTS
				}
				duration := time.Duration(sample.PTS-rpts0) * time.Microsecond
				rpts0 = sample.PTS

				samples := uint32(duration.Seconds() * float64(t.media.Formats[0].ClockRate()))
				packets := t.packetizer.Packetize(sample.Data, samples)
				for _, p := range packets {
					if err := sh.stream.WritePacketRTP(t.media, p); err != nil {
						t.Unlock()
						return
					}
				}

				t.Unlock()
			}
		}(t)
	}
	return &base.Response{
		StatusCode: base.StatusOK,
	}, nil
}

// called when receiving a PAUSE request.
func (sh *RTSPServerSink) OnPause(ctx *gortsplib.ServerHandlerOnPauseCtx) (*base.Response, error) {
	log.Printf("pause request %#v", ctx)

	return &base.Response{
		StatusCode: base.StatusOK,
	}, nil
}

func (sh *RTSPServerSink) OnGetParameter(ctx *gortsplib.ServerHandlerOnGetParameterCtx) (*base.Response, error) {
	log.Printf("get parameter request %#v", ctx)

	return &base.Response{
		StatusCode: base.StatusOK,
	}, nil
}

func (sh *RTSPServerSink) OnSetParameter(ctx *gortsplib.ServerHandlerOnSetParameterCtx) (*base.Response, error) {
	log.Printf("set parameter request %#v", ctx)

	return &base.Response{
		StatusCode: base.StatusOK,
	}, nil
}

func (sh *RTSPServerSink) OnPacketLost(ctx *gortsplib.ServerHandlerOnPacketLostCtx) {
	log.Printf("packet lost")
}

func (sh *RTSPServerSink) OnDecodeError(ctx *gortsplib.ServerHandlerOnDecodeErrorCtx) {
	log.Printf("decode error")
}

func (sh *RTSPServerSink) OnStreamWriteError(ctx *gortsplib.ServerHandlerOnStreamWriteErrorCtx) {
	log.Printf("stream write error")
}

// called when receiving a RECORD request.
func (sh *RTSPServerSink) OnRecord(ctx *gortsplib.ServerHandlerOnRecordCtx) (*base.Response, error) {
	log.Printf("record request")

	return &base.Response{
		StatusCode: base.StatusForbidden,
	}, nil
}

func toMediaAndPayloader(mediaFormatMimeType string, pt uint8) (*description.Media, rtp.Payloader) {
	mimeType := MediaFormatMimeType(mediaFormatMimeType).PionMimeType()
	switch mimeType {
	case webrtc.MimeTypeH264:
		return &description.Media{
			Type: description.MediaTypeVideo,
			Formats: []format.Format{&format.H264{
				PayloadTyp:        pt,
				PacketizationMode: 1,
			}},
		}, &codecs.H264Payloader{}
	case webrtc.MimeTypeVP8:
		return &description.Media{
			Type: description.MediaTypeVideo,
			Formats: []format.Format{&format.VP8{
				PayloadTyp: pt,
			}},
		}, &codecs.VP8Payloader{}
	case webrtc.MimeTypeVP9:
		return &description.Media{
			Type: description.MediaTypeVideo,
			Formats: []format.Format{&format.VP9{
				PayloadTyp: pt,
			}},
		}, &codecs.VP9Payloader{}
	case webrtc.MimeTypeAV1:
		return &description.Media{
			Type: description.MediaTypeVideo,
			Formats: []format.Format{&format.AV1{
				PayloadTyp: pt,
			}},
		}, &codecs.AV1Payloader{}
	default:
		return nil, nil
	}
}

func NewRTSPServerSink(disk *DiskSink, encodedMediaFormatMimeTypes string) (*RTSPServerSink, error) {
	mediaFormatMimeTypes := strings.Split(encodedMediaFormatMimeTypes, ";")
	s := &RTSPServerSink{
		disk:   disk,
		tracks: make([]*rtspTrack, len(mediaFormatMimeTypes)),
	}
	s.s = &gortsplib.Server{
		Handler:           s,
		RTSPAddress:       ":8554",
		UDPRTPAddress:     ":8000",
		UDPRTCPAddress:    ":8001",
		MulticastIPRange:  "224.1.0.0/16",
		MulticastRTPPort:  8002,
		MulticastRTCPPort: 8003,
	}

	// the server must be started first to pick up all the default values.
	if err := s.s.Start(); err != nil {
		return nil, err
	}

	medias := make([]*description.Media, len(mediaFormatMimeTypes))
	for i, mediaFormatMimeType := range mediaFormatMimeTypes {
		media, payloader := toMediaAndPayloader(mediaFormatMimeType, uint8(i+96))
		s.tracks[i] = &rtspTrack{
			media: media,
			packetizer: rtp.NewPacketizer(
				uint16(s.s.MaxPacketSize),
				uint8(i+96),
				0,
				payloader,
				rtp.NewRandomSequencer(),
				uint32(media.Formats[0].ClockRate()),
			),
		}
		medias[i] = media
	}

	s.stream = gortsplib.NewServerStream(s.s, &description.Session{Medias: medias})

	return s, nil
}

func (s *RTSPServerSink) WriteSample(i int, buf []byte, ptsMicroseconds int64) error {
	if s.recordedPlaybackRequestId.Load() > 0 {
		return nil
	}

	t := s.tracks[i]

	t.Lock()
	defer t.Unlock()

	if t.ptsMicroseconds == 0 {
		t.ptsMicroseconds = ptsMicroseconds
		t.pts0 = ptsMicroseconds
	}
	duration := time.Duration(ptsMicroseconds-t.ptsMicroseconds) * time.Microsecond
	t.ptsMicroseconds = ptsMicroseconds

	samples := uint32(duration.Seconds() * float64(t.media.Formats[0].ClockRate()))
	packets := t.packetizer.Packetize(buf, samples)
	for _, p := range packets {
		if err := s.stream.WritePacketRTP(t.media, p); err != nil {
			return fmt.Errorf(
				"t.stream.WritePacketRTP: %w",
				err,
			)
		}
	}
	return nil
}

func (s *RTSPServerSink) Close() error {
	s.stream.Close()
	return nil
}
