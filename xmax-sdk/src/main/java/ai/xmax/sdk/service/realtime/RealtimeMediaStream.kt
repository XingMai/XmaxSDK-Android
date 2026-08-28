package ai.xmax.sdk

import java.util.concurrent.atomic.AtomicReference

/** 实时生成输出的媒体流。 */
public class RealtimeMediaStream internal constructor(
    public val id: String,
    public val videoTrack: RealtimeVideoTrack?,
)

/** 实时视频轨道及其动态元数据。 */
public class RealtimeVideoTrack internal constructor(
    public val id: String,
    videoFormat: RealtimeVideoFormat? = null,
    position: CameraPosition? = null,
) {
    private val metadata = AtomicReference(Metadata(videoFormat, position))

    public val videoFormat: RealtimeVideoFormat?
        get() = metadata.get().videoFormat

    public val position: CameraPosition?
        get() = metadata.get().position

    internal fun updateVideoFormat(videoFormat: RealtimeVideoFormat) {
        metadata.updateAndGet { it.copy(videoFormat = videoFormat) }
    }

    internal fun updatePosition(position: CameraPosition) {
        metadata.updateAndGet { it.copy(position = position) }
    }

    private data class Metadata(
        val videoFormat: RealtimeVideoFormat?,
        val position: CameraPosition?,
    )
}

/** 摄像头首帧已经可以用于预览时触发的监听器。 */
public fun interface RealtimeCameraPreviewReadyListener {
    public fun onCameraPreviewReady()
}
