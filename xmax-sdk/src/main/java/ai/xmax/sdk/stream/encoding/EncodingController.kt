package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration

/** 根据实时视频格式配置 RTC 视频编码参数。 */
internal class EncodingController(
    private val rtcManager: RtcManaging,
) : EncodingControlling {
    override fun configure(videoFormat: RealtimeVideoFormat) {
        videoFormat.validate()
        rtcManager.configureVideoEncoding(
            VideoEncodingConfiguration(
                width = videoFormat.width,
                height = videoFormat.height,
                frameRate = videoFormat.fps,
            ),
        )
    }
}
