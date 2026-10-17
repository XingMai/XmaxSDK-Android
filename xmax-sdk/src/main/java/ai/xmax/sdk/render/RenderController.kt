//
//  RenderController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.render

import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoSink
import ai.xmax.sdk.foundation.rtc.RtcRemoteVideoFrame
import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.render.trajectory.TrajectoryBinding
import ai.xmax.sdk.render.trajectory.TrajectoryRegistry
import ai.xmax.sdk.render.video.VideoRenderBinding
import ai.xmax.sdk.render.video.VideoRenderRegistry
import androidx.annotation.MainThread
import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext

/**
 * 每轮生成使用同一条后处理帧链路提供首帧门控、持续显示和接入方帧回调。
 *
 * @param rtcManager 提供 RTC 采集、发布、订阅和渲染能力的管理器。
 * @param remoteFrameReadyTimeoutMillis 等待远端首帧可显示的最大时长，单位为毫秒；默认值为 `REMOTE_FRAME_READY_TIMEOUT_MILLIS`。
 * @param renderDispatcher 执行视图绑定和显示更新的协程调度器；默认值为 `Dispatchers.Main.immediate`。
 * @param frameDispatcher 远端视频帧的派发组件。
 * @param errorListener 接收媒体错误的回调。
 */
internal class RenderController(
    private val rtcManager: RtcManaging,
    private val remoteFrameReadyTimeoutMillis: Long = REMOTE_FRAME_READY_TIMEOUT_MILLIS,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val frameDispatcher: RealtimeVideoFrameDispatcher = RealtimeVideoFrameDispatcher(),
    private val errorListener: (XmaxError) -> Unit = {},
) : RenderControlling {
    /**
     * 远端渲染状态的并发保护。
     *
     * 串行保护轨道注册、画布绑定和释放；等待首帧时不持锁。
     */
    private val operationLock = ReentrantLock()

    /**
     * 当前结果流及视图、轨道绑定。
     */
    private var generation: RemoteGeneration? = null
    private var remoteView: WeakReference<XmaxVideoView>? = null
    private var remoteContentMode = VideoContentMode.FILL
    private var remoteTrack: RealtimeVideoTrack? = null

    /**
     * 设置当前订阅的远端流；null 表示解除远端流绑定。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    @MainThread
    override fun setRemoteStream(stream: RemoteStream?) = operationLock.withLock {
        val previous = generation
        val next = stream?.let(::RemoteGeneration)
        generation = next
        frameDispatcher.setGeneration(next)
        previous?.ready?.cancel(CancellationException("Remote generation was replaced"))
        remoteView?.get()?.invalidateVideoPresentation()
        try {
            if (previous != null) {
                val failure = runCatching {
                    rtcManager.setRemoteVideoFrameListener(previous.stream, null)
                }.exceptionOrNull()
                try {
                    rtcManager.unbindRemoteVideo(previous.stream)
                } catch (error: Throwable) {
                    if (failure == null) throw error
                    if (failure !== error) failure.addSuppressed(error)
                }
                failure?.let { throw it }
            }
            if (next != null) {
                /**
                 * 目标机器人发布确认后注册本轮帧监听；同一流也不能复用上一轮首帧标记。
                 */
                rtcManager.setRemoteVideoFrameListener(next.stream, object : RtcRemoteVideoSink {
                    /**
                     * 通知订阅的视频流已到达首帧及其尺寸。
                     *
                     * @param width 画面宽度，单位为像素。
                     * @param height 画面高度，单位为像素。
                     */
                    override fun onFirstFrame(width: Int, height: Int) = handleRemoteVideoFrame(next, width, height)

                    /**
                     * 接收当前订阅的视频帧。
                     *
                     * @param frame 待处理、显示或提交的视频帧。
                     */
                    override fun onFrame(frame: RtcRemoteVideoFrame) {
                        operationLock.withLock {
                            if (generation === next) updateDisplayedFormat(frame.displayWidth, frame.displayHeight)
                        }
                        frameDispatcher.dispatch(next, frame)
                    }

                    /**
                     * 报告当前媒体或 RTC 操作发生的错误。
                     *
                     * @param error 本次操作失败的异常或 SDK 错误。
                     */
                    override fun onError(error: XmaxError): Unit = operationLock.withLock {
                        if (generation !== next) return
                        frameDispatcher.setGeneration(null)
                        if (!next.ready.completeExceptionally(error)) errorListener(error)
                    }
                })
            }
        } catch (error: Throwable) {
            next?.ready?.completeExceptionally(error)
            throw error
        }
    }

    /**
     * 为远端轨道注册视频显示和交互轨迹能力。
     *
     * @param track 需要显示或绑定的视频轨道。
     * @param interactionListener 接收触控轨迹帧的回调。
     */
    override fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        interactionListener: (InteractionFrame) -> Unit,
    ) {
        operationLock.withLock { remoteTrack = track }
        VideoRenderRegistry.register(
            track,
            VideoRenderBinding(
                attachHandler = ::attachRemoteVideo,
                detachHandler = ::detachRemoteVideo,
            ),
        )
        track.videoFormat?.let { videoFormat ->
            TrajectoryRegistry.register(track, TrajectoryBinding(interactionListener, videoFormat))
        }
    }

    /**
     * 更新远端轨道及交互坐标使用的视频规格。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param track 需要显示或绑定的视频轨道。
     */
    override fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat, track: RealtimeVideoTrack) {
        TrajectoryRegistry.binding(track)?.update(videoFormat)
    }

    /**
     * 等待当前远端流首帧可显示，超时或流失效时抛出异常。
     */
    override suspend fun waitUntilRemoteFrameReady() {
        val current = operationLock.withLock { generation } ?: throw XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "Remote video stream is unavailable",
        )
        try {
            withTimeout(remoteFrameReadyTimeoutMillis) { current.ready.await() }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "Remote video first frame timed out",
                cause = error,
            )
        }
    }

    /**
     * 注销指定远端轨道并清理相关显示资源。
     *
     * @param track 需要显示或绑定的视频轨道。
     */
    override suspend fun resetRemoteTrack(track: RealtimeVideoTrack?) = withContext(NonCancellable + renderDispatcher) {
        /**
         * 在渲染线程执行释放，避免与界面绑定操作互相等待。
         */
        operationLock.withLock {
            track?.let(VideoRenderRegistry::unregister)
            track?.let(TrajectoryRegistry::unregister)
            try {
                setRemoteStream(null)
            } finally {
                remoteView = null
                remoteContentMode = VideoContentMode.FILL
                remoteTrack = null
            }
        }
    }

    /**
     * 将远端视频绑定到指定预览视图。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    private fun attachRemoteVideo(view: XmaxVideoView, contentMode: VideoContentMode): Unit = operationLock.withLock {
        remoteView = WeakReference(view)
        remoteContentMode = contentMode
        view.invalidateVideoPresentation()
        generation?.takeIf { it.isReady }?.let { current ->
            bindRemoteView(current, view)
        }
    }

    /**
     * 解除指定视图的远端视频绑定。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     */
    private fun detachRemoteVideo(view: XmaxVideoView): Unit = operationLock.withLock {
        if (remoteView?.get() !== view) return
        view.invalidateVideoPresentation()
        remoteView = null
        generation?.takeIf { it.isReady }?.let { rtcManager.unbindRemoteVideo(it.stream) }
    }

    /**
     * 报告当前订阅流的首帧尺寸，忽略失效的订阅。
     *
     * @param current 当前有效的远端生成显示状态。
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     */
    private fun handleRemoteVideoFrame(current: RemoteGeneration, width: Int, height: Int): Unit = operationLock.withLock {
        if (generation !== current || current.ready.isCompleted || width <= 0 || height <= 0) return
        try {
            updateDisplayedFormat(width, height)
            remoteView?.get()?.let { view ->
                bindRemoteView(current, view)
            }
            current.isReady = true
            current.ready.complete(Unit)
        } catch (error: Throwable) {
            /**
             * 通过生成启动调用返回原生渲染配置错误，并按连接故障清理资源。
             */
            current.ready.completeExceptionally(error)
        }
        Unit
    }

    /**
     * 将当前生成结果绑定到远端视频视图。
     *
     * @param current 当前有效的远端生成显示状态。
     * @param view 需要绑定或解除绑定的显示视图。
     */
    private fun bindRemoteView(current: RemoteGeneration, view: XmaxVideoView) {
        /**
         * 同一轨道启动新任务时重新绑定显示资源，并重新等待该任务的首帧。
         */
        view.prepareRtcVideoRendering()
        rtcManager.bindRemoteVideo(current.stream, view.rtcRenderView, remoteContentMode)
    }

    /**
     * 以实际接收画面尺寸更新轨道及触控区域，避免使用尚未生效的请求尺寸。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     */
    private fun updateDisplayedFormat(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val track = remoteTrack ?: return
        val previous = track.videoFormat ?: return
        if (previous.width == width && previous.height == height) return

        val format = previous.copy(width = width, height = height)
        track.updateVideoFormat(format)
        TrajectoryRegistry.binding(track)?.update(format)
    }

    /**
     * 保存当前远端生成的首帧等待状态和视图绑定。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    private class RemoteGeneration(val stream: RemoteStream) {
        /**
         * 当前结果流的首帧等待与就绪状态。
         */
        val ready = CompletableDeferred<Unit>()
        var isReady = false
    }

    /**
     * 提供 RenderController 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val REMOTE_FRAME_READY_TIMEOUT_MILLIS = 10_000L
    }
}
