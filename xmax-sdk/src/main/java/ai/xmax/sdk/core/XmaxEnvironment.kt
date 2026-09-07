package ai.xmax.sdk

/** Xmax 服务环境，在创建客户端时选择。 */
public enum class XmaxEnvironment {
    /** 国内环境。 */
    CHINA,

    /** 海外环境。 */
    GLOBAL;

    internal val apiBaseUrl: String
        get() = when (this) {
            CHINA -> "https://cloud.xmax.22duck.cn/open/api/v1"
            GLOBAL -> "https://api.xmax.cloud/open/api/v1"
        }
}
