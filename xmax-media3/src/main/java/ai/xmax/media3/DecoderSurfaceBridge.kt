//
//  DecoderSurfaceBridge.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import ai.xmax.sdk.RealtimeExternalFrameSink
import ai.xmax.sdk.VideoContentMode
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred

/**
 * 单个解码 Surface：GL 线程分发到本地预览和 RTC 像素输入，不做主线程截图。
 *
 * @param input 取得当前外部音视频帧接收器的函数。
 */
internal class DecoderSurfaceBridge(private val input: () -> RealtimeExternalFrameSink?) {
    /**
     * OpenGL 绘制线程、任务派发与就绪等待。
     */
    private val thread = HandlerThread("Xmax-Media3-GL").apply { start() }
    private val handler = Handler(thread.looper)
    private val ready = CompletableDeferred<Surface>()

    /**
     * EGL 上下文、配置及绘制表面。
     */
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var pbuffer = EGL14.EGL_NO_SURFACE
    private var window = EGL14.EGL_NO_SURFACE
    private lateinit var config: EGLConfig

    /**
     * 解码纹理、着色程序及离屏帧缓冲。
     */
    private var textureId = 0
    private var program = 0
    private var frameTexture = 0
    private var framebuffer = 0

    /**
     * 外部视频帧读取的尺寸与像素缓冲。
     */
    private var captureWidth = 0
    private var captureHeight = 0
    private var pixels: ByteBuffer? = null

    /**
     * 解码器输出与本地预览的 Surface 绑定。
     */
    private var decoderTexture: SurfaceTexture? = null
    private var decoderSurface: Surface? = null
    private var previewSurface: Surface? = null

    /**
     * 预览容器和视频显示尺寸。
     */
    private var previewWidth = 1
    private var previewHeight = 1
    private var videoWidth = 1
    private var videoHeight = 1

    /**
     * 预览显示模式及纹理坐标变换。
     */
    private var contentMode = VideoContentMode.FILL
    private val transform = FloatArray(16)

    /**
     * 解码帧时间戳与外部供帧节奏。
     */
    private var lastTextureTimestamp = Long.MIN_VALUE
    private var nextCaptureUs = 0L

