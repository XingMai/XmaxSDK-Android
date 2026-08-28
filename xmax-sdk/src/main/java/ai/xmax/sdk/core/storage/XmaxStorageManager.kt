package ai.xmax.sdk

import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StoredFile
import ai.xmax.sdk.service.storage.StorageServicing
import java.io.File

/** 文件存储公共入口。 */
internal class XmaxStorageManager(
    private val storageService: StorageServicing,
) : XmaxStorageManaging {
    /** 上传图片二进制数据。 */
    override suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImage(data, fileName, contentType, progress.toStorageProgressListener())
        .toPublicModel()

    /** 上传本地图片文件。 */
    override suspend fun uploadImageFile(
        file: File,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImageFile(file, contentType, progress.toStorageProgressListener())
        .toPublicModel()

    /** 上传本地图片文件路径。 */
    override suspend fun uploadImageFile(
        filePath: String,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = uploadImageFile(File(filePath), contentType, progress)

    /** 上传图片二进制数据并执行内容安全检查。 */
    override suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImageWithSafetyCheck(
            data,
            fileName,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /** 上传本地图片文件并执行内容安全检查。 */
    override suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadImageFileWithSafetyCheck(
            file,
            contentType,
            progress.toStorageProgressListener(),
        )
        .toPublicModel()

    /** 上传本地图片文件路径并执行内容安全检查。 */
    override suspend fun uploadImageFileWithSafetyCheck(
        filePath: String,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = uploadImageFileWithSafetyCheck(File(filePath), contentType, progress)

    /** 上传视频二进制数据。 */
    override suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadVideo(data, fileName, contentType, progress.toStorageProgressListener())
        .toPublicModel()

    /** 上传本地视频文件。 */
    override suspend fun uploadVideoFile(
        file: File,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = storageService
        .uploadVideoFile(file, contentType, progress.toStorageProgressListener())
        .toPublicModel()

    /** 上传本地视频文件路径。 */
    override suspend fun uploadVideoFile(
        filePath: String,
        contentType: String?,
        progress: XmaxStorageProgressListener?,
    ): XmaxUploadedFile = uploadVideoFile(File(filePath), contentType, progress)

    /** 下载远端图片到本地文件。 */
    override suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = storageService
        .downloadImage(remoteUrl, destination, progress.toStorageProgressListener())
        .toPublicModel()

    /** 下载远端图片到本地文件路径。 */
    override suspend fun downloadImage(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = downloadImage(remoteUrl, File(destinationPath), progress)

    /** 下载远端视频到本地文件。 */
    override suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = storageService
        .downloadVideo(remoteUrl, destination, progress.toStorageProgressListener())
        .toPublicModel()

    /** 下载远端视频到本地文件路径。 */
    override suspend fun downloadVideo(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener?,
    ): XmaxDownloadedFile = downloadVideo(remoteUrl, File(destinationPath), progress)
}

private fun StoredFile.toPublicModel(): XmaxUploadedFile =
    XmaxUploadedFile(url = url, objectKey = objectKey, etag = etag)

private fun DownloadedFile.toPublicModel(): XmaxDownloadedFile =
    XmaxDownloadedFile(filePath = file.path, byteCount = byteCount)

private fun XmaxStorageProgressListener?.toStorageProgressListener(): StorageProgressListener? =
    this?.let { listener ->
        StorageProgressListener { completedBytes, totalBytes ->
            listener.onProgress(
                XmaxStorageProgress(
                    completedBytes = completedBytes,
                    totalBytes = totalBytes,
                ),
            )
        }
    }
