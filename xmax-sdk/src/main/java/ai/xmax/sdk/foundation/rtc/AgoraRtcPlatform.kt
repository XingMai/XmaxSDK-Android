package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.stream.room.RoomMessageCodec
import android.content.Context
import android.os.SystemClock
import android.view.View
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.DataStreamConfig
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.RtcConnection
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtc2.RtcEngineEx
import io.agora.rtc2.UserInfo
import io.agora.rtc2.audio.AudioTrackConfig
import io.agora.rtc2.video.CameraCapturerConfiguration
import io.agora.rtc2.video.IVideoFrameObserver
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 创建声网引擎；固定 App ID 允许创建会话前先进行本地预览。
 */
internal fun createAgoraRtcEngine(context: Context, appId: String): RtcPlatform? =
    appId.trim().takeIf(String::isNotEmpty)?.let { AgoraRtcPlatform(context, it) }

internal fun destroyAgoraRtcEngine() = RtcEngine.destroy()

/**
 * 声网状态位中的 2 表示视频被静音，不能当作“正在发布”。
 */
internal fun isAgoraVideoPublished(state: Int): Boolean =
    state and Constants.USER_STATE_MUTE_VIDEO == 0 &&
        state and Constants.USER_STATE_ENABLE_VIDEO != 0 &&
        state and Constants.USER_STATE_ENABLE_LOCAL_VIDEO != 0

/**
 * 供应商实现只存在于 Foundation；公开层继续使用中性轨道及媒体配置。
 */
private class AgoraRtcPlatform(context: Context, private val appId: String) : RtcPlatform {
    private val listener = AtomicReference<RtcEventListener?>()
    private val qualityListener = AtomicReference<RtcQualityListener?>()
    private val previewListener = AtomicReference<(() -> Unit)?>()
    private val activeRoom = AtomicReference<AgoraRoom?>()
    private val frameSinks = ConcurrentHashMap<String, RtcRemoteVideoSink>()
    private var externalVideo = false
    private var externalAudio = false
    private var audioTrackId = -1
    private val awaitingCameraFrame = AtomicBoolean(false)
    private var cameraPosition = CameraPosition.FRONT
    private var captureFormat = CameraCapturerConfiguration.CaptureFormat(1024, 1920, 30)
    private var cameraStarted = false
    private var videoEncodingConfiguration: VideoEncodingConfiguration? = null
    private val engine: RtcEngineEx

    init {
        engine = RtcEngine.create(RtcEngineConfig().apply {
            mContext = context.applicationContext
            mAppId = this@AgoraRtcPlatform.appId
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            mEventHandler = object : IRtcEngineEventHandler() {
                override fun onFirstLocalVideoFrame(source: Constants.VideoSourceType, width: Int, height: Int, elapsed: Int) {
                    previewListener.get()?.invoke()
                }
            }
        }) as RtcEngineEx
        try {
            checkRtc(engine.enableVideo(), "enableVideo")
            checkRtc(engine.enableLocalVideo(false), "disable initial camera capture")
            checkRtc(engine.enableLocalAudio(false), "disable initial microphone capture")
            checkRtc(engine.registerVideoFrameObserver(object : IVideoFrameObserver {
                override fun getVideoFrameProcessMode() = IVideoFrameObserver.PROCESS_MODE_READ_ONLY
                override fun getVideoFormatPreference() = IVideoFrameObserver.VIDEO_PIXEL_I420
                override fun getRotationApplied() = false
                override fun getMirrorApplied() = false
                override fun getObservedFramePosition() = IVideoFrameObserver.POSITION_PRE_RENDERER or IVideoFrameObserver.POSITION_POST_CAPTURER
                override fun onCaptureVideoFrame(source: Int, frame: io.agora.base.VideoFrame): Boolean {
                    if (awaitingCameraFrame.compareAndSet(true, false)) previewListener.get()?.invoke()
                    return true
                }
                override fun onPreEncodeVideoFrame(source: Int, frame: io.agora.base.VideoFrame) = true
                override fun onMediaPlayerVideoFrame(frame: io.agora.base.VideoFrame, playerId: Int) = true
                override fun onRenderVideoFrame(channel: String, uid: Int, frame: io.agora.base.VideoFrame): Boolean {
                    val room = activeRoom.get()?.takeIf { !it.closed && it.roomId == channel } ?: return true
                    val sink = frameSinks[room.userId(uid)] ?: return true
                    try {
                        sink.onFrame(AgoraRemoteVideoFrame(frame))
                        sink.onFirstFrame(frame.rotatedWidth, frame.rotatedHeight)
                    } catch (error: Exception) {
                        sink.onError(XmaxError(XmaxErrorCode.RTC_ERROR, "Remote video frame processing failed", cause = error))
                    }
                    return true
                }
            }), "registerVideoFrameObserver")
        } catch (error: Throwable) {
            RtcEngine.destroy()
            throw error
        }
    }

    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int {
        val result = engine.setVideoEncoderConfiguration(
            RtcVideoConverter.makeEncoderConfiguration(
                configuration,
                cameraPosition = cameraPosition.takeUnless { externalVideo },
            ),
        )
        if (result >= 0) videoEncodingConfiguration = configuration
        return result
    }

