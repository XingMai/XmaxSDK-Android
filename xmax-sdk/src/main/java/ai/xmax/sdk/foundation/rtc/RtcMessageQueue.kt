//
//  RtcMessageQueue.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.foundation.rtc

import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.stream.room.RoomMessageCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * 有序发送房间信令，并在发送额度不足时仅保留同键轨迹的最新内容。
 *
 * @param scope 房间生命周期内的发送协程作用域。
 * @param send 将单个数据包提交给 RTC，失败时抛出异常。
 * @param onFailure 通知房间信令发送故障。
 * @param nowMillis 单调递增的毫秒时钟，用于计算发送窗口。
 */
internal class RtcMessageQueue(
    scope: CoroutineScope,
    private val send: (ByteArray) -> Unit,
    private val onFailure: (Throwable) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /**
     * 保存同一条信令的完整分片、合并键和发送结果。
     *
     * @param packets 按顺序发送的数据包。
     * @param key 尚未发送时用于替换内容的合并键。
     * @param completion 整条信令提交给 RTC 后完成的结果。
     */
    private class Message(
        var packets: List<ByteArray>,
        val key: String?,
        val completion: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    /**
     * 队列状态、未发送内容与包含正在发送信令的结果集合。
     */
    private val lock = Any()
    private val pending = ArrayDeque<Message>()
    private val completions = mutableSetOf<CompletableDeferred<Unit>>()
    private var closed = false
    private var failure: Throwable? = null

    /**
     * 消息编码、队列唤醒及单一发送协程。
     */
    private val codec = RoomMessageCodec()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val worker = scope.launch { drain() }

    /**
     * 将信令加入有序队列；同键且尚未开始发送的内容被替换为最新值。
     *
     * @param message 完整的房间信令。
     * @param replacingPendingMessage 合并键；null 表示每条信令均须保留。
     * @return 整条信令的发送结果，被合并的调用共享同一结果。
     */
    fun enqueue(message: String, replacingPendingMessage: String? = null): CompletableDeferred<Unit> {
        val packets = codec.encode(message).map { it.toByteArray(Charsets.UTF_8) }
        return synchronized(lock) {
            checkAvailable()
            val existing = replacingPendingMessage?.let { key -> pending.firstOrNull { it.key == key } }
            if (existing != null) {
                existing.packets = packets
                wake.trySend(Unit)
                return@synchronized existing.completion
            }

            if (completions.size >= 64) {
                throw XmaxError(XmaxErrorCode.RTC_ERROR, "RTC room message queue is full")
            }

            val entry = Message(packets, replacingPendingMessage)
            pending.addLast(entry)
            completions.add(entry.completion)
            wake.trySend(Unit)
            entry.completion
        }
    }

    /**
     * 丢弃尚未开始发送的可合并信令，不中断已开始发送的分片或普通控制信令。
     *
     * @param key 要清理的合并键；null 表示清理全部可合并信令。
     */
    fun discardPendingMessages(key: String? = null) {
        synchronized(lock) {
            val discarded = pending.filter { it.key != null && (key == null || it.key == key) }
            pending.removeAll(discarded.toSet())
            discarded.forEach {
                it.completion.complete(Unit)
                completions.remove(it.completion)
            }
            wake.trySend(Unit)
        }
    }

    /**
     * 等待调用时已入队的信令提交完成；关闭队列或发送失败时抛出异常。
     */
    suspend fun flush() {
        val snapshot = synchronized(lock) {
            checkAvailable()
            completions.toList()
        }
        snapshot.forEach { it.await() }
    }

    /**
     * 关闭队列并取消所有尚未完成的发送结果。
     */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            completions.forEach { it.cancel(CancellationException("RTC room was closed")) }
            completions.clear()
            pending.clear()
        }
        worker.cancel()
        wake.close()
    }

    /**
     * 队列不可用时抛出关闭或发送失败原因。
     */
    private fun checkAvailable() {
        failure?.let { throw it }
        if (closed) throw CancellationException("RTC room was closed")
    }

    /**
     * 在额度可用后取出最新信令，完整发送其分片并记录实际发送量。
     */
    private suspend fun drain() {
        val sent = ArrayDeque<Pair<Long, Int>>()
        try {
            while (true) {
                coroutineContext.ensureActive()
                var waitMillis = 0L
                val entry = synchronized(lock) {
                    checkAvailable()
                    val next = pending.firstOrNull()
                    if (next != null) {
                        waitMillis = remainingWait(sent, next.packets.first().size)
                    }
                    if (next != null && waitMillis == 0L) pending.removeFirst() else null
                }
                if (entry == null) {
                    if (waitMillis > 0) withTimeoutOrNull(waitMillis) { wake.receive() } else wake.receive()
                    continue
                }

                for (packet in entry.packets) {
                    while (true) {
                        coroutineContext.ensureActive()
                        val remaining = remainingWait(sent, packet.size)
                        if (remaining == 0L) break
                        withTimeoutOrNull(remaining) { wake.receive() }
                    }
                    coroutineContext.ensureActive()
                    send(packet)
                    sent.addLast(nowMillis() to packet.size)
                }
                synchronized(lock) {
                    entry.completion.complete(Unit)
                    completions.remove(entry.completion)
                }
            }
        } catch (error: Throwable) {
            val report = synchronized(lock) {
                val shouldReport = !closed && error !is CancellationException
                failure = error
                completions.forEach { it.completeExceptionally(error) }
                completions.clear()
                pending.clear()
                shouldReport
            }
            if (report) onFailure(error)
        }
    }

    /**
     * 计算当前数据包满足每秒 58 包、29,000 字节额度所需的等待时间。
     *
     * @param sent 当前窗口中已提交的数据包时间及字节数。
     * @param byteCount 待发送数据包的字节数。
     * @return 需等待的毫秒数；0 表示可立即发送。
     */
    private fun remainingWait(sent: ArrayDeque<Pair<Long, Int>>, byteCount: Int): Long {
        val now = nowMillis()
        while (sent.isNotEmpty() && now - sent.first().first >= 1_000) sent.removeFirst()
        if (sent.size < 58 && sent.sumOf { it.second } + byteCount <= 29_000) return 0
        return (1_000 - (now - sent.first().first)).coerceAtLeast(1)
    }
}
