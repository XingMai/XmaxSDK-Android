package ai.xmax.sdk.service.network

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 网络层发送给底层 HTTP Transport 的请求。 */
internal data class ApiHttpRequest(
    val method: ApiMethod,
    val url: URL,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
)

/** 底层 HTTP Transport 返回的原始响应。 */
internal data class ApiHttpResponse(
    val statusCode: Int,
    val body: ByteArray,
)

/** 可替换、可测试的 HTTP 传输边界。 */
internal fun interface ApiTransport {
    suspend fun execute(request: ApiHttpRequest): ApiHttpResponse
}

/** 使用 Android/Java 原生 URLConnection 的默认 Transport。 */
internal class UrlConnectionApiTransport(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) : ApiTransport {
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
