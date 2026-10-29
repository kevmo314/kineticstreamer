package com.kevmo314.kineticstreamer

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Offset to add to camera frame timestamps to put them on CLOCK_MONOTONIC, the clock the audio
 * uses. Cameras whose SENSOR_INFO_TIMESTAMP_SOURCE is REALTIME stamp frames with CLOCK_BOOTTIME
 * (which keeps counting in suspend); UNKNOWN (0) cameras already use CLOCK_MONOTONIC.
 */
fun cameraTimestampOffsetNs(timestampSource: Int?, monotonicNowNs: Long, boottimeNowNs: Long): Long =
    if (timestampSource == SENSOR_TIMESTAMP_SOURCE_REALTIME) monotonicNowNs - boottimeNowNs else 0L

// CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
const val SENSOR_TIMESTAMP_SOURCE_REALTIME = 1

/**
 * Hands encoded frames to a MediaCodec decoder using its async-callback input buffers.
 * The decoder reports free input indices via [onInputBufferAvailable]; [feed] blocks until
 * one is free, so a slow decoder back-pressures the frame reader instead of the decoder
 * being fed empty buffers to keep it cycling.
 */
class DecoderFeeder(private val sink: Sink) {
    fun interface Sink {
        fun queue(index: Int, data: ByteArray, ptsUs: Long, flags: Int)
    }

    private val freeIndices = LinkedBlockingQueue<Int>()

    @Volatile
    private var closed = false

    fun onInputBufferAvailable(index: Int) {
        freeIndices.offer(index)
    }

    /** Returns false if the feeder was closed before an input buffer became free. */
    fun feed(data: ByteArray, ptsUs: Long, flags: Int = 0): Boolean {
        while (!closed) {
            val index = freeIndices.poll(50, TimeUnit.MILLISECONDS) ?: continue
            if (closed) return false // closed while waiting; the codec is going away
            sink.queue(index, data, ptsUs, flags)
            return true
        }
        return false
    }

    fun close() {
        closed = true
    }
}
