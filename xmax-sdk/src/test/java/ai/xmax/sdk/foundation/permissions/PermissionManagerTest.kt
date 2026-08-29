package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionManagerTest {
    @Test
    fun `authorized camera permission returns without requesting`() = runTest {
        var requestCount = 0
        val manager = PermissionManager(
            PermissionAuthorizationClient(
                authorizationStatus = { MediaAuthorizationStatus.AUTHORIZED },
                requestAccess = {
                    requestCount += 1
                    true
                },
            ),
        )

        manager.ensureCameraPermission()

        assertEquals(0, requestCount)
    }

    @Test
    fun `not determined camera permission requests access`() = runTest {
        val requested = mutableListOf<MediaPermission>()
        val manager = PermissionManager(
            PermissionAuthorizationClient(
                authorizationStatus = { MediaAuthorizationStatus.NOT_DETERMINED },
                requestAccess = {
                    requested += it
                    true
                },
            ),
        )

        manager.ensureCameraPermission()

        assertEquals(listOf(MediaPermission.CAMERA), requested)
    }

    @Test
    fun `denied microphone permission returns aligned error`() = runTest {
        val manager = PermissionManager(
            PermissionAuthorizationClient(
                authorizationStatus = { MediaAuthorizationStatus.DENIED },
                requestAccess = { false },
            ),
        )

        val error = expectXmaxError { manager.ensureMicrophonePermission() }

        assertEquals(XmaxErrorCode.MICROPHONE_PERMISSION_DENIED, error.code)
        assertEquals("Microphone permission is unavailable or was denied", error.message)
    }

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}
