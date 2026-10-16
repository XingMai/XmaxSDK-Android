package ai.xmax.sdk.stream.room

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RoomMessageCodecTest {
    @Test fun `short message stays unchanged`() {
        val value = """{"event":"start"}"""
        assertEquals(listOf(value), RoomMessageCodec().encode(value))
        assertEquals(value, RoomMessageCodec().decode("bot", value))
    }

    @Test fun `escaped multilingual chunks fit the wire budget and reassemble out of order`() {
        val value = JSONObject().put("event", "start").put("prompt", ("中文😀\n\\\"".repeat(400))).toString()
        val sender = RoomMessageCodec()
        val packets = sender.encode(value)
        assertTrue(packets.size > 1)
        assertTrue(packets.all { it.toByteArray().size <= 800 })
        assertTrue(packets.all { JSONObject(it).getString("event") == "__agora_chunk__" })
        val receiver = RoomMessageCodec()
        val received = packets.reversed().mapNotNull { receiver.decode("bot", it) }
        assertEquals(listOf(value), received)
    }

    @Test fun `different senders cannot complete each others message and reset drops partials`() {
        val packets = RoomMessageCodec().encode(JSONObject().put("value", "a".repeat(1500)).toString())
        val receiver = RoomMessageCodec()
        assertNull(receiver.decode("a", packets.first()))
        packets.drop(1).forEach { assertNull(receiver.decode("b", it)) }
        receiver.clear()
        packets.drop(1).forEach { assertNull(receiver.decode("a", it)) }
        assertNull(receiver.decode("a", "invalid"))
    }
}
