package ai.xmax.sdk.foundation.rtc

/** RTC 主视频流编码参数。 */
internal data class VideoEncodingConfiguration(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val minimumBitrate: Int = 0,
    val maximumBitrate: Int = -1,
)
