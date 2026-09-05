package ai.xmax.sdk.rendering

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeVideoTrack
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxVideoView
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.media.interaction.InteractionFrame
import ai.xmax.sdk.rendering.trajectory.TrajectoryBinding
import ai.xmax.sdk.rendering.trajectory.TrajectoryRegistry
import ai.xmax.sdk.rendering.video.VideoRenderBinding
import ai.xmax.sdk.rendering.video.VideoRenderRegistry
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

/** 每轮生成先等待后处理后的新帧，再交给 RTC 原生 VideoCanvas 渲染。 */
internal class RenderController(
    private val rtcManager: RtcManaging,
    private val remoteFrameReadyTimeoutMillis: Long = REMOTE_FRAME_READY_TIMEOUT_MILLIS,
    private val renderDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : RenderControlling {
    // Serialize registration, canvas binding and reset; never hold this lock while awaiting a frame.
    private val operationLock = ReentrantLock()
    private var generation: RemoteGeneration? = null
    private var remoteView: WeakReference<XmaxVideoView>? = null
    private var remoteContentMode = VideoContentMode.FILL

    @MainThread
    override fun setRemoteStream(stream: RemoteStream?) = operationLock.withLock {
        val previous = generation
        val next = stream?.let(::RemoteGeneration)
        generation = next
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
                // A matching SEI selects the stream. Even the same stream needs a new
                // registration: an earlier generation's decoded-frame flag is insufficient.
                rtcManager.setRemoteVideoFrameListener(next.stream) { width, height ->
                    handleRemoteVideoFrame(next, width, height)
                }
            }
        } catch (error: Throwable) {
            next?.ready?.completeExceptionally(error)
            throw error
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
                attachHandler = ::attachRemoteVideo,
                detachHandler = ::detachRemoteVideo,
            ),
        )
        track.videoFormat?.let { videoFormat ->
            TrajectoryRegistry.register(track, TrajectoryBinding(interactionListener, videoFormat))
        }
    }

    override fun updateRemoteVideoFormat(videoFormat: RealtimeVideoFormat, track: RealtimeVideoTrack) {
        TrajectoryRegistry.binding(track)?.update(videoFormat)
    }

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

    override suspend fun resetRemoteTrack(track: RealtimeVideoTrack?) = withContext(NonCancellable + renderDispatcher) {
        // Dispatch before acquiring any lock: the UI may be attaching/detaching a view.
        operationLock.withLock {
            track?.let(VideoRenderRegistry::unregister)
            track?.let(TrajectoryRegistry::unregister)
            try {
                setRemoteStream(null)
            } finally {
                remoteView = null
                remoteContentMode = VideoContentMode.FILL
            }
        }
    }

    private fun attachRemoteVideo(view: XmaxVideoView, contentMode: VideoContentMode): Unit = operationLock.withLock {
        remoteView = WeakReference(view)
        remoteContentMode = contentMode
        view.invalidateVideoPresentation()
        generation?.takeIf { it.isReady }?.let { current ->
            bindRemoteView(current, view)
        }
    }

    private fun detachRemoteVideo(view: XmaxVideoView): Unit = operationLock.withLock {
        if (remoteView?.get() !== view) return
        view.invalidateVideoPresentation()
        remoteView = null
        generation?.takeIf { it.isReady }?.let { rtcManager.unbindRemoteVideo(it.stream) }
    }

    private fun handleRemoteVideoFrame(current: RemoteGeneration, width: Int, height: Int): Unit = operationLock.withLock {
        if (generation !== current || current.ready.isCompleted || width <= 0 || height <= 0) return
        try {
            // The temporary sink is only a startup gate. Release it before resuming
            // native rendering so it cannot replace the application's VideoCanvas.
            rtcManager.setRemoteVideoFrameListener(current.stream, null)
            remoteView?.get()?.let { view ->
                bindRemoteView(current, view)
            }
            current.isReady = true
            current.ready.complete(Unit)
        } catch (error: Throwable) {
            // Deliver native setup failures through the waiting start call and its fatal
            // error path, not as uncaught exceptions on the RTC callback dispatcher.
            current.ready.completeExceptionally(error)
        }
        Unit
    }

    private fun bindRemoteView(current: RemoteGeneration, view: XmaxVideoView) {
        // A track can outlive several generations. Never reuse its stopped surface or
        // first-presentation callback when the application keeps the same track assigned.
        view.prepareRtcVideoRendering()
        rtcManager.bindRemoteVideo(current.stream, view.rtcRenderView, remoteContentMode)
    }

    private class RemoteGeneration(val stream: RemoteStream) {
        val ready = CompletableDeferred<Unit>()
        var isReady = false
    }

    private companion object {
        const val REMOTE_FRAME_READY_TIMEOUT_MILLIS = 10_000L
    }
}
