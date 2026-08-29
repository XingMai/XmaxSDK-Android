package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.foundation.media.image.DecodedImage
import android.graphics.Bitmap
import android.net.Uri

/** 定义本地图片持续输出视频帧的能力。 */
internal interface ImageSourceControlling {
    suspend fun prepare(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    suspend fun prepare(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    suspend fun prepare(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    suspend fun prepare(
        decodedImage: DecodedImage,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    fun start()

    fun stop()
}
