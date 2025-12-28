package com.kevmo314.kineticstreamer

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamingConfiguration(val videoCodec: String, val audioCodec: String, val container: Int): Parcelable {
    val videoMediaFormat: MediaFormat
        get() {
            val format = MediaFormat.createVideoFormat(
                videoCodec, 1280, 720
            )
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4 * 1024 * 1024)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            return format
        }

    val audioMediaFormat: MediaFormat
        get() {
            val format = MediaFormat.createAudioFormat(
                audioCodec, 48000, 2
            )
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            if (audioCodec == MediaFormat.MIMETYPE_AUDIO_AAC) {
                format.setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC
                )
            }
            return format
        }

    val fileExtension: String
        get() = when (container) {
            MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM -> "webm"
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 -> "mp4"
            MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP -> "3gp"
            MediaMuxer.OutputFormat.MUXER_OUTPUT_HEIF -> "heif"
            MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG -> "ogg"
            else -> "mp4"
        }
}
