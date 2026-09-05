package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.media.interaction.InteractionControlling
import ai.xmax.sdk.rendering.RenderControlling
import ai.xmax.sdk.service.realtime.RealtimeSession
import ai.xmax.sdk.service.realtime.RealtimeSessionServicing
import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.stream.StreamID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** 协调服务端会话、RTC 房间、本地发布和远端媒体资源。 */
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
    ): RealtimeMediaStream = operationMutex.withLock {
        if (currentSessionId.isNotEmpty()) throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Realtime connection is already open")
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
            return@withLock RealtimeMediaStream(StreamID.REMOTE.value, remoteTrack)
        } catch (error: Throwable) {
            // This gate is held through rollback; a later connect cannot own these resources yet.
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

    private fun ensureCurrent(isCurrent: () -> Boolean) {
        if (!isCurrent()) {
            throw CancellationException("Realtime connection was cancelled")
        }
    }

    private data class ConnectionResources(
        val session: RealtimeSession?,
        val remoteTrack: RealtimeVideoTrack?,
    )
}
