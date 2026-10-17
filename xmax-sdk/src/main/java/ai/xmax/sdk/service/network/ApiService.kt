//
//  ApiService.kt
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
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.runtime.RuntimeInfo
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

/**
 * 负责发送 Xmax API 请求并统一处理响应和错误。
 *
 * @param apiKey 开放平台签发的 API Key；不得写入日志或对外展示。
 * @param baseUrl API 服务的基础地址。
 * @param timeoutMs 网络请求超时时间，单位为毫秒；默认值为 `DEFAULT_TIMEOUT_MS`。
 * @param transport 发送 HTTP 请求并返回原始响应的组件。
 */
internal class ApiService(
    apiKey: String,
    private val baseUrl: String,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val transport: ApiTransport = UrlConnectionApiTransport(),
) : ApiServicing {
    /**
     * API 请求的鉴权信息。
     */
    private val apiKey: String = apiKey.trim()

    /**
     * 发送请求并返回统一响应中的业务数据。
     *
     * @param method HTTP 请求方法。
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @return 供当前业务使用的 JSON 数据。
     */
    override suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject?,
    ): JSONObject {
        validateConfiguration()
        val runtime = RuntimeInfo.current
        val request = ApiHttpRequest(
            method = method,
            url = resolveUrl(path),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "X-Api-Key" to apiKey,
                "X-Platform" to runtime.platform,
                "X-OS-Version" to runtime.osVersion,
                "X-SDK-Version" to runtime.sdkVersion,
                "X-Device-Model" to runtime.deviceModel,
            ),
            body = body?.toString()?.toByteArray(Charsets.UTF_8),
            connectTimeoutMs = timeoutMs,
            readTimeoutMs = timeoutMs,
        )
        val startedAt = System.nanoTime()

        val response = try {
            transport.execute(request)
        } catch (error: XmaxError) {
            ApiLogger.logFailure(method, path, error, durationMs(startedAt))
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            val resolvedError = XmaxError(
                code = XmaxErrorCode.NETWORK_ERROR,
                message = "HTTP request failed: ${ErrorMessageFormatter.format(error)}",
                cause = error,
            )
            ApiLogger.logFailure(method, path, resolvedError, durationMs(startedAt))
            throw resolvedError
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            val resolvedError = XmaxError(
                code = XmaxErrorCode.NETWORK_ERROR,
                message = "HTTP request failed: ${ErrorMessageFormatter.format(error)}",
                cause = error,
            )
            ApiLogger.logFailure(method, path, resolvedError, durationMs(startedAt))
            throw resolvedError
        }

        currentCoroutineContext().ensureActive()
        return try {
            parseEnvelope(response).also {
                ApiLogger.logResponse(
                    method = method,
                    path = path,
                    statusCode = response.statusCode,
                    bodyByteCount = response.body.size,
                    durationMs = durationMs(startedAt),
                    successful = true,
                )
            }
        } catch (error: Throwable) {
            ApiLogger.logResponse(
                method = method,
                path = path,
                statusCode = response.statusCode,
                bodyByteCount = response.body.size,
                durationMs = durationMs(startedAt),
                successful = false,
            )
            throw error
        }
    }

    /**
     * 校验 API 鉴权信息、基础地址和超时配置。
     */
    private fun validateConfiguration() {
        if (apiKey.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_API_KEY,
                message = "API key cannot be empty",
            )
        }

        val uri = parseBaseUri()
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank() ||
            timeoutMs <= 0
        ) {
            throw invalidConfigurationError()
        }
    }

    /**
     * 将相对请求路径解析为同源 API 地址，拒绝越界路径。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @return 可用于网络请求的 URL。
     */
    private fun resolveUrl(path: String): URL {
        val normalizedPath = path.trim()
        if (normalizedPath.isEmpty() ||
            normalizedPath.contains("://") ||
            normalizedPath.startsWith("//")
        ) {
            throw invalidPathError()
        }

        val base = baseUrl.trim().trimEnd('/')
        val relativePath = if (normalizedPath.startsWith('/')) {
            normalizedPath
        } else {
            "/$normalizedPath"
        }
        val resolved = try {
            URI(base + relativePath)
        } catch (error: Exception) {
            throw invalidPathError(error)
        }
        val baseUri = parseBaseUri()
        if (!resolved.scheme.equals(baseUri.scheme, ignoreCase = true) ||
            !resolved.host.equals(baseUri.host, ignoreCase = true)
        ) {
            throw invalidPathError()
        }
        return try {
            resolved.toURL()
        } catch (error: Exception) {
            throw invalidPathError(error)
        }
    }

    /**
     * 解析并校验 API 基础地址。
     *
     * @return 解析并校验后的基础 URI。
     */
    private fun parseBaseUri(): URI = try {
        URI(baseUrl.trim())
    } catch (error: Exception) {
        throw invalidConfigurationError(error)
    }

    /**
     * 解析 API 响应封装并将服务端失败转换为 SDK 错误。
     *
     * @param response 包含状态、响应头及正文的 HTTP 响应。
     * @return 供当前业务使用的 JSON 数据。
     */
    private fun parseEnvelope(response: ApiHttpResponse): JSONObject {
        val envelope = try {
            JSONObject(response.body.toString(Charsets.UTF_8))
        } catch (error: JSONException) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid JSON",
                httpStatus = response.statusCode,
                cause = error,
            )
        }

        if (response.statusCode !in 200..299 || envelope.opt("success") != true) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = envelope.optString("message")
                    .trim()
                    .takeIf(String::isNotEmpty)
                    ?: "Xmax API request failed",
                apiCode = envelope.opt("code") as? Int,
                httpStatus = response.statusCode,
            )
        }

        return envelope.optJSONObject("data") ?: throw XmaxError(
            code = XmaxErrorCode.API_ERROR,
            message = "Server returned invalid response data",
            httpStatus = response.statusCode,
        )
    }

    /**
     * 创建用于报告 API 客户端配置无效的错误。
     *
     * @param cause 触发当前错误的底层异常。
     * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
     */
    private fun invalidConfigurationError(cause: Throwable? = null): XmaxError = XmaxError(
        code = XmaxErrorCode.INVALID_CONFIGURATION,
        message = "API service configuration is invalid",
        cause = cause,
    )

    /**
     * 创建用于报告 API 请求路径无效的错误。
     *
     * @param cause 触发当前错误的底层异常。
     * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
     */
    private fun invalidPathError(cause: Throwable? = null): XmaxError = XmaxError(
        code = XmaxErrorCode.INVALID_CONFIGURATION,
        message = "API request path is invalid",
        cause = cause,
    )

    /**
     * 计算从指定单调时钟起点至当前的毫秒耗时。
     *
     * @param startedAt 操作开始时的单调时钟纳秒数。
     * @return 从起点到当前的毫秒耗时。
     */
    private fun durationMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    /**
     * 提供 ApiService 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
