package ai.xmax.sdk.media.video

import ai.xmax.sdk.VideoFormat
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoFramePlane
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import java.nio.ByteBuffer

/** 将 Android 解码器的灵活 YUV 420 输出转换为 RTC 可直接消费的紧凑 I420 帧。 */
internal object Yuv420VideoFrameConverter {
    fun convert(
        image: Image,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        timestampUs: Long,
    ): VideoFrame {
        if (image.format != ImageFormat.YUV_420_888 || image.planes.size < PLANE_COUNT) {
            throw mediaError("The video decoder output is not YUV 4:2:0")
        }
        val crop = image.cropRect
        return convert(
            source = Source(
                width = image.width,
                height = image.height,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropWidth = crop.width(),
                cropHeight = crop.height(),
                planes = image.planes.take(PLANE_COUNT).map { plane ->
                    Plane(
                        buffer = plane.buffer.duplicate(),
                        rowStride = plane.rowStride,
                        pixelStride = plane.pixelStride,
                        baseOffset = plane.buffer.position(),
                    )
                },
            ),
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            rotation = rotation,
            timestampUs = timestampUs,
        )
    }

    internal fun convert(
        source: Source,
        outputWidth: Int,
        outputHeight: Int,
        rotation: VideoRotation,
        timestampUs: Long,
    ): VideoFrame {
        validate(source, outputWidth, outputHeight)
        val mapping = SourceMapping(source, outputWidth, outputHeight, rotation)
        val y = ByteArray(outputWidth * outputHeight)
        val chromaWidth = outputWidth / CHROMA_SUBSAMPLING
        val chromaHeight = outputHeight / CHROMA_SUBSAMPLING
        val u = ByteArray(chromaWidth * chromaHeight)
        val v = ByteArray(chromaWidth * chromaHeight)

        copyLuma(source.planes[Y_PLANE], mapping, y)
        copyChroma(
            uPlane = source.planes[U_PLANE],
            vPlane = source.planes[V_PLANE],
            mapping = mapping,
            outputWidth = outputWidth,
            outputHeight = outputHeight,
            u = u,
            v = v,
        )

        return VideoFrame(
            format = VideoFormat(outputWidth, outputHeight, VideoPixelFormat.I420),
            timestampUs = timestampUs,
            planes = listOf(
                VideoFramePlane(y, stride = outputWidth, copyData = false),
                VideoFramePlane(u, stride = chromaWidth, copyData = false),
                VideoFramePlane(v, stride = chromaWidth, copyData = false),
            ),
        )
    }

