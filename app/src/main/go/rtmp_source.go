//go:build amd64 || arm64

package kinetic

import (
	"bytes"
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

	sps []byte // Cached SPS
	pps []byte // Cached PPS

	lastVideoPTS int64
	lastAudioPTS int64

	closed bool
	mu     sync.RWMutex
}

// RTMPServer manages the RTMP listener
type RTMPServer struct {
	listener net.Listener
	port     int
	server   *rtmp.Server

	source     *RTMPSource
	sourceChan chan *RTMPSource // Signals when a new source connects

	closed bool
	mu     sync.RWMutex
}

// NewRTMPServer creates a new RTMP server on the specified port
// Use port 0 to let the OS pick an available port
func NewRTMPServer(port int) *RTMPServer {
	return &RTMPServer{
		port:       port,
		sourceChan: make(chan *RTMPSource, 1),
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
			closed := s.closed
			s.mu.RUnlock()
			if !closed {
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

	s.closed = true
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
	close(s.sourceChan)
}

// WaitForSource blocks until a publisher connects and returns the source
// Returns nil if timeout is reached or server is stopped
func (s *RTMPServer) WaitForSource(timeout time.Duration) *RTMPSource {
	select {
	case source := <-s.sourceChan:
		return source
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

	// Close any existing source
	if s.source != nil {
		s.source.Close()
	}
	s.source = source

	// Non-blocking send to channel
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
	h.source = &RTMPSource{
		videoQueue: make(chan *MediaFrame, 60), // ~2 seconds of video at 30fps
		audioQueue: make(chan *MediaFrame, 100),
	}
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

	// Decode FLV audio data
	var audioData flvtag.AudioData
	if err := flvtag.DecodeAudioData(payload, &audioData); err != nil {
		log.Printf("RTMP: audio decode error: %v", err)
		return nil
	}

	// We only handle AAC
	if audioData.SoundFormat != flvtag.SoundFormatAAC {
		return nil
	}

	// Decode AAC-specific data
	var aacData flvtag.AACAudioData
	if err := flvtag.DecodeAACAudioData(audioData.Data, &aacData); err != nil {
		log.Printf("RTMP: AAC decode error: %v", err)
		return nil
	}

	// Skip AAC sequence header (contains AudioSpecificConfig)
	if aacData.AACPacketType == flvtag.AACPacketTypeSequenceHeader {
		log.Printf("RTMP: received AAC sequence header")
		return nil
	}

	// Read raw AAC frame data
	data, err := io.ReadAll(aacData.Data)
	if err != nil {
		return nil
	}

	pts := int64(timestamp) * 1000 // Convert ms to microseconds

	h.source.mu.Lock()
	h.source.lastAudioPTS = pts
	closed := h.source.closed
	h.source.mu.Unlock()

	if closed {
		return nil
	}

	frame := &MediaFrame{
		Data: data,
		PTS:  pts,
	}

	select {
	case h.source.audioQueue <- frame:
	default:
		// Queue full, drop frame
	}

	return nil
}

func (h *rtmpHandler) OnVideo(timestamp uint32, payload io.Reader) error {
	if h.source == nil {
		return nil
	}

	// Decode FLV video data
	var videoData flvtag.VideoData
	if err := flvtag.DecodeVideoData(payload, &videoData); err != nil {
		log.Printf("RTMP: video decode error: %v", err)
		return nil
	}

	// We only handle AVC (H.264)
	if videoData.CodecID != flvtag.CodecIDAVC {
		return nil
	}

	// Decode AVC-specific data
	var avcData flvtag.AVCVideoPacket
	if err := flvtag.DecodeAVCVideoPacket(videoData.Data, &avcData); err != nil {
		log.Printf("RTMP: AVC decode error: %v", err)
		return nil
	}

	pts := int64(timestamp)*1000 + int64(avcData.CompositionTime)*1000 // CTS offset in ms

	switch avcData.AVCPacketType {
	case flvtag.AVCPacketTypeSequenceHeader:
		// AVC sequence header contains SPS/PPS
		data, err := io.ReadAll(avcData.Data)
		if err != nil {
			return nil
		}
		h.parseAVCConfig(data)
		log.Printf("RTMP: received AVC sequence header, SPS=%d bytes, PPS=%d bytes",
			len(h.source.sps), len(h.source.pps))
		return nil

	case flvtag.AVCPacketTypeNALU:
		// NALU data in AVCC format - convert to Annex B
		data, err := io.ReadAll(avcData.Data)
		if err != nil {
			return nil
		}

		annexB := h.avccToAnnexB(data, videoData.FrameType == flvtag.FrameTypeKeyFrame)

		h.source.mu.Lock()
		h.source.lastVideoPTS = pts
		closed := h.source.closed
		h.source.mu.Unlock()

		if closed {
			return nil
		}

		frame := &MediaFrame{
			Data: annexB,
			PTS:  pts,
		}

		select {
		case h.source.videoQueue <- frame:
		default:
			// Queue full, drop frame
			log.Printf("RTMP: video queue full, dropping frame")
		}

	case flvtag.AVCPacketTypeEOS:
		log.Printf("RTMP: received EOS")
		h.source.Close()
	}

	return nil
}

// parseAVCConfig parses AVCDecoderConfigurationRecord to extract SPS/PPS
func (h *rtmpHandler) parseAVCConfig(data []byte) {
	if len(data) < 11 {
		return
	}

	// AVCDecoderConfigurationRecord structure:
	// configurationVersion (1 byte)
	// AVCProfileIndication (1 byte)
	// profile_compatibility (1 byte)
	// AVCLevelIndication (1 byte)
	// lengthSizeMinusOne (1 byte) - lower 2 bits
	// numOfSequenceParameterSets (1 byte) - lower 5 bits
	// Then SPS data...
	// numOfPictureParameterSets (1 byte)
	// Then PPS data...

	offset := 5

	// Number of SPS
	numSPS := int(data[offset] & 0x1F)
	offset++

	for i := 0; i < numSPS; i++ {
		if offset+2 > len(data) {
			return
		}
		spsLen := int(binary.BigEndian.Uint16(data[offset:]))
		offset += 2
		if offset+spsLen > len(data) {
			return
		}
		h.source.sps = make([]byte, spsLen)
		copy(h.source.sps, data[offset:offset+spsLen])
		offset += spsLen
	}

	// Number of PPS
	if offset >= len(data) {
		return
	}
	numPPS := int(data[offset])
	offset++

	for i := 0; i < numPPS; i++ {
		if offset+2 > len(data) {
			return
		}
		ppsLen := int(binary.BigEndian.Uint16(data[offset:]))
		offset += 2
		if offset+ppsLen > len(data) {
			return
		}
		h.source.pps = make([]byte, ppsLen)
		copy(h.source.pps, data[offset:offset+ppsLen])
		offset += ppsLen
	}
}

// avccToAnnexB converts AVCC format NALUs to Annex B format
// Prepends SPS/PPS before keyframes
func (h *rtmpHandler) avccToAnnexB(avcc []byte, isKeyframe bool) []byte {
	var buf bytes.Buffer
	startCode := []byte{0x00, 0x00, 0x00, 0x01}

	// Prepend SPS/PPS before keyframes
	if isKeyframe && len(h.source.sps) > 0 && len(h.source.pps) > 0 {
		buf.Write(startCode)
		buf.Write(h.source.sps)
		buf.Write(startCode)
		buf.Write(h.source.pps)
	}

	// Parse AVCC NALUs (4-byte length prefix)
	offset := 0
	for offset+4 <= len(avcc) {
		naluLen := int(binary.BigEndian.Uint32(avcc[offset:]))
		offset += 4
		if offset+naluLen > len(avcc) {
			break
		}
		buf.Write(startCode)
		buf.Write(avcc[offset : offset+naluLen])
		offset += naluLen
	}

	return buf.Bytes()
}

func (h *rtmpHandler) OnClose() {
	log.Printf("RTMP: client disconnected")
	if h.source != nil {
		h.source.Close()
	}
}

// ReadVideoFrame reads the next video frame (blocking)
// Returns nil when source is closed
func (s *RTMPSource) ReadVideoFrame() *MediaFrame {
	frame, ok := <-s.videoQueue
	if !ok {
		return nil
	}
	return frame
}

// ReadAudioFrame reads the next audio frame (blocking)
// Returns nil when source is closed
func (s *RTMPSource) ReadAudioFrame() *MediaFrame {
	frame, ok := <-s.audioQueue
	if !ok {
		return nil
	}
	return frame
}

// GetVideoPTS returns the PTS of the last video frame
func (s *RTMPSource) GetVideoPTS() int64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.lastVideoPTS
}

// GetAudioPTS returns the PTS of the last audio frame
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

	// Close channels to unblock readers
	close(s.videoQueue)
	close(s.audioQueue)
}

// IsClosed returns whether the source is closed
func (s *RTMPSource) IsClosed() bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.closed
}
