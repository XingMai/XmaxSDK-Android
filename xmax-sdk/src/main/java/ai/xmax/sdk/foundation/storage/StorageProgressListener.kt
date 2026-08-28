package ai.xmax.sdk.foundation.storage

/** 基础存储能力使用的字节进度监听器。 */
internal fun interface StorageProgressListener {
    fun onProgress(completedBytes: Long, totalBytes: Long)
}
