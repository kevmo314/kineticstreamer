package com.kevmo314.kineticstreamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class VideoTimingTest {
    @Test
    fun realtimeSourceIsConvertedToMonotonic() {
        // boottime runs ahead of monotonic by the time spent suspended
        val offset = cameraTimestampOffsetNs(SENSOR_TIMESTAMP_SOURCE_REALTIME, 1_000, 5_000)
        assertEquals(-4_000, offset)
        // a frame stamped "now" in boottime lands on "now" in monotonic
        assertEquals(1_000L, 5_000L + offset)
    }

    @Test
    fun unknownOrMissingSourceIsLeftAlone() {
        assertEquals(0L, cameraTimestampOffsetNs(0, 1_000, 5_000))
        assertEquals(0L, cameraTimestampOffsetNs(null, 1_000, 5_000))
    }

    private class Recorder : DecoderFeeder.Sink {
        val queued = ArrayList<Triple<Int, Int, Long>>() // index, size, pts
        val flags = ArrayList<Int>()

        @Synchronized
        override fun queue(index: Int, data: ByteArray, ptsUs: Long, flags: Int) {
            queued.add(Triple(index, data.size, ptsUs))
            this.flags.add(flags)
        }
    }

    @Test
    fun feedUsesFreeIndicesInOrder() {
        val sink = Recorder()
        val feeder = DecoderFeeder(sink)
        feeder.onInputBufferAvailable(3)
        feeder.onInputBufferAvailable(1)
        assertTrue(feeder.feed(ByteArray(10), 100))
        assertTrue(feeder.feed(ByteArray(20), 200, 4))
        assertEquals(listOf(Triple(3, 10, 100L), Triple(1, 20, 200L)), sink.queued)
        assertEquals(listOf(0, 4), sink.flags)
    }

    @Test
    fun feedBlocksUntilAnInputBufferIsFree() {
        val sink = Recorder()
        val feeder = DecoderFeeder(sink)
        val done = CountDownLatch(1)
        thread {
            feeder.feed(ByteArray(5), 1)
            done.countDown()
        }
        // No free buffer: nothing is queued (the old code queued empty buffers in a busy loop)
        assertFalse(done.await(200, TimeUnit.MILLISECONDS))
        assertTrue(sink.queued.isEmpty())
        feeder.onInputBufferAvailable(7)
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(Triple(7, 5, 1L)), sink.queued)
    }

    @Test
    fun closeUnblocksFeed() {
        val feeder = DecoderFeeder(Recorder())
        val result = AtomicBoolean(true)
        val done = CountDownLatch(1)
        thread {
            result.set(feeder.feed(ByteArray(5), 1))
            done.countDown()
        }
        Thread.sleep(100)
        feeder.close()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertFalse(result.get())
    }

    @Test
    fun feedAfterCloseDoesNotQueueEvenWithFreeBuffer() {
        val sink = Recorder()
        val feeder = DecoderFeeder(sink)
        feeder.onInputBufferAvailable(0)
        feeder.close()
        assertFalse(feeder.feed(ByteArray(5), 1))
        assertTrue(sink.queued.isEmpty())
    }
}
