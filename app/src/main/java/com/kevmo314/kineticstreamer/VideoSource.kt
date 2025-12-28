package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import com.kevmo314.kineticstreamer.kinetic.UVCSource
import com.kevmo314.kineticstreamer.kinetic.UVCStream
import com.kevmo314.kineticstreamer.kinetic.FormatDescriptor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

data class Quadruple<out A, out B, out C, out D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

fun createVideoTexture(): Int {
    val texIds = IntArray(1)
    GLES20.glGenTextures(1, texIds, 0)

    val texId = texIds[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

    // Set up sane defaults for video playback
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MIN_FILTER,
        GLES20.GL_LINEAR
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_MAG_FILTER,
        GLES20.GL_LINEAR
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_S,
        GLES20.GL_CLAMP_TO_EDGE
    )
    GLES20.glTexParameteri(
        GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
        GLES20.GL_TEXTURE_WRAP_T,
        GLES20.GL_CLAMP_TO_EDGE
    )

    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    return texId
}

@androidx.annotation.RequiresPermission(android.Manifest.permission.CAMERA)
suspend fun openCamera(cameraManager: CameraManager, outputSurface: Surface, handler: Handler, cameraId: String): CameraCaptureSession? = suspendCoroutine { continuation ->
    cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(outputSurface)
            }.build()

            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    mutableListOf<OutputConfiguration>().apply {
                        add(OutputConfiguration(outputSurface))
                    },
                    Executors.newSingleThreadExecutor(),
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            session.setRepeatingRequest(request, null, handler)
                            continuation.resume(session)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("StreamingService", "Failed to configure camera session")
                            continuation.resume(null)
                        }
                    }
                )
            )
        }

        override fun onDisconnected(camera: CameraDevice) {
            continuation.resume(null)
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e("StreamingService", "Camera error: $error")
            continuation.resume(null)
        }
    }, handler)
}


fun openUsbCamera(context: Context, device: UsbDevice, outputSurface: Surface): Triple<UsbDeviceConnection?, UVCStream?, Thread?> {
    val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    val conn = manager.openDevice(device)
    if (conn == null) {
        Log.e("VideoSource", "Failed to open USB device")
        return Triple(null, null, null)
    }

    // Initialize UVC source
    val uvcSource = UVCSource(conn.fileDescriptor)

    val stream = uvcSource.startStreaming(
        8, 1920, 1080, 30
    )

    val thread = startUvcFrameProcessing(stream, outputSurface)
    
    return Triple(conn, stream, thread)
}

// Data class to hold frame data and its PTS
data class FrameWithPTS(val data: ByteArray, val pts: Long, val eof: Boolean = false)

