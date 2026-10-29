package com.kevmo314.kineticstreamer

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import com.kevmo314.kineticstreamer.kinetic.RTMPServer
import com.kevmo314.kineticstreamer.kinetic.RTMPSource
import com.kevmo314.kineticstreamer.kinetic.UVCSource

data class CameraSessionResult(val device: CameraDevice?, val session: CameraCaptureSession?)

/**
 * Opens [cameraId] and starts a repeating request into [outputSurface]. Resumes exactly once;
 * the device is closed if the caller is cancelled or anything fails. Returns nulls on failure.
 */
@androidx.annotation.RequiresPermission(android.Manifest.permission.CAMERA)
suspend fun openCamera(cameraManager: CameraManager, outputSurface: Surface, handler: Handler, cameraId: String): CameraSessionResult = suspendCancellableCoroutine { continuation ->
    val resumed = AtomicBoolean(false)
    val device = AtomicReference<CameraDevice?>()

    fun finish(result: CameraSessionResult) {
        if (resumed.compareAndSet(false, true)) {
            if (result.device == null) device.get()?.close()
            continuation.resume(result)
        }
    }

    continuation.invokeOnCancellation { device.get()?.close() }

    try {
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device.set(camera)
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(outputSurface)
                    }.build()
                    camera.createCaptureSession(
                        SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(outputSurface)),
                            { handler.post(it) },
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.setRepeatingRequest(request, null, handler)
                                        finish(CameraSessionResult(camera, session))
                                    } catch (e: Exception) {
                                        Log.e("VideoSource", "Failed to start repeating request", e)
                                        finish(CameraSessionResult(null, null))
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    Log.e("VideoSource", "Failed to configure camera session")
                                    finish(CameraSessionResult(null, null))
                                }
                            }
                        )
                    )
                } catch (e: Exception) {
                    Log.e("VideoSource", "Failed to create camera session", e)
                    finish(CameraSessionResult(null, null))
                }
            }

            // After a successful open these just close the device; the flow's teardown closes the rest
            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                finish(CameraSessionResult(null, null))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("VideoSource", "Camera error: $error")
                camera.close()
                finish(CameraSessionResult(null, null))
            }
        }, handler)
    } catch (e: Exception) {
        Log.e("VideoSource", "Failed to open camera", e)
        finish(CameraSessionResult(null, null))
    }
}

/** An encoded frame and its presentation time in microseconds. */
class FrameWithPTS(val data: ByteArray, val pts: Long)

// Dummy SPS/PPS sent first for OA4 compatibility
private val OA4_SPS_PPS = (
    "00000001" + "67640034ac4d00f0044fcb35010101400000fa00003a9803c70ca8" + "00000001" + "68ee3cb0"
    ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/**
 * H.264 decoder rendering into a surface, fed by a thread pulling frames from [readFrame]
 * (which returns null at end of stream). [closeSource] must unblock [readFrame].
 */
class H264DecoderPipeline(
    tag: String,
    outputSurface: Surface,
    renderer: SurfaceTextureRenderer?,
    readFrame: () -> FrameWithPTS?,
    private val closeSource: () -> Unit,
) : Closeable {
    private val decoder: MediaCodec
    private val feeder: DecoderFeeder
    private val frameThread: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080)
            setInteger("vendor.qcom-ext-dec-input-buffer-count.value", 30) // Qualcomm specific
            setInteger("input-buffer-count", 30)
        }
        decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        feeder = DecoderFeeder { index, data, pts, flags ->
            val buffer = decoder.getInputBuffer(index)
            if (buffer == null || data.size > buffer.capacity()) {
                Log.e(tag, "Dropping ${data.size} byte frame for input buffer ${buffer?.capacity()}")
                decoder.queueInputBuffer(index, 0, 0, pts, flags)
            } else {
                buffer.clear()
                buffer.put(data)
                decoder.queueInputBuffer(index, 0, data.size, pts, flags)
            }
        }
        decoder.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) =
                feeder.onInputBufferAvailable(index)

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                codec.releaseOutputBuffer(index, true)
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e(tag, "Decoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i(tag, "Decoder output format: $format")
                // Crop info lets the renderer account for padding when rotating
                val height = format.getInteger(MediaFormat.KEY_HEIGHT)
                val cropTop = if (format.containsKey("crop-top")) format.getInteger("crop-top") else 0
                val cropBottom = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") else height - 1
                renderer?.setDecoderCrop(height, cropTop, cropBottom)
            }
        })
        decoder.configure(format, outputSurface, null, 0)
        decoder.start()

        frameThread = thread(name = "$tag-frames") {
            try {
                feeder.feed(OA4_SPS_PPS, System.nanoTime() / 1000)
                while (true) {
                    val frame = readFrame() ?: break
                    if (!feeder.feed(frame.data, frame.pts)) return@thread
                }
                Log.i(tag, "End of stream")
                feeder.feed(ByteArray(0), 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } catch (e: InterruptedException) {
                // closing
            } catch (e: IllegalStateException) {
                // decoder stopped while feeding
            }
        }
    }

    override fun close() {
        feeder.close()
        // Close the native source first: the reader may be blocked in JNI where interrupt does nothing
        closeSource()
        frameThread.join(2000)
        if (frameThread.isAlive) {
            Log.w("VideoSource", "Frame thread did not exit, interrupting")
            frameThread.interrupt()
        }
        try {
            decoder.stop()
        } catch (e: Exception) {
            Log.e("VideoSource", "Error stopping decoder: ${e.message}")
        }
        decoder.release()
    }
}

