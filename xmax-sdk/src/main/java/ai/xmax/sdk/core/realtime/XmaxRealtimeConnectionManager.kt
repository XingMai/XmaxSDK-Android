package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.render.RenderControlling
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.StreamID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 将服务端会话、RTC 房间、本地发布及远端轨道作为一组连接资源管理。
 * operationMutex 覆盖连接、断连和失败回滚，防止后一连接接管尚未释放的旧资源。
 * stateLock 仅保护当前会话和轨道快照，不在持锁期间执行挂起操作。
 */
internal class XmaxRealtimeConnectionManager(
    private val sessionService: RealtimeSessionServicing,
    private val interactionController: InteractionControlling,
    private val renderController: RenderControlling,
    private val streamController: StreamControlling,
) {
    private val stateLock = Any()
    private val operationMutex = Mutex()
    private var activeRemoteTrack: RealtimeVideoTrack? = null
    private var activeSession: RealtimeSession? = null

    /** 当前已建立连接的会话标识；尚未完成连接或已断开时为空。 */
    val currentSessionId: String
        get() = synchronized(stateLock) { activeSession?.id.orEmpty() }

    /** 当前远端轨道容器；存在此对象不代表生成结果或远端首帧已就绪。 */
    val currentRemoteStream: RealtimeMediaStream?
        get() = synchronized(stateLock) {
            if (activeSession == null) null else activeRemoteTrack?.let {
                RealtimeMediaStream(StreamID.REMOTE.value, it)
            }
        }

    /** 将远端实际格式同步给轨道与交互坐标映射；无活动轨道时忽略。 */
    fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat) {
        val track = synchronized(stateLock) { activeRemoteTrack } ?: return
        track.updateVideoFormat(videoFormat)
        renderController.updateRemoteVideoFormat(videoFormat, track)
    }

    /**
     * 依次创建服务端会话、加入 RTC 并发布本地媒体，再启动会话心跳和注册远端渲染绑定。
     * 每个异步边界后检查 isCurrent；失败时在同一操作锁内回滚，保留原始错误及取消语义。
     * 心跳故障通过 onHeartbeatFailure 返回上层，不在心跳协程中等待整套连接清理。
     */
    suspend fun connect(
        model: RealtimeModel,
        videoFormat: RealtimeVideoFormat,
        includeLocalAudio: Boolean,
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
            streamController.connect(connection, includeLocalAudio) {
                ensureCurrent(isCurrent)
            }
            ensureCurrent(isCurrent)

            sessionService.startHeartbeat(session.id, onHeartbeatFailure)
            val remoteTrack = RealtimeVideoTrack(
                id = connection.botName ?: "video-remote",
                videoFormat = videoFormat,
            )
            renderController.registerRemoteTrack(remoteTrack) { frame ->
                interactionController.submitInteraction(frame)
            }
            synchronized(stateLock) {
                activeSession = session
                activeRemoteTrack = remoteTrack
            }
            ensureCurrent(isCurrent)
            timing?.mark(RealtimeTiming.Stage.CONNECTION_END)
            return@withLock RealtimeMediaStream(StreamID.REMOTE.value, remoteTrack)
        } catch (error: Throwable) {
            // 回滚仍持有操作锁，此时旧连接的资源不可能已被后续 connect 接管。
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

    /** 清空活动连接快照并逐项释放资源，返回刚关闭的会话标识；不停止本地媒体源。 */
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

    /** 服务端关闭最多等待 5 秒；失败记录日志，避免远端接口故障阻止本地资源回收。 */
    private suspend fun closeSessionBestEffort(sessionId: String) {
        try { withTimeout(5_000L) { sessionService.closeSession(sessionId) } }
        catch (error: Throwable) { logCleanupFailure("Failed to close realtime session $sessionId", error) }
    }

    private fun logCleanupFailure(title: String, error: Throwable) {
        XmaxLogger.error(
            { "$title\n└─ 原因：${ErrorMessageFormatter.format(error)}" },
            category = "Realtime",
        )
    }

    /** 将失去业务所有权的连接结果视为取消，而非向用户报告新的业务故障。 */
    private fun ensureCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) {
            throw CancellationException("Realtime connection was cancelled")
        }
    }

    /** 断连时从活动状态取出的资源快照，后续清理仅操作这一组资源。 */
    private data class ConnectionResources(
        val session: RealtimeSession?,
        val remoteTrack: RealtimeVideoTrack?,
    )
}
