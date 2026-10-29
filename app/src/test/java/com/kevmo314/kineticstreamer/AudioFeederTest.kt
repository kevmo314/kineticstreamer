package com.kevmo314.kineticstreamer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class AudioFeederTest {
    // 48kHz: 480 frames = 10ms = 1920 stereo bytes
    private fun chunk(frames: Int, fill: Int = 1) = ByteArray(frames * 4) { fill.toByte() }

    private fun ms(n: Long) = n * 1_000_000L

    @Test
    fun emptyFeederReturnsNoPtsAndWritesNothing() {
        val feeder = AudioFeeder()
        val dst = ByteBuffer.allocate(1024)
        assertEquals(-1L, feeder.fill(dst))
        assertEquals(0, dst.position())
    }

    @Test
    fun ptsAdvancesAcrossLeftoverBytesWithoutDuplicates() {
        val feeder = AudioFeeder()
        // One 1000-frame chunk captured at t=5ms, consumed through 480-frame buffers.
        feeder.offer(chunk(1000), ms(5))
        val pts = mutableListOf<Long>()
        repeat(2) {
            val dst = ByteBuffer.allocate(480 * 4)
            pts += feeder.fill(dst)
            assertEquals(480 * 4, dst.position())
        }
        assertEquals(listOf(5_000L, 15_000L), pts)

        // Leftover 40 frames + a new chunk: PTS continues from consumed frames, not the new chunk's capture time.
        feeder.offer(chunk(480), ms(30))
        val dst = ByteBuffer.allocate(480 * 4)
        val third = feeder.fill(dst)
        assertEquals(5_000L + 960L * 1_000_000 / 48000, third)
        assertEquals(480 * 4, dst.position())
        assertTrue(pts.last() < third)
    }

    @Test
    fun fillSpansChunksAndKeepsOrder() {
        val feeder = AudioFeeder()
        feeder.offer(chunk(100, 1), 0)
        feeder.offer(chunk(100, 2), ms(2))
        val dst = ByteBuffer.allocate(150 * 4)
        assertEquals(0L, feeder.fill(dst))
        val out = dst.array()
        assertEquals(1, out[0].toInt())
        assertEquals(1, out[100 * 4 - 1].toInt())
        assertEquals(2, out[100 * 4].toInt())
        assertEquals(2, out[150 * 4 - 1].toInt())
        // The remaining 50 frames come next, 150 frames after the start.
        val dst2 = ByteBuffer.allocate(150 * 4)
        assertEquals(150L * 1_000_000 / 48000, feeder.fill(dst2))
        assertEquals(50 * 4, dst2.position())
    }

    @Test
    fun onlyWholeFramesAreCopied() {
        val feeder = AudioFeeder()
        feeder.offer(chunk(10), 0)
        val dst = ByteBuffer.allocate(4 * 3 + 3)
        feeder.fill(dst)
        assertEquals(12, dst.position())
    }

    @Test
    fun overflowDropsNewestAndReanchorsAfterGap() {
        val feeder = AudioFeeder(capacity = 2)
        assertTrue(feeder.offer(chunk(480), ms(0)))
        assertTrue(feeder.offer(chunk(480), ms(10)))
        assertFalse(feeder.offer(chunk(480), ms(20))) // dropped
        assertEquals(1L, feeder.droppedChunks)

        val dst = ByteBuffer.allocate(480 * 4)
        assertEquals(0L, feeder.fill(dst))
        dst.clear()
        assertEquals(10_000L, feeder.fill(dst))

        // After the gap, the next chunk is anchored to its own capture time (t=30ms), not 20ms.
        assertTrue(feeder.offer(chunk(480), ms(30)))
        dst.clear()
        assertEquals(30_000L, feeder.fill(dst))
    }

    @Test
    fun bufferDoesNotMergeAcrossGap() {
        val feeder = AudioFeeder(capacity = 2)
        assertTrue(feeder.offer(chunk(100, 1), 0))
        assertTrue(feeder.offer(chunk(100, 2), ms(2)))
        assertFalse(feeder.offer(chunk(100, 9), ms(4))) // dropped -> gap
        feeder.fill(ByteBuffer.allocate(100 * 4)) // consumes the first chunk
        assertTrue(feeder.offer(chunk(100, 3), ms(50)))

        // Second chunk is returned alone; the post-gap chunk starts a new buffer at its own capture time.
        val dst = ByteBuffer.allocate(1000 * 4)
        assertEquals(100L * 1_000_000 / 48000, feeder.fill(dst))
        assertEquals(100 * 4, dst.position())
        dst.clear()
        assertEquals(50_000L, feeder.fill(dst))
        assertEquals(3, dst.array()[0].toInt())
    }

    @Test
    fun emptyAndTinyChunksAreIgnored() {
        val feeder = AudioFeeder()
        assertTrue(feeder.offer(ByteArray(0), 0))
        assertTrue(feeder.offer(ByteArray(3), 0))
        assertEquals(-1L, feeder.fill(ByteBuffer.allocate(64)))
    }

    @Test
    fun resetStartsOver() {
        val feeder = AudioFeeder()
        feeder.offer(chunk(100), ms(1))
        feeder.fill(ByteBuffer.allocate(40))
        feeder.reset()
        assertEquals(-1L, feeder.fill(ByteBuffer.allocate(64)))
        feeder.offer(chunk(100), ms(500))
        assertEquals(500_000L, feeder.fill(ByteBuffer.allocate(64)))
    }

    @Test
    fun monoToStereoDuplicatesEachSample() {
        val mono = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(byteArrayOf(1, 2, 1, 2, 3, 4, 3, 4), monoToStereo(mono))
    }

    @Test
    fun monoToStereoDropsTrailingOddByte() {
        assertArrayEquals(byteArrayOf(1, 2, 1, 2), monoToStereo(byteArrayOf(1, 2, 3)))
        assertEquals(0, monoToStereo(ByteArray(0)).size)
    }

    @Test
    fun audioLevelsOfSilenceAndFullScale() {
        val levels = FloatArray(4)
        computeAudioLevels(ByteArray(64), levels)
        assertArrayEquals(FloatArray(4), levels, 0f)

        // Full-scale negative samples (0x8000 little-endian) -> RMS 1.0
        val loud = ByteArray(64) { if (it % 2 == 0) 0 else 0x80.toByte() }
        computeAudioLevels(loud, levels)
        for (l in levels) assertEquals(1f, l, 1e-4f)
    }

    @Test
    fun audioLevelsWithTooFewSamplesAreZero() {
        val levels = FloatArray(12) { 0.5f }
        computeAudioLevels(ByteArray(4), levels)
        assertArrayEquals(FloatArray(12), levels, 0f)
    }
}
