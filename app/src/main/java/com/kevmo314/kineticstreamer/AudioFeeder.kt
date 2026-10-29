package com.kevmo314.kineticstreamer

import java.nio.ByteBuffer

private const val BYTES_PER_FRAME = 4 // stereo 16-bit PCM

/** Mono 16-bit PCM to interleaved stereo. A trailing odd byte is dropped. */
fun monoToStereo(mono: ByteArray): ByteArray {
    val frames = mono.size / 2
    val stereo = ByteArray(frames * BYTES_PER_FRAME)
    for (i in 0 until frames) {
        System.arraycopy(mono, i * 2, stereo, i * 4, 2)
        System.arraycopy(mono, i * 2, stereo, i * 4 + 2, 2)
    }
    return stereo
}

/**
 * Per-band RMS (0..1) of interleaved 16-bit little-endian PCM, written into [out]
 * (one band per element). Reuses [out] so the audio path doesn't allocate per chunk.
 */
fun computeAudioLevels(pcm: ByteArray, out: FloatArray) {
    val perBand = pcm.size / 2 / out.size
    for (band in out.indices) {
        if (perBand == 0) {
            out[band] = 0f
            continue
        }
        var sum = 0L
        for (j in band * perBand until (band + 1) * perBand) {
            val s = (pcm[j * 2 + 1].toInt() shl 8) or (pcm[j * 2].toInt() and 0xFF)
            sum += s * s
        }
        out[band] = (Math.sqrt(sum.toDouble() / perBand).toFloat() / 32768f).coerceIn(0f, 1f)
    }
}

/**
 * Buffers stereo 16-bit PCM chunks and slices them into codec input buffers.
 *
 * PTS is the capture time of the first chunk plus the number of frames consumed so far,
 * so every buffer gets a distinct, contiguous timestamp even when a chunk is split across
 * buffers. When a chunk is dropped because the queue is full, the next accepted chunk
 * re-anchors the timeline to its own capture time so A/V sync doesn't drift.
 */
class AudioFeeder(private val sampleRate: Int = 48000, private val capacity: Int = 100) {
    private class Chunk(val data: ByteArray, val captureUs: Long, val anchor: Boolean)

    private val queue = ArrayDeque<Chunk>()
    private var current: ByteArray? = null // chunk being consumed
    private var currentOffset = 0
    private var baseUs = 0L
    private var consumedFrames = 0L
    private var anchorNext = true

    @get:Synchronized
    var droppedChunks = 0L
        private set

    /** Queues [stereoPcm]; returns false (and drops it) if the queue is full. */
    @Synchronized
    fun offer(stereoPcm: ByteArray, captureTimeNanos: Long): Boolean {
        if (stereoPcm.size < BYTES_PER_FRAME) return true
        if (queue.size >= capacity) {
            droppedChunks++
            anchorNext = true
            return false
        }
        queue.addLast(Chunk(stereoPcm, captureTimeNanos / 1000, anchorNext))
        anchorNext = false
        return true
    }

    /**
     * Copies as many whole frames as fit into [dst]. Returns the PTS in microseconds of the
     * first frame, or -1 if there was no data (nothing is written to [dst] then).
     */
    @Synchronized
    fun fill(dst: ByteBuffer): Long {
        var room = dst.remaining() / BYTES_PER_FRAME * BYTES_PER_FRAME
        var pts = -1L
        while (room > 0) {
            var cur = current
            if (cur == null) {
                // Don't merge across a gap into a buffer that already has data.
                val next = queue.firstOrNull() ?: break
                if (next.anchor && pts >= 0) break
                queue.removeFirst()
                if (next.anchor) {
                    baseUs = next.captureUs
                    consumedFrames = 0
                }
                cur = next.data
                current = cur
                currentOffset = 0
            }
            if (pts < 0) pts = baseUs + consumedFrames * 1_000_000L / sampleRate
            val n = minOf(room, cur.size - currentOffset)
            dst.put(cur, currentOffset, n)
            currentOffset += n
            consumedFrames += n / BYTES_PER_FRAME
            room -= n
            if (currentOffset >= cur.size) current = null
        }
        return pts
    }

    @Synchronized
    fun reset() {
        queue.clear()
        current = null
        currentOffset = 0
        consumedFrames = 0
        anchorNext = true
        droppedChunks = 0
    }
}
