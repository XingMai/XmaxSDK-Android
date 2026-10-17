//
//  StorageServicing.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.storage

import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StoredFile
import java.io.File

/**
 * 定义图片和视频的上传、下载及图片安全检查能力。
 */
internal interface StorageServicing {
    /**
     * 上传图片字节并返回可访问的存储结果。
     *
     * @param data 编码后的图片文件字节。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 上传本地图片文件并返回可访问的存储结果。
     *
     * @param file 可读取的本地文件。
     * @param contentType 媒体的 MIME 类型；null 表示根据文件扩展名推断。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun uploadImageFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 上传图片字节并在内容安全检查通过后返回结果。
     *
     * @param data 编码后的图片文件字节。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 上传本地图片并在内容安全检查通过后返回结果。
     *
     * @param file 可读取的本地文件。
     * @param contentType 媒体的 MIME 类型；null 表示根据文件扩展名推断。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 上传视频字节并返回可访问的存储结果。
     *
     * @param data 编码后的视频文件字节。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 上传本地视频文件并返回可访问的存储结果。
     *
     * @param file 可读取的本地文件。
     * @param contentType 媒体的 MIME 类型；null 表示根据文件扩展名推断。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun uploadVideoFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 下载远端图片并返回本地文件信息，失败时保留已有目标文件。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile

    /**
     * 下载远端视频并返回本地文件信息，失败时保留已有目标文件。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile
}
