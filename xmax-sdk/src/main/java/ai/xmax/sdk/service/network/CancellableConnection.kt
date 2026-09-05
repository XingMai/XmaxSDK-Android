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

/** Cancellation interrupts I/O immediately; the structured worker still joins before returning. */
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
