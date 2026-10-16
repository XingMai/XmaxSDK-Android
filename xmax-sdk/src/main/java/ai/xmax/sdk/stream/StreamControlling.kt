package ai.xmax.sdk.stream

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatisticsListener
import ai.xmax.sdk.DownlinkVideoStatisticsListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.Deferred

/**
 * 定义传输层向 Core 暴露的统一能力。
 */
internal interface StreamControlling {
    /**
     * 接收自动调档使用的网络质量样本，不占用公共监听器。
     */
    fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?)

    fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?)

    fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?)

    fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat)

    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)

    fun setRemoteAudioVolume(volume: Float)

    suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean = true,
        ensureActive: () -> Unit,
    )

    suspend fun disconnect()

    fun pushLocalVideoFrame(frame: VideoFrame)

    fun pushLocalAudioFrame(frame: AudioFrame)

    /**
     * 启动生成并等待结果流确认；回传规格缺省时沿用生成规格，上传码率字段不影响下行。
     */
    suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        paddingContain: Boolean = false,
    ): Deferred<Unit>

    /**
     * 更新当前任务的回传尺寸与码率；下行码率按固定 24 fps 估算。
     */
    suspend fun updateDownlinkVideoResolution(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    )

    fun activateNetworkVideoCompletion(onFinish: (() -> Unit)?)

    fun activateRemoteAudio()

    suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    )

    suspend fun stopGeneration(taskId: String)

    suspend fun sendTracks(taskId: String, points: List<RealtimePoint>)
}
