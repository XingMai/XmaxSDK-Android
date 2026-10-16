package ai.xmax.sdk.stream.quality

import ai.xmax.sdk.RealtimeNetworkQualityLevel
import ai.xmax.sdk.RealtimeVideoEncoderPreference
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.stream.encoding.EncodingBitratePolicy
import org.junit.Assert.*
import org.junit.Test

class AdaptiveQualityTest {
    private val baseline = RealtimeVideoFormat(1024, 1920, 30)
    private val bad = RealtimeNetworkQualityLevel.BAD
    private val good = RealtimeNetworkQualityLevel.GOOD

    @Test
    fun `explicit minimum bitrate prevents incompatible initial and automatic lower tiers`() {
        val maximumAtFullSize = EncodingBitratePolicy.resolve(baseline.width, baseline.height, baseline.fps).second
        val input = baseline.copy(minimumBitrate = maximumAtFullSize)
        val up = QualityController.Direction(input, 2, false)
        assertEquals(1, up.level)
        repeat(6) { assertNull(up.observe(bad, it * 1000L, it * 1000L)) }
        assertEquals(input, up.format)
        assertEquals(2, QualityController.Direction(input, 2, true).level)
    }

    @Test
    fun `tiers derive independently from original size and preserve bitrate overrides`() {
        val input = baseline.copy(minimumBitrate = 200, maximumBitrate = 3000,
            encoderPreference = RealtimeVideoEncoderPreference.MAINTAIN_FRAMERATE)
        val sizes = listOf(1024 to 1920, 818 to 1536, 664 to 1248, 512 to 960, 512 to 960)
        for (level in 1..5) {
            val format = QualityVideoFormat.resolve(input, level, downlink = false)
            assertEquals(sizes[level - 1], format.width to format.height)
            assertEquals(QualityTiers.frameRates[level - 1], format.fps)
            assertEquals(input.minimumBitrate, format.minimumBitrate)
            assertEquals(input.maximumBitrate, format.maximumBitrate)
            assertEquals(input.encoderPreference, format.encoderPreference)
            val landscape = QualityVideoFormat.resolve(input.copy(width = 1920, height = 1024), level, false)
            assertEquals(format.width to format.height, landscape.height to landscape.width)
            assertEquals(24, QualityVideoFormat.resolve(input, level, true).fps)
        }
        assertEquals(30, input.fps)
        assertEquals(1920, input.height)
    }

    @Test
    fun `tiers respect minimum short side original fps and deduplicate identical formats`() {
        val tiny = RealtimeVideoFormat(160, 90, 12)
        for (level in 1..5) {
            assertEquals(tiny, QualityVideoFormat.resolve(tiny, level, false))
            assertEquals(1, QualityVideoFormat.canonicalLevel(tiny, level, false))
        }
        val capped = QualityVideoFormat.resolve(RealtimeVideoFormat(480, 640, 30), 5, false)
        assertEquals(240 to 320, capped.width to capped.height)
        assertEquals(16, capped.fps)
        val down = QualityController.Direction(RealtimeVideoFormat(480, 640, 30), 5, true)
        assertEquals(4, down.level)
        repeat(10) { assertNull(down.observe(bad, it * 1000L, it * 1000L)) }
    }

    @Test
    fun `three poor samples downgrade and failure does not commit level`() {
        val direction = QualityController.Direction(baseline, 2, false)
        assertNull(direction.observe(bad, 0, 0))
        assertNull(direction.observe(bad, 1000, 1000))
        val change = requireNotNull(direction.observe(bad, 2000, 2000))
        assertEquals(QualityController.Direction.Change(2, 3, "poorNetwork"), change)
        assertEquals(2, direction.level)
        direction.complete(change, false, 2000)
        assertEquals(2, direction.level)
        assertNull(direction.observe(bad, 5000, 5000))
        assertNull(direction.observe(bad, 6000, 6000))
        assertNull(direction.observe(bad, 7000, 7000))
        val retry = requireNotNull(direction.observe(bad, 8000, 8000))
        direction.complete(retry, true, 8000)
        assertEquals(3, direction.level)
    }

