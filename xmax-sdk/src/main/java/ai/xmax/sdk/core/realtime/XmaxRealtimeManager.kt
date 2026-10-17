//
//  XmaxRealtimeManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.RealtimeCoordinator.OperationKind
import ai.xmax.sdk.RealtimeCoordinator.TerminationScope
import ai.xmax.sdk.media.MediaControlling
import ai.xmax.sdk.render.video.RealtimeVideoFrameDispatcher
import ai.xmax.sdk.service.network.ApiServicing
import ai.xmax.sdk.service.media.MediaService
import androidx.compose.ui.unit.IntSize
import ai.xmax.sdk.stream.quality.QualityController
import ai.xmax.sdk.stream.quality.QualityHistory
import ai.xmax.sdk.stream.quality.QualityVideoFormat
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.withContext

/**
 * 实时公共接口的业务编排层，连接媒体源、服务端会话、生成任务和远端呈现。
 * 生命周期状态及操作所有权由 RealtimeCoordinator 管理，用户通知交给 RealtimeCallbacks。
 * 组件按需创建；每代 Runtime 独立标识错误来源，防止已释放组件的迟到回调影响新连接。
 *
 * @param options 实时生成的模型与业务配置。
 * @param componentFactory 按错误回调及帧派发器创建实时业务组件的工厂。
 * @param callbacks 向接入方派发状态与视频帧的回调组件。
 * @param dispatcher 执行后台操作的协程调度器；默认值为 `Dispatchers.Default`。
 * @param timing 记录本次生成启动耗时的统计对象。
 * @param qualityHistory 按模型及方向保存成功质量档位的存储。
 * @param qualityNow 返回单调时钟毫秒数的函数，供质量策略计算时间间隔。
 */
