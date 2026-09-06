package ai.xmax.sdk

import java.nio.ByteBuffer

/**
 * 最终远端视频帧，像素格式固定为 I420（Y、U、V 三个紧密排列的平面）。
 * SDK 已复制像素，不依赖 RTC 原生帧的生命周期；接入方可持有此对象供异步编码使用，无须释放。
 * 各 data 访问器返回独立的只读视图，修改 position 不会影响其他消费者。
 */
public class RealtimeVideoFrame internal constructor(
    public val width: Int,
    public val height: Int,
    /** 原始流时间戳，单位微秒；录制时以第一帧为基准归零。 */
    public val presentationTimeUs: Long,
    /** 单帧时长，单位微秒；RTC 未提供时为 null。 */
    public val durationUs: Long?,
    /** 显示时仍需顺时针旋转的角度（0、90、180、270）。 */
    public val rotationDegrees: Int,
    private val y: ByteArray,
    private val u: ByteArray,
    private val v: ByteArray,
) {
    public val yStride: Int get() = width
    public val uStride: Int get() = (width + 1) / 2
    public val vStride: Int get() = uStride
    public val yData: ByteBuffer get() = ByteBuffer.wrap(y).asReadOnlyBuffer()
    public val uData: ByteBuffer get() = ByteBuffer.wrap(u).asReadOnlyBuffer()
    public val vData: ByteBuffer get() = ByteBuffer.wrap(v).asReadOnlyBuffer()
}

/** 在 SDK 的后台串行队列上接收最终帧。应尽快转交编码任务，避免阻塞后续回调。 */
public fun interface RealtimeVideoFrameListener {
    public fun onFrame(frame: RealtimeVideoFrame)
}
