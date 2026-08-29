package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.VideoPixelFormat
import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import com.ss.bytertc.engine.VideoEncoderConfig
import com.ss.bytertc.engine.data.VideoBufferType
import com.ss.bytertc.engine.data.VideoContentType
import com.ss.bytertc.engine.data.VideoFrameData
import com.ss.bytertc.engine.data.VideoPixelFormat as VolcVideoPixelFormat
import com.ss.bytertc.engine.data.VideoRotation as VolcVideoRotation
import java.nio.ByteBuffer

/** 在 Xmax 中性视频配置与火山 RTC 类型之间转换。 */
internal object RtcVideoConverter {
    fun convertFrame(
        frame: VideoFrame,
        seiData: ByteArray? = null,
    ): VideoFrameData {
        val expectedPlaneCount = expectedPlaneCount(frame.format.pixelFormat)
        if (frame.planes.size != expectedPlaneCount) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame requires $expectedPlaneCount data planes",
            )
        }

        return VideoFrameData().apply {
            bufferType = VideoBufferType.RAW_MEMORY
            pixelFormat = convertPixelFormat(frame.format.pixelFormat)
            contentType = VideoContentType.NORMAL_FRAME
            timestampUs = frame.timestampUs
            width = frame.format.width
            height = frame.format.height
            rotation = convertRotation(frame.rotation)
            numberOfPlanes = expectedPlaneCount
            planeData = frame.planes.map { plane ->
                when (frame.format.pixelFormat) {
                    VideoPixelFormat.BGRA,
                    VideoPixelFormat.ARGB,
                    -> directByteBuffer(
                        convertPackedToRgba(
                            bytes = plane.selectedBytes(),
                            pixelFormat = frame.format.pixelFormat,
                        ),
                    )

                    else -> plane.byteBuffer()
                }
            }.toTypedArray()
            planeStride = frame.planes.map { it.stride }.toIntArray()
            this.seiData = seiData?.let(::directByteBuffer)
        }
    }

    fun convertPixelFormat(pixelFormat: VideoPixelFormat): VolcVideoPixelFormat =
        when (pixelFormat) {
            VideoPixelFormat.I420 -> VolcVideoPixelFormat.I420
            VideoPixelFormat.NV12 -> VolcVideoPixelFormat.NV12
            VideoPixelFormat.NV21 -> VolcVideoPixelFormat.NV21
            VideoPixelFormat.RGBA,
            VideoPixelFormat.BGRA,
            VideoPixelFormat.ARGB,
            -> VolcVideoPixelFormat.RGBA
        }

    fun convertRotation(rotation: VideoRotation): VolcVideoRotation = when (rotation) {
        VideoRotation.ROTATION_0 -> VolcVideoRotation.VIDEO_ROTATION_0
        VideoRotation.ROTATION_90 -> VolcVideoRotation.VIDEO_ROTATION_90
        VideoRotation.ROTATION_180 -> VolcVideoRotation.VIDEO_ROTATION_180
        VideoRotation.ROTATION_270 -> VolcVideoRotation.VIDEO_ROTATION_270
    }

    fun makeEncoderConfiguration(
        configuration: VideoEncodingConfiguration,
    ): VideoEncoderConfig = VideoEncoderConfig().apply {
        width = configuration.width
        height = configuration.height
        frameRate = configuration.frameRate
        minBitrate = configuration.minimumBitrate
        maxBitrate = configuration.maximumBitrate
        encodePreference = VideoEncoderConfig.EncoderPreference.MAINTAIN_FRAMERATE
    }

    private fun expectedPlaneCount(pixelFormat: VideoPixelFormat): Int = when (pixelFormat) {
        VideoPixelFormat.I420 -> 3
        VideoPixelFormat.NV12,
        VideoPixelFormat.NV21,
        -> 2

        VideoPixelFormat.RGBA,
        VideoPixelFormat.BGRA,
        VideoPixelFormat.ARGB,
        -> 1
    }

    private fun convertPackedToRgba(
        bytes: ByteArray,
        pixelFormat: VideoPixelFormat,
    ): ByteArray = bytes.copyOf().also { output ->
        var index = 0
        while (index + 3 < bytes.size) {
            when (pixelFormat) {
                VideoPixelFormat.BGRA -> {
                    output[index] = bytes[index + 2]
                    output[index + 1] = bytes[index + 1]
                    output[index + 2] = bytes[index]
                    output[index + 3] = bytes[index + 3]
                }

                VideoPixelFormat.ARGB -> {
                    output[index] = bytes[index + 1]
                    output[index + 1] = bytes[index + 2]
                    output[index + 2] = bytes[index + 3]
                    output[index + 3] = bytes[index]
                }

                else -> Unit
            }
            index += PACKED_PIXEL_BYTE_COUNT
        }
    }

    private fun directByteBuffer(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }

    private const val PACKED_PIXEL_BYTE_COUNT: Int = 4
}
