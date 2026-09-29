package ai.xmax.sdk

import android.widget.FrameLayout

/**
 * 外部播放器提供的媒体源。SDK 只绑定/解绑输入，不拥有播放器，不调用其暂停或释放。
 * 生命周期和预览方法在主线程调用；帧可从后台线程同步提交。
 */
public interface RealtimeExternalVideoSource {
    /** 源视频的原始显示规格；实际供帧规格由 SDK 通过 sink.videoFormat 下发。 */
    public val videoFormat: RealtimeVideoFormat
    public val hasAudio: Boolean
    /** 使用 sink.videoFormat 指定的尺寸输出；返回前完成绑定，失败时允许 SDK 调用 stop。 */
    public suspend fun start(sink: RealtimeExternalFrameSink)
    /** 停止供帧并移除 sink；必须幂等，不停止播放器。 */
    public suspend fun stop()
    public fun attachPreview(container: FrameLayout, contentMode: VideoContentMode)
    public fun detachPreview(container: FrameLayout)
    /** 只影响本地听到的音量，不影响提供给 SDK 的 PCM。 */
    public fun setPreviewAudio(volume: Float, muted: Boolean)
}

/**
 * 单次绑定的帧入口；解绑后提交会被丢弃。方法返回后可复用输入数组。
 * 视频为从上到下排列的 RGBA，音频为 48kHz 单声道 PCM16LE，每包 480 个采样。
 * 两者时间戳使用同一单调时钟（微秒），seek/循环后不得回退。
 */
public interface RealtimeExternalFrameSink {
    public val videoFormat: RealtimeVideoFormat
    /** false 时跳过昂贵的 GPU 读回和 PCM 转换；本地播放不受影响。 */
    public val isRequestingFrames: Boolean
    public fun pushVideo(rgba: ByteArray, timestampUs: Long)
    public fun pushAudio(pcm: ByteArray, timestampUs: Long)
    public fun reportError(error: Throwable)
}