    @Test
    fun `failed probe blocks upgrades for sixty seconds without blocking downgrades`() {
        val direction = QualityController.Direction(baseline, 3, false)
        repeat(4) { assertNull(direction.observe(good, it * 1000L, it * 1000L)) }
        val upgrade = requireNotNull(direction.observe(good, 4000, 4000))
        assertEquals("probeUpgrade", upgrade.reason)
        direction.complete(upgrade, true, 4000)
        assertEquals(2, direction.level)
        assertNull(direction.observe(bad, 8000, 8000))
        assertNull(direction.observe(bad, 9000, 9000))
        val fallback = requireNotNull(direction.observe(bad, 10000, 10000))
        assertEquals("failedProbe", fallback.reason)
        direction.complete(fallback, true, 10000)
        assertNull(direction.observe(bad, 15000, 15000))
        assertNull(direction.observe(bad, 16000, 16000))
        val further = requireNotNull(direction.observe(bad, 17000, 17000))
        assertEquals(4, further.to)
        direction.complete(further, true, 17000)
        for (time in 21000L..69000L step 1000) assertNull(direction.observe(good, time, time))
        repeat(4) { assertNull(direction.observe(good, 70000L + it * 1000, 70000L + it * 1000)) }
        assertNotNull(direction.observe(good, 74000, 74000))
    }

    @Test
    fun `stale duplicated future interrupted and unknown samples cannot accumulate`() {
        val direction = QualityController.Direction(baseline, 2, true)
        assertNull(direction.observe(bad, 0, 0))
        repeat(10) { assertNull(direction.observe(bad, 0, 0)) }
        assertNull(direction.observe(bad, 2000, 1000))
        assertNull(direction.observe(bad, 1000, 1000))
        assertNull(direction.observe(RealtimeNetworkQualityLevel.UNKNOWN, 2000, 2000))
        assertNull(direction.observe(bad, 3000, 3000))
        assertNull(direction.observe(bad, 4000, 10000))
        assertNull(direction.observe(bad, 11000, 11000))
        assertNull(direction.observe(bad, 12000, 12000))
        assertNull(direction.observe(bad, 18000, 18000))
        assertNull(direction.observe(bad, 19000, 19000))
        assertEquals(3, direction.observe(bad, 20000, 20000)?.to)
        direction.resetSamples()
        assertNull(direction.observe(bad, 21000, 21000))
        assertNull(direction.observe(bad, 22000, 22000))
    }

    @Test
    fun `uplink and downlink make independent decisions and never exceed bounds`() {
        val up = QualityController.Direction(baseline, 1, false)
        val down = QualityController.Direction(baseline, 5, true)
        repeat(10) {
            assertNull(up.observe(good, it * 1000L, it * 1000L))
            assertNull(down.observe(bad, it * 1000L, it * 1000L))
        }
        assertNull(up.observe(bad, 10000, 10000))
        assertNull(up.observe(bad, 11000, 11000))
        assertEquals(2, up.observe(bad, 12000, 12000)?.to)
        assertEquals(4, down.level)
    }

    @Test
    fun `history validates stored levels and separates directions with safe storage fallback`() {
        val timestamp = System.currentTimeMillis()
        val stored = mutableMapOf("uplink" to QualityHistory.Entry(99, timestamp), "downlink" to QualityHistory.Entry(4, timestamp))
        val history = QualityHistory(stored::get, { key, entry -> stored[key] = entry })
        assertEquals(2, history.read(false))
        assertEquals(4, history.read(true))
        history.write(false, 3)
        history.write(true, 0)
        assertEquals(3, QualityHistory(stored::get).read(false))
        assertEquals(4, history.read(true))
        val unavailable = QualityHistory({ error("unavailable") }, { _, _ -> error("unavailable") })
        assertEquals(2, unavailable.read(false))
        unavailable.write(false, 5)
        assertEquals(5, unavailable.read(false))
    }

    @Test
    fun `history expires at one hour in memory and after recreation without reads extending expiry`() {
        var time = 1000L
        val stored = mutableMapOf<String, QualityHistory.Entry>()
        val history = QualityHistory(stored::get, { key, entry -> stored[key] = entry }, now = { time })
        history.write(false, 4)
        time += 3_600_000L - 1
        assertEquals(4, history.read(false))
        assertEquals(4, QualityHistory(stored::get, now = { time }).read(false))

        time++
        assertEquals(2, history.read(false))
        assertEquals(2, QualityHistory(stored::get, now = { time }).read(false))
    }

    @Test
    fun `successful writes refresh only their own direction`() {
        var time = 1000L
        val history = QualityHistory(now = { time })
        history.write(false, 3)
        history.write(true, 5)
        time += 1_800_000L
        history.write(false, 4)
        time += 1_800_000L
        assertEquals(4, history.read(false))
        assertEquals(2, history.read(true))

        time += 1_800_000L
        assertEquals(2, history.read(false))
    }

    @Test
    fun `legacy records without timestamps and future timestamps fall back to default`() {
        for (timestamp in listOf(0L, -1L, 1001L)) {
            val history = QualityHistory(readValue = { QualityHistory.Entry(5, timestamp) }, now = { 1000L })
            assertEquals(2, history.read(false))
        }
        var time = 1000L
        val history = QualityHistory(now = { time })
        history.write(false, 4)
        time = 999L
        assertEquals(2, history.read(false))
    }
}
