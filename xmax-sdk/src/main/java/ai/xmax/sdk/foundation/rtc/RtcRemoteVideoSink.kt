package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.RealtimeVideoFrame

/** 单轮远端接收：首帧通知切到主线程，像素访问仅限 RTC 当前回调栈。 */
internal fun interface RtcRemoteVideoSink {
    fun onFirstFrame(width: Int, height: Int)
    fun onFrame(frame: RtcRemoteVideoFrame) {}
    fun onError(error: ai.xmax.sdk.XmaxError) {}
}

/** 借用原生帧；跨线程或超出 onFrame 生命周期前必须调用 copy。 */
internal interface RtcRemoteVideoFrame {
    val width: Int
    val height: Int
    fun copy(): RealtimeVideoFrame
}
