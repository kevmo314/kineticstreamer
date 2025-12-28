package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * UAC audio source for USB audio devices
 */
class UACSource(fd: Int) : Closeable {
    private var nativeHandle: Long

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        nativeHandle = create(fd)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create UACSource")
        }
    }

    private external fun create(fd: Int): Long

    /**
     * Start audio streaming with the specified format
     * @param sampleRate Sample rate in Hz (e.g., 48000)
     * @param channels Number of channels (1 for mono, 2 for stereo)
     * @param bitDepth Bit depth (typically 16 or 24)
     * @return UACStream handle for reading audio data, or null if failed
     */
    fun startStreaming(sampleRate: Int, channels: Int, bitDepth: Int): UACStream? {
        val streamHandle = startStreaming(nativeHandle, sampleRate, channels, bitDepth)
        return if (streamHandle != 0L) {
            UACStream(streamHandle, sampleRate, channels, bitDepth)
        } else {
            null
        }
    }

    private external fun startStreaming(handle: Long, sampleRate: Int, channels: Int, bitDepth: Int): Long

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
}
