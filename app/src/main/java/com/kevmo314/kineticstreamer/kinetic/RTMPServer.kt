package com.kevmo314.kineticstreamer.kinetic

import java.io.Closeable

/**
 * RTMP server for receiving video/audio streams from external publishers
 */
class RTMPServer(private val port: Int = 1935) : Closeable {
    private var nativeHandle: Long = 0L

    init {
        // Ensure Kinetic library is loaded
        Kinetic

        nativeHandle = nativeCreate(port)
        if (nativeHandle == 0L) {
            throw RuntimeException("Failed to create RTMPServer")
        }
    }

    /**
     * Start the RTMP server
     * @return true if started successfully
     */
    fun start(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeStart(nativeHandle) != 0
    }

    /**
     * Stop the RTMP server
     */
    fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
            nativeHandle = 0L
        }
    }

    /**
     * Get the port the server is listening on
     */
    fun getPort(): Int {
        if (nativeHandle == 0L) return 0
        return nativeGetPort(nativeHandle)
    }

    /**
     * Get the current source (may be null if no publisher connected)
     */
    fun getSource(): RTMPSource? {
        if (nativeHandle == 0L) return null
        val sourceHandle = nativeGetSource(nativeHandle)
        if (sourceHandle == 0L) return null
        return RTMPSource(sourceHandle)
    }

    /**
     * Wait for a publisher to connect
     * @param timeoutMs timeout in milliseconds
     * @return RTMPSource if a publisher connected, null on timeout
     */
    fun waitForSource(timeoutMs: Int): RTMPSource? {
        if (nativeHandle == 0L) return null
        val sourceHandle = nativeWaitForSource(nativeHandle, timeoutMs)
        if (sourceHandle == 0L) return null
        return RTMPSource(sourceHandle)
    }

    override fun close() {
        stop()
    }

    private external fun nativeCreate(port: Int): Long
    private external fun nativeStart(handle: Long): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeGetPort(handle: Long): Int
    private external fun nativeGetSource(handle: Long): Long
    private external fun nativeWaitForSource(handle: Long, timeoutMs: Int): Long
}
