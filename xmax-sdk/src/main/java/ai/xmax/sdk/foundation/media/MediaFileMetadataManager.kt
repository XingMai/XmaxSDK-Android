//
//  MediaFileMetadataManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 使用 Android 媒体框架读取本地媒体文件元数据。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 */
internal class MediaFileMetadataManager(
    context: Context,
) : MediaFileMetadataManaging {
    /**
     * Android 媒体或文件访问环境。
     */
    private val applicationContext = context.applicationContext

    /**
     * 读取媒体的显示尺寸、旋转方向、时长和音轨信息。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 媒体的尺寸、旋转、时长及音轨信息。
     */
    override suspend fun readMetadata(uri: Uri): MediaFileMetadata =
        withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(applicationContext, uri)
                val width = retriever.requiredPositiveInt(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                )
                val height = retriever.requiredPositiveInt(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                )
                val durationMs = retriever.requiredPositiveLong(
                    MediaMetadataRetriever.METADATA_KEY_DURATION,
                )
                val rotation = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                )?.toIntOrNull().orEmptyRotation()
                val durationUs = Math.multiplyExact(durationMs, MICROSECONDS_PER_MILLISECOND)
                MediaFileMetadata(
                    width = width,
                    height = height,
                    rotation = rotation,
                    durationUs = durationUs,
                    hasAudio = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
                    ) == "yes",
                )
            } catch (error: XmaxError) {
                throw error
            } catch (error: Throwable) {
                throw mediaError("Failed to read media file metadata", error)
            } finally {
                retriever.release()
            }
        }

    /**
     * 读取必须存在且大于零的整数元数据，无效时抛出媒体错误。
     *
     * @param key Android 媒体元数据字段标识。
     * @return 经过校验的正整数元数据值。
     */
    private fun MediaMetadataRetriever.requiredPositiveInt(key: Int): Int =
        extractMetadata(key)?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw mediaError("The media file has invalid video dimensions")

    /**
     * 读取必须存在且大于零的长整数元数据，无效时抛出媒体错误。
     *
     * @param key Android 媒体元数据字段标识。
     * @return 经过校验的正长整数元数据值。
     */
    private fun MediaMetadataRetriever.requiredPositiveLong(key: Int): Long =
        extractMetadata(key)?.toLongOrNull()?.takeIf { it > 0L }
            ?: throw mediaError("The media file has an invalid duration")

    /**
     * 将旋转元数据转换为视频方向；缺失时按未旋转处理。
     *
     * @return 匹配的旋转方向。
     */
    private fun Int?.orEmptyRotation(): VideoRotation = when (this) {
        90 -> VideoRotation.ROTATION_90
        180 -> VideoRotation.ROTATION_180
        270 -> VideoRotation.ROTATION_270
        else -> VideoRotation.ROTATION_0
    }

    /**
     * 创建包含媒体处理原因的 SDK 错误。
     *
     * @param message 待发送或记录的消息文本。
     * @param cause 触发当前错误的底层异常。
     * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
     */
    private fun mediaError(message: String, cause: Throwable? = null): XmaxError =
        XmaxError(XmaxErrorCode.MEDIA_ERROR, message, cause = cause)

    /**
     * 提供 MediaFileMetadataManager 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
    }
}
