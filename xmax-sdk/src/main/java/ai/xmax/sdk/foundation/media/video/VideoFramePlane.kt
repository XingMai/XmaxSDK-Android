package ai.xmax.sdk

import java.nio.ByteBuffer

/** 表示视频帧中的一个像素数据平面。 */
internal class VideoFramePlane(
    data: ByteArray,
    val stride: Int,
    val byteOffset: Int = 0,
    byteLength: Int? = null,
) {
    private val bytes: ByteArray = data.copyOf()

    val data: ByteArray
        get() = bytes.copyOf()

    val byteLength: Int = byteLength ?: data.size - byteOffset

    private val directBytes: ByteBuffer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ByteBuffer.allocateDirect(this.byteLength).apply {
            put(bytes, byteOffset, this@VideoFramePlane.byteLength)
            flip()
        }
    }

    init {
        if (
            data.isEmpty() ||
            byteOffset < 0 ||
            byteOffset >= data.size ||
            this.byteLength <= 0 ||
            this.byteLength > data.size - byteOffset
        ) {
            throw invalidRangeError()
        }
        if (stride <= 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame plane stride must be a positive integer",
            )
        }
    }

    internal fun byteBuffer(): ByteBuffer = directBytes.duplicate().apply {
        position(0)
        limit(byteLength)
    }

    internal fun selectedBytes(): ByteArray = bytes.copyOfRange(
        fromIndex = byteOffset,
        toIndex = byteOffset + byteLength,
    )

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VideoFramePlane &&
            stride == other.stride &&
            byteOffset == other.byteOffset &&
            byteLength == other.byteLength &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + stride
        result = 31 * result + byteOffset
        result = 31 * result + byteLength
        return result
    }

    private companion object {
        fun invalidRangeError(): XmaxError = XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Video frame plane range is invalid",
        )
    }
}
