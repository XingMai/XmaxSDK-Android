package ai.xmax.sdk

import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.media.interaction.InteractionControlling
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/**
 * 协调单次生成的业务标识、条件缓存和交互绑定；操作互斥由上层 RealtimeCoordinator 保证。
 * Core 创建 taskId，并将同一个标识交给 Stream 和 Interaction，以关联信令、帧和轨迹。
 * 此处只等待 Stream 的远端确认，远端视频帧就绪条件由 XmaxRealtimeManager 继续等待。
 */
internal class XmaxRealtimeGenerationManager(
    private val interactionController: InteractionControlling,
    private val streamController: StreamControlling,
    /** 可注入固定标识以测试生命周期；生产环境每次 start 分配一个新任务标识。 */
    private val taskIdGenerator: () -> String = ::createTaskId,
) {
    private val stateLock = Any()
    private var currentContext: RealtimeContext? = null

    /**
     * 解析本次条件、创建任务并等待远端确认，之后才绑定交互和更新条件缓存。
     * 等待期间失败或取消时停止该 taskId；只有通过 ensureCurrent 校验的结果才能返回上层。
     */
    suspend fun start(
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext?,
        ensureCurrent: () -> Unit,
    ): String {
        val resolvedContext = context ?: synchronized(stateLock) { currentContext }
            ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "A realtime context is required for the first generation",
            )
        val taskId = taskIdGenerator()
        val confirmation = streamController.beginGeneration(
            taskId,
            videoFormat,
            resolvedContext,
        )
        try {
            confirmation.await()
            ensureCurrent()
            interactionController.startInteraction(taskId, videoFormat)
            synchronized(stateLock) { currentContext = resolvedContext }
            return taskId
        } catch (error: Throwable) {
            confirmation.cancel()
            cleanupAfterFailure(error, { stop(taskId) })
            throw XmaxError.from(error)
        }
    }

    /** 更新现有任务，复用 taskId；传 null 只刷新交互格式，不发送条件更新信令。 */
    suspend fun update(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext?,
    ) {
        interactionController.startInteraction(taskId, videoFormat)
        if (context == null) return
        streamController.updateGeneration(taskId, videoFormat, context)
        synchronized(stateLock) { currentContext = context }
    }

    /** 停止交互和指定任务，保留上次成功条件以便重新生成；独立释放步骤均会尝试。 */
    suspend fun stop(taskId: String) {
        cleanupResources(
            { interactionController.stopInteraction() },
            { streamController.stopGeneration(taskId) },
        )
    }

    /** 连接重置时清空条件缓存并停止任务；空 taskId 由 Stream 解释为停止当前任务。 */
    suspend fun reset(taskId: String = "") {
        synchronized(stateLock) { currentContext = null }
        stop(taskId)
    }

    internal companion object {
        /** UUID 原始 16 字节使用无填充的 Base64URL 编码，并加平台前缀，便于跨端排查。 */
        fun createTaskId(): String {
            val uuid = UUID.randomUUID()
            val bytes = ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
            return "task-android-${Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)}"
        }
    }
}
