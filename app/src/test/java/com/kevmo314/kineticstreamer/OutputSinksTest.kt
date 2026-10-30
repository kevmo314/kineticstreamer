package com.kevmo314.kineticstreamer

import com.kevmo314.kineticstreamer.kinetic.PLICallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OutputSinksTest {
    private class FakeSink(val url: String, val estimate: Long = 0, val ice: String? = null) : OutputSink {
        val video = CopyOnWriteArrayList<Long>()
        val audio = CopyOnWriteArrayList<Long>()
        @Volatile var closed = false

        override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long {
            video += ptsUs
            return estimate
        }

        override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) {
            audio += ptsUs
        }

        override val iceConnectionState get() = ice

        override fun close() {
            closed = true
        }
    }

    private fun out(url: String, enabled: Boolean = true) = OutputConfiguration(url, enabled)

    private fun sinks(
        configs: List<OutputConfiguration>,
        made: MutableList<FakeSink>,
        estimates: Map<String, Long> = emptyMap(),
    ) = OutputSinks(configs, "video/avc;audio/opus", { c, _ ->
        when {
            c.url.startsWith("bad://") -> null
            c.url.startsWith("fail://") -> throw RuntimeException("refused")
            else -> FakeSink(c.url, estimates[c.url] ?: 0, if (c.url.startsWith("whip://")) "connected" else null).also { made += it }
        }
    }) {}

    @Test
    fun everyOutputOfTheSameProtocolGetsItsOwnSinkAndIsClosed() {
        val made = mutableListOf<FakeSink>()
        val s = sinks(listOf(out("srt://a:1"), out("srt://b:2"), out("whip://c/x"), out("whip://d/y"), out("srt://e:3", false)), made)
        assertEquals(listOf("srt://a:1", "srt://b:2", "whip://c/x", "whip://d/y"), made.map { it.url })

        s.writeVideo(ByteArray(1), 10, 0) {}
        s.writeAudio(ByteArray(1), 20, 0)
        s.close() // drains queued writes first
        for (m in made) {
            assertEquals(m.url, listOf(10L), m.video)
            assertEquals(m.url, listOf(20L), m.audio)
            assertTrue(m.url, m.closed)
        }
    }

    @Test
    fun estimateIsLowestAmongSinksThatHaveOne() {
        val made = mutableListOf<FakeSink>()
        val s = sinks(
            listOf(out("srt://a:1"), out("srt://b:2"), out("rist://c:3")), made,
            estimates = mapOf("srt://a:1" to 5_000_000L, "srt://b:2" to 3_000_000L),
        )
        val got = CopyOnWriteArrayList<Long>()
        s.writeVideo(ByteArray(1), 0, 0) { got += it }
        s.close()
        assertEquals(3_000_000L, got.last()) // one report per sink; the last sees both
    }

    @Test
    fun noEstimateReportsZero() {
        val made = mutableListOf<FakeSink>()
        val s = sinks(listOf(out("srt://a:1")), made)
        val got = CopyOnWriteArrayList<Long>()
        s.writeVideo(ByteArray(1), 0, 0) { got += it }
        s.close()
        assertEquals(0L, got.last())
    }

    @Test
    fun failedConnectionClosesSinksAlreadyOpened() {
        val made = mutableListOf<FakeSink>()
        try {
            sinks(listOf(out("srt://a:1"), out("srt://b:2"), out("fail://c")), made)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("FAIL"))
        }
        assertEquals(2, made.size)
        assertTrue(made.all { it.closed })
    }

    @Test
    fun unknownProtocolIsSkipped() {
        val made = mutableListOf<FakeSink>()
        sinks(listOf(out("bad://x"), out("srt://a:1")), made).close()
        assertEquals(listOf("srt://a:1"), made.map { it.url })
    }

    @Test
    fun whipStateComesFromTheWhipSinkOrNone() {
        val made = mutableListOf<FakeSink>()
        val s = sinks(listOf(out("srt://a:1"), out("whip://c/x")), made)
        assertEquals("connected", s.iceConnectionState())
        assertEquals("none", s.peerConnectionState())
        s.close()
        val s2 = sinks(listOf(out("srt://a:1")), made)
        assertEquals("none", s2.iceConnectionState())
        s2.close()
    }

    @Test
    fun aFailingSinkDoesNotStopTheOthers() {
        val flaky = object : OutputSink {
            override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long = throw RuntimeException("boom")
            override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = throw RuntimeException("boom")
            override fun close() {}
        }
        val good = FakeSink("good", estimate = 1_000_000)
        val s = OutputSinks(listOf(out("srt://a:1"), out("srt://b:2")), "m", { c, _: PLICallback ->
            if (c.url == "srt://a:1") flaky else good
        }) {}
        val got = CopyOnWriteArrayList<Long>()
        s.writeVideo(ByteArray(1), 7, 0) { got += it }
        s.writeAudio(ByteArray(1), 8, 0)
        s.close()
        assertEquals(listOf(7L), good.video)
        assertEquals(listOf(8L), good.audio)
        assertEquals(1_000_000L, got.last())
    }

    /** Blocks every write until [release] is called, like an SRT sink on a dead link. */
    private class StalledSink : OutputSink {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var closed = false

        override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long {
            entered.countDown()
            release.await()
            return 0
        }

        override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) {
            entered.countDown()
            release.await()
        }

        override fun close() {
            closed = true
            release.countDown() // closing a real sink unblocks its stuck write
        }
    }

    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            if (System.nanoTime() > end) fail("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun stalledAndGood(capacity: Int = 256, onPli: () -> Unit = {}): Triple<OutputSinks, StalledSink, FakeSink> {
        val stalled = StalledSink()
        val good = FakeSink("good")
        val s = OutputSinks(listOf(out("srt://a:1"), out("srt://b:2")), "m", { c, _: PLICallback ->
            if (c.url == "srt://a:1") stalled else good
        }, capacity, onPli)
        return Triple(s, stalled, good)
    }

    @Test
    fun aStalledSinkDoesNotDelayTheOthers() {
        val (s, stalled, good) = stalledAndGood()
        for (i in 0 until 50L) {
            s.writeVideo(ByteArray(1), i, if (i == 0L) 1 else 0) {}
            s.writeAudio(ByteArray(1), i, 0)
        }
        awaitUntil("good sink to receive everything") { good.video.size == 50 && good.audio.size == 50 }
        assertEquals((0L until 50L).toList(), good.video.toList())
        assertEquals((0L until 50L).toList(), good.audio.toList())
        assertTrue(stalled.entered.await(5, TimeUnit.SECONDS))
        s.close()
        assertTrue(stalled.closed && good.closed)
    }

    @Test
    fun closeDoesNotHangOnABlockedSink() {
        val (s, stalled, good) = stalledAndGood()
        s.writeVideo(ByteArray(1), 0, 1) {}
        assertTrue(stalled.entered.await(5, TimeUnit.SECONDS))
        val start = System.nanoTime()
        s.close()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        assertTrue("close took ${elapsedMs}ms", elapsedMs < 4000)
        assertTrue(stalled.closed && good.closed)
        assertEquals(listOf(0L), good.video.toList())
    }

    @Test
    fun fullQueueDropsVideoUntilTheNextKeyframeAndAudioButNeverConfig() {
        val plis = AtomicInteger()
        val (s, stalled, good) = stalledAndGood(capacity = 3) { plis.incrementAndGet() }
        s.writeVideo(ByteArray(1), 0, 2) {} // codec config, taken by the writer thread
        assertTrue(stalled.entered.await(5, TimeUnit.SECONDS))
        // The stalled sink's queue now fills: key, p, p
        s.writeVideo(ByteArray(1), 1, 1) {}
        s.writeVideo(ByteArray(1), 2, 0) {}
        s.writeVideo(ByteArray(1), 3, 0) {}
        assertEquals(0, plis.get())
        s.writeVideo(ByteArray(1), 4, 0) {} // full: dropped, asks for a keyframe
        assertEquals(1, plis.get())
        s.writeVideo(ByteArray(1), 5, 0) {} // still dropping, no second request
        s.writeVideo(ByteArray(1), 6, 2) {} // config is kept even when full
        s.writeAudio(ByteArray(1), 7, 0) // dropped
        assertEquals(1, plis.get())
        // The healthy sink saw everything
        awaitUntil("good sink") { good.video.size == 7 && good.audio.size == 1 }
        stalled.release.countDown()
        s.close()
        assertTrue(stalled.closed)
    }

    @Test
    fun videoResumesAtAKeyframeAfterADrop() {
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        val got = CopyOnWriteArrayList<Long>()
        val slow = object : OutputSink {
            override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long {
                started.countDown()
                gate.await()
                got += ptsUs
                return 0
            }
            override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) {}
            override fun close() {}
        }
        val s = OutputSinks(listOf(out("srt://a:1")), "m", { _, _: PLICallback -> slow }, 2) {}
        s.writeVideo(ByteArray(1), 0, 1) {} // in flight
        assertTrue(started.await(5, TimeUnit.SECONDS))
        s.writeVideo(ByteArray(1), 1, 0) {}
        s.writeVideo(ByteArray(1), 2, 0) {}
        s.writeVideo(ByteArray(1), 3, 0) {} // dropped
        s.writeVideo(ByteArray(1), 4, 0) {} // dropped: previous gap
        gate.countDown()
        awaitUntil("queue to drain") { got.size == 3 }
        s.writeVideo(ByteArray(1), 5, 0) {} // still no keyframe: dropped
        s.writeVideo(ByteArray(1), 6, 1) {} // keyframe resumes
        s.writeVideo(ByteArray(1), 7, 0) {}
        s.close()
        assertEquals(listOf(0L, 1L, 2L, 6L, 7L), got.toList())
    }
}
