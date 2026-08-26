package ai.xmax.sdk

/** 表示一次文件传输的进度。 */
public data class XmaxStorageProgress(
    public val completedBytes: Long,
    public val totalBytes: Long,
) {
    /** 限制在 0 至 1 范围内的完成比例。 */
    public val fractionCompleted: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (completedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

/** 文件传输进度监听器。 */
public fun interface XmaxStorageProgressListener {
    public fun onProgress(progress: XmaxStorageProgress)
}

/** 表示上传成功的文件。 */
public data class XmaxUploadedFile(
    public val url: String,
    public val objectKey: String,
    public val etag: String? = null,
)

/** 表示下载成功的文件。 */
public data class XmaxDownloadedFile(
    public val filePath: String,
    public val byteCount: Long,
)
