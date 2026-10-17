//
//  RoomJoinConfiguration.kt
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
 * RTC 房间加入参数。
 *
 * @param roomId RTC 房间标识。
 * @param userId 房间中的业务用户标识。
 * @param token RTC 进房鉴权 Token。
 * @param appId RTC 应用标识，不是应用证书或房间 Token；默认值为 `RtcEngineManager.DEFAULT_APP_ID`。
 */
internal data class RoomJoinConfiguration(
    val roomId: String,
    val userId: String,
    val token: String,
    val appId: String = RtcEngineManager.DEFAULT_APP_ID,
)
