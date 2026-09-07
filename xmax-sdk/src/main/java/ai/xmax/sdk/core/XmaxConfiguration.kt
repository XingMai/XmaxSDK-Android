package ai.xmax.sdk

/** SDK 客户端配置；API Key 在构造时去除首尾空白，实际校验由调用方或服务入口执行。 */
public class XmaxConfiguration @JvmOverloads constructor(
    apiKey: String,
    /** SDK 连接的服务环境；默认使用国内环境。 */
    public val environment: XmaxEnvironment = XmaxEnvironment.CHINA,
    /** SDK 输出的日志类型；默认关闭。创建客户端时会更新 SDK 共享日志器的配置。 */
    public val loggerOptions: XmaxLoggerOption = XmaxLoggerOption.none,
) {
    /** 调用 Xmax 服务使用的 API Key。 */
    public val apiKey: String = apiKey.trim()

    /** 检查 API Key 是否为空；此处不向服务端验证凭据有效性。 */
    public fun validate() {
        if (apiKey.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_API_KEY,
                message = "API key cannot be empty",
            )
        }
    }

    /** 调试输出隐藏 API Key，避免配置对象被打印时泄露凭据。 */
    override fun toString(): String =
        "XmaxConfiguration(apiKey=***, environment=$environment, loggerOptions=${loggerOptions.rawValue})"
}
