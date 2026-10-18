//
//  NetworkVideoController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.RealtimeVideoSampleMethod
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.ui.unit.IntSize
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 网络参考视频只用于本地预览，服务端独立读取同一个 URL 进行生成。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 * @param mediaService 解析媒体尺寸及模型输入规则的服务。
 */
internal class NetworkVideoController(
    private val context: Context,
    private val mediaService: MediaServicing,
) {
    /**
     * 网络视频轨道、服务端输入信息及完成通知。
     */
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set
    @Volatile var reference: RealtimeReferenceVideo? = null
        private set
    @Volatile var onFinish: (() -> Unit)? = null
        private set

    /**
     * 网络视频的本地预览播放器。
     */
    private var preview: NetworkVideoPreview? = null

    /**
     * 创建由服务端读取的网络视频源及其静音本地预览。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param sampleMethod 服务端读取网络视频时采用的采样方式。
     * @param onFinish 网络视频任务完成的通知；null 表示不接收完成通知。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun create(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(currentTrack == null)
        val path = validateNetworkVideoUrl(uri.toString())
        videoFormat.validate()
        val size = mediaService.resolveMediaSize(IntSize(videoFormat.width, videoFormat.height), RealtimeMediaSource.CAMERA)
        val format = videoFormat.copy(width = size.width, height = size.height).also { it.validate() }
        val track = RealtimeVideoTrack("network-video", format)
        val player = NetworkVideoPreview(context, uri)
        preview = player
        reference = RealtimeReferenceVideo(path, sampleMethod)
        this@NetworkVideoController.onFinish = onFinish
        currentTrack = track
        VideoRenderRegistry.register(track, VideoRenderBinding(player::attach, player::detach))
        RealtimeMediaStream(StreamID.LOCAL.value, track)
    }

    /**
     * 停止网络视频本地预览，注销当前轨道并清除服务端视频参考。
     */
    suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        val track = currentTrack
        currentTrack = null
        reference = null
        onFinish = null
        cleanupResources(
            { track?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { preview?.release() },
            { preview = null },
            { track?.let(VideoRenderRegistry::unregister) },
        )
    }
}

/**
 * 取得可供服务端读取的 HTTP 或 HTTPS 视频地址，无效时抛出配置错误。
 *
 * @param value 需要规范化、校验或显示的文本。
 * @return 经校验的 HTTP 或 HTTPS 视频地址。
 */
internal fun validateNetworkVideoUrl(value: String): String {
    val parsed = runCatching { URI(value) }.getOrNull()
    if (parsed == null || parsed.scheme?.lowercase() !in setOf("http", "https") ||
        parsed.host.isNullOrBlank() || parsed.rawUserInfo != null
    ) {
        throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Network video requires a valid HTTP or HTTPS URL")
    }
    return value
}

/**
 * 所有播放器与 Surface 操作由主线程串行执行；预览异常不终止独立的服务端任务。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 * @param uri 应用有读取权限的媒体地址。
 */
private class NetworkVideoPreview(private val context: Context, private val uri: Uri) {
    /**
     * 播放器及其 Surface、纹理和视图绑定。
     */
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: XmaxVideoView? = null
    private var texture: TextureView? = null

    /**
     * 预览的显示模式和播放状态。
     */
    private var contentMode = VideoContentMode.FIT
    private var prepared = false
    private var completed = false

    /**
     * 视频显示尺寸，单位为像素。
     */
    private var videoWidth = 0
    private var videoHeight = 0

