//
//  ImageSourceControlling.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.foundation.media.image.DecodedImage
import android.graphics.Bitmap
import android.net.Uri

/**
 * 定义本地图片持续输出视频帧的能力。
 */
internal interface ImageSourceControlling {
    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @return 图片输入的视频规格及对应视频帧。
     */
    suspend fun prepare(
        imageData: ByteArray,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 图片输入的视频规格及对应视频帧。
     */
    suspend fun prepare(
        bitmap: Bitmap,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 图片输入的视频规格及对应视频帧。
     */
    suspend fun prepare(
        uri: Uri,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param decodedImage 完成解码及方向校正的图片。
     * @return 图片输入的视频规格及对应视频帧。
     */
    suspend fun prepare(
        decodedImage: DecodedImage,
    ): Pair<RealtimeVideoFormat, VideoFrame>

    /**
     * 开始按配置帧率重复输出当前图片帧。
     */
    fun start()

    /**
     * 停止图片帧输出并释放相关资源。
     */
    suspend fun stop()
}
