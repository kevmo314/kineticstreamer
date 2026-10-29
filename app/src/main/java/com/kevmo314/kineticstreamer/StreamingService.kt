package com.kevmo314.kineticstreamer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.Looper
import android.widget.Toast
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.kevmo314.kineticstreamer.kinetic.WHIPSink
import com.kevmo314.kineticstreamer.kinetic.RISTSink
import com.kevmo314.kineticstreamer.kinetic.SRTSink
import com.kevmo314.kineticstreamer.kinetic.PLICallback
import com.kevmo314.kineticstreamer.kinetic.RTMPSource
import java.util.concurrent.atomic.AtomicReference
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.math.abs


class StreamingService : Service() {
    companion object {
        const val ACTION_SET_USB_DEVICE = "com.kevmo314.kineticstreamer.action.SET_USB_DEVICE"
        const val ACTION_USB_DEVICE_CHANGED = "com.kevmo314.kineticstreamer.action.USB_DEVICE_CHANGED"
        const val EXTRA_USB_DEVICE = "com.kevmo314.kineticstreamer.extra.USB_DEVICE"
    }

    class UsbReceiver: BroadcastReceiver() {
        companion object {
            const val ACTION_USB_PERMISSION = "com.kevmo314.kineticstreamer.action.USB_PERMISSION"
        }

        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
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
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
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
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (device != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                context.startService(Intent(context, StreamingService::class.java).apply {
                    action = ACTION_SET_USB_DEVICE
                    putExtra(EXTRA_USB_DEVICE, device)
                })
            }
        }
    }

    private var videoEncoderInputSurface: Surface? = null
    private var videoEncoder: MediaCodec? = null

    private var previewSurface: Surface? = null

    private var audioEncoder: MediaCodec? = null
    private var videoThread: HandlerThread? = null
    private var audioThread: HandlerThread? = null
    // Serialises startStreaming/stopStreaming/onDestroy, which arrive on different threads.
    private val lifecycleLock = Any()
    @Volatile private var streaming = false
    private val audioFeeder = AudioFeeder()
    // Codec input buffers waiting for PCM; fed as soon as audio arrives instead of polling.
    private val audioInputLock = Any()
    private val freeAudioInputs = ArrayDeque<Int>()
    private var settings: Settings? = null
    private var deviceFlowJob: Job? = null
    private var renderer: SurfaceTextureRenderer? = null
    private var webViewOverlay: WebViewOverlay? = null
    private var whipSink: WHIPSink? = null
    private var srtSink: SRTSink? = null
    private var ristSink: RISTSink? = null
    private var networkExecutor: ExecutorService? = null  // Dedicated thread for network writes
    @Volatile private var lastBitrate: Int = 0 // Track last bitrate to avoid frequent updates
    private var videoFrameCount: Long = 0 // Counter for debug logging
    private var useOpusAudio: Boolean = true // Track audio codec: true=Opus (WHIP), false=AAC (SRT)
    private var audioFlowJob: Job? = null
    private val audioLevelCallbacks = RemoteCallbackList<IAudioLevelCallback>()
    private var registeredLevelCallback: IAudioLevelCallback? = null
    private val audioLevels = FloatArray(12) // reused per chunk; only touched on the audio thread
    
    // FPS tracking - circular buffer for last 3 seconds of frame timestamps
    private val frameTimestamps = mutableListOf<Long>()
    private var latestFps: Float = 0f
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
    
    // Timestamp synchronization
    private var streamStartTimeNanos: Long = 0
    private var lastVideoTimestampNanos: Long = 0

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

        override fun startStreaming(config: StreamingConfiguration) {
            synchronized(lifecycleLock) {
                if (streaming) {
                    Log.w("StreamingService", "Already streaming")
                    return
                }

                // Initialize timestamp synchronization FIRST
                streamStartTimeNanos = System.nanoTime()
                audioFeeder.reset()
                synchronized(audioInputLock) { freeAudioInputs.clear() }
                lastVideoTimestampNanos = 0
                videoFrameCount = 0
                lastBitrate = 0
                Log.i("StreamingService", "Stream started at nanos: $streamStartTimeNanos")

                try {
                    setupEncoders()
                    // Pass stream start time to renderer for wall-clock timestamp sync
                    renderer?.streamStartTimeNanos = streamStartTimeNanos
                    renderer?.addOutputSurface(videoEncoderInputSurface)
                } catch (e: Exception) {
                    Log.e("StreamingService", "Failed to start streaming: ${e.message}", e)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@StreamingService, "Streaming failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    teardownStreaming()
                    return
                }
                streaming = true
                updateNotification("Streaming active", true)
                Log.i("StreamingService", "Encoders initialized and streaming active")
            }
        }

        override fun stopStreaming() {
            synchronized(lifecycleLock) {
                teardownStreaming()
                updateNotification("Ready to stream", false)
            }
        }

        override fun isStreaming(): Boolean {
            return streaming
        }
        
        override fun getCurrentBitrate(): Int {
            return lastBitrate
        }
        
        override fun getCurrentFps(): Float {
            return latestFps
        }
        
        override fun setAudioLevelCallback(callback: IAudioLevelCallback?) {
            synchronized(audioLevelCallbacks) {
                registeredLevelCallback?.let { audioLevelCallbacks.unregister(it) }
                registeredLevelCallback = callback
                callback?.let { audioLevelCallbacks.register(it) }
            }
        }
        
        override fun setWebViewOverlay(url: String?, x: Int, y: Int, width: Int, height: Int) {
            if (url == null || url.isEmpty()) {
                removeWebViewOverlay()
                return
            }

            if (webViewOverlay == null) {
                webViewOverlay = WebViewOverlay(applicationContext).also { it.setRenderer(renderer) }
            }
            
            val success = webViewOverlay?.initialize(url, x, y, width, height) ?: false
            if (!success) {
                Log.e("StreamingService", "Failed to initialize WebView overlay")
                webViewOverlay = null
                return
            }
            
            // Set overlay in renderer with screen dimensions (1920x1080 for now)
            renderer?.setOverlay(
                webViewOverlay?.getTextureId() ?: 0,
                webViewOverlay?.getSurfaceTexture(),
                x, y, width, height,
                1920, 1080 // TODO: Get actual video dimensions
            )
            
            Log.i("StreamingService", "WebView overlay set: $url at ($x,$y) ${width}x${height}")
        }
        
        override fun updateWebViewOverlay(url: String?, x: Int, y: Int, width: Int, height: Int) {
            webViewOverlay?.update(url, x, y, width, height)
            
            // Update overlay in renderer
            renderer?.setOverlay(
                webViewOverlay?.getTextureId() ?: 0,
                webViewOverlay?.getSurfaceTexture(),
                x, y, width, height,
                1920, 1080 // TODO: Get actual video dimensions
            )
            
            Log.i("StreamingService", "WebView overlay updated")
        }
        
        override fun removeWebViewOverlay() {
            webViewOverlay?.release()
            webViewOverlay = null
            renderer?.removeOverlay()

            Log.i("StreamingService", "WebView overlay removed")
        }

        override fun refreshWebViewOverlay() {
            webViewOverlay?.reload()
            Log.i("StreamingService", "WebView overlay refresh requested")
        }

        override fun getWhipIceConnectionState(): String {
            return whipSink?.getICEConnectionState() ?: "none"
        }

        override fun getWhipPeerConnectionState(): String {
            return whipSink?.getPeerConnectionState() ?: "none"
        }
    }

    /**
     * Releases everything startStreaming may have created. Each step is isolated so one failure
     * can't skip the rest; also serves as the rollback for a partial start. Order: encoders ->
     * drain the network executor -> close sinks.
     */
    private fun teardownStreaming() {
        streaming = false
        streamStartTimeNanos = 0
        renderer?.streamStartTimeNanos = 0
        lastVideoTimestampNanos = 0
        synchronized(frameTimestamps) { frameTimestamps.clear() }
        latestFps = 0f
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

        // Let queued writes finish before the sinks go away
        networkExecutor?.let { executor ->
            runCatching {
                executor.shutdown()
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow()
            }
        }
        networkExecutor = null

        runCatching { whipSink?.close() }
        whipSink = null
        runCatching { srtSink?.close() }
        srtSink = null
        runCatching { ristSink?.close() }
        ristSink = null

        Log.i("StreamingService", "Streaming stopped and resources cleaned up")
    }

    private fun writeAsync(block: () -> Unit) {
        try {
            networkExecutor?.execute(block)
        } catch (e: RejectedExecutionException) {
            // Stopping
        }
    }

    private fun setupEncoders() {
        // Read output configurations FIRST to determine audio codec
        val outputConfigs = runBlocking { settings?.outputConfigurations?.first() } ?: emptyList()
        Log.i("StreamingService", "Output configurations: $outputConfigs")

        // Determine audio codec based on enabled sinks
        // WHIP (WebRTC) requires Opus, SRT prefers AAC for MPEG-TS compatibility
        val whipEnabled = outputConfigs.any { it.enabled && (it.url.startsWith("whip://") || it.url.startsWith("https://") || it.url.startsWith("http://")) }
        val srtEnabled = outputConfigs.any { it.enabled && it.url.startsWith("srt://") }
        val ristEnabled = outputConfigs.any { it.enabled && it.url.startsWith("rist://") }
        // Opus if WHIP enabled or no MPEG-TS sinks configured. SRT and RIST both
        // ship MPEG-TS and prefer AAC.
        useOpusAudio = whipEnabled || !(srtEnabled || ristEnabled)
        Log.i("StreamingService", "Audio codec: ${if (useOpusAudio) "Opus" else "AAC"} (WHIP=$whipEnabled, SRT=$srtEnabled, RIST=$ristEnabled)")

        // Read video codec setting
        val videoCodec = runBlocking { settings?.codec?.first() } ?: SupportedVideoCodec.H264
        Log.i("StreamingService", "Video codec: $videoCodec (${videoCodec.mimeType})")

        val videoMediaFormat = MediaFormat.createVideoFormat(videoCodec.mimeType, 1920, 1080).apply {
            val initialBitrate = 2000000  // 2 Mbps initial, adjusted dynamically by GCC
            setInteger(MediaFormat.KEY_BIT_RATE, initialBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            lastBitrate = initialBitrate

            // Ultra-low latency settings
            setInteger(MediaFormat.KEY_CAPTURE_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)

            // CBR with frame drop for strict constant bitrate
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR_FD)

            // Allow GCC to scale bitrate up to 7.5 Mbps
            setInteger("max-bitrate", 7_500_000)

            // Disable B-frames for consistent frame rate during high motion
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

            // Real-time priority with low latency to prevent encoder backup during high motion
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority (0 = real-time, 1 = non-real-time)
            setInteger(MediaFormat.KEY_LOW_LATENCY, 1) // Prioritize throughput over quality
            setInteger(MediaFormat.KEY_LATENCY, 3) // Small lookahead buffer to prevent blocking
            setInteger(MediaFormat.KEY_OPERATING_RATE, 60) // Higher than frame rate for headroom

        }

        // Create audio format based on selected codec
        val audioMediaFormat = if (useOpusAudio) {
            // Opus for WHIP/WebRTC - stereo, 48kHz (mono input duplicated to stereo)
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128000) // Double bitrate for stereo

                // Low-latency Opus settings
                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
                    setInteger(MediaFormat.KEY_LATENCY, 0) // Ultra-low latency mode
                    setInteger("opus-use-inband-fec", 0) // Disable FEC for lower latency
                    setInteger("opus-packet-loss-perc", 0) // Assume no packet loss for lower latency
                    setString("opus-application", "voip") // VoIP mode for lowest latency
                } catch (e: Exception) {
                    Log.w("StreamingService", "Opus latency optimizations not supported: ${e.message}")
                }

                Log.i("StreamingService", "Opus encoder configured: stereo 128kbps, ultra-low latency, VoIP mode")
            }
        } else {
            // AAC for SRT/MPEG-TS - stereo, 48kHz, AAC-LC profile (mono input duplicated to stereo)
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority
                } catch (e: Exception) {
                    Log.w("StreamingService", "AAC priority setting not supported: ${e.message}")
                }

                Log.i("StreamingService", "AAC encoder configured: 192kbps, AAC-LC, stereo")
            }
        }

        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val videoEncoderName = codecList.findEncoderForFormat(videoMediaFormat)
            ?: codecList.codecInfos.firstOrNull { codecInfo ->
                codecInfo.isEncoder && codecInfo.supportedTypes.any { it.equals(videoCodec.mimeType, ignoreCase = true) }
            }?.name

        // Create sinks based on configured outputs
        val mimeTypes = listOf(
            videoMediaFormat.getString(MediaFormat.KEY_MIME),
            audioMediaFormat.getString(MediaFormat.KEY_MIME)
        ).joinToString(";")

        for (config in outputConfigs) {
            if (!config.enabled) continue

            when {
                config.url.startsWith("srt://") -> {
                    Log.i("StreamingService", "Creating SRT sink for ${config.url}")
                    try {
                        srtSink = SRTSink(config.url, mimeTypes)

                        // Set up PLI callback to request keyframes on packet loss
                        srtSink?.setPLICallback(object : PLICallback {
                            override fun onPLI() {
                                Log.d("StreamingService", "SRT PLI received, requesting keyframe")
                                videoEncoder?.let { encoder ->
                                    val params = Bundle()
                                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    encoder.setParameters(params)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        throw IllegalStateException("SRT connection failed: ${e.message}", e)
                    }
                }
                config.url.startsWith("rist://") -> {
                    Log.i("StreamingService", "Creating RIST sink for ${config.url}")
                    try {
                        ristSink = RISTSink(config.url, mimeTypes)
                    } catch (e: Exception) {
                        throw IllegalStateException("RIST connection failed: ${e.message}", e)
                    }
                }
                config.url.startsWith("whip://") || config.url.startsWith("https://") || config.url.startsWith("http://") -> {
                    // WHIP sink - strip whip:// prefix and extract token from query params
                    val rawUrl = if (config.url.startsWith("whip://")) config.url.removePrefix("whip://") else config.url
                    val uri = android.net.Uri.parse(rawUrl)
                    val token = uri.getQueryParameter("token") ?: ""
                    // Remove token from URL to get clean endpoint
                    val cleanUrl = uri.buildUpon().clearQuery().apply {
                        uri.queryParameterNames.filter { it != "token" }.forEach {
                            appendQueryParameter(it, uri.getQueryParameter(it))
                        }
                    }.build().toString()
                    Log.i("StreamingService", "Creating WHIP sink for $cleanUrl with token=${token.take(4)}...")
                    try {
                        whipSink = WHIPSink(cleanUrl, token, mimeTypes)

                        // Set up PLI callback to request keyframes
                        whipSink?.setPLICallback(object : PLICallback {
                            override fun onPLI() {
                                Log.d("StreamingService", "PLI received, requesting keyframe")
                                videoEncoder?.let { encoder ->
                                    val params = Bundle()
                                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                    encoder.setParameters(params)
                                }
                            }
                        })
                    } catch (e: Exception) {
                        throw IllegalStateException("WHIP connection failed: ${e.message}", e)
                    }
                }
                else -> {
                    Log.w("StreamingService", "Unknown output protocol: ${config.url}")
                }
            }
        }

        networkExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "NetworkWriter").apply { priority = Thread.MAX_PRIORITY }
        }

        var initialTimestamp: Long = 0
        var frameCount = 0
        var lastFrameTime: Long = 0

        if (videoEncoderName == null) {
            throw IllegalStateException("No video encoder found for ${videoMediaFormat.getString(MediaFormat.KEY_MIME)}")
        }
        Log.i("StreamingService", "Using video encoder: $videoEncoderName")
        val videoHandlerThread = HandlerThread("StreamingService-Video").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        videoThread = videoHandlerThread
        // Assigned before configure so a failure here is cleaned up by teardownStreaming
        videoEncoder = MediaCodec.createByCodecName(videoEncoderName)
        videoEncoder?.apply {
            // Callback must be set before configure for async mode
            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Input buffers handled by Surface input
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val array = try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        ByteArray(info.size).also {
                            buffer.position(info.offset)
                            buffer.get(it)
                        }
                    } catch (e: IllegalStateException) {
                        return // Codec was stopped while this callback was queued
                    }

                    // Normalize to stream start time - both audio and video use the same reference
                    // so they remain in sync while starting near zero for MPEG-TS compatibility
                    val ts = info.presentationTimeUs - (streamStartTimeNanos / 1000)

                    // Queue network writes on dedicated thread to avoid blocking encoder
                    val flags = info.flags
                    writeAsync {
                        try {
                            // Write to WHIP sink if configured
                            val gccBitrate = whipSink?.writeH264(array, ts) ?: 0

                            // Write to SRT sink if configured
                            srtSink?.writeSample(0, array, ts, flags)

                            // Write to RIST sink if configured
                            ristSink?.writeSample(0, array, ts, flags)

                            // Get SRT bandwidth estimate (in bps)
                            val srtBandwidth = srtSink?.getEstimatedBandwidth() ?: 0L

                            // Update encoder bitrate based on bandwidth estimation
                            val targetBitrate = when {
                                gccBitrate > 0 -> gccBitrate
                                srtBandwidth > 0 -> srtBandwidth.toInt()
                                else -> 0
                            }

                            if (targetBitrate > 0) {
                                // Subtract audio bitrate from total estimate (Opus stereo=128Kbps, AAC stereo=192Kbps)
                                val audioBitrate = if (useOpusAudio) 128000 else 192000
                                val encoderBitrate = maxOf(targetBitrate - audioBitrate, 100000)
                                val diff = kotlin.math.abs(encoderBitrate - lastBitrate)
                                if (diff > lastBitrate * 0.05) {
                                    videoEncoder?.let { encoder ->
                                        val params = Bundle()
                                        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, encoderBitrate)
                                        encoder.setParameters(params)
                                        Log.i("StreamingService", "Bitrate: target=${targetBitrate/1000}Kbps - Audio=${audioBitrate/1000}Kbps -> Video=${encoderBitrate/1000}Kbps")
                                        lastBitrate = encoderBitrate
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("StreamingService", "Error writing H264 data: ${e.message}")
                        }
                    }

                    // Update stats on encoder thread (non-blocking)
                    frameCount++
                    val now = System.nanoTime()
                    val interFrameMs = if (lastFrameTime > 0) (now - lastFrameTime) / 1_000_000 else 0
                    lastFrameTime = now

                    // Log timing every 90 frames (~3 sec at 30fps)
                    if (frameCount % 90 == 0) {
                        val effectiveFps = if (interFrameMs > 0) 1000.0 / interFrameMs else 0.0
                        Log.i("StreamingService", "Encoder: frame=$frameCount, ${String.format("%.1f", effectiveFps)}fps, ${array.size/1024}KB, bitrate=${lastBitrate/1000}Kbps")
                    }

                    // Debug: log every 100 frames
                    videoFrameCount++

                    // Track frame for FPS calculation
                    updateFpsTracking()

                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        // Codec was stopped, ignore
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("StreamingService", "Video encoder error: ${e.message}, isRecoverable=${e.isRecoverable}, isTransient=${e.isTransient}", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i("StreamingService", "Encoder output format changed: $format")
                }
            }, Handler(videoHandlerThread.looper))
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        videoEncoderInputSurface = videoEncoder?.createInputSurface()

        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw IllegalStateException("RECORD_AUDIO permission not granted")
        }
        // Set audio format parameters directly
        // Input is mono from AudioSource, converted to stereo before encoding
        audioMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 2) // Stereo (after conversion)
        audioMediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000) // 48kHz
        audioMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_OUT_STEREO)
        audioMediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        val audioEncoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(audioMediaFormat)
        if (audioEncoderName == null) {
            throw IllegalStateException("No audio encoder found for ${audioMediaFormat.getString(MediaFormat.KEY_MIME)}")
        }
        Log.i("StreamingService", "Using audio encoder: $audioEncoderName")
        val audioHandlerThread = HandlerThread("StreamingService-Audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        audioThread = audioHandlerThread
        audioEncoder = MediaCodec.createByCodecName(audioEncoderName)
        audioEncoder?.apply {
            setCallback(object : MediaCodec.Callback() {
                var audioInputCount = 0L
                var audioOutputCount = 0L

                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    pumpAudioInput(codec, index)
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        val array = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(array)

                        // Debug: log output AAC size periodically
                        audioOutputCount++
                        if (audioOutputCount % 100 == 0L) {
                            Log.i("StreamingService", "Audio OUTPUT: frame=$audioOutputCount, aacSize=${array.size}, pts=${info.presentationTimeUs}")
                        }

                        // Normalize to stream start time - same reference as video for A/V sync
                        val pts = info.presentationTimeUs - (streamStartTimeNanos / 1000)
                        val flags = info.flags
                        writeAsync {
                            try {
                                if (useOpusAudio) {
                                    // Opus mode - write to WHIP (WebRTC) and SRT/RIST (if configured)
                                    whipSink?.writeOpus(array, pts)
                                    srtSink?.writeSample(1, array, pts, flags)
                                    ristSink?.writeSample(1, array, pts, flags)
                                } else {
                                    // AAC mode - SRT/RIST both carry AAC in MPEG-TS
                                    srtSink?.writeSample(1, array, pts, flags)
                                    ristSink?.writeSample(1, array, pts, flags)
                                }
                            } catch (e: Exception) {
                                Log.e("StreamingService", "Error writing audio data: ${e.message}", e)
                            }
                        }

                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        // Encoder is stopping, ignore
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("StreamingService", "Audio encoder error: ${e.message}, isRecoverable=${e.isRecoverable}, isTransient=${e.isTransient}", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i("StreamingService", "Encoder output format changed: $format")
                }
            }, Handler(audioHandlerThread.looper))
            configure(audioMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        videoEncoder?.start()
        audioEncoder?.start()

        lastBitrate = 2000000 // Start at 2 Mbps
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
            Log.w("StreamingService", "Dropping unsupported PCM encoding: ${audio.encoding}")
            return
        }
        if (audio.sampleRate != 48000) {
            Log.w("StreamingService", "Dropping unsupported PCM sample rate: ${audio.sampleRate}")
            return
        }
        val stereo = when (audio.channelCount) {
            1 -> monoToStereo(audio.data)
            2 -> audio.data
            else -> {
                Log.w("StreamingService", "Dropping unsupported PCM channel count: ${audio.channelCount}")
                return
            }
        }

        sendAudioLevels(stereo)

        if (!streaming) return
        if (!audioFeeder.offer(stereo, audio.captureTimeNanos)) {
            val dropped = audioFeeder.droppedChunks
            if (dropped % 100 == 1L) Log.w("StreamingService", "Audio queue full, dropped $dropped chunks")
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

    override fun onCreate() {
        // Set up notification channel
        val channel = NotificationChannel(
            "KINETIC_STREAMER",
            "Kinetic Streamer Service",
            NotificationManager.IMPORTANCE_LOW // LOW to avoid sound/vibration
        ).apply {
            description = "Keeps the streaming service running in the background"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)

        // Create pending intent to open MainActivity when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Start foreground service with notification
        val notification = NotificationCompat.Builder(this, "KINETIC_STREAMER")
            .setContentTitle("Kinetic Streamer")
            .setContentText("Ready to stream")
            .setSmallIcon(android.R.drawable.ic_menu_camera) // Use a proper icon in production
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

        startForeground(68448, notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        // Acquire wake lock to keep CPU running when screen is off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "KineticStreamer::StreamingWakeLock"
        ).apply {
            acquire()
        }

        // Acquire WiFi lock to keep WiFi in high-performance mode when screen is off
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "KineticStreamer::WifiLock"
        ).apply {
            acquire()
        }

        // Initialize settings
        val dataStore = DataStoreProvider.getDataStore(this)
        settings = Settings(dataStore)
        
        // Initialize renderer for preview
        renderer = SurfaceTextureRenderer()
        renderer?.addOutputSurface(previewSurface)

        // Apply 180° rotation setting if enabled
        val rotate180 = runBlocking { settings!!.rotateVideo180.first() }
        renderer?.setRotate180(rotate180)

        // Set up the video flow - runs continuously for preview
        // Combine with deviceRefreshTrigger to allow USB reconnection
        deviceFlowJob = combine(
            settings!!.selectedVideoDevice,
            deviceRefreshTrigger.onStart { emit(Unit) } // Emit initial value to start flow
        ) { identifier, _ -> identifier }
        .map { identifier ->
            val usbManager = this.getSystemService(USB_SERVICE) as UsbManager
            val usbDevices = usbManager.deviceList.values.toList()

            // Debug: log all USB devices
            Log.i("StreamingService", "USB devices available: ${usbDevices.size}")
            for (usb in usbDevices) {
                Log.i("StreamingService", "  - ${usb.productName} (id=${usb.deviceId}, isUvc=${usb.isUvc()})")
            }

            // First try to find by stored identifier
            if (identifier != null) {
                Log.i("StreamingService", "Looking for identifier: $identifier")
                val device = VideoSourceDevice.fromIdentifier(identifier, usbDevices)
                if (device != null) {
                    Log.i("StreamingService", "USB device found by identifier: ${device}")
                    return@map device
                }
            }

            // If stored identifier not found, check for any available UVC camera
            val uvcCamera = usbDevices.firstOrNull { it.isUvc() }
            if (uvcCamera != null) {
                Log.i("StreamingService", "Found UVC camera, using: ${uvcCamera.productName}")
                return@map VideoSourceDevice.UsbCamera(uvcCamera)
            }

            // Fall back to internal camera only if no UVC cameras available
            Log.w("StreamingService", "No USB camera found (checked ${usbDevices.size} devices), falling back to internal camera")
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val defaultDevice = cameraManager.cameraIdList.firstOrNull()
            if (defaultDevice != null) {
                val cameraDevice = VideoSourceDevice.Camera(defaultDevice)
                return@map cameraDevice
            }
            null
        }
        .flowOn(Dispatchers.IO) // Process video on IO dispatcher (blocking on frame reads)
        .flatMapLatest { device ->
            Log.i("StreamingService", "flatMapLatest received device: $device")
            // if the device is UsbDevice, request permissions
            if (device is VideoSourceDevice.UsbCamera) {
                Log.i("StreamingService", "Requesting USB camera permission...")
                val granted = device.requestPermission(this)
                Log.i("StreamingService", "USB camera permission granted: $granted")
                if (!granted) {
                    return@flatMapLatest emptyFlow()
                }
            } else if (device is VideoSourceDevice.Camera) {
                val granted = device.requestPermission(this)
                if (!granted) {
                    return@flatMapLatest emptyFlow()
                }
            } else if (device == null) {
                Log.i("StreamingService", "Device is null, returning empty flow")
                return@flatMapLatest emptyFlow()
            }
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i("StreamingService", "Camera permission not granted")
                return@flatMapLatest emptyFlow()
            }

            // Track current video device for audio source selection
            currentVideoDevice.set(device)

            // Clear previous RTMP source when switching devices
            if (device !is VideoSourceDevice.RtmpServer) {
                rtmpSourceRef.set(null)
            }

            Log.i("StreamingService", "Creating VideoSource for device: $device")
            // Pass renderer for direct rendering (avoids frame duplication/dropping)
            // Pass rtmpSourceRef so RTMP can share the source with audio
            VideoSource(this, device, renderer, rtmpSourceRef)
        }
        .onEach { surfaceTexture ->
            // Direct rendering mode: frames are rendered automatically on GL thread
            // This callback just tracks timestamp for synchronization
            val frameTimestampNanos = surfaceTexture.timestamp
            if (frameTimestampNanos > 0 && videoEncoder != null) {
                lastVideoTimestampNanos = frameTimestampNanos
            }
        }
        .launchIn(videoScope)
        
        // Set up audio flow - runs continuously for monitoring
        audioFlowJob =
            // Combine audio device setting with video device changes
            combine(
                settings!!.selectedAudioDevice,
                deviceRefreshTrigger.onStart { emit(Unit) }
            ) { deviceId, _ -> deviceId }
            .flowOn(Dispatchers.IO) // Process audio on IO dispatcher
            .flatMapLatest { deviceId ->
                // Check if current video source is RTMP
                val videoDevice = currentVideoDevice.get()
                if (videoDevice is VideoSourceDevice.RtmpServer) {
                    Log.i("StreamingService", "Using RTMP audio source (video is RTMP)")
                    RtmpAudioSource(rtmpSourceRef)
                } else {
                    Log.i("StreamingService", "Using Android audio device ID: $deviceId")
                    AudioSource(this, deviceId)
                }
            }
            .buffer(10) // Small bounded buffer to avoid dropping audio samples
            .onEach { timestampedAudio ->
                handleAudio(timestampedAudio)
            }
            .launchIn(audioScope)
    }
    
    private fun updateFpsTracking() {
        val now = System.currentTimeMillis()
        
        // Add current frame timestamp
        synchronized(frameTimestamps) {
            frameTimestamps.add(now)
            
            // Remove timestamps older than 3 seconds
            val cutoff = now - 3000
            frameTimestamps.removeAll { it < cutoff }
            
            // Calculate FPS from frames in the last 3 seconds
            if (frameTimestamps.size > 1) {
                val oldestTime = frameTimestamps.first()
                val timeSpanSeconds = (now - oldestTime) / 1000f
                if (timeSpanSeconds > 0) {
                    latestFps = (frameTimestamps.size - 1) / timeSpanSeconds
                }
            }
        }
    }
    
    private fun updateNotification(text: String, isStreaming: Boolean) {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, "KINETIC_STREAMER")
            .setContentTitle("Kinetic Streamer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
            
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(68448, notification)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_USB_DEVICE) {
            val device: UsbDevice? = intent.getParcelableExtra(EXTRA_USB_DEVICE)
            if (device != null) {
                val usbDevice = VideoSourceDevice.UsbCamera(device)
                
                // Request permission and set device asynchronously
                videoScope.launch {
                    // Request permission if it's a UVC/UAC device
                    if (device.isUvc() || device.isUac()) {
                        val granted = usbDevice.requestPermission(this@StreamingService)
                        if (!granted) {
                            Log.w("StreamingService", "USB permission denied for ${device.productName}")
                            return@launch
                        }
                    }
                    settings?.setSelectedVideoDevice(usbDevice)
                }
            }
        } else if (intent?.action == ACTION_USB_DEVICE_CHANGED) {
            Log.i("StreamingService", "USB device changed, triggering device refresh")
            deviceRefreshTrigger.tryEmit(Unit)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        // Clear shared RTMP source reference
        rtmpSourceRef.set(null)
        currentVideoDevice.set(null)

        // Release wake lock
        wakeLock?.release()
        wakeLock = null

        // Release WiFi lock
        wifiLock?.release()
        wifiLock = null

        // Cancel the device flow job
        deviceFlowJob?.cancel()
        deviceFlowJob = null
        
        // Cancel audio flow job
        audioFlowJob?.cancel()
        audioFlowJob = null

        // Cancel the IO scopes
        videoScope.cancel()
        audioScope.cancel()

        synchronized(lifecycleLock) { teardownStreaming() }
        audioLevelCallbacks.kill()

        // Release WebView overlay
        webViewOverlay?.release()
        webViewOverlay = null
        
        // Release renderer resources
        renderer?.release()
        renderer = null
        
        Log.i("StreamingService", "StreamingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}
