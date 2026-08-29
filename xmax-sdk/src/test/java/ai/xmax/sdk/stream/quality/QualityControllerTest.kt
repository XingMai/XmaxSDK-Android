package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimePerformanceAlarm
import ai.xmax.sdk.RealtimePerformanceStatus
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.foundation.rtc.RtcQualityLevel
import ai.xmax.sdk.stream.room.RtcManagingStub
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
public class QualityControllerTest {
    @Test
    public fun `network quality maps every RTC level`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        val received = mutableListOf<RealtimeNetworkQuality>()
        controller.setNetworkQualityListener(received::add)

        RtcQualityLevel.entries.forEach { level ->
            rtcManager.emitNetworkQuality(level, level)
        }
        runCurrent()

        assertEquals(
            RealtimeNetworkQualityLevel.entries,
            received.map(RealtimeNetworkQuality::uplink),
        )
        assertEquals(
            received.map(RealtimeNetworkQuality::uplink),
            received.map(RealtimeNetworkQuality::downlink),
        )
    }

    @Test
    public fun `performance alarm maps limited state and suggested format`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        var received: RealtimePerformanceAlarm? = null
        controller.setPerformanceAlarmListener { received = it }

        rtcManager.emitPerformanceAlarm(
            limited = true,
            suggestedWidth = 540,
            suggestedHeight = 960,
            suggestedFrameRate = 15,
        )
        runCurrent()

        assertEquals(
            RealtimePerformanceAlarm(
                status = RealtimePerformanceStatus.LIMITED,
                suggestedVideoFormat = RealtimeVideoFormat(540, 960, 15),
            ),
            received,
        )
    }

    @Test
    public fun `performance recovery rejects invalid suggestion`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        var received: RealtimePerformanceAlarm? = null
        controller.setPerformanceAlarmListener { received = it }

        rtcManager.emitPerformanceAlarm(
            limited = false,
            suggestedWidth = 0,
            suggestedHeight = 960,
            suggestedFrameRate = 15,
        )
        runCurrent()

        assertEquals(RealtimePerformanceStatus.RECOVERED, received?.status)
        assertNull(received?.suggestedVideoFormat)
    }

    @Test
    public fun `cleared listeners ignore later events`() = runTest {
        val rtcManager = RtcManagingStub()
        val controller = QualityController(rtcManager, callbackScope = this)
        var networkCallbacks = 0
        var performanceCallbacks = 0
        controller.setNetworkQualityListener { networkCallbacks += 1 }
        controller.setPerformanceAlarmListener { performanceCallbacks += 1 }

        rtcManager.emitNetworkQuality(RtcQualityLevel.GOOD, RtcQualityLevel.POOR)
        rtcManager.emitPerformanceAlarm(true, 540, 960, 15)
        controller.setNetworkQualityListener(null)
        controller.setPerformanceAlarmListener(null)
        runCurrent()

        assertEquals(0, networkCallbacks)
        assertEquals(0, performanceCallbacks)
    }
}
