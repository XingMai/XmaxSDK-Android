package ai.xmax.sdk.rendering

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.rendering.trajectory.TrajectoryBinding
import ai.xmax.sdk.rendering.trajectory.TrajectoryRegistry
import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
import android.view.View
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** 使用 RTC 原生 VideoCanvas 协调远端视频轨道与 Android View。 */
internal class RenderController(
    private val rtcManager: RtcManaging,
    private val remoteFrameReadyTimeoutMillis: Long = REMOTE_FRAME_READY_TIMEOUT_MILLIS,
) : RenderControlling {
    private val stateLock = Any()
    private var remoteStream: RemoteStream? = null
    private var remoteView: WeakReference<View>? = null
    private var remoteContentMode = VideoContentMode.FILL
    private var isRemoteFrameReady = false
    private val decodedRemoteStreams = mutableSetOf<RemoteStream>()
    private val remoteFrameWaiters = mutableMapOf<UUID, CompletableDeferred<Unit>>()

    init {
        rtcManager.setRemoteVideoFrameReadyListener(::handleRemoteVideoFrameReady)
    }

    override fun setRemoteStream(stream: RemoteStream?) {
        val resources = synchronized(stateLock) {
            val previous = remoteStream
            remoteStream = stream
            isRemoteFrameReady = stream != null && stream in decodedRemoteStreams
            Triple(previous, stream, remoteView?.get() to remoteContentMode)
        }
        if (resources.first != null && resources.first != resources.second) {
            runCatching { rtcManager.unbindRemoteVideo(resources.first!!) }
        }
        val view = resources.third.first
        if (resources.second != null && view != null) {
            rtcManager.bindRemoteVideo(resources.second!!, view, resources.third.second)
        }
        if (stream == null) {
            cancelRemoteFrameWaiters("Remote video stream was reset")
        }
    }

    override fun registerRemoteTrack(
        track: RealtimeVideoTrack,
        interactionListener: (InteractionFrame) -> Unit,
    ) {
        VideoRenderRegistry.register(
            track,
            VideoRenderBinding(
                libraryName = rtcManager.renderLibraryName,
                attachHandler = { view, contentMode -> attachRemoteVideo(view, contentMode) },
                detachHandler = { view -> detachRemoteVideo(view) },
            ),
        )
        track.videoFormat?.let { videoFormat ->
            TrajectoryRegistry.register(
                track,
                TrajectoryBinding(interactionListener, videoFormat),
            )
        }
    }

    override fun updateRemoteVideoFormat(
        videoFormat: RealtimeVideoFormat,
        track: RealtimeVideoTrack,
    ) {
        TrajectoryRegistry.binding(track)?.update(videoFormat)
    }

    override suspend fun waitUntilRemoteFrameReady() {
        val waiterId = UUID.randomUUID()
        val waiter = synchronized(stateLock) {
            if (remoteStream == null) {
                throw XmaxError(
                    code = XmaxErrorCode.RTC_ERROR,
                    message = "Remote video stream is unavailable",
                )
            }
            if (isRemoteFrameReady) return
            CompletableDeferred<Unit>().also {
                remoteFrameWaiters[waiterId] = it
            }
        }
        try {
            withTimeout(remoteFrameReadyTimeoutMillis) {
                waiter.await()
            }
        } catch (error: TimeoutCancellationException) {
            throw XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "Remote video first frame timed out",
                cause = error,
            )
        } finally {
            synchronized(stateLock) {
                remoteFrameWaiters.remove(waiterId)
            }
        }
    }

    override fun resetRemoteTrack(track: RealtimeVideoTrack?) {
        track?.let(VideoRenderRegistry::unregister)
        track?.let(TrajectoryRegistry::unregister)
        val stream = synchronized(stateLock) {
            remoteStream.also {
                remoteStream = null
                remoteView = null
                isRemoteFrameReady = false
                decodedRemoteStreams.clear()
            }
        }
        if (stream != null) {
            runCatching { rtcManager.unbindRemoteVideo(stream) }
        }
        cancelRemoteFrameWaiters("Remote video stream was reset")
    }

    private fun attachRemoteVideo(view: View, contentMode: VideoContentMode) {
        val stream = synchronized(stateLock) {
            remoteView = WeakReference(view)
            remoteContentMode = contentMode
            remoteStream
        }
        if (stream != null) {
            rtcManager.bindRemoteVideo(stream, view, contentMode)
        }
    }

    private fun detachRemoteVideo(view: View) {
        val stream = synchronized(stateLock) {
            if (remoteView?.get() !== view) return
            remoteView = null
            remoteStream
        }
        if (stream != null) {
            rtcManager.unbindRemoteVideo(stream)
        }
    }

    private fun handleRemoteVideoFrameReady(
        stream: RemoteStream,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        val waiters = synchronized(stateLock) {
            decodedRemoteStreams += stream
            if (remoteStream != stream) return
            isRemoteFrameReady = true
            remoteFrameWaiters.values.toList().also { remoteFrameWaiters.clear() }
        }
        waiters.forEach { it.complete(Unit) }
    }

    private fun cancelRemoteFrameWaiters(message: String) {
        val error = XmaxError(XmaxErrorCode.CANCELLED, message)
        val waiters = synchronized(stateLock) {
            remoteFrameWaiters.values.toList().also { remoteFrameWaiters.clear() }
        }
        waiters.forEach { it.completeExceptionally(error) }
    }

    private companion object {
        const val REMOTE_FRAME_READY_TIMEOUT_MILLIS = 10_000L
    }
}
