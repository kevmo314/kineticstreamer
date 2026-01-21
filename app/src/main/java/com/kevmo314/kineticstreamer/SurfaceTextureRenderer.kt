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
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch

/**
 * Efficient OpenGL renderer that takes a SurfaceTexture and renders it to multiple output surfaces
 * Optimized for dynamic surface management without decoder restarts
 */
class SurfaceTextureRenderer {
    companion object {
        private const val TAG = "SurfaceTextureRenderer"

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

    // Dedicated GL thread for rendering
    private val glThread = HandlerThread("GLRenderer").apply { start() }
    val glHandler = Handler(glThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

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

    private var vertexBuffer: FloatBuffer
    private var textureBuffer: FloatBuffer
    private val textureMatrix = FloatArray(16)

    private val activeSurfaces = mutableMapOf<Surface, EGLSurface>()

    // Overlay support
    private var overlayTexture: SurfaceTexture? = null
    private var overlayTextureId: Int = 0
    private var overlayBounds = FloatArray(4) // x, y, width, height in normalized coords [0,1]
    private val overlayTextureMatrix = FloatArray(16)
    private var hasOverlay = false

    // 180° rotation support for upside-down camera mounting
    @Volatile
    private var rotate180 = false

    // Synthetic timestamp support for smooth encoder timing
    private var frameCount = 0L
    private val frameIntervalNs = 33_333_333L // 30fps = ~33.33ms per frame

    @Volatile
    private var isInitialized = false
    private val initLock = Object()

    init {
        // Initialize vertex buffers (can be done on any thread)
        vertexBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(QUAD_VERTICES)
        vertexBuffer.position(0)

        textureBuffer = ByteBuffer.allocateDirect(TEXTURE_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(TEXTURE_COORDS)
        textureBuffer.position(0)

        Matrix.setIdentityM(textureMatrix, 0)

        // Initialize EGL on the GL thread (GL context is thread-bound)
        val latch = CountDownLatch(1)
        glHandler.post {
            setupEGL()
            setupShaders()
            latch.countDown()
        }
        latch.await() // Wait for GL init to complete

        Log.i(TAG, "SurfaceTextureRenderer initialized on GL thread")
    }

    private fun setupEGL() {
        // Get default display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("Unable to get EGL display")
        }

        // Initialize EGL
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("Unable to initialize EGL")
        }

        // Configure EGL
        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)) {
            throw RuntimeException("Unable to choose EGL config")
        }
        eglConfig = configs[0]

