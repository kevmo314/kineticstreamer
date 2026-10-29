//go:build amd64 || arm64

package kinetic

import (
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"time"

	flvtag "github.com/yutopp/go-flv/tag"
	"github.com/yutopp/go-rtmp"
	rtmpmsg "github.com/yutopp/go-rtmp/message"
)

// MediaFrame holds a single frame with timestamp
type MediaFrame struct {
	Data []byte
	PTS  int64 // microseconds
}

// RTMPSource represents an active RTMP publish session
type RTMPSource struct {
	videoQueue chan *MediaFrame // H.264 NALUs (Annex B format)
	audioQueue chan *MediaFrame // AAC frames (raw AAC, no ADTS)

	// Parameter sets and NAL length size from the AVC sequence header.
	// Only touched from the publisher's connection goroutine.
	sps        [][]byte
	pps        [][]byte
	lengthSize int // bytes in each AVCC NAL length prefix

	lastVideoPTS int64
	lastAudioPTS int64

	// needKeyframe is set when a video frame was dropped; P-frames are
	// discarded until the next keyframe so the decoder never sees missing refs.
	needKeyframe bool

	// The queues are never closed (a producer could still be sending);
	// readers are woken through done instead.
	done   chan struct{}
	closed bool
	mu     sync.RWMutex
}

func newRTMPSource(videoCap, audioCap int) *RTMPSource {
	return &RTMPSource{
		videoQueue:   make(chan *MediaFrame, videoCap),
		audioQueue:   make(chan *MediaFrame, audioCap),
		lengthSize:   4,
		needKeyframe: true,
		done:         make(chan struct{}),
	}
}

// RTMPServer manages the RTMP listener
type RTMPServer struct {
	listener net.Listener
	port     int
	server   *rtmp.Server

	source     *RTMPSource
	sourceChan chan *RTMPSource // Signals when a new source connects

	stopped bool
	done    chan struct{} // closed by Stop
	mu      sync.RWMutex
}

// NewRTMPServer creates a new RTMP server on the specified port
// Use port 0 to let the OS pick an available port
func NewRTMPServer(port int) *RTMPServer {
	return &RTMPServer{
		port:       port,
		sourceChan: make(chan *RTMPSource, 1),
		done:       make(chan struct{}),
	}
}

// Port returns the port the server is listening on
func (s *RTMPServer) Port() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if s.listener == nil {
		return s.port
	}
	return s.listener.Addr().(*net.TCPAddr).Port
}

// Start starts the RTMP server
func (s *RTMPServer) Start() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	addr := fmt.Sprintf(":%d", s.port)
	listener, err := net.Listen("tcp", addr)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %v", addr, err)
	}
	s.listener = listener
	s.port = listener.Addr().(*net.TCPAddr).Port

	log.Printf("RTMP server listening on port %d", s.port)

	// Create the RTMP server with handler factory
	server := rtmp.NewServer(&rtmp.ServerConfig{
		OnConnect: func(conn net.Conn) (io.ReadWriteCloser, *rtmp.ConnConfig) {
			return conn, &rtmp.ConnConfig{
				Handler: &rtmpHandler{server: s},
			}
		},
	})
	s.server = server

	// Start serving in a goroutine (capture server to avoid race with Stop)
	go func() {
		if err := server.Serve(listener); err != nil {
			s.mu.RLock()
			stopped := s.stopped
			s.mu.RUnlock()
			if !stopped {
				log.Printf("RTMP server error: %v", err)
			}
		}
	}()

	return nil
}

// Stop stops the RTMP server
func (s *RTMPServer) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.stopped {
		return
	}
	s.stopped = true
	close(s.done)
	select {
	case <-s.sourceChan:
	default:
	}
	if s.server != nil {
		s.server.Close()
		s.server = nil
	}
	if s.listener != nil {
		s.listener.Close()
		s.listener = nil
	}
	if s.source != nil {
		s.source.Close()
		s.source = nil
	}
}

// WaitForSource blocks until a publisher connects and returns the source
// Returns nil if timeout is reached or server is stopped
func (s *RTMPServer) WaitForSource(timeout time.Duration) *RTMPSource {
	select {
	case source := <-s.sourceChan:
		return source
	case <-s.done:
		return nil
	case <-time.After(timeout):
		return nil
	}
}

// GetSource returns the current source (may be nil)
func (s *RTMPServer) GetSource() *RTMPSource {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.source
}

// setSource sets the active source (called by handler)
func (s *RTMPServer) setSource(source *RTMPSource) {
	s.mu.Lock()
	defer s.mu.Unlock()

	// A publisher can still arrive after Stop; reject it.
	if s.stopped {
		source.Close()
		return
	}

	// Close any existing source
	if s.source != nil {
		s.source.Close()
	}
	s.source = source

	// Replace any unclaimed (now stale) source, then non-blocking send
	select {
	case <-s.sourceChan:
	default:
	}
	select {
	case s.sourceChan <- source:
	default:
	}
}

