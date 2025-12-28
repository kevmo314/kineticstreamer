package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * UAC audio stream for reading PCM audio data from USB audio devices
 */
class UACStream(
    private var nativeHandle: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int
) : Closeable {

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        if (nativeHandle == 0L) {
            throw RuntimeException("Invalid UACStream handle")
        }
    }

    /**
     * Read the next audio frame from the stream
     * @return ByteArray containing PCM audio data, or null if no data available
     */
    fun readFrame(): ByteArray? {
        return if (nativeHandle != 0L) {
            readFrame(nativeHandle)
        } else {
            null
        }
    }

    private external fun readFrame(handle: Long): ByteArray?

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

    /**
     * Get the expected frame size in bytes
     */
    fun getFrameSize(): Int {
        // Calculate expected frame size based on sample rate, channels, and bit depth
        // This is a rough estimate - actual frame sizes may vary
        val bytesPerSample = bitDepth / 8
        val samplesPerFrame = sampleRate / 100  // Assuming ~10ms frames
        return samplesPerFrame * channels * bytesPerSample
    }

    /**
     * Check if the stream is valid
     */
    fun isValid(): Boolean {
        return nativeHandle != 0L
    }
}
