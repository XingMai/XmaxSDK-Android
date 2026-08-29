package ai.xmax.sdk.stream

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeNetworkQualityListener
import ai.xmax.sdk.RealtimePerformanceAlarmListener
import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.service.realtime.RealtimeSessionConnection
import kotlinx.coroutines.Deferred

/** 定义传输层向 Core 暴露的统一能力。 */
internal interface StreamControlling {
    val hasGenerationTask: Boolean

    fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat)

    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)

    fun setRemoteAudioVolume(volume: Float)

    suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        ensureActive: () -> Unit,
    )

    suspend fun disconnect()

    fun setLocalAudioEnabled(enabled: Boolean)

    fun pushLocalVideoFrame(frame: VideoFrame)

    fun pushLocalAudioFrame(frame: AudioFrame)

    suspend fun beginGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    ): Deferred<Unit>

    fun activateRemoteAudio()

    suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
    )

    suspend fun stopGeneration(taskId: String)

    suspend fun sendTracks(taskId: String, points: List<RealtimePoint>)
}
