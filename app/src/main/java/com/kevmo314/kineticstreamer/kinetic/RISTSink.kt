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
    private external fun writeSample(handle: Long, streamIndex: Int, data: ByteArray, pts: Long, flags: Int)
    private external fun close(handle: Long)

    /**
     * Write a sample to the RIST stream.
     * @param streamIndex 0 for video, 1 for audio
     * @param data The encoded data
     * @param ptsMicroseconds Presentation timestamp in microseconds
     * @param flags MediaCodec flags (BUFFER_FLAG_KEY_FRAME marks keyframes)
     */
    fun writeSample(streamIndex: Int, data: ByteArray, ptsMicroseconds: Long, flags: Int) {
        require(streamIndex == 0 || streamIndex == 1) { "Invalid stream index: $streamIndex" }
        writeSample(nativeHandle, streamIndex, data, ptsMicroseconds, flags)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            close(nativeHandle)
            nativeHandle = 0L
        }
    }
}
