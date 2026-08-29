package ai.xmax.sdk.foundation.media

import android.net.Uri

/** 定义读取本地媒体文件元数据的能力。 */
internal interface MediaFileMetadataManaging {
    suspend fun readMetadata(uri: Uri): MediaFileMetadata
}