fun startUvcFrameProcessing(stream: UVCStream, outputSurface: Surface): Thread {
    val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080)
    // Set additional format parameters for H264 from USB cameras
    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920 * 1080) // Set max input size
    // Request more input buffers if possible
    format.setInteger("vendor.qcom-ext-dec-input-buffer-count.value", 30) // Qualcomm specific
    format.setInteger("input-buffer-count", 30)

    var sentOA4PPS = false
    
    var frameQueue = LinkedBlockingDeque<FrameWithPTS>();

    MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
        setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(
                codec: MediaCodec,
                index: Int
            ) {
                if (!sentOA4PPS) {
                    // write dummy sps, OA4 compatibility
                    val inputBuffer = codec.getInputBuffer(index)
                    inputBuffer?.clear()
                    // 00 00 00 01 67 64 00 34 ac 4d 00 f0 04 4f cb 35 01 01 01 40 00 00 fa 00 00 3a 98 03 c7 0c a8 00 00 00 01 68 ee 3c b0
                    val data = byteArrayOf(
                        0x00.toByte(),
                        0x00.toByte(),
                        0x00.toByte(),
                        0x01.toByte(),
                        0x67.toByte(),
                        0x64.toByte(),
                        0x00.toByte(),
                        0x34.toByte(),
                        0xac.toByte(),
                        0x4d.toByte(),
                        0x00.toByte(),
                        0xf0.toByte(),
                        0x04.toByte(),
                        0x4f.toByte(),
                        0xcb.toByte(),
                        0x35.toByte(),
                        0x01.toByte(),
                        0x01.toByte(),
                        0x01.toByte(),
                        0x40.toByte(),
                        0x00.toByte(),
                        0x00.toByte(),
                        0xfa.toByte(),
                        0x00.toByte(),
                        0x00.toByte(),
                        0x3a.toByte(),
                        0x98.toByte(),
                        0x03.toByte(),
                        0xc7.toByte(),
                        0x0c.toByte(),
                        0xa8.toByte(),
                        0x00.toByte(),
                        0x00.toByte(),
                        0x00.toByte(),
                        0x01.toByte(),
                        0x68.toByte(),
                        0xee.toByte(),
                        0x3c.toByte(),
                        0xb0.toByte()
                    )

                    inputBuffer?.put(data)
                    codec.queueInputBuffer(
                        index,
                        0,
                        data.size,
                        System.nanoTime() / 1000,
                        0
                    )
                    sentOA4PPS = true
                    return
                }

                val frameWithPTS = frameQueue.poll()
                if (frameWithPTS == null) {
                    // No frame available yet, return the buffer empty to avoid blocking
                    codec.queueInputBuffer(index, 0, 0, 0, 0)
                    return
                }
                
                // Check for EOF
                if (frameWithPTS.eof) {
                    Log.w("VideoSource", "Received EOF marker, stopping decoder")
                    codec.stop()
                    return
                }

                // Queue input buffer with frame-based PTS
                val inputBuffer = codec.getInputBuffer(index)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(frameWithPTS.data)
                    codec.queueInputBuffer(
                        index,
                        0,
                        frameWithPTS.data.size,
                        frameWithPTS.pts,  // Use PTS from UVC stream
                        0
                    )
                } else {
                    Log.e("VideoSource", "Decoder input buffer is null at index $index")
                }
            }

            override fun onOutputBufferAvailable(
                codec: MediaCodec,
                index: Int,
                info: MediaCodec.BufferInfo
            ) {
                codec.releaseOutputBuffer(index, true)
            }

            override fun onError(
                codec: MediaCodec,
                e: MediaCodec.CodecException
            ) {
                Log.e("VideoSource", "Decoder error: ${e.message}")
            }

            override fun onOutputFormatChanged(
                codec: MediaCodec,
                format: MediaFormat
            ) {
                Log.e("VideoSource", "Decoder format changed to: $format")
            }

        })
        configure(format, outputSurface, null, 0)
        start()
    }

    return thread {
        while (!Thread.currentThread().isInterrupted) {
            val frameData = stream.readFrame()
            if (frameData == null) {
                // Signal EOF and exit
                frameQueue.put(FrameWithPTS(ByteArray(0), 0, true))
                break
            }
            // Get the PTS for this frame from the UVC stream
            val pts = stream.getPTS()
            frameQueue.put(FrameWithPTS(frameData, pts, false))
        }
    }
}

