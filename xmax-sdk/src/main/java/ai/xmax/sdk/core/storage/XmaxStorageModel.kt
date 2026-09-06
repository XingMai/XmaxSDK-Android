package ai.xmax.sdk

/** 表示一次文件传输的进度。 */
public data class XmaxStorageProgress(
    /** 本次传输已完成的字节数。 */
    public val completedBytes: Long,
    /** 总字节数；底层尚未获知总大小时可能为非正值。 */
    public val totalBytes: Long,
) {
    /** 限制在 0 至 1 范围内的完成比例；总大小未知或为零时返回 0。 */
    public val fractionCompleted: Float
        get() = if (totalBytes <= 0L) {
            0f
        } else {
            (completedBytes.toDouble() / totalBytes.toDouble()).coerceIn(0.0, 1.0).toFloat()
        }
}

/** 文件传输进度监听器；通知不保证在主线程执行，且不代表内容安全检查已通过。 */
public fun interface XmaxStorageProgressListener {
    /** 接收字节进度快照；最终成功结果以挂起调用的返回值为准。 */
    public fun onProgress(progress: XmaxStorageProgress)
}

/** 表示上传成功的文件。 */
public data class XmaxUploadedFile(
    /** 上传后的访问地址；安全检查版本可能返回服务端调整后的地址。 */
    public val url: String,
    /** 远端存储对象的键。 */
    public val objectKey: String,
    /** 存储服务返回的实体标签；未提供时为 null。 */
    public val etag: String? = null,
)

/** 表示下载成功的文件。 */
public data class XmaxDownloadedFile(
    /** 下载目标的本地路径，与调用时指定的目标文件对应。 */
    public val filePath: String,
    /** 下载成功后实际写入的字节数。 */
    public val byteCount: Long,
)