// rtmpHandler implements the RTMP server handler
type rtmpHandler struct {
	rtmp.DefaultHandler
	server *RTMPServer
	source *RTMPSource
}

func (h *rtmpHandler) OnServe(conn *rtmp.Conn) {
	log.Printf("RTMP: client connected")
}

func (h *rtmpHandler) OnConnect(timestamp uint32, cmd *rtmpmsg.NetConnectionConnect) error {
	log.Printf("RTMP: OnConnect: app=%s", cmd.Command.App)
	return nil
}

func (h *rtmpHandler) OnCreateStream(timestamp uint32, cmd *rtmpmsg.NetConnectionCreateStream) error {
	log.Printf("RTMP: OnCreateStream")
	return nil
}

func (h *rtmpHandler) OnPublish(ctx *rtmp.StreamContext, timestamp uint32, cmd *rtmpmsg.NetStreamPublish) error {
	log.Printf("RTMP: OnPublish: name=%s, type=%s", cmd.PublishingName, cmd.PublishingType)

	// Create new source
	h.source = newRTMPSource(60, 100) // ~2 seconds of video at 30fps
	h.server.setSource(h.source)

	return nil
}

func (h *rtmpHandler) OnSetDataFrame(timestamp uint32, data *rtmpmsg.NetStreamSetDataFrame) error {
	log.Printf("RTMP: OnSetDataFrame")
	return nil
}

func (h *rtmpHandler) OnAudio(timestamp uint32, payload io.Reader) error {
	if h.source == nil {
		return nil
	}

	// Decode FLV audio data (this also decodes the AAC packet header)
	var audioData flvtag.AudioData
	if err := flvtag.DecodeAudioData(payload, &audioData); err != nil {
		log.Printf("RTMP: audio decode error: %v", err)
		return nil
	}

	// We only handle AAC
	if audioData.SoundFormat != flvtag.SoundFormatAAC {
		return nil
	}

	// Skip AAC sequence header (contains AudioSpecificConfig)
	if audioData.AACPacketType == flvtag.AACPacketTypeSequenceHeader {
		log.Printf("RTMP: received AAC sequence header")
		return nil
	}

	// Read raw AAC frame data
	data, err := io.ReadAll(audioData.Data)
	if err != nil {
		return nil
	}

	pts := int64(timestamp) * 1000 // Convert ms to microseconds

	src := h.source
	src.mu.Lock()
	defer src.mu.Unlock()
	if src.closed {
		return nil
	}
	select {
	case src.audioQueue <- &MediaFrame{Data: data, PTS: pts}:
	default:
		// Queue full, drop frame
	}

	return nil
}

func (h *rtmpHandler) OnVideo(timestamp uint32, payload io.Reader) error {
	if h.source == nil {
		return nil
	}

	// Decode FLV video data (this also decodes the AVC packet header)
	var videoData flvtag.VideoData
	if err := flvtag.DecodeVideoData(payload, &videoData); err != nil {
		log.Printf("RTMP: video decode error: %v", err)
		return nil
	}

	// We only handle AVC (H.264)
	if videoData.CodecID != flvtag.CodecIDAVC {
		return nil
	}

	pts := int64(timestamp)*1000 + int64(videoData.CompositionTime)*1000 // CTS offset in ms

	switch videoData.AVCPacketType {
	case flvtag.AVCPacketTypeSequenceHeader:
		// AVC sequence header contains SPS/PPS
		data, err := io.ReadAll(videoData.Data)
		if err != nil {
			return nil
		}
		h.parseAVCConfig(data)
		log.Printf("RTMP: received AVC sequence header, %d SPS, %d PPS, NAL length size %d",
			len(h.source.sps), len(h.source.pps), h.source.lengthSize)
		return nil

	case flvtag.AVCPacketTypeNALU:
		// NALU data in AVCC format - convert to Annex B
		data, err := io.ReadAll(videoData.Data)
		if err != nil {
			return nil
		}

		isKey := videoData.FrameType == flvtag.FrameTypeKeyFrame
		annexB := h.avccToAnnexB(data, isKey)
		if len(annexB) == 0 {
			return nil
		}

		h.source.pushVideo(&MediaFrame{Data: annexB, PTS: pts}, isKey)

	case flvtag.AVCPacketTypeEOS:
		log.Printf("RTMP: received EOS")
		h.source.Close()
	}

	return nil
}

// pushVideo queues a frame without blocking. After an overflow, frames are
// dropped until the next keyframe.
func (s *RTMPSource) pushVideo(frame *MediaFrame, isKey bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closed {
		return
	}
	if s.needKeyframe && !isKey {
		return
	}
	select {
	case s.videoQueue <- frame:
		s.needKeyframe = false
	default:
		log.Printf("RTMP: video queue full, dropping until next keyframe")
		s.needKeyframe = true
	}
}

