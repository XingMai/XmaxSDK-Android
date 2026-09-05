package ai.xmax.sdk.foundation.runtime

import ai.xmax.sdk.XmaxSdk
import android.os.Build
import org.json.JSONObject

/** API 请求和房间信令共用的 SDK 运行环境快照。 */
internal data class RuntimeInfo(
    val platform: String,
    val osVersion: String,
    val sdkVersion: String,
    val deviceModel: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform)
        .put("os_version", osVersion)
        .put("sdk_version", sdkVersion)
        .put("device_model", deviceModel)

    companion object {
        val current: RuntimeInfo by lazy {
            RuntimeInfo(
                platform = "android",
                osVersion = Build.VERSION.RELEASE.orEmpty().trim().ifEmpty { "unknown" },
                sdkVersion = XmaxSdk.VERSION,
                deviceModel = Build.MODEL.orEmpty().trim().ifEmpty { "unknown" },
            )
        }
    }
}
