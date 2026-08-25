package ai.xmax.sdk

/** Global credentials and options shared by Xmax services. */
public class XmaxConfiguration(apiKey: String) {
    /** API key used to authenticate requests to Xmax services. */
    public val apiKey: String = apiKey.trim()

    init {
        require(this.apiKey.isNotEmpty()) { "API key cannot be empty" }
    }

    override fun toString(): String = "XmaxConfiguration(apiKey=***)"
}

