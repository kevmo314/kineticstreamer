package com.kevmo314.kineticstreamer

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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
            uniform vec4 uOverlayBounds; // x, y, width, height in normalized coordinates
            uniform mat4 uOverlayTextureMatrix;
            void main() {
                vec4 videoColor = texture2D(uTexture, vTextureCoord);

                if (uHasOverlay == 1) {
                    // Convert texture coordinates to screen space [0,1]
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

    @Volatile
    private var isInitialized = false
    private val initLock = Object()

    init {
        // Initialize vertex buffers
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

        // Initialize EGL immediately on creation
        setupEGL()
        setupShaders()

        Log.i(TAG, "SurfaceTextureRenderer initialized")
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
     * Set overlay texture and bounds
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
            Log.d(TAG, "Transform matrix: [${textureMatrix[0]}, ${textureMatrix[1]}, ${textureMatrix[2]}, ${textureMatrix[3]}]")
            Log.d(TAG, "                  [${textureMatrix[4]}, ${textureMatrix[5]}, ${textureMatrix[6]}, ${textureMatrix[7]}]")
            Log.d(TAG, "                  [${textureMatrix[8]}, ${textureMatrix[9]}, ${textureMatrix[10]}, ${textureMatrix[11]}]")
            Log.d(TAG, "                  [${textureMatrix[12]}, ${textureMatrix[13]}, ${textureMatrix[14]}, ${textureMatrix[15]}]")
        }

        // Override with identity matrix + 180 rotation + horizontal flip = vertical flip only
        // The camera's transform matrix includes rotation that causes aspect ratio issues
        Matrix.setIdentityM(textureMatrix, 0)
        // 180 rotation + horizontal flip = vertical flip only
        textureMatrix[5] = -1.0f   // Flip Y scale
        textureMatrix[13] = 1.0f   // Translate Y to compensate

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
        Log.d(TAG, "renderToSurface: surface dimensions ${width[0]}x${height[0]}")

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

        Log.i(TAG, "SurfaceTextureRenderer released")
    }
}
