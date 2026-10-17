//
//  StorageManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.storage

import ai.xmax.sdk.service.network.withCancellableConnection
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Context
import android.net.Uri
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import com.tencent.cos.xml.CosXmlServiceConfig
import com.tencent.cos.xml.CosXmlSimpleService
import com.tencent.cos.xml.exception.CosXmlClientException
import com.tencent.cos.xml.exception.CosXmlServiceException
import com.tencent.cos.xml.listener.CosXmlResultListener
import com.tencent.cos.xml.model.CosXmlRequest
import com.tencent.cos.xml.model.CosXmlResult
import com.tencent.cos.xml.model.`object`.PutObjectRequest
import com.tencent.cos.xml.transfer.COSXMLUploadTask
import com.tencent.cos.xml.transfer.TransferConfig
import com.tencent.cos.xml.transfer.TransferManager
import com.tencent.qcloud.core.auth.SessionQCloudCredentials
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 封装腾讯云 COS 上传和 HTTP 文件下载能力。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 */
internal class StorageManager(context: Context) : StorageManaging {
    /**
     * Android 媒体或文件访问环境。
     */
    private val applicationContext = context.applicationContext

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
    override suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: StorageProgressListener?,
    ): StoredFile = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine
        val completed = AtomicBoolean(false)
        try {
            val serviceConfig = makeServiceConfiguration(configuration)
            val service = CosXmlSimpleService(applicationContext, serviceConfig)
            /**
             * 使用临时凭据允许的 PutObject 上传方式，不要求额外的分片上传权限。
             */
            val transferConfig = TransferConfig.Builder()
                .setForceSimpleUpload(source is StorageSource.LocalFile)
                .build()
            val manager = TransferManager(service, transferConfig)
            val request = when (source) {
                is StorageSource.LocalFile -> PutObjectRequest(
                    configuration.bucket,
                    objectKey,
                    source.file.absolutePath,
                )
                is StorageSource.Bytes -> PutObjectRequest(configuration.bucket, objectKey, source.data)
            }
            request.setRequestHeaders("Content-Type", contentType, false)
            val nowSeconds = System.currentTimeMillis() / 1000L
            request.setCredential(
                SessionQCloudCredentials(
                    configuration.credential.accessKeyId,
                    configuration.credential.secretAccessKey,
                    configuration.credential.sessionToken,
                    nowSeconds - 60L,
                    nowSeconds + 25L * 60L,
                ),
            )
            val task = manager.upload(request, null)
            task.setCosXmlProgressListener { completed, total ->
                if (continuation.isActive) runCatching { progress?.onProgress(completed, total) }
            }
            task.setCosXmlResultListener(
                object : CosXmlResultListener {
                    /**
                     * 完成存储传输的成功结果回调。
                     *
                     * @param request 本次对象存储请求。
                     * @param result 存储服务返回的传输结果。
                     */
                    override fun onSuccess(request: CosXmlRequest, result: CosXmlResult) {
                        if (!continuation.isActive || !completed.compareAndSet(false, true)) return
                        try {
                            val uploadResult = result as? COSXMLUploadTask.COSXMLUploadTaskResult
                            val url = resolveUrl(result.accessUrl, configuration, objectKey)
                            continuation.resume(
                                StoredFile(
                                    url = url,
                                    objectKey = objectKey,
                                    etag = uploadResult?.eTag?.trim()?.takeIf { it.isNotEmpty() },
                                ),
                            )
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(XmaxError.from(error))
                        }
                    }

                    /**
                     * 将存储传输失败转换为挂起调用的异常结果。
                     *
                     * @param request 本次对象存储请求。
                     * @param clientException 存储客户端发生的错误；null 表示无客户端错误。
                     * @param serviceException 存储服务端返回的错误；null 表示无服务端错误。
                     */
                    override fun onFail(
                        request: CosXmlRequest,
                        clientException: CosXmlClientException?,
                        serviceException: CosXmlServiceException?,
                    ) {
                        if (!continuation.isActive || !completed.compareAndSet(false, true)) return
                        val cause = serviceException ?: clientException
                        continuation.resumeWithException(
                            XmaxError(
                                code = XmaxErrorCode.UPLOAD_ERROR,
                                message = cause?.message ?: "Storage upload failed",
                                httpStatus = serviceException?.statusCode,
                                cause = cause,
                            ),
                        )
                    }
                },
            )
            continuation.invokeOnCancellation { completed.set(true); runCatching { task.cancel(true) } }
        } catch (error: Throwable) {
            if (continuation.isActive && completed.compareAndSet(false, true)) {
                continuation.resumeWithException(
                    if (error is XmaxError) error else XmaxError(
                        XmaxErrorCode.UPLOAD_ERROR,
                        error.message ?: "Storage upload failed",
                        cause = error,
                    ),
                )
            }
        }
    }

    /**
     * 下载远端文件；成功后替换目标文件，失败时保留已有目标内容。
     *
     * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
     * @param destination 下载成功后保存内容的目标文件。
     * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
     * @return 下载后的本地文件及实际字节数。
     */
    override suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = downloadFile(remoteUrl, destination, progress)

    /**
     * 创建指定地域、端点及存储桶对应的 COS 客户端配置。
     *
     * @param configuration 对象存储端点、地域、存储桶及临时凭据。
     * @return 用于创建 COS 客户端的地域和端点配置。
     */
    private fun makeServiceConfiguration(configuration: StorageConfiguration): CosXmlServiceConfig {
        val builder = CosXmlServiceConfig.Builder()
            .setRegion(configuration.region)
            .setVerifySSLEnable(true)
            .setDebuggable(false)
            .setConnectionTimeout(15_000)
            .setSocketTimeout(15_000)
        val endpoint = configuration.endpoint.trim()
        if (endpoint.isNotEmpty()) {
            val normalized = if (endpoint.contains("://")) endpoint else "https://$endpoint"
            val uri = URI(normalized)
            val host = uri.host ?: throw XmaxError(XmaxErrorCode.API_ERROR, "Invalid storage endpoint")
            builder.isHttps(!uri.scheme.equals("http", true))
            if (host.startsWith("cos.")) {
                builder.setHostFormat("\${bucket}.$host")
            } else {
                builder.setHost(Uri.parse(normalized))
            }
        }
        return builder.builder()
    }

    /**
     * 取得服务返回的资源地址，缺失时根据存储配置和对象键生成。
     *
     * @param candidate 服务返回的候选资源地址。
     * @param configuration 对象存储端点、地域、存储桶及临时凭据。
     * @param objectKey 对象在存储桶内的完整键名。
     * @return 解析后的资源或请求地址。
     */
    private fun resolveUrl(candidate: String?, configuration: StorageConfiguration, objectKey: String): String {
        val value = candidate?.trim().orEmpty()
        if (value.startsWith("//")) return "https:$value"
        if (value.startsWith("https://") || value.startsWith("http://")) return value

        var endpoint = configuration.endpoint.trim()
        if (endpoint.isEmpty()) {
            endpoint = "https://${configuration.bucket}.cos.${configuration.region}.myqcloud.com"
        } else if (!endpoint.contains("://")) {
            endpoint = "https://$endpoint"
        }
        endpoint = endpoint.trimEnd('/')
        val uri = URI(endpoint)
        if (uri.host?.startsWith("cos.") == true) {
            endpoint = "${uri.scheme}://${configuration.bucket}.${uri.host}" +
                (if (uri.port >= 0) ":${uri.port}" else "")
        }
        val encodedKey = objectKey.split('/').joinToString("/") { part ->
            URLEncoder.encode(part, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return "$endpoint/$encodedKey"
    }
}

/**
 * 将下载内容落入同目录临时文件，并只在完整成功后原子替换目标文件。
 *
 * @param remoteUrl 待下载文件的 HTTP 或 HTTPS 地址。
 * @param destination 下载成功后保存内容的目标文件。
 * @param progress 接收已传输和总字节数的进度回调；null 表示不接收进度。
 * @param connectionFactory 按 URL 创建 HTTP 连接的函数。
 * @return 下载后的本地文件及实际字节数。
 */
internal suspend fun downloadFile(
    remoteUrl: String,
    destination: File,
    progress: StorageProgressListener?,
    connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
): DownloadedFile = withContext(Dispatchers.IO) {
    val resolvedDestination = destination.absoluteFile
    val parent = resolvedDestination.parentFile ?: throw XmaxError(
        XmaxErrorCode.INVALID_CONFIGURATION,
        "Download destination must have a parent directory",
    )
    if ((!parent.exists() && !parent.mkdirs()) || !parent.isDirectory) {
        throw XmaxError(
            XmaxErrorCode.DOWNLOAD_ERROR,
            "Download destination directory is unavailable",
        )
    }

    val destinationKey = resolvedDestination.canonicalPath
    if (activeDownloads.putIfAbsent(destinationKey, Unit) != null) throw XmaxError(
        XmaxErrorCode.INVALID_CONFIGURATION, "Another download is writing to this destination",
    )
    try {
        withCancellableConnection(URL(remoteUrl), factory = connectionFactory) { connection, ensureActive ->
            var temporaryFile: File? = null
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                val status = connection.responseCode
                if (status !in 200..299) {
                    throw XmaxError(
                        XmaxErrorCode.DOWNLOAD_ERROR,
                        "Storage download failed with HTTP $status",
                        httpStatus = status,
                    )
                }

                val total = connection.contentLengthLong
                var completed = 0L
                val stagingFile = File.createTempFile(
                    ".xmax-download-",
                    ".tmp",
                    parent,
                )
                temporaryFile = stagingFile
                connection.inputStream.buffered().use { input ->
                    stagingFile.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            completed += count
                            runCatching { progress?.onProgress(completed, total) }
                        }
                    }
                }

                if (total >= 0L && completed != total) {
                    throw XmaxError(
                        XmaxErrorCode.DOWNLOAD_ERROR,
                        "Storage download was incomplete: expected $total bytes, received $completed",
                    )
                }

                ensureActive()
                replaceAtomically(stagingFile, resolvedDestination)
                temporaryFile = null
                runCatching { progress?.onProgress(completed, completed) }
                DownloadedFile(destination, completed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: XmaxError) {
                throw error
            } catch (error: Throwable) {
                ensureActive()
                throw XmaxError(
                    XmaxErrorCode.DOWNLOAD_ERROR,
                    error.message ?: "Storage download failed",
                    cause = error,
                )
            } finally {
                runCatching { temporaryFile?.delete() }
            }
        }
    } finally { activeDownloads.remove(destinationKey) }
}

private val activeDownloads = ConcurrentHashMap<String, Unit>()

/**
 * 以完整下载的文件替换目标文件，替换失败时保留错误信息。
 *
 * @param source 准备替换目标文件的完整下载文件。
 * @param destination 下载成功后保存内容的目标文件。
 */
private fun replaceAtomically(source: File, destination: File) {
    try {
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (error: AtomicMoveNotSupportedException) {
        throw XmaxError(
            XmaxErrorCode.DOWNLOAD_ERROR,
            "Download destination does not support atomic replacement",
            cause = error,
        )
    }
}
