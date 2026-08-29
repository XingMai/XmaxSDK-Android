package ai.xmax.sdk.foundation.media

import ai.xmax.sdk.VideoRotation
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 使用 Android 媒体框架读取本地媒体文件元数据。 */
internal class MediaFileMetadataManager(
    context: Context,
) : MediaFileMetadataManaging {
    private val applicationContext = context.applicationContext

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

    private fun MediaMetadataRetriever.requiredPositiveInt(key: Int): Int =
        extractMetadata(key)?.toIntOrNull()?.takeIf { it > 0 }
            ?: throw mediaError("The media file has invalid video dimensions")

    private fun MediaMetadataRetriever.requiredPositiveLong(key: Int): Long =
        extractMetadata(key)?.toLongOrNull()?.takeIf { it > 0L }
            ?: throw mediaError("The media file has an invalid duration")

    private fun Int?.orEmptyRotation(): VideoRotation = when (this) {
        90 -> VideoRotation.ROTATION_90
        180 -> VideoRotation.ROTATION_180
        270 -> VideoRotation.ROTATION_270
        else -> VideoRotation.ROTATION_0
    }

    private fun mediaError(message: String, cause: Throwable? = null): XmaxError =
        XmaxError(XmaxErrorCode.MEDIA_ERROR, message, cause = cause)

    private companion object {
        const val MICROSECONDS_PER_MILLISECOND = 1_000L
    }
}
