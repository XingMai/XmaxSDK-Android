package ai.xmax.sdk.service.network

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import java.io.IOException
import java.net.URI
import java.net.URL
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONException
import org.json.JSONObject

/** 负责发送 Xmax API 请求并统一处理响应和错误。 */
internal class ApiService(
    apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    private val transport: ApiTransport = UrlConnectionApiTransport(),
) : ApiServicing {
    private val apiKey: String = apiKey.trim()

    override suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject?,
    ): JSONObject {
        validateConfiguration()
        val request = ApiHttpRequest(
            method = method,
            url = resolveUrl(path),
            headers = mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                "X-Api-Key" to apiKey,
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

    private fun parseBaseUri(): URI = try {
        URI(baseUrl.trim())
    } catch (error: Exception) {
        throw invalidConfigurationError(error)
    }

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

    private fun invalidConfigurationError(cause: Throwable? = null): XmaxError = XmaxError(
        code = XmaxErrorCode.INVALID_CONFIGURATION,
        message = "API service configuration is invalid",
        cause = cause,
    )

    private fun invalidPathError(cause: Throwable? = null): XmaxError = XmaxError(
        code = XmaxErrorCode.INVALID_CONFIGURATION,
        message = "API request path is invalid",
        cause = cause,
    )

    private fun durationMs(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    private companion object {
        const val DEFAULT_BASE_URL = "https://cloud.xmax.22duck.cn/open/api/v1"
        const val DEFAULT_TIMEOUT_MS = 15_000
    }
}
