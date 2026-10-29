package com.kevmo314.kineticstreamer

import android.hardware.display.DisplayManager
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The overlay SurfaceTexture/texture are renderer-owned and must follow the overlay's lifecycle. */
@RunWith(AndroidJUnit4::class)
class WebViewOverlayLifecycleTest {
    companion object {
        /** The first WebView in a process is very slow to create on an emulator; keep that out of the timed paths. */
        @BeforeClass
        @JvmStatic
        fun warmUpWebView() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.runOnMainSync { WebView(instrumentation.targetContext).destroy() }
        }
    }

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var renderer: SurfaceTextureRenderer
    private lateinit var overlay: WebViewOverlay

    @Before
    fun setUp() {
        renderer = SurfaceTextureRenderer()
        overlay = WebViewOverlay(context).also { it.setRenderer(renderer) }
    }

    @After
    fun tearDown() {
        overlay.release()
        drainMainAndGl()
        renderer.release()
    }

    /** Waits for posted main-thread teardown and queued GL work. */
    private fun drainMainAndGl() {
        instrumentation.waitForIdleSync()
        renderer.createOverlayTextureBarrier()
    }

    private fun SurfaceTextureRenderer.createOverlayTextureBarrier() {
        val latch = java.util.concurrent.CountDownLatch(1)
        glHandler.post { latch.countDown() }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    @Test
    fun textureIdIsTheRealRendererTexture() {
        assertTrue(overlay.initialize("about:blank", 0, 0, 320, 180))
        // Regression: this used to be clobbered to -1, which then replaced the renderer's id.
        assertTrue(overlay.getTextureId() > 0)
        assertEquals(renderer.overlayTextureId, overlay.getTextureId())
        assertNotNull(overlay.getSurfaceTexture())
    }

    @Test
    fun rendererSetOverlayWithOverlayIdsDoesNotClobberTexture() {
        assertTrue(overlay.initialize("about:blank", 0, 0, 320, 180))
        val id = renderer.overlayTextureId
        // What StreamingService does after initialize/update.
        renderer.setOverlay(overlay.getTextureId(), overlay.getSurfaceTexture(), 0, 0, 320, 180, 1920, 1080)
        drainMainAndGl()
        assertTrue(id > 0)
        assertEquals(id, renderer.overlayTextureId)
    }

    @Test
    fun releaseDropsTheTextureAndIsIdempotent() {
        assertTrue(overlay.initialize("about:blank", 0, 0, 320, 180))
        val texture = overlay.getSurfaceTexture()!!

        overlay.release()
        overlay.release()
        drainMainAndGl()

        assertNull(overlay.getSurfaceTexture())
        assertEquals(0, overlay.getTextureId())
        assertEquals(0, renderer.overlayTextureId)
        // The renderer released it (not the capture), so a released texture must reject use.
        val rejected = try {
            texture.updateTexImage()
            false
        } catch (e: RuntimeException) {
            true
        }
        assertTrue(rejected)
    }

    @Test
    fun reinitializeReplacesTheOverlayWithoutTearingDownTheNewOne() {
        assertTrue(overlay.initialize("about:blank", 0, 0, 320, 180))
        val first = overlay.getSurfaceTexture()
        assertTrue(overlay.initialize("about:blank", 10, 10, 160, 90))
        drainMainAndGl()

        // Teardown of the first WebView must not have destroyed the second.
        assertNotNull(overlay.getSurfaceTexture())
        assertNotEquals(first, overlay.getSurfaceTexture())
        assertTrue(overlay.getTextureId() > 0)
        assertEquals(renderer.overlayTextureId, overlay.getTextureId())
    }

    @Test
    fun initializeFailsWithoutRenderer() {
        val orphan = WebViewOverlay(context)
        assertTrue(!orphan.initialize("about:blank", 0, 0, 320, 180))
        assertNull(orphan.getSurfaceTexture())
        orphan.release()
    }

    private fun overlayDisplays() = (context.getSystemService(DisplayManager::class.java))
        .displays.filter { it.name == "WebViewOverlay" }

    @Test
    fun captureStartsAfterPageLoadAndReleaseTearsItDown() {
        assertTrue(overlay.initialize("about:blank", 0, 0, 320, 180))
        val deadline = System.currentTimeMillis() + 60_000
        while (overlayDisplays().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertEquals(1, overlayDisplays().size)

        overlay.update(width = 160, height = 90)
        instrumentation.waitForIdleSync()
        assertEquals(1, overlayDisplays().size)

        overlay.release()
        drainMainAndGl()
        val gone = System.currentTimeMillis() + 10_000
        while (overlayDisplays().isNotEmpty() && System.currentTimeMillis() < gone) Thread.sleep(100)
        assertEquals(0, overlayDisplays().size)
    }
}
