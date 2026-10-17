//
//  ImageManaging.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media.image

import android.graphics.Bitmap

/**
 * 定义 SDK 内部使用的平台图片解码能力。
 */
internal interface ImageManaging {
    /**
     * 解码图片文件字节并校正 EXIF 方向。
     *
     * @param data 待读取、传输或持有的二进制数据。
     * @return 已解码并校正方向的图片数据。
     */
    fun decode(data: ByteArray): DecodedImage

    /**
     * 将 Bitmap 转换为可用于生成视频帧的图片数据。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 已解码并校正方向的图片数据。
     */
    fun decode(bitmap: Bitmap): DecodedImage
}
