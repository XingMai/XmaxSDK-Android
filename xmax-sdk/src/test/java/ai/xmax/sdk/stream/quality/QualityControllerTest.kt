package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQuality
import ai.xmax.sdk.UplinkVideoStatistics
import ai.xmax.sdk.DownlinkVideoStatistics
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
    public fun `internal adaptation samples survive removal of public quality listener`() = runTest {
        val rtc = RtcManagingStub()
        val controller = QualityController(rtc, callbackScope = this)
        val internal = mutableListOf<RealtimeNetworkQuality>()
        val external = mutableListOf<RealtimeNetworkQuality>()
        controller.setAdaptiveQualityListener(internal::add)
        controller.setNetworkQualityListener(external::add)
        rtc.emitNetworkQuality(RtcQualityLevel.BAD, RtcQualityLevel.GOOD)
        runCurrent()
        controller.setNetworkQualityListener(null)
        rtc.emitNetworkQuality(RtcQualityLevel.VERY_BAD, RtcQualityLevel.EXCELLENT)
        runCurrent()
        assertEquals(2, internal.size)
        assertEquals(1, external.size)
        controller.setAdaptiveQualityListener(null)
        rtc.emitNetworkQuality(RtcQualityLevel.BAD, RtcQualityLevel.BAD)
        runCurrent()
        assertEquals(2, internal.size)
    }

    @Test
    public fun `statistics are delivered only for published local video and selected remote user`() = runTest {
        val controller = QualityController(RtcManagingStub(), callbackScope = this)
        val local = mutableListOf<UplinkVideoStatistics?>()
        val remote = mutableListOf<DownlinkVideoStatistics?>()
        controller.setUplinkVideoStatisticsListener(local::add)
        controller.setDownlinkVideoStatisticsListener(remote::add)
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 999))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("other"))
        runCurrent()
        assertEquals(0, local.size)
        assertEquals(0, remote.size)

        controller.setUplinkVideoStatisticsEnabled(true)
        controller.setDownlinkVideoStatisticsUser("bot")
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 1234))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("other"))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("bot", renderFrameRate = 24))
        runCurrent()
        assertEquals(listOf(null, UplinkVideoStatistics(bitrateKbps = 1234)), local)
        assertEquals(listOf(null, DownlinkVideoStatistics("bot", renderFrameRate = 24)), remote)
    }

    @Test
    public fun `disconnect and remote replacement invalidate queued statistics`() = runTest {
        val controller = QualityController(RtcManagingStub(), callbackScope = this)
        val local = mutableListOf<UplinkVideoStatistics?>()
        val remote = mutableListOf<DownlinkVideoStatistics?>()
        controller.setUplinkVideoStatisticsListener(local::add)
        controller.setDownlinkVideoStatisticsListener(remote::add)
        controller.setUplinkVideoStatisticsEnabled(true)
        controller.setDownlinkVideoStatisticsUser("old")
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 1234))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("old"))
        controller.setUplinkVideoStatisticsEnabled(false)
        controller.setDownlinkVideoStatisticsUser("new")
        controller.onUplinkVideoStatistics(UplinkVideoStatistics(bitrateKbps = 999))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("old"))
        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("new", renderFrameRate = 30))
        runCurrent()
        assertEquals(listOf<UplinkVideoStatistics?>(null), local)
        assertEquals(listOf(null, DownlinkVideoStatistics("new", renderFrameRate = 30)), remote)

        controller.onDownlinkVideoStatistics(DownlinkVideoStatistics("new", renderFrameRate = 15))
        controller.setDownlinkVideoStatisticsListener(null)
        runCurrent()
        assertEquals(2, remote.size)
    }

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
