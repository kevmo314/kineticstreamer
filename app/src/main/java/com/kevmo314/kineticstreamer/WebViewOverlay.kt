package com.kevmo314.kineticstreamer

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Manages a WebView that renders to an OpenGL texture for overlaying on video
 * Uses SurfaceControl API for hardware-accelerated WebGL support (Android 10+)
 * Requires hardware acceleration - no fallback to software rendering
 */
class WebViewOverlay(private val context: Context) {
    companion object {
        private const val TAG = "WebViewOverlay"
    }

    private var webView: WebView? = null
    private var containerView: FrameLayout? = null
    private var windowManager: WindowManager? = null
    private var surfaceCapture: WebViewSurfaceCapture? = null
    private var textureView: android.view.TextureView? = null
    private var renderer: SurfaceTextureRenderer? = null

    // Overlay configuration
    private var overlayX: Int = 0
    private var overlayY: Int = 0
    private var overlayWidth: Int = 0
    private var overlayHeight: Int = 0
    private var overlayScale: Float = 1.0f
    private var currentUrl: String? = null

    // Handler for WebView operations (must be on UI thread)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initialize the overlay with specified parameters
     * Requires hardware acceleration - will fail if not available
     */
    fun initialize(url: String, x: Int, y: Int, width: Int, height: Int, scale: Float = 1.0f): Boolean {
        Log.i(TAG, "initialize() called with url=$url, x=$x, y=$y, width=$width, height=$height, scale=$scale")
        overlayX = x
        overlayY = y
        overlayWidth = width
        overlayHeight = height
        overlayScale = scale
        currentUrl = url

        // Initialize with hardware acceleration (required)
        return initializeWithSurfaceControl(url)
    }

