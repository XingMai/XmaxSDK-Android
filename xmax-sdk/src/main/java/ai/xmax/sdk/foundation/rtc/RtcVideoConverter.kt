package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.VideoPixelFormat
import android.view.View
import io.agora.rtc2.Constants
import io.agora.rtc2.video.AgoraVideoFrame
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration

/**
 * 将中性视频配置转换为声网类型；输出缓冲区仅供同步提交使用。
 */
internal object RtcVideoConverter {
    /**
     * 本地预览随摄像头自动切换镜像：前置开启、后置关闭；上传编码单独配置。
     */
    fun makeLocalCanvas(view: View?, contentMode: VideoContentMode): VideoCanvas =
        makeCanvas(view, contentMode).apply {
            mirrorMode = Constants.VIDEO_MIRROR_MODE_AUTO
        }

    fun makeCanvas(view: View?, contentMode: VideoContentMode, uid: Int = 0): VideoCanvas =
        VideoCanvas(view, convertRenderMode(contentMode), uid).apply {
            mirrorMode = Constants.VIDEO_MIRROR_MODE_DISABLED
        }

    fun convertRenderMode(contentMode: VideoContentMode): Int = when (contentMode) {
        VideoContentMode.FIT -> VideoCanvas.RENDER_MODE_FIT
        VideoContentMode.FILL -> VideoCanvas.RENDER_MODE_HIDDEN
    }

    /**
     * 整理平面 stride；声网数组帧只接受连续布局，时间戳单位为毫秒。
     */
    fun convertFrame(frame: VideoFrame, seiData: ByteArray? = null): AgoraVideoFrame {
        val width = frame.format.width
        val height = frame.format.height
        val pixel = frame.format.pixelFormat
        val expected = when (pixel) {
            VideoPixelFormat.I420 -> 3
            VideoPixelFormat.NV12, VideoPixelFormat.NV21 -> 2
            else -> 1
        }
        require(frame.planes.size == expected) { "Video frame requires $expected data planes" }
        val packed = pixel in setOf(VideoPixelFormat.RGBA, VideoPixelFormat.BGRA, VideoPixelFormat.ARGB)
        val rows = if (packed) listOf(height) else listOf(height, height / 2, height / 2).take(expected)
        val columns = if (packed) listOf(width * 4) else if (expected == 3) listOf(width, width / 2, width / 2) else listOf(width, width)
        val size = rows.indices.sumOf { rows[it] * columns[it] }
        val data = if (packed && pixel != VideoPixelFormat.ARGB && frame.planes[0].stride == width * 4 && frame.planes[0].byteLength == size) {
            frame.planes[0].selectedBytesView()
        } else {
            ByteArray(size).also { output ->
                var offset = 0
                frame.planes.forEachIndexed { index, plane ->
                    val source = plane.byteBuffer()
                    require(plane.stride >= columns[index])
                    require((rows[index] - 1L) * plane.stride + columns[index] <= source.remaining())
                    repeat(rows[index]) { row ->
                        source.position(row * plane.stride)
                        source.get(output, offset, columns[index])
                        offset += columns[index]
                    }
                }
                if (pixel == VideoPixelFormat.ARGB) {
                    for (i in output.indices step 4) {
                        val alpha = output[i]
                        output[i] = output[i + 1]
                        output[i + 1] = output[i + 2]
                        output[i + 2] = output[i + 3]
                        output[i + 3] = alpha
                    }
                }
                if (pixel == VideoPixelFormat.NV12) {
                    for (i in width * height until output.size step 2) {
                        val u = output[i]
                        output[i] = output[i + 1]
                        output[i + 1] = u
                    }
                }
            }
        }
        return AgoraVideoFrame().apply {
            format = when (pixel) {
                VideoPixelFormat.I420 -> AgoraVideoFrame.FORMAT_I420
                VideoPixelFormat.NV12, VideoPixelFormat.NV21 -> AgoraVideoFrame.FORMAT_NV21
                VideoPixelFormat.BGRA -> AgoraVideoFrame.FORMAT_BGRA
                else -> AgoraVideoFrame.FORMAT_RGBA
            }
            buf = data
            stride = width
            this.height = height
            rotation = frame.rotation.degrees
            timeStamp = frame.timestampUs / 1_000L
            metadataBuffer = seiData
        }
    }

    /**
     * 前置摄像头的上行镜像与预览保持一致；后置及外部输入（position 为 null）不镜像。
     */
    fun makeEncoderConfiguration(
        configuration: VideoEncodingConfiguration,
        cameraPosition: CameraPosition? = null,
    ): VideoEncoderConfiguration =
        VideoEncoderConfiguration().apply {
            dimensions = VideoEncoderConfiguration.VideoDimensions(configuration.width, configuration.height)
            frameRate = configuration.frameRate
            bitrate = configuration.maximumBitrate
            minBitrate = configuration.minimumBitrate
            orientationMode = if (configuration.width > configuration.height) {
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_LANDSCAPE
            } else {
                VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT
            }
            mirrorMode = if (cameraPosition == CameraPosition.FRONT) {
                VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_ENABLED
            } else {
                VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
            }
            degradationPrefer = when (configuration.encoderPreference) {
                VideoEncodingConfiguration.EncoderPreference.AUTO -> VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_AUTO
                VideoEncodingConfiguration.EncoderPreference.MAINTAIN_FRAMERATE -> VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_FRAMERATE
                VideoEncodingConfiguration.EncoderPreference.MAINTAIN_QUALITY -> VideoEncoderConfiguration.DEGRADATION_PREFERENCE.MAINTAIN_QUALITY
            }
        }
}
