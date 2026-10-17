//
//  StorageService.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.storage

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageConfiguration
import ai.xmax.sdk.foundation.storage.StorageCredential
import ai.xmax.sdk.foundation.storage.StorageManaging
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StorageSource
import ai.xmax.sdk.foundation.storage.StoredFile
import ai.xmax.sdk.foundation.storage.TemporaryStorageConfiguration
import ai.xmax.sdk.service.network.ApiServicing
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import java.io.File
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 提供媒体上传下载、格式校验及可选图片内容安全检查。
 *
 * @param apiService 发起服务端 API 请求的客户端。
 * @param storageManager 执行对象存储传输的组件。
 */
internal class StorageService(
    private val apiService: ApiServicing,
    private val storageManager: StorageManaging,
) : StorageServicing {
    /**
     * 上传图片字节并返回可访问的存储结果。
     *
     * @param data 编码后的图片文件字节。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    override suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile = upload(StorageSource.Bytes(data), fileName, contentType, MediaType.IMAGE, false, progress)

    /**
     * 上传本地图片文件并返回可访问的存储结果。
     *
     * @param file 可读取的本地文件。
     * @param contentType 媒体的 MIME 类型；null 表示根据文件扩展名推断。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    override suspend fun uploadImageFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile = upload(
        StorageSource.LocalFile(file),
        file.name,
        contentType ?: inferImageContentType(file.name),
        MediaType.IMAGE,
        false,
        progress,
    )

    /**
     * 上传图片字节并在内容安全检查通过后返回结果。
     *
     * @param data 编码后的图片文件字节。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    override suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile = upload(StorageSource.Bytes(data), fileName, contentType, MediaType.IMAGE, true, progress)

    /**
     * 上传本地图片并在内容安全检查通过后返回结果。
     *
     * @param file 可读取的本地文件。
     * @param contentType 媒体的 MIME 类型；null 表示根据文件扩展名推断。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    override suspend fun uploadImageFileWithSafetyCheck(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile = upload(
        StorageSource.LocalFile(file),
        file.name,
        contentType ?: inferImageContentType(file.name),
        MediaType.IMAGE,
        true,
        progress,
    )

    /**
     * 上传视频字节并返回可访问的存储结果。
     *
     * @param data 编码后的视频文件字节。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    override suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile = upload(StorageSource.Bytes(data), fileName, contentType, MediaType.VIDEO, false, progress)

    /**
     * 上传本地视频文件并返回可访问的存储结果。
     *
     * @param file 可读取的本地文件。
     * @param contentType 媒体的 MIME 类型；null 表示根据文件扩展名推断。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    override suspend fun uploadVideoFile(
        file: File,
        contentType: String?,
        progress: StorageProgressListener?,
    ): StoredFile = upload(
        StorageSource.LocalFile(file),
        file.name,
        contentType ?: inferVideoContentType(file.name),
        MediaType.VIDEO,
        false,
        progress,
    )

    /**
     * 下载远端图片并返回本地文件信息，失败时保留已有目标文件。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    override suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = download(remoteUrl, destination, progress)

    /**
     * 下载远端视频并返回本地文件信息，失败时保留已有目标文件。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    override suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = download(remoteUrl, destination, progress)

    /**
     * 校验媒体并上传，按需执行图片内容安全检查后返回结果。
     *
     * @param source 待上传的本地文件或字节数据。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param mediaType 允许上传的媒体类型。
     * @param checksSafety 是否在上传后执行图片内容安全检查。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 上传后的访问地址、对象键及校验标识。
     */
    private suspend fun upload(
        source: StorageSource,
        fileName: String,
        contentType: String,
        mediaType: MediaType,
        checksSafety: Boolean,
        progress: StorageProgressListener?,
    ): StoredFile {
        val startedAt = System.nanoTime()
        return try {
            val safeName = validateUpload(source, fileName, contentType, mediaType)
            val resolution = readResolution(source, mediaType)
            XmaxLogger.storage.info(
                message = {
                    "开始上传 (Upload Started)\n" +
                        "├─ ${XmaxLogger.localized("类型：", "Type: ")}${mediaType.value}\n" +
                        "├─ ${XmaxLogger.localized("分辨率：", "Resolution: ")}$resolution\n" +
                        "├─ ${XmaxLogger.localized("大小：", "Size: ")}${formatByteCount(source.byteCount)}\n" +
                        "└─ ${XmaxLogger.localized("安全检测：", "Safety Check: ")}$checksSafety"
                },
            )

            val temporary = fetchStorageConfiguration()
            val objectKey = "${temporary.prefix}${System.currentTimeMillis()}_" +
                "${UUID.randomUUID().toString().lowercase()}_$safeName"
            val stored = storageManager.upload(
                source = source,
                objectKey = objectKey,
                contentType = contentType.trim(),
                configuration = temporary.configuration,
                progress = progress,
            )
            val result = if (checksSafety) {
                stored.copy(url = checkImage(stored.url))
            } else {
                stored
            }
            XmaxLogger.storage.info(
                message = {
                    "上传完成 (Upload Completed)\n" +
                        "├─ ${XmaxLogger.localized("地址：", "URL: ")}${result.url}\n" +
                        "└─ ${XmaxLogger.localized("耗时：", "Duration: ")}${formatDuration(startedAt)}"
                },
            )
            result
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            val resolvedError = if (error is XmaxError) {
                error
            } else {
                XmaxError(
                    XmaxErrorCode.UPLOAD_ERROR,
                    error.message ?: "Storage upload failed",
                    cause = error,
                )
            }
            XmaxLogger.storage.error(
                message = {
                    "上传失败 (Upload Failed)\n" +
                        "├─ ${XmaxLogger.localized("错误码：", "Error Code: ")}${resolvedError.code}\n" +
                        "├─ ${XmaxLogger.localized("原因：", "Reason: ")}${resolvedError.message}\n" +
                        "└─ ${XmaxLogger.localized("耗时：", "Duration: ")}${formatDuration(startedAt)}"
                },
            )
            throw resolvedError
        }
    }

    /**
     * 下载指定类型的媒体并返回本地文件结果。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    private suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile {
        if (!isHttpUrl(remoteUrl)) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Invalid download URL")
        }
        if (destination.path.isBlank()) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Download destination path cannot be empty")
        }
        return storageManager.download(remoteUrl, destination, progress)
    }

    /**
     * 取得上传所需的临时存储凭据和对象前缀。
     *
     * @return 服务端签发的临时存储凭据、访问配置及对象键前缀。
     */
    private suspend fun fetchStorageConfiguration(): TemporaryStorageConfiguration {
        val value = apiService.get("/cos/sts")
        val credentials = value.optJSONObject("credentials")
            ?: throw invalidCredentialPayload()
        val bucket = value.requiredNonEmptyString("bucket")
        val region = value.requiredNonEmptyString("region")
        val endpoint = value.requiredString("endpoint")
        val prefix = value.requiredString("prefix")
        return TemporaryStorageConfiguration(
            prefix = prefix,
            configuration = StorageConfiguration(
                bucket = bucket,
                region = region,
                endpoint = endpoint,
                credential = StorageCredential(
                    accessKeyId = credentials.requiredNonEmptyString("accessKeyId"),
                    secretAccessKey = credentials.requiredNonEmptyString("secretAccessKey"),
                    sessionToken = credentials.requiredNonEmptyString("sessionToken"),
                ),
            ),
        )
    }

    /**
     * 请求图片内容安全检查，未通过时抛出不安全图片错误。
     *
     * @param url 资源或请求的网络地址。
     * @return 安全检查通过后的图片地址。
     */
    private suspend fun checkImage(url: String): String {
        val value = apiService.post("/cos/image/check", JSONObject().put("url", url))
        val safe = value.opt("safe")
        if (safe !is Boolean) {
            throw XmaxError(XmaxErrorCode.API_ERROR, "Invalid image safety check payload")
        }
        if (!safe) {
            throw XmaxError(XmaxErrorCode.UNSAFE_IMAGE, "The image did not pass the safety check")
        }
        return value.optString("url").takeIf(::isHttpUrl)
            ?: throw XmaxError(XmaxErrorCode.API_ERROR, "Invalid image safety check payload")
    }

    /**
     * 校验上传数据、文件名及媒体类型，并生成有效对象名称。
     *
     * @param source 待上传的本地文件或字节数据。
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param contentType 媒体的 MIME 类型。
     * @param mediaType 允许上传的媒体类型。
     * @return 通过校验的文件名。
     */
    private fun validateUpload(
        source: StorageSource,
        fileName: String,
        contentType: String,
        mediaType: MediaType,
    ): String {
        when (source) {
            is StorageSource.Bytes -> if (source.data.isEmpty()) {
                throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "${mediaType.label} data cannot be empty")
            }
            is StorageSource.LocalFile -> if (!source.file.isFile) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "${mediaType.label} file path must reference an existing file",
                )
            }
        }
        if (!contentType.trim().lowercase().startsWith("${mediaType.value}/")) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "${mediaType.label} content type must begin with ${mediaType.value}/",
            )
        }
        val safeName = sanitizeFileName(fileName)
        if (safeName.isEmpty()) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "${mediaType.label} file name cannot be empty")
        }
        return safeName
    }

    /**
     * 根据文件扩展名推断图片 MIME 类型。
     *
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @return 图片扩展名对应的 MIME 类型。
     */
    private fun inferImageContentType(fileName: String): String = inferContentType(
        fileName,
        mapOf(
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg",
            "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
            "heic" to "image/heic", "heif" to "image/heif", "bmp" to "image/bmp",
            "svg" to "image/svg+xml", "tif" to "image/tiff", "tiff" to "image/tiff",
            "avif" to "image/avif",
        ),
        "image",
    )

    /**
     * 根据文件扩展名推断视频 MIME 类型。
     *
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @return 视频扩展名对应的 MIME 类型。
     */
    private fun inferVideoContentType(fileName: String): String = inferContentType(
        fileName,
        mapOf(
            "mp4" to "video/mp4", "mov" to "video/quicktime", "m4v" to "video/x-m4v",
            "webm" to "video/webm", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska",
            "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "ts" to "video/mp2t",
        ),
        "video",
    )

    /**
     * 根据允许的扩展名映射推断媒体 MIME 类型。
     *
     * @param fileName 用于构造存储对象名称的文件名，包含扩展名。
     * @param types 文件扩展名到 MIME 类型的允许映射。
     * @param mediaType 允许上传的媒体类型。
     * @return 允许扩展名对应的 MIME 类型。
     */
    private fun inferContentType(fileName: String, types: Map<String, String>, mediaType: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return types[extension] ?: throw XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Unable to infer $mediaType content type from file extension",
        )
    }

    /**
     * 将文件名中的非法字符替换为下划线，并去掉首尾无效分隔符。
     *
     * @param value 需要规范化、校验或显示的文本。
     * @return 不包含目录路径的存储文件名。
     */
    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .replace(Regex("_+"), "_")
        .trim { it == '.' || it == '_' || it == '-' }

    /**
     * 判断地址是否使用 HTTP 或 HTTPS 协议。
     *
     * @param value 需要规范化、校验或显示的文本。
     * @return 使用 HTTP 或 HTTPS 协议时为 true。
     */
    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    /**
     * 读取必须存在的字符串字段，缺失时抛出响应数据错误。
     *
     * @param key JSON 对象中的字段名称。
     * @return 必填字段的字符串值。
     */
    private fun JSONObject.requiredString(key: String): String {
        val value = opt(key)
        if (value !is String) throw invalidCredentialPayload()
        return value.trim()
    }

    /**
     * 读取必须存在且非空的字符串字段。
     *
     * @param key JSON 对象中的字段名称。
     * @return 必填字段的非空字符串值。
     */
    private fun JSONObject.requiredNonEmptyString(key: String): String =
        requiredString(key).takeIf { it.isNotEmpty() } ?: throw invalidCredentialPayload()

    /**
     * 创建用于报告临时存储凭据不完整的错误。
     *
     * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
     */
    private fun invalidCredentialPayload(): XmaxError =
        XmaxError(XmaxErrorCode.API_ERROR, "Invalid storage credential payload")

    /**
     * 读取上传媒体的分辨率描述。
     *
     * @param source 待上传的本地文件或字节数据。
     * @param mediaType 允许上传的媒体类型。
     * @return 媒体尺寸的日志描述。
     */
    private suspend fun readResolution(source: StorageSource, mediaType: MediaType): String =
        withContext(Dispatchers.IO) {
            runCatching {
                when (mediaType) {
                    MediaType.IMAGE -> readImageResolution(source)
                    MediaType.VIDEO -> readVideoResolution(source)
                }
            }.getOrDefault("--")
        }

    /**
     * 读取上传图片的分辨率描述。
     *
     * @param source 待上传的本地文件或字节数据。
     * @return 图片尺寸的日志描述。
     */
    private fun readImageResolution(source: StorageSource): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        when (source) {
            is StorageSource.Bytes -> BitmapFactory.decodeByteArray(
                source.data,
                0,
                source.data.size,
                options,
            )
            is StorageSource.LocalFile -> BitmapFactory.decodeFile(source.file.path, options)
        }
        return formatResolution(options.outWidth, options.outHeight)
    }

    /**
     * 读取上传视频的分辨率描述。
     *
     * @param source 待上传的本地文件或字节数据。
     * @return 视频尺寸的日志描述。
     */
    private fun readVideoResolution(source: StorageSource): String {
        val file = (source as? StorageSource.LocalFile)?.file ?: return "--"
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: return "--"
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: return "--"
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                formatResolution(height, width)
            } else {
                formatResolution(width, height)
            }
        } finally {
            retriever.release()
        }
    }

    /**
     * 将宽高格式化为分辨率文本。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @return 格式化后的宽高文本。
     */
    private fun formatResolution(width: Int, height: Int): String =
        if (width > 0 && height > 0) "$width × $height" else "--"

    /**
     * 将字节数格式化为易读的数据量文本。
     *
     * @param byteCount 数据大小，单位为字节。
     * @return 带数据量单位的字节数描述。
     */
    private fun formatByteCount(byteCount: Long): String {
        val count = byteCount.toDouble()
        return when {
            byteCount < 1_024L -> "$byteCount B"
            byteCount < 1_024L * 1_024L -> String.format(Locale.US, "%.1f KB", count / 1_024.0)
            byteCount < 1_024L * 1_024L * 1_024L ->
                String.format(Locale.US, "%.2f MB", count / (1_024.0 * 1_024.0))
            else -> String.format(
                Locale.US,
                "%.2f GB",
                count / (1_024.0 * 1_024.0 * 1_024.0),
            )
        }
    }

    /**
     * 将从指定时刻开始的耗时格式化为日志文本。
     *
     * @param startedAt 操作开始时的单调时钟纳秒数。
     * @return 带时间单位的耗时描述。
     */
    private fun formatDuration(startedAt: Long): String {
        val milliseconds = (System.nanoTime() - startedAt) / 1_000_000L
        return if (milliseconds < 1_000L) {
            "$milliseconds ms"
        } else {
            String.format(Locale.US, "%.2f s", milliseconds / 1_000.0)
        }
    }

    /**
     * 限定存储服务允许处理的图片或视频类型。
     *
     * @param value 需要规范化、校验或显示的文本。
     * @param label 用于日志或错误描述的可读名称。
     */
    private enum class MediaType(val value: String, val label: String) {
        /**
         * 静态图片输入。
         */
        IMAGE("image", "Image"),

        /**
         * 本地视频输入。
         */
        VIDEO("video", "Video"),
    }
}
