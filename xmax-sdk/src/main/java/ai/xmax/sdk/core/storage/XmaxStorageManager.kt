package ai.xmax.sdk

import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StoredFile
import ai.xmax.sdk.service.storage.StorageServicing
import java.io.File

/**
 * 文件存储公共接口的适配层，将底层结果和进度转换为 SDK 对外模型。
 * 上传校验、临时凭据和安全检查由 StorageService 负责，实际传输及取消由底层存储实现负责。
 * 路径重载统一委托给 File 重载；此层不拦截异常或切换进度回调线程。
 */
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

/** 只暴露上传结果，隔离底层存储实现类型。 */
private fun StoredFile.toPublicModel(): XmaxUploadedFile =
    XmaxUploadedFile(url = url, objectKey = objectKey, etag = etag)

/** 保留下载使用的目标路径及实际写入字节数。 */
private fun DownloadedFile.toPublicModel(): XmaxDownloadedFile =
    XmaxDownloadedFile(filePath = file.path, byteCount = byteCount)

/** 仅适配进度模型，不改变回调的执行线程；未注册监听器时不创建适配器。 */
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
