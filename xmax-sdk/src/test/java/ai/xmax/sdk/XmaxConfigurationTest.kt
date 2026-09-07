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
        assertEquals(XmaxEnvironment.CHINA, configuration.environment)
        assertEquals("https://cloud.xmax.22duck.cn/open/api/v1", configuration.environment.apiBaseUrl)
        assertFalse(configuration.toString().contains("secret-key"))
    }

    @Test
    public fun `logger options preserve combined business and performance flags`() {
        val options = XmaxLoggerOption.business + XmaxLoggerOption.performance
        val configuration = XmaxConfiguration(
            apiKey = "key",
            loggerOptions = options,
        )

        assertEquals(XmaxLoggerOption.all, configuration.loggerOptions)
        assertFalse(configuration.loggerOptions.isEmpty)
        assertTrue(XmaxLoggerOption.business in configuration.loggerOptions)
        assertTrue(XmaxLoggerOption.performance in configuration.loggerOptions)
        assertEquals(XmaxEnvironment.CHINA, configuration.environment)
    }

    @Test
    public fun `legacy positional logger options constructor remains compatible`() {
        val configuration = XmaxConfiguration("key", XmaxLoggerOption.all)

        assertEquals(XmaxLoggerOption.all, configuration.loggerOptions)
        assertEquals(XmaxEnvironment.CHINA, configuration.environment)
    }

    @Test
    public fun `global environment selects overseas API while preserving key and logger settings`() {
        val configuration = XmaxConfiguration(
            apiKey = "  secret-key\n",
            loggerOptions = XmaxLoggerOption.all,
            environment = XmaxEnvironment.GLOBAL,
        )

        configuration.validate()
        assertEquals("secret-key", configuration.apiKey)
        assertEquals(XmaxLoggerOption.all, configuration.loggerOptions)
        assertEquals(XmaxEnvironment.GLOBAL, configuration.environment)
        assertEquals("https://api.xmax.cloud/open/api/v1", configuration.environment.apiBaseUrl)
        assertTrue(configuration.toString().contains("environment=GLOBAL"))
        assertFalse(configuration.toString().contains("secret-key"))
    }

    @Test
    public fun `blank api key is rejected during validation`() {
        val error = assertThrows(XmaxError::class.java) {
            XmaxConfiguration("   ").validate()
        }

        assertEquals(XmaxErrorCode.INVALID_API_KEY, error.code)
    }
}
