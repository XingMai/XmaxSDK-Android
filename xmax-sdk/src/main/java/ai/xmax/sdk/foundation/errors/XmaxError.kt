package ai.xmax.sdk

import kotlinx.coroutines.CancellationException

/** SDK 向接入方暴露的统一错误码。 */
public enum class XmaxErrorCode {
    INVALID_API_KEY, INVALID_CONFIGURATION, INTERNAL_ERROR, NETWORK_ERROR,
    API_ERROR, SESSION_ERROR, RTC_ERROR, MEDIA_ERROR,
    CAMERA_PERMISSION_DENIED, MICROPHONE_PERMISSION_DENIED, UPLOAD_ERROR, DOWNLOAD_ERROR,
    UNSAFE_IMAGE, CANCELLED, TIMEOUT,
}

/** SDK 统一错误；实时资源的清理范围由当前操作决定，不属于错误本身。 */
public class XmaxError(
    public val code: XmaxErrorCode,
    message: String,
    public val apiCode: Int? = null,
    public val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public companion object {
        /** 已有错误原样保留；协程取消继续传播，不转换成业务故障。 */
        public fun from(error: Throwable): XmaxError = when (error) {
            is CancellationException -> throw error
            is XmaxError -> error
            else -> XmaxError(XmaxErrorCode.INTERNAL_ERROR, ErrorMessageFormatter.format(error), cause = error)
        }
    }
}
