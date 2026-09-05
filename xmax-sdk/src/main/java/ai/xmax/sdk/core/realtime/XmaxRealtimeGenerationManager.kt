package ai.xmax.sdk

import ai.xmax.sdk.stream.StreamControlling
import ai.xmax.sdk.media.interaction.InteractionControlling
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

/** 协调生成信令、远端结果确认和条件更新。 */
internal class XmaxRealtimeGenerationManager(
    private val interactionController: InteractionControlling,
    private val streamController: StreamControlling,
    private val taskIdGenerator: () -> String = ::createTaskId,
) {
    private val stateLock = Any()
    private var currentContext: RealtimeContext? = null

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

    suspend fun stop(taskId: String) {
        cleanupResources(
            { interactionController.stopInteraction() },
            { streamController.stopGeneration(taskId) },
        )
    }

    suspend fun reset(taskId: String = "") {
        synchronized(stateLock) { currentContext = null }
        stop(taskId)
    }

    internal companion object {
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