// Helper function to validate H264 NAL units
private fun validateH264Frame(data: ByteArray): Pair<Boolean, String> {
    if (data.size < 4) {
        return Pair(false, "Frame too small")
    }
    
    // Check for start codes (0x00 0x00 0x00 0x01 or 0x00 0x00 0x01)
    var hasStartCode = false
    if (data[0] == 0x00.toByte() && data[1] == 0x00.toByte()) {
        if (data[2] == 0x01.toByte() || (data[2] == 0x00.toByte() && data.size > 3 && data[3] == 0x01.toByte())) {
            hasStartCode = true
        }
    }
    
    if (!hasStartCode) {
        // Check if it might be length-prefixed format (used by some cameras)
        val possibleLength = ((data[0].toInt() and 0xFF) shl 24) or
                           ((data[1].toInt() and 0xFF) shl 16) or
                           ((data[2].toInt() and 0xFF) shl 8) or
                           (data[3].toInt() and 0xFF)
        if (possibleLength > 0 && possibleLength <= data.size - 4) {
            return Pair(true, "Length-prefixed format")
        }
        return Pair(false, "No valid start code or length prefix")
    }
    
    // Find NAL unit type
    val nalStartOffset = if (data[2] == 0x01.toByte()) 3 else 4
    if (data.size <= nalStartOffset) {
        return Pair(false, "No NAL unit data after start code")
    }
    
    val nalUnitType = data[nalStartOffset].toInt() and 0x1F
    val nalRefIdc = (data[nalStartOffset].toInt() shr 5) and 0x03
    
    // Validate NAL unit type (1-23 are valid for H.264)
    if (nalUnitType == 0 || nalUnitType > 23) {
        return Pair(false, "Invalid NAL unit type: $nalUnitType")
    }
    
    // Check for corrupted data patterns
    var zeroCount = 0
    var ffCount = 0
    for (i in nalStartOffset until minOf(nalStartOffset + 100, data.size)) {
        if (data[i] == 0x00.toByte()) zeroCount++
        if (data[i] == 0xFF.toByte()) ffCount++
    }
    
    if (zeroCount > 90 || ffCount > 90) {
        return Pair(false, "Suspicious data pattern (too many 0x00 or 0xFF bytes)")
    }
    
    return Pair(true, "Valid NAL type: $nalUnitType, ref_idc: $nalRefIdc")
}
/**
 * VideoSource represents a persistent video source that manages the
 * camera device (or USB camera) and its associated resources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@androidx.annotation.RequiresPermission(
    android.Manifest.permission.CAMERA
)
fun VideoSource(      context: Context,
                        device: VideoSourceDevice): Flow<SurfaceTexture> = callbackFlow {
    var captureSession: CameraCaptureSession? = null

    var activeUsbDeviceConnection: UsbDeviceConnection? = null
    var activeUvcStream: UVCStream? = null
    var activeDecoder: MediaCodec? = null
    var activeUvcThread: Thread? = null
    val outputSurfaceTexture = SurfaceTexture(createVideoTexture()).apply {
        // Set default buffer size to match expected video resolution
        setDefaultBufferSize(1920, 1080)
        Log.i("VideoSource", "Created SurfaceTexture with default buffer size 1920x1080")
    }
    val outputSurface = Surface(outputSurfaceTexture)

    val handlerThread by lazy {
        HandlerThread("CameraSource").apply {
            start()
        }
    }

    val handler by lazy {
        Handler(handlerThread.looper)
    }

    when (device) {
        is VideoSourceDevice.UsbCamera -> {
            Log.i("VideoSource", "Opening USB camera: ${device.usbDevice.productName}")
            val (conn, uvcStream, thread) = openUsbCamera(context, device.usbDevice, outputSurface)
            activeUsbDeviceConnection = conn
            activeUvcStream = uvcStream
            activeUvcThread = thread
        }
        is VideoSourceDevice.Camera -> {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(device.cameraId)
            val sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION)
            val streamConfigMap = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val outputSizes = streamConfigMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
            Log.i("VideoSource", "Opening camera ${device.cameraId}, sensor orientation: $sensorOrientation")
            Log.i("VideoSource", "Available output sizes: ${outputSizes?.take(5)?.joinToString()}")
            captureSession = openCamera(cameraManager, outputSurface, handler, device.cameraId)
        }
    }

    var frameCount = 0
    outputSurfaceTexture.setOnFrameAvailableListener {
        frameCount++
        if (frameCount == 1 || frameCount % 100 == 0) {
            Log.i("VideoSource", "Frame $frameCount received, timestamp: ${it.timestamp}")
        }
        trySend(it)
    }

    awaitClose {
        activeUvcThread?.interrupt()
        activeUvcThread?.join()
        activeDecoder?.release()
        activeUvcStream?.close()
        activeUsbDeviceConnection?.close()
        captureSession?.close()
        outputSurface.release()
        outputSurfaceTexture.release()
        handlerThread.quitSafely()
    }
}