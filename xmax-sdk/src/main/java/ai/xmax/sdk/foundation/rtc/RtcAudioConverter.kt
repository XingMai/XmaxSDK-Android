package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import com.ss.bytertc.engine.data.AudioChannel
import com.ss.bytertc.engine.data.AudioSampleRate
import com.ss.bytertc.engine.utils.AudioFrame as VolcAudioFrame

/** 将中性 PCM 音频帧转换为火山 RTC 音频帧。 */
internal object RtcAudioConverter {
    /** 创建 48 kHz、单声道、PCM16 火山 RTC 音频帧。 */
    fun convertFrame(frame: AudioFrame): VolcAudioFrame {
        val expectedByteCount = AudioFrame.samplesPerFrame *
            AudioFrame.channelCount * Short.SIZE_BYTES
        if (frame.dataBytes().size != expectedByteCount) {
            throw XmaxError(
                code = XmaxErrorCode.INVALID_CONFIGURATION,
                message = "Audio frame must contain exactly $expectedByteCount bytes",
            )
        }
        return VolcAudioFrame(
            frame.dataBytes().copyOf(),
            AudioFrame.samplesPerFrame,
            AudioSampleRate.AUDIO_SAMPLE_RATE_48000,
            AudioChannel.AUDIO_CHANNEL_MONO,
        )
    }
}
