//
//  XmaxRealtimeConnectionManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.render.RenderControlling
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.StreamID
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 将服务端会话、RTC 房间、本地发布及远端轨道作为一组连接资源管理。
 * operationMutex 覆盖连接、断连和失败回滚，防止后一连接接管尚未释放的旧资源。
 * stateLock 仅保护当前会话和轨道快照，不在持锁期间执行挂起操作。
 *
 * @param sessionService 创建、续期和关闭服务端会话的服务。
 * @param interactionController 提交触控轨迹的控制器。
 * @param renderController 绑定视频轨道与预览视图的控制器。
 * @param streamController 控制 RTC 发布、订阅及生成信令的组件。
 */
internal class XmaxRealtimeConnectionManager(
    private val sessionService: RealtimeSessionServicing,
    private val interactionController: InteractionControlling,
    private val renderController: RenderControlling,
    private val streamController: StreamControlling,
) {
    /**
     * 连接状态及生命周期操作的并发保护。
     */
    private val stateLock = Any()
    private val operationMutex = Mutex()

    /**
     * 当前会话、结果轨道及入房时的输入规格。
     */
    private var activeRemoteTrack: RealtimeVideoTrack? = null
    private var activeSession: RealtimeSession? = null
    private var inputFormat: RealtimeVideoFormat? = null
    private var inputHasAudio = false

    /**
     * 按实际入房配置判断能否复用，连续取消切流时不能依赖已解绑的媒体快照。
     *
     * @param format 视频尺寸、帧率及上行编码参数。
     * @param hasAudio 输入媒体是否包含可用音频。
     * @return 当前连接可复用该输入时为 true，否则为 false。
     */
    fun acceptsInput(format: RealtimeVideoFormat, hasAudio: Boolean): Boolean = synchronized(stateLock) {
        activeSession != null && inputFormat == format && inputHasAudio == hasAudio
    }

    /**
     * 供业务层读取的连接快照。
     *
     * 当前已建立连接的会话标识；尚未完成连接或已断开时为空。
     */
    val currentSessionId: String
        get() = synchronized(stateLock) { activeSession?.id.orEmpty() }

    /**
     * 当前远端轨道容器；存在此对象不代表生成结果或远端首帧已就绪。
     */
    val currentRemoteStream: RealtimeMediaStream?
        get() = synchronized(stateLock) {
            if (activeSession == null) null else activeRemoteTrack?.let {
                RealtimeMediaStream(StreamID.REMOTE.value, it)
            }
        }

    /**
     * 将远端实际格式同步给视频轨道；无活动轨道时忽略。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     */
    fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat) {
        val track = synchronized(stateLock) { activeRemoteTrack } ?: return
        track.updateVideoFormat(videoFormat)
    }

    /**
     * 依次创建服务端会话、加入 RTC 并按输入类型决定是否发布媒体，再启动心跳和注册远端渲染。
     * 每个异步边界后检查 isCurrent；失败时在同一操作锁内回滚，保留原始错误及取消语义。
     * 心跳故障通过 onHeartbeatFailure 返回上层，不在心跳协程中等待整套连接清理。
     *
     * @param model 用于确定服务端能力、地址及输入规格的模型。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param includeLocalAudio 是否同时发布本地音频。
     * @param publishLocalMedia 是否发布本地媒体；服务端直接读取网络视频时为 false；默认值为 `true`。
     * @param isCurrent 判断当前操作是否仍有效的函数。
     * @param onHeartbeatFailure 服务端会话续期失败时接收会话标识和错误的回调。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    suspend fun connect(
        model: RealtimeModel,
        videoFormat: RealtimeVideoFormat,
        includeLocalAudio: Boolean,
        publishLocalMedia: Boolean = true,
        isCurrent: () -> Boolean,
        onHeartbeatFailure: suspend (String, XmaxError) -> Unit,
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentSessionId.isNotEmpty()) throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Realtime connection is already open")
        val timing = currentCoroutineContext()[RealtimeTiming.Attempt]
        timing?.mark(RealtimeTiming.Stage.CONNECTION_START)
        var session: RealtimeSession? = null
        try {
            timing?.mark(RealtimeTiming.Stage.SESSION_START)
            session = sessionService.createSession(model)
            timing?.mark(RealtimeTiming.Stage.SESSION_END)
            ensureCurrent(isCurrent)
            val connection = session.connection ?: throw XmaxError(
                XmaxErrorCode.SESSION_ERROR,
                "Session does not contain complete RTC join information",
            )
            streamController.connect(connection, includeLocalAudio, publishLocalMedia) {
                ensureCurrent(isCurrent)
            }
            ensureCurrent(isCurrent)

            sessionService.startHeartbeat(session.id, onHeartbeatFailure)
            val remoteTrack = RealtimeVideoTrack(
                id = connection.botName ?: "video-remote",
                videoFormat = videoFormat,
            )
            renderController.registerRemoteTrack(remoteTrack, IntSize(videoFormat.width, videoFormat.height)) { frame ->
                interactionController.submitInteraction(frame)
            }
            synchronized(stateLock) {
                activeSession = session
                activeRemoteTrack = remoteTrack
                inputFormat = videoFormat
                inputHasAudio = includeLocalAudio
            }
            ensureCurrent(isCurrent)
            timing?.mark(RealtimeTiming.Stage.CONNECTION_END)
            return@withLock RealtimeMediaStream(StreamID.REMOTE.value, remoteTrack)
        } catch (error: Throwable) {
            /**
             * 回滚仍持有操作锁，此时旧连接的资源不可能已被后续 connect 接管。
             */
            cleanupAfterFailure(error,
                { sessionService.stopHeartbeat() },
                { val track = synchronized(stateLock) {
                    activeRemoteTrack.also { activeSession = null; activeRemoteTrack = null }
                }; renderController.resetRemoteTrack(track) },
                { streamController.disconnect() },
                { session?.id?.let { closeSessionBestEffort(it) } },
            )
            throw XmaxError.from(error)
        }
    }

    /**
     * 清空活动连接快照并逐项释放资源，返回刚关闭的会话标识；不停止本地媒体源。
     *
     * @return 已关闭的会话标识；没有活动会话时为 null。
     */
    suspend fun disconnect(): String? = operationMutex.withLock {
        val resources = synchronized(stateLock) {
            ConnectionResources(activeSession, activeRemoteTrack).also {
                activeSession = null
                activeRemoteTrack = null
            }
        }
        cleanupResources(
            { sessionService.stopHeartbeat() },
            { renderController.resetRemoteTrack(resources.remoteTrack) },
            { streamController.disconnect() },
            { resources.session?.id?.let { closeSessionBestEffort(it) } },
        )
        resources.session?.id
    }

    /**
     * 服务端关闭最多等待 5 秒；失败记录日志，避免远端接口故障阻止本地资源回收。
     *
     * @param sessionId 服务端会话标识。
     */
    private suspend fun closeSessionBestEffort(sessionId: String) {
        try { withTimeout(5_000L) { sessionService.closeSession(sessionId) } }
        catch (error: Throwable) { logCleanupFailure("Failed to close realtime session $sessionId", error) }
    }

    /**
     * 记录资源清理失败的原因，不覆盖触发清理的原始错误。
     *
     * @param title 日志条目的说明标题。
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    private fun logCleanupFailure(title: String, error: Throwable) {
        XmaxLogger.realtime.error(
            message = {
                "$title\n└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
            },
        )
    }

    /**
     * 将失去业务所有权的连接结果视为取消，而非向用户报告新的业务故障。
     *
     * @param isCurrent 判断当前操作是否仍有效的函数。
     */
    private fun ensureCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) {
            throw CancellationException("Realtime connection was cancelled")
        }
    }

    /**
     * 断连时从活动状态取出的资源快照，后续清理仅操作这一组资源。
     *
     * @param session 服务端会话及其连接信息。
     * @param remoteTrack 当前远端视频轨道。
     */
    private data class ConnectionResources(
        val session: RealtimeSession?,
        val remoteTrack: RealtimeVideoTrack?,
    )
}
