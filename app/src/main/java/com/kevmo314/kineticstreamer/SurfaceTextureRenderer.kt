package com.kevmo314.kineticstreamer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Efficient OpenGL renderer that takes a SurfaceTexture and renders it to multiple output surfaces
 * Optimized for dynamic surface management without decoder restarts
 */
class SurfaceTextureRenderer {
    companion object {
        private const val TAG = "SurfaceTextureRenderer"
        private const val GL_TIMEOUT_MS = 3000L

        // Vertex shader - simple fullscreen quad
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uTextureMatrix;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTextureMatrix * vec4(aTextureCoord, 0.0, 1.0)).xy;
            }
        """

        // Fragment shader - external texture (for SurfaceTexture/camera) with overlay support
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uTexture;
            uniform samplerExternalOES uOverlayTexture;
            uniform int uHasOverlay;
            uniform int uRotate180;
            uniform float uPaddingOffset; // Padding offset for rotation (precalculated from integers)
            uniform vec4 uOverlayBounds; // x, y, width, height in normalized coordinates
            uniform mat4 uOverlayTextureMatrix;
            void main() {
                // Apply 180 rotation only to video texture sampling
                vec2 videoCoord = vTextureCoord;
                if (uRotate180 == 1) {
                    // When rotating, shift Y to avoid sampling from padding area
                    // The padding is at the bottom of the buffer, which becomes top after flip
                    videoCoord = vec2(1.0 - vTextureCoord.x, 1.0 - vTextureCoord.y - uPaddingOffset);
                }
                vec4 videoColor = texture2D(uTexture, videoCoord);

                if (uHasOverlay == 1) {
                    // Use original texture coordinates for overlay positioning (not rotated)
                    vec2 screenCoord = vTextureCoord;

                    // Check if current pixel is within overlay bounds
                    vec2 overlayPos = vec2(uOverlayBounds.x, uOverlayBounds.y);
                    vec2 overlaySize = vec2(uOverlayBounds.z, uOverlayBounds.w);

                    if (screenCoord.x >= overlayPos.x && screenCoord.x <= overlayPos.x + overlaySize.x &&
                        screenCoord.y >= overlayPos.y && screenCoord.y <= overlayPos.y + overlaySize.y) {

                        // Calculate normalized UV coordinates within the overlay [0,1]
                        vec2 overlayUV = (screenCoord - overlayPos) / overlaySize;

                        // Flip overlay vertically
                        overlayUV.y = 1.0 - overlayUV.y;

                        // Apply overlay texture matrix to correct orientation
                        vec4 transformedUV = uOverlayTextureMatrix * vec4(overlayUV, 0.0, 1.0);

                        // Sample the overlay texture
                        vec4 overlayColor = texture2D(uOverlayTexture, transformedUV.xy);

                        // Alpha blend overlay on top of video
                        gl_FragColor = mix(videoColor, overlayColor, overlayColor.a);
                    } else {
                        gl_FragColor = videoColor;
                    }
                } else {
                    gl_FragColor = videoColor;
                }
            }
        """

        // Fullscreen quad vertices
        private val QUAD_VERTICES = floatArrayOf(
            -1.0f, -1.0f,  // Bottom left
             1.0f, -1.0f,  // Bottom right
            -1.0f,  1.0f,  // Top left
             1.0f,  1.0f   // Top right
        )

        // Standard texture coordinates - using identity matrix with Y-flip for transform
        private val TEXTURE_COORDS = floatArrayOf(
            0.0f, 0.0f,  // Bottom left
            1.0f, 0.0f,  // Bottom right
            0.0f, 1.0f,  // Top left
            1.0f, 1.0f   // Top right
        )
    }

    // Dedicated GL thread for rendering. All EGL/GL state below is confined to this thread.
    private val glThread = HandlerThread("GLRenderer").apply { start() }
    val glHandler = Handler(glThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null
    // 1x1 surface that stays current whenever no output is being drawn, so texture updates
    // and output surface teardown always have a valid context.
    private var idleSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var positionHandle = 0
    private var textureHandle = 0
    private var textureMatrixHandle = 0
    private var textureUniformHandle = 0
    private var overlayTextureUniformHandle = 0
    private var hasOverlayUniformHandle = 0
    private var overlayBoundsUniformHandle = 0
    private var overlayTextureMatrixHandle = 0
    private var rotate180UniformHandle = 0
    private var paddingOffsetUniformHandle = 0

    private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(QUAD_VERTICES)
        .also { it.position(0) }
    private val textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .put(TEXTURE_COORDS)
        .also { it.position(0) }
    private val textureMatrix = FloatArray(16)
    private val dims = IntArray(1) // scratch for eglQuerySurface

    private class Output(val surface: Surface, val eglSurface: EGLSurface)

    // Encoder outputs are kept at the front so they are drawn and swapped before the preview.
    private val outputs = ArrayList<Output>()

    // Overlay support
    private var overlayTexture: SurfaceTexture? = null
    private var overlayTextureId: Int = 0
    private val overlayBounds = FloatArray(4) // x, y, width, height in normalized coords [0,1]
    private val overlayTextureMatrix = FloatArray(16)
    private var hasOverlay = false
    private var paddingOffset: Float = 0f

    @Volatile
    private var overlayFrameAvailable = false

    // 180° rotation support for upside-down camera mounting
    @Volatile
    private var rotate180 = false

    // Added to SurfaceTexture timestamps to bring them onto CLOCK_MONOTONIC (see cameraTimestampOffsetNs)
    @Volatile
    var timestampOffsetNanos: Long = 0

    // Stream start time for wall-clock timestamp synchronization with audio
    @Volatile
    var streamStartTimeNanos: Long = 0

    init {
        runOnGl {
            setupEGL()
            setupShaders()
        }
        Log.i(TAG, "SurfaceTextureRenderer initialized on GL thread")
    }

    /**
     * Run [block] on the GL thread and wait for it. Waits are bounded because the GL thread can
     * be stuck swapping into a stalled encoder surface.
     */
    private fun runOnGl(block: () -> Unit) {
        if (Looper.myLooper() == glHandler.looper) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Throwable? = null
        val posted = glHandler.post {
            try {
                block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return // renderer already released
        if (!latch.await(GL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.e(TAG, "Timed out waiting for GL thread")
            return
        }
        error?.let { throw it }
    }

    private fun setupEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL display")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }

        // EGL_RECORDABLE_ANDROID is required for MediaCodec input surfaces on some drivers
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            throw RuntimeException("Unable to choose EGL config")
        }
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            eglConfig,
            EGL14.EGL_NO_CONTEXT,
            contextAttribs,
            0
        )

        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("Failed to create EGL context")
        }

        idleSurface = EGL14.eglCreatePbufferSurface(
            eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0
        )
        makeIdleCurrent()
    }

    private fun makeIdleCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, idleSurface, idleSurface, eglContext)
    }

    private fun setupShaders() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Failed to link shader program: $error")
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        textureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTextureMatrix")
        textureUniformHandle = GLES20.glGetUniformLocation(program, "uTexture")
        overlayTextureUniformHandle = GLES20.glGetUniformLocation(program, "uOverlayTexture")
        hasOverlayUniformHandle = GLES20.glGetUniformLocation(program, "uHasOverlay")
        overlayBoundsUniformHandle = GLES20.glGetUniformLocation(program, "uOverlayBounds")
        overlayTextureMatrixHandle = GLES20.glGetUniformLocation(program, "uOverlayTextureMatrix")
        rotate180UniformHandle = GLES20.glGetUniformLocation(program, "uRotate180")
        paddingOffsetUniformHandle = GLES20.glGetUniformLocation(program, "uPaddingOffset")

        Log.i(TAG, "OpenGL shaders compiled successfully")
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Failed to compile shader: $error")
        }

        return shader
    }

    /**
     * Create the camera/decoder-facing SurfaceTexture. The texture is generated on the GL
     * thread so it belongs to the renderer's context (the id is freed with the context).
     */
    fun createVideoSurfaceTexture(width: Int, height: Int): SurfaceTexture {
        var result: SurfaceTexture? = null
        runOnGl {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
            // NEAREST filtering avoids bilinear artifacts at texture edges
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            result = SurfaceTexture(ids[0]).apply { setDefaultBufferSize(width, height) }
        }
        return checkNotNull(result) { "Renderer is not running" }
    }

    /**
     * Add a surface as a render target. Blocks until the EGL surface exists. [encoder] outputs
     * are drawn before other outputs so a slow preview cannot delay the encoder.
     */
    fun addOutputSurface(surface: Surface?, encoder: Boolean = false) {
        if (surface == null) return
        runOnGl {
            if (outputs.any { it.surface == surface }) {
                Log.w(TAG, "Surface already added")
                return@runOnGl
            }
            if (!surface.isValid) {
                Log.w(TAG, "Surface is not valid, skipping")
                return@runOnGl
            }

            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "Failed to create EGL surface, error: ${EGL14.eglGetError()}")
                return@runOnGl
            }

            // Never block the GL thread (and so the encoder) on the display's vsync
            if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                EGL14.eglSwapInterval(eglDisplay, 0)
            }
            makeIdleCurrent()

            outputs.add(if (encoder) 0 else outputs.size, Output(surface, eglSurface))
            Log.i(TAG, "Added output surface, total: ${outputs.size}")
        }
    }

    /**
     * Remove a surface from render targets. When this returns the renderer no longer touches it.
     */
    fun removeOutputSurface(surface: Surface?) {
        if (surface == null) return
        runOnGl {
            val index = outputs.indexOfFirst { it.surface == surface }
            if (index >= 0) {
                destroyOutput(outputs.removeAt(index))
                Log.i(TAG, "Removed output surface, remaining: ${outputs.size}")
            }
        }
    }

    private fun destroyOutput(output: Output) {
        makeIdleCurrent() // the surface may still be current
        EGL14.eglDestroySurface(eglDisplay, output.eglSurface)
    }

    /**
     * Create an overlay texture on the GL thread and return its SurfaceTexture.
     * This must be called to get a valid texture for overlay rendering.
     */
    fun createOverlayTexture(width: Int, height: Int): SurfaceTexture {
        var result: SurfaceTexture? = null
        runOnGl {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            overlayTextureId = textures[0]

            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(overlayTextureId)
            st.setDefaultBufferSize(width, height)
            st.setOnFrameAvailableListener { overlayFrameAvailable = true }

            overlayTexture = st
            result = st
            Log.i(TAG, "Created overlay texture on GL thread with ID $overlayTextureId, size ${width}x${height}")
        }
        return checkNotNull(result) { "Renderer is not running" }
    }

    /**
     * Convert pixel bounds to normalized coordinates. X is mirrored to account for the
     * video coordinate system.
     */
    private fun updateOverlayBounds(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        overlayBounds[0] = 1.0f - (x.toFloat() + width.toFloat()) / screenWidth.toFloat()
        overlayBounds[1] = y.toFloat() / screenHeight.toFloat()
        overlayBounds[2] = width.toFloat() / screenWidth.toFloat()
        overlayBounds[3] = height.toFloat() / screenHeight.toFloat()
        Matrix.setIdentityM(overlayTextureMatrix, 0)
    }

    /**
     * Set overlay bounds (use after createOverlayTexture)
     */
    fun setOverlayBounds(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        glHandler.post {
            updateOverlayBounds(x, y, width, height, screenWidth, screenHeight)
            hasOverlay = overlayTextureId != 0
        }
    }

    /**
     * Set overlay texture and bounds (legacy method)
     */
    fun setOverlay(overlayTextureId: Int, overlayTexture: SurfaceTexture?, x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        glHandler.post {
            this.overlayTextureId = overlayTextureId
            this.overlayTexture = overlayTexture
            hasOverlay = overlayTextureId != 0 || overlayTexture != null
            if (hasOverlay) {
                updateOverlayBounds(x, y, width, height, screenWidth, screenHeight)
            }
        }
    }

    /**
     * Remove overlay
     */
    fun removeOverlay() {
        glHandler.post {
            overlayTexture = null
            overlayTextureId = 0
            hasOverlay = false
        }
    }

    /**
     * Set 180° rotation for upside-down camera mounting
     */
    fun setRotate180(enabled: Boolean) {
        rotate180 = enabled
        Log.i(TAG, "180° rotation ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set decoder crop info from MediaCodec output format.
     * Precalculates the padding offset to avoid floating point precision issues.
     */
    fun setDecoderCrop(bufferHeight: Int, cropTop: Int, cropBottom: Int) {
        val bottomPadding = bufferHeight - cropBottom - 1
        glHandler.post { paddingOffset = bottomPadding.toFloat() / bufferHeight.toFloat() }
        Log.i(TAG, "Decoder crop set: bufferHeight=$bufferHeight, cropTop=$cropTop, cropBottom=$cropBottom, bottomPadding=$bottomPadding")
    }

    /**
     * Attach a SurfaceTexture for direct rendering. When frames arrive, renderFrame()
     * is called immediately on the GL thread, ensuring 1:1 frame correspondence.
     */
    fun attachSurfaceTexture(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener({ st -> renderFrame(st) }, glHandler)
    }

    /**
     * Detach the SurfaceTexture (removes the frame listener)
     */
    fun detachSurfaceTexture(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener(null)
    }

    /**
     * Render the SurfaceTexture to all active output surfaces. Runs on the GL thread
     * (posted there if called from elsewhere).
     */
    fun renderFrame(surfaceTexture: SurfaceTexture) {
        if (Looper.myLooper() != glHandler.looper) {
            glHandler.post { renderFrame(surfaceTexture) }
            return
        }
        if (eglContext == EGL14.EGL_NO_CONTEXT) return

        // Always consume the frame to keep the buffer queue flowing, even with no outputs,
        // otherwise the camera buffer queue fills up and frames stop arriving.
        makeIdleCurrent()
        try {
            surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            // The source released the texture while this frame callback was queued
            Log.w(TAG, "updateTexImage failed: ${e.message}")
            return
        }
        if (outputs.isEmpty()) return

        surfaceTexture.getTransformMatrix(textureMatrix)

        // Only update overlay texture if a new frame is available
        overlayTexture?.let { overlay ->
            if (overlayFrameAvailable) {
                overlayFrameAvailable = false
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                try {
                    overlay.updateTexImage()
                    overlay.getTransformMatrix(overlayTextureMatrix)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "Overlay updateTexImage failed: ${e.message}")
                }
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
        }

        // SurfaceTexture timestamps come from the source (decoder input PTS or camera sensor
        // clock); bring them onto CLOCK_MONOTONIC like the audio.
        val presentationTimeNanos = surfaceTexture.timestamp + timestampOffsetNanos

        // Front to back (encoder first); failed outputs are dropped in place
        var i = 0
        while (i < outputs.size) {
            if (renderToSurface(outputs[i].eglSurface, presentationTimeNanos)) {
                i++
            } else {
                Log.w(TAG, "Dropping output surface after EGL failure")
                destroyOutput(outputs.removeAt(i))
            }
        }
        makeIdleCurrent()
    }

    /**
     * Mark overlay frame as available for update
     */
    fun setOverlayFrameAvailable() {
        overlayFrameAvailable = true
    }

    /** Returns false if the surface is no longer usable. */
    private fun renderToSurface(eglSurface: EGLSurface, presentationTimeNanos: Long): Boolean {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Failed to make EGL surface current")
            return false
        }

        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, dims, 0)
        val width = dims[0]
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, dims, 0)
        val height = dims[0]

        GLES20.glViewport(0, 0, width, height)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glEnableVertexAttribArray(textureHandle)
        GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        // The video texture stays bound to unit 0 from updateTexImage()
        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glUniform1i(textureUniformHandle, 0)
        GLES20.glUniform1i(rotate180UniformHandle, if (rotate180) 1 else 0)
        GLES20.glUniform1f(paddingOffsetUniformHandle, paddingOffset)

        GLES20.glUniform1i(hasOverlayUniformHandle, if (hasOverlay) 1 else 0)
        if (hasOverlay) {
            GLES20.glUniform1i(overlayTextureUniformHandle, 1)
            GLES20.glUniform4fv(overlayBoundsUniformHandle, 1, overlayBounds, 0)
            GLES20.glUniformMatrix4fv(overlayTextureMatrixHandle, 1, false, overlayTextureMatrix, 0)
            if (overlayTextureId != 0) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTextureId)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            }
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureHandle)

        // Presentation time for the encoder, on the same clock as the audio
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNanos)
        return EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * Release all resources
     */
    fun release() {
        runOnGl {
            if (eglContext == EGL14.EGL_NO_CONTEXT) return@runOnGl
            makeIdleCurrent()
            outputs.forEach { EGL14.eglDestroySurface(eglDisplay, it.eglSurface) }
            outputs.clear()

            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }

            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(eglDisplay, idleSurface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            eglContext = EGL14.EGL_NO_CONTEXT
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
        glThread.quitSafely()
        Log.i(TAG, "SurfaceTextureRenderer released")
    }
}
