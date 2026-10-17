//
//  StorageModel.kt
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
 * 对象存储传输使用的临时鉴权凭据。
 *
 * @param accessKeyId 临时存储访问密钥标识。
 * @param secretAccessKey 临时存储访问密钥，不得记录到日志。
 * @param sessionToken 临时存储凭据的会话令牌。
 */
internal data class StorageCredential(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String,
)

/**
 * 对象存储的访问位置及临时凭据。
 *
 * @param bucket 对象存储桶名称。
 * @param region 对象存储所在地域。
 * @param endpoint 对象存储访问端点。
 * @param credential 用于本次传输的临时鉴权凭据。
 */
internal data class StorageConfiguration(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val credential: StorageCredential,
)

/**
 * 本次上传允许的对象键前缀及存储访问配置。
 *
 * @param prefix 存储对象键名或记录键名的前缀。
 * @param configuration 对象存储端点、地域、存储桶及临时凭据。
 */
internal data class TemporaryStorageConfiguration(
    val prefix: String,
    val configuration: StorageConfiguration,
)

/**
 * 可上传的文件或字节数据源。
 */
internal sealed interface StorageSource {
    /**
     * 数据源的内容大小。
     */
    val byteCount: Long

    /**
     * 以本地文件提供待上传内容的数据源。
     *
     * @param file 可读取的本地文件。
     */
    data class LocalFile(val file: File) : StorageSource {
        /**
         * 数据源的内容大小。
         */
        override val byteCount: Long = file.length()
    }

    /**
     * 以字节数组提供待上传内容的数据源。
     *
     * @param data 待读取、传输或持有的二进制数据。
     */
    data class Bytes(val data: ByteArray) : StorageSource {
        /**
         * 数据源的内容大小。
         */
        override val byteCount: Long = data.size.toLong()
    }
}

/**
 * 上传完成后可访问的存储对象信息。
 *
 * @param url 资源或请求的网络地址。
 * @param objectKey 对象在存储桶内的完整键名。
 * @param etag 存储服务返回的对象校验标识；null 表示服务未提供。
 */
internal data class StoredFile(
    val url: String,
    val objectKey: String,
    val etag: String? = null,
)

/**
 * 下载成功后的本地文件及实际数据大小。
 *
 * @param file 可读取的本地文件。
 * @param byteCount 数据大小，单位为字节。
 */
internal data class DownloadedFile(
    val file: File,
    val byteCount: Long,
)
