package ai.xmax.sdk

import android.graphics.Bitmap
import android.net.Uri

/**
 * 本地媒体、连接与生成的公共入口；同一管理器同时拥有一个本地媒体源。
 *
 * 创建或停止本地媒体前须先断开连接；冲突的生命周期操作会抛出配置错误。
 * [disconnect] 和 [close] 可取消受其影响的进行中操作，并等待清理。
 * 挂起调用通过异常返回失败，协程取消保持 CancellationException 语义。
 */
public interface XmaxRealtimeManaging {
    /** 创建管理器时指定的业务配置。 */
    public val options: RealtimeConfiguration

    /** 当前状态快照；持续观察状态应使用 [setStateListener]。 */
    public val currentState: RealtimeState

    /** 替换状态监听器，并异步派发当前快照；通知在主线程执行，传 null 注销。 */
    public suspend fun setStateListener(listener: RealtimeStateListener?)

    /**
     * 替换统一错误监听器；仅在致命故障完成本地清理后，于主线程通知。传 null 注销。
     * 对应的挂起调用也会抛出错误，接入方应避免在两处重复展示同一故障。
     * 可恢复错误仅由调用抛出；协程取消不会转换为业务错误回调。
     */
    public suspend fun setErrorListener(listener: RealtimeErrorListener?)

    /**
     * 接收显示前的最终远端视频帧，与 iOS 使用相同的回调时机。
     * 在 SDK 后台串行队列调用；慢消费者会跳过积压帧，只保留最新待回调帧。
     * 帧像素可持有供异步编码；传 null 清除。停止生成、断连或换任务会丢弃旧的待回调帧，
     * 已开始执行的回调允许返回；无需绑定视频视图也能接收帧。
     */
    public suspend fun setRemoteVideoFrameListener(listener: RealtimeVideoFrameListener?)

    /** 注册相机预览就绪通知；传 null 注销。创建相机流返回不等于预览已显示。 */
    public suspend fun setCameraPreviewReadyListener(
        listener: RealtimeCameraPreviewReadyListener?,
    )

    /** 替换 RTC 网络质量监听器；传 null 注销。 */
    public suspend fun setNetworkQualityListener(listener: RealtimeNetworkQualityListener?)

    /** 替换 RTC 性能告警监听器；传 null 注销。 */
    public suspend fun setPerformanceAlarmListener(listener: RealtimePerformanceAlarmListener?)

    /** 设置本地视频预览音量，取值范围为 `0..1`；成功设置后跨 close 和媒体重建保留。 */
    public suspend fun setLocalAudioVolume(volume: Float)

    /**
     * 设置远端生成音频的播放音量，取值范围为 `0..1`；成功设置后跨 close 保留。
     * 创建新的摄像头或图片流时重置为 0，创建视频流时重置为 1。
     */
    public suspend fun setRemoteAudioVolume(volume: Float)

    /**
     * 创建并启动相机输入；接入方须先取得所需权限，输入尺寸会按模型规则调整。
     * 麦克风默认关闭；启用后在连接时开始采集、断开时停止，不进行本地回放。
     */
    public suspend fun createLocalCameraStream(
        videoFormat: RealtimeVideoFormat,
        position: CameraPosition,
        useMicrophone: Boolean = false,
    ): RealtimeMediaStream

    /** 停止当前相机输入并释放其资源；当前源不是相机时不执行释放。 */
    public suspend fun stopLocalCameraStream()

    /** 解码图片字节并创建持续输出图片帧的本地流；未指定格式时根据图片尺寸计算。 */
    public suspend fun createLocalImageStream(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /** 从 Bitmap 创建本地图片流；未指定格式时根据 Bitmap 尺寸计算。 */
    public suspend fun createLocalImageStream(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /** 从可读取的 URI 创建本地图片流；读取权限由接入方保证。 */
    public suspend fun createLocalImageStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /** 停止当前图片流并释放其资源；当前源不是图片时不执行释放。 */
    public suspend fun stopLocalImageStream()

    /** 从 URI 创建本地视频流；存在音轨时同时提供视频文件的音频输入。 */
    public suspend fun createLocalVideoStream(
        uri: Uri,
        videoFormat: RealtimeVideoFormat? = null,
    ): RealtimeMediaStream

    /** 停止当前视频流并释放解码与预览资源；当前源不是视频时不执行释放。 */
    public suspend fun stopLocalVideoStream()

    /** 切换前后摄像头；生成中会先结束当前任务，切换后使用已缓存的条件重新生成。 */
    public suspend fun switchCamera(): RealtimeMediaStream

    /** 使用本管理器创建且仍活动的本地流建立连接，返回供渲染绑定的远端流。 */
    public suspend fun connect(localStream: RealtimeMediaStream): RealtimeMediaStream

    /** 结束生成并关闭会话和 RTC 连接，保留本地媒体源及监听器以便重连。 */
    public suspend fun disconnect()

    /**
     * 在已建立的连接上生成；首次生成须提供条件，后续传 null 可复用已缓存条件。
     * 已在生成时更新现有任务；新任务等待远端确认和首帧就绪后才进入 GENERATING。
     */
    public suspend fun startGeneration(context: RealtimeContext?)

    /** 必要时先建立连接，再开始或更新生成；返回该连接的远端流。 */
    public suspend fun startGeneration(
        localStream: RealtimeMediaStream,
        context: RealtimeContext?,
    ): RealtimeMediaStream

    /** 取消进行中的操作并等待全部实时资源清理；保留监听器，之后可重新创建本地流复用管理器。 */
    public suspend fun close()
}
