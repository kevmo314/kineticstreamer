package com.kevmo314.kineticstreamer

import android.app.Presentation
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.createBitmap

/**
 * Captures WebView content using SurfaceControl API for hardware-accelerated rendering
 * Requires Android 10+ (API 29+), with full support on Android 12+ (API 31+)
 */
class WebViewSurfaceCapture {
    companion object {
        private const val TAG = "WebViewSurfaceCapture"
    }

    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var surfaceControl: SurfaceControl? = null
    private var mirroredSurface: Surface? = null
    private var textureId: Int = 0
    private var surfaceTexture: android.graphics.SurfaceTexture? = null
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebViewPresentation? = null
    private var displayManager: DisplayManager? = null
    private var captureWidth: Int = 0
    private var captureHeight: Int = 0

    /**
     * Initialize the capture system
     */
    fun initialize(width: Int, height: Int, context: Context) {
        Log.i(TAG, "initialize() called with width=$width, height=$height")
        captureWidth = width
        captureHeight = height

        // Create background thread for capture operations
        captureThread = HandlerThread("WebViewCaptureThread").apply {
            start()
        }
        captureHandler = Handler(captureThread!!.looper)

        // Get DisplayManager for VirtualDisplay creation
        displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        // Create ImageReader to receive captured frames
        // Using RGBA_8888 format for compatibility with OpenGL
        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2 // Use 2 buffers for double buffering
        ).apply {
            setOnImageAvailableListener({ reader ->
                handleNewFrame(reader)
            }, captureHandler)
        }

        // Create OpenGL external texture for the captured content
        val textures = IntArray(1)
        android.opengl.GLES20.glGenTextures(1, textures, 0)
        textureId = textures[0]
        Log.i(TAG, "Created OpenGL external texture with ID $textureId")

        // Bind as external texture and set parameters
        android.opengl.GLES20.glBindTexture(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MIN_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_MAG_FILTER, android.opengl.GLES20.GL_LINEAR)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_S, android.opengl.GLES20.GL_CLAMP_TO_EDGE)
        android.opengl.GLES20.glTexParameteri(android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES, android.opengl.GLES20.GL_TEXTURE_WRAP_T, android.opengl.GLES20.GL_CLAMP_TO_EDGE)

        // Create SurfaceTexture from the external texture
        surfaceTexture = android.graphics.SurfaceTexture(textureId)
        surfaceTexture?.setDefaultBufferSize(width, height)

        // Create a Surface from the SurfaceTexture for rendering
        surface = Surface(surfaceTexture)

        Log.i(TAG, "WebViewSurfaceCapture initialized with size ${width}x${height}, SurfaceTexture created with listener")
    }

    /**
     * Start capturing WebView content using VirtualDisplay
     * This maintains hardware acceleration for WebGL support
     */
    fun startCapture(webView: WebView): Boolean {
        val targetSurface = surface
        if (targetSurface == null) {
            Log.e(TAG, "No surface available for VirtualDisplay")
            return false
        }

        if (displayManager == null) {
            Log.e(TAG, "DisplayManager not initialized")
            return false
        }

        // Get display metrics for density
        val metrics = webView.context.resources.displayMetrics
        val densityDpi = metrics.densityDpi

        // Use stored dimensions instead of WebView dimensions which might be 0
        val width = if (webView.width > 0) webView.width else captureWidth
        val height = if (webView.height > 0) webView.height else captureHeight

        Log.i(TAG, "Creating VirtualDisplay with dimensions: ${width}x${height}")

        // Create VirtualDisplay
        virtualDisplay = displayManager?.createVirtualDisplay(
            "WebViewOverlay",           // name
            width,                      // width
            height,                     // height
            densityDpi,                 // density
            targetSurface,              // surface to render to
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )

        if (virtualDisplay == null) {
            Log.e(TAG, "Failed to create VirtualDisplay")
            return false
        }

        // Create and show Presentation with WebView
        virtualDisplay?.display?.let { display ->
            presentation = WebViewPresentation(display, webView)
            presentation?.show()
        }

        return true
    }

    /**
     * Handle new frame from ImageReader
     */
    private fun handleNewFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage()
        if (image != null) {
            // Convert Image to texture
            // This would involve copying the image data to the OpenGL texture
            val buffer = image.planes[0].buffer
            android.opengl.GLES20.glBindTexture(android.opengl.GLES20.GL_TEXTURE_2D, textureId)
            android.opengl.GLES20.glTexImage2D(
                android.opengl.GLES20.GL_TEXTURE_2D,
                0,
                android.opengl.GLES20.GL_RGBA,
                image.width,
                image.height,
                0,
                android.opengl.GLES20.GL_RGBA,
                android.opengl.GLES20.GL_UNSIGNED_BYTE,
                buffer
            )
            image.close()
        }
    }

    /**
     * Get the OpenGL texture ID containing captured content
     */
    fun getTextureId(): Int {
        Log.d(TAG, "getTextureId() returning $textureId")
        return textureId
    }

    /**
     * Get the SurfaceTexture for rendering
     */
    fun getSurfaceTexture(): android.graphics.SurfaceTexture? = surfaceTexture

    /**
     * Inner Presentation class that hosts the WebView
     * This renders to the VirtualDisplay's Surface
     */
    private inner class WebViewPresentation(
        display: Display,
        private val webView: WebView?
    ) : Presentation(webView?.context, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            // Make the Presentation window transparent
            window?.setBackgroundDrawable(0x00000000.toDrawable())

            // Remove WebView from its current parent if any
            (webView?.parent as? ViewGroup)?.removeView(webView)

            // Create a container for the WebView
            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            // Add WebView to container
            webView?.let {
                // Ensure WebView will draw continuously
                it.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                container.addView(it, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }

            // Set the container as content view
            setContentView(container)

            Log.i(TAG, "WebViewPresentation created and WebView attached")
        }
    }

    /**
     * Release resources
     */
    fun release() {
        // Release Presentation and VirtualDisplay
        presentation?.dismiss()
        presentation = null

        virtualDisplay?.release()
        virtualDisplay = null

        // Release surfaces
        surface?.release()
        surface = null

        surfaceTexture?.release()
        surfaceTexture = null

        mirroredSurface?.release()
        mirroredSurface = null

        surfaceControl?.release()
        surfaceControl = null

        // Release ImageReader
        imageReader?.close()
        imageReader = null

        // Delete texture
        if (textureId != 0) {
            val textures = intArrayOf(textureId)
            android.opengl.GLES20.glDeleteTextures(1, textures, 0)
            textureId = 0
        }

        // Stop capture thread
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null

        Log.i(TAG, "WebViewSurfaceCapture released")
    }
}
