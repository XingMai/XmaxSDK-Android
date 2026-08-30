package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.rendering.RenderControlling
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.StreamID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** 协调服务端会话、RTC 房间、本地发布和远端媒体资源。 */
internal class XmaxRealtimeConnectionManager(
    private val sessionService: RealtimeSessionServicing,
    private val interactionController: InteractionControlling,
    private val renderController: RenderControlling,
    private val streamController: StreamControlling,
) {
    private val stateLock = Any()
    private var activeRemoteTrack: RealtimeVideoTrack? = null
    private var activeSession: RealtimeSession? = null

    val currentSessionId: String
        get() = synchronized(stateLock) { activeSession?.id.orEmpty() }

    val currentRemoteStream: RealtimeMediaStream?
        get() = synchronized(stateLock) {
            if (activeSession == null) null else activeRemoteTrack?.let {
                RealtimeMediaStream(StreamID.REMOTE.value, it)
            }
        }

    fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat) {
        val track = synchronized(stateLock) { activeRemoteTrack } ?: return
        track.updateVideoFormat(videoFormat)
        renderController.updateRemoteVideoFormat(videoFormat, track)
    }

    suspend fun connect(
        model: RealtimeModel,
        videoFormat: RealtimeVideoFormat,
        includeLocalAudio: Boolean,
        isCurrent: () -> Boolean,
        onHeartbeatFailure: suspend (String, XmaxError) -> Unit,
    ): RealtimeMediaStream {
        var session: RealtimeSession? = null
        try {
            session = sessionService.createSession(model)
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
            return RealtimeMediaStream(StreamID.REMOTE.value, remoteTrack)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                rollbackConnection()
                if (session != null) {
                    runCatching { sessionService.closeSession(session.id) }
                        .onFailure {
                            logCleanupFailure(
                                "连接回滚关闭会话失败 " +
                                    "(Failed to Close Session During Connection Rollback)",
                                it,
                            )
                        }
                }
            }
            if (!isCurrent()) {
                throw XmaxError(
                    XmaxErrorCode.CANCELLED,
                    "Realtime connection was cancelled",
                )
            }
            throw XmaxError.from(error)
        }
    }

    suspend fun disconnect(): String? {
        val resources = synchronized(stateLock) {
            ConnectionResources(activeSession, activeRemoteTrack).also {
                activeSession = null
                activeRemoteTrack = null
            }
        }
        sessionService.stopHeartbeat()
        var cleanupError: Throwable? = null
        runCatching { renderController.resetRemoteTrack(resources.remoteTrack) }
            .onFailure {
                cleanupError = it
                logCleanupFailure(
                    "重置远端视频渲染失败 (Failed to Reset Remote Video Rendering)",
                    it,
                )
            }
        runCatching { streamController.disconnect() }
            .onFailure {
                if (cleanupError == null) cleanupError = it
                logCleanupFailure(
                    "断开 RTC 流失败 (Failed to Disconnect RTC Stream)",
                    it,
                )
            }
        resources.session?.id?.let {
            runCatching { sessionService.closeSession(it) }
                .onFailure { error ->
                    if (cleanupError == null) cleanupError = error
                    logCleanupFailure(
                        "关闭实时会话失败 (Failed to Close Realtime Session)",
                        error,
                    )
                }
            cleanupError?.let { error -> throw XmaxError.from(error) }
            return it
        }
        cleanupError?.let { throw XmaxError.from(it) }
        return null
    }

    private suspend fun rollbackConnection() {
        val remoteTrack = synchronized(stateLock) {
            activeRemoteTrack.also {
                activeSession = null
                activeRemoteTrack = null
            }
        }
        sessionService.stopHeartbeat()
        runCatching { renderController.resetRemoteTrack(remoteTrack) }
            .onFailure {
                logCleanupFailure(
                    "重置远端视频渲染失败 (Failed to Reset Remote Video Rendering)",
                    it,
                )
            }
        runCatching { streamController.disconnect() }
            .onFailure {
                logCleanupFailure(
                    "断开 RTC 流失败 (Failed to Disconnect RTC Stream)",
                    it,
                )
            }
    }

    private fun logCleanupFailure(title: String, error: Throwable) {
        XmaxLogger.error(
            { "$title\n└─ 原因：${ErrorMessageFormatter.format(error)}" },
            category = "Realtime",
        )
    }

    private fun ensureCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) {
            throw XmaxError(
                XmaxErrorCode.CANCELLED,
                "Realtime connection was cancelled",
            )
        }
    }

    private data class ConnectionResources(
        val session: RealtimeSession?,
        val remoteTrack: RealtimeVideoTrack?,
    )
}
