//
//  XmaxRealtimeManaging.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import android.graphics.Bitmap
import android.net.Uri

/**
 * 本地媒体、连接与生成的公共入口；同一管理器同时拥有一个本地媒体源。
 *
 * 创建或停止本地媒体前须先断开连接；外部视频热切换使用 [replaceExternalVideoStream]。
 * 同一管理器的生成请求以最新一次为准，SDK 负责取消旧请求并等待回滚，接入方无需串行排队。
 * [disconnect] 和 [close] 可取消受其影响的进行中操作，并等待清理。
 * 挂起调用通过异常返回失败，协程取消保持 CancellationException 语义。
 * 上下行传输质量随网络状况独立调档；无历史记录时从 L2（80% 宽高）开始。
 * 调档不改变原始媒体规格、模型生成尺寸或交互坐标，也不依赖公开统计监听器。
 */
public interface XmaxRealtimeManaging {
    /**
     * 管理器配置与当前生命周期状态。
     *
     * 创建管理器时指定的业务配置。
     */
    public val options: RealtimeConfiguration

    /**
     * 当前状态快照；异步故障由 [RealtimeState.reason] 表达。
     */
    public val currentState: RealtimeState

    /**
     * 本地预览与远端结果的播放音量。
     *
     * 当前本地媒体预览音量，取值范围为 `0..1`；未设置时为 0.45。
     */
    public val localAudioVolume: Float

    /**
     * 当前远端生成音频音量，取值范围为 `0..1`；新建媒体流时按来源重置。
     */
    public val remoteAudioVolume: Float

