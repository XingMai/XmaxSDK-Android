package ai.xmax.sdk.service.network

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger

/** 输出不包含认证信息和响应正文的 API 调试日志。 */
internal object ApiLogger {
    fun logResponse(
        method: ApiMethod,
        path: String,
        statusCode: Int,
        bodyByteCount: Int,
        durationMs: Long,
        successful: Boolean,
    ) {
        val message = responseMessage(method, path, statusCode, bodyByteCount, durationMs)
        if (successful) {
            XmaxLogger.debug({ message }, category = "API")
        } else {
            XmaxLogger.error({ message }, category = "API")
        }
    }

    fun logFailure(
        method: ApiMethod,
        path: String,
        error: Throwable,
        durationMs: Long,
    ) {
        XmaxLogger.error(
            {
                "${method.wireValue} $path 失败 (Request Failed)\n" +
                    "├─ 耗时：$durationMs ms\n" +
                    "└─ 原因：${ErrorMessageFormatter.format(error)}"
            },
            category = "API",
        )
    }

    fun responseMessage(
        method: ApiMethod,
        path: String,
        statusCode: Int,
        bodyByteCount: Int,
        durationMs: Long,
    ): String = "${method.wireValue} $path\n" +
        "├─ 状态：$statusCode\n" +
        "├─ 耗时：$durationMs ms\n" +
        "└─ 响应：$bodyByteCount bytes"
}
