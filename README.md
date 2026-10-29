# Kinetic Streamer

Kinetic Streamer is a mobile app that turns your phone into an RTSP camera.

## Features

- [x] Run an RTSP server on your phone
- [x] H.264, H.265, VP8, VP9, and AV1 video codecs
- [x] Circular buffer recording for video on disk
- [x] Opus audio codecs
- [ ] Client support
  - [x] SRT
  - [x] WHIP
  - [ ] RTSP
  - [ ] RIST
  - [ ] RTMP
- [ ] Server support
  - [x] RTSP
  - [x] WHEP
  - [x] HLS
 
## Roadmap

- [ ] Adaptive bitrate streaming and congestion control
- [ ] Picture-in-picture multi-camera compositing
- [ ] Timestamp/text burn-in
- [ ] WebView overlays
- [ ] GPS data
- [ ] iOS support
- [ ] Continuous nomination support

## Releases

Pushing a `v*` tag builds the release APK (all ABIs, R8-minified) and attaches it, plus the R8 `mapping.txt`, to a GitHub Release. If these repository secrets are set the APK is signed, otherwise it is published unsigned:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded keystore (`base64 -w0 release.jks`)
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
