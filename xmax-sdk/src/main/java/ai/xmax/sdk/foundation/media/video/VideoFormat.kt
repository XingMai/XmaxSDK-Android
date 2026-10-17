//
//  VideoFormat.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 表示视频帧的固定尺寸和像素格式。
 *
 * @param width 画面宽度，单位为像素。
 * @param height 画面高度，单位为像素。
 * @param pixelFormat 视频像素数据的排列格式。
 */
internal data class VideoFormat(
    val width: Int,
    val height: Int,
    val pixelFormat: VideoPixelFormat,
) {
    init {
        if (width <= 0 || height <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video width and height must be positive integers",
            )
        }
    }
}