        // Create EGL context
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
    }

    private fun setupShaders() {
        // Create a temporary pbuffer surface to compile shaders
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )

        val tempSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, tempSurface, tempSurface, eglContext)

        // Compile shaders
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        // Create program
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        // Check link status
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val error = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("Failed to link shader program: $error")
        }

        // Get attribute/uniform locations
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

        // Clean up temp surface
        EGL14.eglDestroySurface(eglDisplay, tempSurface)

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
     * Add a surface as a render target
     */
    fun addOutputSurface(surface: Surface?) {
        if (surface == null) return

        synchronized(activeSurfaces) {
            if (activeSurfaces.containsKey(surface)) {
                Log.w(TAG, "Surface already added")
                return
            }

            // Validate surface before creating EGL surface
            if (!surface.isValid) {
                Log.w(TAG, "Surface is not valid, skipping")
                return
            }

            // Create EGL surface for this output
            val eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay,
                eglConfig,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0
            )

            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                // Query the actual dimensions of the EGL surface
                val width = IntArray(1)
                val height = IntArray(1)
                EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0)
                EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)
                Log.i(TAG, "EGL surface created: ${width[0]}x${height[0]}")

                activeSurfaces[surface] = eglSurface
                Log.i(TAG, "Added output surface, total: ${activeSurfaces.size}")
            } else {
                Log.e(TAG, "Failed to create EGL surface, error: ${EGL14.eglGetError()}")
            }
        }
    }

    /**
     * Remove a surface from render targets
     */
    fun removeOutputSurface(surface: Surface?) {
        if (surface == null) return

        synchronized(activeSurfaces) {
            val eglSurface = activeSurfaces.remove(surface)
            if (eglSurface != null) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                Log.i(TAG, "Removed output surface, remaining: ${activeSurfaces.size}")
            }
        }
    }

    /**
     * Create an overlay texture on the GL thread and return its SurfaceTexture.
     * This must be called to get a valid texture for overlay rendering.
     */
    fun createOverlayTexture(width: Int, height: Int): SurfaceTexture {
        val latch = CountDownLatch(1)
        var result: SurfaceTexture? = null

        glHandler.post {
            // Generate texture on GL thread where we have context
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            overlayTextureId = textures[0]

            // Bind and configure the texture
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Create SurfaceTexture from the texture
            val st = SurfaceTexture(overlayTextureId)
            st.setDefaultBufferSize(width, height)

            // Set up frame listener
            st.setOnFrameAvailableListener {
                overlayFrameAvailable = true
            }

            overlayTexture = st
            result = st

            Log.i(TAG, "Created overlay texture on GL thread with ID $overlayTextureId, size ${width}x${height}")
            latch.countDown()
        }

        latch.await()
        return result!!
    }

    /**
     * Set overlay bounds (use after createOverlayTexture)
     */
    fun setOverlayBounds(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        Log.i(TAG, "setOverlayBounds() called: bounds=($x,$y,${width}x${height}), screen=${screenWidth}x${screenHeight}")

        // Convert pixel coordinates to normalized coordinates [0,1]
        // Mirror X position to account for video coordinate system
        overlayBounds[0] = 1.0f - (x.toFloat() + width.toFloat()) / screenWidth.toFloat() // x (mirrored left edge)
        overlayBounds[1] = y.toFloat() / screenHeight.toFloat() // y (top edge in screen coords)
        overlayBounds[2] = width.toFloat() / screenWidth.toFloat() // width
        overlayBounds[3] = height.toFloat() / screenHeight.toFloat() // height

        // Initialize overlay texture matrix (identity matrix)
        Matrix.setIdentityM(overlayTextureMatrix, 0)

        hasOverlay = overlayTextureId != 0
        Log.i(TAG, "Overlay bounds set: pixel coords=($x, $y, $width, $height), normalized bounds=(${overlayBounds[0]}, ${overlayBounds[1]}, ${overlayBounds[2]}, ${overlayBounds[3]}), hasOverlay=$hasOverlay")
    }

    /**
     * Set overlay texture and bounds (legacy method)
     */
    fun setOverlay(overlayTextureId: Int, overlayTexture: SurfaceTexture?, x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int) {
        Log.i(TAG, "setOverlay() called: textureId=$overlayTextureId, surfaceTexture=$overlayTexture, bounds=($x,$y,${width}x${height}), screen=${screenWidth}x${screenHeight}")
        this.overlayTextureId = overlayTextureId
        this.overlayTexture = overlayTexture

        // Enable overlay if we have either a texture ID or a SurfaceTexture
        if (overlayTextureId != 0 || overlayTexture != null) {
            // Convert pixel coordinates to normalized coordinates [0,1]
            // Mirror X position to account for video coordinate system
            overlayBounds[0] = 1.0f - (x.toFloat() + width.toFloat()) / screenWidth.toFloat() // x (mirrored left edge)
            overlayBounds[1] = y.toFloat() / screenHeight.toFloat() // y (top edge in screen coords)
            overlayBounds[2] = width.toFloat() / screenWidth.toFloat() // width
            overlayBounds[3] = height.toFloat() / screenHeight.toFloat() // height

            // Initialize overlay texture matrix (identity matrix with Y flip)
            Matrix.setIdentityM(overlayTextureMatrix, 0)
            // No flip needed for WebView texture since it's not from camera

            hasOverlay = true
            Log.i(TAG, "Overlay enabled: pixel coords=($x, $y, $width, $height), normalized bounds=(${overlayBounds[0]}, ${overlayBounds[1]}, ${overlayBounds[2]}, ${overlayBounds[3]})")
        } else {
            hasOverlay = false
            Log.i(TAG, "Overlay disabled (no texture ID and no SurfaceTexture)")
        }
    }

    /**
     * Remove overlay
     */
    fun removeOverlay() {
        overlayTexture = null
        overlayTextureId = 0
        hasOverlay = false
    }

    /**
     * Set 180° rotation for upside-down camera mounting
     */
    fun setRotate180(enabled: Boolean) {
        rotate180 = enabled
        Log.i(TAG, "180° rotation ${if (enabled) "enabled" else "disabled"}")
    }

    // Precalculated padding offset for rotation (calculated from integer pixel values)
    private var paddingOffset: Float = 0f

    /**
     * Set decoder crop info from MediaCodec output format.
     * Precalculates the padding offset to avoid floating point precision issues.
     */
    fun setDecoderCrop(bufferHeight: Int, cropTop: Int, cropBottom: Int) {
        val topPadding = cropTop
        val bottomPadding = bufferHeight - cropBottom - 1
        // Precalculate offset from integer pixel values
        // This is the amount to shift when rotating to avoid sampling from padding
        paddingOffset = bottomPadding.toFloat() / bufferHeight.toFloat()
        Log.i(TAG, "Decoder crop set: bufferHeight=$bufferHeight, cropTop=$cropTop, cropBottom=$cropBottom, topPadding=$topPadding, bottomPadding=$bottomPadding, paddingOffset=$paddingOffset")
    }

    /**
     * Attach a SurfaceTexture for direct rendering. When frames arrive, renderFrame()
     * is called immediately on the GL thread, ensuring 1:1 frame correspondence.
     * This avoids frame duplication/dropping caused by async Flow processing.
     */
    fun attachSurfaceTexture(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener({ st ->
            // Called on GL thread - render immediately
            renderFrame(st)
        }, glHandler)
        Log.i(TAG, "SurfaceTexture attached for direct rendering")
    }

    /**
     * Detach the SurfaceTexture (removes the frame listener)
     */
    fun detachSurfaceTexture(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setOnFrameAvailableListener(null)
        Log.i(TAG, "SurfaceTexture detached")
    }

    @Volatile
    private var overlayFrameAvailable = false

    /**
     * Render the SurfaceTexture to all active output surfaces
     */
    fun renderFrame(surfaceTexture: SurfaceTexture) {
        // Always consume the frame to keep the buffer queue flowing
        // Even if we have no surfaces to render to, we must call updateTexImage()
        // otherwise the camera buffer queue fills up and frames stop arriving
        surfaceTexture.updateTexImage()

        // Quick check without lock - skip rendering if no surfaces
        if (activeSurfaces.isEmpty()) {
            return
        }

        // Update textures outside of synchronized block
        // Update main video texture on texture unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        surfaceTexture.getTransformMatrix(textureMatrix)

        // Log transform matrix periodically for debugging
        if (System.currentTimeMillis() % 5000 < 50) {
            Log.i(TAG, "Transform matrix row0: [${textureMatrix[0]}, ${textureMatrix[1]}, ${textureMatrix[2]}, ${textureMatrix[3]}]")
            Log.i(TAG, "Transform matrix row1: [${textureMatrix[4]}, ${textureMatrix[5]}, ${textureMatrix[6]}, ${textureMatrix[7]}]")
            Log.i(TAG, "Transform matrix row2: [${textureMatrix[8]}, ${textureMatrix[9]}, ${textureMatrix[10]}, ${textureMatrix[11]}]")
            Log.i(TAG, "Transform matrix row3: [${textureMatrix[12]}, ${textureMatrix[13]}, ${textureMatrix[14]}, ${textureMatrix[15]}]")
        }

        // 180° rotation and padding adjustment are handled in the fragment shader
        // via uRotate180 and uPaddingOffset uniforms to avoid matrix precision issues.

        // Only update overlay texture if a new frame is available
        overlayTexture?.let { overlay ->
            if (overlayFrameAvailable) {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                overlay.updateTexImage()
                overlay.getTransformMatrix(overlayTextureMatrix)
                // Explicitly bind the overlay texture to unit 1
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTextureId)
                overlayFrameAvailable = false
            }
        }

        // Reset to texture unit 0 for subsequent operations
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        // Copy surface list to avoid holding lock during rendering
        val surfacesToRender = synchronized(activeSurfaces) {
            activeSurfaces.toList()
        }

        // Render to each active surface without holding lock
        for ((_, eglSurface) in surfacesToRender) {
            renderToSurface(eglSurface)
        }

        // Increment frame counter for next synthetic timestamp
        frameCount++
    }

    /**
     * Mark overlay frame as available for update
     */
    fun setOverlayFrameAvailable() {
        overlayFrameAvailable = true
    }

    private fun renderToSurface(eglSurface: EGLSurface) {
        // Make this surface current
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            Log.e(TAG, "Failed to make EGL surface current")
            return
        }

        // Get surface dimensions
        val width = IntArray(1)
        val height = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, width, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, height, 0)

        // Set viewport and clear
        GLES20.glViewport(0, 0, width[0], height[0])
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Use shader program
        GLES20.glUseProgram(program)

        // Set vertex attributes
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

        GLES20.glEnableVertexAttribArray(textureHandle)
        GLES20.glVertexAttribPointer(textureHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

        // Set uniforms
        GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)
        GLES20.glUniform1i(textureUniformHandle, 0)
        GLES20.glUniform1i(rotate180UniformHandle, if (rotate180) 1 else 0)
        GLES20.glUniform1f(paddingOffsetUniformHandle, paddingOffset)

        // Set overlay uniforms
        GLES20.glUniform1i(hasOverlayUniformHandle, if (hasOverlay) 1 else 0)
        if (hasOverlay) {
            GLES20.glUniform1i(overlayTextureUniformHandle, 1) // Texture unit 1 for overlay
            GLES20.glUniform4fv(overlayBoundsUniformHandle, 1, overlayBounds, 0)
            GLES20.glUniformMatrix4fv(overlayTextureMatrixHandle, 1, false, overlayTextureMatrix, 0)
        }

        // Bind main video texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        // Note: The main video texture is already bound by SurfaceTexture.updateTexImage()
        // But we need to ensure we're on the right texture unit

        // Bind overlay texture to unit 1 if present
        if (hasOverlay && overlayTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, overlayTextureId)
            // Reset to texture unit 0 for default state
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        }

        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Set synthetic presentation time for smooth encoder timing
        // This ensures constant frame intervals regardless of actual render timing
        val presentationTimeNs = frameCount * frameIntervalNs
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs)

        // Swap buffers
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)

        // Cleanup
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(textureHandle)
    }

    /**
     * Release all resources
     */
    fun release() {
        // Run cleanup on GL thread
        val latch = CountDownLatch(1)
        glHandler.post {
            synchronized(activeSurfaces) {
                // Destroy all EGL surfaces
                for ((_, eglSurface) in activeSurfaces) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                }
                activeSurfaces.clear()
            }

            // Delete shader program
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }

            // Destroy EGL context
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }

            // Terminate EGL
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
            latch.countDown()
        }
        latch.await()

        // Stop the GL thread
        glThread.quitSafely()

        Log.i(TAG, "SurfaceTextureRenderer released")
    }
}
