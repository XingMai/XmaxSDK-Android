package ai.xmax.sdk

/** SDK 全局配置。 */
public class XmaxConfiguration @JvmOverloads constructor(
    apiKey: String,
    /** SDK 输出的日志类型；默认为不输出日志。 */
    public val loggerOptions: XmaxLoggerOption = XmaxLoggerOption.none,
) {
    /** 调用 Xmax 服务使用的 API Key。 */
    public val apiKey: String = apiKey.trim()

    /** 校验全局配置。 */
    public fun validate() {
        if (apiKey.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_API_KEY,
                message = "API key cannot be empty",
            )
        }
    }

    override fun toString(): String =
        "XmaxConfiguration(apiKey=***, loggerOptions=${loggerOptions.rawValue})"
}
