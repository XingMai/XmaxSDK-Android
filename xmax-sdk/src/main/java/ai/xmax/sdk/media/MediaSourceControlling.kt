package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxVideoView
import android.net.Uri

/** 定义本地视频文件准备、循环播放和预览能力。 */
internal interface MediaSourceControlling {
    val hasAudio: Boolean

    suspend fun prepare(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): MediaSourceConfiguration

    suspend fun start()

    suspend fun setLocalAudioPreviewMuted(muted: Boolean)

    suspend fun setLocalAudioVolume(volume: Float)

    fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode)

    fun detachPreview(view: XmaxVideoView)

    suspend fun stop()
}
