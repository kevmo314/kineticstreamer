package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * RIST sink for streaming video/audio over the RIST protocol (TR-06-1 / 06-2).
 * The URL is forwarded to librist's parser, so query parameters supported by
 * `rist_parse_address2` (e.g. buffer=, bandwidth=, cname=) are honoured.
 */
class RISTSink(url: String, mimeTypes: String) : Closeable {
    private var nativeHandle: Long

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        nativeHandle = create(url, mimeTypes)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create RISTSink")
        }
    }

    private external fun create(url: String, mimeTypes: String): Long
    private external fun writeH264(handle: Long, data: ByteArray, pts: Long)
    private external fun writeH265(handle: Long, data: ByteArray, pts: Long)
    private external fun writeOpus(handle: Long, data: ByteArray, pts: Long)
    private external fun close(handle: Long)

    /**
     * Write a sample to the RIST stream.
     * @param streamIndex 0 for video, 1 for audio
     * @param data The encoded data
     * @param ptsMicroseconds Presentation timestamp in microseconds
     * @param flags MediaCodec flags (currently unused, accepted for API parity with SRTSink)
     */
    fun writeSample(streamIndex: Int, data: ByteArray, ptsMicroseconds: Long, @Suppress("UNUSED_PARAMETER") flags: Int) {
        when (streamIndex) {
            0 -> writeH264(nativeHandle, data, ptsMicroseconds)
            1 -> writeOpus(nativeHandle, data, ptsMicroseconds)
            else -> throw IllegalArgumentException("Invalid stream index: $streamIndex")
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            close(nativeHandle)
            nativeHandle = 0L
        }
    }

    protected fun finalize() {
        close()
    }
}
