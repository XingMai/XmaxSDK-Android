//
//  RealtimeTimingTest.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk

import ai.xmax.sdk.RealtimeTiming.Stage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RealtimeTimingTest {
    @Before
    fun setUp() {
        XmaxLogger.configure(XmaxLoggerOption.none)
    }

    @After
    fun tearDown() {
        XmaxLogger.configure(XmaxLoggerOption.none)
    }

    @Test
    fun `startup reports total session room publication and first frame durations`() = runTest {
        val clock = Clock()
        val logs = mutableListOf<String>()
        RealtimeTiming(clock::now, logs::add).measure {
            val attempt = currentCoroutineContext()[RealtimeTiming.Attempt]!!
            clock.advance(2); attempt.mark(CONNECTION_START)
            attempt.mark(SESSION_START)
            clock.advance(10); attempt.mark(SESSION_END)
            clock.advance(3); attempt.mark(ROOM_START)
            clock.advance(20); attempt.mark(ROOM_END)
            attempt.mark(PUBLICATION_START)
            clock.advance(4); attempt.mark(PUBLICATION_END)
            attempt.mark(CONNECTION_END)
            clock.advance(2); attempt.beginSignal("task")
            clock.advance(3)
            clock.advance(100); attempt.confirmRemoteStream("task")
            clock.advance(40); attempt.finish("task")
            attempt.finish("task")
        }
        val log = logs.single()
        assertEquals(
            """实时生成启动耗时
└─ 总耗时：184.0 ms
   ├─ 创建会话：10.0 ms
   ├─ RTC 进房：20.0 ms
   ├─ 发布本地流：4.0 ms
   └─ 首帧到达：143.0 ms""",
            log,
        )
    }

    @Test
    fun `reused connection has no connection stages and ignores old or duplicate SEI`() = runTest {
        val clock = Clock()
        val logs = mutableListOf<String>()
        RealtimeTiming(clock::now, logs::add).measure {
            val attempt = currentCoroutineContext()[RealtimeTiming.Attempt]!!
            clock.advance(5); attempt.beginSignal("new")
            attempt.confirmRemoteStream("old")
            clock.advance(10); attempt.confirmRemoteStream("new")
            clock.advance(4); attempt.confirmRemoteStream("new")
            attempt.finish("old")
            clock.advance(6); attempt.finish("new")
        }
        assertEquals(
            """实时生成启动耗时
└─ 总耗时：25.0 ms
   └─ 首帧到达：20.0 ms""",
            logs.single(),
        )
    }

    @Test
    fun `global environment uses English timing details`() = runTest {
        XmaxLogger.configure(XmaxLoggerOption.none, XmaxEnvironment.GLOBAL)
        val clock = Clock()
        val logs = mutableListOf<String>()
        RealtimeTiming(clock::now, logs::add).measure {
            val attempt = currentCoroutineContext()[RealtimeTiming.Attempt]!!
            clock.advance(4)
            attempt.beginSignal("task")
            clock.advance(10)
            attempt.confirmRemoteStream("task")
            clock.advance(5)
            attempt.finish("task")
        }

        assertEquals(
            """Realtime Generation Startup Timing
└─ Total: 19.0 ms
   └─ First Frame Arrival: 15.0 ms""",
            logs.single(),
        )
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
                clock.advance(10); attempt.confirmRemoteStream("failed")
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
        old.confirmRemoteStream("old"); old.finish("old"); old.fail(failure)
        assertEquals(1, logs.size)
    }

    private class Clock {
        private var nanos = 0L
        fun now() = nanos
        fun advance(ms: Long) { nanos += ms * 1_000_000 }
    }
}
