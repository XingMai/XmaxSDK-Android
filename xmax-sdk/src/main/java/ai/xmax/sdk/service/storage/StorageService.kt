package ai.xmax.sdk.service.storage

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.storage.DownloadedFile
import ai.xmax.sdk.foundation.storage.StorageConfiguration
import ai.xmax.sdk.foundation.storage.StorageCredential
import ai.xmax.sdk.foundation.storage.StorageManaging
import ai.xmax.sdk.foundation.storage.StorageProgressListener
import ai.xmax.sdk.foundation.storage.StorageSource
import ai.xmax.sdk.foundation.storage.StoredFile
import ai.xmax.sdk.foundation.storage.TemporaryStorageConfiguration
import ai.xmax.sdk.service.network.ApiServicing
import java.io.File
import java.net.URI
import java.util.UUID
import org.json.JSONObject

internal class StorageService(
    private val apiService: ApiServicing,
    private val storageManager: StorageManaging,
) : StorageServicing {
    override suspend fun uploadImage(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile = upload(StorageSource.Bytes(data), fileName, contentType, MediaType.IMAGE, false, progress)

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

    override suspend fun uploadImageWithSafetyCheck(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile = upload(StorageSource.Bytes(data), fileName, contentType, MediaType.IMAGE, true, progress)

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

    override suspend fun uploadVideo(
        data: ByteArray,
        fileName: String,
        contentType: String,
        progress: StorageProgressListener?,
    ): StoredFile = upload(StorageSource.Bytes(data), fileName, contentType, MediaType.VIDEO, false, progress)

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

    override suspend fun downloadImage(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = download(remoteUrl, destination, progress)

    override suspend fun downloadVideo(
        remoteUrl: String,
        destination: File,
        progress: StorageProgressListener?,
    ): DownloadedFile = download(remoteUrl, destination, progress)

    private suspend fun upload(
        source: StorageSource,
        fileName: String,
        contentType: String,
        mediaType: MediaType,
        checksSafety: Boolean,
        progress: StorageProgressListener?,
    ): StoredFile {
        val safeName = validateUpload(source, fileName, contentType, mediaType)
        val temporary = fetchStorageConfiguration()
        val objectKey = "${temporary.prefix}${System.currentTimeMillis()}_" +
            "${UUID.randomUUID().toString().lowercase()}_$safeName"
        val stored = try {
            storageManager.upload(
                source = source,
                objectKey = objectKey,
                contentType = contentType.trim(),
                configuration = temporary.configuration,
                progress = progress,
            )
        } catch (error: XmaxError) {
            throw error
        } catch (error: Throwable) {
            throw XmaxError(
                XmaxErrorCode.UPLOAD_ERROR,
                error.message ?: "Storage upload failed",
                cause = error,
            )
        }
        if (!checksSafety) return stored
        return stored.copy(url = checkImage(stored.url))
    }

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

    private fun inferVideoContentType(fileName: String): String = inferContentType(
        fileName,
        mapOf(
            "mp4" to "video/mp4", "mov" to "video/quicktime", "m4v" to "video/x-m4v",
            "webm" to "video/webm", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska",
            "3gp" to "video/3gpp", "3g2" to "video/3gpp2", "ts" to "video/mp2t",
        ),
        "video",
    )

    private fun inferContentType(fileName: String, types: Map<String, String>, mediaType: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return types[extension] ?: throw XmaxError(
            XmaxErrorCode.INVALID_CONFIGURATION,
            "Unable to infer $mediaType content type from file extension",
        )
    }

    private fun sanitizeFileName(value: String): String = value.trim()
        .replace(Regex("[^\\p{L}\\p{N}._-]"), "_")
        .replace(Regex("_+"), "_")
        .trim { it == '.' || it == '_' || it == '-' }

    private fun isHttpUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
    } catch (_: Exception) {
        false
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = opt(key)
        if (value !is String) throw invalidCredentialPayload()
        return value.trim()
    }

    private fun JSONObject.requiredNonEmptyString(key: String): String =
        requiredString(key).takeIf { it.isNotEmpty() } ?: throw invalidCredentialPayload()

    private fun invalidCredentialPayload(): XmaxError =
        XmaxError(XmaxErrorCode.API_ERROR, "Invalid storage credential payload")

    private enum class MediaType(val value: String, val label: String) {
        IMAGE("image", "Image"),
        VIDEO("video", "Video"),
    }
}
