package ai.xmax.sdk.media.video

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import org.junit.Assert.*
import org.junit.Test

class NetworkVideoUrlTest {
    @Test fun `signed HTTP URLs retain their full query`() {
        for (url in listOf("https://example.test/a.mp4?signature=a%2Fb&expires=123", "http://localhost:8080/video.mp4")) {
            assertEquals(url, validateNetworkVideoUrl(url))
        }
    }

    @Test fun `unsupported or malformed URLs fail before creating resources`() {
        for (url in listOf("", "file:///tmp/a.mp4", "content://media/1", "https:///a.mp4", "https://example.test/a b.mp4")) {
            val error = runCatching { validateNetworkVideoUrl(url) }.exceptionOrNull()
            assertTrue("Expected invalid URL: $url", error is XmaxError)
            assertEquals(XmaxErrorCode.INVALID_CONFIGURATION, (error as XmaxError).code)
        }
    }
}
