package ai.xmax.sdk

import kotlinx.coroutines.CancellationException

/** SDK 向接入方暴露的统一错误码。 */
public enum class XmaxErrorCode {
    INVALID_API_KEY, INVALID_CONFIGURATION, INTERNAL_ERROR, NETWORK_ERROR,
    API_ERROR, SESSION_ERROR, RTC_ERROR, MEDIA_ERROR, FRAME_INTERPOLATION_UNSUPPORTED,
    CAMERA_PERMISSION_DENIED, MICROPHONE_PERMISSION_DENIED, UPLOAD_ERROR, DOWNLOAD_ERROR,
    UNSAFE_IMAGE, CANCELLED, TIMEOUT,
}

/** 错误对当前实时流程的影响；FATAL 表示需要重新准备、启动或连接。 */
public enum class XmaxErrorSeverity { RECOVERABLE, FATAL }

/** SDK 统一错误。原有构造签名保持兼容。 */
public class XmaxError(
    public val code: XmaxErrorCode,
    message: String,
    public val apiCode: Int? = null,
    public val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    public var severity: XmaxErrorSeverity = defaultSeverity(code)
        private set

    public constructor(
        code: XmaxErrorCode,
        message: String,
        severity: XmaxErrorSeverity,
        apiCode: Int? = null,
        httpStatus: Int? = null,
        cause: Throwable? = null,
    ) : this(code, message, apiCode, httpStatus, cause) {
        this.severity = severity
    }

    internal fun withSeverity(value: XmaxErrorSeverity): XmaxError {
        if (severity == value) return this
        return XmaxError(code, message.orEmpty(), value, apiCode, httpStatus, cause).also {
            it.stackTrace = stackTrace
            suppressed.forEach(it::addSuppressed)
        }
    }

    public companion object {
        /** 已有错误原样保留；协程取消继续传播，不转换成业务故障。 */
        public fun from(error: Throwable): XmaxError = when (error) {
            is CancellationException -> throw error
            is XmaxError -> error
            else -> XmaxError(XmaxErrorCode.INTERNAL_ERROR, ErrorMessageFormatter.format(error), cause = error)
        }

        private fun defaultSeverity(code: XmaxErrorCode): XmaxErrorSeverity = when (code) {
            XmaxErrorCode.INVALID_API_KEY, XmaxErrorCode.INVALID_CONFIGURATION,
            XmaxErrorCode.FRAME_INTERPOLATION_UNSUPPORTED, XmaxErrorCode.CAMERA_PERMISSION_DENIED,
            XmaxErrorCode.MICROPHONE_PERMISSION_DENIED, XmaxErrorCode.CANCELLED,
            -> XmaxErrorSeverity.RECOVERABLE
            else -> XmaxErrorSeverity.FATAL
        }
    }
}
