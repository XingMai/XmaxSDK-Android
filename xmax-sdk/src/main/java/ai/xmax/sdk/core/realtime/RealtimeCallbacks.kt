package ai.xmax.sdk

import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 状态和错误通知异步派发到主线程，远端帧使用独立后台串行队列；隔离接入方异常。
 * 排队时捕获注册版本，执行前再次校验，使已替换或注销监听器的待执行通知失效。
 */
internal class RealtimeCallbacks(
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    // 生命周期与 Manager 一致，运行时重建后仍使用同一串行队列。
    val remoteVideoFrames: RealtimeVideoFrameDispatcher = RealtimeVideoFrameDispatcher(),
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var stateListener: RealtimeStateListener? = null
    private var errorListener: RealtimeErrorListener? = null
    private var stateVersion = 0L
    private var errorVersion = 0L

    /** 替换注册并排队发送当前快照；传 null 时只注销。 */
    fun setStateListener(listener: RealtimeStateListener?, state: RealtimeState) = synchronized(lock) {
        stateVersion++
        stateListener = listener
        state(state)
    }

    /** 替换错误监听器，不重放注册之前发生的故障。 */
    fun setErrorListener(listener: RealtimeErrorListener?) = synchronized(lock) {
        errorVersion++
        errorListener = listener
    }

    /** 将快照交给排队时的监听器，执行前再次验证该注册仍然有效。 */
    fun state(state: RealtimeState) = synchronized(lock) {
        val version = stateVersion
        val listener = stateListener ?: return@synchronized
        scope.launch {
            if (synchronized(lock) { version == stateVersion }) protect { listener.onStateChanged(state) }
        }
        Unit
    }

    /** 只接纳致命错误；调用方负责保证资源清理先于此通知。 */
    fun error(error: XmaxError) = synchronized(lock) {
        if (error.severity != XmaxErrorSeverity.FATAL) return@synchronized
        val version = errorVersion
        val listener = errorListener ?: return@synchronized
        scope.launch {
            if (synchronized(lock) { version == errorVersion }) protect { listener.onError(error) }
        }
        Unit
    }

    /** 用户回调故障仅记录诊断，避免再次触发用户错误回调形成递归。 */
    private inline fun protect(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            XmaxLogger.realtime.warn(
                message = { "Realtime listener failed: ${ErrorMessageFormatter.format(error)}" },
            )
        }
    }
}
