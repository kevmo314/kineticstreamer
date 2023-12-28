package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.core.app.ActivityCompat
import java.util.concurrent.Executors

/**
 * CameraSource represents a persistent camera source that manages the
 * camera device and its associated resources.
 */
class CameraSource(
    private val context: Context,
    private val cameraManager: CameraManager
) {
    private var activeCameraDevice: CameraDevice? = null
    private var activeCaptureSession: CameraCaptureSession? = null

    private var activePreviewSurface: Surface? = null
    private var activeEncoderInputSurface: Surface? = null

    private val handlerThread by lazy {
        HandlerThread("CameraSource").apply {
            start()
        }
    }

    private val handler by lazy {
        Handler(handlerThread.looper)
    }

    init {
        setActiveCameraId(cameraManager.cameraIdList.first())
    }

    /**
     * Sets the preview surface to be used for the camera source.
     */
    fun setPreviewSurface(surface: Surface?) {
        activePreviewSurface = surface

        updateCaptureSession()
    }

    /**
     * Sets the encoder input surface to be used for the camera source.
     */
    fun setEncoderInputSurface(surface: Surface?) {
        activeEncoderInputSurface = surface

        updateCaptureSession()
    }

    /**
     * Returns the active camera ID that is being used for the camera source.
     */
    fun getActiveCameraId(): String? {
        return activeCameraDevice?.id
    }

    /**
     * Sets the active camera ID to be used for the camera source.
     */
    fun setActiveCameraId(cameraId: String) {
        activeCaptureSession?.close()
        activeCameraDevice?.close()

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                Log.i("StreamingService", "Camera opened")
                setCameraDevice(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.i("StreamingService", "Camera disconnected")
                setCameraDevice(null)
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("StreamingService", "Camera error: $error")
            }
        }, handler)
    }

    private fun setCameraDevice(camera: CameraDevice?) {
        activeCameraDevice?.close()
        activeCameraDevice = camera

        updateCaptureSession()
    }

    private fun updateCaptureSession() {
        val camera = activeCameraDevice ?: return
        val previewSurface = activePreviewSurface
        val encoderInputSurface = activeEncoderInputSurface

        if (previewSurface == null && encoderInputSurface == null) {
            // no surfaces have been set, so we can't open the camera
            return
        }

        Log.i("StreamingService", "Opening camera session")

        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            activePreviewSurface?.let { addTarget(it) }
            activeEncoderInputSurface?.let { addTarget(it) }
        }.build()

        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                mutableListOf<OutputConfiguration>().apply {
                    previewSurface?.let { add(OutputConfiguration(it)) }
                    encoderInputSurface?.let { add(OutputConfiguration(it)) }
                },
                Executors.newSingleThreadExecutor(),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.i("StreamingService", "Camera session configured")

                        activeCaptureSession?.close()
                        activeCaptureSession = session

                        session.setRepeatingRequest(request, null, handler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("StreamingService", "Failed to configure camera session")
                    }
                }
            )
        )
    }

    fun close() {
        activeCaptureSession?.close()
        activeCameraDevice?.close()
    }
}