package com.kevmo314.kineticstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingPolicyTest {
    private fun out(url: String, enabled: Boolean = true) = OutputConfiguration(url, enabled)

    @Test
    fun opusUnlessOnlyMpegTsSinks() {
        assertTrue(usesOpusAudio(emptyList()))
        assertTrue(usesOpusAudio(listOf(out("whip://host/x?token=t"))))
        assertTrue(usesOpusAudio(listOf(out("https://host/whip"))))
        assertFalse(usesOpusAudio(listOf(out("srt://host:1"))))
        assertFalse(usesOpusAudio(listOf(out("rist://host:1"), out("srt://host:2"))))
    }

    @Test
    fun whipWinsOverMpegTs() {
        assertTrue(usesOpusAudio(listOf(out("srt://host:1"), out("whip://host/x"))))
    }

    @Test
    fun disabledOutputsAreIgnored() {
        assertTrue(usesOpusAudio(listOf(out("srt://host:1", enabled = false))))
        assertFalse(usesOpusAudio(listOf(out("whip://host/x", enabled = false), out("srt://host:1"))))
    }

    @Test
    fun bitrateSubtractsAudioAndIgnoresSmallChanges() {
        assertNull(nextVideoBitrate(0, 128_000, 2_000_000))
        assertEquals(3_872_000, nextVideoBitrate(4_000_000, 128_000, 2_000_000))
        // 2.05 Mbps is within 5% of the current 2 Mbps; 2.2 Mbps is not
        assertNull(nextVideoBitrate(2_178_000, 128_000, 2_000_000))
        assertEquals(2_200_000, nextVideoBitrate(2_328_000, 128_000, 2_000_000))
    }

    @Test
    fun bitrateIsFlooredAndClamped() {
        assertEquals(100_000, nextVideoBitrate(150_000, 192_000, 2_000_000))
        assertEquals(Int.MAX_VALUE, nextVideoBitrate(Long.MAX_VALUE, 128_000, 2_000_000))
    }

    @Test
    fun fpsOverSlidingWindow() {
        val c = FpsCounter(windowMs = 3000)
        assertEquals(0f, c.fps, 0f)
        c.tick(1000)
        assertEquals(0f, c.fps, 0f) // one frame has no rate
        for (i in 1..30) c.tick(1000L + i * 33)
        assertEquals(30f, c.fps, 1.5f)
    }

    @Test
    fun fpsForgetsOldFramesAndResets() {
        val c = FpsCounter(windowMs = 1000)
        for (i in 0..10) c.tick(i * 100L) // 10 fps
        assertEquals(10f, c.fps, 0.01f)
        for (i in 0..60) c.tick(5_000L + i * 20L) // 50 fps; the old burst is out of the window
        assertEquals(50f, c.fps, 0.5f)
        c.reset()
        assertEquals(0f, c.fps, 0f)
        c.tick(10_000)
        assertEquals(0f, c.fps, 0f)
    }
}
