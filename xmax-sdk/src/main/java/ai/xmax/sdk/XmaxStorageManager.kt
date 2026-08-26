package ai.xmax.sdk

import ai.xmax.sdk.internal.storage.StorageService
import java.io.File

/** 文件存储公共入口。 */
public class XmaxStorageManager internal constructor(
    private val storageService: StorageService,
) {
    /** 上传图片二进制数据。 */
    public suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = storageService.uploadImage(data, fileName, contentType, progress).toPublicModel()

    /** 上传本地图片文件。 */
    public suspend fun uploadImageFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = storageService.uploadImageFile(file, contentType, progress).toPublicModel()

    /** 上传本地图片文件路径。 */
    public suspend fun uploadImageFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = uploadImageFile(File(filePath), contentType, progress)

    /** 上传图片二进制数据并执行内容安全检查。 */
    public suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = storageService
        .uploadImageWithSafetyCheck(data, fileName, contentType, progress)
        .toPublicModel()

    /** 上传本地图片文件并执行内容安全检查。 */
    public suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = storageService
        .uploadImageFileWithSafetyCheck(file, contentType, progress)
        .toPublicModel()

    /** 上传本地图片文件路径并执行内容安全检查。 */
    public suspend fun uploadImageFileWithSafetyCheck(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = uploadImageFileWithSafetyCheck(File(filePath), contentType, progress)

    /** 上传视频二进制数据。 */
    public suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = storageService.uploadVideo(data, fileName, contentType, progress).toPublicModel()

    /** 上传本地视频文件。 */
    public suspend fun uploadVideoFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = storageService.uploadVideoFile(file, contentType, progress).toPublicModel()

    /** 上传本地视频文件路径。 */
    public suspend fun uploadVideoFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile = uploadVideoFile(File(filePath), contentType, progress)

    /** 下载远端图片到本地文件。 */
    public suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile = storageService.downloadImage(remoteUrl, destination, progress).toPublicModel()

    /** 下载远端图片到本地文件路径。 */
    public suspend fun downloadImage(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile = downloadImage(remoteUrl, File(destinationPath), progress)

    /** 下载远端视频到本地文件。 */
    public suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile = storageService.downloadVideo(remoteUrl, destination, progress).toPublicModel()

    /** 下载远端视频到本地文件路径。 */
    public suspend fun downloadVideo(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile = downloadVideo(remoteUrl, File(destinationPath), progress)
}

private fun ai.xmax.sdk.internal.storage.StoredFile.toPublicModel(): XmaxUploadedFile =
    XmaxUploadedFile(url = url, objectKey = objectKey, etag = etag)

private fun ai.xmax.sdk.internal.storage.DownloadedFile.toPublicModel(): XmaxDownloadedFile =
    XmaxDownloadedFile(filePath = file.path, byteCount = byteCount)