    /** 仅供低频本地预览；RTC 上行始终直接使用 I420 数据。 */
    fun makePreviewBitmap(frame: VideoFrame): Bitmap {
        if (frame.format.pixelFormat != VideoPixelFormat.I420 || frame.planes.size != PLANE_COUNT) {
            throw IllegalArgumentException("Decoded video preview requires an I420 frame")
        }
        val width = frame.format.width
        val height = frame.format.height
        val yPlane = frame.planes[Y_PLANE]
        val uPlane = frame.planes[U_PLANE]
        val vPlane = frame.planes[V_PLANE]
        val y = yPlane.selectedBytesView()
        val u = uPlane.selectedBytesView()
        val v = vPlane.selectedBytesView()
        val pixels = IntArray(width * height)

        var outputOffset = 0
        for (row in 0 until height) {
            val yRow = row * yPlane.stride
            val chromaRow = row / CHROMA_SUBSAMPLING
            for (column in 0 until width) {
                val luma = (y[yRow + column].toInt() and 0xFF) - LUMA_OFFSET
                val chromaOffset = chromaRow * uPlane.stride + column / CHROMA_SUBSAMPLING
                val chromaU = (u[chromaOffset].toInt() and 0xFF) - CHROMA_OFFSET
                val chromaV = (v[chromaOffset].toInt() and 0xFF) - CHROMA_OFFSET
                val normalizedLuma = luma.coerceAtLeast(0)
                val red = clampColor(
                    (YUV_LUMA_MULTIPLIER * normalizedLuma + YUV_RED_V * chromaV + ROUNDING) shr 8,
                )
                val green = clampColor(
                    (YUV_LUMA_MULTIPLIER * normalizedLuma - YUV_GREEN_U * chromaU -
                        YUV_GREEN_V * chromaV + ROUNDING) shr 8,
                )
                val blue = clampColor(
                    (YUV_LUMA_MULTIPLIER * normalizedLuma + YUV_BLUE_U * chromaU + ROUNDING) shr 8,
                )
                pixels[outputOffset] = OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
                outputOffset += 1
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun copyLuma(
        plane: Plane,
        mapping: SourceMapping,
        output: ByteArray,
    ) {
        var outputOffset = 0
        for (targetY in mapping.targetYs.indices) {
            val displayY = mapping.targetYs[targetY]
            for (targetX in mapping.targetXs.indices) {
                val displayX = mapping.targetXs[targetX]
                val sourceX = mapping.sourceX(displayX, displayY)
                val sourceY = mapping.sourceY(displayX, displayY)
                output[outputOffset] = plane.sample(sourceX, sourceY)
                outputOffset += 1
            }
        }
    }

    private fun copyChroma(
        uPlane: Plane,
        vPlane: Plane,
        mapping: SourceMapping,
        outputWidth: Int,
        outputHeight: Int,
        u: ByteArray,
        v: ByteArray,
    ) {
        var outputOffset = 0
        for (targetY in 0 until outputHeight / CHROMA_SUBSAMPLING) {
            val lumaY = (targetY * CHROMA_SUBSAMPLING + 1).coerceAtMost(outputHeight - 1)
            val displayY = mapping.targetYs[lumaY]
            for (targetX in 0 until outputWidth / CHROMA_SUBSAMPLING) {
                val lumaX = (targetX * CHROMA_SUBSAMPLING + 1).coerceAtMost(outputWidth - 1)
                val displayX = mapping.targetXs[lumaX]
                val sourceX = mapping.sourceX(displayX, displayY) / CHROMA_SUBSAMPLING
                val sourceY = mapping.sourceY(displayX, displayY) / CHROMA_SUBSAMPLING
                u[outputOffset] = uPlane.sample(sourceX, sourceY)
                v[outputOffset] = vPlane.sample(sourceX, sourceY)
                outputOffset += 1
            }
        }
    }

    private fun validate(source: Source, outputWidth: Int, outputHeight: Int) {
        if (source.width <= 0 || source.height <= 0 ||
            source.cropWidth <= 0 || source.cropHeight <= 0 ||
            source.cropLeft < 0 || source.cropTop < 0 ||
            source.cropLeft + source.cropWidth > source.width ||
            source.cropTop + source.cropHeight > source.height ||
            source.planes.size != PLANE_COUNT ||
            outputWidth <= 0 || outputHeight <= 0 ||
            outputWidth % CHROMA_SUBSAMPLING != 0 ||
            outputHeight % CHROMA_SUBSAMPLING != 0
        ) {
            throw mediaError("The decoded video frame dimensions are invalid")
        }
    }

    private fun clampColor(value: Int): Int = value.coerceIn(0, 255)

    internal data class Source(
        val width: Int,
        val height: Int,
        val cropLeft: Int,
        val cropTop: Int,
        val cropWidth: Int,
        val cropHeight: Int,
        val planes: List<Plane>,
    )

    internal data class Plane(
        val buffer: ByteBuffer,
        val rowStride: Int,
        val pixelStride: Int,
        val baseOffset: Int = buffer.position(),
    ) {
        init {
            require(rowStride > 0 && pixelStride > 0 && baseOffset >= 0)
        }

        fun sample(x: Int, y: Int): Byte = buffer.get(
            baseOffset + y * rowStride + x * pixelStride,
        )
    }

    private class SourceMapping(
        private val source: Source,
        outputWidth: Int,
        outputHeight: Int,
        private val rotation: VideoRotation,
    ) {
        private val displayWidth: Int
        private val displayHeight: Int
        val targetXs: IntArray
        val targetYs: IntArray

        init {
            val swapsDimensions = rotation == VideoRotation.ROTATION_90 ||
                rotation == VideoRotation.ROTATION_270
            displayWidth = if (swapsDimensions) source.cropHeight else source.cropWidth
            displayHeight = if (swapsDimensions) source.cropWidth else source.cropHeight
            val scale = maxOf(
                outputWidth.toDouble() / displayWidth,
                outputHeight.toDouble() / displayHeight,
            )
            val visibleWidth = outputWidth / scale
            val visibleHeight = outputHeight / scale
            val displayLeft = (displayWidth - visibleWidth) / 2.0
            val displayTop = (displayHeight - visibleHeight) / 2.0
            targetXs = IntArray(outputWidth) { targetX ->
                (displayLeft + (targetX + 0.5) / scale)
                    .toInt().coerceIn(0, displayWidth - 1)
            }
            targetYs = IntArray(outputHeight) { targetY ->
                (displayTop + (targetY + 0.5) / scale)
                    .toInt().coerceIn(0, displayHeight - 1)
            }
        }

        fun sourceX(displayX: Int, displayY: Int): Int = source.cropLeft + when (rotation) {
            VideoRotation.ROTATION_0 -> displayX
            VideoRotation.ROTATION_90 -> displayY
            VideoRotation.ROTATION_180 -> source.cropWidth - 1 - displayX
            VideoRotation.ROTATION_270 -> source.cropWidth - 1 - displayY
        }

        fun sourceY(displayX: Int, displayY: Int): Int = source.cropTop + when (rotation) {
            VideoRotation.ROTATION_0 -> displayY
            VideoRotation.ROTATION_90 -> source.cropHeight - 1 - displayX
            VideoRotation.ROTATION_180 -> source.cropHeight - 1 - displayY
            VideoRotation.ROTATION_270 -> displayX
        }
    }

    private fun mediaError(message: String): XmaxError = XmaxError(
        code = XmaxErrorCode.MEDIA_ERROR,
        message = message,
    )

    private const val PLANE_COUNT = 3
    private const val Y_PLANE = 0
    private const val U_PLANE = 1
    private const val V_PLANE = 2
    private const val CHROMA_SUBSAMPLING = 2
    private const val LUMA_OFFSET = 16
    private const val CHROMA_OFFSET = 128
    private const val YUV_LUMA_MULTIPLIER = 298
    private const val YUV_RED_V = 409
    private const val YUV_GREEN_U = 100
    private const val YUV_GREEN_V = 208
    private const val YUV_BLUE_U = 516
    private const val ROUNDING = 128
    private const val OPAQUE_ALPHA = -0x1000000
}
