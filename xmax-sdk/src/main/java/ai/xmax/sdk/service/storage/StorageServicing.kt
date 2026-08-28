package ai.xmax.sdk.service.storage

import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StoredFile
import java.io.File

/** 定义图片和视频的上传、下载及图片安全检查能力。 */
internal interface StorageServicing {
    suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun uploadImageFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun uploadVideoFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile

    suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile
}
