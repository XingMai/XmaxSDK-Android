//
//  RealtimeVideoFrameDispatcher.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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

/**
 * 监听器版本与生成身份共同隔离旧帧；慢消费者最多保留一张待派发帧，避免无界积压。
 *
 * @param dispatcher 执行后台操作的协程调度器。
 */
internal class RealtimeVideoFrameDispatcher(
    dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    /**
     * 远端视频帧的后台派发作用域。
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * 帧监听与任务状态的并发保护。
     */
    private val lock = Any()

    /**
     * 当前监听器、生成归属及最新待派发帧。
     */
    private var listener: RealtimeVideoFrameListener? = null
    private var generation: Any? = null
    private var version = 0L
    private var pending: Pending? = null
    private var draining = false

    /**
     * 替换远端视频帧监听器并丢弃旧监听器的待处理帧。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    fun setListener(listener: RealtimeVideoFrameListener?) = synchronized(lock) {
        this.listener = listener
        version++
        pending = null
    }

    /**
     * 设置允许派发帧的当前生成标识。
     *
     * @param generation 用于识别当前生成请求或事件归属的标识。
     */
    fun setGeneration(generation: Any?) = synchronized(lock) {
        this.generation = generation
        version++
        pending = null
    }

    /**
     * 仅为当前生成任务派发远端视频帧。
     *
     * @param generation 用于识别当前生成请求或事件归属的标识。
     * @param frame 待处理、显示或提交的视频帧。
     */
    fun dispatch(generation: Any, frame: RtcRemoteVideoFrame) {
        val version = synchronized(lock) {
            if (this.generation !== generation || listener == null) return
            version
        }
        /**
         * 无监听器时不复制；借用的原生像素只在当前 RTC 回调内访问。
         */
        val copy = frame.copy()
        val launch = synchronized(lock) {
            if (this.version != version || this.generation !== generation || listener == null) return
            pending = Pending(version, copy)
            if (draining) false else { draining = true; true }
        }
        if (launch) scope.launch { drain() }
    }

    /**
     * 将当前生成的最新待处理帧交给监听器，跳过失效任务的帧。
     */
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
                /**
                 * 接入方回调异常仅记录诊断，不能中断渲染或触发 SDK 致命错误通知。
                 */
                XmaxLogger.render.warn(
                    message = { "Remote video frame listener failed: ${error.message}" },
                )
            }
        }
    }

    /**
     * 保存待派发视频帧及其所属生成代次。
     *
     * @param version 用于识别当前任务或回调归属的版本号。
     * @param frame 待处理、显示或提交的视频帧。
     */
    private data class Pending(val version: Long, val frame: RealtimeVideoFrame)
}
