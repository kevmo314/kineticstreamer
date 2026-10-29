package com.kevmo314.kineticstreamer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Passed to [IStreamingService.startStreaming]. The service currently ignores it: the encoder
 * is fixed at 1080p30 and the codec comes from [Settings].
 */
@Parcelize
data class StreamingConfiguration(val videoCodec: String, val audioCodec: String, val container: Int): Parcelable
