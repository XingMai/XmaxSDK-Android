//
//  VideoFramePlane.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.nio.ByteBuffer

/**
 * 表示视频帧中的一个像素数据平面。
 *
 * @param data 待读取、传输或持有的二进制数据。
 * @param stride 相邻像素行起点之间的字节距离。
 * @param byteOffset 有效数据的起始字节偏移；默认值为 `0`。
 * @param byteLength 有效数据字节数；null 表示使用偏移后的剩余数据。
 * @param copyData 是否复制像素数据，使帧不依赖调用方缓冲的后续修改；默认值为 `true`。
 */
internal class VideoFramePlane(
    data: ByteArray,
    val stride: Int,
    val byteOffset: Int = 0,
    byteLength: Int? = null,
    copyData: Boolean = true,
) {
    /**
     * 像素平面的有效字节范围及缓冲访问接口。
     */
    private val bytes: ByteArray = if (copyData) data.copyOf() else data

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

    /**
     * 取得当前像素平面有效范围的字节缓冲区。
     *
     * @return 有效范围对应的字节缓冲区。
     */
    internal fun byteBuffer(): ByteBuffer = directBytes.duplicate().apply {
        position(0)
        limit(byteLength)
    }

    /**
     * 复制缓冲区当前有效范围内的字节。
     *
     * @return 有效数据对应的字节数组。
     */
    internal fun selectedBytes(): ByteArray = bytes.copyOfRange(
        fromIndex = byteOffset,
        toIndex = byteOffset + byteLength,
    )

    /**
     * 仅供同步只读的平台转换使用；调用方不得修改返回数组。
     *
     * @return 有效数据对应的字节数组。
     */
    internal fun selectedBytesView(): ByteArray = if (
        byteOffset == 0 && byteLength == bytes.size
    ) {
        bytes
    } else {
        selectedBytes()
    }

    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VideoFramePlane &&
            stride == other.stride &&
            byteOffset == other.byteOffset &&
            byteLength == other.byteLength &&
            bytes.contentEquals(other.bytes)

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + stride
        result = 31 * result + byteOffset
        result = 31 * result + byteLength
        return result
    }

    /**
     * 提供 VideoFramePlane 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 创建用于报告像素平面字节范围无效的错误。
         *
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        fun invalidRangeError(): XmaxError = XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "Video frame plane range is invalid",
        )
    }
}
