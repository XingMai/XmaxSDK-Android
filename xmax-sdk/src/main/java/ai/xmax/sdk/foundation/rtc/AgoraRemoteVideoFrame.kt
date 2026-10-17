//
//  AgoraRemoteVideoFrame.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame
import io.agora.base.VideoFrame
import java.nio.ByteBuffer

/**
 * 借用声网回调帧；仅在接入方需要像素时复制 I420，显示由原生 VideoCanvas 完成。
 *
 * @param frame 待处理、显示或提交的视频帧。
 */
internal class AgoraRemoteVideoFrame(private val frame: VideoFrame) : RtcRemoteVideoFrame {
    /**
     * 应用旋转后的显示尺寸。
     */
    override val displayWidth: Int get() = frame.rotatedWidth
    override val displayHeight: Int get() = frame.rotatedHeight

    /**
     * 底层视频帧的原始尺寸。
     */
    override val width: Int get() = frame.buffer.width
    override val height: Int get() = frame.buffer.height

    /**
     * 复制远端帧像素及元数据，使调用方可在回调结束后继续使用。
     *
     * @return 像素数据可由调用方独立持有的远端视频帧。
     */
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

    /**
     * 复制像素平面的有效行列，去除行尾填充。
     *
     * @param buffer 包含待处理数据的字节缓冲区。
     * @param stride 相邻像素行起点之间的字节距离。
     * @param columns 每行需要读取的有效样本数。
     * @param rows 需要读取的有效行数。
     * @return 有效数据对应的字节数组。
     */
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
