package ai.xmax.sdk.media.image

import ai.xmax.sdk.RealtimeVideoFormat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 将本地图片处理为目标尺寸，并按固定帧率持续输出视频帧。 */
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
    private val stateLock = Any()
    private var preparedSource: Pair<RealtimeVideoFormat, VideoFrame>? = null
    private var outputJob: Job? = null
    private var isRunning = false

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

    override suspend fun prepare(
        imageData: ByteArray,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(imageManager.decode(imageData), videoFormat)
    }

    override suspend fun prepare(
        bitmap: Bitmap,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(imageManager.decode(bitmap), videoFormat)
    }

    override suspend fun prepare(
        uri: Uri,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = prepare(uriDataLoader(uri), videoFormat)

    override suspend fun prepare(
        decodedImage: DecodedImage,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> = withContext(Dispatchers.Default) {
        prepareDecoded(decodedImage, videoFormat)
    }

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
                runCatching { emitFrame(frame) }
                    .onFailure { error -> errorListener(XmaxError.from(error)) }
            }
        }
        synchronized(stateLock) {
            if (isRunning) outputJob = job else job.cancel()
        }
    }

    override fun stop() {
        val job = synchronized(stateLock) {
            isRunning = false
            preparedSource = null
            outputJob.also { outputJob = null }
        }
        job?.cancel()
    }

    private fun prepareDecoded(
        decodedImage: DecodedImage,
        videoFormat: RealtimeVideoFormat?,
    ): Pair<RealtimeVideoFormat, VideoFrame> {
        synchronized(stateLock) {
            if (preparedSource != null) {
                throw XmaxError(
                    XmaxErrorCode.INVALID_CONFIGURATION,
                    "Stop the current image source before preparing another image",
                )
            }
        }
        val resolvedFormat = resolveVideoFormat(decodedImage.size, videoFormat)
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

    private fun resolveVideoFormat(
        sourceSize: IntSize,
        requestedFormat: RealtimeVideoFormat?,
    ): RealtimeVideoFormat {
        val requested = requestedFormat ?: RealtimeVideoFormat(
            width = sourceSize.width,
            height = sourceSize.height,
            fps = DEFAULT_FRAME_RATE,
        )
        if (requested.fps <= 0) {
            throw XmaxError(
                XmaxErrorCode.INVALID_CONFIGURATION,
                "Image stream frame rate must be greater than zero",
            )
        }
        val resolvedSize = mediaService.resolveModelInputSize(
            IntSize(requested.width, requested.height),
        )
        return RealtimeVideoFormat(
            width = resolvedSize.width,
            height = resolvedSize.height,
            fps = requested.fps,
        ).also(RealtimeVideoFormat::validate)
    }

    private fun emitFrame(frame: VideoFrame) {
        frameListener(frame.updating(timestampUsProvider()))
    }

    private companion object {
        const val DEFAULT_FRAME_RATE = 24

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
