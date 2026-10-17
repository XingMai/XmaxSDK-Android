//
//  XmaxEnvironment.kt
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
 * Xmax 服务环境，在创建客户端时选择。
 */
public enum class XmaxEnvironment {
    /**
     * 国内环境。
     */
    CHINA,

    /**
     * 海外环境。
     */
    GLOBAL;

    /**
     * 当前环境的服务入口。
     */
    internal val apiBaseUrl: String
        get() = when (this) {
            CHINA -> "https://cloud.xmax.22duck.cn/open/api/v1"
            GLOBAL -> "https://api.xmax.cloud/open/api/v1"
        }
}
