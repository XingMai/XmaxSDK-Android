package ai.xmax.sdk.media.audio

import ai.xmax.sdk.AudioFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PCMFramePacketizerTest {
    @Test
    fun `packetizer fills gaps and emits fixed ten millisecond frames`() {
        val packetizer = PCMFramePacketizer(AudioFrame.samplesPerFrame * 2)
        packetizer.append(ShortArray(240) { 10 }, atSample = 120)
        packetizer.append(ShortArray(600) { 20 }, atSample = 360)
        packetizer.finishWithSilence()

        val first = packetizer.nextFrame()!!
        val second = packetizer.nextFrame()!!

        assertEquals(0, first.sampleOffset)
        assertEquals(AudioFrame.samplesPerFrame, second.sampleOffset)
        assertEquals(AudioFrame.samplesPerFrame * Short.SIZE_BYTES, first.data.size)
        assertEquals(List(120) { 0.toShort() }, first.samples().take(120))
        assertEquals(List(240) { 10.toShort() }, first.samples().drop(120).take(240))
        assertEquals(List(120) { 20.toShort() }, first.samples().drop(360))
        assertNull(packetizer.nextFrame())
    }

    private fun PCMFramePacketizer.Packet.samples(): List<Short> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return List(data.size / Short.SIZE_BYTES) { buffer.short }
    }
}