    /**
     * 切换摄像头或输入源后更新编码镜像，保留已配置的尺寸、帧率及码率。
     */
    private fun updateVideoEncoding(): Int =
        videoEncodingConfiguration?.let(::configureVideoEncoding) ?: 0

    override fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?) {
        // Agora 生成协议不依赖逐帧 SEI；供帧失败返回值沿用原接口语义，不升级为异常。
        engine.pushExternalVideoFrame(RtcVideoConverter.convertFrame(frame))
    }

    override fun pushExternalAudioFrame(frame: AudioFrame) {
        if (audioTrackId < 0) return
        engine.pushExternalAudioFrame(
            RtcAudioConverter.convertFrame(frame), frame.timestampUs / 1_000L,
            48_000, 1, Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE, audioTrackId,
        )
    }

    override fun useExternalVideoSource(): Int {
        val result = engine.setExternalVideoSource(true, false, Constants.ExternalVideoSourceType.VIDEO_FRAME)
        if (result < 0) return result
        externalVideo = true
        return updateVideoEncoding()
    }

    override fun startExternalAudioSource(): Int {
        if (audioTrackId >= 0) return 0
        val id = engine.createCustomAudioTrack(Constants.AudioTrackType.AUDIO_TRACK_DIRECT, AudioTrackConfig().apply {
            enableLocalPlayback = false
            enableAudioProcessing = false
        })
        if (id < 0) return id
        audioTrackId = id
        externalAudio = true
        return 0
    }

    override fun stopExternalAudioSource(): Int {
        if (audioTrackId < 0) return 0
        val result = engine.destroyCustomAudioTrack(audioTrackId)
        if (result >= 0) {
            audioTrackId = -1
            externalAudio = false
        }
        return result
    }

    override fun startAudioCapture(): Int {
        if (externalAudio) {
            val result = stopExternalAudioSource()
            if (result < 0) return result
        }
        return engine.enableLocalAudio(true)
    }

    override fun stopAudioCapture(): Int = engine.enableLocalAudio(false)

    override fun startVideoCapture(width: Int, height: Int, frameRate: Int): Int {
        val source = engine.setExternalVideoSource(false, false, Constants.ExternalVideoSourceType.VIDEO_FRAME)
        if (source < 0) return source
        externalVideo = false
        val encoding = updateVideoEncoding()
        if (encoding < 0) return encoding
        captureFormat = CameraCapturerConfiguration.CaptureFormat(width, height, frameRate)
        val config = engine.setCameraCapturerConfiguration(cameraConfiguration())
        if (config < 0) return config
        val enable = engine.enableLocalVideo(true)
        if (enable < 0) return enable
        awaitingCameraFrame.set(true)
        val result = engine.startPreview()
        if (result >= 0) cameraStarted = true
        return result
    }

    override fun stopVideoCapture(): Int {
        cameraStarted = false
        awaitingCameraFrame.set(false)
        val preview = engine.stopPreview()
        val capture = engine.enableLocalVideo(false)
        return if (preview < 0) preview else capture
    }

    override fun switchCamera(position: CameraPosition): Int {
        if (cameraStarted && cameraPosition != position) {
            awaitingCameraFrame.set(true)
            val result = engine.switchCamera()
            if (result < 0) return result
        }
        cameraPosition = position
        if (!cameraStarted) {
            val result = engine.setCameraCapturerConfiguration(cameraConfiguration())
            if (result < 0) return result
        }
        return updateVideoEncoding()
    }

    private fun cameraConfiguration() = CameraCapturerConfiguration(
        if (cameraPosition == CameraPosition.FRONT) CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_FRONT
        else CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR,
        captureFormat,
    ).apply { followEncodeDimensionRatio = true }

    override fun bindLocalVideo(view: View, contentMode: VideoContentMode): Int =
        engine.setupLocalVideo(RtcVideoConverter.makeLocalCanvas(view, contentMode))

    override fun unbindLocalVideo(): Int = engine.setupLocalVideo(RtcVideoConverter.makeLocalCanvas(null, VideoContentMode.FILL))

    override fun bindRemoteVideo(userId: String, view: View, contentMode: VideoContentMode): Int {
        val room = requireNotNull(activeRoom.get())
        return engine.setupRemoteVideoEx(RtcVideoConverter.makeCanvas(view, contentMode, room.uid(userId)), room.connection())
    }

    override fun unbindRemoteVideo(userId: String): Int {
        val room = activeRoom.get() ?: return 0
        return engine.setupRemoteVideoEx(RtcVideoConverter.makeCanvas(null, VideoContentMode.FILL, room.uid(userId)), room.connection())
    }

    override fun setCameraPreviewReadyListener(listener: (() -> Unit)?) { previewListener.set(listener) }

    override fun setRemoteVideoFrameListener(streamId: String, listener: RtcRemoteVideoSink?): Int {
        if (listener == null) frameSinks.remove(streamId) else frameSinks[streamId] = listener
        return 0
    }

    override fun setRemoteAudioVolume(streamId: String, volume: Int): Int {
        val room = requireNotNull(activeRoom.get())
        return engine.adjustUserPlaybackSignalVolumeEx(room.uid(streamId), volume, room.connection())
    }

    override fun setEventListener(listener: RtcEventListener?) { this.listener.set(listener) }
    override fun setQualityListener(listener: RtcQualityListener?) { qualityListener.set(listener) }
    override fun createRoom(roomId: String): RtcRoom = AgoraRoom(roomId)

    /**
     * 每轮入房独立事件桥、发送队列和身份映射；离房后拒绝所有迟到回调。
     */
    private inner class AgoraRoom(val roomId: String) : RtcRoom {
        @Volatile var closed = false
            private set
        private var localUid = 0
        private var localAccount = ""
        private var dataStreamId = -1
        private var stateListener: ((String, Boolean, String?) -> Unit)? = null
        private val accounts = ConcurrentHashMap<Int, String>()
        private val publications = ConcurrentHashMap<Int, Boolean>()
        private val codec = RoomMessageCodec()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val outgoing = Channel<Pair<List<String>, CompletableDeferred<Unit>>>(64)
        private var lastSend: CompletableDeferred<Unit>? = null
        private val pendingSends = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()
        @Volatile private var joined = false
        private val sendLock = Any()

        fun connection() = RtcConnection(roomId, localUid)
        fun userId(uid: Int): String = accounts[uid] ?: Integer.toUnsignedString(uid)
        fun uid(account: String): Int {
            accounts.entries.firstOrNull { it.value == account }?.let { return it.key }
            val info = UserInfo()
            if (engine.getUserInfoByUserAccountEx(account, info, connection()) == 0) {
                accounts[info.uid] = account
                return info.uid
            }
            throw XmaxError(XmaxErrorCode.RTC_ERROR, "RTC user account is not available: $account")
        }

        private fun isCurrent() = !closed && activeRoom.get() === this

        private fun publish(uid: Int, published: Boolean) {
            if (!isCurrent()) return
            publications[uid] = published
            // Web 使用字符串 user account；映射到达之前不能把 numeric uid 当作机器人身份。
            if (accounts[uid] != null) listener.get()?.onRemoteVideoPublished(RemoteStream(roomId, userId(uid)), published)
        }

        private val events = object : IRtcEngineEventHandler() {
            override fun onJoinChannelSuccess(channel: String, uid: Int, elapsed: Int) {
                if (!isCurrent()) return
                localUid = uid
                dataStreamId = engine.createDataStreamEx(DataStreamConfig().apply {
                    ordered = true
                    syncWithAudio = false
                }, connection())
                if (dataStreamId < 0) {
                    stateListener?.invoke(roomId, false, "AGORA_SEND_FAILED")
                    return
                }
                joined = true
                stateListener?.invoke(roomId, true, null)
            }

            override fun onUserInfoUpdated(uid: Int, info: UserInfo) {
                if (!isCurrent()) return
                val account = info.userAccount?.takeIf(String::isNotBlank) ?: return
                accounts[uid] = account
                publications[uid]?.let { publish(uid, it) }
            }

            override fun onUserJoined(uid: Int, elapsed: Int) {
                val info = UserInfo()
                if (isCurrent() && engine.getUserInfoByUidEx(uid, info, connection()) == 0) onUserInfoUpdated(uid, info)
            }

            override fun onUserOffline(uid: Int, reason: Int) {
                publish(uid, false)
                accounts.remove(uid)
                publications.remove(uid)
            }

            override fun onUserMuteVideo(uid: Int, muted: Boolean) = publish(uid, !muted)
            override fun onUserEnableVideo(uid: Int, enabled: Boolean) = publish(uid, enabled)
            override fun onUserEnableLocalVideo(uid: Int, enabled: Boolean) = publish(uid, enabled)
            override fun onUserStateChanged(uid: Int, state: Int) = publish(uid, isAgoraVideoPublished(state))

            override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                if (state == Constants.REMOTE_VIDEO_STATE_STARTING || state == Constants.REMOTE_VIDEO_STATE_DECODING) publish(uid, true)
                if (reason == Constants.REMOTE_VIDEO_STATE_REASON_REMOTE_MUTED || reason == Constants.REMOTE_VIDEO_STATE_REASON_REMOTE_OFFLINE) publish(uid, false)
            }

            override fun onConnectionStateChanged(state: Int, reason: Int) {
                if (!isCurrent()) return
                if (state == Constants.CONNECTION_STATE_FAILED) stateListener?.invoke(roomId, false, "AGORA_CONNECTION_FAILED")
            }

            override fun onRequestToken() {
                if (isCurrent()) stateListener?.invoke(roomId, false, "TOKEN_EXPIRED")
            }

            override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray) {
                if (!isCurrent()) return
                codec.decode(userId(uid), data.toString(Charsets.UTF_8))?.let {
                    listener.get()?.onUserMessageReceived(RemoteStream(roomId, userId(uid)), it)
                }
            }

            override fun onNetworkQuality(uid: Int, txQuality: Int, rxQuality: Int) {
                if (isCurrent() && uid == 0) qualityListener.get()?.onNetworkQuality(
                    RtcQualityConverter.convertLevel(txQuality), RtcQualityConverter.convertLevel(rxQuality),
                )
            }

            override fun onLocalVideoStats(source: Constants.VideoSourceType, stats: LocalVideoStats) {
                if (!isCurrent()) return
            val statistics = RtcQualityConverter.uplinkVideoStatistics(stats)
            RtcStatsLogger.logUplinkVideoStatistics(statistics)
            qualityListener.get()?.onUplinkVideoStatistics(statistics)
                qualityListener.get()?.onPerformanceAlarm(
                    limited = stats.qualityAdaptIndication == 1,
                    suggestedWidth = stats.encodedFrameWidth,
                    suggestedHeight = stats.encodedFrameHeight,
                    suggestedFrameRate = stats.encoderOutputFrameRate,
                )
            }

            override fun onRemoteVideoStats(stats: RemoteVideoStats) {
                if (!isCurrent()) return
                val account = accounts[stats.uid] ?: return
            val statistics = RtcQualityConverter.downlinkVideoStatistics(account, stats)
            RtcStatsLogger.logDownlinkVideoStatistics(statistics)
            qualityListener.get()?.onDownlinkVideoStatistics(statistics)
            }
        }

        override fun setEventListener(listener: (String, Boolean, String?) -> Unit): Int {
            stateListener = listener
            return 0
        }

        override fun join(configuration: RoomJoinConfiguration): Int {
            if (configuration.appId != appId) return -2
            if (!activeRoom.compareAndSet(null, this)) return -5
            localAccount = configuration.userId
            val result = engine.joinChannelWithUserAccountEx(configuration.token, roomId, configuration.userId, ChannelMediaOptions().apply {
                channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
                publishCameraTrack = false
                publishCustomVideoTrack = false
                publishMicrophoneTrack = false
                publishCustomAudioTrack = false
                autoSubscribeAudio = false
                autoSubscribeVideo = false
            }, events)
            if (result >= 0) scope.launch {
                try {
                    sendMessages()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    pendingSends.forEach { it.completeExceptionally(error) }
                    if (isCurrent()) stateListener?.invoke(roomId, false, "AGORA_SEND_FAILED")
                }
            }
            return result
        }

        override fun publishLocalVideo(publish: Boolean): Int = engine.updateChannelMediaOptionsEx(ChannelMediaOptions().apply {
            publishCameraTrack = publish && !externalVideo
            publishCustomVideoTrack = publish && externalVideo
            if (externalVideo) customVideoTrackId = 0
        }, connection())

        override fun publishLocalAudio(publish: Boolean): Int = engine.updateChannelMediaOptionsEx(ChannelMediaOptions().apply {
            publishMicrophoneTrack = publish && !externalAudio
            publishCustomAudioTrack = publish && externalAudio
            if (externalAudio) publishCustomAudioTrackId = audioTrackId
        }, connection())

        override fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int =
            engine.muteRemoteVideoStreamEx(uid(userId), !subscribe, connection())

        override fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int =
            engine.muteRemoteAudioStreamEx(uid(userId), !subscribe, connection())

        override fun resolveRemoteStreamId(userId: String) = userId

        override fun sendRoomMessage(message: String): Long {
            if (!isCurrent() || !joined) return -3
            val packets = codec.encode(message)
            return synchronized(sendLock) {
                if (!isCurrent()) return -3
                val completion = CompletableDeferred<Unit>()
                pendingSends.add(completion)
                if (outgoing.trySend(packets to completion).isSuccess) {
                    lastSend = completion
                    0L
                } else {
                    pendingSends.remove(completion)
                    completion.cancel()
                    -12L
                }
            }
        }

        override suspend fun awaitPendingMessages() {
            val pending = synchronized(sendLock) { lastSend }
            pending?.await()
        }

        /**
         * 声网 DataStream 每秒最多 30 包/6 KB；整组入队，避免分片互相穿插。
         */
        private suspend fun sendMessages() {
            val sent = ArrayDeque<Pair<Long, Int>>()
            for ((packets, completion) in outgoing) {
                for (packet in packets) {
                    val bytes = packet.toByteArray(Charsets.UTF_8)
                    while (isCurrent()) {
                        val now = SystemClock.elapsedRealtime()
                        while (sent.isNotEmpty() && now - sent.first().first >= 1_000) sent.removeFirst()
                        if (sent.size < 28 && sent.sumOf { it.second } + bytes.size <= 5_800) break
                        delay((1_000 - (now - sent.first().first)).coerceAtLeast(1))
                    }
                    if (!isCurrent()) return
                    val result = synchronized(sendLock) {
                        if (!isCurrent()) return
                        engine.sendStreamMessageEx(dataStreamId, bytes, connection())
                    }
                    if (result < 0) {
                        val error = XmaxError(XmaxErrorCode.RTC_ERROR, "Agora room signaling failed: $result")
                        pendingSends.forEach { it.completeExceptionally(error) }
                        stateListener?.invoke(roomId, false, "AGORA_SEND_FAILED")
                        return
                    }
                    sent.addLast(SystemClock.elapsedRealtime() to bytes.size)
                }
                completion.complete(Unit)
                pendingSends.remove(completion)
            }
        }

        override fun leave(): Int = synchronized(sendLock) {
            if (closed) return 0
            closed = true
            joined = false
            scope.cancel()
            outgoing.close()
            pendingSends.forEach { it.cancel(CancellationException("RTC room was closed")) }
            pendingSends.clear()
            codec.clear()
            frameSinks.clear()
            activeRoom.compareAndSet(this, null)
            engine.leaveChannelWithUserAccountEx(roomId, localAccount)
        }

        override fun destroy() {
            leave()
            stateListener = null
            accounts.clear()
            publications.clear()
        }
    }

    private fun checkRtc(code: Int, operation: String) {
        check(code >= 0) { "Agora $operation failed: $code" }
    }
}
