//
//  RealtimeMediaSource.kt
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
 * 实时生成模型支持的本地媒体来源。
 *
 * @param id 用于区分当前对象或操作的唯一标识。
 */
public enum class RealtimeMediaSource(public val id: String) {
    /**
     * 摄像头输入。
     */
    CAMERA("camera"),

    /**
     * 视频输入。
     */
    VIDEO("video"),

    /**
     * 图片输入。
     */
    IMAGE("image"),
}
