package ai.xmax.sdk.foundation.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeInfoTest {
    @Test
    fun `runtime uses shared protocol keys without sharing mutable message JSON`() {
        val runtime = RuntimeInfo("android", "15", "1.2.3", "Pixel 9")
        val firstMessage = runtime.toJson()

        assertEquals(4, firstMessage.length())
        assertEquals("android", firstMessage.getString("platform"))
        assertEquals("15", firstMessage.getString("os_version"))
        assertEquals("1.2.3", firstMessage.getString("sdk_version"))
        assertEquals("Pixel 9", firstMessage.getString("device_model"))

        firstMessage.put("platform", "changed")
        assertEquals("android", runtime.toJson().getString("platform"))
    }
}
