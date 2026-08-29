package ai.xmax.sdk

import java.util.UUID

/** 持有中性像素数据及时间信息的视频帧。 */
internal class VideoFrame(
    val format: VideoFormat,
    val timestampUs: Long,
    planes: List<VideoFramePlane>,
    val rotation: VideoRotation = VideoRotation.ROTATION_0,
    val bufferReuseId: UUID? = null,
) {
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

    /** 复用像素数据并更新逐帧变化的信息。 */
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

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VideoFrame &&
            format == other.format &&
            timestampUs == other.timestampUs &&
            planes == other.planes &&
            rotation == other.rotation &&
            bufferReuseId == other.bufferReuseId

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + planes.hashCode()
        result = 31 * result + rotation.hashCode()
        result = 31 * result + (bufferReuseId?.hashCode() ?: 0)
        return result
    }
}