private fun openUsbCamera(context: Context, device: UsbDevice, outputSurface: Surface, renderer: SurfaceTextureRenderer?): Closeable? {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val conn = manager.openDevice(device)
    if (conn == null) {
        Log.e("VideoSource", "Failed to open USB device")
        return null
    }

    try {
        // Claim video interfaces with force=true to detach kernel uvcvideo driver
        // This leaves audio interfaces (2, 3, 4) for snd-usb-audio
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            // Only claim UVC video interfaces (class 14 = Video)
            if (iface.interfaceClass == 14) {
                val claimed = conn.claimInterface(iface, true)
                Log.i("VideoSource", "Claimed interface ${iface.id} (${iface.name}): $claimed")
            }
        }

        val stream = UVCSource(conn.fileDescriptor).startStreaming(8, 1920, 1080, 30)
        val pipeline = H264DecoderPipeline(
            "UvcDecoder", outputSurface, renderer,
            // Arrival time is CLOCK_MONOTONIC, the clock AudioRecord timestamps use
            { stream.readFrame()?.let { FrameWithPTS(it, stream.getArrivalTimeNs() / 1000) } },
            { stream.close() },
        )
        return Closeable {
            pipeline.close()
            conn.close()
        }
    } catch (e: Throwable) {
        conn.close()
        throw e
    }
}

/**
 * RTMP Audio Source - reads AAC frames from RTMPSource, decodes to PCM.
 * Returns a Flow<ByteArray> of PCM audio data (48kHz, mono, 16-bit).
 */
fun RtmpAudioSource(rtmpSourceRef: AtomicReference<RTMPSource?>): Flow<TimestampedAudio> = callbackFlow {
    Log.i("RtmpAudioSource", "Starting RTMP audio source...")

    // Wait for RTMPSource to become available
    var source: RTMPSource? = null
    var waitCount = 0
    while (source == null && waitCount < 120) { // Wait up to 60 seconds
        source = rtmpSourceRef.get()
        if (source == null) {
            Thread.sleep(500)
            waitCount++
        }
    }

    if (source == null) {
        Log.e("RtmpAudioSource", "RTMPSource not available after waiting")
        close()
        return@callbackFlow
    }

    Log.i("RtmpAudioSource", "RTMPSource available, setting up AAC decoder")

    // Create AAC decoder
    val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 1).apply {
        // AAC-LC profile
        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        setInteger(MediaFormat.KEY_IS_ADTS, 0) // Raw AAC, not ADTS
    }

    // Queue for decoded PCM output
    val pcmQueue = LinkedBlockingQueue<ByteArray>(30)
    var decoderRunning = true

    val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
        setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                if (!decoderRunning) return
                try {
                    val inputBuffer = codec.getInputBuffer(index) ?: return
                    inputBuffer.clear()

                    // Read AAC frame from RTMP source (this may block briefly)
                    val aacFrame = source.readAudioFrame()
                    if (aacFrame == null) {
                        // Source closed, signal EOS
                        codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        return
                    }

                    inputBuffer.put(aacFrame)
                    val pts = source.getAudioPTS()
                    codec.queueInputBuffer(index, 0, aacFrame.size, pts, 0)
                } catch (e: IllegalStateException) {
                    // Decoder stopped
                }
            }

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (!decoderRunning) return
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        codec.releaseOutputBuffer(index, false)
                        return
                    }

                    val outputBuffer = codec.getOutputBuffer(index) ?: return
                    val pcmData = ByteArray(info.size)
                    outputBuffer.position(info.offset)
                    outputBuffer.get(pcmData, 0, info.size)

                    // Offer PCM to queue
                    pcmQueue.offer(pcmData)

                    codec.releaseOutputBuffer(index, false)
                } catch (e: IllegalStateException) {
                    // Decoder stopped
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("RtmpAudioSource", "AAC decoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                Log.i("RtmpAudioSource", "AAC decoder output format: $format")
            }
        })
        configure(aacFormat, null, null, 0)
        start()
    }

    // Thread to emit PCM data from queue to flow
    val emitThread = thread {
        while (decoderRunning && !source.isClosed()) {
            val pcm = pcmQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (pcm != null) {
                trySend(TimestampedAudio(pcm, System.nanoTime()))
            }
        }
        Log.i("RtmpAudioSource", "PCM emit thread exiting")
    }

    awaitClose {
        Log.i("RtmpAudioSource", "Closing RTMP audio source")
        decoderRunning = false
        emitThread.interrupt()
        emitThread.join()
        try {
            decoder.stop()
        } catch (e: Exception) {
            Log.e("RtmpAudioSource", "Error stopping decoder: ${e.message}")
        }
        decoder.release()
    }
}

