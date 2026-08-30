package ai.xmax.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

public class XmaxConfigurationTest {
    @Test
    public fun `api key is trimmed and never printed`() {
        val configuration = XmaxConfiguration("  secret-key  ")

        assertEquals("secret-key", configuration.apiKey)
        assertEquals(XmaxLoggerOption.none, configuration.loggerOptions)
        assertFalse(configuration.toString().contains("secret-key"))
    }

    @Test
    public fun `logger options preserve combined business and performance flags`() {
        val options = XmaxLoggerOption.business + XmaxLoggerOption.performance
        val configuration = XmaxConfiguration("key", loggerOptions = options)

        assertEquals(XmaxLoggerOption.all, configuration.loggerOptions)
        assertFalse(configuration.loggerOptions.isEmpty)
        assertTrue(XmaxLoggerOption.business in configuration.loggerOptions)
        assertTrue(XmaxLoggerOption.performance in configuration.loggerOptions)
    }

    @Test
    public fun `blank api key is rejected during validation`() {
        val error = assertThrows(XmaxError::class.java) {
            XmaxConfiguration("   ").validate()
        }

        assertEquals(XmaxErrorCode.INVALID_API_KEY, error.code)
    }
}
