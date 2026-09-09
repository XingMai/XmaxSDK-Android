package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** 检查相机和麦克风权限；运行时权限申请由接入方的 Activity 负责。 */
internal class PermissionManager(
    private val isCameraPermissionGranted: () -> Boolean,
    private val isMicrophonePermissionGranted: () -> Boolean,
) : PermissionManaging {
    constructor(isCameraPermissionGranted: () -> Boolean) : this(
        isCameraPermissionGranted = isCameraPermissionGranted,
        isMicrophonePermissionGranted = { false },
    )

    constructor(context: Context) : this(
        isCameraPermissionGranted = {
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
        isMicrophonePermissionGranted = {
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        },
    )

    override suspend fun ensureCameraPermission() {
        if (isCameraPermissionGranted()) return
        throw XmaxError(
            code = XmaxErrorCode.CAMERA_PERMISSION_DENIED,
            message = "Camera permission is unavailable or was denied",
        )
    }

    override suspend fun ensureMicrophonePermission() {
        if (isMicrophonePermissionGranted()) return
        throw XmaxError(
            code = XmaxErrorCode.MICROPHONE_PERMISSION_DENIED,
            message = "Microphone permission is unavailable or was denied",
        )
    }
}
