package com.kevmo314.kineticstreamer

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.graphics.drawable.toDrawable

/**
 * Renders a WebView into a SurfaceTexture through a VirtualDisplay + Presentation, which keeps
 * hardware acceleration (and so WebGL) working. The SurfaceTexture is owned by the caller; this
 * class only owns the [Surface] wrapping it. Main thread only.
 */
class WebViewSurfaceCapture(
    private val context: Context,
    private var width: Int,
    private var height: Int,
    private val surfaceTexture: SurfaceTexture,
) {
    companion object {
        private const val TAG = "WebViewSurfaceCapture"
    }

    private var surface: Surface? = Surface(surfaceTexture)
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: WebViewPresentation? = null

    /** Starts capturing [webView]. A no-op if already capturing or after [release]. */
    fun startCapture(webView: WebView): Boolean {
        val target = surface ?: return false
        if (virtualDisplay != null) return true

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.createVirtualDisplay(
            "WebViewOverlay",
            width,
            height,
            context.resources.displayMetrics.densityDpi,
            target,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        if (display == null) {
            Log.e(TAG, "Failed to create VirtualDisplay")
            return false
        }
        virtualDisplay = display
        presentation = WebViewPresentation(display.display, webView).also { it.show() }
        return true
    }

    /** Resizes the capture, including the buffers the WebView renders into. */
    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        surfaceTexture.setDefaultBufferSize(width, height)
        virtualDisplay?.resize(width, height, context.resources.displayMetrics.densityDpi)
    }

    /** Hosts the WebView on the VirtualDisplay. */
    private inner class WebViewPresentation(
        display: Display,
        val webView: WebView,
    ) : Presentation(webView.context, display) {

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.setBackgroundDrawable(0x00000000.toDrawable())

            (webView.parent as? ViewGroup)?.removeView(webView)
            val container = FrameLayout(context)
            container.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            setContentView(container)
        }
    }

    /** Stops capturing. Detaches the WebView first so it can be destroyed safely afterwards. */
    fun release() {
        presentation?.apply {
            (webView.parent as? ViewGroup)?.removeView(webView)
            dismiss()
        }
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
        surface?.release()
        surface = null
    }
}
