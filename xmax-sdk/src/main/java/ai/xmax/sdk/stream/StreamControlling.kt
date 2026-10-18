//
//  StreamControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setAdaptiveQualityListener(listener: ((RealtimeNetworkQuality) -> Unit)?)

    /**
     * 设置上行视频统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?)

    /**
     * 设置下行结果流统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?)

    /**
     * 更新上行视频的尺寸、帧率和编码码率配置。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    fun setVideoEncoderConfig(videoFormat: RealtimeVideoFormat)

    /**
     * 设置 RTC 网络质量监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    /**
     * 设置 RTC 性能告警监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)

    /**
     * 设置当前结果流的音频播放音量，范围为 0 到 1，并保留供后续订阅使用。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    fun setRemoteAudioVolume(volume: Float)

    /**
     * 加入 RTC 房间，按输入来源发布本地媒体并启用对应上行统计。
     *
     * @param connection 服务端返回的 RTC 房间、用户、鉴权及结果流信息。
     * @param includeLocalAudio 是否同时发布本地音频。
     * @param publishLocalMedia 是否发布本地媒体；服务端直接读取网络视频时为 false；默认值为 `true`。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    suspend fun connect(
        connection: RealtimeSessionConnection,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean = true,
        ensureActive: () -> Unit,
    )

    /**
     * 解除媒体发布、远端订阅和生成等待，并离开当前 RTC 房间。
     */
    suspend fun disconnect()

    /**
     * 在生成需要输入帧时向 RTC 提交本地视频帧。
     *
     * @param frame 待处理、显示或提交的视频帧。
     */
    fun pushLocalVideoFrame(frame: VideoFrame)

    /**
     * 在本地音频已发布时向 RTC 提交音频帧。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    fun pushLocalAudioFrame(frame: AudioFrame)

    /**
     * 启动生成并等待结果流确认；回传规格缺省时沿用生成规格，上传码率字段不影响下行。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     * @return 完成资源清理时结束的异步等待结果。
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
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param ensureActive 检查当前操作是否仍可继续的函数，取消时应抛出异常。
     */
    suspend fun updateDownlinkVideoResolution(
        taskId: String,
        targetVideoFormat: RealtimeVideoFormat,
        ensureActive: () -> Unit,
    )

    /**
     * 启用网络视频完成回调，满足生成首帧和服务端完成条件后通知。
     *
     * @param onFinish 网络视频任务完成的通知；null 表示不接收完成通知。
     */
    fun activateNetworkVideoCompletion(onFinish: (() -> Unit)?)

    /**
     * 订阅当前结果流的音频并应用远端播放音量。
     */
    fun activateRemoteAudio()

    /**
     * 更新当前任务的生成条件，不重新创建服务端会话。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     */
    suspend fun updateGeneration(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext,
        faceRestoration: Boolean = false,
        paddingContain: Boolean = false,
    )

    /**
     * 发送停止指定生成任务的信令。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    suspend fun stopGeneration(taskId: String)

    /**
     * 向服务端发送当前任务的媒体归一化坐标轨迹。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param points 媒体归一化坐标中的轨迹点集合，范围为 [0, 1]。
     */
    suspend fun sendTracks(taskId: String, points: List<RealtimePoint>)
}
