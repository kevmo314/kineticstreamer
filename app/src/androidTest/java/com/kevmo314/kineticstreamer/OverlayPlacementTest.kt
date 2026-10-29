package com.kevmo314.kineticstreamer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Renders a solid video plus a four-colour overlay through the real renderer into an
 * ImageReader and checks where the overlay pixels land.
 */
@RunWith(AndroidJUnit4::class)
class OverlayPlacementTest {
    private companion object {
        const val W = 160
        const val H = 90
        const val OW = 40
        const val OH = 20
        const val BACKGROUND = 0xFF0000FF.toInt() // blue video
        val TL = Color.RED
        val TR = Color.GREEN
        val BL = Color.YELLOW
        val BR = Color.WHITE
    }

    private lateinit var renderer: SurfaceTextureRenderer
    private lateinit var reader: ImageReader
    private val producing = AtomicBoolean(true)
    private lateinit var producer: Thread

    @Before
    fun setUp() {
        renderer = SurfaceTextureRenderer()
        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)
        renderer.addOutputSurface(reader.surface)

        val video = renderer.createVideoSurfaceTexture(W, H)
        renderer.attachSurfaceTexture(video)
        val videoSurface = Surface(video)
        producer = thread {
            while (producing.get()) {
                runCatching {
                    val canvas = videoSurface.lockCanvas(null)
                    canvas.drawColor(BACKGROUND)
                    videoSurface.unlockCanvasAndPost(canvas)
                }
                Thread.sleep(16)
            }
            videoSurface.release()
        }
    }

    @After
    fun tearDown() {
        producing.set(false)
        producer.join(5000)
        renderer.removeOutputSurface(reader.surface)
        renderer.release()
        reader.close()
    }

    private fun Canvas.drawQuadrants() {
        val paint = Paint()
        for ((color, x, y) in listOf(
            Triple(TL, 0, 0), Triple(TR, OW / 2, 0), Triple(BL, 0, OH / 2), Triple(BR, OW / 2, OH / 2),
        )) {
            paint.color = color
            drawRect(x.toFloat(), y.toFloat(), (x + OW / 2).toFloat(), (y + OH / 2).toFloat(), paint)
        }
    }

    /** Places the overlay at [x],[y] on the W x H output and returns the first frame that shows it. */
    private fun renderOverlayAt(x: Int, y: Int): IntArray {
        val texture = renderer.createOverlayTexture(OW, OH)
        val surface = Surface(texture)
        val canvas = surface.lockCanvas(null)
        canvas.drawQuadrants()
        surface.unlockCanvasAndPost(canvas)
        renderer.setOverlayBounds(x, y, OW, OH, W, H)

        val deadline = System.currentTimeMillis() + 10_000
        try {
            while (System.currentTimeMillis() < deadline) {
                latestFrame()?.let { if (it.any { p -> p != BACKGROUND }) return it }
                Thread.sleep(30)
            }
        } finally {
            surface.release()
        }
        fail("overlay never appeared")
        error("unreachable")
    }

    private fun latestFrame(): IntArray? {
        val image = reader.acquireLatestImage() ?: return null
        image.use {
            val plane = it.planes[0]
            val buf = plane.buffer
            val out = IntArray(W * H)
            for (row in 0 until H) for (col in 0 until W) {
                val o = row * plane.rowStride + col * 4
                out[row * W + col] = Color.argb(
                    buf.get(o + 3).toInt() and 0xFF, buf.get(o).toInt() and 0xFF,
                    buf.get(o + 1).toInt() and 0xFF, buf.get(o + 2).toInt() and 0xFF
                )
            }
            return out
        }
    }

    private fun IntArray.px(x: Int, y: Int) = this[y * W + x]

    /** Samples the middle of each overlay quadrant and a pixel just outside the overlay on each side. */
    private fun assertPlacedAt(x: Int, y: Int) {
        val f = renderOverlayAt(x, y)
        assertEquals("TL", TL, f.px(x + OW / 4, y + OH / 4))
        assertEquals("TR", TR, f.px(x + 3 * OW / 4, y + OH / 4))
        assertEquals("BL", BL, f.px(x + OW / 4, y + 3 * OH / 4))
        assertEquals("BR", BR, f.px(x + 3 * OW / 4, y + 3 * OH / 4))
        if (x > 0) assertEquals("left of overlay", BACKGROUND, f.px(x - 3, y + OH / 2))
        if (x + OW < W) assertEquals("right of overlay", BACKGROUND, f.px(x + OW + 3, y + OH / 2))
        if (y > 0) assertEquals("above overlay", BACKGROUND, f.px(x + OW / 2, y - 3))
        if (y + OH < H) assertEquals("below overlay", BACKGROUND, f.px(x + OW / 2, y + OH + 3))
    }

    @Test
    fun overlayAtTopLeft() = assertPlacedAt(0, 0)

    @Test
    fun overlayLeftOfCentre() = assertPlacedAt(20, 50)

    @Test
    fun overlayAtBottomRight() = assertPlacedAt(W - OW, H - OH)
}
