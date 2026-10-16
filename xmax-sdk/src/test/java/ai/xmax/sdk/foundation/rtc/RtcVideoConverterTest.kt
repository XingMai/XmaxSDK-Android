package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.*
import io.agora.rtc2.Constants
import io.agora.rtc2.video.AgoraVideoFrame
import io.agora.rtc2.video.VideoCanvas
import io.agora.rtc2.video.VideoEncoderConfiguration
import org.junit.Assert.*
import org.junit.Test

class RtcVideoConverterTest {
    @Test
    fun `encoder mirror follows camera and resets for external input without changing encoding budget`() {
        val configuration = VideoEncodingConfiguration(1024, 1920, 30, 100, 2000)
        val positions = listOf(CameraPosition.FRONT, CameraPosition.BACK, CameraPosition.FRONT, null)

        positions.forEach { position ->
            val result = RtcVideoConverter.makeEncoderConfiguration(configuration, position)
            val expected = if (position == CameraPosition.FRONT) {
                VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_ENABLED
            } else {
                VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED
            }

            assertEquals(expected, result.mirrorMode)
            assertEquals(1024, result.dimensions.width)
            assertEquals(1920, result.dimensions.height)
            assertEquals(30, result.frameRate)
            assertEquals(100, result.minBitrate)
            assertEquals(2000, result.bitrate)
        }
    }

    @Test
    fun `local preview mirrors automatically while remote rendering stays unmirrored`() {
        VideoContentMode.entries.forEach { mode ->
            val local = RtcVideoConverter.makeLocalCanvas(null, mode)
            val remote = RtcVideoConverter.makeCanvas(null, mode, uid = 123)

            assertEquals(Constants.VIDEO_MIRROR_MODE_AUTO, local.mirrorMode)
            assertEquals(Constants.VIDEO_MIRROR_MODE_DISABLED, remote.mirrorMode)
            assertEquals(RtcVideoConverter.convertRenderMode(mode), local.renderMode)
            assertEquals(RtcVideoConverter.convertRenderMode(mode), remote.renderMode)
            assertEquals(0, local.uid)
            assertEquals(123, remote.uid)
        }
    }

    @Test fun `render modes preserve aspect ratio`() {
        assertEquals(VideoCanvas.RENDER_MODE_FIT, RtcVideoConverter.convertRenderMode(VideoContentMode.FIT))
        assertEquals(VideoCanvas.RENDER_MODE_HIDDEN, RtcVideoConverter.convertRenderMode(VideoContentMode.FILL))
    }

    @Test fun `NV12 plane ranges padding rotation and microseconds are converted`() {
        val frame = VideoFrame(VideoFormat(2, 2, VideoPixelFormat.NV12), 12_345, listOf(
            VideoFramePlane(byteArrayOf(99, 1, 2, 0, 3, 4, 0), 3, byteOffset = 1, byteLength = 6),
            VideoFramePlane(byteArrayOf(99, 5, 6), 2, byteOffset = 1, byteLength = 2),
        ), VideoRotation.ROTATION_90)
        val converted = RtcVideoConverter.convertFrame(frame)
        assertEquals(AgoraVideoFrame.FORMAT_NV21, converted.format)
        assertEquals(2, converted.stride)
        assertEquals(2, converted.height)
        assertEquals(12L, converted.timeStamp)
        assertEquals(90, converted.rotation)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 6, 5), converted.buf)
    }

    @Test fun `I420 remains planar and ARGB converts to RGBA`() {
        val planar = VideoFrame(VideoFormat(2, 2, VideoPixelFormat.I420), 0, listOf(
            VideoFramePlane(byteArrayOf(1, 2, 3, 4), 2),
            VideoFramePlane(byteArrayOf(5), 1), VideoFramePlane(byteArrayOf(6), 1),
        ))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), RtcVideoConverter.convertFrame(planar).buf)
        val packed = VideoFrame(VideoFormat(1, 1, VideoPixelFormat.ARGB), 0, listOf(
            VideoFramePlane(byteArrayOf(4, 3, 2, 1), 4),
        ))
        assertArrayEquals(byteArrayOf(3, 2, 1, 4), RtcVideoConverter.convertFrame(packed).buf)
    }

    @Test fun `invalid planes fail before native submission`() {
        val frame = VideoFrame(VideoFormat(2, 2, VideoPixelFormat.I420), 0, listOf(VideoFramePlane(ByteArray(4), 2)))
        assertThrows(IllegalArgumentException::class.java) { RtcVideoConverter.convertFrame(frame) }
        val short = VideoFrame(VideoFormat(2, 2, VideoPixelFormat.RGBA), 0, listOf(VideoFramePlane(ByteArray(4), 8)))
        assertThrows(IllegalArgumentException::class.java) { RtcVideoConverter.convertFrame(short) }
    }

    @Test fun `encoder maps bitrate orientation and degradation preference`() {
        VideoEncodingConfiguration.EncoderPreference.entries.forEach { preference ->
            val config = RtcVideoConverter.makeEncoderConfiguration(VideoEncodingConfiguration(
                1024, 1920, 30, 100, 2000, preference,
            ))
            assertEquals(1024, config.dimensions.width)
            assertEquals(1920, config.dimensions.height)
            assertEquals(30, config.frameRate)
            assertEquals(100, config.minBitrate)
            assertEquals(2000, config.bitrate)
            assertEquals(VideoEncoderConfiguration.MIRROR_MODE_TYPE.MIRROR_MODE_DISABLED, config.mirrorMode)
            assertEquals(VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT, config.orientationMode)
            assertEquals(preference.name, config.degradationPrefer.name.removePrefix("MAINTAIN_").let {
                if (preference == VideoEncodingConfiguration.EncoderPreference.AUTO) it else config.degradationPrefer.name
            })
        }
    }
}