    /**
     * 替换状态监听器，并异步派发当前快照；通知在主线程执行，传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    public suspend fun setStateListener(listener: RealtimeStateListener?)

    /**
     * 接收显示前的最终远端视频帧。
     * 在 SDK 后台串行队列调用；慢消费者会跳过积压帧，只保留最新待回调帧。
     * 帧像素可持有供异步编码；传 null 清除。停止生成、断连或换任务会丢弃旧的待回调帧，
     * 已开始执行的回调允许返回；无需绑定视频视图也能接收帧。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    public suspend fun setRemoteVideoFrameListener(listener: RealtimeVideoFrameListener?)

    /**
     * 设置 RTC 网络质量监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    public suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    /**
     * 设置上行视频统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    public suspend fun setUplinkVideoStatisticsListener(listener: UplinkVideoStatisticsListener?)

    /**
     * 设置下行结果流统计监听器，回调在主线程执行；传 null 注销，断开连接后仍保留监听器。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    public suspend fun setDownlinkVideoStatisticsListener(listener: DownlinkVideoStatisticsListener?)

    /**
     * 设置 RTC 性能告警监听器；传 null 注销。
     *
     * @param listener 接收对应事件的监听器；null 表示注销。
     */
    public suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)

    /**
     * 设置本地视频预览音量，取值范围为 `0..1`；成功设置后跨 close 和媒体重建保留。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    public suspend fun setLocalAudioVolume(volume: Float)

    /**
     * 设置远端生成音频的播放音量，取值范围为 `0..1`；成功设置后跨 close 保留。
     * 创建新的摄像头或图片流时重置为 0，创建视频流时重置为 1。
     *
     * @param volume 播放音量，范围为 0 到 1。
     */
    public suspend fun setRemoteAudioVolume(volume: Float)

    /**
     * 使用当前模型的默认视频规格创建并启动相机输入。
     * 麦克风默认关闭；接入方须先取得所需权限。
     *
     * @param position 需要使用的前置或后置摄像头；默认值为 `CameraPosition.FRONT`。
     * @param useMicrophone 是否启用麦克风；启用前须由应用取得录音权限；默认值为 `false`。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createLocalCameraStream(
        position: CameraPosition = CameraPosition.FRONT,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream = createLocalCameraStream(
        videoFormat = options.model.defaultCameraVideoFormat,
        position = position,
        useMicrophone = useMicrophone,
    )

    /**
     * 创建并启动相机输入；接入方须先取得所需权限，输入尺寸会按模型规则调整。
     * 麦克风默认关闭；启用后在连接时开始采集、断开时停止，不进行本地回放。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param position 需要使用的前置或后置摄像头。
     * @param useMicrophone 是否启用麦克风；启用前须由应用取得录音权限；默认值为 `false`。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream

    /**
     * 停止当前相机输入并释放其资源；当前源不是相机时不执行释放。
     */
    public suspend fun stopLocalCameraStream()

    /**
     * 解码图片字节并创建保持媒体比例、持续输出图片帧的本地流。
     * 使用方向校正后的图片尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createLocalImageStream(
        imageData: ByteArray,
    ): RealtimeMediaStream

    /**
     * 从 Bitmap 创建保持媒体比例的本地图片流。
     * 使用 Bitmap 尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率，模型生成画布独立选择。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createLocalImageStream(
        bitmap: Bitmap,
    ): RealtimeMediaStream

    /**
     * 从可读取的 URI 创建本地图片流；读取权限由接入方保证。
     * 使用方向校正后的图片尺寸（受模型面积上限约束并按 4 对齐）及模型默认帧率。
     * 模型生成画布独立选择，服务端等比适配并裁去补边后输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createLocalImageStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * 停止当前图片流并释放其资源；当前源不是图片时不执行释放。
     */
    public suspend fun stopLocalImageStream()

    /**
     * 从 URI 创建本地视频流；存在音轨时同时提供视频文件的音频输入。
     * 使用方向校正后的素材尺寸（受模型面积上限约束并按 4 对齐）和模型默认帧率。
     * SDK 单独选择生成画布，服务端等比适配并裁边输出。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createLocalVideoStream(
        uri: Uri,
    ): RealtimeMediaStream

    /**
     * 创建由服务端直接读取 URL 的网络视频源；仅支持 HTTP/HTTPS。
     * 本地预览静音播放一次，不发布本地音视频；生成首帧就绪后订阅远端音频。
     * [onFinish] 在当前任务收到服务端完成事件且首帧就绪后于主线程调用一次，
     * 不代表远端尾帧已经显示完毕。创建源不会开始生成；资源由 [close] 统一释放。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param sampleMethod 服务端读取网络视频时采用的采样方式；默认值为 `RealtimeVideoSampleMethod.TIME`。
     * @param onFinish 网络视频任务完成的通知；null 表示不接收完成通知。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun createNetworkVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat,
        sampleMethod: RealtimeVideoSampleMethod = RealtimeVideoSampleMethod.TIME,
        onFinish: (() -> Unit)? = null,
    ): RealtimeMediaStream

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
    public suspend fun createExternalVideoStream(
        source: RealtimeExternalVideoSource,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /**
     * 解绑外部输入，停止前须断开生成连接；不关闭接入方的摄像头或播放器。
     */
    public suspend fun stopExternalVideoStream()

    /**
     * 替换外部视频输入；先停止旧任务及供帧，再调用 [targetSource] 准备并返回目标视频源。
     * 回调在调用方的调度器上执行，取消由 SDK 操作管理；采集器或播放器仍由调用方拥有。
     * 已在生成或正在启动生成时，使用 SDK 已有条件自动创建新任务；仅预览时不生成。
     * 音视频规格相同则保留房间；规格变化时内部重连，始终复用 Manager 和 RTC 引擎。
     * 连续替换以最新请求为准，旧请求以 CancellationException 结束；失败时不恢复旧任务。
     *
     * @param videoFormat 媒体输入的尺寸、帧率及可选上行编码配置；null 表示使用媒体解析结果或来源默认值。
     * @param targetSource 创建替换用外部视频源的挂起函数。
     * @return 替换后的本地媒体流及可用的远端结果流。
     */
    public suspend fun replaceExternalVideoStream(
        videoFormat: RealtimeVideoFormat? = null,
        targetSource: suspend () -> RealtimeExternalVideoSource,
    ): RealtimeStreamReplacement

    /**
     * 停止当前视频流并释放解码与预览资源；当前源不是视频时不执行释放。
     */
    public suspend fun stopLocalVideoStream()

    /**
     * 切换前后摄像头；生成中会先结束当前任务，切换后使用已缓存的条件重新生成。
     *
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun switchCamera(): RealtimeMediaStream

    /**
     * 使用本管理器创建且仍活动的本地流建立连接，返回供渲染绑定的远端流。
     *
     * @param localStream 本地输入媒体流。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream

    /**
     * 结束生成并关闭会话和 RTC 连接，保留本地媒体源；最终进入 READY 或 IDLE。
     */
    public suspend fun disconnect()

    /**
     * 按指定业务原因断开连接；例如显示方向变化时使用 [RealtimeReason.OrientationChanged]。
     *
     * @param reason 引起状态变化或失败的原因。
     */
    public suspend fun disconnect(reason: RealtimeReason)

    /**
     * 在已建立的连接上生成；首次生成须提供条件，后续传 null 可复用已缓存条件。
     * 已在生成时更新现有任务；新任务等待远端确认和首帧就绪后才进入 GENERATING。
     * 两个重载共用替换顺序；新调用替换未完成的旧调用，旧调用以 CancellationException 结束，
     * 不作为业务失败通知。disconnect/close 会取消当时所有待执行生成，不会在清理后自动恢复。
     *
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否回贴原始人脸；默认 false，每次调用均发送本次设置。
     */
    public suspend fun startGeneration(context: RealtimeContext?, faceRestoration: Boolean = false)

    /**
     * 必要时先建立连接，再开始或更新生成；返回该连接的远端流。并发替换语义与另一重载一致。
     *
     * @param localStream 本地输入媒体流。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否回贴原始人脸；默认 false，每次调用均发送本次设置。
     * @return 可用于预览或生成的媒体流及其视频轨道。
     */
    public suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
    ): RealtimeMediaStream

    /**
     * 取消进行中的操作并等待全部实时资源清理；保留监听器，之后可重新创建本地流复用管理器。
     */
    public suspend fun close()
}
