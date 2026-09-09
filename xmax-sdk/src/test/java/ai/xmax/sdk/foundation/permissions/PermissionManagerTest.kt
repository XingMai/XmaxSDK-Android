package ai.xmax.sdk.foundation.permissions

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.XmaxErrorSeverity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionManagerTest {
    @Test
    fun `authorized camera permission succeeds`() = runTest {
        val manager = PermissionManager { true }

        manager.ensureCameraPermission()
    }

    @Test
    fun `camera permission is checked again after revocation`() = runTest {
        var granted = true
        val manager = PermissionManager { granted }

        manager.ensureCameraPermission()
        granted = false
        val error = expectXmaxError { manager.ensureCameraPermission() }

        assertEquals(XmaxErrorCode.CAMERA_PERMISSION_DENIED, error.code)
    }

    @Test
    fun `denied camera permission returns recoverable error`() = runTest {
        val manager = PermissionManager { false }

        val error = expectXmaxError { manager.ensureCameraPermission() }

        assertEquals(XmaxErrorCode.CAMERA_PERMISSION_DENIED, error.code)
        assertEquals(XmaxErrorSeverity.RECOVERABLE, error.severity)
        assertEquals("Camera permission is unavailable or was denied", error.message)
    }

    @Test
    fun `denied microphone permission returns recoverable error`() = runTest {
        val manager = PermissionManager(
            isCameraPermissionGranted = { true },
            isMicrophonePermissionGranted = { false },
        )

        val error = expectXmaxError { manager.ensureMicrophonePermission() }

        assertEquals(XmaxErrorCode.MICROPHONE_PERMISSION_DENIED, error.code)
        assertEquals(XmaxErrorSeverity.RECOVERABLE, error.severity)
        assertEquals("Microphone permission is unavailable or was denied", error.message)
    }

    private suspend fun expectXmaxError(block: suspend () -> Unit): XmaxError = try {
        block()
        throw AssertionError("Expected XmaxError")
    } catch (error: XmaxError) {
        error
    }
}
