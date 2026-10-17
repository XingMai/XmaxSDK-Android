//
//  VideoPlayerControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxVideoView
import android.net.Uri

/**
 * 定义基于统一媒体时间轴的文件音视频输出能力。
 */
internal interface VideoPlayerControlling {
    /**
     * 设置解码源、输出尺寸、旋转方向、帧率及播放时长。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param outputWidth 输出画面宽度，单位为像素。
     * @param outputHeight 输出画面高度，单位为像素。
     * @param rotation 应用到源画面的旋转方向。
     * @param frameRate 视频帧率，单位为 fps。
     * @param hasAudio 输入媒体是否包含可用音频。
     * @param durationUs 媒体持续时间，单位为微秒。
     */
    suspend fun configure(
        uri: Uri,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        frameRate: Int,
        hasAudio: Boolean,
        durationUs: Long,
    )

    /**
     * 启动循环解码，并按统一播放时间线输出音视频帧。
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
     * 停止解码播放并释放当前媒体资源。
     */
    suspend fun stop()
}
