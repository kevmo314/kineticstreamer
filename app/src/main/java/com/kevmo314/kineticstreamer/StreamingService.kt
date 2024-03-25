package com.kevmo314.kineticstreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import kinetic.RTSPServerSink
import kinetic.DiskSink
import java.io.File


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

        override fun startStreaming(config: StreamingConfiguration) {
            val format = config.toMediaFormat()
            val codecName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            encoder = MediaCodec.createByCodecName(codecName).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                Log.i("StreamingService", "Encoder callback")

                setCallback(object : MediaCodec.Callback() {
                    var diskSink = DiskSink(filesDir.absolutePath + File.separator + "kinetic",
                        "video")
                    var rtspServerSink = RTSPServerSink(diskSink, format.getString(MediaFormat.KEY_MIME))
//                     var sink = WHIPSink("https://whip.vdo.ninja/asdasdf", "asdasdf")

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

                        // write to mp4 log
                        // if it's a keyframe, reconfigure the muxer.
//                        if ((info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) > 0) {
//                            muxer?.stop()
//                            muxer?.release()
//                            val sdcard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                                getExternalFilesDir(Environment.DIRECTORY_RECORDINGS)
//                            } else {
//                                getExternalFilesDir(Environment.DIRECTORY_MOVIES)
//                            }
//                            val file = File(filesDir, "kinetic")
//                            file.mkdirs()
//                            val path = File(file, "$ntp.${config.fileExtension}")
//                            muxer = MediaMuxer(path.absolutePath, config.container)
//                            muxerVideoTrack = muxer?.addTrack(format)
//                            muxer?.start()
//                        }
//                        muxer?.writeSampleData(muxerVideoTrack!!, ByteBuffer.wrap(array), info)
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