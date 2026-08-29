package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat

/** 定义实时视频编码参数的配置能力。 */
internal interface EncodingControlling {
    /** 校验并应用实时视频编码格式。 */
    fun configure(videoFormat: RealtimeVideoFormat)
}
