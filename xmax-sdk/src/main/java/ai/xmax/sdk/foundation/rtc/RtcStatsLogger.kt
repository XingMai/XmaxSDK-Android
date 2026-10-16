package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxLogger
import ai.xmax.sdk.XmaxLoggerOption
import io.agora.rtc2.IRtcEngineEventHandler.LocalVideoStats
import io.agora.rtc2.IRtcEngineEventHandler.RemoteVideoStats

/**
 * 声网统计直接使用 SDK 定义的 kbps、fps 和百分比单位，不推算不存在的指标。
 */
internal object RtcStatsLogger {
    fun logLocalStreamStats(stats: LocalVideoStats) {
        XmaxLogger.rtc.debug(message = { localStreamStatsMessage(stats) }, option = XmaxLoggerOption.performance)
    }

    fun logRemoteStreamStats(stats: RemoteVideoStats) {
        XmaxLogger.rtc.debug(message = { remoteStreamStatsMessage(stats) }, option = XmaxLoggerOption.performance)
    }

    internal fun localStreamStatsMessage(stats: LocalVideoStats): String =
        "本地视频发送 (Local Video Uplink)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${stats.encodedFrameWidth} × ${stats.encodedFrameHeight}\n" +
            "├─ ${label("发送码率：", "Send Bitrate: ")}${stats.sentBitrate} kbps\n" +
            "├─ ${label("采集帧率：", "Capture Frame Rate: ")}${stats.captureFrameRate} fps\n" +
            "├─ ${label("编码帧率：", "Encode Frame Rate: ")}${stats.encoderOutputFrameRate} fps\n" +
            "├─ ${label("发送帧率：", "Send Frame Rate: ")}${stats.sentFrameRate} fps\n" +
            "└─ ${label("视频丢包率：", "Video Packet Loss: ")}${stats.txPacketLossRate}%"

    internal fun remoteStreamStatsMessage(stats: RemoteVideoStats): String =
        "远端视频接收 (Remote Video Downlink)\n" +
            "├─ ${label("分辨率：", "Resolution: ")}${stats.width} × ${stats.height}\n" +
            "├─ ${label("接收码率：", "Receive Bitrate: ")}${stats.receivedBitrate} kbps\n" +
            "├─ ${label("解码帧率：", "Decode Frame Rate: ")}${stats.decoderOutputFrameRate} fps\n" +
            "├─ ${label("渲染帧率：", "Render Frame Rate: ")}${stats.rendererOutputFrameRate} fps\n" +
            "├─ ${label("视频丢包率：", "Video Packet Loss: ")}${stats.packetLossRate}%\n" +
            "└─ ${label("端到端时延：", "End-to-End Delay: ")}${stats.e2eDelay} ms"

    private fun label(chinese: String, english: String): String = XmaxLogger.localized(chinese, english)
}
