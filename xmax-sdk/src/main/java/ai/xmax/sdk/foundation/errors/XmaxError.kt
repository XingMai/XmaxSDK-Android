package ai.xmax.sdk

/** SDK 向接入方暴露的统一错误码。 */
public enum class XmaxErrorCode {
    INVALID_API_KEY,
    INVALID_CONFIGURATION,
    INTERNAL_ERROR,
    NETWORK_ERROR,
    API_ERROR,
    SESSION_ERROR,
    RTC_ERROR,
    MEDIA_ERROR,
    FRAME_INTERPOLATION_UNSUPPORTED,
    CAMERA_PERMISSION_DENIED,
    MICROPHONE_PERMISSION_DENIED,
    UPLOAD_ERROR,
    DOWNLOAD_ERROR,
    UNSAFE_IMAGE,
    CANCELLED,
    TIMEOUT,
}

/** SDK 统一抛出的错误。 */
public class XmaxError(
    public val code: XmaxErrorCode,
    message: String,
    public val apiCode: Int? = null,
    public val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public companion object {
        /** 将未知错误转换为稳定的 SDK 错误，已有 [XmaxError] 原样返回。 */
        public fun from(error: Throwable): XmaxError = when (error) {
            is XmaxError -> error
            else -> XmaxError(
                code = XmaxErrorCode.INTERNAL_ERROR,
                message = ErrorMessageFormatter.format(error),
                cause = error,
            )
        }
    }
}
