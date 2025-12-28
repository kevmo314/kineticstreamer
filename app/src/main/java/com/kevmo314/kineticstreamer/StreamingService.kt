package com.kevmo314.kineticstreamer

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kinetic.Kinetic
import kinetic.Sink
import org.json.JSONArray


class StreamingService : Service() {
    private var videoSource: VideoSource? = null
    private var audioSource: AudioRecord? = null

    private var videoEncoderInputSurface: Surface? = null
    private var videoEncoder: MediaCodec? = null

    private var audioEncoder: MediaCodec? = null

    private var outputSinks: MutableList<Sink> = mutableListOf()

    // Native methods
    private external fun initNativeLibraries(): Int

    // Load the native library
    companion object {
        init {
            System.loadLibrary("kinetic")
        }
    }

    private val binder = object : IStreamingService.Stub() {
        override fun setPreviewSurface(surface: Surface?) {
            Log.i("StreamingService", "setPreviewSurface")

            videoSource?.setPreviewSurface(surface)
        }

        override fun startStreaming(config: StreamingConfiguration, outputConfigurationsJson: String?): String? {
            val errors = mutableListOf<String>()

            val videoMediaFormat = config.videoMediaFormat
            val audioMediaFormat = config.audioMediaFormat

            val mimeTypes = listOf(
                videoMediaFormat.getString(MediaFormat.KEY_MIME),
                audioMediaFormat.getString(MediaFormat.KEY_MIME)
            ).joinToString(";")

            // Create output sinks from JSON configuration
            outputSinks.clear()
            if (!outputConfigurationsJson.isNullOrEmpty()) {
                try {
                    val jsonArray = JSONArray(outputConfigurationsJson)
                    for (i in 0 until jsonArray.length()) {
                        val configJson = jsonArray.getJSONObject(i).toString()
                        try {
                            val sink = Kinetic.newSinkFromJSON(configJson, mimeTypes)
                            outputSinks.add(sink)
                            Log.i("StreamingService", "Created sink from config: $configJson")
                        } catch (e: Exception) {
                            Log.e("StreamingService", "Failed to create sink: ${e.message}")
                            errors.add("${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("StreamingService", "Failed to parse output configurations: ${e.message}")
                    errors.add("Config: ${e.message}")
                }
            }

            val videoEncoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoMediaFormat)
            Log.i("StreamingService", "Video format: $videoMediaFormat")
            Log.i("StreamingService", "Video encoder: $videoEncoderName")

            if (videoEncoderName == null) {
                errors.add("No video encoder found for format: ${videoMediaFormat.getString(MediaFormat.KEY_MIME)}")
                return if (errors.isEmpty()) null else errors.joinToString("\n")
            }

            var videoConfigData: ByteArray? = null

            videoEncoder = MediaCodec.createByCodecName(videoEncoderName).apply {
                configure(videoMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        Log.i("StreamingService", "Encoder input buffer available")
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        var array = ByteArray(info.size)
                        buffer.get(array, info.offset, info.size)

                        // For keyframes, prepend SPS/PPS if we have them
                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 && videoConfigData != null) {
                            val combined = ByteArray(videoConfigData!!.size + array.size)
                            System.arraycopy(videoConfigData!!, 0, combined, 0, videoConfigData!!.size)
                            System.arraycopy(array, 0, combined, videoConfigData!!.size, array.size)
                            array = combined
                            Log.i("StreamingService", "Prepended SPS/PPS to keyframe, total size: ${array.size}")
                        }

                        // Write to output sinks
                        var keyframeRequested = false
                        for (sink in outputSinks) {
                            try {
                                if (sink.writeSample(0, array, info.presentationTimeUs)) {
                                    keyframeRequested = true
                                }
                            } catch (e: Exception) {
                                Log.e("StreamingService", "Failed to write video sample to sink: ${e.message}")
                            }
                        }

                        // Request keyframe if any sink requested it (PLI received)
                        if (keyframeRequested) {
                            Log.i("StreamingService", "Requesting keyframe due to PLI")
                            val params = android.os.Bundle()
                            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                            codec.setParameters(params)
                        }

                        codec.releaseOutputBuffer(index, false)
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e("StreamingService", "Encoder error: $e")
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        Log.i("StreamingService", "Encoder output format changed: $format")
                        // Extract SPS and PPS for H.264
                        val sps = format.getByteBuffer("csd-0")
                        val pps = format.getByteBuffer("csd-1")
                        if (sps != null && pps != null) {
                            val spsArray = ByteArray(sps.remaining())
                            val ppsArray = ByteArray(pps.remaining())
                            sps.get(spsArray)
                            pps.get(ppsArray)
                            videoConfigData = spsArray + ppsArray
                            Log.i("StreamingService", "Stored SPS/PPS: ${videoConfigData!!.size} bytes")
                        }
                    }
                }, Handler(HandlerThread("StreamingService").apply { start() }.looper))
            }
            videoEncoderInputSurface = videoEncoder?.createInputSurface()

            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return "Record audio permission not granted"
            }
            audioSource = AudioRecord(
                0, 48000, 2, AudioFormat.ENCODING_PCM_16BIT, 1024 * 1024
            )

