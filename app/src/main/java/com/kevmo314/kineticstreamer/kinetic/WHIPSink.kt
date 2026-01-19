package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

interface PLICallback {
    fun onPLI()
}

/**
 * WHIP sink for WebRTC streaming
 */
class WHIPSink(url: String, token: String, mimeTypes: String) : Closeable {
    private var nativeHandle: Long

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        nativeHandle = create(url, token, mimeTypes)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create WHIPSink")
        }
    }

    private external fun create(url: String, token: String, mimeTypes: String): Long
    private external fun setPLICallback(handle: Long, callback: PLICallback)

    private external fun writeH264(handle: Long, data: ByteArray, pts: Long): Int
    private external fun writeOpus(handle: Long, data: ByteArray, pts: Long)

    /**
     * Set callback for Picture Loss Indication (PLI) requests
     */
    fun setPLICallback(callback: PLICallback) {
        setPLICallback(nativeHandle, callback)
    }

    /**
     * Write H.264 video data directly
     * @return Target bitrate in bps from congestion control
     */
    fun writeH264(data: ByteArray, ptsMicroseconds: Long): Int {
        return writeH264(nativeHandle, data, ptsMicroseconds)
    }

    /**
     * Write Opus audio data directly
     */
    fun writeOpus(data: ByteArray, ptsMicroseconds: Long) {
        writeOpus(nativeHandle, data, ptsMicroseconds)
    }

    /**
     * Write a sample to the WHIP stream
     * @param streamIndex 0 for video, 1 for audio
     * @param data The encoded data
     * @param ptsMicroseconds Presentation timestamp in microseconds
     * @param flags MediaCodec flags
     * @return Target bitrate in bps for video, 0 for audio
     */
    fun writeSample(streamIndex: Int, data: ByteArray, ptsMicroseconds: Long, flags: Int): Int {
        return when (streamIndex) {
            0 -> writeH264(nativeHandle, data, ptsMicroseconds) // Video stream returns bitrate
            1 -> {
                writeOpus(nativeHandle, data, ptsMicroseconds) // Audio stream
                0 // No bitrate control for audio
            }
            else -> throw IllegalArgumentException("Invalid stream index: $streamIndex")
        }
    }

    override fun close() {
        if (nativeHandle != 0L) {
            close(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun close(handle: Long)
    private external fun getICEConnectionState(handle: Long): String
    private external fun getPeerConnectionState(handle: Long): String

    /**
     * Get the current ICE connection state
     */
    fun getICEConnectionState(): String {
        return if (nativeHandle != 0L) getICEConnectionState(nativeHandle) else "unknown"
    }

    /**
     * Get the current peer connection state
     */
    fun getPeerConnectionState(): String {
        return if (nativeHandle != 0L) getPeerConnectionState(nativeHandle) else "unknown"
    }

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
