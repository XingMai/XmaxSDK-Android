//
//  MediaFileMetadata.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.VideoRotation

/**
 * 本地媒体文件中视频、音频和时间线所需的元数据。
 *
 * @param width 画面宽度，单位为像素。
 * @param height 画面高度，单位为像素。
 * @param rotation 应用到源画面的旋转方向。
 * @param durationUs 媒体持续时间，单位为微秒。
 * @param hasAudio 输入媒体是否包含可用音频。
 */
internal data class MediaFileMetadata(
    val width: Int,
    val height: Int,
    val rotation: VideoRotation,
    val durationUs: Long,
    val hasAudio: Boolean,
)
