package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context

/** 检查 SDK 使用相机和麦克风所需的平台权限。 */
internal class PermissionManager(
    private val authorizationClient: PermissionAuthorizationClient,
) : PermissionManaging {
    constructor(context: Context) : this(PermissionAuthorizationClient.live(context))

    override suspend fun ensureCameraPermission() {
        ensurePermission(
            permission = MediaPermission.CAMERA,
            errorCode = XmaxErrorCode.CAMERA_PERMISSION_DENIED,
            errorMessage = "Camera permission is unavailable or was denied",
        )
    }

    override suspend fun ensureMicrophonePermission() {
        ensurePermission(
            permission = MediaPermission.MICROPHONE,
            errorCode = XmaxErrorCode.MICROPHONE_PERMISSION_DENIED,
            errorMessage = "Microphone permission is unavailable or was denied",
        )
    }

    private suspend fun ensurePermission(
        permission: MediaPermission,
        errorCode: XmaxErrorCode,
        errorMessage: String,
    ) {
        when (authorizationClient.authorizationStatus(permission)) {
            MediaAuthorizationStatus.AUTHORIZED -> return
            MediaAuthorizationStatus.NOT_DETERMINED -> {
                if (runCatching { authorizationClient.requestAccess(permission) }.getOrDefault(false)) {
                    return
                }
            }
            MediaAuthorizationStatus.RESTRICTED,
            MediaAuthorizationStatus.DENIED,
            -> Unit
        }
        throw XmaxError(code = errorCode, message = errorMessage)
    }
}
