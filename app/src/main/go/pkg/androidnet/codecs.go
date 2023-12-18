package androidnet

type SupportedCodec int

const (
	SupportedCodecH264 SupportedCodec = iota
	SupportedCodecVP8
	SupportedCodecVP9
	SupportedCodecOpus
	SupportedCodecH265
	SupportedCodecAV1
	SupportedCodecAAC
)
