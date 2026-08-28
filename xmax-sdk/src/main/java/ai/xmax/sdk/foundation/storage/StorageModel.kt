package ai.xmax.sdk.foundation.storage

import java.io.File

internal data class StorageCredential(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String,
)

internal data class StorageConfiguration(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val credential: StorageCredential,
)

internal data class TemporaryStorageConfiguration(
    val prefix: String,
    val configuration: StorageConfiguration,
)

internal sealed interface StorageSource {
    val byteCount: Long

    data class LocalFile(val file: File) : StorageSource {
        override val byteCount: Long = file.length()
    }

    data class Bytes(val data: ByteArray) : StorageSource {
        override val byteCount: Long = data.size.toLong()
    }
}

internal data class StoredFile(
    val url: String,
    val objectKey: String,
    val etag: String? = null,
)

internal data class DownloadedFile(
    val file: File,
    val byteCount: Long,
)
