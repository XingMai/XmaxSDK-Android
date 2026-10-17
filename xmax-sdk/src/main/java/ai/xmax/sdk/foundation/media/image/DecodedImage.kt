//
//  DecodedImage.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.unit.IntSize
import java.util.UUID

/**
 * 表示 SDK 内部已经完成方向处理和像素解码的图片。
 */
internal interface DecodedImage {
    /**
     * 视频或图片的尺寸与像素数据。
     */
    val size: IntSize

    /**
     * 将已解码图片转换为指定尺寸和帧率对应的 RGBA 视频帧。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @return 按目标像素格式、尺寸和方向生成的视频帧。
     */
    fun makeVideoFrame(videoFormat: RealtimeVideoFormat): VideoFrame
}

/**
 * 持有方向已经规范化的 Android Bitmap，并生成可复用的 RGBA 视频帧。
 *
 * @param bitmap 待处理或显示的 Android 图片。
 */
internal class BitmapDecodedImage(
    private val bitmap: Bitmap,
) : DecodedImage {
    /**
     * 视频或图片的尺寸与像素数据。
     */
    override val size: IntSize = IntSize(bitmap.width, bitmap.height)

    /**
     * 将已解码图片转换为指定尺寸和帧率对应的 RGBA 视频帧。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @return 按目标像素格式、尺寸和方向生成的视频帧。
     */
    override fun makeVideoFrame(videoFormat: RealtimeVideoFormat): VideoFrame {
        videoFormat.validate()
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw processingError("Decoded image is unavailable")
        }

        val target = Bitmap.createBitmap(
            videoFormat.width,
            videoFormat.height,
            Bitmap.Config.ARGB_8888,
        )
        try {
            Canvas(target).drawBitmap(
                bitmap,
                Rect(0, 0, bitmap.width, bitmap.height),
                Rect(0, 0, videoFormat.width, videoFormat.height),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
            val pixels = IntArray(videoFormat.width * videoFormat.height)
            target.getPixels(
                pixels,
                0,
                videoFormat.width,
                0,
                0,
                videoFormat.width,
                videoFormat.height,
            )
            val rgba = ByteArray(pixels.size * RGBA_BYTES_PER_PIXEL)
            pixels.forEachIndexed { index, pixel ->
                val offset = index * RGBA_BYTES_PER_PIXEL
                rgba[offset] = (pixel ushr 16).toByte()
                rgba[offset + 1] = (pixel ushr 8).toByte()
                rgba[offset + 2] = pixel.toByte()
                rgba[offset + 3] = (pixel ushr 24).toByte()
            }
            return VideoFrame(
                format = VideoFormat(
                    width = videoFormat.width,
                    height = videoFormat.height,
                    pixelFormat = VideoPixelFormat.RGBA,
                ),
                timestampUs = 0L,
                planes = listOf(
                    VideoFramePlane(
                        data = rgba,
                        stride = videoFormat.width * RGBA_BYTES_PER_PIXEL,
                        copyData = false,
                    ),
                ),
                bufferReuseId = UUID.randomUUID(),
            )
        } catch (error: XmaxError) {
            throw error
        } catch (error: Throwable) {
            throw XmaxError(
                code = XmaxErrorCode.MEDIA_ERROR,
                message = "Failed to prepare image video frame",
                cause = error,
            )
        } finally {
            target.recycle()
        }
    }

    /**
     * 提供 BitmapDecodedImage 使用的构造入口、共享配置或常量。
     */
    internal companion object {
        /**
         * 创建用于报告图像处理失败的媒体错误。
         *
         * @param message 待发送或记录的消息文本。
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        private fun processingError(message: String): XmaxError =
            XmaxError(XmaxErrorCode.MEDIA_ERROR, message)

        /**
         * 共享常量、默认配置与工厂入口。
         */
        private const val RGBA_BYTES_PER_PIXEL = 4
    }
}
