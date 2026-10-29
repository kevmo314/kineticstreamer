//go:build android

package kinetic

import (
	"fmt"
	"log"
	"sync"
	"sync/atomic"

	uvc "github.com/kevmo314/go-uvc"
	"github.com/kevmo314/go-uvc/pkg/descriptors"
	"github.com/kevmo314/go-uvc/pkg/transfers"
)

// UVC Format types matching UVC specification
const (
	UVC_FRAME_FORMAT_UNKNOWN      = 0
	UVC_FRAME_FORMAT_UNCOMPRESSED = 1
	UVC_FRAME_FORMAT_COMPRESSED   = 2
	UVC_FRAME_FORMAT_YUYV         = 3
	UVC_FRAME_FORMAT_UYVY         = 4
	UVC_FRAME_FORMAT_GRAY8        = 5
	UVC_FRAME_FORMAT_GRAY16       = 6
	UVC_FRAME_FORMAT_MJPEG        = 7
	UVC_FRAME_FORMAT_H264         = 8
	UVC_FRAME_FORMAT_NV12         = 9
	UVC_FRAME_FORMAT_YUY2         = 10
)

// FormatDescriptor describes a video format supported by the device
type FormatDescriptor struct {
	Format               int
	FormatName           string
	Width                int
	Height               int
	DefaultFrameInterval int64
	MinFrameInterval     int64
	MaxFrameInterval     int64
	FrameRates           []int
	FormatIndex          uint8
	FrameIndex           uint8
}

// UVCSource represents a UVC video source
type UVCSource struct {
	fd           int
	device       *uvc.UVCDevice
	mu           sync.Mutex
	activeStream *UVCStream
}

// NewUVCSource creates a new UVC source from a file descriptor
func NewUVCSource(fd int) (*UVCSource, error) {
	device, err := uvc.NewUVCDevice(uintptr(fd))
	if err != nil {
		return nil, fmt.Errorf("failed to create UVC device: %v", err)
	}

	return &UVCSource{
		fd:     fd,
		device: device,
	}, nil
}

// UVCStream represents an active video stream. Stats are written by the
// reader goroutine and read from other JNI threads, hence the atomics.
type UVCStream struct {
	reader            *transfers.FrameReader
	closeOnce         sync.Once
	closeErr          error
	lastPTS           atomic.Uint32 // Raw PTS from UVC payload header (device clock ticks)
	lastSOF           atomic.Uint32 // USB SOF counter from UVC payload header (1kHz)
	hasPTS            atomic.Bool   // Whether a frame has had a valid PTS
	lastArrivalTimeNs atomic.Int64  // CLOCK_MONOTONIC timestamp when frame arrived from USB
}

// StartStreaming starts video streaming with the specified format
func (s *UVCSource) StartStreaming(format, width, height, fps int) (*UVCStream, error) {
	s.mu.Lock()
	device := s.device
	s.mu.Unlock()
	if device == nil {
		return nil, fmt.Errorf("UVC source closed")
	}
	info, err := device.DeviceInfo()
	if err != nil {
		return nil, fmt.Errorf("UVC DeviceInfo failed: %v", err)
	}

	for _, si := range info.StreamingInterfaces {
		for fdIndex, d := range si.Descriptors {
			fd, ok := d.(*descriptors.FrameBasedFormatDescriptor)
			if !ok {
				continue
			}
			fourcc, err := fd.FourCC()
			if err != nil || string(fourcc[:]) != "H264" {
				continue
			}
			numFrames := int(NumFrameDescriptors(fd))
			frs := si.Descriptors[fdIndex+1 : min(fdIndex+numFrames+1, len(si.Descriptors))]
			for _, fr := range frs {
				fr, ok := fr.(*descriptors.FrameBasedFrameDescriptor)
				if !ok {
					continue
				}
				if fr.Width != 1920 || fr.Height != 1080 {
					continue
				}

				reader, err := si.ClaimFrameReader(fd.Index(), fr.Index())
				if err != nil {
					log.Printf("UVC: ClaimFrameReader failed: %v", err)
					continue
				}

				log.Printf("UVC: Started streaming 1920x1080 H264")

				stream := &UVCStream{reader: reader}
				s.mu.Lock()
				if s.device == nil { // closed while claiming the reader
					s.mu.Unlock()
					stream.Close()
					return nil, fmt.Errorf("UVC source closed")
				}
				s.activeStream = stream
				s.mu.Unlock()
				return stream, nil
			}
		}
	}

	return nil, fmt.Errorf("failed to find matching format")
}

