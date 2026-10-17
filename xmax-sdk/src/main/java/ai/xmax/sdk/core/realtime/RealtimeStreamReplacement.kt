//
//  RealtimeStreamReplacement.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

/**
 * 外部输入替换结果；remoteStream 仅在自动恢复生成成功时提供。
 *
 * @param localStream 本地输入媒体流。
 * @param remoteStream 远端生成结果流。
 */
public data class RealtimeStreamReplacement(
    public val localStream: RealtimeMediaStream,
    public val remoteStream: RealtimeMediaStream?,
)
