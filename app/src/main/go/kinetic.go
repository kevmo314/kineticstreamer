package kinetic

import (
	"fmt"

	"github.com/pion/webrtc/v4"
)

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
	MediaFormatMimeTypeAudioOPUS MediaFormatMimeType = "audio/opus"
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
	case MediaFormatMimeTypeAudioOPUS:
		return webrtc.MimeTypeOpus
	default:
		panic(fmt.Sprintf("unknown media format mime type: %s", t))
	}
}
