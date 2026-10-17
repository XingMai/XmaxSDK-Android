//
//  XmaxMedia3Source.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.media3

import ai.xmax.sdk.*
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 验证版 Media3 适配器，支持 SDR、常速、PCM16 音频；播放器由调用方创建和释放。
 * 创建 ExoPlayer 时传入 renderersFactory，再调用 attachPlayer；同一播放器只能绑定一个视频输出。
 * SDK stop/close 仅解绑帧入口。最终先释放 ExoPlayer，再调用 release 释放 GL 资源。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 */
@UnstableApi
@MainThread
public class XmaxMedia3Source(context: Context) : RealtimeExternalVideoSource {
    /**
     * 外部帧接收器、视频纹理桥接和播放器。
     */
    @Volatile private var sink: RealtimeExternalFrameSink? = null
    private val bridge = DecoderSurfaceBridge { sink }
    private var player: ExoPlayer? = null

    /**
     * 本地预览视图及其容器。
     */
    private var preview: TextureView? = null
    private var previewHost: FrameLayout? = null

    /**
     * 本地音频预览的音量与静音配置。
     */
    private var volume = 0.45f
    private var muted = false

    /**
     * 播放失败状态及预览首帧通知。
     */
    private var playbackFailure: PlaybackException? = null

    /**
     * 当前本地预览的首帧已经呈现；用于接入层收起视频封面。主线程回调。
     */
    public var onPreviewFrame: (() -> Unit)? = null

    /**
     * 外部输入的视频规格和音轨能力。
     */
    override val videoFormat: RealtimeVideoFormat
        get() {
            val size = checkNotNull(player).videoSize
            require(size.width > 0 && size.height > 0) { "Prepare ExoPlayer before binding it to XmaxSDK" }
            /**
             * 仅提供源视频的显示尺寸；生成输出尺寸由 SDK 按调用方配置和模型规则决定。
             */
            val width = kotlin.math.round(size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            return RealtimeVideoFormat(width, size.height, 30)
        }
    override val hasAudio: Boolean get() = player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_AUDIO) == true

    /**
     * 播放器音视频接收组件和事件监听器。
     */
    public val renderersFactory: DefaultRenderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
        /**
         * 创建同时支持本地播放与外部 PCM 帧输出的音频接收器。
         *
         * @param context 访问 Android 系统服务或媒体资源所需的上下文。
         * @param enableFloatOutput 是否允许播放器使用浮点音频输出。
         * @param enableAudioOutputPlaybackParams 是否允许音频输出应用播放速度等参数。
         * @return 支持 PCM 帧输出的本地音频播放接收器。
         */
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            FrameAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false).build()) { sink }
    }
    private val listener = object : Player.Listener {
        /**
         * 根据播放器状态完成媒体准备或播放状态更新。
         *
         * @param playbackState Media3 播放器当前状态。
         */
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) playbackFailure = null
        }

        /**
         * 将播放器错误报告给外部视频源的使用方。
         *
         * @param error 本次操作失败的异常或 SDK 错误。
         */
        override fun onPlayerError(error: PlaybackException) {
            playbackFailure = error
            sink?.reportError(error)
        }

        /**
         * 更新视频预览和帧输出使用的显示尺寸。
         *
         * @param videoSize 视频经过旋转后的显示尺寸。
         */
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            bridge.videoSize((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height)
        }
    }

    /**
     * 将播放器绑定到当前外部媒体源。
     *
     * @param player 用于本地媒体预览和解码的播放器。
     */
    public suspend fun attachPlayer(player: ExoPlayer): Unit = withContext(Dispatchers.Main.immediate) {
        check(this@XmaxMedia3Source.player == null)
        val surface = bridge.surface()
        this@XmaxMedia3Source.player = player
        player.addListener(listener)
        player.setVideoSurface(surface)
        player.volume = if (muted) 0f else volume
    }

    /**
     * 绑定供帧入口，返回前完成绑定，不等待生成结束。
     * 接入方负责旋转、裁剪和缩放，按 sink.videoFormat 输出；供帧不能等待 startGeneration 返回。
     * 采集源结束或发生不可恢复错误时调用 sink.reportError；绑定失败时 SDK 仍可能调用 stop。
     *
     * @param sink SDK 提供的外部音视频帧接收器。
     */
    override suspend fun start(sink: RealtimeExternalFrameSink) {
        check(this.sink == null)
        bridge.failure?.let { throw it }
        playbackFailure?.let { throw it }
        this.sink = sink
    }

    /**
     * 停止向 SDK 供帧并清除 sink，必须幂等；不代表关闭接入方的摄像头或播放器。
     */
    override suspend fun stop() { sink = null }

    /**
     * 将播放器预览挂载到 SDK 视频容器。
     *
     * @param container 承载本地预览视图的 Android 容器。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    override fun attachPreview(container: FrameLayout, contentMode: VideoContentMode) {
        previewHost?.let(::detachPreview)
        val view = TextureView(container.context)
        preview = view; previewHost = container
        var reported = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            /**
             * 在预览纹理可用后建立视频输出绑定。
             *
             * @param texture 接收预览画面的纹理。
             * @param width 画面宽度，单位为像素。
             * @param height 画面高度，单位为像素。
             */
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                bridge.preview(texture, width, height, contentMode)
            }

            /**
             * 在预览尺寸变化后更新视频布局。
             *
             * @param texture 接收预览画面的纹理。
             * @param width 画面宽度，单位为像素。
             * @param height 画面高度，单位为像素。
             */
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                bridge.preview(texture, width, height, contentMode)
            }

            /**
             * 解除已销毁纹理的媒体绑定并决定纹理资源的释放归属。
             *
             * @param texture 接收预览画面的纹理。
             * @return false，由纹理桥接器在解除 EGL 绑定后释放纹理。
             */
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                bridge.destroyPreview(texture)
                return false
            }

            /**
             * 接收纹理内容更新通知。
             *
             * @param texture 接收预览画面的纹理。
             */
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (!reported && preview === view) {
                    reported = true
                    onPreviewFrame?.invoke()
                }
            }
        }
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    /**
     * 移除对应容器中的播放器预览，不停止播放器。
     *
     * @param container 承载本地预览视图的 Android 容器。
     */
    override fun detachPreview(container: FrameLayout) {
        if (previewHost !== container) return
        preview?.let(container::removeView)
        preview = null; previewHost = null
    }

    /**
     * 设置播放器的本地监听音量与静音状态，不改变交给 SDK 的 PCM 数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     * @param muted 是否静音。
     */
    override fun setPreviewAudio(volume: Float, muted: Boolean) {
        this.volume = volume; this.muted = muted
        player?.volume = if (muted) 0f else volume
    }

    /**
     * 解除外部源与播放器、预览及解码纹理的绑定。
     */
    public suspend fun release(): Unit = withContext(Dispatchers.Main.immediate) {
        sink = null
        onPreviewFrame = null
        previewHost?.let(::detachPreview)
        player?.removeListener(listener)
        player = null
        bridge.release()
    }

    /**
     * 供验证性能读取，不包含媒体数据。
     *
     * @return 不包含媒体内容的处理性能统计文本。
     */
    public fun diagnostics(): String =
        "rendered=${bridge.renderedFrames}, captured=${bridge.capturedFrames}, maxCaptureMs=${bridge.maximumCaptureMs}, error=${bridge.failure?.javaClass?.simpleName}"
}
