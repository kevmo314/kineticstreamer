package com.kevmo314.kineticstreamer

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamingConfiguration(val codec: String, val container: Int): Parcelable {
    fun toMediaFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            codec, 1280, 720
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
//            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, 1)
            format.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, 1)
        }
//        format.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.VP9Profile0)
//        format.setInteger(MediaFormat.KEY_LEVEL, CodecProfileLevel.VP9Level41)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10 * 1024 * 1024)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (codec == MediaFormat.MIMETYPE_VIDEO_AVC || codec == MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
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
