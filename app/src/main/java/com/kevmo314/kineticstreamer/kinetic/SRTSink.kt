package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * SRT sink for streaming video/audio over SRT protocol
 */
class SRTSink(url: String, mimeTypes: String) : Closeable {
    private var nativeHandle: Long
    private var pliCallback: PLICallback? = null

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        nativeHandle = create(url, mimeTypes)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create SRTSink")
        }
    }

    private external fun create(url: String, mimeTypes: String): Long

    private external fun writeH264(handle: Long, data: ByteArray, pts: Long)
    private external fun writeH265(handle: Long, data: ByteArray, pts: Long)
    private external fun writeOpus(handle: Long, data: ByteArray, pts: Long)
    private external fun getBandwidth(handle: Long): Long
    private external fun setPLICallback(handle: Long, callback: PLICallback)

    /**
     * Write a sample to the SRT stream
     * @param streamIndex 0 for video, 1 for audio
     * @param data The encoded data
     * @param ptsMicroseconds Presentation timestamp in microseconds
     * @param flags MediaCodec flags
     */
    fun writeSample(streamIndex: Int, data: ByteArray, ptsMicroseconds: Long, flags: Int) {
        when (streamIndex) {
            0 -> writeH264(nativeHandle, data, ptsMicroseconds) // Video stream
            1 -> writeOpus(nativeHandle, data, ptsMicroseconds) // Audio stream
            else -> throw IllegalArgumentException("Invalid stream index: $streamIndex")
        }
    }

    /**
     * Get estimated bandwidth in bits per second
     */
    fun getEstimatedBandwidth(): Long {
        return getBandwidth(nativeHandle)
    }

    /**
     * Set callback for keyframe requests (PLI - Picture Loss Indication)
     */
    fun setPLICallback(callback: PLICallback) {
        this.pliCallback = callback
        setPLICallback(nativeHandle, callback)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            close(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun close(handle: Long)

    protected fun finalize() {
        close()
    }

    companion object {
        // MediaCodec flags that might be used
        const val BUFFER_FLAG_KEY_FRAME = 1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4
    }
}
