package ai.xmax.sdk.internal.storage

import ai.xmax.sdk.XmaxStorageProgressListener
import java.io.File

internal interface StorageProviding {
    suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: XmaxStorageProgressListener?,
    ): StoredFile

    suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener?,
    ): DownloadedFile
}
