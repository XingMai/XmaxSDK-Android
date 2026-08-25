package ai.xmax.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

public class XmaxConfigurationTest {
    @Test
    public fun `api key is trimmed and never printed`() {
        val configuration = XmaxConfiguration("  secret-key  ")

        assertEquals("secret-key", configuration.apiKey)
        assertFalse(configuration.toString().contains("secret-key"))
    }

    @Test(expected = IllegalArgumentException::class)
    public fun `blank api key is rejected`() {
        XmaxConfiguration("   ")
    }
}

