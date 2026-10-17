//
//  StorageManaging.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.storage

import java.io.File

/**
 * 定义基础文件上传和下载能力。
 */
internal interface StorageManaging {
    /**
     * 上传文件或字节至指定存储对象，并返回可访问的对象信息。
     *
     * @param source 待上传的本地文件或字节数据。
     * @param objectKey 对象在存储桶内的完整键名。
     * @param contentType 媒体的 MIME 类型。
     * @param configuration 对象存储端点、地域、存储桶及临时凭据。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: StorageProgressListener?,
    ): StoredFile

    /**
     * 下载远端文件；成功后替换目标文件，失败时保留已有目标内容。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile
}
