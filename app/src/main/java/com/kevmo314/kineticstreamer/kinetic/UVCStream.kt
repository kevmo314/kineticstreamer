package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * UVC stream handle for receiving frames
 */
class UVCStream(private var handle: Long) : Closeable {

    init {
        // Ensure Kinetic library is loaded
        Kinetic
    }

    /**
     * Read a frame from the stream
     * Returns null if no frame available
     */
    fun readFrame(): ByteArray? {
        if (handle == 0L) return null
        return readFrame(handle)
    }

    /**
     * Get the PTS (presentation timestamp) of the last frame returned
     * Returns the timestamp in microseconds
     */
    fun getPTS(): Long {
        if (handle == 0L) return 0L
        return getPTS(handle)
    }

    private external fun readFrame(handle: Long): ByteArray?
    private external fun getPTS(handle: Long): Long

    override fun close() {
        if (handle != 0L) {
            close(handle)
            handle = 0L
        }
    }

    private external fun close(handle: Long)

    // Removed finalize() to prevent premature cleanup by GC
    // Callers must explicitly call close()
}
