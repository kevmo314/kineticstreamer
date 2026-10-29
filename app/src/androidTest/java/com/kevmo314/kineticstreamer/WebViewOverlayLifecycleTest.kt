package com.kevmo314.kineticstreamer

import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The overlay SurfaceTexture/texture are renderer-owned and must follow the overlay's lifecycle. */
@RunWith(AndroidJUnit4::class)
class WebViewOverlayLifecycleTest {
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

    @Test
    fun videoSourceDeviceSurvivesParcelRoundTrip() {
        for (device in listOf(VideoSourceDevice.Camera("0"), VideoSourceDevice.RtmpServer(1936))) {
            val parcel = Parcel.obtain()
            try {
                parcel.writeParcelable(device, 0)
                parcel.setDataPosition(0)
                assertEquals(device, parcel.readParcelable(VideoSourceDevice::class.java.classLoader, VideoSourceDevice::class.java))
            } finally {
                parcel.recycle()
            }
        }
    }
}
