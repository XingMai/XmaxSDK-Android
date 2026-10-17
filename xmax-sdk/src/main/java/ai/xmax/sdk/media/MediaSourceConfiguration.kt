//
//  MediaSourceConfiguration.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat

/**
 * 本地视频文件准备完成后的输出配置。
 *
 * @param videoFormat 视频尺寸、帧率及上行编码参数。
 * @param hasAudio 输入媒体是否包含可用音频。
 */
internal data class MediaSourceConfiguration(
    val videoFormat: RealtimeVideoFormat,
    val hasAudio: Boolean,
)
