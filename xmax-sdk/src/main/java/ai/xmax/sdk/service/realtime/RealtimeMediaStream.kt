//
//  RealtimeMediaStream.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import java.util.concurrent.atomic.AtomicReference

/**
 * 实时生成输出的媒体流。
 *
 * @param id 用于区分当前对象或操作的唯一标识。
 * @param videoTrack 媒体流包含的视频轨道。
 */
public class RealtimeMediaStream internal constructor(
    public val id: String,
    public val videoTrack: RealtimeVideoTrack?,
)

/**
 * 实时视频轨道及其动态元数据。
 *
 * @param id 用于区分当前对象或操作的唯一标识。
 * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
 * @param position 需要使用的前置或后置摄像头。
 */
public class RealtimeVideoTrack internal constructor(
    public val id: String,
    videoFormat: RealtimeVideoFormat? = null,
    position: CameraPosition? = null,
) {
    /**
     * 轨道的当前视频规格与摄像头方向。
     */
    private val metadata = AtomicReference(Metadata(videoFormat, position))

    public val videoFormat: RealtimeVideoFormat?
        get() = metadata.get().videoFormat

    public val position: CameraPosition?
        get() = metadata.get().position

    /**
     * 更新轨道对外提供的视频规格快照。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    internal fun updateVideoFormat(videoFormat: RealtimeVideoFormat) {
        metadata.updateAndGet { it.copy(videoFormat = videoFormat) }
    }

    /**
     * 更新轨道对外提供的摄像头方向。
     *
     * @param position 需要使用的前置或后置摄像头。
     */
    internal fun updatePosition(position: CameraPosition) {
        metadata.updateAndGet { it.copy(position = position) }
    }

    /**
     * 保存轨道当前的视频规格和摄像头方向快照。
     *
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @param position 需要使用的前置或后置摄像头。
     */
    private data class Metadata(
        val videoFormat: RealtimeVideoFormat?,
        val position: CameraPosition?,
    )
}

/**
 * 内部相机预览就绪通知；公共就绪语义通过 RealtimeConnectionState.READY 表达。
 */
internal fun interface RealtimeCameraPreviewReadyListener {
    /**
     * 通知当前相机预览已可显示。
     */
    fun onCameraPreviewReady()
}
