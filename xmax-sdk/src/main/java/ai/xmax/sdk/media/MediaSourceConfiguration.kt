package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat

/** 本地视频文件准备完成后的输出配置。 */
internal data class MediaSourceConfiguration(
    val videoFormat: RealtimeVideoFormat,
    val hasAudio: Boolean,
)