            audioSource?.let {
                audioMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, it.channelCount)
                audioMediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, it.sampleRate)
                audioMediaFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, it.channelConfiguration)
                audioMediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, it.audioFormat)
                audioMediaFormat.setInteger(MediaFormat.KEY_AUDIO_SESSION_ID, it.audioSessionId)
            }

            audioEncoder = MediaCodec.createByCodecName(
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(audioMediaFormat)
            ).apply {
                configure(audioMediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                val timestamp = AudioTimestamp()

                setCallback(object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        val buffer = codec.getInputBuffer(index) ?: return
                        when (val n = audioSource?.read(buffer, buffer.capacity(), AudioRecord.READ_NON_BLOCKING)) {
                            AudioRecord.ERROR_INVALID_OPERATION -> {
                                Log.e("StreamingService", "Audio source read error")
                            }
                            AudioRecord.ERROR_BAD_VALUE -> {
                                Log.e("StreamingService", "Audio source read error")
                            }
                            AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e("StreamingService", "Audio source read error")
                            }
                            AudioRecord.ERROR, null -> {
                                Log.e("StreamingService", "Audio source read error")
                            }
                            else -> {
                                var pts = System.nanoTime()
                                // try to get a higher-precision timestamp
                                when (audioSource?.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)) {
                                    AudioRecord.SUCCESS -> {
                                        pts = timestamp.nanoTime
                                    }
                                }
                                codec.queueInputBuffer(index, 0, n, pts, 0)
                            }
                        }
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        val buffer = codec.getOutputBuffer(index) ?: return
                        val array = ByteArray(info.size)
                        buffer.get(array, info.offset, info.size)

                        // Write to output sinks (ignore keyframe request for audio)
                        for (sink in outputSinks) {
                            try {
                                sink.writeSample(1, array, info.presentationTimeUs)
                            } catch (e: Exception) {
                                Log.e("StreamingService", "Failed to write audio sample to sink: ${e.message}")
                            }
                        }

                        codec.releaseOutputBuffer(index, false)
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e("StreamingService", "Encoder error: $e")
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        Log.i("StreamingService", "Encoder output format changed: $format")
                    }
                }, Handler(HandlerThread("StreamingService").apply { start() }.looper))
            }

            videoEncoder?.start()
            videoSource?.setEncoderInputSurface(videoEncoderInputSurface)

            audioEncoder?.start()
            audioSource?.startRecording()

            return if (errors.isEmpty()) null else errors.joinToString("\n")
        }

        override fun stopStreaming() {
            videoSource?.setEncoderInputSurface(null)
            videoEncoder?.stop()
            videoEncoderInputSurface?.release()
            videoEncoderInputSurface = null
            videoEncoder?.release()
            videoEncoder = null

            audioSource?.stop()
            audioSource?.release()
            audioSource = null

            // Close all output sinks
            for (sink in outputSinks) {
                try {
                    sink.close()
                } catch (e: Exception) {
                    Log.e("StreamingService", "Failed to close sink: ${e.message}")
                }
            }
            outputSinks.clear()
        }

        override fun isStreaming(): Boolean {
            return videoEncoderInputSurface != null
        }

        override fun getActiveCameraId(): String? {
            return videoSource?.getActiveCameraId()
        }

        override fun setActiveCameraId(cameraId: String?) {
            cameraId?.let { videoSource?.setActiveCameraId(it) }
        }
    }

    override fun onCreate() {
        val channel = NotificationChannel(
            "KINETIC_STREAMER",
            "Kinetic Streamer",
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = "This is channel 1"

        getSystemService(
            NotificationManager::class.java
        ).createNotificationChannel(channel)

        startForeground(
            68448, NotificationCompat.Builder(this, "Kinetic Streamer")
                .setContentTitle("Kinetic Streamer")
                .setContentText("Streaming")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColorized(true)
                .setColor(0xFF009FDF.toInt())
                .setWhen(System.currentTimeMillis())
                .setChannelId("KINETIC_STREAMER")
                .build()
        )

        // Initialize native libraries
        try {
            val result = initNativeLibraries()
            if (result != 0) {
                Log.e("StreamingService", "Failed to initialize native libraries: $result")
            } else {
                Log.i("StreamingService", "Native libraries initialized successfully")
            }
        } catch (e: Exception) {
            Log.e("StreamingService", "Error initializing native libraries", e)
        }

        videoSource = VideoSource(this, getSystemService(Context.CAMERA_SERVICE) as CameraManager)
    }

    override fun onDestroy() {
        super.onDestroy()

        videoSource?.close()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}