//
//  AgoraRtcPlatform.kt
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
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 * @param appId RTC 应用标识，不是应用证书或房间 Token。
 * @return 创建的 RTC 平台引擎；App ID 为空时为 null。
 */
internal fun createAgoraRtcEngine(context: Context, appId: String): RtcPlatform? =
    appId.trim().takeIf(String::isNotEmpty)?.let { AgoraRtcPlatform(context, it) }

/**
 * 释放共享 Agora 引擎及其底层资源。
 */
internal fun destroyAgoraRtcEngine() = RtcEngine.destroy()

/**
 * 判断用户的视频模块和采集均已启用且未静音，确定视频是否可订阅。
 *
 * @param state 当前状态快照。
 * @return 远端视频可订阅时为 true。
 */
internal fun isAgoraVideoPublished(state: Int): Boolean =
    state and Constants.USER_STATE_MUTE_VIDEO == 0 &&
        state and Constants.USER_STATE_ENABLE_VIDEO != 0 &&
        state and Constants.USER_STATE_ENABLE_LOCAL_VIDEO != 0

/**
 * 提供 Agora 音视频采集、编码、房间连接和远端渲染能力。
 *
 * @param context 访问 Android 系统服务或媒体资源所需的上下文。
 * @param appId RTC 应用标识，不是应用证书或房间 Token。
 */
private class AgoraRtcPlatform(context: Context, private val appId: String) : RtcPlatform {
    /**
     * RTC 事件、质量及预览就绪监听器。
     */
    private val listener = AtomicReference<RtcEventListener?>()
    private val qualityListener = AtomicReference<RtcQualityListener?>()
    private val previewListener = AtomicReference<(() -> Unit)?>()

    /**
     * 当前 RTC 房间及远端视频帧接收器。
     */
    private val activeRoom = AtomicReference<AgoraRoom?>()
    private val frameSinks = ConcurrentHashMap<String, RtcRemoteVideoSink>()

    /**
     * 外部音视频输入开关及自定义音频轨道。
     */
    private var externalVideo = false
    private var externalAudio = false
    private var audioTrackId = -1

    /**
     * 相机采集方向、规格及首帧等待状态。
     */
    private val awaitingCameraFrame = AtomicBoolean(false)
    private var cameraPosition = CameraPosition.FRONT
    private var captureFormat = CameraCapturerConfiguration.CaptureFormat(1024, 1920, 30)
    private var cameraStarted = false

    /**
     * 待应用的上行编码配置。
     */
    private var videoEncodingConfiguration: VideoEncodingConfiguration? = null

    /**
     * 底层 Agora RTC 引擎。
     */
    private val engine: RtcEngineEx

