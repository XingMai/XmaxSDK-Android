//
//  VideoFrame.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.UUID

/**
 * 持有中性像素数据及时间信息的视频帧。
 *
 * @param format 视频像素尺寸、排列格式及旋转信息。
 * @param timestampUs 帧的单调时间戳，单位为微秒。
 * @param planes 按像素格式顺序排列的像素平面。
 * @param rotation 应用到源画面的旋转方向；默认值为 `VideoRotation.ROTATION_0`。
 * @param bufferReuseId 用于识别可复用像素缓冲的标识；null 表示没有复用标识。
 */
internal class VideoFrame(
    val format: VideoFormat,
    val timestampUs: Long,
    planes: List<VideoFramePlane>,
    val rotation: VideoRotation = VideoRotation.ROTATION_0,
    val bufferReuseId: UUID? = null,
) {
    /**
     * 视频或图片的尺寸与像素数据。
     */
    val planes: List<VideoFramePlane> = planes.toList()

    init {
        if (timestampUs < 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame timestamp must be non-negative",
            )
        }
        if (planes.isEmpty()) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Video frame must contain at least one plane",
            )
        }
    }

    /**
     * 复用像素数据并更新逐帧变化的信息。
     *
     * @param timestampUs 帧的单调时间戳，单位为微秒。
     * @param rotation 应用到源画面的旋转方向；默认值为 `this.rotation`。
     * @return 按目标像素格式、尺寸和方向生成的视频帧。
     */
    fun updating(
        timestampUs: Long,
        rotation: VideoRotation = this.rotation,
    ): VideoFrame = VideoFrame(
        format = format,
        timestampUs = timestampUs,
        planes = planes,
        rotation = rotation,
        bufferReuseId = bufferReuseId,
    )

    /**
     * 比较对象的值是否相等。
     *
     * @param other 用于比较或组合的另一个对象。
     * @return 对象的内容相等时为 true，否则为 false。
     */
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VideoFrame &&
            format == other.format &&
            timestampUs == other.timestampUs &&
            planes == other.planes &&
            rotation == other.rotation &&
            bufferReuseId == other.bufferReuseId

    /**
     * 生成与值相等规则一致的哈希值。
     *
     * @return 与值相等规则一致的哈希值。
     */
    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + planes.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + (bufferReuseId?.hashCode() ?: 0)
        return result
    }
}
