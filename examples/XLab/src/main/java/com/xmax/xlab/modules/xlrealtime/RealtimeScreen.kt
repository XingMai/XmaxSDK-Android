package com.xmax.xlab.modules.xlrealtime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import ai.xmax.sdk.CameraPosition
import ai.xmax.sdk.RealtimeConfiguration
import ai.xmax.sdk.RealtimeContext
import ai.xmax.sdk.RealtimeMediaStream
import ai.xmax.sdk.RealtimeVideoFormat
import ai.xmax.sdk.VideoContentMode
import ai.xmax.sdk.XmaxClient
import ai.xmax.sdk.XmaxConfiguration
import ai.xmax.sdk.XmaxLoggerOption
import ai.xmax.sdk.XmaxVideoView
import coil3.compose.AsyncImage
import com.xmax.xlab.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

public sealed interface RealtimeSource {
    public data object Camera : RealtimeSource

    public data class Video(public val uri: Uri) : RealtimeSource

    public data class Image(public val uri: Uri) : RealtimeSource
}

public enum class RealtimeTrajectoryStyle {
    SDK_DEFAULT,
    XLAB_CUSTOM,
}

private enum class PickerTarget {
    LOCAL_VIDEO,
    LOCAL_IMAGE,
    CATEGORY_REFERENCE,
    PROMPT_REFERENCE,
}

private enum class ReferenceUploadState {
    READY,
    UPLOADING,
    FAILED,
}

private const val DEFAULT_LOCAL_AUDIO_VOLUME = 0.45f
private const val DEFAULT_REMOTE_AUDIO_VOLUME = 1f

private data class LocalReference(
    val id: String,
    val categoryId: String,
    val uri: Uri,
    val remoteUrl: String? = null,
    val uploadState: ReferenceUploadState = ReferenceUploadState.UPLOADING,
)

private data class PromptReference(
    val uri: Uri,
    val remoteUrl: String? = null,
    val uploadState: ReferenceUploadState = ReferenceUploadState.UPLOADING,
)