    /**
     * 将网络视频预览绑定到指定视图并应用显示模式。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun attach(view: XmaxVideoView, contentMode: VideoContentMode) {
        this.view = view
        this.contentMode = contentMode
        val texture = view.prepareNetworkVideoRendering()
        this.texture = texture
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            /**
             * 在预览纹理可用后建立视频输出绑定。
             *
             * @param value 预览使用的 SurfaceTexture。
             * @param width 画面宽度，单位为像素。
             * @param height 画面高度，单位为像素。
             */
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                if (this@NetworkVideoPreview.texture !== texture) return
                bindSurface(value)
                updateTransform()
            }

            /**
             * 在预览尺寸变化后更新视频布局。
             *
             * @param value 预览使用的 SurfaceTexture。
             * @param width 画面宽度，单位为像素。
             * @param height 画面高度，单位为像素。
             */
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) {
                updateTransform()
            }

            /**
             * 解除已销毁纹理的媒体绑定并决定纹理资源的释放归属。
             *
             * @param value 预览使用的 SurfaceTexture。
             * @return 是否由 TextureView 释放纹理的判定结果。
             */
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                if (this@NetworkVideoPreview.texture === texture) detachSurface()
                return true
            }

            /**
             * 接收纹理内容更新通知。
             *
             * @param value 预览使用的 SurfaceTexture。
             */
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
        }
        texture.surfaceTexture?.let(::bindSurface)
    }

    /**
     * 解除指定视图的网络视频预览绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    fun detach(view: XmaxVideoView) {
        if (this.view !== view) return
        detachSurface()
        texture?.surfaceTextureListener = null
        texture = null
        view.invalidateVideoPresentation()
        this.view = null
    }

    /**
     * 将播放器输出绑定到指定预览纹理。
     *
     * @param value 预览使用的 SurfaceTexture。
     */
    private fun bindSurface(value: SurfaceTexture) {
        runCatching {
            surface?.release()
            surface = Surface(value)
            val existing = player
            if (existing != null) {
                existing.setSurface(surface)
                resumePreview(existing)
            } else {
                val created = MediaPlayer()
                player = created
                created.setVolume(0f, 0f)
                created.isLooping = false
                created.setSurface(surface)
                created.setOnPreparedListener {
                    if (player === it) {
                        prepared = true
                        videoWidth = it.videoWidth
                        videoHeight = it.videoHeight
                        updateTransform()
                        resumePreview(it)
                    }
                }
                created.setOnVideoSizeChangedListener { _, width, height ->
                    videoWidth = width
                    videoHeight = height
                    updateTransform()
                }
                created.setOnCompletionListener { if (player === it) completed = true }
                created.setOnErrorListener { _, what, extra ->
                    previewFailed(IllegalStateException("Network preview error: $what/$extra"))
                    true
                }
                created.setDataSource(context, uri)
                created.prepareAsync()
            }
        }.onFailure(::previewFailed)
    }

    /**
     * 恢复本地视频预览播放。
     *
     * @param player 用于本地媒体预览和解码的播放器。
     */
    private fun resumePreview(player: MediaPlayer) {
        if (!prepared || surface == null) return
        if (completed) {
            /**
             * 重绑视图时恢复尾帧，不重新播放整段视频。
             */
            player.seekTo((player.duration - 1).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            player.start()
        }
    }

    /**
     * 按视频尺寸和显示模式更新预览变换。
     */
    private fun updateTransform() {
        val target = texture ?: return
        if (target.width <= 0 || target.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val widthRatio = target.width.toFloat() / videoWidth
        val heightRatio = target.height.toFloat() / videoHeight
        val scale = if (contentMode == VideoContentMode.FIT) minOf(widthRatio, heightRatio) else maxOf(widthRatio, heightRatio)
        target.setTransform(Matrix().apply {
            setScale(videoWidth * scale / target.width, videoHeight * scale / target.height,
                target.width / 2f, target.height / 2f)
        })
    }

    /**
     * 解除播放器与预览 Surface 的绑定。
     */
    private fun detachSurface() {
        player?.let {
            if (prepared && !completed) it.pause()
            it.setSurface(null)
        }
        surface?.release()
        surface = null
    }

    /**
     * 报告本地预览播放失败。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    private fun previewFailed(error: Throwable) {
        player?.release()
        player = null
        prepared = false
        XmaxLogger.realtime.warn(message = { "Network video preview failed: ${error.message}" })
    }

    /**
     * 释放网络视频播放器和本地预览资源。
     */
    fun release() {
        view?.let(::detach)
        player?.release()
        player = null
        prepared = false
    }
}
