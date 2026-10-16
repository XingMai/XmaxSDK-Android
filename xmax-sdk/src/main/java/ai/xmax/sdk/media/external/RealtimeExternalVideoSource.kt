package ai.xmax.sdk

import android.widget.FrameLayout

/**
 * 接入方自行采集、解码或处理的视频源，可用于摄像头、播放器等外部输入。
 * SDK 只绑定/解绑供帧，不拥有采集器或播放器；权限、设备切换及资源释放由接入方负责。
 * 默认仅提供视频，不要求绑定 SDK 本地预览；音频和预览能力可按需实现。
 * 生命周期和预览方法在主线程调用；帧可从后台线程同步提交。
 */
public interface RealtimeExternalVideoSource {
    /**
     * 源视频的原始显示规格；实际供帧规格由 SDK 通过 sink.videoFormat 下发。
     */
    public val videoFormat: RealtimeVideoFormat

    /**
     * 是否提供音频；默认 false，不采集麦克风或启动外部音频输入，单次绑定期间保持不变。
     */
    public val hasAudio: Boolean
        get() = false

    /**
     * 绑定供帧入口，返回前完成绑定，不等待生成结束。
     * 接入方负责旋转、裁剪和缩放，按 sink.videoFormat 输出；供帧不能等待 startGeneration 返回。
     * 采集源结束或发生不可恢复错误时调用 sink.reportError；绑定失败时 SDK 仍可能调用 stop。
     */
    public suspend fun start(sink: RealtimeExternalFrameSink)

    /**
     * 停止向 SDK 供帧并清除 sink，必须幂等；不代表关闭接入方的摄像头或播放器。
     */
    public suspend fun stop()

    /**
     * 可选：将本地预览挂载到 SDK 视频容器；默认不操作，接入方可自行显示预览。
     */
    public fun attachPreview(container: FrameLayout, contentMode: VideoContentMode) = Unit

    /**
     * 可选：移除对应容器中的本地预览，不停止采集；默认不操作。
     */
    public fun detachPreview(container: FrameLayout) = Unit

    /**
     * 可选：只控制本地监听音量，不改变提交给 SDK 的 PCM；默认不操作。
     */
    public fun setPreviewAudio(volume: Float, muted: Boolean) = Unit
}

/**
 * 单次绑定的帧入口；解绑后提交会被丢弃。方法返回后可复用输入数组。
 * 视频为从上到下排列的 RGBA，音频为 48kHz 单声道 PCM16LE，每包 480 个采样。
 * 两者时间戳使用同一单调时钟（微秒），seek/循环后不得回退。
 */
public interface RealtimeExternalFrameSink {
    public val videoFormat: RealtimeVideoFormat
    /**
     * false 时可跳过昂贵的像素读回和音频转换；无需停止接入方的采集或预览。
     */
    public val isRequestingFrames: Boolean
    public fun pushVideo(rgba: ByteArray, timestampUs: Long)
    public fun pushAudio(pcm: ByteArray, timestampUs: Long)
    public fun reportError(error: Throwable)
}
