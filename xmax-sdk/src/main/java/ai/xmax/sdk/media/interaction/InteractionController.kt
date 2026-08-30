package ai.xmax.sdk.media.interaction

import ai.xmax.sdk.RealtimePoint
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.ErrorMessageFormatter
import ai.xmax.sdk.XmaxLogger
import android.util.SizeF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal typealias InteractionListener = suspend (String, List<RealtimePoint>) -> Unit

/** 将渲染容器中的交互行为转换为模型输入坐标并发送。 */
internal class InteractionController(
    private val listener: InteractionListener = { _, _ -> },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InteractionControlling {
    private val stateLock = Any()
    private var activeInteraction: ActiveInteraction? = null
    private var pendingSubmission: Submission? = null
    private var drainJob: Job? = null
    private var drainGeneration = 0L

    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat) {
        synchronized(stateLock) {
            cancelPendingFramesLocked()
            activeInteraction = ActiveInteraction(taskId, videoFormat)
        }
    }

    override suspend fun stopInteraction() {
        synchronized(stateLock) {
            activeInteraction = null
            cancelPendingFramesLocked()
        }
    }

    override fun submitInteraction(frame: InteractionFrame) {
        val interaction = synchronized(stateLock) { activeInteraction } ?: return
        if (frame.points.isEmpty()) return
        val videoSize = SizeF(
            interaction.videoFormat.width.toFloat(),
            interaction.videoFormat.height.toFloat(),
        )
        val points = frame.points.mapNotNull { point ->
            InteractionCoordinateMapper.map(
                point = point,
                viewportSize = frame.viewportSize,
                videoSize = videoSize,
                contentMode = frame.contentMode,
            )
        }
        if (points.isEmpty()) return

        synchronized(stateLock) {
            val current = activeInteraction
            if (current == null || current.taskId != interaction.taskId) return
            pendingSubmission = Submission(current.taskId, points)
            if (drainJob?.isActive == true) return
            val generation = drainGeneration
            drainJob = scope.launch { drainPendingFrames(generation) }
        }
    }

    private suspend fun drainPendingFrames(generation: Long) {
        while (true) {
            val submission = synchronized(stateLock) {
                if (generation != drainGeneration) return
                pendingSubmission.also { pendingSubmission = null }
            }
            if (submission == null) {
                synchronized(stateLock) {
                    if (generation == drainGeneration && pendingSubmission == null) {
                        drainJob = null
                        return
                    }
                }
                continue
            }
            try {
                listener(submission.taskId, submission.points)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 轨迹采用最新帧优先策略，单帧发送失败不终止后续交互。
                XmaxLogger.warn(
                    {
                        "发送交互轨迹失败，已丢弃当前采样帧 " +
                            "(Failed to Send Interaction Trajectory; Current Sample Dropped)\n" +
                            "└─ 原因：${ErrorMessageFormatter.format(error)}"
                    },
                    category = "Interaction",
                )
            }
        }
    }

    private fun cancelPendingFramesLocked() {
        drainGeneration += 1L
        pendingSubmission = null
        drainJob?.cancel()
        drainJob = null
    }

    private data class ActiveInteraction(
        val taskId: String,
        val videoFormat: RealtimeVideoFormat,
    )

    private data class Submission(
        val taskId: String,
        val points: List<RealtimePoint>,
    )
}
