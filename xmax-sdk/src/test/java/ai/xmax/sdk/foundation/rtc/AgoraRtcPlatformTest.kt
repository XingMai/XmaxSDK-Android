package ai.xmax.sdk.foundation.rtc

import io.agora.rtc2.Constants
import org.junit.Assert.*
import org.junit.Test

class AgoraRtcPlatformTest {
    @Test fun `video mute bit cannot be interpreted as publication`() {
        val enabled = Constants.USER_STATE_ENABLE_VIDEO or Constants.USER_STATE_ENABLE_LOCAL_VIDEO
        assertTrue(isAgoraVideoPublished(enabled))
        assertTrue(isAgoraVideoPublished(enabled or Constants.USER_STATE_MUTE_AUDIO))
        assertFalse(isAgoraVideoPublished(enabled or Constants.USER_STATE_MUTE_VIDEO))
        assertFalse(isAgoraVideoPublished(0))
        assertFalse(isAgoraVideoPublished(Constants.USER_STATE_ENABLE_VIDEO))
    }
}
