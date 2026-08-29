package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxVideoView
import android.net.Uri

/** 定义基于统一媒体时间轴的文件音视频输出能力。 */
internal interface VideoPlayerControlling {
    suspend fun configure(
        uri: Uri,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        frameRate: Int,
        hasAudio: Boolean,
        durationUs: Long,
    )

    suspend fun start()

    suspend fun setLocalAudioPreviewMuted(muted: Boolean)

    suspend fun setLocalAudioVolume(volume: Float)

    fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode)

    fun detachPreview(view: XmaxVideoView)

    suspend fun stop()
}
