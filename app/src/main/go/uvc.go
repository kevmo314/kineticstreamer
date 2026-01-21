package kinetic

import (
	"fmt"
	"log"

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
	activeStream *UVCStream
}

// NewUVCSource creates a new UVC source from a file descriptor
func NewUVCSource(fd int) (*UVCSource, error) {
	device, err := uvc.NewUVCDevice(uintptr(fd))
	if err != nil {
		return nil, fmt.Errorf("failed to create UVC device: %v", err)
	}

	return &UVCSource{
        fd:         fd,
        device:     device,
    }, nil
}

// UVCStream represents an active video stream
type UVCStream struct {
	reader              *transfers.FrameReader
	buffer              [][]byte
	frameIntervalNanos  int64   // Frame interval in nanoseconds from negotiated format
	fps                 float64 // Calculated frames per second
	frameCount          int64   // Count of actual frames (not SPS/PPS)
	lastPTSMicros       int64   // PTS of the last frame returned
}

// StartStreaming starts video streaming with the specified format
func (s *UVCSource) StartStreaming(format, width, height, fps int) (*UVCStream, error) {
	info, err := s.device.DeviceInfo()
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
            frs := si.Descriptors[fdIndex+1 : fdIndex+numFrames+1]
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

                // Calculate frame interval and FPS
                frameIntervalNanos := int64(fr.DefaultFrameInterval)
                actualFps := 0.0
                if frameIntervalNanos > 0 {
                    actualFps = 1_000_000_000.0 / float64(frameIntervalNanos)
                }
                log.Printf("UVC: Started streaming 1920x1080 H264 @ %.1f fps", actualFps)

                return &UVCStream{
                   reader:             reader,
                   buffer:             make([][]byte, 100),
                   frameIntervalNanos: frameIntervalNanos,
                   fps:                actualFps,
                   frameCount:         0,
                   lastPTSMicros:      0,
               }, nil
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

// isH264Frame checks if the NAL unit is an actual frame (not SPS/PPS)
func isH264Frame(data []byte) bool {
    if len(data) < 5 {
        return false
    }
    
    // Find NAL unit start code (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
    startOffset := 0
    if data[0] == 0x00 && data[1] == 0x00 {
        if data[2] == 0x01 {
            startOffset = 3
        } else if data[2] == 0x00 && data[3] == 0x01 {
            startOffset = 4
        }
    }
    
    if startOffset > 0 && startOffset < len(data) {
        nalType := data[startOffset] & 0x1F
        // NAL types: 1-5 are frame data, 7=SPS, 8=PPS, 9=AUD, etc.
        return nalType >= 1 && nalType <= 5
    }
    
    // If we can't determine, assume it's a frame to be safe
    return true
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
    
    // Check if this is an actual frame or SPS/PPS
    if isH264Frame(buf) {
        // It's a frame, increment counter and calculate new PTS
        s.frameCount++
        s.lastPTSMicros = s.frameCount * 100_000 / 3
    }
    // For SPS/PPS, keep the same PTS as the next frame will have
    // (lastPTSMicros stays the same)
    
    return buf, nil
}

// GetPTS returns the PTS of the last frame returned in microseconds
func (s *UVCStream) GetPTS() int64 {
    return s.lastPTSMicros
}

// Close stops the stream
func (s *UVCStream) Close() error {
    return s.reader.Close()
}

// Close closes the UVC source
func (s *UVCSource) Close() error {
    if s.activeStream != nil {
        s.activeStream.Close()
        s.activeStream = nil
    }
    if s.device != nil {
        s.device.Close()
        s.device = nil
    }
    return nil
}
