package com.kevmo314.kineticstreamer

import android.Manifest
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class VideoPipelineInstrumentedTest {
    @Before
    fun grantCamera() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Adds and removes ImageReader outputs from another thread while frames render. */
    @Test
    fun outputSurfacesCanChurnWhileRendering() {
        val renderer = SurfaceTextureRenderer()
        val source = renderer.createVideoSurfaceTexture(640, 480)
        val sourceSurface = Surface(source)
        renderer.attachSurfaceTexture(source)

        val failure = AtomicReference<Throwable?>()
        val producing = AtomicBoolean(true)
        val producer = thread {
            try {
                while (producing.get()) {
                    val canvas = sourceSurface.lockCanvas(null)
                    canvas.drawColor(0xFF336699.toInt())
                    sourceSurface.unlockCanvasAndPost(canvas)
                    Thread.sleep(8)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val frames = AtomicInteger()
        val readerThread = HandlerThread("readers").apply { start() }
        val readerHandler = Handler(readerThread.looper)
        val churn = thread {
            try {
                repeat(40) {
                    val reader = ImageReader.newInstance(320, 240, PixelFormat.RGBA_8888, 3)
                    reader.setOnImageAvailableListener({ r ->
                        r.acquireLatestImage()?.close()
                        frames.incrementAndGet()
                    }, readerHandler)
                    renderer.addOutputSurface(reader.surface)
                    Thread.sleep(30)
                    renderer.removeOutputSurface(reader.surface)
                    reader.close()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        churn.join(60_000)
        producing.set(false)
        producer.join(5_000)

        renderer.detachSurfaceTexture(source)
        renderer.release()
        sourceSurface.release()
        source.release()
        readerThread.quitSafely()

        failure.get()?.let { throw AssertionError("Render/churn thread failed", it) }
        assertTrue("expected rendered frames, got ${frames.get()}", frames.get() > 0)
    }

    @Test
    fun rendererReleaseIsIdempotentAndLateCallsAreIgnored() {
        val renderer = SurfaceTextureRenderer()
        renderer.release()
        renderer.release()
        renderer.removeOutputSurface(null)
        renderer.setOverlayBounds(0, 0, 10, 10, 100, 100)
    }

    @Test
    fun cameraOpenCloseCycles() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull()
        assumeTrue("no camera", cameraId != null)

        val handlerThread = HandlerThread("camera").apply { start() }
        val handler = Handler(handlerThread.looper)
        val renderer = SurfaceTextureRenderer()
        try {
            repeat(5) {
                val texture = renderer.createVideoSurfaceTexture(640, 480)
                val surface = Surface(texture)
                val result = runBlocking {
                    withTimeout(20_000) { openCamera(cameraManager, surface, handler, cameraId!!) }
                }
                assertNotNull("camera should open", result.device)
                result.session?.close()
                result.device?.close()
                surface.release()
                texture.release()
            }
        } finally {
            renderer.release()
            handlerThread.quitSafely()
        }
    }

    @Test
    fun videoSourceFlowOpenCloseCycles() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = cameraManager.cameraIdList.firstOrNull()
        assumeTrue("no camera", cameraId != null)

        val renderer = SurfaceTextureRenderer()
        val sink = ImageReader.newInstance(640, 480, PixelFormat.RGBA_8888, 3)
        val readerThread = HandlerThread("sink").apply { start() }
        val frames = AtomicInteger()
        sink.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            frames.incrementAndGet()
        }, Handler(readerThread.looper))
        renderer.addOutputSurface(sink.surface)
        try {
            repeat(5) {
                // first() cancels the collector, which tears down camera, surfaces and texture
                runBlocking {
                    withTimeout(30_000) {
                        VideoSource(context, VideoSourceDevice.Camera(cameraId!!), renderer).first()
                    }
                }
            }
        } finally {
            renderer.release()
            sink.close()
            readerThread.quitSafely()
        }
    }
}
