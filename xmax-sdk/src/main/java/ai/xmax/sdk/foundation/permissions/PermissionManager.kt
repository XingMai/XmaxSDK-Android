//
//  PermissionManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * 检查相机和麦克风权限；运行时权限申请由接入方的 Activity 负责。
 *
 * @param isCameraPermissionGranted 查询应用是否已获得相机权限的函数。
 * @param isMicrophonePermissionGranted 查询应用是否已获得录音权限的函数。
 */
internal class PermissionManager(
    private val isCameraPermissionGranted: () -> Boolean,
    private val isMicrophonePermissionGranted: () -> Boolean,
) : PermissionManaging {
    /**
     * 创建 PermissionManager，并配置其依赖和运行参数。
     *
     * @param isCameraPermissionGranted 查询应用是否已获得相机权限的函数。
     */
    constructor(isCameraPermissionGranted: () -> Boolean) : this(
        isCameraPermissionGranted = isCameraPermissionGranted,
        isMicrophonePermissionGranted = { false },
    )

    /**
     * 创建 PermissionManager，并配置其依赖和运行参数。
     *
     * @param context 访问 Android 系统服务或媒体资源所需的上下文。
     */
    constructor(context: Context) : this(
        isCameraPermissionGranted = {
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        isMicrophonePermissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
    )

    /**
     * 确认应用已取得相机权限，否则抛出相机权限错误。
     */
    override suspend fun ensureCameraPermission() {
        if (isCameraPermissionGranted()) return
        throw XmaxError(
            code = XmaxErrorCode.CAMERA_PERMISSION_DENIED,
            message = "Camera permission is unavailable or was denied",
        )
    }

    /**
     * 确认应用已取得麦克风权限，否则抛出麦克风权限错误。
     */
    override suspend fun ensureMicrophonePermission() {
        if (isMicrophonePermissionGranted()) return
        throw XmaxError(
            code = XmaxErrorCode.MICROPHONE_PERMISSION_DENIED,
            message = "Microphone permission is unavailable or was denied",
        )
    }
}
