//
//  MediaSourceControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxVideoView
import android.net.Uri

/**
 * 定义本地视频文件准备、循环播放和预览能力。
 */
internal interface MediaSourceControlling {
    /**
     * 当前输入媒体的音轨能力。
     */
    val hasAudio: Boolean

    /**
     * 读取视频元数据并准备保持原始比例的本地视频输入。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可供媒体输入和解码使用的最终配置。
     */
    suspend fun prepare(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): MediaSourceConfiguration

    /**
     * 启动已准备的视频输入，循环解码并输出音视频帧。
     */
    suspend fun start()

    /**
     * 设置本地预览是否静音，不改变保存的音量值。
     *
     * @param muted 是否静音。
     */
    suspend fun setLocalAudioPreviewMuted(muted: Boolean)

    /**
     * 设置当前本地媒体的预览音量，范围为 0 到 1，不影响提交给 RTC 的音频数据。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    suspend fun setLocalAudioVolume(volume: Float)

    /**
     * 将本地媒体预览绑定到指定视图并应用显示模式。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    fun attachPreview(view: XmaxVideoView, contentMode: VideoContentMode)

    /**
     * 解除指定视图的本地媒体预览绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    fun detachPreview(view: XmaxVideoView)

    /**
     * 停止本地视频输出并释放媒体资源。
     */
    suspend fun stop()
}