@Composable
public fun RealtimeScreen(
    apiKey: String,
    source: RealtimeSource,
    trajectoryStyle: RealtimeTrajectoryStyle = RealtimeTrajectoryStyle.SDK_DEFAULT,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val realtimeManager = remember(context, apiKey) {
        XmaxClient(
            context,
            XmaxConfiguration(
                apiKey = apiKey,
                loggerOptions = XmaxLoggerOption.all,
            ),
        ).createRealtimeManager(
            RealtimeConfiguration(),
        )
    }
    val realtimeOperationMutex = remember(realtimeManager) { Mutex() }
    val referenceUploader = remember(context, apiKey) {
        RealtimeReferenceUploader(context, apiKey)
    }
    var currentSource by remember(source) { mutableStateOf(source) }
    val categories = realtimeReferenceCategories
    var selectedCategoryId by remember(source) {
        mutableStateOf(if (source is RealtimeSource.Image) "mox" else "charx")
    }
    var selectedReferenceId by remember { mutableStateOf<String?>(null) }
    var localReferences by remember { mutableStateOf<Map<String, List<LocalReference>>>(emptyMap()) }
    var prompt by remember { mutableStateOf("") }
    var promptReference by remember { mutableStateOf<PromptReference?>(null) }
    var moxActive by remember { mutableStateOf(false) }
    var demoGenerationActive by remember { mutableStateOf(false) }
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }
    var pickerCategoryId by remember { mutableStateOf<String?>(null) }
    var cameraSwitching by remember { mutableStateOf(false) }
    var localMediaStream by remember(realtimeManager) { mutableStateOf<RealtimeMediaStream?>(null) }
    var remoteStream by remember(realtimeManager) { mutableStateOf<RealtimeMediaStream?>(null) }
    var cameraPreviewReady by remember(realtimeManager) { mutableStateOf(false) }
    var generationBusy by remember(realtimeManager) { mutableStateOf(false) }
    var generationLoading by remember(realtimeManager) { mutableStateOf(false) }
    var generationJob by remember(realtimeManager) { mutableStateOf<Job?>(null) }
    var cameraSwitchJob by remember(realtimeManager) { mutableStateOf<Job?>(null) }
    var localAudioVolumeJob by remember(realtimeManager) { mutableStateOf<Job?>(null) }
    var remoteAudioVolumeJob by remember(realtimeManager) { mutableStateOf<Job?>(null) }
    var localAudioVolume by remember { mutableStateOf(DEFAULT_LOCAL_AUDIO_VOLUME) }
    var remoteAudioVolume by remember { mutableStateOf(DEFAULT_REMOTE_AUDIO_VOLUME) }
    var isAudioMuted by remember { mutableStateOf(false) }
    var isAudioVolumeMenuVisible by remember { mutableStateOf(false) }
    var isSuspendedForBackground by remember { mutableStateOf(false) }
    var sourceImageReferenceUrl by remember(currentSource) { mutableStateOf<String?>(null) }
    var sourceImageUploadState by remember(currentSource) {
        mutableStateOf(
            if (currentSource is RealtimeSource.Image) {
                ReferenceUploadState.UPLOADING
            } else {
                ReferenceUploadState.READY
            },
        )
    }
    var cameraPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraRotation = remember { Animatable(0f) }
    var cameraBlurTarget by remember { mutableStateOf(0f) }
    val cameraBlur by animateFloatAsState(
        targetValue = cameraBlurTarget,
        animationSpec = tween(if (cameraBlurTarget > 0f) 140 else 180),
        label = "camera blur",
    )
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraPermissionGranted = granted
        if (!granted) {
            Toast.makeText(context, "需要相机权限才能预览", Toast.LENGTH_SHORT).show()
        }
    }

    DisposableEffect(Unit) {
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> isSuspendedForBackground = true
                Lifecycle.Event.ON_START -> isSuspendedForBackground = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(realtimeManager) {
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                realtimeOperationMutex.withLock {
                    realtimeManager.close()
                }
            }
        }
    }

    LaunchedEffect(currentSource, referenceUploader) {
        val sourceImage = currentSource as? RealtimeSource.Image ?: return@LaunchedEffect
        sourceImageUploadState = ReferenceUploadState.UPLOADING
        try {
            sourceImageReferenceUrl = referenceUploader.upload(sourceImage.uri)
            sourceImageUploadState = ReferenceUploadState.READY
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            sourceImageUploadState = ReferenceUploadState.FAILED
            Toast.makeText(context, "输入图片上传失败", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }

    LaunchedEffect(
        currentSource,
        cameraPermissionGranted,
        realtimeManager,
        isSuspendedForBackground,
    ) {
        realtimeOperationMutex.withLock {
            val selectedSource = currentSource
            if (isSuspendedForBackground) {
                generationJob?.cancelAndJoin()
                generationJob = null
                cameraSwitchJob?.cancelAndJoin()
                cameraSwitchJob = null
                generationBusy = false
                generationLoading = false
                withContext(NonCancellable) {
                    realtimeManager.close()
                }
                localMediaStream = null
                remoteStream = null
                cameraPreviewReady = false
                demoGenerationActive = false
                moxActive = false
                selectedReferenceId = null
                cameraSwitching = false
                cameraRotation.snapTo(0f)
                cameraBlurTarget = 0f
                focusManager.clearFocus()
                return@withLock
            }
            if (selectedSource is RealtimeSource.Camera && !cameraPermissionGranted) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                return@withLock
            }
            generationJob?.cancelAndJoin()
            generationJob = null
            cameraSwitchJob?.cancelAndJoin()
            cameraSwitchJob = null
            generationBusy = false
            generationLoading = false
            realtimeManager.close()
            localMediaStream = null
            remoteStream = null
            cameraPreviewReady = false
            demoGenerationActive = false
            moxActive = false
            selectedReferenceId = null
            cameraSwitching = false
            cameraRotation.snapTo(0f)
            cameraBlurTarget = 0f
            try {
                when (selectedSource) {
                    RealtimeSource.Camera -> {
                        cameraPreviewReady = false
                        realtimeManager.setCameraPreviewReadyListener {
                            cameraPreviewReady = true
                        }
                        localMediaStream = realtimeManager.createLocalCameraStream(
                            videoFormat = RealtimeVideoFormat(
                                width = 704,
                                height = 1280,
                                fps = 24,
                            ),
                            position = CameraPosition.FRONT,
                        )
                    }
                    is RealtimeSource.Image -> {
                        realtimeManager.setCameraPreviewReadyListener(null)
                        localMediaStream = realtimeManager.createLocalImageStream(selectedSource.uri)
                    }
                    is RealtimeSource.Video -> {
                        realtimeManager.setCameraPreviewReadyListener(null)
                        localMediaStream = realtimeManager.createLocalVideoStream(selectedSource.uri)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Toast.makeText(
                    context,
                    error.message ?: "本地媒体启动失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(currentSource) {
        if (currentSource is RealtimeSource.Image) {
            selectedCategoryId = "mox"
        }
        if (currentSource !is RealtimeSource.Video) {
            isAudioVolumeMenuVisible = false
        }
    }

    fun updateLocalReference(
        referenceId: String,
        update: (LocalReference) -> LocalReference,
    ) {
        localReferences = localReferences.mapValues { (_, references) ->
            references.map { reference ->
                if (reference.id == referenceId) update(reference) else reference
            }
        }
    }

    fun startGenerationRequest(contextProvider: suspend () -> RealtimeContext) {
        val localStream = localMediaStream
        val supportsGeneration = currentSource is RealtimeSource.Camera ||
            currentSource is RealtimeSource.Image ||
            currentSource is RealtimeSource.Video
        if (!supportsGeneration || localStream == null || generationBusy) return
        generationJob = scope.launch {
            val requestJob = coroutineContext[Job]
            generationBusy = true
            generationLoading = true
            try {
                val generationContext = contextProvider()
                if (localMediaStream !== localStream) return@launch
                remoteStream = realtimeManager.startGeneration(localStream, generationContext)
                demoGenerationActive = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                demoGenerationActive = false
                moxActive = false
                remoteStream = null
                realtimeManager.disconnect()
                Toast.makeText(
                    context,
                    error.message ?: "实时生成启动失败",
                    Toast.LENGTH_SHORT,
                ).show()
            } finally {
                if (generationJob === requestJob) {
                    generationJob = null
                    generationBusy = false
                    generationLoading = false
                }
            }
        }
    }

    fun startOrUpdateGeneration(contextValue: RealtimeContext) {
        startGenerationRequest { contextValue }
    }

    fun stopDemoGeneration() {
        if (generationBusy) return
        selectedReferenceId = null
        moxActive = false
        focusManager.clearFocus()
        if (!demoGenerationActive) {
            demoGenerationActive = false
            remoteStream = null
            return
        }
        scope.launch {
            generationBusy = true
            try {
                realtimeManager.disconnect()
            } finally {
                demoGenerationActive = false
                remoteStream = null
                generationBusy = false
            }
        }
    }

    fun uploadLocalReference(reference: LocalReference) {
        updateLocalReference(reference.id) {
            it.copy(remoteUrl = null, uploadState = ReferenceUploadState.UPLOADING)
        }
        if (selectedReferenceId == reference.id) {
            demoGenerationActive = false
        }
        scope.launch {
            try {
                val remoteUrl = referenceUploader.upload(reference.uri)
                updateLocalReference(reference.id) {
                    it.copy(remoteUrl = remoteUrl, uploadState = ReferenceUploadState.READY)
                }
                if (selectedReferenceId == reference.id) {
                    startOrUpdateGeneration(
                        RealtimeContext(
                            prompt = promptForReferenceCategory(reference.categoryId),
                            referencePath = remoteUrl,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                updateLocalReference(reference.id) {
                    it.copy(remoteUrl = null, uploadState = ReferenceUploadState.FAILED)
                }
                if (selectedReferenceId == reference.id) {
                    demoGenerationActive = false
                }
                Toast.makeText(context, "参考图上传失败，点击图片可重试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun uploadPromptReference(uri: Uri) {
        val pendingReference = PromptReference(uri)
        promptReference = pendingReference
        scope.launch {
            try {
                val remoteUrl = referenceUploader.upload(uri)
                if (promptReference === pendingReference) {
                    promptReference = pendingReference.copy(
                        remoteUrl = remoteUrl,
                        uploadState = ReferenceUploadState.READY,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                if (promptReference === pendingReference) {
                    promptReference = null
                    Toast.makeText(context, "参考图上传失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val target = pickerTarget
        val categoryId = pickerCategoryId
        pickerTarget = null
        pickerCategoryId = null
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            when (target) {
                PickerTarget.LOCAL_VIDEO -> currentSource = RealtimeSource.Video(uri)
                PickerTarget.LOCAL_IMAGE -> currentSource = RealtimeSource.Image(uri)
                PickerTarget.CATEGORY_REFERENCE -> if (categoryId != null) {
                    val reference = LocalReference(
                        id = "custom-${System.currentTimeMillis()}",
                        categoryId = categoryId,
                        uri = uri,
                    )
                    localReferences = localReferences +
                        (categoryId to (listOf(reference) + localReferences[categoryId].orEmpty()))
                    selectedReferenceId = reference.id
                    moxActive = false
                    demoGenerationActive = false
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    uploadLocalReference(reference)
                }
                PickerTarget.PROMPT_REFERENCE -> uploadPromptReference(uri)
            }
        }
    }

    fun launchPicker(target: PickerTarget, categoryId: String? = null) {
        pickerTarget = target
        pickerCategoryId = categoryId
        val mediaType = when (target) {
            PickerTarget.LOCAL_VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            else -> ActivityResultContracts.PickVisualMedia.ImageOnly
        }
        picker.launch(PickVisualMediaRequest(mediaType))
    }

    fun applyLocalAudioVolume(volume: Float) {
        localAudioVolumeJob?.cancel()
        localAudioVolumeJob = scope.launch {
            try {
                realtimeManager.setLocalAudioVolume(volume)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    fun applyRemoteAudioVolume(volume: Float) {
        remoteAudioVolumeJob?.cancel()
        remoteAudioVolumeJob = scope.launch {
            try {
                realtimeManager.setRemoteAudioVolume(volume)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    fun setAudioMuted(muted: Boolean) {
        isAudioMuted = muted
        applyLocalAudioVolume(if (muted) 0f else localAudioVolume)
        applyRemoteAudioVolume(if (muted) 0f else remoteAudioVolume)
    }

    fun setLocalAudioVolume(volume: Float) {
        localAudioVolume = volume
        if (isAudioMuted) {
            isAudioMuted = false
            applyRemoteAudioVolume(remoteAudioVolume)
        }
        applyLocalAudioVolume(volume)
    }

    fun setRemoteAudioVolume(volume: Float) {
        remoteAudioVolume = volume
        if (isAudioMuted) {
            isAudioMuted = false
            applyLocalAudioVolume(localAudioVolume)
        }
        applyRemoteAudioVolume(volume)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(focusManager) {
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    focusManager.clearFocus()
                }
            },
    ) {
        Box(modifier = Modifier.weight(1f)) {
            MediaCanvas(
                source = currentSource,
                mediaStream = if (demoGenerationActive) remoteStream else localMediaStream,
                trajectoryStyle = trajectoryStyle,
                cameraPreviewReady = cameraPreviewReady,
                generationLoading = generationLoading,
                sourceImageUploading = sourceImageUploadState == ReferenceUploadState.UPLOADING,
                isSuspendedForBackground = isSuspendedForBackground,
                cameraRotation = cameraRotation.value,
                cameraBlur = cameraBlur,
            )

            OverlayAction(
                label = "返回",
                containerSize = 44.dp,
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = 8.dp),
                onClick = onBack,
            ) {
                Image(
                    painter = painterResource(R.drawable.realtime_nav_back),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }

            if (currentSource is RealtimeSource.Camera) {
                OverlayAction(
                    label = "翻转",
                    enabled = !cameraSwitching,
                    modifier = Modifier
                        .statusBarsPadding()
                        .align(Alignment.TopEnd)
                        .padding(end = 8.dp, top = 18.dp),
                    onClick = {
                        if (!cameraSwitching) {
                            cameraSwitchJob = scope.launch {
                                val requestJob = coroutineContext[Job]
                                cameraSwitching = true
                                cameraBlurTarget = 24f
                                delay(140)
                                try {
                                    localMediaStream = realtimeManager.switchCamera()
                                    cameraRotation.snapTo(0f)
                                    cameraRotation.animateTo(
                                        targetValue = 180f,
                                        animationSpec = tween(500),
                                    )
                                    cameraRotation.snapTo(0f)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Throwable) {
                                    Toast.makeText(
                                        context,
                                        error.message ?: "摄像头切换失败",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } finally {
                                    withContext(NonCancellable) {
                                        cameraRotation.snapTo(0f)
                                        cameraBlurTarget = 0f
                                        delay(180)
                                    }
                                    if (cameraSwitchJob === requestJob) {
                                        cameraSwitching = false
                                        cameraSwitchJob = null
                                    }
                                }
                            }
                        }
                    },
                ) {
                    Text("⟳", color = Color.White, fontSize = 27.sp, lineHeight = 27.sp)
                }
            } else {
                MediaTopMenu(
                    showsAudioControls = currentSource is RealtimeSource.Video,
                    localAudioVolume = localAudioVolume,
                    remoteAudioVolume = remoteAudioVolume,
                    isMuted = isAudioMuted,
                    isVolumeMenuVisible = isAudioVolumeMenuVisible,
                    modifier = Modifier
                        .statusBarsPadding()
                        .align(Alignment.TopEnd)
                        .padding(end = 12.dp, top = 8.dp),
                    onVolumeClick = {
                        isAudioVolumeMenuVisible = !isAudioVolumeMenuVisible
                    },
                    onVolumeMenuDismiss = { isAudioVolumeMenuVisible = false },
                    onLocalVolumeChange = ::setLocalAudioVolume,
                    onRemoteVolumeChange = ::setRemoteAudioVolume,
                    onMuteClick = { setAudioMuted(!isAudioMuted) },
                    onGalleryClick = {
                        isAudioVolumeMenuVisible = false
                        launchPicker(
                            if (currentSource is RealtimeSource.Image) {
                                PickerTarget.LOCAL_IMAGE
                            } else {
                                PickerTarget.LOCAL_VIDEO
                            },
                        )
                    },
                )
            }
        }

        RealtimeControlPanel(
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            selectedReferenceId = selectedReferenceId,
            localReferences = localReferences,
            prompt = prompt,
            promptReference = promptReference,
            moxActive = moxActive,
            canStop = demoGenerationActive,
            onStop = ::stopDemoGeneration,
            onCategorySelected = {
                focusManager.clearFocus()
                selectedCategoryId = it
            },
            onReferenceSelected = { referenceId ->
                if (!generationBusy) {
                    focusManager.clearFocus()
                    val selecting = selectedReferenceId != referenceId
                    selectedReferenceId = if (selecting) referenceId else null
                    moxActive = false
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    if (selecting) {
                        realtimeReferenceCategories
                            .flatMap(ReferenceCategory::references)
                            .firstOrNull { it.id == referenceId }
                            ?.let { reference ->
                                startOrUpdateGeneration(
                                    RealtimeContext(
                                        prompt = reference.prompt,
                                        referencePath = reference.defaultReferenceUrl,
                                    ),
                                )
                            }
                    } else {
                        stopDemoGeneration()
                    }
                }
            },
            onLocalReferenceSelected = { reference ->
                if (!generationBusy) {
                    focusManager.clearFocus()
                    when (reference.uploadState) {
                        ReferenceUploadState.UPLOADING -> Unit
                        ReferenceUploadState.FAILED -> uploadLocalReference(reference)
                        ReferenceUploadState.READY -> {
                            val selecting = selectedReferenceId != reference.id
                            selectedReferenceId = if (selecting) reference.id else null
                            moxActive = false
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            if (selecting && reference.remoteUrl != null) {
                                startOrUpdateGeneration(
                                    RealtimeContext(
                                        prompt = promptForReferenceCategory(reference.categoryId),
                                        referencePath = reference.remoteUrl,
                                    ),
                                )
                            } else {
                                stopDemoGeneration()
                            }
                        }
                    }
                }
            },
            onAddReference = { categoryId ->
                focusManager.clearFocus()
                launchPicker(PickerTarget.CATEGORY_REFERENCE, categoryId)
            },
            onPromptChange = { prompt = it },
            onPromptSubmit = {
                val normalized = prompt.trim()
                if (normalized.isNotEmpty() && !generationBusy) {
                    prompt = normalized
                    selectedReferenceId = null
                    moxActive = false
                    focusManager.clearFocus()
                    startOrUpdateGeneration(
                        RealtimeContext(
                            prompt = normalized,
                            referencePath = promptReference?.remoteUrl,
                        ),
                    )
                }
            },
            onPromptReferenceClick = {
                focusManager.clearFocus()
                if (promptReference == null) {
                    launchPicker(PickerTarget.PROMPT_REFERENCE)
                } else if (promptReference?.uploadState != ReferenceUploadState.UPLOADING) {
                    promptReference = null
                }
            },
            onMoxClick = {
                focusManager.clearFocus()
                val sourceImageReady = currentSource !is RealtimeSource.Image ||
                    sourceImageUploadState == ReferenceUploadState.READY
                if (!moxActive && !generationBusy && sourceImageReady) {
                    selectedReferenceId = null
                    moxActive = true
                    val selectedSource = currentSource
                    startGenerationRequest {
                        val referencePath = if (selectedSource is RealtimeSource.Image) {
                            requireNotNull(sourceImageReferenceUrl)
                        } else {
                            null
                        }
                        RealtimeContext(
                            prompt = "让画面自然动起来",
                            referencePath = referencePath,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun MediaCanvas(
    source: RealtimeSource,
    mediaStream: RealtimeMediaStream?,
    trajectoryStyle: RealtimeTrajectoryStyle,
    cameraPreviewReady: Boolean,
    generationLoading: Boolean,
    sourceImageUploading: Boolean,
    isSuspendedForBackground: Boolean,
    cameraRotation: Float,
    cameraBlur: Float,
) {
    val isPreviewReady = when (source) {
        RealtimeSource.Camera -> mediaStream?.id == "stream-remote" || cameraPreviewReady
        is RealtimeSource.Image,
        is RealtimeSource.Video,
        -> mediaStream != null
    }
    val modifier = Modifier
        .fillMaxSize()
        .then(
            if (source is RealtimeSource.Camera) {
                Modifier
            } else {
                Modifier
                    .statusBarsPadding()
                    .padding(top = 68.dp)
            },
        )
        .graphicsLayer {
            rotationY = cameraRotation
            cameraDistance = 18f * density
        }
        .blur(cameraBlur.dp)
        .background(Color.Black)

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (source) {
            RealtimeSource.Camera -> SdkMediaPreview(
                stream = mediaStream,
                contentMode = VideoContentMode.FILL,
                trajectoryStyle = trajectoryStyle,
            )
            is RealtimeSource.Image -> SdkMediaPreview(
                stream = mediaStream,
                contentMode = VideoContentMode.FIT,
                trajectoryStyle = trajectoryStyle,
            )
            is RealtimeSource.Video -> SdkMediaPreview(
                stream = mediaStream,
                contentMode = VideoContentMode.FILL,
                trajectoryStyle = trajectoryStyle,
            )
        }
        RealtimeLoadingView(
            isLoading = !isSuspendedForBackground &&
                (!isPreviewReady || sourceImageUploading || generationLoading),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun SdkMediaPreview(
    stream: RealtimeMediaStream?,
    contentMode: VideoContentMode,
    trajectoryStyle: RealtimeTrajectoryStyle,
) {
    val context = LocalContext.current
    val customTrajectoryRenderer = remember(context, trajectoryStyle) {
        if (trajectoryStyle == RealtimeTrajectoryStyle.XLAB_CUSTOM) {
            CustomTrajectoryRenderer(context)
        } else {
            null
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050506)),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { context ->
                XmaxVideoView(context).apply {
                    videoContentMode = contentMode
                    trajectoryRenderer = customTrajectoryRenderer
                }
            },
            update = { view ->
                view.videoContentMode = contentMode
                if (view.trajectoryRenderer !== customTrajectoryRenderer) {
                    view.trajectoryRenderer = customTrajectoryRenderer
                }
                view.track = stream?.videoTrack
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun MediaTopMenu(
    showsAudioControls: Boolean,
    localAudioVolume: Float,
    remoteAudioVolume: Float,
    isMuted: Boolean,
    isVolumeMenuVisible: Boolean,
    modifier: Modifier,
    onVolumeClick: () -> Unit,
    onVolumeMenuDismiss: () -> Unit,
    onLocalVolumeChange: (Float) -> Unit,
    onRemoteVolumeChange: (Float) -> Unit,
    onMuteClick: () -> Unit,
    onGalleryClick: () -> Unit,
) {
    Row(modifier = modifier.height(50.dp)) {
        if (showsAudioControls) {
            Box(modifier = Modifier.size(width = 48.dp, height = 50.dp)) {
                MediaTopAction(
                    label = "音量",
                    modifier = Modifier.fillMaxSize(),
                    onClick = onVolumeClick,
                ) {
                    VolumeSlidersGlyph(Modifier.size(17.dp))
                }
                DropdownMenu(
                    expanded = isVolumeMenuVisible,
                    onDismissRequest = onVolumeMenuDismiss,
                    modifier = Modifier.width(260.dp),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = Color(0xFF1C1C1E),
                ) {
                    AudioVolumeSliderRow(
                        label = "本地音量",
                        value = localAudioVolume,
                        onValueChange = onLocalVolumeChange,
                    )
                    AudioVolumeSliderRow(
                        label = "远端音量",
                        value = remoteAudioVolume,
                        onValueChange = onRemoteVolumeChange,
                    )
                }
            }
            MediaTopAction(
                label = if (isMuted) "静音" else "声音",
                onClick = onMuteClick,
            ) {
                SpeakerGlyph(muted = isMuted, modifier = Modifier.size(17.dp))
            }
        }
        MediaTopAction(label = "相册", onClick = onGalleryClick) {
            AlbumGlyph(Modifier.size(14.dp))
        }
    }
}

@Composable
private fun MediaTopAction(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 50.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 5.dp)
                .size(width = 28.dp, height = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 29.dp)
                .height(16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AudioVolumeSliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${(value * 100f).roundToInt()}%",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f,
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF2E88),
                activeTrackColor = Color(0xFFFF2E88),
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
        )
    }
}

@Composable
private fun VolumeSlidersGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.7.dp.toPx()
        val knobRadius = 2.2.dp.toPx()
        val rows = listOf(0.24f to 0.68f, 0.5f to 0.36f, 0.76f to 0.58f)
        rows.forEach { (yFraction, knobFraction) ->
            val y = size.height * yFraction
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.12f, y),
                end = Offset(size.width * 0.88f, y),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = Color.White,
                radius = knobRadius,
                center = Offset(size.width * knobFraction, y),
            )
        }
    }
}

@Composable
private fun SpeakerGlyph(muted: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = 1.7.dp.toPx()
        val speaker = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.41f)
            lineTo(size.width * 0.32f, size.height * 0.41f)
            lineTo(size.width * 0.53f, size.height * 0.22f)
            lineTo(size.width * 0.53f, size.height * 0.78f)
            lineTo(size.width * 0.32f, size.height * 0.59f)
            lineTo(size.width * 0.12f, size.height * 0.59f)
            close()
        }
        drawPath(speaker, color = Color.White)
        if (muted) {
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.62f, size.height * 0.34f),
                end = Offset(size.width * 0.88f, size.height * 0.66f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.88f, size.height * 0.34f),
                end = Offset(size.width * 0.62f, size.height * 0.66f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        } else {
            val innerWave = Path().apply {
                moveTo(size.width * 0.62f, size.height * 0.35f)
                quadraticTo(
                    size.width * 0.76f,
                    size.height * 0.5f,
                    size.width * 0.62f,
                    size.height * 0.65f,
                )
            }
            val outerWave = Path().apply {
                moveTo(size.width * 0.7f, size.height * 0.24f)
                quadraticTo(
                    size.width * 0.94f,
                    size.height * 0.5f,
                    size.width * 0.7f,
                    size.height * 0.76f,
                )
            }
            drawPath(innerWave, Color.White, style = Stroke(strokeWidth, cap = StrokeCap.Round))
            drawPath(outerWave, Color.White, style = Stroke(strokeWidth, cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun OverlayAction(
    label: String,
    modifier: Modifier,
    containerSize: Dp = 58.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .size(containerSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        if (label != "返回") {
            Text(
                text = label,
                modifier = Modifier.offset(y = (-1).dp),
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RealtimeControlPanel(
    categories: List<ReferenceCategory>,
    selectedCategoryId: String,
    selectedReferenceId: String?,
    localReferences: Map<String, List<LocalReference>>,
    prompt: String,
    promptReference: PromptReference?,
    moxActive: Boolean,
    canStop: Boolean,
    onStop: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onReferenceSelected: (String) -> Unit,
    onLocalReferenceSelected: (LocalReference) -> Unit,
    onAddReference: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onPromptSubmit: () -> Unit,
    onPromptReferenceClick: () -> Unit,
    onMoxClick: () -> Unit,
) {
    val selectedCategory = categories.firstOrNull { it.id == selectedCategoryId } ?: categories.first()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D0E))
            .navigationBarsPadding()
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            StopButton(
                enabled = canStop,
                onClick = onStop,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            LazyRow(
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(categories, key = { it.id }) { category ->
                    Text(
                        text = category.name,
                        modifier = Modifier
                            .height(36.dp)
                            .clickable { onCategorySelected(category.id) }
                            .padding(horizontal = 1.dp, vertical = 10.dp),
                        color = if (category.id == selectedCategoryId) {
                            Color.White
                        } else {
                            Color.White.copy(alpha = 0.48f)
                        },
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = if (category.id == selectedCategoryId) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            when (selectedCategory.input) {
                ReferenceInput.REFERENCES -> ReferenceStrip(
                    category = selectedCategory,
                    localReferences = localReferences[selectedCategory.id].orEmpty(),
                    selectedReferenceId = selectedReferenceId,
                    onRemoteSelect = onReferenceSelected,
                    onLocalSelect = onLocalReferenceSelected,
                    onAdd = { onAddReference(selectedCategory.id) },
                )
                ReferenceInput.INSTRUCTION -> MoxControl(
                    active = moxActive,
                    instruction = selectedCategory.instruction,
                    onClick = onMoxClick,
                )
                ReferenceInput.PROMPT -> PromptControl(
                    prompt = prompt,
                    reference = promptReference,
                    onPromptChange = onPromptChange,
                    onSubmit = onPromptSubmit,
                    onReferenceClick = onPromptReferenceClick,
                )
            }
        }
    }
}

@Composable
private fun StopButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⊘",
            color = Color.White.copy(alpha = if (enabled) 1f else 0.32f),
            fontSize = 13.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Light,
        )
    }
}

@Composable
private fun ReferenceStrip(
    category: ReferenceCategory,
    localReferences: List<LocalReference>,
    selectedReferenceId: String?,
    onRemoteSelect: (String) -> Unit,
    onLocalSelect: (LocalReference) -> Unit,
    onAdd: () -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedItemIndex = selectedReferenceId?.let { referenceId ->
        val localIndex = localReferences.indexOfFirst { it.id == referenceId }
        if (localIndex >= 0) {
            localIndex + 1
        } else {
            category.references.indexOfFirst { it.id == referenceId }
                .takeIf { it >= 0 }
                ?.plus(localReferences.size + 1)
        }
    }

    LaunchedEffect(selectedReferenceId, selectedItemIndex) {
        val targetIndex = selectedItemIndex ?: return@LaunchedEffect
        withFrameNanos { }
        val layoutInfo = listState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
            ?: return@LaunchedEffect
        val viewportCenter =
            (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
        val itemCenter = itemInfo.offset + itemInfo.size / 2f
        listState.animateScrollBy(itemCenter - viewportCenter)
    }

    LazyRow(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "${category.id}-add") {
            AddReferenceCell(onClick = onAdd)
        }
        items(localReferences, key = { it.id }) { item ->
            ReferenceCell(
                model = item.uri,
                title = "自定义参考图",
                selected = selectedReferenceId == item.id,
                uploadState = item.uploadState,
                onClick = {
                    onLocalSelect(item)
                },
            )
        }
        items(category.references, key = { it.id }) { item ->
            ReferenceCell(
                model = item.iconUrl,
                title = item.title,
                selected = selectedReferenceId == item.id,
                uploadState = ReferenceUploadState.READY,
                onClick = {
                    onRemoteSelect(item.id)
                },
            )
        }
    }
}

@Composable
private fun AddReferenceCell(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.realtime_add_reference),
            contentDescription = "添加参考图",
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ReferenceCell(
    model: Any,
    title: String,
    selected: Boolean,
    uploadState: ReferenceUploadState,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color(0xFFFF4F9A) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = model,
            contentDescription = title,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color(0xFF303032)),
            contentScale = ContentScale.Crop,
        )
        when (uploadState) {
            ReferenceUploadState.UPLOADING -> ReferenceUploadOverlay(isFailed = false)
            ReferenceUploadState.FAILED -> ReferenceUploadOverlay(isFailed = true)
            ReferenceUploadState.READY -> Unit
        }
    }
}

@Composable
private fun ReferenceUploadOverlay(isFailed: Boolean) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(Color.Black.copy(alpha = if (isFailed) 0.46f else 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        if (isFailed) {
            Text(
                text = "↻",
                color = Color.White,
                fontSize = 18.sp,
                lineHeight = 18.sp,
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun MoxControl(active: Boolean, instruction: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 14.dp)
            .offset(y = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = if (active) 0.094f else 0.141f))
            .clickable(enabled = !active, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (active) instruction else "点击开始生成",
            color = Color.White.copy(alpha = if (active) 0.4f else 0.85f),
            fontSize = if (active) 11.sp else 12.sp,
        )
    }
}

@Composable
private fun PromptControl(
    prompt: String,
    reference: PromptReference?,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onReferenceClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val isReferenceUploading = reference?.uploadState == ReferenceUploadState.UPLOADING
    val canSubmit = prompt.trim().isNotEmpty() && !isReferenceUploading
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 14.dp)
            .offset(y = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF272728))
            .padding(start = 11.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (canSubmit) onSubmit()
            }),
            decorationBox = { field ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    if (prompt.isEmpty()) {
                        Text("输入你想要的效果", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                    field()
                }
            },
        )
        Box(
            modifier = Modifier
                .padding(start = 10.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(enabled = !isReferenceUploading, onClick = onReferenceClick),
            contentAlignment = Alignment.Center,
        ) {
            if (reference != null) {
                AsyncImage(
                    model = reference.uri,
                    contentDescription = "自由模式参考图，点击删除",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.realtime_prompt_add),
                    contentDescription = "添加自由模式参考图",
                    modifier = Modifier.size(12.dp),
                )
            }
            if (isReferenceUploading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.42f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = Color.White,
                        strokeWidth = 1.5.dp,
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFFFF2E88))
                .graphicsLayer(alpha = if (canSubmit) 1f else 0.2f)
                .clickable(enabled = canSubmit) {
                    focusManager.clearFocus()
                    onSubmit()
                },
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.realtime_prompt_submit),
                contentDescription = "提交自由模式描述",
                modifier = Modifier.size(width = 11.dp, height = 12.dp),
            )
        }
    }
}

@Composable
private fun AlbumGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRoundRect(
            color = Color.White,
            style = Stroke(1.45.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
        )
        drawCircle(
            Color.White,
            radius = 1.45.dp.toPx(),
            center = Offset(size.width * 0.72f, size.height * 0.3f),
        )
        val mountains = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.78f)
            lineTo(size.width * 0.38f, size.height * 0.5f)
            lineTo(size.width * 0.55f, size.height * 0.67f)
            lineTo(size.width * 0.68f, size.height * 0.54f)
            lineTo(size.width * 0.9f, size.height * 0.78f)
        }
        drawPath(
            mountains,
            Color.White,
            style = Stroke(1.45.dp.toPx(), cap = StrokeCap.Round),
        )
    }
}
