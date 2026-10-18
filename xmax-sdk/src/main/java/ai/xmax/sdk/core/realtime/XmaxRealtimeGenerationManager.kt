//
//  XmaxRealtimeGenerationManager.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

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
 *
 * @param interactionController 提交触控轨迹的控制器。
 * @param streamController 控制 RTC 发布、订阅及生成信令的组件。
 * @param taskIdGenerator 生成唯一任务标识的函数。
 */
internal class XmaxRealtimeGenerationManager(
    private val interactionController: InteractionControlling,
    private val streamController: StreamControlling,

    /**
     * 可注入固定标识以测试生命周期；生产环境每次 start 分配一个新任务标识。
     */
    private val taskIdGenerator: () -> String = ::createTaskId,
) {
    /**
     * 生成条件缓存的并发保护。
     */
    private val stateLock = Any()

    /**
     * 最近一次有效的生成条件及只读快照。
     */
    private var currentContext: RealtimeContext? = null
    private var currentFaceRestoration = false

    /**
     * 在途切流可持有同一个条件对象，保证重连回滚后仍能完成最新替换意图。
     */
    val cachedContext: RealtimeContext? get() = synchronized(stateLock) { currentContext }

    /**
     * 自动重启生成时保留上次成功请求的人脸还原设置。
     */
    val cachedFaceRestoration: Boolean get() = synchronized(stateLock) { currentFaceRestoration }

    /**
     * 首次生成必须提供条件；已缓存条件可供后续重启复用。
     *
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     */
    fun validateContext(context: RealtimeContext?) {
        if (context == null && synchronized(stateLock) { currentContext } == null) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "A realtime context is required for the first generation",
            )
        }
    }

    /**
     * 解析本次条件、创建任务并等待远端确认，之后才绑定交互和更新条件缓存。
     * 等待期间失败或取消时停止该 taskId；只有通过 ensureCurrent 校验的结果才能返回上层。
     *
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param targetVideoFormat 下行输出的视频规格。
     * @param generationVideoFormat 模型生成画布的视频规格，独立于媒体输入规格；默认值为 `videoFormat`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     * @param ensureCurrent 检查当前操作是否有效的函数，失效时应抛出取消异常。
     * @return 新任务的唯一生成标识。
     */
    suspend fun start(
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
        targetVideoFormat: RealtimeVideoFormat? = null,
        generationVideoFormat: RealtimeVideoFormat = videoFormat,
        paddingContain: Boolean = false,
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
            generationVideoFormat,
            resolvedContext,
            faceRestoration,
            targetVideoFormat,
            paddingContain,
        )
        try {
            confirmation.await()
            ensureCurrent()
            interactionController.startInteraction(taskId, videoFormat)
            synchronized(stateLock) {
                currentContext = resolvedContext
                currentFaceRestoration = faceRestoration
            }
            return taskId
        } catch (error: Throwable) {
            confirmation.cancel()
            cleanupAfterFailure(error, { stop(taskId) })
            throw XmaxError.from(error)
        }
    }

    /**
     * 更新现有任务，复用 taskId；条件为空时复用缓存，仍发送本次人脸还原设置。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     * @param videoFormat 视频尺寸、帧率及上行编码参数。
     * @param context 生成提示词及参考素材；null 表示复用已缓存条件，首次生成时必须提供。
     * @param faceRestoration 是否启用人脸修复，对应生成参数 `face_replace`；默认值为 `false`。
     * @param generationVideoFormat 模型生成画布的视频规格，独立于媒体输入规格；默认值为 `videoFormat`。
     * @param paddingContain 是否让服务端等比适配输入并在输出时移除补边；默认值为 `false`。
     * @param ensureCurrent 检查当前操作是否有效的函数，失效时应抛出取消异常。
     */
    suspend fun update(
        taskId: String,
        videoFormat: RealtimeVideoFormat,
        context: RealtimeContext?,
        faceRestoration: Boolean = false,
        generationVideoFormat: RealtimeVideoFormat = videoFormat,
        paddingContain: Boolean = false,
        ensureCurrent: () -> Unit,
    ) {
        val resolvedContext = context ?: synchronized(stateLock) { currentContext }
            ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "A realtime context is required for the first generation",
            )
        streamController.updateGeneration(taskId, generationVideoFormat, resolvedContext, faceRestoration, paddingContain)
        ensureCurrent()
        interactionController.startInteraction(taskId, videoFormat)
        synchronized(stateLock) {
            currentContext = resolvedContext
            currentFaceRestoration = faceRestoration
        }
    }

    /**
     * 停止交互和指定任务，保留上次成功条件以便重新生成；独立释放步骤均会尝试。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    suspend fun stop(taskId: String) {
        cleanupResources(
            { interactionController.stopInteraction() },
            { streamController.stopGeneration(taskId) },
        )
    }

    /**
     * 连接重置时清空条件缓存并停止任务；空 taskId 由 Stream 解释为停止当前任务。
     *
     * @param taskId 用于关联本次生成请求和结果流的任务标识。
     */
    suspend fun reset(taskId: String = "") {
        synchronized(stateLock) {
            currentContext = null
            currentFaceRestoration = false
        }
        stop(taskId)
    }

    /**
     * 提供 XmaxRealtimeGenerationManager 使用的构造入口、共享配置或常量。
     */
    internal companion object {
        /**
         * UUID 原始 16 字节使用无填充的 Base64URL 编码，并通过查询参数标记平台。
         *
         * @return 用于关联生成信令的唯一任务标识。
         */
        fun createTaskId(): String {
            val uuid = UUID.randomUUID()
            val bytes = ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            return "task-$encoded?os=android"
        }
    }
}
