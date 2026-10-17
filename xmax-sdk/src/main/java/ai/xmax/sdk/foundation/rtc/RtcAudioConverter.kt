//
//  RtcAudioConverter.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode

/**
 * 校验声网外部音频所需的 48 kHz、单声道、PCM16LE 数据。
 */
internal object RtcAudioConverter {
    /**
     * 校验外部音频帧为 10 ms、48 kHz、单声道 PCM16LE，并返回有效字节。
     *
     * @param frame 待播放或提交的 PCM 音频帧。
     * @return 有效数据对应的字节数组。
     */
    fun convertFrame(frame: AudioFrame): ByteArray {
        val bytes = frame.dataBytes()
        val expected = AudioFrame.samplesPerFrame * AudioFrame.channelCount * Short.SIZE_BYTES
        if (bytes.size != expected) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Audio frame must contain exactly $expected bytes")
        }
        return bytes
    }
}
