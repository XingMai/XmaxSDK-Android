package com.xmax.xlab.modules.xlrealtime.recording

import ai.xmax.sdk.RealtimeVideoFrameListener
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

internal enum class RecordingState { IDLE, PREPARING, RECORDING, SAVING }

/** 主线程管理一次录制意图；保存独立于页面协程，退出时等待文件收尾，不能把旧帧写进新录制。 */
internal class RealtimeRecordingController(
    private val setFrameListener: suspend (RealtimeVideoFrameListener?) -> Unit,
    private val createRecording: () -> VideoRecording,
    private val saveVideo: suspend (File) -> Unit,
    private val notify: (String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(RecordingState.IDLE)
    val state = mutableState.asStateFlow()
    private var active: VideoRecording? = null
    private var stopRequested: CompletableDeferred<Unit>? = null
    private var operation: Job? = null
    private var closed = false

    fun start() {
        if (closed || mutableState.value != RecordingState.IDLE) return
        val recording = try { createRecording() } catch (error: Exception) {
            notify("无法开始录制：${error.message}")
            return
        }
        val stopped = CompletableDeferred<Unit>()
        active = recording
        stopRequested = stopped
        mutableState.value = RecordingState.RECORDING
        operation = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var file: File? = null
            var listenerAttached = false
            try {
                // 捕获本次 recording；已经开始执行的旧 SDK 回调只会进入已关闭的旧队列。
                setFrameListener { frame -> recording.append(frame) }
                listenerAttached = true
                select<Unit> {
                    stopped.onAwait { }
                    recording.result.onAwait { file = it }
                }
                mutableState.value = RecordingState.SAVING
                setFrameListener(null)
                listenerAttached = false
                val output = file ?: recording.result.await().also { file = it }
                saveVideo(output)
                notify("视频已保存到相册（无声）")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notify("录制失败：${error.message ?: "请重试"}")
            } finally {
                withContext(NonCancellable) {
                    recording.stop()
                    if (listenerAttached) {
                        try { setFrameListener(null) }
                        catch (error: Exception) { notify("无法清除录制回调：${error.message}") }
                    }
                    // 注册失败也要接回编码器的最终文件，避免后台任务或临时 MP4 遗留。
                    if (file == null) file = runCatching { recording.result.await() }.getOrNull()
                    file?.let { if (it.exists() && !it.delete()) notify("无法清理录制临时文件") }
                    if (active === recording) {
                        active = null
                        stopRequested = null
                        mutableState.value = RecordingState.IDLE
                    }
                }
            }
        }
    }

    /** 幂等停止：立即拒绝后续帧，合并按钮、切源和后台同时发起的保存。 */
    fun stopAndSave() {
        if (mutableState.value != RecordingState.RECORDING) return
        mutableState.value = RecordingState.SAVING
        active?.stop()
        stopRequested?.complete(Unit)
    }

    suspend fun close() {
        closed = true
        stopAndSave()
        operation?.join()
        scope.cancel()
    }
}
