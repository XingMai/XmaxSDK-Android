package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.VideoEncoderConfig
import org.junit.Assert.assertEquals
import org.junit.Test

public class RtcVideoConverterTest {
    @Test
    public fun `encoder configuration matches RTC fields and preference`() {
        val converted = RtcVideoConverter.makeEncoderConfiguration(
            VideoEncodingConfiguration(
                width = 1_024,
                height = 768,
                frameRate = 30,
                minimumBitrate = 100,
                maximumBitrate = 2_000,
            ),
        )

        assertEquals(1_024, converted.width)
        assertEquals(768, converted.height)
        assertEquals(30, converted.frameRate)
        assertEquals(100, converted.minBitrate)
        assertEquals(2_000, converted.maxBitrate)
        assertEquals(
            VideoEncoderConfig.EncoderPreference.MAINTAIN_FRAMERATE,
            converted.encodePreference,
        )
    }
}
