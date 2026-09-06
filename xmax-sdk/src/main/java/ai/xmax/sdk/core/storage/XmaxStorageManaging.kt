package ai.xmax.sdk

import java.io.File

/**
 * 图片和视频的文件存储入口，挂起调用完成后返回传输结果，失败通过异常返回。
 * 取消调用会传递给底层传输；进度回调来自传输线程，更新 UI 时由接入方切换到主线程。
 * 下载先写临时文件，成功后替换目标文件；同一目标路径不允许并发下载写入。
 */
public interface XmaxStorageManaging {
    /** 上传图片字节；fileName 用于生成对象名称，contentType 必须为图片 MIME 类型。 */
    public suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** 上传可读取的本地图片；contentType 为 null 时按文件扩展名推断。 */
    public suspend fun uploadImageFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** [uploadImageFile] 的本地路径重载，参数含义与 File 版本一致。 */
    public suspend fun uploadImageFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** 先上传图片字节，再执行服务端内容安全检查；检查通过后返回结果。 */
    public suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** 上传本地图片并执行内容安全检查；未通过时抛出 UNSAFE_IMAGE。 */
    public suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** [uploadImageFileWithSafetyCheck] 的本地路径重载。 */
    public suspend fun uploadImageFileWithSafetyCheck(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** 上传视频字节；fileName 用于生成对象名称，contentType 必须为视频 MIME 类型。 */
    public suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** 上传可读取的本地视频；contentType 为 null 时按文件扩展名推断。 */
    public suspend fun uploadVideoFile(
        file: File,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** [uploadVideoFile] 的本地路径重载。 */
    public suspend fun uploadVideoFile(
        filePath: String,
        contentType: String? = null,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxUploadedFile

    /** 从 HTTP(S) 地址下载图片至指定文件，返回目标路径及实际字节数。 */
    public suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    /** [downloadImage] 的目标路径重载。 */
    public suspend fun downloadImage(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    /** 从 HTTP(S) 地址下载视频至指定文件，返回目标路径及实际字节数。 */
    public suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile

    /** [downloadVideo] 的目标路径重载。 */
    public suspend fun downloadVideo(
        remoteUrl: String,
        destinationPath: String,
        progress: XmaxStorageProgressListener? = null,
    ): XmaxDownloadedFile
}
