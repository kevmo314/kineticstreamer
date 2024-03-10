package com.kevmo314.kineticstreamer

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class StreamingConfiguration(val codec: String): Parcelable {
    fun toMediaFormat(): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            "video/avc", 1920, 1080
        )
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10 * 1024 * 1024)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
        }
        return format
    }
}