// parseAVCConfig parses an AVCDecoderConfigurationRecord to extract the NAL
// length size and all SPS/PPS.
func (h *rtmpHandler) parseAVCConfig(data []byte) {
	if len(data) < 7 {
		return
	}

	// configurationVersion, profile, compatibility, level (4 bytes), then
	// lengthSizeMinusOne (low 2 bits), numOfSPS (low 5 bits), SPS entries,
	// numOfPPS, PPS entries. Entries are 2-byte length + data.
	// A truncated record is ignored entirely.
	offset := 5
	readSets := func(n int) ([][]byte, bool) {
		var sets [][]byte
		for i := 0; i < n; i++ {
			if offset+2 > len(data) {
				return nil, false
			}
			l := int(binary.BigEndian.Uint16(data[offset:]))
			offset += 2
			if offset+l > len(data) {
				return nil, false
			}
			sets = append(sets, append([]byte(nil), data[offset:offset+l]...))
			offset += l
		}
		return sets, true
	}

	numSPS := int(data[offset] & 0x1F)
	offset++
	sps, ok := readSets(numSPS)
	if !ok || offset >= len(data) {
		return
	}
	numPPS := int(data[offset])
	offset++
	pps, ok := readSets(numPPS)
	if !ok {
		return
	}

	src := h.source
	src.lengthSize = int(data[4]&0x03) + 1
	src.sps, src.pps = sps, pps
}

// avccToAnnexB converts AVCC format NALUs to Annex B format
// Prepends SPS/PPS before keyframes
func (h *rtmpHandler) avccToAnnexB(avcc []byte, isKeyframe bool) []byte {
	startCode := []byte{0x00, 0x00, 0x00, 0x01}
	src := h.source

	params := 0
	if isKeyframe {
		for _, p := range src.sps {
			params += 4 + len(p)
		}
		for _, p := range src.pps {
			params += 4 + len(p)
		}
	}
	buf := make([]byte, 0, len(avcc)+params+16)

	// Prepend SPS/PPS before keyframes
	if isKeyframe && len(src.sps) > 0 && len(src.pps) > 0 {
		for _, p := range src.sps {
			buf = append(append(buf, startCode...), p...)
		}
		for _, p := range src.pps {
			buf = append(append(buf, startCode...), p...)
		}
	}

	n := src.lengthSize
	if n < 1 || n > 4 {
		n = 4
	}
	paramsLen := len(buf)
	offset := 0
	for offset+n <= len(avcc) {
		naluLen := 0
		for _, b := range avcc[offset : offset+n] {
			naluLen = naluLen<<8 | int(b)
		}
		offset += n
		if naluLen > len(avcc)-offset {
			break
		}
		if naluLen > 0 {
			buf = append(append(buf, startCode...), avcc[offset:offset+naluLen]...)
		}
		offset += naluLen
	}

	// Parameter sets alone are not a frame.
	if len(buf) == paramsLen {
		return nil
	}
	return buf
}

func (h *rtmpHandler) OnClose() {
	log.Printf("RTMP: client disconnected")
	if h.source != nil {
		h.source.Close()
	}
}

// ReadVideoFrame reads the next video frame (blocking)
// Returns nil when source is closed and drained
func (s *RTMPSource) ReadVideoFrame() *MediaFrame {
	f := readFrame(s.videoQueue, s.done)
	if f != nil {
		s.mu.Lock()
		s.lastVideoPTS = f.PTS
		s.mu.Unlock()
	}
	return f
}

// ReadAudioFrame reads the next audio frame (blocking)
// Returns nil when source is closed and drained
func (s *RTMPSource) ReadAudioFrame() *MediaFrame {
	f := readFrame(s.audioQueue, s.done)
	if f != nil {
		s.mu.Lock()
		s.lastAudioPTS = f.PTS
		s.mu.Unlock()
	}
	return f
}

func readFrame(q chan *MediaFrame, done chan struct{}) *MediaFrame {
	select {
	case f := <-q:
		return f
	case <-done:
		select {
		case f := <-q:
			return f
		default:
			return nil
		}
	}
}

// GetVideoPTS returns the PTS of the last video frame returned by ReadVideoFrame
func (s *RTMPSource) GetVideoPTS() int64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastVideoPTS
}

// GetAudioPTS returns the PTS of the last audio frame returned by ReadAudioFrame
func (s *RTMPSource) GetAudioPTS() int64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastAudioPTS
}

// Close closes the source
func (s *RTMPSource) Close() {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.closed {
		return
	}
	s.closed = true

	// Unblock readers
	close(s.done)
}

// IsClosed returns whether the source is closed
func (s *RTMPSource) IsClosed() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.closed
}
