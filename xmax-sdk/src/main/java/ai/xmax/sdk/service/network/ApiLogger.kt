//
//  ApiLogger.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.network

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger

/**
 * 输出不包含认证信息和响应正文的 API 调试日志。
 */
internal object ApiLogger {
    /**
     * 输出请求路径、响应状态和耗时，不包含凭据或响应正文。
     *
     * @param method HTTP 请求方法。
     * @param path 相对于 API 基础地址的请求路径。
     * @param statusCode HTTP 响应状态码。
     * @param bodyByteCount 响应正文的字节数。
     * @param durationMs 请求耗时，单位为毫秒。
     * @param successful 请求是否成功。
     */
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
            XmaxLogger.api.debug(message = { message })
        } else {
            XmaxLogger.api.error(message = { message })
        }
    }

    /**
     * 输出请求失败的路径、耗时和原因，不包含凭据。
     *
     * @param method HTTP 请求方法。
     * @param path 相对于 API 基础地址的请求路径。
     * @param error 本次操作失败的异常或 SDK 错误。
     * @param durationMs 请求耗时，单位为毫秒。
     */
    fun logFailure(
        method: ApiMethod,
        path: String,
        error: Throwable,
        durationMs: Long,
    ) {
        XmaxLogger.api.error(
            message = {
                "${method.wireValue} $path 失败 (Request Failed)\n" +
                    "├─ ${XmaxLogger.localized("耗时：", "Duration: ")}$durationMs ms\n" +
                    "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
            },
        )
    }

    /**
     * 生成包含响应状态、正文大小和耗时的日志文本。
     *
     * @param method HTTP 请求方法。
     * @param path 相对于 API 基础地址的请求路径。
     * @param statusCode HTTP 响应状态码。
     * @param bodyByteCount 响应正文的字节数。
     * @param durationMs 请求耗时，单位为毫秒。
     * @return 包含请求状态及耗时的日志文本。
     */
    fun responseMessage(
        method: ApiMethod,
        path: String,
        statusCode: Int,
        bodyByteCount: Int,
        durationMs: Long,
    ): String = "${method.wireValue} $path\n" +
        "├─ ${XmaxLogger.localized("状态：", "Status: ")}$statusCode\n" +
        "├─ ${XmaxLogger.localized("耗时：", "Duration: ")}$durationMs ms\n" +
        "└─ ${XmaxLogger.localized("响应：", "Response: ")}$bodyByteCount bytes"
}
