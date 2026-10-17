//
//  RoomMessageCodec.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.stream.room

import java.util.UUID
import org.json.JSONObject

/**
 * 与 Web 的 __agora_chunk__ 协议一致；按 JSON 转义后的 UTF-8 字节预算分片。
 */
internal class RoomMessageCodec {
    /**
     * 保存同一条分片消息已接收的数据及完整性信息。
     *
     * @param count 消息分片的总数量。
     * @param parts 按序号保存的已收到消息分片。
     * @param bytes 已接收分片数据的累计字节数；默认值为 `0`。
     */
    private data class Record(val count: Int, val parts: MutableMap<Int, String> = mutableMapOf(), var bytes: Int = 0)

    /**
     * 按发送者与消息标识保存的分片重组缓存。
     */
    private val records = LinkedHashMap<Pair<String, String>, Record>()

    /**
     * 将信令编码为符合 RTC 单包大小限制的有序消息片段。
     *
     * @param message 待发送或记录的消息文本。
     * @return 按发送顺序排列的信令消息片段。
     */
    fun encode(message: String): List<String> {
        require(message.toByteArray(Charsets.UTF_8).size <= MAX_MESSAGE_BYTES) { "Room message is too large" }
        if (message.toByteArray(Charsets.UTF_8).size <= 800) return listOf(message)
        val pieces = mutableListOf<String>()
        val part = StringBuilder()
        var bytes = 0
        var offset = 0
        while (offset < message.length) {
            val codePoint = message.codePointAt(offset)
            val character = String(Character.toChars(codePoint))
            val cost = JSONObject.quote(character).toByteArray(Charsets.UTF_8).size - 2
            if (bytes + cost > 600 && part.isNotEmpty()) {
                pieces += part.toString()
                part.setLength(0)
                bytes = 0
            }
            part.append(character)
            bytes += cost
            offset += Character.charCount(codePoint)
        }
        if (part.isNotEmpty()) pieces += part.toString()
        val id = UUID.randomUUID().toString().replace("-", "")
        return pieces.mapIndexed { index, data ->
            JSONObject().put("event", EVENT).put("eventId", id).put("index", index)
                .put("count", pieces.size).put("data", data).toString()
        }
    }

    /**
     * 重组指定发送者的分片信令；未收齐时返回 null。
     *
     * @param sender 发送分片消息的用户标识。
     * @param message 待发送或记录的消息文本。
     * @return 完整重组后的消息；尚未收齐时为 null。
     */
    @Synchronized
    fun decode(sender: String, message: String): String? {
        if (message.toByteArray(Charsets.UTF_8).size > MAX_MESSAGE_BYTES) return null
        val json = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (json.optString("event") != EVENT) return message
        val id = json.opt("eventId") as? String ?: return null
        val index = json.opt("index") as? Int ?: return null
        val count = json.opt("count") as? Int ?: return null
        val data = json.opt("data") as? String ?: return null
        if (id.isBlank() || id.length > 128 || count !in 1..512 || index !in 0 until count) return null
        val key = sender to id
        val record = records.getOrPut(key) { Record(count) }
        while (records.size > 64) records.remove(records.keys.first())
        if (record.count != count || record.parts[index]?.let { it != data } == true) {
            records.remove(key)
            return null
        }
        if (index !in record.parts) {
            record.parts[index] = data
            record.bytes += data.toByteArray(Charsets.UTF_8).size
        }
        if (record.bytes > MAX_MESSAGE_BYTES) {
            records.remove(key)
            return null
        }
        if (record.parts.size != count) return null
        records.remove(key)
        val joined = (0 until count).joinToString("") { record.parts.getValue(it) }
        return joined.takeIf { runCatching { JSONObject(it) }.isSuccess }
    }

    /**
     * 清除尚未完成重组的消息分片。
     */
    @Synchronized
    fun clear() = records.clear()

    /**
     * 提供 RoomMessageCodec 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 共享常量、默认配置与工厂入口。
         */
        const val EVENT = "__agora_chunk__"
        const val MAX_MESSAGE_BYTES = 256 * 1024
    }
}
