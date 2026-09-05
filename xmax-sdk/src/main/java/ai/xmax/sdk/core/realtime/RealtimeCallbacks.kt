package ai.xmax.sdk

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 用户通知有序派发到主线程；注册版本隔离已注销或替换的监听。 */
internal class RealtimeCallbacks(dispatcher: CoroutineDispatcher = Dispatchers.Main) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var stateListener: RealtimeStateListener? = null
    private var errorListener: RealtimeErrorListener? = null
    private var stateVersion = 0L
    private var errorVersion = 0L

    fun setStateListener(listener: RealtimeStateListener?, state: RealtimeState) = synchronized(lock) {
        stateVersion++
        stateListener = listener
        state(state)
    }

    fun setErrorListener(listener: RealtimeErrorListener?) = synchronized(lock) {
        errorVersion++
        errorListener = listener
    }

    fun state(state: RealtimeState) = synchronized(lock) {
        val version = stateVersion
        val listener = stateListener ?: return@synchronized
        scope.launch {
            if (synchronized(lock) { version == stateVersion }) protect { listener.onStateChanged(state) }
        }
        Unit
    }

    fun error(error: XmaxError) = synchronized(lock) {
        if (error.severity != XmaxErrorSeverity.FATAL) return@synchronized
        val version = errorVersion
        val listener = errorListener ?: return@synchronized
        scope.launch {
            if (synchronized(lock) { version == errorVersion }) protect { listener.onError(error) }
        }
        Unit
    }

    fun clear() = synchronized(lock) {
        stateVersion++
        errorVersion++
        stateListener = null
        errorListener = null
    }

    private inline fun protect(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            XmaxLogger.warn({ "Realtime listener failed: ${ErrorMessageFormatter.format(error)}" }, "Realtime")
        }
    }
}