/**
 * VideoSource represents a persistent video source that manages the
 * camera device (or USB camera) and its associated resources.
 *
 * Frames are rendered directly on the renderer's GL thread for perfect frame
 * synchronization (no duplication/dropping), so a renderer is required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.RequiresPermission(
    android.Manifest.permission.CAMERA
)
fun VideoSource(context: Context,
                device: VideoSourceDevice,
                renderer: SurfaceTextureRenderer? = null,
                rtmpSourceRef: AtomicReference<RTMPSource?>? = null): Flow<SurfaceTexture> = callbackFlow {
    checkNotNull(renderer) { "VideoSource requires a renderer" }

    // Closed in reverse order of registration. Resources registered after teardown
    // (e.g. by the RTMP waiter thread) are closed immediately.
    val resources = ArrayDeque<Closeable>()
    var closed = false
    fun register(resource: Closeable) {
        val closeNow = synchronized(resources) {
            if (!closed) resources.addFirst(resource)
            closed
        }
        if (closeNow) resource.close()
    }
    fun closeAll() {
        val toClose = synchronized(resources) {
            closed = true
            resources.toList().also { resources.clear() }
        }
        toClose.forEach {
            try {
                it.close()
            } catch (e: Exception) {
                Log.e("VideoSource", "Error closing resource: ${e.message}")
            }
        }
    }

    // Buffer size 1088 (macroblock-aligned) matches the H.264 decoder output. This avoids scaling
    // in the transform matrix - the 1080p encoder surface will naturally crop the bottom 8 padding
    // pixels during rendering.
    val outputSurfaceTexture = renderer.createVideoSurfaceTexture(1920, 1088)
    val outputSurface = Surface(outputSurfaceTexture)
    register {
        outputSurfaceTexture.setOnFrameAvailableListener(null)
        // Release on the GL thread so it cannot race a frame being rendered
        val release = Runnable {
            outputSurface.release()
            outputSurfaceTexture.release()
        }
        if (!renderer.glHandler.post(release)) release.run()
    }

    try {
        renderer.timestampOffsetNanos = 0
        when (device) {
            is VideoSourceDevice.UsbCamera -> {
                Log.i("VideoSource", "Opening USB camera: ${device.usbDevice.productName}")
                openUsbCamera(context, device.usbDevice, outputSurface, renderer)?.let { register(it) }
            }
            is VideoSourceDevice.Camera -> {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val characteristics = cameraManager.getCameraCharacteristics(device.cameraId)
                Log.i("VideoSource", "Opening camera ${device.cameraId}, sensor orientation: ${characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
                renderer.timestampOffsetNanos = cameraTimestampOffsetNs(
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE),
                    System.nanoTime(),
                    SystemClock.elapsedRealtimeNanos(),
                )
                val handlerThread = HandlerThread("CameraSource").apply { start() }
                register { handlerThread.quitSafely() }
                val result = openCamera(cameraManager, outputSurface, Handler(handlerThread.looper), device.cameraId)
                if (result.device == null) {
                    Log.e("VideoSource", "Camera ${device.cameraId} failed to open")
                } else {
                    register {
                        result.session?.close()
                        result.device.close()
                    }
                }
            }
            is VideoSourceDevice.RtmpServer -> {
                Log.i("VideoSource", "Starting RTMP server on port ${device.port}")
                val server = RTMPServer(device.port)
                if (!server.start()) {
                    Log.e("VideoSource", "Failed to start RTMP server")
                    close()
                    return@callbackFlow
                }
                register(server)
                Log.i("VideoSource", "RTMP server started, waiting for publisher...")

                // Wait for a publisher to connect (blocking in background thread)
                thread(name = "RtmpWaiter") {
                    try {
                        val source = server.waitForSource(60000) // Wait up to 60 seconds
                        if (source == null) {
                            Log.w("VideoSource", "No RTMP publisher connected within timeout")
                            return@thread
                        }
                        Log.i("VideoSource", "RTMP publisher connected!")
                        // Share the source with audio pipeline
                        rtmpSourceRef?.set(source)
                        register(
                            H264DecoderPipeline(
                                "RtmpDecoder", outputSurface, renderer,
                                { if (source.isClosed()) null else source.readVideoFrame()?.let { FrameWithPTS(it, source.getVideoPTS()) } },
                                { source.close() },
                            )
                        )
                    } catch (e: Exception) {
                        // e.g. the surface was released because the flow closed meanwhile
                        Log.e("VideoSource", "Failed to start RTMP decoder: ${e.message}")
                    }
                }
            }
        }
    } catch (t: Throwable) {
        closeAll()
        throw t
    }

    // Render immediately on the GL thread: 1:1 frame correspondence (no duplication/dropping)
    outputSurfaceTexture.setOnFrameAvailableListener({ st ->
        renderer.renderFrame(st)
    }, renderer.glHandler)
    // Send once to signal the flow is active (for lifecycle management)
    trySend(outputSurfaceTexture)

    awaitClose { closeAll() }
}
