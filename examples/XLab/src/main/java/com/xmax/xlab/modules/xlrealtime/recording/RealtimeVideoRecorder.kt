package com.xmax.xlab.modules.xlrealtime.recording

import ai.xmax.sdk.RealtimeVideoFrame
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel

internal interface VideoRecording {
    val result: Deferred<File>
    fun append(frame: RealtimeVideoFrame)
    fun stop()
}

/** 每次录制独立拥有队列和编码器；stop 关闭入口并排空最后一帧，旧回调不能进入下一次录制。 */
internal class RealtimeVideoRecorder(
    directory: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    encoderFactory: (File, RecordingFormat) -> RecordingEncoder = ::Mp4VideoEncoder,
) : VideoRecording {
    private val frames = Channel<RecordingFrame>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    override val result: Deferred<File> = scope.async {
        var file: File? = null
        var encoder: RecordingEncoder? = null
        var format: RecordingFormat? = null
        try {
            for (frame in frames) {
                if (encoder == null) {
                    check(directory.isDirectory || directory.mkdirs()) { "无法创建录制目录" }
                    file = File.createTempFile("xlab-recording-", ".mp4", directory)
                    format = frame.format
                    encoder = encoderFactory(file, format)
                }
                check(frame.format == format) { "录制期间视频尺寸或方向发生变化，请重新录制" }
                encoder.append(frame)
            }
            val writer = checkNotNull(encoder) { "尚未录制到生成画面，请稍后重试" }
            writer.finish()
            writer.close()
            encoder = null
            checkNotNull(file)
        } catch (error: Throwable) {
            try { encoder?.close() } catch (cleanup: Throwable) { if (cleanup !== error) error.addSuppressed(cleanup) }
            file?.let { if (it.exists() && !it.delete()) error.addSuppressed(IllegalStateException("无法删除未完成的录制文件")) }
            throw error
        } finally {
            frames.cancel()
        }
    }.also { it.invokeOnCompletion { scope.cancel() } }

    override fun append(frame: RealtimeVideoFrame) = append(RecordingFrame.from(frame))
    internal fun append(frame: RecordingFrame) { frames.trySend(frame) }
    override fun stop() { frames.close() }
}

internal interface RecordingEncoder : AutoCloseable {
    fun append(frame: RecordingFrame)
    fun finish()
}