func NumFrameDescriptors(fd descriptors.FormatDescriptor) uint8 {
	// darn you golang and your lack of structural typing.
	switch fd := fd.(type) {
	case *descriptors.MJPEGFormatDescriptor:
		return fd.NumFrameDescriptors
	case *descriptors.H264FormatDescriptor:
		return fd.NumFrameDescriptors
	case *descriptors.VP8FormatDescriptor:
		return fd.NumFrameDescriptors
	case *descriptors.UncompressedFormatDescriptor:
		return fd.NumFrameDescriptors
	case *descriptors.FrameBasedFormatDescriptor:
		return fd.NumFrameDescriptors
	default:
		return 0
	}
}

// ReadFrame reads the next frame from the stream
func (s *UVCStream) ReadFrame() ([]byte, error) {
	frame, err := s.reader.ReadFrame()
	if err != nil {
		return nil, err
	}
	totalLen := 0
	for _, p := range frame.Payloads {
		totalLen += len(p.Data)
	}
	buf := make([]byte, totalLen)
	offset := 0
	for _, p := range frame.Payloads {
		copy(buf[offset:], p.Data)
		offset += len(p.Data)
	}

	// Capture arrival time from frame (CLOCK_MONOTONIC, set when first USB payload arrived)
	s.lastArrivalTimeNs.Store(frame.ArrivalTimeNs)

	// Get hardware PTS and SOF from the UVC payload header (if device provides them)
	if pts, ok := frame.PTS(); ok {
		s.lastPTS.Store(pts)
		s.hasPTS.Store(true)
	}
	if _, sof, ok := frame.SCR(); ok {
		s.lastSOF.Store(uint32(sof))
	}

	return buf, nil
}

// GetPTS returns the PTS of the last frame in device clock units.
// Note: This is the raw hardware PTS from the UVC payload header.
func (s *UVCStream) GetPTS() int64 {
	return int64(s.lastPTS.Load())
}

// GetSOF returns the USB Start-of-Frame counter (1kHz) from the last frame.
// This is the common clock reference between UVC and UAC devices on the same USB bus.
func (s *UVCStream) GetSOF() int64 {
	return int64(s.lastSOF.Load())
}

// HasPTS returns whether the last frame had a valid PTS from the device.
func (s *UVCStream) HasPTS() bool {
	return s.hasPTS.Load()
}

// GetArrivalTimeNs returns the CLOCK_MONOTONIC timestamp (in nanoseconds) when
// the first USB payload of this frame arrived. This is the same clock used by
// AudioRecord.getTimestamp(TIMEBASE_MONOTONIC) for A/V synchronization.
func (s *UVCStream) GetArrivalTimeNs() int64 {
	return s.lastArrivalTimeNs.Load()
}

// Close stops the stream. It is safe to call more than once.
func (s *UVCStream) Close() error {
	s.closeOnce.Do(func() { s.closeErr = s.reader.Close() })
	return s.closeErr
}

// Close closes the UVC source and its active stream.
func (s *UVCSource) Close() error {
	s.mu.Lock()
	stream, device := s.activeStream, s.device
	s.activeStream, s.device = nil, nil
	s.mu.Unlock()

	if stream != nil {
		stream.Close()
	}
	if device != nil {
		return device.Close()
	}
	return nil
}
