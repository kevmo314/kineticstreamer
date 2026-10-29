package com.kevmo314.kineticstreamer

import android.net.Uri
import android.util.Log
import com.kevmo314.kineticstreamer.kinetic.PLICallback
import com.kevmo314.kineticstreamer.kinetic.RISTSink
import com.kevmo314.kineticstreamer.kinetic.SRTSink
import com.kevmo314.kineticstreamer.kinetic.WHIPSink
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private fun OutputConfiguration.isWhip() =
    url.startsWith("whip://") || url.startsWith("https://") || url.startsWith("http://")

/**
 * Opus if WHIP is enabled or no MPEG-TS sink is. WHIP (WebRTC) requires Opus; SRT and RIST
 * both ship MPEG-TS and prefer AAC.
 */
fun usesOpusAudio(configs: List<OutputConfiguration>): Boolean {
    val enabled = configs.filter { it.enabled }
    return enabled.any { it.isWhip() } || enabled.none { it.url.startsWith("srt://") || it.url.startsWith("rist://") }
}

/**
 * The video bitrate to switch the encoder to for a network estimate of [estimateBps], or null
 * to leave it alone: no estimate, or within 5% of [currentBps]. The audio bitrate is
 * subtracted from the estimate and the result floored at 100 kbps.
 */
fun nextVideoBitrate(estimateBps: Long, audioBps: Int, currentBps: Int): Int? {
    if (estimateBps <= 0) return null
    val target = maxOf(estimateBps - audioBps, 100_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return if (abs(target - currentBps) > currentBps * 0.05) target else null
}

/** One connected output. */
interface OutputSink : Closeable {
    /** Writes a video sample; returns the sink's bandwidth estimate in bps, or 0 if it has none. */
    fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long

    fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int)

    /** WHIP connection states; null for other protocols. */
    val iceConnectionState: String? get() = null
    val peerConnectionState: String? get() = null
}

private class SrtOutput(private val sink: SRTSink) : OutputSink {
    override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long {
        sink.writeSample(0, data, ptsUs, flags)
        return sink.getEstimatedBandwidth()
    }

    override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = sink.writeSample(1, data, ptsUs, flags)

    override fun close() = sink.close()
}

private class RistOutput(private val sink: RISTSink) : OutputSink {
    override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long {
        sink.writeSample(0, data, ptsUs, flags)
        return 0
    }

    override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = sink.writeSample(1, data, ptsUs, flags)

    override fun close() = sink.close()
}

private class WhipOutput(private val sink: WHIPSink) : OutputSink {
    override fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int): Long = sink.writeH264(data, ptsUs).toLong()

    // WHIP is only configured with Opus audio
    override fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = sink.writeOpus(data, ptsUs)

    override val iceConnectionState get() = sink.getICEConnectionState()
    override val peerConnectionState get() = sink.getPeerConnectionState()

    override fun close() = sink.close()
}

private fun createNativeSink(config: OutputConfiguration, mimeTypes: String, pli: PLICallback): OutputSink? {
    val url = config.url
    return when {
        url.startsWith("srt://") -> SrtOutput(SRTSink(url, mimeTypes).also { it.setPLICallback(pli) })
        url.startsWith("rist://") -> RistOutput(RISTSink(url, mimeTypes))
        config.isWhip() -> {
            // The token travels in the query string; hand it over separately
            val uri = Uri.parse(url.removePrefix("whip://"))
            val token = uri.getQueryParameter("token") ?: ""
            val endpoint = uri.buildUpon().clearQuery().apply {
                uri.queryParameterNames.filter { it != "token" }.forEach {
                    appendQueryParameter(it, uri.getQueryParameter(it))
                }
            }.build().toString()
            WhipOutput(WHIPSink(endpoint, token, mimeTypes).also { it.setPLICallback(pli) })
        }
        else -> null
    }
}

/**
 * The enabled outputs, one sink each (any number per protocol), and the single thread that
 * writes to them, so network stalls never block the encoder callbacks. Closing drains queued
 * writes, then closes every sink. If any sink fails to connect, the ones already connected are
 * closed and the constructor throws. [createSink] returns null for an unknown protocol.
 */
class OutputSinks(
    configs: List<OutputConfiguration>,
    mimeTypes: String,
    createSink: (OutputConfiguration, PLICallback) -> OutputSink? = { config, pli -> createNativeSink(config, mimeTypes, pli) },
    onPli: () -> Unit,
) {
    private val sinks = mutableListOf<OutputSink>()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NetworkWriter").apply { priority = Thread.MAX_PRIORITY }
    }

    init {
        val pli = object : PLICallback {
            override fun onPLI() {
                Log.d(TAG, "PLI received, requesting keyframe")
                onPli()
            }
        }
        try {
            for (config in configs.filter { it.enabled }) {
                Log.i(TAG, "Creating ${config.protocol} sink")
                val sink = try {
                    createSink(config, pli)
                } catch (e: Exception) {
                    throw IllegalStateException("${config.protocol} connection failed: ${e.message}", e)
                }
                if (sink == null) Log.w(TAG, "Unknown output protocol: ${config.url}") else sinks += sink
            }
        } catch (e: Exception) {
            close() // no one holds a reference yet, so don't leak the sinks already connected
            throw e
        }
    }

    /** Queues a video write; [onEstimate] gets the lowest bandwidth estimate (bps) among the sinks that have one, 0 if none. */
    fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int, onEstimate: (Long) -> Unit) = async {
        // Every sink must be written, so don't short-circuit on a failure of an earlier one
        val estimates = sinks.map { sink -> guarded(0L) { sink.writeVideo(data, ptsUs, flags) } }
        onEstimate(estimates.filter { it > 0 }.minOrNull() ?: 0L)
    }

    fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = async {
        sinks.forEach { sink -> guarded(Unit) { sink.writeAudio(data, ptsUs, flags) } }
    }

    // One failing sink must not stop the others from being written
    private fun <T> guarded(default: T, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        Log.e(TAG, "Error writing to output: ${e.message}", e)
        default
    }

    fun iceConnectionState(): String = sinks.firstNotNullOfOrNull { it.iceConnectionState } ?: "none"

    fun peerConnectionState(): String = sinks.firstNotNullOfOrNull { it.peerConnectionState } ?: "none"

    private fun async(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to output: ${e.message}", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            // Closing
        }
    }

    fun close() {
        runCatching {
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow()
        }
        sinks.forEach { runCatching { it.close() } }
    }

    private companion object {
        const val TAG = "OutputSinks"
    }
}
