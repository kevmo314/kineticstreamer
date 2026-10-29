package com.kevmo314.kineticstreamer

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.ImageWriter
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Deterministic stand-ins for the camera and microphone. Both run on one clock (CLOCK_MONOTONIC,
 * the clock real capture timestamps use): every [FLASH_PERIOD_MS] the video frame is white for
 * one frame and the audio carries a [TONE_MS] 1 kHz burst starting at exactly the same instant;
 * otherwise the picture is black and the audio silent. The renderer, encoders, JNI and sinks
 * downstream are the real ones.
 */
class SyntheticSources(private val epochNanos: Long = System.nanoTime()) {
    companion object {
        const val FLASH_PERIOD_MS = 500
        const val TONE_MS = 60
        private const val TAG = "SyntheticSources"
        private const val FPS = 30
        private const val SAMPLE_RATE = 48000
        private const val CHUNK_MS = 10
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_MS / 1000
    }

    fun override() = StreamingService.CaptureOverride(
        video = { renderer -> video(renderer) },
        audio = { audio() },
    )

    private fun parkUntil(deadlineNanos: Long) {
        while (!Thread.currentThread().isInterrupted) {
            val left = deadlineNanos - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(left)
        }
    }

    /** Index of the first grid slot (spaced [periodNanos] from the epoch) at or after now. */
    private fun nextSlot(periodNanos: Long) = Math.floorDiv(System.nanoTime() - epochNanos, periodNanos) + 1

    /**
     * Posts black frames at 30 fps, white every [FLASH_PERIOD_MS]. Each buffer is stamped with
     * its slot time, so scheduling jitter on a loaded machine doesn't move the flash.
     */
    private fun video(renderer: SurfaceTextureRenderer): Flow<SurfaceTexture> = callbackFlow {
        val texture = renderer.createVideoSurfaceTexture(640, 360)
        val surface = Surface(texture)
        val writer = ImageWriter.newInstance(surface, 3, PixelFormat.RGBA_8888)
        texture.setOnFrameAvailableListener({ renderer.renderFrame(it) }, renderer.glHandler)
        trySend(texture)

        val framesPerFlash = FLASH_PERIOD_MS * FPS / 1000
        val periodNanos = 1_000_000_000L / FPS
        val producer = thread(name = "SyntheticVideo") {
            var i = nextSlot(periodNanos)
            var posted = 0
            var skipped = 0L
            var white: ByteArray? = null
            var black: ByteArray? = null
            while (!Thread.currentThread().isInterrupted) {
                val slotNanos = epochNanos + i * periodNanos
                parkUntil(slotNanos)
                if (Thread.currentThread().isInterrupted) break
                try {
                    val image = writer.dequeueInputImage()
                    val buffer = image.planes[0].buffer
                    if (white == null) {
                        white = ByteArray(buffer.remaining()) { 0xFF.toByte() }
                        black = ByteArray(buffer.remaining()) { if (it % 4 == 3) 0xFF.toByte() else 0 }
                    }
                    buffer.put(if (i % framesPerFlash == 0L) white else black)
                    image.timestamp = slotNanos
                    writer.queueInputImage(image)
                    posted++
                } catch (e: Exception) {
                    break // writer closed while stopping
                }
                // If we fell behind (dequeue blocked on a slow consumer), skip to the present.
                val next = maxOf(i + 1, Math.floorDiv(System.nanoTime() - epochNanos, periodNanos))
                skipped += next - i - 1
                i = next
                if (posted % 150 == 0) Log.i(TAG, "video: posted=$posted skipped=$skipped")
            }
            Log.i(TAG, "video: posted=$posted skipped=$skipped")
        }
        awaitClose {
            producer.interrupt()
            producer.join(2000)
            writer.close()
            texture.setOnFrameAvailableListener(null)
            // Release on the GL thread so it cannot race a frame being rendered
            val release = Runnable {
                surface.release()
                texture.release()
            }
            if (!renderer.glHandler.post(release)) release.run()
        }
    }

    /** Mono 48 kHz PCM in 10 ms chunks, stamped with the time of their first sample. */
    private fun audio(): Flow<TimestampedAudio> = callbackFlow {
        val chunkNanos = CHUNK_MS * 1_000_000L
        val toneSamples = TONE_MS * SAMPLE_RATE / 1000
        val flashSamples = FLASH_PERIOD_MS * SAMPLE_RATE / 1000
        val producer = thread(name = "SyntheticAudio") {
            var n = nextSlot(chunkNanos)
            while (!Thread.currentThread().isInterrupted) {
                // A real recorder hands over a chunk once it has finished being captured.
                parkUntil(epochNanos + (n + 1) * chunkNanos)
                val pcm = ByteArray(SAMPLES_PER_CHUNK * 2)
                for (s in 0 until SAMPLES_PER_CHUNK) {
                    val g = n * SAMPLES_PER_CHUNK + s // sample index since the epoch
                    val inTone = g % flashSamples < toneSamples
                    val v = if (inTone) (sin(2 * PI * 1000 * g / SAMPLE_RATE) * 0.5 * Short.MAX_VALUE).toInt() else 0
                    pcm[s * 2] = v.toByte()
                    pcm[s * 2 + 1] = (v shr 8).toByte()
                }
                trySend(TimestampedAudio(pcm, epochNanos + n * chunkNanos, SAMPLE_RATE, 1))
                n++
            }
        }
        awaitClose {
            producer.interrupt()
            producer.join(2000)
        }
    }
}
