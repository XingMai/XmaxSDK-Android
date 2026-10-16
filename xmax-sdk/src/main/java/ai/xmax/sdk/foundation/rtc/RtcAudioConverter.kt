package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode

/**
 * 校验声网外部音频所需的 48 kHz、单声道、PCM16LE 数据。
 */
internal object RtcAudioConverter {
    fun convertFrame(frame: AudioFrame): ByteArray {
        val bytes = frame.dataBytes()
        val expected = AudioFrame.samplesPerFrame * AudioFrame.channelCount * Short.SIZE_BYTES
        if (bytes.size != expected) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Audio frame must contain exactly $expected bytes")
        }
        return bytes
    }
}
