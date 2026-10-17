//
//  ImageController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.cleanupResources
import ai.xmax.sdk.cleanupAfterFailure
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import ai.xmax.sdk.stream.StreamID
import android.graphics.Bitmap
import android.net.Uri

/**
 * 协调本地图片解码、循环帧输出和预览资源。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param imageSourceController 负责图片解码及持续供帧的组件。
 */
internal class ImageController(
    private val rtcManager: RtcManaging,
    private val imageSourceController: ImageSourceControlling,
) {
    /**
     * 图片轨道状态的并发保护。
     */
    private val stateLock = Any()

    /**
     * 活动图片轨道及公开读取接口。
     */
    private var activeTrack: RealtimeVideoTrack? = null

    val currentTrack: RealtimeVideoTrack?
        get() = synchronized(stateLock) { activeTrack }

    /**
     * 解码图片字节并创建持续输出图片帧的本地流，输入规格须保持图片比例。
     * 未指定格式时使用方向校正后的图片尺寸（偶数对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(imageData, videoFormat)
    }

    /**
     * 从 Bitmap 创建本地图片流，输入规格须保持图片比例。
     * 未指定格式时使用 Bitmap 尺寸（偶数对齐）及模型默认帧率，模型生成画布独立选择。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalImageStream(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(bitmap, videoFormat)
    }

    /**
     * 从可读取的 URI 创建本地图片流；读取权限由接入方保证。
     * 输入规格须保持图片比例；未指定时使用方向校正后的图片尺寸（偶数对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun createLocalImageStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream = createStream {
        imageSourceController.prepare(uri, videoFormat)
    }

    /**
     * 停止当前图片流并释放其资源；当前源不是图片时不执行释放。
     */
    suspend fun stopLocalImageStream() {
        val track = synchronized(stateLock) {
            activeTrack.also { activeTrack = null }
        }
        cleanupResources(
            { imageSourceController.stop() },
            { track?.let { VideoRenderRegistry.binding(it)?.detach() } },
            { track?.let(VideoRenderRegistry::unregister) },
        )
    }

    /**
     * 准备图片输入并注册可预览、可生成的本地视频轨道。
     *
     * @param prepare 解析输入规格并生成图片帧的挂起函数。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    private suspend fun createStream(
        prepare: suspend () -> Pair<RealtimeVideoFormat, VideoFrame>,
    ): RealtimeMediaStream {
        if (currentTrack != null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Stop the current local image stream before creating another one",
            )
        }
        var track: RealtimeVideoTrack? = null
        try {
            val source = prepare()
            val localTrack = RealtimeVideoTrack(
                id = LOCAL_VIDEO_TRACK_ID,
                videoFormat = source.first,
            )
            track = localTrack
            rtcManager.useExternalVideoSource()
            VideoRenderRegistry.register(
                localTrack,
                VideoRenderBinding(source.second),
            )
            imageSourceController.start()
            synchronized(stateLock) { activeTrack = localTrack }
            return RealtimeMediaStream(StreamID.LOCAL.value, localTrack)
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { imageSourceController.stop() },
                { track?.let(VideoRenderRegistry::unregister) },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * 提供 ImageController 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val LOCAL_VIDEO_TRACK_ID = "video0"
    }
}
