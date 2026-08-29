package ai.xmax.sdk.foundation.rtc

/** 定义 RTC 引擎生命周期和房间连接能力。 */
internal interface RtcManaging {
    /** 初始化 RTC 引擎。重复调用不会重复获取引擎。 */
    suspend fun initialize()

    /** 离开房间并销毁 RTC 引擎。重复调用安全。 */
    suspend fun destroy()

    /** 应用主视频流编码配置。 */
    fun configureVideoEncoding(configuration: VideoEncodingConfiguration)

    /** 加入 RTC 房间，并等待服务端确认加入成功。 */
    suspend fun joinRoom(configuration: RoomJoinConfiguration)

    /** 离开当前或正在加入的 RTC 房间。重复调用安全。 */
    suspend fun leaveRoom()

    /** 向当前 RTC 房间发送消息。 */
    fun sendRoomMessage(message: String)
}