    /**
     * 绘制全屏纹理使用的顶点数据。
     */
    private val vertices = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); flip()
    }

    /**
     * 资源释放状态与处理性能统计。
     */
    @Volatile private var closed = false
    @Volatile var renderedFrames = 0L
        private set
    @Volatile var capturedFrames = 0L
        private set
    @Volatile var maximumCaptureMs = 0L
        private set

    /**
     * 异步图像处理失败结果。
     */
    @Volatile var failure: Throwable? = null
        private set

    init { handler.post { guard { initialize(); ready.complete(checkNotNull(decoderSurface)) } } }

    /**
     * 取得用于接收解码视频的 Surface。
     *
     * @return 接收视频解码输出的 Surface。
     */
    suspend fun surface(): Surface = ready.await()

    /**
     * 更新解码视频的实际尺寸。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     */
    fun videoSize(width: Int, height: Int) { handler.post { videoWidth = width.coerceAtLeast(1); videoHeight = height.coerceAtLeast(1) } }

    /**
     * 设置预览输出纹理、尺寸和显示模式。
     *
     * @param texture 接收预览画面的纹理。
     * @param width 画面宽度，单位为像素；默认值为 `1`。
     * @param height 画面高度，单位为像素；默认值为 `1`。
     * @param mode 视频等比适配或等比裁切的显示方式；默认值为 `VideoContentMode.FILL`。
     */
    fun preview(texture: SurfaceTexture?, width: Int = 1, height: Int = 1, mode: VideoContentMode = VideoContentMode.FILL) {
        handler.post { guard {
            makeCurrent(pbuffer)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
            previewSurface?.release(); previewSurface = null
            previewWidth = width.coerceAtLeast(1); previewHeight = height.coerceAtLeast(1); contentMode = mode
            if (texture != null) {
                previewSurface = Surface(texture)
                window = EGL14.eglCreateWindowSurface(display, config, previewSurface, intArrayOf(EGL14.EGL_NONE), 0)
                check(window != EGL14.EGL_NO_SURFACE) { "Cannot create preview EGL surface" }
            }
        } }
    }

    /**
     * 解除预览的 EGL 绑定并释放对应纹理资源。
     *
     * @param texture 接收预览画面的纹理。
     */
    fun destroyPreview(texture: SurfaceTexture) {
        handler.post { guard {
            makeCurrent(pbuffer)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
            previewSurface?.release(); previewSurface = null
            texture.release()
        } }
    }

    /**
     * 停止纹理输出并释放 EGL、OpenGL 和 Surface 资源。
     */
    suspend fun release() {
        if (closed) return
        val done = CompletableDeferred<Unit>()
        handler.post {
            try {
                closed = true
                decoderTexture?.setOnFrameAvailableListener(null)
                if (display != EGL14.EGL_NO_DISPLAY) {
                    makeCurrent(pbuffer)
                    if (program != 0) GLES20.glDeleteProgram(program)
                    GLES20.glDeleteTextures(2, intArrayOf(textureId, frameTexture), 0)
                    GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                    if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                    decoderSurface?.release(); decoderTexture?.release(); previewSurface?.release()
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroySurface(display, pbuffer)
                    EGL14.eglDestroyContext(display, context)
                    EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
                }
                done.complete(Unit)
            } catch (error: Exception) { done.completeExceptionally(error) }
            finally { thread.quitSafely() }
        }
        done.await()
    }

    /**
     * 创建用于解码纹理、预览绘制和像素读取的 EGL 与 OpenGL 资源。
     */
    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, IntArray(1), 0))
        config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
        textureId = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        program = GLES20.glCreateProgram()
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPosition;
            uniform mat4 uTransform;
            uniform vec2 uScale;
            varying vec2 vTex;
            void main() {
                gl_Position = vec4(aPosition * uScale, 0.0, 1.0);
                vTex = (uTransform * vec4((aPosition + 1.0) * 0.5, 0.0, 1.0)).xy;
            }
        """.trimIndent())
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(uTexture, vTex); }
        """.trimIndent())
        GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
        val linked = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        decoderTexture = SurfaceTexture(textureId).apply {
            setOnFrameAvailableListener({ handler.post { guard { render() } } }, handler)
        }
        decoderSurface = Surface(decoderTexture)
    }

    /**
     * 将解码器的最新视频纹理绘制到预览表面。
     */
    private fun render() {
        makeCurrent(pbuffer)
        val texture = decoderTexture ?: return
        texture.updateTexImage()
        if (texture.timestamp == lastTextureTimestamp) return
        lastTextureTimestamp = texture.timestamp
        texture.getTransformMatrix(transform)
        val sink = input()?.takeIf { it.isRequestingFrames }
        val nowUs = SystemClock.elapsedRealtimeNanos() / 1000
        if (sink != null && nowUs + 2_000 >= nextCaptureUs) {
            val intervalUs = 1_000_000L / sink.videoFormat.fps
            nextCaptureUs = if (nowUs - nextCaptureUs > intervalUs * 2) nowUs + intervalUs else nextCaptureUs + intervalUs
            val started = SystemClock.elapsedRealtime()
            capture(sink, nowUs)
            capturedFrames++
            maximumCaptureMs = maxOf(maximumCaptureMs, SystemClock.elapsedRealtime() - started)
        }
        if (window != EGL14.EGL_NO_SURFACE) {
            makeCurrent(window)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            draw(previewWidth, previewHeight, contentMode)
            check(EGL14.eglSwapBuffers(display, window)) { "Preview EGL swap failed" }
        }
        renderedFrames++
    }

    /**
     * 读取当前视频纹理并向外部帧接收器提交 RGBA 帧。
     *
     * @param sink SDK 提供的外部音视频帧接收器。
     * @param timestampUs 帧的单调时间戳，单位为微秒。
     */
    private fun capture(sink: RealtimeExternalFrameSink, timestampUs: Long) {
        val width = sink.videoFormat.width; val height = sink.videoFormat.height
        if (width != captureWidth || height != captureHeight) {
            GLES20.glDeleteTextures(1, intArrayOf(frameTexture), 0)
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
            frameTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameTexture)
            textureParameters(GLES20.GL_TEXTURE_2D)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            framebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, frameTexture, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE)
            pixels = ByteBuffer.allocateDirect(width * height * 4)
            captureWidth = width; captureHeight = height
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        draw(width, height, VideoContentMode.FILL)
        val buffer = checkNotNull(pixels).apply { clear() }
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Video pixel readback failed" }
        val rgba = ByteArray(width * height * 4)
        val stride = width * 4
        for (row in 0 until height) {
            buffer.position((height - row - 1) * stride)
            buffer.get(rgba, row * stride, stride)
        }
        sink.pushVideo(rgba, timestampUs)
    }

    /**
     * 按指定输出尺寸和显示模式绘制当前纹理。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param mode 视频等比适配或等比裁切的显示方式。
     */
    private fun draw(width: Int, height: Int, mode: VideoContentMode) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        val ratio = (videoWidth.toFloat() / videoHeight) / (width.toFloat() / height)
        val sx = if (mode == VideoContentMode.FILL) maxOf(1f, ratio) else minOf(1f, ratio)
        val sy = if (mode == VideoContentMode.FILL) maxOf(1f, 1f / ratio) else minOf(1f, 1f / ratio)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uScale"), sx, sy)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTransform"), 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
    }

    /**
     * 编译指定类型的 OpenGL 着色器，失败时报告错误。
     *
     * @param type OpenGL 着色器类型。
     * @param source 待编译的着色器源码。
     * @return 编译成功的 OpenGL 着色器标识。
     */
    private fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, source); GLES20.glCompileShader(it)
        val status = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES20.glGetShaderInfoLog(it) }
    }

    /**
     * 为纹理设置采样与边界处理参数。
     *
     * @param target OpenGL 纹理目标类型。
     */
    private fun textureParameters(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    /**
     * 将指定 EGL 表面设为当前绘制目标。
     *
     * @param surface 作为当前绘制目标的 EGL 表面。
     */
    private fun makeCurrent(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display, surface, surface, context)) }

    /**
     * 在 OpenGL 操作失败时报告错误，防止异常丢失。
     *
     * @param action 需要执行的操作。
     */
    private fun guard(action: () -> Unit) {
        if (closed) return
        try { action() } catch (error: Exception) {
            failure = error
            ready.completeExceptionally(error)
            input()?.reportError(error)
        }
    }
}
