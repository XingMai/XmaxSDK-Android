package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import android.content.Context
import android.view.View
import java.lang.ref.WeakReference
import java.util.UUID
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

/** 提供基于火山引擎的 RTC 生命周期和房间连接能力。 */
internal class RtcManager(
    private val engineManager: RtcEngineManager,
    private val joinTimeoutMillis: Long = JOIN_TIMEOUT_MILLIS,
    callbackScope: CoroutineScope? = null,
) : RtcManaging {
    private val eventCallbackScope: CoroutineScope by lazy {
        callbackScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private var engineLease: RtcEngineLease? = null
    private var activeRoom: RoomContext? = null
    private var pendingJoin: PendingJoin? = null
    private var eventListener: WeakReference<RtcEventListener>? = null
    private var qualityListener: WeakReference<RtcQualityListener>? = null
    private var cameraPreviewReadyListener: RealtimeCameraPreviewReadyListener? = null
    private var remoteVideoFrameReadyListener: ((RemoteStream, Int, Int) -> Unit)? = null
    private var isCameraVideoSourceActive = false
    private var hasCapturedFirstLocalVideoFrame = false
    private var hasBoundLocalVideoCanvas = false
    private var hasReportedCameraPreviewReady = false
    private val platformCameraPreviewReadyListener = {
        markFirstLocalVideoFrameCaptured()
    }
    private val platformEventListener = object : RtcEventListener {
        override fun onRemoteVideoPublished(
            userId: String,
            published: Boolean,
        ) {
            handleRemoteVideoPublished(userId, published)
        }

        override fun onSeiMessageReceived(
            stream: RemoteStream,
            message: String,
        ) {
            handleSeiMessageReceived(stream, message)
        }
    }

    internal constructor(context: Context) : this(
        engineManager = RtcEngineManager.shared(context.applicationContext),
    )

    override suspend fun initialize() {
        lifecycleMutex.withLock {
            if (synchronized(stateLock) { engineLease != null }) return

            val lease = try {
                engineManager.acquire()
            } catch (error: CancellationException) {
                throw cancelledError("RTC initialization")
            } catch (error: Throwable) {
                throw XmaxError.from(error)
            }
            synchronized(stateLock) {
                engineLease = lease
                lease.engine.setEventListener(platformEventListener)
                lease.engine.setQualityListener(qualityListener?.get())
                lease.engine.setCameraPreviewReadyListener(platformCameraPreviewReadyListener)
                lease.engine.setRemoteVideoFrameReadyListener(::handleRemoteVideoFrameReady)
            }
        }
    }

    override suspend fun destroy() {
        lifecycleMutex.withLock {
            leaveRoomLocked()
            val lease = synchronized(stateLock) {
                engineLease.also {
                    it?.engine?.setEventListener(null)
                    it?.engine?.setQualityListener(null)
                    it?.engine?.setCameraPreviewReadyListener(null)
                    it?.engine?.setRemoteVideoFrameReadyListener(null)
                    engineLease = null
                    cameraPreviewReadyListener = null
                    resetCameraPreviewReadinessLocked(cameraSourceActive = false)
                    hasBoundLocalVideoCanvas = false
                }
            }
            if (lease != null) {
                engineManager.release(lease)
            }
        }
    }

    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration) {
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

    override fun pushExternalVideoFrame(
        frame: VideoFrame,
        seiData: ByteArray?,
    ) {
        performEngineOperation("pushExternalVideoFrame") {
            it.pushExternalVideoFrame(frame, seiData)
        }
    }

    override fun pushExternalAudioFrame(frame: AudioFrame) {
        performEngineOperation("pushExternalAudioFrame") {
            it.pushExternalAudioFrame(frame)
        }
    }

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

    override fun switchCamera(position: CameraPosition) {
        performEngineOperation("switchCamera") {
            it.switchCamera(position)
        }
    }

    override fun bindLocalVideo(
        view: View,
        contentMode: VideoContentMode,
    ) {
        performEngineOperation("setLocalVideoCanvas") {
            it.bindLocalVideo(view, contentMode)
        }
        markLocalVideoCanvasBound()
    }

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

    override fun unbindRemoteVideo(stream: RemoteStream) {
        performOptionalEngineOperation("setRemoteVideoCanvas") {
            it.unbindRemoteVideo(stream.userId)
        }
    }

    override val renderLibraryName: String
        get() = "XmaxSDK"

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
            val timeoutError = XmaxError(
                code = XmaxErrorCode.TIMEOUT,
                message = "RTC join room timed out",
                cause = error,
            )
            cleanupPendingJoin(pending.id, timeoutError)
            throw timeoutError
        } catch (error: CancellationException) {
            val cancelledError = cancelledError("RTC join room")
            cleanupPendingJoin(pending.id, cancelledError)
            throw cancelledError
        } catch (error: Throwable) {
            throw XmaxError.from(error)
        }
    }

    override suspend fun leaveRoom() {
        lifecycleMutex.withLock {
            leaveRoomLocked()
        }
    }

    override fun publishLocalVideo() {
        performRoomOperation("publishStreamVideo") {
            it.publishLocalVideo(true)
        }
    }

    override fun unpublishLocalVideo() {
        performOptionalRoomOperation("publishStreamVideo") {
            it.publishLocalVideo(false)
        }
    }

    override fun publishLocalAudio() {
        performRoomOperation("publishStreamAudio") {
            it.publishLocalAudio(true)
        }
    }

    override fun unpublishLocalAudio() {
        performOptionalRoomOperation("publishStreamAudio") {
            it.publishLocalAudio(false)
        }
    }

    override fun subscribeRemoteVideo(
        userId: String,
        subscribe: Boolean,
    ) {
        val normalizedUserId = normalizeRemoteUserId(userId)
        performRoomOperation("subscribeStreamVideo") {
            it.subscribeRemoteVideo(normalizedUserId, subscribe)
        }
    }

    override fun subscribeRemoteAudio(
        userId: String,
        subscribe: Boolean,
    ) {
        val normalizedUserId = normalizeRemoteUserId(userId)
        performRoomOperation("subscribeStreamAudio") {
            it.subscribeRemoteAudio(normalizedUserId, subscribe)
        }
    }

    override fun setRemoteAudioVolume(
        volume: Int,
        userId: String,
    ) {
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

    override fun sendRoomMessage(message: String) {
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
            room.sendRoomMessage(message)
        } catch (error: Throwable) {
            throw rtcOperationError("sendRoomMessage", error)
        }
        if (result < 0L) {
            throw rtcResultError("sendRoomMessage", result)
        }
    }

    override fun setEventListener(listener: RtcEventListener?) {
        synchronized(stateLock) {
            eventListener = listener?.let(::WeakReference)
        }
    }

    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) {
        val shouldNotify = synchronized(stateLock) {
            cameraPreviewReadyListener = listener
            markCameraPreviewReadyReportedIfNeededLocked()
        }
        notifyCameraPreviewReady(shouldNotify)
    }

    override fun setRemoteVideoFrameReadyListener(
        listener: ((RemoteStream, Int, Int) -> Unit)?,
    ) {
        synchronized(stateLock) {
            remoteVideoFrameReadyListener = listener
        }
    }

    override fun setQualityListener(listener: RtcQualityListener?) {
        synchronized(stateLock) {
            qualityListener = listener?.let(::WeakReference)
            engineLease?.engine?.setQualityListener(listener)
        }
    }

    private fun performRoomOperation(
        operation: String,
        action: (RtcPlatformRoom) -> Int,
    ) {
        val room = synchronized(stateLock) {
            activeRoom?.room
        } ?: throw rtcError("RTC room is not joined")
        performRoomOperation(room, operation, action)
    }

    private fun performEngineOperation(
        operation: String,
        action: (RtcPlatformEngine) -> Int,
    ) {
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

    private fun performOptionalRoomOperation(
        operation: String,
        action: (RtcPlatformRoom) -> Int,
    ) {
        val room = synchronized(stateLock) {
            activeRoom?.room
        } ?: return
        performRoomOperation(room, operation, action)
    }

    private fun performOptionalEngineOperation(
        operation: String,
        action: (RtcPlatformEngine) -> Int,
    ) {
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

    private fun performRoomOperation(
        room: RtcPlatformRoom,
        operation: String,
        action: (RtcPlatformRoom) -> Int,
    ) {
        val result = try {
            action(room)
        } catch (error: Throwable) {
            throw rtcOperationError(operation, error)
        }
        checkResult(operation, result)
    }

    private fun checkResult(operation: String, result: Int) {
        if (result < 0) throw rtcResultError(operation, result)
    }

    private fun handleRemoteVideoPublished(
        userId: String,
        published: Boolean,
    ) {
        val roomId = synchronized(stateLock) {
            activeRoom?.roomId
        } ?: return
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom?.roomId == roomId }
            }
            listener?.onRemoteVideoPublished(userId, published)
        }
    }

    private fun handleSeiMessageReceived(
        stream: RemoteStream,
        message: String,
    ) {
        val isActive = synchronized(stateLock) {
            activeRoom?.roomId == stream.roomId
        }
        if (!isActive) return
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                eventListener?.get().takeIf { activeRoom?.roomId == stream.roomId }
            }
            listener?.onSeiMessageReceived(stream, message)
        }
    }

    private fun handleRemoteVideoFrameReady(
        stream: RemoteStream,
        width: Int,
        height: Int,
    ) {
        if (synchronized(stateLock) { activeRoom?.roomId != stream.roomId }) return
        eventCallbackScope.launch {
            val listener = synchronized(stateLock) {
                remoteVideoFrameReadyListener
                    .takeIf { activeRoom?.roomId == stream.roomId }
            }
            listener?.invoke(stream, width, height)
        }
    }

    private fun markFirstLocalVideoFrameCaptured() {
        val shouldNotify = synchronized(stateLock) {
            hasCapturedFirstLocalVideoFrame = true
            markCameraPreviewReadyReportedIfNeededLocked()
        }
        notifyCameraPreviewReady(shouldNotify)
    }

    private fun markLocalVideoCanvasBound() {
        val shouldNotify = synchronized(stateLock) {
            hasBoundLocalVideoCanvas = true
            markCameraPreviewReadyReportedIfNeededLocked()
        }
        notifyCameraPreviewReady(shouldNotify)
    }

    private fun resetCameraPreviewReadinessLocked(cameraSourceActive: Boolean) {
        isCameraVideoSourceActive = cameraSourceActive
        hasCapturedFirstLocalVideoFrame = false
        hasReportedCameraPreviewReady = false
    }

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

    private fun notifyCameraPreviewReady(shouldNotify: Boolean) {
        if (!shouldNotify) return
        eventCallbackScope.launch {
            synchronized(stateLock) { cameraPreviewReadyListener }
                ?.onCameraPreviewReady()
        }
    }

    private fun beginJoin(configuration: RoomJoinConfiguration): PendingJoin {
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
                handleRoomState(pending.id, roomId, joined, reason)
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
        } ?: return

        if (joined) {
            val completed = synchronized(stateLock) {
                if (pendingJoin?.id != pendingId) {
                    false
                } else {
                    activeRoom = pending.context
                    pendingJoin = null
                    true
                }
            }
            if (completed) pending.result.complete(Unit)
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

    private fun cleanupPendingJoin(
        pendingId: UUID,
        error: XmaxError,
    ) {
        val pending = synchronized(stateLock) {
            pendingJoin?.takeIf { it.id == pendingId }?.also {
                pendingJoin = null
            }
        } ?: return

        tearDownRoom(pending.context, leave = false)
        pending.result.completeExceptionally(error)
    }

    private fun leaveRoomLocked() {
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
        resources.pending?.context?.let { tearDownRoom(it, leave = false) }
        resources.active?.let { tearDownRoom(it, leave = true) }
    }

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
        }
    }

    private fun RoomJoinConfiguration.normalized(): RoomJoinConfiguration {
        val roomId = roomId.trim().requireValue("RTC room ID")
        val userId = userId.trim().requireValue("RTC user ID")
        val token = token.trim().requireValue("RTC room token")
        return RoomJoinConfiguration(roomId, userId, token)
    }

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

    private fun String.requireValue(name: String): String = takeIf(String::isNotEmpty)
        ?: throw rtcError("$name cannot be empty")

    private fun normalizeRemoteUserId(userId: String): String =
        userId.trim().takeIf(String::isNotEmpty) ?: throw XmaxError(
            code = XmaxErrorCode.INVALID_CONFIGURATION,
            message = "RTC user ID cannot be empty",
        )

    private fun requireActiveRemoteStream(stream: RemoteStream) {
        val isActive = synchronized(stateLock) {
            activeRoom?.roomId == stream.roomId
        }
        if (!isActive) throw rtcError("RTC remote stream is not in the active room")
    }

    private data class RoomContext(
        val roomId: String,
        val room: RtcPlatformRoom,
    )

    private data class PendingJoin(
        val id: UUID = UUID.randomUUID(),
        val context: RoomContext,
        val result: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class RoomResources(
        val active: RoomContext?,
        val pending: PendingJoin?,
    )

    private companion object {
        const val JOIN_TIMEOUT_MILLIS: Long = 15_000L

        fun rtcError(message: String): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = message,
        )

        fun rtcResultError(operation: String, result: Number): XmaxError = rtcError(
            "RTC $operation failed: $result",
        )

        fun rtcOperationError(operation: String, cause: Throwable): XmaxError = XmaxError(
            code = XmaxErrorCode.RTC_ERROR,
            message = "RTC $operation failed: ${cause.message ?: cause.javaClass.simpleName}",
            cause = cause,
        )

        fun cancelledError(operation: String): XmaxError = XmaxError(
            code = XmaxErrorCode.CANCELLED,
            message = "$operation was cancelled",
        )
    }
}
