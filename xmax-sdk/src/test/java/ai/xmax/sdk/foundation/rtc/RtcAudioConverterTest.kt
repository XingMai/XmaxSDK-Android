package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

public class RtcAudioConverterTest {
    @Test
    public fun `audio converter builds external PCM frame`() {
        val data = ByteArray(960) { 7 }

        val converted = RtcAudioConverter.convertFrame(
            AudioFrame(data = data, timestampUs = 10_000),
        )

        assertArrayEquals(data, converted)
    }

    @Test
    public fun `audio converter rejects incomplete frame`() {
        val error = assertThrows(XmaxError::class.java) {
            RtcAudioConverter.convertFrame(
                AudioFrame(data = ByteArray(958), timestampUs = 0),
            )
        }

        assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, error.code)
        assertEquals("Audio frame must contain exactly 960 bytes", error.message)
    }
}
