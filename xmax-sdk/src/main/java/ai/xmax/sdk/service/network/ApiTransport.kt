//
//  ApiTransport.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.network

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 网络层发送给底层 HTTP Transport 的请求。
 *
 * @param method HTTP 请求方法。
 * @param url 资源或请求的网络地址。
 * @param headers HTTP 请求或响应头。
 * @param body HTTP 正文的原始字节。
 * @param connectTimeoutMs 建立网络连接的超时时间，单位为毫秒。
 * @param readTimeoutMs 读取响应数据的超时时间，单位为毫秒。
 */
internal data class ApiHttpRequest(
    val method: ApiMethod,
    val url: URL,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)

/**
 * 底层 HTTP Transport 返回的原始响应。
 *
 * @param statusCode HTTP 响应状态码。
 * @param body HTTP 正文的原始字节。
 */
internal data class ApiHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

/**
 * 可替换、可测试的 HTTP 传输边界。
 */
internal fun interface ApiTransport {
    /**
     * 发送原始 HTTP 请求并返回状态、响应头和正文字节。
     *
     * @param request 包含方法、地址、请求头及正文的 HTTP 请求。
     * @return 包含 HTTP 状态、响应头及正文的原始响应。
     */
    suspend fun execute(request: ApiHttpRequest): ApiHttpResponse
}

/**
 * 使用 Android/Java 原生 URLConnection 的默认 Transport。
 *
 * @param ioDispatcher 执行阻塞网络或文件操作的协程调度器；默认值为 `Dispatchers.IO`。
 * @param connectionFactory 按 URL 创建 HTTP 连接的函数。
 */
internal class UrlConnectionApiTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : ApiTransport {
    /**
     * 发送原始 HTTP 请求并返回状态、响应头和正文字节。
     *
     * @param request 包含方法、地址、请求头及正文的 HTTP 请求。
     * @return 包含 HTTP 状态、响应头及正文的原始响应。
     */
    override suspend fun execute(request: ApiHttpRequest): ApiHttpResponse =
        withCancellableConnection(request.url, ioDispatcher, connectionFactory) { connection, ensureActive ->
            connection.apply {
                requestMethod = request.method.wireValue
                connectTimeout = request.connectTimeoutMs
                readTimeout = request.readTimeoutMs
                useCaches = false
                request.headers.forEach(::setRequestProperty)
                request.body?.let { payload ->
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                }
            }
            ensureActive()
            request.body?.let { payload -> connection.outputStream.use { it.write(payload) } }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { it.readBytes() } ?: ByteArray(0)
            ensureActive()
            ApiHttpResponse(statusCode, responseBody)
        }
}
