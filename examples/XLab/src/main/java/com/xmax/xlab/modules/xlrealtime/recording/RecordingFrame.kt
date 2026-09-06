package com.xmax.xlab.modules.xlrealtime.recording

import ai.xmax.sdk.RealtimeVideoFrame
import java.nio.ByteBuffer

/** 只引用 SDK 已持有的像素；不在帧回调线程重复复制或编码。 */
internal data class RecordingFrame(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val presentationTimeUs: Long,
    val durationUs: Long?,
    val y: ByteBuffer,
    val u: ByteBuffer,
    val v: ByteBuffer,
) {
    val format get() = RecordingFormat(width, height, rotationDegrees)

    companion object {
        fun from(frame: RealtimeVideoFrame) = RecordingFrame(
            frame.width, frame.height, frame.rotationDegrees, frame.presentationTimeUs, frame.durationUs,
            frame.yData, frame.uData, frame.vData,
        )
    }
}

internal data class RecordingFormat(val width: Int, val height: Int, val rotationDegrees: Int) {
    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 &&
            width.toLong() * height * 3 / 2 <= Int.MAX_VALUE && rotationDegrees in setOf(0, 90, 180, 270)
        ) { "录制视频的尺寸或旋转角度无效" }
    }
}

/** 首帧归零；丢帧保留时间间隔，重复或回退的源时间戳按单帧时长继续。 */
internal class RecordingTimeline {
    private var origin: Long? = null
    var lastTimeUs: Long = -1
        private set
    private var lastDurationUs = DEFAULT_DURATION_US
    val endTimeUs: Long get() = Math.addExact(lastTimeUs, lastDurationUs)

    fun next(sourceTimeUs: Long, durationUs: Long?): Long {
        lastDurationUs = durationUs?.takeIf { it > 0 } ?: DEFAULT_DURATION_US
        if (lastTimeUs < 0) {
            origin = sourceTimeUs
            lastTimeUs = 0
        } else {
            val candidate = runCatching { Math.subtractExact(sourceTimeUs, origin!!) }.getOrNull()
            lastTimeUs = candidate?.takeIf { it > lastTimeUs } ?: Math.addExact(lastTimeUs, lastDurationUs)
        }
        return lastTimeUs
    }

    companion object { const val DEFAULT_DURATION_US = 1_000_000L / 24 }
}

/** 写入 MediaCodec 的灵活 YUV 平面，兼容行填充和交错色度，不改变调用方 buffer 位置。 */
internal fun copyI420Plane(
    source: ByteBuffer,
    target: ByteBuffer,
    columns: Int,
    rows: Int,
    rowStride: Int,
    pixelStride: Int,
    offset: Int = 0,
) {
    require(columns > 0 && rows > 0 && pixelStride > 0 && rowStride >= (columns - 1L) * pixelStride + 1)
    require(source.remaining().toLong() >= columns.toLong() * rows)
    require(offset >= 0 && offset + (rows - 1L) * rowStride + (columns - 1L) * pixelStride < target.remaining())
    val input = source.duplicate()
    val output = target.duplicate()
    val start = output.position() + offset
    repeat(rows) { row ->
        val rowStart = start + row * rowStride
        if (pixelStride == 1) {
            val slice = input.slice().apply { limit(columns) }
            output.position(rowStart)
            output.put(slice)
            input.position(input.position() + columns)
        } else {
            repeat(columns) { col -> output.put(rowStart + col * pixelStride, input.get()) }
        }
    }
}
