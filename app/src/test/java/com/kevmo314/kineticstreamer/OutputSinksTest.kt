package com.kevmo314.kineticstreamer

import com.kevmo314.kineticstreamer.kinetic.PLICallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

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
        assertEquals(listOf(3_000_000L), got.toList())
    }

    @Test
    fun noEstimateReportsZero() {
        val made = mutableListOf<FakeSink>()
        val s = sinks(listOf(out("srt://a:1")), made)
        val got = CopyOnWriteArrayList<Long>()
        s.writeVideo(ByteArray(1), 0, 0) { got += it }
        s.close()
        assertEquals(listOf(0L), got.toList())
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
        assertEquals(listOf(1_000_000L), got.toList())
    }
}
