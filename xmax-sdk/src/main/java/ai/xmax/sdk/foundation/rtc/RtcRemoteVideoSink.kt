//
//  RtcRemoteVideoSink.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame

/**
 * 单轮远端接收：首帧通知切到主线程，像素访问仅限 RTC 当前回调栈。
 */
internal fun interface RtcRemoteVideoSink {
    /**
     * 通知订阅的视频流已到达首帧及其尺寸。
     *
     * @param width 画面宽度，单位为像素。
     * @param height 画面高度，单位为像素。
     */
    fun onFirstFrame(width: Int, height: Int)

    /**
     * 接收当前订阅的视频帧。
     *
     * @param frame 待处理、显示或提交的视频帧。
     */
    fun onFrame(frame: RtcRemoteVideoFrame) {}

    /**
     * 报告当前媒体或 RTC 操作发生的错误。
     *
     * @param error 本次操作失败的异常或 SDK 错误。
     */
    fun onError(error: ai.xmax.sdk.XmaxError) {}
}

/**
 * 借用原生帧；跨线程或超出 onFrame 生命周期前必须调用 copy。
 */
internal interface RtcRemoteVideoFrame {
    /**
     * 原始帧尺寸，单位为像素。
     */
    val width: Int
    val height: Int

    /**
     * 应用旋转后的显示尺寸，单位为像素。
     */
    val displayWidth: Int get() = width
    val displayHeight: Int get() = height

    /**
     * 复制远端帧像素及元数据，使调用方可在回调结束后继续使用。
     *
     * @return 像素数据可由调用方独立持有的远端视频帧。
     */
    fun copy(): RealtimeVideoFrame
}
