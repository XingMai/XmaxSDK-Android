//
//  ImageSourceController.kt
//  XmaxSDK
//  Repo: https://github.com/XingMai/XmaxSDK-Android
//
//  Author: 翁跃庭 (Yueting Weng)
//  GitHub: https://github.com/WengYuehTing
//
//  Copyright (c) 2026 XMAX.AI PTE. LTD.
//

package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.RealtimeMediaSource
import ai.xmax.sdk.MediaServicing
import ai.xmax.sdk.VideoFrame
import ai.xmax.sdk.XmaxError
import ai.xmax.sdk.XmaxErrorCode
import ai.xmax.sdk.foundation.media.image.DecodedImage
import ai.xmax.sdk.foundation.media.image.ImageManaging
import ai.xmax.sdk.media.MediaVideoFrameListener
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 将本地图片处理为目标尺寸，并按固定帧率持续输出视频帧。
 *
 * @param imageManager 负责图片解码和方向处理的组件。
 * @param mediaService 解析媒体尺寸及模型输入规则的服务。
 * @param frameListener 接收本地视频帧的监听器。
 * @param errorListener 接收媒体错误的回调。
 * @param uriDataLoader 从有读取权限的 URI 加载字节的函数。
 * @param timestampUsProvider 提供单调时钟微秒时间戳的函数。
 * @param outputScope 持续输出媒体帧的协程作用域。
 */
