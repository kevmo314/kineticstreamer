module github.com/kevmo314/kinetic

go 1.25.0

require (
	github.com/bluenviron/gortsplib/v4 v4.6.2
	github.com/bluenviron/mediacommon v1.9.2
	github.com/google/uuid v1.6.0
	github.com/kevmo314/go-uvc v0.0.0-20260121051837-2d0096283528
	github.com/mengelbart/scream-go v0.5.0
	github.com/pion/interceptor v0.1.42
	github.com/pion/rtcp v1.2.16
	github.com/pion/rtp v1.8.27
	github.com/pion/transport/v3 v3.1.1
	github.com/pion/webrtc/v4 v4.2.0
)

require (
	github.com/asticode/go-astikit v0.30.0 // indirect
	github.com/asticode/go-astits v1.13.0 // indirect
	github.com/kevmo314/go-usb v0.0.0-20260121051232-1c03c859f62b // indirect
	github.com/pion/datachannel v1.5.10 // indirect
	github.com/pion/dtls/v3 v3.0.9 // indirect
	github.com/pion/ice/v4 v4.1.0 // indirect
	github.com/pion/logging v0.2.4 // indirect
	github.com/pion/mdns/v2 v2.1.0 // indirect
	github.com/pion/randutil v0.1.0 // indirect
	github.com/pion/sctp v1.9.0 // indirect
	github.com/pion/sdp/v3 v3.0.17 // indirect
	github.com/pion/srtp/v3 v3.0.9 // indirect
	github.com/pion/stun/v3 v3.0.2 // indirect
	github.com/pion/turn/v4 v4.1.3 // indirect
	github.com/wlynxg/anet v0.0.5 // indirect
	golang.org/x/crypto v0.33.0 // indirect
	golang.org/x/net v0.35.0 // indirect
	golang.org/x/sys v0.40.0 // indirect
)

replace github.com/bluenviron/mediacommon v1.14.0 => github.com/kevmo314/mediacommon v0.0.0-20240430015613-61465b2e6a80

replace github.com/mengelbart/scream-go => ./third_party/scream-go
