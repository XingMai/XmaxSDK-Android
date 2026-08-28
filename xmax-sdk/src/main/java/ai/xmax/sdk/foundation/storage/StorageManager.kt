package ai.xmax.sdk.foundation.storage

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 封装腾讯云 COS 上传和 HTTP 文件下载能力。 */
internal class StorageManager(context: Context) : StorageManaging {
    private val applicationContext = context.applicationContext

    override suspend fun upload(
        source: StorageSource,
        objectKey: String,
        contentType: String,
        configuration: StorageConfiguration,
        progress: StorageProgressListener?,
    ): StoredFile = suspendCancellableCoroutine { continuation ->
        try {
            val serviceConfig = makeServiceConfiguration(configuration)
            val service = CosXmlSimpleService(applicationContext, serviceConfig)
            // The STS policy currently grants PutObject for the issued prefix. Keep local-file
            // uploads on the simple PutObject path, matching the Harmony implementation, instead
            // of allowing the COS SDK's 2 MiB threshold to switch to multipart-only operations.
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
                progress?.onProgress(completed, total)
            }
            task.setCosXmlResultListener(
                object : CosXmlResultListener {
                    override fun onSuccess(request: CosXmlRequest, result: CosXmlResult) {
                        if (!continuation.isActive) return
                        val uploadResult = result as? COSXMLUploadTask.COSXMLUploadTaskResult
                        val url = resolveUrl(result.accessUrl, configuration, objectKey)
                        continuation.resume(
                            StoredFile(
                                url = url,
                                objectKey = objectKey,
                                etag = uploadResult?.eTag?.trim()?.takeIf { it.isNotEmpty() },
                            ),
                        )
                    }

                    override fun onFail(
                        request: CosXmlRequest,
                        clientException: CosXmlClientException?,
                        serviceException: CosXmlServiceException?,
                    ) {
                        if (!continuation.isActive) return
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
            continuation.invokeOnCancellation { task.cancel(true) }
        } catch (error: Throwable) {
            if (continuation.isActive) {
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

    override suspend fun download(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val connection = URL(remoteUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
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
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        completed += count
                        progress?.onProgress(completed, total)
                    }
                }
            }
            progress?.onProgress(completed, completed)
            DownloadedFile(destination, completed)
        } catch (error: XmaxError) {
            throw error
        } catch (error: Throwable) {
            throw XmaxError(
                XmaxErrorCode.DOWNLOAD_ERROR,
                error.message ?: "Storage download failed",
                cause = error,
            )
        } finally {
            connection.disconnect()
        }
    }

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
