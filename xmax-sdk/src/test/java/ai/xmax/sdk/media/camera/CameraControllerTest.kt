package ai.xmax.sdk.media.camera

import ai.xmax.sdk.AudioFrame
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.RealtimeCameraPreviewReadyListener
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.foundation.permissions.PermissionManaging
import ai.xmax.sdk.foundation.rtc.RemoteStream
import ai.xmax.sdk.foundation.rtc.RoomJoinConfiguration
import ai.xmax.sdk.foundation.rtc.RtcEventListener
import ai.xmax.sdk.foundation.rtc.RtcManaging
import ai.xmax.sdk.foundation.rtc.RtcQualityListener
import ai.xmax.sdk.foundation.rtc.VideoEncodingConfiguration
import android.view.View
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CameraControllerTest {
    @Test
    fun `creates switches and stops one local camera stream`() = runTest {
        val rtc = CameraRtcStub()
        val controller = CameraController(
            rtcManager = rtc,
            permissionManager = GrantedPermissionManager,
            mediaService = IdentityMediaService,
        )

        val stream = controller.createLocalCameraStream(
            videoFormat = RealtimeVideoFormat(704, 1280, 24),
            position = CameraPosition.FRONT,
        )

        assertEquals("stream-local", stream.id)
        assertEquals("video0", stream.videoTrack?.id)
        assertEquals(listOf(CameraPosition.FRONT), rtc.cameraPositions)
        assertEquals(listOf(Triple(704, 1280, 24)), rtc.captureFormats)
        assertSame(stream.videoTrack, controller.currentTrack)

        val switched = controller.switchCamera()

        assertSame(stream.videoTrack, switched.videoTrack)
        assertEquals(CameraPosition.BACK, switched.videoTrack?.position)
        assertEquals(listOf(CameraPosition.FRONT, CameraPosition.BACK), rtc.cameraPositions)

        controller.stopLocalCameraStream()

        assertNull(controller.currentTrack)
        assertEquals(1, rtc.unbindCount)
        assertEquals(1, rtc.stopCount)
    }
}

private data object GrantedPermissionManager : PermissionManaging {
    override suspend fun ensureCameraPermission() = Unit

    override suspend fun ensureMicrophonePermission() = Unit
}

private data object IdentityMediaService : MediaServicing {
    override fun resolveModelInputSize(size: IntSize): IntSize = size

    override fun supportsFrameInterpolation(size: IntSize): Boolean = false
}

private class CameraRtcStub : RtcManaging {
    val cameraPositions = mutableListOf<CameraPosition>()
    val captureFormats = mutableListOf<Triple<Int, Int, Int>>()
    var stopCount = 0
    var unbindCount = 0

    override suspend fun initialize() = Unit
    override suspend fun destroy() = Unit
    override fun configureVideoEncoding(configuration: VideoEncodingConfiguration) = Unit
    override fun pushExternalVideoFrame(frame: VideoFrame, seiData: ByteArray?) = Unit
    override fun pushExternalAudioFrame(frame: AudioFrame) = Unit
    override fun useExternalVideoSource() = Unit
    override fun startExternalAudioSource() = Unit
    override fun stopExternalAudioSource() = Unit
    override fun startVideoCapture(width: Int, height: Int, frameRate: Int) {
        captureFormats += Triple(width, height, frameRate)
    }
    override fun stopVideoCapture() {
        stopCount += 1
    }
    override fun switchCamera(position: CameraPosition) {
        cameraPositions += position
    }
    override fun bindLocalVideo(view: View, contentMode: VideoContentMode) = Unit
    override fun unbindLocalVideo() {
        unbindCount += 1
    }
    override fun bindRemoteVideo(
        stream: RemoteStream,
        view: View,
        contentMode: VideoContentMode,
    ) = Unit
    override fun unbindRemoteVideo(stream: RemoteStream) = Unit
    override val renderLibraryName: String = "XmaxSDK"
    override suspend fun joinRoom(configuration: RoomJoinConfiguration) = Unit
    override suspend fun leaveRoom() = Unit
    override fun publishLocalVideo() = Unit
    override fun unpublishLocalVideo() = Unit
    override fun publishLocalAudio() = Unit
    override fun unpublishLocalAudio() = Unit
    override fun subscribeRemoteVideo(userId: String, subscribe: Boolean) = Unit
    override fun subscribeRemoteAudio(userId: String, subscribe: Boolean) = Unit
    override fun setRemoteAudioVolume(volume: Int, userId: String) = Unit
    override fun sendRoomMessage(message: String) = Unit
    override fun setEventListener(listener: RtcEventListener?) = Unit
    override fun setCameraPreviewReadyListener(listener: RealtimeCameraPreviewReadyListener?) = Unit
    override fun setRemoteVideoFrameReadyListener(
        listener: ((RemoteStream, Int, Int) -> Unit)?,
    ) = Unit
    override fun setQualityListener(listener: RtcQualityListener?) = Unit
}
