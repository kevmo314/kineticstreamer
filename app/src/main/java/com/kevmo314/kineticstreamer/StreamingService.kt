package com.kevmo314.kineticstreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kinetic.H264Track
import kinetic.WHIPSink

class StreamingService : Service() {
    private val handlerThread by lazy {
        HandlerThread("StreamingService").apply {
            start()
        }
    }

    private val handler by lazy {
        Handler(handlerThread.looper)
    }

    private val binder = object : IStreamingService.Stub() {
        override fun setPreviewSurface(surface: Surface?) {
            Log.i("StreamingService", "setPreviewSurface")

            cameraSource?.setPreviewSurface(surface)
        }

        override fun startStreaming() {
            encoder = MediaCodec.createEncoderByType("video/avc").apply {
                val format = MediaFormat.createVideoFormat(
                    name, 1920, 1080
                )
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                format.setInteger(MediaFormat.KEY_BIT_RATE, 10 * 1024 * 1024)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 25)
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 3)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    format.setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
                }

                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                setCallback(object : MediaCodec.Callback() {
                    var sink = WHIPSink("https://b.siobud.com/api/whip", "kevmo314")
                    var videoTrack: H264Track = sink.addH264Track()

                    init {
                        sink.connect()
                    }

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
                        videoTrack.writeH264AnnexBSample(array, info.presentationTimeUs)
                        codec.releaseOutputBuffer(index, false)
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        Log.e("StreamingService", "Encoder error: $e")
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        Log.i("StreamingService", "Encoder output format changed: $format")
                    }
                }, handler)
            }
            encoderInputSurface = encoder?.createInputSurface()
            encoder?.start()
            cameraSource?.setEncoderInputSurface(encoderInputSurface)
        }

        override fun stopStreaming() {
            cameraSource?.setEncoderInputSurface(null)
            encoder?.stop()
            encoderInputSurface?.release()
            encoderInputSurface = null
            encoder?.release()
            encoder = null
        }

        override fun isStreaming(): Boolean {
            return encoderInputSurface != null
        }

        override fun getActiveCameraId(): String? {
            return cameraSource?.getActiveCameraId()
        }

        override fun setActiveCameraId(cameraId: String?) {
            cameraId?.let { cameraSource?.setActiveCameraId(it) }
        }
    }

    private var cameraSource: CameraSource? = null

    private var encoderInputSurface: Surface? = null
    private var encoder: MediaCodec? = null

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

        cameraSource = CameraSource(this, getSystemService(Context.CAMERA_SERVICE) as CameraManager)
    }

    override fun onDestroy() {
        super.onDestroy()

        cameraSource?.close()
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}