//
//  RtcManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxLogger
import android.content.Context
import android.view.View
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import ai.xmax.sdk.cleanupResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ai.xmax.sdk.cleanupAfterFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * 提供基于声网引擎的 RTC 生命周期和房间连接能力。
 *
 * @param engineManager 负责进程级 RTC 引擎生命周期的管理器。
 * @param joinTimeoutMillis 等待 RTC 进房结果的最大时长，单位为毫秒；默认值为 `JOIN_TIMEOUT_MILLIS`。
 * @param callbackScope 派发异步回调的协程作用域。
 */
internal class RtcManager(
    private val engineManager: RtcEngineManager,
    private val joinTimeoutMillis: Long = JOIN_TIMEOUT_MILLIS,
    callbackScope: CoroutineScope? = null,
) : RtcManaging {
    /**
     * RTC 事件回调的派发作用域。
     */
    private val eventCallbackScope: CoroutineScope by lazy {
        callbackScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    /**
     * 引擎、房间及原生调用的并发保护。
     */
    private val lifecycleMutex = Mutex()
    /**
     * 原生资源在读取期间保持有效，销毁前等待所有读取结束。
     */
    private val nativeGate = ReentrantReadWriteLock(true)
    private val stateLock = Any()

    /**
     * 当前引擎租约和活动或等待中的房间。
     */
    private var engineLease: RtcEngineLease? = null
    private var activeRoom: RoomContext? = null
    private var pendingJoin: PendingJoin? = null

    /**
     * RTC 事件与质量监听器。
     */
    private var eventListener: WeakReference<RtcEventListener>? = null
    private var qualityListener: WeakReference<RtcQualityListener>? = null

    /**
     * 预览通知版本及远端帧订阅。
     */
    private var cameraListenerVersion = 0L
    private var cameraSourceVersion = 0L
    private var cameraPreviewReadyListener: RealtimeCameraPreviewReadyListener? = null
    private var remoteFrameRegistration: RemoteFrameRegistration? = null

    /**
     * 本地相机采集、画布绑定及首帧就绪条件。
     */
    private var isCameraVideoSourceActive = false
    private var hasCapturedFirstLocalVideoFrame = false
    private var hasBoundLocalVideoCanvas = false
    private var hasReportedCameraPreviewReady = false

    /**
     * 创建 RtcManager，并配置其依赖和运行参数。
     *
     * @param context 访问 Android 系统服务或媒体资源所需的上下文。
     */
    internal constructor(context: Context) : this(
        engineManager = RtcEngineManager.shared(context.applicationContext),
    )

    /**
     * 初始化 RTC 引擎。重复调用不会重复获取引擎。
     */
    override suspend fun initialize() {
        lifecycleMutex.withLock {
            if (synchronized(stateLock) { engineLease != null }) return

            val lease = try {
                engineManager.acquire()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw XmaxError.from(error)
            }
            try {
                nativeGate.write {
                    synchronized(stateLock) { engineLease = lease }
                    val bridge = object : RtcEventListener {
                        /**
                         * 处理指定房间内远端用户的视频发布状态变化，避免旧房间事件串入新连接。
                         *
                         * @param stream 由房间和用户标识确定的远端媒体流。
                         * @param published 远端视频是否处于发布状态。
                         */
                        override fun onRemoteVideoPublished(stream: RemoteStream, published: Boolean) {
                            if (isCurrentLease(lease)) handleRemoteVideoPublished(stream, published)
                        }

                        /**
                         * 接收房间内远端用户的结构化任务通知。
                         *
                         * @param stream 由房间和用户标识确定的远端媒体流。
                         * @param message 待发送或记录的消息文本。
                         */
                        override fun onUserMessageReceived(stream: RemoteStream, message: String) {
                            if (isCurrentLease(lease)) handleUserMessageReceived(stream, message)
                        }

                        /**
                         * 处理远端视频流携带的 SEI 消息。
                         *
                         * @param stream 由房间和用户标识确定的远端媒体流。
                         * @param message 待发送或记录的消息文本。
                         */
                        override fun onSeiMessageReceived(stream: RemoteStream, message: String) {
                            if (isCurrentLease(lease)) handleSeiMessageReceived(stream, message)
                        }
                    }
                    lease.engine.setEventListener(bridge)
                    lease.engine.setQualityListener(synchronized(stateLock) { qualityListener?.get() })
                    lease.engine.setCameraPreviewReadyListener {
                        if (isCurrentLease(lease)) markFirstLocalVideoFrameCaptured()
                    }
                }
            } catch (error: Throwable) {
                cleanupAfterFailure(error, {
                    nativeGate.write { synchronized(stateLock) { engineLease = null } }
                    engineManager.release(lease)
                })
                throw XmaxError.from(error)
            }
        }
    }

    /**
     * 离开房间并销毁 RTC 引擎。重复调用安全。
     */
    override suspend fun destroy() = withContext(NonCancellable) {
        lifecycleMutex.withLock {
            var lease: RtcEngineLease? = null
            cleanupResources(
                { nativeGate.write { leaveRoomLocked() } },
                { nativeGate.write {
                    lease = synchronized(stateLock) {
                        engineLease.also {
                            engineLease = null
                            remoteFrameRegistration = null
                            cameraPreviewReadyListener = null
                            resetCameraPreviewReadinessLocked(cameraSourceActive = false)
                            hasBoundLocalVideoCanvas = false
                        }
                    }
                    lease?.engine?.setEventListener(null)
                    lease?.engine?.setQualityListener(null)
                    lease?.engine?.setCameraPreviewReadyListener(null)
                } },
                { lease?.let(engineManager::release) },
            )
        }
    }

    /**
     * 应用主视频流编码配置。
     *
     * @param configuration 上行视频的尺寸、帧率、码率及编码偏好。
     */
    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration) {
        nativeGate.read {
            validateVideoDimensions(
                width = configuration.width,
                height = configuration.height,
                frameRate = configuration.frameRate,
            )
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: throw rtcError("RTC Engine is not initialized")
            val result = try {
                engine.configureVideoEncoding(configuration)
            } catch (error: Throwable) {
                throw rtcOperationError("setVideoEncoderConfig", error)
            }
            if (result < 0) {
                throw rtcResultError("setVideoEncoderConfig", result)
            }
        }
    }

    /**
     * 推送一帧外部视频数据及其可选 SEI。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @param seiData 附加视频元数据；null 表示不附加，当前 Agora 适配不使用该字段。
     */
    override fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    ) {
        withEngine("pushExternalVideoFrame") { engine ->
            engine.pushExternalVideoFrame(frame, seiData)
        }
    }

    /**
     * 推送一帧 10 ms PCM 外部音频数据。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    override fun pushExternalAudioFrame(frame: AudioFrame) {
        withEngine("pushExternalAudioFrame") { engine ->
            engine.pushExternalAudioFrame(frame)
        }
    }

    /**
     * 将主视频切换为 RTC 外部帧输入。
     */
    override fun useExternalVideoSource() {
        synchronized(stateLock) {
            resetCameraPreviewReadinessLocked(cameraSourceActive = false)
        }
        performEngineOperation("setVideoSourceType") {
            it.useExternalVideoSource()
        }
    }

    /**
     * 将主音频切换为 RTC 外部 PCM 输入。
     */
    override fun startExternalAudioSource() {
        performEngineOperation("setAudioSourceType") {
            it.startExternalAudioSource()
        }
    }

    /**
     * 停止 RTC 外部音频输入。
     */
    override fun stopExternalAudioSource() {
        performOptionalEngineOperation("stopAudioCapture") {
            it.stopExternalAudioSource()
        }
    }

    /**
     * 切换到 RTC 内部音频源并启动麦克风采集。
     */
    override fun startAudioCapture() {
        performEngineOperation("startAudioCapture") {
            it.startAudioCapture()
        }
    }

    /**
     * 停止 RTC 内部麦克风采集。
     */
    override fun stopAudioCapture() {
        performOptionalEngineOperation("stopAudioCapture") {
            it.stopAudioCapture()
        }
    }

    /**
     * 按指定格式启动 RTC 内部摄像头采集。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param frameRate 视频帧率，单位为 fps。
     */
    override fun startVideoCapture(
        width: Int,
        height: Int,
        frameRate: Int,
    ) {
        validateVideoDimensions(width, height, frameRate)
        synchronized(stateLock) {
            resetCameraPreviewReadinessLocked(cameraSourceActive = true)
        }
        try {
            performEngineOperation("startVideoCapture") {
                it.startVideoCapture(width, height, frameRate)
            }
        } catch (error: Throwable) {
            synchronized(stateLock) {
                resetCameraPreviewReadinessLocked(cameraSourceActive = false)
            }
            throw error
        }
    }

    /**
     * 停止 RTC 内部摄像头采集。
     */
    override fun stopVideoCapture() {
        try {
            performOptionalEngineOperation("stopVideoCapture") {
                it.stopVideoCapture()
            }
        } finally {
            synchronized(stateLock) {
                resetCameraPreviewReadinessLocked(cameraSourceActive = false)
            }
        }
    }

    /**
     * 切换 RTC 内部采集使用的摄像头。
     *
     * @param position 需要使用的前置或后置摄像头。
     */
    override fun switchCamera(position: CameraPosition) {
        performEngineOperation("switchCamera") {
            it.switchCamera(position)
        }
    }

    /**
     * 将本地视频绑定到渲染视图。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    override fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    ) {
        performEngineOperation("setLocalVideoCanvas") {
            it.bindLocalVideo(view, contentMode)
        }
        markLocalVideoCanvasBound()
    }

    /**
     * 解除本地视频与渲染视图的绑定。
     */
    override fun unbindLocalVideo() {
        try {
            performOptionalEngineOperation("setLocalVideoCanvas") {
                it.unbindLocalVideo()
            }
        } finally {
            synchronized(stateLock) {
                hasBoundLocalVideoCanvas = false
            }
        }
    }

    /**
     * 将指定远端视频流绑定到渲染视图。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     */
    override fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    ) {
        requireActiveRemoteStream(stream)
        performEngineOperation("setRemoteVideoCanvas") {
            it.bindRemoteVideo(stream.userId, view, contentMode)
        }
    }

    /**
     * 解除指定远端视频流的渲染绑定。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    override fun unbindRemoteVideo(stream: RemoteStream) {
        performOptionalEngineOperation("setRemoteVideoCanvas") {
            it.unbindRemoteVideo(stream.userId)
        }
    }

    /**
     * 加入 RTC 房间，并等待服务端确认加入成功。
     *
     * @param configuration 房间标识、用户账号、App ID 和进房 Token。
     */
    override suspend fun joinRoom(configuration: RoomJoinConfiguration) {
        val normalizedConfiguration = configuration.normalized()
        val pending = lifecycleMutex.withLock {
            beginJoin(normalizedConfiguration)
        }

        try {
            withTimeout(joinTimeoutMillis) {
                pending.result.await()
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            val timeoutError = XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "RTC join room timed out",
                cause = error,
            )
            cleanupPendingJoin(pending.id, timeoutError)
            throw timeoutError
        } catch (error: CancellationException) {
            cleanupPendingJoin(pending.id, error)
            throw error
        } catch (error: Throwable) {
            throw XmaxError.from(error)
        }
    }

    /**
     * 离开当前或正在加入的 RTC 房间。重复调用安全。
     */
    override suspend fun leaveRoom() {
        lifecycleMutex.withLock {
            leaveRoomLocked()
        }
    }

    /**
     * 发布本地视频流。
     */
    override fun publishLocalVideo() {
        performRoomOperation("publishStreamVideo") {
            it.publishLocalVideo(true)
        }
    }

    /**
     * 停止发布本地视频流。没有活动房间时安全返回。
     */
    override fun unpublishLocalVideo() {
        performOptionalRoomOperation("publishStreamVideo") {
            it.publishLocalVideo(false)
        }
    }

    /**
     * 发布本地音频流。
     */
    override fun publishLocalAudio() {
        performRoomOperation("publishStreamAudio") {
            it.publishLocalAudio(true)
        }
    }

    /**
     * 停止发布本地音频流。没有活动房间时安全返回。
     */
    override fun unpublishLocalAudio() {
        performOptionalRoomOperation("publishStreamAudio") {
            it.publishLocalAudio(false)
        }
    }

    /**
     * 更新远端视频流订阅状态。
     *
     * @param userId 房间中的业务用户标识。
     * @param subscribe true 订阅媒体，false 取消订阅。
     */
    override fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    ) {
        val normalizedUserId = normalizeRemoteUserId(userId)
        performRoomOperation("subscribeStreamVideo") {
            it.subscribeRemoteVideo(normalizedUserId, subscribe)
        }
    }

    /**
     * 更新远端音频流订阅状态。
     *
     * @param userId 房间中的业务用户标识。
     * @param subscribe true 订阅媒体，false 取消订阅。
     */
    override fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    ) {
        val normalizedUserId = normalizeRemoteUserId(userId)
        performRoomOperation("subscribeStreamAudio") {
            it.subscribeRemoteAudio(normalizedUserId, subscribe)
        }
    }

    /**
     * 设置指定远端用户的音频播放音量。
     *
     * @param volume 远端音频播放音量，范围为 0 到 100。
     * @param userId 房间中的业务用户标识。
     */
    override fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    ) {
        nativeGate.read {
            val normalizedUserId = normalizeRemoteUserId(userId)
            if (volume !in 0..100) {
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "RTC audio volume must be between 0 and 100",
                )
            }
            val resources = synchronized(stateLock) {
                val engine = engineLease?.engine ?: throw rtcError("RTC Engine is not initialized")
                val streamId = activeRoom?.room?.resolveRemoteStreamId(normalizedUserId)
                    ?: normalizedUserId
                engine to streamId
            }
            val result = try {
                resources.first.setRemoteAudioVolume(resources.second, volume)
            } catch (error: Throwable) {
                throw rtcOperationError("setRemoteAudioPlaybackVolume", error)
            }
            checkResult("setRemoteAudioPlaybackVolume", result)
        }
    }

    /**
     * 向当前 RTC 房间发送消息。
     *
     * @param message 待发送或记录的消息文本。
     * @param replacingPendingMessage 合并键；同键且尚未发送的信令仅保留最新内容。
     */
    override fun sendRoomMessage(message: String, replacingPendingMessage: String?) {
        nativeGate.read {
            if (message.isEmpty()) {
                throw XmaxError(
                    code = XmaxErrorCode.INVALID_CONFIGURATION,
                    message = "RTC room message cannot be empty",
                )
            }
            val room = synchronized(stateLock) {
                activeRoom?.room
            } ?: throw rtcError("RTC room is not joined")
            val result = try {
                room.sendRoomMessage(message, replacingPendingMessage)
            } catch (error: Throwable) {
                throw rtcOperationError("sendRoomMessage", error)
            }
            if (result < 0L) {
                throw rtcResultError("sendRoomMessage", result)
            }
        }
    }

    /**
     * 丢弃当前房间尚未开始发送的可合并信令。
     *
     * @param key 要清理的合并键；null 表示清理全部可合并信令。
     */
    override fun discardPendingMessages(key: String?) {
        nativeGate.read {
            val room = synchronized(stateLock) { activeRoom?.room }
            room?.discardPendingMessages(key)
        }
    }

    /**
     * 不阻塞主线程地等待已经入队的房间信令提交完成。
     */
    override suspend fun awaitPendingMessages() {
        val room = synchronized(stateLock) { activeRoom?.room } ?: throw rtcError("RTC room is not joined")
        room.awaitPendingMessages()
    }

    /**
     * 设置 RTC 事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setEventListener(listener: RtcEventListener?) {
        synchronized(stateLock) {
            eventListener = listener?.let(::WeakReference)
        }
    }

    /**
     * 设置 RTC 摄像头预览就绪监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        val shouldNotify = synchronized(stateLock) {
            cameraListenerVersion++
            cameraPreviewReadyListener = listener
            markCameraPreviewReadyReportedIfNeededLocked()
        }
        notifyCameraPreviewReady(shouldNotify)
    }

    /**
     * 为当前生成注册持续帧接收器；清除时同时停止该接收器的远端渲染。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setRemoteVideoFrameListener(
        stream: RemoteStream,
        listener: RtcRemoteVideoSink?,
    ) {
        nativeGate.write {
            if (listener == null) {
                if (synchronized(stateLock) { remoteFrameRegistration?.stream == stream }) {
                    clearRemoteFrameRegistrationLocked()
                }
                return
            }
            val registration = synchronized(stateLock) {
                val lease = engineLease ?: throw rtcError("RTC Engine is not initialized")
                val room = activeRoom?.takeIf { it.roomId == stream.roomId }
                    ?: throw rtcError("Remote stream does not belong to the active RTC room")
                RemoteFrameRegistration(stream, room.room.resolveRemoteStreamId(stream.userId), room, lease, listener)
            }
            clearRemoteFrameRegistrationLocked()
            synchronized(stateLock) { remoteFrameRegistration = registration }
            try {
                val result = registration.lease.engine.setRemoteVideoFrameListener(registration.streamId, object : RtcRemoteVideoSink {
                    /**
                     * 通知订阅的视频流已到达首帧及其尺寸。
                     *
                     * @param width 画面宽度，单位为像素。
                     * @param height 画面高度，单位为像素。
                     */
                    override fun onFirstFrame(width: Int, height: Int) {
                        handleRemoteVideoFrame(registration, width, height)
                    }

                    /**
                     * 接收当前订阅的视频帧。
                     *
                     * @param frame 待处理、显示或提交的视频帧。
                     */
                    override fun onFrame(frame: RtcRemoteVideoFrame) {
                        /**
                         * 原生帧不能跨线程；不持有 nativeGate/stateLock 调用消费者。
                         */
                        val active = synchronized(stateLock) { isCurrentFrameRegistration(registration) }
                        if (active) registration.listener.onFrame(frame)
                    }

                    /**
                     * 报告当前媒体或 RTC 操作发生的错误。
                     *
                     * @param error 本次操作失败的异常或 SDK 错误。
                     */
                    override fun onError(error: XmaxError) {
                        eventCallbackScope.launch {
                            val active = synchronized(stateLock) { isCurrentFrameRegistration(registration) }
                            if (active) registration.listener.onError(error)
                        }
                    }
                })
                if (result < 0) throw rtcResultError("setRemoteVideoSink", result)
            } catch (error: Throwable) {
                runCatching { clearRemoteFrameRegistrationLocked() }
                    .onFailure { if (it !== error) error.addSuppressed(it) }
                if (error is CancellationException || error is XmaxError) throw error
                throw rtcOperationError("setRemoteVideoSink", error)
            }
        }
    }

    /**
     * 注销当前远端帧监听，阻止已失效的订阅继续回调。
     */
    private fun clearRemoteFrameRegistrationLocked() {
        val registration = synchronized(stateLock) {
            remoteFrameRegistration?.also { it.isActive = false }
        } ?: return
        val result = registration.lease.engine.setRemoteVideoFrameListener(registration.streamId, null)
        if (result < 0) throw rtcResultError("setRemoteVideoSink", result)
        synchronized(stateLock) { remoteFrameRegistration = null }
    }

    /**
     * 设置 RTC 质量事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setQualityListener(listener: RtcQualityListener?) {
        nativeGate.read {
            synchronized(stateLock) {
                qualityListener = listener?.let(::WeakReference)
                engineLease?.engine?.setQualityListener(listener)
            }
        }
    }

    /**
     * 对当前房间执行操作，并将失败返回码转换为 SDK 错误。
     *
     * @param operation 当前执行的 RTC 操作名称。
     * @param action 需要执行的操作。
     */
    private fun performRoomOperation(
        operation: String,
        action: (RtcRoom) -> Int,
    ) {
        nativeGate.read {
            val room = synchronized(stateLock) {
                activeRoom?.room
            } ?: throw rtcError("RTC room is not joined")
            performRoomOperation(room, operation, action)
        }
    }

    /**
     * 对当前引擎执行操作，并将失败返回码转换为 SDK 错误。
     *
     * @param operation 当前执行的 RTC 操作名称。
     * @param action 需要执行的操作。
     */
    private fun performEngineOperation(
        operation: String,
        action: (RtcPlatform) -> Int,
    ) {
        nativeGate.read {
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: throw rtcError("RTC Engine is not initialized")
            val result = try {
                action(engine)
            } catch (error: XmaxError) {
                throw error
            } catch (error: Throwable) {
                throw rtcOperationError(operation, error)
            }
            checkResult(operation, result)
        }
    }

    /**
     * 房间存在时执行操作并校验结果；没有房间时不执行。
     *
     * @param operation 当前执行的 RTC 操作名称。
     * @param action 需要执行的操作。
     */
    private fun performOptionalRoomOperation(
        operation: String,
        action: (RtcRoom) -> Int,
    ) {
        nativeGate.read {
            val room = synchronized(stateLock) {
                activeRoom?.room
            } ?: return
            performRoomOperation(room, operation, action)
        }
    }

    /**
     * 引擎存在时执行操作并校验结果；没有引擎时不执行。
     *
     * @param operation 当前执行的 RTC 操作名称。
     * @param action 需要执行的操作。
     */
    private fun performOptionalEngineOperation(
        operation: String,
        action: (RtcPlatform) -> Int,
    ) {
        nativeGate.read {
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: return
            val result = try {
                action(engine)
            } catch (error: Throwable) {
                throw rtcOperationError(operation, error)
            }
            checkResult(operation, result)
        }
    }

    /**
     * 对当前房间执行操作，并将失败返回码转换为 SDK 错误。
     *
     * @param room 当前操作关联的 RTC 房间。
     * @param operation 当前执行的 RTC 操作名称。
     * @param action 需要执行的操作。
     */
    private fun performRoomOperation(
        room: RtcRoom,
        operation: String,
        action: (RtcRoom) -> Int,
    ) {
        nativeGate.read {
            val result = try {
                action(room)
            } catch (error: Throwable) {
                throw rtcOperationError(operation, error)
            }
            checkResult(operation, result)
        }
    }

    /**
     * 将非成功的 RTC 返回码转换为带操作上下文的 SDK 错误。
     *
     * @param operation 当前执行的 RTC 操作名称。
     * @param result RTC 操作返回的状态或错误码。
     */
    private fun checkResult(operation: String, result: Int) {
        if (result < 0) throw rtcResultError(operation, result)
    }

    /**
     * 对已获取的 RTC 引擎执行操作，未获取引擎时报告错误。
     *
     * @param operation 当前执行的 RTC 操作名称。
     * @param action 需要执行的操作。
     */
    private fun withEngine(
        operation: String,
        action: (RtcPlatform) -> Unit,
    ) {
        nativeGate.read {
            val engine = synchronized(stateLock) {
                engineLease?.engine
            } ?: throw rtcError("RTC Engine is not initialized")
            try {
                action(engine)
            } catch (error: XmaxError) {
                throw error
            } catch (error: Throwable) {
                throw rtcOperationError(operation, error)
            }
        }
    }

    /**
     * 向当前房间的订阅方报告远端视频发布或撤销。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param published 远端视频是否处于发布状态。
     */
    private fun handleRemoteVideoPublished(
        stream: RemoteStream,
        published: Boolean,
    ) {
        /**
         * 发布通知可能紧随原生入房回调到达，此时主线程尚未激活房间。
         * 按用户保留最新发布状态；取消或失败时随 PendingJoin 丢弃，不能借用后续连接。
         */
        val room = synchronized(stateLock) {
            val pending = pendingJoin?.takeIf { it.context.roomId == stream.roomId }
            if (pending != null) {
                pending.remoteVideoPublications[stream] = published
                return
            }
            activeRoom?.takeIf { it.roomId == stream.roomId } ?: return
        }
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom === room }
            }
            listener?.onRemoteVideoPublished(stream, published)
        }
    }

    /**
     * 转发当前房间的用户消息，忽略其他房间的迟到消息。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param message 待发送或记录的消息文本。
     */
    private fun handleUserMessageReceived(stream: RemoteStream, message: String) {
        val room = synchronized(stateLock) { activeRoom?.takeIf { it.roomId == stream.roomId } } ?: return
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom === room }
            }
            listener?.onUserMessageReceived(stream, message)
        }
    }

    /**
     * 转发当前远端流的 SEI 消息。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param message 待发送或记录的消息文本。
     */
    private fun handleSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        val room = synchronized(stateLock) { activeRoom?.takeIf { it.roomId == stream.roomId } } ?: return
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom === room }
            }
            listener?.onSeiMessageReceived(stream, message)
        }
    }

    /**
     * 报告当前订阅流的首帧尺寸，忽略失效的订阅。
     *
     * @param registration 本次远端帧订阅的注册信息。
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     */
    private fun handleRemoteVideoFrame(
        registration: RemoteFrameRegistration,
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        synchronized(stateLock) {
            if (!registration.isActive || remoteFrameRegistration !== registration || activeRoom !== registration.room ||
                engineLease !== registration.lease || registration.framePending
            ) return
            registration.framePending = true
        }
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                registration.listener.takeIf {
                    registration.isActive && remoteFrameRegistration === registration && activeRoom === registration.room &&
                        engineLease === registration.lease
                }
            }
            listener?.onFirstFrame(width, height)
        }
    }

    /**
     * 判断远端帧监听是否仍属于当前房间和引擎。
     *
     * @param registration 本次远端帧订阅的注册信息。
     * @return 注册仍属于当前活动连接时为 true。
     */
    private fun isCurrentFrameRegistration(registration: RemoteFrameRegistration): Boolean =
        registration.isActive && remoteFrameRegistration === registration && activeRoom === registration.room &&
            engineLease === registration.lease

    /**
     * 保存远端帧监听器及其所属房间和引擎，防止跨连接派发帧。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     * @param streamId 远端媒体流或数据通道的标识。
     * @param room 当前操作关联的 RTC 房间。
     * @param lease 当前引擎的独占使用凭据。
     * @param listener 接收对应事件的监听器。
     * @param isActive 当前订阅是否仍有效；默认值为 `true`。
     * @param framePending 是否仍在等待首帧通知；默认值为 `false`。
     */
    private class RemoteFrameRegistration(
        val stream: RemoteStream,
        val streamId: String,
        val room: RoomContext,
        val lease: RtcEngineLease,
        val listener: RtcRemoteVideoSink,
        var isActive: Boolean = true,
        var framePending: Boolean = false,
    )

    /**
     * 记录本地采集首帧已到达，并检查预览是否就绪。
     */
    private fun markFirstLocalVideoFrameCaptured() {
        val shouldNotify = synchronized(stateLock) {
            hasCapturedFirstLocalVideoFrame = true
            markCameraPreviewReadyReportedIfNeededLocked()
        }
        notifyCameraPreviewReady(shouldNotify)
    }

    /**
     * 记录本地视频画布已绑定，并检查预览是否就绪。
     */
    private fun markLocalVideoCanvasBound() {
        val shouldNotify = synchronized(stateLock) {
            hasBoundLocalVideoCanvas = true
            markCameraPreviewReadyReportedIfNeededLocked()
        }
        notifyCameraPreviewReady(shouldNotify)
    }

    /**
     * 清空相机预览就绪状态，为新采集源重新等待首帧。
     *
     * @param cameraSourceActive 当前视频输入是否为相机采集。
     */
    private fun resetCameraPreviewReadinessLocked(cameraSourceActive: Boolean) {
        cameraSourceVersion++
        isCameraVideoSourceActive = cameraSourceActive
        hasCapturedFirstLocalVideoFrame = false
        hasReportedCameraPreviewReady = false
    }

    /**
     * 在采集首帧和画布均就绪时，标记本轮预览已完成通知。
     *
     * @return 本次需要发出预览就绪通知时为 true。
     */
    private fun markCameraPreviewReadyReportedIfNeededLocked(): Boolean {
        if (!isCameraVideoSourceActive ||
            !hasCapturedFirstLocalVideoFrame ||
            !hasBoundLocalVideoCanvas ||
            hasReportedCameraPreviewReady ||
            cameraPreviewReadyListener == null
        ) {
            return false
        }
        hasReportedCameraPreviewReady = true
        return true
    }

    /**
     * 按就绪判定结果通知相机预览监听器。
     *
     * @param shouldNotify 是否已满足预览就绪的通知条件。
     */
    private fun notifyCameraPreviewReady(shouldNotify: Boolean) {
        if (!shouldNotify) return
        val registration = synchronized(stateLock) {
            Triple(cameraListenerVersion, cameraSourceVersion, cameraPreviewReadyListener)
        }
        eventCallbackScope.launch {
            val current = synchronized(stateLock) {
                cameraListenerVersion == registration.first && cameraSourceVersion == registration.second
            }
            if (current) runCatching { registration.third?.onCameraPreviewReady() }.onFailure {
                XmaxLogger.realtime.warn(
                    message = { "Camera preview listener failed: ${it.message}" },
                )
            }
        }
    }

    /**
     * 创建本次进房操作及结果等待器。
     *
     * @param configuration 房间标识、用户账号、App ID 和进房 Token。
     * @return 用于跟踪进房结果及等待事件的操作对象。
     */
    private fun beginJoin(configuration: RoomJoinConfiguration): PendingJoin {
        nativeGate.write {
            val lease = synchronized(stateLock) {
                engineLease
            } ?: throw rtcError("RTC Engine is not initialized")

            synchronized(stateLock) {
                if (activeRoom != null || pendingJoin != null) {
                    throw rtcError("RTC room is already active")
                }
            }

            val room = lease.engine.createRoom(configuration.roomId)
                ?: throw rtcError("Failed to create RTC room")
            val context = RoomContext(configuration.roomId, room)
            val pending = PendingJoin(context = context)
            val listenerResult = try {
                room.setEventListener { roomId, joined, reason ->
                    eventCallbackScope.launch { handleRoomState(pending.id, roomId, joined, reason) }
                }
            } catch (error: Throwable) {
                room.destroy()
                throw rtcOperationError("setRTCRoomEventHandler", error)
            }
            if (listenerResult < 0) {
                room.destroy()
                throw rtcResultError("setRTCRoomEventHandler", listenerResult)
            }

            synchronized(stateLock) {
                pendingJoin = pending
            }
            val joinResult = try {
                room.join(configuration)
            } catch (error: Throwable) {
                val rtcError = rtcOperationError("joinRoom", error)
                cleanupPendingJoin(pending.id, rtcError)
                throw rtcError
            }
            if (joinResult < 0) {
                val error = rtcResultError("joinRoom", joinResult)
                cleanupPendingJoin(pending.id, error)
                throw error
            }
            return pending
        }
    }

    /**
     * 将当前进房操作的 RTC 状态转换为进房结果。
     *
     * @param pendingId 等待中的进房操作标识。
     * @param roomId RTC 房间标识。
     * @param joined 是否已成功加入房间。
     * @param reason 引起状态变化或失败的原因。
     */
    private fun handleRoomState(
        pendingId: UUID,
        roomId: String,
        joined: Boolean,
        reason: String?,
    ) {
        val pending = synchronized(stateLock) {
            pendingJoin?.takeIf {
                it.id == pendingId && it.context.roomId == roomId
            }
        }
        if (pending == null) {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom?.id == pendingId && activeRoom?.roomId == roomId }
            }
            if (!joined && isTerminalRoomFailure(reason)) {
                listener?.onRoomTerminated(roomId, rtcError("RTC room terminated: $reason"))
            }
            return
        }
        if (!joined && isRetryableRoomFailure(reason)) return

        if (joined) {
            val publications = synchronized(stateLock) {
                if (pendingJoin?.id != pendingId) {
                    null
                } else {
                    activeRoom = pending.context
                    pendingJoin = null
                    pending.remoteVideoPublications.toMap()
                }
            }
            if (publications != null) {
                /**
                 * 此处已在回调队列上，先补发入房期间的最新状态，再处理后续排队事件。
                 */
                try {
                    publications.forEach { (stream, published) ->
                        val listener = synchronized(stateLock) {
                            eventListener?.get().takeIf { activeRoom === pending.context }
                        }
                        listener?.onRemoteVideoPublished(stream, published)
                    }
                } finally {
                    pending.result.complete(Unit)
                }
            }
            return
        }

        cleanupPendingJoin(
            pendingId,
            rtcError(
                buildString {
                    append("RTC join room failed")
                    reason?.trim()?.takeIf(String::isNotEmpty)?.let {
                        append(": ")
                        append(it)
                    }
                },
            ),
        )
    }

    /**
     * 终止指定进房操作并释放其房间资源。
     *
     * @param pendingId 等待中的进房操作标识。
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    private fun cleanupPendingJoin(
        pendingId: UUID,
        error: Throwable,
    ) {
        nativeGate.write {
            val resources = synchronized(stateLock) {
                val pending = pendingJoin?.takeIf { it.id == pendingId }
                val joined = activeRoom?.takeIf { it.id == pendingId }
                if (pending != null) pendingJoin = null
                if (joined != null) activeRoom = null
                pending to joined
            }
            try {
                resources.first?.context?.let { tearDownRoom(it, leave = false) }
                resources.second?.let { tearDownRoom(it, leave = true) }
            } finally {
                resources.first?.result?.completeExceptionally(error)
            }
        }
    }

    /**
     * 解除当前房间绑定并结束尚未完成的进房操作。
     */
    private fun leaveRoomLocked() {
        nativeGate.write {
            val resources = synchronized(stateLock) {
                RoomResources(
                    active = activeRoom,
                    pending = pendingJoin,
                ).also {
                    activeRoom = null
                    pendingJoin = null
                }
            }
            resources.pending?.result?.completeExceptionally(
                cancelledError("RTC join room"),
            )
            var failure: Throwable? = null
            val releases: List<() -> Unit> = listOf(
                { clearRemoteFrameRegistrationLocked() },
                { resources.pending?.context?.let { tearDownRoom(it, leave = false) } },
                { resources.active?.let { tearDownRoom(it, leave = true) } },
            )
            releases.forEach { release ->
                try {
                    release()
                } catch (error: Throwable) {
                    if (failure == null) failure = error
                    else if (failure !== error) failure.addSuppressed(error)
                }
            }
            failure?.let { throw it }
        }
    }

    /**
     * 释放指定房间及其回调，可按需先执行离房。
     *
     * @param context 本次 RTC 房间操作的资源和标识。
     * @param leave 释放房间前是否先发送离房请求。
     */
    private fun tearDownRoom(
        context: RoomContext,
        leave: Boolean,
    ) {
        try {
            if (leave) {
                runCatching { context.room.publishLocalVideo(false) }
                runCatching { context.room.publishLocalAudio(false) }
                context.room.leave()
            }
        } finally {
            context.room.destroy()
            synchronized(stateLock) {
                if (remoteFrameRegistration?.room === context) remoteFrameRegistration = null
            }
        }
    }

    /**
     * 取得去除标识前后空白且必填字段有效的进房配置。
     *
     * @return 必填标识有效且已规范化的 RTC 进房配置。
     */
    private fun RoomJoinConfiguration.normalized(): RoomJoinConfiguration {
        val roomId = roomId.trim().requireValue("RTC room ID")
        val userId = userId.trim().requireValue("RTC user ID")
        val token = token.trim().requireValue("RTC room token")
        return RoomJoinConfiguration(roomId, userId, token)
    }

    /**
     * 校验视频尺寸与帧率是否为有效正值。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param frameRate 视频帧率，单位为 fps。
     */
    private fun validateVideoDimensions(
        width: Int,
        height: Int,
        frameRate: Int,
    ) {
        if (width <= 0 || height <= 0 || frameRate <= 0 || width % 2 != 0 || height % 2 != 0) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "RTC video width and height must be positive even numbers, " +
                    "and frame rate must be greater than zero",
            )
        }
    }

    /**
     * 取得非空标识，空白输入会抛出配置错误。
     *
     * @param name 用于错误提示的字段名称。
     * @return 去除首尾空白后的非空字段值。
     */
    private fun String.requireValue(name: String): String = takeIf(String::isNotEmpty)
        ?: throw rtcError("$name cannot be empty")

    /**
     * 规范化并校验远端用户标识。
     *
     * @param userId 房间中的业务用户标识。
     * @return 经过规范化的非空远端用户标识。
     */
    private fun normalizeRemoteUserId(userId: String): String =
        userId.trim().takeIf(String::isNotEmpty) ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "RTC user ID cannot be empty",
        )

    /**
     * 确认远端流属于当前活动房间，否则拒绝操作。
     *
     * @param stream 由房间和用户标识确定的远端媒体流。
     */
    private fun requireActiveRemoteStream(stream: RemoteStream) {
        val isActive = synchronized(stateLock) {
            activeRoom?.roomId == stream.roomId
        }
        if (!isActive) throw rtcError("RTC remote stream is not in the active room")
    }

    /**
     * 判断指定引擎租约是否仍有效。
     *
     * @param lease 当前引擎的独占使用凭据。
     * @return 租约仍属于当前活动引擎时为 true。
     */
    private fun isCurrentLease(lease: RtcEngineLease): Boolean = synchronized(stateLock) { engineLease === lease }

    /**
     * 保存活动 RTC 房间及其本地操作标识。
     *
     * @param roomId RTC 房间标识。
     * @param room 当前操作关联的 RTC 房间。
     * @param id 用于区分当前对象或操作的唯一标识。
     */
    private data class RoomContext(
        val roomId: String,
        val room: RtcRoom,
        val id: UUID = UUID.randomUUID(),
    )

    /**
     * 保存进房等待结果和进房期间到达的远端发布事件。
     *
     * @param context 本次 RTC 房间操作的资源和标识。
     * @param id 用于区分当前对象或操作的唯一标识；默认值为 `context.id`。
     * @param result 用于通知操作完成的等待器。
     * @param remoteVideoPublications 进房期间记录的远端视频发布状态。
     */
    private data class PendingJoin(
        val context: RoomContext,
        val id: UUID = context.id,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
        val remoteVideoPublications: MutableMap<RemoteStream, Boolean> = linkedMapOf(),
    )

    /**
     * 汇总离房时需要清理的活动房间和等待中的进房操作。
     *
     * @param active 当前已加入的房间；null 表示没有活动房间。
     * @param pending 尚未完成的进房操作；null 表示没有等待中的进房。
     */
    private data class RoomResources(
        val active: RoomContext?,
        val pending: PendingJoin?,
    )

    /**
     * 提供 RtcManager 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val JOIN_TIMEOUT_MILLIS: Long = 15_000L

        /**
         * 创建包含 RTC 故障原因的 SDK 错误。
         *
         * @param message 待发送或记录的消息文本。
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        fun rtcError(message: String): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = message,
        )

        /**
         * 创建包含 RTC 返回码的操作错误。
         *
         * @param operation 当前执行的 RTC 操作名称。
         * @param result RTC 操作返回的状态或错误码。
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        fun rtcResultError(operation: String, result: Number): XmaxError = rtcError(
            "RTC $operation failed: $result",
        )

        /**
         * 创建包含底层异常的 RTC 操作错误。
         *
         * @param operation 当前执行的 RTC 操作名称。
         * @param cause 触发当前错误的底层异常。
         * @return 包含错误类别、描述和可用底层原因的 SDK 错误。
         */
        fun rtcOperationError(operation: String, cause: Throwable): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC $operation failed: ${cause.message ?: cause.javaClass.simpleName}",
            cause = cause,
        )

        /**
         * 创建表示 RTC 操作已被取消的协程异常。
         *
         * @param operation 当前执行的 RTC 操作名称。
         * @return 表示当前操作已取消的协程异常。
         */
        fun cancelledError(operation: String): CancellationException =
            CancellationException("$operation was cancelled")
    }
}
