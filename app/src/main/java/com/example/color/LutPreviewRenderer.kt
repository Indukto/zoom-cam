package com.example.color

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLSurfaceView.Renderer that samples the camera SurfaceTexture, applies
 * white-balance + exposure + 3D-LUT color grading in a fragment shader, and
 * blits the result to the screen. This is the live viewfinder counterpart of
 * [LutColorFilter]; the fragment shader performs the same color grade as the
 * CPU path but at full preview rate on the GPU.
 *
 * Lifecycle / threading notes:
 * - All GL calls happen on the GLSurfaceView render thread.
 * - The [SurfaceTexture] fed to CameraX is created on the render thread inside
 *   [onSurfaceCreated] and exposed via [surfaceTextureFuture]; the camera
 *   plumbing reads it back from there.
 * - [onFrameAvailable] is invoked on CameraX's thread; it only pokes the view
 *   to request a render.
 */
class LutPreviewRenderer(
    private val glSurfaceView: GLSurfaceView
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    @Volatile private var surfaceTexture: SurfaceTexture? = null
    /** Read by the SurfaceProvider after [onSurfaceCreated] runs. */
    @Volatile var surfaceTextureReady: Boolean = false
        private set

    // --- GL program state ---
    private var program = 0
    private var aPositionLoc = 0
    private var aTexCoordLoc = 0
    private var uTextureLoc = 0
    private var uStMatrixLoc = 0
    private var uTemperatureLoc = 0
    private var uTintLoc = 0
    private var uExposureLoc = 0
    private var uLutEnabledLoc = 0
    private var uLutLoc = 0
    private var uAlphaMaskLoc = 0
    private var uTexCropLoc = 0

    private var inputTexture = 0
    private var lutTexture = 0
    private var lutWidth = 0  // 0 == no LUT uploaded yet

    // --- Per-frame inputs ---
    private val stMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    @Volatile private var temperature = 0f
    @Volatile private var tint = 0f
    @Volatile private var exposure = 0f
    @Volatile private var flipH = false
    @Volatile private var lutEnabled = false

    // Pending LUT (set from UI thread, consumed on GL thread).
    @Volatile private var pendingLut: CubeLut? = null

    // Surface-buffer aspect (set by SurfaceProvider), used for FILL_CENTER crop.
    @Volatile private var surfaceBufferWidth = 0
    @Volatile private var surfaceBufferHeight = 0
    private var viewWidth = 0
    private var viewHeight = 0

    // ------------------------------------------------------------------
    // Public API (called from the UI/compose thread)

    /** White-balance + exposure. Bounds: temp/tint ∈ [-2,2], exposure ∈ [-3,3]. */
    fun setWhiteBalance(temp: Float, tintVal: Float, exposureVal: Float) {
        temperature = temp
        tint = tintVal
        exposure = exposureVal
        glSurfaceView.requestRender()
    }

    fun setFlipH(value: Boolean) {
        flipH = value
        glSurfaceView.requestRender()
    }

    /**
     * Set the LUT to apply. Pass null to disable LUT grading (WB/exposure only).
     * The upload happens on the GL thread on the next frame.
     */
    fun setLut(lut: CubeLut?) {
        pendingLut = lut
        glSurfaceView.requestRender()
    }

    fun setSurfaceBufferSize(width: Int, height: Int) {
        surfaceBufferWidth = width
        surfaceBufferHeight = height
    }

    /** Blocks until [onSurfaceCreated] has produced the SurfaceTexture. */
    fun awaitSurfaceTexture(): SurfaceTexture? {
        var tries = 0
        while (!surfaceTextureReady && tries < 200) {
            try { Thread.sleep(10) } catch (_: InterruptedException) {}
            tries++
        }
        return surfaceTexture
    }

    // ------------------------------------------------------------------
    // SurfaceTexture.OnFrameAvailableListener

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        glSurfaceView.requestRender()
    }

    // ------------------------------------------------------------------
    // GLSurfaceView.Renderer

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = createProgram(VERT_SHADER, FRAG_SHADER)
        require(program != 0) { "Failed to compile LUT preview program" }

        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture")
        uStMatrixLoc = GLES20.glGetUniformLocation(program, "uStMatrix")
        uTemperatureLoc = GLES20.glGetUniformLocation(program, "uTemperature")
        uTintLoc = GLES20.glGetUniformLocation(program, "uTint")
        uExposureLoc = GLES20.glGetUniformLocation(program, "uExposure")
        uLutEnabledLoc = GLES20.glGetUniformLocation(program, "uLutEnabled")
        uLutLoc = GLES20.glGetUniformLocation(program, "uLut")
        uTexCropLoc = GLES20.glGetUniformLocation(program, "uTexCrop")

        // Input (camera) texture.
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        inputTexture = tex[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // The SurfaceTexture owns the camera-to-GL handoff. Attach a detaching
        // GL texture so we can recreate cleanly on context loss.
        val st = SurfaceTexture(inputTexture)
        st.setOnFrameAvailableListener(this)
        surfaceTexture = st
        surfaceTextureReady = true

        // LUT texture (3D). Created lazily when setLut() provides one.
        val lut = IntArray(1)
        GLES20.glGenTextures(1, lut, 0)
        lutTexture = lut[0]

        // Default GL_UNPACK_ALIGNMENT is 4, which only works when rows are a
        // multiple of 4 bytes. A 3D LUT stored as GL_RGB has 3 bytes per texel
        // — for any LUT size n where n * 3 is NOT a multiple of 4 (e.g. the
        // bundled 13-cube LUTs at 39 bytes/row) the driver inserts phantom
        // pad bytes and the next row's channels phase-shift, producing the
        // classic psychedelic rainbow banding. Pin alignment to 1 (no padding)
        // here so subsequent glTexImage3D uploads are always tightly packed.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Upload any pending LUT.
        pendingLut?.let { uploadLut(it); pendingLut = null }

        val st = surfaceTexture ?: return
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        try {
            st.updateTexImage()
        } catch (e: Exception) {
            // SurfaceTexture may be released during a rebind; just skip this frame.
            return
        }
        st.getTransformMatrix(stMatrix)

        GLES20.glUseProgram(program)

        // Vertex coords (a fullscreen triangle pair).
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        GLES20.glUniform1i(uTextureLoc, 0)
        GLES20.glUniformMatrix4fv(uStMatrixLoc, 1, false, stMatrix, 0)

        // Tex crop: FILL_CENTER. We compute the sub-rect of the camera buffer
        // that fills the view without letterboxing, in normalized coords.
        val crop = computeFillCenterCrop()
        GLES20.glUniform4f(uTexCropLoc, crop[0], crop[1], crop[2], crop[3])

        GLES20.glUniform1f(uTemperatureLoc, temperature)
        GLES20.glUniform1f(uTintLoc, tint)
        GLES20.glUniform1f(uExposureLoc, exposure)

        if (lutEnabled && lutWidth > 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
            GLES20.glUniform1i(uLutLoc, 1)
        }
        GLES20.glUniform1i(uLutEnabledLoc, if (lutEnabled && lutWidth > 0) 1 else 0)

        quadPositionBuf().position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadPositionBuf())

        quadTexCoordBuf(flipH).position(0)
        GLES20.glEnableVertexAttribArray(aTexCoordLoc)
        GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordBuf(flipH))

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTexCoordLoc)
    }

    /** Releases the SurfaceTexture when the camera session is torn down. */
    fun releaseSurfaceTexture() {
        val st = surfaceTexture
        if (st != null) {
            try { st.setOnFrameAvailableListener(null) } catch (_: Exception) {}
            try { st.release() } catch (_: Exception) {}
        }
        surfaceTexture = null
        surfaceTextureReady = false
    }

    // ------------------------------------------------------------------
    // Internals

    private fun uploadLut(lut: CubeLut) {
        // Convert float RGB samples to 8-bit (the camera input is 8-bit anyway).
        val n = lut.size
        val buf = ByteBuffer.allocateDirect(n * n * n * 3).order(ByteOrder.nativeOrder())
        for (v in lut.data) {
            buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        }
        buf.position(0)

        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES20.GL_CLAMP_TO_EDGE)
        // Belt-and-suspenders: also set UNPACK_ALIGNMENT on every upload so a
        // foreign driver that resets it between contexts can't reintroduce
        // the rainbow banding. See onSurfaceCreated for context.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES20.GL_RGB,
            n, n, n, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, buf
        )
        lutWidth = n
        lutEnabled = true
    }

    /**
     * Returns (u0, v0, u1, v1) — the sub-rect of the camera buffer that should
     * be sampled to fill the view with FILL_CENTER semantics (no letterbox).
     * Returns full extent (0,0,1,1) when the surface size isn't known yet.
     */
    private fun computeFillCenterCrop(): FloatArray {
        var bw = surfaceBufferWidth
        var bh = surfaceBufferHeight
        if (bw <= 0 || bh <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return floatArrayOf(0f, 0f, 1f, 1f)
        }
        // CameraX writes frames to the SurfaceTexture in sensor-natural
        // orientation (almost always landscape on phones) and sets the
        // stMatrix to rotate them to display-natural during sampling.
        // uTexCrop is applied BEFORE uStMatrix in the vertex shader, so
        // it operates in pre-rotation buffer coords. For 90°/270°
        // rotations the buffer's post-rotation aspect is W:H inverted,
        // so the buffer-side crop math has to use the swapped dims to
        // produce a fill that's correct in display space. We detect the
        // swap by checking the off-diagonal entries that are non-zero on
        // 90°/270° rotation matrices (identity and 180° leave them at 0,
        // so the crop math runs unchanged there).
        if (kotlin.math.abs(stMatrix[1]) > 0.5f || kotlin.math.abs(stMatrix[4]) > 0.5f) {
            val tmp = bw
            bw = bh
            bh = tmp
        }
        val bufferAspect = bw.toFloat() / bh.toFloat()
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        return if (bufferAspect > viewAspect) {
            // Buffer is wider — crop left/right.
            val crop = viewAspect / bufferAspect
            val off = (1f - crop) * 0.5f
            floatArrayOf(off, 0f, off + crop, 1f)
        } else {
            // Buffer is taller — crop top/bottom.
            val crop = bufferAspect / viewAspect
            val off = (1f - crop) * 0.5f
            floatArrayOf(0f, off, 1f, off + crop)
        }
    }

    private fun quadPositionBuf(): FloatBuffer {
        // Same vertex array each frame; cached statically.
        if (::posBuf.isInitialized) return posBuf
        val verts = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        val b = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(verts)
        b.position(0)
        posBuf = b
        return posBuf
    }

    private lateinit var posBuf: FloatBuffer

    private fun quadTexCoordBuf(flip: Boolean): FloatBuffer {
        if (::tcsBufNoFlip.isInitialized && ::tcsBufFlip.isInitialized) {
            return if (flip) tcsBufFlip else tcsBufNoFlip
        }
        val noFlip = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )
        val flipArr = floatArrayOf(
            1f, 0f,
            0f, 0f,
            1f, 1f,
            0f, 1f
        )
        tcsBufNoFlip = ByteBuffer.allocateDirect(noFlip.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(noFlip)
        tcsBufNoFlip.position(0)
        tcsBufFlip = ByteBuffer.allocateDirect(flipArr.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(flipArr)
        tcsBufFlip.position(0)
        return if (flip) tcsBufFlip else tcsBufNoFlip
    }

    private lateinit var tcsBufNoFlip: FloatBuffer
    private lateinit var tcsBufFlip: FloatBuffer

    private fun loadShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            Log.e(TAG, "Shader compile failed: $log")
            return 0
        }
        return shader
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        if (vs == 0) return 0
        val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (fs == 0) {
            GLES20.glDeleteShader(vs)
            return 0
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(p)}")
            GLES20.glDeleteProgram(p)
            return 0
        }
        return p
    }

    companion object {
        private const val TAG = "LutPreviewRenderer"

        // Quad vertices (clip space) → fullscreen triangle strip.
        private const val VERT_SHADER = """
            uniform mat4 uStMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform vec4 uTexCrop;   // (u0, v0, u1, v1) sub-rect of camera tex
            varying vec2 vTexCoord;
            void main() {
                // Map the base 0..1 texcoord into the cropped sub-rect.
                vec2 cropped = vec2(
                    mix(uTexCrop.x, uTexCrop.z, aTexCoord.x),
                    mix(uTexCrop.y, uTexCrop.w, aTexCoord.y)
                );
                vTexCoord = (uStMatrix * vec4(cropped, 0.0, 1.0)).xy;
                gl_Position = aPosition;
            }
        """

        // Fragment shader: sample camera → apply WB + exposure + optional LUT.
        // Uses GL_OES_EGL_image_external for the camera texture and
        // GL_OES_texture_3D for the LUT (both universal on minSdk 24).
        private const val FRAG_SHADER = """
            #extension GL_OES_EGL_image_external : require
            #extension GL_OES_texture_3D : enable
            precision mediump float;
            precision mediump sampler3D;
            uniform samplerExternalOES uTexture;
            uniform mediump sampler3D uLut;
            uniform float uTemperature;
            uniform float uTint;
            uniform float uExposure;
            uniform int uLutEnabled;
            varying vec2 vTexCoord;

            void main() {
                vec3 c = texture2D(uTexture, vTexCoord).rgb;

                // White balance — temp (warm/cool) and tint (green/magenta).
                // Same sign/feel as the existing capture-side retro filter.
                c.r += uTemperature * 0.04;
                c.b -= uTemperature * 0.04;
                c.g += uTint * 0.04;
                c.r -= uTint * 0.02;
                c.b -= uTint * 0.02;
                c = clamp(c, 0.0, 1.0);

                // Exposure — stops-ish scaling.
                c *= pow(2.0, uExposure * 0.4);
                c = clamp(c, 0.0, 1.0);

                // 3D LUT. texture3D coords are normalized to [0,1]; the GPU's
                // linear filtering gives trilinear interpolation for free.
                if (uLutEnabled == 1) {
                    c = texture3D(uLut, c).rgb;
                }
                gl_FragColor = vec4(c, 1.0);
            }
        """
    }
}