internal class XmaxRealtimeManager(
    override val options: RealtimeConfiguration,
    private val componentFactory: ((XmaxError) -> Unit, (XmaxError) -> Unit, RealtimeVideoFrameDispatcher) -> RealtimeComponents,
    private val callbacks: RealtimeCallbacks = RealtimeCallbacks(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timing: RealtimeTiming = RealtimeTiming(),
    private val qualityHistory: QualityHistory = QualityHistory(),
    private val qualityNow: () -> Long = { System.nanoTime() / 1_000_000 },
) : XmaxRealtimeManaging {
    /**
     * 创建 XmaxRealtimeManager，并配置其依赖和运行参数。
     *
     * @param options 实时生成的模型与业务配置。
     * @param context 访问 Android 系统服务或媒体资源所需的上下文。
     * @param apiService 发起服务端 API 请求的客户端。
     */
    constructor(options: RealtimeConfiguration, context: Context, apiService: ApiServicing) :
        this(options, { onError, onMediaError, frames ->
            createRealtimeComponents(context, apiService, options.model, onError, onMediaError, frames)
        }, qualityHistory = QualityHistory.create(context, options.model.id))

    /**
     * 自动调档任务及样本有效性标识。
     */
    private val qualityScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val qualityRevision = AtomicLong()

    /**
     * 后台回调也会读取运行时身份；创建与释放由协调器串行执行。
     */
    @Volatile private var runtime: Runtime? = null

    /**
     * 本地预览与远端音频的持久配置。
     *
     * 音量配置在媒体资源释放后仍保留；null 表示使用来源默认值。
     */
    @Volatile private var configuredLocalAudioVolume: Float? = null
    @Volatile private var configuredRemoteAudioVolume: Float? = null

    /**
     * 公共质量与统计监听器的注册和并发保护。
     *
     * 只保护监听配置与已存在组件的绑定；不持锁创建组件、执行媒体操作或调用用户回调。
     */
    private val listenerLock = Any()
    private var networkQualityListener: RealtimeNetworkQualityListener? = null
    private var performanceAlarmListener: RealtimePerformanceAlarmListener? = null
    private var uplinkVideoStatisticsListener: UplinkVideoStatisticsListener? = null
    private var downlinkVideoStatisticsListener: DownlinkVideoStatisticsListener? = null

    /**
     * 实时操作协调器与公开状态快照。
     */
    private val coordinator = RealtimeCoordinator(
        callbacks,
        dispatcher,
        hasLocalMedia = { runtime?.components?.media?.currentTrack != null },
        cleanup = ::cleanup,
    )
    override val currentState: RealtimeState get() = coordinator.currentState

    /**
     * 接入方可读取的当前播放音量。
     */
    override val localAudioVolume: Float get() = configuredLocalAudioVolume ?: 0.45f
    override val remoteAudioVolume: Float get() = configuredRemoteAudioVolume ?: 1f

    /**
     * 替换状态监听器，并异步派发当前快照；通知在主线程执行，传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override suspend fun setStateListener(listener: RealtimeStateListener?) {
        callbacks.setStateListener(listener, currentState)
    }

    /**
     * 设置上行视频统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override suspend fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?) {
        synchronized(listenerLock) {
            uplinkVideoStatisticsListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setUplinkVideoStatisticsListener(listener)
        }
    }

    /**
     * 设置下行结果流统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override suspend fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?) {
        synchronized(listenerLock) {
            downlinkVideoStatisticsListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setDownlinkVideoStatisticsListener(listener)
        }
    }

    /**
     * 接收显示前的最终远端视频帧。
     * 在 SDK 后台串行队列调用；慢消费者会跳过积压帧，只保留最新待回调帧。
     * 帧像素可持有供异步编码；传 null 清除。停止生成、断连或换任务会丢弃旧的待回调帧，
     * 已开始执行的回调允许返回；无需绑定视频视图也能接收帧。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override suspend fun setRemoteVideoFrameListener(listener: RealtimeVideoFrameListener?) {
        callbacks.remoteVideoFrames.setListener(listener)
    }

    /**
     * 设置 RTC 网络质量监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?) {
        synchronized(listenerLock) {
            networkQualityListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setNetworkQualityListener(listener)
        }
    }

    /**
     * 设置 RTC 性能告警监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?) {
        synchronized(listenerLock) {
            performanceAlarmListener = listener
            runtime?.takeIf { it.listenersApplied }?.components?.stream?.setPerformanceAlarmListener(listener)
        }
    }

    /**
     * 设置本地视频预览音量，取值范围为 `0..1`；成功设置后跨 close 和媒体重建保留。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    override suspend fun setLocalAudioVolume(volume: Float) {
        execute(OperationKind.SETTING) { _, c ->
            validateAudioVolume(volume)
            c.media.setLocalAudioVolume(volume)
            configuredLocalAudioVolume = volume
        }
    }

    /**
     * 设置远端生成音频的播放音量，取值范围为 `0..1`；成功设置后跨 close 保留。
     * 创建新的摄像头或图片流时重置为 0，创建视频流时重置为 1。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    override suspend fun setRemoteAudioVolume(volume: Float) {
        execute(OperationKind.SETTING) { _, c ->
            validateAudioVolume(volume)
            c.stream.setRemoteAudioVolume(volume)
            configuredRemoteAudioVolume = (volume * 100f).roundToInt() / 100f
        }
    }

    /**
     * 创建并启动相机输入；接入方须先取得所需权限，输入尺寸会按模型规则调整。
     * 麦克风默认关闭；启用后在连接时开始采集、断开时停止，不进行本地回放。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param position 需要使用的前置或后置摄像头。
     * @param useMicrophone 是否启用麦克风；启用前须由应用取得录音权限。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean,
    ): RealtimeMediaStream = mediaOperation(
        sourceRemoteAudioVolume = 0f,
        waitForCameraPreview = true,
    ) {
        it.createLocalCameraStream(videoFormat, position, useMicrophone)
    }

    /**
     * 解码图片字节并创建持续输出图片帧的本地流，输入规格须保持图片比例。
     * 未指定格式时使用方向校正后的图片尺寸（偶数对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalImageStream(imageData: ByteArray, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(imageData, videoFormat) }

    /**
     * 从 Bitmap 创建本地图片流，输入规格须保持图片比例。
     * 未指定格式时使用 Bitmap 尺寸（偶数对齐）及模型默认帧率，模型生成画布独立选择。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalImageStream(bitmap: Bitmap, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(bitmap, videoFormat) }

    /**
     * 从可读取的 URI 创建本地图片流；读取权限由接入方保证。
     * 输入规格须保持图片比例；未指定时使用方向校正后的图片尺寸（偶数对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalImageStream(uri: Uri, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 0f) { it.createLocalImageStream(uri, videoFormat) }

    /**
     * 从 URI 创建本地视频流；存在音轨时同时提供视频文件的音频输入。
     * 指定 videoFormat 时使用其输入规格，宽高须保持素材比例；未指定时使用旋转校正后的
     * 素材尺寸（向下对齐偶数像素）和模型默认帧率。SDK 单独选择生成画布，服务端等比适配并裁边输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createLocalVideoStream(uri: Uri, videoFormat: RealtimeVideoFormat?): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createLocalVideoStream(uri, videoFormat) }

    /**
     * 创建由服务端直接读取 URL 的网络视频源；仅支持 HTTP/HTTPS。
     * 本地预览静音播放一次，不发布本地音视频；生成首帧就绪后订阅远端音频。
     * [onFinish] 在当前任务收到服务端完成事件且首帧就绪后于主线程调用一次，
     * 不代表远端尾帧已经显示完毕。创建源不会开始生成；资源由 [close] 统一释放。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param sampleMethod 服务端读取网络视频时采用的采样方式。
     * @param onFinish 网络视频任务完成的通知；null 表示不接收完成通知。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod,
        onFinish: (() -> Unit)?,
    ): RealtimeMediaStream = mediaOperation(sourceRemoteAudioVolume = 1f) {
        it.createNetworkVideoStream(uri, videoFormat, sampleMethod, onFinish)
    }

    /**
     * 绑定接入方自行采集、解码或处理的视频源；SDK 不负责摄像头或播放器的启停和释放。
     * 默认仅视频，音频和 SDK 容器内的本地预览由 source 按需提供。
     * 未指定 videoFormat 时使用源视频尺寸和模型默认帧率；最终尺寸按模型规则校验、对齐。
     * 外部源须按 sink.videoFormat 输出帧。
     *
     * @param source 接入方负责启停及释放的外部音视频源。
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat?,
    ): RealtimeMediaStream =
        mediaOperation(sourceRemoteAudioVolume = 1f) { it.createExternalVideoStream(source, videoFormat) }

    /**
     * 解绑外部输入，停止前须断开生成连接；不关闭接入方的摄像头或播放器。
     */
    override suspend fun stopExternalVideoStream() { mediaOperation { it.stopExternalVideoStream() } }

    /**
     * 停任务、切输入、恢复任务由同一个操作持有，避免旧帧或迟到回调接管新视频。
     *
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @param targetSource 创建替换用外部视频源的挂起函数。
     * @return 替换后的本地媒体流及可用的远端结果流。
     */
    override suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat?,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement {
        /**
         * 保留调用方的调度上下文；回调的取消由本次 SDK 切流操作统一管理。
         */
        val preparationContext = currentCoroutineContext().minusKey(Job)

        return execute(OperationKind.REPLACEMENT) { token, c ->
            /**
             * 校验当前输入，并为本次操作保留生成条件，供取消接管或重连后恢复。
             */
            if (!c.media.isExternalVideo) {
                throw invalid("The current local media source is not an external video")
            }

            token.inheritGenerationContext(c.generation.cachedContext, c.generation.cachedFaceRestoration)
            token.setFailureScope(TerminationScope.CONNECTION)

            /**
             * 停止旧生成任务，暂时保留房间连接；是否需要重连由新视频的规格决定。
             */
            c.generation.stop(currentState.taskId.orEmpty())

            token.commit(
                RealtimeState(
                    if (c.connection.currentSessionId.isNotEmpty()) {
                        RealtimeConnectionState.CONNECTED
                    } else {
                        RealtimeConnectionState.READY
                    },
                    sessionId = c.connection.currentSessionId.takeIf(String::isNotEmpty),
                )
            )

            /**
             * 媒体层先解除旧帧输入，再回调 App 切换播放器，最后接入返回的视频源。
             */
            val local = c.media.replaceExternalVideoStream(videoFormat) {
                withContext(preparationContext) {
                    targetSource()
                }.also {
                    token.ensureCurrent()
                }
            }

            token.ensureCurrent()

            /**
             * 音视频规格兼容时复用房间；规格变化时断开旧连接，保留引擎和生成条件。
             */
            val format = checkNotNull(local.videoTrack?.videoFormat)

            if (
                c.connection.currentSessionId.isNotEmpty() &&
                !c.connection.acceptsInput(format, c.media.hasAudio)
            ) {
                c.connection.disconnect()
                token.ensureCurrent()
                token.commit(RealtimeState(RealtimeConnectionState.READY))
            }

            /**
             * 切换前仅预览时，返回新的本地流，不启动生成。
             */
            if (!token.resumeGeneration) {
                return@execute RealtimeStreamReplacement(local, null)
            }

            /**
             * 恢复已有或正在启动的生成请求，沿用参考图和提示词创建新任务。
             */
            val remote = c.connection.currentRemoteStream
                ?: connect(token, c, local, resetGeneration = false)

            timing.measure {
                start(token, c, token.requestedContext, token.requestedFaceRestoration ?: c.generation.cachedFaceRestoration)
            }

            RealtimeStreamReplacement(local, remote)
        }
    }

    /**
     * 停止当前相机输入并释放其资源；当前源不是相机时不执行释放。
     */
    override suspend fun stopLocalCameraStream() { mediaOperation { it.stopLocalCameraStream() } }

    /**
     * 停止当前图片流并释放其资源；当前源不是图片时不执行释放。
     */
    override suspend fun stopLocalImageStream() { mediaOperation { it.stopLocalImageStream() } }

    /**
     * 停止当前视频流并释放解码与预览资源；当前源不是视频时不执行释放。
     */
    override suspend fun stopLocalVideoStream() { mediaOperation { it.stopLocalVideoStream() } }

    /**
     * 本地媒体变更要求已断连；准备成功或开始停止资源后，故障按 ALL 范围清理。
     *
     * @param sourceRemoteAudioVolume 新媒体源默认使用的远端音量；null 表示保留当前配置。
     * @param waitForCameraPreview 是否等待相机预览就绪后再继续；默认值为 `false`。
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    private suspend fun <T> mediaOperation(
        sourceRemoteAudioVolume: Float? = null,
        waitForCameraPreview: Boolean = false,
        action: suspend (MediaControlling) -> T,
    ): T =
        execute(OperationKind.MEDIA) { token, c ->
            requireDisconnected(c)
            if (sourceRemoteAudioVolume != null) {
                token.commit(RealtimeState(RealtimeConnectionState.PREPARING))
            } else {
                token.setFailureScope(TerminationScope.ALL)
            }
            val result = action(c.media)
            token.ensureCurrent()
            token.setFailureScope(TerminationScope.ALL)
            sourceRemoteAudioVolume?.let { volume ->
                c.stream.setRemoteAudioVolume(volume)
                configuredRemoteAudioVolume = volume
            }
            if (!waitForCameraPreview) {
                token.commit(
                    RealtimeState(
                        if (c.media.currentTrack == null) {
                            RealtimeConnectionState.IDLE
                        } else {
                            RealtimeConnectionState.READY
                        },
                    ),
                )
            }
            result
        }

    /**
     * 生成中切换时保留连接，结束旧任务后等待相机稳定，再以缓存条件创建新任务。
     *
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun switchCamera(): RealtimeMediaStream =
        execute(OperationKind.SWITCH) { token, c ->
            val wasGenerating = currentState.connectionState == RealtimeConnectionState.GENERATING
            if (wasGenerating) {
                token.setFailureScope(TerminationScope.CONNECTION)
                c.generation.stop(currentState.taskId.orEmpty())
                token.commit(currentState.copy(connectionState = RealtimeConnectionState.CONNECTED, taskId = null))
            }
            val stream = c.media.switchCamera()
            token.ensureCurrent()
            if (wasGenerating) {
                delay(500L)
                timing.measure { start(token, c, null, c.generation.cachedFaceRestoration) }
            }
            stream
        }

    /**
     * 使用本管理器创建且仍活动的本地流建立连接，返回供渲染绑定的远端流。
     *
     * @param localStream 本地输入媒体流。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream =
        execute(OperationKind.CONNECTION) { token, c -> connect(token, c, localStream) }

    /**
     * 验证本地流归属并建立会话；仅当前操作可提交 CONNECTED，连接完成不代表生成已开始。
     *
     * @param token 保证当前操作仍有效的生命周期凭证。
     * @param c 当前操作使用的实时组件集合。
     * @param localStream 本地输入媒体流。
     * @param resetGeneration 是否同时清空当前生成上下文；默认值为 `true`。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    private suspend fun connect(
        token: RealtimeCoordinator.Token,
        c: RealtimeComponents,
        localStream: RealtimeMediaStream,
        resetGeneration: Boolean = true,
    ): RealtimeMediaStream {
        requireDisconnected(c)
        val videoFormat = localStream.videoTrack?.videoFormat
        if (videoFormat == null || !c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")
        token.setFailureScope(TerminationScope.CONNECTION)
        token.commit(RealtimeState(RealtimeConnectionState.CONNECTING))
        try {
            if (resetGeneration) c.generation.reset()
            prepareUplinkQuality(c, videoFormat)
            c.media.startMicrophoneCapture()
            token.ensureCurrent()
            val owner = runtime
            val remote = c.connection.connect(options.model, videoFormat, c.media.hasAudio,
                publishLocalMedia = c.media.networkVideoReference == null,
                isCurrent = { try { token.ensureCurrent(); true } catch (_: CancellationException) { false } },
                onHeartbeatFailure = { sessionId, error ->
                    /**
                     * 同时校验组件代际与会话，避免旧心跳结束一个后续建立的连接。
                     */
                    if (runtime === owner && c.connection.currentSessionId == sessionId) {
                        coordinator.reportFailure(error, TerminationScope.CONNECTION)
                    }
                },
            )
            currentCoroutineContext().ensureActive()
            token.commit(RealtimeState(RealtimeConnectionState.CONNECTED, sessionId = c.connection.currentSessionId))
            return remote
        } catch (error: Throwable) {
            cleanupAfterFailure(
                error,
                { c.connection.disconnect() },
                { c.media.stopMicrophoneCapture() },
            )
            throw error
        }
    }

    /**
     * 在已建立的连接上生成；首次生成须提供条件，后续传 null 可复用已缓存条件。
     * 已在生成时更新现有任务；新任务等待远端确认和首帧就绪后才进入 GENERATING。
     * 两个重载共用替换顺序；新调用替换未完成的旧调用，旧调用以 CancellationException 结束，
     * 不作为业务失败通知。disconnect/close 会取消当时所有待执行生成，不会在清理后自动恢复。
     *
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     */
    override suspend fun startGeneration(context: RealtimeContext?, faceRestoration: Boolean) {
        execute(OperationKind.GENERATION, context, faceRestoration) { token, c ->
            measureStartup { start(token, c, context, faceRestoration) }
        }
    }

    /**
     * 必要时先建立连接，再开始或更新生成；返回该连接的远端流。并发替换语义与另一重载一致。
     *
     * @param localStream 本地输入媒体流。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    override suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ): RealtimeMediaStream =
        execute(OperationKind.GENERATION, context, faceRestoration) { token, c ->
            measureStartup {
                if (!c.media.owns(localStream)) throw invalid("The local stream must be created and started by this realtime manager")
                val remote = if (c.connection.currentSessionId.isNotEmpty()) {
                    c.connection.currentRemoteStream ?: throw XmaxError(XmaxErrorCode.RTC_ERROR, "Realtime connection has no remote stream")
                } else {
                    try {
                        /**
                         * 文件视频继续推送媒体帧，但从用户开始生成起就停止本地出声。
                         */
                        c.media.setLocalAudioPreviewMuted(true)
                        connect(token, c, localStream)
                    } catch (error: Throwable) {
                        cleanupAfterFailure(error, { c.media.setLocalAudioPreviewMuted(false) })
                        throw error
                    }
                }
                start(token, c, context, faceRestoration)
                remote
            }
        }

    /**
     * 记录从启动调用到生成首帧就绪的耗时。
     *
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    private suspend fun <T> measureStartup(action: suspend () -> T): T =
        if (currentState.connectionState == RealtimeConnectionState.GENERATING) action() else timing.measure(action)

    /**
     * 为当前连接启动或更新生成，并在新任务首帧可显示后更新生成状态。
     *
     * @param token 保证当前操作仍有效的生命周期凭证。
     * @param c 当前操作使用的实时组件集合。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`。
     */
    private suspend fun start(
        token: RealtimeCoordinator.Token,
        c: RealtimeComponents,
        context: RealtimeContext?,
        faceRestoration: Boolean,
    ) {
        val current = currentState
        val format = c.media.currentVideoFormat
        if (c.connection.currentSessionId.isEmpty() || format == null ||
            current.connectionState !in setOf(RealtimeConnectionState.CONNECTED, RealtimeConnectionState.GENERATING)) {
            throw invalid("Realtime connection is not open")
        }
        val resolvedContext = (context ?: c.generation.cachedContext)?.let { supplied ->
            c.media.networkVideoReference?.let { supplied.copy(referenceVideo = it) } ?: supplied
        }
        val paddingContain = c.media.isLocalVideo || c.media.isLocalImage
        if (paddingContain && resolvedContext?.referenceVideo != null) {
            throw invalid("Local image or video frame input cannot be combined with a server video URL")
        }

        val generationFormat = if (paddingContain) {
            val size = MediaService(options.model).resolveGenerationSize(IntSize(format.width, format.height))
            format.copy(width = size.width, height = size.height)
        } else format
        token.commit(current)
        if (current.connectionState == RealtimeConnectionState.GENERATING && current.taskId != null) {
            c.generation.update(
                taskId = current.taskId,
                videoFormat = format,
                context = resolvedContext,
                faceRestoration = faceRestoration,
                generationVideoFormat = generationFormat,
                paddingContain = paddingContain,
                ensureCurrent = token::ensureCurrent,
            )
            return
        }
        c.generation.validateContext(resolvedContext)
        prepareUplinkQuality(c, format)
        val owner = checkNotNull(runtime)
        val downlink = QualityController.Direction(format, qualityHistory.read(downlink = true), downlink = true)
        owner.downlinkQuality = downlink
        token.setFailureScope(TerminationScope.CONNECTION)
        var taskId = ""
        try {
            c.media.setLocalAudioPreviewMuted(true)
            taskId = c.generation.start(
                videoFormat = format,
                context = resolvedContext,
                faceRestoration = faceRestoration,
                targetVideoFormat = downlink.format,
                generationVideoFormat = generationFormat,
                paddingContain = paddingContain,
                ensureCurrent = token::ensureCurrent,
            )
            c.render.waitUntilRemoteFrameReady()
            currentCoroutineContext().ensureActive()
            token.ensureCurrent()
            c.stream.activateRemoteAudio()
            token.commit(current.copy(connectionState = RealtimeConnectionState.GENERATING, taskId = taskId))
            owner.qualityTaskId = taskId
            qualityHistory.write(downlink = true, level = downlink.level)
            c.stream.activateNetworkVideoCompletion(c.media.networkVideoFinishHandler?.let { onFinish ->
                {
                    val snapshot = currentState
                    if (runtime?.components === c && snapshot.taskId == taskId &&
                        snapshot.connectionState == RealtimeConnectionState.GENERATING
                    ) onFinish()
                }
            })
            currentCoroutineContext()[RealtimeTiming.Attempt]?.finish(taskId)
        } catch (error: Throwable) {
            cleanupAfterFailure(error,
                { c.generation.stop(taskId) },
                { c.media.setLocalAudioPreviewMuted(false) },
            )
            throw error
        }
    }

    /**
     * 结束生成并关闭会话和 RTC 连接，保留本地媒体源及监听器。
     */
    override suspend fun disconnect() {
        invalidateQualitySamples()
        coordinator.disconnect()
    }

    /**
     * 结束生成并关闭会话和 RTC 连接，保留本地媒体源及监听器。
     *
     * @param reason 引起状态变化或失败的原因。
     */
    override suspend fun disconnect(reason: RealtimeReason) {
        invalidateQualitySamples()
        coordinator.disconnect(reason)
    }

    /**
     * 取消进行中的操作并等待全部实时资源清理；保留监听器，之后可重新创建本地流复用管理器。
     */
    override suspend fun close() {
        invalidateQualitySamples()
        coordinator.terminate(TerminationScope.ALL)
    }

    /**
     * 按任务、连接或全部媒体的范围清理资源；独立清理步骤均会执行。
     *
     * @param target 需要终止的资源范围。
     */
    private suspend fun cleanup(target: TerminationScope) {
        val owner = runtime ?: return
        owner.qualityTaskId = null
        owner.downlinkQuality = null
        owner.uplinkQuality?.resetSamples()
        val c = owner.components
        cleanupResources(
            { if (target == TerminationScope.ALL) {
                synchronized(listenerLock) {
                    /**
                     * 先解除旧组件绑定；清理期间的新注册仅保留配置，供下一代 Runtime 使用。
                     */
                    owner.listenersApplied = false
                    c.stream.setAdaptiveQualityListener(null)
                    c.stream.setNetworkQualityListener(null)
                    c.stream.setPerformanceAlarmListener(null)
                    c.stream.setUplinkVideoStatisticsListener(null)
                    c.stream.setDownlinkVideoStatisticsListener(null)
                }
            } },
            { if (target >= TerminationScope.CONNECTION) c.generation.reset(currentState.taskId.orEmpty()) else c.generation.stop(currentState.taskId.orEmpty()) },
            { if (target >= TerminationScope.CONNECTION) c.media.stopMicrophoneCapture() },
            { if (target >= TerminationScope.CONNECTION) c.connection.disconnect() },
            { c.media.setLocalAudioPreviewMuted(false) },
            { if (target == TerminationScope.ALL) {
                cleanupResources(
                    { c.media.stopLocalStream() },
                    { c.media.setCameraPreviewReadyListener(null) },
                )
            } },
            { if (target == TerminationScope.ALL) synchronized(listenerLock) { runtime = null } },
        )
    }

    /**
     * 统一操作准入与错误归一化；故障是否清理由操作凭证中的范围决定。
     *
     * @param kind 本次操作或媒体源的类型。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param action 需要执行的操作。
     * @return 传入操作成功执行后的结果。
     */
    private suspend fun <T> execute(
        kind: OperationKind,
        context: RealtimeContext? = null,
        faceRestoration: Boolean = false,
        action: suspend (RealtimeCoordinator.Token, RealtimeComponents) -> T,
    ): T {
        invalidateQualitySamples()
        return coordinator.run(kind, failureScope = null, context = context, faceRestoration = faceRestoration) { token ->
            try {
                action(token, components())
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                token.ensureCurrent()
                val resolved = XmaxError.from(error)
                XmaxLogger.realtime.warn(
                    message = {
                        "Realtime ${kind.name.lowercase()} failed: " +
                            ErrorMessageFormatter.format(resolved)
                    },
                )
                throw resolved
            }
        }
    }

    /**
     * 清空前台操作之前的质量样本，阻止旧样本触发后续调档。
     */
    private fun invalidateQualitySamples() {
        qualityRevision.incrementAndGet()
        runtime?.uplinkQuality?.resetSamples()
        runtime?.downlinkQuality?.resetSamples()
    }

    /**
     * 取得当前实时组件；尚未创建时按配置装配组件并恢复监听器与音量设置。
     *
     * @return 关联同一个 RTC 管理器的媒体、流、渲染和业务组件。
     */
    private suspend fun components(): RealtimeComponents {
        val owner = runtime ?: Runtime().also { created ->
            created.components = componentFactory(
                { error -> forwardFailure(created, error, TerminationScope.CONNECTION) },
                { error -> forwardFailure(created, error, TerminationScope.ALL) },
                callbacks.remoteVideoFrames,
            )
            runtime = created
        }
        if (!owner.audioSettingsApplied) {
            /**
             * 在创建、启动媒体之前恢复设置；失败或取消时保持未完成，后续操作重新尝试。
             */
            configuredLocalAudioVolume?.let { owner.components.media.setLocalAudioVolume(it) }
            configuredRemoteAudioVolume?.let { owner.components.stream.setRemoteAudioVolume(it) }
            owner.audioSettingsApplied = true
        }
        if (!owner.listenersApplied) {
            owner.components.stream.setAdaptiveQualityListener { handleAdaptiveQuality(owner, it) }
            owner.components.media.setCameraPreviewReadyListener {
                if (runtime === owner) {
                    coordinator.localPreviewDidBecomeReady()
                }
            }
            synchronized(listenerLock) {
                owner.components.stream.setNetworkQualityListener(networkQualityListener)
                owner.components.stream.setPerformanceAlarmListener(performanceAlarmListener)
                owner.components.stream.setUplinkVideoStatisticsListener(uplinkVideoStatisticsListener)
                owner.components.stream.setDownlinkVideoStatisticsListener(downlinkVideoStatisticsListener)
                owner.listenersApplied = true
            }
        }
        return owner.components
    }

    /**
     * 丢弃旧运行时故障；故障来源决定清理范围，取消事件仅保留诊断日志。
     *
     * @param owner 创建本次操作的实时运行状态。
     * @param error 本次操作失败的异常或 SDK 错误。
     * @param target 需要终止的资源范围。
     */
    private fun forwardFailure(owner: Runtime, error: XmaxError, target: TerminationScope) {
        if (runtime !== owner) return
        if (error.code != XmaxErrorCode.CANCELLED) {
            coordinator.reportFailure(error, target)
        } else {
            XmaxLogger.realtime.warn(
                message = { "Realtime diagnostic: ${ErrorMessageFormatter.format(error)}" },
            )
        }
    }

    /**
     * 确认管理器已断开生成连接，否则拒绝更换本地媒体源。
     *
     * @param c 当前操作使用的实时组件集合。
     */
    private fun requireDisconnected(c: RealtimeComponents) {
        if (c.connection.currentSessionId.isNotEmpty() || currentState.connectionState in setOf(
                RealtimeConnectionState.CONNECTING, RealtimeConnectionState.CONNECTED,
                RealtimeConnectionState.GENERATING, RealtimeConnectionState.DISCONNECTING,
            )) throw invalid("Disconnect realtime before changing the local stream or opening another connection")
    }

    /**
     * 校验播放音量是否位于允许的范围内，无效时抛出配置错误。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    private fun validateAudioVolume(volume: Float) {
        if (!volume.isFinite() || volume !in 0f..1f) throw invalid("Audio volume must be between 0 and 1")
    }

    /**
     * 创建用于报告配置或调用状态无效的错误。
     *
     * @param message 待发送或记录的消息文本。
     * @return 配置无效的 SDK 错误。
     */
    private fun invalid(message: String) = XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, message)

    /**
     * 按当前媒体原始规格恢复上行档位，采集和生成尺寸保持不变。
     *
     * @param c 当前操作使用的实时组件集合。
     * @param baseline 用于计算各质量档位的原始输入规格。
     */
    private fun prepareUplinkQuality(c: RealtimeComponents, baseline: RealtimeVideoFormat) {
        val owner = checkNotNull(runtime)
        if (c.media.networkVideoReference != null) {
            owner.uplinkQuality = null
            return
        }

        val track = c.media.currentTrack
        val previous = owner.uplinkQuality
        val level = qualityHistory.read(downlink = false)
        val direction = if (owner.qualityTrack === track && previous?.baseline == baseline && previous.level == level) previous else {
            QualityController.Direction(baseline, level, downlink = false)
        }
        c.stream.setVideoEncoderConfig(direction.format)
        owner.qualityTrack = track
        owner.uplinkQuality = direction
        direction.resetSamples()
        qualityHistory.write(downlink = false, level = direction.level)
    }

    /**
     * 使用新到达的网络质量样本独立判断上下行，调档通过协调器串行应用。
     *
     * @param owner 创建本次操作的实时运行状态。
     * @param quality 当前网络质量样本。
     */
    private fun handleAdaptiveQuality(owner: Runtime, quality: RealtimeNetworkQuality) {
        if (runtime !== owner) return

        val revision = qualityRevision.get()
        val taskId = owner.qualityTaskId
        val up = owner.uplinkQuality
        val down = owner.downlinkQuality
        val state = currentState
        if (taskId == null || state.taskId != taskId || state.connectionState != RealtimeConnectionState.GENERATING || coordinator.isBusy) {
            up?.resetSamples()
            down?.resetSamples()
            return
        }

        if (!owner.qualityPending.compareAndSet(false, true)) {
            up?.resetSamples()
            down?.resetSamples()
            return
        }

        val sampleAt = qualityNow()
        qualityScope.launch {
            try {
                coordinator.runAdaptiveConfiguration { token ->

                    /**
                     * 操作被替换或终止后按协程取消退出，避免将过期结果视为业务失败。
                     */
                    fun ensureCurrent() {
                        token.ensureCurrent()
                        val current = currentState
                        val ownsRuntime = runtime === owner && owner.uplinkQuality === up && owner.downlinkQuality === down
                        val ownsTask = owner.qualityTaskId == taskId && current.taskId == taskId
                        if (!ownsRuntime || !ownsTask || qualityRevision.get() != revision || current.connectionState != RealtimeConnectionState.GENERATING) {
                            throw CancellationException("Adaptive quality belongs to an inactive generation")
                        }
                    }
                    for ((direction, sample) in listOf(up to quality.uplink, down to quality.downlink)) {
                        ensureCurrent()
                        if (direction == null) continue
                        val change = direction.observe(sample, sampleAt, qualityNow()) ?: continue
                        val target = QualityVideoFormat.resolve(direction.baseline, change.to, direction.downlink)
                        try {
                            if (direction.downlink) {
                                owner.components.stream.updateDownlinkVideoResolution(taskId, target, ::ensureCurrent)
                            } else {
                                owner.components.stream.setVideoEncoderConfig(target)
                            }
                            ensureCurrent()
                            direction.complete(change, true, qualityNow())
                            qualityHistory.write(direction.downlink, change.to)
                            XmaxLogger.realtime.debug(message = {
                                val frameRate = if (direction.downlink) "" else ", ${target.fps} fps"
                                "Adaptive ${if (direction.downlink) "downlink target" else "uplink"}: L${change.from} -> L${change.to}, " +
                                    "${target.width}x${target.height}$frameRate, ${change.reason}"
                            })
                        } catch (cancelled: CancellationException) {
                            direction.resetSamples()
                            throw cancelled
                        } catch (error: Throwable) {
                            ensureCurrent()
                            direction.complete(change, false, qualityNow())
                            XmaxLogger.realtime.warn(message = { "Adaptive quality failed: ${ErrorMessageFormatter.format(error)}" })
                        }
                    }
                }.also { applied ->
                    if (!applied) {
                        up?.resetSamples()
                        down?.resetSamples()
                    }
                }
            } catch (cancelled: CancellationException) {
                up?.resetSamples()
                down?.resetSamples()
            } catch (error: Throwable) {
                up?.resetSamples()
                down?.resetSamples()
                XmaxLogger.realtime.warn(message = { "Adaptive quality failed: ${ErrorMessageFormatter.format(error)}" })
            } finally {
                owner.qualityPending.set(false)
            }
        }
    }

    /**
     * 返回当前生成方向的传输档位状态。
     *
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @return 指定方向的当前调档状态；未开始对应传输时为 null。
     */
    internal fun qualityDebugDirection(downlink: Boolean): QualityController.Direction? {
        val owner = runtime ?: return null
        val state = currentState
        if (state.connectionState != RealtimeConnectionState.GENERATING || state.taskId != owner.qualityTaskId) return null

        return if (downlink) owner.downlinkQuality else owner.uplinkQuality
    }

    /**
     * 切换传输档位并保存历史，自动调档从新档位继续运行。
     *
     * @param downlink true 操作下行档位，false 操作上行档位。
     * @param level 需要应用或保存的质量档位，1 为最高档。
     * @return 档位已成功应用时为 true；忙碌或无活动生成时为 false。
     */
    internal suspend fun selectQualityDebugLevel(downlink: Boolean, level: Int): Boolean {
        require(level in 1..5)
        invalidateQualitySamples()
        val owner = runtime ?: return false
        val taskId = owner.qualityTaskId ?: return false
        val direction = qualityDebugDirection(downlink) ?: return false
        return coordinator.runAdaptiveConfiguration { token ->

            /**
             * 操作被替换或终止后按协程取消退出，避免将过期结果视为业务失败。
             */
            fun ensureCurrent() {
                token.ensureCurrent()
                val state = currentState
                if (runtime !== owner || owner.qualityTaskId != taskId || state.taskId != taskId || state.connectionState != RealtimeConnectionState.GENERATING) {
                    throw CancellationException("Quality debug selection belongs to an inactive generation")
                }
            }
            ensureCurrent()
            val selectedLevel = QualityVideoFormat.canonicalLevel(direction.baseline, level, downlink)
            val target = QualityVideoFormat.resolve(direction.baseline, selectedLevel, downlink)
            if (downlink) {
                owner.components.stream.updateDownlinkVideoResolution(taskId, target, ::ensureCurrent)
            } else {
                owner.components.stream.setVideoEncoderConfig(target)
            }
            ensureCurrent()
            direction.complete(QualityController.Direction.Change(direction.level, selectedLevel, "selection"), true, qualityNow())
            qualityHistory.write(downlink, selectedLevel)
            XmaxLogger.realtime.debug(message = {
                "Debug ${if (downlink) "downlink target" else "uplink"}: L$selectedLevel, " +
                    "${target.width}x${target.height}"
            })
        }
    }

    /**
     * 保存一个实时管理器当前使用的媒体、连接和质量调节状态。
     */
    private class Runtime {
        /**
         * 当前使用的组件及配置恢复状态。
         */
        lateinit var components: RealtimeComponents
        var audioSettingsApplied = false
        var listenersApplied = false

        /**
         * 当前生成的上下行自动调档状态。
         */
        @Volatile var qualityTaskId: String? = null
        @Volatile var uplinkQuality: QualityController.Direction? = null
        @Volatile var downlinkQuality: QualityController.Direction? = null
        var qualityTrack: RealtimeVideoTrack? = null
        val qualityPending = AtomicBoolean(false)
    }

}
