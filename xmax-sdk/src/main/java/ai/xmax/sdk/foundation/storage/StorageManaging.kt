package ai.xmax.sdk.foundation.storage

import java.io.File

/** 定义基础文件上传和下载能力。 */
internal interface StorageManaging {
    suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile
}
