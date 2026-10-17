//
//  RealtimeVideoFrame.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.nio.ByteBuffer

/**
 * 最终远端视频帧，像素格式固定为 I420（Y、U、V 三个紧密排列的平面）。
 * SDK 已复制像素，不依赖 RTC 原生帧的生命周期；接入方可持有此对象供异步编码使用，无须释放。
 * 各 data 访问器返回独立的只读视图，修改 position 不会影响其他消费者。
 *
 * @param width 画面宽度，单位为像素。
 * @param height 画面高度，单位为像素。
 * @param presentationTimeUs 音视频数据的媒体呈现时间，单位为微秒。
 * @param durationUs 媒体持续时间，单位为微秒。
 * @param rotationDegrees 顺时针旋转角度，单位为度。
 * @param y Y 像素平面数据。
 * @param u U 像素平面数据。
 * @param v V 像素平面数据。
 */
public class RealtimeVideoFrame internal constructor(
    public val width: Int,
    public val height: Int,

    /**
     * 原始流时间戳，单位微秒；录制时以第一帧为基准归零。
     */
    public val presentationTimeUs: Long,

    /**
     * 单帧时长，单位微秒；RTC 未提供时为 null。
     */
    public val durationUs: Long?,

    /**
     * 显示时仍需顺时针旋转的角度（0、90、180、270）。
     */
    public val rotationDegrees: Int,
    private val y: ByteArray,
    private val u: ByteArray,
    private val v: ByteArray,
) {
    /**
     * I420 各像素平面的行步长，单位为字节。
     */
    public val yStride: Int get() = width
    public val uStride: Int get() = (width + 1) / 2
    public val vStride: Int get() = uStride

    /**
     * 远端帧的 I420 像素数据。
     */
    public val yData: ByteBuffer get() = ByteBuffer.wrap(y).asReadOnlyBuffer()
    public val uData: ByteBuffer get() = ByteBuffer.wrap(u).asReadOnlyBuffer()
    public val vData: ByteBuffer get() = ByteBuffer.wrap(v).asReadOnlyBuffer()
}

/**
 * 在 SDK 的后台串行队列上接收最终帧。应尽快转交编码任务，避免阻塞后续回调。
 */
public fun interface RealtimeVideoFrameListener {
    /**
     * 接收当前订阅的视频帧。
     *
     * @param frame 待处理、显示或提交的视频帧。
     */
    public fun onFrame(frame: RealtimeVideoFrame)
}
