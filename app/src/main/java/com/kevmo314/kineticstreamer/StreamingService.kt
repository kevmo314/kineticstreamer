package com.kevmo314.kineticstreamer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.util.Log
import android.view.Surface
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.kevmo314.kineticstreamer.kinetic.RTMPSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference

class StreamingService : Service() {
    companion object {
        const val ACTION_SET_USB_DEVICE = "com.kevmo314.kineticstreamer.action.SET_USB_DEVICE"
        const val ACTION_USB_DEVICE_CHANGED = "com.kevmo314.kineticstreamer.action.USB_DEVICE_CHANGED"
        const val EXTRA_USB_DEVICE = "com.kevmo314.kineticstreamer.extra.USB_DEVICE"

        private const val TAG = "StreamingService"
        private const val NOTIFICATION_ID = 68448
        private const val NOTIFICATION_CHANNEL = "KINETIC_STREAMER"

        // The Android software AAC encoder (FDK AAC-LC) delays its output by 2048 samples at 48 kHz.
        private const val AAC_ENCODER_DELAY_US = 2048 * 1_000_000L / 48000

        /**
         * Test hook: when set before the service is created, replaces camera and microphone
         * capture (only that step; renderer, encoders and sinks run for real).
         */
        @Volatile
        @androidx.annotation.VisibleForTesting
        var captureOverride: CaptureOverride? = null
    }

    class CaptureOverride(
        val video: (SurfaceTextureRenderer) -> Flow<SurfaceTexture>,
        val audio: () -> Flow<TimestampedAudio>,
    )

    class UsbReceiver: BroadcastReceiver() {
        companion object {
            const val ACTION_USB_PERMISSION = "com.kevmo314.kineticstreamer.action.USB_PERMISSION"
        }

        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i("UsbReceiver", "USB device attached: ${device?.productName}")

                    // Trigger device refresh to attempt reconnection
                    context.startService(Intent(context, StreamingService::class.java).apply {
                        action = ACTION_USB_DEVICE_CHANGED
                    })

