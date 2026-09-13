package ai.xmax.sdk

import ai.xmax.sdk.service.network.ApiLogger
import ai.xmax.sdk.service.network.ApiMethod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

public class XmaxLoggerTest {
    private val entries = mutableListOf<LogEntry>()

    @Before
    public fun setUp() {
        entries.clear()
        XmaxLogger.configure(XmaxLoggerOption.none)
        XmaxLogger.setSinkForTesting { level, tag, message ->
            entries += LogEntry(level, tag, message)
        }
    }

    @After
    public fun tearDown() {
        XmaxLogger.configure(XmaxLoggerOption.none)
        XmaxLogger.setSinkForTesting(null)
    }

    @Test
    public fun `formats every line with prefixes and ends the log entry with a newline`() {
        assertEquals(
            "[Xmax][API] Request\n[Xmax][API] └─ Status: 200\n",
            XmaxLogger.api.formattedMessage("Request\n└─ Status: 200"),
        )
        assertEquals(
            "[Xmax][Realtime] Ready\n",
            XmaxLogger.realtime.formattedMessage("Ready"),
        )
    }

    @Test
    public fun `only enabled logger options write to sink`() {
        var disabledMessageEvaluated = false
        XmaxLogger.configure(XmaxLoggerOption.business)

        XmaxLogger.rtc.debug(
            message = {
                disabledMessageEvaluated = true
                "performance"
            },
            option = XmaxLoggerOption.performance,
        )
        XmaxLogger.api.info(message = { "business" })

        assertFalse(disabledMessageEvaluated)
        assertEquals(1, entries.size)
        assertEquals(XmaxLogLevel.INFO, entries.single().level)
        assertEquals("XmaxSDK", entries.single().tag)
        assertEquals("[Xmax][API] business\n", entries.single().message)

        XmaxLogger.configure(XmaxLoggerOption.all)
        XmaxLogger.rtc.debug(
            message = { "performance" },
            option = XmaxLoggerOption.performance,
        )
        assertTrue(entries.last().message.contains("[Xmax][RTC] performance"))
    }

    @Test
    public fun `client applies logger options from configuration`() {
        XmaxClient(
            XmaxConfiguration(
                apiKey = "key",
                loggerOptions = XmaxLoggerOption.business,
            ),
        )

        XmaxLogger.realtime.info(message = { "connected" })

        assertEquals("[Xmax][Realtime] connected\n", entries.single().message)
    }

    @Test
    public fun `client selects log detail language from environment`() {
        assertEquals("原因：", XmaxLogger.localized("原因：", "Reason: "))

        XmaxClient(
            XmaxConfiguration(
                apiKey = "key",
                environment = XmaxEnvironment.GLOBAL,
            ),
        )
        assertEquals("Reason: ", XmaxLogger.localized("原因：", "Reason: "))
        val globalMessage = ApiLogger.responseMessage(ApiMethod.GET, "/health", 200, 16, 12)
        assertTrue(globalMessage.contains("Status: 200"))
        assertTrue(globalMessage.contains("Duration: 12 ms"))
        assertFalse(globalMessage.contains("状态："))

        XmaxClient(XmaxConfiguration(apiKey = "key"))
        assertEquals("原因：", XmaxLogger.localized("原因：", "Reason: "))
        assertTrue(ApiLogger.responseMessage(ApiMethod.GET, "/health", 200, 16, 12).contains("状态：200"))
    }

    private data class LogEntry(
        val level: XmaxLogLevel,
        val tag: String,
        val message: String,
    )
}
