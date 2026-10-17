//
//  EncodingControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.encoding

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * 定义实时视频编码参数的配置能力。
 */
internal interface EncodingControlling {
    /**
     * 校验并应用实时视频编码格式。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    fun configure(videoFormat: RealtimeVideoFormat)
}
