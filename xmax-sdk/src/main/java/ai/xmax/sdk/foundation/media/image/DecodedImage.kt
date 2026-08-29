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
import kotlin.math.floor

/** 表示 SDK 内部已经完成方向处理和像素解码的图片。 */
internal interface DecodedImage {
    val size: IntSize

    fun makeVideoFrame(videoFormat: RealtimeVideoFormat): VideoFrame
}

/** 持有方向已经规范化的 Android Bitmap，并生成可复用的 RGBA 视频帧。 */
internal class BitmapDecodedImage(
    private val bitmap: Bitmap,
) : DecodedImage {
    override val size: IntSize = IntSize(bitmap.width, bitmap.height)

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
                centerCropRect(
                    sourceWidth = bitmap.width,
                    sourceHeight = bitmap.height,
                    targetWidth = videoFormat.width,
                    targetHeight = videoFormat.height,
                ),
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

    internal companion object {
        fun centerCropRect(
            sourceWidth: Int,
            sourceHeight: Int,
            targetWidth: Int,
            targetHeight: Int,
        ): Rect {
            val targetAspectRatio = targetWidth.toDouble() / targetHeight
            val sourceAspectRatio = sourceWidth.toDouble() / sourceHeight
            var cropWidth = sourceWidth
            var cropHeight = sourceHeight
            if (sourceAspectRatio > targetAspectRatio) {
                cropWidth = floor(sourceHeight * targetAspectRatio).toInt().coerceAtLeast(1)
            } else if (sourceAspectRatio < targetAspectRatio) {
                cropHeight = floor(sourceWidth / targetAspectRatio).toInt().coerceAtLeast(1)
            }
            val left = (sourceWidth - cropWidth) / 2
            val top = (sourceHeight - cropHeight) / 2
            return Rect(left, top, left + cropWidth, top + cropHeight)
        }

        private fun processingError(message: String): XmaxError =
            XmaxError(XmaxErrorCode.MEDIA_ERROR, message)

        private const val RGBA_BYTES_PER_PIXEL = 4
    }
}
