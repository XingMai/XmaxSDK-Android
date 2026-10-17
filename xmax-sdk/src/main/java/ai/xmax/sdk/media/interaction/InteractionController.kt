//
//  InteractionController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal typealias InteractionListener = suspend (String, List<RealtimePoint>) -> Unit

/**
 * 将渲染容器中的交互行为转换为生成画面的像素坐标并发送。
 *
 * @param listener 接收对应事件的监听器。
 * @param scope 执行后台任务的协程作用域。
 */
internal class InteractionController(
    private val listener: InteractionListener = { _, _ -> },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : InteractionControlling {
    /**
     * 交互任务与轨迹队列的并发保护。
     */
    private val stateLock = Any()

    /**
     * 当前任务、待发送轨迹及派发代次。
     */
    private var activeInteraction: ActiveInteraction? = null
    private var pendingSubmission: Submission? = null
    private var drainJob: Job? = null
    private var drainGeneration = 0L

    /**
     * 为指定生成任务启用交互轨迹提交。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param useDisplayedSize 是否按最终显示画面尺寸计算交互坐标。
     */
    override suspend fun startInteraction(taskId: String, videoFormat: RealtimeVideoFormat, useDisplayedSize: Boolean) {
        stopInteraction()
        synchronized(stateLock) { activeInteraction = ActiveInteraction(taskId, videoFormat, useDisplayedSize) }
    }

    /**
     * 停止当前任务的交互轨迹提交并丢弃未发送的数据。
     */
    override suspend fun stopInteraction() {
        val job = synchronized(stateLock) {
            activeInteraction = null
            drainGeneration++
            pendingSubmission = null
            drainJob.also { drainJob = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * 提交当前显示画面对应的触控轨迹。
     *
     * @param frame 当前触控轨迹及显示画面尺寸。
     */
    override fun submitInteraction(frame: InteractionFrame) {
        val interaction = synchronized(stateLock) { activeInteraction } ?: return
        if (frame.points.isEmpty()) return
        val coordinateFormat = if (interaction.useDisplayedSize) frame.displayedVideoFormat ?: return else interaction.videoFormat
        val videoSize = SizeF(
            coordinateFormat.width.toFloat(),
            coordinateFormat.height.toFloat(),
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

    /**
     * 依次发送当前交互任务的待提交轨迹，忽略失效任务。
     *
     * @param generation 用于识别当前生成请求或事件归属的标识。
     */
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
                currentCoroutineContext().ensureActive()
                listener(submission.taskId, submission.points)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                /**
                 * 轨迹采用最新帧优先策略，单帧发送失败不终止后续交互。
                 */
                XmaxLogger.interaction.warn(
                    message = {
                        "发送交互轨迹失败，已丢弃当前采样帧 " +
                            "(Failed to Send Interaction Trajectory; Current Sample Dropped)\n" +
                            "└─ ${XmaxLogger.localized("原因：", "Reason: ")}${ErrorMessageFormatter.format(error)}"
                    },
                )
            }
        }
    }

    /**
     * 保存当前允许发送轨迹的任务及坐标换算规格。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param useDisplayedSize 是否按最终显示画面尺寸计算交互坐标。
     */
    private data class ActiveInteraction(
        val taskId: String,
        val videoFormat: RealtimeVideoFormat,
        val useDisplayedSize: Boolean,
    )

    /**
     * 准备发送给生成服务的任务标识与轨迹点集合。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param points 视频画面像素坐标中的轨迹点集合。
     */
    private data class Submission(
        val taskId: String,
        val points: List<RealtimePoint>,
    )
}
