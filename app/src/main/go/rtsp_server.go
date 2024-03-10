package kinetic

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"sync"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/rtp/codecs"
	"github.com/pion/webrtc/v4/pkg/media/h264reader"

	"github.com/bluenviron/gortsplib/v4"
	"github.com/bluenviron/gortsplib/v4/pkg/base"
	"github.com/bluenviron/gortsplib/v4/pkg/description"
	"github.com/bluenviron/gortsplib/v4/pkg/format"
)

type RTSPServerSink struct {
	s         *gortsplib.Server
	mutex     sync.Mutex
	stream    *gortsplib.ServerStream
	publisher *gortsplib.ServerSession

	medias []*description.Media
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
	log.Printf("play request")

	return &base.Response{
		StatusCode: base.StatusOK,
	}, nil
}

// called when receiving a RECORD request.
func (sh *RTSPServerSink) OnRecord(ctx *gortsplib.ServerHandlerOnRecordCtx) (*base.Response, error) {
	log.Printf("record request")

	return &base.Response{
		StatusCode: base.StatusForbidden,
	}, nil
}

func NewRTSPServerSink() (*RTSPServerSink, error) {
	h := &RTSPServerSink{}
	h.s = &gortsplib.Server{
		Handler:           h,
		RTSPAddress:       ":8554",
		UDPRTPAddress:     ":8000",
		UDPRTCPAddress:    ":8001",
		MulticastIPRange:  "224.1.0.0/16",
		MulticastRTPPort:  8002,
		MulticastRTCPPort: 8003,
	}
	return h, h.s.Start()
}

func (s *RTSPServerSink) AddH264Track() (*RTSPServerSinkH264Track, error) {
	s.medias = []*description.Media{{
		Type: description.MediaTypeVideo,
		Formats: []format.Format{&format.H264{
			PayloadTyp:        96,
			PacketizationMode: 1,
		}},
	}}
	s.stream = gortsplib.NewServerStream(s.s, &description.Session{
		Medias: s.medias,
	})
	return NewRTSPServerSinkH264Track(s.stream, s.medias[0], s.s.MaxPacketSize)
}

type RTSPServerSinkH264Track struct {
	stream *gortsplib.ServerStream

	buffer *bytes.Buffer
	reader *h264reader.H264Reader

	packetizer rtp.Packetizer

	ptsMicroseconds int64

	media *description.Media
}

func NewRTSPServerSinkH264Track(stream *gortsplib.ServerStream, media *description.Media, mtu int) (*RTSPServerSinkH264Track, error) {
	// TODO: Should sps/pps be required here?
	buffer := &bytes.Buffer{}
	reader, err := h264reader.NewReader(buffer)
	if err != nil {
		return nil, err
	}
	return &RTSPServerSinkH264Track{
		stream: stream,
		buffer: buffer,
		reader: reader,
		packetizer: rtp.NewPacketizer(
			uint16(mtu),
			96,
			0,
			&codecs.H264Payloader{},
			rtp.NewRandomSequencer(),
			90000,
		),
		media: media,
	}, nil
}

func (t *RTSPServerSinkH264Track) WriteH264AnnexBSample(buf []byte, ptsMicroseconds int64) error {
	// append to buffer
	if _, err := t.buffer.Write(buf); err != nil {
		return fmt.Errorf("t.buffer.Write: %w", err)
	}
	// calculate the duration
	if t.ptsMicroseconds == 0 {
		t.ptsMicroseconds = ptsMicroseconds
	}
	duration := time.Duration(ptsMicroseconds-t.ptsMicroseconds) * time.Microsecond
	t.ptsMicroseconds = ptsMicroseconds
	// drain the reader
	for i := 0; ; i++ {
		nalu, err := t.reader.NextNAL()
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return fmt.Errorf("h264reader.NextNAL: %w", err)
		}
		if nalu == nil {
			return nil
		}
		log.Printf("%d bytes, duration %v", len(nalu.Data), duration)

		samples := uint32((33 * time.Millisecond).Seconds() * 90000)
		packets := t.packetizer.Packetize(nalu.Data, samples)
		for _, p := range packets {
			if err := t.stream.WritePacketRTP(t.media, p); err != nil {
				return fmt.Errorf(
					"t.stream.WritePacketRTP: %w",
					err,
				)
			}
		}

	}
}

func (s *RTSPServerSink) Close() error {
	s.s.Close()
	return nil
}
