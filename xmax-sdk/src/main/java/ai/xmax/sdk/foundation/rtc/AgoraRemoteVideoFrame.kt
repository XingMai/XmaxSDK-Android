package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame
import io.agora.base.VideoFrame
import java.nio.ByteBuffer

/**
 * 借用声网回调帧；仅在接入方需要像素时复制 I420，显示由原生 VideoCanvas 完成。
 */
internal class AgoraRemoteVideoFrame(private val frame: VideoFrame) : RtcRemoteVideoFrame {
    override val displayWidth: Int get() = frame.rotatedWidth
    override val displayHeight: Int get() = frame.rotatedHeight
    override val width: Int get() = frame.buffer.width
    override val height: Int get() = frame.buffer.height

    override fun copy(): RealtimeVideoFrame {
        val i420 = frame.buffer.toI420()
        try {
            return RealtimeVideoFrame(
                width = width,
                height = height,
                presentationTimeUs = frame.timestampNs / 1_000L,
                durationUs = null,
                rotationDegrees = frame.rotation,
                y = copyPlane(i420.dataY, i420.strideY, width, height),
                u = copyPlane(i420.dataU, i420.strideU, (width + 1) / 2, (height + 1) / 2),
                v = copyPlane(i420.dataV, i420.strideV, (width + 1) / 2, (height + 1) / 2),
            )
        } finally {
            i420.release()
        }
    }

    private fun copyPlane(buffer: ByteBuffer, stride: Int, columns: Int, rows: Int): ByteArray {
        val source = buffer.duplicate()
        require(stride >= columns && (rows - 1L) * stride + columns <= source.remaining())
        val start = source.position()
        return ByteArray(columns * rows).also { bytes ->
            repeat(rows) { row ->
                source.position(start + row * stride)
                source.get(bytes, row * columns, columns)
            }
        }
    }
}
