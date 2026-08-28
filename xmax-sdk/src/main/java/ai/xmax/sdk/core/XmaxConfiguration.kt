package ai.xmax.sdk

/** SDK 全局配置。 */
public class XmaxConfiguration(apiKey: String) {
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

    override fun toString(): String = "XmaxConfiguration(apiKey=***)"
}
