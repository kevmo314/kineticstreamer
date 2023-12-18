package com.kevmo314.kineticstreamer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kinetic.H264Track
import kinetic.WHIPSink
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore


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

            previewSurface = surface

            reconfigure()
        }
    }

    private val cameraDeviceLock = Semaphore(1)
    private var cameraDevice: CameraDevice? = null

    private var encoder = MediaCodec.createEncoderByType("video/avc").apply {
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
//        format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
//        format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)

        configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

//        val bufferedOutputStream = BufferedOutputStream(FileOutputStream("/data/data/com.kevmo314.kineticstreamer/files/encoder.h264"))

        setCallback(object : MediaCodec.Callback() {
            var sink = WHIPSink("https://b.siobud.com/api/whip", "kevmo314")
            var videoTrack: H264Track? = null

            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                Log.i("StreamingService", "Encoder input buffer available")
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                val videoTrack = videoTrack ?: return
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
                Log.i("StreamingService", "Encoder output format changed")
//                sink.demo()
                videoTrack = sink.addH264Track()
                sink.connect()
                Log.i("StreamingService", "Encoder output format changed: $format")
            }
        }, handler)
    }

    private val encoderInputSurface by lazy {
        val surface = encoder.createInputSurface()
        encoder.start()
        surface
    }

    private var previewSurface: Surface? = null

    private val activeCaptureSessionLock = Semaphore(1)
    private var activeCaptureSession: CameraCaptureSession? = null

    @SuppressLint("MissingPermission")
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


        // bind the first available camera
        val cameraManager =
            getSystemService(Context.CAMERA_SERVICE) as CameraManager

        cameraDeviceLock.acquire()
        cameraManager.openCamera(
            cameraManager.cameraIdList[0],
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.i("StreamingService", "Camera opened")

                    cameraDevice = camera
                    cameraDeviceLock.release()

                    reconfigure()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.i("StreamingService", "Camera disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e("StreamingService", "Camera error: $error")
                }
            }, null
        )
    }

    fun reconfigure() {
        Log.i("StreamingService", "reconfigure")

        if (previewSurface == null) {
            return
        }

        val camera = cameraDevice ?: return

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(encoderInputSurface)
            previewSurface?.let { addTarget(it) }
        }.build()

        activeCaptureSessionLock.acquire()

        // close the previous session if it exists
        activeCaptureSession?.close()

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                mutableListOf(OutputConfiguration(encoderInputSurface)).apply {
                    previewSurface?.let { add(OutputConfiguration(it)) }
                },
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i("StreamingService", "Camera session configured")

                        activeCaptureSession = session
                        activeCaptureSessionLock.release()

                        session.setRepeatingRequest(request, null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("StreamingService", "Failed to configure camera session")
                    }
                }
            )
        )
    }

    @SuppressLint("RestrictedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("StreamingService", "onStartCommand")


        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}