internal class ImageSourceController(
    private val imageManager: ImageManaging,
    private val mediaService: MediaServicing,
    private val frameListener: MediaVideoFrameListener,
    private val errorListener: (XmaxError) -> Unit,
    private val uriDataLoader: suspend (Uri) -> ByteArray,
    private val timestampUsProvider: () -> Long = {
        (SystemClock.elapsedRealtimeNanos() / 1_000L).coerceAtLeast(0L)
    },
    private val outputScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : ImageSourceControlling {
    /**
     * 图片输入状态的并发保护。
     */
    private val stateLock = Any()

    /**
     * 已准备图片及持续供帧任务。
     */
    private var preparedSource: Pair<RealtimeVideoFormat, VideoFrame>? = null
    private var outputJob: Job? = null
    private var isRunning = false

    /**
     * 创建 ImageSourceController，并配置其依赖和运行参数。
     *
     * @param context 访问 Android 系统服务或媒体资源所需的上下文。
     * @param imageManager 负责图片解码和方向处理的组件。
     * @param mediaService 解析媒体尺寸及模型输入规则的服务。
     * @param frameListener 接收本地视频帧的监听器。
     * @param errorListener 接收媒体错误的回调。
     * @param outputScope 持续输出媒体帧的协程作用域。
     */
    constructor(
        context: Context,
        imageManager: ImageManaging,
        mediaService: MediaServicing,
        frameListener: MediaVideoFrameListener,
        errorListener: (XmaxError) -> Unit,
        outputScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        imageManager = imageManager,
        mediaService = mediaService,
        frameListener = frameListener,
        errorListener = errorListener,
        uriDataLoader = { uri -> readUriBytes(context, uri) },
        outputScope = outputScope,
    )

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param imageData 可解码的图片文件字节，不是裸像素数据。
     * @return 图片输入的视频规格及对应视频帧。
     */
    override suspend fun prepare(
        imageData: ByteArray,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(imageManager.decode(imageData))
    }

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param bitmap 待处理或显示的 Android 图片。
     * @return 图片输入的视频规格及对应视频帧。
     */
    override suspend fun prepare(
        bitmap: Bitmap,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(imageManager.decode(bitmap))
    }

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param uri 应用有读取权限的媒体地址。
     * @return 图片输入的视频规格及对应视频帧。
     */
    override suspend fun prepare(
        uri: Uri,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepare(uriDataLoader(uri))

    /**
     * 解码图片并准备保持原始比例的图片输入规格和视频帧。
     *
     * @param decodedImage 完成解码及方向校正的图片。
     * @return 图片输入的视频规格及对应视频帧。
     */
    override suspend fun prepare(
        decodedImage: DecodedImage,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(decodedImage)
    }

    /**
     * 开始按配置帧率重复输出当前图片帧。
     */
    override fun start() {
        val source = synchronized(stateLock) {
            val source = preparedSource ?: throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Prepare the local image before starting the image source",
            )
            if (isRunning) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Local image source is already active",
                )
            }
            isRunning = true
            source
        }
        try {
            emitFrame(source.second)
        } catch (error: Throwable) {
            synchronized(stateLock) { isRunning = false }
            throw XmaxError.from(error)
        }

        val intervalMillis = (1_000L / source.first.fps).coerceAtLeast(1L)
        val job = outputScope.launch {
            while (isActive) {
                delay(intervalMillis)
                val frame = synchronized(stateLock) {
                    if (!isRunning) return@launch
                    preparedSource?.second
                } ?: return@launch
                try { emitFrame(frame) }
                catch (error: Throwable) {
                    currentCoroutineContext().ensureActive()
                    errorListener(XmaxError.from(error))
                    return@launch
                }
            }
        }
        synchronized(stateLock) {
            if (isRunning) outputJob = job else job.cancel()
        }
    }

    /**
     * 停止图片帧输出并清除已准备的输入资源。
     */
    override suspend fun stop() {
        val job = synchronized(stateLock) {
            isRunning = false
            preparedSource = null
            outputJob.also { outputJob = null }
        }
        job?.cancelAndJoin()
    }

    /**
     * 将已解码图片转换为输入规格及可重复提交的视频帧。
     *
     * @param decodedImage 完成解码及方向校正的图片。
     * @return 图片输入的视频规格及对应视频帧。
     */
    private fun prepareDecoded(
        decodedImage: DecodedImage,
    ): Pair<RealtimeVideoFormat, VideoFrame> {
        synchronized(stateLock) {
            if (preparedSource != null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Stop the current image source before preparing another image",
                )
            }
        }
        val resolvedFormat = resolveVideoFormat(decodedImage.size)
        val source = resolvedFormat to decodedImage.makeVideoFrame(resolvedFormat)
        synchronized(stateLock) {
            if (preparedSource != null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Another image source was prepared concurrently",
                )
            }
            preparedSource = source
        }
        return source
    }

    /**
     * 根据方向校正后的图片尺寸计算保持媒体比例的输入规格。
     *
     * @param sourceSize 原始媒体的显示尺寸，单位为像素。
     * @return 解析或计算后的视频尺寸、帧率和编码配置。
     */
    private fun resolveVideoFormat(
        sourceSize: IntSize,
    ): RealtimeVideoFormat {
        if (sourceSize.width < 2 || sourceSize.height < 2) {
            throw XmaxError(XmaxErrorCode.INVALID_CONFIGURATION, "Image dimensions must be at least two pixels")
        }

        val resolvedMediaSize = mediaService.resolveMediaSize(sourceSize, RealtimeMediaSource.IMAGE)
        return RealtimeVideoFormat(resolvedMediaSize.width, resolvedMediaSize.height, mediaService.model.defaultFrameRate)
    }

    /**
     * 向媒体监听器提交当前图片的视频帧。
     *
     * @param frame 待处理、显示或提交的视频帧。
     */
    private fun emitFrame(frame: VideoFrame) {
        frameListener(frame.updating(timestampUsProvider()))
    }

    /**
     * 提供 ImageSourceController 使用的构造入口、共享配置或常量。
     */
    private companion object {
        /**
         * 读取应用有权限访问的 URI 内容。
         *
         * @param context 访问 Android 系统服务或媒体资源所需的上下文。
         * @param uri 应用有读取权限的媒体地址。
         * @return 有效数据对应的字节数组。
         */
        suspend fun readUriBytes(context: Context, uri: Uri): ByteArray =
            withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw XmaxError(
                        XmaxErrorCode.MEDIA_ERROR,
                        "Failed to open local image URI",
                    )
            }
    }
}