                    // Check if it's a UVC camera and auto-open is enabled. DataStore is read off
                    // the main thread; goAsync keeps the receiver alive until it's done.
                    if (device != null && device.isUvc()) {
                        val pending = goAsync()
                        val appContext = context.applicationContext
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val settings = Settings(DataStoreProvider.getDataStore(appContext))
                                if (settings.autoOpenOnUsbCamera.first()) {
                                    Log.i("UsbReceiver", "Auto-opening app for UVC camera: ${device.productName}")
                                    appContext.startActivity(Intent(appContext, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                    })
                                }
                            } finally {
                                pending.finish()
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i("UsbReceiver", "USB device detached: ${device?.productName}")
                    // Trigger device refresh to handle disconnection
                    context.startService(Intent(context, StreamingService::class.java).apply {
                        action = ACTION_USB_DEVICE_CHANGED
                    })
                }
            }
        }
    }

    /** Not exported: only receives the PendingIntent MainActivity hands to UsbManager.requestPermission. */
    class UsbPermissionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbReceiver.ACTION_USB_PERMISSION) return
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                context.startService(Intent(context, StreamingService::class.java).apply {
                    action = ACTION_SET_USB_DEVICE
                    putExtra(EXTRA_USB_DEVICE, device)
                })
            }
        }
    }

    private var videoEncoderInputSurface: Surface? = null
    @Volatile private var videoEncoder: MediaCodec? = null
    @Volatile private var audioEncoder: MediaCodec? = null
    @Volatile private var sinks: OutputSinks? = null
    private var videoThread: HandlerThread? = null
    private var audioThread: HandlerThread? = null

    private var previewSurface: Surface? = null

    // Serialises startStreaming/stopStreaming/onDestroy, which arrive on different threads.
    private val lifecycleLock = Any()
    @Volatile private var streaming = false
    private val audioFeeder = AudioFeeder()
    // Codec input buffers waiting for PCM; fed as soon as audio arrives instead of polling.
    private val audioInputLock = Any()
    private val freeAudioInputs = ArrayDeque<Int>()
    private val settings by lazy { Settings(DataStoreProvider.getDataStore(this)) }
    private var renderer: SurfaceTextureRenderer? = null
    private var webViewOverlay: WebViewOverlay? = null
    @Volatile private var lastBitrate = 0 // encoder's current target, to avoid frequent updates
    private val fpsCounter = FpsCounter()
    private val audioLevelCallbacks = RemoteCallbackList<IAudioLevelCallback>()
    private var registeredLevelCallback: IAudioLevelCallback? = null
    private val audioLevels = FloatArray(12) // reused per chunk; only touched on the audio thread
    // Separate coroutine scopes for video and audio processing on IO dispatcher
    private val videoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Flow to trigger device reconnection (emits Unit when USB device changes)
    private val deviceRefreshTrigger = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    // Shared reference to RTMPSource for audio pipeline (when video source is RTMP)
    private val rtmpSourceRef = AtomicReference<RTMPSource?>(null)

    // Track current video device type for audio source selection
    private val currentVideoDevice = AtomicReference<VideoSourceDevice?>(null)

    // CLOCK_MONOTONIC at stream start. Video (via the renderer) and audio presentation times are
    // both on that clock; subtracting this puts both near zero for MPEG-TS compatibility.
    private var streamStartTimeNanos: Long = 0

    private val binder = object : IStreamingService.Stub() {
        override fun setPreviewSurface(surface: Surface?) {
            // Skip if surface hasn't changed
            if (surface == previewSurface) return

            // Remove old surface
            if (previewSurface != null) {
                renderer?.removeOutputSurface(previewSurface)
            }
            previewSurface = surface

            // Add new surface if valid
            if (surface != null && surface.isValid) {
                renderer?.addOutputSurface(surface)
            }
        }

        override fun startStreaming() {
            synchronized(lifecycleLock) {
                if (streaming) {
                    Log.w(TAG, "Already streaming")
                    return
                }

                streamStartTimeNanos = System.nanoTime()
                audioFeeder.reset()
                synchronized(audioInputLock) { freeAudioInputs.clear() }
                fpsCounter.reset()
                lastBitrate = 0
                Log.i(TAG, "Stream started at nanos: $streamStartTimeNanos")

                try {
                    startEncoders()
                    renderer?.addOutputSurface(videoEncoderInputSurface, encoder = true)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start streaming: ${e.message}", e)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@StreamingService, "Streaming failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    teardownStreaming()
                    return
                }
                streaming = true
                updateNotification("Streaming active")
                Log.i(TAG, "Encoders initialized and streaming active")
            }
        }

        override fun stopStreaming() {
            synchronized(lifecycleLock) {
                teardownStreaming()
                updateNotification("Ready to stream")
            }
        }

        override fun isStreaming(): Boolean = streaming

        override fun getCurrentBitrate(): Int = lastBitrate

        override fun getCurrentFps(): Float = fpsCounter.fps

        override fun setAudioLevelCallback(callback: IAudioLevelCallback?) {
            synchronized(audioLevelCallbacks) {
                registeredLevelCallback?.let { audioLevelCallbacks.unregister(it) }
                registeredLevelCallback = callback
                callback?.let { audioLevelCallbacks.register(it) }
            }
        }

        override fun setWebViewOverlay(url: String?, x: Int, y: Int, width: Int, height: Int) {
            if (url.isNullOrEmpty()) {
                removeWebViewOverlay()
                return
            }

            val overlay = webViewOverlay ?: WebViewOverlay(applicationContext).also {
                it.setRenderer(renderer)
                webViewOverlay = it
            }
            if (!overlay.initialize(url, x, y, width, height)) {
                Log.e(TAG, "Failed to initialize WebView overlay")
                webViewOverlay = null
                return
            }

            setRendererOverlay(overlay, x, y, width, height)
            Log.i(TAG, "WebView overlay set: $url at ($x,$y) ${width}x${height}")
        }

        override fun updateWebViewOverlay(url: String?, x: Int, y: Int, width: Int, height: Int) {
            val overlay = webViewOverlay ?: return
            overlay.update(url, x, y, width, height)
            setRendererOverlay(overlay, x, y, width, height)
            Log.i(TAG, "WebView overlay updated")
        }

        override fun removeWebViewOverlay() {
            webViewOverlay?.release()
            webViewOverlay = null

            Log.i(TAG, "WebView overlay removed")
        }

        override fun refreshWebViewOverlay() {
            webViewOverlay?.reload()
            Log.i(TAG, "WebView overlay refresh requested")
        }

        override fun getWhipIceConnectionState(): String = sinks?.iceConnectionState() ?: "none"

        override fun getWhipPeerConnectionState(): String = sinks?.peerConnectionState() ?: "none"
    }

    private fun setRendererOverlay(overlay: WebViewOverlay, x: Int, y: Int, width: Int, height: Int) {
        renderer?.setOverlay(
            overlay.getTextureId(), overlay.getSurfaceTexture(),
            x, y, width, height, VIDEO_WIDTH, VIDEO_HEIGHT
        )
    }

    /**
     * Releases everything startStreaming may have created. Each step is isolated so one failure
     * can't skip the rest; also serves as the rollback for a partial start. Order: encoders ->
     * drain the network executor and close sinks.
     */
    private fun teardownStreaming() {
        streaming = false
        streamStartTimeNanos = 0
        fpsCounter.reset()
        lastBitrate = 0
        rtmpSourceRef.set(null)

        // Stop the GL thread drawing into the encoder surface before it is released
        val inputSurface = videoEncoderInputSurface
        runCatching { renderer?.removeOutputSurface(inputSurface) }
        videoEncoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        videoEncoder = null
        runCatching { inputSurface?.release() }
        videoEncoderInputSurface = null

        audioEncoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        audioEncoder = null
        synchronized(audioInputLock) { freeAudioInputs.clear() }
        audioFeeder.reset()

        videoThread?.quitSafely()
        videoThread = null
        audioThread?.quitSafely()
        audioThread = null

        sinks?.close()
        sinks = null

        Log.i(TAG, "Streaming stopped and resources cleaned up")
    }

    private fun startEncoders() {
        val outputConfigs = runBlocking { settings.outputConfigurations.first() }
        val useOpus = usesOpusAudio(outputConfigs)
        val videoCodec = runBlocking { settings.codec.first() }
        Log.i(TAG, "Outputs: $outputConfigs, audio=${if (useOpus) "Opus" else "AAC"}, video=${videoCodec.mimeType}")

        val videoFormat = videoEncoderFormat(videoCodec.mimeType)
        val audioFormat = audioEncoderFormat(useOpus)
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val videoEncoderName = codecList.findEncoderForFormat(videoFormat)
            ?: codecList.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(videoCodec.mimeType, ignoreCase = true) }
            }?.name
            ?: throw IllegalStateException("No video encoder found for ${videoCodec.mimeType}")
        val audioEncoderName = codecList.findEncoderForFormat(audioFormat)
            ?: throw IllegalStateException("No audio encoder found for ${audioFormat.getString(MediaFormat.KEY_MIME)}")
        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }
        Log.i(TAG, "Using encoders: $videoEncoderName, $audioEncoderName")

        sinks = OutputSinks(
            outputConfigs,
            "${videoFormat.getString(MediaFormat.KEY_MIME)};${audioFormat.getString(MediaFormat.KEY_MIME)}",
        ) {
            runCatching { videoEncoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }

        // Codecs are assigned before configure so a failure here is cleaned up by teardownStreaming
        val videoHandlerThread = startHandlerThread("StreamingService-Video").also { videoThread = it }
        val video = MediaCodec.createByCodecName(videoEncoderName).also { videoEncoder = it }
        video.setCallback(videoCallback(if (useOpus) OPUS_BITRATE else AAC_BITRATE), Handler(videoHandlerThread.looper))
        video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoEncoderInputSurface = video.createInputSurface()

        // Only the software encoder's delay is known; vendor encoders are left uncompensated.
        val aacDelayKnown = !useOpus && (audioEncoderName.startsWith("c2.android.aac") || audioEncoderName.startsWith("OMX.google.aac"))
        val audioHandlerThread = startHandlerThread("StreamingService-Audio").also { audioThread = it }
        val audio = MediaCodec.createByCodecName(audioEncoderName).also { audioEncoder = it }
        audio.setCallback(audioCallback(if (aacDelayKnown) AAC_ENCODER_DELAY_US else 0L), Handler(audioHandlerThread.looper))
        audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        lastBitrate = INITIAL_VIDEO_BITRATE
        video.start()
        audio.start()
    }

    private fun startHandlerThread(name: String) = HandlerThread(name).apply {
        priority = Thread.MAX_PRIORITY
        start()
    }

    private fun videoCallback(audioBitrate: Int) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Input comes from the Surface
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val data = try {
                val buffer = codec.getOutputBuffer(index) ?: return
                ByteArray(info.size).also {
                    buffer.position(info.offset)
                    buffer.get(it)
                }
            } catch (e: IllegalStateException) {
                return // Codec was stopped while this callback was queued
            }

            // Same reference as audio, so they remain in sync while starting near zero
            val ptsUs = info.presentationTimeUs - streamStartTimeNanos / 1000
            sinks?.writeVideo(data, ptsUs, info.flags) { estimate ->
                // A write queued before teardown can land after the encoder is released
                val encoder = videoEncoder ?: return@writeVideo
                val bitrate = nextVideoBitrate(estimate, audioBitrate, lastBitrate) ?: return@writeVideo
                Log.i(TAG, "Bitrate: estimate=${estimate / 1000}Kbps - Audio=${audioBitrate / 1000}Kbps -> Video=${bitrate / 1000}Kbps")
                runCatching {
                    encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrate) })
                    lastBitrate = bitrate
                }
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) fpsCounter.tick(System.currentTimeMillis())

            try {
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Codec was stopped, ignore
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Video encoder error: ${e.message}, isRecoverable=${e.isRecoverable}, isTransient=${e.isTransient}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Video encoder output format changed: $format")
        }
    }

    /** [delayUs] is how late the encoder emits audio relative to the input it has consumed. */
    private fun audioCallback(delayUs: Long) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            pumpAudioInput(codec, index)
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index) ?: return
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)

                // Same reference as video for A/V sync. Shifting back by the encoder delay stops
                // sound trailing picture by ~43ms. This also drops the codec-config buffer
                // (timestamp 0) and the priming frames, which end before the stream starts.
                val ptsUs = info.presentationTimeUs - streamStartTimeNanos / 1000 - delayUs
                if (ptsUs >= 0) sinks?.writeAudio(data, ptsUs, info.flags)
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Encoder is stopping, ignore
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Audio encoder error: ${e.message}, isRecoverable=${e.isRecoverable}, isTransient=${e.isTransient}", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "Audio encoder output format changed: $format")
        }
    }

    /**
     * Hands PCM to the audio encoder. [newIndex] is an input buffer the codec just offered;
     * buffers with no PCM available are parked and filled when audio arrives, so the encoder
     * is never given empty buffers (which would make it spin).
     */
    private fun pumpAudioInput(codec: MediaCodec, newIndex: Int?) {
        synchronized(audioInputLock) {
            if (codec !== audioEncoder) return // torn down; index belongs to a dead codec
            if (newIndex != null) freeAudioInputs.addLast(newIndex)
            try {
                while (freeAudioInputs.isNotEmpty()) {
                    val index = freeAudioInputs.first()
                    val buffer = codec.getInputBuffer(index)
                    if (buffer == null) {
                        freeAudioInputs.removeFirst()
                        continue
                    }
                    buffer.clear()
                    val pts = audioFeeder.fill(buffer)
                    if (pts < 0) break
                    freeAudioInputs.removeFirst()
                    codec.queueInputBuffer(index, 0, buffer.position(), pts, 0)
                }
            } catch (e: IllegalStateException) {
                // Encoder is stopping, ignore
            }
        }
    }

    private fun handleAudio(audio: TimestampedAudio) {
        if (audio.encoding != AudioFormat.ENCODING_PCM_16BIT) {
            Log.w(TAG, "Dropping unsupported PCM encoding: ${audio.encoding}")
            return
        }
        if (audio.sampleRate != 48000) {
            Log.w(TAG, "Dropping unsupported PCM sample rate: ${audio.sampleRate}")
            return
        }
        val stereo = when (audio.channelCount) {
            1 -> monoToStereo(audio.data)
            2 -> audio.data
            else -> {
                Log.w(TAG, "Dropping unsupported PCM channel count: ${audio.channelCount}")
                return
            }
        }

        sendAudioLevels(stereo)

        if (!streaming) return
        if (!audioFeeder.offer(stereo, audio.captureTimeNanos)) {
            val dropped = audioFeeder.droppedChunks
            if (dropped % 100 == 1L) Log.w(TAG, "Audio queue full, dropped $dropped chunks")
        }
        audioEncoder?.let { pumpAudioInput(it, null) }
    }

    private fun sendAudioLevels(pcm: ByteArray) {
        val count = audioLevelCallbacks.beginBroadcast()
        try {
            if (count == 0) return
            computeAudioLevels(pcm, audioLevels)
            for (i in 0 until count) {
                try {
                    audioLevelCallbacks.getBroadcastItem(i).onAudioLevels(audioLevels)
                } catch (e: Exception) {
                    // Dead or misbehaving client; RemoteCallbackList drops it on death
                }
            }
        } finally {
            audioLevelCallbacks.finishBroadcast()
        }
    }

    /** The stored device if attached, else any UVC camera, else the first built-in camera. */
    private fun pickVideoDevice(identifier: String?): VideoSourceDevice? {
        val usbDevices = (getSystemService(USB_SERVICE) as UsbManager).deviceList.values.toList()
        Log.i(TAG, "USB devices available: ${usbDevices.map { "${it.productName} (id=${it.deviceId}, isUvc=${it.isUvc()})" }}")

        if (identifier != null) {
            VideoSourceDevice.fromIdentifier(identifier, usbDevices)?.let { return it }
        }
        usbDevices.firstOrNull { it.isUvc() }?.let { return VideoSourceDevice.UsbCamera(it) }

        Log.w(TAG, "No USB camera found (checked ${usbDevices.size} devices), falling back to internal camera")
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        return cameraManager.cameraIdList.firstOrNull()?.let { VideoSourceDevice.Camera(it) }
    }

    /** Opens [device] if permitted; an empty flow if its permission or the camera permission is missing. */
    private suspend fun openVideoSource(device: VideoSourceDevice?): Flow<SurfaceTexture> {
        if (device == null) return emptyFlow()
        val granted = when (device) {
            is VideoSourceDevice.UsbCamera -> device.requestPermission(this)
            is VideoSourceDevice.Camera -> device.requestPermission(this)
            else -> true
        }
        if (!granted || ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "No permission for video device $device")
            return emptyFlow()
        }

        // Track current video device for audio source selection
        currentVideoDevice.set(device)
        // Clear previous RTMP source when switching devices
        if (device !is VideoSourceDevice.RtmpServer) rtmpSourceRef.set(null)

        // The renderer draws frames itself on its GL thread (avoids frame duplication/dropping);
        // rtmpSourceRef lets an RTMP video source share itself with the audio pipeline.
        return VideoSource(this, device, renderer, rtmpSourceRef)
    }

    override fun onCreate() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "Kinetic Streamer Service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the streaming service running in the background"
                setShowBadge(false)
            }
        )
        startForeground(
            NOTIFICATION_ID, buildNotification("Ready to stream"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )

        // Keep the CPU and WiFi awake when the screen is off. FULL_LOW_LATENCY only holds while the
        // screen is on, so the (deprecated, but still honoured below API 34) HIGH_PERF mode stays.
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KineticStreamer::StreamingWakeLock").apply { acquire() }
        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KineticStreamer::WifiLock").apply { acquire() }

        // The renderer runs continuously for preview
        val renderer = SurfaceTextureRenderer().also { this.renderer = it }
        renderer.addOutputSurface(previewSurface)
        renderer.setRotate180(runBlocking { settings.rotateVideo180.first() })

        // Combined with deviceRefreshTrigger to allow USB reconnection
        combine(settings.selectedVideoDevice, deviceRefreshTrigger.onStart { emit(Unit) }) { identifier, _ -> identifier }
            .map { pickVideoDevice(it) }
            .flowOn(Dispatchers.IO) // blocking on frame reads
            .flatMapLatest { device ->
                Log.i(TAG, "Video device: $device")
                captureOverride?.video?.invoke(renderer) ?: openVideoSource(device)
            }
            .launchIn(videoScope)

        // Runs continuously for monitoring
        combine(settings.selectedAudioDevice, deviceRefreshTrigger.onStart { emit(Unit) }) { deviceId, _ -> deviceId }
            .flowOn(Dispatchers.IO)
            .flatMapLatest { deviceId ->
                captureOverride?.audio?.invoke() ?: if (currentVideoDevice.get() is VideoSourceDevice.RtmpServer) {
                    Log.i(TAG, "Using RTMP audio source (video is RTMP)")
                    RtmpAudioSource(rtmpSourceRef)
                } else if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "RECORD_AUDIO permission not granted")
                    emptyFlow()
                } else {
                    Log.i(TAG, "Using Android audio device ID: $deviceId")
                    AudioSource(this, deviceId)
                }
            }
            .buffer(10) // Small bounded buffer to avoid dropping audio samples
            .onEach { handleAudio(it) }
            .launchIn(audioScope)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setContentTitle("Kinetic Streamer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_USB_DEVICE) {
            val device = IntentCompat.getParcelableExtra(intent, EXTRA_USB_DEVICE, UsbDevice::class.java)
            if (device != null) {
                val usbDevice = VideoSourceDevice.UsbCamera(device)

                // Request permission and set device asynchronously
                videoScope.launch {
                    // Request permission if it's a UVC/UAC device
                    if (device.isUvc() || device.isUac()) {
                        val granted = usbDevice.requestPermission(this@StreamingService)
                        if (!granted) {
                            Log.w(TAG, "USB permission denied for ${device.productName}")
                            return@launch
                        }
                    }
                    settings.setSelectedVideoDevice(usbDevice)
                }
            }
        } else if (intent?.action == ACTION_USB_DEVICE_CHANGED) {
            Log.i(TAG, "USB device changed, triggering device refresh")
            deviceRefreshTrigger.tryEmit(Unit)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        rtmpSourceRef.set(null)
        currentVideoDevice.set(null)

        wakeLock?.release()
        wakeLock = null
        wifiLock?.release()
        wifiLock = null

        // Cancels the device and audio flows too
        videoScope.cancel()
        audioScope.cancel()

        synchronized(lifecycleLock) { teardownStreaming() }
        audioLevelCallbacks.kill()

        webViewOverlay?.release()
        webViewOverlay = null

        renderer?.release()
        renderer = null

        Log.i(TAG, "StreamingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
