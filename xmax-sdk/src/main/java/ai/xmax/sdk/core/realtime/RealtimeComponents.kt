//
//  RealtimeComponents.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.foundation.rtc.RtcManager
import ai.xmax.sdk.foundation.media.MediaFileMetadataManager
import ai.xmax.sdk.foundation.media.image.ImageManager
import ai.xmax.sdk.media.external.ExternalVideoController
import ai.xmax.sdk.media.MediaController
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.media.MediaSourceController
import ai.xmax.sdk.media.camera.CameraController
import ai.xmax.sdk.media.interaction.InteractionController
import ai.xmax.sdk.media.image.ImageController
import ai.xmax.sdk.media.image.ImageSourceController
import ai.xmax.sdk.media.video.NetworkVideoController
import ai.xmax.sdk.media.video.VideoController
import ai.xmax.sdk.media.video.VideoPlayerController
import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import ai.xmax.sdk.render.RenderController
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import ai.xmax.sdk.service.realtime.RealtimeSessionService
import ai.xmax.sdk.stream.StreamController
import ai.xmax.sdk.stream.StreamControlling
import android.content.Context

import ai.xmax.sdk.render.RenderControlling

/**
 * 装配单个实时管理器的媒体、传输、渲染和业务组件，所有组件共享同一个 RTC 管理器。
 * 媒体帧交给 Stream，远端流交给 Render，交互轨迹通过 Stream 发送。
 * 故障通过注入回调返回 Core，由协调器决定清理范围及用户通知；创建结果具有独立的媒体和连接生命周期。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 * @param apiService 发起服务端 API 请求的客户端。
 * @param model 用于确定服务端能力、地址及输入规格的模型。
 * @param onError 接收连接或运行错误的回调。
 * @param onMediaError 接收本地媒体故障的回调。
 * @param frameDispatcher 远端视频帧的派发组件。
 * @return 关联同一个 RTC 管理器的媒体、流、渲染和业务组件。
 */
internal fun createRealtimeComponents(
    context: Context,
    apiService: ApiServicing,
    model: RealtimeModel,
    onError: (XmaxError) -> Unit,
    onMediaError: (XmaxError) -> Unit,
    frameDispatcher: RealtimeVideoFrameDispatcher,
): RealtimeComponents {
    val rtcManager = RtcManager(context)
    val mediaService = MediaService(model)
    val renderController = RenderController(rtcManager, frameDispatcher = frameDispatcher, errorListener = onError)
    val streamController = StreamController(
        rtcManager = rtcManager,
        errorListener = onError,
        remoteStreamListener = renderController::setRemoteStream,
    )
    val mediaController: MediaControlling = MediaController(
        rtcManager = rtcManager,
        cameraController = CameraController(
            context = context,
            rtcManager = rtcManager,
            mediaService = mediaService,
            errorListener = onMediaError,
        ),
        imageController = ImageController(
            rtcManager = rtcManager,
            imageSourceController = ImageSourceController(
                context = context.applicationContext,
                imageManager = ImageManager(),
                mediaService = mediaService,
                frameListener = streamController::pushLocalVideoFrame,
                errorListener = onMediaError,
            ),
        ),
        videoController = VideoController(
            rtcManager = rtcManager,
            mediaSourceController = MediaSourceController(
                metadataManager = MediaFileMetadataManager(context),
                mediaService = mediaService,
                playerController = VideoPlayerController(
                    context = context,
                    videoFrameListener = streamController::pushLocalVideoFrame,
                    audioFrameListener = streamController::pushLocalAudioFrame,
                    errorListener = onMediaError,
                ),
            ),
        ),
        externalController = ExternalVideoController(
            rtc = rtcManager,
            mediaService = mediaService,
            requestingFrames = { streamController.isRequestingExternalFrames },
            videoListener = streamController::pushLocalVideoFrame,
            audioListener = streamController::pushLocalAudioFrame,
            errorListener = onMediaError,
        ),
        networkVideoController = NetworkVideoController(
            context.applicationContext, mediaService,
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

/**
 * 一代运行时的组件集合；close 释放后，下次操作通过工厂重新创建整套组件。
 *
 * @param media 本地媒体生命周期控制器。
 * @param stream 执行媒体发布、订阅及业务信令的控制器。
 * @param render 视频显示与轨迹绑定控制器。
 * @param connection 负责服务端会话和 RTC 连接生命周期的组件。
 * @param generation 负责生成上下文和任务生命周期的组件。
 */
internal data class RealtimeComponents(
    val media: MediaControlling,
    val stream: StreamControlling,
    val render: RenderControlling,
    val connection: XmaxRealtimeConnectionManager,
    val generation: XmaxRealtimeGenerationManager,
)
