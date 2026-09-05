package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** 检查相机权限；运行时权限申请由接入方的 Activity 负责。 */
internal class PermissionManager(
    private val isCameraPermissionGranted: () -> Boolean,
) : PermissionManaging {
    constructor(context: Context) : this(
        isCameraPermissionGranted = {
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        },
    )

    override suspend fun ensureCameraPermission() {
        if (isCameraPermissionGranted()) return
        throw XmaxError(
            code = XmaxErrorCode.CAMERA_PERMISSION_DENIED,
            message = "Camera permission is unavailable or was denied",
        )
    }
}
