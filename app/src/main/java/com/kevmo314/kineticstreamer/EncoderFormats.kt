package com.kevmo314.kineticstreamer

import android.media.AudioFormat
import android.media.MediaCodecInfo
import android.media.MediaFormat

const val VIDEO_WIDTH = 1920
const val VIDEO_HEIGHT = 1080
const val INITIAL_VIDEO_BITRATE = 2_000_000 // adjusted dynamically from the network estimate
const val OPUS_BITRATE = 128_000
const val AAC_BITRATE = 192_000

/** 1080p30 low-latency CBR, with headroom for bitrate adaptation up to 7.5 Mbps. */
fun videoEncoderFormat(mimeType: String): MediaFormat =
    MediaFormat.createVideoFormat(mimeType, VIDEO_WIDTH, VIDEO_HEIGHT).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, INITIAL_VIDEO_BITRATE)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_CAPTURE_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        // CBR with frame drop for strict constant bitrate
        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)
        setInteger("max-bitrate", 7_500_000)
        // No B-frames, so the frame rate stays consistent during high motion
        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        // Real-time priority and low latency so the encoder doesn't back up during high motion
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        setInteger(MediaFormat.KEY_LATENCY, 3)
        setInteger(MediaFormat.KEY_OPERATING_RATE, 60) // above the frame rate, for headroom
    }

/** Stereo 48 kHz PCM in (mono is duplicated before encoding); Opus for WHIP, AAC-LC for MPEG-TS. */
fun audioEncoderFormat(opus: Boolean): MediaFormat {
    val mime = if (opus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
    return MediaFormat.createAudioFormat(mime, 48000, 2).apply {
        setInteger(MediaFormat.KEY_BIT_RATE, if (opus) OPUS_BITRATE else AAC_BITRATE)
        setInteger(MediaFormat.KEY_PRIORITY, 0)
        setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_OUT_STEREO)
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        if (opus) {
            setInteger(MediaFormat.KEY_LATENCY, 0)
            setInteger("opus-use-inband-fec", 0)
            setInteger("opus-packet-loss-perc", 0)
            setString("opus-application", "voip")
        } else {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
    }
}
