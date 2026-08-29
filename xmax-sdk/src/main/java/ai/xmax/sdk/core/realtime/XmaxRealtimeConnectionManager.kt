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
            .onFailure { cleanupError = it }
        runCatching { streamController.disconnect() }
            .onFailure { if (cleanupError == null) cleanupError = it }
        resources.session?.id?.let {
            runCatching { sessionService.closeSession(it) }
                .onFailure { error -> if (cleanupError == null) cleanupError = error }
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
        runCatching { streamController.disconnect() }
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
