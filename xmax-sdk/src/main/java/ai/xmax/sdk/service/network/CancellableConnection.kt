//
//  CancellableConnection.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.service.network

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 执行可取消的 HTTP 操作；取消时中断网络读写，并在底层操作退出后结束调用。
 *
 * @param url 资源或请求的网络地址。
 * @param dispatcher 执行后台操作的协程调度器；默认值为 `Dispatchers.IO`。
 * @param factory 按 URL 创建 HTTP 连接的函数。
 * @param block 使用 HTTP 连接并检查取消状态的操作。
 * @return 传入操作成功执行后的结果。
 */
internal suspend fun <T> withCancellableConnection(
    url: URL,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    factory: (URL) -> HttpURLConnection,
    block: (HttpURLConnection, () -> Unit) -> T,
): T = coroutineScope {
    val owner = this
    suspendCancellableCoroutine { continuation ->
        val connection = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { runCatching { connection.get()?.disconnect() } }
        owner.launch(dispatcher) {
            val context = currentCoroutineContext()
            val result = runCatching {
                context.ensureActive()
                val opened = factory(url)
                connection.set(opened)
                try {
                    context.ensureActive()
                    block(opened) { context.ensureActive() }
                } finally {
                    connection.set(null)
                    runCatching { opened.disconnect() }
                }
            }
            continuation.resumeWith(result)
        }
    }
}
