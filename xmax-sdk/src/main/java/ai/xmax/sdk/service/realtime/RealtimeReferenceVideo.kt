//
//  RealtimeReferenceVideo.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 服务端读取参考视频时的采样方式。
 *
 * @param value 需要规范化、校验或显示的文本。
 */
public enum class RealtimeVideoSampleMethod(internal val value: String) {
    /**
     * 按时间间隔采样网络视频。
     */
    TIME("time"),

    /**
     * 按帧率采样网络视频。
     */
    FPS("fps"),
}

/**
 * 可由服务端直接访问的参考视频。
 *
 * @param path 相对于 API 基础地址的请求路径。
 * @param sampleMethod 服务端读取网络视频时采用的采样方式；默认值为 `RealtimeVideoSampleMethod.TIME`。
 */
public data class RealtimeReferenceVideo(
    public val path: String,
    public val sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
)
