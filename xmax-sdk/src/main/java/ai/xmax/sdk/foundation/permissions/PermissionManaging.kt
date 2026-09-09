package ai.xmax.sdk.foundation.permissions

/** 定义 SDK 所需平台权限的检查能力。 */
internal interface PermissionManaging {
    suspend fun ensureCameraPermission()

    suspend fun ensureMicrophonePermission()
}
