package ai.xmax.sdk

import ai.xmax.sdk.RealtimeTiming.Stage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RealtimeTimingTest {
    @Test
    fun `startup reports session room signal SEI and first frame without overlapping durations`() = runTest {
        val clock = Clock()
        val logs = mutableListOf<String>()
        RealtimeTiming(clock::now, logs::add).measure {
            val attempt = currentCoroutineContext()[RealtimeTiming.Attempt]!!
            clock.advance(2); attempt.mark(CONNECTION_START)
            attempt.mark(SESSION_START)
            clock.advance(10); attempt.mark(SESSION_END)
            clock.advance(3); attempt.mark(ROOM_START)
            clock.advance(20); attempt.mark(ROOM_END)
            clock.advance(4); attempt.mark(CONNECTION_END)
            clock.advance(2); attempt.beginSignal("task")
            clock.advance(3); attempt.finishSignal("task")
            clock.advance(100); attempt.matchSEI("task")
            clock.advance(40); attempt.finish("task")
            attempt.finish("task")
        }
        val log = logs.single()
        assertTrue(log.contains("总耗时：184.0 ms"))
        assertTrue(log.contains("实时连接：37.0 ms"))
        assertTrue(log.contains("服务端会话创建：10.0 ms"))
        assertTrue(log.contains("RTC 房间连接：20.0 ms"))
        assertTrue(log.contains("媒体发布与连接准备：7.0 ms"))
        assertTrue(log.contains("等待生成结果流确认：103.0 ms"))
        assertTrue(log.contains("发送生成请求：3.0 ms"))
        assertTrue(log.contains("结果流确认到首帧就绪：40.0 ms"))
    }

    @Test
    fun `reused connection has no connection stages and ignores old or duplicate SEI`() = runTest {
        val clock = Clock()
        val logs = mutableListOf<String>()
        RealtimeTiming(clock::now, logs::add).measure {
            val attempt = currentCoroutineContext()[RealtimeTiming.Attempt]!!
            clock.advance(5); attempt.beginSignal("new")
            attempt.matchSEI("old")
            clock.advance(10); attempt.matchSEI("new")
            clock.advance(4); attempt.matchSEI("new")
            attempt.finish("old")
            clock.advance(6); attempt.finish("new")
        }
        assertTrue(logs.single().contains("生成前准备：5.0 ms"))
        assertTrue(logs.single().contains("等待生成结果流确认：10.0 ms"))
        assertTrue(logs.single().contains("结果流确认到首帧就绪：10.0 ms"))
        assertFalse(logs.single().contains("实时连接"))
    }

    @Test
    fun `failure reports pending stage and cancellation cannot later finish an abandoned attempt`() = runTest {
        val clock = Clock()
        val logs = mutableListOf<String>()
        val timing = RealtimeTiming(clock::now, logs::add)
        val failure = XmaxError(XmaxErrorCode.TIMEOUT, "first frame timed out")
        val thrown = runCatching {
            timing.measure {
                val attempt = currentCoroutineContext()[RealtimeTiming.Attempt]!!
                attempt.beginSignal("failed")
                clock.advance(10); attempt.matchSEI("failed")
                clock.advance(100); throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, thrown)
        assertTrue(logs.single().contains("停留阶段：等待首帧"))
        assertTrue(logs.single().contains("结果流确认后等待首帧：100.0 ms"))
        val cancelled = CancellationException("cancelled")
        lateinit var old: RealtimeTiming.Attempt
        val cancellation = runCatching {
            timing.measure {
                old = currentCoroutineContext()[RealtimeTiming.Attempt]!!
                old.beginSignal("old")
                throw cancelled
            }
        }.exceptionOrNull()
        assertTrue(cancellation is CancellationException)
        assertEquals(cancelled.message, cancellation?.message)
        old.matchSEI("old"); old.finish("old"); old.fail(failure)
        assertEquals(1, logs.size)
    }

    private class Clock {
        private var nanos = 0L
        fun now() = nanos
        fun advance(ms: Long) { nanos += ms * 1_000_000 }
    }
}
