package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * UVC source for USB video devices
 */
class UVCSource(fd: Int) : Closeable {
    private var nativeHandle: Long

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        nativeHandle = create(fd)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create UVCSource")
        }
    }

    private external fun create(fd: Int): Long

    /**
     * Start streaming with specified format
     */
    fun startStreaming(format: Int, width: Int, height: Int, fps: Int): UVCStream {
        val streamHandle = startStreaming(nativeHandle, format, width, height, fps)
        if (streamHandle == 0L) {
            throw RuntimeException("Failed to start streaming")
        }
        return UVCStream(streamHandle)
    }

    private external fun startStreaming(handle: Long, format: Int, width: Int, height: Int, fps: Int): Long

    override fun close() {
        if (nativeHandle != 0L) {
            close(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun close(handle: Long)

    // Removed finalize() to prevent premature cleanup by GC
    // Callers must explicitly call close()
}
