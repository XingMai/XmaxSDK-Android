package ai.xmax.sdk.foundation.media.image

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream

/** 使用 Android BitmapFactory 提供 SDK 内部图片解码能力。 */
internal class ImageManager : ImageManaging {
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

    override fun decode(bitmap: Bitmap): DecodedImage {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            throw processingError("Bitmap image is unavailable")
        }
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw processingError("Failed to copy bitmap image")
        return BitmapDecodedImage(copy)
    }

    private fun readOrientation(data: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(data)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

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

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            ?: throw processingError("Failed to normalize image pixels")
        if (!bitmap.isRecycled) bitmap.recycle()
        return copy
    }

    private fun processingError(message: String): XmaxError =
        XmaxError(XmaxErrorCode.MEDIA_ERROR, message)
}
