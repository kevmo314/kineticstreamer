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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.provider.Settings as SystemSettings
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.kevmo314.kineticstreamer.kinetic.WHIPSink
import com.kevmo314.kineticstreamer.kinetic.PLICallback
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicReference
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
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            context.startService(Intent(context, StreamingService::class.java).apply {
                                action = ACTION_SET_USB_DEVICE
                                putExtra(EXTRA_USB_DEVICE, device)
                            })
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    Log.i("UsbReceiver", "USB device attached: ${device?.productName}")

                    // Check if it's a UVC camera and auto-open is enabled
                    if (device != null && device.isUvc()) {
                        val settings = Settings(DataStoreProvider.getDataStore(context))
                        val autoOpen = runBlocking {
                            settings.autoOpenOnUsbCamera.first()
                        }
                        if (autoOpen) {
                            Log.i("UsbReceiver", "Auto-opening app for UVC camera: ${device.productName}")
                            context.startActivity(Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            })
                        }
                    }

                    // Trigger device refresh to attempt reconnection
                    context.startService(Intent(context, StreamingService::class.java).apply {
                        action = ACTION_USB_DEVICE_CHANGED
                    })
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

    // AudioRecord no longer used - audio comes from AudioSource flow

    private var videoEncoderInputSurface: Surface? = null
    private var videoEncoder: MediaCodec? = null

    private var previewSurface: Surface? = null

    private var audioEncoder: MediaCodec? = null
    private var settings: Settings? = null
    private var deviceFlowJob: Job? = null
    private var renderer: SurfaceTextureRenderer? = null
    private var webViewOverlay: WebViewOverlay? = null
    private var whipSink: WHIPSink? = null
    private var queue: ExecutorService? = null
    private var lastBitrate: Int = 0 // Track last bitrate to avoid frequent updates
    private var audioFlowJob: Job? = null
    private var audioLevelCallback: IAudioLevelCallback? = null
    
    // FPS tracking - circular buffer for last 3 seconds of frame timestamps
    private val frameTimestamps = mutableListOf<Long>()
    private var latestFps: Float = 0f
    // Separate coroutine scopes for video and audio processing on IO dispatcher
    private val videoScope = CoroutineScope(Dispatchers.IO + Job())
    private val audioScope = CoroutineScope(Dispatchers.IO + Job())
    private var debugAudioTrack: AudioTrack? = null // For debugging UAC audio playback
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Flow to trigger device reconnection (emits Unit when USB device changes)
    private val deviceRefreshTrigger = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    
    // Timestamp synchronization
    private var streamStartTimeNanos: Long = 0
    private var audioSampleCount: Long = 0
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
            // Update notification to show streaming status
            updateNotification("Streaming active", true)
            
            // Check if already streaming
            if (videoEncoder != null || audioEncoder != null) {
                Log.w("StreamingService", "Already streaming")
                return
            }
            
            // Store configuration
            // TODO: Initialize WHIP sink when endpoint configuration is available
            // For now, WHIP sink needs to be initialized with hardcoded values or from settings
            
            // Initialize timestamp synchronization FIRST
            streamStartTimeNanos = System.nanoTime()
            audioSampleCount = 0
            lastVideoTimestampNanos = 0
            Log.i("StreamingService", "Stream started at nanos: $streamStartTimeNanos")
            
            // Setup and start encoders (they'll use the initialized timestamps)
            setupEncoders()
            Log.i("StreamingService", "Encoders initialized and started")
            
            // Initialize renderer and launch flows
            renderer?.addOutputSurface(videoEncoderInputSurface)
            Log.i("StreamingService", "Flows launched and streaming active")
        }

        override fun stopStreaming() {
            // Update notification to show stopped status
            updateNotification("Ready to stream", false)
            
            // Reset timestamp tracking
            streamStartTimeNanos = 0
            audioSampleCount = 0
            lastVideoTimestampNanos = 0
            
            // Reset FPS tracking
            frameTimestamps.clear()
            latestFps = 0f

            // Remove encoder surface from renderer (keep preview surface)
            renderer?.removeOutputSurface(videoEncoderInputSurface)
            
            // Release hardcoded WebView overlay
            webViewOverlay?.release()
            webViewOverlay = null
            renderer?.removeOverlay()
            
            // Stop and release video encoder
            videoEncoder?.stop()
            videoEncoderInputSurface?.release()
            videoEncoderInputSurface = null
            videoEncoder?.release()
            videoEncoder = null

            // Stop and release audio encoder
            audioEncoder?.stop()
            audioEncoder?.release() 
            audioEncoder = null
            
            // Clean up WHIP sink
            whipSink?.close()
            whipSink = null
            
            // Clean up queue
            queue?.shutdown()
            queue = null
            
            Log.i("StreamingService", "Streaming stopped and resources cleaned up")
        }

        override fun isStreaming(): Boolean {
            return videoEncoder != null || audioEncoder != null
        }
        
        override fun getCurrentBitrate(): Int {
            return lastBitrate
        }
        
        override fun getCurrentFps(): Float {
            return latestFps
        }
        
        override fun setAudioLevelCallback(callback: IAudioLevelCallback?) {
            audioLevelCallback = callback
        }
        
        override fun setWebViewOverlay(url: String?, x: Int, y: Int, width: Int, height: Int) {
            if (url == null || url.isEmpty()) {
                removeWebViewOverlay()
                return
            }

            if (webViewOverlay == null) {
                webViewOverlay = WebViewOverlay(applicationContext)
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
    }
    
    private fun setupEncoders() {
        // Hardcode H.264 video format optimized for ultra-low latency
        val videoMediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
            val initialBitrate = 2000000  // 2 Mbps initial, adjusted dynamically by GCC
            setInteger(MediaFormat.KEY_BIT_RATE, initialBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            lastBitrate = initialBitrate

            // Ultra-low latency settings
            setInteger(MediaFormat.KEY_CAPTURE_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
//            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) // CBR not supported by all HW encoders

            // Disable B-frames for lower latency (P-frames only)
//            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)

            // Real-time priority and low-latency flags (Android 30+)
            setInteger(MediaFormat.KEY_PRIORITY, 0) // Real-time priority (0 = real-time, 1 = non-real-time)
            setInteger(MediaFormat.KEY_LATENCY, 1) // Output after 1 frame queued (no buffering)
            setInteger(MediaFormat.KEY_OPERATING_RATE, 60) // Higher than frame rate for headroom
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setInteger(
                    MediaFormat.KEY_LOW_LATENCY,
                    1
                ) // Enable low-latency mode (1 = enabled, 0 = disabled)
            }

            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) // Baseline for max performance
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel4)
        }

        // Hardcode Opus audio format optimized for low latency
        val audioMediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            
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
            
            Log.i("StreamingService", "Opus encoder configured for ultra-low latency: VoIP mode, no FEC")
        }

        // Create WHIP sink with hardcoded URL and token
        whipSink = WHIPSink("https://b.siobud.com/api/whip", "mugit",
            listOf(videoMediaFormat.getString(MediaFormat.KEY_MIME), audioMediaFormat.getString(MediaFormat.KEY_MIME))
                .joinToString(";"))
        
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

        queue = Executors.newSingleThreadExecutor()

        var initialTimestamp: Long = 0
        var frameCount = 0
        var lastFrameTime: Long = 0

        videoEncoder = MediaCodec.createByCodecName(
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoMediaFormat)
        ).apply {
            configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    // Input buffers handled by Surface input
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    val array = ByteArray(info.size)
                    buffer.get(array, info.offset, info.size)

                    if (initialTimestamp == 0L) {
                        initialTimestamp = info.presentationTimeUs
                    }
                    val ts = info.presentationTimeUs - initialTimestamp

                    // Process immediately for ultra-low latency (no queuing)
                    try {
                        val gccTargetBitrate = whipSink?.writeH264(array, ts) ?: 0

                        frameCount++
                        val now = System.nanoTime()
                        val interFrameMs = if (lastFrameTime > 0) (now - lastFrameTime) / 1_000_000 else 0
                        lastFrameTime = now

                        // Log timing every 90 frames (~3 sec at 30fps)
                        if (frameCount % 90 == 0) {
                            val effectiveFps = if (interFrameMs > 0) 1000.0 / interFrameMs else 0.0
                            Log.i("StreamingService", "Encoder: frame=$frameCount, ${String.format("%.1f", effectiveFps)}fps, ${array.size/1024}KB")
                        }

                        // Update encoder bitrate if changed significantly (>5% difference)
                        // Leave 25% headroom for FEC overhead (per RFC 8854 recommendations)
                        if (gccTargetBitrate > 0) {
                            val encoderBitrate = (gccTargetBitrate * 0.75).toInt()
                            val diff = kotlin.math.abs(encoderBitrate - lastBitrate)
                            if (diff > lastBitrate * 0.05) {
                                videoEncoder?.let { encoder ->
                                    val params = Bundle()
                                    params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, encoderBitrate)
                                    encoder.setParameters(params)
                                    Log.i("StreamingService", "Bitrate: GCC=${gccTargetBitrate/1000}Kbps -> Encoder=${encoderBitrate/1000}Kbps (75% for FEC headroom)")
                                    lastBitrate = encoderBitrate
                                }
                            }
                        }

                        // Track frame for FPS calculation
                        updateFpsTracking()
                    } catch (e: Exception) {
                        Log.e("StreamingService", "Error writing H264 data: ${e.message}")
                    }

                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: IllegalStateException) {
                        // Codec was stopped, ignore
                    }
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e("StreamingService", "Audio encoder error: ${e.message}, isRecoverable=${e.isRecoverable}, isTransient=${e.isTransient}", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    Log.i("StreamingService", "Encoder output format changed: $format")
                }
            }, Handler(HandlerThread("StreamingService-Video").apply { 
                priority = Thread.MAX_PRIORITY
                start()
            }.looper))
        }
        videoEncoderInputSurface = videoEncoder?.createInputSurface()

        if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        // Set audio format parameters directly (no need for dummy AudioRecord)
        // These match the parameters used in AudioSource.kt
        audioMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1) // Mono
        audioMediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, 48000) // 48kHz
        audioMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_MONO)
        audioMediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

        audioEncoder = MediaCodec.createByCodecName(
            MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(audioMediaFormat)
        ).apply {
            configure(audioMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    try {
                        // Get latest audio data (non-blocking)
                        val audioData = latestAudioData.getAndSet(null)
                        if (audioData == null) {
                            // No data available, queue empty buffer
                            codec.queueInputBuffer(index, 0, 0, 0, 0)
                            return
                        }

                        val buffer = codec.getInputBuffer(index) ?: return
                        buffer.clear()

                        val dataSize = minOf(audioData.size, buffer.capacity())
                        buffer.put(audioData, 0, dataSize)

                        // Calculate timestamp for this audio buffer
                        val sampleRate = 48000
                        val samplesInBuffer = dataSize / 2 // 16-bit PCM
                        val timestampUs = if (streamStartTimeNanos > 0) {
                            val elapsedNanos = ((audioSampleCount - samplesInBuffer) * 1_000_000_000L) / sampleRate
                            elapsedNanos / 1000 // Relative time from start
                        } else {
                            0L // Start from 0
                        }

                        codec.queueInputBuffer(index, 0, dataSize, timestampUs, 0)
                    } catch (e: IllegalStateException) {
                        // Encoder is stopping, ignore
                    }
                }

                override fun onOutputBufferAvailable(
                    codec: MediaCodec,
                    index: Int,
                    info: MediaCodec.BufferInfo
                ) {
                    try {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        val array = ByteArray(info.size)
                        buffer.get(array, info.offset, info.size)

                        // Process immediately for ultra-low latency (no queuing)
                        try {
                            whipSink?.writeOpus(array, info.presentationTimeUs)
                        } catch (e: Exception) {
                            Log.e("StreamingService", "Error writing Opus data: ${e.message}", e)
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
            }, Handler(HandlerThread("StreamingService-Audio").apply { 
                priority = Thread.MAX_PRIORITY
                start()
            }.looper))
        }

        videoEncoder?.start()
        audioEncoder?.start()
    }

    private fun setupDebugAudioPlayback() {
        // Create AudioTrack for debug playback - matching recording parameters
        val sampleRate = 48000  // Match recording sample rate
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO  // Match recording channel (mono)
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            channelConfig,
            audioFormat
        )
        
        // Use AudioAttributes instead of deprecated stream type
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
            
        val audioTrackFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioFormat)
            .build()
            
        debugAudioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioTrackFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        
        // Check if AudioTrack was initialized successfully
        if (debugAudioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            Log.e("StreamingService", "Failed to initialize AudioTrack! State: ${debugAudioTrack?.state}")
            debugAudioTrack?.release()
            debugAudioTrack = null
            return
        }
        
        val playState = debugAudioTrack?.playState
        debugAudioTrack?.play()
        val newPlayState = debugAudioTrack?.playState
        
        // Check volume levels
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        Log.i("StreamingService", "Debug audio playback started - sample rate: $sampleRate Hz, buffer size: $bufferSize, state: $playState -> $newPlayState")
        Log.i("StreamingService", "Audio volume: $currentVolume/$maxVolume, AudioTrack state: ${debugAudioTrack?.state}, playState: $newPlayState")
    }
    
    
    // Holds latest audio data for the encoder callback (replaces previous value to avoid backup)
    private val latestAudioData = AtomicReference<ByteArray?>(null)

    private fun feedAudioDataToEncoder(audioData: ByteArray) {
        // Calculate timestamp based on sample count
        val sampleRate = 48000 // Must match AudioSource sample rate
        val samplesInBuffer = audioData.size / 2 // 16-bit PCM = 2 bytes per sample
        
        // Calculate precise timestamp relative to stream start
        val timestampUs = if (streamStartTimeNanos > 0) {
            val elapsedNanos = (audioSampleCount * 1_000_000_000L) / sampleRate
            elapsedNanos / 1000 // Convert to microseconds (relative time from start)
        } else {
            0L // Start from 0 if stream not yet started
        }
        
        // Update sample count for next frame
        audioSampleCount += samplesInBuffer

        // Play audio for debugging
//        val bytesWritten = debugAudioTrack?.write(audioData, 0, audioData.size) ?: 0
//        if (bytesWritten < audioData.size) {
//            Log.w("StreamingService", "AudioTrack only wrote $bytesWritten of ${audioData.size} bytes")
//        } else if (bytesWritten > 0) {
//            // Log successful write periodically for debugging
//            if (System.currentTimeMillis() % 1000 < 50) { // Log roughly once per second
//                val maxValue = audioData.maxOfOrNull { abs(it.toInt()) } ?: 0
//                Log.d("StreamingService", "AudioTrack wrote $bytesWritten bytes, playState: ${debugAudioTrack?.playState}, max amplitude: $maxValue, has sound: $hasSound")
//            }
//        }
        
        // Calculate audio levels for visualizer
        calculateAndSendAudioLevels(audioData)
        
        // Store latest audio data for the encoder callback (overwrites previous)
        latestAudioData.set(audioData)
    }

    private fun calculateAndSendAudioLevels(audioData: ByteArray) {
        audioLevelCallback?.let { callback ->
            try {
                // Convert byte array to 16-bit samples
                val samples = ShortArray(audioData.size / 2)
                for (i in samples.indices) {
                    val low = audioData[i * 2].toInt() and 0xFF
                    val high = audioData[i * 2 + 1].toInt() and 0xFF
                    samples[i] = ((high shl 8) or low).toShort()
                }
                
                // Split samples into 12 frequency bands for visualizer
                val barCount = 12
                val samplesPerBar = samples.size / barCount
                val levels = FloatArray(barCount)
                
                for (i in 0 until barCount) {
                    val startIndex = i * samplesPerBar
                    val endIndex = minOf(startIndex + samplesPerBar, samples.size)
                    
                    var sum = 0L
                    for (j in startIndex until endIndex) {
                        val sample = samples[j].toInt()
                        sum += (sample * sample).toLong()
                    }
                    
                    // Calculate RMS and normalize to 0-1 range
                    val rms = kotlin.math.sqrt(sum.toDouble() / (endIndex - startIndex)).toFloat()
                    levels[i] = (rms / 32768.0f).coerceIn(0.0f, 1.0f)
                }
                
                callback.onAudioLevels(levels)
            } catch (e: Exception) {
                Log.e("StreamingService", "Error calculating audio levels: ${e.message}")
            }
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

        startForeground(68448, notification)

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

        // Check for overlay permission before initializing WebView overlay
        webViewOverlay = WebViewOverlay(applicationContext)
        webViewOverlay?.setRenderer(renderer)
        val success = webViewOverlay?.initialize(
            url = "https://overlays.rtirl.com/gta.html?key=rm0qb5jr0qtis39f",
            x = 950,
            y = 1080 - 270,
            width = 960,
            height = 260,
            scale = 0.5f,
        ) ?: false

        // Set up the video flow - runs continuously for preview
        // Combine with deviceRefreshTrigger to allow USB reconnection
        deviceFlowJob = combine(
            settings!!.selectedVideoDevice,
            deviceRefreshTrigger.onStart { emit(Unit) } // Emit initial value to start flow
        ) { identifier, _ -> identifier }
        .map { identifier ->
            if (identifier == null) return@map null
            val usbManager = this.getSystemService(USB_SERVICE) as UsbManager
            val device = VideoSourceDevice.fromIdentifier(identifier, usbManager.deviceList.values.toList())
            if (device != null) {
                Log.i("StreamingService", "USB device found: ${device}")
                return@map device
            }
            Log.w("StreamingService", "USB device not found, falling back to camera")
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
            Log.i("StreamingService", "Creating VideoSource for device: $device")
            VideoSource(this, device)
        }
        .buffer(Channel.CONFLATED) // Only keep latest frame to minimize latency
        .onEach { surfaceTexture ->
            // Capture the SurfaceTexture timestamp for synchronization (only if streaming)
            val frameTimestampNanos = surfaceTexture.timestamp
            if (frameTimestampNanos > 0 && videoEncoder != null) {
                lastVideoTimestampNanos = frameTimestampNanos
            }
            
            // Render the SurfaceTexture to all active output surfaces
            // Must use Main thread for OpenGL operations with SurfaceTexture
            withContext(Dispatchers.Main) {
                renderer?.renderFrame(surfaceTexture)
            }
        }
        .launchIn(videoScope)
        
        // Set up audio flow - runs continuously for monitoring
        setupDebugAudioPlayback()
        audioFlowJob =
            // Monitor the selected audio device
            settings!!.selectedAudioDevice
            .flowOn(Dispatchers.IO) // Process audio on IO dispatcher
            .flatMapLatest { deviceId ->
                Log.i("StreamingService", "Using selected audio device ID: $deviceId")
                AudioSource(this, deviceId)
            }
            .buffer(Channel.CONFLATED) // Only keep latest audio to minimize latency
            .onEach { audioData ->
                // Only feed to encoder if streaming
                if (audioEncoder != null) {
                    feedAudioDataToEncoder(audioData)
                } else {
                    // Still calculate audio levels for visualizer even when not streaming
                    calculateAndSendAudioLevels(audioData)
                }
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
                kotlinx.coroutines.GlobalScope.launch {
                    // Request permission if it's a UVC/UAC device
                    if (device.isUvc() || device.isUac()) {
                        val granted = usbDevice.requestPermission(this@StreamingService)
                        if (!granted) {
                            Log.w("StreamingService", "USB permission denied for ${device.productName}")
                            return@launch
                        }
                    }
                    
                    // Update active device locally
                    // Device is now stored in settings

                    // Save to settings
                    videoScope.launch {
                        settings?.setSelectedVideoDevice(usbDevice)
                    }
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
        
        // Release wake lock
        wakeLock?.release()
        wakeLock = null

        // Release WiFi lock
        wifiLock?.release()
        wifiLock = null

        // Cancel the device flow job
        deviceFlowJob?.cancel()
        deviceFlowJob = null
        
        // Clean up encoders
        renderer?.removeOutputSurface(videoEncoderInputSurface)
        videoEncoder?.stop()
        videoEncoderInputSurface?.release()
        videoEncoderInputSurface = null
        videoEncoder?.release()
        videoEncoder = null

        // Cancel audio flow job
        audioFlowJob?.cancel()
        audioFlowJob = null
        
        // Cancel the IO scopes
        videoScope.cancel()
        audioScope.cancel()
        
        // Clean up debug audio playback
        debugAudioTrack?.stop()
        debugAudioTrack?.release()
        debugAudioTrack = null
        

        // AudioRecord cleanup no longer needed - handled by AudioSource flow
        
        audioEncoder?.stop()
        audioEncoder?.release()
        audioEncoder = null
        
        whipSink?.close()
        whipSink = null
        
        queue?.shutdown()
        queue = null
        
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