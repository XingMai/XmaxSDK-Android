//
//  ImageManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media.image

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/**
 * 使用 Android BitmapFactory 提供 SDK 内部图片解码能力。
 */
internal class ImageManager : ImageManaging {
    /**
     * 解码图片文件字节并校正 EXIF 方向。
     *
     * @param data 待读取、传输或持有的二进制数据。
     * @return 已解码并校正方向的图片数据。
     */
    override fun decode(data: ByteArray): DecodedImage {
        if (data.isEmpty()) throw processingError("Image source data must not be empty")
        val decoded = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw processingError("Failed to decode image")
        return try {
            BitmapDecodedImage(applyOrientation(decoded, readOrientation(data)))
        } catch (error: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw XmaxError.from(error)
        }
    }

    /**
     * 将 Bitmap 转换为可用于生成视频帧的图片数据。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 已解码并校正方向的图片数据。
     */
    override fun decode(bitmap: Bitmap): DecodedImage {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw processingError("Bitmap image is unavailable")
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw processingError("Failed to copy bitmap image")
        return BitmapDecodedImage(copy)
    }

    /**
     * 读取图片的 EXIF 方向，用于恢复正确的显示方向。
     *
     * @param data 待读取、传输或持有的二进制数据。
     * @return EXIF 方向值；无法读取时使用默认方向。
     */
    private fun readOrientation(data: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(data)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * 按 EXIF 方向旋转或翻转图片。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @param orientation 图片 EXIF 方向值。
     * @return 用于本地显示的图片。
     */
    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return ensureArgb8888(bitmap)
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
        if (transformed !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return ensureArgb8888(transformed)
    }

    /**
     * 取得可按 ARGB_8888 像素格式读取的图片。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 用于本地显示的图片。
     */
    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw processingError("Failed to normalize image pixels")
        if (!bitmap.isRecycled) bitmap.recycle()
        return copy
    }

    /**
     * 创建用于报告图像处理失败的媒体错误。
     *
     * @param message 待发送或记录的消息文本。
     * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
     */
    private fun processingError(message: String): XmaxError =
        XmaxError(XmaxErrorCode.MEDIA_ERROR, message)
}
