package ai.xmax.sdk

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
    public fun `formats every line with Xmax and category prefixes`() {
        assertEquals(
            "[Xmax][API] Request\n[Xmax][API] └─ Status: 200",
            XmaxLogger.formattedMessage("Request\n└─ Status: 200", " API "),
        )
        assertEquals(
            "[Xmax] Ready",
            XmaxLogger.formattedMessage("Ready", "  "),
        )
    }

    @Test
    public fun `only enabled logger options write to sink`() {
        var disabledMessageEvaluated = false
        XmaxLogger.configure(XmaxLoggerOption.business)

        XmaxLogger.debug(
            message = {
                disabledMessageEvaluated = true
                "performance"
            },
            category = "RTC",
            option = XmaxLoggerOption.performance,
        )
        XmaxLogger.info({ "business" }, category = "API")

        assertFalse(disabledMessageEvaluated)
        assertEquals(1, entries.size)
        assertEquals(XmaxLogLevel.INFO, entries.single().level)
        assertEquals("XmaxSDK", entries.single().tag)
        assertEquals("[Xmax][API] business", entries.single().message)

        XmaxLogger.configure(XmaxLoggerOption.all)
        XmaxLogger.debug(
            { "performance" },
            category = "RTC",
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

        XmaxLogger.info({ "connected" }, category = "Realtime")

        assertEquals("[Xmax][Realtime] connected", entries.single().message)
    }

    private data class LogEntry(
        val level: XmaxLogLevel,
        val tag: String,
        val message: String,
    )
}
