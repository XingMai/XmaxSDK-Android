package ai.xmax.sdk.foundation.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/** SDK 需要检查的媒体权限。 */
internal enum class MediaPermission {
    CAMERA,
    MICROPHONE,
}

/** 平台媒体权限的中性授权状态。 */
internal enum class MediaAuthorizationStatus {
    NOT_DETERMINED,
    RESTRICTED,
    DENIED,
    AUTHORIZED,
}

/** 隔离 Android 权限 API，便于 Manager 保持中性且可测试。 */
internal class PermissionAuthorizationClient(
    val authorizationStatus: (MediaPermission) -> MediaAuthorizationStatus,
    val requestAccess: suspend (MediaPermission) -> Boolean,
) {
    companion object {
        fun live(context: Context): PermissionAuthorizationClient = PermissionAuthorizationClient(
            authorizationStatus = { permission ->
                if (context.checkSelfPermission(permission.androidPermission) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    MediaAuthorizationStatus.AUTHORIZED
                } else {
                    MediaAuthorizationStatus.DENIED
                }
            },
            requestAccess = { false },
        )
    }
}

private val MediaPermission.androidPermission: String
    get() = when (this) {
        MediaPermission.CAMERA -> Manifest.permission.CAMERA
        MediaPermission.MICROPHONE -> Manifest.permission.RECORD_AUDIO
    }
