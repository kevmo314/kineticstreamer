package com.kevmo314.kineticstreamer

import android.net.Uri
import android.util.Log
import com.kevmo314.kineticstreamer.kinetic.PLICallback
import com.kevmo314.kineticstreamer.kinetic.RISTSink
import com.kevmo314.kineticstreamer.kinetic.SRTSink
import com.kevmo314.kineticstreamer.kinetic.WHIPSink
import java.io.Closeable
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

private const val TAG = "OutputSinks"
private const val KEY_FRAME = 1
private const val CODEC_CONFIG = 2

/**
 * Writes to one [OutputSink] on its own thread through a bounded queue, so a stalled sink only
 * loses its own data. When the queue is full, audio is dropped and video is dropped until the
 * next keyframe (a P-frame after a gap would not decode), and [onDrop] is called so the caller
 * can ask for one. Codec config is never dropped. Samples are shared read-only between writers:
 * the encoder callbacks hand over a fresh array per sample and nothing mutates it afterwards.
 */
private class SinkWriter(
    private val sink: OutputSink,
    name: String,
    private val capacity: Int,
    private val onDrop: () -> Unit,
    private val onEstimate: () -> Unit,
) {
    private val lock = Object()
    private val queue = ArrayDeque<() -> Unit>()
    private var closed = false
    private var dropUntilKeyframe = false

    // The requested keyframe may have arrived while the queue was still full, and been dropped
    private var keyframeMayBeLost = false

    /** The sink's latest bandwidth estimate in bps, 0 if it has none. */
    @Volatile var estimate = 0L
        private set

    private val thread = Thread({
        while (true) {
            val task = synchronized(lock) {
                while (queue.isEmpty() && !closed) lock.wait()
                queue.removeFirstOrNull()
            } ?: break
            try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Error writing to output: ${e.message}", e)
            }
        }
    }, name).apply {
        priority = Thread.MAX_PRIORITY
        isDaemon = true // a write blocked in native code must not keep the process alive
        start()
    }

    fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int) {
        var requestKeyframe = false
        synchronized(lock) {
            if (closed) return
            val keep = when {
                flags and CODEC_CONFIG != 0 -> true
                queue.size >= capacity -> false
                flags and KEY_FRAME != 0 -> true.also { dropUntilKeyframe = false }
                else -> !dropUntilKeyframe
            }
            if (keep) {
                enqueue {
                    val e = sink.writeVideo(data, ptsUs, flags)
                    estimate = e
                    onEstimate()
                }
            } else {
                requestKeyframe = !dropUntilKeyframe || (keyframeMayBeLost && queue.size <= capacity / 2)
                if (requestKeyframe) keyframeMayBeLost = queue.size >= capacity
                dropUntilKeyframe = true
            }
        }
        if (requestKeyframe) {
            Log.w(TAG, "Output queue full, dropping video until the next keyframe")
            onDrop()
        }
    }

    fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = synchronized(lock) {
        if (!closed && queue.size < capacity) enqueue { sink.writeAudio(data, ptsUs, flags) }
    }

    private fun enqueue(task: () -> Unit) {
        queue.addLast(task)
        lock.notify()
    }

    /** Stops accepting writes; the thread exits once the queue has drained. */
    fun stop() = synchronized(lock) {
        closed = true
        lock.notifyAll()
    }

    /** Waits until [deadlineNanos] for the queue to drain, then discards what is left. */
    fun join(deadlineNanos: Long) {
        thread.join(maxOf(1, (deadlineNanos - System.nanoTime()) / 1_000_000))
        synchronized(lock) { queue.clear() }
    }
}

/**
 * The enabled outputs, one sink each (any number per protocol), each written on its own thread
 * so network stalls never block the encoder callbacks or the other outputs. Closing drains
 * queued writes (up to [CLOSE_TIMEOUT_MS] in total), then closes every sink (each on its own
 * thread, as closing can wait behind a stuck write). If any sink fails to connect, the ones already connected are closed
 * and the constructor throws. [createSink] returns null for an unknown protocol.
 */
class OutputSinks(
    configs: List<OutputConfiguration>,
    mimeTypes: String,
    createSink: (OutputConfiguration, PLICallback) -> OutputSink? = { config, pli -> createNativeSink(config, mimeTypes, pli) },
    private val queueCapacity: Int = QUEUE_CAPACITY,
    private val onPli: () -> Unit,
) {
    private val sinks = mutableListOf<OutputSink>()
    private val writers = mutableListOf<SinkWriter>()

    // Called from every writer thread; serialised so the listener sees one estimate at a time
    private val estimateLock = Any()
    @Volatile private var estimateListener: ((Long) -> Unit)? = null

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
                if (sink == null) {
                    Log.w(TAG, "Unknown output protocol: ${config.url}")
                } else {
                    sinks += sink
                    writers += SinkWriter(sink, "NetworkWriter-${writers.size}", queueCapacity, onPli, ::reportEstimate)
                }
            }
        } catch (e: Exception) {
            close() // no one holds a reference yet, so don't leak the sinks already connected
            throw e
        }
    }

    /**
     * Queues a video write on every sink. [onEstimate] is called after each sink's write with
     * the lowest bandwidth estimate (bps) among the sinks that have one, 0 if none.
     */
    fun writeVideo(data: ByteArray, ptsUs: Long, flags: Int, onEstimate: (Long) -> Unit) {
        estimateListener = onEstimate
        writers.forEach { it.writeVideo(data, ptsUs, flags) }
    }

    fun writeAudio(data: ByteArray, ptsUs: Long, flags: Int) = writers.forEach { it.writeAudio(data, ptsUs, flags) }

    private fun reportEstimate() = synchronized(estimateLock) {
        estimateListener?.invoke(writers.map { it.estimate }.filter { it > 0 }.minOrNull() ?: 0L)
    }

    fun iceConnectionState(): String = sinks.firstNotNullOfOrNull { it.iceConnectionState } ?: "none"

    fun peerConnectionState(): String = sinks.firstNotNullOfOrNull { it.peerConnectionState } ?: "none"

    fun close() {
        writers.forEach { it.stop() }
        val deadline = System.nanoTime() + CLOSE_TIMEOUT_MS * 1_000_000
        writers.forEach { runCatching { it.join(deadline) } }
        estimateListener = null // a write stuck past the deadline must not call into a dead session
        // A native close can wait on the lock held by a stuck write, so give each its own thread
        val closers = sinks.map { sink ->
            Thread({ runCatching { sink.close() } }, "SinkClose").apply { isDaemon = true; start() }
        }
        val closeDeadline = System.nanoTime() + CLOSE_TIMEOUT_MS * 1_000_000
        closers.forEach { it.join(maxOf(1, (closeDeadline - System.nanoTime()) / 1_000_000)) }
    }

    private companion object {
        /** About 3 seconds of 30 fps video plus audio. */
        const val QUEUE_CAPACITY = 256
        const val CLOSE_TIMEOUT_MS = 2000L
    }
}
