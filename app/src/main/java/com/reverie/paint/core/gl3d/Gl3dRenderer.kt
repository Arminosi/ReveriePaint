/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.gl3d

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import com.reverie.paint.model.Math3D
import com.reverie.paint.model.Mesh3D
import com.reverie.paint.model.Model3DState
import com.reverie.paint.model.RenderMode3D
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * 专用轻量级 OpenGL ES 3D 离屏渲染管线
 *
 * 针对素模 (Clay)、光影体块 (Shading) 与结构透视线框 (Wireframe) 定制。
 * 零第三方体积膨胀，支持完全在后台线程渲染出与画布等比的 Bitmap。
 */
class Gl3dRenderer {

    companion object {
        private const val TAG = "Gl3dRenderer"

        private const val VERTEX_SHADER = """
            attribute vec3 aPosition;
            attribute vec3 aNormal;
            uniform mat4 uMVPMatrix;
            uniform mat3 uNormalMatrix;
            varying vec3 vNormal;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vNormal = normalize(uNormalMatrix * aNormal);
            }
        """

        private const val CLAY_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            uniform vec4 uBaseColor;
            void main() {
                vec3 n = normalize(vNormal);
                // 半球环境光: 上部偏微暖天光，下部偏冷地光/阴影
                float hemi = n.y * 0.5 + 0.5;
                vec3 sky = vec3(1.0, 0.98, 0.94);
                vec3 ground = vec3(0.68, 0.70, 0.76);
                vec3 amb = mix(ground, sky, hemi);

                // 侧上方柔和主光
                vec3 keyDir = normalize(vec3(0.4, 0.7, 0.6));
                float diff = max(dot(n, keyDir), 0.0);

                // 边缘微弱轮廓反射光 (Fresnel / Rim)
                float rim = 1.0 - max(dot(n, vec3(0.0, 0.0, 1.0)), 0.0);
                rim = smoothstep(0.5, 0.9, rim) * 0.25;

                vec3 rgb = uBaseColor.rgb * (amb * 0.7 + diff * 0.45) + vec3(rim);
                gl_FragColor = vec4(rgb, uBaseColor.a);
            }
        """

        private const val SHADING_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vNormal;
            uniform vec4 uBaseColor;
            uniform vec3 uLightDir;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 l = normalize(uLightDir);
                float ndotl = max(dot(n, l), 0.0);

                // 明暗两分切面 (保留起稿明确体积)
                float diff = smoothstep(0.0, 0.08, ndotl) * 0.75 + 0.25;
                vec3 rgb = uBaseColor.rgb * diff;
                gl_FragColor = vec4(rgb, uBaseColor.a);
            }
        """

        private const val WIREFRAME_VERTEX_SHADER = """
            attribute vec3 aPosition;
            uniform mat4 uMVPMatrix;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
            }
        """

        private const val WIREFRAME_FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                gl_FragColor = uColor;
            }
        """
    }

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var clayProgram = 0
    private var shadingProgram = 0
    private var wireframeProgram = 0

    private var fboId = 0
    private var colorTexId = 0
    private var depthRbId = 0
    private var fboW = 0
    private var fboH = 0

    private var isInitialized = false

    @Synchronized
    fun ensureInitialized(): Boolean {
        if (isInitialized) return true

        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) return false

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16,
                EGL14.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return false

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            val surfaceAttribs = intArrayOf(
                EGL14.EGL_WIDTH, 1,
                EGL14.EGL_HEIGHT, 1,
                EGL14.EGL_NONE
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, surfaceAttribs, 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return false

            // 编译并链接 Shader Programs
            clayProgram = createProgram(VERTEX_SHADER, CLAY_FRAGMENT_SHADER)
            shadingProgram = createProgram(VERTEX_SHADER, SHADING_FRAGMENT_SHADER)
            wireframeProgram = createProgram(WIREFRAME_VERTEX_SHADER, WIREFRAME_FRAGMENT_SHADER)

            isInitialized = clayProgram != 0 && shadingProgram != 0 && wireframeProgram != 0
            return isInitialized
        } catch (e: Exception) {
            Log.e(TAG, "EGL initialization failed", e)
            return false
        }
    }

    private fun setupFbo(w: Int, h: Int) {
        if (fboW == w && fboH == h && fboId != 0) return

        destroyFbo()

        val fbos = IntArray(1)
        GLES20.glGenFramebuffers(1, fbos, 0)
        fboId = fbos[0]

        val texs = IntArray(1)
        GLES20.glGenTextures(1, texs, 0)
        colorTexId = texs[0]

        val rbs = IntArray(1)
        GLES20.glGenRenderbuffers(1, rbs, 0)
        depthRbId = rbs[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTexId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTexId, 0)

        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRbId)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthRbId)

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete, status=$status")
        }

        fboW = w
        fboH = h
    }

    private fun destroyFbo() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (colorTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(colorTexId), 0)
            colorTexId = 0
        }
        if (depthRbId != 0) {
            GLES20.glDeleteRenderbuffers(1, intArrayOf(depthRbId), 0)
            depthRbId = 0
        }
        fboW = 0
        fboH = 0
    }

    /**
     * 将 3D 网格与状态离屏渲染至 Bitmap (线程安全)
     */
    @Synchronized
    fun renderToBitmap(
        mesh: Mesh3D,
        state: Model3DState,
        width: Int,
        height: Int,
        reusedBitmap: Bitmap? = null,
    ): Bitmap? {
        if (width <= 0 || height <= 0) return null
        if (!ensureInitialized()) return null

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        setupFbo(width, height)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, width, height)

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        // 计算 MVP 变换矩阵
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        val proj = Math3D.perspective(45f, aspect, 0.1f, 100f)

        // 视距根据 zoom 调整
        val dist = (3.5f / state.zoom.coerceIn(0.1f, 10f))
        val view = Math3D.lookAt(0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)

        // 模型世界变换: 平移与旋转
        var model = Math3D.identity()
        model = Math3D.translate(model, state.panX * 2f, state.panY * 2f, 0f)
        model = Math3D.rotate(model, state.rotX, 1f, 0f, 0f)
        model = Math3D.rotate(model, state.rotY, 0f, 1f, 0f)
        model = Math3D.rotate(model, state.rotZ, 0f, 0f, 1f)

        val mv = Math3D.multiplyMM(view, model)
        val mvp = Math3D.multiplyMM(proj, mv)

        // 提取 3x3 法线矩阵
        val normalMatrix = floatArrayOf(
            mv[0], mv[1], mv[2],
            mv[4], mv[5], mv[6],
            mv[8], mv[9], mv[10]
        )

        // 构造扁平着色数据以消除插值平滑模糊，呈现清晰的面部与体块切面
        val triCount = mesh.indices.size / 3
        val flatPositions = FloatArray(triCount * 9)
        val flatNormals = FloatArray(triCount * 9)
        val isSphere = (state.modelType == "sphere" && mesh.normals.size == mesh.positions.size)

        for (t in 0 until triCount) {
            val i0 = mesh.indices[t * 3]
            val i1 = mesh.indices[t * 3 + 1]
            val i2 = mesh.indices[t * 3 + 2]

            val p0x = mesh.positions[i0 * 3]; val p0y = mesh.positions[i0 * 3 + 1]; val p0z = mesh.positions[i0 * 3 + 2]
            val p1x = mesh.positions[i1 * 3]; val p1y = mesh.positions[i1 * 3 + 1]; val p1z = mesh.positions[i1 * 3 + 2]
            val p2x = mesh.positions[i2 * 3]; val p2y = mesh.positions[i2 * 3 + 1]; val p2z = mesh.positions[i2 * 3 + 2]

            val bIdx = t * 9
            flatPositions[bIdx] = p0x; flatPositions[bIdx + 1] = p0y; flatPositions[bIdx + 2] = p0z
            flatPositions[bIdx + 3] = p1x; flatPositions[bIdx + 4] = p1y; flatPositions[bIdx + 5] = p1z
            flatPositions[bIdx + 6] = p2x; flatPositions[bIdx + 7] = p2y; flatPositions[bIdx + 8] = p2z

            if (isSphere) {
                flatNormals[bIdx] = mesh.normals[i0 * 3]; flatNormals[bIdx + 1] = mesh.normals[i0 * 3 + 1]; flatNormals[bIdx + 2] = mesh.normals[i0 * 3 + 2]
                flatNormals[bIdx + 3] = mesh.normals[i1 * 3]; flatNormals[bIdx + 4] = mesh.normals[i1 * 3 + 1]; flatNormals[bIdx + 5] = mesh.normals[i1 * 3 + 2]
                flatNormals[bIdx + 6] = mesh.normals[i2 * 3]; flatNormals[bIdx + 7] = mesh.normals[i2 * 3 + 1]; flatNormals[bIdx + 8] = mesh.normals[i2 * 3 + 2]
            } else {
                val e1x = p1x - p0x; val e1y = p1y - p0y; val e1z = p1z - p0z
                val e2x = p2x - p0x; val e2y = p2y - p0y; val e2z = p2z - p0z
                var fnx = e1y * e2z - e1z * e2y
                var fny = e1z * e2x - e1x * e2z
                var fnz = e1x * e2y - e1y * e2x
                val len = kotlin.math.hypot(kotlin.math.hypot(fnx, fny), fnz).coerceAtLeast(1e-6f)
                fnx /= len; fny /= len; fnz /= len

                flatNormals[bIdx] = fnx; flatNormals[bIdx + 1] = fny; flatNormals[bIdx + 2] = fnz
                flatNormals[bIdx + 3] = fnx; flatNormals[bIdx + 4] = fny; flatNormals[bIdx + 5] = fnz
                flatNormals[bIdx + 6] = fnx; flatNormals[bIdx + 7] = fny; flatNormals[bIdx + 8] = fnz
            }
        }

        val posBuf = ByteBuffer.allocateDirect(flatPositions.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(flatPositions); position(0)
            }
        val normBuf = ByteBuffer.allocateDirect(flatNormals.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(flatNormals); position(0)
            }

        // 解析颜色
        val bc = state.baseColor
        val baseR = ((bc shr 16) and 0xFF) / 255f
        val baseG = ((bc shr 8) and 0xFF) / 255f
        val baseB = (bc and 0xFF) / 255f
        val baseA = 1.0f

        when (state.renderMode) {
            RenderMode3D.CLAY -> {
                GLES20.glUseProgram(clayProgram)
                val uMVP = GLES20.glGetUniformLocation(clayProgram, "uMVPMatrix")
                val uNormM = GLES20.glGetUniformLocation(clayProgram, "uNormalMatrix")
                val uColor = GLES20.glGetUniformLocation(clayProgram, "uBaseColor")
                val aPos = GLES20.glGetAttribLocation(clayProgram, "aPosition")
                val aNorm = GLES20.glGetAttribLocation(clayProgram, "aNormal")

                GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
                GLES20.glUniformMatrix3fv(uNormM, 1, false, normalMatrix, 0)
                GLES20.glUniform4f(uColor, baseR, baseG, baseB, baseA)

                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, posBuf)
                GLES20.glEnableVertexAttribArray(aNorm)
                GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, 0, normBuf)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount * 3)

                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aNorm)
            }

            RenderMode3D.SHADING -> {
                GLES20.glUseProgram(shadingProgram)
                val uMVP = GLES20.glGetUniformLocation(shadingProgram, "uMVPMatrix")
                val uNormM = GLES20.glGetUniformLocation(shadingProgram, "uNormalMatrix")
                val uColor = GLES20.glGetUniformLocation(shadingProgram, "uBaseColor")
                val uLight = GLES20.glGetUniformLocation(shadingProgram, "uLightDir")
                val aPos = GLES20.glGetAttribLocation(shadingProgram, "aPosition")
                val aNorm = GLES20.glGetAttribLocation(shadingProgram, "aNormal")

                val lightDir = Math3D.calculateLightDirection(state.lightYaw, state.lightPitch)

                GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
                GLES20.glUniformMatrix3fv(uNormM, 1, false, normalMatrix, 0)
                GLES20.glUniform4f(uColor, baseR, baseG, baseB, baseA)
                GLES20.glUniform3f(uLight, lightDir[0], lightDir[1], lightDir[2])

                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, posBuf)
                GLES20.glEnableVertexAttribArray(aNorm)
                GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, 0, normBuf)

                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount * 3)

                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aNorm)
            }

            RenderMode3D.WIREFRAME -> {
                // 1. 先用带深度偏移的正面三角面写入深度缓冲，物理遮挡背面线段
                GLES20.glUseProgram(clayProgram)
                val uMVP = GLES20.glGetUniformLocation(clayProgram, "uMVPMatrix")
                val uNormM = GLES20.glGetUniformLocation(clayProgram, "uNormalMatrix")
                val uColor = GLES20.glGetUniformLocation(clayProgram, "uBaseColor")
                val aPos = GLES20.glGetAttribLocation(clayProgram, "aPosition")
                val aNorm = GLES20.glGetAttribLocation(clayProgram, "aNormal")

                GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
                GLES20.glUniformMatrix3fv(uNormM, 1, false, normalMatrix, 0)
                GLES20.glUniform4f(uColor, 0.96f, 0.96f, 0.97f, 1.0f)

                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, posBuf)
                GLES20.glEnableVertexAttribArray(aNorm)
                GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, 0, normBuf)

                GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
                GLES20.glPolygonOffset(1.0f, 1.0f)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount * 3)
                GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)

                GLES20.glDisableVertexAttribArray(aPos)
                GLES20.glDisableVertexAttribArray(aNorm)

                // 2. 绘制纯净正面特征线框
                val wirePosBuf = ByteBuffer.allocateDirect(mesh.positions.size * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                        put(mesh.positions); position(0)
                    }
                val wireBuf = ByteBuffer.allocateDirect(mesh.wireframeIndices.size * 4)
                    .order(ByteOrder.nativeOrder()).asIntBuffer().apply {
                        put(mesh.wireframeIndices); position(0)
                    }

                GLES20.glUseProgram(wireframeProgram)
                val wMVP = GLES20.glGetUniformLocation(wireframeProgram, "uMVPMatrix")
                val wColor = GLES20.glGetUniformLocation(wireframeProgram, "uColor")
                val wPos = GLES20.glGetAttribLocation(wireframeProgram, "aPosition")

                val wc = state.wireframeColor
                val wireR = ((wc shr 16) and 0xFF) / 255f
                val wireG = ((wc shr 8) and 0xFF) / 255f
                val wireB = (wc and 0xFF) / 255f

                GLES20.glUniformMatrix4fv(wMVP, 1, false, mvp, 0)
                GLES20.glUniform4f(wColor, wireR, wireG, wireB, 1.0f)

                GLES20.glEnableVertexAttribArray(wPos)
                GLES20.glVertexAttribPointer(wPos, 3, GLES20.GL_FLOAT, false, 0, wirePosBuf)

                GLES20.glLineWidth(2.5f)
                GLES20.glDrawElements(GLES20.GL_LINES, mesh.wireframeIndices.size, GLES20.GL_UNSIGNED_INT, wireBuf)

                GLES20.glDisableVertexAttribArray(wPos)
            }
        }

        // 读取像素并写入目标 Bitmap
        val pixelBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuffer)

        val bmp = if (reusedBitmap != null && reusedBitmap.width == width && reusedBitmap.height == height && !reusedBitmap.isRecycled) {
            reusedBitmap
        } else {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }

        // OpenGL 读取的像素是上下倒置的，进行垂直行反转写回 Bitmap
        val rowBytes = width * 4
        val tempRow = ByteArray(rowBytes)
        val flippedBuffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until height) {
            pixelBuffer.position((height - 1 - y) * rowBytes)
            pixelBuffer.get(tempRow)
            flippedBuffer.put(tempRow)
        }
        flippedBuffer.position(0)
        bmp.copyPixelsFromBuffer(flippedBuffer)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return bmp
    }

    private fun createProgram(vertexSrc: String, fragmentSrc: String): Int {
        val vShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        if (vShader == 0 || fShader == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vShader)
        GLES20.glAttachShader(program, fShader)
        GLES20.glLinkProgram(program)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Program link error: " + GLES20.glGetProgramInfoLog(program))
            GLES20.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)

        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Shader compile error: " + GLES20.glGetShaderInfoLog(shader))
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    @Synchronized
    fun release() {
        if (!isInitialized) return

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        destroyFbo()

        if (clayProgram != 0) GLES20.glDeleteProgram(clayProgram)
        if (shadingProgram != 0) GLES20.glDeleteProgram(shadingProgram)
        if (wireframeProgram != 0) GLES20.glDeleteProgram(wireframeProgram)

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
        if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(eglDisplay)

        clayProgram = 0
        shadingProgram = 0
        wireframeProgram = 0
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        isInitialized = false
    }
}
