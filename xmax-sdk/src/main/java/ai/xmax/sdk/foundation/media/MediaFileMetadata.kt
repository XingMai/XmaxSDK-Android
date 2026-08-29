package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.VideoRotation

/** 本地媒体文件中视频、音频和时间线所需的元数据。 */
internal data class MediaFileMetadata(
    val width: Int,
    val height: Int,
    val rotation: VideoRotation,
    val durationUs: Long,
    val hasAudio: Boolean,
)
