package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RtcManager
import ai.xmax.sdk.foundation.media.MediaFileMetadataManager
import ai.xmax.sdk.foundation.media.image.ImageManager
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.media.MediaSourceController
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.media.image.ImageController
import ai.xmax.sdk.media.image.ImageSourceController
import ai.xmax.sdk.media.video.VideoController
import ai.xmax.sdk.media.video.VideoPlayerController
import ai.xmax.sdk.rendering.RenderController
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.realtime.RealtimeSessionService
import ai.xmax.sdk.stream.StreamController
import ai.xmax.sdk.stream.StreamControlling
import android.content.Context

import ai.xmax.sdk.rendering.RenderControlling

/** Builds a fresh resource runtime; injected as one factory for deterministic lifecycle tests. */
internal fun createRealtimeComponents(
    context: Context,
    apiService: ApiServicing,
    onError: (XmaxError) -> Unit,
    onMediaError: (XmaxError) -> Unit,
): RealtimeComponents {
    val rtcManager = RtcManager(context)
    val renderController = RenderController(rtcManager)
    val streamController: StreamControlling = StreamController(
        rtcManager = rtcManager,
        errorListener = onError,
        remoteStreamListener = renderController::setRemoteStream,
    )
    val mediaController: MediaControlling = MediaController(
        rtcManager = rtcManager,
        cameraController = CameraController(
            context = context,
            rtcManager = rtcManager,
            errorListener = onMediaError,
        ),
        imageController = ImageController(
            rtcManager = rtcManager,
            imageSourceController = ImageSourceController(
                context = context.applicationContext,
                imageManager = ImageManager(),
                mediaService = MediaService(),
                frameListener = streamController::pushLocalVideoFrame,
                errorListener = onMediaError,
            ),
        ),
        videoController = VideoController(
            rtcManager = rtcManager,
            mediaSourceController = MediaSourceController(
                metadataManager = MediaFileMetadataManager(context),
                mediaService = MediaService(),
                playerController = VideoPlayerController(
                    context = context,
                    videoFrameListener = streamController::pushLocalVideoFrame,
                    audioFrameListener = streamController::pushLocalAudioFrame,
                    errorListener = onMediaError,
                ),
            ),
        ),
        interactionController = InteractionController(
            listener = { taskId, points ->
                streamController.sendTracks(taskId, points)
            },
        ),
    )
    val connectionManager = XmaxRealtimeConnectionManager(
        sessionService = RealtimeSessionService(apiService),
        interactionController = mediaController,
        renderController = renderController,
        streamController = streamController,
    )
    val generationManager = XmaxRealtimeGenerationManager(
        interactionController = mediaController,
        streamController = streamController,
    )

    return RealtimeComponents(mediaController, streamController, renderController, connectionManager, generationManager)
}

internal data class RealtimeComponents(
    val media: MediaControlling,
    val stream: StreamControlling,
    val render: RenderControlling,
    val connection: XmaxRealtimeConnectionManager,
    val generation: XmaxRealtimeGenerationManager,
)
