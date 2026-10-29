package com.kevmo314.kineticstreamer

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a WebView rendered into a renderer-owned SurfaceTexture for overlaying on video.
 * The WebView is hosted on a VirtualDisplay so hardware acceleration (WebGL) keeps working.
 */
class WebViewOverlay(private val context: Context) {
    companion object {
        private const val TAG = "WebViewOverlay"
    }

    // The WebView, its container and the capture are only touched on the main thread.
    private var webView: WebView? = null
    private var containerView: FrameLayout? = null
    private var surfaceCapture: WebViewSurfaceCapture? = null
    private var renderer: SurfaceTextureRenderer? = null

    @Volatile
    private var overlayTexture: SurfaceTexture? = null

    // Overlay configuration
    private var overlayX: Int = 0
    private var overlayY: Int = 0
    private var overlayWidth: Int = 0
    private var overlayHeight: Int = 0
    private var overlayScale: Float = 1.0f
    private var currentUrl: String? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Initialize the overlay with specified parameters. Any previous WebView is torn down first.
     * Blocks until the WebView is set up on the main thread.
     */
    fun initialize(url: String, x: Int, y: Int, width: Int, height: Int, scale: Float = 1.0f): Boolean {
        Log.i(TAG, "initialize() called with url=$url, x=$x, y=$y, width=$width, height=$height, scale=$scale")
        overlayX = x
        overlayY = y
        overlayWidth = width
        overlayHeight = height
        overlayScale = scale
        currentUrl = url

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return initializeOnMainThread(url)
        }

        val success = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        mainHandler.post {
            success.set(initializeOnMainThread(url))
            latch.countDown()
        }
        return try {
            latch.await(5, TimeUnit.SECONDS) && success.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun initializeOnMainThread(url: String): Boolean {
        teardownOnMainThread()
        val gl = renderer
        try {
            val container = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(overlayWidth, overlayHeight)
            }
            val view = createWebView()
            container.addView(view)
            containerView = container
            webView = view

            view.measure(
                View.MeasureSpec.makeMeasureSpec(overlayWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(overlayHeight, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, overlayWidth, overlayHeight)

            // The texture must be created on the renderer's GL thread, which also owns it.
            val texture = checkNotNull(gl) { "No renderer" }.createOverlayTexture(overlayWidth, overlayHeight)
            overlayTexture = texture
            surfaceCapture = WebViewSurfaceCapture(context, overlayWidth, overlayHeight, texture)
            gl.setOverlayBounds(overlayX, overlayY, overlayWidth, overlayHeight, 1920, 1080)

            view.loadUrl(url)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebView overlay", e)
            teardownOnMainThread()
            return false
        }
    }

    private fun createWebView(): WebView = WebView(context).apply {
        layoutParams = FrameLayout.LayoutParams(overlayWidth, overlayHeight)
        // Hardware layer is critical for WebGL
        setLayerType(View.LAYER_TYPE_HARDWARE, null)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            displayZoomControls = false
            builtInZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT

            // Overlays are remote pages; never expose local files or content providers to them.
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
        }

        setInitialScale((overlayScale * 100).toInt())
        setBackgroundColor(0x00000000)

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                Log.i(TAG, "WebView page loaded: $url")
                surfaceCapture?.startCapture(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                Log.e(TAG, "WebView error: ${error.errorCode} - ${error.description} at ${request.url}")
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val logMessage = "JS Console: ${consoleMessage.message()} [${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}]"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e(TAG, logMessage)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, logMessage)
                    else -> Log.d(TAG, logMessage)
                }
                return true
            }
        }
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

        val w = overlayWidth
        val h = overlayHeight
        if (sizeChanged) {
            mainHandler.post {
                containerView?.layoutParams = FrameLayout.LayoutParams(w, h)
                webView?.layoutParams = FrameLayout.LayoutParams(w, h)
                webView?.requestLayout()
            }
        }

        scale?.let {
            if (overlayScale != it) {
                overlayScale = it
                mainHandler.post {
                    webView?.setInitialScale((it * 100).toInt())
                    webView?.reload()
                }
            }
        }

        url?.let { newUrl ->
            if (newUrl != currentUrl) {
                currentUrl = newUrl
                mainHandler.post { webView?.loadUrl(newUrl) }
            }
        }

        renderer?.setOverlayBounds(overlayX, overlayY, overlayWidth, overlayHeight, 1920, 1080)
    }

    /**
     * Set the renderer that owns the overlay texture
     */
    fun setRenderer(renderer: SurfaceTextureRenderer?) {
        this.renderer = renderer
    }

    /** The renderer's GL texture id for the overlay, or 0 if there is none. */
    fun getTextureId(): Int = if (overlayTexture != null) renderer?.overlayTextureId ?: 0 else 0

    /** The overlay SurfaceTexture, or null if the overlay is not initialized or was released. */
    fun getSurfaceTexture(): SurfaceTexture? = overlayTexture

    /**
     * Reload the WebView content
     */
    fun reload() {
        mainHandler.post { webView?.reload() }
    }

    /**
     * Tear down the WebView and capture, then have the renderer release the overlay texture.
     * Must run on the main thread. Safe to call repeatedly.
     */
    private fun teardownOnMainThread() {
        surfaceCapture?.release()
        surfaceCapture = null

        containerView?.removeAllViews()
        containerView = null

        webView?.apply {
            stopLoading()
            destroy()
        }
        webView = null

        // The capture's Surface is gone, so the renderer can now release the SurfaceTexture.
        if (overlayTexture != null) {
            overlayTexture = null
            renderer?.removeOverlay()
        }
    }

    /**
     * Release all resources. Safe to call from any thread.
     */
    fun release() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            teardownOnMainThread()
        } else {
            mainHandler.post { teardownOnMainThread() }
        }
        Log.i(TAG, "WebViewOverlay released")
    }
}
