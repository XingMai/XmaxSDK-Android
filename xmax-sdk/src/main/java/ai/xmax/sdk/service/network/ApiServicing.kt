package ai.xmax.sdk.service.network

import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import org.json.JSONObject

/** Xmax API 支持的 HTTP 请求方法。 */
internal enum class ApiMethod(internal val wireValue: String) {
    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
}

/** 定义 Xmax API 的基础请求能力。 */
internal interface ApiServicing {
    /** 发送请求并返回统一响应中的业务数据。 */
    suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject? = null,
    ): JSONObject

    /** 发送请求并将业务数据转换为指定内部模型。 */
    suspend fun <Response> request(
        method: ApiMethod,
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response {
        val payload = request(method, path, body)
        return try {
            decode(payload)
        } catch (error: XmaxError) {
            throw error
        } catch (error: Throwable) {
            throw XmaxError(
                code = XmaxErrorCode.API_ERROR,
                message = "Server returned invalid response data: ${ErrorMessageFormatter.format(error)}",
                cause = error,
            )
        }
    }

    suspend fun get(path: String): JSONObject = request(ApiMethod.GET, path)

    suspend fun <Response> get(
        path: String,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.GET, path, decode = decode)

    suspend fun post(path: String): JSONObject = request(ApiMethod.POST, path)

    suspend fun post(path: String, body: JSONObject): JSONObject =
        request(ApiMethod.POST, path, body)

    suspend fun <Response> post(
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.POST, path, body, decode)

    suspend fun put(path: String): JSONObject = request(ApiMethod.PUT, path)

    suspend fun put(path: String, body: JSONObject): JSONObject =
        request(ApiMethod.PUT, path, body)

    suspend fun <Response> put(
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.PUT, path, body, decode)

    suspend fun delete(path: String): JSONObject = request(ApiMethod.DELETE, path)

    suspend fun <Response> delete(
        path: String,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.DELETE, path, decode = decode)
}
