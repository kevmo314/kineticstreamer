package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * RTMP source for receiving frames from an RTMP publish session
 */
class RTMPSource(private var handle: Long) : Closeable {

    init {
        // Ensure Kinetic library is loaded
        Kinetic
    }

    /**
     * Read a video frame from the source (blocking)
     * Returns H.264 NALUs in Annex B format
     * Returns null if source is closed
     */
    fun readVideoFrame(): ByteArray? {
        if (handle == 0L) return null
        return nativeReadVideoFrame(handle)
    }

    /**
     * Read an audio frame from the source (blocking)
     * Returns raw AAC frame data
     * Returns null if source is closed
     */
    fun readAudioFrame(): ByteArray? {
        if (handle == 0L) return null
        return nativeReadAudioFrame(handle)
    }

    /**
     * Get the PTS of the last video frame in microseconds
     */
    fun getVideoPTS(): Long {
        if (handle == 0L) return 0L
        return nativeGetVideoPTS(handle)
    }

    /**
     * Get the PTS of the last audio frame in microseconds
     */
    fun getAudioPTS(): Long {
        if (handle == 0L) return 0L
        return nativeGetAudioPTS(handle)
    }

    /**
     * Check if the source is closed
     */
    fun isClosed(): Boolean {
        if (handle == 0L) return true
        return nativeIsClosed(handle) != 0
    }

    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0L
        }
    }

    private external fun nativeReadVideoFrame(handle: Long): ByteArray?
    private external fun nativeReadAudioFrame(handle: Long): ByteArray?
    private external fun nativeGetVideoPTS(handle: Long): Long
    private external fun nativeGetAudioPTS(handle: Long): Long
    private external fun nativeIsClosed(handle: Long): Int
    private external fun nativeClose(handle: Long)
}
