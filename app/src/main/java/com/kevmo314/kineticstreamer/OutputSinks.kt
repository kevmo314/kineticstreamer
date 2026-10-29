package com.kevmo314.kineticstreamer

import android.net.Uri
import android.util.Log
import com.kevmo314.kineticstreamer.kinetic.PLICallback
import com.kevmo314.kineticstreamer.kinetic.RISTSink
import com.kevmo314.kineticstreamer.kinetic.SRTSink
import com.kevmo314.kineticstreamer.kinetic.WHIPSink
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

/**
 * The enabled outputs and the single thread that writes to them, so network stalls never block
 * the encoder callbacks. Closing drains queued writes, then closes the sinks.
 */
class OutputSinks(configs: List<OutputConfiguration>, mimeTypes: String, onPli: () -> Unit) {
    private var whip: WHIPSink? = null
    private var srt: SRTSink? = null
    private var rist: RISTSink? = null
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
                val url = config.url
                Log.i(TAG, "Creating ${config.protocol} sink")
                try {
                    when {
                        url.startsWith("srt://") -> srt = SRTSink(url, mimeTypes).also { it.setPLICallback(pli) }
                        url.startsWith("rist://") -> rist = RISTSink(url, mimeTypes)
                        config.isWhip() -> {
                            // The token travels in the query string; hand it over separately
                            val uri = Uri.parse(url.removePrefix("whip://"))
                            val token = uri.getQueryParameter("token") ?: ""
                            val endpoint = uri.buildUpon().clearQuery().apply {
                                uri.queryParameterNames.filter { it != "token" }.forEach {
                                    appendQueryParameter(it, uri.getQueryParameter(it))
                                }
                            }.build().toString()
                            whip = WHIPSink(endpoint, token, mimeTypes).also { it.setPLICallback(pli) }
                        }
                        else -> Log.w(TAG, "Unknown output protocol: $url")
                    }
                } catch (e: Exception) {
                    throw IllegalStateException("${config.protocol} connection failed: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            close() // no one holds a reference yet, so don't leak the sinks already connected
            throw e
        }
    }

    /** Queues a video write; [onEstimate] gets the sink's bandwidth estimate (bps, 0 if none). */
    fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int, onEstimate: (Long) -> Unit) = async {
        val gcc = whip?.writeH264(data, ptsUs) ?: 0
        srt?.writeSample(0, data, ptsUs, flags)
        rist?.writeSample(0, data, ptsUs, flags)
        onEstimate(if (gcc > 0) gcc.toLong() else srt?.getEstimatedBandwidth() ?: 0L)
    }

    fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = async {
        whip?.writeOpus(data, ptsUs) // WHIP is only configured with Opus audio
        srt?.writeSample(1, data, ptsUs, flags)
        rist?.writeSample(1, data, ptsUs, flags)
    }

    fun iceConnectionState(): String = whip?.getICEConnectionState() ?: "none"

    fun peerConnectionState(): String = whip?.getPeerConnectionState() ?: "none"

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
        runCatching { whip?.close() }
        runCatching { srt?.close() }
        runCatching { rist?.close() }
    }

    private companion object {
        const val TAG = "OutputSinks"
    }
}
