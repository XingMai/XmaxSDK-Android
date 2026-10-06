package ai.xmax.sdk.media.video

import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeReferenceVideo
import ai.xmax.sdk.RealtimeVideoFormat
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

/** 网络参考视频只用于本地预览，服务端独立读取同一个 URL 进行生成。 */
internal class NetworkVideoController(
    private val context: Context,
    private val mediaService: MediaServicing,
) {
    @Volatile var currentTrack: RealtimeVideoTrack? = null
        private set
    @Volatile var reference: RealtimeReferenceVideo? = null
        private set
    @Volatile var onFinish: (() -> Unit)? = null
        private set
    private var preview: NetworkVideoPreview? = null

    suspend fun create(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = withContext(Dispatchers.Main.immediate) {
        check(currentTrack == null)
        val path = validateNetworkVideoUrl(uri.toString())
        videoFormat.validate()
        val size = mediaService.resolveModelInputSize(IntSize(videoFormat.width, videoFormat.height))
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

internal fun validateNetworkVideoUrl(value: String): String {
    val parsed = runCatching { URI(value) }.getOrNull()
    if (parsed == null || parsed.scheme?.lowercase() !in setOf("http", "https") ||
        parsed.host.isNullOrBlank() || parsed.rawUserInfo != null
    ) {
        throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Network video requires a valid HTTP or HTTPS URL")
    }
    return value
}

/** 所有播放器与 Surface 操作由主线程串行执行；预览异常不终止独立的服务端任务。 */
private class NetworkVideoPreview(private val context: Context, private val uri: Uri) {
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var view: XmaxVideoView? = null
    private var texture: TextureView? = null
    private var contentMode = VideoContentMode.FIT
    private var prepared = false
    private var completed = false
    private var videoWidth = 0
    private var videoHeight = 0

    fun attach(view: XmaxVideoView, contentMode: VideoContentMode) {
        this.view = view
        this.contentMode = contentMode
        val texture = view.prepareNetworkVideoRendering()
        this.texture = texture
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                if (this@NetworkVideoPreview.texture !== texture) return
                bindSurface(value)
                updateTransform()
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) {
                updateTransform()
            }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                if (this@NetworkVideoPreview.texture === texture) detachSurface()
                return true
            }
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) = Unit
        }
        texture.surfaceTexture?.let(::bindSurface)
    }

    fun detach(view: XmaxVideoView) {
        if (this.view !== view) return
        detachSurface()
        texture?.surfaceTextureListener = null
        texture = null
        view.invalidateVideoPresentation()
        this.view = null
    }

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

    private fun resumePreview(player: MediaPlayer) {
        if (!prepared || surface == null) return
        if (completed) {
            // 重绑视图时恢复尾帧，不重新播放整段视频。
            player.seekTo((player.duration - 1).coerceAtLeast(0).toLong(), MediaPlayer.SEEK_CLOSEST)
        } else {
            player.start()
        }
    }

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

    private fun detachSurface() {
        player?.let {
            if (prepared && !completed) it.pause()
            it.setSurface(null)
        }
        surface?.release()
        surface = null
    }

    private fun previewFailed(error: Throwable) {
        player?.release()
        player = null
        prepared = false
        XmaxLogger.realtime.warn(message = { "Network video preview failed: ${error.message}" })
    }

    fun release() {
        view?.let(::detach)
        player?.release()
        player = null
        prepared = false
    }
}
