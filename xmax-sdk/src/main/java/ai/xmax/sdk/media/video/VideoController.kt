//
//  VideoController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.MediaSourceControlling
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.net.Uri

/**
 * 协调本地视频播放器、RTC 外部音视频源和预览资源。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param mediaSourceController 解析媒体输入并驱动帧输出的组件。
 */
internal class VideoController(
    private val rtcManager: RtcManaging,
    private val mediaSourceController: MediaSourceControlling,
) {
    /**
     * 视频输入轨道的并发保护。
     */
    private val stateLock = Any()

    /**
     * 活动视频轨道及音轨信息。
     */
    private var activeTrack: RealtimeVideoTrack? = null

    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    val hasAudio: Boolean
        get() = currentTrack != null && mediaSourceController.hasAudio

    /**
     * 从 URI 创建本地视频流；存在音轨时同时提供视频文件的音频输入。
     * 指定 videoFormat 时使用其输入规格，宽高须保持素材比例；未指定时使用旋转校正后的
     * 素材尺寸（向下对齐偶数像素）和模型默认帧率。SDK 单独选择生成画布，服务端等比适配并裁边输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local video stream before creating another one",
            )
        }
        var track: RealtimeVideoTrack? = null
        var externalAudioStarted = false
        try {
            val configuration = mediaSourceController.prepare(uri, videoFormat)
            val localTrack = RealtimeVideoTrack(
                id = LOCAL_VIDEO_TRACK_ID,
                videoFormat = configuration.videoFormat,
            )
            track = localTrack
            rtcManager.useExternalVideoSource()
            if (configuration.hasAudio) {
                rtcManager.startExternalAudioSource()
                externalAudioStarted = true
            }
            VideoRenderRegistry.register(
                localTrack,
                VideoRenderBinding(
                    attachHandler = mediaSourceController::attachPreview,
                    detachHandler = mediaSourceController::detachPreview,
                ),
            )
            mediaSourceController.start()
            synchronized(stateLock) { activeTrack = localTrack }
            return RealtimeMediaStream(StreamID.LOCAL.value, localTrack)
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { mediaSourceController.stop() },
                { if (externalAudioStarted) rtcManager.stopExternalAudioSource() },
                { track?.let(VideoRenderRegistry::unregister) },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * 设置本地预览是否静音，不改变保存的音量值。
     *
     * @param muted 是否静音。
     */
    suspend fun setLocalAudioPreviewMuted(muted: Boolean) {
        if (currentTrack != null) mediaSourceController.setLocalAudioPreviewMuted(muted)
    }

    /**
     * 设置当前本地媒体的预览音量，范围为 0 到 1，不影响提交给 RTC 的音频数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    suspend fun setLocalAudioVolume(volume: Float) {
        mediaSourceController.setLocalAudioVolume(volume)
    }

    /**
     * 停止当前视频流并释放解码与预览资源；当前源不是视频时不执行释放。
     */
    suspend fun stopLocalVideoStream() {
        val state = synchronized(stateLock) {
            val track = activeTrack
            val hasAudio = track != null && mediaSourceController.hasAudio
            activeTrack = null
            track to hasAudio
        }
        cleanupResources(
            { mediaSourceController.stop() },
            { if (state.second) rtcManager.stopExternalAudioSource() },
            { state.first?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { state.first?.let(VideoRenderRegistry::unregister) },
        )
    }

    /**
     * 提供 VideoController 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