    init {
        engine = RtcEngine.create(RtcEngineConfig().apply {
            mContext = context.applicationContext
            mAppId = this@AgoraRtcPlatform.appId
            mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            mEventHandler = object : IRtcEngineEventHandler() {
                /**
                 * 通知本地视频源已产生首帧，供预览就绪判定使用。
                 *
                 * @param source RTC 视频采集来源标识。
                 * @param width 画面宽度，单位为像素。
                 * @param height 画面高度，单位为像素。
                 * @param elapsed RTC 事件报告的经过时间，单位为毫秒。
                 */
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
                /**
                 * 指定视频帧观察器只读取帧数据，不修改 RTC 原始帧。
                 *
                 * @return 只读视频帧处理模式。
                 */
                override fun getVideoFrameProcessMode() = IVideoFrameObserver.PROCESS_MODE_READ_ONLY

                /**
                 * 指定视频帧观察器接收的像素格式。
                 *
                 * @return I420 像素格式。
                 */
                override fun getVideoFormatPreference() = IVideoFrameObserver.VIDEO_PIXEL_I420

                /**
                 * 指定观察器收到的帧是否由 RTC 预先应用旋转。
                 *
                 * @return false，旋转信息保留在帧元数据中。
                 */
                override fun getRotationApplied() = false

                /**
                 * 指定观察器收到的帧是否由 RTC 预先应用镜像。
                 *
                 * @return false，不对回调帧预先应用镜像。
                 */
                override fun getMirrorApplied() = false

                /**
                 * 指定观察器接收采集后及远端渲染前的视频帧。
                 *
                 * @return 采集后与渲染前观察位置的位掩码。
                 */
                override fun getObservedFramePosition() = IVideoFrameObserver.POSITION_PRE_RENDERER or IVideoFrameObserver.POSITION_POST_CAPTURER

                /**
                 * 确认相机已输出新帧，并允许帧继续进入 RTC 处理流程。
                 *
                 * @param source RTC 视频采集来源标识。
                 * @param frame 待处理、显示或提交的视频帧。
                 * @return true，允许 RTC 继续使用该帧。
                 */
                override fun onCaptureVideoFrame(source: Int, frame: io.agora.base.VideoFrame): Boolean {
                    if (awaitingCameraFrame.compareAndSet(true, false)) previewListener.get()?.invoke()
                    return true
                }

                /**
                 * 允许编码前的视频帧继续进入编码流程。
                 *
                 * @param source RTC 视频采集来源标识。
                 * @param frame 待处理、显示或提交的视频帧。
                 * @return true，允许 RTC 继续编码。
                 */
                override fun onPreEncodeVideoFrame(source: Int, frame: io.agora.base.VideoFrame) = true

                /**
                 * 允许播放器视频帧继续进入 RTC 处理流程。
                 *
                 * @param frame 待处理、显示或提交的视频帧。
                 * @param playerId RTC 媒体播放器标识。
                 * @return true，允许 RTC 继续处理播放器帧。
                 */
                override fun onMediaPlayerVideoFrame(frame: io.agora.base.VideoFrame, playerId: Int) = true

                /**
                 * 将当前远端用户的渲染帧交给已注册的视频帧监听器。
                 *
                 * @param channel RTC 房间名称。
                 * @param uid RTC 分配的数字用户标识。
                 * @param frame 待处理、显示或提交的视频帧。
                 * @return true，允许 RTC 继续渲染该帧。
                 */
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

    /**
     * 应用主视频流编码配置。
     *
     * @param configuration 上行视频的尺寸、帧率、码率及编码偏好。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration): Int {
        val encoderConfiguration = RtcVideoConverter.makeEncoderConfiguration(
            configuration,
            cameraPosition = cameraPosition.takeUnless { externalVideo },
        )
        val connection = if (externalVideo) activeRoom.get()?.videoEncodingConnection() else null
        val result = if (connection != null) {
            engine.setVideoEncoderConfigurationEx(encoderConfiguration, connection)
        } else {
            engine.setVideoEncoderConfiguration(encoderConfiguration)
        }

        if (result >= 0) videoEncodingConfiguration = configuration
        return result
    }

    /**
     * 切换摄像头或输入源后更新编码镜像，保留已配置的尺寸、帧率及码率。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    private fun updateVideoEncoding(): Int =
        videoEncodingConfiguration?.let(::configureVideoEncoding) ?: 0

    /**
     * 推送一帧外部视频数据及其可选 SEI。
     *
     * @param frame 待处理、显示或提交的视频帧。
     * @param seiData 附加视频元数据；null 表示不附加，当前 Agora 适配不使用该字段。
     */
    override fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?) {
        /**
         * 提交外部视频帧；当前协议不附加逐帧 SEI，RTC 拒收单帧时不终止媒体源。
         */
        engine.pushExternalVideoFrame(RtcVideoConverter.convertFrame(frame))
    }

    /**
     * 推送一帧 10 ms PCM 外部音频数据。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     */
    override fun pushExternalAudioFrame(frame: AudioFrame) {
        if (audioTrackId < 0) return
        engine.pushExternalAudioFrame(
            RtcAudioConverter.convertFrame(frame), frame.timestampUs / 1_000L,
            48_000, 1, Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE, audioTrackId,
        )
    }

    /**
     * 将主视频切换为 RTC 外部帧输入。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun useExternalVideoSource(): Int {
        val result = engine.setExternalVideoSource(true, false, Constants.ExternalVideoSourceType.VIDEO_FRAME)
        if (result < 0) return result
        externalVideo = true
        return updateVideoEncoding()
    }

    /**
     * 将主音频切换为 RTC 外部 PCM 输入。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
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

    /**
     * 停止 RTC 外部音频输入。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun stopExternalAudioSource(): Int {
        if (audioTrackId < 0) return 0
        val result = engine.destroyCustomAudioTrack(audioTrackId)
        if (result >= 0) {
            audioTrackId = -1
            externalAudio = false
        }
        return result
    }

    /**
     * 切换到 RTC 内部音频源并启动麦克风采集。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun startAudioCapture(): Int {
        if (externalAudio) {
            val result = stopExternalAudioSource()
            if (result < 0) return result
        }
        return engine.enableLocalAudio(true)
    }

    /**
     * 停止 RTC 内部麦克风采集。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun stopAudioCapture(): Int = engine.enableLocalAudio(false)

    /**
     * 按指定格式启动 RTC 内部摄像头采集。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     * @param frameRate 视频帧率，单位为 fps。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
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

    /**
     * 停止 RTC 内部摄像头采集。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun stopVideoCapture(): Int {
        cameraStarted = false
        awaitingCameraFrame.set(false)
        val preview = engine.stopPreview()
        val capture = engine.enableLocalVideo(false)
        return if (preview < 0) preview else capture
    }

    /**
     * 切换 RTC 内部采集使用的摄像头。
     *
     * @param position 需要使用的前置或后置摄像头。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
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

    /**
     * 生成当前摄像头方向、采集尺寸和帧率对应的采集配置。
     *
     * @return 当前相机方向及采集规格。
     */
    private fun cameraConfiguration() = CameraCapturerConfiguration(
        if (cameraPosition == CameraPosition.FRONT) CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_FRONT
        else CameraCapturerConfiguration.CAMERA_DIRECTION.CAMERA_REAR,
        captureFormat,
    ).apply { followEncodeDimensionRatio = true }

    /**
     * 将本地视频绑定到渲染视图。
     *
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun bindLocalVideo(view: View, contentMode: VideoContentMode): Int =
        engine.setupLocalVideo(RtcVideoConverter.makeLocalCanvas(view, contentMode))

    /**
     * 解除本地视频与渲染视图的绑定。
     *
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun unbindLocalVideo(): Int = engine.setupLocalVideo(RtcVideoConverter.makeLocalCanvas(null, VideoContentMode.FILL))

    /**
     * 将指定远端用户的视频绑定到视图并应用显示模式。
     *
     * @param userId 房间中的业务用户标识。
     * @param view 需要绑定或解除绑定的显示视图。
     * @param contentMode 视频等比适配或等比裁切的显示方式。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun bindRemoteVideo(userId: String, view: View, contentMode: VideoContentMode): Int {
        val room = requireNotNull(activeRoom.get())
        return engine.setupRemoteVideoEx(RtcVideoConverter.makeCanvas(view, contentMode, room.uid(userId)), room.connection())
    }

    /**
     * 解除指定远端用户的视频视图绑定。
     *
     * @param userId 房间中的业务用户标识。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun unbindRemoteVideo(userId: String): Int {
        val room = activeRoom.get() ?: return 0
        return engine.setupRemoteVideoEx(RtcVideoConverter.makeCanvas(null, VideoContentMode.FILL, room.uid(userId)), room.connection())
    }

    /**
     * 替换相机预览就绪监听器；null 表示注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setCameraPreviewReadyListener(listener: (() -> Unit)?) { previewListener.set(listener) }

    /**
     * 替换指定远端流的视频帧监听器。
     *
     * @param streamId 远端媒体流或数据通道的标识。
     * @param listener 接收对应事件的监听器；null 表示注销。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun setRemoteVideoFrameListener(streamId: String, listener: RtcRemoteVideoSink?): Int {
        if (listener == null) frameSinks.remove(streamId) else frameSinks[streamId] = listener
        return 0
    }

    /**
     * 设置指定远端用户的音频播放音量。
     *
     * @param streamId 远端媒体流或数据通道的标识。
     * @param volume 远端音频播放音量，范围为 0 到 100。
     * @return RTC 操作返回码；非负表示已接受，负值表示失败。
     */
    override fun setRemoteAudioVolume(streamId: String, volume: Int): Int {
        val room = requireNotNull(activeRoom.get())
        return engine.adjustUserPlaybackSignalVolumeEx(room.uid(streamId), volume, room.connection())
    }

    /**
     * 设置 RTC 事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setEventListener(listener: RtcEventListener?) { this.listener.set(listener) }

    /**
     * 设置 RTC 质量事件监听器，传入空值时清除。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    override fun setQualityListener(listener: RtcQualityListener?) { qualityListener.set(listener) }

    /**
     * 创建用于加入指定 RTC 房间的操作对象。
     *
     * @param roomId RTC 房间标识。
     * @return 指定房间的 RTC 操作对象。
     */
    override fun createRoom(roomId: String): RtcRoom = AgoraRoom(roomId)

    /**
     * 每轮入房独立事件桥、发送队列和身份映射；离房后拒绝所有迟到回调。
     *
     * @param roomId RTC 房间标识。
     */
    private inner class AgoraRoom(val roomId: String) : RtcRoom {
        /**
         * 房间生命周期与本地用户身份。
         */
        @Volatile var closed = false
            private set
        private var localUid = 0
        private var localAccount = ""

        /**
         * 数据通道及房间事件通知。
         */
        private var dataStreamId = -1
        private var stateListener: ((String, Boolean, String?) -> Unit)? = null

        /**
         * 远端用户账号映射与视频发布状态。
         */
        private val accounts = ConcurrentHashMap<Int, String>()
        private val publications = ConcurrentHashMap<Int, Boolean>()

        /**
         * 业务信令的分片编解码及有序发送。
         */
        private val codec = RoomMessageCodec()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val outgoing = Channel<Pair<List<String>, CompletableDeferred<Unit>>>(64)
        private var lastSend: CompletableDeferred<Unit>? = null
        private val pendingSends = ConcurrentHashMap.newKeySet<CompletableDeferred<Unit>>()

        /**
         * 房间加入确认状态。
         */
        @Volatile private var joined = false

        /**
         * 信令发送状态的并发保护。
         */
        private val sendLock = Any()

        /**
         * 取得当前房间对应的 Agora 连接标识。
         *
         * @return 当前房间对应的 RTC 连接标识。
         */
        fun connection() = RtcConnection(roomId, localUid)

        /**
         * 返回当前已连接房间的外部视频编码配置作用域。
         *
         * @return 当前已连接房间对应的 RTC 连接；尚未连接时为 null。
         */
        fun videoEncodingConnection(): RtcConnection? =
            if (joined && isCurrent()) connection() else null

        /**
         * 将 RTC 数字 UID 转换为业务使用的用户账号。
         *
         * @param uid RTC 分配的数字用户标识。
         * @return RTC 数字 UID 对应的业务用户账号。
         */
        fun userId(uid: Int): String = accounts[uid] ?: Integer.toUnsignedString(uid)

        /**
         * 将业务用户账号转换为 RTC 数字 UID。
         *
         * @param account RTC 用户账号字符串。
         * @return 对应的 RTC 数字用户标识。
         */
        fun uid(account: String): Int {
            accounts.entries.firstOrNull { it.value == account }?.let { return it.key }
            val info = UserInfo()
            if (engine.getUserInfoByUserAccountEx(account, info, connection()) == 0) {
                accounts[info.uid] = account
                return info.uid
            }
            throw XmaxError(XmaxErrorCode.RTC_ERROR, "RTC user account is not available: $account")
        }

        /**
         * 判断房间是否仍是当前活动房间且尚未关闭。
         *
         * @return 当前房间仍有效时为 true。
         */
        private fun isCurrent() = !closed && activeRoom.get() === this

        /**
         * 更新远端用户的视频发布状态并通知订阅方。
         *
         * @param uid RTC 分配的数字用户标识。
         * @param published 远端视频是否处于发布状态。
         */
        private fun publish(uid: Int, published: Boolean) {
            if (!isCurrent()) return
            publications[uid] = published
            /**
             * 远端业务身份使用用户账号；账号映射就绪后才确认生成结果发布者。
             */
            if (accounts[uid] != null) listener.get()?.onRemoteVideoPublished(RemoteStream(roomId, userId(uid)), published)
        }

        /**
         * 房间级 RTC 回调接收器。
         */
        private val events = object : IRtcEngineEventHandler() {
            /**
             * 确认本次 RTC 进房成功并通知等待方。
             *
             * @param channel RTC 房间名称。
             * @param uid RTC 分配的数字用户标识。
             * @param elapsed RTC 事件报告的经过时间，单位为毫秒。
             */
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

            /**
             * 更新 RTC 数字 UID 与业务用户账号之间的映射。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param info RTC 数字 UID 对应的用户账号信息。
             */
            override fun onUserInfoUpdated(uid: Int, info: UserInfo) {
                if (!isCurrent()) return
                val account = info.userAccount?.takeIf(String::isNotBlank) ?: return
                accounts[uid] = account
                publications[uid]?.let { publish(uid, it) }
            }

            /**
             * 记录远端用户加入房间。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param elapsed RTC 事件报告的经过时间，单位为毫秒。
             */
            override fun onUserJoined(uid: Int, elapsed: Int) {
                val info = UserInfo()
                if (isCurrent() && engine.getUserInfoByUidEx(uid, info, connection()) == 0) onUserInfoUpdated(uid, info)
            }

            /**
             * 清除离房用户的发布状态并通知订阅方。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param reason 引起状态变化或失败的原因。
             */
            override fun onUserOffline(uid: Int, reason: Int) {
                publish(uid, false)
                accounts.remove(uid)
                publications.remove(uid)
            }

            /**
             * 根据远端视频静音状态更新视频可用性。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param muted 是否静音。
             */
            override fun onUserMuteVideo(uid: Int, muted: Boolean) = publish(uid, !muted)

            /**
             * 根据远端视频模块状态更新视频可用性。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param enabled 是否启用对应能力。
             */
            override fun onUserEnableVideo(uid: Int, enabled: Boolean) = publish(uid, enabled)

            /**
             * 根据远端采集状态更新视频可用性。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param enabled 是否启用对应能力。
             */
            override fun onUserEnableLocalVideo(uid: Int, enabled: Boolean) = publish(uid, enabled)

            /**
             * 根据远端用户状态更新视频发布信息。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param state 当前状态快照。
             */
            override fun onUserStateChanged(uid: Int, state: Int) = publish(uid, isAgoraVideoPublished(state))

            /**
             * 将远端视频状态转换为可订阅的视频发布状态。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param state 当前状态快照。
             * @param reason 引起状态变化或失败的原因。
             * @param elapsed RTC 事件报告的经过时间，单位为毫秒。
             */
            override fun onRemoteVideoStateChanged(uid: Int, state: Int, reason: Int, elapsed: Int) {
                if (state == Constants.REMOTE_VIDEO_STATE_STARTING || state == Constants.REMOTE_VIDEO_STATE_DECODING) publish(uid, true)
                if (reason == Constants.REMOTE_VIDEO_STATE_REASON_REMOTE_MUTED || reason == Constants.REMOTE_VIDEO_STATE_REASON_REMOTE_OFFLINE) publish(uid, false)
            }

            /**
             * 报告 RTC 连接状态变化及不可恢复的连接故障。
             *
             * @param state 当前状态快照。
             * @param reason 引起状态变化或失败的原因。
             */
            override fun onConnectionStateChanged(state: Int, reason: Int) {
                if (!isCurrent()) return
                if (state == Constants.CONNECTION_STATE_FAILED) stateListener?.invoke(roomId, false, "AGORA_CONNECTION_FAILED")
            }

            /**
             * 报告当前 RTC 鉴权凭据失效，需要重新建立业务连接。
             */
            override fun onRequestToken() {
                if (isCurrent()) stateListener?.invoke(roomId, false, "TOKEN_EXPIRED")
            }

            /**
             * 接收房间内的数据消息并转交业务信令处理。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param streamId 远端媒体流或数据通道的标识。
             * @param data 待读取、传输或持有的二进制数据。
             */
            override fun onStreamMessage(uid: Int, streamId: Int, data: ByteArray) {
                if (!isCurrent()) return
                codec.decode(userId(uid), data.toString(Charsets.UTF_8))?.let {
                    listener.get()?.onUserMessageReceived(RemoteStream(roomId, userId(uid)), it)
                }
            }

            /**
             * 接收上下行网络质量样本。
             *
             * @param uid RTC 分配的数字用户标识。
             * @param txQuality RTC 报告的上行网络质量等级。
             * @param rxQuality RTC 报告的下行网络质量等级。
             */
            override fun onNetworkQuality(uid: Int, txQuality: Int, rxQuality: Int) {
                if (isCurrent() && uid == 0) qualityListener.get()?.onNetworkQuality(
                    RtcQualityConverter.convertLevel(txQuality), RtcQualityConverter.convertLevel(rxQuality),
                )
            }

            /**
             * 将 RTC 本地视频统计转换为 SDK 上行统计。
             *
             * @param source RTC 视频采集来源标识。
             * @param stats RTC 视频统计快照。
             */
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

            /**
             * 将 RTC 远端视频统计转换为 SDK 下行统计。
             *
             * @param stats RTC 视频统计快照。
             */
            override fun onRemoteVideoStats(stats: RemoteVideoStats) {
                if (!isCurrent()) return
                val account = accounts[stats.uid] ?: return
            val statistics = RtcQualityConverter.downlinkVideoStatistics(account, stats)
            RtcStatsLogger.logDownlinkVideoStatistics(statistics)
            qualityListener.get()?.onDownlinkVideoStatistics(statistics)
            }
        }

        /**
         * 替换 RTC 房间或引擎事件监听器。
         *
         * @param listener 接收对应事件的监听器。
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
        override fun setEventListener(listener: (String, Boolean, String?) -> Unit): Int {
            stateListener = listener
            return 0
        }

        /**
         * 使用业务用户账号加入 RTC 房间。
         *
         * @param configuration 房间标识、用户账号、App ID 和进房 Token。
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
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

        /**
         * 按发布开关更新本地视频发布状态。
         *
         * @param publish true 发布媒体，false 撤销发布。
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
        override fun publishLocalVideo(publish: Boolean): Int {
            val result = engine.updateChannelMediaOptionsEx(ChannelMediaOptions().apply {
                publishCameraTrack = publish && !externalVideo
                publishCustomVideoTrack = publish && externalVideo
                if (externalVideo) customVideoTrackId = 0
            }, connection())
            if (result < 0) return result

            if (publish && externalVideo) return updateVideoEncoding()

            return result
        }

        /**
         * 按发布开关更新本地音频发布状态。
         *
         * @param publish true 发布媒体，false 撤销发布。
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
        override fun publishLocalAudio(publish: Boolean): Int = engine.updateChannelMediaOptionsEx(ChannelMediaOptions().apply {
            publishMicrophoneTrack = publish && !externalAudio
            publishCustomAudioTrack = publish && externalAudio
            if (externalAudio) publishCustomAudioTrackId = audioTrackId
        }, connection())

        /**
         * 更新远端视频流订阅状态。
         *
         * @param userId 房间中的业务用户标识。
         * @param subscribe true 订阅媒体，false 取消订阅。
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
        override fun subscribeRemoteVideo(userId: String, subscribe: Boolean): Int =
            engine.muteRemoteVideoStreamEx(uid(userId), !subscribe, connection())

        /**
         * 更新远端音频流订阅状态。
         *
         * @param userId 房间中的业务用户标识。
         * @param subscribe true 订阅媒体，false 取消订阅。
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
        override fun subscribeRemoteAudio(userId: String, subscribe: Boolean): Int =
            engine.muteRemoteAudioStreamEx(uid(userId), !subscribe, connection())

        /**
         * 取得用于订阅和渲染指定远端用户的流标识。
         *
         * @param userId 房间中的业务用户标识。
         * @return 与业务用户账号一致的远端流标识。
         */
        override fun resolveRemoteStreamId(userId: String) = userId

        /**
         * 向当前 RTC 房间发送消息。
         *
         * @param message 待发送或记录的消息文本。
         * @return RTC 返回的消息标识或错误码。
         */
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

        /**
         * 不阻塞主线程地等待已经入队的房间信令提交完成。
         */
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

        /**
         * 离开 RTC 房间并清空该房间的发布及订阅状态。
         *
         * @return RTC 操作返回码；非负表示已接受，负值表示失败。
         */
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

        /**
         * 销毁当前房间对象并释放其监听器与异步任务。
         */
        override fun destroy() {
            leave()
            stateListener = null
            accounts.clear()
            publications.clear()
        }
    }

    /**
     * 校验 RTC 返回码，失败时抛出包含操作名称的错误。
     *
     * @param code RTC 返回的状态或错误码。
     * @param operation 用于定位失败原因的操作名称。
     */
    private fun checkRtc(code: Int, operation: String) {
        check(code >= 0) { "Agora $operation failed: $code" }
    }
}
