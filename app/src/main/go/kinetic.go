package kinetic

import (
	"encoding/json"
	"fmt"

	"github.com/bluenviron/mediacommon/pkg/formats/mpegts"
	"github.com/pion/webrtc/v4"
)

// Sink is the common interface for all output sinks
type Sink interface {
	// WriteSample writes a sample to the sink. Returns true if a keyframe is requested.
	WriteSample(trackIndex int, buf []byte, ptsMicroseconds int64) (keyframeRequested bool, err error)
	Close() error
}

// SinkConfig represents the JSON configuration for creating a sink
type SinkConfig struct {
	Type        string `json:"type"`
	Enabled     bool   `json:"enabled"`
	URL         string `json:"url,omitempty"`
	BearerToken string `json:"bearerToken,omitempty"`
	Host        string `json:"host,omitempty"`
	Port        int    `json:"port,omitempty"`
	StreamID    string `json:"streamId,omitempty"`
	Passphrase  string `json:"passphrase,omitempty"`
	StreamKey   string `json:"streamKey,omitempty"`
	Path        string `json:"path,omitempty"`
}

// NewSinkFromJSON creates a sink from a JSON configuration string
func NewSinkFromJSON(configJSON, encodedMediaFormatMimeTypes string) (Sink, error) {
	var cfg SinkConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return nil, fmt.Errorf("failed to parse sink config: %w", err)
	}

	if !cfg.Enabled {
		return nil, fmt.Errorf("sink is disabled")
	}

	switch cfg.Type {
	case "WHIP":
		return NewWHIPSink(cfg.URL, cfg.BearerToken, encodedMediaFormatMimeTypes)
	case "SRT":
		// Build SRT URL with query params
		srtURL := fmt.Sprintf("srt://%s:%d", cfg.Host, cfg.Port)
		if cfg.StreamID != "" || cfg.Passphrase != "" {
			srtURL += "?"
			if cfg.StreamID != "" {
				srtURL += "streamid=" + cfg.StreamID
				if cfg.Passphrase != "" {
					srtURL += "&"
				}
			}
			if cfg.Passphrase != "" {
				srtURL += "passphrase=" + cfg.Passphrase
			}
		}
		return NewSRTSink(srtURL, encodedMediaFormatMimeTypes)
	case "RTMP":
		return nil, fmt.Errorf("RTMP sink not yet implemented")
	case "RTSP":
		port := cfg.Port
		if port == 0 {
			port = 8554
		}
		// RTSP server needs a disk sink as its source
		diskSink, err := NewDiskSink(cfg.Path, encodedMediaFormatMimeTypes)
		if err != nil {
			return nil, fmt.Errorf("failed to create disk sink for RTSP: %w", err)
		}
		return NewRTSPServerSink(diskSink, encodedMediaFormatMimeTypes)
	case "Disk":
		return NewDiskSink(cfg.Path, encodedMediaFormatMimeTypes)
	default:
		return nil, fmt.Errorf("unknown sink type: %s", cfg.Type)
	}
}

type SampleWriter interface {
	WriteSample(buf []byte, ptsMicroseconds int64) error
}

type MediaFormatMimeType string

const (
	MediaFormatMimeTypeVideoVP8  MediaFormatMimeType = "video/x-vnd.on2.vp8"
	MediaFormatMimeTypeVideoVP9  MediaFormatMimeType = "video/x-vnd.on2.vp9"
	MediaFormatMimeTypeVideoAV1  MediaFormatMimeType = "video/av01"
	MediaFormatMimeTypeVideoH264 MediaFormatMimeType = "video/avc"
	MediaFormatMimeTypeVideoH265 MediaFormatMimeType = "video/hevc"
	MediaFormatMimeTypeAudioAAC  MediaFormatMimeType = "audio/aac"
	MediaFormatMimeTypeAudioOpus MediaFormatMimeType = "audio/opus"
)

func (t MediaFormatMimeType) PionMimeType() string {
	switch t {
	case MediaFormatMimeTypeVideoVP8:
		return webrtc.MimeTypeVP8
	case MediaFormatMimeTypeVideoVP9:
		return webrtc.MimeTypeVP9
	case MediaFormatMimeTypeVideoAV1:
		return webrtc.MimeTypeAV1
	case MediaFormatMimeTypeVideoH264:
		return webrtc.MimeTypeH264
	case MediaFormatMimeTypeVideoH265:
		return webrtc.MimeTypeH265
	case MediaFormatMimeTypeAudioAAC:
		return "audio/aac"
	case MediaFormatMimeTypeAudioOpus:
		return webrtc.MimeTypeOpus
	default:
		panic(fmt.Sprintf("unknown media format mime type: %s", t))
	}
}

func (t MediaFormatMimeType) MPEGTSCodec() mpegts.Codec {
	switch t {
	case MediaFormatMimeTypeVideoH264:
		return &mpegts.CodecH264{}
	case MediaFormatMimeTypeVideoH265:
		return &mpegts.CodecH265{}
	case MediaFormatMimeTypeAudioAAC:
		return &mpegts.CodecMPEG4Audio{}
	case MediaFormatMimeTypeAudioOpus:
		return &mpegts.CodecOpus{}
	default:
		panic(fmt.Sprintf("unknown media format mime type: %s", t))
	}
}

type MediaCodecBufferFlag int32

const (
	MediaCodecBufferFlagKeyFrame     MediaCodecBufferFlag = 1
	MediaCodecBufferFlagCodecConfig  MediaCodecBufferFlag = 2
	MediaCodecBufferFlagEndOfStream  MediaCodecBufferFlag = 4
	MediaCodecBufferFlagPartialFrame MediaCodecBufferFlag = 8
	MediaCodecBufferFlagMuxerData    MediaCodecBufferFlag = 16
	MediaCodecBufferFlagDecodeOnly   MediaCodecBufferFlag = 32
)
