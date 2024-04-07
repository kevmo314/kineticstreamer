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
import kinetic.RTSPServerSink
import kinetic.DiskSink
import java.io.File


class StreamingService : Service() {
    private var videoSource: VideoSource? = null
    private var audioSource: AudioRecord? = null

    private var videoEncoderInputSurface: Surface? = null
    private var videoEncoder: MediaCodec? = null

    private var audioEncoder: MediaCodec? = null

    private val binder = object : IStreamingService.Stub() {
        override fun setPreviewSurface(surface: Surface?) {
            Log.i("StreamingService", "setPreviewSurface")

            videoSource?.setPreviewSurface(surface)
        }

        override fun startStreaming(config: StreamingConfiguration) {
            val videoMediaFormat = config.videoMediaFormat
            val audioMediaFormat = config.audioMediaFormat

            val diskSink = DiskSink(
                filesDir.absolutePath + File.separator + "kinetic", "video;audio")
            val rtspServerSink = RTSPServerSink(
                diskSink,
                listOf(videoMediaFormat.getString(MediaFormat.KEY_MIME), audioMediaFormat.getString(MediaFormat.KEY_MIME))
                    .joinToString(";"))

            videoEncoder = MediaCodec.createByCodecName(
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(videoMediaFormat)
            ).apply {
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
                        val array = ByteArray(info.size)
                        buffer.get(array, info.offset, info.size)

                        diskSink.track(0).writeSample(array, info.presentationTimeUs, info.flags)
                        rtspServerSink.writeSample(0, array, info.presentationTimeUs)

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
            videoEncoderInputSurface = videoEncoder?.createInputSurface()

            if (ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return
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

                        diskSink.track(1).writeSample(array, info.presentationTimeUs, info.flags)
                        rtspServerSink.writeSample(1, array, info.presentationTimeUs)

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