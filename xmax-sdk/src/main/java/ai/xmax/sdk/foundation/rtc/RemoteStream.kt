//
//  RemoteStream.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

/**
 * 标识 RTC 房间中的一条远端主流。
 *
 * @param roomId RTC 房间标识。
 * @param userId 房间中的业务用户标识。
 */
internal data class RemoteStream(
    val roomId: String,
    val userId: String,
) {
    /**
     * 生成跨房间唯一的远端流键。
     */
    val key: String
        get() = "$roomId:$userId"
}
