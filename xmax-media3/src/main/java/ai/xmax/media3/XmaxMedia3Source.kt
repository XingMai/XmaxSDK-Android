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
 */
@UnstableApi
@MainThread
public class XmaxMedia3Source(context: Context) : RealtimeExternalVideoSource {
    @Volatile private var sink: RealtimeExternalFrameSink? = null
    private val bridge = DecoderSurfaceBridge { sink }
    private var player: ExoPlayer? = null
    private var preview: TextureView? = null
    private var previewHost: FrameLayout? = null
    private var volume = 0.45f
    private var muted = false
    private var playbackFailure: PlaybackException? = null
    /** 当前本地预览的首帧已经呈现；用于接入层收起视频封面。主线程回调。 */
    public var onPreviewFrame: (() -> Unit)? = null
    override val videoFormat: RealtimeVideoFormat
        get() {
            val size = checkNotNull(player).videoSize
            require(size.width > 0 && size.height > 0) { "Prepare ExoPlayer before binding it to XmaxSDK" }
            // 仅提供源视频的显示尺寸；生成输出尺寸由 SDK 按调用方配置和模型规则决定。
            val width = kotlin.math.round(size.width * size.pixelWidthHeightRatio).toInt().coerceAtLeast(1)
            return RealtimeVideoFormat(width, size.height, 30)
        }
    override val hasAudio: Boolean get() = player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_AUDIO) == true

    public val renderersFactory: DefaultRenderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            FrameAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false).build()) { sink }
    }
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) playbackFailure = null
        }
        override fun onPlayerError(error: PlaybackException) {
            playbackFailure = error
            sink?.reportError(error)
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            bridge.videoSize((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height)
        }
    }

    public suspend fun attachPlayer(player: ExoPlayer): Unit = withContext(Dispatchers.Main.immediate) {
        check(this@XmaxMedia3Source.player == null)
        val surface = bridge.surface()
        this@XmaxMedia3Source.player = player
        player.addListener(listener)
        player.setVideoSurface(surface)
        player.volume = if (muted) 0f else volume
    }

    override suspend fun start(sink: RealtimeExternalFrameSink) {
        check(this.sink == null)
        bridge.failure?.let { throw it }
        playbackFailure?.let { throw it }
        this.sink = sink
    }
    override suspend fun stop() { sink = null }

    override fun attachPreview(container: FrameLayout, contentMode: VideoContentMode) {
        previewHost?.let(::detachPreview)
        val view = TextureView(container.context)
        preview = view; previewHost = container
        var reported = false
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                bridge.preview(texture, width, height, contentMode)
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                bridge.preview(texture, width, height, contentMode)
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                bridge.destroyPreview(texture)
                return false
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (!reported && preview === view) {
                    reported = true
                    onPreviewFrame?.invoke()
                }
            }
        }
        container.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    override fun detachPreview(container: FrameLayout) {
        if (previewHost !== container) return
        preview?.let(container::removeView)
        preview = null; previewHost = null
    }
    override fun setPreviewAudio(volume: Float, muted: Boolean) {
        this.volume = volume; this.muted = muted
        player?.volume = if (muted) 0f else volume
    }

    public suspend fun release(): Unit = withContext(Dispatchers.Main.immediate) {
        sink = null
        onPreviewFrame = null
        previewHost?.let(::detachPreview)
        player?.removeListener(listener)
        player = null
        bridge.release()
    }

    /** 供验证性能读取，不包含媒体数据。 */
    public fun diagnostics(): String =
        "rendered=${bridge.renderedFrames}, captured=${bridge.capturedFrames}, maxCaptureMs=${bridge.maximumCaptureMs}, error=${bridge.failure?.javaClass?.simpleName}"
}
