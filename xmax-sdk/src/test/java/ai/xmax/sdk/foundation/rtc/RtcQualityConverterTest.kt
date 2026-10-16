package ai.xmax.sdk.foundation.rtc

import org.junit.Assert.assertEquals
import org.junit.Test

class RtcQualityConverterTest {
    @Test fun `Agora quality levels are mapped without treating detecting as healthy`() {
        listOf(RtcQualityLevel.UNKNOWN, RtcQualityLevel.EXCELLENT, RtcQualityLevel.GOOD,
            RtcQualityLevel.POOR, RtcQualityLevel.BAD, RtcQualityLevel.VERY_BAD,
            RtcQualityLevel.DOWN).forEachIndexed { value, expected ->
            assertEquals(expected, RtcQualityConverter.convertLevel(value))
        }
        assertEquals(RtcQualityLevel.UNKNOWN, RtcQualityConverter.convertLevel(8))
        assertEquals(RtcQualityLevel.UNKNOWN, RtcQualityConverter.convertLevel(99))
    }
}
