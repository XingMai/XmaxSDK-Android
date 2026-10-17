//
//  RuntimeInfo.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.runtime

import ai.xmax.sdk.XmaxSdk
import android.os.Build
import org.json.JSONObject

/**
 * API 请求和房间信令共用的 SDK 运行环境快照。
 *
 * @param platform 客户端平台名称。
 * @param osVersion 操作系统版本。
 * @param sdkVersion XmaxSDK 版本。
 * @param deviceModel 设备型号。
 */
internal data class RuntimeInfo(
    val platform: String,
    val osVersion: String,
    val sdkVersion: String,
    val deviceModel: String,
) {
    /**
     * 将运行环境信息转换为供服务端请求携带的 JSON 对象。
     *
     * @return 供当前业务使用的 JSON 数据。
     */
    fun toJson(): JSONObject = JSONObject()
        .put("platform", platform)
        .put("os_version", osVersion)
        .put("sdk_version", sdkVersion)
        .put("device_model", deviceModel)

    /**
     * 提供 RuntimeInfo 使用的构造入口、共享配置或常量。
     */
    companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
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