    /**
     * Initialize using SurfaceControl for hardware acceleration (Android 10+)
     */
    private fun initializeWithSurfaceControl(url: String): Boolean {
        var success = false
        val latch = java.util.concurrent.CountDownLatch(1)

        mainHandler.post {
            try {
                // Create container for WebView
                containerView = FrameLayout(context).apply {
                    layoutParams = FrameLayout.LayoutParams(overlayWidth, overlayHeight)
                }

                // Create WebView with hardware acceleration
                webView = WebView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(overlayWidth, overlayHeight)

                    // Force hardware acceleration (critical for WebGL)
                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

                    // Configure for WebGL support
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        setSupportZoom(false)
                        displayZoomControls = false
                        builtInZoomControls = false

                        // Enable all features needed for WebGL
                        allowFileAccess = true
                        allowContentAccess = true
                        allowFileAccessFromFileURLs = true
                        allowUniversalAccessFromFileURLs = true
                        javaScriptCanOpenWindowsAutomatically = true

                        // Mixed content
                        mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

                        // Performance
                        setRenderPriority(android.webkit.WebSettings.RenderPriority.HIGH)
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = android.webkit.WebSettings.LOAD_DEFAULT

                        // Enable hardware acceleration settings
                        setGeolocationEnabled(false)
                    }

                    // Apply scale
                    setInitialScale((overlayScale * 100).toInt())

                    // Transparent background
                    setBackgroundColor(0x00000000)

                    // WebView client
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            Log.i(TAG, "WebView page loaded: $url")

                            view?.let { view -> surfaceCapture?.startCapture(view) }
                        }

                        override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                            Log.e(TAG, "WebView error: $errorCode - $description at $failingUrl")
                        }
                    }

                    // Chrome client for console logging
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                            consoleMessage?.let { msg ->
                                val logMessage = "JS Console: ${msg.message()} [${msg.sourceId()}:${msg.lineNumber()}]"
                                when (msg.messageLevel()) {
                                    android.webkit.ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, logMessage)
                                    android.webkit.ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, logMessage)
                                    else -> Log.d(TAG, logMessage)
                                }
                            }
                            return true
                        }
                    }
                }

                // Add WebView to container (will be moved to VirtualDisplay later)
                containerView?.addView(webView)

                // Force WebView to measure and layout with explicit dimensions
                webView?.measure(
                    android.view.View.MeasureSpec.makeMeasureSpec(overlayWidth, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(overlayHeight, android.view.View.MeasureSpec.EXACTLY)
                )
                webView?.layout(0, 0, overlayWidth, overlayHeight)

                // Note: WebView will be rendered in VirtualDisplay Presentation
                // No WindowManager overlay needed for VirtualDisplay approach

                // Load URL after view is attached
                webView?.loadUrl(url)

                // Initialize surface capture with context
                surfaceCapture = WebViewSurfaceCapture()
                surfaceCapture?.initialize(overlayWidth, overlayHeight, context)
                Log.i(TAG, "Surface capture initialized, textureId=${surfaceCapture?.getTextureId()}")

                success = true
                Log.i(TAG, "Initialized with SurfaceControl for hardware acceleration")

                // Ensure the SurfaceTexture is available and update the renderer
                updateRendererOverlay()

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize with SurfaceControl", e)
                success = false
                cleanup()
            } finally {
                latch.countDown()
            }
        }

        // Wait for initialization to complete
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Initialization timeout", e)
            return false
        }

        return success
    }

    /**
     * Update the WebView content and/or position
     */
    fun update(url: String? = null, x: Int? = null, y: Int? = null, width: Int? = null, height: Int? = null, scale: Float? = null) {
        x?.let { overlayX = it }
        y?.let { overlayY = it }

        var sizeChanged = false
        width?.let {
            if (overlayWidth != it) {
                overlayWidth = it
                sizeChanged = true
            }
        }
        height?.let {
            if (overlayHeight != it) {
                overlayHeight = it
                sizeChanged = true
            }
        }

        if (sizeChanged) {
            // Update window size for SurfaceControl approach
            mainHandler.post {
                containerView?.layoutParams = FrameLayout.LayoutParams(overlayWidth, overlayHeight)
                webView?.layoutParams = FrameLayout.LayoutParams(overlayWidth, overlayHeight)
                webView?.requestLayout()

                // Update window manager params - create new params as width/height are val
                val currentParams = containerView?.layoutParams as? WindowManager.LayoutParams
                if (currentParams != null && containerView != null) {
                    val newParams = WindowManager.LayoutParams(
                        overlayWidth,
                        overlayHeight,
                        currentParams.type,
                        currentParams.flags,
                        currentParams.format
                    )
                    newParams.gravity = currentParams.gravity
                    newParams.x = currentParams.x
                    newParams.y = currentParams.y
                    windowManager?.updateViewLayout(containerView, newParams)
                }
            }
        }

        scale?.let {
            if (overlayScale != it) {
                overlayScale = it
                mainHandler.post {
                    webView?.setInitialScale((overlayScale * 100).toInt())
                    webView?.reload()
                }
            }
        }

        url?.let { newUrl ->
            if (newUrl != currentUrl) {
                currentUrl = newUrl
                mainHandler.post {
                    webView?.loadUrl(newUrl)
                }
            }
        }

        Log.i(TAG, "WebViewOverlay updated - Position: ($overlayX, $overlayY), Size: ${overlayWidth}x${overlayHeight}")
    }

    /**
     * Set the renderer reference for updating overlay
     */
    fun setRenderer(renderer: SurfaceTextureRenderer?) {
        this.renderer = renderer
    }

    /**
     * Update the renderer with the current overlay configuration
     */
    private fun updateRendererOverlay() {
        val textureId = surfaceCapture?.getTextureId() ?: 0
        val surfaceTexture = surfaceCapture?.getSurfaceTexture()

        Log.i(TAG, "Updating renderer overlay: textureId=$textureId, surfaceTexture=$surfaceTexture")

        // Set up frame available listener for overlay texture
        surfaceTexture?.setOnFrameAvailableListener {
            // Notify renderer that a new overlay frame is available
            renderer?.setOverlayFrameAvailable()
        }

        renderer?.setOverlay(
            textureId,
            surfaceTexture,
            overlayX,
            overlayY,
            overlayWidth,
            overlayHeight,
            1920,  // screen width
            1080   // screen height
        )
    }

    /**
     * Get the texture ID for rendering
     */
    fun getTextureId(): Int {
        val id = surfaceCapture?.getTextureId() ?: 0
        Log.d(TAG, "getTextureId() returning $id")
        return id
    }

    /**
     * Get the SurfaceTexture - returns null as we use direct texture rendering
     */
    fun getSurfaceTexture(): SurfaceTexture? {
        // Return the SurfaceTexture from the capture if available
        val surfaceTexture = surfaceCapture?.getSurfaceTexture()
        Log.d(TAG, "getSurfaceTexture() returning $surfaceTexture")
        return surfaceTexture
    }

    /**
     * Refresh the WebView rendering to texture
     */
    fun refreshRender() {
        webView?.postInvalidateOnAnimation()
    }

    /**
     * Clean up resources
     */
    private fun cleanup() {
        surfaceCapture?.release()
        surfaceCapture = null

        mainHandler.post {
            // Clean up container and WebView
            containerView?.removeAllViews()
            containerView = null

            webView?.apply {
                stopLoading()
                clearHistory()
                clearCache(true)
                destroy()
            }
            webView = null
        }
    }

    /**
     * Release all resources
     */
    fun release() {
        cleanup()
        Log.i(TAG, "WebViewOverlay released")
    }
}
