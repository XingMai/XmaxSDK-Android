package ai.xmax.sdk

import java.io.File

/** 定义 SDK 对接入方提供的文件上传和下载能力。 */
public interface XmaxStorageManaging {
    public suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadImageFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadImageFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadImageFileWithSafetyCheck(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadVideoFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun uploadVideoFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    public suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    public suspend fun downloadImage(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    public suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    public suspend fun downloadVideo(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile
}
