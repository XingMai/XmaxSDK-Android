package ai.xmax.sdk.foundation.rtc

/** 标识 RTC 房间中的一条远端主流。 */
internal data class RemoteStream(
    val roomId: String,
    val userId: String,
) {
    /** 生成跨房间唯一的远端流键。 */
    val key: String
        get() = "$roomId:$userId"
}
