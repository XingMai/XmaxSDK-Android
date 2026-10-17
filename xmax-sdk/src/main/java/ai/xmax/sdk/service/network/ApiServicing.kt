//
//  ApiServicing.kt
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
import org.json.JSONObject

/**
 * Xmax API 支持的 HTTP 请求方法。
 *
 * @param wireValue 协议传输使用的字符串值。
 */
internal enum class ApiMethod(internal val wireValue: String) {
    /**
     * 读取服务端资源的 HTTP 方法。
     */
    GET("GET"),

    /**
     * 提交业务请求的 HTTP 方法。
     */
    POST("POST"),

    /**
     * 更新服务端资源的 HTTP 方法。
     */
    PUT("PUT"),

    /**
     * 删除服务端资源的 HTTP 方法。
     */
    DELETE("DELETE"),
}

/**
 * 定义 Xmax API 的基础请求能力。
 */
internal interface ApiServicing {
    /**
     * 发送请求并返回统一响应中的业务数据。
     *
     * @param method HTTP 请求方法。
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun request(
        method: ApiMethod,
        path: String,
        body: JSONObject? = null,
    ): JSONObject

    /**
     * 发送请求并将业务数据转换为指定内部模型。
     *
     * @param method HTTP 请求方法。
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @param decode 将响应业务 JSON 转换为目标类型的函数。
     * @return 由调用方解析函数生成的业务响应。
     */
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

    /**
     * 发送 GET 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun get(path: String): JSONObject = request(ApiMethod.GET, path)

    /**
     * 发送 GET 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @param decode 将响应业务 JSON 转换为目标类型的函数。
     * @return 由调用方解析函数生成的业务响应。
     */
    suspend fun <Response> get(
        path: String,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.GET, path, decode = decode)

    /**
     * 发送 POST 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun post(path: String): JSONObject = request(ApiMethod.POST, path)

    /**
     * 发送 POST 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun post(path: String, body: JSONObject): JSONObject =
        request(ApiMethod.POST, path, body)

    /**
     * 发送 POST 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @param decode 将响应业务 JSON 转换为目标类型的函数。
     * @return 由调用方解析函数生成的业务响应。
     */
    suspend fun <Response> post(
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.POST, path, body, decode)

    /**
     * 发送 PUT 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun put(path: String): JSONObject = request(ApiMethod.PUT, path)

    /**
     * 发送 PUT 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun put(path: String, body: JSONObject): JSONObject =
        request(ApiMethod.PUT, path, body)

    /**
     * 发送 PUT 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @param body 可选的 JSON 请求正文；null 表示没有请求正文。
     * @param decode 将响应业务 JSON 转换为目标类型的函数。
     * @return 由调用方解析函数生成的业务响应。
     */
    suspend fun <Response> put(
        path: String,
        body: JSONObject? = null,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.PUT, path, body, decode)

    /**
     * 发送 DELETE 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @return 供当前业务使用的 JSON 数据。
     */
    suspend fun delete(path: String): JSONObject = request(ApiMethod.DELETE, path)

    /**
     * 发送 DELETE 请求并返回响应业务数据。
     *
     * @param path 相对于 API 基础地址的请求路径。
     * @param decode 将响应业务 JSON 转换为目标类型的函数。
     * @return 由调用方解析函数生成的业务响应。
     */
    suspend fun <Response> delete(
        path: String,
        decode: (JSONObject) -> Response,
    ): Response = request(ApiMethod.DELETE, path, decode = decode)
}
