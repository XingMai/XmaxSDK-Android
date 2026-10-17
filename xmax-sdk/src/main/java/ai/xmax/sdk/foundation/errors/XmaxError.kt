//
//  XmaxError.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import kotlinx.coroutines.CancellationException

/**
 * SDK 向接入方暴露的统一错误码。
 */
public enum class XmaxErrorCode {
    /**
     * API Key 缺失或不符合本地校验要求。
     */
    INVALID_API_KEY,

    /**
     * 配置参数或调用状态无效。
     */
    INVALID_CONFIGURATION,

    /**
     * 无法归类的 SDK 内部错误。
     */
    INTERNAL_ERROR,

    /**
     * 网络连接或数据传输失败。
     */
    NETWORK_ERROR,

    /**
     * 服务端 API 返回失败。
     */
    API_ERROR,

    /**
     * 实时会话创建、续期或状态异常。
     */
    SESSION_ERROR,

    /**
     * RTC 引擎、房间或媒体操作失败。
     */
    RTC_ERROR,

    /**
     * 媒体读取、解码或处理失败。
     */
    MEDIA_ERROR,

    /**
     * 应用尚未取得相机权限。
     */
    CAMERA_PERMISSION_DENIED,

    /**
     * 应用尚未取得录音权限。
     */
    MICROPHONE_PERMISSION_DENIED,

    /**
     * 媒体上传失败。
     */
    UPLOAD_ERROR,

    /**
     * 媒体下载失败。
     */
    DOWNLOAD_ERROR,

    /**
     * 图片未通过服务端内容安全检查。
     */
    UNSAFE_IMAGE,

    /**
     * 操作已取消。
     */
    CANCELLED,

    /**
     * 操作未在允许的时间内完成。
     */
    TIMEOUT,
}

/**
 * SDK 统一错误；实时资源的清理范围由当前操作决定，不属于错误本身。
 *
 * @param code 用于区分错误类别的 SDK 错误码。
 * @param message 待发送或记录的消息文本。
 * @param apiCode 服务端返回的业务错误码；null 表示未提供。
 * @param httpStatus HTTP 响应状态码；null 表示尚无有效响应。
 * @param cause 触发当前错误的底层异常。
 */
public class XmaxError(
    public val code: XmaxErrorCode,
    message: String,
    public val apiCode: Int? = null,
    public val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * 提供 XmaxError 使用的构造入口、共享配置或常量。
     */
    public companion object {
        /**
         * 已有错误原样保留；协程取消继续传播，不转换成业务故障。
         *
         * @param error 本次操作失败的异常或 SDK 错误。
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        public fun from(error: Throwable): XmaxError = when (error) {
            is CancellationException -> throw error
            is XmaxError -> error
            else -> XmaxError(XmaxErrorCode.INTERNAL_ERROR, ErrorMessageFormatter.format(error), cause = error)
        }
    }
}
