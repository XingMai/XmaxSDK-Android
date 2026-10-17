//
//  PermissionManaging.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.permissions

/**
 * 定义 SDK 所需平台权限的检查能力。
 */
internal interface PermissionManaging {
    /**
     * 确认应用已取得相机权限，否则抛出相机权限错误。
     */
    suspend fun ensureCameraPermission()

    /**
     * 确认应用已取得麦克风权限，否则抛出麦克风权限错误。
     */
    suspend fun ensureMicrophonePermission()
}
