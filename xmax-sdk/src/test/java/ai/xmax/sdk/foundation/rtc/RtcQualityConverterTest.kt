package ai.xmax.sdk.foundation.rtc

import com.ss.bytertc.engine.type.NetworkQuality
import com.ss.bytertc.engine.type.NetworkQualityStats
import com.ss.bytertc.engine.type.PerformanceAlarmReason
import org.junit.Assert.assertEquals
import org.junit.Test

public class RtcQualityConverterTest {
    @Test
    public fun `converter maps all known network levels`() {
        assertEquals(
            RtcQualityLevel.UNKNOWN,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_UNKNOWN),
        )
        assertEquals(
            RtcQualityLevel.EXCELLENT,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_EXCELLENT),
        )
        assertEquals(
            RtcQualityLevel.GOOD,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_GOOD),
        )
        assertEquals(
            RtcQualityLevel.POOR,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_POOR),
        )
        assertEquals(
            RtcQualityLevel.BAD,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_BAD),
        )
        assertEquals(
            RtcQualityLevel.VERY_BAD,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_VERY_BAD),
        )
        assertEquals(
            RtcQualityLevel.DOWN,
            RtcQualityConverter.convertLevel(NetworkQuality.NETWORK_QUALITY_DOWN),
        )
        assertEquals(RtcQualityLevel.UNKNOWN, RtcQualityConverter.convertLevel(99))
    }

    @Test
    public fun `converter uses worst valid remote downlink`() {
        val qualities = arrayOf(
            quality(NetworkQuality.NETWORK_QUALITY_EXCELLENT),
            quality(NetworkQuality.NETWORK_QUALITY_BAD),
            quality(NetworkQuality.NETWORK_QUALITY_GOOD),
            quality(99),
        )

        assertEquals(
            RtcQualityLevel.BAD,
            RtcQualityConverter.resolveDownlinkLevel(qualities),
        )
        assertEquals(
            RtcQualityLevel.UNKNOWN,
            RtcQualityConverter.resolveDownlinkLevel(emptyArray()),
        )
    }

    @Test
    public fun `converter resolves performance state`() {
        assertEquals(
            true,
            RtcQualityConverter.resolvePerformanceLimited(
                PerformanceAlarmReason.BANDWIDTH_FALLBACKED,
            ),
        )
        assertEquals(
            true,
            RtcQualityConverter.resolvePerformanceLimited(
                PerformanceAlarmReason.PERFORMANCE_FALLBACKED,
            ),
        )
        assertEquals(
            false,
            RtcQualityConverter.resolvePerformanceLimited(
                PerformanceAlarmReason.BANDWIDTH_RESUMED,
            ),
        )
        assertEquals(
            false,
            RtcQualityConverter.resolvePerformanceLimited(
                PerformanceAlarmReason.PERFORMANCE_RESUMED,
            ),
        )
    }

    private fun quality(rxQuality: Int): NetworkQualityStats = NetworkQualityStats(
        "user-id",
        0.0,
        0,
        0,
        NetworkQuality.NETWORK_QUALITY_UNKNOWN,
        rxQuality,
    )
}
