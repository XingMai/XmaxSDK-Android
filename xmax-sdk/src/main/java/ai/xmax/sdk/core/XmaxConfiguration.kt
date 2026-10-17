//
//  XmaxConfiguration.kt
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
 * SDK 客户端配置；API Key 在构造时去除首尾空白，实际校验由调用方或服务入口执行。
 *
 * @param apiKey 开放平台签发的 API Key；不得写入日志或对外展示。
 * @param loggerOptions 允许输出的 SDK 日志类别；默认值为 `XmaxLoggerOption.none`。
 */
public class XmaxConfiguration @JvmOverloads constructor(
    apiKey: String,

    /**
     * SDK 输出的日志类型；默认关闭。创建客户端时会更新 SDK 共享日志器的配置。
     */
    public val loggerOptions: XmaxLoggerOption = XmaxLoggerOption.none,
) {
    /**
     * 服务环境配置与公开快照。
     */
    private var configuredEnvironment: XmaxEnvironment = XmaxEnvironment.CHINA

    /**
     * SDK 连接的服务环境；默认使用国内环境。
     */
    public val environment: XmaxEnvironment
        get() = configuredEnvironment

    /**
     * 调用 Xmax 服务使用的 API Key。
     */
    public val apiKey: String = apiKey.trim()

    /**
     * 创建包含 API Key、服务环境和日志选项的客户端配置。
     *
     * @param apiKey 开放平台签发的 API Key；不得写入日志或对外展示。
     * @param environment 业务服务环境，同时决定日志语言。
     * @param loggerOptions 允许输出的 SDK 日志类别；默认值为 `XmaxLoggerOption.none`。
     */
    @JvmOverloads
    public constructor(
        apiKey: String,
        environment: XmaxEnvironment,
        loggerOptions: XmaxLoggerOption = XmaxLoggerOption.none,
    ) : this(apiKey, loggerOptions) {
        configuredEnvironment = environment
    }

    /**
     * 检查 API Key 是否为空；此处不向服务端验证凭据有效性。
     */
    public fun validate() {
        if (apiKey.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_API_KEY,
                message = "API key cannot be empty",
            )
        }
    }

    /**
     * 返回隐藏 API Key 的配置描述，可用于安全的诊断输出。
     *
     * @return 适合日志或诊断显示的对象描述。
     */
    override fun toString(): String =
        "XmaxConfiguration(apiKey=***, environment=$environment, loggerOptions=${loggerOptions.rawValue})"
}
