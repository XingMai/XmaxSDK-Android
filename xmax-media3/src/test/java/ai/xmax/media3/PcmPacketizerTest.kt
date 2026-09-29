package ai.xmax.media3

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmPacketizerTest {
    @Test fun `resampling is independent of decoder buffer boundaries`() {
        fun collect(chunk: Int): List<Pair<List<Byte>, Long>> {
            val result = mutableListOf<Pair<List<Byte>, Long>>()
            val packetizer = PcmPacketizer { bytes, pts -> result += bytes.toList() to pts }
            var offset = 0
            while (offset < 88_200) {
                val count = minOf(chunk, 88_200 - offset)
                val data = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(count) { i ->
                    val sample = (((offset + i) % 300) * 100 - 15000).toShort()
                    data.putShort(sample); data.putShort(sample)
                }
                data.flip()
                packetizer.append(data, 44_100, 2, 1_000_000 + offset * 1_000_000L / 44_100)
                offset += count
            }
            return result
        }
        val whole = collect(88_200)
        assertEquals(199, whole.size)
        assertEquals(whole, collect(1024))
        assertEquals(whole, collect(137))
        whole.zipWithNext().forEach { (a, b) -> assertEquals(10_000, b.second - a.second) }
    }

    @Test fun `stereo is mixed and discontinuity discards partial old packet`() {
        val output = mutableListOf<Pair<ByteArray, Long>>()
        val packetizer = PcmPacketizer { bytes, pts -> output += bytes to pts }
        fun input(count: Int, left: Short, right: Short, pts: Long) {
            val data = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
            repeat(count) { data.putShort(left); data.putShort(right) }
            data.flip(); packetizer.append(data, 48_000, 2, pts)
        }
        input(240, 1000, 1000, 0)
        input(480, 3000, 1000, 2_000_000)
        assertEquals(1, output.size)
        assertEquals(2_000_000, output.single().second)
        val data = ByteBuffer.wrap(output.single().first).order(ByteOrder.LITTLE_ENDIAN)
        repeat(480) { assertEquals(2000.toShort(), data.short) }
    }
}
