package ai.xmax.sdk.render.video

import ai.xmax.sdk.RealtimeVideoFrame
import ai.xmax.sdk.RealtimeVideoFrameListener
import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 监听器版本与生成身份共同隔离旧帧；慢消费者最多保留一张待派发帧，避免无界积压。 */
internal class RealtimeVideoFrameDispatcher(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private var listener: RealtimeVideoFrameListener? = null
    private var generation: Any? = null
    private var version = 0L
    private var pending: Pending? = null
    private var draining = false

    fun setListener(listener: RealtimeVideoFrameListener?) = synchronized(lock) {
        this.listener = listener
        version++
        pending = null
    }

    fun setGeneration(generation: Any?) = synchronized(lock) {
        this.generation = generation
        version++
        pending = null
    }

    fun dispatch(generation: Any, frame: RtcRemoteVideoFrame) {
        val version = synchronized(lock) {
            if (this.generation !== generation || listener == null) return
            version
        }
        // 无监听器时不复制；借用的原生像素只在当前 RTC 回调内访问。
        val copy = frame.copy()
        val launch = synchronized(lock) {
            if (this.version != version || this.generation !== generation || listener == null) return
            pending = Pending(version, copy)
            if (draining) false else { draining = true; true }
        }
        if (launch) scope.launch { drain() }
    }

    private fun drain() {
        while (true) {
            val delivery = synchronized(lock) {
                val next = pending
                pending = null
                if (next == null) { draining = false; return }
                val callback = listener.takeIf { next.version == version }
                next.frame to callback
            }
            try {
                delivery.second?.onFrame(delivery.first)
            } catch (error: Throwable) {
                // 接入方回调异常仅记录诊断，不能中断渲染或触发 SDK 致命错误通知。
                XmaxLogger.warn({ "Remote video frame listener failed: ${error.message}" }, category = "Render")
            }
        }
    }

    private data class Pending(val version: Long, val frame: RealtimeVideoFrame)
}
