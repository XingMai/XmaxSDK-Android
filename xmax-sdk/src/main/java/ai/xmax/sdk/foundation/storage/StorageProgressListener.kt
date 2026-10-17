//
//  StorageProgressListener.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.storage

/**
 * 基础存储能力使用的字节进度监听器。
 */
internal fun interface StorageProgressListener {
    /**
     * 接收当前传输的已完成字节数与总字节数。
     *
     * @param completedBytes 已传输的字节数。
     * @param totalBytes 需要传输的总字节数。
     */
    fun onProgress(completedBytes: Long, totalBytes: Long)
}
